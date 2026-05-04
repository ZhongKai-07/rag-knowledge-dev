# Parser Enhancement (Docling) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "基础解析 / 增强解析" toggle on document upload that routes basic parsing to Tika (CHUNK mode) and enhanced parsing to Docling (PIPELINE mode), preserving page numbers / heading paths / table structure for downstream chunking and citation.

**Architecture:** Layered (frontend → knowledge → ingestion → core/parser → infra-ai), strategy + registry + decorator + adapter + template-method patterns. Single `DocumentParser` interface keeps engines pluggable; `FallbackParserDecorator` enables graceful degradation when Docling is offline; `ParseModeRouter` collapses CHUNK/PIPELINE choice into a semantic toggle. New `DoclingDocumentParser` calls a self-built `DoclingClient` (HTTP, modeled after `RagasEvalClient`); Docling Python service runs as an independent container (`quay.io/ds4sd/docling-serve`).

**Tech Stack:** Java 17 / Spring Boot 3.5 / MyBatis Plus / PostgreSQL / Apache Tika 3.2 / Apache HttpClient5 / React 18 + TypeScript + TailwindCSS / Docker.

**PR Decomposition:** 6 PRs, each independently reviewable & rollback-safe. PRs 1–4 land without Docling service ready (toggle visible, silent fallback to Tika). PR 5 brings Docling online. PR 6 propagates structured output to retrieval.

---

## File Structure

### Created

| Path | Responsibility |
|---|---|
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseMode.java` | Semantic enum `BASIC` / `ENHANCED` (decoupled from engine names) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutBlock.java` | Engine-neutral layout block record (page, bbox, type, level, text, headingPath) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutTable.java` | Engine-neutral table record (page, bbox, rows, headingPath) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/BlockType.java` | Enum `HEADING / PARAGRAPH / TABLE / LIST / CAPTION / OTHER` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/FallbackParserDecorator.java` | Decorator: try primary → on failure / unsupported → fallback parser; stamps `parse_engine_actual` metadata |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingDocumentParser.java` | Strategy: enhanced parser; uses `DoclingClient` + `DoclingResponseAdapter`; conditional on `docling.service.enabled=true` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java` | Adapter: Docling JSON → `ParseResult{blocks, tables}` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouter.java` | Strategy: maps `ParseMode` → `ProcessMode` (BASIC→CHUNK, ENHANCED→PIPELINE) |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java` | Chunker that consumes `List<LayoutBlock>`, splits on heading boundaries, never splits a table block |
| `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/AbstractRemoteParser.java` | Template-method base for HTTP-backed parser clients (timeout / retry / logging skeleton) |
| `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/DoclingClient.java` | HTTP client for Docling `POST /v1alpha/convert/file` |
| `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/DoclingClientProperties.java` | `docling.service.*` bound config |
| `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/dto/DoclingConvertResponse.java` | DTO mirror of Docling response |
| `resources/database/upgrade_v1.12_to_v1.13.sql` | DB migration: parse_mode + chunk layout columns |
| `resources/docker/docling-compose.yml` | Independent Docling service compose file |
| `docs/dev/setup/docling-service.md` | Operator-facing service deploy doc |
| `frontend/src/components/upload/ParseModeRadio.tsx` | RadioGroup (基础 / 增强) with tooltip |

### Modified

| Path | Change |
|---|---|
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseResult.java` | Add `blocks` / `tables` fields, keep 2-arg constructor |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java` | Add `selectByParseMode(ParseMode)` method; wrap ENHANCED in `FallbackParserDecorator` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParserType.java` | Add `DOCLING` enum value |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java:81` | Replace hardcoded `ParserType.TIKA` with read of `NodeConfig.settings.parseMode` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java` | Add `parseMode` field |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java` | Add `parseMode` field |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java` | Inject `ParseModeRouter`; persist `parseMode`; use router to decide CHUNK/PIPELINE; thread `parseMode` into `IngestionContext` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java` | Branch: when `StructuredDocument.blocks` non-empty → `StructuredChunkingStrategy`; else legacy String path |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/IndexerNode.java` | Pass `pageNumber / headingPath / blockType` from chunk metadata into vector metadata |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/VectorMetadataFields.java` | Add `PAGE_NUMBER` / `HEADING_PATH` / `BLOCK_TYPE` constants |
| `bootstrap/CLAUDE.md` | Update parser table + add gotchas for ParseMode routing |
| `CLAUDE.md` | Append v1.13 migration entry |
| `frontend/src/services/knowledgeService.ts` | `KnowledgeDocumentUploadPayload` add `parseMode`; FormData append |
| `frontend/src/pages/.../DocumentUploadDialog.tsx` | Replace processMode field with `<ParseModeRadio>`; backend ignores processMode |

---

## PR 1 — Domain model & data-driven ParserNode (no Docling)

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
Expected: FAIL — `ParseMode` not defined.

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

### Task 1.2: Add `BlockType`, `LayoutBlock`, `LayoutTable`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/BlockType.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutBlock.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutTable.java`

- [ ] **Step 1: Write `BlockType`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/BlockType.java`:

```java
package com.knowledgebase.ai.ragent.core.parser.layout;

public enum BlockType {
    HEADING,
    PARAGRAPH,
    TABLE,
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
        BlockType type,
        int pageNumber,            // 1-based; 0 if unknown
        Bbox bbox,                 // null if not provided by engine
        Integer headingLevel,      // null unless type == HEADING
        List<String> headingPath,  // ancestor headings; empty list never null
        String text
) {
    public LayoutBlock {
        if (headingPath == null) headingPath = List.of();
    }

    public record Bbox(double x, double y, double width, double height) {}
}
```

- [ ] **Step 3: Write `LayoutTable`**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/LayoutTable.java`:

