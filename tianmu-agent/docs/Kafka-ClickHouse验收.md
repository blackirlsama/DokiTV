# Kafka → ClickHouse 实际验收

日期：2026-10-24。环境：已部署 staging，MySQL 8.4、Redis 7.4、Kafka 3.9.1、ClickHouse 25.8.33.6；单节点服务、独立持久卷，Kafka/ClickHouse 无宿主机端口映射。正式业务身份与模型未切换，本次使用 demo 身份、脚本模型和 MySQL 演示指标，没有收费模型调用。

部署版本：`20261024T101511Z-7a7b8a7d6a07`。部署自检包含真实 API、FAQ、MySQL 指标证据、SSE 和 Kafka/ClickHouse 查询。

| 场景 | 实际结果 |
|---|---|
| 正常请求 | 查询视频 1001 昨天弹幕数，返回 128；证据 source=`mysql-demo-fixture`；任务成功，HTTP 提交至读取完成约 634 ms |
| span 入库 | 34 个同 traceId span 进入 ClickHouse；key 全部等于 traceId，仅分配到一个 Kafka 分区 |
| 重复消息 | 重放同一 eventId 两次，`trace_spans_current` 中仍只有一个逻辑事件 |
| 异常消息 | 非法 JSON 与非法 envelope 各一条进入 `trace_rejected`，原因分别为 PARSE_ERROR、INVALID_ENVELOPE |
| Kafka 停机 | API 请求仍成功，约 502 ms；本地暂存 40 条 span；恢复 broker 后本次任务的 34 个 span 到达 ClickHouse，pending 清零 |
| ClickHouse 停机 | API 请求仍成功，约 500 ms；exporter 获得 Kafka ACK，本地 pending 为 0；重启 ClickHouse 后继续消费，本次任务的 34 个 span 入库 |
| 验收结束 | 五个常驻服务健康；Kafka 三个分区的消费 lag 均为 0；本地 spool 无 pending |

正常请求 runId：`13ef5b57-e3e7-4dbb-93b3-1bcd5430d81f`。

可查询 Trace：

- 正常：`d30c7ada1fe8f4fb5d273310cd81c8f6`
- Kafka 故障期间：`a58c6858207d1317c9cbca9e0ce88492`
- ClickHouse 故障期间：`bcf740a0f5b6b844a93e7759987f0daf`

```sh
python3 deploy/manage.py trace \
  --env /opt/tianmu-staging/config/releases/20261024T101511Z-7a7b8a7d6a07/release.env \
  --trace-id d30c7ada1fe8f4fb5d273310cd81c8f6
```

查询包括通过 `run.trace_id` 关联的 SSE 投递 span；同 traceId 的 34 个 span 包括 3 次模型决策、2 次工具调用及入口、准备、上下文、Hook、最终交付等阶段。可选预处理分支的执行情况会影响其他请求的实际 span 数量，34 不是固定协议。

同时修复了取消线程上的 Trace 暂存问题：NIO FileChannel 写入可被取消中断关闭，导致取消分支的最终 span 入队失败。暂存改用同步文件流及 FD sync，保留中断标记和原子重命名协议，增加对应回归测试。

本地回归：Agent 共 132 项，0 失败、8 项外部存储测试按配置跳过；部署契约 9 项全通过。上述 Kafka/ClickHouse 场景另外在真实服务器执行，不以 MockProducer 代替。

这些结果验证低流量下的接入、恢复与查询语义，不代表已完成压力测试、主机断电恢复或多节点高可用验收。配置、保留与查询口径见 [部署说明](../../deploy/KAFKA_TRACE.md)。
