# RAG 评估闭环 PR E2 — 合成闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PR E1 地基上落合成闭环——从 KB 抽 chunk → 调 Python 合成 → 落 `t_eval_gold_item`（含 `source_chunk_text` 快照）+ 审核前端 + dataset 激活。

**Architecture:**
- Java 侧新增 `GoldDatasetService / GoldDatasetSynthesisService / GoldItemReviewService` + 两个 Controller + 一个跨域 port `KbChunkSamplerPort`（在 `framework.security.port`，impl 在 `knowledge/`）。
- 合成任务走 `evalExecutor`（PR E1 已建）异步；单批内按 `batch-size` 分批 HTTP 调 Python，规避单次超时。
- 前端 `/admin/eval-suites` 单页三 Tab 容器：Tab 1「黄金集」完整实现（列表 / 创建 / 触发合成 / 审核 / 激活）；Tab 2「评估运行」和 Tab 3「趋势对比」留占位空态，PR E3 填实。

**Tech Stack:** Spring Boot 3.5.7 / MyBatis Plus 3.5.14 / Sa-Token 1.43 / React 18 + Vite + TypeScript + shadcn/ui + Zustand + Recharts。

**硬约束（违反直接 review 打回，对齐 `eval/CLAUDE.md` + spec §9/§15）：**

1. **零新增 ThreadLocal**。`evalExecutor` 上跑的合成任务，`principalUserId` 通过方法参数传递；不用 TaskDecorator 续 UserContext；`RagasEvalClient` 不读 `RagTraceContext`。
2. **不用 `@Async`**。所有异步走 `@Qualifier("evalExecutor") + .execute(Runnable)`。
3. **所有 eval controller 端点 `@SaCheckRole("SUPER_ADMIN")`**。读写不分。EVAL-3（redaction）未落地前不得降级为 `AnyAdmin`。
4. **依赖方向硬约束**：`eval/ → knowledge.KbReadAccessPort`（✓）、`eval/ → framework.security.port.KbChunkSamplerPort`（✓，本 PR 新增）、`eval/ ↛ rag/` 内部表、`eval/ ↛ knowledge/dao/mapper` 直读 `t_knowledge_chunk` ——必须走 port。
5. **`@JsonProperty` 跨 HTTP 映射**：任何 HTTP DTO 新 record / DTO，跨 Python 字段名用 `@JsonProperty` 显式 snake_case 映射（对齐 `SynthesizedItem` 范式）。
6. **采样 SQL 固化**（spec §6.1，不得偏移）：`c.doc_id`（不是 `document_id`）、`d.doc_name`（不是 `name`）、`enabled=1`、`d.status='success'`、`d.deleted=0 AND c.deleted=0`、`ORDER BY c.doc_id, RANDOM()`。
7. **`source_chunk_text` 字节级冻结**：合成时 Java 侧已 JOIN 查出 `chunk.content`，入 `t_eval_gold_item.source_chunk_text` 前**不再 trim / 清洗 / 截断**；KB 重切后 chunk_id 失效，快照文本是唯一锚点。

**PR 不做的事（硬停止点）：**
- ❌ `AnswerPipeline` 抽取 / `ChatForEvalService` —— PR E3-spike + PR E3 的事
- ❌ `/evaluate` 端点 Java 侧 / `EvalRunService` / `EvalRunExecutor` / `SystemSnapshotBuilder` —— PR E3
- ❌ Trend 页 / Run Detail 页 —— PR E3
- ❌ 查询侧 redaction（EVAL-3） —— 独立 PR
- ❌ legacy `@Async` 失效修复（EVAL-2） —— 独立 PR

---

## 前置代码现状（已 grep 确认无漂移）

- `RagentApplication.@MapperScan` 已含 `com.nageoffer.ai.ragent.eval.dao.mapper`
- `application.yaml` `rag.eval.*` 已完整（`python-service / synthesis / run` 三组）
- `EvalProperties`（9 字段）、`EvalAsyncConfig.evalExecutor`（core=2, max=4, queue=50）、`RagasEvalClient`（`synthesize / health`）、4 DO + 4 Mapper 均已就绪
- 4 张 `t_eval_*` 表已 via `upgrade_v1.9_to_v1.10.sql` 落到 `schema_pg.sql`
- Python `ragas/ragas/{app,synthesize,settings}.py` + `/health` + `/synthesize` 已上线
- Frontend `/admin/evaluations`（legacy）保留；`permissions.ts` 已有 `AdminMenuId` union，本 PR 追加 `eval-suites` 成员

---

## File Structure

**Java 新增：**

```
framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/
└── KbChunkSamplerPort.java                ← 新 port，含 ChunkSample record

bootstrap/src/main/java/com/nageoffer/ai/ragent/
├── knowledge/
│   ├── dao/mapper/
│   │   └── KbChunkSamplerMapper.java      ← 新 Mapper，@Select 固化 spec SQL
│   └── service/impl/
│       └── KbChunkSamplerImpl.java        ← Port 实现（Java 侧做 per-doc 去重）
└── eval/
    ├── controller/
    │   ├── GoldDatasetController.java     ← @SaCheckRole("SUPER_ADMIN") 全覆盖
    │   ├── GoldItemController.java
    │   ├── request/
    │   │   ├── CreateGoldDatasetRequest.java
    │   │   ├── TriggerSynthesisRequest.java
    │   │   ├── ReviewGoldItemRequest.java
    │   │   └── EditGoldItemRequest.java
    │   └── vo/
    │       ├── GoldDatasetVO.java
    │       ├── GoldItemVO.java
    │       └── SynthesisProgressVO.java
    ├── service/
    │   ├── GoldDatasetService.java + impl/GoldDatasetServiceImpl.java
    │   ├── GoldDatasetSynthesisService.java + impl/GoldDatasetSynthesisServiceImpl.java
    │   ├── GoldItemReviewService.java + impl/GoldItemReviewServiceImpl.java
    │   └── SynthesisProgressTracker.java  ← @Component 进程内 ConcurrentHashMap<datasetId, Progress>
    └── domain/
        └── SynthesisProgress.java         ← record（total / processed / failed / status / error）
```

**Java 修改：**

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md` — 追加 Service 列表 + PR E2 新 gotcha
- `bootstrap/CLAUDE.md` — 在"各域关键类" 的 eval 域段补 Service/Controller 清单
- `bootstrap/src/main/resources/application.yaml` — 加 `rag.eval.synthesis.batch-size: 5` + 独立 `synthesis-timeout-ms: 600000`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java` — 补 `batchSize` / `synthesisTimeoutMs` 两字段
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java` — 把 `synthesize()` 切到独立 timeout（使用 JdkClientHttpRequestFactory）

**Java 测试新增：**

```
bootstrap/src/test/java/com/nageoffer/ai/ragent/
├── knowledge/service/impl/
│   └── KbChunkSamplerImplTest.java        ← @SpringBootTest，真实 PG fixture + 按 doc 去重
└── eval/service/
    ├── GoldDatasetServiceImplTest.java    ← 纯 Mockito，状态机 + 唯一约束
    ├── GoldDatasetSynthesisServiceImplTest.java ← WireMock + stubbed port + 冻结快照断言
    └── GoldItemReviewServiceImplTest.java ← 纯 Mockito，review_status 迁移
```

**Frontend 新增：**

```
frontend/src/
├── services/evalSuiteService.ts           ← DTO + axios calls
└── pages/admin/eval-suites/
    ├── EvalSuitesPage.tsx                 ← 容器 + 3 Tab（只 Tab 1 有内容）
    ├── tabs/
    │   ├── GoldSetListTab.tsx             ← 列表 + KB 筛选 + 创建 / 合成 / 激活 / 删除
    │   ├── GoldSetReviewPage.tsx          ← 左右分屏审核（y/n/e 快捷键）
    │   └── placeholders/                  ← Tab 2/3 占位（"敬请期待 PR E3"）
    │       ├── RunsPlaceholderTab.tsx
    │       └── TrendsPlaceholderTab.tsx
    └── components/
        ├── CreateGoldSetDialog.tsx
        ├── TriggerSynthesisDialog.tsx
        └── SynthesisProgressDialog.tsx    ← 轮询 + 转跳审核
```

**Frontend 修改：**

- `frontend/src/utils/permissions.ts` — `AdminMenuId` 追加 `"eval-suites"`
- `frontend/src/router.tsx` — 追加 `/admin/eval-suites` + `/admin/eval-suites/:datasetId/review` 两条路由，全部 `<RequireSuperAdmin>`
- `frontend/src/pages/admin/AdminLayout.tsx` — "评测记录"（legacy）下方加"质量评估"条目，不同 icon（`FlaskConical`）

**Docs / ops：**

- `docs/dev/followup/backlog.md` — EVAL-3 条目状态确认仍 open（PR E2 不消它）；新增 EVAL-4 "合成任务可恢复性" 如果 PR 中讨论过
- `log/dev_log/2026-04-25-eval-pr-e2-synthesis.md` — 本 PR session log（最后一步写）
- `docs/dev/gotchas.md` — 追加"eval 域"主题组若出现新坑点

---

## Task 0: Branch bootstrap

**Files:** none (git)

- [ ] **Step 1: Fetch latest main + create feature branch**

```bash
git fetch origin
git checkout main
git pull --ff-only origin main
git checkout -b feature/eval-e2-synthesis
```

- [ ] **Step 2: Verify PR E1 baseline runs**

```bash
mvn -pl bootstrap test -Dtest="Eval*Test"
```

Expected: `EvalPropertiesTest / EvalAsyncConfigTest / EvalMapperScanTest` 全绿。

---

## Task 1: `KbChunkSamplerPort` — 跨域 chunk 采样端口

**Files:**
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbChunkSamplerPort.java`

- [ ] **Step 1: 新建 port**

```java
package com.nageoffer.ai.ragent.framework.security.port;

import java.util.List;

/**
 * 跨域 chunk 采样端口。
 * eval 域通过该 port 从 knowledge 域按 KB 抽取 chunk 用于 Gold Set 合成；
 * 不得绕过该 port 直读 t_knowledge_chunk / t_knowledge_document（硬约束）。
 */
public interface KbChunkSamplerPort {

    /**
     * 从指定 KB 随机采样 chunk（joined with document name），按每 doc 最多 maxPerDoc 条去重。
     *
     * <p>过滤条件（与 spec §6.1 固化，不得改动）：
     * chunk.deleted = 0 AND chunk.enabled = 1 AND
     * doc.deleted   = 0 AND doc.enabled   = 1 AND doc.status = 'success'
     *
     * @param kbId       知识库 ID
     * @param count      目标返回条数（上限）
     * @param maxPerDoc  每 doc 最多贡献条数
     * @return 随机顺序的 chunk 快照（count 不足时尽可能多返回，不抛异常）
     */
    List<ChunkSample> sampleForSynthesis(String kbId, int count, int maxPerDoc);

    /**
     * 采样结果的单条快照——字节级冻结 chunk.content / doc.doc_name。
     * eval 域拿到后直接写入 t_eval_gold_item，不做任何修改。
     */
    record ChunkSample(String chunkId, String chunkText, String docId, String docName) {
    }
}
```

（记得加项目标准的 ASF License header，以项目现有其他 port 文件为模板复制。）

- [ ] **Step 2: Compile framework module**

Run: `mvn -pl framework install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbChunkSamplerPort.java
git commit -m "feat(eval): add KbChunkSamplerPort for cross-domain chunk sampling"
```

---

## Task 2: `KbChunkSamplerImpl` + Mapper + 集成测试

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/KbChunkSamplerMapper.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbChunkSamplerImpl.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbChunkSamplerImplTest.java`

- [ ] **Step 1: 写失败测试（需要已启动 PG 容器 + schema 已初始化）**

```java
package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort.ChunkSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KbChunkSamplerImplTest {

    @Autowired KbChunkSamplerPort port;
    @Autowired JdbcTemplate jdbc;

    private static final String KB_ID = "eval-sampler-kb";

    @BeforeEach
    void seedFixture() {
        jdbc.update("INSERT INTO t_knowledge_base(id, name, collection_name, embedding_model, created_by, dept_id) "
                + "VALUES (?, 'sampler-kb', 'sampler_kb', 'text-embedding-v3', 'sys', '1')", KB_ID);
        jdbc.update("INSERT INTO t_knowledge_document(id, kb_id, doc_name, file_url, file_type, status, enabled, created_by) "
                + "VALUES ('d1', ?, 'Doc A', 'url-a', 'pdf', 'success', 1, 'sys')", KB_ID);
        jdbc.update("INSERT INTO t_knowledge_document(id, kb_id, doc_name, file_url, file_type, status, enabled, created_by) "
                + "VALUES ('d2', ?, 'Doc B', 'url-b', 'pdf', 'success', 1, 'sys')", KB_ID);
        jdbc.update("INSERT INTO t_knowledge_document(id, kb_id, doc_name, file_url, file_type, status, enabled, created_by) "
                + "VALUES ('d3', ?, 'Doc C pending', 'url-c', 'pdf', 'pending', 1, 'sys')", KB_ID);
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                    + "VALUES (?, ?, 'd1', ?, ?, 1, 'sys')", "d1-c" + i, KB_ID, i, "doc1 chunk " + i);
            jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                    + "VALUES (?, ?, 'd2', ?, ?, 1, 'sys')", "d2-c" + i, KB_ID, i, "doc2 chunk " + i);
            jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                    + "VALUES (?, ?, 'd3', ?, ?, 1, 'sys')", "d3-c" + i, KB_ID, i, "should-not-appear " + i);
        }
        jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                + "VALUES ('d1-disabled', ?, 'd1', 99, 'disabled chunk', 0, 'sys')", KB_ID);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_knowledge_chunk WHERE kb_id=?", KB_ID);
        jdbc.update("DELETE FROM t_knowledge_document WHERE kb_id=?", KB_ID);
        jdbc.update("DELETE FROM t_knowledge_base WHERE id=?", KB_ID);
    }

    @Test
    void sampleForSynthesis_respects_count_and_per_doc_cap() {
        List<ChunkSample> samples = port.sampleForSynthesis(KB_ID, 6, 3);

        assertThat(samples).hasSize(6);

        Map<String, Long> perDoc = samples.stream()
                .collect(Collectors.groupingBy(ChunkSample::docId, Collectors.counting()));
        perDoc.values().forEach(c -> assertThat(c).isLessThanOrEqualTo(3L));

        assertThat(samples).extracting(ChunkSample::docId).doesNotContain("d3");
        assertThat(samples).extracting(ChunkSample::chunkId).doesNotContain("d1-disabled");

        samples.forEach(s -> {
            assertThat(s.chunkText()).isNotBlank();
            assertThat(s.docName()).isIn("Doc A", "Doc B");
        });
    }

    @Test
    void sampleForSynthesis_returns_fewer_when_pool_too_small() {
        List<ChunkSample> samples = port.sampleForSynthesis(KB_ID, 100, 3);
        assertThat(samples).hasSize(6);
    }
}
```

- [ ] **Step 2: 跑测试确认 FAIL**

Run: `mvn -pl bootstrap test -Dtest=KbChunkSamplerImplTest`
Expected: FAIL — `KbChunkSamplerPort` bean 未装配。

- [ ] **Step 3: 新建 Mapper（@Select 固化 spec SQL）**

```java
package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * chunk 采样专用 Mapper——JOIN document 表，固化 spec §6.1 过滤条件。
 * 不绑 BaseMapper——纯 @Select，走 @MapperScan 扫描即可装配。
 */
