# Tianmu · 天幕

<p align="center">
  <strong>面向视频弹幕业务的运营问答 Agent</strong><br/>
  在权限边界内查询数据，以证据交付答案，并让每一步都可追溯。
</p>

<p align="center">
  <a href="https://github.com/blackirlsama/Tianmu/actions/workflows/ci.yml"><img src="https://github.com/blackirlsama/Tianmu/actions/workflows/ci.yml/badge.svg" alt="Build and verify"></a>
  <img src="https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.4-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5.4">
  <img src="https://img.shields.io/badge/version-0.7.0--SNAPSHOT-informational" alt="Version">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"></a>
</p>

<p align="center">
  <a href="#概览">概览</a> ·
  <a href="#核心特性">核心特性</a> ·
  <a href="#系统架构">系统架构</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#运行模式">运行模式</a> ·
  <a href="#质量保障">质量保障</a> ·
  <a href="#文档">文档</a> ·
  <a href="#参与贡献">参与贡献</a>
</p>

---

## 概览

运营人员在后台提出“查询视频 1001 昨天的弹幕数”，Tianmu 会依次完成身份校验、问题理解、受控工具调用与证据核验，最终返回带统计口径的答案；无法确认的条件会转为澄清，越权或缺少证据的请求不会产生结论。

仓库由两个相互独立的 Maven 工程组成：

| 工程 | 定位 |
| --- | --- |
| **视频业务后端**（根目录 `src/`） | 用户、视频、弹幕等业务能力，以及面向 Agent 的只读桥接层 |
| **运营 Agent**（[`tianmu-agent/`](tianmu-agent/README.md)） | 问题准备、模型决策、工具调度、证据交付与全链路观测 |

两者通过**鉴权后的只读接口**通信，业务写入与实时弹幕链路完全不受 Agent 影响。默认 Demo 模式可独立启动，无需数据库、中间件或模型 Key。

> **项目状态**：开发预览版（`0.7.0-SNAPSHOT`）。核心问答链路、业务桥接、持久化、Hook 与 Trace 已落地；真实业务口径验收、容量与多实例仍待推进，详见[路线图](docs/ROADMAP.md)。

## 核心特性

| 能力 | 设计要点 |
| --- | --- |
| **权限即边界** | 身份、租户与视频范围在进入循环前固定；有效能力 = 用户权限 ∩ Runtime 策略 ∩ Skill 声明 ∩ 当前激活范围。越权请求不产生工具结果与证据 |
| **证据驱动交付** | 每次查询登记证据 ID、统计口径与数据时点；交付前校验证据归属与引用完整性，伪造或跨任务引用会被拒绝 |
| **受控 Agent 循环** | 限制轮次、工具调用次数、总时限与单次超时；同参重复调用、连续失败与用户取消均受控终止，失败任务不输出答案 |
| **可观测与可扩展** | OpenTelemetry 链路贯穿接入、理解、模型、工具与交付；`AgentHook` 提供观察与拦截，`TransformHook<T>` 提供类型化变换，注册期冻结、运行期不可绕过关键校验 |
| **分层持久化** | MySQL 保存正式记录与审计，Redis 缓存可重建的近期会话视图，私有对象存储保存完整结果；支持幂等、澄清续接与重启后查询 |

## 系统架构

### 请求链路

```mermaid
flowchart TD
    Client["HTTP / SSE 客户端"] --> Access["身份校验<br/>数据范围 · 限流 · 会话"]
    Access --> Govern["输入治理"]
    Govern --> Faq{"FAQ 命中?"}
    Faq -->|是| Deliver["统一交付<br/>终态仲裁"]
    Faq -->|否| Gate{"确定性条件检查"}
    Gate -->|缺少必需条件| Clarify["澄清问题"]
    Gate -->|继续| Parallel["并行预处理"]
    Parallel --> Emotion["情绪识别<br/>非阻塞"]
    Parallel --> Understand["问题理解<br/>澄清判断 · 条件提取 · 改写"]
    Understand --> Ready{"条件齐全?"}
    Ready -->|否| Clarify
    Ready -->|是| Task["PreparedTask<br/>固定条件 · 权限 · 版本 · 预算"]
    Task --> Loop["Agent Loop<br/>模型决策 → 工具执行 → 证据回填"]
    Loop --> Deliver
    Clarify --> Deliver
    Deliver --> Output["答案 · SSE 事件 · 审计记录"]
```

FAQ 先于模型调用，命中即跳过推理；规则优先检查明确缺失的条件，再并行启动问题理解与情绪识别。**一次理解调用同时完成澄清判断、条件提取与安全改写**，情绪识别不阻塞主链路。进入循环前固定业务条件，所有路径共用同一套交付与终态控制。

### 模块划分

Agent 按职责分层，业务逻辑不进入主循环：

```text
Tianmu/
├── src/main/java/com/tianmu/           # 视频业务后端
│   └── agentbridge/                    # 只读业务桥接：JWT 校验、视频授权、固定 SQL
├── tianmu-agent/                       # 运营 Agent（独立 Maven 工程）
│   ├── api/                            # HTTP 接口、鉴权接入、SSE
│   ├── preparation/ application/       # FAQ、并行预处理、历史视图、任务组装、统一交付
│   ├── runtime/                        # 请求编排、模型与工具循环、状态、超时与取消
│   ├── harness/                        # 生命周期 Hook、类型化 Transform、Trace
│   ├── tool/ skill/ prompt/            # 工具执行、能力发现、资源与版本管理
│   ├── access/ query/ model/ adapters/ # 身份、业务查询、模型与存储的端口及实现
│   ├── health/                         # 存活与就绪探针
│   └── src/main/resources/             # prompts / skills / tools 声明
├── deploy/                             # 单机可复现部署栈（Compose / 镜像 / 运维脚本）
├── docs/                               # 路线图、部署、第三方依赖
└── scripts/                            # 冒烟、发布与部署契约检查
```

