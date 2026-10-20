# Hook v0.3：可组合的类型化变换链

## 变更与范围

参考用户提供的五个扩展点、Hook 链与 Kafka Trace 材料，这次落地的是 Hook 的链式变换能力。
现有 AgentHook 保持兼容；新增 TransformHook<T>，返回值传给同阶段的下一个 Hook。
公共业务接口、HTTP 路径和 SSE 类型保持不变，演示能力仍是模拟指标查询。

本次没有实现 Skill 文件热更新、动态配置中心、Runtime 切换、A2A、RAG、报告或完整会话。
“五个扩展点”是后续整体方向，不能因为完成 Hook 就认为另外四项已经支持。

## 三种 Hook 职责

| 类型 | 接口 | 返回/行为 | 失败策略 |
|---|---|---|---|
| Observer | AgentHook，mode=OBSERVER | 观察不可变元信息，埋点或提交异步观测任务 | 记录失败，继续业务与后续 Observer |
| Gate | AgentHook，mode=GATE | 在前置阶段增加限制，可拒绝执行 | HOOK_REJECTED，结束任务 |
| Transform | TransformHook<T> | 返回与阶段匹配的新值，按链式顺序传递 | 失败即终止，绝不自动回退为未经脱敏/校验的原文 |

注册信息在启动时冻结。每条链按 order 升序，同 order 按 name 排序，不依赖 Spring Bean 扫描顺序。
Transform 与现有 Observer/Gate 是两条明确的扩展契约，不把所有阶段强塞到 Object 输入输出接口里。
它们都是受信任的同进程代码，不是对恶意插件的隔离沙箱。

## 已接入的七个变换点

| TransformPoint | 输入/输出类型 | 对应参考材料 | 当前用途与边界 |
|---|---|---|---|
| INPUT | String | onInput | 改写/规范化本次模型输入，原始请求仍保留 |
| CONTEXT | ModelContext | transformContext | 每轮投影观察结果、缩小可见工具集 |
| MODEL_RESULT | Decision | afterModelCall | 修复或规范化结构化决策，之后仍受运行时检查 |
| TOOL_CALL | ToolCall | beforeToolCall | 参数标准化/受控引用恢复，工具名不变 |
| TOOL_RESULT | ToolResult | afterToolCall | 结构化脱敏、裁剪辅助文本，统计事实与引用不变 |
| ANSWER | Answer | onMessageEnd / onAgentEnd 的回答部分 | 格式化最终文本，保留原证据列表 |
| CLARIFICATION | String | onAgentEnd 的澄清部分 | 格式化澄清内容，要求非空且有界 |

beforeAgentStart 中切换模型、Runtime 和 thinking budget 的完整契约尚未实现；当前 ModelPort 也未接真实提供方。
beforeAgentCall / afterAgentCall 需在真实 A2A 调用模型确定后增加，不注册没有执行体的空扩展点。
真正逐 Token 输出的 onMessageEnd 语义也尚未实现，目前只有整段答案交付。

## 实际执行顺序

```text
REQUEST_START
  → INPUT Transform
  → PRE_ROUTE
  → 每轮：
      组装 ModelContext → CONTEXT Transform
      → PRE_MODEL → 模型调用 → MODEL_RESULT Transform → POST_MODEL
      → 根据 Decision 分支：
          工具：检查当前可见工具集 → PRE_TOOL
                → TOOL_CALL Transform
                → 根据标准化后的工具名与参数做重复调用检查
                → 固定参数/Skill/权限校验 → 执行工具
                → TOOL_RESULT Transform → POST_TOOL
                → 回填观察结果
          答案：PRE_DELIVER → ANSWER Transform
                → 固定证据与输出校验 → 终态交付 → POST_DELIVER
          澄清：PRE_DELIVER → CLARIFICATION Transform
                → 终态交付 → POST_DELIVER
  → worker 清理 → REQUEST_END
```

阶段失败时保留原有 ON_ERROR、post 阶段失败元信息和唯一终态机制。
每个 Transform 执行前后检查取消/截止时间；取消仍是协作式，不能中断任意阻塞插件代码。

## 示例：新增工具结果摘要

新增一个 Spring Bean 即可，无需修改 AgentRuntime：

```java
@Component
final class ToolTextSummary implements TransformHook<ToolResult> {
    public String name() { return "tool-text-summary"; }
    public TransformPoint<ToolResult> point() { return TransformPoint.TOOL_RESULT; }
    public int order() { return 100; }

    public ToolResult transform(ToolResult input, TransformContext context) {
        Map<String, Object> data = new LinkedHashMap<>(input.data());
        if (data.get("description") instanceof String text && text.length() > 1000) {
            data.put("description", text.substring(0, 1000) + "…[摘要截断]");
        }
        return new ToolResult(input.tool(), data, input.evidenceId());
    }
}
```