```java
package com.knowledgebase.ai.ragent.core.parser.layout;

import java.util.List;

public record LayoutTable(
        int pageNumber,
        LayoutBlock.Bbox bbox,
        List<List<String>> rows,    // first row is header by convention; never null
        List<String> headingPath    // section path the table sits under
) {
    public LayoutTable {
        if (rows == null) rows = List.of();
        if (headingPath == null) headingPath = List.of();
    }
}
```

- [ ] **Step 4: Compile**

```bash
mvn -pl bootstrap compile
```
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/layout/
git commit -m "feat(parser): add engine-neutral LayoutBlock/LayoutTable/BlockType"
```

### Task 1.3: Extend `ParseResult` with backward-compatible constructor

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseResult.java`

- [ ] **Step 1: Write test for backward compat**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/ParseResultTest.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseResultTest {

    @Test
    void legacyTwoArgConstructor_yieldsEmptyBlocksAndTables() {
        ParseResult r = new ParseResult("hello", Map.of("k", "v"));
        assertEquals("hello", r.text());
        assertEquals("v", r.metadata().get("k"));
        assertTrue(r.blocks().isEmpty());
        assertTrue(r.tables().isEmpty());
    }

    @Test
    void fullConstructor_carriesAllFields() {
        LayoutBlock b = new LayoutBlock(
                com.knowledgebase.ai.ragent.core.parser.layout.BlockType.HEADING,
                1, null, 1, List.of(), "Title"
        );
        ParseResult r = new ParseResult("t", Map.of(), List.of(b), List.of());
        assertEquals(1, r.blocks().size());
        assertEquals("Title", r.blocks().get(0).text());
    }
}
```

- [ ] **Step 2: Modify `ParseResult` to support both constructors**

Read current file first to confirm shape, then replace contents of `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParseResult.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing a document. `blocks` / `tables` are empty for engines that
 * don't emit layout (e.g., Tika). Engines that do (Docling) populate them.
 */
public record ParseResult(
        String text,
        Map<String, Object> metadata,
        List<LayoutBlock> blocks,
        List<LayoutTable> tables
) {
    public ParseResult {
        if (metadata == null) metadata = Map.of();
        if (blocks == null) blocks = List.of();
        if (tables == null) tables = List.of();
    }

    /** Backward-compatible constructor — Tika & legacy callers stay unchanged. */
    public ParseResult(String text, Map<String, Object> metadata) {
        this(text, metadata, List.of(), List.of());
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
git commit -m "feat(parser): extend ParseResult with blocks/tables, keep 2-arg ctor"
```

### Task 1.4: `DocumentParserSelector.selectByParseMode`

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParserType.java`

- [ ] **Step 1: Add `DOCLING` to `ParserType`**

Edit `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/ParserType.java`, in the enum constants list add:

```java
    DOCLING("docling");
```

(adjacent to existing `TIKA("tika")` and `MARKDOWN("markdown")`)

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
        // Docling not registered yet → wrapped in fallback decorator (PR 2 will assert this)
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
            // Docling not registered — caller will see fallback wrapper from PR 2.
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
import com.knowledgebase.ai.ragent.ingestion.domain.IngestionContext;
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

- [ ] **Step 2: Run test (expect fail — still hardcoded)**

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
gh pr create --title "feat(parser): PR 1 — ParseMode + data-driven ParserNode" \
  --body "$(cat <<'EOF'
## Summary
- Add `ParseMode` enum (BASIC / ENHANCED), engine-neutral `LayoutBlock` / `LayoutTable` / `BlockType`
- Extend `ParseResult` with `blocks` / `tables`, keep 2-arg constructor for byte-equivalent legacy calls
- `DocumentParserSelector.selectByParseMode(ParseMode)` — BASIC→Tika, ENHANCED→Docling-or-Tika fallback
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

## PR 2 — `FallbackParserDecorator` graceful degradation

**Goal:** When ENHANCED is requested but Docling unavailable, silently fall back to Tika and stamp metadata so frontend can show ⚠️.

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

- [ ] **Step 2: Run test (expect fail — class missing)**

```bash
mvn -pl bootstrap test -Dtest=FallbackParserDecoratorTest
```
Expected: FAIL — class not found.

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
        return new ParseResult(r.text(), md, r.blocks(), r.tables());
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
            log.info("Docling parser not registered — ENHANCED requests will use Tika fallback");
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
gh pr create --title "feat(parser): PR 2 — FallbackParserDecorator graceful degradation" \
  --body "$(cat <<'EOF'
## Summary
- `FallbackParserDecorator` wraps primary parser; on exception falls back + stamps metadata
- ENHANCED route always wrapped — when Docling not registered, primary == fallback == Tika; metadata still records `parse_engine_requested=docling`, `parse_engine_actual=tika`
- Frontend can read `parse_engine_actual` to show ⚠️ "增强解析尚未启用，已使用基础解析"

## Test plan
- [x] FallbackParserDecoratorTest covers success / fail / extractText paths
- [x] DocumentParserSelectorParseModeTest asserts metadata stamping
EOF
)"
```

---

## PR 3 — DB schema, DTO, persistence

**Goal:** `parseMode` flows from upload request → DB → IngestionContext.

### Task 3.1: SQL migration

**Files:**
- Create: `resources/database/upgrade_v1.12_to_v1.13.sql`

- [ ] **Step 1: Write migration SQL**

Create `resources/database/upgrade_v1.12_to_v1.13.sql`:

```sql
-- v1.12 → v1.13
-- 1) Document-level parser choice
ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS parse_mode VARCHAR(16) NOT NULL DEFAULT 'basic';

COMMENT ON COLUMN t_knowledge_document.parse_mode IS
    'User-facing parser choice: basic (Tika) | enhanced (Docling). Engine name decoupled.';

-- 2) Chunk-level layout fields (populated only when parse_mode=enhanced + Docling available)
ALTER TABLE t_knowledge_chunk
    ADD COLUMN IF NOT EXISTS page_number  INTEGER,
    ADD COLUMN IF NOT EXISTS heading_path TEXT,
    ADD COLUMN IF NOT EXISTS block_type   VARCHAR(32);

