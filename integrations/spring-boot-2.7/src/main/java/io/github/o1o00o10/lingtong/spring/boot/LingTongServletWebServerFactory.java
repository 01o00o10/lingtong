/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import io.github.o1o00o10.lingtong.api.LingTongRuntime;
import io.github.o1o00o10.lingtong.embed.LingTong;
import io.github.o1o00o10.lingtong.transport.AlpnProvider;
import io.github.o1o00o10.lingtong.transport.TlsConfig;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.boot.web.servlet.server.Session;

/** Maps Boot's server settings and ServletContextInitializers to the embedded API. */
/** 把 Boot 的端口、SSL、ServletContextInitializer 等设置转成嵌入式 Runtime。 */
public final class LingTongServletWebServerFactory extends AbstractServletWebServerFactory {
  /** 请求头部超时时长。 */
  private Duration requestHeaderTimeout = Duration.ofSeconds(10);
  /** 最大头部字节数，单位为字节。 */
  private int maxHeaderBytes = 32 * 1024;
  /** 最大请求字节数，单位为字节。 */
  private int maxRequestBytes = 8 * 1024 * 1024;
  /** 空闲超时时长。 */
  private Duration idleTimeout = Duration.ofSeconds(30);
  /** 最大请求集合逐连接。 */
  private int maxRequestsPerConnection = 1_000;
  /** 最大头部数量。 */
  private int maxHeaderCount = 100;
  /** 最大尾部头字节数，单位为字节。 */
  private int maxTrailerBytes = 8 * 1024;
  /** 最大尾部头数量。 */
  private int maxTrailerCount = 100;
  /** 最大参数数量。 */
  private int maxParameterCount = 1_000;
  /** 最大参数字节数，单位为字节。 */
  private int maxParameterBytes = 1024 * 1024;
  /** 最大Cookie数量。 */
  private int maxCookieCount = 200;
  /** 最大Cookie字节数，单位为字节。 */
  private int maxCookieBytes = 8 * 1024;
  /** 请求消息体缓冲区字节数，单位为字节。 */
  private int requestBodyBufferBytes = 64 * 1024;
  /** 请求消息体低水位字节数，单位为字节。 */
  private int requestBodyLowWaterBytes = 32 * 1024;
  /** 响应消息体缓冲区字节数，单位为字节。 */
  private int responseBodyBufferBytes = 64 * 1024;
  /** 响应消息体低水位字节数，单位为字节。 */
  private int responseBodyLowWaterBytes = 32 * 1024;
  /** 最大响应字节数，单位为字节。 */
  private long maxResponseBytes = 8L * 1024L * 1024L;
  /** 停机排空超时时长。 */
  private Duration shutdownDrainTimeout = Duration.ofSeconds(5);
  /** 工作线程线程集合。 */
  private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
  /** 按顺序保存的受信任的代理集合。 */
  private List<String> trustedProxies = new ArrayList<String>();
  /** ALPN提供器。 */
  private AlpnProvider alpnProvider = AlpnProvider.jdk();
  /** 管理令牌。 */
  private String managementToken;
  /** 节点标识符。 */
  private String nodeId;
  /** 按顺序保存的集群对端集合。 */
  private List<String> clusterPeers = new ArrayList<String>();
  /** 集群心跳。 */
  private Duration clusterHeartbeat = Duration.ofSeconds(5);
  /** 集群失败阈值。 */
  private int clusterFailureThreshold = 3;
  /** 管理绑定地址。 */
  private String managementBindAddress = "127.0.0.1";
  /** 管理端口。 */
  private int managementPort = -1;
  /** 管理操作者令牌。 */
  private String managementOperatorToken;
  /** 管理审计文件。 */
  private Path managementAuditFile;
  /** 管理审计最大字节数，单位为字节。 */
  private long managementAuditMaxBytes = 16L * 1024L * 1024L;
  /** 管理审计保留的文件列表。 */
  private int managementAuditRetainedFiles = 5;

  public LingTongServletWebServerFactory() {
    super();
  }

  public LingTongServletWebServerFactory(int port) {
    super(port);
  }

