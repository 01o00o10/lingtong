/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http1;

import io.github.o1o00o10.lingtong.http.BodyMailbox;
import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** HTTP/1.1 增量解析状态机；所属 I/O 分片负责喂入字节和重置实例。 */
public final class Http1Parser {
  /** 默认请求头字段数量上限。 */
  private static final int DEFAULT_MAX_HEADER_COUNT = 100;
  /** 默认 Trailer 字段数量上限。 */
  private static final int DEFAULT_MAX_TRAILER_COUNT = 100;
  /** 一次请求从头部到正文结束的解析阶段。 */
  private enum State {
    /** 正在接收请求行及 Header。 */
    HEADERS,
    /** 按 Content-Length 接收定长正文。 */
    FIXED_BODY,
    /** 读取下一个 Chunk 的十六进制长度行。 */
    CHUNK_SIZE,
    /** 读取当前 Chunk 的数据字节。 */
    CHUNK_DATA,
    /** 等待 Chunk 数据后的 CR。 */
    CHUNK_DATA_CR,
    /** 等待 Chunk 数据后的 LF。 */
    CHUNK_DATA_LF,
    /** 解析最后一个 Chunk 后的 Trailer 行。 */
    TRAILER_LINE,
    /** 本次请求正文及 Trailer 均已结束。 */
    COMPLETE
  }

  /** 有界的请求头/Chunk 行暂存区。 */
  private final byte[] headerBuffer;
  /** 整个请求体的最大字节数，与邮箱容量不同。 */
  private final int maxBodyBytes;
  /** 请求头字段数量上限。 */
  private final int maxHeaderCount;
  /** Trailer 总字节上限。 */
  private final int maxTrailerBytes;
  /** Trailer 字段数量上限。 */
  private final int maxTrailerCount;
  /** 是否在头部解析完后提前交付请求。 */
  private final boolean streaming;
  /** 流式请求体邮箱容量，单位字节。 */
  private final int bodyBufferBytes;
  /** 邮箱低水位，消费到此值后通知网络层恢复读。 */
  private final int bodyLowWaterBytes;
  /** 当前暂存的请求头字节数。 */
  private int headerLength;
  /** CRLFCRLF 头部终止符已匹配的字节数。 */
  private int terminatorProgress;
  /** 当前解析阶段。 */
  private State state = State.HEADERS;
  /** 已解析的 HTTP 方法。 */
  private String method;
  /** 已解析的请求目标。 */
  private String target;
  /** 已解析的协议版本。 */
  private String version;
  /** 头部完成后的不可变 Header 集。 */
  private HttpHeaders headers;
  /** 请求体结束后得到的 Trailer。 */
  private HttpHeaders trailers;
  /** 增量读取 Trailer 时使用的构建器。 */
  private HttpHeaders.Builder trailerBuilder;
  /** 流式请求体的生产者端邮箱。 */
  private BodyMailbox bodyMailbox;
  /** 头部就绪后返回给上层的同一个请求对象。 */
  private HttpRequest streamingRequest;
  /** 防止流式请求被重复发布。 */
  private boolean requestPublished;
  /** 非流式模式聚合正文的字节数组。 */
  private byte[] body;
  /** 已接收的请求体字节数。 */
  private int bodyLength;
  /** Content-Length 模式预期接收的正文长度。 */
  private int expectedBodyLength;
  /** 当前 Chunk 尚未接收的字节数。 */
  private int chunkRemaining;
  /** 正在解析的 Chunk 大小行或 Trailer 行长度。 */
  private int lineLength;
  /** 当前行是否刚读到 CR，下一字节须为 LF。 */
  private boolean lineSawCr;
  /** 已接收的 Trailer 总字节数。 */
  private int trailerBytes;
  /** 已接收的 Trailer 字段数。 */
  private int trailerCount;

  public Http1Parser(int maxHeaderBytes, int maxBodyBytes) {
    this(
        maxHeaderBytes,
        maxBodyBytes,
        false,
        0,
        0,
        DEFAULT_MAX_HEADER_COUNT,
        maxHeaderBytes,
        DEFAULT_MAX_TRAILER_COUNT);
  }