**业务 SQL 留在桥接层，模型协议留在适配器，Skill / Tool / Prompt 分目录维护。** 新能力通过接口与资源声明扩展，避免向主循环堆叠业务分支。

## 快速开始

**环境要求**：JDK 17、Maven 3.6.3+（首次构建需联网下载依赖）。

```bash
git clone https://github.com/blackirlsama/Tianmu.git
cd Tianmu
mvn -B -f tianmu-agent/pom.xml package
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

服务监听 `127.0.0.1:8201`，默认启用模拟身份、脚本模型与内存存储，无需 Key 或中间件。

```bash
# 创建任务
curl -s -X POST http://127.0.0.1:8201/api/agent/runs \
  -H 'Authorization: Bearer demo-alice' \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询视频1001昨天的弹幕数"}'

# 查询结果（替换为返回的 runId）
curl -s -H 'Authorization: Bearer demo-alice' \
  http://127.0.0.1:8201/api/agent/runs/RUN_ID
```

接口返回 `202` 与 `runId`；任务完成后结果为标记了**模拟数据**的弹幕数 `128`。可进一步体验：

- 提问“你能做什么” → 命中 FAQ 直答，跳过模型推理
- 省略日期提问 → 返回 `NEEDS_INPUT` 与澄清问题
- 查询未授权视频 → 返回 `FAILED / FORBIDDEN`，不交付答案

也可运行免 Key 自动冒烟：

```bash
python scripts/smoke_demo.py --jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

> Demo 凭证仅用于本机体验。真实模型、业务鉴权与持久化配置见[部署说明](docs/DEPLOYMENT.md)。

## 运行模式

| Profile | 身份 | 业务数据 | 模型 | 存储 |
| --- | --- | --- | --- | --- |
| `demo`（默认） | 模拟 | 模拟 | 脚本模型 | 内存 |
| `demo,deepseek` | 模拟 | 模拟 | 真实模型 | 内存 |
| `demo,persistent` | 模拟 | 模拟 | 脚本模型 | MySQL / Redis / 私有文件 |
| `business,deepseek,persistent` | 业务登录与权限 | 真实指标 | 真实模型 | MySQL / Redis / 私有文件 |

配置项见 [`.env.example`](.env.example)，通过运行环境注入；程序不会自动加载 `.env` 文件。真实模型调用会产生费用。

**当前能力边界**：业务查询覆盖本人未删除视频、最近 30 个已结束自然日的按日弹幕数，统计范围为**当前已入库且仍保留的记录**，不等同于历史发送总量，也不包含消息队列中尚未落库的数据。Token / 费用预算、资源热更新、多实例调度、复杂 RAG 与报告生成尚未实现。

## 质量保障

| 验证 | 说明 |
| --- | --- |
| Agent 单元与集成测试 | 15 个测试类、124 个用例，覆盖循环控制、权限负例、Hook 顺序、变换失败语义、Trace 重试与存储边界 |
| 部署契约测试 | `scripts/test_deployment.py` 校验配置模板、凭据边界与 Compose 约束，无需 Docker daemon |
| 持续集成 | [Build and verify](.github/workflows/ci.yml) 分别构建两个工程，执行发布检查与免 Key HTTP / SSE 冒烟 |

```bash
mvn -B verify                              # 视频业务后端
mvn -B -f tianmu-agent/pom.xml verify      # 运营 Agent
python scripts/check_publication.py        # 发布内容与敏感信息检查
git diff --check                           # 空白与冲突标记
```

默认测试无需模型 Key；MySQL / Redis 集成测试需显式启用，并在隔离环境中运行。

## 文档

| 文档 | 内容 |
| --- | --- |
| [Agent 开发指南](tianmu-agent/README.md) | 代码分层与扩展入口 |
| [工程目录与扩展规范](tianmu-agent/docs/工程目录与扩展规范.md) | Skill、Tool、Prompt 的开发约定 |
| [接口契约](tianmu-agent/docs/接口契约.md) | 任务、会话、证据、SSE 与错误码 |
| [持久化存储接入](tianmu-agent/docs/持久化存储接入.md) | 事务、缓存、恢复与清理策略 |
| [Harness · Trace 与 Hook](tianmu-agent/docs/Harness-Trace与Hook设计.md) | 治理接口与链路导出 |
| [P0 业务接入](tianmu-agent/docs/P0业务接入.md) | 真实鉴权、查询与 SQL 对账 |
| [部署说明](docs/DEPLOYMENT.md) | 单机部署、健康检查与版本切换 |
| [路线图](docs/ROADMAP.md) | 可领取的协作任务与验收条件 |

## 参与贡献

欢迎通过 [Issues](https://github.com/blackirlsama/Tianmu/issues) 与 [Pull Requests](https://github.com/blackirlsama/Tianmu/pulls) 参与。提交前请阅读[贡献指南](CONTRIBUTING.md)；涉及跨模块契约的改动请先开 Issue 对齐。

- 安全漏洞请按[安全政策](SECURITY.md)私下报告，不要公开提交 Issue。
- 新增业务工具需同时提供权限负例、口径说明与 SQL 对账结果。
- 修改 `core/`、`runtime/` 等公共契约时，请单独提交并说明兼容性影响。

## 许可证

采用 [MIT License](LICENSE)。项目原名 DokiTV，保留历史作者贡献；第三方组件与服务遵循各自条款，见 [NOTICE](NOTICE) 与[第三方依赖说明](docs/THIRD_PARTY.md)。
