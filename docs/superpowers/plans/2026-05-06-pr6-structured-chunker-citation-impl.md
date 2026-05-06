# PR 6 (Phase 2.5 / DOCINT-2): Structured Chunker + Retrieval Citation 实施 plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Docling layout output flow end-to-end (chunker → DB → OS → retriever → SourceChunk) so user-visible citations show "第 X 页 / 章节路径"，并锁定下游 Collateral 单问单答 PR 需要的 chunk-level evidence 契约。

**Architecture:** A' chunker abstraction (`IngestionChunkingStrategy.supports(mode, input)` + priority dispatch) + 跨域桥 (`ChunkLayoutMetadata` 在 `rag.core.vector` 守 Map 契约 / `KnowledgeChunkLayoutMapper` 在 `knowledge.service.support` 承担 DO/CreateRequest 桥接) + OS forward-only mapping migration + frontend null-safe rendering。

**Tech Stack:** Java 17, Spring Boot 3.5.7, MyBatis Plus 3.5.14, Lombok, Jackson, JUnit 5, AssertJ, Mockito, OpenSearch 2.18, React 18 + TypeScript + TailwindCSS。

**Spec 锚点：** [`docs/superpowers/specs/2026-05-06-pr6-structured-chunker-citation-design.md`](../specs/2026-05-06-pr6-structured-chunker-citation-design.md)（v3, 1205 行；含 9 个 Q 决策 + 12 处 plan deviation）。

---

## File Structure

### 新建（11 个 main 类 + 11 个测试类）

| 文件 | 包 | 职责 |
|---|---|---|
| `IngestionChunkingStrategy.java` | `bootstrap/.../ingestion/chunker` | A' 接口 |
| `IngestionChunkingInput.java` | `bootstrap/.../ingestion/chunker` | record 输入模型 |
| `StructuredChunkingStrategy.java` | `bootstrap/.../ingestion/chunker` | priority=100 strategy |
| `LegacyTextChunkingStrategyAdapter.java` | `bootstrap/.../ingestion/chunker` | priority=10 adapter |
| `IngestionChunkingDispatcher.java` | `bootstrap/.../ingestion/chunker` | priority dispatch + 同优先级 fail-fast |
| `StructuredChunkingOptionsResolver.java` | `bootstrap/.../ingestion/chunker`（package-private）| 把异构 ChunkingOptions 收口 |
| `StructuredChunkingDimensions.java` | `bootstrap/.../ingestion/chunker`（package-private record）| target/max/min 三维度 |
| `IngestionChunkingConfig.java` | `bootstrap/.../ingestion/chunker.config` | 注册 2 个 LegacyAdapter bean |
| `ChunkLayoutMetadata.java` | `bootstrap/.../rag/core/vector` | reader/writer/Builder（**仅 Map 操作，零 knowledge / parser 域依赖**）|
| `KnowledgeChunkLayoutMapper.java` | `bootstrap/.../knowledge/service/support` | DO ↔ VectorChunk + VectorChunk ↔ CreateRequest 双向桥 |
| 11 个测试类 | 见各 Task | 单元 + 集成 |

### 修改（13 个文件）

| 文件 | 改动摘要 |
|---|---|
| `IngestionContext.java` | 加 `parseResult: ParseResult` 字段 |
| `ParserNode.java` | parse 后写 `setParseResult(result)` |
| `ChunkerNode.java` | rewire 走 `IngestionChunkingDispatcher` |
| `KnowledgeChunkCreateRequest.java` | 加 9 个 nullable layout 字段 |
| `KnowledgeDocumentServiceImpl.java` | stream map 处加 `layoutMapper.copyToCreateRequest(vc, req)` 一行；注入 `KnowledgeChunkLayoutMapper` |
| `KnowledgeChunkServiceImpl.java` | (a) `batchCreate` DO 构造加 9 字段拷贝；(b) 5 处 `VectorChunk.builder()` 调用点紧跟 `layoutMapper.copyFromDO(do, vc)`；(c) `update` 清 4 个 extraction 字段 |
| `OpenSearchVectorStoreAdmin.java` | metadata mapping 加 9 layout 字段类型声明 |
| `RetrievedChunk.java` | 加 6 个 nullable 字段 |
| `OpenSearchRetrieverService.java` | `toRetrievedChunk` 抽 6 layout 字段 |
| `SourceChunk.java` | 加 6 个 nullable 字段 + 类级 `@JsonInclude(JsonInclude.Include.NON_EMPTY)`（v4 P2 #5） |
| `SourceCardBuilder.java` | RetrievedChunk → SourceChunk 时映射 6 字段 |
| `frontend/src/types/index.ts` | SourceChunk interface 加 6 个 `?: T \| null` 字段 |
| `frontend/src/components/chat/Sources.tsx` | 卡片渲染 evidence 行（`!= null` 守卫）|

### 文档（3 个）

| 文件 | 改动摘要 |
|---|---|
| `docs/dev/setup/docling-service.md` | 加"已存在 KB 切 ENHANCED 的迁移步骤" |
| `docs/dev/followup/backlog.md` | 加 `PARSER-PAGE-PERSIST / PARSER-OCR-PIPELINE / PARSER-TEXT-LAYER-V0.6+ / PARSER-ENHANCER-LAYOUT-ALIGN` 4 条 |
| `docs/dev/gotchas.md` §8 | 加"PR 6 起 layout 字段双路径写入"规则 + re-index 必经 `KnowledgeChunkLayoutMapper.copyFromDO` |

---

## Phase 1: Vector Core Foundation

### Task 1: `ChunkLayoutMetadata` reader / writer / Builder

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/ChunkLayoutMetadata.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/vector/ChunkLayoutMetadataTest.java`

- [ ] **Step 1: Write failing test (3 contracts)**

```java
package com.knowledgebase.ai.ragent.rag.core.vector;

