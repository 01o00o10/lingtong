/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.api.websocket.WebSocketSession;
import io.github.o1o00o10.lingtong.http.HttpRequest;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** 将协议连接适配为 JSR 356 Session，管理消息处理器和远程发送端。 */
final class JsrSession implements Session {
    /** 容器。 */
    private final LingTongServerContainer container;
    /** native会话。 */
    private final WebSocketSession nativeSession;
    /** 端点配置。 */
    private final EndpointConfig endpointConfig;
    /** 请求URI。 */
    private final URI requestUri;
    /** 查询串。 */
    private final String query;
    /** 按键索引的参数集合。 */
    private final Map<String, List<String>> parameters;
    /** 按键索引的路径参数集合。 */
    private final Map<String, String> pathParameters;
    /** 按键索引的用户属性配置。 */
    private final Map<String, Object> userProperties = new LinkedHashMap<String, Object>();
    /** 按键索引的typed处理器集合。 */
    private final Map<Class<?>, List<MessageHandler>> typedHandlers =
            new LinkedHashMap<Class<?>, List<MessageHandler>>();
    /** 处理器集合。 */
    private final Set<MessageHandler> handlers = new LinkedHashSet<MessageHandler>();
    /** 基本认证。 */
    private final RemoteEndpoint.Basic basic;
    /** 异步。 */
    private final RemoteEndpoint.Async async;
    /** 基本认证批处理。 */
    private final Batch basicBatch = new Batch();
    /** 异步批处理。 */
    private final Batch asyncBatch = new Batch();
    /** 按顺序保存的decoders。 */
    private final List<Decoder> decoders;
    /** 按顺序保存的encoders。 */
    private final List<Encoder> encoders;
    /** 最大二进制消息大小。 */
    private int maxBinaryMessageSize;
    /** 最大文本消息大小。 */
    private int maxTextMessageSize;
    /** 空闲超时时长。 */
    private long idleTimeout;
    /** codecs已销毁的，布尔标志。 */
    private boolean codecsDestroyed;

    JsrSession(
            LingTongServerContainer container,
            WebSocketSession nativeSession,
            EndpointConfig endpointConfig,
            HttpRequest request,
            Map<String, String> pathParameters)
            throws InstantiationException, IllegalAccessException {
        this(container, nativeSession, endpointConfig, webSocketUri(request), pathParameters);
    }

    JsrSession(
            LingTongServerContainer container,
            WebSocketSession nativeSession,
            EndpointConfig endpointConfig,
            URI requestUri,
            Map<String, String> pathParameters)
            throws InstantiationException, IllegalAccessException {
        this.container = container;
        this.basic = new BasicRemote();
        this.async = new AsyncRemote();
        this.nativeSession = nativeSession;
        this.endpointConfig = endpointConfig;
        this.decoders = createDecoders(endpointConfig);
        try {
            this.encoders = createEncoders(endpointConfig);
        } catch (InstantiationException | IllegalAccessException | RuntimeException e) {
            for (Decoder decoder : decoders) decoder.destroy();
            throw e;
        }
        this.requestUri = requestUri;
        this.query = requestUri.getRawQuery();
        this.parameters = parseParameters(query);
        this.pathParameters = pathParameters;
        this.userProperties.putAll(endpointConfig.getUserProperties());
        this.maxBinaryMessageSize = container.getDefaultMaxBinaryMessageBufferSize();
        this.maxTextMessageSize = container.getDefaultMaxTextMessageBufferSize();
        this.idleTimeout = container.getDefaultMaxSessionIdleTimeout();
        this.nativeSession.setIdleTimeoutMillis(idleTimeout);
    }

    @Override
    public synchronized void addMessageHandler(MessageHandler handler) {
        if (handler == null) throw new IllegalArgumentException("message handler is required");
        Class<?> inferred = inferMessageType(handler.getClass());
        if (inferred == null) {
            throw new IllegalStateException("cannot determine MessageHandler message type");
        }
        addTyped(inferred, handler);
    }

