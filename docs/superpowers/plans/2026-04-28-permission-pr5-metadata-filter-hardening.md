# PR5 Plan — Metadata Filter Contract Hardening

- **日期**：2026-04-28
- **Spec**：`docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md`
- **路线图**：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B
- **TDD 模板**：每个 commit 先红再绿；测试与代码同 commit，禁止 squash 后倒挂

---

## 整体节奏

```
c1 (builder)            ──→ Layer 1 单测先红再绿
c2 (retriever 守卫)     ──→ Layer 2 守卫测试先红再绿
c3 (channel alignment)  ──→ Layer 3 三通道对齐测试
c4 (gates)              ──→ ArchUnit + grep 守门 + smoke 文档
c5 (optional cleanup)   ──→ SearchContext.metadata 字段删除（仅当 review 通过）
```

每个 commit 独立可回滚；c1 不通过则后续阻塞，c5 失败仅剥离不影响主线。

---

## c1 — DefaultMetadataFilterBuilder 强化 + Layer 1 测试

### 改的代码

**文件**：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/filter/DefaultMetadataFilterBuilder.java`

**改动**：把当前 48 行的 `build` 替换为 spec §3.1 的实现。

```java
@Override
public List<MetadataFilter> build(SearchContext ctx, String kbId) {
    if (kbId == null) {
        // QSI: kbId 缺失时返空，由 OpenSearchRetrieverService.enforceFilterContract 接住
        return Collections.emptyList();
    }
    int level = resolveLevel(ctx, kbId);
    return List.of(
            new MetadataFilter(
                    VectorMetadataFields.KB_ID,
                    MetadataFilter.FilterOp.IN,
                    List.of(kbId)),
            new MetadataFilter(
                    VectorMetadataFields.SECURITY_LEVEL,
                    MetadataFilter.FilterOp.LTE_OR_MISSING,
                    level));
}

/**
 * Resolve per-KB security level ceiling. 当 ctx.kbSecurityLevels 缺 entry 或为 null
 * （AccessScope.All / 系统态路径），返回 Integer.MAX_VALUE — 这是 query shape invariant，
 * 不是授权决策（授权由 AccessScope + AuthzPostProcessor 兜底）。让 "OpenSearch DSL 始终
 * 包含 range(metadata.security_level)" 成为可静态断言的契约。
 */
private static int resolveLevel(SearchContext ctx, String kbId) {
    Map<String, Integer> map = ctx.getKbSecurityLevels();
    if (map == null) {
        return Integer.MAX_VALUE;
    }
    Integer v = map.get(kbId);
    return v != null ? v : Integer.MAX_VALUE;
}
```

### 加的测试

**文件**：`bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/filter/DefaultMetadataFilterBuilderTest.java`

先检查文件是否存在；若存在，扩展用例；若不存在，新建。

**用例矩阵**（5 个）：

```java
class DefaultMetadataFilterBuilderTest {

    private final DefaultMetadataFilterBuilder builder = new DefaultMetadataFilterBuilder();

    private SearchContext ctxWith(Map<String, Integer> levels) {
        SearchContext ctx = new SearchContext();
        ctx.setKbSecurityLevels(levels);
        return ctx;
    }

    @Test
    void normalUser_outputsBothFilters() {
        SearchContext ctx = ctxWith(Map.of("kb-1", 1, "kb-2", 2));
        List<MetadataFilter> filters = builder.build(ctx, "kb-1");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-1");
        assertSecurityLevelFilter(filters, 1);
    }

    @Test
    void missingEntry_fallsBackToMaxValue() {
        SearchContext ctx = ctxWith(Map.of("kb-1", 1));
        List<MetadataFilter> filters = builder.build(ctx, "kb-2");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-2");
        assertSecurityLevelFilter(filters, Integer.MAX_VALUE);
    }

    @Test
    void nullMap_fallsBackToMaxValue() {
        SearchContext ctx = ctxWith(null);
        List<MetadataFilter> filters = builder.build(ctx, "kb-1");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-1");
        assertSecurityLevelFilter(filters, Integer.MAX_VALUE);
    }

