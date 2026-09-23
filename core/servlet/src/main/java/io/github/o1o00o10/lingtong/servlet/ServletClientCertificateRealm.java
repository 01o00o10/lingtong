/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.security.cert.X509Certificate;
import javax.servlet.ServletException;

/** 从 TLS 客户端证书链解析身份和角色的可插拔边界。 */
@FunctionalInterface
public interface ServletClientCertificateRealm {
  ServletSecurityIdentity authenticate(X509Certificate[] certificateChain) throws ServletException;
}
