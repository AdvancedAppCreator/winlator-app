package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.winlator.api.dependency.DependencyInstallRecord;
import com.winlator.api.dependency.DependencyStatus;
import com.winlator.api.dependency.RuntimeDependencyCatalog;
import com.winlator.api.dependency.RuntimeDependencyManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Tests for {@link RuntimeDependencyManager} logic that requires access to
 * package-private {@link ManagedGame} / {@link ManagedGameStore} for test data setup.
 *
 * Covers: affected-game lookup, installer path validation, status lifecycle.
 */
@RunWith(RobolectricTestRunner.class)
public class RuntimeDependencyManagerTest {

    private Context context;
    private SharedPreferences gamePrefs;
    private SharedPreferences depPrefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        gamePrefs = context.getSharedPreferences("managed_game_api",  Context.MODE_PRIVATE);
        depPrefs  = context.getSharedPreferences("runtime_dep_store", Context.MODE_PRIVATE);
        gamePrefs.edit().clear().commit();
        depPrefs.edit().clear().commit();
    }

    @After
    public void tearDown() {
        gamePrefs.edit().clear().commit();
        depPrefs.edit().clear().commit();
    }

    // ── findAffectedGames ─────────────────────────────────────────────────────

    @Test
    public void findAffectedGamesReturnsOnlyGamesForGivenContainer() throws Exception {
        ManagedGameStore store = new ManagedGameStore(context);
        store.put(game("g1", 5));
        store.put(game("g2", 5));
        store.put(game("g3", 6));

        List<RuntimeDependencyFacade.AffectedGame> affected =
                RuntimeDependencyManager.findAffectedGames(context, 5);
        assertEquals(2, affected.size());
        for (RuntimeDependencyFacade.AffectedGame g : affected) {
            assertEquals(5, g.containerId);
        }
    }

    @Test
    public void findAffectedGamesReturnsEmptyForContainerWithNoGames() throws Exception {
        ManagedGameStore store = new ManagedGameStore(context);
        store.put(game("g1", 7));

        List<RuntimeDependencyFacade.AffectedGame> affected =
                RuntimeDependencyManager.findAffectedGames(context, 99);
        assertTrue(affected.isEmpty());
    }

    @Test
    public void findAffectedGamesReturnsSingleGameForIsolatedContainer() throws Exception {
        ManagedGameStore store = new ManagedGameStore(context);
        store.put(game("solo", 42));

        List<RuntimeDependencyFacade.AffectedGame> affected =
                RuntimeDependencyManager.findAffectedGames(context, 42);
        assertEquals(1, affected.size());
        assertEquals("solo", affected.get(0).id);
    }

    @Test
    public void findAffectedGamesReturnsEmptyWhenNoGamesExist() throws Exception {
        List<RuntimeDependencyFacade.AffectedGame> affected =
                RuntimeDependencyManager.findAffectedGames(context, 1);
        assertTrue(affected.isEmpty());
    }

    @Test
    public void findAffectedGamesSortsByTitle() throws Exception {
        ManagedGameStore store = new ManagedGameStore(context);
        ManagedGame g1 = game("id1", 10); g1.title = "Zebra Game"; store.put(g1);
        ManagedGame g2 = game("id2", 10); g2.title = "Alpha Game"; store.put(g2);

        List<RuntimeDependencyFacade.AffectedGame> affected =
                RuntimeDependencyManager.findAffectedGames(context, 10);
        assertEquals(2, affected.size());
        assertEquals("Alpha Game", affected.get(0).title);
        assertEquals("Zebra Game", affected.get(1).title);
    }

    // ── isInstallerPathValid ──────────────────────────────────────────────────

    @Test
    public void nullPathIsInvalid() {
        assertFalse(RuntimeDependencyManager.isInstallerPathValid(null));
    }

    @Test
    public void emptyPathIsInvalid() {
        assertFalse(RuntimeDependencyManager.isInstallerPathValid(""));
    }

    @Test
    public void relativePathIsInvalid() {
        assertFalse(RuntimeDependencyManager.isInstallerPathValid("Download/vc_redist.exe"));
    }

    @Test
    public void absolutePathToNonExistentFileIsInvalid() {
        assertFalse(RuntimeDependencyManager.isInstallerPathValid("/nonexistent/file.exe"));
    }

    @Test
    public void absolutePathToExistingFileIsValid() throws Exception {
        java.io.File f = new java.io.File(context.getCacheDir(), "probe.exe");
        f.createNewFile();
        assertTrue(RuntimeDependencyManager.isInstallerPathValid(f.getAbsolutePath()));
        f.delete();
    }

    // ── resolveInstallerPath ──────────────────────────────────────────────────

    @Test
    public void nullUriReturnsNull() {
        assertNull(RuntimeDependencyManager.resolveInstallerPath(context, null));
    }

    @Test
    public void fileSchemeUriResolvesToPath() {
        android.net.Uri uri = android.net.Uri.parse(
                "file:///storage/emulated/0/Download/vc.exe");
        assertEquals("/storage/emulated/0/Download/vc.exe",
                RuntimeDependencyManager.resolveInstallerPath(context, uri));
    }

    @Test
    public void fileSchemeUriWithEmptyPathReturnsNull() {
        android.net.Uri uri = android.net.Uri.parse("file://");
        assertNull(RuntimeDependencyManager.resolveInstallerPath(context, uri));
    }

    // ── Status lifecycle ──────────────────────────────────────────────────────

    @Test
    public void markInstallingPersistsInstallingStatus() throws Exception {
        java.io.File fakeInstaller = new java.io.File(context.getCacheDir(), "test_vc.exe");
        fakeInstaller.createNewFile();

        RuntimeDependencyManager.markInstalling(
                context, 5, RuntimeDependencyCatalog.VCRUN2015_2022,
                fakeInstaller.getAbsolutePath());

        DependencyInstallRecord record = RuntimeDependencyManager.getRecord(
                context, 5, RuntimeDependencyCatalog.VCRUN2015_2022);

        assertEquals(DependencyStatus.INSTALLING, record.status);
        assertEquals(fakeInstaller.getAbsolutePath(), record.installerPath);
        assertFalse(record.log.isEmpty());
        fakeInstaller.delete();
    }

    @Test
    public void markResultSuccessPersistsInstalledStatus() throws Exception {
        RuntimeDependencyManager.markResult(
                context, 8, RuntimeDependencyCatalog.OPENAL, true, "OK");
        DependencyInstallRecord r =
                RuntimeDependencyManager.getRecord(context, 8, RuntimeDependencyCatalog.OPENAL);
        assertEquals(DependencyStatus.INSTALLED, r.status);
        assertTrue(r.installedAt > 0);
    }

    @Test
    public void markResultFailurePersistsFailedStatus() throws Exception {
        RuntimeDependencyManager.markResult(
                context, 9, RuntimeDependencyCatalog.OPENAL, false, "Wine exited non-zero");
        DependencyInstallRecord r =
                RuntimeDependencyManager.getRecord(context, 9, RuntimeDependencyCatalog.OPENAL);
        assertEquals(DependencyStatus.FAILED, r.status);
        assertEquals("Wine exited non-zero", r.log.get(r.log.size() - 1).message);
    }

    // ── getContainerStatus fills missing entries ──────────────────────────────

    @Test
    public void getContainerStatusContainsAllCatalogEntries() throws Exception {
        java.util.Map<String, DependencyInstallRecord> status =
                RuntimeDependencyManager.getContainerStatus(context, 100);
        for (RuntimeDependencyCatalog.Entry entry : RuntimeDependencyCatalog.allEntries()) {
            assertNotNull("Missing status entry for " + entry.id, status.get(entry.id));
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ManagedGame game(String id, int containerId) {
        ManagedGame g = new ManagedGame();
        g.id = id;
        g.title = "Game " + id;
        g.containerId = containerId;
        g.containerPolicy = ManagedGame.CONTAINER_POLICY_ISOLATED;
        g.state = "ready";
        g.createdAt = 1L;
        g.updatedAt = 1L;
        return g;
    }
}
