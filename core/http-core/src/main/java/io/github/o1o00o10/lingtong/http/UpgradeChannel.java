/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 升级协议向所属网络连接写入字节的受限通道。 */
public interface UpgradeChannel {
    /** 通道是否仍可写入。 */
    boolean isOpen();

    /** 同步提交写入；不保证远端已收到。 */
    void write(ByteBuffer data) throws IOException;

    /** 默认实现同步调用 write 后完成 Stage。 */
    default CompletionStage<Void> writeAsync(ByteBuffer data) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        try {
            write(data);
            result.complete(null);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /** 请求关闭底层升级通道。 */
    void close();
}
