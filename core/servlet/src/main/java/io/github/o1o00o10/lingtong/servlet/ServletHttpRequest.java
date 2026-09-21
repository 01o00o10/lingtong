/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import io.github.o1o00o10.lingtong.http.BodyMailbox;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.RequestTrace;
import io.github.o1o00o10.lingtong.http.TlsConnectionInfo;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将协议请求适配为 HttpServletRequest，并持有会话、参数、分派和异步视图。 */
final class ServletHttpRequest implements HttpServletRequest {
    /** 限制嵌套 forward/包含/异步 分派 深度，防止递归分派耗尽栈。 */
    private static final int MAX_DISPATCH_DEPTH = 32;
    /** 包含 期间临时覆盖、结束后必须恢复的 Servlet 规范属性。 */
    private static final String[] INCLUDE_ATTRIBUTES = {
            RequestDispatcher.INCLUDE_REQUEST_URI,
            RequestDispatcher.INCLUDE_CONTEXT_PATH,
            RequestDispatcher.INCLUDE_SERVLET_PATH,
            RequestDispatcher.INCLUDE_PATH_INFO,
            RequestDispatcher.INCLUDE_QUERY_STRING,
            RequestDispatcher.INCLUDE_MAPPING
    };

    /** 传输层解析出的不可变协议请求。 */
    private final HttpRequest request;

    RequestTrace trace() {
        return request.trace();
    }
    /** Servlet上下文。 */
    private final ServletContext servletContext;
    /** 上下文路径。 */
    private final String contextPath;
    /** 响应。 */
    private final ServletHttpResponse response;
    /** 会话管理器。 */
    private final SessionManager sessionManager;
    /** 属性事件集合。 */
    private final ServletAttributeEventDispatcher attributeEvents;
    /** 请求限制集合。 */
    private final ServletRequestLimits requestLimits;
    /** 会话跟踪模式集合。 */
    private final Set<SessionTrackingMode> sessionTrackingModes;
    /** 请求URI。 */
    private String requestUri;
    /** 查询串字符串。 */
    private String queryString;
    /** Servlet路径。 */
    private String servletPath;
    /** 路径信息。 */
    private String pathInfo;
    /** Servlet映射。 */
    private HttpServletMapping servletMapping;
    /** 可逐块消费的请求体邮箱，读取进度驱动传输背压。 */
    private final BodyMailbox body;
    /** 按键索引的属性集合。 */
    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    /** 请求的会话标识符。 */
    private final String requestedSessionId;
    /** 初始会话。 */
    private final ManagedSession initialSession;
    /** 请求的会话标识符有效标志，布尔标志。 */
    private final boolean requestedSessionIdValid;
    /** 客户端会话 ID 是否来自 Cookie；否则可来自 URL 重写。 */
    private final boolean requestedSessionIdFromCookie;
    /** 多段请求配置。 */
    private final MultipartConfigElement multipartConfig;
    /** 安全运行时。 */
    private final ServletSecurityRuntime securityRuntime;
    /** 按键索引的角色引用集合。 */
    private Map<String, String> roleReferences;

    /** 字符编码。 */
    private String characterEncoding;
    /** 按键索引的参数集合。 */
    private Map<String, String[]> parameters;
    /** 参数查询串字符串。 */
    private String parameterQueryString;
    /** 分派器类型。 */
    private DispatcherType dispatcherType = DispatcherType.REQUEST;
    /** 分派depth。 */
    private int dispatchDepth;
    /** 防止 getInputStream 与 getReader 同时占用请求体。 */
    private boolean inputStreamClaimed;
    /** 防止 getReader 与 getInputStream 同时占用请求体。 */
    private boolean readerClaimed;
    /** 输入流。 */
    private ServletInputStream inputStream;
    /** 读取器。 */
    private BufferedReader reader;
    /** 会话。 */
    private ManagedSession session;
    /** 按顺序保存的多段请求parts。 */
    private List<MultipartPart> multipartParts;
    /** 异步上下文。 */
    private ServletAsyncContext asyncContext;
    /** 异步已支持的，布尔标志。 */
    private boolean asyncSupported;
    /** 安全身份。 */
    private ServletSecurityIdentity securityIdentity;
    /** 认证类型。 */
    private String authenticationType;
    /** 方法。 */
    private String method;

