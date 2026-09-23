# 配置参考

## Spring Boot 通用配置

灵童集成会读取常用的 Spring Boot Server 配置，包括：

| 配置 | 说明 |
| --- | --- |
| `server.address` | 业务监听地址 |
| `server.port` | 业务监听端口 |
| `server.servlet.context-path` | Servlet 上下文路径 |
| `server.servlet.session.timeout` | Session 超时 |
| `server.servlet.session.cookie.*` | Session Cookie 主要属性 |
| `server.compression.*` | 响应压缩开关、阈值、类型和排除项 |
| `server.ssl.*` | TLS 密钥、协议、密码套件和客户端认证 |
| `server.http2.enabled` | 开启 HTTP/2 |
| `server.shutdown` | 立即或优雅停机 |

配置了但尚不支持的关键属性会尽量在启动阶段失败，而不是静默忽略。例如当前不支持
自定义 `server.server-header`。

## 灵童扩展配置

以下属性使用 `server.lingtong` 前缀：

| 属性 | 默认值 | 用途 |
| --- | ---: | --- |
| `request-header-timeout` | `10s` | 完成请求头读取的最长时间 |
| `idle-timeout` | `30s` | 空闲连接超时 |
| `max-requests-per-connection` | `1000` | 单连接请求数上限 |
| `max-header-count` | `100` | 请求头数量上限 |
| `max-request-size` | `8MB` | 请求大小上限 |
| `max-trailer-size` | `8KB` | Trailer 总大小上限 |
| `max-trailer-count` | `100` | Trailer 数量上限 |
| `max-parameter-count` | `1000` | 请求参数数量上限 |
| `max-parameter-size` | `1MB` | 参数数据大小上限 |
| `max-cookie-count` | `200` | Cookie 数量上限 |
| `max-cookie-size` | `8KB` | Cookie 数据大小上限 |
| `request-body-buffer-size` | `64KB` | 请求体流式邮箱高水位 |
| `request-body-low-water-size` | `32KB` | 请求体恢复读取的低水位 |
| `response-body-buffer-size` | `64KB` | 响应体流式邮箱高水位 |
| `response-body-low-water-size` | `32KB` | 响应体恢复写入的低水位 |
| `max-response-size` | `8MB` | 响应大小上限 |
| `shutdown-drain-timeout` | `5s` | 优雅停机排空时间 |
| `worker-threads` | 至少 2，通常为 CPU 数 | Servlet 工作线程数 |
| `trusted-proxies` | 空 | 信任的反向代理地址/CIDR |

管理与节点观测配置：

| 属性 | 默认值 | 用途 |
| --- | ---: | --- |
| `management-token` | 未设置 | 只读管理令牌；未设置时管理 API 不启用 |
| `management-operator-token` | 未设置 | 排空等操作令牌 |
| `management-bind-address` | `127.0.0.1` | 独立管理监听地址 |
| `management-port` | `-1` | 独立管理端口；负值表示不单独监听 |
| `management-audit-file` | 未设置 | 管理操作审计文件 |
| `management-audit-max-size` | `16MB` | 单个审计文件上限 |
| `management-audit-retained-files` | `5` | 轮转保留数 |
| `node-id` | 自动生成 | 节点标识 |
| `cluster-peers` | 空 | 用于节点心跳观测的对端地址 |
| `cluster-heartbeat` | `5s` | 心跳间隔 |
| `cluster-failure-threshold` | `3` | 连续失败判定阈值 |

`cluster-peers` 当前用于节点状态观测，不应理解为完整流量调度、自动故障转移或
Session 复制集群。

## 示例

```yaml
server:
  port: 8080
  shutdown: graceful
  lingtong:
    idle-timeout: 45s
    max-request-size: 16MB
    worker-threads: 16
    management-token: ${LINGTONG_READER_TOKEN}
    management-operator-token: ${LINGTONG_OPERATOR_TOKEN}
    management-bind-address: 127.0.0.1
    management-port: 19090
    management-audit-file: ./logs/lingtong-management-audit.log
```

令牌应通过环境变量或密钥系统注入，不要写入 Git、镜像或日志。

