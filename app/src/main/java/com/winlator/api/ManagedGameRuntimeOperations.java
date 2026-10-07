package com.winlator.api;

import android.content.Context;
import android.content.Intent;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.ContainerOperationLock;
import com.winlator.core.AppUtils;
import com.winlator.core.WineUtils;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

final class ManagedGameRuntimeOperations {
    private ManagedGameRuntimeOperations() {
    }

    static ManagedGame applyConfig(
            Context context,
            String gameId,
            String baseConfigSha256,
            JSONObject set
    ) throws JSONException, IOException {
        GameConfigSchema.validateLegacyPatch(context, set);
        synchronized (ContainerOperationLock.LOCK) {
            ManagedGameStore store = new ManagedGameStore(context);
            ManagedGame game = store.get(gameId);
            if (game == null) throw new IllegalArgumentException("The managed game was not found.");
            Container container = new ContainerManager(context)
                    .getContainerById(game.containerId);
            if (container == null) {
                throw new IllegalArgumentException("The managed game container was not found.");
            }

            JSONObject current = GameApiJson.managedConfig(game, container);
            if (!GameConfigSchema.hash(current).equals(baseConfigSha256)) {
                throw new IllegalArgumentException(
                        "The game configuration changed. Refresh recovery status."
                );
            }
            for (Iterator<String> keys = set.keys(); keys.hasNext(); ) {
                String key = keys.next();
                current.put(key, set.get(key));
            }
            GameApiJson.applyContainerConfig(container, current);
            if (set.has("winVersion")) {
                game.winVersionSource = ManagedGame.WIN_VERSION_SOURCE_EXPLICIT;
            }
            game.configJson = current.toString();
            game.updatedAt = System.currentTimeMillis();
            if (!container.saveData()) {
                throw new IOException("Unable to persist the recovered container configuration.");
            }
            store.put(game);
            return game;
        }
    }

    static JSONObject currentConfig(Context context, ManagedGame game) throws JSONException {
        Container container = new ContainerManager(context).getContainerById(game.containerId);
        if (container == null) {
            throw new IllegalArgumentException("The managed game container was not found.");
        }
        return GameApiJson.managedConfig(game, container);
    }

    static Intent prepareLaunch(Context context, ManagedGame game)
            throws JSONException, IOException {
        if (!RootFS.find(context).isValid()) {
            throw new IllegalStateException("Winlator system files are not ready.");
        }
        String token = GameManagerActivity.reserveInternalLaunch();
        if (token == null) {
            throw new IllegalStateException("Close the currently running Winlator session first.");
        }
        try {
            Container container = new ContainerManager(context)
                    .getContainerById(game.containerId);
            if (container == null) {
                throw new IllegalArgumentException("The managed game container was not found.");
            }
            synchronized (ContainerOperationLock.LOCK) {
                JSONObject config = GameApiJson.managedConfig(game, container);
                GameApiJson.applyContainerConfig(container, config);
                container.setDrives(buildDrives(context, game));
                if (!container.saveData()) {
                    throw new IOException("Unable to prepare the game container.");
                }
            }
            if (!isExecutableAccessible(game, container)) {
                throw new IllegalArgumentException(
                        "The configured game executable is not accessible."
                );
            }
            JSONObject config = GameApiJson.managedConfig(game, container);
            File overlaidExecutable =
                    UnityLaunchOverlay.prepare(context, game, container, config);
            Intent launch = new Intent(context, XServerDisplayActivity.class);
            launch.putExtra("container_id", game.containerId);
            launch.putExtra(GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN, token);
            launch.putExtra(GameApiContract.EXTRA_GAME_ID, game.id);
            applyExecutableExtras(launch, game, overlaidExecutable);
            if (game.arguments != null && !game.arguments.isEmpty()) {
                launch.putExtra("exec_args", game.arguments);
            }
            GameApiJson.applyLaunchConfig(context, launch, game);
            return GameSessionEventReporter.configure(
                    launch,
                    game,
                    false,
                    game.state
            );
        }
        catch (RuntimeException | JSONException | IOException error) {
            GameManagerActivity.releaseInternalLaunch(token);
            throw error;
        }
    }

    static void startPreparedLaunch(Context context, Intent launch) {
        String token = launch.getStringExtra(
                GameApiContract.INTERNAL_EXTRA_SESSION_TOKEN
        );
        try {
            context.startActivity(launch);
        }
        catch (RuntimeException error) {
            GameManagerActivity.releaseInternalLaunch(token);
            throw error;
        }
    }

    static void applyExecutableExtras(
            Intent launch,
            ManagedGame game,
            File overlaidExecutable
    ) {
        if (overlaidExecutable != null) {
            launch.putExtra("exec_path", overlaidExecutable.getAbsolutePath());
            return;
        }
        if (game.executableDosPath != null) {
            launch.putExtra("exec_dos_path", game.executableDosPath);
        }
        else if (game.executablePath != null) {
            launch.putExtra("exec_path", game.executablePath);
        }
    }

    private static boolean isExecutableAccessible(
            ManagedGame game,
            Container container
    ) {
        if (game.executablePath != null) {
            return new File(game.executablePath).isFile() &&
                    WineUtils.unixToDOSPath(game.executablePath, container)
                            .matches("(?i)^[a-z]:.*");
        }
        if (game.executableDosPath == null) return false;
        String path = WineUtils.dosToUnixPath(game.executableDosPath, container);
        return !path.isEmpty() && new File(path).isFile();
    }

    private static String buildDrives(Context context, ManagedGame game) {
        String root = game.gamePath;
        if (root == null && game.executablePath != null) {
            root = new File(game.executablePath).getParent();
        }
        if (root == null) root = AppUtils.DIRECTORY_DOWNLOADS;
        return "D:" + root + "E:" + AppUtils.getInternalStorage(context);
    }
}
