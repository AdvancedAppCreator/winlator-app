package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.winlator.core.StartupLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Persists per-game troubleshooting progress so the remedy ladder advances across the
 * session restarts that applying a fix triggers. For each game we track which step ids have
 * been tried, plus a "pending" step that was applied and is awaiting its outcome. If the
 * pending step was applied in an earlier session and the game is still stalling, it is
 * promoted to "tried" (it failed) and the ladder advances. If the game instead becomes
 * healthy, the pending step is credited as the resolver.
 */
final class StallAttemptStore {
    private static final String PREFS = "stall_troubleshooter";

    private final SharedPreferences preferences;

    StallAttemptStore(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private JSONObject read(String gameId) {
        String raw = preferences.getString(gameId, null);
        if (raw == null) return new JSONObject();
        try {
            return new JSONObject(raw);
        }
        catch (JSONException error) {
            return new JSONObject();
        }
    }

    private void write(String gameId, JSONObject record) {
        preferences.edit().putString(gameId, record.toString()).apply();
    }

    Set<String> triedStepIds(String gameId) {
        Set<String> tried = new HashSet<>();
        JSONArray array = read(gameId).optJSONArray("tried");
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "");
                if (!value.isEmpty()) tried.add(value);
            }
        }
        return tried;
    }

    String pendingStepId(String gameId) {
        String pending = read(gameId).optString("pending", "");
        return pending.isEmpty() ? null : pending;
    }

    long pendingAppliedAt(String gameId) {
        return read(gameId).optLong("pendingAt", 0);
    }

    String pendingCause(String gameId) {
        return read(gameId).optString("cause", "");
    }

    /**
     * Records that a remedy step was just applied (the session is about to restart). The step
     * becomes "pending" until its outcome is observed in a later session.
     */
    void recordApplied(String gameId, String cause, String stepId, long appliedAt) {
        if (gameId == null || gameId.isEmpty() || stepId == null) return;
        try {
            JSONObject record = read(gameId);
            record.put("cause", cause != null ? cause : "");
            record.put("pending", stepId);
            record.put("pendingAt", appliedAt);
            write(gameId, record);
        }
        catch (JSONException error) {
            StartupLog.log("Unable to record applied stall remedy", error);
        }
    }

    /**
     * If a pending step was applied before {@code sessionStartedAt} (i.e. in an earlier
     * session) and the game is stalling again, the step failed: move it into "tried" and
     * clear pending so the ladder advances.
     */
    void promoteStalePending(String gameId, long sessionStartedAt) {
        String pending = pendingStepId(gameId);
        if (pending == null) return;
        if (pendingAppliedAt(gameId) >= sessionStartedAt) return;
        try {
            JSONObject record = read(gameId);
            JSONArray tried = record.optJSONArray("tried");
            if (tried == null) tried = new JSONArray();
            boolean present = false;
            for (int index = 0; index < tried.length(); index++) {
                if (pending.equals(tried.optString(index))) { present = true; break; }
            }
            if (!present) tried.put(pending);
            record.put("tried", tried);
            record.remove("pending");
            record.remove("pendingAt");
            write(gameId, record);
        }
        catch (JSONException error) {
            StartupLog.log("Unable to promote stale pending remedy", error);
        }
    }

    void clear(String gameId) {
        if (gameId == null || gameId.isEmpty()) return;
        preferences.edit().remove(gameId).apply();
    }
}
