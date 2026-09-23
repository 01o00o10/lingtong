/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import java.io.IOException;

/** Pluggable persistence boundary for already-sanitized management audit events. */
/** 已脱敏管理审计事件的可插拔持久化接口。 */
public interface ManagementAuditSink extends AutoCloseable {
  void append(ManagementAuditEvent event) throws IOException;

  @Override
  void close() throws IOException;
}
