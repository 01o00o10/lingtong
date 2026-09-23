/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.io.IOException;
import java.nio.ByteBuffer;

/** HTTP 响应切换为长连接协议后的回调。 */
public interface ConnectionUpgrade {
  /** 升级成功并取得通道时调用。 */
  void onOpen(UpgradeChannel channel) throws IOException;

  /** 收到升级协议的数据块时调用。 */
  void onInput(ByteBuffer input) throws IOException;

  /** 底层连接关闭时调用。 */
  void onClosed();

  /** 可选的连接计时回调；时间为单调时钟纳秒。 */
  default void onTimer(long nowNanos) {}

  /** 由升级协议自行管理空闲超时时返回 true。 */
  default boolean managesIdleTimeout() {
    return false;
  }
}
