package com.winlator.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured, immutable snapshot of everything the troubleshooter reasons about for one
 * stall evaluation: the classified cause, device GPU, the active container configuration,
 * runtime state, and parsed log signatures. Built from a {@link StallSignals} snapshot plus
 * the device GPU string. Pure data + a couple of matching helpers used by the knowledge base.
 */
final class StallEvidence {
    private static final Pattern MISSING_DLL = Pattern.compile(
            "(?i)([a-z0-9_\\-\\.]+\\.dll)[^\\n]*(not found|failed|missing)"
    );

    final String cause;
    final String gpu;
    final JSONObject config;
    final String logBlob;
    final JSONArray logSignatures;
    final boolean runtimeReached;
    final long elapsedMillis;
    final long frameSilenceMillis;
    final long outputSilenceMillis;

    private StallEvidence(
            String cause,
            String gpu,
            JSONObject config,
            String logBlob,
            JSONArray logSignatures,
            boolean runtimeReached,
            long elapsedMillis,
            long frameSilenceMillis,
            long outputSilenceMillis
    ) {
        this.cause = cause;
        this.gpu = gpu;
        this.config = config;
        this.logBlob = logBlob;
        this.logSignatures = logSignatures;
        this.runtimeReached = runtimeReached;
        this.elapsedMillis = elapsedMillis;
        this.frameSilenceMillis = frameSilenceMillis;
        this.outputSilenceMillis = outputSilenceMillis;
    }

    static StallEvidence from(String cause, StallSignals signals, String gpu) {
        StringBuilder blob = new StringBuilder();
        for (String line : signals.recentOutput) {
            if (line != null) blob.append(line).append('\n');
        }
        String lowerBlob = blob.toString().toLowerCase(Locale.ENGLISH);
        return new StallEvidence(
                cause != null ? cause : "unknown",
                gpu != null ? gpu.toLowerCase(Locale.ENGLISH) : "",
                signals.appliedConfig != null ? signals.appliedConfig : new JSONObject(),
                lowerBlob,
                parseSignatures(lowerBlob),
                signals.runtimeReached,
                signals.elapsedMillis(),
                signals.frameSilenceMillis(),
                signals.outputSilenceMillis()
        );
    }

    boolean logContains(String token) {
        return token != null && !token.isEmpty()
                && logBlob.contains(token.toLowerCase(Locale.ENGLISH));
    }

    boolean gpuContains(String token) {
        return token != null && !token.isEmpty()
                && gpu.contains(token.toLowerCase(Locale.ENGLISH));
    }

    /**
     * True when the current container config value for {@code key} contains {@code value}
     * (case-insensitive). Lenient by design so "vortek,gladio" satisfies "vortek".
     */
    boolean configContains(String key, String value) {
        String current = config.optString(key, "");
        return current.toLowerCase(Locale.ENGLISH)
                .contains(value == null ? "" : value.toLowerCase(Locale.ENGLISH));
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("cause", cause)
                .put("gpu", gpu)
                .put("graphicsDriver", config.optString("graphicsDriver", ""))
                .put("dxwrapper", config.optString("dxwrapper", ""))
                .put("box64Preset", config.optString("box64Preset", ""))
                .put("screenSize", config.optString("screenSize", ""))
                .put("runtimeReached", runtimeReached)
                .put("elapsedMillis", elapsedMillis)
                .put("frameSilenceMillis", frameSilenceMillis)
                .put("outputSilenceMillis", outputSilenceMillis)
                .put("logSignatures", logSignatures);
    }

    private static JSONArray parseSignatures(String lowerBlob) {
        Set<String> found = new LinkedHashSet<>();
        String[][] tokens = {
                {"unity", "unity"},
                {"il2cpp", "il2cpp"},
                {"unreal", "unreal_engine"},
                {"vulkan", "vulkan"},
                {"failed to allocate client window", "vulkan_surface_alloc_failed"},
                {"x11drv_vulkan_surface_create", "vulkan_surface_create"},
                {"device lost", "vulkan_device_lost"},
                {"vk_error", "vulkan_error"},
                {"dxvk", "dxvk"},
                {"wined3d", "wined3d"},
                {"wglsetpixelformat", "gl_pixel_format_failed"},
                {"using gdi present", "gdi_software_present"},
                {"uniform components", "shader_uniform_limit"},
                {"d3d11", "d3d11"},
                {"d3d12", "d3d12"},
                {"d3d8", "d3d8"},
                {"ddraw", "directdraw"},
                {"mesa", "mesa"},
                {"turnip", "turnip"},
                {"winepulse", "audio_pulse"},
                {"winealsa", "audio_alsa"},
                {"libpulse", "audio_pulse"},
                {"libasound", "audio_alsa"},
                {"dwmapi", "dwm"},
                {"mono", "dotnet_mono"},
                {".net", "dotnet"},
                {"out of memory", "out_of_memory"},
                {"shader", "shader_activity"}
        };
        for (String[] token : tokens) {
            if (lowerBlob.contains(token[0])) found.add(token[1]);
        }
        Matcher matcher = MISSING_DLL.matcher(lowerBlob);
        int guard = 0;
        while (matcher.find() && guard < 5) {
            found.add("missing_dll:" + matcher.group(1));
            guard++;
        }
        JSONArray array = new JSONArray();
        for (String signature : found) array.put(signature);
        return array;
    }
}
