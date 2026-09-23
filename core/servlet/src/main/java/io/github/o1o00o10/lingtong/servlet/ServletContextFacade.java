/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;

/** 向应用暴露 ServletContext，代理动态注册、资源访问和上下文属性事件。 */
final class ServletContextFacade implements InvocationHandler {
  /** 日志记录器。 */
  private static final Logger LOGGER = Logger.getLogger("io.github.o1o00o10.lingtong.servlet");

  /** 按键索引的属性集合。 */
  private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();
  /** 注册信息集合。 */
  private final RegistrationController registrations;
  /** 应用类加载器。 */
  private final ClassLoader applicationClassLoader;
  /** 上下文路径。 */
  private final String contextPath;
  /** display名称。 */
  private final String displayName;
  /** 资源集合。 */
  private final ServletResourceRoot resources;
  /** 属性事件集合。 */
  private volatile ServletAttributeEventDispatcher attributeEvents;
  /** 代理。 */
  private final ServletContext proxy;
  /** 受限的代理。 */
  private final ServletContext restrictedProxy;
  /** 受限的监听器方法集合。 */
  private static final Set<String> RESTRICTED_LISTENER_METHODS =
      Collections.unmodifiableSet(
          new HashSet<String>(
              Arrays.asList(
                  "getEffectiveMajorVersion",
                  "getEffectiveMinorVersion",
                  "setInitParameter",
                  "addServlet",
                  "addJspFile",
                  "createServlet",
                  "getServletRegistration",
                  "getServletRegistrations",
                  "addFilter",
                  "createFilter",
                  "getFilterRegistration",
                  "getFilterRegistrations",
                  "getSessionCookieConfig",
                  "setSessionTrackingModes",
                  "getDefaultSessionTrackingModes",
                  "getEffectiveSessionTrackingModes",
                  "addListener",
                  "createListener",
                  "getJspConfigDescriptor",
                  "getClassLoader",
                  "declareRoles",
                  "getVirtualServerName",
                  "getSessionTimeout",
                  "setSessionTimeout",
                  "getRequestCharacterEncoding",
                  "setRequestCharacterEncoding",
                  "getResponseCharacterEncoding",
                  "setResponseCharacterEncoding")));

  private ServletContextFacade(
      ServletAttributeEventDispatcher attributeEvents,
      RegistrationController registrations,
      String contextPath,
      String displayName,
      ServletResourceRoot resources,
      ClassLoader applicationClassLoader,
      Path temporaryDirectory) {
    this.attributeEvents = attributeEvents;
    this.registrations = registrations;
    this.contextPath = contextPath;
    this.displayName = displayName;
    this.resources = resources;
    ClassLoader contextClassLoader =
        applicationClassLoader == null
            ? Thread.currentThread().getContextClassLoader()
            : applicationClassLoader;
    this.applicationClassLoader =
        contextClassLoader == null
            ? ServletContextFacade.class.getClassLoader()
            : contextClassLoader;
    File tempDir =
        temporaryDirectory == null
            ? new File(System.getProperty("java.io.tmpdir"))
            : temporaryDirectory.toFile();
    attributes.put(ServletContext.TEMPDIR, tempDir);
    proxy =
        (ServletContext)
            Proxy.newProxyInstance(
                ServletContext.class.getClassLoader(), new Class<?>[] {ServletContext.class}, this);
    restrictedProxy =
        (ServletContext)
            Proxy.newProxyInstance(
                ServletContext.class.getClassLoader(),
                new Class<?>[] {ServletContext.class},
                new InvocationHandler() {
                  @Override
                  public Object invoke(Object restricted, Method method, Object[] args)
                      throws Throwable {
                    if (RESTRICTED_LISTENER_METHODS.contains(method.getName())) {
                      throw new UnsupportedOperationException(
                          "ServletContext."
                              + method.getName()
                              + " is not available to a programmatically added listener");
                    }
                    return ServletContextFacade.this.invoke(restricted, method, args);
                  }
                });
  }

  static ServletContext create() {
    return create(ServletAttributeEventDispatcher.empty());
  }

  static ServletContext create(ServletAttributeEventDispatcher attributeEvents) {
    return new ServletContextFacade(attributeEvents, null, "", null, null, null, null).proxy;
  }

