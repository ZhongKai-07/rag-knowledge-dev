# 开发日志

---

## 2026-04-24 | PR E1 — RAG 评估闭环地基（eval 域 + Python ragent-eval 微服务）

详情：[`2026-04-24-eval-pr-e1-foundation.md`](./2026-04-24-eval-pr-e1-foundation.md)

**核心改动**：
- 新增独立顶级 `bootstrap/.../eval/` bounded context（**不**挂 `rag/` 下），含 4 张 `t_eval_*` 表（v1.10 schema）、4 DO + Mapper、`EvalProperties` / `EvalAsyncConfig`（`evalExecutor` 线程池，**不**用 `@EnableAsync`）/ `RagasEvalClient` 骨架
- `ragas/` 目录从一次性脚本改造成 FastAPI 微服务 `ragent-eval`（:9091），端点 `/health` + `/synthesize`（PR E3 再加 `/evaluate`），Docker Compose 就位
- `RagentApplication.@MapperScan` 加 `com.nageoffer.ai.ragent.eval.dao.mapper`，`EvalMapperScanTest` 用 `@SpringBootTest` 断言 4 个 bean 非 null（Gotcha #14 锁死）
- 跨 HTTP 字段 Java ↔ Python 全部 `@JsonProperty` snake_case ↔ camelCase 显式映射
- 零新增 ThreadLocal（`evalExecutor` 不用 TaskDecorator、`RagasEvalClient` 不读 `RagTraceContext`）
- 硬约束文档化：依赖方向 `eval/ → rag.core / knowledge.*Port / framework.*`（✓），`rag/ → eval/` / `eval/` 直读 rag 表 ❌；SUPER_ADMIN-only 读 eval 结果（EVAL-3 redaction 前置）
- 真实回归：`/synthesize` 真打百炼 qwen-max 返回自然 Q-A；`mvn spring-boot:run` 启动无 `UnsatisfiedDependencyException`；Java 4 tests + Python 7 tests 全绿
- PR #19 Merge commit，15 commit 节奏保留（14 个 feat/docs + 1 个 fix ragas version shadowing）
- **本 PR 只做地基，没有 Service / Controller / 前端入口**。合成真落库 + 审核 UI 是 PR E2；评估执行 + 看板是 PR E3。Backlog：EVAL-2（legacy `RagEvaluationServiceImpl.saveRecord` 失效 `@Async`）/ EVAL-3（eval 读接口 redaction）/ PR E3-spike（`AnswerPipeline` 抽离代价）

---

## 2026-04-23 | PR2 — Over-retrieve + rerank 漏斗归位

详情：[`2026-04-23-pr2-over-retrieve.md`](./2026-04-23-pr2-over-retrieve.md)

**核心改动**：
- 拆 `DEFAULT_TOP_K=10` → `recallTopK=30` + `rerankTopK=10`，新增 `RagRetrievalProperties`（`@PostConstruct` 三重校验），`RetrievalPlan` record 沿链路贯穿。
- `SearchContext` 丢 Lombok `@Builder` 手写 builder（build() 内 IAE 兜底），双字段 `recallTopK` / `rerankTopK`。
- 两 channel 丢 `topKMultiplier` 放大，channel 内部 `sort+cap(recallTopK)`；`RerankPostProcessor` 全局 `sort → limit(recallTopK) → rerank(rerankTopK)`，sort-before-limit 必须。
- `BaiLianRerankClient.effectiveTopN = min(topN, candidates.size())` 防小 KB；`evalCollector.setTopK(distinctChunks.size())` 评测字段归真。
- 死 yaml/常量全清；`SearchChannelProperties.topKMultiplier` 两字段 `@Deprecated(forRemoval=true)`。
- 真实回归决定性证据：`[bailian-rerank] CALLING API: dedup=30, topN=10`（PR1 时是 `dedup=10, topN=10`）。KYC 0.8119 → 0.8541，地球 0.2187 → 0.2705。
- 12 新测试 + 3 测试迁移 + CLAUDE.md 三处更新 + `log/notes/` 新目录 + `gotchas.md §4` 增强。
- PR #18 Merge commit，4 commit 节奏保留（Task 1 base + Task 1 polish + 主体 + follow-up cleanup）。

