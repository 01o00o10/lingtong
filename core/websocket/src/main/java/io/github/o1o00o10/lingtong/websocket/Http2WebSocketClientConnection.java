/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import io.github.o1o00o10.lingtong.http.UpgradeChannel;
import io.github.o1o00o10.lingtong.http2.HpackCodec;
import io.github.o1o00o10.lingtong.http2.HpackHeader;
import io.github.o1o00o10.lingtong.http2.Http2Exception;
import io.github.o1o00o10.lingtong.http2.Http2Frame;
import io.github.o1o00o10.lingtong.http2.Http2ServerConnection;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One RFC 8441 WebSocket tunnel over a dedicated HTTP/2 connection. */
/** 在专用 HTTP/2 连接上建立 RFC 8441 WebSocket 隧道并管理流控。 */
final class Http2WebSocketClientConnection implements UpgradeChannel {
    /** 流标识符。 */
    private static final int STREAM_ID = 1;
    /** 初始流控窗口。 */
    private static final int INITIAL_WINDOW = 65_535;
    /** 最大头部字节数，单位为字节。 */
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    /** 结束流。 */
    private static final int END_STREAM = 0x1;
    /** ack。 */
    /** 确认标志（ACK）。 */
    private static final int ACK = 0x1;
    /** 结束头部集合。 */
    private static final int END_HEADERS = 0x4;
    /** padded。 */
    /** 填充标志（PADDED）。 */
    private static final int PADDED = 0x8;
    /** priority。 */
    /** 优先级（PRIORITY）。 */
    private static final int PRIORITY = 0x20;
    /** settingsenable连接协议。 */
    private static final int SETTINGS_ENABLE_CONNECT_PROTOCOL = 0x8;
    /** 可并发更新的线程标识符集合。 */
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    /** 回调集合。 */
    private static final Executor CALLBACKS = new ThreadPoolExecutor(
            0, 64, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
            daemonFactory("lingtong-ws-h2-callback-"),
            new ThreadPoolExecutor.AbortPolicy());

    /** 套接字。 */
    private final Socket socket;
    /** 输入。 */
    private final InputStream input;
    /** 输出。 */
    private final OutputStream output;
    /** 协议。 */
    private final WebSocketProtocol protocol;
    /** 写入器。 */
    private final ThreadPoolExecutor writer;
    /** 定时器。 */
    private final ScheduledThreadPoolExecutor timer;
    /** 输出锁。 */
    private final Object outputLock = new Object();
    /** flow锁。 */
    private final Object flowLock = new Object();
    /** 可并发更新的打开。 */
    private final AtomicBoolean open = new AtomicBoolean(true);
    /** 连接发送流控窗口。 */
    private int connectionSendWindow;
    /** 流发送流控窗口。 */
    private int streamSendWindow;
    /** 对端初始流控窗口。 */
    private int peerInitialWindow;
    /** 对端最大帧大小。 */
    private int peerMaxFrameSize;
    /** 对端连接启用标志，布尔标志。 */
    private boolean peerConnectEnabled;

    private Http2WebSocketClientConnection(Handshake handshake, WebSocketProtocol protocol) {
        this.socket = handshake.socket;
        this.input = handshake.input;
        this.output = handshake.output;
        this.protocol = protocol;
        this.connectionSendWindow = handshake.connectionSendWindow;
        this.streamSendWindow = handshake.streamSendWindow;
        this.peerInitialWindow = handshake.peerInitialWindow;
        this.peerMaxFrameSize = handshake.peerMaxFrameSize;
        this.peerConnectEnabled = true;
        this.writer = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(256),
                daemonFactory("lingtong-ws-h2-write-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = new ScheduledThreadPoolExecutor(
                1, daemonFactory("lingtong-ws-h2-timer-"));
        this.timer.setRemoveOnCancelPolicy(true);
    }

    static Session connect(
            LingTongServerContainer container,
            Object endpoint,
            ClientEndpointConfig config,
            URI uri) throws DeploymentException, IOException {
        Handshake handshake = openHandshake(config, uri);
        JsrEndpointHandler handler = new JsrEndpointHandler(
                container, endpoint, config, uri, handshake.subprotocol,
                handshake.compressed,
                Math.min(container.getDefaultMaxTextMessageBufferSize(),
                        container.getDefaultMaxBinaryMessageBufferSize()));
        WebSocketProtocol protocol = WebSocketProtocol.client(
                handler, CALLBACKS,
                Math.min(container.getDefaultMaxTextMessageBufferSize(),
                        container.getDefaultMaxBinaryMessageBufferSize()),
                handshake.subprotocol, handshake.compressed);
        Http2WebSocketClientConnection connection =
                new Http2WebSocketClientConnection(handshake, protocol);
        try {
            protocol.onOpen(connection);
            connection.startReader();
            connection.timer.scheduleAtFixedRate(
                    () -> protocol.onTimer(System.nanoTime()),
                    100L, 100L, TimeUnit.MILLISECONDS);
            return handler.awaitOpen();
        } catch (DeploymentException | IOException e) {
            connection.terminate(true);
            throw e;
        } catch (Exception e) {
            connection.terminate(true);
            throw new DeploymentException("HTTP/2 client endpoint failed during onOpen", e);
        }
    }

