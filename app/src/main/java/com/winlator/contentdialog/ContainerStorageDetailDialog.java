package com.winlator.contentdialog;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;

/**
 * Drills into a single container and lists its largest folders by real on-disk (allocated) size, so
 * the "container is 9 GB but only holds 3 GB of files" gap can be traced to where it actually lives
 * (e.g. GPU/DXVK shader caches under {@code .cache}, which sit outside {@code drive_c}). Container
 * top-level entries and the {@code drive_c} subfolders are listed together, largest first.
 */
public class ContainerStorageDetailDialog extends ContentDialog {
    public ContainerStorageDetailDialog(@NonNull Activity activity, Container container) {
        super(activity, R.layout.container_storage_detail_dialog);
        setTitle(container.getName());
        setIcon(R.drawable.icon_info);
        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        View content = findViewById(R.id.LLContent);
        content.getLayoutParams().width = AppUtils.getPreferredDialogWidth(activity);

        final TextView tvTotal = findViewById(R.id.TVTotal);
        final LinearLayout llList = findViewById(R.id.LLList);
        final File rootDir = container.getRootDir();

        Executors.newSingleThreadExecutor().execute(() -> {
            ArrayList<Entry> entries = new ArrayList<>();
            collectChildren(rootDir, "", entries);
            collectChildren(new File(rootDir, ".wine/drive_c"), "C:\\", entries);
            Collections.sort(entries, (a, b) -> Long.compare(b.size, a.size));

            long total = Math.max(0, FileUtils.getAllocatedSize(rootDir));
            activity.runOnUiThread(() -> {
                tvTotal.setText(StringUtils.formatBytes(total));
                llList.removeAllViews();
                boolean any = false;
                for (Entry entry : entries) {
                    if (entry.size < 1024 * 1024) continue;
                    addRow(activity, llList, entry.label, entry.size);
                    any = true;
                }
                if (!any) addRow(activity, llList, "\u2014", 0);
            });
        });
    }

    private static void collectChildren(File dir, String prefix, ArrayList<Entry> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            long size = Math.max(0, FileUtils.getAllocatedSize(child));
            out.add(new Entry(prefix + child.getName(), size));
        }
    }

    private static void addRow(Activity activity, LinearLayout parent, String label, long size) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvLabel = new TextView(activity);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvLabel.setText(label);
        tvLabel.setSingleLine(true);

        TextView tvSize = new TextView(activity);
        tvSize.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tvSize.setText(StringUtils.formatBytes(size));

        row.addView(tvLabel);
        row.addView(tvSize);
        parent.addView(row);
    }

    private static final class Entry {
        final String label;
        final long size;

        Entry(String label, long size) {
            this.label = label;
            this.size = size;
        }
    }
}
