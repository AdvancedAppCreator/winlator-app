package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Unit tests for {@link ModZipExtractor}: path traversal, absolute-path, and
 * symlink rejection, plus basic happy-path extraction.
 *
 * <p>Tests create real ZIP files in a temporary directory (pure-JVM, no
 * Android context required).</p>
 */
public class ModZipExtractorTest {

    private File testDir;
    private File stagingDir;
    private File zipFile;

    @Before
    public void setUp() throws IOException {
        testDir    = createTempDir("modzip_test");
        stagingDir = new File(testDir, "staging");
        zipFile    = new File(testDir, "test.zip");
    }

    @After
    public void tearDown() {
        deleteDir(testDir);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    public void extractsNormalFilesSuccessfully() throws Exception {
        buildZip(zipFile,
                regularEntry("data/config.ini", "setting=1"),
                regularEntry("bin/hook.dll",    "BINARY"),
                regularEntry("readme.txt",      "Hello"));

        ArrayList<String> paths = ModZipExtractor.extract(zipFile, stagingDir);

        assertEquals(3, paths.size());
        assertTrue(paths.contains("data/config.ini"));
        assertTrue(paths.contains("bin/hook.dll"));
        assertTrue(paths.contains("readme.txt"));
        assertTrue(new File(stagingDir, "data/config.ini").isFile());
        assertTrue(new File(stagingDir, "bin/hook.dll").isFile());
    }

    @Test
    public void directoryEntriesAreCreatedNotInReturnList() throws Exception {
        buildZip(zipFile,
                dirEntry("subdir/"),
                regularEntry("subdir/file.txt", "content"));

        ArrayList<String> paths = ModZipExtractor.extract(zipFile, stagingDir);

        // Only file entries should be in the returned list.
        assertEquals(1, paths.size());
        assertEquals("subdir/file.txt", paths.get(0));
        assertTrue(new File(stagingDir, "subdir").isDirectory());
    }

    // -------------------------------------------------------------------------
    // Path traversal rejection
    // -------------------------------------------------------------------------

    @Test
    public void traversalEntryIsRejected() throws Exception {
        buildZip(zipFile, regularEntry("../../etc/passwd", "evil"));
        try {
            ModZipExtractor.extract(zipFile, stagingDir);
            fail("Expected IOException for traversal entry");
        } catch (IOException e) {
            assertTrue("Message should mention traversal or escape",
                    e.getMessage().toLowerCase().contains("traversal")
                            || e.getMessage().toLowerCase().contains("escape"));
        }
    }

    @Test
    public void nestedTraversalIsRejected() throws Exception {
        buildZip(zipFile, regularEntry("a/b/../../../etc/passwd", "evil"));
        try {
            ModZipExtractor.extract(zipFile, stagingDir);
            fail("Expected IOException for nested traversal");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void backslashTraversalIsRejectedBeforePathNormalization() throws Exception {
        buildZip(zipFile, regularEntry("..\\..\\outside.dll", "evil"));
        try {
            ModZipExtractor.extract(zipFile, stagingDir);
            fail("Expected IOException for backslash traversal entry");
        }
        catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("traversal"));
        }
    }

    @Test
    public void duplicateFileTargetsAreRejected() throws Exception {
        buildZip(
                zipFile,
                regularEntry("data/file.txt", "first"),
                regularEntry("data\\file.txt", "second")
        );
        try {
            ModZipExtractor.extract(zipFile, stagingDir);
            fail("Expected IOException for duplicate normalized path");
        }
        catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("duplicate"));
        }
    }

    // -------------------------------------------------------------------------
    // Absolute path rejection
    // -------------------------------------------------------------------------

    @Test
    public void absoluteUnixPathIsRejected() throws Exception {
        buildZip(zipFile, regularEntry("/etc/passwd", "evil"));
        try {
            ModZipExtractor.extract(zipFile, stagingDir);
            fail("Expected IOException for absolute path");
        } catch (IOException e) {
            assertTrue("Message should mention absolute",
                    e.getMessage().toLowerCase().contains("absolute"));
        }
    }

    // -------------------------------------------------------------------------
    // Symlink rejection
    // -------------------------------------------------------------------------

    @Test
    public void symlinkEntryIsRejected() throws Exception {
        buildZipWithSymlink(zipFile, "link_target.dll", "other.dll");
        try {
            ModZipExtractor.extract(zipFile, stagingDir);
            fail("Expected IOException for symlink entry");
        } catch (IOException e) {
            assertTrue("Message should mention symlink",
                    e.getMessage().toLowerCase().contains("symlink"));
        }
    }

    // -------------------------------------------------------------------------
    // normaliseRelativePath
    // -------------------------------------------------------------------------

    @Test
    public void normaliseRemovesLeadingSlash() {
        assertEquals("a/b.txt", ModZipExtractor.normaliseRelativePath("/a/b.txt"));
    }

    @Test
    public void normaliseConvertsBackslash() {
        assertEquals("data/config.ini",
                ModZipExtractor.normaliseRelativePath("data\\config.ini"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ZipArchiveEntry regularEntry(String name, String content) {
        // The ZipArchiveEntry carries the content via a wrapper.
        return new ZipEntryWithContent(name, content.getBytes(StandardCharsets.UTF_8), false);
    }

    private static ZipArchiveEntry dirEntry(String name) {
        ZipArchiveEntry e = new ZipArchiveEntry(name);
        return e;
    }

    private static void buildZip(File dest, ZipArchiveEntry... entries) throws IOException {
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(
                new BufferedOutputStream(new FileOutputStream(dest)))) {
            for (ZipArchiveEntry entry : entries) {
                if (entry instanceof ZipEntryWithContent) {
                    ZipEntryWithContent ewc = (ZipEntryWithContent) entry;
                    ZipArchiveEntry ze = new ZipArchiveEntry(ewc.getName());
                    ze.setSize(ewc.data.length);
                    zos.putArchiveEntry(ze);
                    zos.write(ewc.data);
                } else {
                    zos.putArchiveEntry(entry);
                }
                zos.closeArchiveEntry();
            }
        }
    }

    /** Creates a ZIP that contains one symlink entry (Unix mode bits 0xA1FF). */
    private static void buildZipWithSymlink(File dest, String linkName, String target)
            throws IOException {
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(
                new BufferedOutputStream(new FileOutputStream(dest)))) {
            ZipArchiveEntry symEntry = new ZipArchiveEntry(linkName);
            // Unix mode: 0120777 (octal) = 0xA1FF = symlink + rwxrwxrwx
            symEntry.setUnixMode(0xA1FF);
            byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
            symEntry.setSize(targetBytes.length);
            zos.putArchiveEntry(symEntry);
            zos.write(targetBytes);
            zos.closeArchiveEntry();
        }
    }

    private static File createTempDir(String prefix) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                prefix + "_" + System.nanoTime());
        if (!dir.mkdirs()) throw new IOException("Cannot create temp dir: " + dir);
        return dir;
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) deleteDir(c);
            }
        }
        dir.delete();
    }

    /** Wrapper so we can pass pre-filled content alongside a ZipArchiveEntry. */
    private static class ZipEntryWithContent extends ZipArchiveEntry {
        final byte[] data;

        ZipEntryWithContent(String name, byte[] data, boolean ignored) {
            super(name);
            this.data = data;
        }
    }
}
