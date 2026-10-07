package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

import androidx.preference.PreferenceManager;

import com.winlator.text.GameTextConfig;
import com.winlator.text.GameTextLanguage;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;

final class GameSettingsSchema {
    static final int SCHEMA_VERSION = 4;
    static final String PRESET_CUSTOM = "CUSTOM";
    static final String PRESET_STABILITY = "STABILITY";
    static final String PRESET_BALANCED = "BALANCED";
    static final String PRESET_PERFORMANCE = "PERFORMANCE";
    static final String PRESET_BATTERY = "BATTERY";

    private static final Set<String> TOP_LEVEL_KEYS = setOf(
            "runtime",
            "localization",
            "ocr",
            "controls",
            "input",
            "performance",
            "diagnostics"
    );
    private static final Set<String> LOCALIZATION_KEYS = setOf(
            "gameLanguage",
            "runtimeLocale"
    );
    private static final Set<String> OCR_KEYS = setOf(
            "enabled",
            "mode",
            "sourceLanguage",
            "targetLanguage",
            "intervalMillis",
            "captureRegion",
            "preprocessing",
            "replacements",
            "tiledStrongOcr",
            "translationCacheEnabled",
            "translationCacheMaxEntries"
    );
    private static final Set<String> CONTROL_KEYS = setOf(
            "buttonOrder",
            "visibleButtons",
            "buttonSize",
            "opacity",
            "expanded"
    );
    private static final Set<String> INPUT_KEYS = setOf(
            "controlsProfileId"
    );
    private static final Set<String> PERFORMANCE_KEYS = setOf("preset");
    private static final Set<String> DIAGNOSTICS_KEYS = setOf("stallTroubleshooter");
    private static final Set<String> QUICK_BUTTONS = setOf(
            "fullscreen",
            "input_mode",
            "game_text",
            "strong_ocr",
            "exit"
    );

    private GameSettingsSchema() {
    }

