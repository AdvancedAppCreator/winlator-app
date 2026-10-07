package com.winlator.text;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class GameTextTranslationCache implements AutoCloseable {
    static final int DEFAULT_MAX_ENTRIES = 1000;
    private static final int FILE_VERSION = 1;
    private static final String ENGINE_VERSION = "mlkit-translate-17.0.3";

    private final AtomicFile file;
    private final int maxEntries;
    private final LinkedHashMap<String, String> entries =
            new LinkedHashMap<>(32, 0.75f, true);
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final AtomicBoolean writeScheduled = new AtomicBoolean();
    private volatile boolean closed;

    GameTextTranslationCache(Context context, String namespace, int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        file = cacheFile(context, namespace);
        load();
    }

    static JSONObject status(Context context, String namespace)
            throws JSONException, IOException {
        AtomicFile file = cacheFile(context, namespace);
        JSONObject result = new JSONObject()
                .put("entries", 0)
                .put("bytes", file.getBaseFile().isFile()
                        ? file.getBaseFile().length()
                        : 0);
        if (!file.getBaseFile().isFile()) return result;
        JSONObject root = new JSONObject(
                new String(file.readFully(), StandardCharsets.UTF_8)
        );
        JSONArray entries = root.optJSONArray("entries");
        result.put("entries", entries != null ? entries.length() : 0);
        result.put("engineVersion", root.optString("engineVersion", ""));
        return result;
    }

    static void clear(Context context, String namespace) {
        cacheFile(context, namespace).delete();
    }

    synchronized String get(
            String sourceLanguage,
            String targetLanguage,
            String glossaryVersion,
            String sourceText
    ) {
        return entries.get(key(
                sourceLanguage,
                targetLanguage,
                glossaryVersion,
                sourceText
        ));
    }

    synchronized void put(
            String sourceLanguage,
            String targetLanguage,
            String glossaryVersion,
            String sourceText,
            String translatedText
    ) {
        if (closed || translatedText == null) return;
        entries.put(
                key(sourceLanguage, targetLanguage, glossaryVersion, sourceText),
                translatedText
        );
        trim();
        scheduleWrite();
    }

    synchronized void clear() {
        entries.clear();
        scheduleWrite();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        scheduleWrite();
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ");
    }

    private synchronized void load() {
        if (!file.getBaseFile().isFile()) return;
        try {
            JSONObject root = new JSONObject(
                    new String(file.readFully(), StandardCharsets.UTF_8)
            );
            if (root.optInt("version", 0) != FILE_VERSION ||
                    !ENGINE_VERSION.equals(root.optString("engineVersion", ""))) {
                return;
            }
            JSONArray storedEntries = root.optJSONArray("entries");
            if (storedEntries == null) return;
            int start = Math.max(0, storedEntries.length() - maxEntries);
            for (int index = start; index < storedEntries.length(); index++) {
                JSONObject entry = storedEntries.optJSONObject(index);
                if (entry == null) continue;
                String key = entry.optString("key", "");
                if (!key.isEmpty()) entries.put(key, entry.optString("value", ""));
            }
        }
        catch (IOException | JSONException ignored) {
            entries.clear();
        }
    }

    private void scheduleWrite() {
        if (!writeScheduled.compareAndSet(false, true)) return;
        writer.execute(() -> {
            while (true) {
                writeScheduled.set(false);
                writeSnapshot();
                if (!writeScheduled.get()) return;
            }
        });
    }

    private void writeSnapshot() {
        JSONObject root = new JSONObject();
        try {
            root.put("version", FILE_VERSION);
            root.put("engineVersion", ENGINE_VERSION);
            JSONArray values = new JSONArray();
            synchronized (this) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    values.put(new JSONObject()
                            .put("key", entry.getKey())
                            .put("value", entry.getValue()));
                }
            }
            root.put("entries", values);
        }
        catch (JSONException error) {
            return;
        }

        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        }
        catch (IOException error) {
            if (output != null) file.failWrite(output);
        }
    }

    private synchronized void trim() {
        Iterator<String> keys = entries.keySet().iterator();
        while (entries.size() > maxEntries && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private static String key(
            String sourceLanguage,
            String targetLanguage,
            String glossaryVersion,
            String sourceText
    ) {
        return sha256(
                ENGINE_VERSION + '\n'
                        + normalize(sourceLanguage).toLowerCase(Locale.ROOT) + '\n'
                        + normalize(targetLanguage).toLowerCase(Locale.ROOT) + '\n'
                        + normalize(glossaryVersion) + '\n'
                        + normalize(sourceText)
        );
    }

    private static AtomicFile cacheFile(Context context, String namespace) {
        File directory = new File(context.getFilesDir(), "game-text/translation-cache");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create the translation cache directory.");
        }
        String safeNamespace = sha256(namespace != null && !namespace.isEmpty()
                ? namespace
                : "default");
        return new AtomicFile(new File(directory, safeNamespace + ".json"));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }
}
