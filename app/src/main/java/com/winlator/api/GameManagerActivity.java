package com.winlator.api;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;
import com.winlator.core.AppUtils;
import com.winlator.core.RuntimeLocaleManager;
import com.winlator.core.StartupLog;
import com.winlator.core.WineUtils;
import com.winlator.xenvironment.RootFS;
import com.winlator.text.GameTextCacheManager;
import com.winlator.api.dependency.RuntimeDependencyActivity;
import com.winlator.api.dependency.RuntimeDependencyManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameManagerActivity extends Activity {
    private static final Object RESERVATION_LOCK = new Object();
    private static final int MAX_AGM_METADATA_BYTES = 65536;
    private static final String INTERNAL_EXTRA_RUNTIME_LOCALE_PREPARED =
            "com.winlator.secure.internal.RUNTIME_LOCALE_PREPARED";
    private static boolean mutationReserved;
    private static String reservedMutationToken;
    private static boolean launchReserved;
    private static boolean sessionClaimed;
    private static String reservedLaunchToken;
    private static String activeSessionToken;

    private ManagedGameStore store;
    private boolean mutationInProgress;
    private boolean ownsMutationReservation;
    private boolean ownsLaunchReservation;
    private String ownedLaunchToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartupLog.initialize(this);
        store = new ManagedGameStore(this);

        GameApiAuthorization.AuthResult authorization =
                GameApiAuthorization.authorizePackage(this, getCallingPackage(), null);
        if (!authorization.authenticated) {
            finishError(
                    GameApiContract.ERROR_UNAUTHORIZED,
                    "The caller is not an approved Winlator integration."
            );
            return;
        }

        String action = getIntent().getAction();
        String requiredScope = GameApiAuthorization.requiredScope(action);
        if (requiredScope == null) {
            finishError(
                    GameApiContract.ERROR_UNSUPPORTED_ACTION,
                    "Unsupported or missing API action."
            );
            return;
        }
        if (!authorization.scopes.contains(requiredScope)) {
            finishError(
                    GameApiContract.ERROR_SCOPE_DENIED,
                    "The approved caller does not have the " + requiredScope + " scope."
            );
            return;
        }
        if (isAsynchronousMutation(action) && !reserveMutation()) {
            finishError(GameApiContract.ERROR_OPERATION_IN_PROGRESS, "Another Winlator game operation is still in progress.");
            return;
        }
        try {
            if (GameApiContract.ACTION_GET_CAPABILITIES.equals(action)) {
                handleGetCapabilities();
            }
            else if (GameApiContract.ACTION_LIST_GAMES.equals(action)) {
                handleListGames();
            }
            else if (GameApiContract.ACTION_GET_GAME.equals(action)) {
                handleGetGame();
            }
            else if (GameApiContract.ACTION_CREATE_GAME.equals(action)) {
                handleCreateGame();
            }
            else if (GameApiContract.ACTION_CONFIGURE_GAME.equals(action)) {
                handleConfigureGame();
            }
            else if (GameApiContract.ACTION_RUN_INSTALLER.equals(action)) {
                handleRunInstaller();
            }
            else if (GameApiContract.ACTION_LAUNCH_GAME.equals(action)) {
                handleLaunchGame();
            }
            else if (GameApiContract.ACTION_DELETE_GAME.equals(action)) {
                handleDeleteGame();
            }
            else if (GameApiContract.ACTION_MOVE_GAME_TO_ISOLATED.equals(action)) {
                handleMoveGameToIsolated();
            }
            else if (GameApiContract.ACTION_CREATE_SNAPSHOT.equals(action)) {
                handleCreateSnapshot();
            }
            else if (GameApiContract.ACTION_CLEAR_TRANSLATION_CACHE.equals(action)) {
                handleClearTranslationCache();
            }
            else if (GameApiContract.ACTION_CONFIGURE_GLOBAL_SETTINGS.equals(action)) {
                handleConfigureGlobalSettings();
            }
            else if (GameApiContract.ACTION_OPEN_RECOVERY.equals(action)) {
                handleOpenRecovery();
            }
            else if (GameApiContract.ACTION_OPEN_MOD_MANAGER.equals(action)) {
                handleOpenModManager();
            }
            else if (GameApiContract.ACTION_OPEN_DEPENDENCY_MANAGER.equals(action)) {
                handleOpenDependencyManager();
            }
            else throw new IllegalStateException("The validated action was not dispatched.");
        }
        catch (RequestFinishedException ignored) {
        }
        catch (ApiRequestException e) {
            finishError(e.code, e.getMessage());
        }
        catch (JSONException | IllegalArgumentException e) {
            finishError(GameApiContract.ERROR_INVALID_ARGUMENT, e.getMessage());
        }
        catch (IOException e) {
            finishError(GameApiContract.ERROR_STORAGE_FAILED, e.getMessage());
        }
    }

    private void handleGetCapabilities() throws JSONException {
        Intent result = successResult();
        result.putExtra(
                GameApiContract.EXTRA_CAPABILITIES_JSON,
                GameApiJson.capabilities(this).toString()
        );
        finishSuccess(result);
    }

    private void handleCreateSnapshot() throws JSONException, IOException {
        ManagedGame game = requireGame();
        JSONObject snapshot = new ConfigurationSnapshotStore(this).create(
                game,
                optionalString(getIntent(), GameApiContract.EXTRA_LABEL),
                "external"
        );
        Intent result = successResult();
        result.putExtra(
                GameApiContract.EXTRA_SNAPSHOT_JSON,
                snapshot.toString()
        );
        finishSuccess(result);
    }

    private void handleClearTranslationCache() throws JSONException, IOException {
        ManagedGame game = requireGame();
        GameTextCacheManager.clear(this, "game:" + game.id);
        Intent result = gameResult(game);
        result.putExtra(
                GameApiContract.EXTRA_CACHE_STATUS_JSON,
                GameTextCacheManager.status(this, "game:" + game.id).toString()
        );
        finishSuccess(result);
    }

    private void handleConfigureGlobalSettings() throws JSONException, IOException {
        JSONObject update = parseSettingsUpdate(getIntent());
        if (update == null) {
            throw new IllegalArgumentException("settings_update_json is required.");
        }

        try {
            JSONObject settings = new GlobalSettingsStore(this).update(update);
            Intent result = successResult();
            result.putExtra(
                    GameApiContract.EXTRA_GLOBAL_SETTINGS_JSON,
                    GameApiJson.settingsPayload(settings).toString()
            );
            String hash = GameSettingsSchema.hash(settings);
            JSONObject changed = update.getJSONObject("set");
            for (Iterator<String> keys = changed.keys(); keys.hasNext(); ) {
                GameSessionEventReporter.sendSettingsChanged(
                        this,
                        null,
                        keys.next(),
                        hash,
                        GameApiContract.QUERY_PATH_GLOBAL_SETTINGS
                );
            }
            finishSuccess(result);
        }
        catch (GlobalSettingsStore.SettingsConflictException conflict) {
            Intent result = new Intent();
            result.putExtra(GameApiContract.EXTRA_SUCCESS, false);
            result.putExtra(
                    GameApiContract.EXTRA_API_VERSION,
                    GameApiContract.API_VERSION
            );
            result.putExtra(
                    GameApiContract.EXTRA_ERROR_CODE,
                    GameApiContract.ERROR_SETTINGS_CONFLICT
            );
            result.putExtra(
                    GameApiContract.EXTRA_ERROR_MESSAGE,
                    conflict.getMessage()
            );
            result.putExtra(
                    GameApiContract.EXTRA_GLOBAL_SETTINGS_JSON,
                    GameApiJson.settingsPayload(conflict.current).toString()
            );
            setResult(RESULT_CANCELED, result);
            releaseMutation();
            finish();
        }
    }

    private void handleOpenRecovery() throws JSONException, IOException {
        ManagedGame game = requireGame();
        Intent open = new Intent(this, RecoveryActivity.class);
        open.putExtra(GameApiContract.EXTRA_GAME_ID, game.id);
        finishSuccess(gameResult(game), open);
    }

    private void handleOpenModManager() throws JSONException, IOException {
        ManagedGame game = requireGame();
        Intent open = new Intent(this, ModManagerActivity.class);
        open.putExtra(GameApiContract.EXTRA_GAME_ID, game.id);
        finishSuccess(gameResult(game), open);
    }

    private void handleOpenDependencyManager() throws JSONException, IOException {
        ManagedGame game = requireGame();
        Intent open = new Intent(this, RuntimeDependencyActivity.class);
        open.putExtra(
                RuntimeDependencyActivity.INTERNAL_EXTRA_TRUSTED_CALLER,
                RuntimeDependencyActivity.TRUSTED_CALLER_VALUE
        );
        open.putExtra(RuntimeDependencyActivity.EXTRA_CONTAINER_ID, game.containerId);
        open.putExtra(RuntimeDependencyActivity.EXTRA_GAME_ID, game.id);
        finishSuccess(gameResult(game), open);
    }

    private void handleListGames() throws JSONException, IOException {
        Intent request = getIntent();
        boolean paged = request.hasExtra(GameApiContract.EXTRA_OFFSET) ||
                request.hasExtra(GameApiContract.EXTRA_LIMIT);
        int offset = paged ? request.getIntExtra(GameApiContract.EXTRA_OFFSET, 0) : 0;
        int limit = paged
                ? request.getIntExtra(GameApiContract.EXTRA_LIMIT, 8)
                : Integer.MAX_VALUE;
        if (paged) GameApiContract.validatePage(offset, limit);
        GameApiJson.GamePage page = GameApiJson.gamesPage(this, store, offset, limit);

        Intent result = successResult();
        result.putExtra(
                GameApiContract.EXTRA_GAMES_JSON,
                page.games.toString()
        );
        if (paged) {
            result.putExtra(GameApiContract.EXTRA_HAS_MORE, page.hasMore);
            result.putExtra(GameApiContract.EXTRA_NEXT_OFFSET, page.nextOffset);
        }
        finishSuccess(result);
    }

    private void handleGetGame() throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            ManagedGame game = requireGame();
            finishSuccess(gameResult(game));
        }
    }

    private void handleCreateGame() throws JSONException, IOException {
        if (!RootFS.find(this).isValid()) {
            finishError(GameApiContract.ERROR_ROOTFS_NOT_READY, "Open Winlator once and finish its initial setup.");
            return;
        }

        Intent request = getIntent();
        String title = requiredString(request, GameApiContract.EXTRA_TITLE);
        String gameId = optionalString(request, GameApiContract.EXTRA_GAME_ID);
        if (gameId == null) gameId = UUID.randomUUID().toString();
        validateGameId(gameId);
        String containerPolicy = optionalString(
                request,
                GameApiContract.EXTRA_CONTAINER_POLICY
        );
        if (containerPolicy == null) {
            containerPolicy = ManagedGame.CONTAINER_POLICY_ISOLATED;
        }
        validateContainerPolicy(containerPolicy);
        String containerKey = optionalString(request, GameApiContract.EXTRA_CONTAINER_KEY);
        if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(containerPolicy)) {
            if (containerKey == null) {
                containerKey = ManagedGame.DEFAULT_SHARED_CONTAINER_KEY;
            }
            validateContainerKey(containerKey);
        }
        else if (containerKey != null) {
            throw new IllegalArgumentException(
                    "container_key is only valid with container_policy=shared_default."
            );
        }

        String gamePath = optionalString(request, GameApiContract.EXTRA_GAME_PATH);
        String executablePath = optionalString(request, GameApiContract.EXTRA_EXECUTABLE_PATH);
        String executableDosPath = optionalString(request, GameApiContract.EXTRA_EXECUTABLE_DOS_PATH);
        String installerPath = optionalString(request, GameApiContract.EXTRA_INSTALLER_PATH);
        String arguments = optionalString(request, GameApiContract.EXTRA_ARGUMENTS);
        String installerArguments = optionalString(request, GameApiContract.EXTRA_INSTALLER_ARGUMENTS);
        String agmMetadata = request.hasExtra(GameApiContract.EXTRA_AGM_METADATA)
                ? rawString(request, GameApiContract.EXTRA_AGM_METADATA)
                : null;
        validateAgmMetadata(agmMetadata);
        validateExecutableChoice(executablePath, executableDosPath);

        if (executablePath == null && executableDosPath == null && installerPath == null) {
            throw new IllegalArgumentException("Provide an executable path or installer path.");
        }
        if (executableDosPath != null && installerPath == null) {
            throw new IllegalArgumentException("A DOS executable on a new container requires installer_path.");
        }
        if (executableDosPath != null) validateDosPath(executableDosPath);
        if (gamePath != null) gamePath = normalizeProfilePath(gamePath, "game_path");
        if (executablePath != null) {
            executablePath = normalizeProfilePath(executablePath, "executable_path");
        }
        if (installerPath != null) {
            installerPath = normalizeProfilePath(installerPath, "installer_path");
        }

        synchronized (ContainerOperationLock.LOCK) {
            ManagedGame existing = store.get(gameId);
            if (existing != null) {
                finishExistingCreate(
                        existing,
                        gamePath,
                        executablePath,
                        executableDosPath,
                        installerPath,
                        containerPolicy,
                        containerKey
                );
                return;
            }
        }

        if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(containerPolicy)
                && !requireAgmBootstrapReady()) {
            return;
        }

        if (gamePath != null && !new File(gamePath).isDirectory()) {
            throw new IllegalArgumentException("game_path must be an existing absolute directory.");
        }
        if (executablePath != null && !new File(executablePath).isFile()) {
            throw new IllegalArgumentException("executable_path must be an existing absolute file.");
        }
        if (installerPath != null && !new File(installerPath).isFile()) {
            throw new IllegalArgumentException("installer_path must be an existing absolute file.");
        }

        JSONObject config = parseConfig(request);
        JSONObject settings = parseInitialSettings(request);
        JSONObject settingsRuntime = settings.getJSONObject("runtime");
        if (config.length() > 0 && settingsRuntime.length() > 0) {
            throw new IllegalArgumentException(
                    "Provide runtime settings through either config_json or settings_json, not both."
            );
        }
        if (config.length() == 0 && settingsRuntime.length() > 0) {
            config = new JSONObject(settingsRuntime.toString());
        }
        final boolean explicitWinVersion = config.has("winVersion");
        String initialPreset = settings.getJSONObject("performance")
                .getString("preset");
        JSONObject initialPresetPatch = GameSettingsSchema.performancePatch(
                initialPreset
        );
        if (config.length() == 0 && initialPresetPatch.length() > 0) {
            config = initialPresetPatch;
        }
        else if (config.length() > 0 && initialPresetPatch.length() > 0) {
            settings.getJSONObject("performance")
                    .put("preset", GameSettingsSchema.PRESET_CUSTOM);
        }
        settings.put("runtime", new JSONObject(config.toString()));
        JSONObject containerData = ManagedContainerFactory.createData(
                this,
                ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(containerPolicy)
                        ? sharedContainerName(containerKey)
                        : title,
                buildDrives(gamePath, executablePath, installerPath),
                config
        );

        final String finalGameId = gameId;
        final String finalGamePath = gamePath;
        final String finalExecutablePath = executablePath;
        final String finalExecutableDosPath = executableDosPath;
        final String finalInstallerPath = installerPath;
        final String finalArguments = arguments;
        final String finalInstallerArguments = installerArguments;
        final String finalAgmMetadata = agmMetadata;
        final String finalContainerPolicy = containerPolicy;
        final String finalContainerKey = containerKey;
        final String finalConfigJson = configFromContainerData(containerData).toString();
        final String finalWinVersionSource = explicitWinVersion
                ? ManagedGame.WIN_VERSION_SOURCE_EXPLICIT
                : ManagedGame.WIN_VERSION_SOURCE_DEFAULT;
        final String finalSettingsJson = settings.toString();
        final boolean launchAfterCreate = request.getBooleanExtra(GameApiContract.EXTRA_LAUNCH_AFTER_CREATE, true);
        final boolean asyncInstaller = finalInstallerPath != null &&
                request.getBooleanExtra(GameApiContract.EXTRA_ASYNC_INSTALLER, false);
        if (asyncInstaller && !launchAfterCreate) {
            throw new IllegalArgumentException(
                    "async_installer requires launch_after_create=true."
            );
        }
        if (launchAfterCreate && !reserveLaunch()) {
            finishError(GameApiContract.ERROR_WINLATOR_BUSY, "Close the currently running Winlator session first.");
            return;
        }

        mutationInProgress = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                CreateOutcome outcome;
                synchronized (ContainerOperationLock.LOCK) {
                    outcome = createManagedGame(
                            containerData,
                            finalGameId,
                            title,
                            finalGamePath,
                            finalExecutablePath,
                            finalExecutableDosPath,
                            finalInstallerPath,
                            finalArguments,
                            finalAgmMetadata,
                            finalContainerPolicy,
                            finalContainerKey,
                            finalConfigJson,
                            finalWinVersionSource,
                            finalSettingsJson,
                            asyncInstaller
                    );
                }
                File launchOverlay = null;
                if (outcome.errorCode == null
                        && !outcome.reconciled
                        && launchAfterCreate
                        && finalInstallerPath == null
                        && hasConfiguredExecutable(outcome.game)) {
                    Container launchContainer = new ContainerManager(this)
                            .getContainerById(outcome.containerId);
                    if (launchContainer != null) {
                        launchOverlay = UnityLaunchOverlay.prepare(
                                    this,
                                    outcome.game,
                                    launchContainer,
                                    GameApiJson.managedConfig(
                                            outcome.game,
                                            launchContainer
                                    )
                            );
                    }
                }
                File finalLaunchOverlay = launchOverlay;
                runOnUiThread(() -> finishCreate(
                        outcome,
                        finalInstallerPath,
                        finalInstallerArguments,
                        launchAfterCreate,
                        asyncInstaller,
                        finalLaunchOverlay
                ));
            }
            catch (RuntimeException | JSONException error) {
                runOnUiThread(() -> {
                    mutationInProgress = false;
                    finishError(
                            GameApiContract.ERROR_STORAGE_FAILED,
                            error.getMessage()
                    );
                });
            }
            finally {
                executor.shutdown();
            }
        });
    }

    private CreateOutcome createManagedGame(
            JSONObject containerData,
            String gameId,
            String title,
            String gamePath,
            String executablePath,
            String executableDosPath,
            String installerPath,
            String arguments,
            String agmMetadata,
            String containerPolicy,
            String containerKey,
            String configJson,
            String winVersionSource,
            String settingsJson,
            boolean asyncInstaller
    ) {
        ContainerManager manager = new ContainerManager(this);
        Container container = null;
        boolean containerCreated = false;
        try {
            ManagedGame existing = store.get(gameId);
            if (existing != null) {
                if (existing.matchesCreateIdentity(
                        gamePath,
                        executablePath,
                        executableDosPath,
                        installerPath,
                        containerPolicy,
                        containerKey
                )) {
                    return CreateOutcome.reconciled(existing);
                }
                return CreateOutcome.conflict(existing);
            }

            if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(containerPolicy)) {
                Integer boundContainerId = store.getSharedContainerId(containerKey);
                if (boundContainerId != null) {
                    container = manager.getContainerById(boundContainerId);
                    if (container == null) {
                        store.clearSharedContainer(containerKey, boundContainerId);
                    }
                }
            }

            if (container == null) {
                container = manager.createContainer(containerData);
                if (container == null) {
                    return CreateOutcome.error(
                            GameApiContract.ERROR_CREATE_FAILED,
                            "Winlator could not create the game container."
                    );
                }
                containerCreated = true;
            }

            long now = System.currentTimeMillis();
            ManagedGame game = new ManagedGame();
            game.id = gameId;
            game.title = title;
            game.containerId = container.id;
            game.containerPolicy = containerPolicy;
            game.containerKey = containerKey;
            game.gamePath = gamePath;
            game.executablePath = executablePath;
            game.executableDosPath = executableDosPath;
            game.setCreationIdentity(installerPath);
            game.arguments = arguments;
            game.configJson = configJson;
            game.winVersionSource = winVersionSource;
            game.settingsJson = settingsJson;
            game.agmMetadata = agmMetadata;
            String settledState = hasConfiguredExecutable(game) ? "ready" : "setup_required";
            game.state = asyncInstaller ? "installing" : settledState;
            game.createdAt = now;
            game.updatedAt = now;

            if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(containerPolicy)) {
                store.putSharedGame(game, containerKey, container.id);
            }
            else {
                store.put(game);
            }
            return CreateOutcome.success(
                    game,
                    container.id,
                    containerCreated,
                    settledState
            );
        }
        catch (JSONException | IOException e) {
            if (containerCreated && container != null) manager.removeContainer(container);
            return CreateOutcome.error(
                    GameApiContract.ERROR_STORAGE_FAILED,
                    "The container was rolled back because its game record could not be saved."
            );
        }
    }

    private void finishExistingCreate(
            ManagedGame existing,
            String gamePath,
            String executablePath,
            String executableDosPath,
            String installerPath,
            String containerPolicy,
            String containerKey
    ) throws JSONException, IOException {
        if (!existing.matchesCreateIdentity(
                gamePath,
                executablePath,
                executableDosPath,
                installerPath,
                containerPolicy,
                containerKey
        )) {
            finishGameError(
                    GameApiContract.ERROR_GAME_ALREADY_EXISTS,
                    "A different managed game already uses id " + existing.id + ".",
                    existing
            );
            return;
        }
        Intent result = gameResult(existing);
        result.putExtra(GameApiContract.EXTRA_CREATE_RECONCILED, true);
        finishSuccess(result);
    }

    private void finishCreate(
            CreateOutcome outcome,
            String installerPath,
            String installerArguments,
            boolean launchAfterCreate,
            boolean asyncInstaller,
            File launchOverlay
    ) {
        mutationInProgress = false;
        if (outcome.errorCode != null) {
            if (outcome.game != null) {
                try {
                    finishGameError(outcome.errorCode, outcome.errorMessage, outcome.game);
                }
                catch (JSONException | IOException error) {
                    finishError(GameApiContract.ERROR_STORAGE_FAILED, error.getMessage());
                }
            }
            else {
                finishError(outcome.errorCode, outcome.errorMessage);
            }
            return;
        }
        if (outcome.reconciled) {
            try {
                Intent result = gameResult(outcome.game);
                result.putExtra(GameApiContract.EXTRA_CREATE_RECONCILED, true);
                finishSuccess(result);
            }
            catch (JSONException | IOException error) {
                finishError(GameApiContract.ERROR_STORAGE_FAILED, error.getMessage());
            }
            return;
        }

        try {
            Intent launchIntent = null;
            if (launchAfterCreate) {
                ContainerManager manager = new ContainerManager(this);
                Container container = manager.getContainerById(outcome.containerId);
                if (container == null) {
                    finishError(
                            GameApiContract.ERROR_CONTAINER_NOT_FOUND,
                            "The managed game's container no longer exists."
                    );
                    return;
                }
                synchronized (ContainerOperationLock.LOCK) {
                    prepareContainerForGame(container, outcome.game, installerPath);
                }

                if (installerPath != null) {
                    Intent installerIntent = buildLaunchIntent(
                            container.id,
                            installerPath,
                            null,
                            installerArguments
                    );
                    GameApiJson.applyLaunchConfig(this, installerIntent, outcome.game);
                    launchIntent = GameSessionEventReporter.configure(
                            installerIntent,
                            outcome.game,
                            true,
                            outcome.settledState
                    );
                    GameSessionEventReporter.sendInstallerProgress(
                            this,
                            launchIntent,
                            outcome.containerCreated
                                    ? "container_created"
                                    : "container_reused"
                    );
                }
                else if (hasConfiguredExecutable(outcome.game)) {
                    launchIntent = GameSessionEventReporter.configure(
                            buildLaunchIntent(outcome.game, launchOverlay),
                            outcome.game,
                            false,
                            outcome.game.state
                    );
                }
            }

            if (launchIntent != null && XServerDisplayActivity.isSessionActive()) {
                if (asyncInstaller) {
                    GameSessionEventReporter.failToStart(
                            this,
                            launchIntent,
                            GameApiContract.ERROR_WINLATOR_BUSY,
                            "Another Winlator session started before the installer could launch."
                    );
                    outcome.game.state = outcome.settledState;
                    outcome.game.updatedAt = System.currentTimeMillis();
                    store.put(outcome.game);
                }
                Intent result = gameResult(outcome.game);
                result.putExtra(GameApiContract.EXTRA_LAUNCH_DEFERRED, true);
                releaseLaunch();
                finishSuccess(result);
                return;
            }
            if (asyncInstaller && launchIntent != null) {
                finishSuccessBeforeLaunch(gameResult(outcome.game), launchIntent);
            }
            else {
                finishSuccess(gameResult(outcome.game), launchIntent);
            }
        }
        catch (JSONException | IOException e) {
            finishError(GameApiContract.ERROR_STORAGE_FAILED, e.getMessage());
        }
    }

    private void handleConfigureGame() throws JSONException, IOException {
        ManagedGame game = requireGame();
        if (isLaunchBusy()) {
            finishError(
                    GameApiContract.ERROR_WINLATOR_BUSY,
                    "Close the currently running Winlator session before changing game configuration."
            );
            return;
        }
        ContainerManager manager = new ContainerManager(this);
        Container container = manager.getContainerById(game.containerId);
        if (container == null) {
            finishError(GameApiContract.ERROR_CONTAINER_NOT_FOUND, "The managed game's container no longer exists.");
            return;
        }

        Intent request = getIntent();
        JSONObject legacyConfig = parseConfig(request);
        JSONObject configUpdate = parseConfigUpdate(request);
        JSONObject settingsUpdate = parseSettingsUpdate(request);
        if (configUpdate != null && legacyConfig.length() > 0) {
            throw new IllegalArgumentException(
                    "Provide only config_json or config_update_json, not both."
            );
        }
        if (prepareRuntimeLocaleBeforeConfigure(settingsUpdate)) return;

        synchronized (ContainerOperationLock.LOCK) {
            JSONObject gameConfig = GameApiJson.managedConfig(game, container);
            JSONObject gameSettings = GameSettingsSchema.effective(this, game);
            JSONObject configSet = legacyConfig;
            if (configUpdate != null) {
                String currentHash = GameConfigSchema.hash(gameConfig);
                if (!currentHash.equals(configUpdate.getString("baseConfigSha256"))) {
                    finishConfigConflict(game);
                    return;
                }
                configSet = configUpdate.getJSONObject("set");
            }
            if (settingsUpdate != null) {
                String currentSettingsHash = GameSettingsSchema.hash(gameSettings);
                if (!currentSettingsHash.equals(
                        settingsUpdate.getString("baseSettingsSha256")
                )) {
                    finishSettingsConflict(game);
                    return;
                }
                JSONObject settingsSet = settingsUpdate.getJSONObject("set");
                JSONObject runtimeSet = settingsSet.optJSONObject("runtime");
                if (runtimeSet != null && runtimeSet.length() > 0) {
                    if (configSet.length() > 0) {
                        throw new IllegalArgumentException(
                                "Do not combine config_json/config_update_json with settings.runtime."
                        );
                    }
                    configSet = runtimeSet;
                }
                if (settingsSet.optJSONObject("performance") != null &&
                        settingsSet.getJSONObject("performance").has("preset")) {
                    String preset = settingsSet.getJSONObject("performance")
                            .getString("preset");
                    configSet = resolveConfigSetForPerformancePreset(configSet, preset);
                }
                gameSettings = GameSettingsSchema.applyUpdate(
                        this,
                        gameSettings,
                        settingsSet
                );
            }

            if (request.hasExtra(GameApiContract.EXTRA_TITLE)) {
                game.title = requiredString(request, GameApiContract.EXTRA_TITLE);
                if (ManagedGame.CONTAINER_POLICY_ISOLATED.equals(game.containerPolicy)) {
                    container.setName(game.title);
                }
            }

            if (request.hasExtra(GameApiContract.EXTRA_GAME_PATH)) {
                game.gamePath = requireDirectory(requiredString(request, GameApiContract.EXTRA_GAME_PATH), "game_path");
            }

            boolean hasUnixExecutable = request.hasExtra(GameApiContract.EXTRA_EXECUTABLE_PATH);
            boolean hasDosExecutable = request.hasExtra(GameApiContract.EXTRA_EXECUTABLE_DOS_PATH);
            if (hasUnixExecutable && hasDosExecutable) {
                throw new IllegalArgumentException("Provide only one executable path format.");
            }
            if (hasUnixExecutable) {
                game.executablePath = requireFile(requiredString(request, GameApiContract.EXTRA_EXECUTABLE_PATH), "executable_path");
                game.executableDosPath = null;
            }
            else if (hasDosExecutable) {
                game.executableDosPath = requiredString(request, GameApiContract.EXTRA_EXECUTABLE_DOS_PATH);
                validateDosPath(game.executableDosPath);
                container.setDrives(buildDrives(game.gamePath, null, null));
                String unixPath = WineUtils.dosToUnixPath(game.executableDosPath, container);
                if (unixPath.isEmpty() || !new File(unixPath).isFile()) {
                    throw new IllegalArgumentException("executable_dos_path does not exist in the game container.");
                }
                game.executablePath = null;
            }

            if (request.hasExtra(GameApiContract.EXTRA_ARGUMENTS)) {
                game.arguments = optionalString(request, GameApiContract.EXTRA_ARGUMENTS);
            }
            if (request.hasExtra(GameApiContract.EXTRA_AGM_METADATA)) {
                game.agmMetadata = rawString(request, GameApiContract.EXTRA_AGM_METADATA);
                validateAgmMetadata(game.agmMetadata);
            }

            for (Iterator<String> keys = configSet.keys(); keys.hasNext(); ) {
                String key = keys.next();
                gameConfig.put(key, configSet.get(key));
            }
            if (configSet.has("winVersion")) {
                game.winVersionSource = ManagedGame.WIN_VERSION_SOURCE_EXPLICIT;
            }
            gameSettings.put("runtime", new JSONObject(gameConfig.toString()));
            game.configJson = gameConfig.toString();
            game.settingsJson = gameSettings.toString();
            prepareContainerForGame(container, game, null);

            if (!"installing".equals(game.state)) {
                game.state = hasConfiguredExecutable(game) ? "ready" : "setup_required";
            }
            game.updatedAt = System.currentTimeMillis();
            store.put(game);
        }
        JSONObject authoritativeSettings = GameSettingsSchema.effective(this, game);
        String settingsHash = GameSettingsSchema.hash(authoritativeSettings);
        if (legacyConfig.length() > 0 || configUpdate != null) {
            GameSessionEventReporter.sendSettingsChanged(
                    this,
                    game.id,
                    "runtime",
                    settingsHash,
                    "games/" + game.id + "/" + GameApiContract.QUERY_PATH_SETTINGS
            );
        }
        if (settingsUpdate != null) {
            for (Iterator<String> keys =
                    settingsUpdate.getJSONObject("set").keys(); keys.hasNext(); ) {
                GameSessionEventReporter.sendSettingsChanged(
                        this,
                        game.id,
                        keys.next(),
                        settingsHash,
                        "games/" + game.id + "/" +
                                GameApiContract.QUERY_PATH_SETTINGS
                );
            }
        }
        finishSuccess(gameResult(game));
    }

    private boolean prepareRuntimeLocaleBeforeConfigure(JSONObject settingsUpdate)
            throws JSONException {
        if (settingsUpdate == null ||
                getIntent().getBooleanExtra(
                        INTERNAL_EXTRA_RUNTIME_LOCALE_PREPARED,
                        false
                )) {
            return false;
        }
        JSONObject localization = settingsUpdate.getJSONObject("set")
                .optJSONObject("localization");
        if (localization == null || !localization.has("runtimeLocale")) return false;
        String locale = localization.getString("runtimeLocale");
        if ("system".equals(locale)) return false;

        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid()) {
            finishError(
                    GameApiContract.ERROR_ROOTFS_NOT_READY,
                    "Open Winlator once and finish its initial setup."
            );
            return true;
        }

        mutationInProgress = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            Exception failure = null;
            try {
                RuntimeLocaleManager.ensureAvailable(rootFS, locale);
            }
            catch (IOException | IllegalArgumentException error) {
                failure = error;
            }
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                mutationInProgress = false;
                if (finalFailure != null) {
                    finishError(
                            finalFailure instanceof IllegalArgumentException
                                    ? GameApiContract.ERROR_INVALID_ARGUMENT
                                    : GameApiContract.ERROR_STORAGE_FAILED,
                            "The game locale could not be prepared: "
                                    + finalFailure.getMessage()
                    );
                    return;
                }
                getIntent().putExtra(INTERNAL_EXTRA_RUNTIME_LOCALE_PREPARED, true);
                try {
                    handleConfigureGame();
                }
                catch (RequestFinishedException ignored) {
                }
                catch (ApiRequestException error) {
                    finishError(error.code, error.getMessage());
                }
                catch (JSONException | IllegalArgumentException error) {
                    finishError(GameApiContract.ERROR_INVALID_ARGUMENT, error.getMessage());
                }
                catch (IOException error) {
                    finishError(GameApiContract.ERROR_STORAGE_FAILED, error.getMessage());
                }
            });
            executor.shutdown();
        });
        return true;
    }

    private void handleLaunchGame() throws JSONException, IOException {
        if (!RootFS.find(this).isValid()) {
            finishError(GameApiContract.ERROR_ROOTFS_NOT_READY, "Open Winlator once and finish its initial setup.");
            return;
        }

        ManagedGame game = requireGame();
        if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(game.containerPolicy)
                && !requireAgmBootstrapReady()) return;
        ContainerManager manager = new ContainerManager(this);
        Container container = manager.getContainerById(game.containerId);
        if (container == null) {
            finishError(GameApiContract.ERROR_CONTAINER_NOT_FOUND, "The managed game's container no longer exists.");
            return;
        }
        if (!hasConfiguredExecutable(game)) {
            finishError(GameApiContract.ERROR_EXECUTABLE_NOT_CONFIGURED, "Configure the installed game's executable before launching it.");
            return;
        }
        if (!reserveLaunch()) {
            finishError(GameApiContract.ERROR_WINLATOR_BUSY, "Close the currently running Winlator session first.");
            return;
        }
        synchronized (ContainerOperationLock.LOCK) {
            prepareContainerForGame(container, game, null);
        }
        if (!isExecutableAccessible(game, container)) {
            finishError(GameApiContract.ERROR_PATH_NOT_ACCESSIBLE, "The configured executable does not exist or is not accessible.");
            return;
        }
        JSONObject config = GameApiJson.managedConfig(game, container);
        mutationInProgress = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                File overlaidExecutable =
                        UnityLaunchOverlay.prepare(this, game, container, config);
                runOnUiThread(() -> {
                    mutationInProgress = false;
                    try {
                        finishSuccess(
                                gameResult(game),
                                GameSessionEventReporter.configure(
                                        buildLaunchIntent(game, overlaidExecutable),
                                        game,
                                        false,
                                        game.state
                                )
                        );
                    }
                    catch (JSONException | IllegalArgumentException error) {
                        finishError(
                                GameApiContract.ERROR_INVALID_ARGUMENT,
                                error.getMessage()
                        );
                    }
                    catch (IOException error) {
                        finishError(
                                GameApiContract.ERROR_STORAGE_FAILED,
                                error.getMessage()
                        );
                    }
                });
            }
            catch (RuntimeException error) {
                runOnUiThread(() -> {
                    mutationInProgress = false;
                    finishError(
                            GameApiContract.ERROR_LAUNCH_FAILED,
                            error.getMessage()
                    );
                });
            }
            finally {
                executor.shutdown();
            }
        });
    }

    private void handleRunInstaller() throws JSONException, IOException {
        if (!RootFS.find(this).isValid()) {
            finishError(GameApiContract.ERROR_ROOTFS_NOT_READY, "Open Winlator once and finish its initial setup.");
            return;
        }

        ManagedGame game = requireGame();
        if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(game.containerPolicy)
                && !requireAgmBootstrapReady()) return;
        ContainerManager manager = new ContainerManager(this);
        Container container = manager.getContainerById(game.containerId);
        if (container == null) {
            finishError(GameApiContract.ERROR_CONTAINER_NOT_FOUND, "The managed game's container no longer exists.");
            return;
        }
        if (!reserveLaunch()) {
            finishError(GameApiContract.ERROR_WINLATOR_BUSY, "Close the currently running Winlator session first.");
            return;
        }

        String installerPath = requireFile(
                requiredString(getIntent(), GameApiContract.EXTRA_INSTALLER_PATH),
                "installer_path"
        );
        String installerArguments = optionalString(getIntent(), GameApiContract.EXTRA_INSTALLER_ARGUMENTS);
        String previousState = game.state;
        boolean asyncInstaller = getIntent().getBooleanExtra(
                GameApiContract.EXTRA_ASYNC_INSTALLER,
                false
        );
        synchronized (ContainerOperationLock.LOCK) {
            prepareContainerForGame(container, game, installerPath);
            if (asyncInstaller) {
                game.state = "installing";
                game.updatedAt = System.currentTimeMillis();
                store.put(game);
            }
        }

        Intent installerIntent = buildLaunchIntent(
                container.id,
                installerPath,
                null,
                installerArguments
        );
        GameApiJson.applyLaunchConfig(this, installerIntent, game);
        Intent launchIntent = GameSessionEventReporter.configure(
                installerIntent,
                game,
                true,
                previousState
        );
        GameSessionEventReporter.sendInstallerProgress(this, launchIntent, "launching");
        if (asyncInstaller) {
            finishSuccessBeforeLaunch(gameResult(game), launchIntent);
        }
        else {
            finishSuccess(gameResult(game), launchIntent);
        }
    }

    private void handleDeleteGame() throws JSONException, IOException {
        ManagedGame game = requireGame();
        if (isLaunchBusy()) {
            finishError(GameApiContract.ERROR_WINLATOR_BUSY, "Close the currently running Winlator session first.");
            return;
        }
        mutationInProgress = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            DeleteOutcome outcome;
            synchronized (ContainerOperationLock.LOCK) {
                outcome = deleteManagedGame(game);
            }
            runOnUiThread(() -> {
                mutationInProgress = false;
                if (outcome.errorCode != null) {
                    finishError(outcome.errorCode, outcome.errorMessage);
                    return;
                }
                try {
                    Intent result = gameResult(game);
                    result.putExtra(
                            GameApiContract.EXTRA_CONTAINER_DELETED,
                            outcome.containerDeleted
                    );
                    result.putExtra(
                            GameApiContract.EXTRA_CONTAINER_PRESERVED,
                            !outcome.containerDeleted
                    );
                    result.putExtra(
                            GameApiContract.EXTRA_CONTAINER_REFERENCE_COUNT,
                            outcome.remainingReferences
                    );
                    finishSuccess(result);
                }
                catch (JSONException | IOException e) {
                    finishError(GameApiContract.ERROR_STORAGE_FAILED, e.getMessage());
                }
            });
        });
    }

    private void handleMoveGameToIsolated() throws JSONException, IOException {
        ManagedGame game = requireGame();
        if (!ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(game.containerPolicy)) {
            throw new IllegalArgumentException("The selected game already uses an isolated container.");
        }
        if (isLaunchBusy()) {
            finishError(
                    GameApiContract.ERROR_WINLATOR_BUSY,
                    "Close the currently running Winlator session before cloning a container."
            );
            return;
        }

        mutationInProgress = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            MoveOutcome outcome;
            synchronized (ContainerOperationLock.LOCK) {
                outcome = moveGameToIsolated(game);
            }
            runOnUiThread(() -> {
                mutationInProgress = false;
                if (outcome.errorCode != null) {
                    finishError(outcome.errorCode, outcome.errorMessage);
                    return;
                }
                try {
                    finishSuccess(gameResult(game));
                }
                catch (JSONException | IOException e) {
                    finishError(GameApiContract.ERROR_STORAGE_FAILED, e.getMessage());
                }
            });
        });
    }

    private DeleteOutcome deleteManagedGame(ManagedGame game) {
        try {
            ContainerManager manager = new ContainerManager(this);
            Container container = manager.getContainerById(game.containerId);
            ManagedGameStore.ContainerUsage usage = store.getContainerUsage(game.containerId);
            boolean deleteContainer = ManagedContainerRules.shouldDeleteContainer(
                    game,
                    usage
            );

            store.remove(game.id);
            int remainingReferences = Math.max(0, usage.referenceCount - 1);
            if (container == null || !deleteContainer) {
                return DeleteOutcome.success(false, remainingReferences);
            }

            if (manager.removeContainer(container)) {
                return DeleteOutcome.success(true, remainingReferences);
            }

            try {
                store.put(game);
            }
            catch (JSONException | IOException rollbackError) {
                return DeleteOutcome.error(
                        GameApiContract.ERROR_STORAGE_FAILED,
                        "Container deletion failed and the managed-game record could not be restored."
                );
            }
            return DeleteOutcome.error(
                    GameApiContract.ERROR_DELETE_FAILED,
                    "Winlator could not remove the isolated game container; the game record was restored."
            );
        }
        catch (JSONException | IOException e) {
            return DeleteOutcome.error(
                    GameApiContract.ERROR_STORAGE_FAILED,
                    e.getMessage()
            );
        }
    }

    private MoveOutcome moveGameToIsolated(ManagedGame game) {
        ContainerManager manager = new ContainerManager(this);
        Container source = manager.getContainerById(game.containerId);
        if (source == null) {
            return MoveOutcome.error(
                    GameApiContract.ERROR_CONTAINER_NOT_FOUND,
                    "The managed game's shared container no longer exists."
            );
        }

        ContainerManager.CloneResult cloneResult = manager.cloneContainer(
                source,
                game.title
        );
        if (cloneResult.container == null) {
            if ("insufficient_storage".equals(cloneResult.error)) {
                return MoveOutcome.error(
                        GameApiContract.ERROR_INSUFFICIENT_STORAGE,
                        "Cloning requires "+cloneResult.requiredBytes+
                                " bytes, but only "+cloneResult.availableBytes+" bytes are available."
                );
            }
            if ("size_unavailable".equals(cloneResult.error)) {
                return MoveOutcome.error(
                        GameApiContract.ERROR_SIZE_UNAVAILABLE,
                        "Winlator could not safely calculate the source container size."
                );
            }
            if ("source_missing".equals(cloneResult.error)) {
                return MoveOutcome.error(
                        GameApiContract.ERROR_CONTAINER_NOT_FOUND,
                        "The managed game's shared container no longer exists."
                );
            }
            return MoveOutcome.error(
                    GameApiContract.ERROR_CLONE_FAILED,
                    "Winlator could not clone the shared container."
            );
        }

        try {
            prepareContainerForGame(cloneResult.container, game, null);
            ManagedContainerRules.reassignToIsolated(
                    game,
                    cloneResult.container.id,
                    System.currentTimeMillis(),
                    store::put
            );
            return MoveOutcome.success();
        }
        catch (JSONException | IOException e) {
            if (!manager.removeContainer(cloneResult.container)) {
                return MoveOutcome.error(
                        GameApiContract.ERROR_STORAGE_FAILED,
                        "Game reassignment failed and Winlator could not remove the staged clone."
                );
            }
            return MoveOutcome.error(
                    GameApiContract.ERROR_STORAGE_FAILED,
                    "Game reassignment failed; the staged clone was rolled back."
            );
        }
    }

    private ManagedGame requireGame() throws JSONException, IOException {
        String gameId = requiredString(getIntent(), GameApiContract.EXTRA_GAME_ID);
        ManagedGame game = store.get(gameId);
        if (game == null) {
            finishError(GameApiContract.ERROR_GAME_NOT_FOUND, "No managed game uses id "+gameId+".");
            throw new RequestFinishedException();
        }
        return game;
    }

    private JSONObject configFromContainerData(JSONObject containerData) throws JSONException {
        JSONObject config = new JSONObject();
        for (String field : GameApiJson.CONFIG_FIELDS) {
            if (containerData.has(field)) config.put(field, containerData.get(field));
        }
        return config;
    }

    private void prepareContainerForGame(
            Container container,
            ManagedGame game,
            String installerPath
    ) throws JSONException, IOException {
        if (game.configJson != null) {
            GameApiJson.applyContainerConfig(container, new JSONObject(game.configJson));
        }
        container.setDrives(buildDrives(
                game.gamePath,
                game.executablePath,
                installerPath
        ));
        if (!container.saveData()) {
            throw new IOException("Unable to persist the selected game container configuration.");
        }
    }

    private String sharedContainerName(String containerKey) {
        return "AGM Shared ("+containerKey+")";
    }

    private JSONObject parseConfig(Intent request) throws JSONException {
        String text = optionalString(request, GameApiContract.EXTRA_CONFIG_JSON);
        JSONObject config = text != null ? new JSONObject(text) : new JSONObject();
        GameConfigSchema.validateLegacyPatch(this, config);
        return config;
    }

    private JSONObject parseConfigUpdate(Intent request) throws JSONException {
        String text = optionalString(request, GameApiContract.EXTRA_CONFIG_UPDATE_JSON);
        return text != null
                ? GameConfigSchema.validateUpdate(this, new JSONObject(text))
                : null;
    }

    private JSONObject parseInitialSettings(Intent request) throws JSONException {
        String text = optionalString(request, GameApiContract.EXTRA_SETTINGS_JSON);
        JSONObject defaults = GameSettingsSchema.defaults(this);
        if (text == null) return defaults;
        JSONObject patch = new JSONObject(text);
        JSONObject syntheticUpdate = new JSONObject()
                .put("baseSettingsSha256", GameSettingsSchema.hash(defaults))
                .put("set", patch);
        GameSettingsSchema.validateUpdate(this, syntheticUpdate);
        return GameSettingsSchema.applyUpdate(this, defaults, patch);
    }

    private JSONObject parseSettingsUpdate(Intent request) throws JSONException {
        String text = optionalString(request, GameApiContract.EXTRA_SETTINGS_UPDATE_JSON);
        return text != null
                ? GameSettingsSchema.validateUpdate(this, new JSONObject(text))
                : null;
    }

    private Intent buildLaunchIntent(
            ManagedGame game,
            File overlaidExecutable
    ) throws JSONException {
        Intent intent = buildLaunchIntent(game.containerId, null, null, game.arguments);
        ManagedGameRuntimeOperations.applyExecutableExtras(
                intent,
                game,
                overlaidExecutable
        );
        GameApiJson.applyLaunchConfig(this, intent, game);
        return intent;
    }

    private Intent buildLaunchIntent(int containerId, String executablePath, String executableDosPath, String arguments) {
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", containerId);
        if (ownedLaunchToken != null) {
            intent.putExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN, ownedLaunchToken);
        }
        if (executablePath != null) intent.putExtra("exec_path", executablePath);
        if (executableDosPath != null) intent.putExtra("exec_dos_path", executableDosPath);
        if (arguments != null && !arguments.isEmpty()) intent.putExtra("exec_args", arguments);
        return intent;
    }

    private boolean isExecutableAccessible(ManagedGame game, Container container) {
        if (game.executablePath != null) {
            return new File(game.executablePath).isFile() && isUnixPathMapped(game.executablePath, container);
        }
        String unixPath = WineUtils.dosToUnixPath(game.executableDosPath, container);
        return !unixPath.isEmpty() && new File(unixPath).isFile();
    }

    private boolean isUnixPathMapped(String path, Container container) {
        return WineUtils.unixToDOSPath(path, container).matches("(?i)^[a-z]:.*");
    }

    private String buildDrives(String driveRoot) {
        return "D:"+driveRoot+"E:"+AppUtils.getInternalStorage(this);
    }

    private String buildDrives(String gamePath, String executablePath, String installerPath) {
        String primaryRoot = gamePath;
        if (primaryRoot == null && executablePath != null) primaryRoot = new File(executablePath).getParent();
        if (primaryRoot == null && installerPath != null) primaryRoot = new File(installerPath).getParent();
        if (primaryRoot == null) primaryRoot = AppUtils.DIRECTORY_DOWNLOADS;

        String drives = buildDrives(primaryRoot);
        char nextLetter = 'F';
        if (executablePath != null && !isPathWithin(executablePath, primaryRoot)) {
            String executableRoot = new File(executablePath).getParent();
            if (executableRoot != null) {
                drives += nextLetter+":"+executableRoot;
                nextLetter++;
            }
        }
        if (installerPath != null) {
            String installerRoot = new File(installerPath).getParent();
            boolean mappedByPrimary = isPathWithin(installerPath, primaryRoot);
            boolean mappedByExecutable = executablePath != null &&
                    isPathWithin(installerPath, new File(executablePath).getParent());
            if (installerRoot != null && !mappedByPrimary && !mappedByExecutable) {
                drives += nextLetter+":"+installerRoot;
            }
        }
        return drives;
    }

    private boolean isPathWithin(String path, String directory) {
        String normalizedPath = new File(path).getAbsolutePath();
        String normalizedDirectory = new File(directory).getAbsolutePath();
        return normalizedPath.equals(normalizedDirectory) ||
                normalizedPath.startsWith(normalizedDirectory+File.separator);
    }

    private boolean hasConfiguredExecutable(ManagedGame game) {
        return game.executablePath != null || game.executableDosPath != null;
    }

    private void validateExecutableChoice(String executablePath, String executableDosPath) {
        if (executablePath != null && executableDosPath != null) {
            throw new IllegalArgumentException("Provide executable_path or executable_dos_path, not both.");
        }
    }

    private void validateDosPath(String path) {
        if (!path.matches("(?i)^[a-z]:[\\\\/].+")) {
            throw new IllegalArgumentException("executable_dos_path must be an absolute DOS path.");
        }
    }

    private void validateGameId(String gameId) {
        if (!gameId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("game_id may contain only letters, digits, dot, underscore, and hyphen.");
        }
    }

    private void validateContainerPolicy(String policy) {
        if (!ManagedGame.CONTAINER_POLICY_ISOLATED.equals(policy) &&
                !ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(policy)) {
            throw new IllegalArgumentException(
                    "container_policy must be isolated or shared_default."
            );
        }
    }

    private boolean requireAgmBootstrapReady() {
        if (RuntimeDependencyManager.isAgmBootstrapReady(this)) return true;
        finishError(
                GameApiContract.ERROR_RUNTIME_PREREQUISITES_REQUIRED,
                "Open Winlator and complete Windows prerequisite setup."
        );
        return false;
    }

    private void validateContainerKey(String containerKey) {
        if (!ManagedGame.DEFAULT_SHARED_CONTAINER_KEY.equals(containerKey)) {
            throw new IllegalArgumentException(
                    "Public builds support only container_key=agm.default."
            );
        }
    }

    private String requireDirectory(String path, String field) {
        String normalizedPath = normalizeProfilePath(path, field);
        if (!new File(normalizedPath).isDirectory()) {
            throw new IllegalArgumentException(field+" must be an existing absolute directory.");
        }
        return normalizedPath;
    }

    private String requireFile(String path, String field) {
        String normalizedPath = normalizeProfilePath(path, field);
        if (!new File(normalizedPath).isFile()) {
            throw new IllegalArgumentException(field+" must be an existing absolute file.");
        }
        return normalizedPath;
    }

    private String normalizeProfilePath(String path, String field) {
        File file = new File(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException(field+" must be an absolute path.");
        }
        validateCurrentProfilePath(file, field);
        return file.getAbsolutePath();
    }

    private void validateCurrentProfilePath(File file, String field) {
        try {
            String canonicalPath = file.getCanonicalPath();
            String emulatedPrefix = File.separator+"storage"+File.separator+"emulated"+File.separator;
            if (!canonicalPath.startsWith(emulatedPrefix)) return;

            String profileRoot = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (!canonicalPath.equals(profileRoot) &&
                    !canonicalPath.startsWith(profileRoot+File.separator)) {
                throw new ApiRequestException(
                        GameApiContract.ERROR_PATH_OUTSIDE_PROFILE,
                        field+" points to another Android user's emulated storage."
                );
            }
        }
        catch (IOException e) {
            throw new ApiRequestException(
                    GameApiContract.ERROR_PATH_NOT_ACCESSIBLE,
                    field+" could not be resolved in the current Android profile."
            );
        }
    }

    private String requiredString(Intent intent, String name) {
        String value = optionalString(intent, name);
        if (value == null) throw new IllegalArgumentException("Missing required extra: "+name);
        return value;
    }

    private String optionalString(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private String rawString(Intent intent, String name) {
        Bundle extras = intent.getExtras();
        Object value = extras != null ? extras.get(name) : null;
        if (value == null) return null;
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(name+" must be a String.");
        }
        return (String)value;
    }

    private void validateAgmMetadata(String metadata) {
        if (metadata != null &&
                metadata.getBytes(StandardCharsets.UTF_8).length > MAX_AGM_METADATA_BYTES) {
            throw new ApiRequestException(
                    GameApiContract.ERROR_METADATA_TOO_LARGE,
                    "agm_metadata must not exceed "+MAX_AGM_METADATA_BYTES+" UTF-8 bytes."
            );
        }
    }

    private Intent successResult() {
        Intent result = new Intent();
        result.putExtra(GameApiContract.EXTRA_SUCCESS, true);
        result.putExtra(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        return result;
    }

    private Intent gameResult(ManagedGame game) throws JSONException, IOException {
        Intent result = successResult();
        result.putExtra(GameApiContract.EXTRA_GAME_ID, game.id);
        result.putExtra(GameApiContract.EXTRA_CONTAINER_ID, game.containerId);
        result.putExtra(
                GameApiContract.EXTRA_GAME_JSON,
                GameApiJson.gameWithoutAllocatedSize(this, game).toString()
        );
        return result;
    }

    private void finishSuccess(Intent result) {
        finishSuccess(result, null);
    }

    private void finishSuccess(Intent result, Intent launchIntent) {
        setResult(RESULT_OK, result);
        releaseMutation();
        if (launchIntent != null) {
            try {
                startActivity(launchIntent);
            }
            catch (RuntimeException e) {
                GameSessionEventReporter.failToStart(
                        this,
                        launchIntent,
                        GameApiContract.ERROR_LAUNCH_FAILED,
                        "Winlator could not start the requested Wine session."
                );
                finishError(
                        GameApiContract.ERROR_LAUNCH_FAILED,
                        "Winlator could not start the requested Wine session."
                );
                return;
            }
        }
        else {
            releaseLaunch();
        }
        finish();
    }

    private void finishSuccessBeforeLaunch(Intent result, Intent launchIntent) {
        setResult(RESULT_OK, result);
        releaseMutation();
        Intent deferredLaunch = new Intent(launchIntent);
        GameSessionEventReporter.sendInstallerProgress(this, deferredLaunch, "launch_scheduled");
        try {
            DeferredGameLaunchService.enqueue(this, deferredLaunch);
        }
        catch (RuntimeException e) {
            releaseLaunch();
            GameSessionEventReporter.failToStart(
                    this,
                    deferredLaunch,
                    GameApiContract.ERROR_LAUNCH_FAILED,
                    "Winlator could not schedule the requested Wine session."
            );
            finishError(
                    GameApiContract.ERROR_LAUNCH_FAILED,
                    "Winlator could not schedule the requested Wine session."
            );
            return;
        }
        finish();
    }

    private void finishError(String code, String message) {
        Intent result = new Intent();
        result.putExtra(GameApiContract.EXTRA_SUCCESS, false);
        result.putExtra(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        result.putExtra(GameApiContract.EXTRA_ERROR_CODE, code);
        result.putExtra(GameApiContract.EXTRA_ERROR_MESSAGE, message != null ? message : code);
        setResult(RESULT_CANCELED, result);
        releaseMutation();
        releaseLaunch();
        finish();
    }

    private void finishConfigConflict(ManagedGame game) throws JSONException, IOException {
        finishGameError(
                GameApiContract.ERROR_CONFIG_CONFLICT,
                "The game configuration changed. Refresh it before applying this update.",
                game
        );
    }

    private void finishSettingsConflict(ManagedGame game)
            throws JSONException, IOException {
        finishGameError(
                GameApiContract.ERROR_SETTINGS_CONFLICT,
                "The game settings changed. Refresh them before applying this update.",
                game
        );
    }

    private void finishGameError(String code, String message, ManagedGame game)
            throws JSONException, IOException {
        Intent result = new Intent();
        result.putExtra(GameApiContract.EXTRA_SUCCESS, false);
        result.putExtra(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        result.putExtra(GameApiContract.EXTRA_ERROR_CODE, code);
        result.putExtra(GameApiContract.EXTRA_ERROR_MESSAGE, message);
        result.putExtra(
                GameApiContract.EXTRA_GAME_JSON,
                GameApiJson.gameWithoutAllocatedSize(this, game).toString()
        );
        setResult(RESULT_CANCELED, result);
        releaseMutation();
        releaseLaunch();
        finish();
    }

    private boolean isAsynchronousMutation(String action) {
        return GameApiContract.ACTION_CREATE_GAME.equals(action) ||
                GameApiContract.ACTION_CONFIGURE_GAME.equals(action) ||
                GameApiContract.ACTION_RUN_INSTALLER.equals(action) ||
                GameApiContract.ACTION_LAUNCH_GAME.equals(action) ||
                GameApiContract.ACTION_DELETE_GAME.equals(action) ||
                GameApiContract.ACTION_MOVE_GAME_TO_ISOLATED.equals(action) ||
                GameApiContract.ACTION_CREATE_SNAPSHOT.equals(action) ||
                GameApiContract.ACTION_CLEAR_TRANSLATION_CACHE.equals(action) ||
                GameApiContract.ACTION_CONFIGURE_GLOBAL_SETTINGS.equals(action);
    }

    private boolean reserveMutation() {
        synchronized (RESERVATION_LOCK) {
            if (mutationReserved) return false;
            mutationReserved = true;
            reservedMutationToken = null;
            ownsMutationReservation = true;
            return true;
        }
    }

    private void releaseMutation() {
        synchronized (RESERVATION_LOCK) {
            if (!ownsMutationReservation) return;
            mutationReserved = false;
            reservedMutationToken = null;
            ownsMutationReservation = false;
        }
    }

    private boolean reserveLaunch() {
        synchronized (RESERVATION_LOCK) {
            if ((mutationReserved && !ownsMutationReservation) ||
                    launchReserved ||
                    sessionClaimed ||
                    XServerDisplayActivity.isSessionActive()) return false;
            launchReserved = true;
            ownsLaunchReservation = true;
            ownedLaunchToken = UUID.randomUUID().toString();
            reservedLaunchToken = ownedLaunchToken;
            return true;
        }
    }

    private void releaseLaunch() {
        synchronized (RESERVATION_LOCK) {
            if (!ownsLaunchReservation) return;
            if (ownedLaunchToken != null && ownedLaunchToken.equals(reservedLaunchToken)) {
                launchReserved = false;
                reservedLaunchToken = null;
            }
            ownedLaunchToken = null;
            ownsLaunchReservation = false;
        }
    }

    private boolean isLaunchBusy() {
        synchronized (RESERVATION_LOCK) {
            return launchReserved || sessionClaimed || XServerDisplayActivity.isSessionActive();
        }
    }

    static JSONObject resolveConfigSetForPerformancePreset(
            JSONObject configSet,
            String preset
    ) throws JSONException {
        JSONObject presetPatch = GameSettingsSchema.performancePatch(preset);
        if (presetPatch.length() == 0) return configSet;
        if (configSet.length() > 0) {
            throw new IllegalArgumentException(
                    "Do not combine an explicit runtime config update "
                            + "with a non-custom performance preset."
            );
        }
        return presetPatch;
    }

    public static boolean claimSessionStart(String token) {
        synchronized (RESERVATION_LOCK) {
            if (sessionClaimed) {
                if (activeSessionToken == null) return token == null;
                return activeSessionToken.equals(token);
            }
            if (mutationReserved) return false;

            if (launchReserved) {
                if (token == null || !token.equals(reservedLaunchToken)) return false;
                launchReserved = false;
                reservedLaunchToken = null;
            }
            else if (token != null) {
                return false;
            }

            sessionClaimed = true;
            activeSessionToken = token;
            return true;
        }
    }

    /**
     * Reserves a mutation slot for an internal trusted caller (e.g. ManagedGameRuntimeOperations).
     * Returns a token, or {@code null} if Winlator is currently busy.
     */
    public static String reserveInternalMutation() {
        synchronized (RESERVATION_LOCK) {
            if (mutationReserved || launchReserved || sessionClaimed ||
                    XServerDisplayActivity.isSessionActive()) {
                return null;
            }
            String token = UUID.randomUUID().toString();
            mutationReserved = true;
            reservedMutationToken = token;
            return token;
        }
    }

    /**
     * Releases a mutation slot previously reserved by {@link #reserveInternalMutation()}.
     */
    public static void releaseInternalMutation(String token) {
        synchronized (RESERVATION_LOCK) {
            if (token != null && token.equals(reservedMutationToken)) {
                mutationReserved = false;
                reservedMutationToken = null;
            }
        }
    }

    /**
     * Reserves a launch slot for an internal trusted caller (e.g. RuntimeDependencyActivity).
     * Returns a token to pass to XServerDisplayActivity via
     * {@link com.winlator.api.GameApiContract#INTERNAL_EXTRA_SESSION_TOKEN}, or
     * {@code null} if Winlator is currently busy.
     */
    public static String reserveInternalLaunch() {
        synchronized (RESERVATION_LOCK) {
            if (mutationReserved || launchReserved || sessionClaimed ||
                    XServerDisplayActivity.isSessionActive()) {
                return null;
            }
            String token = UUID.randomUUID().toString();
            launchReserved = true;
            reservedLaunchToken = token;
            return token;
        }
    }

    /**
     * Releases a launch slot previously reserved by {@link #reserveInternalLaunch()}.
     * Call this if the launch is cancelled before the session starts.
     */
    public static void releaseInternalLaunch(String token) {
        synchronized (RESERVATION_LOCK) {
            if (token != null && token.equals(reservedLaunchToken)) {
                launchReserved = false;
                reservedLaunchToken = null;
            }
        }
    }

    public static void notifySessionLaunchFailed() {
        synchronized (RESERVATION_LOCK) {
            launchReserved = false;
            reservedLaunchToken = null;
        }
    }

    public static boolean notifySessionLaunchFailed(String token) {
        synchronized (RESERVATION_LOCK) {
            if (!launchReserved || token == null || !token.equals(reservedLaunchToken)) {
                return false;
            }
            launchReserved = false;
            reservedLaunchToken = null;
            return true;
        }
    }

    public static boolean isLaunchReservationPending(String token) {
        synchronized (RESERVATION_LOCK) {
            return launchReserved && token != null && token.equals(reservedLaunchToken);
        }
    }

    public static void notifySessionEnded(String token) {
        synchronized (RESERVATION_LOCK) {
            boolean matches = activeSessionToken == null
                    ? token == null
                    : activeSessionToken.equals(token);
            if (!sessionClaimed || !matches) return;
            sessionClaimed = false;
            activeSessionToken = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (!mutationInProgress) super.onBackPressed();
    }

    private static final class RequestFinishedException extends IllegalArgumentException {
        RequestFinishedException() {
            super("Request already completed.");
        }
    }

    private static final class ApiRequestException extends RuntimeException {
        final String code;

        ApiRequestException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class CreateOutcome {
        final ManagedGame game;
        final int containerId;
        final boolean containerCreated;
        final boolean reconciled;
        final String settledState;
        final String errorCode;
        final String errorMessage;

        private CreateOutcome(
                ManagedGame game,
                int containerId,
                boolean containerCreated,
                boolean reconciled,
                String settledState,
                String errorCode,
                String errorMessage
        ) {
            this.game = game;
            this.containerId = containerId;
            this.containerCreated = containerCreated;
            this.reconciled = reconciled;
            this.settledState = settledState;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static CreateOutcome success(
                ManagedGame game,
                int containerId,
                boolean containerCreated,
                String settledState
        ) {
            return new CreateOutcome(
                    game,
                    containerId,
                    containerCreated,
                    false,
                    settledState,
                    null,
                    null
            );
        }

        static CreateOutcome reconciled(ManagedGame game) {
            String settledState = game.executablePath != null || game.executableDosPath != null
                    ? "ready"
                    : "setup_required";
            return new CreateOutcome(
                    game,
                    game.containerId,
                    false,
                    true,
                    settledState,
                    null,
                    null
            );
        }

        static CreateOutcome conflict(ManagedGame game) {
            return new CreateOutcome(
                    game,
                    game.containerId,
                    false,
                    false,
                    null,
                    GameApiContract.ERROR_GAME_ALREADY_EXISTS,
                    "A different managed game already uses id " + game.id + "."
            );
        }

        static CreateOutcome error(String code, String message) {
            return new CreateOutcome(null, 0, false, false, null, code, message);
        }
    }

    private static final class DeleteOutcome {
        final boolean containerDeleted;
        final int remainingReferences;
        final String errorCode;
        final String errorMessage;

        private DeleteOutcome(
                boolean containerDeleted,
                int remainingReferences,
                String errorCode,
                String errorMessage
        ) {
            this.containerDeleted = containerDeleted;
            this.remainingReferences = remainingReferences;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static DeleteOutcome success(boolean containerDeleted, int remainingReferences) {
            return new DeleteOutcome(
                    containerDeleted,
                    remainingReferences,
                    null,
                    null
            );
        }

        static DeleteOutcome error(String code, String message) {
            return new DeleteOutcome(false, 0, code, message);
        }
    }

    private static final class MoveOutcome {
        final String errorCode;
        final String errorMessage;

        private MoveOutcome(String errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static MoveOutcome success() {
            return new MoveOutcome(null, null);
        }

        static MoveOutcome error(String code, String message) {
            return new MoveOutcome(code, message);
        }
    }
}
