package com.winlator.api;

import android.content.Context;
import android.content.Intent;

import com.winlator.MainActivity;
import com.winlator.core.Callback;
import com.winlator.core.GPUHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StartupLog;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class ManagedSessionDiagnostics implements Callback<String>, AutoCloseable {
    private static final int MAX_CAPTURED_LINES = 200;
    private static final int MAX_CAPTURED_CHARS = 64 * 1024;
    private static final Pattern GUEST_ERROR_LINE = Pattern.compile(
            "(?i)(\\berr:|\\berror\\b|exception|segmentation fault|sigsegv|sigill|"
                    + "\\bfatal\\b|\\bfailed\\b|cannot |unable to|not found|assertion)"
    );

    /** Receives realtime black-screen / hang notifications on the main thread. */
    public interface StallListener {
        void onStallDetected(JSONObject diagnosis);

        void onStallCleared();

        void onStallResolved(String stepId, String cause);
    }

    /** Supplies the timestamp of the most recently presented frame, or 0 if none. */
    public interface FrameClock {
        long lastFrameTimeMillis();
    }

    private final Context context;
    private final ManagedDiagnosticStore store;
    private final JSONObject draft;
    private final ArrayDeque<String> output = new ArrayDeque<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean eventSent;
    private final Thread.UncaughtExceptionHandler previousExceptionHandler;
    private final Thread.UncaughtExceptionHandler exceptionHandler;
    private int capturedCharacters;
    private volatile long lastGuestOutputAt;
    private volatile FrameClock frameClock;
    private volatile ManagedSessionWatchdog watchdog;
    private volatile StallTroubleshooter troubleshooter;
    private volatile StallAttemptStore attemptStore;
    private final AtomicInteger guestErrorCount = new AtomicInteger();
    private final AtomicInteger unhandledExceptionCount = new AtomicInteger();

    public static ManagedSessionDiagnostics start(
            Context context,
            Intent sessionIntent,
            AtomicBoolean eventSent
    ) {
        if (!GameSessionEventReporter.isManagedSession(sessionIntent)) return null;
        try {
            ManagedDiagnosticStore store = new ManagedDiagnosticStore(context);
            for (JSONObject recovered : store.recoverInterrupted(System.currentTimeMillis())) {
                GameSessionEventReporter.sendDiagnosticSummary(context, recovered);
            }

            String gameId = sessionIntent.getStringExtra(GameApiContract.EXTRA_GAME_ID);
            ManagedGame game = gameId != null ? new ManagedGameStore(context).get(gameId) : null;
            JSONObject config = game != null
                    ? ManagedGameRuntimeOperations.currentConfig(context, game)
                    : new JSONObject();
            JSONObject draft = new JSONObject()
                    .put("reportId", UUID.randomUUID().toString())
                    .put(
                            "sessionId",
                            sessionIntent.getStringExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN)
                    )
                    .put("gameId", gameId)
                    .put(
                            "containerId",
                            sessionIntent.getIntExtra(GameApiContract.EXTRA_CONTAINER_ID, 0)
                    )
                    .put(
                            "installerSession",
                            GameSessionEventReporter.isInstallerSession(sessionIntent)
                    )
                    .put(
                            "dependencySession",
                            GameSessionEventReporter.isDependencySession(sessionIntent)
                    )
                    .put(
                            "startedAt",
                            sessionIntent.getLongExtra(
                                    GameApiContract.EXTRA_STARTED_AT,
                                    System.currentTimeMillis()
                            )
                    )
                    .put("phase", "environment_start")
                    .put("runtimeReached", false)
                    .put("appliedConfig", config)
                    .put("appliedConfigSha256", GameConfigSchema.hash(config));
            if (GameSessionEventReporter.isDependencySession(sessionIntent)) {
                draft.put(
                        "dependencyId",
                        sessionIntent.getStringExtra(
                                GameApiContract.INTERNAL_EXTRA_DEPENDENCY_ID
                        )
                );
            }
            if (GameSessionEventReporter.isInstallerSession(sessionIntent)) {
                String previousState = GameSessionEventReporter.getPreviousState(sessionIntent);
                if (previousState != null && !previousState.isEmpty()) {
                    draft.put("installerPreviousState", previousState);
                }
            }
            store.start(draft);
            return new ManagedSessionDiagnostics(context, store, draft, eventSent);
        }
        catch (JSONException | IOException error) {
            StartupLog.log("Unable to start managed diagnostics", error);
            return null;
        }
    }

    private ManagedSessionDiagnostics(
            Context context,
            ManagedDiagnosticStore store,
            JSONObject draft,
            AtomicBoolean eventSent
    ) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.draft = draft;
        this.eventSent = eventSent;
        ProcessHelper.addDebugCallback(this);

        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        exceptionHandler = (thread, error) -> {
            JSONObject report = finishUncaught(error);
            if (report != null && this.eventSent.compareAndSet(false, true)) {
                GameSessionEventReporter.sendDiagnosticSummary(this.context, report);
            }
            if (previousExceptionHandler != null) {
                previousExceptionHandler.uncaughtException(thread, error);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler);
    }

    @Override
    public synchronized void call(String line) {
        if (finished.get() || line == null) return;
        String redacted = redact(context, line);
        if (redacted.length() > 2048) redacted = redacted.substring(0, 2048);
        output.addLast(redacted);
        capturedCharacters += redacted.length();
        lastGuestOutputAt = System.currentTimeMillis();
        if (GUEST_ERROR_LINE.matcher(redacted).find()) guestErrorCount.incrementAndGet();
        while (output.size() > MAX_CAPTURED_LINES ||
                capturedCharacters > MAX_CAPTURED_CHARS) {
            String removed = output.removeFirst();
            capturedCharacters -= removed.length();
        }
    }

    /** Registers the source of frame-presentation timing used for hang detection. */
    public void setFrameClock(FrameClock frameClock) {
        this.frameClock = frameClock;
    }

    /**
     * Starts the realtime black-screen / hang watchdog. Safe to call once per session;
     * subsequent calls are ignored. Notifications are delivered on the main thread.
     */
    public void startStallWatchdog(StallListener listener) {
        if (finished.get() || listener == null || watchdog != null) return;
        StallThresholds thresholds = MainActivity.DEBUG_MODE
                ? StallThresholds.debug()
                : StallThresholds.defaults();
        long tickMillis = MainActivity.DEBUG_MODE ? 1500 : 3000;

        String gameId = draft.optString("gameId", "");
        String gpu = "";
        try {
            gpu = GPUHelper.glGetRenderer(context);
        }
        catch (RuntimeException error) {
            StartupLog.log("Unable to resolve GPU for stall diagnostics", error);
        }
        attemptStore = new StallAttemptStore(context);
        troubleshooter = new StallTroubleshooter(
                gameId,
                StallKnowledgeBase.load(context),
                attemptStore,
                thresholds,
                gpu
        );

        ManagedSessionWatchdog created = new ManagedSessionWatchdog(
                this::buildSignals,
                signals -> troubleshooter.evaluate(signals),
                new ManagedSessionWatchdog.StallListener() {
                    @Override
                    public void onStallDetected(JSONObject diagnosis) {
                        listener.onStallDetected(diagnosis);
                    }

                    @Override
                    public void onStallCleared() {
                        listener.onStallCleared();
                    }

                    @Override
                    public void onStallResolved(String stepId, String cause) {
                        reportStallResolved(gameId, stepId, cause);
                        listener.onStallResolved(stepId, cause);
                    }
                },
                tickMillis
        );
        watchdog = created;
        created.start();
    }

    /**
     * Records that the user applied a remedy step just before the session restarts, so the
     * troubleshooter advances the ladder and can credit the step if the game then recovers.
     */
    public void recordRemedyApplied(String stepId, String cause) {
        StallAttemptStore store = attemptStore;
        if (store == null) store = attemptStore = new StallAttemptStore(context);
        String gameId = draft.optString("gameId", "");
        store.recordApplied(gameId, cause, stepId, System.currentTimeMillis());
    }

    private void reportStallResolved(String gameId, String stepId, String cause) {
        StallAttemptStore store = attemptStore;
        try {
            JSONObject outcome = new JSONObject()
                    .put("resolved", true)
                    .put("cause", cause != null ? cause : "")
                    .put("resolvedByStep", stepId != null ? stepId : "");
            GameSessionEventReporter.sendStallOutcome(context, gameId, outcome, true);
        }
        catch (JSONException error) {
            StartupLog.log("Unable to report stall resolution", error);
        }
        if (store != null) store.clear(gameId);
    }

    /** Suppresses further notifications for the current stall cause ("keep waiting"). */
    public void dismissStall() {
        ManagedSessionWatchdog current = watchdog;
        if (current != null) current.dismissCurrent();
    }

    private StallSignals buildSignals() {
        if (finished.get()) return null;
        long now = System.currentTimeMillis();
        FrameClock clock = frameClock;
        long lastFrame = clock != null ? clock.lastFrameTimeMillis() : 0;
        synchronized (draft) {
            return new StallSignals(
                    now,
                    draft.optLong("startedAt", now),
                    lastGuestOutputAt,
                    lastFrame,
                    draft.optBoolean("runtimeReached", false),
                    outputSnapshot(),
                    draft.optJSONObject("appliedConfig"),
                    draft.optString("appliedConfigSha256", "")
            );
        }
    }

    private void stopWatchdog() {
        ManagedSessionWatchdog current = watchdog;
        if (current != null) {
            current.stop();
            watchdog = null;
        }
    }

    /** Live guest-log lines matching an error/warning signature since the session started. */
    public int getGuestErrorCount() {
        return guestErrorCount.get();
    }

    /** Uncaught host (Android) exceptions observed during this session. */
    public int getUnhandledExceptionCount() {
        return unhandledExceptionCount.get();
    }

    /** Number of captured guest-output lines currently retained. */
    public synchronized int getCapturedLineCount() {
        return output.size();
    }

    /** Milliseconds since the last guest-output line, or -1 if none yet. */
    public long getMillisSinceGuestOutput() {
        long reference = lastGuestOutputAt;
        return reference <= 0 ? -1 : Math.max(0, System.currentTimeMillis() - reference);
    }

    /** Milliseconds since the last presented frame, or -1 if unknown. */
    public long getMillisSinceLastFrame() {
        FrameClock clock = frameClock;
        long last = clock != null ? clock.lastFrameTimeMillis() : 0;
        return last <= 0 ? -1 : Math.max(0, System.currentTimeMillis() - last);
    }

    public boolean isRuntimeReached() {
        synchronized (draft) {
            return draft.optBoolean("runtimeReached", false);
        }
    }

    /** A copy of the most recent captured guest-output lines, newest last. */
    public synchronized List<String> getRecentOutput(int max) {
        int count = Math.min(max, output.size());
        ArrayList<String> tail = new ArrayList<>(count);
        int skip = output.size() - count;
        int index = 0;
        for (String line : output) {
            if (index++ < skip) continue;
            tail.add(line);
        }
        return tail;
    }

    public void markPhase(String phase) {
        if (finished.get() || phase == null || phase.isEmpty()) return;
        try {
            synchronized (draft) {
                draft.put("phase", phase);
                store.updateActive(draft);
            }
        }
        catch (JSONException | IOException error) {
            StartupLog.log("Unable to update managed diagnostic phase", error);
        }
    }

    public void markRuntimeReached() {
        if (finished.get()) return;
        try {
            synchronized (draft) {
                draft.put("runtimeReached", true);
                draft.put("phase", "runtime");
                store.updateActive(draft);
            }
        }
        catch (JSONException | IOException error) {
            StartupLog.log("Unable to mark managed runtime", error);
        }
    }

    public JSONObject finishProcess(GuestProgramLauncherComponent.TerminationResult result) {
        try {
            synchronized (draft) {
                draft.put("gracefulShutdownRequested", result.gracefulShutdownRequested);
                draft.put("gracefulShutdownCompleted", result.gracefulShutdownCompleted);
                draft.put("terminationTermSent", result.termSent);
                draft.put("terminationKillSent", result.killSent);
            }
        }
        catch (JSONException error) {
            StartupLog.log("Unable to attach process termination details", error);
        }
        return finish(
                result.exitCode,
                result.exitCode == 0 ? "completed" : "unknown",
                null,
                result.origin.value
        );
    }

    public JSONObject finishProcess(int exitCode) {
        return finish(
                exitCode,
                exitCode == 0 ? "completed" : "unknown",
                null,
                exitCode >= 128 && exitCode <= 192
                        ? ProcessHelper.TerminationOrigin.EXTERNAL_SIGNAL.value
                        : ProcessHelper.TerminationOrigin.NATURAL.value
        );
    }

    public JSONObject finishUserExit() {
        return finish(
                null,
                "user_exit",
                null,
                ProcessHelper.TerminationOrigin.USER_EXIT.value
        );
    }

    public JSONObject finishSessionReplaced() {
        return finish(
                null,
                "unknown",
                null,
                ProcessHelper.TerminationOrigin.SESSION_REPLACED.value
        );
    }

    public JSONObject finishUnexpected() {
        return finish(
                null,
                "unknown",
                null,
                ProcessHelper.TerminationOrigin.WINLATOR_TEARDOWN.value
        );
    }

    public JSONObject finishLaunchFailure(
            String phase,
            String category,
            String message,
            JSONObject details
    ) {
        call(message);
        try {
            synchronized (draft) {
                draft.put("phase", phase);
                draft.put("failureCategory", category);
                if (details != null) draft.put("failureDetails", details);
            }
        }
        catch (JSONException error) {
            StartupLog.log("Unable to attach managed launch failure details", error);
        }
        return finish(
                null,
                "launch_failed",
                null,
                ProcessHelper.TerminationOrigin.NATURAL.value
        );
    }

    public JSONObject finishUncaught(Throwable error) {
        unhandledExceptionCount.incrementAndGet();
        if (error != null) {
            StringWriter buffer = new StringWriter();
            error.printStackTrace(new PrintWriter(buffer));
            call(buffer.toString());
        }
        return finish(
                null,
                "crashed",
                error,
                ProcessHelper.TerminationOrigin.NATURAL.value
        );
    }

    public void cancel() {
        if (!finished.compareAndSet(false, true)) return;
        stopWatchdog();
        ProcessHelper.removeDebugCallback(this);
        restoreExceptionHandler();
        try {
            store.cancel(draft.getString("reportId"));
        }
        catch (JSONException | IOException error) {
            StartupLog.log("Unable to cancel managed diagnostics", error);
        }
    }

    private JSONObject finish(
            Integer exitCode,
            String outcome,
            Throwable error,
            String terminationOrigin
    ) {
        if (!finished.compareAndSet(false, true)) return null;
        stopWatchdog();
        ProcessHelper.removeDebugCallback(this);
        restoreExceptionHandler();
        try {
            JSONObject report;
            synchronized (draft) {
                report = ManagedDiagnosticClassifier.classify(
                        draft,
                        outputSnapshot(),
                        exitCode,
                        outcome,
                        error,
                        System.currentTimeMillis(),
                        terminationOrigin
                );
                store.finish(draft.getString("reportId"), report);
            }
            return report;
        }
        catch (JSONException | IOException storageError) {
            StartupLog.log("Unable to finish managed diagnostics", storageError);
            return null;
        }
    }

    @Override
    public void close() {
        stopWatchdog();
        ProcessHelper.removeDebugCallback(this);
        restoreExceptionHandler();
    }

    private synchronized ArrayList<String> outputSnapshot() {
        return new ArrayList<>(output);
    }

    private void restoreExceptionHandler() {
        if (Thread.getDefaultUncaughtExceptionHandler() == exceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler);
        }
    }

    static String redact(Context context, String line) {
        String result = line.replace('\\', '/');
        result = replacePath(result, context.getFilesDir(), "<files>");
        result = replacePath(result, context.getCacheDir(), "<cache>");
        result = replacePath(result, new File(context.getApplicationInfo().dataDir), "<data>");
        return result.replaceAll("/storage/emulated/[0-9]+", "<shared-storage>");
    }

    private static String replacePath(String text, File path, String replacement) {
        if (path == null) return text;
        return text.replace(path.getPath().replace('\\', '/'), replacement);
    }
}