    @Override public boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    @Override public void write(ByteBuffer data) throws IOException {
        try {
            writeAsync(data).toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while writing HTTP/2 WebSocket data", e);
        } catch (ExecutionException e) {
            Throwable failure = e.getCause();
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            throw new IOException("HTTP/2 WebSocket write failed", failure);
        }
    }

    @Override public CompletionStage<Void> writeAsync(ByteBuffer data) {
        CompletableFuture<Void> completion = new CompletableFuture<Void>();
        if (data == null) {
            completion.completeExceptionally(new IllegalArgumentException(
                    "HTTP/2 WebSocket output must not be null"));
            return completion;
        }
        if (!isOpen()) {
            completion.completeExceptionally(new IOException(
                    "HTTP/2 WebSocket connection is closed"));
            return completion;
        }
        ByteBuffer source = data.slice();
        byte[] copy = new byte[source.remaining()];
        source.get(copy);
        WriteTask task = new WriteTask(copy, completion);
        try {
            writer.execute(task);
        } catch (RuntimeException rejected) {
            completion.completeExceptionally(new IOException(
                    "HTTP/2 WebSocket write queue is full", rejected));
        }
        return completion;
    }

    @Override public void close() {
        if (!isOpen()) return;
        try {
            sendFrame(new Http2Frame(Http2Frame.DATA, END_STREAM, STREAM_ID, new byte[0]));
        } catch (IOException ignored) {
            // Termination below owns cleanup and close notification.
        }
        terminate(true);
    }

    private void startReader() {
        daemonFactory("lingtong-ws-h2-read-").newThread(() -> {
            try {
                while (isOpen()) handle(readFrame(input));
            } catch (IOException | RuntimeException failure) {
                // Resource termination below reports the close exactly once.
            } finally {
                terminate(true);
            }
        }).start();
    }

    private void handle(Http2Frame frame) throws IOException, Http2Exception {
        switch (frame.type()) {
            case Http2Frame.SETTINGS:
                handleSettings(frame);
                return;
            case Http2Frame.PING:
                if (frame.streamId() != 0 || frame.payload().length != 8) {
                    throw new Http2Exception(6, 0, "invalid HTTP/2 PING");
                }
                if ((frame.flags() & ACK) == 0) {
                    sendFrame(new Http2Frame(Http2Frame.PING, ACK, 0, frame.payload()));
                }
                return;
            case Http2Frame.WINDOW_UPDATE:
                handleWindowUpdate(frame);
                return;
            case Http2Frame.DATA:
                handleData(frame);
                return;
            case Http2Frame.RST_STREAM:
                if (frame.streamId() == STREAM_ID) {
                    throw new IOException("HTTP/2 WebSocket stream was reset");
                }
                return;
            case Http2Frame.GOAWAY:
                throw new IOException("HTTP/2 peer sent GOAWAY");
            case Http2Frame.HEADERS:
            case Http2Frame.CONTINUATION:
            case Http2Frame.PUSH_PROMISE:
                throw new Http2Exception(1, 0,
                        "unexpected HTTP/2 frame after WebSocket CONNECT");
            default:
        }
    }

    private void handleSettings(Http2Frame frame) throws IOException, Http2Exception {
        if (frame.streamId() != 0 || (frame.flags() & ACK) != 0
                && frame.payload().length != 0) {
            throw new Http2Exception(6, 0, "invalid HTTP/2 SETTINGS");
        }
        if ((frame.flags() & ACK) != 0) return;
        Settings update = parseSettings(frame.payload());
        synchronized (flowLock) {
            if (update.initialWindowSet) {
                long adjusted = (long) streamSendWindow
                        + update.peerInitialWindow - peerInitialWindow;
                if (adjusted > Integer.MAX_VALUE || adjusted < Integer.MIN_VALUE) {
                    throw new Http2Exception(3, 0, "HTTP/2 stream window overflow");
                }
                streamSendWindow = (int) adjusted;
                peerInitialWindow = update.peerInitialWindow;
            }
            if (update.maxFrameSizeSet) peerMaxFrameSize = update.maxFrameSize;
            if (update.connectEnabledSet) {
                if (peerConnectEnabled && !update.connectEnabled) {
                    throw new Http2Exception(1, 0,
                            "ENABLE_CONNECT_PROTOCOL must not revert to zero");
                }
                peerConnectEnabled = update.connectEnabled;
            }
            flowLock.notifyAll();
        }
        sendFrame(new Http2Frame(Http2Frame.SETTINGS, ACK, 0, new byte[0]));
    }

