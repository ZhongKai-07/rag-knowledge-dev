# Follow-up 2：架构审查 & 技术债

来源：2026-04-14 对话会话（RocketMQ 分析 / Query Rewriting & Intent 剖析 / 架构评审）。
按真实优先级排序，不必一次性全做。

---

## 🟠 值得做：MQ 迁移

### MQ-1. RocketMQ → Spring Events（复杂度低，收益明显）

**背景**：当前 RocketMQ 在此项目中解决三个问题，但其中两个用 Spring 原生方案即可覆盖，整体引入成本超过收益（单机 Docker Compose 部署，分布式可靠性优势发挥不出来）。

**改动范围（约半天到一天）**：

| 操作 | 文件 |
|------|------|
| 删除 | `RocketMQProducerAdapter`, `DelegatingTransactionListener`, `TransactionChecker`, `RocketMQAutoConfiguration`, `KnowledgeDocumentChunkTransactionChecker` |
| 新建 | `SpringEventProducerAdapter`（实现 `MessageQueueProducer`），`AsyncConfiguration`（`@EnableAsync` + 线程池），`ChunkRecoveryJob`（补偿任务） |
| 修改 | `MessageQueueProducer`（去掉 `sendInTransaction`），`KnowledgeDocumentServiceImpl`（提取 `@Transactional` 方法），3 个 Consumer（换 `@TransactionalEventListener` + `@Async`） |

**核心改法**（`sendInTransaction` 解构）：

```java
// 之前：本地事务逻辑藏在 lambda 里
messageQueueProducer.sendInTransaction(topic, docId, "文档分块", event, arg -> {
    documentMapper.update(...status=RUNNING...);
    scheduleService.upsertSchedule(documentDO);
});

// 之后：显式 @Transactional 方法 + 事务内发 Spring Event
@Transactional
private void startChunkInTransaction(String docId, KnowledgeDocumentChunkEvent event) {
    int updated = documentMapper.update(...status=RUNNING...);
    if (updated == 0) throw new ClientException("文档分块操作正在进行中");
    scheduleService.upsertSchedule(documentMapper.selectById(docId));
    applicationEventPublisher.publishEvent(event);  // AFTER_COMMIT 才触发 Listener
}

// Consumer 改成：
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onChunkEvent(KnowledgeDocumentChunkEvent event) {
    documentService.executeChunk(event.getDocId());
}
```

**补偿任务**（替代 Broker 回查，~30 行）：

```java
@Scheduled(fixedDelay = 5 * 60 * 1000)
public void recoverStuckChunks() {
    // 查 status=RUNNING 且 update_time 超过阈值时间的文档
    // 重新发布 KnowledgeDocumentChunkEvent
    // 数据来源：t_knowledge_document_chunk_log.start_time 已有
}
```

**收益**：去掉 RocketMQ 外部依赖，本地开发只需 PostgreSQL + Redis，docker-compose 减负，`@IdempotentConsume`（当前未实际使用）和 `MessageWrapper` 可一并清理。

---

## 🟡 架构问题

### ARCH-6. `sendInTransaction` 把 RocketMQ 概念泄漏进接口

**位置**：`framework/.../mq/producer/MessageQueueProducer.java`
**问题**："本地事务"是 RocketMQ 半消息机制的专有概念，不应出现在通用接口签名里。
**修复**：迁移到 Spring Events 时自然消除；若继续用 RocketMQ，把 `localTransaction` 拆出去，接口只暴露 `send`。

### ARCH-7. `TransactionSynchronizationManager` 直接出现在业务 Service

**位置**：`KnowledgeDocumentServiceImpl.java:559-579`（security_level 刷新发送处）
**问题**：Spring 内部基础设施 API 泄漏进业务层，本应用 `@TransactionalEventListener` 处理。
**修复**：把手写的 `registerSynchronization` 替换为 Spring Event + `@TransactionalEventListener(AFTER_COMMIT)`。

### ARCH-8. `DefaultIntentClassifier` 每次请求重建内存树

