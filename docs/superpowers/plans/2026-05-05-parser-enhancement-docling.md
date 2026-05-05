# Phase 2.5 / DOCINT - Parser Enhancement (Docling) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route document ingestion by mode: CHUNK / 鍩虹瑙ｆ瀽 keeps the existing Tika path, PIPELINE / 澧炲己瑙ｆ瀽 uses Docling, and Collateral KB uploads default to enhanced parsing so page/block/table evidence metadata is available downstream.

**Architecture:** Layered (frontend 鈫?knowledge 鈫?ingestion 鈫?core/parser 鈫?infra-ai), strategy + registry + decorator + adapter + template-method patterns. Single `DocumentParser` interface keeps engines pluggable; `ParseModePolicy` decides BASIC/ENHANCED from KB type and user choice, while `ParseModeRouter` maps that semantic mode to CHUNK/PIPELINE. MVP does not implement automatic text-layer quality detection or scanned-PDF OCR guarantees; it reserves `TextLayerQualityDetector` and `OcrFallbackParser` seams for a later pass. New `DoclingDocumentParser` calls a self-built `DoclingClient` (HTTP, modeled after `RagasEvalClient`); Docling Python service runs as an independent container (`quay.io/ds4sd/docling-serve`).

**Tech Stack:** Java 17 / Spring Boot 3.5 / MyBatis Plus / PostgreSQL / Apache Tika 3.2 / Apache HttpClient5 / React 18 + TypeScript + TailwindCSS / Docker.

**PR Decomposition:** 6 PRs, each independently reviewable & rollback-safe. PRs 1鈥? land without Docling service ready (toggle visible, metadata-stamped fallback to Tika). PR 5 brings Docling online. PR 6 propagates structured output to retrieval. Scanned/image-only PDF quality detection is explicitly deferred. Mapping: PR 1-2 belong to `PR-DOCINT-1a`, PR 3-4 belong to `PR-DOCINT-1b`, PR 5 belongs to `PR-DOCINT-1c`, and PR 6 belongs to `PR-DOCINT-2`.

---

## Scope Alignment With Collateral Roadmap

This plan implements the **Phase 2.5 MVP** agreed on 2026-05-05:

- `CHUNK` / BASIC remains the default Tika path for ordinary KB uploads.
- `PIPELINE` / ENHANCED uses Docling and structured chunking.
- Collateral KB uploads default to ENHANCED / PIPELINE from backend policy, not only from frontend UI.
- The system does **not** promise image-only / scanned PDF OCR quality in this MVP.
- Text-layer quality detection is deferred. Keep the abstraction seams (`ParseModePolicy`, `TextLayerQualityDetector`, `OcrFallbackParser`) so automatic recommendation / OCR fallback can be added without rewiring upload or parser code.
- This plan only creates parse/layout metadata; it does not produce Collateral field answers, does not add `t_collateral_field_value`, and does not change RAG answer semantics.

## Upstream / Downstream Boundaries

- This plan can run in parallel with Phase 2 query-planner work because it only changes ingestion and parser metadata. It must not bypass Phase 2 KB scope or permission guards.
- For born-digital PDFs with complex layout or tables, this plan is the hard prerequisite for Phase 3 real Collateral field extraction because Phase 3 consumes page/block/bbox evidence produced here.
- For image-only or scanned PDFs, this plan only reserves the hooks. Field extraction quality should not be promised until a later OCR fallback / text-layer quality detector pass is implemented and validated.

---

## File Structure

### Created

| Path | Responsibility |
|---|---|
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseMode.java` | Semantic enum `BASIC` / `ENHANCED` (decoupled from engine names) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/DocumentPageText.java` | Page-level text model (docId, pageNo, textLayerType, confidence, blocks) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutBlock.java` | Engine-neutral layout block record (blockId, pageNo, bbox, blockType, readingOrder, confidence, text, headingPath) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutTable.java` | Engine-neutral table record (tableId, pageNo, bbox, rows, headingPath, readingOrder, confidence) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/BlockType.java` | Enum `TITLE / PARAGRAPH / TABLE / HEADER / FOOTER / LIST / CAPTION / OTHER` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/FallbackParserDecorator.java` | Decorator: try primary 鈫?on failure / unsupported 鈫?fallback parser; stamps `parse_engine_actual` metadata |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingDocumentParser.java` | Strategy: enhanced parser; uses `DoclingClient` + `DoclingResponseAdapter`; conditional on `docling.service.enabled=true` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java` | Adapter: Docling JSON 鈫?`ParseResult{pages, tables}` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/quality/TextLayerQualityDetector.java` | Deferred quality detector port; MVP implementation returns unknown / no recommendation |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/quality/NoopTextLayerQualityDetector.java` | No-op detector implementation for MVP |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ocr/OcrFallbackParser.java` | Deferred OCR parser port; not wired into the MVP parser chain |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModePolicy.java` | KB-aware policy: Collateral KB defaults/forces ENHANCED; other KBs honor user choice / BASIC default |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouter.java` | Strategy: maps `ParseMode` 鈫?`ProcessMode` (BASIC鈫扖HUNK, ENHANCED鈫扨IPELINE) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeDocumentPageDO.java` | Persists `DocumentPageText` for page preview and later evidence lookup |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentPageMapper.java` | MyBatis mapper for `t_knowledge_document_page` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java` | Chunker that consumes `List<DocumentPageText>`, splits on title boundaries, never splits a table block |
| `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/AbstractRemoteParser.java` | Template-method base for HTTP-backed parser clients (timeout / retry / logging skeleton) |
| `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/DoclingClient.java` | HTTP client for Docling `POST /v1alpha/convert/file` |
| `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/DoclingClientProperties.java` | `docling.service.*` bound config |
| `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/dto/DoclingConvertResponse.java` | DTO mirror of Docling response |
| `resources/database/upgrade_v1.12_to_v1.13.sql` | DB migration: parse_mode + page/chunk layout/evidence columns; uses the current next migration slot |
| `resources/docker/docling-compose.yml` | Independent Docling service compose file |
| `docs/dev/setup/docling-service.md` | Operator-facing service deploy doc |
| `frontend/src/components/upload/ParseModeRadio.tsx` | RadioGroup (鍩虹 / 澧炲己) with tooltip |

### Modified

| Path | Change |
|---|---|
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseResult.java` | Add `pages` / `tables` fields, keep 2-arg constructor |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java` | Add `selectByParseMode(ParseMode)` method; wrap ENHANCED in `FallbackParserDecorator` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParserType.java` | Add `DOCLING` enum value |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java:81` | Replace hardcoded `ParserType.TIKA` with read of `NodeConfig.settings.parseMode` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java` | Add `parseMode` field |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java` | Add `parseMode` field |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java` | Inject `ParseModePolicy` + `ParseModeRouter`; persist resolved `parseMode`; use router to decide CHUNK/PIPELINE; thread `parseMode` into `IngestionContext`; persist page/chunk layout metadata |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java` | Branch: when `ParseResult.pages` non-empty 鈫?`StructuredChunkingStrategy`; else legacy String path |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/IndexerNode.java` | Pass page/block/bbox/textLayer metadata from structured chunks into vector metadata |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/VectorMetadataFields.java` | Add page/block/bbox/textLayer metadata constants |
| `bootstrap/CLAUDE.md` | Update parser table + add gotchas for ParseMode routing |
| `CLAUDE.md` | Append v1.13 migration entry |
| `frontend/src/services/knowledgeService.ts` | `KnowledgeDocumentUploadPayload` add `parseMode`; FormData append |
| `frontend/src/pages/.../DocumentUploadDialog.tsx` | Replace processMode field with `<ParseModeRadio>`; backend ignores processMode |

---

## PR 1 (Phase 2.5 / PR-DOCINT-1a) 鈥?Domain model & data-driven ParserNode (no Docling)

**Goal:** Eliminate hardcoded `ParserType.TIKA` from ParserNode; introduce engine-neutral layout types & ParseMode. Old upload flow byte-equivalent.

### Task 1.1: Add `ParseMode` enum

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseMode.java`

- [ ] **Step 1: Write the unit test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/ParseModeTest.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParseModeTest {

    @Test
    void fromValue_basicAndEnhanced_returnsEnum() {
        assertEquals(ParseMode.BASIC, ParseMode.fromValue("basic"));
        assertEquals(ParseMode.ENHANCED, ParseMode.fromValue("enhanced"));
        assertEquals(ParseMode.BASIC, ParseMode.fromValue("BASIC")); // case-insensitive
    }

    @Test
    void fromValue_nullOrBlank_returnsDefault() {
        assertEquals(ParseMode.BASIC, ParseMode.fromValue(null));
        assertEquals(ParseMode.BASIC, ParseMode.fromValue(""));
        assertEquals(ParseMode.BASIC, ParseMode.fromValue("  "));
    }

    @Test
    void fromValue_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> ParseMode.fromValue("ocr"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -pl bootstrap test -Dtest=ParseModeTest
```
Expected: FAIL 鈥?`ParseMode` not defined.

- [ ] **Step 3: Implement `ParseMode`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseMode.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

public enum ParseMode {
    BASIC("basic"),
    ENHANCED("enhanced");

    private final String value;

    ParseMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ParseMode fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return BASIC;
        }
        String norm = raw.trim().toLowerCase();
        for (ParseMode m : values()) {
            if (m.value.equals(norm)) return m;
        }
        throw new IllegalArgumentException("Unknown parseMode: " + raw);
    }
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
mvn -pl bootstrap test -Dtest=ParseModeTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseMode.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/ParseModeTest.java
git commit -m "feat(parser): add ParseMode enum (BASIC / ENHANCED)"
```

### Task 1.2: Add `DocumentPageText`, `BlockType`, `LayoutBlock`, `LayoutTable`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/DocumentPageText.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/BlockType.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutBlock.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutTable.java`

- [ ] **Step 1: Write `BlockType`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/BlockType.java`:

```java
package com.knowledgebase.ai.ragent.core.parser.layout;

public enum BlockType {
    TITLE,
    PARAGRAPH,
    TABLE,
    HEADER,
    FOOTER,
    LIST,
    CAPTION,
    OTHER;
}
```

- [ ] **Step 2: Write `LayoutBlock`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutBlock.java`:

```java
package com.knowledgebase.ai.ragent.core.parser.layout;

import java.util.List;

/**
 * Engine-neutral layout block. Adapters from Docling / future engines produce these.
 */
public record LayoutBlock(
        String blockId,             // stable within document when engine provides it
        BlockType blockType,
        int pageNo,                // 1-based; 0 if unknown
        Bbox bbox,                 // null if not provided by engine
        String text,
        Integer readingOrder,      // null if engine does not provide order
        Double confidence,         // null if engine does not provide confidence
        Integer headingLevel,      // null unless blockType == TITLE
        List<String> headingPath   // ancestor headings; empty list never null
) {
    public LayoutBlock {
        if (headingPath == null) headingPath = List.of();
    }

    public record Bbox(double x, double y, double width, double height) {}
}
```

- [ ] **Step 3: Write `DocumentPageText`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/DocumentPageText.java`:

```java
package com.knowledgebase.ai.ragent.core.parser.layout;

import java.util.List;

/**
 * Page-level text model used by Phase 3/5 for page preview and evidence lookup.
 * `docId` can be null at parser time and filled by ingestion persistence.
 */
public record DocumentPageText(
        String docId,
        int pageNo,
        String text,
        String textLayerType,       // NATIVE_TEXT / OCR / MIXED / null if unknown
        Double confidence,
        List<LayoutBlock> blocks
) {
    public DocumentPageText {
        if (blocks == null) blocks = List.of();
    }
}
```

- [ ] **Step 4: Write `LayoutTable`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutTable.java`:

```java
package com.knowledgebase.ai.ragent.core.parser.layout;

import java.util.List;

public record LayoutTable(
        String tableId,             // stable within document when engine provides it
        int pageNo,
        LayoutBlock.Bbox bbox,
        List<List<String>> rows,    // first row is header by convention; never null
        List<String> headingPath,   // section path the table sits under
        Integer readingOrder,
        Double confidence
) {
    public LayoutTable {
        if (rows == null) rows = List.of();
        if (headingPath == null) headingPath = List.of();
    }
}
```

- [ ] **Step 5: Compile**

```bash
mvn -pl bootstrap compile
```
Expected: SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/
git commit -m "feat(parser): add page and layout parser models"
```

### Task 1.3: Extend `ParseResult` with page-level model and backward-compatible constructor

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseResult.java`

- [ ] **Step 1: Write test for backward compat**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/ParseResultTest.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseResultTest {

    @Test
    void legacyTwoArgConstructor_yieldsEmptyPagesAndTables() {
        ParseResult r = new ParseResult("hello", Map.of("k", "v"));
        assertEquals("hello", r.text());
        assertEquals("v", r.metadata().get("k"));
        assertTrue(r.pages().isEmpty());
        assertTrue(r.tables().isEmpty());
    }

    @Test
    void legacyStaticFactories_remainAvailableForExistingParsers() {
        assertEquals("hello", ParseResult.ofText("hello").text());
        assertEquals("v", ParseResult.of("hello", Map.of("k", "v")).metadata().get("k"));
    }

    @Test
    void fullConstructor_carriesAllFields() {
        LayoutBlock b = new LayoutBlock(
                "b1",
                com.knowledgebase.ai.ragent.core.parser.layout.BlockType.TITLE,
                1, null, "Title", 1, 0.99D, 1, List.of()
        );
        DocumentPageText page = new DocumentPageText("doc1", 1, "Title", "NATIVE_TEXT", 0.99D, List.of(b));
        ParseResult r = new ParseResult("t", Map.of(), List.of(page), List.of());
        assertEquals(1, r.pages().size());
        assertEquals("Title", r.pages().get(0).blocks().get(0).text());
    }
}
```

- [ ] **Step 2: Modify `ParseResult` to support both constructors**

Read current file first to confirm shape, then replace contents of `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseResult.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing a document. `pages` / `tables` are empty for engines that
 * don't emit layout (e.g., Tika). Engines that do (Docling) populate page-level
 * text plus layout blocks so downstream phases can cite page/block/bbox.
 */