默认内置三个小 Hook：normalize-input（去首尾空白）、redact-tool-credentials（按结构化字段脱敏）、
format-answer（去答案首尾空白）。脱敏 Hook 的 order=-100，后续摘要 Hook 可读取已脱敏的值。
字段脱敏只覆盖 authorization/token/password/secret/apiKey 等指定字段，不是完整 PII 检测或任意文本秘密识别。
当前没有默认的智能摘要或 Token 压缩器，上述摘要是扩展示例。

## 固定不变量：扩展不能取消的保底检查

每次 Hook 返回后立即检查，不能让错误的中间值先流向下一 Hook：

- 类型必须与变换点匹配，不接受 null。
- ModelContext 不能改变 AccessScope、业务日期、当前任务文本、已激活 Skill 或运行时剩余步数。
  INPUT 是任务文本改写的明确入口，不能通过 CONTEXT 隐式修改任务。
- 工具 Schema 只能保留原有工具的子集，不能添加工具、修改 Schema 或重复注册。
  模型忽略工具目录仍调用被移除工具时，执行端返回 TOOL_NOT_AVAILABLE。
- 上下文观察结果只能从原结果中投影，不能凭空新增观察、伪造结果归属或改写受保护事实。
- ToolCall 不能改变工具名；参数变换后还要通过真正的参数、Skill 和数据范围检查。
- ToolResult 不能改写 tool/evidenceId，以及 video_id/date/metric/value/metric_version/source/
  data_as_of/complete/demo/evidence_id 等统计字段；Skill 及 Schema 元信息也不能悄悄变化。
- Answer 格式变换保留原 evidenceIds，最终还要验证证据存在且属于本任务。
  这些检查不等于完整事实核验，文本中的数字或因果关系仍可能需要独立 AnswerVerifier。

原有敏感数据不因为 Hook 失败被原样回填；不在普通日志或 Trace 中记录变换前后正文。
现有只读 Evidence 与变换后的模型观察是两个层次，不用脱敏后的展示摘要覆盖原始业务证据。

ToolCall.arguments、ToolDefinition.inputSchema、ToolResult.data 现在进行递归防御性复制，
嵌套 Map/List 也不可修改，避免一个 Hook 原地修改对象污染其他 Hook、Schema 或原结果。
扩展数据使用 JSON 标量、Map、List，以及已明确支持的不可变框架记录；业务 POJO、Set、任意可变对象需先转换。
嵌套深度最多 20，非法数据类型在构造边界拒绝。

## 结果大小与错误语义

当前基架使用明确的字节/字符上限，不把字符数当成模型 Token 数：

- 进入变换链及中间结果：序列化大小最多 1 MiB。
- 最终 ModelContext：最多 256 KiB；其他阶段结果：最多 64 KiB。
- 输入、澄清和答案正文：最多 8000 字符，且必须非空。

这用于阻止无界内容进入循环，不是完整 Token 预算。超大响应的网络读取上限需由真实适配器另外实现。
当前可以用 Transform 降低结果大小，但完整大结果外置、ResultRef 和可恢复原文读取尚未实现。
不能直接截断 JSON 字符串；应投影字段、摘要文本，再返回完整结构。

| 错误码 | 含义 |
|---|---|
| HOOK_TRANSFORM_FAILED | 变换函数抛异常，任务终止 |
| HOOK_TRANSFORM_INVALID | 返回 null、错误类型或违反不可变约束 |
| HOOK_PAYLOAD_TOO_LARGE | 超过准入上限，尚未得到有效的有界结果 |
| HOOK_REJECTED | Transform 或 Gate 明确拒绝执行 |
| TOOL_NOT_AVAILABLE | 模型请求当前不可见工具 |

## Trace 与参考材料的两点澄清

Transform 自动生成 hook.transform_* Span，记录 Hook 名称、阶段、模式、耗时与安全错误码。
新增插件无需自己调用 Kafka Producer；继续使用 v0.2 的 OpenTelemetry → 本地缓冲 → TraceSink 链路。

Kafka key=traceId 保持同键分区亲和性，但 Offset 顺序不是业务开始时间顺序：Span 通常结束后才发送，
父 Span 常在子 Span 之后出现。跨线程与多个生产者也会交错；检索端应按 parentSpanId 与时间重建。
消费落库后的 traceId 索引决定检索效率，不是“同键分区所以不需要存储索引”。扩分区也可能改变同键后续的分区映射。

动态配置是配置供应、校验和版本快照层的能力，不由某个 Agent 框架名称决定。
本基架目前是启动配置，不宣称 YAML 改动自动下一请求生效；未来热更新需定义请求内版本一致性与回滚。

## 验证

v0.3 新增 13 项变换链测试，包括顺序组合、脱敏后摘要、深层不可变、上下文越权、能力缩减、参数越权、
标准化后去重、统计事实与证据防篡改、最终答案处理、失败闭合、大小限制及注册校验。
完整回归合计 45 项；具体最终打包和进程验证结果见《验证记录》。
