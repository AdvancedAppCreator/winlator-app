package com.winlator.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;

public class LiveMakerCompatTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void detectsExecutableWithLiveMakerArchiveTrailer() throws Exception {
        File executable = folder.newFile("game.exe");
        try (RandomAccessFile output = new RandomAccessFile(executable, "rw")) {
            output.write(new byte[]{'M', 'Z'});
            output.setLength(64);
            output.seek(32);
            output.write(new byte[]{'v', 'f'});
            writeU32(output, 102);
            writeU32(output, 0);
            output.seek(64);
            writeU32(output, 32);
            output.write(new byte[]{'l', 'v'});
        }

        assertTrue(LiveMakerCompat.isExecutable(executable));
    }

    @Test
    public void rejectsNormalExecutableWithoutLiveMakerTrailer() throws Exception {
        File executable = folder.newFile("game.exe");
        try (RandomAccessFile output = new RandomAccessFile(executable, "rw")) {
            output.write(new byte[]{'M', 'Z'});
            output.setLength(64);
        }

        assertFalse(LiveMakerCompat.isExecutable(executable));
    }

    @Test
    public void rejectsTruncatedLiveMakerDirectory() throws Exception {
        File executable = folder.newFile("game.exe");
        try (RandomAccessFile output = new RandomAccessFile(executable, "rw")) {
            output.write(new byte[]{'M', 'Z'});
            output.setLength(48);
            output.seek(32);
            output.write(new byte[]{'v', 'f'});
            writeU32(output, 102);
            writeU32(output, 4);
            output.seek(48);
            writeU32(output, 32);
            output.write(new byte[]{'l', 'v'});
        }

        assertFalse(LiveMakerCompat.isExecutable(executable));
    }

    private static void writeU32(RandomAccessFile output, int value)
            throws java.io.IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }
}
