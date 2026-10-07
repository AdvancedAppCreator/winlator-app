package com.winlator.api;

import android.os.Handler;
import android.os.Looper;

import com.winlator.core.StartupLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Realtime watchdog. Periodically samples live signals, runs the injected troubleshooter
 * evaluator, and reports actionable stalls, clears, and resolutions to a listener on the main
 * thread. Notifications are debounced per remedy (cause + recommended step) so the overlay is
 * not re-shown for the same recommendation, and a dismissed recommendation is suppressed until
 * it changes.
 */
final class ManagedSessionWatchdog {
    interface SignalProvider {
        StallSignals snapshot();
    }

    interface Evaluator {
        StallTroubleshooter.Result evaluate(StallSignals signals) throws JSONException;
    }

    interface StallListener {
        void onStallDetected(JSONObject diagnosis);

        void onStallCleared();

        void onStallResolved(String stepId, String cause);
    }

    private final SignalProvider provider;
    private final Evaluator evaluator;
    private final StallListener listener;
    private final long tickMillis;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<String> dismissedKeys = new HashSet<>();

    private ScheduledExecutorService scheduler;
    private volatile String activeKey;
    private volatile boolean resolvedReported;

    ManagedSessionWatchdog(SignalProvider provider, Evaluator evaluator,
                           StallListener listener, long tickMillis) {
        this.provider = provider;
        this.evaluator = evaluator;
        this.listener = listener;
        this.tickMillis = tickMillis;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "managed-stall-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
                this::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (scheduler != null) scheduler.shutdownNow();
    }

    void dismissCurrent() {
        String key = activeKey;
        if (key != null) dismissedKeys.add(key);
        activeKey = null;
    }

    private void tick() {
        if (!running.get()) return;
        try {
            StallSignals signals = provider.snapshot();
            if (signals == null) return;
            StallTroubleshooter.Result result = evaluator.evaluate(signals);
            switch (result.kind) {
                case STALL:
                    handleStall(result.diagnosis);
                    break;
                case RESOLVED:
                    handleResolved(result.resolvedStepId, result.cause);
                    break;
                case NONE:
                default:
                    if (activeKey != null) {
                        activeKey = null;
                        mainHandler.post(() -> {
                            if (running.get()) listener.onStallCleared();
                        });
                    }
                    break;
            }
        }
        catch (JSONException | RuntimeException error) {
            StartupLog.log("Managed stall watchdog tick failed", error);
        }
    }

    private void handleStall(JSONObject diagnosis) {
        if (diagnosis == null) return;
        String key = diagnosis.optString("dedupeKey", diagnosis.optString("cause", ""));
        if (dismissedKeys.contains(key)) return;
        if (key.equals(activeKey)) return;
        activeKey = key;
        mainHandler.post(() -> {
            if (running.get()) listener.onStallDetected(diagnosis);
        });
    }

    private void handleResolved(String stepId, String cause) {
        if (resolvedReported) return;
        resolvedReported = true;
        activeKey = null;
        mainHandler.post(() -> {
            if (running.get()) listener.onStallResolved(stepId, cause);
        });
    }
}
