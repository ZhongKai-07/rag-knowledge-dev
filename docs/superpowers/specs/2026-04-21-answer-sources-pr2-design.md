# Answer Sources PR2 设计 — 编排层 + 数据管道

> 2026-04-21 · v1
>
> **本文档范围**：Answer Sources 功能 5 个 PR 中的 **PR2**。承接 PR1（`feature/answer-sources-pr1` tip `96b2d06`）的元数据基础设施，负责**后端编排 + SSE 事件 + 前端 SSE 路由**，feature flag 默认 off，零用户可见变化。
>
> **上游设计**：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`（v1 总体设计）。本文档在总体设计基础上**收紧 PR2 边界**，并在代码现状与 v1 spec 冲突处以代码为准。
>
> **上游交接**：`docs/superpowers/plans/2026-04-20-answer-sources-pr1-metadata.md`。

---

## § 0 与上游 v1 spec 的差异摘要

| 条目 | v1 spec 原文 | 本文档收紧 | 原因 |
|---|---|---|---|
| cards 通道 | "`StreamChatHandlerParams` 作为不可变构造参数传入" | `StreamChatHandlerParams` 构造时携带 `final SourceCardsHolder`（空壳），orchestrator 在检索后 `trySet(cards)` | callback 在 `RAGChatServiceImpl:L109` 创建时 cards 尚未算出；set-once holder 在"设值→启 LLM 流"同步段内完成固化，onComplete 异步读到 final-like 快照 |
| SSE emit 位置 | 未明确，隐含"handler 内部发" | **orchestrator 决策 + handler 机械发射**：由 `callback.emitSources(payload)` 承担发射动作，orchestrator 不直接摸 `sender` | 边界清晰：orchestrator 决定推不推，handler 只负责机械发/机械存 |
| `SourceCard.kbName` 字段 | 包含 | **移除**（PR2 DTO 里不出现） | PR1 的 `DocumentMetaSnapshot` 无 `kbName`；为装饰字段多开一个 `rag→knowledge` 查询面不值；未来补时应扩展 `findMetaByIds` 返回而非新增 service 调用 |
| "空结果不推"判定 | 口径未拆 | 拆成 **3 层闸门**（见 § 1）；**sources 决策口径统一用 `distinctChunks.isEmpty()`**，不用 `ctx.isEmpty()`，不用 `hasMcp` | `RetrievalContext.isEmpty()` 定义为 `!hasMcp && !hasKb`，MCP-only 成功时 ctx 不 empty；`hasMcp` 会误伤 mixed 场景 |
| `SourcesPayload.messageId` 为空语义 | 空字符串 | **`null`** | 流式阶段 DB id 确实不存在，`null` 语义比 `""` 更准确；前端不依赖此字段定位消息 |
| 前端 `<Sources />` 骨架 | "卡片渲染占位" | **不建**组件，不改 `MessageItem.tsx` | `<Sources />` 与 `CitationBadge` 视觉 / 交互联动强耦合，应在 PR3 一起设计；PR2 聚焦数据管道 + SSE 路由；空壳组件反而增加 review 噪音 |
| `ConversationMessageVO.sources` / `selectSession` 映射 / `thinkingContent/Duration` 补齐 | v1 spec PR2 行含前端骨架模糊地涵盖 | **全部归 PR4**（与 `t_message.sources_json` 落库同批） | PR4 前后端都没有 `sources_json`，PR2 补 `selectSession` 映射无数据可回填 |

---

## § 1 架构与数据流

### 总体数据流

```
RAGChatServiceImpl.chat(...)
  ├─ 闸门 1（"不进 builder" 的条件，不等同于"早返回"）
  │    ├─ rag.sources.enabled = false  ← 回答路径继续；仅跳过 sources
  │    ├─ 意图歧义 clarification        ← 真早返回（不继续回答）
  │    └─ System-Only                   ← 真早返回（走 streamSystemResponse）
  │
  ├─ retrievalEngine.retrieve(...) → ctx
  │    └─ ctx.isEmpty() → 真早返回"未检索到"（回答路径保留现有语义）
  │
  ├─ 聚合 distinctChunks（PR1 已有代码）
  │
  ├─ 闸门 2（sources 总闸门 — 回答路径继续）
  │    └─ distinctChunks.isEmpty() → 跳过 builder 调用，LLM 流照常启动
  │         覆盖场景：MCP-Only / Mixed 但 KB 零命中
  │         （纯空检索场景已被上面 ctx.isEmpty() 拦住，但 sources 口径仍视作闸门 2）
  │
  ├─ cards = sourceCardBuilder.build(distinctChunks, cfg)
  │
  ├─ 闸门 3（最后保险 — 回答路径继续）
  │    └─ cards.isEmpty() → 不 trySet / 不 emit，LLM 流照常启动
  │         覆盖场景：findMetaByIds 过滤后无卡片
  │
  ├─ handlerParams.cardsHolder.trySet(cards)      ← set-once CAS
  ├─ callback.emitSources(SourcesPayload)         ← SSE 同步发射
  │
  └─ llmService.streamChat(...)（触发 onContent/onComplete 异步回调）