@Mapper
public interface KbChunkSamplerMapper {

    @Select("SELECT c.id AS chunk_id, c.content AS chunk_text, c.doc_id AS doc_id, d.doc_name AS doc_name "
            + "FROM t_knowledge_chunk c "
            + "JOIN t_knowledge_document d ON c.doc_id = d.id "
            + "WHERE c.kb_id = #{kbId} "
            + "AND c.deleted = 0 AND c.enabled = 1 "
            + "AND d.deleted = 0 AND d.enabled = 1 AND d.status = 'success' "
            + "ORDER BY c.doc_id, RANDOM() "
            + "LIMIT #{hardLimit}")
    List<Map<String, Object>> sampleRaw(@Param("kbId") String kbId, @Param("hardLimit") int hardLimit);
}
```

- [ ] **Step 4: 新建 Port 实现（Java 侧 per-doc dedup）**

```java
package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KbChunkSamplerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KbChunkSamplerImpl implements KbChunkSamplerPort {

    private static final int HARD_LIMIT_MULTIPLIER = 10;
    private static final int MIN_HARD_LIMIT = 200;

    private final KbChunkSamplerMapper mapper;

    @Override
    public List<ChunkSample> sampleForSynthesis(String kbId, int count, int maxPerDoc) {
        if (count <= 0 || maxPerDoc <= 0) {
            return Collections.emptyList();
        }
        int hardLimit = Math.max(MIN_HARD_LIMIT, count * HARD_LIMIT_MULTIPLIER);

        List<Map<String, Object>> rows = mapper.sampleRaw(kbId, hardLimit);

        Map<String, Integer> perDocCount = new HashMap<>();
        List<ChunkSample> picked = new ArrayList<>(count);
        for (Map<String, Object> row : rows) {
            String docId = (String) row.get("doc_id");
            int taken = perDocCount.getOrDefault(docId, 0);
            if (taken >= maxPerDoc) continue;
            picked.add(new ChunkSample(
                    (String) row.get("chunk_id"),
                    (String) row.get("chunk_text"),
                    docId,
                    (String) row.get("doc_name")
            ));
            perDocCount.put(docId, taken + 1);
            if (picked.size() >= count) break;
        }
        return picked;
    }
}
```

- [ ] **Step 5: 跑测试确认 PASS**

Run: `mvn -pl bootstrap test -Dtest=KbChunkSamplerImplTest`
Expected: 2/2 PASS。

- [ ] **Step 6: Commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbChunkSamplerPort.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/mapper/KbChunkSamplerMapper.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbChunkSamplerImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbChunkSamplerImplTest.java
git commit -m "feat(eval): implement KbChunkSamplerPort with per-doc dedup"
```

---

## Task 3: 扩展 `EvalProperties` 与 yaml（batch-size + synthesis-timeout）

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/config/EvalPropertiesTest.java`

- [ ] **Step 1: 测试先加失败断言**

在 `EvalPropertiesTest` 里追加：

```java
@Test
void synthesisBatchSizeBindsFromYaml() {
    // 已有 Binder 测试风格；复用 binder 断言 synthesis.batchSize==5 + synthesisTimeoutMs==600_000
    assertThat(properties.getSynthesis().getBatchSize()).isEqualTo(5);
    assertThat(properties.getSynthesis().getSynthesisTimeoutMs()).isEqualTo(600_000);
}
```

Run: `mvn -pl bootstrap test -Dtest=EvalPropertiesTest`
Expected: FAIL — getter 不存在。

- [ ] **Step 2: 扩展 `EvalProperties.Synthesis`**

```java
@Data
public static class Synthesis {
    private int defaultCount = 50;
    private int maxPerDoc = 5;
    private String strongModel = "qwen-max";
    /** Java 端把 N 个 chunk 拆多少批送 Python，单批 timeout 受控于 synthesisTimeoutMs */
    private int batchSize = 5;
    /** 单次 /synthesize HTTP 调用的超时，远大于 pythonService.timeoutMs（120s）——LLM 批调用可能 6-10 分钟 */
    private int synthesisTimeoutMs = 600_000;
}
```

- [ ] **Step 3: 扩展 yaml**

```yaml
rag:
  eval:
    synthesis:
      default-count: 50
      max-per-doc: 5
      strong-model: qwen-max
      batch-size: 5
      synthesis-timeout-ms: 600000
```

- [ ] **Step 4: 跑测试确认 PASS**

Run: `mvn -pl bootstrap test -Dtest=EvalPropertiesTest`
Expected: ALL PASS。

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java \
        bootstrap/src/main/resources/application.yaml \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/config/EvalPropertiesTest.java
git commit -m "feat(eval): add synthesis.batchSize + synthesis-timeout-ms config"
```

---

## Task 4: `RagasEvalClient` 支持独立 synthesize timeout

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java`

- [ ] **Step 1: 改造 `buildClient` 支持 per-call timeout**

```java
private RestClient buildClient(int timeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Math.min(timeoutMs, 10_000));
    factory.setReadTimeout(timeoutMs);
    return RestClient.builder()
            .requestFactory(factory)
            .baseUrl(evalProperties.getPythonService().getUrl())
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .requestInterceptor((request, body, execution) -> {
                log.debug("[ragas-eval] {} {}", request.getMethod(), request.getURI());
                return execution.execute(request, body);
            })
            .build();
}

public SynthesizeResponse synthesize(SynthesizeRequest request) {
    return buildClient(evalProperties.getSynthesis().getSynthesisTimeoutMs())
            .post()
            .uri("/synthesize")
            .body(request)
            .retrieve()
            .body(SynthesizeResponse.class);
}

public HealthStatus health() {
    return buildClient(evalProperties.getPythonService().getTimeoutMs())
            .get()
            .uri("/health")
            .retrieve()
            .body(HealthStatus.class);
}
```

Import `import org.springframework.http.client.SimpleClientHttpRequestFactory;`。

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java
git commit -m "feat(eval): set per-call timeout for RagasEvalClient.synthesize"
```

---

## Task 5: `SynthesisProgress` record + `SynthesisProgressTracker`

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesisProgress.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/SynthesisProgressTracker.java`

- [ ] **Step 1: 建 record**

```java
package com.nageoffer.ai.ragent.eval.domain;

/**
 * 合成任务进度快照——进程内可读，不入库。
 * status: IDLE / RUNNING / COMPLETED / FAILED
 */
public record SynthesisProgress(
        String status,
        int total,
        int processed,
        int failed,
        String error
) {
    public static SynthesisProgress idle() {
        return new SynthesisProgress("IDLE", 0, 0, 0, null);
    }
    public static SynthesisProgress running(int total, int processed, int failed) {
        return new SynthesisProgress("RUNNING", total, processed, failed, null);
    }
    public static SynthesisProgress completed(int total, int processed, int failed) {
        return new SynthesisProgress("COMPLETED", total, processed, failed, null);
    }
    public static SynthesisProgress failed(int total, int processed, int failed, String error) {
        return new SynthesisProgress("FAILED", total, processed, failed, error);
    }
}
```

- [ ] **Step 2: 建 tracker**

```java
package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.domain.SynthesisProgress;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内合成进度追踪器。
 * 不持久化——重启后所有 RUNNING 状态丢失；重启恢复策略见本 PR 文档中的"已知边界"段。
 */
@Component
public class SynthesisProgressTracker {

    private final ConcurrentHashMap<String, SynthesisProgress> map = new ConcurrentHashMap<>();

    public void begin(String datasetId, int total) {
        map.put(datasetId, SynthesisProgress.running(total, 0, 0));
    }

    /**
     * 原子占坑——trigger 入口调用；并发第二个请求会 return false 被拒。
     * 使用 putIfAbsent 防"两个请求都看到 existing=0 就各自 execute"的 race。
     * 占坑后 total=0；真正采样完再调 {@link #begin(String, int)} 覆盖 total。
     *
     * <p>一旦占坑，tracker 保留 RUNNING/COMPLETED/FAILED 终态直到重启或显式 clear；
     * 同一 datasetId 第二次 trigger 会被此方法挡住——合成只跑一次，失败后 delete dataset 重建。
     */
    public boolean tryBegin(String datasetId) {
        return map.putIfAbsent(datasetId, SynthesisProgress.running(0, 0, 0)) == null;
    }

    public void update(String datasetId, int processed, int failed) {
        SynthesisProgress prev = map.get(datasetId);
        int total = prev != null ? prev.total() : processed + failed;
        map.put(datasetId, SynthesisProgress.running(total, processed, failed));
    }

    public void complete(String datasetId, int processed, int failed) {
        SynthesisProgress prev = map.get(datasetId);
        int total = prev != null ? prev.total() : processed + failed;
        map.put(datasetId, SynthesisProgress.completed(total, processed, failed));
    }

    public void fail(String datasetId, String error) {
        SynthesisProgress prev = map.get(datasetId);
        int total = prev != null ? prev.total() : 0;
        int processed = prev != null ? prev.processed() : 0;
        int failed = prev != null ? prev.failed() : 0;
        map.put(datasetId, SynthesisProgress.failed(total, processed, failed, error));
    }

    public SynthesisProgress get(String datasetId) {
        return map.getOrDefault(datasetId, SynthesisProgress.idle());
    }

