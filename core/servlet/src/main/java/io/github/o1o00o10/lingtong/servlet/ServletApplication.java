/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.HttpResponse;
import io.github.o1o00o10.lingtong.http.RequestTrace;
import io.github.o1o00o10.lingtong.transport.StreamingResponseConsumer;
import io.github.o1o00o10.lingtong.transport.StreamingResponseProcessor;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.HttpConstraintElement;
import javax.servlet.HttpMethodConstraintElement;
import javax.servlet.MultipartConfigElement;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.ServletSecurityElement;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;
import javax.servlet.http.MappingMatch;

/**
 * 单个 Web 应用的运行边界。建议按 prepare/start -> beginRequest -> dispatch/FilterChain -> finishRequest ->
 * close 阅读。网络读写不在这里，入口由 KernelProcessor 调用。 注册阶段允许 SCI/Listener 增加组件；启动成功后冻结路由，工作线程只读取运行态。
 */
public final class ServletApplication implements StreamingResponseProcessor, AutoCloseable {
  /** 记录已被错误页接管的 Servlet 故障，避免只留下最终的 500 状态。 */
  private static final Logger LOGGER = Logger.getLogger(ServletApplication.class.getName());
  /** 未匹配路由时复用的 404 响应。 */
  private static final HttpResponse NOT_FOUND = HttpResponse.text(404, "Not Found", "Not Found\n");
  /** 应用停止接收请求时复用的 503 响应。 */
  private static final HttpResponse SERVICE_UNAVAILABLE =
      HttpResponse.text(503, "Service Unavailable", "Service Unavailable\n");
  /** 请求无法被 Servlet 层接受时复用的 400 响应。 */
  private static final HttpResponse BAD_REQUEST =
      HttpResponse.text(400, "Bad Request", "Bad Request\n");
  /** 未映射状态。 */
  private static final HttpServletMapping UNMAPPED =
      new HttpServletMapping() {
        @Override
        public String getMatchValue() {
          return "";
        }

        @Override
        public String getPattern() {
          return "";
        }

        @Override
        public String getServletName() {
          return "";
        }

        @Override
        public MappingMatch getMappingMatch() {
          return null;
        }
      };

  /** 串行化应用启动、停止与排空等待的监视器。 */
  private final Object lifecycleMonitor = new Object();
  /** 上下文路径。 */
  private final String contextPath;
  /** 资源根目录。 */
  private final ServletResourceRoot resourceRoot;
  /** 默认Servlet启用标志，布尔标志。 */
  private final boolean defaultServletEnabled;
  /** 按顺序保存的首页文件列表。 */
  private final List<String> welcomeFiles;
  /** 按键索引的地区编码映射集合。 */
  private final Map<String, String> localeEncodingMappings;
  /** 安全策略。 */
  private final ServletSecurityPolicy securityPolicy;
  /** 安全身份源。 */
  private final ServletSecurityRealm securityRealm;
  /** 客户端证书身份源。 */
  private final ServletClientCertificateRealm clientCertificateRealm;
  /** 描述符安全模式集合。 */
  private final Set<String> descriptorSecurityPatterns;
  /** 已声明的安全角色集合。 */
  private final Set<String> declaredSecurityRoles;
  /** 最大资源字节数，单位为字节。 */
  private final long maxResourceBytes;
  /** 最大响应字节数，单位为字节。 */
  private final long maxResponseBytes;
  /** 响应消息体缓冲区字节数，单位为字节。 */
  private final int responseBodyBufferBytes;
  /** 响应消息体低水位字节数，单位为字节。 */
  private final int responseBodyLowWaterBytes;
  /** 停机排空超时时长毫秒，单位为毫秒。 */
  private final long shutdownDrainTimeoutMillis;
  /** 请求限制集合。 */
  private final ServletRequestLimits requestLimits;
  /** 应用类加载器。 */
  private final ClassLoader applicationClassLoader;
  /** 托管的资源。 */
  private final AutoCloseable managedResource;
  /** 活动请求集合。 */
  private final Set<ServletAsyncContext> activeRequests =
      Collections.newSetFromMap(new IdentityHashMap<ServletAsyncContext, Boolean>());
  /** 按顺序保存的Servlet定义集合。 */
  private List<ServletDefinition> servletDefinitions;
  /** 按顺序保存的已初始化的servlets。 */
  private final List<ServletDefinition> initializedServlets = new ArrayList<ServletDefinition>();
  /** 按顺序保存的过滤器定义集合。 */
  private List<FilterDefinition> filterDefinitions;
  /** 按顺序保存的Servlet路由集合。 */
  private List<ServletRoute> servletRoutes;
  /** 按顺序保存的过滤器路由集合。 */
  private List<FilterRoute> filterRoutes;
  /** 按顺序保存的上下文监听器集合。 */
  private List<ServletContextListener> contextListeners;
  /** 受限的上下文监听器集合。 */
  private final Set<ServletContextListener> restrictedContextListeners =
      Collections.newSetFromMap(new IdentityHashMap<ServletContextListener, Boolean>());
  /** 按顺序保存的请求监听器集合。 */
  private List<ServletRequestListener> requestListeners;
  /** 按顺序保存的上下文属性监听器集合。 */
  private List<ServletContextAttributeListener> contextAttributeListeners;
  /** 按顺序保存的请求属性监听器集合。 */
  private List<ServletRequestAttributeListener> requestAttributeListeners;
  /** 按顺序保存的会话监听器集合。 */
  private List<HttpSessionListener> sessionListeners;
  /** 按顺序保存的会话属性监听器集合。 */
  private List<HttpSessionAttributeListener> sessionAttributeListeners;
  /** 按顺序保存的会话标识符监听器集合。 */
  private List<HttpSessionIdListener> sessionIdListeners;
  /** 按顺序保存的initializers。 */
  private final List<InitializerRegistration> initializers;
  /** Servlet名称集合。 */
  private final Set<String> servletNames;
  /** 按键索引的Servlet模式owners。 */
  private final Map<String, ServletDefinition> servletPatternOwners;
  /** 过滤器名称集合。 */
  private final Set<String> filterNames;
  /** 按键索引的上下文初始化参数集合。 */
  private final Map<String, String> contextInitParameters = new LinkedHashMap<String, String>();
  /** 按键索引的状态码错误页面集合。 */
  private final Map<Integer, String> statusErrorPages;
  /** 按键索引的异常错误页面集合。 */
  private final Map<Class<? extends Throwable>, String> exceptionErrorPages;
  /** 默认错误页面。 */
  private final String defaultErrorPage;
  /** 最大会话集合。 */
  private final int maxSessions;
  /** 会话超时时长秒，单位为秒。 */
  private int sessionTimeoutSeconds;
  /** 会话存储后端。 */
  private final SessionStore sessionStore;
  /** 请求字符编码。 */
  private String requestCharacterEncoding;
  /** 响应字符编码。 */
  private String responseCharacterEncoding;
  /** 会话Cookie配置。 */
  private final MutableSessionCookieConfig sessionCookieConfig;
  /** 会话跟踪模式集合。 */
  private Set<SessionTrackingMode> sessionTrackingModes = defaultTrackingModes();
  /** Servlet上下文门面。 */
  private final ServletContextFacade servletContextFacade;
  /** Servlet上下文。 */
  private final ServletContext servletContext;
  /** 会话管理器。 */
  private volatile SessionManager sessionManager;
  /** 安全运行时。 */
  private volatile ServletSecurityRuntime securityRuntime;
  /** 属性事件集合。 */
  private volatile ServletAttributeEventDispatcher attributeEvents;
  /** 注册信息打开，布尔标志。 */
  private boolean registrationOpen;
  /** restrict已添加的上下文监听器集合，布尔标志。 */
  private boolean restrictAddedContextListeners = true;
  /** 注册信息prepared，布尔标志。 */
  private boolean registrationPrepared;
  /** accepting请求集合，布尔标志。 */
  private boolean acceptingRequests;
  /** 活动请求数量。 */
  private int activeRequestCount;
  /** 排空截止时间纳秒。 */
  private long drainDeadlineNanos;
  /** 已启动标志，布尔标志。 */
  private volatile boolean started;
  /** 异步执行器。 */
  private volatile Executor asyncExecutor =
      new Executor() {
        @Override
        public void execute(Runnable command) {
          throw new RejectedExecutionException("Servlet async executor is not configured");
        }
      };
  /** 异步调度器。 */
  private volatile ScheduledExecutorService asyncScheduler;
  /** 托管的资源已关闭标志，布尔标志。 */
  private boolean managedResourceClosed;
  /** 默认资源定义。 */
  private ServletDefinition defaultResourceDefinition;

  private ServletApplication(
      List<ServletDefinition> servletDefinitions,
      List<FilterDefinition> filterDefinitions,
      List<ServletRoute> servletRoutes,
      List<FilterRoute> filterRoutes,
      List<ServletContextListener> contextListeners,
      List<ServletRequestListener> requestListeners,
      List<ServletContextAttributeListener> contextAttributeListeners,
      List<ServletRequestAttributeListener> requestAttributeListeners,
      List<HttpSessionListener> sessionListeners,
      List<HttpSessionAttributeListener> sessionAttributeListeners,
      List<HttpSessionIdListener> sessionIdListeners,
      List<InitializerRegistration> initializers,
      int maxSessions,
      int sessionTimeoutSeconds,
      String sessionCookieSameSite,
      SessionStore sessionStore,
      String requestCharacterEncoding,
      String responseCharacterEncoding,
      Map<Integer, String> statusErrorPages,
      Map<Class<? extends Throwable>, String> exceptionErrorPages,
      String defaultErrorPage,
      String contextPath,
      String displayName,
      ServletResourceRoot resourceRoot,
      boolean defaultServletEnabled,
      List<String> welcomeFiles,
      Map<String, String> localeEncodingMappings,
      ServletSecurityPolicy securityPolicy,
      ServletSecurityRealm securityRealm,
      ServletClientCertificateRealm clientCertificateRealm,
      Set<String> descriptorSecurityPatterns,
      Path temporaryDirectory,
      long maxResourceBytes,
      long maxResponseBytes,
      int responseBodyBufferBytes,
      int responseBodyLowWaterBytes,
      long shutdownDrainTimeoutMillis,
      ServletRequestLimits requestLimits,
      ClassLoader applicationClassLoader,
      AutoCloseable managedResource) {
    this.contextPath = contextPath;
    this.resourceRoot = resourceRoot;
    this.defaultServletEnabled = defaultServletEnabled;
    this.welcomeFiles = Collections.unmodifiableList(new ArrayList<String>(welcomeFiles));
    this.localeEncodingMappings =
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(localeEncodingMappings));
    this.securityPolicy = securityPolicy;
    this.securityRealm = securityRealm;
    this.clientCertificateRealm = clientCertificateRealm;
    this.descriptorSecurityPatterns =
        Collections.unmodifiableSet(new LinkedHashSet<String>(descriptorSecurityPatterns));
    this.declaredSecurityRoles = new LinkedHashSet<String>(securityPolicy.roles());
    this.maxResourceBytes = maxResourceBytes;
    this.maxResponseBytes = maxResponseBytes;
    this.responseBodyBufferBytes = responseBodyBufferBytes;
    this.responseBodyLowWaterBytes = responseBodyLowWaterBytes;
    this.shutdownDrainTimeoutMillis = shutdownDrainTimeoutMillis;
    this.requestLimits = requestLimits;
    ClassLoader contextClassLoader =
        applicationClassLoader == null
            ? Thread.currentThread().getContextClassLoader()
            : applicationClassLoader;
    this.applicationClassLoader =
        contextClassLoader == null ? ServletApplication.class.getClassLoader() : contextClassLoader;
    this.managedResource = managedResource;
    this.servletDefinitions = new ArrayList<ServletDefinition>(servletDefinitions);
    this.filterDefinitions = new ArrayList<FilterDefinition>(filterDefinitions);
    this.servletRoutes = new ArrayList<ServletRoute>(servletRoutes);
    this.filterRoutes = new ArrayList<FilterRoute>(filterRoutes);
    this.contextListeners = new ArrayList<ServletContextListener>(contextListeners);
    this.requestListeners = new ArrayList<ServletRequestListener>(requestListeners);
    this.contextAttributeListeners =
        new ArrayList<ServletContextAttributeListener>(contextAttributeListeners);
    this.requestAttributeListeners =
        new ArrayList<ServletRequestAttributeListener>(requestAttributeListeners);
    this.sessionListeners = new ArrayList<HttpSessionListener>(sessionListeners);
    this.sessionAttributeListeners =
        new ArrayList<HttpSessionAttributeListener>(sessionAttributeListeners);
    this.sessionIdListeners = new ArrayList<HttpSessionIdListener>(sessionIdListeners);
    this.initializers =
        Collections.unmodifiableList(new ArrayList<InitializerRegistration>(initializers));
    this.servletNames = new HashSet<String>();
    this.servletPatternOwners = new LinkedHashMap<String, ServletDefinition>();
    this.filterNames = new HashSet<String>();
    for (ServletDefinition definition : this.servletDefinitions) {
      servletNames.add(definition.name);
    }
    for (ServletRoute route : this.servletRoutes) {
      servletPatternOwners.put(route.pattern.value(), route.definition);
    }
    for (FilterDefinition definition : this.filterDefinitions) {
      filterNames.add(definition.name);
    }
    this.maxSessions = maxSessions;
    this.sessionTimeoutSeconds = sessionTimeoutSeconds;
    this.sessionStore = sessionStore;
    this.requestCharacterEncoding = requestCharacterEncoding;
    this.responseCharacterEncoding = responseCharacterEncoding;
    this.statusErrorPages =
        Collections.unmodifiableMap(new LinkedHashMap<Integer, String>(statusErrorPages));
    this.exceptionErrorPages =
        Collections.unmodifiableMap(
            new LinkedHashMap<Class<? extends Throwable>, String>(exceptionErrorPages));
    this.defaultErrorPage = defaultErrorPage;
    this.sessionCookieConfig =
        new MutableSessionCookieConfig(
            this::requireRegistrationOpen,
            contextPath.isEmpty() ? "/" : contextPath,
            sessionCookieSameSite);
    this.attributeEvents = ServletAttributeEventDispatcher.empty();
    this.servletContextFacade =
        ServletContextFacade.createFacade(
            new DynamicRegistrationController(),
            contextPath,
            displayName,
            resourceRoot,
            this.applicationClassLoader,
            temporaryDirectory);
    this.servletContext = servletContextFacade.context();
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean requiresClientCertificateAuthentication() {
    return securityPolicy.authenticationMethod()
        == ServletSecurityPolicy.AuthenticationMethod.CLIENT_CERT;
  }

