# 运维与诊断

## 请求链路日志

Spring Boot 的 `application.yml` 可开启细粒度请求跟踪：

```yaml
logging:
  level:
    io.github.o1o00o10.lingtong.trace: DEBUG
    io.github.o1o00o10.lingtong.transport.nio.NioHttpServer: DEBUG
    io.github.o1o00o10.lingtong.servlet: DEBUG
```

链路日志使用同一个 trace 标识记录 transport、worker、kernel、路由、Filter、Servlet
和响应输出阶段，并包含相对耗时。只在短诊断窗口开启 DEBUG，收集后立即恢复；URL、
参数和业务日志可能含敏感数据，导出前必须脱敏。

## 健康与就绪

无需管理令牌的端点：

```text
GET /__lingtong/health
GET /__lingtong/ready
```

`health` 表示进程内运行时可响应；`ready` 还会检查应用是否接受新请求。开始排空后，
就绪端点应返回非就绪状态，可供负载均衡器摘除实例。

## 管理端

建议绑定回环地址并使用独立端口：

```yaml
server:
  lingtong:
    management-token: ${LINGTONG_READER_TOKEN}
    management-operator-token: ${LINGTONG_OPERATOR_TOKEN}
    management-bind-address: 127.0.0.1
    management-port: 19090
    management-audit-file: ./logs/lingtong-management-audit.log
```

读取接口使用 Bearer Token：

```shell
curl -H "Authorization: Bearer $LINGTONG_READER_TOKEN" \
  http://127.0.0.1:19090/__lingtong/manage/v1/runtime
```

可用路径：

| 路径 | 内容 |
| --- | --- |
| `/__lingtong/admin` | 内置管理页面 |
| `/__lingtong/manage/v1/heartbeat` | 节点标识、状态和运行时长 |
| `/__lingtong/manage/v1/runtime` | 运行时状态 |
| `/__lingtong/manage/v1/cluster` | 对端心跳观测 |
| `/__lingtong/manage/v1/threads` | 线程信息 |
| `/__lingtong/manage/v1/diagnostics` | 诊断快照 |
| `/__lingtong/manage/v1/audit` | 管理审计摘要 |
| `/__lingtong/manage/v1/metrics` | Prometheus 文本格式指标 |

## 排空实例

排空是写操作，需要 operator token 和确认头：

```shell
curl -i -X POST \
  -H "Authorization: Bearer $LINGTONG_OPERATOR_TOKEN" \
  -H "X-LingTong-Confirm: drain" \
  http://127.0.0.1:19090/__lingtong/manage/v1/actions/drain
```

管理端目前没有独立原生 TLS 能力。不要把它直接暴露到公网；使用回环/管理网绑定、
主机防火墙或经认证的反向代理，并轮换高强度令牌。

