/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** HTTP/1 与 HTTP/2 共用的 Trailer 禁用字段规则。 */
public final class HttpTrailerFields {
    /** 不得出现在 Trailer 中的字段名集合。 */
    private static final Set<String> FORBIDDEN = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "age", "authorization", "cache-control", "connection",
                    "content-encoding", "content-length", "content-range", "content-type",
                    "date", "expect", "expires", "host", "keep-alive", "location",
                    "max-forwards", "proxy-authenticate", "proxy-authorization",
                    "proxy-connection", "retry-after", "set-cookie", "te", "trailer",
                    "transfer-encoding", "upgrade", "vary", "warning", "www-authenticate")));

    private HttpTrailerFields() {
    }

    /** null、伪首部及禁用字段均返回 true。 */
    public static boolean isForbidden(String name) {
        return name == null || name.isEmpty() || name.charAt(0) == ':'
                || FORBIDDEN.contains(name.toLowerCase(Locale.ROOT));
    }
}
