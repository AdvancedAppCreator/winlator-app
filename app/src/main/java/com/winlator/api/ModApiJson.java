package com.winlator.api;

import android.content.Context;

import com.winlator.XServerDisplayActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

final class ModApiJson {
    private ModApiJson() {
    }

    static JSONObject status(Context context, ManagedGame game)
            throws IOException, JSONException {
        ModManifest manifest = ModManifest.load(
                ModPaths.manifestFile(context.getFilesDir(), game.id)
        );
        JSONArray mods = new JSONArray();
        for (ModEntry entry : manifest.mods) mods.put(entry.toJSON());
        JSONObject conflicts = new JSONObject();
        for (Map.Entry<String, ArrayList<String>> conflict :
                conflicts(manifest.mods).entrySet()) {
            JSONArray owners = new JSONArray();
            for (String owner : conflict.getValue()) owners.put(owner);
            conflicts.put(conflict.getKey(), owners);
        }

        JSONObject result = new JSONObject()
                .put("gameId", game.id)
                .put("mods", mods)
                .put("conflicts", conflicts)
                .put("sessionActive", XServerDisplayActivity.isSessionActive())
                .put("available", true);
        try {
            File root = GameModRootResolver.resolve(context, game);
            result.put("targetRoot", root.getCanonicalPath());
        }
        catch (IOException error) {
            result.put("available", false);
            result.put("unavailableReason", error.getMessage());
        }
        return result;
    }

    private static HashMap<String, ArrayList<String>> conflicts(
            ArrayList<ModEntry> mods
    ) {
        HashMap<String, ArrayList<String>> owners = new HashMap<>();
        for (ModEntry mod : mods) {
            if (!ModEntry.STATE_ENABLED.equals(mod.state)) continue;
            for (String path : mod.files) {
                ArrayList<String> values = owners.get(path);
                if (values == null) {
                    values = new ArrayList<>();
                    owners.put(path, values);
                }
                values.add(mod.id);
            }
        }
        owners.entrySet().removeIf(entry -> entry.getValue().size() < 2);
        return owners;
    }
}
