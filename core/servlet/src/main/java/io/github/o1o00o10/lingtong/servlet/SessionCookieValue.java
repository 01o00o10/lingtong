/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.http.Cookie;

/** 在标准 Cookie 之上携带 SameSite 属性，供响应序列化使用。 */
final class SessionCookieValue extends Cookie {
  /** Cookie 的 Java 序列化版本号。 */
  private static final long serialVersionUID = 1L;
  /** SameSite 策略值，未设置时为空。 */
  private String sameSite;

  SessionCookieValue(String name, String value) {
    super(name, value);
  }

  String sameSite() {
    return sameSite;
  }

  void sameSite(String value) {
    sameSite = value;
  }
}
