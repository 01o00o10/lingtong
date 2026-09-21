/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.http.HttpRequest;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ClientEndpoint;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.HandshakeRequest;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** JSR 356 API adapter; registered endpoints feed the shared handshake registry. */
/** 实现 JSR 356 ServerContainer，并把端点注册接入共同的握手注册表。 */
public final class LingTongServerContainer implements ServerContainer {
    /** Servlet上下文属性。 */
    public static final String SERVLET_CONTEXT_ATTRIBUTE =
            "javax.websocket.server.ServerContainer";
    /** 客户端连接超时时长属性。 */
    public static final String CLIENT_CONNECT_TIMEOUT_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.connectTimeoutMillis";
    /** 客户端SSL上下文属性。 */
    public static final String CLIENT_SSL_CONTEXT_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.sslContext";
    /** 客户端最大redirects属性。 */
    public static final String CLIENT_MAX_REDIRECTS_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.maxRedirects";
    /** 客户端allowcrossoriginredirect属性。 */
    public static final String CLIENT_ALLOW_CROSS_ORIGIN_REDIRECT_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.allowCrossOriginRedirect";
    /** 客户端代理属性。 */
    public static final String CLIENT_PROXY_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.proxy";
    /** 客户端代理授权属性。 */
    public static final String CLIENT_PROXY_AUTHORIZATION_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.proxyAuthorization";
    /** 客户端HTTP/2属性。 */
    public static final String CLIENT_HTTP2_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.http2";
    /** 客户端ALPN提供器属性。 */
    public static final String CLIENT_ALPN_PROVIDER_PROPERTY =
            "io.github.o1o00o10.lingtong.websocket.client.alpnProvider";

    /** 注册表。 */
    private final WebSocketEndpointRegistry registry;
    /** 上下文路径。 */
    private final String contextPath;
    /** 异步发送超时时长。 */
    private volatile long asyncSendTimeout;
    /** 空闲超时时长。 */
    private volatile long idleTimeout;
    /** 最大二进制消息大小。 */
    private volatile int maxBinaryMessageSize;
    /** 最大文本消息大小。 */
    private volatile int maxTextMessageSize;
    /** 按键索引的打开会话集合。 */
    private final Map<EndpointConfig, Set<Session>> openSessions =
            new ConcurrentHashMap<EndpointConfig, Set<Session>>();

    public LingTongServerContainer(WebSocketEndpointRegistry registry, int maxMessageBytes) {
        this(registry, maxMessageBytes, "");
    }

