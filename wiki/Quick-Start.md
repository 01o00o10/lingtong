# 五分钟快速开始

## 环境要求

- JDK 8 或更高版本
- Maven 3.8 或更高版本
- 当前 SNAPSHOT 尚未发布到 Maven Central，需要先安装到本地仓库

## 构建灵童

```shell
git clone https://github.com/01o00o10/lingtong.git
cd lingtong
mvn -B -ntp -DskipTests install
```

公开仓库不包含维护者的私有测试套件，因此这里使用 `-DskipTests`。这只说明生产
源码可以完成编译与打包，不代表 TCK 或生产验收已通过。

## 运行公开示例

```shell
cd examples/spring-boot-2.7-example
mvn spring-boot:run
```

服务默认监听 `127.0.0.1:9999`：

```shell
curl -i http://127.0.0.1:9999/test
```

响应正文应为：

```text
LingTong Spring Boot example is running
```

临时修改端口：

```shell
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080
```

## 确认没有 Tomcat

```shell
mvn dependency:tree | grep -E "tomcat|lingtong"
```

依赖树中应包含 `lingtong-spring-boot-2.7`，不应包含
`spring-boot-starter-tomcat` 或 `tomcat-embed-*`。依赖树校验比只看启动日志更可靠。

## 下一步

把现有应用迁移过来之前，请先阅读 [Spring Boot 2.7 集成](Spring-Boot-2.7-Integration)
和[从 Tomcat 迁移](Migration-from-Tomcat)。

