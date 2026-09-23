# 故障排查

## 启动后进程立即结束

1. 检查是否有完整异常堆栈，而不是只看最后一行日志。
2. 确认应用是 Servlet Web 应用，且存在 `spring-boot-starter-web`。
3. 确认加入了 `lingtong-spring-boot-2.7`，没有手动排除其自动配置。
4. 嵌入式 API 用户必须调用 `runtime.start()`，并自行保持非守护线程。
5. 检查端口占用、绑定地址、证书路径和配置校验失败。

## `@GetMapping` 返回 404

先确认控制器已被 Spring 扫描：启动类通常应在控制器同包或父包。再检查：

- `server.servlet.context-path` 是否增加了路径前缀
- 类级 `@RequestMapping` 与方法级路径是否拼接正确
- 请求方法是否为 GET
- 控制器是否带 `@RestController` 或 `@Controller`
- 是否存在 Spring Security、Filter 或错误页改写响应

开启 `io.github.o1o00o10.lingtong.trace=DEBUG` 后，关注
`servlet.context-miss`、`servlet.route-miss` 和 `servlet.route`。

## 找不到 SNAPSHOT 依赖

当前版本未发布到 Maven Central。回到灵童源码根目录执行：

```shell
mvn -B -ntp -DskipTests install
```

然后确认业务项目使用完全相同的版本 `0.1.0-SNAPSHOT`。

## 仍然启动了 Tomcat

执行 `mvn dependency:tree`，查找哪个依赖传递引入了
`spring-boot-starter-tomcat` 或 `tomcat-embed-*`，在对应依赖上增加 exclusion。仅在
灵童模块自身的 POM 中看不到 Tomcat，并不能证明业务应用的最终依赖树没有 Tomcat。

## HTTP/2 未协商成功

检查 `server.http2.enabled`、TLS 配置、客户端协议支持以及当前 JDK 的 ALPN 能力。
Java 8 的 ALPN 行为与具体发行版/Provider 有关，应在目标 JDK 和目标客户端上实测。

## 管理接口返回 401/404

- 401：检查 `Authorization: Bearer ...` 与 reader/operator token。
- 404：检查是否设置 `management-token`，以及是否启用了独立管理端口。
- 独立管理端口启用后，管理页面/API 不再从业务端口暴露。
- 排空操作还需要 operator 权限及 `X-LingTong-Confirm: drain`。

