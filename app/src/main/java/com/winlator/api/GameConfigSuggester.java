package com.winlator.api;

import android.content.Context;

import com.winlator.box64.Box64Preset;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.LiveMakerCompat;
import com.winlator.core.WineUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inspects a managed game's on-disk files to fingerprint its engine/runtime and derive a
 * best-effort suggested Winlator configuration. Read-only and side-effect free: the AGM
 * integration decides whether to apply the suggestion.
 *
 * <p>The returned payload is intentionally conservative. Only fields we are confident about are
 * populated in {@code suggestedConfig}; the {@code rationale} and {@code evidence} arrays explain
 * every decision so the suggestion can be presented for one-tap review.
 */
final class GameConfigSuggester {
    private static final int MAX_ENTRIES = 4096;

    private GameConfigSuggester() {
    }

    static JSONObject suggest(Context context, ManagedGame game) throws JSONException, IOException {
        JSONObject result = new JSONObject();
        result.put("gameId", game.id);

        File executable = resolveExecutable(context, game);
        File baseDir = resolveBaseDir(game, executable);
        String exeName = game.executablePath != null
                ? new File(game.executablePath).getName().toLowerCase(Locale.ENGLISH)
                : executable != null
                        ? executable.getName().toLowerCase(Locale.ENGLISH)
                        : "";

        result.put("architecture", detectArchitecture(game));

        if (baseDir == null || !baseDir.isDirectory()) {
            result.put("engine", "UNKNOWN");
            result.put("engineLabel", "Unknown");
            result.put("confidence", "low");
            result.put("evidence", new JSONArray());
            result.put("suggestedConfig", new JSONObject());
            result.put("suggestedSettings", new JSONObject());
            result.put("rationale", new JSONArray().put(
                    "The game directory could not be read, so no engine fingerprint was possible."
            ));
            result.put("runnerRecommendation", buildRunnerRecommendation("UNKNOWN"));
            augmentWinVersionSuggestion(context, game, result);
            return result;
        }

        Set<String> names = listNames(baseDir);
        List<String> evidence = new ArrayList<>();
        JSONObject config = new JSONObject();
        JSONObject settings = new JSONObject();
        List<String> rationale = new ArrayList<>();

        String engine = detectEngine(names, exeName, executable, evidence);
        applyEngineSuggestions(engine, config, rationale);
        augmentWithDllHints(names, config, rationale, evidence);
        augmentWithGraphicsApi(context, game, engine, config, rationale, evidence);
        augmentWithLocale(context, baseDir, engine, settings, rationale, evidence);

        result.put("engine", engine);
        result.put("engineLabel", engineLabel(engine));
        result.put("confidence", "UNKNOWN".equals(engine) && config.length() == 0
                && settings.length() == 0 ? "low" : ("UNKNOWN".equals(engine) ? "medium" : "high"));
        result.put("evidence", new JSONArray(evidence));
        result.put("suggestedConfig", config);
        result.put("suggestedSettings", settings);
        if (rationale.isEmpty()) {
            rationale.add("No engine-specific markers were found; Winlator defaults are recommended.");
        }
        result.put("rationale", new JSONArray(rationale));
        result.put("runnerRecommendation", buildRunnerRecommendation(engine));
        augmentWinVersionSuggestion(context, game, result);
        return result;
    }

    private static void augmentWinVersionSuggestion(
            Context context,
            ManagedGame game,
            JSONObject result
    ) throws JSONException, IOException {
        Container container = game.containerId > 0
                ? new ContainerManager(context).getContainerById(game.containerId)
                : null;
        JSONObject currentConfig = container != null
                ? GameApiJson.managedConfig(game, container)
                : game.configJson != null
                        ? new JSONObject(game.configJson)
                        : null;
        if (currentConfig == null) return;

        result.put("baseConfigSha256", GameConfigSchema.hash(currentConfig));
        JSONObject suggestedConfig = result.getJSONObject("suggestedConfig");
        JSONArray rationale = result.getJSONArray("rationale");
        JSONArray evidence = result.getJSONArray("evidence");

        String diagnosticTarget = latestDiagnosticWinVersion(context, game.id);
        ManagedWinVersion.State state = container != null
                ? ManagedWinVersion.resolve(game, container)
                : null;
        augmentWinVersionSuggestion(
                result,
                currentConfig,
                state,
                diagnosticTarget
        );
    }

