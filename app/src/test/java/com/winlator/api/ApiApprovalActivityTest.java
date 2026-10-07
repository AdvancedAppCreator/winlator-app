package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.winlator.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class ApiApprovalActivityTest {
    @Test
    public void launchesWithAppCompatTheme() {
        ApiApprovalActivity activity = Robolectric
                .buildActivity(ApiApprovalActivity.class)
                .setup()
                .get();

        assertEquals(activity.getString(R.string.api_integrations), activity.getTitle());
    }

    @Test
    public void dialogContextUsesReadableTextOnLightSurface() {
        ApiApprovalActivity activity = Robolectric
                .buildActivity(ApiApprovalActivity.class)
                .setup()
                .get();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        assertEquals(
                Color.rgb(33, 33, 33),
                new TextView(builder.getContext()).getCurrentTextColor()
        );
        assertEquals(
                Color.rgb(33, 33, 33),
                new CheckBox(builder.getContext()).getCurrentTextColor()
        );

        AlertDialog dialog = builder
                .setTitle(R.string.approve_installed_app)
                .setMessage(R.string.api_integrations_description)
                .show();
        TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        assertNotNull(title);
        assertEquals(Color.rgb(33, 33, 33), title.getCurrentTextColor());
        dialog.dismiss();
    }

    @Test
    public void installedAppEntryFiltersByLabelAndPackageName() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = "com.example.manager";
        ApiApprovalActivity.InstalledAppEntry entry =
                new ApiApprovalActivity.InstalledAppEntry(
                        applicationInfo,
                        "Adult Game Manager"
                );

        assertTrue(entry.matches("adult"));
        assertTrue(entry.matches("EXAMPLE.MANAGER"));
        assertTrue(entry.matches(""));
        assertFalse(entry.matches("unrelated"));
    }

    @Test
    public void installedAppListIncludesVisibleAppsAndExcludesWinlator() {
        Application application = ApplicationProvider.getApplicationContext();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "com.example.manager";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageInfo.packageName;
        packageInfo.applicationInfo.nonLocalizedLabel = "Adult Game Manager";
        packageInfo.applicationInfo.enabled = true;
        shadowOf(application.getPackageManager()).installPackage(packageInfo);

        ArrayList<ApiApprovalActivity.InstalledAppEntry> entries =
                ApiApprovalActivity.loadInstalledApps(
                        application.getPackageManager(),
                        application.getPackageName()
                );

        assertTrue(containsPackage(entries, packageInfo.packageName));
        assertFalse(containsPackage(entries, application.getPackageName()));
    }

    private static boolean containsPackage(
            ArrayList<ApiApprovalActivity.InstalledAppEntry> entries,
            String packageName
    ) {
        for (ApiApprovalActivity.InstalledAppEntry entry : entries) {
            if (packageName.equals(entry.applicationInfo.packageName)) return true;
        }
        return false;
    }
}
