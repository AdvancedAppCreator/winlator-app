package com.winlator.api;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.WineUtils;

import java.io.File;
import java.io.IOException;

/**
 * Resolves the game-root directory for modding given a {@link ManagedGame}.
 *
 * <h3>Resolution strategy</h3>
 * <ol>
 *   <li><b>Portable root</b>: if {@code game.gamePath != null} that directory
 *       is used directly (no Wine container involved).</li>
 *   <li><b>Wine-installed root</b>: if {@code game.executableDosPath != null}
 *       a fresh {@link ContainerManager} resolves the DOS path to a Unix path
 *       via {@link WineUtils#dosToUnixPath}, and the <em>parent directory</em>
 *       of that path is returned as the game root.</li>
 * </ol>
 *
 * <h3>Rejection rules (Wine-installed path only)</h3>
 * <ul>
 *   <li>Z: drive – always rejected (maps to Linux root; unsafe).</li>
 *   <li>Shared container – rejected to prevent cross-game corruption
 *       (portable roots remain allowed regardless of container policy).</li>
 * </ul>
 */
final class GameModRootResolver {

    private GameModRootResolver() {}

    /**
     * Resolves the mod-target root directory for {@code game}.
     *
     * @throws IOException if the game cannot be modded.
     */
    static File resolve(Context context, ManagedGame game) throws IOException {
        if (game.gamePath != null && !game.gamePath.isEmpty()) {
            File root = new File(game.gamePath);
            if (!root.isDirectory()) {
                throw new IOException(
                        "Portable game path does not exist or is not a directory: "
                                + game.gamePath);
            }
            return root;
        }

        if (game.executableDosPath != null && !game.executableDosPath.isEmpty()) {
            if (ModPaths.isZDrive(game.executableDosPath)) {
                throw new IOException(
                        "Modding is not supported for executables on the Z: drive "
                                + "(host filesystem).");
            }
            if (ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(game.containerPolicy)) {
                throw new IOException(
                        "Modding Wine-installed games in a shared container is not "
                                + "supported; move the game to an isolated container first.");
            }

            ContainerManager manager = new ContainerManager(context);
            Container container = manager.getContainerById(game.containerId);
            if (container == null) {
                throw new IOException(
                        "Container " + game.containerId + " not found for game "
                                + game.id + ".");
            }
            String unixPath = WineUtils.dosToUnixPath(game.executableDosPath, container);
            if (unixPath.isEmpty()) {
                throw new IOException(
                        "Cannot resolve DOS path: " + game.executableDosPath);
            }
            File exeFile = new File(unixPath);
            File root = exeFile.getParentFile();
            if (root == null || !root.isDirectory()) {
                throw new IOException(
                        "Resolved game root does not exist or is not a directory: "
                                + (root != null ? root.getAbsolutePath() : "null"));
            }
            return root;
        }

        throw new IOException(
                "Game " + game.id + " has no game path or executable DOS path configured.");
    }
}
