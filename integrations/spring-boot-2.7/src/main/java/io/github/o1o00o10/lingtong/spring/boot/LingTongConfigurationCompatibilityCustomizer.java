/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.spring.boot;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.unit.DataSize;

/** Maps safe Tomcat aliases and rejects container-specific settings that would be ignored. */
/** 校验和映射 Spring Boot 容器配置，拒绝无法等价迁移的私有属性。 */
final class LingTongConfigurationCompatibilityCustomizer
    implements WebServerFactoryCustomizer<LingTongServletWebServerFactory> {
  /** tomcat。 */
  /** Tomcat 兼容（TOMCAT）。 */
  private static final String TOMCAT = "server.tomcat.";
  /** tomcataliases。 */
  /** Tomcat 兼容别名集合（TOMCAT_ALIASES）。 */
  private static final Set<String> TOMCAT_ALIASES =
      Collections.unmodifiableSet(
          new LinkedHashSet<String>(
              Arrays.asList(
                  "server.tomcat.connection-timeout",
                  "server.tomcat.keep-alive-timeout",
                  "server.tomcat.max-keep-alive-requests",
                  "server.tomcat.max-http-form-post-size",
                  "server.tomcat.threads.max")));
  /** foreignprefixes。 */
  /** 外部前缀集合（FOREIGN_PREFIXES）。 */
  private static final String[] FOREIGN_PREFIXES = {
    TOMCAT, "server.jetty.", "server.undertow.", "server.netty."
  };

  /** environment。 */
  /** 环境（environment）。 */
  private final ConfigurableEnvironment environment;
  /** binder。 */
  /** 绑定器（binder）。 */
  private final Binder binder;

  LingTongConfigurationCompatibilityCustomizer(ConfigurableEnvironment environment) {
    this.environment = environment;
    this.binder = Binder.get(environment);
  }

  @Override
  public void customize(LingTongServletWebServerFactory factory) {
    mapTomcatAliases(factory);
    Set<String> unsupported = configuredForeignProperties();
    unsupported.removeAll(TOMCAT_ALIASES);
    if (!unsupported.isEmpty()) {
      throw new WebServerException(
          "LingTong cannot apply container-specific properties "
              + unsupported
              + "; replace them with server.lingtong.* settings or remove them",
          null);
    }
  }

  private void mapTomcatAliases(LingTongServletWebServerFactory factory) {
    if (configured("server.tomcat.connection-timeout")
        && !configured("server.lingtong.request-header-timeout")) {
      factory.setRequestHeaderTimeout(bind("server.tomcat.connection-timeout", Duration.class));
    }
    if (configured("server.tomcat.keep-alive-timeout")
        && !configured("server.lingtong.idle-timeout")) {
      factory.setIdleTimeout(bind("server.tomcat.keep-alive-timeout", Duration.class));
    }
    if (configured("server.tomcat.max-keep-alive-requests")
        && !configured("server.lingtong.max-requests-per-connection")) {
      factory.setMaxRequestsPerConnection(
          bind("server.tomcat.max-keep-alive-requests", Integer.class));
    }
    if (configured("server.tomcat.max-http-form-post-size")
        && !configured("server.lingtong.max-parameter-size")) {
      factory.setMaxParameterBytes(
          bytes(
              bind("server.tomcat.max-http-form-post-size", DataSize.class),
              "server.tomcat.max-http-form-post-size"));
    }
    if (configured("server.tomcat.threads.max") && !configured("server.lingtong.worker-threads")) {
      factory.setWorkerThreads(bind("server.tomcat.threads.max", Integer.class));
    }
  }

  private Set<String> configuredForeignProperties() {
    Set<String> result = new LinkedHashSet<String>();
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (!(source instanceof EnumerablePropertySource<?>)) continue;
      for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
        if (hasForeignPrefix(name)) result.add(name);
      }
    }
    return result;
  }

  private static boolean hasForeignPrefix(String name) {
    for (String prefix : FOREIGN_PREFIXES) {
      if (name.startsWith(prefix)) return true;
    }
    return false;
  }

  private boolean configured(String name) {
    return binder.bind(name, Bindable.of(String.class)).isBound();
  }

  private <T> T bind(String name, Class<T> type) {
    return binder
        .bind(name, Bindable.of(type))
        .orElseThrow(
            () -> new WebServerException("cannot bind compatibility property " + name, null));
  }

  private static int bytes(DataSize size, String name) {
    long value = size.toBytes();
    if (value <= 0L || value > Integer.MAX_VALUE) {
      throw new WebServerException(name + " is outside LingTong's supported range", null);
    }
    return (int) value;
  }
}
