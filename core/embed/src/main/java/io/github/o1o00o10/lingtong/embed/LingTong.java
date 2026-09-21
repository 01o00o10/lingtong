/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.embed;

import io.github.o1o00o10.lingtong.api.LingTongRuntime;
import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.deployment.WebAppDeployment;
import io.github.o1o00o10.lingtong.deployment.WebAppDeploymentException;
import io.github.o1o00o10.lingtong.kernel.DefaultLingTongRuntime;
import io.github.o1o00o10.lingtong.servlet.ServletApplication;
import io.github.o1o00o10.lingtong.servlet.ServletSecurityPolicy;
import io.github.o1o00o10.lingtong.servlet.ServletSecurityRealm;
import io.github.o1o00o10.lingtong.servlet.ServletClientCertificateRealm;
import io.github.o1o00o10.lingtong.servlet.SessionStore;
import io.github.o1o00o10.lingtong.servlet.FileSessionStore;
import io.github.o1o00o10.lingtong.servlet.JdbcSessionStore;
import io.github.o1o00o10.lingtong.kernel.ManagementConfig;
import io.github.o1o00o10.lingtong.kernel.ManagementAuditSink;
import io.github.o1o00o10.lingtong.kernel.FileManagementAuditSink;
import io.github.o1o00o10.lingtong.transport.TransportConfig;
import io.github.o1o00o10.lingtong.transport.CompressionConfig;
import io.github.o1o00o10.lingtong.transport.ProxyConfig;
import io.github.o1o00o10.lingtong.transport.TlsConfig;
import io.github.o1o00o10.lingtong.transport.AlpnProvider;
import io.github.o1o00o10.lingtong.websocket.LingTongServerContainer;
import io.github.o1o00o10.lingtong.websocket.LingTongWebSocketInitializer;
import io.github.o1o00o10.lingtong.websocket.WebSocketEndpointRegistry;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpoint;
import javax.net.ssl.SSLContext;
import java.util.Collections;
import java.util.EventListener;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import javax.sql.DataSource;
import java.io.IOException;

