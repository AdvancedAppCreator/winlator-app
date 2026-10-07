package com.winlator.core;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

public final class DownloadIntegrity {
    public static final String REVISION = "b6b2259158cf38d06067c34430d840d56b46d220";
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/brunodev85/winlator/" + REVISION + "/";
    private static volatile JSONObject files;

    private DownloadIntegrity() {}

    public static String url(String path) {
        return BASE_URL + path.replace(" ", "%20");
    }

    public static Entry get(Context context, String path) throws IOException {
        try {
            if (files == null) {
                synchronized (DownloadIntegrity.class) {
                    if (files == null) {
                        JSONObject manifest = new JSONObject(
                                FileUtils.readString(context, "download-integrity.json")
                        );
                        if (!REVISION.equals(manifest.getString("revision"))) {
                            throw new IOException("Download manifest revision mismatch.");
                        }
                        files = manifest.getJSONObject("files");
                    }
                }
            }
            JSONObject item = files.getJSONObject(path);
            return new Entry(item.getString("sha256"), item.getLong("size"));
        }
        catch (JSONException | NullPointerException error) {
            throw new IOException("No pinned integrity metadata for " + path + ".", error);
        }
    }

    public static void downloadFile(
            Context context,
            String path,
            String sourceUrl,
            File destination,
            Callback<Boolean> callback
    ) {
        try {
            Entry entry = get(context, path);
            HttpUtils.downloadVerifiedAsync(
                    sourceUrl,
                    destination,
                    entry.sha256,
                    entry.size,
                    callback
            );
        }
        catch (IOException error) {
            callback.call(false);
        }
    }

    public static void downloadText(
            Context context,
            String path,
            Callback<String> callback
    ) {
        try {
            Entry entry = get(context, path);
            File destination = new File(
                    context.getCacheDir(),
                    "verified-downloads/" + entry.sha256
            );
            HttpUtils.downloadVerifiedAsync(
                    url(path),
                    destination,
                    entry.sha256,
                    entry.size,
                    success -> {
                        String content = success ? FileUtils.readString(destination) : null;
                        callback.call(content);
                    }
            );
        }
        catch (IOException error) {
            callback.call(null);
        }
    }

    public static final class Entry {
        public final String sha256;
        public final long size;

        private Entry(String sha256, long size) {
            this.sha256 = sha256;
            this.size = size;
        }
    }
}
