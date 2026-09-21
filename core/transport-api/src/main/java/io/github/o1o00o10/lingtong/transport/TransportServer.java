/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import java.io.IOException;

/**
 * 网络层生命周期契约。Runtime 调用 start 后才能对外接入；停止时先由实现
 * 拒绝新连接并排空已有连接，最终 close 释放 Socket 和 I/O 线程。
 * HTTP 请求如何交付业务层由 RequestProcessor/StreamingResponseProcessor 定义。
 */
public interface TransportServer extends AutoCloseable {
    /** 打开监听端口，完成后才能接收连接。 */
    void start() throws IOException;

    /** 返回监听器当前是否运行。 */
    boolean isRunning();

    /** 返回实际绑定端口；使用端口 0 时由启动后的实现给出。 */
    int port();

    /** 释放监听器持有的网络资源。 */
    @Override
    void close();
}
