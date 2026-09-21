/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/** 承载 server.lingtong.* 配置，并在工厂装配时提供类型化取值。 */
@ConfigurationProperties("server.lingtong")
public class LingTongServerProperties {
    /** 请求头部超时时长。 */
    private Duration requestHeaderTimeout = Duration.ofSeconds(10);
    /** 空闲超时时长。 */
    private Duration idleTimeout = Duration.ofSeconds(30);
    /** 最大请求集合逐连接。 */
    private int maxRequestsPerConnection = 1_000;
    /** 最大头部数量。 */
    private int maxHeaderCount = 100;
    /** 最大请求大小。 */
    private DataSize maxRequestSize = DataSize.ofMegabytes(8);
    /** 最大尾部头大小。 */
    private DataSize maxTrailerSize = DataSize.ofKilobytes(8);
    /** 最大尾部头数量。 */
    private int maxTrailerCount = 100;
    /** 最大参数数量。 */
    private int maxParameterCount = 1_000;
    /** 最大参数大小。 */
    private DataSize maxParameterSize = DataSize.ofMegabytes(1);
    /** 最大Cookie数量。 */
    private int maxCookieCount = 200;
    /** 最大Cookie大小。 */
    private DataSize maxCookieSize = DataSize.ofKilobytes(8);
    /** 请求消息体缓冲区大小。 */
    private DataSize requestBodyBufferSize = DataSize.ofKilobytes(64);
    /** 请求消息体低水位大小。 */
    private DataSize requestBodyLowWaterSize = DataSize.ofKilobytes(32);
    /** 响应消息体缓冲区大小。 */
    private DataSize responseBodyBufferSize = DataSize.ofKilobytes(64);
    /** 响应消息体低水位大小。 */
    private DataSize responseBodyLowWaterSize = DataSize.ofKilobytes(32);
    /** 最大响应大小。 */
    private DataSize maxResponseSize = DataSize.ofMegabytes(8);
    /** 停机排空超时时长。 */
    private Duration shutdownDrainTimeout = Duration.ofSeconds(5);
    /** 工作线程线程集合。 */
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
    /** 按顺序保存的受信任的代理集合。 */
    private List<String> trustedProxies = new ArrayList<String>();
    /** 管理令牌。 */
    private String managementToken;
    /** 节点标识符。 */
    private String nodeId;
    /** 按顺序保存的集群对端集合。 */
    private List<String> clusterPeers = new ArrayList<String>();
    /** 集群心跳。 */
    private Duration clusterHeartbeat = Duration.ofSeconds(5);
    /** 集群失败阈值。 */
    private int clusterFailureThreshold = 3;
    /** 管理绑定地址。 */
    private String managementBindAddress = "127.0.0.1";
    /** 管理端口。 */
    private int managementPort = -1;
    /** 管理操作者令牌。 */
    private String managementOperatorToken;
    /** 管理审计文件。 */
    private Path managementAuditFile;
    /** 管理审计最大大小。 */
    private DataSize managementAuditMaxSize = DataSize.ofMegabytes(16);
    /** 管理审计保留的文件列表。 */
    private int managementAuditRetainedFiles = 5;

    public Duration getRequestHeaderTimeout() {
        return requestHeaderTimeout;
    }

    public void setRequestHeaderTimeout(Duration requestHeaderTimeout) {
        this.requestHeaderTimeout = requestHeaderTimeout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public int getMaxRequestsPerConnection() {
        return maxRequestsPerConnection;
    }

    public void setMaxRequestsPerConnection(int maxRequestsPerConnection) {
        this.maxRequestsPerConnection = maxRequestsPerConnection;
    }

    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }

    public DataSize getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(DataSize maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    public void setMaxHeaderCount(int maxHeaderCount) {
        this.maxHeaderCount = maxHeaderCount;
    }

    public DataSize getMaxTrailerSize() {
        return maxTrailerSize;
    }

    public void setMaxTrailerSize(DataSize maxTrailerSize) {
        this.maxTrailerSize = maxTrailerSize;
    }

    public int getMaxTrailerCount() {
        return maxTrailerCount;
    }

    public void setMaxTrailerCount(int maxTrailerCount) {
        this.maxTrailerCount = maxTrailerCount;
    }

    public int getMaxParameterCount() {
        return maxParameterCount;
    }

    public void setMaxParameterCount(int maxParameterCount) {
        this.maxParameterCount = maxParameterCount;
    }

    public DataSize getMaxParameterSize() {
        return maxParameterSize;
    }

    public void setMaxParameterSize(DataSize maxParameterSize) {
        this.maxParameterSize = maxParameterSize;
    }

    public int getMaxCookieCount() {
        return maxCookieCount;
    }

    public void setMaxCookieCount(int maxCookieCount) {
        this.maxCookieCount = maxCookieCount;
    }

    public DataSize getMaxCookieSize() {
        return maxCookieSize;
    }

    public void setMaxCookieSize(DataSize maxCookieSize) {
        this.maxCookieSize = maxCookieSize;
    }

    public DataSize getRequestBodyBufferSize() {
        return requestBodyBufferSize;
    }

    public void setRequestBodyBufferSize(DataSize requestBodyBufferSize) {
        this.requestBodyBufferSize = requestBodyBufferSize;
    }

    public DataSize getRequestBodyLowWaterSize() {
        return requestBodyLowWaterSize;
    }

    public void setRequestBodyLowWaterSize(DataSize requestBodyLowWaterSize) {
        this.requestBodyLowWaterSize = requestBodyLowWaterSize;
    }

    public DataSize getMaxResponseSize() {
        return maxResponseSize;
    }

    public DataSize getResponseBodyBufferSize() {
        return responseBodyBufferSize;
    }

    public void setResponseBodyBufferSize(DataSize responseBodyBufferSize) {
        this.responseBodyBufferSize = responseBodyBufferSize;
    }

    public DataSize getResponseBodyLowWaterSize() {
        return responseBodyLowWaterSize;
    }

    public void setResponseBodyLowWaterSize(DataSize responseBodyLowWaterSize) {
        this.responseBodyLowWaterSize = responseBodyLowWaterSize;
    }

    public void setMaxResponseSize(DataSize maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }

    public Duration getShutdownDrainTimeout() {
        return shutdownDrainTimeout;
    }

    public void setShutdownDrainTimeout(Duration shutdownDrainTimeout) {
        this.shutdownDrainTimeout = shutdownDrainTimeout;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public String getManagementToken() { return managementToken; }
    public void setManagementToken(String managementToken) { this.managementToken = managementToken; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public List<String> getClusterPeers() { return clusterPeers; }
    public void setClusterPeers(List<String> clusterPeers) { this.clusterPeers = clusterPeers; }
    public Duration getClusterHeartbeat() { return clusterHeartbeat; }
    public void setClusterHeartbeat(Duration clusterHeartbeat) {
        this.clusterHeartbeat = clusterHeartbeat;
    }
    public int getClusterFailureThreshold() { return clusterFailureThreshold; }
    public void setClusterFailureThreshold(int clusterFailureThreshold) {
        this.clusterFailureThreshold = clusterFailureThreshold;
    }
    public String getManagementBindAddress() { return managementBindAddress; }
    public void setManagementBindAddress(String managementBindAddress) {
        this.managementBindAddress = managementBindAddress;
    }
    public int getManagementPort() { return managementPort; }
    public void setManagementPort(int managementPort) { this.managementPort = managementPort; }
    public String getManagementOperatorToken() { return managementOperatorToken; }
    public void setManagementOperatorToken(String value) { managementOperatorToken = value; }
    public Path getManagementAuditFile() { return managementAuditFile; }
    public void setManagementAuditFile(Path value) { managementAuditFile = value; }
    public DataSize getManagementAuditMaxSize() { return managementAuditMaxSize; }
    public void setManagementAuditMaxSize(DataSize value) { managementAuditMaxSize = value; }
    public int getManagementAuditRetainedFiles() { return managementAuditRetainedFiles; }
    public void setManagementAuditRetainedFiles(int value) { managementAuditRetainedFiles = value; }
}
