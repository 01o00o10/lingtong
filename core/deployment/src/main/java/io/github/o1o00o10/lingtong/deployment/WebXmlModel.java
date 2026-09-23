/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import io.github.o1o00o10.lingtong.servlet.ServletApplication;
import io.github.o1o00o10.lingtong.servlet.ServletSecurityPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletSecurityElement;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;

/** 部署描述符的可变中间模型；合并完成后再应用到 ServletApplication。 */
final class WebXmlModel {
  /** true 表示主描述符关闭 fragment 和应用注解扫描。 */
  boolean metadataComplete;
  /** 应用展示名。 */
  String displayName;
  /** web-fragment.xml 的唯一名称，主描述符通常为空。 */
  String fragmentName;
  /** 主 web.xml 对库的绝对排序要求。 */
  Ordering absoluteOrdering;
  /** 当前 fragment 对库的相对排序要求。 */
  Ordering relativeOrdering;
  /** ServletContext 初始化参数，保持声明顺序。 */
  final Map<String, String> contextParameters = new LinkedHashMap<String, String>();
  /** 以注册名索引的 Servlet 声明。 */
  final Map<String, ServletModel> servlets = new LinkedHashMap<String, ServletModel>();
  /** 以注册名索引的 Filter 声明。 */
  final Map<String, FilterModel> filters = new LinkedHashMap<String, FilterModel>();
  /** Servlet URL 映射，保持描述符合并后的顺序。 */
  final List<ServletMappingModel> servletMappings = new ArrayList<ServletMappingModel>();
  /** Filter 映射，顺序影响请求过滤链。 */
  final List<FilterMappingModel> filterMappings = new ArrayList<FilterMappingModel>();
  /** Listener 类名，按启动时应注册的顺序排列。 */
  final List<String> listeners = new ArrayList<String>();
  /** 状态码或异常类型到错误页的映射声明。 */
  final List<ErrorPageModel> errorPages = new ArrayList<ErrorPageModel>();
  /** 文件扩展名到 MIME 类型的映射。 */
  final Map<String, String> mimeMappings = new LinkedHashMap<String, String>();
  /** Locale 标识到响应字符集的映射。 */
  final Map<String, String> localeEncodingMappings = new LinkedHashMap<String, String>();
  /** 首页文件名，顺序决定查找优先级。 */
  final List<String> welcomeFiles = new ArrayList<String>();
  /** 发现的 SCI 及其 HandlesTypes 匹配结果。 */
  final List<InitializerModel> initializers = new ArrayList<InitializerModel>();
  /** 声明的安全角色。 */
  final Set<String> securityRoles = new LinkedHashSet<String>();
  /** URL、HTTP 方法和授权规则的组合。 */
  final List<SecurityConstraintModel> securityConstraints =
      new ArrayList<SecurityConstraintModel>();
  /** 是否拒绝约束未覆盖的 HTTP 方法。 */
  boolean denyUncoveredHttpMethods;
  /** 默认请求字符集，未设置时为空。 */
  String requestCharacterEncoding;
  /** 默认响应字符集，未设置时为空。 */
  String responseCharacterEncoding;
  /** 显式排序后的库文件名，未指定排序时为空。 */
  List<String> orderedLibraries;
  /** Session 与 Cookie 设置，未声明时为空。 */
  SessionModel session;
  /** 登录方式与表单页设置，未声明时为空。 */
  LoginModel login;

