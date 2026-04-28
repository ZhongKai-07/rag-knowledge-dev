# Over-Retrieve + Rerank (PR2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the single `DEFAULT_TOP_K=10` into two independent parameters — `recallTopK` (how many candidates the bi-encoder retrieves) and `rerankTopK` (how many the cross-encoder keeps). This gives the cross-encoder real selection room and lets the HIGH/MID/LOW relevance gate (future) rely on absolute scores.

**Architecture:** Introduce `RagRetrievalProperties` (binding-time validation), add `recallTopK`/`rerankTopK` to `SearchContext` (builder-time IAE guard — double safety net), thread both values from `RAGChatServiceImpl` → `RetrievalEngine` → `MultiChannelRetrievalEngine` → channels. Each top-level channel internally sorts+caps to `recallTopK` (drop the old `topKMultiplier` amplifier). `RerankPostProcessor` does a global `sort → limit(recallTopK) → rerank(rerankTopK)`. `BaiLianRerankClient` defensively clamps `effectiveTopN = min(topN, candidates.size())` so small KBs don't break the百炼 API contract.

**Tech Stack:** Spring Boot 3.5.7 `@ConfigurationProperties`, jakarta.validation, Lombok `@Builder.build()` override, JUnit 5 + Mockito + AssertJ.

---

## File Structure

**Create (3):**
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalProperties.java` — new properties bean, binding + runtime validation
- `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalPropertiesTest.java` — unit test for the properties bean validation
- `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessorTest.java` — new unit test locking sort → cap(recall) → rerank(rerank) contract

**Modify (8):**
- `bootstrap/src/main/resources/application.yaml` — add `rag.retrieval.*` section
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java` — replace `topK` with `recallTopK`+`rerankTopK`, custom `build()` with IAE guard
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java` — accept `RagRetrievalProperties`, compute per-sub-question `RetrievalPlan`, replace `resolveSubQuestionTopK` with `resolveSubQuestionPlan`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java` — signature takes plan instead of `int topK`, `buildSearchContext` populates both fields
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java` — drop `topKMultiplier` use, retrieve per-intent at `recallTopK`, sort+cap to `recallTopK` before returning
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java` — same: drop multiplier, retrieve at `recallTopK`, sort+cap before returning
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java` — simplify `executeParallelRetrieval` signature (no multiplier)
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessor.java` — sort → `limit(recallTopK)` → `rerankService.rerank(query, capped, rerankTopK)`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` — pass `ragRetrievalProperties` into `retrievalEngine.retrieve(...)`, set `evalCollector.setTopK(rerankTopK)`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/EvaluationCollector.java` — javadoc clarifying `topK` stores `rerankTopK` (no schema change)
- `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java` — clamp `effectiveTopN = min(topN, candidates.size())` before `top_n` JSON property
- `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEnginePostProcessorChainTest.java` — update existing tests to new SearchContext shape

---