  static ServletContextFacade createFacade(RegistrationController registrations) {
    return createFacade(registrations, "");
  }

  static ServletContextFacade createFacade(
      RegistrationController registrations, String contextPath) {
    return createFacade(registrations, contextPath, null);
  }

  static ServletContextFacade createFacade(
      RegistrationController registrations, String contextPath, ServletResourceRoot resources) {
    return createFacade(registrations, contextPath, resources, null);
  }

  static ServletContextFacade createFacade(
      RegistrationController registrations,
      String contextPath,
      ServletResourceRoot resources,
      ClassLoader applicationClassLoader) {
    return new ServletContextFacade(
        ServletAttributeEventDispatcher.empty(),
        registrations,
        contextPath,
        null,
        resources,
        applicationClassLoader,
        null);
  }

  static ServletContextFacade createFacade(
      RegistrationController registrations,
      String contextPath,
      String displayName,
      ServletResourceRoot resources,
      ClassLoader applicationClassLoader,
      Path temporaryDirectory) {
    return new ServletContextFacade(
        ServletAttributeEventDispatcher.empty(),
        registrations,
        contextPath,
        displayName,
        resources,
        applicationClassLoader,
        temporaryDirectory);
  }

  ServletContext context() {
    return proxy;
  }

  ServletContext restrictedContext() {
    return restrictedProxy;
  }

  void attributeEvents(ServletAttributeEventDispatcher events) {
    attributeEvents = events;
  }

