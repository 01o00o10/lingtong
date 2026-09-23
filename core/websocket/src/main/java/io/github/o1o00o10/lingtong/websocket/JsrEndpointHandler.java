/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketHandler;
import io.github.o1o00o10.lingtong.api.websocket.WebSocketSession;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpointConfig;

/** 将 WebSocket 消息与生命周期事件分派到 JSR 356 端点方法。 */
final class JsrEndpointHandler implements WebSocketHandler {
  /** 无消息。 */
  private static final Object NO_MESSAGE = new Object();
  /** 容器。 */
  private final LingTongServerContainer container;
  /** 端点。 */
  private final Object endpoint;
  /** 配置。 */
  private final EndpointConfig config;
  /** 请求。 */
  private final HttpRequest request;
  /** 请求URI。 */
  private final URI requestUri;
  /** 按键索引的路径参数集合。 */
  private final Map<String, String> pathParameters;
  /** 子协议。 */
  private final String subprotocol;
  /** 已压缩，布尔标志。 */
  private final boolean compressed;
  /** 最大消息字节数，单位为字节。 */
  private final int maxMessageBytes;
  /** 按键索引的握手头部集合。 */
  private final Map<String, List<String>> handshakeHeaders;
  /** 文本流式处理，布尔标志。 */
  private final boolean textStreaming;
  /** 二进制流式处理，布尔标志。 */
  private final boolean binaryStreaming;
  /** 注解标记的文本部分，布尔标志。 */
  private final boolean annotatedTextPartial;
  /** 注解标记的二进制部分，布尔标志。 */
  private final boolean annotatedBinaryPartial;
  /** 文本片段集合。 */
  private final StringBuilder textFragments = new StringBuilder();
  /** 二进制片段集合。 */
  private final java.io.ByteArrayOutputStream binaryFragments = new java.io.ByteArrayOutputStream();
  /** 文本消息活动，布尔标志。 */
  private boolean textMessageActive;
  /** 文本部分交付，布尔标志。 */
  private boolean textPartialDelivery;
  /** 二进制消息活动，布尔标志。 */
  private boolean binaryMessageActive;
  /** 二进制部分交付，布尔标志。 */
  private boolean binaryPartialDelivery;
  /** 已打开的。 */
  private final CompletableFuture<JsrSession> opened = new CompletableFuture<JsrSession>();
  /** 会话。 */
  private volatile JsrSession session;

  JsrEndpointHandler(
      LingTongServerContainer container,
      Object endpoint,
      ServerEndpointConfig config,
      HttpRequest request,
      Map<String, String> pathParameters,
      String subprotocol,
      boolean compressed,
      int maxMessageBytes,
      Map<String, List<String>> handshakeHeaders) {
    this.container = container;
    this.endpoint = endpoint;
    this.config = config;
    this.request = request;
    this.requestUri = null;
    this.pathParameters = pathParameters;
    this.subprotocol = subprotocol == null ? "" : subprotocol;
    this.compressed = compressed;
    this.maxMessageBytes = maxMessageBytes;
    this.handshakeHeaders = handshakeHeaders;
    this.textStreaming = streamingMessage(true);
    this.binaryStreaming = streamingMessage(false);
    this.annotatedTextPartial = partialMessage(true);
    this.annotatedBinaryPartial = partialMessage(false);
  }

  JsrEndpointHandler(
      LingTongServerContainer container,
      Object endpoint,
      ClientEndpointConfig config,
      URI requestUri,
      String subprotocol,
      boolean compressed,
      int maxMessageBytes) {
    this.container = container;
    this.endpoint = endpoint;
    this.config = config;
    this.request = null;
    this.requestUri = requestUri;
    this.pathParameters = Collections.emptyMap();
    this.subprotocol = subprotocol == null ? "" : subprotocol;
    this.compressed = compressed;
    this.maxMessageBytes = maxMessageBytes;
    this.handshakeHeaders = Collections.emptyMap();
    this.textStreaming = streamingMessage(true);
    this.binaryStreaming = streamingMessage(false);
    this.annotatedTextPartial = partialMessage(true);
    this.annotatedBinaryPartial = partialMessage(false);
  }