    public boolean isRunning(String datasetId) {
        SynthesisProgress p = map.get(datasetId);
        return p != null && "RUNNING".equals(p.status());
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -pl bootstrap compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesisProgress.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/SynthesisProgressTracker.java
git commit -m "feat(eval): add in-memory SynthesisProgressTracker"
```

---

## Task 6: `GoldDatasetService` — CRUD + 状态机

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/GoldDatasetService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetServiceImpl.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/GoldDatasetVO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/CreateGoldDatasetRequest.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetServiceImplTest.java`

- [ ] **Step 1: 建 VO / Request record**

```java
// controller/vo/GoldDatasetVO.java
package com.nageoffer.ai.ragent.eval.controller.vo;

import java.util.Date;

public record GoldDatasetVO(
        String id,
        String kbId,
        String name,
        String description,
        String status,           // DRAFT / ACTIVE / ARCHIVED
        int itemCount,           // 已 APPROVED 条数（合成后、审核前为 0）
        int pendingItemCount,    // PENDING 条数；UI 激活按钮 gate 用
        int totalItemCount,      // 合成产生的全部条数，含 PENDING/REJECTED
        Date createTime,
        Date updateTime
) {
}
```

```java
// controller/request/CreateGoldDatasetRequest.java
package com.nageoffer.ai.ragent.eval.controller.request;

import lombok.Data;

@Data
public class CreateGoldDatasetRequest {
    private String kbId;
    private String name;
    private String description;
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.nageoffer.ai.ragent.eval.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldDatasetVO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldDatasetServiceImplTest {

    private GoldDatasetMapper datasetMapper;
    private GoldItemMapper itemMapper;
    private GoldDatasetServiceImpl service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(GoldDatasetMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        service = new GoldDatasetServiceImpl(datasetMapper, itemMapper);
    }

    @Test
    void create_persists_with_DRAFT_status_and_returns_id() {
        // mock mapper 不会像真 MyBatis Plus 那样填 @TableId(ASSIGN_ID)；用 doAnswer 模拟
        when(datasetMapper.insert(any(GoldDatasetDO.class))).thenAnswer(inv -> {
            GoldDatasetDO d = inv.getArgument(0);
            d.setId("mock-id-" + java.util.UUID.randomUUID());
            return 1;
        });

        CreateGoldDatasetRequest req = new CreateGoldDatasetRequest();
        req.setKbId("kb-1");
        req.setName("smoke-50");
        req.setDescription("d");

        String id = service.create(req, "user-sys");

        ArgumentCaptor<GoldDatasetDO> cap = ArgumentCaptor.forClass(GoldDatasetDO.class);
        verify(datasetMapper).insert(cap.capture());
        GoldDatasetDO saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getItemCount()).isEqualTo(0);
        assertThat(saved.getKbId()).isEqualTo("kb-1");
        assertThat(saved.getCreatedBy()).isEqualTo("user-sys");
        assertThat(id).isNotBlank();
    }

    @Test
    void activate_rejects_non_DRAFT() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);

        assertThatThrownBy(() -> service.activate("d1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("status");
    }

    @Test
    void activate_DRAFT_moves_to_ACTIVE_when_no_pending() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        // impl 会按 review_status 分别查 APPROVED / PENDING；用 Answer 按 wrapper 区分
        when(itemMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            // 简化：approvals=10, pendings=0。若实现改为 lambda 不同，测试需调整
            Wrapper<?> w = inv.getArgument(0);
            String sql = w.getSqlSegment();
            if (sql.contains("APPROVED")) return 10L;
            if (sql.contains("PENDING")) return 0L;
            return 10L;
        });

        service.activate("d1");

        ArgumentCaptor<GoldDatasetDO> cap = ArgumentCaptor.forClass(GoldDatasetDO.class);
        verify(datasetMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(cap.getValue().getItemCount()).isEqualTo(10);
    }

    @Test
    void activate_rejects_when_no_approved_items() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.activate("d1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void activate_rejects_when_pending_items_remain() {
        // spec §10 gotcha #3：激活前必须审完；否则 PENDING 条目会被 requireDraftDataset 永久锁
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            String sql = w.getSqlSegment();
            if (sql.contains("APPROVED")) return 5L;
            if (sql.contains("PENDING")) return 3L;
            return 8L;
        });

        assertThatThrownBy(() -> service.activate("d1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("PENDING");
    }
}
```

Run: `mvn -pl bootstrap test -Dtest=GoldDatasetServiceImplTest`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 建 Service 接口**

```java
package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldDatasetVO;

import java.util.List;

public interface GoldDatasetService {
    /** 创建空数据集（DRAFT / item_count=0），返回 datasetId。*/
    String create(CreateGoldDatasetRequest req, String createdBy);

    /** 列出数据集。kbId 可选筛选；status 可选筛选（DRAFT/ACTIVE/ARCHIVED）。*/
    List<GoldDatasetVO> list(String kbId, String status);

    /** 详情（含 totalItemCount 聚合，方便前端判断是否合成过）。*/
    GoldDatasetVO detail(String datasetId);

    /** DRAFT → ACTIVE；前置：存在 ≥1 条 APPROVED item。*/
    void activate(String datasetId);

    /** ACTIVE → ARCHIVED。*/
    void archive(String datasetId);

    /** 软删——仅允许 DRAFT 或 ARCHIVED；ACTIVE 态必须先 archive。*/
    void delete(String datasetId);
}
```

- [ ] **Step 4: 建 Impl**

```java
package com.nageoffer.ai.ragent.eval.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldDatasetVO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.service.GoldDatasetService;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GoldDatasetServiceImpl implements GoldDatasetService {

    private final GoldDatasetMapper datasetMapper;
    private final GoldItemMapper itemMapper;

    @Override
    public String create(CreateGoldDatasetRequest req, String createdBy) {
        if (req.getKbId() == null || req.getKbId().isBlank()) {
            throw new ClientException("kbId required", BaseErrorCode.CLIENT_ERROR);
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new ClientException("name required", BaseErrorCode.CLIENT_ERROR);
        }
        GoldDatasetDO ds = GoldDatasetDO.builder()
                .kbId(req.getKbId())
                .name(req.getName().trim())
                .description(req.getDescription())
                .status("DRAFT")
                .itemCount(0)
                .createdBy(createdBy)
                .updatedBy(createdBy)
                .build();
        datasetMapper.insert(ds);
        return ds.getId();
    }

    @Override
    public List<GoldDatasetVO> list(String kbId, String status) {
        LambdaQueryWrapper<GoldDatasetDO> q = new LambdaQueryWrapper<>();
        if (kbId != null && !kbId.isBlank()) q.eq(GoldDatasetDO::getKbId, kbId);
        if (status != null && !status.isBlank()) q.eq(GoldDatasetDO::getStatus, status);
        q.orderByDesc(GoldDatasetDO::getCreateTime);
        return datasetMapper.selectList(q).stream().map(this::toVO).toList();
    }

    @Override
    public GoldDatasetVO detail(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        return toVO(ds);
    }

    @Override
    public void activate(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        if (!"DRAFT".equals(ds.getStatus())) {
            throw new ClientException("can only activate DRAFT dataset, current status=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        long approved = countApproved(datasetId);
        if (approved == 0) {
            throw new ClientException("no APPROVED items; cannot activate", BaseErrorCode.CLIENT_ERROR);
        }
        long pending = countPending(datasetId);
        if (pending > 0) {
            // spec §10 gotcha #3：ACTIVE 下不允许增删/改 item。若激活时还有 PENDING，这些条目会被永久锁死
            throw new ClientException(pending + " items still PENDING; review all before activating",
                    BaseErrorCode.CLIENT_ERROR);
        }
        ds.setStatus("ACTIVE");
        ds.setItemCount((int) approved);
        datasetMapper.updateById(ds);
    }

    @Override
    public void archive(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        if (!"ACTIVE".equals(ds.getStatus())) {
            throw new ClientException("can only archive ACTIVE dataset, current status=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        ds.setStatus("ARCHIVED");
        datasetMapper.updateById(ds);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void delete(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        if ("ACTIVE".equals(ds.getStatus())) {
            throw new ClientException("ACTIVE dataset must be archived before delete", BaseErrorCode.CLIENT_ERROR);
        }
        // 级联软删子 item——避免 orphan 数据；两个 @TableLogic 都会命中
        itemMapper.delete(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId));
        datasetMapper.deleteById(datasetId);
    }

    private GoldDatasetDO required(String datasetId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || Objects.equals(ds.getDeleted(), 1)) {
            throw new ClientException("dataset not found: " + datasetId, BaseErrorCode.CLIENT_ERROR);
        }
        return ds;
    }

    private long countApproved(String datasetId) {
        return itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .eq(GoldItemDO::getReviewStatus, "APPROVED"));
    }

    private long countPending(String datasetId) {
        return itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .eq(GoldItemDO::getReviewStatus, "PENDING"));
    }

    private long countAll(String datasetId) {
        return itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId));
    }

    private GoldDatasetVO toVO(GoldDatasetDO ds) {
        long total = countAll(ds.getId());
        long approved = countApproved(ds.getId());
        long pending = countPending(ds.getId());
        return new GoldDatasetVO(
                ds.getId(),
                ds.getKbId(),
                ds.getName(),
                ds.getDescription(),
                ds.getStatus(),
                (int) approved,
                (int) pending,
                (int) total,
                ds.getCreateTime(),
                ds.getUpdateTime()
        );
    }
}
```

- [ ] **Step 5: 跑测试 PASS**

Run: `mvn -pl bootstrap test -Dtest=GoldDatasetServiceImplTest`
Expected: 4/4 PASS。

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/GoldDatasetService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/GoldDatasetVO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/CreateGoldDatasetRequest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetServiceImplTest.java
git commit -m "feat(eval): add GoldDatasetService with DRAFT/ACTIVE/ARCHIVED state machine"
```

---

## Task 7: `GoldDatasetSynthesisService` — 采样 + 分批调 Python + 异步

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/GoldDatasetSynthesisService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetSynthesisServiceImpl.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/TriggerSynthesisRequest.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetSynthesisServiceImplTest.java`

- [ ] **Step 1: 建 Request**

```java
package com.nageoffer.ai.ragent.eval.controller.request;

import lombok.Data;

@Data
public class TriggerSynthesisRequest {
    /** 目标合成条数（上限）；缺省用 rag.eval.synthesis.default-count */
    private Integer count;
}
```

- [ ] **Step 2: 写失败测试——冻结快照 + 零 ThreadLocal**

```java
package com.nageoffer.ai.ragent.eval.service.impl;

import com.nageoffer.ai.ragent.eval.client.RagasEvalClient;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.domain.SynthesizeRequest;
import com.nageoffer.ai.ragent.eval.domain.SynthesizeResponse;
import com.nageoffer.ai.ragent.eval.domain.SynthesizedItem;
import com.nageoffer.ai.ragent.eval.service.SynthesisProgressTracker;
import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort.ChunkSample;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldDatasetSynthesisServiceImplTest {

    private GoldDatasetMapper datasetMapper;
    private GoldItemMapper itemMapper;
    private KbChunkSamplerPort sampler;
    private RagasEvalClient client;
    private SynthesisProgressTracker tracker;
    private EvalProperties props;
    private GoldDatasetSynthesisServiceImpl service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(GoldDatasetMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        sampler = mock(KbChunkSamplerPort.class);
        client = mock(RagasEvalClient.class);
        tracker = new SynthesisProgressTracker();
        props = new EvalProperties();
        props.getSynthesis().setDefaultCount(50);
        props.getSynthesis().setMaxPerDoc(5);
        props.getSynthesis().setBatchSize(2);
        props.getSynthesis().setStrongModel("qwen-max");
        // 7-arg 构造器是唯一 public 构造器；测试传 null executor → trigger() 会抛 IllegalStateException，
        // 测试只驱动 runSynthesisSync 同步路径
        service = new GoldDatasetSynthesisServiceImpl(datasetMapper, itemMapper, sampler, client, tracker, props, null);
    }

    @Test
    void runSynthesis_rejects_if_items_already_exist() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.runSynthesisSync("d1", 10, "user-sys"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("already");
    }

    @Test
    void runSynthesis_freezes_chunk_text_and_doc_name_from_java_side() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);

        ChunkSample sampled = new ChunkSample("chunk-A", "the-full-chunk-text", "doc-X", "Doc X Name");
        when(sampler.sampleForSynthesis("kb-1", 1, 5)).thenReturn(List.of(sampled));

        SynthesizedItem item = new SynthesizedItem("chunk-A", "what is X?", "X is the answer");
        when(client.synthesize(any(SynthesizeRequest.class)))
                .thenReturn(new SynthesizeResponse(List.of(item), List.of()));

        service.runSynthesisSync("d1", 1, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).insert(cap.capture());
        GoldItemDO saved = cap.getValue();
        // 硬约束：快照字段从 Java 侧 sampled 冻结，非 Python 返回值
        assertThat(saved.getSourceChunkText()).isEqualTo("the-full-chunk-text");
        assertThat(saved.getSourceDocName()).isEqualTo("Doc X Name");
        assertThat(saved.getSourceDocId()).isEqualTo("doc-X");
        assertThat(saved.getSourceChunkId()).isEqualTo("chunk-A");
        assertThat(saved.getQuestion()).isEqualTo("what is X?");
        assertThat(saved.getGroundTruthAnswer()).isEqualTo("X is the answer");
        assertThat(saved.getReviewStatus()).isEqualTo("PENDING");
        assertThat(saved.getSynthesizedByModel()).isEqualTo("qwen-max");
        assertThat(tracker.get("d1").status()).isEqualTo("COMPLETED");
    }

    @Test
    void runSynthesis_tracks_failed_chunk_ids_without_inserting() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);

        ChunkSample good = new ChunkSample("c-ok", "ok-text", "d1", "doc");
        ChunkSample bad = new ChunkSample("c-bad", "bad-text", "d1", "doc");
        when(sampler.sampleForSynthesis(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(good, bad));

        when(client.synthesize(any(SynthesizeRequest.class))).thenReturn(
                new SynthesizeResponse(
                        List.of(new SynthesizedItem("c-ok", "q?", "a.")),
                        List.of("c-bad")));

        service.runSynthesisSync("d1", 2, "user-sys");

        verify(itemMapper, never()).insert(argThat((GoldItemDO i) -> "c-bad".equals(i.getSourceChunkId())));
        assertThat(tracker.get("d1").failed()).isEqualTo(1);
        assertThat(tracker.get("d1").processed()).isEqualTo(1);
    }

    @Test
    void runSynthesis_splits_by_batchSize() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);

        List<ChunkSample> samples = List.of(
                new ChunkSample("a", "a", "d", "D"),
                new ChunkSample("b", "b", "d", "D"),
                new ChunkSample("c", "c", "d", "D"),
                new ChunkSample("e", "e", "d", "D"),
                new ChunkSample("f", "f", "d", "D")
        );
        when(sampler.sampleForSynthesis(anyString(), anyInt(), anyInt())).thenReturn(samples);
        when(client.synthesize(any(SynthesizeRequest.class))).thenAnswer(inv -> {
            SynthesizeRequest req = inv.getArgument(0);
            return new SynthesizeResponse(
                    req.chunks().stream()
                            .map(c -> new SynthesizedItem(c.id(), "q", "a"))
                            .toList(),
                    List.of());
        });

        service.runSynthesisSync("d1", 5, "user-sys");

        // batchSize=2 + 5 chunks → 3 次 HTTP 调用（2 + 2 + 1）
        verify(client, org.mockito.Mockito.times(3)).synthesize(any(SynthesizeRequest.class));
    }

    @Test
    void trigger_with_null_executor_throws_IllegalStateException() {
        // tracker 已占位的情况也应失败——executor 是真正的阻塞门
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.trigger("d1", 5, "user-sys"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evalExecutor");
    }
}
```

Run: `mvn -pl bootstrap test -Dtest=GoldDatasetSynthesisServiceImplTest`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 建 Service 接口**

```java
package com.nageoffer.ai.ragent.eval.service;

/**
 * Gold Set 合成服务。
 * 异步入口 trigger(datasetId, count) 提交到 evalExecutor；
 * 同步入口 runSynthesisSync 用于单测和 controller 显式阻塞场景（生产不用）。
 */
public interface GoldDatasetSynthesisService {

    /** 异步触发合成——立即返回，任务进 evalExecutor；通过 SynthesisProgressTracker 轮询进度。*/
    void trigger(String datasetId, int count, String principalUserId);

    /** 同步执行——测试或管理脚本用；生产 controller 不要直调，用 trigger。*/
    void runSynthesisSync(String datasetId, int count, String principalUserId);
}
```

- [ ] **Step 4: 建 Impl**

```java
package com.nageoffer.ai.ragent.eval.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.client.RagasEvalClient;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.domain.SynthesizeChunkInput;
import com.nageoffer.ai.ragent.eval.domain.SynthesizeRequest;
import com.nageoffer.ai.ragent.eval.domain.SynthesizeResponse;
import com.nageoffer.ai.ragent.eval.domain.SynthesizedItem;
import com.nageoffer.ai.ragent.eval.service.GoldDatasetSynthesisService;
import com.nageoffer.ai.ragent.eval.service.SynthesisProgressTracker;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort.ChunkSample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class GoldDatasetSynthesisServiceImpl implements GoldDatasetSynthesisService {

    private final GoldDatasetMapper datasetMapper;
    private final GoldItemMapper itemMapper;
    private final KbChunkSamplerPort sampler;
    private final RagasEvalClient client;
    private final SynthesisProgressTracker tracker;
    private final EvalProperties props;
    private final ThreadPoolTaskExecutor evalExecutor;

    /**
     * 单一构造器——Spring 4.3+ 对单公共构造器自动注入，但显式 @Autowired 避免歧义。
     * 测试时传 evalExecutor=null 并走 runSynthesisSync（同步路径），trigger 会抛 IllegalStateException。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public GoldDatasetSynthesisServiceImpl(GoldDatasetMapper datasetMapper,
                                           GoldItemMapper itemMapper,
                                           KbChunkSamplerPort sampler,
                                           RagasEvalClient client,
                                           SynthesisProgressTracker tracker,
                                           EvalProperties props,
                                           @Qualifier("evalExecutor") ThreadPoolTaskExecutor evalExecutor) {
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.sampler = sampler;
        this.client = client;
        this.tracker = tracker;
        this.props = props;
        this.evalExecutor = evalExecutor;
    }

    @Override
    public void trigger(String datasetId, int count, String principalUserId) {
        // 1. DB / tracker 前置校验——tracker 占坑前必须先验，失败时不留副作用
        validatePreconditions(datasetId);
        if (evalExecutor == null) {
            throw new IllegalStateException("evalExecutor not wired — test-only path must call runSynthesisSync");
        }
        // 2. 原子占坑——并发第二个请求会在这里被拒，防 validate race
        if (!tracker.tryBegin(datasetId)) {
            throw new ClientException("synthesis already in progress or completed for datasetId=" + datasetId,
                    BaseErrorCode.CLIENT_ERROR);
        }
        // 3. 提交异步任务——tracker 终态（complete/fail）由任务内更新
        evalExecutor.execute(() -> {
            try {
                runSynthesisSync(datasetId, count, principalUserId);
            } catch (Exception e) {
                log.error("[eval-synthesis] datasetId={} failed", datasetId, e);
                tracker.fail(datasetId, e.getMessage());
            }
        });
    }

    @Override
    public void runSynthesisSync(String datasetId, int count, String principalUserId) {
        GoldDatasetDO ds = validatePreconditions(datasetId);
        int target = count > 0 ? count : props.getSynthesis().getDefaultCount();

        List<ChunkSample> samples = sampler.sampleForSynthesis(ds.getKbId(), target, props.getSynthesis().getMaxPerDoc());
        if (samples.isEmpty()) {
            tracker.fail(datasetId, "no sampleable chunks for kbId=" + ds.getKbId());
            throw new ClientException("no sampleable chunks; check KB has success-status docs with enabled chunks",
                    BaseErrorCode.CLIENT_ERROR);
        }
        tracker.begin(datasetId, samples.size());

        int batchSize = Math.max(1, props.getSynthesis().getBatchSize());
        Map<String, ChunkSample> byId = new HashMap<>();
        for (ChunkSample s : samples) byId.put(s.chunkId(), s);

        int processed = 0;
        int failed = 0;
        for (int i = 0; i < samples.size(); i += batchSize) {
            List<ChunkSample> batch = samples.subList(i, Math.min(i + batchSize, samples.size()));
            List<SynthesizeChunkInput> inputs = batch.stream()
                    .map(s -> new SynthesizeChunkInput(s.chunkId(), s.chunkText(), s.docName()))
                    .toList();

            SynthesizeResponse resp;
            try {
                resp = client.synthesize(new SynthesizeRequest(inputs));
            } catch (Exception e) {
                log.warn("[eval-synthesis] batch HTTP failed datasetId={} batchStart={} size={}",
                        datasetId, i, batch.size(), e);
                failed += batch.size();
                tracker.update(datasetId, processed, failed);
                continue;
            }

            for (SynthesizedItem item : resp.items()) {
                ChunkSample origin = byId.get(item.sourceChunkId());
                if (origin == null) {
                    log.warn("[eval-synthesis] python returned unknown chunk_id={} for datasetId={}",
                            item.sourceChunkId(), datasetId);
                    continue;
                }
                // spec §10 gotcha #6：Java 入库前再做 blank 校验，双重 fail-closed。
                // Python 已有 pydantic 校验，但契约可能漂移；Java 侧不信任 Python 输出。
                if (item.question() == null || item.question().isBlank()
                        || item.answer() == null || item.answer().isBlank()) {
                    log.warn("[eval-synthesis] blank q/a from python chunk_id={} datasetId={}",
                            item.sourceChunkId(), datasetId);
                    failed++;
                    continue;
                }
                GoldItemDO row = GoldItemDO.builder()
                        .datasetId(datasetId)
                        .question(item.question())
                        .groundTruthAnswer(item.answer())
                        .sourceChunkId(origin.chunkId())
                        .sourceChunkText(origin.chunkText())
                        .sourceDocId(origin.docId())
                        .sourceDocName(origin.docName())
                        .reviewStatus("PENDING")
                        .synthesizedByModel(props.getSynthesis().getStrongModel())
                        .createdBy(principalUserId)
                        .updatedBy(principalUserId)
                        .build();
                itemMapper.insert(row);
                processed++;
            }
            if (resp.failedChunkIds() != null) {
                failed += resp.failedChunkIds().size();
            }
            tracker.update(datasetId, processed, failed);
        }

        tracker.complete(datasetId, processed, failed);
        log.info("[eval-synthesis] datasetId={} total={} processed={} failed={}",
                datasetId, samples.size(), processed, failed);
    }

    /**
     * 合成前置：DB 态 + 状态机校验。
     * ⚠️ 不在这里检查 tracker——并发占位由 trigger() 里 {@link SynthesisProgressTracker#tryBegin} 负责。
     * 两个 request 可能都通过 validatePreconditions（DB 视角 existing=0），
     * 但只有第一个能通过 tryBegin，第二个被拒。
     */
    private GoldDatasetDO validatePreconditions(String datasetId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || Objects.equals(ds.getDeleted(), 1)) {
            throw new ClientException("dataset not found: " + datasetId, BaseErrorCode.CLIENT_ERROR);
        }
        if (!"DRAFT".equals(ds.getStatus())) {
            throw new ClientException("can only synthesize into DRAFT dataset, current=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        long existing = itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId));
        if (existing > 0) {
            throw new ClientException("dataset already has " + existing + " items; delete dataset to retry",
                    BaseErrorCode.CLIENT_ERROR);
        }
        return ds;
    }
}
```

- [ ] **Step 5: 跑测试 PASS**

Run: `mvn -pl bootstrap test -Dtest=GoldDatasetSynthesisServiceImplTest`
Expected: 4/4 PASS。

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/GoldDatasetSynthesisService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetSynthesisServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/TriggerSynthesisRequest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetSynthesisServiceImplTest.java
git commit -m "feat(eval): add GoldDatasetSynthesisService with batched python calls"
```

---

## Task 8: `GoldItemReviewService` — 审核三态 + 编辑

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/GoldItemReviewService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/GoldItemReviewServiceImpl.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/GoldItemVO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/ReviewGoldItemRequest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/EditGoldItemRequest.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/GoldItemReviewServiceImplTest.java`

- [ ] **Step 1: 建 VO / Request records**

```java
// controller/vo/GoldItemVO.java
package com.nageoffer.ai.ragent.eval.controller.vo;

public record GoldItemVO(
        String id,
        String datasetId,
        String question,
        String groundTruthAnswer,
        String sourceChunkId,
        String sourceChunkText,
        String sourceDocId,
        String sourceDocName,
        String reviewStatus,
        String reviewNote,
        String synthesizedByModel
) {
}
```

```java
// controller/request/ReviewGoldItemRequest.java
package com.nageoffer.ai.ragent.eval.controller.request;

import lombok.Data;

@Data
public class ReviewGoldItemRequest {
    private String action;   // APPROVE / REJECT
    private String note;
}
```

```java
// controller/request/EditGoldItemRequest.java
package com.nageoffer.ai.ragent.eval.controller.request;

import lombok.Data;

@Data
public class EditGoldItemRequest {
    private String question;
    private String groundTruthAnswer;
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.nageoffer.ai.ragent.eval.service.impl;

import com.nageoffer.ai.ragent.eval.controller.request.EditGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.request.ReviewGoldItemRequest;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldItemReviewServiceImplTest {

    private GoldItemMapper itemMapper;
    private GoldDatasetMapper datasetMapper;
    private GoldItemReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(GoldItemMapper.class);
        datasetMapper = mock(GoldDatasetMapper.class);
        service = new GoldItemReviewServiceImpl(itemMapper, datasetMapper);
    }

    @Test
    void approve_sets_review_status_APPROVED() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").reviewStatus("PENDING").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").build());

        ReviewGoldItemRequest req = new ReviewGoldItemRequest();
        req.setAction("APPROVE");
        req.setNote("ok");
        service.review("i1", req, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).updateById(cap.capture());
        assertThat(cap.getValue().getReviewStatus()).isEqualTo("APPROVED");
        assertThat(cap.getValue().getReviewNote()).isEqualTo("ok");
    }

    @Test
    void reject_sets_review_status_REJECTED() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").reviewStatus("PENDING").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").build());

        ReviewGoldItemRequest req = new ReviewGoldItemRequest();
        req.setAction("REJECT");
        service.review("i1", req, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).updateById(cap.capture());
        assertThat(cap.getValue().getReviewStatus()).isEqualTo("REJECTED");
    }

    @Test
    void review_rejects_when_dataset_ACTIVE() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").reviewStatus("PENDING").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("ACTIVE").build());

        ReviewGoldItemRequest req = new ReviewGoldItemRequest();
        req.setAction("APPROVE");

        assertThatThrownBy(() -> service.review("i1", req, "user-sys"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void edit_updates_question_and_answer() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").build());

        EditGoldItemRequest req = new EditGoldItemRequest();
        req.setQuestion("new Q");
        req.setGroundTruthAnswer("new A");
        service.edit("i1", req, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).updateById(cap.capture());
        assertThat(cap.getValue().getQuestion()).isEqualTo("new Q");
        assertThat(cap.getValue().getGroundTruthAnswer()).isEqualTo("new A");
    }
}
```

Run: `mvn -pl bootstrap test -Dtest=GoldItemReviewServiceImplTest`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 建 Service + Impl**

```java
// service/GoldItemReviewService.java
package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.controller.request.EditGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.request.ReviewGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldItemVO;

import java.util.List;

public interface GoldItemReviewService {
    List<GoldItemVO> list(String datasetId, String reviewStatus);
    void review(String itemId, ReviewGoldItemRequest req, String operatorUserId);
    void edit(String itemId, EditGoldItemRequest req, String operatorUserId);
}
```

```java
// service/impl/GoldItemReviewServiceImpl.java
package com.nageoffer.ai.ragent.eval.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.controller.request.EditGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.request.ReviewGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldItemVO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.service.GoldItemReviewService;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GoldItemReviewServiceImpl implements GoldItemReviewService {

    private final GoldItemMapper itemMapper;
    private final GoldDatasetMapper datasetMapper;

    @Override
    public List<GoldItemVO> list(String datasetId, String reviewStatus) {
        LambdaQueryWrapper<GoldItemDO> q = new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .orderByAsc(GoldItemDO::getCreateTime);
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            q.eq(GoldItemDO::getReviewStatus, reviewStatus);
        }
        return itemMapper.selectList(q).stream().map(this::toVO).toList();
    }

    @Override
    public void review(String itemId, ReviewGoldItemRequest req, String operatorUserId) {
        GoldItemDO item = requiredItem(itemId);
        requireDraftDataset(item.getDatasetId());
        String action = req.getAction();
        String newStatus = switch (action == null ? "" : action.toUpperCase()) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw new ClientException("action must be APPROVE or REJECT", BaseErrorCode.CLIENT_ERROR);
        };
        item.setReviewStatus(newStatus);
        item.setReviewNote(req.getNote());
        item.setUpdatedBy(operatorUserId);
        itemMapper.updateById(item);
    }

    @Override
    public void edit(String itemId, EditGoldItemRequest req, String operatorUserId) {
        GoldItemDO item = requiredItem(itemId);
        requireDraftDataset(item.getDatasetId());
        if (req.getQuestion() != null && !req.getQuestion().isBlank()) {
            item.setQuestion(req.getQuestion());
        }
        if (req.getGroundTruthAnswer() != null && !req.getGroundTruthAnswer().isBlank()) {
            item.setGroundTruthAnswer(req.getGroundTruthAnswer());
        }
        item.setUpdatedBy(operatorUserId);
        itemMapper.updateById(item);
    }

    private GoldItemDO requiredItem(String itemId) {
        GoldItemDO item = itemMapper.selectById(itemId);
        if (item == null || Objects.equals(item.getDeleted(), 1)) {
            throw new ClientException("item not found: " + itemId, BaseErrorCode.CLIENT_ERROR);
        }
        return item;
    }

    private void requireDraftDataset(String datasetId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || !"DRAFT".equals(ds.getStatus())) {
            throw new ClientException("item can only be reviewed while dataset is DRAFT", BaseErrorCode.CLIENT_ERROR);
        }
    }

    private GoldItemVO toVO(GoldItemDO d) {
        return new GoldItemVO(
                d.getId(),
                d.getDatasetId(),
                d.getQuestion(),
                d.getGroundTruthAnswer(),
                d.getSourceChunkId(),
                d.getSourceChunkText(),
                d.getSourceDocId(),
                d.getSourceDocName(),
                d.getReviewStatus(),
                d.getReviewNote(),
                d.getSynthesizedByModel()
        );
    }
}
```

- [ ] **Step 4: 跑测试 PASS**

Run: `mvn -pl bootstrap test -Dtest=GoldItemReviewServiceImplTest`
Expected: 4/4 PASS。

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/GoldItemReviewService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/GoldItemReviewServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/GoldItemVO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/ReviewGoldItemRequest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/EditGoldItemRequest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/GoldItemReviewServiceImplTest.java
git commit -m "feat(eval): add GoldItemReviewService with APPROVE/REJECT/edit flow"
```

---

## Task 9: `GoldDatasetController` + `GoldItemController`（全 SUPER_ADMIN）

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/GoldDatasetController.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/GoldItemController.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/SynthesisProgressVO.java`

- [ ] **Step 1: SynthesisProgressVO**

```java
package com.nageoffer.ai.ragent.eval.controller.vo;

public record SynthesisProgressVO(
        String status,
        int total,
        int processed,
        int failed,
        String error
) {
}
```

- [ ] **Step 2: GoldDatasetController**

```java
package com.nageoffer.ai.ragent.eval.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.nageoffer.ai.ragent.eval.controller.request.TriggerSynthesisRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldDatasetVO;
import com.nageoffer.ai.ragent.eval.controller.vo.SynthesisProgressVO;
import com.nageoffer.ai.ragent.eval.domain.SynthesisProgress;
import com.nageoffer.ai.ragent.eval.service.GoldDatasetService;
import com.nageoffer.ai.ragent.eval.service.GoldDatasetSynthesisService;
import com.nageoffer.ai.ragent.eval.service.SynthesisProgressTracker;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/eval/gold-datasets")
@SaCheckRole("SUPER_ADMIN")
public class GoldDatasetController {

    private final GoldDatasetService datasetService;
    private final GoldDatasetSynthesisService synthesisService;
    private final SynthesisProgressTracker progressTracker;
    private final EvalProperties props;

    @GetMapping
    public Result<List<GoldDatasetVO>> list(@RequestParam(required = false) String kbId,
                                            @RequestParam(required = false) String status) {
        return Results.success(datasetService.list(kbId, status));
    }

    @GetMapping("/{id}")
    public Result<GoldDatasetVO> detail(@PathVariable("id") String id) {
        return Results.success(datasetService.detail(id));
    }

    @PostMapping
    public Result<String> create(@RequestBody CreateGoldDatasetRequest req) {
        return Results.success(datasetService.create(req, StpUtil.getLoginIdAsString()));
    }

    @PostMapping("/{id}/synthesize")
    public Result<Void> triggerSynthesis(@PathVariable("id") String id,
                                         @RequestBody(required = false) TriggerSynthesisRequest req) {
        int count = (req != null && req.getCount() != null && req.getCount() > 0)
                ? req.getCount()
                : props.getSynthesis().getDefaultCount();
        synthesisService.trigger(id, count, StpUtil.getLoginIdAsString());
        return Results.success();
    }

    @GetMapping("/{id}/synthesis-progress")
    public Result<SynthesisProgressVO> progress(@PathVariable("id") String id) {
        SynthesisProgress p = progressTracker.get(id);
        return Results.success(new SynthesisProgressVO(p.status(), p.total(), p.processed(), p.failed(), p.error()));
    }

    @PostMapping("/{id}/activate")
    public Result<Void> activate(@PathVariable("id") String id) {
        datasetService.activate(id);
        return Results.success();
    }

    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable("id") String id) {
        datasetService.archive(id);
        return Results.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        datasetService.delete(id);
        return Results.success();
    }
}
```

- [ ] **Step 3: GoldItemController**

```java
package com.nageoffer.ai.ragent.eval.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.eval.controller.request.EditGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.request.ReviewGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldItemVO;
import com.nageoffer.ai.ragent.eval.service.GoldItemReviewService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/eval")
@SaCheckRole("SUPER_ADMIN")
public class GoldItemController {

    private final GoldItemReviewService reviewService;

    @GetMapping("/gold-datasets/{id}/items")
    public Result<List<GoldItemVO>> list(@PathVariable("id") String datasetId,
                                         @RequestParam(required = false) String reviewStatus) {
        return Results.success(reviewService.list(datasetId, reviewStatus));
    }

    @PostMapping("/gold-items/{id}/review")
    public Result<Void> review(@PathVariable("id") String itemId, @RequestBody ReviewGoldItemRequest req) {
        reviewService.review(itemId, req, StpUtil.getLoginIdAsString());
        return Results.success();
    }

    @PutMapping("/gold-items/{id}")
    public Result<Void> edit(@PathVariable("id") String itemId, @RequestBody EditGoldItemRequest req) {
        reviewService.edit(itemId, req, StpUtil.getLoginIdAsString());
        return Results.success();
    }
}
```

- [ ] **Step 4: 启动冒烟**

启动（确保 ragent-eval 容器未起也不影响 controller 装配）：

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

另一个终端：
```bash
# 未登录 401
curl -i http://localhost:9090/api/ragent/admin/eval/gold-datasets
# 登录（假设 devAdmin 是 SUPER_ADMIN）后再请求应得 200 返回 []
```

Expected: 启动无 `UnsatisfiedDependencyException`；未登录请求 401。

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/GoldDatasetController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/GoldItemController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/SynthesisProgressVO.java
git commit -m "feat(eval): add gold-dataset + gold-item REST controllers (SUPER_ADMIN)"
```

---

## Task 10: 前端 service layer (`evalSuiteService.ts`)

**Files:**
- Create: `frontend/src/services/evalSuiteService.ts`

- [ ] **Step 1: 建 service**

```ts
import { api } from "@/services/api";

export interface GoldDataset {
  id: string;
  kbId: string;
  name: string;
  description?: string | null;
  status: "DRAFT" | "ACTIVE" | "ARCHIVED";
  itemCount: number;           // APPROVED 条数
  totalItemCount: number;      // 全部条数（含 PENDING / REJECTED）
  createTime?: string | null;
  updateTime?: string | null;
}

export interface GoldItem {
  id: string;
  datasetId: string;
  question: string;
  groundTruthAnswer: string;
  sourceChunkId?: string | null;
  sourceChunkText: string;
  sourceDocId?: string | null;
  sourceDocName?: string | null;
  reviewStatus: "PENDING" | "APPROVED" | "REJECTED";
  reviewNote?: string | null;
  synthesizedByModel?: string | null;
}

export interface SynthesisProgress {
  status: "IDLE" | "RUNNING" | "COMPLETED" | "FAILED";
  total: number;
  processed: number;
  failed: number;
  error?: string | null;
}

export async function listGoldDatasets(kbId?: string, status?: string): Promise<GoldDataset[]> {
  return api.get<GoldDataset[], GoldDataset[]>("/admin/eval/gold-datasets", {
    params: { kbId: kbId || undefined, status: status || undefined }
  });
}

export async function getGoldDataset(id: string): Promise<GoldDataset> {
  return api.get<GoldDataset, GoldDataset>(`/admin/eval/gold-datasets/${id}`);
}

export async function createGoldDataset(body: { kbId: string; name: string; description?: string }): Promise<string> {
  return api.post<string, string>("/admin/eval/gold-datasets", body);
}

export async function triggerSynthesis(id: string, count: number): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-datasets/${id}/synthesize`, { count });
}

export async function getSynthesisProgress(id: string): Promise<SynthesisProgress> {
  return api.get<SynthesisProgress, SynthesisProgress>(`/admin/eval/gold-datasets/${id}/synthesis-progress`);
}

export async function activateGoldDataset(id: string): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-datasets/${id}/activate`);
}

