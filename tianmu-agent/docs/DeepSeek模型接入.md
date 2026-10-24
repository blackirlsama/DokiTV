# DeepSeek：共用 Key，按阶段选择模型

更新日期：2026-10-23。

## 模型分工

| 阶段 | 默认模型 ID | 调用方式 |
|---|---|---|
| 意图、问题理解（澄清、条件提取、改写）与情绪 | deepseek-flash | 三个请求并行，共用一个 Key；情绪不额外阻塞，每路分别计入调用预算 |
| Harness 决策与无工具收尾 | deepseek-v4-pro | 按循环顺序调用，使用同一 Key |
| FAQ 命中 | 无 | 静态已批准条目直接交付 |

官方地址为 `https://api.deepseek.com`，调用 `/chat/completions`。本项目默认配置为 `deepseek-flash` 和 `deepseek-v4-pro`，可通过环境变量覆盖；实际可用模型 ID 以账户配置为准。[官方模型说明](https://api-docs.deepseek.com/quick_start/pricing/)

配置项 preparation-model / harness-model 分开，后续切换其中一个不需要改编排代码。两者使用同一个 agent.models.api-key，来自 DEEPSEEK_API_KEY 环境变量。

## 运行方式

默认 demo profile 完全离线。启用 `demo,deepseek` 后使用真实 DeepSeek 模型，同时保留演示身份、内置 FAQ 和模拟业务查询。

**真实模型不等于真实 Tianmu 数据。** 当前数字仍来自合成数据，答案应明确标注模拟数据。使用 DeepSeek profile 时，原来的两个脚本模型 Bean 自动停用，不会同时注册产生歧义。

先在启动进程的环境中设置 `DEEPSEEK_API_KEY`，再启动：

```powershell
java -jar target/operations-agent-0.7.0-SNAPSHOT.jar --spring.profiles.active=demo,deepseek
```

环境变量：

| 名称 | 默认或用途 |
|---|---|
| DEEPSEEK_API_KEY | 必填，共用凭证；缺少时启动失败 |
| DEEPSEEK_BASE_URL | https://api.deepseek.com |
| PREPARATION_MODEL | deepseek-flash |
| HARNESS_MODEL | deepseek-v4-pro |

只启用 deepseek 而不启用 demo 时，仍需自行提供正式身份、FAQ 和业务查询适配器。不会自动把正式业务请求降级为演示数据。

## 网络、输出与取消

使用 JDK HttpClient，不额外引入供应商 SDK。连接超时 3 秒；真实模型 profile 下意图与问题理解分别上限 10 秒、情绪 3 秒（汇合时未就绪则不额外等待），主循环单次等待 30 秒，收尾 15 秒，请求总时限 120 秒。各阶段仍受总截止时间约束。这些是初始联调参数，需根据实际延迟分布调整。

问题理解一次返回 `needsClarification / clarificationQuestion / conditions / rewrittenQuery`。服务端校验候选条件与原问题的一致性，理解失败保守追问，仅改写不合格时回原文。

当前使用非思考模式、JSON 输出协议，预处理输出上限 384 Token（情绪分支 128），主模型上限 1536 Token。主模型返回现有 TOOL / ANSWER / CLARIFY 决策，再由服务端工具调度器执行；没有让提供商直接运行工具。JSON 模式不等于可信业务输出，严格字段检查、工具参数/权限校验和证据校验继续生效。[官方接口协议](https://api-docs.deepseek.com/api/create-chat-completion/)

HTTP 请求不自动重试或跟随重定向；响应累计大小限制为 128KiB，并拒绝截断输出、重复 JSON 字段或非法结构。取消令牌会取消在途 HTTP Future。401/403 映射为 MODEL_AUTH_FAILED；其他拒绝请求映射为 MODEL_REQUEST_REJECTED；429、服务故障和普通网络失败按可用性故障处理。

不把完整身份范围发给模型；主模型只接收问题、业务日期、当前工具目录、结果和必要准备信息。服务端 AccessScope 始终由可信身份端口提供。

## 凭证与观测

提供的 Key 仅用于受权的官方 API 联调，通过进程环境传递；源码、示例配置、JAR、ZIP 和报告均不应包含真实 Key。属性对象的 toString 会脱敏，不记录请求头或提供商错误正文。

Trace 只增加模型 ID、提供商返回的输入/输出 Token 数等白名单元数据，不记录问题、完整响应或推理正文。用量记录尚未构成费用账本，失败调用的费用也不能靠成功响应 Trace 完整恢复。

适配器测试使用本地假服务验证模型选择、同一凭证、严格结构、错误分类、响应大小和在途取消；真实 API 联调结果见 [验证记录](验证记录.md)。