  @Override
  public List<String> subprotocols() {
    return subprotocol.isEmpty()
        ? Collections.<String>emptyList()
        : Collections.singletonList(subprotocol);
  }

  @Override
  public boolean perMessageDeflate() {
    return compressed;
  }

  @Override
  public Map<String, List<String>> handshakeResponseHeaders() {
    return handshakeHeaders;
  }

  @Override
  public void onOpen(WebSocketSession nativeSession) throws Exception {
    JsrSession created = null;
    try {
      created =
          request == null
              ? new JsrSession(container, nativeSession, config, requestUri, pathParameters)
              : new JsrSession(container, nativeSession, config, request, pathParameters);
      created.setMaxBinaryMessageBufferSize(maxMessageBytes);
      created.setMaxTextMessageBufferSize(maxMessageBytes);
      session = created;
      container.opened(created);
      if (endpoint instanceof Endpoint) {
        ((Endpoint) endpoint).onOpen(created, config);
      } else {
        invokeAnnotated(OnOpen.class, null, null, null);
      }
      opened.complete(created);
    } catch (Exception | Error failure) {
      if (created != null) {
        container.closed(created);
        created.destroyCodecs();
      }
      opened.completeExceptionally(failure);
      throw failure;
    }
  }

  JsrSession awaitOpen() throws Exception {
    try {
      return opened.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while opening WebSocket endpoint", e);
    } catch (ExecutionException e) {
      Throwable failure = e.getCause();
      if (failure instanceof Exception) throw (Exception) failure;
      if (failure instanceof Error) throw (Error) failure;
      throw new IllegalStateException(failure);
    }
  }

  @Override
  public void onText(WebSocketSession ignored, String message) throws Exception {
    JsrSession current = requireSession();
    current.dispatchText(message);
    Object result = invokeAnnotated(OnMessage.class, message, null, null);
    sendResult(result);
  }

  @Override
  public boolean receivesTextFragments() {
    return !textStreaming;
  }

  @Override
  public void onTextFragment(WebSocketSession ignored, String fragment, boolean last)
      throws Exception {
    JsrSession current = requireSession();
    if (!textMessageActive) {
      textMessageActive = true;
      textPartialDelivery = annotatedTextPartial || current.hasTextPartialHandler();
    }
    if (textPartialDelivery) {
      current.dispatchTextPartial(fragment, last);
      if (annotatedTextPartial) {
        sendResult(invokeAnnotated(OnMessage.class, fragment, null, null, last));
      }
    } else {
      textFragments.append(fragment);
    }
    if (last) {
      if (!textPartialDelivery) {
        String message = textFragments.toString();
        textFragments.setLength(0);
        onText(ignored, message);
      }
      textMessageActive = false;
      textPartialDelivery = false;
    }
  }

  @Override
  public void onBinary(WebSocketSession ignored, ByteBuffer message) throws Exception {
    JsrSession current = requireSession();
    current.dispatchBinary(message);
    Object result = invokeAnnotated(OnMessage.class, message, null, null);
    sendResult(result);
  }

  @Override
  public boolean receivesBinaryFragments() {
    return !binaryStreaming;
  }

  @Override
  public void onBinaryFragment(WebSocketSession ignored, ByteBuffer fragment, boolean last)
      throws Exception {
    JsrSession current = requireSession();
    if (!binaryMessageActive) {
      binaryMessageActive = true;
      binaryPartialDelivery = annotatedBinaryPartial || current.hasBinaryPartialHandler();
    }
    if (binaryPartialDelivery) {
      current.dispatchBinaryPartial(fragment, last);
      if (annotatedBinaryPartial) {
        sendResult(invokeAnnotated(OnMessage.class, fragment.asReadOnlyBuffer(), null, null, last));
      }
    } else {
      ByteBuffer source = fragment.asReadOnlyBuffer();
      byte[] bytes = new byte[source.remaining()];
      source.get(bytes);
      binaryFragments.write(bytes);
    }
    if (last) {
      if (!binaryPartialDelivery) {
        ByteBuffer message = ByteBuffer.wrap(binaryFragments.toByteArray());
        binaryFragments.reset();
        onBinary(ignored, message);
      }
      binaryMessageActive = false;
      binaryPartialDelivery = false;
    }
  }

