/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.api.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 端点可见的连接与发送契约；具体完成时机由实现决定。 */
public interface WebSocketSession {
    /** 当前连接的标识。 */
    String id();

    /** 连接是否仍可发送消息。 */
    boolean isOpen();

    /** 未协商到子协议时返回空串。 */
    default String negotiatedSubprotocol() {
        return "";
    }

    /** 返回已协商的扩展名称列表。 */
    default List<String> negotiatedExtensions() {
        return Collections.emptyList();
    }

    /** 同步发送完整文本消息。 */
    void sendText(String message) throws IOException;

    /** 默认实现同步调用 sendText 后完成 Stage；传输实现可覆写为真实异步发送。 */
    default CompletionStage<Void> sendTextAsync(String message) {
        return completedSend(() -> sendText(message));
    }

    /** 默认实现只接受末片；支持连续片发送的实现需要覆写。 */
    default CompletionStage<Void> sendTextFragmentAsync(String fragment, boolean last) {
        if (!last) {
            CompletableFuture<Void> result = new CompletableFuture<Void>();
            result.completeExceptionally(new UnsupportedOperationException(
                    "fragmented text messages are not supported by this session"));
            return result;
        }
        return sendTextAsync(fragment);
    }

    /** 同步发送完整二进制消息。 */
    void sendBinary(ByteBuffer message) throws IOException;

    /** 默认实现同步调用 sendBinary 后完成 Stage。 */
    default CompletionStage<Void> sendBinaryAsync(ByteBuffer message) {
        return completedSend(() -> sendBinary(message));
    }

    /** 默认实现只接受末片；支持连续片发送的实现需要覆写。 */
    default CompletionStage<Void> sendBinaryFragmentAsync(ByteBuffer fragment, boolean last) {
        if (!last) {
            CompletableFuture<Void> result = new CompletableFuture<Void>();
            result.completeExceptionally(new UnsupportedOperationException(
                    "fragmented binary messages are not supported by this session"));
            return result;
        }
        return sendBinaryAsync(fragment);
    }

    /** 同步发送 Ping 控制帧。 */
    void sendPing(ByteBuffer payload) throws IOException;

    /** 默认实现同步调用 sendPing 后完成 Stage。 */
    default CompletionStage<Void> sendPingAsync(ByteBuffer payload) {
        return completedSend(() -> sendPing(payload));
    }

    /** 默认不支持主动发送 Pong。 */
    default void sendPong(ByteBuffer payload) throws IOException {
        throw new UnsupportedOperationException("pong is not supported by this session");
    }

    /** 将 Pong 发送结果表示为 Stage，默认仍调用同步方法。 */
    default CompletionStage<Void> sendPongAsync(ByteBuffer payload) {
        return completedSend(() -> sendPong(payload));
    }

    /** 调整连接空闲超时，单位毫秒；默认实现不做处理。 */
    default void setIdleTimeoutMillis(long timeoutMillis) {
    }

    /** 为一次发送应用超时策略；默认实现原样返回 Stage。 */
    default CompletionStage<Void> withSendTimeout(
            CompletionStage<Void> send, long timeoutMillis) {
        return send;
    }

    /** 异步批处理检查点；默认立即完成。 */
    default CompletionStage<Void> asyncCheckpoint() {
        return CompletableFuture.completedFuture(null);
    }

    /** 将发送完成回调切换到实现指定的执行域；默认保持原 Stage。 */
    default CompletionStage<Void> dispatchCompletion(CompletionStage<Void> send) {
        return send;
    }

    /** 关闭连接。 */
    void close() throws IOException;

    /** 携带状态码和原因关闭连接。 */
    void close(int statusCode, String reason) throws IOException;

    /** 把同步发送动作转换为完成或异常完成的 Stage。 */
    static CompletionStage<Void> completedSend(SendAction action) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        try {
            action.run();
            result.complete(null);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /** 允许抛出 IOException 的同步发送动作。 */
    interface SendAction {
        void run() throws IOException;
    }
}
