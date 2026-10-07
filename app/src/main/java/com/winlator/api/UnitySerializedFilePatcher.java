package com.winlator.api;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class UnitySerializedFilePatcher {
    private static final int MIN_SUPPORTED_VERSION = 17;
    private static final int MAX_SUPPORTED_VERSION = 22;
    private static final int QUALITY_SETTINGS_CLASS_ID = 47;
    private static final Pattern SUPPORTED_UNITY_VERSION = Pattern.compile(
            "^(2019\\.4|2020\\.3|2021\\.3|2022\\.3)\\.\\d+[A-Za-z]\\d+.*$"
    );
    private static final int ALIGN_BYTES_META_FLAG = 0x4000;
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TYPES = 4096;
    private static final int MAX_OBJECTS = 1_000_000;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_ARRAY_ELEMENTS = 1_000_000;
    private static final int MAX_RECURSION_DEPTH = 128;
    private static final Map<Integer, String> COMMON_STRINGS = commonStrings();

    private static final class QualityFields {
        final Header header;
        final List<Integer> offsets;
        final List<Integer> values;

        QualityFields(Header header, List<Integer> offsets, List<Integer> values) {
            this.header = header;
            this.offsets = offsets;
            this.values = values;
        }
    }

    static final class PatchResult {
        final byte[] data;
        final int serializedFileVersion;
        final String unityVersion;
        final List<Integer> previousLimits;
        final List<Integer> patchedOffsets;

        PatchResult(
                byte[] data,
                int serializedFileVersion,
                String unityVersion,
                List<Integer> previousLimits,
                List<Integer> patchedOffsets
        ) {
            this.data = data;
            this.serializedFileVersion = serializedFileVersion;
            this.unityVersion = unityVersion;
            this.previousLimits = previousLimits;
            this.patchedOffsets = patchedOffsets;
        }
    }

    static final class UnsupportedFormatException extends IOException {
        UnsupportedFormatException(String message) {
            super(message);
        }
    }

    private static final class Header {
        int version;
        long dataOffset;
        ByteOrder order;
        String unityVersion;
        List<SerializedType> types;
        List<SerializedObject> objects;
    }

    private static final class SerializedType {
        final int classId;
        final Node root;

        SerializedType(int classId, Node root) {
            this.classId = classId;
            this.root = root;
        }
    }

    private static final class SerializedObject {
        final int classId;
        final int byteStart;
        final int byteSize;
        final Node root;

        SerializedObject(int classId, int byteStart, int byteSize, Node root) {
            this.classId = classId;
            this.byteStart = byteStart;
            this.byteSize = byteSize;
            this.root = root;
        }
    }

    private static final class Node {
        final int level;
        final String type;
        final String name;
        final int byteSize;
        final int metaFlag;
        final List<Node> children = new ArrayList<>();

        Node(int level, String type, String name, int byteSize, int metaFlag) {
            this.level = level;
            this.type = type;
            this.name = name;
            this.byteSize = byteSize;
            this.metaFlag = metaFlag;
        }

        boolean aligned() {
            return (metaFlag & ALIGN_BYTES_META_FLAG) != 0;
        }
    }

    private static final class Cursor {
        final byte[] data;
        int position;
        ByteOrder order;

        Cursor(byte[] data, int position, ByteOrder order) {
            this.data = data;
            this.position = position;
            this.order = order;
        }

        void require(int count) throws IOException {
            if (count < 0 || position < 0 || position > data.length - count) {
                throw new IOException("Unity serialized data is truncated at "+position);
            }
        }

        int readUnsignedByte() throws IOException {
            require(1);
            return data[position++] & 0xff;
        }

        boolean readBoolean() throws IOException {
            return readUnsignedByte() != 0;
        }

        short readShort() throws IOException {
            require(2);
            short value = ByteBuffer.wrap(data, position, 2).order(order).getShort();
            position += 2;
            return value;
        }

        int readInt() throws IOException {
            require(4);
            int value = ByteBuffer.wrap(data, position, 4).order(order).getInt();
            position += 4;
            return value;
        }

        long readUnsignedInt() throws IOException {
            return Integer.toUnsignedLong(readInt());
        }

        long readLong() throws IOException {
            require(8);
            long value = ByteBuffer.wrap(data, position, 8).order(order).getLong();
            position += 8;
            return value;
        }

        void skip(long count) throws IOException {
            if (count < 0 || count > Integer.MAX_VALUE) {
                throw new IOException("Invalid Unity skip length "+count);
            }
            require((int)count);
            position += (int)count;
        }

        void align4() throws IOException {
            int aligned = (position + 3) & ~3;
            skip(aligned - position);
        }

        String readNullString() throws IOException {
            int start = position;
            while (true) {
                require(1);
                if (data[position++] == 0) {
                    return new String(
                            data,
                            start,
                            position - start - 1,
                            StandardCharsets.UTF_8
                    );
                }
            }
        }
    }

    private UnitySerializedFilePatcher() {
    }

    static PatchResult patchTextureQuality(File source, int textureLimit)
            throws IOException {
        if (textureLimit < 1 || textureLimit > 3) {
            throw new IllegalArgumentException("Unity texture limit must be 1, 2, or 3.");
        }
        long length = source.length();
        if (!source.isFile() || length <= 0 || length > MAX_FILE_BYTES) {
            throw new UnsupportedFormatException(
                    "Unity globalgamemanagers is missing or exceeds "
                            +MAX_FILE_BYTES+" bytes."
            );
        }
        byte[] data = Files.readAllBytes(source.toPath());
        QualityFields original = findQualityFields(data);
        Header header = original.header;
        List<Integer> offsets = original.offsets;
        List<Integer> previous = original.values;
        if (offsets.isEmpty()) {
            throw new UnsupportedFormatException(
                    "Unity QualitySettings textureQuality fields were not found."
            );
        }
        byte[] patched = data.clone();
        for (int offset : offsets) {
            ByteBuffer.wrap(patched, offset, 4).order(header.order).putInt(textureLimit);
        }
        QualityFields verified = findQualityFields(patched);
        if (!offsets.equals(verified.offsets)) {
            throw new IOException("Unity texture-limit patch changed the serialized layout.");
        }
        for (int value : verified.values) {
            if (value != textureLimit) {
                throw new IOException("Unity texture-limit patch did not verify.");
            }
        }
        return new PatchResult(
                patched,
                header.version,
                header.unityVersion,
                Collections.unmodifiableList(previous),
                Collections.unmodifiableList(offsets)
        );
    }

    private static QualityFields findQualityFields(byte[] data) throws IOException {
        Header header = parseHeader(data);
        List<Integer> offsets = new ArrayList<>();
        List<Integer> previous = new ArrayList<>();
        for (SerializedObject object : header.objects) {
            if (object.classId != QUALITY_SETTINGS_CLASS_ID || object.root == null) continue;
            Cursor objectReader = new Cursor(data, object.byteStart, header.order);
            int objectEnd = object.byteStart + object.byteSize;
            walkValue(object.root, objectReader, objectEnd, offsets, previous, 0);
            if (objectReader.position > objectEnd) {
                throw new IOException("QualitySettings extends beyond its object boundary.");
            }
        }
        return new QualityFields(header, offsets, previous);
    }

    private static Header parseHeader(byte[] data) throws IOException {
        Cursor reader = new Cursor(data, 0, ByteOrder.BIG_ENDIAN);
        reader.readUnsignedInt();
        reader.readUnsignedInt();
        int version = reader.readInt();
        reader.readUnsignedInt();
        if (version < MIN_SUPPORTED_VERSION || version > MAX_SUPPORTED_VERSION) {
            throw new UnsupportedFormatException(
                    "Unsupported Unity SerializedFile version "+version+"."
            );
        }
        int endianFlag = reader.readUnsignedByte();
        if (endianFlag != 0) {
            throw new UnsupportedFormatException(
                    "Big-endian Unity SerializedFiles are unsupported."
            );
        }
        reader.skip(3);
        long dataOffset;
        if (version >= 22) {
            reader.readUnsignedInt();
            reader.readLong();
            dataOffset = reader.readLong();
            reader.readLong();
        }
        else {
            Cursor initial = new Cursor(data, 0, ByteOrder.BIG_ENDIAN);
            initial.readUnsignedInt();
            initial.readUnsignedInt();
            initial.readInt();
            dataOffset = initial.readUnsignedInt();
        }
        if (dataOffset < reader.position || dataOffset > data.length) {
            throw new IOException("Invalid Unity data offset "+dataOffset+".");
        }
        reader.order = ByteOrder.LITTLE_ENDIAN;
        String unityVersion = reader.readNullString();
        if (!isSupportedUnityVersion(unityVersion)) {
            throw new UnsupportedFormatException(
                    "Unsupported Unity version "+unityVersion
                            +"; supported releases are 2019.4, 2020.3, 2021.3, and 2022.3 LTS."
            );
        }
        reader.readInt();
        boolean hasTypeTree = reader.readBoolean();
        if (!hasTypeTree) {
            throw new UnsupportedFormatException(
                    "Unity SerializedFile has no embedded type tree; "
                            +"texture limiting is unavailable for this build."
            );
        }

        int typeCount = boundedCount(reader.readInt(), MAX_TYPES, "type");
        List<SerializedType> types = new ArrayList<>(typeCount);
        for (int index = 0; index < typeCount; index++) {
            int classId = reader.readInt();
            reader.readBoolean();
            short scriptTypeIndex = reader.readShort();
            if ((version >= 16 && classId == 114) || scriptTypeIndex >= 0 && classId < 0) {
                reader.skip(16);
            }
            reader.skip(16);
            Node root = parseTypeTree(reader, version);
            if (version >= 21) {
                int dependencyCount =
                        boundedCount(reader.readInt(), MAX_TYPES, "type dependency");
                reader.skip((long)dependencyCount * 4);
            }
            types.add(new SerializedType(classId, root));
        }

        int objectCount = boundedCount(reader.readInt(), MAX_OBJECTS, "object");
        List<SerializedObject> objects = new ArrayList<>(objectCount);
        for (int index = 0; index < objectCount; index++) {
            reader.align4();
            reader.readLong();
            long relativeStart =
                    version >= 22 ? reader.readLong() : reader.readUnsignedInt();
            long absoluteStart = dataOffset + relativeStart;
            long byteSize = reader.readUnsignedInt();
            int typeId = reader.readInt();
            if (typeId < 0 || typeId >= types.size()) {
                throw new IOException("Invalid Unity object type index "+typeId+".");
            }
            if (absoluteStart < 0 || absoluteStart > data.length ||
                    byteSize > data.length - absoluteStart) {
                throw new IOException("Unity object extends beyond the serialized file.");
            }
            SerializedType type = types.get(typeId);
            objects.add(new SerializedObject(
                    type.classId,
                    (int)absoluteStart,
                    (int)byteSize,
                    type.root
            ));
        }

        Header header = new Header();
        header.version = version;
        header.dataOffset = dataOffset;
        header.order = ByteOrder.LITTLE_ENDIAN;
        header.unityVersion = unityVersion;
        header.types = types;
        header.objects = objects;
        return header;
    }

    private static Node parseTypeTree(Cursor reader, int version) throws IOException {
        int nodeCount = boundedCount(reader.readInt(), MAX_NODES, "type-tree node");
        int stringBufferSize =
                boundedCount(reader.readInt(), MAX_FILE_BYTES, "type-tree string buffer");
        int nodeSize = version >= 19 ? 32 : 24;
        long nodesBytes = (long)nodeCount * nodeSize;
        if (nodesBytes > Integer.MAX_VALUE) {
            throw new IOException("Unity type-tree node table is too large.");
        }
        int nodesStart = reader.position;
        reader.skip(nodesBytes);
        int stringsStart = reader.position;
        reader.skip(stringBufferSize);

        Cursor nodes = new Cursor(reader.data, nodesStart, reader.order);
        List<Node> flat = new ArrayList<>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            nodes.readShort();
            int level = nodes.readUnsignedByte();
            nodes.readUnsignedByte();
            long typeOffset = nodes.readUnsignedInt();
            long nameOffset = nodes.readUnsignedInt();
            int byteSize = nodes.readInt();
            nodes.readInt();
            int metaFlag = nodes.readInt();
            if (version >= 19) nodes.readLong();
            flat.add(new Node(
                    level,
                    resolveTypeTreeString(reader.data, stringsStart, stringBufferSize, typeOffset),
                    resolveTypeTreeString(reader.data, stringsStart, stringBufferSize, nameOffset),
                    byteSize,
                    metaFlag
            ));
        }
        if (flat.isEmpty() || flat.get(0).level != 0) {
            throw new IOException("Unity type tree has no root node.");
        }
        List<Node> stack = new ArrayList<>();
        Node root = null;
        for (Node node : flat) {
            while (stack.size() > node.level) stack.remove(stack.size() - 1);
            if (node.level == 0) {
                if (root != null) throw new IOException("Unity type tree has multiple roots.");
                root = node;
            }
            else {
                if (stack.size() != node.level) {
                    throw new IOException("Unity type-tree nesting is malformed.");
                }
                stack.get(node.level - 1).children.add(node);
            }
            stack.add(node);
        }
        return root;
    }

    private static String resolveTypeTreeString(
            byte[] data,
            int stringsStart,
            int stringsSize,
            long value
    ) throws IOException {
        if ((value & 0x80000000L) != 0) {
            int commonOffset = (int)(value & 0x7fffffffL);
            String common = COMMON_STRINGS.get(commonOffset);
            return common != null ? common : "common_"+commonOffset;
        }
        if (value < 0 || value >= stringsSize) {
            throw new IOException("Invalid Unity type-tree string offset "+value+".");
        }
        int position = stringsStart + (int)value;
        int limit = stringsStart + stringsSize;
        int end = position;
        while (end < limit && data[end] != 0) end++;
        if (end == limit) throw new IOException("Unterminated Unity type-tree string.");
        return new String(data, position, end - position, StandardCharsets.UTF_8);
    }

    private static void walkValue(
            Node node,
            Cursor reader,
            int objectEnd,
            List<Integer> offsets,
            List<Integer> previous,
            int depth
    ) throws IOException {
        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException("Unity type tree exceeds the recursion limit.");
        }
        int primitiveSize = primitiveSize(node.type);
        if ("string".equals(node.type)) {
            int size = boundedCount(reader.readInt(), objectEnd - reader.position, "string");
            reader.skip(size);
            reader.align4();
        }
        else if ("TypelessData".equals(node.type)) {
            int size = boundedCount(
                    reader.readInt(),
                    objectEnd - reader.position,
                    "typeless data"
            );
            reader.skip(size);
        }
        else if (primitiveSize > 0) {
            if (("textureQuality".equals(node.name)
                    || "globalTextureMipmapLimit".equals(node.name)) &&
                    ("int".equals(node.type) || "SInt32".equals(node.type))) {
                int offset = reader.position;
                int current = reader.readInt();
                if (current < 0 || current > 3) {
                    throw new UnsupportedFormatException(
                            "Unity textureQuality value is outside 0..3."
                    );
                }
                offsets.add(offset);
                previous.add(current);
            }
            else {
                reader.skip(primitiveSize);
            }
        }
        else if (!node.children.isEmpty() && "Array".equals(node.children.get(0).type)) {
            Node array = node.children.get(0);
            if (array.children.size() < 2) {
                throw new IOException("Unity array type tree is incomplete.");
            }
            int count = boundedCount(
                    reader.readInt(),
                    MAX_ARRAY_ELEMENTS,
                    "array"
            );
            Node dataNode = array.children.get(1);
            for (int index = 0; index < count; index++) {
                walkValue(dataNode, reader, objectEnd, offsets, previous, depth + 1);
            }
            if (array.aligned()) reader.align4();
        }
        else {
            if (node.children.isEmpty() && node.byteSize > 0) {
                reader.skip(node.byteSize);
            }
            else {
                for (Node child : node.children) {
                    walkValue(child, reader, objectEnd, offsets, previous, depth + 1);
                }
            }
        }
        if (reader.position > objectEnd) {
            throw new IOException("Unity type-tree value extends beyond its object.");
        }
        if (node.aligned()) reader.align4();
    }

    private static int primitiveSize(String type) {
        switch (type) {
            case "SInt8":
            case "UInt8":
            case "char":
            case "bool":
                return 1;
            case "short":
            case "SInt16":
            case "unsigned short":
            case "UInt16":
                return 2;
            case "int":
            case "SInt32":
            case "unsigned int":
            case "UInt32":
            case "Type*":
            case "float":
                return 4;
            case "long long":
            case "SInt64":
            case "unsigned long long":
            case "UInt64":
            case "FileSize":
            case "double":
                return 8;
            default:
                return -1;
        }
    }

    private static int boundedCount(int count, int max, String label) throws IOException {
        if (count < 0 || count > max) {
            throw new IOException("Invalid Unity "+label+" count "+count+".");
        }
        return count;
    }

    private static boolean isSupportedUnityVersion(String value) {
        return value != null && SUPPORTED_UNITY_VERSION.matcher(value).matches();
    }

    private static Map<Integer, String> commonStrings() {
        Map<Integer, String> values = new HashMap<>();
        values.put(49, "Array");
        values.put(76, "bool");
        values.put(81, "char");
        values.put(106, "data");
        values.put(117, "double");
        values.put(161, "float");
        values.put(222, "int");
        values.put(231, "long long");
        values.put(241, "map");
        values.put(543, "pair");
        values.put(789, "short");
        values.put(795, "size");
        values.put(800, "SInt16");
        values.put(807, "SInt32");
        values.put(814, "SInt64");
        values.put(821, "SInt8");
        values.put(840, "string");
        values.put(894, "TypelessData");
        values.put(907, "UInt16");
        values.put(914, "UInt32");
        values.put(921, "UInt64");
        values.put(928, "UInt8");
        values.put(934, "unsigned int");
        values.put(947, "unsigned long long");
        values.put(966, "unsigned short");
        values.put(981, "vector");
        values.put(1051, "Type*");
        return Collections.unmodifiableMap(values);
    }
}
