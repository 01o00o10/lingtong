/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

/** Standard JSR 356 provider for standalone LingTong WebSocket clients. */
/** 为独立 WebSocket 客户端提供 JSR 356 ContainerProvider 入口。 */
public final class LingTongContainerProvider extends ContainerProvider {
    /** 默认最大消息字节数，单位为字节。 */
    private static final int DEFAULT_MAX_MESSAGE_BYTES = 1024 * 1024;

    @Override
    protected WebSocketContainer getContainer() {
        return new LingTongServerContainer(
                new WebSocketEndpointRegistry(), DEFAULT_MAX_MESSAGE_BYTES);
    }
}
