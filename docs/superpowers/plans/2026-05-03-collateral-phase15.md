# Collateral Phase 1.5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stabilize the Phase 1 Collateral MCP demo by fixing combined house/counterparty spans and adding a conservative Collateral ingestion preset document.

**Architecture:** Keep field answers static and MCP-only. Move reusable party-alias text scanning into `CollateralSeedData`, let the executor recover counterparty from raw spans before falling back to `userQuestion`, and document a no-body-rewrite ingestion pipeline that carries Collateral hints as metadata only.

**Tech Stack:** Java 17, Spring Boot 3.5.7, JUnit 5, AssertJ, Maven, existing ingestion pipeline JSON examples.

---

## File Structure

- Modify `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedData.java`
  - Owns exact alias resolution and new long-text alias scanning.
- Modify `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutor.java`
  - Uses seed-data scanning to recover counterparty from combined spans.
- Modify `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedDataTest.java`
  - Locks long-span alias scanning and ASCII boundary behavior.
- Modify `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutorTest.java`
  - Locks Chinese and English combined-span regressions.
- Create `docs/examples/collateral-pipeline-request.json`
  - Documents the Collateral ingestion pipeline preset without `enhancer` / `context_enhance`.
- Create `docs/examples/collateral-ingestion-example.md`
  - Documents how to use the preset and what it does not guarantee.

## Task 1: Seed Data Alias-Scanning Tests

**Files:**
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedDataTest.java`

- [ ] **Step 1: Add failing tests for long-span counterparty and house-party alias scanning**

Add these test methods before `blankOrNullInputs_returnSafeDefaults()`:

```java
    @Test
    void findCounterpartyAliasInText_matchesAliasInsideLongSpan() {
        assertThat(data.findCounterpartyAliasInText("华泰和HSBC交易的ISDA&CSA下的 MTA 是多少"))
                .contains("HSBC");
        assertThat(data.findCounterpartyAliasInText("Huatai and HSBC ISDA&CSA MTA"))
                .contains("HSBC");
        assertThat(data.findCounterpartyAliasInText("Huatai and ABC ISDA&CSA MTA"))
                .isEmpty();
    }

    @Test
    void containsHousePartyAlias_matchesAliasInsideLongSpan() {
        assertThat(data.containsHousePartyAlias("华泰和HSBC")).isTrue();
        assertThat(data.containsHousePartyAlias("Huatai and HSBC")).isTrue();
        assertThat(data.containsHousePartyAlias("HSBC only")).isFalse();
    }

    @Test
    void containsHousePartyAlias_doesNotMatchAsciiAliasInsideLongerToken() {
        assertThat(data.containsHousePartyAlias("the counterparty is HSBC")).isFalse();
        assertThat(data.containsHousePartyAlias("HTG is not the HT house alias")).isTrue();
        assertThat(data.containsHousePartyAlias("counterparty HTG only")).isFalse();
    }
```

- [ ] **Step 2: Run the seed-data tests and verify they fail for missing methods**

Run:

```powershell
mvn -pl bootstrap -Dtest=CollateralSeedDataTest test
```

Expected: compile failure containing both method names:

```text
cannot find symbol
  symbol:   method findCounterpartyAliasInText(java.lang.String)
cannot find symbol
  symbol:   method containsHousePartyAlias(java.lang.String)
```

## Task 2: Seed Data Alias-Scanning Implementation

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedData.java`

- [ ] **Step 1: Add the regex import**

Add this import with the other `java.util` imports:

```java
import java.util.regex.Pattern;
```

- [ ] **Step 2: Add long-text scanning methods and helpers**

Add these methods after `isHouseParty(String raw)`:

```java
    public Optional<String> findCounterpartyAliasInText(String text) {
        String normalizedText = normalizeAliasText(text);
        if (StrUtil.isBlank(normalizedText)) {
            return Optional.empty();
        }
        return COUNTERPARTY_ALIAS_TO_CANONICAL.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .filter(entry -> containsAlias(normalizedText, entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public boolean containsHousePartyAlias(String text) {
        String normalizedText = normalizeAliasText(text);
        if (StrUtil.isBlank(normalizedText)) {
            return false;
        }
        return HOUSE_PARTY_ALIASES.stream()
                .map(this::normalizeAliasText)
                .anyMatch(alias -> containsAlias(normalizedText, alias));
    }

    private String normalizeAliasText(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private boolean containsAlias(String normalizedText, String normalizedAlias) {
        if (StrUtil.isBlank(normalizedText) || StrUtil.isBlank(normalizedAlias)) {
            return false;
        }
        boolean hasNonAscii = normalizedAlias.chars().anyMatch(ch -> ch > 127);
        if (hasNonAscii) {
            return normalizedText.contains(normalizedAlias);
        }
        Pattern pattern = Pattern.compile("(^|[^a-z0-9])"
                + Pattern.quote(normalizedAlias)
                + "($|[^a-z0-9])");
        return pattern.matcher(normalizedText).find();
    }
```

