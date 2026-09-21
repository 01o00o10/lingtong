/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executor;

/** 校验 HTTP Upgrade 请求并生成 WebSocket 握手响应。 */
public final class WebSocketHandshake {
    /** magic。 */
    /** 魔数（MAGIC）。 */
    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshake() {
    }

    public static boolean isUpgradeRequest(HttpRequest request) {
        return request != null
                && "GET".equals(request.method())
                && "HTTP/1.1".equals(request.version())
                && containsToken(request.headers().all("connection"), "upgrade")
                && containsToken(request.headers().all("upgrade"), "websocket");
    }

    public static HttpResponse accept(
            HttpRequest request,
            WebSocketHandler handler,
            Executor executor,
            int maxMessageBytes) {
        if (!isUpgradeRequest(request)) {
            return HttpResponse.text(400, "Bad Request", "Invalid WebSocket upgrade\n");
        }
        if (!"13".equals(request.headers().first("sec-websocket-version"))) {
            return new HttpResponse(
                    426,
                    "Upgrade Required",
                    HttpHeaders.builder().add("sec-websocket-version", "13").build(),
                    new byte[0]);
        }
        String key = request.headers().first("sec-websocket-key");
        if (!validKey(key)) {
            return HttpResponse.text(400, "Bad Request", "Invalid WebSocket key\n");
        }
        String subprotocol = selectSubprotocol(
                request.headers().all("sec-websocket-protocol"), handler.subprotocols());
        boolean compressed = handler.perMessageDeflate()
                && offersPerMessageDeflate(request.headers().all("sec-websocket-extensions"));
        HttpHeaders.Builder headers = HttpHeaders.builder()
                .add("upgrade", "websocket")
                .add("connection", "Upgrade")
                .add("sec-websocket-accept", acceptKey(key));
        if (!subprotocol.isEmpty()) headers.add("sec-websocket-protocol", subprotocol);
        if (compressed) headers.add("sec-websocket-extensions",
                "permessage-deflate; server_no_context_takeover; client_no_context_takeover");
        addApplicationHeaders(headers, handler);
        return new HttpResponse(
                101,
                "Switching Protocols",
                headers.build(),
                new byte[0],
                new WebSocketProtocol(handler, executor, maxMessageBytes, subprotocol, compressed));
    }

    public static boolean isExtendedConnectRequest(HttpRequest request) {
        return request != null && request.isExtendedConnect()
                && "websocket".equalsIgnoreCase(request.extendedConnectProtocol());
    }

    public static HttpResponse acceptExtendedConnect(
            HttpRequest request,
            WebSocketHandler handler,
            Executor executor,
            int maxMessageBytes) {
        if (!isExtendedConnectRequest(request)) {
            return HttpResponse.text(400, "Bad Request", "Invalid WebSocket CONNECT\n");
        }
        String version = request.headers().first("sec-websocket-version");
        if (version != null && !"13".equals(version)) {
            return new HttpResponse(400, "Bad Request",
                    HttpHeaders.builder().add("sec-websocket-version", "13").build(),
                    new byte[0]);
        }
        String subprotocol = selectSubprotocol(
                request.headers().all("sec-websocket-protocol"), handler.subprotocols());
        boolean compressed = handler.perMessageDeflate()
                && offersPerMessageDeflate(request.headers().all("sec-websocket-extensions"));
        HttpHeaders.Builder headers = HttpHeaders.builder();
        if (!subprotocol.isEmpty()) headers.add("sec-websocket-protocol", subprotocol);
        if (compressed) headers.add("sec-websocket-extensions",
                "permessage-deflate; server_no_context_takeover; client_no_context_takeover");
        addApplicationHeaders(headers, handler);
        return new HttpResponse(200, "OK", headers.build(), new byte[0],
                new WebSocketProtocol(handler, executor, maxMessageBytes, subprotocol, compressed));
    }

    public static String acceptKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] result = digest.digest((key.trim() + MAGIC).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(result);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", e);
        }
    }

    private static boolean validKey(String key) {
        if (key == null) return false;
        try {
            return Base64.getDecoder().decode(key.trim()).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean containsToken(List<String> values, String expected) {
        for (String value : values) {
            for (String token : value.split(",")) {
                if (expected.equalsIgnoreCase(token.trim())) return true;
            }
        }
        return false;
    }

    private static String selectSubprotocol(List<String> requested, List<String> supported) {
        if (supported == null || supported.isEmpty()) return "";
        for (String value : requested) {
            for (String token : value.split(",")) {
                String candidate = token.trim();
                for (String available : supported) {
                    if (candidate.equals(available)) return candidate;
                }
            }
        }
        return "";
    }

    private static boolean offersPerMessageDeflate(List<String> values) {
        for (String value : values) {
            for (String extension : value.split(",")) {
                String[] parts = extension.split(";");
                if (parts.length > 0 && "permessage-deflate".equalsIgnoreCase(parts[0].trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addApplicationHeaders(HttpHeaders.Builder headers, WebSocketHandler handler) {
        for (java.util.Map.Entry<String, List<String>> entry
                : handler.handshakeResponseHeaders().entrySet()) {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if ("connection".equals(name) || "upgrade".equals(name)
                    || "sec-websocket-accept".equals(name)
                    || "sec-websocket-protocol".equals(name)
                    || "sec-websocket-extensions".equals(name)) continue;
            for (String value : entry.getValue()) headers.add(name, value);
        }
    }
}