    static void augmentWinVersionSuggestion(
            JSONObject result,
            JSONObject currentConfig,
            ManagedWinVersion.State state,
            String diagnosticTarget
    ) throws JSONException {
        JSONObject suggestedConfig = result.getJSONObject("suggestedConfig");
        JSONArray rationale = result.getJSONArray("rationale");
        JSONArray evidence = result.getJSONArray("evidence");

        if (diagnosticTarget != null
                && !diagnosticTarget.equals(currentConfig.optString("winVersion"))) {
            suggestedConfig.put("winVersion", diagnosticTarget);
            putWinVersionMetadata(
                    result,
                    "diagnostic_requirement",
                    false,
                    currentConfig.optString("winVersion", null),
                    diagnosticTarget
            );
            rationale.put(
                    "A prior launch explicitly reported that "
                            + winVersionLabel(diagnosticTarget)
                            + " or later is required."
            );
            evidence.put("diagnostic:explicit-" + diagnosticTarget + "-requirement");
            return;
        }

        if (state == null
                || !ManagedGame.WIN_VERSION_SOURCE_REGISTRY_LEGACY.equals(state.source)) {
            return;
        }

        suggestedConfig.put("winVersion", "win10");
        boolean automaticApplySafe = "win10".equals(state.effectiveValue);
        putWinVersionMetadata(
                result,
                "legacy_default_migration",
                automaticApplySafe,
                state.effectiveValue,
                "win10"
        );
        rationale.put(
                state.effectiveValue != null
                        ? "This game has no explicit Windows version and the container currently "
                                + "reports " + winVersionLabel(state.effectiveValue) + "."
                        : "This game has no explicit Windows version and the container registry "
                                + "does not match a supported Winlator version."
        );
        evidence.put(
                state.effectiveValue != null
                        ? "container-registry:" + state.effectiveValue
                        : "container-registry:unrecognized"
        );
    }

    private static String latestDiagnosticWinVersion(Context context, String gameId)
            throws JSONException, IOException {
        JSONArray reports = new ManagedDiagnosticStore(context).listForGame(gameId, 20);
        for (int index = 0; index < reports.length(); index++) {
            JSONObject report = reports.getJSONObject(index);
            if ("windows_version_incompatibility".equals(report.optString("category"))
                    && "high".equals(report.optString("confidence"))
                    && report.has("requiredWinVersion")) {
                return report.getString("requiredWinVersion");
            }
        }
        return null;
    }

    private static void putWinVersionMetadata(
            JSONObject result,
            String source,
            boolean automaticApplySafe,
            String currentValue,
            String targetValue
    ) throws JSONException {
        JSONObject suggestionMetadata = result.optJSONObject("suggestionMetadata");
        if (suggestionMetadata == null) suggestionMetadata = new JSONObject();
        JSONObject metadata = new JSONObject()
                .put("source", source)
                .put("confidence", "high")
                .put("automaticApplySafe", automaticApplySafe)
                .put("targetValue", targetValue);
        if (currentValue != null && !currentValue.isEmpty()) {
            metadata.put("currentEffectiveValue", currentValue);
        }
        suggestionMetadata.put("winVersion", metadata);
        result.put("suggestionMetadata", suggestionMetadata);
    }

    private static String winVersionLabel(String version) {
        switch (version) {
            case "win11":
                return "Windows 11";
            case "win10":
                return "Windows 10";
            default:
                return version;
        }
    }

    private static File resolveExecutable(Context context, ManagedGame game) {
        if (game.executablePath != null && !game.executablePath.isEmpty()) {
            return new File(game.executablePath);
        }
        if (game.executableDosPath == null || game.executableDosPath.isEmpty()
                || game.containerId <= 0) {
            return null;
        }
        Container container = new ContainerManager(context).getContainerById(game.containerId);
        if (container == null) return null;
        String path = WineUtils.dosToUnixPath(game.executableDosPath, container);
        return path != null ? new File(path) : null;
    }

