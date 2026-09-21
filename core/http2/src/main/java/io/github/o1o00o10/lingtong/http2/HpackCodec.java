/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http2;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** HPACK 静态表、动态表和 Huffman 编解码状态。 */
public final class HpackCodec {
    /** RFC 静态表的字段名，索引 0 不使用。 */
    private static final String[] NAMES = {
            null, ":authority", ":method", ":method", ":path", ":path", ":scheme", ":scheme",
            ":status", ":status", ":status", ":status", ":status", ":status", ":status",
            "accept-charset", "accept-encoding", "accept-language", "accept-ranges", "accept",
            "access-control-allow-origin", "age", "allow", "authorization", "cache-control",
            "content-disposition", "content-encoding", "content-language", "content-length",
            "content-location", "content-range", "content-type", "cookie", "date", "etag", "expect",
            "expires", "from", "host", "if-match", "if-modified-since", "if-none-match", "if-range",
            "if-unmodified-since", "last-modified", "link", "location", "max-forwards",
            "proxy-authenticate", "proxy-authorization", "range", "referer", "refresh", "retry-after",
            "server", "set-cookie", "strict-transport-security", "transfer-encoding", "user-agent",
            "vary", "via", "www-authenticate"
    };
    /** 与静态表字段名同索引的字段值。 */
    private static final String[] VALUES = {
            null, "", "GET", "POST", "/", "/index.html", "http", "https", "200", "204", "206",
            "304", "400", "404", "500", "", "gzip, deflate", "", "", "", "", "", "", "", "",
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""
    };

    /** 按最新在前顺序保存的动态表条目。 */
    private final List<HpackHeader> dynamic = new ArrayList<HpackHeader>();
    /** 当前动态表占用的 HPACK 计费字节数。 */
    private int dynamicBytes;
    /** 当前动态表最大容量。 */
    private int maxDynamicBytes = 4096;
    /** 对端允许设置的动态表容量上限。 */
    private int allowedDynamicBytes = 4096;
    /** 解压后完整头部列表的字节预算。 */
    private final int maxHeaderListBytes;

    public HpackCodec(int maxHeaderListBytes) {
        if (maxHeaderListBytes <= 0) throw new IllegalArgumentException("header list limit must be positive");
        this.maxHeaderListBytes = maxHeaderListBytes;
    }

    /** 解压一个完整头部块，同时更新此方向的动态表。 */
    public List<HpackHeader> decode(byte[] block) throws Http2Exception {
        List<HpackHeader> result = new ArrayList<HpackHeader>();
        Cursor cursor = new Cursor(block);
        int listBytes = 0;
        boolean fieldsStarted = false;
        while (cursor.hasRemaining()) {
            int first = cursor.peek();
            HpackHeader header;
            if ((first & 0x80) != 0) {
                int index = readInteger(cursor, 7);
                if (index == 0) throw compression("zero indexed field");
                header = indexed(index);
                fieldsStarted = true;
            } else if ((first & 0x40) != 0) {
                int nameIndex = readInteger(cursor, 6);
                String name = nameIndex == 0 ? readString(cursor) : indexed(nameIndex).name();
                header = new HpackHeader(name, readString(cursor));
                addDynamic(header);
                fieldsStarted = true;
            } else if ((first & 0x20) != 0) {
                if (fieldsStarted) throw compression("table size update after a field");
                int size = readInteger(cursor, 5);
                if (size > allowedDynamicBytes) throw compression("dynamic table size exceeds limit");
                maxDynamicBytes = size;
                evict();
                continue;
            } else {
                int nameIndex = readInteger(cursor, 4);
                String name = nameIndex == 0 ? readString(cursor) : indexed(nameIndex).name();
                header = new HpackHeader(name, readString(cursor));
                fieldsStarted = true;
            }
            listBytes += 32 + header.name().getBytes(StandardCharsets.UTF_8).length
                    + header.value().getBytes(StandardCharsets.UTF_8).length;
            if (listBytes > maxHeaderListBytes) throw compression("header list exceeds configured limit");
            result.add(header);
        }
        return result;
    }

