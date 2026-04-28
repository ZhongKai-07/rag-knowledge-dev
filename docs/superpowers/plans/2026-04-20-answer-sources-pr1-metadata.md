# Answer Sources PR1: Retrieval Metadata Plumbing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thread `docId` and `chunkIndex` from OpenSearch (the current production vector store) through the retrieval layer into `RetrievedChunk`, and add a batch `findMetaByIds` query on `KnowledgeDocumentService` returning `DocumentMetaSnapshot` records. Zero downstream caller signature churn; `RetrievedChunk` equality is explicitly pinned to `id` so the addition is immune to future field sprawl.

**Architecture:** Add two new nullable optional fields (`docId`, `chunkIndex`) directly to the existing `framework.convention.RetrievedChunk`, following the established pattern of `kbId` / `securityLevel` (also nullable-optional, populated by OpenSearch only, explicitly documented as "非 OpenSearch 后端当前不回填"). `OpenSearchRetrieverService.toRetrievedChunk` populates the new fields from response metadata. `VectorMetadataFields` gains two new constants (`DOC_ID`, `CHUNK_INDEX`) to preserve the single-source-of-truth invariant. A new `DocumentMetaSnapshot` record and `findMetaByIds` service method give subsequent PRs a clean batch DB-lookup path.

**Tech Stack:** Java 17, Spring Boot 3.5.7, MyBatis Plus 3.5.14, JUnit 5, Mockito, Lombok, OpenSearch 2.18.

---

## Resolved decisions (confirmed with reviewer, 2026-04-20)

1. **Direct-field extension over `EnrichedChunk` wrapper.** Spec `docs/superpowers/specs/2026-04-17-answer-sources-design.md` (lines 94–106) introduces a new `EnrichedChunk` wrapper class with delegation accessors. We instead add two fields directly to the existing `RetrievedChunk`:
   - Matches existing pattern precisely (`kbId`/`securityLevel` are already nullable optional fields populated only by OpenSearch).
   - Zero downstream signature churn (5+ call sites across `MultiChannelRetrievalEngine` / post-processors / `RAGPromptService` read-only pass-through).
   - Task 0 below updates the spec document so text and code stay aligned.

2. **OpenSearch only.** Milvus and PG retrievers are out of scope for PR1's read-path wiring. Their existing `// dev-only` comments previously only mentioned `kbId/securityLevel` not being backfilled — Task 0 extends them (plus `RagVectorTypeValidator`'s startup WARN) to also name `docId/chunkIndex` so future readers aren't misled into thinking the new fields ARE filled there. This halves PR1's test surface while leaving the production path (`rag.vector.type=opensearch`) fully wired.

3. **Pin `RetrievedChunk` equality to `id`.** `RetrievedChunk` is currently `@Data`, so Lombok generates all-fields `equals`/`hashCode`. Simply adding two more fields silently redefines equality — and `RAGChatServiceImpl.java:194` runs `.distinct()` on a `Stream<RetrievedChunk>` (the only equality-sensitive call site in the entire codebase; grep verified). Pinning equality to `id` via `@EqualsAndHashCode(of = "id")` matches domain semantics (chunks ARE identified by vector-store PK), makes `distinct()` behavior explicit, and immunizes future field additions. This is a micro one-off semantic migration (from "accidental all-fields via @Data" to "explicitly id-only"), not zero-change — called out so reviewers don't read "direct-field extension" as "no equality impact".

---

## File Structure

**Modified (8 files):**
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunk.java` — add 2 fields + `@EqualsAndHashCode(of = "id")` pin
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorMetadataFields.java` — add 2 constants
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java` — extract `doc_id` / `chunk_index` in `toRetrievedChunk`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java` — Task 0 comment update (dev-only gap now names docId/chunkIndex)
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java` — Task 0 comment update (same)
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java` — Task 0 WARN message update
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java` — add `findMetaByIds` method
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java` — impl
- `docs/superpowers/specs/2026-04-17-answer-sources-design.md` — Task 0 spec update

**Created (4 files):**
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dto/DocumentMetaSnapshot.java` — new record
- `framework/src/test/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunkTest.java`
- `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceTest.java`
- `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImplFindMetaTest.java`

---

## Task 0: Sync spec + in-code comments for direct-field + OpenSearch-only decisions

**Files:**
- Modify: `docs/superpowers/specs/2026-04-17-answer-sources-design.md`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java`

- [ ] **Step 1: Replace the `EnrichedChunk` code block (spec lines ~94–106)**

Replace:

