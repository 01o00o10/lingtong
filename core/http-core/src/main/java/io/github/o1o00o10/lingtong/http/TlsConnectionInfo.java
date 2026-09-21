/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.security.cert.X509Certificate;

/** 一次 TLS 连接的握手结果，供 Servlet 安全属性与证书认证使用。 */
public final class TlsConnectionInfo {
    /** 协商的 TLS 协议版本。 */
    private final String protocol;
    /** 协商的密码套件名称。 */
    private final String cipherSuite;
    /** 对外公布的密钥长度，单位位。 */
    private final int keySize;
    /** TLS Session 标识。 */
    private final String sessionId;
    /** 客户端证书链；构造和读取时都复制数组。 */
    private final X509Certificate[] peerCertificates;

    public TlsConnectionInfo(
            String protocol,
            String cipherSuite,
            int keySize,
            String sessionId,
            X509Certificate[] peerCertificates) {
        if (empty(protocol) || empty(cipherSuite) || keySize < 0 || sessionId == null
                || peerCertificates == null) {
            throw new IllegalArgumentException("invalid TLS connection information");
        }
        this.protocol = protocol;
        this.cipherSuite = cipherSuite;
        this.keySize = keySize;
        this.sessionId = sessionId;
        this.peerCertificates = peerCertificates.clone();
    }

    public String protocol() {
        return protocol;
    }

    public String cipherSuite() {
        return cipherSuite;
    }

    public int keySize() {
        return keySize;
    }

    public String sessionId() {
        return sessionId;
    }

    public X509Certificate[] peerCertificates() {
        return peerCertificates.clone();
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}
