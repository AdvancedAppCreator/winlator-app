package com.winlator.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-Java path helpers for the mod manager.
 *
 * <p>Game-root resolution (which requires Android {@code Context},
 * {@code ContainerManager}, and {@code WineUtils}) lives in
 * {@link GameModRootResolver} to keep this class dependency-free and
 * trivially testable without the full Android SDK on the classpath.</p>
 */
final class ModPaths {

    private ModPaths() {}

    // -------------------------------------------------------------------------
    // Path safety helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} if {@code dosPath} refers to the Z: drive. */
    static boolean isZDrive(String dosPath) {
        if (dosPath == null || dosPath.length() < 2) return false;
        char first = dosPath.charAt(0);
        char second = dosPath.charAt(1);
        return (first == 'Z' || first == 'z') && second == ':';
    }

    /**
     * Returns {@code true} if {@code candidate} is contained within
     * {@code root} (using canonical paths so symlinks and ".." are resolved).
     */
    static boolean isContainedIn(File root, File candidate) {
        try {
            String rootPath  = root.getCanonicalPath();
            String candPath  = candidate.getCanonicalPath();
            if (!rootPath.endsWith(File.separator)) rootPath += File.separator;
            return candPath.startsWith(rootPath) || candPath.equals(
                    rootPath.substring(0, rootPath.length() - 1));
        } catch (IOException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private-infrastructure helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a filesystem-safe identifier derived from {@code gameId}.
     * Uses the first 16 hex characters of the SHA-256 hash of the game id.
     */
    static String safeGameId(String gameId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(gameId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i] & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Android platform.
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Returns the private mods root for the given game (relative to {@code filesDir}). */
    static File modsDir(File filesDir, String gameId) {
        return new File(filesDir, "mods/" + safeGameId(gameId));
    }

    /** Returns the manifest file for the given game. */
    static File manifestFile(File filesDir, String gameId) {
        return new File(modsDir(filesDir, gameId), "manifest.json");
    }

    /** Returns the staging directory for a specific mod. */
    static File stagingDir(File filesDir, String gameId, String modId) {
        return new File(modsDir(filesDir, gameId), "staging/" + modId);
    }

    /** Returns the backup directory for a specific mod. */
    static File backupDir(File filesDir, String gameId, String modId) {
        return new File(modsDir(filesDir, gameId), "backups/" + modId);
    }
}