  @Override
  public boolean streamsTextMessages() {
    return textStreaming;
  }

  @Override
  public void onTextStream(WebSocketSession ignored, Reader message) throws Exception {
    Object result = invokeAnnotated(OnMessage.class, message, null, null);
    sendResult(result);
  }

  @Override
  public boolean streamsBinaryMessages() {
    return binaryStreaming;
  }

  @Override
  public void onBinaryStream(WebSocketSession ignored, InputStream message) throws Exception {
    Object result = invokeAnnotated(OnMessage.class, message, null, null);
    sendResult(result);
  }

  @Override
  public void onPong(WebSocketSession ignored, ByteBuffer payload) throws Exception {
    final ByteBuffer copy = payload.asReadOnlyBuffer();
    requireSession().dispatchPong(copy);
    invokeAnnotated(
        OnMessage.class,
        new PongMessage() {
          @Override
          public ByteBuffer getApplicationData() {
            return copy.asReadOnlyBuffer();
          }
        },
        null,
        null);
  }

  @Override
  public void onClose(WebSocketSession ignored, int statusCode, String reason) throws Exception {
    JsrSession current = session;
    if (current == null) return;
    CloseReason closeReason =
        new CloseReason(
            CloseReason.CloseCodes.getCloseCode(statusCode), reason == null ? "" : reason);
    try {
      if (endpoint instanceof Endpoint) ((Endpoint) endpoint).onClose(current, closeReason);
      else invokeAnnotated(OnClose.class, null, closeReason, null);
    } finally {
      container.closed(current);
      current.destroyCodecs();
    }
  }

  @Override
  public void onError(WebSocketSession ignored, Throwable failure) {
    JsrSession current = session;
    try {
      if (endpoint instanceof Endpoint) ((Endpoint) endpoint).onError(current, failure);
      else invokeAnnotated(OnError.class, null, null, failure);
    } catch (Exception suppressed) {
      failure.addSuppressed(suppressed);
    }
  }

  private Object invokeAnnotated(
      Class<? extends Annotation> annotation,
      Object message,
      CloseReason closeReason,
      Throwable failure)
      throws Exception {
    return invokeAnnotated(annotation, message, closeReason, failure, true);
  }

