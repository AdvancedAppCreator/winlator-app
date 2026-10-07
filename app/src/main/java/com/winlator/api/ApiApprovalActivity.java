package com.winlator.api;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.R;
import com.winlator.core.AppUtils;

import org.json.JSONException;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ApiApprovalActivity extends AppCompatActivity {
    private static final List<String> SCOPES = Arrays.asList(
            ApiScope.READ,
            ApiScope.MANAGE_GAMES,
            ApiScope.SETTINGS,
            ApiScope.LAUNCH,
            ApiScope.MODS,
            ApiScope.DEPENDENCIES,
            ApiScope.RECOVERY,
            ApiScope.GLOBAL_UI
    );

    private ApiApprovalStore store;
    private LinearLayout approvalsLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.api_integrations);
        store = new ApiApprovalStore(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        content.setPadding(padding, padding, padding, padding);

        TextView description = new TextView(this);
        description.setText(R.string.api_integrations_description);
        content.addView(description);

        Button approve = new Button(this);
        approve.setText(R.string.approve_installed_app);
        approve.setOnClickListener(view -> showInstalledAppPicker());
        content.addView(approve);

        approvalsLayout = new LinearLayout(this);
        approvalsLayout.setOrientation(LinearLayout.VERTICAL);
        content.addView(approvalsLayout);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);
        refresh();
    }

    private void refresh() {
        approvalsLayout.removeAllViews();
        try {
            for (ApiApprovalEntry entry : store.list()) {
                approvalsLayout.addView(createApprovalView(entry));
            }
        }
        catch (JSONException | IOException error) {
            AppUtils.showToast(this, error.getMessage());
        }
    }

    private View createApprovalView(ApiApprovalEntry entry) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(16), 0, dp(8));

        TextView title = new TextView(this);
        title.setText(resolveLabel(entry.packageName) + "\n" + entry.packageName);
        title.setTextSize(17);
        layout.addView(title);

        TextView details = new TextView(this);
        details.setText(
                getString(
                        R.string.api_integration_details,
                        entry.approvalType,
                        String.join(", ", entry.scopes),
                        entry.revoked
                                ? getString(R.string.revoked)
                                : getString(R.string.active)
                )
        );
        layout.addView(details);

        Button toggle = new Button(this);
        toggle.setText(entry.revoked ? R.string.restore_approval : R.string.revoke_approval);
        toggle.setOnClickListener(view -> confirmRevocation(entry));
        layout.addView(toggle);
        return layout;
    }

    private void showInstalledAppPicker() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context dialogContext = builder.getContext();
        ArrayList<InstalledAppEntry> entries = loadInstalledApps(
                getPackageManager(),
                getPackageName()
        );
        InstalledAppAdapter adapter = new InstalledAppAdapter(dialogContext, entries);

        LinearLayout picker = new LinearLayout(dialogContext);
        picker.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        picker.setPadding(padding, 0, padding, 0);

        EditText filter = new EditText(dialogContext);
        filter.setHint(R.string.filter_installed_apps);
        filter.setSingleLine(true);
        filter.setInputType(InputType.TYPE_CLASS_TEXT);
        picker.addView(filter);

        int listHeight = Math.min(dp(480), getResources().getDisplayMetrics().heightPixels / 2);
        ListView list = new ListView(dialogContext);
        list.setAdapter(adapter);
        picker.addView(
                list,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        listHeight
                )
        );

        TextView empty = new TextView(dialogContext);
        empty.setText(R.string.no_installed_apps_found);
        empty.setGravity(Gravity.CENTER);
        picker.addView(
                empty,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        listHeight
                )
        );
        list.setEmptyView(empty);

        AlertDialog dialog = builder
                .setTitle(R.string.approve_installed_app)
                .setView(picker)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            InstalledAppEntry entry = adapter.getItem(position);
            dialog.dismiss();
            chooseScopes(entry.applicationInfo.packageName);
        });
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                adapter.filter(text == null ? "" : text.toString());
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        dialog.show();
    }

    static ArrayList<InstalledAppEntry> loadInstalledApps(
            PackageManager packageManager,
            String ownPackageName
    ) {
        ArrayList<InstalledAppEntry> entries = new ArrayList<>();
        for (ApplicationInfo applicationInfo :
                packageManager.getInstalledApplications(0)) {
            String packageName = applicationInfo.packageName;
            if (!applicationInfo.enabled ||
                    packageName == null ||
                    packageName.equals(ownPackageName)) {
                continue;
            }
            CharSequence labelText = packageManager.getApplicationLabel(applicationInfo);
            String label = labelText == null || labelText.length() == 0
                    ? packageName
                    : labelText.toString();
            entries.add(new InstalledAppEntry(applicationInfo, label));
        }
        entries.sort((first, second) -> {
            int labelOrder = first.label.compareToIgnoreCase(second.label);
            if (labelOrder != 0) return labelOrder;
            return first.applicationInfo.packageName.compareToIgnoreCase(
                    second.applicationInfo.packageName
            );
        });
        return entries;
    }

    static final class InstalledAppEntry {
        final ApplicationInfo applicationInfo;
        final String label;

        InstalledAppEntry(ApplicationInfo applicationInfo, String label) {
            this.applicationInfo = applicationInfo;
            this.label = label;
        }

        boolean matches(String query) {
            String normalized = query == null
                    ? ""
                    : query.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() ||
                    label.toLowerCase(Locale.ROOT).contains(normalized) ||
                    applicationInfo.packageName.toLowerCase(Locale.ROOT)
                            .contains(normalized);
        }
    }

    private final class InstalledAppAdapter extends BaseAdapter {
        private final Context context;
        private final ArrayList<InstalledAppEntry> allEntries;
        private final ArrayList<InstalledAppEntry> visibleEntries = new ArrayList<>();

        InstalledAppAdapter(Context context, ArrayList<InstalledAppEntry> entries) {
            this.context = context;
            allEntries = entries;
            visibleEntries.addAll(entries);
        }

        void filter(String query) {
            visibleEntries.clear();
            for (InstalledAppEntry entry : allEntries) {
                if (entry.matches(query)) visibleEntries.add(entry);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return visibleEntries.size();
        }

        @Override
        public InstalledAppEntry getItem(int position) {
            return visibleEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int rowPadding = dp(8);
                row.setPadding(0, rowPadding, 0, rowPadding);

                ImageView icon = new ImageView(context);
                int iconSize = dp(40);
                row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

                LinearLayout text = new LinearLayout(context);
                text.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1
                );
                textParams.setMarginStart(dp(12));
                row.addView(text, textParams);

                TextView label = new TextView(context);
                label.setTextSize(16);
                text.addView(label);

                TextView packageName = new TextView(context);
                packageName.setTextSize(12);
                text.addView(packageName);

                holder = new ViewHolder(icon, label, packageName);
                row.setTag(holder);
                convertView = row;
            }
            else {
                holder = (ViewHolder)convertView.getTag();
            }

            InstalledAppEntry entry = getItem(position);
            holder.icon.setImageDrawable(entry.applicationInfo.loadIcon(getPackageManager()));
            holder.label.setText(entry.label);
            holder.packageName.setText(entry.applicationInfo.packageName);
            return convertView;
        }
    }

    private static final class ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView packageName;

        ViewHolder(ImageView icon, TextView label, TextView packageName) {
            this.icon = icon;
            this.label = label;
            this.packageName = packageName;
        }
    }

    private void chooseScopes(String packageName) {
        try {
            ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(
                    packageName,
                    0
            );
            Set<String> certificates =
                    GameApiAuthorization.installedCertificates(this, packageName);
            if (certificates.isEmpty()) {
                AppUtils.showToast(this, R.string.api_unsigned_app);
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            Context dialogContext = builder.getContext();
            LinearLayout scopeLayout = new LinearLayout(dialogContext);
            scopeLayout.setOrientation(LinearLayout.VERTICAL);
            ArrayList<CheckBox> checkBoxes = new ArrayList<>();
            for (String scope : SCOPES) {
                CheckBox checkBox = new CheckBox(dialogContext);
                checkBox.setText(scope);
                checkBox.setChecked(true);
                scopeLayout.addView(checkBox);
                checkBoxes.add(checkBox);
            }
            ScrollView scrollView = new ScrollView(dialogContext);
            scrollView.addView(scopeLayout);
            String label = getPackageManager().getApplicationLabel(applicationInfo).toString();
            builder
                    .setTitle(getString(R.string.approve_app_title, label))
                    .setMessage(
                            getString(
                                    R.string.api_approval_confirmation,
                                    packageName,
                                    certificates.iterator().next()
                            )
                    )
                    .setView(scrollView)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                            R.string.approve,
                            (dialog, which) -> approve(
                                    packageName,
                                    certificates,
                                    checkBoxes
                            )
                    )
                    .show();
        }
        catch (PackageManager.NameNotFoundException |
               NoSuchAlgorithmException error) {
            AppUtils.showToast(this, R.string.api_app_not_installed);
        }
    }

    private void approve(
            String packageName,
            Set<String> certificates,
            List<CheckBox> checkBoxes
    ) {
        HashSet<String> selectedScopes = new HashSet<>();
        for (int index = 0; index < SCOPES.size(); index++) {
            if (checkBoxes.get(index).isChecked()) {
                selectedScopes.add(SCOPES.get(index));
            }
        }
        try {
            store.approve(packageName, certificates, selectedScopes);
            refresh();
        }
        catch (JSONException | IOException | IllegalArgumentException error) {
            AppUtils.showToast(this, error.getMessage());
        }
    }

    private void confirmRevocation(ApiApprovalEntry entry) {
        boolean revoke = !entry.revoked;
        new AlertDialog.Builder(this)
                .setTitle(revoke ? R.string.revoke_approval : R.string.restore_approval)
                .setMessage(entry.packageName)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        revoke ? R.string.revoke_approval : R.string.restore_approval,
                        (dialog, which) -> setRevoked(entry.packageName, revoke)
                )
                .show();
    }

    private void setRevoked(String packageName, boolean revoked) {
        try {
            store.setRevoked(packageName, revoked);
            refresh();
        }
        catch (JSONException | IOException | IllegalArgumentException error) {
            AppUtils.showToast(this, error.getMessage());
        }
    }

    private String resolveLabel(String packageName) {
        try {
            ApplicationInfo information = getPackageManager().getApplicationInfo(
                    packageName,
                    0
            );
            return getPackageManager().getApplicationLabel(information).toString();
        }
        catch (PackageManager.NameNotFoundException error) {
            return getString(R.string.not_installed);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
