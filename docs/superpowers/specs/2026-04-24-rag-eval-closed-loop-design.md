# RAG 评估闭环系统设计

- **日期**：2026-04-24
- **状态**：Draft（待 user review）
- **作者**：ZhongKai-07（brainstorm via Claude）
- **相关文档**：`bootstrap/CLAUDE.md`、`docs/dev/gotchas.md`、`docs/dev/followup/backlog.md`

---

## 1. 背景与目标

### 1.1 现状痛点

当前评估链路薄弱且断裂：

- `t_rag_evaluation_record` 每次问答异步落库 query/chunks/answer（`evalStatus=PENDING`），但**没人用它算分**
- `GET /rag/evaluations/export` 能导出 RAGAS 兼容 JSON，但**`groundTruths=[]` 永远为空**
- `PUT /rag/evaluations/{id}/metrics` 回填接口**无调用方**
- `ragas/ragas/run_eval.py` 是独立 Python CLI：手工导出 JSON → 落盘 → 跑脚本 → 读 CSV，四步脱节
- 只算 `faithfulness + answer_relevancy` 两项，缺 `context_precision + context_recall`
- 百炼 API key 硬编码在 Python 源文件里

### 1.2 目标

针对 **dev 环境下的持续演进优化**，建立评估闭环以指导调优方向：

- ✅ **链路闭环**：从前端一键触发到四指标落库，全链路自动化
- ✅ **Metric 完整化**：RAGAS 四件套（faithfulness / answer_relevancy / context_precision / context_recall）全部覆盖
- ✅ **对比可追溯**：每次评估落配置快照，支持"哪次改动让分数降了"溯源

### 1.3 非目标（本轮明确不做）

- ❌ **在线每条问答评估**：只做离线 Gold Set 批量评估
- ❌ **CI 自动回归守门**：`C. 回归守门` 方向列入下一轮
- ❌ **趋势告警 / 仪表板产品化**：基础趋势对比做，但不做阈值告警
- ❌ **生产级并行评估**：dev 环境单容器串行足够

---

## 2. 核心决策（已与 user 对齐）

| 决策点 | 决议 |
|---|---|
| 首批规模 | 按 KB 划分，每 KB 起步 **50 条** gold set |
| Ground Truth 来源 | **LLM 合成 + 10 秒/条人审**（Qwen-Max 读 chunk 反向生成 Q-A-evidence）|
| 评估引擎 | **Python FastAPI 服务化**（保留 RAGAS 库的指标保真性）|
| Java 域划分 | **顶级 `eval/` 域**（独立 bounded context，和 `rag/` 单向依赖）|
| 评估执行链路 | 走 **真实 RAG 完整链路**（`ChatForEvalService` 阻塞式），不直连 LLM |
| ThreadLocal 约束 | **零新增**（对齐 PR3 硬约束）|
| 触发模式 | 前端手动触发（`SUPER_ADMIN` 限定），显式 `evalExecutor` 异步执行，前端轮询进度 |
| 单条失败策略 | 不阻断整 run；状态机三态：`SUCCESS`（全成）/ `PARTIAL_SUCCESS`（部分成）/ `FAILED`（全败）|
| 权限与异步上下文 | 触发入口 `SUPER_ADMIN`；执行线程作为**系统级** `AccessScope.all()`（仅限此手动触发离线评估场景）|

---

## 3. 架构总览

### 3.1 三方组件

```
┌─────────────────────────────────────────────────────────────┐
│                     前端（admin 新页面）                     │
│   Gold Set 管理 / 评估运行 / 趋势对比                        │
└──────────────────┬──────────────────────────────────────────┘
                   │ HTTP
┌──────────────────▼──────────────────────────────────────────┐
│            Java 后端 bootstrap (顶级域 eval/)                │
│   ┌──────────────┬──────────────┬──────────────────────┐    │
│   │ GoldSet 编排 │ 评估编排     │ 合成/审核/查询接口    │    │
│   └──────┬───────┴──────┬───────┴──────────────────────┘    │
│          │              │                                    │
│          │     调 rag.core.ChatForEvalService 跑真实问答      │
│          │              │                                    │
└──────────┼──────────────┼────────────────────────────────────┘
           │ HTTP         │ HTTP
           ▼              ▼
┌──────────────────────────────────────────────┐
│      Python 服务 ragent-eval (FastAPI)       │
│   POST /synthesize  生成 (Q, A, evidence)    │
│   POST /evaluate    跑 4 指标 RAGAS           │
│   依赖：ragas + openai + 百炼                 │
│   容器化进 docker-compose，端口 9091          │
└──────────────────────────────────────────────┘
```

### 3.2 职责硬边界

- **Java 永远掌握编排与数据**：所有落库、状态机、权限校验、真实 RAG 链路调用
- **Python 只做两件事**：合成 + 评分；**无状态**、**不访问 DB**、I/O 全走 HTTP JSON
- **评估走真实 RAG 链路**：绕开 SSE 和会话落库，但经过 rewrite → retrieve → rerank → LLM 完整管道

---

## 4. 域划分与 DDD 考量

### 4.1 判据与结论

eval 是独立 bounded context：有独立领域词汇（GoldSet / EvalRun / 四指标）、独立生命周期（synthesize → review → activate → run）、用户角色不同（调优者 vs 终端用户）、依赖方向单向（eval → RAG，反向禁止）。