---

## 2026-04-22 | PR1 — Rerank 短路旁路修复

详情：[`2026-04-22-rerank-bypass-fix-pr1.md`](./2026-04-22-rerank-bypass-fix-pr1.md)

**核心改动**：
- `BaiLianRerankClient` 移除 `dedup.size() <= topN` 短路分支：`DEFAULT_TOP_K=10` 下每次命中，导致百炼 rerank API 从未被调用，所有 query 的 `maxScore` 都是上游 OpenSearch hybrid 的假分 `≈0.5`。
- `rag.sources.min-top-score` 配合调整：`0.1 → 0.3`（rerank 产生真实 cross-encoder 分数后阈值有绝对语义）。
- `RoutingRerankService` / `BaiLianRerankClient` / `NoopRerankClient` 保留 INFO 级诊断日志，未来 rerank 静默失败可一行定位。
- `gotchas.md` 第 4 组新增一条 rerank 短路条目。
- 验证数据：KYC `0.5 → 0.8119`，地球到太阳 `0.5 → 0.2187`，从"完全无区分"到"4× 差距"。
- PR2（over-retrieve + rerank 架构，拆分 `retrievalTopK / rerankTopK`）未启动，待相关日志采样稳定后再定档。

---

## 2026-04-22 | Authz/Dedup 修复 + relevance gate

详情：[`2026-04-22-authz-dedup-fix-and-relevance-gate.md`](./2026-04-22-authz-dedup-fix-and-relevance-gate.md)

**核心改动**：
- 修复 `DeduplicationPostProcessor` 从原始 `results` 恢复 chunk 的问题，保证后续处理器不得恢复前序处理器已删除的数据。
- `rag.sources.min-top-score` 默认 `0.55`，`sources` 与 `suggestions` 统一受 relevance gate 控制。
- 新增 2 个后端测试文件并扩展 `RAGChatServiceImplSourcesTest`，覆盖 `0.50 / 0.55 / 0.65` 三档阈值行为和后处理链不变式。
- 运行态确认本地 `colleteraltest` 索引缺 `metadata.kb_id`，需删除索引后重建并对该 KB 文档重新分块/向量化。

---

## 2026-04-07 | feature/opensearch-and-rbac

### 一、实施计划执行（14 个 Task，全部完成）

#### Phase 1：OpenSearch 向量引擎

| Task | 内容 | 备注 |
|------|------|------|
| 1 | Docker Compose + Maven 依赖 | opensearch-java 2.18.0 |
| 2 | OpenSearchProperties + OpenSearchConfig | 需显式添加 httpclient5 依赖 |
| 3 | OpenSearchVectorStoreAdmin | `SimpleEndpoint.forPath()` 不存在，改用 `Requests.builder()` |
| 4 | OpenSearchVectorStoreService | `FieldValue.of(docId)` API 修正 |
| 5 | OpenSearchRetrieverService | `SearchRequest.Builder.withJson()` 不存在，改用 generic client |
| 6 | MilvusVectorStoreAdmin 幂等修正 + Pg Score 归一化 | |

#### Phase 2：RBAC 权限体系

| Task | 内容 |
|------|------|
| 7 | DDL：t_role / t_role_kb_relation / t_user_role |
| 8 | 三个实体类 + MyBatis Mapper |
| 9 | KbAccessService（Redis 缓存 + 双上下文鉴权） |
| 10 | RoleService + RoleController |

#### Phase 3：检索链路集成

| Task | 内容 |
|------|------|
| 11 | SearchContext.accessibleKbIds + 两个检索通道 RBAC 过滤 |
| 12 | Controller → Service → RetrievalEngine → MultiChannel 全链路贯通 |
| 13 | KnowledgeBase / Document 接口权限校验 |

#### Phase 4：前端

