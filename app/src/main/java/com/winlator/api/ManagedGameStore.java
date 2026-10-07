package com.winlator.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.winlator.container.ContainerOperationLock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

final class ManagedGameStore {
    private static final String PREFERENCES_NAME = "managed_game_api";
    private static final String KEY_REGISTRY = "registry";
    private static final int SCHEMA_VERSION = 2;

    private final SharedPreferences preferences;

    ManagedGameStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    ManagedGame get(String id) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            JSONObject games = loadGames();
            JSONObject data = games.optJSONObject(id);
            return data != null ? ManagedGame.fromJSONObject(data) : null;
        }
    }

    ArrayList<ManagedGame> list() throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            JSONObject games = loadGames();
            ArrayList<ManagedGame> result = new ArrayList<>();
            for (Iterator<String> keys = games.keys(); keys.hasNext(); ) {
                String id = keys.next();
                result.add(ManagedGame.fromJSONObject(games.getJSONObject(id)));
            }
            result.sort((first, second) -> first.title.compareToIgnoreCase(second.title));
            return result;
        }
    }

    void put(ManagedGame game) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            JSONObject root = loadRoot();
            JSONObject games = root.getJSONObject("games");
            games.put(game.id, game.toJSONObject());
            save(root);
        }
    }

    void remove(String id) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            JSONObject root = loadRoot();
            root.getJSONObject("games").remove(id);
            save(root);
        }
    }

        void putSharedGame(ManagedGame game, String key, int containerId)
                throws JSONException, IOException {
            synchronized (ContainerOperationLock.LOCK) {
                JSONObject root = loadRoot();
                JSONObject sharedContainers = root.getJSONObject("sharedContainers");
                if (sharedContainers.has(key)
                        && sharedContainers.getInt(key) != containerId) {
                    throw new IOException("Shared container key is already bound.");
                }
                root.getJSONObject("games").put(game.id, game.toJSONObject());
                sharedContainers.put(key, containerId);
                save(root);
            }
        }

        void bindSharedContainer(String key, int containerId)
                throws JSONException, IOException {
            synchronized (ContainerOperationLock.LOCK) {
                JSONObject root = loadRoot();
                JSONObject sharedContainers = root.getJSONObject("sharedContainers");
                if (sharedContainers.has(key)
                        && sharedContainers.getInt(key) != containerId) {
                    throw new IOException("Shared container key is already bound.");
                }
                sharedContainers.put(key, containerId);
                save(root);
            }
        }

        Integer getSharedContainerId(String key) throws JSONException, IOException {
            synchronized (ContainerOperationLock.LOCK) {
                JSONObject sharedContainers = loadRoot().getJSONObject("sharedContainers");
                return sharedContainers.has(key) ? sharedContainers.getInt(key) : null;
            }
        }

        void clearSharedContainer(String key, int expectedContainerId)
                throws JSONException, IOException {
            synchronized (ContainerOperationLock.LOCK) {
                JSONObject root = loadRoot();
                JSONObject sharedContainers = root.getJSONObject("sharedContainers");
                if (sharedContainers.optInt(key, -1) != expectedContainerId) return;
                sharedContainers.remove(key);
                save(root);
            }
        }

        int countContainerReferences(int containerId) throws JSONException, IOException {
            synchronized (ContainerOperationLock.LOCK) {
                int count = 0;
                for (ManagedGame game : list()) {
                    if (game.containerId == containerId) count++;
                }
                return count;
            }
        }

        ContainerUsage getContainerUsage(int containerId) throws JSONException, IOException {
            synchronized (ContainerOperationLock.LOCK) {
                JSONObject root = loadRoot();
                JSONObject games = root.getJSONObject("games");
                JSONObject sharedContainers = root.getJSONObject("sharedContainers");
                int referenceCount = 0;
                ArrayList<String> titles = new ArrayList<>();
                boolean shared = false;
                String containerKey = null;

                for (Iterator<String> keys = games.keys(); keys.hasNext(); ) {
                    ManagedGame game = ManagedGame.fromJSONObject(
                            games.getJSONObject(keys.next())
                    );
                    if (game.containerId != containerId) continue;
                    referenceCount++;
                    titles.add(game.title);
                    if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(game.containerPolicy)) {
                        shared = true;
                        if (containerKey == null) containerKey = game.containerKey;
                    }
                }
                for (Iterator<String> keys = sharedContainers.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    if (sharedContainers.getInt(key) == containerId) {
                        shared = true;
                        containerKey = key;
                        break;
                    }
                }
                return new ContainerUsage(referenceCount, shared, containerKey, titles);
            }
        }
    private JSONObject loadGames() throws JSONException, IOException {
        return loadRoot().getJSONObject("games");
    }

    private JSONObject loadRoot() throws JSONException, IOException {
        String text = preferences.getString(KEY_REGISTRY, null);
        if (text == null || text.isEmpty()) {
            return newRoot();
        }

        JSONObject root = new JSONObject(text);
        int schemaVersion = root.optInt("schemaVersion", 0);
        if (schemaVersion == 1) {
            root.put("schemaVersion", SCHEMA_VERSION);
            if (!root.has("games")) root.put("games", new JSONObject());
            root.put("sharedContainers", new JSONObject());
            migrateLegacyGames(root.getJSONObject("games"));
            save(root);
            return root;
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new JSONException("Unsupported managed-game registry schema");
        }
        if (!root.has("games")) root.put("games", new JSONObject());
        if (!root.has("sharedContainers")) root.put("sharedContainers", new JSONObject());
        return root;
    }

    private JSONObject newRoot() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("games", new JSONObject());
        root.put("sharedContainers", new JSONObject());
        return root;
    }

    private void migrateLegacyGames(JSONObject games) throws JSONException {
        for (Iterator<String> keys = games.keys(); keys.hasNext(); ) {
            String id = keys.next();
            JSONObject game = games.getJSONObject(id);
            if (!game.has("containerPolicy")) {
                game.put("containerPolicy", ManagedGame.CONTAINER_POLICY_ISOLATED);
            }
        }
    }

    private void save(JSONObject root) throws IOException {
        String previous = preferences.getString(KEY_REGISTRY, null);
        if (!preferences.edit().putString(KEY_REGISTRY, root.toString()).commit()) {
            SharedPreferences.Editor rollback = preferences.edit();
            if (previous != null) {
                rollback.putString(KEY_REGISTRY, previous);
            }
            else {
                rollback.remove(KEY_REGISTRY);
            }
            rollback.commit();
            throw new IOException("Unable to persist managed-game registry");
        }
    }

    static final class ContainerUsage {
        final int referenceCount;
        final boolean shared;
        final String containerKey;
        final ArrayList<String> titles;

        ContainerUsage(
                int referenceCount,
                boolean shared,
                String containerKey,
                ArrayList<String> titles
        ) {
            this.referenceCount = referenceCount;
            this.shared = shared;
            this.containerKey = containerKey;
            this.titles = titles;
        }
    }
}
