/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import io.github.o1o00o10.lingtong.api.LingTongException;
import io.github.o1o00o10.lingtong.api.LingTongRuntime;
import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.execution.BoundedExecutionDomain;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.RequestTrace;
import io.github.o1o00o10.lingtong.http.HttpResponse;
import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.ResponseBodyMailbox;
import io.github.o1o00o10.lingtong.http.StreamingHttpResponse;
import io.github.o1o00o10.lingtong.servlet.ServletApplication;
import io.github.o1o00o10.lingtong.transport.StreamingResponseConsumer;
import io.github.o1o00o10.lingtong.transport.StreamingResponseProcessor;
import io.github.o1o00o10.lingtong.transport.CompressionConfig;
import io.github.o1o00o10.lingtong.transport.ProxyConfig;
import io.github.o1o00o10.lingtong.transport.TlsConfig;
import io.github.o1o00o10.lingtong.transport.TransportConfig;
import io.github.o1o00o10.lingtong.transport.nio.NioHttpServer;
import io.github.o1o00o10.lingtong.websocket.WebSocketHandshake;
import io.github.o1o00o10.lingtong.websocket.WebSocketEndpointRegistry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.management.ManagementFactory;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 连接传输层与 Servlet 应用的运行时。阅读路径：prepare/start -> KernelProcessor
 * -> ServletApplication；停止则按 beginDrain -> stop/close 反向释放。
 * 本层只决定保留路由、WebSocket 握手和指标归属，不解析 HTTP 报文。
 */
public final class DefaultLingTongRuntime implements LingTongRuntime {
    /** 启停及应用准备阶段的诊断日志。 */
    private static final Logger LOGGER = Logger.getLogger(DefaultLingTongRuntime.class.getName());
    /** 管理接口的未授权、只读和操作员三级身份。 */
    private enum ManagementRole {
        /** 无管理权限。 */
        NONE,
        /** 可读取诊断数据，但不能执行管理操作。 */
        READER,
        /** 可执行受保护的管理操作。 */
        OPERATOR
    }
    /** 健康状态。 */
    private static final HttpResponse HEALTH = HttpResponse.text(200, "OK", "UP\n");
    /** 保留路由未匹配时返回的 404。 */
    private static final HttpResponse NOT_FOUND = HttpResponse.text(404, "Not Found", "Not Found\n");
    /** 服务端保留路由的 OPTIONS 空响应。 */
    private static final HttpResponse SERVER_OPTIONS = new HttpResponse(
            204,
            "No Content",
            HttpHeaders.builder().add("allow", "OPTIONS").build(),
            new byte[0]);

    /** 串行化启动、停止和关闭的监视器。 */
    private final Object lifecycleMonitor = new Object();
    /** 传输配置。 */
    private final TransportConfig transportConfig;
    /** 工作线程线程集合。 */
    private final int workerThreads;
    /** 工作线程队列容量。 */
    private final int workerQueueCapacity;
    /** 应用。 */
    private final ServletApplication application;
    /** 普通 Upgrade 与 HTTP/2 扩展 CONNECT 共用的 WebSocket 端点注册表。 */
    private final WebSocketEndpointRegistry webSockets;
    /** 最大Web套接字消息字节数，单位为字节。 */
    private final int maxWebSocketMessageBytes;
    /** 管理配置。 */
    private final ManagementConfig managementConfig;
    /** 指标。 */
    private final RuntimeMetrics metrics = new RuntimeMetrics();
    /** 集群监控器。 */
    private final ClusterMonitor clusterMonitor;
    /** 管理操作审计记录及其持久化边界。 */
    private final ManagementAuditLog auditLog;

    /** 运行中标志，布尔标志。 */
    private volatile boolean running;
    /** 服务端。 */
    private volatile NioHttpServer server;
    /** 管理服务端。 */
    private volatile NioHttpServer managementServer;
    /** 执行。 */
    private BoundedExecutionDomain execution;
    /** 管理执行。 */
    private BoundedExecutionDomain managementExecution;
    /** mbean名称。 */
    private ObjectName mbeanName;

    public DefaultLingTongRuntime(
            TransportConfig transportConfig,
            int workerThreads,
            int workerQueueCapacity) {
        this(transportConfig, workerThreads, workerQueueCapacity,
                ServletApplication.builder().build(), Collections.<String, WebSocketHandler>emptyMap(),
                8 * 1024 * 1024);
    }

    public DefaultLingTongRuntime(
            TransportConfig transportConfig,
            int workerThreads,
            int workerQueueCapacity,
            ServletApplication application) {
        this(transportConfig, workerThreads, workerQueueCapacity, application,
                Collections.<String, WebSocketHandler>emptyMap(), 8 * 1024 * 1024);
    }

    public DefaultLingTongRuntime(
            TransportConfig transportConfig,
            int workerThreads,
            int workerQueueCapacity,
            ServletApplication application,
            Map<String, WebSocketHandler> webSockets,
            int maxWebSocketMessageBytes) {
        this(transportConfig, workerThreads, workerQueueCapacity, application,
                registry(webSockets), maxWebSocketMessageBytes);
    }

