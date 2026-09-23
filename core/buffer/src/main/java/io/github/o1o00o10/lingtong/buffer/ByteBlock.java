/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/** 带所有权状态和代际号的缓冲块，防止重复归还和陈旧租约访问。 */
public final class ByteBlock {
  /** 空闲、I/O 持有、任务借用、待回池四种所有权状态。 */
  enum State {
    /** 缓冲块可由缓冲池重新分配。 */
    FREE,
    /** 当前由 I/O 线程独占。 */
    IO_OWNED,
    /** 已借给任务，只允许通过租约读取。 */
    TASK_LEASED,
    /** 租约已关闭，等待原所有者回收。 */
    RETURN_PENDING
  }

  /** 创建此块的唯一缓冲池。 */
  private final BufferArena owner;
  /** 实际可读写字节存储。 */
  private final ByteBuffer storage;
  /** 当前所有权状态，通过 CAS 转移。 */
  private final AtomicReference<State> state = new AtomicReference<State>(State.FREE);
  /** 每次重新获取时递增，令旧租约失效。 */
  private volatile int generation;

  ByteBlock(BufferArena owner, int capacity, boolean direct) {
    this.owner = owner;
    this.storage = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
  }

  boolean belongsTo(BufferArena arena) {
    return owner == arena;
  }

  void acquireForIo() {
    if (!state.compareAndSet(State.FREE, State.IO_OWNED)) {
      throw new IllegalStateException("ByteBlock is not free");
    }
    generation++;
    storage.clear();
  }

  /** 仅当前 I/O 所有者可获取可写视图。 */
  public ByteBuffer writableBuffer() {
    requireState(State.IO_OWNED);
    return storage;
  }

  /** 将 I/O 所有权转为任务只读租约。 */
  public BufferLease leaseReadOnly() {
    if (!state.compareAndSet(State.IO_OWNED, State.TASK_LEASED)) {
      throw new IllegalStateException("ByteBlock cannot be leased from state " + state.get());
    }
    return new BufferLease(this, generation, storage.asReadOnlyBuffer());
  }

  void markReturnPending(int expectedGeneration) {
    requireGeneration(expectedGeneration);
    if (!state.compareAndSet(State.TASK_LEASED, State.RETURN_PENDING)) {
      throw new IllegalStateException("ByteBlock lease is not active");
    }
  }

  void releaseFromIo() {
    if (!state.compareAndSet(State.IO_OWNED, State.FREE)) {
      throw new IllegalStateException("ByteBlock is not owned by I/O");
    }
    storage.clear();
  }

  void recycleReturned() {
    if (!state.compareAndSet(State.RETURN_PENDING, State.FREE)) {
      throw new IllegalStateException("ByteBlock is not pending return");
    }
    storage.clear();
  }

  ByteBuffer leasedBuffer(int expectedGeneration) {
    requireGeneration(expectedGeneration);
    requireState(State.TASK_LEASED);
    return storage.asReadOnlyBuffer();
  }

  State state() {
    return state.get();
  }

  private void requireGeneration(int expectedGeneration) {
    if (generation != expectedGeneration) {
      throw new IllegalStateException("Stale ByteBlock lease");
    }
  }

  private void requireState(State expected) {
    State current = state.get();
    if (current != expected) {
      throw new IllegalStateException("Expected " + expected + " but was " + current);
    }
  }
}
