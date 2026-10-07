package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GlobalSettingsStoreTest {
    private Context context;

    @Before
    public void clearGlobalSettings() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("api_global_settings", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void globalDefaultsAreInheritedByNewGameSettings() throws Exception {
        GlobalSettingsStore store = new GlobalSettingsStore(context);
        JSONObject current = store.get();
        store.update(new JSONObject()
                .put("baseSettingsSha256", GameSettingsSchema.hash(current))
                .put("set", new JSONObject().put(
                        "controls",
                        new JSONObject().put("opacity", 0.5)
                )));

        assertEquals(
                0.5,
                GameSettingsSchema.defaults(context)
                        .getJSONObject("controls")
                        .getDouble("opacity"),
                0.0001
        );
    }

    @Test
    public void staleHashReturnsAuthoritativeCurrentSettings() throws Exception {
        GlobalSettingsStore store = new GlobalSettingsStore(context);
        try {
            store.update(new JSONObject()
                    .put("baseSettingsSha256", repeat("0", 64))
                    .put("set", new JSONObject().put(
                            "ocr",
                            new JSONObject().put("tiledStrongOcr", false)
                    )));
            fail("Expected a global settings conflict.");
        }
        catch (GlobalSettingsStore.SettingsConflictException expected) {
            assertTrue(expected.current.has("ocr"));
        }
    }

    @Test
    public void globalLocalizationIsRejected() throws Exception {
        GlobalSettingsStore store = new GlobalSettingsStore(context);
        JSONObject current = store.get();
        try {
            store.update(new JSONObject()
                    .put("baseSettingsSha256", GameSettingsSchema.hash(current))
                    .put("set", new JSONObject().put(
                            "localization",
                            new JSONObject().put("runtimeLocale", "ja_JP.UTF-8")
                    )));
            fail("Expected global localization to be rejected.");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Global defaults"));
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