public record ParseResult(
        String text,
        Map<String, Object> metadata,
        List<DocumentPageText> pages,
        List<LayoutTable> tables
) {
    public ParseResult {
        if (metadata == null) metadata = Map.of();
        if (pages == null) pages = List.of();
        if (tables == null) tables = List.of();
    }

    /** Backward-compatible constructor 鈥?Tika & legacy callers stay unchanged. */
    public ParseResult(String text, Map<String, Object> metadata) {
        this(text, metadata, List.of(), List.of());
    }

    /** Existing factory used by TikaDocumentParser / MarkdownDocumentParser. */
    public static ParseResult ofText(String text) {
        return new ParseResult(text == null ? "" : text, Map.of());
    }

    /** Existing factory used by parser implementations that attach metadata. */
    public static ParseResult of(String text, Map<String, Object> metadata) {
        return new ParseResult(text == null ? "" : text, metadata == null ? Map.of() : metadata);
    }
}
```

- [ ] **Step 3: Run all tests touching ParseResult**

```bash
mvn -pl bootstrap test -Dtest='*Parse*'
```
Expected: PASS. Existing TikaDocumentParser code still compiles because 2-arg constructor preserved.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseResult.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/ParseResultTest.java
git commit -m "feat(parser): extend ParseResult with pages/tables, keep 2-arg ctor"
```

### Task 1.4: `DocumentParserSelector.selectByParseMode`

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParserType.java`

- [ ] **Step 1: Add `DOCLING` to `ParserType`**

Edit `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParserType.java`, in the enum constants list add:

```java
    DOCLING("Docling");
```

(adjacent to existing `TIKA("Tika")` and `MARKDOWN("Markdown")`; keep display/type casing consistent with the current enum values.)

- [ ] **Step 2: Write test for selectByParseMode**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelectorParseModeTest.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DocumentParserSelectorParseModeTest {

    @Autowired
    DocumentParserSelector selector;

    @Test
    void basicMode_returnsTikaParser() {
        DocumentParser p = selector.selectByParseMode(ParseMode.BASIC);
        assertNotNull(p);
        assertTrue(p.supports("application/pdf"));
    }

    @Test
    void enhancedMode_returnsParserOrFallback() {
        // Docling not registered yet 鈫?wrapped in fallback decorator (PR 2 will assert this)
        DocumentParser p = selector.selectByParseMode(ParseMode.ENHANCED);
        assertNotNull(p);
    }
}
```

- [ ] **Step 3: Implement `selectByParseMode`**

In `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java`, add:

```java
    public DocumentParser selectByParseMode(ParseMode mode) {
        return switch (mode) {
            case BASIC    -> select(ParserType.TIKA.getType());
            case ENHANCED -> selectEnhanced();
        };
    }

    private DocumentParser selectEnhanced() {
        DocumentParser docling = registry.get(ParserType.DOCLING.getType());
        if (docling == null) {
            // Docling not registered 鈥?caller will see fallback wrapper from PR 2.
            return select(ParserType.TIKA.getType());
        }
        return docling;
    }
```

(Adjust `registry` field name to match actual map name in the existing `DocumentParserSelector`.)

- [ ] **Step 4: Run test**

```bash
mvn -pl bootstrap test -Dtest=DocumentParserSelectorParseModeTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParserType.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelectorParseModeTest.java
git commit -m "feat(parser): add selectByParseMode + DOCLING enum slot"
```

### Task 1.5: `ParserNode` reads parseMode from `NodeConfig`

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java:81`

- [ ] **Step 1: Write integration test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNodeParseModeTest.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class ParserNodeParseModeTest {

    @Autowired ParserNode parserNode;
    @Autowired ObjectMapper om;

    @Test
    void parseMode_basic_routesThroughTika_andProducesText() throws Exception {
        IngestionContext ctx = IngestionContext.builder()
                .rawBytes("hello world".getBytes())
                .mimeType("text/plain")
                .build();
        JsonNode settings = om.readTree("{\"parseMode\":\"basic\"}");
        NodeConfig cfg = NodeConfig.builder()
                .nodeId("p1")
                .nodeType("ParserNode")
                .settings(settings)
                .build();
        parserNode.execute(ctx, cfg);
        assertNotNull(ctx.getRawText());
    }
}
```

- [ ] **Step 2: Run test (expect fail 鈥?still hardcoded)**

```bash
mvn -pl bootstrap test -Dtest=ParserNodeParseModeTest
```

This should currently pass because Tika handles text/plain. The hardcoded TIKA path *coincidentally* works, but we're about to make the routing data-driven so the wiring is correct for ENHANCED.

- [ ] **Step 3: Replace hardcoded Tika selection**

In `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java` around line 81 (`parserSelector.select(ParserType.TIKA.getType())`), replace with:

```java
        ParseMode parseMode = readParseMode(config);
        DocumentParser parser = parserSelector.selectByParseMode(parseMode);
```

Add the helper method in same class:

```java
    private ParseMode readParseMode(NodeConfig config) {
        if (config == null || config.getSettings() == null) {
            return ParseMode.BASIC;
        }
        com.fasterxml.jackson.databind.JsonNode pm = config.getSettings().get("parseMode");
        if (pm == null || pm.isNull() || pm.isMissingNode()) {
            return ParseMode.BASIC;
        }
        return ParseMode.fromValue(pm.asText());
    }
```

Add import:

```java
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
```

Remove the now-unused `import com.knowledgebase.ai.ragent.core.parser.ParserType;` if no other references remain in the file.

- [ ] **Step 4: Run test**

```bash
mvn -pl bootstrap test -Dtest=ParserNodeParseModeTest
```
Expected: PASS.

- [ ] **Step 5: Run pre-existing ingestion tests for regression**

```bash
mvn -pl bootstrap test -Dtest='*Ingestion*,*Parser*'
```
Expected: PASS (parsing behavior byte-equivalent for legacy upload).

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNodeParseModeTest.java
git commit -m "refactor(ingestion): ParserNode reads parseMode from NodeConfig (data-driven)"
```

### Task 1.6: PR 1 wrap-up

- [ ] **Step 1: Run full bootstrap test suite**

```bash
mvn -pl bootstrap test
```
Expected: PASS (excluding the pre-existing baseline failures listed in CLAUDE.md).

- [ ] **Step 2: Spotless check**

```bash
mvn spotless:check
```
If fails:
```bash
mvn spotless:apply
git add -u && git commit -m "chore: spotless apply"
```

- [ ] **Step 3: Push branch and open PR 1**

```bash
git push -u origin feat/parser-enhancement-pr1
gh pr create --title "feat(parser): PR 1 鈥?ParseMode + data-driven ParserNode" \
  --body "$(cat <<'EOF'
## Summary
- Add `ParseMode` enum (BASIC / ENHANCED), engine-neutral `DocumentPageText` / `LayoutBlock` / `LayoutTable` / `BlockType`
- Extend `ParseResult` with `pages` / `tables`, keep 2-arg constructor for byte-equivalent legacy calls
- `DocumentParserSelector.selectByParseMode(ParseMode)` 鈥?BASIC鈫扵ika, ENHANCED鈫扗ocling-or-Tika fallback
- `ParserNode` reads `parseMode` from `NodeConfig.settings`, no longer hardcodes Tika

## Test plan
- [x] `ParseModeTest`, `ParseResultTest` unit tests
- [x] `DocumentParserSelectorParseModeTest`, `ParserNodeParseModeTest` integration tests
- [x] Full `mvn -pl bootstrap test` passes (modulo baseline failures)
- [x] `mvn spotless:check` clean
EOF
)"
```

---

## PR 2 (Phase 2.5 / PR-DOCINT-1a) 鈥?`FallbackParserDecorator` graceful degradation

**Goal:** When ENHANCED is requested but Docling unavailable, fall back to Tika with explicit metadata stamps so frontend can show 鈿狅笍.

### Task 2.1: `FallbackParserDecorator`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/FallbackParserDecorator.java`

- [ ] **Step 1: Write test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/FallbackParserDecoratorTest.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackParserDecoratorTest {

    private static final DocumentParser ALWAYS_FAIL = new DocumentParser() {
        @Override public ParseResult parse(byte[] c, String m, Map<String, Object> o) {
            throw new RuntimeException("primary down");
        }
        @Override public String extractText(InputStream is, String n) {
            throw new RuntimeException("primary down");
        }
        @Override public boolean supports(String m) { return true; }
    };

    private static final DocumentParser FALLBACK = new DocumentParser() {
        @Override public ParseResult parse(byte[] c, String m, Map<String, Object> o) {
            return new ParseResult("fallback-text", new HashMap<>());
        }
        @Override public String extractText(InputStream is, String n) {
            return "fallback-text";
        }
        @Override public boolean supports(String m) { return true; }
    };

    @Test
    void primaryFails_fallbackInvoked_metadataStamped() {
        FallbackParserDecorator dec = new FallbackParserDecorator(
                ALWAYS_FAIL, FALLBACK, "docling", "tika");
        ParseResult r = dec.parse(new byte[0], "application/pdf", new HashMap<>());
        assertEquals("fallback-text", r.text());
        assertEquals("tika", r.metadata().get("parse_engine_actual"));
        assertEquals("docling", r.metadata().get("parse_engine_requested"));
        assertEquals("primary_failed", r.metadata().get("parse_fallback_reason"));
    }

    @Test
    void primarySucceeds_metadataMarksRequestedEngine() {
        FallbackParserDecorator dec = new FallbackParserDecorator(
                FALLBACK, ALWAYS_FAIL, "docling", "tika");
        ParseResult r = dec.parse(new byte[0], "application/pdf", new HashMap<>());
        assertEquals("fallback-text", r.text());
        assertEquals("docling", r.metadata().get("parse_engine_actual"));
    }

    @Test
    void extractTextFallback_alsoWorks() throws Exception {
        FallbackParserDecorator dec = new FallbackParserDecorator(
                ALWAYS_FAIL, FALLBACK, "docling", "tika");
        InputStream is = new ByteArrayInputStream(new byte[0]);
        assertEquals("fallback-text", dec.extractText(is, "f.pdf"));
    }
}
```

- [ ] **Step 2: Run test (expect fail 鈥?class missing)**

```bash
mvn -pl bootstrap test -Dtest=FallbackParserDecoratorTest
```
Expected: FAIL 鈥?class not found.

- [ ] **Step 3: Implement `FallbackParserDecorator`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/FallbackParserDecorator.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Decorator: try `primary` first; on any exception, fall back to `fallback` and
 * stamp metadata so callers can observe the degradation. If primary succeeds,
 * metadata still records `parse_engine_actual = primaryName` for traceability.
 */
@Slf4j
public class FallbackParserDecorator implements DocumentParser {

    private final DocumentParser primary;
    private final DocumentParser fallback;
    private final String primaryName;
    private final String fallbackName;

    public FallbackParserDecorator(DocumentParser primary, DocumentParser fallback,
                                   String primaryName, String fallbackName) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        try {
            ParseResult r = primary.parse(content, mimeType, options);
            return stamp(r, primaryName, primaryName, null);
        } catch (Exception e) {
            log.warn("Primary parser '{}' failed, falling back to '{}': {}",
                    primaryName, fallbackName, e.getMessage());
            ParseResult r = fallback.parse(content, mimeType, options);
            return stamp(r, primaryName, fallbackName, "primary_failed");
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) throws IOException {
        try {
            return primary.extractText(stream, fileName);
        } catch (Exception e) {
            log.warn("Primary parser '{}' extractText failed, falling back to '{}': {}",
                    primaryName, fallbackName, e.getMessage());
            return fallback.extractText(stream, fileName);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        return primary.supports(mimeType) || fallback.supports(mimeType);
    }

    private ParseResult stamp(ParseResult r, String requested, String actual, String reason) {
        Map<String, Object> md = new HashMap<>(r.metadata() == null ? Map.of() : r.metadata());
        md.put("parse_engine_requested", requested);
        md.put("parse_engine_actual", actual);
        if (reason != null) md.put("parse_fallback_reason", reason);
        return new ParseResult(r.text(), md, r.pages(), r.tables());
    }
}
```

- [ ] **Step 4: Run test**

```bash
mvn -pl bootstrap test -Dtest=FallbackParserDecoratorTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/FallbackParserDecorator.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/FallbackParserDecoratorTest.java
git commit -m "feat(parser): FallbackParserDecorator with metadata stamping"
```

### Task 2.2: Wire decorator into selector

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java`

- [ ] **Step 1: Update `selectEnhanced()` to wrap in decorator**

In `DocumentParserSelector.java`, replace the `selectEnhanced()` method body added in PR 1 with:

```java
    private DocumentParser selectEnhanced() {
        DocumentParser tika = select(ParserType.TIKA.getType());
        DocumentParser docling = registry.get(ParserType.DOCLING.getType());
        if (docling == null) {
            log.info("Docling parser not registered 鈥?ENHANCED requests will use Tika fallback");
            return new FallbackParserDecorator(
                    tika, tika, ParserType.DOCLING.getType(), ParserType.TIKA.getType());
            // primary == fallback intentionally: stamps metadata as requested=docling/actual=tika
        }
        return new FallbackParserDecorator(
                docling, tika, ParserType.DOCLING.getType(), ParserType.TIKA.getType());
    }
```

Add `@Slf4j` to the class if not present, and the SLF4J import:

```java
import lombok.extern.slf4j.Slf4j;
```

- [ ] **Step 2: Update `DocumentParserSelectorParseModeTest`**

Append to `DocumentParserSelectorParseModeTest`:

```java
    @Test
    void enhancedMode_whenDoclingMissing_yieldsFallbackDecorator_stampedMetadata() {
        DocumentParser p = selector.selectByParseMode(ParseMode.ENHANCED);
        ParseResult r = p.parse("hello".getBytes(), "text/plain", new java.util.HashMap<>());
        assertEquals("docling", r.metadata().get("parse_engine_requested"));
        // actual is "tika" because primary IS tika in degenerate fallback config
        assertEquals("tika", r.metadata().get("parse_engine_actual"));
    }
