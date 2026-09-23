/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

/** Immutable, credential-free management event passed to an audit sink. */
/** 不包含凭据和请求体的管理操作审计记录。 */
public final class ManagementAuditEvent {
  /** 时间戳毫秒，单位为毫秒。 */
  private final long timestampMillis;
  /** 远端地址。 */
  private final String remoteAddress;
  /** 角色。 */
  private final String role;
  /** 方法。 */
  private final String method;
  /** 路径。 */
  private final String path;
  /** 状态码。 */
  private final int status;
  /** outcome。 */
  /** 操作结果（outcome）。 */
  private final String outcome;

  public ManagementAuditEvent(
      long timestampMillis,
      String remoteAddress,
      String role,
      String method,
      String path,
      int status,
      String outcome) {
    if (remoteAddress == null
        || role == null
        || method == null
        || path == null
        || outcome == null
        || status < 100
        || status > 599) {
      throw new IllegalArgumentException("invalid management audit event");
    }
    this.timestampMillis = timestampMillis;
    this.remoteAddress = remoteAddress;
    this.role = role;
    this.method = method;
    this.path = path;
    this.status = status;
    this.outcome = outcome;
  }

  public long timestampMillis() {
    return timestampMillis;
  }

  public String remoteAddress() {
    return remoteAddress;
  }

  public String role() {
    return role;
  }

  public String method() {
    return method;
  }

  public String path() {
    return path;
  }

  public int status() {
    return status;
  }

  public String outcome() {
    return outcome;
  }
}
