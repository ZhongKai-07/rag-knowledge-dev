# PR2 Over-Retrieve + Rerank — Execution Handoff Prompt

> **用途**：在新 Claude Code 会话中启动 PR2 实施。将下方 `---` 之间的整段 Markdown 复制粘贴到新窗口作为首条 user 消息，即可把当前会话的全部决策 + 环境 gotcha 传递过去，新会话无需回翻任何历史。
>
> **Plan 文件（权威）**：[`docs/superpowers/plans/2026-04-22-over-retrieve-rerank-pr2.md`](./2026-04-22-over-retrieve-rerank-pr2.md)
>
> **前序上下文摘要**：PR1（`ba7e8c4`）已合入 main，通过移除 `BaiLianRerankClient` 的候选数短路，让百炼 rerank API 真正被调用。验证数据：KYC 相关问题 maxScore 从 `0.5 → 0.8119`；"地球到太阳距离"无关问题 `0.5 → 0.2187`。PR2 在此基础上拆分召回数与 rerank 数，实现标准 RAG over-retrieve + rerank 漏斗。

---

你接手一个已经进入施工阶段的 RAG 项目 PR2 实施任务。前序会话已经完成全部设计、评审、修正，你的职责是**按现成 plan 执行**，不要重走设计。

## 项目

E:\AIProject\ragent — Java 17 / Spring Boot 3.5.7 / Maven 多模块（bootstrap + framework + infra-ai + mcp-server）/ OpenSearch 2.18 / PostgreSQL / Redisson / RocketMQ 5.x。
- CLAUDE.md 会自动加载，按其指示阅读 `docs/dev/README.md` + `docs/dev/gotchas.md`
- bootstrap 启动：9090 端口，context path `/api/ragent`
- 语言：文档/注释中文，标识符英文

## 当前状态

`main` 最新 commit `521168d`（PR1 的 dev_log 已合入）。PR1 刚完成：修了 rerank 短路让百炼 API 真正被调用，数据验证：KYC 相关问题 maxScore 从 0.5 → 0.8119，地球太阳无关问题 0.5 → 0.2187。`application.yaml` 里 `rag.sources.min-top-score=0.3`。

PR1 遗留的可观测性日志保留在生产代码里（`RoutingRerankService` / `BaiLianRerankClient` / `NoopRerankClient` / `RAGChatServiceImpl` 的 `[sources-gate]`），不要在 PR2 里清掉。

**独立并行的前端分支**：`feature/violet-aurora-design`（Violet Aurora UI 重构，P0–P7）不在 PR2 范围，不要动。

## 你的任务：执行 PR2

**Plan 文件**（权威，唯一真相源）：
`docs/superpowers/plans/2026-04-22-over-retrieve-rerank-pr2.md`

**目标**：把检索阶段的单一 `DEFAULT_TOP_K=10` 拆成 `recallTopK=30` + `rerankTopK=10`，实现标准 RAG 的 over-retrieve + rerank 漏斗。9 个 task，14 个文件。

**执行方式**：`superpowers:subagent-driven-development`（一个 task 一个 subagent，task 之间做 two-stage review）。**不要**在主会话里直接改代码。

## 已锁定的设计决策（不要重新讨论）

1. 新建 `RagRetrievalProperties`（`@PostConstruct validate()`）+ `rag.retrieval.recall-top-k=30 / rerank-top-k=10`
2. `SearchContext` 放弃 Lombok `@Builder`，**手写 builder**（约 50 行），validate 在 build() 里 —— 避免 `metadata$value` 等 Lombok 内部字段耦合
3. `RetrievalEngine` 引入 `RetrievalPlan(recallTopK, rerankTopK)` record；per-sub-question override 规则：`effectiveRerankTopK = node.topK ?? globalRerankTopK`，`effectiveRecallTopK = max(globalRecallTopK, effectiveRerankTopK)`
4. `IntentNode.topK` 语义保持"最终保留数"不变（不破已有节点配置 + 前端文案）
5. 两个 channel 丢弃旧 `topKMultiplier` 放大逻辑，每个 channel 内部 fan-out 后按上游 score 降序 sort + cap 到 `recallTopK`
6. `RerankPostProcessor`：`sort(score desc) → limit(recallTopK) → rerank(rerankTopK)`，排序是必需的，不是可选
7. `BaiLianRerankClient`：`effectiveTopN = min(topN, candidates.size())` 防御小 KB
8. `EvaluationCollector.topK` 不改 schema，改为 `evalCollector.setTopK(distinctChunks.size())`，记录真实喂给 LLM 的数量
9. `SearchChannelProperties.topKMultiplier` 字段打 `@Deprecated(forRemoval=true)`，实际删除留到后续清理 PR

