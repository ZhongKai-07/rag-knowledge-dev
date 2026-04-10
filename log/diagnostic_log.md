# Business Logic Diagnostic Log

> Scan Date: 2026-04-09
> Scope: RAG Chat / Document Ingestion / Knowledge Management & RBAC
> Focus: Business logic correctness, data consistency, edge cases (NOT security)

---

## Summary

| Priority | Count | Core Theme |
|----------|-------|------------|
| P0       | 3     | Vector-DB transaction inconsistency, streaming data loss, concurrent chunking |
| P1       | 4     | Silent degradation, cascade cleanup, MQ idempotency, stuck status |
| P2       | 5     | Message ordering, chunk-vector drift, RBAC cache lag, empty doc handling |
| P3       | 5     | Semantic loss on rewrite failure, multi-intent chunk sharing, S3 leak, etc. |

---

## I. RAG Chat Chain

### [P0] #1 - Streaming interruption loses assistant message

- **File**: `bootstrap/.../rag/service/handler/StreamChatEventHandler.java:161-180`
- **Symptom**: User sees partial answer via SSE, but it is never persisted to conversation memory
- **Root Cause**: Assistant message is only saved inside `onComplete()`. If LLM streaming fails mid-way, `onError()` is invoked instead, which does NOT save the partial answer.
- **Impact**: Next question loads history with user message but no assistant reply. LLM context breaks, subsequent answer quality degrades significantly.
- **Suggested Fix**: Save partial answer in `onError()` path (mark as incomplete), or use write-ahead pattern where message record is created at stream start and updated on completion.

---

### [P0] #2 - Concurrent questions in same conversation cause message disorder

- **File**: `bootstrap/.../rag/core/memory/JdbcConversationMemoryStore.java:54-71`
- **Symptom**: Message history becomes Q1, Q2, A2, A1 instead of Q1, A1, Q2, A2
- **Root Cause**: No sequence number or optimistic lock on `t_conversation_message`. Message ordering relies on DB insertion order, which is not guaranteed under concurrent writes to the same `conversationId`.
- **Impact**: LLM receives disordered context, answer quality degrades.
- **Suggested Fix**: Add `seq_no` column with per-conversation atomic increment, or enforce single-active-stream-per-conversation via distributed lock.

---

### [P1] #3 - Intent classification LLM failure silently degrades to "no results"

- **File**: `bootstrap/.../rag/core/intent/DefaultIntentClassifier.java:203-206`
- **Symptom**: User sees "未检索到与问题相关的文档内容" when intent service is actually down
- **Root Cause**: LLM returning malformed JSON or exception is caught and returns `List.of()` with only a WARN log. Downstream treats empty intent list as normal — proceeds to retrieval with no intents, gets no results.
- **Impact**: User cannot distinguish "no matching docs" from "intent classification service failure". No retry prompt.
- **Suggested Fix**: Add a `ClassificationStatus` (SUCCESS/DEGRADED/FAILED) to intent result. Surface degraded state to user with retry suggestion. Consider circuit breaker for intent LLM.

---

### [P1] #4 - Empty retrieval: no docs vs. service failure — same response

- **File**: `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java:173-179`
- **Symptom**: Both "genuinely no matching documents" and "vector store connection failed" return the same message
- **Root Cause**: `RetrievalContext.isEmpty()` only checks if results are empty, no failure reason attached.
- **Impact**: Transient infrastructure failures (OpenSearch down, Milvus timeout) are presented as normal business results.
- **Suggested Fix**: Add `RetrievalFailureReason` enum to `RetrievalContext`. Distinguish EMPTY_RESULT vs. SERVICE_ERROR. Show different messages accordingly.

---

### [P2] #5 - Query rewrite failure loses multi-turn semantic context

- **File**: `bootstrap/.../rag/core/rewrite/MultiQuestionRewriteService.java:122-127`
- **Symptom**: Anaphoric references like "上次那个问题呢?" become meaningless after rewrite fallback
- **Root Cause**: Rewrite LLM failure falls back to `normalizedQuestion` (term mapping only), which cannot resolve pronouns or references. History was loaded BEFORE rewrite, so there's no secondary fallback path.
- **Impact**: Intent classification and retrieval run on meaningless text; answer is completely off-topic.
- **Suggested Fix**: On rewrite failure, fall back to original question (not normalized version). Or pre-resolve references using conversation history before entering rewrite.

---

### [P2] #6 - Multiple intents per sub-question share identical chunks