    private void handleWindowUpdate(Http2Frame frame) throws Http2Exception {
        if (frame.payload().length != 4) {
            throw new Http2Exception(6, 0, "invalid HTTP/2 WINDOW_UPDATE");
        }
        int increment = ByteBuffer.wrap(frame.payload()).getInt() & 0x7fffffff;
        if (increment == 0) throw new Http2Exception(1, frame.streamId(),
                "zero HTTP/2 window increment");
        synchronized (flowLock) {
            if (frame.streamId() == 0) {
                if ((long) connectionSendWindow + increment > Integer.MAX_VALUE) {
                    throw new Http2Exception(3, 0, "HTTP/2 connection window overflow");
                }
                connectionSendWindow += increment;
            } else if (frame.streamId() == STREAM_ID) {
                if ((long) streamSendWindow + increment > Integer.MAX_VALUE) {
                    throw new Http2Exception(3, STREAM_ID, "HTTP/2 stream window overflow");
                }
                streamSendWindow += increment;
            }
            flowLock.notifyAll();
        }
    }

    private void handleData(Http2Frame frame) throws IOException, Http2Exception {
        if (frame.streamId() != STREAM_ID) {
            throw new Http2Exception(5, frame.streamId(), "DATA on unknown HTTP/2 stream");
        }
        byte[] wire = frame.payload();
        byte[] payload = dataPayload(frame);
        if (payload.length > 0) protocol.onInput(ByteBuffer.wrap(payload));
        if (wire.length > 0) {
            sendFrame(new Http2Frame(Http2Frame.WINDOW_UPDATE, 0, 0,
                    intPayload(wire.length)));
            sendFrame(new Http2Frame(Http2Frame.WINDOW_UPDATE, 0, STREAM_ID,
                    intPayload(wire.length)));
        }
        if ((frame.flags() & END_STREAM) != 0) {
            throw new EOFException("HTTP/2 WebSocket stream ended");
        }
    }

    private void sendData(byte[] bytes) throws IOException, InterruptedException {
        int offset = 0;
        while (offset < bytes.length) {
            int length;
            synchronized (flowLock) {
                while (isOpen() && (connectionSendWindow <= 0 || streamSendWindow <= 0)) {
                    flowLock.wait();
                }
                if (!isOpen()) throw new IOException("HTTP/2 WebSocket connection is closed");
                length = Math.min(bytes.length - offset,
                        Math.min(peerMaxFrameSize,
                                Math.min(connectionSendWindow, streamSendWindow)));
                connectionSendWindow -= length;
                streamSendWindow -= length;
            }
            byte[] payload = new byte[length];
            System.arraycopy(bytes, offset, payload, 0, length);
            sendFrame(new Http2Frame(Http2Frame.DATA, 0, STREAM_ID, payload));
            offset += length;
        }
    }

    private void sendFrame(Http2Frame frame) throws IOException {
        ByteBuffer encoded = frame.encode();
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        synchronized (outputLock) {
            output.write(bytes);
            output.flush();
        }
    }

    private void terminate(boolean notifyProtocol) {
        if (!open.compareAndSet(true, false)) return;
        try { socket.close(); } catch (IOException ignored) { }
        timer.shutdownNow();
        synchronized (flowLock) { flowLock.notifyAll(); }
        IOException failure = new IOException("HTTP/2 WebSocket client connection is closed");
        for (Runnable task : writer.shutdownNow()) {
            if (task instanceof WriteTask) ((WriteTask) task).fail(failure);
        }
        if (notifyProtocol) protocol.onClosed();
    }

    /** 封装写入任务的状态与处理边界。 */
    private final class WriteTask implements Runnable {
        /** 字节数，单位为字节。 */
        private final byte[] bytes;
        /** 完成。 */
        private final CompletableFuture<Void> completion;

        private WriteTask(byte[] bytes, CompletableFuture<Void> completion) {
            this.bytes = bytes;
            this.completion = completion;
        }

        @Override public void run() {
            try {
                sendData(bytes);
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
                terminate(true);
            }
        }

        private void fail(Throwable failure) { completion.completeExceptionally(failure); }
    }

