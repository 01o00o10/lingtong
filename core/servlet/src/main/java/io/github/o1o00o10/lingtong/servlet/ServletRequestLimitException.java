/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

/** 请求参数或 Cookie 超限，携带后续响应使用的 HTTP 状态码。 */
final class ServletRequestLimitException extends IllegalStateException {
  /** 对客户端返回的 HTTP 状态码。 */
  private final int statusCode;

  ServletRequestLimitException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  int statusCode() {
    return statusCode;
  }
}
