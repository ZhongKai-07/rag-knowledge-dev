# Permission PR5 Manual Smoke Paths

> **Spec:** `docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md`
> **Roadmap:** `docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B
> **Prerequisite:** PR1-PR4 smoke paths (`permission-pr{1..4}-smoke.md`) remain valid. Backend listens on `9090` with context path `/api/ragent`.

PR5 把 `metadata.kb_id IN [..]` + `metadata.security_level LTE_OR_MISSING ..` 两条 filter 升级为 OpenSearch retrieval 的 query shape invariant，由 `DefaultMetadataFilterBuilder` 单点输出，由 `OpenSearchRetrieverService.enforceFilterContract` fail-fast。下面 4 条 smoke 覆盖普通用户、历史数据兼容、系统态、绕过攻击四个面。

> ⚠️ **S5b 是发布阻塞项 — PR5 合并前必须执行并通过**。其他三条是回归类 smoke。

---

## S5 — 普通用户检索携带完整 filter 契约

**目的：** 验证普通用户走完整链路（builder → retriever → OpenSearch DSL）时，OpenSearch 实际接收到的 query 同时含 `terms metadata.kb_id` 与 `range metadata.security_level <= <ceiling>`。

**前提：** OpenSearch 节点对 `_search` 请求开启慢查询 / 请求体日志，或后端开启 `org.opensearch.client` DEBUG log 能观察到序列化后的 DSL。也可以在 `OpenSearchRetrieverService` 的 `buildHybridQuery` / `buildKnnOnlyQuery` 临时加 DEBUG 日志打印 `queryJson`（**测完撤销**）。

**步骤：**

```bash
# 1. 以 FICC_USER（部门 FICC, max_security_level 在该 KB 上 = 0 或对应等级）登录
TOKEN=$(curl -sX POST http://localhost:9090/api/ragent/login \
  -d 'username=ficc_user&password=...' | jq -r '.data.token')

# 2. 在 OPS-COB KB 或 FICC_USER 自己的 KB 上发起一次 RAG 检索
curl -sN -H "Authorization: ${TOKEN}" \
  "http://localhost:9090/api/ragent/rag/v3/chat?question=bond+settlement&knowledgeBaseId=<kb-id>"
```

**期望（DSL 形状，不依赖响应文案）：**

- 后端日志（或 OpenSearch slow log）里检索到的 query JSON 同时含：
  - `{"terms": {"metadata.kb_id": ["<kb-id>"]}}` — 单元素 IN，由 `DefaultMetadataFilterBuilder` 输出
  - `{"bool": {"should": [{"range": {"metadata.security_level": {"lte": <N>}}}, {"bool": {"must_not": {"exists": {"field": "metadata.security_level"}}}}], "minimum_should_match": 1}}` — `LTE_OR_MISSING` 的 OpenSearch 翻译，`<N>` 等于该用户在该 KB 的安全等级上限（通过 `SearchContext.kbSecurityLevels` 注入）
- 命中文档返回非空（前提是该 KB 已有索引数据），SSE `META → SOURCES → MESSAGE → FINISH → DONE`

**反例：** 如果日志里只有 hybrid knn + match 无 filter 段，或 filter 段缺这两条之一，c1 实现回归。同时 `enforceFilterContract` 应已抛 `IllegalStateException` 早断，前端表现为 SSE META 后立即 `completeWithError`。

---

## S5b — 【发布阻塞项】生产 OpenSearch 历史数据 `metadata.kb_id` 兼容性核查

**目的：** PR5 c1 起 `terms(metadata.kb_id, [kbId])` 是硬编码的 query shape，缺该字段的旧 chunk 会被全量过滤为不可见。**PR5 合并前必须确认每个 KB 索引里所有文档都已经写入了 `metadata.kb_id`**，否则上线即静默丢检索。

**适用范围：** 所有承载用户数据的 OpenSearch 索引（KB ↔ index 1:1，索引名 = `t_knowledge_base.collection_name`）。包括但不限于：dev、staging、prod。dev / staging 也建议跑一次以提前暴露问题。

**步骤（对每个 KB 索引执行）：**

```bash
# 准备：拉所有 KB 的 collection_name（PG 直连）
docker exec postgres psql -U postgres -d ragent -t \
  -c "SELECT collection_name FROM t_knowledge_base WHERE deleted = 0;" \
  | tr -d ' ' | grep -v '^$' > /tmp/kb-indices.txt

OS="http://localhost:9201"  # 替换为目标环境的 OpenSearch endpoint

while IFS= read -r idx; do
  missing=$(curl -s -X POST "${OS}/${idx}/_count" \
    -H 'Content-Type: application/json' \
    -d '{"query": {"bool": {"must_not": [{"exists": {"field": "metadata.kb_id"}}]}}}' \
    | jq -r '.count')
  total=$(curl -s "${OS}/${idx}/_count" | jq -r '.count')
  echo "${idx}  total=${total}  missing_kb_id=${missing}"
done < /tmp/kb-indices.txt
```

**通过条件：** 每一行 `missing_kb_id == 0`。S5b 通过 → PR5 可合。

**阻塞条件：** 任意一行 `missing_kb_id > 0` → **PR5 合并阻塞**。必须先做 reindex / partial update 把缺失的 `metadata.kb_id` 字段补齐，再合 PR5。补齐脚本示例（按 KB 反推 `kbId`）：

```bash
# 对单个有问题的索引补字段 — kbId 从 t_knowledge_base 反查
KB_ID=$(docker exec postgres psql -U postgres -d ragent -t \
  -c "SELECT id FROM t_knowledge_base WHERE collection_name = '<idx>' AND deleted = 0;" \
  | tr -d ' ')

