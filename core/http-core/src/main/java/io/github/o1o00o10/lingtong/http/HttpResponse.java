/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 已聚合的 HTTP 响应；正文在构造和读取时都复制。 */
public final class HttpResponse {
    /** 无 Trailer 响应复用的空 Header 集。 */
    private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.builder().build();
    /** HTTP 状态码。 */
    private final int status;
    /** 状态原因短语，主要用于 HTTP/1。 */
    private final String reason;
    /** 响应头。 */
    private final HttpHeaders headers;
    /** 已聚合的正文。 */
    private final byte[] body;
    /** 响应尾部字段。 */
    private final HttpHeaders trailers;
    /** 可选的协议升级回调，例如 WebSocket。 */
    private final ConnectionUpgrade upgrade;

    public HttpResponse(int status, String reason, HttpHeaders headers, byte[] body) {
        this(status, reason, headers, body, EMPTY_HEADERS, null);
    }

    public HttpResponse(
            int status,
            String reason,
            HttpHeaders headers,
            byte[] body,
            ConnectionUpgrade upgrade) {
        this(status, reason, headers, body, EMPTY_HEADERS, upgrade);
    }

    public HttpResponse(
            int status,
            String reason,
            HttpHeaders headers,
            byte[] body,
            HttpHeaders trailers) {
        this(status, reason, headers, body, trailers, null);
    }

    public HttpResponse(
            int status,
            String reason,
            HttpHeaders headers,
            byte[] body,
            HttpHeaders trailers,
            ConnectionUpgrade upgrade) {
        if (headers == null) {
            throw new IllegalArgumentException("response headers must not be null");
        }
        this.status = status;
        this.reason = reason;
        this.headers = headers;
        this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        this.trailers = trailers == null ? EMPTY_HEADERS : trailers;
        this.upgrade = upgrade;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    public HttpHeaders trailers() {
        return trailers;
    }

    public ConnectionUpgrade upgrade() {
        return upgrade;
    }

    public static HttpResponse text(int status, String reason, String body) {
        return new HttpResponse(
                status,
                reason,
                HttpHeaders.builder().add("content-type", "text/plain; charset=utf-8").build(),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
