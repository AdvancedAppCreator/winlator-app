package com.winlator.api;

import android.content.Context;
import android.content.Intent;

import com.winlator.api.dependency.RuntimeDependencyManager;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public final class GameSessionEventReporter {
    private static final String INTERNAL_MANAGED_SESSION =
            "com.winlator.secure.internal.MANAGED_SESSION";
    private static final String INTERNAL_INSTALLER_SESSION =
            "com.winlator.secure.internal.INSTALLER_SESSION";
    private static final String INTERNAL_PREVIOUS_STATE =
            "com.winlator.secure.internal.PREVIOUS_STATE";
    private static final String INTERNAL_DEPENDENCY_SESSION =
            "com.winlator.secure.internal.DEPENDENCY_SESSION";

    private GameSessionEventReporter() {
    }

    static Intent configure(
            Intent intent,
            ManagedGame game,
            boolean installerSession,
            String previousState
    ) {
        intent.putExtra(INTERNAL_MANAGED_SESSION, true);
        intent.putExtra(INTERNAL_INSTALLER_SESSION, installerSession);
        intent.putExtra(INTERNAL_PREVIOUS_STATE, previousState);
        intent.putExtra(GameApiContract.EXTRA_GAME_ID, game.id);
        intent.putExtra(GameApiContract.EXTRA_CONTAINER_ID, game.containerId);
        intent.putExtra(GameApiContract.EXTRA_STARTED_AT, System.currentTimeMillis());
        return intent;
    }

    public static Intent configureDependency(
            Intent intent,
            int containerId,
            String gameId,
            String dependencyId
    ) {
        intent.putExtra(INTERNAL_MANAGED_SESSION, true);
        intent.putExtra(INTERNAL_DEPENDENCY_SESSION, true);
        intent.putExtra(GameApiContract.EXTRA_CONTAINER_ID, containerId);
        if (gameId != null) intent.putExtra(GameApiContract.EXTRA_GAME_ID, gameId);
        intent.putExtra(GameApiContract.INTERNAL_EXTRA_DEPENDENCY_ID, dependencyId);
        intent.putExtra(GameApiContract.EXTRA_STARTED_AT, System.currentTimeMillis());
        return intent;
    }

    public static boolean isManagedSession(Intent intent) {
        return intent != null && intent.getBooleanExtra(INTERNAL_MANAGED_SESSION, false);
    }

    public static boolean isInstallerSession(Intent intent) {
        return isManagedSession(intent) &&
                intent.getBooleanExtra(INTERNAL_INSTALLER_SESSION, false);
    }

    public static boolean isDependencySession(Intent intent) {
        return isManagedSession(intent) &&
                intent.getBooleanExtra(INTERNAL_DEPENDENCY_SESSION, false);
    }

    static String getPreviousState(Intent intent) {
        return intent != null ? intent.getStringExtra(INTERNAL_PREVIOUS_STATE) : null;
    }

    public static void sendInstallerProgress(Context context, Intent sessionIntent, String stage) {
        if (!isInstallerSession(sessionIntent)) return;
        Intent event = baseEvent(context, sessionIntent, GameApiContract.EVENT_INSTALL_PROGRESS);
        event.putExtra(GameApiContract.EXTRA_SUCCESS, true);
        if (stage != null && !stage.isEmpty()) {
            event.putExtra(GameApiContract.EXTRA_STAGE, stage);
        }
        ApiEventDispatcher.send(context, event, ApiScope.READ);
    }

    public static boolean complete(Context context, Intent sessionIntent, int processStatus) {
        return complete(context, sessionIntent, processStatus, null);
    }

    public static boolean complete(
            Context context,
            Intent sessionIntent,
            int processStatus,
            JSONObject diagnosticReport
    ) {
        if (!isManagedSession(sessionIntent)) return false;
        if (isDependencySession(sessionIntent)) {
            sendDependencyCompletion(
                    context,
                    sessionIntent,
                    processStatus == 0,
                    processStatus == 0
                            ? "Installer completed successfully."
                            : "Installer exited with status " + processStatus + ".",
                    diagnosticReport
            );
            return true;
        }

        if (!isInstallerSession(sessionIntent)) {
            boolean success = diagnosticSuccess(diagnosticReport, processStatus == 0);
            sendGameCompletion(
                    context,
                    sessionIntent,
                    success,
                    success ? null : GameApiContract.ERROR_LAUNCH_FAILED,
                    success
                            ? null
                            : "The game Wine session exited with status " + processStatus + ".",
                    diagnosticReport
            );
            return true;
        }

        boolean success = processStatus == 0;
        String errorCode = success ? null : GameApiContract.ERROR_INSTALL_FAILED;
        String errorMessage = success
                ? null
                : "The installer Wine session exited with status "+processStatus+".";
        sendInstallerCompletion(
                context,
                sessionIntent,
                success,
                errorCode,
                errorMessage,
                diagnosticReport
        );
        return true;
    }

    public static boolean abort(Context context, Intent sessionIntent) {
        return abort(context, sessionIntent, null);
    }

    public static boolean abort(
            Context context,
            Intent sessionIntent,
            JSONObject diagnosticReport
    ) {
        if (!isManagedSession(sessionIntent)) return false;
        if (isDependencySession(sessionIntent)) {
            sendDependencyCompletion(
                    context,
                    sessionIntent,
                    false,
                    "The dependency installer session ended before completion.",
                    diagnosticReport
            );
            return true;
        }

        if (!isInstallerSession(sessionIntent)) {
            boolean success = diagnosticSuccess(diagnosticReport, false);
            sendGameCompletion(
                    context,
                    sessionIntent,
                    success,
                    success ? null : GameApiContract.ERROR_LAUNCH_FAILED,
                    success ? null : diagnosticFailureMessage(diagnosticReport),
                    diagnosticReport
            );
            return true;
        }

        sendInstallerCompletion(
                context,
                sessionIntent,
                false,
                GameApiContract.ERROR_INSTALL_ABORTED,
                "The installer session ended before the Wine process reported completion.",
                diagnosticReport
        );
        return true;
    }

    public static void failToStart(
            Context context,
            Intent sessionIntent,
            String errorCode,
            String errorMessage
    ) {
        failToStart(context, sessionIntent, errorCode, errorMessage, null);
    }

    public static void failToStart(
            Context context,
            Intent sessionIntent,
            String errorCode,
            String errorMessage,
            JSONObject diagnosticReport
    ) {
        if (!isManagedSession(sessionIntent)) return;
        if (isDependencySession(sessionIntent)) {
            sendDependencyCompletion(
                    context,
                    sessionIntent,
                    false,
                    errorMessage,
                    diagnosticReport
            );
        }
        else if (isInstallerSession(sessionIntent)) {
            sendInstallerCompletion(
                    context,
                    sessionIntent,
                    false,
                    errorCode,
                    errorMessage,
                    diagnosticReport
            );
        }
        else {
            sendGameCompletion(
                    context,
                    sessionIntent,
                    false,
                    errorCode,
                    errorMessage,
                    diagnosticReport
            );
        }
    }

    private static void sendGameCompletion(
            Context context,
            Intent sessionIntent,
            boolean success,
            String errorCode,
            String errorMessage,
            JSONObject diagnosticReport
    ) {
        Intent event = baseEvent(context, sessionIntent, GameApiContract.EVENT_GAME_EXITED);
        event.putExtra(GameApiContract.EXTRA_SUCCESS, success);
        event.putExtra(GameApiContract.EXTRA_ENDED_AT, System.currentTimeMillis());
        if (!success) {
            event.putExtra(
                    GameApiContract.EXTRA_ERROR_CODE,
                    errorCode != null ? errorCode : GameApiContract.ERROR_LAUNCH_FAILED
            );
            event.putExtra(
                    GameApiContract.EXTRA_ERROR_MESSAGE,
                    errorMessage != null
                            ? errorMessage
                            : "The managed game session ended before successful completion."
            );
        }
        addDiagnosticSummary(event, diagnosticReport);
        ApiEventDispatcher.send(context, event, ApiScope.READ);
    }

    private static void sendDependencyCompletion(
            Context context,
            Intent sessionIntent,
            boolean success,
            String message,
            JSONObject diagnosticReport
    ) {
        String dependencyId = sessionIntent.getStringExtra(
                GameApiContract.INTERNAL_EXTRA_DEPENDENCY_ID
        );
        int containerId = sessionIntent.getIntExtra(
                GameApiContract.EXTRA_CONTAINER_ID,
                -1
        );
        String errorCode = null;
        String errorMessage = null;
        try {
            RuntimeDependencyManager.markResult(
                    context,
                    containerId,
                    dependencyId,
                    success,
                    message
            );
        }
        catch (JSONException | IOException error) {
            success = false;
            errorCode = GameApiContract.ERROR_STORAGE_FAILED;
            errorMessage = "The installer ended, but dependency status could not be saved.";
        }
        Intent event = baseEvent(
                context,
                sessionIntent,
                success
                        ? GameApiContract.EVENT_DEPENDENCY_INSTALLED
                        : GameApiContract.EVENT_DEPENDENCY_FAILED
        );
        event.putExtra(GameApiContract.EXTRA_SUCCESS, success);
        event.putExtra(
                GameApiContract.EXTRA_DEPENDENCY_ID,
                dependencyId
        );
        event.putExtra(GameApiContract.EXTRA_ENDED_AT, System.currentTimeMillis());
        if (!success) {
            event.putExtra(
                    GameApiContract.EXTRA_ERROR_CODE,
                    errorCode != null ? errorCode : GameApiContract.ERROR_INSTALL_FAILED
            );
            event.putExtra(
                    GameApiContract.EXTRA_ERROR_MESSAGE,
                    errorMessage != null ? errorMessage : message
            );
        }
        addDiagnosticSummary(event, diagnosticReport);
        ApiEventDispatcher.send(context, event, ApiScope.READ);
    }

    private static void sendInstallerCompletion(
            Context context,
            Intent sessionIntent,
            boolean success,
            String errorCode,
            String errorMessage,
            JSONObject diagnosticReport
    ) {
        String eventType = success
                ? GameApiContract.EVENT_INSTALL_COMPLETED
                : GameApiContract.EVENT_INSTALL_FAILED;
        String finalErrorCode = errorCode;
        String finalErrorMessage = errorMessage;

        try {
            updateInstallerState(context, sessionIntent, success);
        }
        catch (JSONException | IOException e) {
            eventType = GameApiContract.EVENT_INSTALL_FAILED;
            success = false;
            finalErrorCode = GameApiContract.ERROR_STORAGE_FAILED;
            finalErrorMessage = "The installer ended, but Winlator could not persist the game state.";
        }

        Intent event = baseEvent(context, sessionIntent, eventType);
        event.putExtra(GameApiContract.EXTRA_SUCCESS, success);
        event.putExtra(GameApiContract.EXTRA_ENDED_AT, System.currentTimeMillis());
        if (!success) {
            event.putExtra(GameApiContract.EXTRA_ERROR_CODE, finalErrorCode);
            event.putExtra(GameApiContract.EXTRA_ERROR_MESSAGE, finalErrorMessage);
        }
        addDiagnosticSummary(event, diagnosticReport);
        ApiEventDispatcher.send(context, event, ApiScope.READ);
    }

    static void sendDiagnosticSummary(Context context, JSONObject report) {
        if (report == null) return;
        if (report.optBoolean("dependencySession", false)) {
            Intent sessionIntent = new Intent();
            sessionIntent.putExtra(
                    GameApiContract.EXTRA_GAME_ID,
                    report.optString("gameId", null)
            );
            sessionIntent.putExtra(
                    GameApiContract.EXTRA_CONTAINER_ID,
                    report.optInt("containerId", -1)
            );
            sessionIntent.putExtra(
                    GameApiContract.EXTRA_STARTED_AT,
                    report.optLong("startedAt", 0)
            );
            sessionIntent.putExtra(
                    GameApiContract.INTERNAL_EXTRA_DEPENDENCY_ID,
                    report.optString("dependencyId", null)
            );
            sendDependencyCompletion(
                    context,
                    sessionIntent,
                    false,
                    "The dependency installer session ended before Wine reported completion.",
                    report
            );
            return;
        }
        Intent event = new Intent(GameApiContract.ACTION_GAME_EVENT);
        boolean installer = report.optBoolean("installerSession", false);
        String outcome = report.optString("outcome", "unknown");
        boolean success = "completed".equals(outcome) || "user_exit".equals(outcome);
        event.putExtra(
                GameApiContract.EXTRA_EVENT_TYPE,
                installer
                        ? GameApiContract.EVENT_INSTALL_FAILED
                        : GameApiContract.EVENT_GAME_EXITED
        );
        event.putExtra(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        event.putExtra(GameApiContract.EXTRA_SUCCESS, success);
        event.putExtra(GameApiContract.EXTRA_GAME_ID, report.optString("gameId", null));
        event.putExtra(GameApiContract.EXTRA_CONTAINER_ID, report.optInt("containerId", 0));
        event.putExtra(GameApiContract.EXTRA_STARTED_AT, report.optLong("startedAt", 0));
        event.putExtra(GameApiContract.EXTRA_ENDED_AT, report.optLong("endedAt", 0));
        if (installer) {
            event.putExtra(GameApiContract.EXTRA_ERROR_CODE, GameApiContract.ERROR_INSTALL_ABORTED);
            event.putExtra(
                    GameApiContract.EXTRA_ERROR_MESSAGE,
                    "The installer session ended before Wine reported completion."
            );
            try {
                restoreInterruptedInstallerState(context, report);
            }
            catch (JSONException | IOException e) {
                event.putExtra(
                        GameApiContract.EXTRA_ERROR_CODE,
                        GameApiContract.ERROR_STORAGE_FAILED
                );
                event.putExtra(
                        GameApiContract.EXTRA_ERROR_MESSAGE,
                        "The interrupted installer was recovered, but its game state could not be restored."
                );
            }
        }
        else if (!success) {
            event.putExtra(
                    GameApiContract.EXTRA_ERROR_CODE,
                    GameApiContract.ERROR_LAUNCH_FAILED
            );
            event.putExtra(
                    GameApiContract.EXTRA_ERROR_MESSAGE,
                    diagnosticFailureMessage(report)
            );
        }
        addDiagnosticSummary(event, report);
        ApiEventDispatcher.send(context, event, ApiScope.READ);
    }

    private static boolean diagnosticSuccess(JSONObject report, boolean fallback) {
        if (report == null) return fallback;
        String outcome = report.optString("outcome", "");
        if ("completed".equals(outcome) || "user_exit".equals(outcome)) return true;
        if ("crashed".equals(outcome) ||
                "killed".equals(outcome) ||
                "launch_failed".equals(outcome)) {
            return false;
        }
        return fallback;
    }

    private static String diagnosticFailureMessage(JSONObject report) {
        if (report == null) {
            return "The managed game session ended before successful completion.";
        }
        String category = report.optString("category", "");
        if ("runtime_locale_generation".equals(category)) {
            return "Winlator could not prepare the configured game locale.";
        }
        if (!report.optBoolean("runtimeReached", false)) {
            return "The managed game failed before reaching runtime.";
        }
        return "The managed game session ended unexpectedly.";
    }

    private static void addDiagnosticSummary(Intent event, JSONObject report) {
        if (report == null) return;
        putString(event, GameApiContract.EXTRA_REPORT_ID, report, "reportId");
        putString(event, GameApiContract.EXTRA_OUTCOME, report, "outcome");
        putString(event, GameApiContract.EXTRA_PHASE, report, "phase");
        putString(event, GameApiContract.EXTRA_CATEGORY, report, "category");
        putString(event, GameApiContract.EXTRA_CONFIDENCE, report, "confidence");
        putString(
                event,
                GameApiContract.EXTRA_TERMINATION_ORIGIN,
                report,
                "terminationOrigin"
        );
        putString(event, GameApiContract.EXTRA_CONFIG_HEALTH, report, "configHealth");
        putString(
                event,
                GameApiContract.EXTRA_APPLIED_CONFIG_SHA256,
                report,
                "appliedConfigSha256"
        );
        event.putExtra(
                GameApiContract.EXTRA_DURATION_MILLIS,
                report.optLong("durationMillis", 0)
        );
        event.putExtra(
                GameApiContract.EXTRA_RUNTIME_REACHED,
                report.optBoolean("runtimeReached", false)
        );
        if (report.has("exitCode")) {
            event.putExtra(GameApiContract.EXTRA_EXIT_CODE, report.optInt("exitCode"));
        }
        if (report.has("signal")) {
            event.putExtra(GameApiContract.EXTRA_SIGNAL, report.optInt("signal"));
        }
    }

    private static void putString(
            Intent event,
            String extra,
            JSONObject report,
            String field
    ) {
        String value = report.optString(field, null);
        if (value != null) event.putExtra(extra, value);
    }

    private static void updateInstallerState(Context context, Intent sessionIntent, boolean success)
            throws JSONException, IOException {
        String gameId = sessionIntent.getStringExtra(GameApiContract.EXTRA_GAME_ID);
        if (gameId == null) return;

        ManagedGameStore store = new ManagedGameStore(context);
        ManagedGame game = store.get(gameId);
        if (game == null) return;

        if (success) {
            game.state = game.executablePath != null || game.executableDosPath != null
                    ? "ready"
                    : "setup_required";
        }
        else {
            String previousState = sessionIntent.getStringExtra(INTERNAL_PREVIOUS_STATE);
            game.state = previousState != null && !previousState.isEmpty()
                    ? previousState
                    : "setup_required";
        }
        game.updatedAt = System.currentTimeMillis();
        store.put(game);
    }

    private static void restoreInterruptedInstallerState(Context context, JSONObject report)
            throws JSONException, IOException {
        String gameId = report.optString("gameId", null);
        if (gameId == null) return;

        ManagedGameStore store = new ManagedGameStore(context);
        ManagedGame game = store.get(gameId);
        if (game == null) return;

        String previousState = report.optString("installerPreviousState", "");
        game.state = !previousState.isEmpty() ? previousState : "setup_required";
        game.updatedAt = System.currentTimeMillis();
        store.put(game);
    }

    private static Intent baseEvent(Context context, Intent sessionIntent, String eventType) {
        Intent event = new Intent(GameApiContract.ACTION_GAME_EVENT);
        event.putExtra(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        event.putExtra(GameApiContract.EXTRA_EVENT_TYPE, eventType);
        event.putExtra(
                GameApiContract.EXTRA_GAME_ID,
                sessionIntent.getStringExtra(GameApiContract.EXTRA_GAME_ID)
        );
        event.putExtra(
                GameApiContract.EXTRA_CONTAINER_ID,
                sessionIntent.getIntExtra(GameApiContract.EXTRA_CONTAINER_ID, 0)
        );
        event.putExtra(
                GameApiContract.EXTRA_STARTED_AT,
                sessionIntent.getLongExtra(
                        GameApiContract.EXTRA_STARTED_AT,
                        System.currentTimeMillis()
                )
        );
        return event;
    }

    static void sendSettingsChanged(
            Context context,
            String gameId,
            String namespace,
            String hash,
            String providerPath
    ) {
        Intent event = new Intent(GameApiContract.ACTION_GAME_EVENT);
        event.putExtra(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        event.putExtra(
                GameApiContract.EXTRA_EVENT_TYPE,
                GameApiContract.EVENT_SETTINGS_CHANGED
        );
        event.putExtra(GameApiContract.EXTRA_SUCCESS, true);
        if (gameId != null) event.putExtra(GameApiContract.EXTRA_GAME_ID, gameId);
        event.putExtra(GameApiContract.EXTRA_CHANGED_NAMESPACE, namespace);
        event.putExtra(GameApiContract.EXTRA_SETTINGS_SHA256, hash);
        event.putExtra(GameApiContract.EXTRA_PROVIDER_PATH, providerPath);
        ApiEventDispatcher.send(context, event, ApiScope.READ);
    }

    /**
     * Best-effort realtime feedback: reports the outcome of an in-session stall
     * troubleshooting attempt (which remedy resolved a black-screen / hang, or that none
     * did) so AGM can learn centrally. Fire-and-forget; unknown to older AGM builds is safe.
     */
    static void sendStallOutcome(
            Context context,
            String gameId,
            JSONObject outcome,
            boolean resolved
    ) {
        Intent event = new Intent(GameApiContract.ACTION_GAME_EVENT);
        event.putExtra(GameApiContract.EXTRA_API_VERSION, GameApiContract.API_VERSION);
        event.putExtra(GameApiContract.EXTRA_EVENT_TYPE, GameApiContract.EVENT_STALL_OUTCOME);
        event.putExtra(GameApiContract.EXTRA_SUCCESS, resolved);
        if (gameId != null && !gameId.isEmpty()) {
            event.putExtra(GameApiContract.EXTRA_GAME_ID, gameId);
        }
        if (outcome != null) {
            event.putExtra(GameApiContract.EXTRA_DIAGNOSTICS_JSON, outcome.toString());
        }
        ApiEventDispatcher.send(context, event, ApiScope.READ);
    }
}
