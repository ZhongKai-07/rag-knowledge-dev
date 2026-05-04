# RAG 接入 AgentScope-Java POC 设计（v2 / POC-A 隔离验证）

- **状态**：待评审 / 可执行
- **日期**：2026-05-01
- **修订**：v2（基于 review 反馈，从 v1 双链路集成方案改为 POC-A 纯隔离验证）
- **Owner**：TBD（D0 启动前必须指派）
- **工期**：目标 6 个工作日（D0–D5），硬上限 8 个工作日
- **上下文承接**：
  - [2026-04-22 Agent 化 + 持续学习层 Roadmap](./2026-04-22-agent-continual-learning-roadmap.md)
  - [2026-04-26 架构重整 P0 审计计划](./2026-04-26-architecture-p0-audit-plan.md)
  - 企业办公 Agent 平台统一架构方案（V1 = Task Runtime + RAG Capability + Document AI）
- **本文定位**：在动手 V1 Phase 1 之前的**框架选型 spike**。验证 AgentScope-Java 能否承担"Agent Engine 层"角色，为后续 Agentic 能力打基础，同时保住企业级特性（权限、trace、引用、配额）。

---

## 1. v2 重大变更（相对 v1）

v1 设计有 4 个硬冲突，v2 全部修复：

| v1 问题 | v2 修订 |
|---|---|
| `agent-spike` 单向依赖 `bootstrap`，但又要让 `ChatController/RagChatRouter` 分流到 spike——依赖方向冲突 | **完全移除生产 endpoint 分流**。POC 只通过 `@SpringBootTest` 和 standalone runner 执行。`bootstrap` 不引入任何 spike 代码 |
| `buildScope(UserContext user, IntentResult intent, List<Long> kbIds)` 与现有 `RetrievalScopeBuilder.build(String requestedKbId)` 签名冲突，引入双身份来源 | **复用现有 `RetrievalScopeBuilder`**，POC 不重定义。所有需要的输入由 SpikeAgentChatService 在 `UserContext` 已注入的请求线程内同步调用 |
| 把 `RetrievalScope` 塞进 `RagTraceContext` ThreadLocal——后者只存 `traceId/taskId/nodeStack/evalCollector` | **使用 AgentScope `ToolExecutionContext` 传递业务参数**；不新增 ThreadLocal |
| `search(String query, RetrievalScope scope)` 丢掉外层算好的 `List<SubQuestionIntent>`，无法等价复用 `RetrievalEngine.retrieve` | **接口签名改为 `retrieve(RewriteResult, List<SubQuestionIntent>, RetrievalScope)`**，对齐现有 `RetrievalEngine` 入口契约 |
| 双链路 + 三层 Feature Flag + JSON schema | **POC-A 不需要灰度**。不接生产 SSE，不需要 flag。响应 schema 兼容性留给 POC 通过后的 Phase 1 |
| AgentScope-Java 版本和 SB 3.5.7 兼容性未验证 | **新增 D0：依赖锁定与冲突排查**，作为整个 POC 的前置 gate |

---

## 2. 一句话目标

> 在不接生产 endpoint、不动前端、不污染主仓 pom、不绕过现有权限链路的前提下，通过 `@SpringBootTest` 把现有 RAG 经由 AgentScope-Java ReActAgent 跑通端到端，证明 6 项验收（V0–V5）通过，并产出"是否进入 Phase 1"决策报告。

---

## 3. 非目标（明确不做）

- ❌ Mem0 / 长期记忆（引入新存储依赖，POC 不背）
- ❌ Spring Boot Starter 全模块接入（POC-A 用最小依赖）
- ❌ AWS Bedrock + LiteLLM Gateway（沿用现有 `infra-ai` ModelRouter）
- ❌ 任何前端改动
- ❌ 任何数据库 schema 变更（包括 `t_user_feature_flag`）
- ❌ **任何对 `bootstrap` 模块的代码改动**（不引入 RagChatRouter，不挂 spike endpoint）
- ❌ **任何对生产 `/rag/v3/chat` 路径的灰度**
- ❌ 多 Agent 协作 / Supervisor / Planner-Executor 拆分
- ❌ 替换 query rewriting / 意图识别（保持现有确定性 chain）
- ❌ 把 OpenSearch 检索 + RRF + rerank 暴露成多个 Agent 工具
- ❌ 接入 Spring AI Alibaba

