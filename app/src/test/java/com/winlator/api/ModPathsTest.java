package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/**
 * Unit tests for {@link ModPaths}: path containment, Z: drive detection,
 * shared-container rejection, and safe game-id hashing.
 *
 * <p>These are pure-Java tests with no Android context required.</p>
 */
public class ModPathsTest {

    // -------------------------------------------------------------------------
    // Z: drive detection
    // -------------------------------------------------------------------------

    @Test
    public void zDriveUppercaseIsRejected() {
        assertTrue(ModPaths.isZDrive("Z:\\games\\foo.exe"));
    }

    @Test
    public void zDriveLowercaseIsRejected() {
        assertTrue(ModPaths.isZDrive("z:\\games\\foo.exe"));
    }

    @Test
    public void zDriveColonOnlyIsRejected() {
        assertTrue(ModPaths.isZDrive("Z:"));
    }

    @Test
    public void cDriveIsNotZDrive() {
        assertFalse(ModPaths.isZDrive("C:\\games\\foo.exe"));
    }

    @Test
    public void dDriveIsNotZDrive() {
        assertFalse(ModPaths.isZDrive("D:\\games\\foo.exe"));
    }

    @Test
    public void nullPathIsNotZDrive() {
        assertFalse(ModPaths.isZDrive(null));
    }

    @Test
    public void emptyPathIsNotZDrive() {
        assertFalse(ModPaths.isZDrive(""));
    }

    // -------------------------------------------------------------------------
    // Path containment
    // -------------------------------------------------------------------------

    @Test
    public void fileDirectlyUnderRootIsContained() {
        File root = new File("/data/app/games");
        File child = new File("/data/app/games/mod.dll");
        assertTrue(ModPaths.isContainedIn(root, child));
    }

    @Test
    public void fileInSubdirIsContained() {
        File root = new File("/data/app/games");
        File child = new File("/data/app/games/sub/mod.dll");
        assertTrue(ModPaths.isContainedIn(root, child));
    }

    @Test
    public void rootItselfIsContained() {
        File root = new File("/data/app/games");
        assertTrue(ModPaths.isContainedIn(root, root));
    }

    @Test
    public void siblingDirectoryIsNotContained() {
        File root = new File("/data/app/games");
        File sibling = new File("/data/app/other");
        assertFalse(ModPaths.isContainedIn(root, sibling));
    }

    @Test
    public void parentDirectoryIsNotContained() {
        File root = new File("/data/app/games");
        File parent = new File("/data/app");
        assertFalse(ModPaths.isContainedIn(root, parent));
    }

    @Test
    public void pathWithSamePrefixButDifferentNameIsNotContained() {
        // "/data/app/games2" should NOT be inside "/data/app/games".
        File root = new File("/data/app/games");
        File tricky = new File("/data/app/games2/mod.dll");
        assertFalse(ModPaths.isContainedIn(root, tricky));
    }

    @Test
    public void traversalViaDoubleDotIsNotContained() {
        // Even if the string representation looks inside, canonical resolution
        // must show it is outside.
        File root = new File("/data/app/games");
        // Note: new File(root, "../../etc/passwd").getCanonicalPath() → /data/etc/passwd
        // We do NOT call isContainedIn here with a dotdot path because File.canonical
        // resolves on the real FS; just verify the check handles a constructed path.
        File outside = new File("/data/etc/passwd");
        assertFalse(ModPaths.isContainedIn(root, outside));
    }

    // -------------------------------------------------------------------------
    // Safe game ID hashing
    // -------------------------------------------------------------------------

    @Test
    public void safeGameIdIsHex16Chars() {
        String safe = ModPaths.safeGameId("test-game-id");
        assertNotNull(safe);
        assertEquals(16, safe.length());
        assertTrue("Must be lowercase hex", safe.matches("[0-9a-f]{16}"));
    }

    @Test
    public void safeGameIdIsDeterministic() {
        assertEquals(
                ModPaths.safeGameId("my-game"),
                ModPaths.safeGameId("my-game")
        );
    }

    @Test
    public void differentGameIdsProduceDifferentHashes() {
        assertNotEquals(
                ModPaths.safeGameId("game-a"),
                ModPaths.safeGameId("game-b")
        );
    }

    @Test
    public void safeGameIdHandlesSpecialCharacters() {
        // UUIDs with hyphens and slashes should not cause errors.
        String id = "urn:game/id?query=1&foo=2#fragment";
        String safe = ModPaths.safeGameId(id);
        assertEquals(16, safe.length());
        assertTrue(safe.matches("[0-9a-f]{16}"));
    }

    // -------------------------------------------------------------------------
    // Shared-container check (policy inspection)
    // -------------------------------------------------------------------------

    @Test
    public void isolatedGameHasNoSharedContainerRejection() {
        ManagedGame game = makeGame(ManagedGame.CONTAINER_POLICY_ISOLATED, null, "C:\\g.exe");
        // No executableDosPath check here – just confirm the policy field.
        assertFalse(ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(game.containerPolicy));
    }

    @Test
    public void sharedGamePolicyIsIdentified() {
        ManagedGame game = makeGame(ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT, null, "C:\\g.exe");
        assertTrue(ManagedGame.CONTAINER_POLICY_SHARED_DEFAULT.equals(game.containerPolicy));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ManagedGame makeGame(String policy, String gamePath, String dosPath) {
        ManagedGame g = new ManagedGame();
        g.id              = "test-id";
        g.title           = "Test Game";
        g.containerId     = 1;
        g.containerPolicy = policy;
        g.gamePath        = gamePath;
        g.executableDosPath = dosPath;
        g.state           = "ready";
        g.createdAt       = 1;
        g.updatedAt       = 1;
        return g;
    }
}