  /** Prepares Servlet registration now; Boot opens the listener later through WebServer.start(). */
  @Override
  /** 把 Boot 初始化器接入可回滚注册窗口，再返回受 Boot 管理的 WebServer。 */
  public WebServer getWebServer(ServletContextInitializer... initializers) {
    validateSupportedConfiguration();
    ServletContextInitializer[] mergedInitializers = mergeInitializers(initializers);
    Compression compression = getCompression() == null ? new Compression() : getCompression();
    LingTong.Builder builder =
        LingTong.builder()
            .bindAddress(bindAddress())
            .port(getPort())
            .contextPath(getContextPath())
            .sessionTimeoutSeconds(sessionTimeoutSeconds())
            .maxHeaderBytes(maxHeaderBytes)
            .maxBodyBytes(maxRequestBytes)
            .requestHeaderTimeoutMillis(
                positiveMillis(requestHeaderTimeout, "request header timeout"))
            .idleTimeoutMillis(positiveMillis(idleTimeout, "idle timeout"))
            .maxRequestsPerConnection(maxRequestsPerConnection)
            .maxHeaderCount(maxHeaderCount)
            .maxTrailerBytes(maxTrailerBytes)
            .maxTrailerCount(maxTrailerCount)
            .maxParameterCount(maxParameterCount)
            .maxParameterBytes(maxParameterBytes)
            .maxCookieCount(maxCookieCount)
            .maxCookieBytes(maxCookieBytes)
            .requestBodyBufferBytes(requestBodyBufferBytes)
            .requestBodyLowWaterBytes(requestBodyLowWaterBytes)
            .responseBodyBufferBytes(responseBodyBufferBytes)
            .responseBodyLowWaterBytes(responseBodyLowWaterBytes)
            .maxResponseBytes(maxResponseBytes)
            .workerThreads(workerThreads)
            .shutdownDrainTimeoutMillis(
                getShutdown() == Shutdown.IMMEDIATE
                    ? 0L
                    : nonNegativeMillis(shutdownDrainTimeout, "shutdown drain timeout"))
            .compressionEnabled(compression.getEnabled())
            .compressionMinResponseBytes(compression.getMinResponseSize().toBytes())
            .compressionMimeTypes(compression.getMimeTypes())
            .compressionExcludedUserAgents(stringsOrEmpty(compression.getExcludedUserAgents()))
            .trustedProxies(trustedProxies.toArray(new String[trustedProxies.size()]))
            .alpnProvider(alpnProvider)
            .http2Enabled(getHttp2() != null && getHttp2().isEnabled())
            .initializer(
                (handledTypes, servletContext) -> {
                  configureSession(servletContext);
                  getInitParameters()
                      .forEach(
                          (name, value) -> {
                            if (!servletContext.setInitParameter(name, value)) {
                              throw new IllegalStateException(
                                  "duplicate ServletContext init parameter: " + name);
                            }
                          });
                  for (String listener : getWebListenerClassNames()) {
                    servletContext.addListener(listener);
                  }
                  initialize(mergedInitializers, servletContext);
                });
    if (getDisplayName() != null && !getDisplayName().trim().isEmpty()) {
      builder.displayName(getDisplayName());
    }
    if (getSession().getCookie().getSameSite() != null) {
      builder.sessionCookieSameSite(getSession().getCookie().getSameSite().attributeValue());
    }
    File documentRoot = getValidDocumentRoot();
    if (documentRoot != null) {
      builder.resourceRoot(documentRoot.toPath());
    }
    builder.defaultServletEnabled(isRegisterDefaultServlet());
    builder.mimeMappings(mimeMappings());
    builder.localeEncodingMappings(localeEncodingMappings());
    if (getSession().isPersistent()) {
      builder.persistentSessions(getValidSessionStoreDir(true).toPath());
    }
    if (managementToken != null && !managementToken.isEmpty()) {
      builder
          .management(managementToken)
          .managementBindAddress(managementBindAddress)
          .managementPort(managementPort)
          .managementOperatorToken(managementOperatorToken)
          .nodeId(nodeId == null || nodeId.trim().isEmpty() ? "spring-node" : nodeId)
          .clusterPeers(clusterPeers.toArray(new String[clusterPeers.size()]))
          .clusterHeartbeatMillis(positiveMillis(clusterHeartbeat, "cluster heartbeat"))
          .clusterFailureThreshold(clusterFailureThreshold);
      if (managementAuditFile != null) {
        builder.managementAudit(
            managementAuditFile, managementAuditMaxBytes, managementAuditRetainedFiles);
      }
    }
    configureSsl(builder);
    for (ErrorPage errorPage : getErrorPages()) {
      if (errorPage.isGlobal()) {
        builder.errorPage(errorPage.getPath());
      } else if (errorPage.getException() != null) {
        builder.errorPage(errorPage.getException(), errorPage.getPath());
      } else {
        builder.errorPage(errorPage.getStatusCode(), errorPage.getPath());
      }
    }
    LingTongRuntime runtime = builder.build();
    try {
      // Boot needs a usable ServletContext during ApplicationContext refresh.
      runtime.prepare();
    } catch (Exception e) {
      runtime.close();
      throw new WebServerException("Unable to prepare LingTong ServletContext", e);
    }
    return new LingTongWebServer(runtime);
  }

