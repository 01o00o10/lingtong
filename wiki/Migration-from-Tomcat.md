# 从 Tomcat 迁移

迁移目标是“标准业务代码尽量不改”，不是模拟所有 Tomcat 私有行为。

## 第一步：识别专有依赖

在业务仓库中搜索：

```shell
rg "org\.apache\.(catalina|coyote|tomcat)|TomcatServletWebServerFactory|Valve|Realm"
```

同时检查启动脚本、系统属性、JNDI、日志配置、证书路径、`server.tomcat.*` 和依赖树。
命中项需要替换为标准 Servlet/Spring 能力，或登记为迁移阻塞项。

## 第二步：替换依赖

从 `spring-boot-starter-web` 排除 `spring-boot-starter-tomcat`，加入
`lingtong-spring-boot-2.7`。完整片段见 [Spring Boot 2.7 集成](Spring-Boot-2.7-Integration)。

执行：

```shell
mvn dependency:tree
```

确认没有 Tomcat 嵌入式 JAR 被其他依赖重新带入。

## 第三步：建立行为基线

先在原 Tomcat 环境记录，再在灵童环境比对：

| 验收面 | 至少覆盖 |
| --- | --- |
| 路由 | context path、精确/前缀/扩展/默认映射、404/405 |
| 请求 | 参数、编码、Cookie、multipart、大请求、Trailer |
| 响应 | 状态、Header、Cookie、压缩、流式输出、错误页 |
| Servlet | Filter 顺序、Listener、Initializer、异步分派 |
| Session | 创建、续期、失效、Cookie 属性、重启恢复 |
| 协议 | keep-alive、HTTP/2、TLS、WebSocket |
| Spring | MVC、异常处理、静态资源、Actuator、WebSocket |
| 运维 | 健康、优雅停机、日志、指标、告警、回滚 |

## 第四步：分阶段放量

1. 本地与 CI 完成功能回归。
2. 在隔离环境执行容量、长连接、慢请求和异常流量测试。
3. 影子流量或小比例实例验证指标与响应差异。
4. 保留原容器镜像和配置，确保能快速回滚。
5. 达到业务验收门槛后再扩大流量。

## 不应跳过的判断

- TCK 尚未通过，标准边界仍可能存在遗漏。
- Spring Boot 2.7 已结束上游开源支持，应同时规划 Java 17/Jakarta 路线。
- JSP、Tomcat Valve/Realm/Connector 及容器私有管理能力不能直接迁移。
- 单个示例的 200 响应不能证明业务系统可平替。