  private Object invokeAnnotated(
      Class<? extends Annotation> annotation,
      Object message,
      CloseReason closeReason,
      Throwable failure,
      boolean last)
      throws Exception {
    for (Method method : endpoint.getClass().getMethods()) {
      if (!method.isAnnotationPresent(annotation)) continue;
      Object methodMessage = message;
      if (annotation == OnMessage.class) {
        methodMessage = decodedMessage(method, message);
        if (methodMessage == NO_MESSAGE) continue;
      }
      try {
        return method.invoke(
            endpoint, arguments(method, methodMessage, closeReason, failure, last));
      } catch (InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) throw (Exception) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new IllegalStateException(cause);
      }
    }
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Object decodedMessage(Method method, Object message) throws Exception {
    if (message == null) return NO_MESSAGE;
    Class<?> target = messageParameter(method);
    if (target == null) return NO_MESSAGE;
    if (target.isInstance(message)) return message;
    if (message instanceof Reader) {
      for (Decoder decoder : requireSession().decoders()) {
        if (!(decoder instanceof Decoder.TextStream)) continue;
        try {
          Object decoded = ((Decoder.TextStream) decoder).decode((Reader) message);
          if (decoded != null && target.isInstance(decoded)) return decoded;
        } catch (ClassCastException ignored) {
          // This decoder targets another application type.
        }
      }
      return NO_MESSAGE;
    }
    if (message instanceof InputStream) {
      for (Decoder decoder : requireSession().decoders()) {
        if (!(decoder instanceof Decoder.BinaryStream)) continue;
        try {
          Object decoded = ((Decoder.BinaryStream) decoder).decode((InputStream) message);
          if (decoded != null && target.isInstance(decoded)) return decoded;
        } catch (ClassCastException ignored) {
          // This decoder targets another application type.
        }
      }
      return NO_MESSAGE;
    }
    if (message instanceof String && Reader.class.isAssignableFrom(target)) {
      return new StringReader((String) message);
    }
    if (message instanceof String && primitiveTextType(target)) {
      return convertText((String) message, target);
    }
    if (message instanceof ByteBuffer && target == byte[].class) {
      ByteBuffer source = ((ByteBuffer) message).asReadOnlyBuffer();
      byte[] bytes = new byte[source.remaining()];
      source.get(bytes);
      return bytes;
    }
    if (message instanceof ByteBuffer && InputStream.class.isAssignableFrom(target)) {
      ByteBuffer source = ((ByteBuffer) message).asReadOnlyBuffer();
      byte[] bytes = new byte[source.remaining()];
      source.get(bytes);
      return new ByteArrayInputStream(bytes);
    }
    for (Decoder decoder : requireSession().decoders()) {
      try {
        Object decoded = null;
        if (message instanceof String
            && decoder instanceof Decoder.Text
            && ((Decoder.Text) decoder).willDecode((String) message)) {
          decoded = ((Decoder.Text) decoder).decode((String) message);
        } else if (message instanceof String && decoder instanceof Decoder.TextStream) {
          decoded = ((Decoder.TextStream) decoder).decode(new StringReader((String) message));
        } else if (message instanceof ByteBuffer
            && decoder instanceof Decoder.Binary
            && ((Decoder.Binary) decoder).willDecode(((ByteBuffer) message).asReadOnlyBuffer())) {
          decoded = ((Decoder.Binary) decoder).decode(((ByteBuffer) message).asReadOnlyBuffer());
        } else if (message instanceof ByteBuffer && decoder instanceof Decoder.BinaryStream) {
          ByteBuffer source = ((ByteBuffer) message).asReadOnlyBuffer();
          byte[] bytes = new byte[source.remaining()];
          source.get(bytes);
          decoded = ((Decoder.BinaryStream) decoder).decode(new ByteArrayInputStream(bytes));
        }
        if (decoded != null && target.isInstance(decoded)) return decoded;
      } catch (ClassCastException ignored) {
        // This decoder targets another application type.
      }
    }
    return NO_MESSAGE;
  }

  private Class<?> messageParameter(Method method) {
    Class<?>[] types = method.getParameterTypes();
    Annotation[][] annotations = method.getParameterAnnotations();
    Class<?> booleanCandidate = null;
    for (int i = 0; i < types.length; i++) {
      if (annotation(annotations[i], PathParam.class) != null) continue;
      Class<?> type = types[i];
      if (Session.class.isAssignableFrom(type) || EndpointConfig.class.isAssignableFrom(type))
        continue;
      if (type == boolean.class || type == Boolean.class) {
        booleanCandidate = type;
        continue;
      }
      return type;
    }
    return booleanCandidate;
  }

  private boolean streamingMessage(boolean text) {
    for (Method method : endpoint.getClass().getMethods()) {
      if (!method.isAnnotationPresent(OnMessage.class)) continue;
      Class<?> target = messageParameter(method);
      if (target == null) continue;
      if (text && Reader.class.isAssignableFrom(target)) return true;
      if (!text && InputStream.class.isAssignableFrom(target)) return true;
      for (Class<? extends Decoder> decoder : config.getDecoders()) {
        if (text && Decoder.TextStream.class.isAssignableFrom(decoder)) return true;
        if (!text && Decoder.BinaryStream.class.isAssignableFrom(decoder)) return true;
      }
    }
    return false;
  }

  private boolean partialMessage(boolean text) {
    for (Method method : endpoint.getClass().getMethods()) {
      if (!method.isAnnotationPresent(OnMessage.class)) continue;
      Class<?> target = messageParameter(method);
      if (target == null || target == boolean.class || target == Boolean.class) continue;
      boolean flag = false;
      for (Class<?> type : method.getParameterTypes()) {
        if (type == boolean.class || type == Boolean.class) flag = true;
      }
      if (!flag) continue;
      if (text && target == String.class) return true;
      if (!text && (target == ByteBuffer.class || target == byte[].class)) return true;
    }
    return false;
  }

