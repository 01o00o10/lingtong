/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import javax.net.ssl.SSLEngine;
import java.io.IOException;

/**
 * ALPN 适配边界：Java 8 可注入具备 ALPN 能力的 JSSE Provider，传输层无需依赖厂商 API。
 */
public interface AlpnProvider {
    /** 在握手前为服务端引擎配置可协商的协议，按优先级传入。 */
    void configureServer(SSLEngine engine, String... protocols) throws IOException;

    /** 握手后读取协商结果；Provider 不支持 ALPN 时抛出 IOException。 */
    String selectedProtocol(SSLEngine engine) throws IOException;

    /** 使用 JDK 标准 ALPN 方法；旧版 Java 8 不提供这些方法。 */
    static AlpnProvider jdk() {
        return JdkAlpnProvider.INSTANCE;
    }
}
