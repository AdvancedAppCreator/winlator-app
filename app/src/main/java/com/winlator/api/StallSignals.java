package com.winlator.api;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of the live signals used to detect a black-screen / hang state.
 * All timestamps are in the {@link System#currentTimeMillis()} domain. A value of
 * {@code 0} for {@link #lastFrameTime} means "no frame has ever been presented".
 */
final class StallSignals {
    final long now;
    final long startedAt;
    final long lastGuestOutputAt;
    final long lastFrameTime;
    final boolean runtimeReached;
    final List<String> recentOutput;
    final JSONObject appliedConfig;
    final String appliedConfigSha256;

    StallSignals(
            long now,
            long startedAt,
            long lastGuestOutputAt,
            long lastFrameTime,
            boolean runtimeReached,
            List<String> recentOutput,
            JSONObject appliedConfig,
            String appliedConfigSha256
    ) {
        this.now = now;
        this.startedAt = startedAt;
        this.lastGuestOutputAt = lastGuestOutputAt;
        this.lastFrameTime = lastFrameTime;
        this.runtimeReached = runtimeReached;
        this.recentOutput = recentOutput != null
                ? Collections.unmodifiableList(new ArrayList<>(recentOutput))
                : Collections.emptyList();
        this.appliedConfig = appliedConfig;
        this.appliedConfigSha256 = appliedConfigSha256 != null ? appliedConfigSha256 : "";
    }

    long elapsedMillis() {
        return Math.max(0, now - startedAt);
    }

    long outputSilenceMillis() {
        long reference = lastGuestOutputAt > 0 ? lastGuestOutputAt : startedAt;
        return Math.max(0, now - reference);
    }

    /**
     * Milliseconds since the last presented frame, or {@code -1} when no frame has ever
     * been presented (frame liveness is unknown / not applicable).
     */
    long frameSilenceMillis() {
        if (lastFrameTime <= 0) return -1;
        return Math.max(0, now - lastFrameTime);
    }
}
