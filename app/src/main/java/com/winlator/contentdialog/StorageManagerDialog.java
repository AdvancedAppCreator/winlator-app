package com.winlator.contentdialog;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.StringUtils;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide storage breakdown for the whole Winlator private data directory. Unlike the per-container
 * {@link StorageInfoDialog}, this attributes the total across the base system, every container, the
 * downloaded component packages (the usual reclaimable bulk) and cache, and offers one-tap cleanup of
 * the reclaimable categories. Sizes use real on-disk block usage and are measured off the UI thread.
 */
public class StorageManagerDialog extends ContentDialog {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public StorageManagerDialog(@NonNull Activity activity) {
        super(activity, R.layout.storage_manager_dialog);
        setTitle(R.string.storage);
        setIcon(R.drawable.icon_info);
        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        View content = findViewById(R.id.LLContent);
        content.getLayoutParams().width = AppUtils.getPreferredDialogWidth(activity);

        final TextView tvTotal = findViewById(R.id.TVTotal);
        final TextView tvFree = findViewById(R.id.TVFree);
        final TextView tvBaseSystem = findViewById(R.id.TVBaseSystem);
        final TextView tvComponents = findViewById(R.id.TVComponents);
        final TextView tvCache = findViewById(R.id.TVCache);
        final TextView tvOther = findViewById(R.id.TVOther);
        final LinearLayout llContainers = findViewById(R.id.LLContainers);
        final Button btFreeComponents = findViewById(R.id.BTFreeComponents);
        final Button btClearCache = findViewById(R.id.BTClearCache);

        final File filesDir = activity.getFilesDir();
        final File cacheDir = activity.getCacheDir();
        final File rootfsDir = RootFS.find(activity).getRootDir();
        final File componentsDir = new File(filesDir, "installed_components");

        btFreeComponents.setOnClickListener((v) -> ContentDialog.confirm(activity,
                R.string.confirm_free_downloaded, () -> executor.execute(() -> {
                    long before = size(componentsDir);
                    FileUtils.clear(componentsDir);
                    activity.runOnUiThread(() -> {
                        AppUtils.showToast(activity, activity.getString(R.string.freed_space,
                                StringUtils.formatBytes(Math.max(0, before))));
                        dismiss();
                    });
                })));

        btClearCache.setOnClickListener((v) -> executor.execute(() -> {
            long total = Math.max(0, size(cacheDir));
            FileUtils.clear(cacheDir);
            for (File container : containerCacheDirs(rootfsDir)) {
                total += Math.max(0, size(container));
                FileUtils.clear(container);
            }
            final long freed = total;
            activity.runOnUiThread(() -> {
                AppUtils.showToast(activity, activity.getString(R.string.freed_space,
                        StringUtils.formatBytes(freed)));
                dismiss();
            });
        }));

        executor.execute(() -> {
            long cacheSize = Math.max(0, size(cacheDir));
            activity.runOnUiThread(() -> {
                tvCache.setText(StringUtils.formatBytes(cacheSize));
                btClearCache.setEnabled(true);
            });

            long componentsSize = Math.max(0, size(componentsDir));
            activity.runOnUiThread(() -> {
                tvComponents.setText(StringUtils.formatBytes(componentsSize));
                btFreeComponents.setEnabled(componentsSize > 0);
            });

            long containersTotal = 0;
            ContainerManager manager = new ContainerManager(activity);
            ArrayList<Container> containers = manager.getContainers();
            for (Container container : containers) {
                long size = Math.max(0, size(container.getRootDir()));
                containersTotal += size;
                addContainerRow(activity, llContainers, container, size);
            }

            long rootfsSize = Math.max(0, size(rootfsDir));
            long baseSystem = Math.max(0, rootfsSize - containersTotal);
            activity.runOnUiThread(() -> tvBaseSystem.setText(StringUtils.formatBytes(baseSystem)));

            long filesTotal = Math.max(0, size(filesDir));
            long other = Math.max(0, filesTotal - rootfsSize - componentsSize);
            long grandTotal = filesTotal + cacheSize;
            long freeSpace = FileUtils.getAvailableBytes(filesDir);
            activity.runOnUiThread(() -> {
                tvOther.setText(StringUtils.formatBytes(other));
                tvTotal.setText(StringUtils.formatBytes(grandTotal));
                tvFree.setText(StringUtils.formatBytes(freeSpace));
            });
        });
    }

    private static long size(File file) {
        return FileUtils.getAllocatedSize(file);
    }

    private static ArrayList<File> containerCacheDirs(File rootfsDir) {
        ArrayList<File> result = new ArrayList<>();
        File homeDir = new File(rootfsDir, "home");
        File[] users = homeDir.listFiles();
        if (users != null) {
            for (File user : users) {
                if (user.isDirectory() && user.getName().startsWith(RootFS.USER)) {
                    result.add(new File(user, ".cache"));
                }
            }
        }
        return result;
    }

    private void addContainerRow(Activity activity, LinearLayout parent, Container container, long size) {
        activity.runOnUiThread(() -> {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.setClickable(true);
            row.setOnClickListener((v) -> (new ContainerStorageDetailDialog(activity, container)).show());

            TextView tvName = new TextView(activity);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(nameParams);
            tvName.setText(container.getName());

            TextView tvSize = new TextView(activity);
            tvSize.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tvSize.setText(StringUtils.formatBytes(size));

            row.addView(tvName);
            row.addView(tvSize);
            parent.addView(row);
        });
    }
}
