package com.winlator.api;

import com.winlator.box64.Box64Preset;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Realtime classifier for black-screen / hang states. Unlike
 * {@link ManagedDiagnosticClassifier} (which runs post-mortem on process exit), this
 * evaluates a live {@link StallSignals} snapshot and decides whether the session appears
 * stuck, why, and which one-tap fixes to offer. It is a pure function so it can be unit
 * tested and driven with deterministic timing.
 *
 * <p>It is intentionally a sibling of the post-mortem classifier rather than an extension
 * of it, so the existing AGM diagnostic contract and its tests are untouched.</p>
 */
final class ManagedStallClassifier {
    static final int CLASSIFICATION_VERSION = 1;

    private static final Pattern GRAPHICS_ACTIVITY = Pattern.compile(
            "(?i)(vulkan|vk_error|vkcreate|dxvk|d3d|turnip|freedreno|adreno|mesa|zink|"
                    + "gladio|vortek|virgl|swapchain|opengl|wined3d|renderer|gpu)"
    );
    private static final Pattern DEPENDENCY_ACTIVITY = Pattern.compile(
            "(?i)(c0000135|import_dll|api-ms-win|vcruntime|msvcp|msvcr|\\.dll[^\\n]*"
                    + "(not found|failed|missing)|module[^\\n]*not found|waiting for|"
                    + "could not load)"
    );
    private static final Pattern SHADER_ACTIVITY = Pattern.compile(
            "(?i)(shader|pipeline cache|compiling|precompil|glsl|spir-v|spirv)"
    );

    private ManagedStallClassifier() {
    }

    static JSONObject classify(StallSignals signals, StallThresholds thresholds)
            throws JSONException {
        JSONObject diagnosis = new JSONObject()
                .put("classificationVersion", CLASSIFICATION_VERSION)
                .put("stalled", false)
                .put("cause", "healthy")
                .put("confidence", "low")
                .put("elapsedMillis", signals.elapsedMillis())
                .put("runtimeReached", signals.runtimeReached)
                .put("suggestions", new JSONArray());

        if (signals.runtimeReached) {
            long frameSilence = signals.frameSilenceMillis();
            if (frameSilence >= 0 && frameSilence >= thresholds.frameSilenceMillis) {
                return stall(
                        diagnosis,
                        "presenting_stall",
                        "The game window stopped drawing",
                        "A game window opened but no new frames have been presented for a "
                                + "while. This usually means the graphics translation layer "
                                + "or GPU driver stalled after the first frame.",
                        "medium",
                        graphicsAndResolution(signals)
                );
            }
            return diagnosis;
        }

        long elapsed = signals.elapsedMillis();
        if (elapsed < thresholds.softStallMillis) return diagnosis;

        boolean graphics = matches(GRAPHICS_ACTIVITY, signals.recentOutput);
        boolean dependency = matches(DEPENDENCY_ACTIVITY, signals.recentOutput);
        boolean pipelineSilent = signals.outputSilenceMillis() >= thresholds.outputSilenceMillis;

        if (pipelineSilent) {
            if (graphics) {
                return stall(
                        diagnosis,
                        "graphics_init_stall",
                        "Graphics initialization looks stuck",
                        "The game hasn't shown a window and the last activity was graphics "
                                + "driver setup that appears to have stalled. Switching to a "
                                + "more conservative graphics configuration often fixes this.",
                        "medium",
                        graphicsOnly(signals)
                );
            }
            if (dependency) {
                return stall(
                        diagnosis,
                        "dependency_wait_stall",
                        "Waiting on a missing component",
                        "The game hasn't shown a window and the last activity looked like it "
                                + "was waiting on a Windows component or library that may be "
                                + "missing. Installing the game's dependencies may be required.",
                        "low",
                        graphicsOnly(signals)
                );
            }
            return stall(
                    diagnosis,
                    "stuck_before_first_frame",
                    "The game is stuck on a black screen",
                    "The game process is alive but has produced no output and never opened a "
                            + "window. It is likely stuck during early startup. Try more "
                            + "conservative graphics and CPU-translation settings.",
                    "medium",
                    graphicsAndStability(signals)
            );
        }

        if (elapsed >= thresholds.hardStallMillis) {
            return stall(
                    diagnosis,
                    "slow_start",
                    "The game is taking a long time to start",
                    "The game is still working but has not opened a window yet. This can be "
                            + "normal for a first launch (shader compilation, dependency "
                            + "setup). If it never appears, try a more conservative graphics "
                            + "configuration.",
                    "low",
                    matches(SHADER_ACTIVITY, signals.recentOutput)
                            ? new JSONArray()
                            : graphicsOnly(signals)
            );
        }

        return diagnosis;
    }