### 4.2 当前 `rag/` 域已过载

`rag/` 下已含 retrieval / rewrite / intent / memory / prompt / MCP / trace / citations / source-cards / legacy-eval 九类子域。把新评估塞进去只会继续养 god-domain。

### 4.3 结构定稿

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/
├── rag/
│   └── core/
│       └── ChatForEvalService.java    ← 保留在 rag/core/：
│                                        RAG 对外的"阻塞式问答"端口
│                                        eval 域依赖它
├── ingestion/
├── knowledge/
├── core/
├── admin/
├── user/
└── eval/                               ← 新顶级域
    ├── controller/
    ├── service/
    ├── dao/
    ├── domain/
    ├── client/
    └── config/
```

### 4.4 依赖规则（硬约束，写进 `eval/CLAUDE.md`）

```
eval/  ─ depends on ──→  rag.core.ChatForEvalService       (✓ 合法，port)
eval/  ─ depends on ──→  knowledge.KbReadAccessPort        (✓ 合法，port)
eval/  ─ depends on ──→  framework.*                        (✓ 合法)

eval/  ← NEVER depended on by ──  rag/        (硬约束)
eval/  ← NEVER read/write ──  rag/ 内部表     (硬约束)
```

**价值**：未来任何时候想把 eval 拆成独立微服务，换 RestClient 实现就走，零代码腐蚀。

### 4.5 Legacy `RagEvaluationService` / `t_rag_evaluation_record`

**不动**。功能上是 trace 留存（ThreadLocal 收集 q/chunks/answer），属于 trace 子域。在 `bootstrap/CLAUDE.md` 加一行警告：

> ⚠️ `RagEvaluationService` 是 legacy trace 留存，**不是**新评估域入口；新评估见 `eval/` 域

在 `docs/dev/followup/backlog.md` 加一条 `EVAL-1`：未来 DDD 重构时把 `rag/` 九类子域继续拆。

---

## 5. 数据模型

所有新表统一 `t_eval_*` 前缀，归属 eval 域。

### 5.1 `t_eval_gold_dataset` — 黄金集（按 KB 划分）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(20) PK | 雪花 |
| `kb_id` | VARCHAR(20) NOT NULL | 所属 KB |
| `name` | VARCHAR(128) NOT NULL | 数据集名 |
| `description` | TEXT | |
| `status` | VARCHAR(16) | `DRAFT` / `ACTIVE` / `ARCHIVED` |
| `item_count` | INT | 冗余 approved 条数 |
| `created_by`, `updated_by` | VARCHAR(20) | |
| `create_time`, `update_time`, `deleted` | 标准审计 |

**唯一约束**：`(kb_id, name, deleted)` — 同 KB 下数据集名唯一

### 5.2 `t_eval_gold_item` — 黄金集条目

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(20) PK | |
| `dataset_id` | VARCHAR(20) NOT NULL | FK → gold_dataset |
| `question` | TEXT NOT NULL | |
| `ground_truth_answer` | TEXT NOT NULL | RAGAS context_recall 算分依据 |
| `source_chunk_id` | VARCHAR(20) | 合成时 LLM 读的 chunk id（来自 `t_knowledge_chunk.id`，可为空）|
| `source_chunk_text` | TEXT NOT NULL | **合成时 chunk 原文快照**，字节级冻结——KB 重切后仍可回查 |
| `source_doc_id` | VARCHAR(20) | 源文档 id（来自 `t_knowledge_document.id`，可为空）|
| `source_doc_name` | VARCHAR(255) | 源文档名（合成时 JOIN 出，快照）|
| `review_status` | VARCHAR(16) | `PENDING` / `APPROVED` / `REJECTED` |
| `review_note` | TEXT | 审核备注 |
| `synthesized_by_model` | VARCHAR(64) | 合成用模型名（审计）|
| 标准审计字段 | | |

**索引**：`(dataset_id, review_status)`

**设计要点**：`source_chunk_text` 是硬性新增字段，解决原方案的 chunk_id 失效问题——KB 重新向量化后 chunk_id 可能不存在，但快照文本永远在。审核页、drill-down 页都以此为主。

### 5.3 `t_eval_run` — 一次评估运行

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(20) PK | |
| `dataset_id` | VARCHAR(20) NOT NULL | |
| `kb_id` | VARCHAR(20) NOT NULL | 冗余，避 join |
| `triggered_by` | VARCHAR(20) | user_id |
| `status` | VARCHAR(16) | `PENDING` / `RUNNING` / `SUCCESS` / `PARTIAL_SUCCESS` / `FAILED` / `CANCELLED` |
| `total_items` | INT | |
| `succeeded_items` | INT | |
| `failed_items` | INT | |
| `metrics_summary` | TEXT | JSON：四指标均值 |
| `system_snapshot` | TEXT | **⚠️ 关键**：当次配置快照（见下）|
| `evaluator_llm` | VARCHAR(64) | 评测模型名 |
| `error_message` | TEXT | 顶层错误 |
| `started_at`, `finished_at` | TIMESTAMP | |
| 标准审计 | | |

**`system_snapshot` JSON 结构**（历史对比的唯一凭证）：
```json
{
  "recall_top_k": 30,
  "rerank_top_k": 10,
  "rerank_enabled": true,
  "rerank_model": "bge-reranker-v2-m3",
  "rewrite_enabled": true,
  "chat_model": "qwen3.5-flash",
  "embedding_model": "text-embedding-v3",
  "intent_enabled": true,
  "channel_thresholds": {"intent": 0.5, "vector": 0.3},
  "git_commit": "ad467a08",
  "config_hash": "sha256:..."
}
```

### 5.4 `t_eval_result` — 每条 gold item 评估结果

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(20) PK | |
| `run_id` | VARCHAR(20) NOT NULL | FK → run |
| `gold_item_id` | VARCHAR(20) NOT NULL | FK → gold_item |
| `question` | TEXT | 冗余快照 |
| `ground_truth_answer` | TEXT | 冗余快照 |
| `system_answer` | TEXT | 真实 RAG 链路输出 |
| `retrieved_chunks` | TEXT | JSON 数组 |
| `faithfulness` | DECIMAL(5,4) | 可 NULL（失败）|
| `answer_relevancy` | DECIMAL(5,4) | |
| `context_precision` | DECIMAL(5,4) | |
| `context_recall` | DECIMAL(5,4) | |
| `error` | TEXT | 单条失败原因 |
| `elapsed_ms` | INT | 本条耗时 |
| `create_time` | TIMESTAMP | |

**索引**：`(run_id)` 主查询；`(run_id, faithfulness ASC)` 支持"最差 top N"

### 5.5 设计要点

1. **冗余快照字段**：question/answer/chunks 冗余到 `t_eval_result`，gold item 被改/删仍可回放历史 run
2. **`system_snapshot` 是灵魂**：没有它，两次评估分数差异无法溯源
3. **Dataset 状态机**：`DRAFT`（审核期）→ `ACTIVE`（可评估）→ `ARCHIVED`；只有 `ACTIVE` 能发起评估
4. **Run 状态机三态**：`SUCCESS`（全成）/ `PARTIAL_SUCCESS`（部分成，黄警示）/ `FAILED`（全败），防 1/50 成功误显绿灯
5. **per-item result 独立落库**：不合并 JSON，为了 drill-down 和分析查询
6. **`source_chunk_text` 快照是审核页和 drill-down 主显字段**：取代 `source_chunk_id`（可能失效）

---

## 6. 数据流

### 6.1 Flow 1：Gold Set 合成 + 审核

```
[前端] 选 KB → 填 dataset 名 → 指定要合成 N 条 → 点"开始合成"
         │
         ▼  POST /admin/eval/gold-datasets/synthesize  {kbId, name, count}
