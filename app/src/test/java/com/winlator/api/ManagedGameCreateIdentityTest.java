package com.winlator.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class ManagedGameCreateIdentityTest {
    @Test
    public void portableIdentityMatchesCanonicalPathsAndSharedDefaultKey() throws Exception {
        File root = new File("build/identity/game").getAbsoluteFile();
        ManagedGame game = portable(
                new File(root, ".").getPath(),
                new File(root, "bin/../Game.exe").getPath(),
                ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT,
                null
        );

        assertTrue(game.matchesCreateIdentity(
                root.getCanonicalPath(),
                new File(root, "Game.exe").getCanonicalPath(),
                null,
                null,
                ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT,
                ManagedGame.DEFAULT_SHARED_CONTAINER_KEY
        ));
    }

    @Test
    public void legacyPortableIdentityCanBeReconciled() throws Exception {
        ManagedGame game = portable(
                "/storage/emulated/0/Games/Test",
                "/storage/emulated/0/Games/Test/Game.exe",
                ManagedGame.CONTAINER_POLICY_ISOLATED,
                null
        );
        game.creationMode = null;

        assertTrue(game.matchesCreateIdentity(
                game.gamePath,
                game.executablePath,
                null,
                null,
                ManagedGame.CONTAINER_POLICY_ISOLATED,
                null
        ));
    }

    @Test
    public void mutableFieldsDoNotChangeIdentity() throws Exception {
        ManagedGame game = portable(
                "/storage/emulated/0/Games/Test",
                "/storage/emulated/0/Games/Test/Game.exe",
                ManagedGame.CONTAINER_POLICY_ISOLATED,
                null
        );
        game.title = "Renamed";
        game.arguments = "--changed";
        game.agmMetadata = "{\"changed\":true}";

        assertTrue(game.matchesCreateIdentity(
                game.gamePath,
                game.executablePath,
                null,
                null,
                game.containerPolicy,
                null
        ));
    }

    @Test
    public void portableIdentityRejectsDifferentPathOrPolicy() throws Exception {
        ManagedGame game = portable(
                "/storage/emulated/0/Games/Test",
                "/storage/emulated/0/Games/Test/Game.exe",
                ManagedGame.CONTAINER_POLICY_ISOLATED,
                null
        );

        assertFalse(game.matchesCreateIdentity(
                game.gamePath,
                "/storage/emulated/0/Games/Other/Game.exe",
                null,
                null,
                game.containerPolicy,
                null
        ));
        assertFalse(game.matchesCreateIdentity(
                game.gamePath,
                game.executablePath,
                null,
                null,
                ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT,
                ManagedGame.DEFAULT_SHARED_CONTAINER_KEY
        ));
    }

    @Test
    public void installerIdentityRequiresPersistedMatchingInstaller() throws Exception {
        ManagedGame game = new ManagedGame();
        game.containerPolicy = ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT;
        game.containerKey = ManagedGame.DEFAULT_SHARED_CONTAINER_KEY;
        game.executableDosPath = "C:\\Program Files\\Game\\Game.exe";
        game.setCreationIdentity("/storage/emulated/0/Download/setup.exe");

        assertTrue(game.matchesCreateIdentity(
                null,
                null,
                "c:/program files/game/game.exe",
                "/storage/emulated/0/Download/./setup.exe",
                ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT,
                ManagedGame.DEFAULT_SHARED_CONTAINER_KEY
        ));
        assertFalse(game.matchesCreateIdentity(
                null,
                null,
                game.executableDosPath,
                "/storage/emulated/0/Download/other.exe",
                game.containerPolicy,
                game.containerKey
        ));

        game.creationIdentitySha256 = null;
        assertFalse(game.matchesCreateIdentity(
                null,
                null,
                game.executableDosPath,
                "/storage/emulated/0/Download/setup.exe",
                game.containerPolicy,
                game.containerKey
        ));
    }

    @Test
    public void persistedIdentitySurvivesExecutableReconfiguration() throws Exception {
        ManagedGame game = portable(
                "/storage/emulated/0/Games/Test",
                "/storage/emulated/0/Games/Test/Game.exe",
                ManagedGame.CONTAINER_POLICY_ISOLATED,
                null
        );
        String originalExecutable = game.executablePath;
        game.executablePath = "/storage/emulated/0/Games/Test/Alternate.exe";

        assertTrue(game.matchesCreateIdentity(
                game.gamePath,
                originalExecutable,
                null,
                null,
                game.containerPolicy,
                game.containerKey
        ));
        assertFalse(game.matchesCreateIdentity(
                game.gamePath,
                game.executablePath,
                null,
                null,
                game.containerPolicy,
                game.containerKey
        ));
    }

    private ManagedGame portable(
            String gamePath,
            String executablePath,
            String policy,
            String key
    ) throws Exception {
        ManagedGame game = new ManagedGame();
        game.gamePath = gamePath;
        game.executablePath = executablePath;
        game.containerPolicy = policy;
        game.containerKey = key;
        game.setCreationIdentity(null);
        return game;
    }
}