  public static Http1Parser streaming(
      int maxHeaderBytes, int maxBodyBytes, int bodyBufferBytes, int bodyLowWaterBytes) {
    return new Http1Parser(
        maxHeaderBytes,
        maxBodyBytes,
        true,
        bodyBufferBytes,
        bodyLowWaterBytes,
        DEFAULT_MAX_HEADER_COUNT,
        maxHeaderBytes,
        DEFAULT_MAX_TRAILER_COUNT);
  }

  public static Http1Parser streaming(
      int maxHeaderBytes,
      int maxBodyBytes,
      int bodyBufferBytes,
      int bodyLowWaterBytes,
      int maxHeaderCount,
      int maxTrailerBytes,
      int maxTrailerCount) {
    return new Http1Parser(
        maxHeaderBytes,
        maxBodyBytes,
        true,
        bodyBufferBytes,
        bodyLowWaterBytes,
        maxHeaderCount,
        maxTrailerBytes,
        maxTrailerCount);
  }

  private Http1Parser(
      int maxHeaderBytes,
      int maxBodyBytes,
      boolean streaming,
      int bodyBufferBytes,
      int bodyLowWaterBytes,
      int maxHeaderCount,
      int maxTrailerBytes,
      int maxTrailerCount) {
    if (maxHeaderBytes <= 0
        || maxBodyBytes < 0
        || maxHeaderCount <= 0
        || maxTrailerBytes <= 0
        || maxTrailerCount < 0) {
      throw new IllegalArgumentException("invalid parser limits");
    }
    if (streaming
        && (bodyBufferBytes <= 0
            || bodyLowWaterBytes < 0
            || bodyLowWaterBytes >= bodyBufferBytes)) {
      throw new IllegalArgumentException("invalid streaming body limits");
    }
    this.headerBuffer = new byte[maxHeaderBytes];
    this.maxBodyBytes = maxBodyBytes;
    this.maxHeaderCount = maxHeaderCount;
    this.maxTrailerBytes = maxTrailerBytes;
    this.maxTrailerCount = maxTrailerCount;
    this.streaming = streaming;
    this.bodyBufferBytes = bodyBufferBytes;
    this.bodyLowWaterBytes = bodyLowWaterBytes;
  }

  /** 流式模式在头部完成时只返回一次请求，随后继续填充正文邮箱。 */
  public HttpRequest parse(ByteBuffer source) throws HttpParseException {
    try {
      while (source.hasRemaining() && state != State.COMPLETE) {
        int positionBefore = source.position();
        State stateBefore = state;
        switch (state) {
          case HEADERS:
            readHeaderByte(source.get());
            if (terminatorProgress == 4) {
              parseHead();
            }
            break;
          case FIXED_BODY:
            readFixedBody(source);
            break;
          case CHUNK_SIZE:
            if (readLineByte(source.get())) {
              parseChunkSize();
            }
            break;
          case CHUNK_DATA:
            readChunkData(source);
            break;
          case CHUNK_DATA_CR:
            requireByte(source.get(), '\r', "missing CR after chunk data");
            state = State.CHUNK_DATA_LF;
            break;
          case CHUNK_DATA_LF:
            requireByte(source.get(), '\n', "missing LF after chunk data");
            state = State.CHUNK_SIZE;
            break;
          case TRAILER_LINE:
            if (readLineByte(source.get())) {
              parseTrailerLine();
            }
            break;
          default:
            break;
        }
        if (streaming && streamingRequest != null && !requestPublished) {
          requestPublished = true;
          return streamingRequest;
        }
        if (source.position() == positionBefore && state == stateBefore) {
          break;
        }
      }

      if (streaming || state != State.COMPLETE) {
        return null;
      }
      byte[] completedBody = bodyLength == body.length ? body : Arrays.copyOf(body, bodyLength);
      return new HttpRequest(method, target, version, headers, completedBody, trailers);
    } catch (HttpParseException e) {
      failBody(e);
      throw e;
    }
  }