/** Public composition entry point for embedded and WAR-backed runtimes. */
/** 嵌入式入口：组合传输、Servlet、WebSocket、部署与运行时生命周期。 */
public final class LingTong {
    private LingTong() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects transport settings and Servlet registrations before creating one runtime. */
    /** 封装构建器的状态与处理边界。 */
    public static final class Builder {
        /** 绑定地址。 */
        private String bindAddress = "127.0.0.1";
        /** 端口。 */
        private int port = 8080;
        /** ioshards。 */
        /** I/O 状态分片集合（ioShards）。 */
        private int ioShards = defaultIoShards();
        /** 读取缓冲区大小。 */
        private int readBufferSize = 16 * 1024;
        /** 缓冲区集合逐shard。 */
        private int buffersPerShard = 1024;
        /** command队列容量。 */
        private int commandQueueCapacity = 4096;
        /** 最大头部字节数，单位为字节。 */
        private int maxHeaderBytes = 32 * 1024;
        /** 最大头部数量。 */
        private int maxHeaderCount = 100;
        /** 最大消息体字节数，单位为字节。 */
        private int maxBodyBytes = 8 * 1024 * 1024;
        /** 最大尾部头字节数，单位为字节。 */
        private int maxTrailerBytes = 8 * 1024;
        /** 最大尾部头数量。 */
        private int maxTrailerCount = 100;
        /** 请求消息体缓冲区字节数，单位为字节。 */
        private int requestBodyBufferBytes = 64 * 1024;
        /** 请求消息体低水位字节数，单位为字节。 */
        private int requestBodyLowWaterBytes = 32 * 1024;
        /** 请求头部超时时长毫秒，单位为毫秒。 */
        private long requestHeaderTimeoutMillis = 10_000L;
        /** 空闲超时时长毫秒，单位为毫秒。 */
        private long idleTimeoutMillis = 30_000L;
        /** 最大请求集合逐连接。 */
        private int maxRequestsPerConnection = 1_000;
        /** 压缩启用标志，布尔标志。 */
        private boolean compressionEnabled;
        /** 压缩最小响应字节数，单位为字节。 */
        private long compressionMinResponseBytes = 2048L;
        /** 按顺序保存的压缩MIME类型集合。 */
        private List<String> compressionMimeTypes = new ArrayList<String>(Arrays.asList(
                "text/html", "text/xml", "text/plain", "text/css", "text/javascript",
                "application/javascript", "application/json", "application/xml"));
        /** 按顺序保存的压缩已排除的用户agents。 */
        private List<String> compressionExcludedUserAgents = new ArrayList<String>();
        /** 按顺序保存的受信任的代理集合。 */
        private List<String> trustedProxies = new ArrayList<String>();
        /** SSL上下文。 */
        private SSLContext sslContext;
        /** TLS客户端认证。 */
        private TlsConfig.ClientAuth tlsClientAuth = TlsConfig.ClientAuth.NONE;
        /** 按顺序保存的TLSprotocols。 */
        private List<String> tlsProtocols = new ArrayList<String>();
        /** 按顺序保存的TLSciphersuites。 */
        private List<String> tlsCipherSuites = new ArrayList<String>();
        /** TLS握手超时时长毫秒，单位为毫秒。 */
        private long tlsHandshakeTimeoutMillis = 10_000L;
        /** 最大TLS缓冲区字节数，单位为字节。 */
        private int maxTlsBufferBytes = 256 * 1024;
        /** ALPN提供器。 */
        private AlpnProvider alpnProvider = AlpnProvider.jdk();
        /** HTTP/2启用标志，布尔标志。 */
        private boolean http2Enabled;
        /** 工作线程线程集合。 */
        private int workerThreads = defaultWorkerThreads();
        /** 工作线程队列容量。 */
        private int workerQueueCapacity = 8192;
        /** 最大会话集合。 */
        private int maxSessions = 10000;
        /** 会话超时时长秒，单位为秒。 */
        private int sessionTimeoutSeconds = 30 * 60;
        /** 会话存储后端。 */
        private SessionStore sessionStore;
        /** persistent会话目录。 */
        private Path persistentSessionDirectory;
        /** 管理令牌。 */
        private String managementToken;
        /** 节点标识符。 */
        private String nodeId;
        /** 按顺序保存的集群对端集合。 */
        private List<String> clusterPeers = new ArrayList<String>();
        /** 集群心跳毫秒，单位为毫秒。 */
        private long clusterHeartbeatMillis = 5_000L;
        /** 集群失败阈值。 */
        private int clusterFailureThreshold = 3;
        /** 管理绑定地址。 */
        private String managementBindAddress = "127.0.0.1";
        /** 管理端口。 */
        private int managementPort = -1;
        /** 管理操作者令牌。 */
        private String managementOperatorToken;
        /** 管理审计输出端。 */
        private ManagementAuditSink managementAuditSink;
        /** 管理审计文件。 */
        private Path managementAuditFile;
        /** 管理审计最大字节数，单位为字节。 */
        private long managementAuditMaxBytes = FileManagementAuditSink.DEFAULT_MAX_BYTES;
        /** 管理审计保留的文件列表。 */
        private int managementAuditRetainedFiles = FileManagementAuditSink.DEFAULT_RETAINED_FILES;
        /** 最大参数数量。 */
        private int maxParameterCount = 1_000;
        /** 最大参数字节数，单位为字节。 */
        private int maxParameterBytes = 1024 * 1024;
        /** 最大Cookie数量。 */
        private int maxCookieCount = 200;
        /** 最大Cookie字节数，单位为字节。 */
        private int maxCookieBytes = 8 * 1024;
        /** 上下文路径。 */
        private String contextPath = "";
        /** Web应用。 */
        private Path webApplication;
        /** 最大部署entries。 */
        private int maxDeploymentEntries = WebAppDeployment.DEFAULT_MAX_ARCHIVE_ENTRIES;
        /** 最大部署字节数，单位为字节。 */
        private long maxDeploymentBytes = WebAppDeployment.DEFAULT_MAX_EXPANDED_BYTES;
        /** 资源根目录configured，布尔标志。 */
        private boolean resourceRootConfigured;
        /** Websockets。 */
        /** Web套接字集合（webSockets）。 */
        private final WebSocketEndpointRegistry webSockets = new WebSocketEndpointRegistry();
        /** 最大Web套接字消息字节数，单位为字节。 */
        private int maxWebSocketMessageBytes = 8 * 1024 * 1024;
        /** 应用。 */
        private final ServletApplication.Builder application = ServletApplication.builder();
        /** built，布尔标志。 */
        private boolean built;

