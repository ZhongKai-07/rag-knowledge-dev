# 2026-04-25 | PR E2 — RAG 评估闭环合成闭环

分支：`feature/eval-e2-synthesis`（基于 `main@96b9f3a`）

## 背景 / 目标

PR E1（已合入 `main`）只有地基：4 张 `t_eval_*` 表、Python FastAPI 微服务 `ragent-eval`（`/health`、`/synthesize`）、Java eval 域的空骨架（`EvalProperties`、`EvalAsyncConfig.evalExecutor`、`RagasEvalClient`、4 DO + 4 Mapper）。

本 PR 落合成闭环：从 KB 抽 chunk → 百炼 qwen-max 合成 Q-A → 落 `t_eval_gold_item`（含 `source_chunk_text` 字节级快照）+ 前端 `/admin/eval-suites` 人审 + dataset 激活。覆盖 spec §6.2（合成流程）、§7.2 剩余 service、§11 前端 Tab 1。

硬停止点（本 PR 不做，留 PR E3）：`AnswerPipeline` / `ChatForEvalService` / `EvalRunExecutor` / 结果看板 / 查询侧 redaction（EVAL-3）/ legacy `@Async` 失效修复（EVAL-2）。

## 核心决策复述（与 spec 一致）

- **跨域 port**：`KbChunkSamplerPort`（`framework.security.port`）+ `KbChunkSamplerImpl`（`knowledge` 域）替代直读 `t_knowledge_chunk`。SQL 固化 spec §6.1（`c.doc_id`、`d.doc_name`、`enabled=1`、`status='success'`、`ORDER BY c.doc_id, RANDOM()`）；per-doc dedup 在 Java 侧做。
- **合成分批**：Java 按 `rag.eval.synthesis.batch-size=5` 拆 Python 调用，独立 timeout `synthesis-timeout-ms=600000`（与 `python-service.timeout-ms=120000` 分离）。
- **异步 + 进度**：`GoldDatasetSynthesisService.trigger` 提交到 `evalExecutor`；`SynthesisProgressTracker` 进程内 `ConcurrentHashMap`，前端 2s 轮询；`tracker.tryBegin` 用 `putIfAbsent` 做原子占坑防并发 race。
- **权限**：eval 所有 controller 类级 `@SaCheckRole("SUPER_ADMIN")`（EVAL-3 未落地前不降级）。
- **状态机**：Dataset DRAFT → ACTIVE → ARCHIVED；`activate` 前置 `approved ≥ 1 AND pending == 0`（否则 PENDING 条目永久锁死）；`delete` 走 `@Transactional` 级联软删 item。Item PENDING → APPROVED/REJECTED（仅 DRAFT 可改）。
- **快照冻结**：Java 侧采样到的 `chunk.content / doc.doc_name / doc.id / chunk.id` 直接入 `t_eval_gold_item`；Python 不返回这 4 字段；Java 侧还做 blank q/a 二次 fail-closed 校验。
- **零新增 ThreadLocal / 不用 @Async**：全部通过方法参数 / `@Qualifier("evalExecutor").execute(Runnable)` 传递。

## 本次改动

共 46 files changed, ~6045 insertions(+), 18 deletions(-)。

### Java 新增

- `framework/.../security/port/KbChunkSamplerPort.java`（port）
- `bootstrap/.../knowledge/dao/mapper/KbChunkSamplerMapper.java` + `service/impl/KbChunkSamplerImpl.java`（port impl + 测试）
- `bootstrap/.../eval/domain/SynthesisProgress.java`（record）
- `bootstrap/.../eval/service/SynthesisProgressTracker.java`（@Component）
- `bootstrap/.../eval/service/{GoldDatasetService,GoldDatasetSynthesisService,GoldItemReviewService}.java` + 对应 `impl/`
- `bootstrap/.../eval/controller/{GoldDatasetController,GoldItemController}.java`（类级 SUPER_ADMIN）
- `bootstrap/.../eval/controller/request/` 4 个 DTO（`CreateGoldDatasetRequest / TriggerSynthesisRequest / ReviewGoldItemRequest / EditGoldItemRequest`）
- `bootstrap/.../eval/controller/vo/` 3 个 VO（`GoldDatasetVO / GoldItemVO / SynthesisProgressVO`）

### Java 修改

