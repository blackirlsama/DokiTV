# Tianmu 运营 Agent

**视频运营问答的独立 Java 服务，负责问题准备、受控执行与证据交付。**

[项目首页](../README.md) · [部署说明](../docs/DEPLOYMENT.md) · [API 契约](docs/接口契约.md) · [贡献指南](../CONTRIBUTING.md)

本模块使用 Java 17 / Spring Boot 3.5.4，拥有独立 `pom.xml`，可单独构建和运行。默认 Demo 无需模型 Key 或中间件；启用相应 profile 后，可使用真实模型、业务身份与数据、MySQL / Redis 持久化。

当前为开发预览版。对外 API 与内部扩展契约仍可能调整；持久化模式每个数据库仅允许一个 Agent 写实例。

## 本地开发

从仓库根目录执行：

```sh
mvn -B -f tianmu-agent/pom.xml verify
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

也可以在本模块目录执行 `mvn test` 或 `mvn spring-boot:run`。Windows 开发脚本会查找 `JAVA_HOME` 和用户 `.jdks` 中的 JDK 17：

```powershell
# 在 tianmu-agent 目录执行
.\scripts\dev.ps1 -Action test
.\scripts\dev.ps1 -Action run
# 手动指定 JDK
.\scripts\dev.ps1 -Action run -JavaHome 'C:\path\to\jdk-17'
```

默认地址为 `http://127.0.0.1:8201`。使用 `Bearer demo-alice` 提交 `{"message":"查询视频1001昨天的弹幕数"}` 到 `POST /api/agent/runs`，再按返回的 `runId` 查询状态或订阅事件。完整可复制命令见[项目首页](../README.md#快速开始)。

## 架构与执行契约

### 请求准备

`RequestOrchestrator` 协调一次请求，但仅在准备结果为 `HARNESS` 时调用 `AgentRuntime`。

| 顺序 | 组件 | 行为 |
| --- | --- | --- |
| 1 | API / `RunService` | 校验身份和请求，确定资源范围，处理会话、幂等键与显式澄清续接，进入有界队列并开始总时限 |
| 2 | Harness 输入与路由阶段 | 执行输入 Transform 和路由 Gate |
| 3 | `PreparationService` / `FaqPort` | 先查 FAQ；命中后走统一交付，不调用模型 |
| 4 | 历史与业务规则 | 加载授权范围内的历史视图；确定性规则发现缺条件时直接追问 |
| 5 | `PreparationModelPort` | 对同一输入并行提交澄清判断、Query 改写、情绪识别 |
| 6 | 预处理汇合 | 先判断澄清，再限时接纳改写；情绪仅取已完成结果。需要追问时取消其他分支 |
| 7 | `TaskAssembler` | 创建不可变 `PreparedTask`，固定业务条件、权限、日期、资源版本、工具范围与预算 |
| 8 | `AgentRuntime` / `DeliveryService` | 执行循环，统一校验答案并提交终态 |

澄清模型超时或结果不可用时保守追问；改写失败回原文；情绪未就绪为 UNKNOWN。权限与策略拒绝属于硬失败，不按普通可用性故障降级。详细时限与取消语义见[统一入口与并行预处理](docs/统一入口与并行预处理.md)。

### 主循环

```text
PreparedTask
    → 构造 ModelContext / Context Transform
    → ModelPort.decide
    → 校验与变换 Decision
        ├── ANSWER  → DeliveryService
        ├── CLARIFY → DeliveryService
        └── TOOL    → ToolDispatcher
                        → Schema / Skill / 权限 / 固定任务条件校验
                        → AgentTool.execute
                        → 结果变换 / 证据登记 / 观察结果回填
                        → 下一轮，或工具声明直接交付
```

模型只返回决策，不直接执行工具。`ToolContext` 不暴露可变 `RunState`、终态控制和 SSE 发送权。普通工具不能任意激活 Skill，允许激活的工具需显式声明能力。

轮次耗尽时，仅在已有证据且策略允许的情况下进行一次无工具收尾；收尾不能延长总截止时间。重复成功工具调用、连续可重试工具失败、调用次数耗尽和外部取消均有独立保护。SSE 断连不会自动取消任务，客户端应显式调用取消接口。

### 包与依赖边界