export async function archiveGoldDataset(id: string): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-datasets/${id}/archive`);
}

export async function deleteGoldDataset(id: string): Promise<void> {
  await api.delete<void, void>(`/admin/eval/gold-datasets/${id}`);
}

export async function listGoldItems(datasetId: string, reviewStatus?: string): Promise<GoldItem[]> {
  return api.get<GoldItem[], GoldItem[]>(`/admin/eval/gold-datasets/${datasetId}/items`, {
    params: { reviewStatus: reviewStatus || undefined }
  });
}

export async function reviewGoldItem(itemId: string, action: "APPROVE" | "REJECT", note?: string): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-items/${itemId}/review`, { action, note });
}

export async function editGoldItem(itemId: string, body: { question?: string; groundTruthAnswer?: string }): Promise<void> {
  await api.put<void, void>(`/admin/eval/gold-items/${itemId}`, body);
}
```

- [ ] **Step 2: Typecheck**

Run: `cd frontend && ./node_modules/.bin/tsc --noEmit`
Expected: no errors for this file.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/evalSuiteService.ts
git commit -m "feat(eval-ui): add evalSuiteService with dataset/item/progress APIs"
```

---

## Task 11: 前端菜单 + 路由 + 权限注册

**Files:**
- Modify: `frontend/src/utils/permissions.ts`
- Modify: `frontend/src/router.tsx`
- Modify: `frontend/src/pages/admin/AdminLayout.tsx`
- Create (placeholder): `frontend/src/pages/admin/eval-suites/EvalSuitesPage.tsx`
- Create (placeholder): `frontend/src/pages/admin/eval-suites/tabs/GoldSetReviewPage.tsx`

