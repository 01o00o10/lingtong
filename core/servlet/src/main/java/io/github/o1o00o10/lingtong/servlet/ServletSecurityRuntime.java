/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.servlet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Activation-time compiled constraints and request-time authentication decisions. */
/** 在请求分派前执行认证、角色判断和传输保障检查。 */
final class ServletSecurityRuntime {
    /** 策略。 */
    private final ServletSecurityPolicy policy;
    /** 身份源。 */
    private final ServletSecurityRealm realm;
    /** 客户端证书身份源。 */
    private final ServletClientCertificateRealm clientCertificateRealm;
    /** 已声明的角色集合。 */
    private final Set<String> declaredRoles;
    /** 按顺序保存的约束集合。 */
    private final List<CompiledConstraint> constraints;

    ServletSecurityRuntime(
            ServletSecurityPolicy policy,
            ServletSecurityRealm realm,
            ServletClientCertificateRealm clientCertificateRealm,
            Set<String> declaredRoles,
            List<ServletSecurityPolicy.Constraint> additionalConstraints) {
        this.policy = policy;
        this.realm = realm;
        this.clientCertificateRealm = clientCertificateRealm;
        this.declaredRoles = Collections.unmodifiableSet(
                new LinkedHashSet<String>(declaredRoles));
        List<ServletSecurityPolicy.Constraint> allConstraints =
                new ArrayList<ServletSecurityPolicy.Constraint>(policy.constraints());
        allConstraints.addAll(additionalConstraints);
        this.constraints = compile(allConstraints);
        boolean authenticationRequired = policy.authenticationMethod()
                != ServletSecurityPolicy.AuthenticationMethod.NONE;
        for (CompiledConstraint constraint : constraints) {
            authenticationRequired |= constraint.authorization
                    == ServletSecurityPolicy.Authorization.ROLES;
        }
        if (authenticationRequired
                && policy.authenticationMethod()
                == ServletSecurityPolicy.AuthenticationMethod.CLIENT_CERT
                && clientCertificateRealm == null) {
            throw new IllegalStateException(
                    "CLIENT-CERT security policy requires a client-certificate realm");
        }
        if (authenticationRequired
                && policy.authenticationMethod()
                != ServletSecurityPolicy.AuthenticationMethod.CLIENT_CERT
                && realm == null) {
            throw new IllegalStateException(
                    "Servlet security policy requires a configured security realm");
        }
        if (!constraints.isEmpty() && policy.authenticationMethod()
                == ServletSecurityPolicy.AuthenticationMethod.NONE) {
            for (CompiledConstraint constraint : constraints) {
                if (constraint.authorization == ServletSecurityPolicy.Authorization.ROLES) {
                    throw new IllegalStateException(
                            "role constraints require a login configuration");
                }
            }
        }
    }

    Map<String, String> roleReferences(String servletName) {
        return policy.roleReferences(servletName);
    }

    boolean authorize(
            ServletHttpRequest request,
            HttpServletResponse response,
            String method,
            String path) throws ServletException {
        Decision decision = decision(method, path);
        if (decision.secureTransport && !request.isSecure()) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        if (decision.denied) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        if (decision.permittedWithoutAuthentication) {
            return true;
        }
        if (!request.authenticate(response)) {
            return false;
        }
        if (decision.anyAuthenticatedUser) {
            return true;
        }
        for (String role : decision.roles) {
            if (request.isUserInRole(role)) return true;
        }
        sendError(response, HttpServletResponse.SC_FORBIDDEN);
        return false;
    }

