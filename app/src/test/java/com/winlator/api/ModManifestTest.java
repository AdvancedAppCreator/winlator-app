package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Unit tests for {@link ModManifest}: bounds enforcement, schema-version
 * gating, interrupted-state normalisation, and round-trip serialisation.
 *
 * <p>Tests that touch the filesystem use {@code java.io.tmpdir}
 * (available in any JVM test environment).</p>
 */
public class ModManifestTest {

    // -------------------------------------------------------------------------
    // Empty manifest when file does not exist
    // -------------------------------------------------------------------------

    @Test
    public void loadMissingFileReturnsEmptyManifest() throws Exception {
        File absent = new File(System.getProperty("java.io.tmpdir"),
                "manifest_missing_" + System.nanoTime() + ".json");
        ModManifest m = ModManifest.load(absent);
        assertNotNull(m);
        assertTrue(m.mods.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Round-trip save / load
    // -------------------------------------------------------------------------

    @Test
    public void saveAndLoadRoundTrip() throws Exception {
        File f = tempManifestFile();
        ModManifest original = new ModManifest();
        ModEntry e = entry("id1", "My Mod", ModEntry.STATE_ENABLED);
        e.files.add("bin/hook.dll");
        original.mods.add(e);
        original.save(f);

        ModManifest loaded = ModManifest.load(f);
        assertEquals(1, loaded.mods.size());
        assertEquals("id1", loaded.mods.get(0).id);
        assertEquals("My Mod", loaded.mods.get(0).name);
        assertEquals(ModEntry.STATE_ENABLED, loaded.mods.get(0).state);
        assertEquals(1, loaded.mods.get(0).files.size());
        assertEquals("bin/hook.dll", loaded.mods.get(0).files.get(0));
        f.delete();
    }

    // -------------------------------------------------------------------------
    // Interrupted-state normalisation
    // -------------------------------------------------------------------------

    @Test
    public void applyingStateNormalisedToDisabled() throws Exception {
        File f = tempManifestFile();
        ModManifest m = new ModManifest();
        m.mods.add(entry("a", "Mod A", ModEntry.STATE_APPLYING));
        m.save(f);

        ModManifest loaded = ModManifest.load(f);
        assertEquals(ModEntry.STATE_DISABLED, loaded.mods.get(0).state);
        f.delete();
    }

    @Test
    public void rollingBackStateNormalisedToDisabled() throws Exception {
        File f = tempManifestFile();
        ModManifest m = new ModManifest();
        m.mods.add(entry("b", "Mod B", ModEntry.STATE_ROLLING_BACK));
        m.save(f);

        ModManifest loaded = ModManifest.load(f);
        assertEquals(ModEntry.STATE_DISABLED, loaded.mods.get(0).state);
        f.delete();
    }

    @Test
    public void enabledAndDisabledStatesArePreserved() throws Exception {
        File f = tempManifestFile();
        ModManifest m = new ModManifest();
        m.mods.add(entry("e", "Enabled", ModEntry.STATE_ENABLED));
        m.mods.add(entry("d", "Disabled", ModEntry.STATE_DISABLED));
        m.save(f);

        ModManifest loaded = ModManifest.load(f);
        assertEquals(ModEntry.STATE_ENABLED,  loaded.mods.get(0).state);
        assertEquals(ModEntry.STATE_DISABLED, loaded.mods.get(1).state);
        f.delete();
    }

    // -------------------------------------------------------------------------
    // Bounds enforcement
    // -------------------------------------------------------------------------

    @Test(expected = IOException.class)
    public void savingTooManyModsThrows() throws Exception {
        File f = tempManifestFile();
        ModManifest m = new ModManifest();
        for (int i = 0; i < ModManifest.MAX_MOD_COUNT + 1; i++) {
            m.mods.add(entry("id" + i, "Mod " + i, ModEntry.STATE_DISABLED));
        }
        try {
            m.save(f);
        } finally {
            f.delete();
        }
    }

    @Test(expected = IOException.class)
    public void loadingOversizedManifestThrows() throws Exception {
        File f = tempManifestFile();
        // Write a file larger than MAX_MANIFEST_BYTES.
        StringBuilder sb = new StringBuilder(ModManifest.MAX_MANIFEST_BYTES + 16);
        for (int i = 0; i < ModManifest.MAX_MANIFEST_BYTES + 16; i++) sb.append('x');
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(sb.toString());
        }
        try {
            ModManifest.load(f);
        } finally {
            f.delete();
        }
    }

    // -------------------------------------------------------------------------
    // Schema version gating
    // -------------------------------------------------------------------------

    @Test(expected = IOException.class)
    public void unknownSchemaVersionThrows() throws Exception {
        File f = tempManifestFile();
        try (FileWriter fw = new FileWriter(f)) {
            fw.write("{\"schemaVersion\":99,\"mods\":[]}");
        }
        try {
            ModManifest.load(f);
        } finally {
            f.delete();
        }
    }

    @Test(expected = IOException.class)
    public void zeroSchemaVersionThrows() throws Exception {
        File f = tempManifestFile();
        try (FileWriter fw = new FileWriter(f)) {
            fw.write("{\"schemaVersion\":0,\"mods\":[]}");
        }
        try {
            ModManifest.load(f);
        } finally {
            f.delete();
        }
    }

    // -------------------------------------------------------------------------
    // Load-order helpers
    // -------------------------------------------------------------------------

    @Test
    public void nextLoadOrderIsOnePastMaximum() {
        ModManifest m = new ModManifest();
        assertEquals(0, m.nextLoadOrder()); // empty → 0
        ModEntry e0 = entry("a", "A", ModEntry.STATE_DISABLED);
        e0.loadOrder = 0;
        m.mods.add(e0);
        assertEquals(1, m.nextLoadOrder());
        ModEntry e5 = entry("b", "B", ModEntry.STATE_DISABLED);
        e5.loadOrder = 5;
        m.mods.add(e5);
        assertEquals(6, m.nextLoadOrder());
    }

    @Test
    public void findByIdReturnsCorrectEntry() {
        ModManifest m = new ModManifest();
        m.mods.add(entry("aaa", "A", ModEntry.STATE_ENABLED));
        m.mods.add(entry("bbb", "B", ModEntry.STATE_DISABLED));
        assertNotNull(m.findById("aaa"));
        assertEquals("A", m.findById("aaa").name);
        assertEquals(null, m.findById("ccc"));
    }

    @Test
    public void removeByIdDeletesEntry() {
        ModManifest m = new ModManifest();
        m.mods.add(entry("x", "X", ModEntry.STATE_DISABLED));
        m.mods.add(entry("y", "Y", ModEntry.STATE_DISABLED));
        m.remove("x");
        assertEquals(1, m.mods.size());
        assertEquals("y", m.mods.get(0).id);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static File tempManifestFile() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "modmgr_test_" + System.nanoTime());
        dir.mkdirs();
        return new File(dir, "manifest.json");
    }

    private static ModEntry entry(String id, String name, String state) {
        ModEntry e = new ModEntry();
        e.id    = id;
        e.name  = name;
        e.state = state;
        return e;
    }
}
