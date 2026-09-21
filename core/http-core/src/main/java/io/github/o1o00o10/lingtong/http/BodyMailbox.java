/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/** 请求体有界邮箱：网络线程生产，应用线程读取；满时仅暂停对应连接。 */
public final class BodyMailbox {
    /** 内部块的最大分配长度，单位字节。 */
    private static final int CHUNK_BYTES = 8192;
    /** 未完成或无 Trailer 时共享的空 Header 集。 */
    private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.builder().build();

    /** 保护块队列、计数与结束状态的监视器。 */
    private final Object monitor = new Object();
    /** 尚未被应用读取的正文块。 */
    private final ArrayDeque<Chunk> chunks = new ArrayDeque<Chunk>();
    /** 邮箱内可缓存的最多字节数。 */
    private final int capacityBytes;
    /** 消费到该水位时通知网络层恢复读取。 */
    private final int lowWaterBytes;
    /** 单次请求允许接收的总字节数。 */
    private final int maxBodyBytes;

    /** 当前队首块已经读取的偏移量。 */
    private int headOffset;
    /** 仍在邮箱中等待消费的字节数。 */
    private int bufferedBytes;
    /** 已进入邮箱的请求体总字节数。 */
    private int receivedBytes;
    /** 生产者已写完正文和 Trailer；不表示应用已读完。 */
    private boolean complete;
    /** 生产过程中的终止异常。 */
    private Throwable failure;
    /** 完成后才对外可见的 Trailer。 */
    private HttpHeaders trailers = EMPTY_HEADERS;
    /** 已完成正文的零额外聚合视图。 */
    private byte[] completedBodyView;
    /** 空邮箱变为可读或结束时通知消费者。 */
    private Runnable readableSignal;
    /** 消费跨过低水位时通知生产者。 */
    private Runnable writableSignal;

    public BodyMailbox(int capacityBytes, int lowWaterBytes, int maxBodyBytes) {
        if (capacityBytes <= 0 || lowWaterBytes < 0 || lowWaterBytes >= capacityBytes
                || maxBodyBytes < 0) {
            throw new IllegalArgumentException("invalid body mailbox limits");
        }
        this.capacityBytes = capacityBytes;
        this.lowWaterBytes = lowWaterBytes;
        this.maxBodyBytes = maxBodyBytes;
    }

    /** 构造已经完成且可立即读取的请求体。 */
    public static BodyMailbox completed(byte[] body, HttpHeaders trailers) {
        byte[] value = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        BodyMailbox mailbox = new BodyMailbox(Math.max(1, value.length), 0, value.length);
        if (value.length > 0) {
            mailbox.chunks.add(new Chunk(value, value.length));
            mailbox.bufferedBytes = value.length;
            mailbox.receivedBytes = value.length;
        }
        mailbox.complete = true;
        mailbox.trailers = trailers == null ? EMPTY_HEADERS : trailers;
        mailbox.completedBodyView = value;
        return mailbox;
    }

    byte[] completedBodyView() {
        return completedBodyView;
    }

    /** 只接纳剩余容量能容纳的前缀，未消费的 ByteBuffer 字节留给调用方。 */
    public int offer(ByteBuffer source) {
        if (source == null) {
            throw new IllegalArgumentException("body source must not be null");
        }
        Runnable signal = null;
        int accepted;
        synchronized (monitor) {
            requireProducerOpen();
            if (source.hasRemaining() && receivedBytes == maxBodyBytes) {
                throw new IllegalStateException("request body exceeds configured limit");
            }
            accepted = Math.min(source.remaining(), capacityBytes - bufferedBytes);
            accepted = Math.min(accepted, maxBodyBytes - receivedBytes);
            if (accepted == 0) {
                return 0;
            }
            boolean wasReadable = bufferedBytes > 0;
            append(source, accepted);
            bufferedBytes += accepted;
            receivedBytes += accepted;
            monitor.notifyAll();
            if (!wasReadable) {
                signal = readableSignal;
            }
        }
        runSignal(signal);
        return accepted;
    }

    public void complete(HttpHeaders completedTrailers) {
        Runnable signal;
        synchronized (monitor) {
            requireProducerOpen();
            complete = true;
            trailers = completedTrailers == null ? EMPTY_HEADERS : completedTrailers;
            monitor.notifyAll();
            signal = readableSignal;
        }
        runSignal(signal);
    }

    public void fail(Throwable cause) {
        if (cause == null) {
            throw new IllegalArgumentException("body failure must not be null");
        }
        Runnable signal;
        synchronized (monitor) {
            if (complete || failure != null) {
                return;
            }
            failure = cause;
            monitor.notifyAll();
            signal = readableSignal;
        }
        runSignal(signal);
    }

