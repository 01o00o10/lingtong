/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.util.Locale;

/**
 * 传输边界附加的不可变连接端点与有效请求权威信息。
 */
public final class RequestConnectionInfo {
    /** 客户端地址，可信代理模式下可为校验后的有效客户端地址。 */
    private final String remoteAddress;
    /** 客户端端口。 */
    private final int remotePort;
    /** 服务端本地监听地址。 */
    private final String localAddress;
    /** 服务端本地端口。 */
    private final int localPort;
    /** 有效请求方案，例如 http/https。 */
    private final String scheme;
    /** 有效请求主机名。 */
    private final String serverName;
    /** 有效请求端口。 */
    private final int serverPort;
    /** TLS 握手元数据；明文请求为 null。 */
    private final TlsConnectionInfo tls;

    public RequestConnectionInfo(
            String remoteAddress,
            int remotePort,
            String localAddress,
            int localPort,
            String scheme,
            String serverName,
            int serverPort) {
        this(remoteAddress, remotePort, localAddress, localPort, scheme, serverName, serverPort,
                null);
    }

    public RequestConnectionInfo(
            String remoteAddress,
            int remotePort,
            String localAddress,
            int localPort,
            String scheme,
            String serverName,
            int serverPort,
            TlsConnectionInfo tls) {
        if (empty(remoteAddress) || empty(localAddress) || empty(scheme) || empty(serverName)
                || !validPort(remotePort) || !validPort(localPort) || !validPort(serverPort)) {
            throw new IllegalArgumentException("invalid request connection information");
        }
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.localAddress = localAddress;
        this.localPort = localPort;
        this.scheme = scheme.toLowerCase(Locale.ROOT);
        this.serverName = serverName;
        this.serverPort = serverPort;
        this.tls = tls;
    }

    /** 在尚无真实 Socket 元数据时构造本地占位值。 */
    public static RequestConnectionInfo local(HttpHeaders headers) {
        Authority authority = authority(headers == null ? null : headers.first("host"), 80);
        return new RequestConnectionInfo(
                "127.0.0.1", 0, "127.0.0.1", authority.port,
                "http", authority.host, authority.port);
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public int remotePort() {
        return remotePort;
    }

    public String localAddress() {
        return localAddress;
    }

    public int localPort() {
        return localPort;
    }

    public String scheme() {
        return scheme;
    }

    public String serverName() {
        return serverName;
    }

    public int serverPort() {
        return serverPort;
    }

    public boolean secure() {
        return "https".equals(scheme);
    }

    public TlsConnectionInfo tls() {
        return tls;
    }

    /** 从 Host/authority 解析主机与端口，无法解析时使用默认端口。 */
    public static Authority authority(String value, int defaultPort) {
        if (value == null || value.trim().isEmpty()) {
            return new Authority("localhost", defaultPort);
        }
        String authority = value.trim();
        if (authority.charAt(0) == '[') {
            int closing = authority.indexOf(']');
            if (closing < 0) {
                return new Authority(authority, defaultPort);
            }
            String host = authority.substring(1, closing);
            int port = closing + 1 < authority.length() && authority.charAt(closing + 1) == ':'
                    ? parsePort(authority.substring(closing + 2), defaultPort)
                    : defaultPort;
            return new Authority(host, port);
        }
        int firstColon = authority.indexOf(':');
        int lastColon = authority.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            return new Authority(
                    authority.substring(0, firstColon),
                    parsePort(authority.substring(firstColon + 1), defaultPort));
        }
        return new Authority(authority, defaultPort);
    }

    private static int parsePort(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return validPort(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean validPort(int value) {
        return value >= 0 && value <= 65535;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 从 Host 字段得到的主机与端口二元组。 */
    public static final class Authority {
        /** 主机名或 IP 字面量。 */
        private final String host;
        /** 解析结果或默认端口。 */
        private final int port;

        private Authority(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }
    }
}
