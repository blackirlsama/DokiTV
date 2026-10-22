# Tianmu 运营 Agent 基架

Java 17 / Spring Boot 3.5.4。面向同学协作的独立后端工程，使用分包式单体结构。

**当前为开发预览版：默认 Demo 免 Key；business profile 已提供真实 JWT/Redis 身份、视频授权和 SQL 查询适配器。隔离库与一个真实模型场景已验证，正式业务环境仍待验收。**

最新入口：[根 README](../README.md) · [部署说明](../docs/DEPLOYMENT.md) · [P0 业务接入](docs/P0业务接入.md) · [P0 验收](docs/P0业务接入验收.md) · [贡献指南](../CONTRIBUTING.md)。
默认只绑定 `127.0.0.1:8201`。演示身份是公开固定凭证，不能直接部署到公网。

## 先看什么

- [持久化存储接入](docs/持久化存储接入.md)：v0.7 的实际配置、启动、会话/续接、幂等、对象与恢复边界。
- [全流程模拟验收](docs/全流程模拟验收.md)：可重复运行的 HTTP 模型模拟器、真实存储和故障分支验证。

- [工程目录与扩展规范](docs/工程目录与扩展规范.md)：v0.6 的资源目录、受限 ToolContext 与扩展步骤。
- [统一入口与并行预处理](docs/统一入口与并行预处理.md)：v0.6 的串并行顺序、PreparedTask、超时、取消和交付边界。
- [DeepSeek 模型接入](docs/DeepSeek模型接入.md)：共用 Key，Flash 跑预处理，V4 Pro 跑 Harness；环境变量和启用方式。
- [项目进度](docs/项目进度.md)：截至 2026-10-22 的已验证能力、实际边界和下一阶段分工。
- [上下文与记忆管理设计](docs/上下文与记忆管理设计.md)：新参考材料的吸收与修正，尚未实现。
- [架构与分工方案](docs/架构与分工方案.md)：可以直接发给同学，说明目标、范围、负责人和验收标准。
- [接口契约](docs/接口契约.md)：公共类型、HTTP、SSE、工具、证据及错误码。
- [验证记录](docs/验证记录.md)：已做验证与尚未验证的范围。
- [Harness Trace 与 Hook 设计](docs/Harness-Trace与Hook设计.md)：扩展接口、Kafka 配置、缓冲与投递语义。
- [Hook 变换链设计](docs/Hook变换链设计.md)：v0.3 的类型化链式处理、默认插件与固定校验。
- [请求准备与任务包设计](docs/请求准备与任务包设计.md)：参考设计；任务包已在 v0.6 落地，其他内容按进度说明区分。

## 快速启动

需要 JDK 17、Maven 3.6.3 或更新版本。首次启动需下载 Maven 依赖。

Windows PowerShell，在工程根目录执行：

```powershell
.\scripts\dev.ps1 -Action test
.\scripts\dev.ps1 -Action run
# 如没有自动找到 JDK：
.\scripts\dev.ps1 -Action run -JavaHome 'C:\path\to\jdk-17'
```

脚本优先寻找 JAVA_HOME 与用户 `.jdks` 下的 JDK 17，只改变当前进程的环境变量。
其他系统在正确配置 JAVA_HOME 后执行 `mvn test`、`mvn spring-boot:run`。

执行 `mvn -B package` 后，可用 JDK 17 运行 `java -jar target/operations-agent-0.7.0-SNAPSHOT.jar`。
默认使用模拟数据和本地地址。开发和提交代码请使用本模块源码。

## 跑一个完整请求

以下命令在第二个 PowerShell 窗口执行：

```powershell
$headers = @{ Authorization = 'Bearer demo-alice' }
$payload = @{ message = '查询视频1001昨天的弹幕数' } | ConvertTo-Json
$run = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8201/api/agent/runs' `
    -Headers $headers -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($payload))
$run.runId

# 获取进度与最终答案
curl.exe -N -H 'Authorization: Bearer demo-alice' "http://127.0.0.1:8201/api/agent/runs/$($run.runId)/events"

# 查询任务状态
$result = Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:8201/api/agent/runs/$($run.runId)"
$result

# 获取答案引用的证据
$evidenceId = $result.answer.evidenceIds[0]
Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:8201/api/agent/runs/$($run.runId)/evidence/$evidenceId"