  @Override
  public Object invoke(Object ignoredProxy, Method method, Object[] args) throws Throwable {
    String name = method.getName();
    if (method.getDeclaringClass() == Object.class) {
      return objectMethod(name, ignoredProxy, args);
    }
    if ("getContextPath".equals(name)) {
      return contextPath;
    }
    if ("getContext".equals(name)) {
      return null;
    }
    if ("getMajorVersion".equals(name) || "getEffectiveMajorVersion".equals(name)) {
      return 4;
    }
    if ("getMinorVersion".equals(name) || "getEffectiveMinorVersion".equals(name)) {
      return 0;
    }
    if ("getServerInfo".equals(name)) {
      return "LingTong/0.1";
    }
    if ("getServletContextName".equals(name)) {
      return displayName == null
          ? contextPath.isEmpty() ? "LingTong Root Context" : "LingTong Context " + contextPath
          : displayName;
    }
    if ("getVirtualServerName".equals(name)) {
      return "default";
    }
    if ("getServlet".equals(name)) {
      return null;
    }
    if ("getServlets".equals(name) || "getServletNames".equals(name)) {
      return Collections.enumeration(Collections.emptyList());
    }
    if ("getJspConfigDescriptor".equals(name)) {
      return null;
    }
    if ("getClassLoader".equals(name)) {
      return applicationClassLoader;
    }
    if ("getResourcePaths".equals(name)) {
      return resources == null ? null : resources.resourcePaths((String) args[0]);
    }
    if ("getResource".equals(name)) {
      return resources == null ? null : resources.resource((String) args[0]);
    }
    if ("getResourceAsStream".equals(name)) {
      return resources == null ? null : resources.open((String) args[0]);
    }
    if ("getRealPath".equals(name)) {
      return resources == null ? null : resources.realPath((String) args[0]);
    }
    if ("getMimeType".equals(name)) {
      return resources == null ? null : resources.mimeType((String) args[0]);
    }
    if ("getAttribute".equals(name)) {
      return attributes.get(args[0]);
    }
    if ("getAttributeNames".equals(name)) {
      return Collections.enumeration(attributes.keySet());
    }
    if ("setAttribute".equals(name)) {
      String attributeName = (String) args[0];
      Object value = args[1];
      if (value == null) {
        removeAttribute(attributeName);
      } else {
        Object previous = attributes.put(attributeName, value);
        if (previous == null) {
          attributeEvents.contextAdded(proxy, attributeName, value);
        } else {
          attributeEvents.contextReplaced(proxy, attributeName, previous);
        }
      }
      return null;
    }
    if ("removeAttribute".equals(name)) {
      removeAttribute((String) args[0]);
      return null;
    }
    if ("getInitParameter".equals(name)) {
      return registrations == null ? null : registrations.contextInitParameter((String) args[0]);
    }
    if ("getInitParameterNames".equals(name)) {
      return registrations == null
          ? Collections.enumeration(Collections.<String>emptyList())
          : registrations.contextInitParameterNames();
    }
    if ("setInitParameter".equals(name)) {
      requireRegistrations();
      return registrations.setContextInitParameter((String) args[0], (String) args[1]);
    }
    if ("getRequestDispatcher".equals(name)) {
      requireRegistrations();
      return registrations.requestDispatcher((String) args[0]);
    }
    if ("getNamedDispatcher".equals(name)) {
      requireRegistrations();
      return registrations.namedDispatcher((String) args[0]);
    }
    if ("addServlet".equals(name)) {
      requireRegistrations();
      return addServlet(args);
    }
    if ("createServlet".equals(name)) {
      requireRegistrations();
      return registrations.createServlet(castClass(args[0]));
    }
    if ("getServletRegistration".equals(name)) {
      requireRegistrations();
      return registrations.servletRegistration((String) args[0]);
    }
    if ("getServletRegistrations".equals(name)) {
      requireRegistrations();
      return registrations.servletRegistrations();
    }
    if ("addFilter".equals(name)) {
      requireRegistrations();
      return addFilter(args);
    }
    if ("createFilter".equals(name)) {
      requireRegistrations();
      return registrations.createFilter(castClass(args[0]));
    }
    if ("getFilterRegistration".equals(name)) {
      requireRegistrations();
      return registrations.filterRegistration((String) args[0]);
    }
    if ("getFilterRegistrations".equals(name)) {
      requireRegistrations();
      return registrations.filterRegistrations();
    }
    if ("getSessionCookieConfig".equals(name)) {
      requireRegistrations();
      return registrations.sessionCookieConfig();
    }
    if ("setSessionTrackingModes".equals(name)) {
      requireRegistrations();
      registrations.sessionTrackingModes(castSet(args[0]));
      return null;
    }
    if ("getDefaultSessionTrackingModes".equals(name)) {
      requireRegistrations();
      return registrations.defaultSessionTrackingModes();
    }
    if ("getEffectiveSessionTrackingModes".equals(name)) {
      requireRegistrations();
      return registrations.effectiveSessionTrackingModes();
    }
    if ("setSessionTimeout".equals(name)) {
      requireRegistrations();
      registrations.sessionTimeout((Integer) args[0]);
      return null;
    }
    if ("getSessionTimeout".equals(name)) {
      requireRegistrations();
      return registrations.sessionTimeout();
    }
    if ("getRequestCharacterEncoding".equals(name)) {
      return registrations == null ? null : registrations.requestCharacterEncoding();
    }
    if ("setRequestCharacterEncoding".equals(name)) {
      requireRegistrations();
      registrations.requestCharacterEncoding((String) args[0]);
      return null;
    }
    if ("getResponseCharacterEncoding".equals(name)) {
      return registrations == null ? null : registrations.responseCharacterEncoding();
    }
    if ("setResponseCharacterEncoding".equals(name)) {
      requireRegistrations();
      registrations.responseCharacterEncoding((String) args[0]);
      return null;
    }
    if ("addListener".equals(name)) {
      requireRegistrations();
      addListener(args[0]);
      return null;
    }
    if ("createListener".equals(name)) {
      requireRegistrations();
      return registrations.createListener(castClass(args[0]));
    }
    if ("declareRoles".equals(name)) {
      requireRegistrations();
      registrations.declareRoles((String[]) args[0]);
      return null;
    }
    if ("log".equals(name)) {
      log(args);
      return null;
    }
    throw new UnsupportedOperationException("ServletContext." + name + " is not implemented in M1");
  }

  private ServletRegistration.Dynamic addServlet(Object[] args) throws ServletException {
    String registrationName = (String) args[0];
    Object component = args[1];
    if (component instanceof Servlet) {
      return registrations.addServlet(registrationName, (Servlet) component);
    }
    if (component instanceof Class<?>) {
      return registrations.addServlet(
          registrationName, registrations.createServlet(castClass(component)));
    }
    return registrations.addServlet(
        registrationName,
        registrations.createServlet(loadClass((String) component, Servlet.class)));
  }

