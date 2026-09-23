/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.RequestConnectionInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

/** 将灵童握手请求转换为 Spring ServerHttpRequest。 */
final class SpringWebSocketBridgeRequest implements ServerHttpRequest {
  private final HttpRequest request;
  private final String method;
  private final HttpHeaders headers = new HttpHeaders();
  private final URI uri;
  private final InetSocketAddress local;
  private final InetSocketAddress remote;

  SpringWebSocketBridgeRequest(HttpRequest request) {
    this(request, request.method());
  }

  SpringWebSocketBridgeRequest(HttpRequest request, String method) {
    this.request = request;
    this.method = method;
    for (Map.Entry<String, List<String>> entry : request.headers().entries()) {
      headers.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
    }
    RequestConnectionInfo connection = request.connectionInfo();
    uri = httpUri(request, connection);
    local = new InetSocketAddress(connection.localAddress(), connection.localPort());
    remote = new InetSocketAddress(connection.remoteAddress(), connection.remotePort());
  }

  private static URI httpUri(HttpRequest request, RequestConnectionInfo connection) {
    String host = request.headers().first("host");
    if (host == null) {
      host = connection.serverName() + ":" + connection.serverPort();
    }
    return URI.create((connection.secure() ? "https" : "http") + "://" + host + request.target());
  }

  @Override
  public String getMethodValue() {
    return method;
  }

  @Override
  public URI getURI() {
    return uri;
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public InputStream getBody() {
    return new ByteArrayInputStream(request.body());
  }

  @Override
  public Principal getPrincipal() {
    return null;
  }

  @Override
  public InetSocketAddress getLocalAddress() {
    return local;
  }

  @Override
  public InetSocketAddress getRemoteAddress() {
    return remote;
  }

  @Override
  public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) {
    throw new UnsupportedOperationException("asynchronous Spring handshake is not supported");
  }
}
