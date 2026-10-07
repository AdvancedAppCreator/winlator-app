package com.winlator.core;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class StartupLog {
    private static final String TAG = "WinlatorSecure";
    private static final long MAX_LOG_SIZE = 2 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static Context context;

    private StartupLog() {}

    public static void initialize(Context value) {
        synchronized (LOCK) {
            context = value.getApplicationContext();
        }
    }

    public static void startSession(String reason) {
        Context current = context;
        if (current == null) return;

        File logFile = getLogFile();
        if (logFile.isFile() && logFile.length() > MAX_LOG_SIZE && !logFile.delete()) {
            Log.w(TAG, "Unable to rotate "+logFile);
        }

        log("===== "+reason+" =====");
        log("package="+current.getPackageName()
            +" sdk="+Build.VERSION.SDK_INT
            +" dataDir="+current.getApplicationInfo().dataDir
            +" filesDir="+current.getFilesDir()
            +" nativeLibraryDir="+current.getApplicationInfo().nativeLibraryDir);
    }

    public static void log(String message) {
        String line = "["+TIMESTAMP.format(new Date())+"] "+message;
        Log.i(TAG, message);

        synchronized (LOCK) {
            File logFile = getLogFile();
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Log.e(TAG, "Unable to create log directory "+parent);
                return;
            }

            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(line);
                writer.write('\n');
            }
            catch (IOException | SecurityException e) {
                Log.e(TAG, "Unable to write "+logFile, e);
            }
        }
    }

    public static void log(String message, Throwable error) {
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        log(message+"\n"+buffer);
    }

    public static File getLogFile() {
        File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        return new File(new File(documents, "WinlatorSecure"), "startup.log");
    }
}
