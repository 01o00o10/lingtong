# 灵童（LingTong）Wiki

灵童是一个嵌入式优先的 Java Web 运行时。NIO 传输、HTTP/1.1、HTTP/2、
WebSocket 与 Servlet 容器核心均由项目实现，不依赖 Netty，也不内嵌 Tomcat、
Jetty 或 Undertow。

当前版本为 `0.1.0-SNAPSHOT`，面向 Java 8、`javax.servlet` 与 Spring Boot 2.7。

> **重要：当前版本尚未通过完整 Servlet、JSR 356 或 Web Profile TCK，也未获得
> 生产替代认证。** “可以启动示例”不等于“可以无条件替换 Tomcat/TongWeb”。
> 每个业务系统都必须完成自己的兼容性、压力、安全和故障恢复验收。

## 从这里开始

- [五分钟快速开始](Quick-Start)
- [Spring Boot 2.7 集成](Spring-Boot-2.7-Integration)
- [配置参考](Configuration-Reference)
- [从 Tomcat 迁移](Migration-from-Tomcat)
- [运维与诊断](Operations-and-Diagnostics)
- [兼容范围与已知限制](Compatibility-and-Limitations)

## 当前能力概览

| 范围 | 当前状态 |
| --- | --- |
| Java | 生成 Java 8 字节码；维护者在 JDK 8、11、17 上验证 |
| Servlet | Servlet 4.0 主要请求、过滤器、监听器、异步和 Session 路径 |
| Spring Boot | Spring Boot 2.7 嵌入式 WebServer 集成 |
| 协议 | HTTP/1.1、HTTP/2 主要服务端路径、WebSocket |
| 部署 | 嵌入式注册、Spring Boot、WAR/展开目录基础部署 |
| 运维 | 健康/就绪、Prometheus 文本指标、诊断、审计、排空 |
| 暂缓 | JSP、剩余 Web Profile API、完整 TCK、国密验证、商业平台认证 |

## 设计边界

灵童不是对现有容器源代码的重写。它采用传输、协议、执行域、Servlet、部署和
集成适配分层，让协议 I/O 与业务执行解耦，并把缓冲区所有权、背压、容量上限和
优雅停机作为核心约束。更多内容见[架构概览](Architecture-Overview)。

## 项目链接

- [GitHub 主仓库](https://github.com/01o00o10/lingtong)
- [问题反馈](https://github.com/01o00o10/lingtong/issues)
- [安全策略](https://github.com/01o00o10/lingtong/security/policy)
- [许可证说明](https://github.com/01o00o10/lingtong/blob/main/COMMERCIAL-LICENSE.md)

