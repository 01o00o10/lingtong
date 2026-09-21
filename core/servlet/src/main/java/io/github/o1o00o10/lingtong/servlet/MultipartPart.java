/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.ServletException;
import javax.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 保存单个上传分段的头部与内容，并实现 Servlet Part 的读取和删除语义。 */
final class MultipartPart implements Part {
    /** 按键索引的头部集合。 */
    private final Map<String, List<String>> headers;
    /** location。 */
    /** 位置（location）。 */
    private final Path location;
    /** 名称。 */
    private final String name;
    /** submitted文件名称。 */
    private final String submittedFileName;
    /** 内容类型。 */
    private final String contentType;
    /** 大小。 */
    private final long size;
    /** 内容。 */
    private byte[] content;
    /** 临时文件。 */
    private Path temporaryFile;
    /** deleted，布尔标志。 */
    private boolean deleted;

    MultipartPart(
            Map<String, List<String>> headers,
            byte[] source,
            int offset,
            int length,
            int threshold,
            Path location) throws IOException, ServletException {
        this.headers = headers;
        this.location = location;
        Map<String, String> disposition = contentDisposition(firstHeader("content-disposition"));
        if (!"form-data".equalsIgnoreCase(disposition.get("")) || !disposition.containsKey("name")) {
            throw new ServletException("multipart part has no form-data name");
        }
        this.name = disposition.get("name");
        this.submittedFileName = submittedFileName(disposition.get("filename"));
        this.contentType = firstHeader("content-type");
        this.size = length;
        if (threshold >= 0 && length > threshold) {
            Files.createDirectories(location);
            temporaryFile = Files.createTempFile(location, "lingtong-upload-", ".tmp");
            boolean written = false;
            try {
                OutputStream output = Files.newOutputStream(temporaryFile);
                try {
                    output.write(source, offset, length);
                } finally {
                    output.close();
                }
                written = true;
            } finally {
                if (!written) {
                    Files.deleteIfExists(temporaryFile);
                    temporaryFile = null;
                }
            }
        } else {
            content = java.util.Arrays.copyOfRange(source, offset, offset + length);
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        requireAvailable();
        return temporaryFile == null
                ? new ByteArrayInputStream(content)
                : Files.newInputStream(temporaryFile);
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getSubmittedFileName() {
        return submittedFileName;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void write(String fileName) throws IOException {
        requireAvailable();
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        Path target = location.resolve(fileName).normalize();
        if (!target.startsWith(location) || target.equals(location)) {
            throw new IOException("multipart target escapes the configured upload location");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (temporaryFile == null) {
            Files.write(target, content);
        } else {
            Files.copy(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete() throws IOException {
        if (!deleted) {
            deleted = true;
            content = null;
            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
                temporaryFile = null;
            }
        }
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(normalize(name));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        List<String> values = headers.get(normalize(name));
        return values == null ? Collections.<String>emptyList() : values;
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }

    void cleanup() {
        try {
            delete();
        } catch (IOException ignored) {
            // Request cleanup is best effort; explicit Part.delete still reports failures.
        }
    }

    private void requireAvailable() throws IOException {
        if (deleted) {
            throw new IOException("multipart part has been deleted");
        }
    }

    private String firstHeader(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Map<String, String> contentDisposition(String value) throws ServletException {
        if (value == null) {
            throw new ServletException("multipart part has no Content-Disposition header");
        }
        Map<String, String> result = new java.util.LinkedHashMap<String, String>();
        List<String> tokens = splitParameters(value);
        result.put("", tokens.get(0).trim());
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int equals = token.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = token.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String parameter = token.substring(equals + 1).trim();
            result.put(key, unquote(parameter));
        }
        return result;
    }

    private static List<String> splitParameters(String value) {
        List<String> result = new java.util.ArrayList<String>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                token.append(current);
                escaped = false;
            } else if (current == '\\' && quoted) {
                escaped = true;
                token.append(current);
            } else if (current == '"') {
                quoted = !quoted;
                token.append(current);
            } else if (current == ';' && !quoted) {
                result.add(token.toString());
                token.setLength(0);
            } else {
                token.append(current);
            }
        }
        result.add(token.toString());
        return result;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private static String submittedFileName(String value) {
        if (value == null) {
            return null;
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