```java
// bootstrap/rag/core/retrieve/EnrichedChunk.java（新）
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EnrichedChunk {
    private RetrievedChunk chunk;       // 委托，不复制字段
    private String docId;                // 从 vector metadata 回填
    private Integer chunkIndex;

    public String getChunkId() { return chunk.getId(); }
    public String getText()    { return chunk.getText(); }
    public Float  getScore()   { return chunk.getScore(); }
}
```

With:

```java
// framework/convention/RetrievedChunk.java（扩展，新增 2 字段）
// 沿用既有"可空可选字段"模式（类似 kbId / securityLevel，非 OpenSearch 后端不回填）
private String docId;                // 从 vector metadata.doc_id 回填
private Integer chunkIndex;          // 从 vector metadata.chunk_index 回填
```

- [ ] **Step 2: Update the architecture block (spec lines ~39–53)**

Replace `返回 List<EnrichedChunk>（保留旧 RetrievedChunk 路径）` with `在 RetrievedChunk 上扩展 docId/chunkIndex 两个可空字段（沿用 kbId/securityLevel 的可选字段模式，零行为变化）`.

- [ ] **Step 3: Add an OpenSearch-only scope note to PR1 row in spec's "PR 拆分" table (lines ~594–600)**

In the PR1 row, append: `（OpenSearch only — Milvus/PG 保持既有 dev-only 不回填状态，未来启用时再补）`.

- [ ] **Step 4: Update MilvusRetrieverService dev-only comment**

In `MilvusRetrieverService.java` line 88, replace:

```java
        // Milvus dev-only: kbId/securityLevel 不回填, AuthzPostProcessor 会在认证会话中 fail-close。
```

With:

```java
        // Milvus dev-only: kbId/securityLevel/docId/chunkIndex 均不回填.
        // AuthzPostProcessor 会对缺 kbId 的 chunk 在认证会话中 fail-close;
        // "回答来源" (PR1 onwards) 依赖 docId/chunkIndex, 本后端下 source 卡片永远为空 —— 未来启用前需补齐.
```

- [ ] **Step 5: Update PgRetrieverService dev-only comment**

In `PgRetrieverService.java` line 53, replace:

```java
        // Pg dev-only: kbId/securityLevel 不回填, AuthzPostProcessor 会在认证会话中 fail-close。
```

With:

```java
        // Pg dev-only: kbId/securityLevel/docId/chunkIndex 均不回填.
        // AuthzPostProcessor 会对缺 kbId 的 chunk 在认证会话中 fail-close;
        // "回答来源" (PR1 onwards) 依赖 docId/chunkIndex, 本后端下 source 卡片永远为空 —— 未来启用前需补齐.
```

- [ ] **Step 6: Update RagVectorTypeValidator warn message**

In `RagVectorTypeValidator.java`, replace the Javadoc (lines 25–31) and the `log.warn` call (lines 42–46):

```java
/**
 * 向量库类型配置校验器。
 * <p>
 * 非 opensearch 配置时打印 WARN: Milvus/Pg 实现不回填 {@code RetrievedChunk.kbId} /
 * {@code securityLevel} / {@code docId} / {@code chunkIndex}; AuthzPostProcessor 对缺
 * {@code kbId} 的 chunk 在认证会话里 fail-closed, "回答来源"功能下 source 卡片永远为空。
 * 开发/测试环境可用，不建议生产。
 */
```

```java
            log.warn("RAG vector backend is '{}' (not opensearch). " +
                            "AuthzPostProcessor will fail-close all chunks where kbId is null " +
                            "in authenticated sessions; answer-sources feature (PR1+) will show " +
                            "empty source cards because docId/chunkIndex are not backfilled either. " +
                            "This is a dev-only configuration — do NOT use in production without " +
                            "equivalent authz + source metadata support.",
                    vectorType);
```

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/specs/2026-04-17-answer-sources-design.md bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java
git commit -m "chore(sources): sync spec + Milvus/Pg/Validator comments for PR1 scope [PR1]"
```

---

## Task 1: Add DOC_ID and CHUNK_INDEX constants to VectorMetadataFields

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorMetadataFields.java`

Rationale: CLAUDE.md Gotcha: "VectorMetadataFields single source of truth: Always reference ... constants, not string literals." The write path currently uses string literals `"doc_id"` / `"chunk_index"` — we're not sweeping the write-path literal replacement in PR1 (out of scope), but the new read-path extraction must reference constants so future renames stay atomic.

- [ ] **Step 1: Add two constants**

Insert after the existing `SECURITY_LEVEL` constant (line 33), before the private constructor:

