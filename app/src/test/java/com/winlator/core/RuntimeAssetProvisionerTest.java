package com.winlator.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RuntimeAssetProvisionerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sha256AndSizeAreVerifiedDuringExtraction() throws Exception {
        byte[] content = "verified runtime".getBytes(StandardCharsets.UTF_8);
        File source = temporaryFolder.newFile("content");
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(content);
        }
        RuntimeAssetManifest.Asset asset = new RuntimeAssetManifest.Asset(
                "assets/runtime.tzst",
                "runtime.tzst",
                content.length,
                RuntimeAssetProvisioner.sha256(source)
        );
        File apk = createZip("assets/runtime.tzst", content);
        File destination = temporaryFolder.newFolder("installed");

        RuntimeAssetProvisioner.extractAssets(
                apk,
                destination,
                new AtomicBoolean(false),
                Collections.singletonList(asset)
        );

        assertArrayEquals(
                content,
                java.nio.file.Files.readAllBytes(
                        new File(destination, "runtime.tzst").toPath()
                )
        );
    }

    @Test
    public void rejectsDigestMismatchAndRemainsIncomplete() throws Exception {
        byte[] content = "untrusted runtime".getBytes(StandardCharsets.UTF_8);
        RuntimeAssetManifest.Asset asset = new RuntimeAssetManifest.Asset(
                "assets/runtime.tzst",
                "runtime.tzst",
                content.length,
                "0000000000000000000000000000000000000000000000000000000000000000"
        );
        File destination = temporaryFolder.newFolder("partial");

        expectInvalidZip(() -> RuntimeAssetProvisioner.extractAssets(
                createZip("assets/runtime.tzst", content),
                destination,
                new AtomicBoolean(false),
                Collections.singletonList(asset)
        ));
        assertFalse(RuntimeAssetProvisioner.isReady(destination));
    }

    @Test
    public void rejectsMissingAndTraversalEntries() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        RuntimeAssetManifest.Asset asset = new RuntimeAssetManifest.Asset(
                "assets/runtime.tzst",
                "runtime.tzst",
                content.length,
                "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881"
        );
        expectInvalidZip(() -> RuntimeAssetProvisioner.extractAssets(
                createZip("assets/other.tzst", content),
                temporaryFolder.newFolder("missing"),
                new AtomicBoolean(false),
                Collections.singletonList(asset)
        ));
        expectInvalidZip(() -> RuntimeAssetProvisioner.extractAssets(
                createZip("../escape", content, "assets/runtime.tzst", content),
                temporaryFolder.newFolder("traversal"),
                new AtomicBoolean(false),
                Collections.singletonList(asset)
        ));
    }

    @Test
    public void rejectsDuplicateRequiredEntry() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        RuntimeAssetManifest.Asset asset = new RuntimeAssetManifest.Asset(
                "assets/runtime.tzst",
                "runtime.tzst",
                content.length,
                "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881"
        );
        File apk = temporaryFolder.newFile("duplicate.apk");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(apk)) {
            for (int index = 0; index < 2; index++) {
                zip.putArchiveEntry(new ZipArchiveEntry("assets/runtime.tzst"));
                zip.write(content);
                zip.closeArchiveEntry();
            }
        }
        expectInvalidZip(() -> RuntimeAssetProvisioner.extractAssets(
                apk,
                temporaryFolder.newFolder("duplicate"),
                new AtomicBoolean(false),
                Collections.singletonList(asset)
        ));
    }

    @Test
    public void cancellationDoesNotCreateCompleteState() throws Exception {
        byte[] content = "runtime".getBytes(StandardCharsets.UTF_8);
        RuntimeAssetManifest.Asset asset = new RuntimeAssetManifest.Asset(
                "assets/runtime.tzst",
                "runtime.tzst",
                content.length,
                "d92c6a81b2ff504635432e23487a26b8200e9d1d16335dcf8c850555ed2bdc9"
        );
        File destination = temporaryFolder.newFolder("cancelled");
        RuntimeAssetProvisioner.extractAssets(
                createZip("assets/runtime.tzst", content),
                destination,
                new AtomicBoolean(true),
                Collections.singletonList(asset)
        );
        assertFalse(RuntimeAssetProvisioner.isReady(destination));
        assertFalse(new File(destination, "runtime.tzst").exists());
    }

    @Test
    public void cancellationBeforeCommitPreservesExistingInstallation() throws Exception {
        File install = temporaryFolder.newFolder("existing-install");
        File existing = new File(install, "existing");
        try (FileOutputStream output = new FileOutputStream(existing)) {
            output.write("ready".getBytes(StandardCharsets.UTF_8));
        }
        File payload = temporaryFolder.newFolder("verified-payload");

        RuntimeAssetProvisioner.Result result = RuntimeAssetProvisioner.commitPayload(
                payload,
                install,
                new AtomicBoolean(true)
        );

        assertEquals(RuntimeAssetProvisioner.Result.CANCELLED, result);
        assertTrue(existing.isFile());
        assertTrue(payload.isDirectory());
    }

    @Test
    public void abandonedStagesAreRemovedAndNewestValidPreviousIsRecovered() throws Exception {
        File root = temporaryFolder.newFolder("runtime-root");
        File install = new File(root, "installed");
        File staleStage = new File(root, ".stage-stale");
        File activeStage = new File(root, ".stage-active");
        File olderValid = new File(root, ".previous-older");
        File newerInvalid = new File(root, ".previous-newer-invalid");
        File newestValid = new File(root, ".previous-newest");
        assertTrue(staleStage.mkdirs());
        assertTrue(activeStage.mkdirs());
        createReadyDirectory(olderValid);
        assertTrue(newerInvalid.mkdirs());
        createReadyDirectory(newestValid);
        assertTrue(olderValid.setLastModified(1000));
        assertTrue(newerInvalid.setLastModified(2000));
        assertTrue(newestValid.setLastModified(3000));

        assertTrue(RuntimeAssetProvisioner.reconcileAbandoned(
                root,
                install,
                activeStage,
                directory -> new File(directory, "ready").isFile()
        ));

        assertFalse(staleStage.exists());
        assertTrue(activeStage.isDirectory());
        assertTrue(new File(install, "ready").isFile());
        assertFalse(olderValid.exists());
        assertFalse(newerInvalid.exists());
        assertFalse(newestValid.exists());
    }

    @Test
    public void freshInstallCancellationAfterRenameRemovesCommittedPayload() throws Exception {
        File install = new File(temporaryFolder.getRoot(), "fresh-install");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        File payload = new CancellingRenameFile(
                temporaryFolder.newFolder("fresh-payload"),
                cancelled
        );

        RuntimeAssetProvisioner.Result result = RuntimeAssetProvisioner.commitPayload(
                payload,
                install,
                cancelled
        );

        assertEquals(RuntimeAssetProvisioner.Result.CANCELLED, result);
        assertFalse(install.exists());
    }

    private void createReadyDirectory(File directory) throws IOException {
        assertTrue(directory.mkdirs());
        assertTrue(new File(directory, "ready").createNewFile());
    }

    private File createZip(Object... nameAndContent) throws IOException {
        File file = temporaryFolder.newFile("archive-" + System.nanoTime() + ".apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int index = 0; index < nameAndContent.length; index += 2) {
                zip.putNextEntry(new ZipEntry((String)nameAndContent[index]));
                zip.write((byte[])nameAndContent[index + 1]);
                zip.closeEntry();
            }
        }
        return file;
    }

    private void expectInvalidZip(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("Expected invalid APK to be rejected.");
        }
        catch (IOException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class CancellingRenameFile extends File {
        private final AtomicBoolean cancelled;

        CancellingRenameFile(File file, AtomicBoolean cancelled) {
            super(file.getAbsolutePath());
            this.cancelled = cancelled;
        }

        @Override
        public boolean renameTo(File destination) {
            boolean renamed = super.renameTo(destination);
            if (renamed) cancelled.set(true);
            return renamed;
        }
    }
}
