# LingTong (灵童) Runtime

[English](README.md) | [简体中文](README.zh-CN.md)

LingTong, whose official Chinese name is **灵童**, is an embedded-first Java
web runtime. Its NIO transport, HTTP/1.1, HTTP/2, and WebSocket cores are
self-developed and do not use Netty or embed Tomcat, Jetty, or Undertow.

The current `0.1.0-SNAPSHOT` is an early development build targeting Java 8,
standard `javax.servlet` applications, and Spring Boot 2.7.

> **Project status:** early-stage and not certified as a production replacement.
> LingTong has not passed the complete Servlet, JSR 356, or Web Profile TCK.
> Validate every real application before replacing Tomcat or TongWeb.

## Compatibility Status

| Category | Current status |
| --- | --- |
| Java | Java 8 bytecode; public CI builds on JDK 8, 11, and 17 |
| Servlet | Major Servlet 4.0 paths are implemented; not TCK-certified |
| Spring Boot | Embedded integration for Spring Boot 2.7 |
| HTTP | HTTP/1.1 and major HTTP/2 server paths |
| WebSocket | Embedded API, JSR 356, and Spring WebSocket paths; not TCK-certified |
| Replacement scope | Migration target per application, not a blanket Tomcat/TongWeb compatibility claim |
| Deferred | JSP, remaining Web Profile APIs, complete TCK work, Guomi validation, and commercial platform certification |

Spring Boot 2.7 is retained as a Java 8 compatibility line. It has reached the
end of upstream open-source support, so applications must assess dependency
security and plan a future Java 17/Jakarta migration.

## Build From Source

Requirements: JDK 8 or later and Maven 3.8 or later.

```shell
mvn -B -ntp -DskipTests clean package
```

The public repository contains production source and buildable examples. Test
suites and design documents are maintained privately and are not included in
the public checkout. Maintainers run private unit, integration, compatibility,
protocol, and security tests before a release. Those private results are not
independently reproducible from this repository and do not constitute TCK
certification.

## Spring Boot Example

The public example excludes `spring-boot-starter-tomcat`, adds the LingTong
integration, and exposes a standard Spring MVC `@GetMapping` endpoint.

```shell
mvn -B -ntp -DskipTests install
cd examples/spring-boot-2.7-example
mvn spring-boot:run
```

In another terminal:

```shell
curl http://127.0.0.1:9999/test
```

Expected response:

```text
LingTong Spring Boot example is running
```

The complete example is in
[`examples/spring-boot-2.7-example`](examples/spring-boot-2.7-example). The
module README also documents dependency-resolution and port-conflict errors.

## Spring Boot Installation

Exclude Tomcat from the existing web starter and add LingTong. Controllers,
Filters, Listeners, and standard Servlet business code remain unchanged.

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

Until artifacts are published to a public Maven repository, install the source
tree into the local Maven repository with `mvn -B -ntp -DskipTests install`.

`io.github.01o00o10` is the Maven group associated with the GitHub account.
Public Java APIs use `io.github.o1o00o10.lingtong.*`, where the first character
is the letter `o`, not the digit zero.

## Diagnostics

LingTong uses JDK `java.util.logging`. In Spring Boot, enable a per-request
timeline temporarily with:

```yaml
logging:
  level:
    io.github.o1o00o10.lingtong.trace: DEBUG
    io.github.o1o00o10.lingtong.transport.nio.NioHttpServer: DEBUG
```

Detailed logging should only be enabled for short diagnostic windows. Spring,
application, and library logs may contain URLs, parameters, or response data
and require their own redaction and retention review.

## Documentation

Detailed configuration, migration, and usage guides will be published as blog
articles. This README intentionally keeps only the reproducible installation,
example, compatibility boundary, and security information. Blog links will be
added here after publication.

## Security And Support

Do not report undisclosed vulnerabilities in a public issue. Follow
[SECURITY.md](SECURITY.md) and use GitHub Private Vulnerability Reporting after
it is enabled for the repository. Community support is described in
[SUPPORT.md](SUPPORT.md).

Never attach private keys, access tokens, customer data, request bodies, or
complete unredacted diagnostic logs to a public issue.

## License And Contributions

Public source is licensed under `AGPL-3.0-only`. Organizations that cannot or
do not wish to comply with the AGPL may obtain a separate commercial license.
See [LICENSE](LICENSE) and [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md).

External contributions require acceptance of [CLA.md](CLA.md). See
[CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
before opening a pull request.

Project owner: [GitHub account 01o00o10](https://github.com/01o00o10)