        public Builder port(int value) {
            requireNotBuilt();
            port = value;
            return this;
        }

        public Builder bindAddress(String value) {
            requireNotBuilt();
            bindAddress = value;
            return this;
        }

        public Builder ioShards(int value) {
            requireNotBuilt();
            ioShards = value;
            return this;
        }

        public Builder readBufferSize(int value) {
            requireNotBuilt();
            readBufferSize = value;
            return this;
        }

        public Builder buffersPerShard(int value) {
            requireNotBuilt();
            buffersPerShard = value;
            return this;
        }

        public Builder commandQueueCapacity(int value) {
            requireNotBuilt();
            commandQueueCapacity = value;
            return this;
        }

        public Builder maxHeaderBytes(int value) {
            requireNotBuilt();
            maxHeaderBytes = value;
            return this;
        }

        public Builder maxHeaderCount(int value) {
            requireNotBuilt();
            maxHeaderCount = value;
            return this;
        }

        public Builder maxBodyBytes(int value) {
            requireNotBuilt();
            maxBodyBytes = value;
            return this;
        }

        public Builder maxTrailerBytes(int value) {
            requireNotBuilt();
            maxTrailerBytes = value;
            return this;
        }

        public Builder maxTrailerCount(int value) {
            requireNotBuilt();
            maxTrailerCount = value;
            return this;
        }

        public Builder requestBodyBufferBytes(int value) {
            requireNotBuilt();
            requestBodyBufferBytes = value;
            return this;
        }

        public Builder requestBodyLowWaterBytes(int value) {
            requireNotBuilt();
            requestBodyLowWaterBytes = value;
            return this;
        }

        public Builder requestHeaderTimeoutMillis(long value) {
            requireNotBuilt();
            requestHeaderTimeoutMillis = value;
            return this;
        }

        public Builder idleTimeoutMillis(long value) {
            requireNotBuilt();
            idleTimeoutMillis = value;
            return this;
        }

        public Builder maxRequestsPerConnection(int value) {
            requireNotBuilt();
            maxRequestsPerConnection = value;
            return this;
        }

        public Builder compressionEnabled(boolean value) {
            requireNotBuilt();
            compressionEnabled = value;
            return this;
        }

        public Builder compressionMinResponseBytes(long value) {
            requireNotBuilt();
            compressionMinResponseBytes = value;
            return this;
        }

        public Builder compressionMimeTypes(String... values) {
            requireNotBuilt();
            if (values == null) {
                throw new IllegalArgumentException("compression MIME types must not be null");
            }
            compressionMimeTypes = new ArrayList<String>(Arrays.asList(values));
            return this;
        }

        public Builder compressionExcludedUserAgents(String... values) {
            requireNotBuilt();
            if (values == null) {
                throw new IllegalArgumentException("excluded user agents must not be null");
            }
            compressionExcludedUserAgents = new ArrayList<String>(Arrays.asList(values));
            return this;
        }

        public Builder trustedProxies(String... values) {
            requireNotBuilt();
            if (values == null) {
                throw new IllegalArgumentException("trusted proxies must not be null");
            }
            trustedProxies = new ArrayList<String>(Arrays.asList(values));
            return this;
        }

