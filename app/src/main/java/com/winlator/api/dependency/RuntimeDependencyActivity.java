package com.winlator.api.dependency;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.winlator.R;
import com.winlator.MainActivity;
import com.winlator.XServerDisplayActivity;
import com.winlator.api.GameApiContract;
import com.winlator.api.GameManagerActivity;
import com.winlator.api.RuntimeDependencyFacade;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Internal-only UI for managing Windows runtime dependencies on a container.
 *
 * <h3>Opening this activity</h3>
 * The activity is {@code android:exported="false"}. Launch it from within the app with:
 * <ul>
 *   <li>{@link #INTERNAL_EXTRA_TRUSTED_CALLER} = {@link #TRUSTED_CALLER_VALUE}</li>
 *   <li>{@link #EXTRA_CONTAINER_ID} = the target container's integer ID</li>
 *   <li>(optional) {@link #EXTRA_GAME_ID} for display context</li>
 * </ul>
 *
 * <h3>Integration hooks needed from parent</h3>
 * <ol>
 *   <li>A launch path in the AGM / game-manager flow that builds an Intent with the
 *       trusted extras and starts this activity.</li>
 *   <li>A post-install callback hook (e.g. via a BroadcastReceiver or the
 *       {@code GameSessionEventReporter} event) so callers can invoke
 *       {@link RuntimeDependencyManager#markResult} when the Wine session ends.</li>
 * </ol>
 */
public class RuntimeDependencyActivity extends AppCompatActivity {

    /** Required trusted-caller marker; value must equal {@link #TRUSTED_CALLER_VALUE}. */
    public static final String INTERNAL_EXTRA_TRUSTED_CALLER =
            "com.winlator.secure.internal.TRUSTED_CALLER";
    public static final String TRUSTED_CALLER_VALUE = "winlator_internal";

    /** Required: the container ID to manage. */
    public static final String EXTRA_CONTAINER_ID = "container_id";

    /** Optional: game ID for display context. */
    public static final String EXTRA_GAME_ID = "game_id";
    public static final String EXTRA_BOOTSTRAP = "bootstrap";
    private static final String EXTRA_AUTO_CLOSE_ON_COMPLETE = "auto_close_on_complete";

    private static final int REQUEST_INSTALLER_FILE = 1;

    private int containerId;
    private Container container;
    private LinearLayout contentLayout;
    private String gameId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(3);
    private final Map<String, CheckBox> bootstrapChecks = new LinkedHashMap<>();
    private CheckBox bootstrapLicenseAcceptance;
    private boolean bootstrapMode;
    private boolean bootstrapInitialized;
    private boolean bootstrapBusy;
    private boolean autoCloseOnComplete;

    /** Tracks which dependencyId is waiting for a file-picker result. */
    private String pendingPickerDependencyId;

    private static Intent bootstrapIntent(Context context, boolean autoCloseOnComplete) {
        return new Intent(context, RuntimeDependencyActivity.class)
                .putExtra(INTERNAL_EXTRA_TRUSTED_CALLER, TRUSTED_CALLER_VALUE)
                .putExtra(EXTRA_BOOTSTRAP, true)
                .putExtra(EXTRA_AUTO_CLOSE_ON_COMPLETE, autoCloseOnComplete);
    }

    public static void openBootstrap(Context context) {
        context.startActivity(bootstrapIntent(context, false));
    }

    public static void openBootstrapIfNeeded(Context context) {
        if (!RuntimeDependencyManager.isAgmBootstrapReady(context)) {
            context.startActivity(bootstrapIntent(context, true));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Trusted-caller guard ──────────────────────────────────────────────
        String callerMark = getIntent().getStringExtra(INTERNAL_EXTRA_TRUSTED_CALLER);
        if (!TRUSTED_CALLER_VALUE.equals(callerMark)) {
            finish();
            return;
        }

        bootstrapMode = getIntent().getBooleanExtra(EXTRA_BOOTSTRAP, false);
        autoCloseOnComplete = getIntent().getBooleanExtra(
                EXTRA_AUTO_CLOSE_ON_COMPLETE,
                false
        );
        containerId = getIntent().getIntExtra(EXTRA_CONTAINER_ID, -1);
        gameId = getIntent().getStringExtra(EXTRA_GAME_ID);
        if (!bootstrapMode && containerId < 0) {
            finish();
            return;
        }

        setContentView(R.layout.activity_runtime_dependency);

        Toolbar toolbar = findViewById(R.id.Toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(
                    bootstrapMode ? R.string.prerequisites_title : R.string.rdm_title
            ));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        contentLayout = findViewById(R.id.ContentLayout);

        if (bootstrapMode) {
            buildBootstrapUiAsync();
        }
        else {
            ContainerManager manager = new ContainerManager(this);
            container = manager.getContainerById(containerId);
            if (container == null) {
                addInfoText(getString(R.string.rdm_container_not_found, containerId));
                return;
            }
            buildUiAsync();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!bootstrapMode || !bootstrapInitialized || bootstrapBusy) return;
        executor.execute(() -> {
            try {
                RuntimeDependencyBootstrapRecord record =
                        RuntimeDependencyManager.recoverInterruptedAgmBootstrap(this);
                runOnUiThread(() -> {
                    if (record != null
                            && record.state ==
                            RuntimeDependencyBootstrapState.READY_TO_INSTALL) {
                        launchNextBootstrapInstaller();
                    }
                    else refreshBootstrapContent();
                });
            }
            catch (JSONException | IOException error) {
                runOnUiThread(() -> showBootstrapError(error.getMessage()));
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALLER_FILE
                && resultCode == Activity.RESULT_OK
                && data != null
                && pendingPickerDependencyId != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String dependencyId = pendingPickerDependencyId;
                pendingPickerDependencyId = null;
                executor.execute(() -> {
                    try {
                        RuntimeDependencyManager.importInstaller(
                                this,
                                uri,
                                containerId,
                                dependencyId
                        );
                        runOnUiThread(this::refreshContent);
                    }
                    catch (JSONException | IOException | IllegalArgumentException error) {
                        runOnUiThread(() -> Toast.makeText(
                                this,
                                error.getMessage(),
                                Toast.LENGTH_LONG
                        ).show());
                    }
                });
                return;
            }
            Toast.makeText(this,
                    getString(R.string.rdm_installer_path_unresolvable),
                    Toast.LENGTH_LONG).show();
            pendingPickerDependencyId = null;
        }
    }

    // ── Build / refresh UI ────────────────────────────────────────────────────

    private void buildUiAsync() {
        executor.execute(() -> {
            try {
                List<RuntimeDependencyFacade.AffectedGame> affected =
                        RuntimeDependencyManager.findAffectedGames(this, containerId);
                Map<String, DependencyInstallRecord> statuses =
                        RuntimeDependencyManager.getContainerStatus(this, containerId);
                runOnUiThread(() -> buildContent(affected, statuses));
            }
            catch (JSONException | IOException e) {
                runOnUiThread(() ->
                        addInfoText(getString(R.string.rdm_load_error, e.getMessage())));
            }
        });
    }

    private void refreshContent() {
        if (bootstrapMode) {
            refreshBootstrapContent();
            return;
        }
        contentLayout.removeAllViews();
        buildUiAsync();
    }

    private void buildBootstrapUiAsync() {
        bootstrapBusy = true;
        executor.execute(() -> {
            try {
                if (!RootFS.find(this).isValid()) {
                    throw new IOException(getString(R.string.rdm_rootfs_not_ready));
                }
                RuntimeDependencyBootstrapRecord record =
                        RuntimeDependencyManager.inspectAgmBootstrap(this);
                if (record.state == RuntimeDependencyBootstrapState.DOWNLOADING
                        || (record.state == RuntimeDependencyBootstrapState.INSTALLING
                        && !XServerDisplayActivity.isSessionActive())) {
                    record = RuntimeDependencyManager.recoverInterruptedAgmBootstrap(this);
                }
                containerId = record.containerId;
                container = new ContainerManager(this).getContainerById(containerId);
                if (container == null) throw new IOException("AGM shared container is missing.");
                Map<String, Boolean> states =
                        RuntimeDependencyManager.getAgmBootstrapPackageStates(this);
                RuntimeDependencyBootstrapRecord result = record;
                runOnUiThread(() -> {
                    bootstrapInitialized = true;
                    bootstrapBusy = false;
                    if (result.state == RuntimeDependencyBootstrapState.READY_TO_INSTALL) {
                        launchNextBootstrapInstaller();
                    }
                    else buildBootstrapContent(result, states);
                });
            }
            catch (JSONException | IOException error) {
                runOnUiThread(() -> {
                    bootstrapBusy = false;
                    showBootstrapError(error.getMessage());
                });
            }
        });
    }

    private void refreshBootstrapContent() {
        if (bootstrapBusy || isFinishing() || isDestroyed() || executor.isShutdown()) return;
        bootstrapBusy = true;
        executor.execute(() -> {
            try {
                RuntimeDependencyBootstrapRecord record =
                        RuntimeDependencyManager.getAgmBootstrap(this);
                if (record == null) {
                    record = RuntimeDependencyManager.inspectAgmBootstrap(this);
                }
                Map<String, Boolean> states =
                        RuntimeDependencyManager.getAgmBootstrapPackageStates(this);
                RuntimeDependencyBootstrapRecord result = record;
                runOnUiThread(() -> {
                    bootstrapBusy = false;
                    buildBootstrapContent(result, states);
                });
            }
            catch (JSONException | IOException error) {
                runOnUiThread(() -> {
                    bootstrapBusy = false;
                    showBootstrapError(error.getMessage());
                });
            }
        });
    }

    private void buildBootstrapContent(
            RuntimeDependencyBootstrapRecord record,
            Map<String, Boolean> states
    ) {
        boolean allInstalled = !states.isEmpty();
        for (Boolean installed : states.values()) {
            if (!Boolean.TRUE.equals(installed)) {
                allInstalled = false;
                break;
            }
        }
        if (autoCloseOnComplete && allInstalled) {
            if (record.state == RuntimeDependencyBootstrapState.COMPLETE) {
                finishBootstrapToMain();
                return;
            }
            bootstrapBusy = true;
            executor.execute(() -> {
                try {
                    RuntimeDependencyManager.selectAgmBootstrapPackages(
                            this,
                            java.util.Collections.emptyList()
                    );
                    RuntimeDependencyManager.markAgmBootstrapDownloadsReady(this);
                    RuntimeDependencyManager.beginNextAgmBootstrapInstall(this);
                    runOnUiThread(this::finishBootstrapToMain);
                }
                catch (JSONException | IOException error) {
                    runOnUiThread(() -> showBootstrapError(error.getMessage()));
                }
            });
            return;
        }
        contentLayout.removeAllViews();
        bootstrapChecks.clear();
        addSectionHeader(getString(R.string.prerequisites_for_container, container.getName()));
        addInfoText(getString(R.string.prerequisites_description));
        addInfoText(getString(
                R.string.prerequisites_plan_status,
                record.planVersion,
                record.state.name()
        ));
        if (record.lastError != null) addWarningText(record.lastError);
        addDivider();

        for (RuntimeDependencyCatalog.BootstrapPackage entry :
                RuntimeDependencyCatalog.bootstrapSelectablePackages()) {
            boolean installed = Boolean.TRUE.equals(states.get(entry.id));
            LinearLayout row = makeEntryRow();
            CheckBox checkBox = new CheckBox(this);
            checkBox.setChecked(!installed);
            checkBox.setEnabled(record.state != RuntimeDependencyBootstrapState.INSTALLING);
            bootstrapChecks.put(entry.id, checkBox);
            row.addView(checkBox);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            ));
            info.addView(makeNameView(entry.displayName));
            info.addView(makeSmallText(getString(
                    installed
                            ? R.string.prerequisite_installed
                            : R.string.prerequisite_missing,
                    entry.version
            )));
            TextView license = makeSmallText(entry.licenseUrl);
            Linkify.addLinks(license, Linkify.WEB_URLS);
            license.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            info.addView(license);
            row.addView(info);
            contentLayout.addView(row);
        }

        bootstrapLicenseAcceptance = new CheckBox(this);
        bootstrapLicenseAcceptance.setText(R.string.prerequisites_license_acceptance);
        bootstrapLicenseAcceptance.setEnabled(
                record.state != RuntimeDependencyBootstrapState.INSTALLING
        );
        contentLayout.addView(bootstrapLicenseAcceptance);

        Button install = makeSmallButton(
                record.state == RuntimeDependencyBootstrapState.COMPLETE
                        ? getString(R.string.reinstall_selected_prerequisites)
                        : getString(R.string.install_selected_prerequisites)
        );
        install.setEnabled(false);
        bootstrapLicenseAcceptance.setOnCheckedChangeListener(
                (button, checked) -> install.setEnabled(
                        checked
                                && record.state !=
                                RuntimeDependencyBootstrapState.INSTALLING
                )
        );
        install.setOnClickListener(view -> startBootstrapDownloads());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_HORIZONTAL;
        install.setLayoutParams(params);
        contentLayout.addView(install);
    }

    private void startBootstrapDownloads() {
        if (bootstrapBusy) return;
        if (bootstrapLicenseAcceptance == null
                || !bootstrapLicenseAcceptance.isChecked()) {
            showBootstrapError(getString(R.string.prerequisites_acceptance_required));
            return;
        }
        ArrayList<String> selected = new ArrayList<>();
        for (Map.Entry<String, CheckBox> item : bootstrapChecks.entrySet()) {
            if (item.getValue().isChecked()) selected.add(item.getKey());
        }
        bootstrapBusy = true;
        contentLayout.removeAllViews();
        TextView progressText = makeNameView(getString(R.string.preparing_prerequisites));
        contentLayout.addView(progressText);

        executor.execute(() -> {
            try {
                List<String> missing =
                        RuntimeDependencyManager.missingAgmBootstrapPackages(this);
                if (!selected.containsAll(missing)) {
                    throw new IllegalArgumentException(
                            getString(R.string.all_missing_prerequisites_required)
                    );
                }
                RuntimeDependencyBootstrapRecord record =
                        RuntimeDependencyManager.selectAgmBootstrapPackages(this, selected);
                List<String> expanded = record.selectedPackageIds;
                List<RuntimeDependencyCatalog.BootstrapPackage> downloads = new ArrayList<>();
                long totalBytes = 0;
                for (String id : expanded) {
                    RuntimeDependencyCatalog.BootstrapPackage entry =
                            RuntimeDependencyCatalog.getBootstrapPackage(id);
                    if (entry != null && entry.requiresDownload()) {
                        downloads.add(entry);
                        totalBytes += entry.size;
                    }
                }

                CountDownLatch latch = new CountDownLatch(downloads.size());
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicInteger completed = new AtomicInteger();
                Map<String, Long> packageProgress = new ConcurrentHashMap<>();
                long expectedTotal = totalBytes;
                for (RuntimeDependencyCatalog.BootstrapPackage entry : downloads) {
                    DOWNLOAD_EXECUTOR.execute(() -> {
                        try {
                            RuntimeDependencyManager.downloadBootstrapPackage(
                                    this,
                                    entry,
                                    (downloaded, total) -> {
                                        packageProgress.put(entry.id, downloaded);
                                        long aggregate = 0;
                                        for (Long value : packageProgress.values()) {
                                            aggregate += value;
                                        }
                                        int percent = expectedTotal > 0
                                                ? (int)((aggregate * 100) / expectedTotal)
                                                : 100;
                                        if (!isDestroyed()) {
                                            runOnUiThread(() -> progressText.setText(getString(
                                                    R.string.downloading_prerequisites,
                                                    percent
                                            )));
                                        }
                                    }
                            );
                            int count = completed.incrementAndGet();
                            if (!isDestroyed()) {
                                runOnUiThread(() -> progressText.setText(getString(
                                        R.string.downloaded_prerequisites,
                                        count,
                                        downloads.size()
                                )));
                            }
                        }
                        catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        }
                        finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
                Throwable error = failure.get();
                if (error != null) {
                    RuntimeDependencyManager.failAgmBootstrap(this, error.getMessage());
                    throw new IOException(error.getMessage(), error);
                }
                RuntimeDependencyManager.markAgmBootstrapDownloadsReady(this);
                if (!isDestroyed()) {
                    runOnUiThread(() -> {
                        bootstrapBusy = false;
                        launchNextBootstrapInstaller();
                    });
                }
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                runOnUiThread(() -> showBootstrapError("Prerequisite download was interrupted."));
            }
            catch (JSONException | IOException | IllegalArgumentException error) {
                runOnUiThread(() -> showBootstrapError(error.getMessage()));
            }
        });
    }

    private void launchNextBootstrapInstaller() {
        if (bootstrapBusy) return;
        bootstrapBusy = true;
        executor.execute(() -> {
            String sessionToken = null;
            try {
                long deadline = System.currentTimeMillis() + 15000;
                while (XServerDisplayActivity.isSessionActive()
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250);
                }
                if (XServerDisplayActivity.isSessionActive()) {
                    throw new IOException(getString(R.string.rdm_winlator_busy));
                }
                RuntimeDependencyBootstrapRecord record =
                        RuntimeDependencyManager.getAgmBootstrap(this);
                if (record == null) throw new IOException("Prerequisite bootstrap is missing.");
                String nextId = record.nextPackageId();
                if (nextId != null) {
                    sessionToken = GameManagerActivity.reserveInternalLaunch();
                    if (sessionToken == null) throw new IOException(
                            getString(R.string.rdm_winlator_busy)
                    );
                }
                RuntimeDependencyCatalog.BootstrapPackage entry =
                        RuntimeDependencyManager.beginNextAgmBootstrapInstall(this);
                if (entry == null) {
                    if (sessionToken != null) {
                        GameManagerActivity.releaseInternalLaunch(sessionToken);
                    }
                    runOnUiThread(() -> {
                        bootstrapBusy = false;
                        refreshBootstrapContent();
                    });
                    return;
                }
                File installer = RuntimeDependencyManager.getBootstrapPackageFile(this, entry);
                String reservedToken = sessionToken;
                runOnUiThread(() -> {
                    Intent intent = new Intent(this, XServerDisplayActivity.class);
                    intent.putExtra("container_id", containerId);
                    intent.putExtra("exec_path", installer.getPath());
                    intent.putExtra("exec_args", entry.arguments);
                    intent.putExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN, reservedToken);
                    try {
                        startActivity(RuntimeDependencyFacade.configureInstallerIntent(
                                this,
                                intent,
                                containerId,
                                null,
                                entry.id
                        ));
                        bootstrapBusy = false;
                    }
                    catch (RuntimeException | JSONException | IOException error) {
                        GameManagerActivity.releaseInternalLaunch(reservedToken);
                        try {
                            RuntimeDependencyManager.markResult(
                                    this,
                                    containerId,
                                    entry.id,
                                    false,
                                    "Launch failed: " + error.getMessage()
                            );
                        }
                        catch (JSONException | IOException statusError) {
                            error.addSuppressed(statusError);
                        }
                        bootstrapBusy = false;
                        showBootstrapError(error.getMessage());
                    }
                });
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (sessionToken != null) GameManagerActivity.releaseInternalLaunch(sessionToken);
                runOnUiThread(() -> {
                    bootstrapBusy = false;
                    showBootstrapError("Prerequisite launch was interrupted.");
                });
            }
            catch (JSONException | IOException error) {
                if (sessionToken != null) GameManagerActivity.releaseInternalLaunch(sessionToken);
                runOnUiThread(() -> {
                    bootstrapBusy = false;
                    showBootstrapError(error.getMessage());
                });
            }
        });
    }

    private void showBootstrapError(String message) {
        if (isFinishing() || isDestroyed() || executor.isShutdown()) return;
        bootstrapBusy = false;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        contentLayout.removeAllViews();
        addWarningText(message);
        Button retry = makeSmallButton(getString(R.string.retry));
        retry.setOnClickListener(view -> buildBootstrapUiAsync());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_HORIZONTAL;
        retry.setLayoutParams(params);
        contentLayout.addView(retry);
    }

    private void finishBootstrapToMain() {
        startActivity(
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP)
        );
        finish();
    }

    private void buildContent(
            List<RuntimeDependencyFacade.AffectedGame> affected,
            Map<String, DependencyInstallRecord> statuses) {

        // ── Container header ──────────────────────────────────────────────────
        addSectionHeader(getString(R.string.rdm_container_header,
                container.getName(), containerId));

        // ── Affected-games warning ────────────────────────────────────────────
        if (affected.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (RuntimeDependencyFacade.AffectedGame g : affected) {
                if (names.length() > 0) names.append(", ");
                names.append(g.title);
            }
            addWarningText(getString(R.string.rdm_shared_container_warning,
                    affected.size(), names.toString()));
        }
        else if (affected.size() == 1) {
            addInfoText(getString(R.string.rdm_single_game_context, affected.get(0).title));
        }
        else {
            addInfoText(getString(R.string.rdm_no_games_on_container));
        }

        addDivider();

        // ── Supported runtimes ────────────────────────────────────────────────
        addSectionHeader(getString(R.string.rdm_section_supported));
        for (RuntimeDependencyCatalog.Entry entry : RuntimeDependencyCatalog.supportedEntries()) {
            DependencyInstallRecord record = statuses.get(entry.id);
            if (record == null) record = DependencyInstallRecord.empty();
            addSupportedEntry(entry, record, affected);
        }

        addDivider();

        // ── Wrapper-config runtimes ───────────────────────────────────────────
        addSectionHeader(getString(R.string.rdm_section_wrapper_config));
        for (RuntimeDependencyCatalog.Entry entry : RuntimeDependencyCatalog.wrapperConfigEntries()) {
            addWrapperConfigEntry(entry);
        }

        addDivider();

        // ── Unsupported runtimes ──────────────────────────────────────────────
        addSectionHeader(getString(R.string.rdm_section_unsupported));
        for (RuntimeDependencyCatalog.Entry entry : RuntimeDependencyCatalog.unsupportedEntries()) {
            addUnsupportedEntry(entry);
        }
    }

    // ── Entry row builders ────────────────────────────────────────────────────

    private void addSupportedEntry(
            RuntimeDependencyCatalog.Entry entry,
            DependencyInstallRecord record,
            List<RuntimeDependencyFacade.AffectedGame> affected) {

        LinearLayout row = makeEntryRow();

        LinearLayout infoColumn = new LinearLayout(this);
        infoColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoColumn.setLayoutParams(infoParams);

        infoColumn.addView(makeNameView(entry.displayName));
        infoColumn.addView(makeStatusView(record.status));

        if (record.installerPath != null) {
            infoColumn.addView(makeSmallText(
                    getString(R.string.rdm_installer_path_label,
                            new java.io.File(record.installerPath).getName())));
        }

        row.addView(infoColumn);

        // Buttons column
        LinearLayout btnColumn = new LinearLayout(this);
        btnColumn.setOrientation(LinearLayout.VERTICAL);
        btnColumn.setGravity(Gravity.END);

        Button pickBtn = makeSmallButton(getString(R.string.rdm_select_installer));
        pickBtn.setOnClickListener(v -> pickInstallerFile(entry.id));
        btnColumn.addView(pickBtn);

        boolean canInstall = record.installerPath != null
                && RuntimeDependencyManager.isInstallerPathValid(record.installerPath);
        boolean alreadyInstalled = record.status == DependencyStatus.INSTALLED;

        String installLabel = alreadyInstalled
                ? getString(R.string.rdm_repair)
                : getString(R.string.rdm_install);
        Button installBtn = makeSmallButton(installLabel);
        installBtn.setEnabled(canInstall);
        installBtn.setOnClickListener(v ->
                showInstallConfirmation(entry, record, affected));
        btnColumn.addView(installBtn);

        row.addView(btnColumn);
        contentLayout.addView(row);
    }

    private void addWrapperConfigEntry(RuntimeDependencyCatalog.Entry entry) {
        LinearLayout row = makeEntryRow();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(makeNameView(entry.displayName));
        col.addView(makeSmallText(getString(R.string.rdm_wrapper_config_note)));
        row.addView(col);
        contentLayout.addView(row);
    }

    private void addUnsupportedEntry(RuntimeDependencyCatalog.Entry entry) {
        LinearLayout row = makeEntryRow();
        row.setAlpha(0.5f);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(makeNameView(entry.displayName));
        TextView unsupported = makeSmallText(getString(R.string.rdm_not_supported));
        unsupported.setTextColor(Color.parseColor("#EF5350"));
        col.addView(unsupported);
        row.addView(col);
        contentLayout.addView(row);
    }

    // ── Confirmation and launch ───────────────────────────────────────────────

    private void showInstallConfirmation(
            RuntimeDependencyCatalog.Entry entry,
            DependencyInstallRecord record,
            List<RuntimeDependencyFacade.AffectedGame> affected) {

        StringBuilder message = new StringBuilder();
        message.append(getString(R.string.rdm_confirm_install_runtime, entry.displayName));

        if (affected.size() > 1) {
            message.append("\n\n");
            message.append(getString(R.string.rdm_confirm_shared_warning, affected.size()));
            message.append("\n");
            for (RuntimeDependencyFacade.AffectedGame g : affected) {
                message.append("  \u2022 ").append(g.title).append("\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(entry.displayName)
                .setMessage(message.toString().trim())
                .setPositiveButton(getString(R.string.rdm_confirm_install_ok),
                        (d, w) -> launchInstaller(entry, record))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchInstaller(RuntimeDependencyCatalog.Entry entry,
            DependencyInstallRecord record) {

        if (!RootFS.find(this).isValid()) {
            Toast.makeText(this, getString(R.string.rdm_rootfs_not_ready), Toast.LENGTH_LONG).show();
            return;
        }
        if (XServerDisplayActivity.isSessionActive()) {
            Toast.makeText(this, getString(R.string.rdm_winlator_busy), Toast.LENGTH_LONG).show();
            return;
        }
        String installerPath = record.installerPath;
        if (!RuntimeDependencyManager.isInstallerPathValid(installerPath)) {
            Toast.makeText(this, getString(R.string.rdm_installer_file_missing), Toast.LENGTH_LONG).show();
            return;
        }

        String sessionToken = GameManagerActivity.reserveInternalLaunch();
        if (sessionToken == null) {
            Toast.makeText(this, getString(R.string.rdm_winlator_busy), Toast.LENGTH_LONG).show();
            return;
        }
        executor.execute(() -> {
            try {
                synchronized (ContainerOperationLock.LOCK) {
                    RuntimeDependencyManager.prepareContainerDrivesForInstaller(
                            this, container, installerPath);
                    RuntimeDependencyManager.markInstalling(
                            this, containerId, entry.id, installerPath);
                }
            }
            catch (JSONException | IOException e) {
                GameManagerActivity.releaseInternalLaunch(sessionToken);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.rdm_prepare_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
                return;
            }

            runOnUiThread(() -> {
                // Uses the same XServerDisplayActivity launch pathway as GameManagerActivity
                Intent intent = new Intent(this, XServerDisplayActivity.class);
                intent.putExtra("container_id", containerId);
                intent.putExtra("exec_path", installerPath);
                intent.putExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN, sessionToken);
                try {
                    startActivity(RuntimeDependencyFacade.configureInstallerIntent(
                            this,
                            intent,
                            containerId,
                            gameId,
                            entry.id
                    ));
                    refreshContent();
                }
                catch (RuntimeException | JSONException | IOException e) {
                    GameManagerActivity.releaseInternalLaunch(sessionToken);
                    try {
                        RuntimeDependencyManager.markResult(
                                this, containerId, entry.id, false,
                                "Launch failed: " + e.getMessage());
                    }
                    catch (JSONException | IOException statusError) {
                        e.addSuppressed(statusError);
                    }
                    Toast.makeText(this,
                            getString(R.string.rdm_launch_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    refreshContent();
                }
            });
        });
    }

    // ── File picker ───────────────────────────────────────────────────────────

    private void pickInstallerFile(String dependencyId) {
        pendingPickerDependencyId = dependencyId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_INSTALLER_FILE);
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private LinearLayout makeEntryRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackgroundColor(resolveColor(R.attr.colorSecondarySurface));
        return row;
    }

    private TextView makeNameView(String name) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(resolveColor(R.attr.colorPrimaryText));
        return tv;
    }

    private TextView makeStatusView(DependencyStatus status) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        switch (status) {
            case INSTALLED:
                tv.setText(getString(R.string.rdm_status_installed));
                tv.setTextColor(Color.parseColor("#4CAF50"));
                break;
            case INSTALLING:
                tv.setText(getString(R.string.rdm_status_installing));
                tv.setTextColor(Color.parseColor("#FF9800"));
                break;
            case FAILED:
                tv.setText(getString(R.string.rdm_status_failed));
                tv.setTextColor(Color.parseColor("#EF5350"));
                break;
            default:
                tv.setText(getString(R.string.rdm_status_not_installed));
                tv.setTextColor(resolveColor(R.attr.colorSecondaryText));
        }
        return tv;
    }

    private TextView makeSmallText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(resolveColor(R.attr.colorSecondaryText));
        return tv;
    }

    private Button makeSmallButton(String label) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(2), 0, dp(2));
        btn.setLayoutParams(params);
        return btn;
    }

    private void addSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(resolveColor(R.attr.colorPrimaryVariant));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, dp(4));
        tv.setLayoutParams(params);
        contentLayout.addView(tv);
    }

    private void addInfoText(String text) {
        TextView tv = makeSmallText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        tv.setLayoutParams(params);
        contentLayout.addView(tv);
    }

    private void addWarningText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.parseColor("#FF9800"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        tv.setLayoutParams(params);
        contentLayout.addView(tv);
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(resolveColor(R.attr.colorPrimaryVariant));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(0, dp(8), 0, dp(8));
        divider.setLayoutParams(params);
        contentLayout.addView(divider);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int resolveColor(int attrId) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrId, tv, true);
        return tv.data;
    }
}