  private FilterRegistration.Dynamic addFilter(Object[] args) throws ServletException {
    String registrationName = (String) args[0];
    Object component = args[1];
    if (component instanceof Filter) {
      return registrations.addFilter(registrationName, (Filter) component);
    }
    if (component instanceof Class<?>) {
      return registrations.addFilter(
          registrationName, registrations.createFilter(castClass(component)));
    }
    return registrations.addFilter(
        registrationName, registrations.createFilter(loadClass((String) component, Filter.class)));
  }

  private void addListener(Object component) throws ServletException {
    EventListener listener;
    if (component instanceof EventListener) {
      listener = (EventListener) component;
    } else if (component instanceof Class<?>) {
      listener = registrations.createListener(castClass(component));
    } else {
      listener = registrations.createListener(loadClass((String) component, EventListener.class));
    }
    registrations.addListener(listener);
  }

  private void requireRegistrations() {
    if (registrations == null) {
      throw new UnsupportedOperationException("dynamic registration is not available");
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> castClass(Object type) {
    return (Class<T>) type;
  }

  @SuppressWarnings("unchecked")
  private static Set<SessionTrackingMode> castSet(Object value) {
    return (Set<SessionTrackingMode>) value;
  }

  private <T> Class<? extends T> loadClass(String className, Class<T> expectedType)
      throws ServletException {
    try {
      Class<?> type = Class.forName(className, true, applicationClassLoader);
      return type.asSubclass(expectedType);
    } catch (ClassNotFoundException | ClassCastException e) {
      throw new ServletException("cannot load " + className, e);
    }
  }

  private void removeAttribute(String name) {
    Object removed = attributes.remove(name);
    if (removed != null) {
      attributeEvents.contextRemoved(proxy, name, removed);
    }
  }

  private Object objectMethod(String name, Object object, Object[] args) {
    if ("toString".equals(name)) {
      return "LingTongServletContext[" + (contextPath.isEmpty() ? "/" : contextPath) + "]";
    }
    if ("hashCode".equals(name)) {
      return System.identityHashCode(object);
    }
    if ("equals".equals(name)) {
      return object == args[0];
    }
    return null;
  }

  private void log(Object[] args) {
    if (args == null || args.length == 0) {
      return;
    }
    String message = String.valueOf(args[0]);
    Throwable throwable =
        args.length > 1 && args[1] instanceof Throwable ? (Throwable) args[1] : null;
    if (throwable == null) {
      LOGGER.info(message);
    } else {
      LOGGER.log(Level.INFO, message, throwable);
    }
  }

  /** 封装注册信息控制器的状态与处理边界。 */
  interface RegistrationController {
    ServletRegistration.Dynamic addServlet(String name, Servlet servlet);

    FilterRegistration.Dynamic addFilter(String name, Filter filter);

    ServletRegistration servletRegistration(String name);

    Map<String, ? extends ServletRegistration> servletRegistrations();

    FilterRegistration filterRegistration(String name);

    Map<String, ? extends FilterRegistration> filterRegistrations();

    String contextInitParameter(String name);

    java.util.Enumeration<String> contextInitParameterNames();

    boolean setContextInitParameter(String name, String value);

    RequestDispatcher requestDispatcher(String path);

    RequestDispatcher namedDispatcher(String name);

    SessionCookieConfig sessionCookieConfig();

    void sessionTrackingModes(Set<SessionTrackingMode> modes);

    Set<SessionTrackingMode> defaultSessionTrackingModes();

    Set<SessionTrackingMode> effectiveSessionTrackingModes();

    void sessionTimeout(int minutes);

    int sessionTimeout();

    void requestCharacterEncoding(String encoding);

    String requestCharacterEncoding();

    void responseCharacterEncoding(String encoding);

    String responseCharacterEncoding();

    <T extends Servlet> T createServlet(Class<T> type) throws ServletException;

    <T extends Filter> T createFilter(Class<T> type) throws ServletException;

    <T extends EventListener> T createListener(Class<T> type) throws ServletException;

    void addListener(EventListener listener);

    void declareRoles(String... roleNames);
  }
}