## Task 1: RagRetrievalProperties + yaml

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalProperties.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalPropertiesTest.java`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1.1: Write the failing test for valid binding**

```java
// bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalPropertiesTest.java
package com.knowledgebase.ai.ragent.rag.config;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagRetrievalPropertiesTest {

    @Test
    void validConfigPasses() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        props.setRecallTopK(30);
        props.setRerankTopK(10);
        props.validate(); // throws IllegalStateException if invalid

        assertThat(props.getRecallTopK()).isEqualTo(30);
        assertThat(props.getRerankTopK()).isEqualTo(10);
    }

    @Test
    void recallLessThanRerankThrows() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        props.setRecallTopK(5);
        props.setRerankTopK(10);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recall-top-k")
                .hasMessageContaining("rerank-top-k");
    }

    @Test
    void zeroOrNegativeValuesThrow() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        props.setRecallTopK(0);
        props.setRerankTopK(10);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);

        props.setRecallTopK(30);
        props.setRerankTopK(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 1.2: Run the test to verify it fails (class doesn't exist)**

Run: `mvn -pl bootstrap test -Dtest=RagRetrievalPropertiesTest`
Expected: Compilation failure — `RagRetrievalProperties` not found.

- [ ] **Step 1.3: Create RagRetrievalProperties**

```java
// bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalProperties.java
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

package com.knowledgebase.ai.ragent.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 两阶段检索配置：召回数（bi-encoder 检索候选数）与 rerank 后最终保留数（喂给 LLM 的数量）。
 * 绑定阶段 + 运行时双重 fail-fast：配置错直接阻止启动或构建 SearchContext。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.retrieval")
public class RagRetrievalProperties {

    /** 召回阶段候选数（向量检索返回），默认 30。必须 >= rerankTopK。 */
    private int recallTopK = 30;

    /** Rerank 后保留并喂给 LLM 的数量，默认 10。 */
    private int rerankTopK = 10;

    @PostConstruct
    public void validate() {
        if (recallTopK <= 0) {
            throw new IllegalStateException(
                    "rag.retrieval.recall-top-k must be > 0, got: " + recallTopK);
        }
        if (rerankTopK <= 0) {
            throw new IllegalStateException(
                    "rag.retrieval.rerank-top-k must be > 0, got: " + rerankTopK);
        }
        if (recallTopK < rerankTopK) {
            throw new IllegalStateException(
                    "rag.retrieval.recall-top-k (" + recallTopK + ") must be >= rerank-top-k ("
                            + rerankTopK + ")");
        }
    }
}
```

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `mvn -pl bootstrap test -Dtest=RagRetrievalPropertiesTest`
Expected: 3 tests PASS.

- [ ] **Step 1.5: Add yaml section**

Find `application.yaml` under the `rag:` top-level key (before `sources:` at line ~64). Add the new `retrieval:` block above it:

```yaml
  retrieval:
    recall-top-k: 30
    rerank-top-k: 10

  sources:
    enabled: true
    ...
```

- [ ] **Step 1.6: Verify the app still starts**

Run: `mvn -pl bootstrap compile -q`
Expected: exit=0 (compilation clean).

- [ ] **Step 1.7: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalProperties.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalPropertiesTest.java \
        bootstrap/src/main/resources/application.yaml
git commit -m "feat(retrieval): add RagRetrievalProperties for recall/rerank topK split [PR2]"
```

---

## Task 2: SearchContext dual TopK + builder IAE guard

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContextBuilderTest.java`

- [ ] **Step 2.1: Write the failing test for builder guard**

```java
// bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContextBuilderTest.java
package com.knowledgebase.ai.ragent.rag.core.retrieve.channel;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchContextBuilderTest {

    @Test
    void validBuildsContextWithBothTopK() {
        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        assertThat(ctx.getRecallTopK()).isEqualTo(30);
        assertThat(ctx.getRerankTopK()).isEqualTo(10);
    }

    @Test
    void recallLessThanRerankThrowsIAE() {
        assertThatThrownBy(() -> SearchContext.builder()
                .originalQuestion("q")
                .recallTopK(5)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recallTopK")
                .hasMessageContaining("rerankTopK");
    }

    @Test
    void zeroTopKThrowsIAE() {
        assertThatThrownBy(() -> SearchContext.builder()
                .originalQuestion("q")
                .recallTopK(0)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SearchContext.builder()
                .originalQuestion("q")
                .recallTopK(30)
                .rerankTopK(0)
                .accessScope(AccessScope.all())
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2.2: Run test — expect compile error because `topK` is still the only field**

Run: `mvn -pl bootstrap test -Dtest=SearchContextBuilderTest`
Expected: Compilation failure — `recallTopK`/`rerankTopK` setters don't exist.

- [ ] **Step 2.3: Replace `topK` field with `recallTopK` + `rerankTopK` in SearchContext**

Remove the `private int topK;` block at line 60-61. Add the two new fields and a custom `build()` via Lombok `@Builder(builderClassName="SearchContextBuilder")` hook.

Full new field block (replacing lines 57-62 in `SearchContext.java`):

```java
    /**
     * 召回阶段的候选数（向量检索每个目标返回这么多）。
     * 由 {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties#getRecallTopK()} 或
     * 意图节点 override 推导。必须 >= rerankTopK。
     */
    private int recallTopK;

    /**
     * Rerank 后保留并喂给 LLM 的数量（最终 TopK）。
     * 由 {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties#getRerankTopK()} 或
     * {@link com.knowledgebase.ai.ragent.rag.core.intent.IntentNode#getTopK()} override 推导。
     */
    private int rerankTopK;
```

Add a nested builder override (put it inside the class, after the fields, before `getMainQuestion()`):

```java
**IMPORTANT design change:** DO NOT override Lombok's generated `build()`. Lombok's internal field names (`metadata$value`) and positional constructor order are implementation details we shouldn't depend on. Instead, **drop `@Builder` entirely on `SearchContext` and hand-write a small builder** with validation in its `build()`. This costs ~50 lines of explicit code but gives us full control and zero coupling to Lombok internals.

Full replacement of `SearchContext.java` class body. Remove the `@Builder` annotation at the class level (keep `@Data` for getters/setters/equals/hashCode). Hand-write the builder:

```java
    /**
     * Builder-level fail-fast：配置错 (recallTopK < rerankTopK 或非正) 直接 IAE，
     * 避免错配沉默流到 channel / rerank 后再爆。这是和
     * {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties#validate()} 的第二道保险。
     * <p>
     * 不用 Lombok @Builder 是因为需要在 build() 内做校验；Lombok 生成的 build() 可以通过
     * 继承覆盖，但依赖内部字段名 (metadata$value) 和 positional ctor 顺序，跨 Lombok 版本
     * 脆弱。手写 builder 换来完全可控 + 零 Lombok 内部耦合。
     */
    public static SearchContextBuilder builder() {
        return new SearchContextBuilder();
    }

    public static class SearchContextBuilder {
        private String originalQuestion;
        private String rewrittenQuestion;
        private List<String> subQuestions;
        private List<SubQuestionIntent> intents;
        private int recallTopK;
        private int rerankTopK;
        private AccessScope accessScope;
        private Map<String, Integer> kbSecurityLevels;
        private Map<String, Object> metadata;

        public SearchContextBuilder originalQuestion(String v) { this.originalQuestion = v; return this; }
        public SearchContextBuilder rewrittenQuestion(String v) { this.rewrittenQuestion = v; return this; }
        public SearchContextBuilder subQuestions(List<String> v) { this.subQuestions = v; return this; }
        public SearchContextBuilder intents(List<SubQuestionIntent> v) { this.intents = v; return this; }
        public SearchContextBuilder recallTopK(int v) { this.recallTopK = v; return this; }
        public SearchContextBuilder rerankTopK(int v) { this.rerankTopK = v; return this; }
        public SearchContextBuilder accessScope(AccessScope v) { this.accessScope = v; return this; }
        public SearchContextBuilder kbSecurityLevels(Map<String, Integer> v) { this.kbSecurityLevels = v; return this; }
        public SearchContextBuilder metadata(Map<String, Object> v) { this.metadata = v; return this; }

        public SearchContext build() {
            if (recallTopK <= 0) {
                throw new IllegalArgumentException(
                        "SearchContext.recallTopK must be > 0, got: " + recallTopK);
            }
            if (rerankTopK <= 0) {
                throw new IllegalArgumentException(
                        "SearchContext.rerankTopK must be > 0, got: " + rerankTopK);
            }
            if (recallTopK < rerankTopK) {
                throw new IllegalArgumentException(
                        "SearchContext.recallTopK (" + recallTopK
                                + ") must be >= rerankTopK (" + rerankTopK + ")");
            }
            SearchContext ctx = new SearchContext();
            ctx.setOriginalQuestion(originalQuestion);
            ctx.setRewrittenQuestion(rewrittenQuestion);
            ctx.setSubQuestions(subQuestions);
            ctx.setIntents(intents);
            ctx.setRecallTopK(recallTopK);
            ctx.setRerankTopK(rerankTopK);
            ctx.setAccessScope(accessScope);
            ctx.setKbSecurityLevels(kbSecurityLevels);
            ctx.setMetadata(metadata != null ? metadata : new HashMap<>());
            return ctx;
        }
    }
```

Also: at the top of `SearchContext.java`, **remove** `import lombok.Builder;` and the `@Builder` class-level annotation. Keep `@Data` for getters/setters. The `@Builder.Default` note on `metadata` is no longer needed — handle the default inside the custom `build()`.

- [ ] **Step 2.4: Run test to verify pass**

Run: `mvn -pl bootstrap test -Dtest=SearchContextBuilderTest`
Expected: 3 tests PASS.

- [ ] **Step 2.5: Do NOT commit yet** — this commit will fail the wider build until Task 3+ land. Hold the change in working tree and proceed to Task 3.

```bash
git status   # verify SearchContext.java + SearchContextBuilderTest.java are modified/new
# DO NOT commit; build is broken elsewhere until Task 3
```

---

## Task 3: RetrievalEngine threads RetrievalPlan + IntentNode.topK override

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`

- [ ] **Step 3.1: Add a `RetrievalPlan` record inside RetrievalEngine**

Add this nested record at the bottom of `RetrievalEngine.java` (next to the existing `SubQuestionContext` record, around line 290):

```java
    /**
     * 单个子问题的 TopK 方案。
     * <p>
     * {@code rerankTopK} 来自 {@link IntentNode#getTopK()} override，fallback 到全局；
     * {@code recallTopK} 取 {@code max(globalRecallTopK, rerankTopK)}，保证召回池永远 ≥ 最终保留数，
     * 避免 rerank 想挑 N 但实际候选不足的情况。
     */
    public record RetrievalPlan(int recallTopK, int rerankTopK) {
        public RetrievalPlan {
            if (recallTopK < rerankTopK) {
                throw new IllegalArgumentException(
                        "RetrievalPlan.recallTopK (" + recallTopK
                                + ") must be >= rerankTopK (" + rerankTopK + ")");
            }
        }
    }
```

- [ ] **Step 3.2: Inject RagRetrievalProperties + replace resolveSubQuestionTopK**

In `RetrievalEngine.java`:

1. Add field in the `@RequiredArgsConstructor` area (after `multiChannelRetrievalEngine`):
```java
    private final com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties ragRetrievalProperties;
```

2. Replace the `retrieve` method signature + body (lines 82-130). Drop the `int topK` param (callers will be updated in Task 8):

```java
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents,
                                     AccessScope accessScope, String knowledgeBaseId) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .mcpContext("")
                    .kbContext("")
                    .intentChunks(Map.of())
                    .build();
        }

        int globalRecall = ragRetrievalProperties.getRecallTopK();
        int globalRerank = ragRetrievalProperties.getRerankTopK();

        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> buildSubQuestionContext(
                                si,
                                resolveSubQuestionPlan(si, globalRecall, globalRerank),
                                accessScope,
                                knowledgeBaseId
                        ),
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        StringBuilder kbBuilder = new StringBuilder();
        StringBuilder mcpBuilder = new StringBuilder();
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new ConcurrentHashMap<>();

        for (SubQuestionContext context : contexts) {
            if (StrUtil.isNotBlank(context.kbContext())) {
                appendSection(kbBuilder, context.question(), context.kbContext());
            }
            if (StrUtil.isNotBlank(context.mcpContext())) {
                appendSection(mcpBuilder, context.question(), context.mcpContext());
            }
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        return RetrievalContext.builder()
                .mcpContext(mcpBuilder.toString().trim())
                .kbContext(kbBuilder.toString().trim())
                .intentChunks(mergedIntentChunks)
                .build();
    }
