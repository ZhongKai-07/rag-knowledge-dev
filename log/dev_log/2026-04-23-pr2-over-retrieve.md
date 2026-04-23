# 2026-04-23 | Over-retrieve + rerank 漏斗归位（PR2）

Merge: `4ea8b3f0` (PR #18)
分支提交：`00c2bde6` → `906214c4` → `ad467a08` → `de5399df`

## 背景

PR1（`ba7e8c4`）只拆了 `BaiLianRerankClient` 的候选数短路，让 cross-encoder 真正跑起来。
但 `DEFAULT_TOP_K=10` 仍然既是**召回数**又是**最终保留数**——rerank 拿到的永远是
bi-encoder 挑好的那 10 条，只能重排不能换人。标准 RAG 的 over-retrieve + rerank
漏斗在结构上没归位。

## 修复（PR2 范围）

拆两个独立参数：

- `rag.retrieval.recall-top-k=30`（bi-encoder 候选池）
- `rag.retrieval.rerank-top-k=10`（cross-encoder 最终保留）

再把这个漏斗沿检索链路贯穿下去：

### Configuration & plumbing
- **新 `RagRetrievalProperties`**：`@PostConstruct` 校验 `recall ≥ rerank > 0`，启动期 fail-stop。
- **`SearchContext` 手写 builder**（丢掉 Lombok `@Builder`）：`recallTopK` / `rerankTopK` 双字段，`build()` 内做 IAE 兜底。放弃 Lombok 是因为需要在 build() 做校验，继承覆盖 Lombok 生成的 build() 依赖内部字段名（`metadata$value`）脆弱。
- **`RetrievalEngine.RetrievalPlan(int, int)` record**：每个子问题独立算一份 plan，override 规则 `effectiveRerank = IntentNode.topK ?? globalRerank`、`effectiveRecall = max(globalRecall, effectiveRerank)`。`IntentNode.topK` 语义保持"最终保留数"不变，已有节点配置和前端文案都不改。

### Channel-level
- **两 channel 都丢弃 `topKMultiplier` 放大**，每个目标直接 `recallTopK` 召回，channel 内部 `sort(score desc, nulls last) → cap(recallTopK)` 显式一步。对称、可预测、日志可见（`fan-out N -> cap M`）。
- **`RerankPostProcessor`**：跨 channel dedup 后**先 sort 再 cap(recallTopK)**，最后 `rerank(rerankTopK)`。排序是必须的——dedup 顺序不保证 score desc，裸 `.limit` 会砍错。

### Infra & eval
- **`BaiLianRerankClient`**：`effectiveTopN = min(topN, candidates.size())` 防御 clamp。小 KB（候选 < rerankTopK）时不再依赖百炼 API 未验证的 fallback。
- **`RAGChatServiceImpl`**：`evalCollector.setTopK(distinctChunks.size())`——评测记录的是真实喂给 LLM 的 chunk 数，不是 config 默认值。历史代码写 10 实际 8 或 13 的"谎言"在评测里抹平。

### Cleanup
- 死 yaml `rag.search.channels.*.top-k-multiplier` 清掉；Java 字段 `@Deprecated(forRemoval=true)`。
- 死常量 `SEARCH_TOP_K_MULTIPLIER` / `RERANK_LIMIT_MULTIPLIER` / `MIN_SEARCH_TOP_K` / `DEFAULT_TOP_K` 全删（0 调用）。
- Drive-by：`RAGChatServiceImpl:189` 修乱码 `"未检索到���问题相关的文档��容。" → "未检索到与问题相关的文档内容。"`。
- 新增 `log/notes/` 笔记目录 + CLAUDE.md 三处更新（根 / bootstrap / infra-ai）。

## 验证数据

Step 9.9 真实后端回归（新建 KB，新鲜索引有 `metadata.kb_id`）：

| Query | 改前（PR1） | 改后（PR2） | 解读 |
|---|---|---|---|
| KYC（相关） | `maxScore=0.8119`、`dedup=10, topN=10` | `maxScore=0.8541`、`dedup=30, topN=10` | 漏斗归位 |
| 地球到太阳（无关） | `maxScore=0.2187` | `maxScore=0.2705`，被 `min-top-score=0.3` gate 拦 | gate 工作正常 |

决定性证据是 `[bailian-rerank] CALLING API: dedup=30, topN=10` —— PR1 时是 `dedup=10, topN=10`，
cross-encoder 没有挑选空间；PR2 起有 20 条备胎可换。

关键日志：
```
Rerank 入口：chunks=30, cappedToRecall=30, rerankTopK=10
[bailian-rerank] CALLING API: dedup=30, topN=10
[sources-gate] distinctChunks=10, maxScore=0.8541, minTopScore=0.3
```

**PR2 的价值不在单点 maxScore**（PR1 已救了主要 case，PR2 只再涨 +4%），
而是 rerank 从"只能重排"升级到"可以换人"，以及整条链路从隐式 trim 变成显式
sort+cap+可配置。详见 [`log/notes/2026-04-23-pr2-roi.md`](../notes/2026-04-23-pr2-roi.md)。

## 过程坑（session 级）

1. **Task 6 `RerankPostProcessorTest` test 跑不起来**：Maven `-Dtest=X` 仍编译整个 test source set；其他 test 文件用旧 `retrieve(...)` 4-arg 签名卡 main-compile。授权 bleed Task 8 Step 8.2 + Task 9.2 + 9.3(a) 进 Task 6，做了 11 个 callsite 机械迁移。
2. **Task 8 Step 8.1 YAGNI**：Plan 原本要注入 `RagRetrievalProperties` 到 `RAGChatServiceImpl`，但 Step 8.2 已 bleed + Step 8.3 明确用 `distinctChunks.size()` 不用配置值 → 该注入无消费者，跳过。
3. **Step 9.9 回归踩到 AuthzPostProcessor 全 drop**：老 KB（pre `2ca6bc14` PR-A）索引时没写 `metadata.kb_id`，`AuthzPostProcessor` Rule 1 fail-close。非 PR2 回归，pre-existing 数据侧问题。建新 KB 后全正常。已补 `gotchas.md` §4 "OpenSearch mapping upgrades need explicit rebuild + reindex" 条目。

## Follow-up（未做，PR body 里已列）

- 重索引老 KB 清 `metadata.kb_id` 缺失
- 物理删 `SearchChannelProperties.topKMultiplier` 字段（等部署确认无旧 yaml 残留）
- `IntentParallelRetriever.createRetrievalTask(int ignoredTopK)` template-method 味道
- `EvaluationCollector.topK` 字段名 vs 当前语义的命名债（schema migration 成本不值）
- `@Data` 生成的 public setter 能绕过 `SearchContext` builder 校验
- RAGAS recall@10 / precision@10 对比评测
- `IntentNode.topK` override 路径真实回归（需要配了 override 的节点）

## 本次改动文件

19 files 合入 PR，主干：

```
bootstrap/src/main/java/.../rag/config/RagRetrievalProperties.java       +new
bootstrap/src/main/java/.../rag/core/retrieve/RetrievalEngine.java       +58 -27
bootstrap/src/main/java/.../rag/core/retrieve/MultiChannelRetrievalEngine.java  +21 -5
bootstrap/src/main/java/.../rag/core/retrieve/channel/SearchContext.java +108 -5
bootstrap/src/main/java/.../rag/core/retrieve/channel/IntentDirectedSearchChannel.java  +27 -39
bootstrap/src/main/java/.../rag/core/retrieve/channel/VectorGlobalSearchChannel.java  +13 -5
bootstrap/src/main/java/.../rag/core/retrieve/channel/strategy/IntentParallelRetriever.java  +~ -13
bootstrap/src/main/java/.../rag/core/retrieve/postprocessor/RerankPostProcessor.java  +18 -2
bootstrap/src/main/java/.../rag/service/impl/RAGChatServiceImpl.java     +5 -2
bootstrap/src/main/java/.../rag/dto/EvaluationCollector.java             +10
bootstrap/src/main/java/.../rag/constant/RAGConstant.java                -4
bootstrap/src/main/java/.../rag/config/SearchChannelProperties.java      +18
bootstrap/src/main/resources/application.yaml                            +3 -2
infra-ai/src/main/java/.../infra/rerank/BaiLianRerankClient.java         +7 -4
infra-ai/pom.xml                                                         +21
```

测试：

```
bootstrap/src/test/java/.../rag/config/RagRetrievalPropertiesTest.java           +new (4 tests)
bootstrap/src/test/java/.../rag/core/retrieve/channel/SearchContextBuilderTest.java  +new (4 tests)
bootstrap/src/test/java/.../rag/core/retrieve/postprocessor/RerankPostProcessorTest.java  +new (3 tests)
infra-ai/src/test/java/.../infra/rerank/BaiLianRerankClientSmallKbTest.java      +new (1 test, MockWebServer)
bootstrap/src/test/java/.../RAGChatServiceImplSourcesTest.java                   migrated (10 sites)
bootstrap/src/test/java/.../MultiChannelRetrievalEnginePostProcessorChainTest.java  migrated
bootstrap/src/test/java/.../DeduplicationPostProcessorTest.java                  migrated
```

文档：

```
docs/dev/gotchas.md                             +1 (§4 enhance)
CLAUDE.md / bootstrap/CLAUDE.md / infra-ai/CLAUDE.md  +3 tables enriched
log/notes/README.md + 2 初稿笔记                +new
```

合计 +606 / -116，4 个 commit 在 feature 分支，PR #18 merge commit 进 main。

## 执行节奏回看

9 个 task + 1 post-cleanup，全走 `superpowers:subagent-driven-development`（per-task
spec + quality 两阶段 review）。plan 有两处盲点（Step 6.4 的 test 编译链、Step 8.1
的孤儿注入），都是执行期识别 + 用户授权 bleed/skip 后绕过的。回头看 plan 阶段
如果多想一步"测试在 partial merge 状态下跑得起来吗"能省两个 bleed round-trip。