---

## 4. 背景：为什么做这个 POC

V1 统一架构方案中，Agent Runtime 层有两条路：
1. **自建** Planner / Executor / Verifier
2. **引入** AgentScope-Java 作为 Agent Engine

调研结论：
- AgentScope-Java（Apache 2.0, JDK 17+）提供 ReActAgent + PlanNotebook + Long-term Memory + Tool/MCP 集成
- Spring AI Alibaba 的核心已升级到 AgentScope，两者是上下层关系而非竞品
- 框架能直接覆盖 Agent Runtime 层；TaskRuntime / Governance / Workbench / 持续学习层仍需自建
- 风险点：v1.0.x 成熟度未充分验证；release note 提到的 Spring Boot 依赖（疑似 4.x 引用）与现有 SB 3.5.7 可能冲突
- 集成成本未知：与现有 `RetrievalScopeBuilder` / `RetrievalEngine` / `KbReadAccessPort` / `SourceCardBuilder` / `RagTraceContext` 的实际打通成本

POC 要回答的核心商业问题：
- **接入成本**是否在可接受范围
- Agent 内部能否保住企业级特性（权限不绕过、trace 不断、引用不丢、配额不超）
- **ROI** 是否正向（节省的编排代码 vs 引入的 lock-in 与调试复杂度）

---

## 5. 整体架构（POC-A 隔离验证）

```
┌──────────────────────────────────────────────────────────────────┐
│ 测试入口（不进生产路径）                                          │
│  ├─ @SpringBootTest 端到端测试（CI 可跑）                         │
│  └─ SpikeStandaloneRunner（CommandLineRunner，本地手工跑 10 题）  │
└──────────────────────┬───────────────────────────────────────────┘
                       ↓
┌──────────────────────────────────────────────────────────────────┐
│ agent-spike 模块（单向依赖 bootstrap，POC 失败可整体删除）        │
│                                                                    │
│  SpikeAgentChatService                                            │
│   │                                                                │
│   ├─ Step 1：Pre-process（请求线程内，UserContext 已注入）         │
│   │   subQuestionIntents = intentResolver.resolve(rewriteResult)  │
│   │   scope = retrievalScopeBuilder.build(requestedKbId)          │
│   │   ↑ 复用现有 bean，零新接口                                   │
│   │                                                                │
│   ├─ Step 2：构建 ToolExecutionContext（业务参数显式传递）         │
│   │   ctx = ToolExecutionContext.builder()                        │
│   │           .put("rewrite", rewriteResult)                       │
│   │           .put("subIntents", subQuestionIntents)               │
│   │           .put("scope", scope)                                 │
│   │           .build()                                             │
│   │                                                                │
│   ├─ Step 3：ReActAgent.run(rewrittenQuery, ctx)                  │
│   │   ├── 模型走 infra-ai ModelRouter（不直连 LLM）               │
│   │   ├── 工具：retrieve_knowledge → OpenSearchKnowledgeAdapter   │
│   │   │       从 ToolExecutionContext 读 subIntents/scope          │
│   │   └── PostActingEvent hook：捕获工具原始返回 → ChunkBuffer    │
│   │                                                                │
│   └─ Step 4：装配 SSE 事件序列（验证兼容性）                       │
│       META → SOURCES → MESSAGE+ → FINISH → DONE                    │
│       ↑ 用 SSEEventType 枚举，不发到真实 emitter，写入测试 sink    │
└──────────────────────┬───────────────────────────────────────────┘
                       ↓
┌──────────────────────────────────────────────────────────────────┐
│ bootstrap 现有 bean（agent-spike 直接 @Autowired，零代码改动）   │
│  QueryRewriteService / IntentResolver / RetrievalScopeBuilder    │
│  RetrievalEngine / MultiChannelRetrievalEngine / RerankerService │
│  KbReadAccessPort / AuthzPostProcessor / SourceCardBuilder       │
│  CitationStatsCollector / RagTraceContext                         │
└──────────────────────────────────────────────────────────────────┘
```

**关键边界**：
- `agent-spike` 是 Maven 子模块，依赖 `bootstrap`（单向）
- `agent-spike` 不暴露 REST endpoint
- `bootstrap` 不知道 `agent-spike` 存在
- 所有调用都通过 `@SpringBootTest` 启动完整 bootstrap 上下文，agent-spike 的 service 通过 Spring 注入 bootstrap 的 bean

