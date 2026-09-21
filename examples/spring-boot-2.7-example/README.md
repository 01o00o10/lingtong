# LingTong Spring Boot 2.7 Example

[中文](#中文) | [English](#english)

## 中文

这个示例排除了 Spring Boot 自带的 Tomcat，并使用灵童运行标准 Spring MVC
`@GetMapping` 接口。

### 首次运行

示例依赖同一源码仓库中的灵童 SNAPSHOT。首次克隆或修改灵童核心代码后，先在
仓库根目录安装最新制品：

```shell
mvn -B -ntp -DskipTests install
```

然后进入当前示例目录启动：

```shell
cd examples/spring-boot-2.7-example
mvn spring-boot:run
```

看到 `runtime started port=9999` 和 `Started LingTongExampleApplication` 后，通过
下面的命令验证：

```shell
curl http://127.0.0.1:9999/test
```

预期响应为：

```text
LingTong Spring Boot example is running
```

如果提示找不到 `io.github.01o00o10` 的 SNAPSHOT，请重新执行根目录的 `install`。
如果提示端口已被占用，可以在当前目录使用其他端口启动：

```shell
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080
```

## English

This example excludes Spring Boot's embedded Tomcat and runs a standard Spring
MVC `@GetMapping` endpoint on LingTong.

The example depends on LingTong SNAPSHOT artifacts from the same source tree.
After a fresh clone or a LingTong core change, install them from the repository
root first:

```shell
mvn -B -ntp -DskipTests install
```

Then start the application from this directory:

```shell
cd examples/spring-boot-2.7-example
mvn spring-boot:run
curl http://127.0.0.1:9999/test
```

If port `9999` is already in use, run with a different port:

```shell
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080
```