    public DefaultLingTongRuntime(
            TransportConfig transportConfig,
            int workerThreads,
            int workerQueueCapacity,
            ServletApplication application,
            WebSocketEndpointRegistry webSockets,
            int maxWebSocketMessageBytes) {
        this(transportConfig, workerThreads, workerQueueCapacity, application,
                webSockets, maxWebSocketMessageBytes, null);
    }

    public DefaultLingTongRuntime(
            TransportConfig transportConfig,
            int workerThreads,
            int workerQueueCapacity,
            ServletApplication application,
            WebSocketEndpointRegistry webSockets,
            int maxWebSocketMessageBytes,
            ManagementConfig managementConfig) {
        if (transportConfig == null) {
            throw new IllegalArgumentException("transportConfig must not be null");
        }
        if (application == null || webSockets == null || maxWebSocketMessageBytes <= 0) {
            throw new IllegalArgumentException("application must not be null");
        }
        if (workerThreads <= 0 || workerQueueCapacity <= 0) {
            throw new IllegalArgumentException("worker limits must be positive");
        }
        if (application.requiresClientCertificateAuthentication()
                && (!transportConfig.tls().enabled()
                || transportConfig.tls().clientAuth() == TlsConfig.ClientAuth.NONE)) {
            throw new IllegalArgumentException(
                    "CLIENT-CERT authentication requires TLS client authentication");
        }
        this.transportConfig = transportConfig;
        this.workerThreads = workerThreads;
        this.workerQueueCapacity = workerQueueCapacity;
        this.application = application;
        this.webSockets = webSockets;
        this.maxWebSocketMessageBytes = maxWebSocketMessageBytes;
        this.managementConfig = managementConfig;
        this.clusterMonitor = managementConfig == null ? null : new ClusterMonitor(managementConfig);
        this.auditLog = new ManagementAuditLog(
                256, managementConfig == null ? null : managementConfig.auditSink());
    }

    @Override
    /** 在监听端口前完成应用注册，供 Boot 刷新阶段安装 Servlet/Filter。 */
    public void prepare() throws LingTongException {
        synchronized (lifecycleMonitor) {
            try {
                application.prepare();
            } catch (Exception | LinkageError e) {
                LOGGER.log(Level.SEVERE, "runtime preparation failed type="
                        + e.getClass().getName());
                throw new LingTongException("failed to prepare LingTong runtime", e);
            }
        }
    }

    @Override
    /** 先激活应用，再启动业务与可选管理监听器；失败则释放已创建资源。 */
    public void start() throws LingTongException {
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }

            BoundedExecutionDomain nextExecution =
                    new BoundedExecutionDomain(workerThreads, workerQueueCapacity, "lingtong-worker");
            NioHttpServer nextServer = new NioHttpServer(transportConfig, new KernelProcessor(), nextExecution);
            BoundedExecutionDomain nextManagementExecution = null;
            NioHttpServer nextManagementServer = null;
            if (managementConfig != null && managementConfig.dedicatedListener()) {
                nextManagementExecution = new BoundedExecutionDomain(
                        2, 256, "lingtong-management-worker");
                nextManagementServer = new NioHttpServer(
                        managementTransportConfig(), request -> {
                            HttpResponse response = managementResponse(request, true);
                            return response == null ? NOT_FOUND : response;
                        }, nextManagementExecution);
            }
            try {
                application.asyncExecutor(new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        if (!nextExecution.execute(command)) {
                            throw new RejectedExecutionException("LingTong application execution queue is full");
                        }
                    }
                });
                // Servlet 初始化必须先于端口接入，避免首个请求撞上未完成的组件注册。
                application.start();
                nextServer.start();
                execution = nextExecution;
                server = nextServer;
                running = true;
                if (nextManagementServer != null) {
                    managementExecution = nextManagementExecution;
                    managementServer = nextManagementServer;
                    nextManagementServer.start();
                }
                registerMBean();
                if (clusterMonitor != null) clusterMonitor.start();
                LOGGER.info("runtime started port=" + port()
                        + " managementPort=" + managementPort());
            } catch (Exception | LinkageError e) {
                LOGGER.log(Level.SEVERE, "runtime startup failed type="
                        + e.getClass().getName());
                running = false;
                if (clusterMonitor != null) clusterMonitor.close();
                unregisterMBean();
                server = null;
                execution = null;
                managementServer = null;
                managementExecution = null;
                if (nextManagementServer != null) nextManagementServer.close();
                if (nextManagementExecution != null) nextManagementExecution.close();
                nextServer.close();
                nextExecution.close();
                application.close();
                throw new LingTongException("failed to start LingTong runtime", e);
            }
        }
    }

    @Override
    /** 停止接收新请求并排空应用，管理监听器最后关闭。 */
    public void stop() {
        synchronized (lifecycleMonitor) {
            if (!running && server == null && execution == null && managementServer == null) {
                return;
            }
            running = false;
            LOGGER.info("runtime stopping port=" + port());
            if (clusterMonitor != null) clusterMonitor.close();
            unregisterMBean();
            NioHttpServer currentServer = server;
            BoundedExecutionDomain currentExecution = execution;
            NioHttpServer currentManagementServer = managementServer;
            BoundedExecutionDomain currentManagementExecution = managementExecution;
            server = null;
            execution = null;
            managementServer = null;
            managementExecution = null;

            // 应用先拒绝新准入，监听器再停接入；已准入请求可在截止时间前完成。
            application.beginDrain();
            if (currentServer != null) {
                currentServer.beginDrain();
            }
            if (currentExecution != null) {
                currentExecution.close();
            }
            application.close();
            if (currentServer != null) {
                currentServer.awaitDrained(application.shutdownDrainTimeoutMillis());
                currentServer.close();
            }
            if (currentManagementServer != null) {
                currentManagementServer.beginDrain();
                currentManagementServer.awaitDrained(application.shutdownDrainTimeoutMillis());
                currentManagementServer.close();
            }
            if (currentManagementExecution != null) currentManagementExecution.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int port() {
        NioHttpServer current = server;
        return current == null ? transportConfig.port() : current.port();
    }

    @Override
    public int managementPort() {
        NioHttpServer current = managementServer;
        if (current != null) return current.port();
        return managementConfig != null && managementConfig.dedicatedListener()
                ? managementConfig.port() : -1;
    }

    @Override
    /** 停止运行并最终释放部署与审计等托管资源。 */
    public void close() {
        try {
            stop();
        } finally {
            try {
                application.release();
            } finally {
                auditLog.close();
            }
        }
    }

    /**
     * 请求路由闸口：管理路由、OPTIONS *、健康检查和 WebSocket 握手优先；
     * 普通业务才进入 ServletApplication。三个入口对应同步、异步、流式传输契约。
     */
    private final class KernelProcessor implements StreamingResponseProcessor {
        @Override
        public HttpResponse process(HttpRequest request) throws Exception {
            RequestTrace trace = request.trace();
            if (trace != null) trace.event("kernel.enter");
            HttpResponse management = managementResponse(request, false);
            if (management != null) return management;
            if ("OPTIONS".equals(request.method()) && "*".equals(request.target())) {
                return SERVER_OPTIONS;
            }
            if ("GET".equals(request.method()) && "/__lingtong/health".equals(request.target())) {
                return HEALTH;
            }
            HttpResponse webSocket = webSocketResponse(request);
            if (webSocket != null) return webSocket;
            if (trace != null) trace.event("kernel.route-servlet");
            HttpResponse response = application.process(request);
            return response == null ? NOT_FOUND : response;
        }

        @Override
        public CompletionStage<HttpResponse> processAsync(HttpRequest request) {
            RequestTrace trace = request.trace();
            if (trace != null) trace.event("kernel.enter");
            HttpResponse management = managementResponse(request, false);
            if (management != null) return CompletableFuture.completedFuture(management);
            if ("OPTIONS".equals(request.method()) && "*".equals(request.target())) {
                return CompletableFuture.completedFuture(SERVER_OPTIONS);
            }
            if ("GET".equals(request.method()) && "/__lingtong/health".equals(request.target())) {
                return CompletableFuture.completedFuture(HEALTH);
            }
            HttpResponse webSocket = webSocketResponse(request);
            if (webSocket != null) return CompletableFuture.completedFuture(webSocket);
            if (trace != null) trace.event("kernel.route-servlet");
            return application.processAsync(request);
        }

        /** 只发布一次响应头；后续响应体通过 Servlet 的 mailbox 逐块交付。 */
        @Override
        public CompletionStage<Void> processStreaming(
                HttpRequest request,
                StreamingResponseConsumer responseConsumer) {
            RequestTrace trace = request.trace();
            if (trace != null) trace.event("kernel.enter");
            HttpResponse management = managementResponse(request, false);
            if (management != null) return publish(responseConsumer, management);
            if ("OPTIONS".equals(request.method()) && "*".equals(request.target())) {
                return publish(responseConsumer, SERVER_OPTIONS);
            }
            if ("GET".equals(request.method()) && "/__lingtong/health".equals(request.target())) {
                return publish(responseConsumer, HEALTH);
            }
            HttpResponse webSocket = webSocketResponse(request);
            if (webSocket != null) return measuredPublish(responseConsumer, webSocket);
            if (trace != null) trace.event("kernel.route-servlet");
            // 指标覆盖完整应用生命周期，而非仅覆盖响应头发布时刻。
            final long started = metrics.begin();
            final AtomicInteger status = new AtomicInteger(500);
            CompletionStage<Void> completion;
            try {
                completion = application.processStreaming(request, response -> {
                    status.set(response.status());
                    responseConsumer.accept(response);
                });
            } catch (RuntimeException | LinkageError e) {
                metrics.end(started, 500, e);
                throw e;
            }
            completion.whenComplete((ignored, failure) ->
                    metrics.end(started, status.get(), failure));
            return completion;
        }

        private CompletionStage<Void> measuredPublish(
                StreamingResponseConsumer consumer, HttpResponse response) {
            long started = metrics.begin();
            CompletionStage<Void> result = publish(consumer, response);
            result.whenComplete((ignored, failure) ->
                    metrics.end(started, response.status(), failure));
            return result;
        }

        /** HTTP/1 Upgrade 和 HTTP/2 扩展 CONNECT 在此汇合，共用端点注册表。 */
        private HttpResponse webSocketResponse(HttpRequest request) {
            boolean http1 = WebSocketHandshake.isUpgradeRequest(request);
            boolean http2 = WebSocketHandshake.isExtendedConnectRequest(request);
            if (!http1 && !http2) return null;
            java.util.concurrent.Executor callbackExecutor = command -> {
                BoundedExecutionDomain current = execution;
                if (current == null || !current.execute(command)) {
                    throw new RejectedExecutionException("WebSocket callback queue is full");
                }
            };
            try {
                return webSockets.handshake(request, callbackExecutor, maxWebSocketMessageBytes);
            } catch (Exception e) {
                return HttpResponse.text(500, "Internal Server Error", "WebSocket endpoint failed\n");
            }
        }

        private CompletionStage<Void> publish(
                StreamingResponseConsumer consumer,
                HttpResponse response) {
            byte[] bytes = response.body();
            ResponseBodyMailbox body = new ResponseBodyMailbox(
                    Math.max(1, bytes.length), 0, Math.max(1, bytes.length));
            StreamingHttpResponse streaming = new StreamingHttpResponse(
                    response.status(), response.reason(), response.headers(), body, 0L,
                    response.upgrade());
            try {
                if (bytes.length > 0) {
                    body.offer(bytes, 0, bytes.length);
                }
                body.complete(response.trailers());
                consumer.accept(streaming);
                return CompletableFuture.completedFuture(null);
            } catch (Exception | LinkageError e) {
                body.fail(e);
                CompletableFuture<Void> failed = new CompletableFuture<Void>();
                failed.completeExceptionally(e);
                return failed;
            }
        }
    }

    private HttpResponse managementResponse(HttpRequest request, boolean dedicatedListener) {
        String path = request.target();
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if ("GET".equals(request.method()) && "/__lingtong/health".equals(path)) return HEALTH;
        if ("GET".equals(request.method()) && "/__lingtong/ready".equals(path)) {
            return running && application.isAcceptingRequests()
                    ? text(200, "OK", "text/plain; charset=utf-8", "READY\n")
                    : text(503, "Service Unavailable", "text/plain; charset=utf-8", "NOT_READY\n");
        }
        if ("GET".equals(request.method()) && "/__lingtong/admin".equals(path)) {
            if (!dedicatedListener && managementConfig != null
                    && managementConfig.dedicatedListener()) return NOT_FOUND;
            return managementConfig == null ? NOT_FOUND
                    : text(200, "OK", "text/html; charset=utf-8", dashboard());
        }
        if (!path.startsWith("/__lingtong/manage/")) return null;
        if (managementConfig == null) return NOT_FOUND;
        if (!dedicatedListener && managementConfig.dedicatedListener()) return NOT_FOUND;
        metrics.managementRequest();
        ManagementRole role = managementRole(request);
        if (role == ManagementRole.NONE) {
            metrics.managementAuthFailure();
            return audited(request, path, new HttpResponse(401, "Unauthorized",
                    HttpHeaders.builder().add("content-type", "application/json; charset=utf-8")
                            .add("www-authenticate", "Bearer realm=\"LingTong Management\"")
                            .add("cache-control", "no-store")
                            .add("x-content-type-options", "nosniff")
                            .add("x-frame-options", "DENY").build(),
                    "{\"error\":\"unauthorized\"}\n".getBytes(StandardCharsets.UTF_8)),
                    "authentication_failed");
        }
        if ("/__lingtong/manage/v1/actions/drain".equals(path)) {
            if (role != ManagementRole.OPERATOR) {
                metrics.managementAuthorizationFailure();
                return audited(request, path,
                        text(403, "Forbidden", "application/json; charset=utf-8",
                                "{\"error\":\"operator_role_required\"}\n"),
                        "authorization_failed");
            }
            if (!"POST".equals(request.method())) {
                return audited(request, path,
                        text(405, "Method Not Allowed", "application/json; charset=utf-8",
                                "{\"error\":\"method_not_allowed\"}\n"), "method_not_allowed");
            }
            if (!"drain".equals(request.headers().first("x-lingtong-confirm"))) {
                return audited(request, path,
                        text(409, "Conflict", "application/json; charset=utf-8",
                                "{\"error\":\"confirmation_required\"}\n"),
                        "confirmation_required");
            }
            application.beginDrain();
            NioHttpServer current = server;
            if (current != null) current.beginDrain();
            return audited(request, path,
                    text(202, "Accepted", "application/json; charset=utf-8",
                            "{\"status\":\"draining\"}\n"), "drain_started");
        }
        if (!"GET".equals(request.method())) {
            return audited(request, path,
                    text(405, "Method Not Allowed", "application/json; charset=utf-8",
                            "{\"error\":\"method_not_allowed\"}\n"), "method_not_allowed");
        }
        if ("/__lingtong/manage/v1/heartbeat".equals(path)) {
            return audited(request, path, heartbeat(), "read");
        }
        if ("/__lingtong/manage/v1/runtime".equals(path)) {
            return audited(request, path, json(runtimeJson()), "read");
        }
        if ("/__lingtong/manage/v1/cluster".equals(path)) {
            return audited(request, path, json(clusterJson()), "read");
        }
        if ("/__lingtong/manage/v1/threads".equals(path)) {
            return audited(request, path, json(threadsJson()), "read");
        }
        if ("/__lingtong/manage/v1/diagnostics".equals(path)) {
            return audited(request, path, json(diagnosticsJson()), "read");
        }
        if ("/__lingtong/manage/v1/audit".equals(path)) {
            HttpResponse response = json(auditJson());
            return audited(request, path, response, "read");
        }
        if ("/__lingtong/manage/v1/metrics".equals(path)) {
            return audited(request, path,
                    text(200, "OK", "text/plain; version=0.0.4; charset=utf-8", prometheus()),
                    "read");
        }
        return audited(request, path, NOT_FOUND, "not_found");
    }

    private HttpResponse audited(
            HttpRequest request, String path, HttpResponse response, String outcome) {
        auditLog.record(request.connectionInfo().remoteAddress(),
                managementRole(request).name(), request.method(), path, response.status(), outcome);
        return response;
    }

    private ManagementRole managementRole(HttpRequest request) {
        String value = request.headers().first("authorization");
        if (value == null || !value.startsWith("Bearer ")) return ManagementRole.NONE;
        byte[] candidate = value.substring(7).getBytes(StandardCharsets.UTF_8);
        boolean reader = MessageDigest.isEqual(
                managementConfig.token().getBytes(StandardCharsets.UTF_8), candidate);
        String operatorToken = managementConfig.operatorToken();
        boolean operator = operatorToken != null && MessageDigest.isEqual(
                operatorToken.getBytes(StandardCharsets.UTF_8), candidate);
        if (operator || (reader && operatorToken == null)) return ManagementRole.OPERATOR;
        return reader ? ManagementRole.READER : ManagementRole.NONE;
    }

    private String heartbeatJson() {
        return "{\"nodeId\":\"" + jsonEscape(managementConfig.nodeId())
                + "\",\"status\":\"UP\",\"uptimeSeconds\":" + metrics.uptimeSeconds() + "}\n";
    }

    private HttpResponse heartbeat() {
        return new HttpResponse(200, "OK",
                HttpHeaders.builder()
                        .add("content-type", "application/json; charset=utf-8")
                        .add("cache-control", "no-store")
                        .add("x-content-type-options", "nosniff")
                        .add("x-lingtong-node-id", managementConfig.nodeId())
                        .build(),
                heartbeatJson().getBytes(StandardCharsets.UTF_8));
    }

    private String runtimeJson() {
        NioHttpServer currentServer = server;
        BoundedExecutionDomain currentExecution = execution;
        Runtime runtime = Runtime.getRuntime();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        return "{\"nodeId\":\"" + jsonEscape(managementConfig.nodeId())
                + "\",\"running\":" + running
                + ",\"accepting\":" + (currentServer != null && currentServer.isAccepting())
                + ",\"applicationPort\":" + port()
                + ",\"managementPort\":" + managementPort()
                + ",\"uptimeSeconds\":" + metrics.uptimeSeconds()
                + ",\"requestsTotal\":" + metrics.requests()
                + ",\"activeRequests\":" + metrics.activeRequests()
                + ",\"failuresTotal\":" + metrics.failures()
                + ",\"responses2xx\":" + metrics.responses2xx()
                + ",\"responses4xx\":" + metrics.responses4xx()
                + ",\"responses5xx\":" + metrics.responses5xx()
                + ",\"averageRequestMillis\":" + decimal(metrics.averageMillis())
                + ",\"maximumRequestMillis\":" + metrics.maximumMillis()
                + ",\"managementRequests\":" + metrics.managementRequests()
                + ",\"managementAuthFailures\":" + metrics.managementAuthFailures()
                + ",\"managementAuthorizationFailures\":"
                + metrics.managementAuthorizationFailures()
                + ",\"auditEntriesOverwritten\":" + auditLog.overwritten()
                + ",\"auditPersistenceFailures\":" + auditLog.persistenceFailures()
                + ",\"activeConnections\":" + (currentServer == null ? 0 : currentServer.activeConnections())
                + ",\"acceptedConnections\":" + (currentServer == null ? 0 : currentServer.acceptedConnections())
                + ",\"rejectedConnections\":" + (currentServer == null ? 0 : currentServer.rejectedConnections())
                + ",\"workerActive\":" + (currentExecution == null ? 0 : currentExecution.activeCount())
                + ",\"workerQueued\":" + (currentExecution == null ? 0 : currentExecution.queuedCount())
                + ",\"localSessions\":" + application.activeSessionCount()
                + ",\"storedSessions\":" + application.storedSessionCount()
                + ",\"expiredSessions\":" + application.expiredSessionCount()
                + ",\"sessionScavengerFailures\":" + application.sessionScavengerFailureCount()
                + ",\"heapUsedBytes\":" + usedHeap
                + ",\"heapMaxBytes\":" + runtime.maxMemory()
                + ",\"threads\":" + ManagementFactory.getThreadMXBean().getThreadCount() + "}\n";
    }

    private String clusterJson() {
        StringBuilder json = new StringBuilder("{\"nodeId\":\"")
                .append(jsonEscape(managementConfig.nodeId())).append("\",\"peers\":[");
        List<ClusterMonitor.PeerSnapshot> peers = clusterMonitor.snapshot();
        for (int i = 0; i < peers.size(); i++) {
            ClusterMonitor.PeerSnapshot peer = peers.get(i);
            if (i > 0) json.append(',');
            json.append("{\"endpoint\":\"").append(jsonEscape(peer.endpoint))
                    .append("\",\"nodeId\":")
                    .append(peer.nodeId == null ? "null" : "\"" + jsonEscape(peer.nodeId) + "\"")
                    .append(",\"available\":").append(peer.available)
                    .append(",\"latencyMillis\":").append(peer.latencyMillis)
                    .append(",\"lastAttemptMillis\":").append(peer.lastAttemptMillis)
                    .append(",\"lastSuccessMillis\":").append(peer.lastSuccessMillis)
                    .append(",\"probes\":").append(peer.probes)
                    .append(",\"failures\":").append(peer.failures)
                    .append(",\"consecutiveFailures\":").append(peer.consecutiveFailures)
                    .append(",\"failure\":")
                    .append(peer.failure == null ? "null" : "\"" + jsonEscape(peer.failure) + "\"")
                    .append('}');
        }
        return json.append("]}\n").toString();
    }

    private String diagnosticsJson() {
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threads.findDeadlockedThreads();
        StringBuilder json = new StringBuilder("{\"classLoading\":{\"loaded\":")
                .append(classes.getLoadedClassCount())
                .append(",\"totalLoaded\":").append(classes.getTotalLoadedClassCount())
                .append(",\"unloaded\":").append(classes.getUnloadedClassCount())
                .append("},\"operatingSystem\":{\"name\":\"")
                .append(jsonEscape(operatingSystem.getName()))
                .append("\",\"arch\":\"").append(jsonEscape(operatingSystem.getArch()))
                .append("\",\"availableProcessors\":").append(operatingSystem.getAvailableProcessors())
                .append(",\"systemLoadAverage\":").append(jsonNumber(operatingSystem.getSystemLoadAverage()))
                .append("},\"deadlockedThreadIds\":[");
        if (deadlocked != null) {
            for (int i = 0; i < deadlocked.length; i++) {
                if (i > 0) json.append(',');
                json.append(deadlocked[i]);
            }
        }
        json.append("],\"garbageCollectors\":[");
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < collectors.size(); i++) {
            GarbageCollectorMXBean collector = collectors.get(i);
            if (i > 0) json.append(',');
            json.append("{\"name\":\"").append(jsonEscape(collector.getName()))
                    .append("\",\"collectionCount\":").append(collector.getCollectionCount())
                    .append(",\"collectionTimeMillis\":").append(collector.getCollectionTime())
                    .append('}');
        }
        json.append("],\"memoryPools\":[");
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (int i = 0; i < pools.size(); i++) {
            MemoryPoolMXBean pool = pools.get(i);
            MemoryUsage usage = pool.getUsage();
            if (i > 0) json.append(',');
            json.append("{\"name\":\"").append(jsonEscape(pool.getName()))
                    .append("\",\"type\":\"").append(pool.getType())
                    .append("\",\"usedBytes\":").append(usage == null ? -1L : usage.getUsed())
                    .append(",\"committedBytes\":").append(usage == null ? -1L : usage.getCommitted())
                    .append(",\"maxBytes\":").append(usage == null ? -1L : usage.getMax())
                    .append('}');
        }
        return json.append("]}\n").toString();
    }

    private String threadsJson() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        ThreadInfo[] information = threads.getThreadInfo(threads.getAllThreadIds(), 32);
        StringBuilder json = new StringBuilder("{\"threads\":[");
        int written = 0;
        for (ThreadInfo info : information) {
            if (info == null || written >= 64) continue;
            if (written++ > 0) json.append(',');
            json.append("{\"id\":").append(info.getThreadId())
                    .append(",\"name\":\"").append(jsonEscape(info.getThreadName()))
                    .append("\",\"state\":\"").append(info.getThreadState())
                    .append("\",\"stack\":[");
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < stack.length; i++) {
                if (i > 0) json.append(',');
                json.append('"').append(jsonEscape(stack[i].toString())).append('"');
            }
            json.append("]}");
        }
        return json.append("]}\n").toString();
    }

    private String auditJson() {
        List<ManagementAuditEvent> entries = auditLog.snapshot();
        StringBuilder json = new StringBuilder("{\"overwritten\":")
                .append(auditLog.overwritten())
                .append(",\"persistenceFailures\":").append(auditLog.persistenceFailures())
                .append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            ManagementAuditEvent entry = entries.get(i);
            if (i > 0) json.append(',');
            json.append("{\"timestampMillis\":").append(entry.timestampMillis())
                    .append(",\"remoteAddress\":\"").append(jsonEscape(entry.remoteAddress()))
                    .append("\",\"role\":\"").append(jsonEscape(entry.role()))
                    .append("\",\"method\":\"").append(jsonEscape(entry.method()))
                    .append("\",\"path\":\"").append(jsonEscape(entry.path()))
                    .append("\",\"status\":").append(entry.status())
                    .append(",\"outcome\":\"").append(jsonEscape(entry.outcome()))
                    .append("\"}");
        }
        return json.append("]}\n").toString();
    }

    private String prometheus() {
        NioHttpServer currentServer = server;
        BoundedExecutionDomain currentExecution = execution;
        Runtime runtime = Runtime.getRuntime();
        StringBuilder output = new StringBuilder();
        metric(output, "lingtong_up", "gauge", running ? 1 : 0);
        metric(output, "lingtong_requests_total", "counter", metrics.requests());
        metric(output, "lingtong_request_failures_total", "counter", metrics.failures());
        metric(output, "lingtong_responses_2xx_total", "counter", metrics.responses2xx());
        metric(output, "lingtong_responses_4xx_total", "counter", metrics.responses4xx());
        metric(output, "lingtong_responses_5xx_total", "counter", metrics.responses5xx());
        metric(output, "lingtong_active_requests", "gauge", metrics.activeRequests());
        metric(output, "lingtong_request_duration_average_millis", "gauge", metrics.averageMillis());
        metric(output, "lingtong_request_duration_max_millis", "gauge", metrics.maximumMillis());
        metric(output, "lingtong_management_requests_total", "counter", metrics.managementRequests());
        metric(output, "lingtong_management_auth_failures_total", "counter", metrics.managementAuthFailures());
        metric(output, "lingtong_management_authorization_failures_total", "counter",
                metrics.managementAuthorizationFailures());
        metric(output, "lingtong_management_audit_overwritten_total", "counter", auditLog.overwritten());
        metric(output, "lingtong_management_audit_persistence_failures_total", "counter",
                auditLog.persistenceFailures());
        metric(output, "lingtong_active_connections", "gauge",
                currentServer == null ? 0 : currentServer.activeConnections());
        metric(output, "lingtong_connections_accepted_total", "counter",
                currentServer == null ? 0 : currentServer.acceptedConnections());
        metric(output, "lingtong_connections_rejected_total", "counter",
                currentServer == null ? 0 : currentServer.rejectedConnections());
        metric(output, "lingtong_worker_active", "gauge",
                currentExecution == null ? 0 : currentExecution.activeCount());
        metric(output, "lingtong_worker_queued", "gauge",
                currentExecution == null ? 0 : currentExecution.queuedCount());
        metric(output, "lingtong_sessions_local", "gauge", application.activeSessionCount());
        metric(output, "lingtong_sessions_stored", "gauge", application.storedSessionCount());
        metric(output, "lingtong_sessions_expired_total", "counter", application.expiredSessionCount());
        metric(output, "lingtong_session_scavenger_failures_total", "counter",
                application.sessionScavengerFailureCount());
        metric(output, "lingtong_jvm_heap_used_bytes", "gauge",
                runtime.totalMemory() - runtime.freeMemory());
        metric(output, "lingtong_jvm_heap_max_bytes", "gauge", runtime.maxMemory());
        metric(output, "lingtong_jvm_threads", "gauge",
                ManagementFactory.getThreadMXBean().getThreadCount());
        long collectionCount = 0L;
        long collectionTime = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0L) collectionCount += collector.getCollectionCount();
            if (collector.getCollectionTime() >= 0L) collectionTime += collector.getCollectionTime();
        }
        metric(output, "lingtong_jvm_gc_collections_total", "counter", collectionCount);
        metric(output, "lingtong_jvm_gc_collection_millis_total", "counter", collectionTime);
        if (clusterMonitor != null) {
            int available = 0;
            long heartbeatFailures = 0L;
            List<ClusterMonitor.PeerSnapshot> peers = clusterMonitor.snapshot();
            for (ClusterMonitor.PeerSnapshot peer : peers) {
                if (peer.available) available++;
                heartbeatFailures += peer.failures;
            }
            metric(output, "lingtong_cluster_peers", "gauge", peers.size());
            metric(output, "lingtong_cluster_peers_available", "gauge", available);
            metric(output, "lingtong_cluster_heartbeat_failures_total", "counter", heartbeatFailures);
        }
        return output.toString();
    }

    private static void metric(StringBuilder output, String name, String type, Number value) {
        output.append("# TYPE ").append(name).append(' ').append(type).append('\n')
                .append(name).append(' ').append(value).append('\n');
    }

    private TransportConfig managementTransportConfig() {
        return new TransportConfig(
                managementConfig.bindAddress(), managementConfig.port(), 1,
                8 * 1024, 64, 256, 16 * 1024, 0,
                1, 0, 5_000L, 15_000L, 100,
                CompressionConfig.disabled(), ProxyConfig.disabled(),
                32, 1024, 0);
    }

    private static HttpResponse json(String body) {
        return text(200, "OK", "application/json; charset=utf-8", body);
    }

    private static HttpResponse text(int status, String reason, String contentType, String body) {
        return new HttpResponse(status, reason,
                HttpHeaders.builder().add("content-type", contentType)
                        .add("cache-control", "no-store")
                        .add("x-content-type-options", "nosniff")
                        .add("x-frame-options", "DENY")
                        .add("referrer-policy", "no-referrer").build(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String jsonNumber(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "null" : decimal(value);
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String dashboard() {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>LingTong Console</title><style>body{margin:0;font:14px system-ui;background:#f4f6f8;color:#17202a}header{background:#17202a;color:white;padding:16px 24px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px}main{max-width:1180px;margin:auto;padding:20px}nav{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:16px}button,input{height:36px;border:1px solid #aeb6bf;background:white;padding:0 12px}button{cursor:pointer}button.active{background:#117864;color:white;border-color:#117864}#auth{display:flex;gap:8px;max-width:100%}#auth input{min-width:0}pre{background:#fff;border:1px solid #d5d8dc;padding:16px;min-height:360px;overflow:auto;white-space:pre-wrap}h1{font-size:20px;margin:0;letter-spacing:0}.bad{color:#b03a2e}</style></head>"
                + "<body><header><h1>LingTong Runtime Console</h1><div id=auth><input id=token type=password placeholder=\"Management token\"><button onclick=load()>Connect</button></div></header>"
                + "<main><nav><button data-view=runtime class=active>Runtime</button><button data-view=cluster>Cluster</button><button data-view=diagnostics>Diagnostics</button><button data-view=threads>Threads</button><button data-view=audit>Audit</button><button data-view=metrics>Metrics</button><button onclick=drain()>Drain</button></nav><pre id=output>Enter the management token.</pre></main>"
                + "<script>let view='runtime';document.querySelectorAll('nav button[data-view]').forEach(b=>b.onclick=()=>{document.querySelector('.active').classList.remove('active');b.classList.add('active');view=b.dataset.view;load()});function token(){let t=document.getElementById('token').value||sessionStorage.ltToken;if(t)sessionStorage.ltToken=t;return t}async function load(){let t=token();if(!t)return;let o=document.getElementById('output');try{let r=await fetch('/__lingtong/manage/v1/'+view,{headers:{Authorization:'Bearer '+t}});let x=await r.text();if(!r.ok)throw Error(r.status+' '+x);o.className='';o.textContent=view==='metrics'?x:JSON.stringify(JSON.parse(x),null,2)}catch(e){o.className='bad';o.textContent=e.message}}async function drain(){let t=token();if(!t||!confirm('Stop accepting new application requests?'))return;let r=await fetch('/__lingtong/manage/v1/actions/drain',{method:'POST',headers:{Authorization:'Bearer '+t,'X-LingTong-Confirm':'drain'}});document.getElementById('output').textContent=await r.text();view='runtime'}setInterval(()=>{if(view==='runtime'||view==='cluster')load()},5000)</script></body></html>";
    }

    private void registerMBean() {
        try {
            String node = managementConfig == null ? "runtime-" + System.identityHashCode(this)
                    : managementConfig.nodeId();
            ObjectName name = new ObjectName(
                    "io.github.o1o00o10.lingtong:type=Runtime,node=" + ObjectName.quote(node));
            MBeanServer beans = ManagementFactory.getPlatformMBeanServer();
            if (!beans.isRegistered(name)) beans.registerMBean(new RuntimeMBean(node), name);
            mbeanName = name;
        } catch (Exception e) {
            throw new IllegalStateException("failed to register LingTong runtime MBean", e);
        }
    }

    private void unregisterMBean() {
        ObjectName name = mbeanName;
        mbeanName = null;
        if (name == null) return;
        try {
            MBeanServer beans = ManagementFactory.getPlatformMBeanServer();
            if (beans.isRegistered(name)) beans.unregisterMBean(name);
        } catch (Exception ignored) {
            // Runtime shutdown must continue even when an external JMX agent races removal.
        }
    }

    /** 封装运行时mBean的状态与处理边界。 */
    private final class RuntimeMBean implements LingTongRuntimeMXBean {
        /** 节点标识符。 */
        private final String nodeId;
        private RuntimeMBean(String nodeId) { this.nodeId = nodeId; }
        @Override public String getNodeId() { return nodeId; }
        @Override public boolean isRunning() { return running; }
        @Override public boolean isAcceptingRequests() { return application.isAcceptingRequests(); }
        @Override public int getApplicationPort() { return port(); }
        @Override public int getManagementPort() { return managementPort(); }
        @Override public long getUptimeSeconds() { return metrics.uptimeSeconds(); }
        @Override public long getRequestsTotal() { return metrics.requests(); }
        @Override public long getFailuresTotal() { return metrics.failures(); }
        @Override public int getActiveRequests() { return metrics.activeRequests(); }
        @Override public int getActiveConnections() {
            NioHttpServer value = server;
            return value == null ? 0 : value.activeConnections();
        }
        @Override public int getWorkerActive() {
            BoundedExecutionDomain value = execution;
            return value == null ? 0 : value.activeCount();
        }
        @Override public int getWorkerQueued() {
            BoundedExecutionDomain value = execution;
            return value == null ? 0 : value.queuedCount();
        }
        @Override public int getLocalSessions() { return application.activeSessionCount(); }
        @Override public long getStoredSessions() { return application.storedSessionCount(); }
        @Override public long getExpiredSessions() { return application.expiredSessionCount(); }
        @Override public long getSessionScavengerFailures() {
            return application.sessionScavengerFailureCount();
        }
        @Override public long getManagementAuthFailures() {
            return metrics.managementAuthFailures();
        }
        @Override public long getManagementAuthorizationFailures() {
            return metrics.managementAuthorizationFailures();
        }
        @Override public long getManagementAuditOverwritten() { return auditLog.overwritten(); }
        @Override public long getManagementAuditPersistenceFailures() {
            return auditLog.persistenceFailures();
        }
    }

    private static WebSocketEndpointRegistry registry(Map<String, WebSocketHandler> handlers) {
        WebSocketEndpointRegistry result = new WebSocketEndpointRegistry();
        for (Map.Entry<String, WebSocketHandler> entry : handlers.entrySet()) {
            final WebSocketHandler handler = entry.getValue();
            result.register(entry.getKey(), (request, parameters) -> handler);
        }
        return result;
    }
}
