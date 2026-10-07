package com.winlator.api.dependency;

/**
 * Installation status of a single runtime dependency for one container.
 */
public enum DependencyStatus {
    /** No install has been attempted or tracked on this container. */
    NOT_INSTALLED("not_installed"),
    /** An install is in progress (Wine session launched). */
    INSTALLING("installing"),
    /** The most recent install attempt completed successfully. */
    INSTALLED("installed"),
    /** The most recent install attempt failed or was aborted. */
    FAILED("failed");

    private final String key;

    DependencyStatus(String key) {
        this.key = key;
    }

    public String toKey() {
        return key;
    }

    public static DependencyStatus fromKey(String key) {
        if (key == null) return NOT_INSTALLED;
        for (DependencyStatus s : values()) {
            if (s.key.equals(key)) return s;
        }
        return NOT_INSTALLED;
    }
}