| Task | 内容 |
|------|------|
| 14 | chatStore 新增 knowledgeBaseId + ChatInput 知识库选择器 |

---

### 二、启动调试

| 问题 | 原因 | 修复 |
|------|------|------|
| 第一次启动失败 | `opensearch-java` jar 不在 IDE 运行时 classpath | `mvn clean install -DskipTests` + IDE reload |
| 第二次启动失败 | Task 2 子 Agent 修改 yaml 时将 `type: pg` 误改为 `opensearch` | 还原为 `pg` |
| 第二次启动失败（同时） | `@Bean(destroyMethod = "close")` 但 `OpenSearchClient` 无此方法 | 移除 `destroyMethod` |
| OpenSearch 容器无法启动 | compose 文件中 `plugins.security.disabled=true` 与 `DISABLE_SECURITY_PLUGIN=true` 重复设置冲突 | 删除其中一行 |
| OpenSearch 端口冲突 | 9200/5601 被旧项目占用 | 改为 9201/9601/5602，容器名加 `ragent-` 前缀 |

---

### 三、功能验证与修复

| 问题 | 原因 | 修复 |
|------|------|------|
| 检索返回 0 结果（404） | 切换 opensearch 后未重新摄入，索引不存在 | 重新摄入文档（方案 B） |
| 混合检索重启后失效 | `pipelineReady` 是内存标志，重启归零，需摄入才能恢复 | 新增 `@PostConstruct` 启动时自动检测 pipeline |

---

### 四、当前状态

- 分支：`feature/opensearch-and-rbac`
- 向量引擎：`opensearch`（混合检索已就绪，权重 0.5:0.5）
- RBAC：代码就绪，需执行 `resources/database/upgrade_v1.1_to_v1.2.sql` 建表后可用
- 待办：将本分支合并回 `main`

---

### 五、关键配置说明

**OpenSearch Docker 端口（避免与其他项目冲突）**
- API：9201（容器内 9200）
- 性能分析：9601（容器内 9600）
- Dashboards：5602（容器内 5601）
- 容器名：`ragent-opensearch` / `ragent-opensearch-dashboards`

**混合检索权重调整方式**

修改 `application.yaml`：
```yaml
opensearch:
  hybrid:
    vector-weight: 0.5
    text-weight: 0.5
```
用 curl 直接更新 pipeline，重启应用即可生效（无需重新摄入文档）：
```bash
curl -X PUT http://localhost:9201/_search/pipeline/ragent-hybrid-search-pipeline \
  -H "Content-Type: application/json" \
  -d '{"phase_results_processors":[{"normalization-processor":{"normalization":{"technique":"min_max"},"combination":{"technique":"arithmetic_mean","parameters":{"weights":[0.7,0.3]}}}}]}'
```

---

## 2026-04-11 | PR1 — RBAC 安全等级数据层

详情：[`2026-04-11-pr1-rbac-security-level.md`](./2026-04-11-pr1-rbac-security-level.md)

**核心改动**：
- 新增 `sys_dept`（部门），`t_user.dept_id`，`t_role.role_type`（SUPER_ADMIN/DEPT_ADMIN/USER）+ `t_role.max_security_level`（0-3 角色天花板），`t_knowledge_base.dept_id`，`t_knowledge_document.security_level`。
- `KbAccessService` 增加 `isSuperAdmin()`/`isDeptAdmin()`/`getMaxSecurityLevelForKb()`；检索链 `OpenSearchRetrieverService` 支持按 `security_level LTE_OR_MISSING` 过滤。
- `UserProfileLoader` 单次 JOIN 加载 `LoginUser` 身份快照（user + dept + roles）。
- Last-SUPER_ADMIN 不变量：`SuperAdminMutationIntent` sealed interface + `simulateActiveSuperAdminCountAfter` 模拟器。

---

## 2026-04-12 | PR3 — RBAC 前端演示闭环

详情：[`2026-04-12-pr3-rbac-frontend-demo.md`](./2026-04-12-pr3-rbac-frontend-demo.md)

