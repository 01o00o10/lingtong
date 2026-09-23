/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 响应压缩的不可变策略；具体协商和编码发生在传输层。 */
public final class CompressionConfig {
  /** 未显式指定时参与压缩判定的默认 MIME 类型。 */
  private static final List<String> DEFAULT_MIME_TYPES =
      Collections.unmodifiableList(
          Arrays.asList(
              "text/html",
              "text/xml",
              "text/plain",
              "text/css",
              "text/javascript",
              "application/javascript",
              "application/json",
              "application/xml"));

  /** 是否启用响应压缩。 */
  private final boolean enabled;
  /** 可压缩响应的最小正文长度，单位字节。 */
  private final long minResponseBytes;
  /** 允许压缩的 MIME 类型白名单。 */
  private final List<String> mimeTypes;
  /** 不启用压缩的 User-Agent 列表。 */
  private final List<String> excludedUserAgents;

  public CompressionConfig(
      boolean enabled,
      long minResponseBytes,
      List<String> mimeTypes,
      List<String> excludedUserAgents) {
    if (minResponseBytes < 0) {
      throw new IllegalArgumentException("compression minimum response size must not be negative");
    }
    if (mimeTypes == null || excludedUserAgents == null) {
      throw new IllegalArgumentException("compression lists must not be null");
    }
    this.enabled = enabled;
    this.minResponseBytes = minResponseBytes;
    this.mimeTypes = immutableStrings(mimeTypes, "MIME type");
    this.excludedUserAgents = immutableStrings(excludedUserAgents, "excluded user agent");
  }

  /** 返回关闭压缩但保留默认阈值和 MIME 列表的配置。 */
  public static CompressionConfig disabled() {
    return new CompressionConfig(false, 2048L, DEFAULT_MIME_TYPES, Collections.<String>emptyList());
  }

  public boolean enabled() {
    return enabled;
  }

  public long minResponseBytes() {
    return minResponseBytes;
  }

  public List<String> mimeTypes() {
    return mimeTypes;
  }

  public List<String> excludedUserAgents() {
    return excludedUserAgents;
  }

  private static List<String> immutableStrings(List<String> source, String type) {
    List<String> copy = new ArrayList<String>(source.size());
    for (String value : source) {
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException("compression " + type + " must not be empty");
      }
      copy.add(value.trim());
    }
    return Collections.unmodifiableList(copy);
  }
}
