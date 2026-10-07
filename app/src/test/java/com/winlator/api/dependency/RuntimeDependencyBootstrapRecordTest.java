package com.winlator.api.dependency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class RuntimeDependencyBootstrapRecordTest {
    @Test
    public void advancesSequentiallyAndCompletes() {
        RuntimeDependencyBootstrapRecord record =
                RuntimeDependencyBootstrapRecord.create(1, 5)
                        .select(Arrays.asList("a", "b"))
                        .withState(RuntimeDependencyBootstrapState.READY_TO_INSTALL, null);

        assertEquals("a", record.nextPackageId());
        record = record.startInstall("a", "D:/games").completeCurrent();
        assertEquals(RuntimeDependencyBootstrapState.READY_TO_INSTALL, record.state);
        assertEquals("b", record.nextPackageId());
        record = record.startInstall("b", "D:/temporary").completeCurrent();
        assertEquals(RuntimeDependencyBootstrapState.COMPLETE, record.state);
        assertNull(record.nextPackageId());
        assertEquals("D:/games", record.originalDrives);
    }

    @Test
    public void failurePreservesSelectionAndOriginalDrives() {
        RuntimeDependencyBootstrapRecord record =
                RuntimeDependencyBootstrapRecord.create(1, 5)
                        .select(Arrays.asList("a", "b"))
                        .withState(RuntimeDependencyBootstrapState.READY_TO_INSTALL, null)
                        .startInstall("a", "D:/games")
                        .fail("failed");

        assertEquals(RuntimeDependencyBootstrapState.FAILED, record.state);
        assertEquals(Arrays.asList("a", "b"), record.selectedPackageIds);
        assertEquals("D:/games", record.originalDrives);
        assertEquals("failed", record.lastError);
    }
}
