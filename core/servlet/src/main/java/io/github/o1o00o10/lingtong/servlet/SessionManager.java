/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.ServletContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 每应用独立的 Session 缓存、Cookie 策略、事件与可选持久化边界。 */
final class SessionManager implements AutoCloseable {
    /** 未覆盖配置时使用的 Session Cookie 名称。 */
    static final String DEFAULT_COOKIE_NAME = "JSESSIONID";

    /** 当前节点已创建或恢复的 Session，按当前 ID 索引。 */
    private final ConcurrentMap<String, ManagedSession> sessions =
            new ConcurrentHashMap<String, ManagedSession>();
    /** 限制本节点同时驻留的 Session 数量。 */
    private final Semaphore capacity;
    /** 新 Session 的默认最大空闲秒数；零表示不过期。 */
    private final int defaultTimeoutSeconds;
    /** 可注入时钟，用于过期判断与测试。 */
    private final LongSupplier clock;
    /** 生成不可预测 Session ID 的随机源。 */
    private final SecureRandom random = new SecureRandom();
    /** 生命周期、属性和 ID 轮换事件分发器。 */
    private final SessionEventDispatcher events;
    /** 创建响应 Cookie 时读取的配置。 */
    private final MutableSessionCookieConfig cookieConfig;
    /** 可选持久化后端；为空时仅使用本地内存。 */
    private final SessionStore store;
    /** 反序列化应用属性时优先使用的类加载器。 */
    private final ClassLoader applicationClassLoader;
    /** 恢复 Session 后返回给业务代码的 ServletContext。 */
    private final ServletContext servletContext;
    /** 配置存储后创建的定时过期清理线程。 */
    private final ScheduledExecutorService scavenger;
    /** 已清理的本地及存储层过期 Session 累计数。 */
    private final AtomicLong expiredSessions = new AtomicLong();
    /** 定时清理失败累计数。 */
    private final AtomicLong scavengerFailures = new AtomicLong();

    SessionManager(int maxSessions, int defaultTimeoutSeconds) {
        this(maxSessions, defaultTimeoutSeconds, System::currentTimeMillis,
                SessionEventDispatcher.empty(), defaultCookieConfig(), null,
                SessionManager.class.getClassLoader(), null);
    }

    SessionManager(int maxSessions, int defaultTimeoutSeconds, LongSupplier clock) {
        this(maxSessions, defaultTimeoutSeconds, clock, SessionEventDispatcher.empty(),
                defaultCookieConfig(), null, SessionManager.class.getClassLoader(), null);
    }

    SessionManager(
            int maxSessions,
            int defaultTimeoutSeconds,
            LongSupplier clock,
            SessionEventDispatcher events) {
        this(maxSessions, defaultTimeoutSeconds, clock, events, defaultCookieConfig(), null,
                SessionManager.class.getClassLoader(), null);
    }

    SessionManager(
            int maxSessions,
            int defaultTimeoutSeconds,
            LongSupplier clock,
            SessionEventDispatcher events,
            MutableSessionCookieConfig cookieConfig) {
        this(maxSessions, defaultTimeoutSeconds, clock, events, cookieConfig, null,
                SessionManager.class.getClassLoader(), null);
    }

