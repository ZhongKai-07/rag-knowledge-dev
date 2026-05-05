# Agentic 化改造 PoC 设计文档（AgentScope-java 接入）

- **日期**：2026-05-04
- **目标分支**：`feature/agentscope-poc`
- **作者**：ZhongKai-07（设计 via Claude）
- **决议状态**：📝 草案（待评审）
- **预计改动**：~1500 LOC（后端 ~1100 + 前端 ~300 + SQL ~100），分 P0 / P1 / P2 三个 PR
- **相关**：
  - [`docs/dev/arch/business-flows.md`](../arch/business-flows.md) — 现有 RAG 主链路
  - [`docs/dev/followup/backlog.md`](../followup/backlog.md) — 待新增锚点 `AGENT-1 ~ AGENT-3`

---

## 0. 一句话目标

**在不动现有 `/chat` RAG 主链路的前提下，新增一条 `/agent` 链路，引入 AgentScope-java 的 Plan-and-Execute（外） + ReAct（内）混合编排，把现有 `RetrievalEngine` / `MCPToolRegistry` / 文档处理能力包装成 Tool，做成"企业级办公 Agent"PoC。**

成功指标：

1. 跑通"跨知识库 + 工单查询 + 邮件起草"三步任务，端到端 ≤ 30s（P95）
2. Agent 链路与原 RAG 链路并存，原 `/chat` 行为 0 回归
3. 全链路可观测：Plan 可审计、每步可重放、token 预算可监控
4. 在 max_steps / token_budget / HITL 三道闸门下，**无失控循环**

非目标（明确不做）：

- ❌ 替换现有 `RAGChatServiceImpl` 主链路
- ❌ 引入 AgentScope `Mem0LongTermMemory`（沿用自家 `ConversationMemorySummaryService`）
- ❌ 接入 AgentScope Studio（先用现有 `RagTraceContext` + OTel，PoC 阶段不上可视化）
- ❌ Multi-Agent 协作 / A2A 协议（PoC 单 Agent 即可，多 Agent 留 P2）

---

## 1. 现状基线（接入起点）

### 1.1 现有链路（`RAGChatServiceImpl.streamChat`）

```
入口 → 记忆加载 → 查询改写 → 意图分类 → 歧义引导
     → 多通道检索（KB+MCP 并行，单次）→ Prompt 组装 → 流式 LLM
```

特征：**线性、单次、无反思、工具调用时机硬编码**。

### 1.2 可复用资产清单

| 资产 | 位置 | 复用方式 |
|---|---|---|
| `LLMService` / `ChatClient` 抽象 | `infra-ai/chat/` | AgentScope `Model` 接入层底座 |
| `RoutingLLMService` + `ModelHealthStore` | `infra-ai/model/` | 模型路由直接复用 |
| `RetrievalEngine.retrieve()` | `bootstrap/rag/core/retrieve/` | 包装为 Tool `search_knowledge_base` |
| `MCPToolRegistry` + `RemoteMCPToolExecutor` | `bootstrap/rag/core/mcp/` | 包装为多个 MCP Tool |
| `QueryRewriteService` | `bootstrap/rag/core/rewrite/` | Plan 之前的固定预处理 |
| `IntentResolver` + 意图树 | `bootstrap/rag/core/intent/` | 入口分流（简单 → /chat；复杂 → /agent） |
| `ConversationMemoryService` | `bootstrap/rag/core/memory/` | Memory 适配器（实现 AgentScope `Memory` 接口） |
| `EvaluationCollector` + `RagTraceContext` | `bootstrap/rag/core/trace/` | 升级为 Plan 全轨迹采集 |
| KB 权限过滤（`security_level` / `dept_id`） | OpenSearch query DSL | 通过 `AccessScope` 透传到 Tool |
| Sa-Token + RBAC | `bootstrap/user/` | Tool 调用前权限校验切面 |

---

## 2. 目标架构

### 2.1 入口分流

```
POST /api/ragent/agent/chat
     ↓
[固定] QueryRewriteService.rewrite(query)
     ↓
[固定] IntentResolver.resolve()  →  taskComplexity ∈ {SIMPLE, COMPLEX}
     ↓
   ┌── SIMPLE  → 转发到现有 RAGChatServiceImpl.streamChat（不变）
   └── COMPLEX → AgentOrchestrator.plan(...)
```

**分流策略（PoC 阶段写死，后续可配置）**：

- `SIMPLE`：单一 KB + 单一意图 + 无副作用动词 → 走原链路
- `COMPLEX`：跨 KB / 多意图 / 包含副作用动词（"发"、"创建工单"、"对比"、"汇总") → 走 Agent

