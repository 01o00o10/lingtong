/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.deployment;

/** WAR 校验、元数据编译或部署准备失败时向调用者暴露的受检异常。 */
public final class WebAppDeploymentException extends Exception {
  public WebAppDeploymentException(String message) {
    super(message);
  }

  public WebAppDeploymentException(String message, Throwable cause) {
    super(message, cause);
  }
}
