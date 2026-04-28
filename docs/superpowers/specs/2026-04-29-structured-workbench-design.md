# 结构化工作台 v1 设计（Ops 场景驱动）

- **状态**：Draft（待 review）
- **日期**：2026-04-29
- **责任分工**：架构 / 后端 / 前端协同；本设计为 v1 范围锁定与架构分层定义
- **范围**：把当前"KB 中心化 RAG 问答"产品形态升级为"结构化工作台"，承载 Ops 部门 4 个具体业务场景（COB/SOP 问答 + Collateral 筛查 + 协议提取），并为持续学习层埋点。
- **输入**：Ops 部门业务需求（COB chatbot / SOP chatbot / Collateral 智能筛查 / 协议智能提取）
- **关联文档**：
  - `docs/dev/design/2026-04-22-agent-continual-learning-roadmap.md`（持续学习四层金字塔）
  - `docs/dev/design/2026-04-26-permission-roadmap.md`（权限 7 阶段路线）
  - `docs/dev/design/2026-04-26-architecture-p0-audit-plan.md`（架构 P0 审计）
  - `docs/dev/design/2026-04-25-answer-pipeline-spike-adr.md`（回答管线决策）

> 本文是**设计 spec**，不是实施 plan。spec approve 后由 `superpowers:writing-plans` 产出 plan，再拆 PR。

---

## 1. 背景与 North Star

### 1.1 业务驱动

Ops 部门当前最优先的 4 个 AI 应用场景：

| # | 场景 | 类型 | 关键诉求 |
|---|------|------|---------|
| 1 | COB chatbot | 知识问答 | 业务/制度守则/操作流程咨询；缓解人工 COB 团队重复咨询压力；沉淀合规知识资产 |
| 2 | SOP chatbot | 知识问答 | 标准操作流程精准 AI 客服；加速新员工 onboarding 与日常操作效率 |
| 3 | Collateral 智能筛查 | 检索 + 提取 | 从合规协议/流程文件/法务共享检索关键信息（A/B 方角色、保证金流程、MTA、IA、联动账户等） |
| 4 | 协议智能提取 | 文档智能 | 从 ISDA/GMRA 协议提取关键业务字段，输出固定模板 Excel，导入 cloudmarging 系统替代人工录入 |

### 1.2 当前产品形态的根本短板

当前系统是**"KB 中心化"** — 用户进入流程是"登录 → /spaces 看 KB 列表 → 选 KB → /chat 开始问答"。这个形态在面对 Ops 4 个场景时暴露 3 个无法回避的问题：

1. **协议提取无家可归**：上传文档 → 字段抽取 → Excel 下载，根本不是聊天形态。硬塞到 KB 聊天框里 UX 撕裂。
2. **同语料服务多场景的低效**：COB 和 SOP 本质上是同一种"知识问答"能力，但在 KB 中心化模型下被实现为两套各自的 KB +各自的 prompt 调优 + 各自的 eval。
3. **跨能力任务无归宿**：Collateral 筛查既要检索（像知识问答）又要抽取关键字段（像文档智能），归不进任何一个 KB 的"对话框"形态。

### 1.3 拒绝的另一种形态：PaaS 助手作坊

业内常见做法是搭一个"agent 拖拽平台"——用户/管理员拖拽配置一个新 helper，助手列表无限扩张。但这种形态的根本问题是**复用单位错了**：每个 helper ≈ 80% 配置（prompt/语料/工具/UI）+ 20% 代码（编排/护栏/eval），平台把这两部分捆在一个 entity 里鼓励整体 fork，结果配置和代码都被复制——同一份制度文档被 N 个 helper 各自上传 N 份，模型升级要挨个改 helper，没人能回答"这 N 个 helper 底层有几种本质能力"。

我们的形态选择：**任务（用户层）+ Agent（系统层）+ 共享底座**三层抽象，把"20% 代码"沉淀到少数有 owner 的 Agent 库，把"80% 配置"做成轻量任务定义。

### 1.4 North Star

- **对 Ops 用户**：进入工作台直接看到能力区（"知识问答 / 文档处理"），点击直接干活——不用先选 KB、不用"创建任务"。
- **对运营**：所有任务执行结构化落账（Q&A Ledger），未来支撑用户记忆 / 部门 insight / 主动推送 / 反哺 KB。
- **对架构**：能力可复用（Agent 层）、任务可组合（跨 Agent）、底座统一治理（KB / RBAC / Trace / RAGAS）、持续学习横切贯穿。

---

## 2. 非目标

明确不在本设计范围（避免 scope creep）：

