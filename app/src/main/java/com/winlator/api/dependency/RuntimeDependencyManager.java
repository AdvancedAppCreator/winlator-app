package com.winlator.api.dependency;

import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.util.Log;

import com.winlator.api.RuntimeDependencyFacade;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.HttpUtils;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Business-logic layer for the runtime dependency manager.
 *
 * <p><b>Container-wide scope:</b> every operation targets a container, not a single game.
 * Callers must show all managed games sharing that container before mutating state.
 *
 * <p>All store mutations acquire {@link ContainerOperationLock#LOCK}.
 */
public final class RuntimeDependencyManager {
    private static final String TAG = "RuntimeDependency";
    private static final long MAX_INSTALLER_BYTES = 2L * 1024 * 1024 * 1024;
    public static final String AGM_BOOTSTRAP_KEY = "agm.default";

    private RuntimeDependencyManager() {}

    // ── Affected-game lookup ──────────────────────────────────────────────────

    /**
     * Returns all managed games whose container matches {@code containerId}.
     * Used to show the shared-implication warning in the UI before installing.
     */
    public static List<RuntimeDependencyFacade.AffectedGame> findAffectedGames(
            Context context, int containerId)
            throws JSONException, IOException {
        return RuntimeDependencyFacade.findAffectedGames(context, containerId);
    }

    // ── Installer path resolution ─────────────────────────────────────────────

    /**
     * Resolves a {@link Uri} returned by an Android file picker to an absolute file-system
     * path, using the same resolution strategy as the rest of the Winlator app.
     *
     * @return absolute path string, or {@code null} if the URI cannot be resolved to a path.
     */
    public static String resolveInstallerPath(Context context, Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            String path = uri.getPath();
            return (path != null && !path.isEmpty()) ? path : null;
        }
        return FileUtils.getFilePathFromUri(uri);
    }

    public static String importInstaller(
            Context context,
            Uri uri,
            int containerId,
            String dependencyId
    ) throws IOException, JSONException {
        RuntimeDependencyCatalog.Entry entry =
                RuntimeDependencyCatalog.getEntry(dependencyId);
        if (entry == null || !entry.isSupported()) {
            throw new IllegalArgumentException(
                    "The selected dependency does not accept an installer."
            );
        }
        String fileName = displayName(context, uri)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (fileName.isEmpty()) fileName = "installer.exe";
        File directory = new File(
                context.getFilesDir(),
                "dependency-installers/" + containerId + "/" + dependencyId
        );
        if (!directory.isDirectory() &&
                !directory.mkdirs() &&
                !directory.isDirectory()) {
            throw new IOException("Unable to create dependency installer storage.");
        }
        File destination = new File(
                directory,
                UUID.randomUUID() + "-" + fileName
        );
        File temporary = new File(destination.getPath() + ".tmp");
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("The selected installer could not be opened.");
        long total = 0;
        try (BufferedInputStream input = new BufferedInputStream(raw);
             BufferedOutputStream output =
                     new BufferedOutputStream(new FileOutputStream(temporary))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_INSTALLER_BYTES) {
                    throw new IOException("The selected installer exceeds 2 GiB.");
                }
                output.write(buffer, 0, read);
            }
        }
        catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Unable to finalize the dependency installer copy.");
        }
        setInstallerPath(context, containerId, dependencyId, destination.getPath());
        pruneOtherInstallers(directory, destination);
        return destination.getPath();
    }

    /**
     * Returns {@code true} if {@code path} is an absolute path to an existing regular file.
     * Does not validate file extension — executables may be named in various ways.
     */
    public static boolean isInstallerPathValid(String path) {
        if (path == null || path.isEmpty()) return false;
        File f = new File(path);
        return f.isAbsolute() && f.isFile();
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        }
        catch (RuntimeException error) {
            Log.w(TAG, "Unable to query the dependency installer name", error);
        }
        String segment = uri.getLastPathSegment();
        return segment != null ? segment : "installer.exe";
    }

    private static void pruneOtherInstallers(File directory, File keep) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.equals(keep) && !file.delete()) {
                Log.w(TAG, "Unable to remove old dependency installer " + file);
            }
        }
    }

    // ── Status / record accessors ─────────────────────────────────────────────

    /**
     * Returns the persisted install record, or an empty (NOT_INSTALLED) record if none exists.
     */
    public static DependencyInstallRecord getRecord(Context context,
            int containerId, String dependencyId)
            throws JSONException, IOException {
        return new RuntimeDependencyStore(context).getRecord(containerId, dependencyId);
    }

    /**
     * Returns records for all catalog entries on {@code containerId}.
     * Entries without persisted records appear as {@link DependencyInstallRecord#empty()}.
     */
    public static Map<String, DependencyInstallRecord> getContainerStatus(
            Context context, int containerId)
            throws JSONException, IOException {
        Map<String, DependencyInstallRecord> persisted =
                new RuntimeDependencyStore(context).getContainerRecords(containerId);
        for (RuntimeDependencyCatalog.Entry entry : RuntimeDependencyCatalog.allEntries()) {
            if (!persisted.containsKey(entry.id)) {
                persisted.put(entry.id, DependencyInstallRecord.empty());
            }
        }
        return persisted;
    }

    // ── Install lifecycle ─────────────────────────────────────────────────────

    /**
     * Stores the resolved installer path without changing status.
     * Call this when the user picks a file but before launching the installer.
     */
    public static void setInstallerPath(Context context, int containerId,
            String dependencyId, String installerPath)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            DependencyInstallRecord current = store.getRecord(containerId, dependencyId);
            store.putRecord(containerId, dependencyId,
                    current.withInstallerPath(installerPath));
        }
    }

    /**
     * Transitions the dependency to {@link DependencyStatus#INSTALLING} and appends a log
     * entry. Call this immediately before launching the Wine session with the installer.
     */
    public static void markInstalling(Context context, int containerId,
            String dependencyId, String installerPath)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            long now = System.currentTimeMillis();
            DependencyInstallRecord record = store.getRecord(containerId, dependencyId)
                    .withInstallerPath(installerPath)
                    .withStatus(DependencyStatus.INSTALLING, now)
                    .appendLog("Install launched via " + new File(installerPath).getName(), now);
            store.putRecord(containerId, dependencyId, record);
        }
    }

    /**
     * Records the outcome after the Wine session ends.
     */
    public static void markResult(Context context, int containerId,
            String dependencyId, boolean success, String message)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            long now = System.currentTimeMillis();
            DependencyStatus newStatus =
                    success ? DependencyStatus.INSTALLED : DependencyStatus.FAILED;
            String logMessage = (message != null && !message.isEmpty())
                    ? message
                    : (success ? "Installed successfully." : "Install failed.");
            DependencyInstallRecord record = store.getRecord(containerId, dependencyId)
                    .withStatus(newStatus, now)
                    .appendLog(logMessage, now);
            store.putRecord(containerId, dependencyId, record);
            updateBootstrapResult(context, store, containerId, dependencyId, success, logMessage);
        }
    }

    public static RuntimeDependencyBootstrapRecord inspectAgmBootstrap(Context context)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            int containerId = RuntimeDependencyFacade.ensureAgmDefaultContainer(context);
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            RuntimeDependencyBootstrapRecord record = store.getBootstrap(AGM_BOOTSTRAP_KEY);
            if (record == null
                    || record.planVersion != RuntimeDependencyCatalog.BOOTSTRAP_PLAN_VERSION
                    || record.containerId != containerId) {
                record = RuntimeDependencyBootstrapRecord.create(
                        RuntimeDependencyCatalog.BOOTSTRAP_PLAN_VERSION,
                        containerId
                );
                store.putBootstrap(AGM_BOOTSTRAP_KEY, record);
            }
            return record;
        }
    }

    public static RuntimeDependencyBootstrapRecord getAgmBootstrap(Context context)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            return new RuntimeDependencyStore(context).getBootstrap(AGM_BOOTSTRAP_KEY);
        }
    }

    public static RuntimeDependencyBootstrapRecord selectAgmBootstrapPackages(
            Context context,
            List<String> selectedIds
    ) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyBootstrapRecord current = inspectAgmBootstrap(context);
            restoreOriginalDrives(context, current);
            RuntimeDependencyBootstrapRecord selected = current.select(
                    RuntimeDependencyCatalog.expandBootstrapSelection(selectedIds)
            );
            new RuntimeDependencyStore(context).putBootstrap(AGM_BOOTSTRAP_KEY, selected);
            return selected;
        }
    }

    public static void markAgmBootstrapDownloadsReady(Context context)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            RuntimeDependencyBootstrapRecord record = requireBootstrap(store);
            store.putBootstrap(
                    AGM_BOOTSTRAP_KEY,
                    record.withState(RuntimeDependencyBootstrapState.READY_TO_INSTALL, null)
            );
        }
    }

    public static void failAgmBootstrap(Context context, String error)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            RuntimeDependencyBootstrapRecord record = requireBootstrap(store);
            restoreOriginalDrives(context, record);
            store.putBootstrap(AGM_BOOTSTRAP_KEY, record.fail(error));
        }
    }

    public static File getBootstrapPackageFile(
            Context context,
            RuntimeDependencyCatalog.BootstrapPackage entry
    ) {
        File root = new File(context.getFilesDir(), "prerequisites");
        if (entry.installMode == RuntimeDependencyCatalog.InstallMode.DIRECTX_SETUP) {
            return new File(
                    new File(root, "directx_june2010/directx-jun2010"),
                    entry.fileName
            );
        }
        return new File(new File(root, entry.id), entry.fileName);
    }

    public static void downloadBootstrapPackage(
            Context context,
            RuntimeDependencyCatalog.BootstrapPackage entry,
            HttpUtils.VerifiedDownloadProgress progress
    ) throws IOException {
        if (!entry.requiresDownload()) return;
        HttpUtils.downloadVerified(
                entry.url,
                getBootstrapPackageFile(context, entry),
                entry.sha256,
                entry.size,
                progress
        );
    }

    public static boolean isBootstrapPackageInstalled(
            Context context,
            int containerId,
            RuntimeDependencyCatalog.BootstrapPackage entry
    ) {
        try {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            Container container = new ContainerManager(context).getContainerById(containerId);
            return container != null && isBootstrapPackageInstalled(
                    store,
                    container,
                    entry
            );
        }
        catch (JSONException | IOException error) {
            Log.e(TAG, "Unable to inspect prerequisite package " + entry.id, error);
            return false;
        }
    }

    public static Map<String, Boolean> getAgmBootstrapPackageStates(Context context)
            throws JSONException, IOException {
        RuntimeDependencyBootstrapRecord record = inspectAgmBootstrap(context);
        RuntimeDependencyStore store = new RuntimeDependencyStore(context);
        Container container = new ContainerManager(context).getContainerById(record.containerId);
        if (container == null) throw new IOException("AGM shared container is missing.");
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        for (RuntimeDependencyCatalog.BootstrapPackage entry :
                RuntimeDependencyCatalog.bootstrapSelectablePackages()) {
            result.put(entry.id, isBootstrapPackageInstalled(store, container, entry));
        }
        return result;
    }

    public static List<String> missingAgmBootstrapPackages(Context context)
            throws JSONException, IOException {
        ArrayList<String> result = new ArrayList<>();
        Map<String, Boolean> states = getAgmBootstrapPackageStates(context);
        for (Map.Entry<String, Boolean> state : states.entrySet()) {
            if (!state.getValue()) result.add(state.getKey());
        }
        return result;
    }

    public static RuntimeDependencyCatalog.BootstrapPackage beginNextAgmBootstrapInstall(
            Context context
    ) throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            RuntimeDependencyBootstrapRecord record = requireBootstrap(store);
            String packageId = record.nextPackageId();
            if (packageId == null) {
                restoreOriginalDrives(context, record);
                store.putBootstrap(
                        AGM_BOOTSTRAP_KEY,
                        record.withState(RuntimeDependencyBootstrapState.COMPLETE, null)
                );
                return null;
            }
            RuntimeDependencyCatalog.BootstrapPackage entry =
                    RuntimeDependencyCatalog.getBootstrapPackage(packageId);
            if (entry == null) throw new IOException("Unknown prerequisite package: " + packageId);
            File installer = getBootstrapPackageFile(context, entry);
            if (entry.installMode == RuntimeDependencyCatalog.InstallMode.DIRECTX_EXTRACT) {
                File extractionDir = new File(installer.getParentFile(), "directx-jun2010");
                if (!extractionDir.isDirectory()
                        && !extractionDir.mkdirs()
                        && !extractionDir.isDirectory()) {
                    throw new IOException("Unable to create DirectX extraction directory.");
                }
            }
            if (!installer.isFile()) {
                throw new IOException("Prerequisite installer is missing: " + entry.fileName);
            }

            Container container = new ContainerManager(context).getContainerById(record.containerId);
            if (container == null) throw new IOException("AGM shared container is missing.");
            RuntimeDependencyBootstrapRecord installing =
                    record.startInstall(entry.id, container.getDrives());
            store.putBootstrap(AGM_BOOTSTRAP_KEY, installing);
            prepareContainerDrivesForInstaller(context, container, installer.getPath());
            markInstalling(context, record.containerId, entry.id, installer.getPath());
            return entry;
        }
    }

    public static RuntimeDependencyBootstrapRecord recoverInterruptedAgmBootstrap(Context context)
            throws JSONException, IOException {
        synchronized (ContainerOperationLock.LOCK) {
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            RuntimeDependencyBootstrapRecord record = store.getBootstrap(AGM_BOOTSTRAP_KEY);
            if (record != null
                    && ((record.state == RuntimeDependencyBootstrapState.INSTALLING
                    && !com.winlator.XServerDisplayActivity.isSessionActive())
                    || record.state == RuntimeDependencyBootstrapState.DOWNLOADING)) {
                restoreOriginalDrives(context, record);
                record = record.fail(
                        record.state == RuntimeDependencyBootstrapState.DOWNLOADING
                                ? "Prerequisite download was interrupted."
                                : "Prerequisite installation was interrupted."
                );
                store.putBootstrap(AGM_BOOTSTRAP_KEY, record);
            }
            return record;
        }
    }

    public static boolean isAgmBootstrapReady(Context context) {
        try {
            RuntimeDependencyBootstrapRecord record = getAgmBootstrap(context);
            if (record == null
                    || record.planVersion != RuntimeDependencyCatalog.BOOTSTRAP_PLAN_VERSION
                    || record.state != RuntimeDependencyBootstrapState.COMPLETE) {
                return false;
            }
            Container container = new ContainerManager(context).getContainerById(record.containerId);
            if (container == null) return false;
            RuntimeDependencyStore store = new RuntimeDependencyStore(context);
            for (String id : record.selectedPackageIds) {
                RuntimeDependencyCatalog.BootstrapPackage entry =
                        RuntimeDependencyCatalog.getBootstrapPackage(id);
                if (entry != null
                        && entry.installMode !=
                        RuntimeDependencyCatalog.InstallMode.DIRECTX_EXTRACT
                        && !isBootstrapPackageInstalled(store, container, entry)) {
                    return false;
                }
            }
            return true;
        }
        catch (JSONException | IOException error) {
            Log.e(TAG, "Unable to inspect prerequisite bootstrap", error);
            return false;
        }
    }

    private static void updateBootstrapResult(
            Context context,
            RuntimeDependencyStore store,
            int containerId,
            String dependencyId,
            boolean success,
            String message
    ) throws JSONException, IOException {
        RuntimeDependencyBootstrapRecord bootstrap = store.getBootstrap(AGM_BOOTSTRAP_KEY);
        if (bootstrap == null
                || bootstrap.containerId != containerId
                || bootstrap.currentPackageId == null
                || !bootstrap.currentPackageId.equals(dependencyId)) {
            return;
        }
        RuntimeDependencyCatalog.BootstrapPackage entry =
                RuntimeDependencyCatalog.getBootstrapPackage(dependencyId);
        if (!success || entry == null) {
            restoreOriginalDrives(context, bootstrap);
            store.putBootstrap(AGM_BOOTSTRAP_KEY, bootstrap.fail(message));
            return;
        }
        boolean probePassed;
        if (entry.installMode == RuntimeDependencyCatalog.InstallMode.DIRECTX_EXTRACT) {
            RuntimeDependencyCatalog.BootstrapPackage setup =
                    RuntimeDependencyCatalog.getBootstrapPackage("directx_june2010_setup");
            probePassed = setup != null && getBootstrapPackageFile(context, setup).isFile();
        }
        else {
            Container container = new ContainerManager(context).getContainerById(containerId);
            probePassed = container != null
                    && probeContainer(container, entry.probePaths);
        }
        if (!probePassed) {
            DependencyInstallRecord record = store.getRecord(containerId, dependencyId)
                    .withStatus(DependencyStatus.FAILED, System.currentTimeMillis())
                    .appendLog("Installation verification failed.", System.currentTimeMillis());
            store.putRecord(containerId, dependencyId, record);
            restoreOriginalDrives(context, bootstrap);
            store.putBootstrap(
                    AGM_BOOTSTRAP_KEY,
                    bootstrap.fail("Installation completed but verification failed.")
            );
            return;
        }
        RuntimeDependencyBootstrapRecord completed = bootstrap.completeCurrent();
        if (completed.state == RuntimeDependencyBootstrapState.COMPLETE) {
            restoreOriginalDrives(context, completed);
        }
        store.putBootstrap(AGM_BOOTSTRAP_KEY, completed);
    }

    private static RuntimeDependencyBootstrapRecord requireBootstrap(
            RuntimeDependencyStore store
    ) throws JSONException, IOException {
        RuntimeDependencyBootstrapRecord record = store.getBootstrap(AGM_BOOTSTRAP_KEY);
        if (record == null) throw new IOException("Prerequisite bootstrap is not initialized.");
        return record;
    }

    private static boolean isBootstrapPackageInstalled(
            RuntimeDependencyStore store,
            Container container,
            RuntimeDependencyCatalog.BootstrapPackage entry
    ) throws JSONException, IOException {
        RuntimeDependencyCatalog.BootstrapPackage probeEntry = entry;
        String recordId = entry.id;
        if (entry.installMode == RuntimeDependencyCatalog.InstallMode.DIRECTX_EXTRACT) {
            recordId = "directx_june2010_setup";
            probeEntry = RuntimeDependencyCatalog.getBootstrapPackage(recordId);
        }
        if (probeEntry == null) return false;
        DependencyInstallRecord record = store.getRecord(container.id, recordId);
        return record.status == DependencyStatus.INSTALLED
                && probeContainer(container, probeEntry.probePaths);
    }

    private static boolean probeContainer(Container container, String[] relativePaths) {
        File windowsDir = new File(container.getRootDir(), ".wine/drive_c/windows");
        for (String path : relativePaths) {
            File file = new File(windowsDir, path);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    private static void restoreOriginalDrives(
            Context context,
            RuntimeDependencyBootstrapRecord record
    ) throws IOException {
        if (record.originalDrives == null) return;
        Container container = new ContainerManager(context).getContainerById(record.containerId);
        if (container == null) throw new IOException("AGM shared container is missing.");
        container.setDrives(record.originalDrives);
        if (!container.saveData()) throw new IOException("Unable to restore container drives.");
    }

    // ── Container drive preparation ───────────────────────────────────────────

    /**
     * Updates the container's drives so the installer's parent directory is drive D.
     * Must be called with {@link ContainerOperationLock#LOCK} held.
     */
    public static void prepareContainerDrivesForInstaller(
            Context context, Container container, String installerPath)
            throws IOException {
        String installerDir = new File(installerPath).getParent();
        if (installerDir == null) {
            throw new IOException("Installer path has no parent directory: " + installerPath);
        }
        String internalStorage = AppUtils.getInternalStorage(context);
        container.setDrives("D:" + installerDir + "E:" + internalStorage);
        if (!container.saveData()) {
            throw new IOException("Unable to save container configuration.");
        }
    }
}
