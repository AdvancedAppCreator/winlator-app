package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class ManagedGameStoreTest {
    private Context context;
    private SharedPreferences preferences;
    private ManagedGameStore store;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("managed_game_api", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        store = new ManagedGameStore(context);
    }

    @After
    public void tearDown() {
        preferences.edit().clear().commit();
    }

    @Test
    public void legacyRegistryMigratesWithoutDataLoss() throws Exception {
        ManagedGame legacy = game("legacy", 4);
        JSONObject legacyData = legacy.toJSONObject();
        legacyData.remove("containerPolicy");
        JSONObject games = new JSONObject();
        games.put(legacy.id, legacyData);
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("games", games);
        preferences.edit().putString("registry", root.toString()).commit();

        ManagedGame migrated = store.get("legacy");

        assertEquals(4, migrated.containerId);
        assertEquals(ManagedGame.CONTAINER_POLICY_ISOLATED, migrated.containerPolicy);
        JSONObject persisted = new JSONObject(preferences.getString("registry", ""));
        assertEquals(2, persisted.getInt("schemaVersion"));
        assertTrue(persisted.has("sharedContainers"));
        assertEquals("Game legacy", persisted.getJSONObject("games")
                .getJSONObject("legacy").getString("title"));
    }

    @Test
    public void firstAndAdditionalSharedGamesUseStableContainerBinding() throws Exception {
        ManagedGame first = sharedGame("first", 7, ManagedGame.DEFAULT_SHARED_CONTAINER_KEY);
        ManagedGame second = sharedGame("second", 7, ManagedGame.DEFAULT_SHARED_CONTAINER_KEY);

        store.putSharedGame(first, first.containerKey, first.containerId);
        store.putSharedGame(second, second.containerKey, second.containerId);

        assertEquals(Integer.valueOf(7), store.getSharedContainerId(first.containerKey));
        ManagedGameStore.ContainerUsage usage = store.getContainerUsage(7);
        assertTrue(usage.shared);
        assertEquals(2, usage.referenceCount);
        assertEquals(ManagedGame.DEFAULT_SHARED_CONTAINER_KEY, usage.containerKey);
    }

    @Test
    public void isolatedGamesKeepIndependentContainerIds() throws Exception {
        ManagedGame first = game("first", 10);
        ManagedGame second = game("second", 11);
        store.put(first);
        store.put(second);

        assertEquals(10, store.get("first").containerId);
        assertEquals(11, store.get("second").containerId);
        assertFalse(store.getContainerUsage(10).shared);
        assertFalse(store.getContainerUsage(11).shared);
    }

    @Test
    public void deletingSharedGamesPreservesContainerBinding() throws Exception {
        ManagedGame first = sharedGame("first", 7, ManagedGame.DEFAULT_SHARED_CONTAINER_KEY);
        ManagedGame second = sharedGame("second", 7, ManagedGame.DEFAULT_SHARED_CONTAINER_KEY);
        store.putSharedGame(first, first.containerKey, first.containerId);
        store.putSharedGame(second, second.containerKey, second.containerId);

        store.remove(first.id);
        assertEquals(1, store.getContainerUsage(7).referenceCount);
        assertEquals(Integer.valueOf(7), store.getSharedContainerId(first.containerKey));

        store.remove(second.id);
        ManagedGameStore.ContainerUsage usage = store.getContainerUsage(7);
        assertEquals(0, usage.referenceCount);
        assertTrue(usage.shared);
        assertEquals(Integer.valueOf(7), store.getSharedContainerId(first.containerKey));
    }

    @Test
    public void movingOneSharedGameLeavesOtherGamesOnOriginalContainer() throws Exception {
        ManagedGame first = sharedGame("first", 7, ManagedGame.DEFAULT_SHARED_CONTAINER_KEY);
        ManagedGame second = sharedGame("second", 7, ManagedGame.DEFAULT_SHARED_CONTAINER_KEY);
        store.putSharedGame(first, first.containerKey, first.containerId);
        store.putSharedGame(second, second.containerKey, second.containerId);

        first.containerId = 12;
        first.containerPolicy = ManagedGame.CONTAINER_POLICY_ISOLATED;
        first.containerKey = null;
        store.put(first);

        assertEquals(12, store.get("first").containerId);
        assertEquals(7, store.get("second").containerId);
        assertEquals(1, store.getContainerUsage(7).referenceCount);
        assertEquals(1, store.getContainerUsage(12).referenceCount);
        assertFalse(store.getContainerUsage(12).shared);
    }

    @Test
    public void omittedPolicyRetainsLegacyIsolatedBehavior() throws Exception {
        JSONObject data = game("legacy", 20).toJSONObject();
        data.remove("containerPolicy");
        data.remove("containerKey");

        ManagedGame restored = ManagedGame.fromJSONObject(data);

        assertEquals(ManagedGame.CONTAINER_POLICY_ISOLATED, restored.containerPolicy);
        assertNull(restored.containerKey);
    }

    @Test
    public void winVersionProvenancePersistsWithoutBackfillingLegacyConfig() throws Exception {
        ManagedGame explicit = game("explicit", 21);
        explicit.configJson = new JSONObject().put("winVersion", "win7").toString();
        explicit.winVersionSource = ManagedGame.WIN_VERSION_SOURCE_EXPLICIT;
        store.put(explicit);

        ManagedGame restored = store.get(explicit.id);
        assertEquals(
                "win7",
                new JSONObject(restored.configJson).getString("winVersion")
        );
        assertEquals(
                ManagedGame.WIN_VERSION_SOURCE_EXPLICIT,
                restored.winVersionSource
        );

        ManagedGame legacy = game("legacy-unset", 22);
        legacy.configJson = new JSONObject().put("screenSize", "1280x720").toString();
        store.put(legacy);
        ManagedGame restoredLegacy = store.get(legacy.id);
        assertFalse(new JSONObject(restoredLegacy.configJson).has("winVersion"));
        assertNull(restoredLegacy.winVersionSource);
    }

    @Test
    public void creationIdentityPersistsForIdempotentCreate() throws Exception {
        ManagedGame game = game("installer", 23);
        game.executableDosPath = "C:\\Games\\Game.exe";
        game.setCreationIdentity("/storage/emulated/0/Download/setup.exe");
        store.put(game);

        ManagedGame restored = store.get(game.id);

        assertEquals(ManagedGame.CREATION_MODE_INSTALLER, restored.creationMode);
        assertTrue(restored.creationIdentitySha256 != null);
    }

    @Test
    public void concurrentSharedWritesDoNotLoseReferences() throws Exception {
        int count = 24;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            final int gameIndex = index;
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    ManagedGame game = sharedGame(
                            "game-"+gameIndex,
                            31,
                            ManagedGame.DEFAULT_SHARED_CONTAINER_KEY
                    );
                    store.putSharedGame(game, game.containerKey, game.containerId);
                }
                catch (Throwable error) {
                    synchronized (failures) {
                        failures.add(error);
                    }
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals(count, store.getContainerUsage(31).referenceCount);
        assertEquals(
                Integer.valueOf(31),
                store.getSharedContainerId(ManagedGame.DEFAULT_SHARED_CONTAINER_KEY)
        );
    }

    private ManagedGame game(String id, int containerId) {
        ManagedGame game = new ManagedGame();
        game.id = id;
        game.title = "Game "+id;
        game.containerId = containerId;
        game.containerPolicy = ManagedGame.CONTAINER_POLICY_ISOLATED;
        game.state = "ready";
        game.createdAt = 1;
        game.updatedAt = 1;
        return game;
    }

    private ManagedGame sharedGame(String id, int containerId, String key) {
        ManagedGame game = game(id, containerId);
        game.containerPolicy = ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT;
        game.containerKey = key;
        return game;
    }
}
