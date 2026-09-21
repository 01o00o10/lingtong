/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 汇总登录方式、角色引用和 URL/方法约束的应用安全策略。 */
public final class ServletSecurityPolicy {
    /** 封装认证方法的状态与处理边界。 */
    public enum AuthenticationMethod {
        /** 无。 */
        NONE,
        /** 基本认证。 */
        BASIC,
        /** 表单。 */
        FORM,
        /** 客户端cert。 */
        CLIENT_CERT
    }

    /** 封装授权的状态与处理边界。 */
    public enum Authorization {
        /** 允许。 */
        PERMIT,
        /** 拒绝。 */
        DENY,
        /** 角色集合。 */
        ROLES
    }

    /** 封装传输保障的状态与处理边界。 */
    public enum TransportGuarantee {
        /** 无。 */
        NONE,
        /** integral。 */
        /** 整数（INTEGRAL）。 */
        INTEGRAL,
        /** confidential。 */
        /** 机密标志（CONFIDENTIAL）。 */
        CONFIDENTIAL
    }

    /** 基本认证身份源名称。 */
    private final String basicRealmName;
    /** 认证方法。 */
    private final AuthenticationMethod authenticationMethod;
    /** 表单login页面。 */
    private final String formLoginPage;
    /** 表单错误页面。 */
    private final String formErrorPage;
    /** 角色集合。 */
    private final Set<String> roles;
    /** 按顺序保存的约束集合。 */
    private final List<Constraint> constraints;
    /** 按键索引的角色引用集合。 */
    private final Map<String, Map<String, String>> roleReferences;
    /** 拒绝未覆盖的HTTP方法集合，布尔标志。 */
    private final boolean denyUncoveredHttpMethods;

