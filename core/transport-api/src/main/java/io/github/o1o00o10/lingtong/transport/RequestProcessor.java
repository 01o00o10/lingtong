/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import io.github.o1o00o10.lingtong.http.HttpRequest;
import io.github.o1o00o10.lingtong.http.HttpResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 传输层向上交付 HTTP 请求的最小处理端口。 */
public interface RequestProcessor {
    /** 同步处理一次请求；应用代码应由工作线程调用。 */
    HttpResponse process(HttpRequest request) throws Exception;

    /** 默认异步适配仍同步执行 process；专门实现才能延后完成。 */
    default CompletionStage<HttpResponse> processAsync(HttpRequest request) {
        CompletableFuture<HttpResponse> completion = new CompletableFuture<HttpResponse>();
        try {
            completion.complete(process(request));
        } catch (Exception | LinkageError e) {
            completion.completeExceptionally(e);
        }
        return completion;
    }
}