    private static File resolveBaseDir(ManagedGame game, File executable) {
        if (game.gamePath != null && !game.gamePath.isEmpty()) {
            File dir = new File(game.gamePath);
            if (dir.isDirectory()) return dir;
            if (dir.isFile()) return dir.getParentFile();
        }
        return executable != null ? executable.getParentFile() : null;
    }

    private static Set<String> listNames(File dir) {
        Set<String> names = new LinkedHashSet<>();
        File[] entries = dir.listFiles();
        if (entries == null) return names;
        int count = 0;
        for (File entry : entries) {
            if (count++ >= MAX_ENTRIES) break;
            names.add(entry.getName().toLowerCase(Locale.ENGLISH));
        }
        return names;
    }

    private static String detectEngine(
            Set<String> names,
            String exeName,
            File executable,
            List<String> evidence
    ) {
        // Ren'Py
        if (names.contains("renpy") || anyEndsWith(names, ".rpa")
                || (names.contains("game") && names.contains("lib")
                        && (names.contains("renpy.py") || hasAny(names, "renpy")))) {
            record(evidence, names, "renpy", ".rpa");
            return "RENPY";
        }
        // RPG Maker MV / MZ (NW.js/Chromium)
        if (names.contains("nw.dll")
                || (names.contains("package.json")
                        && (names.contains("www") || names.contains("js")))
                || names.contains("credits.html")) {
            record(evidence, names, "nw.dll", "package.json", "www", "credits.html");
            return "RPG_MAKER_MV_MZ";
        }
        // RPG Maker VX / XP / VX Ace (RGSS)
        if (anyEndsWith(names, ".rgssad", ".rgss2a", ".rgss3a")
                || hasAny(names, "rgss")) {
            record(evidence, names, ".rgssad", ".rgss2a", ".rgss3a");
            return "RPG_MAKER_RGSS";
        }
        // Wolf RPG Editor
        if (anyEndsWith(names, ".wolf") || names.contains("gurugurusmf.dll")
                || names.contains("gurugurusmf4.dll")) {
            record(evidence, names, ".wolf", "gurugurusmf4.dll");
            return "WOLF_RPG";
        }
        // LiveMaker / LiveNovel executables carry an appended VF archive ending in an
        // offset + "lv" trailer. Some unpacked projects expose compiled .lsb/.lns files.
        if (LiveMakerCompat.isExecutable(executable)
                || anyEndsWith(names, ".lsb", ".lns")
                || (names.contains("game.ini") && anyEndsWith(names, ".gal"))) {
            if (LiveMakerCompat.isExecutable(executable)) {
                evidence.add("exe:livemaker-vf-archive");
            }
            record(evidence, names, ".lsb", ".lns", ".gal", "game.ini");
            return "LIVEMAKER";
        }
        // KiriKiri / KAG
        if (anyEndsWith(names, ".xp3")) {
            record(evidence, names, ".xp3");
            return "KIRIKIRI";
        }
        // Unity
        if (names.contains("unityplayer.dll") || anyEndsWith(names, "_data")
                || names.contains("unitycrashhandler64.exe")
                || names.contains("unitycrashhandler.exe")) {
            record(evidence, names, "unityplayer.dll", "unitycrashhandler64.exe");
            return "UNITY";
        }
        // Unreal Engine
        if (exeName.endsWith("-win64-shipping.exe") || exeName.endsWith("-win32-shipping.exe")
                || names.contains("engine") || names.contains("manifest_nonufsfiles_win64.txt")) {
            evidence.add("Unreal shipping executable / Engine layout");
            return "UNREAL";
        }
        // Godot 4
        if (anyEndsWith(names, ".pck") || names.contains("project.godot")) {
            record(evidence, names, ".pck", "project.godot");
            return "GODOT";
        }
        // Electron (non-NW.js)
        if (anyEndsWith(names, ".asar") || names.contains("resources")
                && (names.contains("chrome_100_percent.pak") || names.contains("icudtl.dat"))) {
            record(evidence, names, ".asar", "resources", "icudtl.dat");
            return "ELECTRON";
        }
        return "UNKNOWN";
    }

