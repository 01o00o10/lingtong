/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 创建 Servlet 异步超时调度线程。 */
final class ServletAsyncTimerThreadFactory implements ThreadFactory {
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  @Override
  public Thread newThread(Runnable task) {
    Thread thread = new Thread(task, "lingtong-async-timeout-" + SEQUENCE.incrementAndGet());
    thread.setDaemon(true);
    return thread;
  }
}
