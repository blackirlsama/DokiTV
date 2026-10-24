# Kafka → ClickHouse Trace

在独立 staging 栈中启用实际 Trace 传输和分析存储：

```text
Agent / OpenTelemetry
    ↓ 同步写入有界本地 spool
异步 exporter（Kafka ACK 后删除 spool 文件）
    ↓ key = traceId，headers 含 event_id / schema_version
Kafka topic Tianmu.agent.trace.v1（3 partitions）
    ↓ ClickHouse Kafka Engine（独立消费组）
物化视图 → trace_spans / trace_rejected
    ↓
trace_spans_current（FINAL 去重查询视图）
```

本地 spool 保留在原 `traces` 持久卷；Kafka、ClickHouse 使用各自持久卷。Kafka 和 ClickHouse 均不映射宿主机端口。当前是单节点 KRaft、单副本和单节点 ClickHouse，适合 staging；正式环境需要独立容量、权限隔离、TLS/SASL、备份、告警及高可用部署。

## 启用

在 Linux 服务器源码目录中执行，使用既有配置，不会旋转数据库密码：

```sh
python3 deploy/manage.py enable-kafka --env /opt/tianmu-staging/config/runtime.env
python3 deploy/manage.py build --env /opt/tianmu-staging/config/runtime.env
python3 deploy/manage.py up --env /opt/tianmu-staging/config/releases/RELEASE_ID/release.env
python3 deploy/manage.py verify --env /opt/tianmu-staging/config/releases/RELEASE_ID/release.env
```

`enable-kafka` 生成独立 ClickHouse 密码文件和稳定的 KRaft cluster ID。不得在复用 Kafka 数据卷时更改 cluster ID。构建解析并固定 Kafka、ClickHouse 镜像 digest，发布清单校验 Compose、SQL、XML 与脚本。推荐至少 4 GiB 内存；当前资源配额按低流量 staging 配置。

首次启动 ClickHouse 执行 `clickhouse-trace.sql`。后续 schema 升级必须显式迁移；修改初始化 SQL 不会自动升级既有表。更换 topic 也需要迁移 Kafka Engine，不能只修改环境变量。

## 查询

```sh
python3 deploy/manage.py trace \
  --env /opt/tianmu-staging/config/releases/RELEASE_ID/release.env \
  --trace-id TRACE_ID
```

该命令通过容器内客户端查询，不输出密码，包含通过 `run.trace_id` 关联的 SSE 投递 span。原始 envelope 与 attributes 保留 JSON，常用字段单独列出。默认不采集用户正文和模型思考正文。

常用 SQL（使用 `trace_spans_current` 获取去重结果）：

```sql
SELECT span_name, span_type, start_time, duration_ns / 1000000 AS duration_ms
FROM tianmu_trace.trace_spans_current
WHERE trace_id = 'TRACE_ID'
ORDER BY start_time;

SELECT span_type, count(), quantile(0.95)(duration_ns / 1000000) AS p95_ms
FROM tianmu_trace.trace_spans_current
WHERE start_time >= now() - INTERVAL 1 DAY
GROUP BY span_type;
```

同一 traceId 位于同一 Kafka 分区，但消息入队顺序不等于 span 开始时间，尤其在并行预处理时。还原链路使用 `parent_span_id` 与 `start_time`，不依赖 Kafka offset 作为业务时间排序。

## 投递、重复与保留

- exporter 后台等待 Kafka ACK；连接失败退避重试，spool 的默认上限为 10,000 条、64 MiB。超限会计入失败指标，不承诺无限缓存或绝不丢失。
- Kafka Engine 是至少一次消费；写入成功后提交 offset，故障窗口中允许重放。eventId 在 producer 重试时保持不变，ReplacingMergeTree 后台合并；需要即时准确计数时使用 `FINAL` 查询视图。
- `trace_rejected` 隔离解析失败、未知 schema、无效 ID、key 不匹配和无效时间戳的消息，不静默跳过。此表保留 7 天。
- Kafka topic 保留 7 天，并设置每分区约 256 MiB 上限，以先触发者为准；三个分区不等于整条 topic 只有 256 MiB。ClickHouse 正式 spans 按 span 时间 TTL 30 天，TTL 后台执行，不是即时删除。
- MySQL 审计仍随任务终态事务提交；Kafka Trace 不承担业务提交正确性的职责。旧文件出口的历史归档保留，启用 Kafka 不会自动回灌。

官方参考：[Kafka Docker](https://kafka.apache.org/39/getting-started/docker/)、[ClickHouse Kafka Engine 与消费语义](https://clickhouse.com/docs/integrations/kafka/kafka-table-engine)。
