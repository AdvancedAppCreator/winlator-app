package com.winlator.api;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;

/**
 * Safe ZIP extractor for mod archives.
 *
 * <p>Each entry is validated before extraction:</p>
 * <ul>
 *   <li>Symlinks (Unix symlink mode bits) are rejected.</li>
 *   <li>Absolute paths are rejected.</li>
 *   <li>Path traversal (containing {@code ..} components) is rejected via
 *       canonical-path containment check.</li>
 * </ul>
 *
 * <p>All files are extracted flat into {@code stagingDir}.  Directory
 * entries are created as needed.  The returned list contains the
 * game-root-relative path for every extracted <em>file</em> (no
 * directories).</p>
 */
final class ModZipExtractor {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;

    private ModZipExtractor() {}

    /**
     * Extracts {@code zipFile} into {@code stagingDir}.
     *
     * @return relative paths of all extracted file entries.
     * @throws IOException if any unsafe entry is found, extraction fails,
     *                     or the ZIP cannot be opened.
     */
    static ArrayList<String> extract(File zipFile, File stagingDir) throws IOException {
        ArrayList<String> extractedPaths = new ArrayList<>();
        String canonicalStagingDir = stagingDir.getCanonicalPath();
        if (!canonicalStagingDir.endsWith(File.separator)) {
            canonicalStagingDir += File.separator;
        }

        ZipFile zf;
        try {
            zf = new ZipFile(zipFile);
        } catch (IOException e) {
            throw new IOException("Cannot open mod ZIP: " + e.getMessage(), e);
        }
        try {
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                throw new IOException("Cannot create staging directory: " + stagingDir);
            }
            Enumeration<ZipArchiveEntry> entries = zf.getEntries();
            HashSet<String> seenPaths = new HashSet<>();
            long totalBytes = 0;
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("Mod ZIP contains too many entries.");
                }
                String entryName = entry.getName().replace('\\', '/');

                rejectUnsafe(entry, entryName, stagingDir, canonicalStagingDir);
                entryName = normaliseRelativePath(entryName);
                if (!seenPaths.add(entryName)) {
                    throw new IOException(
                            "Mod ZIP contains a duplicate path: " + entryName
                    );
                }

                if (entry.isDirectory()) {
                    File dir = new File(stagingDir, entryName);
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("Cannot create directory: " + dir);
                    }
                    continue;
                }

                File target = new File(stagingDir, entryName);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot create parent directory: " + parent);
                }

                try (InputStream in = zf.getInputStream(entry);
                     BufferedOutputStream out =
                             new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    int read;
                    long fileBytes = 0;
                    while ((read = in.read(buf)) != -1) {
                        fileBytes += read;
                        totalBytes += read;
                        if (fileBytes > MAX_FILE_BYTES) {
                            throw new IOException(
                                    "Mod ZIP entry exceeds the per-file size limit: " +
                                            entryName
                            );
                        }
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            throw new IOException(
                                    "Mod ZIP exceeds the total extracted-size limit."
                            );
                        }
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }
                extractedPaths.add(entryName);
            }
        } finally {
            zf.close();
        }
        return extractedPaths;
    }

    // -------------------------------------------------------------------------
    // Safety validation
    // -------------------------------------------------------------------------

    private static void rejectUnsafe(
            ZipArchiveEntry entry,
            String entryName,
            File stagingDir,
            String canonicalStagingDir
    ) throws IOException {
        if (entry.isUnixSymlink()) {
            throw new IOException(
                    "Mod ZIP contains a symlink entry and cannot be installed safely: "
                            + entryName);
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw new IOException(
                    "Mod ZIP contains an absolute-path entry: " + entryName);
        }
        if (entryName.matches("(?i)^[a-z]:.*")) {
            throw new IOException(
                    "Mod ZIP contains a drive-qualified absolute path: " + entryName
            );
        }
        for (String component : entryName.split("/", -1)) {
            if ("..".equals(component)) {
                throw new IOException(
                        "Mod ZIP entry contains path traversal: " + entryName
                );
            }
        }
        // Canonical-path containment check handles all ".." traversals and
        // drive-letter tricks (new File(dir, "/abs") resolves to "/abs" in Java).
        File resolved;
        try {
            resolved = new File(stagingDir, entryName).getCanonicalFile();
        } catch (IOException e) {
            throw new IOException(
                    "Cannot resolve entry path '" + entryName + "': " + e.getMessage(), e);
        }
        String resolvedPath = resolved.getPath();
        if (!resolvedPath.startsWith(canonicalStagingDir)
                && !resolvedPath.equals(
                        canonicalStagingDir.substring(0, canonicalStagingDir.length() - 1))) {
            throw new IOException(
                    "Mod ZIP entry would escape staging directory (traversal): "
                            + entryName);
        }
    }

    /**
     * Normalises a ZIP entry name to use forward slashes and no leading slash.
     */
    static String normaliseRelativePath(String entryName) {
        return entryName.replace('\\', '/').replaceAll("^/+", "");
    }
}
