/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.HttpResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/** Runtime endpoint table shared by embedded, JSR 356, and Spring registration paths. */
/** 按路径管理已注册端点，并为握手选择处理器和协商配置。 */
public final class WebSocketEndpointRegistry {
    /** 路由集合。 */
    private final CopyOnWriteArrayList<Route> routes = new CopyOnWriteArrayList<Route>();

    public void register(String path, EndpointFactory factory) {
        if (path == null || !path.startsWith("/") || path.indexOf('?') >= 0 || factory == null) {
            throw new IllegalArgumentException("WebSocket endpoint path and factory are invalid");
        }
        Route candidate = new Route(path, factory);
        for (Route route : routes) {
            if (route.pattern.equals(candidate.pattern)) {
                throw new IllegalArgumentException("duplicate WebSocket endpoint path: " + path);
            }
        }
        routes.add(candidate);
    }

    public HttpResponse handshake(
            HttpRequest request,
            Executor executor,
            int maxMessageBytes) throws Exception {
        String target = request.target();
        int query = target.indexOf('?');
        String path = query < 0 ? target : target.substring(0, query);
        for (Route route : routes) {
            Map<String, String> parameters = route.match(path);
            if (parameters == null) continue;
            WebSocketHandler handler = route.factory.create(request, parameters);
            if (handler == null) return HttpResponse.text(403, "Forbidden", "WebSocket rejected\n");
            return request.isExtendedConnect()
                    ? WebSocketHandshake.acceptExtendedConnect(
                            request, handler, executor, maxMessageBytes)
                    : WebSocketHandshake.accept(request, handler, executor, maxMessageBytes);
        }
        return null;
    }

    /** 封装端点工厂的状态与处理边界。 */
    public interface EndpointFactory {
        WebSocketHandler create(HttpRequest request, Map<String, String> pathParameters)
                throws Exception;
    }

    /** 封装路由的状态与处理边界。 */
    private static final class Route {
        /** template。 */
        /** 模板（template）。 */
        private final String template;
        /** 模式。 */
        private final String pattern;
        /** segments。 */
        /** 分段集合（segments）。 */
        private final String[] segments;
        /** 工厂。 */
        private final EndpointFactory factory;

        private Route(String template, EndpointFactory factory) {
            this.template = template;
            this.segments = segments(template);
            StringBuilder normalized = new StringBuilder();
            java.util.Set<String> parameters = new java.util.LinkedHashSet<String>();
            this.factory = factory;
            for (String segment : segments) {
                if ((segment.startsWith("{") || segment.endsWith("}"))
                        && !(segment.startsWith("{") && segment.endsWith("}")
                        && segment.length() > 2)) {
                    throw new IllegalArgumentException("invalid WebSocket path template: " + template);
                }
                normalized.append('/');
                if (segment.startsWith("{")) {
                    String parameter = segment.substring(1, segment.length() - 1);
                    if (!parameters.add(parameter)) {
                        throw new IllegalArgumentException(
                                "duplicate WebSocket path parameter: " + parameter);
                    }
                    normalized.append("{}");
                } else {
                    normalized.append(segment);
                }
            }
            this.pattern = normalized.length() == 0 ? "/" : normalized.toString();
        }

        private Map<String, String> match(String path) {
            String[] values = segments(path);
            if (values.length != segments.length) return null;
            Map<String, String> parameters = new LinkedHashMap<String, String>();
            for (int i = 0; i < segments.length; i++) {
                String expected = segments[i];
                if (expected.startsWith("{")) {
                    parameters.put(expected.substring(1, expected.length() - 1), values[i]);
                } else if (!expected.equals(values[i])) {
                    return null;
                }
            }
            return Collections.unmodifiableMap(parameters);
        }

        private static String[] segments(String path) {
            if ("/".equals(path)) return new String[0];
            return path.substring(1).split("/", -1);
        }
    }
}
