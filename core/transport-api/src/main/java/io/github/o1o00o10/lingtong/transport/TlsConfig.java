/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;

/** TLS 监听配置；证书材料由调用方组装到 SSLContext。 */
public final class TlsConfig {
  /** 客户端证书的要求程度。 */
  public enum ClientAuth {
    /** 不请求客户端证书。 */
    NONE,
    /** 请求但不强制客户端提供证书。 */
    WANT,
    /** 强制客户端提供有效证书。 */
    NEED
  }

  /** 默认握手时限，单位毫秒。 */
  private static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000L;
  /** 每条 TLS 连接可使用的缓冲预算默认上限，单位字节。 */
  private static final int DEFAULT_MAX_BUFFER_BYTES = 256 * 1024;

  /** 为 null 表示未启用 TLS。 */
  private final SSLContext sslContext;
  /** NONE/WANT/NEED 客户端证书策略。 */
  private final ClientAuth clientAuth;
  /** 允许的 TLS 协议版本；空列表沿用 Provider 默认值。 */
  private final List<String> protocols;
  /** 允许的密码套件；空列表沿用 Provider 默认值。 */
  private final List<String> cipherSuites;
  /** TLS 握手超时，单位毫秒。 */
  private final long handshakeTimeoutMillis;
  /** 单连接 TLS 缓冲预算上限，单位字节。 */
  private final int maxBufferBytes;
  /** HTTP/2 等协议在 TLS 握手中使用的 ALPN 适配器。 */
  private final AlpnProvider alpnProvider;

  private TlsConfig() {
    this.sslContext = null;
    this.clientAuth = ClientAuth.NONE;
    this.protocols = Collections.emptyList();
    this.cipherSuites = Collections.emptyList();
    this.handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
    this.maxBufferBytes = DEFAULT_MAX_BUFFER_BYTES;
    this.alpnProvider = AlpnProvider.jdk();
  }

  public TlsConfig(
      SSLContext sslContext,
      ClientAuth clientAuth,
      List<String> protocols,
      List<String> cipherSuites,
      long handshakeTimeoutMillis,
      int maxBufferBytes) {
    this(
        sslContext,
        clientAuth,
        protocols,
        cipherSuites,
        handshakeTimeoutMillis,
        maxBufferBytes,
        AlpnProvider.jdk());
  }

  public TlsConfig(
      SSLContext sslContext,
      ClientAuth clientAuth,
      List<String> protocols,
      List<String> cipherSuites,
      long handshakeTimeoutMillis,
      int maxBufferBytes,
      AlpnProvider alpnProvider) {
    if (sslContext == null) {
      throw new IllegalArgumentException("SSLContext must not be null");
    }
    if (clientAuth == null
        || protocols == null
        || cipherSuites == null
        || handshakeTimeoutMillis <= 0L
        || maxBufferBytes <= 0
        || alpnProvider == null) {
      throw new IllegalArgumentException("TLS configuration is invalid");
    }
    this.sslContext = sslContext;
    this.clientAuth = clientAuth;
    this.protocols = immutableValues(protocols, "TLS protocol");
    this.cipherSuites = immutableValues(cipherSuites, "TLS cipher suite");
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.maxBufferBytes = maxBufferBytes;
    this.alpnProvider = alpnProvider;
  }

  /** 生成不启用 TLS 的配置，而非创建空 SSLContext。 */
  public static TlsConfig disabled() {
    return new TlsConfig();
  }

  public boolean enabled() {
    return sslContext != null;
  }

  public SSLContext sslContext() {
    return sslContext;
  }

  public ClientAuth clientAuth() {
    return clientAuth;
  }

  public List<String> protocols() {
    return protocols;
  }

  public List<String> cipherSuites() {
    return cipherSuites;
  }

  public long handshakeTimeoutMillis() {
    return handshakeTimeoutMillis;
  }

  public int maxBufferBytes() {
    return maxBufferBytes;
  }

  public AlpnProvider alpnProvider() {
    return alpnProvider;
  }

  private static List<String> immutableValues(List<String> values, String label) {
    ArrayList<String> copy = new ArrayList<String>(values.size());
    for (String value : values) {
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException(label + " must not be empty");
      }
      copy.add(value);
    }
    return Collections.unmodifiableList(copy);
  }
}
