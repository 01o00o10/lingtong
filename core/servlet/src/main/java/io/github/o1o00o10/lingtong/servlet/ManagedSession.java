/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;

/** 单个 HttpSession 的访问计数、属性与失效状态；复合状态受实例锁保护。 */
final class ManagedSession implements HttpSession {
  /** 按请求 ID 查找并登记访问时的结果。 */
  enum AccessResult {
    /** 成功登记访问。 */
    ACCESSED,
    /** 已超过最大空闲时长。 */
    EXPIRED,
    /** 请求携带的是轮换前的旧 ID。 */
    STALE_ID,
    /** Session 已被显式失效。 */
    INVALID
  }

  /** 管理本对象索引、容量与持久化的所属应用管理器。 */
  private final SessionManager manager;
  /** getServletContext() 返回的应用上下文。 */
  private final ServletContext servletContext;
  /** Session 及属性事件的监听器分发器。 */
  private final SessionEventDispatcher events;
  /** 创建时间，单位 Unix 毫秒。 */
  private final long creationTime;
  /** 按插入顺序保存的业务属性；访问需持有实例锁。 */
  private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

  /** 当前 Session ID，轮换时更新。 */
  private volatile String id;
  /** 上一次请求的访问时间，单位 Unix 毫秒。 */
  private volatile long lastAccessedTime;
  /** 当前访问周期起点，单位 Unix 毫秒。 */
  private volatile long lastAccessStarted;
  /** 最后一个请求结束时的空闲计时起点，单位 Unix 毫秒。 */
  private volatile long expiryBaseTime;
  /** 最大空闲时长，单位秒；非正值表示不过期。 */
  private volatile int maxInactiveInterval;
  /** false 表示已失效，不再允许访问属性。 */
  private volatile boolean valid = true;
  /** 新建后尚未被下一次请求重新访问的标记。 */
  private volatile boolean isNew = true;
  /** 正在使用该 Session 的请求数；活动期间不按空闲时间过期。 */
  private int activeRequests = 1;
  /** 登录后可复用的认证状态。 */
  private ServletSecuritySessionState securityState;
  /** FORM 认证前暂存的请求。 */
  private ServletSecuritySessionState.SavedRequest savedRequest;
  /** 持久化记录的乐观锁版本；-1 表示尚未写入。 */
  private long storeVersion = -1L;

  ManagedSession(
      SessionManager manager,
      ServletContext servletContext,
      SessionEventDispatcher events,
      String id,
      long now,
      int maxInactiveInterval) {
    this.manager = manager;
    this.servletContext = servletContext;
    this.events = events;
    this.id = id;
    this.creationTime = now;
    this.lastAccessedTime = now;
    this.lastAccessStarted = now;
    this.expiryBaseTime = now;
    this.maxInactiveInterval = maxInactiveInterval;
  }

  ManagedSession(
      SessionManager manager,
      ServletContext servletContext,
      SessionEventDispatcher events,
      String id,
      long creationTime,
      long lastAccessedTime,
      long expiryBaseTime,
      int maxInactiveInterval,
      Map<String, Object> restoredAttributes,
      long storeVersion) {
    this.manager = manager;
    this.servletContext = servletContext;
    this.events = events;
    this.id = id;
    this.creationTime = creationTime;
    this.lastAccessedTime = lastAccessedTime;
    this.lastAccessStarted = lastAccessedTime;
    this.expiryBaseTime = expiryBaseTime;
    this.maxInactiveInterval = maxInactiveInterval;
    this.attributes.putAll(restoredAttributes);
    this.activeRequests = 0;
    this.isNew = false;
    this.storeVersion = storeVersion;
  }

  /** 校验有效期及 ID 后登记访问，避免并发请求期间误判为过期。 */
  synchronized AccessResult beginAccess(String requestedId, long now) {
    if (!valid) {
      return AccessResult.INVALID;
    }
    if (!id.equals(requestedId)) {
      return AccessResult.STALE_ID;
    }
    if (isExpired(now)) {
      return AccessResult.EXPIRED;
    }
    lastAccessedTime = lastAccessStarted;
    lastAccessStarted = now;
    activeRequests++;
    isNew = false;
    return AccessResult.ACCESSED;
  }

  /** 最后一个请求结束后才重置空闲超时的起点。 */
  synchronized void endAccess(long now) {
    if (activeRequests > 0) {
      activeRequests--;
    }
    if (valid && activeRequests == 0) {
      expiryBaseTime = now;
    }
  }

  synchronized boolean isExpired(long now) {
    return valid
        && activeRequests == 0
        && maxInactiveInterval > 0
        && now - expiryBaseTime >= maxInactiveInterval * 1000L;
  }

  synchronized boolean isIdle() {
    return valid && activeRequests == 0;
  }

  synchronized long expiresAtMillis() {
    return maxInactiveInterval <= 0 ? Long.MAX_VALUE : expiryBaseTime + maxInactiveInterval * 1000L;
  }

  synchronized Map<String, Object> snapshotAttributes() {
    return new LinkedHashMap<String, Object>(attributes);
  }

  synchronized long storeVersion() {
    return storeVersion;
  }

  synchronized void storeVersion(long value) {
    storeVersion = value;
  }

  synchronized long snapshotCreationTime() {
    return creationTime;
  }

  synchronized long snapshotLastAccessedTime() {
    return lastAccessedTime;
  }

  synchronized long snapshotExpiryBaseTime() {
    return expiryBaseTime;
  }