- [ ] **Step 1: 扩展 permissions**

```ts
export type AdminMenuId =
  | "dashboard"
  | "knowledge"
  | "intent-tree"
  | "ingestion"
  | "mappings"
  | "traces"
  | "evaluations"
  | "eval-suites"        // 新增
  | "sample-questions"
  | "access"
  | "settings";
```

（`DEPT_VISIBLE` 不改——eval-suites 是 SUPER_ADMIN only，DEPT_ADMIN 看不见。`canSeeMenuItem` 的 `isSuperAdmin || ...` 分支已自动覆盖。）

- [ ] **Step 2: 建最小 `EvalSuitesPage` 容器（三 Tab 占位）**

```tsx
import { useSearchParams } from "react-router-dom";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { GoldSetListTab } from "./tabs/GoldSetListTab";
import { RunsPlaceholderTab } from "./tabs/placeholders/RunsPlaceholderTab";
import { TrendsPlaceholderTab } from "./tabs/placeholders/TrendsPlaceholderTab";

const VALID_TABS = ["gold-sets", "runs", "trends"] as const;
type TabId = (typeof VALID_TABS)[number];

export function EvalSuitesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get("tab") as TabId) ?? "gold-sets";
  const activeTab: TabId = VALID_TABS.includes(tab) ? tab : "gold-sets";

  const setTab = (t: TabId) => {
    const next = new URLSearchParams(searchParams);
    next.set("tab", t);
    setSearchParams(next, { replace: true });
  };

  return (
    <div className="space-y-4 p-6">
      <div>
        <h1 className="text-2xl font-semibold">质量评估</h1>
        <p className="mt-1 text-sm text-slate-500">
          Gold Set 合成 / 审核 / 评估运行 / 趋势对比——RAG 调优闭环入口。
        </p>
      </div>
      <Tabs value={activeTab} onValueChange={(v) => setTab(v as TabId)}>
        <TabsList>
          <TabsTrigger value="gold-sets">黄金集</TabsTrigger>
          <TabsTrigger value="runs">评估运行</TabsTrigger>
          <TabsTrigger value="trends">趋势对比</TabsTrigger>
        </TabsList>
        <TabsContent value="gold-sets">
          <GoldSetListTab />
        </TabsContent>
        <TabsContent value="runs">
          <RunsPlaceholderTab />
        </TabsContent>
        <TabsContent value="trends">
          <TrendsPlaceholderTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
```

- [ ] **Step 3: 两个占位 Tab**

```tsx
// tabs/placeholders/RunsPlaceholderTab.tsx
export function RunsPlaceholderTab() {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-300 p-16 text-slate-500">
      <p className="text-base font-medium">评估运行</p>
      <p className="mt-2 text-sm">即将在 PR E3 上线——届时可一键触发 RAGAS 四指标评估并查看结果看板。</p>
    </div>
  );
}
```

```tsx
// tabs/placeholders/TrendsPlaceholderTab.tsx
export function TrendsPlaceholderTab() {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-300 p-16 text-slate-500">
      <p className="text-base font-medium">趋势对比</p>
      <p className="mt-2 text-sm">即将在 PR E3 上线——同 dataset 多次评估指标折线 + 系统 snapshot diff。</p>
    </div>
  );
}
```

- [ ] **Step 4: GoldSetReviewPage 占位（Task 13 填实）**

```tsx
// tabs/GoldSetReviewPage.tsx —— Task 13 填实
export function GoldSetReviewPage() {
  return <div className="p-6 text-slate-500">审核页即将实现。</div>;
}
```

- [ ] **Step 5: `GoldSetListTab` 骨架（Task 12 填实；此处先建空壳避免 import 报错）**

```tsx
// tabs/GoldSetListTab.tsx
export function GoldSetListTab() {
  return <div className="py-8 text-sm text-slate-500">列表加载中…</div>;
}
```

- [ ] **Step 6: router 加两条路由**

修改 `frontend/src/router.tsx`，在 `evaluations` 路由之后、`access` 之前插入：

```tsx
import { EvalSuitesPage } from "@/pages/admin/eval-suites/EvalSuitesPage";
import { GoldSetReviewPage } from "@/pages/admin/eval-suites/tabs/GoldSetReviewPage";

// ...在 admin children 数组：
{
  path: "eval-suites",
  element: <RequireSuperAdmin><EvalSuitesPage /></RequireSuperAdmin>
},
{
  path: "eval-suites/datasets/:datasetId/review",
  element: <RequireSuperAdmin><GoldSetReviewPage /></RequireSuperAdmin>
},
```

- [ ] **Step 7: AdminLayout 菜单加"质量评估"**

在 `menuGroups[0].items` 的 `evaluations` 条目**之后**追加：

```tsx
{
  menuId: "eval-suites",
  path: "/admin/eval-suites",
  label: "质量评估",
  icon: FlaskConical   // from lucide-react
},
```

同时在 `import { ... } from "lucide-react"` 里加 `FlaskConical`。