  private void validateSupportedConfiguration() {
    if (getServerHeader() != null && !getServerHeader().trim().isEmpty()) {
      throw unsupported("server.server-header");
    }
    if (getDocumentRoot() != null
        && (!getDocumentRoot().isDirectory() || !getDocumentRoot().canRead())) {
      throw new WebServerException(
          "LingTong document root is not a readable directory: " + getDocumentRoot(), null);
    }
    if (maxHeaderBytes <= 0) {
      throw new WebServerException("LingTong maximum HTTP header size must be positive", null);
    }
    if (maxRequestBytes <= 0 || workerThreads <= 0) {
      throw new WebServerException(
          "LingTong request size and worker thread settings must be positive", null);
    }
    if (shouldRegisterJspServlet()) {
      throw unsupported("JSP registration; JSP/EL/JSTL are separate modules");
    }
    if (maxRequestsPerConnection <= 0) {
      throw new WebServerException("LingTong max requests per connection must be positive", null);
    }
    if (maxHeaderCount <= 0
        || maxTrailerBytes <= 0
        || maxTrailerCount < 0
        || maxParameterCount <= 0
        || maxParameterBytes <= 0
        || maxCookieCount <= 0
        || maxCookieBytes <= 0) {
      throw new WebServerException("LingTong request count and size limits are invalid", null);
    }
    if (requestBodyBufferBytes <= 0
        || requestBodyLowWaterBytes < 0
        || requestBodyLowWaterBytes >= requestBodyBufferBytes) {
      throw new WebServerException("LingTong request body watermarks are invalid", null);
    }
    if (maxResponseBytes <= 0L || maxResponseBytes > Integer.MAX_VALUE) {
      throw new WebServerException(
          "LingTong max response size must be between 1 byte and 2 GiB", null);
    }
    if (responseBodyBufferBytes <= 0
        || responseBodyLowWaterBytes < 0
        || responseBodyLowWaterBytes >= responseBodyBufferBytes) {
      throw new WebServerException("LingTong response body watermarks are invalid", null);
    }
  }

  private void configureSsl(LingTong.Builder builder) {
    Ssl ssl = getSsl();
    if (ssl == null || !ssl.isEnabled()) {
      return;
    }
    try {
      builder
          .tls(SpringBootSslContextFactory.create(ssl, getOrCreateSslStoreProvider()))
          .tlsClientAuth(clientAuth(ssl.getClientAuth()))
          .tlsProtocols(stringsOrEmpty(ssl.getEnabledProtocols()))
          .tlsCipherSuites(stringsOrEmpty(ssl.getCiphers()));
    } catch (Exception e) {
      throw new WebServerException("Unable to configure LingTong TLS", e);
    }
  }

  public AlpnProvider getAlpnProvider() {
    return alpnProvider;
  }

  public void setAlpnProvider(AlpnProvider alpnProvider) {
    if (alpnProvider == null) throw new IllegalArgumentException("ALPN provider must not be null");
    this.alpnProvider = alpnProvider;
  }

  public void setManagementToken(String value) {
    managementToken = value;
  }

  public void setNodeId(String value) {
    nodeId = value;
  }

  public void setClusterPeers(List<String> value) {
    clusterPeers = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
  }

  public void setClusterHeartbeat(Duration value) {
    clusterHeartbeat = value;
  }

  public void setClusterFailureThreshold(int value) {
    clusterFailureThreshold = value;
  }

  public void setManagementBindAddress(String value) {
    managementBindAddress = value;
  }

  public void setManagementPort(int value) {
    managementPort = value;
  }

  public void setManagementOperatorToken(String value) {
    managementOperatorToken = value;
  }

  public void setManagementAuditFile(Path value) {
    managementAuditFile = value;
  }

  public void setManagementAuditMaxBytes(long value) {
    managementAuditMaxBytes = value;
  }

  public void setManagementAuditRetainedFiles(int value) {
    managementAuditRetainedFiles = value;
  }

  private static TlsConfig.ClientAuth clientAuth(Ssl.ClientAuth value) {
    if (value == Ssl.ClientAuth.NEED) return TlsConfig.ClientAuth.NEED;
    if (value == Ssl.ClientAuth.WANT) return TlsConfig.ClientAuth.WANT;
    return TlsConfig.ClientAuth.NONE;
  }

