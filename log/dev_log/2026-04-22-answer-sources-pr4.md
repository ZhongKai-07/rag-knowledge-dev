# Answer Sources PR4 — 持久化 sources_json + VO 映射补齐

**日期**：2026-04-22
**分支**：`feature/answer-sources-pr4`（PR [#16](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/16)，21 commits）
**状态**：merged 进 `origin/main`（merge commit `a9a4711`）
**设计文档**：`docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md`
**实施计划**：`docs/superpowers/plans/2026-04-22-answer-sources-pr4-implementation.md`
**feature flag**：`rag.sources.enabled=false`（仍默认关，PR5 才翻 true）

---

## 背景

Answer Sources 5-PR 系列的第 4 个 PR。承接 PR1（元数据 plumbing）+ PR2（编排 + SSE + 前端路由骨架）+ PR3（Prompt 改造 + remarkCitations + 引用质量埋点）。PR4 补齐"持久化 + VO 映射"这条末端数据链：新消息的 `sources` 落 `t_message.sources_json`，`listMessages` 反序列化回 VO，刷新页面时 `<Sources />` 能重现。PR4 在 flag off 状态下与 PR3 末态**字节级等价**，零用户可见变化。

| PR | 范围 | 状态 |
|----|------|------|
| PR1 | `RetrievedChunk.docId/chunkIndex` + OpenSearch 回填 + `findMetaByIds` | ✅ #12+#14 |
| PR2 | DTO + `SourceCardBuilder` + SSE SOURCES 事件 + 前端路由 | ✅ #13 |
| PR3 | Prompt citation mode + `remarkCitations` + `CitationBadge` + `<Sources />` + 引用质量埋点 | ✅ #15 |
| **PR4（本文档）** | `t_message.sources_json` 持久化 + `ConversationMessageVO.sources` + 前端 `thinking`/`thinkingDuration`/`sources` VO catch-up | ✅ #16 |
| PR5 | `rag.sources.enabled: true` 上线 + 清 PR3 遗留 4 nit | ⏳ 待开始 |

---

## 落盘事实

### 后端（7 文件改 + 2 测试类新增）

| 文件 | 内容 |
|---|---|
| `rag/dao/entity/ConversationMessageDO.java` | `@TableField("sources_json", updateStrategy=...) String sourcesJson` 新字段。`updateStrategy` 与同表 `thinkingContent` 对齐（commit `9d7118e`） |
| `rag/controller/vo/ConversationMessageVO.java` | `private List<SourceCard> sources` 新字段 |
| `rag/service/ConversationMessageService.java` | 新接口方法 `void updateSourcesJson(String messageId, String json)` |
| `rag/service/impl/ConversationMessageServiceImpl.java` | (a) 实现 `updateSourcesJson`：`StrUtil.isBlank` guard → `mapper.update(entity, Wrappers.lambdaUpdate(...).eq(getId, messageId))`，对齐 `RagTraceRecordServiceImpl` 风格；(b) `listMessages` VO builder 加 `.sources(deserializeSources(record.getSourcesJson()))`，Jackson 异常 → `null` 降级不抛；(c) 静态 `SOURCES_MAPPER`；(d) 加 `@Slf4j` |
| `rag/service/handler/StreamChatHandlerParams.java` | 新字段 `conversationMessageService`（final，通过 Lombok builder 注入） |
| `rag/service/handler/StreamCallbackFactory.java` | `ConversationMessageService` 作为 Spring bean 注入 factory，builder 链加一行 `conversationMessageService(this.conversationMessageService)` |
| `rag/service/handler/StreamChatEventHandler.java` | (a) 新 final 字段 `conversationMessageService`；(b) `persistSourcesIfPresent(String messageId)` 新方法：`messageId blank → return` + `cardsOpt.isEmpty() → return` + `SOURCES_MAPPER.writeValueAsString(cards)` + `updateSourcesJson(...)` + try/catch Exception → log.warn 降级；(c) `onComplete` 在 `memoryService.append` 之后、`updateTraceTokenUsage` 之前插入调用 |
| `ConversationMessageServiceSourcesTest`（新，读写双路径测试） | 写路径：`updateSourcesJson` blank-messageId guard / 正常 lambdaUpdate path / mapper 异常冒泡；读路径：`listMessages` sources_json 反序列化成 VO / malformed-json 降级 null / null 值直通。`initTableInfo` helper 抽出来（commit `1e834f0`），备注 AbstractWrapper cast 的 MyBatis Plus 约定 |
| `StreamChatEventHandlerPersistenceTest`（新，onComplete 落库路径） | (a) `messageId` 空 → 不调 `updateSourcesJson`；(b) `cardsHolder` 空 → 不调；(c) 正常 → 调用 + json 参数断言；(d) `updateSourcesJson` 抛异常 → handler 不中断，`onComplete` 后续 FINISH/merge/DONE 主链路全绿；(e) `InOrder` 锁 `memoryService.append → persistSourcesIfPresent → updateTraceTokenUsage → mergeCitationStatsIntoTrace` 顺序（commit `0829f08` 补完整 roundtrip） |

### 前端（2 改 + 1 测试扩）

| 文件 | 内容 |
|---|---|
| `services/sessionService.ts` | `ConversationMessageVO` interface 补三字段（全 optional）：`thinkingContent?: string` / `thinkingDuration?: number` / `sources?: SourceCard[]`；加 `SourceCard` import |
| `stores/chatStore.ts` | `selectSession` mapper 写 `thinking: item.thinkingContent` / `thinkingDuration: item.thinkingDuration` / `sources: item.sources`。修复 PR2/3 期间未做的"刷新页面后历史深度思考 + sources 不回显"bug |
| `stores/chatStore.test.ts` | 新增 `describe('selectSession mapping')`：三字段单独/组合/缺省/空数组的往返映射覆盖 |

### SQL + 文档

| 文件 | 内容 |
|---|---|
| `resources/database/upgrade_v1.8_to_v1.9.sql`（新） | `ALTER TABLE t_message ADD COLUMN IF NOT EXISTS sources_json TEXT;` + `COMMENT ON COLUMN` 幂等语句。命名从 v1 spec 的 `v1.4_to_v1.5` 改为 `v1.8_to_v1.9`（spec 写时未校对 migration 链当前状态） |
| `resources/database/schema_pg.sql` | `CREATE TABLE t_message` 加 `sources_json TEXT`；COMMENT 块加一行（双写保证 fresh init 与 upgrade 两条路径一致） |
| `resources/database/full_schema_pg.sql` | 同上，pg_dump 风格 `text`（小写） |
| `CLAUDE.md` | 根级"Upgrade 脚本"清单加 `upgrade_v1.8_to_v1.9.sql` 条目 |

### 测试总数（PR4 新增）

- **后端 37 新用例**：`ConversationMessageServiceSourcesTest` 读写合并 + `StreamChatEventHandlerPersistenceTest` handler 路径
- **前端 31 新用例**：`chatStore.test.ts` 的 `selectSession mapping` 扩 + 对齐既有的 handler/sources/citation 测试
- 合计 **37 后端 + 31 前端** 全绿；PR3 末态测试零回归

---

## 架构 invariants（PR4 保持 / 新增，共 7 条）

1. **orchestrator 决策 / handler 机械发射**：PR4 落库是 `onComplete` 的机械 side-effect，不掺决策（flag / cards 空都是早返回 guard，不是业务分支）；`RAGChatServiceImpl` 的"三层闸门 + cards 推送"逻辑零改动
2. **零 ThreadLocal 新增**：`ConversationMessageService` 经 `StreamChatHandlerParams` → `StreamCallbackFactory` → handler 构造器注入为 `final` 字段；`messageId` 是 `memoryService.append` 同步返回值。**不**在异步段读 `UserContext` / `RagTraceContext`
3. **持久化失败降级不阻塞**：`persistSourcesIfPresent` try/catch 所有 Exception → `log.warn`；DB 挂了 / Jackson 挂了 / service 抛了都不影响 `onComplete` 后续 `updateTraceTokenUsage` → `mergeCitationStatsIntoTrace` → `FINISH/SUGGESTIONS/DONE` 主链路。Handler 的"最后一公里"稳定性 > 单条 sources 快照完整性
4. **读路径降级对齐**：`listMessages` 里单个 `sourcesJson` 反序列化失败 → `sources=null`（`malformed-json` 与 `null` 同语义），不拖累整个响应。历史脏数据不阻塞会话加载
5. **落库时机严格**：必须在 `memoryService.append` **之后**（拿到 `messageId`）、`updateTraceTokenUsage` **之前**（保持 `onComplete` trace 写入顺序链）。`StreamChatEventHandlerPersistenceTest` 用 `Mockito.InOrder` 锁此顺序
6. **取消路径不落库**（v1 spec 契约）：`buildCompletionPayloadOnCancel` 分支不调 `persistSourcesIfPresent`。SSE 中途断开的 `sources` 留本会话视觉可见，但刷新后不重现——"异常中断不落库"规则显式生效
7. **Rollback 契约字节级等价**：flag=false 时 `cardsHolder` 永远为空 → `persistSourcesIfPresent` 空 guard 早返回 → `sources_json` 永远 NULL → `listMessages` 映射为 `sources=null` → 前端 `hasSources=false` → PR3 末态零偏移

---

## Review 流程

PR4 走了 **2 轮 spec review + 多轮 code-quality fix** 的闭环，与 PR3 同款但更严（落库 + VO 跨域，错了容易脏数据）。

### Spec review（2 轮）

| Round | commit | 发现 | 修法 |
|---|---|---|---|
| round-1 | `ff21e03` | 3 findings：(a) `upgrade_v1.4_to_v1.5.sql` 被历史占用，v1 spec 命名过期；(b) "`ConversationService.selectSession(...)`" 类名口误（实际 `ConversationMessageServiceImpl.listMessages`）；(c) v1 spec 说"VO 加 `thinkingContent`/`thinkingDuration`"但后端已映射，PR4 仅改前端 | 收紧 § 0 差异摘要表；锚点从口误移到真源；scope 改为"前端映射 catch-up" |
| round-2 | `c2ca1b7` | 1 finding：spec 示例用 `ListAppender<ILoggingEvent>` 捕获 log.warn，但 `StreamChatEventHandler` 用的是 `@Slf4j` 的 logback binding，直接 `Mockito.spy(log)` 语义不对 | 删除 fabricated reference；改为 "降级路径用 `verify(log, times(1))` + `verify(mainFlow).continue()`" 语义化断言 |

### Task-level code-quality fix

`0829f08` / `1e834f0` / `465c5d5` / `9d7118e` 是 TDD 过程中发现的实现/测试小偏差：handler test wiring 缺 InOrder / wrapper cast 需注释说明 / 遗留字段未清 / `@TableField(updateStrategy)` 对齐 peer 字段等。每次发现即 commit，不堆到最后。

---

## Known follow-ups（交给 PR5）

**PR3 遗留 4 条 nit**（延续到 PR5 顺手收）：

- **N-3** `MarkdownRenderer.tsx` — `components` 对象走 `React.useMemo`（PR3 spec §3.6 规定，实际内联字面量）
- **N-4** `remarkCitations.test.ts` — 用完整 `unified().use(remarkParse).use(remarkGfm).use(remarkCitations).runSync()` pipeline（PR3 spec §4.3 规定，实际 `.parse()` + 手调插件）
- **N-5** 抽 `CITATION_HIGHLIGHT_MS = 1500` 常量到 `citationAst.ts`；`MessageItem.tsx:45` 引用替换
- **N-6** `Sources.tsx:22` 删 `const visible = cards` 的 identity alias

**backlog 条目**（SRC-1/3/4/5/7/9 不在 PR5 范围，继续沉淀）：见 `docs/dev/followup/backlog.md` SRC-1 ~ SRC-9。SRC-6 在 PR3 已闭环。

---

## 流程亮点

- **2 轮 spec review + 多次 task-level fix 的分层闭环**：spec reviewer 在写代码**前**先核对设计文档与既有代码锚点（发现 v1 spec 的 v1.4→v1.5 命名过期 + `ConversationService.selectSession` 类名口误）；code quality reviewer 在每个 task 完成时审断言严格度、wrapper 写法、`@Slf4j` binding 等细节。Important 立即 commit，Nit 写进 PR body
- **Stacked PR 陷阱已避**：PR4 base 直接对 main（PR1-3 都在 main）；吸取 PR2 stacked PR base 翻车教训
- **零 blocker + 零 regression**：基线失败项（Milvus / pgvector / seeded KB data）未新增；PR3 末态 24 前端 + 17 后端用例全绿；PR4 新增 37 后端 + 31 前端用例全绿
- **前端 sources VO catch-up**：PR2 写 `<Sources />` 但 `selectSession` 只映射 `content` 字段，刷新页面丢 sources；PR3 写 remarkCitations 但同样漏 catch-up；PR4 回头补齐（也顺手修了 `thinking` 系列，是 PR2/3 期间同源遗漏）

---

## PR 合并链

| PR | 说明 | 合进 |
|---|---|---|
| [#16](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/16) | PR4 — sources_json 持久化 + VO 映射补齐 + 前端 selectSession catch-up | `main`（merge commit `a9a4711`） |

---

## 文件改动总览

**后端 7 改 + 2 测试新增** = 9 文件
**前端 2 改 + 1 测试扩** = 3 文件
**SQL/文档 4**：upgrade_v1.8_to_v1.9.sql 新 + schema_pg.sql 改 + full_schema_pg.sql 改 + 根 CLAUDE.md
**Spec/plan/log 3**：design + plan + 本日志
**总计**：19 代码 / SQL 文件 + 3 文档

## 下一步（PR5）

- `rag.sources.enabled: false → true` 唯一功能改动
- 清 PR3 遗留 4 nit（frontend only，N-3/4/5/6）
- 8 项手动冒烟（flag flip = 功能真上线，测试过不代表生产过）
- 补 PR5 dev_log，Answer Sources 5-PR 系列正式完结