  public boolean isReadingHeaders() {
    return state == State.HEADERS;
  }

  public boolean isMessageComplete() {
    return state == State.COMPLETE;
  }

  /** 邮箱已满时通知所属 I/O 分片暂停此连接的网络读取。 */
  public boolean isBodyBackpressured() {
    return streaming
        && (state == State.FIXED_BODY || state == State.CHUNK_DATA)
        && bodyMailbox != null
        && bodyMailbox.remainingCapacity() == 0;
  }

  public void abort(Throwable failure) {
    if (failure == null) {
      throw new IllegalArgumentException("parser abort failure must not be null");
    }
    failBody(failure);
  }

  /** 前一个请求结束后复用解析器，禁止提前丢弃尚未终结的正文邮箱。 */
  public void reset() {
    if (bodyMailbox != null && !bodyMailbox.isComplete() && bodyMailbox.failure() == null) {
      throw new IllegalStateException("cannot reset parser before request body terminates");
    }
    headerLength = 0;
    terminatorProgress = 0;
    state = State.HEADERS;
    method = null;
    target = null;
    version = null;
    headers = null;
    trailers = null;
    trailerBuilder = null;
    bodyMailbox = null;
    streamingRequest = null;
    requestPublished = false;
    body = null;
    bodyLength = 0;
    expectedBodyLength = 0;
    chunkRemaining = 0;
    lineLength = 0;
    lineSawCr = false;
    trailerBytes = 0;
    trailerCount = 0;
  }

  private void readHeaderByte(byte value) throws HttpParseException {
    if (headerLength == headerBuffer.length) {
      throw new HttpParseException(431, "request headers exceed configured limit");
    }
    headerBuffer[headerLength++] = value;

    if ((terminatorProgress == 0 || terminatorProgress == 2) && value == '\r') {
      terminatorProgress++;
    } else if ((terminatorProgress == 1 || terminatorProgress == 3) && value == '\n') {
      terminatorProgress++;
    } else {
      terminatorProgress = value == '\r' ? 1 : 0;
    }
  }

  /** 校验请求行、Header 与消息分帧后选择定长或 Chunked 正文状态。 */
  private void parseHead() throws HttpParseException {
    String head = new String(headerBuffer, 0, headerLength - 4, StandardCharsets.ISO_8859_1);
    String[] lines = head.split("\\r\\n", -1);
    if (lines.length == 0) {
      throw new HttpParseException("missing request line");
    }
    if (lines.length - 1 > maxHeaderCount) {
      throw new HttpParseException(431, "request header count exceeds configured limit");
    }

    parseRequestLine(lines[0]);
    HttpHeaders.Builder builder = HttpHeaders.builder();
    for (int i = 1; i < lines.length; i++) {
      parseHeader(lines[i], builder);
    }
    headers = builder.build();
    validateHost(headers.all("host"));

    boolean transferEncoded = headers.contains("transfer-encoding");
    boolean contentLengthPresent = headers.contains("content-length");
    if (transferEncoded && contentLengthPresent) {
      throw new HttpParseException("content-length and transfer-encoding cannot be combined");
    }
    if (transferEncoded) {
      if (!"HTTP/1.1".equals(version)) {
        throw new HttpParseException("transfer-encoding requires HTTP/1.1");
      }
      requireChunked(headers.all("transfer-encoding"));
      trailerBuilder = HttpHeaders.builder();
      state = State.CHUNK_SIZE;
      resetLine();
      if (streaming) {
        initializeStreamingRequest();
      } else {
        body = new byte[0];
      }
      return;
    }

    int contentLength = parseContentLength(headers.all("content-length"));
    if (contentLength > maxBodyBytes) {
      throw new HttpParseException(413, "request body exceeds configured limit");
    }
    expectedBodyLength = contentLength;
    trailers = HttpHeaders.builder().build();
    if (streaming) {
      initializeStreamingRequest();
      if (contentLength == 0) {
        bodyMailbox.complete(trailers);
        state = State.COMPLETE;
      } else {
        state = State.FIXED_BODY;
      }
    } else {
      body = new byte[contentLength];
      state = contentLength == 0 ? State.COMPLETE : State.FIXED_BODY;
    }
  }