    public LingTongServerContainer(
            WebSocketEndpointRegistry registry, int maxMessageBytes, String contextPath) {
        if (registry == null || maxMessageBytes <= 0) {
            throw new IllegalArgumentException("registry and message limit are required");
        }
        this.registry = registry;
        this.contextPath = contextPath == null || "/".equals(contextPath) ? "" : contextPath;
        this.maxBinaryMessageSize = maxMessageBytes;
        this.maxTextMessageSize = maxMessageBytes;
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException {
        ServerEndpoint annotation = endpointClass.getAnnotation(ServerEndpoint.class);
        if (annotation == null) {
            throw new DeploymentException("endpoint class has no @ServerEndpoint: "
                    + endpointClass.getName());
        }
        ServerEndpointConfig config = ServerEndpointConfig.Builder
                .create(endpointClass, annotation.value())
                .subprotocols(Arrays.asList(annotation.subprotocols()))
                .encoders(Arrays.asList(annotation.encoders()))
                .decoders(Arrays.asList(annotation.decoders()))
                .configurator(newConfigurator(annotation.configurator()))
                .build();
        addEndpoint(config);
    }

    @Override
    public void addEndpoint(final ServerEndpointConfig config) throws DeploymentException {
        if (config == null) throw new DeploymentException("endpoint configuration is required");
        JsrEndpointValidator.validate(config);
        try {
            registry.register(contextPath + config.getPath(), (request, pathParameters) ->
                    createHandler(config, request, pathParameters));
        } catch (IllegalArgumentException e) {
            throw new DeploymentException("failed to deploy WebSocket endpoint " + config.getPath(), e);
        }
    }

    private WebSocketHandler createHandler(
            ServerEndpointConfig config,
            HttpRequest request,
            Map<String, String> pathParameters) throws Exception {
        String origin = request.headers().first("origin");
        if (!config.getConfigurator().checkOrigin(origin)) return null;
        Object endpoint = config.getConfigurator().getEndpointInstance(config.getEndpointClass());
        List<String> requestedProtocols = commaValues(
                request.headers().all("sec-websocket-protocol"));
        String selected = config.getConfigurator().getNegotiatedSubprotocol(
                config.getSubprotocols(), requestedProtocols);
        boolean compressed = !config.getConfigurator().getNegotiatedExtensions(
                Collections.singletonList(PerMessageDeflateExtension.INSTANCE),
                requestedExtensions(request)).isEmpty();
        MutableHandshakeResponse response = new MutableHandshakeResponse();
        config.getConfigurator().modifyHandshake(
                config, new RequestHandshake(request), response);
        return new JsrEndpointHandler(this, endpoint, config, request, pathParameters,
                selected, compressed,
                Math.min(maxTextMessageSize, maxBinaryMessageSize), response.getHeaders());
    }

    private static ServerEndpointConfig.Configurator newConfigurator(
            Class<? extends ServerEndpointConfig.Configurator> type) throws DeploymentException {
        if (type == ServerEndpointConfig.Configurator.class) {
            return new LingTongDefaultServerEndpointConfigurator();
        }
        try {
            return type.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new DeploymentException("cannot create endpoint configurator " + type.getName(), e);
        }
    }

    private static List<String> commaValues(List<String> values) {
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            for (String token : value.split(",")) {
                if (!token.trim().isEmpty()) result.add(token.trim());
            }
        }
        return result;
    }

    private static List<Extension> requestedExtensions(HttpRequest request) {
        for (String name : commaValues(request.headers().all("sec-websocket-extensions"))) {
            if (name.toLowerCase(java.util.Locale.ROOT).startsWith("permessage-deflate")) {
                return Collections.<Extension>singletonList(PerMessageDeflateExtension.INSTANCE);
            }
        }
        return Collections.emptyList();
    }

    @Override public long getDefaultAsyncSendTimeout() { return asyncSendTimeout; }
    @Override public void setAsyncSendTimeout(long value) { asyncSendTimeout = value; }
    @Override public long getDefaultMaxSessionIdleTimeout() { return idleTimeout; }
    @Override public void setDefaultMaxSessionIdleTimeout(long value) { idleTimeout = value; }
    @Override public int getDefaultMaxBinaryMessageBufferSize() { return maxBinaryMessageSize; }
    @Override public void setDefaultMaxBinaryMessageBufferSize(int value) {
        requirePositive(value); maxBinaryMessageSize = value;
    }
    @Override public int getDefaultMaxTextMessageBufferSize() { return maxTextMessageSize; }
    @Override public void setDefaultMaxTextMessageBufferSize(int value) {
        requirePositive(value); maxTextMessageSize = value;
    }
    @Override public Set<Extension> getInstalledExtensions() {
        return Collections.<Extension>singleton(PerMessageDeflateExtension.INSTANCE);
    }

    @Override public Session connectToServer(Object endpoint, URI path)
            throws DeploymentException, IOException {
        if (endpoint == null) throw new DeploymentException("client endpoint is required");
        return connectClient(endpoint, annotatedClientConfig(endpoint.getClass()), path);
    }
    @Override public Session connectToServer(Class<?> endpointClass, URI path)
            throws DeploymentException, IOException {
        if (endpointClass == null) throw new DeploymentException("client endpoint class is required");
        return connectClient(newEndpoint(endpointClass), annotatedClientConfig(endpointClass), path);
    }
    @Override public Session connectToServer(
            Endpoint endpoint, ClientEndpointConfig config, URI path)
            throws DeploymentException, IOException {
        return connectClient(endpoint, config, path);
    }
    @Override public Session connectToServer(
            Class<? extends Endpoint> endpointClass, ClientEndpointConfig config, URI path)
            throws DeploymentException, IOException {
        return connectClient((Endpoint) newEndpoint(endpointClass), config, path);
    }

