package com.winlator.core;

import java.io.File;
import java.io.RandomAccessFile;

public final class LiveMakerCompat {
    public static final String RUNTIME_LOCALE = "ja_JP.UTF-8";

    private static final int MIN_ARCHIVE_VERSION = 100;
    private static final int MAX_ARCHIVE_VERSION = 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 1_000_000;

    private LiveMakerCompat() {
    }

    public static boolean isExecutable(File file) {
        if (file == null || !file.isFile() || file.length() < 16) return false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (readU16(input, 0) != 0x5a4d) return false;

            long trailer = file.length() - 6;
            long archiveOffset = Integer.toUnsignedLong(readU32(input, trailer));
            if (readU16(input, trailer + 4) != 0x766c
                    || archiveOffset < 2
                    || archiveOffset + 10 > trailer) {
                return false;
            }

            int version = readU32(input, archiveOffset + 2);
            long count = Integer.toUnsignedLong(readU32(input, archiveOffset + 6));
            long minimumDirectorySize = 18L + 22L * count;
            return readU16(input, archiveOffset) == 0x6676
                    && version >= MIN_ARCHIVE_VERSION
                    && version <= MAX_ARCHIVE_VERSION
                    && count <= MAX_ARCHIVE_ENTRIES
                    && archiveOffset + minimumDirectorySize <= trailer;
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private static int readU16(RandomAccessFile input, long offset)
            throws java.io.IOException {
        input.seek(offset);
        int b0 = input.read();
        int b1 = input.read();
        if ((b0 | b1) < 0) return -1;
        return b0 | (b1 << 8);
    }

    private static int readU32(RandomAccessFile input, long offset)
            throws java.io.IOException {
        input.seek(offset);
        int b0 = input.read();
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b0 | b1 | b2 | b3) < 0) return -1;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }
}
