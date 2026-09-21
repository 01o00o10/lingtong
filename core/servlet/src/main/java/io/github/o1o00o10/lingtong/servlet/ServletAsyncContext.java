/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 管理一次 Servlet 异步处理的多轮 分派、超时、Listener 和终结状态。 */
final class ServletAsyncContext implements AsyncContext {
    /** 由应用提供的异步任务执行、重新分派与请求收尾边界。 */
    interface Lifecycle {
        void execute(Runnable task);

        void dispatch(ServletRequest request, ServletResponse response, String path) throws Exception;

        void finish(Throwable failure, Runnable listenerCompletion);
    }

    /** 未显式设置时采用的 30 秒异步超时。 */
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /** 保护异步周期、分派请求和终结状态的监视器。 */
    private final Object monitor = new Object();
    /** 发起异步处理时的容器原始请求。 */
    private final ServletHttpRequest originalRequest;
    /** 发起异步处理时的容器原始响应。 */
    private final ServletHttpResponse originalResponse;
    /** Servlet上下文。 */
    private final ServletContext servletContext;
    /** 调度器。 */
    private final ScheduledExecutorService scheduler;
    /** 生命周期。 */
    private final Lifecycle lifecycle;
    /** 按顺序保存的监听器集合。 */
    private final List<ListenerRegistration> listeners = new ArrayList<ListenerRegistration>();

    /** 当前异步周期向业务暴露的请求，可为包装对象。 */
    private ServletRequest suppliedRequest;
    /** 当前异步周期向业务暴露的响应，可为包装对象。 */
    private ServletResponse suppliedResponse;
    /** 默认分派路径。 */
    private String defaultDispatchPath;
    /** 请求的分派路径。 */
    private String requestedDispatchPath;
    /** 超时时长毫秒，单位为毫秒。 */
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    /** 当前异步周期注册的超时任务，终结或重新分派时取消。 */
    private ScheduledFuture<?> timeoutTask;
    /** true 表示当前 Servlet 分派调用栈尚未退出。 */
    private boolean dispatchInProgress = true;
    /** 异步已启动标志，布尔标志。 */
    private boolean asyncStarted;
    /** 分派请求的，布尔标志。 */
    private boolean dispatchRequested;
    /** 完成标志请求的，布尔标志。 */
    private boolean completeRequested;
    /** 请求已进入唯一的终结路径，禁止重复 完成标志/分派。 */
    private boolean terminal;
    /** 防止同一次失败向 A同步Listener 重复发送 onError。 */
    private boolean errorNotified;
    /** 终结流程需要传递给上层的失败原因。 */
    private Throwable terminalFailure;

    ServletAsyncContext(
            ServletHttpRequest originalRequest,
            ServletHttpResponse originalResponse,
            ServletContext servletContext,
            ScheduledExecutorService scheduler,
            Lifecycle lifecycle) {
        this.originalRequest = originalRequest;
        this.originalResponse = originalResponse;
        this.servletContext = servletContext;
        this.scheduler = scheduler;
        this.lifecycle = lifecycle;
    }

    /** 开始新异步周期，向上一周期监听器发送 onStartA同步。 */
    AsyncContext startCycle(ServletRequest request, ServletResponse response, String dispatchPath) {
        List<ListenerRegistration> previous;
        synchronized (monitor) {
            if (terminal || !dispatchInProgress || asyncStarted) {
                throw new IllegalStateException("asynchronous processing cannot be started in the current state");
            }
            previous = new ArrayList<ListenerRegistration>(listeners);
            listeners.clear();
            suppliedRequest = request;
            suppliedResponse = response;
            defaultDispatchPath = dispatchPath;
            requestedDispatchPath = null;
            dispatchRequested = false;
            completeRequested = false;
            terminalFailure = null;
            errorNotified = false;
            asyncStarted = true;
        }
        notifyListeners(previous, EventType.START, null);
        return this;
    }

    boolean isStarted() {
        synchronized (monitor) {
            return asyncStarted && !terminal;
        }
    }

    void executeIoCallback(Runnable task) {
        synchronized (monitor) {
            requireStarted();
        }
        lifecycle.execute(task);
    }

    void failIo(Throwable failure) {
        fail(failure);
    }

