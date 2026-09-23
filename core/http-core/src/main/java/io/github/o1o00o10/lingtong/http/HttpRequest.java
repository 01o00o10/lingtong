/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** 协议层向应用层交付的一次请求；元数据不可变，正文可经邮箱逐块到达。 */
public final class HttpRequest {
  /** HTTP 方法。 */
  private final String method;
  /** 原始请求目标，包含路径及可能存在的查询串。 */
  private final String target;
  /** 请求协议版本。 */
  private final String version;
  /** 已解析的请求头。 */
  private final HttpHeaders headers;
  /** 请求体与 Trailer 的交付通道。 */
  private final BodyMailbox bodyMailbox;
  /** 真实连接或经可信代理修正后的端点信息。 */
  private final RequestConnectionInfo connectionInfo;
  /** HTTP/2 扩展 CONNECT 的协议名；普通请求为 null。 */
  private final String extendedConnectProtocol;
  /** 可选请求诊断时间线；默认关闭。 */
  private final RequestTrace trace;
  /** 按需聚合的正文缓存；可能尚未从邮箱读完。 */
  private byte[] materializedBody;

  public HttpRequest(
      String method, String target, String version, HttpHeaders headers, byte[] body) {
    this(method, target, version, headers, body, HttpHeaders.builder().build());
  }

  public HttpRequest(
      String method,
      String target,
      String version,
      HttpHeaders headers,
      byte[] body,
      HttpHeaders trailers) {
    this(
        method,
        target,
        version,
        headers,
        BodyMailbox.completed(body, trailers),
        RequestConnectionInfo.local(headers),
        null,
        null);
  }

  private HttpRequest(
      String method,
      String target,
      String version,
      HttpHeaders headers,
      BodyMailbox bodyMailbox,
      RequestConnectionInfo connectionInfo,
      String extendedConnectProtocol,
      RequestTrace trace) {
    if (bodyMailbox == null || connectionInfo == null) {
      throw new IllegalArgumentException(
          "request body mailbox and connection information must not be null");
    }
    this.method = method;
    this.target = target;
    this.version = version;
    this.headers = headers;
    this.bodyMailbox = bodyMailbox;
    this.connectionInfo = connectionInfo;
    this.extendedConnectProtocol = extendedConnectProtocol;
    this.trace = trace;
    this.materializedBody = bodyMailbox.completedBodyView();
  }

  /** 创建头部先到、正文后续进入邮箱的请求。 */
  public static HttpRequest withBodyMailbox(
      String method, String target, String version, HttpHeaders headers, BodyMailbox bodyMailbox) {
    return new HttpRequest(
        method,
        target,
        version,
        headers,
        bodyMailbox,
        RequestConnectionInfo.local(headers),
        null,
        null);
  }

  public static HttpRequest extendedConnect(
      String protocol, String target, HttpHeaders headers, BodyMailbox bodyMailbox) {
    if (protocol == null || protocol.trim().isEmpty()) {
      throw new IllegalArgumentException("extended CONNECT protocol must not be empty");
    }
    return new HttpRequest(
        "CONNECT",
        target,
        "HTTP/2",
        headers,
        bodyMailbox,
        RequestConnectionInfo.local(headers),
        protocol,
        null);
  }

  /** 保留原正文邮箱，只替换连接元数据。 */
  public HttpRequest withConnectionInfo(RequestConnectionInfo value) {
    return new HttpRequest(
        method, target, version, headers, bodyMailbox, value, extendedConnectProtocol, trace);
  }

  /** 附加请求级诊断时间线，保留原正文邮箱与协议元数据。 */
  public HttpRequest withTrace(RequestTrace value) {
    return new HttpRequest(
        method,
        target,
        version,
        headers,
        bodyMailbox,
        connectionInfo,
        extendedConnectProtocol,
        value);
  }

  public RequestTrace trace() {
    return trace;
  }

  public String method() {
    return method;
  }

  public String target() {
    return target;
  }

  public String version() {
    return version;
  }

  public HttpHeaders headers() {
    return headers;
  }

  /** 阻塞聚合正文并返回副本；流式消费应直接使用 bodyMailbox()。 */
  public synchronized byte[] body() {
    if (materializedBody == null) {
      try {
        materializedBody = bodyMailbox.readAllBlocking();
      } catch (IOException e) {
        throw new IllegalStateException("failed to read request body", e);
      }
    }
    return Arrays.copyOf(materializedBody, materializedBody.length);
  }

  public BodyMailbox bodyMailbox() {
    return bodyMailbox;
  }

  public RequestConnectionInfo connectionInfo() {
    return connectionInfo;
  }

  public String extendedConnectProtocol() {
    return extendedConnectProtocol;
  }

  public boolean isExtendedConnect() {
    return extendedConnectProtocol != null;
  }

  public HttpHeaders trailers() {
    return bodyMailbox.trailers();
  }

  public boolean keepAlive() {
    if ("HTTP/1.0".equals(version)) {
      return containsConnectionToken(headers.all("connection"), "keep-alive");
    }
    return !containsConnectionToken(headers.all("connection"), "close");
  }

  private boolean containsConnectionToken(List<String> values, String expected) {
    for (String value : values) {
      String[] tokens = value.split(",");
      for (String token : tokens) {
        if (expected.equalsIgnoreCase(token.trim())) {
          return true;
        }
      }
    }
    return false;
  }
}