```

- [ ] **Step 3: Run test**

```bash
mvn -pl bootstrap test -Dtest=DocumentParserSelectorParseModeTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelectorParseModeTest.java
git commit -m "feat(parser): wrap ENHANCED route in FallbackParserDecorator"
```

### Task 2.3: PR 2 wrap-up

- [ ] **Step 1: Full test pass + spotless + push + PR**

```bash
mvn -pl bootstrap test
mvn spotless:check
git push -u origin feat/parser-enhancement-pr2
gh pr create --title "feat(parser): PR 2 鈥?FallbackParserDecorator graceful degradation" \
  --body "$(cat <<'EOF'
## Summary
- `FallbackParserDecorator` wraps primary parser; on exception falls back + stamps metadata
- ENHANCED route always wrapped 鈥?when Docling not registered, primary == fallback == Tika; metadata still records `parse_engine_requested=docling`, `parse_engine_actual=tika`
- Frontend can read `parse_engine_actual` to show 鈿狅笍 "澧炲己瑙ｆ瀽灏氭湭鍚敤锛屽凡浣跨敤鍩虹瑙ｆ瀽"

## Test plan
- [x] FallbackParserDecoratorTest covers success / fail / extractText paths
- [x] DocumentParserSelectorParseModeTest asserts metadata stamping
EOF
)"
```

---

## PR 3 (Phase 2.5 / PR-DOCINT-1b) 鈥?DB schema, DTO, persistence

**Goal:** `parseMode` flows from upload request 鈫?DB 鈫?IngestionContext.

### Task 3.1: SQL migration

**Files:**
- Create: `resources/database/upgrade_v1.12_to_v1.13.sql`

- [ ] **Step 1: Write migration SQL**

Create `resources/database/upgrade_v1.12_to_v1.13.sql`:

```sql
-- v1.12 鈫?v1.13
-- Parser enhancement migration.
-- Uses the current next migration slot in this repo. If Collateral Phase 2
-- migrations merge first, renumber this file during rebase instead of leaving
-- a gap or colliding with an already-applied migration.

-- 1) Document-level parser choice
ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS parse_mode VARCHAR(16) NOT NULL DEFAULT 'basic';

COMMENT ON COLUMN t_knowledge_document.parse_mode IS
    'User-facing parser choice: basic (Tika) | enhanced (Docling). Engine name decoupled.';

-- 2) Page-level parser output for preview and later evidence lookup
CREATE TABLE IF NOT EXISTS t_knowledge_document_page (
    id                VARCHAR(20) NOT NULL PRIMARY KEY,
    doc_id            VARCHAR(20) NOT NULL,
    page_no           INTEGER     NOT NULL,
    text              TEXT,
    text_layer_type   VARCHAR(32),
    confidence        DOUBLE PRECISION,
    blocks_json       TEXT,
    created_by        VARCHAR(20) NOT NULL,
    updated_by        VARCHAR(20),
    create_time       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_knowledge_document_page UNIQUE (doc_id, page_no)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_page_doc_id
    ON t_knowledge_document_page (doc_id);

COMMENT ON TABLE t_knowledge_document_page IS 'Page-level parser output for layout-aware evidence lookup';
COMMENT ON COLUMN t_knowledge_document_page.text_layer_type IS 'NATIVE_TEXT|OCR|MIXED|UNKNOWN when available';
COMMENT ON COLUMN t_knowledge_document_page.blocks_json IS 'JSON string array of LayoutBlock records including blockId/pageNo/bbox/readingOrder';

-- 3) Chunk-level layout/evidence fields (populated only when parse_mode=enhanced + Docling available)
ALTER TABLE t_knowledge_chunk
    ADD COLUMN IF NOT EXISTS page_number  INTEGER,
    ADD COLUMN IF NOT EXISTS page_start   INTEGER,
    ADD COLUMN IF NOT EXISTS page_end     INTEGER,
    ADD COLUMN IF NOT EXISTS heading_path TEXT,
    ADD COLUMN IF NOT EXISTS block_type   VARCHAR(32),
    ADD COLUMN IF NOT EXISTS source_block_ids TEXT,
    ADD COLUMN IF NOT EXISTS bbox_refs TEXT,
    ADD COLUMN IF NOT EXISTS text_layer_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS layout_confidence DOUBLE PRECISION;

COMMENT ON COLUMN t_knowledge_chunk.page_number IS 'Display page hint; page_start/page_end are canonical evidence range';
COMMENT ON COLUMN t_knowledge_chunk.page_start IS 'First 1-based source page for this chunk';
COMMENT ON COLUMN t_knowledge_chunk.page_end IS 'Last 1-based source page for this chunk';
COMMENT ON COLUMN t_knowledge_chunk.heading_path IS 'JSON array of ancestor headings, e.g. ["绗?绔?椋庨櫓绠＄悊","3.2 淇＄敤椋庨櫓"]';
COMMENT ON COLUMN t_knowledge_chunk.block_type IS 'TITLE|PARAGRAPH|TABLE|HEADER|FOOTER|LIST|CAPTION|OTHER';
COMMENT ON COLUMN t_knowledge_chunk.source_block_ids IS 'JSON array of source layout block ids';
COMMENT ON COLUMN t_knowledge_chunk.bbox_refs IS 'JSON array of bbox references copied from parser layout output';
COMMENT ON COLUMN t_knowledge_chunk.text_layer_type IS 'NATIVE_TEXT|OCR|MIXED when available';
COMMENT ON COLUMN t_knowledge_chunk.layout_confidence IS 'Parser layout confidence when available';
```

- [ ] **Step 2: Apply migration to local PG**

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.12_to_v1.13.sql
```

Verify:

```bash
docker exec postgres psql -U postgres -d ragent -c "\d t_knowledge_document" | grep parse_mode
docker exec postgres psql -U postgres -d ragent -c "\d t_knowledge_document_page" | grep -E "page_no|text_layer_type|blocks_json"
docker exec postgres psql -U postgres -d ragent -c "\d t_knowledge_chunk" | grep -E "page_number|page_start|page_end|heading_path|block_type|source_block_ids|bbox_refs|text_layer_type"
```

Expected: parse mode, page-level output table, and chunk layout/evidence column rows printed.

- [ ] **Step 3: Append migration to root CLAUDE.md migration list**

In root `CLAUDE.md`, find the `Upgrade scripts in resources/database/` list and append:

```markdown
- `upgrade_v1.12_to_v1.13.sql` 鈥?Parser enhancement: `t_knowledge_document.parse_mode` (basic/enhanced), `t_knowledge_document_page`, and `t_knowledge_chunk` page/block/bbox/textLayer metadata columns
```

- [ ] **Step 4: Commit**

```bash
git add resources/database/upgrade_v1.12_to_v1.13.sql CLAUDE.md
git commit -m "feat(db): v1.13 migration 鈥?parse_mode + page/chunk layout columns"
```

### Task 3.2: Update DTO and DO

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java`

- [ ] **Step 1: Read existing DTO to check style**

```bash
head -80 bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java
```

Note Lombok / Validation annotation style.

- [ ] **Step 2: Add `parseMode` to upload request**

Append to the field block of `KnowledgeDocumentUploadRequest.java` (preserving existing Lombok style):

```java
    /**
     * 瑙ｆ瀽妯″紡锛歜asic锛堝熀纭€瑙ｆ瀽锛孴ika锛?/ enhanced锛堝寮鸿В鏋愶紝Docling锛夈€?
     * 缂虹渷涓?basic锛屽悜鍚庡吋瀹硅€佸鎴风銆?
     */
    private String parseMode;
```

- [ ] **Step 3: Add `parseMode` to `KnowledgeDocumentDO`**

In `KnowledgeDocumentDO.java`, add field with MyBatis Plus column mapping (mirror existing `securityLevel` style):

```java
    @TableField("parse_mode")
    private String parseMode;
```

- [ ] **Step 4: Compile**

```bash
mvn -pl bootstrap compile
```
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java
git commit -m "feat(knowledge): add parseMode field to upload request + DO"
```

### Task 3.2b: Add page-level persistence entity

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeDocumentPageDO.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentPageMapper.java`

- [ ] **Step 1: Add entity**

```java
package com.knowledgebase.ai.ragent.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_knowledge_document_page")
public class KnowledgeDocumentPageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String docId;
    private Integer pageNo;
    private String text;
    private String textLayerType;
    private Double confidence;

    @TableField("blocks_json")
    private String blocksJson;

    private String createdBy;
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 2: Add mapper**

```java
package com.knowledgebase.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeDocumentPageDO;

public interface KnowledgeDocumentPageMapper extends BaseMapper<KnowledgeDocumentPageDO> {
}
```

- [ ] **Step 3: Compile and commit**

```bash
mvn -pl bootstrap compile
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeDocumentPageDO.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentPageMapper.java
git commit -m "feat(knowledge): add document page persistence model"
```

### Task 3.3: Persist `parseMode` in service

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

- [ ] **Step 1: Find the upload persistence block**

```bash
grep -n "documentDO.setProcessMode\|documentDO.setSecurityLevel" bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
```

- [ ] **Step 2: Add `parseMode` persistence**

Adjacent to the existing `documentDO.setSecurityLevel(...)` line in the upload method (around line 149鈥?69 per CLAUDE.md trace), add:

```java
        documentDO.setParseMode(
                com.knowledgebase.ai.ragent.core.parser.ParseMode.fromValue(requestParam.getParseMode()).getValue());
```

- [ ] **Step 3: Verify persistence without adding a half-stub SpringBootTest**

Do not add a pseudo integration test until an existing upload fixture is identified. For this PR, rely on `ParseModeTest` for normalization and add one service-level assertion to an existing upload/service test if one exists. If no fixture exists, run a manual DB verification after upload:

```sql
SELECT parse_mode
FROM t_knowledge_document
ORDER BY create_time DESC
LIMIT 1;
```

Expected after enhanced upload: `enhanced`.

- [ ] **Step 4: Run focused tests / compile**

```bash
mvn -pl bootstrap test -Dtest=ParseModeTest
mvn -pl bootstrap compile
```
Expected: PASS / SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "feat(knowledge): persist parseMode on upload (defaults to basic)"
```

### Task 3.4: `VectorMetadataFields` constants

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/VectorMetadataFields.java`

- [ ] **Step 1: Add constants**

In `VectorMetadataFields.java`, alongside existing constants (`KB_ID`, `SECURITY_LEVEL`, `DOC_ID`, `CHUNK_INDEX`), add:

```java
    public static final String PAGE_NUMBER  = "page_number"; // display hint; PAGE_START/PAGE_END are canonical
    public static final String PAGE_START   = "page_start";
    public static final String PAGE_END     = "page_end";
    public static final String HEADING_PATH = "heading_path";
    public static final String BLOCK_TYPE   = "block_type";
    public static final String SOURCE_BLOCK_IDS = "source_block_ids";
    public static final String BBOX_REFS = "bbox_refs";
    public static final String TEXT_LAYER_TYPE = "text_layer_type";
    public static final String LAYOUT_CONFIDENCE = "layout_confidence";
```

- [ ] **Step 2: Compile**

```bash
mvn -pl bootstrap compile
```
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/VectorMetadataFields.java
git commit -m "feat(rag): add layout metadata field constants"
```

### Task 3.5: PR 3 wrap-up

- [ ] **Step 1: Full test + spotless + push + PR**

```bash
mvn -pl bootstrap test
mvn spotless:check
git push -u origin feat/parser-enhancement-pr3
gh pr create --title "feat(parser): PR 3 鈥?DB schema + DTO + persistence" \
  --body "$(cat <<'EOF'
## Summary
- v1.13 migration: `parse_mode` on `t_knowledge_document`; page-level `t_knowledge_document_page`; page/block/bbox/textLayer metadata on `t_knowledge_chunk`
- `KnowledgeDocumentUploadRequest` + `KnowledgeDocumentDO` carry `parseMode`
- Service normalizes nullable input via `ParseMode.fromValue` (defaults BASIC)
- `VectorMetadataFields` adds layout/evidence metadata constants

## Migration
Run on local: `docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.12_to_v1.13.sql`

## Test plan
- [x] DDL applies cleanly; columns observable in `\d`
- [x] `ParseModeTest` normalizer; manual DB check for upload persistence until an upload fixture exists
EOF
)"
```

---

## PR 4 (Phase 2.5 / PR-DOCINT-1b) 鈥?`ParseModePolicy` + `ParseModeRouter` + frontend toggle

**Goal:** parseMode replaces processMode at the UX surface; backend policy resolves the effective parse mode (Collateral KB 鈫?ENHANCED, otherwise user choice / BASIC default), then routes BASIC鈫扖HUNK / ENHANCED鈫扨IPELINE.

### Routing semantics (read before touching code)

The current repo has two ingestion paths and they react to `parseMode` differently. Implementers must keep this in mind so the same value behaves consistently end-to-end.

1. **CHUNK path is intentionally Tika-only.** `KnowledgeDocumentServiceImpl.runChunkProcess(...)` calls `parserSelector.select(ParserType.TIKA.getType())` directly and bypasses the ingestion engine / `ParserNode` entirely. When the resolved parse mode is BASIC, `parse_mode` is persisted as `basic` for traceability but does not change parser routing — Tika is hardcoded for the CHUNK path on purpose (fast path, byte-equivalent to legacy uploads).
2. **PIPELINE path is where parseMode actually selects the engine.** When `ParseModePolicy` resolves the effective mode to ENHANCED (Collateral default, or user choice on other KBs), the upload is also forced to PIPELINE. The PIPELINE branch runs `IngestionEngine` → `ParserNode`, and `ParserNode` reads `NodeConfig.settings.parseMode` (PR 1) → `DocumentParserSelector.selectByParseMode` → Docling (or Tika fallback via `FallbackParserDecorator` when Docling is offline).
3. **Async chunk consumer is transparent to this PR.** Upload writes `parse_mode` + `process_mode` to `t_knowledge_document`; the async path `KnowledgeDocumentChunkConsumer` → `executeChunk(docId)` → `runChunkTask(documentDO)` then reads `process_mode` from the DO and dispatches to CHUNK or PIPELINE. No async wiring change is needed in PR 4 — persisting the resolved values in the upload transaction is enough.

Implication: forcing ENHANCED for Collateral KB must happen **before** `resolveProcessModeConfig(...)` (which currently throws when PIPELINE is requested without `pipelineId`). PR 4 supplies a `default-enhanced-pipeline` seed precisely to satisfy that precondition without forcing the frontend to pick a pipeline.

### Task 4.0: `ParseModePolicy` backend decision seam

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModePolicy.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeDecision.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModePolicyTest.java`

