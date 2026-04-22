---
继续执行 answer-sources 功能 **PR5**（`rag.sources.enabled: false → true` 翻上线 + 顺手清 PR3 遗留 4 条 nit + 写 PR4/PR5 dev_log）。

## 上下文（6 步加载）

1. **核对 PR4 是否已合入 main**（PR5 必须 PR4 落地后才启动）：
   ```bash
   git fetch origin && git log origin/main --oneline | head -25
   ```
   预期看到 merge commit `a9a4711 Merge pull request #16 from ZhongKai-07/feature/answer-sources-pr4` + **21 个** PR4 标签提交（含 spec 3 轮 review / plan / 10 TDD task / 2 轮 Minor fix / cleanup）。若未见，停下确认 merge 状态。

2. **读以下 6 个文档按序理解 PR5 边界**：
   - 上游 v1 spec: `docs/superpowers/specs/2026-04-17-answer-sources-design.md`（PR 拆分矩阵 § "PR 拆分与回滚" 明确 PR5 = flag 翻 true）
   - **PR3 设计**: `docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md`（§ 8 Gotchas + `MarkdownRenderer` / `remarkCitations` 原始设计，是 4 条 nit 的起点）
   - **PR3 dev_log**: `log/dev_log/2026-04-22-answer-sources-pr3.md`（§ "Known follow-ups" 列了 N-3 / N-4 / N-5 / N-6 四条）
   - **PR4 设计 + 计划**: `docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md` + `docs/superpowers/plans/2026-04-22-answer-sources-pr4-implementation.md`（§ 6 "Not in PR4" 明确把 N-3~N-6 推给 PR5）
   - **PR4 dev_log**：**如果还没写，动 PR5 前先补一个**（templates 参 `log/dev_log/2026-04-22-answer-sources-pr3.md`）。不补会延续 "PR 合完不写 log" 的漂移，未来交接越积越深。

3. **main 上现在已有的基线（PR4 合并后）**：
   - **后端**：`ConversationMessageDO.sourcesJson` 持久化（`@TableField("sources_json", updateStrategy=NOT_NULL)`）/ `ConversationMessageVO.sources` / `ConversationMessageService.updateSourcesJson(messageId, json)` + `listMessages` 反序列化 / `StreamChatEventHandler.persistSourcesIfPresent` 在 `onComplete` `memoryService.append` 之后、`updateTraceTokenUsage` 之前
   - **wiring**：`ConversationMessageService` 经 `StreamChatHandlerParams` / `StreamCallbackFactory` 注入 handler final 字段（零 ThreadLocal 新增）
   - **前端**：`sessionService.ts` VO 扩 `thinkingContent?` / `thinkingDuration?` / `sources?` 三字段 + `chatStore.selectSession` mapper 带三字段
   - **SQL**：`upgrade_v1.8_to_v1.9.sql` 已合 + `schema_pg.sql` / `full_schema_pg.sql` 双写同步
   - **测试**：PR4 新增 37 后端用例 + 31 前端用例，全绿；PR3 的 InOrder 锁 overwrite→merge 与 PR4 的 InOrder 锁 persist→merge 共同钉住 `onComplete` 三段顺序
   - **feature flag** `rag.sources.enabled=false`（仍默认关，本 PR5 要翻 true）

4. **从最新 main 拉新分支**（直合 main，非 stacked）：
   ```bash
   git checkout main && git pull origin main
   git checkout -b feature/answer-sources-pr5
   ```

5. **读 `docs/dev/followup/backlog.md` 的 SRC-1 至 SRC-9**。PR5 **scope 外**（不要顺手做）：SRC-1 Milvus/Pg 回填 / SRC-3 handler 残留 ThreadLocal / SRC-4 onSuggestions guard / SRC-5 kbName 字段 / SRC-7 ESLint break / SRC-9 updateTraceTokenUsage 根治。

6. **本地跑一遍基线确认当前 main 全绿**：
   ```bash
   mvn -pl bootstrap test -Dtest='ConversationMessageServiceSourcesTest,StreamChatEventHandlerPersistenceTest,StreamChatEventHandlerCitationTest,StreamChatEventHandlerSuggestionsTest,RAGPromptServiceCitationTest,CitationStatsCollectorTest,RAGChatServiceImplSourcesTest' -q
   cd frontend && npm run test -- --run && ./node_modules/.bin/tsc --noEmit && cd ..
   ```
   预期：37 后端 + 31 前端全绿。若不绿，先排查（可能是本地环境问题 / 基线回归，PR5 动手前必须先清）。

## PR5 范围