        public Builder tls(SSLContext value) {
            requireNotBuilt();
            if (value == null) {
                throw new IllegalArgumentException("SSLContext must not be null");
            }
            sslContext = value;
            return this;
        }

        public Builder tlsClientAuth(TlsConfig.ClientAuth value) {
            requireNotBuilt();
            if (value == null) {
                throw new IllegalArgumentException("TLS client authentication must not be null");
            }
            tlsClientAuth = value;
            return this;
        }

        public Builder tlsProtocols(String... values) {
            requireNotBuilt();
            if (values == null) {
                throw new IllegalArgumentException("TLS protocols must not be null");
            }
            tlsProtocols = new ArrayList<String>(Arrays.asList(values));
            return this;
        }

        public Builder tlsCipherSuites(String... values) {
            requireNotBuilt();
            if (values == null) {
                throw new IllegalArgumentException("TLS cipher suites must not be null");
            }
            tlsCipherSuites = new ArrayList<String>(Arrays.asList(values));
            return this;
        }

        public Builder tlsHandshakeTimeoutMillis(long value) {
            requireNotBuilt();
            tlsHandshakeTimeoutMillis = value;
            return this;
        }

        public Builder maxTlsBufferBytes(int value) {
            requireNotBuilt();
            maxTlsBufferBytes = value;
            return this;
        }

        public Builder alpnProvider(AlpnProvider value) {
            requireNotBuilt();
            if (value == null) throw new IllegalArgumentException("ALPN provider must not be null");
            alpnProvider = value;
            return this;
        }

        public Builder http2Enabled(boolean value) {
            requireNotBuilt();
            http2Enabled = value;
            return this;
        }

        public Builder workerThreads(int value) {
            requireNotBuilt();
            workerThreads = value;
            return this;
        }

        public Builder workerQueueCapacity(int value) {
            requireNotBuilt();
            workerQueueCapacity = value;
            return this;
        }

        public Builder maxSessions(int value) {
            requireNotBuilt();
            maxSessions = value;
            return this;
        }

        public Builder sessionTimeoutSeconds(int value) {
            requireNotBuilt();
            sessionTimeoutSeconds = value;
            return this;
        }

        public Builder sessionCookieSameSite(String value) {
            requireNotBuilt();
            application.sessionCookieSameSite(value);
            return this;
        }

        public Builder sessionStore(SessionStore value) {
            requireNotBuilt();
            if (value == null) throw new IllegalArgumentException("session store must not be null");
            sessionStore = value;
            persistentSessionDirectory = null;
            return this;
        }

        public Builder persistentSessions(Path directory) {
            requireNotBuilt();
            if (directory == null) throw new IllegalArgumentException("session directory is required");
            persistentSessionDirectory = directory;
            sessionStore = null;
            return this;
        }

        public Builder distributedSessions(DataSource dataSource) {
            return sessionStore(new JdbcSessionStore(dataSource));
        }

        public Builder distributedSessions(DataSource dataSource, String table) {
            return sessionStore(new JdbcSessionStore(dataSource, table));
        }

        public Builder management(String token) {
            requireNotBuilt();
            managementToken = token;
            return this;
        }

        public Builder managementBindAddress(String value) {
            requireNotBuilt();
            managementBindAddress = value;
            return this;
        }

        public Builder managementPort(int value) {
            requireNotBuilt();
            managementPort = value;
            return this;
        }

        public Builder managementOperatorToken(String value) {
            requireNotBuilt();
            managementOperatorToken = value;
            return this;
        }

        public Builder managementAudit(Path file) {
            return managementAudit(file, FileManagementAuditSink.DEFAULT_MAX_BYTES,
                    FileManagementAuditSink.DEFAULT_RETAINED_FILES);
        }

        public Builder managementAudit(Path file, long maxBytes, int retainedFiles) {
            requireNotBuilt();
            if (file == null) throw new IllegalArgumentException("management audit file is required");
            managementAuditFile = file;
            managementAuditMaxBytes = maxBytes;
            managementAuditRetainedFiles = retainedFiles;
            managementAuditSink = null;
            return this;
        }