### 2.2 Plan-and-Execute 主干

```
┌─────────────────────────────────────────────────────────┐
│ Phase 1: PLAN                                            │
│   Planner Agent（百炼 Qwen-Max，温度 0.1）                  │
│   输入：rewritten_query + intent + access_scope           │
│   输出：JSON Plan {steps: [{id, action, tool, args,       │
│         depends_on, side_effect}], reasoning, est_cost}   │
│                                                           │
│ ┌── [可选] HITL 确认（含副作用步骤时强制） ←─────────┐    │
│ │   /agent/plan/{planId}/approve                    │    │
│ └────────────────────────────────────────────────────┘    │
│                          ↓                                │
│ Phase 2: EXECUTE                                          │
│   PlanExecutor（自研，基于 IngestionEngine 模式扩展）       │
│   for each step in topological order:                    │
│     ├─ 权限校验（AccessScope + 工具 ACL）                  │
│     ├─ 工具调用（含 timeout / retry）                      │
│     ├─ 失败 → 单步 ReAct 子流程（max_steps=3）兜底         │
│     ├─ 副作用工具 → HITL 二次确认                          │
│     └─ 步级结果落 trace + 累加 token 预算                  │
│                          ↓                                │
│ Phase 3: SYNTHESIZE                                      │
│   Synthesizer Agent（流式）                               │
│   输入：original_query + plan + step_results             │
│   输出：最终答案 + sources_json + plan_trace_id           │
└─────────────────────────────────────────────────────────┘
```

### 2.3 Tool 设计（不全平铺）

**暴露给 LLM 自主调用的 Tool**（精挑细选，避免动作空间爆炸）：

| Tool 名 | 包装的现有组件 | 副作用 | 可重复调用 |
|---|---|---|---|
| `search_knowledge_base(kb_id, query, top_k)` | `RetrievalEngine` | 否 | 是（不同参数） |
| `search_across_kbs(query, kb_ids[], top_k)` | `RetrievalEngine` 多 KB 模式 | 否 | 是 |
| `get_document_chunk(doc_id, chunk_id)` | `KnowledgeChunkService` | 否 | 是 |
| `query_ticket(ticket_id)` | `mcp-server/TicketMCPExecutor` | 否 | 是 |
| `query_sales(filter)` | `mcp-server/SalesMCPExecutor` | 否 | 是 |
| `draft_email(to, subject, body)` | 新增（仅起草到草稿区，不发） | 否 | 是 |
| `send_email(draft_id)` | 新增（真正发送） | **是** | **否（HITL 必经）** |
| `create_ticket(payload)` | 新增（业务系统对接） | **是** | **否（HITL 必经）** |

**不暴露为 Tool**（保留为 pipeline 内固定步骤）：

- `QueryRewriteService` — 入口已跑过一次，Agent 不应再改写
- `IntentResolver` — 分流职责，不交给 LLM
- `IntentGuidanceService` — 控制流（HITL hook），不是 Tool
- `RAGPromptService` — Prompt 模板，框架内部使用
- `ConversationMemoryService` — Memory 适配器，框架自动调用

**理由**：让 LLM 只决定"查什么 / 调什么业务系统"，**不让它决定"是否改写 / 是否分流"**——这两步是 deterministic 的。

### 2.4 模块划分

```
bootstrap/
├── agent/                      ← 新增模块
│   ├── controller/
│   │   └── AgentController.java          # POST /agent/chat, /agent/plan/{id}/approve
│   ├── service/
│   │   ├── AgentOrchestrator.java        # 入口分流 + Plan-Execute 主干
│   │   ├── PlannerService.java           # 包装 AgentScope ReActAgent，温度 0.1
│   │   ├── PlanExecutor.java             # 步骤执行器，含 HITL/重试/兜底
│   │   ├── SynthesizerService.java       # 汇总流式生成
│   │   └── HumanApprovalGate.java        # HITL 二次确认 + Redis 待审批队列
│   ├── tool/                              # AgentScope Tool 适配层
│   │   ├── KnowledgeBaseSearchTool.java
│   │   ├── McpToolBridge.java            # 把 MCPToolRegistry 桥接成 Toolkit
│   │   ├── EmailDraftTool.java
│   │   └── ToolPermissionAspect.java     # @AgentTool + Sa-Token + AccessScope 校验
│   ├── memory/
│   │   └── AgentScopeMemoryAdapter.java  # 适配 ConversationMemoryService → AgentScope Memory
│   ├── domain/
│   │   ├── AgentPlan.java                # JSON Plan 结构
│   │   ├── PlanStep.java
│   │   ├── StepResult.java
│   │   └── AgentTraceEvent.java
│   └── dao/
│       ├── AgentPlanDO.java + Mapper     # t_agent_plan
│       └── AgentStepRunDO.java + Mapper  # t_agent_step_run
└── rag/                        ← 不动
```