    private static Handshake openHandshake(ClientEndpointConfig config, URI uri)
            throws DeploymentException, IOException {
        WebSocketClientConnection.validateUri(uri);
        int timeout = WebSocketClientConnection.connectTimeout(config);
        String host = uri.getHost();
        int port = WebSocketClientConnection.effectivePort(uri);
        Socket socket = WebSocketClientConnection.openSocket(
                config, uri, host, port, timeout, true);
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeout);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            writePreface(output);

            Http2Frame first = readFrame(input);
            if (first.type() != Http2Frame.SETTINGS || first.streamId() != 0
                    || (first.flags() & ACK) != 0) {
                throw new DeploymentException(
                        "HTTP/2 server did not start with a SETTINGS frame");
            }
            Settings settings = parseSettings(first.payload());
            if (!settings.connectEnabled) {
                throw new DeploymentException(
                        "HTTP/2 server did not enable extended CONNECT");
            }
            writeFrame(output, new Http2Frame(
                    Http2Frame.SETTINGS, ACK, 0, new byte[0]));

            Map<String, List<String>> headers = requestHeaders(config, uri);
            config.getConfigurator().beforeRequest(headers);
            enforceHeaders(headers, uri, config);
            HpackCodec encoder = new HpackCodec(MAX_HEADER_BYTES);
            encoder.allowedDynamicBytes(settings.headerTableSize);
            writeHeaderBlock(output, encoder.encode(hpackHeaders(uri, headers)),
                    settings.maxFrameSize);