**位置**：`DefaultIntentClassifier.java:69-96`，`loadIntentTreeData()`
**问题**：每次调用 `classifyTargets()` 都走：Redis 反序列化 → `flatten()` → `Collectors.toMap()` 建索引 → 返回临时对象。意图树 TTL 7 天，变更极低频，这个开销无意义。
**修复**：在 Bean 内持有 `volatile IntentTreeData hotCache`，加一个 `refreshCache()` 方法，Redis 版本变更或意图节点 CRUD 后主动调用；正常请求直接读内存热缓存。

```java
// 现在（每次请求）
private IntentTreeData loadIntentTreeData() {
    List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache(); // Redis 反序列化
    return new IntentTreeData(flatten(roots), ...);  // 临时对象
}

// 建议（内存热缓存）
private volatile IntentTreeData hotCache;

@PostConstruct
public void initCache() { hotCache = buildFromRedisOrDb(); }

public void refreshCache() { hotCache = buildFromRedisOrDb(); } // 意图节点变更时调用

private IntentTreeData loadIntentTreeData() { return hotCache; }
```

### ARCH-9. `MessageWrapper<T>` 是 MQ 专属类型放进 framework

**位置**：`framework/.../mq/MessageWrapper.java`
**问题**：仅用于 RocketMQ 消息附带业务 key，换 Spring Events 后变死代码，不属于通用基础设施。
**修复**：迁移 MQ 后随 RocketMQ 相关代码一并删除。

---

## 🟢 小清理

### CLEAN-4. `t_user.role` 遗留字段

**位置**：`schema_pg.sql`，`t_user` 表
**问题**：`role VARCHAR(32)` 是旧版角色字段，真实 RBAC 已迁移到 `t_user_role → t_role`，该字段是死数据。
**修复**：确认无代码读取后，`ALTER TABLE t_user DROP COLUMN role`，同步更新 `schema_pg.sql` 和 `full_schema_pg.sql`。

### CLEAN-5. `IntentTreeCacheManager` 返回值不一致

**位置**：`IntentTreeCacheManager.java:57-73`
**问题**：`getIntentTreeFromCache()` 缓存不存在时返回 `null`，但 `loadIntentTreeFromDB()` 空时返回 `List.of()`，调用方需要两处 null 判断（`CollUtil.isEmpty` 的行为不一致）。
**修复**：统一返回 `List.of()`（不返回 null），调用方只需一次 `isEmpty` 检查。

---

## 📋 参考：本次对话梳理的系统知识

以下为本次对话梳理的背景知识，不是 TODO，但可作为后续开发参考。

**RocketMQ 使用场景**（3 个 topic）：
- `knowledge-document-chunk_topic`：文档分块异步化（事务消息）
- `knowledge-document-security-level_topic`：security_level 变更刷新向量 metadata
- `message-feedback_topic`：点赞/点踩异步持久化

**Query Rewriting 链路**：
`术语归一化（DB规则）→ LLM 改写+拆分（temperature=0.1）→ 兜底规则拆分`
Prompt 在 `bootstrap/src/main/resources/prompt/user-question-rewrite.st`

**Intent Classification 链路**：
`子问题并行分类（intentClassifyExecutor）→ 叶子节点全量打分（LLM）→ score≥0.35 过滤 → 最多 3 个意图 → 歧义引导检测`
Prompt 在 `bootstrap/src/main/resources/prompt/intent-classifier.st`
意图树缓存 Key：`ragent:intent:tree`（Redis，TTL 7 天），CRUD 后需调 `clearIntentTreeCache()`。

**数据库表分组**（26 张）：
- 用户权限：`sys_dept / t_user / t_role / t_user_role / t_role_kb_relation`
- 会话消息：`t_conversation / t_conversation_summary / t_message / t_message_feedback / t_sample_question`
- 知识库管理：`t_knowledge_base / t_knowledge_document / t_knowledge_chunk / t_knowledge_document_chunk_log / t_knowledge_document_schedule / t_knowledge_document_schedule_exec`
- RAG 追踪：`t_intent_node / t_query_term_mapping / t_rag_trace_run / t_rag_trace_node`
- RAG 评测：`t_rag_evaluation_record`
- Ingestion 流水线：`t_ingestion_pipeline / t_ingestion_pipeline_node / t_ingestion_task / t_ingestion_task_node`
- 向量存储：`t_knowledge_vector`（pgvector 模式专用）
