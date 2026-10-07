package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class GameConfigSuggesterTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ManagedGame gameFor(File dir) {
        ManagedGame game = new ManagedGame();
        game.id = "game-1";
        game.gamePath = dir.getAbsolutePath();
        return game;
    }

    private File dirWith(String... entries) throws Exception {
        File dir = folder.newFolder();
        for (String entry : entries) {
            new File(dir, entry).createNewFile();
        }
        return dir;
    }

    @Test
    public void detectsRpgMakerMvAndSuggestsGpuWorkaround() throws Exception {
        File dir = folder.newFolder();
        new File(dir, "nw.dll").createNewFile();
        new File(dir, "www").mkdir();
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals("RPG_MAKER_MV_MZ", result.getString("engine"));
        assertEquals(
                "--disable-gpu --in-process-gpu",
                result.getJSONObject("suggestedConfig").getString("launchArguments")
        );
    }

    @Test
    public void detectsUnityAndSuggestsDxvk() throws Exception {
        File dir = dirWith("UnityPlayer.dll");
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals("UNITY", result.getString("engine"));
        assertEquals("dxvk", result.getJSONObject("suggestedConfig").getString("dxwrapper"));
    }

    @Test
    public void detectsRenpyFromArchive() throws Exception {
        File dir = dirWith("archive.rpa");
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals("RENPY", result.getString("engine"));
    }

    @Test
    public void detectsGodotMobileRenderer() throws Exception {
        File dir = dirWith("game.pck");
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals("GODOT", result.getString("engine"));
        assertEquals(
                "--rendering-method mobile",
                result.getJSONObject("suggestedConfig").getString("launchArguments")
        );
    }

    @Test
    public void detectsLiveMakerAndSuggestsJapaneseCompatibility() throws Exception {
        File dir = folder.newFolder();
        new File(dir, "\uac8c\uc784.dat").createNewFile();
        File executable = new File(dir, "game.exe");
        try (java.io.RandomAccessFile output =
                     new java.io.RandomAccessFile(executable, "rw")) {
            output.write(new byte[]{'M', 'Z'});
            output.setLength(64);
            output.seek(32);
            output.write(new byte[]{'v', 'f'});
            writeU32(output, 102);
            writeU32(output, 0);
            output.seek(64);
            writeU32(output, 32);
            output.write(new byte[]{'l', 'v'});
        }
        ManagedGame game = gameFor(dir);
        game.executablePath = executable.getAbsolutePath();

        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                game
        );

        assertEquals("LIVEMAKER", result.getString("engine"));
        assertEquals(
                "ddrawWrapper=cnc-ddraw",
                result.getJSONObject("suggestedConfig").getString("dxwrapperConfig")
        );
        assertEquals(
                "ja_JP.UTF-8",
                result.getJSONObject("suggestedSettings")
                        .getJSONObject("localization")
                        .getString("runtimeLocale")
        );
        assertEquals(
                "livemaker-font-enumeration",
                result.getJSONObject("runnerRecommendation")
                        .getJSONArray("knownIssues")
                        .getString(0)
        );
    }

    @Test
    public void unknownEngineProducesEmptySuggestion() throws Exception {
        File dir = dirWith("readme.txt");
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals("UNKNOWN", result.getString("engine"));
        assertEquals(0, result.getJSONObject("suggestedConfig").length());
        assertEquals("low", result.getString("confidence"));
    }

    @Test
    public void missingDirectoryIsHandledGracefully() throws Exception {
        ManagedGame game = new ManagedGame();
        game.id = "game-2";
        game.gamePath = new File(folder.getRoot(), "does-not-exist").getAbsolutePath();
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                game
        );

        assertEquals("UNKNOWN", result.getString("engine"));
        assertTrue(result.getJSONObject("suggestedConfig").length() == 0);
    }

    @Test
    public void detectsJapaneseFromKanaFilename() throws Exception {
        File dir = folder.newFolder();
        new File(dir, "\u30b2\u30fc\u30e0.dat").createNewFile(); // ゲーム.dat
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals(
                "ja_JP.UTF-8",
                result.getJSONObject("suggestedSettings")
                        .getJSONObject("localization")
                        .getString("runtimeLocale")
        );
    }

    @Test
    public void detectsKoreanFromHangulFilename() throws Exception {
        File dir = folder.newFolder();
        new File(dir, "\uac8c\uc784.dat").createNewFile(); // 게임.dat
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals(
                "ko_KR.UTF-8",
                result.getJSONObject("suggestedSettings")
                        .getJSONObject("localization")
                        .getString("runtimeLocale")
        );
    }

    @Test
    public void asciiGameHasNoLocaleSuggestion() throws Exception {
        File dir = dirWith("readme.txt", "data.pak");
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals(0, result.getJSONObject("suggestedSettings").length());
    }

    @Test
    public void rgssGameRecommendsJoiplayWithKnownIssue() throws Exception {
        File dir = dirWith("Game.exe", "Game.rgss3a");
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        );

        assertEquals("RPG_MAKER_RGSS", result.getString("engine"));
        JSONObject rec = result.getJSONObject("runnerRecommendation");
        assertEquals("joiplay", rec.getString("preferredRunner"));
        assertEquals("poor", rec.getString("winlatorSuitability"));
        assertTrue(rec.getBoolean("nativeEngineInterpreterPreferred"));
        boolean hasDeadlockTag = false;
        for (int i = 0; i < rec.getJSONArray("knownIssues").length(); i++) {
            if ("wine-mmdevapi-audio-deadlock".equals(rec.getJSONArray("knownIssues").getString(i))) {
                hasDeadlockTag = true;
            }
        }
        assertTrue(hasDeadlockTag);
    }

    @Test
    public void renpyRecommendsNativeInterpreter() throws Exception {
        File dir = dirWith("archive.rpa");
        JSONObject rec = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        ).getJSONObject("runnerRecommendation");

        assertEquals("joiplay", rec.getString("preferredRunner"));
        assertTrue(rec.getBoolean("nativeEngineInterpreterPreferred"));
    }

    @Test
    public void unityRecommendsWinlator() throws Exception {
        File dir = dirWith("UnityPlayer.dll");
        JSONObject rec = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        ).getJSONObject("runnerRecommendation");

        assertEquals("winlator", rec.getString("preferredRunner"));
        assertEquals("ideal", rec.getString("winlatorSuitability"));
        assertTrue(!rec.getBoolean("nativeEngineInterpreterPreferred"));
        assertEquals(0, rec.getJSONArray("knownIssues").length());
    }

    @Test
    public void unknownEngineStillRecommendsWinlatorAtLowConfidence() throws Exception {
        File dir = dirWith("readme.txt");
        JSONObject rec = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                gameFor(dir)
        ).getJSONObject("runnerRecommendation");

        assertEquals("winlator", rec.getString("preferredRunner"));
        assertEquals("low", rec.getString("confidence"));
    }

    @Test
    public void missingDirectoryStillProducesRunnerRecommendation() throws Exception {
        ManagedGame game = new ManagedGame();
        game.id = "game-3";
        game.gamePath = new File(folder.getRoot(), "nope").getAbsolutePath();
        JSONObject result = GameConfigSuggester.suggest(
                RuntimeEnvironment.getApplication(),
                game
        );

        assertTrue(result.has("runnerRecommendation"));
        assertEquals(
                "winlator",
                result.getJSONObject("runnerRecommendation").getString("preferredRunner")
        );
    }

    @Test
    public void legacyRegistryValueProducesMigrationMetadata() throws Exception {
        JSONObject result = baseSuggestion();
        JSONObject current = new JSONObject().put("screenSize", "1280x720");

        GameConfigSuggester.augmentWinVersionSuggestion(
                result,
                current,
                new ManagedWinVersion.State(
                        "win7",
                        ManagedGame.WIN_VERSION_SOURCE_REGISTRY_LEGACY
                ),
                null
        );

        assertEquals(
                "win10",
                result.getJSONObject("suggestedConfig").getString("winVersion")
        );
        JSONObject metadata = result.getJSONObject("suggestionMetadata")
                .getJSONObject("winVersion");
        assertEquals("legacy_default_migration", metadata.getString("source"));
        assertEquals("high", metadata.getString("confidence"));
        assertTrue(!metadata.getBoolean("automaticApplySafe"));
        assertEquals("win7", metadata.getString("currentEffectiveValue"));
        assertEquals("win10", metadata.getString("targetValue"));
    }

    @Test
    public void diagnosticRequirementTakesPriorityOverLegacyMigration() throws Exception {
        JSONObject result = baseSuggestion();
        JSONObject current = new JSONObject().put("winVersion", "win7");

        GameConfigSuggester.augmentWinVersionSuggestion(
                result,
                current,
                new ManagedWinVersion.State(
                        "win7",
                        ManagedGame.WIN_VERSION_SOURCE_EXPLICIT
                ),
                "win11"
        );

        assertEquals(
                "win11",
                result.getJSONObject("suggestedConfig").getString("winVersion")
        );
        JSONObject metadata = result.getJSONObject("suggestionMetadata")
                .getJSONObject("winVersion");
        assertEquals("diagnostic_requirement", metadata.getString("source"));
        assertEquals("win7", metadata.getString("currentEffectiveValue"));
        assertEquals("win11", metadata.getString("targetValue"));
    }

    private JSONObject baseSuggestion() throws Exception {
        return new JSONObject()
                .put("suggestedConfig", new JSONObject())
                .put("rationale", new org.json.JSONArray())
                .put("evidence", new org.json.JSONArray());
    }

    private static void writeU32(java.io.RandomAccessFile output, int value)
            throws java.io.IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }
}
