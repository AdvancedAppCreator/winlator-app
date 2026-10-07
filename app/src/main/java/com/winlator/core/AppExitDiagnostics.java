package com.winlator.core;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AppExitDiagnostics {
    private static final String PREFS_NAME = "app_exit_diagnostics";
    private static final String PREF_LAST_EXIT_TIMESTAMP = "last_exit_timestamp";
    private static final long HEARTBEAT_INTERVAL_SECONDS = 2;
    private static final long MAX_TRACE_BYTES = 4 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FILE_TIMESTAMP =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
    private static Context context;
    private static JSONObject activeSession;
    private static ScheduledExecutorService heartbeatExecutor;

    private AppExitDiagnostics() {}

    public static void initialize(Context value) {
        synchronized (LOCK) {
            context = value.getApplicationContext();
        }
        installJavaCrashHandler();
        Thread exitHistoryThread = new Thread(
                AppExitDiagnostics::recordPreviousProcessExit,
                "winlator-exit-history"
        );
        exitHistoryThread.setDaemon(true);
        exitHistoryThread.start();
    }

    public static void beginSession(Context value, Map<String, String> details) {
        synchronized (LOCK) {
            context = value.getApplicationContext();
            stopHeartbeatLocked();
            activeSession = new JSONObject();
            try {
                activeSession.put("state", "active");
                activeSession.put("startedAt", System.currentTimeMillis());
                activeSession.put("startedElapsedRealtime", SystemClock.elapsedRealtime());
                activeSession.put("phase", "configuration_ready");
                activeSession.put("phaseAt", System.currentTimeMillis());
                JSONObject values = new JSONObject();
                for (Map.Entry<String, String> entry : details.entrySet()) {
                    values.put(entry.getKey(), entry.getValue());
                }
                activeSession.put("details", values);
            }
            catch (JSONException error) {
                StartupLog.log("Unable to initialize active-session diagnostics", error);
                activeSession = null;
                return;
            }
            writeSessionStateLocked(false);
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor((runnable) -> {
                Thread thread = new Thread(runnable, "winlator-exit-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            heartbeatExecutor.scheduleAtFixedRate(
                    AppExitDiagnostics::writeHeartbeat,
                    HEARTBEAT_INTERVAL_SECONDS,
                    HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
    }

    public static void updatePhase(String phase) {
        synchronized (LOCK) {
            if (activeSession == null) return;
            try {
                activeSession.put("phase", phase);
                activeSession.put("phaseAt", System.currentTimeMillis());
            }
            catch (JSONException error) {
                StartupLog.log("Unable to update active-session phase", error);
                return;
            }
            writeSessionStateLocked(false);
        }
    }

    public static void completeSession(String outcome) {
        synchronized (LOCK) {
            if (activeSession == null) return;
            try {
                activeSession.put("state", "completed");
                activeSession.put("outcome", outcome);
                activeSession.put("completedAt", System.currentTimeMillis());
            }
            catch (JSONException error) {
                StartupLog.log("Unable to complete active-session diagnostics", error);
            }
            writeSessionStateLocked(false);
            File activeFile = getActiveSessionFile();
            File completedFile = new File(getDiagnosticsDirectory(), "last_completed_session.json");
            copyFile(activeFile, completedFile);
            if (activeFile.isFile() && !activeFile.delete()) {
                StartupLog.log("Unable to remove completed active-session marker "+activeFile);
            }
            activeSession = null;
            stopHeartbeatLocked();
        }
    }

    static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "low_memory";
            case ApplicationExitInfo.REASON_CRASH:
                return "java_crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "native_crash";
            case ApplicationExitInfo.REASON_ANR:
                return "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "initialization_failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "permission_change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "excessive_resource_usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "dependency_died";
            case ApplicationExitInfo.REASON_OTHER:
                return "other";
            case ApplicationExitInfo.REASON_FREEZER:
                return "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                return "package_state_change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                return "package_updated";
            case ApplicationExitInfo.REASON_UNKNOWN:
            default:
                return "unknown";
        }
    }

    private static void writeHeartbeat() {
        synchronized (LOCK) {
            writeSessionStateLocked(true);
        }
    }

    private static void writeSessionStateLocked(boolean collectMemory) {
        if (activeSession == null || context == null) return;
        try {
            activeSession.put("heartbeatAt", System.currentTimeMillis());
            activeSession.put("heartbeatElapsedRealtime", SystemClock.elapsedRealtime());
            if (collectMemory) {
                activeSession.put("processPssKb", Debug.getPss());
                activeSession.put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize());
                Runtime runtime = Runtime.getRuntime();
                activeSession.put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
                activeSession.put("javaHeapMaxBytes", runtime.maxMemory());

                ActivityManager activityManager =
                        (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
                if (activityManager != null) {
                    ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                    activityManager.getMemoryInfo(memoryInfo);
                    activeSession.put("systemAvailableBytes", memoryInfo.availMem);
                    activeSession.put("systemTotalBytes", memoryInfo.totalMem);
                    activeSession.put("systemLowMemory", memoryInfo.lowMemory);
                }
            }
        }
        catch (JSONException error) {
            StartupLog.log("Unable to update active-session heartbeat", error);
            return;
        }
        writeJsonAtomically(getActiveSessionFile(), activeSession);
    }

    private static void recordPreviousProcessExit() {
        Context current;
        synchronized (LOCK) {
            current = context;
        }
        if (current == null) return;

        File previousActiveSession = archivePreviousActiveSession();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        ActivityManager activityManager =
                (ActivityManager)current.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return;
        SharedPreferences preferences =
                current.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastTimestamp = preferences.getLong(PREF_LAST_EXIT_TIMESTAMP, 0);
        List<ApplicationExitInfo> exits;
        try {
            exits = activityManager.getHistoricalProcessExitReasons(
                    current.getPackageName(),
                    0,
                    16
            );
        }
        catch (SecurityException error) {
            StartupLog.log("Unable to read application exit history", error);
            return;
        }

        long newestTimestamp = lastTimestamp;
        for (int index = exits.size() - 1; index >= 0; index--) {
            ApplicationExitInfo exit = exits.get(index);
            if (exit.getTimestamp() <= lastTimestamp) continue;
            JSONObject record = new JSONObject();
            try {
                record.put("recordedAt", System.currentTimeMillis());
                record.put("timestamp", exit.getTimestamp());
                record.put("pid", exit.getPid());
                record.put("processName", exit.getProcessName());
                record.put("reason", exit.getReason());
                record.put("reasonName", reasonName(exit.getReason()));
                record.put("status", exit.getStatus());
                record.put("importance", exit.getImportance());
                record.put("pssKb", exit.getPss());
                record.put("rssKb", exit.getRss());
                record.put("description", exit.getDescription());
                if (previousActiveSession != null) {
                    record.put("previousActiveSession", previousActiveSession.getName());
                }
                String traceName = copyExitTrace(exit);
                if (traceName != null) record.put("traceFile", traceName);
            }
            catch (JSONException error) {
                StartupLog.log("Unable to serialize application exit history", error);
                continue;
            }
            appendJsonLine(getExitHistoryFile(), record);
            newestTimestamp = Math.max(newestTimestamp, exit.getTimestamp());
            StartupLog.log(
                    "Previous process exit reason="
                            +reasonName(exit.getReason())
                            +" status="+exit.getStatus()
                            +" timestamp="+exit.getTimestamp()
                            +" description="+exit.getDescription()
            );
        }
        if (newestTimestamp > lastTimestamp) {
            preferences.edit().putLong(PREF_LAST_EXIT_TIMESTAMP, newestTimestamp).apply();
        }
    }

    private static File archivePreviousActiveSession() {
        File activeFile = getActiveSessionFile();
        if (!activeFile.isFile()) return null;
        JSONObject previous = readJson(activeFile);
        if (previous != null && "completed".equals(previous.optString("state"))) {
            if (!activeFile.delete()) {
                StartupLog.log("Unable to clear completed active-session marker "+activeFile);
            }
            return null;
        }
        File destination = new File(
                getDiagnosticsDirectory(),
                "abrupt_session_"+FILE_TIMESTAMP.format(new Date())+".json"
        );
        if (!copyFile(activeFile, destination)) return null;
        if (!activeFile.delete()) {
            StartupLog.log("Unable to clear previous active-session marker "+activeFile);
        }
        return destination;
    }

    private static String copyExitTrace(ApplicationExitInfo exit) {
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) return null;
            String name = "exit_trace_"
                    +exit.getTimestamp()
                    +"_"+exit.getPid()
                    +".bin";
            File destination = new File(getDiagnosticsDirectory(), name);
            try (BufferedInputStream reader = new BufferedInputStream(input);
                 BufferedOutputStream writer =
                         new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[8192];
                long copied = 0;
                int count;
                while ((count = reader.read(buffer)) != -1 && copied < MAX_TRACE_BYTES) {
                    int writable = (int)Math.min(count, MAX_TRACE_BYTES - copied);
                    writer.write(buffer, 0, writable);
                    copied += writable;
                }
            }
            return name;
        }
        catch (IOException | SecurityException error) {
            StartupLog.log("Unable to copy application exit trace", error);
            return null;
        }
    }

    private static void installJavaCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof DiagnosticExceptionHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(
                new DiagnosticExceptionHandler(previous)
        );
    }

    private static File getDiagnosticsDirectory() {
        File documents =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File directory = new File(documents, "Winlator");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            StartupLog.log("Unable to create application-exit directory "+directory);
        }
        return directory;
    }

    private static File getActiveSessionFile() {
        return new File(getDiagnosticsDirectory(), "active_android_session.json");
    }

    private static File getExitHistoryFile() {
        return new File(getDiagnosticsDirectory(), "app_exit_history.jsonl");
    }

    private static void writeJsonAtomically(File destination, JSONObject value) {
        File temporary = new File(destination.getParentFile(), destination.getName()+".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.getFD().sync();
        }
        catch (IOException | JSONException | SecurityException error) {
            StartupLog.log("Unable to write diagnostic file "+destination, error);
            return;
        }
        if (destination.isFile() && !destination.delete()) {
            StartupLog.log("Unable to replace diagnostic file "+destination);
            return;
        }
        if (!temporary.renameTo(destination)) {
            StartupLog.log("Unable to publish diagnostic file "+destination);
        }
    }

    private static void appendJsonLine(File destination, JSONObject value) {
        try (FileWriter writer = new FileWriter(destination, true)) {
            writer.write(value.toString());
            writer.write('\n');
        }
        catch (IOException | SecurityException error) {
            StartupLog.log("Unable to append diagnostic file "+destination, error);
        }
    }

    private static JSONObject readJson(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int)Math.min(file.length(), 1024 * 1024)];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count == -1) break;
                offset += count;
            }
            return new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
        }
        catch (IOException | JSONException | SecurityException error) {
            StartupLog.log("Unable to read diagnostic file "+file, error);
            return null;
        }
    }

    private static boolean copyFile(File source, File destination) {
        if (!source.isFile()) return false;
        try (BufferedInputStream input =
                     new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output =
                     new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return true;
        }
        catch (IOException | SecurityException error) {
            StartupLog.log("Unable to copy diagnostic file "+source+" to "+destination, error);
            return false;
        }
    }

    private static void stopHeartbeatLocked() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private static final class DiagnosticExceptionHandler
            implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler delegate;

        private DiagnosticExceptionHandler(Thread.UncaughtExceptionHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            Log.e("WinlatorSecure", "Uncaught Java exception thread="+thread.getName(), error);
            writeJavaCrashMarker(thread, error);
            if (delegate != null) delegate.uncaughtException(thread, error);
        }

        private void writeJavaCrashMarker(Thread thread, Throwable error) {
            File documents =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            File directory = new File(documents, "Winlator");
            if (!directory.isDirectory()) directory.mkdirs();
            File file = new File(directory, "last_java_crash.txt");
            try (PrintWriter writer = new PrintWriter(new FileOutputStream(file))) {
                writer.println("timestamp="+System.currentTimeMillis());
                writer.println("thread="+thread.getName());
                writer.println("type="+error.getClass().getName());
                writer.println("message="+error.getMessage());
                error.printStackTrace(writer);
            }
            catch (IOException | SecurityException ignored) {
                Log.e("WinlatorSecure", "Unable to write Java crash marker");
            }
        }
    }
}
