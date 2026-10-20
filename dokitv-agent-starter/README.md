# DokiTV 运营 Agent 基架

Java 17 / Spring Boot 3.5.4。面向同学协作的独立后端工程，使用分包式单体结构。

**当前是可运行的演示基架：没有调用真实大模型，没有连接 DokiTV，也没有真实运营数据。**
默认只绑定 `127.0.0.1:8201`。演示身份是公开固定凭证，不能直接部署到公网。

## 先看什么

- [架构与分工方案](docs/架构与分工方案.md)：可以直接发给同学，说明目标、范围、负责人和验收标准。
- [接口契约](docs/接口契约.md)：公共类型、HTTP、SSE、工具、证据及错误码。
- [验证记录](docs/验证记录.md)：已做验证与尚未验证的范围。
- [Harness Trace 与 Hook 设计](docs/Harness-Trace与Hook设计.md)：扩展接口、Kafka 配置、缓冲与投递语义。
- [Hook 变换链设计](docs/Hook变换链设计.md)：v0.3 的类型化链式处理、默认插件与固定校验。
- [请求准备与任务包设计](docs/请求准备与任务包设计.md)：对参考材料的吸收、修正与下一阶段接口建议（尚未实现）。

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

另附的 `dokitv-agent-demo.jar` 可用 JDK 17 执行 `java -jar dokitv-agent-demo.jar`，无需 Maven；
同样使用模拟数据和默认本地地址。开发和提交代码请使用源码工程。

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
com.dokitv.agent
├── api        HTTP / SSE / 错误响应
├── access     IdentityPort：可信身份边界
├── core       共享类型与错误类型
├── runtime    Agent Loop / 任务状态 / 有界执行队列
├── model      ModelPort：待同学实现真实模型适配
├── query      BusinessQueryPort：待同学接 DokiTV 查询服务
├── skill      Skill 目录、说明、工具激活
├── tool       工具 Schema、参数与权限检查、证据登记
├── demo       演示身份、脚本模型、合成数据
└── harness    Hook SPI、OpenTelemetry、Kafka / 文件出口、持久化导出缓冲
```

当前默认启用 `demo` profile。添加真实适配器时使用独立 profile（例如 `integration`），显式提供
`IdentityPort`、`ModelPort`、`BusinessQueryPort` 三个 Bean。非 demo 模式缺少实现会启动失败，避免悄悄降级成模拟数据。

## 当前边界

- 一个 Agent，一次一个工具调用；最多 6 步，执行检查点检查 30 秒总时长，同参工具重复调用立即终止。
- 网络调用的硬超时尚未实现；真实适配器必须设置独立连接/读取超时并配合取消。
- 已实现任务内 Skill 激活、权限范围、结果归属、证据 ID 校验；尚未实现逐条结论的事实核验。
- SSE 支持事件序号、同进程内回放和 `Last-Event-ID`；断线不会自动取消后台任务。
- 任务和证据存在内存，重启丢失；最多保留 200 个任务，创建新任务时清理创建超过一小时的终态任务。
- 取消为协作式：先将任务置为取消，随后执行检查点阻止继续与交付；不承诺中断正在阻塞的远程调用。
- 已有 OpenTelemetry Trace、Observer/Gate Hook、七类 Transform 链和 Kafka / 文件出口。默认写本地 `var/trace/archive/spans.jsonl`，Snapshot 返回 traceId。
- 没有不可丢的持久化审计、费用核算、长期记忆、Q&A 缓存、RAG、Shell、报告渲染或生产级限流。
- 任务存储仍在内存；Trace 的待发记录有独立持久化缓冲，不能用 Trace 恢复任务执行。

这些是下一阶段的明确工作项，不能把本工程当作已完成的生产系统。
