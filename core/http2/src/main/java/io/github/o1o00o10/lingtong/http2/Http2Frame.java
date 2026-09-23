/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http2;

import java.nio.ByteBuffer;

/** 单个 HTTP/2 帧；长度字段与 9 字节帧头由 encode 生成。 */
public final class Http2Frame {
  /** 请求或响应的正文数据。 */
  public static final int DATA = 0;
  /** 头部块首帧。 */
  public static final int HEADERS = 1;
  /** 流优先级信息。 */
  public static final int PRIORITY = 2;
  /** 异常终止一条流。 */
  public static final int RST_STREAM = 3;
  /** 连接配置及其确认。 */
  public static final int SETTINGS = 4;
  /** 服务端推送承诺帧类型。 */
  public static final int PUSH_PROMISE = 5;
  /** 连接存活探测。 */
  public static final int PING = 6;
  /** 连接关闭或排空通知。 */
  public static final int GOAWAY = 7;
  /** 增加发送流控窗口。 */
  public static final int WINDOW_UPDATE = 8;
  /** 延续未结束的头部块。 */
  public static final int CONTINUATION = 9;

  /** 帧类型编号。 */
  private final int type;
  /** 帧类型相关的标志位。 */
  private final int flags;
  /** 流编号；0 代表连接级帧。 */
  private final int streamId;
  /** 帧负载的防御性副本。 */
  private final byte[] payload;

  public Http2Frame(int type, int flags, int streamId, byte[] payload) {
    if (type < 0 || type > 255 || flags < 0 || flags > 255 || streamId < 0) {
      throw new IllegalArgumentException("invalid HTTP/2 frame metadata");
    }
    this.type = type;
    this.flags = flags;
    this.streamId = streamId;
    this.payload = payload == null ? new byte[0] : payload.clone();
  }

  public int type() {
    return type;
  }

  public int flags() {
    return flags;
  }

  public int streamId() {
    return streamId;
  }

  public byte[] payload() {
    return payload.clone();
  }

  /** 编码为可写出的完整帧缓冲区。 */
  public ByteBuffer encode() {
    ByteBuffer result = ByteBuffer.allocate(9 + payload.length);
    result.put((byte) (payload.length >>> 16));
    result.put((byte) (payload.length >>> 8));
    result.put((byte) payload.length);
    result.put((byte) type).put((byte) flags).putInt(streamId & 0x7fffffff);
    result.put(payload).flip();
    return result;
  }
}
