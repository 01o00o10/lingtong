/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.api.websocket.WebSocketSession;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.RequestConnectionInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Spring WebSocketHandler 和握手拦截器适配到原生端点注册表。 */
final class LingTongSpringWebSocketAdapter implements WebSocketHandler {
    /** 处理器。 */
    private final org.springframework.web.socket.WebSocketHandler handler;
    /** 请求。 */
    private final HttpRequest request;
    /** 按键索引的属性集合。 */
    private final Map<String, Object> attributes;
    /** 按顺序保存的interceptors。 */
    private final List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors;
    /** 桥接请求。 */
    private final BridgeRequest bridgeRequest;
    /** 桥接响应。 */
    private final BridgeResponse bridgeResponse;
    /** 会话。 */
    private volatile SpringSession session;

    LingTongSpringWebSocketAdapter(
            org.springframework.web.socket.WebSocketHandler handler,
            HttpRequest request,
            Map<String, Object> attributes,
            List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors,
            BridgeRequest bridgeRequest,
            BridgeResponse bridgeResponse) {
        this.handler = handler;
        this.request = request;
        this.attributes = attributes;
        this.interceptors = interceptors;
        this.bridgeRequest = bridgeRequest;
        this.bridgeResponse = bridgeResponse;
    }

    static WebSocketHandler create(
            org.springframework.web.socket.WebSocketHandler handler,
            HttpRequest request,
            List<org.springframework.web.socket.server.HandshakeInterceptor> interceptors)
            throws Exception {
        BridgeRequest bridgeRequest = new BridgeRequest(request);
        BridgeResponse bridgeResponse = new BridgeResponse();
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        int applied = 0;
        try {
            for (org.springframework.web.socket.server.HandshakeInterceptor interceptor : interceptors) {
                if (!interceptor.beforeHandshake(
                        bridgeRequest, bridgeResponse, handler, attributes)) {
                    for (int i = applied - 1; i >= 0; i--) {
                        interceptors.get(i).afterHandshake(
                                bridgeRequest, bridgeResponse, handler, null);
                    }
                    return null;
                }
                applied++;
            }
        } catch (Exception failure) {
            for (int i = applied - 1; i >= 0; i--) {
                interceptors.get(i).afterHandshake(
                        bridgeRequest, bridgeResponse, handler, failure);
            }
            throw failure;
        }
        return new LingTongSpringWebSocketAdapter(
                handler, request, attributes,
                new ArrayList<org.springframework.web.socket.server.HandshakeInterceptor>(interceptors),
                bridgeRequest, bridgeResponse);
    }

    static WebSocketHandler createPrepared(
            org.springframework.web.socket.WebSocketHandler handler,
            HttpRequest request,
            Map<String, Object> attributes,
            BridgeRequest bridgeRequest,
            BridgeResponse bridgeResponse) {
        return new LingTongSpringWebSocketAdapter(
                handler, request, new LinkedHashMap<String, Object>(attributes),
                Collections.<org.springframework.web.socket.server.HandshakeInterceptor>emptyList(),
                bridgeRequest, bridgeResponse);
    }

    @Override
    public List<String> subprotocols() {
        if (handler instanceof org.springframework.web.socket.SubProtocolCapable) {
            return ((org.springframework.web.socket.SubProtocolCapable) handler).getSubProtocols();
        }
        return Collections.emptyList();
    }

    @Override public boolean perMessageDeflate() { return true; }

    @Override public Map<String, List<String>> handshakeResponseHeaders() {
        return bridgeResponse.headers;
    }