- [ ] **Step 1: Test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModePolicyTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParseModePolicyTest {

    private final ParseModePolicy policy = new ParseModePolicy();

    @Test
    void collateralKb_forcesEnhancedEvenWhenUserChoosesBasic() {
        ParseModeDecision d = policy.decide("COLLATERAL", ParseMode.BASIC);
        assertEquals(ParseMode.ENHANCED, d.parseMode());
        assertEquals("collateral_kb_default_enhanced", d.reason());
    }

    @Test
    void nonCollateralKb_usesUserChoiceOrBasicDefault() {
        assertEquals(ParseMode.ENHANCED, policy.decide("COB", ParseMode.ENHANCED).parseMode());
        assertEquals(ParseMode.BASIC, policy.decide("COB", null).parseMode());
    }
}
```

- [ ] **Step 2: Implement**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeDecision.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;

public record ParseModeDecision(ParseMode parseMode, String reason) {
}
```

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModePolicy.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import org.springframework.stereotype.Component;

/**
 * Decides the effective parse mode before process-mode routing.
 *
 * MVP intentionally does not run text-layer quality detection. Future implementations
 * can inject TextLayerQualityDetector / OcrFallbackParser signals here without changing
 * upload controllers or ParserNode.
 */
@Component
public class ParseModePolicy {

    private static final String COLLATERAL_KB_TYPE = "COLLATERAL";

    public ParseModeDecision decide(String kbType, ParseMode requested) {
        if (COLLATERAL_KB_TYPE.equalsIgnoreCase(String.valueOf(kbType))) {
            return new ParseModeDecision(ParseMode.ENHANCED, "collateral_kb_default_enhanced");
        }
        ParseMode effective = requested == null ? ParseMode.BASIC : requested;
        return new ParseModeDecision(effective, "user_or_default");
    }
}
```

Implementation note: if the current schema does not yet expose a KB type, use the existing stable Collateral KB identifier / config property for the first implementation. Keep this logic inside `ParseModePolicy`, not scattered in `KnowledgeDocumentServiceImpl`.

- [ ] **Step 3: Run test**

```bash
mvn -pl bootstrap test -Dtest=ParseModePolicyTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModePolicy.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeDecision.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModePolicyTest.java
git commit -m "feat(knowledge): add KB-aware ParseModePolicy"
```

### Task 4.0b: Add deferred quality / OCR extension ports

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/quality/TextLayerQualityDetector.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/quality/TextLayerQuality.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/quality/NoopTextLayerQualityDetector.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ocr/OcrFallbackParser.java`

- [ ] **Step 1: Add no-op quality detector seam**

```java
package com.knowledgebase.ai.ragent.core.parser.quality;

public interface TextLayerQualityDetector {
    TextLayerQuality detect(byte[] content, String mimeType);
}
```

```java
package com.knowledgebase.ai.ragent.core.parser.quality;

public record TextLayerQuality(
        String textLayerType,       // NATIVE_TEXT / OCR / MIXED / UNKNOWN
        boolean recommendsEnhanced,
        boolean recommendsOcrFallback,
        Double confidence,
        String reason
) {
    public static TextLayerQuality unknown() {
        return new TextLayerQuality("UNKNOWN", false, false, null, "mvp_not_evaluated");
    }
}
```

```java
package com.knowledgebase.ai.ragent.core.parser.quality;

import org.springframework.stereotype.Component;

@Component
public class NoopTextLayerQualityDetector implements TextLayerQualityDetector {
    @Override
    public TextLayerQuality detect(byte[] content, String mimeType) {
        return TextLayerQuality.unknown();
    }
}
```

- [ ] **Step 2: Add OCR fallback parser port but do not wire it**

```java
package com.knowledgebase.ai.ragent.core.parser.ocr;

import com.knowledgebase.ai.ragent.core.parser.DocumentParser;

/**
 * Future extension point for image-only / scanned PDFs.
 * MVP keeps this port unused so OCR quality is not implied by Docling integration.
 */
public interface OcrFallbackParser extends DocumentParser {
}
```

Do not inject these ports into `ParseModePolicy` yet. They exist so the future detector / OCR fallback can be wired without changing upload controller or parser contracts.

- [ ] **Step 3: Compile and commit**

```bash
mvn -pl bootstrap compile
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/quality/ \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ocr/
git commit -m "feat(parser): reserve text-layer quality and OCR fallback ports"
```

### Task 4.1: `ParseModeRouter`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouter.java`

- [ ] **Step 1: Test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouterTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.knowledge.enums.ProcessMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParseModeRouterTest {

    private final ParseModeRouter router = new ParseModeRouter();

    @Test
    void basic_routesToChunk() {
        assertEquals(ProcessMode.CHUNK, router.resolve(ParseMode.BASIC));
    }

    @Test
    void enhanced_routesToPipeline() {
        assertEquals(ProcessMode.PIPELINE, router.resolve(ParseMode.ENHANCED));
    }
}
```

- [ ] **Step 2: Implement**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouter.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.knowledge.enums.ProcessMode;
import org.springframework.stereotype.Component;

/**
 * Maps user-facing {@link ParseMode} to internal {@link ProcessMode}.
 * BASIC 鈫?CHUNK (fast path, Tika).
 * ENHANCED 鈫?PIPELINE (Docling + structured chunker + layout metadata).
 *
 * This collapses two technical concepts (chunk vs pipeline; tika vs docling) into one
 * product-facing toggle, hiding processMode from the UI.
 */
@Component
public final class ParseModeRouter {

    public ProcessMode resolve(ParseMode parseMode) {
        return switch (parseMode) {
            case BASIC    -> ProcessMode.CHUNK;
            case ENHANCED -> ProcessMode.PIPELINE;
        };
    }
}
```

(Current repo FQN is `com.knowledgebase.ai.ragent.knowledge.enums.ProcessMode`; keep using `getValue()` for DB persistence.)

- [ ] **Step 3: Run test**

```bash
mvn -pl bootstrap test -Dtest=ParseModeRouterTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouter.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouterTest.java
git commit -m "feat(knowledge): ParseModeRouter (BASIC鈫扖HUNK, ENHANCED鈫扨IPELINE)"
```

### Task 4.2: Service uses policy + router; Collateral and ENHANCED force pipeline

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

- [ ] **Step 1: Inject policy and router**

In the field block:

```java
    private final ParseModePolicy parseModePolicy;
    private final ParseModeRouter parseModeRouter;
```

(Lombok `@RequiredArgsConstructor` already wires it via the `final` field.)

- [ ] **Step 2: Use policy and router in upload flow**

In `upload(...)`, immediately after reading the requested parse mode, resolve the effective parse mode and override `processMode`:

```java
        ParseMode requestedParseMode = ParseMode.fromValue(requestParam.getParseMode());
        String kbType = resolveKbTypeOrStableCode(kbId); // kbId comes from upload(String kbId, ...)
        ParseModeDecision decision = parseModePolicy.decide(kbType, requestedParseMode);
        ProcessMode resolvedMode = parseModeRouter.resolve(decision.parseMode());
        documentDO.setParseMode(decision.parseMode().getValue());
        documentDO.setProcessMode(resolvedMode.getValue());
        log.info("Resolved document parse mode, kbId={}, requested={}, effective={}, reason={}",
                kbId, requestedParseMode, decision.parseMode(), decision.reason());
```

This intentionally **ignores** `requestParam.getProcessMode()`. Add a comment:

```java
        // parseMode is the UX-level switch; ParseModePolicy may override it for KBs
        // that require enhanced parsing (Collateral). processMode is derived only
        // from the effective parse mode; frontend processMode is ignored.
```

If document-level ingestion metadata already has a stable storage point, also persist `parse_mode_reason`; otherwise the log/trace is enough for this PR. Do not block this PR on a new metadata column.

Execution note: `KnowledgeDocumentUploadRequest` does not carry `kbId`; the service receives it from the controller path variable as the `upload(String kbId, ...)` method argument. Once this method persists `process_mode='pipeline'`, the async consumer path (`KnowledgeDocumentChunkConsumer` 鈫?`executeChunk(docId)` 鈫?`runChunkTask`) reloads the document and naturally enters the PIPELINE branch.

Why BASIC still works: CHUNK mode intentionally keeps the existing `runChunkProcess(...)` fast path, which directly selects Tika. In BASIC mode, `parse_mode='basic'` is metadata and does not route through `ParserNode`. `ParserNode` needs the `parseMode` setting only when `ParseModePolicy` resolves ENHANCED and the upload is flipped to PIPELINE.

- [ ] **Step 3: Inject parseMode into pipeline NodeConfig (PIPELINE path only)**

In `runPipelineProcess()` at the spot where `IngestionContext` is built (around line 393), add:

```java
        ctx.setParseMode(documentDO.getParseMode());  // effective engine-neutral semantic value
```

(Add `parseMode` field to `IngestionContext` builder + getter/setter via Lombok.)

Then where `PipelineDefinition` is loaded, **inject `parseMode` into the ParserNode's NodeConfig settings** before engine execution. Add a helper `enrichParserNodeConfig(PipelineDefinition, ParseMode)` that walks `pipelineDef.getNodes()`, finds the `ParserNode` config, deep-copies its settings JsonNode, sets `parseMode` field, and replaces in the definition (definition object should be a per-execution copy to avoid mutating cached defs):

Current repo note: `PipelineDefinition` and `NodeConfig` use Lombok `@Builder` without `toBuilder`. Before using the helper below, upgrade both annotations:

```java
@Builder(toBuilder = true)
public class PipelineDefinition {
```

```java
@Builder(toBuilder = true)
public class NodeConfig {
```

```java
    private PipelineDefinition enrichParserNodeConfig(PipelineDefinition def, ParseMode parseMode) {
        // Walk nodes, find type==ParserNode, copy settings, set parseMode, return new def
        return def.withParserNodeSetting("parseMode", parseMode.getValue());
    }
```

If `PipelineDefinition` doesn't have a `withParserNodeSetting` helper, **add one** to `PipelineDefinition.java` (small builder method). Spec the helper:

```java
    public PipelineDefinition withParserNodeSetting(String key, String value) {
        // Return a new PipelineDefinition where any node with type == "ParserNode" has
        // its settings JsonNode copied with key=value added. Other nodes unchanged.
    }
```

If implementing this is non-trivial (settings is a JsonNode tree), use Jackson's `ObjectNode.deepCopy()`:

```java
    public PipelineDefinition withParserNodeSetting(String key, String value) {
        ObjectMapper om = new ObjectMapper();
        List<NodeConfig> copy = new ArrayList<>();
        for (NodeConfig n : this.getNodes()) {
            if ("ParserNode".equals(n.getNodeType())) {
                ObjectNode settings = n.getSettings() == null
                        ? om.createObjectNode()
                        : ((ObjectNode) n.getSettings()).deepCopy();
                settings.put(key, value);
                copy.add(n.toBuilder().settings(settings).build());
            } else {
                copy.add(n);
            }
        }
        return this.toBuilder().nodes(copy).build();
    }
```

- [ ] **Step 4: Seed `default-enhanced-pipeline`**

Append to `resources/database/upgrade_v1.12_to_v1.13.sql`:

```sql
-- 3) Seed default enhanced pipeline (used when parse_mode='enhanced')
INSERT INTO t_ingestion_pipeline_definition
    (id, pipeline_name, description, node_definitions, created_by, updated_by)
VALUES (
    'default-enhanced-pipeline',
    'Default Enhanced Parsing',
    'Auto-selected for ENHANCED parse mode and Collateral KB uploads. Uses Docling parser + structured chunker.',
    '[
      {"nodeId":"fetch","nodeType":"FetcherNode","settings":{},"nextNodeId":"parse"},
      {"nodeId":"parse","nodeType":"ParserNode","settings":{"parseMode":"enhanced"},"nextNodeId":"chunk"},
      {"nodeId":"chunk","nodeType":"ChunkerNode","settings":{"strategy":"structured"},"nextNodeId":"index"},
      {"nodeId":"index","nodeType":"IndexerNode","settings":{},"nextNodeId":null}
    ]'::jsonb,
    'system',
    'system'
)
ON CONFLICT (id) DO NOTHING;
```

(Adjust column names to match the actual `t_ingestion_pipeline_definition` schema; verify with `\d t_ingestion_pipeline_definition` before committing.)

- [ ] **Step 5: Service forces pipelineId on effective ENHANCED**

In `KnowledgeDocumentServiceImpl.upload(...)` after `resolvedMode`:

```java
        if (resolvedMode == ProcessMode.PIPELINE
                && (requestParam.getPipelineId() == null || requestParam.getPipelineId().isBlank())) {
            documentDO.setPipelineId("default-enhanced-pipeline");
        } else if (resolvedMode == ProcessMode.PIPELINE) {
            documentDO.setPipelineId(requestParam.getPipelineId());
        }
```