  /**
   * Boot 刷新上下文时先运行 SCI/Initializer，允许动态注册 Controller 对应的 Servlet。 这里只构建注册计划；组件 init 和网络监听都留给
   * start()。
   */
  public void prepare() throws ServletException {
    ClassLoader previous = enterApplicationClassLoader();
    try {
      synchronized (lifecycleMonitor) {
        prepareRegistrations();
      }
    } finally {
      restoreContextClassLoader(previous);
    }
  }

  public void asyncExecutor(Executor value) {
    if (value == null) {
      throw new IllegalArgumentException("async executor must not be null");
    }
    synchronized (lifecycleMonitor) {
      if (started) {
        throw new IllegalStateException(
            "async executor cannot change while the application is running");
      }
      asyncExecutor = value;
    }
  }

  /**
   * 激活顺序：SCI -> Context Listener -> 运行态 -> Filter -> eager Servlet -> 冻结注册。 任一步失败都逆向销毁已激活组件并回滚
   * Listener 阶段的注册，避免半启动应用接流量。
   */
  public void start() throws ServletException {
    ClassLoader previous = enterApplicationClassLoader();
    try {
      synchronized (lifecycleMonitor) {
        if (started) {
          return;
        }
        List<FilterDefinition> initializedFilters = new ArrayList<FilterDefinition>();
        int initializedContextListeners = 0;
        ScheduledThreadPoolExecutor nextScheduler =
            new ScheduledThreadPoolExecutor(1, new ServletAsyncTimerThreadFactory());
        nextScheduler.setRemoveOnCancelPolicy(true);
        asyncScheduler = nextScheduler;
        RegistrationCheckpoint activationCheckpoint = null;
        try {
          prepare();
          // Context Listener 仍可动态注册组件，因此必须等所有回调结束再生成运行态。
          activationCheckpoint = new RegistrationCheckpoint();
          registrationOpen = true;
          refreshAttributeEvents();
          for (int i = 0; i < contextListeners.size(); i++) {
            ServletContextListener listener = contextListeners.get(i);
            ServletContext callbackContext =
                restrictedContextListeners.contains(listener)
                    ? servletContextFacade.restrictedContext()
                    : servletContext;
            listener.contextInitialized(new ServletContextEvent(callbackContext));
            initializedContextListeners++;
          }
          registrationOpen = false;
          prepareRuntimeState();
          for (FilterDefinition definition : filterDefinitions) {
            definition.filter.init(
                ComponentConfigs.filter(
                    definition.name, servletContext, definition.initParameters));
            initializedFilters.add(definition);
          }
          List<ServletDefinition> eagerServlets = new ArrayList<ServletDefinition>();
          for (ServletDefinition definition : servletDefinitions) {
            if (definition.loadOnStartup >= 0) eagerServlets.add(definition);
          }
          Collections.sort(eagerServlets, Comparator.comparingInt(value -> value.loadOnStartup));
          for (ServletDefinition definition : eagerServlets) {
            initializeServlet(definition);
          }
          freezeRegistrations();
          started = true;
          acceptingRequests = true;
          drainDeadlineNanos = 0L;
        } catch (ServletException | RuntimeException | LinkageError e) {
          registrationOpen = false;
          nextScheduler.shutdownNow();
          asyncScheduler = null;
          destroyInitializedServlets();
          resetServletInitializationFailures();
          destroyFilters(initializedFilters);
          if (sessionManager != null) {
            sessionManager.close();
            sessionManager = null;
          }
          securityRuntime = null;
          destroyContextListeners(initializedContextListeners);
          if (activationCheckpoint != null) {
            activationCheckpoint.rollback();
          }
          throw e;
        } finally {
          registrationOpen = false;
        }
      }
    } finally {
      restoreContextClassLoader(previous);
    }
  }

  /** SCI（含 Boot 初始化器）共享一个可回滚的注册阶段；失败后可重新准备。 */
  private void prepareRegistrations() throws ServletException {
    if (registrationPrepared) {
      return;
    }
    RegistrationCheckpoint checkpoint = new RegistrationCheckpoint();
    registrationOpen = true;
    try {
      for (InitializerRegistration initializer : initializers) {
        restrictAddedContextListeners = initializer.restrictAddedContextListeners;
        try {
          initializer.initializer.onStartup(initializer.handledTypes, servletContext);
        } finally {
          restrictAddedContextListeners = true;
        }
      }
      refreshAttributeEvents();
      registrationPrepared = true;
    } catch (ServletException | RuntimeException | LinkageError e) {
      checkpoint.rollback();
      throw e;
    } finally {
      registrationOpen = false;
    }
  }

  /** 复制为只读快照，让后续请求不会观察到正在修改的注册列表。 */
  private void freezeRegistrations() {
    servletDefinitions = immutableCopy(servletDefinitions);
    filterDefinitions = immutableCopy(filterDefinitions);
    servletRoutes = immutableCopy(servletRoutes);
    filterRoutes = immutableCopy(filterRoutes);
    contextListeners = immutableCopy(contextListeners);
    requestListeners = immutableCopy(requestListeners);
    contextAttributeListeners = immutableCopy(contextAttributeListeners);
    requestAttributeListeners = immutableCopy(requestAttributeListeners);
    sessionListeners = immutableCopy(sessionListeners);
    sessionAttributeListeners = immutableCopy(sessionAttributeListeners);
    sessionIdListeners = immutableCopy(sessionIdListeners);
  }

  /** 从注册结果建立 Session、安全、路由和静态资源运行态。 */
  private void prepareRuntimeState() {
    installDefaultResourceServlet();
    validateErrorPageTargets();
    validateSecurityTargets();
    refreshAttributeEvents();
    SessionEventDispatcher sessionEvents =
        new SessionEventDispatcher(sessionListeners, sessionAttributeListeners, sessionIdListeners);
    sessionManager =
        new SessionManager(
            maxSessions,
            sessionTimeoutSeconds,
            System::currentTimeMillis,
            sessionEvents,
            sessionCookieConfig,
            sessionStore,
            applicationClassLoader,
            servletContext);
    securityRuntime =
        new ServletSecurityRuntime(
            securityPolicy,
            securityRealm,
            clientCertificateRealm,
            declaredSecurityRoles,
            servletSecurityConstraints());
  }

  private void validateSecurityTargets() {
    if (securityPolicy.authenticationMethod() != ServletSecurityPolicy.AuthenticationMethod.FORM) {
      return;
    }
    if (resolve(securityPolicy.formLoginPage()) == null) {
      throw new IllegalStateException(
          "FORM login page has no Servlet mapping: " + securityPolicy.formLoginPage());
    }
    if (resolve(securityPolicy.formErrorPage()) == null) {
      throw new IllegalStateException(
          "FORM error page has no Servlet mapping: " + securityPolicy.formErrorPage());
    }
  }

  private List<ServletSecurityPolicy.Constraint> servletSecurityConstraints() {
    List<ServletSecurityPolicy.Constraint> result =
        new ArrayList<ServletSecurityPolicy.Constraint>();
    for (ServletDefinition definition : servletDefinitions) {
      if (definition.servletSecurity == null) continue;
      List<String> patterns = new ArrayList<String>();
      for (ServletRoute route : servletRoutes) {
        if (route.definition == definition
            && !descriptorSecurityPatterns.contains(route.pattern.value())) {
          patterns.add(route.pattern.value());
        }
      }
      if (!patterns.isEmpty()) {
        addServletSecurityConstraints(result, patterns, definition.servletSecurity);
      }
    }
    return result;
  }

  private static void addServletSecurityConstraints(
      List<ServletSecurityPolicy.Constraint> target,
      List<String> patterns,
      ServletSecurityElement element) {
    Set<String> methodNames = new LinkedHashSet<String>(element.getMethodNames());
    target.add(securityConstraint(patterns, Collections.<String>emptySet(), methodNames, element));
    for (HttpMethodConstraintElement method : element.getHttpMethodConstraints()) {
      target.add(
          securityConstraint(
              patterns,
              Collections.singleton(method.getMethodName()),
              Collections.<String>emptySet(),
              method));
    }
  }

  private static ServletSecurityPolicy.Constraint securityConstraint(
      List<String> patterns,
      Set<String> methods,
      Set<String> omittedMethods,
      HttpConstraintElement element) {
    Set<String> roles = new LinkedHashSet<String>();
    Collections.addAll(roles, element.getRolesAllowed());
    ServletSecurityPolicy.Authorization authorization;
    if (!roles.isEmpty()) {
      authorization = ServletSecurityPolicy.Authorization.ROLES;
    } else if (element.getEmptyRoleSemantic() == ServletSecurity.EmptyRoleSemantic.DENY) {
      authorization = ServletSecurityPolicy.Authorization.DENY;
    } else {
      authorization = ServletSecurityPolicy.Authorization.PERMIT;
    }
    ServletSecurityPolicy.TransportGuarantee guarantee =
        element.getTransportGuarantee() == ServletSecurity.TransportGuarantee.CONFIDENTIAL
            ? ServletSecurityPolicy.TransportGuarantee.CONFIDENTIAL
            : ServletSecurityPolicy.TransportGuarantee.NONE;
    return new ServletSecurityPolicy.Constraint(
        patterns, methods, omittedMethods, authorization, roles, guarantee);
  }

  private void installDefaultResourceServlet() {
    if (!defaultServletEnabled || resourceRoot == null || servletPatternOwners.containsKey("/")) {
      return;
    }
    String name = "lingtong-default-resources";
    for (int suffix = 2; servletNames.contains(name); suffix++) {
      name = "lingtong-default-resources-" + suffix;
    }
    ServletDefinition definition =
        new ServletDefinition(
            name, new DefaultResourceServlet(resourceRoot, maxResourceBytes, welcomeFiles));
    ServletRoute route = new ServletRoute(definition, new UrlPattern("/"));
    servletNames.add(name);
    servletDefinitions.add(definition);
    servletRoutes.add(route);
    servletPatternOwners.put("/", definition);
    defaultResourceDefinition = definition;
  }

  private WelcomeResolution resolveWelcome(String path, ServletRoute route) {
    if (resourceRoot == null
        || route == null
        || route.definition != defaultResourceDefinition
        || !path.endsWith("/")
        || !resourceRoot.isDirectory(path)) {
      return null;
    }
    for (String welcomeFile : welcomeFiles) {
      String candidate = path + welcomeFile;
      if (resourceRoot.isRegularFile(candidate)) {
        return new WelcomeResolution(candidate, resolve(candidate));
      }
    }
    for (String welcomeFile : welcomeFiles) {
      String candidate = path + welcomeFile;
      ServletRoute candidateRoute = resolve(candidate);
      if (candidateRoute != null && candidateRoute.definition != defaultResourceDefinition) {
        return new WelcomeResolution(candidate, candidateRoute);
      }
    }
    return null;
  }

  private void validateErrorPageTargets() {
    Set<String> paths = new LinkedHashSet<String>();
    paths.addAll(statusErrorPages.values());
    paths.addAll(exceptionErrorPages.values());
    if (defaultErrorPage != null) {
      paths.add(defaultErrorPage);
    }
    for (String path : paths) {
      Target target = Target.parse(path);
      if (resolve(target.path) == null) {
        throw new IllegalStateException("error page has no Servlet mapping: " + target.path);
      }
    }
  }

  private void refreshAttributeEvents() {
    attributeEvents =
        new ServletAttributeEventDispatcher(contextAttributeListeners, requestAttributeListeners);
    servletContextFacade.attributeEvents(attributeEvents);
  }

  private static <T> List<T> immutableCopy(List<T> source) {
    return Collections.unmodifiableList(new ArrayList<T>(source));
  }

  @Override
  public HttpResponse process(HttpRequest request) throws Exception {
    // 旧同步接口只接受立即完成的结果；异步 Servlet 必须走 completion API。
    CompletableFuture<HttpResponse> result = processAsync(request).toCompletableFuture();
    if (!result.isDone()) {
      throw new IllegalStateException(
          "asynchronous request requires the RequestProcessor completion API");
    }
    try {
      return result.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServletException("interrupted while completing Servlet request", e);
    } catch (ExecutionException e) {
      Throwable failure = e.getCause();
      if (failure instanceof Exception) {
        throw (Exception) failure;
      }
      if (failure instanceof LinkageError) {
        throw (LinkageError) failure;
      }
      throw new ServletException("Servlet request failed", failure);
    }
  }

