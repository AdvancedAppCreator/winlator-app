package com.winlator.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeAssetManifestTest {
    @Test
    public void pinsCanonicalUpstreamRelease() {
        assertEquals("brunodev85/winlator", RuntimeAssetManifest.UPSTREAM_REPOSITORY);
        assertEquals("v11.1.0", RuntimeAssetManifest.UPSTREAM_VERSION);
        assertEquals(
                "https://github.com/brunodev85/winlator/releases/download/v11.1.0/Winlator_11.1.apk",
                RuntimeAssetManifest.APK_URL
        );
        assertEquals(156943882L, RuntimeAssetManifest.APK_SIZE);
        assertEquals(
                "80bdea17d8497a2ae0ff637e68d82a884ccc5ca4406880950b96fd2483e50970",
                RuntimeAssetManifest.APK_SHA256
        );
        assertEquals(3, RuntimeAssetManifest.REQUIRED_ASSETS.size());
        assertEquals("assets/rootfs_patches.tzst", RuntimeAssetManifest.ROOTFS_PATCHES.apkPath);
        assertEquals(4173700L, RuntimeAssetManifest.ROOTFS_PATCHES.size);
        assertEquals(
                "44b73e37587ea827a12a34753632feb6e2a9c127089e342774167dd91aba8210",
                RuntimeAssetManifest.ROOTFS_PATCHES.sha256
        );
        assertTrue(RuntimeAssetManifest.APK_URL.startsWith("https://github.com/"));
    }
}
