# Agent 化 + 持续学习层 Roadmap

- **Status**: Draft / 方向对齐阶段，尚未选定首个落地 Phase
- **Date**: 2026-04-22
- **Owner**: TBD
- **上下文**: 从 OpenViking 调研 → user memory 方案讨论 → 延伸出的系统级 roadmap
- **相关记忆**: `memory/project_source_display_plan.md`（来源展示）、`memory/project_permission_redesign.md`（RBAC）

> 本文不是实施 spec，是**方向性 roadmap**。选定 Phase 后，每个 Phase 各自进入 brainstorming → spec → plan → implementation 流程。

---

## 1. 背景与 North Star

### 1.1 现状短板

传统 RAG（本项目当前形态）每次问答都是**从零开始**：`query → rewrite → intent → retrieve chunks → prompt → answer`。问答过程中沉淀的信息（用户意图、高频话题、回答质量、点赞/复问信号）**不会反哺系统本身**。结果：

- 同一个用户反复问相似问题，系统永远"不认识"他
- 同一个部门普遍的知识盲区，运营看不到也无法干预
- 知识库里文档堆积，但用户真正在问什么、KB 覆盖没覆盖，缺乏闭环

### 1.2 North Star：把问答助手做成「知识库 Agent」

- **对用户**：一个带 memory 的个性化 agent，能在跨会话/跨 KB 层面记得"这个人是谁、关心什么、问过什么"
- **对运营**：一套自运转的知识运营后台，能持续产出"用户画像 / 部门热点 / 覆盖盲区 / 高价值 Q&A"
- **对知识库本身**：高质量 Q&A 能经审核反哺回 KB，形成自生长

### 1.3 三处外部参考

| 来源 | 借鉴点 |
|------|--------|
| Karpathy "LLM WIKI" | LLM 从原始文档 + 历史 Q&A 构建可索引 wiki；每次问答沉淀为集体记忆资产 |
| Claude Code autodream | 夜间离线批任务整合用户记忆、梳理部门知识 |
| ChatGPT Pulse | 基于用户历史问题，每日主动生成个性化简报推送 |

三者共用同一底座：**可被 LLM 反复消费的结构化 Q&A 沉淀池**。

---

## 2. 总体架构（四层金字塔）

```
┌────────────────────────────────────────────────┐
│  Pulse (每日推送)                              │  ← 面向用户的主动触达
├────────────────────────────────────────────────┤
│  User Memory        │  LLM WIKI (集体记忆)     │  ← 个人资产 / 集体资产
├────────────────────────────────────────────────┤
│  Autodream (定时批任务)                        │  ← 夜间 LLM 整合引擎
├────────────────────────────────────────────────┤
│  Q&A Ledger                                    │  ← 底座：每次问答的结构化沉淀池
└────────────────────────────────────────────────┘
           ↑ 复用
┌────────────────────────────────────────────────┐
│  现有 RAG Pipeline（rewrite/intent/retrieve）  │
└────────────────────────────────────────────────┘
```

**关键设计原则**：Ledger 是唯一数据源，上面所有层都是 Ledger 的消费者。不允许上层直接读 `t_message` / `t_rag_evaluation_record` 等原始表，避免未来 schema 演化时改动面爆炸。

---

## 3. 分阶段拆解（Phases P0–P5）

| Phase | 交付物 | 复杂度 | 依赖 | 现状复用 |
|-------|--------|--------|------|----------|
| **P0. Q&A Ledger** | 每次问答结构化落库（query / intent / chunks / answer / 反馈 / traceId / kbId / userId / deptId / embedding） | 中 | 无 | 部分已有 — `t_message`、`t_rag_evaluation_record`、`RagTraceContext`、`sources_json`（v1.9） |
| **P1. User Memory** | 从 Ledger 抽取 per-user 事实/偏好/关注领域；支持显式 + 隐式 + 审阅编辑 | 中 | P0 | 无 |
| **P2. LLM WIKI（集体记忆）** | 从 Ledger 聚类生成 per-KB / per-dept 主题摘要 + 覆盖盲区 + 高频问题图谱 | 高 | P0 | 无 |
| **P3. Autodream 调度** | 夜间批任务编排（扫 Ledger → 写 P1/P2），含成本保护 / 失败重试 / trace | 中 | P0 | 无（Spring `@Scheduled` 够用） |
| **P4. Pulse** | 每日每用户生成推送内容 + 触达通道（SSE/邮件/飞书机器人） | 中 | P1 + P2 | 无 |
| **P5. 反哺 KB** | 高质量 Q&A → 候选 FAQ chunk，人工审核后入 KB；需与现有 ingestion pipeline 对接 | 高 | P0 + P2 | 无 |

