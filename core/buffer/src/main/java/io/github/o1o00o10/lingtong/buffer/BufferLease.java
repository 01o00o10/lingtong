/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** 跨线程只读借用凭据；close 只标记待归还，由 BufferArena 执行回池。 */
public final class BufferLease implements AutoCloseable {
    /** 被借出的原始缓冲块。 */
    private final ByteBlock block;
    /** 借出时的代际号，用于拒绝回池后的陈旧访问。 */
    private final int generation;
    /** 借出瞬间的 position/limit 边界。 */
    private final ByteBuffer initialView;
    /** 防止重复关闭租约。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    BufferLease(ByteBlock block, int generation, ByteBuffer initialView) {
        this.block = block;
        this.generation = generation;
        this.initialView = initialView;
    }

    /** 返回不超过原借用边界的只读视图。 */
    public ByteBuffer buffer() {
        if (closed.get()) {
            throw new IllegalStateException("BufferLease is closed");
        }
        ByteBuffer current = block.leasedBuffer(generation);
        current.position(initialView.position());
        current.limit(initialView.limit());
        return current;
    }

    /** 完成借用；调用方仍需让所属 Arena.recycle 回收缓冲块。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            block.markReturnPending(generation);
        }
    }

    ByteBlock block() {
        return block;
    }
}