# 取消任务；已结束任务保持原终态
Invoke-RestMethod -Method Post -Headers $headers -Uri "http://127.0.0.1:8201/api/agent/runs/$($run.runId)/cancel"
```

预期执行：读取 `video-metrics` Skill → 查询模拟指标 → 引用证据回答。数字固定为 128，并明确标注模拟数据。

- `demo-alice` 只能查询视频 1001；`demo-bob` 只能查询视频 1002。
- “查询视频1001的弹幕数”会要求补充日期。
- Alice 查询视频 1002 的任务以 `FORBIDDEN` 失败。
- 支持“昨天”或最近 30 天内已结束日期，按 `Asia/Shanghai` 解释。
- 演示模型是确定性脚本，只展示单视频弹幕计数流程；复杂分析问题尚不支持。

## 源码入口

```text
com.tianmu.agent
├── adapters/storage MySQL 工作单元 / Redis 视图 / 私有对象
├── api        HTTP / SSE / 错误响应
├── access     IdentityPort：可信身份边界
├── core       共享类型与错误类型
├── application TaskAssembler / DeliveryService
├── runtime    RequestOrchestrator / Agent Loop / 任务状态 / 有界执行队列
├── preparation FAQ 端口 / 小模型端口 / 有界并行与汇合
├── model      ModelPort / DeepSeek 真实模型适配
├── query      BusinessQueryPort：待同学接 Tianmu 查询服务
├── skill      从独立 Skill 文件加载目录与说明
├── prompt     Prompt 文件、索引与版本加载
├── faq        FAQ 文件与精确匹配适配器
├── resource   有界 UTF-8 / JSON 资源读取
├── tool       工具注册与分发；builtin 下每个工具一个实现类
├── demo       演示身份、脚本模型、合成数据
└── harness    Hook SPI、OpenTelemetry、Kafka / 文件出口、持久化导出缓冲
```

能力资源分别位于 src/main/resources/skills、tools、prompts、faq；模型指令、Skill 说明、工具 Schema 不再混写在 Java 执行逻辑中。当前为启动加载，修改资源后需重新打包或重启。

当前默认启用 `demo` profile。添加真实适配器时使用独立 profile（例如 `integration`），显式提供
`IdentityPort`、`FaqPort`、`PreparationModelPort`、`ModelPort`、`BusinessQueryPort` 五个 Bean。
非 demo 模式缺少实现会启动失败，避免悄悄降级成模拟数据。预处理与主循环模型独立适配；demo 下两者均为本地规则。

已提供 DeepSeek 适配器。设置 DEEPSEEK_API_KEY 后，以 `--spring.profiles.active=demo,deepseek` 启动：
三个预处理分支使用 deepseek-flash，主循环使用 deepseek-v4-pro，业务数据仍为模拟数据。

## 当前边界

- 同一 POST 入口先查 FAQ，未命中并行情绪识别、澄清判断和 Query 改写；条件足够再进入 Harness。
- 一个 Agent，一次一个工具调用；默认最多 6 轮，有证据时可额外无工具收尾一次；成功同参工具重复调用终止。
- 总截止时间默认 30 秒，独立定时器覆盖排队和调用等待；模型/查询等待各 5 秒、收尾 3 秒。真实适配器仍需设置连接/读取超时并响应取消。
- 已实现任务内 Skill 激活、权限范围、结果归属、证据 ID 校验；尚未实现逐条结论的事实核验。
- SSE 支持事件序号、同进程内回放和 `Last-Event-ID`；断线不会自动取消后台任务。
- 任务和证据存在内存，重启丢失；最多保留 200 个任务，创建新任务时清理创建超过一小时的终态任务。
- 取消通过统一令牌唤醒服务端等待并阻止晚到交付；Java 无法强制结束不响应中断的远程调用线程。
- 已有 OpenTelemetry Trace、Observer/Gate Hook、七类 Transform 链和 Kafka / 文件出口。默认写本地 `var/trace/archive/spans.jsonl`，Snapshot 返回 traceId。
- 没有不可丢的持久化审计、费用核算、长期记忆、Q&A 缓存、RAG、Shell、报告渲染或生产级限流。
- 任务存储仍在内存；Trace 的待发记录有独立持久化缓冲，不能用 Trace 恢复任务执行。

这些是下一阶段的明确工作项，不能把本工程当作已完成的生产系统。

## 启用持久化

配置专用 MySQL 库的 AGENT_JDBC_URL / AGENT_DB_USER / AGENT_DB_PASSWORD、Redis 地址和 AGENT_OBJECT_DIRECTORY，然后执行：

```powershell
java -jar Tianmu-agent-demo.jar --spring.profiles.active=demo,persistent
```

应用通过 Flyway 建表及校验迁移。数据库故障不降级成内存成功；本版每个库只支持一个 Agent 写实例。详见 [持久化存储接入](docs/持久化存储接入.md)，或使用 [本地 MySQL/Redis Compose](infra/storage/compose.yml)。