    /** 当前分派退出后，决定继续等待、执行下一次分派或完成请求。 */
    void exitDispatch(Throwable failure) {
        boolean submitDispatch = false;
        boolean finish = false;
        synchronized (monitor) {
            if (terminal) {
                return;
            }
            dispatchInProgress = false;
            if (failure == null) {
                if (completeRequested) {
                    finish = true;
                } else if (dispatchRequested) {
                    beginScheduledDispatch();
                    submitDispatch = true;
                } else if (asyncStarted) {
                    scheduleTimeout();
                } else {
                    finish = true;
                }
            }
        }
        if (failure != null) {
            fail(failure);
        } else if (submitDispatch) {
            submitDispatch();
        } else if (finish) {
            finish(null);
        }
    }

    @Override
    public ServletRequest getRequest() {
        synchronized (monitor) {
            requireActive();
            return suppliedRequest;
        }
    }

    @Override
    public ServletResponse getResponse() {
        synchronized (monitor) {
            requireActive();
            return suppliedResponse;
        }
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        synchronized (monitor) {
            requireActive();
            return suppliedRequest == originalRequest && suppliedResponse == originalResponse;
        }
    }

    @Override
    public void dispatch() {
        String path;
        synchronized (monitor) {
            requireStarted();
            path = defaultDispatchPath;
        }
        dispatch(path);
    }

