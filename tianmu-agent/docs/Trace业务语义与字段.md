# Trace 业务语义与字段

Trace 用于解释一次提问经历了什么、为什么走该分支、在哪一步失败，以及答案何时生成、何时被提交发送。保留 OpenTelemetry Context、父子 Span、文件 outbox 和 Kafka TraceSink，不另建 ThreadLocal 链路系统。

## 协议与兼容

新增 `SpanType` 枚举，以白名单属性 `span.type` 提供稳定业务分类。既有 `agent.model`、`agent.tool` 等 Span 名称保留，避免破坏已有按名称查询。新生命周期节点使用 receive_query、agent_start、agent_end、finish、error；查询与聚合统一按 span.type，而不是解析名称前缀。

新导出记录采用 TraceEnvelope **1.1**，基础字段不变，attributes 增加有界字符串数组（最多 32 项，每项最多 160 字符）。v1.0 Schema 保留，新增 v1.1 Schema；导出 worker 接受并原样重放旧 v1.0 pending 文件。Kafka Header 继续携带对应版本与稳定 event_id。消费端必须先支持 1.1，不能按旧版仅标量属性的 Schema 验证新记录。

## 节点与实际字段

| span.type | 触发时机 | 关键字段 |
|---|---|---|
| receive_query | 为新任务建立 HarnessSession，只发生一次 | input.char_count、身份与会话关联字段 |
| pre_route | 完整预处理结束，已有真实路由结果 | request.route、route.reason、preparation.intent、preparation.emotion、preparation.degraded_count |
| faq_lookup | FAQ 查找结束 | faq.hit、命中时的 faq.id / faq.version |
| faq_direct | 命中 FAQ 后准备交付 | faq.hit、faq.id、faq.version |
| emotion_recognition | 独立情绪分支完成 | preparation.emotion、模型名称和提供方 usage（若有） |
| intent_recognition | FAQ 未命中后与理解、情绪并行识别 | preparation.intent、模型名称和提供方 usage（若有） |
| query_understanding | 理解、澄清和改写候选生成 | model.decision=CLARIFY/PROCEED、query.rewritten（候选存在，不等于已采纳） |
| memory_load | 从记忆仓储加载历史 | 耗时、operation.outcome、安全错误码 |
| context_build | 任务组装或每轮上下文构建 | context.observation_count、agent.available_tools |
| agent_start | 实际进入 Agent Loop，FAQ/前置澄清不会生成 | agent.max_turns、agent.available_tools（前 32 项）、agent.available_tool_count、适配器提供时的 model.name |
| llm_inference | 主循环每次模型调用，强制收敛调用也单独记录 | loop.step、run.finalizing、model.name、model.decision、model.selected_tool、model.input_tokens / output_tokens |
| tool_call | 工具调用及准入检查 | tool.name、tool.success、tool.has_evidence、tool.terminal_delivery、安全错误码 |
| read_skill | 工具请求激活 Skill | skill.name、skill.version |
| answer_ready | 受控交付层生成并提交答案或澄清 | answer.evidence_count、operation.outcome |
| terminal_delivery | 每次 SSE 订阅，从创建到完成/断开 | delivery.type=SSE、delivery.replay、delivery.success、delivery.first_content_ms、delivery.e2e_ms |
| agent_end | Agent Loop 离开（包含异常） | agent.total_turns、agent.reached_turn_limit、agent.outcome、运行级 usage 快照 |
| error | 任务确实进入 FAILED 状态 | error.code，不保存异常消息或堆栈 |
| finish | 终态监听与执行线程清理后，只发生一次 | finish.type、run.status、run.stop_reason、run.e2e_ms、run.answer_ready_ms、usage 汇总 |

所有业务节点携带 `run.id / session.id / run.turn_seq / tenant.id / actor.id`。身份来自 RunState.owner 的已认证作用域；不读取模型输出、HTTP 自报 Trace 属性或 authorizationRef 来补身份。

`transfer_manual` 尚无业务能力，不生成假节点。当前 FAQ 接口只提供审核后的 id/version/text，没有候选分数或 rerankScore；情绪接口只返回分类，没有 emotionReason；这些字段暂不编造。工具展示标题和精确模型名仅在有可信来源时接入，当前保留 tool.name 与调用提供方时的 model.name。

