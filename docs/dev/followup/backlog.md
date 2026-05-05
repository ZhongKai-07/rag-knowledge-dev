# Deferred Follow-ups

> Resolved on `main`:
> - `EVAL-3`: eval read endpoints `retrieved_chunks` go through `EvalResultRedactionService`; list/trend VOs structurally lack the field; current ceiling=`Integer.MAX_VALUE` (SUPER_ADMIN full read), AnyAdmin downgrade only requires switching the ceiling
> - Landed in PR E3 (`feature/eval-e3-plan`)
> - `SEC-1`: `DeduplicationPostProcessor` no longer revives chunks from raw `results`
> - `SRC-10`: added `rag.sources.min-top-score=0.55`; low-relevance KB evidence no longer emits `sources` or `suggestions`
> - Details: `log/dev_log/2026-04-22-authz-dedup-fix-and-relevance-gate.md`
> - Landed via direct commit to `main` (`1e82b3a4`); no PR opened for this fix

由 2026-04-14 `/simplify` 三 agent 审查（91 commit 的 RBAC feature 分支）发现但未在那一轮处理的遗留项。
按**真实优先级**排序，不是按提出顺序。动手前可逐条挑，不必刷全表。

---

## 🔴 上线前必查

### SL-1. Milvus / Pg 检索未实现 `metadataFilters`

**位置**：`MilvusRetrieverService` / `PgRetrieverService`
**症状**：接受 `metadataFilters` 参数但**静默忽略**。
**影响**：`rag.vector.type` 切到 `milvus` 或 `pg` 时，文档 `security_level` 过滤**完全失效** —— 低权用户会读到高密文档。
**修复**：要么补实现、要么启动时校验 `rag.vector.type=opensearch` 或上述 service `@PostConstruct` fail-fast。
**已在** `CLAUDE.md` 标注，但属于"容易忘"的 trap。

### SL-2. `t_user.dept_id` / `t_knowledge_base.dept_id` 缺 FK 约束

**位置**：DB schema
**症状**：`SysDeptServiceImpl.delete()` 本轮加了 `@Transactional` + `SELECT FOR UPDATE` 串行化 dept 自身的并发修改，但仍无法阻止**并发向已删除 dept 插入 user/KB**。
**修复**：补 `ALTER TABLE t_user ADD CONSTRAINT fk_user_dept FOREIGN KEY (dept_id) REFERENCES sys_dept(id) ON DELETE RESTRICT;` 同理 `t_knowledge_base`。
**注意**：需要检查历史数据是否有孤儿行，否则 ALTER 会失败。

### EVAL-4. 合成任务可恢复性（PR E2 引入）

**位置**：`bootstrap/.../eval/service/SynthesisProgressTracker.java`
**症状**：`SynthesisProgressTracker` 进程内 `ConcurrentHashMap`，非持久化。后端重启后所有 RUNNING 状态丢失，部分 `t_eval_gold_item` 可能已落库。UI 按 `totalItemCount > 0` 隐藏"合成"按钮，用户只能 delete dataset 重来。
**改进选项**：
- 给 `t_eval_gold_dataset` 加 `sync_status` 列（migration + dataset-level 状态持久化）
- 启动时扫 stale RUNNING 并标记 FAILED
- 允许增量合成（需 per-chunk dedup）
**优先级**：🟡 低。单机部署 + 运行时间 < 10 分钟场景不触发；真正需要 k8s 滚动更新 + 长批时再做。

### EVAL-TOVO-AGG. `GoldDatasetServiceImpl.toVO()` 3×N 聚合查询

**位置**：`GoldDatasetServiceImpl.toVO()` 调 `countAll / countApproved / countPending` 3 次 `selectCount`；`list()` 里 N 个 dataset 触发 3N 次。
**症状**：20 dataset 的 list → 61 次 DB round-trip。admin 管理页低频所以现在能接受。
**触发条件**：list 返回 > ~50 行，或 list 被放进热路径。
**方案**：
```sql
SELECT dataset_id, review_status, COUNT(*) FROM t_eval_gold_item
WHERE dataset_id IN (?, ?, ...) AND deleted = 0
GROUP BY dataset_id, review_status
```
一次拉回后 Java 侧聚合到 `Map<String, StatusCounts>`。`detail()` 保留 3-call 无需改。
**优先级**：🟢 低。tests 锁住了 3-count 语义，改时需同步更新 wrapper SQL-fragment 路由。

### EVAL-retry. `RagasEvalClient` 缺失 HTTP retry

