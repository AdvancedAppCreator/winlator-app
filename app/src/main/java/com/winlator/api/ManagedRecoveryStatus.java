package com.winlator.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ManagedRecoveryStatus {
    private static final int CRASH_LOOP_COUNT = 3;
    private static final long CRASH_LOOP_WINDOW_MILLIS = 10 * 60 * 1000L;

    private ManagedRecoveryStatus() {
    }

    static JSONObject analyze(String gameId, JSONArray reports, long now)
            throws JSONException {
        JSONObject result = new JSONObject()
                .put("gameId", gameId)
                .put("crashLoopDetected", false)
                .put("recentFailureCount", 0)
                .put("suggestions", new JSONArray());
        JSONObject latestFailure = null;
        JSONObject lastKnownGood = null;
        int matchingFailures = 0;
        String failureHash = null;
        long newestFailureAt = 0;

        for (int index = 0; index < reports.length(); index++) {
            JSONObject report = reports.getJSONObject(index);
            if (lastKnownGood == null && "good".equals(report.optString("configHealth"))) {
                lastKnownGood = report;
            }
            if (!"bad".equals(report.optString("configHealth"))) continue;
            long endedAt = report.optLong("endedAt", 0);
            if (latestFailure == null) {
                latestFailure = report;
                failureHash = report.optString("appliedConfigSha256", "");
                newestFailureAt = endedAt;
            }
            if (newestFailureAt - endedAt > CRASH_LOOP_WINDOW_MILLIS) continue;
            if (failureHash.equals(report.optString("appliedConfigSha256", ""))) {
                matchingFailures++;
            }
        }

        result.put("recentFailureCount", matchingFailures);
        result.put(
                "crashLoopDetected",
                matchingFailures >= CRASH_LOOP_COUNT &&
                        now - newestFailureAt <= CRASH_LOOP_WINDOW_MILLIS
        );
        if (latestFailure != null) {
            result.put("latestFailureReportId", latestFailure.getString("reportId"));
            result.put(
                    "suggestions",
                    new JSONArray(latestFailure.optJSONArray("suggestions") != null
                            ? latestFailure.getJSONArray("suggestions").toString()
                            : "[]")
            );
        }
        if (lastKnownGood != null && lastKnownGood.optJSONObject("appliedConfig") != null) {
            result.put("lastKnownGood", new JSONObject()
                    .put("reportId", lastKnownGood.getString("reportId"))
                    .put("configJson", lastKnownGood.getJSONObject("appliedConfig"))
                    .put(
                            "configSha256",
                            lastKnownGood.optString("appliedConfigSha256", "")
                    )
                    .put("endedAt", lastKnownGood.optLong("endedAt", 0)));
        }
        return result;
    }
}
