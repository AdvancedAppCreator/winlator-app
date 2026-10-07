package com.winlator.api;

import android.content.Context;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.DXWrappers;
import com.winlator.container.DesktopMode;
import com.winlator.container.DriverPolicy;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.WineThemeManager;
import com.winlator.win32.WinVersions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class GameConfigSchema {
    static final int SCHEMA_VERSION = 7;

    private GameConfigSchema() {
    }

    static JSONObject build(Context context) throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("schemaVersion", SCHEMA_VERSION);
        JSONArray fields = new JSONArray();
        fields.put(field(
                "screenSize",
                "string",
                "resolution",
                Container.DEFAULT_SCREEN_SIZE,
                "Windows desktop resolution.",
                screenSizeOptions(),
                null,
                new JSONObject()
                        .put("pattern", "^[0-9]+x[0-9]+$")
                        .put("example", "1280x720")
        ));
        fields.put(field(
                "winVersion",
                "string",
                "enum",
                WinVersions.DEFAULT_VERSION,
                "Windows version reported by Wine applications.",
                winVersionOptions(),
                null,
                null
        ));
        fields.put(field(
                "envVars",
                "string",
                "env_vars",
                Container.DEFAULT_ENV_VARS,
                "Environment variables applied to this game.",
                null,
                null,
                keyValueFormat(" ", "=")
        ));
        fields.put(field(
                "cpuList",
                "string",
                "cpu_set",
                Container.getFallbackCPUList(),
                "Host CPU indices available to 64-bit guest processes.",
                null,
                null,
                cpuListFormat()
        ));
        fields.put(field(
                "cpuListWoW64",
                "string",
                "cpu_set",
                Container.getFallbackCPUList(),
                "Host CPU indices available to 32-bit guest processes.",
                null,
                null,
                cpuListFormat()
        ));
        fields.put(field(
                "graphicsDriver",
                "string",
                "enum",
                getDefaultGraphicsDriver(context),
                "Vulkan and OpenGL renderer pair.",
                graphicsDriverOptions(),
                null,
                new JSONObject()
                        .put("delimiter", ",")
                        .put("order", new JSONArray().put("vulkan").put("opengl"))
        ));
        fields.put(field(
                "graphicsDriverConfig",
                "string",
                "opaque_string",
                "",
                "Driver-specific Vulkan and OpenGL configuration.",
                null,
                "graphicsDriver",
                new JSONObject()
                        .put("pairDelimiter", "|")
                        .put("entryDelimiter", ",")
                        .put("assignment", "=")
                        .put("orderSignificant", true)
        ));
        fields.put(field(
                "dxwrapper",
                "string",
                "enum",
                Container.DEFAULT_DXWRAPPER,
                "Direct3D 8-11 translation layer.",
                options(
                        option(DXWrappers.DXVK, "DXVK"),
                        option(DXWrappers.WINED3D, "WineD3D")
                ),
                "graphicsDriver",
                null
        ));
        fields.put(field(
                "dxwrapperConfig",
                "string",
                "opaque_string",
                "",
                "Direct3D 8-11 and DirectX 12 wrapper configuration.",
                null,
                "dxwrapper",
                new JSONObject()
                        .put("pairDelimiter", "|")
                        .put("entryDelimiter", ",")
                        .put("assignment", "=")
                        .put("orderSignificant", true)
        ));
        fields.put(field(
                "audioDriver",
                "string",
                "enum",
                Container.DEFAULT_AUDIO_DRIVER,
                "Windows audio backend.",
                options(
                        option(AudioDrivers.ALSA, "ALSA"),
                        option(AudioDrivers.PULSEAUDIO, "PulseAudio"),
                        option(AudioDrivers.SILENT, "Silent (no sound)"),
                        option(AudioDrivers.DISABLED, "Disabled")
                ),
                null,
                null
        ));
        fields.put(field(
                "audioDriverConfig",
                "string",
                "opaque_string",
                "",
                "Audio-driver-specific key/value configuration.",
                null,
                "audioDriver",
                keyValueFormat(",", "=")
        ));
        fields.put(field(
                "wincomponents",
                "string",
                "component_set",
                Container.DEFAULT_WINCOMPONENTS,
                "Legacy compatibility field. Public builds use Wine built-in components.",
                null,
                null,
                new JSONObject()
                        .put("delimiter", ",")
                        .put("assignment", "=")
                        .put("allowedKeys", new JSONArray()
                                .put("direct3d")
                                .put("directsound")
                                .put("directmusic")
                                .put("directshow")
                                .put("directplay")
                                .put("xaudio")
                                .put("vcrun2005")
                                .put("vcrun2010")
                                .put("wmdecoder"))
                        .put("allowedValues", new JSONArray().put("0"))
                        .put("orderSignificant", false)
        ));
        fields.put(field(
                "hudMode",
                "integer",
                "integer_enum",
                0,
                "Performance HUD detail level.",
                options(
                        option(0, "Disabled"),
                        option(1, "Simple (FPS)"),
                        option(2, "Full")
                ),
                null,
                null
        ));
        fields.put(field(
                "startupSelection",
                "integer",
                "integer_enum",
                Container.STARTUP_SELECTION_ESSENTIAL,
                "Windows service startup profile.",
                options(
                        option(0, "Normal"),
                        option(1, "Essential"),
                        option(2, "Aggressive")
                ),
                null,
                null
        ));
        fields.put(field(
                "box64Preset",
                "string",
                "enum",
                Box64Preset.DEFAULT,
                "Box64 compatibility/performance preset.",
                box64PresetOptions(context),
                null,
                null
        ));
        fields.put(field(
                "desktopTheme",
                "string",
                "opaque_string",
                WineThemeManager.DEFAULT_DESKTOP_THEME,
                "Wine desktop theme and background configuration.",
                null,
                null,
                new JSONObject()
                        .put("delimiter", ",")
                        .put("orderSignificant", true)
                        .put("example", WineThemeManager.DEFAULT_DESKTOP_THEME)
        ));
        fields.put(field(
                "driverPolicy",
                "string",
                "enum",
                DriverPolicy.DEFAULT,
                "Graphics/DX driver version policy: default keeps the saved versions, "
                        + "latest uses the newest installed drivers, specific pins explicit "
                        + "versions set in the driver configs.",
                options(
                        option(DriverPolicy.DEFAULT, "Default drivers"),
                        option(DriverPolicy.LATEST, "Latest drivers"),
                        option(DriverPolicy.SPECIFIC, "Specific drivers")
                ),
                null,
                null
        ));
        fields.put(field(
                "desktopMode",
                "string",
                "enum",
                DesktopMode.AUTO,
                "Wine desktop mode: auto (single-app for launched games), nogui "
                        + "(borderless single application), or shell (full Wine desktop).",
                options(
                        option(DesktopMode.AUTO, "Auto"),
                        option(DesktopMode.NOGUI, "Single application (nogui)"),
                        option(DesktopMode.SHELL, "Full desktop (shell)")
                ),
                null,
                null
        ));
        fields.put(field(
                "forceFullscreen",
                "boolean",
                "boolean",
                false,
                "Force Windows fullscreen behavior for this game.",
                null,
                null,
                null
        ));
        fields.put(field(
                UnityLaunchOverlay.CONFIG_KEY,
                "string",
                "enum",
                UnityLaunchOverlay.DISABLED,
                "Limits Unity texture mip resolution through a container-private runtime "
                        + "overlay. The original game files are never modified.",
                options(
                        option(UnityLaunchOverlay.DISABLED, "Disabled"),
                        option("1", "Half-resolution textures"),
                        option("2", "Quarter-resolution textures"),
                        option("3", "Eighth-resolution textures")
                ),
                null,
                new JSONObject().put("engine", "UNITY")
        ));
        fields.put(field(
                "launchArguments",
                "string",
                "text",
                "",
                "Extra command-line arguments appended to the game executable at launch. "
                        + "Useful for engine flags, e.g. \"--disable-gpu --in-process-gpu\" "
                        + "to fix black screens in NW.js/Electron/Chromium games.",
                null,
                null,
                new JSONObject()
                        .put("multiline", false)
                        .put("example", "--disable-gpu --in-process-gpu")
                        .put("presets", new JSONArray()
                                .put(new JSONObject()
                                        .put("label", "NW.js/Chromium fix")
                                        .put("value", "--disable-gpu --in-process-gpu")))
        ));
        schema.put("fields", fields);
        return schema;
    }

    static JSONObject validateUpdate(Context context, JSONObject update) throws JSONException {
        if (update == null) throw new IllegalArgumentException("config_update_json is required.");
        String baseHash = update.optString("baseConfigSha256", "");
        if (!baseHash.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("baseConfigSha256 must be a SHA-256 hex digest.");
        }
        JSONObject set = update.optJSONObject("set");
        if (set == null) throw new IllegalArgumentException("config_update_json.set must be an object.");
        validateSet(context, set, true);

        JSONObject result = new JSONObject();
        result.put("baseConfigSha256", baseHash.toUpperCase(Locale.US));
        result.put("set", set);
        return result;
    }

    static void validateLegacyPatch(Context context, JSONObject config) throws JSONException {
        validateSet(context, config, false);
    }

    static String hash(JSONObject config) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonicalize(config).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format(Locale.US, "%02X", value));
            return result.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void validateSet(Context context, JSONObject config, boolean strictOptions)
            throws JSONException {
        for (Iterator<String> keys = config.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!GameApiJson.CONFIG_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Unsupported config field: " + key);
            }
            Object value = config.get(key);
            if ("forceFullscreen".equals(key)) {
                requireType(key, value, Boolean.class, "a Boolean");
            }
            else if ("hudMode".equals(key) || "startupSelection".equals(key)) {
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException(key + " must be an integer.");
                }
            }
            else if (!(value instanceof String)) {
                throw new IllegalArgumentException(key + " must be a String.");
            }
        }

        if (config.has("screenSize")) validateScreenSize(config.getString("screenSize"));
        if (config.has("hudMode")) validateRange(config.getInt("hudMode"), 0, 2, "hudMode");
        if (config.has("startupSelection")) {
            validateRange(config.getInt("startupSelection"), 0, 2, "startupSelection");
        }
        if (config.has("winVersion")
                && !WinVersions.isSupported(config.getString("winVersion"))) {
            throw new IllegalArgumentException(
                    "Unsupported winVersion: " + config.getString("winVersion")
            );
        }
        if (config.has(UnityLaunchOverlay.CONFIG_KEY)) {
            String value = config.getString(UnityLaunchOverlay.CONFIG_KEY);
            if (!UnityLaunchOverlay.DISABLED.equals(value)
                    && !"1".equals(value)
                    && !"2".equals(value)
                    && !"3".equals(value)) {
                throw new IllegalArgumentException(
                        "Unsupported unityTextureLimit: "+value
                );
            }
        }
        if (strictOptions) {
            if (config.has("graphicsDriver")) {
                String value = config.getString("graphicsDriver");
                if (!graphicsDriverValues().contains(value)) {
                    throw new IllegalArgumentException("Unsupported graphicsDriver: " + value);
                }
            }
            if (config.has("dxwrapper")) {
                String value = config.getString("dxwrapper");
                if (!DXWrappers.DXVK.equals(value) && !DXWrappers.WINED3D.equals(value)) {
                    throw new IllegalArgumentException("Unsupported dxwrapper: " + value);
                }
            }
            if (config.has("audioDriver")) {
                String value = config.getString("audioDriver");
                if (!AudioDrivers.ALSA.equals(value) && !AudioDrivers.PULSEAUDIO.equals(value)
                        && !AudioDrivers.SILENT.equals(value) && !AudioDrivers.DISABLED.equals(value)) {
                    throw new IllegalArgumentException("Unsupported audioDriver: " + value);
                }
            }
            if (config.has("box64Preset") &&
                    Box64PresetManager.getPreset(context, config.getString("box64Preset")) == null) {
                throw new IllegalArgumentException(
                        "Unsupported box64Preset: " + config.getString("box64Preset")
                );
            }
            if (config.has("driverPolicy")
                    && !DriverPolicy.isValid(config.getString("driverPolicy"))) {
                throw new IllegalArgumentException(
                        "Unsupported driverPolicy: " + config.getString("driverPolicy")
                );
            }
            if (config.has("desktopMode")
                    && !DesktopMode.isValid(config.getString("desktopMode"))) {
                throw new IllegalArgumentException(
                        "Unsupported desktopMode: " + config.getString("desktopMode")
                );
            }
        }
    }

    private static JSONObject field(
            String key,
            String wireType,
            String editor,
            Object defaultValue,
            String description,
            JSONArray options,
            String dependsOn,
            JSONObject format
    ) throws JSONException {
        JSONObject field = new JSONObject();
        field.put("key", key);
        field.put("wireType", wireType);
        field.put("editor", editor);
        field.put("default", defaultValue);
        field.put("description", description);
        if (options != null) field.put("options", options);
        if (dependsOn != null) field.put("dependsOn", dependsOn);
        if (format != null) field.put("format", format);
        return field;
    }

    private static JSONArray screenSizeOptions() throws JSONException {
        return options(
                option("640x360", "640 x 360"),
                option("640x480", "640 x 480"),
                option("800x600", "800 x 600"),
                option("854x480", "854 x 480"),
                option("960x544", "960 x 544"),
                option("1024x768", "1024 x 768"),
                option("1280x720", "1280 x 720"),
                option("1280x800", "1280 x 800"),
                option("1280x1024", "1280 x 1024"),
                option("1366x768", "1366 x 768"),
                option("1440x900", "1440 x 900"),
                option("1600x900", "1600 x 900"),
                option("1920x1080", "1920 x 1080")
        );
    }

    private static JSONArray winVersionOptions() throws JSONException {
        JSONArray options = new JSONArray();
        for (WinVersions.WinVersion winVersion : WinVersions.getWinVersions()) {
            options.put(option(winVersion.version, winVersion.description));
        }
        return options;
    }

    private static JSONArray graphicsDriverOptions() throws JSONException {
        JSONArray options = new JSONArray();
        for (String value : graphicsDriverValues()) {
            String[] parts = value.split(",");
            options.put(option(
                    value,
                    GraphicsDrivers.getName(parts[0]) + " / " + GraphicsDrivers.getName(parts[1])
            ));
        }
        return options;
    }

    private static String getDefaultGraphicsDriver(Context context) {
        try {
            return GraphicsDrivers.getDefaultDriver(context);
        }
        catch (LinkageError error) {
            return GraphicsDrivers.DEFAULT_VULKAN_DRIVER
                    + ","
                    + GraphicsDrivers.DEFAULT_OPENGL_DRIVER;
        }
    }

    private static Set<String> graphicsDriverValues() {
        TreeSet<String> values = new TreeSet<>();
        String[] vulkan = {GraphicsDrivers.TURNIP, GraphicsDrivers.VORTEK};
        String[] openGl = {GraphicsDrivers.ZINK, GraphicsDrivers.VIRGL, GraphicsDrivers.GLADIO};
        for (String vk : vulkan) {
            for (String gl : openGl) values.add(vk + "," + gl);
        }
        return values;
    }

    private static JSONArray box64PresetOptions(Context context) throws JSONException {
        JSONArray options = new JSONArray();
        for (Box64Preset preset : Box64PresetManager.getPresets(context)) {
            options.put(option(preset.id, preset.name));
        }
        return options;
    }

    private static JSONObject keyValueFormat(String delimiter, String assignment)
            throws JSONException {
        return new JSONObject()
                .put("delimiter", delimiter)
                .put("assignment", assignment)
                .put("escaping", "No escaping; values must not contain the delimiter.")
                .put("orderSignificant", false);
    }

    private static JSONObject cpuListFormat() throws JSONException {
        return new JSONObject()
                .put("delimiter", ",")
                .put("tokenPattern", "^[0-9]+$")
                .put("orderSignificant", false);
    }

    private static JSONObject option(Object value, String label) throws JSONException {
        return new JSONObject().put("value", value).put("label", label);
    }

    private static JSONArray options(JSONObject... values) {
        JSONArray options = new JSONArray();
        for (JSONObject value : values) options.put(value);
        return options;
    }

    private static void requireType(
            String key,
            Object value,
            Class<?> expected,
            String description
    ) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException(key + " must be " + description + ".");
        }
    }

    private static void validateScreenSize(String screenSize) {
        if (!screenSize.matches("[0-9]+x[0-9]+")) {
            throw new IllegalArgumentException("screenSize must use WIDTHxHEIGHT.");
        }
        String[] dimensions = screenSize.split("x");
        int width = Integer.parseInt(dimensions[0]);
        int height = Integer.parseInt(dimensions[1]);
        if (width < 1 || width > Short.MAX_VALUE || height < 1 || height > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "screenSize dimensions must be between 1 and " + Short.MAX_VALUE + "."
            );
        }
    }

    private static void validateRange(int value, int min, int max, String key) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ".");
        }
    }

    private static String canonicalize(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            TreeSet<String> keys = new TreeSet<>();
            for (Iterator<String> iterator = object.keys(); iterator.hasNext(); ) {
                keys.add(iterator.next());
            }
            ArrayList<String> entries = new ArrayList<>();
            for (String key : keys) {
                entries.add(JSONObject.quote(key) + ":" + canonicalize(object.opt(key)));
            }
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            ArrayList<String> entries = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                entries.add(canonicalize(array.opt(index)));
            }
            return "[" + String.join(",", entries) + "]";
        }
        if (value instanceof String) return JSONObject.quote((String)value);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        return JSONObject.quote(value.toString());
    }
}
