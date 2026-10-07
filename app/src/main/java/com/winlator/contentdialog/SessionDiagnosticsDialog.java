package com.winlator.contentdialog;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.winlator.R;
import com.winlator.api.ManagedSessionDiagnostics;
import com.winlator.core.AppUtils;
import com.winlator.renderer.GLRenderer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live in-session diagnostics dialog. Samples host memory, thread states, waiting calls,
 * guest-output / frame liveness, and handled/unhandled error counts once per second while
 * visible, to give insight into what a stalled or misbehaving game session is doing.
 * Works for any session; guest-specific rows appear only when managed diagnostics are active.
 */
public class SessionDiagnosticsDialog extends ContentDialog {
    private static final long REFRESH_INTERVAL_MILLIS = 1000;

    private final ManagedSessionDiagnostics diagnostics;
    private final GLRenderer renderer;
    private final TextView textView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            textView.setText(collect());
            handler.postDelayed(this, REFRESH_INTERVAL_MILLIS);
        }
    };

    public SessionDiagnosticsDialog(
            @NonNull Context context,
            ManagedSessionDiagnostics diagnostics,
            GLRenderer renderer
    ) {
        super(context, R.layout.session_diagnostics_dialog);
        this.diagnostics = diagnostics;
        this.renderer = renderer;
        setIcon(R.drawable.icon_task_manager);
        setTitle(context.getString(R.string.session_diagnostics));
        textView = findViewById(R.id.TVSessionDiagnostics);
        View content = findViewById(R.id.LLContent);
        content.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        findViewById(R.id.BTExportSessionDiagnostics)
                .setOnClickListener(view -> exportToFile());
    }

    private void exportToFile() {
        File parent = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "Winlator");
        if (!parent.isDirectory()) parent.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File file = new File(parent, "session_diagnostics_" + stamp + ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(textView.getText().toString());
            String path = file.getPath();
            int index = path.indexOf(Environment.DIRECTORY_DOCUMENTS);
            AppUtils.showToast(getContext(),
                    getContext().getString(R.string.logs_exported_to) + " "
                            + (index >= 0 ? path.substring(index) : path));
        }
        catch (IOException error) {
            AppUtils.showToast(getContext(), "Unable to export diagnostics.");
        }
    }

    @Override
    public void show() {
        super.show();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    public void dismiss() {
        handler.removeCallbacks(refreshTask);
        super.dismiss();
    }

    private CharSequence collect() {
        StringBuilder builder = new StringBuilder();
        appendMemory(builder);
        builder.append('\n');
        appendThreads(builder);
        builder.append('\n');
        appendSession(builder);
        return builder.toString();
    }

    private void appendMemory(StringBuilder builder) {
        Runtime runtime = Runtime.getRuntime();
        long javaMax = runtime.maxMemory();
        long javaTotal = runtime.totalMemory();
        long javaUsed = javaTotal - runtime.freeMemory();
        long nativeUsed = Debug.getNativeHeapAllocatedSize();

        builder.append("== MEMORY ==\n");
        builder.append(String.format(Locale.US, "Java heap : %s / %s (max %s)\n",
                mb(javaUsed), mb(javaTotal), mb(javaMax)));
        builder.append(String.format(Locale.US, "Native    : %s allocated\n",
                mb(nativeUsed)));

        ActivityManager activityManager =
                (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            builder.append(String.format(Locale.US,
                    "System    : %s free / %s total%s\n",
                    mb(memoryInfo.availMem), mb(memoryInfo.totalMem),
                    memoryInfo.lowMemory ? "  [LOW MEMORY]" : ""));
            builder.append(String.format(Locale.US, "Low-mem at: %s\n",
                    mb(memoryInfo.threshold)));
        }
    }

    private void appendThreads(StringBuilder builder) {
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        int runnable = 0;
        int blocked = 0;
        int waiting = 0;
        int timedWaiting = 0;
        for (Thread thread : traces.keySet()) {
            switch (thread.getState()) {
                case RUNNABLE: runnable++; break;
                case BLOCKED: blocked++; break;
                case WAITING: waiting++; break;
                case TIMED_WAITING: timedWaiting++; break;
                default: break;
            }
        }
        int waitingCalls = blocked + waiting + timedWaiting;

        builder.append("== THREADS ==\n");
        builder.append(String.format(Locale.US, "Total     : %d\n", traces.size()));
        builder.append(String.format(Locale.US,
                "Runnable  : %d   Blocked: %d\n", runnable, blocked));
        builder.append(String.format(Locale.US,
                "Waiting   : %d   Timed-wait: %d\n", waiting, timedWaiting));
        builder.append(String.format(Locale.US,
                "Waiting calls (blocked/waiting): %d\n", waitingCalls));

        for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
            Thread thread = entry.getKey();
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING) {
                StackTraceElement[] stack = entry.getValue();
                String top = stack.length > 0 ? stack[0].toString() : "(no frame)";
                builder.append(String.format(Locale.US, "  • %s [%s] %s\n",
                        thread.getName(), state, top));
            }
        }
    }

    private void appendSession(StringBuilder builder) {
        builder.append("== SESSION ==\n");
        if (diagnostics == null) {
            builder.append("Managed diagnostics not active for this session.\n");
            if (renderer != null) {
                long frameAge = frameAge();
                builder.append(String.format(Locale.US, "Last frame: %s\n", ms(frameAge)));
                appendRendererFrameState(builder);
            }
            return;
        }

        builder.append(String.format(Locale.US, "Runtime reached : %s\n",
                diagnostics.isRuntimeReached() ? "yes" : "no (no game window yet)"));
        builder.append(String.format(Locale.US, "Guest output    : %s ago\n",
                ms(diagnostics.getMillisSinceGuestOutput())));
        long frameAge = diagnostics.getMillisSinceLastFrame();
        if (frameAge < 0 && renderer != null) frameAge = frameAge();
        builder.append(String.format(Locale.US, "Last frame      : %s ago\n", ms(frameAge)));
        appendRendererFrameState(builder);
        builder.append(String.format(Locale.US, "Captured lines  : %d\n",
                diagnostics.getCapturedLineCount()));
        builder.append(String.format(Locale.US, "Guest errors    : %d (logged)\n",
                diagnostics.getGuestErrorCount()));
        builder.append(String.format(Locale.US, "Unhandled excns : %d\n",
                diagnostics.getUnhandledExceptionCount()));

        List<String> recent = diagnostics.getRecentOutput(6);
        if (!recent.isEmpty()) {
            builder.append("\n-- recent guest output --\n");
            for (String line : recent) {
                builder.append(line.length() > 160 ? line.substring(0, 160) + "…" : line);
                builder.append('\n');
            }
        }
    }

    private long frameAge() {
        if (renderer == null) return -1;
        long last = renderer.getLastFrameTime();
        return last <= 0 ? -1 : Math.max(0, System.currentTimeMillis() - last);
    }

    private void appendRendererFrameState(StringBuilder builder) {
        if (renderer == null) return;
        long hostAge = renderer.getLastFrameTime() > 0
                ? Math.max(0, System.currentTimeMillis() - renderer.getLastFrameTime())
                : -1;
        builder.append(String.format(Locale.US,
                "Host draws      : %d (last %s ago)\n",
                renderer.getHostFrameCount(), ms(hostAge)));
        builder.append(String.format(Locale.US,
                "Guest updates   : %d\n", renderer.getGuestContentUpdateCount()));
        builder.append(String.format(Locale.US,
                "Guest presents  : %d\n", renderer.getGuestPresentedUpdateCount()));
    }

    private static String ms(long millis) {
        if (millis < 0) return "n/a";
        if (millis < 1000) return millis + " ms";
        return String.format(Locale.US, "%.1f s", millis / 1000.0);
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