    @Override
    public synchronized <T> void addMessageHandler(
            Class<T> type, MessageHandler.Whole<T> handler) {
        addTyped(type, handler);
    }

    @Override
    public synchronized <T> void addMessageHandler(
            Class<T> type, MessageHandler.Partial<T> handler) {
        addTyped(type, handler);
    }

    private void addTyped(Class<?> type, MessageHandler handler) {
        if (type == null || handler == null) throw new IllegalArgumentException("handler type is required");
        String nativeType = nativeMessageType(type);
        for (Class<?> registered : typedHandlers.keySet()) {
            if (nativeType.equals(nativeMessageType(registered))) {
                throw new IllegalStateException(
                        "a handler is already registered for " + nativeType + " messages");
            }
        }
        handlers.add(handler);
        typedHandlers.computeIfAbsent(type, key -> new ArrayList<MessageHandler>()).add(handler);
    }

    @Override public synchronized Set<MessageHandler> getMessageHandlers() {
        return Collections.unmodifiableSet(new LinkedHashSet<MessageHandler>(handlers));
    }

    @Override public synchronized void removeMessageHandler(MessageHandler handler) {
        handlers.remove(handler);
        for (List<MessageHandler> values : typedHandlers.values()) values.remove(handler);
    }

    void dispatchText(String message) {
        dispatch(String.class, message);
    }

    void dispatchBinary(ByteBuffer message) {
        if (hasHandler(byte[].class)) {
            ByteBuffer source = message.asReadOnlyBuffer();
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            dispatch(byte[].class, bytes);
        } else {
            dispatch(ByteBuffer.class, message.asReadOnlyBuffer());
        }
    }

    boolean dispatchTextPartial(String fragment, boolean last) {
        return dispatchPartial(String.class, fragment, last);
    }

    synchronized boolean hasTextPartialHandler() {
        return hasPartialHandler(String.class);
    }

    boolean dispatchBinaryPartial(ByteBuffer fragment, boolean last) {
        if (hasHandler(byte[].class)) {
            ByteBuffer source = fragment.asReadOnlyBuffer();
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            return dispatchPartial(byte[].class, bytes, last);
        }
        return dispatchPartial(ByteBuffer.class, fragment.asReadOnlyBuffer(), last);
    }

    synchronized boolean hasBinaryPartialHandler() {
        return hasPartialHandler(byte[].class) || hasPartialHandler(ByteBuffer.class);
    }

    void dispatchPong(final ByteBuffer payload) {
        dispatch(PongMessage.class, new PongMessage() {
            @Override public ByteBuffer getApplicationData() { return payload.asReadOnlyBuffer(); }
        });
    }

    List<Decoder> decoders() {
        return decoders;
    }