```

3. Replace `buildSubQuestionContext(SubQuestionIntent intent, int topK, ...)` with the plan version (lines 132-144):

```java
    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, RetrievalPlan plan,
                                                       AccessScope accessScope, String knowledgeBaseId) {
        List<NodeScore> kbIntents = filterKbIntents(intent.nodeScores());
        List<NodeScore> mcpIntents = filterMCPIntents(intent.nodeScores());

        KbResult kbResult = retrieveAndRerank(intent, kbIntents, plan, accessScope, knowledgeBaseId);

        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }
```

4. Replace `resolveSubQuestionTopK(...)` (lines 146-160) with:

```java
    /**
     * 子问题的 TopK 方案：
     * 1. rerankTopK = max(kbIntents 中 node.topK) ?? globalRerankTopK
     *    (IntentNode.topK 沿用"最终保留数"语义，不改已有节点配置和前端文案)
     * 2. recallTopK = max(globalRecallTopK, rerankTopK)
     *    (保证召回池 ≥ 最终保留数)
     */
    private RetrievalPlan resolveSubQuestionPlan(SubQuestionIntent intent,
                                                  int globalRecall, int globalRerank) {
        int effectiveRerank = filterKbIntents(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(globalRerank);
        int effectiveRecall = Math.max(globalRecall, effectiveRerank);
        return new RetrievalPlan(effectiveRecall, effectiveRerank);
    }
```

5. Update `retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, int topK, ...)` signature (line 203) to accept the plan:

```java
    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, RetrievalPlan plan,
                                        AccessScope accessScope, String knowledgeBaseId) {
        List<SubQuestionIntent> subIntents = List.of(intent);
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(
                subIntents, plan, accessScope, knowledgeBaseId);

        if (CollUtil.isEmpty(chunks)) {
            return KbResult.empty();
        }

        Map<String, List<RetrievedChunk>> intentChunks = new ConcurrentHashMap<>();
        if (CollUtil.isNotEmpty(kbIntents)) {
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }

        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, plan.rerankTopK());
        return new KbResult(groupedContext, intentChunks);
    }
```

6. Remove the now-unused `DEFAULT_TOP_K` static import if it's no longer referenced in the file.

- [ ] **Step 3.3: Update MultiChannelRetrievalEngine.retrieveKnowledgeChannels signature**

Replace `retrieveKnowledgeChannels(List<SubQuestionIntent>, int topK, ...)` (line 78-116) with the plan-carrying version:

```java
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents,
                                                           com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine.RetrievalPlan plan,
                                                           AccessScope accessScope, String knowledgeBaseId) {
        SearchContext context = buildSearchContext(subIntents, plan, accessScope);

        // 单知识库定向检索路径（召回数直接用 recallTopK）
        if (knowledgeBaseId != null) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(knowledgeBaseId);
            if (kb == null || kb.getCollectionName() == null) {
                return List.of();
            }
            RetrieveRequest req = RetrieveRequest.builder()
                    .query(context.getMainQuestion())
                    .topK(plan.recallTopK())
                    .collectionName(kb.getCollectionName())
                    .metadataFilters(metadataFilterBuilder.build(context, knowledgeBaseId))
                    .build();
            List<RetrievedChunk> chunks = retrieverService.retrieve(req);

            SearchChannelResult singleResult = SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName("single-kb-" + kb.getCollectionName())
                    .chunks(chunks)
                    .confidence(1.0)
                    .latencyMs(0)
                    .build();
            return executePostProcessors(List.of(singleResult), context);
        }

        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return List.of();
        }

        return executePostProcessors(channelResults, context);
    }