```java
    /** 文档 ID. 写路径: {@code OpenSearchVectorStoreService}/{@code IndexerNode}. 读路径: {@code OpenSearchRetrieverService} 回填到 {@link com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk#getDocId()}. */
    public static final String DOC_ID = "doc_id";

    /** 分块在文档内的顺序索引. 写路径同 DOC_ID. 读路径回填到 {@link com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk#getChunkIndex()}. */
    public static final String CHUNK_INDEX = "chunk_index";
```

- [ ] **Step 2: Verify compile**

```bash
mvn -pl bootstrap compile -DskipTests -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorMetadataFields.java
git commit -m "feat(rag): add DOC_ID / CHUNK_INDEX constants to VectorMetadataFields [PR1]"
```

---

## Task 2: Extend RetrievedChunk with docId and chunkIndex fields

**Files:**
- Create: `framework/src/test/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunkTest.java`
- Modify: `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunk.java`

- [ ] **Step 1: Write failing test**

`framework/src/test/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunkTest.java`:

```java
package com.knowledgebase.ai.ragent.framework.convention;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievedChunkTest {

    @Test
    void builder_shouldCarryDocIdAndChunkIndex() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("c_1")
                .text("hello")
                .score(0.9f)
                .docId("doc_abc")
                .chunkIndex(3)
                .build();

        assertThat(chunk.getDocId()).isEqualTo("doc_abc");
        assertThat(chunk.getChunkIndex()).isEqualTo(3);
    }

    @Test
    void newFields_shouldBeNullableOptional() {
        // 沿用 kbId/securityLevel 的 nullable 约定, 未设置时必须为 null 且不抛
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("c_1")
                .text("hello")
                .score(0.9f)
                .build();

        assertThat(chunk.getDocId()).isNull();
        assertThat(chunk.getChunkIndex()).isNull();
        assertThat(chunk.getKbId()).isNull();
        assertThat(chunk.getSecurityLevel()).isNull();
    }

    @Test
    void toBuilder_shouldPreserveNewFields() {
        RetrievedChunk original = RetrievedChunk.builder()
                .id("c_1")
                .docId("doc_abc")
                .chunkIndex(3)
                .build();

        RetrievedChunk copy = original.toBuilder().score(0.5f).build();

        assertThat(copy.getDocId()).isEqualTo("doc_abc");
        assertThat(copy.getChunkIndex()).isEqualTo(3);
        assertThat(copy.getScore()).isEqualTo(0.5f);
    }

    @Test
    void equality_shouldBePinnedToIdOnly() {
        // 固定 equality 语义到 id, 避免新增字段静默改变 RAGChatServiceImpl.distinct() 行为.
        // 域语义: chunk 由 vector store PK 唯一标识; 其他字段是表现数据, 不参与身份比较.
        RetrievedChunk a = RetrievedChunk.builder()
                .id("c_1").text("hello").score(0.9f)
                .docId("doc_abc").chunkIndex(3).build();
        RetrievedChunk b = RetrievedChunk.builder()
                .id("c_1").text("different").score(0.1f)
                .docId("doc_xyz").chunkIndex(99).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        RetrievedChunk c = RetrievedChunk.builder()
                .id("c_2").text("hello").score(0.9f)
                .docId("doc_abc").chunkIndex(3).build();
        assertThat(a).isNotEqualTo(c);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl framework test -Dtest=RetrievedChunkTest -q
```

Expected: `COMPILATION ERROR` — method `docId(String)` / `chunkIndex(Integer)` not found on builder. (Step 3 adds the fields AND `@EqualsAndHashCode(of = "id")` in one edit; if implementer splits and adds only fields, `equality_shouldBePinnedToIdOnly` will then fail because `@Data`'s default all-fields equality survives — that's the signal to add the annotation.)

- [ ] **Step 3: Add fields + pin equality on RetrievedChunk**

Add `@EqualsAndHashCode(of = "id")` to the class-level annotation block (above `@Data` or alongside it — Lombok merges them). Insert the new fields after the existing `securityLevel` field (after line 66 of current file, before the closing brace).

Full annotation block becomes:

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode(of = "id")
public class RetrievedChunk {
```

Add the import: `import lombok.EqualsAndHashCode;`

Fields appended after `securityLevel`:

```java
    /**
     * 文档 ID（从向量库 metadata.doc_id 回填）。
     * 用于"回答来源"功能按文档聚合 chunks。
     * OpenSearch 后端回填；其他后端（Milvus/Pg）当前不回填（与 kbId/securityLevel 同例），空值兼容旧数据。
     */
    private String docId;

