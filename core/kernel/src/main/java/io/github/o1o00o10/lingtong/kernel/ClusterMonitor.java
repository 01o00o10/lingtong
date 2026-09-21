/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.kernel;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** 定期探测静态配置的对端，并汇总延迟与可用性快照。 */
final class ClusterMonitor implements AutoCloseable {
    /** 封装对端snapshot的状态与处理边界。 */
    static final class PeerSnapshot {
        /** 端点。 */
        final String endpoint;
        /** 节点标识符。 */
        final String nodeId;
        /** 可用，布尔标志。 */
        final boolean available;
        /** latency毫秒，单位为毫秒。 */
        final long latencyMillis;
        /** 末尾尝试次数毫秒，单位为毫秒。 */
        final long lastAttemptMillis;
        /** 末尾成功毫秒，单位为毫秒。 */
        final long lastSuccessMillis;
        /** probes。 */
        /** 探测次数（probes）。 */
        final long probes;
        /** 失败次数。 */
        final long failures;
        /** consecutive失败次数。 */
        final int consecutiveFailures;
        /** 失败。 */
        final String failure;

        PeerSnapshot(String endpoint, String nodeId, boolean available, long latencyMillis,
                long lastAttemptMillis, long lastSuccessMillis, long probes, long failures,
                int consecutiveFailures, String failure) {
            this.endpoint = endpoint;
            this.nodeId = nodeId;
            this.available = available;
            this.latencyMillis = latencyMillis;
            this.lastAttemptMillis = lastAttemptMillis;
            this.lastSuccessMillis = lastSuccessMillis;
            this.probes = probes;
            this.failures = failures;
            this.consecutiveFailures = consecutiveFailures;
            this.failure = failure;
        }
    }

    /** 配置。 */
    private final ManagementConfig config;
    /** 按键索引的对端集合。 */
    private final Map<String, PeerSnapshot> peers = new LinkedHashMap<String, PeerSnapshot>();
    /** 调度器。 */
    private ScheduledExecutorService scheduler;

    ClusterMonitor(ManagementConfig config) {
        this.config = config;
        for (String peer : config.peers()) {
            String normalized = normalize(peer);
            peers.put(normalized, new PeerSnapshot(
                    normalized, null, false, -1L, 0L, 0L, 0L, 0L, 0, "not probed"));
        }
    }

    synchronized void start() {
        if (scheduler != null || peers.isEmpty()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "lingtong-cluster-heartbeat");
                thread.setDaemon(true);
                return thread;
            }
        });
        scheduler.scheduleWithFixedDelay(this::probeAll, 0L,
                config.heartbeatMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized List<PeerSnapshot> snapshot() {
        return Collections.unmodifiableList(new ArrayList<PeerSnapshot>(peers.values()));
    }

    private void probeAll() {
        List<String> endpoints;
        synchronized (this) {
            endpoints = new ArrayList<String>(peers.keySet());
        }
        for (String endpoint : endpoints) probe(endpoint);
    }

    private void probe(String endpoint) {
        long started = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    endpoint + "/__lingtong/manage/v1/heartbeat").openConnection();
            connection.setConnectTimeout((int) Math.min(5000L, config.heartbeatMillis()));
            connection.setReadTimeout((int) Math.min(5000L, config.heartbeatMillis()));
            connection.setRequestProperty("Authorization", "Bearer " + config.token());
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("heartbeat returned HTTP " + status);
            String nodeId = connection.getHeaderField("X-LingTong-Node-Id");
            if (nodeId == null || !nodeId.matches("[A-Za-z0-9._-]{1,128}")) {
                throw new IOException("heartbeat omitted a valid node identity");
            }
            if (config.nodeId().equals(nodeId)) {
                throw new IOException("heartbeat returned the local node identity");
            }
            byte[] buffer = new byte[256];
            try (InputStream input = connection.getInputStream()) {
                while (input.read(buffer) >= 0) { }
            }
            update(endpoint, nodeId, true, elapsedMillis(started), null);
        } catch (Exception e) {
            update(endpoint, null, false, elapsedMillis(started), failure(e));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void update(
            String endpoint, String nodeId, boolean successful, long latencyMillis, String failure) {
        PeerSnapshot previous = peers.get(endpoint);
        long now = System.currentTimeMillis();
        int consecutiveFailures = successful ? 0 : previous.consecutiveFailures + 1;
        boolean available = successful || (previous.available
                && consecutiveFailures < config.failureThreshold());
        peers.put(endpoint, new PeerSnapshot(
                endpoint,
                successful ? nodeId : previous.nodeId,
                available,
                latencyMillis,
                now,
                successful ? now : previous.lastSuccessMillis,
                previous.probes + 1L,
                previous.failures + (successful ? 0L : 1L),
                consecutiveFailures,
                failure));
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("cluster peer must not be null");
        String result = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            throw new IllegalArgumentException("cluster peer must be an HTTP(S) endpoint");
        }
        return result;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String failure(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isEmpty()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + message;
    }
}
