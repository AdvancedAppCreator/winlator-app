package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.winlator.container.ContainerOperationLock;
import com.winlator.text.GameTextConfig;
import com.winlator.text.GameTextController;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public final class ManagedGameSettingsAccess {
    private static final String OCR_NAMESPACE = "ocr";

    private ManagedGameSettingsAccess() {
    }

    public static JSONObject get(Context context, String gameId)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            ManagedGameStore store = new ManagedGameStore(context);
            ManagedGame game = store.get(gameId);
            if (game == null) return null;
            JSONObject settings = GameSettingsSchema.effective(context, game);
            if (game.settingsJson == null) {
                game.settingsJson = settings.toString();
                game.updatedAt = System.currentTimeMillis();
                store.put(game);
                GameSessionEventReporter.sendSettingsChanged(
                        context,
                        game.id,
                        OCR_NAMESPACE,
                        GameSettingsSchema.hash(settings),
                        "games/" + game.id + "/" +
                                GameApiContract.QUERY_PATH_SETTINGS
                );
            }
            return settings;
        }
    }

    public static GameTextController.ConfigStore gameTextConfigStore(
            Context context,
            String gameId,
            SharedPreferences fallbackPreferences
    ) {
        return new GameTextController.ConfigStore() {
            @Override
            public GameTextConfig load() {
                try {
                    JSONObject settings = effectiveSettings(context, gameId);
                    return settings != null
                            ? GameSettingsSchema.gameTextConfig(settings)
                            : GameTextConfig.load(fallbackPreferences);
                }
                catch (JSONException | IOException error) {
                    return GameTextConfig.load(fallbackPreferences);
                }
            }

            @Override
            public void save(GameTextConfig config) {
                if (gameId == null || gameId.isEmpty()) {
                    config.save(fallbackPreferences);
                    return;
                }
                synchronized (ContainerOperationLock.LOCK) {
                    try {
                        ManagedGameStore store = new ManagedGameStore(context);
                        ManagedGame game = store.get(gameId);
                        if (game == null) {
                            config.save(fallbackPreferences);
                            return;
                        }
                        JSONObject settings = GameSettingsSchema.effective(context, game);
                        GameSettingsSchema.putGameTextConfig(settings, config);
                        game.settingsJson = settings.toString();
                        game.updatedAt = System.currentTimeMillis();
                        store.put(game);
                        GameSessionEventReporter.sendSettingsChanged(
                                context,
                                game.id,
                                OCR_NAMESPACE,
                                GameSettingsSchema.hash(settings),
                                "games/" + game.id + "/" +
                                        GameApiContract.QUERY_PATH_SETTINGS
                        );
                    }
                    catch (JSONException | IOException error) {
                        throw new IllegalStateException(
                                "Unable to save the managed-game OCR profile.",
                                error
                        );
                    }
                }
            }

            @Override
            public boolean tiledStrongOcrEnabled() {
                return ocrBoolean(
                        context,
                        gameId,
                        "tiledStrongOcr",
                        true
                );
            }

            @Override
            public String strongOcrPreprocessing() {
                try {
                    JSONObject settings = effectiveSettings(context, gameId);
                    return settings != null
                            ? settings.getJSONObject("ocr")
                                    .optString("preprocessing", "AUTO")
                            : "AUTO";
                }
                catch (JSONException | IOException error) {
                    return "AUTO";
                }
            }

            @Override
            public boolean translationCacheEnabled() {
                return ocrBoolean(
                        context,
                        gameId,
                        "translationCacheEnabled",
                        true
                );
            }

            @Override
            public int translationCacheMaxEntries() {
                try {
                    JSONObject settings = effectiveSettings(context, gameId);
                    return settings != null
                            ? settings.getJSONObject("ocr")
                                    .optInt("translationCacheMaxEntries", 1000)
                            : 1000;
                }
                catch (JSONException | IOException error) {
                    return 1000;
                }
            }

            @Override
            public String contentLanguage() {
                try {
                    JSONObject settings = effectiveSettings(context, gameId);
                    return settings != null
                            ? settings.getJSONObject("localization")
                                    .optString("gameLanguage", "und")
                            : "und";
                }
                catch (JSONException | IOException error) {
                    return "und";
                }
            }
        };
    }

    private static boolean ocrBoolean(
            Context context,
            String gameId,
            String key,
            boolean fallback
    ) {
        try {
            JSONObject settings = effectiveSettings(context, gameId);
            return settings != null
                    ? settings.getJSONObject("ocr").optBoolean(key, fallback)
                    : fallback;
        }
        catch (JSONException | IOException error) {
            return fallback;
        }
    }

    public static JSONObject controls(Context context, String gameId)
            throws JSONException, IOException {
        JSONObject settings = effectiveSettings(context, gameId);
        return settings != null ? settings.getJSONObject("controls") : null;
    }

    /**
     * The on-screen input-controls (virtual controller) profile id selected for this managed
     * game, or {@code 0} when none is configured or for a non-managed session.
     */
    public static int controlsProfileId(Context context, String gameId) {
        if (gameId == null || gameId.isEmpty()) return 0;
        try {
            JSONObject settings = effectiveSettings(context, gameId);
            JSONObject input = settings != null ? settings.optJSONObject("input") : null;
            return input != null ? input.optInt("controlsProfileId", 0) : 0;
        }
        catch (JSONException | IOException error) {
            return 0;
        }
    }

    public static void setControlsExpanded(
            Context context,
            String gameId,
            boolean expanded
    ) throws JSONException, IOException {
        if (gameId == null || gameId.isEmpty()) return;
        synchronized (ContainerOperationLock.LOCK) {
            ManagedGameStore store = new ManagedGameStore(context);
            ManagedGame game = store.get(gameId);
            if (game == null) return;
            JSONObject settings = GameSettingsSchema.effective(context, game);
            settings.getJSONObject("controls").put("expanded", expanded);
            game.settingsJson = settings.toString();
            game.updatedAt = System.currentTimeMillis();
            store.put(game);
        }
    }

    private static JSONObject effectiveSettings(Context context, String gameId)
            throws JSONException, IOException {
        return gameId == null || gameId.isEmpty()
                ? GameSettingsSchema.defaults(context)
                : get(context, gameId);
    }

    /**
     * Whether the realtime black-screen / hang troubleshooter is enabled for this managed
     * game. Returns {@code false} on any error or for a non-managed session.
     */
    public static boolean stallTroubleshooterEnabled(Context context, String gameId) {
        if (gameId == null || gameId.isEmpty()) return false;
        try {
            JSONObject settings = effectiveSettings(context, gameId);
            return settings != null
                    && settings.getJSONObject("diagnostics")
                            .optBoolean("stallTroubleshooter", false);
        }
        catch (JSONException | IOException error) {
            return false;
        }
    }
}