**核心改动**：
- 前端权限单一真相源：`utils/permissions.ts`（`getPermissions` 纯函数 + `usePermissions` hook）、`router/guards.tsx`（`RequireAnyAdmin` / `RequireMenuAccess`）。
- 管理后台新增：部门管理页、KB 共享 Tab（`KbSharingTab`），角色/用户页对 DEPT_ADMIN 自动裁剪为只读或本部门视图。
- Spaces 入口页、`?kbId=` URL 作为空间锁唯一来源，`resetForNewSpace` 防止会话串空间。
- 后端：`SpacesController` 统计 API、`SysDeptController` 部门 CRUD（GLOBAL 硬保护）、KB 创建时 `dept_id` 解析器。
- 验证：`docs/dev/pr3-demo-walkthrough.md`（Mode A UI 闭环）+ `docs/dev/pr3-curl-matrix.http`（Mode B 后端边界 17/18 PASS）。

---

## 2026-04-13 | PR3 收尾 — 权限修复 & 跨部门共享

详情：[`2026-04-13-rbac-permission-fixes.md`](./2026-04-13-rbac-permission-fixes.md)

**核心改动**：
- `t_role_kb_relation.max_security_level` 新增（per-KB 粒度），替代全局 MAX 语义；检索链用 per-KB 解析替代全局 ceiling。
- KB-centric 角色绑定 API（支持跨部门共享）+ 前端 `KbSharingTab` 条件隐藏（DEPT_ADMIN 对非本部门 KB fail-closed）。
- DEPT_ADMIN 可访问 `/admin/roles`（只读）；privilege escalation 守护：`validateRoleAssignment` 禁止分配超出自身 ceiling 的角色/SUPER_ADMIN/DEPT_ADMIN。
- `DashboardController` 补 `@SaCheckRole` 审计修复；fixture 改为幂等。

---

## 2026-04-15 | Upstream 选择性融合

详情：[`2026-04-15-upstream-selective-merge.md`](./2026-04-15-upstream-selective-merge.md)

**核心改动**：
- 对比上游 `nageoffer/ragent` 20 个新 commit，评估 4 个方向后选择性合并高价值低冲突改动。
- `t_message` 新增 `thinking_content`/`thinking_duration` 列，ChatMessage 全链路贯通（存储层就绪，写入端待后续接入）。
- `LLMService.chat(request, modelId)` 重载 + `RoutingLLMService` 路由实现，支持调用方指定模型。
- `ProbeStreamBridge` 替换 `FirstPacketAwaiter` + `ProbeBufferingCallback`（修复上游已知回调乱序 bug + /simplify 发现锁内回放回归并修复）。
- `EnhancerNode`/`EnricherNode`/`ChunkEmbeddingService` 从依赖 infra-ai 内部类改为依赖公开接口（9 处内部依赖 → 0）。
- `RoutingEmbeddingService` 统一用 `executor.executeWithFallback`，移除冗余手动健康检查。
- 17 文件，+320/-423 行（净减 103 行），RBAC/Spaces/OpenSearch/security_level 零改动。

---

## 2026-04-14 | /simplify 审查 + controller 参数名扫雷

详情：本次会话直接在 `main` 合并（PR #3）；审查报告 agent 生成，未单独落文档。

**核心改动**（分 3 commit）：
- `refactor(rbac)`：9 项 /simplify 修复 — `SysDeptServiceImpl` TOCTOU（`@Transactional` + `SELECT FOR UPDATE`）、`sameDept()` helper 去重 5 处不一致 null 判断、`canAssignRole` 前端加 `maxSecurityLevel` 天花板、`KbAccessService.getMaxSecurityLevelsForKbs` 批量解析（单次 DB 查询替代 N 次 Redis hit）、DEPT_ADMIN `getAccessibleKbIds` Redis 缓存化、共享 `SecurityLevelBadge` + `formatDateTime` 组件、清理 narrative 注释与 `java.util.*` FQN。
- `fix(controllers)`：7 controller 显式 `@PathVariable("...")`/`@RequestParam("...")` 补名（Maven 不带 `-parameters` 导致的运行时 `IllegalArgumentException`）。
- `chore`：设计文档/图表 + `.gitignore` 过滤 `.playwright-mcp/`、`log/*.log`、根目录 `*.png`。

