package com.winlator.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class ApiScope {
    static final String READ = "read";
    static final String MANAGE_GAMES = "manage_games";
    static final String SETTINGS = "settings";
    static final String LAUNCH = "launch";
    static final String MODS = "mods";
    static final String DEPENDENCIES = "dependencies";
    static final String RECOVERY = "recovery";
    static final String GLOBAL_UI = "global_ui";

    static final Set<String> ALL = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    READ,
                    MANAGE_GAMES,
                    SETTINGS,
                    LAUNCH,
                    MODS,
                    DEPENDENCIES,
                    RECOVERY,
                    GLOBAL_UI
            )
    ));

    private ApiScope() {
    }
}
