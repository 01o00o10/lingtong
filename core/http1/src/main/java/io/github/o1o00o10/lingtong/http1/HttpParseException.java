/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http1;

/** 携带应返回给客户端的 HTTP 状态码的解析错误。 */
public final class HttpParseException extends Exception {
    /** 校验失败对应的 HTTP 响应状态码。 */
    private final int statusCode;

    public HttpParseException(String message) {
        this(400, message);
    }

    public HttpParseException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