  public Duration getRequestHeaderTimeout() {
    return requestHeaderTimeout;
  }

  public void setRequestHeaderTimeout(Duration requestHeaderTimeout) {
    this.requestHeaderTimeout = requestHeaderTimeout;
  }

  public int getMaxHeaderBytes() {
    return maxHeaderBytes;
  }

  public void setMaxHeaderBytes(int maxHeaderBytes) {
    this.maxHeaderBytes = maxHeaderBytes;
  }

  public int getMaxRequestBytes() {
    return maxRequestBytes;
  }

  public void setMaxRequestBytes(int maxRequestBytes) {
    this.maxRequestBytes = maxRequestBytes;
  }

  public int getWorkerThreads() {
    return workerThreads;
  }

  public void setWorkerThreads(int workerThreads) {
    this.workerThreads = workerThreads;
  }

  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  public void setIdleTimeout(Duration idleTimeout) {
    this.idleTimeout = idleTimeout;
  }

  public int getMaxRequestsPerConnection() {
    return maxRequestsPerConnection;
  }

  public void setMaxRequestsPerConnection(int maxRequestsPerConnection) {
    this.maxRequestsPerConnection = maxRequestsPerConnection;
  }

  public int getMaxHeaderCount() {
    return maxHeaderCount;
  }

  public void setMaxHeaderCount(int maxHeaderCount) {
    this.maxHeaderCount = maxHeaderCount;
  }

  public int getMaxTrailerBytes() {
    return maxTrailerBytes;
  }

  public void setMaxTrailerBytes(int maxTrailerBytes) {
    this.maxTrailerBytes = maxTrailerBytes;
  }

  public int getMaxTrailerCount() {
    return maxTrailerCount;
  }

  public void setMaxTrailerCount(int maxTrailerCount) {
    this.maxTrailerCount = maxTrailerCount;
  }

  public int getMaxParameterCount() {
    return maxParameterCount;
  }

  public void setMaxParameterCount(int maxParameterCount) {
    this.maxParameterCount = maxParameterCount;
  }

  public int getMaxParameterBytes() {
    return maxParameterBytes;
  }

  public void setMaxParameterBytes(int maxParameterBytes) {
    this.maxParameterBytes = maxParameterBytes;
  }

  public int getMaxCookieCount() {
    return maxCookieCount;
  }

  public void setMaxCookieCount(int maxCookieCount) {
    this.maxCookieCount = maxCookieCount;
  }

  public int getMaxCookieBytes() {
    return maxCookieBytes;
  }

  public void setMaxCookieBytes(int maxCookieBytes) {
    this.maxCookieBytes = maxCookieBytes;
  }

  public int getRequestBodyBufferBytes() {
    return requestBodyBufferBytes;
  }

  public void setRequestBodyBufferBytes(int requestBodyBufferBytes) {
    this.requestBodyBufferBytes = requestBodyBufferBytes;
  }

  public int getRequestBodyLowWaterBytes() {
    return requestBodyLowWaterBytes;
  }

  public void setRequestBodyLowWaterBytes(int requestBodyLowWaterBytes) {
    this.requestBodyLowWaterBytes = requestBodyLowWaterBytes;
  }

  public long getMaxResponseBytes() {
    return maxResponseBytes;
  }

  public int getResponseBodyBufferBytes() {
    return responseBodyBufferBytes;
  }

  public void setResponseBodyBufferBytes(int responseBodyBufferBytes) {
    this.responseBodyBufferBytes = responseBodyBufferBytes;
  }

  public int getResponseBodyLowWaterBytes() {
    return responseBodyLowWaterBytes;
  }

  public void setResponseBodyLowWaterBytes(int responseBodyLowWaterBytes) {
    this.responseBodyLowWaterBytes = responseBodyLowWaterBytes;
  }

  public void setMaxResponseBytes(long maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
  }

  public Duration getShutdownDrainTimeout() {
    return shutdownDrainTimeout;
  }

  public void setShutdownDrainTimeout(Duration shutdownDrainTimeout) {
    this.shutdownDrainTimeout = shutdownDrainTimeout;
  }

  public List<String> getTrustedProxies() {
    return new ArrayList<String>(trustedProxies);
  }

  public void setTrustedProxies(List<String> trustedProxies) {
    if (trustedProxies == null) {
      throw new IllegalArgumentException("trusted proxies must not be null");
    }
    this.trustedProxies = new ArrayList<String>(trustedProxies);
  }