**位置**：`RagasEvalClient.synthesize()` + `application.yaml` `rag.eval.python-service.max-retries: 2`（当前配置但未消费）。
**症状**：`GoldDatasetSynthesisServiceImpl` 对整批 HTTP 异常的处理是 `failed += batch.size() + tracker.update + continue`。transient network blip 把整批标记失败，不可恢复。
**方案**：在 `RagasEvalClient` 加 `RestClient` level retry interceptor（指数退避），消费 `max-retries` 配置；idempotent 语义（同一批重试同样返回 `SynthesizeResponse`，Python 侧已幂等）。
**优先级**：🟡 中。PR E3 前先落，否则评估运行触发真 LLM 调用时 transient 故障成本更高。

### EVAL-BATCH-DOUBLECOUNT. Synthesis `failed` 计数器可能 double-count

**位置**：`GoldDatasetSynthesisServiceImpl.runSynthesisSync()` 最后一段。
**症状**：Python 若在 `items` 里返回空 q/a（→ 内层 blank guard `failed++`）且同一 `chunk_id` 又出现在 `failed_chunk_ids`（外层 `failed += size()`），同一 chunk 被计两次。
**修复**：维护 `Set<String> accountedInItems` 记录内层已处理的 chunk_id，外层累计前做 filter。
**优先级**：🟢 低。Python 当前不会同时返回两处；Java 侧只是不信任契约的防御层。待真实 drift 出现再改。

### EVAL-EMBED-RESILIENCE. eval 高并发触发 embedding 路由熔断（PR E3 E2E 引入）

**位置**：`infra-ai/.../ModelSelector.buildModelTarget` line 157（`healthStore.isOpen` 过滤）+ `ModelRoutingExecutor.executeWithFallback` line 47（empty targets throw）。
**症状**：eval 跑批 15 item × 多次 embed 调用，若 siliconflow 失败（欠费 / quota / 400）2 次→ OPEN 30s；fallback 到 ollama 若本地未启动也失败→ 也 OPEN；两候选同 OPEN 时 selectEmbeddingCandidates() 返 `[]` → throw `No Embedding model candidates available`，后续 item 全 failed 直到熔断半开。
**根因**：`failure-threshold: 2` 太敏感 + 候选只 2 个；缺指数退避 retry。
**方案**：(a) `RoutingEmbeddingService` 加 retry-with-backoff（同 `EVAL-retry` 模式）；(b) eval-only 把 `failure-threshold` 调到 5 或在 eval 路径用专用 quota 池；(c) 第 3 候选（百炼 embedding）补齐多样性。
**优先级**：🟡 中。eval 跑批时可见，普通用户聊天高频也会触发只是不易察觉。

### EVAL-AR-EMBED. DashScope embeddings 形状错误致 answer_relevancy 整批 NaN

**位置**：Python ragent-eval 容器调 DashScope `text-embedding-v3` API。
**症状**：`BadRequestError: Value error, contents is neither str nor list of str.: input.contents`。RAGAS `answer_relevancy` 内部需要 embedding 计算 question/answer 余弦相似度——embedding call 失败 → 该 metric 整批 NaN，`_safe_float` 转 None。其他 3 个 metric 不受影响（按 RAGAS per-metric 容错）。
**根因**：RAGAS 0.2.x `LangchainEmbeddingsWrapper` + `langchain-openai` 0.2.x 对 DashScope 兼容模式下 embedding `input` 字段格式有歧义（list[list[str]] vs list[str]）。
**方案**：升级 langchain-openai / RAGAS 版本，或在 ragas/Dockerfile 装 ragas[dashscope] adapter；或自写 embedding wrapper 显式控制 payload 形状。
**优先级**：🟡 中。当前 4 metric 缺 1（25% 数据丢失），趋势页 AR 折线常空。

### EVAL-OS-PARSE. OpenSearch 部分查询返 HTML 致 JsonParseException（pre-existing）

**位置**：`OpenSearchRetrieverService.doSearch` line 127 `objectMapper.readValue`。
**症状**：`com.fasterxml.jackson.core.JsonParseException: Unexpected character ('<' (code 60))`。OpenSearch 在某些查询条件下返 HTML 错误页（疑似 auth 重定向 / 5xx 报错页），ObjectMapper 读到 `<` 就崩。
**根因**：未知，疑似 `opscobtest2` 索引 mapping 兼容性 / 大请求体超限 / 偶发 502。
**方案**：先在 `doSearch` 上加 `Content-Type: application/json` 强校验 + 错误响应 raw body 打日志；再排查 OpenSearch server log。
**优先级**：🟡 中。pre-existing（PR E3 之外），但 eval 跑批时显著放大命中率（每 item 多次检索）。

