package com.winlator.text;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public final class GameTextCacheManager {
    private GameTextCacheManager() {
    }

    public static JSONObject status(Context context, String namespace)
            throws JSONException, IOException {
        return GameTextTranslationCache.status(context, namespace);
    }

    public static void clear(Context context, String namespace) {
        GameTextTranslationCache.clear(context, namespace);
    }
}
