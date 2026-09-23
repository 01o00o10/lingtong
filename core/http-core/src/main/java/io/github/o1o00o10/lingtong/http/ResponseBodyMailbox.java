/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/** 响应体有界邮箱：应用线程生产，网络线程消费。生产完成不等于字节已写到 Socket。 */
public final class ResponseBodyMailbox {
  /** 单个内部块的最大长度，单位字节。 */
  private static final int CHUNK_BYTES = 8192;
  /** 无 Trailer 时共享的空 Header 集。 */
  private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.builder().build();

  /** 保护块队列、计数与结束状态的监视器。 */
  private final Object monitor = new Object();
  /** 等待网络线程写出的正文块。 */
  private final ArrayDeque<byte[]> chunks = new ArrayDeque<byte[]>();
  /** 邮箱可缓存的最多字节数。 */
  private final int capacityBytes;
  /** 消费到该水位时唤醒暂停的应用写入。 */
  private final int lowWaterBytes;
  /** 单次响应允许生产的正文总量。 */
  private final long maxBodyBytes;

  /** 目前仍等待网络写出的字节数。 */
  private int bufferedBytes;
  /** 已接纳到邮箱的累计字节数，不是已发送字节数。 */
  private long writtenBytes;
  /** 生产者已经结束；邮箱可能仍有未发送的块。 */
  private boolean complete;
  /** 生产或消费失败时记录的原因。 */
  private Throwable failure;
  /** 正文完成时提供的响应 Trailer。 */
  private HttpHeaders trailers = EMPTY_HEADERS;
  /** 从空邮箱进入可读状态时通知网络线程。 */
  private Runnable readableSignal;
  /** 出现可写空间或失败时通知应用线程。 */
  private Runnable writableSignal;

  public ResponseBodyMailbox(int capacityBytes, int lowWaterBytes, long maxBodyBytes) {
    if (capacityBytes <= 0
        || lowWaterBytes < 0
        || lowWaterBytes >= capacityBytes
        || maxBodyBytes <= 0L) {
      throw new IllegalArgumentException("invalid response body mailbox limits");
    }
    this.capacityBytes = capacityBytes;
    this.lowWaterBytes = lowWaterBytes;
    this.maxBodyBytes = maxBodyBytes;
  }

  /** 只接纳当前容量能容纳的前缀；非阻塞生产者可在可写回调后重试。 */
  public int offer(byte[] source, int offset, int length) throws IOException {
    requireRange(source, offset, length);
    Runnable signal = null;
    int accepted;
    synchronized (monitor) {
      throwIfFailed();
      requireProducerOpen();
      requireWithinLimit(length);
      accepted = Math.min(length, capacityBytes - bufferedBytes);
      if (accepted == 0) {
        return 0;
      }
      boolean wasEmpty = bufferedBytes == 0;
      append(source, offset, accepted);
      bufferedBytes += accepted;
      writtenBytes += accepted;
      monitor.notifyAll();
      if (wasEmpty) {
        signal = readableSignal;
      }
    }
    runSignal(signal);
    return accepted;
  }

  public void writeBlocking(byte[] source, int offset, int length) throws IOException {
    requireRange(source, offset, length);
    int position = offset;
    int remaining = length;
    while (remaining > 0) {
      synchronized (monitor) {
        while (bufferedBytes == capacityBytes && !complete && failure == null) {
          try {
            monitor.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while writing response body", e);
          }
        }
      }
      int accepted = offer(source, position, remaining);
      position += accepted;
      remaining -= accepted;
    }
  }

  /** 取走一个块；跨过低水位时唤醒暂停的响应生产者。 */
  public byte[] pollChunk() throws IOException {
    Runnable signal = null;
    byte[] chunk;
    synchronized (monitor) {
      if (chunks.isEmpty()) {
        throwIfFailed();
        return null;
      }
      boolean aboveLowWater = bufferedBytes > lowWaterBytes;
      chunk = chunks.removeFirst();
      bufferedBytes -= chunk.length;
      monitor.notifyAll();
      if (aboveLowWater && bufferedBytes <= lowWaterBytes) {
        signal = writableSignal;
      }
    }
    runSignal(signal);
    return chunk;
  }

  public void complete() {
    complete(EMPTY_HEADERS);
  }

  public void complete(HttpHeaders completedTrailers) {
    Runnable signal;
    synchronized (monitor) {
      if (complete || failure != null) {
        return;
      }
      complete = true;
      trailers = completedTrailers == null ? EMPTY_HEADERS : completedTrailers;
      monitor.notifyAll();
      signal = readableSignal;
    }
    runSignal(signal);
  }

  public void fail(Throwable cause) {
    if (cause == null) {
      throw new IllegalArgumentException("response body failure must not be null");
    }
    Runnable readable;
    Runnable writable;
    synchronized (monitor) {
      if (complete || failure != null) {
        return;
      }
      failure = cause;
      monitor.notifyAll();
      readable = readableSignal;
      writable = writableSignal;
    }
    runSignal(readable);
    if (writable != readable) {
      runSignal(writable);
    }
  }

  public boolean isWritable() {
    synchronized (monitor) {
      return !complete && failure == null && bufferedBytes < capacityBytes;
    }
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

  public HttpHeaders trailers() {
    synchronized (monitor) {
      return complete ? trailers : EMPTY_HEADERS;
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

  public long writtenBytes() {
    synchronized (monitor) {
      return writtenBytes;
    }
  }

  public int capacityBytes() {
    return capacityBytes;
  }

  public int lowWaterBytes() {
    return lowWaterBytes;
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

  public void onWritable(Runnable signal) {
    boolean notify;
    synchronized (monitor) {
      writableSignal = signal;
      notify = signal != null && (bufferedBytes < capacityBytes || failure != null);
    }
    if (notify) {
      runSignal(signal);
    }
  }

  private void append(byte[] source, int offset, int length) {
    int position = offset;
    int remaining = length;
    while (remaining > 0) {
      int count = Math.min(remaining, Math.min(CHUNK_BYTES, capacityBytes));
      chunks.addLast(Arrays.copyOfRange(source, position, position + count));
      position += count;
      remaining -= count;
    }
  }

  private void requireWithinLimit(int length) throws IOException {
    if (length < 0 || writtenBytes > maxBodyBytes - length) {
      throw new IOException("response body exceeds configured limit of " + maxBodyBytes + " bytes");
    }
  }

  private void throwIfFailed() throws IOException {
    if (failure == null) {
      return;
    }
    if (failure instanceof IOException) {
      throw (IOException) failure;
    }
    throw new IOException("response body failed", failure);
  }

  private void requireProducerOpen() {
    if (complete) {
      throw new IllegalStateException("response body mailbox is already complete");
    }
  }

  private static void requireRange(byte[] source, int offset, int length) {
    if (source == null) {
      throw new NullPointerException("source");
    }
    if (offset < 0 || length < 0 || offset > source.length - length) {
      throw new IndexOutOfBoundsException();
    }
  }

  private static void runSignal(Runnable signal) {
    if (signal != null) {
      signal.run();
    }
  }
}
