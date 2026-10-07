package com.winlator.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GameTextTranslationCacheTest {
    @Test
    public void normalizesEquivalentSourceText() {
        assertEquals(
                "Menu option",
                GameTextTranslationCache.normalize("  Menu \n option  ")
        );
        assertEquals(
                "ABC",
                GameTextTranslationCache.normalize("ＡＢＣ")
        );
    }

    @Test
    public void separatesLanguagePairsAndGlossaryVersions() {
        Context context = ApplicationProvider.getApplicationContext();
        GameTextTranslationCache cache =
                new GameTextTranslationCache(context, "language-test", 10);
        cache.clear();
        cache.put("ja", "en", "g1", "開始", "Start");

        assertEquals("Start", cache.get("ja", "en", "g1", "開始"));
        assertNull(cache.get("ja", "fr", "g1", "開始"));
        assertNull(cache.get("ja", "en", "g2", "開始"));
        cache.close();
    }

    @Test
    public void evictsLeastRecentlyUsedEntries() {
        Context context = ApplicationProvider.getApplicationContext();
        GameTextTranslationCache cache =
                new GameTextTranslationCache(context, "lru-test", 2);
        cache.clear();
        cache.put("ja", "en", "g", "one", "1");
        cache.put("ja", "en", "g", "two", "2");
        assertEquals("1", cache.get("ja", "en", "g", "one"));
        cache.put("ja", "en", "g", "three", "3");

        assertNull(cache.get("ja", "en", "g", "two"));
        assertEquals("1", cache.get("ja", "en", "g", "one"));
        assertEquals("3", cache.get("ja", "en", "g", "three"));
        cache.close();
    }

    @Test
    public void statusAndClearUseTheSamePersistentNamespace() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        GameTextTranslationCache cache = new GameTextTranslationCache(
                context,
                "status-game",
                10
        );
        cache.clear();
        cache.put("ja", "en", "v1", "設定", "Settings");
        cache.close();

        assertEquals(
                1,
                GameTextCacheManager.status(context, "status-game")
                        .getInt("entries")
        );
        GameTextCacheManager.clear(context, "status-game");
        assertEquals(
                0,
                GameTextCacheManager.status(context, "status-game")
                        .getInt("entries")
        );
    }
}