  private String bindAddress() {
    InetAddress address = getAddress();
    return address == null ? "0.0.0.0" : address.getHostAddress();
  }

  private int sessionTimeoutSeconds() {
    Duration timeout = getSession().getTimeout();
    long seconds = timeout == null ? 30L * 60L : timeout.getSeconds();
    if (seconds < 0 || seconds > Integer.MAX_VALUE) {
      throw new WebServerException("invalid LingTong HTTP session timeout: " + timeout, null);
    }
    return (int) seconds;
  }

  private void configureSession(javax.servlet.ServletContext context) {
    Session session = getSession();
    SessionCookieConfig target = context.getSessionCookieConfig();
    Session.Cookie cookie = session.getCookie();
    if (cookie.getName() != null) target.setName(cookie.getName());
    if (cookie.getDomain() != null) target.setDomain(cookie.getDomain());
    if (cookie.getPath() != null) target.setPath(cookie.getPath());
    if (cookie.getComment() != null) target.setComment(cookie.getComment());
    if (cookie.getHttpOnly() != null) target.setHttpOnly(cookie.getHttpOnly());
    if (cookie.getSecure() != null) target.setSecure(cookie.getSecure());
    if (cookie.getMaxAge() != null) {
      long seconds = cookie.getMaxAge().getSeconds();
      if (seconds < -1L || seconds > Integer.MAX_VALUE) {
        throw new WebServerException(
            "invalid LingTong session cookie max age: " + cookie.getMaxAge(), null);
      }
      target.setMaxAge((int) seconds);
    }
    if (session.getTrackingModes() != null && !session.getTrackingModes().isEmpty()) {
      Set<SessionTrackingMode> modes = EnumSet.noneOf(SessionTrackingMode.class);
      for (Session.SessionTrackingMode mode : session.getTrackingModes()) {
        modes.add(SessionTrackingMode.valueOf(mode.name()));
      }
      context.setSessionTrackingModes(modes);
    }
  }

  private Map<String, String> mimeMappings() {
    Map<String, String> mappings = new LinkedHashMap<String, String>();
    for (org.springframework.boot.web.server.MimeMappings.Mapping mapping : getMimeMappings()) {
      mappings.put(mapping.getExtension(), mapping.getMimeType());
    }
    return mappings;
  }

  private Map<String, String> localeEncodingMappings() {
    Map<String, String> mappings = new LinkedHashMap<String, String>();
    for (Map.Entry<Locale, java.nio.charset.Charset> mapping :
        getLocaleCharsetMappings().entrySet()) {
      mappings.put(mapping.getKey().toString(), mapping.getValue().name());
    }
    return mappings;
  }

  private static void initialize(
      ServletContextInitializer[] initializers, javax.servlet.ServletContext servletContext)
      throws ServletException {
    for (ServletContextInitializer initializer : initializers) {
      initializer.onStartup(servletContext);
    }
  }

  private static WebServerException unsupported(String capability) {
    return new WebServerException("LingTong M1 does not support " + capability, null);
  }

  private static long positiveMillis(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new WebServerException("LingTong " + name + " must be positive", null);
    }
    try {
      long millis = duration.toMillis();
      if (millis <= 0) {
        throw new WebServerException("LingTong " + name + " must be at least 1ms", null);
      }
      return millis;
    } catch (ArithmeticException e) {
      throw new WebServerException("LingTong " + name + " is too large", e);
    }
  }

  private static long nonNegativeMillis(Duration duration, String name) {
    if (duration == null || duration.isNegative()) {
      throw new WebServerException("LingTong " + name + " must not be negative", null);
    }
    try {
      return duration.toMillis();
    } catch (ArithmeticException e) {
      throw new WebServerException("LingTong " + name + " is too large", e);
    }
  }

  private static String[] stringsOrEmpty(String[] values) {
    return values == null ? new String[0] : values;
  }

  /** 封装灵童 Web 服务端的状态与处理边界。 */
  private static final class LingTongWebServer implements WebServer {
    /** 运行时。 */
    private final LingTongRuntime runtime;

    private LingTongWebServer(LingTongRuntime runtime) {
      this.runtime = runtime;
    }

    @Override
    public void start() throws WebServerException {
      try {
        runtime.start();
      } catch (Exception e) {
        throw new WebServerException("Unable to start LingTong", e);
      }
    }

    @Override
    public void stop() throws WebServerException {
      runtime.close();
    }

    @Override
    public int getPort() {
      return runtime.port();
    }
  }
}
