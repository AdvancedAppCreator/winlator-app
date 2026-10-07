package com.winlator.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UnitySerializedFilePatcherTest {
    @Test
    public void patchesEveryQualityProfileWithoutChangingOtherFields() throws Exception {
        byte[] fixture = fixture(2, new int[]{0, 1, 3});
        File file = File.createTempFile("unity-quality", ".assets");
        Files.write(file.toPath(), fixture);

        UnitySerializedFilePatcher.PatchResult result =
                UnitySerializedFilePatcher.patchTextureQuality(file, 2);

        assertEquals("2021.3.11f1", result.unityVersion);
        assertEquals(22, result.serializedFileVersion);
        assertEquals(java.util.Arrays.asList(0, 1, 3), result.previousLimits);
        assertEquals(3, result.patchedOffsets.size());
        assertEquals(2, readInt(result.data, 4096));
        assertEquals(3, readInt(result.data, 4100));
        assertEquals(2, readInt(result.data, 4104));
        assertEquals(2, readInt(result.data, 4108));
        assertEquals(2, readInt(result.data, 4112));

        byte[] expected = fixture.clone();
        for (int offset : new int[]{4104, 4108, 4112}) {
            ByteBuffer.wrap(expected, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(2);
        }
        assertArrayEquals(expected, result.data);
    }

    @Test
    public void refusesFilesWithStrippedQualitySettingsTypeTree() throws Exception {
        byte[] fixture = fixture(2, new int[]{0}, false);
        File file = File.createTempFile("unity-quality-missing-tree", ".assets");
        Files.write(file.toPath(), fixture);

        org.junit.Assert.assertThrows(
                UnitySerializedFilePatcher.UnsupportedFormatException.class,
                () -> UnitySerializedFilePatcher.patchTextureQuality(file, 2)
        );
    }

    @Test
    public void refusesUnsupportedUnityVersion() throws Exception {
        byte[] fixture = fixture(2, new int[]{0});
        byte[] newVersion = "2023.3.11f1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int versionOffset = 48;
        System.arraycopy(newVersion, 0, fixture, versionOffset, newVersion.length);
        File file = File.createTempFile("unity-quality-new-version", ".assets");
        Files.write(file.toPath(), fixture);

        org.junit.Assert.assertThrows(
                UnitySerializedFilePatcher.UnsupportedFormatException.class,
                () -> UnitySerializedFilePatcher.patchTextureQuality(file, 2)
        );
    }

    @Test
    public void refusesBigEndianSerializedFile() throws Exception {
        byte[] fixture = fixture(2, new int[]{0});
        fixture[16] = 1;
        File file = File.createTempFile("unity-quality-big-endian", ".assets");
        Files.write(file.toPath(), fixture);

        org.junit.Assert.assertThrows(
                UnitySerializedFilePatcher.UnsupportedFormatException.class,
                () -> UnitySerializedFilePatcher.patchTextureQuality(file, 2)
        );
    }

    @Test
    public void overlayLimitParserIsStrict() {
        assertEquals(0, UnityLaunchOverlay.parseLimit("off"));
        assertEquals(0, UnityLaunchOverlay.parseLimit("0"));
        assertEquals(1, UnityLaunchOverlay.parseLimit("1"));
        assertEquals(2, UnityLaunchOverlay.parseLimit("2"));
        assertEquals(3, UnityLaunchOverlay.parseLimit("3"));
        assertEquals(0, UnityLaunchOverlay.parseLimit("4"));
        assertEquals(0, UnityLaunchOverlay.parseLimit("invalid"));
    }

    @Test
    public void patchesOptionalExternalUnityFixture() throws Exception {
        String path = System.getenv("UNITY_GLOBALGAMEMANAGERS_FIXTURE");
        Assume.assumeTrue(path != null && new File(path).isFile());

        UnitySerializedFilePatcher.PatchResult result =
                UnitySerializedFilePatcher.patchTextureQuality(new File(path), 2);

        assertEquals("2021.3.11f1", result.unityVersion);
        assertEquals(java.util.Arrays.asList(0, 0, 0), result.previousLimits);
        assertEquals(3, result.patchedOffsets.size());
        String output = System.getenv("UNITY_PATCHED_FIXTURE_OUTPUT");
        if (output != null && !output.isEmpty()) {
            Files.write(new File(output).toPath(), result.data);
        }
    }

    private static byte[] fixture(int currentQuality, int[] textureLimits) {
        return fixture(currentQuality, textureLimits, true);
    }

    private static byte[] fixture(
            int currentQuality,
            int[] textureLimits,
            boolean hasTypeTree
    ) {
        List<NodeRecord> nodes = new ArrayList<>();
        nodes.add(new NodeRecord(0, "QualitySettings", "Base", -1));
        nodes.add(new NodeRecord(1, "int", "m_CurrentQuality", 4));
        nodes.add(new NodeRecord(1, "vector", "m_QualitySettings", -1));
        nodes.add(new NodeRecord(2, "Array", "Array", -1));
        nodes.add(new NodeRecord(3, "int", "size", 4));
        nodes.add(new NodeRecord(3, "QualitySetting", "data", -1));
        nodes.add(new NodeRecord(4, "int", "textureQuality", 4));

        Map<String, Integer> offsets = new LinkedHashMap<>();
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        for (NodeRecord node : nodes) {
            addString(offsets, strings, node.type);
            addString(offsets, strings, node.name);
        }

        ByteArrayOutputStream objectBytes = new ByteArrayOutputStream();
        if (hasTypeTree) {
            writeIntLE(objectBytes, currentQuality);
            writeIntLE(objectBytes, textureLimits.length);
            for (int limit : textureLimits) writeIntLE(objectBytes, limit);
        }
        else {
            writeIntLE(objectBytes, currentQuality);
            writeIntLE(objectBytes, textureLimits.length);
            for (int limit : textureLimits) writeQualitySetting2021(objectBytes, limit);
            writeIntLE(objectBytes, 0);
        }

        ByteBuffer nodeBytes = ByteBuffer.allocate(nodes.size() * 32)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (NodeRecord node : nodes) {
            nodeBytes.putShort((short)1);
            nodeBytes.put((byte)node.level);
            nodeBytes.put((byte)0);
            nodeBytes.putInt(offsets.get(node.type));
            nodeBytes.putInt(offsets.get(node.name));
            nodeBytes.putInt(node.byteSize);
            nodeBytes.putInt(0);
            nodeBytes.putInt(0);
            nodeBytes.putLong(0);
        }

        ByteArrayOutputStream metadataBytes = new ByteArrayOutputStream();
        writeNullString(metadataBytes, "2021.3.11f1");
        writeIntLE(metadataBytes, 19);
        metadataBytes.write(hasTypeTree ? 1 : 0);
        writeIntLE(metadataBytes, 1);
        writeIntLE(metadataBytes, 47);
        metadataBytes.write(0);
        writeShortLE(metadataBytes, -1);
        metadataBytes.write(new byte[16], 0, 16);
        if (hasTypeTree) {
            writeIntLE(metadataBytes, nodes.size());
            writeIntLE(metadataBytes, strings.size());
            metadataBytes.write(nodeBytes.array(), 0, nodeBytes.array().length);
            metadataBytes.write(strings.toByteArray(), 0, strings.size());
            writeIntLE(metadataBytes, 0);
        }
        writeIntLE(metadataBytes, 1);
        align4(metadataBytes);
        writeLongLE(metadataBytes, 1);
        writeLongLE(metadataBytes, 0);
        writeIntLE(metadataBytes, objectBytes.size());
        writeIntLE(metadataBytes, 0);

        int dataOffset = 4096;
        int fileSize = dataOffset + objectBytes.size();
        ByteBuffer header = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0);
        header.putInt(0);
        header.putInt(22);
        header.putInt(0);
        header.put((byte)0);
        header.put(new byte[3]);
        header.putInt(metadataBytes.size());
        header.putLong(fileSize);
        header.putLong(dataOffset);
        header.putLong(0);

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(header.array(), 0, header.array().length);
        file.write(metadataBytes.toByteArray(), 0, metadataBytes.size());
        while (file.size() < dataOffset) file.write(0);
        file.write(objectBytes.toByteArray(), 0, objectBytes.size());
        return file.toByteArray();
    }

    private static void writeQualitySetting2021(
            ByteArrayOutputStream output,
            int textureLimit
    ) {
        writeAlignedString(output, "Quality");
        for (int index = 0; index < 5; index++) writeIntLE(output, 0);
        for (int index = 0; index < 6; index++) writeFloatLE(output, 0);
        writeIntLE(output, 0);
        writeIntLE(output, 0);
        writeIntLE(output, textureLimit);
        writeIntLE(output, 0);
        writeIntLE(output, 0);
        output.write(0);
        output.write(0);
        output.write(0);
        output.write(0);
        writeIntLE(output, 0);
        writeFloatLE(output, 1);
        writeIntLE(output, 0);
        output.write(0);
        output.write(0);
        align4(output);
        writeFloatLE(output, 512);
        for (int index = 0; index < 4; index++) writeIntLE(output, 0);
        writeIntLE(output, 0);
        writeIntLE(output, 0);
        output.write(1);
        align4(output);
        writeFloatLE(output, 1);
        writeIntLE(output, 0);
        writeLongLE(output, 0);
        align4(output);
    }

    private static int readInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void addString(
            Map<String, Integer> offsets,
            ByteArrayOutputStream output,
            String value
    ) {
        if (offsets.containsKey(value)) return;
        offsets.put(value, output.size());
        writeNullString(output, value);
    }

    private static void writeNullString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.write(bytes, 0, bytes.length);
        output.write(0);
    }

    private static void writeIntLE(ByteArrayOutputStream output, int value) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value).array();
        output.write(bytes, 0, bytes.length);
    }

    private static void writeFloatLE(ByteArrayOutputStream output, float value) {
        writeIntLE(output, Float.floatToIntBits(value));
    }

    private static void writeAlignedString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeIntLE(output, bytes.length);
        output.write(bytes, 0, bytes.length);
        align4(output);
    }

    private static void writeShortLE(ByteArrayOutputStream output, int value) {
        byte[] bytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short)value).array();
        output.write(bytes, 0, bytes.length);
    }

    private static void writeLongLE(ByteArrayOutputStream output, long value) {
        byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value).array();
        output.write(bytes, 0, bytes.length);
    }

    private static void align4(ByteArrayOutputStream output) {
        while ((output.size() & 3) != 0) output.write(0);
    }

    private static final class NodeRecord {
        final int level;
        final String type;
        final String name;
        final int byteSize;

        NodeRecord(int level, String type, String name, int byteSize) {
            this.level = level;
            this.type = type;
            this.name = name;
            this.byteSize = byteSize;
        }
    }
}