### EVAL-CP-LOWYIELD. Context Precision 收敛率异常低

**位置**：RAGAS `context_precision` metric 在 `EvalRunExecutorTest` 实战 run。
**症状**：15 item PARTIAL_SUCCESS run 中，5 item 跑出 faithfulness、9 item 跑出 context_recall、但仅 1 item 跑出 context_precision（faith vs CP 应高度相关，差 5 倍异常）。
**根因（怀疑）**：CP 内部 LLM grader prompt 长度问题 / 子任务超时 / output 解析失败把指标打 NaN。需要看 ragent-eval 容器日志中 `context_precision` 的具体 RAGAS Job 错误。
**方案**：先抓 CP 失败 item 的 RAGAS 内部 trace；可能要单独跑 RAGAS CLI 复现 + 逐项排查。
**优先级**：🟢 低。属于 RAGAS 内部行为调优，不阻塞 eval 闭环；趋势页可正常用其他 3 metric。

---

## 🟠 性能 / 可扩展

### PERF-1. `RagTraceQueryServiceImpl.listNodes` 拉大 blob 全字段

**位置**：`RagTraceQueryServiceImpl.java:100-106`
**症状**：`selectList` 无 `.select()` projection，拉了 `inputData`/`outputData`/`extraData`（LLM I/O，可能很大）但 `toNodeVO` 大多忽略。
**修复**：加 `.select(...)` 只拿 VO 需要的列。

### PERF-2. Last-SUPER_ADMIN 守护 O(users × roles)

**位置**：`KbAccessServiceImpl.countSuperAdminsExcluding` (~line 530)
**症状**：`selectList(所有 user)` 后对每个 user 单独 `selectList(其 roles)`。任何 role mutation / 用户删除都触发。
**修复**：改单条 `SELECT DISTINCT ur.user_id FROM t_user_role ur WHERE ur.role_id IN (validSuperRoleIds) AND ur.user_id NOT IN (excluded)` + Java 层应用 override。
**优先级低**：admin mutation 频率本来就低，延迟感知小。

### PERF-3. `RoleServiceImpl` 批量写入 N+1

**位置**：`RoleServiceImpl.java:152, 201, 278-307`
**症状**：`setRoleKnowledgeBases` / `setUserRoles` / `setKbRoleBindings` 全部逐条 `mapper.insert()`。`setKbRoleBindings` 还在循环内 `selectById` + `selectList`，双 N+1。
**修复**：`saveBatch` 或手写 `INSERT ... SELECT`；循环外一次性 preload roles/userRoles。

### PERF-4. `RoleListPage` 前端 fan-out `getRoleKnowledgeBases`

**位置**：`frontend/src/pages/admin/roles/RoleListPage.tsx:99-110`
**症状**：对每个角色发一次 HTTP 只为拿 KB 数量（30 角色 = 30 round-trip，每次都走一遍 Sa-Token + RBAC）。
**修复**：后端 `GET /role` 响应直接带 `kbCount` 字段，或加 `GET /role/kb-counts` 批量接口。

### ~~OBS-1. Suggested Questions 挂 RagTrace 节点（部分）~~ ✅ 核心已解决（2026-04-16）

**进展**：commit 已给 `DefaultSuggestedQuestionsService.generate` 挂上 `@RagTraceNode(name="suggested-chat", type="SUGGESTION")`。Trace 详情页现在能看到独立的 "suggested-chat" 节点 + 耗时 + 状态，配合 Option A 的 chip 展示（commit `a309650`）已能覆盖 90% 排查需求。
**TTL 前置问题未出现**：执行时实测 `RagTraceContext` 能跨过 `suggestedQuestionsExecutor` 传递（机制未完全探明，可能是 `TransmittableThreadLocal` 在 submit 时自动 capture；OBS-2 也因此降级为纯一致性问题）。
**剩余可选**：把 LLM request/response 快照写到 node 的 `inputData`/`outputData` 便于复现 prompt。非阻塞，真需要深度排查再做。

### OBS-2. `suggestedQuestionsExecutor` 与项目其他线程池不一致

**位置**：`bootstrap/.../rag/config/SuggestedQuestionsExecutorConfig.java`
**背景**：项目里其他所有 RAG 线程池（`ThreadPoolExecutorConfig` 里 9 个）都走 `ThreadPoolExecutor` + Hutool `ThreadFactoryBuilder` + `TtlExecutors.getTtlExecutor(...)`，独此一家用裸 `ThreadPoolTaskExecutor`。
**症状**：实测 TTL 传递是 OK 的（OBS-1 挂节点后 trace 能正常嵌套），所以**不影响功能**。但：
- 新人看到会困惑"为啥这个特殊"
- 未来若某处显式依赖 `TtlExecutors` 包装过的 `Executor`（比如监控、装饰器链）会绕开此 bean
**修复**：改为与 `ThreadPoolExecutorConfig` 一致的模式；同步修改 `StreamChatHandlerParams` 字段类型为 `Executor` 并调整 Task 17 集成测试。
**优先级低**：纯代码一致性，无功能风险。