  @Override
  public CompletionStage<HttpResponse> processAsync(HttpRequest request) {
    // 请求线程暂时切换为 Web 应用类加载器；真正的异步完成由 Lifecycle.finish 接管。
    CompletableFuture<HttpResponse> result = new CompletableFuture<HttpResponse>();
    ClassLoader previous = enterApplicationClassLoader();
    try {
      beginRequest(request, result, null, null);
    } catch (Exception | LinkageError e) {
      result.completeExceptionally(e);
    } finally {
      restoreContextClassLoader(previous);
    }
    return result;
  }

  @Override
  public CompletionStage<Void> processStreaming(
      HttpRequest request, StreamingResponseConsumer responseConsumer) {
    if (responseConsumer == null) {
      throw new IllegalArgumentException("streaming response consumer must not be null");
    }
    // 流式结果的 future 表示应用生命周期完成，响应体字节由 mailbox 独立交付。
    CompletableFuture<Void> lifecycle = new CompletableFuture<Void>();
    ClassLoader previous = enterApplicationClassLoader();
    try {
      beginRequest(request, null, responseConsumer, lifecycle);
    } catch (Exception | LinkageError e) {
      lifecycle.completeExceptionally(e);
    } finally {
      restoreContextClassLoader(previous);
    }
    return lifecycle;
  }

  /**
   * 统一的请求入口：准入计数 -> 请求校验/路由/安全 -> Listener -> Filter/Servlet。 创建 AsyncContext 后由其 finish
   * 回调负责收尾；创建前的提前返回由 finally 释放计数。 因此同步完成、异步完成、超时和错误都只走一次 finishRequest。
   */
  private void beginRequest(
      HttpRequest request,
      CompletableFuture<HttpResponse> result,
      StreamingResponseConsumer responseConsumer,
      CompletableFuture<Void> streamingLifecycle)
      throws Exception {
    RequestTrace trace = request.trace();
    ScheduledExecutorService scheduler;
    synchronized (lifecycleMonitor) {
      if (!started) {
        throw new IllegalStateException("Servlet application has not been started");
      }
      if (!acceptingRequests) {
        completeImmediate(SERVICE_UNAVAILABLE, result, responseConsumer, streamingLifecycle);
        return;
      }
      activeRequestCount++;
      scheduler = asyncScheduler;
    }
    if (trace != null) trace.event("servlet.admitted");

    // 只有交给 AsyncContext 后才能由完成回调释放准入名额。
    boolean lifecycleTransferred = false;
    try {
      try {
        requestLimits.validateCookies(request.headers());
      } catch (ServletRequestLimitException e) {
        if (trace != null) trace.event("servlet.request-rejected", "status=400");
        completeImmediate(BAD_REQUEST, result, responseConsumer, streamingLifecycle);
        return;
      }
      // 先剥离 context path，再按 Servlet 映射优先级选择目标；欢迎页可能改写目标。
      Target target = Target.parse(request.target());
      String applicationPath = pathWithinContext(target.path);
      if (applicationPath == null) {
        if (trace != null) trace.event("servlet.context-miss", "status=404");
        completeImmediate(NOT_FOUND, result, responseConsumer, streamingLifecycle);
        return;
      }
      ServletRoute route = resolve(applicationPath);
      boolean formLoginAction = securityRuntime.isFormLoginAction(applicationPath);
      WelcomeResolution welcome = resolveWelcome(applicationPath, route);
      if (welcome != null) {
        applicationPath = welcome.path;
        route = welcome.route;
      }
      if (route == null && !formLoginAction && errorPagePath(404, null) == null) {
        if (trace != null) trace.event("servlet.route-miss", "status=404");
        completeImmediate(NOT_FOUND, result, responseConsumer, streamingLifecycle);
        return;
      }

      List<Filter> filters =
          route == null
              ? Collections.<Filter>emptyList()
              : matchingFilters(applicationPath, route.definition.name, DispatcherType.REQUEST);
      if (trace != null)
        trace.event(
            "servlet.route",
            "target="
                + (route == null ? "none" : route.definition.servlet.getClass().getName())
                + " filters="
                + filters.size());
      HttpServletMapping mapping =
          route == null ? UNMAPPED : route.pattern.mapping(applicationPath, route.definition.name);
      // 非流式响应直接构建 HttpResponse；流式响应使用有限容量 mailbox 施加背压。
      ServletHttpResponse servletResponse =
          responseConsumer == null
              ? new ServletHttpResponse(
                  maxResponseBytes,
                  64 * 1024,
                  32 * 1024,
                  null,
                  responseCharacterEncoding,
                  localeEncodingMappings)
              : new ServletHttpResponse(
                  maxResponseBytes,
                  responseBodyBufferBytes,
                  responseBodyLowWaterBytes,
                  responseConsumer,
                  responseCharacterEncoding,
                  localeEncodingMappings);
      ServletHttpRequest servletRequest =
          new ServletHttpRequest(
              request,
              servletContext,
              servletResponse,
              sessionManager,
              attributeEvents,
              requestLimits,
              sessionTrackingModes,
              contextPath,
              welcome == null ? target.requestUri : contextPath + applicationPath,
              target.query,
              route == null ? applicationPath : route.pattern.servletPath(applicationPath),
              route == null ? null : route.pattern.pathInfo(applicationPath),
              mapping,
              target.sessionId,
              route == null ? null : route.definition.multipartConfig,
              requestCharacterEncoding,
              securityRuntime,
              securityRuntime.roleReferences(route == null ? "" : route.definition.name));
      servletResponse.attachRequest(servletRequest);
      ServletRequestEvent requestEvent = new ServletRequestEvent(servletContext, servletRequest);
      final int[] initializedRequestListeners = {0};
      final String servletName = route == null ? null : route.definition.name;
      final ServletAsyncContext[] contextHolder = new ServletAsyncContext[1];
      // AsyncContext 持有请求的后半段生命周期，包括再次分派、超时和完成通知。
      ServletAsyncContext asyncContext =
          new ServletAsyncContext(
              servletRequest,
              servletResponse,
              servletContext,
              scheduler,
              new ServletAsyncContext.Lifecycle() {
                @Override
                public void execute(Runnable task) {
                  asyncExecutor.execute(contextual(task));
                }

                @Override
                public void dispatch(ServletRequest request, ServletResponse response, String path)
                    throws Exception {
                  asyncDispatch(request, response, path);
                }

                @Override
                public void finish(Throwable failure, Runnable listenerCompletion) {
                  ClassLoader previous = enterApplicationClassLoader();
                  try {
                    finishRequest(
                        servletRequest,
                        servletResponse,
                        requestEvent,
                        initializedRequestListeners[0],
                        servletName,
                        failure,
                        listenerCompletion,
                        result,
                        streamingLifecycle,
                        contextHolder[0]);
                  } finally {
                    restoreContextClassLoader(previous);
                  }
                }
              });
      contextHolder[0] = asyncContext;
      synchronized (lifecycleMonitor) {
        activeRequests.add(asyncContext);
      }
      lifecycleTransferred = true;
      servletRequest.attachAsyncContext(asyncContext);
      servletResponse.attachAsyncContext(asyncContext);
      servletRequest.setAsyncSupported(
          route != null
              && asyncSupported(route.definition, applicationPath, DispatcherType.REQUEST));
      Throwable failure = null;
      // 安全拒绝由 securityRuntime 写响应；允许时才通知请求 Listener 并运行组件链。
      try {
        if (formLoginAction) {
          String formErrorPage = securityRuntime.processFormLogin(servletRequest, servletResponse);
          if (formErrorPage != null) {
            for (ServletRequestListener listener : requestListeners) {
              listener.requestInitialized(requestEvent);
              initializedRequestListeners[0]++;
            }
            dispatch(
                servletRequest,
                servletResponse,
                Target.parse(formErrorPage),
                null,
                DispatcherType.FORWARD);
          }
        } else if (route == null) {
          for (ServletRequestListener listener : requestListeners) {
            listener.requestInitialized(requestEvent);
            initializedRequestListeners[0]++;
          }
          servletResponse.sendError(404);
        } else if (!securityRuntime.authorize(
            servletRequest, servletResponse, servletRequest.getMethod(), applicationPath)) {
          if (trace != null)
            trace.event("servlet.security-rejected", "status=" + servletResponse.getStatus());
          // The security runtime has completed the challenge or denial response.
        } else {
          if (trace != null) trace.event("servlet.security-allowed");
          for (ServletRequestListener listener : requestListeners) {
            listener.requestInitialized(requestEvent);
            initializedRequestListeners[0]++;
          }
          initializeServlet(route.definition);
          new ServletFilterChain(filters, route.definition.servlet, trace)
              .doFilter(servletRequest, servletResponse);
        }
      } catch (Exception | LinkageError e) {
        failure = e;
      }
      asyncContext.exitDispatch(failure);
    } finally {
      if (!lifecycleTransferred) {
        releaseRequest(null);
      }
    }
  }

  /**
   * 所有完成路径的汇合点：错误页/默认错误 -> 响应完成 -> Listener/Session 清理 -> 完成 future -> 释放准入计数。流式响应只能在已发布的 body
   * 上报告后续失败。
   */
  private void finishRequest(
      ServletHttpRequest request,
      ServletHttpResponse response,
      ServletRequestEvent requestEvent,
      int initializedRequestListeners,
      String servletName,
      Throwable failure,
      Runnable listenerCompletion,
      CompletableFuture<HttpResponse> result,
      CompletableFuture<Void> streamingLifecycle,
      ServletAsyncContext asyncContext) {
    RequestTrace trace = request.trace();
    HttpResponse completedResponse = null;
    Throwable completedFailure = null;
    try {
      if (failure != null) {
        if (!(failure instanceof ServletRequestLimitException)) {
          Throwable cause = unwrapServletException(failure);
          StackTraceElement[] frames = cause.getStackTrace();
          LOGGER.severe(
              "Servlet request failed type="
                  + cause.getClass().getName()
                  + (frames.length == 0 ? "" : " at " + frames[0]));
        }
        if (failure instanceof ServletRequestLimitException && !response.isCommitted()) {
          ServletRequestLimitException rejected = (ServletRequestLimitException) failure;
          response.sendError(rejected.statusCode(), rejected.getMessage());
          handleError(request, response, null, servletName);
        } else if (!handleError(request, response, failure, servletName)) {
          rethrow(failure);
        }
      } else if (response.isErrorPending()) {
        handleError(request, response, null, servletName);
      }
      // 两种响应模式只在提交方式不同，清理顺序保持一致。
      if (streamingLifecycle == null) {
        completedResponse = response.build();
      } else {
        response.prepareStreamingCompletion();
      }
    } catch (Exception | LinkageError e) {
      completedFailure = e;
      if (streamingLifecycle != null) {
        response.failStreaming(e);
      }
    }
    if (trace != null)
      trace.event(
          "servlet.response-ready",
          "status="
              + response.getStatus()
              + " failure="
              + (completedFailure == null ? "none" : completedFailure.getClass().getName()));
    // Listener 清理必须先于对外完成信号，否则调用方可能观察到未清理的请求。
    try {
      listenerCompletion.run();
    } finally {
      try {
        destroyRequestListeners(requestEvent, initializedRequestListeners);
      } finally {
        request.finishSessionAccess();
      }
    }
    try {
      if (completedFailure == null && streamingLifecycle != null) {
        response.completeStreaming();
      }
      if (completedFailure == null && streamingLifecycle == null) {
        result.complete(completedResponse);
      } else if (completedFailure == null) {
        streamingLifecycle.complete(null);
      } else if (streamingLifecycle == null) {
        result.completeExceptionally(completedFailure);
      } else {
        streamingLifecycle.completeExceptionally(completedFailure);
      }
    } finally {
      releaseRequest(asyncContext);
    }
  }

  private void completeImmediate(
      HttpResponse response,
      CompletableFuture<HttpResponse> result,
      StreamingResponseConsumer responseConsumer,
      CompletableFuture<Void> streamingLifecycle)
      throws IOException {
    if (responseConsumer == null) {
      result.complete(response);
      return;
    }
    io.github.o1o00o10.lingtong.http.ResponseBodyMailbox body =
        new io.github.o1o00o10.lingtong.http.ResponseBodyMailbox(
            responseBodyBufferBytes, responseBodyLowWaterBytes, maxResponseBytes);
    byte[] bytes = response.body();
    body.writeBlocking(bytes, 0, bytes.length);
    body.complete(response.trailers());
    responseConsumer.accept(
        new io.github.o1o00o10.lingtong.http.StreamingHttpResponse(
            response.status(), response.reason(), response.headers(), body));
    streamingLifecycle.complete(null);
  }

