# LingTong（灵童）Runtime

[English](README.md) | [简体中文](README.zh-CN.md)

LingTong 的正式中文名称是 **灵童**。灵童是一个嵌入式优先的 Java Web
运行时，其 NIO 传输、HTTP/1.1、HTTP/2 和 WebSocket 核心均为自研实现，
不使用 Netty，也不内嵌 Tomcat、Jetty 或 Undertow。

当前 `0.1.0-SNAPSHOT` 是早期研发版本，目标是支持 Java 8、标准
`javax.servlet` 应用和 Spring Boot 2.7。

> **项目状态：早期版本，尚未获得生产替代认证。** 灵童尚未通过完整的
> Servlet、JSR 356 或 Web Profile TCK。替换 Tomcat 或 TongWeb 前，必须针对
> 每个真实业务应用单独验证。

## 兼容状态

| 类别 | 当前状态 |
| --- | --- |
| Java | 生成 Java 8 字节码；公开 CI 在 JDK 8、11、17 上构建 |
| Servlet | 已实现 Servlet 4.0 主要路径，尚未通过 TCK |
| Spring Boot | 已实现 Spring Boot 2.7 嵌入式集成 |
| HTTP | 已实现 HTTP/1.1 和主要 HTTP/2 服务端路径 |
| WebSocket | 已实现嵌入式 API、JSR 356 和 Spring WebSocket 主要路径，尚未通过 TCK |
| 替代范围 | 只能按应用迁移验收，不能作 Tomcat/TongWeb 全量兼容声明 |
| 暂缓范围 | JSP、剩余 Web Profile API、完整 TCK、国密验证和商业平台认证 |

Spring Boot 2.7 作为 Java 8 兼容线保留。该版本已经结束上游开源支持，业务应用
需要自行评估依赖安全风险，并规划后续 Java 17/Jakarta 迁移路线。

## 从源码构建

要求 JDK 8 或更高版本、Maven 3.8 或更高版本：

```shell
mvn -B -ntp -DskipTests clean package
```

公开仓库只包含生产源码和可构建示例，不公开测试套件和设计文档。维护者会在版本
发布前执行私有单元测试、集成测试、兼容性测试、协议测试和安全测试。这些私有测试
结果无法通过公开仓库独立复现，也不代表已经通过 TCK 认证。

## Spring Boot 示例

公开示例排除了 `spring-boot-starter-tomcat`，引入灵童集成，并提供一个标准
Spring MVC `@GetMapping` 接口。

```shell
mvn -B -ntp -DskipTests install
cd examples/spring-boot-2.7-example
mvn spring-boot:run
```

在另一个终端执行：

```shell
curl http://127.0.0.1:9999/test
```

预期响应：

```text
LingTong Spring Boot example is running
```

完整示例位于
[`examples/spring-boot-2.7-example`](examples/spring-boot-2.7-example)。该模块的
README 还列出了依赖解析失败和端口冲突的处理方法。

## Spring Boot 安装

从原有 Web Starter 中排除 Tomcat，再添加灵童集成。Controller、Filter、
Listener 和标准 Servlet 业务代码不需要修改。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-tomcat</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>io.github.01o00o10</groupId>
    <artifactId>lingtong-spring-boot-2.7</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

在制品发布到公共 Maven 仓库之前，先在灵童源码根目录执行
`mvn -B -ntp -DskipTests install`，将快照安装到本地 Maven 仓库。

`io.github.01o00o10` 是与 GitHub 账号对应的 Maven Group。公开 Java API 使用
`io.github.o1o00o10.lingtong.*` 包名前缀，其中第一个字符是字母 `o`，不是数字零。

## 诊断日志

灵童使用 JDK `java.util.logging`。Spring Boot 中可以临时开启单请求链路日志：

```yaml
logging:
  level:
    io.github.o1o00o10.lingtong.trace: DEBUG
    io.github.o1o00o10.lingtong.transport.nio.NioHttpServer: DEBUG
```

详细日志只应在短时间诊断窗口内开启。Spring、业务代码和其他依赖的日志可能包含
URL、参数或响应数据，需要单独评估脱敏和保留策略。

## 使用文档

完整使用文档见 [GitHub Wiki](https://github.com/01o00o10/lingtong/wiki)，包括快速
开始、Spring Boot 集成、配置参考、架构、Tomcat 迁移、诊断、安全和故障排查。
Wiki 的可审查源文件保存在 [wiki/](wiki/)；发布方法见
[WIKI-PUBLISHING.md](WIKI-PUBLISHING.md)。

## 安全与支持

不要通过公开 Issue 报告尚未披露的漏洞。请遵循 [SECURITY.md](SECURITY.md)，并在
仓库启用后使用 GitHub 私密漏洞报告。社区支持范围见 [SUPPORT.md](SUPPORT.md)。

不要在公开 Issue 中附加私钥、访问令牌、客户数据、请求正文或完整且未经脱敏的
诊断日志。

## 许可与贡献

公开源码使用 `AGPL-3.0-only`。无法或不希望履行 AGPL 义务的组织，可以向版权
所有者购买单独的商业许可证。详见 [LICENSE](LICENSE) 和
[COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md)。

外部贡献必须接受 [CLA.md](CLA.md)。提交 Pull Request 前请阅读
[CONTRIBUTING.md](CONTRIBUTING.md) 和 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

项目所有者：[GitHub 账号 01o00o10](https://github.com/01o00o10)
