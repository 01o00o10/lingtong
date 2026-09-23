/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import io.github.o1o00o10.lingtong.http.StreamingHttpResponse;

/** 传输层接收一份流式响应的回调边界。 */
public interface StreamingResponseConsumer {
  /** 交付响应头与正文邮箱；同一次请求只能交付一次。 */
  void accept(StreamingHttpResponse response);
}
