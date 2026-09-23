/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.Locale;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/** 屏蔽 include 目标无权修改的响应元数据，只允许其写入响应体。 */
final class IncludeResponseWrapper extends HttpServletResponseWrapper {
  IncludeResponseWrapper(HttpServletResponse response) {
    super(response);
  }

  @Override
  public void addCookie(Cookie cookie) {}

  @Override
  public void sendError(int statusCode, String message) {}

  @Override
  public void sendError(int statusCode) {}

  @Override
  public void sendRedirect(String location) {}

  @Override
  public void setDateHeader(String name, long date) {}

  @Override
  public void addDateHeader(String name, long date) {}

  @Override
  public void setHeader(String name, String value) {}

  @Override
  public void addHeader(String name, String value) {}

  @Override
  public void setIntHeader(String name, int value) {}

  @Override
  public void addIntHeader(String name, int value) {}

  @Override
  public void setStatus(int statusCode) {}

  @Deprecated
  @Override
  public void setStatus(int statusCode, String message) {}

  @Override
  public void setCharacterEncoding(String charset) {}

  @Override
  public void setContentLength(int length) {}

  @Override
  public void setContentLengthLong(long length) {}

  @Override
  public void setContentType(String type) {}

  @Override
  public void setBufferSize(int size) {}

  @Override
  public void flushBuffer() {}

  @Override
  public void resetBuffer() {}

  @Override
  public void reset() {}

  @Override
  public void setLocale(Locale locale) {}
}