- `EvalProperties.Synthesis` 追加 `batchSize` + `synthesisTimeoutMs`
- `application.yaml` 对应加 `batch-size: 5` + `synthesis-timeout-ms: 600000`
- `RagasEvalClient.buildClient(int timeoutMs)` 加 per-call timeout；`synthesize()` 走 `synthesis-timeout-ms`，`health()` 保持 `python-service.timeout-ms`

### Java 测试

- `KbChunkSamplerImplTest`（`@SpringBootTest` + 真 PG fixture，2 test）
- `GoldDatasetServiceImplTest`（纯 Mockito + `TableInfoHelper.initTableInfo` + `renderWrapper` 辅助路由，5 test）
- `GoldDatasetSynthesisServiceImplTest`（纯 Mockito + 冻结快照断言，5 test）
- `GoldItemReviewServiceImplTest`（纯 Mockito，4 test）
- `EvalPropertiesTest` 增补 `batchSize` + `synthesisTimeoutMs` 断言

### 前端新增

- `frontend/src/services/evalSuiteService.ts`
- `frontend/src/pages/admin/eval-suites/EvalSuitesPage.tsx`（3 Tab 容器，URL 同步 `?tab=gold-sets|runs|trends`）
- `frontend/src/pages/admin/eval-suites/tabs/{GoldSetListTab,GoldSetReviewPage}.tsx`
- `frontend/src/pages/admin/eval-suites/tabs/placeholders/{RunsPlaceholderTab,TrendsPlaceholderTab}.tsx`
- `frontend/src/pages/admin/eval-suites/components/{CreateGoldSetDialog,TriggerSynthesisDialog,SynthesisProgressDialog}.tsx`

### 前端修改

- `utils/permissions.ts` `AdminMenuId` 追加 `"eval-suites"`
- `router.tsx` 加 `/admin/eval-suites` 和 `/admin/eval-suites/datasets/:datasetId/review` 两条路由（`<RequireSuperAdmin>` 包裹）
- `pages/admin/AdminLayout.tsx` 侧栏加"质量评估"（`FlaskConical` icon）+ `breadcrumbMap`