- [ ] **Step 3: Run the seed-data tests and verify they pass**

Run:

```powershell
mvn -pl bootstrap -Dtest=CollateralSeedDataTest test
```

Expected:

```text
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Task 3: Executor Combined-Span Regression Tests

**Files:**
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutorTest.java`

- [ ] **Step 1: Add failing regression tests for combined party spans**

Add these tests after `execute_houseAliasFallback_secondaryMatchSucceeds()`:

```java
    @Test
    void execute_combinedChineseHouseAndCounterpartySpan_recoversCounterparty() {
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的ISDA&CSA下的 MTA 是多少",
                "华泰和HSBC", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_combinedEnglishHouseAndCounterpartySpan_recoversCounterparty() {
        MCPResponse resp = executor.execute(req(
                "Huatai and HSBC ISDA&CSA MTA",
                "Huatai and HSBC", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_combinedHouseAndUnknownCounterparty_doesNotRecoverHsbc() {
        MCPResponse resp = executor.execute(req(
                "华泰和ABC交易的ISDA&CSA下的 MTA 是多少",
                "华泰和ABC", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult())
                .contains("请补充更多信息以定位文档")
                .contains("Please provide Counterparty")
                .doesNotContain("US$250,000.00");
    }
```

- [ ] **Step 2: Run the executor tests and verify the two combined-span recovery cases fail**

Run:

```powershell
mvn -pl bootstrap -Dtest=CollateralFieldLookupMCPExecutorTest test
```

Expected: two assertion failures for missing `US$250,000.00`; the unknown-counterparty test may already pass after Task 2.

## Task 4: Executor Counterparty Resolution Fix

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutor.java`

- [ ] **Step 1: Remove the now-unused `Locale` import**

Remove:

```java
import java.util.Locale;
```

- [ ] **Step 2: Replace `resolveEffectiveCounterparty`**

Replace the current method with:

```java
    private String resolveEffectiveCounterparty(String raw, String userQuestion) {
        if (StrUtil.isBlank(raw)) {
            return seedData.findCounterpartyAliasInText(userQuestion).orElse(null);
        }

        Optional<String> exactCounterparty = seedData.resolveCounterparty(raw);
        if (exactCounterparty.isPresent()) {
            return exactCounterparty.get();
        }

        if (seedData.containsHousePartyAlias(raw)) {
            return seedData.findCounterpartyAliasInText(raw)
                    .or(() -> seedData.findCounterpartyAliasInText(userQuestion))
                    .orElse(null);
        }

        return raw;
    }
```

- [ ] **Step 3: Run the Collateral executor and seed-data tests**

Run:

```powershell
mvn -pl bootstrap -Dtest=CollateralSeedDataTest,CollateralFieldLookupMCPExecutorTest test
```

Expected:

```text
[INFO] Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Task 5: Collateral Ingestion Preset Docs

**Files:**
- Create: `docs/examples/collateral-pipeline-request.json`
- Create: `docs/examples/collateral-ingestion-example.md`

- [ ] **Step 1: Create the Collateral pipeline JSON**

Create `docs/examples/collateral-pipeline-request.json` with:

```json
{
  "name": "collateral-ingestion-pipeline",
  "description": "Collateral协议摄取流水线 - 原文解析、保守分块、元数据富集、向量化；不启用文档级正文改写",
  "nodes": [
    {
      "nodeId": "fetcher-1",
      "nodeType": "fetcher",
      "nextNodeId": "parser-1"
    },
    {
      "nodeId": "parser-1",
      "nodeType": "parser",
      "settings": {
        "rules": [
          {
            "mimeType": "PDF"
          },
          {
            "mimeType": "DOCX"
          }
        ]
      },
      "nextNodeId": "chunker-1"
    },
    {
      "nodeId": "chunker-1",
      "nodeType": "chunker",
      "settings": {
        "strategy": "fixed_size",
        "chunkSize": 768,
        "overlapSize": 160
      },
      "nextNodeId": "enricher-1"
    },
    {
      "nodeId": "enricher-1",
      "nodeType": "enricher",
      "settings": {
        "modelId": "qwen-plus",
        "attachDocumentMetadata": true,
        "tasks": [
          {
            "type": "metadata",
            "systemPrompt": "You extract explicit Collateral retrieval metadata from one chunk. Return only a JSON object with keys counterparty, agreementType, fieldHint, page. Use an empty string when the value is not explicitly present in the chunk. Do not rewrite, summarize, infer, or add field values.",
            "userPromptTemplate": "Chunk text:\n{{text}}\n\nReturn JSON only."
          }
        ]
      },
      "nextNodeId": "indexer-1"
    },
    {
      "nodeId": "indexer-1",
      "nodeType": "indexer",
      "settings": {
        "metadataFields": [
          "counterparty",
          "agreementType",
          "fieldHint",
          "page",
          "keywords"
        ]
      }
    }
  ]
}
```