### 1. Flag flip（**唯一功能改动**）

**改一行 YAML**：`bootstrap/src/main/resources/application.yaml`

找到 `rag.sources:` 块，把 `enabled: false` 改为 `enabled: true`。保留 `preview-max-chars: 200` / `max-cards: 8` 不动。

**配套文档更新**：
- **`bootstrap/CLAUDE.md`** 的"主要配置节"表里 `rag.sources` 描述改为反映 live 状态（去掉"PR2-PR4 期间默认 off，PR5 才翻 true"的口径，改为"2026-04-XX 起默认 on"）
- 根 `CLAUDE.md` 若有类似口径同样更新（grep `rag.sources.enabled` 定位）

### 2. 清理 PR3 遗留 4 条 nit（frontend only）

所有 4 条都在 `frontend/src/components/chat/` 或 `frontend/src/utils/`，**先 Read 每个文件确认行号**（PR4 期间这几个文件未动，但 Spotless / 其他局部修改可能挪过行）。

#### N-3: `MarkdownRenderer.tsx` `components` 对象走 `React.useMemo`

**位置**：`frontend/src/components/chat/MarkdownRenderer.tsx` L42 附近（spec §3.6 规定 components 用 `React.useMemo`，PR3 实际内联了字面量）。

**问题**：每次 render 都新建 `components` object，即便 `indexMap` / `onCitationClick` 没变也会触发子树重 mount。

**修法**：把 `components = { code: ..., cite: ... }` 包进 `React.useMemo(() => ({...}), [hasSources, indexMap, onCitationClick])`。依赖数组和 PR3 spec §3.6 一致。

#### N-4: `remarkCitations.test.ts` 用完整 unified pipeline

**位置**：`frontend/src/utils/remarkCitations.test.ts` L15 附近（spec §4.3 规定用 `unified().use(remarkParse).use(remarkGfm).use(remarkCitations).runSync(tree)`，PR3 实际是 `.parse()` + `(plugin as any)(tree)` 手调）。

**问题**：当前 plugin 无 unified context 依赖所以没出事；但若未来 plugin 用到 context 会 silently 失败。

**修法**：把所有测试 helper 改成 `const tree = unified().use(remarkParse).use(remarkGfm).use(remarkCitations).runSync({ type: 'root', children: [...] })` 形式。需确认 `unified` / `remark-parse` 已在 devDeps（PR3 已加）。

#### N-5: 抽 `CITATION_HIGHLIGHT_MS` 常量

**位置**：
- `frontend/src/components/chat/MessageItem.tsx` L45 附近（`window.setTimeout(..., 1500)`）
- `frontend/src/components/chat/MarkdownRenderer.tsx` 或 `Sources.tsx` 可能有同样 magic number（grep `1500` 定位）

**修法**：在 `frontend/src/utils/citationAst.ts` 加 `export const CITATION_HIGHLIGHT_MS = 1500;`，两处引用替换。

#### N-6: `Sources.tsx` 去掉 identity alias

**位置**：`frontend/src/components/chat/Sources.tsx` L22 附近（`const visible = cards;` 是 PR3 决定 Option X 后遗留的 identity alias）。

**修法**：删该行，把下面 `visible.length === 0` / `visible.map(...)` 全替换为直接用 `cards`。注意 `if (cards.length === 0) return null;` 的守护位置保持不变。

### 3. 手动冒烟（PR5 是功能"真正上线"的 PR，冒烟必须完整）

参 PR3 spec § 4.5 + PR4 spec § 5.3 的手动冒烟清单，**按以下 8 项逐条验证**：

1. **空库冒烟**：KB 里不存在的问题（比如 "今天天气怎么样"）→ 答案走回答，**无** `<Sources />` / `[^n]`（空检索短路）
2. **KB 命中单文档**：提问命中单一文档多个 chunk → 答案含 `[^1]` + Sources 区 1 张卡 → 点 `[^1]` 滚到卡片 + 高亮 1.5s + auto-expand chunk preview
3. **KB 命中多文档**：提问跨 2-3 文档 → `[^1][^2][^3]` + Sources 3 张卡（按 topScore 降序）→ 点 `[^2]` 精确跳第 2 张
4. **刷新页面**（PR4 修复重点）：回 2 / 3 同一会话 → `selectSession` 从 DB 拉回 `sources_json` → Sources 卡片与角标**完整重现**（PR3 末态这里是空的）
5. **历史深度思考消息**：打开 PR2/3 期间的历史会话 → `thinking` / `thinkingDuration` 字段正确显示（PR4 前端 catch-up 修复）
6. **意图歧义 clarification**：提一个歧义问题（PR3 SRC-6 测试锁的路径）→ `<Sources />` / `[^n]` 均**不**出现，clarification prompt 正常流式返回
7. **流式中取消**：长回答中途点取消 → 已推的 Sources 留在本会话可见，**刷新后消失**（v1 spec 契约：异常中断不落库）
8. **LLM 越界引用**：若出现 `[^99]`（cards 只有 8 张）→ CitationBadge 降级为纯 `<sup>[^99]</sup>` 无交互，不点不报错
9. **DevTools 验证**（bonus）：Network 看 `/conversations/{id}/messages` 响应含 `sources` 数组；`t_rag_trace_run.extra_data` 查新 trace 含 `citationTotal / Valid / Invalid / Coverage`

