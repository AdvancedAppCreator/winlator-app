package com.winlator.api;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.StartupLog;
import com.winlator.core.WineUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class UnityLaunchOverlay {
    static final String CONFIG_KEY = "unityTextureLimit";
    static final String DISABLED = "off";
    private static final String DATA_SUFFIX = "_Data";
    private static final String BURST_SUFFIX = "_BurstDebugInformation_DoNotShip";
    private static final String APP_INFO = "app.info";
    private static final Charset CP437 = charsetOrNull("IBM437");
    private static final Charset CP932 = charsetOrNull("windows-31j");

    private UnityLaunchOverlay() {
    }

    static File prepare(
            Context context,
            ManagedGame game,
            Container container,
            JSONObject config
    ) {
        int limit = parseLimit(config.optString(CONFIG_KEY, DISABLED));
        File gameOverlayRoot = new File(
                new File(container.getRootDir(), ".wine/drive_c/.winlator-unity-overlays"),
                safeName(game.id)
        );

        try {
            File executable = resolveExecutable(game, container);
            if (executable == null || !executable.isFile()) {
                if (limit == 0) {
                    pruneDisabledOverlay(gameOverlayRoot);
                    return null;
                }
                throw new IOException("The managed Unity executable is unavailable.");
            }
            File originalRoot = executable.getParentFile();
            if (originalRoot == null
                    || !new File(originalRoot, "UnityPlayer.dll").isFile()) {
                pruneDisabledOverlay(gameOverlayRoot);
                return null;
            }
            File dataDirectory = findDataDirectory(originalRoot, executable);
            if (dataDirectory == null) {
                if (limit == 0) {
                    pruneDisabledOverlay(gameOverlayRoot);
                    return null;
                }
                throw new UnitySerializedFilePatcher.UnsupportedFormatException(
                        "Unity data directory was not found."
                );
            }
            NameRepair nameRepair = detectNameRepair(originalRoot, executable, dataDirectory);
            if (limit == 0) {
                if (nameRepair == null) {
                    pruneDisabledOverlay(gameOverlayRoot);
                    return null;
                }
                return prepareNameRepairOverlay(
                        game,
                        executable,
                        originalRoot,
                        dataDirectory,
                        nameRepair,
                        gameOverlayRoot
                );
            }

            try {
                return prepareTextureOverlay(
                        game,
                        executable,
                        originalRoot,
                        dataDirectory,
                        nameRepair,
                        gameOverlayRoot,
                        limit
                );
            }
            catch (IOException | JSONException | NoSuchAlgorithmException
                    | SecurityException textureError) {
                if (nameRepair == null) throw textureError;
                StartupLog.log(
                        "Unity texture patch unavailable; continuing with filename repair"
                                +" game="+game.id
                                +" limit="+limit,
                        textureError
                );
                try {
                    return prepareNameRepairOverlay(
                            game,
                            executable,
                            originalRoot,
                            dataDirectory,
                            nameRepair,
                            gameOverlayRoot
                    );
                }
                catch (IOException | NoSuchAlgorithmException repairError) {
                    repairError.addSuppressed(textureError);
                    throw repairError;
                }
            }
        }
        catch (IOException | JSONException | NoSuchAlgorithmException
                | SecurityException error) {
            StartupLog.log(
                    "Unity launch overlay unavailable; launching the original game unchanged"
                            +" game="+game.id
                            +" limit="+limit,
                    error
            );
            return null;
        }
    }

    private static File prepareTextureOverlay(
            ManagedGame game,
            File executable,
            File originalRoot,
            File dataDirectory,
            NameRepair nameRepair,
            File gameOverlayRoot,
            int limit
    ) throws IOException, JSONException, NoSuchAlgorithmException {
        File globalManagers = new File(dataDirectory, "globalgamemanagers");
        if (!globalManagers.isFile()) {
            throw new UnitySerializedFilePatcher.UnsupportedFormatException(
                    "Unity globalgamemanagers was not found."
            );
        }

        String sourceHash = sha256(globalManagers);
        File cacheRoot = new File(gameOverlayRoot, "cache");
        File cacheEntry = new File(cacheRoot, sourceHash+"-limit-"+limit);
        File patchedManagers = new File(cacheEntry, "globalgamemanagers");
        File metadataFile = new File(cacheEntry, "metadata.json");
        if (!isValidCacheEntry(metadataFile, patchedManagers, sourceHash, limit)) {
            if (cacheEntry.exists() && !FileUtils.delete(cacheEntry)) {
                throw new IOException("Unable to replace an invalid Unity overlay cache.");
            }
            UnitySerializedFilePatcher.PatchResult patch =
                    UnitySerializedFilePatcher.patchTextureQuality(globalManagers, limit);
            if (!cacheEntry.isDirectory() && !cacheEntry.mkdirs()) {
                throw new IOException("Unable to create Unity overlay cache.");
            }
            if (!FileUtils.writeBytesAtomic(patchedManagers, patch.data)) {
                throw new IOException("Unable to write patched Unity settings.");
            }
            JSONObject metadata = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("sourceSha256", sourceHash)
                    .put("textureLimit", limit)
                    .put("unityVersion", patch.unityVersion)
                    .put("serializedFileVersion", patch.serializedFileVersion)
                    .put("patchedFieldCount", patch.patchedOffsets.size());
            if (!FileUtils.writeStringAtomic(metadataFile, metadata.toString(2))) {
                FileUtils.delete(cacheEntry);
                throw new IOException("Unable to write Unity overlay metadata.");
            }
        }

        File shadowsRoot = new File(gameOverlayRoot, "shadows");
        File shadowRoot = new File(
                shadowsRoot,
                sourceHash+"-limit-"+limit+nameRepairKey(nameRepair)
        );
        File shadowExecutable = new File(
                shadowRoot,
                nameRepair != null
                        ? nameRepair.canonicalExecutableName()
                        : executable.getName()
        );
        File shadowManagers = new File(
                new File(shadowRoot, dataDirectory.getName()),
                globalManagers.getName()
        );
        if ((!shadowExecutable.exists()
                || !shadowManagers.isFile()
                || (nameRepair != null && !isNameRepairComplete(
                        shadowRoot,
                        executable,
                        dataDirectory,
                        nameRepair
                )))
                && shadowRoot.exists()
                && !FileUtils.delete(shadowRoot)) {
            throw new IOException("Unable to replace an incomplete Unity shadow root.");
        }
        if (!shadowRoot.exists()) {
            if (!shadowRoot.mkdirs()) {
                throw new IOException("Unable to create Unity shadow root.");
            }
            buildShadow(
                    originalRoot,
                    dataDirectory,
                    globalManagers,
                    patchedManagers,
                    shadowRoot,
                    executable,
                    nameRepair
            );
        }
        pruneCache(cacheRoot, cacheEntry);
        pruneCache(shadowsRoot, shadowRoot);

        if (!shadowExecutable.exists()) {
            throw new IOException("Unity shadow executable was not created.");
        }
        StartupLog.log(
                "Prepared Unity texture overlay game="+game.id
                        +" limit="+limit
                        +" nameRepair="+(nameRepair != null)
                        +" source="+globalManagers
                        +" shadow="+shadowExecutable
        );
        return shadowExecutable;
    }

    static int parseLimit(String value) {
        if (value == null || value.isEmpty() || DISABLED.equals(value) || "0".equals(value)) {
            return 0;
        }
        try {
            int limit = Integer.parseInt(value);
            return limit >= 1 && limit <= 3 ? limit : 0;
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static File resolveExecutable(ManagedGame game, Container container) {
        if (game.executablePath != null) return new File(game.executablePath);
        if (game.executableDosPath == null) return null;
        String path = WineUtils.dosToUnixPath(game.executableDosPath, container);
        return !path.isEmpty() ? new File(path) : null;
    }

    private static File findDataDirectory(File root, File executable) {
        if (root == null) return null;
        String expected = baseName(executable.getName())+DATA_SUFFIX;
        File exact = new File(root, expected);
        if (exact.isDirectory()) return exact;
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory() && expected.equalsIgnoreCase(child.getName())) return child;
        }
        return null;
    }

    static NameRepair detectNameRepair(
            File originalRoot,
            File executable,
            File dataDirectory
    ) throws IOException {
        String sourceBaseName = baseName(executable.getName());
        File appInfo = new File(dataDirectory, APP_INFO);
        if (!appInfo.isFile()) return null;

        String productName;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(appInfo),
                StandardCharsets.UTF_8
        ))) {
            reader.readLine();
            productName = reader.readLine();
        }
        if (productName == null) return null;
        productName = productName.trim();
        if (!isSafeWindowsBaseName(productName) || productName.equals(sourceBaseName)) {
            return null;
        }
        String decoded = decodeCp437AsCp932(sourceBaseName);
        if (!productName.equals(decoded)) return null;

        File canonicalExecutable = new File(originalRoot, productName+".exe");
        File canonicalData = new File(originalRoot, productName+DATA_SUFFIX);
        File canonicalBurst = new File(originalRoot, productName+BURST_SUFFIX);
        if (canonicalExecutable.exists() || canonicalData.exists() || canonicalBurst.exists()) {
            return null;
        }

        File sourceBurst = new File(originalRoot, sourceBaseName+BURST_SUFFIX);
        return new NameRepair(
                sourceBaseName,
                productName,
                sourceBurst.isDirectory()
        );
    }

    static String decodeCp437AsCp932(String value) {
        if (value == null || value.isEmpty()) return null;
        if (CP437 == null || CP932 == null) return null;
        try {
            ByteBuffer bytes = CP437
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            return CP932
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        }
        catch (CharacterCodingException error) {
            return null;
        }
    }

    private static File prepareNameRepairOverlay(
            ManagedGame game,
            File executable,
            File originalRoot,
            File dataDirectory,
            NameRepair nameRepair,
            File gameOverlayRoot
    ) throws IOException, NoSuchAlgorithmException {
        File cacheRoot = new File(gameOverlayRoot, "cache");
        if (cacheRoot.exists() && !FileUtils.delete(cacheRoot)) {
            throw new IOException("Unable to prune disabled Unity texture cache.");
        }
        File shadowsRoot = new File(gameOverlayRoot, "shadows");
        File shadowRoot = new File(
                shadowsRoot,
                "name-"+nameRepairToken(nameRepair)
        );
        File shadowExecutable = new File(shadowRoot, nameRepair.canonicalExecutableName());
        if (!isNameRepairComplete(shadowRoot, executable, dataDirectory, nameRepair)
                && shadowRoot.exists()
                && !FileUtils.delete(shadowRoot)) {
            throw new IOException("Unable to replace an incomplete Unity name-repair shadow.");
        }
        if (!shadowRoot.exists()) {
            if (!shadowRoot.mkdirs()) {
                throw new IOException("Unable to create Unity name-repair shadow.");
            }
            buildNameRepairShadow(
                    originalRoot,
                    executable,
                    dataDirectory,
                    shadowRoot,
                    nameRepair
            );
        }
        pruneCache(shadowsRoot, shadowRoot);
        if (!shadowExecutable.exists()) {
            throw new IOException("Unity name-repair executable alias was not created.");
        }
        StartupLog.log(
                "Prepared Unity filename repair overlay game="+game.id
                        +" source="+executable.getName()
                        +" canonical="+nameRepair.canonicalExecutableName()
                        +" shadow="+shadowExecutable
        );
        return shadowExecutable;
    }

    private static boolean isNameRepairComplete(
            File shadowRoot,
            File executable,
            File dataDirectory,
            NameRepair nameRepair
    ) {
        if (!new File(shadowRoot, executable.getName()).exists()
                || !new File(shadowRoot, dataDirectory.getName()).isDirectory()
                || !new File(shadowRoot, nameRepair.canonicalExecutableName()).exists()
                || !new File(shadowRoot, nameRepair.canonicalDataName()).isDirectory()) {
            return false;
        }
        return !nameRepair.hasBurstDirectory
                || new File(shadowRoot, nameRepair.canonicalBurstName()).isDirectory();
    }

    private static void buildShadow(
            File originalRoot,
            File dataDirectory,
            File globalManagers,
            File patchedManagers,
            File shadowRoot,
            File executable,
            NameRepair nameRepair
    ) throws IOException {
        File[] rootChildren = originalRoot.listFiles();
        if (rootChildren == null) throw new IOException("Unable to list the Unity game root.");
        for (File child : rootChildren) {
            File shadowChild = new File(shadowRoot, child.getName());
            if (child.equals(dataDirectory)) {
                if (!shadowChild.mkdirs()) {
                    throw new IOException("Unable to create the Unity shadow data directory.");
                }
                File[] dataChildren = dataDirectory.listFiles();
                if (dataChildren == null) {
                    throw new IOException("Unable to list the Unity data directory.");
                }
                for (File dataChild : dataChildren) {
                    File shadowDataChild = new File(shadowChild, dataChild.getName());
                    if (dataChild.equals(globalManagers)) {
                        Files.copy(
                                patchedManagers.toPath(),
                                shadowDataChild.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                    else createVerifiedSymlink(dataChild, shadowDataChild);
                }
            }
            else createVerifiedSymlink(child, shadowChild);
        }
        if (nameRepair != null) {
            addNameRepairAliases(
                    executable,
                    dataDirectory,
                    shadowRoot,
                    nameRepair
            );
        }
    }

    private static void buildNameRepairShadow(
            File originalRoot,
            File executable,
            File dataDirectory,
            File shadowRoot,
            NameRepair nameRepair
    ) throws IOException {
        File[] rootChildren = originalRoot.listFiles();
        if (rootChildren == null) throw new IOException("Unable to list the Unity game root.");
        for (File child : rootChildren) {
            createVerifiedSymlink(child, new File(shadowRoot, child.getName()));
        }
        addNameRepairAliases(executable, dataDirectory, shadowRoot, nameRepair);
    }

    private static void addNameRepairAliases(
            File executable,
            File dataDirectory,
            File shadowRoot,
            NameRepair nameRepair
    ) throws IOException {
        File shadowSourceExecutable = new File(shadowRoot, executable.getName());
        File shadowSourceData = new File(shadowRoot, dataDirectory.getName());
        createVerifiedSymlink(
                shadowSourceExecutable,
                new File(shadowRoot, nameRepair.canonicalExecutableName())
        );
        createVerifiedSymlink(
                shadowSourceData,
                new File(shadowRoot, nameRepair.canonicalDataName())
        );
        if (nameRepair.hasBurstDirectory) {
            createVerifiedSymlink(
                    new File(shadowRoot, nameRepair.sourceBaseName+BURST_SUFFIX),
                    new File(shadowRoot, nameRepair.canonicalBurstName())
            );
        }
    }

    private static void createVerifiedSymlink(File target, File link) throws IOException {
        FileUtils.symlink(target, link);
        if (!FileUtils.isSymlink(link) || !link.exists()) {
            throw new IOException("Unable to create Unity overlay symlink "+link);
        }
    }

    private static void pruneCache(File cacheRoot, File keep) {
        File[] entries = cacheRoot.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.equals(keep) && !FileUtils.delete(entry)) {
                StartupLog.log("Unable to prune stale Unity overlay cache "+entry);
            }
        }
    }

    static void pruneDisabledOverlay(File gameOverlayRoot) {
        if (gameOverlayRoot.exists() && !FileUtils.delete(gameOverlayRoot)) {
            StartupLog.log("Unable to prune disabled Unity overlay "+gameOverlayRoot);
        }
    }

    static boolean isValidCacheEntry(
            File metadataFile,
            File patchedManagers,
            String sourceHash,
            int limit
    ) {
        if (!metadataFile.isFile() || !patchedManagers.isFile()) return false;
        try {
            JSONObject metadata = new JSONObject(FileUtils.readString(metadataFile));
            return metadata.optInt("schemaVersion", 0) == 1
                    && sourceHash.equalsIgnoreCase(metadata.optString("sourceSha256"))
                    && metadata.optInt("textureLimit", 0) == limit
                    && metadata.optInt("patchedFieldCount", 0) > 0;
        }
        catch (JSONException | RuntimeException error) {
            return false;
        }
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input =
                     new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value));
        }
        return result.toString();
    }

    private static String sha256Text(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte item : bytes) result.append(String.format(Locale.US, "%02x", item));
        return result.toString();
    }

    private static String baseName(String filename) {
        int extension = filename.lastIndexOf('.');
        return extension > 0 ? filename.substring(0, extension) : filename;
    }

    private static boolean isSafeWindowsBaseName(String value) {
        if (value.isEmpty() || value.length() > 120
                || value.endsWith(" ") || value.endsWith(".")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (item < 32 || "\\/:*?\"<>|".indexOf(item) >= 0) return false;
        }
        String upper = value.toUpperCase(Locale.US);
        if (upper.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) return false;
        return !".".equals(value) && !"..".equals(value);
    }

    private static String nameRepairKey(NameRepair repair) throws NoSuchAlgorithmException {
        return repair != null ? "-name-"+nameRepairToken(repair) : "";
    }

    private static String nameRepairToken(NameRepair repair)
            throws NoSuchAlgorithmException {
        return sha256Text(
                repair.sourceBaseName+"\n"+repair.canonicalBaseName
        ).substring(0, 16);
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        }
        catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String safeName(String value) {
        return value != null ? value.replaceAll("[^A-Za-z0-9._-]", "_") : "unknown";
    }

    static final class NameRepair {
        final String sourceBaseName;
        final String canonicalBaseName;
        final boolean hasBurstDirectory;

        NameRepair(
                String sourceBaseName,
                String canonicalBaseName,
                boolean hasBurstDirectory
        ) {
            this.sourceBaseName = sourceBaseName;
            this.canonicalBaseName = canonicalBaseName;
            this.hasBurstDirectory = hasBurstDirectory;
        }

        String canonicalExecutableName() {
            return canonicalBaseName+".exe";
        }

        String canonicalDataName() {
            return canonicalBaseName+DATA_SUFFIX;
        }

        String canonicalBurstName() {
            return canonicalBaseName+BURST_SUFFIX;
        }
    }
}