    private static void applyEngineSuggestions(
            String engine,
            JSONObject config,
            List<String> rationale
    ) throws JSONException {
        switch (engine) {
            case "RENPY":
                config.put("box64Preset", Box64Preset.PERFORMANCE);
                config.put("dxwrapper", DXWrappers.DXVK);
                config.put("audioDriver", AudioDrivers.ALSA);
                rationale.add("Ren'Py (Python + pygame_sdl2) runs well under Box64; the "
                        + "PERFORMANCE preset and DXVK give smooth OpenGL/Vulkan rendering.");
                break;
            case "RPG_MAKER_MV_MZ":
                config.put("launchArguments", "--disable-gpu --in-process-gpu");
                config.put("box64Preset", Box64Preset.INTERMEDIATE);
                rationale.add("RPG Maker MV/MZ ships an NW.js/Chromium runtime that frequently "
                        + "shows a black screen under Wine; '--disable-gpu --in-process-gpu' "
                        + "forces software compositing and reliably fixes it.");
                break;
            case "RPG_MAKER_RGSS":
                config.put("dxwrapper", DXWrappers.WINED3D);
                config.put("box64Preset", Box64Preset.STABILITY);
                rationale.add("RPG Maker VX/XP/VX Ace use the legacy RGSS Direct3D8 renderer, "
                        + "which is more compatible through WineD3D than DXVK; the STABILITY "
                        + "preset avoids Box64 dynarec edge cases in these 32-bit games.");
                break;
            case "WOLF_RPG":
                config.put("dxwrapper", DXWrappers.WINED3D);
                config.put("box64Preset", Box64Preset.STABILITY);
                rationale.add("Wolf RPG Editor games use DirectDraw/Direct3D and are 32-bit; "
                        + "WineD3D plus the STABILITY preset is the most compatible combination.");
                break;
            case "KIRIKIRI":
                config.put("dxwrapper", DXWrappers.WINED3D);
                config.put("box64Preset", Box64Preset.STABILITY);
                rationale.add("KiriKiri/KAG engine titles rely on older Direct3D and DirectShow "
                        + "paths that are most compatible via WineD3D with the STABILITY preset.");
                break;
            case "LIVEMAKER":
                config.put("dxwrapper", DXWrappers.WINED3D);
                config.put("dxwrapperConfig", "ddrawWrapper=cnc-ddraw");
                config.put("box64Preset", Box64Preset.STABILITY);
                rationale.add("LiveMaker is a 32-bit Delphi engine that mixes GDI text with "
                        + "legacy DirectDraw surfaces. CNC DDraw replaces that 2D path directly, "
                        + "while the STABILITY preset avoids aggressive translation settings.");
                break;
            case "UNITY":
                config.put("dxwrapper", DXWrappers.DXVK);
                config.put("box64Preset", Box64Preset.PERFORMANCE);
                rationale.add("Unity games target Direct3D 11; DXVK on Vulkan gives the best "
                        + "performance and compatibility, paired with the PERFORMANCE preset.");
                break;
            case "UNREAL":
                config.put("dxwrapper", DXWrappers.DXVK);
                config.put("box64Preset", Box64Preset.PERFORMANCE);
                rationale.add("Unreal Engine games use Direct3D 11/12; DXVK/VKD3D on Vulkan is "
                        + "recommended, with the PERFORMANCE preset for demanding scenes.");
                break;
            case "GODOT":
                config.put("launchArguments", "--rendering-method mobile");
                config.put("dxwrapper", DXWrappers.DXVK);
                config.put("box64Preset", Box64Preset.PERFORMANCE);
                rationale.add("Godot 4's default Forward+ renderer is often slow or visually "
                        + "glitchy on mobile Vulkan (Adreno/Turnip); '--rendering-method mobile' "
                        + "selects the lighter mobile backend that runs far better.");
                break;
            case "ELECTRON":
                config.put("launchArguments", "--disable-gpu --in-process-gpu");
                config.put("box64Preset", Box64Preset.INTERMEDIATE);
                rationale.add("Electron/Chromium apps commonly render a black screen under Wine "
                        + "unless GPU acceleration is disabled with "
                        + "'--disable-gpu --in-process-gpu'.");
                break;
            default:
                break;
        }
    }