    synchronized void destroyCodecs() {
        if (codecsDestroyed) return;
        codecsDestroyed = true;
        for (Decoder decoder : decoders) decoder.destroy();
        for (Encoder encoder : encoders) encoder.destroy();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private synchronized void dispatch(Class<?> type, Object message) {
        List<MessageHandler> values = new ArrayList<MessageHandler>();
        List<MessageHandler> exact = typedHandlers.get(type);
        if (exact != null) values.addAll(exact);
        List<MessageHandler> inferred = typedHandlers.get(Object.class);
        if (inferred != null) values.addAll(inferred);
        for (MessageHandler handler : values) {
            if (handler instanceof MessageHandler.Whole) {
                try {
                    ((MessageHandler.Whole) handler).onMessage(message);
                } catch (ClassCastException ignored) {
                    // An inferred generic handler belongs to another message type.
                }
            } else if (handler instanceof MessageHandler.Partial) {
                try {
                    ((MessageHandler.Partial) handler).onMessage(message, true);
                } catch (ClassCastException ignored) {
                    // An inferred generic handler belongs to another message type.
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private synchronized boolean dispatchPartial(
            Class<?> type, Object message, boolean last) {
        List<MessageHandler> values = typedHandlers.get(type);
        if (values == null) return false;
        boolean handled = false;
        for (MessageHandler handler : new ArrayList<MessageHandler>(values)) {
            if (!(handler instanceof MessageHandler.Partial)) continue;
            ((MessageHandler.Partial) handler).onMessage(message, last);
            handled = true;
        }
        return handled;
    }

    private boolean hasPartialHandler(Class<?> type) {
        List<MessageHandler> values = typedHandlers.get(type);
        if (values == null) return false;
        for (MessageHandler handler : values) {
            if (handler instanceof MessageHandler.Partial) return true;
        }
        return false;
    }

    private synchronized boolean hasHandler(Class<?> type) {
        return typedHandlers.containsKey(type);
    }

    @Override public WebSocketContainer getContainer() { return container; }
    @Override public String getProtocolVersion() { return "13"; }
    @Override public String getNegotiatedSubprotocol() { return nativeSession.negotiatedSubprotocol(); }
    @Override public List<Extension> getNegotiatedExtensions() {
        return nativeSession.negotiatedExtensions().isEmpty()
                ? Collections.<Extension>emptyList()
                : Collections.<Extension>singletonList(PerMessageDeflateExtension.INSTANCE);
    }
    @Override public boolean isSecure() { return "wss".equalsIgnoreCase(requestUri.getScheme()); }
    @Override public boolean isOpen() { return nativeSession.isOpen(); }
    @Override public long getMaxIdleTimeout() { return idleTimeout; }
    @Override public void setMaxIdleTimeout(long value) {
        idleTimeout = value;
        nativeSession.setIdleTimeoutMillis(value);
    }
    @Override public void setMaxBinaryMessageBufferSize(int value) { maxBinaryMessageSize = positive(value); }
    @Override public int getMaxBinaryMessageBufferSize() { return maxBinaryMessageSize; }
    @Override public void setMaxTextMessageBufferSize(int value) { maxTextMessageSize = positive(value); }
    @Override public int getMaxTextMessageBufferSize() { return maxTextMessageSize; }
    @Override public RemoteEndpoint.Async getAsyncRemote() { return async; }
    @Override public RemoteEndpoint.Basic getBasicRemote() { return basic; }
    @Override public String getId() { return nativeSession.id(); }
    @Override public void close() throws IOException {
        flushBeforeClose();
        nativeSession.close();
    }
    @Override public void close(CloseReason reason) throws IOException {
        flushBeforeClose();
        nativeSession.close(reason.getCloseCode().getCode(), reason.getReasonPhrase());
    }
    @Override public URI getRequestURI() { return requestUri; }
    @Override public Map<String, List<String>> getRequestParameterMap() { return parameters; }
    @Override public String getQueryString() { return query; }
    @Override public Map<String, String> getPathParameters() { return pathParameters; }
    @Override public Map<String, Object> getUserProperties() { return userProperties; }
    @Override public Principal getUserPrincipal() { return null; }
    @Override public Set<Session> getOpenSessions() {
        return container.openSessions(endpointConfig);
    }

    EndpointConfig endpointConfig() {
        return endpointConfig;
    }

    /** 封装基本认证远端的状态与处理边界。 */
    private final class BasicRemote implements RemoteEndpoint.Basic {
        @Override public void sendText(String text) throws IOException {
            await(basicBatch.submit(textMessage(text)));
        }
        @Override public void sendBinary(ByteBuffer data) throws IOException {
            int limit = requireData(data).limit();
            await(basicBatch.submit(binaryMessage(data)));
            data.position(limit);
        }
        @Override public synchronized void sendText(String text, boolean last) throws IOException {
            await(basicBatch.submit(textFragmentMessage(text, last)));
        }
        @Override public synchronized void sendBinary(ByteBuffer data, boolean last) throws IOException {
            await(basicBatch.submit(binaryFragmentMessage(data, last)));
            data.position(data.limit());
        }
        @Override public OutputStream getSendStream() throws IOException {
            return new BinaryMessageOutput(basicBatch, true);
        }
        @Override public Writer getSendWriter() throws IOException {
            return new TextMessageWriter(basicBatch, true);
        }
        @Override public void sendObject(Object value) throws IOException, EncodeException {
            await(encodedSend(value, basicBatch, true));
        }
        @Override public void setBatchingAllowed(boolean allowed) throws IOException {
            basicBatch.setAllowed(allowed);
        }
        @Override public boolean getBatchingAllowed() { return basicBatch.isAllowed(); }
        @Override public void flushBatch() throws IOException { await(basicBatch.flush()); }
        @Override public void sendPing(ByteBuffer payload) throws IOException {
            await(basicBatch.submit(pingMessage(payload)));
        }
        @Override public void sendPong(ByteBuffer payload) throws IOException {
            await(basicBatch.submit(pongMessage(payload)));
        }
    }

    /** 封装异步远端的状态与处理边界。 */
    private final class AsyncRemote implements RemoteEndpoint.Async {
        /** 超时时长。 */
        private volatile long timeout = container.getDefaultAsyncSendTimeout();
        @Override public long getSendTimeout() { return timeout; }
        @Override public void setSendTimeout(long value) { timeout = value; }
        @Override public void sendText(String text, SendHandler handler) {
            complete(handler, submit(textMessage(text)));
        }
        @Override public Future<Void> sendText(String text) {
            return future(submit(textMessage(text)));
        }
        @Override public Future<Void> sendBinary(ByteBuffer data) {
            requireData(data);
            int limit = data.limit();
            CompletionStage<Void> send = submit(binaryMessage(data));
            send.whenComplete((ignored, failure) -> { if (failure == null) data.position(limit); });
            return future(send);
        }
        @Override public void sendBinary(ByteBuffer data, SendHandler handler) {
            if (handler == null) throw new IllegalArgumentException("send handler must not be null");
            requireData(data);
            int limit = data.limit();
            CompletionStage<Void> send = submit(binaryMessage(data));
            send.whenComplete((ignored, failure) -> { if (failure == null) data.position(limit); });
            complete(handler, send);
        }
        @Override public Future<Void> sendObject(Object value) {
            if (value == null) throw new IllegalArgumentException("message must not be null");
            try { return future(submit(encodedSend(value, asyncBatch, false))); }
            catch (Throwable failure) { return failedFuture(failure); }
        }
        @Override public void sendObject(Object value, SendHandler handler) {
            if (handler == null) throw new IllegalArgumentException("send handler must not be null");
            if (value == null) throw new IllegalArgumentException("message must not be null");
            try { complete(handler, submit(encodedSend(value, asyncBatch, false))); }
            catch (Throwable failure) { complete(handler, failedStage(failure)); }
        }
        @Override public void setBatchingAllowed(boolean allowed) throws IOException {
            asyncBatch.setAllowed(allowed);
        }
        @Override public boolean getBatchingAllowed() { return asyncBatch.isAllowed(); }
        @Override public void flushBatch() throws IOException { await(asyncBatch.flush()); }
        @Override public void sendPing(ByteBuffer payload) throws IOException {
            await(asyncBatch.submit(pingMessage(payload)));
        }
        @Override public void sendPong(ByteBuffer payload) throws IOException {
            await(asyncBatch.submit(pongMessage(payload)));
        }

        private CompletionStage<Void> submit(OutboundMessage message) {
            return nativeSession.withSendTimeout(asyncBatch.submit(message), timeout);
        }

        private CompletionStage<Void> submit(CompletionStage<Void> send) {
            return nativeSession.withSendTimeout(send, timeout);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private synchronized CompletionStage<Void> encodedSend(
            Object value, Batch batch, boolean blocking)
            throws IOException, EncodeException {
        if (value == null) throw new EncodeException(null, "cannot encode a null message");
        if (value instanceof String) return batch.submit(textMessage((String) value));
        if (value instanceof ByteBuffer) return batch.submit(binaryMessage((ByteBuffer) value));
        if (value instanceof byte[]) {
            return batch.submit(binaryMessage(ByteBuffer.wrap((byte[]) value)));
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof Character) {
            return batch.submit(textMessage(String.valueOf(value)));
        }
        for (Encoder encoder : encoders) {
            try {
                if (encoder instanceof Encoder.Text) {
                    return batch.submit(textMessage(((Encoder.Text) encoder).encode(value)));
                }
                if (encoder instanceof Encoder.Binary) {
                    return batch.submit(binaryMessage(((Encoder.Binary) encoder).encode(value)));
                }
                if (encoder instanceof Encoder.TextStream) {
                    TextMessageWriter target = new TextMessageWriter(batch, blocking);
                    try {
                        ((Encoder.TextStream) encoder).encode(value, target);
                        target.close();
                        return target.completion();
                    } catch (ClassCastException wrongType) {
                        if (target.started()) {
                            target.abort();
                            throw new EncodeException(value,
                                    "text stream encoder failed after output began", wrongType);
                        }
                    } catch (IOException | EncodeException failure) {
                        target.abort();
                        throw failure;
                    } catch (RuntimeException failure) {
                        target.abort();
                        throw failure;
                    }
                }
                if (encoder instanceof Encoder.BinaryStream) {
                    BinaryMessageOutput target = new BinaryMessageOutput(batch, blocking);
                    try {
                        ((Encoder.BinaryStream) encoder).encode(value, target);
                        target.close();
                        return target.completion();
                    } catch (ClassCastException wrongType) {
                        if (target.started()) {
                            target.abort();
                            throw new EncodeException(value,
                                    "binary stream encoder failed after output began", wrongType);
                        }
                    } catch (IOException | EncodeException failure) {
                        target.abort();
                        throw failure;
                    } catch (RuntimeException failure) {
                        target.abort();
                        throw failure;
                    }
                }
            } catch (ClassCastException ignored) {
                // Try the next configured encoder.
            }
        }
        throw new EncodeException(value, "no encoder for " + value.getClass().getName());
    }

    private void complete(SendHandler handler, CompletionStage<Void> send) {
        if (handler == null) throw new IllegalArgumentException("send handler must not be null");
        nativeSession.dispatchCompletion(send).whenComplete((ignored, failure) -> handler.onResult(
                failure == null ? new SendResult() : new SendResult(unwrap(failure))));
    }

    private static Future<Void> future(CompletionStage<Void> send) {
        return send.toCompletableFuture();
    }

    private OutboundMessage textMessage(String text) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        final String copy = text;
        final int size = copy.getBytes(StandardCharsets.UTF_8).length;
        return new OutboundMessage(size) {
            @Override CompletionStage<Void> send() { return nativeSession.sendTextAsync(copy); }
        };
    }

    private OutboundMessage binaryMessage(ByteBuffer data) {
        ByteBuffer source = requireData(data).slice();
        final byte[] copy = new byte[source.remaining()];
        source.get(copy);
        return new OutboundMessage(copy.length) {
            @Override CompletionStage<Void> send() {
                return nativeSession.sendBinaryAsync(ByteBuffer.wrap(copy));
            }
        };
    }

    private OutboundMessage textFragmentMessage(String text, final boolean last) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        final String copy = text;
        final int size = copy.getBytes(StandardCharsets.UTF_8).length;
        return new OutboundMessage(size) {
            @Override CompletionStage<Void> send() {
                return nativeSession.sendTextFragmentAsync(copy, last);
            }
        };
    }

    private OutboundMessage binaryFragmentMessage(ByteBuffer data, final boolean last) {
        ByteBuffer source = requireData(data).slice();
        final byte[] copy = new byte[source.remaining()];
        source.get(copy);
        return new OutboundMessage(copy.length) {
            @Override CompletionStage<Void> send() {
                return nativeSession.sendBinaryFragmentAsync(ByteBuffer.wrap(copy), last);
            }
        };
    }

    private OutboundMessage pingMessage(ByteBuffer data) {
        return controlMessage(data, true);
    }

    private OutboundMessage pongMessage(ByteBuffer data) {
        return controlMessage(data, false);
    }

    private OutboundMessage controlMessage(ByteBuffer data, final boolean ping) {
        ByteBuffer source = data == null ? ByteBuffer.allocate(0) : data.slice();
        if (source.remaining() > 125) {
            throw new IllegalArgumentException("control payload exceeds 125 bytes");
        }
        final byte[] copy = new byte[source.remaining()];
        source.get(copy);
        return new OutboundMessage(copy.length) {
            @Override CompletionStage<Void> send() {
                return ping
                        ? nativeSession.sendPingAsync(ByteBuffer.wrap(copy))
                        : nativeSession.sendPongAsync(ByteBuffer.wrap(copy));
            }
        };
    }

    private void flushBeforeClose() throws IOException {
        await(basicBatch.flush());
        await(asyncBatch.flush());
    }

    private static ByteBuffer requireData(ByteBuffer data) {
        if (data == null) throw new IllegalArgumentException("binary data must not be null");
        return data;
    }

    private static void await(CompletionStage<Void> send) throws IOException {
        try {
            send.toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while sending WebSocket message", e);
        } catch (ExecutionException e) {
            Throwable failure = unwrap(e.getCause());
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            throw new IOException("WebSocket message send failed", failure);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static CompletionStage<Void> failedStage(Throwable failure) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        result.completeExceptionally(failure);
        return result;
    }

    private static Future<Void> failedFuture(Throwable failure) {
        return failedStage(failure).toCompletableFuture();
    }

    /** 封装出站消息的状态与处理边界。 */
    private abstract class OutboundMessage {
        /** 字节数，单位为字节。 */
        private final int bytes;
        private OutboundMessage(int bytes) { this.bytes = bytes; }
        abstract CompletionStage<Void> send();
    }

    /** 封装流式处理消息目标的状态与处理边界。 */
    private abstract class StreamingMessageTarget {
        /** 批处理。 */
        private final Batch batch;
        /** blocking，布尔标志。 */
        private final boolean blocking;
        /** 按顺序保存的sends。 */
        private final List<CompletableFuture<Void>> sends =
                new ArrayList<CompletableFuture<Void>>();
        /** 已启动标志，布尔标志。 */
        private boolean started;

        private StreamingMessageTarget(Batch batch, boolean blocking) {
            this.batch = batch;
            this.blocking = blocking;
        }

        final void submit(OutboundMessage message) throws IOException {
            started = true;
            CompletionStage<Void> send = batch.submit(message);
            sends.add(send.toCompletableFuture());
            if (blocking) await(send);
        }

        final boolean started() { return started; }

        final CompletionStage<Void> completion() {
            return CompletableFuture.allOf(
                    sends.toArray(new CompletableFuture[sends.size()]));
        }
    }

    /** 封装二进制消息输出的状态与处理边界。 */
    private final class BinaryMessageOutput extends OutputStream {
        /** chunk字节数，单位为字节。 */
        private static final int CHUNK_BYTES = 8192;
        /** 目标。 */
        private final StreamingMessageTarget target;
        /** 缓冲区。 */
        private final byte[] buffer = new byte[CHUNK_BYTES];
        /** 数量。 */
        private int count;
        /** 已关闭标志，布尔标志。 */
        private boolean closed;

        private BinaryMessageOutput(Batch batch, boolean blocking) {
            target = new StreamingMessageTarget(batch, blocking) { };
        }

        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (value == null) throw new NullPointerException("value");
            if (offset < 0 || length < 0 || length > value.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            ensureOpen();
            while (length > 0) {
                int copied = Math.min(length, buffer.length - count);
                System.arraycopy(value, offset, buffer, count, copied);
                count += copied;
                offset += copied;
                length -= copied;
                if (count == buffer.length) emit(false);
            }
        }

        @Override public void flush() throws IOException {
            ensureOpen();
            if (count > 0) emit(false);
        }

        @Override public void close() throws IOException {
            if (closed) return;
            emit(true);
            closed = true;
        }

        private void emit(boolean last) throws IOException {
            byte[] value = new byte[count];
            System.arraycopy(buffer, 0, value, 0, count);
            count = 0;
            target.submit(binaryFragmentMessage(ByteBuffer.wrap(value), last));
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("WebSocket message stream is closed");
        }

        private CompletionStage<Void> completion() { return target.completion(); }
        private boolean started() { return target.started(); }
        private void abort() {
            try { if (!closed && started()) close(); } catch (IOException ignored) { }
        }
    }

    /** 封装文本消息写入器的状态与处理边界。 */
    private final class TextMessageWriter extends Writer {
        /** chunkchars。 */
        /** 数据块字符集合（CHUNK_CHARS）。 */
        private static final int CHUNK_CHARS = 4096;
        /** 目标。 */
        private final StreamingMessageTarget target;
        /** 缓冲区。 */
        private final StringBuilder buffer = new StringBuilder(CHUNK_CHARS);
        /** 已关闭标志，布尔标志。 */
        private boolean closed;

        private TextMessageWriter(Batch batch, boolean blocking) {
            target = new StreamingMessageTarget(batch, blocking) { };
        }

        @Override public void write(char[] value, int offset, int length) throws IOException {
            if (value == null) throw new NullPointerException("value");
            if (offset < 0 || length < 0 || length > value.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            ensureOpen();
            buffer.append(value, offset, length);
            drainFullChunks();
        }

        @Override public void write(String value, int offset, int length) throws IOException {
            if (value == null) throw new NullPointerException("value");
            if (offset < 0 || length < 0 || length > value.length() - offset) {
                throw new IndexOutOfBoundsException();
            }
            ensureOpen();
            buffer.append(value, offset, offset + length);
            drainFullChunks();
        }

        @Override public void flush() throws IOException {
            ensureOpen();
            int length = safePrefix(buffer.length());
            if (length > 0) emit(length, false);
        }

        @Override public void close() throws IOException {
            if (closed) return;
            if (buffer.length() > 0 && Character.isHighSurrogate(
                    buffer.charAt(buffer.length() - 1))) {
                throw new IOException("text stream ends with an unmatched high surrogate");
            }
            emit(buffer.length(), true);
            closed = true;
        }

        private void drainFullChunks() throws IOException {
            while (buffer.length() >= CHUNK_CHARS) {
                int length = safePrefix(CHUNK_CHARS);
                if (length == 0) return;
                emit(length, false);
            }
        }

        private int safePrefix(int requested) {
            int length = Math.min(requested, buffer.length());
            if (length > 0 && Character.isHighSurrogate(buffer.charAt(length - 1))) length--;
            return length;
        }

        private void emit(int length, boolean last) throws IOException {
            String value = buffer.substring(0, length);
            buffer.delete(0, length);
            target.submit(textFragmentMessage(value, last));
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("WebSocket message writer is closed");
        }

        private CompletionStage<Void> completion() { return target.completion(); }
        private boolean started() { return target.started(); }
        private void abort() {
            try { if (!closed && started()) close(); } catch (IOException ignored) { }
        }
    }

    /** 封装批处理的状态与处理边界。 */
    private final class Batch {
        /** 按顺序保存的消息集合。 */
        private final List<OutboundMessage> messages = new ArrayList<OutboundMessage>();
        /** allowed，布尔标志。 */
        private boolean allowed;
        /** 字节数，单位为字节。 */
        private int bytes;

        synchronized CompletionStage<Void> submit(OutboundMessage message) {
            if (!allowed) return message.send();
            CompletionStage<Void> prior = CompletableFuture.completedFuture(null);
            int limit = Math.max(64 * 1024,
                    Math.max(maxTextMessageSize, maxBinaryMessageSize));
            if (!messages.isEmpty() && (long) bytes + message.bytes > limit) {
                prior = flushLocked();
            }
            if (message.bytes > limit) {
                final CompletionStage<Void> flushed = prior;
                return flushed.thenCompose(ignored -> message.send());
            }
            messages.add(message);
            bytes += message.bytes;
            CompletionStage<Void> checkpoint = nativeSession.asyncCheckpoint();
            return prior.thenCompose(ignored -> checkpoint);
        }

        synchronized CompletionStage<Void> flush() {
            return flushLocked();
        }

        void setAllowed(boolean value) throws IOException {
            CompletionStage<Void> pending = CompletableFuture.completedFuture(null);
            synchronized (this) {
                if (allowed && !value) pending = flushLocked();
                allowed = value;
            }
            await(pending);
        }

        synchronized boolean isAllowed() { return allowed; }

        private CompletionStage<Void> flushLocked() {
            if (messages.isEmpty()) return CompletableFuture.completedFuture(null);
            List<CompletableFuture<Void>> sends = new ArrayList<CompletableFuture<Void>>();
            for (OutboundMessage message : messages) {
                sends.add(message.send().toCompletableFuture());
            }
            messages.clear();
            bytes = 0;
            return CompletableFuture.allOf(sends.toArray(new CompletableFuture[sends.size()]));
        }
    }

    private static int positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("message size must be positive");
        return value;
    }

    private static Map<String, List<String>> parseParameters(String query) {
        if (query == null || query.isEmpty()) return Collections.emptyMap();
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
            result.computeIfAbsent(name, key -> new ArrayList<String>()).add(value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { throw new IllegalArgumentException("invalid request query", e); }
    }

    private static URI webSocketUri(HttpRequest request) {
        io.github.o1o00o10.lingtong.http.RequestConnectionInfo info = request.connectionInfo();
        String scheme = info.secure() ? "wss" : "ws";
        String authority = request.headers().first("host");
        if (authority == null) authority = info.serverName() + ":" + info.serverPort();
        return URI.create(scheme + "://" + authority + request.target());
    }

    private static Class<?> inferMessageType(Class<?> type) {
        if (type == null || type == Object.class) return null;
        for (Type value : type.getGenericInterfaces()) {
            if (!(value instanceof ParameterizedType)) continue;
            ParameterizedType parameterized = (ParameterizedType) value;
            Type raw = parameterized.getRawType();
            if (raw == MessageHandler.Whole.class || raw == MessageHandler.Partial.class) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof Class<?>) return (Class<?>) argument;
            }
        }
        return inferMessageType(type.getSuperclass());
    }

    private static List<Decoder> createDecoders(EndpointConfig config)
            throws InstantiationException, IllegalAccessException {
        List<Decoder> result = new ArrayList<Decoder>();
        try {
            for (Class<? extends Decoder> type : config.getDecoders()) {
                Decoder decoder = type.newInstance();
                decoder.init(config);
                result.add(decoder);
            }
            return Collections.unmodifiableList(result);
        } catch (InstantiationException | IllegalAccessException | RuntimeException e) {
            for (Decoder decoder : result) decoder.destroy();
            throw e;
        }
    }

    private static List<Encoder> createEncoders(EndpointConfig config)
            throws InstantiationException, IllegalAccessException {
        List<Encoder> result = new ArrayList<Encoder>();
        try {
            for (Class<? extends Encoder> type : config.getEncoders()) {
                Encoder encoder = type.newInstance();
                encoder.init(config);
                result.add(encoder);
            }
            return Collections.unmodifiableList(result);
        } catch (InstantiationException | IllegalAccessException | RuntimeException e) {
            for (Encoder encoder : result) encoder.destroy();
            throw e;
        }
    }

    private static String nativeMessageType(Class<?> type) {
        if (type == ByteBuffer.class || type == byte[].class) return "binary";
        if (type == PongMessage.class) return "pong";
        return "text";
    }
}
