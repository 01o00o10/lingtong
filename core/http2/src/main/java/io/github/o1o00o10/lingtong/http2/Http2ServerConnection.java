/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http2;

import io.github.o1o00o10.lingtong.http.HttpHeaders;
import io.github.o1o00o10.lingtong.http.BodyMailbox;
import io.github.o1o00o10.lingtong.http.ConnectionUpgrade;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.HttpResponse;
import io.github.o1o00o10.lingtong.http.HttpTrailerFields;
import io.github.o1o00o10.lingtong.http.ResponseBodyMailbox;
import io.github.o1o00o10.lingtong.http.StreamingHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 单连接 HTTP/2 状态：帧、HPACK、流表及连接/流两层流控。 */
public final class Http2ServerConnection {
    /** 客户端连接序言的固定字节序列。 */
    public static final byte[] CLIENT_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** 本帧关闭当前发送方向的标志。 */
    private static final int END_STREAM = 0x1;
    /** SETTINGS/PING 的确认标志。 */
    private static final int ACK = 0x1;
    /** 头部块在本帧结束的标志。 */
    private static final int END_HEADERS = 0x4;
    /** 帧负载包含填充字节的标志。 */
    private static final int PADDED = 0x8;
    /** HEADERS 携带优先级信息的标志。 */
    private static final int PRIORITY = 0x20;
    /** HTTP/2 默认初始流控窗口，单位字节。 */
    private static final int INITIAL_WINDOW = 65_535;
    /** 允许扩展 CONNECT 的 SETTINGS 参数标识。 */
    private static final int SETTINGS_ENABLE_CONNECT_PROTOCOL = 0x8;

    /** 从网络块逐帧解码的状态。 */
    private final Http2FrameDecoder frames = new Http2FrameDecoder();
    /** 客户端请求头解压使用的 HPACK 状态。 */
    private final HpackCodec decoder;
    /** 服务端响应头压缩使用的 HPACK 状态。 */
    private final HpackCodec encoder;
    /** 普通请求体聚合上限，单位字节。 */
    private final int maxBodyBytes;
    /** 压缩头部解码后的容量预算。 */
    private final int maxHeaderBytes;
    /** 同时活跃的客户端流数量上限。 */
    private final int maxConcurrentStreams;
    /** 当前仍需处理的流，按流 ID 索引。 */
    private final Map<Integer, Stream> streams = new HashMap<Integer, Stream>();
    /** 已接收的最大客户端流 ID，用于校验新流及 GOAWAY。 */
    private int highestClientStream;
    /** 等待 CONTINUATION 的流 ID；0 表示当前没有未完头部块。 */
    private int continuationStream;
    /** 用于要求首帧为连接级 SETTINGS。 */
    private boolean firstFrame = true;
    /** 对端允许我们发送的单帧负载上限。 */
    private int peerMaxFrameSize = 16_384;
    /** 对端配置的新流初始发送窗口。 */
    private int peerInitialWindow = INITIAL_WINDOW;
    /** 连接级可发送的 DATA 字节额度。 */
    private int connectionSendWindow = INITIAL_WINDOW;
    /** 连接级仍可接收的 DATA 字节额度。 */
    private int connectionReceiveWindow = INITIAL_WINDOW;
    /** 协议错误后连接是否已关闭。 */
    private boolean closed;
    /** 已发 GOAWAY，拒绝后续新流但允许既有流排空。 */
    private boolean draining;

