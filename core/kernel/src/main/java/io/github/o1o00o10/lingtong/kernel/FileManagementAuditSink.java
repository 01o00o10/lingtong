/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** JSON-lines audit sink with bounded size and deterministic numbered rotation. */
/** 将已脱敏的管理事件写入有大小上限、按序号轮转的文件。 */
public final class FileManagementAuditSink implements ManagementAuditSink {
    /** 默认最大字节数，单位为字节。 */
    public static final long DEFAULT_MAX_BYTES = 16L * 1024L * 1024L;
    /** 默认保留的文件列表。 */
    public static final int DEFAULT_RETAINED_FILES = 5;

    /** 文件。 */
    private final Path file;
    /** 最大字节数，单位为字节。 */
    private final long maxBytes;
    /** 保留的文件列表。 */
    private final int retainedFiles;

    public FileManagementAuditSink(Path file) throws IOException {
        this(file, DEFAULT_MAX_BYTES, DEFAULT_RETAINED_FILES);
    }

    public FileManagementAuditSink(Path file, long maxBytes, int retainedFiles) throws IOException {
        if (file == null || maxBytes < 1024L || retainedFiles <= 0 || retainedFiles > 100) {
            throw new IllegalArgumentException("invalid management audit file configuration");
        }
        this.file = file.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.retainedFiles = retainedFiles;
        Files.createDirectories(this.file.getParent());
    }

    @Override
    public synchronized void append(ManagementAuditEvent event) throws IOException {
        if (event == null) throw new IllegalArgumentException("audit event must not be null");
        byte[] line = json(event).getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(file) && Files.size(file) + line.length > maxBytes) rotate();
        Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override public void close() { }

    private void rotate() throws IOException {
        for (int index = retainedFiles; index >= 1; index--) {
            Path source = index == 1 ? file : rotated(index - 1);
            if (!Files.exists(source)) continue;
            if (index == retainedFiles) Files.deleteIfExists(rotated(index));
            Files.move(source, rotated(index), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path rotated(int index) {
        return file.resolveSibling(file.getFileName().toString() + "." + index);
    }

    private static String json(ManagementAuditEvent event) {
        return "{\"timestampMillis\":" + event.timestampMillis()
                + ",\"remoteAddress\":\"" + escape(event.remoteAddress())
                + "\",\"role\":\"" + escape(event.role())
                + "\",\"method\":\"" + escape(event.method())
                + "\",\"path\":\"" + escape(event.path())
                + "\",\"status\":" + event.status()
                + ",\"outcome\":\"" + escape(event.outcome()) + "\"}\n";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }
}