    @Test
    void levelZero_isEmittedAsZeroNotFallback() {
        SearchContext ctx = ctxWith(Map.of("kb-1", 0));
        List<MetadataFilter> filters = builder.build(ctx, "kb-1");

        assertSecurityLevelFilter(filters, 0);   // 不能误退化为 MAX_VALUE
    }

    @Test
    void nullKbId_returnsEmpty() {
        SearchContext ctx = ctxWith(Map.of("kb-1", 1));
        List<MetadataFilter> filters = builder.build(ctx, null);

        assertThat(filters).isEmpty();   // 由 retriever QSI-3 接住
    }

    private static void assertKbIdFilter(List<MetadataFilter> filters, String expectedKbId) {
        MetadataFilter f = filters.stream()
                .filter(x -> VectorMetadataFields.KB_ID.equals(x.field()))
                .findFirst()
                .orElseThrow();
        assertThat(f.op()).isEqualTo(MetadataFilter.FilterOp.IN);
        assertThat(f.value()).isEqualTo(List.of(expectedKbId));
    }

    private static void assertSecurityLevelFilter(List<MetadataFilter> filters, int expectedLevel) {
        MetadataFilter f = filters.stream()
                .filter(x -> VectorMetadataFields.SECURITY_LEVEL.equals(x.field()))
                .findFirst()
                .orElseThrow();
        assertThat(f.op()).isEqualTo(MetadataFilter.FilterOp.LTE_OR_MISSING);
        assertThat(f.value()).isEqualTo(expectedLevel);
    }
}
```

### 验证

```bash
mvn -pl bootstrap test -Dtest=DefaultMetadataFilterBuilderTest
```

5 个用例全绿。

### Commit message

```
feat(security): DefaultMetadataFilterBuilder 强制输出 kb_id + security_level filter (PR5 c1)

builder 对非空 kbId 永远输出:
- kb_id IN [kbId] (singleton, OpenSearch 渲染为 terms)
- security_level LTE_OR_MISSING level (missing/null → Integer.MAX_VALUE no-op range)

让"OpenSearch DSL 始终同时含 metadata.kb_id 与 metadata.security_level filter"
成为可静态断言的 query shape invariant。Integer.MAX_VALUE 不是授权决策——授权
由 AccessScope + AuthzPostProcessor 兜底。

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B
Spec: docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md
```

---

## c2 — OpenSearchRetrieverService 缺失守卫 + Layer 2 测试

### 改的代码

**文件**：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`

**改动 1**：在 `doSearch` 入口（L100 前）加 `enforceFilterContract` 调用。

```java
private List<RetrievedChunk> doSearch(String collectionName, String query, float[] vector,
                                      int topK, List<MetadataFilter> metadataFilters) {
    enforceFilterContract(metadataFilters, collectionName);   // ← QSI-3, 在 try 之前
    try {
        // ... 原逻辑 ...
    } catch (IllegalStateException e) {
        throw e;   // 显式不吞契约违规
    } catch (Exception e) {
        log.error("OpenSearch retrieval failed for index: {}", collectionName, e);
        return List.of();
    }
}

/**
 * QSI-3: 所有 OpenSearch retrieval 必须经过 DefaultMetadataFilterBuilder，
 * filter set 至少含 kb_id IN 与 security_level LTE_OR_MISSING。
 * 缺失即 fail-fast，避免静默查询绕过权限边界。
 */
private static void enforceFilterContract(List<MetadataFilter> filters, String collectionName) {
    boolean hasKbId = filters != null && filters.stream()
            .anyMatch(f -> VectorMetadataFields.KB_ID.equals(f.field())
                    && f.op() == MetadataFilter.FilterOp.IN);
    boolean hasSecurityLevel = filters != null && filters.stream()
            .anyMatch(f -> VectorMetadataFields.SECURITY_LEVEL.equals(f.field())
                    && f.op() == MetadataFilter.FilterOp.LTE_OR_MISSING);
    if (!hasKbId || !hasSecurityLevel) {
        throw new IllegalStateException(
                "OpenSearch filter contract violated for collection=" + collectionName
                + ", hasKbIdFilter=" + hasKbId + ", hasSecurityLevelFilter=" + hasSecurityLevel
                + ". All retrieval must go through DefaultMetadataFilterBuilder.");
    }
}
```

