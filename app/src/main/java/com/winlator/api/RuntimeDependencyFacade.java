package com.winlator.api;

import android.content.Context;
import android.content.Intent;

import com.winlator.container.Container;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public façade that exposes package-internal managed-game data needed by the
 * runtime dependency manager without widening the visibility of {@link ManagedGame}
 * or {@link ManagedGameStore}.
 */
public final class RuntimeDependencyFacade {

    /** Lightweight public projection of a managed game used by the dependency manager. */
    public static final class AffectedGame {
        public final String id;
        public final String title;
        public final int containerId;

        AffectedGame(ManagedGame g) {
            this.id = g.id;
            this.title = g.title;
            this.containerId = g.containerId;
        }
    }

    private RuntimeDependencyFacade() {}

    /**
     * Returns all managed games whose {@code containerId} matches the given value.
     * Used to populate the shared-container warning in the dependency manager UI.
     */
    public static List<AffectedGame> findAffectedGames(Context context, int containerId)
            throws JSONException, IOException {
        List<ManagedGame> all = new ManagedGameStore(context).list();
        List<AffectedGame> result = new ArrayList<>();
        for (ManagedGame g : all) {
            if (g.containerId == containerId) result.add(new AffectedGame(g));
        }
        Collections.sort(result, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return result;
    }

    public static int ensureAgmDefaultContainer(Context context)
            throws JSONException, IOException {
        Container container = ManagedContainerRegistry.ensureAgmDefaultContainer(context);
        return container.id;
    }

    public static Intent configureInstallerIntent(
            Context context,
            Intent intent,
            int containerId,
            String gameId,
            String dependencyId
    ) throws JSONException, IOException {
        if (gameId != null && !gameId.isEmpty()) {
            ManagedGame game = new ManagedGameStore(context).get(gameId);
            if (game == null || game.containerId != containerId) {
                throw new IllegalArgumentException(
                        "The selected game does not use this dependency container."
                );
            }
            GameApiJson.applyLaunchConfig(context, intent, game);
        }
        return GameSessionEventReporter.configureDependency(
                intent,
                containerId,
                gameId,
                dependencyId
        );
    }
}