**CLAUDE.md 更新**：记录 DB 全清重建命令、pgvector 缺失误报、Spotless 在 `mvn compile` 自动 apply、`@RequiredArgsConstructor + @Qualifier` 安全性（lombok.config 已配 `copyableAnnotations`）、裸标注扫描一行命令。

---

## 2026-04-16 | Suggested Questions + 联调修复 + 链路追踪曝光

详情：[`2026-04-16-suggested-questions.md`](./2026-04-16-suggested-questions.md)

**核心改动**：
- 追问预测功能：每次 RAG 回答完成后独立调 `qwen-turbo` 生成 3 个后续追问，走新 SSE `suggestions` 事件推送，前端渲染为可点击 chip；专用线程池 `suggestedQuestionsExecutor` 隔离主流，失败不影响 `done`。22 Task + TDD（11 单测），覆盖 JSON/markdown/异常/空返回全路径。
- 审计通道：推荐结果合并进 `t_rag_trace_run.extra_data`（新增 `mergeRunExtraData`）；Trace 详情页展示 chip + 挂 `@RagTraceNode("suggested-chat")` 包装节点，瀑布图可识别推荐 LLM 调用。
- 联调修复 3 个 bug：(1) IntentNode `@Data @Builder` 缺 `@NoArgsConstructor` 导致 Redis Jackson 反序列化永远失败，每请求 fallback 重建意图树；(2) `mergeRunExtraData` 用 Gson 导致整数 round-trip 成 `"5228.0"`，Dashboard `CAST(... AS INTEGER)` 炸 —— 换 Jackson + SQL 改 `CAST AS NUMERIC AS BIGINT` 双保险；(3) `bootstrap/pom.xml` 缺 surefire 3.2.5 override（parent pom `@{argLine}` 依赖未装 JaCoCo），TDD 循环才能跑。
- `/simplify` 三 agent 清理 8 条（dedup flatMap、复用 `PromptTemplateUtils.fillSlots` / `<Badge>`、压缩冗长注释、删不可达 `ctx != null` 守护等），净减 7 行。
- 29 文件 +3718/-16（含 spec+plan 2590 行文档），净代码改动约 +1110 行。

**CLAUDE.md 更新**：`extra_data` 读写路径分工（query Gson / merge-write MUST Jackson）、`@Data @Builder` 对 Jackson 需 `@NoArgsConstructor @AllArgsConstructor`、`PromptTemplateUtils.fillSlots` 工具链入 rag 域表。

**follow-ups 新增**：OBS-1（✅ 已完成核心）/ OBS-2（TTL 实测已传，降为一致性）/ OBS-3（`sendEvent(FINISH)` 未包 try/catch 的 taskManager 泄漏窗口，pre-existing 本 PR 放大）。

---

## 2026-04-18 | RBAC 解耦 + 检索侧授权加固（10 PR + 3 hotfix）

详情：[`2026-04-18-rbac-decoupling-authz-hardening.md`](./2026-04-18-rbac-decoupling-authz-hardening.md)
团队回顾：[`../../docs/dev/follow-up/2026-04-18-rbac-refactor-retrospective.md`](../../docs/dev/follow-up/2026-04-18-rbac-refactor-retrospective.md)

