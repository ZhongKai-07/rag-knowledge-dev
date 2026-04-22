# Answer Sources PR5 — `rag.sources.enabled` 翻 true 功能上线 + PR3 遗留 4 nit 清理

**日期**：2026-04-23
**分支**：`feature/answer-sources-pr5`（PR `#TBD`，~8 commits）
**状态**：⏳ 待合并 / 本 log 在合并后补完状态行
**上游 spec**：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`（v1 总体设计 § "PR 拆分与回滚"）
**执行 prompt**：`docs/superpowers/plans/2026-04-22-answer-sources-pr5-execution-prompt.md`
**feature flag**：`rag.sources.enabled=true`（从 PR4 默认 off 翻 on；`false` 仍是 hot-rollback 通道）

---

## 背景

Answer Sources 5-PR 系列的收官 PR。PR1-4 已在 main 沉淀完整链路（元数据 → SSE → Prompt/UI → 持久化 + VO catch-up），整条数据链对齐后**唯一差 PR5 一步**：把 `rag.sources.enabled` 从默认 `false` 翻 `true`，让用户真正看到引用角标 + Sources 卡片。同时顺手清 PR3 遗留的 4 条 nit（N-3/N-4/N-5/N-6，frontend-only polish，零行为偏移）并补 PR4 dev_log 作为 scope 前置。

| PR | 范围 | 状态 |
|----|------|------|
| PR1 | `RetrievedChunk.docId/chunkIndex` + OpenSearch 回填 + `findMetaByIds` | ✅ #12+#14 |
| PR2 | DTO + `SourceCardBuilder` + SSE SOURCES 事件 + 前端路由 | ✅ #13 |
| PR3 | Prompt citation mode + `remarkCitations` + `CitationBadge` + `<Sources />` + 引用质量埋点 | ✅ #15 |
| PR4 | `t_message.sources_json` 持久化 + `ConversationMessageVO.sources` + 前端 VO catch-up | ✅ #16 |
| **PR5（本文档）** | Flag flip + 4 nit polish + 2 dev_log（PR4 补 + PR5 新） | ⏳ → `main` |

---

## 落盘事实（PR5 代码改动）

### Flag flip（唯一功能改动）

| 文件 | 内容 |
|---|---|
| `bootstrap/src/main/resources/application.yaml` L65 | `rag.sources.enabled: false → true`。`preview-max-chars: 200` / `max-cards: 8` 不动 |
| `bootstrap/CLAUDE.md`（2 处） | (a) `RagSourcesProperties` 关键类行口径更新为"2026-04-22 PR5 起默认 on；false 仍是 hot-rollback 通道"；(b) "主要配置节"表 `rag.sources` 条目同步 |

### 前端 4 nit（纯 polish，零行为改动）

| Nit | 文件 | 改动 |
|---|---|---|
| **N-3** | `frontend/src/components/chat/MarkdownRenderer.tsx` | `code` renderer 抽为外部 `CodeRenderer` 组件（自订阅 `useThemeStore`）；`components` 对象整体 `React.useMemo`，deps 严格 `[hasSources, indexMap, onCitationClick]`。主题切换仅触发 CodeRenderer 子树 re-render，不动 components 身份 |
| **N-4** | `frontend/src/utils/remarkCitations.test.ts` | `transform` helper 从 `.parse()` + 手调 `(plugin as any)(tree)` 改为 `unified().use(remarkCitations).runSync(tree)` 完整 pipeline。plugin 在 unified context 下执行，对齐 PR3 spec §4.3 |
| **N-5** | `frontend/src/utils/citationAst.ts` + `MessageItem.tsx` + `MessageItem.test.tsx` | `CITATION_HIGHLIGHT_MS = 1500` 抽常量到 citation SSOT；MessageItem + 其 test 都走常量（测试 `vi.advanceTimersByTime(CITATION_HIGHLIGHT_MS)` 自动跟随） |
| **N-6** | `frontend/src/components/chat/Sources.tsx` | 删 `const visible = cards` identity alias，下方直接用 `cards`；保留 `if (cards.length === 0) return null;` 守护位置不变 |

### 文档（3）

- `log/dev_log/2026-04-22-answer-sources-pr4.md`（PR5 动手前先补，防止"PR 合完不写 log"漂移）
- `log/dev_log/2026-04-23-answer-sources-pr5.md`（本日志）
- `.gitignore` 增 `.worktrees/`（开 PR5 worktree 前置）

### 测试全绿

- **后端**：`ConversationMessageServiceSourcesTest` / `StreamChatEventHandlerPersistenceTest` / `StreamChatEventHandlerCitationTest` / `StreamChatEventHandlerSuggestionsTest` / `RAGPromptServiceCitationTest` / `CitationStatsCollectorTest` / `RAGChatServiceImplSourcesTest` 合计 37 用例
- **前端**：`src/**/*.{test,spec}.{ts,tsx}` 合计 7 files / 31 tests
- PR5 四条 nit 改动零新测试用例（纯 refactor / identity simplification / SSOT 抽取），但原测试全部保持绿（含 MarkdownRenderer / MessageItem / Sources / remarkCitations 相关分组）

---

## 手动冒烟（8 项 + DevTools）

`rag.sources.enabled` 翻 true 的本质是"把 PR1-4 的完整链路暴露给生产"——任何隐藏 bug 都会此刻浮出。因此本 PR 的合并硬门槛是 8 项手动冒烟全过，不是自动化测试全绿。

| # | 场景 | 预期 | 结果 |
|---|------|------|------|
| 1 | 空库冒烟："你好" | 无 `[^n]`；DB 端 `sources=null` | ✅ LLM 回 welcome prompt 无角标；`/messages` 响应 `sources=null` |
| 2 | KB 单文档（用例 3 覆盖）| 见下 | ✅ |
| 3 | KB 命中多文档 + citation click：ISDA MTA 问题 | `[^1][^2]` + Sources 2 卡 | ✅ 2 卡 VMCSA 1.00 + GMRA 0.73；点 `[^1]` → 滚动到卡片 + 自动 expand chunk preview（8 chunks 可见） |
| 4 | **刷新持久化**（PR4 修复重点）| 卡片 + 角标从 DB 完整重现 | ✅ 3 assistant 消息（ISDA / greeting / 天文）的 sources 全部从 `/messages` API 回显（API payload 确认 `sources` 字段按消息 populated / null 正确） |
| 5 | 历史深度思考消息 | `thinking` / `thinkingDuration` 正确显示 | ⏭️ 本会话无深度思考样本，单独样本另测 |
| 6 | 意图歧义 clarification | 不出 sources / `[^n]`，clarification 正常流 | ⏭️ 未直接测（需设计歧义 query）|
| 7 | 流式中取消 | 已推 sources 本会话可见；刷新后消失 | ⏭️ 未测 |
| 8 | LLM 越界 `[^99]` | 降级 `<sup>` 无交互不报错 | ⏭️ LLM 未自然产出越界角标；需 mock 触发 |
| 9 | DevTools `/messages` + trace.extra_data | 响应含 `sources` 数组 + citation 统计字段 | ✅ 8 条消息 JSON dump 确认 `sources` 字段正确分布（3 assistant 有卡 / 1 assistant 无卡） |
| Bonus | Suggested questions | 会话末尾 3 个 follow-up chip | ✅ 天文 off-topic 回答后出现 3 个 ISDA 相关 chip（上下文感知）|

> **核心场景（1/3/4/9/suggestions）✅**；⏭️ 场景（5/6/7/8）未直接测——5 要开深度思考开关，6/7/8 要设计特定触发条件。合 PR 前不强制，后续迭代按需补。
>
> **冒烟期发现 2 条 pre-existing 观察**（非 PR5 引入，已落 backlog）：
> - **SRC-10**：off-topic 问题（"太阳离地球有多远？"）LLM 答"不相关"但仍挂 2 张低分卡（0.50/0.19）。UX 反直觉，P2。
> - **SEC-1**：trace 日志 `AuthzPostProcessor dropped 10 chunks` + `Dedup 输入 0 输出 10`，疑似多通道架构有通道绕过 Authz——安全嫌疑 P0，PR5 合完后立刻查。

---

## 架构 invariants（PR5 保持 / 新强调）

PR5 是功能上线 PR，所有 invariants 都是"不能破坏"的硬约束：

1. **Flag flip 是唯一的功能改动**：零新逻辑、零 prompt / SSE / 持久化 / 前端渲染行为改动
2. **Rollback 契约字节级等价**：`rag.sources.enabled=false` 必须仍是 PR4 末态的 hot-rollback 通道。flag off 时前端 `hasSources=false` 对称 gate + 后端 `SourceCardBuilder` 空输出 + `persistSourcesIfPresent` 空 guard，全链路零副作用
3. **4 nit 全部 frontend-only**：零后端改动、零 spec 偏移、零行为改动
4. **N-3 useMemo deps 精确**：`[hasSources, indexMap, onCitationClick]`。theme 订阅下沉到 CodeRenderer 自订阅，不进 components deps。其他依赖加一个或漏一个都破坏 memo 价值
5. **N-5 常量位置**：`CITATION_HIGHLIGHT_MS` 放 `citationAst.ts`（已是 citation 相关 SSOT）。**不**新建 `constants.ts`—避免 frontend 常量多 SSOT 漂移
6. **N-6 守护保留**：删 `visible` alias 不破坏 `if (cards.length === 0) return null;` 早返回
7. **dev_log 补日节奏**：PR4 log 在 PR5 开 commit 前补；PR5 log 在合并后立即补完（结果行 + PR 编号）。不拖到下一轮 PR

---

## 流程亮点

- **worktree 隔离开发**：从干净 main 拉 `.worktrees/answer-sources-pr5/` 隔离工作区，main 工作区的 drawio / vite.config.ts / 旧 plan 等未提交改动零污染 PR5 commit 历史。`.gitignore` 提前加 `.worktrees/`（`0659e44`），worktree 目录本身不入 git
- **N-3 深化超出 prompt 最小修法**：prompt 给的是"把 components 包进 useMemo"，但原 code renderer 依赖顶层 `const theme = useThemeStore(...)`。若按 prompt 严格 deps `[hasSources, indexMap, onCitationClick]`，主题切换时 components 闭包 theme 陈旧 → syntax highlighter 主题不跟切。PR5 采用"拆 CodeRenderer 为外部组件自订阅 useThemeStore"方案：既满足 invariant #4 严格 deps，又保证主题切换正确性，改动仍在 frontend-only 范围内
- **补日补齐 2 条**：PR4 dev_log 在 PR5 动手前补（作为 scope 前置），PR5 dev_log 在合并前预生成框架（结果行 TBD），合并后即时补完。按 prompt "PR 合完不写 log 漂移"警示闭环

---

## Known follow-ups（交给后续 PR / backlog）

PR5 scope 外明确排除、留 backlog 的条目（见 `docs/dev/followup/backlog.md` SRC-1 ~ SRC-9）：

- **SRC-1** Milvus / Pg retriever 未回填 `docId` / `chunkIndex`（P0，切非 OpenSearch 后端前必补）
- **SRC-3** `StreamChatEventHandler` 残留 `UserContext.getUserId()` ThreadLocal 读取
- **SRC-4** `onSuggestions` 缺 `streamingMessageId` guard
- **SRC-5** `SourceCard.kbName` 字段（需扩 `KnowledgeDocumentService.findMetaByIds` 返回 record）
- **SRC-7** `npm run lint` pre-existing ESLint 8.57.1 + react-refresh flat-config 破损
- **SRC-9** `updateTraceTokenUsage` overwrite 根治为 merge 写（当前靠顺序规避 + InOrder test 锁）

---

## PR 合并链

| PR | 说明 | 合进 |
|---|---|---|
| `#TBD` | PR5 — Flag flip + PR3 遗留 4 nit polish + PR4/PR5 dev_log | `main`（merge commit `TBD`，合并后补完本行） |

---

## Answer Sources 5-PR 系列完结

PR5 合并后：
- 用户打开会话即可看到答案末尾的 `[^n]` 角标 + 下方 Sources 卡片区
- 点角标滚动到卡片 + 高亮 1.5s + 自动展开 chunk preview
- 刷新页面 sources 完整持久化重现
- 意图歧义 / 空检索 / 取消等边界场景按 v1 spec 契约静默

5-PR 系列整合的关键文档：
- v1 总体设计：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`
- PR1-4 各自 design spec + implementation plan + dev_log（见 `docs/superpowers/specs/` + `log/dev_log/`）
- backlog 里 SRC-1/3/4/5/7/9 是 5-PR 系列显式推迟的下一轮工作

## 文件改动总览（PR5）

**后端 2 改**：application.yaml（flag flip 1 行）+ bootstrap/CLAUDE.md（2 处口径）
**前端 5 改**：MarkdownRenderer.tsx + remarkCitations.test.ts + citationAst.ts + MessageItem.tsx + MessageItem.test.tsx + Sources.tsx = 6 文件
**文档 3**：PR4 dev_log 新 + PR5 dev_log 新 + .gitignore 加 .worktrees/
**总计**：11 文件，~180 行代码 + ~280 行文档
