package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class UnityLaunchOverlayTest {
    @Test
    public void cacheEntryRequiresMatchingCompleteMetadata() throws Exception {
        File root = Files.createTempDirectory("unity-overlay-cache").toFile();
        try {
            File patched = new File(root, "globalgamemanagers");
            File metadata = new File(root, "metadata.json");
            Files.write(patched.toPath(), new byte[]{1, 2, 3});
            Files.write(
                    metadata.toPath(),
                    new JSONObject()
                            .put("schemaVersion", 1)
                            .put("sourceSha256", "ABCDEF")
                            .put("textureLimit", 2)
                            .put("patchedFieldCount", 3)
                            .toString()
                            .getBytes(StandardCharsets.UTF_8)
            );

            assertTrue(UnityLaunchOverlay.isValidCacheEntry(
                    metadata,
                    patched,
                    "abcdef",
                    2
            ));
            assertFalse(UnityLaunchOverlay.isValidCacheEntry(
                    metadata,
                    patched,
                    "different",
                    2
            ));
            assertFalse(UnityLaunchOverlay.isValidCacheEntry(
                    metadata,
                    patched,
                    "abcdef",
                    3
            ));
            Files.write(
                    metadata.toPath(),
                    "{malformed".getBytes(StandardCharsets.UTF_8)
            );
            assertFalse(UnityLaunchOverlay.isValidCacheEntry(
                    metadata,
                    patched,
                    "abcdef",
                    2
            ));
        }
        finally {
            com.winlator.core.FileUtils.delete(root);
        }
    }

    @Test
    public void disabledSettingPrunesContainerPrivateOverlay() throws Exception {
        File root = Files.createTempDirectory("unity-overlay-disabled").toFile();
        try {
            File nested = new File(root, "cache/entry/globalgamemanagers");
            assertTrue(nested.getParentFile().mkdirs());
            Files.write(nested.toPath(), new byte[]{1});

            UnityLaunchOverlay.pruneDisabledOverlay(root);

            assertFalse(root.exists());
        }
        finally {
            com.winlator.core.FileUtils.delete(root);
        }
    }

    @Test
    public void decodesCp437MojibakeAsCp932() {
        String canonical = "テスト製品";
        String mojibake = new String(
                canonical.getBytes(Charset.forName("windows-31j")),
                Charset.forName("IBM437")
        );
        assertEquals(
                canonical,
                UnityLaunchOverlay.decodeCp437AsCp932(mojibake)
        );
        assertNull(UnityLaunchOverlay.decodeCp437AsCp932("日本語"));
    }

    @Test
    public void detectsOnlyVerifiedUnityProductNameRepair() throws Exception {
        File root = Files.createTempDirectory("unity-name-repair").toFile();
        try {
            String canonical = "テスト製品";
            String mojibake = new String(
                    canonical.getBytes(Charset.forName("windows-31j")),
                    Charset.forName("IBM437")
            );
            File executable = new File(root, mojibake+".exe");
            File data = new File(root, mojibake+"_Data");
            File burst = new File(root, mojibake+"_BurstDebugInformation_DoNotShip");
            assertTrue(data.mkdirs());
            assertTrue(burst.mkdirs());
            Files.write(executable.toPath(), new byte[]{1});
            Files.write(
                    new File(data, "app.info").toPath(),
                    ("DefaultCompany\n"+canonical)
                            .getBytes(StandardCharsets.UTF_8)
            );

            UnityLaunchOverlay.NameRepair repair =
                    UnityLaunchOverlay.detectNameRepair(root, executable, data);

            assertNotNull(repair);
            assertEquals(canonical+".exe", repair.canonicalExecutableName());
            assertEquals(canonical+"_Data", repair.canonicalDataName());
            assertEquals(
                    canonical+"_BurstDebugInformation_DoNotShip",
                    repair.canonicalBurstName()
            );

            Files.write(
                    new File(data, "app.info").toPath(),
                    "DefaultCompany\nDifferent Product".getBytes(StandardCharsets.UTF_8)
            );
            assertNull(UnityLaunchOverlay.detectNameRepair(root, executable, data));
        }
        finally {
            com.winlator.core.FileUtils.delete(root);
        }
    }
}