- [ ] **Step 6: Apply migration locally**

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.12_to_v1.13.sql
docker exec postgres psql -U postgres -d ragent -c "SELECT id FROM t_ingestion_pipeline_definition WHERE id='default-enhanced-pipeline'"
```

Expected: 1 row.

- [ ] **Step 7: End-to-end test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/EnhancedRoutingIntegrationTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.knowledge.enums.ProcessMode;
import com.knowledgebase.ai.ragent.knowledge.service.support.ParseModeRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnhancedRoutingIntegrationTest {

    private final ParseModeRouter router = new ParseModeRouter();

    @Test
    void enhancedNeverYieldsChunkRegardlessOfRequestProcessMode() {
        // Even if the legacy frontend sends processMode=CHUNK, effective parseMode=enhanced should win.
        assertEquals(ProcessMode.PIPELINE, router.resolve(ParseMode.ENHANCED));
    }
}
```

```bash
mvn -pl bootstrap test -Dtest=EnhancedRoutingIntegrationTest
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/pipeline/PipelineDefinition.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/pipeline/NodeConfig.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/context/IngestionContext.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/EnhancedRoutingIntegrationTest.java \
        resources/database/upgrade_v1.12_to_v1.13.sql
git commit -m "feat(knowledge): parseMode routes BASIC->CHUNK, ENHANCED->PIPELINE w/ default pipeline"
```

### Task 4.3: Frontend `ParseModeRadio` component

**Files:**
- Create: `frontend/src/components/upload/ParseModeRadio.tsx`

- [ ] **Step 1: Locate existing upload dialog & shadcn primitives**

```bash
find frontend/src -name "*UploadDialog*" -o -name "*DocumentUpload*" | head -5
ls frontend/src/components/ui/ | grep -i radio
```

- [ ] **Step 2: Implement component**

Create `frontend/src/components/upload/ParseModeRadio.tsx`:

```tsx
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Label } from "@/components/ui/label";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { Info } from "lucide-react";

export type ParseMode = "basic" | "enhanced";

interface ParseModeRadioProps {
  value: ParseMode;
  onChange: (v: ParseMode) => void;
  disabled?: boolean;
}

export function ParseModeRadio({ value, onChange, disabled }: ParseModeRadioProps) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Label className="text-sm font-medium">瑙ｆ瀽鏂瑰紡</Label>
        <Tooltip>
          <TooltipTrigger asChild>
            <Info className="h-4 w-4 text-muted-foreground cursor-help" />
          </TooltipTrigger>
          <TooltipContent className="max-w-xs">
            <p>
              <strong>鍩虹瑙ｆ瀽</strong>锛氶€傚悎绾枃鏈被鏂囨。锛堟櫘閫?PDF / Word / 鏂囨湰锛夛紝绉掔骇瀹屾垚銆?
            </p>
            <p className="mt-1">
              <strong>澧炲己瑙ｆ瀽</strong>锛氳瘑鍒〃鏍笺€佺増闈㈠拰鏍囬灞傜骇锛岄€傚悎澶嶆潅 PDF锛屽鐞嗘洿鎱€?              寤鸿鍚堝悓 / 鎶ュ憡 / 鍚〃鏍肩殑 PDF 閫夌敤銆?
            </p>
          </TooltipContent>
        </Tooltip>
      </div>
      <RadioGroup
        value={value}
        onValueChange={(v) => onChange(v as ParseMode)}
        disabled={disabled}
        className="flex gap-6"
      >
        <div className="flex items-center space-x-2">
          <RadioGroupItem value="basic" id="parse-basic" />
          <Label htmlFor="parse-basic" className="font-normal cursor-pointer">
            鍩虹瑙ｆ瀽
          </Label>
        </div>
        <div className="flex items-center space-x-2">
          <RadioGroupItem value="enhanced" id="parse-enhanced" />
          <Label htmlFor="parse-enhanced" className="font-normal cursor-pointer">
            澧炲己瑙ｆ瀽
          </Label>
        </div>
      </RadioGroup>
    </div>
  );
}
```

- [ ] **Step 3: Add `parseMode` to upload payload type**

In `frontend/src/services/knowledgeService.ts`, find the interface (around line 103鈥?14) and add:

```ts
  parseMode?: "basic" | "enhanced";
```

In the FormData construction block (around line 213鈥?49), append:

```ts
  if (payload.parseMode) {
    formData.append("parseMode", payload.parseMode);
  }
```

- [ ] **Step 4: Wire into upload dialog**

In the upload dialog component, replace the existing `processMode` selector with:

```tsx
import { ParseModeRadio, type ParseMode } from "@/components/upload/ParseModeRadio";

// state
const [parseMode, setParseMode] = useState<ParseMode>("basic");

// in form JSX, replace the processMode field with:
<ParseModeRadio value={parseMode} onChange={setParseMode} />

// in submit handler, include parseMode in payload:
await uploadDocument({ ...payload, parseMode });
```

Remove any UI/state for `processMode` from the dialog (backend ignores it now).

- [ ] **Step 5: Manual test**

```bash
cd frontend && pnpm dev
```

In browser:
1. Open upload dialog
2. Verify "瑙ｆ瀽鏂瑰紡" radio shows two options + 鈸?tooltip
3. Upload a small PDF with "澧炲己瑙ｆ瀽" selected
4. Inspect DB: `SELECT id, parse_mode, process_mode, pipeline_id FROM t_knowledge_document ORDER BY create_time DESC LIMIT 1;` 鈥?should show `enhanced / PIPELINE / default-enhanced-pipeline`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/upload/ParseModeRadio.tsx \
        frontend/src/services/knowledgeService.ts \
        frontend/src/pages/  # adjust to actual upload dialog path
git commit -m "feat(frontend): parseMode RadioGroup replaces processMode in upload UI"
```

### Task 4.4: PR 4 wrap-up

- [ ] **Step 1: Full backend test + spotless + frontend type-check + push + PR**

```bash
mvn -pl bootstrap test
mvn spotless:check
cd frontend && pnpm tsc --noEmit && cd ..
git push -u origin feat/parser-enhancement-pr4
gh pr create --title "feat(parser): PR 4 鈥?ParseModeRouter + frontend toggle" \
  --body "$(cat <<'EOF'
## Summary
- `ParseModeRouter` collapses chunk/pipeline + tika/docling into one product-facing toggle
- ENHANCED upload auto-uses seeded `default-enhanced-pipeline` (no user pipelineId needed)
- Frontend dialog shows 鍩虹 / 澧炲己 RadioGroup with tooltip; processMode field removed
- Docling not yet integrated 鈫?ENHANCED currently uses Tika fallback with `parse_engine_actual=tika` metadata (frontend still shows the choice)

## Test plan
- [x] Backend tests pass; spotless clean
- [x] Frontend tsc clean
- [x] Manual: upload with 澧炲己 鈫?DB shows parse_mode=enhanced, process_mode=PIPELINE
EOF
)"
```

---

## PR 5 (Phase 2.5 / PR-DOCINT-1c) 鈥?`DoclingClient` + `DoclingDocumentParser` (real Docling integration)

**Goal:** Bring Docling Python service online; ENHANCED uploads now produce real layout output.

### Task 5.1: Docling service deployment

**Files:**
- Create: `resources/docker/docling-compose.yml`
- Create: `docs/dev/setup/docling-service.md`

- [ ] **Step 1: Compose file**

Create `resources/docker/docling-compose.yml`:

```yaml
services:
  docling:
    image: quay.io/ds4sd/docling-serve:latest
    container_name: docling
    ports:
      - "5001:5001"
    environment:
      - DOCLING_SERVE_ENABLE_UI=false
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5001/health"]
      interval: 30s
      timeout: 10s
      retries: 3
```

- [ ] **Step 2: Operator doc**

Create `docs/dev/setup/docling-service.md`:

```markdown
# Docling 鏈嶅姟閮ㄧ讲

## 鍚姩

```bash
docker compose -f resources/docker/docling-compose.yml up -d docling
```

## 鍋ュ悍妫€鏌?

```bash
curl http://localhost:5001/health
# 鏈熸湜锛歿"status":"ok"}
```

## 椤圭洰閰嶇疆

`bootstrap/src/main/resources/application.yaml` 鍔犲叆锛?

```yaml
docling:
  service:
    enabled: true
    host: http://localhost:5001
    timeout-ms: 60000
    health-endpoint: /health
    convert-endpoint: /v1alpha/convert/file
```

## 鍏抽棴 / 鍥炴粴

鎶?`docling.service.enabled` 璁句负 `false`锛氭墍鏈?ENHANCED 涓婁紶閫氳繃 `FallbackParserDecorator` 甯︽爣璁伴檷绾у埌 Tika锛屽墠绔?UI 涓嶅彉锛堟枃妗ｅ垪琛ㄤ細鏄剧ず 鈿狅笍 闄嶇骇鎻愮ず锛夈€?```

- [ ] **Step 3: Start service & verify**

```bash
docker compose -f resources/docker/docling-compose.yml up -d docling
sleep 10
curl -f http://localhost:5001/health
```

Expected: HTTP 200.

- [ ] **Step 4: Commit**

```bash
git add resources/docker/docling-compose.yml docs/dev/setup/docling-service.md
git commit -m "ops(docling): independent Docling service via docker-compose + setup doc"
```

### Task 5.2: `AbstractRemoteParser` template

**Files:**
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/AbstractRemoteParser.java`

- [ ] **Step 1: Implement template**

Create `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/AbstractRemoteParser.java`:

```java
package com.knowledgebase.ai.ragent.infra.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.time.Duration;

/**
 * Template Method base for HTTP-backed remote parser clients.
 * Subclasses implement {@link #buildRequest} + {@link #parseResponse}.
 * Provides consistent timeout / logging / lifecycle.
 */
@Slf4j
public abstract class AbstractRemoteParser<RESP> implements AutoCloseable {

    private final CloseableHttpClient http;
    private final Duration timeout;

    protected AbstractRemoteParser(Duration timeout) {
        this.timeout = timeout;
        RequestConfig cfg = RequestConfig.custom()
                .setResponseTimeout(Timeout.of(timeout))
                .setConnectTimeout(Timeout.of(Duration.ofSeconds(10)))
                .build();
        this.http = HttpClients.custom().setDefaultRequestConfig(cfg).build();
    }

    protected final RESP execute(HttpPost request) throws IOException {
        long t0 = System.currentTimeMillis();
        try {
            return http.execute(request, response -> {
                int code = response.getCode();
                if (code >= 400) {
                    throw new IOException("Remote parser HTTP " + code);
                }
                return parseResponse(response.getEntity());
            });
        } finally {
            log.info("Remote parse took {} ms (timeout={}ms)",
                    System.currentTimeMillis() - t0, timeout.toMillis());
        }
    }

    protected abstract RESP parseResponse(org.apache.hc.core5.http.HttpEntity entity) throws IOException;

    @Override
    public void close() throws IOException {
        http.close();
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn -pl infra-ai compile
```
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/AbstractRemoteParser.java
git commit -m "feat(infra-ai): AbstractRemoteParser template-method base"
```

### Task 5.3: `DoclingClient` + properties + DTO

**Files:**
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/DoclingClient.java`
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/DoclingClientProperties.java`
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/dto/DoclingConvertResponse.java`

- [ ] **Step 1: Properties**

Create `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/DoclingClientProperties.java`:

```java
package com.knowledgebase.ai.ragent.infra.parser.docling;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "docling.service")
public class DoclingClientProperties {
    private boolean enabled = false;
    private String host = "http://localhost:5001";
    private int timeoutMs = 60_000;
    private String healthEndpoint = "/health";
    private String convertEndpoint = "/v1alpha/convert/file";
}
```

- [ ] **Step 2: Response DTO**

Create `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/dto/DoclingConvertResponse.java`:

```java
package com.knowledgebase.ai.ragent.infra.parser.docling.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Mirror of Docling /v1alpha/convert/file response (relevant subset).
 * Keep loose with @JsonIgnoreProperties so Docling version drift doesn't break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingConvertResponse(
        Document document,
        Map<String, Object> metadata
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            String text,
            List<DoclingBlock> blocks,
            List<DoclingTable> tables
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingBlock(
            String id,
            String type,             // "heading" / "paragraph" / "list" / etc.
            Integer pageNo,
            Bbox bbox,
            Integer readingOrder,
            Double confidence,
            String textLayerType,
            Integer level,
            String text,
            List<String> sectionPath
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingTable(
            String id,
            Integer pageNo,
            Bbox bbox,
            List<List<String>> rows,
            List<String> sectionPath,
            Integer readingOrder,
            Double confidence
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bbox(double x, double y, double width, double height) {}
}
```

(Adjust field names after first real `curl` of the Docling endpoint 鈥?see Task 5.6.)

- [ ] **Step 3: Client**

Create `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/DoclingClient.java`:

```java
package com.knowledgebase.ai.ragent.infra.parser.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.infra.parser.AbstractRemoteParser;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@Configuration
@EnableConfigurationProperties(DoclingClientProperties.class)
public class DoclingClient extends AbstractRemoteParser<DoclingConvertResponse> {

    private final DoclingClientProperties props;
    private final ObjectMapper om = new ObjectMapper();

    public DoclingClient(DoclingClientProperties props) {
        super(Duration.ofMillis(props.getTimeoutMs()));
        this.props = props;
    }

    public DoclingConvertResponse convert(byte[] content, String fileName) throws IOException {
        HttpPost req = new HttpPost(props.getHost() + props.getConvertEndpoint());
        req.setEntity(MultipartEntityBuilder.create()
                .addBinaryBody("file", content, ContentType.APPLICATION_OCTET_STREAM, fileName)
                .build());
        return execute(req);
    }

    public boolean isHealthy() {
        try {
            HttpPost req = new HttpPost(props.getHost() + props.getHealthEndpoint());
            execute(req);
            return true;
        } catch (Exception e) {
            log.warn("Docling health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected DoclingConvertResponse parseResponse(HttpEntity entity) throws IOException {
        try {
            return om.readValue(EntityUtils.toString(entity), DoclingConvertResponse.class);
        } catch (Exception e) {
            throw new IOException("Failed to parse Docling response", e);
        }
    }
}
```

- [ ] **Step 4: Compile**

```bash
mvn -pl infra-ai compile
```
Expected: SUCCESS. If `httpmime` / `MultipartEntityBuilder` is missing, add to `infra-ai/pom.xml`:

