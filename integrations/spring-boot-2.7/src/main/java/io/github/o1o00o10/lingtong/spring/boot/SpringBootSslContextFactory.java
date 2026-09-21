/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.SslStoreProvider;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/** 从 Spring Boot SSL 配置构建嵌入式监听器使用的 SSLContext。 */
final class SpringBootSslContextFactory {
    private SpringBootSslContextFactory() {
    }

    static SSLContext create(Ssl ssl, SslStoreProvider stores) throws Exception {
        KeyStore keyStore = stores.getKeyStore();
        if (keyStore == null) {
            throw new IllegalArgumentException("server.ssl requires key material");
        }
        char[] password = keyPassword(ssl, stores);
        KeyManager[] keyManagers;
        try {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            keyManagers = selectAlias(keyManagerFactory.getKeyManagers(),
                    keyStore, ssl.getKeyAlias());
        } finally {
            Arrays.fill(password, '\0');
        }

        KeyStore trustStore = stores.getTrustStore();
        TrustManager[] trustManagers = null;
        if (trustStore != null) {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            trustManagers = trustManagerFactory.getTrustManagers();
        }

        String protocol = ssl.getProtocol();
        SSLContext context = SSLContext.getInstance(
                protocol == null || protocol.trim().isEmpty() ? "TLS" : protocol.trim());
        context.init(keyManagers, trustManagers, null);
        return context;
    }

    private static char[] keyPassword(Ssl ssl, SslStoreProvider stores) {
        String password = stores.getKeyPassword();
        if (password == null) password = ssl.getKeyPassword();
        if (password == null) password = ssl.getKeyStorePassword();
        return password == null ? new char[0] : password.toCharArray();
    }

    private static KeyManager[] selectAlias(
            KeyManager[] managers, KeyStore keyStore, String alias) throws Exception {
        if (alias == null || alias.trim().isEmpty()) return managers;
        String selected = alias.trim();
        if (!keyStore.isKeyEntry(selected)) {
            throw new IllegalArgumentException("server.ssl.key-alias is not a key entry: " + selected);
        }
        KeyManager[] result = managers.clone();
        boolean wrapped = false;
        for (int i = 0; i < result.length; i++) {
            if (result[i] instanceof X509ExtendedKeyManager) {
                result[i] = new AliasKeyManager((X509ExtendedKeyManager) result[i], selected);
                wrapped = true;
            }
        }
        if (!wrapped) {
            throw new IllegalArgumentException(
                    "server.ssl.key-alias requires an X509 key manager");
        }
        return result;
    }

    /** 封装alias键管理器的状态与处理边界。 */
    private static final class AliasKeyManager extends X509ExtendedKeyManager {
        /** 委托对象。 */
        private final X509ExtendedKeyManager delegate;
        /** alias。 */
        /** 别名（alias）。 */
        private final String alias;

        private AliasKeyManager(X509ExtendedKeyManager delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override
        public String chooseEngineServerAlias(
                String keyType, Principal[] issuers, SSLEngine engine) {
            return supports(keyType, issuers) ? alias : null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return supports(keyType, issuers) ? alias : null;
        }

        private boolean supports(String keyType, Principal[] issuers) {
            String[] aliases = delegate.getServerAliases(keyType, issuers);
            if (aliases == null) return false;
            for (String candidate : aliases) {
                if (alias.equals(candidate)) return true;
            }
            return false;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(
                String[] keyTypes, Principal[] issuers, Socket socket) {
            return delegate.chooseClientAlias(keyTypes, issuers, socket);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return supports(keyType, issuers) ? new String[] {alias} : null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String selectedAlias) {
            return delegate.getCertificateChain(selectedAlias);
        }

        @Override
        public PrivateKey getPrivateKey(String selectedAlias) {
            return delegate.getPrivateKey(selectedAlias);
        }
    }
}