**核心改动**：
- **接口隔离**：`KbAccessService` 22 方法上帝对象拆为 7 个 framework port（`AccessScope` / `CurrentUserProbe` / `KbReadAccessPort` / `KbManageAccessPort` / `UserAdminGuard` / `SuperAdminInvariantGuard` / `KbAccessCacheAdmin` + `KbMetadataReader` / `SuperAdminMutationIntent`），同一 impl 实现多端口；老接口 `@Deprecated` 保留（47 调用点延后分批迁移）。
- **反向依赖消灭**：`KbMetadataReader` port 放 framework，impl 放 knowledge 域，`KbAccessServiceImpl` 删 `KnowledgeBaseMapper` / `KnowledgeDocumentMapper` 两字段；9 处跨域查询收敛到 port（含 `listAllKbIds` / `listKbIdsByDeptId` / `filterExistingKbIds` / `filterKbIdsByDept` 等集合能力）。
- **写路径签名加固**：`VectorStoreService.indexDocumentChunks` / `updateChunk` 新增 `kbId + securityLevel` 参数，7 处调用点同批改；OpenSearch mapping 声明 `kb_id: keyword`；`IngestionContext` 扩同名字段；`IndexerNode.insertRows` 的 JsonObject round-trip 丢 metadata 问题消除。
- **检索侧单一真相源**：`AccessScope` 从 `RAGChatServiceImpl` 入口贯通到 channels；`SearchContext.accessibleKbIds` 字段一次性删除，顺手修未登录态 `null ≡ SUPER_ADMIN` 老 bug。
- **纵深防御**：`AuthzPostProcessor(order=0)` 按 kbId 白名单 + `security_level` ceiling + `kbId==null` 三重 fail-closed；ERROR 日志触发即意味着 retriever 过滤失效。
- **`MetadataFilterBuilder` 抽 bean**：`MultiChannelRetrievalEngine.buildMetadataFilters` static 方法销毁，3 处调用迁到注入 bean。
- **3 个 hotfix**：(1) `deleteDocumentVectors` 对 `index_not_found` 幂等；(2) CHUNK-mode 补 `ensureVectorSpace`（原只在 PIPELINE + KB 创建调）；(3) `KnowledgeDocumentServiceImpl.update` 的 `chunk_config` jsonb typeHandler 绕过修复（hybrid updateById + wrapper 清 null）。
- 14 commit，~1900 insertions / ~340 deletions（含 plan + 执行笔记 ~1500 行文档）。

**用户感知**：🟢 编辑文档元数据不再 500（hotfix #3）；🔴 部署前 OpenSearch 索引必须清库重建（老 chunk 缺 `kb_id` 会被 AuthzPostProcessor 全丢）；⚪ 有权问答 chunk 集合完全一致。

**Plan 缺口**：实际 7 处写路径调用（plan 说 6）、`syncChunkToVector` 2 个调用者（plan 说 1）、`VectorChunk.metadata` 字段已存在（plan step 0.5a 空操作）、`MultiChannelRetrievalEngine` `KbAccessService` 注入不能删（plan 过简），全部记入执行笔记。

**CLAUDE.md 建议增补**：`VectorStoreService` 签名约定；`@TableField(typeHandler)` 仅 entity-based CRUD 生效的 jsonb 更新风险；清 OS 索引后的恢复契约（幂等 delete + auto ensureVectorSpace + 老 chunk fail-closed）；`AuthzPostProcessor` ERROR 日志意味着索引漂移。

**遗留 follow-ups**：FU-1 调用点迁移 47 点（P1）/ FU-2 密级强一致补偿（P1）/ FU-3 缓存失效事件化（P2）/ FU-4 非 OpenSearch 后端硬拒（P2）/ FU-5 opscobtest1 的 3 文档重分块（P0 运维）。

---

## 2026-04-21 | Answer Sources PR1 + PR2 — 元数据管道 + 编排/SSE/前端路由骨架

详情：[`2026-04-21-answer-sources-pr1-pr2.md`](./2026-04-21-answer-sources-pr1-pr2.md)

