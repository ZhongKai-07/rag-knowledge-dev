# 2026-04-24 | PR E1 — RAG 评估闭环地基（eval 域骨架 + Python ragent-eval 微服务）

Merge: `d3a7fd6c` (PR #19)
分支提交：15 个 commit（14 个 feat/docs + 1 个 fix），按 14-task 实施计划逐步推进

## 背景

项目之前的"RAGAS 评估"只是 `RagEvaluationServiceImpl.saveRecord` 把 query-chunk-answer 三元组落 `t_rag_evaluation_record`，然后走 `GET /rag/evaluations/export` 导出 JSON 给外部 Python 脚本（`ragas/run_eval.py`）批处理。**调优闭环是断的**：想知道改了 rerank / prompt / embedding 指标涨跌，得手动导出→手动跑脚本→手动对比，实际上没人跑。

本 PR 起做 spec v4 规划的三段式 RAG 评估闭环（E1 地基 / E2 合成闭环 / E3 评估闭环）的第一段。E1 只做骨架，不上任何业务能力。

## 核心决策（spec v4 固化）

- **独立顶级 bounded context `bootstrap/.../eval/`**，不挂 `rag/` 下。理由：评估消费 rag 但不属于 rag；未来 DDD 拆分时 eval 可以独立抽出。依赖方向硬约束：`eval/ → rag.core` / `knowledge.KbReadAccessPort` / `framework.*`（✓），`rag/ → eval/` ❌，`eval/` 直读 rag 表 ❌
- **Python 服务化**：`ragas/` 目录从一次性脚本改造成 FastAPI 微服务 `ragent-eval`（:9091），端点 `/health` + `/synthesize`（PR E3 再加 `/evaluate`）。容器化 + compose 就位
- **零新增 ThreadLocal**：`EvalAsyncConfig.evalExecutor` 是 `ThreadPoolTaskExecutor`，**不**用 TaskDecorator 续 UserContext；`RagasEvalClient` 不读 `RagTraceContext`；所有跨方法状态走 record / DO / 方法参数
- **不用 `@EnableAsync`**：项目主启动类 `RagentApplication` 没开 `@EnableAsync`（legacy `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 因此是失效 bug → backlog EVAL-2）。eval 域一律 `@Qualifier("evalExecutor") + .execute(Runnable)`，避免无意间让 legacy 失效 `@Async` 突然生效
- **SUPER_ADMIN-only 读权限**（PR E2+ 生效）：`t_eval_result.retrieved_chunks` 是系统级检索产物含跨 `security_level` 内容；开放 AnyAdmin 读的前置是"查询侧 redaction"（backlog EVAL-3）
- **系统级 `AccessScope.all()` 仅 SUPER_ADMIN 手动触发合法**（PR E3 生效）：扩展到定时任务 / 部门管理员前必须重做权限模型

## PR E1 范围（14 Task）

### DB（Task 1）
- `resources/database/upgrade_v1.9_to_v1.10.sql` 新增 4 张表：`t_eval_gold_dataset` / `t_eval_gold_item` / `t_eval_run` / `t_eval_result`
- `t_eval_result` 刻意设计为硬删表（无 `deleted` / `updatedBy` / `updateTime`），immutable audit log 语义
- `schema_pg.sql` 同步追加；根 `CLAUDE.md` 升级脚本表加 v1.10 一行

### Java 配置 + 异步（Task 2-3）
- `EvalProperties`（`rag.eval.*`）：9 个字段分 `pythonService / synthesis / run` 三组；`@ConfigurationProperties` + Binder 单测
- `EvalAsyncConfig.evalExecutor`：`ThreadPoolTaskExecutor`（core=2, max=4, queue=50, prefix="eval-exec-", waitForTasksToCompleteOnShutdown=true）

### Java 数据层（Task 4-5）
- 4 个 DO + 4 个 BaseMapper 接口（纯 boilerplate，camelCase → snake_case 靠 MyBatis Plus 默认转换）
- `RagentApplication.@MapperScan` 加 `com.nageoffer.ai.ragent.eval.dao.mapper`，`EvalMapperScanTest` 用 `@SpringBootTest` 断言 4 个 bean 非 null（Gotcha #14 锁死）

### Java 契约层（Task 6-7）
- 4 个 Synthesize DTO record，跨 HTTP 字段全部用 `@JsonProperty` 显式 snake_case 映射（`docName → doc_name`, `sourceChunkId → source_chunk_id`, `failedChunkIds → failed_chunk_ids`, `ragasVersion / evaluatorLlm`）—— Jackson 默认不做 camelCase ↔ snake_case 转换
- `RagasEvalClient`：`@Component` + RestClient 骨架，`synthesize()` + `health()` + `HealthStatus` record；每次 `buildClient()` 新建实例（无状态、无 ThreadLocal）

### Java 文档（Task 8）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md`：域说明 + 依赖方向约束 + 5 条本域 gotchas
- `bootstrap/CLAUDE.md`：加 `### eval 域` 段 + legacy `RagEvaluationServiceImpl` 警告（"不是新评估域入口"）

### Python（Task 9-11）
- `ragas/ragas/settings.py`：pydantic-settings 从 env 读 `DASHSCOPE_API_KEY`、`EVALUATOR_CHAT_MODEL`、`SYNTHESIS_STRONG_MODEL` 等
- `ragas/ragas/synthesize.py`：`synthesize_one(chunk, client, model) -> SynthesizedItem`，强模型（qwen-max）吐 JSON envelope，fail-fast SynthesisError；SYNTHESIS_PROMPT 模板里示例 JSON 的 `{}` 必须 `{{}}` 双花括号转义（`.format()` 坑）
- `ragas/ragas/app.py`：FastAPI，`/health` + `/synthesize`（混合批，单条失败放 `failed_chunk_ids`，整体不失败）；`@lru_cache` on `_settings()` / `_llm_client()`

### 打包 + 部署（Task 12-13）
- `ragas/requirements.txt`（runtime）+ `requirements-dev.txt`（`-r` 叠加）+ `Dockerfile`（Python 3.11-slim，带 build-essential for ragas wheel + HEALTHCHECK）+ `.dockerignore`
- `resources/docker/ragent-eval.compose.yaml`：build 本地、端口 9091、环境变量 `DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}` 从 shell 注入
- `.env.example`：仅一个 `DASHSCOPE_API_KEY=` 占位；`launch.md` 加可选启动段

### E2E Smoke（Task 14）
- `docker compose up ragent-eval` → `curl /health` 200 → `curl /synthesize` 真打百炼 qwen-max 返回合理 Q-A
- `mvn -pl bootstrap spring-boot:run` 启动无 `UnsatisfiedDependencyException`
- Java 回归：`EvalPropertiesTest` / `EvalAsyncConfigTest` / `EvalMapperScanTest` 4 tests 全绿
- Python 回归：7/7 pytest 全绿（settings ×2 + synthesize ×3 + app ×2）

## 过程坑

1. **Python 包 shadowing**：Docker 里 `COPY ragas ./ragas` 把本地包拷到 `/app/ragas/`，本地空 `__init__.py` 会 shadow PyPI `ragas` 库的 `__init__.py`，导致 `import ragas; ragas.__version__` 取到本地空壳，`/health` 返回 `"ragas_version": "unknown"`。修复方案：`__init__.py` 用 `importlib.metadata.version("ragas")` 读 dist-info 元数据，绕过 `sys.path` 优先级问题（commit `1a5713a8`）。本地 dev 环境未装 PyPI ragas → fallback "unknown"；Docker 装了 → 返回真实版本号。未来 RAGAS 评估用 `from ragas.metrics import faithfulness` 本身没问题（本地包没 `metrics.py`，Python fallback 到 PyPI），但**不要**在本地包里加同名子模块，否则也会 shadow。
2. **`.env.example` 被误操作**：用户把 `.env.example` rename 成 `.env` 填真 key 后，仓库里 `.env.example` 被删。Git tracked `.env.example` 但未 tracked `.env`。修复：`git checkout HEAD -- .env.example` 从上个 commit 恢复，`.env`（untracked 带真 key）保持不变不被跟踪。
3. **SYNTHESIS_PROMPT JSON 示例花括号**：模板里 `{"question": "...", "answer": "..."}` 在 Python `.format(text=, doc_name=)` 里会抛 `IndexError` 或 `KeyError`（花括号被当成位置参数）。必须写成 `{{"question": "...", "answer": "..."}}`。
4. **`@MapperScan` 陷阱**：不加 `com.nageoffer.ai.ragent.eval.dao.mapper` 到 `RagentApplication.@MapperScan` basePackages，4 个 eval mapper bean **根本装配不了**。`EvalMapperScanTest` 是刻意用 `@SpringBootTest` + `@Autowired(required=false)` 做 fail-loud 的断言测试。
5. **pytest rootdir + sys.path**：`ragas/tests/test_settings.py` 从 `ragas.settings` import，依赖 pytest 把 `ragas/` 自动加进 sys.path。跑时必须 `cd ragas && python -m pytest tests/`，不能在项目根跑。

## 验证数据

- `curl http://localhost:9091/health`：
  ```json
  {"status":"ok","ragas_version":"0.2.x","evaluator_llm":"qwen3.5-flash"}
  ```
- `curl -X POST /synthesize` 喂 Spring Boot 介绍段，qwen-max 真返回自然 Q-A（source_chunk_id 正确回传，failed_chunk_ids 为空）
- `mvn -pl bootstrap test -Dtest="Eval*Test"`：4 tests 全绿
- `cd ragas && python -m pytest tests/`：7 passed

## 回滚策略

本 PR 新代码**全部**在新 `eval/` 域和 `ragas/` 目录内。唯一一处 main 代码修改是 `RagentApplication.@MapperScan` basePackages 追加一个包路径。回滚只需 revert 这些 commit；v1.10 的 4 张表在 DB 里保留也不影响任何现有查询（`t_eval_*` 与任何已有表零 JOIN）。

## 后续（backlog）

- **EVAL-2**：legacy `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 失效 bug —— 独立 PR 修。直接开 `@EnableAsync` 会复活一堆连锁效应（所有遗留 `@Async` 注解都生效），需隔离评估。优先级中。
- **EVAL-3**：评估读接口的 `security_level` redaction —— 开放 `AnyAdmin` 读的前置。在这之前所有 eval 读接口 SUPER_ADMIN-only。优先级高（E2 merge 前必须出方案）。
- **PR E2**：合成真落库 + 审核前端 `/admin/eval-suites` + dataset 激活。覆盖 spec §6.2 合成流程 + §7.2 Service 骨架剩余 + §11 前端。
- **PR E3-spike**：从 `streamChat` 抽 `AnswerPipeline` 的代价评估 ADR。E2 merged 后启动。
- **PR E3**：`ChatForEvalService` + `EvalRunExecutor` + 评估执行 + 结果看板 + 趋势对比。

## 本次改动文件

Java（新增，共 10 个 main + 4 个 test）：
- `bootstrap/.../eval/config/EvalProperties.java` + test
- `bootstrap/.../eval/async/EvalAsyncConfig.java` + test
- `bootstrap/.../eval/dao/entity/{GoldDataset, GoldItem, EvalRun, EvalResult}DO.java`
- `bootstrap/.../eval/dao/mapper/{GoldDataset, GoldItem, EvalRun, EvalResult}Mapper.java` + `EvalMapperScanTest`
- `bootstrap/.../eval/domain/{SynthesizeChunkInput, SynthesizedItem, SynthesizeRequest, SynthesizeResponse}.java`
- `bootstrap/.../eval/client/RagasEvalClient.java`
- `bootstrap/.../eval/CLAUDE.md`

Java（修改，共 3 个）：
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`（`@MapperScan` 加 eval.dao.mapper）
- `bootstrap/src/main/resources/application.yaml`（加 `rag.eval.*` 配置节）
- `bootstrap/CLAUDE.md`（加 eval 域段 + legacy 警告）

DB：
- `resources/database/upgrade_v1.9_to_v1.10.sql`（新）
- `resources/database/schema_pg.sql`（追加 v1.10 段）

Python（新增）：
- `ragas/ragas/{__init__, settings, synthesize, app}.py`
- `ragas/tests/{__init__, test_settings, test_synthesize, test_app}.py`
- `ragas/{requirements, requirements-dev}.txt`
- `ragas/Dockerfile` + `ragas/.dockerignore`

Infra + docs：
- `resources/docker/ragent-eval.compose.yaml`（新）
- `.env.example`（新）
- `docs/dev/setup/launch.md`（追加 ragent-eval 启动段）
- `CLAUDE.md`（升级脚本表加 v1.10 一行）
- `docs/superpowers/specs/2026-04-24-rag-eval-closed-loop-design.md`（新，728 行）
- `docs/superpowers/plans/2026-04-24-rag-eval-pr-e1.md`（新，1822 行）
