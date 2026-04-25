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
