package com.winlator.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ProcessHelperTest {
    @Test
    public void collectsOnlyRecursiveDescendants() {
        ProcessHelper.PStat leader = process(100, 10);
        ProcessHelper.PStat child = process(101, 100);
        ProcessHelper.PStat grandchild = process(102, 101);
        ProcessHelper.PStat unrelated = process(103, 10);

        List<Integer> descendants = ProcessHelper.collectDescendantProcessIds(
                Arrays.asList(leader, child, grandchild, unrelated),
                leader.pid
        );

        assertTrue(descendants.contains(child.pid));
        assertTrue(descendants.contains(grandchild.pid));
        assertFalse(descendants.contains(unrelated.pid));
        assertFalse(descendants.contains(leader.pid));
    }

    private static ProcessHelper.PStat process(int pid, int parentPid) {
        ProcessHelper.PStat process = new ProcessHelper.PStat();
        process.pid = pid;
        process.parentPID = parentPid;
        return process;
    }
}
