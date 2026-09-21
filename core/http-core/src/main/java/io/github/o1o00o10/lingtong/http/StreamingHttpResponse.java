/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

/** 先发布响应头，再从有界邮箱交付正文的响应。 */
public final class StreamingHttpResponse {
    /** HTTP 状态码。 */
    private final int status;
    /** 状态原因短语。 */
    private final String reason;
    /** 首次提交时确定的响应头。 */
    private final HttpHeaders headers;
    /** 应用生产正文、传输层消费正文的邮箱。 */
    private final ResponseBodyMailbox body;
    /** 提交响应时已写入正文的字节数。 */
    private final long bodyBytesAtCommit;
    /** 可选连接升级动作。 */
    private final ConnectionUpgrade upgrade;

    public StreamingHttpResponse(
            int status,
            String reason,
            HttpHeaders headers,
            ResponseBodyMailbox body) {
        this(status, reason, headers, body, 0L, null);
    }

    public StreamingHttpResponse(
            int status,
            String reason,
            HttpHeaders headers,
            ResponseBodyMailbox body,
            long bodyBytesAtCommit) {
        this(status, reason, headers, body, bodyBytesAtCommit, null);
    }

    public StreamingHttpResponse(
            int status,
            String reason,
            HttpHeaders headers,
            ResponseBodyMailbox body,
            long bodyBytesAtCommit,
            ConnectionUpgrade upgrade) {
        if (headers == null || body == null) {
            throw new IllegalArgumentException("streaming response headers and body must not be null");
        }
        if (bodyBytesAtCommit < 0L) {
            throw new IllegalArgumentException("committed response body size must not be negative");
        }
        this.status = status;
        this.reason = reason;
        this.headers = headers;
        this.body = body;
        this.bodyBytesAtCommit = bodyBytesAtCommit;
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

    public ResponseBodyMailbox body() {
        return body;
    }

    public long bodyBytesAtCommit() {
        return bodyBytesAtCommit;
    }

    public ConnectionUpgrade upgrade() {
        return upgrade;
    }
}
