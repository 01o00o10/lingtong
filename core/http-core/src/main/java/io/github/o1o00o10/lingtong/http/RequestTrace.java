/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 一次请求的可选诊断时间线；不包含 URL、Header、正文或异常消息。 */
public final class RequestTrace {
    /** 与 Spring Boot 的 logging.level.io.github.o1o00o10.lingtong.trace 对应。 */
    private static final Logger LOGGER = Logger.getLogger("io.github.o1o00o10.lingtong.trace");
    /** 仅在诊断开启时分配的请求序号。 */
    private static final AtomicLong NEXT_ID = new AtomicLong();

    /** 跨连接、跨 HTTP/2 流唯一的本进程请求 ID。 */
    private final long id;
    /** 从分派开始计时的单调时钟。 */
    private final long startedNanos;
    /** 并发阶段共享的单调日志序号。 */
    private final AtomicInteger steps = new AtomicInteger();
    /** 确保最终摘要最多写一次。 */
    private final AtomicBoolean finished = new AtomicBoolean();

    private RequestTrace(long id) {
        this.id = id;
        this.startedNanos = System.nanoTime();
    }

    /** 未开启 FINE 时返回 null，避免正常请求分配追踪对象。 */
    public static RequestTrace begin(long connectionId, int streamId, String method) {
        if (!LOGGER.isLoggable(Level.FINE)) return null;
        RequestTrace trace = new RequestTrace(NEXT_ID.incrementAndGet());
        trace.event("transport.accept", "connection=" + connectionId
                + (streamId == 0 ? "" : " stream=" + streamId)
                + " method=" + method);
        return trace;
    }

    public long id() {
        return id;
    }

    /** 阶段名称和附加字段必须是容器控制的安全值，不接受请求内容。 */
    public synchronized void event(String stage, String detail) {
        if (!LOGGER.isLoggable(Level.FINE) || finished.get()) return;
        int step = steps.incrementAndGet();
        LOGGER.fine("trace=" + id + " step=" + step + " elapsedUs="
                + elapsedMicros(startedNanos) + " stage=" + stage
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    public void event(String stage) {
        event(stage, null);
    }

    /** 汇总已记录的阶段数；结束阶段本身也计入总数。 */
    public synchronized void finish(String stage) {
        if (!finished.compareAndSet(false, true)) return;
        int count = steps.incrementAndGet();
        LOGGER.fine("trace=" + id + " step=" + count + " elapsedUs="
                + elapsedMicros(startedNanos) + " stage=" + stage
                + " totalSteps=" + count);
    }

    public static long elapsedMicros(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000L);
    }
}