---

## 6. 4 个 RAG 能力的接入位置（关键决策）

按"是否调 LLM"和"是否每次必须执行"两个维度切分。**接入位置不同决定 POC 验证的是不同的东西**。

| 能力 | 是否调 LLM | 每次必须 | POC 接入位置 | 理由 |
|---|---|---|---|---|
| **Query Rewriting** | ✅ | 视模式 | **Agent 之外**（pre-process） | 现有 multi-query / sub-query / HyDE 链是确定性的，效果稳定。塞进 Agent 会丢失 chain |
| **意图识别** | ✅ | 不一定 | **Agent 之外**（pre-process） | 输出 `List<SubQuestionIntent>` 是 RetrievalEngine 的必需入参，必须在外层算好 |
| **OpenSearch 多路检索 + RRF** | ❌ | ✅ | **Agent 之内**（`Knowledge.retrieve` 内部） | Agent 的 Agentic 能力体现在"决定何时检索" |
| **Rerank（gte-rerank）** | ❌ | ✅ | **Agent 之内**（紧跟检索后，对 Agent 透明） | rerank 是检索后固定后处理 |

**关键约束**：
- 不要把 rewriting 做成 ReAct 工具
- 不要让 Agent 每次重新猜 RetrievalScope 或 SubQuestionIntent（外层一次性算好，通过 `ToolExecutionContext` 传给工具，所有内层检索复用）
- 不要拆 OpenSearch 检索和 Rerank 为两个工具

---

## 7. POC 物理布局

新增临时模块 `agent-spike/`，单向依赖 `bootstrap`：

```
agent-spike/
  pom.xml                                # 独立依赖，引入 agentscope-java
                                          # 排除可能与 SB 3.5.7 冲突的传递依赖
  src/main/java/com/knowledgebase/ai/ragent/spike/
    SpikeAgentChatService.java          # POC 主入口（调用 bootstrap bean）
    OpenSearchKnowledgeAdapter.java     # 实现 AgentScope Knowledge 接口
                                         # 从 ToolExecutionContext 读业务参数
    SpikeChunkBuffer.java               # PostActingEvent hook 收集 chunk 元数据
                                         # request-scoped bean，非 ThreadLocal
    SpikeSseAssembler.java              # 装配 META→SOURCES→MESSAGE→FINISH→DONE
                                         # 写入测试 sink，不发到真实 SseEmitter
    SpikeStandaloneRunner.java          # CommandLineRunner，本地手工跑 10 题
    config/SpikeAgentConfig.java        # @Configuration，装配 AgentScope bean
  src/test/java/com/knowledgebase/ai/ragent/spike/
    KnowledgeAdapterAuthzTest.java      # V1 权限断言（4 个无权限场景）
    AgentEndToEndTest.java              # 端到端 @SpringBootTest（10 题）
    SseEventSequenceTest.java           # V3 SSE 事件顺序验证
    TraceContinuityTest.java            # V4 trace_id 跨 Agent 边界验证

bootstrap/                               # ❌ 零改动
```

**模块依赖**：`agent-spike → bootstrap`（单向）。**禁止** bootstrap 反向 import agent-spike，由 ArchUnit 在 spike 期内本地校验。

**包名**：`com.knowledgebase.ai.ragent.spike`（与现有 `com.knowledgebase.ai.ragent.*` 一致，不要写 `com.nageoffer`）。

---

## 8. SpikeRagPort 接口设计（v2 重写）

**v2 不引入新 port**——直接 `@Autowired` 现有 bean，零接口扩张。下表列出所有 spike 直接依赖的 bootstrap bean 和契约：

| Bean | 用途（spike 内） | 调用线程 |
|---|---|---|
| `QueryRewriteService` | 改写原始 query | 请求主线程（pre-process） |
| `IntentResolver` | 输出 `List<SubQuestionIntent>` | 请求主线程（pre-process） |
| `RetrievalScopeBuilder.build(String requestedKbId)` | 构建 RetrievalScope，**内部读 `UserContext`** | 请求主线程（pre-process） |
| `RetrievalEngine.retrieve(RewriteResult, List<SubQuestionIntent>, RetrievalScope, ...)` | 多通道检索 + RRF + AuthzPostProcessor + rerank → `RetrievalContext` | Agent 工具回调线程 |
| `SourceCardBuilder.build(distinctChunks)` | 聚合 → `List<SourceCard>` | 请求主线程（pre-process 完检索后） |
| `CitationStatsCollector.scan(answer, cards)` | 引用统计 | onComplete 阶段 |
| `RagTraceContext`（已有 ThreadLocal） | 复用现有 trace_id 注入 | 全程 |