`breadcrumbMap` 里追加 `"eval-suites": "质量评估"`。

- [ ] **Step 8: typecheck + 手工验证**

```bash
cd frontend
./node_modules/.bin/tsc --noEmit
npm run dev
```

登录 SUPER_ADMIN 账号，侧栏看到"质量评估"条目，点进去看到三 Tab，URL 切换 `?tab=runs` / `?tab=trends` 正确。

- [ ] **Step 9: Commit**

```bash
git add frontend/src/utils/permissions.ts \
        frontend/src/router.tsx \
        frontend/src/pages/admin/AdminLayout.tsx \
        frontend/src/pages/admin/eval-suites/
git commit -m "feat(eval-ui): scaffold /admin/eval-suites page with 3 tabs (gold-sets implemented, runs/trends placeholder)"
```

---

## Task 12: 前端 `GoldSetListTab` + 创建 / 触发 / 进度 / 激活 / 删除

**Files:**
- Modify: `frontend/src/pages/admin/eval-suites/tabs/GoldSetListTab.tsx` (先前建的空壳 → 填实)
- Create: `frontend/src/pages/admin/eval-suites/components/CreateGoldSetDialog.tsx`
- Create: `frontend/src/pages/admin/eval-suites/components/TriggerSynthesisDialog.tsx`
- Create: `frontend/src/pages/admin/eval-suites/components/SynthesisProgressDialog.tsx`

- [ ] **Step 1: CreateGoldSetDialog**

