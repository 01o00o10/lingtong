# 兼容范围与已知限制

## 当前目标

| 项目 | 目标/状态 |
| --- | --- |
| Java | Java 8 字节码，JDK 8/11/17 运行验证 |
| API 命名空间 | `javax.servlet`，不是 `jakarta.servlet` |
| Spring Boot | 2.7.x 嵌入式 Servlet 应用 |
| Servlet | 4.0 主要路径，尚未通过完整 TCK |
| WebSocket | 嵌入式 API、JSR 356、Spring WebSocket 主要路径 |
| HTTP | HTTP/1.1 与 HTTP/2 主要服务端路径 |
| TLS | JSSE；Java 8 ALPN 能力受所用 JDK/Provider 约束 |

## 明确不作承诺的范围

- Tomcat、TongWeb、JBoss 或 Undertow 私有 API 与内部配置
- JSP 与完整 Web Profile API 集合
- 完整 Servlet/JSR 356/Web Profile TCK 认证
- 国密算法、国密 TLS 互操作及商业密码合规认证
- 任意 Spring Boot 应用不经验证即可零风险平替
- 独立管理端原生 TLS、多用户身份源、完整细粒度 RBAC
- 完整服务发现、流量调度、自动故障转移和透明 Session 复制集群

## 关于测试

公开仓库包含生产源码和可运行示例，不包含维护者的私有测试套件与内部设计文档。
维护者发布前会运行单元、集成、协议、兼容和安全测试，但外部用户无法仅通过公开仓库
复现这些结果。任何认证或兼容声明都必须由可追溯的正式报告支持。

## 如何判断一个应用是否可迁移

只有同时满足以下条件，才能对某个具体应用得出迁移结论：

1. 不依赖容器私有 API，或已有明确替代实现。
2. 业务回归、协议一致性和错误路径全部通过。
3. 容量、延迟、长连接、慢客户端和资源上限满足门槛。
4. TLS、安全扫描、日志脱敏和权限配置通过审核。
5. 故障恢复、优雅停机、监控告警和回滚演练完成。

