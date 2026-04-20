# Answer Sources PR1 → PR2 Handoff

> 2026-04-20 结束 PR1，2026-04-21+ 在另一台机器继续 PR2。本文档包含恢复开发所需的全部上下文。

## 今日交付状态（PR1 完成）

**分支：** `feature/answer-sources-pr1`（已推送 origin；7 个 commits ahead of main 起点 `dd00053`）

| # | commit | 说明 |
|---|---|---|
| 1 | `054ef66` | Plan 文档（在 main 上） |
| 2 | `a01d1a3` | Task 0：spec + Milvus/Pg/Validator 注释同步 |
| 3 | `5ca0fc5` | Task 0 fixup：去掉 spec 表里残留的 `EnrichedChunk` 引用 |
| 4 | `4bf8ec6` | Task 1：`VectorMetadataFields` 新增 `DOC_ID`/`CHUNK_INDEX` 常量 |
| 5 | `81fad04` | Task 2：`RetrievedChunk` 新增 `docId`/`chunkIndex` 字段 + `@EqualsAndHashCode(of="id")` 等值固定 |
| 6 | `e11ec71` | Task 3：`OpenSearchRetrieverService.toRetrievedChunk` 回填两个新字段 |
| 7 | `96b2d06` | Task 4：`DocumentMetaSnapshot` record + `KnowledgeDocumentService.findMetaByIds` |

**质量门禁：**
- `mvn clean install -DskipTests` ✅
- `mvn spotless:check` ✅
- `mvn test` ✅ 11 个新单测全过，0 新增失败（仅 10 个 CLAUDE.md 已列的基线失败）
- 5 个 task 每个都过了两阶段 review（spec compliance + code quality）
- 跨任务最终 review 通过 READY TO MERGE

**冒烟测试：** 用户已在 2026-04-20 本机完成，OK。

## PR1 GitHub PR 尚未打开

GitHub 给的 PR 创建 URL：<https://github.com/ZhongKai-07/rag-knowledge-dev/pull/new/feature/answer-sources-pr1>

明天决定是否今天打开 PR（见下方"明日第一步"）。

## 关键设计决策（PR2 要继承）

1. **字段直扩路线，不用 `EnrichedChunk` 包装类。** PR2 的 `SourceCardBuilder` 直接从 `RetrievedChunk.getDocId()` / `.getChunkIndex()` 取值，不需要 unwrap 任何 wrapper。spec 已在 `5ca0fc5` 同步。
2. **`RetrievedChunk` equality 已显式固定到 `id`。** PR2 里如果有 `.distinct()` 或 Set/Map 按 `RetrievedChunk` key，行为是"按 id 去重"。
3. **OpenSearch-only。** Milvus/Pg 的 `docId`/`chunkIndex` 故意不回填，注释已说明。PR2 的 SSE `sources` 事件在这两种后端下会推空卡片列表——如果 `rag.vector.type!=opensearch`，整个 answer-sources 功能退化为"不推送 source 事件"，前端 `<Sources/>` 不渲染（符合 spec 的"feature flag off + 非 opensearch 场景行为一致"）。

## PR1 PR 描述要包含的副作用（Task 2 review 发现的 latent bug 修复）

`RetrievedChunk.@EqualsAndHashCode(of="id")` 的副作用是修复了 `DefaultContextFormatter.java:103` 的隐性 bug——之前 `@Data` 生成的 all-fields equality 导致"同 id 不同 score"的 chunks（多意图检索同一 chunk 拿到不同 rerank 分数）不会被 `.distinct()` 去重，悄悄膨胀 prompt context。现在正确折叠。**打 PR 描述时务必点名这个点**，部署后若"每次问答的 prompt chunks 数量"指标下降，不是 bug 而是符合预期的修复。

两个 `.distinct()` 触点：
- `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java:194`（suggestion-context）
- `bootstrap/.../rag/core/prompt/DefaultContextFormatter.java:103`（multi-intent prompt context — **latent bug fix**）

