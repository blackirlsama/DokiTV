# 换电脑启动与开发接续

更新：2026-10-24。仓库为 `blackirlsama/Tianmu`，主分支 `master`。请从仓库根目录执行以下命令。

## 1. 最小启动：无需 Key 和中间件

安装 JDK 17，确认 `JAVA_HOME` 指向 JDK（包含 javac），然后：

```sh
git clone https://github.com/blackirlsama/Tianmu.git
cd Tianmu
```

Windows PowerShell：

```powershell
.\mvnw.cmd -B -f tianmu-agent/pom.xml package
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

Linux/macOS：

```sh
sh ./mvnw -B -f tianmu-agent/pom.xml package
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

也可以使用已安装的 Maven：`mvn -B -f tianmu-agent/pom.xml package`。首次构建需要联网。不要直接打包根 pom 来启动 Agent；根工程是原视频平台后端，Agent 有独立的 pom。

默认监听 `127.0.0.1:8201`，启用离线 demo：脚本模型、演示身份、内存运行记录、文件 Trace。没有真实模型调用；指标为固定演示值。配置或服务数据不会随 git clone 复制。

另开 PowerShell 验证：

```powershell
$headers = @{ Authorization = 'Bearer demo-alice' }
$body = @{ message = '查询视频1001昨天的弹幕数' } | ConvertTo-Json -Compress
$created = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8201/api/agent/runs' -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod -Uri "http://127.0.0.1:8201/api/agent/runs/$($created.runId)" -Headers $headers
curl.exe -N -H 'Authorization: Bearer demo-alice' "http://127.0.0.1:8201/api/agent/runs/$($created.runId)/events"
```

创建请求异步返回 202；如查询时仍为 QUEUED/RUNNING，稍后重查。预期最终 SUCCEEDED，回答标注模拟数据 128，并带 evidenceIds。

## 2. 完整基础设施

需要独立 Linux Docker 主机，按 [部署操作](../deploy/README.md) 初始化配置、构建并启动。启用 Kafka/ClickHouse 使用 [Trace 部署](../deploy/KAFKA_TRACE.md) 中的 enable-kafka 流程。

- MySQL 是正式记录来源；Redis 缓存有界近期历史，故障时回源 MySQL。
- `demo,persistent` 读取 MySQL `demo_video_metric`，并保存任务、消息、证据、审计和 outbox。
- V2 的演示指标目前仅预置 2026-10-22、2026-10-23，视频 1001/1002。超过这些日期没有记录会失败；示例“昨天”及部署自检中的固定 128 断言不是永久有效的测试数据。日期还必须处于请求业务日期之前的 30 天内。
- Kafka 按 traceId 分区；ClickHouse Kafka Engine 与物化视图入库，查询使用 `trace_spans_current` 去重视图。Kafka、ClickHouse 均无宿主机端口映射。
- 产物使用本地私有文件持久卷；尚未接 MinIO/S3。

数据库、SSH、模型和 ClickHouse 凭据不在仓库。已有服务器配置保存在 `/opt/tianmu-staging/config`，运行数据在 Docker volumes 中；换电脑不需要复制凭据到源码。使用新服务器需要生成独立配置，使用原服务器需要在新电脑单独配置 SSH 访问。不要对已有部署重新 init 或删除持久卷。

## 3. 已完成与部署差异

| 范围 | 状态 |
|---|---|
| 统一入口 | FAQ 优先；问题理解合并澄清、条件提取与改写，情绪并行且非阻塞 |
| Harness | 6 轮、16 次模型调用上限、整体 30 秒；取消、超时、强制收尾、证据校验 |
| Hook / Trace | 生命周期与类型化变换扩展；OpenTelemetry span；Kafka → ClickHouse 已实测 |
| 工具 | 仅 list_skills、read_skill、read_result、query_video_metrics，数量保持 4 个 |
| Skill | video-metrics 1.2.0；参考资料按需分页进入上下文；PreparedTask 固定版本和内容指纹 |
| read_result | 当前任务证据分页，超过 8 KiB 自动返回首页；小结果兼容原响应；没有通用结果外置仓库 |
| 上下文 | 最近 10 个可见轮次；最近 8 条原文、较早摘录；历史正文上限 16,000 字符 |
| 真实业务桥接 | 代码已提供 business profile；当前服务器仍使用演示身份和脚本模型 |

当前服务器 staging 版本为 `20261024T101511Z-7a7b8a7d6a07`，已接真实 MySQL、Redis、Kafka、ClickHouse。该版本已经验收 Kafka/ClickHouse 停机恢复、重复查询去重、异常消息隔离及 Trace 取消线程写盘修复。

**本次 Skill 1.2.0 与证据分页增强已在代码中完成，但尚未部署到已有服务器。** 不要把本地最新代码误认为现网当前版本。原服务器可在 `/opt/tianmu-staging/config/current-release` 确认实际版本。

本地最近一次 Agent 测试：138 项，0 失败、8 项外部存储测试因配置未启用而跳过；部署契约 9 项通过。完整中间件验收见 [Kafka/ClickHouse 验收](../tianmu-agent/docs/Kafka-ClickHouse验收.md)。

## 4. 下一步工作与约束

1. 等待用户提供原平台前端仓库/目录，然后接 Agent 入口、对话面板、SSE、取消和澄清续接；目前还没有前端入口。
2. SQL 工具仅讨论过方案，**尚未实现**。拟增加一个按 Skill 开放的受控只读 query_sql，必须先解决数据范围和业务权限，不能让模型任意查业务库。
3. Shell 仅讨论过，不在当前工具集中。不要为了“通用”堆工具；只补真实任务需要的能力，避免增加模型上下文与重叠入口。
4. 后续优先做 Token 预算、工具结果上下文治理、真实模型评测和正式业务指标对账。当前较早历史是原文摘录，不是语义摘要；没有长期记忆和多实例执行恢复。
5. 新 Skill/Tool/Prompt 分别放独立资源目录；新增能力通过接口注册，避免把业务逻辑写进核心循环。

主要入口：[架构说明](../tianmu-agent/README.md)、[工具扩展规范](../tianmu-agent/docs/工程目录与扩展规范.md)、[接口契约](../tianmu-agent/docs/接口契约.md)、[P0 业务接入](../tianmu-agent/docs/P0业务接入.md)。

给新电脑上的开发助手：先读本文件与仓库现有说明，检查 git status 和实际部署版本，再继续工作。不要默认已实现 SQL/Shell/前端，也不要把 demo 数据或脚本模型说成真实业务与真实模型。
