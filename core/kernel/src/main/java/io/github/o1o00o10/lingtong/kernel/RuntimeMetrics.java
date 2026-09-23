/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 跨线程累计请求、连接和延迟指标，供管理接口与 JMX 读取。 */
final class RuntimeMetrics {
  /** 已启动标志毫秒，单位为毫秒。 */
  private final long startedMillis = System.currentTimeMillis();
  /** 可并发更新的请求集合。 */
  private final AtomicLong requests = new AtomicLong();
  /** 可并发更新的失败次数。 */
  private final AtomicLong failures = new AtomicLong();
  /** 可并发更新的响应集合2xx。 */
  private final AtomicLong responses2xx = new AtomicLong();
  /** 可并发更新的响应集合4xx。 */
  private final AtomicLong responses4xx = new AtomicLong();
  /** 可并发更新的响应集合5xx。 */
  private final AtomicLong responses5xx = new AtomicLong();
  /** 可并发更新的时长纳秒。 */
  private final AtomicLong durationNanos = new AtomicLong();
  /** 可并发更新的maximum纳秒。 */
  private final AtomicLong maximumNanos = new AtomicLong();
  /** 可并发更新的活动请求集合。 */
  private final AtomicInteger activeRequests = new AtomicInteger();
  /** 可并发更新的管理请求集合。 */
  private final AtomicLong managementRequests = new AtomicLong();
  /** 可并发更新的管理认证失败次数。 */
  private final AtomicLong managementAuthFailures = new AtomicLong();
  /** 可并发更新的管理授权失败次数。 */
  private final AtomicLong managementAuthorizationFailures = new AtomicLong();

  long begin() {
    requests.incrementAndGet();
    activeRequests.incrementAndGet();
    return System.nanoTime();
  }

  void end(long started, int status, Throwable failure) {
    activeRequests.decrementAndGet();
    long elapsed = Math.max(0L, System.nanoTime() - started);
    durationNanos.addAndGet(elapsed);
    updateMaximum(elapsed);
    if (failure != null) failures.incrementAndGet();
    if (status >= 500) responses5xx.incrementAndGet();
    else if (status >= 400) responses4xx.incrementAndGet();
    else if (status >= 200) responses2xx.incrementAndGet();
  }

  long uptimeSeconds() {
    return Math.max(0L, (System.currentTimeMillis() - startedMillis) / 1000L);
  }

  long requests() {
    return requests.get();
  }

  long failures() {
    return failures.get();
  }

  long responses2xx() {
    return responses2xx.get();
  }

  long responses4xx() {
    return responses4xx.get();
  }

  long responses5xx() {
    return responses5xx.get();
  }

  int activeRequests() {
    return activeRequests.get();
  }

  double averageMillis() {
    long count = requests.get();
    return count == 0L ? 0D : durationNanos.get() / 1_000_000D / count;
  }

  long maximumMillis() {
    return TimeUnit.NANOSECONDS.toMillis(maximumNanos.get());
  }

  void managementRequest() {
    managementRequests.incrementAndGet();
  }

  void managementAuthFailure() {
    managementAuthFailures.incrementAndGet();
  }

  void managementAuthorizationFailure() {
    managementAuthorizationFailures.incrementAndGet();
  }

  long managementRequests() {
    return managementRequests.get();
  }

  long managementAuthFailures() {
    return managementAuthFailures.get();
  }

  long managementAuthorizationFailures() {
    return managementAuthorizationFailures.get();
  }

  private void updateMaximum(long elapsed) {
    long current;
    do {
      current = maximumNanos.get();
      if (elapsed <= current) return;
    } while (!maximumNanos.compareAndSet(current, elapsed));
  }
}
