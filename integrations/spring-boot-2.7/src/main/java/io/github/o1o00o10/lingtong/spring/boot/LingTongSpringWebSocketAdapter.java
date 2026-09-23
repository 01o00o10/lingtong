/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.api.websocket.WebSocketSession;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;

/** 将 Spring WebSocketHandler 和握手拦截器适配到灵童原生端点。 */
final class LingTongSpringWebSocketAdapter implements WebSocketHandler {
  private final org.springframework.web.socket.WebSocketHandler handler;
  private final HttpRequest request;
  private final Map<String, Object> attributes;
  private final List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors;
  private final SpringWebSocketBridgeRequest bridgeRequest;
  private final SpringWebSocketBridgeResponse bridgeResponse;
  private volatile SpringWebSocketSession session;

  private LingTongSpringWebSocketAdapter(
      org.springframework.web.socket.WebSocketHandler handler,
      HttpRequest request,
      Map<String, Object> attributes,
      List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors,
      SpringWebSocketBridgeRequest bridgeRequest,
      SpringWebSocketBridgeResponse bridgeResponse) {
    this.handler = handler;
    this.request = request;
    this.attributes = attributes;
    this.interceptors = interceptors;
    this.bridgeRequest = bridgeRequest;
    this.bridgeResponse = bridgeResponse;
  }

  static WebSocketHandler create(
      org.springframework.web.socket.WebSocketHandler handler,
      HttpRequest request,
      List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors)
      throws Exception {
    SpringWebSocketBridgeRequest bridgeRequest = new SpringWebSocketBridgeRequest(request);
    SpringWebSocketBridgeResponse bridgeResponse = new SpringWebSocketBridgeResponse();
    Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    int applied = 0;
    try {
      for (org.springframework.web.socket.server.HandshakeInterceptor interceptor : interceptors) {
        if (!interceptor.beforeHandshake(bridgeRequest, bridgeResponse, handler, attributes)) {
          notifyHandshakeComplete(
              interceptors, applied, bridgeRequest, bridgeResponse, handler, null);
          return null;
        }
        applied++;
      }
    } catch (Exception failure) {
      notifyHandshakeComplete(
          interceptors, applied, bridgeRequest, bridgeResponse, handler, failure);
      throw failure;
    }
    return new LingTongSpringWebSocketAdapter(
        handler,
        request,
        attributes,
        new ArrayList<org.springframework.web.socket.server.HandshakeInterceptor>(interceptors),
        bridgeRequest,
        bridgeResponse);
  }

  static WebSocketHandler createPrepared(
      org.springframework.web.socket.WebSocketHandler handler,
      HttpRequest request,
      Map<String, Object> attributes,
      SpringWebSocketBridgeRequest bridgeRequest,
      SpringWebSocketBridgeResponse bridgeResponse) {
    return new LingTongSpringWebSocketAdapter(
        handler,
        request,
        new LinkedHashMap<String, Object>(attributes),
        Collections.<org.springframework.web.socket.server.HandshakeInterceptor>emptyList(),
        bridgeRequest,
        bridgeResponse);
  }

  private static void notifyHandshakeComplete(
      List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors,
      int applied,
      SpringWebSocketBridgeRequest request,
      SpringWebSocketBridgeResponse response,
      org.springframework.web.socket.WebSocketHandler handler,
      Exception failure) {
    for (int i = applied - 1; i >= 0; i--) {
      interceptors.get(i).afterHandshake(request, response, handler, failure);
    }
  }

  @Override
  public List<String> subprotocols() {
    if (handler instanceof SubProtocolCapable) {
      return ((SubProtocolCapable) handler).getSubProtocols();
    }
    return Collections.emptyList();
  }

  @Override
  public boolean perMessageDeflate() {
    return true;
  }

  @Override
  public Map<String, List<String>> handshakeResponseHeaders() {
    HttpHeaders headers = bridgeResponse.getHeaders();
    return headers;
  }

  @Override
  public void onOpen(WebSocketSession nativeSession) throws Exception {
    SpringWebSocketSession created = new SpringWebSocketSession(nativeSession, request, attributes);
    session = created;
    try {
      handler.afterConnectionEstablished(created);
    } finally {
      notifyHandshakeComplete(
          interceptors, interceptors.size(), bridgeRequest, bridgeResponse, handler, null);
    }
  }

  @Override
  public void onText(WebSocketSession ignored, String message) throws Exception {
    handler.handleMessage(session, new TextMessage(message));
  }

  @Override
  public void onBinary(WebSocketSession ignored, ByteBuffer message) throws Exception {
    handler.handleMessage(session, new BinaryMessage(message));
  }

  @Override
  public void onPong(WebSocketSession ignored, ByteBuffer payload) throws Exception {
    handler.handleMessage(session, new PongMessage(payload));
  }

  @Override
  public void onClose(WebSocketSession ignored, int code, String reason) throws Exception {
    handler.afterConnectionClosed(session, new CloseStatus(code, reason));
  }

  @Override
  public void onError(WebSocketSession ignored, Throwable failure) {
    try {
      handler.handleTransportError(session, failure);
    } catch (Exception suppressed) {
      failure.addSuppressed(suppressed);
    }
  }
}