### OBS-3. `sender.sendEvent(FINISH, ...)` 未包 try/catch 导致 taskManager 泄漏

**位置**：`StreamChatEventHandler.onComplete()`
**症状**：`sendEvent(FINISH, payload)` 若抛（客户端已断、SSE 已关），控制流直接逃离 `onComplete`，`taskManager.unregister(taskId)` 不会被调用，条目残留在 `StreamTaskManager`。这是 2026-04-16 feature/suggested-questions 引入前就存在的行为，但由于新增了 `shouldGenerate` 分支，FINISH 之后要做的事更多，泄漏窗口被放大。
**修复**：把 `sendEvent(FINISH, ...)` 包进 try/catch，失败时短路到 `sendDoneAndClose()`（保证 unregister + complete）。
**优先级低**：只有客户端主动断连才会触发，生产上偶发。

### EVAL-2. Legacy `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 失效

**位置**：`bootstrap/.../rag/service/impl/RagEvaluationServiceImpl.java:53`
**症状**：方法上挂了 `@Async`，但 `RagentApplication` 没开 `@EnableAsync` —— 注解失效，`saveRecord` 实际上**同步**在请求线程里跑。当前没造成已观察到的问题，但如果未来有人"好心"加上 `@EnableAsync`，会同时激活项目里所有其他遗留 `@Async` 注解（未审计，可能连锁效应）。
**修复方向**：删 `@Async` 注解，或按 PR E1 eval 域的模式：注入 `@Qualifier("...")` 的显式 executor + `.execute(Runnable)`；修之前先全局 `grep -r "@Async"` 评估影响面。
**优先级低**：当前无功能影响，但埋坑，eval 域已用不同模式规避（见 `eval/CLAUDE.md` Gotcha #3）。

---

## 🟡 架构 / 类型安全

### ARCH-1. `KbAccessServiceImpl` 是 god-service（22 方法 / ~550 行）

**位置**：`KbAccessServiceImpl.java`
**症状**：混合 RBAC 读路径、mutation pre-flight（Last-SUPER_ADMIN 模拟）、dept-based admin bypass、doc-level 检查、缓存驱逐、请求时鉴权。每个 `checkXxxAccess` 重复 `hasUser → isSuperAdmin → isDeptAdmin → throw` 开场白。
**建议拆**：
- `SuperAdminInvariantService`（`countSuperAdminsExcluding` + `simulateActiveSuperAdminCountAfter`）
- `KbAuthorizationService`（所有 `check*` 方法）
- `KbAccessQueryService`（`getAccessibleKbIds` / `getMaxSecurityLevelsForKbs`）

### ARCH-2. DTO 还在用 `String` 而非枚举

**位置**：`RoleController.RoleCreateRequest.roleType: String`、`RoleKbBindingRequest.permission: String`、`RoleServiceImpl` 内多处 `RoleType.SUPER_ADMIN.name().equals(str)` 比较
**症状**：项目明明有 `RoleType` / `Permission` 枚举，DTO 却传字符串，一路 `.name().equals()` 比较易拼错，静默回退到默认（e.g. `"MANAGE"` / `"READ"`）。
**修复**：DTO 直接接 `RoleType` / `Permission`，Jackson 自动反序列化；消除 8 处 `.name().equals()` + `KbAccessServiceImpl:160` 的 try/catch。

### ARCH-3. Service 返回 Controller 内部类 DTO

**位置**：`RoleServiceImpl.java:176` 返回 `List<RoleController.RoleKbBindingRequest>`；`getKbRoleBindings:230` 返回 `List<KnowledgeBaseController.KbRoleBindingVO>`
**症状**：Service 依赖 Controller（倒挂）。
**修复**：DTO 移到 `user/dto/` 或 `role/dto/`。

### ARCH-4. 管理页三个 List 页 ~80% 同构

**位置**：`UserListPage.tsx` / `RoleListPage.tsx` / `DepartmentListPage.tsx`
**症状**：`dialogState: { open, mode, entity }` 形状 + create/edit handler 骨架（每页 ~60 LOC）+ 页头/搜索/Card/Table/AlertDialog 包裹层全部重复。
**修复**：抽 `useCrudDialog<T>()` hook + `<AdminListShell>` 布局组件，预计省 ~180 LOC。

