/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport.nio;

import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.RequestConnectionInfo;
import io.github.o1o00o10.lingtong.http.TlsConnectionInfo;
import io.github.o1o00o10.lingtong.transport.ProxyConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅对可信直连代理解析 Forwarded/X-Forwarded-*，失败时回退真实连接信息。 */
final class ForwardedRequestResolver {
    /** 允许提供转发头的直连代理 IP/CIDR 范围。 */
    private final List<Cidr> trustedProxies;

    ForwardedRequestResolver(ProxyConfig config) {
        trustedProxies = new ArrayList<Cidr>(config.trustedProxies().size());
        for (String value : config.trustedProxies()) {
            trustedProxies.add(Cidr.parse(value));
        }
    }

    RequestConnectionInfo resolve(
            HttpHeaders headers,
            InetSocketAddress remote,
            InetSocketAddress local) {
        return resolve(headers, remote, local, null);
    }

    RequestConnectionInfo resolve(
            HttpHeaders headers,
            InetSocketAddress remote,
            InetSocketAddress local,
            TlsConnectionInfo tls) {
        int defaultPort = tls == null ? 80 : 443;
        RequestConnectionInfo.Authority directAuthority =
                RequestConnectionInfo.authority(headers.first("host"), defaultPort);
        RequestConnectionInfo direct = new RequestConnectionInfo(
                address(remote), remote.getPort(), address(local), local.getPort(),
                tls == null ? "http" : "https", directAuthority.host(), directAuthority.port(),
                tls);
        if (trustedProxies.isEmpty() || !trusted(address(remote))) {
            return direct;
        }

        List<String> forwardedHeaders = headers.all("forwarded");
        if (!forwardedHeaders.isEmpty()) {
            RequestConnectionInfo resolved = resolveForwarded(
                    forwardedHeaders, direct, address(remote));
            return resolved == null ? direct : resolved;
        }
        RequestConnectionInfo resolved = resolveXForwarded(headers, direct, address(remote));
        return resolved == null ? direct : resolved;
    }

