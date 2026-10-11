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

/** 有界执行域，支持繁忙时优先扩容线程，过载时显式拒绝任务。 */
public final class BoundedExecutionDomain implements AutoCloseable {
  /** 工作线程及有界任务队列的所有者。 */
  private final ThreadPoolExecutor executor;

  public BoundedExecutionDomain(int threads, int queueCapacity, String threadPrefix) {
    this(threads, threads, queueCapacity, threadPrefix);
  }

  /** 空闲线程优先复用，繁忙时先扩容至 maxThreads，再进入有界队列。 */
  public BoundedExecutionDomain(
      int coreThreads, int maxThreads, int queueCapacity, String threadPrefix) {
    if (coreThreads <= 0 || maxThreads < coreThreads || queueCapacity <= 0) {
      throw new IllegalArgumentException("invalid coreThreads, maxThreads or queueCapacity");
    }
    EagerQueue queue = new EagerQueue(queueCapacity);
    this.executor =
        new ThreadPoolExecutor(
            coreThreads,
            maxThreads,
            60L,
            TimeUnit.SECONDS,
            queue,
            new NamedThreadFactory(threadPrefix),
            new ThreadPoolExecutor.AbortPolicy());
    queue.owner = executor;
  }

  /** 已提交且尚未结束的任务数，包括正在提交的任务，避免只靠 activeCount 低估负载。 */
  private final AtomicInteger submitted = new AtomicInteger();

  /** 接收成功返回 true；满载或关闭后的拒绝返回 false。 */
  public boolean execute(Runnable task) {
    if (task == null) throw new NullPointerException("task");
    Runnable counted =
        () -> {
          try {
            task.run();
          } finally {
            submitted.decrementAndGet();
          }
        };
    submitted.incrementAndGet();
    boolean accepted = false;
    try {
      try {
        executor.execute(counted);
        accepted = true;
      } catch (RejectedExecutionException ignored) {
        // 多个提交者同时触发扩容时，失败者仍可使用剩余队列容量。
        EagerQueue queue = (EagerQueue) executor.getQueue();
        if (!executor.isShutdown() && queue.force(counted)) {
          // 与 execute 的关闭检查保持一致，撤回尚未执行的任务。
          accepted = !(executor.isShutdown() && executor.remove(counted));
        }
      }
      return accepted;
    } finally {
      if (!accepted) submitted.decrementAndGet();
    }
  }

  private final class EagerQueue extends ArrayBlockingQueue<Runnable> {
    private static final long serialVersionUID = 1L;
    private ThreadPoolExecutor owner;

    private EagerQueue(int capacity) {
      super(capacity);
    }

    @Override
    public boolean offer(Runnable task) {
      int poolSize = owner.getPoolSize();
      if (submitted.get() > poolSize && poolSize < owner.getMaximumPoolSize()) {
        return false;
      }
      return super.offer(task);
    }

    private boolean force(Runnable task) {
      return super.offer(task);
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