```text
src/main/java/com/tianmu/agent/
├── api/                 HTTP / SSE 与资源读取
├── application/         TaskAssembler / MemoryBuilder / DeliveryService
├── preparation/         FAQ / 并行预处理 / 条件与语义策略
├── runtime/             请求编排、循环、任务状态与调度
│   ├── control/         截止时间、取消、终态仲裁
│   ├── events/          事件存储端口
│   └── store/           仓储、证据与对象存储端口
├── harness/
│   ├── hook/            生命周期 Hook 与类型化 Transform
│   └── trace/           OpenTelemetry / 文件 Outbox / TraceSink
├── tool/                工具接口、目录、注册、校验与调度
│   └── builtin/         内置工具实现
├── skill/               Skill Catalog
├── prompt/              Prompt Catalog
├── access/              IdentityPort
├── query/               BusinessQueryPort
├── model/               ModelPort 与模型适配器
│   └── deepseek/
├── adapters/
│   ├── business/        HTTP 业务身份与指标适配
│   └── storage/         MySQL / Redis / 私有文件实现
├── core/                决策、任务、证据、记忆等共享类型
├── resource/            有界资源读取与解析
└── demo/                脚本模型、模拟身份与指标
```

业务 SQL 位于根工程 `src/main/java/com/orangecode/tianmu/agentbridge/`，Agent 使用 `IdentityPort` / `BusinessQueryPort` 访问业务服务。接入其他业务时替换相应适配器，避免把业务数据库细节带入循环。

## 资源与扩展开发

### Skill、Tool 与 Prompt

```text
src/main/resources/
├── skills/
│   └── video-metrics/
│       ├── SKILL.md             能力说明与使用约束
│       └── skill.json           名称、版本与工具声明
├── tools/
│   ├── list_skills/tool.json
│   ├── read_skill/tool.json
│   ├── read_result/tool.json
│   └── query_video_metrics/tool.json
├── prompts/
│   ├── index.json               Prompt ID、版本、资源路径
│   ├── common/                  JSON 输出约束、当前问题焦点边界
│   ├── preparation/             emotion / clarification / rewrite
│   └── harness/                 system / finalize
├── faq/                         Demo / 业务 FAQ
├── db/migration/                Agent 专用 Flyway 迁移
└── application*.yml             运行模式与环境配置
```

- **新增 Skill**：提供 `SKILL.md` 和 `skill.json`，引用已注册工具；新增可执行逻辑仍需实现工具。模型通过 `read_skill` 激活对应工具。
- **新增 Tool**：实现 `AgentTool` 并注册 Spring Bean，在 `resources/tools/<name>/tool.json` 声明描述和参数 Schema；通过受限 `ToolContext` 获取宿主能力。
- **新增 Prompt**：将正文放入 `resources/prompts/`，在 `index.json` 登记 ID 和版本，通过 `PromptCatalog` 获取。
- **新增业务条件**：实现 `QueryReadinessPolicy` 做确定性必填检查，实现 `TaskSpecificationResolver` 固定已知业务条件与来源。
- **更换模型**：分别实现主循环 `ModelPort` 与预处理 `PreparationModelPort`，落实 `CallControl` 中的截止时间和取消。

Catalog 在启动时校验资源并形成不可变快照，任务记录资源版本。当前是 classpath 加载，修改后需要重新构建、启动；尚无远程分发或热更新。工具 Schema、资源路径和载荷大小仍需通过框架校验。

完整示例与校验规则见[工程目录与扩展规范](docs/工程目录与扩展规范.md)。

### Hook 与 Transform

`AgentHook` 面向生命周期观察与执行前拦截，13 个位置为：

```text
REQUEST_START  PRE_ROUTE
PRE_MODEL      POST_MODEL
PRE_TOOL       POST_TOOL
PRE_SKILL      POST_SKILL
PRE_DELIVER    POST_DELIVER
ON_ERROR       ON_CANCEL       REQUEST_END
```

实现接口并注册 Bean，声明唯一名称、订阅点、顺序与模式。执行顺序为 `order` 升序，再按名称排序：

- `OBSERVER`：观察事件，失败被记录并隔离。
- `GATE`：仅可订阅允许拦截的执行前阶段；拒绝或异常终止相应操作。

需要改写载荷时实现 `TransformHook<T>`，不要通过生命周期事件修改运行状态。7 个类型化位置为：

| 位置 | 输入 / 输出 |
| --- | --- |
| `INPUT` | 用户输入字符串 |
| `CONTEXT` | `ModelContext` |
| `MODEL_RESULT` | `Decision` |
| `TOOL_CALL` | `ToolCall` |
| `TOOL_RESULT` | `ToolResult` |
| `ANSWER` | `Answer` |
| `CLARIFICATION` | 澄清字符串 |

