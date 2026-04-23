# `metadata.kb_id` 是做什么用的

> 触发：PR2 Step 9.9 回归时发现老 KB 的 30 chunks 全被 `AuthzPostProcessor` fail-close 掉，看到 WARN `kbId is null/blank (non-OpenSearch backend?)` 才想起这个字段的定位。

## 一句话

每个 chunk 上的"这条 chunk 属于哪个知识库"的**权威标签**，授权层的锚点。

## 三个下游消费者

### 1. `AuthzPostProcessor` 的三重过滤都依赖它

```java
// Rule 1: kbId 缺失 → fail-closed（防 Milvus/Pg 后端穿透）
if (kbId == null || kbId.isBlank()) return false;

// Rule 2: RBAC 白名单 —— 用户角色能读哪些 KB？
if (!scope.allows(kbId)) return false;

// Rule 3: security_level 天花板 —— 这条 chunk 的密级 ≤ 用户在这个 KB 的最高可读密级
Integer ceiling = context.getKbSecurityLevels().get(kbId);
if (level > ceiling) return false;
```

场景：用户 A 对 KB-X 有 READ 权限、对 KB-Y 没权限。如果检索回来的 chunks 混了 KB-Y 的，这一层兜底 fail-close —— 是**纵深防御**（即使 retriever 侧 metadataFilter 漏了，AuthzPostProcessor 兜底）。

### 2. 回答来源卡片（Source Cards）

`SourceCardBuilder` 用 `chunk.kbId` 查 KB 名称挂到卡片上，前端"文档 A — 来自 KB-财务"。

### 3. 评测 / 观察 / 审计

`RagTraceContext` 用 `kbId` 记录每次问答命中了哪些 KB；`EvaluationCollector` 把 kbId 落 `t_rag_evaluation_record`，后续"KB-财务的 recall@10" 分析。

## 为什么不从 collection name 推导

OpenSearch "一 KB 一 index"，collection_name 和 kbId 是 1:1，理论上可以反推。但 design 把 `kb_id` 显式写进每个 doc 的 metadata，有三个原因：

1. **AuthzPostProcessor 是 vector-backend-agnostic 的**：Milvus/pgvector 多 KB 混存在一个 collection，只能靠 metadata 区分。OpenSearch 跟着统一接口走。
2. **跨 KB 查询**（多通道 `VectorGlobalSearchChannel`）一次打多个 index，返回混合结果时每条自带 `kb_id` 最省事。
3. **`collection_name` ≠ `kbId`**：前者是派生的物理存储名（如 `kb-<snowflake>-vectors`），后者是 `t_knowledge_base.id` 业务主键，权限系统只认后者。

## 数据侧 gotcha

- **写入**：`OpenSearchVectorStoreService.buildDocument:238` — `metadata.put(KB_ID, kbId != null ? kbId : "")`
- **读取**：`OpenSearchRetrieverService.toRetrievedChunk:271-273` — 从 `_source.metadata.kb_id` 回填，null/blank 时 chunk.kbId 保留 null
- **这是 PR-A (`2ca6bc14 feat(rbac): inject kb_id/security_level into vector write path`) 加的**。之前索引的 doc 没有 `metadata.kb_id`，会被 AuthzPostProcessor Rule 1 全部 drop。
- **修复**：重新上传文档触发 re-index，或建新 KB（今天验证过新 KB 工作正常）。

## 相关文件

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/AuthzPostProcessor.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java`
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunk.java`（kbId 字段 javadoc）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorMetadataFields.java`（常量）
