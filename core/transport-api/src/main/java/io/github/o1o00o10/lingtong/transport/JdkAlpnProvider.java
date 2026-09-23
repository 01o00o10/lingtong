/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/** 用反射访问标准 ALPN API，避免 Java 8 编译期链接 Java 9+ 方法。 */
final class JdkAlpnProvider implements AlpnProvider {
  /** 无状态实现的共享实例。 */
  static final JdkAlpnProvider INSTANCE = new JdkAlpnProvider();

  private JdkAlpnProvider() {}

  @Override
  public void configureServer(SSLEngine engine, String... protocols) throws IOException {
    try {
      SSLParameters parameters = engine.getSSLParameters();
      Method setter = SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
      setter.invoke(parameters, new Object[] {protocols});
      engine.setSSLParameters(parameters);
    } catch (NoSuchMethodException e) {
      throw new IOException("the active Java 8 JSSE provider has no standard ALPN API", e);
    } catch (IllegalAccessException | InvocationTargetException | SecurityException e) {
      throw new IOException("failed to configure JSSE ALPN", e);
    }
  }

  @Override
  public String selectedProtocol(SSLEngine engine) throws IOException {
    try {
      Method getter = SSLEngine.class.getMethod("getApplicationProtocol");
      return (String) getter.invoke(engine);
    } catch (NoSuchMethodException e) {
      throw new IOException("the active Java 8 JSSE provider has no standard ALPN API", e);
    } catch (IllegalAccessException | InvocationTargetException | SecurityException e) {
      throw new IOException("failed to read the negotiated JSSE ALPN protocol", e);
    }
  }
}
