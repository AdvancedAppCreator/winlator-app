package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;

import androidx.test.core.app.ApplicationProvider;

import com.winlator.api.dependency.DependencyStatus;
import com.winlator.api.dependency.RuntimeDependencyCatalog;
import com.winlator.api.dependency.RuntimeDependencyManager;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public class GameSessionEventReporterTest {
    private static final String INTEGRATION_PACKAGE = "com.example.integration";

    @Before
    public void approveInstalledIntegration() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        application.getSharedPreferences("api_approvals", 0)
                .edit()
                .clear()
                .commit();
        Signature signature = new Signature(
                "event-recipient-certificate".getBytes(StandardCharsets.UTF_8)
        );
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = INTEGRATION_PACKAGE;
        packageInfo.signatures = new Signature[]{signature};
        shadowOf(application.getPackageManager()).installPackage(packageInfo);
        HashSet<String> certificates = new HashSet<>();
        certificates.add(sha256(signature.toByteArray()));
        new ApiApprovalStore(application).approve(
                INTEGRATION_PACKAGE,
                certificates,
                ApiScope.ALL
        );
    }

    @Test
    public void diagnosticBroadcastContainsSummaryButNotEvidence() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        JSONObject report = new JSONObject()
                .put("reportId", "report")
                .put("gameId", "game")
                .put("containerId", 4)
                .put("startedAt", 1000L)
                .put("endedAt", 2000L)
                .put("outcome", "crashed")
                .put("phase", "runtime")
                .put("category", "box64_segfault")
                .put("confidence", "high")
                .put("terminationOrigin", "external_signal")
                .put("configHealth", "bad")
                .put("runtimeReached", true)
                .put("exitCode", 139)
                .put("signal", 11)
                .put("evidence", "must not be broadcast");

        GameSessionEventReporter.sendDiagnosticSummary(application, report);

        List<Intent> broadcasts = shadowOf(application).getBroadcastIntents();
        Intent event = broadcasts.get(broadcasts.size() - 1);
        assertEquals("report", event.getStringExtra(GameApiContract.EXTRA_REPORT_ID));
        assertEquals(
                "box64_segfault",
                event.getStringExtra(GameApiContract.EXTRA_CATEGORY)
        );
        assertEquals(139, event.getIntExtra(GameApiContract.EXTRA_EXIT_CODE, -1));
        assertEquals(
                "external_signal",
                event.getStringExtra(GameApiContract.EXTRA_TERMINATION_ORIGIN)
        );
        assertFalse(event.hasExtra("evidence"));
    }

    @Test
    public void interruptedInstallerRestoresPreviousGameState() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        ManagedGameStore store = new ManagedGameStore(application);
        ManagedGame game = new ManagedGame();
        game.id = "recovered-installer";
        game.title = "Recovered installer";
        game.containerId = 4;
        game.containerPolicy = ManagedGame.CONTAINER_POLICY_ISOLATED;
        game.state = "installing";
        game.createdAt = 1;
        game.updatedAt = 1;
        store.put(game);

        try {
            JSONObject report = new JSONObject()
                    .put("reportId", "installer-report")
                    .put("gameId", game.id)
                    .put("containerId", game.containerId)
                    .put("installerSession", true)
                    .put("installerPreviousState", "ready")
                    .put("startedAt", 1000L)
                    .put("endedAt", 2000L)
                    .put("outcome", "unknown")
                    .put("phase", "startup")
                    .put("category", "app_or_session_terminated")
                    .put("confidence", "low")
                    .put("configHealth", "unknown")
                    .put("runtimeReached", false);

            GameSessionEventReporter.sendDiagnosticSummary(application, report);

            assertEquals("ready", store.get(game.id).state);
            List<Intent> broadcasts = shadowOf(application).getBroadcastIntents();
            Intent event = broadcasts.get(broadcasts.size() - 1);
            assertEquals(
                    GameApiContract.ERROR_INSTALL_ABORTED,
                    event.getStringExtra(GameApiContract.EXTRA_ERROR_CODE)
            );
        }

        finally {
            store.remove(game.id);
        }
    }

    @Test
    public void interruptedDependencySessionClearsInstallingState() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        String dependencyId = RuntimeDependencyCatalog.OPENAL;
        RuntimeDependencyManager.markInstalling(
                application,
                12,
                dependencyId,
                application.getFilesDir() + "/openal.exe"
        );
        JSONObject report = new JSONObject()
                .put("reportId", "dependency-report")
                .put("dependencySession", true)
                .put("dependencyId", dependencyId)
                .put("containerId", 12)
                .put("startedAt", 1000L)
                .put("endedAt", 2000L)
                .put("outcome", "unknown")
                .put("phase", "startup")
                .put("category", "app_or_session_terminated")
                .put("confidence", "low")
                .put("configHealth", "unknown")
                .put("runtimeReached", false);

        GameSessionEventReporter.sendDiagnosticSummary(application, report);

        assertEquals(
                DependencyStatus.FAILED,
                RuntimeDependencyManager.getRecord(
                        application,
                        12,
                        dependencyId
                ).status
        );
        List<Intent> broadcasts = shadowOf(application).getBroadcastIntents();
        Intent event = broadcasts.get(broadcasts.size() - 1);
        assertEquals(
                GameApiContract.EVENT_DEPENDENCY_FAILED,
                event.getStringExtra(GameApiContract.EXTRA_EVENT_TYPE)
        );
    }

    @Test
    public void nonZeroManagedGameExitIsReportedAsFailure() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        Intent sessionIntent = managedGameIntent();
        JSONObject report = new JSONObject()
                .put("reportId", "failed-game")
                .put("outcome", "crashed")
                .put("phase", "runtime")
                .put("category", "abnormal_process_exit")
                .put("confidence", "medium")
                .put("configHealth", "bad")
                .put("runtimeReached", true)
                .put("exitCode", 3);

        GameSessionEventReporter.complete(application, sessionIntent, 3, report);

        Intent event = lastBroadcast(application);
        assertFalse(event.getBooleanExtra(GameApiContract.EXTRA_SUCCESS, true));
        assertEquals(
                GameApiContract.ERROR_LAUNCH_FAILED,
                event.getStringExtra(GameApiContract.EXTRA_ERROR_CODE)
        );
        assertEquals(3, event.getIntExtra(GameApiContract.EXTRA_EXIT_CODE, -1));
    }

    @Test
    public void localeStartupAbortIncludesStructuredLaunchFailure() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        Intent sessionIntent = managedGameIntent();
        JSONObject report = new JSONObject()
                .put("reportId", "locale-report")
                .put("outcome", "launch_failed")
                .put("phase", "environment_start")
                .put("category", "runtime_locale_generation")
                .put("confidence", "high")
                .put("configHealth", "bad")
                .put("runtimeReached", false);

        GameSessionEventReporter.failToStart(
                application,
                sessionIntent,
                GameApiContract.ERROR_LAUNCH_FAILED,
                "The game locale could not be prepared.",
                report
        );

        Intent event = lastBroadcast(application);
        assertFalse(event.getBooleanExtra(GameApiContract.EXTRA_SUCCESS, true));
        assertEquals(
                GameApiContract.ERROR_LAUNCH_FAILED,
                event.getStringExtra(GameApiContract.EXTRA_ERROR_CODE)
        );
        assertEquals(
                "The game locale could not be prepared.",
                event.getStringExtra(GameApiContract.EXTRA_ERROR_MESSAGE)
        );
        assertEquals(
                "locale-report",
                event.getStringExtra(GameApiContract.EXTRA_REPORT_ID)
        );
        assertEquals(
                "environment_start",
                event.getStringExtra(GameApiContract.EXTRA_PHASE)
        );
        assertFalse(event.getBooleanExtra(GameApiContract.EXTRA_RUNTIME_REACHED, true));
    }

    @Test
    public void explicitUserExitRemainsSuccessful() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        JSONObject report = new JSONObject()
                .put("reportId", "user-exit")
                .put("outcome", "user_exit")
                .put("phase", "shutdown")
                .put("category", "user_exit")
                .put("confidence", "high")
                .put("configHealth", "unknown")
                .put("runtimeReached", true);

        GameSessionEventReporter.abort(
                application,
                managedGameIntent(),
                report
        );

        assertTrue(lastBroadcast(application).getBooleanExtra(
                GameApiContract.EXTRA_SUCCESS,
                false
        ));
    }

    @Test
    public void settingsChangedIsSuccessfulPostCommitNotification() {
        Application application = ApplicationProvider.getApplicationContext();

        GameSessionEventReporter.sendSettingsChanged(
                application,
                "settings-game",
                "runtime",
                "ABC123",
                "games/settings-game/settings"
        );

        Intent event = lastBroadcast(application);
        assertEquals(
                GameApiContract.EVENT_SETTINGS_CHANGED,
                event.getStringExtra(GameApiContract.EXTRA_EVENT_TYPE)
        );
        assertTrue(event.getBooleanExtra(GameApiContract.EXTRA_SUCCESS, false));
        assertEquals(
                "ABC123",
                event.getStringExtra(GameApiContract.EXTRA_SETTINGS_SHA256)
        );
        assertFalse(event.hasExtra(GameApiContract.EXTRA_ERROR_CODE));
        assertFalse(event.hasExtra(GameApiContract.EXTRA_ERROR_MESSAGE));
    }

    @Test
    public void inconclusiveDiagnosticUsesCleanProcessStatus() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        JSONObject report = new JSONObject()
                .put("reportId", "unknown-clean-exit")
                .put("outcome", "unknown")
                .put("phase", "shutdown")
                .put("category", "graphics_initialization")
                .put("confidence", "low")
                .put("configHealth", "unknown")
                .put("runtimeReached", false)
                .put("exitCode", 0);

        GameSessionEventReporter.complete(
                application,
                managedGameIntent(),
                0,
                report
        );

        assertTrue(lastBroadcast(application).getBooleanExtra(
                GameApiContract.EXTRA_SUCCESS,
                false
        ));
    }

    private Intent managedGameIntent() {
        ManagedGame game = new ManagedGame();
        game.id = "managed-game";
        game.containerId = 4;
        return GameSessionEventReporter.configure(
                new Intent(),
                game,
                false,
                "ready"
        );
    }

    private Intent lastBroadcast(Application application) {
        List<Intent> broadcasts = shadowOf(application).getBroadcastIntents();
        return broadcasts.get(broadcasts.size() - 1);
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