**核心改动**：
- PR1 (commit `96b2d06`)：`RetrievedChunk.docId`/`chunkIndex` 两字段 + `OpenSearchRetrieverService` 回填 + `VectorMetadataFields.DOC_ID`/`CHUNK_INDEX` 常量 + `KnowledgeDocumentService.findMetaByIds` 批量接口（+ `DocumentMetaSnapshot` record）。`RetrievedChunk.@EqualsAndHashCode(of="id")` 修复 `DefaultContextFormatter.java:103` + `RAGChatServiceImpl.java:194` 两处 `.distinct()` 的 latent bug（同 id 不同 score 未去重膨胀 prompt context）。
- PR2（15 commit）：5 新后端类（`SourceCard`/`SourceChunk`/`SourcesPayload` DTOs + `SourceCardsHolder` set-once CAS 容器 + `SourceCardBuilder` 纯聚合 `@Service` + `RagSourcesProperties`）+ 5 改后端类（`SSEEventType.SOURCES` / `StreamChatHandlerParams.cardsHolder @Builder.Default @NonNull` / `StreamChatEventHandler.trySetCards`+`emitSources` 机械 delegate / `RAGChatServiceImpl` 三层闸门编排 / `application.yaml` 加 `rag.sources` 块）+ 前端 3 改（types / `useStreamResponse` case / `chatStore` 提取 `createStreamHandlers` 工厂 + `onSources` guard）+ vitest+jsdom 首次引入 + 26 单测（21 后端 + 5 前端，全绿）。
- **SSE 契约**：`META → SOURCES → MESSAGE+ → FINISH → (SUGGESTIONS) → DONE`。SOURCES 由 orchestrator 同步 emit（不走 handler 生命周期回调），保证在 LLM 流启动前到位。
- **架构 invariants**：(1) orchestrator 决策 / handler 机械发射；(2) set-once CAS 替代 ThreadLocal 做同步→异步数据传递；(3) sources 判定锚点统一 `distinctChunks.isEmpty()`（严禁 `ctx.isEmpty()` 或 `hasMcp`）；(4) "真早返回"（歧义/SystemOnly/空检索）vs "继续回答跳过 sources"（flag off / distinctChunks 空 / cards 空）严格区分。
- **feature flag**：`rag.sources.enabled=false` 默认，PR2-PR4 期间静默零可见变化，PR5 翻 true 上线。

**流程教训**：(1) stacked PR 陷阱（PR #13 base=PR1 分支没回到 main，需 PR #14 catch-up）；(2) `mvn compile -q` stale bytecode 假阳性（T5 漏网错 import，需 `mvn clean compile` 兜底）；(3) subagent 类路径/签名"读真源"不"信 spec"（T7 多处 spec vs 代码冲突，implementer 主动纠正）；(4) 闭包 handlers 提取为 module-level 工厂解锁测试可达性（`chatStore.createStreamHandlers` 模式）。

