package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;

public class ManagedContainerRulesTest {
    @Test
    public void sharedContainersAreNeverAutomaticallyDeleted() {
        ManagedGame game = game(4, ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT);
        ManagedGameStore.ContainerUsage finalReference = usage(1, true);
        ManagedGameStore.ContainerUsage noReferences = usage(0, true);

        assertFalse(ManagedContainerRules.shouldDeleteContainer(game, finalReference));
        assertFalse(ManagedContainerRules.shouldDeleteContainer(game, noReferences));
    }

    @Test
    public void isolatedContainerDeletesOnlyAfterFinalReference() {
        ManagedGame game = game(4, ManagedGame.CONTAINER_POLICY_ISOLATED);

        assertFalse(ManagedContainerRules.shouldDeleteContainer(game, usage(2, false)));
        assertTrue(ManagedContainerRules.shouldDeleteContainer(game, usage(1, false)));
    }

    @Test
    public void reassignmentChangesOnlySelectedGame() throws Exception {
        ManagedGame selected = game(4, ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT);
        selected.containerKey = ManagedGame.DEFAULT_SHARED_CONTAINER_KEY;
        ManagedGame other = game(4, ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT);

        ManagedContainerRules.reassignToIsolated(selected, 9, 200, ignored -> {
        });

        assertEquals(9, selected.containerId);
        assertEquals(ManagedGame.CONTAINER_POLICY_ISOLATED, selected.containerPolicy);
        assertNull(selected.containerKey);
        assertEquals(4, other.containerId);
        assertEquals(ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT, other.containerPolicy);
    }

    @Test
    public void reassignmentFailureRollsBackEveryField() {
        ManagedGame game = game(4, ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT);
        game.containerKey = ManagedGame.DEFAULT_SHARED_CONTAINER_KEY;
        game.updatedAt = 100;

        try {
            ManagedContainerRules.reassignToIsolated(game, 9, 200, ignored -> {
                throw new IOException("forced failure");
            });
            fail("Expected reassignment failure");
        }
        catch (JSONException e) {
            fail(e.getMessage());
        }
        catch (IOException expected) {
            assertEquals("forced failure", expected.getMessage());
        }

        assertEquals(4, game.containerId);
        assertEquals(
                ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT,
                game.containerPolicy
        );
        assertEquals(ManagedGame.DEFAULT_SHARED_CONTAINER_KEY, game.containerKey);
        assertEquals(100, game.updatedAt);
    }

    private ManagedGame game(int containerId, String policy) {
        ManagedGame game = new ManagedGame();
        game.id = "game-"+containerId;
        game.title = "Game";
        game.containerId = containerId;
        game.containerPolicy = policy;
        return game;
    }

    private ManagedGameStore.ContainerUsage usage(int references, boolean shared) {
        return new ManagedGameStore.ContainerUsage(
                references,
                shared,
                shared ? ManagedGame.DEFAULT_SHARED_CONTAINER_KEY : null,
                new ArrayList<>()
        );
    }
}