### ARCH-5. `KbSharingTab` 与 `RoleListPage` 的 KB 绑定编辑器 90% 重复

**位置**：`KbSharingTab.tsx:102-176` vs `RoleListPage.tsx:438-522`
**修复**：抽 `<KbBindingEditor bindings onChange />` 共用组件。

---

## 🔵 Agentic 化（PoC 启动中）

> 设计文档：[`docs/dev/design/2026-05-04-agentscope-agentic-poc-design.md`](../design/2026-05-04-agentscope-agentic-poc-design.md)
> 路线：边界 PoC（不动现有 `/chat`，新增 `/agent` 链路）；外层 Plan-and-Execute + 子步骤 ReAct 兜底。

### AGENT-1. 接入 AgentScope-java（PoC 阶段 1：依赖 + LLM 桥接 + Tool 适配）

**范围**：PR1 + PR2（~700 LOC）。引入 `agentscope-spring-boot-starter:1.0.12`，把 `BaiLianChatClient` 适配为 `Model`，把 `RetrievalEngine` + 2 个 MCP 工具包装为 AgentScope `Toolkit`。
**前置**：Spring Boot 3.5.7 与 AgentScope 1.0.12 依赖兼容性手验。
**风险**：版本冲突 → 退路是只引 `agentscope-core`，自己装配 bean。

### AGENT-2. Plan-and-Execute 主干 + HITL（PoC 阶段 2）

**范围**：PR3 + PR4（~800 LOC）。`PlannerService` / `PlanExecutor` / `SynthesizerService` / `HumanApprovalGate`；新表 `t_agent_plan` + `t_agent_step_run`（`upgrade_v1.11_to_v1.12.sql`）；副作用工具走 HITL Redis 队列。
**关键约束**：六道闸门（步数 / token / 单 Tool 次数 / 重复参数 / 总耗时 / 失败降级）必须全部生效，否则不上线。

### AGENT-3. 可观测 + 评估（PoC 阶段 3，可选）

**范围**：PR5（~200 LOC）。OpenTelemetry 接入 + `EvaluationCollector` 扩展收集 Agent 轨迹 + Grafana 看板（步数分布 / token 消耗 / HITL 通过率 / 终止原因）。
**优先级**：P2，PoC 跑通后视实际流量再做。

---

## 🟢 小清理

### ~~CLEAN-1. 错误吞咽~~ ✅ 已解决（2026-04-14, `feature/cleanup-error-swallows`）

~~`UserListPage.tsx:128` / `SpacesPage.tsx:85` / `KbSharingTab.tsx:39` 多处 `catch { /* ignore */ }` 把网络错误当成权限拒绝处理。应该 `inspect err.code`，只在 RBAC 错误码下静默。~~

**处理**：抽 `isRbacRejection(err)` 到 `utils/error.ts`；3 处 catch 都改为"只在 RBAC 拒绝时静默，否则 toast + console.error"。`KbSharingTab` 的 `setNoAccess(true)` 副作用在 RBAC 分支保留。

### CLEAN-2. `authStore.checkAuth` 未节流

`stores/authStore.ts`：每次组件 mount 都无条件调 `fetchCurrentUser`，忽略缓存。加 `lastVerifiedAt` + 60s 节流。

### CLEAN-3. `UserListPage.handleRefresh` 双 fetch

`UserListPage.tsx:95-98`：`setPageNo(1)` + `loadUsers(1, keyword)` 同时触发，page 已在 1 时会 fetch 两次。

---

## 📦 Answer Sources（2026-04-21 PR1+PR2 遗留）

### SRC-1. Milvus / Pg retriever 未回填 `docId` / `chunkIndex`（以及历史遗留的 `kbId` / `securityLevel`）

**位置**：`MilvusRetrieverService.java` / `PgRetrieverService.java`（`toRetrievedChunk` 或等价映射处）
**症状**：PR1 只在 `OpenSearchRetrieverService.toRetrievedChunk` 回填了这 4 个字段，Milvus/Pg 保持 dev-only 状态不回填（代码注释已标明）。
**影响**：切 `rag.vector.type=milvus` 或 `pg` 后：
- Answer Sources 功能（PR2-PR5）完全失效（`distinctChunks` 都没 docId，`SourceCardBuilder` 全部丢弃）
- `security_level` 过滤完全失效（和 SL-1 叠加）
**修复**：上线非 OpenSearch 后端之前必须补齐这 4 个字段的回填。
**优先级**：P0（前置于 vector backend 切换）。

