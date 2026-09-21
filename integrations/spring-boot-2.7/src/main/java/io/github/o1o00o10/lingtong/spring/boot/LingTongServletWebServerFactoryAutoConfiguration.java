/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

import javax.servlet.Servlet;
import javax.servlet.ServletContext;

/** Supplies the Boot SPI adapter only when no other ServletWebServerFactory is present. */
/** 当 Boot Servlet 应用尚无 WebServerFactory 时注册 LingTong 工厂。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Servlet.class, LingTongServletWebServerFactory.class})
@EnableConfigurationProperties(LingTongServerProperties.class)
public class LingTongServletWebServerFactoryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ServletWebServerFactory.class)
    public LingTongServletWebServerFactory lingTongServletWebServerFactory(
            LingTongServerProperties properties) {
        LingTongServletWebServerFactory factory = new LingTongServletWebServerFactory();
        factory.setRequestHeaderTimeout(properties.getRequestHeaderTimeout());
        factory.setIdleTimeout(properties.getIdleTimeout());
        factory.setMaxRequestsPerConnection(properties.getMaxRequestsPerConnection());
        factory.setMaxHeaderCount(properties.getMaxHeaderCount());
        factory.setMaxRequestBytes(bytes(properties.getMaxRequestSize(), "max request size"));
        factory.setMaxTrailerBytes(bytes(properties.getMaxTrailerSize(), "max trailer size"));
        factory.setMaxTrailerCount(properties.getMaxTrailerCount());
        factory.setMaxParameterCount(properties.getMaxParameterCount());
        factory.setMaxParameterBytes(bytes(properties.getMaxParameterSize(), "max parameter size"));
        factory.setMaxCookieCount(properties.getMaxCookieCount());
        factory.setMaxCookieBytes(bytes(properties.getMaxCookieSize(), "max cookie size"));
        factory.setRequestBodyBufferBytes(bytes(properties.getRequestBodyBufferSize(), "request body buffer size"));
        factory.setRequestBodyLowWaterBytes(bytes(
                properties.getRequestBodyLowWaterSize(), "request body low water size"));
        factory.setResponseBodyBufferBytes(bytes(
                properties.getResponseBodyBufferSize(), "response body buffer size"));
        factory.setResponseBodyLowWaterBytes(bytes(
                properties.getResponseBodyLowWaterSize(), "response body low water size"));
        factory.setMaxResponseBytes(properties.getMaxResponseSize().toBytes());
        factory.setShutdownDrainTimeout(properties.getShutdownDrainTimeout());
        factory.setWorkerThreads(properties.getWorkerThreads());
        factory.setTrustedProxies(properties.getTrustedProxies());
        factory.setManagementToken(properties.getManagementToken());
        factory.setNodeId(properties.getNodeId());
        factory.setClusterPeers(properties.getClusterPeers());
        factory.setClusterHeartbeat(properties.getClusterHeartbeat());
        factory.setClusterFailureThreshold(properties.getClusterFailureThreshold());
        factory.setManagementBindAddress(properties.getManagementBindAddress());
        factory.setManagementPort(properties.getManagementPort());
        factory.setManagementOperatorToken(properties.getManagementOperatorToken());
        factory.setManagementAuditFile(properties.getManagementAuditFile());
        factory.setManagementAuditMaxBytes(bytes(
                properties.getManagementAuditMaxSize(), "management audit max size"));
        factory.setManagementAuditRetainedFiles(properties.getManagementAuditRetainedFiles());
        return factory;
    }

    @Bean
    public WebServerFactoryCustomizer<LingTongServletWebServerFactory>
            lingTongStandardServerPropertiesCustomizer(ServerProperties properties) {
        return factory -> {
            if (properties.getMaxHttpHeaderSize() != null) {
                factory.setMaxHeaderBytes(bytes(
                        properties.getMaxHttpHeaderSize(), "maximum HTTP header size"));
            }
        };
    }

    @Bean
    public WebServerFactoryCustomizer<LingTongServletWebServerFactory>
            lingTongConfigurationCompatibilityCustomizer(
                    ConfigurableEnvironment environment) {
        return new LingTongConfigurationCompatibilityCustomizer(environment);
    }

    @Bean
    @ConditionalOnClass(WebSocketConfigurer.class)
    public WebSocketConfigurer lingTongSpringWebSocketConfigurer(ServletContext servletContext) {
        return new LingTongSpringWebSocketConfigurer(servletContext);
    }

    private static int bytes(org.springframework.util.unit.DataSize value, String name) {
        if (value == null || value.toBytes() < 0L || value.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("LingTong " + name + " is outside the supported range");
        }
        return (int) value.toBytes();
    }
}