    /** 将头部字段编码为一个 HPACK 块。 */
    public byte[] encode(List<HpackHeader> headers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (HpackHeader header : headers) {
            int exact = exactStaticIndex(header.name(), header.value());
            if (exact > 0) {
                writeInteger(out, exact, 7, 0x80);
            } else {
                int name = nameStaticIndex(header.name());
                writeInteger(out, name, 4, 0);
                if (name == 0) writeString(out, header.name());
                writeString(out, header.value());
            }
        }
        return out.toByteArray();
    }

    public void allowedDynamicBytes(int value) {
        if (value < 0) throw new IllegalArgumentException("dynamic table limit must not be negative");
        allowedDynamicBytes = value;
        if (maxDynamicBytes > value) {
            maxDynamicBytes = value;
            evict();
        }
    }

    private HpackHeader indexed(int index) throws Http2Exception {
        if (index < NAMES.length) return new HpackHeader(NAMES[index], VALUES[index]);
        int dynamicIndex = index - NAMES.length;
        if (dynamicIndex < 0 || dynamicIndex >= dynamic.size()) throw compression("invalid table index");
        return dynamic.get(dynamicIndex);
    }

    private void addDynamic(HpackHeader header) {
        int size = entrySize(header);
        if (size > maxDynamicBytes) {
            dynamic.clear();
            dynamicBytes = 0;
            return;
        }
        dynamic.add(0, header);
        dynamicBytes += size;
        evict();
    }

    private void evict() {
        while (dynamicBytes > maxDynamicBytes && !dynamic.isEmpty()) {
            dynamicBytes -= entrySize(dynamic.remove(dynamic.size() - 1));
        }
    }

    private static int entrySize(HpackHeader h) {
        return 32 + h.name().getBytes(StandardCharsets.UTF_8).length
                + h.value().getBytes(StandardCharsets.UTF_8).length;
    }