  private void readFixedBody(ByteBuffer source) {
    int copy = Math.min(source.remaining(), expectedBodyLength - bodyLength);
    if (streaming) {
      copy = offerBody(source, copy);
    } else {
      source.get(body, bodyLength, copy);
    }
    bodyLength += copy;
    if (bodyLength == expectedBodyLength) {
      if (streaming) {
        bodyMailbox.complete(trailers);
      }
      state = State.COMPLETE;
    }
  }

  private void readChunkData(ByteBuffer source) {
    int copy = Math.min(source.remaining(), chunkRemaining);
    if (streaming) {
      copy = offerBody(source, copy);
    } else {
      ensureBodyCapacity(bodyLength + copy);
      source.get(body, bodyLength, copy);
    }
    bodyLength += copy;
    chunkRemaining -= copy;
    if (chunkRemaining == 0) {
      state = State.CHUNK_DATA_CR;
    }
  }

  private boolean readLineByte(byte value) throws HttpParseException {
    if (lineSawCr) {
      if (value != '\n') {
        throw new HttpParseException("HTTP line uses an invalid terminator");
      }
      lineSawCr = false;
      return true;
    }
    if (value == '\r') {
      lineSawCr = true;
      return false;
    }
    if (value == '\n') {
      throw new HttpParseException("HTTP line must end with CRLF");
    }
    int lineLimit =
        state == State.TRAILER_LINE
            ? Math.min(headerBuffer.length, maxTrailerBytes)
            : headerBuffer.length;
    if (lineLength == lineLimit) {
      if (state == State.TRAILER_LINE) {
        throw new HttpParseException(431, "request trailers exceed configured limit");
      }
      throw new HttpParseException("chunk metadata exceeds configured header limit");
    }
    headerBuffer[lineLength++] = value;
    return false;
  }

  private void parseChunkSize() throws HttpParseException {
    String line = currentLine();
    resetLine();
    int semicolon = line.indexOf(';');
    String sizeText = semicolon < 0 ? line : line.substring(0, semicolon);
    if (sizeText.isEmpty()) {
      throw new HttpParseException("missing chunk size");
    }
    if (semicolon >= 0 && containsControl(line.substring(semicolon + 1))) {
      throw new HttpParseException("invalid chunk extension");
    }

    long size = 0L;
    for (int i = 0; i < sizeText.length(); i++) {
      int digit = Character.digit(sizeText.charAt(i), 16);
      if (digit < 0 || size > (Integer.MAX_VALUE - digit) / 16L) {
        throw new HttpParseException("invalid chunk size");
      }
      size = size * 16L + digit;
    }
    if (size > maxBodyBytes - bodyLength) {
      throw new HttpParseException(413, "request body exceeds configured limit");
    }
    if (size == 0L) {
      state = State.TRAILER_LINE;
    } else {
      chunkRemaining = (int) size;
      if (!streaming) {
        ensureBodyCapacity(bodyLength + chunkRemaining);
      }
      state = State.CHUNK_DATA;
    }
  }

  private void parseTrailerLine() throws HttpParseException {
    trailerBytes += lineLength + 2;
    if (trailerBytes > maxTrailerBytes) {
      throw new HttpParseException(431, "request trailers exceed configured limit");
    }
    String line = currentLine();
    resetLine();
    if (line.isEmpty()) {
      trailers = trailerBuilder.build();
      if (streaming) {
        bodyMailbox.complete(trailers);
      }
      state = State.COMPLETE;
      return;
    }
    trailerCount++;
    if (trailerCount > maxTrailerCount) {
      throw new HttpParseException(431, "request trailer count exceeds configured limit");
    }
    int colon = line.indexOf(':');
    if (colon <= 0) {
      throw new HttpParseException("invalid trailer line");
    }
    String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
    if (isForbiddenTrailer(name)) {
      throw new HttpParseException("forbidden trailer field: " + name);
    }
    parseHeader(line, trailerBuilder);
  }

