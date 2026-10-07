package com.winlator.api;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.container.ContainerOperationLock;
import com.winlator.core.StreamUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Per-managed-game mod manager.
 *
 * <h3>Storage layout</h3>
 * <pre>
 *   &lt;filesDir&gt;/mods/&lt;safeGameId&gt;/
 *     manifest.json          – bounded mod registry
 *     staging/&lt;modId&gt;/      – extracted mod files
 *     backups/_baseline/     – originals captured before the first active owner
 * </pre>
 *
 * <h3>Thread safety</h3>
 * All filesystem mutations are performed inside
 * {@code synchronized (ContainerOperationLock.LOCK)}.
 * Callers should invoke {@link #enableMod}, {@link #disableMod}, etc. from a
 * background thread; ModManagerActivity does this via an Executor.
 *
 * <h3>Crash / interrupted-apply recovery</h3>
 * Entries whose state was {@code applying} or {@code rolling_back} are
 * normalised to {@code disabled} by {@link ModManifest#load} before this
 * class even sees them.  {@link #recoverDisabledStates} then scans for mods
 * that are recorded as {@code disabled} but whose staging directory is absent
 * (e.g. after a force-stop mid-import) and removes those phantom entries.
 *
 * <h3>Conflict semantics</h3>
 * Conflicts are informational: enabling a mod that shares files with another
 * enabled mod is allowed but reported in {@link #buildConflictMap}.  The mod
 * with the higher {@link ModEntry#loadOrder} wins on disk.
 */
public class ModManager {
    private static final String TAG = "ModManager";

    /** Outcome returned by every mutation method. */
    public static final class Result {
        public final boolean success;
        public final String  error;

        private Result(boolean success, String error) {
            this.success = success;
            this.error   = error;
        }

        static Result ok()                { return new Result(true, null);   }
        static Result fail(String error)  { return new Result(false, error); }
    }

    private final Context    context;
    private final ManagedGame game;
    private final File       modsDir;
    private final File       manifestFile;

    /**
     * Creates a ModManager for the given game and performs lightweight
     * recovery of any phantom manifest entries (staging dir absent).
     */
    public ModManager(Context context, ManagedGame game) {
        this.context      = context.getApplicationContext();
        this.game         = game;
        File filesDir     = this.context.getFilesDir();
        this.modsDir      = ModPaths.modsDir(filesDir, game.id);
        this.manifestFile = ModPaths.manifestFile(filesDir, game.id);
        try {
            synchronized (ContainerOperationLock.LOCK) {
                recoverDisabledStates();
            }
        }
        catch (Exception error) {
            Log.e(TAG, "Unable to recover the mod manifest", error);
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the current mod list (load-order ascending).
     */
    public ArrayList<ModEntry> listMods() {
        synchronized (ContainerOperationLock.LOCK) {
            try {
                ModManifest manifest = loadManifest();
                sortByLoadOrder(manifest.mods);
                return new ArrayList<>(manifest.mods);
            }
            catch (Exception error) {
                throw new IllegalStateException("Unable to load the mod manifest.", error);
            }
        }
    }

    /**
     * Imports a mod from a content URI pointing to a ZIP file.
     *
     * <p>This method copies the stream, extracts it, validates entries, and
     * registers the mod in the manifest.  It does NOT apply the mod to the
     * game root (the mod starts in {@code pending} state).</p>
     *
     * @param zipUri    Content URI for the ZIP (from ACTION_OPEN_DOCUMENT).
     * @param modName   Display name; if blank, derived from the URI's last path segment.
     */
    public Result importMod(Uri zipUri, String modName) {
        String mutationToken = GameManagerActivity.reserveInternalMutation();
        if (mutationToken == null) {
            return Result.fail("Cannot import a mod while Winlator is busy.");
        }
        String resolvedName = (modName != null && !modName.isEmpty())
                ? modName
                : displayNameFromUri(zipUri);
        String modId = UUID.randomUUID().toString();
        File stagingDir = ModPaths.stagingDir(context.getFilesDir(), game.id, modId);

        File stagingZip = new File(modsDir, ".import_" + modId + ".zip");
        boolean imported = false;
        try {
            if (!modsDir.exists() && !modsDir.mkdirs()) {
                return Result.fail("Cannot create mod directory.");
            }
            // 1. Copy URI stream to private storage.
            InputStream source = context.getContentResolver().openInputStream(zipUri);
            if (source == null) return Result.fail("The selected mod ZIP could not be opened.");
            try (InputStream in = new BufferedInputStream(source);
                 BufferedOutputStream out =
                         new BufferedOutputStream(new FileOutputStream(stagingZip))) {
                if (!StreamUtils.copy(in, out)) {
                    return Result.fail("Failed to copy mod ZIP to private storage.");
                }
            }

            // 2. Extract safely.
            ArrayList<String> files;
            try {
                files = ModZipExtractor.extract(stagingZip, stagingDir);
            } catch (IOException e) {
                return Result.fail("ZIP extraction failed: " + e.getMessage());
            }
            if (files.isEmpty()) {
                return Result.fail("The mod ZIP contains no extractable files.");
            }

            // 3. Register in manifest.
            synchronized (ContainerOperationLock.LOCK) {
                ModManifest manifest = loadManifest();
                if (manifest.mods.size() >= ModManifest.MAX_MOD_COUNT) {
                    return Result.fail("Maximum mod count ("
                            + ModManifest.MAX_MOD_COUNT + ") reached.");
                }
                ModEntry entry = new ModEntry();
                entry.id             = modId;
                entry.name           = resolvedName;
                entry.sourceFileName = displayNameFromUri(zipUri);
                entry.installedAt    = System.currentTimeMillis();
                entry.state          = ModEntry.STATE_PENDING;
                entry.loadOrder      = manifest.nextLoadOrder();
                entry.files          = files;
                manifest.mods.add(entry);
                manifest.save(manifestFile);
            }
            imported = true;
            return Result.ok();
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            return Result.fail("Import failed: " + e.getMessage());
        } finally {
            if (!imported) deleteDir(stagingDir);
            stagingZip.delete();
            GameManagerActivity.releaseInternalMutation(mutationToken);
        }
    }

    /**
     * Enables (applies) a mod to the game root.
     *
     * <p>Files already on disk are backed up before being overwritten. Paths
     * that did not previously exist are recorded in the manifest so rollback
     * can delete them.</p>
     *
     * <p>This method is idempotent: enabling an already-enabled mod re-applies
     * its files (useful after a partial apply was recovered).</p>
     */
    public Result enableMod(String modId) {
        String mutationToken = GameManagerActivity.reserveInternalMutation();
        if (mutationToken == null) {
            return Result.fail("Cannot modify game files while Winlator is busy.");
        }
        try {
            synchronized (ContainerOperationLock.LOCK) {
                File gameRoot = resolveGameRoot();
                ModManifest manifest = loadManifest();
                ModEntry entry = manifest.findById(modId);
                if (entry == null) {
                    return Result.fail("Mod not found: " + modId);
                }
                File stagingDir = ModPaths.stagingDir(context.getFilesDir(), game.id, modId);
                if (!stagingDir.isDirectory()) {
                    return Result.fail("Mod staging directory missing; re-import the mod.");
                }
                String previousState = entry.state;
                entry.state = ModEntry.STATE_APPLYING;
                manifest.save(manifestFile);
                HashSet<String> affected = new HashSet<>(entry.files);
                try {
                    for (String relativePath : affected) {
                        File source = containedFile(stagingDir, relativePath);
                        if (!source.isFile()) {
                            throw new IOException(
                                    "Mod staging file is missing: " + relativePath
                            );
                        }
                        ensureBaseline(
                                manifest,
                                gameRoot,
                                relativePath,
                                !ModEntry.STATE_ENABLED.equals(previousState)
                        );
                    }
                    manifest.save(manifestFile);
                    entry.state = ModEntry.STATE_ENABLED;
                    applyWinningFiles(manifest, gameRoot, affected);
                    manifest.save(manifestFile);
                    return Result.ok();
                }
                catch (IOException | org.json.JSONException error) {
                    entry.state = previousState;
                    try {
                        applyWinningFiles(manifest, gameRoot, affected);
                        manifest.save(manifestFile);
                    }
                    catch (Exception rollbackError) {
                        return Result.fail(
                                "Enable failed and rollback was incomplete: " +
                                        rollbackError.getMessage()
                        );
                    }
                    return Result.fail("Enable failed: " + error.getMessage());
                }
            }
        }
        catch (IOException | org.json.JSONException e) {
                return Result.fail("Enable failed: " + e.getMessage());
        }
        finally {
            GameManagerActivity.releaseInternalMutation(mutationToken);
        }
    }

    /**
     * Disables (rolls back) a mod from the game root.
     *
     * <p>Backed-up originals are restored for each file unless a different
     * enabled mod with a <em>higher</em> load order also owns that file (in
     * which case the higher mod's version should stay on disk).</p>
     */
    public Result disableMod(String modId) {
        String mutationToken = GameManagerActivity.reserveInternalMutation();
        if (mutationToken == null) {
            return Result.fail("Cannot modify game files while Winlator is busy.");
        }
        try {
            synchronized (ContainerOperationLock.LOCK) {
                return disableLocked(modId);
            }
        }
        catch (IOException | org.json.JSONException e) {
                return Result.fail("Disable failed: " + e.getMessage());
        }
        finally {
            GameManagerActivity.releaseInternalMutation(mutationToken);
        }
    }

    /**
     * Removes a mod entirely.  If the mod is currently enabled it is first
     * disabled.  Staging and backup directories are deleted.
     */
    public Result removeMod(String modId) {
        String mutationToken = GameManagerActivity.reserveInternalMutation();
        if (mutationToken == null) {
            return Result.fail("Cannot modify game files while Winlator is busy.");
        }
        try {
            synchronized (ContainerOperationLock.LOCK) {
                ModManifest manifest = loadManifest();
                ModEntry entry = manifest.findById(modId);
                if (entry == null) return Result.ok();

                if (ModEntry.STATE_ENABLED.equals(entry.state)) {
                    Result r = disableLocked(modId);
                    if (!r.success) return r;
                    manifest = loadManifest();
                }

                manifest.remove(modId);
                manifest.save(manifestFile);

                deleteDir(ModPaths.stagingDir(context.getFilesDir(), game.id, modId));
                deleteDir(ModPaths.backupDir(context.getFilesDir(), game.id, modId));
                return Result.ok();
            }
        }
        catch (IOException | org.json.JSONException e) {
                return Result.fail("Remove failed: " + e.getMessage());
        }
        finally {
            GameManagerActivity.releaseInternalMutation(mutationToken);
        }
    }

    /**
     * Builds a map of {@code relativePath → list of modIds} for every file
     * claimed by more than one currently-enabled mod.
     */
    public HashMap<String, ArrayList<String>> buildConflictMap() {
        ArrayList<ModEntry> mods = listMods();
        HashMap<String, ArrayList<String>> fileOwners = new HashMap<>();
        for (int i = 0; i < mods.size(); i++) {
            ModEntry e = mods.get(i);
            if (!ModEntry.STATE_ENABLED.equals(e.state)) continue;
            for (int j = 0; j < e.files.size(); j++) {
                String f = e.files.get(j);
                ArrayList<String> owners = fileOwners.get(f);
                if (owners == null) {
                    owners = new ArrayList<>();
                    fileOwners.put(f, owners);
                }
                owners.add(e.id);
            }
        }
        // Keep only files with more than one owner.
        HashMap<String, ArrayList<String>> conflicts = new HashMap<>();
        for (Map.Entry<String, ArrayList<String>> kv : fileOwners.entrySet()) {
            if (kv.getValue().size() > 1) {
                conflicts.put(kv.getKey(), kv.getValue());
            }
        }
        return conflicts;
    }

    /**
     * Returns the set of mod IDs that are involved in at least one conflict.
     */
    public ArrayList<String> conflictingModIds() {
        HashMap<String, ArrayList<String>> map = buildConflictMap();
        ArrayList<String> ids = new ArrayList<>();
        for (Map.Entry<String, ArrayList<String>> kv : map.entrySet()) {
            ArrayList<String> owners = kv.getValue();
            for (int i = 0; i < owners.size(); i++) {
                String id = owners.get(i);
                if (!ids.contains(id)) ids.add(id);
            }
        }
        return ids;
    }

    // =========================================================================
    // Recovery
    // =========================================================================

    /**
     * Removes manifest entries whose staging directory no longer exists.
     * Called once on construction under {@code ContainerOperationLock}.
     */
    private void recoverDisabledStates() throws IOException, org.json.JSONException {
        ModManifest manifest = loadManifest();
        boolean changed = manifest.interruptedStateRecovered;
        HashSet<String> affected = new HashSet<>(manifest.interruptedPaths);
        for (int i = manifest.mods.size() - 1; i >= 0; i--) {
            ModEntry e = manifest.mods.get(i);
            File stagingDir = ModPaths.stagingDir(context.getFilesDir(), game.id, e.id);
            if (!stagingDir.isDirectory()) {
                if (ModEntry.STATE_ENABLED.equals(e.state)) {
                    affected.addAll(e.files);
                }
                // Staging was wiped (e.g. app data cleared) – remove entry.
                manifest.mods.remove(i);
                changed = true;
            }
        }
        if (changed) {
            applyWinningFiles(manifest, resolveGameRoot(), affected);
            manifest.save(manifestFile);
        }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private ModManifest loadManifest() throws IOException {
        try {
            return ModManifest.load(manifestFile);
        } catch (org.json.JSONException e) {
            throw new IOException("Corrupt mod manifest: " + e.getMessage(), e);
        }
    }

    private File resolveGameRoot() throws IOException {
        return GameModRootResolver.resolve(context, game);
    }

    private Result disableLocked(String modId)
            throws IOException, org.json.JSONException {
        File gameRoot = resolveGameRoot();
        ModManifest manifest = loadManifest();
        ModEntry entry = manifest.findById(modId);
        if (entry == null) return Result.fail("Mod not found: " + modId);
        if (ModEntry.STATE_DISABLED.equals(entry.state) ||
                ModEntry.STATE_PENDING.equals(entry.state)) {
            return Result.ok();
        }

        entry.state = ModEntry.STATE_ROLLING_BACK;
        manifest.save(manifestFile);
        HashSet<String> affected = new HashSet<>(entry.files);
        try {
            entry.state = ModEntry.STATE_DISABLED;
            applyWinningFiles(manifest, gameRoot, affected);
            manifest.save(manifestFile);
            return Result.ok();
        }
        catch (IOException | org.json.JSONException error) {
            entry.state = ModEntry.STATE_ENABLED;
            try {
                applyWinningFiles(manifest, gameRoot, affected);
                manifest.save(manifestFile);
            }
            catch (Exception rollbackError) {
                return Result.fail(
                        "Disable failed and rollback was incomplete: " +
                                rollbackError.getMessage()
                );
            }
            return Result.fail("Disable failed: " + error.getMessage());
        }
    }

    private void ensureBaseline(
            ModManifest manifest,
            File gameRoot,
            String relativePath,
            boolean refreshWhenUnowned
    ) throws IOException {
        File baseline = baselineFile(relativePath);
        boolean baselineKnown = baseline.isFile() ||
                manifest.baselineMissing.contains(relativePath);
        boolean unowned = highestEnabledOwner(manifest, relativePath) == null;
        if (!refreshWhenUnowned || !unowned) {
            if (baselineKnown) return;
            throw new IOException(
                    "The original file baseline is unavailable: " + relativePath
            );
        }
        manifest.baselineMissing.remove(relativePath);
        if (baseline.exists() && !baseline.delete()) {
            throw new IOException(
                    "Unable to refresh the original file baseline: " +
                            relativePath
            );
        }
        File target = containedFile(gameRoot, relativePath);
        if (target.isDirectory()) {
            throw new IOException(
                    "A mod file conflicts with an existing directory: " +
                            relativePath
            );
        }
        if (target.isFile()) {
            copyFile(target, baseline);
        }
        else {
            manifest.baselineMissing.add(relativePath);
        }
    }

    private void applyWinningFiles(
            ModManifest manifest,
            File gameRoot,
            HashSet<String> relativePaths
    ) throws IOException {
        for (String relativePath : relativePaths) {
            ModEntry winner = highestEnabledOwner(manifest, relativePath);
            File target = containedFile(gameRoot, relativePath);
            if (winner != null) {
                File source = containedFile(
                        ModPaths.stagingDir(
                                context.getFilesDir(),
                                game.id,
                                winner.id
                        ),
                        relativePath
                );
                if (!source.isFile()) {
                    throw new IOException(
                            "Enabled mod staging file is missing: " + relativePath
                    );
                }
                copyFile(source, target);
                continue;
            }
            File baseline = baselineFile(relativePath);
            if (baseline.isFile()) {
                copyFile(baseline, target);
            }
            else if (manifest.baselineMissing.contains(relativePath)) {
                if (target.exists() && !target.delete()) {
                    throw new IOException(
                            "Unable to remove mod-created file: " + relativePath
                    );
                }
            }
            else {
                throw new IOException(
                        "The original file baseline is unavailable: " + relativePath
                );
            }
        }
    }

    private ModEntry highestEnabledOwner(
            ModManifest manifest,
            String relativePath
    ) {
        ModEntry winner = null;
        for (ModEntry candidate : manifest.mods) {
            if (!ModEntry.STATE_ENABLED.equals(candidate.state) ||
                    !candidate.files.contains(relativePath)) {
                continue;
            }
            if (winner == null || candidate.loadOrder > winner.loadOrder) {
                winner = candidate;
            }
        }
        return winner;
    }

    private File baselineFile(String relativePath) throws IOException {
        File baselineRoot = new File(
                ModPaths.backupDir(context.getFilesDir(), game.id, "_baseline"),
                "files"
        );
        return containedFile(baselineRoot, relativePath);
    }

    private static File containedFile(File root, String relativePath)
            throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File candidate = new File(canonicalRoot, relativePath).getCanonicalFile();
        if (!ModPaths.isContainedIn(canonicalRoot, candidate)) {
            throw new IOException("Mod path escapes its approved root: " + relativePath);
        }
        return candidate;
    }

    private static void sortByLoadOrder(ArrayList<ModEntry> list) {
        // Insertion sort – mod lists are typically small.
        for (int i = 1; i < list.size(); i++) {
            ModEntry key = list.get(i);
            int j = i - 1;
            while (j >= 0 && list.get(j).loadOrder > key.loadOrder) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, key);
        }
    }

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        ensureParent(dst);
        File temporary = new File(
                dst.getParentFile(),
                "." + dst.getName() + ".modtmp-" + UUID.randomUUID()
        );
        try (BufferedInputStream in =
                     new BufferedInputStream(new java.io.FileInputStream(src));
             BufferedOutputStream out =
                     new BufferedOutputStream(new FileOutputStream(temporary))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        try {
            Files.move(
                    temporary.toPath(),
                    dst.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(
                    temporary.toPath(),
                    dst.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
        finally {
            temporary.delete();
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) deleteDir(child);
            }
        }
        dir.delete();
    }

    private static String displayNameFromUri(Uri uri) {
        if (uri == null) return "Unknown mod";
        String last = uri.getLastPathSegment();
        if (last == null) return "Unknown mod";
        int slash = last.lastIndexOf('/');
        if (slash >= 0) last = last.substring(slash + 1);
        // Strip .zip extension.
        if (last.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            last = last.substring(0, last.length() - 4);
        }
        return last.isEmpty() ? "Unknown mod" : last;
    }
}
