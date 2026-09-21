/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import javax.websocket.Extension;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 表示协商后的 permessage-deflate 扩展及其参数。 */
final class PerMessageDeflateExtension implements Extension {
    /** instance。 */
    /** 实例（INSTANCE）。 */
    static final PerMessageDeflateExtension INSTANCE = new PerMessageDeflateExtension();

    /** 按顺序保存的参数集合。 */
    private final List<Parameter> parameters = Collections.unmodifiableList(Arrays.asList(
            new Value("server_no_context_takeover", null),
            new Value("client_no_context_takeover", null)));

    private PerMessageDeflateExtension() {
    }

    @Override
    public String getName() {
        return "permessage-deflate";
    }

    @Override
    public List<Parameter> getParameters() {
        return parameters;
    }

    /** 封装值的状态与处理边界。 */
    private static final class Value implements Parameter {
        /** 名称。 */
        private final String name;
        /** 值。 */
        private final String value;

        private Value(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override public String getName() { return name; }
        @Override public String getValue() { return value; }
    }
}
