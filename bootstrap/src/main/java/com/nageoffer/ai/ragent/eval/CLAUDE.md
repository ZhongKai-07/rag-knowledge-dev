# eval 域（RAG 评估闭环）

独立 bounded context，不属于 `rag/` 域。见设计文档 `docs/superpowers/specs/2026-04-24-rag-eval-closed-loop-design.md`。

## 职责

- Gold Set 管理（合成 / 审核 / 激活）
- 评估运行调度 + 结果聚合
- 调 Python `ragent-eval` 服务跑 RAGAS 四指标

## 依赖方向（硬约束）

```
eval/  → rag.core.ChatForEvalService   (✓ 合法 port，PR E3 引入)
eval/  → knowledge.KbReadAccessPort     (✓ 合法 port)
eval/  → framework.*                    (✓ 合法)

eval/  ← ⛔ rag/ 不得依赖 eval
eval/  ← ⛔ 读/写 rag/ 内部表
```

## 目录结构

```
eval/
├── controller/   REST 入口（PR E2+）
├── service/      业务编排（PR E2+）
├── async/        EvalAsyncConfig —— evalExecutor bean
├── dao/          4 个 DO + Mapper
├── domain/       DTOs
├── client/       RagasEvalClient
└── config/       EvalProperties
```

## 关键 Gotchas（本域专属，通用坑点见 `docs/dev/gotchas.md`）

1. **零 ThreadLocal 新增**：所有跨方法/跨线程状态走参数 / record / DO。违反示例：
   - `class EvalRunContext extends ThreadLocal<EvalRun>` ❌
   - `RagasEvalClient` 里读 `RagTraceContext.traceId` ❌
   - `evalExecutor + TaskDecorator 续 UserContext` ❌

2. **`@MapperScan` 必须包含 eval.dao.mapper**：在 `RagentApplication.@MapperScan` 里显式写上，否则启动期 `UnsatisfiedDependencyException`。

3. **不使用 `@Async` 注解**：项目主启动类没有 `@EnableAsync`。eval 域一律用 `@Qualifier("evalExecutor")` 注入 + 显式 `execute()`。

4. **系统级 `AccessScope.all()` 仅 SUPER_ADMIN 手动触发合法**（PR E3 起生效）：扩展到定时任务 / 部门管理员 / 回归守门等场景前**必须**重新做权限模型。

5. **评估读接口一律 SUPER_ADMIN**（PR E2+ 起生效）：`t_eval_result.retrieved_chunks` 是系统级检索产物，含跨 `security_level` 内容；直到 EVAL-3（查询侧 redaction）落地前不得降级为 `AnyAdmin`。

## 配置

`application.yaml` 下 `rag.eval.*`（见 `EvalProperties`）。

## 测试

- 配置绑定：`EvalPropertiesTest`（纯 Binder，无 Spring context）
- 线程池：`EvalAsyncConfigTest`（纯 bean 构造）
- Mapper 装配：`EvalMapperScanTest`（`@SpringBootTest`）
- 采样 port：`KbChunkSamplerImplTest`（`@SpringBootTest` + 真 PG fixture）
- Dataset 状态机：`GoldDatasetServiceImplTest`（纯 Mockito + `TableInfoHelper.initTableInfo`）
- 合成编排：`GoldDatasetSynthesisServiceImplTest`（纯 Mockito + 冻结快照断言）
- 审核：`GoldItemReviewServiceImplTest`（纯 Mockito）

## PR E2 已落地（2026-04-25）

