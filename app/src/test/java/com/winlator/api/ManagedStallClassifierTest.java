package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ManagedStallClassifierTest {
    private static final long NOW = 1_000_000L;
    private static final String HASH = "deadbeef";

    private static JSONObject config() {
        return new JSONObject();
    }

    private static StallSignals signals(
            long startedAgo,
            long outputSilence,
            long frameSilence,
            boolean runtimeReached,
            List<String> output,
            JSONObject appliedConfig
    ) {
        return new StallSignals(
                NOW,
                NOW - startedAgo,
                NOW - outputSilence,
                frameSilence < 0 ? 0 : NOW - frameSilence,
                runtimeReached,
                output,
                appliedConfig,
                appliedConfig != null ? HASH : ""
        );
    }

    private static List<String> generic() {
        return Arrays.asList("heartbeat 1", "heartbeat 2");
    }

    private static boolean hasSuggestion(JSONObject diagnosis, String id) {
        JSONArray suggestions = diagnosis.optJSONArray("suggestions");
        if (suggestions == null) return false;
        for (int index = 0; index < suggestions.length(); index++) {
            if (id.equals(suggestions.optJSONObject(index).optString("id"))) return true;
        }
        return false;
    }

    @Test
    public void earlyStartupIsNotStalled() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(5000, 1000, -1, false, generic(), config()),
                StallThresholds.defaults()
        );
        assertFalse(diagnosis.getBoolean("stalled"));
    }

    @Test
    public void silentBlackScreenSuggestsGraphicsAndStability() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(30000, 15000, -1, false, generic(), config()),
                StallThresholds.defaults()
        );
        assertTrue(diagnosis.getBoolean("stalled"));
        assertEquals("stuck_before_first_frame", diagnosis.getString("cause"));
        assertTrue(hasSuggestion(diagnosis, "graphics-compatibility"));
        assertTrue(hasSuggestion(diagnosis, "box64-stability"));
    }

    @Test
    public void graphicsActivityGivesGraphicsInitStall() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(30000, 15000, -1, false,
                        Arrays.asList("heartbeat", "Initializing Vulkan device 0"),
                        config()),
                StallThresholds.defaults()
        );
        assertEquals("graphics_init_stall", diagnosis.getString("cause"));
        assertTrue(hasSuggestion(diagnosis, "graphics-compatibility"));
        assertFalse(hasSuggestion(diagnosis, "box64-stability"));
    }

    @Test
    public void dependencyActivityGivesDependencyWaitStall() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(30000, 15000, -1, false,
                        Arrays.asList("heartbeat",
                                "err:module: Library api-ms-win-crt.dll not found"),
                        config()),
                StallThresholds.defaults()
        );
        assertEquals("dependency_wait_stall", diagnosis.getString("cause"));
    }

    @Test
    public void framesStoppingAfterWindowGivesPresentingStall() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(40000, 2000, 10000, true, generic(), config()),
                StallThresholds.defaults()
        );
        assertTrue(diagnosis.getBoolean("stalled"));
        assertEquals("presenting_stall", diagnosis.getString("cause"));
        assertTrue(hasSuggestion(diagnosis, "graphics-compatibility"));
        assertTrue(hasSuggestion(diagnosis, "lower-resolution"));
    }

    @Test
    public void freshFramesAfterWindowIsHealthy() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(40000, 2000, 1000, true, generic(), config()),
                StallThresholds.defaults()
        );
        assertFalse(diagnosis.getBoolean("stalled"));
    }

    @Test
    public void slowStartWhileOutputFlowingIsLowConfidence() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(65000, 2000, -1, false, generic(), config()),
                StallThresholds.defaults()
        );
        assertTrue(diagnosis.getBoolean("stalled"));
        assertEquals("slow_start", diagnosis.getString("cause"));
        assertEquals("low", diagnosis.getString("confidence"));
    }

    @Test
    public void stabilitySuggestionOmittedWhenAlreadyStability() throws Exception {
        JSONObject applied = new JSONObject().put("box64Preset", "STABILITY");
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(30000, 15000, -1, false, generic(), applied),
                StallThresholds.defaults()
        );
        assertEquals("stuck_before_first_frame", diagnosis.getString("cause"));
        assertTrue(hasSuggestion(diagnosis, "graphics-compatibility"));
        assertFalse(hasSuggestion(diagnosis, "box64-stability"));
    }

    @Test
    public void missingConfigStillProducesDiagnosisWithoutSuggestions() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                signals(30000, 15000, -1, false, generic(), null),
                StallThresholds.defaults()
        );
        assertTrue(diagnosis.getBoolean("stalled"));
        assertEquals("stuck_before_first_frame", diagnosis.getString("cause"));
        assertEquals(0, diagnosis.getJSONArray("suggestions").length());
    }

    @Test
    public void healthyBeforeSoftThresholdWithNoOutputYet() throws Exception {
        JSONObject diagnosis = ManagedStallClassifier.classify(
                new StallSignals(NOW, NOW - 3000, 0, 0, false,
                        Collections.emptyList(), config(), HASH),
                StallThresholds.defaults()
        );
        assertFalse(diagnosis.getBoolean("stalled"));
    }
}