  /** 注册安全策略、错误页及启动回调；描述符条目在回调中写入 ServletContext。 */
  void apply(ServletApplication.Builder builder, ClassLoader classLoader)
      throws WebAppDeploymentException {
    if (displayName != null) builder.displayName(displayName);
    if (!mimeMappings.isEmpty()) builder.mimeMappings(mimeMappings);
    if (!localeEncodingMappings.isEmpty()) {
      builder.localeEncodingMappings(localeEncodingMappings);
    }
    if (!welcomeFiles.isEmpty()) builder.welcomeFiles(welcomeFiles);
    applySecurity(builder);
    for (ErrorPageModel page : errorPages) {
      if (page.errorCode != null) {
        builder.errorPage(page.errorCode, page.location);
      } else if (page.exceptionType != null) {
        try {
          Class<?> type = Class.forName(page.exceptionType, false, classLoader);
          builder.errorPage(type.asSubclass(Throwable.class), page.location);
        } catch (ClassNotFoundException | ClassCastException e) {
          throw new WebAppDeploymentException(
              "cannot load error-page exception " + page.exceptionType, e);
        }
      } else {
        builder.errorPage(page.location);
      }
    }
    for (int i = initializers.size() - 1; i >= 0; i--) {
      InitializerModel initializer = initializers.get(i);
      builder.initializerFirst(initializer.initializer, initializer.handledTypes, true);
    }
    builder.initializerFirst(new DescriptorInitializer(this));
  }

  /** 单个 Servlet 声明及其可选配置。 */
  static final class ServletModel {
    /** Servlet 注册名。 */
    String name;
    /** 实现类的二进制名称。 */
    String className;
    /** ServletConfig 初始化参数。 */
    final Map<String, String> initParameters = new LinkedHashMap<String, String>();
    /** 合并后的异步支持状态。 */
    boolean asyncSupported;
    /** 描述符是否明确指定异步支持，用于覆盖注解默认值。 */
    boolean asyncSupportedSpecified;
    /** 此声明是否来自 @WebServlet。 */
    boolean annotationDeclared;
    /** 启动加载顺序；空值表示未指定。 */
    Integer loadOnStartup;
    /** Multipart 上传限制；未声明时为空。 */
    MultipartConfigElement multipartConfig;
    /** Servlet 内角色别名到应用角色名的映射。 */
    final Map<String, String> roleReferences = new LinkedHashMap<String, String>();
    /** Servlet 粒度安全规则；未声明时为空。 */
    ServletSecurityElement servletSecurity;
  }

  /** 单个 Filter 声明及其可选配置。 */
  static final class FilterModel {
    /** Filter 注册名。 */
    String name;
    /** 实现类的二进制名称。 */
    String className;
    /** FilterConfig 初始化参数。 */
    final Map<String, String> initParameters = new LinkedHashMap<String, String>();
    /** 合并后的异步支持状态。 */
    boolean asyncSupported;
    /** 描述符是否明确指定异步支持。 */
    boolean asyncSupportedSpecified;
    /** 此声明是否来自 @WebFilter。 */
    boolean annotationDeclared;
  }

  /** 一个 Servlet 可映射多个 URL 模式。 */
  static final class ServletMappingModel {
    /** 目标 Servlet 注册名。 */
    String servletName;
    /** 按声明顺序排列的 URL 模式。 */
    final List<String> urlPatterns = new ArrayList<String>();
  }

  /** Filter 对 URL 或 Servlet 名称的映射。 */
  static final class FilterMappingModel {
    /** 目标 Filter 注册名。 */
    String filterName;
    /** URL 模式集合。 */
    final List<String> urlPatterns = new ArrayList<String>();
    /** Servlet 名称集合。 */
    final List<String> servletNames = new ArrayList<String>();
    /** DispatcherType 集合；为空时默认 REQUEST。 */
    final Set<DispatcherType> dispatchers = new LinkedHashSet<DispatcherType>();
  }

  /** 错误页匹配条件与目标位置。 */
  static final class ErrorPageModel {
    /** HTTP 状态码条件，未指定时为空。 */
    Integer errorCode;
    /** 异常类名条件，未指定时为空。 */
    String exceptionType;
    /** 应用内错误页路径。 */
    String location;
  }