    private static void augmentWithGraphicsApi(
            Context context,
            ManagedGame game,
            String engine,
            JSONObject config,
            List<String> rationale,
            List<String> evidence
    ) throws JSONException {
        if (config.has("graphicsDriver")) return;
        String exePath = game.executablePath;
        if (exePath == null || exePath.isEmpty()) return;
        Set<String> imports = readImportedDlls(new File(exePath));
        if (imports.isEmpty()) return;

        boolean usesGL = imports.contains("opengl32.dll");
        boolean usesD3D = imports.contains("d3d12.dll") || imports.contains("d3d11.dll")
                || imports.contains("dxgi.dll") || imports.contains("d3d10.dll")
                || imports.contains("d3d10core.dll") || imports.contains("d3d9.dll")
                || imports.contains("d3d8.dll") || imports.contains("ddraw.dll");
        boolean usesVulkan = imports.contains("vulkan-1.dll");

        // A native OpenGL game (imports opengl32 and no Direct3D/Vulkan) renders upside-down on
        // the default Gladio OpenGL renderer for some titles. Zink (Mesa GL-over-Vulkan) presents
        // them with the correct orientation, verified against a native-GL game that Gladio flipped.
        if (usesGL && !usesD3D && !usesVulkan) {
            config.put("graphicsDriver", defaultVulkanDriver(context) + "," + GraphicsDrivers.ZINK);
            evidence.add("imports:opengl32.dll");
            rationale.add("The executable links OpenGL directly (opengl32) with no Direct3D, so it "
                    + "is a native OpenGL game. The default Gladio OpenGL renderer draws some of "
                    + "these upside-down; switching the OpenGL driver to Zink renders them "
                    + "correctly.");
        }
    }

    private static String defaultVulkanDriver(Context context) {
        try {
            String pair = GraphicsDrivers.getDefaultDriver(context);
            if (pair != null && pair.contains(",")) return pair.split(",")[0];
        }
        catch (Throwable ignored) {
        }
        return GraphicsDrivers.TURNIP;
    }

    private static void augmentWithLocale(
            Context context,
            File baseDir,
            String engine,
            JSONObject settings,
            List<String> rationale,
            List<String> evidence
    ) throws JSONException {
        String locale = detectCjkLocale(baseDir, engine, evidence);
        if (locale == null) return;
        settings.put("localization", new JSONObject().put("runtimeLocale", locale));
        String label;
        switch (locale) {
            case "ko_KR.UTF-8": label = "Korean"; break;
            case "zh_CN.UTF-8": label = "Simplified Chinese"; break;
            case "zh_TW.UTF-8": label = "Traditional Chinese"; break;
            default: label = "Japanese"; break;
        }
        rationale.add("This looks like a " + label + " game. Setting the Wine launch locale to "
                + locale + " gives it the matching system code page, which many CJK games require "
                + "to start at all, and ensures paths and text decode correctly (the bundled Noto "
                + "CJK font then renders them).");
    }

    /**
     * Detects the most likely CJK locale from the scripts used in the game's file/folder names,
     * falling back to Japanese for the Japanese-centric engines. Returns a runtimeLocale enum
     * value or {@code null} when nothing CJK is found.
     */
    private static String detectCjkLocale(File baseDir, String engine, List<String> evidence) {
        if ("LIVEMAKER".equals(engine)) {
            evidence.add("engine:japanese-cp932");
            return LiveMakerCompat.RUNTIME_LOCALE;
        }
        boolean kana = false;
        boolean hangul = false;
        boolean han = false;
        List<String> scan = new ArrayList<>();
        if (baseDir.getName() != null) scan.add(baseDir.getName());
        File[] entries = baseDir.listFiles();
        if (entries != null) {
            int count = 0;
            for (File entry : entries) {
                if (count++ >= MAX_ENTRIES) break;
                scan.add(entry.getName());
            }
        }
        for (String name : scan) {
            for (int i = 0; i < name.length(); ) {
                int cp = name.codePointAt(i);
                i += Character.charCount(cp);
                if ((cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0xFF66 && cp <= 0xFF9D)) kana = true;
                else if ((cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0x1100 && cp <= 0x11FF)
                        || (cp >= 0x3130 && cp <= 0x318F)) hangul = true;
                else if ((cp >= 0x3400 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF)) han = true;
            }
        }
        boolean jpEngine = "KIRIKIRI".equals(engine) || "LIVEMAKER".equals(engine)
                || "WOLF_RPG".equals(engine)
                || "RPG_MAKER_RGSS".equals(engine);
        if (kana || hangul || han) evidence.add("filenames:CJK");
        if (kana) return "ja_JP.UTF-8";
        if (hangul) return "ko_KR.UTF-8";
        if (han) return jpEngine ? "ja_JP.UTF-8" : "zh_CN.UTF-8";
        if (jpEngine) {
            evidence.add("engine:japanese");
            return "ja_JP.UTF-8";
        }
        return null;
    }

