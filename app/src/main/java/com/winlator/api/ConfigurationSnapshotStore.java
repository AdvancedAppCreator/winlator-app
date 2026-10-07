package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;

final class ConfigurationSnapshotStore {
    private static final String PREFERENCES = "managed_game_snapshots";
    private static final String KEY_STORE = "store_v1";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PER_GAME = 20;
    private static final Object LOCK = new Object();

    private final Context context;
    private final SharedPreferences preferences;

    ConfigurationSnapshotStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    JSONObject create(
            ManagedGame game,
            String label,
            String reason
    ) throws JSONException, IOException {
        if (game == null) throw new IllegalArgumentException("A managed game is required.");
        synchronized (LOCK) {
            JSONObject root = loadRoot();
            JSONArray snapshots = root.getJSONObject("games")
                    .optJSONArray(game.id);
            if (snapshots == null) snapshots = new JSONArray();
            JSONObject settings = GameSettingsSchema.effective(context, game);
            Container container = new ContainerManager(context).getContainerById(game.containerId);
            JSONObject config = container != null
                    ? GameApiJson.managedConfig(game, container)
                    : game.configJson != null
                            ? new JSONObject(game.configJson)
                            : new JSONObject();
            JSONObject snapshot = new JSONObject()
                    .put("snapshotId", UUID.randomUUID().toString())
                    .put("gameId", game.id)
                    .put("label", label == null ? "" : label.trim())
                    .put("reason", reason == null ? "manual" : reason)
                    .put("createdAt", System.currentTimeMillis())
                    .put("configJson", config)
                    .put("configSha256", GameConfigSchema.hash(config))
                    .put("settingsJson", settings)
                    .put("settingsSha256", GameSettingsSchema.hash(settings));
            if (container != null) ManagedWinVersion.putMetadata(snapshot, game, container);
            prepend(snapshots, snapshot);
            while (snapshots.length() > MAX_PER_GAME) {
                snapshots.remove(snapshots.length() - 1);
            }
            root.getJSONObject("games").put(game.id, snapshots);
            save(root);
            return new JSONObject(snapshot.toString());
        }
    }

    JSONArray list(String gameId) throws JSONException, IOException {
        synchronized (LOCK) {
            JSONArray stored = loadRoot().getJSONObject("games")
                    .optJSONArray(gameId);
            return stored != null
                    ? new JSONArray(stored.toString())
                    : new JSONArray();
        }
    }

    JSONObject get(String gameId, String snapshotId)
            throws JSONException, IOException {
        JSONArray snapshots = list(gameId);
        for (int index = 0; index < snapshots.length(); index++) {
            JSONObject snapshot = snapshots.getJSONObject(index);
            if (snapshotId.equals(snapshot.optString("snapshotId"))) {
                return snapshot;
            }
        }
        return null;
    }

    ManagedGame restore(String gameId, String snapshotId)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            JSONObject snapshot = get(gameId, snapshotId);
            if (snapshot == null) {
                throw new IllegalArgumentException("The configuration snapshot was not found.");
            }
            JSONObject config = snapshot.getJSONObject("configJson");
            JSONObject settings = snapshot.getJSONObject("settingsJson");
            GameConfigSchema.validateLegacyPatch(context, config);
            GameSettingsSchema.applyUpdate(
                    context,
                    GameSettingsSchema.defaults(context),
                    settings
            );
            ManagedGameStore gameStore = new ManagedGameStore(context);
            ManagedGame game = gameStore.get(gameId);
            if (game == null) throw new IllegalArgumentException("The managed game was not found.");
            game.configJson = config.toString();
            game.winVersionSource = snapshot.optString("winVersionSource", "");
            if (game.winVersionSource.isEmpty()) {
                game.winVersionSource = config.has("winVersion")
                        ? ManagedGame.WIN_VERSION_SOURCE_EXPLICIT
                        : null;
            }
            game.settingsJson = settings.toString();
            game.updatedAt = System.currentTimeMillis();
            gameStore.put(game);
            return game;
        }
    }

    private JSONObject loadRoot() throws JSONException {
        String text = preferences.getString(KEY_STORE, "");
        if (text.isEmpty()) {
            return new JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("games", new JSONObject());
        }
        JSONObject root = new JSONObject(text);
        if (root.optInt("schemaVersion", 0) != SCHEMA_VERSION) {
            throw new JSONException("Unsupported configuration snapshot schema.");
        }
        return root;
    }

    private void save(JSONObject root) throws IOException {
        String previous = preferences.getString(KEY_STORE, null);
        if (preferences.edit().putString(KEY_STORE, root.toString()).commit()) return;
        SharedPreferences.Editor rollback = preferences.edit();
        if (previous != null) rollback.putString(KEY_STORE, previous);
        else rollback.remove(KEY_STORE);
        rollback.commit();
        throw new IOException("Unable to persist configuration snapshots.");
    }

    private static void prepend(JSONArray values, JSONObject value)
            throws JSONException {
        for (int index = values.length(); index > 0; index--) {
            values.put(index, values.get(index - 1));
        }
        values.put(0, value);
    }
}
