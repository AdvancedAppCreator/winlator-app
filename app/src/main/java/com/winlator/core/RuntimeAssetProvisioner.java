package com.winlator.core;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RuntimeAssetProvisioner {
    private static final String INSTALL_ROOT = "upstream-runtime";
    private static final String COMPLETE_MARKER = ".complete";
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_ZIP_ENTRY_SIZE = 80L * 1024L * 1024L;
    private static final long STORAGE_RESERVE_BYTES = 16L * 1024L * 1024L;
    private static final Set<String> REDIRECT_HOSTS = new HashSet<>();

    static {
        REDIRECT_HOSTS.add("github.com");
        REDIRECT_HOSTS.add("release-assets.githubusercontent.com");
        REDIRECT_HOSTS.add("objects.githubusercontent.com");
    }

    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    public interface Completion {
        void onComplete(Result result);
    }

    interface InstallValidator {
        boolean isReady(File directory);
    }

    public enum Result {
        SUCCESS,
        CANCELLED,
        NETWORK_ERROR,
        HTTP_ERROR,
        STORAGE_ERROR,
        INVALID_DOWNLOAD,
        INVALID_APK
    }

    private RuntimeAssetProvisioner() {}

    public static File getAsset(Context context, RuntimeAssetManifest.Asset asset) {
        return new File(getInstallDir(context), asset.installedName);
    }

    public static boolean isReady(Context context) {
        return isReady(getInstallDir(context));
    }

    static boolean isReady(File installDir) {
        if (!new File(installDir, COMPLETE_MARKER).isFile()) return false;
        try {
            for (RuntimeAssetManifest.Asset asset : RuntimeAssetManifest.REQUIRED_ASSETS) {
                File file = new File(installDir, asset.installedName);
                if (file.length() != asset.size || !asset.sha256.equals(sha256(file))) {
                    return false;
                }
            }
            return true;
        }
        catch (IOException error) {
            return false;
        }
    }

    public static void provisionAsync(
            Context context,
            AtomicBoolean cancelled,
            Progress progress,
            Completion completion
    ) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> completion.onComplete(
                provision(appContext, cancelled, progress)
        ), "runtime-asset-provisioner").start();
    }

    static Result provision(Context context, AtomicBoolean cancelled, Progress progress) {
        File root = new File(context.getFilesDir(), INSTALL_ROOT);
        File installDir = getInstallDir(context);
        if (!root.isDirectory() && !root.mkdirs()) return Result.STORAGE_ERROR;
        if (!reconcileAbandoned(root, installDir, null, RuntimeAssetProvisioner::isReady)) {
            return Result.STORAGE_ERROR;
        }
        if (isReady(installDir)) return Result.SUCCESS;
        long requiredSpace = RuntimeAssetManifest.APK_SIZE + STORAGE_RESERVE_BYTES;
        for (RuntimeAssetManifest.Asset asset : RuntimeAssetManifest.REQUIRED_ASSETS) {
            requiredSpace += asset.size;
        }
        if (root.getUsableSpace() < requiredSpace) return Result.STORAGE_ERROR;
        File stage = new File(root, ".stage-" + UUID.randomUUID());
        File payload = new File(stage, "payload");
        File apk = new File(stage, RuntimeAssetManifest.APK_NAME);
        if (!payload.mkdirs()) return Result.STORAGE_ERROR;

        try {
            download(apk, cancelled, progress);
            if (cancelled.get()) return Result.CANCELLED;
            if (apk.length() != RuntimeAssetManifest.APK_SIZE
                    || !RuntimeAssetManifest.APK_SHA256.equals(sha256(apk))) {
                return Result.INVALID_DOWNLOAD;
            }
            extractRequiredAssets(apk, payload, cancelled);
            if (cancelled.get()) return Result.CANCELLED;
            writeMarker(payload);
            if (!isReady(payload)) return Result.INVALID_APK;
            if (cancelled.get()) return Result.CANCELLED;
            return commitPayload(payload, installDir, cancelled);
        }
        catch (DownloadException error) {
            return error.result;
        }
        catch (IOException error) {
            return Result.INVALID_APK;
        }
        finally {
            FileUtils.delete(stage);
        }
    }

    static void extractRequiredAssets(File apk, File destination, AtomicBoolean cancelled)
            throws IOException {
        extractAssets(apk, destination, cancelled, RuntimeAssetManifest.REQUIRED_ASSETS);
    }

    static void extractAssets(
            File apk,
            File destination,
            AtomicBoolean cancelled,
            List<RuntimeAssetManifest.Asset> assets
    ) throws IOException {
        Set<String> names = new HashSet<>();
        Set<String> required = new HashSet<>();
        for (RuntimeAssetManifest.Asset asset : assets) {
            required.add(asset.apkPath);
        }

        try (ZipFile zip = new ZipFile(apk)) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                validateZipName(name);
                if (!names.add(name)) throw new IOException("Duplicate APK entry: " + name);
                if (required.contains(name)) {
                    RuntimeAssetManifest.Asset asset = findAsset(name, assets);
                    if (entry.isDirectory()
                            || entry.getSize() != asset.size
                            || entry.getSize() < 0
                            || entry.getSize() > MAX_ZIP_ENTRY_SIZE) {
                        throw new IOException("Invalid required APK entry: " + name);
                    }
                }
            }
            if (!names.containsAll(required)) throw new IOException("Required APK assets are missing.");

            for (RuntimeAssetManifest.Asset asset : assets) {
                if (cancelled.get()) return;
                ZipEntry entry = zip.getEntry(asset.apkPath);
                File output = new File(destination, asset.installedName);
                copyBounded(zip.getInputStream(entry), output, asset.size, cancelled);
                if (cancelled.get()) return;
                if (output.length() != asset.size || !asset.sha256.equals(sha256(output))) {
                    throw new IOException("Required APK asset failed verification: " + asset.apkPath);
                }
            }
        }
    }

    private static void download(File destination, AtomicBoolean cancelled, Progress progress)
            throws DownloadException {
        URL url;
        try {
            url = new URL(RuntimeAssetManifest.APK_URL);
        }
        catch (IOException error) {
            throw new DownloadException(Result.NETWORK_ERROR, error);
        }

        HttpURLConnection connection = null;
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validateUrl(url);
                connection = (HttpURLConnection)url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null) throw new IOException("Redirect has no location.");
                    url = new URL(url, location);
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new DownloadException(Result.HTTP_ERROR, null);
                }
                long declaredLength = connection.getContentLengthLong();
                if (declaredLength != -1 && declaredLength != RuntimeAssetManifest.APK_SIZE) {
                    throw new DownloadException(Result.INVALID_DOWNLOAD, null);
                }
                streamDownload(connection, destination, cancelled, progress);
                return;
            }
            throw new IOException("Too many HTTPS redirects.");
        }
        catch (IOException error) {
            if (destination.exists() && !destination.delete()) {
                throw new DownloadException(Result.STORAGE_ERROR, error);
            }
            throw new DownloadException(Result.NETWORK_ERROR, error);
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void streamDownload(
            HttpURLConnection connection,
            File destination,
            AtomicBoolean cancelled,
            Progress progress
    ) throws IOException, DownloadException {
        long total = 0;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while (!cancelled.get() && (read = input.read(buffer)) != -1) {
                total += read;
                if (total > RuntimeAssetManifest.APK_SIZE) {
                    throw new IOException("Download exceeded its pinned size.");
                }
                output.write(buffer, 0, read);
                if (progress != null) progress.onProgress(total, RuntimeAssetManifest.APK_SIZE);
            }
        }
        catch (java.io.FileNotFoundException error) {
            throw new DownloadException(Result.STORAGE_ERROR, error);
        }
        if (cancelled.get()) {
            if (destination.exists() && !destination.delete()) {
                throw new DownloadException(Result.STORAGE_ERROR, null);
            }
            return;
        }
        if (total != RuntimeAssetManifest.APK_SIZE) {
            throw new IOException("Download byte count does not match the pin.");
        }
    }

    private static void copyBounded(
            InputStream source,
            File destination,
            long expectedSize,
            AtomicBoolean cancelled
    ) throws IOException {
        long total = 0;
        try (InputStream input = source;
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while (!cancelled.get() && (read = input.read(buffer)) != -1) {
                total += read;
                if (total > expectedSize) throw new IOException("APK entry exceeded its size limit.");
                output.write(buffer, 0, read);
            }
        }
        if (!cancelled.get() && total != expectedSize) {
            throw new IOException("APK entry byte count mismatch.");
        }
    }

    private static void validateZipName(String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("Absolute APK entry path.");
        }
        for (String part : normalized.split("/")) {
            if ("..".equals(part)) throw new IOException("Parent traversal in APK entry.");
        }
    }

    private static RuntimeAssetManifest.Asset findAsset(
            String apkPath,
            List<RuntimeAssetManifest.Asset> assets
    ) throws IOException {
        for (RuntimeAssetManifest.Asset asset : assets) {
            if (asset.apkPath.equals(apkPath)) return asset;
        }
        throw new IOException("Unexpected required asset.");
    }

    private static void writeMarker(File payload) throws IOException {
        String marker = RuntimeAssetManifest.UPSTREAM_REPOSITORY + "\n"
                + RuntimeAssetManifest.UPSTREAM_VERSION + "\n"
                + RuntimeAssetManifest.APK_SHA256 + "\n";
        try (OutputStream output = new FileOutputStream(new File(payload, COMPLETE_MARKER))) {
            output.write(marker.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static File getInstallDir(Context context) {
        return new File(
                new File(context.getFilesDir(), INSTALL_ROOT),
                RuntimeAssetManifest.UPSTREAM_VERSION
        );
    }

    static Result commitPayload(File payload, File installDir, AtomicBoolean cancelled) {
        if (cancelled.get()) return Result.CANCELLED;
        File previous = null;
        if (installDir.exists()) {
            if (cancelled.get()) return Result.CANCELLED;
            previous = new File(
                    installDir.getParentFile(),
                    ".previous-" + UUID.randomUUID()
            );
            if (cancelled.get()) return Result.CANCELLED;
            if (!installDir.renameTo(previous)) return Result.STORAGE_ERROR;
        }
        if (cancelled.get()) {
            return restorePrevious(previous, installDir)
                    ? Result.CANCELLED
                    : Result.STORAGE_ERROR;
        }
        if (!payload.renameTo(installDir)) {
            restorePrevious(previous, installDir);
            return Result.STORAGE_ERROR;
        }
        if (cancelled.get()) {
            return rollbackCommittedInstall(previous, installDir);
        }
        if (previous != null) FileUtils.delete(previous);
        return Result.SUCCESS;
    }

    private static Result rollbackCommittedInstall(File previous, File installDir) {
        if (!FileUtils.delete(installDir)) return Result.STORAGE_ERROR;
        if (previous != null && !previous.renameTo(installDir)) return Result.STORAGE_ERROR;
        return Result.CANCELLED;
    }

    static boolean reconcileAbandoned(
            File root,
            File installDir,
            File activeStage,
            InstallValidator validator
    ) {
        File[] entries = root.listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (entry.getName().startsWith(".stage-")
                    && (activeStage == null || !entry.equals(activeStage))
                    && !FileUtils.delete(entry)) {
                return false;
            }
        }

        File[] previous = root.listFiles(file -> file.getName().startsWith(".previous-"));
        if (previous == null) return false;
        Arrays.sort(previous, Comparator
                .comparingLong(File::lastModified)
                .reversed()
                .thenComparing(File::getName));

        if (!validator.isReady(installDir)) {
            File recovery = null;
            for (File candidate : previous) {
                if (validator.isReady(candidate)) {
                    recovery = candidate;
                    break;
                }
            }
            if (recovery == null) return true;
            if (installDir.exists() && !FileUtils.delete(installDir)) return false;
            if (!recovery.renameTo(installDir) || !validator.isReady(installDir)) return false;
        }

        for (File backup : previous) {
            if (backup.exists() && !FileUtils.delete(backup)) return false;
        }
        return true;
    }

    private static boolean restorePrevious(File previous, File installDir) {
        return previous == null
                || (previous.exists()
                && !installDir.exists()
                && previous.renameTo(installDir));
    }

    private static void validateUrl(URL url) throws IOException {
        String host = url.getHost().toLowerCase(Locale.US);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !REDIRECT_HOSTS.contains(host)) {
            throw new IOException("Unsafe upstream redirect.");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format(Locale.US, "%02x", item));
        return value.toString();
    }

    private static final class DownloadException extends Exception {
        final Result result;

        DownloadException(Result result, Throwable cause) {
            super(cause);
            this.result = result;
        }
    }
}
