/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http1;

import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.HttpResponse;
import io.github.o1o00o10.lingtong.http.ResponseBodyMailbox;
import io.github.o1o00o10.lingtong.http.StreamingHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.GZIPOutputStream;

/** 按请求协商、响应 MIME 与缓存语义选择 GZIP 或原样响应。 */
public final class Http1ResponseCompressor {
    /** 是否启用压缩。 */
    private final boolean enabled;
    /** 触发压缩的最小响应大小，单位字节。 */
    private final long minResponseBytes;
    /** 可压缩 MIME 类型列表。 */
    private final List<String> mimeTypes;
    /** 不进行压缩的 User-Agent 正则表达式。 */
    private final List<Pattern> excludedUserAgents;

    public Http1ResponseCompressor(
            boolean enabled,
            long minResponseBytes,
            List<String> mimeTypes,
            List<String> excludedUserAgents) {
        if (minResponseBytes < 0 || mimeTypes == null || excludedUserAgents == null) {
            throw new IllegalArgumentException("invalid HTTP compression configuration");
        }
        this.enabled = enabled;
        this.minResponseBytes = minResponseBytes;
        this.mimeTypes = new ArrayList<String>(mimeTypes);
        this.excludedUserAgents = new ArrayList<Pattern>(excludedUserAgents.size());
        try {
            for (String expression : excludedUserAgents) {
                this.excludedUserAgents.add(Pattern.compile(expression));
            }
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid compression excluded user-agent expression", e);
        }
    }