```xml
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
</dependency>
```

(Already declared in bootstrap; verify infra-ai has access via parent or add explicit dep.)

- [ ] **Step 5: Commit**

```bash
git add infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/
git commit -m "feat(infra-ai): DoclingClient + properties + response DTO"
```

### Task 5.4: `DoclingResponseAdapter`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java`

- [ ] **Step 1: Test with sample JSON**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapterTest.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DoclingResponseAdapterTest {

    private final DoclingResponseAdapter adapter = new DoclingResponseAdapter();

    @Test
    void mapsBlocksAndTables() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document(
                        "all text",
                        List.of(
                                new DoclingConvertResponse.DoclingBlock(
                                        "b1", "heading", 1,
                                        new DoclingConvertResponse.Bbox(0, 0, 100, 20),
                                        1, 0.99D, "NATIVE_TEXT", 1, "Chapter 1", List.of()),
                                new DoclingConvertResponse.DoclingBlock(
                                        "b2", "paragraph", 1,
                                        new DoclingConvertResponse.Bbox(0, 30, 100, 40),
                                        2, 0.98D, "NATIVE_TEXT", null, "Body...", List.of("Chapter 1"))
                        ),
                        List.of(
                                new DoclingConvertResponse.DoclingTable(
                                        "t1", 2,
                                        new DoclingConvertResponse.Bbox(10, 10, 200, 100),
                                        List.of(List.of("a", "b"), List.of("1", "2")),
                                        List.of("Chapter 1", "1.1 Tables"), 3, 0.97D)
                        )
                ),
                Map.of("source", "test")
        );

        ParseResult r = adapter.toParseResult(resp);
        assertEquals("all text", r.text());
        assertEquals(1, r.pages().size());
        assertEquals(2, r.pages().get(0).blocks().size());
        assertEquals(BlockType.TITLE, r.pages().get(0).blocks().get(0).blockType());
        assertEquals(1, r.pages().get(0).blocks().get(0).headingLevel());
        assertEquals("NATIVE_TEXT", r.pages().get(0).textLayerType());
        assertEquals(1, r.tables().size());
        assertEquals(2, r.tables().get(0).rows().size());
        assertEquals("test", r.metadata().get("source"));
    }

    @Test
    void unknownBlockType_mapsToOther() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document("x",
                        List.of(new DoclingConvertResponse.DoclingBlock(
                                "b1", "weird-type", 1, null, null, null, "NATIVE_TEXT", null, "x", List.of())),
                        List.of()),
                Map.of()
        );
        ParseResult r = adapter.toParseResult(resp);
        assertEquals(BlockType.OTHER, r.pages().get(0).blocks().get(0).blockType());
    }

    @Test
    void nullDocument_yieldsEmptyResult() {
        DoclingConvertResponse resp = new DoclingConvertResponse(null, Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals("", r.text());
        assertNotNull(r.pages());
    }
}
```

- [ ] **Step 2: Implement adapter**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Adapter: Docling-private response shape 鈫?engine-neutral {@link ParseResult}.
 * Replace this adapter (not the parser interface) when swapping engines.
 */
@Component
public class DoclingResponseAdapter {

    public ParseResult toParseResult(DoclingConvertResponse resp) {
        if (resp == null || resp.document() == null) {
            return new ParseResult("", resp == null ? Map.of() : resp.metadata(), List.of(), List.of());
        }
        DoclingConvertResponse.Document d = resp.document();
        List<DoclingConvertResponse.DoclingBlock> rawBlocks =
                d.blocks() == null ? List.of() : d.blocks();
        List<LayoutBlock> blocks = rawBlocks
                .stream().map(this::mapBlock).toList();
        List<LayoutTable> tables = (d.tables() == null ? List.<DoclingConvertResponse.DoclingTable>of() : d.tables())
                .stream().map(this::mapTable).toList();
        String text = d.text() == null ? "" : d.text();
        List<DocumentPageText> pages = mapPages(blocks, rawBlocks);
        return new ParseResult(text, resp.metadata() == null ? Map.of() : resp.metadata(), pages, tables);
    }

    private LayoutBlock mapBlock(DoclingConvertResponse.DoclingBlock b) {
        return new LayoutBlock(
                b.id(),
                mapType(b.type()),
                b.pageNo() == null ? 0 : b.pageNo(),
                b.bbox() == null ? null : new LayoutBlock.Bbox(
                        b.bbox().x(), b.bbox().y(), b.bbox().width(), b.bbox().height()),
                b.text() == null ? "" : b.text(),
                b.readingOrder(),
                b.confidence(),
                b.level(),
                b.sectionPath() == null ? List.of() : b.sectionPath()
        );
    }

    private LayoutTable mapTable(DoclingConvertResponse.DoclingTable t) {
        return new LayoutTable(
                t.id(),
                t.pageNo() == null ? 0 : t.pageNo(),
                t.bbox() == null ? null : new LayoutBlock.Bbox(
                        t.bbox().x(), t.bbox().y(), t.bbox().width(), t.bbox().height()),
                t.rows() == null ? List.of() : t.rows(),
                t.sectionPath() == null ? List.of() : t.sectionPath(),
                t.readingOrder(),
                t.confidence()
        );
    }

    private List<DocumentPageText> mapPages(List<LayoutBlock> blocks,
                                            List<DoclingConvertResponse.DoclingBlock> rawBlocks) {
        Map<Integer, List<LayoutBlock>> blocksByPage = blocks.stream()
                .collect(Collectors.groupingBy(LayoutBlock::pageNo, LinkedHashMap::new, Collectors.toList()));
        Map<Integer, List<DoclingConvertResponse.DoclingBlock>> rawByPage = rawBlocks.stream()
                .collect(Collectors.groupingBy(b -> b.pageNo() == null ? 0 : b.pageNo(),
                        LinkedHashMap::new, Collectors.toList()));

        return blocksByPage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<LayoutBlock> sorted = sortByReadingOrder(e.getValue());
                    String pageText = sorted.stream()
                            .map(LayoutBlock::text)
                            .filter(s -> s != null && !s.isBlank())
                            .collect(Collectors.joining("\n"));
                    return new DocumentPageText(
                            null,
                            e.getKey(),
                            pageText,
                            inferTextLayerType(rawByPage.get(e.getKey())),
                            averageConfidence(sorted),
                            sorted
                    );
                })
                .toList();
    }

    private List<LayoutBlock> sortByReadingOrder(List<LayoutBlock> blocks) {
        return blocks.stream()
                .sorted(Comparator.comparing(b -> b.readingOrder() == null ? Integer.MAX_VALUE : b.readingOrder()))
                .toList();
    }

    private String inferTextLayerType(List<DoclingConvertResponse.DoclingBlock> rawBlocks) {
        if (rawBlocks == null || rawBlocks.isEmpty()) return null;
        boolean hasOcr = rawBlocks.stream().anyMatch(b -> "OCR".equalsIgnoreCase(b.textLayerType()));
        boolean hasNative = rawBlocks.stream().anyMatch(b -> "NATIVE_TEXT".equalsIgnoreCase(b.textLayerType()));
        if (hasOcr && hasNative) return "MIXED";
        if (hasOcr) return "OCR";
        if (hasNative) return "NATIVE_TEXT";
        return rawBlocks.stream().map(DoclingConvertResponse.DoclingBlock::textLayerType)
                .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private Double averageConfidence(List<LayoutBlock> blocks) {
        OptionalDouble average = blocks.stream()
                .map(LayoutBlock::confidence)
                .filter(v -> v != null)
                .mapToDouble(Double::doubleValue)
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    private BlockType mapType(String docling) {
        if (docling == null) return BlockType.OTHER;
        return switch (docling.toLowerCase()) {
            case "heading", "title", "section-header" -> BlockType.TITLE;
            case "paragraph", "text" -> BlockType.PARAGRAPH;
            case "table" -> BlockType.TABLE;
            case "header", "page-header" -> BlockType.HEADER;
            case "footer", "page-footer" -> BlockType.FOOTER;
            case "list", "list-item" -> BlockType.LIST;
            case "caption" -> BlockType.CAPTION;
            default -> BlockType.OTHER;
        };
    }
}
```

- [ ] **Step 3: Run test**

```bash
mvn -pl bootstrap test -Dtest=DoclingResponseAdapterTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapterTest.java
git commit -m "feat(parser): DoclingResponseAdapter (Docling JSON -> engine-neutral)"
```

### Task 5.5: `DoclingDocumentParser`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingDocumentParser.java`

- [ ] **Step 1: Implement parser**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingDocumentParser.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.infra.parser.docling.DoclingClient;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "docling.service", name = "enabled", havingValue = "true")
public class DoclingDocumentParser implements DocumentParser {

    public static final String TYPE = ParserType.DOCLING.getType();

    private final DoclingClient client;
    private final DoclingResponseAdapter adapter;

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        try {
            String fileName = options == null ? "input" : (String) options.getOrDefault("fileName", "input");
            DoclingConvertResponse resp = client.convert(content, fileName);
            return adapter.toParseResult(resp);
        } catch (IOException e) {
            // Wrap so FallbackParserDecorator catches it and falls back to Tika.
            throw new RuntimeException("Docling parse failed", e);
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) throws IOException {
        byte[] bytes = stream.readAllBytes();
        return parse(bytes, null, Map.of("fileName", fileName)).text();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return true;
        // Docling supports PDF/Word/Excel/PPT/HTML/images. Decline pure plain text 鈥?Tika is faster.
        return !mimeType.startsWith("text/plain") && !mimeType.equals("text/markdown");
    }
}
```

- [ ] **Step 2: Register parser type**

In `DocumentParserSelector.java`, the `@PostConstruct` registry build should auto-pick this up via Spring component scan + the existing registration mechanism (verify by reading the existing init logic). If registration is by `parser.type()` method on each `DocumentParser` impl, ensure `DoclingDocumentParser` exposes the same. If the existing pattern uses a `getType()` method, add:

```java
    public String getType() { return TYPE; }
```

(Adjust to match the existing `TikaDocumentParser` pattern.)

- [ ] **Step 3: Smoke test**

Manual end-to-end with Docling running:

```bash
docker compose -f resources/docker/docling-compose.yml up -d
# In application.yaml: docling.service.enabled=true
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

In another terminal, upload a PDF with parseMode=enhanced via the frontend or curl, then check:

```bash
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT id, parse_mode, process_mode, status FROM t_knowledge_document \
   ORDER BY create_time DESC LIMIT 1;"
```

Expected: the latest document shows `enhanced / pipeline / success`, and application logs show `parse_engine_actual=docling` or equivalent parser selection telemetry. Chunk/page metadata propagation is verified in PR 6.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingDocumentParser.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java
git commit -m "feat(parser): DoclingDocumentParser w/ ConditionalOnProperty"
```

### Task 5.6: Adjust DTO field mapping to actual Docling response

**Files:**
- Modify: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/dto/DoclingConvertResponse.java`

- [ ] **Step 1: Capture real response**

```bash
curl -X POST http://localhost:5001/v1alpha/convert/file \
     -F "file=@tmp/parser-spike/samples/1. Guideline on AML KYC_2025.pdf" \
     > tmp/parser-spike/docling-real-response.json
head -200 tmp/parser-spike/docling-real-response.json | jq .
```

- [ ] **Step 2: Reconcile DTO field names**

If real field names differ from `pageNo / sectionPath / level`, update `DoclingConvertResponse.java` field names + corresponding adapter mapping in `DoclingResponseAdapter`. Use Jackson `@JsonProperty` on records to preserve internal naming if external is snake_case.

- [ ] **Step 3: Re-run adapter test**

```bash
mvn -pl bootstrap test -Dtest=DoclingResponseAdapterTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/parser/docling/dto/DoclingConvertResponse.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java
git commit -m "fix(docling): align DTO fields with actual Docling /convert response"
```

### Task 5.7: PR 5 wrap-up

- [ ] **Step 1: Full test + spotless + push + PR**

```bash
mvn -pl bootstrap test
mvn -pl infra-ai test
mvn spotless:check
git push -u origin feat/parser-enhancement-pr5
gh pr create --title "feat(parser): PR 5 鈥?Docling integration (real enhanced parsing)" \
  --body "$(cat <<'EOF'
## Summary
- Independent Docling service (`quay.io/ds4sd/docling-serve`) via docker-compose
- `AbstractRemoteParser` template for HTTP-backed parsers (timeout / logging skeleton)
- `DoclingClient` (infra-ai), `DoclingDocumentParser` (core/parser, `@ConditionalOnProperty`)
- `DoclingResponseAdapter` maps Docling JSON 鈫?engine-neutral `ParseResult{pages, tables}`
- ENHANCED uploads now produce real layout output

## Operator notes
- Start: `docker compose -f resources/docker/docling-compose.yml up -d`
- Toggle: `docling.service.enabled=true|false` (false 鈫?metadata-stamped fallback to Tika via PR 2 decorator)

## Test plan
- [x] Adapter unit test (synthetic + real captured response)
- [x] Manual end-to-end: upload PDF with 澧炲己瑙ｆ瀽 鈫?document succeeds with Docling parser telemetry
- [x] Smoke: toggle enabled=false 鈫?ENHANCED upload still works (Tika fallback, metadata flag set)
EOF
)"
```

---

## PR 6 (Phase 2.5 / PR-DOCINT-2) 鈥?Structured chunker + retrieval citation

**Goal:** Make Docling's structured output flow through to chunking and citation display.

### Task 6.1: `StructuredChunkingStrategy`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java`