        public Builder managementAuditSink(ManagementAuditSink value) {
            requireNotBuilt();
            if (value == null) throw new IllegalArgumentException("management audit sink is required");
            managementAuditSink = value;
            managementAuditFile = null;
            return this;
        }

        public Builder nodeId(String value) {
            requireNotBuilt();
            nodeId = value;
            return this;
        }

        public Builder clusterPeers(String... values) {
            requireNotBuilt();
            if (values == null) throw new IllegalArgumentException("cluster peers must not be null");
            clusterPeers = new ArrayList<String>(Arrays.asList(values));
            return this;
        }

        public Builder clusterHeartbeatMillis(long value) {
            requireNotBuilt();
            clusterHeartbeatMillis = value;
            return this;
        }

        public Builder clusterFailureThreshold(int value) {
            requireNotBuilt();
            clusterFailureThreshold = value;
            return this;
        }

        public Builder requestCharacterEncoding(String value) {
            requireNotBuilt();
            application.requestCharacterEncoding(value);
            return this;
        }

        public Builder responseCharacterEncoding(String value) {
            requireNotBuilt();
            application.responseCharacterEncoding(value);
            return this;
        }

        public Builder maxParameterCount(int value) {
            requireNotBuilt();
            maxParameterCount = value;
            return this;
        }

        public Builder maxParameterBytes(int value) {
            requireNotBuilt();
            maxParameterBytes = value;
            return this;
        }

        public Builder maxCookieCount(int value) {
            requireNotBuilt();
            maxCookieCount = value;
            return this;
        }

        public Builder maxCookieBytes(int value) {
            requireNotBuilt();
            maxCookieBytes = value;
            return this;
        }

        public Builder contextPath(String value) {
            requireNotBuilt();
            application.contextPath(value);
            contextPath = value == null || "/".equals(value) ? "" : value;
            return this;
        }

        public Builder displayName(String value) {
            requireNotBuilt();
            application.displayName(value);
            return this;
        }

        public Builder resourceRoot(Path value) {
            requireNotBuilt();
            application.resourceRoot(value);
            resourceRootConfigured = true;
            return this;
        }

        public Builder defaultServletEnabled(boolean value) {
            requireNotBuilt();
            application.defaultServletEnabled(value);
            return this;
        }

        public Builder mimeMappings(Map<String, String> values) {
            requireNotBuilt();
            application.mimeMappings(values);
            return this;
        }

        public Builder localeEncodingMappings(Map<String, String> values) {
            requireNotBuilt();
            application.localeEncodingMappings(values);
            return this;
        }

        public Builder securityPolicy(ServletSecurityPolicy value) {
            requireNotBuilt();
            application.securityPolicy(value);
            return this;
        }

        public Builder securityRealm(ServletSecurityRealm value) {
            requireNotBuilt();
            application.securityRealm(value);
            return this;
        }

        public Builder clientCertificateRealm(ServletClientCertificateRealm value) {
            requireNotBuilt();
            application.clientCertificateRealm(value);
            return this;
        }

        public Builder welcomeFiles(List<String> values) {
            requireNotBuilt();
            application.welcomeFiles(values);
            return this;
        }

        /** Selects a WAR or expanded web root; metadata is loaded by {@link #build()}. */
        public Builder webApplication(Path value) {
            requireNotBuilt();
            if (value == null) {
                throw new IllegalArgumentException("web application path must not be null");
            }
            webApplication = value;
            return this;
        }

        public Builder maxDeploymentEntries(int value) {
            requireNotBuilt();
            if (value <= 0) {
                throw new IllegalArgumentException("max deployment entries must be positive");
            }
            maxDeploymentEntries = value;
            return this;
        }

        public Builder maxDeploymentBytes(long value) {
            requireNotBuilt();
            if (value <= 0L) {
                throw new IllegalArgumentException("max deployment bytes must be positive");
            }
            maxDeploymentBytes = value;
            return this;
        }

