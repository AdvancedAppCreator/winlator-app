package com.winlator.api;

import android.content.Context;
import android.content.Intent;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;
import com.winlator.core.WineUtils;
import com.winlator.win32.WinVersions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class GameApiJson {
    static final Set<String> CONFIG_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "screenSize",
            "winVersion",
            "envVars",
            "cpuList",
            "cpuListWoW64",
            "graphicsDriver",
            "graphicsDriverConfig",
            "dxwrapper",
            "dxwrapperConfig",
            "audioDriver",
            "audioDriverConfig",
            "wincomponents",
            "hudMode",
            "startupSelection",
            "box64Preset",
            "desktopTheme",
            "driverPolicy",
            "desktopMode",
            "forceFullscreen",
            "unityTextureLimit",
            "launchArguments"
    )));

    private GameApiJson() {
    }

    static JSONObject capabilities(Context context) throws JSONException {
        JSONObject capabilities = new JSONObject();
        capabilities.put("apiVersion", GameApiContract.API_VERSION);
        capabilities.put("packageName", context.getPackageName());
        capabilities.put("oneContainerPerGame", false);
        capabilities.put("sharedContainers", true);
        capabilities.put("sharedDefaultContainer", true);
        capabilities.put("defaultContainerPolicy", ManagedGame.CONTAINER_POLICY_ISOLATED);
        capabilities.put("defaultSharedContainerKey", ManagedGame.DEFAULT_SHARED_CONTAINER_KEY);
        capabilities.put("moveGameToIsolated", true);
        capabilities.put("referenceSafeContainerDeletion", true);
        capabilities.put("supportsPortableGames", true);
        capabilities.put("supportsWindowsInstallers", true);
        capabilities.put("supportsUnixExecutables", true);
        capabilities.put("supportsDosExecutables", true);
        capabilities.put("idempotentCreateByGameId", true);
        capabilities.put("createReconciledExtra", GameApiContract.EXTRA_CREATE_RECONCILED);
        capabilities.put("createConflictIncludesGame", true);
        capabilities.put("silentQueryProvider", true);
        capabilities.put("silentQueryProviderAuthority", GameApiContract.QUERY_PROVIDER_AUTHORITY);
        capabilities.put("agmMetadata", true);
        capabilities.put("agmMetadataMaxBytes", 65536);
        capabilities.put("gameEventBroadcast", true);
        capabilities.put("gameEventBroadcastAction", GameApiContract.ACTION_GAME_EVENT);
        capabilities.put("managedLaunchReturnsToCaller", true);
        capabilities.put(
                "gameEventSenderPermission",
                GameApiContract.PERMISSION_SEND_GAME_EVENTS
        );
        capabilities.put("installerProgress", true);
        capabilities.put("asyncInstallers", true);
        capabilities.put("asyncInstallerOptIn", true);
        capabilities.put("asyncInstallerExtra", GameApiContract.EXTRA_ASYNC_INSTALLER);
        capabilities.put("sameProfilePaths", true);
        capabilities.put("safUri", false);
        capabilities.put("listPagination", true);
        capabilities.put("recommendedListPageSize", 8);
        capabilities.put("managedGameConfiguration", true);
        capabilities.put("unityTextureLimitOverlay", true);
        capabilities.put("unityProductNameRepair", true);
        capabilities.put("gameConfigSchemaVersion", GameConfigSchema.SCHEMA_VERSION);
        capabilities.put("configSchemaPath", GameApiContract.QUERY_PATH_CONFIG_SCHEMA);
        capabilities.put("configConflictDetection", true);
        capabilities.put("configUpdateExtra", GameApiContract.EXTRA_CONFIG_UPDATE_JSON);
        capabilities.put("managedDiagnostics", true);
        capabilities.put("diagnosticsSchemaVersion", 1);
        capabilities.put("diagnosticHistory", true);
        capabilities.put("diagnosticHistoryPerGame", 20);
        capabilities.put("gameEventDiagnosticRefs", true);
        capabilities.put("diagnosticsPath", GameApiContract.QUERY_PATH_DIAGNOSTICS);
        capabilities.put("managedGameSettings", true);
        capabilities.put("gameSettingsSchemaVersion", GameSettingsSchema.SCHEMA_VERSION);
        capabilities.put(
                "settingsSchemaPath",
                GameApiContract.QUERY_PATH_SETTINGS_SCHEMA
        );
        capabilities.put("settingsConflictDetection", true);
        capabilities.put(
                "settingsUpdateExtra",
                GameApiContract.EXTRA_SETTINGS_UPDATE_JSON
        );
        capabilities.put("perGameLocalization", true);
        capabilities.put("perGameOcrProfiles", true);
        capabilities.put("automaticOcrScriptSelection", true);
        capabilities.put("tiledStrongOcr", true);
        capabilities.put("translationCache", true);
        capabilities.put(
                "translationCachePath",
                GameApiContract.QUERY_PATH_TRANSLATION_CACHE
        );
        capabilities.put("quickControlProfiles", true);
        capabilities.put("perGameControlsProfile", true);
        capabilities.put("performancePresets", true);
        capabilities.put("configurationSnapshots", true);
        capabilities.put("snapshotHistoryPerGame", 20);
        capabilities.put("snapshotsPath", GameApiContract.QUERY_PATH_SNAPSHOTS);
        capabilities.put("crashLoopRecovery", true);
        capabilities.put("recoveryPath", GameApiContract.QUERY_PATH_RECOVERY);
        capabilities.put("perGameModManager", true);
        capabilities.put("modsPath", GameApiContract.QUERY_PATH_MODS);
        capabilities.put("runtimeDependencyManager", true);
        capabilities.put("agmPrerequisiteBootstrap", true);
        capabilities.put("agmSharedContainerKey", "agm.default");
        capabilities.put(
                "dependenciesPath",
                GameApiContract.QUERY_PATH_DEPENDENCIES
        );
        capabilities.put("configSuggestions", true);
        capabilities.put(
                "configSuggestionsPath",
                GameApiContract.QUERY_PATH_SUGGESTED_CONFIG
        );
        capabilities.put("runnerRecommendation", true);
        capabilities.put("scopedApprovedIntegrations", true);
        capabilities.put("integrationRevocation", true);
        capabilities.put("integrationScopes", new JSONArray(ApiScope.ALL));
        capabilities.put("globalSettings", true);
        capabilities.put(
                "globalSettingsPath",
                GameApiContract.QUERY_PATH_GLOBAL_SETTINGS
        );

        JSONArray actions = new JSONArray();
        actions.put(GameApiContract.ACTION_GET_CAPABILITIES);
        actions.put(GameApiContract.ACTION_LIST_GAMES);
        actions.put(GameApiContract.ACTION_GET_GAME);
        actions.put(GameApiContract.ACTION_CREATE_GAME);
        actions.put(GameApiContract.ACTION_CONFIGURE_GAME);
        actions.put(GameApiContract.ACTION_RUN_INSTALLER);
        actions.put(GameApiContract.ACTION_LAUNCH_GAME);
        actions.put(GameApiContract.ACTION_DELETE_GAME);
        actions.put(GameApiContract.ACTION_MOVE_GAME_TO_ISOLATED);
        actions.put(GameApiContract.ACTION_CREATE_SNAPSHOT);
        actions.put(GameApiContract.ACTION_CLEAR_TRANSLATION_CACHE);
        actions.put(GameApiContract.ACTION_CONFIGURE_GLOBAL_SETTINGS);
        actions.put(GameApiContract.ACTION_OPEN_RECOVERY);
        actions.put(GameApiContract.ACTION_OPEN_MOD_MANAGER);
        actions.put(GameApiContract.ACTION_OPEN_DEPENDENCY_MANAGER);
        capabilities.put("actions", actions);

        JSONArray configFields = new JSONArray();
        for (String field : CONFIG_FIELDS) configFields.put(field);
        capabilities.put("configFields", configFields);
        return capabilities;
    }

    static JSONObject settingsPayload(JSONObject settings) throws JSONException {
        return new JSONObject()
                .put("settingsJson", settings)
                .put("settingsSha256", GameSettingsSchema.hash(settings));
    }

    static void applyLaunchConfig(
            Context context,
            Intent intent,
            ManagedGame game
    ) throws JSONException {
        if (game.configJson != null) {
            JSONObject config = new JSONObject(game.configJson);
            if (config.has("forceFullscreen")) {
                intent.putExtra(
                        GameApiContract.INTERNAL_EXTRA_FORCE_FULLSCREEN,
                        config.getBoolean("forceFullscreen")
                );
            }
            String launchArguments = config.optString("launchArguments", "").trim();
            if (!launchArguments.isEmpty()) {
                String existing = intent.getStringExtra("exec_args");
                String combined = existing != null && !existing.trim().isEmpty()
                        ? existing.trim() + " " + launchArguments
                        : launchArguments;
                intent.putExtra("exec_args", combined);
            }
        }
        JSONObject localization = GameSettingsSchema.effective(context, game)
                .getJSONObject("localization");
        intent.putExtra(
                GameApiContract.INTERNAL_EXTRA_GAME_LANGUAGE,
                localization.getString("gameLanguage")
        );
        String runtimeLocale = localization.getString("runtimeLocale");
        if (!"system".equals(runtimeLocale)) {
            intent.putExtra(
                    GameApiContract.INTERNAL_EXTRA_RUNTIME_LOCALE,
                    runtimeLocale
            );
        }
    }

    static JSONArray games(Context context, ManagedGameStore store)
            throws JSONException, IOException {
        return gamesPage(context, store, 0, Integer.MAX_VALUE).games;
    }

    static GamePage gamesPage(
            Context context,
            ManagedGameStore store,
            int offset,
            int limit
    ) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            ContainerManager manager = new ContainerManager(context);
            java.util.ArrayList<ManagedGame> records = store.list();
            Map<Integer, Long> allocatedSizes = new HashMap<>();
            JSONArray games = new JSONArray();
            int end = (int)Math.min(records.size(), (long)offset+limit);
            for (int index = offset; index < end; index++) {
                games.put(game(
                        context,
                        records.get(index),
                        manager,
                        store,
                        allocatedSizes,
                        true
                ));
            }
            return new GamePage(games, end < records.size(), end);
        }
    }

    static JSONObject game(Context context, ManagedGame game)
            throws JSONException, IOException {
        return game(context, game, true);
    }

    static JSONObject gameWithoutAllocatedSize(Context context, ManagedGame game)
            throws JSONException, IOException {
        return game(context, game, false);
    }

    private static JSONObject game(
            Context context,
            ManagedGame game,
            boolean includeAllocatedSize
    ) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            return game(
                    context,
                    game,
                    new ContainerManager(context),
                    new ManagedGameStore(context),
                    new HashMap<>(),
                    includeAllocatedSize
            );
        }
    }

    private static JSONObject game(
            Context context,
            ManagedGame game,
            ContainerManager manager,
            ManagedGameStore store,
            Map<Integer, Long> allocatedSizes,
            boolean includeAllocatedSize
    ) throws JSONException, IOException {
        JSONObject data = game.toJSONObject();
        data.remove("configJson");
        data.remove("settingsJson");
        data.remove("creationIdentitySha256");
        Container container = manager.getContainerById(game.containerId);
        ManagedGameStore.ContainerUsage usage = store.getContainerUsage(game.containerId);
        data.put("containerPresent", container != null);
        data.put("containerShared", usage.shared);
        data.put("containerReferenceCount", usage.referenceCount);
        if (usage.containerKey != null) data.put("containerKey", usage.containerKey);
        JSONObject config = container != null ? managedConfig(game, container) : null;
        if (config != null) {
            data.put("configJson", config);
            data.put("configSha256", GameConfigSchema.hash(config));
            data.put("containerConfig", config);
        }
        ManagedWinVersion.putMetadata(data, game, container);
        JSONObject settings = GameSettingsSchema.effective(context, game);
        data.put("settingsJson", settings);
        data.put("settingsSha256", GameSettingsSchema.hash(settings));
        if (container != null) {
            if (includeAllocatedSize) {
                Long allocatedSize = allocatedSizes.get(container.id);
                if (allocatedSize == null) {
                    allocatedSize = manager.getContainerAllocatedSize(container);
                    allocatedSizes.put(container.id, allocatedSize);
                }
                if (allocatedSize >= 0) {
                    data.put("containerAllocatedSizeBytes", allocatedSize);
                }
            }
        }
        return data;
    }

    static JSONObject containerConfig(Container container) throws JSONException {
        JSONObject config = new JSONObject();
        config.put("screenSize", container.getScreenSize());
        String winVersion = WinVersions.readVersion(container);
        if (winVersion != null) config.put("winVersion", winVersion);
        config.put("envVars", container.getEnvVars());
        config.put("cpuList", container.getCPUList(true));
        config.put("cpuListWoW64", container.getCPUListWoW64(true));
        config.put("graphicsDriver", container.getGraphicsDriver());
        config.put("graphicsDriverConfig", container.getGraphicsDriverConfig());
        config.put("dxwrapper", container.getDXWrapper());
        config.put("dxwrapperConfig", container.getDXWrapperConfig());
        config.put("audioDriver", container.getAudioDriver());
        config.put("audioDriverConfig", container.getAudioDriverConfig());
        config.put("wincomponents", container.getWinComponents());
        config.put("drives", container.getDrives());
        config.put("hudMode", container.getHUDMode());
        config.put("startupSelection", container.getStartupSelection());
        config.put("box64Preset", container.getBox64Preset());
        config.put("desktopTheme", container.getDesktopTheme());
        config.put("driverPolicy", container.getDriverPolicy());
        config.put("desktopMode", container.getDesktopMode());
        return config;
    }

    static JSONObject managedConfig(ManagedGame game, Container container) throws JSONException {
        if (game.configJson != null) return new JSONObject(game.configJson);
        JSONObject config = containerConfig(container);
        config.remove("winVersion");
        return config;
    }

    /**
     * Applies a managed-game config snapshot/patch onto a container (the inverse of
     * {@link #containerConfig(Container)}). Only keys present in {@code config} are applied, so it
     * works for both full snapshots and changed-only patches. Shared by every managed launch path.
     */
    static void applyContainerConfig(Container container, JSONObject config) throws JSONException {
        if (config.has("screenSize")) container.setScreenSize(config.getString("screenSize"));
        if (config.has("winVersion")) {
            WineUtils.setWinVersion(container, config.getString("winVersion"));
        }
        if (config.has("envVars")) container.setEnvVars(config.getString("envVars"));
        if (config.has("cpuList")) container.setCPUList(config.getString("cpuList"));
        if (config.has("cpuListWoW64")) container.setCPUListWoW64(config.getString("cpuListWoW64"));
        if (config.has("graphicsDriver")) container.setGraphicsDriver(config.getString("graphicsDriver"));
        if (config.has("graphicsDriverConfig")) container.setGraphicsDriverConfig(config.getString("graphicsDriverConfig"));
        if (config.has("dxwrapper")) container.setDXWrapper(config.getString("dxwrapper"));
        if (config.has("dxwrapperConfig")) container.setDXWrapperConfig(config.getString("dxwrapperConfig"));
        if (config.has("audioDriver")) container.setAudioDriver(config.getString("audioDriver"));
        if (config.has("audioDriverConfig")) container.setAudioDriverConfig(config.getString("audioDriverConfig"));
        if (config.has("wincomponents")) container.setWinComponents(config.getString("wincomponents"));
        if (config.has("hudMode")) container.setHUDMode((byte)config.getInt("hudMode"));
        if (config.has("startupSelection")) container.setStartupSelection((byte)config.getInt("startupSelection"));
        if (config.has("box64Preset")) container.setBox64Preset(config.getString("box64Preset"));
        if (config.has("driverPolicy")) container.setDriverPolicy(config.getString("driverPolicy"));
        if (config.has("desktopMode")) container.setDesktopMode(config.getString("desktopMode"));
        if (config.has("desktopTheme")) container.setDesktopTheme(config.getString("desktopTheme"));
    }

    static final class GamePage {
        final JSONArray games;
        final boolean hasMore;
        final int nextOffset;

        GamePage(JSONArray games, boolean hasMore, int nextOffset) {
            this.games = games;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
        }
    }
}