  /** 持久化前通知具备激活监听能力的属性值。 */
  synchronized void willPassivate() {
    HttpSessionEvent event = new HttpSessionEvent(this);
    for (Object value : new ArrayList<Object>(attributes.values())) {
      if (value instanceof HttpSessionActivationListener) {
        ((HttpSessionActivationListener) value).sessionWillPassivate(event);
      }
    }
  }

  /** 从持久化记录恢复后通知属性值。 */
  synchronized void didActivate() {
    HttpSessionEvent event = new HttpSessionEvent(this);
    for (Object value : new ArrayList<Object>(attributes.values())) {
      if (value instanceof HttpSessionActivationListener) {
        ((HttpSessionActivationListener) value).sessionDidActivate(event);
      }
    }
  }

  String id() {
    return id;
  }

  void created() {
    events.created(this);
  }

  void idChanged(String previousId) {
    events.idChanged(this, previousId);
  }

  void changeId(String replacement) {
    id = replacement;
  }

  void requireValid() {
    if (!valid) {
      throw new IllegalStateException("HTTP session has been invalidated");
    }
  }

  boolean isValid() {
    return valid;
  }

  /** 原子标记失效并清空属性，再向 Listener 派发销毁和解绑事件。 */
  void invalidateState() {
    List<Map.Entry<String, Object>> removed;
    synchronized (this) {
      if (!valid) {
        return;
      }
      valid = false;
      removed = new ArrayList<Map.Entry<String, Object>>(attributes.entrySet());
      attributes.clear();
      securityState = null;
      savedRequest = null;
      activeRequests = 0;
    }
    events.destroyed(this);
    for (Map.Entry<String, Object> entry : removed) {
      valueUnbound(entry.getKey(), entry.getValue());
      events.attributeRemoved(this, entry.getKey(), entry.getValue());
    }
  }

  @Override
  public long getCreationTime() {
    requireValid();
    return creationTime;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public long getLastAccessedTime() {
    requireValid();
    return lastAccessedTime;
  }

  @Override
  public ServletContext getServletContext() {
    return servletContext;
  }

  @Override
  public void setMaxInactiveInterval(int interval) {
    maxInactiveInterval = interval;
  }

  @Override
  public int getMaxInactiveInterval() {
    return maxInactiveInterval;
  }

  @Deprecated
  @Override
  public HttpSessionContext getSessionContext() {
    return null;
  }

  @Override
  public synchronized Object getAttribute(String name) {
    requireValid();
    return attributes.get(name);
  }

  @Deprecated
  @Override
  public Object getValue(String name) {
    return getAttribute(name);
  }

  @Override
  public synchronized Enumeration<String> getAttributeNames() {
    requireValid();
    return Collections.enumeration(new ArrayList<String>(attributes.keySet()));
  }

  @Deprecated
  @Override
  public synchronized String[] getValueNames() {
    requireValid();
    return attributes.keySet().toArray(new String[attributes.size()]);
  }

  @Override
  /** 替换属性时先解绑旧值，再绑定新值并通知属性监听器。 */
  public synchronized void setAttribute(String name, Object value) {
    requireName(name);
    if (value == null) {
      removeAttribute(name);
      return;
    }
    requireValid();
    Object previous = attributes.put(name, value);
    if (previous != null) {
      valueUnbound(name, previous);
    }
    valueBound(name, value);
    if (previous == null) {
      events.attributeAdded(this, name, value);
    } else {
      events.attributeReplaced(this, name, previous);
    }
  }

  @Deprecated
  @Override
  public void putValue(String name, Object value) {
    setAttribute(name, value);
  }

  @Override
  public synchronized void removeAttribute(String name) {
    requireName(name);
    requireValid();
    Object removed = attributes.remove(name);
    if (removed != null) {
      valueUnbound(name, removed);
      events.attributeRemoved(this, name, removed);
    }
  }

  @Deprecated
  @Override
  public void removeValue(String name) {
    removeAttribute(name);
  }

  @Override
  public synchronized void invalidate() {
    requireValid();
    manager.invalidate(this);
  }

  synchronized ServletSecuritySessionState securityState() {
    return valid ? securityState : null;
  }

  synchronized void securityState(ServletSecuritySessionState value) {
    requireValid();
    securityState = value;
  }

  synchronized void clearSecurityState() {
    if (valid) {
      securityState = null;
      savedRequest = null;
    }
  }

  synchronized void saveRequest(ServletSecuritySessionState.SavedRequest value) {
    requireValid();
    savedRequest = value;
  }

  synchronized ServletSecuritySessionState.SavedRequest savedRequest() {
    return valid ? savedRequest : null;
  }

  /** 仅在登录回跳 URI 与查询串匹配时消费原始请求一次。 */
  synchronized ServletSecuritySessionState.SavedRequest consumeSavedRequest(
      String requestUri, String queryString) {
    if (!valid
        || securityState == null
        || savedRequest == null
        || !savedRequest.matches(requestUri, queryString)) {
      return null;
    }
    ServletSecuritySessionState.SavedRequest result = savedRequest;
    savedRequest = null;
    return result;
  }

  @Override
  public boolean isNew() {
    requireValid();
    return isNew;
  }

  private void valueBound(String name, Object value) {
    if (value instanceof HttpSessionBindingListener) {
      ((HttpSessionBindingListener) value)
          .valueBound(new HttpSessionBindingEvent(this, name, value));
    }
  }

  private void valueUnbound(String name, Object value) {
    if (value instanceof HttpSessionBindingListener) {
      ((HttpSessionBindingListener) value)
          .valueUnbound(new HttpSessionBindingEvent(this, name, value));
    }
  }

  private void requireName(String name) {
    if (name == null) {
      throw new IllegalArgumentException("HTTP session attribute name must not be null");
    }
  }
}
