package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;

final class ManagedDiagnosticStore {
    private static final String PREFERENCES_NAME = "managed_game_diagnostics";
    private static final String KEY_STORE = "store";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_REPORTS_PER_GAME = 20;
    private static final int MAX_REPORTS_TOTAL = 500;
    private static final int MAX_REPORT_BYTES = 32 * 1024;
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    ManagedDiagnosticStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }

    ArrayList<JSONObject> recoverInterrupted(long endedAt) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            JSONObject active = root.getJSONObject("active");
            ArrayList<JSONObject> recovered = new ArrayList<>();
            ArrayList<String> ids = new ArrayList<>();
            for (Iterator<String> keys = active.keys(); keys.hasNext(); ) ids.add(keys.next());
            for (String id : ids) {
                JSONObject report = ManagedDiagnosticClassifier.interrupted(
                        active.getJSONObject(id),
                        endedAt
                );
                active.remove(id);
                addReport(root, report);
                recovered.add(report);
            }
            if (!ids.isEmpty()) save(root);
            return recovered;
        }
    }

    void start(JSONObject draft) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            root.getJSONObject("active").put(draft.getString("reportId"), draft);
            save(root);
        }
    }

    void updateActive(JSONObject draft) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            JSONObject active = root.getJSONObject("active");
            String reportId = draft.getString("reportId");
            if (!active.has(reportId)) return;
            active.put(reportId, draft);
            save(root);
        }
    }

    void finish(String reportId, JSONObject report) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            root.getJSONObject("active").remove(reportId);
            addReport(root, report);
            save(root);
        }
    }

    void cancel(String reportId) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            root.getJSONObject("active").remove(reportId);
            save(root);
        }
    }

    JSONObject get(String reportId) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject report = loadRoot().getJSONObject("reports").optJSONObject(reportId);
            return report != null ? new JSONObject(report.toString()) : null;
        }
    }

    JSONArray listForGame(String gameId, int limit) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            JSONObject reports = root.getJSONObject("reports");
            JSONArray ids = root.getJSONObject("gameOrder").optJSONArray(gameId);
            JSONArray result = new JSONArray();
            if (ids == null) return result;
            for (int index = 0; index < ids.length() && result.length() < limit; index++) {
                JSONObject report = reports.optJSONObject(ids.getString(index));
                if (report != null) result.put(new JSONObject(report.toString()));
            }
            return result;
        }
    }

    static JSONObject boundReport(JSONObject source) throws JSONException {
        JSONObject report = new JSONObject(source.toString());
        JSONArray evidence = report.optJSONArray("evidence");
        if (evidence != null) {
            while (evidence.length() > 8) evidence.remove(evidence.length() - 1);
        }
        if (size(report) <= MAX_REPORT_BYTES) return report;

        if (evidence != null) {
            while (evidence.length() > 1 && size(report) > MAX_REPORT_BYTES) {
                evidence.remove(evidence.length() - 1);
            }
        }
        if (size(report) <= MAX_REPORT_BYTES) return report;

        if (report.has("appliedConfig")) {
            report.remove("appliedConfig");
            report.put("appliedConfigOmitted", true);
        }
        if (size(report) > MAX_REPORT_BYTES) {
            report.put("suggestions", new JSONArray());
        }
        if (size(report) > MAX_REPORT_BYTES) {
            report.put("evidence", new JSONArray());
        }
        JSONObject failureDetails = report.optJSONObject("failureDetails");
        if (failureDetails != null && failureDetails.has("message")) {
            String message = failureDetails.optString("message", "");
            if (message.length() > 2048) {
                failureDetails.put("message", message.substring(0, 2048));
                failureDetails.put("messageTruncatedForStorage", true);
            }
        }
        if (size(report) > MAX_REPORT_BYTES &&
                failureDetails != null &&
                failureDetails.has("output")) {
            String output = failureDetails.optString("output", "");
            if (output.length() > 4096) {
                failureDetails.put("output", output.substring(0, 4096));
                failureDetails.put("outputTruncatedForStorage", true);
            }
        }
        if (size(report) > MAX_REPORT_BYTES &&
                failureDetails != null &&
                failureDetails.has("output")) {
            failureDetails.remove("output");
            failureDetails.put("outputOmitted", true);
        }
        if (size(report) > MAX_REPORT_BYTES && failureDetails != null) {
            report.remove("failureDetails");
            report.put("failureDetailsOmitted", true);
        }
        return report;
    }

    private void addReport(JSONObject root, JSONObject report) throws JSONException {
        report = boundReport(report);
        String reportId = report.getString("reportId");
        String gameId = report.optString("gameId", "");
        JSONObject reports = root.getJSONObject("reports");
        JSONArray globalOrder = root.getJSONArray("globalOrder");
        JSONObject gameOrder = root.getJSONObject("gameOrder");

        removeValue(globalOrder, reportId);
        prepend(globalOrder, reportId);
        reports.put(reportId, report);

        if (!gameId.isEmpty()) {
            JSONArray ids = gameOrder.optJSONArray(gameId);
            if (ids == null) ids = new JSONArray();
            removeValue(ids, reportId);
            prepend(ids, reportId);
            gameOrder.put(gameId, ids);
            while (ids.length() > MAX_REPORTS_PER_GAME) {
                removeReport(root, ids.getString(ids.length() - 1));
            }
        }

        while (globalOrder.length() > MAX_REPORTS_TOTAL) {
            removeReport(root, globalOrder.getString(globalOrder.length() - 1));
        }
    }

    private void removeReport(JSONObject root, String reportId) throws JSONException {
        JSONObject reports = root.getJSONObject("reports");
        JSONObject report = reports.optJSONObject(reportId);
        reports.remove(reportId);
        removeValue(root.getJSONArray("globalOrder"), reportId);
        if (report == null) return;
        String gameId = report.optString("gameId", "");
        JSONArray gameIds = root.getJSONObject("gameOrder").optJSONArray(gameId);
        if (gameIds != null) {
            removeValue(gameIds, reportId);
            if (gameIds.length() == 0) root.getJSONObject("gameOrder").remove(gameId);
        }
    }

    private JSONObject loadRoot() throws JSONException {
        String text = preferences.getString(KEY_STORE, "");
        if (text.isEmpty()) return newRoot();
        JSONObject root = new JSONObject(text);
        if (root.optInt("schemaVersion", 0) != SCHEMA_VERSION) {
            throw new JSONException("Unsupported diagnostic store schema");
        }
        return root;
    }

    private JSONObject newRoot() throws JSONException {
        return new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("reports", new JSONObject())
                .put("globalOrder", new JSONArray())
                .put("gameOrder", new JSONObject())
                .put("active", new JSONObject());
    }

    private void save(JSONObject root) throws IOException {
        String previous = preferences.getString(KEY_STORE, null);
        if (!preferences.edit().putString(KEY_STORE, root.toString()).commit()) {
            SharedPreferences.Editor rollback = preferences.edit();
            if (previous != null) rollback.putString(KEY_STORE, previous);
            else rollback.remove(KEY_STORE);
            rollback.commit();
            throw new IOException("Unable to persist managed diagnostics");
        }
    }

    private static void prepend(JSONArray array, String value) throws JSONException {
        for (int index = array.length(); index > 0; index--) {
            array.put(index, array.get(index - 1));
        }
        array.put(0, value);
    }

    private static void removeValue(JSONArray array, String value) throws JSONException {
        for (int index = array.length() - 1; index >= 0; index--) {
            if (value.equals(array.optString(index))) array.remove(index);
        }
    }

    private static int size(JSONObject report) {
        return report.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
