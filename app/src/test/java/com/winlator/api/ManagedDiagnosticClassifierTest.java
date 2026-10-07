package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class ManagedDiagnosticClassifierTest {
    @Test
    public void classifiesBox64SegfaultAndSuggestsStability() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, false),
                Arrays.asList(
                        "Box64 with Dynarec",
                        "Segmentation fault at address 0x1234"
                ),
                139,
                "unknown",
                null,
                5000
        );

        assertEquals("crashed", report.getString("outcome"));
        assertEquals("box64_segfault", report.getString("category"));
        assertEquals(11, report.getInt("signal"));
        assertEquals("bad", report.getString("configHealth"));
        assertEquals(
                "STABILITY",
                report.getJSONArray("suggestions")
                        .getJSONObject(0)
                        .getJSONObject("set")
                        .getString("box64Preset")
        );
    }

    @Test
    public void meaningfulUserSessionMarksConfigurationGood() throws Exception {
        JSONObject draft = draft(1000, true);
        draft.put("phase", "runtime");

        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft,
                Collections.emptyList(),
                null,
                "user_exit",
                null,
                62000
        );

        assertEquals("user_exit", report.getString("outcome"));
        assertEquals("good", report.getString("configHealth"));
        assertTrue(report.getBoolean("runtimeReached"));
    }

    @Test
    public void explicitRuntimeLocaleFailureIsAHighConfidenceLaunchFailure()
            throws Exception {
        JSONObject draft = draft(1000, false)
                .put("phase", "environment_start")
                .put("failureCategory", "runtime_locale_generation")
                .put("failureDetails", new JSONObject().put("exitStatus", 1));

        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft,
                Collections.singletonList(
                        "Runtime locale failure: localedef returned exit status 1"
                ),
                null,
                "launch_failed",
                null,
                2000
        );

        assertEquals("launch_failed", report.getString("outcome"));
        assertEquals("runtime_locale_generation", report.getString("category"));
        assertEquals("environment_start", report.getString("phase"));
        assertEquals("high", report.getString("confidence"));
        assertFalse(report.getBoolean("runtimeReached"));
        assertEquals(
                1,
                report.getJSONObject("failureDetails").getInt("exitStatus")
        );
    }

    @Test
    public void killStatusSuggestsLowerResolution() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, true),
                Collections.singletonList("Killed process because memory allocation failed"),
                137,
                "unknown",
                null,
                10000
        );

        assertEquals("possible_memory_pressure", report.getString("category"));
        assertEquals(
                "1280x720",
                report.getJSONArray("suggestions")
                        .getJSONObject(0)
                        .getJSONObject("set")
                        .getString("screenSize")
        );
    }

    @Test
    public void guestVirtualAddressOomOutranksTeardownKillStatus() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, true),
                Arrays.asList(
                        "err:virtual:allocate_virtual_memory out of memory "
                                + "for allocation size 0x57320000",
                        "wine: Unhandled exception 0x0eedfade"
                ),
                137,
                "unknown",
                null,
                10000,
                "winlator_teardown"
        );

        assertEquals("guest_virtual_address_oom", report.getString("category"));
        assertEquals("crashed", report.getString("outcome"));
        assertEquals("winlator_teardown", report.getString("terminationOrigin"));
        assertEquals("high", report.getString("confidence"));
        assertEquals(
                0x57320000L,
                report.getJSONObject("failureDetails").getLong("allocationSizeBytes")
        );
    }

    @Test
    public void gstreamerFailureOutranksMissingGtkAndKillStatus() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, true),
                Arrays.asList(
                        "[BOX64] Error loading needed lib libgtk-3.so.0",
                        "GStreamer-Video-CRITICAL: gst_video_info_from_caps: "
                                + "assertion 'gst_caps_is_fixed (caps)' failed",
                        "*** stack smashing detected ***: terminated"
                ),
                137,
                "unknown",
                null,
                10000,
                "winlator_teardown"
        );

        assertEquals("media_pipeline_failure", report.getString("category"));
        assertEquals("high", report.getString("confidence"));
        assertTrue(report.getJSONArray("evidence").toString().contains("libgtk-3.so.0"));
        assertTrue(report.getJSONArray("evidence").toString().contains("stack smashing"));
    }

    @Test
    public void teardownKillWithoutFailureEvidenceIsNotMemoryPressure() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, true),
                Collections.emptyList(),
                137,
                "unknown",
                null,
                10000,
                "winlator_teardown"
        );

        assertEquals("winlator_teardown", report.getString("category"));
        assertEquals("winlator_teardown", report.getString("terminationOrigin"));
        assertEquals("unknown", report.getString("configHealth"));
    }

    @Test
    public void userExitOutranksIncidentalMissingNativeLibraryLog() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, true),
                Collections.singletonList(
                        "[BOX64] Error loading needed lib libgtk-3.so.0"
                ),
                null,
                "user_exit",
                null,
                62000,
                "user_exit"
        );

        assertEquals("user_exit", report.getString("category"));
        assertEquals("user_exit", report.getString("outcome"));
        assertEquals("good", report.getString("configHealth"));
    }

    @Test
    public void cleanExitOutranksIncidentalGstreamerWarning() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, true),
                Collections.singletonList(
                        "GStreamer-Video-CRITICAL: gst_video_info_from_caps warning"
                ),
                0,
                "completed",
                null,
                62000,
                "natural"
        );

        assertEquals("clean_exit", report.getString("category"));
        assertEquals("completed", report.getString("outcome"));
        assertEquals("good", report.getString("configHealth"));
    }

    @Test
    public void evidenceIsBoundedAndRedacted() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String privatePath = context.getFilesDir().getPath() + "/" + repeat("A", 600);
        String output = ManagedSessionDiagnostics.redact(
                context,
                "wine: permission denied " + privatePath
        );
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, false),
                Arrays.asList(
                        output, output, output, output, output,
                        output, output, output, output
                ),
                1,
                "unknown",
                null,
                2000
        );

        assertEquals(8, report.getJSONArray("evidence").length());
        for (int index = 0; index < report.getJSONArray("evidence").length(); index++) {
            String message = report.getJSONArray("evidence")
                    .getJSONObject(index)
                    .getString("message");
            assertTrue(message.length() <= 512);
            assertTrue(message.contains("<files>"));
            assertTrue(!message.contains(context.getFilesDir().getPath()));
        }
    }

    @Test
    public void androidExceptionIncludesStackEvidence() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, false),
                Collections.singletonList(
                        "java.lang.IllegalStateException: broken\n"
                                + "\tat com.winlator.Test.run(Test.java:42)"
                ),
                null,
                "crashed",
                new IllegalStateException("broken"),
                2000
        );

        assertEquals("android_exception", report.getString("category"));
        assertEquals(2, report.getJSONArray("evidence").length());
        assertEquals(
                "android",
                report.getJSONArray("evidence").getJSONObject(1).getString("source")
        );
        assertTrue(
                report.getJSONArray("evidence")
                        .getJSONObject(1)
                        .getString("message")
                        .contains("Test.run")
        );
    }

    @Test
    public void explicitWindows10RequirementProducesActionableSuggestion() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, false),
                Collections.singletonList(
                        "This application requires Windows 10 or higher."
                ),
                1,
                "unknown",
                null,
                2000
        );

        assertEquals("windows_version_incompatibility", report.getString("category"));
        assertEquals("high", report.getString("confidence"));
        assertEquals("win10", report.getString("requiredWinVersion"));
        assertEquals(
                "win10",
                report.getJSONArray("suggestions")
                        .getJSONObject(0)
                        .getJSONObject("set")
                        .getString("winVersion")
        );
    }

    @Test
    public void genericOldWindowsErrorDoesNotGuessTargetVersion() throws Exception {
        JSONObject report = ManagedDiagnosticClassifier.classify(
                draft(1000, false),
                Collections.singletonList("ERROR_OLD_WIN_VERSION"),
                1,
                "unknown",
                null,
                2000
        );

        assertEquals("windows_version_incompatibility", report.getString("category"));
        assertEquals("medium", report.getString("confidence"));
        assertFalse(report.has("requiredWinVersion"));
        assertEquals(0, report.getJSONArray("suggestions").length());
    }

    private JSONObject draft(long startedAt, boolean runtimeReached) throws Exception {
        JSONObject config = new JSONObject()
                .put("screenSize", "1920x1080")
                .put("box64Preset", "INTERMEDIATE")
                .put("graphicsDriver", "turnip,gladio")
                .put("dxwrapper", "dxvk")
                .put("audioDriver", "alsa");
        return new JSONObject()
                .put("reportId", "report")
                .put("sessionId", "session")
                .put("gameId", "game")
                .put("containerId", 4)
                .put("startedAt", startedAt)
                .put("phase", runtimeReached ? "runtime" : "startup")
                .put("runtimeReached", runtimeReached)
                .put("appliedConfig", config)
                .put("appliedConfigSha256", GameConfigSchema.hash(config));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
