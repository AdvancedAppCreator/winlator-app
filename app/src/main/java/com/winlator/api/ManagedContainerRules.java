package com.winlator.api;

import org.json.JSONException;

import java.io.IOException;

final class ManagedContainerRules {
    interface GameWriter {
        void write(ManagedGame game) throws JSONException, IOException;
    }

    private ManagedContainerRules() {
    }

    static boolean shouldDeleteContainer(
            ManagedGame game,
            ManagedGameStore.ContainerUsage usage
    ) {
        return ManagedGame.CONTAINER_POLICY_ISOLATED.equals(game.containerPolicy) &&
                !usage.shared &&
                usage.referenceCount <= 1;
    }

    static void reassignToIsolated(
            ManagedGame game,
            int containerId,
            long updatedAt,
            GameWriter writer
    ) throws JSONException, IOException {
        int previousContainerId = game.containerId;
        String previousPolicy = game.containerPolicy;
        String previousKey = game.containerKey;
        long previousUpdatedAt = game.updatedAt;
        try {
            game.containerId = containerId;
            game.containerPolicy = ManagedGame.CONTAINER_POLICY_ISOLATED;
            game.containerKey = null;
            game.updatedAt = updatedAt;
            writer.write(game);
        }
        catch (JSONException | IOException e) {
            game.containerId = previousContainerId;
            game.containerPolicy = previousPolicy;
            game.containerKey = previousKey;
            game.updatedAt = previousUpdatedAt;
            throw e;
        }
    }
}
