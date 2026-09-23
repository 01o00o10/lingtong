/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.http.UpgradeChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;

/** Owns one JSR 356 client connection without depending on a server transport. */
/** 独立维护 JSR 356 客户端连接、读写线程和协议会话。 */
final class WebSocketClientConnection implements UpgradeChannel {
  /** 默认连接超时时长毫秒，单位为毫秒。 */
  private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
  /** 最大握手字节数，单位为字节。 */
  private static final int MAX_HANDSHAKE_BYTES = 64 * 1024;
  /** 可并发更新的线程标识符集合。 */
  private static final AtomicInteger THREAD_IDS = new AtomicInteger();
  /** 回调集合。 */
  private static final Executor CALLBACKS =
      new ThreadPoolExecutor(
          0,
          64,
          60L,
          TimeUnit.SECONDS,
          new java.util.concurrent.SynchronousQueue<Runnable>(),
          daemonFactory("lingtong-ws-client-callback-"),
          new ThreadPoolExecutor.AbortPolicy());

  /** 套接字。 */
  private final Socket socket;
  /** 输入。 */
  private final InputStream input;
  /** 输出。 */
  private final OutputStream output;
  /** 协议。 */
  private final WebSocketProtocol protocol;
  /** 写入器。 */
  private final ThreadPoolExecutor writer;
  /** 定时器。 */
  private final ScheduledThreadPoolExecutor timer;
  /** 可并发更新的打开。 */
  private final AtomicBoolean open = new AtomicBoolean(true);