## 决策、结果和统计口径

- 模型结果经既有变换与校验后记录 CALL_TOOL、FINAL_ANSWER 或 CLARIFY；主模型调用或结果校验异常记录 ERROR。理解分支使用 PROCEED/CLARIFY，不与主循环推理次数混算。
- 路由结果在实际预处理结束后记录，旧的 agent.route 入口插座不冒充最终路由。route.reason 仅表示 FAQ_HIT、CLARIFICATION_REQUIRED 或 READY，不伪造更细粒度意图。
- 工具被 Gate 拒绝或执行抛错也记录 tool.success=false，operation.executed 表示阶段主体是否进入，不代表底层外部请求一定发出。read_skill 是工具内部子阶段，不能再当成独立工具次数累加。
- agent.reached_turn_limit 表示已达到配置轮数边界，不等于任务失败；同一轮也可能正常回答，是否强制收敛看 run.finalizing 和最终 stop reason。
- usage.input_tokens / output_tokens 是当前 Run 中所有模型（含理解和情绪分支）的**提供方已报告**合计。只在响应有数值型 usage 时统计；生成内容不合法但 usage 可解析时仍计数。
- finish 的 usage.complete 表示已报告 usage 的调用数与任务登记的模型尝试数一致。超时、取消、缺失 usage、Demo 模型没有用量数据时可能不完整，不能把缺失的用量当作准确的零费用。
- agent_end、finish、agent.run 上的 usage 都是同一运行级快照，usage.scope=RUN；聚合只能选一个口径。逐调用统计则只加 model.input_tokens/output_tokens，不再加父 Span 汇总。

## 时间与交付

run.e2e_ms 从任务 createdAt 到持久化终态通知，包含排队；根 Span 的持续时间还可能包含终态后的执行线程清理，两者不混用。成功回答和澄清另记录 run.answer_ready_ms；这表示结果已就绪，不表示用户收到。

terminal_delivery 是**每个订阅**的独立 Span。SSE 请求可能拥有不同于创建任务请求的 Trace，通过 run.id 和 run.trace_id 关联，不将订阅强行挂到已经结束的任务 Span。终态后订阅或携带非零游标的订阅标记 delivery.replay=true。

delivery.first_content_ms 从订阅创建到第一次 ANSWER、CLARIFICATION 或 ERROR 的 SseEmitter.send 成功返回。PROGRESS、ABORT、DONE 不触发；首次值不覆盖。delivery.e2e_ms 在同一时刻按任务创建时间计算。send 返回仅说明应用提交了发送，可能仍在框架缓冲，不能保证 socket flush 或客户端展示。

当前模型为非流式返回，因此**不填造模型 TTFT**。SSE 重连或晚订阅的发送耗时也不能冒充用户原始请求的 TTFT。后续真实流式模型可另加首 Token 指标。

delivery.success 表示事件流已正常发送至关闭状态；发送成功的 ERROR/ABORT 流仍可能对应失败/取消任务，业务成功与传输成功必须分别判断。

## 安全、边界与待办

JSONL / Kafka 的 Span attributes 仍然只携带元数据和详情引用，消息正文不进入该导出流。输入、输出、模型服务响应和错误详情通过私有 ObjectStore 保存，读取接口继承任务当前用户、租户和视频权限。

### 节点详情

- `node.type`：REQUEST、MODEL、TOOL、SKILL、CONTEXT、ROUTING、LOOP、DELIVERY、HOOK、HTTP 或 STAGE。`span.type` 继续表示细分业务阶段，例如 intent_recognition。
- `node.status`：RUNNING、SUCCEEDED、FAILED、TIMED_OUT、CANCELLED。与 OTel status 的 UNSET/ERROR 分开；UNSET 不直接当作成功。
- `execution.attempt / retry_count`：实际工具尝试的序号与已发生重试数。模型当前没有隐式重试，固定为 1/0。每次工具尝试保留独立 Span。
- `execution.timeout_ms / queue_ms / active_ms / wait_ms`：适配器调用预算、工作线程排队、实际执行和调用方等待。未执行到对应阶段时不伪造数值。Span 的总耗时仍包含 Hook 和详情采集等开销。
- `tool.call_id` 使用本次工具 Span ID；`skill.name / version` 和私有输出快照包含实际加载的 Skill 内容。
- `detail.{input,output,provider_output,error}_{ref,state,bytes,redacted}`：私有快照引用、采集状态、脱敏后原始字节数和是否脱敏。状态区分 COMPLETE、TRUNCATED、NOT_CAPTURED、DISABLED、CAPTURE_FAILED、LIMIT_REACHED。