    ServletHttpRequest(
            HttpRequest request,
            ServletContext servletContext,
            ServletHttpResponse response,
            SessionManager sessionManager,
            ServletAttributeEventDispatcher attributeEvents,
            ServletRequestLimits requestLimits,
            Set<SessionTrackingMode> sessionTrackingModes,
            String contextPath,
            String requestUri,
            String queryString,
            String servletPath,
            String pathInfo,
            HttpServletMapping servletMapping,
            String requestedUrlSessionId,
            MultipartConfigElement multipartConfig,
            String defaultCharacterEncoding,
            ServletSecurityRuntime securityRuntime,
            Map<String, String> roleReferences) {
        this.request = request;
        this.method = request.method();
        this.servletContext = servletContext;
        this.response = response;
        this.sessionManager = sessionManager;
        this.attributeEvents = attributeEvents;
        this.requestLimits = requestLimits;
        this.sessionTrackingModes = sessionTrackingModes;
        this.contextPath = contextPath;
        this.requestUri = requestUri;
        this.queryString = queryString;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        this.servletMapping = servletMapping;
        this.multipartConfig = multipartConfig;
        this.securityRuntime = securityRuntime;
        this.roleReferences = roleReferences;
        String contentTypeEncoding = charsetFromContentType(request.headers().first("content-type"));
        this.characterEncoding = contentTypeEncoding == null
                ? defaultCharacterEncoding : contentTypeEncoding;
        this.parameterQueryString = queryString;
        this.body = request.bodyMailbox();
        installTlsAttributes(request.connectionInfo().tls());
        String cookieSessionId = sessionTrackingModes.contains(SessionTrackingMode.COOKIE)
                ? findSessionCookie(request.headers().first("cookie")) : null;
        this.requestedSessionIdFromCookie = cookieSessionId != null;
        this.requestedSessionId = cookieSessionId != null ? cookieSessionId
                : sessionTrackingModes.contains(SessionTrackingMode.URL) ? requestedUrlSessionId : null;
        this.initialSession = sessionManager.findAndBeginAccess(requestedSessionId);
        this.session = initialSession;
        this.requestedSessionIdValid = initialSession != null;
        if (initialSession != null) {
            ServletSecuritySessionState state = initialSession.securityState();
            if (state != null) {
                securityIdentity = state.identity();
                authenticationType = state.authenticationType();
            }
            ServletSecuritySessionState.SavedRequest saved =
                    initialSession.consumeSavedRequest(requestUri, queryString);
            if (saved != null) {
                method = saved.method();
                parameters = saved.parameters();
            }
        }
    }

    private void installTlsAttributes(TlsConnectionInfo tls) {
        if (tls == null) {
            return;
        }
        attributes.put("javax.servlet.request.cipher_suite", tls.cipherSuite());
        attributes.put("javax.servlet.request.key_size", Integer.valueOf(tls.keySize()));
        attributes.put("javax.servlet.request.ssl_session_id", tls.sessionId());
        java.security.cert.X509Certificate[] certificates = tls.peerCertificates();
        if (certificates.length > 0) {
            attributes.put("javax.servlet.request.X509Certificate", certificates);
        }
    }

    @Override
    public String getAuthType() {
        return authenticationType;
    }

