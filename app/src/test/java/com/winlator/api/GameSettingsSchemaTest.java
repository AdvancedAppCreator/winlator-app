package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GameSettingsSchemaTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void hashIsStableAcrossNestedObjectKeyOrder() throws Exception {
        JSONObject first = new JSONObject()
                .put("ocr", new JSONObject()
                        .put("mode", "OFF")
                        .put("script", "JAPANESE"))
                .put("localization", new JSONObject()
                        .put("runtimeLocale", "ja_JP.UTF-8")
                        .put("gameLanguage", "ja"));
        JSONObject second = new JSONObject()
                .put("localization", new JSONObject()
                        .put("gameLanguage", "ja")
                        .put("runtimeLocale", "ja_JP.UTF-8"))
                .put("ocr", new JSONObject()
                        .put("script", "JAPANESE")
                        .put("mode", "OFF"));

        assertEquals(GameSettingsSchema.hash(first), GameSettingsSchema.hash(second));
        second.getJSONObject("ocr").put("mode", "SUBTITLE");
        assertNotEquals(GameSettingsSchema.hash(first), GameSettingsSchema.hash(second));
    }

    @Test
    public void nestedUpdatePreservesUnchangedSettings() throws Exception {
        JSONObject current = GameSettingsSchema.defaults(context);
        JSONObject updated = GameSettingsSchema.applyUpdate(
                context,
                current,
                new JSONObject().put(
                        "localization",
                        new JSONObject().put("runtimeLocale", "ja_JP.UTF-8")
                )
        );

        assertEquals(
                "ja_JP.UTF-8",
                updated.getJSONObject("localization").getString("runtimeLocale")
        );
        assertEquals(
                current.getJSONObject("ocr").toString(),
                updated.getJSONObject("ocr").toString()
        );
    }

    @Test
    public void unifiedSettingsAcceptRuntimeCompatibilityUpdates() throws Exception {
        JSONObject current = GameSettingsSchema.defaults(context);
        JSONObject updated = GameSettingsSchema.applyUpdate(
                context,
                current,
                new JSONObject().put(
                        "runtime",
                        new JSONObject()
                                .put("screenSize", "1280x720")
                                .put("forceFullscreen", true)
                )
        );

        assertEquals(
                "1280x720",
                updated.getJSONObject("runtime").getString("screenSize")
        );
        assertTrue(updated.getJSONObject("runtime")
                .getBoolean("forceFullscreen"));
    }

    @Test
    public void strictCaptureRegionRejectsFallbackShapedInput() throws Exception {
        JSONObject current = GameSettingsSchema.defaults(context);
        for (String invalid : new String[]{
                "not,a,region",
                "-0.1,0,1,1",
                "0.8,0,0.2,1",
                "0,0,0.01,1"
        }) {
            try {
                GameSettingsSchema.applyUpdate(
                        context,
                        current,
                        new JSONObject().put(
                                "ocr",
                                new JSONObject().put("captureRegion", invalid)
                        )
                );
                fail("Expected invalid capture region to be rejected: " + invalid);
            }

            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("captureRegion"));
            }
        }
    }

    @Test
    public void rejectsUnknownStrongOcrPreprocessingProfile() throws Exception {
        try {
            GameSettingsSchema.applyUpdate(
                    context,
                    GameSettingsSchema.defaults(context),
                    new JSONObject().put(
                            "ocr",
                            new JSONObject().put("preprocessing", "MAGIC")
                    )
            );
            fail("Expected unsupported OCR preprocessing to be rejected.");
        }

        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("preprocessing"));
        }
    }

    @Test
    public void acceptsOutlinedStrongOcrPreprocessingProfile() throws Exception {
        JSONObject updated = GameSettingsSchema.applyUpdate(
                context,
                GameSettingsSchema.defaults(context),
                new JSONObject().put(
                        "ocr",
                        new JSONObject().put("preprocessing", "OUTLINED_TEXT")
                )
        );

        assertEquals(
                "OUTLINED_TEXT",
                updated.getJSONObject("ocr").getString("preprocessing")
        );
    }

    @Test
    public void standaloneSettingsPayloadIncludesConflictHash() throws Exception {
        JSONObject settings = GameSettingsSchema.defaults(context);
        JSONObject payload = GameApiJson.settingsPayload(settings);

        assertEquals(settings.toString(), payload.getJSONObject("settingsJson").toString());
        assertEquals(
                GameSettingsSchema.hash(settings),
                payload.getString("settingsSha256")
        );
    }

    @Test
    public void performancePresetsProduceAdvertisedRuntimePatches()
            throws Exception {
        assertEquals(
                "STABILITY",
                GameSettingsSchema.performancePatch(
                        GameSettingsSchema.PRESET_STABILITY
                ).getString("box64Preset")
        );
        assertEquals(
                "960x544",
                GameSettingsSchema.performancePatch(
                        GameSettingsSchema.PRESET_BATTERY
                ).getString("screenSize")
        );
        assertEquals(
                0,
                GameSettingsSchema.performancePatch(
                        GameSettingsSchema.PRESET_CUSTOM
                ).length()
        );
    }

    @Test
    public void customPerformancePresetPreservesExplicitRuntimePatch()
            throws Exception {
        JSONObject runtimePatch = new JSONObject()
                .put("screenSize", "1920x1080");

        JSONObject resolved = GameManagerActivity
                .resolveConfigSetForPerformancePreset(
                        runtimePatch,
                        GameSettingsSchema.PRESET_CUSTOM
                );

        assertEquals("1920x1080", resolved.getString("screenSize"));
    }

    @Test
    public void nonCustomPerformancePresetRejectsExplicitRuntimePatch()
            throws Exception {
        JSONObject runtimePatch = new JSONObject()
                .put("screenSize", "1920x1080");

        assertThrows(
                IllegalArgumentException.class,
                () -> GameManagerActivity.resolveConfigSetForPerformancePreset(
                        runtimePatch,
                        GameSettingsSchema.PRESET_BATTERY
                )
        );
    }

    @Test
    public void controlsValidateExpandedAndCompleteOrdering() throws Exception {
        JSONObject defaults = GameSettingsSchema.defaults(context);
        assertTrue(defaults.getJSONObject("controls").getBoolean("expanded"));
        org.json.JSONArray incompleteOrder = new org.json.JSONArray(
                defaults.getJSONObject("controls").getJSONArray("buttonOrder").toString()
        );
        incompleteOrder.remove(0);

        try {
            GameSettingsSchema.applyUpdate(
                    context,
                    defaults,
                    new JSONObject().put(
                            "controls",
                            new JSONObject().put(
                                    "buttonOrder",
                                    incompleteOrder
                            )
                    )
            );
            fail("Expected incomplete button ordering to be rejected.");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("buttonOrder"));
        }
    }

    @Test
    public void controlsProfileDefaultsToNoneAndRejectsNegative() throws Exception {
        JSONObject defaults = GameSettingsSchema.defaults(context);
        assertEquals(0, defaults.getJSONObject("input").getInt("controlsProfileId"));

        JSONObject updated = GameSettingsSchema.applyUpdate(
                context,
                defaults,
                new JSONObject().put("input", new JSONObject().put("controlsProfileId", 3))
        );
        assertEquals(3, updated.getJSONObject("input").getInt("controlsProfileId"));

        try {
            GameSettingsSchema.applyUpdate(
                    context,
                    defaults,
                    new JSONObject().put("input", new JSONObject().put("controlsProfileId", -1))
            );
            fail("Expected a negative controlsProfileId to be rejected.");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("controlsProfileId"));
        }
    }
}