### SRC-2. 写路径字符串字面量扫荡：`"doc_id"` / `"chunk_index"` → `VectorMetadataFields.DOC_ID` / `.CHUNK_INDEX`

**位置（已定位）**：
- `IndexerNode.java:230`
- `OpenSearchVectorStoreService.java:226-227`
- `OpenSearchVectorStoreAdmin.java:280-281`（text block 改不了，留注释说明即可）
- `MilvusVectorStoreService.java:220-221`
- `PgVectorStoreService.java:119-120`
**症状**：PR1 给读路径加了 `VectorMetadataFields` 常量但写路径还用字面量，未来改字段名会漂移。
**修复**：逐点改为常量引用。text-block 场景留 `// SSOT: VectorMetadataFields.DOC_ID` 注释。
**优先级**：P3（功能无风险，纯一致性）。

### SRC-3. `StreamChatEventHandler` 里遗留 ThreadLocal 读取

**位置**：`StreamChatEventHandler.java`
**症状**：`onComplete()` 仍直接读 `UserContext.getUserId()` 和 `RagTraceContext.getEvalCollector()`。虽然构造器已经缓存了 `this.userId` 和 `this.traceId`，但业务代码路径没用缓存，而是运行时再读 ThreadLocal。
**背景**：CLAUDE.md 记录 `ChatRateLimitAspect.finally` 会提前清空上下文，理论上 handler 异步回调时 ThreadLocal 可能为空。实测 `TransmittableThreadLocal` 目前覆盖了（OBS-1/2 里探明），但是**依赖脆弱**。
**修复**：改用构造期捕获的字段，彻底去掉异步段的 ThreadLocal 读取。PR2 已经给 cards 立了典范（set-once holder 模式）。
**优先级**：P2（PR4 改 onComplete 做 sources_json 落库时可以顺手清）。

### SRC-4. `chatStore.onSuggestions` 缺 `streamingMessageId` guard

**位置**：`frontend/src/stores/chatStore.ts`（`onSuggestions` handler 内）
**症状**：`onSuggestions(payload)` 直接用 `payload.messageId` 找消息写 `suggestedQuestions`，没做 `streamingMessageId === assistantId` guard。如果用户已取消/重发，payload.messageId 可能匹配到历史消息而不是"当前流消息"。
**对比**：PR2 给 `onSources` 严格加了这个 guard + non-array drop，模板可照搬。
**优先级**：P3（pre-existing 小概率 bug，不是 PR2 引入）。

### SRC-5. `SourceCard.kbName` 字段延期

**背景**：v1 spec 设计里 `SourceCard` 有 `kbName` 展示字段，PR2 按边界收紧决策**去掉了**（DocumentMetaSnapshot 无 kbName）。
**未来怎么补**：**不要** rag 侧再注一个 `KnowledgeBaseService` 查 name，那样会扩 rag→knowledge 查询面。**正确做法**：扩展 `KnowledgeDocumentService.findMetaByIds` 的返回 record 从 `(docId, docName, kbId)` 升为 `(docId, docName, kbId, kbName)`，让 knowledge 域一次 JOIN `t_knowledge_base` 带出 name。`rag` 仍然只依赖一个 service 边界。
**触发**：PR3 / PR4 用户层需要看到 KB 名称时再做。
**优先级**：P3（additive，功能可推迟）。

### ~~SRC-6. 缺席矩阵"意图歧义 clarification" 行没显式单测~~ ✅ 已解决（2026-04-22 PR3）

**处理**：PR3 §2.8 commit `6710085` 在 `RAGChatServiceImplSourcesTest` 追加 `streamChat_whenGuidanceClarificationPrompt_thenSkipSourcesEntirely` 测试（≤25 行）。当 `guidanceService.detectAmbiguity` 返回 prompt 结果，断言：(负向) `sourceCardBuilder` / `trySetCards` / `emitSources` / `llmService.streamChat` 均 `verifyNoInteractions` / `never()`；(正向) `callback.onContent(eq(prompt))` + `callback.onComplete` 各调用一次，确认 clarification 仍正常走流式返回。

### SRC-7. `npm run lint` 预存在 ESLint 破损

