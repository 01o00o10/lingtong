/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用于确认 Spring MVC 请求已经由灵童容器接收和分发的示例控制器。 */
@RestController
public class ExampleController {

  /**
   * 返回固定文本，便于使用浏览器或 curl 验证运行结果。
   *
   * @return 示例运行状态
   */
  @GetMapping("/test")
  public String test() {
    return "LingTong Spring Boot example is running";
  }
}