**业务参数传递（替代 ThreadLocal）**：
```java
// SpikeAgentChatService（请求主线程）
ToolExecutionContext toolCtx = ToolExecutionContext.builder()
    .put("subIntents", subQuestionIntents)     // List<SubQuestionIntent>
    .put("scope", retrievalScope)              // RetrievalScope
    .put("traceId", RagTraceContext.getTraceId())
    .build();
agent.run(rewrittenQuery, toolCtx);

// OpenSearchKnowledgeAdapter（Agent 工具回调线程）
@Override
public List<Document> retrieve(String query, ToolExecutionContext ctx) {
    var subIntents = ctx.get("subIntents", List.class);
    var scope = ctx.get("scope", RetrievalScope.class);
    var traceId = ctx.get("traceId", String.class);
    RagTraceContext.set(traceId);  // 跨线程续 trace
    var rewriteResult = RewriteResult.fromAgentQuery(query);
    return retrievalEngine.retrieve(rewriteResult, subIntents, scope)
                          .getDistinctChunks()
                          .stream().map(this::toAgentDocument).toList();
}
```

**接口设计原则**：
- **零新 port**——spike 失败时只需删 agent-spike 模块，bootstrap 一行不动
- **业务参数走 `ToolExecutionContext`**——AgentScope 官方机制，跨线程显式传递
- **trace_id 走现有 `RagTraceContext`**——但需要在 Agent 工具回调线程显式 `set`/`clear`（V4 验收点）
- **chunk 元数据走 `PostActingEvent` hook + 请求级 `SpikeChunkBuffer` bean**——非 ThreadLocal stash

---

## 9. POC 验收标准（6 项 V，v2 重编号）

按风险排序。每项必须有可复现证据，**不许"看起来 work"**。

| # | 验证问题 | 证据形式 | 风险等级 |
|---|---|---|---|
| **V0** | AgentScope-Java 能在 SB 3.5.7 + JDK 17 下装配启动，无传递依赖冲突 | `mvn -pl agent-spike dependency:tree` 输出无 `(version managed from X)` 冲突；`mvn -pl agent-spike test` 全绿 | **极高**（D0 gate） |
| **V1** | `OpenSearchKnowledgeAdapter` 复用 `RetrievalEngine` + `AuthzPostProcessor` + `KbReadAccessPort`，权限链路不绕过 | 4 个单测：(a) 无权限用户 (b) 高密级文档 + 低 SL 用户 (c) 跨部门 KB (d) 软删 KB；**断言基于结构（`distinctChunks` 不含 docId=X）而非 "not found" 文案**（按 `feedback_assertion_decoupling`） | **极高** |
| **V2** | Agentic 模式下 Agent 实际调 `retrieve_knowledge` 工具；total LLM call ≤ 旧 `/chat` + 2（容忍 ReAct reasoning 多 1-2 步） | trace 显示工具调用次数 ≥1；10 题的 LLM 调用次数对比表 | 中 |
| **V3** | chunk 元数据（`docId / score / span / kbId / securityLevel`）通过 `PostActingEvent` hook 完整保留；`SpikeSseAssembler` 装配出与现有 `/rag/v3/chat` 字节级等价的 SSE 事件序列 | 测试断言：(a) `SOURCES` 事件必须在首个 `MESSAGE` 之前 (b) sources payload 与现有 `SourceCardBuilder.build(distinctChunks)` 输出 diff 为空 (c) 事件顺序 `META → SOURCES → MESSAGE+ → FINISH → DONE` | **极高** |
| **V4** | `RagTraceContext.traceId` 跨 Agent 调用边界保持一致（包括工具回调线程）；trace DB 步骤数满足下限 | 测试断言：所有 step 的 `trace_id` 完全相同；step 数 ≥ rewrite(1) + classifyIntent(0 或 1) + agent_reasoning(≥1) + tool_call(≥1) | 高 |
| **V5** | LLM 调用都走现有 `infra-ai` ModelRouter，不绕开熔断 / 配额 / 路由 | LLM 调用日志含 `model_used`，model 选型与现有路由策略一致；无直连 provider 痕迹；`infra-ai` rate-limit 计数器在 spike 调用后正确递增 | 高 |

