/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import io.github.o1o00o10.lingtong.http.RequestTrace;
import java.io.IOException;
import java.util.List;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/** 执行一次请求对应的 Filter 链，并在末端调用目标 Servlet。 */
final class ServletFilterChain implements FilterChain {
  private final List<Filter> filters;
  private final Servlet servlet;
  private final RequestTrace trace;
  private int index;

  ServletFilterChain(List<Filter> filters, Servlet servlet, RequestTrace trace) {
    this.filters = filters;
    this.servlet = servlet;
    this.trace = trace;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response)
      throws IOException, ServletException {
    if (index < filters.size()) {
      invokeNextFilter(request, response);
    } else {
      invokeServlet(request, response);
    }
  }

  private void invokeNextFilter(ServletRequest request, ServletResponse response)
      throws IOException, ServletException {
    int current = index++;
    Filter filter = filters.get(current);
    if (trace != null) {
      trace.event("filter.enter", "index=" + current + " class=" + filter.getClass().getName());
    }
    long started = trace == null ? 0L : System.nanoTime();
    try {
      filter.doFilter(request, response, this);
    } finally {
      if (trace != null) {
        trace.event(
            "filter.exit",
            "index=" + current + " durationUs=" + RequestTrace.elapsedMicros(started));
      }
    }
  }

  private void invokeServlet(ServletRequest request, ServletResponse response)
      throws IOException, ServletException {
    if (trace != null) {
      trace.event("servlet.enter", "class=" + servlet.getClass().getName());
    }
    long started = trace == null ? 0L : System.nanoTime();
    try {
      servlet.service(request, response);
    } finally {
      if (trace != null) {
        trace.event("servlet.exit", "durationUs=" + RequestTrace.elapsedMicros(started));
      }
    }
  }
}
