/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketSession;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.RequestConnectionInfo;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;

/** 将灵童原生会话暴露为 Spring WebSocketSession。 */
final class SpringWebSocketSession implements org.springframework.web.socket.WebSocketSession {
  private static final int DEFAULT_MESSAGE_LIMIT = 64 * 1024;

  private final WebSocketSession nativeSession;
  private final URI uri;
  private final HttpHeaders headers = new HttpHeaders();
  private final Map<String, Object> attributes;
  private final InetSocketAddress local;
  private final InetSocketAddress remote;
  private int textLimit = DEFAULT_MESSAGE_LIMIT;
  private int binaryLimit = DEFAULT_MESSAGE_LIMIT;

  SpringWebSocketSession(
      WebSocketSession nativeSession, HttpRequest request, Map<String, Object> attributes) {
    this.nativeSession = nativeSession;
    RequestConnectionInfo connection = request.connectionInfo();
    this.uri = websocketUri(request, connection);
    for (Map.Entry<String, List<String>> entry : request.headers().entries()) {
      headers.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
    }
    this.attributes = new LinkedHashMap<String, Object>(attributes);
    this.local = new InetSocketAddress(connection.localAddress(), connection.localPort());
    this.remote = new InetSocketAddress(connection.remoteAddress(), connection.remotePort());
  }

  private static URI websocketUri(HttpRequest request, RequestConnectionInfo connection) {
    String host = request.headers().first("host");
    if (host == null) {
      host = connection.serverName() + ":" + connection.serverPort();
    }
    return URI.create((connection.secure() ? "wss" : "ws") + "://" + host + request.target());
  }

  @Override
  public String getId() {
    return nativeSession.id();
  }

  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public HttpHeaders getHandshakeHeaders() {
    return HttpHeaders.readOnlyHttpHeaders(headers);
  }

  @Override
  public Map<String, Object> getAttributes() {
    return attributes;
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
  public String getAcceptedProtocol() {
    return nativeSession.negotiatedSubprotocol();
  }

  @Override
  public void setTextMessageSizeLimit(int value) {
    textLimit = value;
  }

  @Override
  public int getTextMessageSizeLimit() {
    return textLimit;
  }

  @Override
  public void setBinaryMessageSizeLimit(int value) {
    binaryLimit = value;
  }

  @Override
  public int getBinaryMessageSizeLimit() {
    return binaryLimit;
  }

  @Override
  public List<WebSocketExtension> getExtensions() {
    return nativeSession.negotiatedExtensions().isEmpty()
        ? Collections.<WebSocketExtension>emptyList()
        : Collections.singletonList(new WebSocketExtension("permessage-deflate"));
  }

  @Override
  public void sendMessage(WebSocketMessage<?> message) throws IOException {
    if (message instanceof TextMessage) {
      nativeSession.sendText((String) message.getPayload());
    } else if (message instanceof BinaryMessage) {
      nativeSession.sendBinary((ByteBuffer) message.getPayload());
    } else if (message instanceof PingMessage) {
      nativeSession.sendPing((ByteBuffer) message.getPayload());
    } else if (message instanceof PongMessage) {
      nativeSession.sendPong((ByteBuffer) message.getPayload());
    } else {
      throw new IOException("unsupported Spring WebSocket message type " + message.getClass());
    }
  }

  @Override
  public boolean isOpen() {
    return nativeSession.isOpen();
  }

  @Override
  public void close() throws IOException {
    nativeSession.close();
  }

  @Override
  public void close(CloseStatus status) throws IOException {
    nativeSession.close(status.getCode(), status.getReason());
  }
}