---

## 3. 关键技术决策（ADR 摘要）

### ADR-1: 用 Plan-and-Execute 而非纯 ReAct 作主干

- **决策**：外层 Plan-and-Execute，子步骤失败时 fallback 到 ReAct（max_steps=3）
- **理由**：企业办公任务步骤多、副作用敏感、需可审计；纯 ReAct 在副作用工具上风险过高
- **代价**：Plan 生成增加一次 LLM 调用（约 +1.5s）
- **替代方案**：纯 ReAct（拒绝，循环风险）、纯 Workflow DSL（拒绝，灵活性不足）

### ADR-2: 现有 RAG 链路保留，不重构

- **决策**：新增 `/agent/chat` 端点，原 `/chat` 不动
- **理由**：80% 流量是简单 KB 问答，Plan-Execute 对它们是过度工程；保留单轮链路保住 P50 延迟
- **代价**：维护两套链路；意图分流可能错判
- **替代方案**：全量切换（拒绝，回归风险高）

### ADR-3: Tool 精挑而非平铺

- **决策**：只暴露 8 个 Tool，QueryRewrite/Intent 等保留为 pipeline 内步骤
- **理由**：动作空间小 → LLM 决策稳定 → 循环概率低；deterministic 步骤交给 LLM 没收益
- **代价**：Plan 不能"跳过"改写
- **替代方案**：全部平铺（拒绝，循环风险 + token 浪费）

### ADR-4: HITL 双闸门

- **决策**：Plan 生成后可选确认；副作用工具调用前强制确认
- **理由**：副作用一旦执行无法撤回（发邮件、创建工单）
- **代价**：交互延迟增加；前端需新增审批 UI
- **实现**：Redis `agent:approval:{planId}` 待审批队列 + WebSocket 推送 + 5min TTL 自动拒绝

### ADR-5: 沿用现有 Memory，不引入 Mem0

- **决策**：写 `AgentScopeMemoryAdapter` 把 `ConversationMemoryService` 适配为 AgentScope `Memory` 接口
- **理由**：避免双 store；`t_conversation_message` 已是单一事实源；摘要逻辑成熟
- **代价**：放弃 Mem0 的实体记忆能力（PoC 不需要）

### ADR-6: 循环防护（六道闸门，缺一不可）

| 闸门 | 阈值 | 实现 |
|---|---|---|
| Plan 步数上限 | ≤ 12 步 | Planner system prompt 约束 + 校验 |
| 单步 ReAct 步数 | ≤ 3 | `ReActAgent.builder().maxSteps(3)` |
| 总 token 预算 | ≤ 32k input / 会话 | Hook 累加，超限强制 finalize |
| 单 Tool 调用次数 | ≤ 5 / 会话 | Toolkit Hook 维护 `Map<ToolName, Count>` |
| 重复参数检测 | 同 Tool+args 连调 ≥ 2 → 终止 | Hook 维护 `Set<ToolCallSignature>` |
| 总耗时 | ≤ 60s | reactive `timeout` 操作符 + 优雅 finalize |

任一触发 → 写 `t_agent_step_run.terminate_reason` + 降级回退到普通 RAG 单轮。

---

## 4. 数据模型

### 4.1 新增表（`upgrade_v1.11_to_v1.12.sql`）

```sql
-- Plan 主表
CREATE TABLE t_agent_plan (
    id              BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    kb_scope        TEXT,                   -- JSON: 涉及的 kb_id 列表
    original_query  TEXT NOT NULL,
    rewritten_query TEXT,
    plan_json       TEXT NOT NULL,          -- 完整 Plan JSON
    status          VARCHAR(32) NOT NULL,   -- DRAFT/APPROVED/EXECUTING/DONE/FAILED/TERMINATED
    terminate_reason VARCHAR(64),           -- max_steps/token_budget/repeated_call/...
    total_tokens_in  INT DEFAULT 0,
    total_tokens_out INT DEFAULT 0,
    duration_ms      INT,
    create_time      TIMESTAMP DEFAULT now(),
    update_time      TIMESTAMP DEFAULT now(),
    deleted          SMALLINT DEFAULT 0
);
CREATE INDEX idx_agent_plan_conv ON t_agent_plan(conversation_id, deleted);

-- 步级运行轨迹
CREATE TABLE t_agent_step_run (
    id            BIGINT PRIMARY KEY,
    plan_id       BIGINT NOT NULL,
    step_id       VARCHAR(32) NOT NULL,    -- Plan 内的逻辑步 id
    seq           INT NOT NULL,
    tool_name     VARCHAR(64) NOT NULL,
    tool_args     TEXT,                     -- JSON
    tool_result   TEXT,                     -- JSON（截断到 8KB）
    status        VARCHAR(32) NOT NULL,    -- PENDING/AWAITING_APPROVAL/RUNNING/DONE/FAILED/SKIPPED
    react_steps   INT DEFAULT 0,            -- 该步内 ReAct 子循环次数
    tokens_in     INT DEFAULT 0,
    tokens_out    INT DEFAULT 0,
    duration_ms   INT,
    error_message TEXT,
    create_time   TIMESTAMP DEFAULT now(),
    UNIQUE (plan_id, step_id)
);
CREATE INDEX idx_step_run_plan ON t_agent_step_run(plan_id);
```