[Java GoldDatasetController]
         ▼
[Java GoldDatasetSynthesisService]
   1. 建 t_eval_gold_dataset 状态 DRAFT
   2. 从 t_knowledge_chunk JOIN t_knowledge_document 按 kb_id 分层采样：
      SELECT c.id AS chunk_id, c.content AS chunk_text,
             c.doc_id, d.doc_name
      FROM t_knowledge_chunk c
      JOIN t_knowledge_document d ON c.doc_id = d.id
      WHERE c.kb_id = ?
        AND c.deleted = 0 AND c.enabled = 1
        AND d.deleted = 0 AND d.enabled = 1 AND d.status = 'success'
      ORDER BY c.doc_id, RANDOM()
      -- 再在 Java 侧做每 doc ≤ max-per-doc 的去重，共 N 条
      -- 列名与 schema_pg.sql 对齐：t_knowledge_chunk.doc_id / t_knowledge_document.doc_name
      -- enabled + status='success' 过滤：不采样被禁用或未成功入库的 chunk
   3. 调 Python（把原文随请求一起发，Python 不查 DB）:
         ▼  POST :9091/synthesize  {chunks: [{id, text, doc_name}]}
[Python ragent-eval /synthesize]
   对每 chunk 调强模型（Qwen-Max）：
     prompt = "基于下面文档片段生成一个自然的用户问题和标准答案..."
     response → {source_chunk_id, question, answer}
   pydantic 校验 + 单条失败不崩整批
   返回 list[items] + failed_chunk_ids
         ▼
[Java]
   4. 把原始 chunk.text 冻结进 gold_item.source_chunk_text（快照）
      把 doc_name/doc_id 冻结进 source_doc_name/source_doc_id
      批量 insert t_eval_gold_item 状态 PENDING
   5. 返回 dataset_id

[前端] 跳转审核页（左右分屏）
   - 左：chunk 原文 / 右：合成 Q+A
   - 快捷键 y/n/e (approve/reject/edit)
   - 审完点"激活" → status DRAFT → ACTIVE
```

**异常处理**：
- Python 单条失败 → 跳过该 chunk，返回 `failed_chunk_ids`，前端提示"N 条合成失败"
- Python 整服务不可达 → Java try/catch 记日志，dataset 保持 DRAFT（可重试）
- API key 缺失 → Python 启动期 fail-fast，Java 调用 502，前端告警

### 6.2 Flow 2：一键评估（核心闭环）

```
[前端] 在 ACTIVE dataset 页点"开始评估"
         │
         ▼  POST /admin/eval/runs  {datasetId}
[Java EvalRunController] @SaCheckRole("SUPER_ADMIN")
   1. 校验 dataset.status=ACTIVE
   2. 建 t_eval_run：
        - status=PENDING
        - system_snapshot = SystemSnapshotBuilder.build()
        - total_items = APPROVED item 数
        - triggered_by = 当前 user_id（审计用）
   3. 返回 runId，前端开始轮询
   4. evalExecutor.execute(() -> runExecutor.runInternal(runId, principalUserId))