        public Builder maxResourceBytes(long value) {
            requireNotBuilt();
            application.maxResourceBytes(value);
            return this;
        }

        public Builder maxResponseBytes(long value) {
            requireNotBuilt();
            application.maxResponseBytes(value);
            return this;
        }

        public Builder responseBodyBufferBytes(int value) {
            requireNotBuilt();
            application.responseBodyBufferBytes(value);
            return this;
        }

        public Builder responseBodyLowWaterBytes(int value) {
            requireNotBuilt();
            application.responseBodyLowWaterBytes(value);
            return this;
        }

        public Builder shutdownDrainTimeoutMillis(long value) {
            requireNotBuilt();
            application.shutdownDrainTimeoutMillis(value);
            return this;
        }

        public Builder servlet(String name, String pattern, Servlet servlet) {
            requireNotBuilt();
            application.servlet(name, pattern, servlet);
            return this;
        }

        public Builder servlet(String name, String pattern, Servlet servlet, boolean asyncSupported) {
            requireNotBuilt();
            application.servlet(name, pattern, servlet, asyncSupported);
            return this;
        }

        public Builder filter(String name, String pattern, Filter filter) {
            requireNotBuilt();
            application.filter(name, pattern, filter);
            return this;
        }

        public Builder filter(String name, String pattern, Filter filter, boolean asyncSupported) {
            requireNotBuilt();
            application.filter(name, pattern, filter, asyncSupported);
            return this;
        }

        public Builder listener(EventListener listener) {
            requireNotBuilt();
            application.listener(listener);
            return this;
        }

        public Builder errorPage(int statusCode, String path) {
            requireNotBuilt();
            application.errorPage(statusCode, path);
            return this;
        }

        public Builder errorPage(Class<? extends Throwable> exceptionType, String path) {
            requireNotBuilt();
            application.errorPage(exceptionType, path);
            return this;
        }

        public Builder errorPage(String path) {
            requireNotBuilt();
            application.errorPage(path);
            return this;
        }

        public Builder initializer(ServletContainerInitializer initializer) {
            return initializer(initializer, Collections.<Class<?>>emptySet());
        }

        public Builder initializer(ServletContainerInitializer initializer, Set<Class<?>> handledTypes) {
            requireNotBuilt();
            application.initializer(initializer, handledTypes);
            return this;
        }

        public Builder webSocket(String path, WebSocketHandler handler) {
            requireNotBuilt();
            if (path == null || !path.startsWith("/") || path.indexOf('?') >= 0 || handler == null) {
                throw new IllegalArgumentException("WebSocket path and handler are invalid");
            }
            webSockets.register(path, (request, parameters) -> handler);
            return this;
        }

        public Builder maxWebSocketMessageBytes(int value) {
            requireNotBuilt();
            if (value <= 0) throw new IllegalArgumentException("WebSocket message limit must be positive");
            maxWebSocketMessageBytes = value;
            return this;
        }