  /** web.xml 的 Session 和 Cookie 配置。 */
  static final class SessionModel {
    /** Session 超时分钟数；未指定时为空。 */
    Integer timeoutMinutes;
    /** Session Cookie 名称。 */
    String cookieName;
    /** Session Cookie Domain 属性。 */
    String cookieDomain;
    /** Session Cookie Path 属性。 */
    String cookiePath;
    /** Session Cookie Comment 属性。 */
    String cookieComment;
    /** Session Cookie 最大寿命，单位秒。 */
    Integer cookieMaxAge;
    /** Session Cookie HttpOnly 标志，空值表示沿用默认配置。 */
    Boolean cookieHttpOnly;
    /** Session Cookie Secure 标志，空值表示沿用默认配置。 */
    Boolean cookieSecure;
    /** 显式启用的 Session 跟踪方式。 */
    final Set<SessionTrackingMode> trackingModes = new LinkedHashSet<SessionTrackingMode>();
  }

  /** 登录方式和表单认证页面。 */
  static final class LoginModel {
    /** BASIC、FORM 或 CLIENT_CERT 等认证方式。 */
    ServletSecurityPolicy.AuthenticationMethod authenticationMethod;
    /** BASIC 认证域名，未配置时为空。 */
    String realmName;
    /** FORM 认证登录页，非 FORM 时为空。 */
    String formLoginPage;
    /** FORM 认证失败页，非 FORM 时为空。 */
    String formErrorPage;

    boolean sameAs(LoginModel other) {
      return other != null
          && authenticationMethod == other.authenticationMethod
          && equal(realmName, other.realmName)
          && equal(formLoginPage, other.formLoginPage)
          && equal(formErrorPage, other.formErrorPage);
    }

    private static boolean equal(Object left, Object right) {
      return left == null ? right == null : left.equals(right);
    }
  }

  /** 一组 URL 模式上的方法、角色与传输保障规则。 */
  static final class SecurityConstraintModel {
    /** 受约束的 URL 模式。 */
    final List<String> patterns = new ArrayList<String>();
    /** 明确列出的 HTTP 方法。 */
    final Set<String> methods = new LinkedHashSet<String>();
    /** method-omission 中排除的 HTTP 方法。 */
    final Set<String> omittedMethods = new LinkedHashSet<String>();
    /** 是否声明 auth-constraint；声明但无角色表示拒绝访问。 */
    boolean authConstraint;
    /** 允许访问的角色集合。 */
    final Set<String> roles = new LinkedHashSet<String>();
    /** 是否要求受保护传输。 */
    ServletSecurityPolicy.TransportGuarantee transportGuarantee =
        ServletSecurityPolicy.TransportGuarantee.NONE;
  }

  /** web-fragment 的相对顺序或 web.xml 的绝对顺序。 */
  static final class Ordering {
    /** 必须排在这些具名 fragment 之前，绝对排序时充当名称列表。 */
    final List<String> before = new ArrayList<String>();
    /** 必须排在这些具名 fragment 之后。 */
    final List<String> after = new ArrayList<String>();
    /** 相对排序中的 before/others 标记。 */
    boolean beforeOthers;
    /** 相对排序中的 after/others 标记。 */
    boolean afterOthers;
    /** 绝对排序中 others 插入 before 列表的位置。 */
    Integer othersPosition;
  }

  /** 单个 SCI 实例及预先匹配出的 HandlesTypes 类集合。 */
  static final class InitializerModel {
    /** ServletContainerInitializer 实例。 */
    final ServletContainerInitializer initializer;
    /** HandlesTypes 匹配类；未声明兴趣类型时可为空。 */
    final Set<Class<?>> handledTypes;

    InitializerModel(ServletContainerInitializer initializer, Set<Class<?>> handledTypes) {
      this.initializer = initializer;
      this.handledTypes = handledTypes;
    }
  }

  /** 在应用启动阶段把描述符声明转换为动态注册。 */
  private static final class DescriptorInitializer implements ServletContainerInitializer {
    /** 编译完成的部署模型。 */
    private final WebXmlModel model;

