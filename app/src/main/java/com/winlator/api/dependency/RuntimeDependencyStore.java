package com.winlator.api.dependency;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Persists {@link DependencyInstallRecord}s keyed by {@code containerId:dependencyId}
 * in a dedicated SharedPreferences file.
 *
 * <p>All mutations are serialised through {@code synchronized(this)} to avoid torn writes.
 */
final class RuntimeDependencyStore {

    private static final String PREFS_NAME   = "runtime_dep_store";
    private static final String KEY_ROOT     = "root";
    private static final int    SCHEMA_V     = 2;

    private final SharedPreferences prefs;

    RuntimeDependencyStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the persisted record, or an empty record if none exists. */
    synchronized DependencyInstallRecord getRecord(int containerId, String dependencyId)
            throws JSONException, IOException {
        JSONObject records = loadRoot().getJSONObject("records");
        String key = recordKey(containerId, dependencyId);
        JSONObject obj = records.optJSONObject(key);
        return obj != null ? DependencyInstallRecord.fromJSON(obj) : DependencyInstallRecord.empty();
    }

    /** Persists the record for {@code (containerId, dependencyId)}. */
    synchronized void putRecord(int containerId, String dependencyId,
            DependencyInstallRecord record)
            throws JSONException, IOException {
        JSONObject root = loadRoot();
        root.getJSONObject("records").put(recordKey(containerId, dependencyId), record.toJSON());
        save(root);
    }

    /** Returns all persisted records for {@code containerId}, keyed by dependencyId. */
    synchronized Map<String, DependencyInstallRecord> getContainerRecords(int containerId)
            throws JSONException, IOException {
        JSONObject records = loadRoot().getJSONObject("records");
        String prefix = containerId + ":";
        Map<String, DependencyInstallRecord> result = new HashMap<>();
        for (Iterator<String> it = records.keys(); it.hasNext(); ) {
            String key = it.next();
            if (key.startsWith(prefix)) {
                String depId = key.substring(prefix.length());
                result.put(depId, DependencyInstallRecord.fromJSON(records.getJSONObject(key)));
            }
        }
        return result;
    }

    /** Removes all records for {@code containerId}. */
    synchronized void clearContainer(int containerId) throws JSONException, IOException {
        JSONObject root = loadRoot();
        JSONObject records = root.getJSONObject("records");
        String prefix = containerId + ":";
        Iterator<String> it = records.keys();
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        while (it.hasNext()) {
            String key = it.next();
            if (key.startsWith(prefix)) toRemove.add(key);
        }
        for (String key : toRemove) records.remove(key);
        if (!toRemove.isEmpty()) save(root);
    }

    synchronized RuntimeDependencyBootstrapRecord getBootstrap(String key)
            throws JSONException, IOException {
        JSONObject data = loadRoot().getJSONObject("bootstraps").optJSONObject(key);
        return data != null ? RuntimeDependencyBootstrapRecord.fromJSON(data) : null;
    }

    synchronized void putBootstrap(
            String key,
            RuntimeDependencyBootstrapRecord record
    ) throws JSONException, IOException {
        JSONObject root = loadRoot();
        root.getJSONObject("bootstraps").put(key, record.toJSON());
        save(root);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static String recordKey(int containerId, String dependencyId) {
        return containerId + ":" + dependencyId;
    }

    private JSONObject loadRoot() throws JSONException, IOException {
        String text = prefs.getString(KEY_ROOT, null);
        if (text == null || text.isEmpty()) return newRoot();
        JSONObject root = new JSONObject(text);
        int version = root.optInt("schemaVersion", 0);
        if (version == 1) {
            root.put("schemaVersion", SCHEMA_V);
            root.put("bootstraps", new JSONObject());
            save(root);
        }
        else if (version != SCHEMA_V) {
            throw new JSONException("Unsupported runtime dependency store schema");
        }
        if (!root.has("records")) root.put("records", new JSONObject());
        if (!root.has("bootstraps")) root.put("bootstraps", new JSONObject());
        return root;
    }

    private static JSONObject newRoot() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_V);
        root.put("records", new JSONObject());
        root.put("bootstraps", new JSONObject());
        return root;
    }

    private void save(JSONObject root) throws IOException {
        String prev = prefs.getString(KEY_ROOT, null);
        if (!prefs.edit().putString(KEY_ROOT, root.toString()).commit()) {
            SharedPreferences.Editor rollback = prefs.edit();
            if (prev != null) rollback.putString(KEY_ROOT, prev);
            else rollback.remove(KEY_ROOT);
            rollback.commit();
            throw new IOException("Unable to persist runtime dependency store.");
        }
    }
}
