/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

/** 向 JMX 暴露运行时端口、请求与 Session 指标的只读接口。 */
public interface LingTongRuntimeMXBean {
    String getNodeId();
    boolean isRunning();
    boolean isAcceptingRequests();
    int getApplicationPort();
    int getManagementPort();
    long getUptimeSeconds();
    long getRequestsTotal();
    long getFailuresTotal();
    int getActiveRequests();
    int getActiveConnections();
    int getWorkerActive();
    int getWorkerQueued();
    int getLocalSessions();
    long getStoredSessions();
    long getExpiredSessions();
    long getSessionScavengerFailures();
    long getManagementAuthFailures();
    long getManagementAuthorizationFailures();
    long getManagementAuditOverwritten();
    long getManagementAuditPersistenceFailures();
}
