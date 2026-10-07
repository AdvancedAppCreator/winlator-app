package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;

final class GlobalSettingsStore {
    private static final String PREFERENCES = "api_global_settings";
    private static final String KEY_SETTINGS = "settings_v1";
    private static final Object LOCK = new Object();

    private final Context context;
    private final SharedPreferences preferences;

    GlobalSettingsStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    JSONObject get() throws JSONException {
        return GameSettingsSchema.defaults(context);
    }

    JSONObject update(JSONObject validatedUpdate)
            throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject current = get();
            String currentHash = GameSettingsSchema.hash(current);
            if (!currentHash.equals(
                    validatedUpdate.getString("baseSettingsSha256")
            )) {
                throw new SettingsConflictException(current);
            }
            JSONObject set = validatedUpdate.getJSONObject("set");
            if (set.has("localization") || set.has("runtime")) {
                throw new IllegalArgumentException(
                        "Global defaults support only ocr, controls, and performance."
                );
            }
            JSONObject updated = GameSettingsSchema.applyUpdate(
                    context,
                    current,
                    set
            );
            JSONObject saved = new JSONObject();
            for (Iterator<String> keys = set.keys(); keys.hasNext(); ) {
                String key = keys.next();
                saved.put(key, updated.get(key));
            }
            JSONObject previousPatch = savedPatch(context);
            for (Iterator<String> keys = previousPatch.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!saved.has(key)) saved.put(key, previousPatch.get(key));
            }
            String previous = preferences.getString(KEY_SETTINGS, null);
            if (!preferences.edit().putString(KEY_SETTINGS, saved.toString()).commit()) {
                SharedPreferences.Editor rollback = preferences.edit();
                if (previous != null) rollback.putString(KEY_SETTINGS, previous);
                else rollback.remove(KEY_SETTINGS);
                rollback.commit();
                throw new IOException("Unable to persist global settings.");
            }
            return updated;
        }
    }

    static JSONObject savedPatch(Context context) throws JSONException {
        String text = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_SETTINGS, "");
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    static final class SettingsConflictException extends IllegalArgumentException {
        final JSONObject current;

        SettingsConflictException(JSONObject current) {
            super("Global settings changed. Refresh them before applying this update.");
            this.current = current;
        }
    }
}
