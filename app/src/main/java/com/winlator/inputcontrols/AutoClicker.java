package com.winlator.inputcontrols;

import android.os.Handler;
import android.os.Looper;

import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

/**
 * Repeatedly injects a left mouse click at a fixed, percentage-based location of the guest
 * screen while active. Runs entirely on the main (UI) thread, mirroring how touch input is
 * injected, so no additional synchronization with the X server is required.
 */
public class AutoClicker {
    public static final int MIN_INTERVAL_MS = 50;
    private static final long PRESS_HOLD_MS = 30;

    private final XServer xServer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = false;
    private int guestX = 0;
    private int guestY = 0;
    private int intervalMs = 1000;

    private final Runnable pressRunnable = this::doPress;
    private final Runnable releaseRunnable = this::doRelease;

    public AutoClicker(XServer xServer) {
        this.xServer = xServer;
    }

    public void configure(int guestX, int guestY, int intervalMs) {
        this.guestX = guestX;
        this.guestY = guestY;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(pressRunnable);
        handler.removeCallbacks(releaseRunnable);
        handler.post(pressRunnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pressRunnable);
        handler.removeCallbacks(releaseRunnable);
        releaseButtonIfPressed();
    }

    public boolean toggle() {
        if (running) stop();
        else start();
        return running;
    }

    private void doPress() {
        if (!running) return;
        xServer.injectPointerMove(guestX, guestY);
        if (!xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT))
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        handler.postDelayed(releaseRunnable, PRESS_HOLD_MS);
    }

    private void doRelease() {
        releaseButtonIfPressed();
        if (running) handler.postDelayed(pressRunnable, Math.max(0, intervalMs - PRESS_HOLD_MS));
    }

    private void releaseButtonIfPressed() {
        if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT))
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
    }
}
