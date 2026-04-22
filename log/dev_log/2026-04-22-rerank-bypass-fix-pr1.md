# 2026-04-22 | Rerank 短路旁路修复（PR1）

Commit: `ba7e8c4`

## 症状

一次问答后，相关问题（KYC）和完全无关的问题（地球到太阳的距离）在
`[sources-gate]` 日志里打出完全相同的 `maxScore=0.5`，sources / suggestions
两路对无关问题一样放行。

## 根因

两层复合问题：

1. `BaiLianRerankClient.rerank` 在 `dedup.size() <= topN` 时直接 return，**不调用百炼 rerank API**。
   `DEFAULT_TOP_K=10`，检索恒回 10 个候选，每次都命中这个短路分支。短路本意是
   "候选数 ≤ 目标数就不需要截断"，但它同时把 rerank 的**另一个职责——为每个
   chunk 打绝对 `relevance_score`**——一并省掉了。
2. 上游 OpenSearch hybrid search（KNN + BM25 + min-max 归一化 + arithmetic mean
   融合）返回的 `_score` 是**相对分**，top-1 归一化后恒 ≈ 1，单通道命中 + 另一
   通道空时 arithmetic mean ≈ 0.5。于是所有 query 的 maxScore 稳定落在 0.5 附近，
   完全不反映相关度。叠加 `OpenSearchRetrieverService:261` 的 `Math.min(score, 1.0f)`
   截断，进一步磨平了差异。

两个问题合起来：**rerank 没打真实分 + hybrid 分数本身无绝对语义**，导致 relevance
gate 从语义上失效。

## 修复（PR1 范围）

只修问题 1 —— 去掉 rerank 的候选数短路：

- `BaiLianRerankClient`：保留 `topN<=0` 早退，移除 `dedup.size() <= topN` 分支。
  任何非空候选都会调用百炼 rerank API，拿到每个 chunk 的 `relevance_score`（覆盖
  掉上游 hybrid 的假分）。
- `application.yaml`：`rag.sources.min-top-score` 从 `0.1` 调到 `0.3`。阈值原本
  是按 hybrid 假分推出的，rerank 产生真实 cross-encoder 分数后阈值有了绝对语义，
  可以调严。
- `RoutingRerankService` / `BaiLianRerankClient` / `NoopRerankClient` 保留 INFO
  级诊断日志（`[rerank-routing]` / `[bailian-rerank]` / `[noop-rerank]`），任何
  未来的 rerank 静默失败（短路回归、误路由 NOOP、API 错误降级）都会在一行日志里暴露。
- `docs/dev/gotchas.md`：第 4 组（向量存储）新增一条 rerank 短路条目。

## 验证数据

同一 KB、同一会话，改前 vs 改后：

| Query | 改前 `maxScore` | 改后 `maxScore` | 解读 |
|---|---|---|---|
| KYC（相关） | 0.5 | **0.8119** | cross-encoder 打出高分 |
| 地球到太阳（无关） | 0.5 | **0.2187** | cross-encoder 打出低分 |

从"完全无区分"到"4× 差距"，证明 rerank 确实在跑，分数有绝对语义。相关日志：

```
[rerank-routing] selected: provider=bailian, model=qwen3-rerank, priority=1
[bailian-rerank] CALLING API: dedup=10, topN=10
[sources-gate] distinctChunks=10, maxScore=0.8118707537651062, minTopScore=0.1
```

（阈值随 commit 一并改到 0.3，截图里还是采样时的 0.1。）

顺带排除的嫌疑：

- 配置里 `rerank-noop` 的 `priority=100` 高于 `qwen3-rerank` 的 `priority=1`，看着
  像会误路由；但 `default-model: qwen3-rerank` 锁定了默认选择，实际路由是 bailian，
  NOOP 只是熔断兜底，不影响。

## Follow-up（PR2，本次未做）

问题 2（hybrid 分数无绝对语义）本身在 PR1 不再重要——rerank 接管了 gate 用的分数
路径，上游 hybrid 分数只剩"召回排序"语义，不再直接喂给 gate。但架构上仍有债：

- `DEFAULT_TOP_K=10` 同时表示"召回数"和"最终保留数"，候选 == 目标就没有给
  rerank 留出挑选空间，等于 cross-encoder 只能打分不能降权。
- 标准 RAG 做法是 over-retrieve + rerank：召回 30，rerank 出 10。PR1 结构下，
  rerank 只能确认 10 个都要，无法把 bi-encoder 误排的噪声 chunk 降权挤出。

PR2 范围：拆分 `retrievalTopK` 与 `rerankTopK`，增加 `rag.retrieval.recall-top-k=30 / rerank-top-k=10`
配置，把 over-retrieve 变成默认形态。相关阈值（HIGH/MID/LOW 三层分级）在 PR2
之后再一次性定档。

## 本次改动文件

```
bootstrap/src/main/java/.../rag/service/impl/RAGChatServiceImpl.java     +8
bootstrap/src/main/resources/application.yaml                            +1 -1
docs/dev/gotchas.md                                                      +1
infra-ai/src/main/java/.../infra/rerank/BaiLianRerankClient.java         +5 -1
infra-ai/src/main/java/.../infra/rerank/NoopRerankClient.java            +4
infra-ai/src/main/java/.../infra/rerank/RoutingRerankService.java        +10 -1
```

29 行变更，6 个文件。