curl -s -X POST "${OS}/<idx>/_update_by_query" \
  -H 'Content-Type: application/json' \
  -d "{
    \"script\": {
      \"source\": \"if (ctx._source.metadata == null) { ctx._source.metadata = new HashMap(); } ctx._source.metadata.kb_id = params.kbId\",
      \"params\": {\"kbId\": \"${KB_ID}\"}
    },
    \"query\": {\"bool\": {\"must_not\": [{\"exists\": {\"field\": \"metadata.kb_id\"}}]}}
  }"
```

补齐后**重跑 S5b 直到 0 条缺失**，再合 PR5。

**为什么这是 PR5 独有的发布阻塞项：** PR4 之前 `metadata.kb_id` 仅由 `AuthzPostProcessor` 做后置白名单兜底，缺字段的 chunk 走的是 `kbId == null` fail-closed 分支，影响面是 KB 级而非全索引；PR5 c1 起 `kb_id IN [kbId]` 进入 OpenSearch DSL 正向过滤，缺字段直接在召回侧被 0 命中，丢数据是静默的。

---

## S6 — 系统态（评估 / MQ 消费）的 no-op range 形状

**目的：** 验证 `AccessScope.All` 走系统态时 `DefaultMetadataFilterBuilder` 仍输出完整 filter set（KB + security_level），其中 security_level 为 `Integer.MAX_VALUE`（no-op range，等价不过滤但子句仍然出现，保持 query shape 不变）。

**步骤：**

1. 触发一次 ACTIVE 评估 run（`EvalRunController` 显式持有 `AccessScope.all()`，spec §15.3 唯一合法持有者）。或者在 dev 环境推一条 `t_knowledge_document_chunk` MQ 消息走异步消费链路。
2. 后端开启 `com.knowledgebase.ai.ragent.rag.core.retrieve` DEBUG，观察 `queryJson`。

**期望：** OpenSearch DSL 含 `{"range": {"metadata.security_level": {"lte": 2147483647}}}`（或对应数据库类型的 `Integer.MAX_VALUE` 字面量），与 S5 形状对齐——同样两条 filter，只是 security_level 上界放到天花板。`enforceFilterContract` 检查的是 op 类型 (`LTE_OR_MISSING`)，**不**检查阈值，所以系统态依然通过。

**反例：** 系统态路径缺 `metadata.kb_id` 或 `metadata.security_level` 子句之一 → c1 在系统态分支回归。

---

## S7 — 绕过攻击：手动构造空 filter 列表必须 fail-fast

**目的：** 验证 `OpenSearchRetrieverService.enforceFilterContract` 真把契约违规升级为 `IllegalStateException`，**不**降级成空 list 静默放行。这是对未来重构的兜底防御：万一有人引入新通道直接 new `RetrieveRequest` 而忘了走 builder，retriever 端必须立刻爆。

**步骤（dev 临时实验，**不要合进代码**）：**

1. 在某个开发分支临时改 `OpenSearchRetrieverService.retrieve(...)` 头上注入 `retrieveParam.setMetadataFilters(java.util.Collections.emptyList());`（强行清空 builder 输出）
2. 重启后端，调一次普通 RAG chat
3. 后端日志应含 `OpenSearch filter contract violated for collection=...` 或对应 stack trace

**期望：**

- 后端抛 `IllegalStateException`，message 含 `filter contract violated`、`hasKbIdFilter=false`、`hasSecurityLevelFilter=false`
- 该异常**不**被 `catch (Exception)` 吞成 `List.of()` 返回（c2 加的 `catch (IllegalStateException) { throw e; }` 显式 rethrow）
- 异常通过 `ChatRateLimitAspect.invokeWithTrace` catch → `emitter.completeWithError(cause)`，前端 SSE 在 META 之后立即断流，**不**返回空答案降级（不要把 `未检索到与问题相关的文档内容。` 当成 PASS）

**反例：** 如果接口返回 200 + 空答案，说明 `catch (Exception)` 把契约违规吞了 → `enforceFilterContract` 被挪进了 try（grep gate G3 也会同时报错）或 `catch (IllegalStateException)` 被删 (G4 同时报错)。

实验完毕后 `git checkout -- bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java` 撤销改动。

---

## 自动化兜底

PR5 三类长效守门已自动化：

- `bash docs/dev/verification/permission-pr5-filter-contract.sh` — 4 道 grep gate（G1 new MetadataFilter 白名单 / G2 enforceFilterContract 存在 / G3 enforce 在 doSearch 第一处 try 之前 / G4 catch IllegalStateException rethrow）
- `MetadataFilterConstructionArchTest` — ArchUnit 静态规则禁止 `..rag.core.retrieve..` 内非 `DefaultMetadataFilterBuilder` 类构造 `MetadataFilter`
- 三通道对齐测试（c3）— `MetadataFilterBuilderTest` / 既有通道测试

S5/S6/S7 是这些自动化的运行时回归确认；S5b 是部署前数据兼容性专项。
