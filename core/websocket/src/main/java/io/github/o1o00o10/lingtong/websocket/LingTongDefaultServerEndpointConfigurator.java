/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.websocket;

import java.util.Collections;
import java.util.List;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpointConfig;

/** Platform defaults used by JSR 356 Configurator delegation. */
/** 提供 JSR 356 服务端端点配置器的默认实例创建与协商行为。 */
public final class LingTongDefaultServerEndpointConfigurator
    extends ServerEndpointConfig.Configurator {
  @Override
  public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
    for (String candidate : requested) if (supported.contains(candidate)) return candidate;
    return "";
  }

  @Override
  public List<Extension> getNegotiatedExtensions(
      List<Extension> installed, List<Extension> requested) {
    for (Extension wanted : requested) {
      for (Extension available : installed) {
        if (wanted.getName().equalsIgnoreCase(available.getName())) {
          return Collections.singletonList(available);
        }
      }
    }
    return Collections.emptyList();
  }

  @Override
  public boolean checkOrigin(String originHeaderValue) {
    return true;
  }

  @Override
  public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
    try {
      return endpointClass.newInstance();
    } catch (IllegalAccessException e) {
      InstantiationException failure =
          new InstantiationException("cannot access endpoint " + endpointClass.getName());
      failure.initCause(e);
      throw failure;
    }
  }
}
