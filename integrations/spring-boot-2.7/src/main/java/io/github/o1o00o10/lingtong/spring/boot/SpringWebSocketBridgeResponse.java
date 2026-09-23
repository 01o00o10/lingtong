/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;

/** 收集 Spring 握手处理器写入的状态、响应头和响应体。 */
final class SpringWebSocketBridgeResponse implements ServerHttpResponse {
  private final HttpHeaders headers = new HttpHeaders();
  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private HttpStatus status = HttpStatus.SWITCHING_PROTOCOLS;

  @Override
  public void setStatusCode(HttpStatus value) {
    status = value;
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public OutputStream getBody() {
    return body;
  }

  @Override
  public void flush() {
    // ByteArrayOutputStream does not buffer outside this object.
  }

  @Override
  public void close() {
    // The owning handshake adapter controls the native connection lifecycle.
  }

  HttpStatus status() {
    return status;
  }
}