### 加的测试

**文件**：`bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceFilterContractTest.java`

mock OpenSearch client 与 `RagDefaultProperties`；通过反射或测试用 ctor 触发 `doSearch`。如果 `doSearch` 是 private，可改为通过 `retrieve` / `retrieveByVector` 公共入口触发。

**用例**（4 个守卫 + 1 个 DSL shape）：

```java
class OpenSearchRetrieverServiceFilterContractTest {

    private OpenSearchRetrieverService service;
    // ... mock setup: client, ragDefaultProperties, objectMapper ...

    @Test
    void emptyFilters_failFast() {
        RetrieveRequest req = baseRequest().metadataFilters(List.of()).build();
        assertThatThrownBy(() -> service.retrieve(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filter contract violated")
                .hasMessageContaining("hasKbIdFilter=false");
    }

    @Test
    void nullFilters_failFast() {
        RetrieveRequest req = baseRequest().metadataFilters(null).build();
        assertThatThrownBy(() -> service.retrieve(req))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyKbId_failFast() {
        RetrieveRequest req = baseRequest()
                .metadataFilters(List.of(kbIdFilter("kb-1")))
                .build();
        assertThatThrownBy(() -> service.retrieve(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hasSecurityLevelFilter=false");
    }

    @Test
    void onlySecurityLevel_failFast() {
        RetrieveRequest req = baseRequest()
                .metadataFilters(List.of(securityLevelFilter(2)))
                .build();
        assertThatThrownBy(() -> service.retrieve(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hasKbIdFilter=false");
    }

    @Test
    void buildKnnOnlyQuery_dslContainsTermsAndRange() {
        // 反射调 private buildKnnOnlyQuery, 不走 HTTP mock 链
        float[] vector = {0.1f, 0.2f, 0.3f};
        List<MetadataFilter> filters = List.of(kbIdFilter("kb-1"), securityLevelFilter(2));

        String dsl = ReflectionTestUtils.invokeMethod(
                service, "buildKnnOnlyQuery", vector, 5, filters);

        assertThat(dsl).contains("\"terms\"").contains("metadata.kb_id");
        assertThat(dsl).contains("\"range\"").contains("metadata.security_level");
    }

    @Test
    void buildHybridQuery_dslContainsTermsAndRange() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        List<MetadataFilter> filters = List.of(kbIdFilter("kb-1"), securityLevelFilter(2));

        String dsl = ReflectionTestUtils.invokeMethod(
                service, "buildHybridQuery", "test query", vector, 5, filters);

        assertThat(dsl).contains("\"terms\"").contains("metadata.kb_id");
        assertThat(dsl).contains("\"range\"").contains("metadata.security_level");
    }

    private static MetadataFilter kbIdFilter(String kbId) {
        return new MetadataFilter(VectorMetadataFields.KB_ID,
                MetadataFilter.FilterOp.IN, List.of(kbId));
    }

    private static MetadataFilter securityLevelFilter(int level) {
        return new MetadataFilter(VectorMetadataFields.SECURITY_LEVEL,
                MetadataFilter.FilterOp.LTE_OR_MISSING, level);
    }
}
```

> **实现策略（默认走 fallback，不搭 HTTP mock 链）**：
> - **fail-fast 用例 1-4**：用 `retrieveByVector(req)` 触发 `doSearch`，避免 embedding 计算 mock 链
> - **DSL shape 用例 5**：直接用 `ReflectionTestUtils.invokeMethod(service, "buildKnnOnlyQuery", vector, topK, filters)` / `"buildHybridQuery"` 反射调 private 方法，断言返回 JSON 字符串包含 `"terms"` + `"metadata.kb_id"` 与 `"range"` + `"metadata.security_level"` 子串。**不**通过 HTTP client mock 捕获 request body
> - 现有 `OpenSearchRetrieverServiceTest` 已用 `ReflectionTestUtils` 注入 `@Value` 字段，风格统一

