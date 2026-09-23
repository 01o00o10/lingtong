/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

/** 一个监听器的不可变网络配置；创建时统一校验容量与时限。 */
public final class TransportConfig {
  /** 请求头读取超时的默认值，单位毫秒。 */
  private static final long DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS = 10_000L;
  /** 空闲连接超时的默认值，单位毫秒。 */
  private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000L;
  /** 单连接最多处理的请求数默认值。 */
  private static final int DEFAULT_MAX_REQUESTS_PER_CONNECTION = 1_000;
  /** 请求体邮箱默认容量，单位字节。 */
  private static final int DEFAULT_REQUEST_BODY_BUFFER_BYTES = 64 * 1024;
  /** 请求头字段个数默认上限。 */
  private static final int DEFAULT_MAX_HEADER_COUNT = 100;
  /** Trailer 总字节数默认上限。 */
  private static final int DEFAULT_MAX_TRAILER_BYTES = 8 * 1024;
  /** Trailer 字段个数默认上限。 */
  private static final int DEFAULT_MAX_TRAILER_COUNT = 100;

  /** 监听绑定地址。 */
  private final String bindAddress;
  /** 监听端口；0 表示由操作系统分配。 */
  private final int port;
  /** Selector/I/O 分片数，不等于业务工作线程数。 */
  private final int ioShards;
  /** I/O 缓冲池中单个读取块的大小，单位字节。 */
  private final int readBufferSize;
  /** 每个 I/O 分片的可用缓冲块数量。 */
  private final int buffersPerShard;
  /** I/O 分片的命令队列容量。 */
  private final int commandQueueCapacity;
  /** 单次请求头总字节预算。 */
  private final int maxHeaderBytes;
  /** 单次请求体总字节预算。 */
  private final int maxBodyBytes;
  /** 单次请求头字段个数预算。 */
  private final int maxHeaderCount;
  /** 单次请求 Trailer 总字节预算。 */
  private final int maxTrailerBytes;
  /** 单次请求 Trailer 字段个数预算。 */
  private final int maxTrailerCount;
  /** 请求体生产者与 Servlet 消费者之间的邮箱容量，单位字节。 */
  private final int requestBodyBufferBytes;
  /** 邮箱降到此水位后通知 I/O 分片恢复读，单位字节。 */
  private final int requestBodyLowWaterBytes;
  /** 请求头读取时限，单位毫秒。 */
  private final long requestHeaderTimeoutMillis;
  /** 连接空闲时限，单位毫秒。 */
  private final long idleTimeoutMillis;
  /** 一条持久连接上允许处理的请求总数。 */
  private final int maxRequestsPerConnection;
  /** 响应压缩策略。 */
  private final CompressionConfig compression;
  /** 可信代理与转发头策略。 */
  private final ProxyConfig proxy;
  /** TLS 配置；disabled() 表示明文监听。 */
  private final TlsConfig tls;
  /** 是否允许 HTTP/2 协议入口。 */
  private final boolean http2Enabled;

