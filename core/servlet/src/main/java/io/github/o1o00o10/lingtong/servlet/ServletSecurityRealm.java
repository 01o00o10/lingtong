/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.ServletException;

/** 根据用户名和凭据查询应用身份的可插拔认证边界。 */
public interface ServletSecurityRealm {
    ServletSecurityIdentity authenticate(String username, char[] password)
            throws ServletException;
}