### 验证

```bash
mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceFilterContractTest
mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest   # 既有测试不能挂
```

### Commit message

```
feat(security): OpenSearchRetrieverService fail-fast 缺失 filter (PR5 c2)

doSearch 入口加 enforceFilterContract: filter set 不同时含 kb_id IN 与
security_level LTE_OR_MISSING 时抛 IllegalStateException, 显式不被
catch (Exception) 吞成空 list (catch IllegalStateException → rethrow).

锁住 spec QSI-3: 所有 retrieval 都必须经 DefaultMetadataFilterBuilder.
Layer 2 契约测试 4 守卫用例 + DSL shape 断言.

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B
Spec: docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md
```

---

## c3 — Layer 3 通道对齐测试

### 加的测试（拆 3 个独立 test class）

依赖图差异较大，c3 默认拆成 3 个独立测试类，每个锁住一个通道：

| 文件 | 通道 | 锁住的语义 |
|---|---|---|
| `MultiChannelRetrievalEngineFilterAlignmentTest` | `MultiChannelRetrievalEngine` 单 KB 路径 | `req.collectionName ↔ req.metadataFilters[kb_id]` 来自 `scope.targetKbId` |
| `VectorGlobalChannelFilterAlignmentTest` | `VectorGlobalSearchChannel` per-KB 循环 | 每次循环迭代的 `collectionName ↔ kb_id filter` 严格对齐当前迭代 KB |
| `IntentParallelRetrieverFilterAlignmentTest` | `IntentParallelRetriever` per-intent → per-KB | 每个 `IntentNode` 上对应的 `collectionName ↔ kb_id filter` 是一致的 pair（语义来源是 IntentNode 本身，**不**暗示走 `KbMetadataReader`） |

**异步处理**：所有用例的 executor 用 direct executor（`Runnable::run`）压掉异步不确定性，让捕获到的 `RetrieveRequest` 顺序可断言。

**核心断言**（每个测试类都有一个 helper）：每次通道调 `retrieverService.retrieve(req)` 时，`req.collectionName` 与 `req.metadataFilters[kb_id].value[0]` 配对来自同一 KB。

```java
class MetadataFilterChannelAlignmentTest {

    @Mock private RetrieverService retrieverService;
    @Mock private KbMetadataReader kbMetadataReader;
    // ... 其他依赖 mock ...

    @Captor private ArgumentCaptor<RetrieveRequest> reqCaptor;

    @BeforeEach
    void setUp() {
        when(kbMetadataReader.getCollectionName("kb-1")).thenReturn("col-kb-1");
        when(kbMetadataReader.getCollectionName("kb-2")).thenReturn("col-kb-2");
        when(retrieverService.retrieve(any())).thenReturn(List.of());
    }

    @Test
    void multiChannelEngine_singleKb_alignsCollectionAndKbIdFilter() {
        // 构造 SearchContext + scope 单 KB; 触发 MultiChannelRetrievalEngine 单 KB 路径
        engine.retrieveKnowledgeChannels(/* scope.targetKbId=kb-1 */);

        verify(retrieverService, atLeastOnce()).retrieve(reqCaptor.capture());
        for (RetrieveRequest req : reqCaptor.getAllValues()) {
            assertCollectionAlignsKbIdFilter(req);
        }
    }

    @Test
    void vectorGlobalSearchChannel_perKb_alignsEachIteration() {
        // scope.accessScope = Ids(kb-1, kb-2); 触发 per-KB 循环
        channel.search(/* ... */);

        verify(retrieverService, times(2)).retrieve(reqCaptor.capture());
        List<RetrieveRequest> reqs = reqCaptor.getAllValues();
        // 每次调用 collection 与 kb_id filter 严格对齐
        for (RetrieveRequest req : reqs) {
            assertCollectionAlignsKbIdFilter(req);
        }
        // 两次调用分别对应 kb-1 / kb-2
        assertThat(reqs.stream().map(RetrieveRequest::getCollectionName))
                .containsExactlyInAnyOrder("col-kb-1", "col-kb-2");
    }

    @Test
    void intentParallelRetriever_perIntent_alignsKbIdFilterWithIntentNodeKb() {
        // IntentParallelRetriever 的 collection/kbId 来自 IntentNode 本身,
        // 不走 KbMetadataReader. 测试断言: 每个 IntentNode 上的 (kbId, collectionName)
        // pair 与 builder 输出 kb_id filter 严格对齐.
        retriever.retrieve(/* ... 多个 intentNode 各带不同 kbId/collection ... */);

        verify(retrieverService, atLeastOnce()).retrieve(reqCaptor.capture());
        for (RetrieveRequest req : reqCaptor.getAllValues()) {
            assertCollectionAlignsKbIdFilter(req);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertCollectionAlignsKbIdFilter(RetrieveRequest req) {
        String collection = req.getCollectionName();
        MetadataFilter kbIdFilter = req.getMetadataFilters().stream()
                .filter(f -> VectorMetadataFields.KB_ID.equals(f.field()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing kb_id filter for " + collection));
        List<String> values = (List<String>) kbIdFilter.value();
        assertThat(values).hasSize(1);

        // 反查 collection → kbId 是否与 filter 单元素一致
        // 这里通过 mock setup 已建立 col-kb-1 ↔ kb-1 映射
        assertThat(collectionToKbId(collection)).isEqualTo(values.get(0));
    }
}
```

