/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.websocket.WebSocketEndpointRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.sockjs.SockJsService;
import org.springframework.web.socket.sockjs.transport.TransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.EventSourceTransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.HtmlFileTransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.WebSocketTransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.XhrPollingTransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.XhrReceivingTransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.XhrStreamingTransportHandler;

/** Connects Spring's SockJS session engine to LingTong-owned WebSocket upgrades. */
/** 将 Spring SockJS 相关请求桥接到容器的 HTTP 与 WebSocket 处理入口。 */
final class LingTongSpringSockJsBridge {
  private LingTongSpringSockJsBridge() {}

  static void install(
      Object registration,
      Field sockJsRegistrationField,
      SockJsServiceRegistration sockJsRegistration,
      Map<org.springframework.web.socket.WebSocketHandler, List<String>> handlerMap,
      WebSocketEndpointRegistry endpoints,
      ServletContext servletContext)
      throws ReflectiveOperationException {
    CapturingHandshakeHandler handshake = new CapturingHandshakeHandler();
    boolean webSocketEnabled = replaceWebSocketTransport(sockJsRegistration, handshake);
    SharedSockJsServiceRegistration shared =
        new SharedSockJsServiceRegistration(sockJsRegistration);
    sockJsRegistrationField.set(registration, shared);
    if (!webSocketEnabled) return;

    for (Map.Entry<org.springframework.web.socket.WebSocketHandler, List<String>> entry :
        handlerMap.entrySet()) {
      final org.springframework.web.socket.WebSocketHandler applicationHandler = entry.getKey();
      for (String path : entry.getValue()) {
        final String route = withContextPath(servletContext, path);
        endpoints.register(
            route + "/{server}/{session}/websocket",
            (request, parameters) ->
                handshake.capture(
                    shared.service(),
                    applicationHandler,
                    request,
                    "/"
                        + parameters.get("server")
                        + "/"
                        + parameters.get("session")
                        + "/websocket"));
        endpoints.register(
            route + "/websocket",
            (request, parameters) ->
                handshake.capture(shared.service(), applicationHandler, request, "/websocket"));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static boolean replaceWebSocketTransport(
      SockJsServiceRegistration registration, HandshakeHandler handshake)
      throws ReflectiveOperationException {
    Field handlersField = SockJsServiceRegistration.class.getDeclaredField("transportHandlers");
    Field overridesField =
        SockJsServiceRegistration.class.getDeclaredField("transportHandlerOverrides");
    handlersField.setAccessible(true);
    overridesField.setAccessible(true);
    List<TransportHandler> handlers =
        new ArrayList<TransportHandler>((List<TransportHandler>) handlersField.get(registration));
    List<TransportHandler> overrides =
        new ArrayList<TransportHandler>((List<TransportHandler>) overridesField.get(registration));
    WebSocketTransportHandler replacement = new WebSocketTransportHandler(handshake);
    if (!handlers.isEmpty()) {
      boolean configured = contains(handlers, replacement);
      if (configured) replace(handlers, replacement);
      registration.setTransportHandlers(handlers.toArray(new TransportHandler[handlers.size()]));
      return configured;
    } else {
      handlers.add(new XhrPollingTransportHandler());
      handlers.add(new XhrReceivingTransportHandler());
      handlers.add(new XhrStreamingTransportHandler());
      handlers.add(new EventSourceTransportHandler());
      handlers.add(new HtmlFileTransportHandler());
      for (TransportHandler override : overrides) replace(handlers, override);
      replace(handlers, replacement);
      registration.setTransportHandlerOverrides();
      registration.setTransportHandlers(handlers.toArray(new TransportHandler[handlers.size()]));
      return true;
    }
  }

  private static boolean contains(List<TransportHandler> handlers, TransportHandler candidate) {
    for (TransportHandler handler : handlers) {
      if (handler.getTransportType() == candidate.getTransportType()) return true;
    }
    return false;
  }

  private static void replace(List<TransportHandler> handlers, TransportHandler replacement) {
    for (int i = handlers.size() - 1; i >= 0; i--) {
      if (handlers.get(i).getTransportType() == replacement.getTransportType()) {
        handlers.remove(i);
      }
    }
    handlers.add(replacement);
  }

  private static String withContextPath(ServletContext context, String path) {
    String contextPath = context.getContextPath();
    String result = contextPath == null || contextPath.isEmpty() ? path : contextPath + path;
    return result.length() > 1 && result.endsWith("/")
        ? result.substring(0, result.length() - 1)
        : result;
  }

  /** 封装sharedSockJSJavaScript服务注册信息的状态与处理边界。 */
  private static final class SharedSockJsServiceRegistration extends SockJsServiceRegistration {
    /** 委托对象。 */
    private final SockJsServiceRegistration delegate;
    /** 工厂。 */
    private final Method factory;
    /** 服务。 */
    private volatile SockJsService service;

    private SharedSockJsServiceRegistration(SockJsServiceRegistration delegate)
        throws NoSuchMethodException {
      this.delegate = delegate;
      this.factory = SockJsServiceRegistration.class.getDeclaredMethod("getSockJsService");
      this.factory.setAccessible(true);
    }

    @Override
    protected SockJsService getSockJsService() {
      return service();
    }

    @Override
    public SockJsServiceRegistration setTaskScheduler(TaskScheduler scheduler) {
      delegate.setTaskScheduler(scheduler);
      return this;
    }

    private SockJsService service() {
      SockJsService result = service;
      if (result != null) return result;
      synchronized (this) {
        result = service;
        if (result == null) service = result = invokeFactory();
      }
      return result;
    }

    private SockJsService invokeFactory() {
      try {
        return (SockJsService) factory.invoke(delegate);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("cannot access Spring SockJS service factory", e);
      } catch (InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new IllegalStateException("cannot create Spring SockJS service", cause);
      }
    }
  }

  /** 封装捕获中的握手处理器的状态与处理边界。 */
  private static final class CapturingHandshakeHandler implements HandshakeHandler {
    /** 当前。 */
    private final ThreadLocal<Capture> current = new ThreadLocal<Capture>();

    private WebSocketHandler capture(
        SockJsService service,
        org.springframework.web.socket.WebSocketHandler applicationHandler,
        HttpRequest request,
        String sockJsPath)
        throws Exception {
      SpringWebSocketBridgeRequest bridgeRequest =
          new SpringWebSocketBridgeRequest(
              request, request.isExtendedConnect() ? "GET" : request.method());
      SpringWebSocketBridgeResponse bridgeResponse = new SpringWebSocketBridgeResponse();
      Capture capture = new Capture();
      if (current.get() != null) {
        throw new IllegalStateException("nested SockJS WebSocket handshake");
      }
      current.set(capture);
      try {
        service.handleRequest(bridgeRequest, bridgeResponse, sockJsPath, applicationHandler);
      } finally {
        current.remove();
      }
      if (capture.handler == null) return null;
      return LingTongSpringWebSocketAdapter.createPrepared(
          capture.handler, request, capture.attributes, bridgeRequest, bridgeResponse);
    }

    @Override
    public boolean doHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        org.springframework.web.socket.WebSocketHandler handler,
        Map<String, Object> attributes) {
      Capture capture = current.get();
      if (capture == null) {
        throw new IllegalStateException("SockJS handshake is outside LingTong upgrade dispatch");
      }
      capture.handler = handler;
      capture.attributes = new java.util.LinkedHashMap<String, Object>(attributes);
      return true;
    }
  }

  /** 封装capture的状态与处理边界。 */
  private static final class Capture {
    /** 处理器。 */
    private org.springframework.web.socket.WebSocketHandler handler;
    /** 按键索引的属性集合。 */
    private Map<String, Object> attributes = Collections.emptyMap();
  }
}
