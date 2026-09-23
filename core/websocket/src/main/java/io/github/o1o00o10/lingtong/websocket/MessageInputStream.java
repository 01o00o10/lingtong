/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;

/** A single-message bridge from the I/O owner to a blocking endpoint callback. */
/** 将逐块到达的 WebSocket 消息暴露为可顺序读取的 InputStream。 */
final class MessageInputStream extends InputStream {
  /** chunks。 */
  /** 数据块集合（chunks）。 */
  private final ArrayDeque<byte[]> chunks = new ArrayDeque<byte[]>();
  /** 当前。 */
  private byte[] current;
  /** 偏移量。 */
  private int offset;
  /** finished，布尔标志。 */
  private boolean finished;
  /** 已关闭标志，布尔标志。 */
  private boolean closed;
  /** 失败。 */
  private IOException failure;

  synchronized void offer(byte[] value, int length) {
    if (closed || length == 0) return;
    byte[] copy = new byte[length];
    System.arraycopy(value, 0, copy, 0, length);
    chunks.offer(copy);
    notifyAll();
  }

  synchronized void finish() {
    finished = true;
    notifyAll();
  }

  synchronized void fail(IOException value) {
    if (finished || failure != null) return;
    failure = value;
    notifyAll();
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int count = read(one, 0, 1);
    return count < 0 ? -1 : one[0] & 0xff;
  }

  @Override
  public synchronized int read(byte[] target, int targetOffset, int length) throws IOException {
    if (target == null) throw new NullPointerException("target");
    if (targetOffset < 0 || length < 0 || length > target.length - targetOffset) {
      throw new IndexOutOfBoundsException();
    }
    if (length == 0) return 0;
    while (!closed && current == null && chunks.isEmpty() && failure == null && !finished) {
      try {
        wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while reading WebSocket message", e);
      }
    }
    if (closed) throw new IOException("WebSocket message stream is closed");
    if (failure != null) throw failure;
    if (current == null) current = chunks.poll();
    if (current == null) return -1;
    int count = Math.min(length, current.length - offset);
    System.arraycopy(current, offset, target, targetOffset, count);
    offset += count;
    if (offset == current.length) {
      current = null;
      offset = 0;
    }
    return count;
  }

  @Override
  public synchronized int available() {
    int result = current == null ? 0 : current.length - offset;
    for (byte[] chunk : chunks) result += chunk.length;
    return result;
  }

  @Override
  public synchronized void close() {
    closed = true;
    chunks.clear();
    current = null;
    notifyAll();
  }
}