- [ ] **Step 2: Create the Collateral ingestion example**

Create `docs/examples/collateral-ingestion-example.md` with:

```markdown
# Collateral Ingestion Pipeline Example

This example is for Phase 1.5 demo stability only. It lets a Collateral KB ingest agreement documents for ordinary KB retrieval background. The `collateral_field_lookup` MCP answer still comes from the static seed executor until Phase 2 and Phase 3 are complete.

## Pipeline

`collateral-pipeline-request.json` uses:

```text
fetcher-1 -> parser-1 -> chunker-1 -> enricher-1 -> indexer-1
```

It intentionally does not include an `enhancer` node and does not run document-level `context_enhance`. Collateral values, dates, thresholds, and source snippets must remain original-text based.

## Create Pipeline

```powershell
curl -X POST "http://localhost:9090/api/ragent/ingestion/pipelines" `
  -H "Content-Type: application/json" `
  -d "@docs/examples/collateral-pipeline-request.json"
```

Expected response shape:

```json
{
  "code": "0",
  "data": {
    "id": "1234567890",
    "name": "collateral-ingestion-pipeline"
  },
  "message": "success"
}
```

## Upload With Conservative Metadata

```powershell
curl -X POST "http://localhost:9090/api/ragent/ingestion/tasks/upload" `
  -F "pipelineId=1234567890" `
  -F "file=@E:/agreements/hsbc-isda-csa.pdf" `
  -F "metadata={\"counterparty\":\"HSBC\",\"agreementType\":\"ISDA&CSA\",\"fieldHint\":\"MTA\",\"page\":\"P23\"}"
```

The metadata helps ordinary KB retrieval readability. It is not accepted as a field answer by the MCP executor.

## Boundaries

- MCP A/B/B'/C/D output remains unchanged.
- MCP-only answers still do not emit `SOURCES`.
- Scanned PDFs and complex layouts are not covered by Phase 1.5. OCR and layout evidence enter the roadmap Phase 2.5.
- Real agreement field values are not connected to MCP until the Phase 2 security gate and Phase 3 data model are complete.
```

- [ ] **Step 3: Scan the new docs for forbidden phase creep**

Run:

```powershell
Select-String -Path docs\examples\collateral-pipeline-request.json,docs\examples\collateral-ingestion-example.md -Pattern "context_enhance|EnhancerNode|真实协议字段|source card|OCR"
```

Expected: matches are limited to boundary text saying these items are not included; no JSON node has `nodeType` equal to `enhancer`.

## Task 6: Verification and Commit

**Files:**
- All files changed in Tasks 1-5.

- [ ] **Step 1: Run targeted tests**

Run:

```powershell
mvn -pl bootstrap -Dtest=CollateralSeedDataTest,CollateralFieldLookupMCPExecutorTest test
```

Expected:

```text
[INFO] BUILD SUCCESS
```

- [ ] **Step 2: Run Spotless check for bootstrap**

Run:

```powershell
mvn -pl bootstrap -DskipTests spotless:check
```

Expected:

```text
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: Run the plan self-check scan**

Run:

```powershell
$patterns = @('TO' + 'DO', 'TB' + 'D', 'FIX' + 'ME', '待' + '定', 'fill' + ' in', 'implement' + ' later')
Select-String -Path docs\superpowers\plans\2026-05-03-collateral-phase15.md -Pattern $patterns
```

Expected: no matches.

- [ ] **Step 4: Commit Phase 1.5**

Run:

```powershell
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedData.java `
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutor.java `
  bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedDataTest.java `
  bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutorTest.java `
  docs/examples/collateral-pipeline-request.json `
  docs/examples/collateral-ingestion-example.md
git commit -m "fix(collateral): recover counterparty from combined party spans"
```

Expected:

```text
commit output contains: fix(collateral): recover counterparty from combined party spans
```

## Self-Review

- Spec coverage: combined Chinese and English house/counterparty spans are covered; unknown counterparty does not recover HSBC; ingestion preset documents parser, chunker, enricher, indexer and excludes document-level body rewrite; MCP source behavior is unchanged.
- Scope control: this plan does not add real agreement data, KB-scoped intent filtering, OCR/layout, source cards, or management UI.
- Type consistency: new method names are `findCounterpartyAliasInText` and `containsHousePartyAlias` in both tests and implementation; executor calls those exact names.
- Verification coverage: targeted tests cover the code change; docs scan checks the pipeline example does not add an enhancer node.