- **Gold Dataset 三态机**：`GoldDatasetService`（DRAFT → ACTIVE → ARCHIVED）；`activate()` 前置 `approved ≥ 1 AND pending == 0`（否则 PENDING 条目会被后续 `GoldItemReviewService.requireDraftDataset` 永久锁）；`delete()` 走 `@Transactional(rollbackFor=Exception.class)` 级联软删子 item，避免 orphan。
- **合成闭环**：`GoldDatasetSynthesisService.trigger(datasetId, count, principalUserId)` 异步提交到 `evalExecutor`；`SynthesisProgressTracker.tryBegin()` 用 `putIfAbsent` 做原子占坑防并发 race；默认 batchSize=5 分批调 Python `/synthesize`；`synthesize` 单批 timeout 600s（`rag.eval.synthesis.synthesis-timeout-ms`），与 `pythonService.timeoutMs=120s` 分离。
- **跨域 port**：`framework.security.port.KbChunkSamplerPort` + `knowledge.service.impl.KbChunkSamplerImpl`（`@Select` 固化 spec §6.1 SQL；Java 侧做 per-doc dedup），替代直读 `t_knowledge_chunk`。
- **source_chunk_text 字节级冻结**：合成时 Java 侧 JOIN 查出 `chunk.content`，入库前不 trim/清洗/截断；Python 不返回该字段。`GoldItemReviewServiceImpl` 同样不允许改 source 快照字段。
- **所有 controller `@SaCheckRole("SUPER_ADMIN")`**：`GoldDatasetController` + `GoldItemController` 类级控制，读写不分；EVAL-3 落地前不得降级。
- **零新增 ThreadLocal**：`principalUserId` 纯方法参数；`evalExecutor.execute(Runnable)` 内不读 UserContext。
- **前端 `/admin/eval-suites`**：单页三 Tab（黄金集完整实现 + 评估运行 / 趋势对比占位）；审核页 y/n/e 快捷键，侧栏"质量评估"（`FlaskConical` icon）与 legacy "评测记录"（`ClipboardCheck`）并列独立入口。

## PR E2 已知边界（见 backlog EVAL-4）

- `SynthesisProgressTracker` 进程内非持久；后端重启后 RUNNING 状态丢失，部分 `t_eval_gold_item` 可能已落库。UI 按 `totalItemCount > 0` 隐藏"合成"按钮，用户只能 delete dataset 重来。
- `toVO()` 每 dataset 3 次 `selectCount`：在 list 返回 > ~50 行时值得改为单聚合 SQL（见 backlog EVAL-TOVO-AGG）。
- HTTP batch 级 failure 目前 `failed += batch.size()`，transient blip 会把整批标失败；真正的 retry 策略见 backlog EVAL-retry（由 `RagasEvalClient` 消费 `pythonService.max-retries`）。

## PR E3 已落地（2026-04-26）

