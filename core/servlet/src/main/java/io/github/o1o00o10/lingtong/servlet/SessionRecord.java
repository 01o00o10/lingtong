/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.Arrays;

/** SessionStore 中不可变的版本化记录；载荷读写时均做防御性复制。 */
public final class SessionRecord {
  /** 对应 HTTP Session 的标识符。 */
  private final String id;
  /** 乐观并发控制版本。 */
  private final long version;
  /** 绝对过期时间，单位为 Unix 毫秒。 */
  private final long expiresAtMillis;
  /** 序列化后的 Session 属性及时间状态。 */
  private final byte[] payload;

  public SessionRecord(String id, long version, long expiresAtMillis, byte[] payload) {
    if (id == null || id.isEmpty() || version < 0L || payload == null) {
      throw new IllegalArgumentException("invalid session record");
    }
    this.id = id;
    this.version = version;
    this.expiresAtMillis = expiresAtMillis;
    this.payload = Arrays.copyOf(payload, payload.length);
  }

  public String id() {
    return id;
  }

  public long version() {
    return version;
  }

  public long expiresAtMillis() {
    return expiresAtMillis;
  }

  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }

  public SessionRecord withVersion(long value) {
    return new SessionRecord(id, value, expiresAtMillis, payload);
  }
}
