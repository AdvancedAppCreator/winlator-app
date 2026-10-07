package com.winlator.core;

import static org.junit.Assert.assertEquals;

import android.app.ApplicationExitInfo;

import org.junit.Test;

public class AppExitDiagnosticsTest {
    @Test
    public void reasonNameMapsActionableAndroidExitReasons() {
        assertEquals(
                "native_crash",
                AppExitDiagnostics.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE)
        );
        assertEquals(
                "low_memory",
                AppExitDiagnostics.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY)
        );
        assertEquals(
                "signaled",
                AppExitDiagnostics.reasonName(ApplicationExitInfo.REASON_SIGNALED)
        );
    }
}
