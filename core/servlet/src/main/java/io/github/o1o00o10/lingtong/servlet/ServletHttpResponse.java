/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.HttpTrailerFields;
import io.github.o1o00o10.lingtong.http.ResponseBodyMailbox;
import io.github.o1o00o10.lingtong.http.StreamingHttpResponse;
import io.github.o1o00o10.lingtong.transport.StreamingResponseConsumer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

/** 实现 Servlet 响应提交、错误页、Cookie、Trailer 与流式 Body 输出。 */
final class ServletHttpResponse implements HttpServletResponse {
  /** 按键索引的头部集合。 */
  private final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
  /** 非流式模式下暂存的响应体字节。 */
  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  /** 输出流。 */
  private final ServletOutputStream outputStream = new BodyOutputStream();
  /** 最大消息体字节数，单位为字节。 */
  private final int maxBodyBytes;
  /** 响应消息体缓冲区字节数，单位为字节。 */
  private final int responseBodyBufferBytes;
  /** 响应消息体低水位字节数，单位为字节。 */
  private final int responseBodyLowWaterBytes;
  /** 流式模式下接收头部发布及后续 Body 数据的传输消费者。 */
  private final StreamingResponseConsumer streamingConsumer;
  /** 默认字符编码。 */
  private final String defaultCharacterEncoding;
  /** 按键索引的地区编码映射集合。 */
  private final Map<String, String> localeEncodingMappings;

  /** 状态码。 */
  private int status = SC_OK;
  /** 与状态码对应的响应原因短语。 */
  private String reason = "OK";
  /** 字符编码。 */
  private String characterEncoding = "ISO-8859-1";
  /** 内容类型。 */
  private String contentType;
  /** 地区。 */
  private Locale locale = Locale.getDefault();
  /** 缓冲区大小。 */
  private int bufferSize = 8192;
  /** 头部已经提交，之后不得再改状态码或响应头。 */
  private boolean committed;
  /** 输出流已认领的，布尔标志。 */
  private boolean outputStreamClaimed;
  /** 写入器已认领的，布尔标志。 */
  private boolean writerClaimed;
  /** 业务是否显式设置字符编码；影响 Content-Type 的生成。 */
  private boolean characterEncodingSet;
  /** 输出已关闭标志，布尔标志。 */
  private boolean outputClosed;
  /** 错误待处理，布尔标志。 */
  private boolean errorPending;
  /** 错误消息。 */
  private String errorMessage;
  /** 写入器。 */
  private PrintWriter writer;
  /** 异步上下文。 */
  private ServletAsyncContext asyncContext;
  /** 流式模式的有界 Body 邮箱，生产与网络写入分属不同线程。 */
  private ResponseBodyMailbox responseBody;
  /** 流式响应头部是否已经发布给传输层。 */
  private boolean streamingPublished;
  /** 业务已完成但尚待发布最终响应的标记。 */
  private boolean completedPublishPending;
  /** 请求。 */
  private ServletHttpRequest request;
  /** 业务提供的 Trailer 字段，响应结束时求值。 */
  private Supplier<Map<String, String>> trailerFields;
  /** 完成后冻结的 Trailer 快照。 */
  private HttpHeaders completedTrailers = HttpHeaders.builder().build();

  ServletHttpResponse(long maxBodyBytes) {
    this(maxBodyBytes, null);
  }

  ServletHttpResponse(long maxBodyBytes, String defaultCharacterEncoding) {
    this(
        maxBodyBytes,
        64 * 1024,
        32 * 1024,
        null,
        defaultCharacterEncoding,
        Collections.<String, String>emptyMap());
  }

  ServletHttpResponse(
      long maxBodyBytes,
      int responseBodyBufferBytes,
      int responseBodyLowWaterBytes,
      StreamingResponseConsumer streamingConsumer) {
    this(
        maxBodyBytes,
        responseBodyBufferBytes,
        responseBodyLowWaterBytes,
        streamingConsumer,
        null,
        Collections.<String, String>emptyMap());
  }

