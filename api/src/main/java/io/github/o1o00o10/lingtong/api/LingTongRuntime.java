/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.api;

/** 嵌入式运行时的两阶段生命周期入口。 */
public interface LingTongRuntime extends AutoCloseable {
  /** 执行初始化器和动态注册，不打开网络监听端口。 */
  void prepare() throws LingTongException;

  /** 激活 Servlet 组件并启动监听；未 prepare 时会在启动过程中准备。 */
  void start() throws LingTongException;

  /** 停止准入并排空当前运行；不等于最终释放部署资源。 */
  void stop();

  /** 返回业务监听器是否处于运行态。 */
  boolean isRunning();

  /** 返回业务监听端口，端口 0 启动后可读到实际端口。 */
  int port();

  /** 返回独立管理监听端口；与业务端口共用时返回 -1。 */
  default int managementPort() {
    return -1;
  }

  /** 最终释放运行时、部署和管理资源。 */
  @Override
  void close();
}
