package com.winlator.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Bounded, crash-safe JSON manifest stored at
 * {@code <filesDir>/mods/<safeGameId>/manifest.json}.
 *
 * <p>Safety properties:</p>
 * <ul>
 *   <li>Maximum {@value #MAX_MOD_COUNT} mods.</li>
 *   <li>Maximum {@value #MAX_MANIFEST_BYTES} bytes on disk.</li>
 *   <li>Atomic writes via a temp file + rename.</li>
 *   <li>Entries left in {@code applying} or {@code rolling_back} state are
 *       normalised to {@code disabled} on load so that ModManager can
 *       perform filesystem recovery without re-reading stale state.</li>
 * </ul>
 */
final class ModManifest {
    static final int SCHEMA_VERSION   = 1;
    static final int MAX_MOD_COUNT    = 64;
    static final int MAX_MANIFEST_BYTES = 512 * 1024; // 512 KB

    final ArrayList<ModEntry> mods = new ArrayList<>();
    final ArrayList<String> baselineMissing = new ArrayList<>();
    final ArrayList<String> interruptedPaths = new ArrayList<>();
    boolean interruptedStateRecovered;

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    static ModManifest load(File manifestFile) throws IOException, JSONException {
        ModManifest m = new ModManifest();
        if (!manifestFile.exists()) return m;

        byte[] raw = Files.readAllBytes(manifestFile.toPath());
        if (raw.length > MAX_MANIFEST_BYTES) {
            throw new IOException("Mod manifest exceeds size limit ("
                    + raw.length + " > " + MAX_MANIFEST_BYTES + " bytes).");
        }
        JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        int version = root.optInt("schemaVersion", 0);
        if (version != SCHEMA_VERSION) {
            throw new IOException("Unsupported mod manifest schema version: " + version);
        }
        JSONArray modsArr = root.optJSONArray("mods");
        if (modsArr != null) {
            for (int i = 0; i < modsArr.length(); i++) {
                ModEntry entry = ModEntry.fromJSON(modsArr.getJSONObject(i));
                // Normalise interrupted states so ModManager can recover them.
                if (ModEntry.STATE_APPLYING.equals(entry.state)
                        || ModEntry.STATE_ROLLING_BACK.equals(entry.state)) {
                    entry.state = ModEntry.STATE_DISABLED;
                    m.interruptedStateRecovered = true;
                    m.interruptedPaths.addAll(entry.files);
                }
                m.mods.add(entry);
            }
        }
        JSONArray missing = root.optJSONArray("baselineMissing");
        if (missing != null) {
            for (int index = 0; index < missing.length(); index++) {
                m.baselineMissing.add(missing.getString(index));
            }
        }
        return m;
    }

    void save(File manifestFile) throws IOException, JSONException {
        if (mods.size() > MAX_MOD_COUNT) {
            throw new IOException("Mod count exceeds limit ("
                    + mods.size() + " > " + MAX_MOD_COUNT + ").");
        }
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < mods.size(); i++) arr.put(mods.get(i).toJSON());
        root.put("mods", arr);
        JSONArray missing = new JSONArray();
        for (String path : baselineMissing) missing.put(path);
        root.put("baselineMissing", missing);

        byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_MANIFEST_BYTES) {
            throw new IOException("Serialised mod manifest exceeds size limit ("
                    + data.length + " > " + MAX_MANIFEST_BYTES + " bytes).");
        }
        File parent = manifestFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create mod directory: " + parent);
        }
        File tmp = new File(parent, "." + manifestFile.getName()
                + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp.toPath(), data);
            try {
                Files.move(tmp.toPath(), manifestFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), manifestFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | SecurityException e) {
            tmp.delete();
            throw new IOException("Failed to save mod manifest: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    ModEntry findById(String id) {
        for (int i = 0; i < mods.size(); i++) {
            if (mods.get(i).id.equals(id)) return mods.get(i);
        }
        return null;
    }

    void remove(String id) {
        for (int i = mods.size() - 1; i >= 0; i--) {
            if (mods.get(i).id.equals(id)) {
                mods.remove(i);
                return;
            }
        }
    }

    /** Returns the next load-order value (one above the current maximum). */
    int nextLoadOrder() {
        int max = -1;
        for (int i = 0; i < mods.size(); i++) {
            if (mods.get(i).loadOrder > max) max = mods.get(i).loadOrder;
        }
        return max + 1;
    }
}