    public int readAvailable(byte[] target, int offset, int length) throws IOException {
        requireRange(target, offset, length);
        Runnable signal = null;
        int count;
        synchronized (monitor) {
            if (length == 0) {
                return 0;
            }
            if (bufferedBytes == 0) {
                throwIfFailed();
                return complete ? -1 : 0;
            }
            boolean aboveLowWater = bufferedBytes > lowWaterBytes;
            count = copyAvailable(target, offset, length);
            if (aboveLowWater && bufferedBytes <= lowWaterBytes) {
                signal = writableSignal;
            }
        }
        runSignal(signal);
        return count;
    }

    /** 阻塞等到有数据、正常结束或失败。 */
    public int readBlocking(byte[] target, int offset, int length) throws IOException {
        requireRange(target, offset, length);
        if (length == 0) {
            return 0;
        }
        synchronized (monitor) {
            while (bufferedBytes == 0 && !complete && failure == null) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading request body", e);
                }
            }
        }
        return readAvailable(target, offset, length);
    }

    public byte[] readAllBlocking() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[Math.min(8192, Math.max(1, maxBodyBytes))];
        int count;
        while ((count = readBlocking(chunk, 0, chunk.length)) >= 0) {
            output.write(chunk, 0, count);
        }
        return output.toByteArray();
    }

    public boolean isReadable() {
        synchronized (monitor) {
            return bufferedBytes > 0;
        }
    }

    public boolean isFinished() {
        synchronized (monitor) {
            return complete && bufferedBytes == 0;
        }
    }

    public boolean isComplete() {
        synchronized (monitor) {
            return complete;
        }
    }

    public Throwable failure() {
        synchronized (monitor) {
            return failure;
        }
    }

    public int bufferedBytes() {
        synchronized (monitor) {
            return bufferedBytes;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacityBytes - bufferedBytes;
        }
    }

    public HttpHeaders trailers() {
        synchronized (monitor) {
            return complete ? trailers : EMPTY_HEADERS;
        }
    }

    public void onReadable(Runnable signal) {
        boolean notify;
        synchronized (monitor) {
            readableSignal = signal;
            notify = signal != null && (bufferedBytes > 0 || complete || failure != null);
        }
        if (notify) {
            runSignal(signal);
        }
    }

    /** 消费跨过低水位后唤醒网络层，回调不得直接修改 Selector 状态。 */
    public void onWritable(Runnable signal) {
        synchronized (monitor) {
            writableSignal = signal;
        }
    }

    private int copyAvailable(byte[] target, int offset, int length) {
        int copied = 0;
        while (copied < length && bufferedBytes > 0) {
            Chunk head = chunks.peekFirst();
            int count = Math.min(length - copied, head.length - headOffset);
            System.arraycopy(head.values, headOffset, target, offset + copied, count);
            copied += count;
            headOffset += count;
            bufferedBytes -= count;
            if (headOffset == head.length) {
                chunks.removeFirst();
                headOffset = 0;
            }
        }
        return copied;
    }

    private void append(ByteBuffer source, int length) {
        int remaining = length;
        while (remaining > 0) {
            Chunk tail = chunks.peekLast();
            if (tail == null || tail.length == tail.values.length) {
                tail = new Chunk(Math.min(CHUNK_BYTES, capacityBytes));
                chunks.addLast(tail);
            }
            int count = Math.min(remaining, tail.values.length - tail.length);
            source.get(tail.values, tail.length, count);
            tail.length += count;
            remaining -= count;
        }
    }

    private void throwIfFailed() throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException) {
            throw (IOException) failure;
        }
        throw new IOException("request body failed", failure);
    }

    private void requireProducerOpen() {
        if (complete || failure != null) {
            throw new IllegalStateException("request body mailbox is already terminated");
        }
    }

    private static void requireRange(byte[] target, int offset, int length) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (offset < 0 || length < 0 || offset > target.length - length) {
            throw new IndexOutOfBoundsException();
        }
    }

    private static void runSignal(Runnable signal) {
        if (signal != null) {
            signal.run();
        }
    }

    /** 邮箱队列中的单个请求体块及其消费位置。 */
    private static final class Chunk {
        /** 块内字节存储。 */
        private final byte[] values;
        /** 块内已经填入的字节数。 */
        private int length;

        private Chunk(int capacity) {
            values = new byte[capacity];
        }

        private Chunk(byte[] values, int length) {
            this.values = values;
            this.length = length;
        }
    }
}
