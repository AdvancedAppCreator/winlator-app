package com.winlator.api;

import com.winlator.box64.Box64Preset;
import com.winlator.container.AudioDrivers;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ManagedDiagnosticClassifier {
    static final int CLASSIFICATION_VERSION = 3;

    private static final Pattern GUEST_VIRTUAL_ADDRESS_OOM = Pattern.compile(
            "(?i)(virtual:allocate_virtual_memory.*out of memory|"
                    + "out of memory for allocation size\\s+0x[0-9a-f]+)"
    );
    private static final Pattern GUEST_ALLOCATION_SIZE = Pattern.compile(
            "(?i)allocation size\\s+(0x[0-9a-f]+)"
    );
    private static final Pattern MEDIA_PIPELINE_FAILURE = Pattern.compile(
            "(?i)(gstreamer[^\\n]*critical|gst_video_info_from_caps|"
                    + "gst_caps_is_fixed|winegstreamer[^\\n]*(failed|error))"
    );
    private static final Pattern STACK_SMASHING = Pattern.compile(
            "(?i)(stack smashing detected|stack-smash)"
    );
    private static final Pattern MISSING_NATIVE_LIBRARY = Pattern.compile(
            "(?i)(error loading needed lib\\s+[^\\s]+\\.so(?:\\.[0-9]+)*|"
                    + "dlopen[^\\n]*(failed|not found)[^\\n]*\\.so(?:\\.[0-9]+)*)"
    );
    private static final Pattern SEGFAULT = Pattern.compile(
            "(?i)(segmentation fault|sigsegv|signal\\s+11|native crash.*11|fault address)"
    );
    private static final Pattern ILLEGAL_INSTRUCTION = Pattern.compile(
            "(?i)(illegal instruction|sigill|signal\\s+4|unsupported opcode)"
    );
    private static final Pattern MEMORY_PRESSURE = Pattern.compile(
            "(?i)(out of memory|cannot allocate memory|allocation failed|killed process|low memory)"
    );
    private static final Pattern MISSING_DEPENDENCY = Pattern.compile(
            "(?i)(c0000135|import_dll.*(not found|failed)|library .* not found|module .* not found|failed to load .*\\.dll)"
    );
    private static final Pattern GRAPHICS_FAILURE = Pattern.compile(
            "(?i)(vk_error_|failed to (create|initialize).*(vulkan|device|swapchain)|"
                    + "vulkan.*(unavailable|unsupported|failed)|dxvk.*(error|failed)|"
                    + "turnip.*(error|failed)|mesa.*(error|failed)|zink.*(error|failed)|"
                    + "gladio.*(error|failed)|vortek.*(error|failed))"
    );
    private static final Pattern AUDIO_FAILURE = Pattern.compile(
            "(?i)((alsa|pulseaudio|audio).*(failed|error|unavailable)|"
                    + "failed to (open|initialize).*(audio|sound)|no audio device)"
    );
    private static final Pattern PATH_FAILURE = Pattern.compile(
            "(?i)(permission denied|no such file or directory|file not found|path .* not accessible|exec format error)"
    );
    private static final Pattern RUNTIME_LOCALE_FAILURE = Pattern.compile(
            "(?i)(runtime locale failure|unable to generate runtime locale|localedef)"
    );
    private static final Pattern WINDOWS_11_REQUIREMENT = Pattern.compile(
            "(?i)(requires?|needs?|supported\\s+(?:only\\s+)?on).{0,48}"
                    + "windows\\s*11(?:\\s+or\\s+(?:higher|later|newer))?"
    );
    private static final Pattern WINDOWS_10_REQUIREMENT = Pattern.compile(
            "(?i)(requires?|needs?|supported\\s+(?:only\\s+)?on).{0,48}"
                    + "windows\\s*10(?:\\s+or\\s+(?:higher|later|newer))?"
    );
    private static final Pattern OLD_WINDOWS_VERSION = Pattern.compile(
            "(?i)(error_old_win_version|specified program requires a newer version of windows)"
    );

    private ManagedDiagnosticClassifier() {
    }

    static JSONObject classify(
            JSONObject draft,
            List<String> output,
            Integer exitCode,
            String requestedOutcome,
            Throwable uncaughtError,
            long endedAt
    ) throws JSONException {
        return classify(
                draft,
                output,
                exitCode,
                requestedOutcome,
                uncaughtError,
                endedAt,
                deriveTerminationOrigin(exitCode, requestedOutcome)
        );
    }

    static JSONObject classify(
            JSONObject draft,
            List<String> output,
            Integer exitCode,
            String requestedOutcome,
            Throwable uncaughtError,
            long endedAt,
            String requestedTerminationOrigin
    ) throws JSONException {
        long startedAt = draft.getLong("startedAt");
        long durationMillis = Math.max(0, endedAt - startedAt);
        boolean runtimeReached = draft.optBoolean("runtimeReached", false);
        String phase = draft.optString(
                "phase",
                runtimeReached ? "runtime" : "startup"
        );
        String joined = String.join("\n", output);
        String requiredWinVersion = requiredWinVersion(joined);
        boolean genericWindowsVersionFailure = OLD_WINDOWS_VERSION.matcher(joined).find();

        String outcome = requestedOutcome;
        String category = "unknown_failure";
        String confidence = "low";
        Integer signal = signalFromExitCode(exitCode);
        String terminationOrigin = requestedTerminationOrigin != null
                ? requestedTerminationOrigin
                : deriveTerminationOrigin(exitCode, requestedOutcome);
        boolean intentionalTermination =
                "user_exit".equals(terminationOrigin) ||
                "session_replaced".equals(terminationOrigin) ||
                "winlator_teardown".equals(terminationOrigin);
        boolean preserveCleanTermination =
                "user_exit".equals(terminationOrigin) ||
                "session_replaced".equals(terminationOrigin) ||
                (exitCode != null && exitCode == 0);
        boolean mediaStackSmash = STACK_SMASHING.matcher(joined).find() &&
                joined.toLowerCase(Locale.ENGLISH).contains("gstreamer");

        if (uncaughtError != null) {
            outcome = "crashed";
            category = "android_exception";
            confidence = "high";
            phase = runtimeReached ? "runtime" : phase;
        }
        else if (!draft.optString("failureCategory", "").isEmpty()) {
            outcome = "launch_failed";
            category = draft.getString("failureCategory");
            confidence = "high";
        }
        else if (exitCode != null && exitCode == -1) {
            outcome = "launch_failed";
            category = "process_start_failed";
            confidence = "high";
            phase = "process_start";
        }
        else if (!preserveCleanTermination &&
                GUEST_VIRTUAL_ADDRESS_OOM.matcher(joined).find()) {
            outcome = "crashed";
            category = "guest_virtual_address_oom";
            confidence = "high";
        }
        else if (!preserveCleanTermination &&
                (MEDIA_PIPELINE_FAILURE.matcher(joined).find() || mediaStackSmash)) {
            outcome = "crashed";
            category = "media_pipeline_failure";
            confidence = "high";
        }
        else if (!preserveCleanTermination &&
                MISSING_NATIVE_LIBRARY.matcher(joined).find()) {
            outcome = nonZeroOutcome(exitCode);
            category = "missing_native_library";
            confidence = "high";
        }
        else if (!preserveCleanTermination &&
                (requiredWinVersion != null || genericWindowsVersionFailure)) {
            outcome = nonZeroOutcome(exitCode);
            category = "windows_version_incompatibility";
            confidence = requiredWinVersion != null ? "high" : "medium";
        }
        else if (!preserveCleanTermination &&
                ((exitCode != null && exitCode == 139) || SEGFAULT.matcher(joined).find())) {
            outcome = "crashed";
            category = "box64_segfault";
            confidence = "high";
            signal = 11;
        }
        else if (!preserveCleanTermination &&
                ((exitCode != null && exitCode == 132) ||
                        ILLEGAL_INSTRUCTION.matcher(joined).find())) {
            outcome = "crashed";
            category = "box64_illegal_instruction";
            confidence = "high";
            signal = 4;
        }
        else if (!preserveCleanTermination &&
                ((!intentionalTermination && exitCode != null && exitCode == 137) ||
                        MEMORY_PRESSURE.matcher(joined).find())) {
            outcome = "killed";
            category = "possible_memory_pressure";
            confidence = exitCode != null && exitCode == 137 ? "medium" : "low";
            signal = exitCode != null && exitCode == 137 ? 9 : signal;
        }
        else if (!preserveCleanTermination && MISSING_DEPENDENCY.matcher(joined).find()) {
            outcome = nonZeroOutcome(exitCode);
            category = "missing_windows_dependency";
            confidence = "high";
        }
        else if (!preserveCleanTermination && GRAPHICS_FAILURE.matcher(joined).find()) {
            outcome = nonZeroOutcome(exitCode);
            category = "graphics_initialization";
            confidence = "high";
        }
        else if (!preserveCleanTermination && AUDIO_FAILURE.matcher(joined).find()) {
            outcome = nonZeroOutcome(exitCode);
            category = "audio_initialization";
            confidence = "medium";
        }
        else if (!preserveCleanTermination && PATH_FAILURE.matcher(joined).find()) {
            outcome = "launch_failed";
            category = "path_or_permission";
            confidence = "high";
        }
        else if ("user_exit".equals(terminationOrigin)) {
            outcome = "user_exit";
            category = "user_exit";
            confidence = "high";
            phase = "shutdown";
        }
        else if ("session_replaced".equals(terminationOrigin)) {
            outcome = "killed";
            category = "session_replaced";
            confidence = "high";
            phase = "shutdown";
        }
        else if ("winlator_teardown".equals(terminationOrigin)) {
            outcome = "killed";
            category = "winlator_teardown";
            confidence = "high";
            phase = "shutdown";
        }
        else if (exitCode != null && exitCode == 0) {
            outcome = "completed";
            category = runtimeReached ? "clean_exit" : "early_clean_exit";
            confidence = "high";
            phase = "shutdown";
        }
        else if (exitCode != null) {
            outcome = runtimeReached ? "crashed" : "launch_failed";
            category = runtimeReached ? "abnormal_process_exit" : "early_process_exit";
            confidence = "medium";
        }
        else if ("unknown".equals(requestedOutcome)) {
            category = "app_or_session_terminated";
            confidence = "low";
        }

        String configHealth = "unknown";
        if ("crashed".equals(outcome) ||
                ("killed".equals(outcome) && !intentionalTermination) ||
                "launch_failed".equals(outcome)) {
            configHealth = "bad";
        }
        else if (durationMillis >= 60000 &&
                ("completed".equals(outcome) || "user_exit".equals(outcome)) &&
                runtimeReached) {
            configHealth = "good";
        }

        JSONObject report = new JSONObject(draft.toString());
        report.put("classificationVersion", CLASSIFICATION_VERSION);
        report.put("endedAt", endedAt);
        report.put("durationMillis", durationMillis);
        report.put("outcome", outcome);
        report.put("phase", phase);
        report.put("category", category);
        report.put("confidence", confidence);
        report.put("terminationOrigin", terminationOrigin);
        if (exitCode != null) report.put("exitCode", exitCode);
        if (signal != null) report.put("signal", signal);
        report.put("runtimeReached", runtimeReached);
        report.put("configHealth", configHealth);
        if (requiredWinVersion != null) {
            report.put("requiredWinVersion", requiredWinVersion);
        }
        if ("guest_virtual_address_oom".equals(category)) {
            Matcher allocation = GUEST_ALLOCATION_SIZE.matcher(joined);
            if (allocation.find()) {
                String hexadecimal = allocation.group(1);
                JSONObject details = report.optJSONObject("failureDetails");
                if (details == null) details = new JSONObject();
                details.put("allocationSizeHex", hexadecimal);
                try {
                    details.put(
                            "allocationSizeBytes",
                            Long.parseLong(hexadecimal.substring(2), 16)
                    );
                }
                catch (NumberFormatException ignored) {
                }
                report.put("failureDetails", details);
            }
        }
        report.put("evidence", evidenceFor(category, output, uncaughtError));
        report.put(
                "suggestions",
                suggestionsFor(
                        category,
                        report.optJSONObject("appliedConfig"),
                        report.optString("appliedConfigSha256", ""),
                        requiredWinVersion
                )
        );
        return ManagedDiagnosticStore.boundReport(report);
    }

    static JSONObject interrupted(JSONObject draft, long endedAt) throws JSONException {
        return classify(
                draft,
                new ArrayList<>(),
                null,
                "unknown",
                null,
                endedAt,
                "external_signal"
        );
    }

    private static JSONArray evidenceFor(
            String category,
            List<String> output,
            Throwable uncaughtError
    ) throws JSONException {
        JSONArray evidence = new JSONArray();
        if (uncaughtError != null) {
            evidence.put(new JSONObject()
                    .put("source", "android")
                    .put("message", truncate(
                            uncaughtError.getClass().getName() + ": " + uncaughtError.getMessage()
                    )));
            if (!output.isEmpty() && evidence.length() < 8) {
                evidence.put(new JSONObject()
                        .put("source", "android")
                        .put("message", truncate(output.get(output.size() - 1))));
            }
        }

        Pattern pattern = patternFor(category);
        if (pattern != null) {
            for (int index = output.size() - 1; index >= 0 && evidence.length() < 8; index--) {
                String line = output.get(index);
                if (pattern.matcher(line).find()) {
                    evidence.put(new JSONObject()
                            .put("source", "guest")
                            .put("message", truncate(line)));
                }
            }
        }
        if (evidence.length() == 0) {
            int start = Math.max(0, output.size() - 3);
            for (int index = start; index < output.size() && evidence.length() < 8; index++) {
                evidence.put(new JSONObject()
                        .put("source", "guest")
                        .put("message", truncate(output.get(index))));
            }
        }
        return evidence;
    }

    private static JSONArray suggestionsFor(
            String category,
            JSONObject config,
            String configHash,
            String requiredWinVersion
    ) throws JSONException {
        JSONArray suggestions = new JSONArray();
        if (config == null || configHash.isEmpty()) return suggestions;

        if ("box64_segfault".equals(category) ||
                "box64_illegal_instruction".equals(category) ||
                "early_process_exit".equals(category)) {
            if (!Box64Preset.STABILITY.equals(config.optString("box64Preset"))) {
                suggestions.put(suggestion(
                        "box64-stability",
                        "Use the Box64 Stability preset",
                        "Disables aggressive dynamic-recompiler optimizations that can cause guest crashes.",
                        configHash,
                        new JSONObject().put("box64Preset", Box64Preset.STABILITY)
                ));
            }
        }
        if ("graphics_initialization".equals(category)) {
            JSONObject set = new JSONObject()
                    .put("graphicsDriver", GraphicsDrivers.VORTEK + "," + GraphicsDrivers.GLADIO)
                    .put("graphicsDriverConfig", "")
                    .put("dxwrapper", DXWrappers.WINED3D)
                    .put("dxwrapperConfig", "");
            suggestions.put(suggestion(
                    "graphics-compatibility",
                    "Use compatibility graphics",
                    "Switches away from Vulkan translation layers to a conservative WineD3D configuration.",
                    configHash,
                    set
            ));
        }
        if ("audio_initialization".equals(category)) {
            String current = config.optString("audioDriver", AudioDrivers.ALSA);
            String alternative = AudioDrivers.ALSA.equals(current)
                    ? AudioDrivers.PULSEAUDIO
                    : AudioDrivers.ALSA;
            suggestions.put(suggestion(
                    "alternate-audio",
                    "Try the alternate audio driver",
                    "Changes only the Windows audio backend.",
                    configHash,
                    new JSONObject()
                            .put("audioDriver", alternative)
                            .put("audioDriverConfig", "")
            ));
        }
        if ("possible_memory_pressure".equals(category) &&
                !"1280x720".equals(config.optString("screenSize"))) {
            suggestions.put(suggestion(
                    "lower-resolution",
                    "Lower the game resolution",
                    "Reduces graphics memory and render-target pressure without changing Android RAM.",
                    configHash,
                    new JSONObject().put("screenSize", "1280x720")
            ));
        }
        if ("windows_version_incompatibility".equals(category)
                && requiredWinVersion != null
                && !requiredWinVersion.equals(config.optString("winVersion"))) {
            String label = "win11".equals(requiredWinVersion)
                    ? "Windows 11"
                    : "Windows 10";
            suggestions.put(suggestion(
                    "windows-" + requiredWinVersion.substring(3) + "-version",
                    "Report " + label,
                    "The game explicitly reported that " + label + " or later is required.",
                    configHash,
                    new JSONObject().put("winVersion", requiredWinVersion)
            ));
        }
        return suggestions;
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

    private static Pattern patternFor(String category) {
        switch (category) {
            case "box64_segfault":
                return SEGFAULT;
            case "box64_illegal_instruction":
                return ILLEGAL_INSTRUCTION;
            case "possible_memory_pressure":
                return MEMORY_PRESSURE;
            case "guest_virtual_address_oom":
                return GUEST_VIRTUAL_ADDRESS_OOM;
            case "media_pipeline_failure":
                return Pattern.compile(
                        "(?i)(gstreamer|winegstreamer|gst_video_info_from_caps|"
                                + "gst_caps_is_fixed|stack smashing detected|"
                                + "error loading needed lib)"
                );
            case "missing_native_library":
                return MISSING_NATIVE_LIBRARY;
            case "missing_windows_dependency":
                return MISSING_DEPENDENCY;
            case "graphics_initialization":
                return GRAPHICS_FAILURE;
            case "audio_initialization":
                return AUDIO_FAILURE;
            case "path_or_permission":
                return PATH_FAILURE;
            case "runtime_locale_generation":
                return RUNTIME_LOCALE_FAILURE;
            case "windows_version_incompatibility":
                return Pattern.compile(
                        WINDOWS_11_REQUIREMENT.pattern() + "|"
                                + WINDOWS_10_REQUIREMENT.pattern() + "|"
                                + OLD_WINDOWS_VERSION.pattern()
                );
            default:
                return null;
        }
    }

    private static String requiredWinVersion(String output) {
        if (WINDOWS_11_REQUIREMENT.matcher(output).find()) return "win11";
        if (WINDOWS_10_REQUIREMENT.matcher(output).find()) return "win10";
        return null;
    }

    private static String nonZeroOutcome(Integer exitCode) {
        return exitCode != null && exitCode == 0 ? "unknown" : "launch_failed";
    }

    private static Integer signalFromExitCode(Integer exitCode) {
        if (exitCode == null || exitCode < 128 || exitCode > 192) return null;
        return exitCode - 128;
    }

    private static String deriveTerminationOrigin(Integer exitCode, String requestedOutcome) {
        if ("user_exit".equals(requestedOutcome)) return "user_exit";
        Integer signal = signalFromExitCode(exitCode);
        return signal != null ? "external_signal" : "natural";
    }

    private static String truncate(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 509) + "...";
    }
}
