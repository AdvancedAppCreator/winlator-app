package com.winlator.container;

import android.content.Context;
import android.os.Handler;

import com.winlator.R;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.RuntimeAssetManifest;
import com.winlator.core.RuntimeAssetProvisioner;
import com.winlator.core.WineInfo;
import com.winlator.core.WineUtils;
import com.winlator.win32.WinVersions;
import com.winlator.xenvironment.RootFS;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;

public class ContainerManager {
    private static final long CLONE_FREE_SPACE_RESERVE_BYTES = 16L * 1024L * 1024L;
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = RootFS.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        synchronized (ContainerOperationLock.LOCK) {
            loadContainers();
        }
    }

    public Context getContext() {
        return context;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        try {
            File[] files = homeDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && file.getName().startsWith(".agm-clone-")) {
                        FileUtils.delete(file);
                        continue;
                    }
                    if (file.isDirectory()) {
                        if (file.getName().startsWith(RootFS.USER+"-")) {
                            Container container = new Container(Integer.parseInt(file.getName().replace(RootFS.USER+"-", "")));
                            container.setRootDir(new File(homeDir, RootFS.USER+"-"+container.id));
                            JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                            container.loadData(data);
                            containers.add(container);
                            maxContainerId = Math.max(maxContainerId, container.id);
                        }
                    }
                }
            }
        }
        catch (JSONException e) {}
    }

    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, RootFS.USER+"-"+container.id));
        File file = new File(homeDir, RootFS.USER);
        file.delete();
        FileUtils.symlink(RootFS.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(data);
            handler.post(() -> callback.call(container));
        });
    }

    public void duplicateContainerAsync(
            Container container,
            Callback<CloneResult> callback
    ) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            CloneResult result = cloneContainer(
                    container,
                    container.getName()+" ("+context.getString(R.string.copy)+")"
            );
            handler.post(() -> callback.call(result));
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    public Container createContainer(JSONObject data) {
        synchronized (ContainerOperationLock.LOCK) {
            loadContainers();
            File containerDir = null;
            try {
                int id = maxContainerId + 1;
                data.put("id", id);

                containerDir = new File(homeDir, RootFS.USER+"-"+id);
                if (!containerDir.mkdirs()) return null;

                Container container = new Container(id);
                container.setRootDir(containerDir);
                container.loadData(data);

                boolean isMainWineVersion = !data.has("wineVersion") ||
                        WineInfo.isMainWineVersion(data.getString("wineVersion"));
                if (!isMainWineVersion) container.setWineVersion(data.getString("wineVersion"));

                if (!extractContainerPatternFile(container.getWineVersion(), containerDir)) {
                    FileUtils.delete(containerDir);
                    return null;
                }
                WineUtils.setWinVersion(
                        container,
                        data.optString("winVersion", WinVersions.DEFAULT_VERSION)
                );

                if (!container.saveData()) {
                    FileUtils.delete(containerDir);
                    return null;
                }
                maxContainerId++;
                containers.add(container);
                return container;
            }
            catch (JSONException | IllegalArgumentException e) {
                if (containerDir != null) FileUtils.delete(containerDir);
                return null;
            }
        }
    }

    public CloneResult cloneContainer(Container source, String name) {
        synchronized (ContainerOperationLock.LOCK) {
            loadContainers();
            Container srcContainer = getContainerById(source.id);
            if (srcContainer == null) return CloneResult.sourceMissing();

            long allocatedSize = FileUtils.getAllocatedSize(srcContainer.getRootDir());
            if (allocatedSize < 0) return CloneResult.sizeUnavailable();
            long availableBytes = FileUtils.getAvailableBytes(homeDir);
            long requiredBytes;
            try {
                requiredBytes = Math.addExact(allocatedSize, CLONE_FREE_SPACE_RESERVE_BYTES);
            }
            catch (ArithmeticException e) {
                return CloneResult.insufficientStorage(allocatedSize, availableBytes);
            }
            if (availableBytes < 0 || availableBytes < requiredBytes) {
                return CloneResult.insufficientStorage(requiredBytes, availableBytes);
            }

            int id = maxContainerId + 1;
            File stageDir = new File(homeDir, ".agm-clone-"+id+"-"+UUID.randomUUID());
            File dstDir = new File(homeDir, RootFS.USER+"-"+id);
            if (!stageDir.mkdirs()) return CloneResult.copyFailed();

            if (!FileUtils.copyPreservingSymlinks(
                    srcContainer.getRootDir(),
                    stageDir,
                    file -> FileUtils.chmod(file, 0771)
            )) {
                FileUtils.delete(stageDir);
                return CloneResult.copyFailed();
            }

            Container dstContainer;
            try {
                dstContainer = copyContainerData(srcContainer, id, stageDir, name);
            }
            catch (JSONException e) {
                FileUtils.delete(stageDir);
                return CloneResult.copyFailed();
            }
            if (!dstContainer.saveData()) {
                FileUtils.delete(stageDir);
                return CloneResult.commitFailed();
            }
            if (dstDir.exists() || !stageDir.renameTo(dstDir)) {
                FileUtils.delete(stageDir);
                return CloneResult.commitFailed();
            }

            dstContainer.setRootDir(dstDir);
            maxContainerId = id;
            containers.add(dstContainer);
            return CloneResult.success(dstContainer, allocatedSize, availableBytes);
        }
    }

    public boolean removeContainer(Container container) {
        synchronized (ContainerOperationLock.LOCK) {
            loadContainers();
            Container current = getContainerById(container.id);
            if (current == null) return true;
            if (!FileUtils.delete(current.getRootDir())) return false;
            containers.remove(current);
            return true;
        }
    }

    public long getContainerAllocatedSize(Container container) {
        synchronized (ContainerOperationLock.LOCK) {
            return FileUtils.getAllocatedSize(container.getRootDir());
        }
    }

    private Container copyContainerData(
            Container source,
            int id,
            File rootDir,
            String name
    ) throws JSONException {
        Container destination = new Container(id);
        destination.setRootDir(rootDir);
        destination.setName(name);
        destination.setScreenSize(source.getScreenSize());
        destination.setEnvVars(source.getEnvVars());
        destination.setCPUList(source.getCPUList());
        destination.setCPUListWoW64(source.getCPUListWoW64());
        destination.setGraphicsDriver(source.getGraphicsDriver());
        destination.setGraphicsDriverConfig(source.getGraphicsDriverConfig());
        destination.setDXWrapper(source.getDXWrapper());
        destination.setDXWrapperConfig(source.getDXWrapperConfig());
        destination.setAudioDriver(source.getAudioDriver());
        destination.setAudioDriverConfig(source.getAudioDriverConfig());
        destination.setWinComponents(source.getWinComponents());
        destination.setDrives(source.getDrives());
        destination.setHUDMode(source.getHUDMode());
        destination.setStartupSelection(source.getStartupSelection());
        destination.setBox64Preset(source.getBox64Preset());
        destination.setDesktopTheme(source.getDesktopTheme());
        destination.setWineVersion(source.getWineVersion());
        destination.setExtraData(source.copyExtraData());
        return destination;
    }

    public ArrayList<Shortcut> loadShortcuts(Shortcut selectedFolder) {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();

        if (selectedFolder != null) {
            File[] files = selectedFolder.file.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".desktop") || file.isDirectory()) {
                        shortcuts.add(new Shortcut(selectedFolder.container, file));
                    }
                }
            }
        }
        else {
            for (Container container : containers) {
                File desktopDir = new File(container.getUserDir(), "Desktop");
                File[] files = desktopDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".desktop") || file.isDirectory()) {
                            shortcuts.add(new Shortcut(container, file));
                        }
                    }
                }
            }
        }

        shortcuts.sort((a, b) -> {
            int value = Boolean.compare(b.file.isDirectory(), a.file.isDirectory());
            if (value == 0) value = a.name.compareTo(b.name);
            return value;
        });
        return shortcuts;
    }

    public ArrayList<FileInfo> loadFiles(Container container, FileInfo parent) {
        ArrayList<FileInfo> fileInfos = new ArrayList<>();

        if (parent != null) {
            fileInfos = parent.list();
        }
        else {
            String rootPath = container.getRootDir().getPath();
            fileInfos.add(new FileInfo(container, "C:", rootPath+"/.wine/drive_c", FileInfo.Type.DRIVE));
            for (Drive drive : container.drivesIterator()) {
                fileInfos.add(new FileInfo(container, drive.letter+":", drive.path, FileInfo.Type.DRIVE));
            }

            File userDir = container.getUserDir();
            File documentsDir = new File(userDir, "Documents");
            File favoritesDir = new File(userDir, "Favorites");

            fileInfos.add(new FileInfo(container, documentsDir.getName(), documentsDir.getPath(), FileInfo.Type.DIRECTORY));
            fileInfos.add(new FileInfo(container, favoritesDir.getName(), favoritesDir.getPath(), FileInfo.Type.DIRECTORY));

            Collections.sort(fileInfos);
        }
        return fileInfos;
    }

    public int getNextContainerId() {
        return maxContainerId + 1;
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    public static final class CloneResult {
        public final Container container;
        public final String error;
        public final long requiredBytes;
        public final long availableBytes;

        private CloneResult(
                Container container,
                String error,
                long requiredBytes,
                long availableBytes
        ) {
            this.container = container;
            this.error = error;
            this.requiredBytes = requiredBytes;
            this.availableBytes = availableBytes;
        }

        private static CloneResult success(
                Container container,
                long requiredBytes,
                long availableBytes
        ) {
            return new CloneResult(container, null, requiredBytes, availableBytes);
        }

        private static CloneResult sourceMissing() {
            return new CloneResult(null, "source_missing", -1, -1);
        }

        private static CloneResult sizeUnavailable() {
            return new CloneResult(null, "size_unavailable", -1, -1);
        }

        private static CloneResult insufficientStorage(long requiredBytes, long availableBytes) {
            return new CloneResult(
                    null,
                    "insufficient_storage",
                    requiredBytes,
                    availableBytes
            );
        }

        private static CloneResult copyFailed() {
            return new CloneResult(null, "copy_failed", -1, -1);
        }

        private static CloneResult commitFailed() {
            return new CloneResult(null, "commit_failed", -1, -1);
        }
    }

    private void copyCommonDlls(String srcName, String dstName, JSONObject commonDlls, File containerDir) throws JSONException {
        File srcDir = new File(RootFS.find(context).getRootDir(), "/opt/wine/lib/wine/"+srcName);
        JSONArray dlnames = commonDlls.getJSONArray(dstName);

        for (int i = 0; i < dlnames.length(); i++) {
            String dlname = dlnames.getString(i);
            File dstFile = new File(containerDir, ".wine/drive_c/windows/"+dstName+"/"+dlname);
            FileUtils.copy(new File(srcDir, dlname), dstFile);
        }
    }

    private boolean extractContainerPatternFile(String wineVersion, File containerDir) {
        if (WineInfo.isMainWineVersion(wineVersion)) {
            boolean result = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    RuntimeAssetProvisioner.getAsset(
                            context,
                            RuntimeAssetManifest.CONTAINER_PATTERN
                    ),
                    containerDir
            );

            if (result) {
                try {
                    JSONObject commonDlls = new JSONObject(FileUtils.readString(context, "common_dlls.json"));
                    copyCommonDlls("x86_64-windows", "system32", commonDlls, containerDir);
                    copyCommonDlls("i386-windows", "syswow64", commonDlls, containerDir);
                }
                catch (JSONException e) {
                    return false;
                }
            }

            return result;
        }
        else {
            File installedWineDir = RootFS.find(context).getInstalledWineDir();
            WineInfo wineInfo = WineInfo.fromIdentifier(context, wineVersion);
            File file = new File(installedWineDir, "container-pattern-"+wineInfo.fullVersion()+".tzst");
            return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, file, containerDir);
        }
    }
}
