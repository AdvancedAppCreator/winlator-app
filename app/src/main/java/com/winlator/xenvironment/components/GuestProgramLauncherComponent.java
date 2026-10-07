package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.core.ProfilePathRelocator;
import com.winlator.core.StartupLog;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private static final class LaunchState {
        int pid = -1;
        EnvVars envVars;
        File workingDir;
        File box64Executable;
        File activeProcessFile;
        ProcessHelper.TerminationOrigin requestedOrigin;
        boolean gracefulShutdownRequested;
        boolean gracefulShutdownCompleted;
        boolean termSent;
        boolean killSent;
        boolean cleanupStarted;
    }

    public static final class TerminationResult {
        public final int exitCode;
        public final ProcessHelper.TerminationOrigin origin;
        public final boolean gracefulShutdownRequested;
        public final boolean gracefulShutdownCompleted;
        public final boolean termSent;
        public final boolean killSent;

        TerminationResult(
                int exitCode,
                ProcessHelper.TerminationOrigin origin,
                boolean gracefulShutdownRequested,
                boolean gracefulShutdownCompleted,
                boolean termSent,
                boolean killSent
        ) {
            this.exitCode = exitCode;
            this.origin = origin;
            this.gracefulShutdownRequested = gracefulShutdownRequested;
            this.gracefulShutdownCompleted = gracefulShutdownCompleted;
            this.termSent = termSent;
            this.killSent = killSent;
        }
    }

    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private Callback<Integer> terminationCallback;
    private Callback<TerminationResult> detailedTerminationCallback;
    private boolean diagnosticsEnabled;
    private LaunchState activeLaunch;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            extractBox64File();
            copyDefaultBox64RCFile();
            int launchedPid = execGuestProgram();
            pid = activeLaunch != null ? launchedPid : -1;
            if (pid == -1) {
                TerminationResult result = new TerminationResult(
                        -1,
                        ProcessHelper.TerminationOrigin.NATURAL,
                        false,
                        false,
                        false,
                        false
                );
                if (detailedTerminationCallback != null) {
                    detailedTerminationCallback.call(result);
                }
                if (terminationCallback != null) terminationCallback.call(-1);
            }
        }
    }

    @Override
    public void stop() {
        stop(ProcessHelper.TerminationOrigin.WINLATOR_TEARDOWN);
    }

    @Override
    public void stop(ProcessHelper.TerminationOrigin origin) {
        synchronized (lock) {
            if (pid != -1) {
                LaunchState launch = activeLaunch;
                if (launch != null) launch.requestedOrigin = origin;
                cleanupWineSession(launch);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public void setDetailedTerminationCallback(
            Callback<TerminationResult> detailedTerminationCallback
    ) {
        this.detailedTerminationCallback = detailedTerminationCallback;
    }

    public void setDiagnosticsEnabled(boolean diagnosticsEnabled) {
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public static void cleanupStaleSession(RootFS rootFS) {
        File activeProcessFile =
                new File(rootFS.getRootDir(), ".winlator/active_guest_process");
        if (!activeProcessFile.isFile()) return;

        String value = FileUtils.readString(activeProcessFile).trim();
        try {
            int stalePid = Integer.parseInt(value);
            String commandLine =
                    readOptionalString(new File("/proc/"+stalePid+"/cmdline"));
            if (commandLine == null || commandLine.isEmpty()) {
                StartupLog.log("Recorded stale guest process is no longer running pid="+stalePid);
            }
            else if (commandLine.contains("box64") ||
                    commandLine.contains("wine") ||
                    commandLine.contains("libbox64launcher")) {
                StartupLog.log("Cleaning stale guest process group pgid="+stalePid);
                ProcessHelper.terminateProcessGroup(stalePid, 1000);
            }
            else {
                StartupLog.log(
                        "Ignoring stale guest PID because it no longer identifies Wine/Box64 pid="
                                +stalePid
                );
            }
        }
        catch (NumberFormatException error) {
            StartupLog.log("Invalid stale guest process record value="+value, error);
        }
        FileUtils.delete(activeProcessFile);
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();
        Context context = environment.getContext();
        String box64Version = getBox64Version(context);
        if (box64Version.equals(DefaultVersion.BOX64) && !ProfilePathRelocator.ensureRootAlias(rootDir)) {
            StartupLog.log("Aborting Box64 launch because the stable rootfs alias is unavailable");
            return -1;
        }

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        if (box64Version.equals(DefaultVersion.BOX64)) {
            envVars.put(
                    "LD_LIBRARY_PATH",
                    "/system/lib64:"
                            +context.getApplicationInfo().nativeLibraryDir+":"
                            +rootDir+"/usr/lib:"
                            +rootDir+"/lib"
            );
        }
        else {
            envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        }
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINLATOR_ROOTFS", rootDir);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();
        File gstreamerCacheDir = new File(rootDir, RootFS.USER_CACHE_PATH+"/gstreamer-1.0");
        if (!gstreamerCacheDir.isDirectory()) gstreamerCacheDir.mkdirs();

        File box64Executable = getBox64Executable(context, box64Version);
        String command = box64Executable+" "+guestExecutable;
        LaunchState launch = new LaunchState();
        launch.envVars = new EnvVars();
        launch.envVars.putAll(envVars);
        launch.workingDir = rootDir;
        launch.box64Executable = box64Executable;
        launch.activeProcessFile =
                new File(rootDir, ".winlator/active_guest_process");

        StartupLog.log("Launching Box64 version="+box64Version
            +" path="+box64Executable
            +" exists="+box64Executable.isFile()
            +" executable="+box64Executable.canExecute()
            +" workingDir="+rootDir);

        activeLaunch = launch;
        launch.pid = ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            StartupLog.log("Box64 process exited status="+status);
            cleanupWineSession(launch);
            TerminationResult result;
            synchronized (lock) {
                if (pid == launch.pid) pid = -1;
                if (activeLaunch == launch) activeLaunch = null;
                String recordedPid = readOptionalString(launch.activeProcessFile);
                if (recordedPid != null &&
                        String.valueOf(launch.pid).equals(recordedPid.trim())) {
                    FileUtils.delete(launch.activeProcessFile);
                }
                ProcessHelper.TerminationOrigin origin = launch.requestedOrigin;
                if (origin == null) {
                    origin = status >= 128 && status <= 192
                            ? ProcessHelper.TerminationOrigin.EXTERNAL_SIGNAL
                            : ProcessHelper.TerminationOrigin.NATURAL;
                }
                result = new TerminationResult(
                        status,
                        origin,
                        launch.gracefulShutdownRequested,
                        launch.gracefulShutdownCompleted,
                        launch.termSent,
                        launch.killSent
                );
            }
            if (detailedTerminationCallback != null) detailedTerminationCallback.call(result);
            if (terminationCallback != null) terminationCallback.call(status);
        });
        if (launch.pid > 0) {
            File parent = launch.activeProcessFile.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            FileUtils.writeString(launch.activeProcessFile, String.valueOf(launch.pid));
        }
        else if (activeLaunch == launch) {
            activeLaunch = null;
        }
        return launch.pid;
    }

    private void requestGracefulWineShutdown(LaunchState launch) {
        if (launch == null ||
                launch.envVars == null ||
                launch.workingDir == null ||
                launch.box64Executable == null) {
            return;
        }
        launch.gracefulShutdownRequested = true;
        Integer status = ProcessHelper.execAndWait(
                launch.box64Executable+" wineserver -k",
                launch.envVars,
                launch.workingDir,
                5000
        );
        launch.gracefulShutdownCompleted = status != null && status == 0;
        StartupLog.log(
                "Graceful wineserver shutdown status="
                        +(status != null ? status : "timeout_or_start_failure")
        );
    }

    private void cleanupWineSession(LaunchState launch) {
        if (launch == null) return;
        synchronized (launch) {
            if (launch.cleanupStarted) return;
            launch.cleanupStarted = true;
        }
        requestGracefulWineShutdown(launch);
        ProcessHelper.TerminationDetails details =
                ProcessHelper.terminateProcessGroup(launch.pid, 2000);
        launch.termSent = details.termSent;
        launch.killSent = details.killSent;
    }

    private static String readOptionalString(File file) {
        byte[] data = FileUtils.read(file);
        return data != null ? new String(data, StandardCharsets.UTF_8) : null;
    }

    private void extractBox64File() {
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = getBox64Version(context);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        if (box64Version.equals(DefaultVersion.BOX64)) {
            File nativeLibraryDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File packagedLauncher = new File(nativeLibraryDir, "libbox64launcher.so");
            File packagedBox64 = new File(nativeLibraryDir, "libbox64.so");
            File packagedLoader = new File(nativeLibraryDir, "libglibcloader.so");
            if (!packagedLauncher.isFile() || !packagedBox64.isFile() || !packagedLoader.isFile()) {
                StartupLog.log("Packaged Box64 runtime is incomplete: launcher="+packagedLauncher.isFile()
                    +" box64="+packagedBox64.isFile()
                    +" loader="+packagedLoader.isFile());
                return;
            }
            preferences.edit().putString("current_box64_version", box64Version).apply();
            return;
        }

        if (!box64Version.equals(currentBox64Version)) {
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
    }

    private String getBox64Version(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString("box64_version", DefaultVersion.BOX64);
    }

    private File getBox64Executable(Context context, String box64Version) {
        if (box64Version.equals(DefaultVersion.BOX64)) {
            return new File(context.getApplicationInfo().nativeLibraryDir, "libbox64launcher.so");
        }
        return new File(environment.getRootFS().getRootDir(), "/usr/local/bin/box64");
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "1");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }
        else if (diagnosticsEnabled) {
            envVars.put("BOX64_NOBANNER", "0");
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
            envVars.put("BOX64_SHOWSEGV", "1");
            envVars.put("BOX64_DLSYM_ERROR", "1");
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
        String pluginPath = rootFS.getRootDir()+"/usr/lib/gstreamer-1.0";
        envVars.put("GST_PLUGIN_PATH", pluginPath);
        envVars.put("GST_PLUGIN_SYSTEM_PATH", pluginPath);
        envVars.put(
                "GST_REGISTRY",
                rootFS.getRootDir()
                        +RootFS.USER_CACHE_PATH
                        +"/gstreamer-1.0/registry.aarch64.bin"
        );
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}