/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 可信反向代理范围；未命中时不得采信转发头。 */
public final class ProxyConfig {
  /** 被允许提供 Forwarded/X-Forwarded-* 元数据的直连代理地址或 CIDR。 */
  private final List<String> trustedProxies;

  public ProxyConfig(List<String> trustedProxies) {
    if (trustedProxies == null) {
      throw new IllegalArgumentException("trusted proxies must not be null");
    }
    List<String> copy = new ArrayList<String>(trustedProxies.size());
    for (String value : trustedProxies) {
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException("trusted proxy entry must not be empty");
      }
      copy.add(value.trim());
    }
    this.trustedProxies = Collections.unmodifiableList(copy);
  }

  /** 默认不信任任何代理。 */
  public static ProxyConfig disabled() {
    return new ProxyConfig(Collections.<String>emptyList());
  }

  public boolean enabled() {
    return !trustedProxies.isEmpty();
  }

  public List<String> trustedProxies() {
    return trustedProxies;
  }
}