    /**
     * 分块在所属文档内的顺序索引（从 metadata.chunk_index 回填）。
     * 用于 source 卡片内的阅读顺序展示；非顺序索引（如跨文档的全局序号）不使用此字段。
     * OpenSearch 后端回填；其他后端当前不回填，空值兼容旧数据。
     */
    private Integer chunkIndex;
```

- [ ] **Step 4: Install framework so bootstrap picks up changes**

```bash
mvn -pl framework install -DskipTests -q
```

Expected: `BUILD SUCCESS`. (Per CLAUDE.md: "Cross-module source changes require `mvn install`".)

- [ ] **Step 5: Run test**

```bash
mvn -pl framework test -Dtest=RetrievedChunkTest -q
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 6: Spotless check + commit**

```bash
mvn -pl framework spotless:check -q
# if fails: mvn -pl framework spotless:apply -q
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunk.java framework/src/test/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunkTest.java
git commit -m "feat(framework): add docId / chunkIndex + pin equality to id on RetrievedChunk [PR1]"
```

---

## Task 3: OpenSearchRetrieverService — populate docId and chunkIndex

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`

Context: `toRetrievedChunk` (currently lines ~253–287) parses `source.get("metadata")` into a `Map<?, ?>` and extracts `KB_ID`/`SECURITY_LEVEL`. We're adding identical extraction for `DOC_ID` and `CHUNK_INDEX` using the same defensive pattern (`instanceof` + non-blank check).

- [ ] **Step 1: Write failing test**

Since `toRetrievedChunk` is `private`, the test uses reflection via `ReflectionTestUtils.invokeMethod`. (Alternatively: promote to package-private for testability — but that's a scope bump; reflection keeps PR1 minimal.)

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenSearchRetrieverServiceTest {

    @Test
    void toRetrievedChunk_shouldExtractDocIdAndChunkIndex() {
        OpenSearchRetrieverService service = newService();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", "kb_xyz");
        metadata.put("security_level", 1);
        metadata.put("doc_id", "doc_abc");
        metadata.put("chunk_index", 12);

        Map<String, Object> source = new HashMap<>();
        source.put("id", "c_1");
        source.put("content", "hello world");
        source.put("metadata", metadata);

        Map<String, Object> hit = new HashMap<>();
        hit.put("_source", source);
        hit.put("_score", 0.85);

        RetrievedChunk chunk = ReflectionTestUtils.invokeMethod(service, "toRetrievedChunk", hit);

        assertThat(chunk).isNotNull();
        assertThat(chunk.getId()).isEqualTo("c_1");
        assertThat(chunk.getDocId()).isEqualTo("doc_abc");
        assertThat(chunk.getChunkIndex()).isEqualTo(12);
        assertThat(chunk.getKbId()).isEqualTo("kb_xyz");
        assertThat(chunk.getSecurityLevel()).isEqualTo(1);
    }

    @Test
    void toRetrievedChunk_shouldTolerateMissingDocIdOrChunkIndex() {
        OpenSearchRetrieverService service = newService();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", "kb_xyz");
        // 故意不放 doc_id / chunk_index — 模拟老数据

        Map<String, Object> source = new HashMap<>();
        source.put("id", "c_1");
        source.put("content", "hello");
        source.put("metadata", metadata);

        Map<String, Object> hit = new HashMap<>();
        hit.put("_source", source);
        hit.put("_score", 0.5);

        RetrievedChunk chunk = ReflectionTestUtils.invokeMethod(service, "toRetrievedChunk", hit);

        assertThat(chunk.getDocId()).isNull();
        assertThat(chunk.getChunkIndex()).isNull();
        assertThat(chunk.getKbId()).isEqualTo("kb_xyz"); // 其他字段不受影响
    }

    @Test
    void toRetrievedChunk_shouldTolerateBlankDocId() {
        OpenSearchRetrieverService service = newService();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", "   "); // 空白字符串当作 null

        Map<String, Object> source = new HashMap<>();
        source.put("id", "c_1");
        source.put("content", "");
        source.put("metadata", metadata);

        Map<String, Object> hit = new HashMap<>();
        hit.put("_source", source);
        hit.put("_score", 0.5);

        RetrievedChunk chunk = ReflectionTestUtils.invokeMethod(service, "toRetrievedChunk", hit);

        assertThat(chunk.getDocId()).isNull();
    }

    /**
     * 构造 service 实例。`toRetrievedChunk` 仅读入参 hit, 不依赖任何实例字段,
     * 因此所有 @RequiredArgsConstructor 生成的 ctor 参数用 mock 即可。
     * 实施者: 读 `OpenSearchRetrieverService.java` 顶部的 @RequiredArgsConstructor 下挂的 `final` 字段清单
     * (约 3-5 个: EmbeddingService / OpenSearchClient / OpenSearchProperties 等),
     * 每个传 `mock(Type.class)` 构造即可。源文件不会有显式 `public OpenSearchRetrieverService(...)` 构造器
     * (由 Lombok 生成), 所以 grep 该字符串不会命中 —— 直接读字段声明区。
     */
    private OpenSearchRetrieverService newService() {
        // 实施者 placeholder: 以实际 final 字段清单替换本段 (约 3-5 行 mock)
        throw new UnsupportedOperationException(
                "Implementer: replace with `new OpenSearchRetrieverService(mock(Dep1.class), mock(Dep2.class), ...)` "
              + "matching the `final` field declarations at the top of OpenSearchRetrieverService.java. "
              + "See KnowledgeDocumentServiceImplTest.setUp for the same @RequiredArgsConstructor + mocks pattern.");
    }
}
```

**Implementer note**: To find the constructor signature, read the top of `OpenSearchRetrieverService.java` — the class uses Lombok `@RequiredArgsConstructor`, so ctor params == `private final` field declarations in order. Pass `mock(Type.class)` for every such field (none are exercised by `toRetrievedChunk`). If any dep has `@NonNull`, a plain Mockito mock satisfies it.

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest -q
```

Expected: `Tests run: 3, Failures: 3` — assertions on `getDocId()` / `getChunkIndex()` fail because extraction isn't implemented yet. (If the `UnsupportedOperationException` placeholder is still there, you'll see 3 errors instead — fix `newService()` first.)

- [ ] **Step 3: Extend `toRetrievedChunk`**

In `OpenSearchRetrieverService.java`, inside `toRetrievedChunk` — specifically the `if (meta instanceof Map<?, ?> metaMap) { ... }` block — add two blocks mirroring the existing `KB_ID` / `SECURITY_LEVEL` extraction:

```java
            String kbId = null;
            Integer securityLevel = null;
            String docId = null;           // NEW
            Integer chunkIndex = null;     // NEW
            if (source != null) {
                Object meta = source.get("metadata");
                if (meta instanceof Map<?, ?> metaMap) {
                    Object kb = metaMap.get(VectorMetadataFields.KB_ID);
                    if (kb != null && !kb.toString().isBlank()) {
                        kbId = kb.toString();
                    }
                    Object sl = metaMap.get(VectorMetadataFields.SECURITY_LEVEL);
                    if (sl instanceof Number n) {
                        securityLevel = n.intValue();
                    }
                    Object did = metaMap.get(VectorMetadataFields.DOC_ID);        // NEW
                    if (did != null && !did.toString().isBlank()) {                 // NEW
                        docId = did.toString();                                     // NEW
                    }                                                               // NEW
                    Object ci = metaMap.get(VectorMetadataFields.CHUNK_INDEX);     // NEW
                    if (ci instanceof Number n) {                                   // NEW
                        chunkIndex = n.intValue();                                  // NEW
                    }                                                               // NEW
                }
            }

