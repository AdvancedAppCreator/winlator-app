package com.winlator.api.dependency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Map;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class RuntimeDependencyStoreTest {

    private Context context;
    private SharedPreferences prefs;
    private RuntimeDependencyStore store;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences("runtime_dep_store", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new RuntimeDependencyStore(context);
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
    }

    // ── Default record ────────────────────────────────────────────────────────

    @Test
    public void missingRecordReturnsEmptyNotInstalled() throws Exception {
        DependencyInstallRecord r = store.getRecord(1, RuntimeDependencyCatalog.VCRUN2015_2022);
        assertEquals(DependencyStatus.NOT_INSTALLED, r.status);
        assertNull(r.installerPath);
        assertEquals(0L, r.installedAt);
        assertTrue(r.log.isEmpty());
    }

    // ── Status persistence ────────────────────────────────────────────────────

    @Test
    public void putAndGetRoundTrips() throws Exception {
        DependencyInstallRecord record = DependencyInstallRecord.empty()
                .withInstallerPath("/data/Download/vc_redist.exe")
                .withStatus(DependencyStatus.INSTALLED, 999L)
                .appendLog("Installed OK", 999L);

        store.putRecord(5, RuntimeDependencyCatalog.VCRUN2015_2022, record);
        DependencyInstallRecord got = store.getRecord(5, RuntimeDependencyCatalog.VCRUN2015_2022);

        assertEquals(DependencyStatus.INSTALLED, got.status);
        assertEquals("/data/Download/vc_redist.exe", got.installerPath);
        assertEquals(999L, got.installedAt);
        assertEquals(1, got.log.size());
        assertEquals("Installed OK", got.log.get(0).message);
        assertEquals(999L, got.log.get(0).timestamp);
    }

    @Test
    public void statusUpdateReplacesOldStatus() throws Exception {
        DependencyInstallRecord initial = DependencyInstallRecord.empty()
                .withStatus(DependencyStatus.INSTALLING, 100L);
        store.putRecord(3, RuntimeDependencyCatalog.OPENAL, initial);

        DependencyInstallRecord updated = initial.withStatus(DependencyStatus.INSTALLED, 200L);
        store.putRecord(3, RuntimeDependencyCatalog.OPENAL, updated);

        assertEquals(DependencyStatus.INSTALLED, store.getRecord(3, RuntimeDependencyCatalog.OPENAL).status);
        assertEquals(200L, store.getRecord(3, RuntimeDependencyCatalog.OPENAL).installedAt);
    }

    // ── Log management ────────────────────────────────────────────────────────

    @Test
    public void logEntriesAccumulateUpToMax() throws Exception {
        DependencyInstallRecord r = DependencyInstallRecord.empty();
        for (int i = 0; i < DependencyInstallRecord.MAX_LOG_ENTRIES + 5; i++) {
            r = r.appendLog("entry-" + i, i);
        }
        store.putRecord(7, RuntimeDependencyCatalog.OPENAL, r);
        DependencyInstallRecord got = store.getRecord(7, RuntimeDependencyCatalog.OPENAL);

        assertEquals(DependencyInstallRecord.MAX_LOG_ENTRIES, got.log.size());
        // Oldest entries should have been dropped; last entry should be the newest
        assertEquals("entry-" + (DependencyInstallRecord.MAX_LOG_ENTRIES + 4),
                got.log.get(got.log.size() - 1).message);
    }

    // ── Multi-container isolation ─────────────────────────────────────────────

    @Test
    public void differentContainersHaveIndependentRecords() throws Exception {
        DependencyInstallRecord r1 = DependencyInstallRecord.empty()
                .withStatus(DependencyStatus.INSTALLED, 1L);
        DependencyInstallRecord r2 = DependencyInstallRecord.empty()
                .withStatus(DependencyStatus.FAILED, 2L);

        store.putRecord(10, RuntimeDependencyCatalog.VCRUN2015_2022, r1);
        store.putRecord(11, RuntimeDependencyCatalog.VCRUN2015_2022, r2);

        assertEquals(DependencyStatus.INSTALLED,
                store.getRecord(10, RuntimeDependencyCatalog.VCRUN2015_2022).status);
        assertEquals(DependencyStatus.FAILED,
                store.getRecord(11, RuntimeDependencyCatalog.VCRUN2015_2022).status);
    }

    // ── getContainerRecords ───────────────────────────────────────────────────

    @Test
    public void getContainerRecordsReturnsOnlyMatchingContainer() throws Exception {
        store.putRecord(20, RuntimeDependencyCatalog.VCRUN2015_2022,
                DependencyInstallRecord.empty().withStatus(DependencyStatus.INSTALLED, 1L));
        store.putRecord(20, RuntimeDependencyCatalog.OPENAL,
                DependencyInstallRecord.empty().withStatus(DependencyStatus.FAILED, 2L));
        store.putRecord(21, RuntimeDependencyCatalog.VCRUN2015_2022,
                DependencyInstallRecord.empty().withStatus(DependencyStatus.INSTALLING, 3L));

        Map<String, DependencyInstallRecord> records = store.getContainerRecords(20);
        assertEquals(2, records.size());
        assertNotNull(records.get(RuntimeDependencyCatalog.VCRUN2015_2022));
        assertNotNull(records.get(RuntimeDependencyCatalog.OPENAL));
    }

    @Test
    public void getContainerRecordsForEmptyContainerReturnsEmptyMap() throws Exception {
        Map<String, DependencyInstallRecord> records = store.getContainerRecords(99);
        assertTrue(records.isEmpty());
    }

    // ── clearContainer ────────────────────────────────────────────────────────

    @Test
    public void clearContainerRemovesOnlyThatContainer() throws Exception {
        store.putRecord(30, RuntimeDependencyCatalog.OPENAL,
                DependencyInstallRecord.empty().withStatus(DependencyStatus.INSTALLED, 1L));
        store.putRecord(31, RuntimeDependencyCatalog.OPENAL,
                DependencyInstallRecord.empty().withStatus(DependencyStatus.INSTALLED, 2L));

        store.clearContainer(30);

        assertEquals(DependencyStatus.NOT_INSTALLED,
                store.getRecord(30, RuntimeDependencyCatalog.OPENAL).status);
        assertEquals(DependencyStatus.INSTALLED,
                store.getRecord(31, RuntimeDependencyCatalog.OPENAL).status);
    }

    // ── JSON round-trip with null fields ─────────────────────────────────────

    @Test
    public void recordWithNullInstallerPathRoundTrips() throws Exception {
        DependencyInstallRecord r = DependencyInstallRecord.empty()
                .withStatus(DependencyStatus.FAILED, 555L);
        store.putRecord(1, RuntimeDependencyCatalog.OPENAL, r);
        DependencyInstallRecord got = store.getRecord(1, RuntimeDependencyCatalog.OPENAL);
        assertNull(got.installerPath);
        assertEquals(DependencyStatus.FAILED, got.status);
    }

    @Test
    public void bootstrapRoundTrips() throws Exception {
        RuntimeDependencyBootstrapRecord record =
                RuntimeDependencyBootstrapRecord.create(3, 42)
                        .select(Arrays.asList("first", "second"))
                        .withState(RuntimeDependencyBootstrapState.READY_TO_INSTALL, null)
                        .startInstall("first", "")
                        .completeCurrent();
        store.putBootstrap("agm.default", record);

        RuntimeDependencyBootstrapRecord restored = store.getBootstrap("agm.default");
        assertNotNull(restored);
        assertEquals(3, restored.planVersion);
        assertEquals(42, restored.containerId);
        assertEquals(RuntimeDependencyBootstrapState.READY_TO_INSTALL, restored.state);
        assertEquals(Arrays.asList("first", "second"), restored.selectedPackageIds);
        assertEquals(java.util.Collections.singletonList("first"), restored.completedPackageIds);
        assertEquals("", restored.originalDrives);
    }

    @Test
    public void schemaOneMigratesWithoutLosingRecords() throws Exception {
        DependencyInstallRecord existing = DependencyInstallRecord.empty()
                .withStatus(DependencyStatus.INSTALLED, 123L);
        org.json.JSONObject records = new org.json.JSONObject()
                .put("7:openal", existing.toJSON());
        prefs.edit().putString(
                "root",
                new org.json.JSONObject()
                        .put("schemaVersion", 1)
                        .put("records", records)
                        .toString()
        ).commit();

        assertEquals(
                DependencyStatus.INSTALLED,
                store.getRecord(7, RuntimeDependencyCatalog.OPENAL).status
        );
        assertNull(store.getBootstrap("agm.default"));
        assertEquals(
                2,
                new org.json.JSONObject(prefs.getString("root", "{}"))
                        .getInt("schemaVersion")
        );
    }
}