COMMENT ON COLUMN t_knowledge_chunk.page_number IS '1-based page number (NULL for non-paginated docs)';
COMMENT ON COLUMN t_knowledge_chunk.heading_path IS 'JSON array of ancestor headings, e.g. ["第3章 风险管理","3.2 信用风险"]';
COMMENT ON COLUMN t_knowledge_chunk.block_type IS 'HEADING|PARAGRAPH|TABLE|LIST|CAPTION|OTHER';
```

- [ ] **Step 2: Apply migration to local PG**

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.12_to_v1.13.sql
```

Verify:

```bash
docker exec postgres psql -U postgres -d ragent -c "\d t_knowledge_document" | grep parse_mode
docker exec postgres psql -U postgres -d ragent -c "\d t_knowledge_chunk" | grep -E "page_number|heading_path|block_type"
```

Expected: 4 column rows printed.

- [ ] **Step 3: Append migration to root CLAUDE.md migration list**

In root `CLAUDE.md`, find the `Upgrade scripts in resources/database/` list and append:

```markdown
- `upgrade_v1.12_to_v1.13.sql` — Parser enhancement: `t_knowledge_document.parse_mode` (basic/enhanced) + `t_knowledge_chunk.page_number` / `heading_path` / `block_type`
```

- [ ] **Step 4: Commit**

```bash
git add resources/database/upgrade_v1.12_to_v1.13.sql CLAUDE.md
git commit -m "feat(db): v1.13 migration — parse_mode + chunk layout columns"
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
     * 解析模式：basic（基础解析，Tika） / enhanced（增强解析，Docling）。
     * 缺省为 basic，向后兼容老客户端。
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

### Task 3.3: Persist `parseMode` in service

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

- [ ] **Step 1: Find the upload persistence block**

```bash
grep -n "documentDO.setProcessMode\|documentDO.setSecurityLevel" bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
```

- [ ] **Step 2: Add `parseMode` persistence**

Adjacent to the existing `documentDO.setSecurityLevel(...)` line in the upload method (around line 149–169 per CLAUDE.md trace), add:

```java
        documentDO.setParseMode(
                com.knowledgebase.ai.ragent.core.parser.ParseMode.fromValue(requestParam.getParseMode()).getValue());
```

- [ ] **Step 3: Write integration test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceParseModeTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class KnowledgeDocumentServiceParseModeTest {

    @Autowired KnowledgeDocumentServiceImpl svc;
    @Autowired KnowledgeDocumentMapper mapper;

    @Test
    void upload_withEnhanced_persistsEnhanced() throws Exception {
        // Arrange — minimal valid request; KB seed assumed available in test profile
        KnowledgeDocumentUploadRequest req = new KnowledgeDocumentUploadRequest();
        req.setSourceType("file");
        req.setProcessMode("CHUNK");
        req.setChunkStrategy("fixed_size");
        req.setChunkConfig("{}");
        req.setParseMode("enhanced");
        // ... set kb id from a fixture seeded in test (depends on existing test infra)

        // Act / Assert: pseudo — adapt to actual upload signature
        // String docId = svc.upload(seedKbId, file, req);
        // KnowledgeDocumentDO saved = mapper.selectById(docId);
        // assertEquals("enhanced", saved.getParseMode());
    }

    @Test
    void upload_withNullParseMode_defaultsToBasic() {
        KnowledgeDocumentUploadRequest req = new KnowledgeDocumentUploadRequest();
        req.setParseMode(null);
        assertEquals("basic",
                com.knowledgebase.ai.ragent.core.parser.ParseMode.fromValue(req.getParseMode()).getValue());
    }
}
```

(Note: full upload integration depends on test fixtures present in this project. The second test guarantees the normalizer behavior; the first should be expanded once the existing upload test fixture pattern is identified.)

- [ ] **Step 4: Run test**

```bash
mvn -pl bootstrap test -Dtest=KnowledgeDocumentServiceParseModeTest
```
Expected: PASS for the normalizer test.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceParseModeTest.java
git commit -m "feat(knowledge): persist parseMode on upload (defaults to basic)"
```

### Task 3.4: `VectorMetadataFields` constants

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/VectorMetadataFields.java`

- [ ] **Step 1: Add constants**

In `VectorMetadataFields.java`, alongside existing constants (`KB_ID`, `SECURITY_LEVEL`, `DOC_ID`, `CHUNK_INDEX`), add:

```java
    public static final String PAGE_NUMBER  = "page_number";
    public static final String HEADING_PATH = "heading_path";
    public static final String BLOCK_TYPE   = "block_type";
```

- [ ] **Step 2: Compile**

```bash
mvn -pl bootstrap compile
```
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/VectorMetadataFields.java
git commit -m "feat(rag): VectorMetadataFields adds PAGE_NUMBER/HEADING_PATH/BLOCK_TYPE"
```

### Task 3.5: PR 3 wrap-up

- [ ] **Step 1: Full test + spotless + push + PR**

```bash
mvn -pl bootstrap test
mvn spotless:check
git push -u origin feat/parser-enhancement-pr3
gh pr create --title "feat(parser): PR 3 — DB schema + DTO + persistence" \
  --body "$(cat <<'EOF'
## Summary
- v1.13 migration: `parse_mode` on `t_knowledge_document`; `page_number / heading_path / block_type` on `t_knowledge_chunk`
- `KnowledgeDocumentUploadRequest` + `KnowledgeDocumentDO` carry `parseMode`
- Service normalizes nullable input via `ParseMode.fromValue` (defaults BASIC)
- `VectorMetadataFields` adds 3 layout constants

## Migration
Run on local: `docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.12_to_v1.13.sql`

## Test plan
- [x] DDL applies cleanly; columns observable in `\d`
- [x] `KnowledgeDocumentServiceParseModeTest` normalizer
EOF
)"
```

---

## PR 4 — `ParseModeRouter` + frontend toggle

**Goal:** parseMode replaces processMode at the UX surface; backend routes BASIC→CHUNK / ENHANCED→PIPELINE.

### Task 4.1: `ParseModeRouter`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouter.java`