    private Session connectClient(Object endpoint, ClientEndpointConfig config, URI path)
            throws DeploymentException, IOException {
        if (endpoint == null || config == null || path == null) {
            throw new DeploymentException("client endpoint, configuration and URI are required");
        }
        JsrEndpointValidator.validateClient(endpoint.getClass(), config);
        return WebSocketClientConnection.connect(this, endpoint, config, path);
    }

    private static Object newEndpoint(Class<?> type) throws DeploymentException {
        try {
            return type.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new DeploymentException("cannot create client endpoint " + type.getName(), e);
        }
    }

    private static ClientEndpointConfig annotatedClientConfig(Class<?> type)
            throws DeploymentException {
        ClientEndpoint annotation = type.getAnnotation(ClientEndpoint.class);
        if (annotation == null) {
            throw new DeploymentException("client endpoint has no @ClientEndpoint: "
                    + type.getName());
        }
        ClientEndpointConfig.Configurator configurator;
        try {
            configurator = annotation.configurator().newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new DeploymentException("cannot create client configurator "
                    + annotation.configurator().getName(), e);
        }
        return ClientEndpointConfig.Builder.create()
                .preferredSubprotocols(Arrays.asList(annotation.subprotocols()))
                .encoders(Arrays.asList(annotation.encoders()))
                .decoders(Arrays.asList(annotation.decoders()))
                .configurator(configurator)
                .build();
    }

    private static void requirePositive(int value) {
        if (value <= 0) throw new IllegalArgumentException("message buffer size must be positive");
    }

    /** 封装mutable握手响应的状态与处理边界。 */
    private static final class MutableHandshakeResponse implements HandshakeResponse {
        /** 按键索引的头部集合。 */
        private final Map<String, List<String>> headers =
                new java.util.LinkedHashMap<String, List<String>>();
        @Override public Map<String, List<String>> getHeaders() { return headers; }
    }

    /** 封装请求握手的状态与处理边界。 */
    private static final class RequestHandshake implements HandshakeRequest {
        /** 请求。 */
        private final HttpRequest request;
        /** 按键索引的头部集合。 */
        private final Map<String, List<String>> headers;

        private RequestHandshake(HttpRequest request) {
            this.request = request;
            Map<String, List<String>> copy = new java.util.LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> entry : request.headers().entries()) {
                copy.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<String>(entry.getValue())));
            }
            headers = Collections.unmodifiableMap(copy);
        }

        @Override public Map<String, List<String>> getHeaders() { return headers; }
        @Override public Principal getUserPrincipal() { return null; }
        @Override public URI getRequestURI() {
            io.github.o1o00o10.lingtong.http.RequestConnectionInfo info = request.connectionInfo();
            String host = request.headers().first("host");
            if (host == null) host = info.serverName() + ":" + info.serverPort();
            return URI.create((info.secure() ? "wss" : "ws") + "://" + host
                    + request.target());
        }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public Object getHttpSession() { return null; }
        @Override public Map<String, List<String>> getParameterMap() {
            String query = getQueryString();
            if (query == null || query.isEmpty()) return Collections.emptyMap();
            Map<String, List<String>> values = new java.util.LinkedHashMap<String, List<String>>();
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                values.computeIfAbsent(name, key -> new ArrayList<String>()).add(value);
            }
            return values;
        }
        @Override public String getQueryString() { return getRequestURI().getRawQuery(); }
    }

    void opened(JsrSession session) {
        openSessions.computeIfAbsent(session.endpointConfig(), key ->
                Collections.newSetFromMap(new ConcurrentHashMap<Session, Boolean>()))
                .add(session);
    }

    void closed(JsrSession session) {
        Set<Session> sessions = openSessions.get(session.endpointConfig());
        if (sessions == null) return;
        sessions.remove(session);
        if (sessions.isEmpty()) openSessions.remove(session.endpointConfig(), sessions);
    }

    Set<Session> openSessions(EndpointConfig endpointConfig) {
        Set<Session> sessions = openSessions.get(endpointConfig);
        return sessions == null
                ? Collections.<Session>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<Session>(sessions));
    }
}
