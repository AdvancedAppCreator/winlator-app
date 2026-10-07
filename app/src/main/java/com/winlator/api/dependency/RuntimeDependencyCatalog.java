package com.winlator.api.dependency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative catalog of known Windows runtime dependencies and their support status.
 *
 * <p>Supported (user may supply installer): {@link #VCRUN2015_2022}, {@link #OPENAL}.
 * <p>Wrapper-config (DirectX managed via container wrapper settings): {@link #DIRECTX}.
 * <p>Unsupported (must not be presented as installable): all others.
 *
 * This class has no Android dependencies and can be tested with plain JUnit4.
 */
public final class RuntimeDependencyCatalog {
    public static final int BOOTSTRAP_PLAN_VERSION = 3;

    /** Classification of a catalog entry. */
    public enum Category {
        /**
         * User may supply an installer EXE/MSI; installation status is tracked per container.
         */
        SUPPORTED,
        /**
         * Must not be presented as installable through this manager. Clearly reported as
         * not supported.
         */
        UNSUPPORTED,
        /**
         * Not user-installable here. Managed via the container's wrapper / graphics settings
         * (e.g. DXVK, VKD3D, WineD3D).
         */
        WRAPPER_CONFIG
    }

    public enum InstallMode {
        EXE,
        DIRECTX_EXTRACT,
        DIRECTX_SETUP
    }

    public static final class BootstrapPackage {
        public final String id;
        public final String displayName;
        public final String version;
        public final String url;
        public final String sha256;
        public final String fileName;
        public final long size;
        public final String licenseUrl;
        public final String arguments;
        public final InstallMode installMode;
        public final String[] probePaths;
        public final boolean selectable;

        BootstrapPackage(
                String id,
                String displayName,
                String version,
                String url,
                String sha256,
                String fileName,
                long size,
                String licenseUrl,
                String arguments,
                InstallMode installMode,
                String[] probePaths,
                boolean selectable
        ) {
            this.id = id;
            this.displayName = displayName;
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.fileName = fileName;
            this.size = size;
            this.licenseUrl = licenseUrl;
            this.arguments = arguments;
            this.installMode = installMode;
            this.probePaths = probePaths;
            this.selectable = selectable;
        }

        public boolean requiresDownload() {
            return url != null;
        }
    }

    /** A single entry in the catalog. */
    public static final class Entry {
        public final String id;
        public final String displayName;
        public final Category category;
        /** Human-readable description shown in the dependency manager UI. */
        public final String description;

        Entry(String id, String displayName, Category category, String description) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.description = description;
        }

        public boolean isSupported() {
            return category == Category.SUPPORTED;
        }

        public boolean isUnsupported() {
            return category == Category.UNSUPPORTED;
        }

        public boolean isWrapperConfig() {
            return category == Category.WRAPPER_CONFIG;
        }
    }

    // ── Supported ────────────────────────────────────────────────────────────
    /** Microsoft Visual C++ 2015–2022 Redistributable (x86 and/or x64). */
    public static final String VCRUN2015_2022 = "vcrun2015_2022";
    /** OpenAL audio runtime. */
    public static final String OPENAL = "openal";

    // ── Wrapper-config (not user-installable) ────────────────────────────────
    /** DirectX — handled by the container's DXVK / VKD3D / WineD3D wrapper. */
    public static final String DIRECTX = "directx";

    // ── Unsupported (shown but blocked) ──────────────────────────────────────
    public static final String DOTNET   = "dotnet";
    public static final String MONO     = "mono";
    public static final String XNA      = "xna";
    public static final String PHYSX    = "physx";
    public static final String VCRUN2008 = "vcrun2008";
    public static final String VCRUN2012 = "vcrun2012";

    private static final Map<String, Entry> CATALOG;
    private static final Map<String, BootstrapPackage> BOOTSTRAP_CATALOG;

    static {
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();

        // Supported
        put(m, VCRUN2015_2022,
                "Visual C++ 2015–2022",
                Category.SUPPORTED,
                "Microsoft Visual C++ Redistributable for Visual Studio 2015, 2017, 2019, and 2022. "
                        + "The 2022 package covers all of these versions. "
                        + "Supply vc_redist.x64.exe and/or vc_redist.x86.exe.");
        put(m, OPENAL,
                "OpenAL",
                Category.SUPPORTED,
                "OpenAL audio runtime (oalinst.exe or the OpenAL SDK installer).");

        // Wrapper-config
        put(m, DIRECTX,
                "DirectX",
                Category.WRAPPER_CONFIG,
                "DirectX support is provided by the container\u2019s wrapper setting "
                        + "(DXVK, VKD3D, or WineD3D). Legacy DirectX helper libraries "
                        + "are managed by Windows prerequisite setup.");

        // Unsupported
        put(m, VCRUN2008,
                "Visual C++ 2008",
                Category.UNSUPPORTED,
                "Managed by Windows prerequisite setup.");
        put(m, VCRUN2012,
                "Visual C++ 2012",
                Category.UNSUPPORTED,
                "Managed by Windows prerequisite setup.");
        put(m, DOTNET,
                ".NET / .NET Framework",
                Category.UNSUPPORTED,
                "Not supported by this manager.");
        put(m, MONO,
                "Mono",
                Category.UNSUPPORTED,
                "Not supported by this manager.");
        put(m, XNA,
                "XNA Framework",
                Category.UNSUPPORTED,
                "Not supported by this manager.");
        put(m, PHYSX,
                "NVIDIA PhysX",
                Category.UNSUPPORTED,
                "Not supported by this manager.");

        CATALOG = Collections.unmodifiableMap(m);

        LinkedHashMap<String, BootstrapPackage> bootstrap = new LinkedHashMap<>();
        String vcLicense = "https://visualstudio.microsoft.com/license-terms/";
        putBootstrap(bootstrap, "vcrun2005_x86", "Visual C++ 2005 (x86)", "8.0.50727.6195",
                "https://download.microsoft.com/download/8/B/4/8B42259F-5D70-43F4-AC2E-4B208FD8D66A/vcredist_x86.EXE",
                "8648c5fc29c44b9112fe52f9a33f80e7fc42d10f3b5b42b2121542a13e44adfd",
                "vcredist2005_x86.exe", 2710520, vcLicense, "/q",
                InstallMode.EXE, "syswow64/msvcr80.dll");
        putBootstrap(bootstrap, "vcrun2005_x64", "Visual C++ 2005 (x64)", "8.0.50727.6195",
                "https://download.microsoft.com/download/8/B/4/8B42259F-5D70-43F4-AC2E-4B208FD8D66A/vcredist_x64.EXE",
                "4487570bd86e2e1aac29db2a1d0a91eb63361fcaac570808eb327cd4e0e2240d",
                "vcredist2005_x64.exe", 3179000, vcLicense, "/q",
                InstallMode.EXE, "system32/msvcr80.dll");
        putBootstrap(bootstrap, "vcrun2008_x86", "Visual C++ 2008 (x86)", "9.0.30729.6161",
                "https://download.microsoft.com/download/5/D/8/5D8C65CB-C849-4025-8E95-C3966CAFD8AE/vcredist_x86.exe",
                "8742bcbf24ef328a72d2a27b693cc7071e38d3bb4b9b44dec42aa3d2c8d61d92",
                "vcredist2008_x86.exe", 4483040, vcLicense, "/q",
                InstallMode.EXE, "syswow64/msvcr90.dll");
        putBootstrap(bootstrap, "vcrun2008_x64", "Visual C++ 2008 (x64)", "9.0.30729.6161",
                "https://download.microsoft.com/download/5/D/8/5D8C65CB-C849-4025-8E95-C3966CAFD8AE/vcredist_x64.exe",
                "c5e273a4a16ab4d5471e91c7477719a2f45ddadb76c7f98a38fa5074a6838654",
                "vcredist2008_x64.exe", 5211080, vcLicense, "/q",
                InstallMode.EXE, "system32/msvcr90.dll");
        putBootstrap(bootstrap, "vcrun2010_x86", "Visual C++ 2010 (x86)", "10.0.40219.325",
                "https://download.microsoft.com/download/5/B/C/5BC5DBB3-652D-4DCE-B14A-475AB85EEF6E/vcredist_x86.exe",
                "31d32fa39d52cac9a765a43660431f7a127eee784b54b2f5e2af3e2b763a1af8",
                "vcredist2010_x86.exe", 5076456, vcLicense, "/q",
                InstallMode.EXE, "syswow64/msvcr100.dll");
        putBootstrap(bootstrap, "vcrun2010_x64", "Visual C++ 2010 (x64)", "10.0.40219.325",
                "https://download.microsoft.com/download/A/8/0/A80747C3-41BD-45DF-B505-E9710D2744E0/vcredist_x64.exe",
                "2fddbc3aaaab784c16bc673c3bae5f80929d5b372810dbc28649283566d33255",
                "vcredist2010_x64.exe", 5677032, vcLicense, "/q",
                InstallMode.EXE, "system32/msvcr100.dll");
        putBootstrap(bootstrap, "vcrun2012_x86", "Visual C++ 2012 (x86)", "11.0.61030.0",
                "https://download.microsoft.com/download/1/6/B/16B06F60-3B20-4FF2-B699-5E9B7962F9AE/VSU_4/vcredist_x86.exe",
                "b924ad8062eaf4e70437c8be50fa612162795ff0839479546ce907ffa8d6e386",
                "vcredist2012_x86.exe", 6554576, vcLicense, "/q",
                InstallMode.EXE, "syswow64/msvcr110.dll");
        putBootstrap(bootstrap, "vcrun2012_x64", "Visual C++ 2012 (x64)", "11.0.61030.0",
                "https://download.microsoft.com/download/1/6/B/16B06F60-3B20-4FF2-B699-5E9B7962F9AE/VSU_4/vcredist_x64.exe",
                "681be3e5ba9fd3da02c09d7e565adfa078640ed66a0d58583efad2c1e3cc4064",
                "vcredist2012_x64.exe", 7186992, vcLicense, "/q",
                InstallMode.EXE, "system32/msvcr110.dll");
        putBootstrap(bootstrap, "vcrun2013_x86", "Visual C++ 2013 (x86)", "12.0.40664.0",
                "https://download.microsoft.com/download/0/5/6/056dcda9-d667-4e27-8001-8a0c6971d6b1/vcredist_x86.exe",
                "89f4e593ea5541d1c53f983923124f9fd061a1c0c967339109e375c661573c17",
                "vcredist2013_x86.exe", 6510544, vcLicense, "/quiet /norestart",
                InstallMode.EXE, "syswow64/msvcr120.dll");
        putBootstrap(bootstrap, "vcrun2013_x64", "Visual C++ 2013 (x64)", "12.0.40664.0",
                "https://download.microsoft.com/download/0/5/6/056dcda9-d667-4e27-8001-8A0C6971D6B1/vcredist_x64.exe",
                "20e2645b7cd5873b1fa3462b99a665ac8d6e14aae83ded9d875fea35ffdd7d7e",
                "vcredist2013_x64.exe", 7201032, vcLicense, "/quiet /norestart",
                InstallMode.EXE, "system32/msvcr120.dll");
        putBootstrap(bootstrap, "vcrun2026_x86", "Visual C++ 2015-2026 (x86)", "14.50.35719",
                "https://download.visualstudio.microsoft.com/download/pr/355d2512-13c2-400a-bf9f-8a296abb5932/F0BAB33A302B3CDB2E11113760D016F54FD3D2632C65BA7834FAC4F0ABD7F1A3/VC_redist.x86.exe",
                "f0bab33a302b3cdb2e11113760d016f54fd3d2632c65ba7834fac4f0abd7f1a3",
                "vc_redist_2015_2026_x86.exe", 6941536, vcLicense, "/quiet /norestart",
                InstallMode.EXE, "syswow64/vcruntime140.dll", "syswow64/msvcp140.dll");
        putBootstrap(bootstrap, "vcrun2026_x64", "Visual C++ 2015-2026 (x64)", "14.50.35719",
                "https://download.visualstudio.microsoft.com/download/pr/ebdab8e5-1d7b-4d9f-a11b-cbb1720c3b12/843068991DAAA1F73AD9F6239BCE4D0F6A07A51F18C37EA2A867E9BECA71295C/VC_redist.x64.exe",
                "843068991daaa1f73ad9f6239bce4d0f6a07a51f18c37ea2a867e9beca71295c",
                "vc_redist_2015_2026_x64.exe", 18731856, vcLicense, "/quiet /norestart",
                InstallMode.EXE, "system32/vcruntime140.dll", "system32/msvcp140.dll");
        putBootstrap(bootstrap, "directx_june2010", "DirectX End-User Runtimes (June 2010)",
                "9.29.1974.1",
                "https://download.microsoft.com/download/8/4/A/84A35BF1-DAFE-4AE8-82AF-AD2AE20B6B14/directx_Jun2010_redist.exe",
                "053f76dcbb28802e23341b6a787e3b0791c0fa5c8d4d011b1044172dbf89c73b",
                "directx_Jun2010_redist.exe", 100275120,
                "https://www.microsoft.com/en-us/download/details.aspx?id=8109",
                "/Q /T:D:\\directx-jun2010", InstallMode.DIRECTX_EXTRACT);
        bootstrap.put("directx_june2010_setup", new BootstrapPackage(
                "directx_june2010_setup",
                "DirectX Setup",
                "9.29.1974.1",
                null,
                null,
                "DXSETUP.exe",
                0,
                "https://www.microsoft.com/en-us/download/details.aspx?id=8109",
                "/silent",
                InstallMode.DIRECTX_SETUP,
                new String[]{
                        "syswow64/d3dx9_43.dll",
                        "system32/d3dx9_43.dll",
                        "syswow64/xinput1_3.dll",
                        "system32/xinput1_3.dll"
                },
                false
        ));
        BOOTSTRAP_CATALOG = Collections.unmodifiableMap(bootstrap);
    }

    private static void put(Map<String, Entry> map,
            String id, String displayName, Category category, String description) {
        map.put(id, new Entry(id, displayName, category, description));
    }

    private static void putBootstrap(
            Map<String, BootstrapPackage> map,
            String id,
            String displayName,
            String version,
            String url,
            String sha256,
            String fileName,
            long size,
            String licenseUrl,
            String arguments,
            InstallMode installMode,
            String... probePaths
    ) {
        map.put(id, new BootstrapPackage(
                id,
                displayName,
                version,
                url,
                sha256,
                fileName,
                size,
                licenseUrl,
                arguments,
                installMode,
                probePaths,
                true
        ));
    }

    private RuntimeDependencyCatalog() {}

    /** Returns the catalog entry for {@code id}, or {@code null} if unknown. */
    public static Entry getEntry(String id) {
        return CATALOG.get(id);
    }

    /** All entries in catalog definition order. */
    public static List<Entry> allEntries() {
        return new ArrayList<>(CATALOG.values());
    }

    /** Entries the user can install through this manager. */
    public static List<Entry> supportedEntries() {
        List<Entry> result = new ArrayList<>();
        for (Entry e : CATALOG.values()) {
            if (e.isSupported()) result.add(e);
        }
        return result;
    }

    /** Entries that must not be presented as installable. */
    public static List<Entry> unsupportedEntries() {
        List<Entry> result = new ArrayList<>();
        for (Entry e : CATALOG.values()) {
            if (e.isUnsupported()) result.add(e);
        }
        return result;
    }

    /** Entries managed via container wrapper settings rather than user installers. */
    public static List<Entry> wrapperConfigEntries() {
        List<Entry> result = new ArrayList<>();
        for (Entry e : CATALOG.values()) {
            if (e.isWrapperConfig()) result.add(e);
        }
        return result;
    }

    public static BootstrapPackage getBootstrapPackage(String id) {
        return BOOTSTRAP_CATALOG.get(id);
    }

    public static List<BootstrapPackage> bootstrapSelectablePackages() {
        List<BootstrapPackage> result = new ArrayList<>();
        for (BootstrapPackage entry : BOOTSTRAP_CATALOG.values()) {
            if (entry.selectable) result.add(entry);
        }
        return result;
    }

    public static List<String> expandBootstrapSelection(List<String> selectedIds) {
        List<String> result = new ArrayList<>();
        for (String id : selectedIds) {
            if (!BOOTSTRAP_CATALOG.containsKey(id)) {
                throw new IllegalArgumentException("Unknown prerequisite package: " + id);
            }
            result.add(id);
            if ("directx_june2010".equals(id)) result.add("directx_june2010_setup");
        }
        return result;
    }
}