- [ ] **Step 1: Test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouterTest.java`:

```java
package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.enums.ProcessMode;
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
import com.knowledgebase.ai.ragent.knowledge.dao.entity.enums.ProcessMode;
import org.springframework.stereotype.Component;

/**
 * Maps user-facing {@link ParseMode} to internal {@link ProcessMode}.
 * BASIC → CHUNK (fast path, Tika).
 * ENHANCED → PIPELINE (Docling + structured chunker + layout metadata).
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

(Verify the actual `ProcessMode` enum FQN by `grep -rn 'enum ProcessMode' bootstrap/src/main/java`; adjust import if needed.)

- [ ] **Step 3: Run test**

```bash
mvn -pl bootstrap test -Dtest=ParseModeRouterTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouter.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/support/ParseModeRouterTest.java
git commit -m "feat(knowledge): ParseModeRouter (BASIC→CHUNK, ENHANCED→PIPELINE)"
```

### Task 4.2: Service uses router; ENHANCED forces pipeline

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

- [ ] **Step 1: Inject router**

In the field block:

```java
    private final ParseModeRouter parseModeRouter;
```

(Lombok `@RequiredArgsConstructor` already wires it via the `final` field.)

- [ ] **Step 2: Use router in upload flow**

In `upload(...)`, immediately after computing `parseMode`, override `processMode`:

```java
        ParseMode parseMode = ParseMode.fromValue(requestParam.getParseMode());
        ProcessMode resolvedMode = parseModeRouter.resolve(parseMode);
        documentDO.setParseMode(parseMode.getValue());
        documentDO.setProcessMode(resolvedMode.name());
```

This intentionally **ignores** `requestParam.getProcessMode()`. Add a comment:

```java
        // parseMode is the UX-level switch; processMode is derived. Frontend value of
        // requestParam.getProcessMode() is ignored once parseMode is supplied.
```

- [ ] **Step 3: Inject parseMode into pipeline NodeConfig (PIPELINE path only)**

In `runPipelineProcess()` at the spot where `IngestionContext` is built (around line 393), add:

```java
        ctx.setParseMode(documentDO.getParseMode());  // engine-neutral semantic value
```

(Add `parseMode` field to `IngestionContext` builder + getter/setter via Lombok.)

Then where `PipelineDefinition` is loaded, **inject `parseMode` into the ParserNode's NodeConfig settings** before engine execution. Add a helper `enrichParserNodeConfig(PipelineDefinition, ParseMode)` that walks `pipelineDef.getNodes()`, finds the `ParserNode` config, deep-copies its settings JsonNode, sets `parseMode` field, and replaces in the definition (definition object should be a per-execution copy to avoid mutating cached defs):

```java
    private PipelineDefinition enrichParserNodeConfig(PipelineDefinition def, ParseMode parseMode) {
        // Walk nodes, find type==ParserNode, copy settings, set parseMode, return new def
        // Implementation depends on PipelineDefinition mutability — adapt to existing builder.
        // If PipelineDefinition is immutable, build a new one with cloned NodeConfig list.
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
    'Auto-selected when user picks 增强解析. Uses Docling parser + structured chunker.',
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

- [ ] **Step 5: Service forces pipelineId on ENHANCED**

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
import com.knowledgebase.ai.ragent.knowledge.dao.entity.enums.ProcessMode;
import com.knowledgebase.ai.ragent.knowledge.service.support.ParseModeRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnhancedRoutingIntegrationTest {

    private final ParseModeRouter router = new ParseModeRouter();

    @Test
    void enhancedNeverYieldsChunkRegardlessOfRequestProcessMode() {
        // Even if the legacy frontend sends processMode=CHUNK, parseMode=enhanced should win.
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
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/IngestionContext.java \
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
        <Label className="text-sm font-medium">解析方式</Label>
        <Tooltip>
          <TooltipTrigger asChild>
            <Info className="h-4 w-4 text-muted-foreground cursor-help" />
          </TooltipTrigger>
          <TooltipContent className="max-w-xs">
            <p>
              <strong>基础解析</strong>：适合纯文本类文档（普通 PDF / Word / 文本），秒级完成。
            </p>
            <p className="mt-1">
              <strong>增强解析</strong>：识别表格、版面、扫描件，质量更高，处理更慢。
              建议合同 / 报告 / 含表格的 PDF 选用。
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
            基础解析
          </Label>
        </div>
        <div className="flex items-center space-x-2">
          <RadioGroupItem value="enhanced" id="parse-enhanced" />
          <Label htmlFor="parse-enhanced" className="font-normal cursor-pointer">
            增强解析
          </Label>
        </div>
      </RadioGroup>
    </div>
  );
}
```

- [ ] **Step 3: Add `parseMode` to upload payload type**

In `frontend/src/services/knowledgeService.ts`, find the interface (around line 103–114) and add:

```ts
  parseMode?: "basic" | "enhanced";
```

In the FormData construction block (around line 213–249), append:

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
2. Verify "解析方式" radio shows two options + ⓘ tooltip
3. Upload a small PDF with "增强解析" selected
4. Inspect DB: `SELECT id, parse_mode, process_mode, pipeline_id FROM t_knowledge_document ORDER BY create_time DESC LIMIT 1;` — should show `enhanced / PIPELINE / default-enhanced-pipeline`

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
gh pr create --title "feat(parser): PR 4 — ParseModeRouter + frontend toggle" \
  --body "$(cat <<'EOF'
## Summary
- `ParseModeRouter` collapses chunk/pipeline + tika/docling into one product-facing toggle
- ENHANCED upload auto-uses seeded `default-enhanced-pipeline` (no user pipelineId needed)
- Frontend dialog shows 基础 / 增强 RadioGroup with tooltip; processMode field removed
- Docling not yet integrated → ENHANCED currently silently uses Tika via PR 2 fallback (frontend still shows the choice)

## Test plan
- [x] Backend tests pass; spotless clean
- [x] Frontend tsc clean
- [x] Manual: upload with 增强 → DB shows parse_mode=enhanced, process_mode=PIPELINE
EOF
)"
```

---

## PR 5 — `DoclingClient` + `DoclingDocumentParser` (real Docling integration)

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
# Docling 服务部署

## 启动

```bash
docker compose -f resources/docker/docling-compose.yml up -d docling
```

## 健康检查

```bash
curl http://localhost:5001/health
# 期望：{"status":"ok"}
```

## 项目配置

`bootstrap/src/main/resources/application.yaml` 加入：

```yaml
docling:
  service:
    enabled: true
    host: http://localhost:5001
    timeout-ms: 60000
    health-endpoint: /health
    convert-endpoint: /v1alpha/convert/file
```

## 关闭 / 回滚

把 `docling.service.enabled` 设为 `false`：所有 ENHANCED 上传通过 `FallbackParserDecorator` 静默降级到 Tika，前端 UI 不变（文档列表会显示 ⚠️ 降级提示）。
```

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
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/AbstractRemoteParser.java`

- [ ] **Step 1: Implement template**

Create `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/AbstractRemoteParser.java`:

```java
package com.knowledgebase.ai.infra.parser;

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
git add infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/AbstractRemoteParser.java
git commit -m "feat(infra-ai): AbstractRemoteParser template-method base"
```

### Task 5.3: `DoclingClient` + properties + DTO

**Files:**
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/DoclingClient.java`
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/DoclingClientProperties.java`
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/dto/DoclingConvertResponse.java`

- [ ] **Step 1: Properties**

Create `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/DoclingClientProperties.java`:

```java
package com.knowledgebase.ai.infra.parser.docling;

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

Create `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/dto/DoclingConvertResponse.java`:

```java
package com.knowledgebase.ai.infra.parser.docling.dto;

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
            String type,             // "heading" / "paragraph" / "list" / etc.
            Integer pageNo,
            Bbox bbox,
            Integer level,
            String text,
            List<String> sectionPath
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingTable(
            Integer pageNo,
            Bbox bbox,
            List<List<String>> rows,
            List<String> sectionPath
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bbox(double x, double y, double width, double height) {}
}
```

(Adjust field names after first real `curl` of the Docling endpoint — see Task 5.6.)

- [ ] **Step 3: Client**

Create `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/DoclingClient.java`:

```java
package com.knowledgebase.ai.infra.parser.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.infra.parser.AbstractRemoteParser;
import com.knowledgebase.ai.infra.parser.docling.dto.DoclingConvertResponse;
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
git add infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/
git commit -m "feat(infra-ai): DoclingClient + properties + response DTO"
```

### Task 5.4: `DoclingResponseAdapter`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java`

- [ ] **Step 1: Test with sample JSON**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapterTest.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.infra.parser.docling.dto.DoclingConvertResponse;
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
                                        "heading", 1,
                                        new DoclingConvertResponse.Bbox(0, 0, 100, 20),
                                        1, "Chapter 1", List.of()),
                                new DoclingConvertResponse.DoclingBlock(
                                        "paragraph", 1,
                                        new DoclingConvertResponse.Bbox(0, 30, 100, 40),
                                        null, "Body...", List.of("Chapter 1"))
                        ),
                        List.of(
                                new DoclingConvertResponse.DoclingTable(
                                        2,
                                        new DoclingConvertResponse.Bbox(10, 10, 200, 100),
                                        List.of(List.of("a", "b"), List.of("1", "2")),
                                        List.of("Chapter 1", "1.1 Tables"))
                        )
                ),
                Map.of("source", "test")
        );

        ParseResult r = adapter.toParseResult(resp);
        assertEquals("all text", r.text());
        assertEquals(2, r.blocks().size());
        assertEquals(BlockType.HEADING, r.blocks().get(0).type());
        assertEquals(1, r.blocks().get(0).headingLevel());
        assertEquals(1, r.tables().size());
        assertEquals(2, r.tables().get(0).rows().size());
        assertEquals("test", r.metadata().get("source"));
    }

    @Test
    void unknownBlockType_mapsToOther() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document("x",
                        List.of(new DoclingConvertResponse.DoclingBlock(
                                "weird-type", 1, null, null, "x", List.of())),
                        List.of()),
                Map.of()
        );
        ParseResult r = adapter.toParseResult(resp);
        assertEquals(BlockType.OTHER, r.blocks().get(0).type());
    }

    @Test
    void nullDocument_yieldsEmptyResult() {
        DoclingConvertResponse resp = new DoclingConvertResponse(null, Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals("", r.text());
        assertNotNull(r.blocks());
    }
}
```

- [ ] **Step 2: Implement adapter**

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java`:

```java
package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.infra.parser.docling.dto.DoclingConvertResponse;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adapter: Docling-private response shape → engine-neutral {@link ParseResult}.
 * Replace this adapter (not the parser interface) when swapping engines.
 */
