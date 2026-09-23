/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable management listener, authentication, and static peer settings. */
/** 管理监听地址、认证令牌与静态对端的不可变配置。 */
public final class ManagementConfig {
  /** 令牌。 */
  private final String token;
  /** 节点标识符。 */
  private final String nodeId;
  /** 按顺序保存的对端集合。 */
  private final List<String> peers;
  /** 心跳毫秒，单位为毫秒。 */
  private final long heartbeatMillis;
  /** 失败阈值。 */
  private final int failureThreshold;
  /** 绑定地址。 */
  private final String bindAddress;
  /** 端口。 */
  private final int port;
  /** 操作者令牌。 */
  private final String operatorToken;
  /** 审计输出端。 */
  private final ManagementAuditSink auditSink;

  public ManagementConfig(String token, String nodeId, List<String> peers, long heartbeatMillis) {
    this(token, nodeId, peers, heartbeatMillis, 3);
  }

  public ManagementConfig(
      String token, String nodeId, List<String> peers, long heartbeatMillis, int failureThreshold) {
    this(token, nodeId, peers, heartbeatMillis, failureThreshold, "127.0.0.1", -1);
  }

  public ManagementConfig(
      String token,
      String nodeId,
      List<String> peers,
      long heartbeatMillis,
      int failureThreshold,
      String bindAddress,
      int port) {
    this(token, nodeId, peers, heartbeatMillis, failureThreshold, bindAddress, port, null, null);
  }

  public ManagementConfig(
      String token,
      String nodeId,
      List<String> peers,
      long heartbeatMillis,
      int failureThreshold,
      String bindAddress,
      int port,
      String operatorToken,
      ManagementAuditSink auditSink) {
    if (token == null
        || token.length() < 16
        || nodeId == null
        || !nodeId.matches("[A-Za-z0-9._-]{1,128}")
        || peers == null
        || heartbeatMillis < 250L
        || failureThreshold <= 0
        || failureThreshold > 100
        || bindAddress == null
        || bindAddress.trim().isEmpty()
        || port < -1
        || port > 65535
        || (operatorToken != null && operatorToken.length() < 16)) {
      throw new IllegalArgumentException("invalid management or cluster configuration");
    }
    this.token = token;
    this.nodeId = nodeId;
    this.peers = Collections.unmodifiableList(new ArrayList<String>(peers));
    this.heartbeatMillis = heartbeatMillis;
    this.failureThreshold = failureThreshold;
    this.bindAddress = bindAddress;
    this.port = port;
    this.operatorToken = operatorToken;
    this.auditSink = auditSink;
  }

  public String token() {
    return token;
  }

  public String nodeId() {
    return nodeId;
  }

  public List<String> peers() {
    return peers;
  }

  public long heartbeatMillis() {
    return heartbeatMillis;
  }

  public int failureThreshold() {
    return failureThreshold;
  }

  public String bindAddress() {
    return bindAddress;
  }

  public int port() {
    return port;
  }

  public boolean dedicatedListener() {
    return port >= 0;
  }

  public String operatorToken() {
    return operatorToken;
  }

  public ManagementAuditSink auditSink() {
    return auditSink;
  }
}
