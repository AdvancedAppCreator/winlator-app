package com.winlator.text;

import static org.junit.Assert.assertEquals;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GameTextConfigTest {
    @Test
    public void regionRoundTripsAndInvalidRegionsFallBack() {
        RectF region = new RectF(0.1f, 0.2f, 0.9f, 0.8f);
        RectF restored = GameTextConfig.parseRegion(GameTextConfig.serializeRegion(region));

        assertEquals(region.left, restored.left, 0.0001f);
        assertEquals(region.bottom, restored.bottom, 0.0001f);
        assertEquals(GameTextConfig.defaultRegion(), GameTextConfig.parseRegion("broken"));
    }

    @Test
    public void languageSelectionsFallBackToOfflineSafeDefaults() {
        assertEquals(GameTextLanguage.AUTO, GameTextLanguage.normalizeSource("unsupported"));
        assertEquals(GameTextLanguage.ORIGINAL, GameTextLanguage.normalizeTarget("unsupported"));
        assertEquals(GameTextConfig.Script.JAPANESE, GameTextLanguage.recommendedScript("ja"));
        assertEquals(GameTextConfig.Script.DEVANAGARI, GameTextLanguage.recommendedScript("hi"));
    }
}
