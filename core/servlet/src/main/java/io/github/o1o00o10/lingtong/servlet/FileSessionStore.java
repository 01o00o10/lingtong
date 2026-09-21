/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 单节点文件存储：每个 Session 一个文件，写入后替换目标文件。 */
public final class FileSessionStore implements SessionStore {
    /** 文件头魔数，用于识别 Session 记录。 */
    private static final int MAGIC = 0x4c545353;
    /** 当前文件格式版本。 */
    private static final int FORMAT = 1;
    /** Session 文件所在目录。 */
    private final Path directory;

    public FileSessionStore(Path directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("session directory is required");
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    /** 完整读取并校验文件头、载荷长度及尾部字节。 */
    @Override
    public synchronized SessionRecord load(String id) throws IOException {
        Path file = file(id);
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) {
                throw new IOException("unsupported session record format: " + file);
            }
            long version = input.readLong();
            long expiresAt = input.readLong();
            int length = input.readInt();
            if (length < 0 || length > 64 * 1024 * 1024) {
                throw new IOException("invalid session payload length");
            }
            byte[] payload = new byte[length];
            input.readFully(payload);
            if (input.read() != -1) throw new IOException("trailing bytes in session record");
            return new SessionRecord(id, version, expiresAt, payload);
        } catch (EOFException e) {
            throw new IOException("truncated session record: " + file, e);
        }
    }

    /** 在实例锁内检查版本并写临时文件，再尝试原子替换。 */
    @Override
    public synchronized long save(SessionRecord record, long expectedVersion) throws IOException {
        SessionRecord current = load(record.id());
        long currentVersion = current == null ? -1L : current.version();
        if (currentVersion != expectedVersion) return -1L;
        long nextVersion = expectedVersion + 1L;
        Path target = file(record.id());
        Path temporary = Files.createTempFile(directory, ".session-", ".tmp");
        boolean moved = false;
        try {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                byte[] payload = record.payload();
                output.writeInt(MAGIC);
                output.writeInt(FORMAT);
                output.writeLong(nextVersion);
                output.writeLong(record.expiresAtMillis());
                output.writeInt(payload.length);
                output.write(payload);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return nextVersion;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized boolean delete(String id, long expectedVersion) throws IOException {
        SessionRecord current = load(id);
        if (current == null) return false;
        if (expectedVersion >= 0L && current.version() != expectedVersion) return false;
        return Files.deleteIfExists(file(id));
    }

    @Override
    public synchronized long size() throws IOException {
        long count = 0L;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.session")) {
            for (Path ignored : files) count++;
        }
        return count;
    }

    @Override
    public synchronized long purgeExpired(long nowMillis) throws IOException {
        long removed = 0L;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.session")) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - ".session".length());
                SessionRecord record = load(id);
                if (record != null && record.expiresAtMillis() <= nowMillis
                        && Files.deleteIfExists(path)) removed++;
            }
        }
        return removed;
    }

    @Override public void close() { }

    /** 先校验 ID 字符集，再映射为目录内文件名。 */
    private Path file(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("invalid session id");
        }
        return directory.resolve(id + ".session");
    }
}
