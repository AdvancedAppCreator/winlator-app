package com.winlator.api;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;

public class DeferredGameLaunchService extends IntentService {
    private static final String EXTRA_LAUNCH_INTENT =
            "com.winlator.secure.internal.LAUNCH_INTENT";
    // Give the caller's result/UI transition time to settle before we bring up the session Activity.
    private static final long INITIAL_LAUNCH_DELAY_MS = 300;
    // Poll until XServerDisplayActivity claims the launch reservation (≈10s worst case).
    private static final int CLAIM_POLL_ATTEMPTS = 100;
    private static final long CLAIM_POLL_INTERVAL_MS = 100;

    public DeferredGameLaunchService() {
        super("DeferredGameLaunchService");
    }

    static void enqueue(Context context, Intent launchIntent) {
        Intent serviceIntent = new Intent(context, DeferredGameLaunchService.class);
        serviceIntent.putExtra(EXTRA_LAUNCH_INTENT, launchIntent);
        context.startService(serviceIntent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Intent launchIntent = intent != null
                ? intent.getParcelableExtra(EXTRA_LAUNCH_INTENT)
                : null;
        if (launchIntent == null) {
            GameManagerActivity.notifySessionLaunchFailed();
            return;
        }

        try {
            Thread.sleep(INITIAL_LAUNCH_DELAY_MS);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
            String token = launchIntent.getStringExtra(
                    GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN
            );
            for (int attempt = 0; attempt < CLAIM_POLL_ATTEMPTS; attempt++) {
                if (!GameManagerActivity.isLaunchReservationPending(token)) return;
                Thread.sleep(CLAIM_POLL_INTERVAL_MS);
            }
            reportLaunchFailure(launchIntent);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reportLaunchFailure(launchIntent);
        }
        catch (RuntimeException e) {
            reportLaunchFailure(launchIntent);
        }
    }

    private void reportLaunchFailure(Intent launchIntent) {
        String token = launchIntent.getStringExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN);
        if (!GameManagerActivity.notifySessionLaunchFailed(token)) return;
        GameSessionEventReporter.failToStart(
                this,
                launchIntent,
                GameApiContract.ERROR_LAUNCH_FAILED,
                "Winlator could not start the requested Wine session."
        );
    }
}