            return RetrievedChunk.builder()
                    .id(id)
                    .text(content)
                    .score(score)
                    .kbId(kbId)
                    .securityLevel(securityLevel)
                    .docId(docId)                  // NEW
                    .chunkIndex(chunkIndex)        // NEW
                    .build();
```

(Line numbers may differ from the exploration report — grep `toRetrievedChunk` to locate.)

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest -q
```

Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Spotless + commit**

```bash
mvn -pl bootstrap spotless:check -q
# If fails: mvn -pl bootstrap spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceTest.java
git commit -m "feat(rag): OpenSearch retriever populates docId / chunkIndex [PR1]"
```

---

## Task 4: DocumentMetaSnapshot + KnowledgeDocumentService.findMetaByIds

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dto/DocumentMetaSnapshot.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImplFindMetaTest.java`

Context: `knowledge/dto/` directory does **not** currently exist — create it. The project precedent uses Lombok classes for DTOs (e.g., `rag/core/retrieve/RetrieveRequest`), but Java 17 records are idiomatic for immutable snapshot value types and are used in `framework/security/port/AccessScope`. Records it is.

**Entity field name clarification**: Exploration confirmed `KnowledgeDocumentDO.docName` (Java field), mapped to physical column `doc_name`. The original spec text "DB t_knowledge_document.name" is incorrect — the column is `doc_name`. This plan uses the correct field.

- [ ] **Step 1: Create DocumentMetaSnapshot record**

`bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dto/DocumentMetaSnapshot.java`:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.knowledgebase.ai.ragent.knowledge.dto;

/**
 * 文档元信息快照 (batch 查询返回类型).
 * 用于"回答来源"功能在回答生成时拉取 docId → docName/kbId 映射快照.
 * 快照语义: 答案生成时的 docName / kbId, 后续文档改名 / 删除不影响历史引用展示.
 */
public record DocumentMetaSnapshot(String docId, String docName, String kbId) {
}
```

- [ ] **Step 2: Add method to KnowledgeDocumentService interface**

Add to `KnowledgeDocumentService.java`:

```java
    /**
     * 根据文档 ID 集合批量查询元信息快照.
     * <p>
     * 返回的 snapshot 数量 ≤ 入参数量: 不存在 / 已软删除的 docId 会被自动过滤 (不抛异常).
     * 用于"回答来源"功能在构建 source 卡片时批量拉取 docName / kbId 的 O(1) 写库映射.
     *
     * @param docIds 文档 ID 集合, 允许为空或含不存在 ID
     * @return 可用文档的元信息子集; 空入参返回空列表
     */
    List<com.knowledgebase.ai.ragent.knowledge.dto.DocumentMetaSnapshot> findMetaByIds(
            java.util.Collection<String> docIds);
```

(Keep the FQCNs inline rather than adding top-level imports in a public interface — matches the file's existing spare import style.)

- [ ] **Step 3: Write failing test**

Since the full `KnowledgeDocumentServiceImpl` constructor has ~17 parameters, we follow the same `KnowledgeDocumentServiceImplTest` pattern (direct instantiation with mocks).

`bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImplFindMetaTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.injector.MapperBuilderAssistant;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.knowledgebase.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceImplFindMetaTest {

    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        // CRITICAL: 不初始化 TableInfo, LambdaQueryWrapper 解析 `KnowledgeDocumentDO::getId` 列名时会 NPE —
        // 单测根本进不到 documentMapper mock, 就先在 MP 元信息阶段炸掉. 沿用 KnowledgeDocumentServiceImplTest
        // 的 initTableInfo helper 模式 (同文件夹 KnowledgeDocumentServiceImplTest.java:67 + :142).
        initTableInfo(KnowledgeDocumentDO.class);

        documentMapper = mock(KnowledgeDocumentMapper.class);
        // 实施者: 复用 KnowledgeDocumentServiceImplTest 的 ~17 个 mock 参数构造模式.
        // 本测试只关心 findMetaByIds, 其他依赖传 mock(Type.class) 即可.
        // 如果现有 test 的 setUp 已是 static/package-private helper, 直接复用;
        // 否则就地重复 17 行 mock ctor 调用 —— 不要为此引入新的 test-support 类.
        service = /* new KnowledgeDocumentServiceImpl(... mocks + documentMapper ...) */ null;
        if (service == null) {
            throw new UnsupportedOperationException(
                    "Implementer: replace with actual ctor. Copy the mock set from "
                  + "KnowledgeDocumentServiceImplTest.setUp(); swap in `documentMapper` for the mapper slot.");
        }
    }

    // Copy of the helper at KnowledgeDocumentServiceImplTest.java:142 — keeps this test self-contained.
    // If the two tests converge, extract to a shared test-support class; for now duplicate.
    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    @Test
    void findMetaByIds_shouldReturnOnlyAvailableDocs() {
        KnowledgeDocumentDO doc1 = KnowledgeDocumentDO.builder()
                .id("doc_1").docName("手册 A.pdf").kbId("kb_x").build();
        KnowledgeDocumentDO doc2 = KnowledgeDocumentDO.builder()
                .id("doc_2").docName("考勤办法.docx").kbId("kb_x").build();

        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc1, doc2));

        List<DocumentMetaSnapshot> snapshots =
                service.findMetaByIds(List.of("doc_1", "doc_2", "doc_missing"));

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).extracting(DocumentMetaSnapshot::docId)
                .containsExactlyInAnyOrder("doc_1", "doc_2");
        assertThat(snapshots).extracting(DocumentMetaSnapshot::docName)
                .containsExactlyInAnyOrder("手册 A.pdf", "考勤办法.docx");
    }

    @Test
    void findMetaByIds_shouldReturnEmpty_forEmptyInput() {
        List<DocumentMetaSnapshot> snapshots = service.findMetaByIds(Set.of());
        assertThat(snapshots).isEmpty();
        verifyNoInteractions(documentMapper);
    }

    @Test
    void findMetaByIds_shouldReturnEmpty_forNullInput() {
        List<DocumentMetaSnapshot> snapshots = service.findMetaByIds(null);
        assertThat(snapshots).isEmpty();
        verifyNoInteractions(documentMapper);
    }

    @Test
    void findMetaByIds_shouldQueryMapperOnce() {
        KnowledgeDocumentDO live = KnowledgeDocumentDO.builder()
                .id("doc_1").docName("活").kbId("kb_x").build();
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(live));

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeDocumentDO>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);

        service.findMetaByIds(List.of("doc_1"));

        verify(documentMapper).selectList(captor.capture());
        // @TableLogic 由 MP 自动追加 deleted=0, 不需要显式断言 (CLAUDE.md Gotcha).
        assertThat(captor.getValue()).isNotNull();
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=KnowledgeDocumentServiceImplFindMetaTest -q
```

Expected: `COMPILATION ERROR` — `service.findMetaByIds(...)` not defined. (Or `UnsupportedOperationException` if implementer hasn't filled in `setUp()` yet.)

- [ ] **Step 5: Implement findMetaByIds in KnowledgeDocumentServiceImpl**

Add to `KnowledgeDocumentServiceImpl.java`:

```java
    @Override
    public List<DocumentMetaSnapshot> findMetaByIds(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return List.of();
        }
        // @TableLogic 自动过滤 deleted=0, 无需显式条件 (CLAUDE.md Gotcha).
        List<KnowledgeDocumentDO> rows = documentMapper.selectList(
                Wrappers.<KnowledgeDocumentDO>lambdaQuery()
                        .in(KnowledgeDocumentDO::getId, docIds)
                        .select(
                                KnowledgeDocumentDO::getId,
                                KnowledgeDocumentDO::getDocName,
                                KnowledgeDocumentDO::getKbId));
        return rows.stream()
                .map(d -> new DocumentMetaSnapshot(d.getId(), d.getDocName(), d.getKbId()))
                .collect(Collectors.toList());
    }
```

Add top-level imports as needed:

```java
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.knowledgebase.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import java.util.Collection;
import java.util.stream.Collectors;
```

- [ ] **Step 6: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=KnowledgeDocumentServiceImplFindMetaTest -q
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 7: Spotless + commit**

```bash
mvn -pl bootstrap spotless:check -q
# if fails: mvn -pl bootstrap spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dto/DocumentMetaSnapshot.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImplFindMetaTest.java
git commit -m "feat(knowledge): batch findMetaByIds for answer sources [PR1]"
```

---

## Task 5: Full PR1 build + Spotless + baseline test verification

**Files:** none (validation only)

- [ ] **Step 1: Full clean build**

```bash
mvn clean install -DskipTests -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Full Spotless check**

```bash
mvn spotless:check -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Full test run (baseline failures expected)**

PowerShell:

```powershell
mvn test -q 2>&1 | Select-Object -Last 80
```

Bash (Git Bash on Windows or Linux):

```bash
mvn test -q 2>&1 | tail -80
```

Expected:
- All PR1 new tests pass (`RetrievedChunkTest`, `OpenSearchRetrieverServiceTest`, `KnowledgeDocumentServiceImplFindMetaTest`).
- Only the pre-existing baseline failures fail per CLAUDE.md: `MilvusCollectionTests`, `InvoiceIndexDocumentTests`, `PgVectorStoreServiceTest.testChineseCharacterInsertion`, `IntentTreeServiceTests.initFromFactory`, `VectorTreeIntentClassifierTests` (10 errors total).
- No **new** failures in any other test class — especially nothing in the retrieval / knowledge / framework packages beyond the baseline.

- [ ] **Step 4: Smoke-test OpenSearch end-to-end (manual)**

Requires Docker containers running (see `docs/dev/setup/launch.md`).

PowerShell (primary, matches CLAUDE.md Launch section):

```powershell
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

Then fire a chat request against an indexed KB and inspect logs — `AuthzPostProcessor` should still pass (kbId extraction unchanged); debug-log a `RetrievedChunk` to confirm `docId` / `chunkIndex` are now populated. No frontend change is expected (PR2 scope).

Optional direct OpenSearch hit to verify raw metadata:

PowerShell (uses `curl.exe` explicitly to bypass the `Invoke-WebRequest` alias):

```powershell
$env:NO_PROXY='localhost,127.0.0.1'; curl.exe -s "http://localhost:9201/<collection-name>/_search?pretty" -H "Content-Type: application/json" -d '{\"size\":1,\"_source\":[\"id\",\"metadata\"]}'
```

Bash:

```bash
NO_PROXY=localhost,127.0.0.1 curl -s "http://localhost:9201/<collection-name>/_search?pretty" -H "Content-Type: application/json" -d '{"size":1,"_source":["id","metadata"]}'
```

Confirms `metadata.doc_id` and `metadata.chunk_index` exist in OpenSearch-side. If they don't (old index), re-run ingestion to refresh — PR1 reader assumes write-path already populates these (verified: `OpenSearchVectorStoreService.java` lines 226-227).

- [ ] **Step 5: Push branch**

```bash
git log --oneline origin/main..HEAD
# Should show 5 commits (Task 0 + Tasks 1-4, Task 5 is validation only)
git push -u origin feature/answer-sources-pr1
```

Open PR titled `feat(rag): answer sources PR1 — retrieval metadata plumbing` against `main`. PR description should reference `docs/superpowers/specs/2026-04-17-answer-sources-design.md` and this plan file.

---

## Self-Review Checklist

**Spec coverage** (against PR1 scope: "EnrichedChunk + OpenSearch/Milvus/PG 检索层回填 metadata（新方法） + KnowledgeDocumentService.findMetaByIds + DocumentMetaSnapshot"):

- [x] Metadata回填 OpenSearch — Task 3
- [x] Metadata回填 Milvus — **out of scope for PR1** (decision §2), Task 0 updates source comment to name the new gap
- [x] Metadata回填 PG — **out of scope for PR1** (same), Task 0 updates source comment
- [x] `RagVectorTypeValidator` startup WARN — Task 0 updates message to flag the answer-sources impact
- [x] `KnowledgeDocumentService.findMetaByIds` — Task 4
- [x] `DocumentMetaSnapshot` — Task 4
- [x] `EnrichedChunk` — **replaced with direct-field extension** (decision §1), Task 0 updates spec to match
- [x] `RetrievedChunk` equality pinned to `id` — Task 2 (decision §3), prevents silent `distinct()` drift in `RAGChatServiceImpl:194`
- [x] `VectorMetadataFields` SSOT constants — Task 1 (goes beyond spec's literal scope but is load-bearing for the read-path extension)

**Placeholder scan**: two intentional `UnsupportedOperationException` "implementer fills in" stubs remain — Task 3 `newService()` and Task 4 `setUp()` — because spelling out the real ~17-param constructor in a plan is worse than letting the implementer copy-paste from the existing test. Both stubs clearly direct the implementer to the reusable source. No "TBD" / "TODO" / "similar to Task N" elsewhere.

**Type consistency**: `docId` / `chunkIndex` field names used consistently across RetrievedChunk, OpenSearchRetrieverService, `DocumentMetaSnapshot`, `findMetaByIds`. `DocumentMetaSnapshot` record accessors are `docId()` / `docName()` / `kbId()` (record convention). `KnowledgeDocumentDO.docName` matches (verified in exploration).

**Known plan risks**:
1. **Reflection-based test in Task 3** (`toRetrievedChunk` is private) — implementer may prefer promoting the method to package-private for cleaner test access. Either way works.
2. **Task 4 test boilerplate** — the 17-param `KnowledgeDocumentServiceImpl` constructor means test setup is verbose. The plan intentionally defers this to the implementer (copy from existing `KnowledgeDocumentServiceImplTest`). `initTableInfo(KnowledgeDocumentDO.class)` is now explicit in the setUp scaffold — without it, `LambdaQueryWrapper` column resolution NPEs before reaching the mocked mapper.
3. **Equality-semantic migration** — pinning `RetrievedChunk` equality from accidental all-fields (via `@Data`) to explicit id-only is technically a behavior change, though grep-verified single-touchpoint (`RAGChatServiceImpl.distinct()` at line 194) and domain-aligned. If a future retrieval path ever produces two rows with identical id but different payload, the new behavior dedupes; old behavior kept both. Considered a correctness improvement, not a regression.

---

## PR1 Done-When

- All Task 0–4 commits on branch `feature/answer-sources-pr1`
- `mvn clean install` green
- `mvn spotless:check` green
- All new unit tests pass; no new baseline failures
- Smoke test: OpenSearch retrieval returns `RetrievedChunk` with non-null `docId` / `chunkIndex` (Task 5 Step 4)
- PR opened against `main` with link to spec + this plan