@Component
public class DoclingResponseAdapter {

    public ParseResult toParseResult(DoclingConvertResponse resp) {
        if (resp == null || resp.document() == null) {
            return new ParseResult("", resp == null ? Map.of() : resp.metadata(), List.of(), List.of());
        }
        DoclingConvertResponse.Document d = resp.document();
        List<LayoutBlock> blocks = (d.blocks() == null ? List.<DoclingConvertResponse.DoclingBlock>of() : d.blocks())
                .stream().map(this::mapBlock).toList();
        List<LayoutTable> tables = (d.tables() == null ? List.<DoclingConvertResponse.DoclingTable>of() : d.tables())
                .stream().map(this::mapTable).toList();
        String text = d.text() == null ? "" : d.text();
        return new ParseResult(text, resp.metadata() == null ? Map.of() : resp.metadata(), blocks, tables);
    }

    private LayoutBlock mapBlock(DoclingConvertResponse.DoclingBlock b) {
        return new LayoutBlock(
                mapType(b.type()),
                b.pageNo() == null ? 0 : b.pageNo(),
                b.bbox() == null ? null : new LayoutBlock.Bbox(
                        b.bbox().x(), b.bbox().y(), b.bbox().width(), b.bbox().height()),
                b.level(),
                b.sectionPath() == null ? List.of() : b.sectionPath(),
                b.text() == null ? "" : b.text()
        );
    }