        /**
         * Assembles the deployment, Servlet plan, protocol endpoints, and kernel without
         * opening a listener. The caller owns the returned runtime and must close it.
         */
        /** 固化 Builder 配置，装配应用与传输组件；此时尚不监听端口。 */
        public LingTongRuntime build() {
            requireNotBuilt();
            TransportConfig transport = new TransportConfig(
                    bindAddress,
                    port,
                    ioShards,
                    readBufferSize,
                    buffersPerShard,
                    commandQueueCapacity,
                    maxHeaderBytes,
                    maxBodyBytes,
                    requestBodyBufferBytes,
                    requestBodyLowWaterBytes,
                    requestHeaderTimeoutMillis,
                    idleTimeoutMillis,
                    maxRequestsPerConnection,
                    new CompressionConfig(
                            compressionEnabled,
                            compressionMinResponseBytes,
                            compressionMimeTypes,
                            compressionExcludedUserAgents),
                    new ProxyConfig(trustedProxies),
                    maxHeaderCount,
                    maxTrailerBytes,
                    maxTrailerCount,
                    sslContext == null ? TlsConfig.disabled() : new TlsConfig(
                            sslContext,
                            tlsClientAuth,
                            tlsProtocols,
                            tlsCipherSuites,
                            tlsHandshakeTimeoutMillis,
                            maxTlsBufferBytes,
                            alpnProvider),
                    http2Enabled);
            WebAppDeployment deployment = null;
            try {
                if (webApplication != null) {
                    if (resourceRootConfigured) {
                        throw new IllegalStateException(
                                "resourceRoot and webApplication cannot be configured together");
                    }
                    deployment = WebAppDeployment.open(
                            webApplication, maxDeploymentEntries, maxDeploymentBytes);
                    deployment.applyTo(application);
                }
                application.sessions(maxSessions, sessionTimeoutSeconds);
                if (persistentSessionDirectory != null) {
                    try {
                        sessionStore = new FileSessionStore(persistentSessionDirectory);
                    } catch (IOException e) {
                        throw new IllegalStateException("failed to open persistent session directory", e);
                    }
                }
                if (sessionStore != null) application.sessionStore(sessionStore);
                application.maxParameterCount(maxParameterCount);
                application.maxParameterBytes(maxParameterBytes);
                application.maxCookieCount(maxCookieCount);
                application.maxCookieBytes(maxCookieBytes);
                LingTongServerContainer webSocketContainer =
                        new LingTongServerContainer(
                                webSockets, maxWebSocketMessageBytes, contextPath);
                if (deployment != null) {
                    application.initializer(new LingTongWebSocketInitializer(),
                            deployment.matchingClasses(
                                    ServerEndpoint.class,
                                    ServerApplicationConfig.class,
                                    Endpoint.class));
                }
                // WebSocket endpoints must be visible while Servlet initializers run.
                application.initializerFirst((classes, context) -> context.setAttribute(
                        LingTongServerContainer.SERVLET_CONTEXT_ATTRIBUTE,
                        webSocketContainer));
                application.initializerFirst((classes, context) -> context.setAttribute(
                        WebSocketEndpointRegistry.class.getName(), webSockets));
                ServletApplication servletApplication = application.build();
                if (managementToken == null
                        && (managementOperatorToken != null || managementAuditSink != null
                        || managementAuditFile != null || managementPort >= 0)) {
                    throw new IllegalStateException(
                            "management token is required for management security configuration");
                }
                ManagementAuditSink effectiveAuditSink = managementAuditSink;
                if (managementAuditFile != null) {
                    try {
                        effectiveAuditSink = new FileManagementAuditSink(
                                managementAuditFile, managementAuditMaxBytes,
                                managementAuditRetainedFiles);
                    } catch (IOException e) {
                        throw new IllegalStateException("failed to open management audit file", e);
                    }
                }
                DefaultLingTongRuntime runtime = new DefaultLingTongRuntime(
                        transport,
                        workerThreads,
                        workerQueueCapacity,
                        servletApplication,
                        webSockets,
                        maxWebSocketMessageBytes,
                        managementToken == null ? null : new ManagementConfig(
                                managementToken,
                                nodeId == null ? "node-" + java.util.UUID.randomUUID().toString() : nodeId,
                                clusterPeers,
                                clusterHeartbeatMillis,
                                clusterFailureThreshold,
                                managementBindAddress,
                                managementPort,
                                managementOperatorToken,
                                effectiveAuditSink));
                built = true;
                deployment = null;
                return runtime;
            } catch (WebAppDeploymentException e) {
                throw new IllegalStateException("failed to prepare web application", e);
            } finally {
                if (deployment != null) {
                    deployment.close();
                }
            }
        }

        private void requireNotBuilt() {
            if (built) {
                throw new IllegalStateException("LingTong Builder has already built a runtime");
            }
        }

        private static int defaultIoShards() {
            return Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 16));
        }

        private static int defaultWorkerThreads() {
            int processors = Runtime.getRuntime().availableProcessors();
            return Math.max(32, Math.min(processors * 8, 256));
        }
    }
}