  /** 最简构造使用回环地址、默认超时、无压缩、无 TLS。 */
  public TransportConfig(
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes) {
    this(
        "127.0.0.1",
        port,
        ioShards,
        readBufferSize,
        buffersPerShard,
        commandQueueCapacity,
        maxHeaderBytes,
        maxBodyBytes,
        DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS,
        DEFAULT_IDLE_TIMEOUT_MILLIS,
        DEFAULT_MAX_REQUESTS_PER_CONNECTION,
        CompressionConfig.disabled());
  }

  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes) {
    this(
        bindAddress,
        port,
        ioShards,
        readBufferSize,
        buffersPerShard,
        commandQueueCapacity,
        maxHeaderBytes,
        maxBodyBytes,
        DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS,
        DEFAULT_IDLE_TIMEOUT_MILLIS,
        DEFAULT_MAX_REQUESTS_PER_CONNECTION,
        CompressionConfig.disabled());
  }

  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes,
      long requestHeaderTimeoutMillis,
      long idleTimeoutMillis,
      int maxRequestsPerConnection) {
    this(
        bindAddress,
        port,
        ioShards,
        readBufferSize,
        buffersPerShard,
        commandQueueCapacity,
        maxHeaderBytes,
        maxBodyBytes,
        requestHeaderTimeoutMillis,
        idleTimeoutMillis,
        maxRequestsPerConnection,
        CompressionConfig.disabled());
  }

  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes,
      long requestHeaderTimeoutMillis,
      long idleTimeoutMillis,
      int maxRequestsPerConnection,
      CompressionConfig compression) {
    this(
        bindAddress,
        port,
        ioShards,
        readBufferSize,
        buffersPerShard,
        commandQueueCapacity,
        maxHeaderBytes,
        maxBodyBytes,
        defaultRequestBodyBufferBytes(maxBodyBytes),
        defaultRequestBodyLowWaterBytes(maxBodyBytes),
        requestHeaderTimeoutMillis,
        idleTimeoutMillis,
        maxRequestsPerConnection,
        compression,
        ProxyConfig.disabled());
  }

  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes,
      int requestBodyBufferBytes,
      int requestBodyLowWaterBytes,
      long requestHeaderTimeoutMillis,
      long idleTimeoutMillis,
      int maxRequestsPerConnection,
      CompressionConfig compression) {
    this(
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
        compression,
        ProxyConfig.disabled());
  }

  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes,
      int requestBodyBufferBytes,
      int requestBodyLowWaterBytes,
      long requestHeaderTimeoutMillis,
      long idleTimeoutMillis,
      int maxRequestsPerConnection,
      CompressionConfig compression,
      ProxyConfig proxy) {
    this(
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
        compression,
        proxy,
        DEFAULT_MAX_HEADER_COUNT,
        DEFAULT_MAX_TRAILER_BYTES,
        DEFAULT_MAX_TRAILER_COUNT,
        TlsConfig.disabled());
  }

  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes,
      int requestBodyBufferBytes,
      int requestBodyLowWaterBytes,
      long requestHeaderTimeoutMillis,
      long idleTimeoutMillis,
      int maxRequestsPerConnection,
      CompressionConfig compression,
      ProxyConfig proxy,
      int maxHeaderCount,
      int maxTrailerBytes,
      int maxTrailerCount) {
    this(
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
        compression,
        proxy,
        maxHeaderCount,
        maxTrailerBytes,
        maxTrailerCount,
        TlsConfig.disabled());
  }

  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes,
      int requestBodyBufferBytes,
      int requestBodyLowWaterBytes,
      long requestHeaderTimeoutMillis,
      long idleTimeoutMillis,
      int maxRequestsPerConnection,
      CompressionConfig compression,
      ProxyConfig proxy,
      int maxHeaderCount,
      int maxTrailerBytes,
      int maxTrailerCount,
      TlsConfig tls) {
    this(
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
        compression,
        proxy,
        maxHeaderCount,
        maxTrailerBytes,
        maxTrailerCount,
        tls,
        false);
  }

  /** 完整构造负责校验全部网络上限；其他重载委托到这里。 */
  public TransportConfig(
      String bindAddress,
      int port,
      int ioShards,
      int readBufferSize,
      int buffersPerShard,
      int commandQueueCapacity,
      int maxHeaderBytes,
      int maxBodyBytes,
      int requestBodyBufferBytes,
      int requestBodyLowWaterBytes,
      long requestHeaderTimeoutMillis,
      long idleTimeoutMillis,
      int maxRequestsPerConnection,
      CompressionConfig compression,
      ProxyConfig proxy,
      int maxHeaderCount,
      int maxTrailerBytes,
      int maxTrailerCount,
      TlsConfig tls,
      boolean http2Enabled) {
    if (bindAddress == null || bindAddress.trim().isEmpty()) {
      throw new IllegalArgumentException("bindAddress must not be empty");
    }
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be between 0 and 65535");
    }
    if (ioShards <= 0
        || readBufferSize <= 0
        || buffersPerShard <= 0
        || commandQueueCapacity <= 0
        || maxHeaderBytes <= 0
        || maxBodyBytes < 0
        || requestBodyBufferBytes <= 0
        || requestBodyLowWaterBytes < 0
        || requestBodyLowWaterBytes >= requestBodyBufferBytes
        || requestHeaderTimeoutMillis <= 0
        || idleTimeoutMillis <= 0
        || maxRequestsPerConnection <= 0
        || compression == null
        || proxy == null
        || tls == null
        || maxHeaderCount <= 0
        || maxTrailerBytes <= 0
        || maxTrailerCount < 0) {
      throw new IllegalArgumentException("transport limits are invalid");
    }
    this.bindAddress = bindAddress;
    this.port = port;
    this.ioShards = ioShards;
    this.readBufferSize = readBufferSize;
    this.buffersPerShard = buffersPerShard;
    this.commandQueueCapacity = commandQueueCapacity;
    this.maxHeaderBytes = maxHeaderBytes;
    this.maxBodyBytes = maxBodyBytes;
    this.maxHeaderCount = maxHeaderCount;
    this.maxTrailerBytes = maxTrailerBytes;
    this.maxTrailerCount = maxTrailerCount;
    this.requestBodyBufferBytes = requestBodyBufferBytes;
    this.requestBodyLowWaterBytes = requestBodyLowWaterBytes;
    this.requestHeaderTimeoutMillis = requestHeaderTimeoutMillis;
    this.idleTimeoutMillis = idleTimeoutMillis;
    this.maxRequestsPerConnection = maxRequestsPerConnection;
    this.compression = compression;
    this.proxy = proxy;
    this.tls = tls;
    this.http2Enabled = http2Enabled;
  }

  public String bindAddress() {
    return bindAddress;
  }

  public int port() {
    return port;
  }

  public int ioShards() {
    return ioShards;
  }

  public int readBufferSize() {
    return readBufferSize;
  }

  public int buffersPerShard() {
    return buffersPerShard;
  }

  public int commandQueueCapacity() {
    return commandQueueCapacity;
  }

  public int maxHeaderBytes() {
    return maxHeaderBytes;
  }

  public int maxBodyBytes() {
    return maxBodyBytes;
  }

  public int maxHeaderCount() {
    return maxHeaderCount;
  }

  public int maxTrailerBytes() {
    return maxTrailerBytes;
  }

  public int maxTrailerCount() {
    return maxTrailerCount;
  }

  public int requestBodyBufferBytes() {
    return requestBodyBufferBytes;
  }

  public int requestBodyLowWaterBytes() {
    return requestBodyLowWaterBytes;
  }

  public long requestHeaderTimeoutMillis() {
    return requestHeaderTimeoutMillis;
  }

  public long idleTimeoutMillis() {
    return idleTimeoutMillis;
  }

  public int maxRequestsPerConnection() {
    return maxRequestsPerConnection;
  }

  public CompressionConfig compression() {
    return compression;
  }

  public ProxyConfig proxy() {
    return proxy;
  }

  public TlsConfig tls() {
    return tls;
  }

  public boolean http2Enabled() {
    return http2Enabled;
  }

  private static int defaultRequestBodyBufferBytes(int maxBodyBytes) {
    return Math.max(1, Math.min(DEFAULT_REQUEST_BODY_BUFFER_BYTES, Math.max(1, maxBodyBytes)));
  }

  private static int defaultRequestBodyLowWaterBytes(int maxBodyBytes) {
    return defaultRequestBodyBufferBytes(maxBodyBytes) / 2;
  }
}