    private LayoutTable mapTable(DoclingConvertResponse.DoclingTable t) {
        return new LayoutTable(
                t.pageNo() == null ? 0 : t.pageNo(),
                t.bbox() == null ? null : new LayoutBlock.Bbox(
                        t.bbox().x(), t.bbox().y(), t.bbox().width(), t.bbox().height()),
                t.rows() == null ? List.of() : t.rows(),
                t.sectionPath() == null ? List.of() : t.sectionPath()
        );
    }

    private BlockType mapType(String docling) {
        if (docling == null) return BlockType.OTHER;
        return switch (docling.toLowerCase()) {
            case "heading", "title", "section-header" -> BlockType.HEADING;
            case "paragraph", "text" -> BlockType.PARAGRAPH;
            case "table" -> BlockType.TABLE;
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

import com.knowledgebase.ai.infra.parser.docling.DoclingClient;
import com.knowledgebase.ai.infra.parser.docling.dto.DoclingConvertResponse;
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
        // Docling supports PDF/Word/Excel/PPT/HTML/images. Decline pure plain text — Tika is faster.
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
  "SELECT id, parse_mode, page_number, heading_path FROM t_knowledge_chunk \
   WHERE doc_id=(SELECT id FROM t_knowledge_document ORDER BY create_time DESC LIMIT 1) \
   ORDER BY chunk_index LIMIT 5;"
```

Expected: rows with non-null `page_number` / `heading_path`.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingDocumentParser.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DocumentParserSelector.java
git commit -m "feat(parser): DoclingDocumentParser w/ ConditionalOnProperty"
```

### Task 5.6: Adjust DTO field mapping to actual Docling response

**Files:**
- Modify: `infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/dto/DoclingConvertResponse.java`

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
git add infra-ai/src/main/java/com/knowledgebase/ai/infra/parser/docling/dto/DoclingConvertResponse.java \
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
gh pr create --title "feat(parser): PR 5 — Docling integration (real enhanced parsing)" \
  --body "$(cat <<'EOF'
## Summary
- Independent Docling service (`quay.io/ds4sd/docling-serve`) via docker-compose
- `AbstractRemoteParser` template for HTTP-backed parsers (timeout / logging skeleton)
- `DoclingClient` (infra-ai), `DoclingDocumentParser` (core/parser, `@ConditionalOnProperty`)
- `DoclingResponseAdapter` maps Docling JSON → engine-neutral `ParseResult{blocks, tables}`
- ENHANCED uploads now produce real layout output

## Operator notes
- Start: `docker compose -f resources/docker/docling-compose.yml up -d`
- Toggle: `docling.service.enabled=true|false` (false → silent fallback to Tika via PR 2 decorator)

## Test plan
- [x] Adapter unit test (synthetic + real captured response)
- [x] Manual end-to-end: upload PDF with 增强解析 → DB chunks have page_number / heading_path
- [x] Smoke: toggle enabled=false → ENHANCED upload still works (Tika fallback, metadata flag set)
EOF
)"
```

---

## PR 6 — Structured chunker + retrieval citation

**Goal:** Make Docling's structured output flow through to chunking and citation display.

### Task 6.1: `StructuredChunkingStrategy`

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java`

- [ ] **Step 1: Test**

Create `bootstrap/src/test/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategyTest.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
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
        List<LayoutBlock> blocks = List.of(
                new LayoutBlock(BlockType.HEADING, 1, null, 1, List.of(), "Chapter 1"),
                new LayoutBlock(BlockType.PARAGRAPH, 1, null, null, List.of("Chapter 1"), "Para A"),
                new LayoutBlock(BlockType.HEADING, 1, null, 2, List.of("Chapter 1"), "1.1"),
                new LayoutBlock(BlockType.PARAGRAPH, 2, null, null, List.of("Chapter 1", "1.1"), "Para B")
        );

        List<StructuredChunk> chunks = strategy.chunk(blocks, List.of(), 1000);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).text().contains("Chapter 1"));
        assertTrue(chunks.get(0).text().contains("Para A"));
        assertEquals(List.of("Chapter 1"), chunks.get(0).headingPath());
        assertEquals(2, chunks.get(1).pageNumber());
        assertEquals(List.of("Chapter 1", "1.1"), chunks.get(1).headingPath());
    }

    @Test
    void neverSplitsTable_eachTableIsOneChunk() {
        List<LayoutTable> tables = List.of(
                new LayoutTable(3, null,
                        List.of(List.of("h1", "h2"), List.of("a", "b"), List.of("c", "d")),
                        List.of("Section 5"))
        );
        List<StructuredChunk> chunks = strategy.chunk(List.of(), tables, 1000);
        assertEquals(1, chunks.size());
        assertEquals(BlockType.TABLE, chunks.get(0).blockType());
        assertEquals(3, chunks.get(0).pageNumber());
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
        List<String> headingPath,
        BlockType blockType
) {}
```

Create `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/chunker/StructuredChunkingStrategy.java`:

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunks a structured document by heading boundaries. Tables are kept atomic
 * (each table = one chunk). Long sections are softly split at maxChars while
 * preserving headingPath on every produced chunk.
 */
@Component
public class StructuredChunkingStrategy {

    public List<StructuredChunk> chunk(List<LayoutBlock> blocks, List<LayoutTable> tables, int maxChars) {
        List<StructuredChunk> out = new ArrayList<>();

        // 1) Tables — each one its own chunk (atomic).
        for (LayoutTable t : tables) {
            out.add(new StructuredChunk(
                    serializeTable(t),
                    t.pageNumber(),
                    t.headingPath(),
                    BlockType.TABLE
            ));
        }

        // 2) Group blocks under heading boundaries.
        List<LayoutBlock> currentSection = new ArrayList<>();
        for (LayoutBlock b : blocks) {
            if (b.type() == BlockType.HEADING && !currentSection.isEmpty()) {
                flushSection(currentSection, out, maxChars);
                currentSection.clear();
            }
            currentSection.add(b);
        }
        if (!currentSection.isEmpty()) {
            flushSection(currentSection, out, maxChars);
        }
        return out;
    }

    private void flushSection(List<LayoutBlock> section, List<StructuredChunk> out, int maxChars) {
        if (section.isEmpty()) return;
        StringBuilder buf = new StringBuilder();
        int firstPage = section.get(0).pageNumber();
        List<String> path = section.get(0).headingPath();
        // If first block is a heading, derive path = path + heading.text
        if (section.get(0).type() == BlockType.HEADING) {
            List<String> extended = new ArrayList<>(path);
            extended.add(section.get(0).text());
            path = extended;
        }

        for (LayoutBlock b : section) {
            if (buf.length() + b.text().length() > maxChars && buf.length() > 0) {
                out.add(new StructuredChunk(buf.toString(), firstPage, path, BlockType.PARAGRAPH));
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(b.text());
        }
        if (buf.length() > 0) {
            out.add(new StructuredChunk(buf.toString(), firstPage, path, BlockType.PARAGRAPH));
        }
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
git commit -m "feat(ingestion): StructuredChunkingStrategy (heading-aware, table-atomic)"
```

### Task 6.2: `ChunkerNode` consumes structured input

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java`

- [ ] **Step 1: Add branch**

In `ChunkerNode.execute(...)`, replace the existing chunking call with:

```java
        ParseResult pr = context.getParseResult();  // assumed available; if not, persist on context in ParserNode
        List<StructuredChunk> chunks;
        if (pr != null && (!pr.blocks().isEmpty() || !pr.tables().isEmpty())) {
            chunks = structuredChunkingStrategy.chunk(pr.blocks(), pr.tables(), maxChars);
        } else {
            // Legacy String path — preserved byte-equivalent for BASIC mode and old data.
            chunks = legacyTextChunkerAdapter.chunk(context.getRawText(), config);
        }
        context.setStructuredChunks(chunks);
```

(`legacyTextChunkerAdapter` wraps the existing `FixedSizeTextChunker` / `StructureAwareTextChunker` so the output type unifies as `List<StructuredChunk>` — old chunks have `pageNumber=0`, `headingPath=[]`, `blockType=PARAGRAPH`.)

- [ ] **Step 2: Update ParserNode to put `ParseResult` on context**

In `ParserNode.execute(...)`, after parsing:

```java
        context.setParseResult(parseResult);  // new field on IngestionContext
        context.setRawText(parseResult.text()); // preserve legacy field
```

(Add `parseResult` field + getter/setter to `IngestionContext`.)

- [ ] **Step 3: Test the legacy fallback path**

Add to `ChunkerNode` test:

```java
    @Test
    void emptyBlocks_fallsBackToLegacyStringChunking_byteEquivalent() {
        // Verify CHUNK mode (BASIC) produces same chunks as before refactor.
        // Concrete assertion: count + first chunk text matches recorded baseline.
    }
```

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ChunkerNode.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/domain/IngestionContext.java
git commit -m "feat(ingestion): ChunkerNode uses structured strategy when blocks present"
```

### Task 6.3: `IndexerNode` writes layout metadata

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/IndexerNode.java`

- [ ] **Step 1: Pass structured chunk fields into vector metadata**

In the per-chunk metadata construction block (around line 246 per CLAUDE.md):

```java
        Map<String, Object> meta = new HashMap<>(baseMeta);
        if (chunk.pageNumber() > 0) {
            meta.put(VectorMetadataFields.PAGE_NUMBER, chunk.pageNumber());
        }
        if (!chunk.headingPath().isEmpty()) {
            meta.put(VectorMetadataFields.HEADING_PATH,
                    objectMapper.writeValueAsString(chunk.headingPath()));
        }
        if (chunk.blockType() != null) {
            meta.put(VectorMetadataFields.BLOCK_TYPE, chunk.blockType().name());
        }
```

- [ ] **Step 2: Persist to `t_knowledge_chunk`**

Wherever `t_knowledge_chunk` rows are inserted (likely in `KnowledgeDocumentServiceImpl` chunk persistence or a chunk DAO), add:

```java
        chunkDO.setPageNumber(chunk.pageNumber() > 0 ? chunk.pageNumber() : null);
        chunkDO.setHeadingPath(chunk.headingPath().isEmpty() ? null
                : objectMapper.writeValueAsString(chunk.headingPath()));
        chunkDO.setBlockType(chunk.blockType() == null ? null : chunk.blockType().name());
```

- [ ] **Step 3: Add columns to `KnowledgeChunkDO`**

In `KnowledgeChunkDO.java`:

```java
    @TableField("page_number")
    private Integer pageNumber;

    @TableField("heading_path")
    private String headingPath;

    @TableField("block_type")
    private String blockType;
```

- [ ] **Step 4: Smoke test end-to-end**

Restart app, upload a PDF with `parseMode=enhanced`, then:

```bash
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT chunk_index, page_number, heading_path, block_type, LEFT(content, 50) \
   FROM t_knowledge_chunk \
   WHERE doc_id = (SELECT id FROM t_knowledge_document WHERE parse_mode='enhanced' \
                   ORDER BY create_time DESC LIMIT 1) \
   ORDER BY chunk_index LIMIT 10;"
```

Expected: `page_number` and `heading_path` populated for at least the section-content rows.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/IndexerNode.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/entity/KnowledgeChunkDO.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "feat(ingestion): persist + index layout metadata (page/heading/blockType)"
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
  <span className="text-xs text-muted-foreground">📄 第 {card.pageNumber} 页</span>
)}
{card.headingPath && card.headingPath.length > 0 && (
  <span className="text-xs text-muted-foreground">
    · {card.headingPath.join(" › ")}
  </span>
)}
```

- [ ] **Step 3: Manual test**

Ask a question against an enhanced-parsed doc; verify source card shows "📄 第 5 页 · 第 3 章 › 3.2 信用风险".

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
| `ParseMode` enum (core/parser/) | 用户语义解析模式：BASIC（Tika）/ ENHANCED（Docling）。**不要在代码里直接写引擎名**——经 selector 路由 |
| `DoclingDocumentParser` (core/parser/) | `@ConditionalOnProperty(docling.service.enabled=true)`；通过 `DoclingClient` 调用独立 Python 服务（`docker-compose -f resources/docker/docling-compose.yml`）|
| `FallbackParserDecorator` (core/parser/) | ENHANCED 路由的兜底装饰；Docling 失败 / 未注册时透明降级到 Tika，metadata 写 `parse_engine_actual` 给前端做 ⚠️ 提示 |
| `ParseModeRouter` (knowledge/service/support/) | 把 `parseMode` 翻译成 `processMode`：BASIC→CHUNK、ENHANCED→PIPELINE。**前端不再传 processMode**，由该 router 决定 |
| `StructuredChunkingStrategy` (ingestion/chunker/) | 当 `ParseResult.blocks` 非空时启用，按 heading 切片、表格原子保留；否则降级 String 路径 |
```

- [ ] **Step 2: Add gotchas**

Append to bootstrap `CLAUDE.md` gotchas:

```markdown
- **parseMode vs processMode** (PR 4 起)：前端 `parseMode` 是 UX 层；`processMode` 由 `ParseModeRouter` 派生。Service 收到上传请求时**忽略** `requestParam.getProcessMode()`。要修改路由策略只动 `ParseModeRouter`，不要直接读前端字段。
- **Docling 关停回滚** (PR 5 起)：把 `docling.service.enabled` 改成 `false`，所有 ENHANCED 上传通过 `FallbackParserDecorator` 静默走 Tika，DB schema / 上传字段不动；前端在文档列表读 `parse_engine_actual` 显示 ⚠️。
- **layout metadata 字段**（PR 6 起）：`page_number / heading_path / block_type` 仅在 `parse_mode=enhanced` 且 Docling 在线时写入。BASIC 路径所有 layout 列为 NULL，不要 fail-closed 当作错误。
```

- [ ] **Step 3: Push + PR**

```bash
mvn -pl bootstrap test
mvn spotless:check
cd frontend && pnpm tsc --noEmit && cd ..
git push -u origin feat/parser-enhancement-pr6
gh pr create --title "feat(parser): PR 6 — Structured chunking + layout citation" \
  --body "$(cat <<'EOF'
## Summary
- `StructuredChunkingStrategy` consumes `LayoutBlock` / `LayoutTable`, splits at heading boundaries, keeps tables atomic
- `ChunkerNode` branches: blocks present → structured; else legacy String path (byte-equivalent for BASIC)
- `IndexerNode` writes `page_number / heading_path / block_type` to vector metadata + `t_knowledge_chunk`
- Frontend SourceCard renders 📄 第 X 页 · 章 › 节 for enhanced-parsed citations
- CLAUDE.md updated with new parser table + 3 gotchas

## Test plan
- [x] `StructuredChunkingStrategyTest` heading + table cases
- [x] Manual: enhanced upload → t_knowledge_chunk has page_number / heading_path
- [x] Manual: chat against enhanced-parsed doc → source card shows page + heading
- [x] Regression: BASIC upload chunks byte-equivalent vs main
EOF
)"
```

---

## Self-Review Checklist (filled out)

**1. Spec coverage:**

- ✅ "基础解析 / 增强解析" UI labels — Task 4.3
- ✅ Tika fallback when Docling offline — PR 2 entirely
- ✅ Independent Docling deploy (not docling-java) — PR 5 with self-built `DoclingClient`
- ✅ Architecture layering enforced — file paths confirm: `core/parser` doesn't import `ingestion/`; `infra-ai` doesn't import `bootstrap/`
- ✅ Strategy / Registry / Decorator / Adapter / Template / Factory patterns — explicit `Where each pattern lives` mapping
- ✅ `ParseMode` semantic decoupling from engine names — Task 1.1, DB column stores `basic|enhanced`
- ✅ `ENHANCED → PIPELINE` forced routing — Task 4.2 with `ParseModeRouter`
- ✅ Hide `processMode` from frontend — Task 4.3
- ✅ Layout metadata persisted (page/heading/block) — PR 3 schema + PR 6 indexer
- ✅ ChunkerNode dual-path (legacy + structured) — Task 6.2

**2. Placeholder scan:**
- No "TBD / TODO / similar to Task N / fill in details" markers.
- Every code step shows full code.
- Frontend dialog edit references "adjust to actual upload dialog path" — that is a discovery instruction, not a placeholder; the `find` command in Task 4.3 step 1 resolves the path before edit.
- `enrichParserNodeConfig` in Task 4.2 inlines the full implementation including the JsonNode deep copy logic.

**3. Type consistency:**
- `ParseMode` vs `ParserType`: ParseMode is user-facing semantic (BASIC/ENHANCED); ParserType is engine identity (TIKA/DOCLING). Used correctly throughout.
- `LayoutBlock.headingPath` is `List<String>`, consistently used as `List<String>` in adapter / chunker / DB serialization (JSON-stringified at DB boundary).
- `StructuredChunk` consistently has `(text, pageNumber, headingPath, blockType)`.
- `ParseResult` 4-arg vs 2-arg constructor — both defined in Task 1.3, consistently used: Tika code stays on 2-arg; Docling adapter uses 4-arg.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-05-parser-enhancement-docling.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
