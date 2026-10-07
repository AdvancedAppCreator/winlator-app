package com.winlator.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeAssetProvisioningCoordinator {
    public enum Status {
        IDLE,
        CONSENT_REQUIRED,
        RUNNING,
        CANCELLED,
        FAILED,
        SUCCEEDED
    }

    public static final class State {
        public final Status status;
        public final int progress;
        public final RuntimeAssetProvisioner.Result result;

        private State(
                Status status,
                int progress,
                RuntimeAssetProvisioner.Result result
        ) {
            this.status = status;
            this.progress = progress;
            this.result = result;
        }
    }

    public interface Observer {
        void onRuntimeProvisioningStateChanged(State state);
    }

    interface Dispatcher {
        void dispatch(Runnable runnable);
    }

    interface ReadyChecker {
        boolean isReady(Context context);
    }

    interface Starter {
        void start(
                Context context,
                AtomicBoolean cancelled,
                RuntimeAssetProvisioner.Progress progress,
                RuntimeAssetProvisioner.Completion completion
        );
    }

    private static final RuntimeAssetProvisioningCoordinator INSTANCE =
            new RuntimeAssetProvisioningCoordinator(
                    runnable -> new Handler(Looper.getMainLooper()).post(runnable),
                    RuntimeAssetProvisioner::provisionAsync,
                    RuntimeAssetProvisioner::isReady
            );

    private final Dispatcher dispatcher;
    private final Starter starter;
    private final ReadyChecker readyChecker;
    private final Set<Observer> observers = new HashSet<>();
    private State state = new State(Status.IDLE, 0, null);
    private AtomicBoolean cancellation;

    RuntimeAssetProvisioningCoordinator(
            Dispatcher dispatcher,
            Starter starter,
            ReadyChecker readyChecker
    ) {
        this.dispatcher = dispatcher;
        this.starter = starter;
        this.readyChecker = readyChecker;
    }

    public static RuntimeAssetProvisioningCoordinator getInstance() {
        return INSTANCE;
    }

    public void attach(Observer observer) {
        State current;
        synchronized (this) {
            observers.add(observer);
            current = state;
        }
        dispatch(observer, current);
    }

    public synchronized void detach(Observer observer) {
        observers.remove(observer);
    }

    public void request(Context context) {
        synchronized (this) {
            if (state.status != Status.IDLE) return;
        }
        boolean ready = readyChecker.isReady(context.getApplicationContext());
        State next;
        synchronized (this) {
            if (state.status != Status.IDLE) return;
            next = new State(
                    ready ? Status.SUCCEEDED : Status.CONSENT_REQUIRED,
                    0,
                    ready ? RuntimeAssetProvisioner.Result.SUCCESS : null
            );
            state = next;
        }
        notifyObservers(next);
    }

    public boolean start(Context context) {
        AtomicBoolean operationCancellation;
        State running;
        synchronized (this) {
            if (state.status != Status.CONSENT_REQUIRED
                    && state.status != Status.CANCELLED
                    && state.status != Status.FAILED) {
                return false;
            }
            operationCancellation = new AtomicBoolean(false);
            cancellation = operationCancellation;
            running = new State(Status.RUNNING, 0, null);
            state = running;
        }
        notifyObservers(running);
        starter.start(
                context.getApplicationContext(),
                operationCancellation,
                (downloaded, total) -> updateProgress(operationCancellation, downloaded, total),
                result -> complete(operationCancellation, result)
        );
        return true;
    }

    public synchronized void cancel() {
        if (state.status == Status.RUNNING && cancellation != null) {
            cancellation.set(true);
        }
    }

    private void updateProgress(AtomicBoolean operation, long downloaded, long total) {
        if (total <= 0) return;
        State next;
        synchronized (this) {
            if (cancellation != operation || state.status != Status.RUNNING) return;
            int progress = (int)Math.min(100L, (downloaded * 100L) / total);
            if (progress == state.progress) return;
            next = new State(Status.RUNNING, progress, null);
            state = next;
        }
        notifyObservers(next);
    }

    private void complete(
            AtomicBoolean operation,
            RuntimeAssetProvisioner.Result result
    ) {
        State next;
        synchronized (this) {
            if (cancellation != operation || state.status != Status.RUNNING) return;
            cancellation = null;
            Status status;
            if (result == RuntimeAssetProvisioner.Result.SUCCESS) {
                status = Status.SUCCEEDED;
            }
            else if (result == RuntimeAssetProvisioner.Result.CANCELLED) {
                status = Status.CANCELLED;
            }
            else {
                status = Status.FAILED;
            }
            next = new State(status, state.progress, result);
            state = next;
        }
        notifyObservers(next);
    }

    private void notifyObservers(State next) {
        ArrayList<Observer> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(observers);
        }
        for (Observer observer : snapshot) dispatch(observer, next);
    }

    private void dispatch(Observer observer, State next) {
        dispatcher.dispatch(() -> {
            synchronized (RuntimeAssetProvisioningCoordinator.this) {
                if (!observers.contains(observer)) return;
            }
            observer.onRuntimeProvisioningStateChanged(next);
        });
    }
}
