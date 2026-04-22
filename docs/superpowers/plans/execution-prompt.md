  ---
  继续执行 answer-sources 功能 PR4（`t_message.sources_json` 持久化 + `ConversationMessageVO.sources` 映射 + 顺带补前端缺失的
  `thinkingContent/Duration` 映射）。

  ## 上下文（5 步加载）

  1. **核对 PR3 是否已合入 main**（PR4 必须 PR3 落地后才启动，避免 stacked PR 坑）：
     ```bash
     git fetch origin && git log origin/main --oneline | head -20
     ```
     预期看到 17 个 `[PR3]` commit（feat/test/chore/docs sources，含 `f7b9f27 docs(backlog): add SRC-9`）。若只看到 `cf4677e docs(sources):
   PR3 implementation plan` 两条（设计 + 计划）说明 PR3 还没 merge —— 停下来先确认合并状态，不开 PR4。

  2. 读以下 5 个文档按序理解 PR4 边界：
     - 上游 v1 spec: `docs/superpowers/specs/2026-04-17-answer-sources-design.md`（重点看"持久化 Schema"节）
     - PR2 设计 + 实施记录: `docs/superpowers/specs/2026-04-21-answer-sources-pr2-design.md` +
  `log/dev_log/2026-04-21-answer-sources-pr1-pr2.md`
     - **PR3 设计**: `docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md`（§ 6 "Not in PR3" 明确列出 PR4 范围）
     - **PR3 实施计划**: `docs/superpowers/plans/2026-04-22-answer-sources-pr3-implementation.md`
     - **PR3 交接 dev_log**: `log/dev_log/2026-04-22-answer-sources-pr3.md` —— 若不存在，**动 PR4 之前先补一个**，模板参
  `log/dev_log/2026-04-21-answer-sources-pr1-pr2.md`；作为 PR4 的"我此前到哪了"基线。

  3. main 上现在已有的基线（PR3 合并后）：
     - 后端：`PromptContext.cards` / `RAGPromptService.buildCitationEvidence + appendCitationRule` /
  `RAGChatServiceImpl.streamLLMResponse(..., cards)` / `CitationStatsCollector` 纯工具 / `StreamChatEventHandler.onComplete` 含
  `mergeCitationStatsIntoTrace`（顺序：`updateTraceTokenUsage` → merge → `saveEvaluationRecord`）
     - 前端：`citationAst` + `remarkCitations` 插件 + `CitationBadge` + `Sources` + `MarkdownRenderer hasSources dual-gate` + `MessageItem`
   集成
     - feature flag `rag.sources.enabled=false`（默认静默，PR5 才翻 true）
     - 后端 41 测试 + 前端 27 测试全绿

  4. 从**最新 main** 拉新分支（直合 main，非 stacked）：
     ```bash
     git checkout main && git pull origin main
     git checkout -b feature/answer-sources-pr4
     ```

  5. 读 `docs/dev/followup/backlog.md` 的 SRC-1~9。PR4 scope 外：SRC-3（handler ThreadLocal 残留）/ SRC-4（onSuggestions guard）/
  SRC-9（updateTraceTokenUsage overwrite 根治）—— 除非顺手能做。

  ## PR4 范围

  ### 后端

  - **`t_message` 加列 `sources_json TEXT`** + COMMENT `'答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用'`
  - **迁移脚本名：⚠️ 不是 spec 里写死的 `upgrade_v1.4_to_v1.5.sql`**（已被"软删后复用 KB collection_name"占用，CLAUDE.md
  记录在册）。当前最新是 `upgrade_v1.7_to_v1.8.sql`。PR4 应新增 **`upgrade_v1.8_to_v1.9.sql`**，并同步更新 CLAUDE.md "升级脚本"清单加一行。
  - **schema 双写**（CLAUDE.md 硬约束）：
    - `resources/database/schema_pg.sql` 的 `CREATE TABLE t_message` 块加列
    - `resources/database/full_schema_pg.sql` 的 `CREATE TABLE public.t_message` 块加列 + **独立 COMMENT 块**（不可内联在 CREATE TABLE 内）
  - **DAO**：`ConversationMessageDO` 加 `String sourcesJson` 字段（`@TableField("sources_json")` 对齐列名即可；不走 typeHandler，service
  层手动 Jackson 序列化）。
  - **落库逻辑**：`StreamChatEventHandler.onComplete` 里，在 `memoryService.append(...)` 拿到 `messageId` 之后加
  `persistSourcesIfPresent(messageId)` 私有方法：
    - 读 `cardsHolder.get()`；空则 return。
    - **用 Jackson** `ObjectMapper.writeValueAsString(cards)`。**禁止 Gson**（历史坑：Gson 把 int 写成 `"N.0"`）。
    - 调 `ConversationMessageService.updateSourcesJson(messageId, json)`（新增 service 方法）。
    - try/catch Exception + `log.warn(...)`，异常不影响主流程。
    - 位置推荐：紧跟 `memoryService.append`、在 `mergeCitationStatsIntoTrace` 之前或之后都行（互不依赖）。
  - **读路径**：
    - `ConversationMessageVO` 加 `List<SourceCard> sources` 字段。
    - `ConversationService.selectSession(...)` 里把 `sources_json` 用 Jackson 反序列化成 `List<SourceCard>` 填 VO；反序列化失败 →
  `sources=null`，不抛（log.warn）。
    - **顺带修 `thinkingContent` / `thinkingDuration`** 的前端映射——这两字段后端 VO 一直有（v1.3→v1.4 migration 加的），但前端
  `sessionService.ts` TS 接口从未声明、`chatStore.selectSession` 映射也没带。借 PR4 同步补齐。

  ### 前端

  - `services/sessionService.ts` 的 `ConversationMessageVO` TS 接口补三个字段：`thinkingContent?: string`、`thinkingDuration?:
  number`、`sources?: SourceCard[]`。
  - `stores/chatStore.ts` 的 `selectSession` 映射（不是 SSE 路径）把 `item.thinkingContent → m.thinking`、`item.thinkingDuration →
  m.thinkingDuration`、`item.sources → m.sources` 都带上。
  - **不**新建任何组件、**不**动 `MessageItem` / `MarkdownRenderer` / `Sources` —— PR3 已就绪，PR4 只做数据管道。

  ### 测试

  - 后端新增：
    - `ConversationMessageServiceSourcesPersistenceTest`（mock DAO，断言 `updateSourcesJson` 被调用一次、JSON Jackson round-trip 等价）
    - 扩 `StreamChatEventHandlerCitationTest` 或新建 `StreamChatEventHandlerPersistenceTest`（holder set → persist 调用一次、传的 JSON
  能反序列化成等价 `List<SourceCard>`；holder 空 → persist 不调）
    - 扩 `ConversationServiceTest`（或新建）：sources_json 有值 → VO.sources 填充；`malformed-json` → VO.sources null 不抛
  - 前端扩 `chatStore.test.ts`：`selectSession` 正确映射 `sources` + `thinking` + `thinkingDuration` 三字段
  - 迁移脚本本地手测：`docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.8_to_v1.9.sql`
  重复执行不报错（幂等）

  ## PR4 不包含（明确排除）

  - `SourceCard.kbName` 字段（SRC-5 future additive）
  - flag 翻 true（PR5 独立）
  - 任何 prompt / 前端 UI 改造（PR3 完成）
  - Milvus/Pg retriever 回填 docId/chunkIndex（SRC-1）
  - `updateTraceTokenUsage` 改 merge 写（SRC-9 root-cause 根治；PR3 已顺序规避）
  - `onSuggestions streamingMessageId guard` 补齐（SRC-4）

  ## 执行流程

  推荐 **superpowers:brainstorming → writing-spec → writing-plan → subagent-driven-development**。PR4 scope 比 PR3 小，brainstorm 可合入
  spec 写作阶段，但 spec / plan / execute 三段仍建议各签字一次。

  每个后端 task 验证 gate 仍然是 **`mvn -pl bootstrap clean compile -q`**（PR2 L2 教训：`target/` stale bytecode 瞒过错误
  import）。subagent prompt 必须写"若 spec/plan 类路径或签名与实际代码冲突，以代码为准；先 Read 真源再写测试"（PR2 L3 教训）。

  ## 关键架构 invariants（PR4 不能违反）

  1. **JSON 序列化一律 Jackson**，禁止 Gson（历史坑：Gson int → `"N.0"`）。
  2. **落库失败不阻塞主流程**：persist + deserialize 都 try/catch Exception + log.warn，降级为 `sources=null`。
  3. **零 ThreadLocal 新增**：handler 已从构造期持有 `messageId`（memoryService.append
  返回值）、`cardsHolder`、`traceId`——所有读都走这三个。不新增 ThreadLocal。
  4. **schema 双写**：`schema_pg.sql` + `full_schema_pg.sql` 必须同步，后者 COMMENT 独立块。
  5. **迁移脚本命名按当前最新版本递增**：`v1.8_to_v1.9.sql`，不是 spec 写死的 `v1.4_to_v1.5.sql`。CLAUDE.md 的升级脚本清单同步加一行。
  6. **`thinkingContent/Duration` 是"顺带修"不是新增**：后端 VO 有这两字段但前端一直没映射；PR4 只在前端 TS 接口 + selectSession
  映射补齐，**不改后端**。

  ## 分支 & PR

  - 基线：`feature/answer-sources-pr4`（从**最新 main** 拉；**不** stacked —— PR1+PR2 踩过的坑不重演）
  - PR base：`main`
  - commit message 规范：`<type>(sources): <desc> [PR4]`，type ∈ `feat / test / chore / docs / fix`
  - 预期 commit 数：~10-15（spec + plan + 8-12 feat/test/chore/fix + docs）

  PR body 模板：

  ```
  ## Summary
  - PR4 for Answer Sources: persist sources_json to t_message + thread VO.sources through selectSession + fix frontend's missing
  thinkingContent/Duration mapping
  - Feature flag `rag.sources.enabled=false` unchanged — zero user-visible change
  - Jackson-only serialization; persistence failures degrade to sources=null without blocking main flow
  - Migration upgrade_v1.8_to_v1.9.sql idempotent; dual schema write (schema_pg.sql + full_schema_pg.sql)

  ## Test plan
  - [ ] Backend unit: ConversationMessageServiceSourcesPersistenceTest, StreamChatEventHandlerPersistenceTest, ConversationServiceTest —
  all green
  - [ ] Frontend unit: chatStore.test.ts selectSession mapping cases green
  - [ ] Type check: ./node_modules/.bin/tsc --noEmit exit 0
  - [ ] Spotless: mvn -pl bootstrap spotless:check BUILD SUCCESS
  - [ ] Migration: upgrade_v1.8_to_v1.9.sql runs cleanly + idempotent
  - [ ] Manual smoke (flag on): answer streams with [^n], refresh page → sources persist and re-render; thinking 字段在已有会话也正确映射

  ## Links
  - Spec: docs/superpowers/specs/2026-04-17-answer-sources-design.md (v1 总体设计)
  - PR3 Spec: docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md
  - PR3 dev_log: log/dev_log/2026-04-22-answer-sources-pr3.md
  ```

  ## 执行前确认

  - 确认 PR3 已合入 main（step 1 的 `git log origin/main` 能看到 17 个 [PR3] commit）
  - 读完上述 5 个文档，确认理解 PR4 scope 与 6 条 invariant
  - 若 PR3 dev_log 未写，先补（这次不踩 PR2 → PR3 间"dev_log 延迟写"的节奏问题）
  - 选好执行模式（推荐 subagent-driven）
  - 然后开始 brainstorm / spec

  ---
  关键点：① step 1 先 verify PR3 merge 状态，未合不开工；② migration 版本号坑（v1.8_to_v1.9，不是 spec 里的 v1.4_to_v1.5）；③ PR3 dev_log
  建议在启动 PR4 前先补，保持节奏；④ thinking 字段补齐定性为"前端映射 catch-up"而非新增，避免 scope 漂移。