import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkLayoutMetadataTest {

    @Test
    void readback_threeShapesAllResolveToList() {
        VectorChunk a = new VectorChunk(); a.setMetadata(new HashMap<>(Map.of(
                VectorMetadataFields.HEADING_PATH, List.of("第三章", "3.2 信用风险"))));
        VectorChunk b = new VectorChunk(); b.setMetadata(new HashMap<>(Map.of(
                VectorMetadataFields.HEADING_PATH, new String[]{"第三章", "3.2 信用风险"})));
        VectorChunk c = new VectorChunk(); c.setMetadata(new HashMap<>(Map.of(
                VectorMetadataFields.HEADING_PATH, "[\"第三章\",\"3.2 信用风险\"]")));

        assertThat(ChunkLayoutMetadata.headingPath(a)).containsExactly("第三章", "3.2 信用风险");
        assertThat(ChunkLayoutMetadata.headingPath(b)).containsExactly("第三章", "3.2 信用风险");
        assertThat(ChunkLayoutMetadata.headingPath(c)).containsExactly("第三章", "3.2 信用风险");
    }

    @Test
    void readback_missingKeyReturnsNull_notEmpty() {
        // v4 review P2 #5：缺 key 返 null（不是空 List），保 SourceChunk 序列化对称
        VectorChunk vc = new VectorChunk(); vc.setMetadata(new HashMap<>());
        assertThat(ChunkLayoutMetadata.headingPath(vc)).isNull();
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).isNull();
    }

    @Test
    void writer_skipsNullAndEmpty() {
        VectorChunk vc = new VectorChunk(); vc.setMetadata(new HashMap<>());
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(null)
                .headingPath(List.of())
                .blockType(null)
                .pageRange(null, null);
        assertThat(vc.getMetadata()).isEmpty();
    }

    @Test
    void writer_putsValueWhenPresent() {
        VectorChunk vc = new VectorChunk(); vc.setMetadata(new HashMap<>());
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(12)
                .pageRange(12, 13)
                .headingPath(List.of("第三章", "3.2"))
                .blockType("PARAGRAPH");
        assertThat(vc.getMetadata())
                .containsEntry(VectorMetadataFields.PAGE_NUMBER, 12)
                .containsEntry(VectorMetadataFields.PAGE_START, 12)
                .containsEntry(VectorMetadataFields.PAGE_END, 13)
                .containsEntry(VectorMetadataFields.BLOCK_TYPE, "PARAGRAPH")
                .containsEntry(VectorMetadataFields.HEADING_PATH, List.of("第三章", "3.2"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=ChunkLayoutMetadataTest
```
Expected: FAIL — `cannot find symbol class ChunkLayoutMetadata`.

- [ ] **Step 3: Implement `ChunkLayoutMetadata`**

```java
package com.knowledgebase.ai.ragent.rag.core.vector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * VectorChunk.metadata 上 layout 字段的类型化访问层。
 *
 * <p>边界（v3）：仅做 Map 内 type access。<b>不依赖任何 knowledge / parser 域类型</b>。
 * Reader 三态兼容（List / String[] / JSON String），Writer 跳过 null/empty。
 *
 * <p>DO ↔ VectorChunk 与 CreateRequest ↔ VectorChunk 的桥接交给
 * {@code knowledge.service.support.KnowledgeChunkLayoutMapper}，<b>不在本类</b>。
 */
@Slf4j
public final class ChunkLayoutMetadata {

    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private ChunkLayoutMetadata() {}

    // ============ Reader ============

    public static Integer pageNumber(VectorChunk c)         { return readInt(c, VectorMetadataFields.PAGE_NUMBER); }
    public static Integer pageStart(VectorChunk c)          { return readInt(c, VectorMetadataFields.PAGE_START); }
    public static Integer pageEnd(VectorChunk c)            { return readInt(c, VectorMetadataFields.PAGE_END); }
    public static List<String> headingPath(VectorChunk c)   { return readStringList(c, VectorMetadataFields.HEADING_PATH); }
    public static String blockType(VectorChunk c)           { return readString(c, VectorMetadataFields.BLOCK_TYPE); }
    public static List<String> sourceBlockIds(VectorChunk c){ return readStringList(c, VectorMetadataFields.SOURCE_BLOCK_IDS); }
    public static String bboxRefs(VectorChunk c)            { return readString(c, VectorMetadataFields.BBOX_REFS); }
    public static String textLayerType(VectorChunk c)       { return readString(c, VectorMetadataFields.TEXT_LAYER_TYPE); }
    public static Double layoutConfidence(VectorChunk c)    { return readDouble(c, VectorMetadataFields.LAYOUT_CONFIDENCE); }

    private static Integer readInt(VectorChunk c, String key) {
        Object v = read(c, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Double readDouble(VectorChunk c, String key) {
        Object v = read(c, key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String readString(VectorChunk c, String key) {
        Object v = read(c, key);
        return v == null ? null : v.toString();
    }

    /**
     * 三态兼容：List / String[] / JSON String 兜底。
     * <p><b>v4 review P2 #5</b>：返回 {@code null}（不是空 List）来表达 "key 缺失"，
     * 让 BASIC chunk 的 SourceChunk 序列化与 {@code @JsonInclude(NON_EMPTY)} 对齐。
     */
    private static List<String> readStringList(VectorChunk c, String key) {
        Object v = read(c, key);
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<String> result = list.stream().filter(Objects::nonNull).map(Object::toString).toList();
            return result.isEmpty() ? null : result;
        }
        if (v instanceof String[] arr) {
            List<String> result = Arrays.stream(arr).filter(Objects::nonNull).toList();
            return result.isEmpty() ? null : result;
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                List<String> parsed = SHARED_MAPPER.readValue(s, STRING_LIST);
                return parsed == null || parsed.isEmpty() ? null : parsed;
            } catch (Exception ignored) {
                return List.of(s);   // 单值 String 兜底（视为非空）
            }
        }
        return null;
    }

    private static Object read(VectorChunk c, String key) {
        if (c == null || c.getMetadata() == null) return null;
        return c.getMetadata().get(key);
    }

    // ============ Writer Builder ============

    public static Builder writer(VectorChunk c) {
        if (c.getMetadata() == null) c.setMetadata(new HashMap<>());
        return new Builder(c.getMetadata());
    }

    public static final class Builder {
        private final Map<String, Object> meta;
        Builder(Map<String, Object> meta) { this.meta = meta; }

        public Builder pageNumber(Integer v)             { return putIfPresent(VectorMetadataFields.PAGE_NUMBER, v); }
        public Builder pageRange(Integer start, Integer end) {
            putIfPresent(VectorMetadataFields.PAGE_START, start);
            putIfPresent(VectorMetadataFields.PAGE_END, end);
            return this;
        }
        public Builder headingPath(List<String> path) {
            if (path != null && !path.isEmpty()) meta.put(VectorMetadataFields.HEADING_PATH, path);
            return this;
        }
        /** writer 对 BlockType 解耦：调用方传 BlockType.name() String，避免反向 import core.parser.layout.BlockType */
        public Builder blockType(String name) {
            if (name != null && !name.isBlank()) meta.put(VectorMetadataFields.BLOCK_TYPE, name);
            return this;
        }
        public Builder sourceBlockIds(List<String> ids) {
            if (ids != null && !ids.isEmpty()) meta.put(VectorMetadataFields.SOURCE_BLOCK_IDS, ids);
            return this;
        }
        public Builder bboxRefs(String json) {
            if (json != null && !json.isBlank() && !"[]".equals(json))
                meta.put(VectorMetadataFields.BBOX_REFS, json);
            return this;
        }
        public Builder textLayerType(String tlt) {
            if (tlt != null && !tlt.isBlank()) meta.put(VectorMetadataFields.TEXT_LAYER_TYPE, tlt);
            return this;
        }
        public Builder layoutConfidence(Double conf) {
            if (conf != null) meta.put(VectorMetadataFields.LAYOUT_CONFIDENCE, conf);
            return this;
        }

        private Builder putIfPresent(String key, Object value) {
            if (value != null) meta.put(key, value);
            return this;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=ChunkLayoutMetadataTest
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/ChunkLayoutMetadata.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/vector/ChunkLayoutMetadataTest.java
git commit -m "feat(rag-vector): ChunkLayoutMetadata typed accessor (PR 6 / Q6)"
```

---

## Phase 2: Ingestion Context + Chunker Abstraction

### Task 2: `IngestionContext.parseResult` + `ParserNode` 写入

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/context/IngestionContext.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNodeParseResultPropagationTest.java`

- [ ] **Step 1: Write failing test**

注意真实 API：`ParserNode(ObjectMapper, DocumentParserSelector)` 双参 ctor；`NodeConfig.settings` 是 `JsonNode`（不是 `Map<String, Object>`）；`NodeResult` 在 `ingestion.domain.result.NodeResult`。

```java
package com.knowledgebase.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.parser.DocumentParser;
import com.knowledgebase.ai.ragent.core.parser.DocumentParserSelector;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserNodeParseResultPropagationTest {

    @Test
    void parserNode_putsFullParseResultOnContext_andStillSetsRawText() {
        DocumentParser parser = mock(DocumentParser.class);
        ParseResult full = new ParseResult(
                "page1 text", Map.of(),
                List.of(new DocumentPageText(null, 1, "page1 text", null, null, List.of())),
                List.of());
        when(parser.parse(any(), any(), any())).thenReturn(full);

        DocumentParserSelector selector = mock(DocumentParserSelector.class);
        when(selector.selectByParseMode(ParseMode.BASIC)).thenReturn(parser);

        ObjectMapper objectMapper = new ObjectMapper();
        ParserNode node = new ParserNode(objectMapper, selector);   // ← 双参 ctor

        IngestionContext ctx = IngestionContext.builder()
                .rawBytes("hello".getBytes())
                .mimeType("text/plain")
                .parseMode(ParseMode.BASIC.getValue())
                .build();
        // NodeConfig.settings 是 JsonNode；用 objectMapper 构造一个空对象节点
        NodeConfig config = NodeConfig.builder()
                .settings(objectMapper.createObjectNode())
                .build();

        node.execute(ctx, config);

        assertThat(ctx.getParseResult()).isSameAs(full);
        assertThat(ctx.getRawText()).isEqualTo("page1 text");
        assertThat(ctx.getParseResult().pages()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=ParserNodeParseResultPropagationTest
```
Expected: FAIL — `setParseResult` not on IngestionContext, or compile error.

- [ ] **Step 3: Add `parseResult` field to `IngestionContext.java`**

In `IngestionContext.java`, add after the `private String parseMode;` field:

```java
    /**
     * 完整 ParseResult，含 pages / tables 等 layout 信息。
     *
     * <p>SoT 约定（PR 6 起）：
     * <ul>
     *   <li>ParserNode 是唯一写入 parseResult 的节点</li>
     *   <li>rawText 是 parseResult.text() 的派生兼容字段，ParserNode 内保持二者同步</li>
     *   <li>enhancedText 是独立派生文本（EnhancerNode 写），不回写 parseResult</li>
     *   <li>layout / table 新代码只读 parseResult.pages() / parseResult.tables()</li>
     * </ul>
     */
    private com.knowledgebase.ai.ragent.core.parser.ParseResult parseResult;
```

- [ ] **Step 4: Modify `ParserNode.execute` to write parseResult**

In `ParserNode.execute(...)`, replace the existing parsing block (currently writing `setRawText` + `setDocument`) with the version that **also** writes `setParseResult`:

```java
        ParseResult result = parser.parse(context.getRawBytes(), mimeType, options);
        context.setParseResult(result);                     // PR 6: SoT for layout
        context.setRawText(result.text());                  // 派生兼容（保留）
        StructuredDocument document = StructuredDocument.builder()
                .text(result.text())
                .metadata(result.metadata())
                .build();
        context.setDocument(document);                      // legacy 兼容（保留）
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=ParserNodeParseResultPropagationTest
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/context/IngestionContext.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNodeParseResultPropagationTest.java
git commit -m "feat(ingestion): IngestionContext.parseResult + ParserNode writes it (PR 6 / Q3)"
```

---

### Task 3: `IngestionChunkingInput` record

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingInput.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingInputTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionChunkingInputTest {

    @Test
    void from_prefersEnhancedTextOverRawText() {
        IngestionContext ctx = IngestionContext.builder()
                .rawText("raw")
                .enhancedText("enhanced")
                .parseMode(ParseMode.ENHANCED.getValue())
                .build();
        IngestionChunkingInput input = IngestionChunkingInput.from(ctx);
        assertThat(input.text()).isEqualTo("enhanced");
        assertThat(input.parseMode()).isEqualTo(ParseMode.ENHANCED);
    }

    @Test
    void from_fallsBackToRawTextWhenEnhancedBlank() {
        IngestionContext ctx = IngestionContext.builder()
                .rawText("raw")
                .enhancedText("")
                .parseMode(null)
                .build();
        IngestionChunkingInput input = IngestionChunkingInput.from(ctx);
        assertThat(input.text()).isEqualTo("raw");
        assertThat(input.parseMode()).isEqualTo(ParseMode.BASIC);  // null → BASIC per ParseMode.fromValue
    }

    @Test
    void from_carriesParseResult() {
        ParseResult pr = new ParseResult("t", Map.of(), List.of(), List.of());
        IngestionContext ctx = IngestionContext.builder().parseResult(pr).rawText("t").build();
        IngestionChunkingInput input = IngestionChunkingInput.from(ctx);
        assertThat(input.parseResult()).isSameAs(pr);
    }

    @Test
    void compactCtor_nullMetadataBecomesEmptyMap() {
        IngestionChunkingInput input = new IngestionChunkingInput("t", null, ParseMode.BASIC, null);
        assertThat(input.metadata()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=IngestionChunkingInputTest
```
Expected: FAIL — class not found.

- [ ] **Step 3: Implement `IngestionChunkingInput`**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Chunker 阶段的统一输入模型。屏蔽 IngestionContext 的具体形态，让
 * {@link IngestionChunkingStrategy} 不直接依赖 ingestion domain 的 god object。
 *
 * <ul>
 *   <li>{@code text}：legacy adapter 用，取值语义 enhancedText &gt; rawText</li>
 *   <li>{@code parseResult}：SoT for layout，structured strategy 消费</li>
 *   <li>{@code parseMode}：用户意图 BASIC / ENHANCED（信号性）</li>
 *   <li>{@code metadata}：ingestion-level metadata，可空</li>
 * </ul>
 */
public record IngestionChunkingInput(
        String text,
        ParseResult parseResult,
        ParseMode parseMode,
        Map<String, Object> metadata) {

    public IngestionChunkingInput {
        if (metadata == null) metadata = Map.of();
    }

    public static IngestionChunkingInput from(IngestionContext context) {
        String text = StringUtils.hasText(context.getEnhancedText())
                ? context.getEnhancedText()
                : context.getRawText();
        ParseMode mode = ParseMode.fromValue(context.getParseMode());
        return new IngestionChunkingInput(
                text == null ? "" : text,
                context.getParseResult(),
                mode,
                context.getMetadata());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=IngestionChunkingInputTest
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingInput.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingInputTest.java
git commit -m "feat(ingestion-chunker): IngestionChunkingInput record (PR 6 / A')"
```

---

### Task 4: `StructuredChunkingOptionsResolver` + `StructuredChunkingDimensions`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingDimensions.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingOptionsResolver.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingOptionsResolverTest.java`

- [ ] **Step 1: Write failing test**

注意真实 API：`FixedSizeOptions` 在 `com.knowledgebase.ai.ragent.core.chunk`（**不是 .option 子包**），是 **record**（非 Lombok @Builder）；用 `new FixedSizeOptions(int chunkSize, int overlapSize)` 构造；accessor 是 `chunkSize()` 等 record-style（非 `getChunkSize()`）。`TextBoundaryOptions` 同包，4 个 int 字段构造：`new TextBoundaryOptions(int targetChars, int overlapChars, int maxChars, int minChars)`。

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.FixedSizeOptions;
import com.knowledgebase.ai.ragent.core.chunk.TextBoundaryOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredChunkingOptionsResolverTest {

    @Test
    void textBoundaryOptions_passesThroughTargetMaxMin() {
        TextBoundaryOptions opt = new TextBoundaryOptions(1000, 100, 1500, 400);
        StructuredChunkingDimensions d = StructuredChunkingOptionsResolver.resolve(opt);
        assertThat(d.target()).isEqualTo(1000);
        assertThat(d.max()).isEqualTo(1500);
        assertThat(d.min()).isEqualTo(400);
    }

    @Test
    void fixedSizeOptions_derivesMaxAndMin() {
        FixedSizeOptions opt = new FixedSizeOptions(1000, 0);
        StructuredChunkingDimensions d = StructuredChunkingOptionsResolver.resolve(opt);
        assertThat(d.target()).isEqualTo(1000);
        assertThat(d.max()).isEqualTo(1300);   // 30% 弹性
        assertThat(d.min()).isEqualTo(400);    // 40% 下限
    }

    @Test
    void nullOrUnknown_fallsBackToStructuredDefaults() {
        StructuredChunkingDimensions d = StructuredChunkingOptionsResolver.resolve(null);
        assertThat(d.target()).isEqualTo(1400);
        assertThat(d.max()).isEqualTo(1800);
        assertThat(d.min()).isEqualTo(600);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=StructuredChunkingOptionsResolverTest
```
Expected: FAIL — class not found.

- [ ] **Step 3: Implement record + resolver**

`StructuredChunkingDimensions.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

/** target / max / min 三维度，由 {@link StructuredChunkingOptionsResolver} 产出。 */
record StructuredChunkingDimensions(int target, int max, int min) {}
```

`StructuredChunkingOptionsResolver.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.FixedSizeOptions;
import com.knowledgebase.ai.ragent.core.chunk.TextBoundaryOptions;

/**
 * 把异构 ChunkingOptions 收口为 {@link StructuredChunkingDimensions}，让
 * {@link StructuredChunkingStrategy} 不直接耦合 TextBoundaryOptions / FixedSizeOptions。
 *
 * <p><b>真实 API 注意</b>：FixedSizeOptions / TextBoundaryOptions 都是 records，accessor
 * 是 record-style（{@code tb.targetChars()} 而非 {@code tb.getTargetChars()}）。
 */
final class StructuredChunkingOptionsResolver {

    private StructuredChunkingOptionsResolver() {}

    static StructuredChunkingDimensions resolve(ChunkingOptions options) {
        if (options instanceof TextBoundaryOptions tb) {
            return new StructuredChunkingDimensions(tb.targetChars(), tb.maxChars(), tb.minChars());
        }
        if (options instanceof FixedSizeOptions fs) {
            int target = fs.chunkSize();
            int max = (int) Math.ceil(target * 1.3);
            int min = Math.max(1, (int) (target * 0.4));
            return new StructuredChunkingDimensions(target, max, min);
        }
        return new StructuredChunkingDimensions(1400, 1800, 600);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=StructuredChunkingOptionsResolverTest
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingDimensions.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingOptionsResolver.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingOptionsResolverTest.java
git commit -m "feat(ingestion-chunker): StructuredChunkingOptionsResolver (PR 6 / Q5 helper)"
```

---

### Task 5: `IngestionChunkingStrategy` interface

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingStrategy.java`

- [ ] **Step 1: Implement interface (no test — interface only)**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;

import java.util.List;

/**
 * A' chunker 抽象（PR 6 / Q5）：capability-based dispatch + priority。
 *
 * <p>{@link #supports(ChunkingMode, IngestionChunkingInput)} 不依赖 settings.strategy 一个轴，
 * structured strategy 看 layout 可用性、legacy adapter 看 mode 严格匹配。
 *
 * <p>{@link #priority()} 决定 dispatch 时的优先顺序：StructuredChunkingStrategy = 100，
 * LegacyTextChunkingStrategyAdapter = 10。同优先级多命中由
 * {@link IngestionChunkingDispatcher} fail-fast。
 */
public interface IngestionChunkingStrategy {

    /** Higher value tried first in dispatch. */
    int priority();

    /** 是否能处理当前 (mode, input) 组合。MUST be cheap — 每文档调一次。 */
    boolean supports(ChunkingMode mode, IngestionChunkingInput input);

    /**
     * Chunk the input. layout 字段（如果有）由实现通过
     * {@code ChunkLayoutMetadata.writer(...)} 写入返回 chunk 的 metadata Map。
     */
    List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options);
}
```

- [ ] **Step 2: Verify compile**

```bash
mvn -pl bootstrap compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingStrategy.java
git commit -m "feat(ingestion-chunker): IngestionChunkingStrategy A' interface (PR 6 / Q5)"
```

---

### Task 6: `LegacyTextChunkingStrategyAdapter` + Spring config

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/LegacyTextChunkingStrategyAdapter.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/config/IngestionChunkingConfig.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/LegacyTextChunkingStrategyAdapterTest.java`

- [ ] **Step 1: Write failing test (双契约：mode 严格匹配 + 不写 layout 字段)**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingStrategy;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LegacyTextChunkingStrategyAdapterTest {

    @Test
    void supports_strictModeMatchOnly() {
        ChunkingStrategy delegate = mock(ChunkingStrategy.class);
        LegacyTextChunkingStrategyAdapter adapter =
                new LegacyTextChunkingStrategyAdapter(ChunkingMode.STRUCTURE_AWARE, delegate);

        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());
        assertThat(adapter.supports(ChunkingMode.STRUCTURE_AWARE, in)).isTrue();
        assertThat(adapter.supports(ChunkingMode.FIXED_SIZE, in)).isFalse();
        assertThat(adapter.priority()).isEqualTo(10);
    }

    @Test
    void chunk_doesNotWriteAnyLayoutField() {
        ChunkingStrategy delegate = mock(ChunkingStrategy.class);
        VectorChunk delegateOutput = VectorChunk.builder()
                .chunkId("c1").index(0).content("hello").metadata(new HashMap<>()).build();
        when(delegate.chunk(eq("hello"), any())).thenReturn(List.of(delegateOutput));

        LegacyTextChunkingStrategyAdapter adapter =
                new LegacyTextChunkingStrategyAdapter(ChunkingMode.STRUCTURE_AWARE, delegate);
        IngestionChunkingInput in = new IngestionChunkingInput("hello", null, ParseMode.BASIC, Map.of());

        List<VectorChunk> out = adapter.chunk(in, mock(ChunkingOptions.class));

        assertThat(out).hasSize(1);
        Map<String, Object> meta = out.get(0).getMetadata();
        // 关键回归：legacy 路径不能写任何 layout key
        assertThat(meta).doesNotContainKeys(
                VectorMetadataFields.PAGE_NUMBER, VectorMetadataFields.PAGE_START,
                VectorMetadataFields.PAGE_END, VectorMetadataFields.HEADING_PATH,
                VectorMetadataFields.BLOCK_TYPE, VectorMetadataFields.SOURCE_BLOCK_IDS,
                VectorMetadataFields.BBOX_REFS, VectorMetadataFields.TEXT_LAYER_TYPE,
                VectorMetadataFields.LAYOUT_CONFIDENCE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=LegacyTextChunkingStrategyAdapterTest
```
Expected: FAIL — class not found.

- [ ] **Step 3: Implement adapter + Spring config**

`LegacyTextChunkingStrategyAdapter.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingStrategy;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;

import java.util.List;

/**
 * 包装老 {@link ChunkingStrategy}（FixedSizeTextChunker / StructureAwareTextChunker），
 * 使其符合 A' 接口。priority=10。
 *
 * <p><b>关键契约</b>：本 adapter 绝不写任何 layout 字段到 VectorChunk.metadata —— layout
 * 写入唯一入口是 {@link StructuredChunkingStrategy}。这条保证 BASIC byte-equivalent 不破。
 */
public class LegacyTextChunkingStrategyAdapter implements IngestionChunkingStrategy {

    private final ChunkingMode supportedMode;
    private final ChunkingStrategy delegate;

    public LegacyTextChunkingStrategyAdapter(ChunkingMode supportedMode, ChunkingStrategy delegate) {
        this.supportedMode = supportedMode;
        this.delegate = delegate;
    }

    @Override public int priority() { return 10; }

    @Override
    public boolean supports(ChunkingMode mode, IngestionChunkingInput input) {
        return this.supportedMode == mode;
    }

    @Override
    public List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options) {
        return delegate.chunk(input.text(), options);
    }
}
```

`IngestionChunkingConfig.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker.config;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.strategy.FixedSizeTextChunker;
import com.knowledgebase.ai.ragent.core.chunk.strategy.StructureAwareTextChunker;
import com.knowledgebase.ai.ragent.ingestion.chunker.LegacyTextChunkingStrategyAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册两个 LegacyAdapter bean，每个对应一个 {@link ChunkingMode}. */
@Configuration
public class IngestionChunkingConfig {

    @Bean
    public LegacyTextChunkingStrategyAdapter legacyFixedSizeAdapter(FixedSizeTextChunker delegate) {
        return new LegacyTextChunkingStrategyAdapter(ChunkingMode.FIXED_SIZE, delegate);
    }

    @Bean
    public LegacyTextChunkingStrategyAdapter legacyStructureAwareAdapter(StructureAwareTextChunker delegate) {
        return new LegacyTextChunkingStrategyAdapter(ChunkingMode.STRUCTURE_AWARE, delegate);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=LegacyTextChunkingStrategyAdapterTest
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/LegacyTextChunkingStrategyAdapter.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/config/IngestionChunkingConfig.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/LegacyTextChunkingStrategyAdapterTest.java
git commit -m "feat(ingestion-chunker): LegacyTextChunkingStrategyAdapter + config (PR 6 / Q9 contract)"
```

---

### Task 7: `StructuredChunkingStrategy`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategyTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredChunkingStrategyTest {

    private final StructuredChunkingStrategy strategy = new StructuredChunkingStrategy();

    @Test
    void priority_is100() {
        assertThat(strategy.priority()).isEqualTo(100);
    }

    @Test
    void supports_falseWhenParseResultNull() {
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.ENHANCED, Map.of());
        assertThat(strategy.supports(ChunkingMode.STRUCTURE_AWARE, in)).isFalse();
    }

    @Test
    void supports_falseWhenPagesAndTablesEmpty() {
        ParseResult pr = new ParseResult("t", Map.of(), List.of(), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("t", pr, ParseMode.ENHANCED, Map.of());
        assertThat(strategy.supports(ChunkingMode.STRUCTURE_AWARE, in)).isFalse();
    }

    @Test
    void supports_trueWhenPagesNonEmpty_regardlessOfMode() {
        DocumentPageText page = new DocumentPageText(null, 1, "hello", null, null, List.of());
        ParseResult pr = new ParseResult("hello", Map.of(), List.of(page), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("hello", pr, ParseMode.ENHANCED, Map.of());

        // 任意 ChunkingMode 都返 true
        assertThat(strategy.supports(ChunkingMode.STRUCTURE_AWARE, in)).isTrue();
        assertThat(strategy.supports(ChunkingMode.FIXED_SIZE, in)).isTrue();
    }

    @Test
    void chunk_titleBoundarySplit_keepsHeadingPath() {
        List<LayoutBlock> blocks = List.of(
                new LayoutBlock("b1", BlockType.TITLE, 1, null, "Chapter 1", 1, 0.99D, 1, List.of()),
                new LayoutBlock("b2", BlockType.PARAGRAPH, 1, null, "Para A", 2, 0.98D, null, List.of("Chapter 1")));
        DocumentPageText page = new DocumentPageText(null, 1, "Chapter 1\nPara A", "NATIVE_TEXT", 0.98D, blocks);
        ParseResult pr = new ParseResult("Chapter 1\nPara A", Map.of(), List.of(page), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("Chapter 1\nPara A", pr, ParseMode.ENHANCED, Map.of());

        List<VectorChunk> out = strategy.chunk(in, null);

        assertThat(out).hasSize(1);
        VectorChunk vc = out.get(0);
        assertThat(ChunkLayoutMetadata.headingPath(vc)).containsExactly("Chapter 1");
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(1);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(1);
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo(BlockType.PARAGRAPH.name());
    }

    @Test
    void chunk_tableIsAtomic_singleChunk() {
        LayoutTable table = new LayoutTable("t1", 3, null,
                List.of(List.of("h1", "h2"), List.of("a", "b")),
                List.of("Section 5"), 1, 0.99D);
        ParseResult pr = new ParseResult("", Map.of(), List.of(), List.of(table));
        IngestionChunkingInput in = new IngestionChunkingInput("", pr, ParseMode.ENHANCED, Map.of());

        List<VectorChunk> out = strategy.chunk(in, null);

        assertThat(out).hasSize(1);
        VectorChunk vc = out.get(0);
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo(BlockType.TABLE.name());
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(3);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(3);
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).containsExactly("t1");
    }

    @Test
    void chunk_v051Limits_textLayerTypeAndConfidenceStayNull() {
        // v0.5.1 Adapter 给页 / 块的 textLayerType / confidence 都填 null —— Q7 已锁
        DocumentPageText page = new DocumentPageText(null, 1, "x",
                /*textLayerType*/ null, /*confidence*/ null,
                List.of(new LayoutBlock("b1", BlockType.PARAGRAPH, 1, null, "x", 1, /*confidence*/ null, null, List.of())));
        ParseResult pr = new ParseResult("x", Map.of(), List.of(page), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("x", pr, ParseMode.ENHANCED, Map.of());

        List<VectorChunk> out = strategy.chunk(in, null);

        assertThat(out).hasSize(1);
        Map<String, Object> meta = out.get(0).getMetadata();
        assertThat(meta).doesNotContainKey(VectorMetadataFields.TEXT_LAYER_TYPE);
        assertThat(meta).doesNotContainKey(VectorMetadataFields.LAYOUT_CONFIDENCE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=StructuredChunkingStrategyTest
```
Expected: FAIL — class not found.

- [ ] **Step 3: Implement strategy**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Layout-aware structured chunker（PR 6 / Q5）。
 *
 * <p>priority=100；{@link #supports(ChunkingMode, IngestionChunkingInput)} 不依赖 mode 参数 ——
 * layout 可用性是客观判断，mode 只在 layout 不可用时引导 fallback 选择 legacy chunker。
 */
@Component
public class StructuredChunkingStrategy implements IngestionChunkingStrategy {

    @Override public int priority() { return 100; }

    @Override
    public boolean supports(ChunkingMode mode, IngestionChunkingInput input) {
        ParseResult pr = input.parseResult();
        if (pr == null) return false;
        return !pr.pages().isEmpty() || !pr.tables().isEmpty();
    }

    @Override
    public List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options) {
        StructuredChunkingDimensions dims = StructuredChunkingOptionsResolver.resolve(options);
        ParseResult pr = input.parseResult();
        List<VectorChunk> out = new ArrayList<>();

        // 1. tables：每个表格一个 chunk，原子不切
        for (LayoutTable t : pr.tables()) {
            VectorChunk vc = newChunk(serializeTable(t), out.size());
            ChunkLayoutMetadata.writer(vc)
                    .pageNumber(t.pageNo())
                    .pageRange(t.pageNo(), t.pageNo())
                    .headingPath(t.headingPath())
                    .blockType(BlockType.TABLE.name())
                    .sourceBlockIds(t.tableId() == null ? List.of() : List.of(t.tableId()))
                    .bboxRefs(bboxRefsJson(t.tableId(), t.pageNo(), t.bbox()))
                    .textLayerType(textLayerTypeForRange(pr.pages(), t.pageNo(), t.pageNo()))
                    .layoutConfidence(t.confidence());
            out.add(vc);
        }

        // 2. body blocks：按 title 边界分段，按 dims 软切
        List<LayoutBlock> currentSection = new ArrayList<>();
        for (DocumentPageText page : pr.pages().stream()
                .sorted(Comparator.comparingInt(DocumentPageText::pageNo)).toList()) {
            List<LayoutBlock> orderedBlocks = page.blocks().stream()
                    .filter(this::isContentBlock)
                    .sorted(Comparator.comparing(b -> b.readingOrder() == null ? Integer.MAX_VALUE : b.readingOrder()))
                    .toList();
            for (LayoutBlock b : orderedBlocks) {
                if (b.blockType() == BlockType.TITLE && !currentSection.isEmpty()) {
                    flushSection(currentSection, pr.pages(), out, dims);
                    currentSection.clear();
                }
                currentSection.add(b);
            }
        }
        if (!currentSection.isEmpty()) flushSection(currentSection, pr.pages(), out, dims);
        return out;
    }

    private boolean isContentBlock(LayoutBlock b) {
        return b.blockType() != BlockType.HEADER && b.blockType() != BlockType.FOOTER;
    }

    private void flushSection(List<LayoutBlock> section, List<DocumentPageText> pages,
                              List<VectorChunk> out, StructuredChunkingDimensions dims) {
        if (section.isEmpty()) return;
        StringBuilder buf = new StringBuilder();
        List<LayoutBlock> slice = new ArrayList<>();
        List<String> path = section.get(0).headingPath();
        if (section.get(0).blockType() == BlockType.TITLE) {
            List<String> extended = new ArrayList<>(path);
            extended.add(section.get(0).text());
            path = extended;
        }
        for (LayoutBlock b : section) {
            if (buf.length() + b.text().length() > dims.max() && buf.length() > 0) {
                out.add(buildBodyChunk(buf.toString(), path, List.copyOf(slice), pages, out.size()));
                buf.setLength(0);
                slice.clear();
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(b.text());
            slice.add(b);
        }
        if (buf.length() > 0) {
            out.add(buildBodyChunk(buf.toString(), path, List.copyOf(slice), pages, out.size()));
        }
    }

    private VectorChunk buildBodyChunk(String text, List<String> headingPath, List<LayoutBlock> sourceBlocks,
                                        List<DocumentPageText> pages, int index) {
        int pageStart = sourceBlocks.stream().mapToInt(LayoutBlock::pageNo).min().orElse(0);
        int pageEnd = sourceBlocks.stream().mapToInt(LayoutBlock::pageNo).max().orElse(pageStart);
        List<String> blockIds = sourceBlocks.stream()
                .map(LayoutBlock::blockId).filter(Objects::nonNull).filter(id -> !id.isBlank()).toList();
        String bboxRefs = sourceBlocks.stream()
                .filter(b -> b.bbox() != null)
                .map(b -> bboxRefJson(b.blockId(), b.pageNo(), b.bbox()))
                .collect(Collectors.joining(",", "[", "]"));
        if ("[]".equals(bboxRefs)) bboxRefs = null;

        VectorChunk vc = newChunk(text, index);
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(pageStart)
                .pageRange(pageStart, pageEnd)
                .headingPath(headingPath)
                .blockType(BlockType.PARAGRAPH.name())
                .sourceBlockIds(blockIds)
                .bboxRefs(bboxRefs)
                .textLayerType(textLayerTypeForRange(pages, pageStart, pageEnd))
                .layoutConfidence(averageConfidence(sourceBlocks));
        return vc;
    }

    private VectorChunk newChunk(String content, int index) {
        return VectorChunk.builder()
                .chunkId(java.util.UUID.randomUUID().toString())
                .index(index)
                .content(content)
                .metadata(new HashMap<>())
                .build();
    }

    private String textLayerTypeForRange(List<DocumentPageText> pages, int pageStart, int pageEnd) {
        List<String> values = pages.stream()
                .filter(p -> p.pageNo() >= pageStart && p.pageNo() <= pageEnd)
                .map(DocumentPageText::textLayerType)
                .filter(Objects::nonNull).distinct().toList();
        if (values.isEmpty()) return null;
        return values.size() == 1 ? values.get(0) : "MIXED";
    }

    private Double averageConfidence(List<LayoutBlock> blocks) {
        OptionalDouble avg = blocks.stream()
                .map(LayoutBlock::confidence).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average();
        return avg.isPresent() ? avg.getAsDouble() : null;
    }

    private String bboxRefsJson(String blockId, int pageNo, LayoutBlock.Bbox bbox) {
        if (bbox == null) return null;
        return "[" + bboxRefJson(blockId, pageNo, bbox) + "]";
    }

    private String bboxRefJson(String blockId, int pageNo, LayoutBlock.Bbox bbox) {
        return "{\"blockId\":\"" + (blockId == null ? "" : blockId) + "\","
                + "\"pageNo\":" + pageNo + ","
                + "\"x\":" + bbox.x() + ",\"y\":" + bbox.y() + ","
                + "\"width\":" + bbox.width() + ",\"height\":" + bbox.height() + "}";
    }

    private String serializeTable(LayoutTable t) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : t.rows()) sb.append(String.join(" | ", row)).append("\n");
        return sb.toString().stripTrailing();
    }
}
```

If `LayoutBlock` constructor / accessor signatures differ from this assumption (9-arg ctor, `blockId / blockType / pageNo / bbox / text / readingOrder / confidence / level / headingPath` 顺序），先 grep 现有 record 定义确认实际签名。

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=StructuredChunkingStrategyTest
```
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategyTest.java
git commit -m "feat(ingestion-chunker): StructuredChunkingStrategy (PR 6 / Q5 / layout-aware)"
```

---

### Task 8: `IngestionChunkingDispatcher`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingDispatcher.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingDispatcherTest.java`

- [ ] **Step 1: Write failing test (含同优先级 fail-fast)**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionChunkingDispatcherTest {

    @Test
    void dispatch_picksHighestPriorityMatchingStrategy() {
        IngestionChunkingStrategy high = mock(IngestionChunkingStrategy.class);
        IngestionChunkingStrategy low = mock(IngestionChunkingStrategy.class);
        when(high.priority()).thenReturn(100);
        when(low.priority()).thenReturn(10);
        when(high.supports(any(), any())).thenReturn(true);
        when(low.supports(any(), any())).thenReturn(true);
        when(high.chunk(any(), any())).thenReturn(List.of(new VectorChunk()));

        IngestionChunkingDispatcher d = new IngestionChunkingDispatcher(List.of(low, high));
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());
        d.chunk(ChunkingMode.STRUCTURE_AWARE, in, mock(ChunkingOptions.class));

        verify(high).chunk(any(), any());
        verify(low, never()).chunk(any(), any());
    }

    @Test
    void dispatch_failsFastOnAmbiguousSamePriority() {
        IngestionChunkingStrategy a = mock(IngestionChunkingStrategy.class);
        IngestionChunkingStrategy b = mock(IngestionChunkingStrategy.class);
        when(a.priority()).thenReturn(50);
        when(b.priority()).thenReturn(50);
        when(a.supports(any(), any())).thenReturn(true);
        when(b.supports(any(), any())).thenReturn(true);

        IngestionChunkingDispatcher d = new IngestionChunkingDispatcher(List.of(a, b));
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());

        assertThatThrownBy(() -> d.chunk(ChunkingMode.STRUCTURE_AWARE, in, mock(ChunkingOptions.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous chunking strategies");
    }

    @Test
    void dispatch_throwsWhenNoStrategyMatches() {
        IngestionChunkingStrategy s = mock(IngestionChunkingStrategy.class);
        when(s.priority()).thenReturn(10);
        when(s.supports(any(), any())).thenReturn(false);

        IngestionChunkingDispatcher d = new IngestionChunkingDispatcher(List.of(s));
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());

        assertThatThrownBy(() -> d.chunk(ChunkingMode.STRUCTURE_AWARE, in, mock(ChunkingOptions.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No chunking strategy supports");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=IngestionChunkingDispatcherTest
```
Expected: FAIL — class not found.

- [ ] **Step 3: Implement dispatcher**

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Priority-based dispatch + 同优先级 fail-fast（PR 6 / Q5）。
 * 多个 strategy supports 同一 (mode, input)，最高 priority 唯一获胜；同 priority → 抛异常。
 */
@Component
public class IngestionChunkingDispatcher {

    private final List<IngestionChunkingStrategy> strategies;

    public IngestionChunkingDispatcher(List<IngestionChunkingStrategy> all) {
        this.strategies = all.stream()
                .sorted(Comparator.comparingInt(IngestionChunkingStrategy::priority).reversed())
                .toList();
    }

    public List<VectorChunk> chunk(ChunkingMode mode, IngestionChunkingInput input, ChunkingOptions options) {
        List<IngestionChunkingStrategy> matched = strategies.stream()
                .filter(s -> s.supports(mode, input))
                .toList();
        if (matched.isEmpty()) {
            int pages = input.parseResult() == null ? -1 : input.parseResult().pages().size();
            int tables = input.parseResult() == null ? -1 : input.parseResult().tables().size();
            throw new IllegalStateException(
                    "No chunking strategy supports mode=" + mode
                            + " (pages=" + pages + ", tables=" + tables + ")");
        }
        int top = matched.get(0).priority();
        List<IngestionChunkingStrategy> tied = matched.stream().filter(s -> s.priority() == top).toList();
        if (tied.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous chunking strategies at priority=" + top + ": "
                            + tied.stream().map(s -> s.getClass().getSimpleName()).toList());
        }
        return tied.get(0).chunk(input, options);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=IngestionChunkingDispatcherTest
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingDispatcher.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/IngestionChunkingDispatcherTest.java
git commit -m "feat(ingestion-chunker): IngestionChunkingDispatcher with same-priority fail-fast (PR 6 / Q5)"
```

---

### Task 9: `ChunkerNode` rewire

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNodeDispatchTest.java`

- [ ] **Step 1: Write failing test**

注意真实 API：`ChunkerNode` 现状 ctor 是 `(ObjectMapper, ChunkingStrategyFactory, ChunkEmbeddingService)` 三参；PR 6 改造目标是把 `ChunkingStrategyFactory` 替换为 `IngestionChunkingDispatcher`（仍然 3 参）；`NodeConfig.settings` 是 `JsonNode`（用 ObjectMapper 构造）。

```java
package com.knowledgebase.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingDispatcher;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingInput;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkerNodeDispatchTest {

    @Test
    void execute_routesThroughDispatcherAndEmbeds() {
        ObjectMapper objectMapper = new ObjectMapper();
        IngestionChunkingDispatcher dispatcher = mock(IngestionChunkingDispatcher.class);
        ChunkEmbeddingService embedSvc = mock(ChunkEmbeddingService.class);
        VectorChunk chunk = VectorChunk.builder().chunkId("c1").index(0).content("x").build();
        when(dispatcher.chunk(any(), any(IngestionChunkingInput.class), any())).thenReturn(List.of(chunk));

        ChunkerNode node = new ChunkerNode(objectMapper, dispatcher, embedSvc);   // ← 3 参 ctor
        IngestionContext ctx = IngestionContext.builder()
                .rawText("x").parseMode("basic").build();
        // NodeConfig.settings 是 JsonNode；用 ObjectMapper 构造 strategy = "structure_aware"
        ObjectNode settings = objectMapper.createObjectNode();
        settings.put("strategy", ChunkingMode.STRUCTURE_AWARE.getValue());
        settings.put("chunkSize", 1000);
        settings.put("overlapSize", 100);
        NodeConfig config = NodeConfig.builder().settings(settings).build();

        node.execute(ctx, config);

        assertThat(ctx.getChunks()).containsExactly(chunk);
        verify(embedSvc).embed(List.of(chunk), null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=ChunkerNodeDispatchTest
```
Expected: FAIL — `ChunkerNode` 构造器签名仍是老的 `(ChunkingStrategyFactory, ChunkEmbeddingService)`，测试期望 `(IngestionChunkingDispatcher, ChunkEmbeddingService)`。

- [ ] **Step 3: Rewrite `ChunkerNode` body (preserve existing helper shape)**

main 上现有 `ChunkerNode` 的真实形态：

- ctor 字段：`ObjectMapper / ChunkingStrategyFactory / ChunkEmbeddingService` 三参
- `parseSettings(JsonNode)` 用 `objectMapper.convertValue(node, ChunkerSettings.class)`，**默认 chunkSize=512 / overlapSize=128**
- `convertToChunkConfig(ChunkerSettings)` 调 `settings.getStrategy().createDefaultOptions(chunkSize, overlapSize)` —— **`ChunkerSettings` 没有 `toChunkingOptions()` 方法**，必须走 `ChunkingMode.createDefaultOptions(int, int)` 真实 API
- 现有 `convertToVectorChunks` 是 builder trivial copy，PR 6 后**不再需要**（dispatcher 直接产出 List\<VectorChunk\>）

PR 6 改动：把 `ChunkingStrategyFactory` 替换为 `IngestionChunkingDispatcher`；`requireStrategy + chunker.chunk(text, options)` 替换为 `dispatcher.chunk(mode, input, options)`；删 `convertToVectorChunks` trivial copy；私有 `parseSettings / convertToChunkConfig` 全部保留。

```java
package com.knowledgebase.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingDispatcher;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingInput;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import com.knowledgebase.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.knowledgebase.ai.ragent.ingestion.domain.result.NodeResult;
import com.knowledgebase.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final IngestionChunkingDispatcher dispatcher;
    private final ChunkEmbeddingService chunkEmbeddingService;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        ChunkerSettings settings = parseSettings(config.getSettings());
        ChunkingMode mode = settings.getStrategy();
        if (mode == null) {
            return NodeResult.fail(new ClientException("ChunkerNode 缺少 settings.strategy"));
        }
        IngestionChunkingInput input = IngestionChunkingInput.from(context);
        ChunkingOptions options = convertToChunkConfig(settings);

        List<VectorChunk> chunks = dispatcher.chunk(mode, input, options);
        chunkEmbeddingService.embed(chunks, null);
        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        return settings.getStrategy().createDefaultOptions(
                settings.getChunkSize(), settings.getOverlapSize());
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=ChunkerNodeDispatchTest
```
Expected: PASS.

- [ ] **Step 5: Run full chunker package tests to ensure no regression**

```bash
mvn -pl bootstrap test -Dtest='com.knowledgebase.ai.ragent.ingestion.chunker.*Test,ChunkerNode*Test'
```
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNodeDispatchTest.java
git commit -m "refactor(ingestion): ChunkerNode rewires to IngestionChunkingDispatcher (PR 6 / Q5)"
```

---

## Phase 3: Knowledge Persistence Bridge

### Task 10: Extend `KnowledgeChunkCreateRequest` with 9 layout fields

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeChunkCreateRequest.java`

- [ ] **Step 1: Add 9 nullable fields**

Replace the entire body of `KnowledgeChunkCreateRequest`:

```java
package com.knowledgebase.ai.ragent.knowledge.controller.request;

import lombok.Data;

/**
 * 知识库 Chunk 创建请求。PR 6 起加 9 个 nullable layout 字段：
 * <ul>
 *   <li>manual chunk creation API（KnowledgeChunkController）调用时不会填（手工 chunk 没 layout）</li>
 *   <li>ingestion persist 路径由 KnowledgeChunkLayoutMapper.copyToCreateRequest 自动填</li>
 *   <li>headingPath / sourceBlockIds 与 KnowledgeChunkDO 同形态：JSON String</li>
 * </ul>
 */
@Data
public class KnowledgeChunkCreateRequest {

    /** 现有字段不动 */
    private String content;
    private Integer index;
    private String chunkId;

    /** PR 6 layout 字段 */
    private Integer pageNumber;
    private Integer pageStart;
    private Integer pageEnd;
    private String headingPath;        // JSON 数组字符串
    private String blockType;
    private String sourceBlockIds;     // JSON 数组字符串
    private String bboxRefs;
    private String textLayerType;
    private Double layoutConfidence;
}
```

- [ ] **Step 2: Verify compile**

```bash
mvn -pl bootstrap compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeChunkCreateRequest.java
git commit -m "feat(knowledge): KnowledgeChunkCreateRequest carries 9 layout fields (PR 6 / Q6 v3)"
```

---

### Task 11: `KnowledgeChunkLayoutMapper`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/KnowledgeChunkLayoutMapper.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/KnowledgeChunkLayoutMapperTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkLayoutMapperTest {

    private final KnowledgeChunkLayoutMapper mapper = new KnowledgeChunkLayoutMapper(new ObjectMapper());

    @Test
    void copyFromDO_fullDoFillsAll9Keys() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1")
                .pageNumber(12).pageStart(12).pageEnd(13)
                .headingPath("[\"第三章\",\"3.2\"]")
                .blockType("PARAGRAPH")
                .sourceBlockIds("[\"b1\",\"b2\"]")
                .bboxRefs("[]")  // helper writer 跳过 "[]"
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.95)
                .build();
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();

        mapper.copyFromDO(chunkDO, vc);

        assertThat(ChunkLayoutMetadata.pageNumber(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(13);
        assertThat(ChunkLayoutMetadata.headingPath(vc)).containsExactly("第三章", "3.2");
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo("PARAGRAPH");
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).containsExactly("b1", "b2");
        assertThat(ChunkLayoutMetadata.textLayerType(vc)).isEqualTo("NATIVE_TEXT");
        assertThat(ChunkLayoutMetadata.layoutConfidence(vc)).isEqualTo(0.95);
        // bbox_refs="[]" 被 writer 主动跳过
        assertThat(vc.getMetadata()).doesNotContainKey(VectorMetadataFields.BBOX_REFS);
    }

    @Test
    void copyFromDO_allNullDo_writesNoKeys() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder().id("c1").build();  // 全 null
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();

        mapper.copyFromDO(chunkDO, vc);

        assertThat(vc.getMetadata()).doesNotContainKeys(
                VectorMetadataFields.PAGE_NUMBER, VectorMetadataFields.PAGE_START,
                VectorMetadataFields.PAGE_END, VectorMetadataFields.HEADING_PATH,
                VectorMetadataFields.BLOCK_TYPE, VectorMetadataFields.SOURCE_BLOCK_IDS,
                VectorMetadataFields.BBOX_REFS, VectorMetadataFields.TEXT_LAYER_TYPE,
                VectorMetadataFields.LAYOUT_CONFIDENCE);
    }

    @Test
    void copyFromDO_invalidJsonHeadingPath_logsWarnNotThrow() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1").headingPath("{not valid json").build();
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();

        mapper.copyFromDO(chunkDO, vc);

        assertThat(vc.getMetadata()).doesNotContainKey(VectorMetadataFields.HEADING_PATH);
    }

    @Test
    void copyToCreateRequest_metadataFullToCreateRequest9Fields() {
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(7).pageRange(7, 8)
                .headingPath(List.of("第一章", "1.1"))
                .blockType("PARAGRAPH")
                .sourceBlockIds(List.of("b1"))
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.88);
        KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();

        mapper.copyToCreateRequest(vc, req);

        assertThat(req.getPageNumber()).isEqualTo(7);
        assertThat(req.getPageStart()).isEqualTo(7);
        assertThat(req.getPageEnd()).isEqualTo(8);
        assertThat(req.getHeadingPath()).isEqualTo("[\"第一章\",\"1.1\"]");
        assertThat(req.getBlockType()).isEqualTo("PARAGRAPH");
        assertThat(req.getSourceBlockIds()).isEqualTo("[\"b1\"]");
        assertThat(req.getTextLayerType()).isEqualTo("NATIVE_TEXT");
        assertThat(req.getLayoutConfidence()).isEqualTo(0.88);
    }

    @Test
    void copyToCreateRequest_emptyMetadataAllNull() {
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();
        KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();

        mapper.copyToCreateRequest(vc, req);

        assertThat(req.getPageNumber()).isNull();
        assertThat(req.getHeadingPath()).isNull();
        assertThat(req.getSourceBlockIds()).isNull();
        assertThat(req.getBlockType()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=KnowledgeChunkLayoutMapperTest
```
Expected: FAIL — class not found.

- [ ] **Step 3: Implement mapper**

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Knowledge 域内的 layout 字段桥接层（PR 6 / v3 review 引入）：
 * <ul>
 *   <li>{@link #copyFromDO}: re-index 路径用，DO → VectorChunk.metadata（JSON String → List 反序列化）</li>
 *   <li>{@link #copyToCreateRequest}: persist 路径用，VectorChunk.metadata → CreateRequest（List → JSON String 序列化）</li>
 * </ul>
 *
 * <p>本类自由 import knowledge.dao.entity.KnowledgeChunkDO 和
 * knowledge.controller.request.KnowledgeChunkCreateRequest；这是合规的 knowledge → rag.core.vector
 * 单向依赖。{@link ChunkLayoutMetadata} 维持 vector core 抽象的纯净。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeChunkLayoutMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /** DB 反向回填：DO 9 字段 → VectorChunk.metadata。 */
    public void copyFromDO(KnowledgeChunkDO chunkDO, VectorChunk target) {
        if (chunkDO == null || target == null) return;
        ChunkLayoutMetadata.Builder w = ChunkLayoutMetadata.writer(target);
        w.pageNumber(chunkDO.getPageNumber());
        w.pageRange(chunkDO.getPageStart(), chunkDO.getPageEnd());
        w.blockType(chunkDO.getBlockType());
        w.bboxRefs(chunkDO.getBboxRefs());
        w.textLayerType(chunkDO.getTextLayerType());
        w.layoutConfidence(chunkDO.getLayoutConfidence());
        w.headingPath(parseStringList(chunkDO.getHeadingPath(), "headingPath", chunkDO.getId()));
        w.sourceBlockIds(parseStringList(chunkDO.getSourceBlockIds(), "sourceBlockIds", chunkDO.getId()));
    }

    /** Persist 序列化：VectorChunk.metadata → CreateRequest 9 字段。
     *  v4 review P2 #5：reader 现在缺 key 返 null（不是空 List），所以 hp / sb 必须 null-safe。 */
    public void copyToCreateRequest(VectorChunk source, KnowledgeChunkCreateRequest target) {
        if (source == null || target == null || source.getMetadata() == null) return;
        target.setPageNumber(ChunkLayoutMetadata.pageNumber(source));
        target.setPageStart(ChunkLayoutMetadata.pageStart(source));
        target.setPageEnd(ChunkLayoutMetadata.pageEnd(source));
        target.setBlockType(ChunkLayoutMetadata.blockType(source));
        target.setBboxRefs(ChunkLayoutMetadata.bboxRefs(source));
        target.setTextLayerType(ChunkLayoutMetadata.textLayerType(source));
        target.setLayoutConfidence(ChunkLayoutMetadata.layoutConfidence(source));

        List<String> hp = ChunkLayoutMetadata.headingPath(source);    // 可能 null
        target.setHeadingPath(hp == null || hp.isEmpty() ? null : toJson(hp));
        List<String> sb = ChunkLayoutMetadata.sourceBlockIds(source);
        target.setSourceBlockIds(sb == null || sb.isEmpty() ? null : toJson(sb));
    }

    private List<String> parseStringList(String json, String fieldName, String chunkId) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to deserialize {} for chunk {}: {}", fieldName, chunkId, json);
            return Collections.emptyList();
        }
    }

    private String toJson(List<String> list) {
        try { return objectMapper.writeValueAsString(list); }
        catch (Exception e) { return null; }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=KnowledgeChunkLayoutMapperTest
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/KnowledgeChunkLayoutMapper.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/KnowledgeChunkLayoutMapperTest.java
git commit -m "feat(knowledge): KnowledgeChunkLayoutMapper bridge component (PR 6 / Q6 v3)"
```

---

### Task 12: `KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically` 注入 mapper + 加 1 行

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

- [ ] **Step 1: Inject `KnowledgeChunkLayoutMapper` field**

In `KnowledgeDocumentServiceImpl.java`, add to the field block (within `@RequiredArgsConstructor` scope):

```java
    private final com.knowledgebase.ai.ragent.knowledge.service.support.KnowledgeChunkLayoutMapper layoutMapper;
```

- [ ] **Step 2: Modify the stream map block in `persistChunksAndVectorsAtomically:323-331`**

Find the existing stream:

```java
        List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.getContent());
                    return req;
                })
                .toList();
```

Add **one line** before `return req;`:

```java
        List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.getContent());
                    layoutMapper.copyToCreateRequest(vc, req);   // PR 6 / Q6 v3：9 layout 字段一次性拷贝
                    return req;
                })
                .toList();
```

- [ ] **Step 3: Verify compile**

```bash
mvn -pl bootstrap compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "feat(knowledge): persist path threads layout via KnowledgeChunkLayoutMapper (PR 6)"
```

---

### Task 13: `KnowledgeChunkServiceImpl.batchCreate` DO 构造加 9 字段拷贝

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java`

- [ ] **Step 1: Locate `batchCreate(...)` line 220-232**

Find the existing `KnowledgeChunkDO.builder()...build()` block within `batchCreate`. Replace it with:

```java
            KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                    .id(chunkId)
                    .kbId(kbId)
                    .docId(docId)
                    .chunkIndex(chunkIndex)
                    .content(content)
                    .contentHash(SecureUtil.sha256(content))
                    .charCount(content.length())
                    .tokenCount(resolveTokenCount(content))
                    // PR 6 新增 9 layout 字段：CreateRequest 已携带 JSON String 形态，DO 字段同形态
                    .pageNumber(request.getPageNumber())
                    .pageStart(request.getPageStart())
                    .pageEnd(request.getPageEnd())
                    .headingPath(request.getHeadingPath())
                    .blockType(request.getBlockType())
                    .sourceBlockIds(request.getSourceBlockIds())
                    .bboxRefs(request.getBboxRefs())
                    .textLayerType(request.getTextLayerType())
                    .layoutConfidence(request.getLayoutConfidence())
                    .enabled(1)
                    .createdBy(username)
                    .updatedBy(username)
                    .build();
            chunkDOList.add(chunkDO);
```

- [ ] **Step 2: Verify compile**

```bash
mvn -pl bootstrap compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java
git commit -m "feat(knowledge): batchCreate copies 9 layout fields from CreateRequest to DO (PR 6)"
```

---

### Task 14: 5 处 re-index 路径走统一 helper `buildVectorChunkFromDO`（v4 review P1 #1 + P1 #4）

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/ChunkServiceReindexCallSitesMockTest.java`

- [ ] **Step 1: Inject `KnowledgeChunkLayoutMapper layoutMapper` field in BOTH services**

Add to `KnowledgeChunkServiceImpl`'s `@RequiredArgsConstructor` field block:

```java
    private final com.knowledgebase.ai.ragent.knowledge.service.support.KnowledgeChunkLayoutMapper layoutMapper;
```

(Task 12 already injected in `KnowledgeDocumentServiceImpl` —— 不重复加。)

- [ ] **Step 2: Add private helper `buildVectorChunkFromDO` to `KnowledgeChunkServiceImpl`**

```java
    /**
     * Re-index 路径统一构造 helper：DB 读 KnowledgeChunkDO → VectorChunk + 9 layout 字段反向回填到 metadata。
     * 5 处 VectorChunk.builder() 原始调用点统一委托此方法（v4 review P1 #4：让单测覆盖一处即覆盖五处）。
     *
     * <p>包级别可见（package-private）以便 {@code ChunkServiceReindexCallSitesMockTest} 直接断言。
     */
    VectorChunk buildVectorChunkFromDO(KnowledgeChunkDO chunkDO) {
        VectorChunk vc = VectorChunk.builder()
                .chunkId(chunkDO.getId())
                .content(chunkDO.getContent())
                .index(chunkDO.getChunkIndex())
                .build();
        layoutMapper.copyFromDO(chunkDO, vc);
        return vc;
    }
```

- [ ] **Step 3: Replace 5 `VectorChunk.builder()` call sites with `buildVectorChunkFromDO`**

| 位置 | 替换前 | 替换后 |
|---|---|---|
| `KnowledgeChunkServiceImpl:244-250` (`batchCreate` writeVector 分支) | `.map(each -> VectorChunk.builder().chunkId(...).content(...).index(...).build())` | `.map(this::buildVectorChunkFromDO)` |
| `KnowledgeChunkServiceImpl:303`（单 chunk add 入向量）| `VectorChunk chunk = VectorChunk.builder()...build();` | `VectorChunk chunk = buildVectorChunkFromDO(chunkDO);` |
| `KnowledgeChunkServiceImpl:434-438` (`batchEnableChunks` enable 分支) | `.map(c -> VectorChunk.builder()....build())` | `.map(this::buildVectorChunkFromDO)` |
| `KnowledgeChunkServiceImpl:534-540`（单 chunk enable）| `VectorChunk chunk = VectorChunk.builder()...build();` | `VectorChunk chunk = buildVectorChunkFromDO(chunkDO);` |
| **`KnowledgeDocumentServiceImpl:784-791`（doc-level enable，v4 review P1 #1）**| `chunks = knowledgeChunkService.listByDocId(docId);` 然后 `.map(each -> VectorChunk.builder()....build())` | **改 line 784** 为 `chunks = chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId).orderByAsc(KnowledgeChunkDO::getChunkIndex));`（直接查 DO，绕过 VO），然后 `.map(knowledgeChunkServiceImpl::buildVectorChunkFromDO)` —— 即跨 service 调用 package-private helper（同包），或者把 helper 改 `public` 让 cross-service 能调 |

`KnowledgeDocumentServiceImpl:784` 的具体改写：

```java
        // 启用时：embed 耗时较长，在事务外提前执行
        List<VectorChunk> vectorChunks = null;
        if (enabled) {
            // v4 review P1 #1: doc-level enable 改用 DO 路径（KnowledgeChunkVO 没 9 layout 字段，无法 copyFromDO）
            List<KnowledgeChunkDO> chunkDOs = chunkMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeChunkDO>()
                            .eq(KnowledgeChunkDO::getDocId, docId)
                            .orderByAsc(KnowledgeChunkDO::getChunkIndex));
            vectorChunks = chunkDOs.stream()
                    .map(knowledgeChunkServiceImpl::buildVectorChunkFromDO)   // 跨 service package-private 调用（同包）
                    .toList();
            if (CollUtil.isEmpty(vectorChunks)) {
                log.warn("启用文档时未找到任何 Chunk，跳过向量重建，docId={}", docId);
                return;
            }
            chunkEmbeddingService.embed(vectorChunks, kbDO.getEmbeddingModel());
        }
```

如果 `KnowledgeDocumentServiceImpl` 已经 `@RequiredArgsConstructor` 且没注入 `chunkMapper`，先加 `private final KnowledgeChunkMapper chunkMapper;` field（同 service 域，依赖合规）。

注：`knowledgeChunkServiceImpl` 是 service 字段名（`KnowledgeChunkServiceImpl knowledgeChunkServiceImpl`），如果现有注入字段名是 `knowledgeChunkService`（接口型），需要把 `buildVectorChunkFromDO` 提到 `KnowledgeChunkService` 接口，或者把 helper 改 `public` 直接通过 impl 实例调。简洁方案：把 helper 改 `public`，`KnowledgeDocumentServiceImpl` 注入 `KnowledgeChunkService` 接口扩展方法即可。

- [ ] **Step 4: Write `ChunkServiceReindexCallSitesMockTest` (v4 review P1 #4 锁契约)**

`bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/ChunkServiceReindexCallSitesMockTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.knowledgebase.ai.ragent.knowledge.service.support.KnowledgeChunkLayoutMapper;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v4 review P1 #4：仅靠 mapper 单测无法证明"5 处 VectorChunk.builder 调用点真的调了 copyFromDO"。
 * 本测试通过验证 {@code buildVectorChunkFromDO} helper（5 处 call site 共用入口）的输出契约，
 * 间接锁定 5 处都不会丢 layout —— 任何 call site 绕开 helper 自己 builder 都会被 review 灯红。
 *
 * <p>完整 service 行为（数据库 + OS）走 manual ops smoke（spec §10.3）。
 */
class ChunkServiceReindexCallSitesMockTest {

    @Test
    void buildVectorChunkFromDO_propagatesAll9LayoutFieldsToMetadata() {
        // 直接构造一个 KnowledgeChunkServiceImpl 的部分实例（用 layoutMapper 真实实例，service 其它依赖可 null —— helper 不依赖它们）
        KnowledgeChunkLayoutMapper mapper = new KnowledgeChunkLayoutMapper(new ObjectMapper());
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                /* mapper */ null, /* documentMapper */ null, /* knowledgeBaseMapper */ null,
                /* embeddingService */ null, /* vectorStoreService */ null,
                /* tokenCounter */ null, /* objectMapper */ new ObjectMapper(),
                /* layoutMapper */ mapper, /* kbManageAccess */ null
        );  // 字段顺序按 KnowledgeChunkServiceImpl 实际 @RequiredArgsConstructor 调整

        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1").content("hello").chunkIndex(7)
                .pageNumber(12).pageStart(12).pageEnd(13)
                .headingPath("[\"第三章\",\"3.2\"]")
                .blockType("PARAGRAPH")
                .sourceBlockIds("[\"b1\"]")
                .bboxRefs("[{\"x\":1}]")
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.95)
                .build();

        VectorChunk vc = service.buildVectorChunkFromDO(chunkDO);

        assertThat(vc.getChunkId()).isEqualTo("c1");
        assertThat(vc.getContent()).isEqualTo("hello");
        assertThat(vc.getIndex()).isEqualTo(7);
        // 9 layout 字段全部到位
        assertThat(ChunkLayoutMetadata.pageNumber(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(13);
        assertThat(ChunkLayoutMetadata.headingPath(vc)).containsExactly("第三章", "3.2");
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo("PARAGRAPH");
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).containsExactly("b1");
        assertThat(ChunkLayoutMetadata.bboxRefs(vc)).isEqualTo("[{\"x\":1}]");
        assertThat(ChunkLayoutMetadata.textLayerType(vc)).isEqualTo("NATIVE_TEXT");
        assertThat(ChunkLayoutMetadata.layoutConfidence(vc)).isEqualTo(0.95);
    }
}
```

如果 `KnowledgeChunkServiceImpl` 真实 ctor 字段数 / 顺序与上面注释不符，按真实定义调整 mock 注入。**关键不变**：用真实 `layoutMapper` 实例 + helper 直调。

- [ ] **Step 5: Run test**

```bash
mvn -pl bootstrap test -Dtest=ChunkServiceReindexCallSitesMockTest
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/ChunkServiceReindexCallSitesMockTest.java
git commit -m "feat(knowledge): 5 re-index sites unified via buildVectorChunkFromDO helper (PR 6 / v4 P1 #1 #4)"
```

---

### Task 15: `KnowledgeChunkServiceImpl.update` manual edit contract（5 保留 / 4 清空）

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java` `update()` method (line 263+ in main)

- [ ] **Step 1: Replace `update`'s persist block with `LambdaUpdateWrapper.set` (v4 review P1 #2)**

**关键 gotcha**：MyBatis Plus 默认 `FieldStrategy.NOT_NULL` 让 `chunkMapper.updateById(chunkDO)` 跳过 null 字段。`chunkDO.setSourceBlockIds(null)` 后调 updateById **不会**清 DB 列。必须用 `LambdaUpdateWrapper.set(field, null)` 显式发送 NULL 给 SQL。9 layout 列均无 typeHandler 注解（验证过），LambdaUpdateWrapper.set 安全（不触发 gotcha §2 typeHandler 陷阱）。

替换原来的 `chunkDO.setContent(newContent); ... chunkMapper.updateById(chunkDO);` 块为：

```java
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
// ...

        // PR 6 / v4 review P1 #2: 用 LambdaUpdateWrapper.set 显式发送 NULL，绕过 FieldStrategy.NOT_NULL
        chunkMapper.update(null, Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getId, chunkId)
                .set(KnowledgeChunkDO::getContent, newContent)
                .set(KnowledgeChunkDO::getContentHash, SecureUtil.sha256(newContent))
                .set(KnowledgeChunkDO::getCharCount, newContent.length())
                .set(KnowledgeChunkDO::getTokenCount, resolveTokenCount(newContent))
                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                // PR 6: 清 4 个 extraction-specific 字段（5 个 location 字段不出现在 set 列表里 = 不动）
                .set(KnowledgeChunkDO::getSourceBlockIds, null)
                .set(KnowledgeChunkDO::getBboxRefs, null)
                .set(KnowledgeChunkDO::getTextLayerType, null)
                .set(KnowledgeChunkDO::getLayoutConfidence, null));

        // 重读最新 DO 用于 OS rebuild（含保留的 location 字段 + 已清空的 extraction 字段）
        KnowledgeChunkDO refreshed = chunkMapper.selectById(chunkId);
        VectorChunk vc = buildVectorChunkFromDO(refreshed);   // Task 14 加的 helper，含 layoutMapper.copyFromDO
        chunkEmbeddingService.embed(List.of(vc), kbDO.getEmbeddingModel());
        vectorStoreService.indexDocumentChunks(collectionName, docId, kbId, securityLevel, List.of(vc));
```

如果原 `update` 方法还有别的 setter（如 `enabled` 状态等），**保留**那些不与 layout 冲突的 set 调用，仅仅替换"涉及 content 与 layout 清除的部分"为上述 LambdaUpdateWrapper。

- [ ] **Step 2: Write regression test**

`bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual edit 契约（PR 6 / Q6 v3 review P2 #3）：
 * 5 location 字段保留（pageNumber / pageStart / pageEnd / headingPath / blockType），
 * 4 extraction 字段清空（sourceBlockIds / bboxRefs / textLayerType / layoutConfidence）。
 *
 * <p>本测试是 contract-level 单元覆盖；完整 service 行为依赖 Mapper / Spring 上下文，详见 manual ops smoke。
 */
class ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest {

    @Test
    void simulatedUpdate_clearsExtractionFields_keepsLocationFields() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1").content("original")
                .pageNumber(7).pageStart(7).pageEnd(8)
                .headingPath("[\"Ch1\"]")
                .blockType("PARAGRAPH")
                .sourceBlockIds("[\"b1\"]")
                .bboxRefs("[{\"x\":1}]")
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.9)
                .build();

        // 模拟 update 内部的 4 字段清空
        chunkDO.setContent("edited");
        chunkDO.setSourceBlockIds(null);
        chunkDO.setBboxRefs(null);
        chunkDO.setTextLayerType(null);
        chunkDO.setLayoutConfidence(null);

        // 5 location 字段保留
        assertThat(chunkDO.getPageNumber()).isEqualTo(7);
        assertThat(chunkDO.getPageStart()).isEqualTo(7);
        assertThat(chunkDO.getPageEnd()).isEqualTo(8);
        assertThat(chunkDO.getHeadingPath()).isEqualTo("[\"Ch1\"]");
        assertThat(chunkDO.getBlockType()).isEqualTo("PARAGRAPH");

        // 4 extraction 字段清空
        assertThat(chunkDO.getSourceBlockIds()).isNull();
        assertThat(chunkDO.getBboxRefs()).isNull();
        assertThat(chunkDO.getTextLayerType()).isNull();
        assertThat(chunkDO.getLayoutConfidence()).isNull();
    }
}
```

- [ ] **Step 3: Run test**

```bash
mvn -pl bootstrap test -Dtest=ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest.java
git commit -m "feat(knowledge): manual chunk edit clears 4 extraction fields, keeps 5 location (PR 6 / v3 P2 #3)"
```

---

## Phase 4: OpenSearch Mapping Migration

### Task 16: `OpenSearchVectorStoreAdmin` mapping forward-only 升级

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java`

- [ ] **Step 1: Locate `buildMappingJson` (or equivalent) text block**

Look at the metadata mapping JSON near line 260-299 (per spec). Add 9 layout properties **inside** the existing `metadata.properties` block:

```java
"metadata": {
  "dynamic": false,
  "properties": {
    "kb_id":            { "type": "keyword" },
    "security_level":   { "type": "integer" },
    "doc_id":           { "type": "keyword" },
    "chunk_index":      { "type": "integer" },
    "task_id":          { "type": "keyword", "index": false },
    "pipeline_id":      { "type": "keyword" },
    "source_type":      { "type": "keyword" },
    "source_location":  { "type": "keyword", "index": false },
    "keywords":         { "type": "text" },
    "summary":          { "type": "text" },
    "page_number":      { "type": "integer" },
    "page_start":       { "type": "integer" },
    "page_end":         { "type": "integer" },
    "heading_path":     { "type": "keyword" },
    "block_type":       { "type": "keyword" },
    "source_block_ids": { "type": "keyword" },
    "bbox_refs":        { "type": "text", "index": false },
    "text_layer_type":  { "type": "keyword" },
    "layout_confidence":{ "type": "float" }
  }
}
```

`ensureVectorSpace` 维持 create-if-missing 语义不动 —— 已有索引按 spec §11.2 走 `DELETE + re-ingest`。

- [ ] **Step 2: Verify compile**

```bash
mvn -pl bootstrap compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java
git commit -m "feat(opensearch): metadata mapping declares 9 layout fields (PR 6 / Q2 forward-only)"
```

---

## Phase 5: Retrieval Read Path

### Task 17: `RetrievedChunk` extend + `OpenSearchRetrieverService.toRetrievedChunk`

**Files:**
- Modify: `framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/RetrievedChunk.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrieverLayoutFieldRoundTripIntegrationTest.java`

- [ ] **Step 1: Add 6 nullable fields to `RetrievedChunk`**

Append to the existing fields:

```java
    // PR 6 layout 字段（chunk-level evidence，仅 OpenSearch backend 回填；非 OS backend null）
    private Integer pageNumber;
    private Integer pageStart;
    private Integer pageEnd;
    private java.util.List<String> headingPath;
    private String blockType;
    private java.util.List<String> sourceBlockIds;
```

`bbox_refs / textLayerType / layoutConfidence` **不进 RetrievedChunk** —— 不是 SourceChunk 契约的一部分（spec Non-Goal 5 + Q7）。

- [ ] **Step 2: Write failing integration test**

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索读路径：OS metadata Map → RetrievedChunk 6 字段。
 * 验证 OpenSearchRetrieverService.toRetrievedChunk 能正确把 OS _source.metadata
 * 反序列化为 RetrievedChunk 6 字段（特别 List<String> 不被错误处理为 String）。
 */
class RetrieverLayoutFieldRoundTripIntegrationTest {

    @Test
    void toRetrievedChunkExtractsLayout_listFieldsAsList() {
        // 模拟 OS hit._source.metadata 的 Map 形态
        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("kb_id", "kb1");
        metaMap.put("security_level", 0);
        metaMap.put("doc_id", "d1");
        metaMap.put("chunk_index", 0);
        metaMap.put("page_number", 7);
        metaMap.put("page_start", 7);
        metaMap.put("page_end", 8);
        metaMap.put("heading_path", List.of("第一章", "1.1"));
        metaMap.put("block_type", "PARAGRAPH");
        metaMap.put("source_block_ids", List.of("b1", "b2"));

        RetrievedChunk chunk = OpenSearchRetrieverService.toRetrievedChunkForTest(metaMap, "id1", "text", 0.95F);

        assertThat(chunk.getPageNumber()).isEqualTo(7);
        assertThat(chunk.getPageStart()).isEqualTo(7);
        assertThat(chunk.getPageEnd()).isEqualTo(8);
        assertThat(chunk.getHeadingPath()).containsExactly("第一章", "1.1");
        assertThat(chunk.getBlockType()).isEqualTo("PARAGRAPH");
        assertThat(chunk.getSourceBlockIds()).containsExactly("b1", "b2");
    }
}
```

如果 `toRetrievedChunk` 是 private，加一个 package-private static helper `toRetrievedChunkForTest(Map, String id, String text, Float score)` 暴露纯 metadata 抽取逻辑供测试用。

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=RetrieverLayoutFieldRoundTripIntegrationTest
```
Expected: FAIL — `toRetrievedChunkForTest` 不存在 OR 6 layout 字段未被回填。

- [ ] **Step 4: Modify `OpenSearchRetrieverService.toRetrievedChunk` to extract 6 layout fields**

In the existing `toRetrievedChunk(...)` method (line 281-327)，在现有 `kb_id / security_level / doc_id / chunk_index` 抽取后追加：

```java
        // PR 6: 抽 6 个 chunk-level layout 字段（用 ChunkLayoutMetadata reader 复用三态兼容）
        VectorChunk metaWrapper = new VectorChunk();
        metaWrapper.setMetadata(metaMap);
        chunk.setPageNumber(ChunkLayoutMetadata.pageNumber(metaWrapper));
        chunk.setPageStart(ChunkLayoutMetadata.pageStart(metaWrapper));
        chunk.setPageEnd(ChunkLayoutMetadata.pageEnd(metaWrapper));
        chunk.setHeadingPath(ChunkLayoutMetadata.headingPath(metaWrapper));   // v4 起返 null 而非空 List
        chunk.setBlockType(ChunkLayoutMetadata.blockType(metaWrapper));
        chunk.setSourceBlockIds(ChunkLayoutMetadata.sourceBlockIds(metaWrapper));   // v4 起返 null
```

**v4 review P2 #5 注意**：`ChunkLayoutMetadata.headingPath / sourceBlockIds` 在 v4 起 metadata Map 缺 key 时返回 `null`（不是 `List.of()`）。所以 `chunk.setHeadingPath(null)` 会发生 —— `RetrievedChunk.headingPath` 字段类型是 nullable `List<String>`，下游 `SourceCardBuilder.copy` 透传 null 进 SourceChunk，`@JsonInclude(NON_EMPTY)` 自动跳过 → 前端不出现 `headingPath: []` 假阳性。整条链路对称。

并加一个 package-private test helper（如尚未存在）：

```java
    static RetrievedChunk toRetrievedChunkForTest(Map<String, Object> metaMap, String id, String text, Float score) {
        // 把生产逻辑抽到一个静态 method，让测试可以在不启 OS / Spring 的前提下验证 metadata 抽取
        // 实施时把 toRetrievedChunk 内的"metaMap → RetrievedChunk 字段"映射部分 inline 到此处
        ...
    }
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn -pl bootstrap test -Dtest=RetrieverLayoutFieldRoundTripIntegrationTest
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/RetrievedChunk.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrieverLayoutFieldRoundTripIntegrationTest.java
git commit -m "feat(rag-retrieve): RetrievedChunk + OpenSearchRetrieverService extract 6 layout fields (PR 6)"
```

---

## Phase 6: Source Rendering

### Task 18: `SourceChunk` extend + `@JsonInclude(NON_NULL)` + `SourceCardBuilder` mapping

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/dto/SourceChunk.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCardBuilder.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/dto/SourceChunkSerializationTest.java`

- [ ] **Step 1: Modify `SourceChunk.java`**

Replace the entire body:

```java
package com.knowledgebase.ai.ragent.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档级源卡片内的单个 chunk 片段。PR 6 起加 6 个 nullable layout 字段。
 *
 * <p><b>v4 review P2 #5</b>：使用 {@code NON_EMPTY}（不是 NON_NULL）—— 既跳 null，又跳空 List/String，
 * 防止 BASIC chunk 因为 `headingPath = []` 这种边缘 wire 出 `"headingPath":[]` 假阳性。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SourceChunk {
    private String chunkId;
    private int chunkIndex;
    private String preview;
    private float score;

    /** PR 6 chunk-level evidence（与 Collateral PR tier-A 契约对齐） */
    private Integer pageNumber;
    private Integer pageStart;
    private Integer pageEnd;
    private List<String> headingPath;
    private String blockType;
    private List<String> sourceBlockIds;
}
```

- [ ] **Step 2: Write failing serialization test**

```java
package com.knowledgebase.ai.ragent.rag.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceChunkSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void layoutFields_omittedWhenNull() throws Exception {
        SourceChunk chunk = SourceChunk.builder()
                .chunkId("c1").chunkIndex(0).preview("hello").score(0.9F)
                .build();  // 6 layout 字段全 null
        String json = mapper.writeValueAsString(chunk);

        assertThat(json).doesNotContain("pageNumber");
        assertThat(json).doesNotContain("headingPath");
        assertThat(json).doesNotContain("blockType");
    }

    @Test
    void layoutFields_includedWhenPresent() throws Exception {
        SourceChunk chunk = SourceChunk.builder()
                .chunkId("c1").chunkIndex(0).preview("hello").score(0.9F)
                .pageNumber(7).pageStart(7).pageEnd(8)
                .headingPath(List.of("第一章"))
                .blockType("PARAGRAPH")
                .build();
        String json = mapper.writeValueAsString(chunk);

        assertThat(json).contains("\"pageNumber\":7");
        assertThat(json).contains("\"headingPath\":[\"第一章\"]");
        assertThat(json).contains("\"blockType\":\"PARAGRAPH\"");
    }
}
```

- [ ] **Step 3: Run test to verify it passes (Step 1 已写好实现)**

```bash
mvn -pl bootstrap test -Dtest=SourceChunkSerializationTest
```
Expected: PASS (2 tests).

- [ ] **Step 4: Modify `SourceCardBuilder` to map 6 fields**

In `SourceCardBuilder.java` find the existing `chunksInDoc.stream().map(rc -> SourceChunk.builder()...)` block (around line 115-125)，加 6 行：

```java
List<SourceChunk> sortedChunks = chunksInDoc.stream()
        .map(rc -> SourceChunk.builder()
                .chunkId(rc.getId())
                .chunkIndex(rc.getChunkIndex())
                .preview(truncateByCodePoint(rc.getText(), previewMaxChars))
                .score(rc.getScore())
                // PR 6 chunk-level evidence
                .pageNumber(rc.getPageNumber())
                .pageStart(rc.getPageStart())
                .pageEnd(rc.getPageEnd())
                .headingPath(rc.getHeadingPath())
                .blockType(rc.getBlockType())
                .sourceBlockIds(rc.getSourceBlockIds())
                .build())
        .toList();
```

- [ ] **Step 5: Verify compile + run all source tests**

```bash
mvn -pl bootstrap compile
mvn -pl bootstrap test -Dtest='com.knowledgebase.ai.ragent.rag.dto.*Test,SourceCardBuilder*Test'
```
Expected: BUILD SUCCESS + ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/dto/SourceChunk.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCardBuilder.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/dto/SourceChunkSerializationTest.java
git commit -m "feat(rag-source): SourceChunk + Builder add 6 layout fields with @JsonInclude NON_NULL (PR 6 / v3 P1 #2)"
```

---

## Phase 7: Frontend

### Task 19: Frontend types + Sources.tsx evidence rendering

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/components/chat/Sources.tsx`

- [ ] **Step 1: Extend `SourceChunk` interface in `types/index.ts`**

Find the existing `SourceChunk` interface and add 6 fields:

```typescript
export interface SourceChunk {
  chunkId: string;
  chunkIndex: number;
  preview: string;
  score: number;
  // PR 6 chunk-level evidence。后端 @JsonInclude(NON_NULL) 让 null 字段在 wire 上不出现 →
  // 前端字段为 undefined；但 cache 漂移 / 历史数据仍可能产生 null，所以类型用 ?: T | null
  // 同时覆盖 undefined 和 null。守卫一律用 != null（不是 !== undefined）。
  pageNumber?: number | null;
  pageStart?: number | null;
  pageEnd?: number | null;
  headingPath?: string[] | null;
  blockType?: string | null;
  sourceBlockIds?: string[] | null;
}
```

- [ ] **Step 2: Modify `Sources.tsx` to render evidence row**

注意守卫策略（v4 review P1 #2 + P2 #5）：

- 标量字段（`pageNumber / pageStart / pageEnd / blockType`）用 `!= null`（同时拒 null 与 undefined）
- 集合字段（`headingPath / sourceBlockIds`）用 `?.length`（同时拒 null / undefined / 空数组），与后端 `@JsonInclude(NON_EMPTY)` 对称

Find the chunk preview render block. Above the preview text, add evidence row:

```tsx
{chunk.pageNumber != null && (
    <span className="text-xs text-muted-foreground mr-2">
        第 {chunk.pageStart != null && chunk.pageEnd != null && chunk.pageStart !== chunk.pageEnd
            ? `${chunk.pageStart}-${chunk.pageEnd}`
            : chunk.pageNumber} 页
    </span>
)}
{chunk.headingPath?.length ? (
    <span className="text-xs text-muted-foreground">
        {chunk.headingPath.join(" › ")}
    </span>
) : null}
{chunk.blockType === "TABLE" && (
    <Badge variant="outline" className="text-xs ml-2">表格</Badge>
)}
```

确保 `Badge` 已 import（如果不在文件顶部，从 `@/components/ui/badge` 加 import）。

- [ ] **Step 3: Verify frontend builds**

```bash
cd frontend && npm run typecheck
```
Expected: 0 errors.

```bash
cd frontend && npm run build
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Manual smoke (BASIC chunk 不显示 evidence 行)**

后端启动：

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap -am spring-boot:run
```

前端 dev server：

```bash
cd frontend && npm run dev
```

- 上传 BASIC PDF → 对话引用该 PDF → SourceChunk 不显示 "第 X 页"
- 上传 ENHANCED PDF → 对话引用该 PDF → SourceChunk 显示 "第 X 页 / 章节 / 段落或表格"

**注意**：完整 happy-path 端到端需要 Docker Postgres + OpenSearch + RustFS + Docling sidecar（Task 23 manual ops smoke 验收）。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/components/chat/Sources.tsx
git commit -m "feat(frontend): SourceChunk shows page / heading / block type evidence (PR 6 / v3 P1 #2)"
```

---

## Phase 8: Documentation

### Task 20: Documentation updates

**Files:**
- Modify: `docs/dev/setup/docling-service.md`
- Modify: `docs/dev/followup/backlog.md`
- Modify: `docs/dev/gotchas.md`

- [ ] **Step 1: `docs/dev/setup/docling-service.md` 加迁移章节**

在文件末尾追加：

```markdown
## 已存在 KB 切换到 ENHANCED 的迁移步骤（PR 6 起）

PR 6 引入 9 个 layout metadata 字段写入 OpenSearch + DB。`OpenSearchVectorStoreAdmin`
的 mapping 定义已扩，但 `ensureVectorSpace` 仅 create-if-missing，**不会自动 patch 已有索引**。

要让一个 PR 6 之前创建的 KB 真正使用 layout 索引（而非 `_source` 仅存不索引的半升级状态），按以下步骤：

1. 后端服务可继续运行；目标 KB 须无正在进行的 ingestion
2. `curl -X DELETE http://localhost:9201/<kb-collection-name>`（NO_PROXY 见 gotcha §7）
3. KB 管理页面把所有文档状态改为"待重新 ingest"，或后端管理工具批量触发
4. 等 RocketMQ chunker 队列消费完毕
5. OS `_mapping` 校验：新索引应含 9 个 layout 字段
6. 抽样检索一份带 page 的 chunk，断言 SourceChunk 6 字段非空

**禁止半升级状态**：不要靠 `_source` 的"看起来字段还在"绕过此流程；未声明 mapping 的字段不可被
term/range filter 命中。
```

- [ ] **Step 2: `docs/dev/followup/backlog.md` 加 4 条 backlog**

在合适章节追加：

```markdown
- **PARSER-PAGE-PERSIST**（PR 6 Non-Goal）— 激活 `t_knowledge_document_page`：写入页文本 +
  `blocks_json` schema 决策 + idempotency 管理；激活 `KnowledgeDocumentPageMapper` 调用；
  为 evidence preview UI PR 准备
- **PARSER-OCR-PIPELINE** — `OcrFallbackParser` 接入：text-layer 质量检测决定走 OCR；OCR-derived
  layout 适配。当前接口零调用，纯占位
- **PARSER-TEXT-LAYER-V0.6+** — Docling sidecar 升级到暴露 `textLayerType / confidence` 后，
  `DoclingResponseAdapter` 适配；DB / OS 列从 NULL 开始有数据
- **PARSER-ENHANCER-LAYOUT-ALIGN** — EnhancerNode-before-StructuredChunkingStrategy 的
  `enhancedText` vs layout 对齐策略
```

- [ ] **Step 3: `docs/dev/gotchas.md` §8 加新条**

在 §8 Parser 域末尾追加：

```markdown
- **PR 6 起 layout 字段双路径写入**（chunk 阶段写 `VectorChunk.metadata` Map → OS catch-all 透传写入索引；
  persist 阶段经 `KnowledgeChunkLayoutMapper.copyToCreateRequest` 抽到 `KnowledgeChunkCreateRequest`
  的 9 字段 → DO → DB）。**DB 与 OS 必须保持双侧一致**。任何从 DB 读 `KnowledgeChunkDO` 重建
  `VectorChunk` 写回 OS 的 re-index 路径（enable/disable/单 chunk 编辑等）必须紧跟
  `KnowledgeChunkLayoutMapper.copyFromDO(do, vc)` 把 9 字段反向回填到 metadata Map，
  否则 OS 端 layout 会丢。Sweep `VectorChunk.builder()` 在 `KnowledgeChunkServiceImpl` /
  `KnowledgeDocumentServiceImpl` 的 5 处调用点（PR 6 已加；新增类似路径必须延续此模式）。
- **手工编辑 chunk content 后 layout 字段处理（PR 6）**：`KnowledgeChunkServiceImpl.update`
  保留 5 个 location 字段（`pageNumber / pageStart / pageEnd / headingPath / blockType` —
  chunk 仍属该页该章节），清空 4 个 extraction 字段（`sourceBlockIds / bboxRefs /
  textLayerType / layoutConfidence` — 已不忠于改写后的文本）。新增手工编辑入口须延续此切分。
```

- [ ] **Step 4: Commit**

```bash
git add docs/dev/setup/docling-service.md docs/dev/followup/backlog.md docs/dev/gotchas.md
git commit -m "docs: PR 6 migration steps + 4 backlog + 2 gotcha rules"
```

---

## Phase 9: Final Integration Verification

### Task 21: Manual ops smoke + integration tests battery

**Files:** (no code; verification only)

- [ ] **Step 1: Run all PR 6 unit + integration tests**

```bash
mvn -pl bootstrap test -Dtest='ChunkLayoutMetadataTest,IngestionChunkingInputTest,StructuredChunkingOptionsResolverTest,LegacyTextChunkingStrategyAdapterTest,StructuredChunkingStrategyTest,IngestionChunkingDispatcherTest,ChunkerNodeDispatchTest,ParserNodeParseResultPropagationTest,KnowledgeChunkLayoutMapperTest,ChunkServiceReindexCallSitesMockTest,ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest,RetrieverLayoutFieldRoundTripIntegrationTest,SourceChunkSerializationTest'
```
Expected: ALL PASS.

- [ ] **Step 2: Run full bootstrap module test suite**

```bash
mvn -pl bootstrap test
```
Expected: PASS（baseline 已知失败由 root CLAUDE.md 列出，本 PR 不引入新失败）。

- [ ] **Step 3: Run formatter check**

```bash
mvn spotless:apply
git status   # verify no unstaged formatter changes
```
Expected: clean working tree。

- [ ] **Step 4: Manual ops smoke 1 — OS mapping forward-only**

启动后端：

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap -am spring-boot:run
```

新建 KB（前端 admin 界面 / API），上传 1 份 ENHANCED PDF：

```bash
curl -X GET 'http://localhost:9201/<new-kb-collection-name>/_mapping' -H 'Accept: application/json' | jq '.metadata.properties | keys'
```
Expected：含 `page_number, page_start, page_end, heading_path, block_type, source_block_ids, bbox_refs, text_layer_type, layout_confidence`。

老 KB（PR 6 之前建）的 `_mapping` 不应含这 9 字段（forward-only 设计）。

- [ ] **Step 5: Manual ops smoke 2 — ENHANCED 上传 + 检索**

ENHANCED 上传一份多页 + 含表格 PDF；触发 RAG 检索；前端 SourceCard 应显示：

- 段落 chunk: "第 X 页 / 章节路径"
- 表格 chunk: "第 X 页 / 章节路径 / [表格] badge"

- [ ] **Step 6: Manual ops smoke 3 — Docling 失败降级**

```bash
docker stop docling
```
ENHANCED 上传同份 PDF；后端日志含 `parse_engine_actual=Tika, parse_fallback_reason=primary_failed`；
DB layout 列 NULL；前端 SourceCard 不显示 evidence 行（与 BASIC 视觉一致）。

- [ ] **Step 7: Manual ops smoke 4 — disable→enable round-trip**

ENHANCED KB 中找一份文档，前端禁用全部 chunks → OS chunks 物理删 → 启用全部 chunks → OS 重建。
检索同份文档，断言 SourceCard 仍显示 layout evidence（与启用前一致）。

- [ ] **Step 8: Manual ops smoke 5 — manual edit chunk content**

进入 chunk 管理页面，编辑某 chunk content；OS 应：

- 5 个 location 字段（pageNumber / pageStart / pageEnd / headingPath / blockType）保持原值
- 4 个 extraction 字段（sourceBlockIds / bboxRefs / textLayerType / layoutConfidence）变 NULL（OS 不索引）

- [ ] **Step 9: Final commit (空 commit 标记完成 + 推送)**

```bash
git commit --allow-empty -m "chore(pr6): all 21 tasks complete + manual smoke green"
```

---

## Self-Review

**1. Spec coverage check**:

| Spec § | Task |
|---|---|
| §1 Goal & Non-Goals | 全部 task 隐含；§1.2 Non-Goal 1 (page 表) Task 12-13 显式不接 mapper |
| §2.1 SourceChunk 6 字段契约 | Task 18 |
| §2.1.1 双侧防御（@JsonInclude + != null） | Task 18 + Task 19 |
| §2.2 value-in-paragraph guard 契约 | spec 文档化，Collateral PR 实现（不在 PR 6） |
| §3 Discovery deltas 12 处 | 各 task 显式处理（Δ1=Task 2, Δ2=Task 16, Δ3=Task 5-9, Δ4=Task 7, Δ5=Task 2-3, Δ6=Task 13, Δ7=Task 17, Δ8=Non-Goal, Δ9=skip, Δ10=Non-Goal, Δ11=Task 9, Δ12=Backlog SL-1）|
| §4.1 触达面 | 全 21 task |
| §4.2 5 条 SoT 约定 | Task 2 注释 |
| §5.1-5.15 component design | Task 1-19 |
| §6 4+1 Data flow | Task 1-19 隐含；Task 21 manual smoke 端到端验证 |
| §7 Persistence Contract | Task 11-15 |
| §8 SourceChunk schema lockdown | Task 18 |
| §9 Frontend changes | Task 19 |
| §10 Test plan 三段 | 各 task 包含 + Task 21 manual smoke |
| §11 Operational notes | Task 20 |
| §12 Plan deviations | spec 内文档化 |
| §13 Backlog 关联 | Task 20 |

**Gap：** 无明显遗漏。Task 17 的 `toRetrievedChunkForTest` helper 是侵入性低的 testability 调整，实施时如发现现有 `toRetrievedChunk` 已适合直接调用，可不引入此 helper。

**2. Placeholder scan**: 无 TBD / TODO / "implement later" / 模糊的"add appropriate error handling"。所有 step 含可执行代码或 maven 命令。

**3. Type consistency**:

- `KnowledgeChunkLayoutMapper.copyFromDO(KnowledgeChunkDO, VectorChunk)` Task 11 / Task 14 一致
- `KnowledgeChunkLayoutMapper.copyToCreateRequest(VectorChunk, KnowledgeChunkCreateRequest)` Task 11 / Task 12 一致
- `IngestionChunkingStrategy.supports(ChunkingMode, IngestionChunkingInput) → boolean` Task 5 / Task 6 / Task 7 / Task 8 一致
- `IngestionChunkingStrategy.priority() → int` Task 5 / Task 6 / Task 7 / Task 8 一致
- `IngestionChunkingDispatcher.chunk(ChunkingMode, IngestionChunkingInput, ChunkingOptions) → List<VectorChunk>` Task 8 / Task 9 一致
- `RetrievedChunk` 加 6 个字段：`Integer pageNumber / Integer pageStart / Integer pageEnd / List<String> headingPath / String blockType / List<String> sourceBlockIds` Task 17 / Task 18 一致
- `SourceChunk` 加同 6 个字段（包名空间不同，Java 类型同）Task 18 一致
- TS interface `pageNumber?: number | null` Task 19 / spec §2.1 / §5.15 一致

**4. Frequent commits**: 21 task → 21+ commits（部分 task 有多 commit）。每个 task 完成后立即 commit。

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-06-pr6-structured-chunker-citation-impl.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — 我每个 task 派一个新 subagent 执行，task 间做两阶段 review，迭代速度快、上下文窗口可控。适合本 PR 这种 21 个 task / 跨 4 层穿透的中大型 feature。

**2. Inline Execution** — 我在当前 session 用 superpowers:executing-plans skill 批量执行，关键 checkpoint 暂停 review。适合更小、更线性的工作。

**选哪个？**
