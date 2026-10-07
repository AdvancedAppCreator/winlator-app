package com.winlator.api.dependency;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable record tracking install state for one dependency on one container.
 * Fully JSON-serialisable; no Android dependencies.
 */
public final class DependencyInstallRecord {

    public static final int MAX_LOG_ENTRIES = 50;

    /** Timestamped log entry for an install attempt. */
    public static final class LogEntry {
        public final long timestamp;
        public final String message;

        LogEntry(long timestamp, String message) {
            this.timestamp = timestamp;
            this.message = message;
        }

        JSONObject toJSON() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("ts", timestamp);
            obj.put("msg", message);
            return obj;
        }

        static LogEntry fromJSON(JSONObject obj) throws JSONException {
            return new LogEntry(obj.getLong("ts"), obj.getString("msg"));
        }
    }

    public final DependencyStatus status;
    /** Last installer file-system path used, or null. */
    public final String installerPath;
    /** Unix epoch millis of last successful install, or 0. */
    public final long installedAt;
    public final List<LogEntry> log;

    private DependencyInstallRecord(DependencyStatus status, String installerPath,
            long installedAt, List<LogEntry> log) {
        this.status = status;
        this.installerPath = installerPath;
        this.installedAt = installedAt;
        this.log = Collections.unmodifiableList(log);
    }

    /** Record representing no known install history. */
    public static DependencyInstallRecord empty() {
        return new DependencyInstallRecord(
                DependencyStatus.NOT_INSTALLED, null, 0, new ArrayList<>());
    }

    public DependencyInstallRecord withStatus(DependencyStatus newStatus, long now) {
        long installed = (newStatus == DependencyStatus.INSTALLED) ? now : this.installedAt;
        return new DependencyInstallRecord(newStatus, installerPath, installed, new ArrayList<>(log));
    }

    public DependencyInstallRecord withInstallerPath(String path) {
        return new DependencyInstallRecord(status, path, installedAt, new ArrayList<>(log));
    }

    public DependencyInstallRecord appendLog(String message, long now) {
        List<LogEntry> updated = new ArrayList<>(log);
        updated.add(new LogEntry(now, message));
        if (updated.size() > MAX_LOG_ENTRIES) {
            updated = updated.subList(updated.size() - MAX_LOG_ENTRIES, updated.size());
        }
        return new DependencyInstallRecord(status, installerPath, installedAt, updated);
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("status", status.toKey());
        if (installerPath != null) obj.put("installerPath", installerPath);
        obj.put("installedAt", installedAt);
        JSONArray logArr = new JSONArray();
        for (LogEntry entry : log) logArr.put(entry.toJSON());
        obj.put("log", logArr);
        return obj;
    }

    public static DependencyInstallRecord fromJSON(JSONObject obj) throws JSONException {
        DependencyStatus status = DependencyStatus.fromKey(obj.optString("status", null));
        String installerPath = obj.has("installerPath") ? obj.getString("installerPath") : null;
        long installedAt = obj.optLong("installedAt", 0);
        JSONArray logArr = obj.optJSONArray("log");
        List<LogEntry> log = new ArrayList<>();
        if (logArr != null) {
            for (int i = 0; i < logArr.length(); i++) {
                log.add(LogEntry.fromJSON(logArr.getJSONObject(i)));
            }
        }
        return new DependencyInstallRecord(status, installerPath, installedAt, log);
    }
}