            HpackCodec decoder = new HpackCodec(MAX_HEADER_BYTES);
            Response response;
            do {
                response = readResponse(input, output, decoder, settings);
            } while (response.status >= 100 && response.status < 200);
            config.getConfigurator().afterResponse(response);
            Negotiated negotiated = validateResponse(response, config);
            socket.setSoTimeout(0);
            return new Handshake(socket, input, output,
                    settings.connectionWindow, settings.streamWindow,
                    settings.peerInitialWindow, settings.maxFrameSize,
                    negotiated.subprotocol, negotiated.compressed);
        } catch (IOException | RuntimeException | DeploymentException failure) {
            try { socket.close(); } catch (IOException ignored) { }
            if (failure instanceof Http2Exception) {
                throw new DeploymentException("invalid HTTP/2 WebSocket handshake", failure);
            }
            throw failure;
        }
    }

    private static void writePreface(OutputStream output) throws IOException {
        output.write(Http2ServerConnection.CLIENT_PREFACE);
        ByteBuffer settings = ByteBuffer.allocate(18);
        settings.putShort((short) 2).putInt(0);
        settings.putShort((short) 6).putInt(MAX_HEADER_BYTES);
        settings.putShort((short) SETTINGS_ENABLE_CONNECT_PROTOCOL).putInt(1);
        writeFrame(output, new Http2Frame(
                Http2Frame.SETTINGS, 0, 0, settings.array()));
    }

    private static void writeHeaderBlock(
            OutputStream output, byte[] block, int maxFrameSize) throws IOException {
        int offset = 0;
        boolean first = true;
        do {
            int length = Math.min(maxFrameSize, block.length - offset);
            byte[] fragment = new byte[length];
            System.arraycopy(block, offset, fragment, 0, length);
            offset += length;
            int type = first ? Http2Frame.HEADERS : Http2Frame.CONTINUATION;
            int flags = offset == block.length ? END_HEADERS : 0;
            writeFrame(output, new Http2Frame(type, flags, STREAM_ID, fragment));
            first = false;
        } while (offset < block.length);
    }

    private static Response readResponse(
            InputStream input, OutputStream output, HpackCodec decoder, Settings settings)
            throws IOException, DeploymentException, Http2Exception {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        boolean continuation = false;
        boolean responseEndStream = false;
        while (true) {
            Http2Frame frame = readFrame(input);
            if (continuation && (frame.type() != Http2Frame.CONTINUATION
                    || frame.streamId() != STREAM_ID)) {
                throw new Http2Exception(1, 0, "expected HTTP/2 CONTINUATION");
            }
            if (frame.type() == Http2Frame.SETTINGS) {
                if (frame.streamId() != 0) throw new Http2Exception(1, 0,
                        "SETTINGS uses a stream");
                if ((frame.flags() & ACK) == 0) {
                    Settings update = parseSettings(frame.payload());
                    settings.apply(update);
                    writeFrame(output, new Http2Frame(
                            Http2Frame.SETTINGS, ACK, 0, new byte[0]));
                } else if (frame.payload().length != 0) {
                    throw new Http2Exception(6, 0, "SETTINGS ACK has payload");
                }
                continue;
            }
            if (frame.type() == Http2Frame.PING) {
                if (frame.streamId() != 0 || frame.payload().length != 8) {
                    throw new Http2Exception(6, 0, "invalid HTTP/2 PING");
                }
                if ((frame.flags() & ACK) == 0) writeFrame(output,
                        new Http2Frame(Http2Frame.PING, ACK, 0, frame.payload()));
                continue;
            }
            if (frame.type() == Http2Frame.WINDOW_UPDATE) {
                applyHandshakeWindowUpdate(frame, settings);
                continue;
            }
            if (frame.type() == Http2Frame.RST_STREAM || frame.type() == Http2Frame.GOAWAY) {
                throw new DeploymentException("HTTP/2 server rejected WebSocket CONNECT");
            }
            if (frame.type() != Http2Frame.HEADERS && frame.type() != Http2Frame.CONTINUATION
                    || frame.streamId() != STREAM_ID) {
                throw new Http2Exception(1, 0,
                        "unexpected frame during HTTP/2 WebSocket handshake");
            }
            byte[] fragment = frame.type() == Http2Frame.HEADERS
                    ? headerFragment(frame) : frame.payload();
            if (frame.type() == Http2Frame.HEADERS) {
                responseEndStream = (frame.flags() & END_STREAM) != 0;
            }
            if ((long) block.size() + fragment.length > MAX_HEADER_BYTES) {
                throw new Http2Exception(11, STREAM_ID, "response headers are too large");
            }
            block.write(fragment, 0, fragment.length);
            continuation = (frame.flags() & END_HEADERS) == 0;
            if (!continuation) {
                if (responseEndStream) {
                    throw new DeploymentException(
                            "HTTP/2 WebSocket CONNECT stream ended in the response");
                }
                return response(decoder.decode(block.toByteArray()));
            }
        }
    }

    private static Settings parseSettings(byte[] payload)
            throws Http2Exception {
        if (payload.length % 6 != 0) throw new Http2Exception(6, 0,
                "invalid HTTP/2 SETTINGS length");
        Settings result = new Settings();
        ByteBuffer values = ByteBuffer.wrap(payload);
        while (values.hasRemaining()) {
            int id = values.getShort() & 0xffff;
            long value = values.getInt() & 0xffffffffL;
            if (id == 1) {
                result.headerTableSize = (int) Math.min(value, Integer.MAX_VALUE);
                result.headerTableSizeSet = true;
            } else if (id == 2) {
                if (value != 0) throw new Http2Exception(1, 0,
                        "server must disable HTTP/2 push");
            } else if (id == 4) {
                if (value > Integer.MAX_VALUE) throw new Http2Exception(3, 0,
                        "invalid HTTP/2 initial window");
                result.streamWindow = (int) value;
                result.peerInitialWindow = (int) value;
                result.initialWindowSet = true;
            } else if (id == 5) {
                if (value < 16_384 || value > 16_777_215) {
                    throw new Http2Exception(1, 0, "invalid HTTP/2 maximum frame size");
                }
                result.maxFrameSize = (int) value;
                result.maxFrameSizeSet = true;
            } else if (id == SETTINGS_ENABLE_CONNECT_PROTOCOL) {
                if (value > 1) throw new Http2Exception(1, 0,
                        "invalid ENABLE_CONNECT_PROTOCOL");
                result.connectEnabled = value == 1;
                result.connectEnabledSet = true;
            }
        }
        return result;
    }

    private static void applyHandshakeWindowUpdate(Http2Frame frame, Settings settings)
            throws Http2Exception {
        if (frame.payload().length != 4) throw new Http2Exception(6, 0,
                "invalid HTTP/2 WINDOW_UPDATE");
        int value = ByteBuffer.wrap(frame.payload()).getInt() & 0x7fffffff;
        if (value == 0) throw new Http2Exception(1, frame.streamId(),
                "zero HTTP/2 window increment");
        if (frame.streamId() == 0) settings.connectionWindow = addWindow(
                settings.connectionWindow, value, 0);
        else if (frame.streamId() == STREAM_ID) settings.streamWindow = addWindow(
                settings.streamWindow, value, STREAM_ID);
    }

    private static int addWindow(int current, int increment, int streamId)
            throws Http2Exception {
        long result = (long) current + increment;
        if (result > Integer.MAX_VALUE) throw new Http2Exception(3, streamId,
                "HTTP/2 window overflow");
        return (int) result;
    }

    private static Map<String, List<String>> requestHeaders(
            ClientEndpointConfig config, URI uri) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        put(headers, "Host", WebSocketClientConnection.authority(uri));
        put(headers, "Sec-WebSocket-Version", "13");
        if (!config.getPreferredSubprotocols().isEmpty()) {
            put(headers, "Sec-WebSocket-Protocol", join(config.getPreferredSubprotocols()));
        }
        if (!config.getExtensions().isEmpty()) {
            List<String> values = new ArrayList<String>();
            for (Extension extension : config.getExtensions()) values.add(extension(extension));
            put(headers, "Sec-WebSocket-Extensions", join(values));
        }
        return headers;
    }

    private static void enforceHeaders(
            Map<String, List<String>> headers, URI uri, ClientEndpointConfig config) {
        replace(headers, "Host", WebSocketClientConnection.authority(uri));
        replace(headers, "Sec-WebSocket-Version", "13");
        if (config.getPreferredSubprotocols().isEmpty()) {
            remove(headers, "Sec-WebSocket-Protocol");
        } else {
            replace(headers, "Sec-WebSocket-Protocol",
                    join(config.getPreferredSubprotocols()));
        }
        if (config.getExtensions().isEmpty()) {
            remove(headers, "Sec-WebSocket-Extensions");
        } else {
            List<String> values = new ArrayList<String>();
            for (Extension extension : config.getExtensions()) values.add(extension(extension));
            replace(headers, "Sec-WebSocket-Extensions", join(values));
        }
        remove(headers, "Connection");
        remove(headers, "Upgrade");
        remove(headers, "Sec-WebSocket-Key");
    }

    private static List<HpackHeader> hpackHeaders(
            URI uri, Map<String, List<String>> source) throws DeploymentException {
        List<HpackHeader> result = new ArrayList<HpackHeader>();
        result.add(new HpackHeader(":method", "CONNECT"));
        result.add(new HpackHeader(":protocol", "websocket"));
        result.add(new HpackHeader(":scheme",
                "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http"));
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        result.add(new HpackHeader(":path", path));
        result.add(new HpackHeader(":authority", WebSocketClientConnection.authority(uri)));
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String name = entry.getKey();
            if (!validHeaderName(name) || entry.getValue() == null) {
                throw new DeploymentException("invalid HTTP/2 WebSocket request header");
            }
            name = name.toLowerCase(Locale.ROOT);
            if ("host".equals(name)) continue;
            if (connectionSpecific(name)) throw new DeploymentException(
                    "connection-specific header is forbidden in HTTP/2: " + name);
            for (String value : entry.getValue()) {
                validHeaderValue(value);
                if ("te".equals(name) && !"trailers".equalsIgnoreCase(value.trim())) {
                    throw new DeploymentException(
                            "HTTP/2 TE request header may only contain trailers");
                }
                result.add(new HpackHeader(name, value));
            }
        }
        return result;
    }

    private static Response response(List<HpackHeader> fields)
            throws DeploymentException {
        String status = null;
        boolean regular = false;
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (HpackHeader field : fields) {
            String name = field.name();
            if (!name.equals(name.toLowerCase(Locale.ROOT))) {
                throw new DeploymentException("uppercase HTTP/2 response field name");
            }
            if (name.startsWith(":")) {
                if (regular || !":status".equals(name) || status != null) {
                    throw new DeploymentException("invalid HTTP/2 response pseudo-header");
                }
                status = field.value();
            } else {
                regular = true;
                if (!validHeaderName(name) || connectionSpecific(name)) {
                    throw new DeploymentException("invalid HTTP/2 WebSocket response header");
                }
                validHeaderValue(field.value());
                headers.computeIfAbsent(name, ignored -> new ArrayList<String>())
                        .add(field.value());
            }
        }
        if (status == null || status.length() != 3) {
            throw new DeploymentException("missing HTTP/2 response status");
        }
        try {
            return new Response(Integer.parseInt(status), headers);
        } catch (NumberFormatException e) {
            throw new DeploymentException("invalid HTTP/2 response status", e);
        }
    }

    private static Negotiated validateResponse(
            Response response, ClientEndpointConfig config) throws DeploymentException {
        if (response.status < 200 || response.status >= 300) {
            throw new DeploymentException(
                    "HTTP/2 WebSocket server returned status " + response.status);
        }
        if (response.headers.containsKey("sec-websocket-accept")) {
            throw new DeploymentException(
                    "Sec-WebSocket-Accept is forbidden in an RFC 8441 response");
        }
        List<String> protocols = commaTokens(
                response.headers.get("sec-websocket-protocol"));
        if (protocols.size() > 1) throw new DeploymentException(
                "server selected multiple WebSocket subprotocols");
        String protocol = protocols.isEmpty() ? "" : protocols.get(0);
        if (!protocol.isEmpty() && !config.getPreferredSubprotocols().contains(protocol)) {
            throw new DeploymentException("server selected an unrequested WebSocket subprotocol");
        }
        List<String> extensions = commaTokens(
                response.headers.get("sec-websocket-extensions"));
        if (extensions.size() > 1) throw new DeploymentException(
                "server selected multiple WebSocket extensions");
        boolean compressed = false;
        if (!extensions.isEmpty()) {
            validateCompression(extensions.get(0), config);
            compressed = true;
        }
        return new Negotiated(protocol, compressed);
    }

    private static void validateCompression(String value, ClientEndpointConfig config)
            throws DeploymentException {
        String[] parts = value.split(";", -1);
        boolean requested = false;
        for (Extension extension : config.getExtensions()) {
            if ("permessage-deflate".equalsIgnoreCase(extension.getName())) requested = true;
        }
        if (!requested || !"permessage-deflate".equalsIgnoreCase(parts[0].trim())) {
            throw new DeploymentException("server selected an unsupported WebSocket extension");
        }
        boolean server = false;
        boolean client = false;
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim().toLowerCase(Locale.ROOT);
            if ("server_no_context_takeover".equals(parameter) && !server) server = true;
            else if ("client_no_context_takeover".equals(parameter) && !client) client = true;
            else throw new DeploymentException(
                    "unsupported permessage-deflate response parameter");
        }
        if (!server || !client) throw new DeploymentException(
                "permessage-deflate requires both no-context-takeover parameters");
    }

    private static byte[] headerFragment(Http2Frame frame) throws Http2Exception {
        byte[] payload = frame.payload();
        int offset = 0;
        int padding = 0;
        if ((frame.flags() & PADDED) != 0) {
            if (payload.length == 0) throw new Http2Exception(1, STREAM_ID,
                    "missing HEADERS padding length");
            padding = payload[offset++] & 0xff;
        }
        if ((frame.flags() & PRIORITY) != 0) offset += 5;
        if (offset + padding > payload.length) throw new Http2Exception(1, STREAM_ID,
                "invalid HEADERS padding");
        byte[] result = new byte[payload.length - offset - padding];
        System.arraycopy(payload, offset, result, 0, result.length);
        return result;
    }

    private static byte[] dataPayload(Http2Frame frame) throws Http2Exception {
        byte[] payload = frame.payload();
        if ((frame.flags() & PADDED) == 0) return payload;
        if (payload.length == 0) throw new Http2Exception(1, STREAM_ID,
                "missing DATA padding length");
        int padding = payload[0] & 0xff;
        if (padding >= payload.length) throw new Http2Exception(1, STREAM_ID,
                "invalid DATA padding");
        byte[] result = new byte[payload.length - padding - 1];
        System.arraycopy(payload, 1, result, 0, result.length);
        return result;
    }

    private static Http2Frame readFrame(InputStream input) throws IOException, Http2Exception {
        byte[] header = readFully(input, 9);
        int length = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
        if (length > 16_384) throw new Http2Exception(6, 0,
                "peer exceeded the advertised HTTP/2 frame size");
        if ((header[5] & 0x80) != 0) throw new Http2Exception(1, 0,
                "reserved HTTP/2 stream bit is set");
        int streamId = ((header[5] & 0x7f) << 24) | ((header[6] & 0xff) << 16)
                | ((header[7] & 0xff) << 8) | (header[8] & 0xff);
        return new Http2Frame(header[3] & 0xff, header[4] & 0xff,
                streamId, readFully(input, length));
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("connection closed inside HTTP/2 frame");
            offset += count;
        }
        return bytes;
    }

    private static void writeFrame(OutputStream output, Http2Frame frame) throws IOException {
        ByteBuffer encoded = frame.encode();
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        output.write(bytes);
        output.flush();
    }

    private static byte[] intPayload(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static boolean connectionSpecific(String name) {
        return "connection".equals(name) || "keep-alive".equals(name)
                || "proxy-connection".equals(name) || "transfer-encoding".equals(name)
                || "upgrade".equals(name);
    }

    private static boolean validHeaderName(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!(character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0)) return false;
        }
        return true;
    }

    private static void validHeaderValue(String value) throws DeploymentException {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new DeploymentException("invalid HTTP/2 WebSocket header value");
        }
    }

    private static List<String> commaTokens(List<String> values) throws DeploymentException {
        if (values == null) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            for (String token : value.split(",", -1)) {
                if (token.trim().isEmpty()) throw new DeploymentException(
                        "empty WebSocket handshake token");
                result.add(token.trim());
            }
        }
        return result;
    }

    private static String extension(Extension extension) {
        StringBuilder result = new StringBuilder(extension.getName());
        for (Extension.Parameter parameter : extension.getParameters()) {
            result.append("; ").append(parameter.getName());
            if (parameter.getValue() != null) result.append('=').append(parameter.getValue());
        }
        return result.toString();
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }

    private static void put(
            Map<String, List<String>> headers, String name, String value) {
        headers.put(name, new ArrayList<String>(Collections.singletonList(value)));
    }

    private static void replace(
            Map<String, List<String>> headers, String name, String value) {
        remove(headers, name);
        put(headers, name, value);
    }

    private static void remove(Map<String, List<String>> headers, String name) {
        List<String> found = new ArrayList<String>();
        for (String existing : headers.keySet()) {
            if (name.equalsIgnoreCase(existing)) found.add(existing);
        }
        for (String existing : found) headers.remove(existing);
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 封装settings的状态与处理边界。 */
    private static final class Settings {
        /** 头部table大小。 */
        private int headerTableSize = 4096;
        /** 最大帧大小。 */
        private int maxFrameSize = 16_384;
        /** 连接流控窗口。 */
        private int connectionWindow = INITIAL_WINDOW;
        /** 流流控窗口。 */
        private int streamWindow = INITIAL_WINDOW;
        /** 对端初始流控窗口。 */
        private int peerInitialWindow = INITIAL_WINDOW;
        /** 连接启用标志，布尔标志。 */
        private boolean connectEnabled;
        /** 头部table大小集合，布尔标志。 */
        private boolean headerTableSizeSet;
        /** 最大帧大小集合，布尔标志。 */
        private boolean maxFrameSizeSet;
        /** 初始流控窗口集合，布尔标志。 */
        private boolean initialWindowSet;
        /** 连接启用标志集合，布尔标志。 */
        private boolean connectEnabledSet;

        private void apply(Settings update) throws Http2Exception {
            if (update.headerTableSizeSet) headerTableSize = update.headerTableSize;
            if (update.maxFrameSizeSet) maxFrameSize = update.maxFrameSize;
            if (update.initialWindowSet) {
                long adjusted = (long) streamWindow
                        + update.peerInitialWindow - peerInitialWindow;
                if (adjusted > Integer.MAX_VALUE || adjusted < Integer.MIN_VALUE) {
                    throw new Http2Exception(3, 0, "HTTP/2 stream window overflow");
                }
                streamWindow = (int) adjusted;
                peerInitialWindow = update.peerInitialWindow;
            }
            if (connectEnabled && update.connectEnabledSet && !update.connectEnabled) {
                throw new Http2Exception(1, 0,
                        "ENABLE_CONNECT_PROTOCOL must not revert to zero");
            }
            if (update.connectEnabledSet) connectEnabled = update.connectEnabled;
        }
    }

    /** 封装响应的状态与处理边界。 */
    private static final class Response implements HandshakeResponse {
        /** 状态码。 */
        private final int status;
        /** 按键索引的头部集合。 */
        private final Map<String, List<String>> headers;

        private Response(int status, Map<String, List<String>> source) {
            this.status = status;
            Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<String>(entry.getValue())));
            }
            this.headers = Collections.unmodifiableMap(copy);
        }

        @Override public Map<String, List<String>> getHeaders() { return headers; }
    }

    /** 封装协商后的的状态与处理边界。 */
    private static class Negotiated {
        /** 子协议。 */
        final String subprotocol;
        /** 已压缩，布尔标志。 */
        final boolean compressed;

        private Negotiated(String subprotocol, boolean compressed) {
            this.subprotocol = subprotocol;
            this.compressed = compressed;
        }
    }

    /** 封装握手的状态与处理边界。 */
    private static final class Handshake extends Negotiated {
        /** 套接字。 */
        private final Socket socket;
        /** 输入。 */
        private final InputStream input;
        /** 输出。 */
        private final OutputStream output;
        /** 连接发送流控窗口。 */
        private final int connectionSendWindow;
        /** 流发送流控窗口。 */
        private final int streamSendWindow;
        /** 对端初始流控窗口。 */
        private final int peerInitialWindow;
        /** 对端最大帧大小。 */
        private final int peerMaxFrameSize;

        private Handshake(
                Socket socket, InputStream input, OutputStream output,
                int connectionSendWindow, int streamSendWindow, int peerInitialWindow,
                int peerMaxFrameSize,
                String subprotocol, boolean compressed) {
            super(subprotocol, compressed);
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.connectionSendWindow = connectionSendWindow;
            this.streamSendWindow = streamSendWindow;
            this.peerInitialWindow = peerInitialWindow;
            this.peerMaxFrameSize = peerMaxFrameSize;
        }
    }
}