- ❌ 不做 PaaS 助手平台 / agent 拖拽 builder
- ❌ v1 不做 Pulse 推送、集体记忆/部门 insight、Autodream 调度、反哺 KB 审核入口（推到 v2/v3）
- ❌ 不重构现有 RAG pipeline 内部细节（rewrite / intent / retrieve / rerank 现有实现保留，仅在外层增加 Agent 包装）
- ❌ 不动 KB CRUD 后端逻辑（仅前端入口位置变；/spaces 页面在 v1 内保留为兜底）
- ❌ 不做 DDD 全量重建（与 `memory/project_ddd_rebuild_plan.md` 是替代选择，本设计走渐进改造路线）
- ❌ v1 不引入 OpenFGA / OPA / Casbin（沿用现有 RBAC + security_level 链路）
- ❌ 不替换 OpenSearch / Milvus / pgvector 选型（沿用现有 OpenSearch 主路径）

---

## 3. 核心架构

### 3.1 三层 + 横切层

```
┌────────────────────────────────────────────────────────────┐
│ 用户层（Workbench UI）                                     │
│  💬 知识问答 │ 📤 文档处理 │ 📊 最近 │ 🔔 推送(v2)        │
│   ↓ 隐式创建 task instance（用户无感）                      │
├────────────────────────────────────────────────────────────┤
│ Task 编排层（任务定义 = 配置）                              │  ← 80% 配置
│  TaskTemplate { kb_ids, agent_ref, prompt_overrides, ui }  │
│  TaskInstance { user_id, status, input, output, trace_id } │
├────────────────────────────────────────────────────────────┤
│ Agent 层（可复用能力，少数有 owner）                        │  ← 20% 代码
│  KnowledgeQAAgent  │  DocIntelligenceAgent                 │
│  - 检索 / 引用约束 │  - 字段抽取 / 模板渲染 / 跨文档比对    │
│  - 多轮记忆        │                                        │
├────────────────────────────────────────────────────────────┤
│ 底座（复用现有，不动）                                      │
│  KB · RBAC · security_level · Trace · RAGAS · 模型路由     │
│  · OpenSearch · Tika · Sa-Token · MyBatis · RocketMQ       │
└────────────────────────────────────────────────────────────┘
                           ↓ 任务执行结构化落账
┌────────────────────────────────────────────────────────────┐
│ 持续学习横切层（v1 最小闭环）                                │
│  Q&A Ledger（多任务 schema）                                │
│  └─ User Memory v1（注入 Agent prompt）                     │
│  ╳ Autodream / Collective Memory / Pulse / 反哺（v2/v3）   │
└────────────────────────────────────────────────────────────┘
```

### 3.2 三个抽象的职责边界

#### Task（任务）

- **是什么**：用户视角的"我要做的一件事"，UI 上呈现为能力区入口和"最近"历史。**"task" 是后端 entity 命名，UI 文案严禁出现"任务"二字**（用"知识问答 / 文档处理 / 最近对话 / 提取记录"等用户语义词）。
- **TaskTemplate（任务模板）**：声明"这个任务用哪个 Agent + 绑哪个 KB + 用什么 UI 形态 + prompt 覆盖"。v1 阶段为代码内置 4 个模板（COB / SOP / Collateral 筛查 / 协议提取）。
- **TaskInstance（任务实例）**：用户每次进入能力区并发起第一个动作（提问 / 上传）时**自动创建**，承载该次工作的全部状态（输入 / 中间产物 / 输出 / trace_id）。
- **TaskInstance ≠ Conversation**：知识问答任务下，一个 instance 包含一段多轮对话；协议提取下，一个 instance = 一份上传文档 + 一份 Excel 产出。

#### Agent（能力）

- **是什么**：可复用的能力组件，封装一类业务任务背后共享的编排逻辑、prompt 模板、护栏规则、模型选择。
- **v1 两个 Agent**：
  - `KnowledgeQAAgent` — 服务 COB 问答、SOP 问答。底层能力 = 现有 RAG pipeline（rewrite / intent / retrieve / rerank / prompt build / stream）抽出后的薄包装。
  - `DocIntelligenceAgent` — 服务协议提取、Collateral 筛查的字段抽取部分。底层能力 = 全新搭建（字段 schema 驱动的 LLM 抽取 / 模板渲染 / 跨文档对比）。
- **Agent 接口最小契约**：
  ```java
  interface Agent<I, O> {
    String name();                          // 例如 "knowledge_qa"
    AgentCapability[] capabilities();       // 声明本 agent 提供哪些 skill
    O execute(I input, AgentContext ctx);   // 主入口，ctx 含 user/dept/kb/trace
  }
  ```
- **Agent 不感知任务**：Agent 只接收 input + context，不知道"我现在服务的是 COB 还是 SOP"。任务的特化由 TaskTemplate 在调用前注入（kb_ids / prompt_overrides）。

