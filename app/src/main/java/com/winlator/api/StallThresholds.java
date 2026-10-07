package com.winlator.api;

/**
 * Time thresholds that govern when the realtime watchdog treats a live session as stalled.
 * Kept separate from the classifier so tests can drive deterministic timing and so
 * {@link com.winlator.MainActivity#DEBUG_MODE} can shorten them for manual UI testing.
 */
final class StallThresholds {
    /** Grace period before we consider a window-less session potentially stuck. */
    final long softStallMillis;
    /** Point past which a very slow load is surfaced even while guest output continues. */
    final long hardStallMillis;
    /** Guest-output silence that indicates the startup pipeline is no longer progressing. */
    final long outputSilenceMillis;
    /** Frame silence (after a window mapped) that indicates presentation has stalled. */
    final long frameSilenceMillis;

    StallThresholds(
            long softStallMillis,
            long hardStallMillis,
            long outputSilenceMillis,
            long frameSilenceMillis
    ) {
        this.softStallMillis = softStallMillis;
        this.hardStallMillis = hardStallMillis;
        this.outputSilenceMillis = outputSilenceMillis;
        this.frameSilenceMillis = frameSilenceMillis;
    }

    static StallThresholds defaults() {
        return new StallThresholds(25000, 60000, 12000, 8000);
    }

    /** Compressed timings for DEBUG_MODE so the overlay can be exercised quickly. */
    static StallThresholds debug() {
        return new StallThresholds(6000, 15000, 4000, 4000);
    }
}
