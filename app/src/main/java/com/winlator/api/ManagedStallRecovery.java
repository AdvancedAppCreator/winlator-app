package com.winlator.api;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Public entry point that lets the in-session overlay apply a realtime stall suggestion.
 * Wraps the package-private {@link ManagedGameRuntimeOperations#applyConfig} so callers
 * outside the {@code com.winlator.api} package (the display activity) can persist a fix.
 * The caller is responsible for restarting the session afterwards.
 */
public final class ManagedStallRecovery {
    private ManagedStallRecovery() {
    }

    /**
     * Persists the suggestion's configuration change against the managed game and its
     * container. Returns {@code true} on success.
     *
     * @param baseConfigSha256 hash the suggestion was built against; used to reject the
     *                         change if the configuration has since drifted.
     */
    public static boolean applyConfig(
            Context context,
            String gameId,
            String baseConfigSha256,
            JSONObject set
    ) throws JSONException, IOException {
        if (context == null || gameId == null || gameId.isEmpty() || set == null) return false;
        ManagedGameRuntimeOperations.applyConfig(
                context.getApplicationContext(),
                gameId,
                baseConfigSha256,
                set
        );
        return true;
    }
}