## 前序审查捕获的四个 finding（已在 plan 中修复，确认一下）

1. Task 9 必须把 `RAGChatServiceImplSourcesTest.java` 的 10 个 `retrieve(any(), anyInt(), any(), any())` → `retrieve(any(), any(), any())` 一并迁移（已写进 Step 9.2）
2. `evalCollector.setTopK` 用 `distinctChunks.size()` 不用 `getRerankTopK()`（已写进 Step 8.3）
3. SearchContext 手写 builder（已写进 Step 2.3）
4. 验收日志里 `minTopScore=<CURRENT_THRESHOLD>`，不写死数字（已写进 Step 9.9）

## 开工前先拍板两件事（问用户）

1. **分支**：建议开 `feature/pr2-over-retrieve` 独立工作区再动（前面一次在 main 直接改出过事故）。请用户确认。
2. **验证当前工作区干净**：跑 `git status`，如果 `.claude/settings.json` 有本地修改，`git stash` 起来再开工。

## 环境 gotcha（会坑到你）

- **Maven 多模块**：改 infra-ai 后必须 `mvn -pl infra-ai install -DskipTests`，否则 bootstrap 加载的是 `~/.m2` 里的旧 jar，代码看着改了但运行时没生效。PR1 就被这个坑过一次。
- **Windows shell**：`bash`，不是 PowerShell。用 Unix 路径和 `/dev/null`，不是 `NUL`。
- **启动命令**：`$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run` —— 实际是 PowerShell 语法但会通过，NO_PROXY 必须设否则 RustFS/OpenSearch 被代理拦
- **Pre-existing test 失败基线**（不是 PR2 的回归，可忽略）：`MilvusCollectionTests`、`InvoiceIndexDocumentTests`、`PgVectorStoreServiceTest.testChineseCharacterInsertion`、`IntentTreeServiceTests.initFromFactory`、`VectorTreeIntentClassifierTests` —— 这些在 `main` 上就是红的，见根 CLAUDE.md
- **Lombok 风险**：plan Task 2 明确不用 `@Builder` 而手写 builder，如果 subagent 试图走回 Lombok 覆盖 `build()`，拒绝它的方案，让它按 plan 写
- **MockWebServer 依赖**：Task 7 的测试需要 `com.squareup.okhttp3:mockwebserver` 在 infra-ai 测试 scope。执行前先 `mvn -pl infra-ai dependency:tree | grep mockwebserver` 确认；若缺，subagent 改 pom.xml 加入 test scope

## 执行节奏

1. 第一步：确认 plan 在 `docs/superpowers/plans/2026-04-22-over-retrieve-rerank-pr2.md` 存在且完整（应该有 Step 1.1 ~ Step 9.9）
2. 第二步：调用 `superpowers:subagent-driven-development` 技能，按其规定的 two-stage review 流程执行
3. 每个 task 完成后 review，用户拍板 continue 再进下一个
4. 所有 9 个 task 完成后，Task 9.8 做一次性 commit（plan 里有完整 commit message 模板），Task 9.9 做人工真实回归

## 成功判据

最后一步 Step 9.9 里，后端 log 出现：

```
[bailian-rerank] CALLING API: dedup=30, topN=10
```

`dedup=30`（PR1 时是 `dedup=10`）就是 PR2 生效的决定性证据。

## 开始

先阅读 `docs/superpowers/plans/2026-04-22-over-retrieve-rerank-pr2.md`。然后问用户：
- 是否开 feature 分支 + worktree？
- 工作区是否干净可以开工？

两个都 yes 就调 `superpowers:subagent-driven-development` 开始 Task 1。
