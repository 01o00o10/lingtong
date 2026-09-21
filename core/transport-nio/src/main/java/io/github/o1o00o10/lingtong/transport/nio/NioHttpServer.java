/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport.nio;

import io.github.o1o00o10.lingtong.buffer.BufferArena;
import io.github.o1o00o10.lingtong.buffer.ByteBlock;
import io.github.o1o00o10.lingtong.execution.BoundedExecutionDomain;
import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.RequestTrace;
import io.github.o1o00o10.lingtong.http.HttpResponse;
import io.github.o1o00o10.lingtong.http.ConnectionUpgrade;
import io.github.o1o00o10.lingtong.http.UpgradeChannel;
import io.github.o1o00o10.lingtong.http.ResponseBodyMailbox;
import io.github.o1o00o10.lingtong.http.StreamingHttpResponse;
import io.github.o1o00o10.lingtong.http.TlsConnectionInfo;
import io.github.o1o00o10.lingtong.http1.Http1Encoder;
import io.github.o1o00o10.lingtong.http1.Http1Parser;
import io.github.o1o00o10.lingtong.http1.Http1ResponseCompressor;
import io.github.o1o00o10.lingtong.http1.HttpParseException;
import io.github.o1o00o10.lingtong.http2.Http2Exception;
import io.github.o1o00o10.lingtong.http2.Http2ServerConnection;
import io.github.o1o00o10.lingtong.transport.RequestProcessor;
import io.github.o1o00o10.lingtong.transport.StreamingResponseProcessor;
import io.github.o1o00o10.lingtong.transport.TransportConfig;
import io.github.o1o00o10.lingtong.transport.TransportServer;

import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * 网络入口。阅读路径：start/acceptLoop -> IoShard.runLoop -> Connection.parseBufferedInput
 * -> dispatch/processOnWorker -> sendOnShard/onWritable -> finishResponseOnShard。
 * 每个连接只归一个 Selector 分片修改；工作线程通过 shard.submit 投递写回命令。
 * HTTP/2 多路复用、TLS 和 Upgrade 仍由同一连接状态机维护。
 */
public final class NioHttpServer implements TransportServer {
    /** 运行时诊断日志；FINE 记录请求轨迹，WARNING 及以上记录故障。 */
    private static final Logger LOGGER = Logger.getLogger(NioHttpServer.class.getName());
    /** 跨监听器唯一的连接诊断 ID，不写入协议报文。 */
    private static final AtomicLong NEXT_CONNECTION_ID = new AtomicLong();
    /** 本监听器的固定网络上限与协议开关。 */
    private final TransportConfig config;
    /** 向上交付 HTTP 请求的应用处理器。 */
    private final RequestProcessor processor;
    /** 执行业务请求的有界工作域。 */
    private final BoundedExecutionDomain execution;
    /** 串行化 start/close 的生命周期锁。 */
    private final Object lifecycleMonitor = new Object();
    /** 等待连接排空时使用的监视器。 */
    private final Object drainMonitor = new Object();
    /** 接入连接时轮询选择 I/O 分片。 */
    private final AtomicInteger nextShard = new AtomicInteger();
    /** 尚未关闭的连接总数。 */
    private final AtomicInteger activeConnections = new AtomicInteger();
    /** 接入成功的连接累计数。 */
    private final AtomicLong acceptedConnections = new AtomicLong();
    /** 因容量或关闭而拒绝的连接累计数。 */
    private final AtomicLong rejectedConnections = new AtomicLong();

    /** 监听器及分片线程是否正在运行。 */
    private volatile boolean running;
    /** 是否仍允许新连接进入。 */
    private volatile boolean accepting;
    /** 实际绑定端口，启动前为 -1。 */
    private volatile int boundPort = -1;
    /** Acceptor 使用的服务端 Socket。 */
    private ServerSocketChannel serverChannel;
    /** 唯一的接入线程。 */
    private Thread acceptorThread;
    /** 分担连接读写的 Selector 分片。 */
    private IoShard[] shards;
    /** 可选的流式 GZIP 压缩工作域。 */
    private BoundedExecutionDomain compressionExecution;
    /** 可选的 TLS 委托任务工作域。 */
    private BoundedExecutionDomain tlsExecution;

    public NioHttpServer(
            TransportConfig config,
            RequestProcessor processor,
            BoundedExecutionDomain execution) {
        if (config == null || processor == null || execution == null) {
            throw new IllegalArgumentException("transport dependencies must not be null");
        }
        this.config = config;
        this.processor = processor;
        this.execution = execution;
    }

    @Override
    public void start() throws IOException {
        // 先建立分片和监听 Socket，再开放 accept；失败时释放尚未发布的资源。
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }

            IoShard[] nextShards = new IoShard[config.ioShards()];
            ServerSocketChannel nextServer = null;
            BoundedExecutionDomain nextCompression = config.compression().enabled()
                    ? new BoundedExecutionDomain(
                            config.ioShards(),
                            config.commandQueueCapacity(),
                            "lingtong-compression")
                    : null;
            BoundedExecutionDomain nextTls = config.tls().enabled()
                    ? new BoundedExecutionDomain(
                            config.ioShards(),
                            config.commandQueueCapacity(),
                            "lingtong-tls")
                    : null;
            try {
                for (int i = 0; i < nextShards.length; i++) {
                    nextShards[i] = new IoShard(
                            i,
                            config,
                            processor,
                            execution,
                            nextCompression,
                            nextTls,
                            activeConnections,
                            drainMonitor);
                    nextShards[i].start();
                }

                nextServer = ServerSocketChannel.open();
                nextServer.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                nextServer.configureBlocking(true);
                nextServer.bind(new InetSocketAddress(config.bindAddress(), config.port()));

                shards = nextShards;
                serverChannel = nextServer;
                compressionExecution = nextCompression;
                tlsExecution = nextTls;
                boundPort = ((InetSocketAddress) nextServer.getLocalAddress()).getPort();
                running = true;
                accepting = true;
                acceptorThread = new Thread(this::acceptLoop, "lingtong-acceptor");
                acceptorThread.start();
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("listener started port=" + boundPort + " tls="
                            + config.tls().enabled() + " http2=" + config.http2Enabled());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.SEVERE, "listener startup failed " + failureLocation(e));
                closeQuietly(nextServer);
                closeShards(nextShards);
                closeExecution(nextCompression);
                closeExecution(nextTls);
                throw e;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int port() {
        return boundPort >= 0 ? boundPort : config.port();
    }

    public int activeConnections() { return activeConnections.get(); }
    public long acceptedConnections() { return acceptedConnections.get(); }
    public long rejectedConnections() { return rejectedConnections.get(); }
    public boolean isAccepting() { return accepting; }

    @Override
    public void close() {
        beginDrain();
        IoShard[] currentShards;
        BoundedExecutionDomain currentCompression;
        BoundedExecutionDomain currentTls;
        synchronized (lifecycleMonitor) {
            if (!running && serverChannel == null && shards == null) {
                return;
            }
            running = false;
            currentShards = shards;
            shards = null;
            currentCompression = compressionExecution;
            compressionExecution = null;
            currentTls = tlsExecution;
            tlsExecution = null;
        }
        closeShards(currentShards);
        closeExecution(currentCompression);
        closeExecution(currentTls);
    }

