package com.winlator.core;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeAssetProvisioningCoordinatorTest {
    @Test
    public void provisioningIsSingleFlightAndRebindsToRunningState() {
        FakeStarter starter = new FakeStarter();
        RuntimeAssetProvisioningCoordinator coordinator =
                new RuntimeAssetProvisioningCoordinator(Runnable::run, starter, context -> false);
        RecordingObserver first = new RecordingObserver();
        RecordingObserver recreated = new RecordingObserver();
        Context context = new TestContext();

        coordinator.attach(first);
        coordinator.request(context);
        assertTrue(coordinator.start(context));
        assertFalse(coordinator.start(context));
        assertEquals(1, starter.starts);

        coordinator.detach(first);
        coordinator.attach(recreated);
        assertEquals(
                RuntimeAssetProvisioningCoordinator.Status.RUNNING,
                recreated.last().status
        );

        int detachedNotificationCount = first.states.size();
        starter.progress.onProgress(50, 100);
        assertEquals(detachedNotificationCount, first.states.size());
        assertEquals(50, recreated.last().progress);

        starter.completion.onComplete(RuntimeAssetProvisioner.Result.SUCCESS);
        assertEquals(
                RuntimeAssetProvisioningCoordinator.Status.SUCCEEDED,
                recreated.last().status
        );
    }

    @Test
    public void cancellationIsOwnedByActiveSingleFlight() {
        FakeStarter starter = new FakeStarter();
        RuntimeAssetProvisioningCoordinator coordinator =
                new RuntimeAssetProvisioningCoordinator(Runnable::run, starter, context -> false);
        RecordingObserver observer = new RecordingObserver();
        Context context = new TestContext();
        coordinator.attach(observer);
        coordinator.request(context);
        coordinator.start(context);

        coordinator.cancel();

        assertTrue(starter.cancelled.get());
        starter.completion.onComplete(RuntimeAssetProvisioner.Result.CANCELLED);
        assertEquals(
                RuntimeAssetProvisioningCoordinator.Status.CANCELLED,
                observer.last().status
        );
    }

    @Test
    public void queuedCallbacksDoNotTargetDetachedObserver() {
        List<Runnable> callbacks = new ArrayList<>();
        RuntimeAssetProvisioningCoordinator coordinator =
                new RuntimeAssetProvisioningCoordinator(
                        callbacks::add,
                        new FakeStarter(),
                        context -> false
                );
        RecordingObserver observer = new RecordingObserver();

        coordinator.attach(observer);
        coordinator.detach(observer);
        for (Runnable callback : callbacks) callback.run();

        assertTrue(observer.states.isEmpty());
    }

    private static final class FakeStarter
            implements RuntimeAssetProvisioningCoordinator.Starter {
        int starts;
        AtomicBoolean cancelled;
        RuntimeAssetProvisioner.Progress progress;
        RuntimeAssetProvisioner.Completion completion;

        @Override
        public void start(
                Context context,
                AtomicBoolean cancelled,
                RuntimeAssetProvisioner.Progress progress,
                RuntimeAssetProvisioner.Completion completion
        ) {
            starts++;
            this.cancelled = cancelled;
            this.progress = progress;
            this.completion = completion;
        }
    }

    private static final class RecordingObserver
            implements RuntimeAssetProvisioningCoordinator.Observer {
        final List<RuntimeAssetProvisioningCoordinator.State> states = new ArrayList<>();

        @Override
        public void onRuntimeProvisioningStateChanged(
                RuntimeAssetProvisioningCoordinator.State state
        ) {
            states.add(state);
        }

        RuntimeAssetProvisioningCoordinator.State last() {
            return states.get(states.size() - 1);
        }
    }

    private static final class TestContext extends ContextWrapper {
        TestContext() {
            super(null);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
}
