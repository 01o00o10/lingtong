/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

/** 为 Servlet 和 Filter 构造隔离的初始化配置快照。 */
final class ComponentConfigs {
  private ComponentConfigs() {}

  static ServletConfig servlet(String name, ServletContext context) {
    return servlet(name, context, Collections.<String, String>emptyMap());
  }

  /** 复制参数后再暴露给组件，避免注册表后续修改影响已初始化实例。 */
  static ServletConfig servlet(
      String name, ServletContext context, Map<String, String> initParameters) {
    final Map<String, String> parameters =
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(initParameters));
    return new ServletConfig() {
      @Override
      public String getServletName() {
        return name;
      }

      @Override
      public ServletContext getServletContext() {
        return context;
      }

      @Override
      public String getInitParameter(String parameterName) {
        return parameters.get(parameterName);
      }

      @Override
      public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(parameters.keySet());
      }
    };
  }

  static FilterConfig filter(String name, ServletContext context) {
    return filter(name, context, Collections.<String, String>emptyMap());
  }

  /** 为 Filter 提供与 ServletConfig 相同的参数快照语义。 */
  static FilterConfig filter(
      String name, ServletContext context, Map<String, String> initParameters) {
    final Map<String, String> parameters =
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(initParameters));
    return new FilterConfig() {
      @Override
      public String getFilterName() {
        return name;
      }

      @Override
      public ServletContext getServletContext() {
        return context;
      }

      @Override
      public String getInitParameter(String parameterName) {
        return parameters.get(parameterName);
      }

      @Override
      public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(parameters.keySet());
      }
    };
  }
}
