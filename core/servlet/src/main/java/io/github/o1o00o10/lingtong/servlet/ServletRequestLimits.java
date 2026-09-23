/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import io.github.o1o00o10.lingtong.http.HttpHeaders;
import java.util.List;

/** 每请求的参数与 Cookie 上限，超限时统一转为客户端错误。 */
final class ServletRequestLimits {
  /** 查询串和表单解析后允许的最大参数个数。 */
  private final int maxParameterCount;
  /** 参数原始来源允许的最大字节数。 */
  private final int maxParameterBytes;
  /** Cookie 头中允许的最大非空条目数。 */
  private final int maxCookieCount;
  /** Cookie 头值允许的累计最大长度。 */
  private final int maxCookieBytes;

  ServletRequestLimits(
      int maxParameterCount, int maxParameterBytes, int maxCookieCount, int maxCookieBytes) {
    if (maxParameterCount <= 0
        || maxParameterBytes <= 0
        || maxCookieCount <= 0
        || maxCookieBytes <= 0) {
      throw new IllegalArgumentException("Servlet request limits must be positive");
    }
    this.maxParameterCount = maxParameterCount;
    this.maxParameterBytes = maxParameterBytes;
    this.maxCookieCount = maxCookieCount;
    this.maxCookieBytes = maxCookieBytes;
  }

  /** 在解析 Cookie 对象前检查头值总量和条目数量。 */
  void validateCookies(HttpHeaders headers) {
    int bytes = 0;
    int count = 0;
    List<String> values = headers.all("cookie");
    for (String value : values) {
      if (bytes > maxCookieBytes - value.length()) {
        throw badRequest("request cookies exceed configured byte limit");
      }
      bytes += value.length();
      for (String item : value.split(";", -1)) {
        if (!item.trim().isEmpty()) {
          count++;
          if (count > maxCookieCount) {
            throw badRequest("request cookie count exceeds configured limit");
          }
        }
      }
    }
  }

  /** 每次请求创建独立计数器，不在不同请求间共享已消耗配额。 */
  ParameterBudget parameterBudget() {
    return new ParameterBudget(maxParameterCount, maxParameterBytes);
  }

  private static ServletRequestLimitException badRequest(String message) {
    return new ServletRequestLimitException(400, message);
  }

  /** 跟踪单个请求已消耗的参数个数与原始数据量。 */
  static final class ParameterBudget {
    /** 该请求的最大参数个数。 */
    private final int maxCount;
    /** 该请求可解析的最大参数来源字节数。 */
    private final int maxBytes;
    /** 已接收参数个数。 */
    private int count;
    /** 已计入的参数来源字节数。 */
    private int bytes;

    private ParameterBudget(int maxCount, int maxBytes) {
      this.maxCount = maxCount;
      this.maxBytes = maxBytes;
    }

    /** 计入 URL 或表单来源长度，并在溢出前拒绝请求。 */
    void addSourceBytes(long value) {
      if (value < 0L || value > Integer.MAX_VALUE || bytes > maxBytes - value) {
        throw badRequest("request parameters exceed configured byte limit");
      }
      bytes += (int) value;
    }

    void addParameter() {
      count++;
      if (count > maxCount) {
        throw badRequest("request parameter count exceeds configured limit");
      }
    }
  }
}
