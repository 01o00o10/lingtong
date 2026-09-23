/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;

/** 将 Context 与请求属性的增删改通知给对应 Servlet 监听器。 */
final class ServletAttributeEventDispatcher {
  /** 日志记录器。 */
  private static final Logger LOGGER =
      Logger.getLogger("io.github.o1o00o10.lingtong.servlet.attributes");
  /** empty。 */
  /** 空值标志（EMPTY）。 */
  private static final ServletAttributeEventDispatcher EMPTY =
      new ServletAttributeEventDispatcher(
          new ServletContextAttributeListener[0], new ServletRequestAttributeListener[0]);

  /** 上下文监听器集合。 */
  private final ServletContextAttributeListener[] contextListeners;
  /** 请求监听器集合。 */
  private final ServletRequestAttributeListener[] requestListeners;

  ServletAttributeEventDispatcher(
      List<ServletContextAttributeListener> contextListeners,
      List<ServletRequestAttributeListener> requestListeners) {
    this(
        contextListeners.toArray(new ServletContextAttributeListener[contextListeners.size()]),
        requestListeners.toArray(new ServletRequestAttributeListener[requestListeners.size()]));
  }

  private ServletAttributeEventDispatcher(
      ServletContextAttributeListener[] contextListeners,
      ServletRequestAttributeListener[] requestListeners) {
    this.contextListeners = contextListeners;
    this.requestListeners = requestListeners;
  }

  static ServletAttributeEventDispatcher empty() {
    return EMPTY;
  }

  void contextAdded(ServletContext context, String name, Object value) {
    ServletContextAttributeEvent event = new ServletContextAttributeEvent(context, name, value);
    for (ServletContextAttributeListener listener : contextListeners) {
      invoke(() -> listener.attributeAdded(event), "context attributeAdded");
    }
  }

  void contextReplaced(ServletContext context, String name, Object previous) {
    ServletContextAttributeEvent event = new ServletContextAttributeEvent(context, name, previous);
    for (ServletContextAttributeListener listener : contextListeners) {
      invoke(() -> listener.attributeReplaced(event), "context attributeReplaced");
    }
  }

  void contextRemoved(ServletContext context, String name, Object removed) {
    ServletContextAttributeEvent event = new ServletContextAttributeEvent(context, name, removed);
    for (ServletContextAttributeListener listener : contextListeners) {
      invoke(() -> listener.attributeRemoved(event), "context attributeRemoved");
    }
  }

  void requestAdded(ServletContext context, ServletRequest request, String name, Object value) {
    ServletRequestAttributeEvent event =
        new ServletRequestAttributeEvent(context, request, name, value);
    for (ServletRequestAttributeListener listener : requestListeners) {
      invoke(() -> listener.attributeAdded(event), "request attributeAdded");
    }
  }

  void requestReplaced(
      ServletContext context, ServletRequest request, String name, Object previous) {
    ServletRequestAttributeEvent event =
        new ServletRequestAttributeEvent(context, request, name, previous);
    for (ServletRequestAttributeListener listener : requestListeners) {
      invoke(() -> listener.attributeReplaced(event), "request attributeReplaced");
    }
  }

  void requestRemoved(ServletContext context, ServletRequest request, String name, Object removed) {
    ServletRequestAttributeEvent event =
        new ServletRequestAttributeEvent(context, request, name, removed);
    for (ServletRequestAttributeListener listener : requestListeners) {
      invoke(() -> listener.attributeRemoved(event), "request attributeRemoved");
    }
  }

  private void invoke(Runnable callback, String callbackName) {
    try {
      callback.run();
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "Servlet listener failed during " + callbackName, e);
    }
  }
}
