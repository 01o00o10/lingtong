/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http2;

/** 解压后的一个 HTTP/2 头部字段。 */
public final class HpackHeader {
    /** 小写字段名，伪头部以冒号开头。 */
    private final String name;
    /** 字段值。 */
    private final String value;

    public HpackHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String name() { return name; }
    public String value() { return value; }
}