    @Override
    public void dispatch(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException("async dispatch path must start with '/'");
        }
        boolean submitNow;
        synchronized (monitor) {
            requireStarted();
            if (dispatchRequested || completeRequested) {
                throw new IllegalStateException("async completion or dispatch has already been requested");
            }
            dispatchRequested = true;
            requestedDispatchPath = path;
            submitNow = !dispatchInProgress;
            if (submitNow) {
                beginScheduledDispatch();
            }
        }
        if (submitNow) {
            submitDispatch();
        }
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        if (context != servletContext) {
            throw new IllegalArgumentException("cross-context async dispatch is not available");
        }
        dispatch(path);
    }

    @Override
    public void complete() {
        boolean finishNow;
        synchronized (monitor) {
            requireStarted();
            if (dispatchRequested || completeRequested) {
                throw new IllegalStateException("async completion or dispatch has already been requested");
            }
            completeRequested = true;
            finishNow = !dispatchInProgress;
        }
        if (finishNow) {
            finish(null);
        }
    }

    @Override
    public void start(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("async task must not be null");
        }
        synchronized (monitor) {
            requireStarted();
        }
        try {
            lifecycle.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (Throwable failure) {
                        fail(failure);
                    }
                }
            });
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    @Override
    public void addListener(AsyncListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("async listener must not be null");
        }
        synchronized (monitor) {
            requireStarted();
            listeners.add(new ListenerRegistration(listener, suppliedRequest, suppliedResponse));
        }
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
        if (listener == null || request == null || response == null) {
            throw new IllegalArgumentException("async listener and supplied request/response must not be null");
        }
        synchronized (monitor) {
            requireStarted();
            listeners.add(new ListenerRegistration(listener, request, response));
        }
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> listenerClass) throws ServletException {
        if (listenerClass == null) {
            throw new IllegalArgumentException("async listener class must not be null");
        }
        try {
            return listenerClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new ServletException("cannot create async listener " + listenerClass.getName(), e);
        }
    }

    @Override
    public void setTimeout(long timeout) {
        synchronized (monitor) {
            requireStarted();
            timeoutMillis = timeout;
            if (!dispatchInProgress) {
                cancelTimeout();
                scheduleTimeout();
            }
        }
    }

    @Override
    public long getTimeout() {
        synchronized (monitor) {
            return timeoutMillis;
        }
    }

    private void beginScheduledDispatch() {
        cancelTimeout();
        dispatchRequested = false;
        asyncStarted = false;
        dispatchInProgress = true;
    }

    private void submitDispatch() {
        final ServletRequest request;
        final ServletResponse response;
        final String path;
        synchronized (monitor) {
            if (terminal) {
                return;
            }
            request = suppliedRequest;
            response = suppliedResponse;
            path = requestedDispatchPath;
        }
        try {
            lifecycle.execute(new Runnable() {
                @Override
                public void run() {
                    Throwable failure = null;
                    try {
                        lifecycle.dispatch(request, response, path);
                    } catch (Throwable e) {
                        failure = e;
                    }
                    exitDispatch(failure);
                }
            });
        } catch (RuntimeException e) {
            synchronized (monitor) {
                dispatchInProgress = false;
            }
            fail(e);
        }
    }

    private void scheduleTimeout() {
        if (timeoutMillis <= 0 || terminal || !asyncStarted) {
            return;
        }
        timeoutTask = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                submitTimeout();
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void submitTimeout() {
        synchronized (monitor) {
            if (terminal || !asyncStarted || dispatchInProgress) {
                return;
            }
            timeoutTask = null;
        }
        try {
            lifecycle.execute(new Runnable() {
                @Override
                public void run() {
                    timeoutOnWorker();
                }
            });
        } catch (RuntimeException e) {
            synchronized (monitor) {
                if (!terminal && asyncStarted && !dispatchInProgress) {
                    timeoutTask = scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            submitTimeout();
                        }
                    }, 10L, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void timeoutOnWorker() {
        List<ListenerRegistration> snapshot;
        synchronized (monitor) {
            if (terminal || !asyncStarted || dispatchInProgress) {
                return;
            }
            snapshot = new ArrayList<ListenerRegistration>(listeners);
        }
        notifyListeners(snapshot, EventType.TIMEOUT, null);
        boolean fallback;
        synchronized (monitor) {
            fallback = !terminal && !dispatchInProgress && !dispatchRequested && !completeRequested;
        }
        if (fallback) {
            finish(new SocketTimeoutException("asynchronous Servlet request timed out"));
        }
    }

    private void fail(Throwable failure) {
        List<ListenerRegistration> snapshot;
        synchronized (monitor) {
            if (terminal || errorNotified) {
                return;
            }
            errorNotified = true;
            snapshot = new ArrayList<ListenerRegistration>(listeners);
        }
        notifyListeners(snapshot, EventType.ERROR, failure);
        boolean finishNow = false;
        synchronized (monitor) {
            if (!terminal && !dispatchRequested && !completeRequested) {
                terminalFailure = failure;
                completeRequested = true;
                finishNow = !dispatchInProgress;
            }
        }
        if (finishNow) {
            finish(failure);
        }
    }

    void shutdown(Throwable failure) {
        List<ListenerRegistration> snapshot;
        synchronized (monitor) {
            if (terminal) {
                return;
            }
            snapshot = errorNotified
                    ? Collections.<ListenerRegistration>emptyList()
                    : new ArrayList<ListenerRegistration>(listeners);
            errorNotified = true;
            terminalFailure = failure;
            dispatchRequested = false;
            completeRequested = true;
        }
        notifyListeners(snapshot, EventType.ERROR, failure);
        finish(failure);
    }

    private void finish(Throwable failure) {
        final List<ListenerRegistration> snapshot;
        synchronized (monitor) {
            if (terminal) {
                return;
            }
            terminal = true;
            asyncStarted = false;
            dispatchInProgress = false;
            terminalFailure = failure == null ? terminalFailure : failure;
            cancelTimeout();
            snapshot = new ArrayList<ListenerRegistration>(listeners);
        }
        lifecycle.finish(terminalFailure, new Runnable() {
            @Override
            public void run() {
                notifyListeners(snapshot, EventType.COMPLETE, null);
            }
        });
    }

    private void notifyListeners(
            List<ListenerRegistration> registrations,
            EventType type,
            Throwable failure) {
        for (ListenerRegistration registration : registrations) {
            AsyncEvent event = new AsyncEvent(
                    this, registration.request, registration.response, failure);
            try {
                switch (type) {
                    case START:
                        registration.listener.onStartAsync(event);
                        break;
                    case TIMEOUT:
                        registration.listener.onTimeout(event);
                        break;
                    case ERROR:
                        registration.listener.onError(event);
                        break;
                    case COMPLETE:
                        registration.listener.onComplete(event);
                        break;
                    default:
                        throw new IllegalStateException("unknown async listener event");
                }
            } catch (IOException | RuntimeException ignored) {
                // Listener failures do not prevent the remaining async lifecycle callbacks.
            }
        }
    }

    private void requireStarted() {
        requireActive();
        if (!asyncStarted) {
            throw new IllegalStateException("asynchronous processing has not been started");
        }
    }

    private void requireActive() {
        if (terminal) {
            throw new IllegalStateException("asynchronous request has completed");
        }
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
    }

    /** 封装事件类型的状态与处理边界。 */
    private enum EventType {
        /** 起始。 */
        START,
        /** 超时时长。 */
        TIMEOUT,
        /** 错误。 */
        ERROR,
        /** 完成标志。 */
        COMPLETE
    }

    /** 封装监听器注册信息的状态与处理边界。 */
    private static final class ListenerRegistration {
        /** 监听器。 */
        private final AsyncListener listener;
        /** 请求。 */
        private final ServletRequest request;
        /** 响应。 */
        private final ServletResponse response;

        private ListenerRegistration(
                AsyncListener listener,
                ServletRequest request,
                ServletResponse response) {
            this.listener = listener;
            this.request = request;
            this.response = response;
        }
    }
}