    SessionManager(
            int maxSessions,
            int defaultTimeoutSeconds,
            LongSupplier clock,
            SessionEventDispatcher events,
            MutableSessionCookieConfig cookieConfig,
            SessionStore store,
            ClassLoader applicationClassLoader,
            ServletContext servletContext) {
        if (maxSessions <= 0 || defaultTimeoutSeconds < 0 || clock == null) {
            throw new IllegalArgumentException("invalid session manager configuration");
        }
        this.capacity = new Semaphore(maxSessions);
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.clock = clock;
        this.events = events;
        this.cookieConfig = cookieConfig;
        this.store = store;
        this.applicationClassLoader = applicationClassLoader;
        this.servletContext = servletContext;
        if (store != null) {
            try {
                expiredSessions.addAndGet(store.purgeExpired(clock.getAsLong()));
            } catch (IOException e) {
                throw new IllegalStateException("failed to purge expired HTTP sessions", e);
            }
            scavenger = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "lingtong-session-scavenger");
                    thread.setDaemon(true);
                    thread.setContextClassLoader(SessionManager.class.getClassLoader());
                    return thread;
                }
            });
            scavenger.scheduleWithFixedDelay(
                    this::sweepExpiredSafely, 60L, 60L, TimeUnit.SECONDS);
        } else {
            scavenger = null;
        }
    }

    String cookieName() {
        return cookieConfig.getName();
    }

    void configureCookie(javax.servlet.http.Cookie cookie, boolean requestSecure) {
        if (cookieConfig.getDomain() != null) {
            cookie.setDomain(cookieConfig.getDomain());
        }
        cookie.setPath(cookieConfig.getPath() == null ? "/" : cookieConfig.getPath());
        cookie.setComment(cookieConfig.getComment());
        cookie.setHttpOnly(cookieConfig.isHttpOnly());
        cookie.setSecure(cookieConfig.isSecure() || requestSecure);
        cookie.setMaxAge(cookieConfig.getMaxAge());
        if (cookie instanceof SessionCookieValue) {
            ((SessionCookieValue) cookie).sameSite(cookieConfig.getSameSite());
        }
    }

    /** 查找或恢复 Session，并在返回前登记本次请求访问。 */
    ManagedSession findAndBeginAccess(String id) {
        if (id == null) {
            return null;
        }
        ManagedSession session = sessions.get(id);
        if (session == null && store != null) {
            session = restore(id);
        }
        if (session == null) {
            return null;
        }
        long now = clock.getAsLong();
        ManagedSession.AccessResult result = session.beginAccess(id, now);
        if (result == ManagedSession.AccessResult.ACCESSED) {
            return session;
        }
        if (result == ManagedSession.AccessResult.EXPIRED) {
            removeExpired(session);
        } else {
            sessions.remove(id, session);
        }
        return null;
    }

    /** 占用本节点配额、生成唯一 ID，并触发创建事件。 */
    ManagedSession create(ServletContext servletContext) {
        long now = clock.getAsLong();
        if (!capacity.tryAcquire()) {
            purgeExpired(now);
            if (!capacity.tryAcquire()) {
                throw new IllegalStateException("HTTP session capacity is exhausted");
            }
        }

        boolean registered = false;
        try {
            for (int attempt = 0; attempt < 16; attempt++) {
                String id = newSessionId();
                ManagedSession session = new ManagedSession(
                        this, servletContext, events, id, now, defaultTimeoutSeconds);
                if (sessions.putIfAbsent(id, session) == null) {
                    registered = true;
                    session.created();
                    return session;
                }
            }
            throw new IllegalStateException("unable to allocate a unique HTTP session id");
        } finally {
            if (!registered) {
                capacity.release();
            }
        }
    }

    /** 轮换 Session ID，同时更新本地索引和可选存储记录。 */
    String changeId(ManagedSession session) {
        synchronized (session) {
            session.requireValid();
            String previous = session.id();
            for (int attempt = 0; attempt < 16; attempt++) {
                String replacement = newSessionId();
                if (sessions.putIfAbsent(replacement, session) == null) {
                    session.changeId(replacement);
                    sessions.remove(previous, session);
                    if (store != null) {
                        session.storeVersion(-1L);
                        persist(session);
                        deleteStored(previous, -1L);
                    }
                    session.idChanged(previous);
                    return replacement;
                }
            }
            throw new IllegalStateException("unable to rotate HTTP session id");
        }
    }

    void invalidate(ManagedSession session) {
        if (sessions.remove(session.id(), session)) {
            capacity.release();
        }
        deleteStored(session.id(), -1L);
        session.invalidateState();
    }

    /** 请求结束后更新空闲计时；最后一个访问者离开时才持久化。 */
    void endAccess(ManagedSession session) {
        session.endAccess(clock.getAsLong());
        if (store != null && session.isIdle()) persist(session);
    }

    int localSize() {
        return sessions.size();
    }

    long storedSize() {
        if (store == null) return sessions.size();
        try {
            return store.size();
        } catch (IOException e) {
            throw new IllegalStateException("failed to count persisted HTTP sessions", e);
        }
    }

    long expiredSessionCount() {
        return expiredSessions.get();
    }

    long scavengerFailureCount() {
        return scavengerFailures.get();
    }

    /** 清理本地及存储层过期记录，返回本次清理数量。 */
    long sweepExpired() {
        long before = expiredSessions.get();
        long now = clock.getAsLong();
        purgeExpired(now);
        if (store != null) {
            try {
                expiredSessions.addAndGet(store.purgeExpired(now));
            } catch (IOException e) {
                throw new IllegalStateException("failed to purge persisted HTTP sessions", e);
            }
        }
        return expiredSessions.get() - before;
    }

    /** 停止定时任务；无存储时销毁 Session，有存储时尽力持久化。 */
    @Override
    public void close() {
        if (scavenger != null) scavenger.shutdownNow();
        for (Map.Entry<String, ManagedSession> entry : sessions.entrySet()) {
            ManagedSession session = entry.getValue();
            if (sessions.remove(entry.getKey(), session)) {
                capacity.release();
                if (store == null) session.invalidateState();
                else if (session.isValid()) {
                    session.willPassivate();
                    persist(session);
                }
            }
        }
        if (store != null) {
            try {
                store.close();
            } catch (IOException e) {
                throw new IllegalStateException("failed to close HTTP session store", e);
            }
        }
    }

    private void purgeExpired(long now) {
        for (ManagedSession session : sessions.values()) {
            if (session.isExpired(now)) {
                removeExpired(session);
            }
        }
    }

    private void removeExpired(ManagedSession session) {
        if (sessions.remove(session.id(), session)) {
            capacity.release();
            deleteStored(session.id(), session.storeVersion());
            session.invalidateState();
            expiredSessions.incrementAndGet();
        }
    }

    private void sweepExpiredSafely() {
        try {
            sweepExpired();
        } catch (RuntimeException | LinkageError ignored) {
            scavengerFailures.incrementAndGet();
        }
    }

    private String newSessionId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 从存储读取记录、检查过期、反序列化并占用本节点配额。 */
    private ManagedSession restore(String id) {
        final SessionRecord record;
        try {
            record = store.load(id);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load HTTP session", e);
        }
        if (record == null) return null;
        long now = clock.getAsLong();
        if (record.expiresAtMillis() <= now) {
            deleteStored(id, record.version());
            return null;
        }
        PersistedState state = decode(record.payload());
        if (!capacity.tryAcquire()) {
            purgeExpired(now);
            if (!capacity.tryAcquire()) return null;
        }
        ManagedSession restored = new ManagedSession(
                this, servletContext,
                events, id, state.creationTime, state.lastAccessedTime, state.expiryBaseTime,
                state.maxInactiveInterval, state.attributes, record.version());
        ManagedSession existing = sessions.putIfAbsent(id, restored);
        if (existing != null) {
            capacity.release();
            return existing;
        }
        restored.didActivate();
        return restored;
    }

    /** 序列化空闲 Session，以预期版本做乐观并发写入。 */
    private void persist(ManagedSession session) {
        PersistedState state = new PersistedState(
                session.snapshotCreationTime(), session.snapshotLastAccessedTime(),
                session.snapshotExpiryBaseTime(), session.getMaxInactiveInterval(),
                session.snapshotAttributes());
        byte[] payload = encode(state);
        long expected = session.storeVersion();
        try {
            long version = store.save(new SessionRecord(
                    session.id(), Math.max(0L, expected), session.expiresAtMillis(), payload), expected);
            if (version < 0L) {
                throw new IllegalStateException("concurrent HTTP session update: " + session.id());
            }
            session.storeVersion(version);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist HTTP session", e);
        }
    }

    private void deleteStored(String id, long expectedVersion) {
        if (store == null) return;
        try {
            store.delete(id, expectedVersion);
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete persisted HTTP session", e);
        }
    }

    private byte[] encode(PersistedState state) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ObjectOutputStream output = new ObjectOutputStream(bytes);
            output.writeObject(state);
            output.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "persistent HTTP session attributes must be Serializable", e);
        }
    }

    private PersistedState decode(byte[] payload) {
        try {
            ObjectInputStream input = new ApplicationObjectInputStream(
                    new ByteArrayInputStream(payload), applicationClassLoader);
            Object value = input.readObject();
            if (!(value instanceof PersistedState) || input.read() != -1) {
                throw new IOException("invalid persisted HTTP session payload");
            }
            input.close();
            return (PersistedState) value;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("failed to deserialize HTTP session", e);
        }
    }

    /** 存储载荷中的应用状态，不包含管理器、监听器和 ServletContext。 */
    private static final class PersistedState implements Serializable {
        /** Java 序列化格式版本。 */
        private static final long serialVersionUID = 1L;
        /** Session 创建时间，单位 Unix 毫秒。 */
        private final long creationTime;
        /** 上一次请求开始前记录的访问时间，单位 Unix 毫秒。 */
        private final long lastAccessedTime;
        /** 计算空闲超时的基准时间，单位 Unix 毫秒。 */
        private final long expiryBaseTime;
        /** 最大空闲时长，单位秒。 */
        private final int maxInactiveInterval;
        /** 业务 Session 属性；持久化要求其值可序列化。 */
        private final Map<String, Object> attributes;
        private PersistedState(
                long creationTime, long lastAccessedTime, long expiryBaseTime,
                int maxInactiveInterval, Map<String, Object> attributes) {
            this.creationTime = creationTime;
            this.lastAccessedTime = lastAccessedTime;
            this.expiryBaseTime = expiryBaseTime;
            this.maxInactiveInterval = maxInactiveInterval;
            this.attributes = attributes;
        }
    }

    /** 优先从应用类加载器解析 Session 中的业务类型。 */
    private static final class ApplicationObjectInputStream extends ObjectInputStream {
        /** 当前应用的类加载器。 */
        private final ClassLoader classLoader;

        private ApplicationObjectInputStream(ByteArrayInputStream input, ClassLoader classLoader)
                throws IOException {
            super(input);
            this.classLoader = classLoader;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor)
                throws IOException, ClassNotFoundException {
            try {
                return Class.forName(descriptor.getName(), false, classLoader);
            } catch (ClassNotFoundException ignored) {
                return super.resolveClass(descriptor);
            }
        }
    }

    private static MutableSessionCookieConfig defaultCookieConfig() {
        return new MutableSessionCookieConfig(() -> {
        });
    }
}
