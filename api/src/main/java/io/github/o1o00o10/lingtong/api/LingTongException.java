/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.api;

/** 准备或启动运行时失败时对外暴露的受检异常。 */
public final class LingTongException extends Exception {
    public LingTongException(String message) {
        super(message);
    }

    public LingTongException(String message, Throwable cause) {
        super(message, cause);
    }
}