    @Override
    public Cookie[] getCookies() {
        String header = getHeader("cookie");
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        List<Cookie> cookies = new ArrayList<Cookie>();
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                cookies.add(new Cookie(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim()));
            }
        }
        return cookies.isEmpty() ? null : cookies.toArray(new Cookie[cookies.size()]);
    }

    @Override
    public long getDateHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1L;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid HTTP date header", e);
        }
    }

    @Override
    public String getHeader(String name) {
        return request.headers().first(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(request.headers().all(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : request.headers().entries()) {
            names.add(entry.getKey());
        }
        return Collections.enumeration(names);
    }

    @Override
    public int getIntHeader(String name) {
        String value = getHeader(name);
        return value == null ? -1 : Integer.parseInt(value);
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getPathInfo() {
        return pathInfo;
    }

    @Override
    public String getPathTranslated() {
        return pathInfo == null ? null : servletContext.getRealPath(pathInfo);
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    @Override
    public String getRemoteUser() {
        return securityIdentity == null ? null : securityIdentity.name();
    }

    @Override
    public boolean isUserInRole(String role) {
        return securityRuntime.isUserInRole(securityIdentity, roleReferences, role);
    }

    @Override
    public Principal getUserPrincipal() {
        return securityIdentity == null ? null : securityIdentity.principal();
    }

    @Override
    public String getRequestedSessionId() {
        return requestedSessionId;
    }

    @Override
    public String getRequestURI() {
        return requestUri;
    }

    @Override
    public StringBuffer getRequestURL() {
        String host = getServerName();
        boolean ipv6 = host.indexOf(':') >= 0;
        StringBuffer result = new StringBuffer(getScheme()).append("://");
        if (ipv6) {
            result.append('[').append(host).append(']');
        } else {
            result.append(host);
        }
        if (!("http".equals(getScheme()) && getServerPort() == 80)
                && !("https".equals(getScheme()) && getServerPort() == 443)) {
            result.append(':').append(getServerPort());
        }
        return result.append(requestUri);
    }

    @Override
    public String getServletPath() {
        return servletPath;
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (session != null && session.isValid()) {
            return session;
        }
        if (!create) {
            return null;
        }
        if (response.isCommitted()) {
            throw new IllegalStateException("cannot create an HTTP session after the response is committed");
        }
        session = sessionManager.create(servletContext);
        addSessionCookie(session.getId());
        return session;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public String changeSessionId() {
        ManagedSession current = (ManagedSession) getSession(false);
        if (current == null) {
            throw new IllegalStateException("no valid HTTP session is associated with this request");
        }
        String replacement = sessionManager.changeId(current);
        addSessionCookie(replacement);
        return replacement;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return requestedSessionIdValid;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return requestedSessionIdFromCookie;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return requestedSessionId != null && !requestedSessionIdFromCookie;
    }

    @Deprecated
    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }

    void finishSessionAccess() {
        if (initialSession != null) {
            sessionManager.endAccess(initialSession);
        }
        if (session != null && session != initialSession) {
            sessionManager.endAccess(session);
        }
        if (multipartParts != null) {
            for (MultipartPart part : multipartParts) {
                part.cleanup();
            }
        }
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws ServletException {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        return securityRuntime.authenticate(this, response);
    }

    @Override
    public void login(String username, String password) throws ServletException {
        if (securityIdentity != null) {
            throw new ServletException("request caller is already authenticated");
        }
        securityIdentity = securityRuntime.login(username, password);
        authenticationType = securityRuntime.authenticationType();
        persistAuthentication(true, true);
    }

    @Override
    public void logout() throws ServletException {
        securityIdentity = null;
        authenticationType = null;
        if (session != null) session.clearSecurityState();
    }

    ServletSecurityIdentity securityIdentity() {
        return securityIdentity;
    }

    HttpRequest rawRequest() {
        return request;
    }

    void securityIdentity(ServletSecurityIdentity identity, String authType) {
        securityIdentity = identity;
        authenticationType = authType;
        persistAuthentication(false, false);
    }

    void establishFormIdentity(ServletSecurityIdentity identity) {
        securityIdentity = identity;
        authenticationType = HttpServletRequest.FORM_AUTH;
        persistAuthentication(true, true);
    }

    void saveFormRequest() {
        ManagedSession current = (ManagedSession) getSession(true);
        current.saveRequest(new ServletSecuritySessionState.SavedRequest(
                getMethod(), getRequestURI(), getQueryString(), getParameterMap()));
    }

    ServletSecuritySessionState.SavedRequest savedFormRequest() {
        ManagedSession current = (ManagedSession) getSession(false);
        return current == null ? null : current.savedRequest();
    }

    private void persistAuthentication(boolean createSession, boolean rotateId) {
        ManagedSession current = (ManagedSession) getSession(createSession);
        if (current == null) return;
        current.securityState(new ServletSecuritySessionState(
                securityIdentity, authenticationType));
        if (rotateId) changeSessionId();
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        if (multipartConfig == null) {
            throw new IllegalStateException("target Servlet has no multipart configuration");
        }
        if (multipartParts == null) {
            multipartParts = MultipartParser.parse(body(), getContentType(), multipartConfig, servletContext);
        }
        return Collections.<Part>unmodifiableList(multipartParts);
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        if (name == null) {
            throw new IllegalArgumentException("part name must not be null");
        }
        for (Part part : getParts()) {
            if (name.equals(part.getName())) {
                return part;
            }
        }
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws ServletException {
        throw new ServletException("HTTP upgrade is not implemented in M1");
    }

    @Override
    public HttpServletMapping getHttpServletMapping() {
        return servletMapping;
    }

    @Override
    public Map<String, String> getTrailerFields() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> entry : request.trailers().entries()) {
            StringBuilder combined = new StringBuilder();
            for (String value : entry.getValue()) {
                if (combined.length() > 0) {
                    combined.append(", ");
                }
                combined.append(value);
            }
            result.put(entry.getKey(), combined.toString());
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public boolean isTrailerFieldsReady() {
        return body.isComplete();
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        if (readerClaimed || inputStreamClaimed) {
            return;
        }
        try {
            Charset.forName(encoding);
        } catch (RuntimeException e) {
            throw new UnsupportedEncodingException(encoding);
        }
        characterEncoding = encoding;
    }

    @Override
    public int getContentLength() {
        long length = getContentLengthLong();
        return length < 0 || length > Integer.MAX_VALUE ? -1 : (int) length;
    }

    @Override
    public long getContentLengthLong() {
        String value = getHeader("content-length");
        return value == null ? -1L : Long.parseLong(value);
    }

    @Override
    public String getContentType() {
        return getHeader("content-type");
    }

    @Override
    public ServletInputStream getInputStream() {
        if (readerClaimed) {
            throw new IllegalStateException("getReader() has already been called");
        }
        inputStreamClaimed = true;
        if (inputStream == null) {
            inputStream = new BodyInputStream(body);
        }
        return inputStream;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameters().get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters().keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = parameters().get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> copy = new LinkedHashMap<String, String[]>();
        for (Map.Entry<String, String[]> entry : parameters().entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String getProtocol() {
        return request.version();
    }

    @Override
    public String getScheme() {
        return request.connectionInfo().scheme();
    }

    @Override
    public String getServerName() {
        return request.connectionInfo().serverName();
    }

    @Override
    public int getServerPort() {
        return request.connectionInfo().serverPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (inputStreamClaimed) {
            throw new IllegalStateException("getInputStream() has already been called");
        }
        readerClaimed = true;
        if (reader == null) {
            String encoding = characterEncoding == null ? "ISO-8859-1" : characterEncoding;
            reader = new BufferedReader(new InputStreamReader(new BodyInputStream(body), encoding));
        }
        return reader;
    }

    @Override
    public String getRemoteAddr() {
        return request.connectionInfo().remoteAddress();
    }

    @Override
    public String getRemoteHost() {
        return getRemoteAddr();
    }

    @Override
    public void setAttribute(String name, Object value) {
        requireAttributeName(name);
        if (value == null) {
            removeAttribute(name);
        } else {
            Object previous = attributes.put(name, value);
            if (previous == null) {
                attributeEvents.requestAdded(servletContext, this, name, value);
            } else {
                attributeEvents.requestReplaced(servletContext, this, name, previous);
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        requireAttributeName(name);
        Object removed = attributes.remove(name);
        if (removed != null) {
            attributeEvents.requestRemoved(servletContext, this, name, removed);
        }
    }

    @Override
    public Locale getLocale() {
        return acceptedLocales().get(0);
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return Collections.enumeration(acceptedLocales());
    }

    @Override
    public boolean isSecure() {
        return request.connectionInfo().secure();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            return servletContext.getRequestDispatcher(path);
        }
        String applicationPath = servletPath + (pathInfo == null ? "" : pathInfo);
        int slash = applicationPath.lastIndexOf('/');
        String base = slash < 0 ? "/" : applicationPath.substring(0, slash + 1);
        int queryStart = path.indexOf('?');
        String relativePath = queryStart < 0 ? path : path.substring(0, queryStart);
        String query = queryStart < 0 ? "" : path.substring(queryStart);
        String normalized = URI.create(base + relativePath).normalize().getRawPath();
        return servletContext.getRequestDispatcher(normalized + query);
    }

    @Deprecated
    @Override
    public String getRealPath(String path) {
        return servletContext.getRealPath(path);
    }

    @Override
    public int getRemotePort() {
        return request.connectionInfo().remotePort();
    }

    @Override
    public String getLocalName() {
        return request.connectionInfo().localAddress();
    }

    @Override
    public String getLocalAddr() {
        return request.connectionInfo().localAddress();
    }

    @Override
    public int getLocalPort() {
        return request.connectionInfo().localPort();
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public AsyncContext startAsync() {
        return startAsync(this, response);
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        if (!asyncSupported) {
            throw new IllegalStateException("asynchronous processing is disabled by the current Servlet chain");
        }
        if (servletRequest == null || servletResponse == null) {
            throw new IllegalArgumentException("async request and response must not be null");
        }
        if (asyncContext == null) {
            throw new IllegalStateException("asynchronous processing is not attached to this request");
        }
        return asyncContext.startCycle(servletRequest, servletResponse, asyncDispatchPath());
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncContext != null && asyncContext.isStarted();
    }

    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    @Override
    public AsyncContext getAsyncContext() {
        if (!isAsyncStarted()) {
            throw new IllegalStateException("asynchronous Servlet processing has not started");
        }
        return asyncContext;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return dispatcherType;
    }

    void attachAsyncContext(ServletAsyncContext value) {
        asyncContext = value;
    }

    boolean setAsyncSupported(boolean value) {
        boolean previous = asyncSupported;
        asyncSupported = value;
        return previous;
    }

    void setAsyncDispatchAttributes() {
        if (attributes.containsKey(AsyncContext.ASYNC_REQUEST_URI)) {
            return;
        }
        attributes.put(AsyncContext.ASYNC_REQUEST_URI, requestUri);
        attributes.put(AsyncContext.ASYNC_CONTEXT_PATH, contextPath);
        attributes.put(AsyncContext.ASYNC_SERVLET_PATH, servletPath);
        putOrRemove(AsyncContext.ASYNC_PATH_INFO, pathInfo);
        putOrRemove(AsyncContext.ASYNC_QUERY_STRING, queryString);
        attributes.put(AsyncContext.ASYNC_MAPPING, servletMapping);
    }

    private String asyncDispatchPath() {
        String path = contextPath.isEmpty() ? requestUri : requestUri.substring(contextPath.length());
        return queryString == null || queryString.isEmpty() ? path : path + "?" + queryString;
    }

    DispatchState beginDispatch(
            DispatcherType type,
            String targetPath,
            String targetQuery,
            String targetServletPath,
            String targetPathInfo,
            HttpServletMapping targetMapping,
            Map<String, String> targetRoleReferences) throws ServletException {
        if (dispatchDepth >= MAX_DISPATCH_DEPTH) {
            throw new ServletException("maximum RequestDispatcher depth exceeded");
        }
        DispatchState state = new DispatchState(this, type == DispatcherType.INCLUDE);
        dispatchDepth++;
        dispatcherType = type;
        roleReferences = targetRoleReferences;
        if (targetPath == null) {
            return state;
        }

        parameterQueryString = combineQueries(targetQuery, parameterQueryString);
        parameters = null;
        if (type == DispatcherType.FORWARD || type == DispatcherType.ERROR) {
            if (type == DispatcherType.FORWARD) {
                setForwardAttribute(RequestDispatcher.FORWARD_REQUEST_URI, requestUri);
                setForwardAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, contextPath);
                setForwardAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, servletPath);
                setForwardAttribute(RequestDispatcher.FORWARD_PATH_INFO, pathInfo);
                setForwardAttribute(RequestDispatcher.FORWARD_QUERY_STRING, queryString);
                setForwardAttribute(RequestDispatcher.FORWARD_MAPPING, servletMapping);
            }
            requestUri = targetPath;
            queryString = targetQuery;
            servletPath = targetServletPath;
            pathInfo = targetPathInfo;
            servletMapping = targetMapping;
        } else if (type == DispatcherType.INCLUDE) {
            attributes.put(RequestDispatcher.INCLUDE_REQUEST_URI, targetPath);
            attributes.put(RequestDispatcher.INCLUDE_CONTEXT_PATH, contextPath);
            attributes.put(RequestDispatcher.INCLUDE_SERVLET_PATH, targetServletPath);
            putOrRemove(RequestDispatcher.INCLUDE_PATH_INFO, targetPathInfo);
            putOrRemove(RequestDispatcher.INCLUDE_QUERY_STRING, targetQuery);
            attributes.put(RequestDispatcher.INCLUDE_MAPPING, targetMapping);
        }
        return state;
    }

    void endDispatch(DispatchState state) {
        requestUri = state.requestUri;
        queryString = state.queryString;
        servletPath = state.servletPath;
        pathInfo = state.pathInfo;
        servletMapping = state.servletMapping;
        parameterQueryString = state.parameterQueryString;
        parameters = state.parameters;
        dispatcherType = state.dispatcherType;
        roleReferences = state.roleReferences;
        if (state.includeValues != null) {
            for (int i = 0; i < INCLUDE_ATTRIBUTES.length; i++) {
                if (state.includePresent[i]) {
                    attributes.put(INCLUDE_ATTRIBUTES[i], state.includeValues[i]);
                } else {
                    attributes.remove(INCLUDE_ATTRIBUTES[i]);
                }
            }
        }
        dispatchDepth--;
    }

    private Map<String, String[]> parameters() {
        if (parameters == null) {
            Map<String, List<String>> collected = new LinkedHashMap<String, List<String>>();
            ServletRequestLimits.ParameterBudget budget = requestLimits.parameterBudget();
            decodeParameters(parameterQueryString, collected, budget);
            String contentType = getContentType();
            if (contentType != null
                    && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
                decodeParameters(
                        new String(body(), StandardCharsets.ISO_8859_1), collected, budget);
            } else if (contentType != null
                    && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                collectMultipartParameters(collected, budget);
            }
            Map<String, String[]> result = new LinkedHashMap<String, String[]>();
            for (Map.Entry<String, List<String>> entry : collected.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toArray(new String[entry.getValue().size()]));
            }
            parameters = result;
        }
        return parameters;
    }

    private byte[] body() {
        return request.body();
    }

    private void collectMultipartParameters(
            Map<String, List<String>> destination,
            ServletRequestLimits.ParameterBudget budget) {
        try {
            for (Part part : getParts()) {
                if (part.getSubmittedFileName() != null) {
                    continue;
                }
                budget.addParameter();
                budget.addSourceBytes(part.getSize());
                ByteArrayOutputStream value = new ByteArrayOutputStream((int) Math.min(part.getSize(), 4096L));
                java.io.InputStream input = part.getInputStream();
                try {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        value.write(buffer, 0, count);
                    }
                } finally {
                    input.close();
                }
                String encoding = characterEncoding == null ? "UTF-8" : characterEncoding;
                List<String> values = destination.get(part.getName());
                if (values == null) {
                    values = new ArrayList<String>();
                    destination.put(part.getName(), values);
                }
                values.add(new String(value.toByteArray(), encoding));
            }
        } catch (IOException | ServletException e) {
            throw new IllegalStateException("cannot parse multipart form parameters", e);
        }
    }

    private void decodeParameters(
            String encoded,
            Map<String, List<String>> destination,
            ServletRequestLimits.ParameterBudget budget) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        budget.addSourceBytes(encoded.length());
        String encoding = characterEncoding == null ? "UTF-8" : characterEncoding;
        for (String pair : encoded.split("&", -1)) {
            budget.addParameter();
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            String name = decode(rawName, encoding);
            String value = decode(rawValue, encoding);
            List<String> values = destination.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                destination.put(name, values);
            }
            values.add(value);
        }
    }

    private String decode(String value, String encoding) {
        try {
            return URLDecoder.decode(value, encoding);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid request parameter encoding", e);
        }
    }

    private static String charsetFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String parameter : contentType.split(";")) {
            int equals = parameter.indexOf('=');
            if (equals < 0 || !"charset".equalsIgnoreCase(parameter.substring(0, equals).trim())) {
                continue;
            }
            String value = parameter.substring(equals + 1).trim();
            if (value.length() >= 2 && value.charAt(0) == '"'
                    && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private String findSessionCookie(String header) {
        if (header == null) {
            return null;
        }
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = pair.substring(0, equals).trim();
            if (sessionManager.cookieName().equals(name)) {
                return pair.substring(equals + 1).trim();
            }
        }
        return null;
    }

    private void addSessionCookie(String id) {
        if (!sessionTrackingModes.contains(SessionTrackingMode.COOKIE)) {
            return;
        }
        Cookie cookie = new SessionCookieValue(sessionManager.cookieName(), id);
        sessionManager.configureCookie(cookie, isSecure());
        response.addCookie(cookie);
    }

    String encodeSessionURL(String url) {
        if (url == null || !sessionTrackingModes.contains(SessionTrackingMode.URL)
                || (requestedSessionIdFromCookie && requestedSessionIdValid)) {
            return url;
        }
        ManagedSession current = session;
        if (current == null || !current.isValid() || !sameApplication(url)) {
            return url;
        }
        int suffix = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) {
            suffix = query;
        }
        if (fragment >= 0 && fragment < suffix) {
            suffix = fragment;
        }
        String path = url.substring(0, suffix);
        String lower = path.toLowerCase(Locale.ROOT);
        int existing = lower.indexOf(";jsessionid=");
        if (existing >= 0) {
            int end = existing + ";jsessionid=".length();
            while (end < path.length() && path.charAt(end) != ';' && path.charAt(end) != '/') {
                end++;
            }
            return path.substring(0, existing) + ";jsessionid=" + current.getId()
                    + path.substring(end) + url.substring(suffix);
        }
        return path + ";jsessionid=" + current.getId() + url.substring(suffix);
    }

    private boolean sameApplication(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.isOpaque()) {
                return false;
            }
            if (uri.getHost() != null) {
                int port = uri.getPort();
                int effectivePort = port >= 0 ? port
                        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                if (!getScheme().equalsIgnoreCase(uri.getScheme())
                        || !getServerName().equalsIgnoreCase(uri.getHost())
                        || getServerPort() != effectivePort) {
                    return false;
                }
            } else if (value.startsWith("//")) {
                return false;
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || contextPath.isEmpty()) {
                return true;
            }
            return path.equals(contextPath) || path.startsWith(contextPath + "/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<Locale> acceptedLocales() {
        List<String> headers = request.headers().all("accept-language");
        if (headers.isEmpty()) {
            return Collections.singletonList(Locale.getDefault());
        }
        List<LocalePreference> preferences = new ArrayList<LocalePreference>();
        int order = 0;
        for (String header : headers) {
            for (String item : header.split(",")) {
                String[] parts = item.trim().split(";");
                String tag = parts[0].trim();
                double quality = 1.0;
                for (int i = 1; i < parts.length; i++) {
                    String parameter = parts[i].trim();
                    if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                        quality = languageQuality(parameter.substring(2));
                    }
                }
                if (quality <= 0.0 || tag.isEmpty()) {
                    order++;
                    continue;
                }
                Locale locale = "*".equals(tag) ? Locale.getDefault() : Locale.forLanguageTag(tag);
                if (!locale.getLanguage().isEmpty()) {
                    preferences.add(new LocalePreference(locale, quality, order));
                }
                order++;
            }
        }
        Collections.sort(preferences, new Comparator<LocalePreference>() {
            @Override
            public int compare(LocalePreference left, LocalePreference right) {
                int quality = Double.compare(right.quality, left.quality);
                return quality != 0 ? quality : Integer.compare(left.order, right.order);
            }
        });
        List<Locale> locales = new ArrayList<Locale>(preferences.size());
        for (LocalePreference preference : preferences) {
            if (!locales.contains(preference.locale)) {
                locales.add(preference.locale);
            }
        }
        return locales.isEmpty() ? Collections.singletonList(Locale.getDefault()) : locales;
    }

    private static double languageQuality(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isNaN(parsed) || parsed < 0.0 || parsed > 1.0 ? 0.0 : parsed;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** 封装地区preference的状态与处理边界。 */
    private static final class LocalePreference {
        /** 地区。 */
        private final Locale locale;
        /** quality。 */
        /** 质量权重（quality）。 */
        private final double quality;
        /** order。 */
        /** 顺序（order）。 */
        private final int order;

        private LocalePreference(Locale locale, double quality, int order) {
            this.locale = locale;
            this.quality = quality;
            this.order = order;
        }
    }

    private void setForwardAttribute(String name, Object value) {
        if (!attributes.containsKey(name)) {
            putOrRemove(name, value);
        }
    }

    private void putOrRemove(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    private static String combineQueries(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        return first + "&" + second;
    }

    private void requireAttributeName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Servlet request attribute name must not be null");
        }
    }

    /** 封装消息体输入流的状态与处理边界。 */
    private final class BodyInputStream extends ServletInputStream {
        /** 邮箱。 */
        private final BodyMailbox mailbox;
        /** single字节。 */
        private final byte[] singleByte = new byte[1];
        /** 监听器。 */
        private ReadListener listener;
        /** 回调scheduled，布尔标志。 */
        private boolean callbackScheduled;
        /** 回调活动，布尔标志。 */
        private boolean callbackActive;
        /** alldatanotified，布尔标志。 */
        private boolean allDataNotified;
        /** failed，布尔标志。 */
        private boolean failed;
        /** consumed字节数，单位为字节。 */
        private long consumedBytes;

        private BodyInputStream(BodyMailbox mailbox) {
            this.mailbox = mailbox;
        }

        @Override
        public synchronized boolean isFinished() {
            return mailbox.isFinished();
        }

        @Override
        public synchronized boolean isReady() {
            return listener == null || (callbackActive && mailbox.isReadable());
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null) {
                throw new IllegalArgumentException("read listener must not be null");
            }
            synchronized (this) {
                if (listener != null) {
                    throw new IllegalStateException("read listener has already been set");
                }
                if (asyncContext == null || !asyncContext.isStarted()) {
                    throw new IllegalStateException("non-blocking reads require asynchronous processing");
                }
                listener = readListener;
            }
            mailbox.onReadable(new Runnable() {
                @Override
                public void run() {
                    submitCallback();
                }
            });
        }

        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : singleByte[0] & 0xff;
        }

        @Override
        public int read(byte[] values, int offset, int length) throws IOException {
            boolean nonBlocking;
            synchronized (this) {
                nonBlocking = listener != null;
                if (nonBlocking) {
                    requireReadyForRead();
                }
            }
            int count = nonBlocking
                    ? mailbox.readAvailable(values, offset, length)
                    : mailbox.readBlocking(values, offset, length);
            if (count > 0) {
                synchronized (this) {
                    consumedBytes += count;
                }
            }
            return count;
        }

        private void submitCallback() {
            synchronized (this) {
                if (failed || allDataNotified || callbackScheduled || callbackActive) {
                    return;
                }
                callbackScheduled = true;
            }
            try {
                asyncContext.executeIoCallback(new Runnable() {
                    @Override
                    public void run() {
                        notifyDataAvailable();
                    }
                });
            } catch (RuntimeException failure) {
                synchronized (this) {
                    callbackScheduled = false;
                }
                notifyError(failure);
            }
        }

        private void notifyDataAvailable() {
            long consumedBefore;
            synchronized (this) {
                callbackScheduled = false;
                if (failed || allDataNotified) {
                    return;
                }
                callbackActive = true;
                consumedBefore = consumedBytes;
            }
            try {
                Throwable bodyFailure = mailbox.failure();
                if (bodyFailure != null) {
                    notifyError(bodyFailure);
                    return;
                }
                if (mailbox.isReadable()) {
                    listener.onDataAvailable();
                }
                boolean consumed;
                synchronized (this) {
                    callbackActive = false;
                    consumed = consumedBytes > consumedBefore;
                }
                bodyFailure = mailbox.failure();
                if (bodyFailure != null) {
                    notifyError(bodyFailure);
                } else if (mailbox.isFinished()) {
                    notifyAllDataRead();
                } else if (mailbox.isReadable() && consumed) {
                    submitCallback();
                } else if (mailbox.isReadable()) {
                    notifyError(new IllegalStateException(
                            "ReadListener returned without consuming available request data"));
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    callbackActive = false;
                }
                notifyError(failure);
            }
        }

        private void notifyAllDataRead() {
            synchronized (this) {
                if (failed || allDataNotified) {
                    return;
                }
                allDataNotified = true;
            }
            try {
                listener.onAllDataRead();
            } catch (Throwable failure) {
                notifyError(failure);
            }
        }

        private void notifyError(Throwable failure) {
            synchronized (this) {
                if (failed) {
                    return;
                }
                failed = true;
                callbackScheduled = false;
                callbackActive = false;
            }
            try {
                listener.onError(failure);
            } catch (RuntimeException ignored) {
                // The original I/O failure remains the request failure.
            }
            asyncContext.failIo(failure);
        }

        private void requireReadyForRead() {
            if (!callbackActive || (!mailbox.isReadable() && !mailbox.isFinished())) {
                throw new IllegalStateException("non-blocking request input is not ready");
            }
        }
    }

    /** 封装分派状态的状态与处理边界。 */
    static final class DispatchState {
        /** 请求URI。 */
        private final String requestUri;
        /** 查询串字符串。 */
        private final String queryString;
        /** Servlet路径。 */
        private final String servletPath;
        /** 路径信息。 */
        private final String pathInfo;
        /** Servlet映射。 */
        private final HttpServletMapping servletMapping;
        /** 参数查询串字符串。 */
        private final String parameterQueryString;
        /** 按键索引的参数集合。 */
        private final Map<String, String[]> parameters;
        /** 分派器类型。 */
        private final DispatcherType dispatcherType;
        /** 按键索引的角色引用集合。 */
        private final Map<String, String> roleReferences;
        /** 包含值集合。 */
        private final Object[] includeValues;
        /** 包含present。 */
        private final boolean[] includePresent;

        private DispatchState(ServletHttpRequest request, boolean saveIncludeAttributes) {
            requestUri = request.requestUri;
            queryString = request.queryString;
            servletPath = request.servletPath;
            pathInfo = request.pathInfo;
            servletMapping = request.servletMapping;
            parameterQueryString = request.parameterQueryString;
            parameters = request.parameters;
            dispatcherType = request.dispatcherType;
            roleReferences = request.roleReferences;
            if (saveIncludeAttributes) {
                includeValues = new Object[INCLUDE_ATTRIBUTES.length];
                includePresent = new boolean[INCLUDE_ATTRIBUTES.length];
                for (int i = 0; i < INCLUDE_ATTRIBUTES.length; i++) {
                    includePresent[i] = request.attributes.containsKey(INCLUDE_ATTRIBUTES[i]);
                    includeValues[i] = request.attributes.get(INCLUDE_ATTRIBUTES[i]);
                }
            } else {
                includeValues = null;
                includePresent = null;
            }
        }
    }
}
