/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

import io.github.o1o00o10.lingtong.servlet.ServletSecurityPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.SessionTrackingMode;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** 将 web.xml / web-fragment.xml 转为中间模型，并在解析阶段拒绝不受支持的配置。 */
final class WebXmlParser {
  /** 单个部署描述符的最大读取字节数。 */
  private static final long MAX_DESCRIPTOR_BYTES = 1024L * 1024L;

  /** 主描述符不存在时返回空模型，由编译器继续发现注解与 SCI。 */
  WebXmlModel parse(Path descriptor) throws WebAppDeploymentException {
    if (!Files.exists(descriptor)) {
      return new WebXmlModel();
    }
    try {
      if (!Files.isRegularFile(descriptor) || Files.size(descriptor) > MAX_DESCRIPTOR_BYTES) {
        throw new WebAppDeploymentException("WEB-INF/web.xml is not a bounded regular file");
      }
      try (InputStream input = Files.newInputStream(descriptor)) {
        return parse(input, "WEB-INF/web.xml", false);
      }
    } catch (WebAppDeploymentException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new WebAppDeploymentException("cannot parse WEB-INF/web.xml", e);
    }
  }

  /** 解析库内 fragment；source 标识出错的 JAR 条目。 */
  WebXmlModel parseFragment(InputStream input, String source) throws WebAppDeploymentException {
    return parse(input, source, true);
  }