    private ServletSecurityPolicy(Builder builder) {
        basicRealmName = builder.basicRealmName;
        authenticationMethod = builder.authenticationMethod;
        formLoginPage = builder.formLoginPage;
        formErrorPage = builder.formErrorPage;
        roles = Collections.unmodifiableSet(new LinkedHashSet<String>(builder.roles));
        constraints = Collections.unmodifiableList(new ArrayList<Constraint>(builder.constraints));
        Map<String, Map<String, String>> references =
                new LinkedHashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : builder.roleReferences.entrySet()) {
            references.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(entry.getValue())));
        }
        roleReferences = Collections.unmodifiableMap(references);
        denyUncoveredHttpMethods = builder.denyUncoveredHttpMethods;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String basicRealmName() {
        return basicRealmName;
    }

    public AuthenticationMethod authenticationMethod() {
        return authenticationMethod;
    }

    public String formLoginPage() {
        return formLoginPage;
    }

    public String formErrorPage() {
        return formErrorPage;
    }

    public Set<String> roles() {
        return roles;
    }

    public List<Constraint> constraints() {
        return constraints;
    }

    public Map<String, String> roleReferences(String servletName) {
        Map<String, String> result = roleReferences.get(servletName);
        return result == null ? Collections.<String, String>emptyMap() : result;
    }

    public boolean denyUncoveredHttpMethods() {
        return denyUncoveredHttpMethods;
    }

    /** 封装约束的状态与处理边界。 */
    public static final class Constraint {
        /** 按顺序保存的模式集合。 */
        private final List<String> patterns;
        /** 方法集合。 */
        private final Set<String> methods;
        /** 已省略的方法集合。 */
        private final Set<String> omittedMethods;
        /** 授权。 */
        private final Authorization authorization;
        /** 角色集合。 */
        private final Set<String> roles;
        /** 传输保障。 */
        private final TransportGuarantee transportGuarantee;

        Constraint(
                Collection<String> patterns,
                Collection<String> methods,
                Collection<String> omittedMethods,
                Authorization authorization,
                Collection<String> roles,
                TransportGuarantee transportGuarantee) {
            if (patterns == null || patterns.isEmpty() || methods == null
                    || omittedMethods == null || authorization == null || roles == null
                    || transportGuarantee == null) {
                throw new IllegalArgumentException("security constraint values must not be null");
            }
            if (!methods.isEmpty() && !omittedMethods.isEmpty()) {
                throw new IllegalArgumentException(
                        "security constraint cannot combine methods and omitted methods");
            }
            List<String> checkedPatterns = new ArrayList<String>();
            for (String pattern : patterns) {
                new UrlPattern(pattern);
                checkedPatterns.add(pattern);
            }
            this.patterns = Collections.unmodifiableList(checkedPatterns);
            this.methods = immutableMethods(methods);
            this.omittedMethods = immutableMethods(omittedMethods);
            this.authorization = authorization;
            this.roles = immutableNames(roles, "security roles");
            this.transportGuarantee = transportGuarantee;
            if (authorization == Authorization.ROLES && this.roles.isEmpty()) {
                throw new IllegalArgumentException("role authorization requires at least one role");
            }
            if (authorization != Authorization.ROLES && !this.roles.isEmpty()) {
                throw new IllegalArgumentException("permit/deny constraints must not declare roles");
            }
        }

        public List<String> patterns() {
            return patterns;
        }

        public Set<String> methods() {
            return methods;
        }

        public Set<String> omittedMethods() {
            return omittedMethods;
        }

        public Authorization authorization() {
            return authorization;
        }

        public Set<String> roles() {
            return roles;
        }

        public TransportGuarantee transportGuarantee() {
            return transportGuarantee;
        }
    }

    /** 封装构建器的状态与处理边界。 */
    public static final class Builder {
        /** 基本认证身份源名称。 */
        private String basicRealmName;
        /** 认证方法。 */
        private AuthenticationMethod authenticationMethod = AuthenticationMethod.NONE;
        /** 表单login页面。 */
        private String formLoginPage;
        /** 表单错误页面。 */
        private String formErrorPage;
        /** 角色集合。 */
        private final Set<String> roles = new LinkedHashSet<String>();
        /** 按顺序保存的约束集合。 */
        private final List<Constraint> constraints = new ArrayList<Constraint>();
        /** 按键索引的角色引用集合。 */
        private final Map<String, Map<String, String>> roleReferences =
                new LinkedHashMap<String, Map<String, String>>();
        /** 拒绝未覆盖的HTTP方法集合，布尔标志。 */
        private boolean denyUncoveredHttpMethods;

        public Builder basicAuthentication(String realmName) {
            if (realmName == null || realmName.trim().isEmpty()
                    || realmName.indexOf('"') >= 0 || realmName.indexOf('\\') >= 0
                    || containsControl(realmName)) {
                throw new IllegalArgumentException("BASIC realm name is invalid");
            }
            basicRealmName = realmName.trim();
            authenticationMethod = AuthenticationMethod.BASIC;
            formLoginPage = null;
            formErrorPage = null;
            return this;
        }

        public Builder formAuthentication(String loginPage, String errorPage) {
            formLoginPage = requireApplicationPath(loginPage, "FORM login page");
            formErrorPage = requireApplicationPath(errorPage, "FORM error page");
            authenticationMethod = AuthenticationMethod.FORM;
            basicRealmName = null;
            return this;
        }

        public Builder clientCertificateAuthentication() {
            authenticationMethod = AuthenticationMethod.CLIENT_CERT;
            basicRealmName = null;
            formLoginPage = null;
            formErrorPage = null;
            return this;
        }

        public Builder role(String role) {
            roles.add(requireName(role, "security role"));
            return this;
        }

        public Builder roleReference(String servletName, String roleName, String roleLink) {
            String servlet = requireName(servletName, "Servlet name");
            String reference = requireName(roleName, "role reference");
            String linkedRole = requireName(roleLink, "role link");
            Map<String, String> mappings = roleReferences.get(servlet);
            if (mappings == null) {
                mappings = new LinkedHashMap<String, String>();
                roleReferences.put(servlet, mappings);
            }
            String previous = mappings.put(reference, linkedRole);
            if (previous != null && !previous.equals(linkedRole)) {
                throw new IllegalArgumentException(
                        "conflicting role reference " + reference + " for Servlet " + servlet);
            }
            return this;
        }

        public Builder constraint(
                Collection<String> patterns,
                Collection<String> methods,
                Collection<String> omittedMethods,
                Authorization authorization,
                Collection<String> allowedRoles,
                TransportGuarantee transportGuarantee) {
            constraints.add(new Constraint(patterns, methods, omittedMethods,
                    authorization, allowedRoles, transportGuarantee));
            return this;
        }

        public Builder denyUncoveredHttpMethods(boolean value) {
            denyUncoveredHttpMethods = value;
            return this;
        }

        public ServletSecurityPolicy build() {
            return new ServletSecurityPolicy(this);
        }
    }

    private static Set<String> immutableMethods(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (String value : values) {
            result.add(requireName(value, "HTTP method").toUpperCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> immutableNames(Collection<String> values, String type) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (String value : values) result.add(requireName(value, type));
        return Collections.unmodifiableSet(result);
    }

    private static String requireName(String value, String type) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(type + " must not be empty");
        }
        return value.trim();
    }

    private static String requireApplicationPath(String value, String type) {
        if (value == null || value.isEmpty() || value.charAt(0) != '/') {
            throw new IllegalArgumentException(type + " must start with '/'");
        }
        if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException(type + " must not contain a query or fragment");
        }
        return value;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }
}