    private static JSONObject stall(
            JSONObject diagnosis,
            String cause,
            String title,
            String message,
            String confidence,
            JSONArray suggestions
    ) throws JSONException {
        diagnosis.put("stalled", true);
        diagnosis.put("cause", cause);
        diagnosis.put("title", title);
        diagnosis.put("message", message);
        diagnosis.put("confidence", confidence);
        diagnosis.put("suggestions", suggestions);
        return diagnosis;
    }

    private static boolean matches(Pattern pattern, List<String> lines) {
        for (String line : lines) {
            if (line != null && pattern.matcher(line).find()) return true;
        }
        return false;
    }

    private static JSONArray graphicsOnly(StallSignals signals) throws JSONException {
        JSONArray suggestions = new JSONArray();
        JSONObject graphics = graphicsCompatibility(signals);
        if (graphics != null) suggestions.put(graphics);
        return suggestions;
    }

    private static JSONArray graphicsAndStability(StallSignals signals) throws JSONException {
        JSONArray suggestions = graphicsOnly(signals);
        JSONObject stability = box64Stability(signals);
        if (stability != null) suggestions.put(stability);
        return suggestions;
    }

    private static JSONArray graphicsAndResolution(StallSignals signals) throws JSONException {
        JSONArray suggestions = graphicsOnly(signals);
        JSONObject resolution = lowerResolution(signals);
        if (resolution != null) suggestions.put(resolution);
        return suggestions;
    }

    private static JSONObject graphicsCompatibility(StallSignals signals) throws JSONException {
        if (!hasConfig(signals)) return null;
        JSONObject set = new JSONObject()
                .put("graphicsDriver", GraphicsDrivers.VORTEK + "," + GraphicsDrivers.GLADIO)
                .put("graphicsDriverConfig", "")
                .put("dxwrapper", DXWrappers.WINED3D)
                .put("dxwrapperConfig", "");
        return suggestion(
                "graphics-compatibility",
                "Use compatibility graphics",
                "Switches away from Vulkan translation layers to a conservative WineD3D "
                        + "configuration, then restarts the game.",
                signals.appliedConfigSha256,
                set
        );
    }

    private static JSONObject box64Stability(StallSignals signals) throws JSONException {
        if (!hasConfig(signals)) return null;
        if (Box64Preset.STABILITY.equals(signals.appliedConfig.optString("box64Preset"))) {
            return null;
        }
        return suggestion(
                "box64-stability",
                "Use the Box64 Stability preset",
                "Disables aggressive CPU-translation optimizations that can hang early "
                        + "startup, then restarts the game.",
                signals.appliedConfigSha256,
                new JSONObject().put("box64Preset", Box64Preset.STABILITY)
        );
    }

    private static JSONObject lowerResolution(StallSignals signals) throws JSONException {
        if (!hasConfig(signals)) return null;
        if ("1280x720".equals(signals.appliedConfig.optString("screenSize"))) return null;
        return suggestion(
                "lower-resolution",
                "Lower the game resolution",
                "Reduces graphics memory and render-target pressure, then restarts the game.",
                signals.appliedConfigSha256,
                new JSONObject().put("screenSize", "1280x720")
        );
    }

    private static boolean hasConfig(StallSignals signals) {
        return signals.appliedConfig != null && !signals.appliedConfigSha256.isEmpty();
    }

    private static JSONObject suggestion(
            String id,
            String title,
            String rationale,
            String configHash,
            JSONObject set
    ) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("rationale", rationale)
                .put("baseConfigSha256", configHash)
                .put("set", set);
    }
}