```

- [ ] **Step 3.4: Update buildSearchContext to accept RetrievalPlan and populate both TopK fields**

Replace `buildSearchContext(List<SubQuestionIntent>, int topK, AccessScope)` (lines 257-275) with:

```java
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents,
                                              com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine.RetrievalPlan plan,
                                              AccessScope accessScope) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();
        Map<String, Integer> kbSecurityLevels;
        if (accessScope instanceof AccessScope.Ids ids && !ids.kbIds().isEmpty() && UserContext.hasUser()) {
            kbSecurityLevels = kbAccessService.getMaxSecurityLevelsForKbs(UserContext.getUserId(), ids.kbIds());
        } else {
            kbSecurityLevels = Collections.emptyMap();
        }

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .recallTopK(plan.recallTopK())
                .rerankTopK(plan.rerankTopK())
                .accessScope(accessScope)
                .kbSecurityLevels(kbSecurityLevels)
                .build();
    }
```

- [ ] **Step 3.5: Verify compilation of RetrievalEngine + MultiChannelRetrievalEngine**

Run: `mvn -pl bootstrap compile -q`
Expected: Compilation fails in Channel classes / RAGChatServiceImpl / tests (fine — addressed in Tasks 4-8). If RetrievalEngine itself or MultiChannelRetrievalEngine has a compile error, fix before proceeding.

- [ ] **Step 3.6: Do NOT commit yet** — build is broken on caller side (channels + RAGChatServiceImpl). Proceed to Task 4.

---

## Task 4: IntentDirectedSearchChannel uses recallTopK + sort+cap

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java`

- [ ] **Step 4.1: Simplify IntentParallelRetriever signature (drop multiplier)**

In `IntentParallelRetriever.java`, replace the 5-arg `executeParallelRetrieval` overload (lines 58-71) with a 4-arg version:

```java
    /**
     * 并行检索每个意图的 KB，每个目标各自取 perIntentRecallTopK 个候选。
     * 通道级 sort+cap 由 caller (IntentDirectedSearchChannel) 统一处理。
     */
    public List<RetrievedChunk> executeParallelRetrieval(String question,
                                                         List<NodeScore> targets,
                                                         int perIntentRecallTopK,
                                                         SearchContext context) {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> new IntentTask(
                        nodeScore,
                        resolveIntentRecallTopK(nodeScore, perIntentRecallTopK),
                        metadataFilterBuilder.build(
                                context, nodeScore.getNode().getKbId())))
                .toList();
        return super.executeParallelRetrieval(question, intentTasks, perIntentRecallTopK);
    }
```

Replace `resolveIntentTopK` (lines 108-123) with:

```java
    /**
     * 单意图召回数：优先 node.topK（已改语义为最终保留数，这里按最终保留数兜底放大到 perIntent recall），
     * 否则用全局 perIntentRecallTopK。
     */
    private int resolveIntentRecallTopK(NodeScore nodeScore, int perIntentRecallTopK) {
        if (nodeScore != null && nodeScore.getNode() != null) {
            Integer nodeTopK = nodeScore.getNode().getTopK();
            if (nodeTopK != null && nodeTopK > 0) {
                // node.topK 是"最终保留数"。召回至少放到与全局 recall 同等规模，保证 rerank 有挑选空间。
                return Math.max(perIntentRecallTopK, nodeTopK);
            }
        }
        return perIntentRecallTopK;
    }
```

- [ ] **Step 4.2: Update IntentDirectedSearchChannel to use recallTopK + sort+cap**

In `IntentDirectedSearchChannel.java`, replace the `search` method (lines 86-147). Remove `topKMultiplier` reads and add per-channel sort+cap:

```java
    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            List<NodeScore> kbIntents = extractKbIntents(context);

            if (CollUtil.isEmpty(kbIntents)) {
                log.warn("意图定向检索通道被启用，但未找到 KB 意图（不应该发生）");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.INTENT_DIRECTED)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            log.info("执行意图定向检索，识别出 {} 个 KB 意图", kbIntents.size());

            int recallTopK = context.getRecallTopK();
            List<RetrievedChunk> fanOutChunks = parallelRetriever.executeParallelRetrieval(
                    context.getMainQuestion(),
                    kbIntents,
                    recallTopK,
                    context
            );

            // 通道级 sort+cap：多意图 fan-out 后可能多达 N*recallTopK 条，
            // 按上游 score 降序取前 recallTopK，把"通道贡献给下游 rerank 的候选数"严格收口到 recallTopK。
            List<RetrievedChunk> cappedChunks = fanOutChunks.stream()
                    .sorted(java.util.Comparator.comparing(
                            com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk::getScore,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .limit(recallTopK)
                    .toList();

            double confidence = kbIntents.stream()
                    .mapToDouble(NodeScore::getScore)
                    .max()
                    .orElse(0.0);

            long latency = System.currentTimeMillis() - startTime;

            log.info("意图定向检索完成，fan-out {} -> cap {} 个 Chunk，置信度：{}，耗时 {}ms",
                    fanOutChunks.size(), cappedChunks.size(), confidence, latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(cappedChunks)
                    .confidence(confidence)
                    .latencyMs(latency)
                    .metadata(Map.of("intentCount", kbIntents.size()))
                    .build();

        } catch (Exception e) {
            log.error("意图定向检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
```

Also delete the now-unused `retrieveByIntents` private method (old lines 172-184).

- [ ] **Step 4.3: Do NOT commit yet** — VectorGlobalSearchChannel still references old `context.getTopK()` API. Proceed to Task 5.

---