  private boolean handleError(
      ServletHttpRequest request,
      ServletHttpResponse response,
      Throwable failure,
      String servletName)
      throws IOException, ServletException {
    // 头部已发送后不能再转发到错误页，否则会产生第二个响应。
    if (failure != null && response.isStreamingPublished()) {
      return false;
    }
    Throwable error = unwrapServletException(failure);
    int status =
        error == null ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    String errorPage = errorPagePath(status, error);
    if (errorPage == null) {
      if (error == null) {
        response.completeDefaultError();
        return true;
      }
      return false;
    }

    Target target = Target.parse(errorPage);
    if (resolve(target.path) == null) {
      throw new ServletException("error page has no Servlet mapping: " + target.path, error);
    }
    String message = error == null ? response.errorMessage() : error.getMessage();
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    request.setAttribute(
        RequestDispatcher.ERROR_EXCEPTION_TYPE, error == null ? null : error.getClass());
    request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, error);
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
    request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, servletName);
    response.beginErrorDispatch(status);
    dispatch(request, response, target, null, DispatcherType.ERROR);
    return true;
  }

  private String errorPagePath(int status, Throwable error) {
    for (Class<?> type = error == null ? null : error.getClass();
        type != null && Throwable.class.isAssignableFrom(type);
        type = type.getSuperclass()) {
      String path = exceptionErrorPages.get(type);
      if (path != null) {
        return path;
      }
    }
    String statusPath = statusErrorPages.get(status);
    return statusPath == null ? defaultErrorPage : statusPath;
  }

  private static Throwable unwrapServletException(Throwable failure) {
    if (failure instanceof ServletException
        && ((ServletException) failure).getRootCause() != null) {
      return ((ServletException) failure).getRootCause();
    }
    return failure;
  }

  private static void rethrow(Throwable failure) throws Exception {
    if (failure instanceof Exception) {
      throw (Exception) failure;
    }
    throw (LinkageError) failure;
  }

  @Override
  /** 停止准入并排空请求，然后按逆序销毁已初始化的应用组件。 */
  public void close() {
    ClassLoader previous = enterApplicationClassLoader();
    try {
      beginDrain();
      List<ServletAsyncContext> timedOutRequests;
      synchronized (lifecycleMonitor) {
        if (!started) {
          return;
        }
        awaitDrain();
        timedOutRequests = new ArrayList<ServletAsyncContext>(activeRequests);
      }
      ServletException shutdownFailure =
          new ServletException(
              "Servlet request did not complete before the application shutdown deadline");
      for (ServletAsyncContext request : timedOutRequests) {
        request.shutdown(shutdownFailure);
      }

      synchronized (lifecycleMonitor) {
        started = false;
        acceptingRequests = false;
        ScheduledExecutorService scheduler = asyncScheduler;
        asyncScheduler = null;
        if (scheduler != null) {
          scheduler.shutdownNow();
        }
        destroyInitializedServlets();
        resetServletInitializationFailures();
        destroyFilters(filterDefinitions);
        if (sessionManager != null) {
          sessionManager.close();
        }
        securityRuntime = null;
        destroyContextListeners(contextListeners.size());
      }
    } finally {
      restoreContextClassLoader(previous);
    }
  }

  /** Final disposal also closes the WAR class loader and temporary deployment resources. */
  /** 在关闭应用后释放 WAR 类加载器等托管部署资源。 */
  public void release() {
    close();
    closeManagedResource();
  }

  private Runnable contextual(Runnable task) {
    return new Runnable() {
      @Override
      public void run() {
        ClassLoader previous = enterApplicationClassLoader();
        try {
          task.run();
        } finally {
          restoreContextClassLoader(previous);
        }
      }
    };
  }

  private ClassLoader enterApplicationClassLoader() {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    if (previous != applicationClassLoader) {
      thread.setContextClassLoader(applicationClassLoader);
    }
    return previous;
  }

  private void restoreContextClassLoader(ClassLoader previous) {
    Thread thread = Thread.currentThread();
    if (thread.getContextClassLoader() != previous) {
      thread.setContextClassLoader(previous);
    }
  }

  private void closeManagedResource() {
    synchronized (lifecycleMonitor) {
      if (managedResourceClosed || managedResource == null) {
        return;
      }
      managedResourceClosed = true;
    }
    try {
      managedResource.close();
    } catch (Exception ignored) {
      // Component destruction has completed; managed resource cleanup is best effort.
    }
  }

  public void beginDrain() {
    synchronized (lifecycleMonitor) {
      if (!started || !acceptingRequests) {
        return;
      }
      acceptingRequests = false;
      drainDeadlineNanos =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownDrainTimeoutMillis);
    }
  }

  public long shutdownDrainTimeoutMillis() {
    return shutdownDrainTimeoutMillis;
  }

  public int activeSessionCount() {
    SessionManager current = sessionManager;
    return current == null ? 0 : current.localSize();
  }

  public int activeRequestCount() {
    synchronized (lifecycleMonitor) {
      return activeRequestCount;
    }
  }

  public boolean isAcceptingRequests() {
    synchronized (lifecycleMonitor) {
      return acceptingRequests;
    }
  }

  public long storedSessionCount() {
    SessionManager current = sessionManager;
    return current == null ? 0L : current.storedSize();
  }

  public long expiredSessionCount() {
    SessionManager current = sessionManager;
    return current == null ? 0L : current.expiredSessionCount();
  }

  public long sessionScavengerFailureCount() {
    SessionManager current = sessionManager;
    return current == null ? 0L : current.scavengerFailureCount();
  }

  private void awaitDrain() {
    while (activeRequestCount > 0) {
      long remainingNanos = drainDeadlineNanos - System.nanoTime();
      if (remainingNanos <= 0L) {
        return;
      }
      long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
      int waitNanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis));
      try {
        lifecycleMonitor.wait(waitMillis, waitNanos);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void releaseRequest(ServletAsyncContext asyncContext) {
    synchronized (lifecycleMonitor) {
      if (asyncContext != null) {
        activeRequests.remove(asyncContext);
      }
      activeRequestCount--;
      lifecycleMonitor.notifyAll();
    }
  }

  private void destroyContextListeners(int count) {
    ServletContextEvent event = new ServletContextEvent(servletContext);
    for (int i = count - 1; i >= 0; i--) {
      try {
        contextListeners.get(i).contextDestroyed(event);
      } catch (RuntimeException ignored) {
        // Continue destroying the remaining application listeners.
      }
    }
  }

  private void destroyRequestListeners(ServletRequestEvent event, int count) {
    for (int i = count - 1; i >= 0; i--) {
      try {
        requestListeners.get(i).requestDestroyed(event);
      } catch (RuntimeException ignored) {
        // A listener must not prevent the remaining request cleanup callbacks.
      }
    }
  }

  /** 映射优先级：精确 > 最长路径前缀 > 扩展名 > 默认 Servlet。 */
  private ServletRoute resolve(String path) {
    ServletRoute bestPath = null;
    ServletRoute extension = null;
    ServletRoute defaultRoute = null;
    for (ServletRoute route : servletRoutes) {
      if (!route.pattern.matches(path)) {
        continue;
      }
      if (route.pattern.isExact()) {
        return route;
      }
      if (route.pattern.isPath()
          && (bestPath == null || route.pattern.pathLength() > bestPath.pattern.pathLength())) {
        bestPath = route;
      } else if (route.pattern.isExtension() && extension == null) {
        extension = route;
      } else if (!route.pattern.isPath() && !route.pattern.isExtension()) {
        defaultRoute = route;
      }
    }
    if (bestPath != null) {
      return bestPath;
    }
    return extension != null ? extension : defaultRoute;
  }

  /** 依注册顺序匹配，并去重同一 Filter 的多种映射。 */
  private List<Filter> matchingFilters(
      String path, String servletName, DispatcherType dispatcherType) {
    List<Filter> filters = new ArrayList<Filter>();
    Set<FilterDefinition> matched = new LinkedHashSet<FilterDefinition>();
    for (FilterRoute route : filterRoutes) {
      if (filterMatches(route, path, servletName, dispatcherType)
          && matched.add(route.definition)) {
        filters.add(route.definition.filter);
      }
    }
    return filters;
  }

  /** Servlet 和当前分派命中的每个 Filter 都允许异步时，请求才可调用 startAsync。 */
  private boolean asyncSupported(
      ServletDefinition servlet, String path, DispatcherType dispatcherType) {
    if (!servlet.asyncSupported) {
      return false;
    }
    for (FilterRoute route : filterRoutes) {
      if (filterMatches(route, path, servlet.name, dispatcherType)
          && !route.definition.asyncSupported) {
        return false;
      }
    }
    return true;
  }

  private boolean filterMatches(
      FilterRoute route, String path, String servletName, DispatcherType dispatcherType) {
    return route.dispatcherTypes.contains(dispatcherType)
        && ((route.pattern != null && path != null && route.pattern.matches(path))
            || (route.servletName != null && route.servletName.equals(servletName)));
  }

  private void asyncDispatch(
      ServletRequest suppliedRequest, ServletResponse suppliedResponse, String path)
      throws Exception {
    ServletHttpRequest request = unwrapRequest(suppliedRequest);
    request.setAsyncDispatchAttributes();
    Target target = Target.parse(path);
    dispatch(suppliedRequest, suppliedResponse, target, null, DispatcherType.ASYNC);
  }

  /** FORWARD/INCLUDE/ERROR/ASYNC 共用的二次分派；临时覆盖请求映射信息，结束时恢复。 INCLUDE 使用受限响应包装器，防止被包含资源修改外层响应头或状态。 */
  private void dispatch(
      ServletRequest suppliedRequest,
      ServletResponse suppliedResponse,
      Target target,
      ServletDefinition namedServlet,
      DispatcherType dispatcherType)
      throws IOException, ServletException {
    ServletHttpRequest request = unwrapRequest(suppliedRequest);
    ServletHttpResponse response = unwrapResponse(suppliedResponse);
    ServletRoute route = target == null ? null : resolve(target.path);
    ServletDefinition definition =
        namedServlet == null ? (route == null ? null : route.definition) : namedServlet;
    if (definition == null) {
      throw new ServletException("dispatch target is no longer available");
    }

    String matchPath = target == null ? null : target.path;
    List<Filter> filters = matchingFilters(matchPath, definition.name, dispatcherType);
    RequestTrace trace = request.trace();
    if (trace != null)
      trace.event(
          "servlet.redispatch",
          "type="
              + dispatcherType
              + " target="
              + definition.servlet.getClass().getName()
              + " filters="
              + filters.size());
    HttpServletMapping mapping =
        route == null
            ? request.getHttpServletMapping()
            : route.pattern.mapping(target.path, definition.name);
    ServletHttpRequest.DispatchState state =
        request.beginDispatch(
            dispatcherType,
            target == null ? null : requestUri(target.path),
            target == null ? null : target.query,
            route == null ? null : route.pattern.servletPath(target.path),
            route == null ? null : route.pattern.pathInfo(target.path),
            mapping,
            securityRuntime.roleReferences(definition.name));
    boolean previousAsyncSupported =
        request.setAsyncSupported(asyncSupported(definition, matchPath, dispatcherType));
    try {
      ServletResponse dispatchedResponse =
          dispatcherType == DispatcherType.INCLUDE
              ? new IncludeResponseWrapper((HttpServletResponse) suppliedResponse)
              : suppliedResponse;
      initializeServlet(definition);
      new ServletFilterChain(filters, definition.servlet, trace)
          .doFilter(suppliedRequest, dispatchedResponse);
    } finally {
      request.setAsyncSupported(previousAsyncSupported);
      request.endDispatch(state);
    }
    if (dispatcherType == DispatcherType.FORWARD) {
      response.completeForward();
    } else if (dispatcherType == DispatcherType.ERROR) {
      if (response.isErrorPending()) {
        response.completeDefaultError();
      } else {
        response.completeErrorDispatch();
      }
    }
  }

  private String pathWithinContext(String requestPath) {
    if (contextPath.isEmpty()) {
      return requestPath;
    }
    if (requestPath.equals(contextPath)) {
      return "/";
    }
    if (!requestPath.startsWith(contextPath + "/")) {
      return null;
    }
    return requestPath.substring(contextPath.length());
  }

  private String requestUri(String applicationPath) {
    return contextPath.isEmpty() ? applicationPath : contextPath + applicationPath;
  }

  private static ServletHttpRequest unwrapRequest(ServletRequest request) throws ServletException {
    ServletRequest current = request;
    while (current instanceof ServletRequestWrapper) {
      current = ((ServletRequestWrapper) current).getRequest();
    }
    if (!(current instanceof ServletHttpRequest)) {
      throw new ServletException(
          "RequestDispatcher requires a request from this LingTong application");
    }
    return (ServletHttpRequest) current;
  }

  private static ServletHttpResponse unwrapResponse(ServletResponse response)
      throws ServletException {
    ServletResponse current = response;
    while (current instanceof ServletResponseWrapper) {
      current = ((ServletResponseWrapper) current).getResponse();
    }
    if (!(current instanceof ServletHttpResponse)) {
      throw new ServletException(
          "RequestDispatcher requires a response from this LingTong application");
    }
    return (ServletHttpResponse) current;
  }

  /** 懒加载 Servlet 的单次初始化；锁在定义上，避免并发首请求重复 init。 */
  private void initializeServlet(ServletDefinition definition) throws ServletException {
    synchronized (definition.lifecycleMonitor) {
      if (definition.initialized) return;
      if (definition.initializationFailure != null) {
        throw new ServletException(
            "Servlet initialization previously failed: " + definition.name,
            definition.initializationFailure);
      }
      try {
        definition.servlet.init(
            ComponentConfigs.servlet(definition.name, servletContext, definition.initParameters));
        definition.initialized = true;
        synchronized (initializedServlets) {
          initializedServlets.add(definition);
        }
      } catch (ServletException | RuntimeException | LinkageError failure) {
        definition.initializationFailure = failure;
        if (failure instanceof ServletException) throw (ServletException) failure;
        throw new ServletException("Servlet initialization failed: " + definition.name, failure);
      }
    }
  }

  private void destroyInitializedServlets() {
    List<ServletDefinition> definitions;
    synchronized (initializedServlets) {
      definitions = new ArrayList<ServletDefinition>(initializedServlets);
      initializedServlets.clear();
    }
    for (int i = definitions.size() - 1; i >= 0; i--) {
      ServletDefinition definition = definitions.get(i);
      synchronized (definition.lifecycleMonitor) {
        if (definition.initialized) {
          try {
            definition.servlet.destroy();
          } catch (RuntimeException ignored) {
            // Continue destroying the remaining application components.
          }
        }
        definition.initialized = false;
        definition.initializationFailure = null;
      }
    }
  }

  private void resetServletInitializationFailures() {
    for (ServletDefinition definition : servletDefinitions) {
      synchronized (definition.lifecycleMonitor) {
        if (!definition.initialized) definition.initializationFailure = null;
      }
    }
  }

  private void destroyFilters(List<FilterDefinition> definitions) {
    for (int i = definitions.size() - 1; i >= 0; i--) {
      try {
        definitions.get(i).filter.destroy();
      } catch (RuntimeException ignored) {
        // Continue destroying the remaining application components.
      }
    }
  }

  /** 封装构建器的状态与处理边界。 */
  public static final class Builder {
    /** 按顺序保存的Servlet定义集合。 */
    private final List<ServletDefinition> servletDefinitions = new ArrayList<ServletDefinition>();
    /** 按顺序保存的过滤器定义集合。 */
    private final List<FilterDefinition> filterDefinitions = new ArrayList<FilterDefinition>();
    /** 按顺序保存的Servlet路由集合。 */
    private final List<ServletRoute> servletRoutes = new ArrayList<ServletRoute>();
    /** 按顺序保存的过滤器路由集合。 */
    private final List<FilterRoute> filterRoutes = new ArrayList<FilterRoute>();
    /** 按顺序保存的上下文监听器集合。 */
    private final List<ServletContextListener> contextListeners =
        new ArrayList<ServletContextListener>();
    /** 按顺序保存的请求监听器集合。 */
    private final List<ServletRequestListener> requestListeners =
        new ArrayList<ServletRequestListener>();
    /** 按顺序保存的上下文属性监听器集合。 */
    private final List<ServletContextAttributeListener> contextAttributeListeners =
        new ArrayList<ServletContextAttributeListener>();
    /** 按顺序保存的请求属性监听器集合。 */
    private final List<ServletRequestAttributeListener> requestAttributeListeners =
        new ArrayList<ServletRequestAttributeListener>();
    /** 按顺序保存的会话监听器集合。 */
    private final List<HttpSessionListener> sessionListeners = new ArrayList<HttpSessionListener>();
    /** 按顺序保存的会话属性监听器集合。 */
    private final List<HttpSessionAttributeListener> sessionAttributeListeners =
        new ArrayList<HttpSessionAttributeListener>();
    /** 按顺序保存的会话标识符监听器集合。 */
    private final List<HttpSessionIdListener> sessionIdListeners =
        new ArrayList<HttpSessionIdListener>();
    /** 按顺序保存的initializers。 */
    private final List<InitializerRegistration> initializers =
        new ArrayList<InitializerRegistration>();
    /** Servlet名称集合。 */
    private final Set<String> servletNames = new HashSet<String>();
    /** Servlet模式集合。 */
    private final Set<String> servletPatterns = new HashSet<String>();
    /** 过滤器名称集合。 */
    private final Set<String> filterNames = new HashSet<String>();
    /** 最大会话集合。 */
    private int maxSessions = 10000;
    /** 会话超时时长秒，单位为秒。 */
    private int sessionTimeoutSeconds = 30 * 60;
    /** 会话Cookie相同站点。 */
    private String sessionCookieSameSite;
    /** 会话存储后端。 */
    private SessionStore sessionStore;
    /** 请求字符编码。 */
    private String requestCharacterEncoding;
    /** 响应字符编码。 */
    private String responseCharacterEncoding;
    /** 按键索引的状态码错误页面集合。 */
    private final Map<Integer, String> statusErrorPages = new LinkedHashMap<Integer, String>();
    /** 按键索引的异常错误页面集合。 */
    private final Map<Class<? extends Throwable>, String> exceptionErrorPages =
        new LinkedHashMap<Class<? extends Throwable>, String>();
    /** 默认错误页面。 */
    private String defaultErrorPage;
    /** 上下文路径。 */
    private String contextPath = "";
    /** display名称。 */
    private String displayName;
    /** 资源根目录。 */
    private Path resourceRoot;
    /** 默认Servlet启用标志，布尔标志。 */
    private boolean defaultServletEnabled = true;
    /** 按顺序保存的资源overlays。 */
    private final List<Path> resourceOverlays = new ArrayList<Path>();
    /** 按键索引的MIME映射集合。 */
    private final Map<String, String> mimeMappings = new LinkedHashMap<String, String>();
    /** 按键索引的地区编码映射集合。 */
    private final Map<String, String> localeEncodingMappings = new LinkedHashMap<String, String>();
    /** 安全策略。 */
    private ServletSecurityPolicy securityPolicy = ServletSecurityPolicy.builder().build();
    /** 安全身份源。 */
    private ServletSecurityRealm securityRealm;
    /** 客户端证书身份源。 */
    private ServletClientCertificateRealm clientCertificateRealm;
    /** 描述符安全模式集合。 */
    private Set<String> descriptorSecurityPatterns = Collections.emptySet();
    /** 按顺序保存的首页文件列表。 */
    private List<String> welcomeFiles = Collections.singletonList("index.html");
    /** 临时目录。 */
    private Path temporaryDirectory;
    /** 最大资源字节数，单位为字节。 */
    private long maxResourceBytes = 32L * 1024L * 1024L;
    /** 最大响应字节数，单位为字节。 */
    private long maxResponseBytes = 8L * 1024L * 1024L;
    /** 响应消息体缓冲区字节数，单位为字节。 */
    private int responseBodyBufferBytes = 64 * 1024;
    /** 响应消息体低水位字节数，单位为字节。 */
    private int responseBodyLowWaterBytes = 32 * 1024;
    /** 停机排空超时时长毫秒，单位为毫秒。 */
    private long shutdownDrainTimeoutMillis = 5_000L;
    /** 最大参数数量。 */
    private int maxParameterCount = 1_000;
    /** 最大参数字节数，单位为字节。 */
    private int maxParameterBytes = 1024 * 1024;
    /** 最大Cookie数量。 */
    private int maxCookieCount = 200;
    /** 最大Cookie字节数，单位为字节。 */
    private int maxCookieBytes = 8 * 1024;
    /** 应用类加载器。 */
    private ClassLoader applicationClassLoader;
    /** 托管的资源。 */
    private AutoCloseable managedResource;

    public Builder servlet(String name, String pattern, Servlet servlet) {
      return servlet(name, pattern, servlet, false);
    }

    public Builder servlet(String name, String pattern, Servlet servlet, boolean asyncSupported) {
      requireName(name, "Servlet");
      if (servlet == null) {
        throw new IllegalArgumentException("Servlet must not be null");
      }
      UrlPattern urlPattern = new UrlPattern(pattern);
      if (!servletNames.add(name)) {
        throw new IllegalArgumentException("duplicate Servlet name: " + name);
      }
      if (!servletPatterns.add(pattern)) {
        throw new IllegalArgumentException("duplicate Servlet URL pattern: " + pattern);
      }
      ServletDefinition definition = new ServletDefinition(name, servlet);
      definition.loadOnStartup = 0;
      definition.asyncSupported = asyncSupported;
      servletDefinitions.add(definition);
      servletRoutes.add(new ServletRoute(definition, urlPattern));
      return this;
    }

    public Builder filter(String name, String pattern, Filter filter) {
      return filter(name, pattern, filter, false);
    }

    public Builder filter(String name, String pattern, Filter filter, boolean asyncSupported) {
      requireName(name, "Filter");
      if (filter == null) {
        throw new IllegalArgumentException("Filter must not be null");
      }
      if (!filterNames.add(name)) {
        throw new IllegalArgumentException("duplicate Filter name: " + name);
      }
      FilterDefinition definition = new FilterDefinition(name, filter);
      definition.asyncSupported = asyncSupported;
      filterDefinitions.add(definition);
      filterRoutes.add(new FilterRoute(definition, new UrlPattern(pattern)));
      return this;
    }

    public Builder listener(EventListener listener) {
      if (listener == null) {
        throw new IllegalArgumentException("listener must not be null");
      }
      boolean supported = false;
      if (listener instanceof ServletContextListener) {
        contextListeners.add((ServletContextListener) listener);
        supported = true;
      }
      if (listener instanceof ServletRequestListener) {
        requestListeners.add((ServletRequestListener) listener);
        supported = true;
      }
      if (listener instanceof ServletContextAttributeListener) {
        contextAttributeListeners.add((ServletContextAttributeListener) listener);
        supported = true;
      }
      if (listener instanceof ServletRequestAttributeListener) {
        requestAttributeListeners.add((ServletRequestAttributeListener) listener);
        supported = true;
      }
      if (listener instanceof HttpSessionListener) {
        sessionListeners.add((HttpSessionListener) listener);
        supported = true;
      }
      if (listener instanceof HttpSessionAttributeListener) {
        sessionAttributeListeners.add((HttpSessionAttributeListener) listener);
        supported = true;
      }
      if (listener instanceof HttpSessionIdListener) {
        sessionIdListeners.add((HttpSessionIdListener) listener);
        supported = true;
      }
      if (!supported) {
        throw new IllegalArgumentException(
            "unsupported Servlet listener type: " + listener.getClass().getName());
      }
      return this;
    }

    public Builder initializer(ServletContainerInitializer initializer) {
      return initializer(initializer, Collections.<Class<?>>emptySet());
    }

    public Builder initializer(
        ServletContainerInitializer initializer, Set<Class<?>> handledTypes) {
      if (initializer == null || handledTypes == null) {
        throw new IllegalArgumentException("initializer and handledTypes must not be null");
      }
      initializers.add(new InitializerRegistration(initializer, handledTypes, true));
      return this;
    }

    public Builder initializerFirst(ServletContainerInitializer initializer) {
      return initializerFirst(initializer, Collections.<Class<?>>emptySet(), false);
    }

    public Builder initializerFirst(
        ServletContainerInitializer initializer,
        Set<Class<?>> handledTypes,
        boolean restrictAddedContextListeners) {
      if (initializer == null) {
        throw new IllegalArgumentException("initializer must not be null");
      }
      initializers.add(
          0, new InitializerRegistration(initializer, handledTypes, restrictAddedContextListeners));
      return this;
    }

    public Builder applicationClassLoader(ClassLoader value) {
      if (value == null) {
        throw new IllegalArgumentException("application class loader must not be null");
      }
      applicationClassLoader = value;
      return this;
    }

    public Builder managedResource(AutoCloseable value) {
      if (value == null) {
        throw new IllegalArgumentException("managed resource must not be null");
      }
      if (managedResource != null && managedResource != value) {
        throw new IllegalStateException("an application managed resource is already configured");
      }
      managedResource = value;
      return this;
    }

    public Builder sessions(int maximum, int timeoutSeconds) {
      if (maximum <= 0 || timeoutSeconds < 0) {
        throw new IllegalArgumentException("invalid HTTP session configuration");
      }
      maxSessions = maximum;
      sessionTimeoutSeconds = timeoutSeconds;
      return this;
    }

    public Builder sessionCookieSameSite(String value) {
      if (value != null
          && !"Lax".equals(value)
          && !"Strict".equals(value)
          && !"None".equals(value)) {
        throw new IllegalArgumentException("invalid SameSite policy: " + value);
      }
      sessionCookieSameSite = value;
      return this;
    }

    public Builder sessionStore(SessionStore value) {
      if (value == null) {
        throw new IllegalArgumentException("HTTP session store must not be null");
      }
      sessionStore = value;
      return this;
    }

    public Builder requestCharacterEncoding(String value) {
      requestCharacterEncoding = validateCharacterEncoding(value);
      return this;
    }

    public Builder responseCharacterEncoding(String value) {
      responseCharacterEncoding = validateCharacterEncoding(value);
      return this;
    }

    public Builder contextPath(String value) {
      contextPath = requireContextPath(value);
      return this;
    }

    public Builder displayName(String value) {
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException("display name must not be empty");
      }
      displayName = value.trim();
      return this;
    }

    public Builder resourceRoot(Path value) {
      if (value == null) throw new IllegalArgumentException("resource root must not be null");
      resourceRoot = value;
      return this;
    }

    public Builder defaultServletEnabled(boolean value) {
      defaultServletEnabled = value;
      return this;
    }

    public Builder resourceOverlay(Path value) {
      if (value == null) throw new IllegalArgumentException("resource overlay must not be null");
      resourceOverlays.add(value);
      return this;
    }

    public Builder mimeMappings(Map<String, String> values) {
      if (values == null) throw new IllegalArgumentException("MIME mappings must not be null");
      for (Map.Entry<String, String> mapping : values.entrySet()) {
        String extension = mapping.getKey();
        String mimeType = mapping.getValue();
        if (extension == null
            || extension.trim().isEmpty()
            || mimeType == null
            || mimeType.trim().isEmpty()) {
          throw new IllegalArgumentException("MIME mapping values must not be empty");
        }
        mimeMappings.put(extension.trim().toLowerCase(Locale.ROOT), mimeType.trim());
      }
      return this;
    }

    public Builder localeEncodingMappings(Map<String, String> values) {
      if (values == null) {
        throw new IllegalArgumentException("locale encoding mappings must not be null");
      }
      for (Map.Entry<String, String> mapping : values.entrySet()) {
        String locale = mapping.getKey();
        String encoding = mapping.getValue();
        if (locale == null
            || locale.trim().isEmpty()
            || encoding == null
            || encoding.trim().isEmpty()) {
          throw new IllegalArgumentException("locale encoding mapping values must not be empty");
        }
        localeEncodingMappings.put(
            normalizeLocale(locale), Charset.forName(encoding.trim()).name());
      }
      return this;
    }

    public Builder securityPolicy(ServletSecurityPolicy value) {
      if (value == null) {
        throw new IllegalArgumentException("security policy must not be null");
      }
      securityPolicy = value;
      return this;
    }

    public Builder securityRealm(ServletSecurityRealm value) {
      if (value == null) {
        throw new IllegalArgumentException("security realm must not be null");
      }
      securityRealm = value;
      return this;
    }

    public Builder clientCertificateRealm(ServletClientCertificateRealm value) {
      if (value == null) {
        throw new IllegalArgumentException("client certificate realm must not be null");
      }
      clientCertificateRealm = value;
      return this;
    }

    public Builder descriptorSecurityPatterns(Set<String> values) {
      if (values == null) {
        throw new IllegalArgumentException("descriptor security patterns must not be null");
      }
      LinkedHashSet<String> checked = new LinkedHashSet<String>();
      for (String value : values) {
        new UrlPattern(value);
        checked.add(value);
      }
      descriptorSecurityPatterns = Collections.unmodifiableSet(checked);
      return this;
    }

    public Builder welcomeFiles(List<String> values) {
      if (values == null || values.isEmpty()) {
        throw new IllegalArgumentException("welcome files must not be empty");
      }
      List<String> validated = new ArrayList<String>();
      for (String value : values) {
        if (value == null
            || value.trim().isEmpty()
            || value.trim().startsWith("/")
            || value.trim().endsWith("/")) {
          throw new IllegalArgumentException(
              "welcome file must be a partial URI without boundary slashes");
        }
        validated.add(value.trim());
      }
      welcomeFiles = Collections.unmodifiableList(validated);
      return this;
    }

    public Builder temporaryDirectory(Path value) {
      if (value == null || !Files.isDirectory(value) || !Files.isWritable(value)) {
        throw new IllegalArgumentException("temporary directory must be a writable directory");
      }
      temporaryDirectory = value.toAbsolutePath().normalize();
      return this;
    }

    public Builder maxResourceBytes(long value) {
      if (value <= 0 || value > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(
            "max resource bytes must be between 1 and Integer.MAX_VALUE");
      }
      maxResourceBytes = value;
      return this;
    }

    public Builder maxResponseBytes(long value) {
      if (value <= 0L || value > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(
            "max response bytes must be between 1 and Integer.MAX_VALUE");
      }
      maxResponseBytes = value;
      return this;
    }

    public Builder responseBodyBufferBytes(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("response body buffer must be positive");
      }
      responseBodyBufferBytes = value;
      return this;
    }

    public Builder responseBodyLowWaterBytes(int value) {
      if (value < 0) {
        throw new IllegalArgumentException("response body low-water mark must not be negative");
      }
      responseBodyLowWaterBytes = value;
      return this;
    }

    public Builder shutdownDrainTimeoutMillis(long value) {
      if (value < 0L) {
        throw new IllegalArgumentException("shutdown drain timeout must not be negative");
      }
      shutdownDrainTimeoutMillis = value;
      return this;
    }

    public Builder maxParameterCount(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("max parameter count must be positive");
      }
      maxParameterCount = value;
      return this;
    }

    public Builder maxParameterBytes(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("max parameter bytes must be positive");
      }
      maxParameterBytes = value;
      return this;
    }

    public Builder maxCookieCount(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("max cookie count must be positive");
      }
      maxCookieCount = value;
      return this;
    }

    public Builder maxCookieBytes(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("max cookie bytes must be positive");
      }
      maxCookieBytes = value;
      return this;
    }

    public Builder errorPage(int statusCode, String path) {
      if (statusCode < 400 || statusCode > 599) {
        throw new IllegalArgumentException("error page status must be between 400 and 599");
      }
      statusErrorPages.put(statusCode, requireErrorPagePath(path));
      return this;
    }

    public Builder errorPage(Class<? extends Throwable> exceptionType, String path) {
      if (exceptionType == null) {
        throw new IllegalArgumentException("error page exception type must not be null");
      }
      exceptionErrorPages.put(exceptionType, requireErrorPagePath(path));
      return this;
    }

    public Builder errorPage(String path) {
      defaultErrorPage = requireErrorPagePath(path);
      return this;
    }

    public ServletApplication build() {
      if (responseBodyLowWaterBytes >= responseBodyBufferBytes) {
        throw new IllegalStateException(
            "response body low-water mark must be below the buffer size");
      }
      return new ServletApplication(
          servletDefinitions,
          filterDefinitions,
          servletRoutes,
          filterRoutes,
          contextListeners,
          requestListeners,
          contextAttributeListeners,
          requestAttributeListeners,
          sessionListeners,
          sessionAttributeListeners,
          sessionIdListeners,
          initializers,
          maxSessions,
          sessionTimeoutSeconds,
          sessionCookieSameSite,
          sessionStore,
          requestCharacterEncoding,
          responseCharacterEncoding,
          statusErrorPages,
          exceptionErrorPages,
          defaultErrorPage,
          contextPath,
          displayName,
          resourceRoot == null
              ? null
              : new ServletResourceRoot(resourceRoot, resourceOverlays, mimeMappings),
          defaultServletEnabled,
          welcomeFiles,
          localeEncodingMappings,
          securityPolicy,
          securityRealm,
          clientCertificateRealm,
          descriptorSecurityPatterns,
          temporaryDirectory,
          maxResourceBytes,
          maxResponseBytes,
          responseBodyBufferBytes,
          responseBodyLowWaterBytes,
          shutdownDrainTimeoutMillis,
          new ServletRequestLimits(
              maxParameterCount, maxParameterBytes, maxCookieCount, maxCookieBytes),
          applicationClassLoader,
          managedResource);
    }

    private static void requireName(String name, String componentType) {
      if (name == null || name.trim().isEmpty()) {
        throw new IllegalArgumentException(componentType + " name must not be empty");
      }
    }

    private static String validateCharacterEncoding(String value) {
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException("character encoding must not be empty");
      }
      try {
        return Charset.forName(value.trim()).name();
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("unsupported character encoding: " + value, e);
      }
    }

    private static String requireErrorPagePath(String path) {
      if (path == null || path.isEmpty() || path.charAt(0) != '/') {
        throw new IllegalArgumentException("error page path must start with '/'");
      }
      return path;
    }

    private static String requireContextPath(String value) {
      if (value == null) {
        throw new IllegalArgumentException("context path must not be null");
      }
      if (value.isEmpty() || "/".equals(value)) {
        return "";
      }
      if (value.charAt(0) != '/'
          || value.endsWith("/")
          || value.indexOf('*') >= 0
          || value.indexOf('?') >= 0
          || value.indexOf('#') >= 0) {
        throw new IllegalArgumentException(
            "context path must start with '/', must not end with '/', and must not contain '*', '?' or '#'");
      }
      return value;
    }
  }

  private static String normalizeLocale(String value) {
    return value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
  }

  /** 封装Servlet定义的状态与处理边界。 */
  private static final class ServletDefinition {
    /** 生命周期监控器。 */
    private final Object lifecycleMonitor = new Object();
    /** 名称。 */
    private final String name;
    /** 此定义对应的应用 Servlet 实例。 */
    private final Servlet servlet;
    /** 按键索引的初始化参数集合。 */
    private final Map<String, String> initParameters = new LinkedHashMap<String, String>();
    /** 异步已支持的，布尔标志。 */
    private boolean asyncSupported;
    /** 加载on启动。 */
    private int loadOnStartup = -1;
    /** 多段请求配置。 */
    private MultipartConfigElement multipartConfig;
    /** 运行作为角色。 */
    private String runAsRole;
    /** Servlet安全。 */
    private ServletSecurityElement servletSecurity;
    /** 已初始化的，布尔标志。 */
    private boolean initialized;
    /** initialization失败。 */
    private Throwable initializationFailure;

    private ServletDefinition(String name, Servlet servlet) {
      this.name = name;
      this.servlet = servlet;
    }
  }

  /** 封装过滤器定义的状态与处理边界。 */
  private static final class FilterDefinition {
    /** 名称。 */
    private final String name;
    /** 过滤器。 */
    private final Filter filter;
    /** 按键索引的初始化参数集合。 */
    private final Map<String, String> initParameters = new LinkedHashMap<String, String>();
    /** 异步已支持的，布尔标志。 */
    private boolean asyncSupported;

    private FilterDefinition(String name, Filter filter) {
      this.name = name;
      this.filter = filter;
    }
  }

  /** 封装Servlet路由的状态与处理边界。 */
  private static final class ServletRoute {
    /** 定义。 */
    private final ServletDefinition definition;
    /** 模式。 */
    private final UrlPattern pattern;

    private ServletRoute(ServletDefinition definition, UrlPattern pattern) {
      this.definition = definition;
      this.pattern = pattern;
    }
  }

  /** 封装过滤器路由的状态与处理边界。 */
  private static final class FilterRoute {
    /** 定义。 */
    private final FilterDefinition definition;
    /** 模式。 */
    private final UrlPattern pattern;
    /** Servlet名称。 */
    private final String servletName;
    /** 分派器类型集合。 */
    private final Set<DispatcherType> dispatcherTypes;

    private FilterRoute(FilterDefinition definition, UrlPattern pattern) {
      this(definition, pattern, null, Collections.singleton(DispatcherType.REQUEST));
    }

    private FilterRoute(
        FilterDefinition definition,
        UrlPattern pattern,
        String servletName,
        Set<DispatcherType> dispatcherTypes) {
      this.definition = definition;
      this.pattern = pattern;
      this.servletName = servletName;
      this.dispatcherTypes =
          Collections.unmodifiableSet(new LinkedHashSet<DispatcherType>(dispatcherTypes));
    }
  }

  /** 封装动态注册信息控制器的状态与处理边界。 */
  private final class DynamicRegistrationController
      implements ServletContextFacade.RegistrationController {
    @Override
    public ServletRegistration.Dynamic addServlet(String name, Servlet servlet) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        requireComponent(name, servlet, "Servlet");
        if (!servletNames.add(name)) {
          return null;
        }
        ServletDefinition definition = new ServletDefinition(name, servlet);
        servletDefinitions.add(definition);
        return new DynamicServletRegistration(definition);
      }
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String name, Filter filter) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        requireComponent(name, filter, "Filter");
        if (!filterNames.add(name)) {
          return null;
        }
        FilterDefinition definition = new FilterDefinition(name, filter);
        filterDefinitions.add(definition);
        return new DynamicFilterRegistration(definition);
      }
    }

    @Override
    public ServletRegistration servletRegistration(String name) {
      synchronized (lifecycleMonitor) {
        ServletDefinition definition = findServlet(name);
        return definition == null ? null : new DynamicServletRegistration(definition);
      }
    }

    @Override
    public Map<String, ? extends ServletRegistration> servletRegistrations() {
      synchronized (lifecycleMonitor) {
        Map<String, ServletRegistration> result = new LinkedHashMap<String, ServletRegistration>();
        for (ServletDefinition definition : servletDefinitions) {
          result.put(definition.name, new DynamicServletRegistration(definition));
        }
        return Collections.unmodifiableMap(result);
      }
    }

    @Override
    public FilterRegistration filterRegistration(String name) {
      synchronized (lifecycleMonitor) {
        FilterDefinition definition = findFilter(name);
        return definition == null ? null : new DynamicFilterRegistration(definition);
      }
    }

    @Override
    public Map<String, ? extends FilterRegistration> filterRegistrations() {
      synchronized (lifecycleMonitor) {
        Map<String, FilterRegistration> result = new LinkedHashMap<String, FilterRegistration>();
        for (FilterDefinition definition : filterDefinitions) {
          result.put(definition.name, new DynamicFilterRegistration(definition));
        }
        return Collections.unmodifiableMap(result);
      }
    }

    @Override
    public String contextInitParameter(String name) {
      return contextInitParameters.get(name);
    }

    @Override
    public java.util.Enumeration<String> contextInitParameterNames() {
      return Collections.enumeration(contextInitParameters.keySet());
    }

    @Override
    public boolean setContextInitParameter(String name, String value) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        requireInitParameter(name, value);
        if (contextInitParameters.containsKey(name)) {
          return false;
        }
        contextInitParameters.put(name, value);
        return true;
      }
    }

    @Override
    public RequestDispatcher requestDispatcher(String path) {
      if (path == null || path.isEmpty() || path.charAt(0) != '/') {
        throw new IllegalArgumentException("ServletContext dispatcher path must start with '/'");
      }
      Target target = Target.parse(path);
      return resolve(target.path) == null ? null : new ApplicationRequestDispatcher(target, null);
    }

    @Override
    public RequestDispatcher namedDispatcher(String name) {
      if (name == null) {
        return null;
      }
      ServletDefinition definition = findServlet(name);
      return definition == null ? null : new ApplicationRequestDispatcher(null, definition);
    }

    @Override
    public SessionCookieConfig sessionCookieConfig() {
      return sessionCookieConfig;
    }

    @Override
    public void sessionTrackingModes(Set<SessionTrackingMode> modes) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (modes == null || modes.isEmpty()) {
          throw new IllegalArgumentException("session tracking modes must not be empty");
        }
        if (modes.contains(SessionTrackingMode.SSL)
            || (!modes.contains(SessionTrackingMode.COOKIE)
                && !modes.contains(SessionTrackingMode.URL))) {
          throw new IllegalArgumentException(
              "LingTong supports COOKIE and URL session tracking modes");
        }
        sessionTrackingModes =
            Collections.unmodifiableSet(new LinkedHashSet<SessionTrackingMode>(modes));
      }
    }

    @Override
    public Set<SessionTrackingMode> defaultSessionTrackingModes() {
      return defaultTrackingModes();
    }

    @Override
    public Set<SessionTrackingMode> effectiveSessionTrackingModes() {
      return sessionTrackingModes;
    }

    @Override
    public void sessionTimeout(int minutes) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (minutes < 0 || minutes > Integer.MAX_VALUE / 60) {
          throw new IllegalArgumentException(
              "session timeout minutes are outside the supported range");
        }
        sessionTimeoutSeconds = minutes * 60;
      }
    }

    @Override
    public int sessionTimeout() {
      synchronized (lifecycleMonitor) {
        return sessionTimeoutSeconds == 0 ? 0 : (sessionTimeoutSeconds + 59) / 60;
      }
    }

    @Override
    public void requestCharacterEncoding(String encoding) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        requestCharacterEncoding =
            encoding == null ? null : Builder.validateCharacterEncoding(encoding);
      }
    }

    @Override
    public String requestCharacterEncoding() {
      synchronized (lifecycleMonitor) {
        return requestCharacterEncoding;
      }
    }

    @Override
    public void responseCharacterEncoding(String encoding) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        responseCharacterEncoding =
            encoding == null ? null : Builder.validateCharacterEncoding(encoding);
      }
    }

    @Override
    public String responseCharacterEncoding() {
      synchronized (lifecycleMonitor) {
        return responseCharacterEncoding;
      }
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> type) throws ServletException {
      return instantiate(type, "Servlet");
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> type) throws ServletException {
      return instantiate(type, "Filter");
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> type) throws ServletException {
      return instantiate(type, "listener");
    }

    @Override
    public void addListener(EventListener listener) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        registerListener(listener);
        if (restrictAddedContextListeners && listener instanceof ServletContextListener) {
          restrictedContextListeners.add((ServletContextListener) listener);
        }
        refreshAttributeEvents();
      }
    }

    @Override
    public void declareRoles(String... roleNames) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (roleNames == null || roleNames.length == 0) {
          throw new IllegalArgumentException("role names must not be empty");
        }
        for (String roleName : roleNames) {
          Builder.requireName(roleName, "security role");
          declaredSecurityRoles.add(roleName);
        }
      }
    }

    private <T> T instantiate(Class<T> type, String componentType) throws ServletException {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (type == null) {
          throw new IllegalArgumentException(componentType + " class must not be null");
        }
        try {
          return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | SecurityException e) {
          throw new ServletException("cannot create " + componentType + " " + type.getName(), e);
        }
      }
    }
  }

  /** 封装动态Servlet注册信息的状态与处理边界。 */
  private final class DynamicServletRegistration implements ServletRegistration.Dynamic {
    /** 定义。 */
    private final ServletDefinition definition;

    private DynamicServletRegistration(ServletDefinition definition) {
      this.definition = definition;
    }

    @Override
    public Set<String> addMapping(String... urlPatterns) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (urlPatterns == null) {
          throw new IllegalArgumentException("Servlet URL patterns must not be null");
        }
        List<UrlPattern> validated = new ArrayList<UrlPattern>(urlPatterns.length);
        Set<String> conflicts = new LinkedHashSet<String>();
        for (String pattern : urlPatterns) {
          UrlPattern parsed = new UrlPattern(pattern);
          validated.add(parsed);
          ServletDefinition owner = servletPatternOwners.get(pattern);
          if (owner != null && owner != definition) {
            conflicts.add(pattern);
          }
        }
        if (!conflicts.isEmpty()) {
          return conflicts;
        }
        for (UrlPattern pattern : validated) {
          if (!definitionHasServletPattern(definition, pattern.value())) {
            servletRoutes.add(new ServletRoute(definition, pattern));
            servletPatternOwners.put(pattern.value(), definition);
          }
        }
        return Collections.emptySet();
      }
    }

    @Override
    public Collection<String> getMappings() {
      synchronized (lifecycleMonitor) {
        List<String> mappings = new ArrayList<String>();
        for (ServletRoute route : servletRoutes) {
          if (route.definition == definition) {
            mappings.add(route.pattern.value());
          }
        }
        return Collections.unmodifiableList(mappings);
      }
    }

    @Override
    public String getRunAsRole() {
      return definition.runAsRole;
    }

    @Override
    public void setLoadOnStartup(int loadOnStartup) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        definition.loadOnStartup = loadOnStartup;
      }
    }

    @Override
    public Set<String> setServletSecurity(ServletSecurityElement constraint) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (constraint == null) {
          throw new IllegalArgumentException("Servlet security constraint must not be null");
        }
        definition.servletSecurity = constraint;
        LinkedHashSet<String> conflicts = new LinkedHashSet<String>();
        for (String mapping : getMappings()) {
          if (descriptorSecurityPatterns.contains(mapping)) conflicts.add(mapping);
        }
        return Collections.unmodifiableSet(conflicts);
      }
    }

    @Override
    public void setMultipartConfig(MultipartConfigElement multipartConfig) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (multipartConfig == null) {
          throw new IllegalArgumentException("multipartConfig must not be null");
        }
        definition.multipartConfig = multipartConfig;
      }
    }

    @Override
    public void setRunAsRole(String roleName) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        definition.runAsRole = roleName;
      }
    }

    @Override
    public void setAsyncSupported(boolean asyncSupported) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        definition.asyncSupported = asyncSupported;
      }
    }

    @Override
    public String getName() {
      return definition.name;
    }

    @Override
    public String getClassName() {
      return definition.servlet.getClass().getName();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        requireInitParameter(name, value);
        if (definition.initParameters.containsKey(name)) {
          return false;
        }
        definition.initParameters.put(name, value);
        return true;
      }
    }

    @Override
    public String getInitParameter(String name) {
      return definition.initParameters.get(name);
    }

    @Override
    public Set<String> setInitParameters(Map<String, String> initParameters) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        return putInitParameters(definition.initParameters, initParameters);
      }
    }

    @Override
    public Map<String, String> getInitParameters() {
      return immutableParameters(definition.initParameters);
    }
  }

  /** 封装动态过滤器注册信息的状态与处理边界。 */
  private final class DynamicFilterRegistration implements FilterRegistration.Dynamic {
    /** 定义。 */
    private final FilterDefinition definition;

    private DynamicFilterRegistration(FilterDefinition definition) {
      this.definition = definition;
    }

    @Override
    public void addMappingForServletNames(
        EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... servletNames) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (servletNames == null) {
          throw new IllegalArgumentException("Servlet names must not be null");
        }
        List<FilterRoute> routes = new ArrayList<FilterRoute>(servletNames.length);
        Set<DispatcherType> dispatchers = dispatcherTypes(dispatcherTypes);
        for (String servletName : servletNames) {
          if (servletName == null || servletName.isEmpty()) {
            throw new IllegalArgumentException("Servlet name must not be empty");
          }
          routes.add(new FilterRoute(definition, null, servletName, dispatchers));
        }
        addFilterRoutes(routes, isMatchAfter);
      }
    }

    @Override
    public Collection<String> getServletNameMappings() {
      synchronized (lifecycleMonitor) {
        List<String> mappings = new ArrayList<String>();
        for (FilterRoute route : filterRoutes) {
          if (route.definition == definition && route.servletName != null) {
            mappings.add(route.servletName);
          }
        }
        return Collections.unmodifiableList(mappings);
      }
    }

    @Override
    public void addMappingForUrlPatterns(
        EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... urlPatterns) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        if (urlPatterns == null) {
          throw new IllegalArgumentException("Filter URL patterns must not be null");
        }
        List<FilterRoute> routes = new ArrayList<FilterRoute>(urlPatterns.length);
        Set<DispatcherType> dispatchers = dispatcherTypes(dispatcherTypes);
        for (String pattern : urlPatterns) {
          routes.add(new FilterRoute(definition, new UrlPattern(pattern), null, dispatchers));
        }
        addFilterRoutes(routes, isMatchAfter);
      }
    }

    @Override
    public Collection<String> getUrlPatternMappings() {
      synchronized (lifecycleMonitor) {
        List<String> mappings = new ArrayList<String>();
        for (FilterRoute route : filterRoutes) {
          if (route.definition == definition && route.pattern != null) {
            mappings.add(route.pattern.value());
          }
        }
        return Collections.unmodifiableList(mappings);
      }
    }

    @Override
    public void setAsyncSupported(boolean asyncSupported) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        definition.asyncSupported = asyncSupported;
      }
    }

    @Override
    public String getName() {
      return definition.name;
    }

    @Override
    public String getClassName() {
      return definition.filter.getClass().getName();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        requireInitParameter(name, value);
        if (definition.initParameters.containsKey(name)) {
          return false;
        }
        definition.initParameters.put(name, value);
        return true;
      }
    }

    @Override
    public String getInitParameter(String name) {
      return definition.initParameters.get(name);
    }

    @Override
    public Set<String> setInitParameters(Map<String, String> initParameters) {
      synchronized (lifecycleMonitor) {
        requireRegistrationOpen();
        return putInitParameters(definition.initParameters, initParameters);
      }
    }

    @Override
    public Map<String, String> getInitParameters() {
      return immutableParameters(definition.initParameters);
    }
  }

  private void addFilterRoutes(List<FilterRoute> routes, boolean matchAfter) {
    if (matchAfter) {
      filterRoutes.addAll(routes);
    } else {
      filterRoutes.addAll(0, routes);
    }
  }

  private void registerListener(EventListener listener) {
    if (listener == null) {
      throw new IllegalArgumentException("listener must not be null");
    }
    boolean supported = false;
    if (listener instanceof ServletContextListener) {
      contextListeners.add((ServletContextListener) listener);
      supported = true;
    }
    if (listener instanceof ServletRequestListener) {
      requestListeners.add((ServletRequestListener) listener);
      supported = true;
    }
    if (listener instanceof ServletContextAttributeListener) {
      contextAttributeListeners.add((ServletContextAttributeListener) listener);
      supported = true;
    }
    if (listener instanceof ServletRequestAttributeListener) {
      requestAttributeListeners.add((ServletRequestAttributeListener) listener);
      supported = true;
    }
    if (listener instanceof HttpSessionListener) {
      sessionListeners.add((HttpSessionListener) listener);
      supported = true;
    }
    if (listener instanceof HttpSessionAttributeListener) {
      sessionAttributeListeners.add((HttpSessionAttributeListener) listener);
      supported = true;
    }
    if (listener instanceof HttpSessionIdListener) {
      sessionIdListeners.add((HttpSessionIdListener) listener);
      supported = true;
    }
    if (!supported) {
      throw new IllegalArgumentException(
          "unsupported Servlet listener type: " + listener.getClass().getName());
    }
  }

  private void requireRegistrationOpen() {
    if (!registrationOpen) {
      throw new IllegalStateException(
          "Servlet registration is closed after application activation");
    }
  }

  private ServletDefinition findServlet(String name) {
    for (ServletDefinition definition : servletDefinitions) {
      if (definition.name.equals(name)) {
        return definition;
      }
    }
    return null;
  }

  private FilterDefinition findFilter(String name) {
    for (FilterDefinition definition : filterDefinitions) {
      if (definition.name.equals(name)) {
        return definition;
      }
    }
    return null;
  }

  private boolean definitionHasServletPattern(ServletDefinition definition, String pattern) {
    for (ServletRoute route : servletRoutes) {
      if (route.definition == definition && route.pattern.value().equals(pattern)) {
        return true;
      }
    }
    return false;
  }

  private static Set<DispatcherType> dispatcherTypes(EnumSet<DispatcherType> dispatcherTypes) {
    return dispatcherTypes == null
        ? Collections.singleton(DispatcherType.REQUEST)
        : new LinkedHashSet<DispatcherType>(dispatcherTypes);
  }

  private static void requireComponent(String name, Object component, String type) {
    Builder.requireName(name, type);
    if (component == null) {
      throw new IllegalArgumentException(type + " must not be null");
    }
  }

  private static void requireInitParameter(String name, String value) {
    if (name == null || value == null) {
      throw new IllegalArgumentException("init parameter name and value must not be null");
    }
  }

  private static Set<String> putInitParameters(
      Map<String, String> destination, Map<String, String> parameters) {
    if (parameters == null) {
      throw new IllegalArgumentException("init parameters must not be null");
    }
    Set<String> conflicts = new LinkedHashSet<String>();
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      requireInitParameter(entry.getKey(), entry.getValue());
      if (destination.containsKey(entry.getKey())) {
        conflicts.add(entry.getKey());
      }
    }
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      if (!conflicts.contains(entry.getKey())) {
        destination.put(entry.getKey(), entry.getValue());
      }
    }
    return conflicts;
  }

  private static Map<String, String> immutableParameters(Map<String, String> parameters) {
    return Collections.unmodifiableMap(new LinkedHashMap<String, String>(parameters));
  }

  /** 封装初始化器注册信息的状态与处理边界。 */
  private static final class InitializerRegistration {
    /** 初始化器。 */
    private final ServletContainerInitializer initializer;
    /** handled类型集合。 */
    private final Set<Class<?>> handledTypes;
    /** restrict已添加的上下文监听器集合，布尔标志。 */
    private final boolean restrictAddedContextListeners;

    private InitializerRegistration(
        ServletContainerInitializer initializer,
        Set<Class<?>> handledTypes,
        boolean restrictAddedContextListeners) {
      this.initializer = initializer;
      this.handledTypes =
          handledTypes == null
              ? null
              : Collections.unmodifiableSet(new LinkedHashSet<Class<?>>(handledTypes));
      this.restrictAddedContextListeners = restrictAddedContextListeners;
    }
  }

  /** 封装注册信息checkpoint的状态与处理边界。 */
  private final class RegistrationCheckpoint {
    /** Servlet定义数量。 */
    private final int servletDefinitionCount;
    /** 过滤器定义数量。 */
    private final int filterDefinitionCount;
    /** Servlet路由数量。 */
    private final int servletRouteCount;
    /** 过滤器路由数量。 */
    private final int filterRouteCount;
    /** 上下文监听器数量。 */
    private final int contextListenerCount;
    /** 请求监听器数量。 */
    private final int requestListenerCount;
    /** 上下文属性监听器数量。 */
    private final int contextAttributeListenerCount;
    /** 请求属性监听器数量。 */
    private final int requestAttributeListenerCount;
    /** 会话监听器数量。 */
    private final int sessionListenerCount;
    /** 会话属性监听器数量。 */
    private final int sessionAttributeListenerCount;
    /** 会话标识符监听器数量。 */
    private final int sessionIdListenerCount;
    /** 按键索引的Servlet状态集合。 */
    private final Map<ServletDefinition, ServletDefinitionState> servletStates =
        new IdentityHashMap<ServletDefinition, ServletDefinitionState>();
    /** 按键索引的过滤器状态集合。 */
    private final Map<FilterDefinition, FilterDefinitionState> filterStates =
        new IdentityHashMap<FilterDefinition, FilterDefinitionState>();
    /** 按键索引的已保存的上下文初始化参数集合。 */
    private final Map<String, String> savedContextInitParameters;
    /** 已保存的会话跟踪模式集合。 */
    private final Set<SessionTrackingMode> savedSessionTrackingModes;
    /** 已保存的会话Cookie配置。 */
    private final MutableSessionCookieConfig.State savedSessionCookieConfig;
    /** 已保存的会话超时时长秒，单位为秒。 */
    private final int savedSessionTimeoutSeconds;
    /** 已保存的请求字符编码。 */
    private final String savedRequestCharacterEncoding;
    /** 已保存的响应字符编码。 */
    private final String savedResponseCharacterEncoding;
    /** 已保存的受限的上下文监听器集合。 */
    private final Set<ServletContextListener> savedRestrictedContextListeners;
    /** 已保存的已声明的安全角色集合。 */
    private final Set<String> savedDeclaredSecurityRoles;
    /** 已保存的默认资源定义。 */
    private final ServletDefinition savedDefaultResourceDefinition;

    private RegistrationCheckpoint() {
      servletDefinitionCount = servletDefinitions.size();
      filterDefinitionCount = filterDefinitions.size();
      servletRouteCount = servletRoutes.size();
      filterRouteCount = filterRoutes.size();
      contextListenerCount = contextListeners.size();
      requestListenerCount = requestListeners.size();
      contextAttributeListenerCount = contextAttributeListeners.size();
      requestAttributeListenerCount = requestAttributeListeners.size();
      sessionListenerCount = sessionListeners.size();
      sessionAttributeListenerCount = sessionAttributeListeners.size();
      sessionIdListenerCount = sessionIdListeners.size();
      savedContextInitParameters = new LinkedHashMap<String, String>(contextInitParameters);
      savedSessionTrackingModes = new LinkedHashSet<SessionTrackingMode>(sessionTrackingModes);
      savedSessionCookieConfig = sessionCookieConfig.snapshot();
      savedSessionTimeoutSeconds = sessionTimeoutSeconds;
      savedRequestCharacterEncoding = requestCharacterEncoding;
      savedResponseCharacterEncoding = responseCharacterEncoding;
      savedRestrictedContextListeners =
          Collections.newSetFromMap(new IdentityHashMap<ServletContextListener, Boolean>());
      savedRestrictedContextListeners.addAll(restrictedContextListeners);
      savedDeclaredSecurityRoles = new LinkedHashSet<String>(declaredSecurityRoles);
      savedDefaultResourceDefinition = defaultResourceDefinition;
      for (ServletDefinition definition : servletDefinitions) {
        servletStates.put(definition, new ServletDefinitionState(definition));
      }
      for (FilterDefinition definition : filterDefinitions) {
        filterStates.put(definition, new FilterDefinitionState(definition));
      }
    }

    private void rollback() {
      truncate(servletDefinitions, servletDefinitionCount);
      truncate(filterDefinitions, filterDefinitionCount);
      truncate(servletRoutes, servletRouteCount);
      truncate(filterRoutes, filterRouteCount);
      truncate(contextListeners, contextListenerCount);
      truncate(requestListeners, requestListenerCount);
      truncate(contextAttributeListeners, contextAttributeListenerCount);
      truncate(requestAttributeListeners, requestAttributeListenerCount);
      truncate(sessionListeners, sessionListenerCount);
      truncate(sessionAttributeListeners, sessionAttributeListenerCount);
      truncate(sessionIdListeners, sessionIdListenerCount);
      contextInitParameters.clear();
      contextInitParameters.putAll(savedContextInitParameters);
      sessionTrackingModes =
          Collections.unmodifiableSet(
              new LinkedHashSet<SessionTrackingMode>(savedSessionTrackingModes));
      sessionCookieConfig.restore(savedSessionCookieConfig);
      sessionTimeoutSeconds = savedSessionTimeoutSeconds;
      requestCharacterEncoding = savedRequestCharacterEncoding;
      responseCharacterEncoding = savedResponseCharacterEncoding;
      restrictedContextListeners.clear();
      restrictedContextListeners.addAll(savedRestrictedContextListeners);
      declaredSecurityRoles.clear();
      declaredSecurityRoles.addAll(savedDeclaredSecurityRoles);
      defaultResourceDefinition = savedDefaultResourceDefinition;
      for (Map.Entry<ServletDefinition, ServletDefinitionState> entry : servletStates.entrySet()) {
        entry.getValue().restore(entry.getKey());
      }
      for (Map.Entry<FilterDefinition, FilterDefinitionState> entry : filterStates.entrySet()) {
        entry.getValue().restore(entry.getKey());
      }
      servletNames.clear();
      servletPatternOwners.clear();
      filterNames.clear();
      for (ServletDefinition definition : servletDefinitions) {
        servletNames.add(definition.name);
      }
      for (ServletRoute route : servletRoutes) {
        servletPatternOwners.put(route.pattern.value(), route.definition);
      }
      for (FilterDefinition definition : filterDefinitions) {
        filterNames.add(definition.name);
      }
      refreshAttributeEvents();
    }

    private <T> void truncate(List<T> values, int size) {
      if (values.size() > size) {
        values.subList(size, values.size()).clear();
      }
    }
  }

  /** 封装Servlet定义状态的状态与处理边界。 */
  private static final class ServletDefinitionState {
    /** 按键索引的初始化参数集合。 */
    private final Map<String, String> initParameters;
    /** 异步已支持的，布尔标志。 */
    private final boolean asyncSupported;
    /** 加载on启动。 */
    private final int loadOnStartup;
    /** 多段请求配置。 */
    private final MultipartConfigElement multipartConfig;
    /** 运行作为角色。 */
    private final String runAsRole;
    /** Servlet安全。 */
    private final ServletSecurityElement servletSecurity;

    private ServletDefinitionState(ServletDefinition definition) {
      initParameters = new LinkedHashMap<String, String>(definition.initParameters);
      asyncSupported = definition.asyncSupported;
      loadOnStartup = definition.loadOnStartup;
      multipartConfig = definition.multipartConfig;
      runAsRole = definition.runAsRole;
      servletSecurity = definition.servletSecurity;
    }

    private void restore(ServletDefinition definition) {
      definition.initParameters.clear();
      definition.initParameters.putAll(initParameters);
      definition.asyncSupported = asyncSupported;
      definition.loadOnStartup = loadOnStartup;
      definition.multipartConfig = multipartConfig;
      definition.runAsRole = runAsRole;
      definition.servletSecurity = servletSecurity;
    }
  }

  /** 封装过滤器定义状态的状态与处理边界。 */
  private static final class FilterDefinitionState {
    /** 按键索引的初始化参数集合。 */
    private final Map<String, String> initParameters;
    /** 异步已支持的，布尔标志。 */
    private final boolean asyncSupported;

    private FilterDefinitionState(FilterDefinition definition) {
      initParameters = new LinkedHashMap<String, String>(definition.initParameters);
      asyncSupported = definition.asyncSupported;
    }

    private void restore(FilterDefinition definition) {
      definition.initParameters.clear();
      definition.initParameters.putAll(initParameters);
      definition.asyncSupported = asyncSupported;
    }
  }

  /** 封装应用请求分派器的状态与处理边界。 */
  private final class ApplicationRequestDispatcher implements RequestDispatcher {
    /** 目标。 */
    private final Target target;
    /** namedServlet。 */
    /** 具名的Servlet（namedServlet）。 */
    private final ServletDefinition namedServlet;

    private ApplicationRequestDispatcher(Target target, ServletDefinition namedServlet) {
      this.target = target;
      this.namedServlet = namedServlet;
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response)
        throws ServletException, IOException {
      if (response.isCommitted()) {
        throw new IllegalStateException("cannot forward after the response is committed");
      }
      response.resetBuffer();
      dispatch(request, response, target, namedServlet, DispatcherType.FORWARD);
    }

    @Override
    public void include(ServletRequest request, ServletResponse response)
        throws ServletException, IOException {
      if (!(response instanceof HttpServletResponse)) {
        throw new ServletException("HTTP RequestDispatcher requires an HttpServletResponse");
      }
      dispatch(request, response, target, namedServlet, DispatcherType.INCLUDE);
    }
  }

  /** 封装目标的状态与处理边界。 */
  private static final class Target {
    /** 请求URI。 */
    private final String requestUri;
    /** 路径。 */
    private final String path;
    /** 查询串。 */
    private final String query;
    /** 会话标识符。 */
    private final String sessionId;

    private Target(String requestUri, String path, String query, String sessionId) {
      this.requestUri = requestUri;
      this.path = path;
      this.query = query;
      this.sessionId = sessionId;
    }

    private static Target parse(String requestTarget) {
      int queryStart = requestTarget.indexOf('?');
      String path = queryStart < 0 ? requestTarget : requestTarget.substring(0, queryStart);
      String query = queryStart < 0 ? null : requestTarget.substring(queryStart + 1);
      if (path.isEmpty() || path.charAt(0) != '/') {
        throw new IllegalArgumentException("only origin-form HTTP request targets are supported");
      }
      StringBuilder mappingPath = new StringBuilder(path.length());
      String sessionId = null;
      for (int position = 0; position < path.length(); ) {
        char current = path.charAt(position);
        if (current != ';') {
          mappingPath.append(current);
          position++;
          continue;
        }
        int end = position + 1;
        while (end < path.length() && path.charAt(end) != ';' && path.charAt(end) != '/') {
          end++;
        }
        String parameter = path.substring(position + 1, end);
        int equals = parameter.indexOf('=');
        String name = equals < 0 ? parameter : parameter.substring(0, equals);
        if (sessionId == null && "jsessionid".equalsIgnoreCase(name)) {
          sessionId = equals < 0 ? "" : parameter.substring(equals + 1);
        }
        position = end;
      }
      return new Target(path, mappingPath.toString(), query, sessionId);
    }
  }

  /** 封装首页resolution的状态与处理边界。 */
  private static final class WelcomeResolution {
    /** 路径。 */
    private final String path;
    /** 路由。 */
    private final ServletRoute route;

    private WelcomeResolution(String path, ServletRoute route) {
      this.path = path;
      this.route = route;
    }
  }

  private static Set<SessionTrackingMode> defaultTrackingModes() {
    LinkedHashSet<SessionTrackingMode> modes = new LinkedHashSet<SessionTrackingMode>();
    modes.add(SessionTrackingMode.COOKIE);
    modes.add(SessionTrackingMode.URL);
    return Collections.unmodifiableSet(modes);
  }
}
