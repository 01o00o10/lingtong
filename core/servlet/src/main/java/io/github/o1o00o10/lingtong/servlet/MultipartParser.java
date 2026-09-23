/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

/** 在请求大小限制内解析 multipart/form-data，生成可由 Servlet 访问的 Part。 */
final class MultipartParser {
  /** 头部结束。 */
  private static final byte[] HEADER_END = new byte[] {'\r', '\n', '\r', '\n'};

  private MultipartParser() {}

  static List<MultipartPart> parse(
      byte[] body, String contentType, MultipartConfigElement config, ServletContext servletContext)
      throws IOException, ServletException {
    String boundary = boundary(contentType);
    if (config.getMaxRequestSize() >= 0 && body.length > config.getMaxRequestSize()) {
      throw new IllegalStateException("multipart request exceeds configured maximum size");
    }
    Path location = uploadLocation(config, servletContext);
    byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
    byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
    if (!matches(body, 0, delimiter)) {
      throw new ServletException("multipart body does not start with its declared boundary");
    }

    List<MultipartPart> parts = new ArrayList<MultipartPart>();
    int position = delimiter.length;
    try {
      while (true) {
        if (matches(body, position, new byte[] {'-', '-'})) {
          position += 2;
          if (position == body.length || matches(body, position, new byte[] {'\r', '\n'})) {
            return parts;
          }
          throw new ServletException("invalid multipart closing boundary");
        }
        requireCrlf(body, position);
        position += 2;
        int headerEnd = indexOf(body, HEADER_END, position);
        if (headerEnd < 0) {
          throw new ServletException("multipart part headers are incomplete");
        }
        Map<String, List<String>> headers = parseHeaders(body, position, headerEnd);
        int contentStart = headerEnd + HEADER_END.length;
        int contentEnd = nextDelimiter(body, nextDelimiter, delimiter.length, contentStart);
        if (contentEnd < 0) {
          throw new ServletException("multipart part has no closing boundary");
        }
        int size = contentEnd - contentStart;
        MultipartPart part =
            new MultipartPart(
                headers, body, contentStart, size, config.getFileSizeThreshold(), location);
        if (part.getSubmittedFileName() != null
            && config.getMaxFileSize() >= 0
            && size > config.getMaxFileSize()) {
          part.cleanup();
          throw new IllegalStateException("multipart file exceeds configured maximum size");
        }
        parts.add(part);
        position = contentEnd + 2 + delimiter.length;
      }
    } catch (IOException | ServletException | RuntimeException e) {
      for (MultipartPart part : parts) {
        part.cleanup();
      }
      throw e;
    }
  }

  private static String boundary(String contentType) throws ServletException {
    if (contentType == null
        || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
      throw new ServletException("request is not multipart/form-data");
    }
    for (String parameter : contentType.split(";")) {
      int equals = parameter.indexOf('=');
      if (equals < 0 || !"boundary".equalsIgnoreCase(parameter.substring(0, equals).trim())) {
        continue;
      }
      String value = parameter.substring(equals + 1).trim();
      if (value.length() >= 2
          && value.charAt(0) == '"'
          && value.charAt(value.length() - 1) == '"') {
        value = value.substring(1, value.length() - 1);
      }
      if (value.isEmpty()
          || value.length() > 70
          || value.indexOf('\r') >= 0
          || value.indexOf('\n') >= 0) {
        break;
      }
      return value;
    }
    throw new ServletException("multipart boundary is missing or invalid");
  }

  private static Path uploadLocation(MultipartConfigElement config, ServletContext context) {
    Object temporaryDirectory = context.getAttribute(ServletContext.TEMPDIR);
    Path base =
        temporaryDirectory instanceof File
            ? ((File) temporaryDirectory).toPath()
            : Paths.get(System.getProperty("java.io.tmpdir"));
    String configured = config.getLocation();
    if (configured == null || configured.isEmpty()) {
      return base.toAbsolutePath().normalize();
    }
    Path location = Paths.get(configured);
    return (location.isAbsolute() ? location : base.resolve(location)).toAbsolutePath().normalize();
  }

  private static Map<String, List<String>> parseHeaders(byte[] body, int start, int end)
      throws ServletException {
    String block = new String(body, start, end - start, StandardCharsets.ISO_8859_1);
    Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
    for (String line : block.split("\r\n", -1)) {
      int colon = line.indexOf(':');
      if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
        throw new ServletException("invalid multipart part header");
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      List<String> values = headers.get(name);
      if (values == null) {
        values = new ArrayList<String>();
        headers.put(name, values);
      }
      values.add(value);
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      entry.setValue(Collections.unmodifiableList(entry.getValue()));
    }
    return Collections.unmodifiableMap(headers);
  }

  private static void requireCrlf(byte[] body, int position) throws ServletException {
    if (!matches(body, position, new byte[] {'\r', '\n'})) {
      throw new ServletException("invalid multipart boundary delimiter");
    }
  }

  private static int indexOf(byte[] source, byte[] target, int start) {
    for (int i = start; i <= source.length - target.length; i++) {
      if (matches(source, i, target)) {
        return i;
      }
    }
    return -1;
  }

  private static int nextDelimiter(byte[] source, byte[] target, int delimiterLength, int start) {
    int candidate = start;
    while ((candidate = indexOf(source, target, candidate)) >= 0) {
      int suffix = candidate + 2 + delimiterLength;
      if (matches(source, suffix, new byte[] {'-', '-'})
          || matches(source, suffix, new byte[] {'\r', '\n'})) {
        return candidate;
      }
      candidate++;
    }
    return -1;
  }

  private static boolean matches(byte[] source, int start, byte[] target) {
    if (start < 0 || start + target.length > source.length) {
      return false;
    }
    for (int i = 0; i < target.length; i++) {
      if (source[start + i] != target[i]) {
        return false;
      }
    }
    return true;
  }
}
