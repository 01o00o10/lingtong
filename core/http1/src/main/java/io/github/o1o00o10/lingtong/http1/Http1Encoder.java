/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http1;

import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.HttpResponse;
import io.github.o1o00o10.lingtong.http.HttpTrailerFields;
import io.github.o1o00o10.lingtong.http.StreamingHttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** HTTP/1.1 响应编码器；统一管理长度、连接和 Chunked 相关头部。 */
public final class Http1Encoder {
  /** 无 Trailer 时复用的终止 Chunk，只读副本由调用方获取。 */
  private static final ByteBuffer LAST_CHUNK =
      ByteBuffer.wrap("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)).asReadOnlyBuffer();

  public ByteBuffer encode(HttpResponse response, boolean keepAlive) {
    return encode(response, keepAlive, false);
  }

  /** 一次性编码已聚合响应，按状态码和 HEAD 语义决定是否写正文。 */
  public ByteBuffer encode(HttpResponse response, boolean keepAlive, boolean suppressBody) {
    if (response == null) {
      throw new IllegalArgumentException("response must not be null");
    }
    if (response.status() < 100 || response.status() > 999) {
      throw new IllegalArgumentException("invalid HTTP status");
    }
    validateHeader(response.reason());
    byte[] body = response.body();
    boolean statusAllowsBody = statusAllowsBody(response.status());
    boolean encodeBody = statusAllowsBody && !suppressBody;
    boolean chunked = encodeBody && hasTrailers(response.trailers());
    int contentLength =
        response.status() == 205
            ? 0
            : suppressBody || !statusAllowsBody
                ? declaredContentLength(response, body.length)
                : body.length;
    StringBuilder head = new StringBuilder(256);
    head.append("HTTP/1.1 ")
        .append(response.status())
        .append(' ')
        .append(response.reason())
        .append("\r\n");

    for (Map.Entry<String, List<String>> entry : response.headers().entries()) {
      validateHeader(entry.getKey());
      if (isManagedHeader(entry.getKey())) {
        continue;
      }
      for (String value : entry.getValue()) {
        validateHeader(value);
        head.append(entry.getKey()).append(": ").append(value).append("\r\n");
      }
    }
    if (chunked) {
      head.append("transfer-encoding: chunked\r\n");
    } else if (statusAllowsContentLength(response.status())) {
      head.append("content-length: ").append(contentLength).append("\r\n");
    }
    if (response.status() == 101 && response.upgrade() != null) {
      String connection = response.headers().first("connection");
      head.append("connection: ")
          .append(connection == null ? "Upgrade" : connection)
          .append("\r\n");
    } else {
      head.append("connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
    }
    head.append("server: LingTong\r\n\r\n");

    byte[] headerBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
    ByteBuffer encodedChunk = chunked && body.length > 0 ? encodeChunk(body) : null;
    ByteBuffer encodedEnd = chunked ? encodeLastChunk(response.trailers()) : null;
    int encodedBodyLength = encodeBody && !chunked ? body.length : 0;
    int chunkLength = encodedChunk == null ? 0 : encodedChunk.remaining();
    int endLength = encodedEnd == null ? 0 : encodedEnd.remaining();
    ByteBuffer encoded =
        ByteBuffer.allocate(headerBytes.length + encodedBodyLength + chunkLength + endLength);
    encoded.put(headerBytes);
    if (encodedChunk != null) {
      encoded.put(encodedChunk);
    } else if (encodeBody) {
      encoded.put(body);
    }
    if (encodedEnd != null) encoded.put(encodedEnd);
    encoded.flip();
    return encoded;
  }

  /** 仅编码流式响应的状态行与 Header，正文稍后逐块送出。 */
  public ByteBuffer encodeStreamingHead(
      StreamingHttpResponse response, boolean keepAlive, boolean suppressBody) {
    if (response == null) {
      throw new IllegalArgumentException("response must not be null");
    }
    if (response.status() < 100 || response.status() > 999) {
      throw new IllegalArgumentException("invalid HTTP status");
    }
    validateHeader(response.reason());
    boolean chunked = usesChunkedEncoding(response.status(), suppressBody);
    StringBuilder head = new StringBuilder(256);
    head.append("HTTP/1.1 ")
        .append(response.status())
        .append(' ')
        .append(response.reason())
        .append("\r\n");
    for (Map.Entry<String, List<String>> entry : response.headers().entries()) {
      validateHeader(entry.getKey());
      if (isStreamingManagedHeader(entry.getKey())) {
        continue;
      }
      for (String value : entry.getValue()) {
        validateHeader(value);
        head.append(entry.getKey()).append(": ").append(value).append("\r\n");
      }
    }
    if (chunked) {
      head.append("transfer-encoding: chunked\r\n");
    } else if (statusAllowsContentLength(response.status())) {
      head.append("content-length: ").append(declaredContentLength(response, 0L)).append("\r\n");
    }
    head.append("connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
    head.append("server: LingTong\r\n\r\n");
    return ByteBuffer.wrap(head.toString().getBytes(StandardCharsets.US_ASCII));
  }

  /** 将一个非空正文块编码为 HTTP Chunked DATA。 */
  public ByteBuffer encodeChunk(byte[] body) {
    if (body == null || body.length == 0) {
      throw new IllegalArgumentException("HTTP chunk must not be empty");
    }
    byte[] prefix = Integer.toHexString(body.length).getBytes(StandardCharsets.US_ASCII);
    ByteBuffer encoded = ByteBuffer.allocate(prefix.length + 2 + body.length + 2);
    encoded.put(prefix).put((byte) '\r').put((byte) '\n');
    encoded.put(body).put((byte) '\r').put((byte) '\n');
    encoded.flip();
    return encoded;
  }

  public ByteBuffer encodeLastChunk() {
    return LAST_CHUNK.duplicate();
  }

  /** 编码终止 Chunk，并过滤协议禁止放入 Trailer 的字段。 */
  public ByteBuffer encodeLastChunk(HttpHeaders trailers) {
    if (!hasTrailers(trailers)) {
      return encodeLastChunk();
    }
    StringBuilder encoded = new StringBuilder("0\r\n");
    for (Map.Entry<String, List<String>> entry : trailers.entries()) {
      if (HttpTrailerFields.isForbidden(entry.getKey())) continue;
      validateHeader(entry.getKey());
      for (String value : entry.getValue()) {
        validateHeader(value);
        encoded.append(entry.getKey()).append(": ").append(value).append("\r\n");
      }
    }
    encoded.append("\r\n");
    return ByteBuffer.wrap(encoded.toString().getBytes(StandardCharsets.US_ASCII));
  }

  public boolean usesChunkedEncoding(int status, boolean suppressBody) {
    return statusAllowsBody(status) && !suppressBody;
  }

  private void validateHeader(String value) {
    if (value == null) {
      throw new IllegalArgumentException("HTTP header value must not be null");
    }
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("HTTP header contains a line break");
    }
  }

  private boolean isManagedHeader(String name) {
    return "content-length".equalsIgnoreCase(name)
        || "transfer-encoding".equalsIgnoreCase(name)
        || "connection".equalsIgnoreCase(name)
        || "server".equalsIgnoreCase(name);
  }

  private boolean isStreamingManagedHeader(String name) {
    return isManagedHeader(name) || "transfer-encoding".equalsIgnoreCase(name);
  }

  private boolean hasTrailers(HttpHeaders trailers) {
    if (trailers == null) return false;
    for (Map.Entry<String, List<String>> entry : trailers.entries()) {
      if (!HttpTrailerFields.isForbidden(entry.getKey()) && !entry.getValue().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private int declaredContentLength(HttpResponse response, int fallback) {
    String value = response.headers().first("content-length");
    if (value == null) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value);
      return parsed < 0 ? fallback : parsed;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private long declaredContentLength(StreamingHttpResponse response, long fallback) {
    String value = response.headers().first("content-length");
    if (value == null) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(value);
      return parsed < 0L ? fallback : parsed;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private boolean statusAllowsBody(int status) {
    return status >= 200 && status != 204 && status != 205 && status != 304;
  }

  private boolean statusAllowsContentLength(int status) {
    return status >= 200 && status != 204;
  }
}
