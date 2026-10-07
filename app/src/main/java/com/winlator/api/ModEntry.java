package com.winlator.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** Represents a single installed mod for a managed game. */
final class ModEntry {
    static final String STATE_PENDING     = "pending";
    static final String STATE_ENABLED     = "enabled";
    static final String STATE_DISABLED    = "disabled";
    static final String STATE_APPLYING    = "applying";
    static final String STATE_ROLLING_BACK = "rolling_back";

    String id;
    String name;
    String sourceFileName;
    long installedAt;
    String state = STATE_PENDING;
    /** Ascending order; last-applied (highest) wins on file conflicts. */
    int loadOrder;
    /** Game-root-relative paths of every file this mod installs. */
    ArrayList<String> files = new ArrayList<>();
    JSONObject toJSON() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("sourceFileName", sourceFileName);
        obj.put("installedAt", installedAt);
        obj.put("state", state);
        obj.put("loadOrder", loadOrder);
        JSONArray filesArr = new JSONArray();
        for (int i = 0; i < files.size(); i++) filesArr.put(files.get(i));
        obj.put("files", filesArr);
        return obj;
    }

    static ModEntry fromJSON(JSONObject obj) throws JSONException {
        ModEntry e = new ModEntry();
        e.id             = obj.getString("id");
        e.name           = obj.getString("name");
        e.sourceFileName = obj.optString("sourceFileName", "");
        e.installedAt    = obj.optLong("installedAt", 0);
        e.state          = obj.optString("state", STATE_PENDING);
        e.loadOrder      = obj.optInt("loadOrder", 0);
        JSONArray filesArr = obj.optJSONArray("files");
        if (filesArr != null) {
            for (int i = 0; i < filesArr.length(); i++) e.files.add(filesArr.getString(i));
        }
        return e;
    }
}