    @Override
    public void onOpen(WebSocketSession nativeSession) throws Exception {
        SpringSession created = new SpringSession(nativeSession, request, attributes);
        session = created;
        try {
            handler.afterConnectionEstablished(created);
        } finally {
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                interceptors.get(i).afterHandshake(
                        bridgeRequest, bridgeResponse, handler, null);
            }
        }
    }

    @Override public void onText(WebSocketSession ignored, String message) throws Exception {
        handler.handleMessage(session, new TextMessage(message));
    }

    @Override public void onBinary(WebSocketSession ignored, ByteBuffer message) throws Exception {
        handler.handleMessage(session, new BinaryMessage(message));
    }

    @Override public void onPong(WebSocketSession ignored, ByteBuffer payload) throws Exception {
        handler.handleMessage(session, new PongMessage(payload));
    }

    @Override public void onClose(WebSocketSession ignored, int code, String reason) throws Exception {
        handler.afterConnectionClosed(session, new CloseStatus(code, reason));
    }

    @Override public void onError(WebSocketSession ignored, Throwable failure) {
        try { handler.handleTransportError(session, failure); }
        catch (Exception suppressed) { failure.addSuppressed(suppressed); }
    }

    /** 封装Spring会话的状态与处理边界。 */
    private static final class SpringSession implements org.springframework.web.socket.WebSocketSession {
        /** native会话。 */
        private final WebSocketSession nativeSession;
        /** Spring 握手请求的目标 URI。 */
        private final URI uri;
        /** 头部集合。 */
        private final HttpHeaders headers = new HttpHeaders();
        /** 按键索引的属性集合。 */
        private final Map<String, Object> attributes;
        /** 本地。 */
        private final InetSocketAddress local;
        /** 远端。 */
        private final InetSocketAddress remote;
        /** 文本上限。 */
        private int textLimit = 64 * 1024;
        /** 二进制上限。 */
        private int binaryLimit = 64 * 1024;

        private SpringSession(
                WebSocketSession nativeSession,
                HttpRequest request,
                Map<String, Object> attributes) {
            this.nativeSession = nativeSession;
            RequestConnectionInfo info = request.connectionInfo();
            String host = request.headers().first("host");
            if (host == null) host = info.serverName() + ":" + info.serverPort();
            this.uri = URI.create((info.secure() ? "wss" : "ws") + "://" + host
                    + request.target());
            for (Map.Entry<String, List<String>> entry : request.headers().entries()) {
                headers.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
            }
            this.attributes = new LinkedHashMap<String, Object>(attributes);
            this.local = new InetSocketAddress(info.localAddress(), info.localPort());
            this.remote = new InetSocketAddress(info.remoteAddress(), info.remotePort());
        }

        @Override public String getId() { return nativeSession.id(); }
        @Override public URI getUri() { return uri; }
        @Override public HttpHeaders getHandshakeHeaders() { return HttpHeaders.readOnlyHttpHeaders(headers); }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return local; }
        @Override public InetSocketAddress getRemoteAddress() { return remote; }
        @Override public String getAcceptedProtocol() { return nativeSession.negotiatedSubprotocol(); }
        @Override public void setTextMessageSizeLimit(int value) { textLimit = value; }
        @Override public int getTextMessageSizeLimit() { return textLimit; }
        @Override public void setBinaryMessageSizeLimit(int value) { binaryLimit = value; }
        @Override public int getBinaryMessageSizeLimit() { return binaryLimit; }
        @Override public List<WebSocketExtension> getExtensions() {
            return nativeSession.negotiatedExtensions().isEmpty()
                    ? Collections.<WebSocketExtension>emptyList()
                    : Collections.singletonList(new WebSocketExtension("permessage-deflate"));
        }
        @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage) nativeSession.sendText((String) message.getPayload());
            else if (message instanceof BinaryMessage) nativeSession.sendBinary((ByteBuffer) message.getPayload());
            else if (message instanceof PingMessage) nativeSession.sendPing((ByteBuffer) message.getPayload());
            else if (message instanceof PongMessage) nativeSession.sendPong((ByteBuffer) message.getPayload());
            else throw new IOException("unsupported Spring WebSocket message type " + message.getClass());
        }
        @Override public boolean isOpen() { return nativeSession.isOpen(); }
        @Override public void close() throws IOException { nativeSession.close(); }
        @Override public void close(CloseStatus status) throws IOException {
            nativeSession.close(status.getCode(), status.getReason());
        }
    }

    /** 封装桥接请求的状态与处理边界。 */
    static final class BridgeRequest implements org.springframework.http.server.ServerHttpRequest {
        /** 请求。 */
        private final HttpRequest request;
        /** 方法。 */
        private final String method;
        /** 头部集合。 */
        private final HttpHeaders headers = new HttpHeaders();
        /** 已建立 WebSocket 会话对应的请求 URI。 */
        private final URI uri;
        /** 本地。 */
        private final InetSocketAddress local;
        /** 远端。 */
        private final InetSocketAddress remote;

        BridgeRequest(HttpRequest request) {
            this(request, request.method());
        }

        BridgeRequest(HttpRequest request, String method) {
            this.request = request;
            this.method = method;
            for (Map.Entry<String, List<String>> entry : request.headers().entries()) {
                headers.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
            }
            RequestConnectionInfo info = request.connectionInfo();
            String host = request.headers().first("host");
            if (host == null) host = info.serverName() + ":" + info.serverPort();
            uri = URI.create((info.secure() ? "https" : "http") + "://" + host
                    + request.target());
            local = new InetSocketAddress(info.localAddress(), info.localPort());
            remote = new InetSocketAddress(info.remoteAddress(), info.remotePort());
        }

        @Override public String getMethodValue() { return method; }
        @Override public URI getURI() { return uri; }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public InputStream getBody() { return new ByteArrayInputStream(request.body()); }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return local; }
        @Override public InetSocketAddress getRemoteAddress() { return remote; }
        @Override public org.springframework.http.server.ServerHttpAsyncRequestControl
                getAsyncRequestControl(org.springframework.http.server.ServerHttpResponse response) {
            throw new UnsupportedOperationException("asynchronous Spring handshake is not supported");
        }
    }

    /** 封装桥接响应的状态与处理边界。 */
    static final class BridgeResponse implements org.springframework.http.server.ServerHttpResponse {
        /** 头部集合。 */
        private final HttpHeaders headers = new HttpHeaders();
        /** 消息体。 */
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        /** 状态码。 */
        private org.springframework.http.HttpStatus status = org.springframework.http.HttpStatus.SWITCHING_PROTOCOLS;
        @Override public void setStatusCode(org.springframework.http.HttpStatus value) { status = value; }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public OutputStream getBody() { return body; }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
