/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 按 Servlet 约定分发 Session 生命周期、属性和 ID 变更事件。 */
final class SessionEventDispatcher {
    /** Listener 回调失败时记录日志，不中断其余监听器。 */
    private static final Logger LOGGER = Logger.getLogger("io.github.o1o00o10.lingtong.servlet.session");
    /** 没有注册监听器时可复用的空分发器。 */
    private static final SessionEventDispatcher EMPTY = new SessionEventDispatcher(
            new HttpSessionListener[0],
            new HttpSessionAttributeListener[0],
            new HttpSessionIdListener[0]);

    /** Session 创建/销毁监听器；销毁按逆序回调。 */
    private final HttpSessionListener[] lifecycleListeners;
    /** 属性增删改监听器。 */
    private final HttpSessionAttributeListener[] attributeListeners;
    /** Session ID 轮换监听器。 */
    private final HttpSessionIdListener[] idListeners;

    SessionEventDispatcher(
            List<HttpSessionListener> lifecycleListeners,
            List<HttpSessionAttributeListener> attributeListeners,
            List<HttpSessionIdListener> idListeners) {
        this(
                lifecycleListeners.toArray(new HttpSessionListener[lifecycleListeners.size()]),
                attributeListeners.toArray(new HttpSessionAttributeListener[attributeListeners.size()]),
                idListeners.toArray(new HttpSessionIdListener[idListeners.size()]));
    }

    private SessionEventDispatcher(
            HttpSessionListener[] lifecycleListeners,
            HttpSessionAttributeListener[] attributeListeners,
            HttpSessionIdListener[] idListeners) {
        this.lifecycleListeners = lifecycleListeners;
        this.attributeListeners = attributeListeners;
        this.idListeners = idListeners;
    }

    static SessionEventDispatcher empty() {
        return EMPTY;
    }

    void created(ManagedSession session) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        for (HttpSessionListener listener : lifecycleListeners) {
            invoke(() -> listener.sessionCreated(event), "sessionCreated");
        }
    }

    void destroyed(ManagedSession session) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        for (int i = lifecycleListeners.length - 1; i >= 0; i--) {
            HttpSessionListener listener = lifecycleListeners[i];
            invoke(() -> listener.sessionDestroyed(event), "sessionDestroyed");
        }
    }

    void attributeAdded(ManagedSession session, String name, Object value) {
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(session, name, value);
        for (HttpSessionAttributeListener listener : attributeListeners) {
            invoke(() -> listener.attributeAdded(event), "attributeAdded");
        }
    }

    void attributeReplaced(ManagedSession session, String name, Object previous) {
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(session, name, previous);
        for (HttpSessionAttributeListener listener : attributeListeners) {
            invoke(() -> listener.attributeReplaced(event), "attributeReplaced");
        }
    }

    void attributeRemoved(ManagedSession session, String name, Object removed) {
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(session, name, removed);
        for (HttpSessionAttributeListener listener : attributeListeners) {
            invoke(() -> listener.attributeRemoved(event), "attributeRemoved");
        }
    }

    void idChanged(ManagedSession session, String previousId) {
        HttpSessionEvent event = new HttpSessionEvent(session);
        for (HttpSessionIdListener listener : idListeners) {
            invoke(() -> listener.sessionIdChanged(event, previousId), "sessionIdChanged");
        }
    }

    /** 隔离单个监听器的运行时异常，继续通知后续监听器。 */
    private void invoke(Runnable callback, String callbackName) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "HTTP session listener failed during " + callbackName, e);
        }
    }
}
