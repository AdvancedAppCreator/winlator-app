package com.winlator.api;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.core.AppUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecoveryActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private ManagedGame game;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Game recovery");
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);
        load();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        content.removeAllViews();
        addText("Loading recovery status...");
        String gameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
        executor.execute(() -> {
            try {
                ManagedGame loaded = new ManagedGameStore(this).get(gameId);
                if (loaded == null) {
                    throw new IllegalArgumentException("The managed game was not found.");
                }
                JSONObject status = ManagedRecoveryStatus.analyze(
                        gameId,
                        new ManagedDiagnosticStore(this).listForGame(gameId, 20),
                        System.currentTimeMillis()
                );
                JSONArray snapshots = new ConfigurationSnapshotStore(this).list(gameId);
                runOnUiThread(() -> show(loaded, status, snapshots));
            }
            catch (Exception error) {
                runOnUiThread(() -> {
                    content.removeAllViews();
                    addText("Recovery status could not be loaded: " + error.getMessage());
                });
            }
        });
    }

    private void show(
            ManagedGame loaded,
            JSONObject status,
            JSONArray snapshots
    ) {
        game = loaded;
        content.removeAllViews();
        addHeading(loaded.title);
        addText(status.optBoolean("crashLoopDetected")
                ? "Crash loop detected. No changes have been applied automatically."
                : "No current crash loop was detected.");
        addText(
                "Recent failures with the same configuration: " +
                        status.optInt("recentFailureCount", 0)
        );

        JSONArray suggestions = status.optJSONArray("suggestions");
        if (suggestions != null && suggestions.length() > 0) {
            addHeading("Suggested changes");
            for (int index = 0; index < suggestions.length(); index++) {
                JSONObject suggestion = suggestions.optJSONObject(index);
                if (suggestion == null) continue;
                Button button = addButton(
                        suggestion.optString("title", "Apply suggestion") +
                                " and retry"
                );
                button.setOnClickListener(view -> confirmSuggestion(suggestion));
                addText(suggestion.optString("rationale", ""));
            }
        }

        JSONObject lastKnownGood = status.optJSONObject("lastKnownGood");
        if (lastKnownGood != null) {
            Button restoreGood = addButton("Restore last-known-good and retry");
            restoreGood.setOnClickListener(view ->
                    confirmLastKnownGood(lastKnownGood));
        }

        addHeading("Configuration snapshots");
        Button create = addButton("Create snapshot");
        create.setOnClickListener(view -> createSnapshot());
        if (snapshots.length() == 0) addText("No snapshots saved.");
        for (int index = 0; index < snapshots.length(); index++) {
            JSONObject snapshot = snapshots.optJSONObject(index);
            if (snapshot == null) continue;
            String label = snapshot.optString("label", "");
            Button restore = addButton(
                    "Restore " + (label.isEmpty()
                            ? snapshot.optString("snapshotId")
                            : label)
            );
            restore.setOnClickListener(view -> confirmSnapshot(snapshot));
        }
    }

    private void confirmSuggestion(JSONObject suggestion) {
        confirm(
                suggestion.optString("title", "Apply suggested settings") +
                        " and launch the game?",
                () -> runRecovery(() -> {
                    snapshotBeforeChange("Before suggested recovery");
                    return ManagedGameRuntimeOperations.applyConfig(
                            this,
                            game.id,
                            suggestion.getString("baseConfigSha256"),
                            suggestion.getJSONObject("set")
                    );
                })
        );
    }

    private void confirmLastKnownGood(JSONObject lastKnownGood) {
        confirm(
                "Restore the last-known-good compatibility settings and launch the game?",
                () -> runRecovery(() -> {
                    snapshotBeforeChange("Before last-known-good restore");
                    JSONObject current = ManagedGameRuntimeOperations.currentConfig(
                            this,
                            game
                    );
                    return ManagedGameRuntimeOperations.applyConfig(
                            this,
                            game.id,
                            GameConfigSchema.hash(current),
                            lastKnownGood.getJSONObject("configJson")
                    );
                })
        );
    }

    private void confirmSnapshot(JSONObject snapshot) {
        confirm(
                "Restore this snapshot and launch the game?",
                () -> runRecovery(() -> {
                    snapshotBeforeChange("Before snapshot restore");
                    return new ConfigurationSnapshotStore(this).restore(
                            game.id,
                            snapshot.getString("snapshotId")
                    );
                })
        );
    }

    private void createSnapshot() {
        executor.execute(() -> {
            try {
                new ConfigurationSnapshotStore(this).create(
                        game,
                        "Manual " + System.currentTimeMillis(),
                        "manual"
                );
                runOnUiThread(this::load);
            }
            catch (Exception error) {
                runOnUiThread(() -> AppUtils.showToast(this, error.getMessage()));
            }
        });
    }

    private void snapshotBeforeChange(String label) throws Exception {
        new ConfigurationSnapshotStore(this).create(game, label, "recovery");
    }

    private void runRecovery(RecoveryOperation operation) {
        setControlsEnabled(false);
        executor.execute(() -> {
            try {
                ManagedGame recovered = operation.run();
                Intent launch =
                        ManagedGameRuntimeOperations.prepareLaunch(this, recovered);
                runOnUiThread(() -> {
                    try {
                        ManagedGameRuntimeOperations.startPreparedLaunch(this, launch);
                        finish();
                    }
                    catch (Exception error) {
                        setControlsEnabled(true);
                        AppUtils.showToast(this, error.getMessage());
                    }
                });
            }
            catch (Exception error) {
                runOnUiThread(() -> {
                    setControlsEnabled(true);
                    AppUtils.showToast(this, error.getMessage());
                    load();
                });
            }
        });
    }

    private void confirm(String message, Runnable confirmed) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm recovery")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Apply and retry", (dialog, which) -> confirmed.run())
                .show();
    }

    private void setControlsEnabled(boolean enabled) {
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            if (child instanceof Button) child.setEnabled(enabled);
        }
    }

    private void addHeading(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(20);
        view.setPadding(0, dp(14), 0, dp(6));
        content.addView(view);
    }

    private void addText(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setPadding(0, dp(4), 0, dp(8));
        content.addView(view);
    }

    private Button addButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        content.addView(button);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface RecoveryOperation {
        ManagedGame run() throws Exception;
    }
}
