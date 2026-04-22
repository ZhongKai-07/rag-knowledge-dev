# 2026-04-22 | Authz/Dedup 修复 + relevance gate

## 核心改动

- 修复 `DeduplicationPostProcessor`：只在当前工作集 `chunks` 内去重，不再从原始 `results` 恢复已被前序处理器删除的 chunk。
- winner 规则固定为：通道优先级更高优先，其次 `score` 更高，再其次保留更早出现的 chunk。
- `RagSourcesProperties` 新增 `minTopScore`，默认值 `0.55`；`application.yaml` 同步加 `rag.sources.min-top-score: 0.55`。
- `RAGChatServiceImpl` 新增 `hasRelevantKbEvidence(distinctChunks)`，统一控制 `sources` 是否发射和 `SuggestionContext.shouldGenerate`。
- 保留既有 `!hasMcp` 语义，只把原先的 `!topChunks.isEmpty()` 替换为 `hasRelevantKbEvidence`。

## 阈值说明

- 当前阈值 `0.55` 是 provisional 默认值。
- 经验锚点来自本轮 smoke：
  - 强相关命中约为 `1.00 / 0.73`
  - off-topic 命中约为 `0.50 / 0.14`
- `0.55` 落在已观测到的天然分界之间，能挡住当前误卡片场景，同时保留正向业务问题。
- 上线后建议继续观察 1-2 周真实 query 分布，再决定是否把阈值调到 `0.60` 或更低。

## 测试

- 新增 `DeduplicationPostProcessorTest`
  - 空 `chunks` + 非空 `results` 时输出必须为空
  - 高优先级重复 chunk 已被过滤时，不得被 Dedup 恢复
  - 当前工作集里有重复时，winner 服从“通道优先级 > score > 先出现”
- 新增 `MultiChannelRetrievalEnginePostProcessorChainTest`
  - 前序处理器删除后，后续 Dedup 不得恢复 chunk
- 扩展 `RAGChatServiceImplSourcesTest`
  - `0.50`：不发 `sources`，`shouldGenerate=false`
  - `0.55`：闸门开启
  - `0.65`：闸门开启

## 运行态排查与观察判据

- 本地 `rag.vector.type=opensearch`，不是 Milvus/Pg 路径误切换。
- 受影响索引 `colleteraltest` 原先缺 `metadata.kb_id`；删除索引、重建 mapping、重新分块/向量化后，抽样 `_source.metadata.kb_id` 已恢复为 `100%` 有值。
- `AuthzPostProcessor: kbId is null/blank` 的告警是 fail-closed 的结果，不是单独的修复目标；当索引 metadata 修复后它会自然消失。
- 独立且必须长期锁住的不变式是：
  - `Dedup input=N output=M` 不得再出现 `M > N`
  - `MultiChannelRetrievalEnginePostProcessorChainTest` 负责锁住“任何后处理器删除的 chunk，后续链都不能恢复”

## 文档

- `docs/dev/gotchas.md` 新增 OpenSearch mapping 升级必须显式重建索引的说明。
- `docs/dev/followup/backlog.md` 顶部标记 `SEC-1` / `SRC-10` 已在 `main` 解决。
- 直推 `main` 落地，commit `1e82b3a4`，未开 PR。
