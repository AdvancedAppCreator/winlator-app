package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.contentdialog.AboutDialog;
import com.winlator.contentdialog.StorageManagerDialog;
import com.winlator.api.dependency.RuntimeDependencyActivity;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.GPUHelper;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.DownloadProgressDialog;
import com.winlator.core.RuntimeAssetManifest;
import com.winlator.core.RuntimeAssetProvisioner;
import com.winlator.core.RuntimeAssetProvisioningCoordinator;
import com.winlator.core.StartupLog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.xenvironment.RootFSInstaller;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final boolean DEBUG_MODE = false; // FIXME change to false
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    private DrawerLayout drawerLayout;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private Callback<Uri> openFileCallback;
    private SharedPreferences preferences;
    private Fragment currentFragment;
    private final RuntimeAssetProvisioningCoordinator runtimeProvisioning =
            RuntimeAssetProvisioningCoordinator.getInstance();
    private final RuntimeAssetProvisioningCoordinator.Observer runtimeProvisioningObserver =
            this::onRuntimeProvisioningStateChanged;
    private ContentDialog runtimeProvisioningDialog;
    private DownloadProgressDialog runtimeProvisioningProgressDialog;
    private boolean runtimeBootstrapContinued;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        StartupLog.initialize(this);
        StartupLog.startSession("app-launch");
        GPUHelper.initialize(this);
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);

        setSupportActionBar(findViewById(R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.menu_item_input_controls));
            navigationView.setCheckedItem(R.id.menu_item_input_controls);
        }
        else {
            boolean showShortcutsFirst = preferences.getBoolean("show_shortcuts_first", false);
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : (showShortcutsFirst ? R.id.menu_item_shortcuts : R.id.menu_item_containers);

            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);
            if (!requestAppPermissions()) initializeSystemFiles();

            int containerId = intent.getIntExtra("container_id", 0);
            String startPath = intent.getStringExtra("start_path");
            if (containerId > 0 && startPath != null) {
                showFragment(new ContainerFileManagerFragment(containerId, startPath));
            }
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onStart() {
        super.onStart();
        runtimeProvisioning.attach(runtimeProvisioningObserver);
    }

    @Override
    protected void onStop() {
        runtimeProvisioning.detach(runtimeProvisioningObserver);
        closeRuntimeProvisioningUi();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeSystemFiles();
            }
            else finish();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (openFileCallback != null) {
                openFileCallback.call(data.getData());
                openFileCallback = null;
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if ((newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) && currentFragment instanceof BaseFileManagerFragment) {
            ((BaseFileManagerFragment)currentFragment).onOrientationChanged();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentFragment != null && currentFragment.isVisible()) {
            if (currentFragment instanceof BaseFileManagerFragment) {
                BaseFileManagerFragment fileManagerFragment = (BaseFileManagerFragment)currentFragment;
                if (fileManagerFragment.onBackPressed()) return;
            }
            else if (currentFragment instanceof ContainersFragment) {
                finish();
            }
        }

        showFragment(new ContainersFragment());
    }

    public void setOpenFileCallback(Callback<Uri> openFileCallback) {
        this.openFileCallback = openFileCallback;
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;

        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }

    private void initializeSystemFiles() {
        if (!RuntimeAssetProvisioner.isReady(this)) {
            runtimeProvisioning.request(this);
            return;
        }
        if (runtimeBootstrapContinued) return;
        runtimeBootstrapContinued = true;
        RootFSInstaller.installIfNeeded(this, success -> {
            if (success) RuntimeDependencyActivity.openBootstrapIfNeeded(this);
        });
    }

    private void requestRuntimeProvisioningConsent() {
        if (runtimeProvisioningDialog != null && runtimeProvisioningDialog.isShowing()) return;
        runtimeProvisioningDialog = new ContentDialog(this);
        runtimeProvisioningDialog.setTitle(R.string.runtime_provisioning_title);
        runtimeProvisioningDialog.setMessage(getString(
                R.string.runtime_provisioning_consent,
                RuntimeAssetManifest.UPSTREAM_REPOSITORY,
                RuntimeAssetManifest.UPSTREAM_VERSION
        ));
        runtimeProvisioningDialog.setOnConfirmCallback(
                () -> runtimeProvisioning.start(this)
        );
        runtimeProvisioningDialog.setOnCancelCallback(this::finish);
        runtimeProvisioningDialog.setCancelable(false);
        runtimeProvisioningDialog.show();
    }

    private void showRuntimeProvisioningProgress(int progress) {
        dismissRuntimeProvisioningDialog();
        if (runtimeProvisioningProgressDialog == null) {
            runtimeProvisioningProgressDialog = new DownloadProgressDialog(this);
        }
        if (!runtimeProvisioningProgressDialog.isShowing()) {
            runtimeProvisioningProgressDialog.show(
                    R.string.runtime_provisioning_downloading,
                    runtimeProvisioning::cancel
            );
        }
        runtimeProvisioningProgressDialog.setProgress(progress);
    }

    private void showRuntimeProvisioningError(RuntimeAssetProvisioner.Result result) {
        closeRuntimeProvisioningProgress();
        if (runtimeProvisioningDialog != null && runtimeProvisioningDialog.isShowing()) return;
        runtimeProvisioningDialog = new ContentDialog(this);
        runtimeProvisioningDialog.setTitle(R.string.runtime_provisioning_failed_title);
        runtimeProvisioningDialog.setMessage(getString(
                R.string.runtime_provisioning_failed,
                getRuntimeProvisioningError(result)
        ));
        runtimeProvisioningDialog.setOnConfirmCallback(
                () -> runtimeProvisioning.start(this)
        );
        runtimeProvisioningDialog.setOnCancelCallback(this::finish);
        runtimeProvisioningDialog.setCancelable(false);
        runtimeProvisioningDialog.show();
    }

    private void onRuntimeProvisioningStateChanged(
            RuntimeAssetProvisioningCoordinator.State state
    ) {
        if (isFinishing() || isDestroyed()) return;
        switch (state.status) {
            case CONSENT_REQUIRED:
            case CANCELLED:
                closeRuntimeProvisioningProgress();
                requestRuntimeProvisioningConsent();
                break;
            case RUNNING:
                showRuntimeProvisioningProgress(state.progress);
                break;
            case FAILED:
                showRuntimeProvisioningError(state.result);
                break;
            case SUCCEEDED:
                closeRuntimeProvisioningUi();
                initializeSystemFiles();
                break;
            default:
                break;
        }
    }

    private void closeRuntimeProvisioningUi() {
        dismissRuntimeProvisioningDialog();
        closeRuntimeProvisioningProgress();
    }

    private void dismissRuntimeProvisioningDialog() {
        if (runtimeProvisioningDialog != null) {
            runtimeProvisioningDialog.dismiss();
            runtimeProvisioningDialog = null;
        }
    }

    private void closeRuntimeProvisioningProgress() {
        if (runtimeProvisioningProgressDialog != null) {
            runtimeProvisioningProgressDialog.close();
            runtimeProvisioningProgressDialog = null;
        }
    }

    private String getRuntimeProvisioningError(RuntimeAssetProvisioner.Result result) {
        switch (result) {
            case HTTP_ERROR:
                return getString(R.string.runtime_provisioning_error_http);
            case STORAGE_ERROR:
                return getString(R.string.runtime_provisioning_error_storage);
            case INVALID_DOWNLOAD:
                return getString(R.string.runtime_provisioning_error_integrity);
            case INVALID_APK:
                return getString(R.string.runtime_provisioning_error_apk);
            default:
                return getString(R.string.runtime_provisioning_error_network);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.menu_item_add ||
            itemId == R.id.menu_item_home ||
            itemId == R.id.menu_item_view_style ||
            itemId == R.id.menu_item_new_folder) {
            return super.onOptionsItemSelected(menuItem);
        }
        else {
            if (editInputControls) {
                setResult(RESULT_OK);
                finish();
            }
            else {
                if (currentFragment instanceof BaseFileManagerFragment) {
                    BaseFileManagerFragment fileManagerFragment = (BaseFileManagerFragment)currentFragment;
                    if (fileManagerFragment.onOptionsMenuClicked()) return true;
                }
                drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.menu_item_shortcuts:
                preferences.edit().putBoolean("show_shortcuts_first", true).apply();
                showFragment(new ShortcutsFragment());
                break;
            case R.id.menu_item_containers:
                preferences.edit().putBoolean("show_shortcuts_first", false).apply();
                showFragment(new ContainersFragment());
                break;
            case R.id.menu_item_input_controls:
                showFragment(new InputControlsFragment(selectedProfileId));
                break;
            case R.id.menu_item_settings:
                showFragment(new SettingsFragment());
                break;
            case R.id.menu_item_storage:
                (new StorageManagerDialog(this)).show();
                break;
            case R.id.menu_item_about:
                (new AboutDialog(this)).show();
                break;
        }
        return true;
    }

    public void showFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
            .replace(R.id.FLFragmentContainer, fragment)
            .commit();

        drawerLayout.closeDrawer(GravityCompat.START);
        currentFragment = fragment;
    }
}