**位置**：`frontend/` 根（`.eslintrc.cjs` + `package.json`）
**症状**：`npm run lint` 报 `"ConfigArrayFactory._normalizePluginConfig" failed: Object literal may only specify known properties, and 'name' does not exist in type 'PluginConf'`（ESLint 8.57.1 与 `plugin:react-refresh/recommended` flat-config 不兼容）。**不是 PR2 引入**：clean branch stash 掉所有本地改动后复现。
**影响**：CI lint gate 无法运行。PR2 期间改用 `node_modules/.bin/tsc --noEmit` 作类型 gate + `npm run test` 作测试 gate 代替。
**修复路径**（二选一）：
- (a) 升 ESLint 到 9.x（flat config 原生支持，但需要迁移 `.eslintrc.cjs` → `eslint.config.js`）
- (b) 降 `eslint-plugin-react-refresh` 到兼容 8.x 的老版本
**优先级**：P2（阻塞 CI lint gate，独立任务修）。

### SRC-8. Stacked PR 流程 checklist 缺失

**背景**：2026-04-21 PR1+PR2 这次 stacked PR 踩了坑：PR #13 base 写的是 `feature/answer-sources-pr1`，PR #12 合完之后 GitHub 不自动把 PR #13 的 base 改到 main，导致 PR #13 合并后 PR2 内容**只进了 PR1 分支不进 main**，需要开 PR #14 catch-up 才能把 PR2 推到 main。
**建议**：在 `docs/dev/` 某处（或 `CLAUDE.md` 根级 "PR 流程" 小节）沉淀 checklist：
1. 父 PR 合并后立即 `git fetch origin && git log origin/main | head` 确认 main 是否含子 PR 内容
2. 若缺，两条路：(a) 开 catch-up PR（head=更新后的中间分支，base=main）；(b) 去 GitHub UI 把子 PR 的 base 从中间分支改到 main 再 merge
3. 或者更朴素：在 PR1 合并前就去把 PR2 的 base 改 main，避开坑
**优先级**：P3（流程债，不是代码债）。

---

### SRC-9. `updateTraceTokenUsage` 的 overwrite 写法是 latent 坑

**位置**：`StreamChatEventHandler.updateTraceTokenUsage`
**症状**：走 `updateRunExtraData(traceId, String)` 覆盖写。任何在它**之前**已合入 `extra_data` 的字段都会被它清掉。
**当前规避**：PR3 的 `mergeCitationStatsIntoTrace` 必须在 `updateTraceTokenUsage` **之后**执行；`RAGConfigProperties.suggestionsEnabled=false` 场景下无 `mergeSuggestionsIntoTrace` 抢跑。StreamChatEventHandlerCitationTest 用 Mockito InOrder 锁住了这个顺序。
**根治**：把 `updateTraceTokenUsage` 改为 `mergeRunExtraData(traceId, Map.of("promptTokens", ..., "completionTokens", ..., "totalTokens", ...))`；非 PR3 scope。
**优先级**：P3（latent，顺序依赖已规避；但将来任何新的"先合后写"字段都可能再踩）。

---

### SRC-10. off-topic 问题仍展示低分 Sources 卡片（UX 反直觉）

**位置**：`RAGChatServiceImpl.streamChat` 检索链 + `SourceCardBuilder`
**症状**（2026-04-22 PR5 冒烟发现）：提一个完全 off-topic 的问题（如"太阳离地球有多远？"），LLM 正确识别"不相关"并在答案里声明"不属于华泰业务"+ 不产出 `[^n]`，但前端仍挂 2 张 Sources 卡（VMCSA 0.50 / GMRA 0.19）。
**原因**：三层闸门按设计都过了——(1) flag on；(2) `distinctChunks` 非空（全局向量通道召回低分 chunk）；(3) `cards.isEmpty()=false`（按 docId 聚合后 2 个文档）。spec 契约上合规，但用户看到"答案说无关 + 还挂两张卡"会困惑。
**修法候选**：
- (a) **min-score threshold**（推荐）：`SourceCardBuilder` 或 `RagSourcesProperties` 加 `min-top-score: 0.6`（示例），`topScore < threshold` 的 docId 聚合直接丢。小改动，保守默认
- (b) **citation-driven filtering**：LLM 答完如果 `citationTotal == 0`（answer 里根本无 `[^n]`），把 emitSources 压回或后推后清空。需要跨 orchestrator ↔ handler 边界，scope 大
- (c) 接受现状 + UX 文案："以下是检索到的相关性较低的文档（AI 未采用）"
**优先级**：P2（非安全 / 非功能阻塞，但生产用户层直接可见；(a) 半天可落，性价比高）。

---

### SEC-1. `MultiChannelRetrievalEngine` 疑似存在某个通道绕过 `AuthzPostProcessor`