    public Http2ServerConnection(int maxHeaderBytes, int maxBodyBytes, int maxConcurrentStreams) {
        if (maxBodyBytes < 0 || maxConcurrentStreams <= 0) {
            throw new IllegalArgumentException("invalid HTTP/2 limits");
        }
        this.decoder = new HpackCodec(maxHeaderBytes);
        this.encoder = new HpackCodec(maxHeaderBytes);
        this.maxBodyBytes = maxBodyBytes;
        this.maxHeaderBytes = maxHeaderBytes;
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public ByteBuffer initialSettings() {
        ByteBuffer payload = ByteBuffer.allocate(24);
        payload.putShort((short) 2).putInt(0);
        payload.putShort((short) 3).putInt(maxConcurrentStreams);
        payload.putShort((short) 6).putInt(64 * 1024);
        payload.putShort((short) SETTINGS_ENABLE_CONNECT_PROTOCOL).putInt(1);
        return frame(Http2Frame.SETTINGS, 0, 0, payload.array());
    }

    /** 应用 h2c Upgrade 请求中的 HTTP2-Settings 参数。 */
    public void applyUpgradeSettings(byte[] payload) throws Http2Exception {
        if (payload == null) throw new IllegalArgumentException("upgrade settings must not be null");
        settings(new Http2Frame(Http2Frame.SETTINGS, 0, 0, payload), ignored -> { });
        firstFrame = false;
    }

    /** 将 HTTP/1.1 Upgrade 请求安装为 HTTP/2 的 1 号流。 */
    public void dispatchUpgradeRequest(HttpRequest request, Listener listener) throws Http2Exception {
        if (request == null || listener == null || highestClientStream != 0) {
            throw new IllegalArgumentException("invalid h2c upgrade request");
        }
        HttpHeaders.Builder headers = HttpHeaders.builder();
        for (Map.Entry<String, List<String>> entry : request.headers().entries()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (connectionSpecific(name) || "http2-settings".equals(name)) continue;
            for (String value : entry.getValue()) headers.add(name, value);
        }
        Stream stream = new Stream(1, peerInitialWindow);
        stream.headersComplete = true;
        stream.remoteEnd = true;
        stream.dispatched = true;
        stream.requestMethod = request.method();
        streams.put(1, stream);
        highestClientStream = 1;
        listener.onRequest(1, new HttpRequest(
                request.method(), request.target(), "HTTP/2", headers.build(), request.body(),
                request.trailers()));
    }

    /** 消费网络输入、更新流状态，并通过回调产出应发送的帧。 */
    public void receive(ByteBuffer input, Listener listener, Consumer<ByteBuffer> output)
            throws Http2Exception {
        if (closed) return;
        for (Http2Frame frame : frames.decode(input)) handle(frame, listener, output);
    }

    /** 为一条流写入已聚合响应，受连接及流窗口共同约束。 */
    public void respond(int streamId, HttpResponse response, Consumer<ByteBuffer> output)
            throws Http2Exception {
        Stream stream = streams.get(streamId);
        if (stream == null || stream.reset || stream.responseStarted) return;
        stream.responseStarted = true;
        List<HpackHeader> headers = new ArrayList<HpackHeader>();
        headers.add(new HpackHeader(":status", Integer.toString(response.status())));
        boolean hasLength = false;
        for (Map.Entry<String, List<String>> entry : response.headers().entries()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (connectionSpecific(name)) continue;
            if ("content-length".equals(name)) hasLength = true;
            for (String value : entry.getValue()) headers.add(new HpackHeader(name, value));
        }
        byte[] responseBody = response.body();
        boolean suppressBody = "HEAD".equals(stream.requestMethod)
                || response.status() == 204 || response.status() == 205 || response.status() == 304
                || (response.status() >= 100 && response.status() < 200);
        byte[] body = suppressBody ? new byte[0] : responseBody;
        stream.responseTrailers = suppressBody || !hasTrailers(response.trailers())
                ? null : response.trailers();
        if (!hasLength) headers.add(new HpackHeader(
                "content-length", Integer.toString(suppressBody ? responseBody.length : body.length)));
        byte[] block = encoder.encode(headers);
        writeHeaderBlock(streamId, block,
                body.length == 0 && stream.responseTrailers == null, output);
        if (body.length == 0) {
            if (stream.responseTrailers != null) {
                endResponseStream(stream, output);
                return;
            }
            stream.localEnd = true;
            removeIfClosed(stream);
        } else {
            stream.responseBody = body;
            flushData(stream, output);
        }
    }

    /** 将正文邮箱关联到流，窗口/可读信号到来时继续发送 DATA。 */
    public void respondStreaming(
            int streamId,
            StreamingHttpResponse response,
            Runnable readableSignal,
            Consumer<ByteBuffer> output) throws Http2Exception {
        Stream stream = streams.get(streamId);
        if (stream == null || stream.reset || stream.responseStarted) return;
        stream.responseStarted = true;
        boolean suppressBody = "HEAD".equals(stream.requestMethod)
                || response.status() == 204 || response.status() == 205 || response.status() == 304
                || (response.status() >= 100 && response.status() < 200);
        writeResponseHeaders(streamId, response.status(), response.headers(), suppressBody, output);
        if (suppressBody) {
            stream.localEnd = true;
            stream.responseMailbox = response.body();
            stream.responseReadableSignal = readableSignal;
            stream.responseDrainPending = true;
            response.body().onReadable(readableSignal);
            flushData(stream, output);
            return;
        }
        stream.responseMailbox = response.body();
        stream.responseReadableSignal = readableSignal;
        response.body().onReadable(readableSignal);
        flushData(stream, output);
    }

    /** 建立扩展 CONNECT 隧道，并返回接受前暂存的请求字节。 */
    public byte[] acceptTunnel(int streamId, HttpResponse response, Consumer<ByteBuffer> output)
            throws Http2Exception {
        Stream stream = streams.get(streamId);
        if (stream == null || !stream.extendedConnect || stream.responseStarted
                || response.upgrade() == null || response.status() < 200 || response.status() >= 300) {
            throw stream(1, streamId, "invalid extended CONNECT response");
        }
        stream.responseStarted = true;
        stream.tunnel = true;
        writeResponseHeaders(streamId, response.status(), response.headers(), false, output);
        byte[] pending = stream.body.toByteArray();
        stream.body.reset();
        return pending;
    }

    public void sendTunnelData(int streamId, ByteBuffer data, Consumer<ByteBuffer> output)
            throws IOException {
        enqueueTunnelData(streamId, data, null, output);
    }

    public CompletionStage<Void> sendTunnelDataAsync(
            int streamId, ByteBuffer data, Consumer<ByteBuffer> output) {
        CompletableFuture<Void> completion = new CompletableFuture<Void>();
        try {
            enqueueTunnelData(streamId, data, completion, output);
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    private void enqueueTunnelData(
            int streamId,
            ByteBuffer data,
            CompletableFuture<Void> completion,
            Consumer<ByteBuffer> output) throws IOException {
        Stream stream = streams.get(streamId);
        if (stream == null || !stream.tunnel || stream.localEnd || stream.reset) {
            throw new IOException("HTTP/2 tunnel is closed");
        }
        ByteBuffer source = data == null ? null : data.slice();
        if (source == null) throw new IllegalArgumentException("tunnel data must not be null");
        int tunnelQueueLimit = Math.max(64 * 1024, maxBodyBytes);
        if ((long) stream.queuedTunnelBytes + source.remaining() > tunnelQueueLimit) {
            throw new IOException("HTTP/2 tunnel output exceeds configured queue limit");
        }
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        if (bytes.length > 0) {
            stream.tunnelOutput.offer(new TunnelWrite(bytes, completion));
            stream.queuedTunnelBytes += bytes.length;
        } else if (completion != null) {
            completion.complete(null);
        }
        flushData(stream, output);
    }

    public void closeTunnel(int streamId, Consumer<ByteBuffer> output) {
        Stream stream = streams.get(streamId);
        if (stream == null || !stream.tunnel || stream.localEnd) return;
        stream.tunnelClosePending = true;
        flushData(stream, output);
    }

    public void resumeStreaming(int streamId, Consumer<ByteBuffer> output) {
        Stream stream = streams.get(streamId);
        if (stream != null) flushData(stream, output);
    }

    /** 返回 RST_STREAM 或 GOAWAY；作用域由异常的 streamId 决定。 */
    public ByteBuffer errorFrame(Http2Exception failure) {
        if (failure.streamId() != 0) {
            Stream stream = streams.remove(failure.streamId());
            if (stream != null) {
                stream.reset = true;
                failTunnelWrites(stream, failure);
                detachMailbox(stream);
            }
            return frame(Http2Frame.RST_STREAM, 0, failure.streamId(), intPayload(failure.errorCode()));
        }
        closed = true;
        failAllTunnelWrites(failure);
        ByteBuffer payload = ByteBuffer.allocate(8);
        payload.putInt(highestClientStream).putInt(failure.errorCode());
        return frame(Http2Frame.GOAWAY, 0, 0, payload.array());
    }

    /** 发送正常排空通知，并禁止新的客户端流。 */
    public ByteBuffer gracefulGoAway() {
        draining = true;
        ByteBuffer payload = ByteBuffer.allocate(8);
        payload.putInt(highestClientStream).putInt(0);
        return frame(Http2Frame.GOAWAY, 0, 0, payload.array());
    }

    public boolean isClosed() { return closed; }

    public boolean hasActiveLocalStreams() {
        for (Stream stream : streams.values()) {
            if (!stream.localEnd || stream.responseDrainPending) return true;
        }
        return false;
    }

    private void handle(Http2Frame frame, Listener listener, Consumer<ByteBuffer> output)
            throws Http2Exception {
        if (continuationStream != 0
                && (frame.type() != Http2Frame.CONTINUATION || frame.streamId() != continuationStream)) {
            throw connection(1, "expected CONTINUATION frame");
        }
        if (firstFrame) {
            firstFrame = false;
            if (frame.type() != Http2Frame.SETTINGS || frame.streamId() != 0) {
                throw connection(1, "client preface must start with SETTINGS");
            }
        }
        switch (frame.type()) {
            case Http2Frame.SETTINGS: settings(frame, output); break;
            case Http2Frame.PING: ping(frame, output); break;
            case Http2Frame.HEADERS: headers(frame, listener, output); break;
            case Http2Frame.CONTINUATION: continuation(frame, listener); break;
            case Http2Frame.DATA: data(frame, listener, output); break;
            case Http2Frame.WINDOW_UPDATE: windowUpdate(frame, output); break;
            case Http2Frame.RST_STREAM: reset(frame, listener); break;
            case Http2Frame.GOAWAY:
                closed = true;
                failAllTunnelWrites(new IOException("peer closed the HTTP/2 connection"));
                break;
            case Http2Frame.PRIORITY: priority(frame); break;
            case Http2Frame.PUSH_PROMISE: throw connection(1, "client sent PUSH_PROMISE");
            default: break;
        }
    }

    private void settings(Http2Frame frame, Consumer<ByteBuffer> output) throws Http2Exception {
        byte[] payload = frame.payload();
        if (frame.streamId() != 0) throw connection(1, "SETTINGS uses a stream");
        if ((frame.flags() & ACK) != 0) {
            if (payload.length != 0) throw connection(6, "SETTINGS ACK has payload");
            return;
        }
        if (payload.length % 6 != 0) throw connection(6, "invalid SETTINGS length");
        ByteBuffer values = ByteBuffer.wrap(payload);
        while (values.hasRemaining()) {
            int id = values.getShort() & 0xffff;
            long value = values.getInt() & 0xffffffffL;
            if (id == 1) {
                encoder.allowedDynamicBytes((int) Math.min(value, Integer.MAX_VALUE));
            } else if (id == 2 && value > 1) {
                throw connection(1, "invalid ENABLE_PUSH");
            } else if (id == 4) {
                if (value > 0x7fffffffL) throw connection(3, "invalid initial window");
                int delta = (int) value - peerInitialWindow;
                peerInitialWindow = (int) value;
                for (Stream stream : streams.values()) {
                    long updated = (long) stream.sendWindow + delta;
                    if (updated > Integer.MAX_VALUE || updated < Integer.MIN_VALUE) {
                        throw connection(3, "stream window overflow");
                    }
                    stream.sendWindow = (int) updated;
                }
            } else if (id == 5) {
                if (value < 16_384 || value > 16_777_215) throw connection(1, "invalid maximum frame size");
                peerMaxFrameSize = (int) value;
            } else if (id == SETTINGS_ENABLE_CONNECT_PROTOCOL) {
                if (value > 1) throw connection(1, "invalid ENABLE_CONNECT_PROTOCOL");
            }
        }
        output.accept(frame(Http2Frame.SETTINGS, ACK, 0, new byte[0]));
        for (Stream stream : new ArrayList<Stream>(streams.values())) flushData(stream, output);
    }

    private void ping(Http2Frame frame, Consumer<ByteBuffer> output) throws Http2Exception {
        if (frame.streamId() != 0 || frame.payload().length != 8) throw connection(6, "invalid PING");
        if ((frame.flags() & ACK) == 0) output.accept(frame(Http2Frame.PING, ACK, 0, frame.payload()));
    }

    private void headers(Http2Frame frame, Listener listener, Consumer<ByteBuffer> output)
            throws Http2Exception {
        int streamId = frame.streamId();
        if (streamId == 0) throw connection(1, "HEADERS without stream");
        Stream stream = streams.get(streamId);
        if (stream == null) {
            if ((streamId & 1) == 0 || streamId <= highestClientStream) {
                throw connection(1, "invalid client stream identifier");
            }
            if (activeStreams() >= maxConcurrentStreams) {
                output.accept(frame(Http2Frame.RST_STREAM, 0, streamId, intPayload(7)));
                highestClientStream = streamId;
                return;
            }
            if (draining) {
                output.accept(frame(Http2Frame.RST_STREAM, 0, streamId, intPayload(7)));
                return;
            }
            highestClientStream = streamId;
            stream = new Stream(streamId, peerInitialWindow);
            streams.put(streamId, stream);
        } else if (stream.headersComplete) {
            if (stream.remoteEnd || stream.dispatched || (frame.flags() & END_STREAM) == 0) {
                throw stream(1, streamId, "invalid request trailers");
            }
            stream.trailersBlock = true;
        }
        byte[] fragment = headerFragment(frame);
        if ((long) stream.headerBlock.size() + fragment.length > maxHeaderBytes) {
            throw stream(11, streamId, "header block exceeds configured limit");
        }
        stream.headerBlock.write(fragment, 0, fragment.length);
        if ((frame.flags() & END_STREAM) != 0) stream.remoteEnd = true;
        if ((frame.flags() & END_HEADERS) != 0) {
            finishHeaders(stream, listener);
        } else {
            continuationStream = streamId;
        }
    }

    private void continuation(Http2Frame frame, Listener listener) throws Http2Exception {
        if (continuationStream == 0 || frame.streamId() != continuationStream) {
            throw connection(1, "unexpected CONTINUATION");
        }
        Stream stream = streams.get(frame.streamId());
        byte[] payload = frame.payload();
        if ((long) stream.headerBlock.size() + payload.length > maxHeaderBytes) {
            throw stream(11, stream.id, "header block exceeds configured limit");
        }
        stream.headerBlock.write(payload, 0, payload.length);
        if ((frame.flags() & END_HEADERS) != 0) {
            continuationStream = 0;
            finishHeaders(stream, listener);
        }
    }

    private void finishHeaders(Stream stream, Listener listener) throws Http2Exception {
        List<HpackHeader> decoded = decoder.decode(stream.headerBlock.toByteArray());
        stream.headerBlock.reset();
        if (stream.trailersBlock) {
            stream.trailers = requestTrailers(stream.id, decoded);
            stream.trailersBlock = false;
            dispatch(stream, listener);
            return;
        }
        stream.headers = decoded;
        stream.headersComplete = true;
        if (stream.remoteEnd || isExtendedConnect(stream.headers)) dispatch(stream, listener);
    }

    private void data(Http2Frame frame, Listener listener, Consumer<ByteBuffer> output)
            throws Http2Exception {
        if (frame.streamId() == 0) throw connection(1, "DATA without stream");
        Stream stream = streams.get(frame.streamId());
        if (stream == null || !stream.headersComplete || stream.remoteEnd) {
            throw stream(5, frame.streamId(), "DATA on closed or idle stream");
        }
        byte[] payload = dataPayload(frame);
        int flowBytes = frame.payload().length;
        connectionReceiveWindow -= flowBytes;
        stream.receiveWindow -= flowBytes;
        if (connectionReceiveWindow < 0) throw connection(3, "connection receive window exceeded");
        if (stream.receiveWindow < 0) throw stream(3, stream.id, "stream receive window exceeded");
        if (stream.tunnel) {
            if (payload.length > 0) listener.onTunnelData(stream.id, ByteBuffer.wrap(payload));
        } else {
            if ((long) stream.body.size() + payload.length > maxBodyBytes) {
                throw stream(11, stream.id, "request body exceeds configured limit");
            }
            stream.body.write(payload, 0, payload.length);
        }
        if (flowBytes > 0) {
            connectionReceiveWindow += flowBytes;
            stream.receiveWindow += flowBytes;
            output.accept(frame(Http2Frame.WINDOW_UPDATE, 0, 0, intPayload(flowBytes)));
            output.accept(frame(Http2Frame.WINDOW_UPDATE, 0, stream.id, intPayload(flowBytes)));
        }
        if ((frame.flags() & END_STREAM) != 0) {
            stream.remoteEnd = true;
            if (stream.tunnel) listener.onTunnelClosed(stream.id);
            else dispatch(stream, listener);
            removeIfClosed(stream);
        }
    }

    /** 校验伪首部后只向应用回调交付一次普通请求。 */
    private void dispatch(Stream stream, Listener listener) throws Http2Exception {
        if (stream.dispatched) return;
        String method = null;
        String path = null;
        String authority = null;
        String scheme = null;
        String protocol = null;
        boolean regularSeen = false;
        HttpHeaders.Builder headers = HttpHeaders.builder();
        for (HpackHeader field : stream.headers) {
            String name = field.name();
            if (!name.equals(name.toLowerCase(Locale.ROOT))) throw stream(1, stream.id, "uppercase field name");
            if (name.startsWith(":")) {
                if (regularSeen) throw stream(1, stream.id, "pseudo-header after regular field");
                if (":method".equals(name)) method = unique(method, field.value(), stream.id, name);
                else if (":path".equals(name)) path = unique(path, field.value(), stream.id, name);
                else if (":authority".equals(name)) authority = unique(authority, field.value(), stream.id, name);
                else if (":scheme".equals(name)) scheme = unique(scheme, field.value(), stream.id, name);
                else if (":protocol".equals(name)) protocol = unique(protocol, field.value(), stream.id, name);
                else throw stream(1, stream.id, "invalid request pseudo-header");
            } else {
                regularSeen = true;
                if (connectionSpecific(name) || ("te".equals(name) && !"trailers".equalsIgnoreCase(field.value()))) {
                    throw stream(1, stream.id, "connection-specific field in HTTP/2");
                }
                headers.add(name, field.value());
            }
        }
        boolean extendedConnect = "CONNECT".equals(method) && protocol != null;
        if (method == null || path == null || path.isEmpty() || scheme == null
                || (protocol != null && !extendedConnect)) {
            throw stream(1, stream.id, "missing request pseudo-header");
        }
        if (authority != null) headers.add("host", authority);
        stream.dispatched = true;
        stream.requestMethod = method;
        stream.extendedConnect = extendedConnect;
        if (extendedConnect) {
            listener.onRequest(stream.id, HttpRequest.extendedConnect(
                    protocol, path, headers.build(), BodyMailbox.completed(new byte[0],
                            HttpHeaders.builder().build())));
        } else {
            listener.onRequest(stream.id, new HttpRequest(
                    method, path, "HTTP/2", headers.build(), stream.body.toByteArray(),
                    stream.trailers == null ? HttpHeaders.builder().build() : stream.trailers));
        }
    }

    private static HttpHeaders requestTrailers(int streamId, List<HpackHeader> fields)
            throws Http2Exception {
        HttpHeaders.Builder trailers = HttpHeaders.builder();
        for (HpackHeader field : fields) {
            String name = field.name();
            if (!name.equals(name.toLowerCase(Locale.ROOT))) {
                throw stream(1, streamId, "uppercase trailer field name");
            }
            if (name.startsWith(":") || connectionSpecific(name)
                    || "content-length".equals(name) || "host".equals(name)
                    || "te".equals(name) || "trailer".equals(name)) {
                throw stream(1, streamId, "forbidden HTTP/2 request trailer field");
            }
            trailers.add(name, field.value());
        }
        return trailers.build();
    }

    private void windowUpdate(Http2Frame frame, Consumer<ByteBuffer> output) throws Http2Exception {
        if (frame.payload().length != 4) throw connection(6, "invalid WINDOW_UPDATE length");
        int increment = ByteBuffer.wrap(frame.payload()).getInt() & 0x7fffffff;
        if (increment == 0) {
            if (frame.streamId() == 0) throw connection(1, "zero connection window increment");
            throw stream(1, frame.streamId(), "zero stream window increment");
        }
        if (frame.streamId() == 0) {
            if ((long) connectionSendWindow + increment > Integer.MAX_VALUE) throw connection(3, "connection window overflow");
            connectionSendWindow += increment;
            for (Stream stream : new ArrayList<Stream>(streams.values())) flushData(stream, output);
        } else {
            Stream stream = streams.get(frame.streamId());
            if (stream == null) return;
            if ((long) stream.sendWindow + increment > Integer.MAX_VALUE) throw stream(3, stream.id, "stream window overflow");
            stream.sendWindow += increment;
            flushData(stream, output);
        }
    }

    private void reset(Http2Frame frame, Listener listener) throws Http2Exception {
        if (frame.streamId() == 0 || frame.payload().length != 4) throw connection(6, "invalid RST_STREAM");
        Stream stream = streams.remove(frame.streamId());
        if (stream != null) {
            stream.reset = true;
            failTunnelWrites(stream, new IOException("HTTP/2 stream was reset"));
            detachMailbox(stream);
            if (stream.tunnel) listener.onTunnelClosed(stream.id);
        }
    }

    private void priority(Http2Frame frame) throws Http2Exception {
        if (frame.streamId() == 0 || frame.payload().length != 5) throw connection(6, "invalid PRIORITY");
    }

    /** 只有连接与当前流的发送窗口均有额度时才排出 DATA。 */
    private void flushData(Stream stream, Consumer<ByteBuffer> output) {
        if (stream.responseDrainPending) {
            try {
                while (stream.responseMailbox.pollChunk() != null) {
                    // HEAD 和禁止正文的响应仍要排空生产者邮箱，避免写端悬挂。
                }
            } catch (IOException ignored) {
                // 线上响应已结束；只需解绑失败的生产者。
            }
            if (stream.responseMailbox.isComplete() || stream.responseMailbox.failure() != null) {
                stream.responseDrainPending = false;
                detachMailbox(stream);
                removeIfClosed(stream);
            }
            return;
        }
        if (stream.localEnd || stream.reset) return;
        if (stream.responseMailbox != null && stream.responseMailbox.failure() != null) {
            failResponseStream(stream, output);
            return;
        }
        if (stream.responseMailbox != null && stream.responseChunk == null
                && stream.responseMailbox.isFinished()) {
            stream.responseTrailers = stream.responseMailbox.trailers();
            endResponseStream(stream, output);
            return;
        }
        if (stream.tunnel && stream.responseChunk == null && stream.tunnelOutput.isEmpty()
                && stream.tunnelClosePending) {
            output.accept(frame(Http2Frame.DATA, END_STREAM, stream.id, new byte[0]));
            stream.localEnd = true;
            removeIfClosed(stream);
            return;
        }
        while (connectionSendWindow > 0 && stream.sendWindow > 0) {
            if (stream.responseChunk == null) {
                if (stream.responseBody != null) {
                    stream.responseChunk = stream.responseBody;
                    stream.responseBody = null;
                    stream.responseOffset = 0;
                } else if (stream.responseMailbox != null) {
                    try {
                        stream.responseChunk = stream.responseMailbox.pollChunk();
                    } catch (IOException e) {
                        failResponseStream(stream, output);
                        return;
                    }
                    stream.responseOffset = 0;
                    if (stream.responseChunk == null) {
                        if (stream.responseMailbox.failure() != null) {
                            failResponseStream(stream, output);
                        } else if (stream.responseMailbox.isFinished()) {
                            stream.responseTrailers = stream.responseMailbox.trailers();
                            endResponseStream(stream, output);
                        }
                        return;
                    }
                } else if (stream.tunnel) {
                    stream.activeTunnelWrite = stream.tunnelOutput.peek();
                    stream.responseChunk = stream.activeTunnelWrite == null
                            ? null : stream.activeTunnelWrite.bytes;
                    stream.responseOffset = 0;
                    if (stream.responseChunk == null) {
                        if (stream.tunnelClosePending) {
                            output.accept(frame(Http2Frame.DATA, END_STREAM, stream.id, new byte[0]));
                            stream.localEnd = true;
                            removeIfClosed(stream);
                        }
                        return;
                    }
                } else {
                    return;
                }
            }
            int remaining = stream.responseChunk.length - stream.responseOffset;
            int length = Math.min(remaining, Math.min(peerMaxFrameSize,
                    Math.min(connectionSendWindow, stream.sendWindow)));
            byte[] payload = new byte[length];
            System.arraycopy(stream.responseChunk, stream.responseOffset, payload, 0, length);
            stream.responseOffset += length;
            connectionSendWindow -= length;
            stream.sendWindow -= length;
            boolean chunkEnd = stream.responseOffset == stream.responseChunk.length;
            boolean end = chunkEnd && stream.responseBody == null && stream.responseMailbox == null
                    && stream.responseTrailers == null && !stream.tunnel;
            ByteBuffer encoded = frame(
                    Http2Frame.DATA, end ? END_STREAM : 0, stream.id, payload);
            CompletableFuture<Void> completion = stream.tunnel && chunkEnd
                    && stream.activeTunnelWrite != null
                    ? stream.activeTunnelWrite.completion : null;
            emit(output, encoded, completion);
            if (!chunkEnd) continue;
            if (stream.tunnel) {
                stream.tunnelOutput.poll();
                stream.queuedTunnelBytes -= stream.responseChunk.length;
                stream.activeTunnelWrite = null;
            }
            stream.responseChunk = null;
            stream.responseOffset = 0;
            if (!stream.tunnel && stream.responseBody == null && stream.responseMailbox == null
                    && stream.responseTrailers != null) {
                endResponseStream(stream, output);
                return;
            }
            if (end) {
                stream.localEnd = true;
                removeIfClosed(stream);
                return;
            }
        }
    }

    private void failResponseStream(Stream stream, Consumer<ByteBuffer> output) {
        output.accept(frame(Http2Frame.RST_STREAM, 0, stream.id, intPayload(2)));
        stream.reset = true;
        streams.remove(stream.id);
        failTunnelWrites(stream, new IOException("HTTP/2 response stream failed"));
        detachMailbox(stream);
    }

    private void endResponseStream(Stream stream, Consumer<ByteBuffer> output) {
        if (hasTrailers(stream.responseTrailers)) {
            List<HpackHeader> trailers = new ArrayList<HpackHeader>();
            for (Map.Entry<String, List<String>> entry : stream.responseTrailers.entries()) {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                if (HttpTrailerFields.isForbidden(name)) continue;
                for (String value : entry.getValue()) trailers.add(new HpackHeader(name, value));
            }
            writeHeaderBlock(stream.id, encoder.encode(trailers), true, output);
        } else {
            output.accept(frame(Http2Frame.DATA, END_STREAM, stream.id, new byte[0]));
        }
        stream.responseTrailers = null;
        stream.localEnd = true;
        detachMailbox(stream);
        removeIfClosed(stream);
    }

    private static boolean hasTrailers(HttpHeaders trailers) {
        if (trailers == null) return false;
        for (Map.Entry<String, List<String>> entry : trailers.entries()) {
            if (!HttpTrailerFields.isForbidden(entry.getKey()) && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void emit(
            Consumer<ByteBuffer> output,
            ByteBuffer frame,
            CompletableFuture<Void> completion) {
        if (completion != null && output instanceof Output) {
            ((Output) output).accept(frame, completion);
        } else {
            output.accept(frame);
            if (completion != null) completion.complete(null);
        }
    }

    private void failAllTunnelWrites(Throwable failure) {
        for (Stream stream : streams.values()) failTunnelWrites(stream, failure);
    }

    private static void failTunnelWrites(Stream stream, Throwable failure) {
        if (stream.activeTunnelWrite != null && stream.activeTunnelWrite.completion != null) {
            stream.activeTunnelWrite.completion.completeExceptionally(failure);
        }
        TunnelWrite write;
        while ((write = stream.tunnelOutput.poll()) != null) {
            if (write != stream.activeTunnelWrite && write.completion != null) {
                write.completion.completeExceptionally(failure);
            }
        }
        stream.activeTunnelWrite = null;
        stream.queuedTunnelBytes = 0;
    }

    private void writeResponseHeaders(
            int streamId,
            int status,
            HttpHeaders source,
            boolean endStream,
            Consumer<ByteBuffer> output) {
        List<HpackHeader> headers = new ArrayList<HpackHeader>();
        headers.add(new HpackHeader(":status", Integer.toString(status)));
        for (Map.Entry<String, List<String>> entry : source.entries()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (connectionSpecific(name)) continue;
            for (String value : entry.getValue()) headers.add(new HpackHeader(name, value));
        }
        writeHeaderBlock(streamId, encoder.encode(headers), endStream, output);
    }

    private void writeHeaderBlock(int streamId, byte[] block, boolean endStream, Consumer<ByteBuffer> output) {
        int offset = 0;
        boolean first = true;
        do {
            int length = Math.min(peerMaxFrameSize, block.length - offset);
            byte[] fragment = new byte[length];
            System.arraycopy(block, offset, fragment, 0, length);
            offset += length;
            int flags = offset == block.length ? END_HEADERS : 0;
            if (first && endStream) flags |= END_STREAM;
            output.accept(frame(first ? Http2Frame.HEADERS : Http2Frame.CONTINUATION,
                    flags, streamId, fragment));
            first = false;
        } while (offset < block.length);
    }

    private static byte[] headerFragment(Http2Frame frame) throws Http2Exception {
        byte[] payload = frame.payload();
        int offset = 0;
        int padding = 0;
        if ((frame.flags() & PADDED) != 0) {
            if (payload.length == 0) throw connection(1, "missing HEADERS padding length");
            padding = payload[offset++] & 0xff;
        }
        if ((frame.flags() & PRIORITY) != 0) offset += 5;
        if (offset + padding > payload.length) throw connection(1, "invalid HEADERS padding");
        byte[] result = new byte[payload.length - offset - padding];
        System.arraycopy(payload, offset, result, 0, result.length);
        return result;
    }

    private static byte[] dataPayload(Http2Frame frame) throws Http2Exception {
        byte[] payload = frame.payload();
        if ((frame.flags() & PADDED) == 0) return payload;
        if (payload.length == 0) throw connection(1, "missing DATA padding length");
        int padding = payload[0] & 0xff;
        if (padding >= payload.length) throw connection(1, "invalid DATA padding");
        byte[] result = new byte[payload.length - padding - 1];
        System.arraycopy(payload, 1, result, 0, result.length);
        return result;
    }

    private int activeStreams() {
        int active = 0;
        for (Stream stream : streams.values()) if (!stream.reset && !(stream.localEnd && stream.remoteEnd)) active++;
        return active;
    }

    private void removeIfClosed(Stream stream) {
        if (stream.localEnd && stream.remoteEnd && !stream.responseDrainPending) {
            streams.remove(stream.id);
            detachMailbox(stream);
        }
    }

    private static void detachMailbox(Stream stream) {
        if (stream.responseMailbox != null) stream.responseMailbox.onReadable(null);
        stream.responseMailbox = null;
        stream.responseReadableSignal = null;
    }

    private static boolean isExtendedConnect(List<HpackHeader> headers) {
        boolean connect = false;
        boolean protocol = false;
        for (HpackHeader header : headers) {
            if (":method".equals(header.name()) && "CONNECT".equals(header.value())) connect = true;
            if (":protocol".equals(header.name())) protocol = true;
        }
        return connect && protocol;
    }

    private static String unique(String current, String value, int streamId, String name) throws Http2Exception {
        if (current != null) throw stream(1, streamId, "duplicate " + name);
        return value;
    }

    private static boolean connectionSpecific(String name) {
        return "connection".equals(name) || "keep-alive".equals(name)
                || "proxy-connection".equals(name) || "transfer-encoding".equals(name)
                || "upgrade".equals(name);
    }

    private static byte[] intPayload(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
    private static ByteBuffer frame(int type, int flags, int streamId, byte[] payload) {
        return new Http2Frame(type, flags, streamId, payload).encode();
    }
    private static Http2Exception connection(int error, String message) { return new Http2Exception(error, 0, message); }
    private static Http2Exception stream(int error, int streamId, String message) { return new Http2Exception(error, streamId, message); }

    /** 将完成的请求或隧道数据交给连接所属的上层处理器。 */
    public interface Listener {
        /** 一条流的请求头与所需正文已达到可交付状态。 */
        void onRequest(int streamId, HttpRequest request) throws Http2Exception;

        /** 扩展 CONNECT 隧道收到新数据；未接受隧道时默认拒绝。 */
        default void onTunnelData(int streamId, ByteBuffer data) throws Http2Exception {
            throw new Http2Exception(5, streamId, "extended CONNECT tunnel is not accepted");
        }

        /** 隧道的对端发送方向关闭。 */
        default void onTunnelClosed(int streamId) {
        }
    }

    /** 帧写出回调；可将某次写入与完成通知关联。 */
    public interface Output extends Consumer<ByteBuffer> {
        /** 将一次帧写入与完成 Stage 绑定。 */
        void accept(ByteBuffer frame, CompletableFuture<Void> completion);
    }

    /** 一条流的头部、正文、双向结束标志和输出流控状态。 */
    private static final class Stream {
        /** 客户端分配的流 ID。 */
        private final int id;
        /** 跨 HEADERS/CONTINUATION 拼接的压缩头部块。 */
        private final ByteArrayOutputStream headerBlock = new ByteArrayOutputStream();
        /** 普通请求体或升级前隧道数据的暂存区。 */
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        /** 当前流可发送的 DATA 字节额度。 */
        private int sendWindow;
        /** 当前流仍可接收的 DATA 字节额度。 */
        private int receiveWindow = INITIAL_WINDOW;
        /** 解压后的请求头。 */
        private List<HpackHeader> headers;
        /** 请求 Trailer。 */
        private HttpHeaders trailers;
        /** 首个请求头部块是否已结束。 */
        private boolean headersComplete;
        /** 当前拼接的头部块是否为 Trailer。 */
        private boolean trailersBlock;
        /** 客户端请求方向是否已 END_STREAM。 */
        private boolean remoteEnd;
        /** 服务端响应方向是否已 END_STREAM。 */
        private boolean localEnd;
        /** 请求是否已经交付过应用回调。 */
        private boolean dispatched;
        /** 响应是否已经开始，防止重复发布。 */
        private boolean responseStarted;
        /** 流是否已被 RST_STREAM 终止。 */
        private boolean reset;
        /** 已聚合响应尚待发送的字节。 */
        private byte[] responseBody;
        /** 当前正在受窗口限制发送的正文块。 */
        private byte[] responseChunk;
        /** 当前响应块内已发送的偏移量。 */
        private int responseOffset;
        /** 流式响应的正文邮箱。 */
        private ResponseBodyMailbox responseMailbox;
        /** 正文结束后要发出的响应 Trailer。 */
        private HttpHeaders responseTrailers;
        /** 响应邮箱变为可读时唤醒所属连接的回调。 */
        private Runnable responseReadableSignal;
        /** 隧道尚未发送的写入队列。 */
        private final ArrayDeque<TunnelWrite> tunnelOutput = new ArrayDeque<TunnelWrite>();
        /** 当前正在拆分为 DATA 帧的隧道写入。 */
        private TunnelWrite activeTunnelWrite;
        /** 隧道输出队列的字节预算占用。 */
        private int queuedTunnelBytes;
        /** 请求头声明了扩展 CONNECT。 */
        private boolean extendedConnect;
        /** 扩展 CONNECT 已获得成功响应并进入隧道模式。 */
        private boolean tunnel;
        /** 隧道输出排完后需要发送 END_STREAM。 */
        private boolean tunnelClosePending;
        /** 禁止正文上网但仍需排空应用邮箱的响应。 */
        private boolean responseDrainPending;
        /** 保存原始请求方法以判断 HEAD 响应。 */
        private String requestMethod;

        private Stream(int id, int sendWindow) { this.id = id; this.sendWindow = sendWindow; }
    }

    /** 一次隧道发送及其异步完成通知。 */
    private static final class TunnelWrite {
        /** 此次发送的全部字节。 */
        private final byte[] bytes;
        /** 字节被连接输出处理后完成的通知。 */
        private final CompletableFuture<Void> completion;

        private TunnelWrite(byte[] bytes, CompletableFuture<Void> completion) {
            this.bytes = bytes;
            this.completion = completion;
        }
    }
}