    /** 从最靠近服务器的一跳向外剥离连续可信代理。 */
    private RequestConnectionInfo resolveForwarded(
            List<String> values,
            RequestConnectionInfo direct,
            String peerAddress) {
        List<ForwardedElement> elements;
        try {
            elements = parseForwarded(values);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String current = peerAddress;
        Node client = null;
        int selected = -1;
        for (int index = elements.size() - 1; index >= 0 && trusted(current); index--) {
            client = Node.parse(elements.get(index).parameters.get("for"));
            if (client == null) {
                return null;
            }
            selected = index;
            current = client.address;
        }
        if (selected < 0 || client == null) {
            return null;
        }
        ForwardedElement element = elements.get(selected);
        return effective(direct, client, element.parameters.get("proto"),
                element.parameters.get("host"), null);
    }

    private RequestConnectionInfo resolveXForwarded(
            HttpHeaders headers,
            RequestConnectionInfo direct,
            String peerAddress) {
        List<String> addresses = commaValues(headers.all("x-forwarded-for"));
        if (addresses.isEmpty()) {
            return null;
        }
        String current = peerAddress;
        Node client = null;
        int selected = -1;
        for (int index = addresses.size() - 1; index >= 0 && trusted(current); index--) {
            client = Node.parse(addresses.get(index));
            if (client == null) {
                return null;
            }
            selected = index;
            current = client.address;
        }
        if (selected < 0 || client == null) {
            return null;
        }
        String proto = selectedValue(headers.all("x-forwarded-proto"), selected, addresses.size());
        String host = selectedValue(headers.all("x-forwarded-host"), selected, addresses.size());
        String port = selectedValue(headers.all("x-forwarded-port"), selected, addresses.size());
        return effective(direct, client, proto, host, port);
    }

    private RequestConnectionInfo effective(
            RequestConnectionInfo direct,
            Node client,
            String rawScheme,
            String rawHost,
            String rawPort) {
        String scheme = normalizedScheme(rawScheme, direct.scheme());
        if (scheme == null) {
            return null;
        }
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        String host = rawHost == null ? direct.serverName() : unquote(rawHost);
        if (!validAuthority(host)) {
            return null;
        }
        RequestConnectionInfo.Authority authority = RequestConnectionInfo.authority(host, defaultPort);
        int serverPort = rawPort == null ? authority.port() : parsePort(rawPort, -1);
        if (serverPort < 0) {
            return null;
        }
        return new RequestConnectionInfo(
                client.address,
                client.port,
                direct.localAddress(),
                direct.localPort(),
                scheme,
                authority.host(),
                serverPort,
                direct.tls());
    }

    private boolean trusted(String address) {
        try {
            byte[] candidate = InetAddress.getByName(address).getAddress();
            for (Cidr range : trustedProxies) {
                if (range.matches(candidate)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static List<ForwardedElement> parseForwarded(List<String> values) {
        List<ForwardedElement> result = new ArrayList<ForwardedElement>();
        for (String value : values) {
            for (String rawElement : split(value, ',')) {
                Map<String, String> parameters = new LinkedHashMap<String, String>();
                for (String rawParameter : split(rawElement, ';')) {
                    int equals = rawParameter.indexOf('=');
                    if (equals <= 0) {
                        throw new IllegalArgumentException("malformed Forwarded parameter");
                    }
                    String name = rawParameter.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                    String parameterValue = unquote(rawParameter.substring(equals + 1).trim());
                    if (name.isEmpty() || parameterValue.isEmpty() || parameters.put(name, parameterValue) != null) {
                        throw new IllegalArgumentException("malformed Forwarded parameter");
                    }
                }
                if (parameters.isEmpty()) {
                    throw new IllegalArgumentException("empty Forwarded element");
                }
                result.add(new ForwardedElement(parameters));
            }
        }
        return result;
    }

    private static List<String> split(String value, char separator) {
        List<String> result = new ArrayList<String>();
        StringBuilder item = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                item.append(current);
                escaped = false;
            } else if (current == '\\' && quoted) {
                item.append(current);
                escaped = true;
            } else if (current == '"') {
                item.append(current);
                quoted = !quoted;
            } else if (current == separator && !quoted) {
                addNonEmpty(result, item.toString());
                item.setLength(0);
            } else {
                item.append(current);
            }
        }
        if (quoted || escaped) {
            throw new IllegalArgumentException("unterminated quoted value");
        }
        addNonEmpty(result, item.toString());
        return result;
    }

    private static List<String> commaValues(List<String> values) {
        List<String> result = new ArrayList<String>();
        try {
            for (String value : values) {
                result.addAll(split(value, ','));
            }
        } catch (IllegalArgumentException e) {
            return new ArrayList<String>();
        }
        return result;
    }

    private static String selectedValue(List<String> values, int selected, int addressCount) {
        List<String> parsed = commaValues(values);
        if (parsed.size() == 1) {
            return parsed.get(0);
        }
        return parsed.size() == addressCount && selected < parsed.size() ? parsed.get(selected) : null;
    }

    private static String normalizedScheme(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String candidate = unquote(value).toLowerCase(Locale.ROOT);
        return "http".equals(candidate) || "https".equals(candidate) ? candidate : null;
    }

    private static boolean validAuthority(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        RequestConnectionInfo.Authority parsed = RequestConnectionInfo.authority(value, 80);
        String host = parsed.host();
        if (host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            if (Node.parse("[" + host + "]") == null) {
                return false;
            }
            if (value.charAt(0) != '[') {
                return true;
            }
            int closing = value.indexOf(']');
            return closing == value.length() - 1
                    || (closing >= 0 && closing + 1 < value.length()
                    && value.charAt(closing + 1) == ':'
                    && parsePort(value.substring(closing + 2), -1) >= 0);
        }
        for (int i = 0; i < host.length(); i++) {
            char current = host.charAt(i);
            if (!(current >= 'a' && current <= 'z')
                    && !(current >= 'A' && current <= 'Z')
                    && !(current >= '0' && current <= '9')
                    && current != '.' && current != '-') {
                return false;
            }
        }
        int colon = value.lastIndexOf(':');
        return colon < 0 || parsePort(value.substring(colon + 1), -1) >= 0;
    }

    private static int parsePort(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(unquote(value));
            return parsed >= 0 && parsed <= 65535 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length() - 2);
        boolean escaped = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char current = value.charAt(i);
            if (escaped) {
                result.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else {
                result.append(current);
            }
        }
        if (escaped) {
            throw new IllegalArgumentException("invalid quoted value");
        }
        return result.toString();
    }

    private static void addNonEmpty(List<String> values, String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty forwarded value");
        }
        values.add(trimmed);
    }

    private static String address(InetSocketAddress value) {
        return value.getAddress().getHostAddress();
    }

    /** 单个 Forwarded 条目的解析结果。 */
    private static final class ForwardedElement {
        /** 一个 Forwarded 元素的参数映射。 */
        private final Map<String, String> parameters;

        private ForwardedElement(Map<String, String> parameters) {
            this.parameters = parameters;
        }
    }

    /** 已解析的转发链节点及可选协议、主机信息。 */
    private static final class Node {
        /** for 参数中的 IP 字面量。 */
        private final String address;
        /** 可选客户端端口；缺失时为 0。 */
        private final int port;

        private Node(String address, int port) {
            this.address = address;
            this.port = port;
        }

        private static Node parse(String value) {
            if (value == null) {
                return null;
            }
            String candidate = unquote(value).trim();
            if (candidate.isEmpty() || "unknown".equalsIgnoreCase(candidate)
                    || candidate.charAt(0) == '_') {
                return null;
            }
            String host = candidate;
            int port = 0;
            if (candidate.charAt(0) == '[') {
                int closing = candidate.indexOf(']');
                if (closing < 0) {
                    return null;
                }
                host = candidate.substring(1, closing);
                if (closing + 1 < candidate.length()) {
                    if (candidate.charAt(closing + 1) != ':') {
                        return null;
                    }
                    port = parsePort(candidate.substring(closing + 2), -1);
                    if (port < 0) {
                        return null;
                    }
                }
            } else if (candidate.indexOf(':') == candidate.lastIndexOf(':')
                    && candidate.indexOf(':') > 0) {
                int colon = candidate.indexOf(':');
                host = candidate.substring(0, colon);
                port = parsePort(candidate.substring(colon + 1), -1);
                if (port < 0) {
                    return null;
                }
            }
            if (!ipLiteral(host)) {
                return null;
            }
            try {
                return new Node(InetAddress.getByName(host).getHostAddress(), port);
            } catch (UnknownHostException e) {
                return null;
            }
        }

        private static boolean ipLiteral(String value) {
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (!(current >= '0' && current <= '9')
                        && !(current >= 'a' && current <= 'f')
                        && !(current >= 'A' && current <= 'F')
                        && current != '.' && current != ':') {
                    return false;
                }
            }
            return !value.isEmpty();
        }
    }

    /** 用于校验受信任代理来源的 CIDR 网段。 */
    private static final class Cidr {
        /** CIDR 网络地址的原始字节。 */
        private final byte[] network;
        /** 匹配时参与比较的前缀位数。 */
        private final int prefixBits;

        private Cidr(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        private static Cidr parse(String value) {
            int slash = value.indexOf('/');
            String host = slash < 0 ? value : value.substring(0, slash);
            if (!Node.ipLiteral(host)) {
                throw new IllegalArgumentException("trusted proxy must be an IP address or CIDR: " + value);
            }
            try {
                byte[] address = InetAddress.getByName(host).getAddress();
                int bits = slash < 0 ? address.length * 8
                        : Integer.parseInt(value.substring(slash + 1));
                if (bits < 0 || bits > address.length * 8) {
                    throw new IllegalArgumentException("invalid trusted proxy prefix: " + value);
                }
                return new Cidr(address, bits);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("invalid trusted proxy: " + value, e);
            }
        }

        private boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int wholeBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