    private String readString(Cursor cursor) throws Http2Exception {
        boolean huffman = (cursor.peek() & 0x80) != 0;
        int length = readInteger(cursor, 7);
        byte[] value = cursor.read(length);
        byte[] decoded = huffman ? Huffman.decode(value) : value;
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static int readInteger(Cursor cursor, int prefix) throws Http2Exception {
        int first = cursor.readByte();
        int mask = (1 << prefix) - 1;
        long value = first & mask;
        if (value < mask) return (int) value;
        int shift = 0;
        while (true) {
            int next = cursor.readByte();
            if (shift > 28) throw compression("integer overflow");
            value += (long) (next & 0x7f) << shift;
            if (value > Integer.MAX_VALUE) throw compression("integer overflow");
            if ((next & 0x80) == 0) return (int) value;
            shift += 7;
        }
    }

    private static void writeInteger(ByteArrayOutputStream out, int value, int prefix, int marker) {
        int mask = (1 << prefix) - 1;
        if (value < mask) {
            out.write(marker | value);
            return;
        }
        out.write(marker | mask);
        value -= mask;
        while (value >= 128) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInteger(out, bytes.length, 7, 0);
        out.write(bytes, 0, bytes.length);
    }

    private static int exactStaticIndex(String name, String value) {
        for (int i = 1; i < NAMES.length; i++) {
            if (NAMES[i].equals(name) && VALUES[i].equals(value)) return i;
        }
        return 0;
    }

    private static int nameStaticIndex(String name) {
        for (int i = 1; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return 0;
    }

    private static Http2Exception compression(String message) {
        return new Http2Exception(9, 0, message);
    }

    /** 跟踪 HPACK 头块的读取偏移并执行边界检查。 */
    private static final class Cursor {
        /** 正在解析的完整 HPACK 块。 */
        private final byte[] bytes;
        /** 下一个未读取的字节位置。 */
        private int offset;
        private Cursor(byte[] bytes) { this.bytes = bytes; }
        private boolean hasRemaining() { return offset < bytes.length; }
        private int peek() throws Http2Exception {
            if (!hasRemaining()) throw compression("truncated header block");
            return bytes[offset] & 0xff;
        }
        private int readByte() throws Http2Exception {
            int value = peek(); offset++; return value;
        }
        private byte[] read(int length) throws Http2Exception {
            if (length < 0 || bytes.length - offset < length) throw compression("truncated string");
            byte[] result = new byte[length];
            System.arraycopy(bytes, offset, result, 0, length); offset += length; return result;
        }
    }

    /** HPACK 静态 Huffman 编解码表与位级处理。 */
    private static final class Huffman {
        /** HPACK Huffman 符号码字表。 */
        private static final int[] CODES = {
            0x1ff8,0x7fffd8,0xfffffe2,0xfffffe3,0xfffffe4,0xfffffe5,0xfffffe6,0xfffffe7,
            0xfffffe8,0xffffea,0x3ffffffc,0xfffffe9,0xfffffea,0x3ffffffd,0xfffffeb,0xfffffec,
            0xfffffed,0xfffffee,0xfffffef,0xffffff0,0xffffff1,0xffffff2,0x3ffffffe,0xffffff3,
            0xffffff4,0xffffff5,0xffffff6,0xffffff7,0xffffff8,0xffffff9,0xffffffa,0xffffffb,
            0x14,0x3f8,0x3f9,0xffa,0x1ff9,0x15,0xf8,0x7fa,0x3fa,0x3fb,0xf9,0x7fb,
            0xfa,0x16,0x17,0x18,0x0,0x1,0x2,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f,
            0x5c,0xfb,0x7ffc,0x20,0xffb,0x3fc,0x1ffa,0x21,0x5d,0x5e,0x5f,0x60,0x61,
            0x62,0x63,0x64,0x65,0x66,0x67,0x68,0x69,0x6a,0x6b,0x6c,0x6d,0x6e,0x6f,
            0x70,0x71,0x72,0xfc,0x73,0xfd,0x1ffb,0x7fff0,0x1ffc,0x3ffc,0x22,0x7ffd,
            0x3,0x23,0x4,0x24,0x5,0x25,0x26,0x27,0x6,0x74,0x75,0x28,0x29,0x2a,
            0x7,0x2b,0x76,0x2c,0x8,0x9,0x2d,0x77,0x78,0x79,0x7a,0x7b,0x7ffe,
            0x7fc,0x3ffd,0x1ffd,0xffffffc,0xfffe6,0x3fffd2,0xfffe7,0xfffe8,0x3fffd3,
            0x3fffd4,0x3fffd5,0x7fffd9,0x3fffd6,0x7fffda,0x7fffdb,0x7fffdc,0x7fffdd,
            0x7fffde,0xffffeb,0x7fffdf,0xffffec,0xffffed,0x3fffd7,0x7fffe0,0xffffee,
            0x7fffe1,0x7fffe2,0x7fffe3,0x7fffe4,0x1fffdc,0x3fffd8,0x7fffe5,0x3fffd9,
            0x7fffe6,0x7fffe7,0xffffef,0x3fffda,0x1fffdd,0xfffe9,0x3fffdb,0x3fffdc,
            0x7fffe8,0x7fffe9,0x1fffde,0x7fffea,0x3fffdd,0x3fffde,0xfffff0,0x1fffdf,
            0x3fffdf,0x7fffeb,0x7fffec,0x1fffe0,0x1fffe1,0x3fffe0,0x1fffe2,0x7fffed,
            0x3fffe1,0x7fffee,0x7fffef,0xfffea,0x3fffe2,0x3fffe3,0x3fffe4,0x7ffff0,
            0x3fffe5,0x3fffe6,0x7ffff1,0x3ffffe0,0x3ffffe1,0xfffeb,0x7fff1,0x3fffe7,
            0x7ffff2,0x3fffe8,0x1ffffec,0x3ffffe2,0x3ffffe3,0x3ffffe4,0x7ffffde,
            0x7ffffdf,0x3ffffe5,0xfffff1,0x1ffffed,0x7fff2,0x1fffe3,0x3ffffe6,
            0x7ffffe0,0x7ffffe1,0x3ffffe7,0x7ffffe2,0xfffff2,0x1fffe4,0x1fffe5,
            0x3ffffe8,0x3ffffe9,0xffffffd,0x7ffffe3,0x7ffffe4,0x7ffffe5,0xfffec,
            0xfffff3,0xfffed,0x1fffe6,0x3fffe9,0x1fffe7,0x1fffe8,0x7ffff3,0x3fffea,
            0x3fffeb,0x1ffffee,0x1ffffef,0xfffff4,0xfffff5,0x3ffffea,0x7ffff4,
            0x3ffffeb,0x7ffffe6,0x3ffffec,0x3ffffed,0x7ffffe7,0x7ffffe8,0x7ffffe9,
            0x7ffffea,0x7ffffeb,0xffffffe,0x7ffffec,0x7ffffed,0x7ffffee,0x7ffffef,
            0x7fffff0,0x3ffffee,0x3fffffff
        };
        /** 与码字表一一对应的有效位长度。 */
        private static final byte[] LENGTHS = {
            13,23,28,28,28,28,28,28,28,24,30,28,28,30,28,28,28,28,28,28,28,28,30,28,28,28,28,28,28,28,28,28,
            6,10,10,12,13,6,8,11,10,10,8,11,8,6,6,6,5,5,5,6,6,6,6,6,6,6,7,8,15,6,12,10,13,6,
            7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,8,7,8,13,19,13,14,6,15,5,6,5,6,5,6,6,6,
            5,7,7,6,6,6,5,6,7,6,5,5,6,7,7,7,7,7,15,11,14,13,28,20,22,20,20,22,22,22,23,22,23,23,
            23,23,23,24,23,24,24,22,23,24,23,23,23,23,21,22,23,22,23,23,24,22,21,20,22,22,23,23,21,
            23,22,22,24,21,22,23,23,21,21,22,21,23,22,23,23,20,22,22,22,23,22,22,23,26,26,20,19,22,
            23,22,25,26,26,26,27,27,26,24,25,19,21,26,27,27,26,27,24,21,21,26,26,28,27,27,27,20,24,
            20,21,22,21,21,23,22,22,25,25,24,24,26,23,26,27,26,26,27,27,27,27,27,28,27,27,27,27,27,26,30
        };
        /** 解码用的前缀树根节点。 */
        private static final Node ROOT = buildTree();

        private static byte[] decode(byte[] encoded) throws Http2Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length * 2);
            Node node = ROOT;
            int trailingBits = 0;
            boolean trailingOnes = true;
            for (byte octet : encoded) {
                for (int bit = 7; bit >= 0; bit--) {
                    int value = (octet >>> bit) & 1;
                    node = value == 0 ? node.zero : node.one;
                    trailingBits++;
                    trailingOnes &= value == 1;
                    if (node == null) throw compression("invalid Huffman code");
                    if (node.symbol >= 0) {
                        if (node.symbol == 256) throw compression("Huffman EOS in string");
                        out.write(node.symbol);
                        node = ROOT;
                        trailingBits = 0;
                        trailingOnes = true;
                    }
                }
            }
            if (node != ROOT && (trailingBits > 7 || !trailingOnes)) {
                throw compression("invalid Huffman padding");
            }
            return out.toByteArray();
        }

        private static Node buildTree() {
            Node root = new Node();
            for (int symbol = 0; symbol < CODES.length; symbol++) {
                Node node = root;
                int code = CODES[symbol];
                for (int bit = LENGTHS[symbol] - 1; bit >= 0; bit--) {
                    if (((code >>> bit) & 1) == 0) {
                        if (node.zero == null) node.zero = new Node();
                        node = node.zero;
                    } else {
                        if (node.one == null) node.one = new Node();
                        node = node.one;
                    }
                }
                node.symbol = symbol;
            }
            return root;
        }

        /** Huffman 解码树中的一个分支或终止符。 */
        private static final class Node {
            /** 当前位为 0 时的子节点。 */
            private Node zero;
            /** 当前位为 1 时的子节点。 */
            private Node one;
            /** 叶节点对应的符号，-1 表示内部节点。 */
            private int symbol = -1;
        }
    }
}
