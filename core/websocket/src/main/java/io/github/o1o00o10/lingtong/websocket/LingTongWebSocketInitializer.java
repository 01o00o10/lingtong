/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

/** Discovers standard WebSocket endpoints from the web application's class index. */
/** 在 Web 应用启动时从类索引发现并注册标准 WebSocket 端点。 */
@HandlesTypes({ServerEndpoint.class, ServerApplicationConfig.class, Endpoint.class})
public final class LingTongWebSocketInitializer implements ServletContainerInitializer {
  @Override
  public void onStartup(Set<Class<?>> classes, ServletContext context) throws ServletException {
    Object value = context.getAttribute(LingTongServerContainer.SERVLET_CONTEXT_ATTRIBUTE);
    if (!(value instanceof ServerContainer)) {
      throw new ServletException("WebSocket ServerContainer is not installed");
    }
    ServerContainer container = (ServerContainer) value;
    Set<Class<?>> candidates = classes == null ? Collections.<Class<?>>emptySet() : classes;
    Set<Class<?>> annotated = new LinkedHashSet<Class<?>>();
    Set<Class<? extends Endpoint>> endpoints = new LinkedHashSet<Class<? extends Endpoint>>();
    Set<Class<? extends ServerApplicationConfig>> configurations =
        new LinkedHashSet<Class<? extends ServerApplicationConfig>>();
    for (Class<?> candidate : candidates) {
      if (candidate.isAnnotationPresent(ServerEndpoint.class)) annotated.add(candidate);
      if (candidate != Endpoint.class && Endpoint.class.isAssignableFrom(candidate)) {
        endpoints.add(candidate.asSubclass(Endpoint.class));
      }
      if (candidate != ServerApplicationConfig.class
          && ServerApplicationConfig.class.isAssignableFrom(candidate)) {
        configurations.add(candidate.asSubclass(ServerApplicationConfig.class));
      }
    }
    try {
      if (configurations.isEmpty()) {
        for (Class<?> endpoint : annotated) container.addEndpoint(endpoint);
        return;
      }
      Set<Class<?>> selectedAnnotated = new LinkedHashSet<Class<?>>();
      Set<ServerEndpointConfig> selectedProgrammatic = new LinkedHashSet<ServerEndpointConfig>();
      for (Class<? extends ServerApplicationConfig> type : configurations) {
        ServerApplicationConfig configuration = type.newInstance();
        Set<Class<?>> selected =
            configuration.getAnnotatedEndpointClasses(Collections.unmodifiableSet(annotated));
        if (selected != null) selectedAnnotated.addAll(selected);
        Set<ServerEndpointConfig> configured =
            configuration.getEndpointConfigs(Collections.unmodifiableSet(endpoints));
        if (configured != null) selectedProgrammatic.addAll(configured);
      }
      for (Class<?> endpoint : selectedAnnotated) container.addEndpoint(endpoint);
      for (ServerEndpointConfig endpoint : selectedProgrammatic) {
        container.addEndpoint(endpoint);
      }
    } catch (DeploymentException | InstantiationException | IllegalAccessException e) {
      throw new ServletException("failed to deploy WebSocket endpoints", e);
    }
  }
}
