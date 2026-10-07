package com.winlator.core;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;

import com.winlator.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public abstract class ProcessHelper {
    public enum PState {RUNNING, SLEEPING, WAITING, ZOMBIE, STOPPED, DEAD, OTHER}
    public enum TerminationOrigin {
        NATURAL("natural"),
        USER_EXIT("user_exit"),
        SESSION_REPLACED("session_replaced"),
        WINLATOR_TEARDOWN("winlator_teardown"),
        EXTERNAL_SIGNAL("external_signal");

        public final String value;

        TerminationOrigin(String value) {
            this.value = value;
        }
    }

    public static final class TerminationDetails {
        public final boolean termSent;
        public final boolean killSent;

        TerminationDetails(boolean termSent, boolean killSent) {
            this.termSent = termSent;
            this.killSent = killSent;
        }
    }

    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();
    private static final byte SIGKILL = 9;
    private static final byte SIGTERM = 15;
    private static final byte SIGCONT = 18;
    private static final byte SIGSTOP = 19;

    public static class PStat {
        public int pid = 0;
        public String name = "";
        public PState state = PState.OTHER;
        public int parentPID = 0;
        public boolean guestProcess = false;

        @NonNull
        @Override
        public String toString() {
            return pid+" "+name+" "+state+" "+parentPID+" "+guestProcess;
        }
    }

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, SIGCONT);
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, EnvVars envVars) {
        return exec(command, envVars, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir) {
        return exec(command, envVars, workingDir, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback) {
        int pid = -1;
        StartupLog.log("Process start command="+command+" workingDir="+workingDir);
        try {
            ProcessBuilder processBuilder = (new ProcessBuilder(splitCommand(command))).directory(workingDir);
            if (debugCallbacks.isEmpty()) processBuilder.redirectOutput(new File("/dev/null")).redirectErrorStream(true);

            Map<String, String> environment = processBuilder.environment();
            for (String name : envVars) environment.put(name, envVars.get(name));

            java.lang.Process process = processBuilder.start();
            try {
                Field pidField = process.getClass().getDeclaredField("pid");
                pidField.setAccessible(true);
                pid = pidField.getInt(process);
                pidField.setAccessible(false);
            }
            catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
                StartupLog.log("Unable to determine process ID for command="+command, e);
            }
            StartupLog.log("Process started pid="+pid);

            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }

            if (terminationCallback != null) createWaitForThread(process, pid, terminationCallback);
        }
        catch (IOException | SecurityException e) {
            StartupLog.log("Process start failed command="+command, e);
        }
        return pid;
    }

    public static Integer execAndWait(
            String command,
            EnvVars envVars,
            File workingDir,
            long timeoutMillis
    ) {
        StartupLog.log("Process start-and-wait command="+command+" workingDir="+workingDir);
        try {
            ProcessBuilder processBuilder =
                    (new ProcessBuilder(splitCommand(command))).directory(workingDir);
            processBuilder.redirectErrorStream(true);
            Map<String, String> environment = processBuilder.environment();
            if (envVars != null) {
                for (String name : envVars) environment.put(name, envVars.get(name));
            }

            java.lang.Process process = processBuilder.start();
            if (!debugCallbacks.isEmpty()) createDebugThread(process.getInputStream());
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                StartupLog.log("Process timed out command="+command);
                return null;
            }
            int status = process.exitValue();
            StartupLog.log("Process completed command="+command+" status="+status);
            return status;
        }
        catch (IOException | InterruptedException | SecurityException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            StartupLog.log("Process start-and-wait failed command="+command, e);
            return null;
        }
    }

    public static TerminationDetails terminateProcessGroup(int leaderPid, long timeoutMillis) {
        if (leaderPid <= 0) return new TerminationDetails(false, false);

        boolean termSent = signalOwnedProcessGroup(leaderPid, SIGTERM);
        if (waitForProcessGroupExit(leaderPid, timeoutMillis)) {
            return new TerminationDetails(termSent, false);
        }

        boolean killSent = signalOwnedProcessGroup(leaderPid, SIGKILL);
        waitForProcessGroupExit(leaderPid, Math.min(timeoutMillis, 500));
        return new TerminationDetails(termSent, killSent);
    }

    private static boolean signalOwnedProcessGroup(int leaderPid, int signal) {
        boolean sent = false;
        try {
            Os.kill(-leaderPid, signal);
            sent = true;
        }
        catch (ErrnoException | SecurityException e) {
            StartupLog.log(
                    "Unable to signal process group pgid="+leaderPid+" signal="+signal,
                    e
            );
        }

        for (int pid : getDescendantProcessIds(leaderPid)) {
            try {
                Os.kill(pid, signal);
                sent = true;
            }
            catch (ErrnoException | SecurityException ignored) {
            }
        }
        return sent;
    }

    private static boolean waitForProcessGroupExit(int leaderPid, long timeoutMillis) {
        long deadline = android.os.SystemClock.uptimeMillis() + Math.max(0, timeoutMillis);
        do {
            if (!isProcessGroupAlive(leaderPid)) return true;
            android.os.SystemClock.sleep(25);
        }
        while (android.os.SystemClock.uptimeMillis() < deadline);
        return !isProcessGroupAlive(leaderPid);
    }

    private static boolean isProcessGroupAlive(int leaderPid) {
        try {
            Os.kill(-leaderPid, 0);
            return true;
        }
        catch (ErrnoException | SecurityException ignored) {
            return false;
        }
    }

    static List<Integer> getDescendantProcessIds(int rootPid) {
        return collectDescendantProcessIds(readProcesses(), rootPid);
    }

    static List<Integer> collectDescendantProcessIds(List<PStat> processes, int rootPid) {
        Set<Integer> descendants = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (PStat process : processes) {
                if (process.pid == rootPid || descendants.contains(process.pid)) continue;
                if (process.parentPID == rootPid || descendants.contains(process.parentPID)) {
                    descendants.add(process.pid);
                    changed = true;
                }
            }
        }
        while (changed);
        return new ArrayList<>(descendants);
    }

    private static void createDebugThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    StartupLog.log("process: "+line);
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                        else if (MainActivity.DEBUG_MODE) System.out.println(line);
                    }
                }
            }
            catch (IOException e) {}
        });
    }

    private static void createWaitForThread(java.lang.Process process, int pid, final Callback<Integer> terminationCallback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int status = process.waitFor();
                StartupLog.log("Process terminated pid="+pid+" status="+status);
                terminationCallback.call(status);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                StartupLog.log("Interrupted while waiting for pid="+pid, e);
            }
        });
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
        }
    }

    /**
     * Feeds a synthetic line into the debug-output stream so internal diagnostics (for
     * example X-server request rejections) appear in the same captured/exported log as guest
     * output. No-op when nothing is listening.
     */
    public static void emitDebugMessage(String line) {
        if (line == null) return;
        synchronized (debugCallbacks) {
            if (debugCallbacks.isEmpty()) return;
            for (Callback<String> callback : debugCallbacks) callback.call(line);
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static List<PStat> getChildProcesses() {
        ArrayList<PStat> result = new ArrayList<>();
        int parentPID = Os.getpid();

        for (PStat process : readProcesses()) {
            if (process.parentPID == parentPID || process.pid > parentPID) {
                process.guestProcess =
                        process.name.contains("wine") || process.name.contains(".exe");
                result.add(process);
            }
        }
        return result;
    }

    private static List<PStat> readProcesses() {
        File procFile = new File("/proc");
        String[] pids = procFile.list(
                (file, name) -> (new File(file, name)).isDirectory() && name.matches("[0-9]+")
        );
        if (pids == null) return Collections.emptyList();

        ArrayList<PStat> result = new ArrayList<>();
        for (String pid : pids) {
            PStat process = readProcessStat(pid);
            if (process != null) result.add(process);
        }
        return result;
    }

    private static PStat readProcessStat(String pid) {
        try (Scanner scanner = new Scanner(new FileInputStream("/proc/"+pid+"/stat"))) {
            PStat pstat = new PStat();
            int index = 0;
            while (scanner.hasNext() && index < 4) {
                switch (index++) {
                    case 0:
                        pstat.pid = scanner.nextInt();
                        break;
                    case 1:
                        Pattern oldDelimiter = scanner.delimiter();
                        scanner.useDelimiter("\\)");
                        pstat.name = scanner.hasNext() ? scanner.next().substring(2) : "";
                        scanner.useDelimiter(oldDelimiter);
                        if (scanner.hasNext()) scanner.next();
                        break;
                    case 2:
                        pstat.state = parseProcessState(scanner.next());
                        break;
                    case 3:
                        pstat.parentPID = scanner.nextInt();
                        break;
                }
            }
            return pstat.pid > 0 ? pstat : null;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static PState parseProcessState(String state) {
        switch (state) {
            case "R":
                return PState.RUNNING;
            case "S":
                return PState.SLEEPING;
            case "D":
                return PState.WAITING;
            case "Z":
                return PState.ZOMBIE;
            case "T":
                return PState.STOPPED;
            case "X":
                return PState.DEAD;
            default:
                return PState.OTHER;
        }
    }
}