[Java EvalRunExecutor - 显式 executor 线程]
   使用系统级 AccessScope.all()（仅手动触发离线评估合法——见 §15）
   run.status = RUNNING
   for each gold_item in items:
      try:
         // A. 走真实 RAG 链路（阻塞，走 AnswerPipeline）
         AnswerResult resp = chatForEvalService.chatForEval(
             kbId, gold_item.question
         )
         // B. 批量暂存（batch_size=5）
         batch.add({goldItemId, question, gt_answer, chunks, answer})
         if batch.size()==5: flushToPython(batch)
      catch TimeoutException / ChatException:
         写 t_eval_result 该条 error=xxx，指标 NULL
   flushToPython(剩余批)

   [Python /evaluate]
      对每条跑 4 指标 RAGAS metrics
      返回 results[]
         ▼
   Java 批量 insert t_eval_result
   汇总均值 → update run.metrics_summary
   // 三态状态机（不再 "≥1 条即 SUCCESS"）
   if failed_items == 0:           run.status = SUCCESS
   elif succeeded_items == 0:      run.status = FAILED
   else:                           run.status = PARTIAL_SUCCESS
   run.finished_at = now()

[前端] 轮询 GET /admin/eval/runs/{runId}
   - RUNNING：显示进度 succeeded/total
   - SUCCESS（绿）/ PARTIAL_SUCCESS（黄警示）/ FAILED（红）：切换看板
       • 四指标均值大卡
       • 分布直方图（0-0.2 / 0.2-0.4 / ... / 0.8-1.0 buckets）
       • per-item 表格（按指标升序找最差）→ drill-down
```

**关键设计决策**：

1. **`ChatForEvalService` 需要前置重构**（PR E3 前置风险，不可低估）：
   - 现有 `RAGChatServiceImpl.streamChat` 是 `void` 返回，内部混着 `memoryService.loadAndAppend` / `callback.emitSources` / EvaluationCollector / SSE emit，**绝不是**"绕开 SSE 就能剥出来"
   - 最小侵入做法：抽出**最小同步回答编排** `AnswerPipeline`（rewrite → retrieve → rerank → prompt → `LLMService.chat` 同步），**不承诺**一次性重构 `streamChat` 全部逻辑
   - `streamChat` 保持外层行为不变，内部改为调 `AnswerPipeline` + SSE adapter + 会话写入 + sources emit
   - `ChatForEvalService` 是 `AnswerPipeline + eval adapter`：不走 `memoryService.loadAndAppend`，直接拿完整 answer
   - 风险诚实披露：这步抽取可能牵出现有 streamChat 的隐性耦合；PR E3 前**必须**先做一个 spike PR 验证抽取边界，评估真实工程量

2. **批量 5 条送 Python**：单条 HTTP 开销大，全量一次会在 Python 内爆上下文

3. **三态状态机**：`SUCCESS`（全成）/ `PARTIAL_SUCCESS`（部分成，黄警示）/ `FAILED`（全败）。取代旧"≥1 条即 SUCCESS"——1/50 显示绿灯会误导趋势判断

4. **显式 `evalExecutor` 而非 `@Async` 注解**：`RagentApplication` 当前无 `@EnableAsync`（现有 `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 本身就是失效 bug，见 EVAL-2）。eval 域不依赖全局 async 语义，自建 `ThreadPoolTaskExecutor` bean 显式 `execute()`

5. **幂等性**：同 chunks+answer 跑两次分数接近（RAGAS 有少量 LLM 不确定性，可接受），不做 cache

### 6.3 Flow 3（小流）：历史对比

`GET /admin/eval/runs?datasetId=xxx` 列同 dataset 所有 run → 前端画四条折线（x 轴=run 时间序）+ 点 run 看 `system_snapshot` diff。**持续演进的核心视图**。

---

## 7. 代码组织

### 7.1 Java 侧 `eval/` 新域结构

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/
├── controller/
│   ├── GoldDatasetController.java      ← Dataset CRUD + 合成触发
│   ├── GoldItemController.java         ← Item 审核
│   └── EvalRunController.java          ← 发起 + 查询 + 对比
├── service/
│   ├── GoldDatasetService.java
│   ├── GoldDatasetSynthesisService.java  ← 调 Python /synthesize
│   ├── GoldItemReviewService.java
│   ├── EvalRunService.java             ← 编排：snapshot → 提交到 evalExecutor → 聚合
│   ├── EvalRunExecutor.java            ← 对每条 gold_item 跑 RAG + 批送 Python
│   ├── SystemSnapshotBuilder.java      ← 采集当前配置生成 snapshot JSON
│   └── impl/...
├── client/
│   └── RagasEvalClient.java            ← Python HTTP 客户端（RestClient）
├── async/
│   └── EvalAsyncConfig.java            ← 定义 evalExecutor bean（不碰全局 @EnableAsync）
├── dao/
│   ├── entity/
│   │   ├── GoldDatasetDO.java
│   │   ├── GoldItemDO.java
│   │   ├── EvalRunDO.java
│   │   └── EvalResultDO.java
│   └── mapper/...
├── domain/
│   ├── SystemSnapshot.java             ← record，4 类参数分组
│   ├── SynthesizeRequest/Response.java
│   └── EvaluateRequest/Response.java
└── config/
    └── EvalProperties.java             ← rag.eval.* 配置绑定
