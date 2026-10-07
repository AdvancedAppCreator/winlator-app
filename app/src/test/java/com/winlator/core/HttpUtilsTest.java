package com.winlator.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

public class HttpUtilsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void completeVerifiedPartialFinalizesWithoutNetwork() throws Exception {
        File destination = new File(temporaryFolder.getRoot(), "artifact.exe");
        File partial = new File(destination.getPath() + ".part");
        byte[] content = "verified-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(partial.toPath(), content);
        String hash = hex(MessageDigest.getInstance("SHA-256").digest(content));

        HttpUtils.downloadVerified(
                "https://download.microsoft.com/not-requested.exe",
                destination,
                hash,
                content.length,
                null
        );

        assertTrue(destination.isFile());
        assertFalse(partial.exists());
        assertArrayEquals(content, Files.readAllBytes(destination.toPath()));
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsUntrustedDownloadHost() throws Exception {
        HttpUtils.downloadVerified(
                "https://example.com/file.exe",
                new File(temporaryFolder.getRoot(), "artifact.exe"),
                "0000000000000000000000000000000000000000000000000000000000000000",
                1,
                null
        );
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
