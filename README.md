# Tianmu · 天幕

**面向视频弹幕业务的运营问答 Agent，让业务查询有权限边界、有数据证据、可追溯。**

![Java 17](https://img.shields.io/badge/Java-17-007396)
![Spring Boot 3.5.4](https://img.shields.io/badge/Spring_Boot-3.5.4-6DB33F)
[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[快速开始](#快速开始) · [工程架构](#工程架构) · [开发指南](tianmu-agent/README.md) · [部署说明](docs/DEPLOYMENT.md) · [参与贡献](CONTRIBUTING.md)

用户提出“查询视频 1001 昨天的弹幕数”，Tianmu 完成身份校验、问题准备、工具查询与证据校验，再返回带统计口径的答案。

仓库包含**视频业务后端**和**独立运营 Agent**。业务后端负责数据生产与管理，Agent 通过鉴权、只读业务接口访问数据。默认 Demo 可独立运行，无需启动完整视频平台。

> 当前为开发预览版：已实现核心问答链路、业务桥接、持久化、Hook 与 Trace；正式业务环境和生产容量仍待验收。

## 快速开始

需要 **JDK 17、Maven 3.6.3+**，首次构建需下载依赖。

```sh
git clone https://github.com/blackirlsama/Tianmu.git
cd Tianmu
mvn -B -f tianmu-agent/pom.xml package
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:8201`，使用模拟身份、脚本模型和内存存储，无需 Key 或中间件。

```sh
curl -s -X POST http://127.0.0.1:8201/api/agent/runs \
  -H 'Authorization: Bearer demo-alice' \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询视频1001昨天的弹幕数"}'
```

接口返回 **202** 和 `runId`。将下方 `RUN_ID` 替换为返回值，查询任务结果：

```sh
curl -s -H 'Authorization: Bearer demo-alice' \
  http://127.0.0.1:8201/api/agent/runs/RUN_ID
```

示例结果为标记了**模拟数据**的弹幕数 **128**。提问“你能做什么”体验 FAQ，省略日期体验澄清。也可运行自动冒烟：

```sh
python scripts/smoke_demo.py --jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

Demo 凭证仅用于本机体验。真实模型、业务接入与持久化配置见[部署说明](docs/DEPLOYMENT.md)。

## 工程架构

### 请求链路

```mermaid
flowchart TD
    A["HTTP / SSE 客户端"] --> B["身份与范围校验 / 请求入队"]
    B --> C["输入治理 / FAQ 查询"]
    C -->|命中| J["统一交付 / 终态仲裁"]
    C -->|未命中| D["授权历史加载 / 确定性条件检查"]
    D -->|明确缺条件| Q["澄清问题"]
    D -->|继续| P["并行启动"]
    P --> E
    P --> M["情绪识别：非阻塞"]["问题理解：澄清 + 条件提取 + 改写"]
    E --> F{"条件是否齐全"}
    F -->|否| Q
    Q --> J
    F -->|是| G["PreparedTask：固定条件、权限、版本与预算"]
    M -.已就绪则使用.-> G
    G --> H["Harness：模型 → 工具 → 结果回填"]
    H --> J
    J --> K["正式记录 / 答案与事件"]
```

FAQ 先于模型调用，命中即跳过推理。规则先检查明确缺失条件，再并行启动问题理解与情绪识别。**一次理解调用完成澄清判断、条件提取和改写；情绪不额外阻塞**。理解超时或条件冲突则追问，仅改写不合格时回原文。进入循环前固定业务条件，所有路径共用交付与终态控制。

### 模块边界

项目包含两个独立 Maven 工程，根目录不是聚合工程。

| 模块 | 职责 |
| --- | --- |
| 根工程 `src/` | 视频业务后端，包含用户、视频、弹幕及相关数据链路 |
| `src/.../agentbridge/` | JWT / Redis 登录校验、视频授权、固定只读 SQL；可独立运行 |
| `tianmu-agent/` | 问题准备、模型决策、工具调度、会话管理与证据交付 |

Agent 内部按职责分层：

| 目录 | 职责 |
| --- | --- |
| `api/` | HTTP 接口、鉴权接入、SSE |
| `preparation/`、`application/` | FAQ、并行预处理、历史视图、任务组装、统一交付 |
| `runtime/` | 请求编排、模型与工具循环、状态、超时与取消 |
| `harness/` | 生命周期 Hook、类型化 Transform、Trace |
| `tool/`、`skill/`、`prompt/` | 工具执行、能力发现、资源与版本管理 |
| `access/`、`query/`、`model/`、`adapters/` | 身份、业务查询、模型与存储的端口及适配实现 |

**业务 SQL 留在桥接层，模型协议留在适配器，Skill / Tool 声明 / Prompt 分目录维护。** 新能力通过接口与资源扩展，避免向主循环堆叠业务逻辑。

### 执行、存储与观测

- **受控执行**：轮次、调用次数、总时限、单次超时、连续失败和取消保护；具备条件时支持无工具收尾。
- **可扩展治理**：`AgentHook` 提供观察与执行前拦截，`TransformHook<T>` 提供类型化载荷变换；关键权限与不变量由框架校验。
- **分层存储**：MySQL 保存正式记录，Redis 缓存可重建的近期会话视图，私有文件存储保存完整结果；支持幂等、澄清续接和重启查询。
- **链路与审计**：OpenTelemetry → 有界本地文件 Outbox → JSONL / Kafka；审计独立落 MySQL。导出可能重放或丢失，不承诺端到端恰好一次。

Skill 和 Prompt 当前从 classpath 加载，修改后需重新构建并启动。持久化当前仅支持单写实例；重启后不自动重放正在执行的模型与工具。

## 配置与能力边界

| Profiles | 运行方式 |
| --- | --- |
| `demo`（默认） | 模拟身份、数据、模型；内存存储 |
| `demo,deepseek` | 模拟业务 + 真实模型 |
| `demo,persistent` | 模拟业务 + MySQL / Redis / 私有文件 |
| `business,deepseek,persistent` | 业务登录与权限 + 真实指标、模型和持久化 |

配置清单见 [.env.example](.env.example)，需通过运行环境注入；程序不会自动加载 `.env`。真实模型会产生费用。

当前业务查询覆盖本人未删除视频、最近 30 个已结束自然日的按日弹幕数，统计**当前已入库且仍保留的记录**。该值不等于历史发送总量，也不包含 MQ 尚未落库的数据。

Token / 费用预算、资源热更新、多实例调度、复杂 RAG 与报告生成尚未实现，见[路线图](docs/ROADMAP.md)。完整视频平台还需自行准备中间件及业务表结构。

## 开发与文档

```sh
mvn -B verify
mvn -B -f tianmu-agent/pom.xml verify
python scripts/check_publication.py
git diff --check
```

默认测试无需模型 Key；MySQL / Redis 集成测试需显式启用并使用隔离环境。CI 分别验证两个工程，并运行免 Key HTTP / SSE 冒烟。

| 文档 | 内容 |
| --- | --- |
| [Agent 开发指南](tianmu-agent/README.md) | 代码分层与扩展入口 |
| [工程目录与扩展规范](tianmu-agent/docs/工程目录与扩展规范.md) | Skill、Tool、Prompt 开发 |
| [接口契约](tianmu-agent/docs/接口契约.md) | 任务、会话、证据、SSE 与错误码 |
| [持久化接入](tianmu-agent/docs/持久化存储接入.md) | 事务、缓存、恢复与清理 |
| [Trace 与 Hook](tianmu-agent/docs/Harness-Trace与Hook设计.md) | 治理接口与链路导出 |
| [P0 业务接入](tianmu-agent/docs/P0业务接入.md) | 真实鉴权、查询与 SQL 对账 |

欢迎通过 [Issues](https://github.com/blackirlsama/Tianmu/issues) 和 [Pull Requests](https://github.com/blackirlsama/Tianmu/pulls) 参与。提交前请阅读[贡献指南](CONTRIBUTING.md)；安全问题按[安全政策](SECURITY.md)报告。

## 许可证

采用 [MIT License](LICENSE)。项目原名 DokiTV，保留历史作者贡献；第三方组件及服务遵循各自条款，见 [NOTICE](NOTICE)。
