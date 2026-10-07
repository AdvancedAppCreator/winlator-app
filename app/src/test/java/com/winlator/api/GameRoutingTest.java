package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GameRoutingTest {
    @Test
    public void lifecycleIntentTargetsSelectedGamesContainer() {
        ManagedGame shared = game("shared", 4);
        ManagedGame isolated = game("isolated", 9);

        Intent sharedIntent = GameSessionEventReporter.configure(
                new Intent(),
                shared,
                true,
                "ready"
        );
        Intent isolatedIntent = GameSessionEventReporter.configure(
                new Intent(),
                isolated,
                false,
                "ready"
        );

        assertEquals(4, sharedIntent.getIntExtra(GameApiContract.EXTRA_CONTAINER_ID, 0));
        assertEquals(9, isolatedIntent.getIntExtra(GameApiContract.EXTRA_CONTAINER_ID, 0));
        assertEquals("shared", sharedIntent.getStringExtra(GameApiContract.EXTRA_GAME_ID));
        assertEquals("isolated", isolatedIntent.getStringExtra(GameApiContract.EXTRA_GAME_ID));
    }

    @Test
    public void sharedGamesRetainIndependentLaunchConfiguration() throws Exception {
        ManagedGame first = game("first", 4);
        first.arguments = "--first";
        first.configJson = new JSONObject()
                .put("screenSize", "1280x720")
                .put("box64Preset", "INTERMEDIATE")
                .put("forceFullscreen", true)
                .toString();
        ManagedGame second = game("second", 4);
        second.arguments = "--second";
        second.configJson = new JSONObject()
                .put("screenSize", "1920x1080")
                .put("box64Preset", "COMPATIBILITY")
                .put("forceFullscreen", false)
                .toString();

        ManagedGame restoredFirst = ManagedGame.fromJSONObject(first.toJSONObject());
        ManagedGame restoredSecond = ManagedGame.fromJSONObject(second.toJSONObject());

        assertEquals("--first", restoredFirst.arguments);
        assertEquals("--second", restoredSecond.arguments);
        assertNotEquals(restoredFirst.configJson, restoredSecond.configJson);
        assertEquals(
                "1280x720",
                new JSONObject(restoredFirst.configJson).getString("screenSize")
        );
        assertEquals(
                "1920x1080",
                new JSONObject(restoredSecond.configJson).getString("screenSize")
        );
        assertTrue(new JSONObject(restoredFirst.configJson).getBoolean("forceFullscreen"));
        assertFalse(new JSONObject(restoredSecond.configJson).getBoolean("forceFullscreen"));
    }

    @Test
    public void managedLaunchAppliesSavedFullscreenPreference() throws Exception {
        ManagedGame game = game("fullscreen", 4);
        game.configJson = new JSONObject()
                .put("forceFullscreen", true)
                .toString();
        Intent intent = new Intent();

        GameApiJson.applyLaunchConfig(
                ApplicationProvider.getApplicationContext(),
                intent,
                game
        );

        assertTrue(intent.getBooleanExtra(
                GameApiContract.INTERNAL_EXTRA_FORCE_FULLSCREEN,
                false
        ));
    }

    private ManagedGame game(String id, int containerId) {
        ManagedGame game = new ManagedGame();
        game.id = id;
        game.title = id;
        game.containerId = containerId;
        game.containerPolicy = ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT;
        game.containerKey = ManagedGame.DEFAULT_SHARED_CONTAINER_KEY;
        game.state = "ready";
        return game;
    }
}
