# Answer Sources PR3 — Prompt 改造 + 前端引用渲染 + 引用质量埋点

**日期**：2026-04-22
**分支**：`feature/answer-sources-pr3`（PR [#15](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/15)，20 commits）
**状态**：merged 进 `origin/main`（commit `c462de7`）
**设计文档**：`docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md`
**实施计划**：`docs/superpowers/plans/2026-04-22-answer-sources-pr3-implementation.md`
**feature flag**：`rag.sources.enabled=false`（PR2-PR4 期间静默，PR5 才翻 true）

---

## 背景

Answer Sources 5-PR 系列的第 3 个 PR。承接 PR1（元数据 plumbing）+ PR2（编排 + SSE + 前端路由骨架）。本 PR flag off 状态下**零用户可见变化**、和 PR2 末态**字节级等价**。

| PR | 范围 | 状态 |
|----|------|------|
| PR1 | `RetrievedChunk.docId/chunkIndex` + OpenSearch 回填 + `findMetaByIds` | ✅ #12+#14 |
| PR2 | DTO + `SourceCardBuilder` + SSE SOURCES 事件 + 前端路由 | ✅ #13 |
| **PR3（本文档）** | Prompt citation mode + `remarkCitations` + `CitationBadge` + `<Sources />` + 引用质量埋点 | ✅ #15 |
| PR4 | `t_message.sources_json` 持久化 + `ConversationMessageVO.sources` | ⏳ 未开始 |
| PR5 | `rag.sources.enabled: true` 上线 | ⏳ 未开始 |

---

## 落盘事实

### 后端（5 文件改 + 4 测试类）

| 文件 | 内容 |
|---|---|
| `rag/core/prompt/PromptContext.java` | 新增 `cards: List<SourceCard>` 字段；`hasMcp()`/`hasKb()` 方法不动（cards 不参与 scene 判定） |
| `rag/core/prompt/RAGPromptService.java` | `buildStructuredMessages` 签名不变，内部局部派生：`citationMode = CollUtil.isNotEmpty(cards) && ctx.hasKb()`；新增私有 `buildCitationEvidence(ctx)`（正文从 `intentChunks.RetrievedChunk.getText()` 全文回收，**不**用 `SourceChunk.preview`）+ `appendCitationRule(base, cards)`（动态白名单，`cards.size()==1` → `[^1]`，≥2 → `[^1] 至 [^N]`） |
| `rag/core/source/CitationStatsCollector.java`（新） | 静态工具类；`scan(answer, cards)` 返回 `(total, valid, invalid, coverage)` record。`valid` 用 `indexSet.contains(n)` 非 `1..N` range；regex `CITATION = \[\^(\d+)]` + `SENTENCE = [^。！？]+[。！？]`；空输入 → `(0,0,0,0.0)` 不除零 |
| `rag/service/handler/StreamChatEventHandler.java` | `onComplete` 中在 `updateTraceTokenUsage()`（overwrite）**之后**、`saveEvaluationRecord()` 之前插入 `mergeCitationStatsIntoTrace()`；使用构造期缓存的 `final traceId` 字段 + `traceRecordService.mergeRunExtraData(traceId, Map)` merge 写（**不**是 overwrite）。异常 catch Warn log 不中断 |
| `rag/service/impl/RAGChatServiceImpl.java` | `streamLLMResponse(...)` 新增 `List<SourceCard> cards` 参数，threaded 进 `PromptContext.builder().cards(cards).build()` |
| `RAGPromptServiceCitationTest`（新，7 用例） | citation mode 全路径：evidence 含 `【参考文档】[^1]《docName》` / 正文用 `RetrievedChunk.text` 非 preview / chunkId miss → fallback preview / size=1 vs ≥2 rule 差异 / 异常组合 kbContext 空 → 回退 / cards=null → PR2 等价 |
| `CitationStatsCollectorTest`（新，6 用例） | 含非连续 index `[1,3,5]` + answer `[^2]` → invalid++（锁 indexSet 契约）、空 answer/空 cards、末尾无句号 coverage 粗切 |
| `StreamChatEventHandlerCitationTest`（新，4 用例） | holder 未 set 不调 merge / 有 set + 有引用 → 4 字段 merge / `InOrder` 锁 overwrite-then-merge 顺序 / answer 非空但无引用走完循环仍全 0（review round-1 补） |
| `RAGChatServiceImplSourcesTest`（扩，+1 用例） | SRC-6 闭环：`streamChat_whenGuidanceClarificationPrompt_thenSkipSourcesEntirely` — guidance prompt 分支 `sourceCardBuilder` / `trySetCards` / `emitSources` / `llmService.streamChat` 都 never called |

### 前端（4 新 + 3 改 + package.json）

| 文件 | 内容 |
|---|---|
| `utils/citationAst.ts`（新） | 共享常量 SSOT：`CITATION = /\[\^(\d+)]/g` + `SKIP_PARENT_TYPES = Set("inlineCode","code","link","image","linkReference")` |
| `utils/remarkCitations.ts`（新，mdast 插件） | 三合一 visit：(1) `footnoteReference → cite` + `hProperties.data-n`；(2) `footnoteDefinition → splice 删除`；(3) `text → CITATION.exec` 切片注入 cite 节点，跳过 SKIP_PARENT_TYPES 父节点。严禁字符串预处理 |
| `components/chat/CitationBadge.tsx`（新） | 蓝色上标徽章；`indexMap.get(n)` miss → 降级为纯 `<sup>[^n]</sup>` 无交互；命中 → `<sup><button title={docName} onClick={onClick(n)}>n</button></sup>` |
| `components/chat/Sources.tsx`（新） | `React.forwardRef`；忠实渲染后端 cards 原序（Option X，后端 `SourceCardBuilder` 已 clip 到 8，前端不做 partition / cited-never-clipped 保护）；`highlightedIndex` 变化触发 auto-expand；点击 toggle |
| `components/chat/MarkdownRenderer.tsx` | `hasSources = Array.isArray(sources) && sources.length > 0` **单一闸门对称 gate** 两侧：`remarkPlugins = hasSources ? [remarkGfm, remarkCitations] : [remarkGfm]` + `components.cite` 只在 `hasSources=true` 时映射。守 PR2 rollback 字节级契约 |
| `components/chat/MessageItem.tsx` | 承接 citation click：`highlightedIndex` / `timerRef` / `sourcesRef` 三个 state；`handleCitationClick(n)` → `scrollIntoView` + `setHighlightedIndex(n)` + 1500ms setTimeout 清除；`useEffect` cleanup 清 timer 防 leak |
| `package.json` | 新 runtime dep `unist-util-visit@^5`（显式提升避免 transitive 漂移）+ 新 devDeps `@testing-library/react@^16` / `@testing-library/user-event@^14` / `unified@^11` / `remark-parse@^11`（后两者仅测试 parse 用，生产 react-markdown 自 parse） |
| 新增 5 个前端测试文件 | `remarkCitations.test.ts`(7) + `CitationBadge.test.tsx`(3) + `Sources.test.tsx`(6) + `MarkdownRenderer.test.tsx`(3) + `MessageItem.test.tsx`(5)，共 24 用例 |

### 测试总数（PR3 新增）

- **后端 17 新用例**（7+6+4）+ 1 扩用例（SRC-6）= 18
- **前端 24 新用例** 分布 5 文件
- 合计 42 个新测试全绿；零 regression

---

## 架构 invariants（PR3 保持 / 新增）

1. **orchestrator 决策 / handler 机械发射**：Prompt 改造在 `RAGPromptService` 内部派生；`StreamChatEventHandler.mergeCitationStatsIntoTrace` 是纯函数 → `traceRecordService.mergeRunExtraData` 调用，无业务分支；orchestrator 只决定是否传 `cards` 进 `streamLLMResponse`
2. **零 ThreadLocal 新增**：`onComplete` 用构造期 `final traceId` 字段，**不**在 `mergeCitationStatsIntoTrace` 里读 `RagTraceContext.getTraceId()`；`cardsHolder.get()` 是 PR2 立的 Optional 快照
3. **sources 判定锚点统一**：**后端** `CitationStatsCollector.scan` 用 `indexSet.contains(n)`；**前端** `CitationBadge` 用 `indexMap.get(n)`。**绝不**用 `cards[n-1]` 或 `1..N` range。契约对齐"index 非位置"语义，未来支持非连续 index（如过滤后）不动
4. **`mergeRunExtraData` 顺序硬性**：必须在 `updateTraceTokenUsage`（overwrite 写）**之后**执行。颠倒会让 merge 结果被后续 overwrite 清掉。`StreamChatEventHandlerCitationTest` 用 `Mockito.InOrder` 锁住此顺序
5. **前端 `[^n]` 严禁字符串 preprocess**：`remarkCitations` 走 mdast AST 三段 visit；`SKIP_PARENT_TYPES`（code / inlineCode / link / image / linkReference）硬约束；内部三段 visit 顺序（footnoteReference → footnoteDefinition → text）**不能颠倒**：第 2 步 splice 删除定义子树是第 3 步 text visit 不会误抓定义体内 `[^n]` 的前提
6. **`<Sources />` 契约**：忠实展示后端 `cards` 原序；后端 `SourceCardBuilder` 的 `topScore desc` + clip 到 `max-cards=8` 是**唯一**可见性裁剪层；前端不做 partition / 不做第二层预算
7. **Rollback 契约字节级等价**：flag off 或 `hasSources=false` 时，`MarkdownRenderer` **不挂** `remarkCitations` 也**不映射** `cite`，答案里字面 `[^n]`（教程 / 幻觉 marker）保持为纯文本，与 PR2 末态**字节级等价**。`MarkdownRenderer.test.tsx` 用 case (a) 显式锁此契约
8. **`remarkPlugins` 顺序硬固定** `[remarkGfm, remarkCitations]`：反了的话 remark-gfm 无法把已解析的定义块归并到 footnoteReference

---

## Review round-1（两阶段 review 后的补丁 commit `366a0b1`）

PR3 实施完成后走了一轮 spec-compliance + code-quality 两阶段 review。**无 blocker**；发现 2 条 Important + 4 条 Nit。合 PR 前修了 Important 2 + Nit 2，剩 2 条 Nit 写进 PR body 的 "Known follow-ups" 由 PR4/PR5 顺手收。

| 编号 | 位置 | 修法 |
|---|---|---|
| I-1 | `CitationStatsCollector.scan` | 在 SENTENCE 循环上加注释：粗切对无终止标点的尾段会系统性低估 coverage（读 trace.extra_data.citationCoverage 时需知此限） |
| I-2 | `StreamChatEventHandlerCitationTest` | 补 `onComplete_whenAnswerNonBlankButHasNoCitations_thenMergesAllZerosViaLoopPath`：区别于"空 answer"走 `scan` 首行早返回的路径，本用例实际走完 CITATION/SENTENCE 两循环、结果仍全 0 |
| N-1 | `Sources.test.tsx` | 补动态 re-render 用例：`rerender` 从 `highlightedIndex=1` 切到 2，验证两张卡都保持 expanded（Set add 不移除旧值） |
| N-2 | `MessageItem.test.tsx` | 补 unmount timer cleanup：spy `window.clearTimeout`，验证 `unmount()` 触发 `useEffect` cleanup 调用 clearTimeout；后续 `advanceTimersByTime(2000)` 不抛错 |

Round-1 总共 +66 行零行为改动。

---

## Known follow-ups

### 留到 PR4/PR5 顺手收的 2 条 nit

- **N-3** `MarkdownRenderer.tsx:42` — spec §3.6 规定 `components` 对象走 `React.useMemo`，实际内联字面量。每次 render 新建组件 object，`indexMap` / `onCitationClick` 变化时触发子树重 mount。影响小但偏离 spec
- **N-4** `remarkCitations.test.ts:15` — spec §4.3 规定 `unified().use(remarkParse).use(remarkGfm).use(remarkCitations).runSync()`，实际是 `.parse()` + `(plugin as any)(tree)` 手调。当前 plugin 无 context 依赖，下一版若用到 unified context 要补
- **N-5** `MessageItem.tsx:45` / `MarkdownRenderer.tsx:210` — magic `1500`ms 高亮时长直接写死，抽 `CITATION_HIGHLIGHT_MS` 更清晰
- **N-6** `Sources.tsx:22` — `const visible = cards` 是 Option X 去掉 partition 后遗留的 identity alias，可删或内联

### 新 backlog 条目

- **SRC-9** `updateTraceTokenUsage` 的 overwrite 写法是 latent 坑 — 详见 `docs/dev/followup/backlog.md`。当前 PR3 通过"先 overwrite 再 merge"的顺序规避 + `InOrder` test 锁，未来根治应改 merge 写入

### 闭环（本 PR 解决）

- **SRC-6** ✅ 缺席矩阵"意图歧义 clarification"行没显式单测 — 本 PR §2.8 补完 `streamChat_whenGuidanceClarificationPrompt_thenSkipSourcesEntirely`（commit `6710085`），断言 sourceCardBuilder / trySetCards / emitSources / llmService.streamChat 都 never called，且 clarification prompt 仍走 `callback.onContent + onComplete`

---

## 流程亮点

- **两阶段 review 闭环**：spec reviewer 先核对 25 文件对照 PR3 design spec 的逐条契约；code quality reviewer 再看 bugs / 不变式 / DRY / 测试严格度。Important 在合 PR 前落 round-1 commit，Nit 写进 PR body 由下游 PR 顺手收
- **Stacked PR 陷阱已避**：PR3 base 直接对 main（PR1+PR2 已在 main），不再踩 PR2 那次的"子 PR 只合进中间分支"坑
- **零 blocker + 无 regression**：基线失败项（Milvus / pgvector / seeded KB data）未新增；前端 29/29 + 后端 `StreamChatEventHandlerCitationTest` 4/4 全绿

---

## PR 合并链

| PR | 说明 | 合进 |
|---|---|---|
| [#15](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/15) | PR3 — Prompt 改造 + remarkCitations + `<Sources />` + 引用质量埋点 | `main`（commit `c462de7`） |

---

## 文件改动总览

**后端 5 改 + 4 测试** = 9 文件
**前端 4 新 + 3 改 + package.json + lock** = 9 文件
**文档 3**：design spec + implementation plan + 本日志
**总计**：25 文件，+4453/-16 行（大部分在 spec + plan 的 ~3000 行设计文档）