    public void beginDrain() {
        // 关闭监听 Socket 使阻塞的 accept 返回，已有连接留给各分片排空。
        Thread thread;
        IoShard[] currentShards;
        synchronized (lifecycleMonitor) {
            accepting = false;
            if (serverChannel != null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("listener draining port=" + port()
                            + " activeConnections=" + activeConnections.get());
                }
            }
            closeQuietly(serverChannel);
            serverChannel = null;
            thread = acceptorThread;
            acceptorThread = null;
            currentShards = shards;
        }
        join(thread);
        if (currentShards != null) {
            for (IoShard shard : currentShards) {
                if (shard != null) {
                    shard.beginDrain();
                }
            }
        }
    }

    public boolean awaitDrained(long timeoutMillis) {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("drain timeout must not be negative");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (drainMonitor) {
            while (activeConnections.get() > 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                int waitNanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis));
                try {
                    drainMonitor.wait(waitMillis, waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void acceptLoop() {
        // 接入线程不解析报文，只配置 Socket 并轮询投递到有容量的 I/O 分片。
        while (accepting) {
            SocketChannel channel = null;
            try {
                channel = serverChannel.accept();
                if (channel == null) {
                    continue;
                }
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                acceptedConnections.incrementAndGet();

                if (!registerWithAvailableShard(channel)) {
                    rejectedConnections.incrementAndGet();
                    LOGGER.warning("connection rejected: no available I/O shard");
                    closeQuietly(channel);
                }
            } catch (IOException e) {
                closeQuietly(channel);
                if (!accepting) {
                    return;
                }
                LOGGER.log(Level.WARNING, "accept failed " + failureLocation(e));
            } catch (RuntimeException e) {
                closeQuietly(channel);
                LOGGER.log(Level.SEVERE, "accept failed " + failureLocation(e));
            }
        }
    }

    /** 故障摘要保留异常类型与代码位置，避免把异常消息中的敏感数据写入日志。 */
    static String failureLocation(Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; depth < 8 && root.getCause() != null
                && root.getCause() != root; depth++) {
            root = root.getCause();
        }
        StackTraceElement[] frames = root.getStackTrace();
        return root.getClass().getName() + (frames.length == 0 ? "" : " at " + frames[0]);
    }

    private boolean registerWithAvailableShard(SocketChannel channel) {
        if (!accepting) {
            return false;
        }
        IoShard[] currentShards = shards;
        if (currentShards == null || currentShards.length == 0) {
            return false;
        }
        int first = Math.floorMod(nextShard.getAndIncrement(), currentShards.length);
        for (int offset = 0; offset < currentShards.length; offset++) {
            IoShard shard = currentShards[(first + offset) % currentShards.length];
            if (shard.register(channel)) {
                return true;
            }
        }
        return false;
    }

    private static void closeShards(IoShard[] values) {
        if (values == null) {
            return;
        }
        for (IoShard shard : values) {
            if (shard != null) {
                shard.close();
            }
        }
    }

    private static void closeExecution(BoundedExecutionDomain value) {
        if (value != null) {
            value.close();
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭或注册被拒绝时尽力回收，不阻断后续清理。
        }
    }

    /** 一个 Selector 线程及其独占的连接、缓冲池与命令队列。 */
    private static final class IoShard implements AutoCloseable {
        /** 限制恶意报文引起的协议告警量，单位纳秒。 */
        private final AtomicLong nextProtocolWarningNanos = new AtomicLong();
        /** 上次协议告警后省略的重复告警数。 */
        private final AtomicLong suppressedProtocolWarnings = new AtomicLong();
        /** 非法请求的默认响应。 */
        private static final HttpResponse BAD_REQUEST = HttpResponse.text(400, "Bad Request", "Bad Request\n");
        /** 请求体超限的默认响应。 */
        private static final HttpResponse PAYLOAD_TOO_LARGE =
                HttpResponse.text(413, "Payload Too Large", "Payload Too Large\n");
        /** 请求头超限的默认响应。 */
        private static final HttpResponse REQUEST_HEADER_FIELDS_TOO_LARGE = HttpResponse.text(
                431, "Request Header Fields Too Large", "Request Header Fields Too Large\n");
        /** 未处理异常的默认响应。 */
        private static final HttpResponse INTERNAL_ERROR =
                HttpResponse.text(500, "Internal Server Error", "Internal Server Error\n");
        /** 请求读取超时的默认响应。 */
        private static final HttpResponse REQUEST_TIMEOUT =
                HttpResponse.text(408, "Request Timeout", "Request Timeout\n");
        /** 工作队列过载时的默认响应。 */
        private static final HttpResponse UNAVAILABLE =
                HttpResponse.text(503, "Service Unavailable", "Service Unavailable\n");

        /** 分片序号，用于线程名和连接分配。 */
        private final int index;
        /** 此分片唯一的 Selector。 */
        private final Selector selector;
        /** 其他线程提交的连接状态变更命令。 */
        private final ArrayBlockingQueue<Runnable> commands;
        /** 避免重复唤醒 Selector 的标记。 */
        private final AtomicBoolean wakeupPending = new AtomicBoolean();
        /** 此分片独占的有界读取缓冲池。 */
        private final BufferArena buffers;
        /** 监听器配置快照。 */
        private final TransportConfig config;
        /** 向上交付请求的处理器。 */
        private final RequestProcessor processor;
        /** 应用工作线程域。 */
        private final BoundedExecutionDomain execution;
        /** 压缩任务工作线程域；未启用时为 null。 */
        private final BoundedExecutionDomain compressionExecution;
        /** TLS 委托任务工作线程域；未启用时为 null。 */
        private final BoundedExecutionDomain tlsExecution;
        /** 与服务器共享的活跃连接计数。 */
        private final AtomicInteger activeConnections;
        /** 与服务器共享的排空等待监视器。 */
        private final Object drainMonitor;
        /** 无状态 HTTP/1 响应编码器。 */
        private final Http1Encoder encoder = new Http1Encoder();
        /** 此分片的响应 GZIP 协商器。 */
        private final Http1ResponseCompressor compressor;
        /** 可信代理规则解析器。 */
        private final ForwardedRequestResolver forwardedRequestResolver;

        /** Selector 循环是否仍运行。 */
        private volatile boolean running;
        /** 是否正在拒绝新请求并排空连接。 */
        private volatile boolean draining;
        /** 排空命令是否已在本分片线程生效。 */
        private boolean drainApplied;
        /** 执行 Selector 循环的线程。 */
        private Thread thread;

        private IoShard(
                int index,
                TransportConfig config,
                RequestProcessor processor,
                BoundedExecutionDomain execution,
                BoundedExecutionDomain compressionExecution,
                BoundedExecutionDomain tlsExecution,
                AtomicInteger activeConnections,
                Object drainMonitor) throws IOException {
            this.index = index;
            this.config = config;
            this.processor = processor;
            this.execution = execution;
            this.compressionExecution = compressionExecution;
            this.tlsExecution = tlsExecution;
            this.activeConnections = activeConnections;
            this.drainMonitor = drainMonitor;
            this.selector = Selector.open();
            this.commands = new ArrayBlockingQueue<Runnable>(config.commandQueueCapacity());
            this.buffers = new BufferArena(config.readBufferSize(), config.buffersPerShard(), true);
            this.compressor = new Http1ResponseCompressor(
                    config.compression().enabled(),
                    config.compression().minResponseBytes(),
                    config.compression().mimeTypes(),
                    config.compression().excludedUserAgents());
            this.forwardedRequestResolver = new ForwardedRequestResolver(config.proxy());
        }

        private void start() {
            running = true;
            thread = new Thread(this::runLoop, "lingtong-io-" + index);
            thread.setDaemon(true);
            thread.start();
        }

        private boolean register(SocketChannel channel) {
            if (!running || draining) {
                return false;
            }
            activeConnections.incrementAndGet();
            if (submit(() -> registerOnShard(channel))) {
                return true;
            }
            connectionClosed();
            return false;
        }

        private void beginDrain() {
            draining = true;
            signal();
        }

        private boolean submit(Runnable command) {
            if (!running || !commands.offer(command)) {
                return false;
            }
            signal();
            return true;
        }

        private void signal() {
            if (wakeupPending.compareAndSet(false, true)) {
                selector.wakeup();
            }
        }

        private void runLoop() {
            // 命令队列承接工作线程回调；连接状态和 SelectionKey 只在本线程推进。
            try {
                while (running) {
                    drainCommands();
                    applyDrainIfNeeded();
                    selector.select(250L);
                    drainCommands();
                    applyDrainIfNeeded();
                    processSelectedKeys();
                    sweepConnections();
                }
            } catch (IOException ignored) {
                running = false;
            } finally {
                drainCommands();
                closeAllConnections();
                closeQuietly(selector);
            }
        }

        private void drainCommands() {
            // 清除 wakeup 标志后再检查队列，避免并发投递时漏掉唤醒。
            do {
                Runnable command;
                while ((command = commands.poll()) != null) {
                    try {
                        command.run();
                    } catch (RuntimeException failure) {
                        // 单条连接命令失败不能终止整个 I/O 分片。
                        LOGGER.log(Level.SEVERE, "I/O command failed " + failureLocation(failure));
                    }
                }
                wakeupPending.set(false);
            } while (!commands.isEmpty() && wakeupPending.compareAndSet(false, true));
        }

        private void logProtocolRejection(String message) {
            long now = System.nanoTime();
            long next = nextProtocolWarningNanos.get();
            if (now >= next && nextProtocolWarningNanos.compareAndSet(next,
                    now + TimeUnit.SECONDS.toNanos(1))) {
                LOGGER.warning(message + " suppressedSinceLast="
                        + suppressedProtocolWarnings.getAndSet(0));
            } else {
                suppressedProtocolWarnings.incrementAndGet();
            }
        }

        private void registerOnShard(SocketChannel channel) {
            if (!running || draining) {
                connectionClosed();
                closeQuietly(channel);
                return;
            }
            ByteBlock block = buffers.acquire();
            if (block == null) {
                connectionClosed();
                closeQuietly(channel);
                return;
            }
            try {
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                Connection connection = new Connection(this, channel, key, block);
                key.attach(connection);
                connection.initializeOnShard();
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "connection registration failed " + failureLocation(e));
                buffers.releaseFromIo(block);
                connectionClosed();
                closeQuietly(channel);
            }
        }

        private void applyDrainIfNeeded() {
            if (!draining || drainApplied) {
                return;
            }
            drainApplied = true;
            for (SelectionKey key : new ArrayList<SelectionKey>(selector.keys())) {
                Object attachment = key.attachment();
                if (attachment instanceof Connection) {
                    ((Connection) attachment).beginDrainOnShard();
                }
            }
        }

        private void connectionClosed() {
            if (activeConnections.decrementAndGet() == 0) {
                synchronized (drainMonitor) {
                    drainMonitor.notifyAll();
                }
            }
        }

        private void processSelectedKeys() {
            Set<SelectionKey> selected = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selected.iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();
                Connection connection = (Connection) key.attachment();
                if (connection == null) {
                    key.cancel();
                    continue;
                }
                try {
                    if (!key.isValid()) {
                        connection.closeOnShard();
                    } else {
                        if (key.isReadable()) {
                            connection.onReadable();
                        }
                        if (key.isValid() && key.isWritable()) {
                            connection.onWritable();
                        }
                    }
                } catch (CancelledKeyException | IOException e) {
                    if (connection.tlsEngine != null && !connection.tlsHandshakeComplete) {
                        LOGGER.warning("connection=" + connection.diagnosticId
                                + " TLS handshake failed " + failureLocation(e));
                    } else if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("connection=" + connection.diagnosticId
                                + " I/O closed " + failureLocation(e));
                    }
                    connection.closeOnShard();
                }
            }
        }

        private void sweepConnections() {
            long now = System.nanoTime();
            for (SelectionKey key : new ArrayList<SelectionKey>(selector.keys())) {
                Connection connection = (Connection) key.attachment();
                if (connection != null && (connection.closeRequested.get() || !key.isValid())) {
                    connection.closeOnShard();
                } else if (connection != null) {
                    connection.onTimer(now);
                }
            }
        }

        private void closeAllConnections() {
            for (SelectionKey key : new ArrayList<SelectionKey>(selector.keys())) {
                Object attachment = key.attachment();
                if (attachment instanceof Connection) {
                    ((Connection) attachment).closeOnShard();
                } else {
                    key.cancel();
                    closeQuietly(key.channel());
                }
            }
        }

        @Override
        public void close() {
            running = false;
            selector.wakeup();
            join(thread);
        }

        /** 单连接识别出的协议阶段。 */
        private enum Protocol {
            /** 等待足够字节以识别协议。 */
            PROBING,
            /** 按 HTTP/1.1 解析。 */
            HTTP1,
            /** 已选择 HTTP/2，等待客户端连接序言。 */
            HTTP2_PREFACE,
            /** 按 HTTP/2 帧状态机解析。 */
            HTTP2,
            /** HTTP 协议已切换为 WebSocket 等升级协议。 */
            UPGRADED
        }

        /** 单条 Socket 的协议状态，仅所属 IoShard 直接修改。 */
        private final class Connection {
            /** 排查同一 Socket 上多个请求、HTTP/2 流及失败时使用的关联 ID。 */
            private final long diagnosticId = NEXT_CONNECTION_ID.incrementAndGet();
            /** 拥有此连接的 I/O 分片。 */
            private final IoShard shard;
            /** 底层 TCP 通道。 */
            private final SocketChannel channel;
            /** Selector 注册键及读写兴趣位。 */
            private final SelectionKey key;
            /** 从分片缓冲池租来的网络读取块。 */
            private final ByteBlock block;
            /** 当前可解析的明文字节视图。 */
            private ByteBuffer input;
            /** 当前 HTTP/1 请求的增量解析器。 */
            private final Http1Parser parser;
            /** 协议识别及升级后的当前阶段。 */
            private Protocol protocol = Protocol.PROBING;
            /** HTTP/2 连接与流控状态，进入 h2 后创建。 */
            private Http2ServerConnection http2;
            /** 已生效的 WebSocket 等升级协议回调。 */
            private ConnectionUpgrade upgrade;
            /** 等待握手响应写完后才生效的升级回调。 */
            private ConnectionUpgrade pendingUpgrade;
            /** 等待 101 响应写完后接入 h2 流 1 的请求。 */
            private HttpRequest pendingH2cRequest;
            /** 尚未写出的 HTTP/2 或升级协议帧。 */
            private final ArrayDeque<ProtocolWrite> protocolOutput =
                    new ArrayDeque<ProtocolWrite>();
            /** HTTP/2 编码器向本连接输出队列提交帧的适配器。 */
            private final Http2ServerConnection.Output http2Output =
                    new Http2ServerConnection.Output() {
                        @Override public void accept(ByteBuffer frame) {
                            enqueueProtocolOutput(frame);
                        }

                        @Override public void accept(
                                ByteBuffer frame, CompletableFuture<Void> completion) {
                            enqueueProtocolOutput(frame, completion);
                        }
                    };
            /** 按流 ID 保存正在运行的 HTTP/2 升级协议。 */
            private final Map<Integer, ConnectionUpgrade> http2Upgrades =
                    new HashMap<Integer, ConnectionUpgrade>();
            /** 按流 ID 保存尚未完成的 HTTP/2 请求。 */
            private final Map<Integer, HttpRequest> http2Requests =
                    new HashMap<Integer, HttpRequest>();
            /** 当前连接尚未完成的 HTTP/2 请求数。 */
            private int activeHttp2Requests;
            /** 任意线程提交的关闭请求标记。 */
            private final AtomicBoolean closeRequested = new AtomicBoolean();
            /** 原始 TCP 对端地址。 */
            private final InetSocketAddress remoteAddress;
            /** 本地监听端点地址。 */
            private final InetSocketAddress localAddress;
            /** 此连接独占的 TLS 引擎；明文连接为 null。 */
            private final SSLEngine tlsEngine;
            /** TLS 网络侧加密输入缓冲区。 */
            private ByteBuffer tlsInput;
            /** TLS 网络侧待写加密输出缓冲区。 */
            private ByteBuffer tlsOutput;
            /** TLS 握手是否已经结束。 */
            private boolean tlsHandshakeComplete;
            /** 是否有 TLS 委托任务正在工作域执行。 */
            private volatile boolean tlsTaskRunning;
            /** 委托任务跨线程返回的失败原因。 */
            private volatile Throwable tlsTaskFailure;
            /** 是否正在发送 TLS close_notify 并关闭连接。 */
            private boolean tlsClosing;
            /** ALPN 是否选中了 h2。 */
            private boolean alpnSelectedHttp2;
            /** 已完成握手后可交付给 Servlet 的 TLS 元数据。 */
            private TlsConnectionInfo tlsConnectionInfo;
            /** 建连时的单调时钟，供握手超时判断。 */
            private final long connectedNanos;

            /** 连接已关闭且不得再次写入。 */
            private boolean closed;
            /** 当前 HTTP/1 请求仍在应用或输出阶段。 */
            private boolean processing;
            /** 请求体邮箱满导致此连接暂停网络读。 */
            private boolean bodyReadPaused;
            /** 当前 HTTP/1 响应已提交给网络写侧。 */
            private boolean responseCommitted;
            /** 本次响应写完后应关闭连接。 */
            private boolean closeAfterWrite;
            /** 当前请求的头部读取计时已经开始。 */
            private boolean requestStarted;
            /** 该持久连接已服务的请求数。 */
            private int requestsServed;
            /** 当前请求首字节到达的单调时间。 */
            private long requestStartedNanos;
            /** 最近网络活动的单调时间。 */
            private long lastActivityNanos;
            /** 当前正在处理的 HTTP/1 请求。 */
            private HttpRequest activeRequest;
            /** 尚未写完的明文响应缓冲。 */
            private ByteBuffer output;
            /** 明文输出写完后的完成通知。 */
            private CompletableFuture<Void> outputCompletion;
            /** TLS 加密输出写完后的完成通知。 */
            private CompletableFuture<Void> tlsOutputCompletion;
            /** 流式响应尚未排空的正文邮箱。 */
            private ResponseBodyMailbox responseBody;
            /** 正文是否按 HTTP/1 Chunked 编码。 */
            private boolean responseChunked;
            /** HEAD 或无正文状态下是否抑制线上正文。 */
            private boolean responseBodySuppressed;
            /** 是否正在写流式响应最后一个 Chunk。 */
            private boolean streamingFinalWrite;

            private Connection(IoShard shard, SocketChannel channel, SelectionKey key, ByteBlock block)
                    throws IOException {
                this.shard = shard;
                this.channel = channel;
                this.key = key;
                this.block = block;
                this.remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
                this.localAddress = (InetSocketAddress) channel.getLocalAddress();
                if (config.tls().enabled()) {
                    this.tlsEngine = config.tls().sslContext().createSSLEngine();
                    tlsEngine.setUseClientMode(false);
                    if (!config.tls().protocols().isEmpty()) {
                        tlsEngine.setEnabledProtocols(config.tls().protocols().toArray(
                                new String[config.tls().protocols().size()]));
                    }
                    if (!config.tls().cipherSuites().isEmpty()) {
                        tlsEngine.setEnabledCipherSuites(config.tls().cipherSuites().toArray(
                                new String[config.tls().cipherSuites().size()]));
                    }
                    if (config.tls().clientAuth()
                            == io.github.o1o00o10.lingtong.transport.TlsConfig.ClientAuth.NEED) {
                        tlsEngine.setNeedClientAuth(true);
                    } else if (config.tls().clientAuth()
                            == io.github.o1o00o10.lingtong.transport.TlsConfig.ClientAuth.WANT) {
                        tlsEngine.setWantClientAuth(true);
                    }
                    configureAlpn(tlsEngine);
                    SSLSession initialSession = tlsEngine.getSession();
                    int packetSize = checkedTlsBufferSize(initialSession.getPacketBufferSize());
                    int applicationSize = checkedTlsBufferSize(
                            initialSession.getApplicationBufferSize());
                    this.input = ByteBuffer.allocateDirect(checkedTlsBufferSize(
                            Math.max(config.readBufferSize(), applicationSize)));
                    this.tlsInput = ByteBuffer.allocateDirect(packetSize);
                    this.tlsOutput = ByteBuffer.allocateDirect(packetSize);
                    tlsOutput.flip();
                    tlsEngine.beginHandshake();
                } else {
                    this.tlsEngine = null;
                    this.input = block.writableBuffer();
                }
                this.parser = Http1Parser.streaming(
                        config.maxHeaderBytes(),
                        config.maxBodyBytes(),
                        config.requestBodyBufferBytes(),
                        config.requestBodyLowWaterBytes(),
                        config.maxHeaderCount(),
                        config.maxTrailerBytes(),
                        config.maxTrailerCount());
                this.connectedNanos = System.nanoTime();
                this.lastActivityNanos = connectedNanos;
            }

            private void initializeOnShard() throws IOException {
                if (tlsEngine != null) {
                    driveTlsHandshake();
                }
            }

            private void configureAlpn(SSLEngine engine) {
                if (!config.http2Enabled()) return;
                try {
                    config.tls().alpnProvider().configureServer(engine, "h2", "http/1.1");
                } catch (IOException ignored) {
                    // Java 8 仍可使用 h2c，或通过 SPI 注入具备 ALPN 能力的 Provider。
                }
            }

            private int checkedTlsBufferSize(int requested) throws SSLException {
                if (requested <= 0 || requested > config.tls().maxBufferBytes()) {
                    throw new SSLException("TLS provider requested an invalid buffer size: "
                            + requested);
                }
                return requested;
            }

            private void onReadable() throws IOException {
                if (closed || bodyReadPaused || responseCommitted) {
                    return;
                }
                if (tlsEngine != null) {
                    onTlsReadable();
                    return;
                }
                int read = channel.read(input);
                if (read < 0) {
                    closeOnShard();
                    return;
                }
                if (read > 0) {
                    long now = System.nanoTime();
                    lastActivityNanos = now;
                    if (!requestStarted) {
                        requestStarted = true;
                        requestStartedNanos = now;
                    }
                    parseBufferedInput();
                }
            }

            private void onTlsReadable() throws IOException {
                if (tlsTaskRunning) {
                    return;
                }
                ensureTlsNetworkInputCapacity();
                int read = channel.read(tlsInput);
                if (read < 0) {
                    try {
                        tlsEngine.closeInbound();
                    } catch (SSLException ignored) {
                        // 对端突然断开时直接关闭本连接。
                    }
                    closeOnShard();
                    return;
                }
                if (read > 0) {
                    lastActivityNanos = System.nanoTime();
                    driveTlsHandshake();
                    if (tlsHandshakeComplete && input.position() > 0 && !responseCommitted) {
                        parseBufferedInput();
                    }
                }
            }

            private void driveTlsHandshake() throws IOException {
                if (closed || tlsTaskRunning) {
                    return;
                }
                if (tlsTaskFailure != null) {
                    throw new SSLException("TLS delegated task failed", tlsTaskFailure);
                }
                while (!closed && !tlsTaskRunning && !tlsHandshakeComplete) {
                    if (tlsOutput.hasRemaining()) {
                        flushTlsOutput();
                        if (tlsOutput.hasRemaining()) {
                            key.interestOps(SelectionKey.OP_WRITE);
                            return;
                        }
                    }
                    SSLEngineResult.HandshakeStatus status = tlsEngine.getHandshakeStatus();
                    if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        scheduleTlsTasks();
                        return;
                    }
                    if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                        wrapTls(ByteBuffer.allocate(0));
                        continue;
                    }
                    if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                        SSLEngineResult result = unwrapTls();
                        if (result == null
                                || result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            key.interestOps(SelectionKey.OP_READ);
                            return;
                        }
                        if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                            closeOnShard();
                            return;
                        }
                        continue;
                    }
                    completeTlsHandshake();
                }
                if (tlsHandshakeComplete) {
                    unwrapTlsApplicationData();
                    updateReadInterest();
                }
            }

            private void unwrapTlsApplicationData() throws IOException {
                while (tlsInput.position() > 0 && !closed && !responseCommitted
                        && (!processing || !parser.isMessageComplete())) {
                    SSLEngineResult result = unwrapTls();
                    if (result == null
                            || result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        return;
                    }
                    if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                        closeOnShard();
                        return;
                    }
                    if (result.bytesProduced() > 0) {
                        long now = System.nanoTime();
                        if (!requestStarted) {
                            requestStarted = true;
                            requestStartedNanos = now;
                        }
                        lastActivityNanos = now;
                        parseBufferedInput();
                    }
                    SSLEngineResult.HandshakeStatus status = result.getHandshakeStatus();
                    if (status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                            && status != SSLEngineResult.HandshakeStatus.FINISHED) {
                        tlsHandshakeComplete = false;
                        driveTlsHandshake();
                        return;
                    }
                    if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                        return;
                    }
                }
            }

            private SSLEngineResult unwrapTls() throws IOException {
                if (tlsInput.position() == 0) {
                    return null;
                }
                while (true) {
                    tlsInput.flip();
                    SSLEngineResult result;
                    try {
                        result = tlsEngine.unwrap(tlsInput, input);
                    } finally {
                        tlsInput.compact();
                    }
                    if (result.getStatus() != SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            ensureTlsNetworkInputCapacity();
                        }
                        return result;
                    }
                    input = growTlsBuffer(input,
                            tlsEngine.getSession().getApplicationBufferSize());
                }
            }

            private void wrapTls(ByteBuffer source) throws IOException {
                if (tlsOutput.hasRemaining()) {
                    return;
                }
                while (true) {
                    tlsOutput.clear();
                    SSLEngineResult result = tlsEngine.wrap(source, tlsOutput);
                    tlsOutput.flip();
                    if (result.getStatus() != SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        if (result.getStatus() == SSLEngineResult.Status.CLOSED
                                && !tlsClosing) {
                            closeOnShard();
                        }
                        return;
                    }
                    tlsOutput = growEmptyTlsBuffer(tlsOutput,
                            tlsEngine.getSession().getPacketBufferSize());
                }
            }

            private void flushTlsOutput() throws IOException {
                if (!tlsOutput.hasRemaining()) {
                    return;
                }
                int written = channel.write(tlsOutput);
                if (written > 0) {
                    lastActivityNanos = System.nanoTime();
                }
            }

            private void scheduleTlsTasks() throws IOException {
                List<Runnable> tasks = new ArrayList<Runnable>();
                Runnable task;
                while ((task = tlsEngine.getDelegatedTask()) != null) {
                    tasks.add(task);
                }
                if (tasks.isEmpty()) {
                    throw new SSLException("TLS engine requested a delegated task but supplied none");
                }
                tlsTaskRunning = true;
                key.interestOps(0);
                if (tlsExecution == null || !tlsExecution.execute(() -> {
                    try {
                        for (Runnable delegated : tasks) {
                            delegated.run();
                        }
                    } catch (RuntimeException | Error failure) {
                        tlsTaskFailure = failure;
                    }
                    if (!shard.submit(this::resumeTlsAfterTaskOnShard)) {
                        requestCloseFromAnyThread();
                    }
                })) {
                    tlsTaskRunning = false;
                    throw new SSLException("TLS delegated-task execution was rejected");
                }
            }

            private void resumeTlsAfterTaskOnShard() {
                tlsTaskRunning = false;
                try {
                    driveTlsHandshake();
                } catch (IOException e) {
                    closeOnShard();
                }
            }

            private void completeTlsHandshake() throws SSLException {
                SSLSession session = tlsEngine.getSession();
                Certificate[] peer;
                try {
                    peer = session.getPeerCertificates();
                } catch (SSLPeerUnverifiedException ignored) {
                    peer = new Certificate[0];
                }
                List<X509Certificate> certificates = new ArrayList<X509Certificate>();
                for (Certificate certificate : peer) {
                    if (certificate instanceof X509Certificate) {
                        certificates.add((X509Certificate) certificate);
                    }
                }
                tlsConnectionInfo = new TlsConnectionInfo(
                        session.getProtocol(),
                        session.getCipherSuite(),
                        cipherKeySize(session.getCipherSuite()),
                        hexadecimal(session.getId()),
                        certificates.toArray(new X509Certificate[certificates.size()]));
                if (config.http2Enabled()) {
                    try {
                        alpnSelectedHttp2 = "h2".equals(
                                config.tls().alpnProvider().selectedProtocol(tlsEngine));
                        if (!alpnSelectedHttp2) protocol = Protocol.HTTP1;
                    } catch (IOException ignored) {
                        protocol = Protocol.HTTP1;
                    }
                }
                tlsHandshakeComplete = true;
            }

            private void ensureTlsNetworkInputCapacity() throws SSLException {
                if (tlsInput.hasRemaining()) {
                    return;
                }
                tlsInput = growTlsBuffer(tlsInput, tlsEngine.getSession().getPacketBufferSize());
            }

            private ByteBuffer growTlsBuffer(ByteBuffer current, int requested)
                    throws SSLException {
                int minimum = Math.max(current.capacity() + 1, requested);
                int capacity = nextTlsBufferCapacity(current.capacity(), minimum);
                ByteBuffer replacement = ByteBuffer.allocateDirect(capacity);
                current.flip();
                replacement.put(current);
                return replacement;
            }

            private ByteBuffer growEmptyTlsBuffer(ByteBuffer current, int requested)
                    throws SSLException {
                int minimum = Math.max(current.capacity() + 1, requested);
                return ByteBuffer.allocateDirect(nextTlsBufferCapacity(
                        current.capacity(), minimum));
            }

            private int nextTlsBufferCapacity(int current, int minimum) throws SSLException {
                int maximum = config.tls().maxBufferBytes();
                if (minimum > maximum || current >= maximum) {
                    throw new SSLException("TLS buffer limit exceeded");
                }
                long doubled = (long) current * 2L;
                return (int) Math.min(maximum, Math.max((long) minimum, doubled));
            }

            private int cipherKeySize(String cipher) {
                if (cipher.contains("AES_256") || cipher.contains("CHACHA20")) return 256;
                if (cipher.contains("3DES_EDE")) return 168;
                if (cipher.contains("AES_128") || cipher.contains("RC4_128")
                        || cipher.contains("SM4")) return 128;
                if (cipher.contains("DES40") || cipher.contains("RC4_40")) return 40;
                if (cipher.contains("DES_CBC")) return 56;
                return 0;
            }

            private String hexadecimal(byte[] bytes) {
                char[] result = new char[bytes.length * 2];
                char[] digits = "0123456789abcdef".toCharArray();
                for (int i = 0; i < bytes.length; i++) {
                    int value = bytes[i] & 0xff;
                    result[i * 2] = digits[value >>> 4];
                    result[i * 2 + 1] = digits[value & 0x0f];
                }
                return new String(result);
            }

            private void parseBufferedInput() {
                // 探测协议后只进入对应解析器；HTTP/1 在响应提交前不继续解析下一请求。
                input.flip();
                try {
                    if (protocol == Protocol.PROBING && !selectProtocol()) {
                        return;
                    }
                    if (protocol == Protocol.HTTP2_PREFACE && !completeH2cPreface()) {
                        return;
                    }
                    if (protocol == Protocol.HTTP2) {
                        receiveHttp2();
                        return;
                    }
                    if (protocol == Protocol.UPGRADED) {
                        receiveUpgraded();
                        return;
                    }
                    while (input.hasRemaining() && !closed && !responseCommitted) {
                        int positionBefore = input.position();
                        HttpRequest request = parser.parse(input);
                        if (request != null) {
                            dispatch(request);
                        }
                        if (parser.isMessageComplete()) {
                            requestStarted = false;
                            bodyReadPaused = false;
                            break;
                        }
                        // 请求体 mailbox 满时撤销读兴趣，消费方释放容量后再恢复。
                        if (parser.isBodyBackpressured()) {
                            bodyReadPaused = true;
                            break;
                        }
                        if (input.position() == positionBefore) {
                            break;
                        }
                    }
                } catch (HttpParseException e) {
                    shard.logProtocolRejection("connection=" + diagnosticId
                            + " protocol=http1 rejected status=" + e.statusCode());
                    input.position(input.limit());
                    HttpResponse rejection = e.statusCode() == 413 ? PAYLOAD_TOO_LARGE
                            : e.statusCode() == 431 ? REQUEST_HEADER_FIELDS_TOO_LARGE
                            : BAD_REQUEST;
                    sendOnShard(encoder.encode(rejection, false), false);
                } finally {
                    input.compact();
                }
                updateReadInterest();
            }

            private boolean selectProtocol() {
                // 明文连接按 HTTP/2 preface 增量探测；TLS 的 ALPN 若选 h2 则不回退 HTTP/1。
                if (!config.http2Enabled()) {
                    protocol = Protocol.HTTP1;
                    return true;
                }
                int available = input.remaining();
                int compared = Math.min(available, Http2ServerConnection.CLIENT_PREFACE.length);
                for (int i = 0; i < compared; i++) {
                    if (input.get(input.position() + i) != Http2ServerConnection.CLIENT_PREFACE[i]) {
                        if (alpnSelectedHttp2) {
                            closeOnShard();
                            return false;
                        }
                        protocol = Protocol.HTTP1;
                        return true;
                    }
                }
                if (available < Http2ServerConnection.CLIENT_PREFACE.length) return false;
                input.position(input.position() + Http2ServerConnection.CLIENT_PREFACE.length);
                protocol = Protocol.HTTP2;
                http2 = new Http2ServerConnection(config.maxHeaderBytes(), config.maxBodyBytes(), 100);
                enqueueProtocolOutput(http2.initialSettings());
                requestStarted = false;
                return true;
            }

            private boolean completeH2cPreface() {
                int available = input.remaining();
                int compared = Math.min(available, Http2ServerConnection.CLIENT_PREFACE.length);
                for (int i = 0; i < compared; i++) {
                    if (input.get(input.position() + i) != Http2ServerConnection.CLIENT_PREFACE[i]) {
                        closeOnShard();
                        return false;
                    }
                }
                if (available < Http2ServerConnection.CLIENT_PREFACE.length) return false;
                input.position(input.position() + Http2ServerConnection.CLIENT_PREFACE.length);
                protocol = Protocol.HTTP2;
                enqueueProtocolOutput(http2.initialSettings());
                enqueueProtocolOutput(new io.github.o1o00o10.lingtong.http2.Http2Frame(
                        io.github.o1o00o10.lingtong.http2.Http2Frame.SETTINGS, 1, 0, new byte[0]).encode());
                HttpRequest request = pendingH2cRequest;
                pendingH2cRequest = null;
                try {
                    http2.dispatchUpgradeRequest(request, (streamId, value) ->
                            dispatchHttp2(streamId, value));
                } catch (Http2Exception e) {
                    shard.logProtocolRejection("connection=" + diagnosticId
                            + " protocol=http2 stream=" + e.streamId()
                            + " rejected errorCode=" + e.errorCode());
                    enqueueProtocolOutput(http2.errorFrame(e));
                    closeAfterWrite = true;
                }
                requestStarted = false;
                return true;
            }

            private void receiveHttp2() {
                try {
                    http2.receive(input, new Http2ServerConnection.Listener() {
                        @Override
                        public void onRequest(int streamId, HttpRequest request) throws Http2Exception {
                            dispatchHttp2(streamId, request);
                        }

                        @Override
                        public void onTunnelData(int streamId, ByteBuffer data) throws Http2Exception {
                            ConnectionUpgrade current = http2Upgrades.get(streamId);
                            if (current == null) {
                                throw new Http2Exception(5, streamId, "extended CONNECT tunnel is not accepted");
                            }
                            try {
                                current.onInput(data);
                            } catch (IOException | RuntimeException e) {
                                http2Upgrades.remove(streamId);
                                current.onClosed();
                                throw new Http2Exception(2, streamId, "extended CONNECT handler failed");
                            }
                        }

                        @Override
                        public void onTunnelClosed(int streamId) {
                            ConnectionUpgrade current = http2Upgrades.remove(streamId);
                            if (current != null) current.onClosed();
                        }
                    }, http2Output);
                } catch (Http2Exception e) {
                    enqueueProtocolOutput(http2.errorFrame(e));
                    if (e.streamId() == 0) closeAfterWrite = true;
                }
            }

            private void dispatchHttp2(int streamId, HttpRequest request) throws Http2Exception {
                // 同一连接可有多个活动流：每条流独立计数、追踪并投递工作域。
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("connection=" + diagnosticId + " protocol=http2 stream="
                            + streamId + " method=" + request.method() + " dispatched");
                }
                RequestTrace trace = RequestTrace.begin(diagnosticId, streamId, request.method());
                final HttpRequest dispatched = (trace == null ? request : request.withTrace(trace))
                        .withConnectionInfo(
                        forwardedRequestResolver.resolve(
                                request.headers(), remoteAddress, localAddress, tlsConnectionInfo));
                requestsServed++;
                activeHttp2Requests++;
                http2Requests.put(streamId, dispatched);
                if (!execution.execute(() -> processHttp2OnWorker(streamId, dispatched))) {
                    if (trace != null) trace.finish("transport.worker-rejected");
                    LOGGER.warning("connection=" + diagnosticId + " protocol=http2 stream="
                            + streamId + " worker queue full");
                    activeHttp2Requests--;
                    http2Requests.remove(streamId);
                    throw new Http2Exception(7, streamId, "request execution was rejected");
                }
            }

            private void processHttp2OnWorker(int streamId, HttpRequest request) {
                RequestTrace trace = request.trace();
                if (trace != null) trace.event("worker.enter");
                if (processor instanceof StreamingResponseProcessor) {
                    processHttp2StreamingOnWorker(
                            streamId, request, (StreamingResponseProcessor) processor);
                    return;
                }
                CompletionStage<HttpResponse> pending;
                try {
                    pending = processor.processAsync(request);
                    if (pending == null) throw new IllegalStateException("null HTTP/2 response stage");
                } catch (Exception | LinkageError e) {
                    logApplicationFailure("http2", streamId, e);
                    if (trace != null) trace.finish("http2.application-failed");
                    completeHttp2Response(streamId, INTERNAL_ERROR);
                    return;
                }
                pending.whenComplete((response, failure) -> {
                    if (failure != null) logApplicationFailure("http2", streamId, failure);
                    else if (response == null) logApplicationFailure("http2", streamId,
                            new IllegalStateException("null response"));
                    HttpResponse completed = failure == null && response != null
                            ? response : INTERNAL_ERROR;
                    if (trace != null) {
                        trace.event("transport.response-published", "status=" + completed.status());
                        trace.finish("http2.application-complete");
                    }
                    completeHttp2Response(streamId, completed);
                });
            }

            private void processHttp2StreamingOnWorker(
                    int streamId,
                    HttpRequest request,
                    StreamingResponseProcessor streamingProcessor) {
                RequestTrace trace = request.trace();
                // 发布响应头与完成应用处理是两件事；重复发布必须立即拒绝。
                AtomicBoolean published = new AtomicBoolean();
                AtomicReference<StreamingHttpResponse> publishedResponse =
                        new AtomicReference<StreamingHttpResponse>();
                CompletionStage<Void> pending;
                try {
                    pending = streamingProcessor.processStreaming(request, response -> {
                        if (response == null || !published.compareAndSet(false, true)) {
                            throw new IllegalStateException(
                                    "streaming processor must publish exactly one response");
                        }
                        publishedResponse.set(response);
                        if (trace != null) trace.event("transport.response-published",
                                "status=" + response.status());
                        beginHttp2StreamingResponse(streamId, response);
                    });
                    if (pending == null) {
                        throw new IllegalStateException(
                                "streaming processor returned a null completion stage");
                    }
                } catch (Exception | LinkageError e) {
                    logApplicationFailure("http2", streamId, e);
                    if (trace != null) trace.finish("http2.application-failed");
                    if (!published.get()) completeHttp2Response(streamId, INTERNAL_ERROR);
                    else publishedResponse.get().body().fail(e);
                    return;
                }
                pending.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        logApplicationFailure("http2", streamId, failure);
                        if (published.get()) publishedResponse.get().body().fail(failure);
                        else completeHttp2Response(streamId, INTERNAL_ERROR);
                    } else if (!published.get()) {
                        logApplicationFailure("http2", streamId,
                                new IllegalStateException("no response published"));
                        completeHttp2Response(streamId, INTERNAL_ERROR);
                    }
                    if (trace != null) trace.finish("http2.application-complete");
                });
            }

            private void beginHttp2StreamingResponse(
                    int streamId,
                    StreamingHttpResponse response) {
                if (response.upgrade() != null) {
                    try {
                        ByteArrayOutputStream body = new ByteArrayOutputStream();
                        byte[] chunk;
                        while ((chunk = response.body().pollChunk()) != null) body.write(chunk);
                        completeHttp2Response(streamId, new HttpResponse(
                                response.status(), response.reason(), response.headers(),
                                body.toByteArray(), response.body().trailers(), response.upgrade()));
                    } catch (IOException e) {
                        completeHttp2Response(streamId, INTERNAL_ERROR);
                    }
                    return;
                }
                logResponse("http2", streamId, response.status());
                if (!shard.submit(() -> {
                    try {
                        http2.respondStreaming(streamId, response, () -> {
                            if (!shard.submit(() -> http2.resumeStreaming(
                                    streamId, http2Output))) {
                                response.body().fail(new IOException(
                                        "HTTP/2 response command queue is full"));
                                requestCloseFromAnyThread();
                            }
                        }, http2Output);
                    } catch (Http2Exception e) {
                        response.body().fail(e);
                        enqueueProtocolOutput(http2.errorFrame(e));
                    } finally {
                        http2Requests.remove(streamId);
                        activeHttp2Requests--;
                    }
                })) {
                    response.body().fail(new IOException("HTTP/2 response command queue is full"));
                    requestCloseFromAnyThread();
                }
            }

            private void beginHttp2Tunnel(int streamId, HttpResponse response)
                    throws Http2Exception {
                final ConnectionUpgrade tunnel = response.upgrade();
                byte[] pending = http2.acceptTunnel(streamId, response, http2Output);
                http2Upgrades.put(streamId, tunnel);
                try {
                    tunnel.onOpen(new UpgradeChannel() {
                    @Override
                    public boolean isOpen() {
                        return !closed && http2Upgrades.get(streamId) == tunnel;
                    }

                    @Override
                    public void write(ByteBuffer data) throws IOException {
                        if (data == null) throw new IllegalArgumentException("tunnel output must not be null");
                        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                        copy.put(data.slice()).flip();
                        if (!shard.submit(() -> {
                            try {
                                http2.sendTunnelData(streamId, copy, http2Output);
                            } catch (IOException e) {
                                ConnectionUpgrade removed = http2Upgrades.remove(streamId);
                                if (removed != null) removed.onClosed();
                            }
                        })) throw new IOException("HTTP/2 tunnel output queue is full");
                    }

                    @Override
                    public CompletionStage<Void> writeAsync(ByteBuffer data) {
                        final CompletableFuture<Void> completion = new CompletableFuture<Void>();
                        if (data == null) {
                            completion.completeExceptionally(new IllegalArgumentException(
                                    "tunnel output must not be null"));
                            return completion;
                        }
                        final ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                        copy.put(data.slice()).flip();
                        if (!shard.submit(() -> http2.sendTunnelDataAsync(
                                streamId, copy, http2Output).whenComplete((ignored, failure) -> {
                                    if (failure == null) completion.complete(null);
                                    else completion.completeExceptionally(failure);
                                }))) {
                            completion.completeExceptionally(new IOException(
                                    "HTTP/2 tunnel output queue is full"));
                        }
                        return completion;
                    }

                    @Override
                    public void close() {
                        if (!shard.submit(() -> {
                            http2.closeTunnel(streamId, http2Output);
                            http2Upgrades.remove(streamId);
                        })) requestCloseFromAnyThread();
                    }
                    });
                } catch (IOException e) {
                    http2Upgrades.remove(streamId);
                    throw new Http2Exception(2, streamId, "extended CONNECT open failed");
                }
                if (pending.length > 0) {
                    try {
                        tunnel.onInput(ByteBuffer.wrap(pending));
                    } catch (IOException e) {
                        http2Upgrades.remove(streamId);
                        tunnel.onClosed();
                        throw new Http2Exception(2, streamId, "extended CONNECT handler failed");
                    }
                }
            }

            private void completeHttp2Response(int streamId, HttpResponse response) {
                logResponse("http2", streamId, response.status());
                if (!shard.submit(() -> {
                    try {
                        if (response.upgrade() != null) {
                            beginHttp2Tunnel(streamId, response);
                        } else {
                            http2.respond(streamId, response, http2Output);
                        }
                    } catch (Http2Exception e) {
                        enqueueProtocolOutput(http2.errorFrame(e));
                        if (e.streamId() == 0) closeAfterWrite = true;
                    } finally {
                        http2Requests.remove(streamId);
                        activeHttp2Requests--;
                        if (activeHttp2Requests == 0 && closeAfterWrite
                                && output == null && protocolOutput.isEmpty()
                                && !http2.hasActiveLocalStreams()) closeOnShard();
                    }
                })) requestCloseFromAnyThread();
            }

            private void receiveUpgraded() {
                if (!input.hasRemaining()) return;
                ByteBuffer bytes = ByteBuffer.allocate(input.remaining());
                bytes.put(input).flip();
                try {
                    upgrade.onInput(bytes);
                } catch (IOException | RuntimeException e) {
                    closeOnShard();
                }
            }

            private void enqueueProtocolOutput(ByteBuffer bytes) {
                enqueueProtocolOutput(bytes, null);
            }

            private void enqueueProtocolOutput(
                    ByteBuffer bytes, CompletableFuture<Void> completion) {
                if (bytes == null || !bytes.hasRemaining()) {
                    if (completion != null) completion.complete(null);
                    return;
                }
                if (closed) {
                    if (completion != null) completion.completeExceptionally(
                            new IOException("connection is closed"));
                    return;
                }
                ByteBuffer copy = ByteBuffer.allocate(bytes.remaining());
                copy.put(bytes.slice()).flip();
                ProtocolWrite write = new ProtocolWrite(copy, completion);
                if (output == null) {
                    output = copy;
                    outputCompletion = completion;
                } else if (protocolOutput.size() < config.commandQueueCapacity()) {
                    protocolOutput.offer(write);
                }
                else {
                    if (completion != null) completion.completeExceptionally(
                            new IOException("upgrade output queue is full"));
                    closeOnShard();
                    return;
                }
                if (key.isValid()) key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }

            private void dispatch(HttpRequest request) {
                // h2c Upgrade 先完成协议切换；普通 HTTP/1 请求再建立诊断与工作任务。
                if (isH2cUpgrade(request)) {
                    beginH2cUpgrade(request);
                    return;
                }
                RequestTrace trace = RequestTrace.begin(diagnosticId, 0, request.method());
                final HttpRequest dispatchedRequest = (trace == null ? request : request.withTrace(trace))
                        .withConnectionInfo(
                        forwardedRequestResolver.resolve(
                                request.headers(), remoteAddress, localAddress,
                                tlsConnectionInfo));
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("connection=" + diagnosticId + " protocol=http1 method="
                            + dispatchedRequest.method() + " dispatched");
                }
                processing = true;
                activeRequest = dispatchedRequest;
                requestsServed++;
                // mailbox 恢复可写时，回到所属分片重新启用 Socket 读兴趣。
                dispatchedRequest.bodyMailbox().onWritable(() -> {
                    if (!shard.submit(this::resumeBodyReadOnShard)) {
                        requestCloseFromAnyThread();
                    }
                });
                final boolean requestKeepAlive = dispatchedRequest.keepAlive()
                        && requestsServed < config.maxRequestsPerConnection();
                boolean accepted = execution.execute(
                        () -> processOnWorker(dispatchedRequest, requestKeepAlive));
                if (!accepted) {
                    if (trace != null) trace.finish("transport.worker-rejected");
                    LOGGER.warning("connection=" + diagnosticId
                            + " protocol=http1 worker queue full");
                    parser.abort(new IOException("request execution was rejected"));
                    sendOnShard(encoder.encode(UNAVAILABLE, false), false);
                }
            }

            private boolean isH2cUpgrade(HttpRequest request) {
                return tlsEngine == null && config.http2Enabled()
                        && "HTTP/1.1".equals(request.version())
                        && containsToken(request.headers().all("connection"), "upgrade")
                        && containsToken(request.headers().all("connection"), "http2-settings")
                        && containsToken(request.headers().all("upgrade"), "h2c")
                        && request.headers().all("http2-settings").size() == 1;
            }

            private void beginH2cUpgrade(HttpRequest request) {
                if (request.bodyMailbox().isComplete()) {
                    beginH2cUpgradeOnShard(request);
                    return;
                }
                processing = true;
                activeRequest = request;
                request.bodyMailbox().onWritable(() -> {
                    if (!shard.submit(this::resumeBodyReadOnShard)) requestCloseFromAnyThread();
                });
                if (!execution.execute(() -> {
                    try {
                        request.body();
                        if (!shard.submit(() -> beginH2cUpgradeOnShard(request))) {
                            requestCloseFromAnyThread();
                        }
                    } catch (RuntimeException e) {
                        if (!shard.submit(() -> sendOnShard(
                                encoder.encode(BAD_REQUEST, false), false))) {
                            requestCloseFromAnyThread();
                        }
                    }
                })) {
                    parser.abort(new IOException("h2c request execution was rejected"));
                    sendOnShard(encoder.encode(UNAVAILABLE, false), false);
                }
            }

            private void beginH2cUpgradeOnShard(HttpRequest request) {
                final byte[] settings;
                try {
                    settings = Base64.getUrlDecoder().decode(
                            request.headers().first("http2-settings"));
                    http2 = new Http2ServerConnection(
                            config.maxHeaderBytes(), config.maxBodyBytes(), 100);
                    http2.applyUpgradeSettings(settings);
                } catch (IllegalArgumentException | Http2Exception e) {
                    sendOnShard(encoder.encode(BAD_REQUEST, false), false);
                    return;
                }
                pendingH2cRequest = request.withConnectionInfo(
                        forwardedRequestResolver.resolve(
                                request.headers(), remoteAddress, localAddress, tlsConnectionInfo));
                ByteBuffer switching = encoder.encode(new HttpResponse(
                        101,
                        "Switching Protocols",
                        io.github.o1o00o10.lingtong.http.HttpHeaders.builder()
                                .add("connection", "Upgrade")
                                .add("upgrade", "h2c")
                                .build(),
                        new byte[0]), true);
                sendOnShard(switching, true);
            }

            private boolean containsToken(List<String> values, String expected) {
                for (String value : values) {
                    for (String token : value.split(",")) {
                        if (expected.equalsIgnoreCase(token.trim())) return true;
                    }
                }
                return false;
            }

            /** 邮箱回调必须排队回所属分片，不得跨线程直接修改 Selector 状态。 */
            private void resumeBodyReadOnShard() {
                if (closed || output != null || parser.isMessageComplete() || !key.isValid()) {
                    return;
                }
                bodyReadPaused = false;
                if (input.position() > 0) {
                    parseBufferedInput();
                } else {
                    updateReadInterest();
                }
            }

            /** 工作线程开始应用处理时，连接与 SelectionKey 仍归 I/O 分片所有。 */
            private void processOnWorker(HttpRequest request, boolean requestKeepAlive) {
                if (request.trace() != null) request.trace().event("worker.enter");
                if (processor instanceof StreamingResponseProcessor) {
                    processStreamingOnWorker(
                            request, requestKeepAlive, (StreamingResponseProcessor) processor);
                    return;
                }
                CompletionStage<HttpResponse> pending;
                try {
                    pending = processor.processAsync(request);
                    if (pending == null) {
                        throw new IllegalStateException("request processor returned a null completion stage");
                    }
                } catch (Exception | LinkageError e) {
                    logApplicationFailure("http1", 0, e);
                    completeResponse(request, INTERNAL_ERROR, false);
                    return;
                }

                pending.whenComplete((response, failure) -> {
                    if (failure != null || response == null) {
                        logApplicationFailure("http1", 0, failure == null
                                ? new IllegalStateException("null response") : failure);
                        completeResponse(request, INTERNAL_ERROR, false);
                        return;
                    }
                    boolean keepAlive = requestKeepAlive && !containsConnectionToken(response, "close");
                    completeResponse(request, response, keepAlive);
                });
            }

            private void processStreamingOnWorker(
                    HttpRequest request,
                    boolean requestKeepAlive,
                    StreamingResponseProcessor streamingProcessor) {
                // 头部发布一次；应用完成后可能仍有 body 块等待 Selector 写出。
                AtomicBoolean published = new AtomicBoolean();
                AtomicReference<StreamingHttpResponse> publishedResponse =
                        new AtomicReference<StreamingHttpResponse>();
                CompletionStage<Void> pending;
                try {
                    pending = streamingProcessor.processStreaming(request, response -> {
                        if (response == null || !published.compareAndSet(false, true)) {
                            throw new IllegalStateException(
                                    "streaming processor must publish exactly one response");
                        }
                        publishedResponse.set(response);
                        beginStreamingResponse(request, response, requestKeepAlive);
                    });
                    if (pending == null) {
                        throw new IllegalStateException(
                                "streaming processor returned a null completion stage");
                    }
                } catch (Exception | LinkageError e) {
                    logApplicationFailure("http1", 0, e);
                    if (!published.get()) {
                        completeResponse(request, INTERNAL_ERROR, false);
                    } else {
                        publishedResponse.get().body().fail(e);
                    }
                    return;
                }
                pending.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        logApplicationFailure("http1", 0, failure);
                        if (published.get()) {
                            publishedResponse.get().body().fail(failure);
                        } else {
                            completeResponse(request, INTERNAL_ERROR, false);
                        }
                    } else if (!published.get()) {
                        logApplicationFailure("http1", 0,
                                new IllegalStateException("no response published"));
                        completeResponse(request, INTERNAL_ERROR, false);
                    }
                });
            }

            private void beginStreamingResponse(
                    HttpRequest request,
                    StreamingHttpResponse response,
                    boolean requestKeepAlive) {
                // 已完成的小响应可走普通编码路径；未完成的 body 保留 mailbox 逐块写。
                if (response.body().isComplete()) {
                    try {
                        ByteArrayOutputStream body = new ByteArrayOutputStream();
                        byte[] chunk;
                        while ((chunk = response.body().pollChunk()) != null) {
                            body.write(chunk, 0, chunk.length);
                        }
                        completeResponse(
                                request,
                                new HttpResponse(
                                        response.status(),
                                        response.reason(),
                                        response.headers(),
                                        body.toByteArray(),
                                        response.body().trailers(),
                                        response.upgrade()),
                                requestKeepAlive
                                        && !containsConnectionToken(response.headers(), "close"));
                    } catch (IOException e) {
                        completeResponse(request, INTERNAL_ERROR, false);
                    }
                    return;
                }
                if (request.trace() != null) request.trace().event(
                        "transport.response-published", "status=" + response.status());
                logResponse("http1", 0, response.status());
                Http1ResponseCompressor.StreamingPlan compression =
                        compressor.prepareStreaming(request, response);
                StreamingHttpResponse wireResponse = compression.response();
                boolean keepAlive = requestKeepAlive
                        && request.bodyMailbox().isComplete()
                        && !containsConnectionToken(wireResponse.headers(), "close")
                        && !draining;
                boolean suppressBody = "HEAD".equals(request.method());
                ByteBuffer head;
                try {
                    head = encoder.encodeStreamingHead(wireResponse, keepAlive, suppressBody);
                } catch (RuntimeException e) {
                    response.body().fail(e);
                    completeResponse(request, INTERNAL_ERROR, false);
                    return;
                }
                final boolean completedKeepAlive = keepAlive;
                final ByteBuffer completedHead = head;
                if (!shard.submit(() -> sendStreamingOnShard(
                        wireResponse,
                        completedHead,
                        completedKeepAlive,
                        suppressBody))) {
                    wireResponse.body().fail(new IOException("response command queue is full"));
                    response.body().fail(new IOException("response command queue is full"));
                    requestCloseFromAnyThread();
                    return;
                }
                if (compression.gzip()) {
                    new GzipResponseBridge(
                            compression.sourceBody(), wireResponse.body()).start();
                }
            }

            /** 在压缩工作域中把原始正文逐块写入有界 GZIP 输出邮箱。 */
            private final class GzipResponseBridge {
                /** Servlet 侧未压缩的正文邮箱。 */
                private final ResponseBodyMailbox source;
                /** 网络侧 GZIP 编码后的正文邮箱。 */
                private final ResponseBodyMailbox target;
                /** 防止重复提交压缩任务。 */
                private final AtomicBoolean scheduled = new AtomicBoolean();
                /** 桥接已经正常完成或失败。 */
                private volatile boolean terminated;
                /** 延续同一 GZIP 流状态的压缩器。 */
                private GZIPOutputStream gzip;

                private GzipResponseBridge(
                        ResponseBodyMailbox source,
                        ResponseBodyMailbox target) {
                    this.source = source;
                    this.target = target;
                }

                private void start() {
                    target.onWritable(() -> {
                        Throwable failure = target.failure();
                        if (failure != null) {
                            source.fail(failure);
                        }
                    });
                    source.onReadable(this::schedule);
                }

                private void schedule() {
                    if (terminated || !scheduled.compareAndSet(false, true)) {
                        return;
                    }
                    if (compressionExecution == null || !compressionExecution.execute(this::pump)) {
                        scheduled.set(false);
                        fail(new IOException("response compression execution was rejected"));
                    }
                }

                private void pump() {
                    try {
                        if (gzip == null) {
                            gzip = new GZIPOutputStream(new MailboxOutputStream(target), 8192, true);
                        }
                        boolean wrote = false;
                        byte[] chunk;
                        while ((chunk = source.pollChunk()) != null) {
                            gzip.write(chunk);
                            wrote = true;
                        }
                        if (wrote) {
                            gzip.flush();
                        }
                        Throwable failure = source.failure();
                        if (failure != null) {
                            throw failure instanceof IOException
                                    ? (IOException) failure
                                    : new IOException("source response body failed", failure);
                        }
                        if (source.isFinished()) {
                            gzip.close();
                            target.complete(source.trailers());
                            terminated = true;
                            source.onReadable(null);
                            target.onWritable(null);
                        }
                    } catch (IOException | RuntimeException e) {
                        fail(e);
                    } finally {
                        scheduled.set(false);
                        if (!terminated && (source.isReadable()
                                || source.isComplete()
                                || source.failure() != null)) {
                            schedule();
                        }
                    }
                }

                private void fail(Throwable failure) {
                    if (terminated) {
                        return;
                    }
                    terminated = true;
                    source.onReadable(null);
                    target.onWritable(null);
                    source.fail(failure);
                    target.fail(failure);
                }
            }

            /** 让 GZIPOutputStream 以阻塞写入方式遵守目标邮箱容量。 */
            private final class MailboxOutputStream extends OutputStream {
                /** 压缩结果的目标邮箱。 */
                private final ResponseBodyMailbox mailbox;

                private MailboxOutputStream(ResponseBodyMailbox mailbox) {
                    this.mailbox = mailbox;
                }

                @Override
                public void write(int value) throws IOException {
                    byte[] single = {(byte) value};
                    write(single, 0, 1);
                }

                @Override
                public void write(byte[] values, int offset, int length) throws IOException {
                    mailbox.writeBlocking(values, offset, length);
                }
            }

            private void completeResponse(HttpRequest request, HttpResponse response, boolean responseKeepAlive) {
                if (request.trace() != null) request.trace().event(
                        "transport.response-published", "status=" + response.status());
                logResponse("http1", 0, response.status());
                ByteBuffer encoded;
                ConnectionUpgrade completedUpgrade = response.upgrade();
                try {
                    responseKeepAlive = responseKeepAlive
                            && request.bodyMailbox().isComplete()
                            && !draining;
                    response = compressor.compress(request, response);
                    encoded = encoder.encode(response, responseKeepAlive, "HEAD".equals(request.method()));
                } catch (RuntimeException e) {
                    logApplicationFailure("http1-encode", 0, e);
                    encoded = encoder.encode(INTERNAL_ERROR, false);
                    responseKeepAlive = false;
                }
                final ByteBuffer completedResponse = encoded;
                final boolean completedKeepAlive = responseKeepAlive;
                if (!shard.submit(() -> sendOnShard(
                        completedResponse, completedKeepAlive, completedUpgrade))) {
                    requestCloseFromAnyThread();
                }
            }

            private void sendOnShard(ByteBuffer response, boolean keepAlive) {
                sendOnShard(response, keepAlive, null);
            }

            private void sendOnShard(
                    ByteBuffer response, boolean keepAlive, ConnectionUpgrade completedUpgrade) {
                // 唯一的 HTTP/1 响应提交点：后续只允许 Selector 写出，不再更改响应。
                if (closed || closeRequested.get() || !key.isValid()) {
                    closeOnShard();
                    return;
                }
                if (responseCommitted) {
                    return;
                }
                responseCommitted = true;
                if (!parser.isMessageComplete()) {
                    parser.abort(new IOException("response completed before request body"));
                    keepAlive = false;
                }
                output = response;
                pendingUpgrade = completedUpgrade;
                responseBody = null;
                closeAfterWrite = !keepAlive || draining;
                processing = true;
                bodyReadPaused = false;
                key.interestOps(SelectionKey.OP_WRITE);
            }

            private void sendStreamingOnShard(
                    StreamingHttpResponse response,
                    ByteBuffer head,
                    boolean keepAlive,
                    boolean suppressBody) {
                if (closed || closeRequested.get() || !key.isValid()) {
                    response.body().fail(new IOException("connection closed before response started"));
                    closeOnShard();
                    return;
                }
                if (responseCommitted) {
                    response.body().fail(new IOException("response has already been committed"));
                    return;
                }
                responseCommitted = true;
                if (!parser.isMessageComplete()) {
                    parser.abort(new IOException("response completed before request body"));
                    keepAlive = false;
                }
                responseBody = response.body();
                responseChunked = encoder.usesChunkedEncoding(response.status(), suppressBody);
                responseBodySuppressed = !responseChunked;
                streamingFinalWrite = false;
                output = head;
                closeAfterWrite = !keepAlive || draining;
                processing = true;
                bodyReadPaused = false;
                // body 到达不直接跨线程写 Socket，只通知分片恢复 OP_WRITE。
                responseBody.onReadable(() -> {
                    if (!shard.submit(this::resumeResponseWriteOnShard)) {
                        requestCloseFromAnyThread();
                    }
                });
                key.interestOps(SelectionKey.OP_WRITE);
            }

            private void resumeResponseWriteOnShard() {
                if (closed || responseBody == null || output != null || !key.isValid()) {
                    return;
                }
                key.interestOps(SelectionKey.OP_WRITE);
            }

            private void beginDrainOnShard() {
                if (closed) {
                    return;
                }
                if (protocol == Protocol.HTTP2 && http2 != null && !http2.isClosed()) {
                    enqueueProtocolOutput(http2.gracefulGoAway());
                }
                closeAfterWrite = true;
                if (!processing && output == null && protocolOutput.isEmpty()
                        && (protocol != Protocol.HTTP2 || activeHttp2Requests == 0
                        && !http2.hasActiveLocalStreams())) {
                    closeOnShard();
                }
            }

            private void onWritable() throws IOException {
                if (tlsEngine != null) {
                    onTlsWritable();
                    return;
                }
                if (output == null) {
                    if (protocol == Protocol.HTTP2 || protocol == Protocol.UPGRADED) {
                        finishProtocolWrite();
                        return;
                    }
                    if (responseBody != null) {
                        advanceStreamingOutput();
                    } else {
                        closeOnShard();
                    }
                    return;
                }
                channel.write(output);
                lastActivityNanos = System.nanoTime();
                if (output.hasRemaining()) {
                    return;
                }

                completeOutput(null);
                output = null;
                if (protocol == Protocol.HTTP2 || protocol == Protocol.UPGRADED) {
                    finishProtocolWrite();
                    return;
                }
                if (responseBody != null) {
                    if (streamingFinalWrite) {
                        finishResponseOnShard();
                    } else {
                        advanceStreamingOutput();
                    }
                    return;
                }
                finishResponseOnShard();
            }

            private void onTlsWritable() throws IOException {
                while (!closed) {
                    if (tlsOutput.hasRemaining()) {
                        flushTlsOutput();
                        if (tlsOutput.hasRemaining()) {
                            key.interestOps(SelectionKey.OP_WRITE);
                            return;
                        }
                        completeTlsOutput(null);
                    }
                    if (tlsTaskRunning) {
                        key.interestOps(0);
                        return;
                    }
                    if (tlsClosing) {
                        if (!tlsEngine.isOutboundDone()) {
                            wrapTls(ByteBuffer.allocate(0));
                            continue;
                        }
                        closeOnShard();
                        return;
                    }
                    if (!tlsHandshakeComplete) {
                        driveTlsHandshake();
                        if (!tlsHandshakeComplete || tlsOutput.hasRemaining()
                                || tlsTaskRunning) {
                            return;
                        }
                    }
                    if (output == null) {
                        if (protocol == Protocol.HTTP2 || protocol == Protocol.UPGRADED) {
                            finishProtocolWrite();
                            return;
                        }
                        if (responseBody != null) {
                            advanceStreamingOutput();
                            if (output == null) {
                                return;
                            }
                        } else {
                            finishResponseOnShard();
                            return;
                        }
                    }
                    wrapTls(output);
                    if (!output.hasRemaining()) {
                        CompletableFuture<Void> completed = outputCompletion;
                        outputCompletion = null;
                        if (completed != null) {
                            if (tlsOutput.hasRemaining()) tlsOutputCompletion = completed;
                            else completed.complete(null);
                        }
                        output = null;
                        if (protocol == Protocol.HTTP2 || protocol == Protocol.UPGRADED) {
                            if (tlsOutput.hasRemaining()) {
                                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                                return;
                            }
                            finishProtocolWrite();
                            return;
                        }
                    }
                    SSLEngineResult.HandshakeStatus status = tlsEngine.getHandshakeStatus();
                    if (status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                            && status != SSLEngineResult.HandshakeStatus.FINISHED) {
                        tlsHandshakeComplete = false;
                    }
                }
            }

            private void finishProtocolWrite() {
                ProtocolWrite next = protocolOutput.poll();
                output = next == null ? null : next.data;
                outputCompletion = next == null ? null : next.completion;
                if (next != null) {
                    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                } else if (closeAfterWrite) {
                    if (protocol != Protocol.HTTP2 || activeHttp2Requests == 0
                            && !http2.hasActiveLocalStreams()) {
                        closeOnShard();
                    } else {
                        key.interestOps(0);
                    }
                } else {
                    key.interestOps(SelectionKey.OP_READ);
                }
            }

            private void advanceStreamingOutput() throws IOException {
                // 一次取一块；暂无数据时取消写兴趣，结束时编码最后一块及 trailers。
                while (responseBody != null) {
                    byte[] chunk = responseBody.pollChunk();
                    if (chunk != null) {
                        if (responseBodySuppressed) {
                            continue;
                        }
                        output = encoder.encodeChunk(chunk);
                        key.interestOps(SelectionKey.OP_WRITE);
                        return;
                    }
                    if (responseBody.failure() != null) {
                        closeOnShard();
                        return;
                    }
                    if (responseBody.isFinished()) {
                        if (responseChunked) {
                            streamingFinalWrite = true;
                            output = encoder.encodeLastChunk(responseBody.trailers());
                            key.interestOps(SelectionKey.OP_WRITE);
                        } else {
                            finishResponseOnShard();
                        }
                        return;
                    }
                    key.interestOps(0);
                    return;
                }
            }

            private void finishResponseOnShard() throws IOException {
                // 写尽后的分叉：h2c/WebSocket 切协议，否则复用连接或关闭。
                detachResponseBody();
                if (activeRequest != null && activeRequest.trace() != null) {
                    activeRequest.trace().finish("transport.http1-output-complete");
                }
                if (pendingH2cRequest != null) {
                    protocol = Protocol.HTTP2_PREFACE;
                    processing = false;
                    responseCommitted = false;
                    closeAfterWrite = false;
                    parser.abort(new IOException("HTTP connection upgraded to h2c"));
                    if (activeRequest != null) {
                        activeRequest.bodyMailbox().onWritable(null);
                        activeRequest = null;
                    }
                    key.interestOps(SelectionKey.OP_READ);
                    if (input.position() > 0) parseBufferedInput();
                    return;
                }
                if (pendingUpgrade != null) {
                    upgrade = pendingUpgrade;
                    pendingUpgrade = null;
                    protocol = Protocol.UPGRADED;
                    processing = false;
                    responseCommitted = false;
                    closeAfterWrite = false;
                    parser.abort(new IOException("HTTP connection upgraded"));
                    upgrade.onOpen(new UpgradeChannel() {
                        @Override
                        public boolean isOpen() {
                            return !closed && protocol == Protocol.UPGRADED;
                        }

                        @Override
                        public void write(ByteBuffer data) throws IOException {
                            if (data == null) {
                                throw new IllegalArgumentException("upgrade output must not be null");
                            }
                            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                            copy.put(data.slice()).flip();
                            if (!shard.submit(() -> enqueueProtocolOutput(copy))) {
                                throw new IOException("upgrade output queue is full");
                            }
                        }

                        @Override
                        public CompletionStage<Void> writeAsync(ByteBuffer data) {
                            CompletableFuture<Void> completion = new CompletableFuture<Void>();
                            if (data == null) {
                                completion.completeExceptionally(new IllegalArgumentException(
                                        "upgrade output must not be null"));
                                return completion;
                            }
                            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                            copy.put(data.slice()).flip();
                            if (!shard.submit(() -> enqueueProtocolOutput(copy, completion))) {
                                completion.completeExceptionally(new IOException(
                                        "upgrade output queue is full"));
                            }
                            return completion;
                        }

                        @Override
                        public void close() {
                            if (!shard.submit(() -> {
                                closeAfterWrite = true;
                                if (output == null && protocolOutput.isEmpty()) closeOnShard();
                            })) requestCloseFromAnyThread();
                        }
                    });
                    key.interestOps(SelectionKey.OP_READ);
                    if (input.position() > 0) parseBufferedInput();
                    return;
                }
                if (closeAfterWrite) {
                    if (tlsEngine != null && !tlsClosing) {
                        tlsClosing = true;
                        tlsEngine.closeOutbound();
                        key.interestOps(SelectionKey.OP_WRITE);
                        return;
                    }
                    closeOnShard();
                    return;
                }
                closeAfterWrite = false;
                processing = false;
                responseCommitted = false;
                if (activeRequest != null) {
                    activeRequest.bodyMailbox().onWritable(null);
                    activeRequest = null;
                }
                parser.reset();
                key.interestOps(SelectionKey.OP_READ);
                if (input.position() > 0) {
                    requestStarted = true;
                    requestStartedNanos = System.nanoTime();
                    parseBufferedInput();
                }
                if (tlsEngine != null && tlsInput.position() > 0
                        && !responseCommitted
                        && (!processing || !parser.isMessageComplete())) {
                    unwrapTlsApplicationData();
                }
            }

            private void detachResponseBody() {
                if (responseBody != null) {
                    responseBody.onReadable(null);
                    responseBody = null;
                }
                responseChunked = false;
                responseBodySuppressed = false;
                streamingFinalWrite = false;
            }

            private void onTimer(long now) {
                // 不同协议各管自己的空闲状态；流式响应超时需要同时失败 body。
                if (closed) {
                    return;
                }
                if (tlsEngine != null && !tlsHandshakeComplete
                        && elapsedMillis(connectedNanos, now)
                        >= config.tls().handshakeTimeoutMillis()) {
                    LOGGER.warning("connection=" + diagnosticId + " TLS handshake timed out");
                    closeOnShard();
                    return;
                }
                if (closed) {
                    return;
                }
                if (protocol == Protocol.UPGRADED) {
                    upgrade.onTimer(now);
                    if (!closed && !upgrade.managesIdleTimeout()
                            && elapsedMillis(lastActivityNanos, now)
                            >= config.idleTimeoutMillis()) closeOnShard();
                    return;
                }
                if (protocol == Protocol.HTTP2) {
                    for (ConnectionUpgrade current :
                            new ArrayList<ConnectionUpgrade>(http2Upgrades.values())) {
                        current.onTimer(now);
                    }
                    if (!closed && http2Upgrades.isEmpty()
                            && elapsedMillis(lastActivityNanos, now)
                            >= config.idleTimeoutMillis()) closeOnShard();
                    return;
                }
                if (output != null) {
                    return;
                }
                if (responseBody != null) {
                    if (elapsedMillis(lastActivityNanos, now) >= config.idleTimeoutMillis()) {
                        LOGGER.warning("connection=" + diagnosticId
                                + " streaming response timed out");
                        responseBody.fail(new IOException("streaming response timed out"));
                        closeOnShard();
                    }
                    return;
                }
                if (requestStarted && parser.isReadingHeaders()
                        && elapsedMillis(requestStartedNanos, now) >= config.requestHeaderTimeoutMillis()) {
                    LOGGER.warning("connection=" + diagnosticId + " request headers timed out");
                    sendOnShard(encoder.encode(REQUEST_TIMEOUT, false), false);
                } else if (requestStarted && !parser.isMessageComplete()
                        && elapsedMillis(lastActivityNanos, now) >= config.idleTimeoutMillis()) {
                    LOGGER.warning("connection=" + diagnosticId + " request body timed out");
                    parser.abort(new IOException("request body timed out"));
                    sendOnShard(encoder.encode(REQUEST_TIMEOUT, false), false);
                } else if (elapsedMillis(lastActivityNanos, now) >= config.idleTimeoutMillis()) {
                    if (requestStarted && !processing) {
                        sendOnShard(encoder.encode(REQUEST_TIMEOUT, false), false);
                    } else if (!processing) {
                        closeOnShard();
                    }
                }
            }

            private void updateReadInterest() {
                // 所有读写兴趣的变更都在所属分片执行，避免跨线程碰 SelectionKey。
                if (closed || !key.isValid() || responseCommitted) {
                    return;
                }
                if (tlsEngine != null && !tlsHandshakeComplete) {
                    if (tlsTaskRunning) {
                        key.interestOps(tlsOutput.hasRemaining() ? SelectionKey.OP_WRITE : 0);
                    } else {
                        SSLEngineResult.HandshakeStatus status = tlsEngine.getHandshakeStatus();
                        key.interestOps(tlsOutput.hasRemaining()
                                || status == SSLEngineResult.HandshakeStatus.NEED_WRAP
                                ? SelectionKey.OP_WRITE : SelectionKey.OP_READ);
                    }
                    return;
                }
                if (protocol == Protocol.HTTP2 || protocol == Protocol.UPGRADED) {
                    int operations = SelectionKey.OP_READ;
                    if (output != null || !protocolOutput.isEmpty()) operations |= SelectionKey.OP_WRITE;
                    key.interestOps(operations);
                    return;
                }
                boolean readEnabled = !bodyReadPaused
                        && (!processing || !parser.isMessageComplete());
                int operations = key.interestOps();
                if (readEnabled) {
                    operations |= SelectionKey.OP_READ;
                } else {
                    operations &= ~SelectionKey.OP_READ;
                }
                key.interestOps(operations);
            }

            private boolean containsConnectionToken(HttpResponse response, String expected) {
                return containsConnectionToken(response.headers(), expected);
            }

            private boolean containsConnectionToken(
                    io.github.o1o00o10.lingtong.http.HttpHeaders headers,
                    String expected) {
                for (String value : headers.all("connection")) {
                    for (String token : value.split(",")) {
                        if (expected.equalsIgnoreCase(token.trim())) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private long elapsedMillis(long start, long end) {
                return (end - start) / 1_000_000L;
            }

            private void logApplicationFailure(String protocolName, int streamId, Throwable failure) {
                LOGGER.severe("connection=" + diagnosticId + " protocol=" + protocolName
                        + (streamId == 0 ? "" : " stream=" + streamId)
                        + " application failed " + failureLocation(failure));
            }

            private void logResponse(String protocolName, int streamId, int status) {
                if (status < 500 && !LOGGER.isLoggable(Level.FINE)) return;
                String message = "connection=" + diagnosticId + " protocol=" + protocolName
                        + (streamId == 0 ? "" : " stream=" + streamId)
                        + " response status=" + status;
                LOGGER.log(status >= 500 ? Level.WARNING : Level.FINE, message);
            }

            private void requestCloseFromAnyThread() {
                closeRequested.set(true);
                closeQuietly(channel);
                selector.wakeup();
            }

            private void closeOnShard() {
                // 统一失败并清理待发送数据、升级回调、mailbox 和缓冲区，最后扣连接计数。
                if (closed) {
                    return;
                }
                closed = true;
                if (activeRequest != null && activeRequest.trace() != null) {
                    activeRequest.trace().finish("transport.connection-closed");
                }
                for (HttpRequest request : http2Requests.values()) {
                    if (request.trace() != null) {
                        request.trace().finish("transport.connection-closed");
                    }
                }
                IOException closedFailure = new IOException(
                        "connection closed before upgrade output completed");
                completeOutput(closedFailure);
                completeTlsOutput(closedFailure);
                ProtocolWrite pendingWrite;
                while ((pendingWrite = protocolOutput.poll()) != null) {
                    if (pendingWrite.completion != null) {
                        pendingWrite.completion.completeExceptionally(closedFailure);
                    }
                }
                if (upgrade != null) {
                    try {
                        upgrade.onClosed();
                    } catch (RuntimeException ignored) {
                        // 升级协议回调失败不能阻止传输资源清理。
                    }
                }
                for (ConnectionUpgrade current : http2Upgrades.values()) {
                    try {
                        current.onClosed();
                    } catch (RuntimeException ignored) {
                        // 单流升级回调失败不能阻止连接清理。
                    }
                }
                http2Upgrades.clear();
                if (activeRequest != null) {
                    activeRequest.bodyMailbox().onWritable(null);
                }
                if (responseBody != null) {
                    responseBody.fail(new IOException("connection closed before response completed"));
                    detachResponseBody();
                }
                parser.abort(new IOException("connection closed before request body completed"));
                if (tlsEngine != null) {
                    tlsEngine.closeOutbound();
                }
                key.cancel();
                closeQuietly(channel);
                buffers.releaseFromIo(block);
                connectionClosed();
            }

            private void completeOutput(Throwable failure) {
                CompletableFuture<Void> completion = outputCompletion;
                outputCompletion = null;
                if (completion == null) return;
                if (failure == null) completion.complete(null);
                else completion.completeExceptionally(failure);
            }

            private void completeTlsOutput(Throwable failure) {
                CompletableFuture<Void> completion = tlsOutputCompletion;
                tlsOutputCompletion = null;
                if (completion == null) return;
                if (failure == null) completion.complete(null);
                else completion.completeExceptionally(failure);
            }

            /** 一个待写协议帧及其可选完成通知。 */
            private final class ProtocolWrite {
                /** 尚待写出的帧字节。 */
                private final ByteBuffer data;
                /** 帧写出后的完成通知。 */
                private final CompletableFuture<Void> completion;

                private ProtocolWrite(ByteBuffer data, CompletableFuture<Void> completion) {
                    this.data = data;
                    this.completion = completion;
                }
            }
        }
    }
}