**如果任何一项失败**：停下来诊断，不能合 PR5。flag 翻 true 的本质是"把 PR1-4 的完整链路暴露给生产"——任何隐藏 bug 都会此刻浮出。

### 4. 写 PR4 dev_log + PR5 dev_log

- **PR4 dev_log**：`log/dev_log/2026-04-22-answer-sources-pr4.md`——模板参 `log/dev_log/2026-04-22-answer-sources-pr3.md`；记录 21 commits、2 阶段 review 流程（spec round-1/2 + Task 2/4/7 三轮 code-quality fix）、架构 invariants 7 条保持、known follow-ups → PR5
- **PR5 dev_log**：`log/dev_log/2026-04-23-answer-sources-pr5.md`（假设 PR5 落地日）——记录 flag flip 日期、4 条 nit 清理、8 项手动冒烟结果、Answer Sources 5-PR 系列正式完结标记

**补日志时机**：**PR5 合并后立即写 PR5 log**；**PR4 log 在开 PR5 之前补**（作为 PR5 的基线上下文）。

## PR5 不包含（明确排除）

- `SourceCard.kbName` 字段（SRC-5）
- Milvus/Pg retriever 元数据回填（SRC-1 P0，切非 OpenSearch 后端前必补，但本 PR5 不动）
- `StreamChatEventHandler` 残留 `UserContext.getUserId()` ThreadLocal 读取（SRC-3）
- `onSuggestions` 缺 `streamingMessageId` guard（SRC-4）
- `updateTraceTokenUsage` overwrite 根治为 merge 写（SRC-9）
- ESLint 破损修复（SRC-7）
- 任何新的 prompt / UI / SSE / 持久化改动

**特别警告**：PR5 是"功能上线"的 PR，scope 漂移最诱人——看着哪里都想顺手改。坚决按上面 2 块（flag + 4 nit）执行，其他的留 backlog。

## 执行流程

PR5 scope 很小（~6-10 commits，flag 改 1 行 + 4 nit + 2 dev_log + docs 同步），**brainstorming 阶段可合入 spec 或直接跳过**。推荐：

- **writing-spec** (可选，若你觉得 4 nit 写法值得 align) → **writing-plan** (推荐) → **subagent-driven-development** (N-3 / N-5 / N-6 三条纯机械，N-4 纯测试改造；单 dispatch 搞定每条)

或者**直接 inline execute**：4 条 nit + flag flip 加起来大概 2-3 小时（含 spotless / tsc / frontend test），不一定要 subagent driven。

每个前端 task 验证 gate：
```bash
cd frontend && ./node_modules/.bin/tsc --noEmit && npm run test -- --run
```

**Flag flip 的 gate 更严**：改 yaml 后必须跑 `mvn -pl bootstrap spring-boot:run` 真启动验证（不能光看 compile）——测试 `application.yaml` 是否真被正确加载，`RagSourcesProperties.enabled` 是否真 true。

## 关键架构 invariants（PR5 不能违反）

1. **flag flip 是唯一的功能改动**：PR5 不引入任何新逻辑、不改任何 prompt / SSE / 持久化 / 前端渲染行为
2. **Rollback 契约**：如果 PR5 合并后出现生产问题，**`rag.sources.enabled=false` 运行时 hot rollback 必须保持可用**——flag off 时整条链路（prompt / SSE / persist / 前端）零副作用（PR3/4 已验证字节级等价）
3. **4 nit 全部 frontend-only**：零后端改动、零 spec 偏移
4. **N-3 依赖数组精确**：`useMemo` 依赖必须只含 `hasSources` / `indexMap` / `onCitationClick`。若加其他依赖或漏一个，会重建 components object 失去 memo 价值
5. **N-5 常量放哪**：`CITATION_HIGHLIGHT_MS` 放 `citationAst.ts`（已是 citation 相关 SSOT）。**不**新建 `constants.ts`——避免 frontend 常量多 SSOT 漂移
6. **N-6 守护不丢**：删 `const visible = cards` 时保留 `if (cards.length === 0) return null;` 早返回
7. **dev_log 补日的节奏**：PR4 log 必须在 PR5 开 commit 前补；PR5 log 在合并后立即补。不拖到下一轮 PR

