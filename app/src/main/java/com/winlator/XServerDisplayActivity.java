package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.api.GameApiContract;
import com.winlator.api.GameManagerActivity;
import com.winlator.api.GameSessionEventReporter;
import com.winlator.api.ManagedGameSettingsAccess;
import com.winlator.api.ManagedStallRecovery;
import com.winlator.api.ManagedSessionDiagnostics;
import com.winlator.api.dependency.RuntimeDependencyCatalog;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.DesktopMode;
import com.winlator.container.DriverPolicy;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.SessionDiagnosticsDialog;
import com.winlator.contentdialog.GameTextDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.AppExitDiagnostics;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.GPUHelper;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LiveMakerCompat;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.RuntimeAssetManifest;
import com.winlator.core.RuntimeAssetProvisioner;
import com.winlator.core.ProcessHelper;
import com.winlator.core.RuntimeLocaleManager;
import com.winlator.core.StringUtils;
import com.winlator.core.StartupLog;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.AutoClicker;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.widget.FrameRating;
import com.winlator.widget.CrosshairView;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.SeekBar;
import com.winlator.widget.SessionStallOverlayView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.text.GameTextConfig;
import com.winlator.text.GameTextController;
import com.winlator.text.GameTextOverlayView;
import com.winlator.text.StrongOcrSelectionView;
import com.winlator.text.StrongOcrInspectionView;
import com.winlator.text.TextRegionSelectorView;
import com.winlator.winhandler.GamepadHandler;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private static final String PREF_QUICK_SESSION_CONTROLS_EXPANDED = "quick_session_controls_expanded";
    private static final String PREF_GAME_TEXT_LAST_MODE = "game_text_last_mode";
    private static volatile boolean sessionActive;
    private final AtomicBoolean managedSessionEventSent = new AtomicBoolean();
    private final AtomicBoolean exitStarted = new AtomicBoolean();
    private final ExecutorService managedSettingsExecutor =
            Executors.newSingleThreadExecutor();
    private volatile boolean suppressManagedSessionAbort;
    private boolean ownsSessionClaim;
    private String sessionToken;
    private ImageButton quickFullscreenButton;
    private ImageButton quickInputModeButton;
    private ImageButton quickTextButton;
    private ImageButton quickTranslateButton;
    private ImageButton quickDiagnosticsButton;
    private ImageButton quickStrongOcrButton;
    private ImageButton quickAutoClickerButton;
    private ImageButton quickExitButton;
    private View quickSessionControls;
    private LinearLayout quickSessionButtons;
    private ImageButton quickControlsToggleButton;
    private boolean fullscreenEnabled;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private AutoClicker autoClicker;
    private CrosshairView autoClickerCrosshair;
    private View autoClickerEditor;
    private boolean autoClickerEnabled;
    private DebugDialog debugDialog;
    private int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;
    private GameTextController gameTextController;
    private GameTextOverlayView gameTextOverlayView;
    private TextRegionSelectorView textRegionSelectorView;
    private StrongOcrSelectionView strongOcrSelectionView;
    private StrongOcrInspectionView strongOcrInspectionView;
    private ManagedSessionDiagnostics managedDiagnostics;
    private SessionStallOverlayView stallOverlay;
    private volatile String currentStallCause;
    private String effectiveBox64Preset;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        StartupLog.initialize(this);
        StartupLog.startSession("container-launch");
        GPUHelper.initialize(this);
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        sessionToken = getIntent().getStringExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN);
        if (!GameManagerActivity.claimSessionStart(sessionToken)) {
            finish();
            return;
        }
        ownsSessionClaim = true;
        sessionActive = true;
        GameSessionEventReporter.sendInstallerProgress(this, getIntent(), "session_starting");
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        boolean dependencySession = GameSessionEventReporter.isDependencySession(getIntent());
        if (dependencySession) {
            String dependencyId = getIntent().getStringExtra(
                    GameApiContract.INTERNAL_EXTRA_DEPENDENCY_ID
            );
            RuntimeDependencyCatalog.BootstrapPackage entry =
                    RuntimeDependencyCatalog.getBootstrapPackage(dependencyId);
            TextView status = findViewById(R.id.TVDependencyInstallStatus);
            status.setText(getString(
                    R.string.installing_windows_prerequisite,
                    entry != null ? entry.displayName : dependencyId
            ));
            status.setVisibility(View.VISIBLE);
            findViewById(R.id.LLQuickSessionControls).setVisibility(View.GONE);
        }

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        managedDiagnostics = ManagedSessionDiagnostics.start(
                this,
                getIntent(),
                managedSessionEventSent
        );
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            if (container == null) {
                GameSessionEventReporter.failToStart(
                        this,
                        getIntent(),
                        GameApiContract.ERROR_CONTAINER_NOT_FOUND,
                        "The requested container does not exist."
                );
                finish();
                return;
            }
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        suppressManagedSessionAbort = true;
                        if (managedDiagnostics != null) managedDiagnostics.cancel();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            String dxwrapper = container.getDXWrapper();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = new ScreenInfo(container.getScreenSize());

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = new ScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (hasDirectExecutableIntent()) {
                    String execPath = intent.hasExtra("exec_dos_path")
                            ? intent.getStringExtra("exec_dos_path")
                            : intent.getStringExtra("exec_path");
                    win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(execPath));
                }
            }

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
            applyDriverPolicy();
            effectiveBox64Preset = shortcut != null
                    ? shortcut.getExtra("box64Preset", container.getBox64Preset())
                    : container.getBox64Preset();
            logEffectiveDrivers();
            beginExitDiagnosticsSession();
        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        final boolean[] flags = {
                false,
                shortcut != null || hasDirectExecutableIntent(),
                false
        };
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (window.id == frameRatingWindowId) frameRating.update();
                if (!flags[2] && window.isRenderable()) {
                    AppExitDiagnostics.updatePhase(
                            "first_window_content:"+window.id+":"+window.getClassName()
                    );
                    flags[2] = true;
                }
            }

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    if (managedDiagnostics != null) managedDiagnostics.markRuntimeReached();
                    AppExitDiagnostics.updatePhase(
                            "first_window_mapped:"+window.id+":"+window.getClassName()
                    );
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            AppExitDiagnostics.updatePhase("runtime_files_preparing");
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
                WineUtils.setupAudioDriverFiles(this, audioDriver);
            }
            AppExitDiagnostics.updatePhase("environment_preparing");
            setupXEnvironment();
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            if (capturePointerOnExternalMouse) touchpadView.requestPointerCapture();

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        AppExitDiagnostics.updatePhase("activity_resumed");
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
    }

    @Override
    public void onPause() {
        AppExitDiagnostics.updatePhase("activity_paused");
        super.onPause();
        if (autoClicker != null) {
            autoClickerEnabled = false;
            autoClicker.stop();
            if (quickAutoClickerButton != null) quickAutoClickerButton.setSelected(false);
            closeAutoClickerEditor();
        }
        if (environment != null && !isInPictureInPictureMode()) {
            environment.onPause();
            xServerView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        boolean preservingSession = isChangingConfigurations() || suppressManagedSessionAbort;
        if (!preservingSession && !exitStarted.get()) {
            AppExitDiagnostics.completeSession("activity_destroyed_without_explicit_exit");
        }
        if (preservingSession) {
            managedSessionEventSent.set(true);
            if (managedDiagnostics != null) managedDiagnostics.cancel();
        }
        else if (!suppressManagedSessionAbort &&
                GameSessionEventReporter.isManagedSession(getIntent()) &&
                managedSessionEventSent.compareAndSet(false, true)) {
            JSONObject diagnosticReport = managedDiagnostics != null
                    ? managedDiagnostics.finishUnexpected()
                    : null;
            GameSessionEventReporter.abort(this, getIntent(), diagnosticReport);
        }
        winHandler.stop();
        if (gameTextController != null) gameTextController.close();
        if (managedDiagnostics != null) managedDiagnostics.close();
        managedSettingsExecutor.shutdownNow();
        XEnvironment stoppingEnvironment = environment;
        environment = null;
        if (stoppingEnvironment != null && !preservingSession) {
            Thread cleanupThread = new Thread(() -> {
                try {
                    stoppingEnvironment.stopEnvironmentComponents(
                            ProcessHelper.TerminationOrigin.WINLATOR_TEARDOWN
                    );
                }
                finally {
                    releaseSessionClaim();
                }
            }, "winlator-destroy-stop");
            cleanupThread.start();
        }
        else {
            if (stoppingEnvironment != null) {
                stoppingEnvironment.stopEnvironmentComponents(
                        ProcessHelper.TerminationOrigin.WINLATOR_TEARDOWN
                );
            }
            if (!preservingSession) releaseSessionClaim();
        }
        super.onDestroy();
    }

    private void releaseSessionClaim() {
        if (!ownsSessionClaim) return;
        sessionActive = false;
        GameManagerActivity.notifySessionEnded(sessionToken);
        ownsSessionClaim = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String replacementToken =
                intent.getStringExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN);
        if (sessionActive && !Objects.equals(sessionToken, replacementToken)) {
            exit(ProcessHelper.TerminationOrigin.SESSION_REPLACED);
            return;
        }
        setIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (strongOcrSelectionView != null) {
            cancelStrongOcrSelection();
            return;
        }
        if (strongOcrInspectionView != null) {
            closeStrongOcrInspection();
            return;
        }
        if (textRegionSelectorView != null) {
            FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
            rootView.removeView(textRegionSelectorView);
            textRegionSelectorView = null;
            return;
        }
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                toggleFullscreen(renderer);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_active_windows:
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_auto_clicker:
                openAutoClickerSettings();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_exit:
                confirmExit();
                break;
        }
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    private void toggleAutoClicker() {
        if (autoClicker == null) return;
        closeAutoClickerEditor();
        setAutoClickerEnabled(!autoClickerEnabled);
    }

    private void setAutoClickerEnabled(boolean enabled) {
        autoClickerEnabled = enabled;
        if (quickAutoClickerButton != null) quickAutoClickerButton.setSelected(enabled);
        if (enabled) {
            autoClickerCrosshair.setEditable(false);
            autoClickerCrosshair.setVisibility(View.VISIBLE);
            int interval = preferences.getInt("auto_clicker_interval", 1000);
            float[] guest = crosshairGuestPoint();
            autoClicker.configure(Math.round(guest[0]), Math.round(guest[1]), interval);
            autoClicker.start();
        }
        else {
            autoClicker.stop();
            if (autoClickerEditor == null) autoClickerCrosshair.setVisibility(View.GONE);
        }
        AppUtils.showToast(this, enabled ? R.string.auto_clicker_enabled : R.string.auto_clicker_disabled);
    }

    private float[] crosshairGuestPoint() {
        float screenX = autoClickerCrosshair.getFractionX() * AppUtils.getScreenWidth();
        float screenY = autoClickerCrosshair.getFractionY() * AppUtils.getScreenHeight();
        return touchpadView.screenToGuest(screenX, screenY);
    }

    private void openAutoClickerSettings() {
        if (autoClicker == null || autoClickerEditor != null) return;
        setAutoClickerEnabled(false);
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        autoClickerCrosshair.setEditable(true);
        autoClickerCrosshair.setVisibility(View.VISIBLE);
        View panel = getLayoutInflater().inflate(R.layout.auto_clicker_editor, rootView, false);
        final SeekBar intervalSeekBar = panel.findViewById(R.id.SBAutoClickerInterval);
        intervalSeekBar.setValue(preferences.getInt("auto_clicker_interval", 1000));
        panel.findViewById(R.id.BTAutoClickerDone).setOnClickListener((view) -> {
            preferences.edit()
                    .putInt("auto_clicker_interval", Math.round(intervalSeekBar.getValue()))
                    .putFloat("auto_clicker_x", autoClickerCrosshair.getFractionX() * 100f)
                    .putFloat("auto_clicker_y", autoClickerCrosshair.getFractionY() * 100f)
                    .apply();
            closeAutoClickerEditor();
        });
        autoClickerEditor = panel;
        rootView.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
    }

    private void closeAutoClickerEditor() {
        if (autoClickerEditor != null) {
            ((FrameLayout)findViewById(R.id.FLXServerDisplay)).removeView(autoClickerEditor);
            autoClickerEditor = null;
        }
        if (autoClickerCrosshair != null) {
            autoClickerCrosshair.setEditable(false);
            if (!autoClickerEnabled) autoClickerCrosshair.setVisibility(View.GONE);
        }
    }

    private void exit(boolean userInitiated) {
        exit(
                userInitiated
                        ? ProcessHelper.TerminationOrigin.USER_EXIT
                        : ProcessHelper.TerminationOrigin.WINLATOR_TEARDOWN
        );
    }

    private void exit(ProcessHelper.TerminationOrigin origin) {
        if (!exitStarted.compareAndSet(false, true)) return;
        AppExitDiagnostics.completeSession("explicit_exit:"+origin.name());
        boolean managedSession = GameSessionEventReporter.isManagedSession(getIntent());
        if (origin == ProcessHelper.TerminationOrigin.USER_EXIT &&
                managedSession &&
                managedSessionEventSent.compareAndSet(false, true)) {
            JSONObject diagnosticReport = managedDiagnostics != null
                    ? managedDiagnostics.finishUserExit()
                    : null;
            GameSessionEventReporter.abort(this, getIntent(), diagnosticReport);
        }
        else if (origin == ProcessHelper.TerminationOrigin.SESSION_REPLACED &&
                managedSession &&
                managedSessionEventSent.compareAndSet(false, true)) {
            JSONObject diagnosticReport = managedDiagnostics != null
                    ? managedDiagnostics.finishSessionReplaced()
                    : null;
            GameSessionEventReporter.abort(this, getIntent(), diagnosticReport);
        }
        winHandler.stop();
        XEnvironment stoppingEnvironment = environment;
        environment = null;
        Thread cleanupThread = new Thread(() -> {
            if (stoppingEnvironment != null) {
                stoppingEnvironment.stopEnvironmentComponents(origin);
            }
            runOnUiThread(() -> finishExit(managedSession));
        }, "winlator-session-stop");
        cleanupThread.start();
    }

    private void finishExit(boolean managedSession) {
        if (GameSessionEventReporter.isDependencySession(getIntent())) {
            releaseSessionClaim();
            finish();
            return;
        }
        if (managedSession) {
            // The singleTask display activity can sit above Winlator's main activity.
            // Remove that task so the AGM task that launched the session is revealed.
            finishAndRemoveTask();
            return;
        }

        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.containerId = container.id;
            options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
            AppUtils.restartApplication(this, options);
        }
        else AppUtils.restartApplication(this);
    }

    private void confirmExit() {
        ContentDialog.confirm(
                this,
                R.string.do_you_want_to_exit_the_game,
                () -> exit(true)
        );
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        if (extractDXWrapperFiles()) containerDataChanged = true;

        String bundledFontsVersion = "1";
        if (!bundledFontsVersion.equals(container.getExtra("bundledFonts")) || wineprefixWasUpdated) {
            WineUtils.setupBundledFonts(this, container);
            container.putExtra("bundledFonts", bundledFontsVersion);
            container.putExtra("cjkFonts", null);
            containerDataChanged = true;
        }

        String rpgMakerRtpVersion = "1";
        if (!rpgMakerRtpVersion.equals(container.getExtra("rpgMakerRtp")) || wineprefixWasUpdated) {
            WineUtils.setupRPGMakerRTP(this, container);
            container.putExtra("rpgMakerRtp", rpgMakerRtpVersion);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(this, container, true);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        AppExitDiagnostics.updatePhase("environment_configuring");
        StartupLog.log("Preparing container="+(container != null ? container.id : 0)+" root="+rootPath);
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");
        String runtimeLocale = getIntent().getStringExtra(
                GameApiContract.INTERNAL_EXTRA_RUNTIME_LOCALE
        );
        if (runtimeLocale == null && container != null
                && !Container.DEFAULT_WINE_LOCALE.equals(container.getWineLocale())) {
            String locale = container.getWineLocale();
            try {
                RuntimeLocaleManager.ensureAvailable(rootFS, locale);
                envVars.put("LANG", locale);
                envVars.put("LC_ALL", locale);
            }
            catch (IOException | IllegalArgumentException error) {
                StartupLog.log("Unable to apply the container Wine locale", error);
                AppUtils.showToast(this, getString(
                        R.string.runtime_locale_failed, runtimeLocaleFailureSummary(error)));
            }
        }
        if (runtimeLocale == null
                && (container == null
                        || Container.DEFAULT_WINE_LOCALE.equals(container.getWineLocale()))) {
            runtimeLocale = liveMakerRuntimeLocale();
        }
        if (runtimeLocale != null) {
            try {
                RuntimeLocaleManager.ensureAvailable(rootFS, runtimeLocale);
                envVars.put("LANG", runtimeLocale);
                envVars.put("LC_ALL", runtimeLocale);
            }
            catch (IOException | IllegalArgumentException error) {
                StartupLog.log("Unable to apply the managed game locale", error);
                String message = getString(
                        R.string.runtime_locale_failed,
                        runtimeLocaleFailureSummary(error)
                );
                JSONObject diagnosticReport = null;
                if (managedDiagnostics != null) {
                    diagnosticReport = managedDiagnostics.finishLaunchFailure(
                            "environment_start",
                            "runtime_locale_generation",
                            "Runtime locale failure: " + error.getMessage(),
                            runtimeLocaleFailureDetails(runtimeLocale, error)
                    );
                }
                if (GameSessionEventReporter.isManagedSession(getIntent()) &&
                        managedSessionEventSent.compareAndSet(false, true)) {
                    GameSessionEventReporter.failToStart(
                            this,
                            getIntent(),
                            GameApiContract.ERROR_LAUNCH_FAILED,
                            message,
                            diagnosticReport
                    );
                }
                runOnUiThread(() -> {
                    AppUtils.showToast(this, message);
                    exit(false);
                });
                return;
            }
        }

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put(
                "WINEDEBUG",
                enableWineDebug && !wineDebugChannels.isEmpty()
                        ? "+"+wineDebugChannels.replace(",", ",+")
                        : managedDiagnostics != null ? "err+all" : "-all"
        );

        GuestProgramLauncherComponent.cleanupStaleSession(rootFS);
        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        guestProgramLauncherComponent.setDiagnosticsEnabled(managedDiagnostics != null);

        if (container != null) {
            if (container.getHUDMode() == FrameRating.Mode.FULL.ordinal()) envVars.put("X11_WND_GPU_INFO", "1");

            String autoDesktop = shortcut != null || hasDirectExecutableIntent() ? "nogui" : "shell";
            String desktopMode = container.getDesktopMode();
            String desktopName = DesktopMode.AUTO.equals(desktopMode) ? autoDesktop : desktopMode;
            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+" "+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);
            ProcessHelper.emitDebugMessage("[SESSION] desktopMode=" + desktopName
                    + " (policy=" + desktopMode + ") launch="
                    + (hasDirectExecutableIntent() ? "direct_exe"
                            : shortcut != null ? "shortcut" : "container"));

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

            guestProgramLauncherComponent.setBox64Preset(effectiveBox64Preset);
        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA) || audioDriver.equals(AudioDrivers.SILENT)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            options.silent = audioDriver.equals(AudioDrivers.SILENT);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setDetailedTerminationCallback((result) -> {
            if (GameSessionEventReporter.isManagedSession(getIntent()) &&
                    managedSessionEventSent.compareAndSet(false, true)) {
                JSONObject diagnosticReport = managedDiagnostics != null
                        ? managedDiagnostics.finishProcess(result)
                        : null;
                GameSessionEventReporter.complete(
                        this,
                        getIntent(),
                        result.exitCode,
                        diagnosticReport
                );
            }
            exit(ProcessHelper.TerminationOrigin.NATURAL);
        });
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        if (managedDiagnostics != null) managedDiagnostics.markPhase("process_start");
        AppExitDiagnostics.updatePhase("components_starting");
        environment.startEnvironmentComponents();
        AppExitDiagnostics.updatePhase("components_started");
        if (managedDiagnostics != null) managedDiagnostics.markPhase("startup");
        GameSessionEventReporter.sendInstallerProgress(this, getIntent(), "installer_running");

        AppExitDiagnostics.updatePhase("winhandler_starting");
        winHandler.start();
        AppExitDiagnostics.updatePhase("runtime_active");
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
    }

    private String liveMakerRuntimeLocale() {
        File executable = null;
        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            executable = new File(intent.getStringExtra("exec_path"));
        }
        else if (intent.hasExtra("exec_dos_path")) {
            String path = WineUtils.dosToUnixPath(
                    intent.getStringExtra("exec_dos_path"),
                    container
            );
            if (path != null) executable = new File(path);
        }
        if (LiveMakerCompat.isExecutable(executable)) {
            ProcessHelper.emitDebugMessage(
                    "[LIVEMAKER] applying Japanese CP932-compatible runtime locale"
            );
            return LiveMakerCompat.RUNTIME_LOCALE;
        }
        return null;
    }

    private JSONObject runtimeLocaleFailureDetails(String locale, Throwable error) {
        try {
            JSONObject details = new JSONObject()
                    .put("locale", locale)
                    .put("stage", "runtime_locale")
                    .put("message", runtimeLocaleFailureSummary(error));
            if (!(error instanceof RuntimeLocaleManager.GenerationException)) {
                return details;
            }

            RuntimeLocaleManager.GenerationException generationError =
                    (RuntimeLocaleManager.GenerationException)error;
            JSONArray argv = new JSONArray();
            for (String argument : generationError.getCommand()) {
                argv.put(redactRootPath(argument));
            }
            JSONObject environment = new JSONObject();
            for (java.util.Map.Entry<String, String> entry :
                    generationError.getEnvironment().entrySet()) {
                environment.put(entry.getKey(), redactRootPath(entry.getValue()));
            }
            details.put("stage", generationError.getStage());
            details.put("argv", argv);
            details.put("environment", environment);
            details.put(
                    "expectedOutputDirectory",
                    redactRootPath(
                            generationError.getOutputDirectory() != null
                                    ? generationError.getOutputDirectory().getPath()
                                    : null
                    )
            );
            details.put("complete", generationError.isOutputComplete());
            if (generationError.getExitStatus() != null) {
                details.put("exitStatus", generationError.getExitStatus());
            }
            details.put("output", redactRootPath(generationError.getOutput()));
            details.put("outputTruncated", generationError.isOutputTruncated());
            return details;
        }
        catch (JSONException jsonError) {
            StartupLog.log("Unable to serialize runtime locale failure details", jsonError);
            return null;
        }
    }

    private String redactRootPath(String value) {
        if (value == null) return "";
        return value.replace(rootFS.getRootDir().getPath(), "<rootfs>");
    }

    private String runtimeLocaleFailureSummary(Throwable error) {
        if (!(error instanceof RuntimeLocaleManager.GenerationException)) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) {
                return "The configured locale could not be prepared.";
            }
            return message.length() <= 2048 ? message : message.substring(0, 2048);
        }
        RuntimeLocaleManager.GenerationException generationError =
                (RuntimeLocaleManager.GenerationException)error;
        return "locale generation failed during "
                + generationError.getStage()
                + " (exit status "
                + (
                        generationError.getExitStatus() != null
                                ? generationError.getExitStatus()
                                : "not available"
                )
                + ")";
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        renderer.setForceWindowsFullscreen(
                shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1") ||
                getIntent().getBooleanExtra(
                        GameApiContract.INTERNAL_EXTRA_FORCE_FULLSCREEN,
                        false
                )
        );

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        autoClicker = new AutoClicker(xServer);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setDirectTouchMode(preferences.getBoolean("direct_touch_mode", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        autoClickerCrosshair = new CrosshairView(this);
        autoClickerCrosshair.setVisibility(View.GONE);
        autoClickerCrosshair.setFraction(
                preferences.getFloat("auto_clicker_x", 50f) / 100f,
                preferences.getFloat("auto_clicker_y", 50f) / 100f);
        rootView.addView(autoClickerCrosshair, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (container != null && container.getHUDMode() != FrameRating.Mode.DISABLED.ordinal()) {
            frameRating = new FrameRating(this);
            frameRating.setMode(FrameRating.Mode.values()[container.getHUDMode()]);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating);
        }

        gameTextOverlayView = new GameTextOverlayView(this);
        rootView.addView(gameTextOverlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        gameTextController = new GameTextController(
                this,
                ManagedGameSettingsAccess.gameTextConfigStore(
                        this,
                        getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID),
                        preferences
                ),
                renderer,
                gameTextOverlayView,
                gameTextNamespace(
                        getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID),
                        container
                )
        );
        setupQuickSessionControls(renderer);

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }
        else {
            String managedGameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
            if (managedGameId != null && !managedGameId.isEmpty()) {
                int managedProfileId =
                        ManagedGameSettingsAccess.controlsProfileId(this, managedGameId);
                if (managedProfileId > 0) {
                    ControlsProfile profile = inputControlsManager.getProfile(managedProfileId);
                    if (profile != null) showInputControls(profile);
                }
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);

        setupStallDiagnostics(rootView, renderer);
    }

    private void setupStallDiagnostics(FrameLayout rootView, final GLRenderer renderer) {
        if (managedDiagnostics == null) return;
        String diagnosticsGameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
        boolean enabledByGame = ManagedGameSettingsAccess.stallTroubleshooterEnabled(
                this, diagnosticsGameId);
        boolean enabledLocally = preferences.getBoolean("enable_stall_diagnostics", false);
        if (!enabledByGame && !enabledLocally) return;
        stallOverlay = new SessionStallOverlayView(this, new SessionStallOverlayView.Listener() {
            @Override
            public void onApplySuggestion(JSONObject suggestion) {
                applyStallSuggestion(suggestion);
            }

            @Override
            public void onKeepWaiting() {
                if (managedDiagnostics != null) managedDiagnostics.dismissStall();
            }

            @Override
            public void onCloseGame() {
                exit(ProcessHelper.TerminationOrigin.USER_EXIT);
            }
        });
        rootView.addView(stallOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        managedDiagnostics.setFrameClock(renderer::getLastFrameTime);
        managedDiagnostics.startStallWatchdog(new ManagedSessionDiagnostics.StallListener() {
            @Override
            public void onStallDetected(JSONObject diagnosis) {
                currentStallCause = diagnosis.optString("cause", "");
                if (stallOverlay != null && !exitStarted.get()) {
                    stallOverlay.showDiagnosis(diagnosis);
                }
            }

            @Override
            public void onStallCleared() {
                if (stallOverlay != null) stallOverlay.hide();
            }

            @Override
            public void onStallResolved(String stepId, String cause) {
                if (stallOverlay != null) stallOverlay.hide();
                AppUtils.showToast(XServerDisplayActivity.this, R.string.stall_overlay_resolved);
            }
        });
    }

    private void applyStallSuggestion(JSONObject suggestion) {
        if (suggestion == null || exitStarted.get()) return;
        String gameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
        if (gameId == null || gameId.isEmpty()) return;
        if (stallOverlay != null) stallOverlay.setActionsEnabled(false);
        AppUtils.showToast(this, R.string.stall_overlay_applying);
        final String baseHash = suggestion.optString("baseConfigSha256", "");
        final String stepId = suggestion.optString("stepId", suggestion.optString("id", ""));
        final JSONObject set = suggestion.optJSONObject("set");
        final String cause = currentStallCause;
        managedSettingsExecutor.execute(() -> {
            try {
                if (managedDiagnostics != null && !stepId.isEmpty()) {
                    managedDiagnostics.recordRemedyApplied(stepId, cause);
                }
                ManagedStallRecovery.applyConfig(this, gameId, baseHash, set);
                runOnUiThread(this::restartSessionForStallFix);
            }
            catch (Exception error) {
                runOnUiThread(() -> {
                    if (stallOverlay != null) stallOverlay.setActionsEnabled(true);
                    AppUtils.showToast(
                            this,
                            getString(R.string.stall_overlay_apply_failed, error.getMessage())
                    );
                });
            }
        });
    }

    private void restartSessionForStallFix() {
        suppressManagedSessionAbort = true;
        if (managedDiagnostics != null) managedDiagnostics.cancel();
        AppUtils.restartActivity(this);
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbDirectTouchMode = dialog.findViewById(R.id.CBDirectTouchMode);
        cbDirectTouchMode.setChecked(touchpadView.isDirectTouchMode());
        cbRelativeMouseMovement.setEnabled(!cbDirectTouchMode.isChecked());
        cbDirectTouchMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) cbRelativeMouseMovement.setChecked(false);
            cbRelativeMouseMovement.setEnabled(!isChecked);
        });

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            boolean directTouchMode = cbDirectTouchMode.isChecked();
            setDirectTouchMode(directTouchMode, true);
            xServer.setRelativeMouseMovement(!directTouchMode && cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    private void setupQuickSessionControls(GLRenderer renderer) {
        quickSessionControls = findViewById(R.id.LLQuickSessionControls);
        quickSessionButtons = findViewById(R.id.LLQuickSessionButtons);
        quickControlsToggleButton = findViewById(R.id.BTQuickControlsToggle);
        quickFullscreenButton = findViewById(R.id.BTQuickFullscreen);
        quickInputModeButton = findViewById(R.id.BTQuickInputMode);
        quickTextButton = findViewById(R.id.BTQuickGameText);
        quickTranslateButton = findViewById(R.id.BTQuickTranslate);
        quickDiagnosticsButton = findViewById(R.id.BTQuickDiagnostics);
        quickStrongOcrButton = findViewById(R.id.BTQuickStrongOcr);
        quickAutoClickerButton = findViewById(R.id.BTQuickAutoClicker);
        quickExitButton = findViewById(R.id.BTQuickExit);
        JSONObject managedControls = loadManagedQuickControls();
        applyQuickControlLayout(managedControls);
        String managedGameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
        boolean controlsExpanded = managedGameId != null && !managedGameId.isEmpty()
                ? managedControls != null && managedControls.optBoolean("expanded", true)
                : preferences.getBoolean(
                        PREF_QUICK_SESSION_CONTROLS_EXPANDED,
                        managedControls == null ||
                                managedControls.optBoolean("expanded", true)
                );
        setQuickSessionControlsExpanded(controlsExpanded, false);
        fullscreenEnabled = renderer.isFullscreen();
        quickFullscreenButton.setSelected(fullscreenEnabled);
        updateQuickInputModeButton();

        quickControlsToggleButton.setOnClickListener((view) ->
                setQuickSessionControlsExpanded(
                        quickSessionButtons.getVisibility() != View.VISIBLE,
                        true
                ));
        quickFullscreenButton.setOnClickListener((view) -> toggleFullscreen(renderer));
        quickInputModeButton.setOnClickListener((view) ->
                setDirectTouchMode(!touchpadView.isDirectTouchMode(), true));
        quickTextButton.setOnClickListener((view) -> showGameTextDialog());
        quickTranslateButton.setOnClickListener((view) -> toggleGameTextTranslation());
        quickDiagnosticsButton.setOnClickListener((view) -> showSessionDiagnosticsDialog());
        quickStrongOcrButton.setOnClickListener((view) -> beginStrongOcrSelection());
        quickAutoClickerButton.setOnClickListener((view) -> toggleAutoClicker());
        quickExitButton.setOnClickListener((view) -> confirmExit());
        updateGameTextButton();
    }

    private void setQuickSessionControlsExpanded(boolean expanded, boolean persist) {
        quickSessionButtons.setVisibility(expanded ? View.VISIBLE : View.GONE);
        quickControlsToggleButton.setContentDescription(getString(
                expanded ? R.string.collapse_quick_controls : R.string.expand_quick_controls
        ));
        if (persist) {
            String gameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
            if (gameId == null || gameId.isEmpty()) {
                preferences.edit()
                        .putBoolean(PREF_QUICK_SESSION_CONTROLS_EXPANDED, expanded)
                        .apply();
            }
            else {
                managedSettingsExecutor.execute(() -> {
                    try {
                        ManagedGameSettingsAccess.setControlsExpanded(
                                this,
                                gameId,
                                expanded
                        );
                    }
                    catch (JSONException | IOException error) {
                        runOnUiThread(() -> AppUtils.showToast(
                                this,
                                "Unable to save quick-control state."
                        ));
                    }
                });
            }
        }
    }

    private JSONObject loadManagedQuickControls() {
        String gameId = getIntent().getStringExtra(GameApiContract.EXTRA_GAME_ID);
        try {
            return ManagedGameSettingsAccess.controls(this, gameId);
        }
        catch (JSONException | IOException error) {
            return null;
        }
    }

    private void applyQuickControlLayout(JSONObject controls) {
        if (controls == null) return;
        int sizeDp;
        switch (controls.optString("buttonSize", "MEDIUM")) {
            case "SMALL":
                sizeDp = 34;
                break;
            case "LARGE":
                sizeDp = 48;
                break;
            default:
                sizeDp = 40;
                break;
        }
        float opacity = (float)controls.optDouble("opacity", 0.85);
        JSONObject views = new JSONObject();
        try {
            views.put("fullscreen", quickFullscreenButton);
            views.put("input_mode", quickInputModeButton);
            views.put("game_text", quickTextButton);
            views.put("strong_ocr", quickStrongOcrButton);
            views.put("exit", quickExitButton);
            JSONArray order = controls.getJSONArray("buttonOrder");
            JSONArray visible = controls.getJSONArray("visibleButtons");
            for (int index = 0; index < order.length(); index++) {
                String key = order.getString(index);
                View button = (View)views.get(key);
                quickSessionButtons.bringChildToFront(button);
                if ("game_text".equals(key)) {
                    quickSessionButtons.bringChildToFront(quickTranslateButton);
                    quickSessionButtons.bringChildToFront(quickDiagnosticsButton);
                }
            }
            int satellitePixels = Math.round(sizeDp * getResources().getDisplayMetrics().density);
            quickTranslateButton.setAlpha(opacity);
            LinearLayout.LayoutParams translateParams =
                    (LinearLayout.LayoutParams)quickTranslateButton.getLayoutParams();
            translateParams.width = satellitePixels;
            translateParams.height = satellitePixels;
            quickTranslateButton.setLayoutParams(translateParams);
            quickDiagnosticsButton.setAlpha(opacity);
            LinearLayout.LayoutParams diagParams =
                    (LinearLayout.LayoutParams)quickDiagnosticsButton.getLayoutParams();
            int diagPixels = satellitePixels;
            diagParams.width = diagPixels;
            diagParams.height = diagPixels;
            quickDiagnosticsButton.setLayoutParams(diagParams);
            for (Iterator<String> keys = views.keys(); keys.hasNext(); ) {
                String key = keys.next();
                View button = (View)views.get(key);
                button.setVisibility(contains(visible, key) ? View.VISIBLE : View.GONE);
                button.setAlpha(opacity);
                LinearLayout.LayoutParams params =
                        (LinearLayout.LayoutParams)button.getLayoutParams();
                int pixels = Math.round(
                        sizeDp * getResources().getDisplayMetrics().density
                );
                params.width = pixels;
                params.height = pixels;
                button.setLayoutParams(params);
            }
            quickControlsToggleButton.setAlpha(opacity);
        }
        catch (JSONException error) {
            throw new IllegalStateException("Invalid managed quick-control settings.", error);
        }
    }

    private static boolean contains(JSONArray values, String expected)
            throws JSONException {
        for (int index = 0; index < values.length(); index++) {
            if (expected.equals(values.getString(index))) return true;
        }
        return false;
    }

    private void toggleFullscreen(GLRenderer renderer) {
        renderer.toggleFullscreen();
        fullscreenEnabled = !fullscreenEnabled;
        touchpadView.setFullscreen(fullscreenEnabled);
        if (quickFullscreenButton != null) {
            quickFullscreenButton.setSelected(fullscreenEnabled);
        }
    }

    private void setDirectTouchMode(boolean directTouchMode, boolean persist) {
        touchpadView.setDirectTouchMode(directTouchMode);
        if (directTouchMode) xServer.setRelativeMouseMovement(false);
        if (persist) {
            preferences.edit().putBoolean("direct_touch_mode", directTouchMode).apply();
        }
        updateQuickInputModeButton();
    }

    private void updateQuickInputModeButton() {
        if (quickInputModeButton == null || touchpadView == null) return;

        boolean directTouchMode = touchpadView.isDirectTouchMode();
        quickInputModeButton.setSelected(directTouchMode);
        quickInputModeButton.setImageResource(
                directTouchMode ? R.drawable.icon_touch_mode : R.drawable.icon_mouse_mode
        );
        quickInputModeButton.setContentDescription(getString(
                directTouchMode
                        ? R.string.switch_to_mouse_mode
                        : R.string.switch_to_touch_mode
        ));
    }

    private void showGameTextDialog() {
        if (gameTextController != null) new GameTextDialog(this, gameTextController).show();
    }

    private void showSessionDiagnosticsDialog() {
        (new SessionDiagnosticsDialog(
                this, managedDiagnostics, xServerView.getRenderer())).show();
    }

    public void updateGameTextButton() {
        if (quickTextButton == null || gameTextController == null) return;
        boolean enabled = gameTextController.isEnabled();
        quickTextButton.setSelected(enabled);
        quickTextButton.setContentDescription(getString(
                enabled ? R.string.game_text_enabled : R.string.game_text
        ));
        if (quickTranslateButton != null) {
            quickTranslateButton.setSelected(enabled);
            quickTranslateButton.setContentDescription(getString(
                    enabled ? R.string.game_text_translate_on : R.string.game_text_translate_off
            ));
        }
    }

    private void toggleGameTextTranslation() {
        if (gameTextController == null) return;
        GameTextConfig current = gameTextController.getConfig();
        GameTextConfig.Mode newMode;
        if (current.mode == GameTextConfig.Mode.OFF) {
            GameTextConfig.Mode last = GameTextConfig.Mode.fromPreference(
                    preferences.getString(PREF_GAME_TEXT_LAST_MODE, GameTextConfig.Mode.SUBTITLE.name())
            );
            newMode = last == GameTextConfig.Mode.OFF ? GameTextConfig.Mode.SUBTITLE : last;
        }
        else {
            preferences.edit().putString(PREF_GAME_TEXT_LAST_MODE, current.mode.name()).apply();
            newMode = GameTextConfig.Mode.OFF;
        }
        gameTextController.saveConfig(new GameTextConfig(
                newMode,
                current.intervalMillis,
                current.captureRegion,
                current.replacements,
                current.sourceLanguage,
                current.targetLanguage
        ));
        updateGameTextButton();
        AppUtils.showToast(this, newMode == GameTextConfig.Mode.OFF
                ? R.string.game_text_translate_off
                : R.string.game_text_translate_on);
    }

    public void beginGameTextRegionSelection() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        if (textRegionSelectorView != null) rootView.removeView(textRegionSelectorView);
        RectF currentRegion = gameTextController.getConfig().captureRegion;
        textRegionSelectorView = new TextRegionSelectorView(this, currentRegion, region -> {
            GameTextConfig current = gameTextController.getConfig();
            GameTextConfig updated = new GameTextConfig(
                    current.mode,
                    current.intervalMillis,
                    region,
                    current.replacements,
                    current.sourceLanguage,
                    current.targetLanguage
            );
            rootView.removeView(textRegionSelectorView);
            textRegionSelectorView = null;
            gameTextController.saveConfig(updated);
            updateGameTextButton();
        });
        rootView.addView(textRegionSelectorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private void beginStrongOcrSelection() {
        if (gameTextController == null || strongOcrSelectionView != null) return;
        if (!gameTextController.isEnabled()) {
            AppUtils.showToast(this, R.string.game_text_strong_enable_first);
            return;
        }

        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        if (textRegionSelectorView != null) {
            rootView.removeView(textRegionSelectorView);
            textRegionSelectorView = null;
        }
        gameTextController.captureStrongOcrSnapshot(snapshot -> {
            if (isFinishing() || strongOcrSelectionView != null) {
                snapshot.recycle();
                return;
            }

            quickSessionControls.setVisibility(View.GONE);
            strongOcrSelectionView = new StrongOcrSelectionView(this, snapshot, region -> {
                StrongOcrSelectionView selectionView = strongOcrSelectionView;
                if (selectionView == null) return;
                Bitmap capturedFrame = selectionView.takeSnapshot();
                rootView.removeView(selectionView);
                strongOcrSelectionView = null;
                quickSessionControls.setVisibility(View.VISIBLE);
                gameTextController.runStrongOcr(
                        capturedFrame,
                        region,
                        this::showStrongOcrInspection
                );
            });
            rootView.addView(strongOcrSelectionView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
        });
    }

    private void cancelStrongOcrSelection() {
        StrongOcrSelectionView selectionView = strongOcrSelectionView;
        if (selectionView == null) return;
        strongOcrSelectionView = null;
        selectionView.releaseSnapshot();
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        rootView.removeView(selectionView);
        quickSessionControls.setVisibility(View.VISIBLE);
    }

    private void showStrongOcrInspection(
            Bitmap snapshot,
            java.util.List<RectF> detectedRegions
    ) {
        if (isFinishing()) {
            snapshot.recycle();
            return;
        }
        closeStrongOcrInspection();
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        quickSessionControls.setVisibility(View.GONE);
        strongOcrInspectionView = new StrongOcrInspectionView(
                this,
                snapshot,
                detectedRegions,
                new StrongOcrInspectionView.Listener() {
                    @Override
                    public void onLineRequested(RectF normalizedRegion) {
                        StrongOcrInspectionView inspection = strongOcrInspectionView;
                        if (inspection == null) return;
                        Bitmap copy = inspection.copySnapshot();
                        closeStrongOcrInspection();
                        if (copy != null) {
                            gameTextController.runStrongOcr(
                                    copy,
                                    normalizedRegion,
                                    XServerDisplayActivity.this::showStrongOcrInspection
                            );
                        }
                    }

                    @Override
                    public void onCloseRequested() {
                        closeStrongOcrInspection();
                    }
                }
        );
        rootView.addView(strongOcrInspectionView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private void closeStrongOcrInspection() {
        StrongOcrInspectionView inspection = strongOcrInspectionView;
        if (inspection == null) return;
        strongOcrInspectionView = null;
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        rootView.removeView(inspection);
        quickSessionControls.setVisibility(View.VISIBLE);
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    private void applyDriverPolicy() {
        if (container == null) return;
        if (!DriverPolicy.LATEST.equals(container.getDriverPolicy())) return;

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            String latest = GeneralComponents.getLatestVersion(
                    GeneralComponents.Type.TURNIP, this);
            if (latest != null) graphicsDriverConfig[0].put("version", latest);
        }
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            String latest = GeneralComponents.getLatestVersion(
                    GeneralComponents.Type.DXVK, this);
            if (latest != null) dxwrapperConfig[0].put("version", latest);
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String latest = GeneralComponents.getLatestVersion(
                    GeneralComponents.Type.WINED3D, this);
            if (latest != null) dxwrapperConfig[0].put("version", latest);
        }
        String latestVkd3d = GeneralComponents.getLatestVersion(
                GeneralComponents.Type.VKD3D, this);
        if (latestVkd3d != null) dxwrapperConfig[1].put("version", latestVkd3d);
    }

    private void logEffectiveDrivers() {
        if (container == null) return;
        String graphics = describeGraphicsDriver(0)+","+describeGraphicsDriver(1);
        String dx = dxwrapper;
        if (DXWrappers.DXVK.equals(dxwrapper)) {
            dx = "dxvk-" + dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (DXWrappers.WINED3D.equals(dxwrapper)) {
            dx = "wined3d-" + dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }
        String vkd3d = "vkd3d-" + dxwrapperConfig[1].get("version", DefaultVersion.VKD3D);
        ProcessHelper.emitDebugMessage("[DRIVERS] policy=" + container.getDriverPolicy()
                + " graphics=" + graphics
                + " graphicsConfig="+container.getGraphicsDriverConfig()
                + " dxwrapper=" + dx
                + " dxwrapperConfig="+container.getDXWrapperConfig()
                + " box64Preset="+effectiveBox64Preset
                + " " + vkd3d);
    }

    private String describeGraphicsDriver(int index) {
        String driver = graphicsDriver[index];
        String version = graphicsDriverConfig[index].get("version");
        if (version.isEmpty()) version = DefaultVersion.valueOf(driver);
        return version != null && !version.isEmpty() ? driver+"-"+version : driver;
    }

    private void beginExitDiagnosticsSession() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("containerId", String.valueOf(container.id));
        details.put("driverPolicy", container.getDriverPolicy());
        details.put("graphicsDriver", graphicsDriver[0]+","+graphicsDriver[1]);
        details.put("graphicsDriverConfig", container.getGraphicsDriverConfig());
        details.put("dxwrapper", dxwrapper);
        details.put("dxwrapperConfig", container.getDXWrapperConfig());
        details.put("box64Preset", effectiveBox64Preset);
        details.put("screenSize", screenInfo.toString());
        Intent intent = getIntent();
        details.put("gameId", intent.getStringExtra(GameApiContract.EXTRA_GAME_ID));
        String executable = intent.hasExtra("exec_dos_path")
                ? intent.getStringExtra("exec_dos_path")
                : intent.getStringExtra("exec_path");
        details.put("executable", executable);
        details.put("launchType", hasDirectExecutableIntent()
                ? "direct_exe"
                : shortcut != null ? "shortcut" : "container");
        AppExitDiagnostics.beginSession(this, details);
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        boolean changed = !cacheId.equals(container.getExtra("graphicsDriver"));
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            container.putExtra("graphicsDriver", cacheId);
            container.saveData();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK) && (changed || MainActivity.DEBUG_MODE)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");

                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                break;
        }
    }

    private void showTouchpadHelpDialog() {
        boolean directTouchMode = touchpadView.isDirectTouchMode();
        ContentDialog dialog = new ContentDialog(this,
                directTouchMode ? R.layout.direct_touch_help_dialog : R.layout.touchpad_help_dialog);
        dialog.setTitle(directTouchMode ? R.string.direct_touch_help : R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper"));
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        return true;
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    static String gameTextNamespace(String gameId, Container container) {
        if (gameId != null && !gameId.isEmpty()) return "game:" + gameId;
        if (container != null) return "container:" + container.id;
        return "wineprefix";
    }

    private boolean hasDirectExecutableIntent() {
        Intent intent = getIntent();
        return intent.hasExtra("exec_path") || intent.hasExtra("exec_dos_path");
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.path.endsWith(".lnk") || shortcut.path.contains("://")) {
                cmdArgs = "\""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            Intent intent = getIntent();
            String directExecArgs = intent.getStringExtra("exec_args");
            execArgs = directExecArgs != null && !directExecArgs.isEmpty() ? " "+directExecArgs : "";

            if (intent.hasExtra("exec_dos_path")) {
                execPath = intent.getStringExtra("exec_dos_path");
            }
            else if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);
            }

            if (execPath != null && execPath.endsWith(".lnk")) {
                cmdArgs = "\""+execPath+"\""+execArgs;
                execPath = null;
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }
            cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \""+filename+"\""+execArgs;
        }

        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\windows \"wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public static boolean isSessionActive() {
        return sessionActive;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void changeWineAudioDriver() {
        File rootDir = rootFS.getRootDir();
        File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
        // Always pin the Wine audio driver to a single backend on every launch. Gating this on a
        // value change previously left a stale/default driver list after a wineprefix update
        // (e.g. an app upgrade regenerating the prefix): the extra still said "pulseaudio" so the
        // pin was skipped, Wine fell back to its default "pulse,alsa" probe order, and winealsa's
        // init deadlocked (Wine 10.10) even though PulseAudio was selected — hanging the game.
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (audioDriver.equals(AudioDrivers.ALSA) || audioDriver.equals(AudioDrivers.SILENT)) {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
            }
            else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
            }
            else if (audioDriver.equals(AudioDrivers.DISABLED)) {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "");
            }

            // Pinning the "Audio" driver above is not enough: Wine 10.10's DirectSound/mmdevapi
            // device enumeration (which RGSS and other games trigger when they start BGM
            // playback) loads EVERY audio .drv regardless of that preference, and winealsa's
            // init deadlocks on this build — hanging the game right after audio init. Force the
            // unused audio backend off via DllOverrides ("" = disabled) so only the selected one
            // can ever be loaded. The value is rewritten every launch, so switching drivers or a
            // wineprefix reset always re-applies the correct pair.
            String winepulseOverride, winealsaOverride;
            if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                winepulseOverride = "builtin";
                winealsaOverride = "";
            }
            else if (audioDriver.equals(AudioDrivers.ALSA) || audioDriver.equals(AudioDrivers.SILENT)) {
                winepulseOverride = "";
                winealsaOverride = "builtin";
            }
            else {
                winepulseOverride = "";
                winealsaOverride = "";
            }
            registryEditor.setStringValue("Software\\Wine\\DllOverrides", "winepulse", winepulseOverride);
            registryEditor.setStringValue("Software\\Wine\\DllOverrides", "winealsa", winealsaOverride);
        }
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                RuntimeAssetProvisioner.getAsset(this, RuntimeAssetManifest.ROOTFS_PATCHES),
                rootDir
        );
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        SettingsFragment.resetBox64Version(this);
    }

    private void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null) return;
        if (visible) {
            Window child = window.getChildCount() > 0 ? window.getChildren().get(0) : null;
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                Window frameRatingWindow = window.isSurface() ? window : child;
                if (frameRating.getMode() == FrameRating.Mode.FULL) {
                    Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                    frameRating.setGPUInfo(gpuInfo != null ? new String(gpuInfo.data.array()) : "N/A");
                }
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}