  ServletHttpResponse(
      long maxBodyBytes,
      int responseBodyBufferBytes,
      int responseBodyLowWaterBytes,
      StreamingResponseConsumer streamingConsumer,
      String defaultCharacterEncoding) {
    this(
        maxBodyBytes,
        responseBodyBufferBytes,
        responseBodyLowWaterBytes,
        streamingConsumer,
        defaultCharacterEncoding,
        Collections.<String, String>emptyMap());
  }

  ServletHttpResponse(
      long maxBodyBytes,
      int responseBodyBufferBytes,
      int responseBodyLowWaterBytes,
      StreamingResponseConsumer streamingConsumer,
      String defaultCharacterEncoding,
      Map<String, String> localeEncodingMappings) {
    if (maxBodyBytes <= 0L || maxBodyBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("response body limit is out of range");
    }
    if (responseBodyBufferBytes <= 0
        || responseBodyLowWaterBytes < 0
        || responseBodyLowWaterBytes >= responseBodyBufferBytes) {
      throw new IllegalArgumentException("invalid response body mailbox limits");
    }
    this.maxBodyBytes = (int) maxBodyBytes;
    this.responseBodyBufferBytes = responseBodyBufferBytes;
    this.responseBodyLowWaterBytes = responseBodyLowWaterBytes;
    this.streamingConsumer = streamingConsumer;
    this.defaultCharacterEncoding =
        defaultCharacterEncoding == null
            ? "ISO-8859-1"
            : Charset.forName(defaultCharacterEncoding).name();
    this.characterEncoding = this.defaultCharacterEncoding;
    this.localeEncodingMappings =
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(localeEncodingMappings));
  }

  void attachAsyncContext(ServletAsyncContext value) {
    asyncContext = value;
  }

  void attachRequest(ServletHttpRequest value) {
    request = value;
  }

  io.github.o1o00o10.lingtong.http.HttpResponse build() throws IOException {
    if (!outputClosed) {
      flushWriter();
    }
    completedTrailers = responseTrailers();
    return new io.github.o1o00o10.lingtong.http.HttpResponse(
        status, reason, responseHeaders(), body.toByteArray(), completedTrailers);
  }

  void prepareStreamingCompletion() throws IOException {
    if (!outputClosed) {
      flushWriter();
    }
    completedTrailers = responseTrailers();
    if (!streamingPublished && body.size() <= responseBodyBufferBytes) {
      publishCompletedStreaming();
      outputClosed = true;
      return;
    }
    commitStreaming();
    outputClosed = true;
  }

  void completeStreaming() {
    responseBody.complete(completedTrailers);
    if (completedPublishPending) {
      completedPublishPending = false;
      streamingPublished = true;
      streamingConsumer.accept(
          new StreamingHttpResponse(status, reason, responseHeaders(), responseBody));
    }
  }

  void failStreaming(Throwable failure) {
    if (responseBody != null) {
      responseBody.fail(failure);
    }
  }

  private HttpHeaders responseHeaders() {
    HttpHeaders.Builder resultHeaders = HttpHeaders.builder();
    if (contentType != null && !containsHeader("content-type")) {
      String value = contentType;
      if (writerClaimed && !containsIgnoreCase(contentType, "charset=")) {
        value += "; charset=" + characterEncoding;
      }
      resultHeaders.add("content-type", value);
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      for (String value : entry.getValue()) {
        resultHeaders.add(entry.getKey(), value);
      }
    }
    return resultHeaders.build();
  }

  private HttpHeaders responseTrailers() {
    if (trailerFields == null) {
      return HttpHeaders.builder().build();
    }
    Map<String, String> supplied = trailerFields.get();
    if (supplied == null || supplied.isEmpty()) {
      return HttpHeaders.builder().build();
    }
    HttpHeaders.Builder trailers = HttpHeaders.builder();
    for (Map.Entry<String, String> entry : supplied.entrySet()) {
      if (HttpTrailerFields.isForbidden(entry.getKey())) {
        continue;
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("HTTP trailer value must not be null");
      }
      trailers.add(entry.getKey(), entry.getValue());
    }
    return trailers.build();
  }

  void completeForward() throws IOException {
    if (outputClosed) {
      return;
    }
    flushWriter();
    committed = true;
    outputClosed = true;
  }

  boolean isErrorPending() {
    return errorPending;
  }

  boolean isStreamingPublished() {
    return streamingPublished;
  }

  String errorMessage() {
    return errorMessage;
  }

  void beginErrorDispatch(int errorStatus) {
    body.reset();
    status = errorStatus;
    reason = reason(errorStatus);
    committed = false;
    outputClosed = false;
    errorPending = false;
    recreateWriter();
  }

  void completeErrorDispatch() throws IOException {
    completeForward();
  }

  void completeDefaultError() throws IOException {
    int errorStatus = status;
    String message = errorMessage;
    beginErrorDispatch(errorStatus);
    setContentType("text/plain");
    getWriter().write(message == null ? reason : message);
    completeErrorDispatch();
  }

  @Override
  public void addCookie(Cookie cookie) {
    if (cookie == null) {
      throw new IllegalArgumentException("cookie must not be null");
    }
    StringBuilder encoded =
        new StringBuilder(cookie.getName()).append('=').append(cookie.getValue());
    if (cookie.getPath() != null) {
      encoded.append("; Path=").append(cookie.getPath());
    }
    if (cookie.getDomain() != null) {
      encoded.append("; Domain=").append(cookie.getDomain());
    }
    if (cookie.getMaxAge() >= 0) {
      encoded.append("; Max-Age=").append(cookie.getMaxAge());
    }
    if (cookie.getSecure()) {
      encoded.append("; Secure");
    }
    if (cookie.isHttpOnly()) {
      encoded.append("; HttpOnly");
    }
    if (cookie instanceof SessionCookieValue && ((SessionCookieValue) cookie).sameSite() != null) {
      encoded.append("; SameSite=").append(((SessionCookieValue) cookie).sameSite());
    }
    addHeader("set-cookie", encoded.toString());
  }

  @Override
  public boolean containsHeader(String name) {
    return headers.containsKey(normalize(name));
  }

  @Override
  public String encodeURL(String url) {
    return request == null ? url : request.encodeSessionURL(url);
  }

  @Override
  public String encodeRedirectURL(String url) {
    return encodeURL(url);
  }

  @Deprecated
  @Override
  public String encodeUrl(String url) {
    return encodeURL(url);
  }

  @Deprecated
  @Override
  public String encodeRedirectUrl(String url) {
    return encodeRedirectURL(url);
  }

  @Override
  public void sendError(int statusCode, String message) throws IOException {
    requireNotCommitted();
    resetBuffer();
    setStatus(statusCode);
    errorMessage = message;
    errorPending = true;
    committed = true;
    outputClosed = true;
  }

  @Override
  public void sendError(int statusCode) throws IOException {
    sendError(statusCode, null);
  }

  @Override
  public void sendRedirect(String location) throws IOException {
    requireNotCommitted();
    resetBuffer();
    setStatus(SC_FOUND);
    setHeader("location", location);
    committed = true;
  }

  @Override
  public void setDateHeader(String name, long date) {
    setHeader(name, formatDate(date));
  }

  @Override
  public void addDateHeader(String name, long date) {
    addHeader(name, formatDate(date));
  }

  @Override
  public void setHeader(String name, String value) {
    if (committed) {
      return;
    }
    List<String> values = new ArrayList<String>();
    values.add(value);
    headers.put(normalize(name), values);
  }

  @Override
  public void addHeader(String name, String value) {
    if (committed) {
      return;
    }
    String normalized = normalize(name);
    List<String> values = headers.get(normalized);
    if (values == null) {
      values = new ArrayList<String>();
      headers.put(normalized, values);
    }
    values.add(value);
  }

  @Override
  public void setIntHeader(String name, int value) {
    setHeader(name, Integer.toString(value));
  }

  @Override
  public void addIntHeader(String name, int value) {
    addHeader(name, Integer.toString(value));
  }

  @Override
  public void setStatus(int statusCode) {
    if (!committed) {
      status = statusCode;
      reason = reason(statusCode);
    }
  }

  @Deprecated
  @Override
  public void setStatus(int statusCode, String message) {
    if (!committed) {
      status = statusCode;
      reason = message == null ? reason(statusCode) : message;
    }
  }

  @Override
  public int getStatus() {
    return status;
  }

  @Override
  public String getHeader(String name) {
    List<String> values = headers.get(normalize(name));
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  @Override
  public Collection<String> getHeaders(String name) {
    List<String> values = headers.get(normalize(name));
    return values == null ? Collections.<String>emptyList() : Collections.unmodifiableList(values);
  }

  @Override
  public Collection<String> getHeaderNames() {
    return Collections.unmodifiableSet(headers.keySet());
  }

  @Override
  public void setTrailerFields(Supplier<Map<String, String>> supplier) {
    requireNotCommitted();
    trailerFields = supplier;
  }

  @Override
  public Supplier<Map<String, String>> getTrailerFields() {
    return trailerFields;
  }

  @Override
  public String getCharacterEncoding() {
    return characterEncoding;
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  @Override
  public ServletOutputStream getOutputStream() {
    if (writerClaimed) {
      throw new IllegalStateException("getWriter() has already been called");
    }
    outputStreamClaimed = true;
    return outputStream;
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    if (outputStreamClaimed) {
      throw new IllegalStateException("getOutputStream() has already been called");
    }
    writerClaimed = true;
    if (writer == null) {
      writer = new PrintWriter(new OutputStreamWriter(outputStream, characterEncoding));
    }
    return writer;
  }

  @Override
  public void setCharacterEncoding(String charset) {
    if (!committed && !writerClaimed && charset != null) {
      characterEncoding = Charset.forName(charset).name();
      characterEncodingSet = true;
    }
  }

  @Override
  public void setContentLength(int length) {
    setIntHeader("content-length", length);
  }

  @Override
  public void setContentLengthLong(long length) {
    setHeader("content-length", Long.toString(length));
  }

  @Override
  public void setContentType(String type) {
    if (committed) {
      return;
    }
    contentType = type;
    String charset = charsetFrom(type);
    if (charset != null && !writerClaimed) {
      setCharacterEncoding(charset);
    }
  }

  @Override
  public void setBufferSize(int size) {
    if (committed || body.size() > 0) {
      throw new IllegalStateException("response body has already been written");
    }
    if (size <= 0) {
      throw new IllegalArgumentException("buffer size must be positive");
    }
    bufferSize = size;
  }

  @Override
  public int getBufferSize() {
    return bufferSize;
  }

  @Override
  public void flushBuffer() throws IOException {
    flushWriter();
    committed = true;
    if (streamingConsumer != null) {
      commitStreaming();
    }
  }

  @Override
  public void resetBuffer() {
    requireNotCommitted();
    body.reset();
    recreateWriter();
  }

  @Override
  public boolean isCommitted() {
    return committed;
  }

  @Override
  public void reset() {
    requireNotCommitted();
    body.reset();
    headers.clear();
    status = SC_OK;
    reason = "OK";
    contentType = null;
    characterEncoding = defaultCharacterEncoding;
    characterEncodingSet = false;
    locale = Locale.getDefault();
    errorPending = false;
    errorMessage = null;
    outputClosed = false;
    trailerFields = null;
    completedTrailers = HttpHeaders.builder().build();
    recreateWriter();
  }

  @Override
  public void setLocale(Locale value) {
    if (!committed && value != null) {
      locale = value;
      setHeader("content-language", value.toLanguageTag());
      if (!writerClaimed && !characterEncodingSet) {
        String mapped = localeEncoding(value);
        if (mapped != null) {
          characterEncoding = mapped;
        }
      }
    }
  }

  @Override
  public Locale getLocale() {
    return locale;
  }

  private String localeEncoding(Locale value) {
    String encoding = localeEncodingMappings.get(normalizeLocale(value.toString()));
    if (encoding == null) {
      encoding = localeEncodingMappings.get(normalizeLocale(value.toLanguageTag()));
    }
    if (encoding == null) {
      encoding = localeEncodingMappings.get(normalizeLocale(value.getLanguage()));
    }
    return encoding;
  }

  private static String normalizeLocale(String value) {
    return value.replace('-', '_').toLowerCase(Locale.ROOT);
  }

  private void flushWriter() throws IOException {
    if (writer != null) {
      writer.flush();
      if (writer.checkError()) {
        throw new IOException("failed to encode Servlet response");
      }
    }
  }

  private void commitStreaming() throws IOException {
    if (streamingConsumer == null || streamingPublished) {
      return;
    }
    committed = true;
    responseBody =
        new ResponseBodyMailbox(responseBodyBufferBytes, responseBodyLowWaterBytes, maxBodyBytes);
    streamingPublished = true;
    try {
      streamingConsumer.accept(
          new StreamingHttpResponse(status, reason, responseHeaders(), responseBody, body.size()));
      byte[] buffered = body.toByteArray();
      body.reset();
      if (buffered.length > 0) {
        responseBody.writeBlocking(buffered, 0, buffered.length);
      }
    } catch (IOException | RuntimeException e) {
      responseBody.fail(e);
      throw e;
    }
  }

  private void publishCompletedStreaming() throws IOException {
    committed = true;
    responseBody =
        new ResponseBodyMailbox(responseBodyBufferBytes, responseBodyLowWaterBytes, maxBodyBytes);
    byte[] buffered = body.toByteArray();
    body.reset();
    if (buffered.length > 0) {
      int accepted = responseBody.offer(buffered, 0, buffered.length);
      if (accepted != buffered.length) {
        throw new IOException("completed response does not fit its response mailbox");
      }
    }
    completedPublishPending = true;
  }

  private void requireNotCommitted() {
    if (committed) {
      throw new IllegalStateException("response is already committed");
    }
  }

  private void recreateWriter() {
    if (writerClaimed) {
      writer =
          new PrintWriter(new OutputStreamWriter(outputStream, Charset.forName(characterEncoding)));
    }
  }

  private String formatDate(long date) {
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(
        Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC));
  }

  private static String normalize(String name) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("header name must not be empty");
    }
    return name.toLowerCase(Locale.ROOT);
  }

  private static String charsetFrom(String value) {
    if (value == null) {
      return null;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    int position = lower.indexOf("charset=");
    if (position < 0) {
      return null;
    }
    String charset = value.substring(position + 8).trim();
    int separator = charset.indexOf(';');
    return separator < 0 ? charset : charset.substring(0, separator).trim();
  }

  private static boolean containsIgnoreCase(String value, String expected) {
    return value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
  }

  private static String reason(int value) {
    switch (value) {
      case 200:
        return "OK";
      case 201:
        return "Created";
      case 202:
        return "Accepted";
      case 204:
        return "No Content";
      case 206:
        return "Partial Content";
      case 301:
        return "Moved Permanently";
      case 302:
        return "Found";
      case 304:
        return "Not Modified";
      case 400:
        return "Bad Request";
      case 401:
        return "Unauthorized";
      case 403:
        return "Forbidden";
      case 404:
        return "Not Found";
      case 405:
        return "Method Not Allowed";
      case 408:
        return "Request Timeout";
      case 409:
        return "Conflict";
      case 413:
        return "Payload Too Large";
      case 415:
        return "Unsupported Media Type";
      case 416:
        return "Range Not Satisfiable";
      case 500:
        return "Internal Server Error";
      case 501:
        return "Not Implemented";
      case 503:
        return "Service Unavailable";
      default:
        return "Status";
    }
  }

  /** 封装消息体输出流的状态与处理边界。 */
  private final class BodyOutputStream extends ServletOutputStream {
    /** 监听器。 */
    private WriteListener listener;
    /** 就绪标志，布尔标志。 */
    private boolean ready;
    /** failed，布尔标志。 */
    private boolean failed;

    @Override
    public synchronized boolean isReady() {
      if (listener == null) {
        return true;
      }
      return ready
          && (responseBody == null ? body.size() < maxBodyBytes : responseBody.isWritable());
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
      if (writeListener == null) {
        throw new IllegalArgumentException("write listener must not be null");
      }
      synchronized (this) {
        if (listener != null) {
          throw new IllegalStateException("write listener has already been set");
        }
        if (asyncContext == null || !asyncContext.isStarted()) {
          throw new IllegalStateException("non-blocking writes require asynchronous processing");
        }
        listener = writeListener;
        ready = streamingConsumer == null;
      }
      if (streamingConsumer == null) {
        scheduleWritePossible();
        return;
      }
      try {
        commitStreaming();
        responseBody.onWritable(
            new Runnable() {
              @Override
              public void run() {
                scheduleWritePossible();
              }
            });
      } catch (IOException | RuntimeException failure) {
        notifyError(failure);
      }
    }

    @Override
    public void write(int value) throws IOException {
      byte[] single = {(byte) value};
      write(single, 0, 1);
    }

    @Override
    public void write(byte[] values, int offset, int length) throws IOException {
      requireOutputOpen();
      if (streamingConsumer == null) {
        requireCapacity(length);
        body.write(values, offset, length);
        return;
      }
      requireTotalCapacity(length);
      if (!streamingPublished && body.size() <= bufferSize - length) {
        body.write(values, offset, length);
        return;
      }
      commitStreaming();
      synchronized (this) {
        if (listener != null) {
          if (length > responseBody.remainingCapacity()) {
            ready = false;
            throw new IllegalStateException(
                "non-blocking response write exceeds current mailbox capacity");
          }
          int accepted = responseBody.offer(values, offset, length);
          ready = responseBody.isWritable();
          if (accepted != length) {
            throw new IllegalStateException(
                "non-blocking response write exceeds current mailbox capacity");
          }
          return;
        }
      }
      responseBody.writeBlocking(values, offset, length);
    }

    private void requireOutputOpen() throws IOException {
      if (outputClosed) {
        throw new IOException("response output is closed after RequestDispatcher.forward");
      }
      synchronized (this) {
        if (listener != null && !ready) {
          throw new IllegalStateException("non-blocking response output is not ready");
        }
      }
    }

    private void scheduleWritePossible() {
      Throwable bodyFailure = responseBody == null ? null : responseBody.failure();
      if (bodyFailure != null) {
        notifyError(bodyFailure);
        return;
      }
      synchronized (this) {
        if (failed || listener == null) {
          return;
        }
        ready = responseBody == null || responseBody.isWritable();
        if (!ready) {
          return;
        }
      }
      try {
        asyncContext.executeIoCallback(
            new Runnable() {
              @Override
              public void run() {
                notifyWritePossible();
              }
            });
      } catch (RuntimeException failure) {
        notifyError(failure);
      }
    }

    private void notifyWritePossible() {
      synchronized (this) {
        if (failed || !ready) {
          return;
        }
      }
      try {
        listener.onWritePossible();
      } catch (Throwable failure) {
        notifyError(failure);
      }
    }

    private void requireCapacity(int length) throws IOException {
      if (length < 0 || body.size() > maxBodyBytes - length) {
        synchronized (this) {
          ready = false;
        }
        throw new IOException(
            "response body exceeds configured limit of " + maxBodyBytes + " bytes");
      }
    }

    private void requireTotalCapacity(int length) throws IOException {
      long written = body.size() + (responseBody == null ? 0L : responseBody.writtenBytes());
      if (length < 0 || written > maxBodyBytes - length) {
        synchronized (this) {
          ready = false;
        }
        throw new IOException(
            "response body exceeds configured limit of " + maxBodyBytes + " bytes");
      }
    }

    private void notifyError(Throwable failure) {
      synchronized (this) {
        if (failed) {
          return;
        }
        failed = true;
        ready = false;
      }
      try {
        listener.onError(failure);
      } catch (RuntimeException ignored) {
        // The original I/O failure remains the request failure.
      }
      asyncContext.failIo(failure);
    }
  }
}