  private void requireChunked(List<String> values) throws HttpParseException {
    int tokenCount = 0;
    String token = null;
    for (String value : values) {
      for (String part : value.split(",", -1)) {
        tokenCount++;
        token = trimOws(part);
        if (token.isEmpty()) {
          throw new HttpParseException("invalid transfer-encoding");
        }
      }
    }
    if (tokenCount != 1 || !"chunked".equalsIgnoreCase(token)) {
      throw new HttpParseException("unsupported transfer-encoding");
    }
  }

  private void ensureBodyCapacity(int required) {
    if (required <= body.length) {
      return;
    }
    int grown = body.length == 0 ? Math.min(1024, maxBodyBytes) : body.length;
    while (grown < required) {
      int next = grown <= maxBodyBytes / 2 ? grown * 2 : maxBodyBytes;
      if (next <= grown) {
        grown = required;
        break;
      }
      grown = next;
    }
    body = Arrays.copyOf(body, grown);
  }

  private void initializeStreamingRequest() {
    bodyMailbox = new BodyMailbox(bodyBufferBytes, bodyLowWaterBytes, maxBodyBytes);
    streamingRequest = HttpRequest.withBodyMailbox(method, target, version, headers, bodyMailbox);
  }

  private int offerBody(ByteBuffer source, int length) {
    if (length == 0) {
      return 0;
    }
    int originalLimit = source.limit();
    source.limit(source.position() + length);
    try {
      return bodyMailbox.offer(source);
    } finally {
      source.limit(originalLimit);
    }
  }

  private void failBody(Throwable failure) {
    if (bodyMailbox != null) {
      bodyMailbox.fail(failure);
    }
  }

  private String currentLine() {
    return new String(headerBuffer, 0, lineLength, StandardCharsets.ISO_8859_1);
  }

  private void resetLine() {
    lineLength = 0;
    lineSawCr = false;
  }

  private void requireByte(byte actual, char expected, String message) throws HttpParseException {
    if (actual != (byte) expected) {
      throw new HttpParseException(message);
    }
  }

  private boolean isForbiddenTrailer(String name) {
    return "content-length".equals(name)
        || "transfer-encoding".equals(name)
        || "host".equals(name)
        || "connection".equals(name)
        || "trailer".equals(name)
        || "te".equals(name)
        || "upgrade".equals(name);
  }