**V0 和 V3 是最大风险点**：
- **V0**：release note 提到 Spring Boot 4.x 引用（疑似 Spring Framework 7.x 而非 SB 4.x），与 SB 3.5.7 可能冲突。**D0 不通过则整个 POC kill**
- **V3**：AgentScope ReActAgent 默认会把工具返回 normalize 成文本喂回 LLM，chunk 元数据会丢失。**官方 `PostActingEvent` hook 是 side-channel 出口，必须验证它能拿到原始结构化返回**。同时 SSE 事件顺序硬约束（见 `bootstrap/CLAUDE.md`：`SOURCES` 必须在首个 `MESSAGE` 前同步发出）必须在 spike 内重现，否则 Phase 1 接生产时会破前端约定。**D3 上午优先验证 V3，不通过则 kill**

---

## 10. 6 天执行计划（D0–D5）

| Day | 任务 | 产出 / Gate |
|---|---|---|
| **D0** | **依赖锁定与冲突排查**：<br>1. 拉取 `agentscope-ai/agentscope-java` 仓库 README + `pom.xml` 确认 Maven 坐标 / 实际版本 / 实际 Spring 依赖<br>2. 在 `agent-spike/pom.xml` 锁定版本，运行 `mvn -pl agent-spike dependency:tree`<br>3. 排查与 SB 3.5.7 / Spring Framework 6.x 冲突<br>4. 编写依赖排除策略；hello-world ReActAgent 启动测试 | **V0 通过**；模块可启动；产出 `D0_dependency_report.md` |
| **D1** | 写 `OpenSearchKnowledgeAdapter`（mock RetrievalEngine 先跑通框架联调）；写 `KnowledgeAdapterAuthzTest` 4 个无权限场景 | **V1 通过**；权限不绕过有单测证据 |
| **D2** | 接真实 `RetrievalEngine` + `RetrievalScopeBuilder` + `IntentResolver`；端到端跑 1 个真实问题；用 `PostActingEvent` hook 捕获 chunk 元数据 | chunk 元数据完整捕获；如失败则下午 kill POC |
| **D3 AM** | 实现 `SpikeSseAssembler`；验证 SSE 事件序列与生产 `/rag/v3/chat` 字节级等价 | **V3 通过**；不通过则**当天下午跳过 D3 PM / D4，直接进入 D5 报告** |
| **D3 PM** | 接现有 `infra-ai` ModelRouter；验证 trace_id 跨工具回调线程续上 | **V4、V5 通过** |
| **D4** | 跑完 10 题对照测试；产出 (answer 相似度 / source overlap / latency / token / LLM 调用次数) 对比表；ArchUnit 校验 `bootstrap` 不依赖 `agent-spike` | **V2 通过** |
| **D5** | 写 POC 报告：6 项 V 的证据、风险、是否进入 Phase 1 的建议；review 与归档 | 决策文档 `docs/dev/design/2026-05-01-agentscope-poc-report.md` |

**测试数据集**：
- 5 题来自现有 RAGAS 评测集（`t_rag_evaluation_record`）
- 5 题手工出题（覆盖：单 KB / 多 KB / 高密级 / 低密级 / 跨域 / 短查询 / 长查询）

**测试运行方式**（POC-A 不接生产 endpoint）：
- CI：`mvn -pl agent-spike test` 跑所有 `@SpringBootTest`
- 本地手工：`mvn -pl agent-spike spring-boot:run` 启动 `SpikeStandaloneRunner` 顺序跑 10 题，结果落到 `target/spike-results/`

---

## 11. 失败定义

任意一项触发即判 POC 失败：
- V0–V5 任一项无法验证通过
- 端到端 latency > 旧 `/chat` 的 3 倍
- 单次 token 消耗 > 旧 `/chat` 的 2 倍
- POC 工期超过硬上限 8 个工作日

---

## 12. POC 后分叉决策矩阵