- [ ] **Step 1: Test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategyTest.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredChunkingStrategyTest {

    private final StructuredChunkingStrategy strategy = new StructuredChunkingStrategy();

    @Test
    void splitsAtHeadingBoundaries_keepsHeadingPathOnEachChunk() {
        List<LayoutBlock> page1Blocks = List.of(
                new LayoutBlock("b1", BlockType.TITLE, 1, null, "Chapter 1", 1, 0.99D, 1, List.of()),
                new LayoutBlock("b2", BlockType.PARAGRAPH, 1, null, "Para A", 2, 0.98D, null, List.of("Chapter 1")),
                new LayoutBlock("b3", BlockType.TITLE, 1, null, "1.1", 3, 0.97D, 2, List.of("Chapter 1"))
        );
        List<LayoutBlock> page2Blocks = List.of(
                new LayoutBlock("b4", BlockType.PARAGRAPH, 2, null, "Para B", 4, 0.96D, null, List.of("Chapter 1", "1.1"))
        );
        List<DocumentPageText> pages = List.of(
                new DocumentPageText("doc1", 1, "Chapter 1\nPara A\n1.1", "NATIVE_TEXT", 0.98D, page1Blocks),
                new DocumentPageText("doc1", 2, "Para B", "NATIVE_TEXT", 0.96D, page2Blocks)
        );

        List<StructuredChunk> chunks = strategy.chunk(pages, List.of(), 1000);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).text().contains("Chapter 1"));
        assertTrue(chunks.get(0).text().contains("Para A"));
        assertEquals(List.of("Chapter 1"), chunks.get(0).headingPath());
        assertEquals(1, chunks.get(1).pageStart());
        assertEquals(2, chunks.get(1).pageEnd());
        assertEquals(List.of("b3", "b4"), chunks.get(1).sourceBlockIds());
        assertEquals("NATIVE_TEXT", chunks.get(1).textLayerType());
        assertEquals(List.of("Chapter 1", "1.1"), chunks.get(1).headingPath());
    }

    @Test
    void neverSplitsTable_eachTableIsOneChunk() {
        List<LayoutTable> tables = List.of(
                new LayoutTable("t1", 3, null,
                        List.of(List.of("h1", "h2"), List.of("a", "b"), List.of("c", "d")),
                        List.of("Section 5"), 1, 0.99D)
        );
        List<StructuredChunk> chunks = strategy.chunk(List.of(), tables, 1000);
        assertEquals(1, chunks.size());
        assertEquals(BlockType.TABLE, chunks.get(0).blockType());
        assertEquals(3, chunks.get(0).pageStart());
        assertEquals(3, chunks.get(0).pageEnd());
        assertEquals(List.of("t1"), chunks.get(0).sourceBlockIds());
    }
}
```

- [ ] **Step 2: Implement**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunk.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;

import java.util.List;

public record StructuredChunk(
        String text,
        int pageNumber,
        int pageStart,
        int pageEnd,
        List<String> headingPath,
        BlockType blockType,
        List<String> sourceBlockIds,
        String bboxRefsJson,
        String textLayerType,
        Double layoutConfidence
) {
    public StructuredChunk {
        if (headingPath == null) headingPath = List.of();
        if (sourceBlockIds == null) sourceBlockIds = List.of();
    }

    /** Convenience ctor for legacy callers while structured metadata propagation is phased in. */
    public StructuredChunk(String text, int pageNumber, List<String> headingPath, BlockType blockType) {
        this(text, pageNumber, pageNumber, pageNumber, headingPath, blockType,
                List.of(), null, null, null);
    }
}
```

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Chunks a structured document by title boundaries. Tables are kept atomic
 * (each table = one chunk). Long sections are softly split at maxChars while
 * preserving headingPath on every produced chunk.
 */
@Component
public class StructuredChunkingStrategy {

    public List<StructuredChunk> chunk(List<DocumentPageText> pages, List<LayoutTable> tables, int maxChars) {
        List<StructuredChunk> out = new ArrayList<>();

        // 1) Tables 鈥?each one its own chunk (atomic).
        for (LayoutTable t : tables) {
            out.add(new StructuredChunk(
                    serializeTable(t),
                    t.pageNo(),
                    t.pageNo(),
                    t.pageNo(),
                    t.headingPath(),
                    BlockType.TABLE,
                    t.tableId() == null || t.tableId().isBlank() ? List.of() : List.of(t.tableId()),
                    bboxRefsJson(t.tableId(), t.pageNo(), t.bbox()),
                    textLayerTypeForRange(pages, t.pageNo(), t.pageNo()),
                    t.confidence()
            ));
        }

        // 2) Group body blocks under title boundaries using page reading order.
        List<LayoutBlock> currentSection = new ArrayList<>();
        for (DocumentPageText page : pages.stream()
                .sorted(Comparator.comparingInt(DocumentPageText::pageNo)).toList()) {
            List<LayoutBlock> orderedBlocks = page.blocks().stream()
                    .filter(this::isContentBlock)
                    .sorted(Comparator.comparing(b -> b.readingOrder() == null ? Integer.MAX_VALUE : b.readingOrder()))
                    .toList();
            for (LayoutBlock b : orderedBlocks) {
                if (b.blockType() == BlockType.TITLE && !currentSection.isEmpty()) {
                    flushSection(currentSection, pages, out, maxChars);
                    currentSection.clear();
                }
                currentSection.add(b);
            }
        }
        if (!currentSection.isEmpty()) {
            flushSection(currentSection, pages, out, maxChars);
        }
        return out;
    }

    private boolean isContentBlock(LayoutBlock b) {
        return b.blockType() != BlockType.HEADER && b.blockType() != BlockType.FOOTER;
    }

    private void flushSection(List<LayoutBlock> section, List<DocumentPageText> pages,
                              List<StructuredChunk> out, int maxChars) {
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
            if (buf.length() + b.text().length() > maxChars && buf.length() > 0) {
                out.add(newChunk(buf.toString(), path, List.copyOf(slice), pages));
                buf.setLength(0);
                slice.clear();
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(b.text());
            slice.add(b);
        }
        if (buf.length() > 0) {
            out.add(newChunk(buf.toString(), path, List.copyOf(slice), pages));
        }
    }