> **拆分边界**：每个测试类只 mock 自己通道的依赖；共用的 `assertCollectionAlignsKbIdFilter` helper 可放到一个 `package-private` 工具类（例如 `FilterAlignmentAssertions`）共享，避免三处复制。

### 验证

```bash
mvn -pl bootstrap test -Dtest='MetadataFilterChannelAlignmentTest,*FilterAlignmentTest'
```

### Commit message

```
test(security): 通道级 per-KB filter 对齐契约测试 (PR5 c3)

锁住 spec QSI-4: MultiChannelRetrievalEngine / VectorGlobalSearchChannel /
IntentParallelRetriever 三处通道每次调 retrieverService.retrieve 时,
req.collectionName 与 req.metadataFilters[kb_id].value[0] 来自同一 KB.

防止未来重构出现"collection 是 kb-1 但 filter 是 kb-2"的错位.

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B
Spec: docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md
```

---

## c4 — 守门脚本 + ArchUnit + Smoke 文档

### 加的脚本

**文件**：`docs/dev/verification/permission-pr5-filter-contract.sh`

```bash
#!/usr/bin/env bash
# PR5 守门 — Metadata Filter Contract Hardening
# 用法: bash docs/dev/verification/permission-pr5-filter-contract.sh
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
SRC="$ROOT/bootstrap/src/main/java"

fail=0

echo "[PR5 G1] channel/engine/retriever 不得直接 new MetadataFilter (builder/test 白名单)"
hits=$(rg -n "new MetadataFilter\(" "$SRC" \
    --glob '!**/filter/DefaultMetadataFilterBuilder.java' \
    --glob '!**/test/**' || true)
if [[ -n "$hits" ]]; then
    echo "❌ 发现非白名单 new MetadataFilter:"
    echo "$hits"
    fail=1
fi

echo "[PR5 G2] OpenSearchRetrieverService 必须含 enforceFilterContract"
if ! rg -q "enforceFilterContract" "$SRC/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java"; then
    echo "❌ OpenSearchRetrieverService 缺 enforceFilterContract 守卫"
    fail=1
fi

echo "[PR5 G3] enforceFilterContract 必须在 doSearch 方法内的第一处 try 之前"
# 注意: OpenSearchRetrieverService 还有 init() 等更早的 try (L55), 全文件第一处 try
# 可能是 init 里的 try, 所以必须把作用域限定在 doSearch 方法内.
file="$SRC/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java"
do_search_line=$(rg -n "private List<RetrievedChunk> doSearch\(" "$file" | head -1 | cut -d: -f1 || echo 0)
if [[ "$do_search_line" -eq 0 ]]; then
    echo "❌ 找不到 doSearch 方法签名"
    fail=1
else
    # 在 doSearch 行号之后查找 enforceFilterContract 和 try
    enforce_line=$(awk -v start="$do_search_line" 'NR > start && /enforceFilterContract\(metadataFilters/ { print NR; exit }' "$file" || echo 0)
    try_line=$(awk -v start="$do_search_line" 'NR > start && /^\s+try \{/ { print NR; exit }' "$file" || echo 0)
    if [[ "$enforce_line" -eq 0 ]]; then
        echo "❌ doSearch 方法内未调用 enforceFilterContract"
        fail=1
    elif [[ "$try_line" -eq 0 ]]; then
        echo "❌ doSearch 方法内未找到 try 块"
        fail=1
    elif [[ "$enforce_line" -ge "$try_line" ]]; then
        echo "❌ doSearch 内 enforceFilterContract 未在第一处 try 之前 (enforce@$enforce_line, try@$try_line, doSearch@$do_search_line)"
        fail=1
    fi
fi

echo "[PR5 G4] catch (IllegalStateException) 显式 rethrow 防御"
if ! rg -q "catch \(IllegalStateException" "$file"; then
    echo "❌ OpenSearchRetrieverService 缺 catch (IllegalStateException) rethrow 防御"
    fail=1
fi

if [[ "$fail" -eq 0 ]]; then
    echo "✅ PR5 守门通过"
else
    exit 1
fi
```