### 4.2 复用 / 扩展

- `t_message.sources_json` —— 扩展 schema：新增 `agent_plan_id` 字段（指向 `t_agent_plan.id`）
- `t_conversation` —— 复用，PoC 阶段 Agent 与原 RAG 共用同一会话表
- `t_rag_evaluation_record` —— 不动，PoC 阶段 Agent 链路单独走 `t_agent_step_run`

---

## 5. PR 分阶段交付

### PR1 (P0)：依赖接入 + LLM 桥接（~300 LOC，~3 天）

**目标**：在新分支引入 `agentscope-spring-boot-starter:1.0.12`，跑通 `Hello AgentScope` + 一次百炼调用。

- 新增 maven 依赖（先不强制 starter，逐步引入）
- `AgentScopeBaiLianModelAdapter`：把 `BaiLianChatClient` 适配为 AgentScope `Model` 接口
- 单测 `AgentScopeIntegrationSmokeTest`：构造 `ReActAgent` + 调用百炼回答 "你好"
- **验收**：测试通过；不引入任何业务逻辑改动

**风险点**：版本/依赖冲突（Spring Boot 3.5.7 vs AgentScope 实际依赖的版本）。如有冲突，**回退到只引入 `agentscope-core` 而非 starter**，自己写 bean 装配。

### PR2 (P0)：Tool 适配层 + KB 检索 Tool（~400 LOC，~4 天）

**目标**：把 `RetrievalEngine` 和 2 个 MCP 工具包装为 AgentScope Tool，能在单 Agent 内调用。

- `KnowledgeBaseSearchTool` + 单测
- `McpToolBridge`：动态把 `MCPToolRegistry` 已注册工具转换为 AgentScope `Toolkit`
- `ToolPermissionAspect`：Sa-Token + `AccessScope` 校验切面
- 集成测试：构造 ReActAgent → 让它"查 KB 中关于 X 的内容"→ 验证 Tool 被调用、权限校验生效
- **验收**：跨 KB 检索可用；越权调用被拒绝

### PR3 (P1)：Plan-and-Execute 主干 + 持久化（~500 LOC，~5 天）

**目标**：完整 Plan→Execute→Synthesize 链路；`t_agent_plan` / `t_agent_step_run` 落库。

- `PlannerService` + Plan JSON schema 定义
- `PlanExecutor`：顺序执行（PoC 阶段不做 DAG 并行）+ 六道闸门
- `SynthesizerService`：流式汇总
- `AgentController`：`POST /agent/chat` 端点
- 数据库迁移 `upgrade_v1.11_to_v1.12.sql`
- 端到端测试：跨 KB 问答 + 工单查询场景
- **验收**：闸门全部生效（mock 触发各种终止条件）；Plan 可在数据库中查到完整轨迹

### PR4 (P1)：HITL + 副作用工具（~300 LOC，~3 天）

**目标**：副作用工具（邮件、工单创建）走 HITL 二次确认。

- `HumanApprovalGate`：Redis 待审批队列 + WebSocket 推送
- `EmailDraftTool` + `SendEmailTool`（带 HITL 注解）
- 前端：`/agent` 页面新增审批弹窗
- **验收**：发邮件场景：Plan 显示 → 执行到发送步暂停 → 用户审批 → 实际发送

### PR5 (P2)：可观测 + 评估（~200 LOC，~2 天，可选）

- 接入 OpenTelemetry（替代部分 ThreadLocal trace）
- 扩展 `EvaluationCollector` 收集 Agent 轨迹
- Grafana 看板：步数分布 / token 消耗 / HITL 通过率 / 终止原因分布

**总计**：~1700 LOC，~17 工作日（PR5 可选）。

---

## 6. 测试策略

