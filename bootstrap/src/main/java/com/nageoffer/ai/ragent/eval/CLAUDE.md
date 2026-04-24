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