  private Object[] arguments(
      Method method, Object message, CloseReason closeReason, Throwable failure, boolean last) {
    Class<?>[] types = method.getParameterTypes();
    Annotation[][] annotations = method.getParameterAnnotations();
    Object[] values = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      PathParam pathParam = annotation(annotations[i], PathParam.class);
      if (pathParam != null) values[i] = convert(pathParameters.get(pathParam.value()), types[i]);
      else if (Session.class.isAssignableFrom(types[i])) values[i] = session;
      else if (EndpointConfig.class.isAssignableFrom(types[i])) values[i] = config;
      else if (message != null && accepts(types[i], message)) values[i] = message;
      else if (CloseReason.class.isAssignableFrom(types[i])) values[i] = closeReason;
      else if (Throwable.class.isAssignableFrom(types[i])) values[i] = failure;
      else if (types[i] == boolean.class || types[i] == Boolean.class) {
        values[i] = Boolean.valueOf(last);
      } else
        throw new IllegalArgumentException("unsupported endpoint parameter " + types[i].getName());
    }
    return values;
  }

  private void sendResult(Object result) throws Exception {
    if (result != null) requireSession().getBasicRemote().sendObject(result);
  }

  private JsrSession requireSession() {
    JsrSession current = session;
    if (current == null) throw new IllegalStateException("WebSocket endpoint is not open");
    return current;
  }

  private static Object convert(String value, Class<?> type) {
    if (type == String.class) return value;
    if (type == int.class || type == Integer.class) return Integer.valueOf(value);
    if (type == long.class || type == Long.class) return Long.valueOf(value);
    if (type == boolean.class || type == Boolean.class) return Boolean.valueOf(value);
    if (type == short.class || type == Short.class) return Short.valueOf(value);
    if (type == byte.class || type == Byte.class) return Byte.valueOf(value);
    if (type == double.class || type == Double.class) return Double.valueOf(value);
    if (type == float.class || type == Float.class) return Float.valueOf(value);
    if (type == char.class || type == Character.class) {
      if (value == null || value.length() != 1)
        throw new IllegalArgumentException("invalid char path parameter");
      return Character.valueOf(value.charAt(0));
    }
    throw new IllegalArgumentException("unsupported @PathParam type " + type.getName());
  }

  private static boolean primitiveTextType(Class<?> type) {
    return type == boolean.class
        || type == Boolean.class
        || type == byte.class
        || type == Byte.class
        || type == short.class
        || type == Short.class
        || type == int.class
        || type == Integer.class
        || type == long.class
        || type == Long.class
        || type == float.class
        || type == Float.class
        || type == double.class
        || type == Double.class
        || type == char.class
        || type == Character.class;
  }

  private static Object convertText(String value, Class<?> type) {
    if (type == boolean.class || type == Boolean.class) return Boolean.valueOf(value);
    if (type == byte.class || type == Byte.class) return Byte.valueOf(value);
    if (type == short.class || type == Short.class) return Short.valueOf(value);
    if (type == int.class || type == Integer.class) return Integer.valueOf(value);
    if (type == long.class || type == Long.class) return Long.valueOf(value);
    if (type == float.class || type == Float.class) return Float.valueOf(value);
    if (type == double.class || type == Double.class) return Double.valueOf(value);
    if (value.length() == 1) return Character.valueOf(value.charAt(0));
    throw new IllegalArgumentException("text message is not a single character");
  }

  private static boolean accepts(Class<?> type, Object value) {
    if (type.isInstance(value)) return true;
    if (!type.isPrimitive()) return false;
    return type == boolean.class && value instanceof Boolean
        || type == byte.class && value instanceof Byte
        || type == short.class && value instanceof Short
        || type == int.class && value instanceof Integer
        || type == long.class && value instanceof Long
        || type == float.class && value instanceof Float
        || type == double.class && value instanceof Double
        || type == char.class && value instanceof Character;
  }

  private static <T extends Annotation> T annotation(Annotation[] values, Class<T> type) {
    for (Annotation value : values) if (type.isInstance(value)) return type.cast(value);
    return null;
  }
}