    /**
     * Reads the imported DLL names from a PE executable's import directory. Best-effort and
     * bounded; returns an empty set on any malformed or unreadable header.
     */
    private static Set<String> readImportedDlls(File exe) {
        Set<String> dlls = new LinkedHashSet<>();
        if (exe == null || !exe.isFile()) return dlls;
        try (RandomAccessFile raf = new RandomAccessFile(exe, "r")) {
            long fileLen = exe.length();
            if (readU16(raf, 0) != 0x5A4D) return dlls;
            int peOff = readU32(raf, 0x3C);
            if (peOff <= 0 || peOff + 24 > fileLen) return dlls;
            if (readU32(raf, peOff) != 0x00004550) return dlls;
            int coff = peOff + 4;
            int numSections = readU16(raf, coff + 2);
            int sizeOpt = readU16(raf, coff + 16);
            if (numSections <= 0 || numSections > 96) return dlls;
            int optStart = coff + 20;
            int magic = readU16(raf, optStart);
            int dataDirOff;
            if (magic == 0x10b) dataDirOff = optStart + 96;
            else if (magic == 0x20b) dataDirOff = optStart + 112;
            else return dlls;
            int importRva = readU32(raf, dataDirOff + 8);
            if (importRva == 0) return dlls;

            int sectionsOff = optStart + sizeOpt;
            int[] vaddr = new int[numSections];
            int[] vsize = new int[numSections];
            int[] praw = new int[numSections];
            int[] rsize = new int[numSections];
            for (int i = 0; i < numSections; i++) {
                int so = sectionsOff + i * 40;
                if (so + 40 > fileLen) return dlls;
                vsize[i] = readU32(raf, so + 8);
                vaddr[i] = readU32(raf, so + 12);
                rsize[i] = readU32(raf, so + 16);
                praw[i] = readU32(raf, so + 20);
            }

            int importOff = rvaToOffset(importRva, vaddr, vsize, praw, rsize, numSections);
            if (importOff < 0) return dlls;
            for (int i = 0; i < 2048; i++) {
                int desc = importOff + i * 20;
                if (desc + 20 > fileLen) break;
                int origThunk = readU32(raf, desc);
                int nameRva = readU32(raf, desc + 12);
                int firstThunk = readU32(raf, desc + 16);
                if (origThunk == 0 && nameRva == 0 && firstThunk == 0) break;
                if (nameRva == 0) continue;
                int nameOff = rvaToOffset(nameRva, vaddr, vsize, praw, rsize, numSections);
                if (nameOff < 0) continue;
                String name = readAsciiZ(raf, nameOff, 64);
                if (!name.isEmpty()) dlls.add(name.toLowerCase(Locale.ENGLISH));
            }
        }
        catch (Exception ignored) {
        }
        return dlls;
    }

    private static int rvaToOffset(
            int rva, int[] vaddr, int[] vsize, int[] praw, int[] rsize, int count) {
        for (int i = 0; i < count; i++) {
            int size = Math.max(vsize[i], rsize[i]);
            if (rva >= vaddr[i] && rva < vaddr[i] + size) {
                return praw[i] + (rva - vaddr[i]);
            }
        }
        return -1;
    }

