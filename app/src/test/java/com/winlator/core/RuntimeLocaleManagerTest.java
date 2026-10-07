package com.winlator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.winlator.xenvironment.RootFS;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class RuntimeLocaleManagerTest {
    @Test
    public void mapsPosixLocaleToGeneratedDirectory() {
        assertEquals(
                "ja_JP.utf8",
                RuntimeLocaleManager.directoryName("ja_JP.UTF-8")
        );
        assertEquals(
                "en_US.utf8",
                RuntimeLocaleManager.directoryName("en_US.UTF-8")
        );
    }

    @Test
    public void invokesLocaledefThroughTheBundledGlibcLinker() {
        File root = new File("/data/user/0/com.winlator/files/rootfs");
        List<String> command = RuntimeLocaleManager.buildCommand(
                root,
                "ja_JP.UTF-8"
        );

        assertTrue(command.get(0).replace('\\', '/')
                .endsWith("usr/lib/ld-linux-aarch64.so.1"));
        assertEquals("--library-path", command.get(1));
        assertTrue(command.get(3).replace('\\', '/')
                .endsWith("usr/bin/localedef"));
        assertTrue(command.contains("--no-archive"));
        assertEquals("ja_JP", command.get(6));
        assertTrue(command.get(command.size() - 1).replace('\\', '/')
                .endsWith("usr/lib/locale/ja_JP.utf8"));
    }

    @Test
    public void firstGenerationCreatesCompleteDirectory() throws Exception {
        File root = createRuntimeRoot("first");
        try {
            RuntimeLocaleManager.ensureAvailable(
                    root,
                    "ja_JP.UTF-8",
                    (command, workingDirectory, environment) -> {
                        assertTrue(command.contains("--no-archive"));
                        assertEquals(
                                new File(root, "usr/share/i18n").getPath(),
                                environment.get("I18NPATH")
                        );
                        assertEquals(
                                new File(root, "usr/lib/locale").getPath(),
                                environment.get("LOCPATH")
                        );
                        assertEquals(
                                "/system/bin:/system/xbin",
                                environment.get("PATH")
                        );
                        assertFalse(environment.containsKey("LD_LIBRARY_PATH"));
                        createCompleteLocale(
                                new File(root, "usr/lib/locale/ja_JP.utf8")
                        );
                        return new RuntimeLocaleManager.ProcessResult(0, "", false);
                    }
            );

            assertTrue(RuntimeLocaleManager.isComplete(
                    new File(root, "usr/lib/locale/ja_JP.utf8")
            ));
        }
        finally {
            FileUtils.delete(root);
        }
    }

    @Test
    public void completeLocaleIsReusedWithoutRunningLocaledef() throws Exception {
        File root = createRuntimeRoot("reuse");
        try {
            createCompleteLocale(new File(root, "usr/lib/locale/ja_JP.utf8"));
            RuntimeLocaleManager.ensureAvailable(
                    root,
                    "ja_JP.UTF-8",
                    (command, workingDirectory, environment) -> {
                        throw new AssertionError("localedef should not run for a complete locale.");
                    }
            );
        }
        finally {
            FileUtils.delete(root);
        }
    }

    @Test
    public void generationFailureIncludesStatusOutputAndExpectedDirectory()
            throws Exception {
        File root = createRuntimeRoot("failure");
        File localeDirectory = new File(root, "usr/lib/locale/ja_JP.utf8");
        assertTrue(localeDirectory.mkdirs());
        assertTrue(new File(localeDirectory, "LC_CTYPE").createNewFile());

        try {
            RuntimeLocaleManager.ensureAvailable(
                    root,
                    "ja_JP.UTF-8",
                    (command, workingDirectory, environment) ->
                            new RuntimeLocaleManager.ProcessResult(
                                    7,
                                    "cannot write locale archive",
                                    false
                            )
            );
            fail("Expected locale generation to fail.");
        }
        catch (RuntimeLocaleManager.GenerationException expected) {
            assertEquals(Integer.valueOf(7), expected.getExitStatus());
            assertEquals("localedef", expected.getStage());
            assertEquals("cannot write locale archive", expected.getOutput());
            assertEquals(localeDirectory, expected.getOutputDirectory());
            assertTrue(expected.getCommand().contains("--no-archive"));
            assertNotNull(expected.getEnvironment().get("LOCPATH"));
            assertTrue(expected.getMessage().contains("exitStatus=7"));
            assertFalse(localeDirectory.exists());
        }
        finally {
            FileUtils.delete(root);
        }
    }

    @Test
    public void incompleteLocaleDirectoryIsRemovedBeforeRegeneration()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        RootFS rootFS = RootFS.find(context);
        File localeDirectory = new File(
                rootFS.getRootDir(),
                "usr/lib/locale/ja_JP.utf8"
        );
        assertTrue(localeDirectory.mkdirs());
        assertTrue(new File(localeDirectory, "LC_CTYPE").createNewFile());

        try {
            RuntimeLocaleManager.ensureAvailable(rootFS, "ja_JP.UTF-8");
        }
        catch (IOException expected) {
            assertFalse(localeDirectory.exists());
            return;
        }
        throw new AssertionError("Expected missing locale tools to fail.");
    }

    private File createRuntimeRoot(String suffix) throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        File root = new File(context.getCacheDir(), "runtime-locale-" + suffix);
        FileUtils.delete(root);
        assertTrue(new File(root, "usr/bin").mkdirs());
        assertTrue(new File(root, "usr/lib/gconv").mkdirs());
        assertTrue(new File(root, "usr/lib/locale").mkdirs());
        assertTrue(new File(root, "usr/share/i18n/locales").mkdirs());
        assertTrue(new File(root, "usr/share/i18n/charmaps").mkdirs());
        createFile(new File(root, "usr/bin/localedef"));
        createFile(new File(root, "usr/lib/ld-linux-aarch64.so.1"));
        createFile(new File(root, "usr/lib/gconv/gconv-modules"));
        createFile(new File(root, "usr/share/i18n/locales/ja_JP"));
        createFile(new File(root, "usr/share/i18n/charmaps/UTF-8.gz"));
        return root;
    }

    private void createCompleteLocale(File directory) throws IOException {
        assertTrue(directory.mkdirs() || directory.isDirectory());
        createFile(new File(directory, "LC_CTYPE"));
        createFile(new File(directory, "LC_COLLATE"));
        createFile(new File(directory, "LC_TIME"));
        createFile(new File(directory, "LC_NUMERIC"));
        createFile(new File(directory, "LC_MONETARY"));
        assertTrue(new File(directory, "LC_MESSAGES").mkdirs());
        createFile(new File(directory, "LC_MESSAGES/SYS_LC_MESSAGES"));
    }

    private void createFile(File file) throws IOException {
        assertTrue(file.createNewFile() || file.isFile());
        assertTrue(file.setReadable(true, true));
        assertTrue(file.setExecutable(true, true));
    }
}
