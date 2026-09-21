/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.SessionCookieConfig;

/** 启动注册阶段可写的 Session Cookie 配置，支持回滚快照。 */
final class MutableSessionCookieConfig implements SessionCookieConfig {
    /** 每次修改前检查当前是否仍在可注册阶段。 */
    private final Runnable mutationGuard;
    /** Session Cookie 名称。 */
    private String name = SessionManager.DEFAULT_COOKIE_NAME;
    /** 可选 Domain 属性。 */
    private String domain;
    /** Cookie Path，默认使用应用配置的 Context 路径。 */
    private String path;
    /** 可选 Comment 属性。 */
    private String comment;
    /** HttpOnly 开关，默认启用。 */
    private boolean httpOnly = true;
    /** Secure 开关；安全请求仍可由管理器额外强制启用。 */
    private boolean secure;
    /** Max-Age，单位秒；-1 表示会话 Cookie。 */
    private int maxAge = -1;
    /** SameSite 值，可为 Lax、Strict、None 或空值。 */
    private String sameSite;

    MutableSessionCookieConfig(Runnable mutationGuard) {
        this(mutationGuard, "/");
    }

    MutableSessionCookieConfig(Runnable mutationGuard, String defaultPath) {
        this(mutationGuard, defaultPath, null);
    }

    MutableSessionCookieConfig(Runnable mutationGuard, String defaultPath, String sameSite) {
        this.mutationGuard = mutationGuard;
        this.path = defaultPath;
        this.sameSite = sameSite;
    }

    @Override
    public void setName(String name) {
        mutate();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("session cookie name must not be empty");
        }
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setDomain(String domain) {
        mutate();
        this.domain = domain;
    }

    @Override
    public String getDomain() {
        return domain;
    }

    @Override
    public void setPath(String path) {
        mutate();
        this.path = path;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public void setComment(String comment) {
        mutate();
        this.comment = comment;
    }

    @Override
    public String getComment() {
        return comment;
    }

    @Override
    public void setHttpOnly(boolean httpOnly) {
        mutate();
        this.httpOnly = httpOnly;
    }

    @Override
    public boolean isHttpOnly() {
        return httpOnly;
    }

    @Override
    public void setSecure(boolean secure) {
        mutate();
        this.secure = secure;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public void setMaxAge(int maxAge) {
        mutate();
        this.maxAge = maxAge;
    }

    @Override
    public int getMaxAge() {
        return maxAge;
    }

    void setSameSite(String value) {
        mutate();
        if (value != null && !"Lax".equals(value)
                && !"Strict".equals(value) && !"None".equals(value)) {
            throw new IllegalArgumentException("invalid SameSite policy: " + value);
        }
        sameSite = value;
    }

    String getSameSite() {
        return sameSite;
    }

    /** 保存注册阶段配置，供失败回滚。 */
    State snapshot() {
        return new State(name, domain, path, comment, httpOnly, secure, maxAge, sameSite);
    }

    /** 失败回滚时恢复快照，不触发可写性检查。 */
    void restore(State state) {
        name = state.name;
        domain = state.domain;
        path = state.path;
        comment = state.comment;
        httpOnly = state.httpOnly;
        secure = state.secure;
        maxAge = state.maxAge;
        sameSite = state.sameSite;
    }

    private void mutate() {
        mutationGuard.run();
    }

    /** Cookie 配置的不可变回滚快照。 */
    static final class State {
        /** Cookie 名称。 */
        private final String name;
        /** 快照中的 Cookie 域名。 */
        private final String domain;
        /** 快照中的 Cookie 路径。 */
        private final String path;
        /** 快照中的 Cookie 备注。 */
        private final String comment;
        /** HttpOnly 开关。 */
        private final boolean httpOnly;
        /** Secure 开关。 */
        private final boolean secure;
        /** Max-Age 秒数。 */
        private final int maxAge;
        /** SameSite 策略。 */
        private final String sameSite;

        private State(
                String name,
                String domain,
                String path,
                String comment,
                boolean httpOnly,
                boolean secure,
                int maxAge,
                String sameSite) {
            this.name = name;
            this.domain = domain;
            this.path = path;
            this.comment = comment;
            this.httpOnly = httpOnly;
            this.secure = secure;
            this.maxAge = maxAge;
            this.sameSite = sameSite;
        }
    }
}
