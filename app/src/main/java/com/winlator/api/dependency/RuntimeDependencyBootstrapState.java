package com.winlator.api.dependency;

public enum RuntimeDependencyBootstrapState {
    NEEDS_SELECTION,
    DOWNLOADING,
    READY_TO_INSTALL,
    INSTALLING,
    FAILED,
    COMPLETE;

    static RuntimeDependencyBootstrapState fromKey(String value) {
        if (value == null) return NEEDS_SELECTION;
        try {
            return valueOf(value);
        }
        catch (IllegalArgumentException error) {
            return NEEDS_SELECTION;
        }
    }
}