    boolean authenticate(ServletHttpRequest request, HttpServletResponse response)
            throws ServletException {
        if (request.securityIdentity() != null) return true;
        if (policy.authenticationMethod() == ServletSecurityPolicy.AuthenticationMethod.FORM) {
            request.saveFormRequest();
            redirect(response, request.getContextPath() + policy.formLoginPage());
            return false;
        }
        if (policy.authenticationMethod()
                == ServletSecurityPolicy.AuthenticationMethod.CLIENT_CERT) {
            io.github.o1o00o10.lingtong.http.TlsConnectionInfo tls = request.rawRequest().connectionInfo().tls();
            java.security.cert.X509Certificate[] chain = tls == null
                    ? new java.security.cert.X509Certificate[0] : tls.peerCertificates();
            if (chain.length > 0) {
                ServletSecurityIdentity identity = clientCertificateRealm.authenticate(chain);
                if (identity != null) {
                    request.securityIdentity(identity, HttpServletRequest.CLIENT_CERT_AUTH);
                    return true;
                }
            }
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (policy.authenticationMethod() != ServletSecurityPolicy.AuthenticationMethod.BASIC
                || realm == null) {
            throw new ServletException("username/password authentication is not configured");
        }
        Credentials credentials = credentials(request.getHeader("authorization"));
        if (credentials != null) {
            try {
                ServletSecurityIdentity identity = realm.authenticate(
                        credentials.username, credentials.password);
                if (identity != null) {
                    request.securityIdentity(identity, HttpServletRequest.BASIC_AUTH);
                    return true;
                }
            } finally {
                Arrays.fill(credentials.password, '\0');
            }
        }
        response.setHeader("WWW-Authenticate",
                "Basic realm=\"" + policy.basicRealmName() + "\"");
        sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    ServletSecurityIdentity login(String username, String password) throws ServletException {
        if ((policy.authenticationMethod() != ServletSecurityPolicy.AuthenticationMethod.BASIC
                && policy.authenticationMethod() != ServletSecurityPolicy.AuthenticationMethod.FORM)
                || realm == null) {
            throw new ServletException("username/password authentication is not configured");
        }
        if (username == null || password == null) {
            throw new ServletException("username and password must not be null");
        }
        char[] secret = password.toCharArray();
        try {
            ServletSecurityIdentity identity = realm.authenticate(username, secret);
            if (identity == null) throw new ServletException("authentication failed");
            return identity;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    String authenticationType() {
        return policy.authenticationMethod() == ServletSecurityPolicy.AuthenticationMethod.FORM
                ? HttpServletRequest.FORM_AUTH : HttpServletRequest.BASIC_AUTH;
    }

    boolean isFormLoginAction(String applicationPath) {
        return policy.authenticationMethod() == ServletSecurityPolicy.AuthenticationMethod.FORM
                && "/j_security_check".equals(applicationPath);
    }

    String processFormLogin(
            ServletHttpRequest request,
            HttpServletResponse response) throws ServletException {
        ServletSecuritySessionState.SavedRequest saved = request.savedFormRequest();
        if (!"POST".equalsIgnoreCase(request.getMethod()) || saved == null) {
            response.setStatus(HttpServletResponse.SC_OK);
            return policy.formErrorPage();
        }
        String username = request.getParameter("j_username");
        String password = request.getParameter("j_password");
        if (username == null || password == null) {
            response.setStatus(HttpServletResponse.SC_OK);
            return policy.formErrorPage();
        }
        char[] secret = password.toCharArray();
        try {
            ServletSecurityIdentity identity = realm.authenticate(username, secret);
            if (identity == null) {
                response.setStatus(HttpServletResponse.SC_OK);
                return policy.formErrorPage();
            }
            request.establishFormIdentity(identity);
            response.setStatus(HttpServletResponse.SC_SEE_OTHER);
            response.setHeader("Location", response.encodeRedirectURL(saved.redirectTarget()));
            return null;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    boolean isUserInRole(
            ServletSecurityIdentity identity,
            Map<String, String> references,
            String role) {
        if (identity == null || role == null || "*".equals(role)) return false;
        String linked = references.get(role);
        String effective = linked == null ? role : linked;
        if ("**".equals(effective) && !declaredRoles.contains("**")) return true;
        return identity.hasRole(effective);
    }

    private Decision decision(String method, String path) {
        List<CompiledConstraint> pathMatches = new ArrayList<CompiledConstraint>();
        int bestRank = -1;
        int bestLength = -1;
        for (CompiledConstraint constraint : constraints) {
            if (!constraint.pattern.matches(path)) continue;
            int rank = rank(constraint.pattern);
            int length = constraint.pattern.isPath() ? constraint.pattern.pathLength() : 0;
            if (rank > bestRank || (rank == bestRank && length > bestLength)) {
                pathMatches.clear();
                bestRank = rank;
                bestLength = length;
            }
            if (rank == bestRank && length == bestLength) pathMatches.add(constraint);
        }
        if (pathMatches.isEmpty()) return Decision.PERMIT;

        boolean matchedMethod = false;
        boolean permitted = false;
        boolean denied = false;
        boolean secure = false;
        boolean anyAuthenticated = false;
        Set<String> roles = new LinkedHashSet<String>();
        for (CompiledConstraint constraint : pathMatches) {
            if (!constraint.applies(method)) continue;
            matchedMethod = true;
            secure |= constraint.transportGuarantee
                    != ServletSecurityPolicy.TransportGuarantee.NONE;
            if (constraint.authorization == ServletSecurityPolicy.Authorization.PERMIT) {
                permitted = true;
            } else if (constraint.authorization == ServletSecurityPolicy.Authorization.DENY) {
                denied = true;
            } else {
                for (String role : constraint.roles) {
                    if ("**".equals(role) && !declaredRoles.contains("**")) {
                        anyAuthenticated = true;
                    } else if ("*".equals(role)) {
                        roles.addAll(declaredRoles);
                    } else {
                        roles.add(role);
                    }
                }
            }
        }
        if (!matchedMethod) {
            return policy.denyUncoveredHttpMethods() ? Decision.DENY : Decision.PERMIT;
        }
        return new Decision(permitted, denied, secure, anyAuthenticated, roles);
    }

    private static List<CompiledConstraint> compile(
            List<ServletSecurityPolicy.Constraint> source) {
        List<CompiledConstraint> result = new ArrayList<CompiledConstraint>();
        for (ServletSecurityPolicy.Constraint constraint : source) {
            for (String pattern : constraint.patterns()) {
                result.add(new CompiledConstraint(new UrlPattern(pattern), constraint));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static int rank(UrlPattern pattern) {
        if (pattern.isExact()) return 4;
        if (pattern.isPath()) return 3;
        if (pattern.isExtension()) return 2;
        return 1;
    }

    private static Credentials credentials(String authorization) {
        if (authorization == null) return null;
        int space = authorization.indexOf(' ');
        if (space <= 0 || !"basic".equalsIgnoreCase(authorization.substring(0, space))) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(authorization.substring(space + 1).trim());
            String value = new String(decoded, StandardCharsets.ISO_8859_1);
            int colon = value.indexOf(':');
            if (colon < 0) return null;
            return new Credentials(value.substring(0, colon),
                    value.substring(colon + 1).toCharArray());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void sendError(HttpServletResponse response, int status)
            throws ServletException {
        try {
            response.sendError(status);
        } catch (IOException e) {
            throw new ServletException("failed to send authentication response", e);
        }
    }

    private static void redirect(HttpServletResponse response, String location)
            throws ServletException {
        try {
            response.sendRedirect(response.encodeRedirectURL(location));
        } catch (IOException e) {
            throw new ServletException("failed to redirect to FORM login page", e);
        }
    }

    /** 封装credentials的状态与处理边界。 */
    private static final class Credentials {
        /** username。 */
        /** 用户名（username）。 */
        private final String username;
        /** 密码。 */
        private final char[] password;

        private Credentials(String username, char[] password) {
            this.username = username;
            this.password = password;
        }
    }

    /** 封装已编译的约束的状态与处理边界。 */
    private static final class CompiledConstraint {
        /** 模式。 */
        private final UrlPattern pattern;
        /** 方法集合。 */
        private final Set<String> methods;
        /** 已省略的方法集合。 */
        private final Set<String> omittedMethods;
        /** 授权。 */
        private final ServletSecurityPolicy.Authorization authorization;
        /** 角色集合。 */
        private final Set<String> roles;
        /** 传输保障。 */
        private final ServletSecurityPolicy.TransportGuarantee transportGuarantee;

        private CompiledConstraint(
                UrlPattern pattern, ServletSecurityPolicy.Constraint constraint) {
            this.pattern = pattern;
            methods = constraint.methods();
            omittedMethods = constraint.omittedMethods();
            authorization = constraint.authorization();
            roles = constraint.roles();
            transportGuarantee = constraint.transportGuarantee();
        }

        private boolean applies(String method) {
            String normalized = method.toUpperCase(Locale.ROOT);
            return methods.isEmpty()
                    ? !omittedMethods.contains(normalized) : methods.contains(normalized);
        }
    }

    /** 封装decision的状态与处理边界。 */
    private static final class Decision {
        /** 允许。 */
        private static final Decision PERMIT = new Decision(true, false, false,
                false, Collections.<String>emptySet());
        /** 拒绝。 */
        private static final Decision DENY = new Decision(false, true, false,
                false, Collections.<String>emptySet());
        /** permittedwithout认证，布尔标志。 */
        private final boolean permittedWithoutAuthentication;
        /** denied，布尔标志。 */
        private final boolean denied;
        /** secure传输，布尔标志。 */
        private final boolean secureTransport;
        /** anyauthenticated用户，布尔标志。 */
        private final boolean anyAuthenticatedUser;
        /** 角色集合。 */
        private final Set<String> roles;

        private Decision(
                boolean permittedWithoutAuthentication,
                boolean denied,
                boolean secureTransport,
                boolean anyAuthenticatedUser,
                Set<String> roles) {
            this.permittedWithoutAuthentication = permittedWithoutAuthentication;
            this.denied = denied;
            this.secureTransport = secureTransport;
            this.anyAuthenticatedUser = anyAuthenticatedUser;
            this.roles = roles;
        }
    }
}
