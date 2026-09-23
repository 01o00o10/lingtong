/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Bounded in-memory management audit trail; credentials and request bodies are never retained. */
/** 保存有界的近期审计事件，并转交可选持久化输出端。 */
final class ManagementAuditLog implements AutoCloseable {
  /** 容量。 */
  private final int capacity;
  /** 输出端。 */
  private final ManagementAuditSink sink;
  /** entries。 */
  /** 条目集合（entries）。 */
  private final Deque<ManagementAuditEvent> entries = new ArrayDeque<ManagementAuditEvent>();
  /** overwritten。 */
  /** 已覆盖数量（overwritten）。 */
  private long overwritten;
  /** persistence失败次数。 */
  private long persistenceFailures;

  ManagementAuditLog(int capacity, ManagementAuditSink sink) {
    if (capacity <= 0) throw new IllegalArgumentException("audit capacity must be positive");
    this.capacity = capacity;
    this.sink = sink;
  }

  synchronized void record(
      String remoteAddress, String role, String method, String path, int status, String outcome) {
    if (entries.size() == capacity) {
      entries.removeFirst();
      overwritten++;
    }
    ManagementAuditEvent event =
        new ManagementAuditEvent(
            System.currentTimeMillis(), remoteAddress, role, method, path, status, outcome);
    entries.addLast(event);
    if (sink != null) {
      try {
        sink.append(event);
      } catch (IOException | RuntimeException ignored) {
        persistenceFailures++;
      }
    }
  }

  synchronized List<ManagementAuditEvent> snapshot() {
    List<ManagementAuditEvent> result = new ArrayList<ManagementAuditEvent>(entries);
    Collections.reverse(result);
    return Collections.unmodifiableList(result);
  }

  synchronized long overwritten() {
    return overwritten;
  }

  synchronized long persistenceFailures() {
    return persistenceFailures;
  }

  @Override
  public synchronized void close() {
    if (sink == null) return;
    try {
      sink.close();
    } catch (IOException | RuntimeException ignored) {
      persistenceFailures++;
    }
  }
}
