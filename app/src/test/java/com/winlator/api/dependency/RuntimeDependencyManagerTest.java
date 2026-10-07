package com.winlator.api.dependency;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class RuntimeDependencyManagerTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("runtime_dep_store", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void importInstallerCopiesIntoPrivateStorageAndTracksPath()
            throws Exception {
        byte[] content = new byte[]{1, 2, 3, 4, 5};
        File source = new File(context.getCacheDir(), "vc_redist.x64.exe");
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(content);
        }

        String imported = RuntimeDependencyManager.importInstaller(
                context,
                Uri.fromFile(source),
                7,
                RuntimeDependencyCatalog.VCRUN2015_2022
        );

        File destination = new File(imported);
        assertTrue(destination.isFile());
        assertTrue(destination.getCanonicalPath().startsWith(
                context.getFilesDir().getCanonicalPath()
        ));
        assertArrayEquals(content, Files.readAllBytes(destination.toPath()));
        assertEquals(
                imported,
                RuntimeDependencyManager.getRecord(
                        context,
                        7,
                        RuntimeDependencyCatalog.VCRUN2015_2022
                ).installerPath
        );
    }

    @Test
    public void installerLifecyclePersistsFinalResult() throws Exception {
        RuntimeDependencyManager.markInstalling(
                context,
                8,
                RuntimeDependencyCatalog.OPENAL,
                new File(context.getFilesDir(), "openal.exe").getAbsolutePath()
        );
        assertEquals(
                DependencyStatus.INSTALLING,
                RuntimeDependencyManager.getRecord(
                        context,
                        8,
                        RuntimeDependencyCatalog.OPENAL
                ).status
        );

        RuntimeDependencyManager.markResult(
                context,
                8,
                RuntimeDependencyCatalog.OPENAL,
                true,
                "Installer exited successfully."
        );
        DependencyInstallRecord result = RuntimeDependencyManager.getRecord(
                context,
                8,
                RuntimeDependencyCatalog.OPENAL
        );
        assertEquals(DependencyStatus.INSTALLED, result.status);
        assertEquals(
                "Installer exited successfully.",
                result.log.get(result.log.size() - 1).message
        );
    }
}