| POC 结果 | 下一步 |
|---|---|
| **6 项全过 + ROI 正向** | 进入 Phase 1 设计；`agent-spike` **不直接转正**，而是作为 Phase 1 RetrievalQaTaskHandler 实现的参考；正式接入需经 brainstorm + plan 流程 |
| **6 项过但 ROI 边际** | Phase 1 自己写 Planner（更可控）；AgentScope 推迟到 Phase 5 复杂任务 |
| **V0 失败（依赖冲突）** | 评估升级 SB 版本可行性；或回退自建 |
| **V1 失败（权限绕过）** | 立刻停；这是企业级红线，无法妥协 |
| **V3 失败（chunk 元数据丢失 / SSE 序列不兼容）** | 立刻停；评估 SAA Graph 是否能解（Graph 状态机更显式），或回退自建 |
| **V4 / V5 失败（trace 断 / model 路由绕过）** | 报告冲突点；判断是否值得补 Adapter；大概率回退自建 |

---

## 13. POC 报告必答 3 题

D5 输出的 `docs/dev/design/2026-05-01-agentscope-poc-report.md` 必须明确回答：

1. **接入成本**：从 POC 推算，把 AgentScope 接到正式 Phase 1 的 RetrievalQaTaskHandler 里需要多少改动？（具体到模块和 LOC 量级；包括接 SSE emitter 的工作量）
2. **Lock-in 程度**：如果 6 个月后想撤掉 AgentScope，撤回成本多少？（Adapter 边界够不够干净；`ToolExecutionContext` 的依赖深度）
3. **直接收益**：相比纯自建 Planner/Executor，AgentScope 在 V1 / Phase 1 阶段实际节省了什么？（如果只是包了一层 ReAct loop 但 RAG 链路还是自己的，可能净收益是 0 甚至负）

**第 3 题特别关键**：POC 通过 ≠ 应该用。POC 验证"能不能"，决策"该不该"还要看 ROI。

---

## 14. 风险与缓解

| 风险 | 严重度 | 缓解 |
|---|---|---|
| **AgentScope-Java 依赖与 SB 3.5.7 冲突（疑似 SB 4.x 或 Spring 7.x 引用）** | 极高 | D0 优先排查；冲突无法排除则 kill POC |
| **chunk 元数据丢失（V3）** | 极高 | 用 `PostActingEvent` hook + 请求级 `SpikeChunkBuffer`，不依赖 ThreadLocal stash |
| **SSE 事件序列与生产不兼容**（`SOURCES` 不在首 `MESSAGE` 前 / payload diff） | 极高 | D3 强断言，不通过 kill POC；参照 `bootstrap/CLAUDE.md` 的硬约束 |
| **AgentScope 1.0.x 刚发，稳定性未验证** | 高 | POC-A 隔离边界；失败可整体删除 `agent-spike/` |
| **权限绕过（Knowledge 没接 KbReadAccessPort）** | 极高 | V1 强单测；权限路径必须走 `RetrievalScopeBuilder` + `AuthzPostProcessor` |
| **trace_id 跨 Agent 工具回调线程断裂** | 高 | 在 `OpenSearchKnowledgeAdapter` 显式 `RagTraceContext.set` / `clear`；V4 验证 |
| **AgentScope JSONL trace exporter 落盘敏感数据**（含 prompt / tool input / error） | 高 | spike 默认禁用 JSONL exporter；如启用必须配脱敏 |
| **ReAct loop 多轮 reasoning 导致 token 翻倍** | 中 | V2 设硬上限；超标判定 ROI 负 |
| **`AccessScope.All` sentinel 被 spike 误处理为 `Ids(allKbIds)`** | 中 | 复用现有 `RetrievalEngine`，不在 spike 重写 scope 应用逻辑 |

---

## 15. 与上下游 roadmap 的关系

