# Spring Boot 2.7 集成

## Maven 依赖

先从 Web Starter 中排除 Tomcat，再添加灵童集成模块：

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

注意两个容易混淆的名字：Maven Group 是 `io.github.01o00o10`；Java 包名前缀是
`io.github.o1o00o10.lingtong`，包名首字符是字母 `o`。

## 业务代码

标准 Spring MVC 控制器不需要改写：

```java
@RestController
public class ExampleController {
    @GetMapping("/test")
    public String test() {
        return "running";
    }
}
```

入口类应位于控制器的同包或父包，或者显式指定扫描范围：

```java
@SpringBootApplication(scanBasePackages = "com.example")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## 基础配置

```yaml
server:
  address: 127.0.0.1
  port: 8080
  servlet:
    context-path: /app
    session:
      timeout: 30m
  compression:
    enabled: true
    min-response-size: 2KB
  lingtong:
    request-header-timeout: 10s
    idle-timeout: 30s
    worker-threads: 8
```

灵童复用 Spring Boot 的 `ServletWebServerFactory` 生命周期，所以
`SpringApplication.run(...)` 返回后，Web Server 会保持应用运行。若进程立即退出，
请检查应用是否被识别为 Servlet Web 应用、Tomcat 是否确实排除，以及启动异常是否
被上层脚本吞掉，详见[故障排查](Troubleshooting)。

## 兼容原则

标准 Servlet API 和常规 Spring MVC 应用是兼容目标。以下内容不能假设兼容：

- `org.apache.catalina.*`、Tomcat Valve、Realm、Connector 等私有 API
- 依赖 Tomcat 目录结构、JNDI 命名或专有系统属性的代码
- 尚未列入兼容矩阵的 Spring Boot 配置项
- JSP 或完整 Jakarta EE Web Profile

