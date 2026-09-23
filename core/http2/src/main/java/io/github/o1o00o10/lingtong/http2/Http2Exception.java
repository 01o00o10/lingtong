/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http2;

import java.io.IOException;

/** 附带 HTTP/2 错误码及作用域的协议异常。 */
public final class Http2Exception extends IOException {
  /** 线上的 HTTP/2 错误码。 */
  private final int errorCode;
  /** 非零表示流级错误，零表示连接级错误。 */
  private final int streamId;

  public Http2Exception(int errorCode, int streamId, String message) {
    super(message);
    this.errorCode = errorCode;
    this.streamId = streamId;
  }

  public int errorCode() {
    return errorCode;
  }

  public int streamId() {
    return streamId;
  }
}
