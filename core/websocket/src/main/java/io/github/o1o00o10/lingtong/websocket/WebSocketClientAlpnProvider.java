/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Isolates client-side ALPN so Java 8 deployments can supply a JSSE-specific adapter. */
/** 在客户端 TLS 握手后读取 ALPN 协商结果的适配边界。 */
public interface WebSocketClientAlpnProvider {
    void configure(SSLSocket socket, String... protocols) throws IOException;

    String selectedProtocol(SSLSocket socket) throws IOException;

    static WebSocketClientAlpnProvider jdk() {
        return JdkHolder.INSTANCE;
    }

    /** 封装jdkholder的状态与处理边界。 */
    final class JdkHolder {
        /** instance。 */
        /** 实例（INSTANCE）。 */
        private static final WebSocketClientAlpnProvider INSTANCE =
                new WebSocketClientAlpnProvider() {
                    @Override
                    public void configure(SSLSocket socket, String... protocols)
                            throws IOException {
                        try {
                            SSLParameters parameters = socket.getSSLParameters();
                            Method setter = SSLParameters.class.getMethod(
                                    "setApplicationProtocols", String[].class);
                            setter.invoke(parameters, new Object[] {protocols});
                            socket.setSSLParameters(parameters);
                        } catch (NoSuchMethodException e) {
                            throw new IOException(
                                    "the active Java 8 JSSE provider has no standard ALPN API", e);
                        } catch (IllegalAccessException | InvocationTargetException
                                | SecurityException e) {
                            throw new IOException("failed to configure client JSSE ALPN", e);
                        }
                    }

                    @Override
                    public String selectedProtocol(SSLSocket socket) throws IOException {
                        try {
                            Method getter = SSLSocket.class.getMethod("getApplicationProtocol");
                            return (String) getter.invoke(socket);
                        } catch (NoSuchMethodException e) {
                            throw new IOException(
                                    "the active Java 8 JSSE provider has no standard ALPN API", e);
                        } catch (IllegalAccessException | InvocationTargetException
                                | SecurityException e) {
                            throw new IOException("failed to read client JSSE ALPN", e);
                        }
                    }
                };

        private JdkHolder() { }
    }
}