    /** 对已聚合正文完成协商、GZIP 编码及 Vary/ETag 修正。 */
    public HttpResponse compress(HttpRequest request, HttpResponse response) {
        byte[] body = response.body();
        if (!enabled || body.length < minResponseBytes || !statusAllowsCompression(response.status())
                || response.headers().contains("content-encoding")
                || response.headers().contains("content-range")
                || containsToken(response.headers().all("cache-control"), "no-transform")
                || !compressible(response.headers().first("content-type"))) {
            return response;
        }

        boolean variesByUserAgent = !excludedUserAgents.isEmpty();
        if (!acceptsGzip(request.headers().all("accept-encoding"))
                || excluded(request.headers().first("user-agent"))) {
            return withVary(response, variesByUserAgent);
        }

        byte[] compressed = gzip(body);
        HttpHeaders.Builder headers = HttpHeaders.builder();
        String vary = null;
        for (Map.Entry<String, List<String>> entry : response.headers().entries()) {
            if ("content-length".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            if ("vary".equalsIgnoreCase(entry.getKey())) {
                vary = combine(entry.getValue());
                continue;
            }
            for (String value : entry.getValue()) {
                headers.add(entry.getKey(), "etag".equalsIgnoreCase(entry.getKey())
                        ? weakEtag(value) : value);
            }
        }
        headers.add("content-encoding", "gzip");
        vary = addVary(vary, "Accept-Encoding");
        if (variesByUserAgent) {
            vary = addVary(vary, "User-Agent");
        }
        headers.add("vary", vary);
        return new HttpResponse(
                response.status(), response.reason(), headers.build(), compressed,
                response.trailers(), response.upgrade());
    }

    /** 为流式响应选择原样传输或额外的 GZIP 正文邮箱。 */
    public StreamingPlan prepareStreaming(HttpRequest request, StreamingHttpResponse response) {
        long knownBodyBytes = Math.max(
                response.bodyBytesAtCommit(),
                declaredContentLength(response.headers()));
        if (!enabled || knownBodyBytes < minResponseBytes
                || "HEAD".equals(request.method())
                || !statusAllowsCompression(response.status())
                || response.headers().contains("content-encoding")
                || response.headers().contains("content-range")
                || containsToken(response.headers().all("cache-control"), "no-transform")
                || !compressible(response.headers().first("content-type"))) {
            return StreamingPlan.identity(response);
        }

        boolean variesByUserAgent = !excludedUserAgents.isEmpty();
        if (!acceptsGzip(request.headers().all("accept-encoding"))
                || excluded(request.headers().first("user-agent"))) {
            return StreamingPlan.identity(new StreamingHttpResponse(
                    response.status(),
                    response.reason(),
                    varyHeaders(response.headers(), variesByUserAgent),
                    response.body(),
                    response.bodyBytesAtCommit()));
        }

        ResponseBodyMailbox encodedBody = new ResponseBodyMailbox(
                response.body().capacityBytes(),
                response.body().lowWaterBytes(),
                Long.MAX_VALUE);
        StreamingHttpResponse encodedResponse = new StreamingHttpResponse(
                response.status(),
                response.reason(),
                gzipHeaders(response.headers(), variesByUserAgent),
                encodedBody,
                response.bodyBytesAtCommit());
        return StreamingPlan.gzip(response.body(), encodedResponse);
    }

    private HttpResponse withVary(HttpResponse response, boolean variesByUserAgent) {
        return new HttpResponse(
                response.status(),
                response.reason(),
                varyHeaders(response.headers(), variesByUserAgent),
                response.body(),
                response.trailers(),
                response.upgrade());
    }

    private HttpHeaders varyHeaders(HttpHeaders source, boolean variesByUserAgent) {
        HttpHeaders.Builder headers = HttpHeaders.builder();
        String vary = null;
        for (Map.Entry<String, List<String>> entry : source.entries()) {
            if ("vary".equalsIgnoreCase(entry.getKey())) {
                vary = combine(entry.getValue());
                continue;
            }
            for (String value : entry.getValue()) {
                headers.add(entry.getKey(), value);
            }
        }
        vary = addVary(vary, "Accept-Encoding");
        if (variesByUserAgent) {
            vary = addVary(vary, "User-Agent");
        }
        headers.add("vary", vary);
        return headers.build();
    }

    private HttpHeaders gzipHeaders(HttpHeaders source, boolean variesByUserAgent) {
        HttpHeaders.Builder headers = HttpHeaders.builder();
        String vary = null;
        for (Map.Entry<String, List<String>> entry : source.entries()) {
            if ("content-length".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            if ("vary".equalsIgnoreCase(entry.getKey())) {
                vary = combine(entry.getValue());
                continue;
            }
            for (String value : entry.getValue()) {
                headers.add(entry.getKey(), "etag".equalsIgnoreCase(entry.getKey())
                        ? weakEtag(value) : value);
            }
        }
        headers.add("content-encoding", "gzip");
        vary = addVary(vary, "Accept-Encoding");
        if (variesByUserAgent) {
            vary = addVary(vary, "User-Agent");
        }
        headers.add("vary", vary);
        return headers.build();
    }

    private long declaredContentLength(HttpHeaders headers) {
        String value = headers.first("content-length");
        if (value == null) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0L ? -1L : parsed;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private boolean compressible(String contentType) {
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String value = (separator < 0 ? contentType : contentType.substring(0, separator))
                .trim().toLowerCase(Locale.ROOT);
        for (String configured : mimeTypes) {
            String expected = configured.toLowerCase(Locale.ROOT);
            if (expected.endsWith("/*")) {
                if (value.startsWith(expected.substring(0, expected.length() - 1))) {
                    return true;
                }
            } else if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean excluded(String userAgent) {
        if (userAgent == null) {
            return false;
        }
        for (Pattern pattern : excludedUserAgents) {
            if (pattern.matcher(userAgent).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean acceptsGzip(List<String> values) {
        double gzip = -1.0;
        double wildcard = -1.0;
        for (String value : values) {
            for (String item : value.split(",")) {
                String[] parameters = item.trim().split(";");
                String coding = parameters[0].trim();
                double quality = 1.0;
                for (int i = 1; i < parameters.length; i++) {
                    String parameter = parameters[i].trim();
                    if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                        quality = quality(parameter.substring(2));
                    }
                }
                if ("gzip".equalsIgnoreCase(coding)) {
                    gzip = Math.max(gzip, quality);
                } else if ("*".equals(coding)) {
                    wildcard = Math.max(wildcard, quality);
                }
            }
        }
        return gzip >= 0.0 ? gzip > 0.0 : wildcard > 0.0;
    }

    private double quality(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isNaN(parsed) || parsed < 0.0 || parsed > 1.0 ? 0.0 : parsed;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private boolean statusAllowsCompression(int status) {
        return status >= 200 && status != 204 && status != 205 && status != 206 && status != 304;
    }

    private boolean containsToken(List<String> values, String expected) {
        for (String value : values) {
            for (String token : value.split(",")) {
                if (expected.equalsIgnoreCase(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private byte[] gzip(byte[] body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(32, body.length / 2));
            GZIPOutputStream gzip = new GZIPOutputStream(bytes);
            gzip.write(body);
            gzip.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot gzip in-memory HTTP response", e);
        }
    }

    private String combine(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }

    private String addVary(String value, String field) {
        if (value != null) {
            for (String token : value.split(",")) {
                if (field.equalsIgnoreCase(token.trim()) || "*".equals(token.trim())) {
                    return value;
                }
            }
            return value + ", " + field;
        }
        return field;
    }

    private String weakEtag(String value) {
        return value != null && value.startsWith("\"") ? "W/" + value : value;
    }

    /** 流式压缩的源邮箱、线上的响应以及是否需要编码执行器。 */
    public static final class StreamingPlan {
        /** Servlet 写入的原始正文邮箱。 */
        private final ResponseBodyMailbox sourceBody;
        /** 最终交给传输层的响应。 */
        private final StreamingHttpResponse response;
        /** 是否需要把源正文持续压缩到目标邮箱。 */
        private final boolean gzip;

        private StreamingPlan(
                ResponseBodyMailbox sourceBody,
                StreamingHttpResponse response,
                boolean gzip) {
            this.sourceBody = sourceBody;
            this.response = response;
            this.gzip = gzip;
        }

        private static StreamingPlan identity(StreamingHttpResponse response) {
            return new StreamingPlan(response.body(), response, false);
        }

        private static StreamingPlan gzip(
                ResponseBodyMailbox sourceBody,
                StreamingHttpResponse response) {
            return new StreamingPlan(sourceBody, response, true);
        }

        public ResponseBodyMailbox sourceBody() {
            return sourceBody;
        }

        public StreamingHttpResponse response() {
            return response;
        }

        public boolean gzip() {
            return gzip;
        }
    }
}