- **AnswerPipeline 不抽**（ADR `docs/dev/design/2026-04-25-answer-pipeline-spike-adr.md`）：`ChatForEvalService`（`rag/core/`）直接组合现有 6 个 RAG service bean（`queryRewriteService` / `intentResolver` / `guidanceService` / `retrievalEngine` / `promptBuilder` / `llmService`）。`streamChat` 字节级不变。
- **三态状态机**：`SUCCESS`（全成）/ `PARTIAL_SUCCESS`（黄）/ `FAILED`（红），由 `EvalRunExecutor.decideStatus(succeeded, failed)` 强制。`flushBatch` 返 `BatchOutcome(succ, fail)` record；HTTP 整批失败 + per-item error 非空 + per-item missing 三类全部进 `failed_items`（review P1-2）。
- **`max-parallel-runs=1` 硬 enforce**（review P1-4）：`EvalRunServiceImpl.startRun` 用 `ReentrantLock startRunLock` 包住 SELECT COUNT + INSERT + executor.execute；并发 startRun 不会越过 count 检查。多实例部署需在 `t_eval_run` 加部分唯一索引，类字段注释里给方案。
- **`AccessScope.all()` 唯一合法持有者**（review P1-1，spec §15.3）：`EvalRunExecutor.runInternal` 显式构造 `AccessScope systemScope = AccessScope.all()` 并传给 `chatForEvalService.chatForEval(scope, kbId, q)`；`ChatForEvalService` 永不自造 scope。其他入口必须传调用 principal 的真实 scope。
- **`system_snapshot` 单一真相源**：`SystemSnapshotBuilder` 写入 `recall_top_k / rerank_top_k / sources_enabled / sources_min_top_score / eval_sources_disabled / chat_model / embedding_model / rerank_model`，附 sha256 `config_hash` 便于 dedup（hash 仅基于 8 个 RAG-behavior 字段，不含 `git_commit` 元数据）。`eval_sources_disabled=true` 是常量声明 eval 链路 `cards=List.of()` 关闭 citation；新加任何影响 RAG 的配置必须同步加字段（review checklist 必查）。
- **EVAL-3 redaction 落地**：`EvalResultRedactionService` 是 result 读端点的硬合并门禁。当前所有 controller 类级 `@SaCheckRole("SUPER_ADMIN")` + `Integer.MAX_VALUE` ceiling = 全读。未来放 AnyAdmin 只需把 ceiling 换成 `UserContext.getMaxSecurityLevel()`，不改 service 契约。`securityLevel == null` 视为 0（最低密级，永远可读）。
- **API split（review P1-3）**：list `/runs/{runId}/results` 返 `EvalResultSummaryVO[]`（`.select()` projection 不带 chunks）；drill-down `/runs/{runId}/results/{resultId}` 返 `EvalResultVO`（经 redaction 的 chunks）。`EvalResultSummaryVO` 类型上不含 `retrievedChunks` 字段——契约即门禁，零绕过路径。drill-down 端点 `selectOne(eq(id, resultId).eq(runId, runId))` 双 filter 防 URL 错位拉到别 run 的结果。
- **零 ThreadLocal 新增**：`MDC.put("evalRunId", runId)` 仅做日志关联（非业务状态），方法 finally `MDC.remove`；`X-Eval-Run-Id` HTTP header 透传到 Python 侧。
- **DTO field naming**：Java→Python `/evaluate` 字段是 `result_id`（`EvaluateRequest.Item.resultId` / `EvaluateResponse.MetricResult.resultId`），不是 `gold_item_id`——它carries the `EvalResultDO` snowflake，便于 batch 内多条同 goldItem 也能精确回填指标。
- **Python `/evaluate`**：4 metric（faith / ans-rel / ctx-prec / ctx-rec）；单条失败 `error` 字段填充不抛；空 items 短路。本地 `ragas` 包名与 PyPI 同名 → `evaluate.py` 用 `_load_pypi_ragas_symbols()` 在 module load 时手工剥离 `sys.modules` shadowing 后导入 PyPI ragas API。
- **前端**：`EvalRunsTab` 替换 placeholder（含 RUNNING 行 2s 轮询）；`EvalRunDetailPage` 4 卡 + 4 直方图 + per-item 表 + drill-down 抽屉（实际用 Dialog——sheet 组件不在 UI 库）；`EvalTrendsTab` recharts 折线 + `SnapshotDiffViewer` JSON diff。
- **测试**：`ChatForEvalServiceTest` 5 / `SystemSnapshotBuilderTest` 3 / `RetrievedChunkSnapshot` n.a. / `EvaluateRequest/Response` n.a. / `EvalPropertiesTest` 3 / `RagasEvalClientEvaluateTest` 2 (WireMock) / `RoutingLLMServiceSyncFallbackTest` 2 (infra-ai) / `EvalRunServiceImplTest` 5 (incl 8-thread concurrent) / `EvalRunExecutorTest` 5 / `EvalResultRedactionServiceTest` 5 / `EvalRunControllerRedactionTest` 5 (standalone MockMvc) / `test_evaluate.py` 4 (Python pytest)。

## PR E3 已知边界

- `evaluate` HTTP 失败→整 batch 标失败（无 retry，复用 backlog EVAL-retry 后续修；同模式合成侧 PR E2 已记录）
- `metricsSummary` 是 single-pass 累加；后续若加 quantile / 标准差需扩 schema
- `EvalRunDetailPage` 直方图固定 5 桶；如需细粒度可加 props
- `git_commit` 字段当前固定为 `"unknown"`（项目无 `git-commit-id-maven-plugin`）；不影响 hash semantics（已从 hash 输入剔除）但 trend 页 `SnapshotDiffViewer` 不会显示真 commit hash
- T14（Python smoke + Spring Boot startup）+ T19（端到端 UI 验证）需要 docker compose + 百炼 API key + dev DB 含 ACTIVE dataset；这是 human-in-loop 验证，不在 agent 范围