- **File**: `bootstrap/.../rag/core/retrieve/RetrievalEngine.java:218-224`
- **Symptom**: "人事制度" and "财务制度" intents both receive the same retrieval results (mostly about HR)
- **Root Cause**: Multi-intent retrieval does not support per-intent chunking. All retrieved chunks are assigned to every intent. Code comment acknowledges: "无法精确对应到某个意图节点".
- **Impact**: Prompt includes irrelevant chunks for some intents; LLM may confuse which intent the context belongs to.
- **Suggested Fix**: Split retrieval by intent — run separate vector queries with intent-specific filters. Or at minimum, tag chunks with their source intent for the prompt builder to organize.

---

### [P3] #7 - SYSTEM-only intent path doesn't validate prompt template

- **File**: `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java:210-228`
- **Symptom**: LLM generates unconstrained response when custom prompt template fails to load
- **Root Cause**: `streamSystemResponse()` uses prompt template without validating it loaded successfully. If template is null/empty, LLM answers without any system prompt.
- **Impact**: Occasional unconstrained responses that may violate business policies.
- **Suggested Fix**: Validate prompt template is non-blank before streaming. Fall back to a hardcoded safe default if missing.

---

### [P3] #8 - Intent guidance maxOptions has no bounds check

- **File**: `bootstrap/.../rag/core/guidance/IntentGuidanceService.java:230-236`
- **Symptom**: `IndexOutOfBoundsException` at runtime if `maxOptions` > available options
- **Root Cause**: `subList(0, maxOptions)` without bounds validation.
- **Impact**: Uncaught exception kills the guidance flow; user gets a 500 error instead of guidance.
- **Suggested Fix**: `Math.min(maxOptions, optionIds.size())` before subList call.

---

## II. Document Ingestion Chain

### [P0] #9 - Vector delete succeeds + DB rollback = permanent retrieval blackhole

- **File**: `bootstrap/.../knowledge/service/impl/KnowledgeDocumentServiceImpl.java:254-278`
- **Symptom**: Document exists in DB with chunks, but vector store has no vectors — RAG search permanently misses it
- **Root Cause**: `persistChunksAndVectorsAtomically()` wraps both DB and vector operations in a DB transaction, but vector store is NOT transactional. If `indexDocumentChunks()` fails after `deleteDocumentVectors()` succeeds, DB rolls back but deleted vectors are gone.
- **Impact**: Silent, permanent data loss. Document appears healthy in admin panel but is invisible to RAG queries.
- **Suggested Fix**: 
  1. Separate vector operations from DB transaction
  2. Use "write-new-then-swap" pattern: insert new vectors first, then delete old ones only after DB commit succeeds
  3. Add reconciliation job that detects documents with chunks but no vectors

---

### [P0] #10 - Manual chunking + scheduled re-chunking race condition

- **File**: `KnowledgeDocumentServiceImpl.java:169` vs `ScheduleRefreshProcessor.java:173`
- **Symptom**: Same document processed by two threads simultaneously; unpredictable final state
- **Root Cause**: Both paths use `UPDATE ... WHERE status != RUNNING` as optimistic lock, but they are separate code paths without a shared distributed lock. Under high concurrency both can succeed.
- **Impact**: Two sets of chunks interleave; final vector state is corrupted.
- **Suggested Fix**: Use a shared Redis distributed lock keyed on `doc:{docId}:chunk` across both manual and scheduled paths. Only one can acquire at a time.

---

### [P1] #11 - RocketMQ transaction checker false positive causes double chunking

- **File**: `bootstrap/.../knowledge/mq/KnowledgeDocumentChunkTransactionChecker.java:63-64`
- **Symptom**: Document gets chunked twice; duplicate vectors in store
- **Root Cause**: Checker only verifies `status == RUNNING` → returns true (commit message). If consumer finished processing but crashed before status update to SUCCESS, checker commits and RocketMQ re-delivers.
- **Impact**: Duplicate vectors inflate storage and may cause duplicate search results.
- **Suggested Fix**: Transaction checker should also verify processing hasn't already produced chunks (check `chunk_count > 0` for the doc).

---

### [P1] #12 - Document permanently stuck in RUNNING status

- **File**: `bootstrap/.../knowledge/schedule/KnowledgeDocumentScheduleJob.java:78-87`
- **Symptom**: Document shows "processing" indefinitely; user cannot re-trigger or delete
- **Root Cause**: Recovery depends on scheduled job checking `update_time` timeout. Risks:
  1. Recovery job and normal completion overlap — can mark a just-succeeded doc as FAILED
  2. If the scheduled job itself is down, no recovery happens at all
  3. Default timeout (10 min) may be too short for large documents