```

### 7.2 关键类职责

| 类 | 职责 |
|---|---|
| `GoldDatasetSynthesisService` | KB 采样 chunk → 调 Python → 批量落 gold_item；异常不回滚 dataset（DRAFT 保留重试）|
| `EvalRunService` | 幂等建 run + 记 snapshot + 提交任务到 `evalExecutor`；**不含循环体逻辑** |
| `EvalRunExecutor` | 在 `evalExecutor` 线程里执行 for-each；单条失败兜底；满 5 条 flush 到 Python；三态状态机判定 |
| `EvalAsyncConfig` | 定义 `evalExecutor: ThreadPoolTaskExecutor` bean（core=2, max=4, queue=50）；**不引入 `@EnableAsync`** 避免副作用 |
| `RagasEvalClient` | 纯 HTTP 客户端；超时/重试/fail-fast 都在这层 |
| `SystemSnapshotBuilder` | 注入 `RagRetrievalProperties` / `RAGDefaultProperties` / `AIModelProperties`（infra-ai，含 `ai.chat.*` 和 `ai.rerank.*` 整个 binding）/ `Environment`（取 `git.commit.id` 等），拼 snapshot JSON。**单一真相源**——改配置加字段改这一个 builder |
| `AnswerPipeline`（在 `rag/core/` 新增）| **最小同步回答编排**：rewrite → retrieve → rerank → prompt → `LLMService.chat` 同步。`streamChat` 和 `ChatForEvalService` 都是它的 adapter |
| `ChatForEvalService`（在 `rag/core/` 而非 `eval/`）| `AnswerPipeline` 的 eval adapter：不走 `memoryService.loadAndAppend`，不 emit SSE，不写 `t_conversation` / `t_rag_evaluation_record`；返回 `AnswerResult {answer, chunks}` |

### 7.3 Python 服务 `ragent-eval`

**目录**（在现有 `ragas/` 改造）：

```
ragas/
├── ragas/
│   ├── app.py                      ← 新：FastAPI 入口
│   ├── synthesize.py               ← 新：合成 Q-A-evidence
│   ├── evaluate.py                 ← 新：四指标 RAGAS
│   ├── settings.py                 ← 新：pydantic-settings 从 env 读配置
│   ├── run_eval.py                 ← 旧脚本，保留供离线调试
│   └── requirements.txt            ← 加 fastapi/uvicorn/pydantic-settings
├── Dockerfile                      ← 新
└── README.md                       ← 新
```

**FastAPI 契约**：

```python
# POST /synthesize
请求: {"chunks": [{"id", "text", "doc_name"}]}    # Java 把原文 + doc_name 随请求一起发
响应: {"items": [{"source_chunk_id", "question", "answer"}],  # Python 只负责 LLM 产出三件套
      "failed_chunk_ids": ["c2"]}
# Python 不返回 doc_hint / doc_name / chunk_text——这些 Java 侧采样时已冻结
# Java 把 Python 返回的 (source_chunk_id, question, answer) 和自己冻结的
#   (source_chunk_text, source_doc_id, source_doc_name) 组装后 insert t_eval_gold_item

# POST /evaluate
请求: {"items": [{"gold_item_id", "question", "contexts":[...], "answer", "ground_truth"}]}
响应: {"results": [{"gold_item_id",
                    "faithfulness", "answer_relevancy",
                    "context_precision", "context_recall",
                    "error": null}]}

# GET /health
响应: {"status": "ok", "ragas_version": "0.2.x", "evaluator_llm": "qwen3.5-flash"}
```

**Dockerfile**：`python:3.11-slim` → `pip install -r requirements.txt` → `uvicorn ragas.app:app --host 0.0.0.0 --port 9091`

**环境变量**（替代硬编码）：
```
DASHSCOPE_API_KEY=sk-xxx
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EVALUATOR_CHAT_MODEL=qwen3.5-flash
EVALUATOR_EMBEDDING_MODEL=text-embedding-v3
SYNTHESIS_STRONG_MODEL=qwen-max
```

### 7.4 docker-compose 文件（新）

按项目惯例 `{service}-stack.compose.yaml`，新建 `resources/docker/ragent-eval.compose.yaml`：

```yaml
services:
  ragent-eval:
    build: ../../ragas
    container_name: ragent-eval
    ports:
      - "9091:9091"
    environment:
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
      EVALUATOR_CHAT_MODEL: qwen3.5-flash
      SYNTHESIS_STRONG_MODEL: qwen-max
    restart: unless-stopped