| Roadmap | 关系 |
|---|---|
| [4-22 Agent 化 + 持续学习层 Roadmap](./2026-04-22-agent-continual-learning-roadmap.md) | POC 通过 → Phase 1 TaskRun schema 设计可参考 AgentScope 的 PlanNotebook 输出语义；POC 失败不影响（持续学习层独立于 Agent 内核） |
| [4-26 P0 架构审计计划](./2026-04-26-architecture-p0-audit-plan.md) | POC 不依赖 P0 完成（不引入新生产代码，零 bootstrap 改动）；Phase 1 转正前必须等 P0 完成 |
| V1 统一架构方案 | POC 是 V1 Phase 0 的"框架决策 spike"。POC 结果决定 Phase 1 RetrievalQaTaskHandler 内部用 AgentScope 还是自建 |
| [4-26 Permission Roadmap](./2026-04-26-permission-roadmap.md) | POC 必须验证 `KbReadAccessPort` / `RetrievalScopeBuilder` / `AuthzPostProcessor` / `security_level` 链路完整保留（V1 验收） |

---

## 16. 验收完成的判定

POC 在以下条件全部满足时算完成（不区分通过 / 失败）：

- [ ] `agent-spike/` 模块代码已提交
- [ ] V0–V5 每项都有可复现证据（单测 / 对比表 / trace 截图 / dependency:tree）；如某项被 kill switch 提前终止，需在报告中说明哪一项卡住
- [ ] 10 题对比表已归档（answer 相似度 / source overlap / latency / token / LLM 调用次数）；如 V3 提前 kill 仅需 1 题部分数据
- [ ] POC 报告 `2026-05-01-agentscope-poc-report.md` 已写完，3 个必答题有明确结论
- [ ] 分叉决策已选定（§12 矩阵 6 个分支之一），并在报告中说明理由
- [ ] D0 输出 `D0_dependency_report.md` 已归档

POC 之后的清理 / 转正动作不在 POC 完成判定内，作为 §17 后续动作单独跟踪。

---

## 17. 后续动作（POC 完成后跟踪）

| POC 结果 | 后续动作 |
|---|---|
| 通过 | 1) **不直接转正 spike 代码**；启动 V1 Phase 1（TaskRun + RetrievalQaTaskHandler + Workbench 前端）的 brainstorm + plan 流程<br>2) `agent-spike/` 暂留作 Phase 1 实现参考，Phase 1 设计批准后删除 |
| 失败 | 1) 清理：`agent-spike/` 模块从主仓移除<br>2) 重新评估 V1 Phase 1 的 Agent Runtime 实现路径（自建 vs SAA Graph vs 推迟 Agent 化） |

---

## Appendix A：v1 → v2 修订映射

| v1 章节 | v1 内容 | v2 处理 |
|---|---|---|
| §4 整体架构 | ChatController + RagChatRouter 分流 | 删除；改为 `@SpringBootTest` + `SpikeStandaloneRunner` |
| §6 物理布局 | `bootstrap/src/main/java/.../rag/spike/SpikeRagPort.java` + `RagChatRouter.java` | 删除；bootstrap 零改动 |
| §7 Feature Flag | `application.yaml` + `t_user_feature_flag` + `X-Chat-Engine` header | 全部删除；POC-A 不需要灰度 |
| §8 SpikeRagPort | `rewrite / classifyIntent / buildScope / search` 自定义接口 | 改为直接复用现有 bean，零新接口 |
| §8 buildScope 签名 | `buildScope(UserContext user, IntentResult intent, List<Long> kbIds)` | 删除；改用现有 `RetrievalScopeBuilder.build(String requestedKbId)` |
| §8 search 签名 | `search(String query, RetrievalScope scope)` | 改为 `RetrievalEngine.retrieve(RewriteResult, List<SubQuestionIntent>, RetrievalScope)`，对齐现有契约 |
| §8 RetrievalScope 注入 | 塞进 `RagTraceContext` ThreadLocal | 改为 AgentScope `ToolExecutionContext` 显式传递 |
| §9 V4 (chunk 元数据) | "diff 为空" 表述模糊 | 拆为 V3：(a) 顺序 (b) payload diff (c) `SOURCES` 在首 `MESSAGE` 前 三个明确断言 |
| §9 验收 | 5 项 V + JSON schema 兼容 | 改为 6 项 V + SSE 事件序列兼容 |
| §10 D1–D5 | 5 天，未明确 D0 | 改为 6 天，D0 显式 gate |
| §14 风险 | 笼统提兼容性 | 拆为 V0（Spring Boot 冲突）+ JSONL trace 敏感数据等 9 条 |
| 包名 | `com.nageoffer.ai.ragent` | 改为 `com.knowledgebase.ai.ragent`（与现有代码一致） |
