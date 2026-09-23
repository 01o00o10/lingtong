/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import io.github.o1o00o10.lingtong.websocket.WebSocketEndpointRegistry;
import java.lang.reflect.Field;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;
import org.springframework.core.Ordered;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.RequestUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/** Installs LingTong's transport bridge after application WebSocket registrations. */
/** 在 Spring WebSocket 注册完成后安装容器传输桥接。 */
final class LingTongSpringWebSocketConfigurer implements WebSocketConfigurer, Ordered {
  /** Servlet上下文。 */
  private final ServletContext servletContext;

  LingTongSpringWebSocketConfigurer(ServletContext servletContext) {
    this.servletContext = servletContext;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void registerWebSocketHandlers(WebSocketHandlerRegistry value) {
    if (!(value instanceof ServletWebSocketHandlerRegistry)) return;
    Object attribute = servletContext.getAttribute(WebSocketEndpointRegistry.class.getName());
    if (!(attribute instanceof WebSocketEndpointRegistry)) {
      throw new IllegalStateException("LingTong WebSocket endpoint registry is unavailable");
    }
    WebSocketEndpointRegistry endpoints = (WebSocketEndpointRegistry) attribute;
    try {
      Field registrationsField =
          ServletWebSocketHandlerRegistry.class.getDeclaredField("registrations");
      registrationsField.setAccessible(true);
      List<Object> registrations = (List<Object>) registrationsField.get(value);
      for (Object registration : registrations) install(registration, endpoints);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Spring WebSocket 5.3 registration model changed", e);
    }
  }

  @SuppressWarnings("unchecked")
  private void install(Object registration, WebSocketEndpointRegistry endpoints)
      throws ReflectiveOperationException {
    Class<?> base = registration.getClass().getSuperclass();
    Field handlerMapField = base.getDeclaredField("handlerMap");
    Field handshakeField = base.getDeclaredField("handshakeHandler");
    Field originsField = base.getDeclaredField("allowedOrigins");
    Field originPatternsField = base.getDeclaredField("allowedOriginPatterns");
    Field interceptorsField = base.getDeclaredField("interceptors");
    Field sockJsField = base.getDeclaredField("sockJsServiceRegistration");
    handlerMapField.setAccessible(true);
    handshakeField.setAccessible(true);
    originsField.setAccessible(true);
    originPatternsField.setAccessible(true);
    interceptorsField.setAccessible(true);
    sockJsField.setAccessible(true);
    Map<org.springframework.web.socket.WebSocketHandler, List<String>> handlerMap =
        (Map<org.springframework.web.socket.WebSocketHandler, List<String>>)
            handlerMapField.get(registration);
    SockJsServiceRegistration sockJs = (SockJsServiceRegistration) sockJsField.get(registration);
    if (sockJs != null) {
      LingTongSpringSockJsBridge.install(
          registration, sockJsField, sockJs, handlerMap, endpoints, servletContext);
      return;
    }
    if (handshakeField.get(registration) == null) {
      registration
          .getClass()
          .getMethod(
              "setHandshakeHandler", org.springframework.web.socket.server.HandshakeHandler.class)
          .invoke(registration, new DefaultHandshakeHandler(new TransportOwnedUpgradeStrategy()));
    }
    List<String> origins = new ArrayList<String>((List<String>) originsField.get(registration));
    List<String> patterns =
        new ArrayList<String>((List<String>) originPatternsField.get(registration));
    List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors =
        new ArrayList<org.springframework.web.socket.server.HandshakeInterceptor>(
            (List<org.springframework.web.socket.server.HandshakeInterceptor>)
                interceptorsField.get(registration));
    for (Map.Entry<org.springframework.web.socket.WebSocketHandler, List<String>> entry :
        handlerMap.entrySet()) {
      final org.springframework.web.socket.WebSocketHandler handler = entry.getKey();
      for (String path : entry.getValue()) {
        String contextPath = servletContext.getContextPath();
        String route = contextPath == null || contextPath.isEmpty() ? path : contextPath + path;
        endpoints.register(
            route,
            (request, pathParameters) -> {
              if (!originAllowed(request, origins, patterns)) return null;
              return LingTongSpringWebSocketAdapter.create(handler, request, interceptors);
            });
      }
    }
  }

  private static boolean originAllowed(
      io.github.o1o00o10.lingtong.http.HttpRequest request,
      List<String> origins,
      List<String> patterns) {
    String origin = request.headers().first("origin");
    if (origin == null) return true;
    if (origins.isEmpty() && patterns.isEmpty()) {
      try {
        java.net.URI value = java.net.URI.create(origin);
        String host = request.headers().first("host");
        String expected = request.connectionInfo().scheme() + "://" + host;
        java.net.URI server = java.net.URI.create(expected);
        int originPort =
            value.getPort() >= 0
                ? value.getPort()
                : "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
        int serverPort =
            server.getPort() >= 0
                ? server.getPort()
                : "https".equalsIgnoreCase(server.getScheme()) ? 443 : 80;
        return value.getScheme().equalsIgnoreCase(server.getScheme())
            && value.getHost().equalsIgnoreCase(server.getHost())
            && originPort == serverPort;
      } catch (RuntimeException e) {
        return false;
      }
    }
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(origins);
    cors.setAllowedOriginPatterns(patterns);
    return cors.checkOrigin(origin) != null;
  }

  /** 封装传输owned升级strategy的状态与处理边界。 */
  private static final class TransportOwnedUpgradeStrategy implements RequestUpgradeStrategy {
    @Override
    public String[] getSupportedVersions() {
      return new String[] {"13"};
    }

    @Override
    public List<WebSocketExtension> getSupportedExtensions(ServerHttpRequest request) {
      return Collections.singletonList(new WebSocketExtension("permessage-deflate"));
    }

    @Override
    public void upgrade(
        ServerHttpRequest request,
        ServerHttpResponse response,
        String protocol,
        List<WebSocketExtension> extensions,
        Principal principal,
        org.springframework.web.socket.WebSocketHandler handler,
        Map<String, Object> attributes) {
      throw new HandshakeFailureException(
          "LingTong upgrades Spring WebSocket routes at the transport boundary");
    }
  }
}