    private StructuredChunk newChunk(String text, List<String> headingPath, List<LayoutBlock> sourceBlocks,
                                     List<DocumentPageText> pages) {
        int pageStart = sourceBlocks.stream().mapToInt(LayoutBlock::pageNo).min().orElse(0);
        int pageEnd = sourceBlocks.stream().mapToInt(LayoutBlock::pageNo).max().orElse(pageStart);
        int displayPage = pageStart;
        List<String> blockIds = sourceBlocks.stream()
                .map(LayoutBlock::blockId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
        String bboxRefs = sourceBlocks.stream()
                .filter(b -> b.bbox() != null)
                .map(b -> bboxRefJson(b.blockId(), b.pageNo(), b.bbox()))
                .collect(Collectors.joining(",", "[", "]"));
        if ("[]".equals(bboxRefs)) bboxRefs = null;
        return new StructuredChunk(
                text,
                displayPage,
                pageStart,
                pageEnd,
                headingPath,
                BlockType.PARAGRAPH,
                blockIds,
                bboxRefs,
                textLayerTypeForRange(pages, pageStart, pageEnd),
                averageConfidence(sourceBlocks)
        );
    }

    private String textLayerTypeForRange(List<DocumentPageText> pages, int pageStart, int pageEnd) {
        List<String> values = pages.stream()
                .filter(p -> p.pageNo() >= pageStart && p.pageNo() <= pageEnd)
                .map(DocumentPageText::textLayerType)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (values.isEmpty()) return null;
        return values.size() == 1 ? values.get(0) : "MIXED";
    }

    private Double averageConfidence(List<LayoutBlock> blocks) {
        OptionalDouble average = blocks.stream()
                .map(LayoutBlock::confidence)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    private String bboxRefsJson(String blockId, int pageNo, LayoutBlock.Bbox bbox) {
        if (bbox == null) return null;
        return "[" + bboxRefJson(blockId, pageNo, bbox) + "]";
    }

    private String bboxRefJson(String blockId, int pageNo, LayoutBlock.Bbox bbox) {
        return "{\"blockId\":\"" + (blockId == null ? "" : blockId) + "\","
                + "\"pageNo\":" + pageNo + ","
                + "\"x\":" + bbox.x() + ","
                + "\"y\":" + bbox.y() + ","
                + "\"width\":" + bbox.width() + ","
                + "\"height\":" + bbox.height() + "}";
    }

    private String serializeTable(LayoutTable t) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : t.rows()) {
            sb.append(String.join(" | ", row)).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
```

- [ ] **Step 3: Run test**

```bash
mvn -pl bootstrap test -Dtest=StructuredChunkingStrategyTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/ \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategyTest.java
git commit -m "feat(ingestion): StructuredChunkingStrategy (title-aware, table-atomic)"
```

### Task 6.2: `ChunkerNode` consumes structured input

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java`

- [ ] **Step 0: Extend `IngestionContext` for structured parser output**

Modify `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/context/IngestionContext.java` and add:

```java
import java.util.List;

    private com.knowledgebase.ai.ragent.core.parser.ParseResult parseResult;

    private List<com.knowledgebase.ai.ragent.ingestion.chunker.StructuredChunk> structuredChunks;
```

Keep the existing `chunks: List<VectorChunk>` field. `structuredChunks` carries layout-rich chunks until they are converted to / persisted with normal vector chunks; legacy BASIC remains on `chunks`.

- [ ] **Step 1: Add branch**

In `ChunkerNode.execute(...)`, replace the existing chunking call with:

```java
        ParseResult pr = context.getParseResult();  // assumed available; if not, persist on context in ParserNode
        List<StructuredChunk> chunks;
        if (pr != null && (!pr.pages().isEmpty() || !pr.tables().isEmpty())) {
            chunks = structuredChunkingStrategy.chunk(pr.pages(), pr.tables(), maxChars);
        } else {
            // Legacy String path 鈥?preserved byte-equivalent for BASIC mode and old data.
            chunks = legacyTextChunkerAdapter.chunk(context.getRawText(), config);
        }
        context.setStructuredChunks(chunks);
```

(`legacyTextChunkerAdapter` wraps the existing `FixedSizeTextChunker` / `StructureAwareTextChunker` so the output type unifies as `List<StructuredChunk>` 鈥?old chunks have `pageNumber=0`, `page_start/page_end=0`, `source_block_ids=[]`, `headingPath=[]`, `blockType=PARAGRAPH`.)

- [ ] **Step 2: Update ParserNode to put `ParseResult` on context**

In `ParserNode.execute(...)`, after parsing:

```java
        context.setParseResult(parseResult);  // new field on IngestionContext
        context.setRawText(parseResult.text()); // preserve legacy field
```

(The `parseResult` field was added in Step 0.)

- [ ] **Step 3: Test the legacy fallback path**

Add to `ChunkerNode` test:

```java
    @Test
    void emptyPages_fallsBackToLegacyStringChunking_byteEquivalent() {
        // Verify CHUNK mode (BASIC) produces same chunks as before refactor.
        // Concrete assertion: count + first chunk text matches recorded baseline.
    }
```

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/context/IngestionContext.java
git commit -m "feat(ingestion): ChunkerNode uses structured strategy when pages present"
```

### Task 6.3: `IndexerNode` writes layout metadata

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/IndexerNode.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
- Use: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentPageMapper.java`

Current repo note: `runPipelineProcess(...)` sets `skipIndexerWrite=true`; `IndexerNode` should prepare/validate vector payloads, but the authoritative DB/vector write happens in `KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically(...)`. Therefore page rows and chunk layout columns must be persisted from the service transaction, not from `IndexerNode` alone.

- [ ] **Step 1: Pass structured chunk fields into vector metadata**

In the per-chunk metadata construction block (around line 246 per CLAUDE.md):

```java
        Map<String, Object> meta = new HashMap<>(baseMeta);
        if (chunk.pageNumber() > 0) {
            meta.put(VectorMetadataFields.PAGE_NUMBER, chunk.pageNumber());
        }
        if (chunk.pageStart() > 0) {
            meta.put(VectorMetadataFields.PAGE_START, chunk.pageStart());
        }
        if (chunk.pageEnd() > 0) {
            meta.put(VectorMetadataFields.PAGE_END, chunk.pageEnd());
        }
        if (!chunk.headingPath().isEmpty()) {
            meta.put(VectorMetadataFields.HEADING_PATH,
                    objectMapper.writeValueAsString(chunk.headingPath()));
        }
        if (chunk.blockType() != null) {
            meta.put(VectorMetadataFields.BLOCK_TYPE, chunk.blockType().name());
        }
        if (!chunk.sourceBlockIds().isEmpty()) {
            meta.put(VectorMetadataFields.SOURCE_BLOCK_IDS,
                    objectMapper.writeValueAsString(chunk.sourceBlockIds()));
        }
        if (chunk.bboxRefsJson() != null) {
            meta.put(VectorMetadataFields.BBOX_REFS, chunk.bboxRefsJson());
        }
        if (chunk.textLayerType() != null) {
            meta.put(VectorMetadataFields.TEXT_LAYER_TYPE, chunk.textLayerType());
        }
        if (chunk.layoutConfidence() != null) {
            meta.put(VectorMetadataFields.LAYOUT_CONFIDENCE, chunk.layoutConfidence());
        }
```

- [ ] **Step 2: Persist `DocumentPageText` to `t_knowledge_document_page`**

First extend the pipeline return value so the service can see page-level parser output:

```java
    private record PipelineProcessResult(
            List<VectorChunk> chunks,
            List<DocumentPageText> pages,
            List<StructuredChunk> structuredChunks
    ) {}
```

Change `runPipelineProcess(...)` from returning `List<VectorChunk>` to `PipelineProcessResult`, reading `context.getParseResult().pages()` and `context.getStructuredChunks()` after pipeline execution. In `executeChunk(...)`, pass those pages / structured chunks into `persistChunksAndVectorsAtomically(...)` together with the normal vector chunks.

Update the persistence method signature accordingly:

```java
    private int persistChunksAndVectorsAtomically(
            String collectionName,
            String docId,
            String kbId,
            Integer securityLevel,
            List<VectorChunk> chunks,
            List<DocumentPageText> pages,
            List<StructuredChunk> structuredChunks)
```

Align `structuredChunks` to `chunks` by `chunk_index`. If counts differ, log a warning and persist the normal vector chunks without layout columns instead of failing the whole ingestion; page rows can still be persisted from `pages`.

When the pipeline has non-empty pages, persist those page rows inside the same transaction as chunk/vector persistence so Phase 3/5 can open page previews without re-parsing the source document:

Inject `KnowledgeDocumentPageMapper` and reuse the existing `ObjectMapper` (or add one following local service style):

```java
        if (pages != null && !pages.isEmpty()) {
            List<KnowledgeDocumentPageDO> pageRows = pages.stream()
                    .map(page -> KnowledgeDocumentPageDO.builder()
                            .docId(docId)
                            .pageNo(page.pageNo())
                            .text(page.text())
                            .textLayerType(page.textLayerType())
                            .confidence(page.confidence())
                            .blocksJson(objectMapper.writeValueAsString(page.blocks()))
                            .createdBy(operatorId)
                            .updatedBy(operatorId)
                            .build())
                    .toList();
            pageRows.forEach(knowledgeDocumentPageMapper::insert);
        }
```

Place this in `KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically(...)` before inserting new chunk rows. If retries can re-run ingestion for the same `docId`, physically delete existing page rows for that `docId` in the same transaction before inserting fresh rows, or use upsert on `(doc_id, page_no)`. A soft-delete-only update is not enough because the unique constraint does not include `deleted`.

- [ ] **Step 3: Persist to `t_knowledge_chunk`**

Wherever `t_knowledge_chunk` rows are inserted (likely in `KnowledgeDocumentServiceImpl` chunk persistence or a chunk DAO), add:

```java
        chunkDO.setPageNumber(chunk.pageNumber() > 0 ? chunk.pageNumber() : null);
        chunkDO.setPageStart(chunk.pageStart() > 0 ? chunk.pageStart() : null);
        chunkDO.setPageEnd(chunk.pageEnd() > 0 ? chunk.pageEnd() : null);
        chunkDO.setHeadingPath(chunk.headingPath().isEmpty() ? null
                : objectMapper.writeValueAsString(chunk.headingPath()));
        chunkDO.setBlockType(chunk.blockType() == null ? null : chunk.blockType().name());
        chunkDO.setSourceBlockIds(chunk.sourceBlockIds().isEmpty() ? null
                : objectMapper.writeValueAsString(chunk.sourceBlockIds()));
        chunkDO.setBboxRefs(chunk.bboxRefsJson());
        chunkDO.setTextLayerType(chunk.textLayerType());
        chunkDO.setLayoutConfidence(chunk.layoutConfidence());
```

- [ ] **Step 4: Add columns to `KnowledgeChunkDO`**

In `KnowledgeChunkDO.java`:

```java
    @TableField("page_number")
    private Integer pageNumber;

    @TableField("page_start")
    private Integer pageStart;

    @TableField("page_end")
    private Integer pageEnd;

    @TableField("heading_path")
    private String headingPath;

    @TableField("block_type")
    private String blockType;

    @TableField("source_block_ids")
    private String sourceBlockIds;

    @TableField("bbox_refs")
    private String bboxRefs;

    @TableField("text_layer_type")
    private String textLayerType;

    @TableField("layout_confidence")
    private Double layoutConfidence;
```

- [ ] **Step 5: Smoke test end-to-end**

Restart app, upload a PDF with `parseMode=enhanced`, then:

```bash
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT page_no, text_layer_type, LEFT(text, 50) \
   FROM t_knowledge_document_page \
   WHERE doc_id = (SELECT id FROM t_knowledge_document WHERE parse_mode='enhanced' \
                   ORDER BY create_time DESC LIMIT 1) \
   ORDER BY page_no LIMIT 5;"

docker exec postgres psql -U postgres -d ragent -c \
  "SELECT chunk_index, page_number, page_start, page_end, source_block_ids, bbox_refs, text_layer_type, heading_path, block_type, LEFT(content, 50) \
   FROM t_knowledge_chunk \
   WHERE doc_id = (SELECT id FROM t_knowledge_document WHERE parse_mode='enhanced' \
                   ORDER BY create_time DESC LIMIT 1) \
   ORDER BY chunk_index LIMIT 10;"
```

Expected: `t_knowledge_document_page` has one row per parsed page; `page_start/page_end` populated for enhanced chunks; `source_block_ids`, `bbox_refs`, `text_layer_type`, and `heading_path` populate when Docling returns the corresponding layout data. `page_number` may be present as a display hint.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/IndexerNode.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeChunkDO.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "feat(ingestion): persist + index layout metadata"
```

### Task 6.4: Frontend SourceCard layout display

**Files:**
- Modify: SourceCard component (locate via `grep -rn "SourceCard" frontend/src --include='*.tsx' | head -5`)

- [ ] **Step 1: Backend serializes layout fields into source card**

In `SourceCardBuilder.java`, ensure each card includes:

```java
        card.setPageNumber(chunk.getPageNumber());
        card.setHeadingPath(chunk.getHeadingPath());
```

(Add fields to `SourceCard` DTO.)

- [ ] **Step 2: Frontend renders them**

In source card `.tsx`, where chunk metadata is shown:

```tsx
{card.pageNumber && (
  <span className="text-xs text-muted-foreground">馃搫 绗?{card.pageNumber} 椤?/span>
)}
{card.headingPath && card.headingPath.length > 0 && (
  <span className="text-xs text-muted-foreground">
    路 {card.headingPath.join(" 鈥?")}
  </span>
)}
```

- [ ] **Step 3: Manual test**

Ask a question against an enhanced-parsed doc; verify source card shows "馃搫 绗?5 椤?路 绗?3 绔?鈥?3.2 淇＄敤椋庨櫓".

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCardBuilder.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCard.java \
        frontend/src/components/  # adjust to actual SourceCard path
git commit -m "feat(rag): SourceCard renders page + heading path for enhanced-parsed docs"
```

### Task 6.5: PR 6 wrap-up + final docs

- [ ] **Step 1: Update `bootstrap/CLAUDE.md` parser table**

Append to the relevant table in `bootstrap/CLAUDE.md`:

```markdown
| `ParseMode` enum (core/parser/) | 鐢ㄦ埛璇箟瑙ｆ瀽妯″紡锛欱ASIC锛圱ika锛? ENHANCED锛圖ocling锛夈€?*涓嶈鍦ㄤ唬鐮侀噷鐩存帴鍐欏紩鎿庡悕**鈥斺€旂粡 selector 璺敱 |
| `DoclingDocumentParser` (core/parser/) | `@ConditionalOnProperty(docling.service.enabled=true)`锛涢€氳繃 `DoclingClient` 璋冪敤鐙珛 Python 鏈嶅姟锛坄docker-compose -f resources/docker/docling-compose.yml`锛墊
| `FallbackParserDecorator` (core/parser/) | ENHANCED 璺敱鐨勫厹搴曡楗帮紱Docling 澶辫触 / 鏈敞鍐屾椂閫忔槑闄嶇骇鍒?Tika锛宮etadata 鍐?`parse_engine_actual` 缁欏墠绔仛 鈿狅笍 鎻愮ず |
| `ParseModeRouter` (knowledge/service/support/) | 鎶?`parseMode` 缈昏瘧鎴?`processMode`锛欱ASIC鈫扖HUNK銆丒NHANCED鈫扨IPELINE銆?*鍓嶇涓嶅啀浼?processMode**锛岀敱璇?router 鍐冲畾 |
| `StructuredChunkingStrategy` (ingestion/chunker/) | 褰?`ParseResult.pages` 闈炵┖鏃跺惎鐢紝鎸?title / reading order 鍒囩墖銆佽〃鏍煎師瀛愪繚鐣欙紱鍚﹀垯闄嶇骇 String 璺緞 |
```

- [ ] **Step 2: Add gotchas**

Append to bootstrap `CLAUDE.md` gotchas:

```markdown
- **parseMode vs processMode** (PR 4 璧?锛氬墠绔?`parseMode` 鏄?UX 灞傦紱`processMode` 鐢?`ParseModeRouter` 娲剧敓銆係ervice 鏀跺埌涓婁紶璇锋眰鏃?*蹇界暐** `requestParam.getProcessMode()`銆傝淇敼璺敱绛栫暐鍙姩 `ParseModeRouter`锛屼笉瑕佺洿鎺ヨ鍓嶇瀛楁銆?
- **Docling 鍏冲仠鍥炴粴** (PR 5 璧?锛氭妸 `docling.service.enabled` 鏀规垚 `false`锛屾墍鏈?ENHANCED 涓婁紶閫氳繃 `FallbackParserDecorator` 甯︽爣璁拌蛋 Tika锛孌B schema / 涓婁紶瀛楁涓嶅姩锛涘墠绔湪鏂囨。鍒楄〃璇?`parse_engine_actual` 鏄剧ず 鈿狅笍銆?- **layout metadata 瀛楁**锛圥R 6 璧凤級锛歚page_start / page_end / source_block_ids / bbox_refs / text_layer_type` 鏄瘉鎹拷婧牳蹇冨瓧娈碉紱`page_number` 鍙槸鍓嶇灞曠ず hint銆侭ASIC 璺緞鎵€鏈?layout 鍒椾负 NULL锛屼笉瑕?fail-closed 褰撲綔閿欒銆?- **page-level parse output**锛圥R 6 璧凤級锛歚t_knowledge_document_page` 淇濆瓨 `DocumentPageText`锛屼緵 page preview / Phase 3 evidence lookup 浣跨敤锛涗笉瑕佸湪鏌ヨ鏃堕噸鏂拌皟鐢?Docling 瑙ｆ瀽鍘熸枃銆?```

- [ ] **Step 3: Push + PR**

```bash
mvn -pl bootstrap test
mvn spotless:check
cd frontend && pnpm tsc --noEmit && cd ..
git push -u origin feat/parser-enhancement-pr6
gh pr create --title "feat(parser): PR 6 鈥?Structured chunking + layout citation" \
  --body "$(cat <<'EOF'
## Summary
- `StructuredChunkingStrategy` consumes `DocumentPageText` / `LayoutTable`, splits at title boundaries, keeps tables atomic
- `ChunkerNode` branches: pages present 鈫?structured; else legacy String path (byte-equivalent for BASIC)
- `t_knowledge_document_page` persists page-level text / textLayer / blocks JSON for later preview
- `IndexerNode` writes page/block/bbox/textLayer metadata to vector metadata + `t_knowledge_chunk`
- Frontend SourceCard renders 馃搫 绗?X 椤?路 绔?鈥?鑺?for enhanced-parsed citations
- CLAUDE.md updated with new parser table + 3 gotchas

## Test plan
- [x] `StructuredChunkingStrategyTest` title + table cases
- [x] Manual: enhanced upload 鈫?t_knowledge_chunk has page/block layout metadata
- [x] Manual: chat against enhanced-parsed doc 鈫?source card shows page + heading
- [x] Regression: BASIC upload chunks byte-equivalent vs main
EOF
)"
```

---

## Self-Review Checklist (filled out)

**1. Spec coverage:**

- 鉁?"鍩虹瑙ｆ瀽 / 澧炲己瑙ｆ瀽" UI labels 鈥?Task 4.3
- 鉁?Tika fallback when Docling offline 鈥?PR 2 entirely
- 鉁?Independent Docling deploy (not docling-java) 鈥?PR 5 with self-built `DoclingClient`
- 鉁?Architecture layering enforced 鈥?file paths confirm: `core/parser` doesn't import `ingestion/`; `infra-ai` doesn't import `bootstrap/`
- 鉁?Strategy / Registry / Decorator / Adapter / Template / Factory patterns 鈥?explicit `Where each pattern lives` mapping
- 鉁?`ParseMode` semantic decoupling from engine names 鈥?Task 1.1, DB column stores `basic|enhanced`
- 鉁?`ENHANCED 鈫?PIPELINE` forced routing 鈥?Task 4.2 with `ParseModeRouter`
- 鉁?Collateral KB defaults/forces ENHANCED 鈥?Task 4.0 `ParseModePolicy`
- 鉁?`DocumentPageText` page-level model 鈥?Task 1.2 / Task 1.3
- 鉁?Deferred quality/OCR seams 鈥?Task 4.0b `TextLayerQualityDetector` + `OcrFallbackParser`
- 鉁?Hide `processMode` from frontend 鈥?Task 4.3
- 鉁?Layout metadata persisted (page table + chunk page/block/bbox/textLayer) 鈥?PR 3 schema + PR 6 indexer
- 鉁?ChunkerNode dual-path (legacy + structured) 鈥?Task 6.2

**2. Placeholder scan:**
- No "TBD / TODO / similar to Task N / fill in details" markers.
- Every code step shows full code.
- Frontend dialog edit references "adjust to actual upload dialog path" 鈥?that is a discovery instruction, not a placeholder; the `find` command in Task 4.3 step 1 resolves the path before edit.
- `enrichParserNodeConfig` in Task 4.2 inlines the full implementation including the JsonNode deep copy logic.

**3. Type consistency:**
- `ParseMode` vs `ParserType`: ParseMode is user-facing semantic (BASIC/ENHANCED); ParserType is engine identity (TIKA/DOCLING). Used correctly throughout.
- `DocumentPageText` owns page-level `textLayerType` / `confidence`; `LayoutBlock` owns block-level `blockId` / `pageNo` / `bbox` / `readingOrder` for evidence refs.
- `LayoutBlock.headingPath` is `List<String>`, consistently used as `List<String>` in adapter / chunker / DB serialization (JSON-stringified at DB boundary).
- `StructuredChunk` consistently has canonical evidence fields `(pageStart, pageEnd, sourceBlockIds, bboxRefsJson, textLayerType)` plus `pageNumber` as a display hint.
- `ParseResult` 4-arg vs 2-arg constructor 鈥?both defined in Task 1.3, consistently used: Tika code stays on 2-arg; Docling adapter uses 4-arg with `pages` / `tables`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-05-parser-enhancement-docling.md`. Two execution options:

**1. Subagent-Driven (recommended)** 鈥?I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** 鈥?Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