  private WebSocketClientConnection(
      Socket socket, InputStream input, OutputStream output, WebSocketProtocol protocol) {
    this.socket = socket;
    this.input = input;
    this.output = output;
    this.protocol = protocol;
    this.writer =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(256),
            daemonFactory("lingtong-ws-client-write-"),
            new ThreadPoolExecutor.AbortPolicy());
    this.timer = new ScheduledThreadPoolExecutor(1, daemonFactory("lingtong-ws-client-timer-"));
    this.timer.setRemoveOnCancelPolicy(true);
  }

  /** 建立客户端握手并创建 JSR 356 Session；失败时释放套接字。 */
  static Session connect(
      LingTongServerContainer container, Object endpoint, ClientEndpointConfig config, URI uri)
      throws DeploymentException, IOException {
    if (http2Enabled(config)) {
      return Http2WebSocketClientConnection.connect(container, endpoint, config, uri);
    }
    Handshake handshake = openHandshake(config, uri);
    JsrEndpointHandler handler =
        new JsrEndpointHandler(
            container,
            endpoint,
            config,
            handshake.uri,
            handshake.subprotocol,
            handshake.compressed,
            Math.min(
                container.getDefaultMaxTextMessageBufferSize(),
                container.getDefaultMaxBinaryMessageBufferSize()));
    WebSocketProtocol protocol =
        WebSocketProtocol.client(
            handler,
            CALLBACKS,
            Math.min(
                container.getDefaultMaxTextMessageBufferSize(),
                container.getDefaultMaxBinaryMessageBufferSize()),
            handshake.subprotocol,
            handshake.compressed);
    WebSocketClientConnection connection =
        new WebSocketClientConnection(
            handshake.socket, handshake.input, handshake.output, protocol);
    try {
      protocol.onOpen(connection);
      connection.startReader();
      connection.timer.scheduleAtFixedRate(
          () -> protocol.onTimer(System.nanoTime()), 100L, 100L, TimeUnit.MILLISECONDS);
      return handler.awaitOpen();
    } catch (DeploymentException | IOException e) {
      connection.terminate(true);
      throw e;
    } catch (Exception e) {
      connection.terminate(true);
      throw new DeploymentException("client endpoint failed during onOpen", e);
    }
  }

  @Override
  public boolean isOpen() {
    return open.get() && !socket.isClosed();
  }

  @Override
  public void write(ByteBuffer data) throws IOException {
    try {
      writeAsync(data).toCompletableFuture().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while writing WebSocket client frame", e);
    } catch (ExecutionException e) {
      Throwable failure = e.getCause();
      if (failure instanceof IOException) throw (IOException) failure;
      if (failure instanceof RuntimeException) throw (RuntimeException) failure;
      throw new IOException("WebSocket client frame write failed", failure);
    }
  }

  @Override
  public CompletionStage<Void> writeAsync(ByteBuffer data) {
    CompletableFuture<Void> completion = new CompletableFuture<Void>();
    if (data == null) {
      completion.completeExceptionally(
          new IllegalArgumentException("WebSocket client output must not be null"));
      return completion;
    }
    if (!isOpen()) {
      completion.completeExceptionally(new IOException("WebSocket client is closed"));
      return completion;
    }
    ByteBuffer source = data.slice();
    byte[] copy = new byte[source.remaining()];
    source.get(copy);
    WriteTask task = new WriteTask(copy, completion);
    try {
      writer.execute(task);
    } catch (RuntimeException rejected) {
      completion.completeExceptionally(
          new IOException("WebSocket client write queue is full", rejected));
    }
    return completion;
  }

  @Override
  public void close() {
    terminate(true);
  }

  private void startReader() {
    Thread reader =
        daemonFactory("lingtong-ws-client-read-")
            .newThread(
                () -> {
                  byte[] buffer = new byte[8192];
                  try {
                    for (int count; isOpen() && (count = input.read(buffer)) >= 0; ) {
                      if (count > 0) protocol.onInput(ByteBuffer.wrap(buffer, 0, count));
                    }
                  } catch (IOException | RuntimeException failure) {
                    // Resource termination below reports the abnormal close exactly once.
                  } finally {
                    terminate(true);
                  }
                });
    reader.start();
  }

  private void terminate(boolean notifyProtocol) {
    if (!open.compareAndSet(true, false)) return;
    closeQuietly(socket);
    timer.shutdownNow();
    IOException failure = new IOException("WebSocket client connection is closed");
    for (Runnable task : writer.shutdownNow()) {
      if (task instanceof WriteTask) ((WriteTask) task).fail(failure);
    }
    if (notifyProtocol) protocol.onClosed();
  }

  /** 封装写入任务的状态与处理边界。 */
  private final class WriteTask implements Runnable {
    /** 字节数，单位为字节。 */
    private final byte[] bytes;
    /** 完成。 */
    private final CompletableFuture<Void> completion;

    private WriteTask(byte[] bytes, CompletableFuture<Void> completion) {
      this.bytes = bytes;
      this.completion = completion;
    }

    @Override
    public void run() {
      try {
        output.write(bytes);
        output.flush();
        completion.complete(null);
      } catch (Throwable failure) {
        completion.completeExceptionally(failure);
        terminate(true);
      }
    }

    private void fail(Throwable failure) {
      completion.completeExceptionally(failure);
    }
  }

  private static Handshake openHandshake(ClientEndpointConfig config, URI uri)
      throws DeploymentException, IOException {
    validateUri(uri);
    int timeout = connectTimeout(config);
    int maxRedirects = maxRedirects(config);
    boolean allowCrossOrigin = allowCrossOriginRedirect(config);
    Set<String> visited = new LinkedHashSet<String>();
    URI current = uri;
    int redirects = 0;
    while (true) {
      String redirectKey = current.normalize().toASCIIString();
      if (!visited.add(redirectKey)) {
        throw new DeploymentException("WebSocket redirect loop detected at " + current);
      }
      String host = current.getHost();
      int port = effectivePort(current);
      Socket socket = openSocket(config, current, host, port, timeout, false);
      try {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(timeout);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        String key = clientKey();
        Map<String, List<String>> headers = requestHeaders(config, current, key);
        config.getConfigurator().beforeRequest(headers);
        enforceHandshakeHeaders(headers, current, key, config);
        writeRequest(output, current, headers);
        ClientResponse response = readResponse(input);
        config.getConfigurator().afterResponse(response);
        if (isRedirect(response.status)) {
          if (redirects >= maxRedirects) {
            throw new DeploymentException("WebSocket redirect limit exceeded at " + current);
          }
          URI next = redirectTarget(current, response);
          validateRedirect(current, next, allowCrossOrigin);
          closeQuietly(socket);
          current = next;
          redirects++;
          continue;
        }
        Negotiated negotiated = validateResponse(response, key, config);
        socket.setSoTimeout(0);
        return new Handshake(
            socket, input, output, current, negotiated.subprotocol, negotiated.compressed);
      } catch (IOException | RuntimeException | DeploymentException failure) {
        closeQuietly(socket);
        throw failure;
      }
    }
  }

  static Socket openSocket(
      ClientEndpointConfig config,
      URI uri,
      String host,
      int port,
      int timeout,
      boolean requireHttp2)
      throws IOException, DeploymentException {
    Proxy proxy = clientProxy(config);
    Socket plain = new Socket();
    try {
      if (proxy == null) {
        plain.connect(new InetSocketAddress(host, port), timeout);
      } else {
        plain.connect((InetSocketAddress) proxy.address(), timeout);
        plain.setSoTimeout(timeout);
        establishProxyTunnel(config, plain, host, port);
      }
      if ("ws".equalsIgnoreCase(uri.getScheme())) return plain;
      Object configured =
          config.getUserProperties().get(LingTongServerContainer.CLIENT_SSL_CONTEXT_PROPERTY);
      if (configured != null && !(configured instanceof SSLContext)) {
        throw new DeploymentException(
            LingTongServerContainer.CLIENT_SSL_CONTEXT_PROPERTY + " must contain an SSLContext");
      }
      SSLContext context = configured == null ? defaultSslContext() : (SSLContext) configured;
      SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
      SSLParameters parameters = tls.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      tls.setSSLParameters(parameters);
      WebSocketClientAlpnProvider alpn = null;
      if (requireHttp2) {
        alpn = clientAlpnProvider(config);
        alpn.configure(tls, "h2");
      }
      tls.startHandshake();
      if (requireHttp2 && !"h2".equals(alpn.selectedProtocol(tls))) {
        throw new DeploymentException("WebSocket HTTP/2 client did not negotiate h2");
      }
      return tls;
    } catch (IOException | RuntimeException | DeploymentException failure) {
      closeQuietly(plain);
      throw failure;
    }
  }

  private static WebSocketClientAlpnProvider clientAlpnProvider(ClientEndpointConfig config)
      throws DeploymentException {
    Object value =
        config.getUserProperties().get(LingTongServerContainer.CLIENT_ALPN_PROVIDER_PROPERTY);
    if (value == null) return WebSocketClientAlpnProvider.jdk();
    if (!(value instanceof WebSocketClientAlpnProvider)) {
      throw new DeploymentException(
          LingTongServerContainer.CLIENT_ALPN_PROVIDER_PROPERTY
              + " must contain a WebSocketClientAlpnProvider");
    }
    return (WebSocketClientAlpnProvider) value;
  }

  private static boolean http2Enabled(ClientEndpointConfig config) throws DeploymentException {
    Object value = config.getUserProperties().get(LingTongServerContainer.CLIENT_HTTP2_PROPERTY);
    if (value == null) return false;
    if (!(value instanceof Boolean)) {
      throw new DeploymentException(
          LingTongServerContainer.CLIENT_HTTP2_PROPERTY + " must contain a Boolean");
    }
    return (Boolean) value;
  }

  private static Proxy clientProxy(ClientEndpointConfig config) throws DeploymentException {
    Object value = config.getUserProperties().get(LingTongServerContainer.CLIENT_PROXY_PROPERTY);
    if (value == null || value == Proxy.NO_PROXY) return null;
    if (!(value instanceof Proxy)
        || ((Proxy) value).type() != Proxy.Type.HTTP
        || !(((Proxy) value).address() instanceof InetSocketAddress)) {
      throw new DeploymentException(
          LingTongServerContainer.CLIENT_PROXY_PROPERTY + " must contain an HTTP Proxy");
    }
    return (Proxy) value;
  }

  private static void establishProxyTunnel(
      ClientEndpointConfig config, Socket socket, String host, int port)
      throws IOException, DeploymentException {
    String target = connectAuthority(host, port);
    StringBuilder request =
        new StringBuilder()
            .append("CONNECT ")
            .append(target)
            .append(" HTTP/1.1\r\n")
            .append("Host: ")
            .append(target)
            .append("\r\n")
            .append("Proxy-Connection: keep-alive\r\n");
    Object authorization =
        config.getUserProperties().get(LingTongServerContainer.CLIENT_PROXY_AUTHORIZATION_PROPERTY);
    if (authorization != null) {
      if (!(authorization instanceof String)) {
        throw new DeploymentException(
            LingTongServerContainer.CLIENT_PROXY_AUTHORIZATION_PROPERTY + " must contain a String");
      }
      validHeader((String) authorization);
      request.append("Proxy-Authorization: ").append(authorization).append("\r\n");
    }
    request.append("\r\n");
    OutputStream output = socket.getOutputStream();
    output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
    output.flush();
    ClientResponse response = readResponse(socket.getInputStream());
    if (response.status != 200) {
      throw new DeploymentException("HTTP proxy returned status " + response.status);
    }
  }

  private static SSLContext defaultSslContext() throws IOException {
    try {
      return SSLContext.getDefault();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException("default TLS context is unavailable", e);
    }
  }

  private static Map<String, List<String>> requestHeaders(
      ClientEndpointConfig config, URI uri, String key) {
    Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
    put(headers, "Host", authority(uri));
    put(headers, "Upgrade", "websocket");
    put(headers, "Connection", "Upgrade");
    put(headers, "Sec-WebSocket-Key", key);
    put(headers, "Sec-WebSocket-Version", "13");
    if (!config.getPreferredSubprotocols().isEmpty()) {
      put(headers, "Sec-WebSocket-Protocol", join(config.getPreferredSubprotocols()));
    }
    if (!config.getExtensions().isEmpty()) {
      List<String> extensions = new ArrayList<String>();
      for (Extension extension : config.getExtensions()) extensions.add(extension(extension));
      put(headers, "Sec-WebSocket-Extensions", join(extensions));
    }
    return headers;
  }

  private static void enforceHandshakeHeaders(
      Map<String, List<String>> headers, URI uri, String key, ClientEndpointConfig config) {
    replaceCaseInsensitive(headers, "Host", authority(uri));
    replaceCaseInsensitive(headers, "Upgrade", "websocket");
    replaceCaseInsensitive(headers, "Connection", "Upgrade");
    replaceCaseInsensitive(headers, "Sec-WebSocket-Key", key);
    replaceCaseInsensitive(headers, "Sec-WebSocket-Version", "13");
    if (!config.getPreferredSubprotocols().isEmpty()) {
      replaceCaseInsensitive(
          headers, "Sec-WebSocket-Protocol", join(config.getPreferredSubprotocols()));
    } else {
      removeCaseInsensitive(headers, "Sec-WebSocket-Protocol");
    }
    if (!config.getExtensions().isEmpty()) {
      List<String> extensions = new ArrayList<String>();
      for (Extension extension : config.getExtensions()) {
        extensions.add(extension(extension));
      }
      replaceCaseInsensitive(headers, "Sec-WebSocket-Extensions", join(extensions));
    } else {
      removeCaseInsensitive(headers, "Sec-WebSocket-Extensions");
    }
  }

  private static void writeRequest(OutputStream output, URI uri, Map<String, List<String>> headers)
      throws IOException {
    StringBuilder request = new StringBuilder();
    String target = uri.getRawPath();
    if (target == null || target.isEmpty()) target = "/";
    if (uri.getRawQuery() != null) target += "?" + uri.getRawQuery();
    request.append("GET ").append(target).append(" HTTP/1.1\r\n");
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (!validHeaderName(entry.getKey()) || entry.getValue() == null) {
        throw new IOException("invalid WebSocket handshake header name");
      }
      for (String value : entry.getValue()) {
        validHeader(value);
        request.append(entry.getKey()).append(": ").append(value).append("\r\n");
      }
    }
    request.append("\r\n");
    output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
    output.flush();
  }

  private static ClientResponse readResponse(InputStream input)
      throws IOException, DeploymentException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int matched = 0;
    while (bytes.size() < MAX_HANDSHAKE_BYTES) {
      int value = input.read();
      if (value < 0) throw new IOException("connection closed during WebSocket handshake");
      bytes.write(value);
      if (value == (matched == 0 || matched == 2 ? '\r' : '\n')) matched++;
      else matched = value == '\r' ? 1 : 0;
      if (matched == 4) break;
    }
    if (matched != 4) throw new DeploymentException("WebSocket handshake headers are too large");
    String[] lines = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
    String[] status = lines[0].split(" ", 3);
    if (status.length < 2 || !"HTTP/1.1".equals(status[0])) {
      throw new DeploymentException("invalid WebSocket handshake status line");
    }
    int code;
    try {
      code = Integer.parseInt(status[1]);
    } catch (NumberFormatException e) {
      throw new DeploymentException("invalid WebSocket handshake status", e);
    }
    Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon <= 0) throw new DeploymentException("invalid WebSocket response header");
      String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
      if (!validHeaderName(name)) {
        throw new DeploymentException("invalid WebSocket response header name");
      }
      String value = lines[i].substring(colon + 1).trim();
      headers.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
    }
    return new ClientResponse(code, headers);
  }

  private static Negotiated validateResponse(
      ClientResponse response, String key, ClientEndpointConfig config) throws DeploymentException {
    if (response.status != 101) {
      throw new DeploymentException("WebSocket server returned HTTP " + response.status);
    }
    if (!containsToken(response.headers.get("upgrade"), "websocket")
        || !containsToken(response.headers.get("connection"), "upgrade")) {
      throw new DeploymentException(
          "WebSocket server did not accept the Upgrade: " + response.headers);
    }
    if (!WebSocketHandshake.acceptKey(key)
        .equals(single(response.headers.get("sec-websocket-accept")))) {
      throw new DeploymentException("invalid Sec-WebSocket-Accept response");
    }
    List<String> selectedProtocols = commaTokens(response.headers.get("sec-websocket-protocol"));
    if (selectedProtocols.size() > 1) {
      throw new DeploymentException("server selected multiple WebSocket subprotocols");
    }
    String subprotocol = selectedProtocols.isEmpty() ? null : selectedProtocols.get(0);
    if (subprotocol == null) subprotocol = "";
    if (!subprotocol.isEmpty() && !config.getPreferredSubprotocols().contains(subprotocol)) {
      throw new DeploymentException("server selected an unrequested WebSocket subprotocol");
    }
    boolean compressed = false;
    List<String> extensions = commaTokens(response.headers.get("sec-websocket-extensions"));
    if (extensions.size() > 1) {
      throw new DeploymentException("server selected multiple WebSocket extensions");
    }
    if (!extensions.isEmpty()) {
      validateCompressionExtension(extensions.get(0), config);
      compressed = true;
    }
    return new Negotiated(subprotocol, compressed);
  }

  static void validateUri(URI uri) throws DeploymentException {
    if (uri == null
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || !("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))) {
      throw new DeploymentException("WebSocket client URI must use ws or wss with a host");
    }
    if (uri.getFragment() != null) {
      throw new DeploymentException("WebSocket client URI must not contain a fragment");
    }
  }

  static int connectTimeout(ClientEndpointConfig config) throws DeploymentException {
    Object value =
        config.getUserProperties().get(LingTongServerContainer.CLIENT_CONNECT_TIMEOUT_PROPERTY);
    if (value == null) return DEFAULT_CONNECT_TIMEOUT_MILLIS;
    if (!(value instanceof Number)
        || ((Number) value).longValue() <= 0L
        || ((Number) value).longValue() > Integer.MAX_VALUE) {
      throw new DeploymentException(
          LingTongServerContainer.CLIENT_CONNECT_TIMEOUT_PROPERTY + " must be a positive integer");
    }
    return ((Number) value).intValue();
  }

  private static int maxRedirects(ClientEndpointConfig config) throws DeploymentException {
    Object value =
        config.getUserProperties().get(LingTongServerContainer.CLIENT_MAX_REDIRECTS_PROPERTY);
    if (value == null) return 0;
    if (!(value instanceof Number)) {
      throw new DeploymentException(
          LingTongServerContainer.CLIENT_MAX_REDIRECTS_PROPERTY
              + " must be an integer from 0 through 20");
    }
    Number number = (Number) value;
    long redirects = number.longValue();
    if (redirects < 0L || redirects > 20L || number.doubleValue() != redirects) {
      throw new DeploymentException(
          LingTongServerContainer.CLIENT_MAX_REDIRECTS_PROPERTY
              + " must be an integer from 0 through 20");
    }
    return (int) redirects;
  }

  private static boolean allowCrossOriginRedirect(ClientEndpointConfig config)
      throws DeploymentException {
    Object value =
        config
            .getUserProperties()
            .get(LingTongServerContainer.CLIENT_ALLOW_CROSS_ORIGIN_REDIRECT_PROPERTY);
    if (value == null) return false;
    if (!(value instanceof Boolean)) {
      throw new DeploymentException(
          LingTongServerContainer.CLIENT_ALLOW_CROSS_ORIGIN_REDIRECT_PROPERTY
              + " must contain a Boolean");
    }
    return (Boolean) value;
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  private static URI redirectTarget(URI current, ClientResponse response)
      throws DeploymentException {
    String location = single(response.headers.get("location"));
    if (location == null || location.trim().isEmpty()) {
      throw new DeploymentException("WebSocket redirect must contain exactly one Location header");
    }
    try {
      URI target = current.resolve(new URI(location.trim()));
      validateUri(target);
      return target;
    } catch (java.net.URISyntaxException e) {
      throw new DeploymentException("invalid WebSocket redirect Location", e);
    }
  }

  private static void validateRedirect(URI current, URI target, boolean allowCrossOrigin)
      throws DeploymentException {
    if ("wss".equalsIgnoreCase(current.getScheme()) && "ws".equalsIgnoreCase(target.getScheme())) {
      throw new DeploymentException("WebSocket redirect must not downgrade wss to ws");
    }
    if (!allowCrossOrigin && !sameOrigin(current, target)) {
      throw new DeploymentException("cross-origin WebSocket redirect is disabled");
    }
  }

  private static boolean sameOrigin(URI left, URI right) {
    return left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && effectivePort(left) == effectivePort(right);
  }

  static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) return uri.getPort();
    return "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  static String authority(URI uri) {
    String host = uri.getHost();
    if (host.indexOf(':') >= 0) host = "[" + host + "]";
    int port = uri.getPort();
    if (port < 0
        || port == 80 && "ws".equalsIgnoreCase(uri.getScheme())
        || port == 443 && "wss".equalsIgnoreCase(uri.getScheme())) return host;
    return host + ":" + port;
  }

  private static String connectAuthority(String host, int port) {
    if (host.indexOf(':') >= 0) host = "[" + host + "]";
    return host + ":" + port;
  }

  private static String clientKey() {
    byte[] nonce = new byte[16];
    new SecureRandom().nextBytes(nonce);
    return Base64.getEncoder().encodeToString(nonce);
  }

  private static String extension(Extension extension) {
    StringBuilder value = new StringBuilder(extension.getName());
    for (Extension.Parameter parameter : extension.getParameters()) {
      value.append("; ").append(parameter.getName());
      if (parameter.getValue() != null) value.append('=').append(parameter.getValue());
    }
    return value.toString();
  }

  private static boolean requested(ClientEndpointConfig config, String name) {
    for (Extension extension : config.getExtensions()) {
      if (name.equalsIgnoreCase(extension.getName())) return true;
    }
    return false;
  }

  private static void validateCompressionExtension(String value, ClientEndpointConfig config)
      throws DeploymentException {
    String[] parts = value.split(";", -1);
    if (!"permessage-deflate".equalsIgnoreCase(parts[0].trim())
        || !requested(config, "permessage-deflate")) {
      throw new DeploymentException("server selected an unsupported extension");
    }
    boolean serverNoContext = false;
    boolean clientNoContext = false;
    for (int i = 1; i < parts.length; i++) {
      String parameter = parts[i].trim().toLowerCase(Locale.ROOT);
      if ("server_no_context_takeover".equals(parameter) && !serverNoContext) {
        serverNoContext = true;
      } else if ("client_no_context_takeover".equals(parameter) && !clientNoContext) {
        clientNoContext = true;
      } else {
        throw new DeploymentException(
            "server selected an unsupported permessage-deflate parameter");
      }
    }
    if (!serverNoContext || !clientNoContext) {
      throw new DeploymentException(
          "permessage-deflate requires both no-context-takeover parameters");
    }
  }

  private static boolean containsToken(List<String> values, String expected) {
    if (values == null) return false;
    for (String value : values) {
      for (String token : value.split(",")) {
        if (expected.equalsIgnoreCase(token.trim())) return true;
      }
    }
    return false;
  }

  private static List<String> commaTokens(List<String> values) throws DeploymentException {
    if (values == null) return Collections.emptyList();
    List<String> result = new ArrayList<String>();
    for (String value : values) {
      for (String token : value.split(",", -1)) {
        if (token.trim().isEmpty()) {
          throw new DeploymentException("empty WebSocket handshake token");
        }
        result.add(token.trim());
      }
    }
    return result;
  }

  private static String single(List<String> values) {
    return values == null || values.size() != 1 ? null : values.get(0);
  }

  private static void put(Map<String, List<String>> headers, String name, String value) {
    headers.put(name, new ArrayList<String>(Collections.singletonList(value)));
  }

  private static void replaceCaseInsensitive(
      Map<String, List<String>> headers, String name, String value) {
    removeCaseInsensitive(headers, name);
    put(headers, name, value);
  }

  private static void removeCaseInsensitive(Map<String, List<String>> headers, String name) {
    List<String> found = new ArrayList<String>();
    for (String existing : headers.keySet()) {
      if (name.equalsIgnoreCase(existing)) found.add(existing);
    }
    for (String existing : found) headers.remove(existing);
  }

  private static String join(List<String> values) {
    StringBuilder result = new StringBuilder();
    for (String value : values) {
      if (result.length() > 0) result.append(", ");
      result.append(value);
    }
    return result.toString();
  }

  private static void validHeader(String value) throws IOException {
    if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IOException("invalid WebSocket handshake header");
    }
  }

  private static boolean validHeaderName(String value) {
    if (value == null || value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      if (!(character >= 'a' && character <= 'z'
          || character >= 'A' && character <= 'Z'
          || character >= '0' && character <= '9'
          || "!#$%&'*+-.^_`|~".indexOf(character) >= 0)) return false;
    }
    return true;
  }

  private static ThreadFactory daemonFactory(final String prefix) {
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + THREAD_IDS.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }

  /** 封装客户端响应的状态与处理边界。 */
  private static final class ClientResponse implements HandshakeResponse {
    /** 状态码。 */
    private final int status;
    /** 按键索引的头部集合。 */
    private final Map<String, List<String>> headers;

    private ClientResponse(int status, Map<String, List<String>> source) {
      this.status = status;
      Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
      for (Map.Entry<String, List<String>> entry : source.entrySet()) {
        copy.put(
            entry.getKey(), Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
      }
      this.headers = Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<String, List<String>> getHeaders() {
      return headers;
    }
  }

  /** 封装协商后的的状态与处理边界。 */
  private static class Negotiated {
    /** 子协议。 */
    final String subprotocol;
    /** 已压缩，布尔标志。 */
    final boolean compressed;

    private Negotiated(String subprotocol, boolean compressed) {
      this.subprotocol = subprotocol;
      this.compressed = compressed;
    }
  }

  /** 封装握手的状态与处理边界。 */
  private static final class Handshake extends Negotiated {
    /** 套接字。 */
    private final Socket socket;
    /** 输入。 */
    private final InputStream input;
    /** 输出。 */
    private final OutputStream output;
    /** 客户端握手目标 URI。 */
    private final URI uri;

    private Handshake(
        Socket socket,
        InputStream input,
        OutputStream output,
        URI uri,
        String subprotocol,
        boolean compressed) {
      super(subprotocol, compressed);
      this.socket = socket;
      this.input = input;
      this.output = output;
      this.uri = uri;
    }
  }
}
