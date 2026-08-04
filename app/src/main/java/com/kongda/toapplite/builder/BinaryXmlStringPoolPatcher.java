package com.kongda.toapplite.builder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds the string-pool chunk in a binary Android XML file while preserving
 * all string indices. This lets the builder replace the manifest package and
 * application label without bundling aapt/apktool on the phone.
 */
final class BinaryXmlStringPoolPatcher {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int UTF8_FLAG = 0x00000100;

    private BinaryXmlStringPoolPatcher() {
    }

    static byte[] replaceExactStrings(byte[] xml, Map<String, String> replacements) throws IOException {
        if (xml.length < 8 || u16(xml, 0) != RES_XML_TYPE) {
            throw new IOException("AndroidManifest.xml 不是有效的二进制 XML");
        }

        int rootHeaderSize = u16(xml, 2);
        int declaredSize = u32(xml, 4);
        int limit = Math.min(declaredSize, xml.length);
        int cursor = rootHeaderSize;

        while (cursor + 8 <= limit) {
            int type = u16(xml, cursor);
            int headerSize = u16(xml, cursor + 2);
            int chunkSize = u32(xml, cursor + 4);
            if (headerSize < 8 || chunkSize < headerSize || cursor + chunkSize > limit) {
                throw new IOException("二进制 XML 块损坏");
            }
            if (type == RES_STRING_POOL_TYPE) {
                byte[] newPool = rebuildStringPool(xml, cursor, chunkSize, headerSize, replacements);
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        xml.length - chunkSize + newPool.length
                );
                output.write(xml, 0, cursor);
                output.write(newPool);
                output.write(xml, cursor + chunkSize, xml.length - cursor - chunkSize);
                byte[] patched = output.toByteArray();
                putU32(patched, 4, patched.length);
                return patched;
            }
            cursor += chunkSize;
        }
        throw new IOException("未找到 Android XML 字符串池");
    }

    private static byte[] rebuildStringPool(
            byte[] xml,
            int chunkOffset,
            int oldChunkSize,
            int headerSize,
            Map<String, String> replacements
    ) throws IOException {
        if (headerSize < 28) {
            throw new IOException("字符串池头部异常");
        }

        int stringCount = u32(xml, chunkOffset + 8);
        int styleCount = u32(xml, chunkOffset + 12);
        int flags = u32(xml, chunkOffset + 16);
        int stringsStart = u32(xml, chunkOffset + 20);
        int stylesStart = u32(xml, chunkOffset + 24);
        boolean utf8 = (flags & UTF8_FLAG) != 0;

        int offsetsStart = chunkOffset + headerSize;
        int styleOffsetsStart = offsetsStart + stringCount * 4;
        int stringsBase = chunkOffset + stringsStart;
        int stringsEnd = stylesStart == 0 ? chunkOffset + oldChunkSize : chunkOffset + stylesStart;

        if (stringCount < 0 || offsetsStart < 0 || styleOffsetsStart < offsetsStart
                || stringsBase < styleOffsetsStart + styleCount * 4
                || stringsEnd < stringsBase || stringsEnd > chunkOffset + oldChunkSize) {
            throw new IOException("字符串池偏移异常");
        }

        List<String> strings = new ArrayList<>(stringCount);
        Set<String> foundKeys = new HashSet<>();
        for (int i = 0; i < stringCount; i++) {
            int relativeOffset = u32(xml, offsetsStart + i * 4);
            int absoluteOffset = stringsBase + relativeOffset;
            if (absoluteOffset < stringsBase || absoluteOffset >= stringsEnd) {
                throw new IOException("字符串偏移越界");
            }
            String original = utf8
                    ? decodeUtf8(xml, absoluteOffset, stringsEnd)
                    : decodeUtf16(xml, absoluteOffset, stringsEnd);
            String replacement = replacements.get(original);
            if (replacement != null) {
                strings.add(replacement);
                foundKeys.add(original);
            } else {
                strings.add(original);
            }
        }

        if (!foundKeys.containsAll(replacements.keySet())) {
            throw new IOException("模板占位符不完整，拒绝生成不确定的 APK");
        }

        ByteArrayOutputStream stringData = new ByteArrayOutputStream();
        int[] newStringOffsets = new int[stringCount];
        for (int i = 0; i < strings.size(); i++) {
            newStringOffsets[i] = stringData.size();
            if (utf8) {
                encodeUtf8(stringData, strings.get(i));
            } else {
                encodeUtf16(stringData, strings.get(i));
            }
        }
        while ((stringData.size() & 3) != 0) {
            stringData.write(0);
        }

        byte[] styleData = new byte[0];
        if (stylesStart != 0) {
            int styleDataOffset = chunkOffset + stylesStart;
            styleData = new byte[chunkOffset + oldChunkSize - styleDataOffset];
            System.arraycopy(xml, styleDataOffset, styleData, 0, styleData.length);
        }

        int newStringsStart = headerSize + stringCount * 4 + styleCount * 4;
        int newStylesStart = stylesStart == 0 ? 0 : newStringsStart + stringData.size();
        int newChunkSize = newStringsStart + stringData.size() + styleData.length;

        byte[] rebuilt = new byte[newChunkSize];
        System.arraycopy(xml, chunkOffset, rebuilt, 0, headerSize);
        putU32(rebuilt, 4, newChunkSize);
        putU32(rebuilt, 20, newStringsStart);
        putU32(rebuilt, 24, newStylesStart);

        for (int i = 0; i < stringCount; i++) {
            putU32(rebuilt, headerSize + i * 4, newStringOffsets[i]);
        }
        if (styleCount > 0) {
            System.arraycopy(
                    xml,
                    styleOffsetsStart,
                    rebuilt,
                    headerSize + stringCount * 4,
                    styleCount * 4
            );
        }

        byte[] encodedStrings = stringData.toByteArray();
        System.arraycopy(encodedStrings, 0, rebuilt, newStringsStart, encodedStrings.length);
        if (styleData.length > 0) {
            System.arraycopy(styleData, 0, rebuilt, newStylesStart, styleData.length);
        }
        return rebuilt;
    }

    private static String decodeUtf8(byte[] data, int offset, int limit) throws IOException {
        Length utf16Length = readUtf8Length(data, offset, limit);
        Length byteLength = readUtf8Length(data, utf16Length.nextOffset, limit);
        int start = byteLength.nextOffset;
        int end = start + byteLength.value;
        if (end >= limit || data[end] != 0) {
            throw new IOException("UTF-8 字符串损坏");
        }
        return new String(data, start, byteLength.value, StandardCharsets.UTF_8);
    }

    private static String decodeUtf16(byte[] data, int offset, int limit) throws IOException {
        Length length = readUtf16Length(data, offset, limit);
        int byteLength = Math.multiplyExact(length.value, 2);
        int end = length.nextOffset + byteLength;
        if (end + 1 >= limit || data[end] != 0 || data[end + 1] != 0) {
            throw new IOException("UTF-16 字符串损坏");
        }
        return new String(data, length.nextOffset, byteLength, StandardCharsets.UTF_16LE);
    }

    private static Length readUtf8Length(byte[] data, int offset, int limit) throws IOException {
        if (offset >= limit) {
            throw new IOException("字符串长度越界");
        }
        int first = data[offset] & 0xff;
        if ((first & 0x80) == 0) {
            return new Length(first, offset + 1);
        }
        if (offset + 1 >= limit) {
            throw new IOException("字符串长度越界");
        }
        int value = ((first & 0x7f) << 8) | (data[offset + 1] & 0xff);
        return new Length(value, offset + 2);
    }

    private static Length readUtf16Length(byte[] data, int offset, int limit) throws IOException {
        if (offset + 1 >= limit) {
            throw new IOException("字符串长度越界");
        }
        int first = u16(data, offset);
        if ((first & 0x8000) == 0) {
            return new Length(first, offset + 2);
        }
        if (offset + 3 >= limit) {
            throw new IOException("字符串长度越界");
        }
        int second = u16(data, offset + 2);
        int value = ((first & 0x7fff) << 16) | second;
        return new Length(value, offset + 4);
    }

    private static void encodeUtf8(ByteArrayOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUtf8Length(output, value.length());
        writeUtf8Length(output, bytes.length);
        output.write(bytes);
        output.write(0);
    }

    private static void encodeUtf16(ByteArrayOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
        writeUtf16Length(output, value.length());
        output.write(bytes);
        output.write(0);
        output.write(0);
    }

    private static void writeUtf8Length(ByteArrayOutputStream output, int value) throws IOException {
        if (value < 0 || value > 0x7fff) {
            throw new IOException("字符串过长");
        }
        if (value <= 0x7f) {
            output.write(value);
        } else {
            output.write(((value >> 8) & 0x7f) | 0x80);
            output.write(value & 0xff);
        }
    }

    private static void writeUtf16Length(ByteArrayOutputStream output, int value) throws IOException {
        if (value < 0) {
            throw new IOException("字符串长度异常");
        }
        if (value <= 0x7fff) {
            writeLe16(output, value);
        } else {
            writeLe16(output, ((value >> 16) & 0x7fff) | 0x8000);
            writeLe16(output, value & 0xffff);
        }
    }

    private static void writeLe16(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void putU32(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private static final class Length {
        final int value;
        final int nextOffset;

        Length(int value, int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }
}