## PR1 review 中识别的 follow-up（非阻塞，可放 backlog）

放到 `docs/dev/followup/backlog.md` 或 PR 描述脚注：

| 项 | 位置 | 优先级 |
|---|---|---|
| 写路径字符串字面量扫荡：`"doc_id"` / `"chunk_index"` → `VectorMetadataFields.DOC_ID` / `.CHUNK_INDEX` | `IndexerNode.java:230`, `OpenSearchVectorStoreService.java:226-227`, `OpenSearchVectorStoreAdmin.java:280-281`（text block 改不了，留注释即可）, `MilvusVectorStoreService.java:220-221`, `PgVectorStoreService.java:119-120` | Minor |
| Milvus / Pg retriever 回填 `docId`/`chunkIndex`（以及历史未回填的 `kbId`/`securityLevel`）：启用这两个后端之前必须做 | 注释已在 Task 0 更新 | Minor（dev-only 后端） |
| `KnowledgeDocumentService.findMetaByIds` 接口签名改回普通 import（当前用 FQCN 风格和其他接口不一致） | `KnowledgeDocumentService.java:139-140` | Minor |
| 测试文件路径统一（`/service/` vs `/service/impl/`） | `KnowledgeDocumentServiceImplTest` 和 `KnowledgeDocumentServiceImplFindMetaTest` 目前分两处 | Minor |

## PR2 范围预告（按 spec `docs/superpowers/specs/2026-04-17-answer-sources-design.md`）

- `SourceCardBuilder`（聚合 `List<RetrievedChunk>` → `List<SourceCard>`，按 docId 分组，调 `findMetaByIds` 取 docName/kbId）
- 新 DTO：`SourceCard` / `SourceChunk` / `SourcesPayload`
- `SSEEventType.SOURCES` 事件枚举
- `RAGChatServiceImpl` 编排层注入 `SourceCardBuilder`，在 SSE 流里推送 `sources` 事件（在首个 `message` 之前、`finish` 之前）
- `StreamChatEventHandler` / `StreamChatHandlerParams` 加字段
- 前端 `<Sources />` 骨架（flag 默认 off，卡片渲染占位）
- **feature flag：** `rag.sources.enabled: false`（默认 off 确保 PR 合并无可见影响）
- **不包括：** Prompt 改造、`remarkCitations`、`CitationBadge`、`t_message.sources_json` 持久化——那些是 PR3/PR4 工作

spec 里 PR2 行：`SourceCardBuilder + SourceCard/SourceChunk/SourcesPayload DTO + SSEEventType.SOURCES + RAGChatServiceImpl 编排 + 前端 <Sources /> 骨架（flag 默认 off）`

## 本机未推送的 untracked 文件（仅本机可见）

```
.claude/scheduled_tasks.lock
.tmp-session-diff.txt
docs/dev/design/2026-04-20-dept-isolation-phase2-plan.md
docs/dev/follow-up/followup_plan_based_on_04-19_review.md
log/screenshots/2026-04-20-p0.3-opsadmin-addsharing.png
log/screenshots/p15c-admin-sharing.png
```

**这些是此前别的工作线的 in-progress 文件，与 PR1/PR2 无关。**如果你在明天机器上要继续那条线，需要今天手动 commit/stash/push；否则它们就留本机了（建议这么做，不污染 PR 分支）。

## 明日第一步（在新机器上）