### Docs

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md` — 追加 PR E2 落地清单 + 已知边界
- `bootstrap/CLAUDE.md` — 在 eval 域补关键类表
- `docs/dev/followup/backlog.md` — 更新 EVAL-3 + 新增 EVAL-4（可恢复性）/ EVAL-TOVO-AGG / EVAL-retry / EVAL-BATCH-DOUBLECOUNT

## 过程决策点

### P1-1. 双构造器 → 单 @Autowired 构造器（codex review 打回）

原计划：`GoldDatasetSynthesisServiceImpl` 给两个构造器（7 arg + 6 arg），让测试走 6-arg 无 executor 路径。问题：Spring 对多构造器组件不会自动选 `@Qualifier` 的那个，启动期可能 `No default constructor`。

修复：保留单一 7-arg `@Autowired` 构造器，`@Qualifier("evalExecutor") ThreadPoolTaskExecutor` 参数允许 `null`（测试传 null 并走 `runSynthesisSync` 同步路径；`trigger()` 遇 null 直接抛 `IllegalStateException`）。

### P1-2. tryBegin 原子占坑

原计划：`trigger()` 只在 `validatePreconditions` 里查 `tracker.isRunning()` 作为并发拒绝。问题：两个并发 request 可能都看到 `existing == 0`（DB 视角）+ `isRunning == false`（tracker 视角），然后各自 `execute`，最终重复合成 / 重复插入。

修复：`SynthesisProgressTracker.tryBegin(datasetId)` 用 `putIfAbsent` 占坑。`trigger()` 的执行顺序：`validatePreconditions → null-executor check → tryBegin → execute`。`validatePreconditions` 不再检查 tracker（避免语义双写）。

### P1-3. `activate` 必须 `pending == 0`

原计划：`activate` 仅检查 `approved ≥ 1`。问题：激活后 `GoldItemReviewService.requireDraftDataset` 禁止改任何 item（整个 review 流程锁死），未审 PENDING 条目永久留在库里。

修复：`activate` 加 `countPending == 0` gate；VO 加 `pendingItemCount`；前端 list UI `canActivate = itemCount > 0 && pendingItemCount === 0`，审核按钮加待审数徽标。

### 补充 1. Java 侧 blank q/a 二次校验

Python 侧 pydantic 校验会挡掉空 q/a，但契约可能漂移。Java 侧不信任 Python 输出，`GoldDatasetSynthesisServiceImpl` 在 items 迭代里再加一轮 `question == null || isBlank` 检查，命中 `failed++; continue;`（不插入）。

### 补充 2. `delete()` 级联软删

原计划：`GoldDatasetServiceImpl.delete` 只 `datasetMapper.deleteById`，子 `t_eval_gold_item` 会变成 orphan（`@TableLogic deleted=0` 但父被软删）。

修复：`delete()` 加 `@Transactional(rollbackFor=Exception.class)`，先 `itemMapper.delete(wrapper.eq(datasetId))` 再 `datasetMapper.deleteById`。

### 前端细节

- `GoldSetListTab` Radix Select 不允许 `value=""`——用 `ALL = "__all__"` sentinel，API 调用前翻译成 `undefined`。
- `SynthesisProgressDialog` 项目没有 `@/components/ui/progress`——自绘 `<div>` 进度条。
- `tsconfig.app.json` 开了 `noUnusedLocals`：`EvalSuitesPage` 删了 `useMemo / useNavigate`；`GoldSetReviewPage` 删了 `useMemo`。

### 测试细节

- `GoldDatasetServiceImplTest` 用 `TableInfoHelper.initTableInfo(...)` 预热 MyBatis-Plus lambda cache（与 `ConversationMessageServiceSourcesTest` 同套路）；`renderWrapper(sql + " :: " + paramValues)` 辅助按 SQL 片段路由 `selectCount` 返回值。
- `GoldDatasetServiceImplTest.create` 用 `when(mapper.insert(any())).thenAnswer(...)` 模拟 MP 的 `ASSIGN_ID` 行为（mock mapper 不自动填 `@TableId`）。
- `GoldDatasetSynthesisServiceImplTest` 用 `argThat((GoldItemDO i) -> ...)`（静态导入 `ArgumentMatchers.argThat`）匹配特定 chunk_id；`client.synthesize` 用 `.thenAnswer(inv -> ...)` 模拟多批次 HTTP。

## 验证

### Java 单测

- `KbChunkSamplerImplTest`（2）、`GoldDatasetServiceImplTest`（5）、`GoldDatasetSynthesisServiceImplTest`（5）、`GoldItemReviewServiceImplTest`（4）、`EvalPropertiesTest`（1 扩展）全绿。
- `mvn -pl bootstrap spotless:check` 清洁；无新增 pre-existing baseline 失败。
- 启动冒烟（compile + spotless）通过；未做真实 HTTP startup（可选）。

### Python

- PR E1 的 7/7 pytest 不受影响（本 PR 未改 Python 代码）。

### 前端

- `./node_modules/.bin/tsc --noEmit` 零错误。
- `npm run build` 未跑（留 Task 15）。

### E2E（待 Task 15 做）

完整流程：SUPER_ADMIN 登录 → `/admin/eval-suites` → 创建 `smoke-e2` dataset → 触发合成 5 条 → 进度对话框 COMPLETED → 审核 y/n/e → 激活 → 归档 → 删除；DB 核查 `t_eval_gold_item.source_chunk_text` 与原 KB chunk 字节级一致。

## 回滚

本 PR 新代码全在 `eval/` 域 + `knowledge.KbChunkSamplerImpl` + 前端 `eval-suites/` 目录。revert 整批 commit 即可；`t_eval_*` 表结构无变化（v1.10 schema 保留，PR E1 已落）。`bootstrap/CLAUDE.md` / `eval/CLAUDE.md` / `backlog.md` 的 diff 在回滚中会一并撤销。

## 后续

- PR E3-spike：验证从 `streamChat` 抽 `AnswerPipeline` 工程量
- PR E3：`ChatForEvalService` + `EvalRunExecutor` + `/evaluate` 端点 + 结果看板 + 趋势
- EVAL-3：eval 读接口 redaction（PR E3 放开 `AnyAdmin` 前必须前置）
- EVAL-4：合成任务可恢复性（单机无感，k8s 前再做）
- EVAL-TOVO-AGG：`toVO` 聚合优化（list > 50 行时触发）
- EVAL-retry：`RagasEvalClient` HTTP retry（真评估运行上线前）
