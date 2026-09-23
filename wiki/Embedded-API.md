# 嵌入式 API

不使用 Spring Boot 时，可通过 `lingtong-embed` 组合运行时：

```xml
<dependency>
    <groupId>io.github.01o00o10</groupId>
    <artifactId>lingtong-embed</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

下面注册一个标准 Servlet：

```java
import io.github.o1o00o10.lingtong.api.LingTongRuntime;
import io.github.o1o00o10.lingtong.embed.LingTong;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class Main {
    public static void main(String[] args) throws Exception {
        LingTongRuntime runtime = LingTong.builder()
                .bindAddress("127.0.0.1")
                .port(8080)
                .servlet("hello", "/hello", new HttpServlet() {
                    @Override
                    protected void doGet(HttpServletRequest request,
                                         HttpServletResponse response) throws IOException {
                        response.setContentType("text/plain;charset=UTF-8");
                        response.getWriter().println("hello from LingTong");
                    }
                })
                .build();

        runtime.start();
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        Thread.currentThread().join();
    }
}
```

`build()` 只固化配置和装配组件，不打开监听端口；`start()` 才启动服务。调用方拥有
返回的运行时，并负责在退出时调用 `close()`。独立 `main` 程序还必须保留一个
非守护线程，示例中的 `join()` 就承担这一职责。

## 常用构建项

- 传输：`bindAddress`、`port`、`ioShards`、`idleTimeoutMillis`
- 容量：`maxHeaderBytes`、`maxBodyBytes`、`workerThreads`
- Servlet：`servlet`、`filter`、`listener`、`initializer`
- 静态资源与部署：`resourceRoot`、`webApplication`
- 协议：`http2Enabled`、`webSocket`、`maxWebSocketMessageBytes`
- TLS：`tls`、`tlsProtocols`、`tlsCipherSuites`、`tlsClientAuth`
- Session：`persistentSessions`、`distributedSessions`
- 运维：`management`、`managementPort`、`managementAudit`

Builder 在构建后不可重复修改或再次构建。建议每个监听实例创建独立 Builder，避免
在应用代码里共享可变装配状态。