    private DescriptorInitializer(WebXmlModel model) {
      this.model = model;
    }

    /** 按参数、监听器、Servlet、Filter、Session 的顺序写入上下文。 */
    @Override
    public void onStartup(Set<Class<?>> handledTypes, ServletContext context)
        throws ServletException {
      if (model.orderedLibraries != null) {
        context.setAttribute(
            ServletContext.ORDERED_LIBS,
            Collections.unmodifiableList(new ArrayList<String>(model.orderedLibraries)));
      }
      for (Map.Entry<String, String> parameter : model.contextParameters.entrySet()) {
        if (!context.setInitParameter(parameter.getKey(), parameter.getValue())) {
          throw new ServletException("duplicate context parameter: " + parameter.getKey());
        }
      }
      for (String listener : model.listeners) {
        context.addListener(listener);
      }
      for (ServletModel servlet : model.servlets.values()) {
        ServletRegistration.Dynamic registration =
            context.addServlet(servlet.name, servlet.className);
        if (registration == null) {
          throw new ServletException("duplicate Servlet name: " + servlet.name);
        }
        Set<String> conflicts = registration.setInitParameters(servlet.initParameters);
        if (!conflicts.isEmpty()) {
          throw new ServletException("duplicate Servlet init parameters: " + conflicts);
        }
        registration.setAsyncSupported(servlet.asyncSupported);
        if (servlet.loadOnStartup != null) {
          registration.setLoadOnStartup(servlet.loadOnStartup);
        }
        if (servlet.multipartConfig != null) {
          registration.setMultipartConfig(servlet.multipartConfig);
        }
      }
      for (ServletMappingModel mapping : model.servletMappings) {
        ServletRegistration registration = context.getServletRegistration(mapping.servletName);
        if (!(registration instanceof ServletRegistration.Dynamic)) {
          throw new ServletException("undefined Servlet mapping target: " + mapping.servletName);
        }
        Set<String> conflicts =
            ((ServletRegistration.Dynamic) registration)
                .addMapping(mapping.urlPatterns.toArray(new String[mapping.urlPatterns.size()]));
        if (!conflicts.isEmpty()) {
          throw new ServletException("conflicting Servlet URL patterns: " + conflicts);
        }
      }
      for (ServletModel servlet : model.servlets.values()) {
        if (servlet.servletSecurity == null) continue;
        ServletRegistration registration = context.getServletRegistration(servlet.name);
        if (!(registration instanceof ServletRegistration.Dynamic)) {
          throw new ServletException("undefined Servlet security target: " + servlet.name);
        }
        ((ServletRegistration.Dynamic) registration).setServletSecurity(servlet.servletSecurity);
      }
      for (FilterModel filter : model.filters.values()) {
        FilterRegistration.Dynamic registration = context.addFilter(filter.name, filter.className);
        if (registration == null) {
          throw new ServletException("duplicate Filter name: " + filter.name);
        }
        Set<String> conflicts = registration.setInitParameters(filter.initParameters);
        if (!conflicts.isEmpty()) {
          throw new ServletException("duplicate Filter init parameters: " + conflicts);
        }
        registration.setAsyncSupported(filter.asyncSupported);
      }
      for (FilterMappingModel mapping : model.filterMappings) {
        FilterRegistration registration = context.getFilterRegistration(mapping.filterName);
        if (!(registration instanceof FilterRegistration.Dynamic)) {
          throw new ServletException("undefined Filter mapping target: " + mapping.filterName);
        }
        FilterRegistration.Dynamic dynamic = (FilterRegistration.Dynamic) registration;
        EnumSet<DispatcherType> dispatchers =
            mapping.dispatchers.isEmpty()
                ? EnumSet.of(DispatcherType.REQUEST)
                : EnumSet.copyOf(mapping.dispatchers);
        if (!mapping.urlPatterns.isEmpty()) {
          dynamic.addMappingForUrlPatterns(
              dispatchers,
              true,
              mapping.urlPatterns.toArray(new String[mapping.urlPatterns.size()]));
        }
        if (!mapping.servletNames.isEmpty()) {
          dynamic.addMappingForServletNames(
              dispatchers,
              true,
              mapping.servletNames.toArray(new String[mapping.servletNames.size()]));
        }
      }
      applySession(context, model.session);
      if (model.requestCharacterEncoding != null) {
        context.setRequestCharacterEncoding(model.requestCharacterEncoding);
      }
      if (model.responseCharacterEncoding != null) {
        context.setResponseCharacterEncoding(model.responseCharacterEncoding);
      }
    }