```

`.env.example` 新增 `DASHSCOPE_API_KEY=` 空占位（不提交真 key）。
`docs/dev/setup/launch.md` 补启动命令：`docker compose -f resources/docker/ragent-eval.compose.yaml up -d`

### 7.5 前端 admin 新页面

⚠️ **与 legacy `/admin/evaluations`（评测记录）严格区分**：
- **`/admin/evaluations`**（已存在）：legacy QCA 留存页（`RagEvaluationPage`），只读展示 `t_rag_evaluation_record` 的 query-chunk-answer triples；**不是**质量评估闭环入口
- **`/admin/eval-suites`**（本 PR 新增）：质量评估闭环（Gold Set + 四指标 + 趋势对比）

新目录 `frontend/src/pages/admin/eval-suites/`：

```
index.tsx                   ← 入口，三个 tab
GoldSetListPage.tsx         ← Tab 1: Gold Set 管理
GoldSetReviewPage.tsx       ← 左右分屏审核（y/n/e 快捷键）
EvalRunListPage.tsx         ← Tab 2: 评估运行
EvalRunDetailPage.tsx       ← 单 run 结果看板 + drill-down
EvalTrendPage.tsx           ← Tab 3: 趋势对比（折线 + snapshot diff）
```

- `router.tsx` 注册 `/admin/eval-suites/*`（不是 `/admin/evaluation`）
- `AdminLayout.tsx` 侧栏保留"评测记录"（legacy）并新增"质量评估"（本 PR），两个入口并列且视觉区分（比如不同 icon）
- 图表用 `recharts`，快捷键用 `useHotkeys`

---

## 8. 配置

新配置节 `rag.eval.*`：

```yaml
rag:
  eval:
    python-service:
      url: http://ragent-eval:9091
      timeout-ms: 120000
      max-retries: 2
    synthesis:
      default-count: 50
      max-per-doc: 5            # 防单 doc 采样过多
      strong-model: qwen-max
    run:
      batch-size: 5             # 每 N 条送 Python
      per-item-timeout-ms: 30000
      max-parallel-runs: 1      # dev env 串行，防 LLM QPS 爆
```

---

## 9. ThreadLocal 硬约束（对齐 PR3）

### 9.1 规则

**eval 域零 ThreadLocal 新增**。所有跨方法/跨线程状态走参数 / record / DO。

| 组件 | 有 TL？ |
|---|---|
| `EvalRunService.startRun(datasetId)` | ❌ 参数传 |
| `EvalRunExecutor.runInternal(runId, principalUserId)` 在 `evalExecutor` 跨线程 | ❌ 参数显式传入，不用 `TaskDecorator` |
| 循环体 `for gold_item in items` | ❌ 循环变量 |
| `ChatForEvalService.chatForEval(kbId, question)` | **间接有，但不由 eval 引入**（见下）|
| `RagasEvalClient.evaluate(batch)` | ❌ 纯 HTTP |
| DAO `mapper.insert(DO)` | ❌ DO 字段齐全 |

### 9.2 `ChatForEvalService` 的 TL

`chatForEval` 内部复用 `RetrievalEngine` / `RAGPromptService` 等组件——这些组件**已经**用 `RagTraceContext` 做日志关联。但：

- **eval 域代码不读不写 `RagTraceContext`**
- TL 的 set/clean 全部发生在 `ChatForEvalService` 一个方法栈内，**同步同线程，不跨 async**
- 因此没有 PR3 当时那个 `onComplete` 异步回调读空 TL 的问题

### 9.3 违反示例（review 拒）

```java
class EvalRunContext extends ThreadLocal<EvalRun> { ... }   // ❌
RagasEvalClient 里读 RagTraceContext.traceId                // ❌（应作参数传）
evalExecutor + TaskDecorator 续 UserContext/RagTraceContext  // ❌（异步线程走系统级 AccessScope）
```

### 9.4 跨层 runId 关联

`RagasEvalClient.evaluate(runId, batch)` 在 HTTP 请求头加 `X-Eval-Run-Id: xxx`，Python 侧日志对应，完全不走 TL。

---

## 10. Key Gotchas（写进 `eval/CLAUDE.md` 和 `docs/dev/gotchas.md`）

1. **`system_snapshot` 是历史对比的唯一凭证**。新增任何影响 RAG 行为的配置（新模型、阈值、开关），**必须**同步加到 `SystemSnapshotBuilder`。PR review checklist 必项。不加 = 未来读不懂分数差异

2. **评估走真实 RAG 链路 ≠ 走生产 SSE 链路**。`ChatForEvalService` 三个硬隔离：
   - `conversationId = null`（service 内 null 分支，**不**调 `memoryService.loadAndAppend`）
   - 不注册前端可见的 trace run（或 `traceKind=EVAL` 排除）
   - 不触发 `RagEvaluationServiceImpl.saveRecord`（legacy 留存）

3. **Gold set 状态机严格**：`DRAFT → ACTIVE → ARCHIVED`，**`ACTIVE` 下不允许增删 item**（防历史 run 和 item 对不上）。改 item 必须 clone 成新 dataset

4. **Python 单容器串行**：`max-parallel-runs=1` 是 dev env 约束，防两个 run 同时打爆百炼 QPS 配额。未来生产并行必须先升配额 + Python 水平扩展

5. **评估 LLM ≠ 被评估 LLM**：`SystemSnapshotBuilder` 记 `chat_model`（被评）和 Python `EVALUATOR_CHAT_MODEL`（评测）两个值。相同 WARN 日志

6. **`ground_truth_answer` 必填非空**：合成时 LLM 可能返回空 answer。Python `/synthesize` 返回前过滤，Java 入库前再校验，双重 fail-closed

7. **Docker compose NO_PROXY**：按项目惯例 `ragent-eval` 加进 `NO_PROXY`，防系统代理打废内网调用

8. **Python 合成输出稳定性**：LLM 合成 Q-A 时可能返回非 JSON / 字段缺失。Python **必须**用 pydantic 校验 + 单条失败不崩整批，返回 `failed_chunk_ids`

9. **`t_eval_result.retrieved_chunks` 可能很大**：50 条 × 10 chunks × 几百字 → MB 级。PG TEXT 可以但 `SELECT *` 慢。查询接口只返摘要，drill-down 再单条全量读

10. **Gold set 的 `source_chunk_id` 可能失效，但 `source_chunk_text` 永远在**：KB 重新向量化后 chunk 被重切，`source_chunk_id` 可能指向不存在的行。审核页和 drill-down 页**默认使用 `source_chunk_text` 快照**；chunk_id 仅用于"跳回 KB 原 chunk 查看上下文"这种次要功能，拿不到时 UI 显示"chunk 已失效，参考快照文本"

11. **前端双入口职责严格分离**：`/admin/evaluations`（legacy QCA 留存）和 `/admin/eval-suites`（新质量闭环）是两个入口，不合并。侧栏各用一个 icon + 文字，侧栏代码 review 必查是否混淆

12. **`@Async` 陷阱**：项目主启动类 `RagentApplication` **没有** `@EnableAsync`——现有 `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 其实是失效的（legacy bug，记为 EVAL-2）。eval 域**绝不**依赖 `@Async` 注解，一律用显式 `evalExecutor.execute()`

13. **系统级 `AccessScope.all()` 边界**：仅限 **`SUPER_ADMIN` 手动触发的离线评估**场景合法；**不得**被部门管理员触发的评估、定时任务、回归守门等场景复用。扩展到其他场景时必须重新做权限模型。代码 review 硬阻断项

14. **`@MapperScan` 必须显式加 eval.dao.mapper 包**：`RagentApplication` 现有 `@MapperScan` 只扫 `rag / ingestion / knowledge / user` 四个包，新域的 mapper **不会**自动生效。PR E1 里必须改 `RagentApplication.java` 加一行 `"com.knowledgebase.ai.ragent.eval.dao.mapper"`。漏了 = 所有 mapper bean 装配失败、启动期 `UnsatisfiedDependencyException`

15. **评估读接口权限不得降级为 `AnyAdmin`**（见 §15.1）：`t_eval_result.retrieved_chunks` 是系统级检索产物，含跨 `security_level` 的内容。读接口放宽会绕过 RBAC，直到 EVAL-3（查询侧 redaction）落地前，**一律 `SUPER_ADMIN`**

---

## 11. 测试策略

| 层级 | 测什么 | 工具 |
|---|---|---|
| **Java 单测** | `SystemSnapshotBuilder`（配置→JSON）、`RagasEvalClient`（HTTP mock）、`EvalRunExecutor` 单条失败不阻断 + 三态状态机判定（全成/部分/全败）、`EvalAsyncConfig` bean 装配 | JUnit5 + Mockito + WireMock |
| **Java 集成测** | `/admin/eval/runs` 全流程：建 run → 跑（mock `ChatForEvalService`）→ mock Python → 校验 `t_eval_result` 行数 + metrics_summary | SpringBootTest + @MockBean |
| **Python 单测** | `/synthesize` 和 `/evaluate` 端点 I/O 契约（mock RAGAS `ascore`） | pytest + pytest-httpx |
| **契约测** | Java DTO ↔ Python pydantic 字段名/类型/optional 对齐 | 手工 schema review |
| **手工 smoke** | 真起 docker-compose，合成 5 条 → 审 3 条 → 评估，肉眼验四指标合理（0-1 区间、非全 NaN）| 手动 |

**刻意不测**：
- ❌ RAGAS 算法正确性（上游库的事）
- ❌ 评测 LLM 输出稳定（非确定性，会 flaky）
- ❌ legacy `t_rag_evaluation_record` 代码（保留不动）

---

## 12. 实施顺序预告（写进 writing-plans 时细化）

分 3 个 PR 逐步落地，每 PR 独立可 merge + demo：

- **PR E1（地基）**：4 张新表（`upgrade_v1.9_to_v1.10.sql`）+ Java `eval/` 域骨架（含 `EvalAsyncConfig` + `evalExecutor`）+ **`RagentApplication.@MapperScan` 加 `com.knowledgebase.ai.ragent.eval.dao.mapper`** + Python 服务壳 + `ragent-eval.compose.yaml`。验收：`POST /synthesize` 合成一条返回 JSON（不落库）+ 启动期 mapper bean 正常装配（无 `UnsatisfiedDependencyException`）
- **PR E2（合成闭环）**：合成真落库（写 `source_chunk_text` 快照）+ 审核前端 `/admin/eval-suites` + dataset 激活。验收：50 条能跑完人审，DRAFT → ACTIVE
- **PR E3-spike（预研）**：验证从 `RAGChatServiceImpl.streamChat` 抽出 `AnswerPipeline` 的真实工程量与隐性耦合。产出 ADR：决定 PR E3 是否需拆分成 E3a（重构）+ E3b（eval 闭环）。**不合并**，或合并一个极小的标记性 refactor
- **PR E3（评估闭环）**：`AnswerPipeline`（如 spike 证明可落地）+ `ChatForEvalService` + `EvalRunExecutor` + result 看板 + 趋势页。验收：一键跑完 50 条拿四指标 + 两次 run 对比 snapshot diff + 三态状态机正确

---

## 13. 开放问题 / 后续跟进

本轮 brainstorm 锁定了主干边界，以下三项需要在 writing-plans 阶段或 PR 执行时再细化：

1. **`AnswerPipeline` 抽取代价未知**：PR E3 前必须做一个 spike（小 PR 或分支）验证从 `streamChat` 抽最小同步编排的真实工程量与隐性耦合，评估后再决定 PR E3 是否需要拆分成 E3a（重构）+ E3b（eval 闭环）
2. **legacy `@Async` 失效（EVAL-2）处理时机**：`RagEvaluationServiceImpl.saveRecord` 目前在主线程跑，是否本 PR 顺手修、还是后续单独 PR。建议**后续单独 PR**（本 PR 只添加 `evalExecutor` 不触碰全局 async 语义）
3. **`traceKind=EVAL` vs 完全不注册 trace run**：实施期二选一。倾向"完全不注册"——更干净、不污染 trace 列表索引
4. **EVAL-3：read API redaction**：评估读接口 v1 为 `SUPER_ADMIN`；未来放开 `AnyAdmin` 需先做 `retrieved_chunks` 的 `security_level` redaction 或让评估跑在查询者 scope 内——独立设计

---

## 14. 参考

- brainstorm 会话原文：2026-04-24 session
- PR3 `onComplete` TL 约束：见 `bootstrap/CLAUDE.md` §Key Gotchas
- 现有评估代码：`bootstrap/.../rag/service/impl/RagEvaluationServiceImpl.java`、`ragas/ragas/run_eval.py`
- 现有前端 legacy 页：`frontend/src/pages/admin/evaluations/RagEvaluationPage.tsx`
- 相关 schema：`resources/database/schema_pg.sql` 中的 `t_knowledge_chunk` / `t_knowledge_document`
- compose 文件命名惯例：`resources/docker/*.compose.yaml`

---

## 15. 权限与异步执行上下文

### 15.1 触发入口权限

v1/dev 范围内**所有 eval-suites 端点统一 `@SaCheckRole("SUPER_ADMIN")`**（写 + 读）：

- `POST /admin/eval/runs`（发起评估）
- `POST /admin/eval/gold-datasets/synthesize`（合成）
- Gold Item 审核/编辑
- **查询 run / result / 趋势 / drill-down**（**所有读接口**）

**为什么读接口也必须 SUPER_ADMIN**：评估执行时走系统级 `AccessScope.all()`，`t_eval_result.retrieved_chunks` 会包含**跨 `security_level` 天花板**的内容（比如 L3 机密文档的 chunk）。如果读接口只要 `AnyAdmin`，部门管理员通过查 result 就能看到本该被 `AuthzPostProcessor` 挡住的机密内容——直接绕过 RBAC。

**未来放开 `AnyAdmin` 的前置条件**（记入 EVAL-3 backlog，不在本 PR 做）：
- 在 `t_eval_result` 上做查询侧 redaction：按查询者的 `security_level` 天花板对 `retrieved_chunks` 做过滤或脱敏
- 或者把评估本身也收窄到查询者 scope 内跑，放弃系统级 all()
- 两种方案都需要独立设计，属于"后续放开"而非"现在拦"

### 15.2 异步线程内权限模型

异步线程里没有 `UserContext`（因为我们硬约束不用 `TaskDecorator` 续 TL），所以 `EvalRunExecutor` 走**系统级 `AccessScope.all()`**：

```java
class EvalRunExecutor {
    void runInternal(String runId, String principalUserId) {
        // principalUserId 仅用于审计日志，不用于权限过滤
        // 检索走系统级：不受 KB/dept ACL / security_level 约束
        AccessScope systemScope = AccessScope.all();
        // ... chatForEvalService.chatForEval(systemScope, kbId, question) ...
    }
}
```

### 15.3 系统级 `AccessScope.all()` 的合法边界（硬约束）

**仅**以下场景合法：

- ✅ `SUPER_ADMIN` 手动触发的离线评估（本 PR 场景）

**明确禁止**扩展到：

- ❌ 部门管理员（`KB_ADMIN`）触发的评估
- ❌ 定时任务自动触发的评估（包括未来的 CI 守门）
- ❌ 真实用户流量走的任何链路

扩展到上述任一场景时**必须**重新做权限模型（引入代表用户的权限上下文），不得直接沿用 `AccessScope.all()`。

### 15.4 为什么系统级合法

- 评估 dataset 归属于 KB，触发者已通过 `SUPER_ADMIN` 角色跨 dept 合法
- 评估读 chunks 是为了给 RAGAS 算分，**不**给真实用户暴露
- `t_eval_result.retrieved_chunks` 是系统级 `AccessScope.all()` 的产物，**可能包含跨 `security_level` 的内容**；v1/dev 没有查询侧 redaction，**结果读取一律 `SUPER_ADMIN`**（见 §15.1）
- `AuthzPostProcessor` 的 `security_level` 天花板**只在真实用户流量的 RAG 链路**生效；eval 执行走系统级不受它约束，eval 结果读取也**不依赖**它做兜底过滤（依赖端点级 `SUPER_ADMIN` 硬拦截）
- 未来放开到 `AnyAdmin` 必须先走 EVAL-3：在 `t_eval_result` 查询侧实现 `security_level` redaction，或让评估本身跑在查询者 scope 内