- **Impact**: Document locked out from all operations.
- **Suggested Fix**: 
  1. Add heartbeat mechanism: RUNNING docs must update `update_time` periodically during processing
  2. Expose manual "force reset" endpoint for admins
  3. Add alerting when docs exceed expected processing time

---

### [P2] #13 - Empty/corrupted document silently marked SUCCESS

- **File**: `KnowledgeDocumentServiceImpl.java:375-378`, `ChunkerNode.java:60`
- **Symptom**: Document shows as successfully processed but RAG never finds it
- **Root Cause**: Pipeline produces zero chunks (image-only PDF, corrupted file, etc.) but still marks status as SUCCESS with `chunk_count=0`.
- **Impact**: User believes document is usable. Silent failure.
- **Suggested Fix**: Treat zero-chunk result as WARNING or PARTIAL_SUCCESS. Surface it in UI as "Document parsed but no indexable content found".

---

### [P2] #14 - Deletion during scheduled re-chunking creates orphaned chunks

- **File**: `ScheduleRefreshProcessor.java:195-198`, `KnowledgeDocumentServiceImpl.java:408-410`
- **Symptom**: Chunks and vectors created for a soft-deleted document
- **Root Cause**: Delete blocks if status is RUNNING, but between the scheduler's `tryMarkRunning()` and actual processing, a delete can succeed (status was still pending). Scheduler then proceeds to chunk a deleted document.
- **Impact**: Orphaned chunks and vectors for non-existent documents; wasted resources.
- **Suggested Fix**: Re-check document `deleted` flag after acquiring the processing lock, before starting any work.

---

### [P3] #15 - S3 files not cleaned up on chunking failure

- **File**: `KnowledgeDocumentServiceImpl.java:239-250`
- **Symptom**: S3 storage grows indefinitely with orphaned files from failed ingestions
- **Root Cause**: FAILED path marks status but does not delete the uploaded S3 file.
- **Impact**: Storage cost accumulation over time.
- **Suggested Fix**: Add S3 cleanup in failure handler, or run periodic garbage collection job for files without healthy document references.

---

### [P3] #16 - chunk_count not decremented on intermediate delete

- **File**: `bootstrap/.../knowledge/service/impl/KnowledgeChunkServiceImpl.java:461-466`
- **Symptom**: Admin panel shows wrong chunk count for documents
- **Root Cause**: `deleteByDocId()` (used during re-chunking) deletes chunks but doesn't reset `chunk_count`. If new chunk insertion fails after delete, count shows stale value.
- **Impact**: Misleading statistics; affects embedding model change validation logic.
- **Suggested Fix**: Reset `chunk_count = 0` atomically with bulk chunk deletion.

---

## III. Knowledge Management & RBAC Chain

### [P1] #17 - KB deletion does not cascade-clean related data

- **File**: `bootstrap/.../knowledge/service/impl/KnowledgeBaseServiceImpl.java:187-206`
- **Symptom**: Orphaned records accumulate in multiple tables after KB deletion
- **Root Cause**: KB soft-delete only marks itself deleted. Does NOT clean up:
  - `t_role_kb_relation` — roles still reference deleted KB
  - `t_conversation` — conversations with `kb_id` pointing to deleted KB
  - `t_knowledge_document_schedule` — scheduled jobs continue for deleted KB's docs
- **Impact**: Table bloat; role management UI shows ghost KBs; scheduled jobs waste resources.
- **Suggested Fix**: Add cascade cleanup in KB delete transaction: delete RBAC relations, mark conversations as archived, delete schedule records.

---

### [P1] #18 - KB creation: DB succeeds but vector space / S3 fails

- **File**: `bootstrap/.../knowledge/service/impl/KnowledgeBaseServiceImpl.java:66-123`
- **Symptom**: KB exists in DB but has no vector space or S3 bucket; all subsequent operations fail
- **Root Cause**: `@Transactional` covers DB insert, but S3 bucket and vector space creation are external calls. If S3 succeeds but vector space fails, transaction rolls back DB but S3 bucket remains as orphan. If DB insert succeeds but both external calls fail, KB record exists without infrastructure.
- **Impact**: KB is unusable; document uploads and chunking fail with cryptic errors.
- **Suggested Fix**: Use saga pattern: create external resources first, then insert DB record. On failure, compensate (delete S3 bucket). Or add health-check on KB access that verifies infrastructure exists.

