package com.winlator.core;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Drive;
import com.winlator.win32.MSLogFont;
import com.winlator.win32.WinVersions;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public abstract class WineUtils {
    public static void createDosdevicesSymlinks(Context context, Container container, boolean addDriveCDRom) {
        File rootDir = container.getRootDir();
        String dosdevicesPath = (new File(rootDir, ".wine/dosdevices")).getPath();
        File[] files = (new File(dosdevicesPath)).listFiles();
        if (files != null) for (File file : files) if (file.getName().matches("[a-z]:")) file.delete();

        FileUtils.symlink("../drive_c", dosdevicesPath+"/c:");
        FileUtils.symlink("../../../../", dosdevicesPath+"/z:");

        if (addDriveCDRom) {
            File driveX = new File(rootDir, ".wine/drive_x");
            if (!driveX.isDirectory()) {
                driveX.mkdir();
                FileUtils.chmod(driveX, 0771);
            }

            String serial = String.format(Locale.ENGLISH, "%-8x", (int)'X').replace(' ', '0');
            FileUtils.writeString(new File(driveX, ".windows-serial"), serial+"\n");
            FileUtils.symlink("../drive_x", dosdevicesPath+"/x:");
        }

        for (Drive drive : container.drivesIterator()) {
            File linkTarget = new File(drive.path);
            String path = linkTarget.getAbsolutePath();
            if (!linkTarget.isDirectory() && path.startsWith(AppUtils.getInternalStorage(context))) {
                linkTarget.mkdirs();
                FileUtils.chmod(linkTarget, 0771);
            }
            FileUtils.symlink(path, dosdevicesPath+"/"+drive.letter.toLowerCase(Locale.ENGLISH)+":");
        }
    }

    public static void setSystemFont(WineRegistryEditor userRegistry, String faceName) {
        byte[] fontNormalData = (new MSLogFont()).setFaceName(faceName).toByteArray();
        byte[] fontBoldData = (new MSLogFont()).setFaceName(faceName).setWeight(700).toByteArray();
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "CaptionFont", fontBoldData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "IconFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "MenuFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "MessageFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "SmCaptionFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "StatusFont", fontNormalData);
    }

    public static void applySystemTweaks(Context context, WineInfo wineInfo) {
        File rootDir = RootFS.find(context).getRootDir();

        File userCacheDir = new File(rootDir, RootFS.USER_CACHE_PATH);
        if (!userCacheDir.isDirectory()) userCacheDir.mkdirs();
        File userConfigDir = new File(rootDir, RootFS.USER_CONFIG_PATH);
        if (!userConfigDir.isDirectory()) userConfigDir.mkdirs();

        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");
        File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
        removeLegacyCoreFonts(rootDir);

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setStringValue("Software\\Wine\\Drives", "x:", "cdrom");
            registryEditor.setStringValue("Software\\Classes\\.reg", null, "REGfile");
            registryEditor.setStringValue("Software\\Classes\\.reg", "Content Type", "application/reg");
            registryEditor.setStringValue("Software\\Classes\\REGfile\\Shell\\Open\\command", null, "C:\\windows\\regedit.exe /C \"%1\"");

            registryEditor.setStringValue("Software\\Classes\\dllfile\\DefaultIcon", null, "shell32.dll,-154");
            registryEditor.setStringValue("Software\\Classes\\lnkfile\\DefaultIcon", null, "shell32.dll,-30");
            registryEditor.setStringValue("Software\\Classes\\inifile\\DefaultIcon", null, "shell32.dll,-151");

            File fontsAddedFile = new File(userConfigDir, "openfonts.added.v1");
            if (!fontsAddedFile.isFile()) {
                setupSystemFonts(registryEditor);
                FileUtils.writeString(fontsAddedFile, String.valueOf(System.currentTimeMillis()));
            }
        }

        final String[] direct3dLibs = {"d3d8", "d3d9", "d3d10", "d3d10_1", "d3d10core", "d3d11", "d3d12", "d3d12core", "ddraw", "dxgi", "wined3d"};
        final String dllOverridesKey = "Software\\Wine\\DllOverrides";

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            for (String name : direct3dLibs) registryEditor.setStringValue(dllOverridesKey, name, "native,builtin");

            registryEditor.removeKey("Software\\Winlator\\WFM\\ContextMenu\\7-Zip");
            registryEditor.setStringValue(
                    "Software\\Wine\\AddonsURL",
                    null,
                    DownloadIntegrity.url("wine_addons/")
            );
            registryEditor.setStringValue("Software\\Wine\\Drivers", "Graphics", "x11");
        }
    }

    public static void changeBrowsersRegistryKey(Container container, boolean useAndroidBrowser) {
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (useAndroidBrowser) {
                registryEditor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\winhandler.exe /url");
            }
            else registryEditor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\system32\\iexplore.exe");
        }
    }

    public static void updateWineprefix(Context context, final Callback<Integer> terminationCallback) {
        RootFS rootFS = RootFS.find(context);
        final File rootDir = rootFS.getRootDir();
        File tmpDir = rootFS.getTmpDir();
        if (!tmpDir.isDirectory()) tmpDir.mkdir();

        FileUtils.writeString(new File(rootDir, RootFS.WINEPREFIX+"/.update-timestamp"), "0\n");

        EnvVars envVars = new EnvVars();
        envVars.put("WINEPREFIX", rootDir+RootFS.WINEPREFIX);
        envVars.put("WINEDLLOVERRIDES", "mscoree,mshtml=d");

        XEnvironment environment = new XEnvironment(context, rootFS);
        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setGuestExecutable("wine wineboot -u");
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            FileUtils.writeString(new File(rootDir, RootFS.WINEPREFIX+"/.update-timestamp"), "disable\n");
            if (terminationCallback != null) terminationCallback.call(status);
        });
        environment.addComponent(guestProgramLauncherComponent);
        environment.startEnvironmentComponents();
    }

    public static boolean isWineprefixWasUpdated(Container container) {
        File file = new File(container.getRootDir(), "/.wine/.update-timestamp");
        String content = FileUtils.readString(file);
        
        if (!content.startsWith("disable")) {
            content = content.replaceAll("[\r\n]+", "");
            try {
                int updateTimestamp = Integer.parseInt(content);
                if (updateTimestamp != 0) return FileUtils.writeString(file, "disable\n");
            }
            catch (NumberFormatException e) {}
        }
        return false;
    }

    public static void changeServicesStatus(Container container, byte startupSelection) {
        final byte SERVICE_DISABLED = 4;
        final String[] services = {"BITS:3", "Eventlog:2", "HTTP:3", "LanmanServer:3", "NDIS:2", "PlugPlay:2", "RpcSs:3", "scardsvr:3", "Schedule:3", "Spooler:3", "StiSvc:3", "TermService:3", "Winmgmt:3", "wuauserv:3", "winebth:3"};
        final String[] extraServices = {"nsiproxy:2", "MSIServer:3", "FontCache:3"};
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setCreateKeyIfNotExist(false);

            String controlSetPath = registryEditor.getSymlinkValue("System\\CurrentControlSet", "SymbolicLinkValue");
            if (controlSetPath == null) controlSetPath = "System\\CurrentControlSet";

            for (String service : services) {
                String name = service.substring(0, service.indexOf(":"));
                int value = startupSelection != Container.STARTUP_SELECTION_NORMAL ? SERVICE_DISABLED : Character.getNumericValue(service.charAt(service.length()-1));
                registryEditor.setDwordValue(controlSetPath+"\\Services\\"+name, "Start", value);
            }

            for (String service : extraServices) {
                String name = service.substring(0, service.indexOf(":"));
                int value = startupSelection == Container.STARTUP_SELECTION_AGGRESSIVE ? SERVICE_DISABLED : Character.getNumericValue(service.charAt(service.length()-1));
                registryEditor.setDwordValue(controlSetPath+"\\Services\\"+name, "Start", value);
            }
        }
    }

    public static String unixToDOSPath(String unixPath, Container container) {
        String dosPath = "";
        String driveLetter = "";
        String normalizedUnixPath = normalizePath(unixPath);
        String selectedDrivePath = "";

        for (Drive drive : container.drivesIterator()) {
            String normalizedDrivePath = normalizePath(drive.path);
            if (isPathInside(normalizedUnixPath, normalizedDrivePath) &&
                    normalizedDrivePath.length() > selectedDrivePath.length()) {
                driveLetter = drive.letter+":";
                selectedDrivePath = normalizedDrivePath;
            }
        }

        if (!selectedDrivePath.isEmpty()) {
            dosPath = normalizedUnixPath.substring(selectedDrivePath.length()).replace("/", "\\");
        }

        if (dosPath.isEmpty()) {
            String driveCPath = normalizePath(new File(container.getRootDir(), ".wine/drive_c").getPath());
            if (isPathInside(normalizedUnixPath, driveCPath)) {
                driveLetter = "C:";
                dosPath = normalizedUnixPath.substring(driveCPath.length()).replace("/", "\\");
            }
        }

        if (!dosPath.startsWith("\\")) dosPath += "\\";
        dosPath = driveLetter+StringUtils.removeEndSlash(dosPath);
        if (dosPath.equals(driveLetter)) dosPath += "\\";
        return dosPath;
    }

    private static String normalizePath(String path) {
        String normalized = new File(path).getAbsolutePath().replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length()-1);
        }
        return normalized;
    }

    private static boolean isPathInside(String path, String directory) {
        if (directory.isEmpty()) return false;
        if (path.equals(directory)) return true;
        return directory.equals("/") ? path.startsWith("/") : path.startsWith(directory+"/");
    }

    public static String dosToUnixPath(String dosPath, Container container) {
        int index = dosPath.indexOf(":");
        if (index == -1) return "";

        String unixPath = "";
        String driveLetter = dosPath.substring(0, index).toUpperCase(Locale.ENGLISH);
        String relativePath = StringUtils.removeStartSlash(dosPath.substring(index+1).replace("\\", "/"));

        if (driveLetter.equals("C")) {
            unixPath = container.getRootDir()+"/.wine/drive_c/"+relativePath;
        }
        else if (driveLetter.equals("Z")) {
            File rootDir = new File(container.getRootDir(), "../../");
            try {
                unixPath = rootDir.getCanonicalPath()+"/"+relativePath;
            }
            catch (IOException e) {}
        }
        else {
            for (Drive drive : container.drivesIterator()) {
                if (drive.letter.equals(driveLetter)) {
                    unixPath = drive.path+"/"+relativePath;
                    break;
                }
            }
        }

        return unixPath;
    }

    public static void setWinVersion(Container container, int winVersionIdx) {
        WinVersions.WinVersion winVersion = WinVersions.getWinVersions()[winVersionIdx];
        String currentBuild = String.valueOf(winVersion.buildNumber);
        String currentVersion = winVersion.currentVersion != null ? winVersion.currentVersion : winVersion.majorVersion+"."+winVersion.minorVersion;

        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            String key64 = "Software\\Microsoft\\Windows NT\\CurrentVersion";
            String key32 = "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion";

            registryEditor.setStringValue(key32, "CurrentVersion", currentVersion);
            registryEditor.setDwordValue(key32, "CurrentMajorVersionNumber", winVersion.majorVersion);
            registryEditor.setDwordValue(key32, "CurrentMinorVersionNumber", winVersion.minorVersion);
            registryEditor.setStringValue(key32, "CSDVersion", winVersion.csdVersion);
            registryEditor.setStringValue(key32, "CurrentBuild", currentBuild);
            registryEditor.setStringValue(key32, "CurrentBuildNumber", currentBuild);
            registryEditor.setStringValue(key32, "ProductName", "Microsoft "+winVersion.description);

            registryEditor.setStringValue(key64, "CurrentVersion", currentVersion);
            registryEditor.setDwordValue(key64, "CurrentMajorVersionNumber", winVersion.majorVersion);
            registryEditor.setDwordValue(key64, "CurrentMinorVersionNumber", winVersion.minorVersion);
            registryEditor.setStringValue(key64, "CSDVersion", winVersion.csdVersion);
            registryEditor.setStringValue(key64, "CurrentBuild", currentBuild);
            registryEditor.setStringValue(key64, "CurrentBuildNumber", currentBuild);
            registryEditor.setStringValue(key64, "ProductName", "Microsoft "+winVersion.description);
        }
    }

    public static void setWinVersion(Container container, String version) {
        int index = WinVersions.indexOf(version);
        if (index < 0) {
            throw new IllegalArgumentException("Unsupported winVersion: " + version);
        }
        setWinVersion(container, index);
    }

    public static final String CJK_FONT_FILE = "NotoSansCJKjp-Regular.otf";
    public static final String CJK_FONT_FACE = "Noto Sans CJK JP";

    // RPG Maker VX Ace (RGSS3) and many JP games hard-require the "VL Gothic" family that
    // ships in the RTP; a FontSubstitutes alias does not satisfy an explicit
    // Font.exist?("VL Gothic") check, so the real faces are bundled and registered under
    // their exact family names. Files carry their internal family names "VL Gothic" /
    // "VL PGothic", so Wine enumerates them by those names.
    private static final String[][] JP_GAME_FONTS = {
            {"VL Gothic (TrueType)", "VL-Gothic-Regular.ttf"},
            {"VL PGothic (TrueType)", "VL-PGothic-Regular.ttf"}
    };

    private static final String[][] LIVEMAKER_FONTS = {
            {"MS Gothic (TrueType)", "MS-Gothic-Compatible.ttf"},
            {"MS PGothic (TrueType)", "MS-PGothic-Compatible.ttf"},
            {"MS UI Gothic (TrueType)", "MS-UIGothic-Compatible.ttf"}
    };

    private static final String[][] LATIN_COMPAT_FONTS = {
            {"Liberation Sans (TrueType)", "LiberationSans-Regular.ttf"},
            {"Liberation Sans Bold (TrueType)", "LiberationSans-Bold.ttf"},
            {"Liberation Sans Bold Italic (TrueType)", "LiberationSans-BoldItalic.ttf"},
            {"Liberation Sans Italic (TrueType)", "LiberationSans-Italic.ttf"},
            {"Liberation Serif (TrueType)", "LiberationSerif-Regular.ttf"},
            {"Liberation Serif Bold (TrueType)", "LiberationSerif-Bold.ttf"},
            {"Liberation Serif Bold Italic (TrueType)", "LiberationSerif-BoldItalic.ttf"},
            {"Liberation Serif Italic (TrueType)", "LiberationSerif-Italic.ttf"},
            {"Liberation Mono (TrueType)", "LiberationMono-Regular.ttf"},
            {"Liberation Mono Bold (TrueType)", "LiberationMono-Bold.ttf"},
            {"Liberation Mono Bold Italic (TrueType)", "LiberationMono-BoldItalic.ttf"},
            {"Liberation Mono Italic (TrueType)", "LiberationMono-Italic.ttf"}
    };

    public static void setupBundledFonts(Context context, Container container) {
        File sharedFontsDir = new File(
                RootFS.find(context).getRootDir(),
                "opt/wine/share/wine/fonts"
        );
        if (!sharedFontsDir.isDirectory()) sharedFontsDir.mkdirs();
        File fontFile = new File(sharedFontsDir, CJK_FONT_FILE);
        if (!fontFile.isFile() || fontFile.length() == 0) {
            FileUtils.copy(context, "fonts/" + CJK_FONT_FILE, fontFile);
            FileUtils.chmod(fontFile, 0644);
        }

        String[][] jpFontEntries = new String[JP_GAME_FONTS.length][2];
        for (int i = 0; i < JP_GAME_FONTS.length; i++) {
            File f = new File(sharedFontsDir, JP_GAME_FONTS[i][1]);
            if (!f.isFile() || f.length() == 0) {
                FileUtils.copy(context, "fonts/" + JP_GAME_FONTS[i][1], f);
                FileUtils.chmod(f, 0644);
            }
            jpFontEntries[i][0] = JP_GAME_FONTS[i][0];
            jpFontEntries[i][1] = "Z:\\opt\\wine\\share\\wine\\fonts\\" + JP_GAME_FONTS[i][1];
        }

        String[][] latinFontEntries = new String[LATIN_COMPAT_FONTS.length][2];
        for (int i = 0; i < LATIN_COMPAT_FONTS.length; i++) {
            File f = new File(sharedFontsDir, LATIN_COMPAT_FONTS[i][1]);
            if (!f.isFile() || f.length() == 0) {
                FileUtils.copy(context, "fonts/" + LATIN_COMPAT_FONTS[i][1], f);
                FileUtils.chmod(f, 0644);
            }
            latinFontEntries[i][0] = LATIN_COMPAT_FONTS[i][0];
            latinFontEntries[i][1] =
                    "Z:\\opt\\wine\\share\\wine\\fonts\\" + LATIN_COMPAT_FONTS[i][1];
        }

        File prefixFontsDir = new File(
                container.getRootDir(),
                ".wine/drive_c/windows/Fonts"
        );
        if (!prefixFontsDir.isDirectory()) prefixFontsDir.mkdirs();
        String[][] liveMakerFontEntries = new String[LIVEMAKER_FONTS.length][2];
        for (int i = 0; i < LIVEMAKER_FONTS.length; i++) {
            String filename = LIVEMAKER_FONTS[i][1];
            File sharedFont = new File(sharedFontsDir, filename);
            if (!sharedFont.isFile() || sharedFont.length() == 0) {
                FileUtils.copy(context, "fonts/" + filename, sharedFont);
                FileUtils.chmod(sharedFont, 0644);
            }

            File installedFont = new File(prefixFontsDir, filename);
            if (!installedFont.isFile() || installedFont.length() == 0) {
                FileUtils.delete(installedFont);
                FileUtils.symlink(sharedFont, installedFont);
                if (!installedFont.isFile()) FileUtils.copy(sharedFont, installedFont);
            }
            liveMakerFontEntries[i][0] = LIVEMAKER_FONTS[i][0];
            liveMakerFontEntries[i][1] = "C:\\windows\\Fonts\\" + filename;
        }

        String fontPath = "Z:\\opt\\wine\\share\\wine\\fonts\\" + CJK_FONT_FILE;
        String[][] fontEntry = {{CJK_FONT_FACE + " (TrueType)", fontPath}};

        // Family aliases that Japanese/Chinese/Korean games request explicitly.
        final String[] fontAliases = {
                "MS Mincho", "MS PMincho",
                "Meiryo", "Meiryo UI", "Yu Gothic", "Yu Gothic UI", "Yu Mincho",
                "MigMix 1P", "Migu 1P",
                "SimSun", "NSimSun", "SimHei", "Microsoft YaHei", "Microsoft YaHei UI",
                "KaiTi", "FangSong", "DengXian", "STSong", "STHeiti",
                "MingLiU", "PMingLiU", "MingLiU_HKSCS", "Microsoft JhengHei",
                "Microsoft JhengHei UI", "DFKai-SB",
                "Gulim", "GulimChe", "Dotum", "DotumChe", "Batang", "BatangChe",
                "Gungsuh", "GungsuhChe", "Malgun Gothic", "Malgun Gothic UI", "New Gulim"
        };

        // Latin/UI base fonts that get a CJK fallback so any missing glyph (e.g. a game's
        // error MessageBox drawn with Tahoma/MS Shell Dlg) resolves to the bundled CJK face
        // without changing Latin rendering.
        final String[] linkBaseFonts = {
                "Tahoma", "Microsoft Sans Serif", "MS Sans Serif", "MS Shell Dlg",
                "MS Shell Dlg 2", "Segoe UI", "Arial", "Times New Roman", "Courier New",
                "Verdana", "System", "FixedSys", "Lucida Console", "Small Fonts"
        };
        String systemLink = CJK_FONT_FILE + "," + CJK_FONT_FACE;

        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", fontEntry);
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", fontEntry);
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", jpFontEntries);
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", jpFontEntries);
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", latinFontEntries);
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", latinFontEntries);
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts",
                    liveMakerFontEntries);
            registryEditor.setStringValues(
                    "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
                    liveMakerFontEntries);
            for (String family : new String[]{"MS Gothic", "MS PGothic", "MS UI Gothic"}) {
                registryEditor.removeValue(
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes",
                        family
                );
            }
            for (String alias : fontAliases) {
                registryEditor.setStringValue(
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes",
                        alias,
                        CJK_FONT_FACE
                );
            }
            String[][] latinAliases = {
                    {"Arial", "Liberation Sans"},
                    {"Arial Black", "Liberation Sans"},
                    {"Arial Narrow", "Liberation Sans"},
                    {"Andale Mono", "Liberation Mono"},
                    {"Comic Sans MS", "Liberation Sans"},
                    {"Courier New", "Liberation Mono"},
                    {"Georgia", "Liberation Serif"},
                    {"Impact", "Liberation Sans"},
                    {"Times New Roman", "Liberation Serif"},
                    {"Trebuchet MS", "Liberation Sans"},
                    {"Verdana", "Liberation Sans"}
            };
            for (String[] alias : latinAliases) {
                registryEditor.setStringValue(
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes",
                        alias[0],
                        alias[1]
                );
            }
            for (String base : linkBaseFonts) {
                registryEditor.setMultiStringValue(
                        "Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink",
                        base,
                        systemLink
                );
            }
        }
    }

    // RPG Maker VX Ace (RGSS3) games packaged without the RTP but shipping their own
    // assets (Game.rgss3a) still abort at launch with "RPGVXAce RTP is required to run
    // this game" whenever Game.ini carries a leftover RTP= line, because RGSS301 does a
    // registry lookup for the RTP path first and bails before reading its own bundled
    // assets. Pre-registering the RTP key (pointing at a real, created folder) satisfies
    // that lookup so both self-contained games and genuinely RTP-dependent ones proceed
    // unmodified, with no game-file edits.
    public static void setupRPGMakerRTP(Context context, Container container) {
        File rootDir = container.getRootDir();
        File rtpDir = new File(rootDir, ".wine/drive_c/ProgramData/Enterbrain/RGSS3/Standard");
        if (!rtpDir.isDirectory()) {
            rtpDir.mkdirs();
            FileUtils.chmod(rtpDir, 0771);
        }
        String rtpPath = "C:\\ProgramData\\Enterbrain\\RGSS3\\Standard";

        File systemRegFile = new File(rootDir, ".wine/system.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            // RPG Maker is 32-bit: cover both the native (32-bit prefix) and the
            // WOW6432Node-redirected (64-bit prefix) HKLM locations.
            registryEditor.setStringValue("Software\\Enterbrain\\RGSS3\\RTP", "Standard", rtpPath);
            registryEditor.setStringValue("Software\\Wow6432Node\\Enterbrain\\RGSS3\\RTP", "Standard", rtpPath);
        }

        File userRegFile = new File(rootDir, ".wine/user.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            registryEditor.setStringValue("Software\\Enterbrain\\RGSS3\\RTP", "Standard", rtpPath);
        }
    }

    // Wine 10.10's DirectSound/mmdevapi device enumeration loads EVERY audio unix driver
    // regardless of the configured Audio driver preference or DllOverrides, and winealsa's
    // init deadlocks on this build — hanging games (e.g. RGSS/RPG Maker VX Ace) the moment
    // they enumerate audio devices to start BGM playback. When ALSA isn't the selected
    // backend we move winealsa.so aside so mmdevapi physically cannot load it and falls back
    // to the selected driver (winepulse); it is restored when ALSA/Silent is chosen. Runs
    // every launch so it self-corrects across driver switches and rootfs re-extractions.
    public static void setupAudioDriverFiles(Context context, String audioDriver) {
        boolean keepAlsa = AudioDrivers.ALSA.equals(audioDriver) || AudioDrivers.SILENT.equals(audioDriver);
        File rootDir = RootFS.find(context).getRootDir();
        final String[] unixDirs = {
                "opt/wine/lib/wine/x86_64-unix",
                "opt/wine/lib/wine/i386-unix"
        };
        for (String dir : unixDirs) {
            File driverDir = new File(rootDir, dir);
            if (!driverDir.isDirectory()) continue;
            File enabled = new File(driverDir, "winealsa.so");
            File disabled = new File(driverDir, "winealsa.so.disabled");
            if (keepAlsa) {
                if (!enabled.isFile() && disabled.isFile()) disabled.renameTo(enabled);
            }
            else if (enabled.isFile()) {
                if (disabled.isFile()) disabled.delete();
                enabled.renameTo(disabled);
            }
        }
    }

    private static final String[] LEGACY_CORE_FONT_FILES = {
            "andalemo.ttf",
            "arial.ttf", "arialbd.ttf", "arialbi.ttf", "ariali.ttf", "ariblk.ttf",
            "comic.ttf", "comicbd.ttf",
            "cour.ttf", "courbd.ttf", "courbi.ttf", "couri.ttf",
            "georgia.ttf", "georgiab.ttf", "georgiai.ttf", "georgiaz.ttf",
            "impact.ttf",
            "times.ttf", "timesbd.ttf", "timesbi.ttf", "timesi.ttf",
            "trebuc.ttf", "trebucbd.ttf", "trebucbi.ttf", "trebucit.ttf",
            "verdana.ttf", "verdanab.ttf", "verdanai.ttf", "verdanaz.ttf",
            "webdings.ttf"
    };

    private static final String[] LEGACY_CORE_FONT_NAMES = {
            "Andale Mono (TrueType)",
            "Arial (TrueType)", "Arial Black (TrueType)", "Arial Bold (TrueType)",
            "Arial Bold Italic (TrueType)", "Arial Italic (TrueType)",
            "Comic Sans MS (TrueType)", "Comic Sans MS Bold (TrueType)",
            "Courier New (TrueType)", "Courier New Bold (TrueType)",
            "Courier New Bold Italic (TrueType)", "Courier New Italic (TrueType)",
            "Georgia (TrueType)", "Georgia Bold (TrueType)",
            "Georgia Bold Italic (TrueType)", "Georgia Italic (TrueType)",
            "Impact (TrueType)",
            "Times New Roman (TrueType)", "Times New Roman Bold (TrueType)",
            "Times New Roman Bold Italic (TrueType)", "Times New Roman Italic (TrueType)",
            "Trebuchet MS (TrueType)", "Trebuchet MS Bold (TrueType)",
            "Trebuchet MS Bold Italic (TrueType)", "Trebuchet MS Italic (TrueType)",
            "Verdana (TrueType)", "Verdana Bold (TrueType)",
            "Verdana Bold Italic (TrueType)", "Verdana Italic (TrueType)",
            "Webdings (TrueType)"
    };

    private static void removeLegacyCoreFonts(File rootDir) {
        File fontsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/Fonts");
        for (String filename : LEGACY_CORE_FONT_FILES) {
            FileUtils.delete(new File(fontsDir, filename));
        }
    }

    private static void setupSystemFonts(WineRegistryEditor registryEditor) {
        String[] registryKeys = {
                "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts",
                "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
        };
        for (String registryKey : registryKeys) {
            for (String fontName : LEGACY_CORE_FONT_NAMES) {
                registryEditor.removeValue(registryKey, fontName);
            }
        }

        final String[][] wineFonts = {
            {"Marlett (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\marlett.ttf"},
            {"Symbol (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\symbol.ttf"},
            {"Tahoma (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahoma.ttf"},
            {"Tahoma Bold (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahomabd.ttf"},
            {"Wingdings (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\wingding.ttf"}
        };

        registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", wineFonts);
        registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", wineFonts);
    }
}
