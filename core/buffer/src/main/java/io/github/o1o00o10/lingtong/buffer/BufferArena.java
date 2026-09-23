/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.buffer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** 一个 I/O 分片的有界缓冲池，只回收由自己创建的 ByteBlock。 */
public final class BufferArena {
  /** 单个缓冲块大小，单位字节。 */
  private final int blockSize;
  /** 缓冲块创建总量上限。 */
  private final int maxBlocks;
  /** 是否使用堆外直接缓冲。 */
  private final boolean direct;
  /** 当前可复用的空闲缓冲块。 */
  private final ArrayBlockingQueue<ByteBlock> freeBlocks;
  /** 已创建的缓冲块数，包含正在使用的块。 */
  private final AtomicInteger created = new AtomicInteger();
  /** 当前未回池的缓冲块数。 */
  private final AtomicInteger inUse = new AtomicInteger();

  public BufferArena(int blockSize, int maxBlocks, boolean direct) {
    if (blockSize <= 0 || maxBlocks <= 0) {
      throw new IllegalArgumentException("blockSize and maxBlocks must be positive");
    }
    this.blockSize = blockSize;
    this.maxBlocks = maxBlocks;
    this.direct = direct;
    this.freeBlocks = new ArrayBlockingQueue<ByteBlock>(maxBlocks);
  }

  /** 获取 I/O 所有权；达到上限且无空闲块时返回 null。 */
  public ByteBlock acquire() {
    ByteBlock block = freeBlocks.poll();
    if (block == null && reserveCreation()) {
      block = new ByteBlock(this, blockSize, direct);
    }
    if (block == null) {
      return null;
    }
    block.acquireForIo();
    inUse.incrementAndGet();
    return block;
  }

  /** I/O 未借出时直接释放并回池。 */
  public void releaseFromIo(ByteBlock block) {
    requireBlock(block);
    block.releaseFromIo();
    returnToPool(block);
  }

  /** 只读租约归还后回收其原始缓冲块。 */
  public void recycle(BufferLease lease) {
    if (lease == null) {
      throw new IllegalArgumentException("lease must not be null");
    }
    ByteBlock block = lease.block();
    requireBlock(block);
    block.recycleReturned();
    returnToPool(block);
  }

  public int createdBlocks() {
    return created.get();
  }

  public int inUseBlocks() {
    return inUse.get();
  }

  private boolean reserveCreation() {
    while (true) {
      int current = created.get();
      if (current >= maxBlocks) {
        return false;
      }
      if (created.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  private void returnToPool(ByteBlock block) {
    inUse.decrementAndGet();
    if (!freeBlocks.offer(block)) {
      throw new IllegalStateException("BufferArena free queue overflow");
    }
  }

  private void requireBlock(ByteBlock block) {
    if (block == null) {
      throw new IllegalArgumentException("block must not be null");
    }
    if (!block.belongsTo(this)) {
      throw new IllegalArgumentException("block belongs to a different BufferArena");
    }
  }
}