    static JSONObject build(Context context) throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("schemaVersion", SCHEMA_VERSION);
        schema.put("updateExtra", GameApiContract.EXTRA_SETTINGS_UPDATE_JSON);
        schema.put("namespaces", new JSONArray()
                .put(namespace(
                        "runtime",
                        "API v4 compatibility and container runtime settings.",
                        GameConfigSchema.build(context).getJSONArray("fields")
                ))
                .put(namespace(
                        "localization",
                        "Per-game content language and Wine process locale.",
                        new JSONArray()
                                .put(field(
                                        "gameLanguage",
                                        "string",
                                        "language_tag",
                                        "und",
                                        null,
                                        "BCP-47 language used by the game content."
                                ))
                                .put(field(
                                        "runtimeLocale",
                                        "string",
                                        "enum",
                                        "system",
                                        runtimeLocaleOptions(),
                                        "POSIX locale applied only to this game's Wine process."
                                ))
                ))
                .put(namespace(
                        "ocr",
                        "Per-game OCR, translation, glossary and cache behavior.",
                        ocrFields(defaults(context).getJSONObject("ocr"))
                ))
                .put(namespace(
                        "controls",
                        "Per-game quick-control layout.",
                        controlsFields()
                ))
                .put(namespace(
                        "input",
                        "Per-game on-screen input-controls (virtual controller) profile.",
                        inputFields(context)
                ))
                .put(namespace(
                        "performance",
                        "Named compatibility/performance profile selection.",
                        new JSONArray().put(field(
                                "preset",
                                "string",
                                "enum",
                                PRESET_CUSTOM,
                                options(
                                        PRESET_CUSTOM,
                                        PRESET_STABILITY,
                                        PRESET_BALANCED,
                                        PRESET_PERFORMANCE,
                                        PRESET_BATTERY
                                ),
                                "Selecting a preset applies its advertised runtime patch."
                        ))
                ))
                .put(namespace(
                        "diagnostics",
                        "Per-game in-session diagnostics behavior.",
                        new JSONArray().put(field(
                                "stallTroubleshooter",
                                "boolean",
                                "boolean",
                                false,
                                null,
                                "Run the realtime black-screen / hang troubleshooter for "
                                        + "this game and surface one-tap fixes when it stalls."
                        ))
                )));
        schema.put("performancePresets", performancePresets());
        return schema;
    }

    static JSONObject defaults(Context context) throws JSONException {
        JSONObject settings = platformDefaults(context);
        deepMerge(settings, GlobalSettingsStore.savedPatch(context));
        validateComplete(context, settings);
        return settings;
    }

    private static JSONObject platformDefaults(Context context) throws JSONException {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        GameTextConfig text = GameTextConfig.load(preferences);
        JSONObject settings = new JSONObject();
        settings.put("runtime", new JSONObject());
        settings.put("localization", new JSONObject()
                .put("gameLanguage", "und")
                .put("runtimeLocale", "system"));
        settings.put("ocr", gameTextConfigToJson(text)
                .put("enabled", false)
                .put("sourceLanguage", "en")
                .put("targetLanguage", "en")
                .put("preprocessing", "AUTO")
                .put("tiledStrongOcr", true)
                .put("translationCacheEnabled", true)
                .put("translationCacheMaxEntries", 1000));
        settings.put("controls", new JSONObject()
                .put("buttonOrder", quickButtons())
                .put("visibleButtons", quickButtons())
                .put("buttonSize", "MEDIUM")
                .put("opacity", 0.85)
                .put("expanded", true));
        settings.put("input", new JSONObject()
                .put("controlsProfileId", 0));
        settings.put("performance", new JSONObject().put("preset", PRESET_CUSTOM));
        settings.put("diagnostics", new JSONObject().put("stallTroubleshooter", false));
        return settings;
    }

    static JSONObject effective(Context context, ManagedGame game) throws JSONException {
        JSONObject result = defaults(context);
        if (game.settingsJson != null) {
            deepMerge(result, new JSONObject(game.settingsJson));
        }
        if (game.configJson != null) {
            result.put("runtime", new JSONObject(game.configJson));
        }
        stripDeprecatedOcrKeys(result.optJSONObject("ocr"));
        validateComplete(context, result);
        return result;
    }

    private static void stripDeprecatedOcrKeys(JSONObject ocr) {
        if (ocr == null) return;
        ocr.remove("script");
        ocr.remove("scriptMode");
    }

    static JSONObject validateUpdate(Context context, JSONObject update) throws JSONException {
        if (update == null) {
            throw new IllegalArgumentException("settings_update_json is required.");
        }
        String baseHash = update.optString("baseSettingsSha256", "");
        if (!baseHash.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    "baseSettingsSha256 must be a SHA-256 hex digest."
            );
        }
        JSONObject set = update.optJSONObject("set");
        if (set == null) {
            throw new IllegalArgumentException("settings_update_json.set must be an object.");
        }
        validatePatch(context, set);
        JSONObject result = new JSONObject();
        result.put("baseSettingsSha256", baseHash.toUpperCase(Locale.US));
        result.put("set", new JSONObject(set.toString()));
        return result;
    }

    static JSONObject applyUpdate(
            Context context,
            JSONObject current,
            JSONObject set
    ) throws JSONException {
        JSONObject updated = new JSONObject(current.toString());
        deepMerge(updated, set);
        validateComplete(context, updated);
        return updated;
    }

    static String hash(JSONObject settings) {
        return GameConfigSchema.hash(settings);
    }

    static GameTextConfig gameTextConfig(JSONObject settings) throws JSONException {
        JSONObject ocr = settings.getJSONObject("ocr");
        boolean enabled = ocr.optBoolean("enabled", false);
        GameTextConfig.Mode mode = GameTextConfig.Mode.valueOf(ocr.getString("mode"));
        if (!enabled) mode = GameTextConfig.Mode.OFF;
        else if (mode == GameTextConfig.Mode.OFF) mode = GameTextConfig.Mode.SUBTITLE;
        return new GameTextConfig(
                mode,
                ocr.getLong("intervalMillis"),
                GameTextConfig.parseRegion(ocr.getString("captureRegion")),
                ocr.getString("replacements"),
                ocr.getString("sourceLanguage"),
                ocr.getString("targetLanguage")
        );
    }

    static void putGameTextConfig(JSONObject settings, GameTextConfig config)
            throws JSONException {
        JSONObject previous = settings.optJSONObject("ocr");
        JSONObject ocr = gameTextConfigToJson(config);
        ocr.put("enabled", config.mode != GameTextConfig.Mode.OFF);
        ocr.put(
                "tiledStrongOcr",
                previous == null || previous.optBoolean("tiledStrongOcr", true)
        );
        ocr.put(
                "preprocessing",
                previous != null
                        ? previous.optString("preprocessing", "AUTO")
                        : "AUTO"
        );
        ocr.put(
                "translationCacheEnabled",
                previous == null || previous.optBoolean("translationCacheEnabled", true)
        );
        ocr.put(
                "translationCacheMaxEntries",
                previous != null
                        ? previous.optInt("translationCacheMaxEntries", 1000)
                        : 1000
        );
        settings.put("ocr", ocr);
    }

    static JSONObject performancePatch(String preset) throws JSONException {
        switch (preset) {
            case PRESET_STABILITY:
                return new JSONObject()
                        .put("box64Preset", "STABILITY")
                        .put("screenSize", "1280x720")
                        .put("startupSelection", 1);
            case PRESET_BALANCED:
                return new JSONObject()
                        .put("box64Preset", "INTERMEDIATE")
                        .put("screenSize", "1280x720")
                        .put("startupSelection", 1);
            case PRESET_PERFORMANCE:
                return new JSONObject()
                        .put("box64Preset", "PERFORMANCE")
                        .put("startupSelection", 2);
            case PRESET_BATTERY:
                return new JSONObject()
                        .put("box64Preset", "INTERMEDIATE")
                        .put("screenSize", "960x544")
                        .put("startupSelection", 1);
            case PRESET_CUSTOM:
                return new JSONObject();
            default:
                throw new IllegalArgumentException("Unsupported performance preset: " + preset);
        }
    }

    private static JSONObject gameTextConfigToJson(GameTextConfig config)
            throws JSONException {
        return new JSONObject()
                .put("mode", config.mode.name())
                .put("sourceLanguage", config.sourceLanguage)
                .put("targetLanguage", config.targetLanguage)
                .put("intervalMillis", config.intervalMillis)
                .put(
                        "captureRegion",
                        GameTextConfig.serializeRegion(config.captureRegion)
                )
                .put("replacements", config.replacements);
    }

    private static void validatePatch(Context context, JSONObject patch)
            throws JSONException {
        validateKeys(patch, TOP_LEVEL_KEYS, "settings");
        if (patch.has("runtime")) {
            requireObject(patch, "runtime");
            GameConfigSchema.validateLegacyPatch(
                    context,
                    patch.getJSONObject("runtime")
            );
        }
        if (patch.has("localization")) {
            requireObject(patch, "localization");
            validateKeys(
                    patch.getJSONObject("localization"),
                    LOCALIZATION_KEYS,
                    "localization"
            );
        }
        if (patch.has("ocr")) {
            requireObject(patch, "ocr");
            stripDeprecatedOcrKeys(patch.getJSONObject("ocr"));
            validateKeys(patch.getJSONObject("ocr"), OCR_KEYS, "ocr");
        }
        if (patch.has("controls")) {
            requireObject(patch, "controls");
            validateKeys(patch.getJSONObject("controls"), CONTROL_KEYS, "controls");
        }
        if (patch.has("input")) {
            requireObject(patch, "input");
            validateKeys(patch.getJSONObject("input"), INPUT_KEYS, "input");
        }
        if (patch.has("performance")) {
            requireObject(patch, "performance");
            validateKeys(
                    patch.getJSONObject("performance"),
                    PERFORMANCE_KEYS,
                    "performance"
            );
        }
        if (patch.has("diagnostics")) {
            requireObject(patch, "diagnostics");
            validateKeys(
                    patch.getJSONObject("diagnostics"),
                    DIAGNOSTICS_KEYS,
                    "diagnostics"
            );
        }
    }

    private static void validateComplete(Context context, JSONObject settings)
            throws JSONException {
        validatePatch(context, settings);
        JSONObject localization = settings.getJSONObject("localization");
        String gameLanguage = localization.getString("gameLanguage");
        if (!"und".equals(gameLanguage) &&
                !gameLanguage.matches("(?i)^[a-z]{2,3}([_-][a-z0-9]{2,8})*$")) {
            throw new IllegalArgumentException("gameLanguage must be a BCP-47 tag or und.");
        }
        String runtimeLocale = localization.getString("runtimeLocale");
        if (!runtimeLocaleValues().contains(runtimeLocale)) {
            throw new IllegalArgumentException("Unsupported runtimeLocale: " + runtimeLocale);
        }

        JSONObject ocr = settings.getJSONObject("ocr");
        ocr.getBoolean("enabled");
        enumValue(GameTextConfig.Mode.class, ocr.getString("mode"), "ocr.mode");
        String preprocessing = ocr.getString("preprocessing");
        if (!setOf("AUTO", "COLOR", "HIGH_CONTRAST", "BRIGHT_TEXT", "OUTLINED_TEXT")
                .contains(preprocessing)) {
            throw new IllegalArgumentException(
                    "Unsupported ocr.preprocessing: " + preprocessing
            );
        }
        int interval = ocr.getInt("intervalMillis");
        if (interval < 250 || interval > 5000) {
            throw new IllegalArgumentException(
                    "ocr.intervalMillis must be between 250 and 5000."
            );
        }
        parseStrictRegion(ocr.getString("captureRegion"));
        if (ocr.getString("replacements").getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("ocr.replacements is too large.");
        }
        ocr.getBoolean("tiledStrongOcr");
        ocr.getBoolean("translationCacheEnabled");
        int maxEntries = ocr.getInt("translationCacheMaxEntries");
        if (maxEntries < 100 || maxEntries > 5000) {
            throw new IllegalArgumentException(
                    "ocr.translationCacheMaxEntries must be between 100 and 5000."
            );
        }

        JSONObject controls = settings.getJSONObject("controls");
        validateButtons(controls.getJSONArray("buttonOrder"), true);
        validateButtons(controls.getJSONArray("visibleButtons"), false);
        String size = controls.getString("buttonSize");
        if (!"SMALL".equals(size) && !"MEDIUM".equals(size) && !"LARGE".equals(size)) {
            throw new IllegalArgumentException("Unsupported controls.buttonSize: " + size);
        }
        double opacity = controls.getDouble("opacity");
        if (opacity < 0.25 || opacity > 1.0) {
            throw new IllegalArgumentException(
                    "controls.opacity must be between 0.25 and 1.0."
            );
        }
        controls.getBoolean("expanded");
        int controlsProfileId = settings.getJSONObject("input").getInt("controlsProfileId");
        if (controlsProfileId < 0) {
            throw new IllegalArgumentException(
                    "input.controlsProfileId must be a non-negative profile id (0 = none)."
            );
        }
        performancePatch(settings.getJSONObject("performance").getString("preset"));
        settings.getJSONObject("diagnostics").getBoolean("stallTroubleshooter");
    }

    private static RectF parseStrictRegion(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "ocr.captureRegion must contain four normalized coordinates."
            );
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "ocr.captureRegion must contain four normalized coordinates."
            );
        }
        try {
            float left = Float.parseFloat(parts[0]);
            float top = Float.parseFloat(parts[1]);
            float right = Float.parseFloat(parts[2]);
            float bottom = Float.parseFloat(parts[3]);
            if (!Float.isFinite(left) || !Float.isFinite(top) ||
                    !Float.isFinite(right) || !Float.isFinite(bottom) ||
                    left < 0 || top < 0 || right > 1 || bottom > 1 ||
                    right - left < 0.05f || bottom - top < 0.05f) {
                throw new IllegalArgumentException(
                        "ocr.captureRegion must be ordered, normalized, and at least 5% wide and high."
                );
            }
            return new RectF(left, top, right, bottom);
        }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "ocr.captureRegion must contain numeric coordinates."
            );
        }
    }

    private static void validateButtons(JSONArray buttons, boolean requireAll)
            throws JSONException {
        HashSet<String> found = new HashSet<>();
        for (int index = 0; index < buttons.length(); index++) {
            String button = buttons.getString(index);
            if (!QUICK_BUTTONS.contains(button) || !found.add(button)) {
                throw new IllegalArgumentException(
                        "Quick-control button identifiers must be supported and unique."
                );
            }
        }
        if (requireAll && found.size() != QUICK_BUTTONS.size()) {
            throw new IllegalArgumentException(
                    "controls.buttonOrder must contain every quick-control button once."
            );
        }
    }

    private static void validateKeys(
            JSONObject object,
            Set<String> allowed,
            String namespace
    ) {
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "Unsupported " + namespace + " field: " + key
                );
            }
        }
    }

    private static void requireObject(JSONObject parent, String key) {
        if (parent.optJSONObject(key) == null) {
            throw new IllegalArgumentException(key + " must be an object.");
        }
    }

    private static <T extends Enum<T>> void enumValue(
            Class<T> type,
            String value,
            String key
    ) {
        try {
            Enum.valueOf(type, value);
        }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported " + key + ": " + value);
        }
    }

    private static void deepMerge(JSONObject target, JSONObject patch)
            throws JSONException {
        for (Iterator<String> keys = patch.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = patch.get(key);
            if (value instanceof JSONObject && target.optJSONObject(key) != null) {
                deepMerge(target.getJSONObject(key), (JSONObject)value);
            }
            else {
                target.put(key, value);
            }
        }
    }

    private static JSONObject namespace(
            String key,
            String description,
            JSONArray fields
    ) throws JSONException {
        return new JSONObject()
                .put("key", key)
                .put(
                        "scope",
                        "localization".equals(key) || "runtime".equals(key)
                                || "input".equals(key)
                                ? "game"
                                : "game_and_global_default"
                )
                .put("description", description)
                .put("fields", fields);
    }

    private static JSONObject field(
            String key,
            String wireType,
            String editor,
            Object defaultValue,
            JSONArray options,
            String description
    ) throws JSONException {
        JSONObject field = new JSONObject()
                .put("key", key)
                .put("wireType", wireType)
                .put("editor", editor)
                .put("default", defaultValue)
                .put("description", description);
        if (options != null) field.put("options", options);
        return field;
    }

    private static JSONObject field(
            String key,
            String wireType,
            String editor,
            Object defaultValue,
            JSONArray options,
            String description,
            String dependsOn
    ) throws JSONException {
        JSONObject field = field(key, wireType, editor, defaultValue, options, description);
        if (dependsOn != null) field.put("dependsOn", dependsOn);
        return field;
    }

    private static JSONArray languageOptions(List<GameTextLanguage.Option> languages)
            throws JSONException {
        JSONArray options = new JSONArray();
        for (GameTextLanguage.Option language : languages) {
            options.put(new JSONObject()
                    .put("value", language.tag)
                    .put("label", language.label));
        }
        return options;
    }

    private static JSONArray ocrFields(JSONObject defaults) throws JSONException {
        return new JSONArray()
                .put(field("enabled", "boolean", "boolean",
                        defaults.optBoolean("enabled", false), null,
                        "Enable the on-screen OCR auto-translator for this game."))
                .put(field("mode", "string", "enum", defaults.getString("mode"),
                        options("OFF", "SUBTITLE", "REPLACE"), "OCR display mode."))
                .put(field("preprocessing", "string", "enum",
                        defaults.getString("preprocessing"),
                        options(
                                "AUTO",
                                "COLOR",
                                "HIGH_CONTRAST",
                                "BRIGHT_TEXT",
                                "OUTLINED_TEXT"
                        ),
                        "Strong OCR preprocessing; AUTO merges complementary passes."))
                .put(field("sourceLanguage", "string", "enum",
                        defaults.getString("sourceLanguage"),
                        languageOptions(GameTextLanguage.sourceOptions()),
                        "Language to translate from (the game's text language).",
                        "enabled"))
                .put(field("targetLanguage", "string", "enum",
                        defaults.getString("targetLanguage"),
                        languageOptions(GameTextLanguage.targetOptions()),
                        "Language to translate into.",
                        "enabled"))
                .put(field("intervalMillis", "integer", "range",
                        defaults.getInt("intervalMillis"), null,
                        "Live OCR interval, 250-5000 ms."))
                .put(field("captureRegion", "string", "normalized_rect",
                        defaults.getString("captureRegion"), null,
                        "Normalized left,top,right,bottom capture rectangle."))
                .put(field("replacements", "string", "multiline",
                        defaults.getString("replacements"), null,
                        "Per-game glossary and replacement rules."))
                .put(field("tiledStrongOcr", "boolean", "boolean", true, null,
                        "Use overlapping tiles during on-demand Strong OCR."))
                .put(field("translationCacheEnabled", "boolean", "boolean", true, null,
                        "Cache repeated offline translations for this game."))
                .put(field("translationCacheMaxEntries", "integer", "range", 1000, null,
                        "Persistent translation-cache bound, 100-5000 entries."));
    }

    private static JSONArray controlsFields() throws JSONException {
        return new JSONArray()
                .put(field("buttonOrder", "array", "reorder", quickButtons(), null,
                        "Order of all quick controls."))
                .put(field("visibleButtons", "array", "multi_select", quickButtons(), null,
                        "Quick controls visible while expanded."))
                .put(field("buttonSize", "string", "enum", "MEDIUM",
                        options("SMALL", "MEDIUM", "LARGE"), "Quick-control size."))
                .put(field("opacity", "number", "range", 0.85, null,
                        "Quick-control opacity, 0.25-1.0."))
                .put(field("expanded", "boolean", "boolean", true, null,
                        "Expanded state, persisted per managed game."));
    }

    private static JSONArray inputFields(Context context) throws JSONException {
        return new JSONArray()
                .put(field("controlsProfileId", "integer", "enum", 0,
                        controlsProfileOptions(context),
                        "On-screen input-controls (virtual controller) profile to auto-enable "
                                + "for this game. 0 = none. Options are the profiles configured "
                                + "in Winlator; refetch this schema to see newly added profiles."));
    }

    private static JSONArray controlsProfileOptions(Context context) throws JSONException {
        JSONArray options = new JSONArray();
        options.put(new JSONObject().put("value", 0).put("label", "None"));
        try {
            InputControlsManager manager = new InputControlsManager(context);
            for (ControlsProfile profile : manager.getProfiles(true)) {
                options.put(new JSONObject()
                        .put("value", profile.id)
                        .put("label", profile.getName()));
            }
        }
        catch (Exception ignored) {
        }
        return options;
    }


    private static JSONArray runtimeLocaleOptions() throws JSONException {
        JSONArray options = new JSONArray();
        for (String value : runtimeLocaleValues()) {
            options.put(new JSONObject().put("value", value).put("label", value));
        }
        return options;
    }

    private static Set<String> runtimeLocaleValues() {
        return setOf(
                "system",
                "en_US.UTF-8",
                "pt_BR.UTF-8",
                "ru_RU.UTF-8",
                "ja_JP.UTF-8",
                "zh_CN.UTF-8",
                "zh_TW.UTF-8",
                "ko_KR.UTF-8"
        );
    }

    private static JSONArray performancePresets() throws JSONException {
        JSONArray presets = new JSONArray();
        for (String preset : new String[]{
                PRESET_STABILITY,
                PRESET_BALANCED,
                PRESET_PERFORMANCE,
                PRESET_BATTERY
        }) {
            presets.put(new JSONObject()
                    .put("id", preset)
                    .put("set", performancePatch(preset)));
        }
        return presets;
    }

    private static JSONArray quickButtons() {
        return new JSONArray()
                .put("fullscreen")
                .put("input_mode")
                .put("game_text")
                .put("strong_ocr")
                .put("exit");
    }

    private static JSONArray options(String... values) throws JSONException {
        JSONArray options = new JSONArray();
        for (String value : values) {
            options.put(new JSONObject().put("value", value).put("label", value));
        }
        return options;
    }

    private static Set<String> setOf(String... values) {
        HashSet<String> result = new HashSet<>();
        for (String value : values) result.add(value);
        return result;
    }
}
