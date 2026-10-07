package com.winlator.xenvironment;

import com.winlator.core.ProcessHelper;

public abstract class EnvironmentComponent {
    protected XEnvironment environment;

    public abstract void start();

    public abstract void stop();

    public void stop(ProcessHelper.TerminationOrigin origin) {
        stop();
    }

    public void onPause() {}

    public void onResume() {}
}