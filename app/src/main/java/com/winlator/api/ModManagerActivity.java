package com.winlator.api;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.winlator.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-managed-game mod manager UI.
 *
 * <p>Launch with {@code Intent} that includes
 * {@link GameApiContract#EXTRA_GAME_ID} pointing to a registered
 * {@link ManagedGame}. The parent integrates this activity into the
 * AndroidManifest.</p>
 *
 * <p>All UI strings are programmatic to avoid requiring edits to
 * {@code strings.xml}.</p>
 */
public class ModManagerActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_ZIP = 1001;

    private ManagedGame     game;
    private ModManager      manager;
    private ModAdapter      adapter;
    private ListView        listView;
    private TextView        emptyView;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.mod_manager_activity);

        executor = Executors.newSingleThreadExecutor();

        String gameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
        if (gameId == null || gameId.isEmpty()) {
            Toast.makeText(this, "Missing game_id.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            ManagedGameStore store = new ManagedGameStore(this);
            game = store.get(gameId);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load game: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (game == null) {
            Toast.makeText(this, "Game not found: " + gameId, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        manager = new ModManager(this, game);

        Toolbar toolbar = findViewById(R.id.mod_manager_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Mods – " + game.title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Import button in toolbar overflow.
        toolbar.inflateMenu(0); // no menu XML; add action programmatically
        toolbar.setOnMenuItemClickListener(item -> false);

        View importBtn = buildToolbarImportButton(toolbar);
        toolbar.addView(importBtn);

        listView  = findViewById(R.id.mod_manager_list);
        emptyView = findViewById(R.id.mod_manager_empty);
        emptyView.setText("No mods installed.\nTap Import to add a mod ZIP.");

        adapter = new ModAdapter();
        listView.setAdapter(adapter);

        refreshList();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // -------------------------------------------------------------------------
    // File picker
    // -------------------------------------------------------------------------

    private View buildToolbarImportButton(Toolbar toolbar) {
        // Reuse existing icon_install drawable which is already present.
        ImageView btn = new ImageView(this);
        Toolbar.LayoutParams lp = new Toolbar.LayoutParams(
                (int) (42 * getResources().getDisplayMetrics().density),
                (int) (42 * getResources().getDisplayMetrics().density));
        btn.setLayoutParams(lp);
        btn.setImageResource(R.drawable.icon_install);
        btn.setPadding(8, 8, 8, 8);
        btn.setContentDescription("Import mod ZIP");
        btn.setBackgroundResource(android.R.attr.selectableItemBackgroundBorderless);
        btn.setOnClickListener(v -> promptImport());
        return btn;
    }

    private void promptImport() {
        final EditText nameInput = new EditText(this);
        nameInput.setHint("Mod name (optional)");
        new AlertDialog.Builder(this)
                .setTitle("Import Mod")
                .setMessage("Enter a display name (optional), then pick the ZIP file.")
                .setView(nameInput)
                .setPositiveButton("Pick ZIP", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    pendingModName = name;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"application/zip", "application/x-zip-compressed"});
                    startActivityForResult(intent, REQUEST_PICK_ZIP);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String pendingModName;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_ZIP && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            Uri zipUri = data.getData();
            // Persist read permission for the duration of the import.
            getContentResolver().takePersistableUriPermission(
                    zipUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runImport(zipUri, pendingModName);
        }
    }

    // -------------------------------------------------------------------------
    // Background operations
    // -------------------------------------------------------------------------

    private void runImport(Uri zipUri, String modName) {
        showLoading("Importing mod…");
        executor.execute(() -> {
            ModManager.Result result = manager.importMod(zipUri, modName);
            runOnUiThread(() -> {
                hideLoading();
                if (result.success) {
                    toast("Mod imported successfully.");
                } else {
                    toast("Import failed: " + result.error);
                }
                refreshList();
            });
        });
    }

    private void runEnable(String modId) {
        showLoading("Applying mod…");
        executor.execute(() -> {
            ModManager.Result result = manager.enableMod(modId);
            runOnUiThread(() -> {
                hideLoading();
                if (!result.success) toast("Enable failed: " + result.error);
                refreshList();
            });
        });
    }

    private void runDisable(String modId) {
        showLoading("Rolling back mod…");
        executor.execute(() -> {
            ModManager.Result result = manager.disableMod(modId);
            runOnUiThread(() -> {
                hideLoading();
                if (!result.success) toast("Disable failed: " + result.error);
                refreshList();
            });
        });
    }

    private void confirmRemove(String modId, String modName) {
        new AlertDialog.Builder(this)
                .setTitle("Remove Mod")
                .setMessage("Remove \"" + modName + "\"? This will roll back its changes.")
                .setPositiveButton("Remove", (d, w) -> runRemove(modId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runRemove(String modId) {
        showLoading("Removing mod…");
        executor.execute(() -> {
            ModManager.Result result = manager.removeMod(modId);
            runOnUiThread(() -> {
                hideLoading();
                if (!result.success) toast("Remove failed: " + result.error);
                refreshList();
            });
        });
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private View loadingOverlay;

    private void showLoading(String message) {
        // Simple Toast-based loading indicator; a ProgressDialog would require
        // Fragment support which is outside scope.
        toast(message);
    }

    private void hideLoading() {
        // No persistent overlay to dismiss.
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void refreshList() {
        ArrayList<ModEntry> mods;
        ArrayList<String> conflicting;
        try {
            mods = manager.listMods();
            conflicting = manager.conflictingModIds();
        }
        catch (IllegalStateException error) {
            toast(error.getMessage());
            listView.setVisibility(View.GONE);
            emptyView.setText(error.getMessage());
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        adapter.setData(mods, conflicting);
        if (mods.isEmpty()) {
            listView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            listView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private class ModAdapter extends BaseAdapter {
        private ArrayList<ModEntry> mods       = new ArrayList<>();
        private ArrayList<String>   conflicting = new ArrayList<>();

        void setData(ArrayList<ModEntry> mods, ArrayList<String> conflicting) {
            this.mods        = mods;
            this.conflicting = conflicting;
            notifyDataSetChanged();
        }

        @Override public int     getCount()              { return mods.size(); }
        @Override public Object  getItem(int pos)        { return mods.get(pos); }
        @Override public long    getItemId(int pos)      { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(
                        R.layout.mod_manager_list_item, parent, false);
            }
            ModEntry entry = mods.get(position);
            boolean hasConflict = conflicting.contains(entry.id);

            TextView nameView   = row.findViewById(R.id.mod_item_name);
            TextView statusView = row.findViewById(R.id.mod_item_status);
            ImageView toggle    = row.findViewById(R.id.mod_item_toggle);
            ImageView remove    = row.findViewById(R.id.mod_item_remove);
            View conflictDot    = row.findViewById(R.id.mod_item_conflict_dot);

            nameView.setText(entry.name);

            String statusText;
            boolean isEnabled;
            switch (entry.state) {
                case ModEntry.STATE_ENABLED:
                    statusText = hasConflict ? "Enabled  ⚠ conflict" : "Enabled";
                    isEnabled = true;
                    break;
                case ModEntry.STATE_DISABLED:
                    statusText = "Disabled";
                    isEnabled = false;
                    break;
                case ModEntry.STATE_PENDING:
                    statusText = "Pending (not applied)";
                    isEnabled = false;
                    break;
                default:
                    statusText = entry.state;
                    isEnabled = false;
            }
            statusView.setText(statusText);
            conflictDot.setVisibility(hasConflict ? View.VISIBLE : View.GONE);
            toggle.setImageResource(isEnabled
                    ? R.drawable.toggle_button_on
                    : R.drawable.toggle_button_off);

            toggle.setOnClickListener(v -> {
                if (isEnabled) {
                    runDisable(entry.id);
                } else {
                    runEnable(entry.id);
                }
            });
            remove.setOnClickListener(v -> confirmRemove(entry.id, entry.name));
            return row;
        }
    }
}