  private void parseRequestLine(String line) throws HttpParseException {
    int firstSpace = line.indexOf(' ');
    int secondSpace = firstSpace < 0 ? -1 : line.indexOf(' ', firstSpace + 1);
    if (firstSpace <= 0
        || secondSpace <= firstSpace + 1
        || line.indexOf(' ', secondSpace + 1) >= 0) {
      throw new HttpParseException("invalid request line");
    }
    method = line.substring(0, firstSpace);
    target = line.substring(firstSpace + 1, secondSpace);
    version = line.substring(secondSpace + 1);
    if (!isToken(method)) {
      throw new HttpParseException("invalid method");
    }
    if (target.isEmpty()) {
      throw new HttpParseException("empty request target");
    }
    validateRequestTarget();
    if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) {
      throw new HttpParseException("unsupported HTTP version");
    }
  }

  private void parseHeader(String line, HttpHeaders.Builder builder) throws HttpParseException {
    if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
      throw new HttpParseException("invalid folded or empty header");
    }
    int colon = line.indexOf(':');
    if (colon <= 0) {
      throw new HttpParseException("invalid header line");
    }
    String name = line.substring(0, colon);
    if (!isToken(name)) {
      throw new HttpParseException("invalid header name");
    }
    String value = trimOws(line.substring(colon + 1));
    if (containsControl(value)) {
      throw new HttpParseException("invalid header value");
    }
    builder.add(name, value);
  }

  private int parseContentLength(List<String> values) throws HttpParseException {
    if (values.isEmpty()) {
      return 0;
    }
    Long expected = null;
    for (String value : values) {
      for (String member : value.split(",", -1)) {
        String candidate = trimOws(member);
        long parsed = parseDecimal(candidate, "invalid content-length");
        if (parsed > Integer.MAX_VALUE) {
          throw new HttpParseException("invalid content-length");
        }
        if (expected != null && expected.longValue() != parsed) {
          throw new HttpParseException("conflicting content-length headers");
        }
        expected = parsed;
      }
    }
    return expected == null ? 0 : expected.intValue();
  }

  private void validateHost(List<String> values) throws HttpParseException {
    if (values.isEmpty()) {
      if ("HTTP/1.1".equals(version)) {
        throw new HttpParseException("HTTP/1.1 requires a host header");
      }
      return;
    }
    if (values.size() != 1) {
      throw new HttpParseException("multiple host headers are not allowed");
    }
    String authority = values.get(0);
    if (authority.isEmpty()
        || authority.indexOf(',') >= 0
        || authority.indexOf('/') >= 0
        || authority.indexOf('\\') >= 0
        || authority.indexOf('@') >= 0
        || authority.indexOf('?') >= 0
        || authority.indexOf('#') >= 0
        || containsWhitespace(authority)) {
      throw new HttpParseException("invalid host header");
    }

    String port = null;
    if (authority.charAt(0) == '[') {
      int closing = authority.indexOf(']');
      if (closing <= 1) {
        throw new HttpParseException("invalid host header");
      }
      String suffix = authority.substring(closing + 1);
      if (!suffix.isEmpty()) {
        if (suffix.charAt(0) != ':') {
          throw new HttpParseException("invalid host header");
        }
        port = suffix.substring(1);
      }
    } else {
      int firstColon = authority.indexOf(':');
      int lastColon = authority.lastIndexOf(':');
      if (firstColon != lastColon) {
        throw new HttpParseException("IPv6 host literals must be enclosed in brackets");
      }
      String host = firstColon < 0 ? authority : authority.substring(0, firstColon);
      if (host.isEmpty()) {
        throw new HttpParseException("invalid host header");
      }
      if (firstColon >= 0) {
        port = authority.substring(firstColon + 1);
      }
    }
    if (port != null) {
      long parsedPort = parseDecimal(port, "invalid host port");
      if (parsedPort > 65535) {
        throw new HttpParseException("invalid host port");
      }
    }
  }

  private void validateRequestTarget() throws HttpParseException {
    if ("*".equals(target)) {
      if (!"OPTIONS".equals(method)) {
        throw new HttpParseException("asterisk-form target requires OPTIONS");
      }
      return;
    }
    if (target.charAt(0) != '/') {
      throw new HttpParseException("only origin-form request targets are supported");
    }
    for (int i = 0; i < target.length(); i++) {
      char value = target.charAt(i);
      if (value <= 32 || value >= 127 || value == '\\' || value == '#') {
        throw new HttpParseException("invalid request target");
      }
      if (value == '%') {
        if (i + 2 >= target.length()
            || Character.digit(target.charAt(i + 1), 16) < 0
            || Character.digit(target.charAt(i + 2), 16) < 0) {
          throw new HttpParseException("invalid percent-encoding in request target");
        }
        i += 2;
      }
    }
  }

  private long parseDecimal(String value, String message) throws HttpParseException {
    if (value.isEmpty()) {
      throw new HttpParseException(message);
    }
    long parsed = 0L;
    for (int i = 0; i < value.length(); i++) {
      char digit = value.charAt(i);
      if (digit < '0' || digit > '9' || parsed > (Long.MAX_VALUE - (digit - '0')) / 10L) {
        throw new HttpParseException(message);
      }
      parsed = parsed * 10L + (digit - '0');
    }
    return parsed;
  }

  private boolean containsWhitespace(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isWhitespace(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private boolean isToken(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 32 || c >= 127 || "()<>@,;:\\\"/[]?={}\t".indexOf(c) >= 0) {
        return false;
      }
    }
    return true;
  }

  private String trimOws(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
      start++;
    }
    while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
      end--;
    }
    return value.substring(start, end);
  }

  private boolean containsControl(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ((c < 32 && c != '\t') || c == 127) {
        return true;
      }
    }
    return false;
  }
}