### 加的 ArchUnit 规则

**文件**：`bootstrap/src/test/java/com/knowledgebase/ai/ragent/arch/MetadataFilterConstructionArchTest.java`

```java
class MetadataFilterConstructionArchTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.knowledgebase.ai.ragent");

    @Test
    void onlyDefaultMetadataFilterBuilderConstructsMetadataFilter() {
        ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage("..rag.core.retrieve..")
                .and()
                .doNotHaveSimpleName("DefaultMetadataFilterBuilder")
                .should()
                .callConstructor(MetadataFilter.class, String.class, MetadataFilter.FilterOp.class, Object.class)
                .because("PR5 QSI: MetadataFilter 构造仅允许在 DefaultMetadataFilterBuilder 内进行, "
                        + "防止 channel / engine / retriever 绕过 builder 输出不完整 filter set")
                .check(classes);
    }
}
```

### Smoke 文档

**文件**：`docs/dev/verification/permission-pr5-smoke.md`

写 4 条手工 smoke（**S5b 是发布阻塞项**）：

- **S5（普通用户）**：FICC_USER 登录 → 在 OPS-COB KB 上检索 → 网络面板 / 后端日志确认 OpenSearch DSL 含 `terms metadata.kb_id` 与 `range metadata.security_level <= 0`
- **S5b【发布阻塞项 — 部署前必跑】生产 OpenSearch 历史数据兼容性核查**：对每个 KB 索引（`<collection-name>`）执行：
  ```bash
  curl -s -X POST "$OS/<collection-name>/_count" -H 'Content-Type: application/json' -d '{
    "query": { "bool": { "must_not": [ { "exists": { "field": "metadata.kb_id" } } ] } }
  }'
  ```
  - **命中数 == 0** → S5b 通过，PR5 可合
  - **命中数 > 0** → **PR5 合并阻塞**，必须先做 reindex / backfill 把缺失的 `metadata.kb_id` 字段补齐（KB ↔ index 1:1，可从 `t_knowledge_base.collection_name` 反推 `kbId`），再合 PR5。**禁止**带历史空 `kb_id` 文档合并 PR5——这些文档会被 c1 加的 `terms(metadata.kb_id, [kbId])` 全量过滤为不可见
  - 检测脚本可放到 ops 工具链，并在每次发布前自动跑一次
