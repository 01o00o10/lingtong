/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import io.github.o1o00o10.lingtong.http.HttpRequest;
import java.util.concurrent.CompletionStage;

/** 将响应头发布与完整请求生命周期解耦的处理端口。 */
public interface StreamingResponseProcessor extends RequestProcessor {
  /** 发布一次流式响应；Stage 在请求处理完成时结束，正文可由邮箱继续排出。 */
  CompletionStage<Void> processStreaming(
      HttpRequest request, StreamingResponseConsumer responseConsumer);
}