### 6.1 单元测试

- `AgentScopeBaiLianModelAdapterTest` — 各种 prompt 形态
- `KnowledgeBaseSearchToolTest` — 权限边界、空结果、超长结果截断
- `PlanExecutorTest` — 六道闸门各自触发、回退降级
- `HumanApprovalGateTest` — TTL 过期、并发审批、拒绝路径

### 6.2 集成测试（黄金集）

3 个 fixture 场景，预期 Plan 步骤数 + 终态：

| 场景 | 步数 | HITL | 终态 |
|---|---|---|---|
| 跨 2 个 KB 的对比问答 | 3 (search × 2 + synth) | 否 | DONE |
| 查工单 + 起草邮件 | 4 (kb + ticket + draft + synth) | 否 | DONE |
| 创建工单（副作用） | 3 + HITL | 是 | DONE |
| 故意循环引诱（重复 search） | — | 否 | TERMINATED (repeated_call) |

### 6.3 回归保护

- 现有 `RAGChatServiceImpl` 单测全部继续跑通
- 端到端：`/chat` 旧端点行为 0 变化（响应结构、流式格式、sources_json）

---

## 7. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| AgentScope 1.0.x 仍有 breaking change | 中 | 中 | PoC 锁版本 1.0.12，启用前手动跑兼容性回归 |
| Plan JSON 格式不稳定（LLM 漂移） | 中 | 高 | JSON schema 严格校验 + 失败重试 1 次 + 降级到 RAG 单轮 |
| Token 成本失控（业务方未预期） | 中 | 中 | 默认 `max_tokens = 32k`，超限即终止；UI 显示估算成本 |
| HITL 阻塞用户 | 高 | 低 | 5min TTL 自动拒绝 + WebSocket 实时推送 + UI 浮窗 |
| 意图分流误判（简单问题进 Agent） | 中 | 低 | 阈值可配置；记录误判率到 metric |
| `mcp-server` 工具新增 / 变更，Agent Toolkit 不感知 | 中 | 中 | `McpToolBridge` 启动时 + 定时（5min）拉取工具列表 |
| Spring Boot starter 与现有 bean 冲突 | 低 | 高 | PR1 先验证；冲突则只引 core，自己装配 |

---

## 8. 决策入口（评审通过即可开做）

请评审者重点确认：

1. **路线 A（边界 PoC）** vs **路线 B（工具层并接）** vs **路线 C（重写主干）** —— 本文档默认 A，能接受吗？
2. **Plan-and-Execute 作主干 + ReAct 作子兜底** —— 这个混合是否符合预期？
3. **PR 拆分粒度（5 个 PR / ~17 天）** —— 是否要再细分或合并？
4. **HITL 走 WebSocket + Redis 队列** —— 还是先用轮询简化 PoC？
5. **新表 `t_agent_plan` / `t_agent_step_run`** —— 命名 / 字段 / 索引是否 OK？
6. **`AgentScopeMemoryAdapter`** —— 沿用现有 Memory 是否赞同（vs 引入 Mem0）？

评审通过后：

- 在 [`docs/dev/followup/backlog.md`](../followup/backlog.md) 新增锚点 `AGENT-1`（PR1）/ `AGENT-2`（PR2-3）/ `AGENT-3`（PR4-5）
- 创建分支 `feature/agentscope-poc`，从 PR1 开始

---

## 附录 A：AgentScope-java 关键能力对应

| 需求 | AgentScope API | 备注 |
|---|---|---|
| LLM 接入 | `Model` 接口 | 自实现适配 `LLMService` |
| Agent 编排 | `ReActAgent.builder()` | 用于子步骤兜底 |
| 工具注册 | `Toolkit` + `@Tool` 注解 | 适配现有 MCP 工具 |
| 短期记忆 | `Memory` 接口 | 自实现适配 `ConversationMemoryService` |
| HITL | `Hook` 系统 + reactive 中断 | 落地审批闸门 |
| 流式输出 | Project Reactor `Flux` | 与现有 `StreamCallback` 桥接 |
| 可观测 | OpenTelemetry 原生 | P2 阶段接入 |

## 附录 B：本文档未覆盖的后续方向

- **Multi-Agent 协作**（A2A 协议）—— 适合"客服 Agent + 检索 Agent + 工单 Agent"分工，留 v1.2+
- **AgentScope Studio 接入** —— 可视化 Plan 调试，团队规模 > 3 人时再考虑
- **Trinity-RFT**（强化学习微调 Agent 行为）—— 等官方 Java 端发布后评估
- **跨会话长期记忆 / 实体抽取**（Mem0）—— 等 Agent 流量稳定后回看
