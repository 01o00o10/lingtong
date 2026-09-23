/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** 兜底提供公开静态资源，支持首页、条件请求和单段字节范围。 */
final class DefaultResourceServlet extends HttpServlet {
  /** 资源目录及 JAR 覆盖层的统一视图。 */
  private final ServletResourceRoot resources;
  /** 单个静态资源允许读取的最大字节数。 */
  private final long maxResourceBytes;
  /** 目录请求依次尝试的首页文件名。 */
  private final List<String> welcomeFiles;

  DefaultResourceServlet(
      ServletResourceRoot resources, long maxResourceBytes, List<String> welcomeFiles) {
    this.resources = resources;
    this.maxResourceBytes = maxResourceBytes;
    this.welcomeFiles = Collections.unmodifiableList(new ArrayList<String>(welcomeFiles));
  }

  /** 校验公开路径和文件大小后，按条件头返回完整或单段响应。 */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    Path resource = resources.publicFile(path);
    if (resource != null && Files.isDirectory(resource)) {
      if (!path.endsWith("/")) {
        response.sendRedirect(request.getRequestURI() + "/");
        return;
      }
      resource = welcomeResource(path);
    }
    if (resource == null || !Files.isRegularFile(resource)) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    long length = Files.size(resource);
    if (length > maxResourceBytes || length > Integer.MAX_VALUE) {
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    long lastModified = Files.getLastModifiedTime(resource).toMillis();
    String etag = "W/\"" + Long.toHexString(length) + "-" + Long.toHexString(lastModified) + "\"";
    response.setHeader("etag", etag);
    response.setDateHeader("last-modified", lastModified);
    response.setHeader("accept-ranges", "bytes");
    String mimeType = resources.mimeType(resource.getFileName().toString());
    response.setContentType(mimeType == null ? "application/octet-stream" : mimeType);

    if (notModified(request, etag, lastModified)) {
      response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
      return;
    }

    ByteRange range = range(request, length, etag, lastModified);
    if (range == ByteRange.UNSATISFIABLE) {
      response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
      response.setHeader("content-range", "bytes */" + length);
      response.setContentLengthLong(0L);
      return;
    }
    byte[] content = Files.readAllBytes(resource);
    if (range == null) {
      response.setContentLengthLong(length);
      response.getOutputStream().write(content);
    } else {
      response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
      response.setHeader("content-range", "bytes " + range.start + "-" + range.end + "/" + length);
      response.setContentLengthLong(range.length());
      response.getOutputStream().write(content, (int) range.start, (int) range.length());
    }
  }

  /** 按声明顺序选取目录中首个可访问的首页文件。 */
  private Path welcomeResource(String directoryPath) {
    for (String welcomeFile : welcomeFiles) {
      String candidate = welcomeFile.startsWith("/") ? welcomeFile : directoryPath + welcomeFile;
      Path resource = resources.publicFile(candidate);
      if (resource != null && Files.isRegularFile(resource)) return resource;
    }
    return null;
  }

  /** If-None-Match 优先于 If-Modified-Since 判断缓存是否仍有效。 */
  private boolean notModified(HttpServletRequest request, String etag, long lastModified) {
    String ifNoneMatch = request.getHeader("if-none-match");
    if (ifNoneMatch != null) {
      for (String candidate : ifNoneMatch.split(",")) {
        if ("*".equals(candidate.trim()) || etag.equals(candidate.trim())) {
          return true;
        }
      }
      return false;
    }
    long ifModifiedSince = dateHeader(request.getHeader("if-modified-since"));
    return ifModifiedSince >= 0 && lastModified / 1000L <= ifModifiedSince / 1000L;
  }

  /** 仅接受单段 字节数 范围；不满足或格式错误返回哨兵值。 */
  private ByteRange range(HttpServletRequest request, long length, String etag, long lastModified) {
    String value = request.getHeader("range");
    if (value == null || !ifRangeMatches(request.getHeader("if-range"), etag, lastModified)) {
      return null;
    }
    if (!value.startsWith("bytes=") || value.indexOf(',') >= 0 || length == 0) {
      return ByteRange.UNSATISFIABLE;
    }
    String specification = value.substring(6).trim();
    int dash = specification.indexOf('-');
    if (dash < 0) {
      return ByteRange.UNSATISFIABLE;
    }
    try {
      if (dash == 0) {
        long suffix = Long.parseLong(specification.substring(1));
        if (suffix <= 0) return ByteRange.UNSATISFIABLE;
        return new ByteRange(Math.max(0L, length - suffix), length - 1L);
      }
      long start = Long.parseLong(specification.substring(0, dash));
      long end =
          dash == specification.length() - 1
              ? length - 1L
              : Long.parseLong(specification.substring(dash + 1));
      if (start < 0 || start >= length || end < start) return ByteRange.UNSATISFIABLE;
      return new ByteRange(start, Math.min(end, length - 1L));
    } catch (NumberFormatException e) {
      return ByteRange.UNSATISFIABLE;
    }
  }

  /** If-Range 不匹配时忽略 Range，改为返回完整资源。 */
  private boolean ifRangeMatches(String value, String etag, long lastModified) {
    if (value == null) return true;
    if (value.startsWith("\"") || value.startsWith("W/")) return etag.equals(value.trim());
    long date = dateHeader(value);
    return date >= 0 && lastModified / 1000L <= date / 1000L;
  }

  private long dateHeader(String value) {
    if (value == null) return -1L;
    try {
      return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
          .toInstant()
          .toEpochMilli();
    } catch (DateTimeParseException e) {
      return -1L;
    }
  }

  /** 闭区间字节范围；单例哨兵表示 416 响应。 */
  private static final class ByteRange {
    /** 无法满足 Range 请求时使用的哨兵。 */
    private static final ByteRange UNSATISFIABLE = new ByteRange(-1L, -1L);
    /** 包含在响应中的起始字节偏移。 */
    private final long start;
    /** 包含在响应中的末尾字节偏移。 */
    private final long end;

    private ByteRange(long start, long end) {
      this.start = start;
      this.end = end;
    }

    private long length() {
      return end - start + 1L;
    }
  }
}