- **S6（系统态）**：触发离线评估或 MQ 消费路径 → 后端日志确认 DSL 含 `range metadata.security_level <= 2147483647` no-op，但仍出现该子句
- **S7（绕过攻击）**：人为构造一个绕过 builder 的调用（如临时改测试代码传空 filter 列表）→ 后端抛 `IllegalStateException`，日志含 `filter contract violated`，**不**返回空 list

### 验证

```bash
bash docs/dev/verification/permission-pr5-filter-contract.sh
mvn -pl bootstrap test -Dtest=MetadataFilterConstructionArchTest
```

两者全绿。

### Commit message

```
test(security): PR5 守门脚本 + ArchUnit + smoke 文档 (PR5 c4)

- docs/dev/verification/permission-pr5-filter-contract.sh: 4 道 grep 守门
  (G1 new MetadataFilter 白名单, G2-G4 enforceFilterContract 存在/位置/防御)
- MetadataFilterConstructionArchTest: 仅 DefaultMetadataFilterBuilder 可
  构造 MetadataFilter
- docs/dev/verification/permission-pr5-smoke.md: 3 条手工 smoke (S5/S6/S7)

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B
Spec: docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md
```

---

## c5（可选）— SearchContext.metadata 字段清理

**前置条件**：以下 grep 在 `bootstrap/src` 内对 `SearchContext` 实例零 caller（不仅 getter，也包括 setter / builder method），且 review 通过：

```bash
# 注意 SearchContext 用 Lombok @Data, getter/setter 都自动生成; builder 也有 .metadata(...)
# 别只看 getMetadata, 否则会漏掉写入端的引用
rg -n "ctx\.getMetadata|searchContext\.getMetadata|context\.getMetadata" bootstrap/src
rg -n "ctx\.setMetadata|searchContext\.setMetadata|context\.setMetadata" bootstrap/src
rg -n "\.metadata\(" bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag    # builder method
```

排除：`VectorChunk.getMetadata()` / `MilvusVectorStoreService` / `OpenSearchVectorStoreService` / `PgVectorStoreService` 中的 `chunk.getMetadata()`（这些是 `VectorChunk.metadata` 字段，不是 `SearchContext.metadata`）。

### 改的代码

**文件**：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/SearchContext.java`

删除：
- 字段 `private Map<String, Object> metadata = new HashMap<>();`
- builder method `metadata(Map<String, Object> v)`
- `build()` 内 `ctx.setMetadata(...)`

### 验证

```bash
mvn -pl bootstrap test
mvn spotless:check
```

### Commit message

```
refactor(rag): 删除 SearchContext.metadata 零 caller 字段 (PR5 c5)

审计 rag/ 域确认零 caller, 零测试依赖, 零未来语义计划. PR5 contract
hardening 完成后顺带清理.

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B
```

> 若 review 不通过则**不提交此 commit**——主线 c1-c4 不依赖此清理。

---

## 全程验证（PR 提交前）

```bash
# 1. 完整测试
mvn -pl bootstrap test

# 2. 守门
bash docs/dev/verification/permission-pr5-filter-contract.sh
bash docs/dev/verification/permission-pr3-leak-free.sh   # PR3 不能回归
bash docs/dev/verification/permission-pr4-*.sh           # PR4 不能回归

# 3. ArchUnit
mvn -pl bootstrap test -Dtest='*ArchTest'

# 4. 格式
mvn spotless:check

# 5. 启动 smoke (按 permission-pr5-smoke.md S5/S6/S7)
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

---

## 回滚预案

- c1 / c2 / c3 / c4 可独立 revert
- c1 revert 不影响 c2 守卫（守卫只检查输入 filter 形态，不依赖 builder 实现细节）
- c2 revert 后即使有人意外不带 filter 也只是退化为 PR4 行为，不会引入新风险
- c5 默认不合并不需回滚

---

## 引用源

- Spec：`docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md`
- 路线图：`docs/dev/design/2026-04-26-permission-roadmap.md`
- PR4 plan 参考：`docs/superpowers/plans/2026-04-28-permission-pr4-retrieval-scope-builder.md`
- 关键文件清单见 spec §10
