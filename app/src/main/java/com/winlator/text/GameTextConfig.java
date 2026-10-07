package com.winlator.text;

import android.content.SharedPreferences;
import android.graphics.RectF;

import java.util.Locale;

public final class GameTextConfig {
    public static final String PREF_MODE = "game_text_mode";
    public static final String PREF_INTERVAL = "game_text_interval";
    public static final String PREF_REGION = "game_text_region";
    public static final String PREF_REPLACEMENTS = "game_text_replacements";
    public static final String PREF_SOURCE_LANGUAGE = "game_text_source_language";
    public static final String PREF_TARGET_LANGUAGE = "game_text_target_language";

    public enum Mode {
        OFF,
        SUBTITLE,
        REPLACE;

        public static Mode fromPreference(String value) {
            try {
                return valueOf(value == null ? "" : value.toUpperCase(Locale.ENGLISH));
            }
            catch (IllegalArgumentException ignored) {
                return OFF;
            }
        }
    }

    public enum Script {
        LATIN,
        CHINESE,
        DEVANAGARI,
        JAPANESE,
        KOREAN;

        public static Script fromPreference(String value) {
            try {
                return valueOf(value == null ? "" : value.toUpperCase(Locale.ENGLISH));
            }
            catch (IllegalArgumentException ignored) {
                return LATIN;
            }
        }
    }

    public final Mode mode;
    public final Script script;
    public final long intervalMillis;
    public final RectF captureRegion;
    public final String replacements;
    public final String sourceLanguage;
    public final String targetLanguage;

    public GameTextConfig(
            Mode mode,
            long intervalMillis,
            RectF captureRegion,
            String replacements,
            String sourceLanguage,
            String targetLanguage
    ) {
        this.mode = mode;
        this.intervalMillis = Math.max(250, intervalMillis);
        this.captureRegion = sanitizeRegion(captureRegion);
        this.replacements = replacements == null ? "" : replacements;
        this.sourceLanguage = GameTextLanguage.normalizeSource(sourceLanguage);
        this.targetLanguage = GameTextLanguage.normalizeTarget(targetLanguage);
        this.script = GameTextLanguage.recommendedScript(this.sourceLanguage);
    }

    public static GameTextConfig load(SharedPreferences preferences) {
        return new GameTextConfig(
                Mode.fromPreference(preferences.getString(PREF_MODE, Mode.OFF.name())),
                preferences.getLong(PREF_INTERVAL, 1000),
                parseRegion(preferences.getString(PREF_REGION, null)),
                preferences.getString(PREF_REPLACEMENTS, ""),
                preferences.getString(PREF_SOURCE_LANGUAGE, GameTextLanguage.AUTO),
                preferences.getString(PREF_TARGET_LANGUAGE, GameTextLanguage.ORIGINAL)
        );
    }

    public void save(SharedPreferences preferences) {
        preferences.edit()
                .putString(PREF_MODE, mode.name())
                .putLong(PREF_INTERVAL, intervalMillis)
                .putString(PREF_REGION, serializeRegion(captureRegion))
                .putString(PREF_REPLACEMENTS, replacements)
                .putString(PREF_SOURCE_LANGUAGE, sourceLanguage)
                .putString(PREF_TARGET_LANGUAGE, targetLanguage)
                .apply();
    }

    public boolean isTranslationEnabled() {
        return !GameTextLanguage.ORIGINAL.equals(targetLanguage);
    }

    public static RectF parseRegion(String value) {
        if (value == null || value.trim().isEmpty()) return defaultRegion();
        String[] parts = value.split(",");
        if (parts.length != 4) return defaultRegion();
        try {
            return sanitizeRegion(new RectF(
                    Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3])
            ));
        }
        catch (NumberFormatException ignored) {
            return defaultRegion();
        }
    }

    public static String serializeRegion(RectF region) {
        RectF safe = sanitizeRegion(region);
        return safe.left + "," + safe.top + "," + safe.right + "," + safe.bottom;
    }

    public static RectF defaultRegion() {
        return new RectF(0.0f, 0.55f, 1.0f, 1.0f);
    }

    private static RectF sanitizeRegion(RectF region) {
        RectF safe = region != null ? new RectF(region) : defaultRegion();
        safe.left = clamp(safe.left);
        safe.top = clamp(safe.top);
        safe.right = clamp(safe.right);
        safe.bottom = clamp(safe.bottom);
        if (safe.width() < 0.05f || safe.height() < 0.05f) return defaultRegion();
        return safe;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