#### 跨 Agent 任务：Collateral 筛查

Collateral 筛查既要检索（命中相关协议条款）又要抽取关键字段（A/B 方角色、MTA、IA），是验证两层抽象价值的关键场景。

- **TaskTemplate 声明**：`agents = [KnowledgeQAAgent, DocIntelligenceAgent]`，外加一个"组合策略"——v1 是顺序调用（先检索得相关 chunk，再对命中片段做字段抽取）。
- **不需要"协调器 Agent"**：Task 编排层负责调用顺序与结果传递；Agent 仍然单一职责。
- **未来扩展**：当跨 Agent 组合复杂到一定程度（条件分支、并行、循环），可演进为"工具编排 Agent"（v2 候选），但 v1 不引入。

### 3.3 底座（复用，不动）

完全沿用现有：
- **知识库**：`knowledge` 域 CRUD 不变；`t_knowledge_base` 表不动。
- **权限**：沿用 RBAC（user/role/dept）+ security_level + 当前推进中的 PR1-5 chain（KbAccessService 退役、KbAccessCalculator、RetrievalScopeBuilder、metadata filter hardening）。所有任务执行走现有 `AuthzPostProcessor` / `MetadataFilterBuilder` 链路，**零旁路**。
- **追踪**：`RagTraceContext` ThreadLocal 不动；TaskInstance 持有 trace_id。
- **评测**：`eval` 域 RAGAS 闭环不动；任务级 RAGAS 分布是 v2 部门看板的输入。
- **向量后端**：OpenSearch 主路径不变。

---

## 4. 持续学习横切层（v1 最小闭环）

### 4.1 设计原则

- **Ledger 是唯一数据源**：上层产物（User Memory / 未来的集体记忆 / Pulse 内容池）禁止直接读 `t_message` / `t_rag_evaluation_record` 等原始表；只能读 Ledger。这一约束是 schema 演化时改动面收敛的关键。
- **横切而非顶层**：持续学习不是"金字塔顶尖"，是与三层架构正交的横切层——所有任务执行落账（向下读 Agent 输出），所有 Agent prompt 阶段消费 Memory（向上注入），所有反哺写回底座 KB（向上写）。
- **v1 只做"读+注入"，不做"写回 KB"**：v1 闭环是 Ledger → User Memory → Agent prompt 注入。反哺 KB / Pulse 推送 / 集体 insight 全部 v2/v3。

### 4.2 v1 必做

| 组件 | 内容 | 落地位置 |
|------|------|---------|
| **Q&A Ledger 表** | 多任务 schema：`task_instance_id / task_type / agent_name / user_id / dept_id / kb_ids[] / input / output / feedback / trace_id / cost / created_at`；问答任务 + 文档智能任务统一落账（output 字段为 JSON，按任务类型存不同结构） | 新表 `t_task_ledger`（决策详见 §6.4） |
| **统一落账钩子** | TaskInstance 生命周期事件 → Ledger 写入；通过事件而非 service 直调，避免业务 service 持有 Ledger 写依赖 | `framework` 新增 `TaskLedgerListener`；通过 Spring `ApplicationEvent` 解耦 |
| **User Memory 表** | per-user / 跨任务跨会话的事实 + 偏好 + 关注主题；schema：`user_id / fact_type / fact_value / source_task_instance_id / created_at / user_visible / user_editable` | 新表 `t_user_memory` |
| **Memory 注入钩子** | Agent.execute 进入 prompt build 阶段时，从 `t_user_memory` 拉取当前 user 的 memory 段，注入到系统 prompt | `framework` 新增 `MemoryInjector`；Agent 接口预留 `enrichPrompt(ctx)` hook |
| **User Memory 用户可视页** | 用户在前端能看到"系统记得我什么"，可编辑 / 可导出 / 可删除（合规要求） | `frontend` 新增 `/profile/memory` 页面 |

### 4.3 v2/v3 推后

| 组件 | 推后到 |
|------|--------|
| Autodream 调度（夜间批任务） | v2 |
| 集体记忆 / 部门 insight 看板 | v2（依赖 Autodream） |
| Pulse 推送（站内卡片 / 邮件 / 飞书） | v2 |
| 反哺 KB 候选审核入口 + `source=llm_wiki` 标记 | v3 |
| 跨任务 / 跨文档 insight（如"50 份新签 ISDA 中 3 份 MTA 异常"） | v3 |

### 4.4 与 `2026-04-22-agent-continual-learning-roadmap.md` 的关系

完全承接 P0–P5 的方向，但有两个**关键调整**（对齐结构化工作台架构）：

