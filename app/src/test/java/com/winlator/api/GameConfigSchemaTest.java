package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GameConfigSchemaTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void hashIsStableAcrossObjectKeyOrder() throws Exception {
        JSONObject first = new JSONObject()
                .put("screenSize", "1280x720")
                .put("forceFullscreen", true)
                .put("hudMode", 1);
        JSONObject second = new JSONObject()
                .put("hudMode", 1)
                .put("forceFullscreen", true)
                .put("screenSize", "1280x720");

        assertEquals(GameConfigSchema.hash(first), GameConfigSchema.hash(second));
        second.put("hudMode", 2);
        assertNotEquals(GameConfigSchema.hash(first), GameConfigSchema.hash(second));
    }

    @Test
    public void schemaAdvertisesEverySupportedField() throws Exception {
        JSONObject schema = GameConfigSchema.build(context);
        JSONArray fields = schema.getJSONArray("fields");

        assertEquals(GameConfigSchema.SCHEMA_VERSION, schema.getInt("schemaVersion"));
        assertEquals(GameApiJson.CONFIG_FIELDS.size(), fields.length());
        for (int index = 0; index < fields.length(); index++) {
            JSONObject field = fields.getJSONObject(index);
            assertTrue(GameApiJson.CONFIG_FIELDS.contains(field.getString("key")));
            assertTrue(field.has("wireType"));
            assertTrue(field.has("editor"));
            assertTrue(field.has("default"));
        }
    }

    @Test
    public void conflictSafeUpdateRejectsUnsupportedValues() throws Exception {
        JSONObject update = new JSONObject()
                .put("baseConfigSha256", repeat("A", 64))
                .put("set", new JSONObject().put("dxwrapper", "unsupported"));

        try {
            GameConfigSchema.validateUpdate(context, update);
            fail("Expected unsupported wrapper to be rejected");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("dxwrapper"));
        }
    }

    @Test
    public void schemaAdvertisesWinVersionEnumFromNativeVersions() throws Exception {
        JSONArray fields = GameConfigSchema.build(context).getJSONArray("fields");
        JSONObject field = null;
        for (int index = 0; index < fields.length(); index++) {
            if ("winVersion".equals(fields.getJSONObject(index).getString("key"))) {
                field = fields.getJSONObject(index);
                break;
            }
        }

        assertTrue(field != null);
        assertEquals("string", field.getString("wireType"));
        assertEquals("enum", field.getString("editor"));
        assertEquals("win10", field.getString("default"));
        assertEquals(12, field.getJSONArray("options").length());
        assertEquals("win11", field.getJSONArray("options")
                .getJSONObject(0).getString("value"));
        assertEquals("Windows 11", field.getJSONArray("options")
                .getJSONObject(0).getString("label"));
    }

    @Test
    public void winVersionValidationRejectsUnknownIdsForEveryPatchStyle() throws Exception {
        JSONObject invalid = new JSONObject().put("winVersion", "windows10");
        try {
            GameConfigSchema.validateLegacyPatch(context, invalid);
            fail("Expected invalid legacy winVersion to be rejected");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("winVersion"));
        }

        JSONObject valid = new JSONObject()
                .put("baseConfigSha256", repeat("A", 64))
                .put("set", new JSONObject().put("winVersion", "win10"));
        assertEquals(
                "win10",
                GameConfigSchema.validateUpdate(context, valid)
                        .getJSONObject("set")
                        .getString("winVersion")
        );
    }

    @Test
    public void unityTextureLimitIsAdvertisedAndStrictlyValidated() throws Exception {
        JSONArray fields = GameConfigSchema.build(context).getJSONArray("fields");
        JSONObject textureLimit = null;
        for (int index = 0; index < fields.length(); index++) {
            JSONObject field = fields.getJSONObject(index);
            if ("unityTextureLimit".equals(field.getString("key"))) {
                textureLimit = field;
                break;
            }
        }

        assertTrue(textureLimit != null);
        assertEquals("off", textureLimit.getString("default"));
        assertEquals(4, textureLimit.getJSONArray("options").length());
        GameConfigSchema.validateLegacyPatch(
                context,
                new JSONObject().put("unityTextureLimit", "2")
        );
        try {
            GameConfigSchema.validateLegacyPatch(
                    context,
                    new JSONObject().put("unityTextureLimit", "4")
            );
            fail("Expected invalid Unity texture limit to be rejected");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unityTextureLimit"));
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
