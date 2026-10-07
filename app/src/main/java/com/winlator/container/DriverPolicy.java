package com.winlator.container;

public abstract class DriverPolicy {
    /** Use the versions stored in the container/game config as-is (no override). */
    public static final String DEFAULT = "default";
    /** Resolve driver components to the newest installed version at launch. */
    public static final String LATEST = "latest";
    /** Use explicit driver versions pinned in the config. */
    public static final String SPECIFIC = "specific";

    public static boolean isValid(String value) {
        return DEFAULT.equals(value) || LATEST.equals(value) || SPECIFIC.equals(value);
    }

    public static String normalize(String value) {
        return isValid(value) ? value : DEFAULT;
    }
}
