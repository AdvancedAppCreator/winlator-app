package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.winlator.core.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
public class ModManagerIntegrationTest {
    private Context context;
    private File gameRoot;
    private ManagedGame game;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        gameRoot = new File(context.getCacheDir(), "mod-game-" + System.nanoTime());
        assertTrue(gameRoot.mkdirs());
        game = new ManagedGame();
        game.id = "mod-test-" + System.nanoTime();
        game.gamePath = gameRoot.getAbsolutePath();
        FileUtils.delete(ModPaths.modsDir(context.getFilesDir(), game.id));
    }

    @After
    public void tearDown() {
        FileUtils.delete(gameRoot);
        FileUtils.delete(ModPaths.modsDir(context.getFilesDir(), game.id));
    }

    @Test
    public void failedEmptyImportRemovesStagingDirectory() throws Exception {
        File zip = new File(context.getCacheDir(), "empty-mod.zip");
        try (ZipOutputStream ignored = new ZipOutputStream(new FileOutputStream(zip))) {
        }

        ModManager.Result result = new ModManager(context, game)
                .importMod(Uri.fromFile(zip), "Empty");

        assertFalse(result.success);
        File staging = new File(
                ModPaths.modsDir(context.getFilesDir(), game.id),
                "staging"
        );
        File[] children = staging.listFiles();
        assertTrue(children == null || children.length == 0);
    }

    @Test
    public void enablingAndDisablingRestoresOriginalFile() throws Exception {
        File target = new File(gameRoot, "data/config.ini");
        assertTrue(target.getParentFile().mkdirs());
        Files.write(target.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        File zip = zipWith("data/config.ini", "modded");

        ModManager manager = new ModManager(context, game);
        assertTrue(manager.importMod(Uri.fromFile(zip), "Config mod").success);
        String modId = manager.listMods().get(0).id;

        assertTrue(manager.enableMod(modId).success);
        assertEquals("modded", read(target));
        assertTrue(manager.disableMod(modId).success);
        assertEquals("original", read(target));
    }

    @Test
    public void disablingConflictingModsRestoresPreviousWinnerThenOriginal()
            throws Exception {
        File target = new File(gameRoot, "data/config.ini");
        assertTrue(target.getParentFile().mkdirs());
        Files.write(target.toPath(), "original".getBytes(StandardCharsets.UTF_8));

        ModManager manager = new ModManager(context, game);
        assertTrue(manager.importMod(
                Uri.fromFile(zipWith("data/config.ini", "low")),
                "Low"
        ).success);
        assertTrue(manager.importMod(
                Uri.fromFile(zipWith("data/config.ini", "high")),
                "High"
        ).success);
        ArrayList<ModEntry> mods = manager.listMods();
        String lowId = mods.get(0).id;
        String highId = mods.get(1).id;

        assertTrue(manager.enableMod(lowId).success);
        assertEquals("low", read(target));
        assertTrue(manager.enableMod(highId).success);
        assertEquals("high", read(target));

        HashMap<String, ArrayList<String>> conflicts = manager.buildConflictMap();
        assertEquals(2, conflicts.get("data/config.ini").size());

        assertTrue(manager.disableMod(highId).success);
        assertEquals("low", read(target));
        assertTrue(manager.disableMod(lowId).success);
        assertEquals("original", read(target));
    }

    @Test
    public void reenableRefreshesBaselineAfterExternalGameUpdate()
            throws Exception {
        File target = new File(gameRoot, "data/config.ini");
        assertTrue(target.getParentFile().mkdirs());
        Files.write(target.toPath(), "original".getBytes(StandardCharsets.UTF_8));

        ModManager manager = new ModManager(context, game);
        assertTrue(manager.importMod(
                Uri.fromFile(zipWith("data/config.ini", "modded")),
                "Config mod"
        ).success);
        String modId = manager.listMods().get(0).id;

        assertTrue(manager.enableMod(modId).success);
        assertTrue(manager.disableMod(modId).success);
        Files.write(target.toPath(), "updated".getBytes(StandardCharsets.UTF_8));

        assertTrue(manager.enableMod(modId).success);
        assertTrue(manager.disableMod(modId).success);
        assertEquals("updated", read(target));
    }

    @Test
    public void reenablePreservesExternallyAddedFile() throws Exception {
        File target = new File(gameRoot, "data/new.ini");
        ModManager manager = new ModManager(context, game);
        assertTrue(manager.importMod(
                Uri.fromFile(zipWith("data/new.ini", "modded")),
                "New file mod"
        ).success);
        String modId = manager.listMods().get(0).id;

        assertTrue(manager.enableMod(modId).success);
        assertTrue(manager.disableMod(modId).success);
        assertFalse(target.exists());
        assertTrue(
                target.getParentFile().isDirectory() ||
                        target.getParentFile().mkdirs()
        );
        Files.write(target.toPath(), "legitimate".getBytes(StandardCharsets.UTF_8));

        assertTrue(manager.enableMod(modId).success);
        assertTrue(manager.disableMod(modId).success);
        assertEquals("legitimate", read(target));
    }

    private File zipWith(String path, String content) throws Exception {
        File zip = new File(context.getCacheDir(), "mod-" + System.nanoTime() + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(path));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return zip;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