1. **Ledger schema 扩成多任务类型**：原 roadmap §3.2 的 P0 是"问答专用"（基于 `t_message`），本设计要求 Ledger 从一开始就承载多种任务类型（问答 / 文档抽取 / 未来工具编排）。否则 v2 加文档智能任务时要做迁移。
2. **Memory 注入点改到 Agent 层 prompt 阶段**：原 roadmap 暗含"在 RAG pipeline 内注入"，本设计明确放到 Agent 接口的 `enrichPrompt(ctx)` hook，使所有 Agent（包括未来新增的工具编排 Agent）自动受益，不需要每个 pipeline 各自集成。

---

## 5. UI/UX 设计

### 5.1 路由策略（v1 渐进改造）

```
现有路由（保留 v1 内不动）：
  /spaces        — KB 列表（老入口；不主推但兜底）
  /chat          — 现有 RAG 聊天页（老用户继续可用）
  /admin/*       — 管理后台

新增路由：
  /tasks         — 结构化工作台主入口（采用 §5.2 推荐 UX）
                   注：URL 用 /tasks 仅为路由命名，UI 文案上零"任务"二字。
                       前端组件命名建议用 WorkbenchPanel / ChatPanel / DocPanel，避开 Task* 前缀，
                       避免"路由名 task vs UI 文案不能用 task"的术语污染。
  /profile/memory — 用户可视的 User Memory 编辑页
```

**渐进改造的双轨期**：v1 期间 `/spaces` 与 `/tasks` 并存，前者是老体验、后者是新体验。可通过看板对比两套入口的活跃度 / 任务完成率 / RAGAS 分数，作为 v2 决定旧入口下线时机的依据。

**导航引流**：顶部导航栏加"Ops 工作台"链接到 `/tasks`，并在 `/spaces` 页面顶部加一条引流条（"试试新版工作台 →"），不强制跳转。

### 5.2 工作台主界面（/tasks）

```
┌──────────┬──────────────────────────────────────────────┐
│ Ops 工作台│                                              │
│           │  💬 知识问答（默认选中）                      │
│ 💬 知识问答│  ────────────────────────────────────         │
│ 📤 文档处理│   默认查询你部门可访问的全部知识库              │
│           │   [🟢 COB] [🟢 SOP] [⊕ 限定来源]              │
│ ── 最近 ──│                                              │
│ 📝 EU MTA │  ┌──────────────────────────────────────┐    │
│   政策     │  │ "EU 区域 MTA 默认值是多少？"           │   │
│ 📝 SOP 异常│  │                                       │   │
│ 📤 ISDA1234│  │ 系统：根据 COB §3.2，默认值 €250k...   │   │
│ 🔍 ABC 筛查│  │ 📎 来源：COB Manual §3.2              │   │
│           │  └──────────────────────────────────────┘    │
│ 🔔 推送(v2)│  [继续问...]                                  │
│           │                                              │
└──────────┴──────────────────────────────────────────────┘
```

**关键约束**：
- 用户从来不点"创建任务"。点能力区（💬 / 📤）→ 直接进对应交互形态。
- TaskInstance 在用户**第一个真实动作**时自动创建（提问 / 上传）。空的 TaskInstance 不入库。
- "最近"列表 = 当前用户的 TaskInstance 历史，点击恢复上下文。

### 5.3 知识问答内的 KB chip + 来源卡

- **默认融合**：进入 💬 知识问答，默认所有部门可访问 KB（COB + SOP）都纳入检索范围。来源卡上明确标 `From COB / From SOP`。
- **快捷限定**：聊天框上方 chip 列出用户可访问的所有 KB；点击 chip 切换状态（🟢 启用 / ⚪ 关闭）。
- **限定即写入 task config**：用户限定到"只查 COB"会写入当前 TaskInstance 的 kb_ids 配置；该限定持续到本次对话结束，新建对话恢复默认。

### 5.4 文档处理内的筛查/提取流程

```
点击"📤 文档处理"：

  ┌────────────────────┬────────────────────┐
  │ 🔍 协议筛查         │ 📤 提取关键字段      │
  │ 从已归档协议找信息   │ 上传 ISDA/GMRA       │
  │ [开始筛查 →]        │ → 下载 Excel         │
  │                    │ [上传文档 →]          │
  └────────────────────┴────────────────────┘

  ── 最近处理 ──
  📤 ISDA_ABC_2026.pdf · 提取完成 · [下载 Excel]
  🔍 对手方 XYZ 筛查 · 命中 5 处 · [查看]
```