### 3.1 依赖路径

- **用户个性化主线**：`P0 → P1 → P4`
- **知识运营主线**：`P0 → P2 → P5`
- **共用基建**：`P3` 在 P1/P2 后引入，把手工/on-demand 批任务升级为调度驱动

### 3.2 P0 是硬前置

没有 Ledger，P1-P5 全都悬空。Ledger 的 **schema 稳定性 + 消费者接口抽象** 是整套系统的架构要害。第一阶段必须先做 gap 分析：

- `t_message` 目前存什么？（query、answer、thinking、sources_json）
- `t_rag_evaluation_record` 目前存什么？（RAGAS 评测三元组）
- `RagTraceContext` 目前落盘什么？（trace → run → extra_data）
- **差什么**：intent 结构化字段？chunk 粒度的命中记录？用户反馈信号？query 语义向量？

分析结论决定：新建 `t_qa_ledger` 表 vs 给 `t_message` 扩列 vs 做 view 聚合。

---

## 4. 风险与现实约束

| 风险 | 说明 | 缓解方向 |
|------|------|----------|
| **P2 幻觉注入** | LLM WIKI 自动生成 wiki，如果不设闸门会把幻觉写回 KB | 强制人工审核闸门；生成产物标记 `source=llm_wiki` 与人工文档区分 |
| **成本爆炸** | 每轮对话抽 memory + 夜间批任务 + pulse 生成 = LLM 调用放大数十倍 | 分级模型路由（抽取用小模型，Pulse 用大模型）；配额阈值 + kill switch |
| **隐私 / PII** | Memory 会自动抽取用户身份/部门/项目，泄露面扩大 | 用户可见 + 可编辑 + 可导出删除；敏感字段黑名单；dept 内可见不可跨 dept |
| **触达通道复杂度** | Pulse 推送涉及邮件/飞书/站内，每个通道都是独立系统 | P4 先只做站内（登录后展示），邮件/飞书作为 P4.1 扩展 |
| **质量漂移** | 持续积累 ≠ 持续改进；无度量会变质量黑洞 | 沿用现有 RAGAS 评测管道（`t_rag_evaluation_record`），每个 Phase 上线前后必须跑对比 |
| **与 RBAC 交叉** | user memory / 集体记忆必须尊重部门 + security_level 隔离 | 所有 Ledger 查询走现有 `AuthzPostProcessor` / `MetadataFilterBuilder` 链路，不绕过 |

---

## 5. 未决问题

以下问题在选定 Phase 后的详细 brainstorm 中解决：

1. **Ledger 表 vs 视图**：新建 `t_qa_ledger` 还是扩 `t_message`？
2. **Memory 抽取策略**：显式 / 隐式 / 审阅编辑三选一还是混合？
3. **集体记忆粒度**：per-KB、per-dept、还是 per-topic-cluster？
4. **Pulse 触达通道首选**：站内、邮件、飞书？
5. **反哺 KB 的人工审核入口**：新建 admin 页还是复用现有 KB CRUD？
6. **Autodream 跑在哪**：Spring `@Scheduled` 单机、独立 worker、还是借 RocketMQ delay message？
7. **Memory schema**：自由文本（一行一条）vs 结构化 JSON（role/interests/projects）？
8. **跨 KB memory**：user memory 是全局的还是每 KB 独立？

---

## 6. 下一步

等 owner 确认首个 Phase，然后：

1. 针对该 Phase 单独 brainstorm（superpowers 流程）
2. 产出实施 spec（`docs/dev/design/` 或 `docs/superpowers/specs/`）
3. 写 plan、拆 PR、按照项目现有节奏推进

**推荐起点**：**P0 单独一份 spec**（含 gap 分析），理由见 §3.2。
