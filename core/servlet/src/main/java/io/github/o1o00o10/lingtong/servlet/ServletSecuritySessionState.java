/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 保存在 Session 中的认证结果，供后续请求复用。 */
final class ServletSecuritySessionState {
    /** 已认证的主体与角色。 */
    private final ServletSecurityIdentity identity;
    /** ServletRequest.getAuthType() 使用的认证方式。 */
    private final String authenticationType;

    ServletSecuritySessionState(
            ServletSecurityIdentity identity,
            String authenticationType) {
        this.identity = identity;
        this.authenticationType = authenticationType;
    }

    ServletSecurityIdentity identity() {
        return identity;
    }

    String authenticationType() {
        return authenticationType;
    }

    /** FORM 登录前暂存的原始请求，用于认证后恢复目标与参数。 */
    static final class SavedRequest {
        /** 原始 HTTP 方法。 */
        private final String method;
        /** 原始请求 URI。 */
        private final String requestUri;
        /** 原始查询串，可能为空。 */
        private final String queryString;
        /** 防御性复制的原始请求参数。 */
        private final Map<String, String[]> parameters;

        SavedRequest(
                String method,
                String requestUri,
                String queryString,
                Map<String, String[]> parameters) {
            this.method = method;
            this.requestUri = requestUri;
            this.queryString = queryString;
            this.parameters = immutableParameters(parameters);
        }

        String method() {
            return method;
        }

        String redirectTarget() {
            return queryString == null ? requestUri : requestUri + "?" + queryString;
        }

        boolean matches(String uri, String query) {
            return requestUri.equals(uri)
                    && (queryString == null ? query == null : queryString.equals(query));
        }

        Map<String, String[]> parameters() {
            return immutableParameters(parameters);
        }

        /** 连同数组值一起复制，防止调用方改变已保存请求。 */
        private static Map<String, String[]> immutableParameters(
                Map<String, String[]> source) {
            Map<String, String[]> copy = new LinkedHashMap<String, String[]>();
            for (Map.Entry<String, String[]> entry : source.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
