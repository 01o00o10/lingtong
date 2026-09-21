/*
 * Copyright (C) 2026 01o00o10
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.o1o00o10.lingtong.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 2.7 示例入口，运行时使用灵童而不是内嵌 Tomcat。
 */
@SpringBootApplication
public class LingTongExampleApplication {

    /**
     * 启动示例应用。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(LingTongExampleApplication.class, args);
    }
}
