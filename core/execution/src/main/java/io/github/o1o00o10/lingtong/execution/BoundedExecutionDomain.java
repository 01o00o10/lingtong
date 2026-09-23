/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.execution;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 固定线程数、有界队列的应用执行域，过载时显式拒绝任务。 */
public final class BoundedExecutionDomain implements AutoCloseable {
  /** 工作线程及有界任务队列的所有者。 */
  private final ThreadPoolExecutor executor;

  public BoundedExecutionDomain(int threads, int queueCapacity, String threadPrefix) {
    if (threads <= 0 || queueCapacity <= 0) {
      throw new IllegalArgumentException("threads and queueCapacity must be positive");
    }
    this.executor =
        new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(queueCapacity),
            new NamedThreadFactory(threadPrefix),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /** 入队成功返回 true；满载或关闭后的拒绝返回 false。 */
  public boolean execute(Runnable task) {
    try {
      executor.execute(task);
      return true;
    } catch (RejectedExecutionException ignored) {
      return false;
    }
  }

  public int activeCount() {
    return executor.getActiveCount();
  }

  public int queuedCount() {
    return executor.getQueue().size();
  }

  /** 先等待工作结束，超时后中断剩余任务。 */
  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** 为有界执行域创建带稳定名称的工作线程。 */
  private static final class NamedThreadFactory implements ThreadFactory {
    /** 域内线程序号。 */
    private final AtomicInteger sequence = new AtomicInteger();
    /** 线程名的统一前缀。 */
    private final String prefix;

    private NamedThreadFactory(String prefix) {
      this.prefix = threadPrefix(prefix);
    }

    @Override
    public Thread newThread(Runnable task) {
      Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }

    private static String threadPrefix(String value) {
      return value.endsWith("-") ? value : value + "-";
    }
  }
}
