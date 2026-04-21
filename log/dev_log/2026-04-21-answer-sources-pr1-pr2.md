# Answer Sources PR1 + PR2 — 元数据管道 + 编排/SSE/前端路由骨架

**日期**：2026-04-21
**分支**：`feature/answer-sources-pr1`（PR #12）+ `feature/answer-sources-pr2`（PR #13 + catch-up PR #14）
**状态**：全部 merged 进 `origin/main`（commit `e560fb4`）
**设计文档**：
- 上游 v1 spec：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`
- PR2 设计：`docs/superpowers/specs/2026-04-21-answer-sources-pr2-design.md`
**实施计划**：`docs/superpowers/plans/2026-04-21-answer-sources-pr2-implementation.md`
**feature flag**：`rag.sources.enabled=false`（PR2-PR4 期间静默，PR5 才翻 true）

---

## 背景与目标

RAG 问答需要把"答案依据的源文档"可视化给用户以便核查。v1 设计拆成 **5 个独立可回滚的 PR**：

| PR | 范围 | 状态 |
|----|------|------|
| PR1 | `RetrievedChunk` + `OpenSearch` metadata 回填 + `findMetaByIds` | ✅ 本 session 完成（跨机，本机承接） |
| PR2 | DTO + `SourceCardBuilder` + `SSEEventType.SOURCES` + `RAGChatServiceImpl` 编排 + 前端 SSE 路由 | ✅ 本 session 完成 |
| PR3 | Prompt 改造 + `remarkCitations` + `CitationBadge` + `<Sources />` UI + 埋点 | ⏳ 未开始 |
| PR4 | `t_message.sources_json` 持久化 + `ConversationMessageVO.sources` + `selectSession` 映射 | ⏳ 未开始 |
| PR5 | `rag.sources.enabled: true` 上线 | ⏳ 未开始 |

本 session 完成 PR1（已在上游预制） + PR2（本 session 原生）。

---

## PR1（跨机承接，本 session 只合并）

上游 `feature/answer-sources-pr1` 分支已在 origin（tip `96b2d06`），本 session 的前半段是把它合进 main。

### PR1 核心改动（6 commit）

| commit | 内容 |
|---|---|
| `a01d1a3` / `5ca0fc5` | spec + Milvus/Pg 注释同步（无逻辑） |
| `4bf8ec6` | `VectorMetadataFields` 加 `DOC_ID` / `CHUNK_INDEX` 常量（SSOT，避字面量漂移） |
| `81fad04` | `RetrievedChunk` 加 `docId` / `chunkIndex` 两个可空字段；`@EqualsAndHashCode(of="id")` 显式固定等值语义为 id-only |
| `e11ec71` | `OpenSearchRetrieverService.toRetrievedChunk` 回填两字段；Milvus/Pg 保持 dev-only 不回填（注释标明） |
| `96b2d06` | 新 `DocumentMetaSnapshot(docId, docName, kbId)` record + `KnowledgeDocumentService.findMetaByIds(Collection<String>)` 批量接口 |

### PR1 副作用（重要，已在 PR #12 body 里标注）

`RetrievedChunk.@EqualsAndHashCode(of="id")` 修复了 `DefaultContextFormatter.java:103` 和 `RAGChatServiceImpl.java:194` 两处 `.distinct()` 的 **latent bug**：之前 `@Data` 全字段等值导致"同 id 不同 rerank score"的 chunks（多意图检索命中同一 chunk）不会被去重，**悄悄膨胀 prompt context**。合并后线上 prompt chunks 数量指标预期下降，不是 regression 而是修复生效。

### PR1 新增测试（11 个）

`RetrievedChunkTest` / `OpenSearchRetrieverServiceTest` / `KnowledgeDocumentServiceImplFindMetaTest`。

---

## PR2（本 session 原生开发，15 commit）

### 流程（严格走 superpowers 全链路）

1. **Brainstorming**：4 轮边界问答（handler 生命周期 / cards 传递 / 推不推判定 / 前端骨架深度）。关键决策：
   - cards 用 `SourceCardsHolder`（set-once CAS 容器）传递，而非 spec 原版的"不可变构造参数"——因为 callback 在检索前就构造，cards 此时不存在
   - orchestrator 决策 + handler 机械发射（`callback.emitSources(payload)` 唯一入口）
   - 3 层闸门锚点为 `distinctChunks.isEmpty()`，**不**用 `ctx.isEmpty()`（MCP-only 漏判）也**不**用 `hasMcp`（mixed 误伤）
   - 移除 spec 原版的 `kbName` 字段（未来在 `findMetaByIds` 返回里扩展，保 rag→knowledge 单 service 边界）
   - 前端**不建** `<Sources />` 组件（和 PR3 的 `CitationBadge` 耦合设计，一起做不返工）
2. **Spec writing** → 2 轮 review 修复 → 签字（commit `0a94996` + `d23ed26`）
3. **Plan writing** → 1 轮 review 修 4 个 P1/P2（Task 7 真实 service @InjectMocks / Task 3 ConfigurationProperties 对齐 spec / Task 12 `--passWithNoTests` / Task 14 具体测试体）→ 签字（commit `cf44ce7` + `bbdbfe7`）
4. **Subagent-driven execution**：14 个 TDD task + 每 task 两阶段 review（spec compliance + code quality），再加最终全量 review
5. **PR 创建**：踩到 stacked-PR 陷阱，第 3 次 PR catch-up 才把 PR2 内容带进 main

### 14 task 对应的 11 个 feature/fix commit

| Task | Commit | 内容 |
|---|---|---|
| T1 | `6465d13` | 3 DTOs + `SSEEventType.SOURCES` + Jackson round-trip 测试 |
| T2 | `d4c65fd` | `SourceCardsHolder`（set-once CAS，4 单测含 8 线程并发） |
| T3 | `86a0527` | `RagSourcesProperties` + yaml |
| T4 | `6ed96ef` | `SourceCardBuilder`（8 单测：分组/排序/preview codePoint 截断/max-cards clip/meta 过滤） |
| T5 | `91d5418` | `StreamChatHandlerParams.cardsHolder` 加 `@Builder.Default @NonNull` |
| T5-fix | `70dac67` | 修 stale bytecode 漏网的错误 import（见下"教训"） |
| T6 | `0f731f6` | `StreamChatEventHandler.trySetCards` + `emitSources`（一行 delegate） |
| T7 | `32c00fd` | `RAGChatServiceImpl` 三层闸门 + `@InjectMocks` 驱动真实 streamChat + Mockito InOrder 锁 `retrieve → build → emit → streamChat` |
| T7-fix | `a0b536f` | 测试硬化：`RagTraceContext.clear()` 生命周期 + `ArgumentCaptor` 验证 payload 内容 + SystemOnly / trySet=false 两条补防 |
| T9 | `1aab75b` | 前端 3 TS 接口 + `Message.sources?` |
| T10 | `0a0c2a1` | `useStreamResponse` switch 加 `case "sources"` |
| T11 | `b868af8` | `chatStore` 把 handlers 从 `sendMessage` 闭包提取为 module-level `createStreamHandlers` export 工厂（10 handlers 全搬 + 新增 `onSources`） |
| T12 | `2438fc5` | vitest 2.1.9 + jsdom 25.0.1 devDeps + `vitest.config.ts` |
| T13 | `9fcedd0` | `useStreamResponse.test.ts` parser-level（1 case） |
| T14 | `843c332` | `chatStore.test.ts` store-level（4 case：stale-stream guard / happy write / non-array drop / onFinish id 替换保留 sources） |

> Task 8（可选 `@SpringBootTest` SSE wire）**故意跳过**：项目无现成 SSE 测试基类，且 Task 7 的 Mockito InOrder 断言已锁定相对顺序（`sender.sendEvent` 同步，单个 `SseEmitter` 上帧序必然有序）。

### 测试总数

- **后端 21 单测**：`SourcesPayloadTest`(2) + `SourceCardsHolderTest`(4) + `SourceCardBuilderTest`(8) + `RAGChatServiceImplSourcesTest`(7)
- **前端 5 单测**（vitest 首次引入）：`useStreamResponse.test.ts`(1) + `chatStore.test.ts`(4)
- 全绿，零 regression

---

## 架构 invariants（PR2 起立的边界）

1. **orchestrator 决策 / handler 机械发射**：SSE 的推不推判定全在 `RAGChatServiceImpl`。`StreamChatEventHandler.emitSources` 一行 delegate 到 `sender.sendEvent`，**不加 try/catch 不加业务分支**。
2. **set-once CAS 容器**：`SourceCardsHolder.trySet(cards)` 在同步段设值（orchestrator 检索+build 之后、`llmService.streamChat` 之前），handler 异步 `onComplete`（PR4 才消费）通过 `.get()` 读到 Optional 快照——**不走 ThreadLocal，不读 TTL**。
3. **三层闸门锚点 `distinctChunks.isEmpty()`**：**严禁**用 `ctx.isEmpty()`（后者定义 `!hasMcp && !hasKb`，MCP-only 漏判）或 `hasMcp`（mixed 误伤）。
4. **"不推 sources" 的两类场景分清**：
   - **真早返回**（答路径也停）：guidance 歧义 / System-Only / `ctx.isEmpty()`
   - **继续回答仅跳过 sources**：flag off / `distinctChunks.isEmpty()` / `cards.isEmpty()`
5. **SSE 事件顺序**：`META → SOURCES → MESSAGE+ → FINISH → (SUGGESTIONS) → DONE`。SOURCES 由 orchestrator 同步 emit，不是 handler 生命周期回调。

---

## 流程教训

### L1：stacked PR 的陷阱（这次踩了，下次注意）

stacked PR 策略：
- PR #12：`feature/answer-sources-pr1` → `main`
- PR #13：`feature/answer-sources-pr2` → `feature/answer-sources-pr1`（base 不是 main）

**PR #12 合完之后，GitHub 不会自动把 PR #13 的 base 改为 main**。PR #13 合并时只合进了中间分支 `feature/answer-sources-pr1`，**没回到 main**。必须再开第三个 PR（#14）从更新后的 PR1 分支 → main 才能把 PR2 内容带进 main。

**以后做 stacked PR 的检查清单**：
1. 父 PR 合并后，`git fetch origin && git log origin/main --oneline | head` 立即确认 main 是否包含子 PR 内容
2. 若缺，立刻开 catch-up PR（head=更新后的中间分支，base=main）
3. 或者：PR1 合并后，手动把 PR2 的 base **改到 main** 再 merge（GitHub UI 支持改 base）

### L2：`mvn compile -q` 的 stale bytecode 假阳性

T5 的 implementer 报告 "BUILD SUCCESS" 但实际代码里 import 路径写错（`rag.dto.sources.SourceCardsHolder` 应为 `rag.service.handler.SourceCardsHolder`，且 same-package 根本不需要 import）。`mvn -pl bootstrap compile -q` 因为 target/ 目录已有旧的 .class 被判为"up to date"，跳过重编译，**瞒过了错误**。

**Controller 注意**：给实施 task 的验证步骤指令里**明确要求 `mvn clean compile`**（不是 `compile`）对有修改的文件。`clean` 一次 7-12 秒成本可接受，换一次 false-positive 防护值得。

### L3：subagent 类型/class 签名要"读真源"而不是"信 spec"

T7 的 implementer 发现 spec 里提到的多个类路径是错的（`RetrievalContext` 在 `rag.dto` 不是 `rag.core.retrieve`、`GuidanceDecision.none()` 不是 `.pass()`、`AccessScope` 在 `framework.security.port` 不是 `user.access` 等）。implementer 正确处理——**先 Read 真源，再写测试**。

**给 subagent 的 prompt 里必须说清**："若 spec 里的类路径/签名和实际代码冲突，以代码为准；读真源再决定"。PR2 的 plan 里就专门写了这条。

### L4：工厂提取解锁测试可达性

前端 `chatStore.ts` 里 10 个 SSE handlers 原本定义在 `sendMessage` 闭包内，外部拿不到引用，store-level 测试无法 drive 具体 handler 行为。**解法**：提取为 module-level `createStreamHandlers(get, set, assistantId, stopTask): StreamHandlers` export 工厂，行为完全不变（所有 handler 体逐字搬运），但测试里可以直接 `createStreamHandlers(useChatStore.getState, useChatStore.setState, 'msgA', stopTaskMock).onSources(payload)` 调用。

这是一个通用模式：**"闭包 handlers 外部不可测"**→**"提取 module-level factory"**。未来前端类似场景（其他 SSE 流、WebSocket handlers、复杂事件路由）可以照做。

---

## Deferred / Follow-ups（已进 `docs/dev/followup/backlog.md`）

- **`SourceCard.kbName` 字段**：PR2 scope 内故意不加。未来补时走"knowledge 侧扩 `findMetaByIds` 返回字段"路径（保 rag→knowledge 单 service 边界），**不要** rag 侧再注 `KnowledgeBaseService`。
- **`chatStore.onSuggestions` 缺 `streamingMessageId` guard**：2026-04-21 code review 发现的 latent bug，和 answer-sources 无关，留给后续做前端 guard 扫荡时一起修。
- **`StreamChatEventHandler` 里 `UserContext.getUserId()` / `RagTraceContext.getEvalCollector()` 的 ThreadLocal 残留**：遗留债，PR2 新增代码已零 ThreadLocal，但没清旧的。PR4 碰 onComplete 时可以顺手清。
- **写路径字符串字面量扫荡**：`"doc_id"` / `"chunk_index"` 在 `IndexerNode.java:230` / `OpenSearchVectorStoreService.java:226-227` / `OpenSearchVectorStoreAdmin.java:280-281`（text block 改不了留注释即可）/ `MilvusVectorStoreService.java:220-221` / `PgVectorStoreService.java:119-120` 仍是字面量，应换为 `VectorMetadataFields.DOC_ID` / `.CHUNK_INDEX` 常量。
- **Milvus/Pg retriever 未回填 `docId`/`chunkIndex`/`kbId`/`securityLevel`**：切换这两个后端之前必须补齐，否则 sources / security_level 过滤全断（PR1 注释已标明）。
- **`npm run lint` 预存在 break**：ESLint 8.57 与 `plugin:react-refresh/recommended` 不兼容。不是 PR2 引入，但阻塞 CI 里的 lint gate。单独任务修。
- **缺席矩阵"意图歧义 clarification"行没写显式单测**：结构上被早返回覆盖，但没有断言锁定。未来 PR3 改 `RAGChatServiceImpl` 时顺手补一个。

---

## PR 列表

| PR | 说明 | 合并时间（UTC） |
|---|---|---|
| [#12](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/12) | PR1 — retrieval metadata plumbing (docId + chunkIndex) | 2026-04-21 11:31:58 |
| [#13](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/13) | PR2 — orchestration + SSE event + frontend routing (flag off) | 2026-04-21 11:34:37（合进 PR1 分支） |
| [#14](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/14) | catch-up：把 PR2 内容从 PR1 分支带进 main | 2026-04-21 后续 |

合并链：`a14b66b`（PR#12）→ `21da7f5`（PR#13 进 PR1 分支）→ `e560fb4`（PR#14 catch-up 进 main）。

---

## 文件改动总览

**后端 11 文件**（6 新 + 5 改）：
- 新：`rag/dto/SourceCard.java` / `SourceChunk.java` / `SourcesPayload.java` / `rag/service/handler/SourceCardsHolder.java` / `rag/core/source/SourceCardBuilder.java` / `rag/config/RagSourcesProperties.java`
- 改：`rag/enums/SSEEventType.java` / `rag/service/handler/StreamChatHandlerParams.java` / `rag/service/handler/StreamChatEventHandler.java` / `rag/service/impl/RAGChatServiceImpl.java` / `application.yaml`

**前端 5 文件**（3 改 + 2 新测试 + 1 新 config + 1 package.json）：
- 改：`src/types/index.ts` / `src/hooks/useStreamResponse.ts` / `src/stores/chatStore.ts` / `frontend/package.json`
- 新：`frontend/vitest.config.ts` / `src/hooks/useStreamResponse.test.ts` / `src/stores/chatStore.test.ts`

**文档 4 commit**：设计 spec + spec fix + plan + plan fix（本 session 产出 + 合入 PR2 分支）。
