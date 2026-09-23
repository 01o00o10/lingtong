/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 已认证主体及其角色的不可变视图，供 Servlet 安全检查使用。 */
public final class ServletSecurityIdentity {
  /** 主体。 */
  private final Principal principal;
  /** 角色集合。 */
  private final Set<String> roles;

  public ServletSecurityIdentity(String name, Set<String> roles) {
    if (name == null || name.trim().isEmpty() || roles == null) {
      throw new IllegalArgumentException("identity name and roles must not be empty");
    }
    final String principalName = name.trim();
    this.principal =
        new Principal() {
          @Override
          public String getName() {
            return principalName;
          }

          @Override
          public String toString() {
            return principalName;
          }
        };
    LinkedHashSet<String> copy = new LinkedHashSet<String>();
    for (String role : roles) {
      if (role == null || role.trim().isEmpty()) {
        throw new IllegalArgumentException("identity roles must not contain empty values");
      }
      copy.add(role.trim());
    }
    this.roles = Collections.unmodifiableSet(copy);
  }

  public Principal principal() {
    return principal;
  }

  public String name() {
    return principal.getName();
  }

  public Set<String> roles() {
    return roles;
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }
}