```tsx
import { useEffect, useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import { createGoldDataset } from "@/services/evalSuiteService";
import { toast } from "sonner";

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: (datasetId: string) => void;
  defaultKbId?: string;
}

export function CreateGoldSetDialog({ open, onClose, onCreated, defaultKbId }: Props) {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string>(defaultKbId ?? "");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    getKnowledgeBases(1, 100, "").then(setKbs).catch(() => setKbs([]));
  }, [open]);

  const submit = async () => {
    if (!kbId || !name.trim()) {
      toast.error("请选择 KB 并填写名称");
      return;
    }
    try {
      setSubmitting(true);
      const id = await createGoldDataset({ kbId, name: name.trim(), description });
      toast.success("数据集已创建");
      onCreated(id);
      onClose();
      setName("");
      setDescription("");
    } catch (e) {
      toast.error((e as Error).message || "创建失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>创建黄金集</DialogTitle>
          <DialogDescription>空数据集创建后状态为 DRAFT；下一步触发合成。</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-2">
            <label className="text-sm font-medium">知识库</label>
            <Select value={kbId} onValueChange={setKbId}>
              <SelectTrigger><SelectValue placeholder="选择 KB" /></SelectTrigger>
              <SelectContent>
                {kbs.map((kb) => <SelectItem key={kb.id} value={kb.id}>{kb.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">名称</label>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：smoke-50" />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">描述（可选）</label>
            <Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button onClick={submit} disabled={submitting}>{submitting ? "创建中…" : "创建"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 2: TriggerSynthesisDialog**

```tsx
import { useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { triggerSynthesis } from "@/services/evalSuiteService";
import { toast } from "sonner";

interface Props {
  open: boolean;
  onClose: () => void;
  datasetId: string | null;
  onStarted: (datasetId: string) => void;
}

export function TriggerSynthesisDialog({ open, onClose, datasetId, onStarted }: Props) {
  const [count, setCount] = useState<number>(50);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!datasetId) return;
    if (!Number.isFinite(count) || count <= 0 || count > 500) {
      toast.error("合成条数需在 1-500 之间");
      return;
    }
    try {
      setSubmitting(true);
      await triggerSynthesis(datasetId, count);
      toast.success("合成任务已提交");
      onStarted(datasetId);
      onClose();
    } catch (e) {
      toast.error((e as Error).message || "触发合成失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>触发合成</DialogTitle>
          <DialogDescription>
            后端将随机采样 KB 中成功入库的 chunk，调百炼 qwen-max 合成 Q-A 对。合成期间不得再次触发。
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <label className="text-sm font-medium">合成条数</label>
          <Input
            type="number"
            min={1}
            max={500}
            value={count}
            onChange={(e) => setCount(parseInt(e.target.value, 10))}
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button onClick={submit} disabled={submitting}>{submitting ? "提交中…" : "开始合成"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 3: SynthesisProgressDialog（2 秒轮询）**

```tsx
import { useEffect, useRef, useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { getSynthesisProgress, type SynthesisProgress } from "@/services/evalSuiteService";
import { useNavigate } from "react-router-dom";

interface Props {
  open: boolean;
  onClose: () => void;
  datasetId: string | null;
  onFinished?: () => void;
}

export function SynthesisProgressDialog({ open, onClose, datasetId, onFinished }: Props) {
  const [progress, setProgress] = useState<SynthesisProgress | null>(null);
  const timerRef = useRef<number | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!open || !datasetId) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const p = await getSynthesisProgress(datasetId);
        if (cancelled) return;
        setProgress(p);
        if (p.status === "COMPLETED" || p.status === "FAILED") {
          if (timerRef.current) window.clearTimeout(timerRef.current);
          if (p.status === "COMPLETED") onFinished?.();
          return;
        }
      } catch {
        // 轮询失败静默
      }
      timerRef.current = window.setTimeout(tick, 2000);
    };
    tick();
    return () => {
      cancelled = true;
      if (timerRef.current) window.clearTimeout(timerRef.current);
    };
  }, [open, datasetId, onFinished]);

  const pct = progress && progress.total > 0 ? Math.round(((progress.processed + progress.failed) / progress.total) * 100) : 0;

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>合成进度</DialogTitle>
          <DialogDescription>
            {progress?.status === "RUNNING" && "合成进行中，可关闭此窗口稍后回来查看"}
            {progress?.status === "COMPLETED" && "合成完成，可进入审核页"}
            {progress?.status === "FAILED" && "合成失败"}
            {(!progress || progress.status === "IDLE") && "等待进度……"}
          </DialogDescription>
        </DialogHeader>
        {progress && progress.total > 0 ? (
          <div className="space-y-2">
            {/* 自绘进度条——项目暂无 shadcn Progress 组件，避免引入新 UI 依赖 */}
            <div className="h-2 w-full overflow-hidden rounded bg-slate-200">
              <div className="h-full bg-indigo-500 transition-all" style={{ width: `${pct}%` }} />
            </div>
            <p className="text-sm text-slate-500">
              已处理 {progress.processed} / {progress.total}（失败 {progress.failed}）
            </p>
          </div>
        ) : (
          <p className="text-sm text-slate-500">启动中……</p>
        )}
        {progress?.error && <p className="text-sm text-rose-600">{progress.error}</p>}
        <DialogFooter>
          {progress?.status === "COMPLETED" && datasetId && (
            <Button onClick={() => {
              onClose();
              navigate(`/admin/eval-suites/datasets/${datasetId}/review`);
            }}>去审核</Button>
          )}
          <Button variant="outline" onClick={onClose}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

> **已知：** 项目现无 `@/components/ui/progress`。本组件采用自绘 `<div>`（上面代码已含），无需新增 shadcn 依赖。

- [ ] **Step 4: 填实 `GoldSetListTab`**

```tsx
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Plus, Play, CheckCircle2, Archive, Trash2, FileSearch } from "lucide-react";
import {
  listGoldDatasets,
  activateGoldDataset,
  archiveGoldDataset,
  deleteGoldDataset,
  type GoldDataset
} from "@/services/evalSuiteService";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import { CreateGoldSetDialog } from "../components/CreateGoldSetDialog";
import { TriggerSynthesisDialog } from "../components/TriggerSynthesisDialog";
import { SynthesisProgressDialog } from "../components/SynthesisProgressDialog";
import { toast } from "sonner";
import { formatDateTime } from "@/utils/helpers";

export function GoldSetListTab() {
  // Radix Select 不允许 value=""——用 "__all__" sentinel，API 调用前翻译成 undefined
  const ALL = "__all__";
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [kbFilter, setKbFilter] = useState<string>(ALL);
  const [statusFilter, setStatusFilter] = useState<string>(ALL);
  const [datasets, setDatasets] = useState<GoldDataset[]>([]);
  const [loading, setLoading] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [progressOpen, setProgressOpen] = useState(false);
  const [activeDatasetId, setActiveDatasetId] = useState<string | null>(null);

  useEffect(() => {
    getKnowledgeBases(1, 100, "").then(setKbs).catch(() => setKbs([]));
  }, []);

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await listGoldDatasets(
        kbFilter === ALL ? undefined : kbFilter,
        statusFilter === ALL ? undefined : statusFilter
      );
      setDatasets(data);
    } catch (e) {
      toast.error((e as Error).message || "加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [kbFilter, statusFilter]);

  const statusBadge = (status: string) => {
    const map: Record<string, { variant: any; label: string }> = {
      DRAFT: { variant: "secondary", label: "草稿" },
      ACTIVE: { variant: "default", label: "可用" },
      ARCHIVED: { variant: "outline", label: "归档" }
    };
    const it = map[status] ?? { variant: "secondary", label: status };
    return <Badge variant={it.variant}>{it.label}</Badge>;
  };

  const onTriggerSynth = (id: string) => {
    setActiveDatasetId(id);
    setTriggerOpen(true);
  };

  const onActivate = async (id: string) => {
    try {
      await activateGoldDataset(id);
      toast.success("已激活");
      refresh();
    } catch (e) {
      toast.error((e as Error).message || "激活失败");
    }
  };

  const onArchive = async (id: string) => {
    try {
      await archiveGoldDataset(id);
      toast.success("已归档");
      refresh();
    } catch (e) {
      toast.error((e as Error).message || "归档失败");
    }
  };

  const onDelete = async (id: string) => {
    if (!confirm("确认删除数据集？ACTIVE 态需先归档。")) return;
    try {
      await deleteGoldDataset(id);
      toast.success("已删除");
      refresh();
    } catch (e) {
      toast.error((e as Error).message || "删除失败");
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Select value={kbFilter} onValueChange={setKbFilter}>
          <SelectTrigger className="w-[240px]"><SelectValue placeholder="全部 KB" /></SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>全部 KB</SelectItem>
            {kbs.map((k) => <SelectItem key={k.id} value={k.id}>{k.name}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="w-[160px]"><SelectValue placeholder="全部状态" /></SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>全部状态</SelectItem>
            <SelectItem value="DRAFT">草稿</SelectItem>
            <SelectItem value="ACTIVE">可用</SelectItem>
            <SelectItem value="ARCHIVED">归档</SelectItem>
          </SelectContent>
        </Select>
        <div className="flex-1" />
        <Button onClick={() => setCreateOpen(true)}><Plus className="mr-1 h-4 w-4" />新建数据集</Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>名称</TableHead>
            <TableHead>KB</TableHead>
            <TableHead>状态</TableHead>
            <TableHead>已审批 / 总数</TableHead>
            <TableHead>创建时间</TableHead>
            <TableHead className="text-right">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {datasets.map((d) => (
            <TableRow key={d.id}>
              <TableCell className="font-medium">{d.name}</TableCell>
              <TableCell>{kbs.find((k) => k.id === d.kbId)?.name ?? d.kbId}</TableCell>
              <TableCell>{statusBadge(d.status)}</TableCell>
              <TableCell>{d.itemCount} / {d.totalItemCount}</TableCell>
              <TableCell>{d.createTime ? formatDateTime(d.createTime) : "-"}</TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  {d.status === "DRAFT" && d.totalItemCount === 0 && (
                    <Button size="sm" variant="outline" onClick={() => onTriggerSynth(d.id)}>
                      <Play className="mr-1 h-3 w-3" />合成
                    </Button>
                  )}
                  {d.status === "DRAFT" && d.totalItemCount > 0 && (
                    <>
                      <Button size="sm" variant="outline" asChild>
                        <Link to={`/admin/eval-suites/datasets/${d.id}/review`}>
                          <FileSearch className="mr-1 h-3 w-3" />审核
                          {d.pendingItemCount > 0 && <span className="ml-1 text-xs text-amber-600">({d.pendingItemCount})</span>}
                        </Link>
                      </Button>
                      {d.itemCount > 0 && d.pendingItemCount === 0 && (
                        <Button size="sm" onClick={() => onActivate(d.id)}>
                          <CheckCircle2 className="mr-1 h-3 w-3" />激活
                        </Button>
                      )}
                    </>
                  )}
                  {d.status === "ACTIVE" && (
                    <Button size="sm" variant="outline" onClick={() => onArchive(d.id)}>
                      <Archive className="mr-1 h-3 w-3" />归档
                    </Button>
                  )}
                  {d.status !== "ACTIVE" && (
                    <Button size="sm" variant="ghost" onClick={() => onDelete(d.id)}>
                      <Trash2 className="h-3 w-3 text-rose-500" />
                    </Button>
                  )}
                </div>
              </TableCell>
            </TableRow>
          ))}
          {datasets.length === 0 && !loading && (
            <TableRow>
              <TableCell colSpan={6} className="py-8 text-center text-sm text-slate-500">
                暂无数据集，点"新建数据集"开始。
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <CreateGoldSetDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => refresh()}
        defaultKbId={kbFilter === ALL ? undefined : kbFilter}
      />
      <TriggerSynthesisDialog
        open={triggerOpen}
        onClose={() => setTriggerOpen(false)}
        datasetId={activeDatasetId}
        onStarted={(id) => {
          setActiveDatasetId(id);
          setProgressOpen(true);
        }}
      />
      <SynthesisProgressDialog
        open={progressOpen}
        onClose={() => { setProgressOpen(false); refresh(); }}
        datasetId={activeDatasetId}
        onFinished={() => refresh()}
      />
    </div>
  );
}
```

- [ ] **Step 5: Typecheck + 手工验证（需起 ragent-eval 容器）**

```bash
cd frontend
./node_modules/.bin/tsc --noEmit
npm run dev
```

```bash
# 另起终端：启动 ragent-eval（首次 build 较慢）
docker compose -f resources/docker/ragent-eval.compose.yaml up -d
curl http://localhost:9091/health
```

浏览器登录 SUPER_ADMIN → `/admin/eval-suites` → "新建数据集"（选一个有成功入库文档的 KB，名字填 smoke-e2）→ 触发合成（条数=5）→ 进度对话框每 2s 轮询，完成后点"去审核"跳转到 `/datasets/:id/review`（下 Task 实现）。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/admin/eval-suites/tabs/GoldSetListTab.tsx \
        frontend/src/pages/admin/eval-suites/components/
git commit -m "feat(eval-ui): implement gold-set list with create/synthesize/activate/delete flow"
```

---

## Task 13: 前端 `GoldSetReviewPage` — 左右分屏 + y/n/e 快捷键

**Files:**
- Modify: `frontend/src/pages/admin/eval-suites/tabs/GoldSetReviewPage.tsx`

- [ ] **Step 1: 完整填实**

```tsx
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Textarea } from "@/components/ui/textarea";
import { toast } from "sonner";
import { Check, X, Pencil, CheckCircle2, ArrowLeft } from "lucide-react";
import {
  listGoldItems,
  reviewGoldItem,
  editGoldItem,
  activateGoldDataset,
  getGoldDataset,
  type GoldItem,
  type GoldDataset
} from "@/services/evalSuiteService";

export function GoldSetReviewPage() {
  const { datasetId } = useParams<{ datasetId: string }>();
  const navigate = useNavigate();

  const [dataset, setDataset] = useState<GoldDataset | null>(null);
  const [items, setItems] = useState<GoldItem[]>([]);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [editing, setEditing] = useState(false);
  const [editedQuestion, setEditedQuestion] = useState("");
  const [editedAnswer, setEditedAnswer] = useState("");
  const [note, setNote] = useState("");

  const refresh = useCallback(async () => {
    if (!datasetId) return;
    const [ds, list] = await Promise.all([getGoldDataset(datasetId), listGoldItems(datasetId)]);
    setDataset(ds);
    setItems(list);
  }, [datasetId]);

  useEffect(() => { refresh(); }, [refresh]);

  const current = items[currentIdx];
  const pendingCount = items.filter((i) => i.reviewStatus === "PENDING").length;
  const approvedCount = items.filter((i) => i.reviewStatus === "APPROVED").length;
  const canActivate = dataset?.status === "DRAFT" && approvedCount > 0 && pendingCount === 0;

  const gotoNext = () => {
    const nextPending = items.findIndex((it, idx) => idx > currentIdx && it.reviewStatus === "PENDING");
    if (nextPending >= 0) setCurrentIdx(nextPending);
    else {
      // 全部审完
      setCurrentIdx(Math.min(currentIdx + 1, items.length - 1));
    }
  };

  const approve = useCallback(async () => {
    if (!current) return;
    try {
      await reviewGoldItem(current.id, "APPROVE", note || undefined);
      toast.success("已接受");
      await refresh();
      setNote("");
      gotoNext();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }, [current, note, refresh]);

  const reject = useCallback(async () => {
    if (!current) return;
    try {
      await reviewGoldItem(current.id, "REJECT", note || undefined);
      toast.success("已拒绝");
      await refresh();
      setNote("");
      gotoNext();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }, [current, note, refresh]);

  const startEdit = () => {
    if (!current) return;
    setEditedQuestion(current.question);
    setEditedAnswer(current.groundTruthAnswer);
    setEditing(true);
  };

  const saveEdit = async () => {
    if (!current) return;
    try {
      await editGoldItem(current.id, { question: editedQuestion, groundTruthAnswer: editedAnswer });
      setEditing(false);
      toast.success("已保存");
      await refresh();
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  const activate = async () => {
    if (!datasetId) return;
    try {
      await activateGoldDataset(datasetId);
      toast.success("数据集已激活");
      navigate("/admin/eval-suites");
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  // 快捷键 y/n/e（编辑中暂不响应）
  useEffect(() => {
    if (editing) return;
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName?.toLowerCase();
      if (tag === "input" || tag === "textarea") return;
      if (e.key === "y") approve();
      else if (e.key === "n") reject();
      else if (e.key === "e") startEdit();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [editing, approve, reject]);

  if (!dataset || items.length === 0) {
    return <div className="p-6 text-sm text-slate-500">加载中或无数据……</div>;
  }

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-3">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="sm" onClick={() => navigate("/admin/eval-suites")}>
            <ArrowLeft className="mr-1 h-4 w-4" />返回
          </Button>
          <div>
            <div className="font-semibold">{dataset.name} · 审核</div>
            <div className="text-xs text-slate-500">
              {currentIdx + 1} / {items.length} · 待审 {pendingCount} · 已通过 {approvedCount}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">快捷键：y 通过 · n 拒绝 · e 编辑</span>
          <Button disabled={!canActivate} onClick={activate}>
            <CheckCircle2 className="mr-1 h-4 w-4" />激活数据集
          </Button>
        </div>
      </header>

      <div className="grid flex-1 grid-cols-2 gap-4 overflow-auto p-6">
        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <h3 className="mb-2 text-sm font-semibold text-slate-600">原文 chunk（快照）</h3>
          <p className="text-xs text-slate-400">
            文档：{current.sourceDocName ?? current.sourceDocId ?? "（已失效）"} · chunk_id {current.sourceChunkId ?? "（已失效）"}
          </p>
          <pre className="mt-3 whitespace-pre-wrap break-words rounded bg-slate-50 p-3 text-sm leading-relaxed">
            {current.sourceChunkText}
          </pre>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <div className="mb-2 flex items-center justify-between">
            <h3 className="text-sm font-semibold text-slate-600">合成问答</h3>
            <Badge variant={
              current.reviewStatus === "APPROVED" ? "default" :
                current.reviewStatus === "REJECTED" ? "destructive" : "secondary"
            }>
              {current.reviewStatus}
            </Badge>
          </div>
          {editing ? (
            <div className="space-y-3">
              <div>
                <label className="text-xs font-medium text-slate-500">Question</label>
                <Textarea value={editedQuestion} onChange={(e) => setEditedQuestion(e.target.value)} rows={3} />
              </div>
              <div>
                <label className="text-xs font-medium text-slate-500">Ground truth answer</label>
                <Textarea value={editedAnswer} onChange={(e) => setEditedAnswer(e.target.value)} rows={6} />
              </div>
              <div className="flex justify-end gap-2">
                <Button variant="outline" onClick={() => setEditing(false)}>取消</Button>
                <Button onClick={saveEdit}>保存</Button>
              </div>
            </div>
          ) : (
            <div className="space-y-3">
              <div>
                <div className="text-xs font-medium text-slate-500">Question</div>
                <p className="mt-1 text-sm">{current.question}</p>
              </div>
              <div>
                <div className="text-xs font-medium text-slate-500">Ground truth answer</div>
                <p className="mt-1 whitespace-pre-wrap text-sm">{current.groundTruthAnswer}</p>
              </div>
              <div>
                <label className="text-xs font-medium text-slate-500">备注（可选）</label>
                <Textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} placeholder="留给自己的审核理由" />
              </div>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" onClick={approve}><Check className="mr-1 h-3 w-3" />接受 (y)</Button>
                <Button size="sm" variant="destructive" onClick={reject}><X className="mr-1 h-3 w-3" />拒绝 (n)</Button>
                <Button size="sm" variant="outline" onClick={startEdit}><Pencil className="mr-1 h-3 w-3" />编辑 (e)</Button>
              </div>
            </div>
          )}

          <div className="mt-4 flex justify-between text-xs text-slate-500">
            <button disabled={currentIdx === 0} onClick={() => setCurrentIdx((i) => i - 1)}>← 上一条</button>
            <button disabled={currentIdx >= items.length - 1} onClick={() => setCurrentIdx((i) => i + 1)}>下一条 →</button>
          </div>
        </section>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Typecheck**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit
```

- [ ] **Step 3: 手工 E2E smoke**

1. 登录 SUPER_ADMIN
2. `/admin/eval-suites` 创建 `smoke-e2` dataset（选有成功文档的 KB）
3. 触发合成 count=5
4. 进度走完 → 去审核
5. y/n/e 三个快捷键各测一次，每条审完自动跳下一条
6. 全部审过 ≥1 条后，顶部"激活数据集"按钮启用
7. 激活 → 回列表页，该 dataset status=ACTIVE
8. 列表页点归档 → ARCHIVED；点删除 → 消失

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/admin/eval-suites/tabs/GoldSetReviewPage.tsx
git commit -m "feat(eval-ui): gold-set review page with y/n/e keyboard shortcuts + activate"
```

---

## Task 14: 文档更新（CLAUDE.md / backlog / dev_log）

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md`
- Modify: `bootstrap/CLAUDE.md`
- Modify: `docs/dev/followup/backlog.md`
- Create: `log/dev_log/2026-04-25-eval-pr-e2-synthesis.md`

- [ ] **Step 1: `eval/CLAUDE.md` 补 PR E2 产物**

在职责段下方追加：

```markdown
## PR E2 已落地（2026-04-25）

- Gold Dataset 三态机：`GoldDatasetService`（DRAFT → ACTIVE → ARCHIVED）；ACTIVE 下禁止增删 item（`GoldItemReviewService` 显式校验 DRAFT 才可改）
- 合成闭环：`GoldDatasetSynthesisService.trigger(...)` 异步提交到 `evalExecutor`；`SynthesisProgressTracker` 进程内 `ConcurrentHashMap` 存进度；默认 batchSize=5 分批调 Python `/synthesize`
- 跨域 port：`KbChunkSamplerPort`（framework）+ `KbChunkSamplerImpl`（knowledge 域）替代直读 `t_knowledge_chunk`；SQL 固化 spec §6.1 过滤条件（`enabled=1`, `status='success'`, per-doc cap in Java）
- 所有 controller `@SaCheckRole("SUPER_ADMIN")`（EVAL-3 落地前不降级）
- `source_chunk_text` 字节级冻结：Java 侧 sampled 结果直接入库，Python 不返回该字段
```

- [ ] **Step 2: `bootstrap/CLAUDE.md` 补 eval 域类**

在 eval 域段落里（在 "独立顶级域，详见 eval/CLAUDE.md" 之后）加一张最小关键类表：

```markdown
| 类 | 职责 |
|----|------|
| `KbChunkSamplerPort`（framework）+ `KbChunkSamplerImpl`（knowledge）| 跨域 chunk 采样 port；SQL 固化 spec §6.1，Java 侧 per-doc dedup |
| `GoldDatasetService` | 数据集 CRUD + DRAFT/ACTIVE/ARCHIVED 状态机 |
| `GoldDatasetSynthesisService` | 异步合成编排：采样 → 分批调 Python → 落 gold_item（chunk_text 快照冻结） |
| `GoldItemReviewService` | PENDING → APPROVED/REJECTED；DRAFT 态下才可改 |
| `SynthesisProgressTracker` | 进程内 `ConcurrentHashMap<datasetId, SynthesisProgress>`，重启丢失 |
| `RagasEvalClient` | Python HTTP 客户端；`synthesize` 走 `synthesisTimeoutMs`（默认 600s），`health` 走 `pythonService.timeoutMs`（120s） |
```

- [ ] **Step 3: `docs/dev/followup/backlog.md` 更新 EVAL-2/3 状态 + 新条目 EVAL-4**

追加（或更新）：

```markdown
### EVAL-3：eval 读接口 `security_level` redaction —— 开放 AnyAdmin 前置
**状态**: open（PR E2 未消；PR E2 所有读接口仍 `@SaCheckRole("SUPER_ADMIN")`）
**触发**: 未来放开 eval 读接口到 DEPT_ADMIN 前必须先落 redaction
**方案候选**: ①查询侧对 `retrieved_chunks` 按 caller `security_level` 过滤；②评估本身跑在 caller scope 内放弃系统级

### EVAL-4：合成任务可恢复性（PR E2 引入）
**状态**: open，优先级低
**现状**: `SynthesisProgressTracker` 进程内内存；重启后 RUNNING 状态丢失，数据库中可能有部分 gold_item。UI 检测 `totalItemCount > 0` 会隐藏"合成"按钮；用户只能 delete dataset 重来
**改进选项**: ①加 `t_eval_gold_dataset.sync_status` 列（migration + dataset-level 状态持久化）；②启动时扫 stale RUNNING 并标记 FAILED；③允许增量合成（需 per-chunk dedup）
```

- [ ] **Step 4: 写 session log `log/dev_log/2026-04-25-eval-pr-e2-synthesis.md`**

```markdown
# 2026-04-25 | PR E2 — RAG 评估闭环合成闭环

分支：`feature/eval-e2-synthesis`（基于 `main@96b9f3a`）

## 背景 / 目标
PR E1 只有地基（4 表 + Python 骨架 + Java 域壳）。本 PR 落合成：从 KB 抽 chunk → 百炼 qwen-max 合成 Q-A → 落 `t_eval_gold_item`（含 `source_chunk_text` 快照）+ 前端 `/admin/eval-suites` 人审 + dataset 激活。覆盖 spec §6.2 + §7.2 剩余 service + §11 前端 Tab 1。

## 核心决策复述（与 spec 一致）
- 跨域 port：`KbChunkSamplerPort`（`framework.security.port`）+ `KbChunkSamplerImpl`（`knowledge`）替代直读 `t_knowledge_chunk`
- 合成分批：Java 按 `rag.eval.synthesis.batch-size=5` 拆 Python 调用，独立 timeout `synthesisTimeoutMs=600s`
- 进度：进程内 `SynthesisProgressTracker`（非持久），前端 2s 轮询
- 权限：eval 所有 controller `@SaCheckRole("SUPER_ADMIN")`（EVAL-3 未落地）
- 状态机：Dataset DRAFT → ACTIVE → ARCHIVED；Item PENDING → APPROVED/REJECTED（仅 DRAFT 态可改）

## 本次改动文件
<run `git diff --stat main` 后贴上>

## 验证
- Java 单测：`KbChunkSamplerImplTest / GoldDatasetServiceImplTest / GoldDatasetSynthesisServiceImplTest / GoldItemReviewServiceImplTest / EvalPropertiesTest / EvalAsyncConfigTest / EvalMapperScanTest` 全绿
- 启动冒烟：`mvn -pl bootstrap spring-boot:run` 无 `UnsatisfiedDependencyException`
- E2E smoke：起 `ragent-eval` 容器 → 前端创建 dataset → 触发合成 5 条 → 审核 → 激活

## 过程坑 / 决策点
<按实施过程追加>

## 回滚
本 PR 新代码全在 `eval/` 域 + `knowledge/KbChunkSamplerImpl` + 前端 `eval-suites/`。回滚 revert 整批即可；`t_eval_*` 表结构无变化（v1.10 schema 保留，PR E1 已落）。

## 后续
- PR E3-spike：验证从 `streamChat` 抽 `AnswerPipeline` 工程量
- PR E3：`ChatForEvalService` + `EvalRunExecutor` + 结果看板 + 趋势
- EVAL-3：eval 读接口 redaction（v1/dev 前置条件）
- EVAL-4：合成任务可恢复性
```

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md \
        bootstrap/CLAUDE.md \
        docs/dev/followup/backlog.md \
        log/dev_log/2026-04-25-eval-pr-e2-synthesis.md
git commit -m "docs(eval): update CLAUDE.md + backlog + session log for PR E2"
```

---

## Task 15: 全量回归 + E2E smoke + 开 PR

**Files:** none（纯验收 + git + PR）

- [ ] **Step 1: Java 全量回归**

```bash
mvn -pl bootstrap test
```

Expected:
- 所有 `Eval*Test / KbChunkSampler*Test / GoldDataset*Test / GoldItem*Test` 全绿
- **已知 baseline 失败**（与 PR E2 无关，`CLAUDE.md` 明确记录可忽略）：
  `MilvusCollectionTests / InvoiceIndexDocumentTests / PgVectorStoreServiceTest#testChineseCharacterInsertion / IntentTreeServiceTests#initFromFactory / VectorTreeIntentClassifierTests`（10 errors）—— 不得增加新失败

- [ ] **Step 2: Python 回归（确保 PR E1 套件未回归）**

```bash
cd ragas && python -m pytest tests/
```

Expected: 7 passed（与 PR E1 一致）。

- [ ] **Step 3: 前端 typecheck + build**

```bash
cd frontend
./node_modules/.bin/tsc --noEmit
npm run build
```

Expected: BUILD SUCCESS。（`npm run lint` 有 pre-existing break，跳过。）

- [ ] **Step 4: 完整 E2E smoke（走真 Python）**

```bash
# 确保后端 + docker 服务起
docker compose -f resources/docker/ragent-eval.compose.yaml up -d
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
# 另起
cd frontend && npm run dev
```

浏览器操作：
1. SUPER_ADMIN 登录 → `/admin/eval-suites`
2. 创建 dataset `smoke-e2`（KB = 任一有 ≥3 success 文档的 KB），描述留空
3. 触发合成，count=5
4. 进度对话框 2s 轮询，COMPLETED 后点"去审核"
5. 审核页：y 接受 ≥1 条，n 拒绝 ≥1 条，e 编辑并保存 ≥1 条
6. 回列表，确认 `itemCount ≥ 1` 后点"激活"→ 回 `/admin/eval-suites`，状态 ACTIVE
7. 点归档 → ARCHIVED
8. 再点删除 → 列表消失

数据库核查：
```bash
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT id, status, item_count FROM t_eval_gold_dataset ORDER BY create_time DESC LIMIT 3;"
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT id, dataset_id, review_status, LEFT(source_chunk_text, 30) AS text_head FROM t_eval_gold_item ORDER BY create_time DESC LIMIT 10;"
```

`source_chunk_text` 列应非空、与原 KB chunk 内容字节级一致。

- [ ] **Step 5: Push 分支**

```bash
git status   # 确认无遗留改动
git push -u origin feature/eval-e2-synthesis
```

- [ ] **Step 6: 尝试 gh CLI 开 PR（Windows 经常没装，退回手工）**

```bash
gh pr create --title "PR E2 — RAG eval closed-loop: synthesis + review UI" --body "$(cat <<'EOF'
## Summary

- 按 spec §6.2 落合成闭环：KB 采样 → 分批调 Python `/synthesize` → 落 `t_eval_gold_item`（含 `source_chunk_text` 字节级冻结快照）
- 按 spec §7.2 补 `GoldDatasetService / GoldDatasetSynthesisService / GoldItemReviewService` + `SynthesisProgressTracker`（进程内）
- 按 spec §11 上 `/admin/eval-suites` 单页三 Tab（黄金集完整实现，评估运行 / 趋势对比占位由 PR E3 填实）
- 新增跨域 `KbChunkSamplerPort`（framework）+ impl（knowledge），替代直读 `t_knowledge_chunk`

## 硬约束对齐

- 零新增 ThreadLocal（`principalUserId` 走方法参数）
- 不用 `@Async`（`@Qualifier("evalExecutor").execute(Runnable)`）
- 所有 eval controller `@SaCheckRole("SUPER_ADMIN")`（EVAL-3 前不降级）
- 跨 HTTP 字段全部 `@JsonProperty` 显式 snake_case（延续 PR E1）

## 测试

- Java 单测 4 个新类 + 1 个 @SpringBootTest（KbChunkSamplerImplTest）全绿
- Python 回归 7/7（PR E1 套件无变化）
- E2E smoke：创建 → 合成 5 条 → 审核 y/n/e → 激活 → 归档 → 删除 走通；DB `source_chunk_text` 快照字节级一致

## 硬停止点（本 PR 不做）

- `AnswerPipeline` 抽取 / `ChatForEvalService` / `EvalRunExecutor` — PR E3
- `retrieved_chunks` redaction — EVAL-3
- legacy `@Async` 失效修复 — EVAL-2

## 已知边界（EVAL-4）

`SynthesisProgressTracker` 进程内；重启后 RUNNING 丢失，已有 gold_item 留在 DB。UI 据 `totalItemCount > 0` 隐藏合成按钮，用户需 delete dataset 重来。改进方案在 backlog EVAL-4。

## Test plan

- [ ] `mvn -pl bootstrap test` 绿 + 无新增 baseline 失败
- [ ] `cd ragas && python -m pytest tests/` 7/7
- [ ] `cd frontend && ./node_modules/.bin/tsc --noEmit` + `npm run build` 成功
- [ ] 起 `ragent-eval` 容器 → 完整 E2E smoke 通过
- [ ] `t_eval_gold_item.source_chunk_text` 与原 KB chunk 字节级一致
EOF
)"
```

如果 `gh` 不可用（Windows），输出 PR body 文本 + 分支名，交给用户手工到 GitHub 创建 PR。

- [ ] **Step 7: 最终 dev_log 收尾**

把本 session 过程中遇到的坑 / 修改记录 追加回 `log/dev_log/2026-04-25-eval-pr-e2-synthesis.md`，commit：

```bash
git add log/dev_log/2026-04-25-eval-pr-e2-synthesis.md
git commit -m "docs(dev_log): finalize PR E2 session log with execution notes"
git push
```

---

## Self-Review Checklist（写 plan 后自查）

✅ Spec §6.2（合成流程）→ Task 2 (sampler) + Task 7 (synthesis)
✅ Spec §6.1（采样 SQL 列名）→ Task 2 Mapper `@Select` 固化
✅ Spec §7.2（GoldDatasetService / GoldDatasetSynthesisService）→ Task 6 / 7
✅ Spec §7.2（GoldItemReviewService）→ Task 8
✅ Spec §11（/admin/eval-suites 新页面 + review 分屏 + y/n/e）→ Task 11 / 12 / 13
✅ Spec §11（与 legacy /admin/evaluations 严格区分）→ Task 11 Step 7（菜单里并列两个独立条目）
✅ Spec §9（零 ThreadLocal + 不用 @Async）→ Task 7 Impl 构造器 + `@Qualifier("evalExecutor")`
✅ Spec §10 gotcha #3（ACTIVE 下禁增删 item）→ Task 8 `requireDraftDataset`
✅ Spec §10 gotcha #6（ground_truth 非空）→ Python 侧已在 PR E1 `synthesize.py` 校验；Java 侧 `editGoldItem` 非空检查
✅ Spec §10 gotcha #10（`source_chunk_text` 快照字节级冻结）→ Task 7 Impl 显式从 sampled `ChunkSample` 取，不从 Python 返回
✅ Spec §10 gotcha #11（双入口严格分离）→ Task 11 `/admin/evaluations` 保留 + `/admin/eval-suites` 新增
✅ Spec §10 gotcha #12（不用 `@Async`）→ Task 7 Impl 显式 `evalExecutor.execute(Runnable)`
✅ Spec §10 gotcha #15（读接口 SUPER_ADMIN）→ Task 9 两个 Controller 类级 `@SaCheckRole`
✅ Spec §15.1（所有 eval 端点统一 SUPER_ADMIN）→ Task 9
✅ Spec §14 `@JsonProperty` 硬约束 → 无新增跨 HTTP DTO（复用 PR E1 的 `SynthesizeChunkInput / SynthesizedItem`）；若将来加需遵守

无占位符 / 无 TBD / 方法签名跨任务一致（`KbChunkSamplerPort.sampleForSynthesis(...)` / `GoldDatasetSynthesisService.trigger(...) + runSynthesisSync(...)` / `GoldItemReviewService.review / edit / list` 贯穿一致）。

---

## 依赖图 / Task 执行顺序

```
Task 0 (branch)
  ↓
Task 1 (Port) → Task 2 (Impl + Test)
  ↓
Task 3 (Properties) → Task 4 (Client timeout)
  ↓
Task 5 (ProgressTracker)
  ↓
Task 6 (GoldDatasetService) → Task 7 (SynthesisService) → Task 8 (ReviewService)
  ↓
Task 9 (Controllers)
  ↓
Task 10 (FE service) → Task 11 (FE scaffold) → Task 12 (FE list) → Task 13 (FE review)
  ↓
Task 14 (docs) → Task 15 (smoke + PR)
```

Task 1-9 属纯后端；Task 10-13 纯前端（仅 API 契约依赖 Task 9）。后端链可独立跑绿再上前端。

---

## Post-Review 修订记录（2026-04-25，基于 codex review）

Review 提出 5 条 P1/P2 + 2 条补充，全部接纳：

| 问题 | 修订位置 |
|---|---|
| **P1-1** `GoldDatasetSynthesisServiceImpl` 双构造器 Spring 无法选对 | Task 7 Impl：删 6-arg 构造器；7-arg 加 `@Autowired`；`@Qualifier("evalExecutor")` 参数允许 null（测试传 null 走 `runSynthesisSync`，`trigger` 抛 `IllegalStateException`） |
| **P1-2** trigger 缺原子占位，并发 race 可重复合成 | Task 5 `SynthesisProgressTracker.tryBegin()`（putIfAbsent）；Task 7 trigger 在 `validatePreconditions` 后显式 `tryBegin` 占坑；`validatePreconditions` 移除 `tracker.isRunning` 检查（避免和 tryBegin 语义双写） |
| **P1-3** activate 允许 PENDING > 0，审完未审条目永久锁 | Task 6 `activate()` 加 `countPending == 0` 校验 + 新测试；VO 加 `pendingItemCount`；Task 12 列表 UI 激活按钮 gate 改为 `itemCount > 0 && pendingItemCount === 0`，审核按钮加待审数徽标 |
| **P2-1a** `create` 测试 `assertThat(id).isNotBlank()` 在 mock mapper 下必 fail | Task 6 测试用 `when(mapper.insert(any())).thenAnswer(...)` 在调用时填充 `id` 后返回 1 |
| **P2-1b** Task 7 自定义 `argThat` 签名错（Mockito 需 `ArgumentMatchers.argThat` 返回 `T`） | Task 7 测试 `import static org.mockito.ArgumentMatchers.argThat`；用法改为 `argThat((GoldItemDO i) -> ...)`；删自定义 helper |
| **P2-2a** `@/components/ui/progress` 不存在 | Task 12 SynthesisProgressDialog 改为自绘 `<div>` 进度条 |
| **P2-2b** `<SelectItem value="">` Radix 运行时报错 | Task 12 用 `ALL = "__all__"` sentinel；API 调用前 `kbFilter === ALL ? undefined : kbFilter` 翻译 |
| **P2-2c** `noUnusedLocals` 下 `useMemo` / `useNavigate` 未用会让 `tsc --noEmit` 失败 | Task 11 `EvalSuitesPage` 删 `useMemo` / `useNavigate`；Task 13 `GoldSetReviewPage` 删 `useMemo` |
| **补充 1** `ground_truth` Java 入库前未校验（spec §10 gotcha #6 双重 fail-closed） | Task 7 Impl 在 `items` 迭代内加 `question/answer` blank 校验，空的 `failed++ + continue` |
| **补充 2** `delete()` 不处理子 items → orphan | Task 6 `delete()` 加 `@Transactional(rollbackFor=Exception.class)`；先软删 `t_eval_gold_item` 子条目再删 dataset |

测试未跑，但静态校对过：
- `GoldDatasetServiceImplTest`：新增 `activate_rejects_when_pending_items_remain`，`activate_DRAFT_moves_to_ACTIVE_when_no_pending` 用 `selectCount` Answer 按 SQL 片段路由返回值
- `GoldDatasetSynthesisServiceImplTest`：新增 `trigger_with_null_executor_throws_IllegalStateException`，验证 Spring 注入 executor 是真正执行门
- Frontend：`ALL` sentinel + 自绘 progress 均无需新依赖

依然未覆盖（接受进 backlog）：
- tryBegin race 的并发压力测试（难写，靠 Impl 的 `putIfAbsent` 语义保证）
- delete cascade 的单测（逻辑足够显而易见；smoke 验证即可）