**PR 列表**：[#12](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/12) (PR1→main) / [#13](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/13) (PR2→PR1) / [#14](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/14) (catch-up→main)

**Follow-ups**：见 `docs/dev/followup/backlog.md` 新增 7 条（kbName additive / onSuggestions guard 缺失 / handler ThreadLocal 残留 / 写路径字面量扫荡 / Milvus-Pg retriever 回填 / npm lint break / 歧义矩阵行没显式单测）。

---

## 2026-04-22 | Answer Sources PR3 — Prompt 改造 + 引用渲染 + 质量埋点

详情：[`2026-04-22-answer-sources-pr3.md`](./2026-04-22-answer-sources-pr3.md)

**核心改动**：
- 后端（5 改 + 4 测试）：`PromptContext.cards` 字段；`RAGPromptService` citation mode（内部派生 `resolvedKbEvidence = buildCitationEvidence(ctx)` + `resolvedSystemPrompt = appendCitationRule(base, cards)`，签名零变化）；新 `CitationStatsCollector`（静态工具，`scan(answer, cards) → (total, valid, invalid, coverage)`，`valid` 用 `indexSet.contains(n)` 非 `1..N`）；`StreamChatEventHandler.onComplete` 在 `updateTraceTokenUsage`（overwrite）**之后**插 `mergeCitationStatsIntoTrace()` 走 `mergeRunExtraData(traceId, Map)` 合写；`RAGChatServiceImpl.streamLLMResponse` 多接 `List<SourceCard> cards` 参数 threaded 进 `PromptContext`。
- 前端（4 新 + 3 改）：`utils/citationAst.ts`（SSOT：CITATION 正则 + SKIP_PARENT_TYPES 5 元素）+ `utils/remarkCitations.ts`（mdast 三段 visit：footnoteReference→cite / footnoteDefinition→删 / text→CITATION 切片）；`CitationBadge.tsx`（蓝色 `<sup>`，`indexMap.get(n)` miss 降级为纯文本）；`Sources.tsx`（forwardRef，忠实渲染后端 cards 原序，`highlightedIndex` 触发 auto-expand）；`MarkdownRenderer` 以 `hasSources` **单一闸门对称 gate** `remarkPlugins` 和 `components.cite`（守 PR2 rollback 字节级契约）；`MessageItem` 承接 citation click → scrollIntoView + 1500ms 高亮 + unmount cleanup。
- **架构 invariants**：(1) 零 ThreadLocal 新增（`onComplete` 用构造期 final traceId 字段）；(2) 后端 `indexSet.contains(n)` / 前端 `indexMap.get(n)` 永不用 `cards[n-1]`；(3) `mergeRunExtraData` 必须在 `updateTraceTokenUsage` 之后（`Mockito.InOrder` 锁）；(4) `remarkPlugins` 顺序硬固定 `[remarkGfm, remarkCitations]`；(5) `remarkCitations` 内部三段 visit 顺序（footnoteReference → footnoteDefinition → text）不能颠倒。
- **SSE 契约零变化**：`citationStats` 落 `t_rag_trace_run.extra_data`，不走 SSE。
- **feature flag**：`rag.sources.enabled=false` 默认；flag off 或 `hasSources=false` 时与 PR2 末态**字节级等价**，`MarkdownRenderer.test.tsx` case (a) 显式锁此契约。
- Review round-1 补丁（commit `366a0b1`）：`CitationStatsCollector` SENTENCE 粗切注释 + 3 条测试补全（handler 非空无引用走完循环 / Sources 动态 rerender / MessageItem unmount cleanup）。
- 测试总数：后端 18 新（7+6+4+1 扩）+ 前端 24 新 = 42 用例全绿。
- **SRC-6 闭环**：本 PR §2.8 补 `streamChat_whenGuidanceClarificationPrompt_thenSkipSourcesEntirely` 锁 guidance 分支跳过 sources。
- **SRC-9 新增 backlog**：`updateTraceTokenUsage` overwrite latent 坑（当前规避靠 InOrder 顺序）。

**PR**：[#15](https://github.com/ZhongKai-07/rag-knowledge-dev/pull/15) → main（commit `c462de7`）；20 commits，+4453/-16 行（大部分是 spec + plan ~3000 行设计文档）。

**Follow-ups**（留给 PR4/PR5）：N-3 `MarkdownRenderer.components` useMemo / N-4 `remarkCitations.test.ts` 改 unified pipeline / N-5 `CITATION_HIGHLIGHT_MS` 抽常量 / N-6 `Sources.visible` identity alias 清理。

---

## 2026-04-19 | KB 删除级联回收

详情：[`2026-04-19-kb-delete-cascade.md`](./2026-04-19-kb-delete-cascade.md)

**核心改动**：
- `FileStorageService` 增加 `ensureBucket` / `deleteBucket`，`S3FileStorageService` 收拢 bucket 生命周期管理。
- `VectorStoreAdmin` 增加 `dropVectorSpace`，OpenSearch 支持幂等删除，Milvus/Pg 明确标记不支持生产删除。
- `KnowledgeBaseServiceImpl.delete()` 增加 role-KB 解绑、事务后回收 OpenSearch index / S3 bucket，并补齐删除单测与缓存失效测试。
- `t_knowledge_base.uk_collection_name` 调整为 `(collection_name, deleted)`，新增 `upgrade_v1.4_to_v1.5.sql`，修复“软删后无法重建同 collectionName KB”的预存 schema 缺陷。