**位置**：`bootstrap/.../rag/core/retrieval/MultiChannelRetrievalEngine.java` + `AuthzPostProcessor`
**症状**（2026-04-22 PR5 冒烟发现，捕获于 off-topic 问题的 trace 日志 `log/diagnostic/error_log/response_log.md`）：
```
AuthzPostProcessor: dropping chunk id=... – kbId is null/blank (non-OpenSearch backend?)
... (连续 10 次)
ERROR AuthzPostProcessor dropped 10 chunks – retriever filter failure detected. Scope type: Ids
后置处理器 Authz 完成 - 输入: 10, 输出: 0, 变化: -10
后置处理器 Deduplication 完成 - 输入: 0, 输出: 10, 变化: +10  ← ⚠️
后置处理器 Rerank 完成 - 输入: 10, 输出: 10
```
Authz 全部 drop 之后，Deduplication 阶段"输入 0 输出 10"——去重按定义只能 ≤ 输入量。唯一解释：多通道架构下一个通道的 chunk **未经 Authz** 就进入了 Dedup 合并阶段。
**潜在影响（安全红线）**：若真的存在通道绕过 Authz，则 `security_level` / `kbId` / `accessScope` fail-closed 三重防线对该通道**完全失效**——低权用户可能读到高密 chunk。
**另一种可能**（较轻）：日志口径漂移——"输入 / 输出"记录的不是同一批 chunk，是多通道后处理器链各自跑完后 merge 阶段的记账混淆；实际安全控制正常，只是 log 误导。
**待查**：
1. 读 `MultiChannelRetrievalEngine` post-processor 链的挂载点，对每个 `RetrievalChannel` 确认 Authz 都挂了
2. 如果确实挂了但日志仍显示绕过，查 Dedup 的实现是否错用了某个"未过滤"的源
3. 同时 `kbId is null/blank (non-OpenSearch backend?)` 这条 WARN 本身说明 chunk metadata 缺 `kbId`——和 SL-1 / SRC-1 同族问题，即使 OpenSearch 后端也要查一下 index metadata 是否完整
**优先级**：**P0**（安全嫌疑，PR5 合并后立刻查；即使最终定性为日志口径问题也要把日志改清楚）。

---

### SEC-2. PR3 `KbAccessCalculatorTest` 可补两条授权收紧断言

**位置**：`bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/support/KbAccessCalculatorTest.java`
**背景**：PR3 阶段 A 收口严格按 approved plan 保留 7 条 calculator 单测；code quality review 发现两个可增强点，已确认技术上成立但不纳入 PR3 plan 执行范围。
**建议**：
- max-security map 断言从 `containsEntry(...)` 收紧为 exact map contents，避免实现额外返回未请求 KB 时测试仍通过。
- 增加 `minPermission` 过滤测试：READ relation 在 `Permission.WRITE` 下应被排除，WRITE / MANAGE relation 按 ordinal 语义保留。
**优先级**：P2（测试硬化；PR3 正文先按批准计划推进，后续小 PR 或 review-fix 单独补）。

### SEC-3. `KbScopeResolver.resolveForRead(LoginUser)` API 收敛

**位置**：`KbScopeResolver` / `KbScopeResolverImpl`
**背景**：PR3 按 approved plan 将 `KbReadAccessPort` 收敛为 current-user-only，`KbScopeResolverImpl.resolveForRead(LoginUser)` 内部也改为调用 `getAccessScope(Permission.READ)`。code quality review 指出：该 resolver 方法签名仍接受 `LoginUser`，但普通用户路径实际依赖 ambient `UserContext`，接口形状与实现契约不再完全一致。
**建议**：后续 cleanup PR 二选一：将 resolver API 收敛为 current-user-only（删除 `LoginUser` 参数），或引入显式 snapshot/subject-aware resolver 路径，避免调用方误以为传入的 `LoginUser` 是普通用户路径的唯一决策来源。
**优先级**：P2（接口语义清理；PR3 c2 继续严格执行 current-user-only port 迁移，不在阶段 A 收口 PR 内扩展 resolver 架构）。

---

## 🗂️ 引用

- 审查来源：本地会话 2026-04-14 `/simplify`（3 个并行 agent：reuse / quality / efficiency）
- 2026-04-21 新增 SRC-1~8：Answer Sources PR1+PR2 合并后记录的遗留项
- 2026-04-22 新增 SRC-9：PR3 Answer Sources 合并前记录的 trace.extra_data overwrite 隐患
- 2026-04-22 新增 SRC-10 + SEC-1：PR5 flag flip 后手动冒烟捕获的 off-topic UX + Authz 绕过嫌疑
- 本轮已处理：参见 `log/dev_log/dev_log.md` 的 "2026-04-14" 条目
- 本表不是 TODO 兜底，只记"有意识地留到下一轮"的东西。下一轮 feature 不必优先刷它。
