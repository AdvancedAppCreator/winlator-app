package com.winlator.core;

import android.app.Activity;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.File;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class HttpUtils {
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> TRUSTED_DOWNLOAD_HOSTS = new HashSet<>(Arrays.asList(
            "download.microsoft.com",
            "download.visualstudio.microsoft.com",
            "raw.githubusercontent.com"
    ));
    private static final ConcurrentHashMap<String, Object> DOWNLOAD_LOCKS =
            new ConcurrentHashMap<>();

    public interface VerifiedDownloadProgress {
        void onProgress(long downloaded, long total);
    }

    public static void downloadVerified(
            String sourceUrl,
            File destination,
            String expectedSha256,
            long expectedSize,
            VerifiedDownloadProgress progress
    ) throws IOException {
        String key = destination.getAbsolutePath();
        Object lock = DOWNLOAD_LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            downloadVerifiedLocked(
                    sourceUrl,
                    destination,
                    expectedSha256,
                    expectedSize,
                    progress
            );
        }
    }

    private static void downloadVerifiedLocked(
            String sourceUrl,
            File destination,
            String expectedSha256,
            long expectedSize,
            VerifiedDownloadProgress progress
    ) throws IOException {
        if (expectedSize <= 0) throw new IllegalArgumentException("Expected size must be positive.");
        String expectedHash = expectedSha256.toLowerCase(Locale.US);
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Unable to create download directory.");
        }
        if (destination.isFile()) {
            if (destination.length() == expectedSize
                    && expectedHash.equals(sha256(destination))) {
                if (progress != null) progress.onProgress(expectedSize, expectedSize);
                return;
            }
            if (!destination.delete()) throw new IOException("Unable to replace invalid download.");
        }

        File partial = new File(destination.getPath() + ".part");
        long offset = partial.isFile() ? partial.length() : 0;
        if (offset == expectedSize) {
            if (expectedHash.equals(sha256(partial))) {
                if (!partial.renameTo(destination)) {
                    throw new IOException("Unable to finalize verified partial download.");
                }
                if (progress != null) progress.onProgress(expectedSize, expectedSize);
                return;
            }
            if (!partial.delete()) {
                throw new IOException("Unable to reset invalid partial download.");
            }
            offset = 0;
        }
        if (offset < 0 || offset > expectedSize) {
            if (!partial.delete()) throw new IOException("Unable to reset invalid partial download.");
            offset = 0;
        }

        HttpURLConnection connection = openTrustedConnection(sourceUrl, offset);
        int status = connection.getResponseCode();
        boolean resumed = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL;
        if (status != HttpURLConnection.HTTP_OK && !resumed) {
            connection.disconnect();
            throw new IOException("Download failed with HTTP " + status + ".");
        }
        if (offset > 0 && !resumed) {
            if (!partial.delete()) {
                connection.disconnect();
                throw new IOException("Unable to restart partial download.");
            }
            offset = 0;
        }

        MessageDigest digest = newDigest();
        if (resumed) updateDigest(digest, partial);
        long total = offset;
        try (InputStream input = new BufferedInputStream(
                connection.getInputStream(),
                StreamUtils.BUFFER_SIZE
        );
             OutputStream output = new FileOutputStream(partial, resumed)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > expectedSize) {
                    throw new IOException("Download exceeded the expected size.");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                if (progress != null) progress.onProgress(total, expectedSize);
            }
        }
        finally {
            connection.disconnect();
        }

        if (total != expectedSize) {
            throw new IOException("Download size mismatch.");
        }
        String actualHash = hex(digest.digest());
        if (!expectedHash.equals(actualHash)) {
            if (!partial.delete()) {
                throw new IOException("Download hash mismatch and partial cleanup failed.");
            }
            throw new IOException("Download hash mismatch.");
        }
        if (!partial.renameTo(destination)) {
            throw new IOException("Unable to finalize verified download.");
        }
    }

    public static boolean verifySha256(File file, String expectedSha256) throws IOException {
        return file.isFile()
                && expectedSha256.toLowerCase(Locale.US).equals(sha256(file));
    }

    public static void downloadVerifiedAsync(
            String sourceUrl,
            File destination,
            String expectedSha256,
            long expectedSize,
            Callback<Boolean> onDownloadComplete
    ) {
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = false;
            try {
                downloadVerified(
                        sourceUrl,
                        destination,
                        expectedSha256,
                        expectedSize,
                        (VerifiedDownloadProgress)null
                );
                success = true;
            }
            catch (IOException | IllegalArgumentException error) {
                Log.e("HttpUtils", "Verified download failed: " + sourceUrl, error);
            }
            onDownloadComplete.call(success);
        });
    }

    private static HttpURLConnection openTrustedConnection(String sourceUrl, long offset)
            throws IOException {
        URL url = new URL(sourceUrl);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            validateTrustedUrl(url);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307
                    || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Download redirect has no location.");
                url = new URL(url, location);
                continue;
            }
            return connection;
        }
        throw new IOException("Too many download redirects.");
    }

    private static void validateTrustedUrl(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !TRUSTED_DOWNLOAD_HOSTS.contains(url.getHost().toLowerCase(Locale.US))) {
            throw new IOException("Untrusted prerequisite download URL.");
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        updateDigest(digest, file);
        return hex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }

    private static void downloadAsync(String url, Callback<String> onDownloadComplete) {
        try {
            HttpURLConnection connection = (HttpURLConnection)(new URL(url)).openConnection();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                onDownloadComplete.call(null);
                return;
            }

            byte[] bytes;
            try (InputStream inStream = connection.getInputStream()) {
                bytes = StreamUtils.copyToByteArray(inStream);
            }
            onDownloadComplete.call(new String(bytes, StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            onDownloadComplete.call(null);
        }
    }

    public static void download(final String url, final Callback<String> onDownloadComplete) {
        Executors.newSingleThreadExecutor().execute(() -> downloadAsync(url, onDownloadComplete));
    }

    private static void downloadAsync(String url, File destination, AtomicBoolean interruptRef, Callback<Integer> onPublishProgress, Callback<Boolean> onDownloadComplete) {
        try {
            interruptRef.set(false);
            HttpURLConnection connection = (HttpURLConnection)(new URL(url)).openConnection();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                onDownloadComplete.call(false);
                return;
            }

            int contentLength = connection.getContentLength();
            try (InputStream inStream = new BufferedInputStream(connection.getInputStream(), StreamUtils.BUFFER_SIZE);
                 OutputStream outStream = new FileOutputStream(destination)) {

                byte[] buffer = new byte[1024];
                int totalSize = 0;
                int bytesRead;
                while ((bytesRead = inStream.read(buffer)) != -1 && !interruptRef.get()) {
                    totalSize += bytesRead;
                    if (onPublishProgress != null) {
                        int progress = (int)(((float)totalSize / contentLength) * 100);
                        onPublishProgress.call(progress);
                    }
                    outStream.write(buffer, 0, bytesRead);
                }

            }

            onDownloadComplete.call(!interruptRef.get());
        }
        catch (Exception e) {
            onDownloadComplete.call(false);
        }
    }

    public static void download(final Activity activity, final String url, final File destination, final Callback<Boolean> onDownloadComplete) {
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        final AtomicBoolean interruptRef = new AtomicBoolean();
        dialog.show(() -> interruptRef.set(true));
        Executors.newSingleThreadExecutor().execute(() -> {
            downloadAsync(url, destination, interruptRef, (progress) -> {
                activity.runOnUiThread(() -> {
                    dialog.setProgress(progress);
                });
            }, (success) -> {
                if (!success && destination.isFile()) destination.delete();
                activity.runOnUiThread(() -> {
                    dialog.close();
                    onDownloadComplete.call(success);
                });
            });
        });
    }
}