StreamChatEventHandler（构造时已拿到 cardsHolder 引用）
  ├─ initialize()   → META
  ├─ onContent()    → MESSAGE+
  ├─ emitSources()  → SOURCES（由 orchestrator 触发，非生命周期回调）
  └─ onComplete()   → FINISH → (async) SUGGESTIONS → DONE
       ※ PR2 里 holder 仅被持有、不被消费；PR4 才在 onComplete 读取做落库

前端
  ├─ useStreamResponse.case "sources" → handlers.onSources?.(payload)
  └─ chatStore.onSources
       ├─ guard: state.streamingMessageId === assistantId
       └─ state.messages.map(m => m.id === state.streamingMessageId
                                 ? { ...m, sources: payload.cards }
                                 : m)
       ※ 更新目标按 streamingMessageId 定位，不依赖 payload.messageId
```

### 两层语义分离（关键）

| 判定层 | 锚点 | 触发什么 |
|---|---|---|
| **回答路径语义** | `ctx.isEmpty()`（含义：`!hasMcp && !hasKb`） | 保留现有早返回"未检索到"分支，行为不变 |
| **sources 决策语义** | `distinctChunks.isEmpty()` | 决定是否调 `SourceCardBuilder`、是否 emit SOURCES |

这两套语义在 PR2 里是独立的：MCP-Only 场景下 `ctx.isEmpty()=false`（回答路径正常走 MCP），但 `distinctChunks.isEmpty()=true`（sources 决策跳过 build）。

### SSE 事件顺序

```
META → SOURCES → MESSAGE+ → FINISH → (SUGGESTIONS) → DONE
```

- `META` 由 `callback.initialize()` 在 orchestrator 入口发出
- `SOURCES` 由 orchestrator 在 `trySet` 成功后同步触发 `callback.emitSources(...)`，发生在 LLM stream 启动前
- `MESSAGE+ / FINISH / SUGGESTIONS / DONE` 由 LLM stream 回调触发，顺序由 handler 保证

`SUGGESTIONS` 在 MCP 场景会跳过（现有行为），因此只在**纯 KB 命中 happy path** 的集成测试里断言完整顺序，不将此顺序泛化到所有场景。

---

## § 2 后端组件清单

### 新增类

| 路径 | 角色与契约 |
|---|---|
| `bootstrap/rag/service/handler/SourceCardsHolder.java` | set-once 容器，内部 `AtomicReference<List<SourceCard>>`。方法：`boolean trySet(List<SourceCard>)` — CAS 防重入，已 set 返回 false；`Optional<List<SourceCard>> get()`。**不放 framework** — 非泛型、仅服务本功能 |
| `bootstrap/rag/dto/SourceCard.java` | `@Data @NoArgsConstructor @AllArgsConstructor @Builder`；字段：`int index / String docId / String docName / String kbId / float topScore / List<SourceChunk> chunks`（**无 kbName**） |
| `bootstrap/rag/dto/SourceChunk.java` | 同上；字段：`String chunkId / int chunkIndex / String preview / float score` |
| `bootstrap/rag/dto/SourcesPayload.java` | 同上；字段：`String conversationId / String messageId（可空，流式阶段为 null）/ List<SourceCard> cards` |
| `bootstrap/rag/core/source/SourceCardBuilder.java` | `@Service`。签名：`List<SourceCard> build(List<RetrievedChunk> chunks, int maxCards, int previewMaxChars)`。职责：纯文档级聚合 — 过滤 `docId==null` 的 chunk（WARN 日志）、按 `docId` groupBy、调 `KnowledgeDocumentService.findMetaByIds` 补 docName/kbId、findMetaByIds 查不到的 docId 整个文档过滤、cards 按 `topScore desc` 排、`index` 从 1 起分配、chunks 按 `chunkIndex asc` 排（null 放末尾）、`preview` 按 `codePointCount` 截断到 `previewMaxChars` 保中文完整、最后按 `maxCards` clip。**不负责**：feature flag / sources 推不推判定 / SSE 发送 / handler 生命周期 / 落库 / kbName 查询 |
| `bootstrap/rag/config/RagSourcesProperties.java` | `@ConfigurationProperties(prefix="rag.sources")`。字段：`boolean enabled=false / int previewMaxChars=200 / int maxCards=8`。配置绑定路径需确保被 `@EnableConfigurationProperties` 或 Spring Boot 自动扫描覆盖到 |

### 修改类

| 路径 | 改动 |
|---|---|
| `bootstrap/rag/enums/SSEEventType.java` | 新增 `SOURCES("sources")` 枚举常量 |
| `bootstrap/rag/service/handler/StreamChatHandlerParams.java` | 新增 `final SourceCardsHolder cardsHolder` 字段。**必须**标 `@Builder.Default cardsHolder = new SourceCardsHolder()` 让默认值语义不可绕过 — 任何经 `StreamChatHandlerParams.builder()...build()` 的调用即使不调用 `.cardsHolder(...)` 也保证拿到空壳实例，禁止 `null` 通过。orchestrator 后续 `trySet` |
| `bootstrap/rag/service/handler/StreamCallbackFactory.java` | **无需改动** — 因 `@Builder.Default` 自动兜底，现有 `.builder()...build()` 调用（`createChatEventHandler` L62-75）直接受益。本行仅为显式声明，避免实施时误改 |
| `bootstrap/rag/service/handler/StreamChatEventHandler.java` | 构造器解包 `cardsHolder`；**PR2 仅持有 holder，不在 onComplete 中读取消费**（避免无业务效果的读取动作；PR4 真正使用）；新增 `emitSources(SourcesPayload payload)` 方法 — 内部调 `sender.sendEvent(SSEEventType.SOURCES.value(), payload)` |
| `bootstrap/rag/service/impl/RAGChatServiceImpl.java` | 注入 `SourceCardBuilder` + `RagSourcesProperties`；在聚合 `distinctChunks` 后（v1 spec 提到的 L202 附近）按三层闸门逻辑调用 builder、trySet、`callback.emitSources`。**"不调 builder / 不 emit" 的分支分两类**，实施时严格区分：<br/>(a) **真早返回**（不继续回答流程）：意图歧义 / System-Only / `ctx.isEmpty()`<br/>(b) **继续回答但跳过 sources**（回答路径不变，仅 sources 路径短路）：`rag.sources.enabled=false` / `distinctChunks.isEmpty()`（含 MCP-Only、Mixed 但 KB 零命中）/ `cards.isEmpty()` |
| `bootstrap/src/main/resources/application.yaml` | 新增 `rag.sources: {enabled: false, preview-max-chars: 200, max-cards: 8}` |

### 架构原则（边界承诺）

- **SourceCardBuilder 纯聚合**，不理解业务语义（flag / 推不推 / SSE / 落库）
- **orchestrator 决策**，handler **机械发射**：`callback.emitSources(payload)` 是唯一入口，orchestrator 不直接触碰 `sender`
- **set-once 语义**：`SourceCardsHolder.trySet` CAS 防重入；同步段内设值、异步段内只读快照
- **新增代码零 ThreadLocal 依赖**：handler 现有 `UserContext.getUserId()` / `RagTraceContext.getEvalCollector()` 的 ThreadLocal 读取是遗留债（followup backlog），PR2 新增代码不扩大此面

---

## § 3 前端改动清单

**仅 3 个文件**，`MessageItem.tsx` 零改动。

| 路径 | 改动 |
|---|---|
| `frontend/src/types/index.ts` | 新增 `SourceCard` / `SourceChunk` / `SourcesPayload` TS 接口（**无 kbName**）；`Message` 接口加 `sources?: SourceCard[]` |
| `frontend/src/hooks/useStreamResponse.ts` | `StreamHandlers` 接口加 `onSources?: (payload: SourcesPayload) => void`；switch 加 `case "sources": handlers.onSources?.(payload); break;` |
| `frontend/src/stores/chatStore.ts` | 构造 stream handlers 处新增 `onSources`：guard `state.streamingMessageId === assistantId` 不相等时丢弃；相等时 `messages.map` 把匹配 `state.streamingMessageId` 的 message 更新 `sources` 字段；**更新目标按 `state.streamingMessageId` 定位，不依赖 `payload.messageId`** |

### 前端落地约束

- `onFinish` 里现有的 `{ ...message, id: newId }` spread 模式保证 `sources` 字段在 id 替换后被保留 — 无需额外代码
- **不修复** `onSuggestions` 的 `streamingMessageId` guard 缺失（PR2 外延，入 followup backlog）
- **不改** `ConversationMessageVO` / `selectSession` 映射 / `sessionService.ts`（PR4 范围）

---

## § 4 SSE 契约

### `sources` 事件载荷

```json
{
  "conversationId": "c_abc",
  "messageId": null,
  "cards": [
    {
      "index": 1,
      "docId": "doc_abc",
      "docName": "员工手册 v3.2.pdf",
      "kbId": "kb_xyz",
      "topScore": 0.892,
      "chunks": [
        { "chunkId": "c_001", "chunkIndex": 12, "preview": "…", "score": 0.892 },
        { "chunkId": "c_002", "chunkIndex": 13, "preview": "…", "score": 0.815 }
      ]
    },
    { "index": 2, "...": "..." }
  ]
}
```

### 语义约定

- `index` 从 1 开始，等于 LLM 将来看到的引用编号 / 卡片显示编号（PR3 对齐）
- `cards` 按 `topScore` 降序
- `chunks` 在卡片内按 `chunkIndex` 升序
- `preview` 按 `previewMaxChars` 截断（默认 200），`codePointCount` 保中文完整
- `cards: []` 场景 **不推送** `sources` 事件（前端 `message.sources` 保持 undefined）
- `messageId` **可空**（流式阶段 DB id 尚未分配；前端不依赖此字段定位消息）

### 缺席矩阵

| 场景 | 回答路径行为 | sources 判定锚点 | 推 SOURCES？ | `cardsHolder` |
|---|---|---|---|---|
| `rag.sources.enabled=false` | 正常 | 闸门 1（配置读取） | 否 | 未 set |
| 意图歧义 clarification | 早返回 `guidanceDecision.isPrompt()` | 闸门 1（早返回） | 否 | 未 set |
| System-Only | 早返回 `streamSystemResponse()` | 闸门 1（早返回） | 否 | 未 set |
| 空检索（KB+MCP 都空） | `ctx.isEmpty()` 早返回"未检索到" | 闸门 2（`distinctChunks.isEmpty()`） | 否 | 未 set |
| MCP-Only（`ctx.hasMcp=true, ctx.hasKb=false`） | 正常走 MCP 主链路 | 闸门 2（`distinctChunks.isEmpty()`） | 否 | 未 set |
| Mixed 但 KB 零命中 | 正常 | 闸门 2（`distinctChunks.isEmpty()`） | 否 | 未 set |
| Builder 过滤后 `cards.isEmpty()` | 正常 | 闸门 3（`cards.isEmpty()`） | 否 | 未 set |
| 正常 KB 命中（任意 mixed） | 正常 | — | **是** | **set** |

---

## § 5 测试点

### 后端单测

| 测试类 | 用例 |
|---|---|
| `SourceCardsHolderTest` | `trySet` 仅一次成功 / 二次 `trySet` 返回 false 且不覆盖 / 未 set 时 `get()` 返回 `Optional.empty()` / 并发两次 `trySet` 仅一个成功 |
| `SourceCardBuilderTest` | 10 chunk/3 doc → cards=3，topScore 降序，chunks 升序，preview 截断 200 且中文完整；空入参 → 空；部分 docId 查询不存在 → 过滤不抛；`docId==null` chunk → 丢弃 + WARN；`maxCards=2` 时正确 clip；`findMetaByIds` 返回空 → 空 cards |
| `RAGChatServiceImplSourcesTest`（集成级 mock） | happy path：builder 调用一次 / `trySet` 调用一次 / `emitSources` 调用一次；flag off：不调 builder / 不 emit；`distinctChunks.isEmpty()`：不调 builder / 不 emit；`cards.isEmpty()`：调 builder 一次但不 emit；意图歧义分支：不调 builder；System-Only 分支：不调 builder；**MCP-Only（`ctx.hasMcp=true` 但 `distinctChunks.isEmpty()`）：不调 builder / 不 emit**（锁住判定口径） |

### 后端集成

- **纯 KB 命中 happy path** 端到端 `/rag/v3/chat`（OpenSearch 真实检索）→ 断言 SSE 帧序：`meta → sources → message+ → finish → suggestions → done`（MCP 场景 suggestions 本就跳过，不泛化此顺序到所有 happy path）
- flag off → SSE 无 `sources` 帧

### 前端单测

- **`useStreamResponse` 的 SSE 事件路由**：
  - 喂一帧 `event: sources\ndata: {...}` 到 reader，断言 `handlers.onSources` 被调用一次、参数为解析后的 `SourcesPayload`
  - 保护点：若 `useStreamResponse` 忘加 `case "sources"` 分支，本断言失败；否则 store 层测试全过也发现不了回归
- `chatStore.onSources`：
  - `streamingMessageId !== assistantId` 时丢弃（不调 set）
  - 相等时正确写入 `message.sources`
- `chatStore.onFinish` id 替换后，`sources` 字段被 `{ ...message, id: newId }` 保留（顺手锁住判断）

### 手动冒烟

- flag on → DevTools Network 能看到 `event: sources` 帧；store 里 assistant message 拿到 `sources` 字段；UI 零变化
- flag off → 完全无感

---

## § 6 Not in PR2（明确排除）

- Prompt 改造 / `remarkCitations` / `CitationBadge` / 埋点 → **PR3**
- `<Sources />` UI 组件 / `MessageItem.tsx` 改动 → **PR3**
- `t_message.sources_json` 持久化 + 升级 SQL + schema 双写 → **PR4**
- `ConversationMessageVO.sources` + `selectSession` 映射补齐 + `thinkingContent/Duration` 补齐 → **PR4**
- feature flag 默认打开 → **PR5**
- `SourceCard.kbName` 字段 → 未来 additive（扩展 `KnowledgeDocumentService.findMetaByIds` 返回，而非新增 service 调用）
- `onSuggestions` 的 `streamingMessageId` guard 修复（latent bug）→ followup backlog
- Handler 中 `UserContext.getUserId()` / `RagTraceContext.getEvalCollector()` 的 ThreadLocal 读取残留 → followup backlog

---

## § 7 Gotcha 摘要 & 关键设计决策回顾

1. **cards 通过 `SourceCardsHolder` 传递**：同步段内 `trySet`、异步 `onComplete()` 只读快照，等价于 v1 spec "不可变构造参数"的安全性但不要求构造时 cards 可用
2. **orchestrator 决策 + handler 机械发射**：`callback.emitSources(payload)` 是唯一 SSE emit 入口；orchestrator 不直接摸 `sender`
3. **sources 判定锚点统一为 `distinctChunks.isEmpty()`**：不用 `ctx.isEmpty()`（误判 MCP-Only）、不用 `hasMcp`（误伤 mixed）
4. **DTO `@NoArgsConstructor @AllArgsConstructor`** 必须显式标注（Lombok `@Data @Builder` 组合 Jackson 反序列化坑，项目历史踩过）
5. **`SourcesPayload.messageId` 可空**：流式阶段 DB id 不存在，`null` 比 `""` 准确；前端 `onSources` 更新目标用 `state.streamingMessageId` 定位，不依赖此字段
6. **前端 `{ ...message, id: newId }` spread** 自然保留 `sources` 字段，无需额外代码
7. **PR2 新增代码零 ThreadLocal 依赖**：handler 现有 `UserContext` / `RagTraceContext` 读取不扩大
8. **SourceCardsHolder 放 rag 域非 framework**：非泛型、仅服务本功能，避免 framework 抽象过度

---

## § 8 PR 拆分回顾（定位本 PR）

| PR | 范围 | 可见影响 |
|----|------|----------|
| PR1 ✅ | `RetrievedChunk.docId/chunkIndex` + OpenSearch 回填 + `findMetaByIds` + `DocumentMetaSnapshot` | 零 |
| **PR2（本文档）** | `SourceCardBuilder` + DTOs + `SSEEventType.SOURCES` + `RAGChatServiceImpl` 编排 + 前端 SSE 路由（不含 UI 组件） | 默认无感（flag off） |
| PR3 | Prompt 改造 + `remarkCitations` + `CitationBadge` + `<Sources />` UI + 埋点 | 默认无感（flag off） |
| PR4 | `t_message.sources_json` 持久化 + `ConversationMessageVO.sources` + `selectSession` 映射补齐（顺带 `thinkingContent/Duration` 修复） | 默认无感（flag off） |
| PR5 | `rag.sources.enabled: true` | 功能上线 |

---

## 附：参考路径

- 本 PR 基线分支：`feature/answer-sources-pr2`（tip 初始 `96b2d06`）
- 上游 v1 spec：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`
- PR1 交接：`docs/superpowers/plans/2026-04-20-answer-sources-pr1-metadata.md`
- 代码现状锚点：
  - `RAGChatServiceImpl.java` 主链路聚合点（`distinctChunks` 构建附近，L200 区域）
  - `StreamChatHandlerParams.java`（构造器参数面）
  - `StreamChatEventHandler.java`（生命周期方法 `initialize / onContent / onComplete`）
  - `SSEEventType.java`（枚举扩展）
  - `useStreamResponse.ts` / `chatStore.ts`（SSE 路由 + store）