---

### [P2] #19 - RBAC cache 30-minute stale window after permission change

- **File**: `bootstrap/.../user/service/impl/RoleServiceImpl.java:145-152`
- **Symptom**: User retains access to KB for up to 30 minutes after permission revocation
- **Root Cause**: `evictCacheForRole()` only clears cache for users currently holding the role. If a user was just removed from the role, their cached permissions are not evicted. Cache TTL is 30 minutes.
- **Impact**: Revoked users can still query restricted knowledge bases during the TTL window.
- **Suggested Fix**: 
  1. Also evict cache for users REMOVED from the role (before deletion)
  2. Shorten TTL to 5 minutes
  3. Or use event-driven cache invalidation (publish invalidation event on any RBAC change)

---

### [P2] #20 - Chunk content update: vector update outside transaction

- **File**: `bootstrap/.../knowledge/service/impl/KnowledgeChunkServiceImpl.java:239-282`
- **Symptom**: Chunk text is updated but vector embedding still reflects old content
- **Root Cause**: DB update is inside `@Transactional`, but `vectorStoreService.updateChunk()` is called after transaction commits. If vector update fails, DB has new content but vector store has stale embedding.
- **Impact**: RAG retrieval uses old similarity scores; search results don't match actual chunk content.
- **Suggested Fix**: Re-embed the chunk content and update vector atomically (or at least with compensation on failure).

---

### [P2] #21 - Conversation creation skips KB existence and RBAC validation

- **File**: `bootstrap/.../rag/service/impl/ConversationServiceImpl.java:112-142`
- **Symptom**: Conversations created for non-existent or unauthorized KBs
- **Root Cause**: `createOrUpdate()` stores `kbId` directly without checking KB exists in `t_knowledge_base` or verifying user has RBAC access.
- **Impact**: Data integrity violation. Conversation references invalid KB. Later RAG queries may fail or behave unexpectedly.
- **Suggested Fix**: Validate KB existence and user RBAC access before creating conversation.

---

### [P3] #22 - PgVector uses hardcoded global index name for all KBs

- **File**: `bootstrap/.../rag/core/vector/PgVectorStoreAdmin.java:37-52`
- **Symptom**: Only first KB gets a proper HNSW index; subsequent KBs may share or miss index
- **Root Cause**: Index name `"idx_kv_embedding_hnsw"` is hardcoded. Creating multiple KBs doesn't create per-KB indexes.
- **Impact**: Vector search performance inconsistent across KBs; some KBs may do sequential scan instead of ANN.
- **Suggested Fix**: Generate index name per collection/KB: `"idx_" + collectionName + "_embedding_hnsw"`.

---

### [P3] #23 - vectorSpaceExists() swallows all exceptions

- **File**: `bootstrap/.../rag/core/vector/PgVectorStoreAdmin.java:55-63`
- **Symptom**: KB creation proceeds without vector space when DB is temporarily down
- **Root Cause**: `catch (Exception e) { return false; }` — cannot distinguish "space doesn't exist" from "DB connection failed".
- **Impact**: May skip index creation or incorrectly proceed with KB setup.
- **Suggested Fix**: Catch specific exceptions; propagate connection errors.

---

## Cross-Cutting Themes

### Theme A: Vector Store vs. DB Transaction Gap
Issues #9, #17 (partial), #20 share the same root cause: vector store operations are not transactional but are mixed with DB transactions. Any failure in the vector path after DB commit (or vice versa) leaves inconsistent state.

**Systemic Fix**: Adopt an eventual-consistency model with a reconciliation mechanism. Use an outbox pattern: write intent to DB, then process vector operations asynchronously with retry and idempotency.

### Theme B: Status Machine Without Formal Guard
Issues #10, #12, #14 stem from document status transitions lacking formal state machine validation. Any code path can attempt any transition without verifying preconditions.

**Systemic Fix**: Implement explicit state machine with allowed transitions. Use `UPDATE ... WHERE status = :expectedStatus` consistently as the ONLY way to transition states. Log all transitions.

### Theme C: Silent Degradation Without User Feedback
Issues #3, #4, #5, #13 all silently degrade when upstream services fail, presenting infrastructure failures as normal business results.

**Systemic Fix**: Add a `DegradationContext` that accumulates warnings through the RAG pipeline. Surface warnings in SSE response metadata so the frontend can display "results may be incomplete" banners.