  private WebXmlModel parse(InputStream input, String source, boolean fragment)
      throws WebAppDeploymentException {
    try {
      byte[] descriptor = readBounded(input);
      DocumentBuilderFactory factory = secureFactory();
      Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(descriptor));
      Element root = document.getDocumentElement();
      String expectedRoot = fragment ? "web-fragment" : "web-app";
      if (root == null || !expectedRoot.equals(localName(root))) {
        throw new WebAppDeploymentException(source + " root must be " + expectedRoot);
      }
      WebXmlModel model = new WebXmlModel();
      String metadataComplete = root.getAttribute("metadata-complete");
      if (metadataComplete != null && !metadataComplete.trim().isEmpty()) {
        model.metadataComplete = parseBoolean(metadataComplete.trim(), "metadata-complete");
      }
      parseRoot(root, model, fragment);
      return model;
    } catch (WebAppDeploymentException e) {
      throw e;
    } catch (IOException | ParserConfigurationException | SAXException | RuntimeException e) {
      throw new WebAppDeploymentException("cannot parse " + source, e);
    }
  }

  /** 流式累计时检查上限，避免描述符耗尽内存。 */
  private static byte[] readBounded(InputStream input)
      throws IOException, WebAppDeploymentException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    int total = 0;
    while ((count = input.read(buffer)) >= 0) {
      if (total > MAX_DESCRIPTOR_BYTES - count) {
        throw new WebAppDeploymentException("deployment descriptor exceeds 1 MiB");
      }
      output.write(buffer, 0, count);
      total += count;
    }
    return output.toByteArray();
  }

  /** 禁止 DOCTYPE、外部实体与外部 Schema 访问。 */
  private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory;
  }

  /** 根据根元素的子节点类型填充模型，同时区分主描述符与 fragment 的合法字段。 */
  private static void parseRoot(Element root, WebXmlModel model, boolean fragment)
      throws WebAppDeploymentException {
    for (Element element : children(root)) {
      String name = localName(element);
      if ("context-param".equals(name)) {
        String key = requiredChildText(element, "param-name");
        putUnique(
            model.contextParameters,
            key,
            requiredChildText(element, "param-value"),
            "context parameter");
      } else if ("servlet".equals(name)) {
        WebXmlModel.ServletModel servlet = parseServlet(element);
        putUnique(model.servlets, servlet.name, servlet, "Servlet");
      } else if ("servlet-mapping".equals(name)) {
        model.servletMappings.add(parseServletMapping(element));
      } else if ("filter".equals(name)) {
        WebXmlModel.FilterModel filter = parseFilter(element);
        putUnique(model.filters, filter.name, filter, "Filter");
      } else if ("filter-mapping".equals(name)) {
        model.filterMappings.add(parseFilterMapping(element));
      } else if ("listener".equals(name)) {
        model.listeners.add(requiredChildText(element, "listener-class"));
      } else if ("error-page".equals(name)) {
        model.errorPages.add(parseErrorPage(element));
      } else if ("mime-mapping".equals(name)) {
        parseMimeMapping(element, model);
      } else if ("welcome-file-list".equals(name)) {
        parseWelcomeFiles(element, model);
      } else if ("locale-encoding-mapping-list".equals(name)) {
        parseLocaleEncodingMappings(element, model);
      } else if ("security-role".equals(name)) {
        rejectUnsupportedChildren(element, set("description", "role-name"));
        model.securityRoles.add(requiredChildText(element, "role-name"));
      } else if ("login-config".equals(name)) {
        if (model.login != null) {
          throw new WebAppDeploymentException("duplicate login-config");
        }
        model.login = parseLogin(element);
      } else if ("security-constraint".equals(name)) {
        model.securityConstraints.addAll(parseSecurityConstraint(element));
      } else if ("deny-uncovered-http-methods".equals(name)) {
        if (model.denyUncoveredHttpMethods) {
          throw new WebAppDeploymentException("duplicate deny-uncovered-http-methods");
        }
        rejectUnsupportedChildren(element, java.util.Collections.<String>emptySet());
        model.denyUncoveredHttpMethods = true;
      } else if ("session-config".equals(name)) {
        if (model.session != null) {
          throw new WebAppDeploymentException("duplicate session-config");
        }
        model.session = parseSession(element);
      } else if ("request-character-encoding".equals(name)) {
        if (model.requestCharacterEncoding != null) {
          throw new WebAppDeploymentException("duplicate request-character-encoding");
        }
        model.requestCharacterEncoding = parseCharacterEncoding(element);
      } else if ("response-character-encoding".equals(name)) {
        if (model.responseCharacterEncoding != null) {
          throw new WebAppDeploymentException("duplicate response-character-encoding");
        }
        model.responseCharacterEncoding = parseCharacterEncoding(element);
      } else if (!fragment && "display-name".equals(name)) {
        if (model.displayName != null) {
          throw new WebAppDeploymentException("duplicate display-name");
        }
        model.displayName = requiredText(element);
      } else if (fragment && "name".equals(name)) {
        if (model.fragmentName != null) {
          throw new WebAppDeploymentException("duplicate web-fragment name");
        }
        model.fragmentName = requiredText(element);
      } else if (fragment && "ordering".equals(name)) {
        if (model.relativeOrdering != null) {
          throw new WebAppDeploymentException("duplicate web-fragment ordering");
        }
        model.relativeOrdering = parseRelativeOrdering(element);
      } else if (!fragment && "absolute-ordering".equals(name)) {
        if (model.absoluteOrdering != null) {
          throw new WebAppDeploymentException("duplicate absolute-ordering");
        }
        model.absoluteOrdering = parseAbsoluteOrdering(element);
      } else if (!"display-name".equals(name)
          && !"description".equals(name)
          && !"distributable".equals(name)) {
        throw new WebAppDeploymentException("unsupported web.xml element: " + name);
      }
    }
  }

  private static void parseMimeMapping(Element element, WebXmlModel model)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("extension", "mime-type"));
    String extension = requiredChildText(element, "extension").toLowerCase(Locale.ROOT);
    String mimeType = requiredChildText(element, "mime-type");
    String previous = model.mimeMappings.put(extension, mimeType);
    if (previous != null && !previous.equals(mimeType)) {
      throw new WebAppDeploymentException("conflicting MIME mapping for extension " + extension);
    }
  }

  private static void parseWelcomeFiles(Element element, WebXmlModel model)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("welcome-file"));
    java.util.List<Element> files = childElements(element, "welcome-file");
    if (files.isEmpty()) {
      throw new WebAppDeploymentException("welcome-file-list requires a welcome-file");
    }
    for (Element file : files) {
      String value = requiredText(file);
      if (value.startsWith("/") || value.endsWith("/")) {
        throw new WebAppDeploymentException(
            "welcome-file must be a partial URI without boundary slashes");
      }
      model.welcomeFiles.add(value);
    }
  }

  private static void parseLocaleEncodingMappings(Element element, WebXmlModel model)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("locale-encoding-mapping"));
    for (Element mapping : childElements(element, "locale-encoding-mapping")) {
      rejectUnsupportedChildren(mapping, set("locale", "encoding"));
      String locale = normalizeLocale(requiredChildText(mapping, "locale"));
      String encoding = canonicalCharacterEncoding(requiredChildText(mapping, "encoding"));
      String previous = model.localeEncodingMappings.put(locale, encoding);
      if (previous != null && !previous.equals(encoding)) {
        throw new WebAppDeploymentException("conflicting locale encoding mapping for " + locale);
      }
    }
  }

  /** 读取主描述符的绝对排序，保留 others 的插入位置。 */
  private static WebXmlModel.Ordering parseAbsoluteOrdering(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("name", "others"));
    WebXmlModel.Ordering ordering = new WebXmlModel.Ordering();
    Set<String> names = new HashSet<String>();
    for (Element child : children(element)) {
      if ("name".equals(localName(child))) {
        String name = requiredText(child);
        if (names.add(name)) ordering.before.add(name);
      } else if (ordering.beforeOthers) {
        throw new WebAppDeploymentException("absolute-ordering contains duplicate others");
      } else {
        ordering.beforeOthers = true;
        ordering.othersPosition = ordering.before.size();
      }
    }
    return ordering;
  }

  /** 读取 fragment 的 before/after 约束，供编译阶段拓扑排序。 */
  private static WebXmlModel.Ordering parseRelativeOrdering(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("before", "after"));
    WebXmlModel.Ordering ordering = new WebXmlModel.Ordering();
    Element before = optionalChild(element, "before");
    Element after = optionalChild(element, "after");
    if (before != null) parseOrderingSide(before, ordering.before, true, ordering);
    if (after != null) parseOrderingSide(after, ordering.after, false, ordering);
    if (ordering.beforeOthers && ordering.afterOthers) {
      throw new WebAppDeploymentException("web-fragment cannot be both before and after others");
    }
    return ordering;
  }

  private static void parseOrderingSide(
      Element side, java.util.List<String> names, boolean before, WebXmlModel.Ordering ordering)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(side, set("name", "others"));
    Set<String> unique = new HashSet<String>();
    boolean others = false;
    for (Element child : children(side)) {
      if ("name".equals(localName(child))) {
        String value = requiredText(child);
        if (unique.add(value)) names.add(value);
      } else if (others) {
        throw new WebAppDeploymentException("duplicate others in web-fragment ordering");
      } else {
        others = true;
      }
    }
    if (before) ordering.beforeOthers = others;
    else ordering.afterOthers = others;
  }

  private static WebXmlModel.ServletModel parseServlet(Element element)
      throws WebAppDeploymentException {
    WebXmlModel.ServletModel model = new WebXmlModel.ServletModel();
    model.name = requiredChildText(element, "servlet-name");
    model.className = requiredChildText(element, "servlet-class");
    Set<String> supported =
        set(
            "servlet-name",
            "servlet-class",
            "init-param",
            "load-on-startup",
            "async-supported",
            "multipart-config",
            "security-role-ref",
            "description",
            "display-name");
    rejectUnsupportedChildren(element, supported);
    for (Element parameter : childElements(element, "init-param")) {
      putUnique(
          model.initParameters,
          requiredChildText(parameter, "param-name"),
          requiredChildText(parameter, "param-value"),
          "Servlet init parameter");
    }
    for (Element reference : childElements(element, "security-role-ref")) {
      rejectUnsupportedChildren(reference, set("description", "role-name", "role-link"));
      putUnique(
          model.roleReferences,
          requiredChildText(reference, "role-name"),
          requiredChildText(reference, "role-link"),
          "Servlet security role reference");
    }
    String async = optionalChildText(element, "async-supported");
    model.asyncSupportedSpecified = async != null;
    model.asyncSupported = async != null && parseBoolean(async, "Servlet async-supported");
    String load = optionalChildText(element, "load-on-startup");
    model.loadOnStartup = load == null ? null : parseInteger(load, "load-on-startup");
    Element multipart = optionalChild(element, "multipart-config");
    if (multipart != null) {
      rejectUnsupportedChildren(
          multipart, set("location", "max-file-size", "max-request-size", "file-size-threshold"));
      String location = optionalChildText(multipart, "location");
      String maxFile = optionalChildText(multipart, "max-file-size");
      String maxRequest = optionalChildText(multipart, "max-request-size");
      String threshold = optionalChildText(multipart, "file-size-threshold");
      model.multipartConfig =
          new MultipartConfigElement(
              location == null ? "" : location,
              maxFile == null ? -1L : parseLong(maxFile, "max-file-size"),
              maxRequest == null ? -1L : parseLong(maxRequest, "max-request-size"),
              threshold == null ? 0 : parseInteger(threshold, "file-size-threshold"));
    }
    return model;
  }

  private static WebXmlModel.ServletMappingModel parseServletMapping(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("servlet-name", "url-pattern"));
    WebXmlModel.ServletMappingModel model = new WebXmlModel.ServletMappingModel();
    model.servletName = requiredChildText(element, "servlet-name");
    for (Element pattern : childElements(element, "url-pattern")) {
      model.urlPatterns.add(requiredText(pattern));
    }
    if (model.urlPatterns.isEmpty()) {
      throw new WebAppDeploymentException("servlet-mapping requires a url-pattern");
    }
    return model;
  }

  private static WebXmlModel.FilterModel parseFilter(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(
        element,
        set(
            "filter-name",
            "filter-class",
            "init-param",
            "async-supported",
            "description",
            "display-name"));
    WebXmlModel.FilterModel model = new WebXmlModel.FilterModel();
    model.name = requiredChildText(element, "filter-name");
    model.className = requiredChildText(element, "filter-class");
    for (Element parameter : childElements(element, "init-param")) {
      putUnique(
          model.initParameters,
          requiredChildText(parameter, "param-name"),
          requiredChildText(parameter, "param-value"),
          "Filter init parameter");
    }
    String async = optionalChildText(element, "async-supported");
    model.asyncSupportedSpecified = async != null;
    model.asyncSupported = async != null && parseBoolean(async, "Filter async-supported");
    return model;
  }

  private static WebXmlModel.FilterMappingModel parseFilterMapping(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(
        element, set("filter-name", "url-pattern", "servlet-name", "dispatcher"));
    WebXmlModel.FilterMappingModel model = new WebXmlModel.FilterMappingModel();
    model.filterName = requiredChildText(element, "filter-name");
    for (Element pattern : childElements(element, "url-pattern")) {
      model.urlPatterns.add(requiredText(pattern));
    }
    for (Element servletName : childElements(element, "servlet-name")) {
      model.servletNames.add(requiredText(servletName));
    }
    for (Element dispatcher : childElements(element, "dispatcher")) {
      try {
        model.dispatchers.add(
            DispatcherType.valueOf(requiredText(dispatcher).toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new WebAppDeploymentException("invalid filter dispatcher", e);
      }
    }
    if (model.urlPatterns.isEmpty() && model.servletNames.isEmpty()) {
      throw new WebAppDeploymentException("filter-mapping requires a mapping target");
    }
    return model;
  }

  private static WebXmlModel.ErrorPageModel parseErrorPage(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("error-code", "exception-type", "location"));
    WebXmlModel.ErrorPageModel model = new WebXmlModel.ErrorPageModel();
    String code = optionalChildText(element, "error-code");
    model.errorCode = code == null ? null : parseInteger(code, "error-code");
    model.exceptionType = optionalChildText(element, "exception-type");
    model.location = requiredChildText(element, "location");
    if (model.errorCode != null && model.exceptionType != null) {
      throw new WebAppDeploymentException(
          "error-page cannot contain both error-code and exception-type");
    }
    return model;
  }

  private static WebXmlModel.SessionModel parseSession(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("session-timeout", "cookie-config", "tracking-mode"));
    WebXmlModel.SessionModel model = new WebXmlModel.SessionModel();
    String timeout = optionalChildText(element, "session-timeout");
    model.timeoutMinutes = timeout == null ? null : parseInteger(timeout, "session-timeout");
    Element cookie = optionalChild(element, "cookie-config");
    if (cookie != null) {
      rejectUnsupportedChildren(
          cookie, set("name", "domain", "path", "comment", "http-only", "secure", "max-age"));
      model.cookieName = optionalChildText(cookie, "name");
      model.cookieDomain = optionalChildText(cookie, "domain");
      model.cookiePath = optionalChildText(cookie, "path");
      model.cookieComment = optionalChildText(cookie, "comment");
      String maxAge = optionalChildText(cookie, "max-age");
      model.cookieMaxAge = maxAge == null ? null : parseInteger(maxAge, "cookie max-age");
      String httpOnly = optionalChildText(cookie, "http-only");
      model.cookieHttpOnly = httpOnly == null ? null : parseBoolean(httpOnly, "cookie http-only");
      String secure = optionalChildText(cookie, "secure");
      model.cookieSecure = secure == null ? null : parseBoolean(secure, "cookie secure");
    }
    for (Element tracking : childElements(element, "tracking-mode")) {
      try {
        model.trackingModes.add(
            SessionTrackingMode.valueOf(requiredText(tracking).toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new WebAppDeploymentException("invalid session tracking-mode", e);
      }
    }
    return model;
  }

  private static WebXmlModel.LoginModel parseLogin(Element element)
      throws WebAppDeploymentException {
    rejectUnsupportedChildren(element, set("auth-method", "realm-name", "form-login-config"));
    String method = requiredChildText(element, "auth-method").toUpperCase(Locale.ROOT);
    if (!"BASIC".equals(method) && !"FORM".equals(method) && !"CLIENT-CERT".equals(method)) {
      throw new WebAppDeploymentException("unsupported authentication method: " + method);
    }
    Element form = optionalChild(element, "form-login-config");
    if (!"FORM".equals(method) && form != null) {
      throw new WebAppDeploymentException(
          "form-login-config can only be used with FORM authentication");
    }
    WebXmlModel.LoginModel model = new WebXmlModel.LoginModel();
    String realm = optionalChildText(element, "realm-name");
    model.authenticationMethod =
        "CLIENT-CERT".equals(method)
            ? ServletSecurityPolicy.AuthenticationMethod.CLIENT_CERT
            : ServletSecurityPolicy.AuthenticationMethod.valueOf(method);
    model.realmName = realm == null ? "LingTong" : realm;
    if ("FORM".equals(method)) {
      if (form == null) {
        throw new WebAppDeploymentException("FORM authentication requires form-login-config");
      }
      rejectUnsupportedChildren(form, set("form-login-page", "form-error-page"));
      model.formLoginPage =
          requireApplicationPath(requiredChildText(form, "form-login-page"), "FORM login page");
      model.formErrorPage =
          requireApplicationPath(requiredChildText(form, "form-error-page"), "FORM error page");
    }
    return model;
  }

  private static String requireApplicationPath(String value, String type)
      throws WebAppDeploymentException {
    if (value.charAt(0) != '/' || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
      throw new WebAppDeploymentException(
          type + " must start with '/' and must not contain a query or fragment");
    }
    return value;
  }

  private static java.util.List<WebXmlModel.SecurityConstraintModel> parseSecurityConstraint(
      Element element) throws WebAppDeploymentException {
    rejectUnsupportedChildren(
        element,
        set("display-name", "web-resource-collection", "auth-constraint", "user-data-constraint"));
    java.util.List<Element> resources = childElements(element, "web-resource-collection");
    if (resources.isEmpty()) {
      throw new WebAppDeploymentException("security-constraint requires a web-resource-collection");
    }
    Element auth = optionalChild(element, "auth-constraint");
    Set<String> roles = new LinkedHashSet<String>();
    if (auth != null) {
      rejectUnsupportedChildren(auth, set("description", "role-name"));
      for (Element role : childElements(auth, "role-name")) {
        roles.add(requiredText(role));
      }
    }
    ServletSecurityPolicy.TransportGuarantee guarantee =
        ServletSecurityPolicy.TransportGuarantee.NONE;
    Element userData = optionalChild(element, "user-data-constraint");
    if (userData != null) {
      rejectUnsupportedChildren(userData, set("description", "transport-guarantee"));
      try {
        guarantee =
            ServletSecurityPolicy.TransportGuarantee.valueOf(
                requiredChildText(userData, "transport-guarantee").toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new WebAppDeploymentException("invalid transport-guarantee", e);
      }
    }

    java.util.List<WebXmlModel.SecurityConstraintModel> result =
        new java.util.ArrayList<WebXmlModel.SecurityConstraintModel>();
    for (Element resource : resources) {
      rejectUnsupportedChildren(
          resource,
          set(
              "web-resource-name",
              "description",
              "url-pattern",
              "http-method",
              "http-method-omission"));
      requiredChildText(resource, "web-resource-name");
      WebXmlModel.SecurityConstraintModel constraint = new WebXmlModel.SecurityConstraintModel();
      for (Element pattern : childElements(resource, "url-pattern")) {
        constraint.patterns.add(requiredText(pattern));
      }
      if (constraint.patterns.isEmpty()) {
        throw new WebAppDeploymentException("web-resource-collection requires a url-pattern");
      }
      for (Element method : childElements(resource, "http-method")) {
        constraint.methods.add(requiredText(method).toUpperCase(Locale.ROOT));
      }
      for (Element method : childElements(resource, "http-method-omission")) {
        constraint.omittedMethods.add(requiredText(method).toUpperCase(Locale.ROOT));
      }
      if (!constraint.methods.isEmpty() && !constraint.omittedMethods.isEmpty()) {
        throw new WebAppDeploymentException(
            "web-resource-collection cannot combine http-method and http-method-omission");
      }
      constraint.authConstraint = auth != null;
      constraint.roles.addAll(roles);
      constraint.transportGuarantee = guarantee;
      result.add(constraint);
    }
    return result;
  }

  private static String parseCharacterEncoding(Element element) throws WebAppDeploymentException {
    return canonicalCharacterEncoding(requiredText(element));
  }

  private static String canonicalCharacterEncoding(String value) throws WebAppDeploymentException {
    try {
      return Charset.forName(value).name();
    } catch (RuntimeException e) {
      throw new WebAppDeploymentException("unsupported character encoding: " + value, e);
    }
  }

  private static String normalizeLocale(String value) {
    return value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
  }

  /** 合并全部来源后校验 Servlet/Filter 映射指向已声明的目标。 */
  static void validateReferences(WebXmlModel model) throws WebAppDeploymentException {
    for (WebXmlModel.ServletMappingModel mapping : model.servletMappings) {
      if (!model.servlets.containsKey(mapping.servletName)) {
        throw new WebAppDeploymentException(
            "undefined Servlet mapping target: " + mapping.servletName);
      }
    }
    for (WebXmlModel.FilterMappingModel mapping : model.filterMappings) {
      if (!model.filters.containsKey(mapping.filterName)) {
        throw new WebAppDeploymentException(
            "undefined Filter mapping target: " + mapping.filterName);
      }
      for (String servletName : mapping.servletNames) {
        if (!model.servlets.containsKey(servletName)) {
          throw new WebAppDeploymentException("undefined Filter Servlet target: " + servletName);
        }
      }
    }
  }

  /** 对未知子元素直接报错，避免配置被容器静默忽略。 */
  private static void rejectUnsupportedChildren(Element parent, Set<String> supported)
      throws WebAppDeploymentException {
    for (Element child : children(parent)) {
      if (!supported.contains(localName(child))) {
        throw new WebAppDeploymentException(
            "unsupported " + localName(parent) + " element: " + localName(child));
      }
    }
  }

  private static Element optionalChild(Element parent, String name)
      throws WebAppDeploymentException {
    Element result = null;
    for (Element child : children(parent)) {
      if (name.equals(localName(child))) {
        if (result != null) {
          throw new WebAppDeploymentException("duplicate " + name);
        }
        result = child;
      }
    }
    return result;
  }

  private static String requiredChildText(Element parent, String name)
      throws WebAppDeploymentException {
    String value = optionalChildText(parent, name);
    if (value == null || value.isEmpty()) {
      throw new WebAppDeploymentException(localName(parent) + " requires " + name);
    }
    return value;
  }

  private static String optionalChildText(Element parent, String name)
      throws WebAppDeploymentException {
    Element child = optionalChild(parent, name);
    return child == null ? null : requiredText(child);
  }

  private static String requiredText(Element element) throws WebAppDeploymentException {
    String value = element.getTextContent() == null ? "" : element.getTextContent().trim();
    if (value.isEmpty()) {
      throw new WebAppDeploymentException(localName(element) + " must not be empty");
    }
    return value;
  }

  private static java.util.List<Element> childElements(Element parent, String name) {
    java.util.List<Element> result = new java.util.ArrayList<Element>();
    for (Element child : children(parent)) {
      if (name.equals(localName(child))) result.add(child);
    }
    return result;
  }

  private static java.util.List<Element> children(Element parent) {
    java.util.List<Element> result = new java.util.ArrayList<Element>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) result.add((Element) node);
    }
    return result;
  }

  private static String localName(Element element) {
    return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
  }

  private static int parseInteger(String value, String name) throws WebAppDeploymentException {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new WebAppDeploymentException("invalid " + name + ": " + value, e);
    }
  }

  private static long parseLong(String value, String name) throws WebAppDeploymentException {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new WebAppDeploymentException("invalid " + name + ": " + value, e);
    }
  }

  private static boolean parseBoolean(String value, String name) throws WebAppDeploymentException {
    if ("true".equalsIgnoreCase(value)) return true;
    if ("false".equalsIgnoreCase(value)) return false;
    throw new WebAppDeploymentException("invalid " + name + ": " + value);
  }

  private static Set<String> set(String... values) {
    Set<String> result = new HashSet<String>();
    java.util.Collections.addAll(result, values);
    return result;
  }

  private static <T> void putUnique(
      java.util.Map<String, T> values, String name, T value, String type)
      throws WebAppDeploymentException {
    if (values.put(name, value) != null) {
      throw new WebAppDeploymentException("duplicate " + type + ": " + name);
    }
  }
}