同一位置按序串联输出，框架随后验证可信范围、固定任务条件、引用与大小等不变量。变换失败不能绕过校验交付原始结果。插件应线程安全，避免阻塞；Java 扩展是可信代码，不提供隔离沙箱。

详见 [Trace 与 Hook](docs/Harness-Trace与Hook设计.md)和[Hook 变换链](docs/Hook变换链设计.md)。

## 业务接入与数据口径

`business` profile 通过 HTTP 访问业务桥接。业务端验证 JWT、Redis 当前登录和用户状态；首版仅允许本人未删除视频。工具查询会重新验证登录，并在同一 SQL 语句中限制视频归属和统计范围。

当前指标 `bullet_count` 的口径版本为 `bullet-retained-created-v1`：按北京时间自然日，统计当前已入库且仍保留的弹幕记录，只接受最近 30 天内已结束日期。结果不代表历史发送总数或 MQ 消费完整性。

业务端独立桥接入口、只读连接池、索引和 SQL 对账步骤见 [P0 业务接入](docs/P0业务接入.md)。生产凭证只由运行环境提供，原始登录 Token 不写入 Agent 数据库。

## 存储、Trace 与恢复

- **MySQL**：权威保存会话、任务、消息、证据、事件、审计与产物元数据。终态及关联交付记录在同一事务提交。
- **Redis**：有界历史视图缓存，包含授权指纹和版本；故障回源 MySQL。存储 Outbox 异步刷新缓存。
- **对象存储**：当前为私有文件适配器，读取校验归属、长度和 SHA-256；`ObjectStore` 可替换，尚未提供 S3 / MinIO SDK 适配。
- **Trace**：OpenTelemetry → 有界本地文件 Outbox → JSONL / Kafka。后台等待目的地确认，失败重试；重复依靠 `eventId` 去重，容量和磁盘故障可能造成丢失。

MySQL 存储 Outbox 与本地 Trace Outbox 职责独立。Trace 当前没有附带消费查询平台；审计落库不依赖 Kafka。

持久化仅允许单写实例。重启可读取已完成任务和保留期内事件，排队任务受原期限约束恢复；在执行任务终止为 `RUNNER_LOST`。尚无执行检查点和多实例任务调度。

运行配置、保留策略、会话删除及恢复语义见[持久化存储接入](docs/持久化存储接入.md)。不同模式的启动命令见[部署说明](../docs/DEPLOYMENT.md)。

## 测试与验证

模块验证与打包：

```sh
mvn -B -f tianmu-agent/pom.xml verify
```

仓库级检查和 HTTP / SSE 冒烟：

```sh
python scripts/test_publication.py
python scripts/check_publication.py
python scripts/smoke_demo.py --jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
git diff --check
```

以上命令从仓库根目录执行。普通测试无需模型 Key；MySQL / Redis 集成测试需在隔离环境显式启用 `AGENT_STORAGE_IT=true` 并配置连接。不能将测试指向正式业务库。CI 配置见 [ci.yml](../.github/workflows/ci.yml)。

新增扩展应验证实际边界，例如参数非法、权限收缩、截止时间、晚到结果、变换越权和失败交付。真实模型调用需单独执行，不属于默认 CI。

## 文档导航

| 内容 | 文档 |
| --- | --- |
| API 字段、状态、事件与错误码 | [接口契约](docs/接口契约.md) |
| 并行准备、降级与任务固定 | [统一入口与并行预处理](docs/统一入口与并行预处理.md) |
| 资源目录与能力扩展 | [工程目录与扩展规范](docs/工程目录与扩展规范.md) |
| 生命周期治理与变换 | [Trace / Hook](docs/Harness-Trace与Hook设计.md) · [Transform](docs/Hook变换链设计.md) |
| 数据持久化与恢复 | [持久化存储接入](docs/持久化存储接入.md) |
| 业务桥接与 SQL 对账 | [P0 业务接入](docs/P0业务接入.md) · [验收记录](docs/P0业务接入验收.md) |
| 部署、贡献与演进 | [部署](../docs/DEPLOYMENT.md) · [贡献](../CONTRIBUTING.md) · [路线图](../docs/ROADMAP.md) |

部分设计文档描述目标架构；尚未实现的能力以[项目首页的能力边界](../README.md#当前能力与边界)和当前代码为准。