主循环的 MODEL_CONTEXT 是送给模型适配器的逻辑上下文。DeepSeekClient 会用实际序列化前的 MODEL_REQUEST（messages、参数，不含认证 Header）替换同节点 input 引用，并另存 MODEL_PROVIDER_RESPONSE；节点 output 是经过校验和变换后的业务 Decision。工具 input 是实际准入校验的参数，output 是最终准入结果。Hook Transform 自身保留前后快照和字节数，`context.changed` 表示该 Hook 是否改变内容。

### 模型用量

每个模型节点提供 `model.name / provider / temperature / max_tokens / streaming / message_count / finish_reason`（有可信来源时）。`model.input_tokens / output_tokens / total_tokens / cache_hit_tokens / cache_miss_tokens / reasoning_tokens` 只记录服务商实际返回的非负数值。

`model.usage_state` 与请求级 `usage.state` 区分 NOT_REPORTED、PARTIAL、REPORTED、NOT_APPLICABLE；没有上报用量时不写 token 数值，避免把缺失当 0。请求合计按已报告的调用统计，结合 `usage.complete / reported_calls` 判断完整性。当前是非流式模型接口，`model.first_token_state=NOT_APPLICABLE`；`model.response_ms` 是完整响应耗时，不冒充 TTFT。

不采集模型隐藏推理正文；服务商响应中的 reasoning_content 被规则遮盖。公开回答和结构化决策保存在对应输出快照中。

### 存储与读取

默认 `harness.trace.details.enabled=true`，单份快照上限 128 KiB、每个 Run 累计采集上限 8 MiB，可通过 HARNESS_TRACE_DETAILS_ENABLED、HARNESS_TRACE_DETAILS_MAX_BYTES、HARNESS_TRACE_DETAILS_MAX_RUN_BYTES 调整。超限保留明确的截断预览或限额状态。采集失败不改变业务执行结果。

持久化环境复用已有 ObjectStore / MinIO 配置；Demo 使用 trace.directory/details 下的私有文件。每个引用包含 spanId、方向、字节数和 SHA-256，存储 Key 绑定 runId，读取时同时校验权限、大小和摘要。接口：

`GET /api/agent/runs/{runId}/trace-payloads/{spanId}/{direction}/{bytes}/{sha}`

密码、API Key、authorizationRef、Bearer 凭证、手机号和邮箱按规则遮盖；规则脱敏不保证发现任意正文中的全部敏感信息。快照对象的保留期需要由部署环境的私有存储生命周期管理；JSONL 的滚动归档不自动删除对象存储内的快照。

本地复现与查看（PowerShell）：

```powershell
python scripts/smoke_demo.py --jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar --artifacts var/trace-demo
python scripts/render_trace.py --trace var/trace-demo/spans.jsonl --details var/trace-demo/details --output var/demo-trace-call-tree.html
```

导出的 HTML 包含经过脱敏的输入输出详情，按与快照相同的授权范围使用。页面支持节点展开、开始/结束时间、耗时、Token、错误、输入输出和原始元数据查看。

生命周期由 HarnessSession 的终态 CAS 和执行计数控制，排队取消、错误与正常提前返回均有唯一 finish。进程被强杀时未结束 Span 无法由 outbox 恢复；晚到、未响应取消的适配器也可能在 finish 后产生记录，因此 finish 不是“所有记录已经到齐”的证明。

现有同步刷盘、容量上限、失败重试、eventId 去重契约保持不变。Kafka Consumer、在线 Trace 列表查询、跨服务 HTTP 注入、动态采样仍待建设；本地修改需部署后才能用于服务器流量。
