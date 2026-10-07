package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ManagedRecoveryStatusTest {
    @Test
    public void detectsThreeRecentFailuresWithSameConfiguration() throws Exception {
        long now = 1_000_000;
        JSONArray reports = new JSONArray()
                .put(report("f3", "bad", "HASH", now - 1000))
                .put(report("f2", "bad", "HASH", now - 2000))
                .put(report("f1", "bad", "HASH", now - 3000));

        JSONObject status = ManagedRecoveryStatus.analyze("game", reports, now);

        assertTrue(status.getBoolean("crashLoopDetected"));
        assertEquals(3, status.getInt("recentFailureCount"));
    }

    @Test
    public void differentConfigurationDoesNotCountTowardLoop() throws Exception {
        long now = 1_000_000;
        JSONArray reports = new JSONArray()
                .put(report("f3", "bad", "A", now - 1000))
                .put(report("f2", "bad", "B", now - 2000))
                .put(report("f1", "bad", "A", now - 3000));

        JSONObject status = ManagedRecoveryStatus.analyze("game", reports, now);

        assertFalse(status.getBoolean("crashLoopDetected"));
        assertEquals(2, status.getInt("recentFailureCount"));
    }

    @Test
    public void exposesLatestSuggestionsAndLastKnownGood() throws Exception {
        long now = 1_000_000;
        JSONObject failure = report("failure", "bad", "BAD", now - 1000)
                .put("suggestions", new JSONArray().put(
                        new JSONObject().put("id", "stability")
                ));
        JSONObject good = report("good", "good", "GOOD", now - 20_000)
                .put("appliedConfig", new JSONObject().put("screenSize", "1280x720"));

        JSONObject status = ManagedRecoveryStatus.analyze(
                "game",
                new JSONArray().put(failure).put(good),
                now
        );

        assertEquals("stability", status.getJSONArray("suggestions")
                .getJSONObject(0).getString("id"));
        assertEquals("GOOD", status.getJSONObject("lastKnownGood")
                .getString("configSha256"));
    }

    private static JSONObject report(
            String id,
            String health,
            String hash,
            long endedAt
    ) throws Exception {
        return new JSONObject()
                .put("reportId", id)
                .put("configHealth", health)
                .put("appliedConfigSha256", hash)
                .put("endedAt", endedAt)
                .put("suggestions", new JSONArray());
    }
}
