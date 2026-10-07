package com.winlator.container;

public abstract class DesktopMode {
    /** Choose automatically: single-app (nogui) for launched games, shell otherwise. */
    public static final String AUTO = "auto";
    /** Borderless single-application desktop (wine explorer /desktop=nogui). */
    public static final String NOGUI = "nogui";
    /** Full Winlator/Wine desktop with a shell (wine explorer /desktop=shell). */
    public static final String SHELL = "shell";

    public static boolean isValid(String value) {
        return AUTO.equals(value) || NOGUI.equals(value) || SHELL.equals(value);
    }

    public static String normalize(String value) {
        return isValid(value) ? value : AUTO;
    }
}