    private static void applySession(ServletContext context, SessionModel session) {
      if (session == null) {
        return;
      }
      if (session.timeoutMinutes != null) {
        context.setSessionTimeout(session.timeoutMinutes);
      }
      if (!session.trackingModes.isEmpty()) {
        context.setSessionTrackingModes(
            Collections.unmodifiableSet(
                new LinkedHashSet<SessionTrackingMode>(session.trackingModes)));
      }
      SessionCookieConfig cookie = context.getSessionCookieConfig();
      if (session.cookieName != null) cookie.setName(session.cookieName);
      if (session.cookieDomain != null) cookie.setDomain(session.cookieDomain);
      if (session.cookiePath != null) cookie.setPath(session.cookiePath);
      if (session.cookieComment != null) cookie.setComment(session.cookieComment);
      if (session.cookieMaxAge != null) cookie.setMaxAge(session.cookieMaxAge);
      if (session.cookieHttpOnly != null) cookie.setHttpOnly(session.cookieHttpOnly);
      if (session.cookieSecure != null) cookie.setSecure(session.cookieSecure);
    }
  }

  /** 将描述符中的角色、登录方式和约束汇总为容器安全策略。 */
  private void applySecurity(ServletApplication.Builder builder) {
    if (login == null
        && securityRoles.isEmpty()
        && securityConstraints.isEmpty()
        && !denyUncoveredHttpMethods) {
      return;
    }
    ServletSecurityPolicy.Builder policy = ServletSecurityPolicy.builder();
    policy.denyUncoveredHttpMethods(denyUncoveredHttpMethods);
    if (login != null) {
      if (login.authenticationMethod == ServletSecurityPolicy.AuthenticationMethod.BASIC) {
        policy.basicAuthentication(login.realmName);
      } else if (login.authenticationMethod == ServletSecurityPolicy.AuthenticationMethod.FORM) {
        policy.formAuthentication(login.formLoginPage, login.formErrorPage);
      } else if (login.authenticationMethod
          == ServletSecurityPolicy.AuthenticationMethod.CLIENT_CERT) {
        policy.clientCertificateAuthentication();
      }
    }
    for (String role : securityRoles) policy.role(role);
    for (ServletModel servlet : servlets.values()) {
      for (Map.Entry<String, String> reference : servlet.roleReferences.entrySet()) {
        policy.roleReference(servlet.name, reference.getKey(), reference.getValue());
      }
    }
    for (SecurityConstraintModel constraint : securityConstraints) {
      ServletSecurityPolicy.Authorization authorization =
          !constraint.authConstraint
              ? ServletSecurityPolicy.Authorization.PERMIT
              : constraint.roles.isEmpty()
                  ? ServletSecurityPolicy.Authorization.DENY
                  : ServletSecurityPolicy.Authorization.ROLES;
      policy.constraint(
          constraint.patterns,
          constraint.methods,
          constraint.omittedMethods,
          authorization,
          constraint.roles,
          constraint.transportGuarantee);
    }
    builder.securityPolicy(policy.build());
    LinkedHashSet<String> descriptorPatterns = new LinkedHashSet<String>();
    for (SecurityConstraintModel constraint : securityConstraints) {
      descriptorPatterns.addAll(constraint.patterns);
    }
    builder.descriptorSecurityPatterns(descriptorPatterns);
  }
}