```bash
# 1. 拉最新代码
cd <workspace>
git fetch origin
git checkout main && git pull

# 2. 拉 PR1 分支
git checkout feature/answer-sources-pr1

# 3. 本地校验（一次性确认环境正常，~1 分钟）
mvn -pl framework install -DskipTests -q
mvn -pl bootstrap test -Dtest=RetrievedChunkTest,OpenSearchRetrieverServiceTest,KnowledgeDocumentServiceImplFindMetaTest -q
# 应显示 Tests run: 11, Failures: 0, Errors: 0

# 4. （可选）现在打开 PR1 的 GitHub PR
gh pr create --title "feat(sources): PR1 — retrieval metadata plumbing (docId + chunkIndex)" --body-file - <<'EOF'
## Summary
PR1/5 of Answer Sources feature track — metadata plumbing only, zero user-visible behavior change.

- `VectorMetadataFields` 新增 `DOC_ID` / `CHUNK_INDEX` 常量（SSOT）
- `RetrievedChunk` 新增 `docId` / `chunkIndex` 两个可空字段（沿用 `kbId`/`securityLevel` 模式），并用 `@EqualsAndHashCode(of="id")` 显式固定 equality 到 id
- `OpenSearchRetrieverService` 回填两个新字段；Milvus/Pg 保持 dev-only 不回填（注释已同步）
- 新增 `KnowledgeDocumentService.findMetaByIds` + `DocumentMetaSnapshot` record，批量查询文档元信息快照（供 PR2 消费）

## 等值语义副作用（重要）
将 `RetrievedChunk` equality 从 `@Data` 全字段隐式相等改为显式 id-only，修复了 `DefaultContextFormatter.java:103` 的隐性 bug：同 id 不同 rerank score 的 chunks（多意图检索同一 chunk）之前不会被 `.distinct()` 去重，悄悄膨胀 prompt context。另一 `.distinct()` 触点在 `RAGChatServiceImpl.java:194`。

## Test plan
- [x] 11 个新单测全过（`RetrievedChunkTest` / `OpenSearchRetrieverServiceTest` / `KnowledgeDocumentServiceImplFindMetaTest`）
- [x] `mvn clean install -DskipTests` / `mvn spotless:check` 绿
- [x] 本机 OpenSearch 冒烟已过
- [ ] 部署后观察 "每次问答的 prompt chunks 数量" 指标（预期下降，因为 latent bug fix）

## Deferred（follow-up）
- 写路径字面量扫荡（`"doc_id"`/`"chunk_index"` → 常量）
- Milvus/Pg retriever 回填（等这两个后端进生产前补）
EOF

# 5. 从 PR1 分支 tip 拉 PR2 分支
git checkout -b feature/answer-sources-pr2

# 6. 开始 PR2：把下面那段 "明日接续开发提示词" 粘到 Claude Code
```

## 明日接续开发提示词（粘给新 Claude Code）

```
继续开发 answer-sources 功能的 PR2。上下文：

- 当前在 `feature/answer-sources-pr2` 分支（从 PR1 分支 tip `96b2d06` 拉出）
- PR1 已完成 + 推送（`feature/answer-sources-pr1` on origin），是否已打开 GitHub PR 你确认下
- 完整上下文、决策、follow-up 全在 `docs/superpowers/plans/2026-04-20-answer-sources-pr1-handoff.md`，请先读
- 设计 spec 在 `docs/superpowers/specs/2026-04-17-answer-sources-design.md`

PR2 范围（按 spec 和 handoff 文档）：`SourceCardBuilder` + `SourceCard/SourceChunk/SourcesPayload` DTOs + `SSEEventType.SOURCES` + `RAGChatServiceImpl` 编排 + 前端 `<Sources />` 骨架。feature flag 默认 off，零用户可见变化。

执行策略（继承昨天成功的流程）：
1. superpowers:brainstorming 先确认 PR2 的边界问题（尤其：SSE 事件顺序、前端骨架要多细、空结果不推送事件的判定点）
2. superpowers:writing-plans 产出 plan（路径 `docs/superpowers/plans/2026-04-2X-answer-sources-pr2-<topic>.md`）
3. superpowers:subagent-driven-development 执行，每 task 两阶段 review

先读 handoff 文档，然后开始 brainstorming。
```

## PR1 分支结束清理（所有 PR 合并后执行，非紧急）

```bash
# PR1 + PR2 + ... 都合进 main 后
git checkout main
git pull
git branch -d feature/answer-sources-pr1
git push origin --delete feature/answer-sources-pr1
```
