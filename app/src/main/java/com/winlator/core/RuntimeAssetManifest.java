package com.winlator.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class RuntimeAssetManifest {
    public static final String UPSTREAM_REPOSITORY = "brunodev85/winlator";
    public static final String UPSTREAM_VERSION = "v11.1.0";
    public static final String APK_NAME = "Winlator_11.1.apk";
    public static final String APK_URL =
            "https://github.com/brunodev85/winlator/releases/download/v11.1.0/Winlator_11.1.apk";
    public static final long APK_SIZE = 156943882L;
    public static final String APK_SHA256 =
            "80bdea17d8497a2ae0ff637e68d82a884ccc5ca4406880950b96fd2483e50970";

    public static final Asset ROOTFS = new Asset(
            "assets/rootfs.tzst",
            "rootfs.tzst",
            65251198L,
            "8b5110f248e84f2aee4df37dab8bac4c4bf2bdc7b400c0643a0778ca8e7e40c2"
    );
    public static final Asset CONTAINER_PATTERN = new Asset(
            "assets/container_pattern.tzst",
            "container_pattern.tzst",
            7399363L,
            "8ae3a4fee33e86da26826395650bb07c6f49ce94629ea4b9442bc633b6b8ca33"
    );
    public static final Asset ROOTFS_PATCHES = new Asset(
            "assets/rootfs_patches.tzst",
            "rootfs_patches.tzst",
            4173700L,
            "44b73e37587ea827a12a34753632feb6e2a9c127089e342774167dd91aba8210"
    );
    public static final List<Asset> REQUIRED_ASSETS = Collections.unmodifiableList(
            Arrays.asList(ROOTFS, CONTAINER_PATTERN, ROOTFS_PATCHES)
    );

    private RuntimeAssetManifest() {}

    public static final class Asset {
        public final String apkPath;
        public final String installedName;
        public final long size;
        public final String sha256;

        Asset(String apkPath, String installedName, long size, String sha256) {
            this.apkPath = apkPath;
            this.installedName = installedName;
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