- **筛查流程**：点"开始筛查" → 输入对手方/关键词 → 调用 `KnowledgeQAAgent` 检索 → 命中协议条款列表 → 用户选定后调用 `DocIntelligenceAgent` 抽字段 → 显示字段卡片。
- **提取流程**：点"上传文档" → 文件上传 → 服务端 Tika 解析 → `DocIntelligenceAgent` 按预定 schema（ISDA / GMRA）抽字段 → 渲染 Excel 模板 → 下载链接 + 进度条（异步任务）。
- **异步任务**：协议提取耗时较长（v1 假设单份 < 30s 同步处理；超 30s 走 v2 异步任务通道）。

### 5.5 用户语义 vs 系统术语词汇映射（spec 强约束）

| 用户看到的词 | 系统 entity / 字段 | 严格约束 |
|------------|------------------|---------|
| 💬 知识问答 | `task_template = knowledge_qa` | UI 严禁说"任务模板" |
| 📤 文档处理 | `task_template = doc_intelligence` | UI 严禁说"任务模板" |
| 📝 一段对话 / 一次提取 / 一次筛查 | `task_instance` | UI 严禁说"任务实例" |
| "最近" | task_instance 历史列表 | UI 严禁说"实例列表" |
| 🟢 COB / SOP chip | `kb_id` 集合 | UI 用 KB 名，不暴露 id |
| 📎 来源卡 | `sources_json` 渲染 | 已有，沿用 |

PR review 时如发现 UI 文案出现"任务/任务模板/任务实例"等技术词，必须打回。

---

## 6. 关键模块设计

### 6.1 后端模块组织

按现有 bootstrap 域驱动惯例，新增 / 改造：

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/
├── workbench/                        ← 新增域：任务编排
│   ├── controller/
│   │   ├── WorkbenchController       ← /tasks 主入口数据
│   │   ├── KnowledgeQATaskController ← 知识问答任务执行
│   │   └── DocIntelligenceTaskController ← 文档处理任务执行
│   ├── service/
│   │   ├── TaskTemplateRegistry      ← 4 个 task template 内置注册
│   │   ├── TaskInstanceService       ← TaskInstance 生命周期
│   │   └── TaskExecutionOrchestrator ← 编排：解析 template → 调 agent → 收集输出
│   ├── domain/
│   │   ├── TaskTemplate              ← 模板定义对象
│   │   ├── TaskInstance              ← 实例 entity
│   │   └── TaskExecutionResult
│   └── dao/
│       └── TaskInstanceMapper
├── agent/                            ← 新增域：Agent 能力库
│   ├── AgentRegistry                 ← agent name → impl 注册表
│   ├── KnowledgeQAAgent              ← 包装现有 RAG pipeline
│   ├── DocIntelligenceAgent          ← 全新搭建
│   └── domain/
│       ├── Agent (interface)
│       ├── AgentContext
│       └── AgentCapability
├── ledger/                           ← 新增域：持续学习落账
│   ├── service/
│   │   └── TaskLedgerWriter
│   ├── listener/
│   │   └── TaskLedgerListener        ← 监听 TaskInstance 完成事件
│   └── dao/
│       └── TaskLedgerMapper
├── memory/                           ← 新增域：用户记忆
│   ├── controller/
│   │   └── UserMemoryController      ← /profile/memory 接口
│   ├── service/
│   │   ├── UserMemoryService
│   │   └── MemoryInjector            ← Agent prompt 注入钩子
│   └── dao/
│       └── UserMemoryMapper
├── rag/                              ← 现有，最小改动
│   └── (现有 RAG pipeline 不动；KnowledgeQAAgent 调用现有 RAGChatService)
├── ingestion/ ...                    ← 不动
├── knowledge/ ...                    ← 不动
├── eval/ ...                         ← 不动（v2 接 ledger 数据后再扩）
├── admin/ ...                        ← 不动（v2 加任务/agent 看板）
└── user/ ...                         ← 不动
```

**架构边界守护**（对齐 `2026-04-26-architecture-p0-audit-plan.md`）：
- `workbench` 域只调用本域 service + `agent` 域接口，不直接注入其他域 mapper。
- `agent` 域只通过 `framework` port 调用底座（KB / RAG / 检索），不直接注入 knowledge / rag 域 mapper。
- `ledger` / `memory` 域通过事件接收数据，避免业务路径上的强依赖。
- 新增 ArchUnit 规则：`workbench` 不得 import `*.dao.*Mapper`（除自己的）；`agent` 不得 import `rag.dao.*` 或 `knowledge.dao.*`。

### 6.2 Task 模板定义（v1 代码内置）

v1 阶段不做 admin UI 配置，4 个模板代码内置（v2/v3 视需要再演进为配置驱动）：

```java
@Component
public class TaskTemplateRegistry {
  private final Map<String, TaskTemplate> templates = Map.of(
    "cob_qa", TaskTemplate.builder()
      .templateName("cob_qa")
      .displayName("COB 问答")  // 注意：实际 UI 文案在前端，不在后端
      .agentName("knowledge_qa")
      .defaultKbIdsConfigKey("workbench.cob.kb-ids")  // 启动期从 application.yaml 读取，避免硬编码
      .uiForm(UIForm.CHAT)
      .build(),

    "sop_qa", TaskTemplate.builder()
      .templateName("sop_qa")
      .agentName("knowledge_qa")
      .defaultKbIdsConfigKey("workbench.sop.kb-ids")
      .uiForm(UIForm.CHAT)
      .build(),

    "collateral_screening", TaskTemplate.builder()
      .templateName("collateral_screening")
      .agentNames(List.of("knowledge_qa", "doc_intelligence"))  // 跨 agent
      .compositionStrategy(SEQUENTIAL_RETRIEVE_THEN_EXTRACT)
      .defaultKbIdsConfigKey("workbench.collateral.kb-ids")
      .uiForm(UIForm.SCREENING)
      .build(),

    "agreement_extraction", TaskTemplate.builder()
      .templateName("agreement_extraction")
      .agentName("doc_intelligence")
      .extractionSchema("ISDA")  // v1 hardcode 两种：ISDA / GMRA；v2 配置化
      .uiForm(UIForm.UPLOAD_AND_DOWNLOAD)
      .build()
  );
}
```

**关键不变量**：
- `defaultKbIds` 是默认值，运行时仍受 RBAC 过滤（用户实际可访问 KB ⊆ defaultKbIds）。
- `agentName(s)` 必须能在 `AgentRegistry` 解析到实例，启动期校验。

### 6.3 Agent 接口

```java
public interface Agent<I, O> {
  /** 唯一标识，与 TaskTemplate.agentName 对应 */
  String name();

