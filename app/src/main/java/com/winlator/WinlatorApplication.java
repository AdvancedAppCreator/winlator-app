package com.winlator;

import android.app.Application;

import com.winlator.core.AppExitDiagnostics;
import com.winlator.core.StartupLog;

public class WinlatorApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        StartupLog.initialize(this);
        AppExitDiagnostics.initialize(this);
    }
}
