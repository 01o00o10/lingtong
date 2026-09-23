/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.MappingMatch;

/** 实现 Servlet URL 模式分类、匹配及 HttpServletMapping 视图。 */
final class UrlPattern {
  /** 模式种类决定路由优先级和 ServletPath/路径Info 的计算方式。 */
  private enum Kind {
    /** 空字符串模式，仅匹配 Context 根路径。 */
    CONTEXT_ROOT,
    /** 完整路径精确匹配。 */
    EXACT,
    /** 以 /* 结尾的路径前缀匹配。 */
    PATH,
    /** 以 *. 开头的扩展名匹配。 */
    EXTENSION,
    /** 单独的 /，作为兜底映射。 */
    DEFAULT
  }

  /** 注册时的原始 URL 模式。 */
  private final String value;
  /** 经校验后的模式类型。 */
  private final Kind kind;

  UrlPattern(String value) {
    if (value == null) {
      throw new IllegalArgumentException("URL pattern must not be null");
    }
    this.value = value;
    this.kind = classify(value);
  }

  String value() {
    return value;
  }

  /** 判断 Context 内路径是否匹配，调用方负责不同模式间的优先级。 */
  boolean matches(String path) {
    switch (kind) {
      case CONTEXT_ROOT:
        return "/".equals(path);
      case EXACT:
        return value.equals(path);
      case PATH:
        String prefix = pathPrefix();
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
      case EXTENSION:
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash && path.substring(dot + 1).equals(value.substring(2));
      case DEFAULT:
        return true;
      default:
        return false;
    }
  }

  /** 路径前缀长度，用于在多个路径模式间选择最长匹配。 */
  int pathLength() {
    return kind == Kind.PATH ? pathPrefix().length() : -1;
  }

  boolean isExact() {
    return kind == Kind.EXACT || kind == Kind.CONTEXT_ROOT;
  }

  boolean isPath() {
    return kind == Kind.PATH;
  }

  boolean isExtension() {
    return kind == Kind.EXTENSION;
  }

  /** 根据映射类型计算 Servlet 规范暴露的 ServletPath。 */
  String servletPath(String path) {
    if (kind == Kind.CONTEXT_ROOT || (kind == Kind.PATH && pathPrefix().isEmpty())) {
      return "";
    }
    if (kind == Kind.PATH) {
      return pathPrefix();
    }
    return path;
  }

  /** 路径前缀后的剩余部分；非路径映射返回空值。 */
  String pathInfo(String path) {
    if (kind == Kind.CONTEXT_ROOT) {
      return "/";
    }
    if (kind != Kind.PATH) {
      return null;
    }
    String prefix = pathPrefix();
    if (prefix.isEmpty()) {
      return path;
    }
    return path.length() == prefix.length() ? null : path.substring(prefix.length());
  }

  /** 构造 Servlet 4.0 映射信息，供请求对象和框架读取。 */
  HttpServletMapping mapping(String path, String servletName) {
    final String matchValue = matchValue(path);
    final MappingMatch mappingMatch = mappingMatch();
    return new HttpServletMapping() {
      @Override
      public String getMatchValue() {
        return matchValue;
      }

      @Override
      public String getPattern() {
        return value;
      }

      @Override
      public String getServletName() {
        return servletName;
      }

      @Override
      public MappingMatch getMappingMatch() {
        return mappingMatch;
      }
    };
  }

  private String pathPrefix() {
    return value.substring(0, value.length() - 2);
  }

  private String matchValue(String path) {
    switch (kind) {
      case CONTEXT_ROOT:
      case DEFAULT:
        return "";
      case EXACT:
        return path.startsWith("/") ? path.substring(1) : path;
      case PATH:
        String info = pathInfo(path);
        return info == null ? "" : stripLeadingSlash(info);
      case EXTENSION:
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot);
      default:
        return "";
    }
  }

  private MappingMatch mappingMatch() {
    switch (kind) {
      case CONTEXT_ROOT:
        return MappingMatch.CONTEXT_ROOT;
      case EXACT:
        return MappingMatch.EXACT;
      case PATH:
        return MappingMatch.PATH;
      case EXTENSION:
        return MappingMatch.EXTENSION;
      case DEFAULT:
        return MappingMatch.DEFAULT;
      default:
        throw new IllegalStateException("unknown mapping kind");
    }
  }

  /** 在注册阶段拒绝非法模式，避免请求时再处理错误配置。 */
  private static Kind classify(String pattern) {
    if (pattern.isEmpty()) {
      return Kind.CONTEXT_ROOT;
    }
    if ("/".equals(pattern)) {
      return Kind.DEFAULT;
    }
    if (pattern.startsWith("*.")
        && pattern.length() > 2
        && pattern.indexOf('/', 2) < 0
        && pattern.indexOf('*', 1) < 0) {
      return Kind.EXTENSION;
    }
    if (pattern.startsWith("/")
        && pattern.endsWith("/*")
        && pattern.indexOf('*') == pattern.length() - 1) {
      return Kind.PATH;
    }
    if (pattern.startsWith("/") && pattern.indexOf('*') < 0) {
      return Kind.EXACT;
    }
    throw new IllegalArgumentException("invalid Servlet URL pattern: " + pattern);
  }

  private static String stripLeadingSlash(String value) {
    return value.startsWith("/") ? value.substring(1) : value;
  }
}