    private static int readU16(RandomAccessFile raf, long offset) throws java.io.IOException {
        raf.seek(offset);
        int b0 = raf.read();
        int b1 = raf.read();
        if ((b0 | b1) < 0) return -1;
        return b0 | (b1 << 8);
    }

    private static int readU32(RandomAccessFile raf, long offset) throws java.io.IOException {
        raf.seek(offset);
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) return -1;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static String readAsciiZ(RandomAccessFile raf, long offset, int max)
            throws java.io.IOException {
        raf.seek(offset);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            int b = raf.read();
            if (b <= 0) break;
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static void augmentWithDllHints(
            Set<String> names,
            JSONObject config,
            List<String> rationale,
            List<String> evidence
    ) throws JSONException {
        if (names.contains("d3d12.dll") || names.contains("d3d12core.dll")) {
            evidence.add("d3d12.dll");
            if (!config.has("dxwrapper")) config.put("dxwrapper", DXWrappers.DXVK);
            rationale.add("The game bundles Direct3D 12; run it over Vulkan (DXVK/VKD3D) for "
                    + "hardware acceleration.");
        }
        if (names.contains("mscoree.dll") || anyEndsWith(names, ".runtimeconfig.json", ".deps.json")) {
            evidence.add(".NET runtime markers");
            rationale.add("This looks like a .NET application; make sure the .NET/Mono Wine "
                    + "component is installed (see the runtime dependency manager) or it may fail "
                    + "to start.");
        }
    }

    private static String detectArchitecture(ManagedGame game) {
        if (game.executablePath == null || game.executablePath.isEmpty()) return "unknown";
        File exe = new File(game.executablePath);
        if (!exe.isFile()) return "unknown";
        try (RandomAccessFile raf = new RandomAccessFile(exe, "r")) {
            byte[] mz = new byte[2];
            raf.seek(0);
            if (raf.read(mz) != 2 || mz[0] != 'M' || mz[1] != 'Z') return "unknown";
            raf.seek(0x3C);
            int peOffset = readLittleEndianInt(raf);
            if (peOffset <= 0 || peOffset > exe.length() - 6) return "unknown";
            raf.seek(peOffset);
            byte[] sig = new byte[4];
            if (raf.read(sig) != 4 || sig[0] != 'P' || sig[1] != 'E'
                    || sig[2] != 0 || sig[3] != 0) {
                return "unknown";
            }
            int machine = raf.read() | (raf.read() << 8);
            switch (machine) {
                case 0x8664:
                    return "x86_64";
                case 0x014c:
                    return "x86";
                case 0xAA64:
                    return "arm64";
                default:
                    return "unknown";
            }
        }
        catch (Exception error) {
            return "unknown";
        }
    }

    private static int readLittleEndianInt(RandomAccessFile raf) throws java.io.IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) return -1;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static boolean anyEndsWith(Set<String> names, String... suffixes) {
        for (String name : names) {
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) return true;
            }
        }
        return false;
    }

    private static boolean hasAny(Set<String> names, String fragment) {
        for (String name : names) {
            if (name.contains(fragment)) return true;
        }
        return false;
    }

    private static void record(List<String> evidence, Set<String> names, String... markers) {
        for (String marker : markers) {
            if (marker.startsWith(".")) {
                if (anyEndsWith(names, marker)) evidence.add("*" + marker);
            }
            else if (names.contains(marker)) {
                evidence.add(marker);
            }
        }
    }

    private static String engineLabel(String engine) {        switch (engine) {
            case "RENPY":
                return "Ren'Py";
            case "RPG_MAKER_MV_MZ":
                return "RPG Maker MV/MZ (NW.js)";
            case "RPG_MAKER_RGSS":
                return "RPG Maker VX/XP/VX Ace (RGSS)";
            case "WOLF_RPG":
                return "Wolf RPG Editor";
            case "KIRIKIRI":
                return "KiriKiri / KAG";
            case "LIVEMAKER":
                return "LiveMaker / LiveNovel";
            case "UNITY":
                return "Unity";
            case "UNREAL":
                return "Unreal Engine";
            case "GODOT":
                return "Godot";
            case "ELECTRON":
                return "Electron";
            default:
                return "Unknown";
        }
    }

    // Advisory hint (agreed with AGM) letting the manager route a game to a native engine
    // interpreter (e.g. JoiPlay) vs Winlator. Winlator only self-assesses from the detected
    // engine; AGM owns the final routing decision. Emitted independently of whether any
    // suggestedConfig/suggestedSettings were produced. knownIssues tags are stable, kebab-case
    // and append-only so AGM can branch on them.
    private static JSONObject buildRunnerRecommendation(String engine) throws JSONException {
        String preferredRunner;
        String suitability;
        boolean nativePreferred;
        String confidence;
        JSONArray reasons = new JSONArray();
        JSONArray knownIssues = new JSONArray();

        switch (engine) {
            case "RPG_MAKER_RGSS":
                preferredRunner = "joiplay";
                suitability = "poor";
                nativePreferred = true;
                confidence = "high";
                reasons.put("RPG Maker XP/VX/VX Ace (RGSS) runs natively in a JoiPlay-style interpreter, which is lighter and avoids Windows emulation.");
                reasons.put("Winlator's bundled Wine 10.10 has a DirectSound/mmdevapi critical-section deadlock that can hang RGSS audio during playback.");
                knownIssues.put("wine-mmdevapi-audio-deadlock");
                break;
            case "RPG_MAKER_MV_MZ":
                preferredRunner = "joiplay";
                suitability = "fair";
                nativePreferred = true;
                confidence = "high";
                reasons.put("RPG Maker MV/MZ is an HTML5/NW.js engine that a native JoiPlay interpreter runs directly and more efficiently than emulating the Windows build.");
                break;
            case "RENPY":
                preferredRunner = "joiplay";
                suitability = "good";
                nativePreferred = true;
                confidence = "high";
                reasons.put("Ren'Py has a native Android interpreter (as used by JoiPlay) that is lighter than running the Windows build under Wine. Winlator also runs Ren'Py builds well.");
                break;
            case "WOLF_RPG":
                preferredRunner = "winlator";
                suitability = "good";
                nativePreferred = false;
                confidence = "high";
                reasons.put("Wolf RPG Editor games are Windows-only and are not natively supported by JoiPlay; Winlator runs them.");
                break;
            case "KIRIKIRI":
                preferredRunner = "winlator";
                suitability = "good";
                nativePreferred = false;
                confidence = "high";
                reasons.put("KiriKiri/KAG games are Windows-only and are not natively supported by JoiPlay; Winlator runs them.");
                break;
            case "LIVEMAKER":
                preferredRunner = "winlator";
                suitability = "good";
                nativePreferred = false;
                confidence = "high";
                reasons.put("LiveMaker has no maintained native Android interpreter; the Windows runtime must run under Wine.");
                reasons.put("LiveMaker enumerates installed Japanese fonts and may fail unless an actual Shift-JIS-capable MS Gothic-compatible face is present.");
                knownIssues.put("livemaker-font-enumeration");
                break;
            case "UNITY":
            case "UNREAL":
                preferredRunner = "winlator";
                suitability = "ideal";
                nativePreferred = false;
                confidence = "high";
                reasons.put("This is a native Windows game engine that JoiPlay does not run; Winlator is the appropriate runner.");
                break;
            case "GODOT":
            case "ELECTRON":
                preferredRunner = "winlator";
                suitability = "good";
                nativePreferred = false;
                confidence = "high";
                reasons.put("This is a native Windows build that JoiPlay does not run; Winlator is the appropriate runner.");
                break;
            default: // UNKNOWN
                preferredRunner = "winlator";
                suitability = "good";
                nativePreferred = false;
                confidence = "low";
                reasons.put("No engine markers were identified; treating it as a native Windows title that Winlator runs. A native interpreter like JoiPlay only helps for RPG Maker or Ren'Py games.");
                break;
        }

        JSONObject rec = new JSONObject();
        rec.put("winlatorSuitability", suitability);
        rec.put("nativeEngineInterpreterPreferred", nativePreferred);
        rec.put("preferredRunner", preferredRunner);
        rec.put("confidence", confidence);
        rec.put("reasons", reasons);
        rec.put("knownIssues", knownIssues);
        return rec;
    }
}
