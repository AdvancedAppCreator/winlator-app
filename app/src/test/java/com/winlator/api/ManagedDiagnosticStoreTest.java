package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class ManagedDiagnosticStoreTest {
    private Context context;
    private SharedPreferences preferences;
    private ManagedDiagnosticStore store;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(
                "managed_game_diagnostics",
                Context.MODE_PRIVATE
        );
        preferences.edit().clear().commit();
        store = new ManagedDiagnosticStore(context);
    }

    @After
    public void tearDown() {
        preferences.edit().clear().commit();
    }

    @Test
    public void retainsNewestTwentyReportsPerGame() throws Exception {
        for (int index = 0; index < 25; index++) {
            JSONObject report = report("report-" + index, "game");
            store.finish(report.getString("reportId"), report);
        }

        JSONArray reports = store.listForGame("game", 20);

        assertEquals(20, reports.length());
        assertEquals("report-24", reports.getJSONObject(0).getString("reportId"));
        assertEquals("report-5", reports.getJSONObject(19).getString("reportId"));
        assertTrue(store.get("report-4") == null);
    }

    @Test
    public void oversizedReportDropsConfigBeforeExceedingBound() throws Exception {
        JSONObject report = report("large", "game");
        report.put(
                "appliedConfig",
                new JSONObject().put("envVars", repeat("A", 40000))
        );

        JSONObject bounded = ManagedDiagnosticStore.boundReport(report);

        assertTrue(
                bounded.toString().getBytes(StandardCharsets.UTF_8).length <= 32 * 1024
        );
        assertFalse(bounded.has("appliedConfig"));
        assertTrue(bounded.getBoolean("appliedConfigOmitted"));
    }

    @Test
    public void oversizedLocaleFailureOutputIsBounded() throws Exception {
        JSONObject report = report("locale-failure", "game")
                .put(
                        "failureDetails",
                        new JSONObject()
                                .put("stage", "localedef")
                                .put("message", repeat("failure detail ", 5000))
                                .put("output", repeat("localedef failed\n", 5000))
                );

        JSONObject bounded = ManagedDiagnosticStore.boundReport(report);

        assertTrue(
                bounded.toString().getBytes(StandardCharsets.UTF_8).length <= 32 * 1024
        );
        if (bounded.has("failureDetails")) {
            JSONObject details = bounded.getJSONObject("failureDetails");
            assertTrue(
                    details.optBoolean("outputTruncatedForStorage", false) ||
                            details.optBoolean("outputOmitted", false)
            );
            assertTrue(details.optString("message", "").length() <= 2048);
        }
        else {
            assertTrue(bounded.getBoolean("failureDetailsOmitted"));
        }
    }

    @Test
    public void recoversAndClearsInterruptedActiveSessions() throws Exception {
        JSONObject draft = report("active", "game");
        draft.remove("endedAt");
        draft.remove("outcome");
        store.start(draft);

        ArrayList<JSONObject> recovered = store.recoverInterrupted(5000);

        assertEquals(1, recovered.size());
        assertEquals(
                "app_or_session_terminated",
                recovered.get(0).getString("category")
        );
        assertEquals(
                "active",
                store.get("active").getString("reportId")
        );
        assertEquals(0, store.recoverInterrupted(6000).size());
    }

    private JSONObject report(String id, String gameId) throws Exception {
        return new JSONObject()
                .put("reportId", id)
                .put("gameId", gameId)
                .put("startedAt", 1)
                .put("endedAt", 2)
                .put("outcome", "completed")
                .put("phase", "shutdown")
                .put("category", "clean_exit")
                .put("confidence", "high")
                .put("configHealth", "unknown")
                .put("runtimeReached", true)
                .put("evidence", new JSONArray())
                .put("suggestions", new JSONArray());
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