## Task 5: VectorGlobalSearchChannel uses recallTopK + sort+cap

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`

- [ ] **Step 5.1: Update VectorGlobalSearchChannel.search**

Replace the search method body that reads `context.getTopK() * topKMultiplier` (line 132) with direct `recallTopK` use + sort+cap:

```java
    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("执行向量全局检索，问题：{}", context.getMainQuestion());

            List<KnowledgeBaseDO> kbs = getAccessibleKBs(context);

            if (kbs.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过全局检索");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            int recallTopK = context.getRecallTopK();
            List<RetrievedChunk> fanOutChunks = retrieveFromAllCollections(
                    context.getMainQuestion(),
                    kbs,
                    context,
                    recallTopK
            );

            // 通道级 sort+cap：多 KB fan-out 后可能 N*recallTopK 条，按 score 降序取前 recallTopK。
            List<RetrievedChunk> cappedChunks = fanOutChunks.stream()
                    .sorted(java.util.Comparator.comparing(
                            RetrievedChunk::getScore,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .limit(recallTopK)
                    .toList();

            long latency = System.currentTimeMillis() - startTime;

            log.info("向量全局检索完成，fan-out {} -> cap {} 个 Chunk，耗时 {}ms",
                    fanOutChunks.size(), cappedChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(cappedChunks)
                    .confidence(0.7)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("向量全局检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
```

- [ ] **Step 5.2: Do NOT commit yet** — RerankPostProcessor still reads old `context.getTopK()`. Proceed to Task 6.

---

## Task 6: RerankPostProcessor sort → cap(recallTopK) → rerank(rerankTopK)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessor.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessorTest.java`

- [ ] **Step 6.1: Write the failing test locking sort → cap → rerank contract**

```java
// bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessorTest.java
package com.knowledgebase.ai.ragent.rag.core.retrieve.postprocessor;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.infra.rerank.RerankService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RerankPostProcessorTest {

    @Test
    void capsInputToRecallTopKSortedByScoreDescBeforeRerank() {
        RerankService rerank = mock(RerankService.class);
        // Stub: echo back first rerankTopK candidates to keep test focused on input shape.
        when(rerank.rerank(anyString(), any(), anyInt())).thenAnswer(inv -> {
            List<RetrievedChunk> in = inv.getArgument(1);
            int topN = inv.getArgument(2);
            return in.subList(0, Math.min(in.size(), topN));
        });

        RerankPostProcessor processor = new RerankPostProcessor(rerank);

        // 60 chunks with random-looking scores; expect top 30 (by score desc) reach rerank.
        List<RetrievedChunk> chunks = IntStream.range(0, 60)
                .mapToObj(i -> RetrievedChunk.builder()
                        .id("c" + i)
                        .text("t" + i)
                        // alternating score pattern ensures a mix of hi/lo — top30 will be
                        // the 30 ids with the highest (i % 7 * 0.1f + 0.05f) style scores.
                        .score(((i * 13) % 100) / 100.0f)
                        .build())
                .toList();

        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        List<RetrievedChunk> out = processor.process(chunks, List.of(), ctx);

        ArgumentCaptor<List<RetrievedChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(rerank).rerank(anyString(), captor.capture(), anyInt());
        List<RetrievedChunk> sentToRerank = captor.getValue();

        assertThat(sentToRerank).hasSize(30);
        // Must be sorted by score desc
        for (int i = 1; i < sentToRerank.size(); i++) {
            float prev = sentToRerank.get(i - 1).getScore();
            float cur = sentToRerank.get(i).getScore();
            assertThat(prev).isGreaterThanOrEqualTo(cur);
        }
        // Output is the rerank truncation (stub returned first 10)
        assertThat(out).hasSize(10);
    }

    @Test
    void callsRerankWithRerankTopKAsTopN() {
        RerankService rerank = mock(RerankService.class);
        when(rerank.rerank(anyString(), any(), anyInt())).thenReturn(List.of());
        RerankPostProcessor processor = new RerankPostProcessor(rerank);

        List<RetrievedChunk> chunks = IntStream.range(0, 15)
                .mapToObj(i -> RetrievedChunk.builder().id("c" + i).score(0.1f * i).build())
                .toList();

        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        processor.process(chunks, List.of(), ctx);

        verify(rerank).rerank(anyString(), any(), org.mockito.ArgumentMatchers.eq(10));
    }

    @Test
    void skipsRerankWhenChunksEmpty() {
        RerankService rerank = mock(RerankService.class);
        RerankPostProcessor processor = new RerankPostProcessor(rerank);

        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        List<RetrievedChunk> out = processor.process(List.of(), List.of(), ctx);

        assertThat(out).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(rerank);
    }
}
```

- [ ] **Step 6.2: Run test — expect compile failure because RerankPostProcessor doesn't know recallTopK yet**

Run: `mvn -pl bootstrap test -Dtest=RerankPostProcessorTest`
Expected: Compilation failure or test failure (current processor calls `context.getTopK()` which no longer exists after Task 2).

- [ ] **Step 6.3: Update RerankPostProcessor.process()**

Replace `process` method (lines 58-72) with:

```java
    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }

        int recallTopK = context.getRecallTopK();
        int rerankTopK = context.getRerankTopK();

        // 全局 sort + cap：把跨通道 dedup 后的候选按上游相对分排序，取前 recallTopK
        // 送入 rerank。排序是必须的——dedup 后顺序不保证 score desc，直接 limit 会砍错。
        List<RetrievedChunk> capped = chunks.stream()
                .sorted(java.util.Comparator.comparing(
                        RetrievedChunk::getScore,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(recallTopK)
                .toList();

        log.info("Rerank 入口：chunks={}, cappedToRecall={}, rerankTopK={}",
                chunks.size(), capped.size(), rerankTopK);

        return rerankService.rerank(
                context.getMainQuestion(),
                capped,
                rerankTopK
        );
    }
```

- [ ] **Step 6.4: Run test to verify pass**

Run: `mvn -pl bootstrap test -Dtest=RerankPostProcessorTest`
Expected: 3 tests PASS.

- [ ] **Step 6.5: Do NOT commit yet** — RAGChatServiceImpl and MultiChannelRetrievalEnginePostProcessorChainTest still broken.

---

## Task 7: BaiLianRerankClient defensive `effectiveTopN` clamp

**Files:**
- Modify: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java`

- [ ] **Step 7.1: Add a failing unit test for small-KB scenario**

Find or create a test for `BaiLianRerankClient`. If none exists, check `infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/rerank/` for the closest neighbor. Create `BaiLianRerankClientSmallKbTest.java`:

```java
// infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClientSmallKbTest.java
package com.knowledgebase.ai.ragent.infra.rerank;

import com.google.gson.JsonObject;
import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.infra.model.ModelTarget;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaiLianRerankClientSmallKbTest {

    private MockWebServer server;
    private BaiLianRerankClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new BaiLianRerankClient(new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void clampsTopNToCandidatesSizeWhenCandidatesSmaller() throws Exception {
        // Serve a minimal valid 百炼 rerank response with 3 results
        String body = """
                {"output":{"results":[
                  {"index":0,"relevance_score":0.9,"document":{"text":"a"}},
                  {"index":1,"relevance_score":0.7,"document":{"text":"b"}},
                  {"index":2,"relevance_score":0.3,"document":{"text":"c"}}
                ]}}""";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body)
                .addHeader("Content-Type", "application/json"));

        List<RetrievedChunk> candidates = List.of(
                RetrievedChunk.builder().id("a").text("a").score(0.1f).build(),
                RetrievedChunk.builder().id("b").text("b").score(0.2f).build(),
                RetrievedChunk.builder().id("c").text("c").score(0.3f).build()
        );

        AIModelProperties.Candidate candidate = new AIModelProperties.Candidate();
        candidate.setProvider("bailian");
        candidate.setModel("qwen3-rerank");
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString());
        provider.setApiKey("k");
        ModelTarget target = new ModelTarget(candidate, provider);

        // Ask for topN=10 but we only have 3 candidates → API should be called with top_n=3.
        List<RetrievedChunk> out = client.rerank("q", candidates, 10, target);

        RecordedRequest req = server.takeRequest();
        String sent = req.getBody().readUtf8();
        // parse and assert top_n field equals 3 (clamped), not 10
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(sent).getAsJsonObject();
        int topNSent = json.getAsJsonObject("parameters").get("top_n").getAsInt();
        assertThat(topNSent).isEqualTo(3);

        assertThat(out).hasSize(3);
    }
}
```

**NOTE:** `ModelTarget` and `AIModelProperties.Candidate` constructors may differ from what's shown. Before writing this test, grep them with: `Grep "class ModelTarget" infra-ai/src/main/java` and `Grep "class Candidate" infra-ai`. Use the real constructor/record shape found there. If `MockWebServer` is not on the classpath, add `com.squareup.okhttp3:mockwebserver:4.12.0` to `infra-ai/pom.xml` test scope.

- [ ] **Step 7.2: Run test — expect failure (current client sends topN unchanged)**

Run: `mvn -pl infra-ai test -Dtest=BaiLianRerankClientSmallKbTest`
Expected: FAIL — asserts `topNSent=3` but actual is 10.

- [ ] **Step 7.3: Clamp effectiveTopN inside doRerank**

In `BaiLianRerankClient.java`, edit `doRerank` (around line 83). Replace:

```java
        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);
```

with:

```java
        // 防御性 clamp：候选数 < 请求 top_n 时，百炼 API 对 top_n>documents 的行为未验证；
        // 直接 clamp 到候选数，避免依赖 API 的 fallback。上层是否截断由 caller 决定，这里只保证请求合法。
        int effectiveTopN = Math.min(topN, candidates.size());
        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", effectiveTopN);
        parameters.addProperty("return_documents", true);
```

Also update the loop that fills `reranked` (around line 166) — replace `if (reranked.size() >= topN) break;` with `if (reranked.size() >= effectiveTopN) break;` to stay consistent. Same for the fallback loop below (`while reranked.size() < topN`) → `while reranked.size() < effectiveTopN`.

- [ ] **Step 7.4: Run test to verify pass**

Run: `mvn -pl infra-ai test -Dtest=BaiLianRerankClientSmallKbTest`
Expected: PASS.

- [ ] **Step 7.5: Install infra-ai locally** (bootstrap needs the updated jar)

Run: `mvn -pl infra-ai install -DskipTests -q`
Expected: exit=0.

- [ ] **Step 7.6: Do NOT commit yet** — one more caller to fix.

---

## Task 8: RAGChatServiceImpl + EvaluationCollector javadoc

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/EvaluationCollector.java`

- [ ] **Step 8.1: Inject RagRetrievalProperties**

In `RAGChatServiceImpl.java`, Lombok `@RequiredArgsConstructor` generates the ctor. Add the property as a final field:

Find the field block around the top of the class and add:

```java
    private final com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties ragRetrievalProperties;
```

- [ ] **Step 8.2: Update the `retrievalEngine.retrieve(...)` call site**

Line 188 currently reads:

```java
RetrievalContext ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K, accessScope, knowledgeBaseId);
```

Replace with (drop the `DEFAULT_TOP_K` arg, signature changed in Task 3):

```java
RetrievalContext ctx = retrievalEngine.retrieve(subIntents, accessScope, knowledgeBaseId);
```

- [ ] **Step 8.3: Update `evalCollector.setTopK` to record actual chunk count**

Line 254:

```java
evalCollector.setTopK(DEFAULT_TOP_K);
```

Replace with:

```java
// 记录实际喂给 LLM 的 chunk 数（不是 config 默认值）。
// 这样对 IntentNode.topK override 场景、多 sub-question 聚合 + dedup 都是真值，
// 避免"配置写 10、实际 8 或 13"在评测里记错。
evalCollector.setTopK(distinctChunks.size());
```

**Why not `ragRetrievalProperties.getRerankTopK()`?** Per-sub-question `IntentNode.topK` override + multi-sub-question dedup means the actual chunks reaching the LLM can diverge from the global config. `distinctChunks.size()` is the ground truth at the moment `evalCollector.setTopK` is called (after retrieval, before prompt). Historical code had the same bug (hardcoded `DEFAULT_TOP_K=10`), we fix it here.

- [ ] **Step 8.4: Remove unused import of DEFAULT_TOP_K if no longer referenced**

If after edits `DEFAULT_TOP_K` is no longer used in `RAGChatServiceImpl.java`, remove `import static com.knowledgebase.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;` (around line 75).

- [ ] **Step 8.5: Add javadoc to EvaluationCollector.topK**

In `EvaluationCollector.java` line 40, replace:

```java
    private int topK;
```

with:

```java
    /**
     * 评测记录：实际喂给 LLM 的 chunk 数（dedup 后 distinctChunks.size()）。
     * <p>
     * 不等于全局 rag.retrieval.rerank-top-k 配置值——IntentNode.topK override、多 sub-question
     * 聚合 + dedup 会让实际数量与配置值分叉。评测口径采用"真值"：记录 prompt 阶段真实看到的
     * chunk 数，方便后续 recall/precision 分析。
     * <p>
     * 命名保留 "topK" 是为避免数据库 schema migration。若将来需要分别评测召回和 rerank 两个
     * 阶段，再在 t_rag_evaluation_record 加 recall_top_k 列。
     */
    private int topK;
```

- [ ] **Step 8.6: Full compile check**

Run: `mvn -pl bootstrap compile -q`
Expected: exit=0 (bootstrap now compiles; callers + callees aligned).

- [ ] **Step 8.7: Do NOT commit yet** — existing integration test still uses old SearchContext shape. Fix in Task 9.

---

## Task 9: Update existing tests to new SearchContext + RetrievalEngine signatures

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEnginePostProcessorChainTest.java`
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`
- Any other file matched by the two greps below.

- [ ] **Step 9.1: Run full test suite to see what breaks**

Run: `mvn -pl bootstrap test -q 2>&1 | tail -80`
Expected: Compilation errors in at least two places:
1. `MultiChannelRetrievalEnginePostProcessorChainTest` — uses `SearchContext.builder().topK(...)` which no longer exists
2. `RAGChatServiceImplSourcesTest` — has **10 call sites** using `when(retrievalEngine.retrieve(any(), anyInt(), any(), any()))...` and `verify(retrievalEngine).retrieve(any(), anyInt(), any(), any())`. After Task 3 dropped the `int topK` param, the signature is now `retrieve(List, AccessScope, String)` — three args, no `anyInt()`.

- [ ] **Step 9.2: Fix RAGChatServiceImplSourcesTest — drop the anyInt() arg from all retrieve() stubs/verifications**

Run: `grep -n "retrieve(any(), anyInt(), any(), any())" bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`
Expected: ~10 matches.

For each match, replace `retrieve(any(), anyInt(), any(), any())` with `retrieve(any(), any(), any())`. This is mechanical — either do it with `sed`-style replace-all in the editor, or use:

```bash
# Verify count before
grep -c "retrieve(any(), anyInt(), any(), any())" \
  bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java
```

Apply the edit. Then re-check:

```bash
# Should now be 0
grep -c "retrieve(any(), anyInt(), any(), any())" \
  bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java
# Should match the old count
grep -c "retrieve(any(), any(), any())" \
  bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java
```

If `anyInt` import becomes unused after the edit, remove `import static org.mockito.ArgumentMatchers.anyInt;` (IntelliJ's "Optimize Imports" or manual).

- [ ] **Step 9.3: Replace `.topK(...)` with `.recallTopK(...).rerankTopK(...)` in all affected tests**

In `MultiChannelRetrievalEnginePostProcessorChainTest.java`, find every `SearchContext.builder()` chain and replace `.topK(N)` with `.recallTopK(N).rerankTopK(N)`. (For tests that don't need the distinction, using the same value for both is correct — the test is about the chain, not the cap behavior.)

Also check and fix any other test that uses `SearchContext.builder().topK(...)`:

Run: `grep -rn "SearchContext.builder" bootstrap/src/test/java` and update each callsite in the same fashion.

- [ ] **Step 9.4: Check any code using `context.getTopK()`**

Run: `grep -rn "context.getTopK\|ctx.getTopK\|\.getTopK()" bootstrap/src/test/java bootstrap/src/main/java | grep -v "IntentNode\|nodeTopK\|IntentTreeService"`

Filter out `IntentNode.getTopK` calls (those refer to the node-level override, not `SearchContext`). Expected: zero matches on `SearchContext.getTopK()` after the grep filter.

If remaining callers exist on `SearchContext`, update them to use `getRecallTopK()` or `getRerankTopK()` per context — `getRerankTopK()` is the likely right answer for any place that previously thought of "topK" as "final keep count".

- [ ] **Step 9.5: Run full test suite**

Run: `mvn -pl bootstrap test -q 2>&1 | tail -30`
Expected: Only pre-existing failures from `CLAUDE.md` "Pre-existing test failures on fresh checkout" list. No new failures.

Pre-existing failures (ignore): `MilvusCollectionTests`, `InvoiceIndexDocumentTests`, `PgVectorStoreServiceTest.testChineseCharacterInsertion`, `IntentTreeServiceTests.initFromFactory`, `VectorTreeIntentClassifierTests`.

- [ ] **Step 9.6: Remove old SEARCH_TOP_K_MULTIPLIER constants from RAGConstant.java if unused**

Run: `grep -rn "SEARCH_TOP_K_MULTIPLIER\|RERANK_LIMIT_MULTIPLIER\|MIN_SEARCH_TOP_K" bootstrap/src/main/java bootstrap/src/test/java`

If zero non-definition matches (i.e. only the declaration in `RAGConstant.java`), delete those three constants (lines ~68-78 in `RAGConstant.java`). If still referenced elsewhere, leave them and note in the final commit message as follow-up.

**Note:** `DEFAULT_TOP_K` may still be referenced by code outside this PR's scope (e.g. places that don't touch retrieval). Grep and judge. Safe default: leave `DEFAULT_TOP_K` in place; delete only `SEARCH_TOP_K_MULTIPLIER`/`RERANK_LIMIT_MULTIPLIER`/`MIN_SEARCH_TOP_K` if truly dead.

- [ ] **Step 9.7: Drop stale topKMultiplier config (cosmetic)**

`SearchChannelProperties.java` still has `topKMultiplier` fields in both `VectorGlobal` and `IntentDirected` inner classes. They are no longer read by channel code. Two choices:
- **Conservative:** leave fields in place with a javadoc `@deprecated` tag; caller deletion is a separate follow-up PR.
- **Aggressive:** delete the fields + any corresponding yaml under `rag.search.channels.*.top-k-multiplier` (if present in `application.yaml`).

**Pick conservative for this PR** — drop usage, keep the bean field with `@Deprecated` and a javadoc note pointing to `RagRetrievalProperties`. This keeps the blast radius of PR2 bounded to retrieval logic; `SearchChannelProperties` cleanup belongs in a separate small PR.

Add `@Deprecated(forRemoval = true)` + javadoc to both `topKMultiplier` fields in `SearchChannelProperties.java`:

```java
        /**
         * @deprecated since PR2 — retrieval topK amplification moved into
         * {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties} (recallTopK / rerankTopK split).
         * This field is no longer read by channel code; left in place only to avoid breaking existing yaml.
         * Will be removed once all deployments drop the setting from their config.
         */
        @Deprecated(forRemoval = true)
        private int topKMultiplier = 3;
```

- [ ] **Step 9.8: Commit the entire PR2 as one commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalProperties.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagRetrievalPropertiesTest.java \
        bootstrap/src/main/resources/application.yaml \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContextBuilderTest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessor.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/RerankPostProcessorTest.java \
        infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java \
        infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClientSmallKbTest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/EvaluationCollector.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEnginePostProcessorChainTest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SearchChannelProperties.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/constant/RAGConstant.java

git commit -m "$(cat <<'EOF'
feat(retrieval): split recallTopK / rerankTopK for over-retrieve + rerank [PR2]

PR2 after PR1 fixed rerank bypass: even with cross-encoder scores flowing,
recall=rerank=10 left rerank no selection room — it could only rescore the 10
candidates bi-encoder picked, not demote bi-encoder misses. Standard RAG is
over-retrieve + rerank: recall wider, rerank narrower.

Structural changes:
- New RagRetrievalProperties (rag.retrieval.recall-top-k=30 / rerank-top-k=10)
  with @PostConstruct validation (recallTopK >= rerankTopK, both > 0).
- SearchContext: drop single topK, add recallTopK + rerankTopK with builder-time
  IAE guard. Double safety net with Properties.validate(), fail-fast at both
  config bind and per-request build.
- RetrievalEngine: add RetrievalPlan(recallTopK, rerankTopK) record + per-sub-
  question override rule: effectiveRerankTopK = node.topK ?? globalRerankTopK,
  effectiveRecallTopK = max(globalRecallTopK, effectiveRerankTopK). IntentNode.topK
  keeps its "final keep count" semantic so existing DB rows and admin UI stay correct.
- Channels: IntentDirectedSearchChannel and VectorGlobalSearchChannel drop the old
  topKMultiplier amplifier; each retrieves per-target at recallTopK and internally
  sort+caps to recallTopK before returning. This prevents N-intent / N-KB fan-out
  from exploding to 60+ candidates feeding rerank.
- RerankPostProcessor: sort(score desc) -> limit(recallTopK) -> rerank(rerankTopK).
  Sorting before cap is load-bearing — dedup order isn't by score and limit would
  drop good candidates silently.
- BaiLianRerankClient: effectiveTopN = min(topN, candidates.size()) before sending
  top_n to 百炼. Small KBs (<rerankTopK chunks) now work without relying on undefined
  API behavior.
- EvaluationCollector.topK javadoc clarifying it stores rerankTopK (no schema migration).

Behavioral follow-ups:
- SearchChannelProperties.topKMultiplier fields marked @Deprecated(forRemoval=true);
  will delete fields + any stale yaml in a follow-up cleanup PR.
- SEARCH_TOP_K_MULTIPLIER / RERANK_LIMIT_MULTIPLIER / MIN_SEARCH_TOP_K constants
  in RAGConstant.java removed if fully unused (see commit diff).

Tests added:
- RagRetrievalPropertiesTest: binding validation (valid / recall<rerank / zero).
- SearchContextBuilderTest: builder-time IAE on misconfig.
- RerankPostProcessorTest: locks sort -> cap(recallTopK) -> rerank(rerankTopK)
  invariant with ArgumentCaptor; asserts rerankService receives exactly
  recallTopK candidates in score-desc order and topN=rerankTopK.
- BaiLianRerankClientSmallKbTest: MockWebServer proves top_n is clamped to
  candidates.size() when KB has <rerankTopK chunks.

EOF
)"
```

- [ ] **Step 9.9: Verify with a real run**

Restart backend: `mvn -pl infra-ai install -DskipTests && $env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run`

First read current sources gate threshold so expectations are parametric, not hardcoded:

```bash
grep "min-top-score" bootstrap/src/main/resources/application.yaml
```

Note the value (call it `CURRENT_THRESHOLD`).

Then from the frontend, ask the same two queries from PR1:
- "高风险客户需要做哪些额外验证"（relevant）
- "地球到太阳的距离"（unrelated）

Expected log lines (the key observations, where `CURRENT_THRESHOLD` is whatever was grepped above):

```
Rerank 入口：chunks=<~30-60>, cappedToRecall=30, rerankTopK=10
[bailian-rerank] CALLING API: dedup=30, topN=10
[sources-gate] distinctChunks=<~10>, maxScore=<relevant: >0.6; unrelated: <0.3>, minTopScore=<CURRENT_THRESHOLD>
```

**The load-bearing observation is `dedup=30` in the bailian log** — this proves rerank now receives a 30-wide candidate pool and picks the top 10. Before PR2 the log said `dedup=10, topN=10`.

The `minTopScore` log value should match `CURRENT_THRESHOLD` from the grep — the plan does not modify `rag.sources.min-top-score`, so the value is whatever the tree already has.

---

## Self-Review

**1. Spec coverage:**
- ✅ `RagRetrievalProperties` with binding + builder double validation — Task 1 + Task 2
- ✅ `SearchContext` split + IAE — Task 2
- ✅ `RerankPostProcessor` sort → cap → rerank — Task 6
- ✅ `IntentNode.topK` compat override — Task 3 (`resolveSubQuestionPlan`)
- ✅ `EvaluationCollector.topK` javadoc only, no schema change — Task 8
- ✅ `BaiLianRerankClient` `effectiveTopN = min(topN, candidates.size())` — Task 7
- ✅ Multi-channel fan-out cap per top-level channel — Tasks 4 + 5 (sort+cap before return)
- ✅ Global pre-rerank cap in `RerankPostProcessor` — Task 6
- ✅ Small KB edge-case integration test — Task 7 (MockWebServer based)
- ✅ Existing `MultiChannelRetrievalEnginePostProcessorChainTest` updated — Task 9

**2. Placeholder scan:** No "TBD" / "implement later" / "similar to Task N" — every task has full code or exact grep/edit instructions.

**3. Type consistency:**
- `RetrievalPlan(int recallTopK, int rerankTopK)` — used consistently in Task 3 (definition) and referenced in `MultiChannelRetrievalEngine.retrieveKnowledgeChannels` signature.
- `SearchContext.getRecallTopK()` / `getRerankTopK()` — set in Task 2, read in Tasks 4, 5, 6.
- `ragRetrievalProperties.getRecallTopK()` / `getRerankTopK()` — set in Task 1, read in Tasks 3 and 8.
- `effectiveTopN` — local to Task 7, not cross-task.

**4. Open risks flagged (not plan blockers):**
- Lombok `@Builder` nested class override (Task 2.3) may not compile if Lombok's generated constructor order differs. Fallback: inline validation via static factory or `@Builder.ObtainVia`. Test compile first.
- `MockWebServer` dependency may not be on `infra-ai` classpath (Task 7.1). Verify with `mvn -pl infra-ai dependency:tree | grep mockwebserver` before relying on the test pattern.
- Lombok `@Builder.Default` field `metadata$value` reference in the Task 2.3 custom build() relies on Lombok's internal naming. If Lombok emits a different field name, fix inline.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-22-over-retrieve-rerank-pr2.md`. Two execution options:

1. **Subagent-Driven (recommended)** - fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** - execute tasks in this session using executing-plans, batch with checkpoints

Which approach?