## 分支 & PR

- 基线：`feature/answer-sources-pr5`（从**最新 main** 拉）
- PR base：`main`
- commit message 规范：`<type>(sources): <desc> [PR5]`，type ∈ `feat / chore / docs / fix / test`
- 预期 commit 数：~6-10
  - 1× `docs(sources): PR4 dev_log 补齐 [PR5]`（写 PR4 log，算 PR5 前置）
  - 1× `feat(sources): enable rag.sources.enabled + update CLAUDE.md config note [PR5]`
  - 1× `refactor(sources): MarkdownRenderer components useMemo (PR3 N-3) [PR5]`
  - 1× `test(sources): remarkCitations test use full unified pipeline (PR3 N-4) [PR5]`
  - 1× `refactor(sources): extract CITATION_HIGHLIGHT_MS constant (PR3 N-5) [PR5]`
  - 1× `refactor(sources): remove Sources identity alias (PR3 N-6) [PR5]`
  - 1× `docs(sources): PR5 dev_log + close Answer Sources 5-PR track [PR5]`

## PR body 模板

```
## Summary
- PR5 for Answer Sources: flip `rag.sources.enabled: false → true` 功能正式上线
- 顺手清 PR3 遗留 4 条 nit（N-3/4/5/6 frontend polish，零行为改动）
- 补 PR4 + PR5 dev_log，Answer Sources 5-PR 系列完结
- 零后端新功能；flag off 回退路径保留为一层 hot-rollback 通道

## Test plan
- [ ] Frontend unit: remarkCitations.test.ts unified-pipeline 重写后 7 用例全绿；其他既有 24 用例零回归
- [ ] Type check: `./node_modules/.bin/tsc --noEmit` exit 0
- [ ] Spotless + backend compile: 零 Java 改动，基线绿
- [ ] 手动冒烟 8 项全过（见 PR body 附录或 dev_log）：空检索 / 单文档 / 多文档 / 刷新持久化 / 历史思考字段 / 意图歧义 / 取消不落库 / 越界角标降级 / DevTools trace extra_data
- [ ] Hot rollback: `rag.sources.enabled=false` 重启，字节级回到 PR4 末态

## Links
- Spec: docs/superpowers/specs/2026-04-17-answer-sources-design.md (v1 总体设计)
- PR3 design / dev_log: docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md / log/dev_log/2026-04-22-answer-sources-pr3.md
- PR4 design / dev_log: docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md / log/dev_log/2026-04-22-answer-sources-pr4.md
```

## 执行前确认

- [ ] `git log origin/main | head -25` 看到 PR4 merge commit（`a9a4711`）+ 21 个 [PR4] 提交
- [ ] 读完上述 6 个文档，确认理解 PR5 scope（flag + 4 nit + 冒烟 + 2 dev_log）与 7 条 invariant
- [ ] 基线全绿（37 后端 + 31 前端）
- [ ] PR4 dev_log 已补（**PR5 动手前必做**）
- [ ] 选好执行模式（推荐 subagent-driven 或 inline；brainstorm 可跳）

## 关键点提醒

① **PR4 dev_log 先补**，别再延期；② **flag flip 是功能上线**，冒烟 8 项必须真过，不能光跑测试就合；③ **4 条 nit 只动前端**，任何后端改动立即 stop & reassess；④ **CITATION_HIGHLIGHT_MS 放 citationAst.ts**，不另起文件；⑤ **rollback 通道保留**——flag=false 必须仍是 PR3 末态字节级等价路径，PR5 不能因任何改动破坏这一点；⑥ **scope 漂移是 PR5 最大风险**——backlog 里 SRC-1/3/4/5/9 都诱人但全部留后续。

---

关键点：① PR5 是功能真正上线的 PR，冒烟清单 8 项是合 PR 的硬门槛；② 4 条 nit 全 frontend-only，零 spec 偏移、零后端改动；③ rollback 通道（flag=false → 字节级回到 PR4 末态）必须保留验证；④ PR4 dev_log 在 PR5 动手前补上，避免再次延期；⑤ scope 漂移是最大风险——SRC-1/3/4/5/9 坚决不顺手做。