  /** 声明本 agent 提供哪些 capability（用于 PR review 和文档自动生成） */
  AgentCapability[] capabilities();

  /** Memory 注入钩子：Agent 在 prompt build 前调用，由 MemoryInjector 实现 */
  default String enrichPrompt(String basePrompt, AgentContext ctx) {
    return basePrompt;
  }

  /** 主执行入口 */
  O execute(I input, AgentContext ctx);
}

public class AgentContext {
  Long userId;       // 异步路径必须从 ctx 取，不读 UserContext.getUserId()
  Long deptId;
  List<Long> kbIds;
  String traceId;
  String taskInstanceId;
  Map<String, Object> attrs;  // task-specific 配置（如 prompt overrides / extraction schema）
}
```

**异步规则**（对齐 `2026-04-26-permission-roadmap.md` 反模式）：Agent 内部任何异步路径（线程池 / RocketMQ consumer）只能从 `AgentContext` 取 user/dept，禁止 `UserContext.getUserId()`。

### 6.4 Ledger schema 决策

**两种方案**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A. 新建 `t_task_ledger` 表** | schema 干净，多任务统一；不污染 `t_message` 历史含义 | 写入需双写（聊天仍写 `t_message` 保持兼容）；查询模型分裂 |
| **B. 扩展 `t_message`** | 单表查询；聊天历史天然落账 | `t_message` 概念被拉宽；文档抽取 output 不像"消息"；schema 演化难 |

**推荐 A**：新建 `t_task_ledger`，所有任务统一落账；`t_message` 保留为聊天专属（继续承载多轮对话上下文，但不再是 ledger 唯一来源）。Ledger 与 `t_message` 的关系是"前者引用后者"——同一段对话的 ledger 行 `output_ref` 指向 `t_message.id` 列表。

**Schema 草案**（写 plan 时细化）：

```sql
CREATE TABLE t_task_ledger (
  id            BIGSERIAL PRIMARY KEY,
  task_instance_id  BIGINT NOT NULL,
  task_template VARCHAR(64) NOT NULL,        -- cob_qa / sop_qa / collateral_screening / agreement_extraction
  agent_names   VARCHAR(256),                -- comma-separated; 跨 agent 任务多个
  user_id       BIGINT NOT NULL,
  dept_id       BIGINT NOT NULL,
  kb_ids        BIGINT[] NULL,
  input         JSONB NOT NULL,              -- query / upload meta
  output        JSONB NOT NULL,              -- answer + sources / extracted fields / screening hits
  output_ref    JSONB NULL,                  -- 引用 t_message.id 等关联表
  feedback      JSONB NULL,                  -- 点赞 / 反馈（v2 填充）
  trace_id      VARCHAR(64) NULL,
  cost_tokens   INTEGER NULL,
  cost_usd      NUMERIC(10,4) NULL,
  status        VARCHAR(16) NOT NULL,        -- success / failed / partial
  created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_user_created ON t_task_ledger (user_id, created_at DESC);
CREATE INDEX idx_ledger_dept_template ON t_task_ledger (dept_id, task_template, created_at DESC);
```

### 6.5 Memory 注入

```java
@Component
public class MemoryInjector {
  private final UserMemoryService memoryService;

  /** Agent 在 prompt build 前调用 */
  public String enrich(String basePrompt, AgentContext ctx) {
    var memories = memoryService.findByUserId(ctx.userId);
    if (memories.isEmpty()) return basePrompt;
    var memoryBlock = renderMemoryBlock(memories);  // "用户角色：EU Ops；近期关注：MTA, IA"
    return basePrompt + "\n\n[用户上下文]\n" + memoryBlock;
  }
}
```

**v1 简化**：Memory 抽取**不做自动 LLM 抽取**——改为"用户主动声明"形态（用户在 `/profile/memory` 页面手填或勾选 facts）。这样：
- 避免 v1 引入抽取 LLM 调用成本
- 避免 PII 风险（用户主动声明的内容默认就是用户授权的）
- 保留接口但不实现自动抽取，v2 加 Autodream 时填充

**v2 增强**：Autodream 调度从 Ledger 抽取 facts → 写入 `t_user_memory` → 标记 `source=auto`，与 user 主动写入的 `source=manual` 区分；用户可见可编辑可删除。

---

## 7. 与现有 roadmap 的对齐关系

| 现有 roadmap | 本设计的位置 |
|------------|-------------|
| **架构 P0 审计** (`2026-04-26-architecture-p0-audit-plan.md`) | 本设计的 §6.1 模块边界规则严格对齐 P0 审计的"允许跨域模式"；新增 ArchUnit 规则纳入 P0 backlog |
| **权限 roadmap** (`2026-04-26-permission-roadmap.md`) | 本设计 §3.3 / §6.3 显式承诺"零旁路 RBAC"；Agent context 必须显式持有 userId/deptId，禁止异步路径读 UserContext |
| **持续学习 roadmap** (`2026-04-22-agent-continual-learning-roadmap.md`) | 本设计 §4.4 已说明两点关键调整：Ledger 多任务化、Memory 注入下沉到 Agent prompt |
| **Answer pipeline ADR** (`2026-04-25-answer-pipeline-spike-adr.md`) | 知识问答 Agent 的内部实现继承 ADR 决策；本设计不重新讨论 |
| **DDD 重建计划** (`memory/project_ddd_rebuild_plan.md`) | **互斥选择**：本设计走渐进改造，DDD 全量重建路线暂搁；如未来选 DDD 重建，本设计的"任务/Agent/Ledger"三个新域可作为限界上下文起点 |

---

## 8. 分阶段交付建议

v1 拆 6 个 PR（细化由 plan 阶段做）：

| # | PR 主题 | 涉及域 | 验收信号 |
|---|---------|-------|---------|
| 1 | 架构骨架：Task / Agent / Ledger 三域空壳 + ArchUnit 规则 | workbench / agent / ledger / framework | 三个新域目录就位，AgentRegistry / TaskTemplateRegistry 可启动期注册，ArchUnit 规则落地 |
| 2 | KnowledgeQAAgent 包装现有 RAG pipeline + COB/SOP 模板 | agent / workbench | `/tasks` 后端能跑通"COB 问答"，结果与 `/chat` 一致 |
| 3 | Q&A Ledger 表 + 落账钩子 + 两个问答任务自动落账 | ledger / workbench | 一次问答后 `t_task_ledger` 有一行；trace_id 串通 |
| 4 | DocIntelligenceAgent + 协议提取（ISDA hardcode） | agent / workbench | 上传 ISDA → 30s 内得到 Excel；字段抽取准确率 baseline 跑通 |
| 5 | Collateral 筛查（验证跨 Agent 组合） | workbench / agent | "对手方 XYZ" 检索命中协议 → 抽字段 → 显示卡片 |
| 6 | 前端 `/tasks` 工作台 + `/profile/memory` + `/spaces` 引流条 | frontend / memory | 用户进 `/tasks` 看到 §5.2 主界面；可手填 memory；下次问答 prompt 含 memory |

每个 PR 各自走 brainstorm（必要时） → spec → plan → impl 的 superpowers 流程。

---

## 9. 风险与缓解

| 风险 | 严重度 | 缓解 |
|------|-------|------|
| **架构纪律失守 → Agent 数量爆炸退化为 PaaS** | 高 | 新增 Agent 必须走架构 review；ArchUnit 规则限制新 agent 包必须实现 `Agent` 接口；任意 PR 增加超过 1 个 Agent 必须 spec |
| **协议提取准确率不达标** | 中 | v1 限定 ISDA / GMRA 两种 schema；接 RAGAS 类似的字段级 eval（"抽对的字段数 / 总字段数"），上线前 baseline ≥ 80% |
| **TaskInstance 创建不收敛 → 数据库膨胀** | 中 | 严格"用户第一个真实动作才创建"；空 instance 不入库；定期清理 7 天无操作的 instance |
| **渐进改造的双轨埋点不一致** | 中 | `/spaces` + `/chat` 老路径 v1 不接 Ledger（数据隔离）；运营看板明确标注"仅 /tasks 数据"；v2 决定旧路径下线时再补迁移 |
| **Memory 注入 prompt 失控（爆 token）** | 低 | MemoryInjector 限定 memory 段最大 token 数；超过截断；超大 memory 走 summary 路径（v2） |
| **PII 隐私（v1 用户主动声明形态较安全）** | 低 | v1 user memory 全部 user-visible / user-editable / user-deletable；导出能力 |
| **/tasks 与 /spaces 双入口数据相互污染** | 低 | TaskInstance / Conversation 在 schema 上隔离；`t_conversation.kb_id` 不变 |

---

## 10. 未决问题（写 plan 时决定）

1. **TaskInstance 与 Conversation 关系**：知识问答任务下，一个 TaskInstance 是否对应一个 `t_conversation`？还是 instance > conversation 多对一？
2. **协议提取的 schema 配置**：v1 ISDA/GMRA hardcode；v2 是否做 admin UI 配置 / yaml 配置 / 数据库表？
3. **跨 Agent 任务的事务边界**：Collateral 筛查中检索 + 抽取的失败重试 / 部分成功怎么处理？
4. **/tasks URL 命名**：是否改名为 `/workbench`（避免 task 字眼污染团队术语习惯）？已在 §5.1 标注路由命名风险，需 PM / 前端最终确认。
5. **Memory 注入的 token 预算**：默认上限多少？是否可被 task template 覆盖？
6. **OpenSearch index 设计**：协议提取产出的字段是否进 OpenSearch（用于跨文档对比 insight v3）？
7. **异步任务通道**：协议提取 > 30s 时的异步通道走 RocketMQ 还是新起 Spring `@Async` 池？
8. **前端组件命名约束的强制工具**：是否加 ESLint 规则禁止 `Task*` 组件名出现在用户可见区域？

---

## 11. 验收标准（v1 整体完成的判据）

- [ ] `/tasks` 主入口可访问，不需要"创建任务"动作即可开始问答 / 上传
- [ ] COB 问答结果与现有 `/chat` 在同一 query 下答案一致性 ≥ 95%（RAGAS 对照）
- [ ] 协议提取（ISDA）字段准确率 baseline ≥ 80%
- [ ] Collateral 筛查能跑通"检索 → 命中 → 抽字段"完整链路
- [ ] 任意一次任务执行后 `t_task_ledger` 有对应行 + trace_id 串通
- [ ] `/profile/memory` 用户可手填 / 编辑 / 删除 memory，下次提问 prompt 含 memory 段
- [ ] 所有任务执行受现有 RBAC + security_level 拦截，零旁路（接 PR1-5 测试用例验证）
- [ ] ArchUnit 新规则全部 green
- [ ] UI 文案 review 通过：零"任务/任务模板/任务实例"等技术词
- [ ] `/spaces` + `/chat` 老路径在 v1 内仍可用（兜底）

---

## Appendix A：术语表

| 术语 | 定义 |
|------|------|
| **结构化工作台** | 本设计目标产品形态，承载 Ops 多场景的统一入口 |
| **能力区** | UI 上的一级功能（💬 知识问答 / 📤 文档处理）；用户视角概念 |
| **TaskTemplate** | 后端 entity；声明"这种任务用哪个 Agent + 哪个 KB + 什么 UI 形态" |
| **TaskInstance** | 后端 entity；用户每次执行任务的实例，承载状态、输入、输出、trace |
| **Agent** | 系统层可复用能力包（KnowledgeQAAgent / DocIntelligenceAgent） |
| **Skill / Capability** | Agent 内部的能力声明（如"字段抽取"、"模板渲染"），暂为文档/PR review 用，不是运行时第一公民 |
| **Q&A Ledger** | 持续学习层底盘；多任务统一结构化落账 |
| **User Memory** | per-user 的事实/偏好；v1 用户主动声明，v2 Autodream 抽取 |
| **底座** | KB / RBAC / Trace / RAGAS / 模型路由 / 向量库等共享基础设施 |
