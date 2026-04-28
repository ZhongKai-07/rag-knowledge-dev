# Answer Sources PR2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 PR1 埋好的元数据（`RetrievedChunk.docId/chunkIndex` + `findMetaByIds`），经**编排层 + 聚合器 + SSE 事件**流向前端 store，feature flag 默认 off，零用户可见变化。

**Architecture:** RAG 编排层（`RAGChatServiceImpl`）在检索后、LLM 流启动前调用 `SourceCardBuilder` 聚合文档级卡片；通过 `SourceCardsHolder`（set-once CAS 容器）在同步段固化 cards、异步段只读快照；`callback.emitSources(payload)` 由 handler 机械发射 SSE `sources` 事件。前端新增 `case "sources"` 分支 + `chatStore.onSources` guard，写入 `Message.sources`，不渲染 UI。

**Tech Stack:** Java 17 + Spring Boot 3.5.7 + Lombok + JUnit 5 + Mockito + AssertJ（后端）；React 18 + TypeScript + Zustand + Vitest（前端，**Vitest 基础设施在 PR2 首次引入**）

---

## Spec Reference

- 设计文档：`docs/superpowers/specs/2026-04-21-answer-sources-pr2-design.md`（commit `d23ed26`）
- 上游 v1 spec：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`
- PR1 交接：`docs/superpowers/plans/2026-04-20-answer-sources-pr1-metadata.md`
- 基线分支：`feature/answer-sources-pr2`（tip 初始 `96b2d06`）

---

## File Structure

### 新增文件

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/
├── dto/
│   ├── SourceCard.java           (DTO: 文档级源卡片)
│   ├── SourceChunk.java          (DTO: 卡片内 chunk 片段)
│   └── SourcesPayload.java       (DTO: SSE sources 事件载荷)
├── service/handler/
│   └── SourceCardsHolder.java    (set-once CAS 容器)
├── core/source/
│   └── SourceCardBuilder.java    (聚合 chunks → cards)
└── config/
    └── RagSourcesProperties.java (feature flag 配置)

bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/
├── service/handler/
│   └── SourceCardsHolderTest.java
├── core/source/
│   └── SourceCardBuilderTest.java
├── service/impl/
│   └── RAGChatServiceImplSourcesTest.java
└── controller/
    └── RAGChatControllerSourcesIntegrationTest.java

frontend/
├── src/hooks/useStreamResponse.test.ts   (新)
├── src/stores/chatStore.test.ts          (新)
└── vitest.config.ts                      (新)
```

### 修改文件

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/
├── enums/SSEEventType.java                    (+SOURCES 枚举)
├── service/handler/StreamChatHandlerParams.java (+cardsHolder 字段)
├── service/handler/StreamChatEventHandler.java  (+trySetCards/emitSources 方法)
└── service/impl/RAGChatServiceImpl.java        (+注入+3 闸门+emit)

bootstrap/src/main/resources/application.yaml    (+rag.sources 配置块)

frontend/
├── src/types/index.ts              (+SourceCard/SourceChunk/SourcesPayload + Message.sources)
├── src/hooks/useStreamResponse.ts  (+case "sources" + onSources handler)
├── src/stores/chatStore.ts         (+onSources guard + 写入)
└── package.json                    (+vitest devDep + test script)
```

---

## Implementation Notes（user 的两条提醒）

1. **禁止 `.cardsHolder(null)` 显式调用**：`StreamChatHandlerParams.cardsHolder` 字段用 `@Builder.Default = new SourceCardsHolder()` + `@NonNull` 注解，即使有人显式传 null 也在 builder 端 NPE，不让空 holder 通过
2. **`emitSources` 异常语义沿用 `sender.sendEvent`**：方法体就是一行 `sender.sendEvent(...)` 调用，不加 try/catch / 不加定制日志，保持和现有 `SUGGESTIONS / FINISH / DONE` 事件一致的异常传播风格

---

## Scope Note: Frontend Vitest 基础设施首次引入

前端当前**没有**任何测试框架（`package.json` 无 vitest/jest；`src/**` 无 `*.test.*` 文件）。P4 评审要求的 parser-level 单测必须依赖 vitest 基础设施。本计划把基础设施搭建作为 Task 12 纳入 PR2 scope。

- **增量**：`vitest` + `jsdom` devDependencies + `vitest.config.ts` + `"test": "vitest run"` script
- **范围**：仅 PR2 要求的 2 个测试文件（`useStreamResponse.test.ts` + `chatStore.test.ts`），不扩展到其他历史模块
- **未来 onboarding**：其他模块的测试补全走 followup 任务

若用户希望基础设施独立为 prereq PR，把 Task 12/13/14 从本计划拆出即可；其余 1-11 与 frontend tests 解耦。

---

## Tasks

### Task 1: DTOs + SSEEventType.SOURCES

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourceCard.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourceChunk.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourcesPayload.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/enums/SSEEventType.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/dto/SourcesPayloadTest.java`

- [ ] **Step 1.1: 创建 `SourceChunk.java`**

```java
package com.knowledgebase.ai.ragent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 文档级源卡片内的单个 chunk 片段。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceChunk {
    private String chunkId;
    private int chunkIndex;
    private String preview;
    private float score;
}
```

- [ ] **Step 1.2: 创建 `SourceCard.java`**

```java
package com.knowledgebase.ai.ragent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 文档级源卡片（一张卡 = 一个文档）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceCard {
    /** 引用编号，1..N（对应未来 LLM 输出的 [^n]）。 */
    private int index;
    private String docId;
    private String docName;
    private String kbId;
    private float topScore;
    private List<SourceChunk> chunks;
}
```

- [ ] **Step 1.3: 创建 `SourcesPayload.java`**

```java
package com.knowledgebase.ai.ragent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SSE {@code sources} 事件载荷。
 * <p>
 * {@code messageId} 在流式阶段为 {@code null}（DB id 尚未分配）；
 * 前端按 {@code streamingMessageId} 定位消息，不依赖此字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcesPayload {
    private String conversationId;
    private String messageId;
    private List<SourceCard> cards;
}
```

- [ ] **Step 1.4: 在 `SSEEventType.java` 加 `SOURCES("sources")`**

在 `SUGGESTIONS("suggestions")` 行前插入：

```java
/** 回答来源事件。 */
SOURCES("sources"),
```

（保持既有 `@RequiredArgsConstructor` + `value()` 风格不变）

- [ ] **Step 1.5: 写 Jackson round-trip 测试锁 DTO 兼容性**

`SourcesPayloadTest.java`：

```java
package com.knowledgebase.ai.ragent.rag.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourcesPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRoundTripWithMessageIdNull() throws Exception {
        SourceChunk chunk = SourceChunk.builder()
                .chunkId("c1").chunkIndex(0).preview("hi").score(0.9f).build();
        SourceCard card = SourceCard.builder()
                .index(1).docId("d1").docName("doc.pdf").kbId("kb1").topScore(0.9f)
                .chunks(List.of(chunk)).build();
        SourcesPayload original = SourcesPayload.builder()
                .conversationId("c_abc").messageId(null).cards(List.of(card)).build();

        String json = mapper.writeValueAsString(original);
        SourcesPayload restored = mapper.readValue(json, SourcesPayload.class);

        assertThat(restored).isEqualTo(original);
        assertThat(json).contains("\"messageId\":null");
    }

    @Test
    void shouldRoundTripEmptyCards() throws Exception {
        SourcesPayload original = SourcesPayload.builder()
                .conversationId("c").messageId(null).cards(List.of()).build();

        String json = mapper.writeValueAsString(original);
        SourcesPayload restored = mapper.readValue(json, SourcesPayload.class);

        assertThat(restored).isEqualTo(original);
    }
}
```

- [ ] **Step 1.6: 运行测试**

```bash
mvn -pl bootstrap test -Dtest=SourcesPayloadTest
```

预期：2 测试通过。

- [ ] **Step 1.7: 运行 spotless 格式化**

```bash
mvn spotless:apply -q
```

- [ ] **Step 1.8: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourceCard.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourceChunk.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourcesPayload.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/enums/SSEEventType.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/dto/SourcesPayloadTest.java

git commit -m "feat(sources): add SourceCard/Chunk/Payload DTOs + SSEEventType.SOURCES [PR2]"
```

---

### Task 2: SourceCardsHolder (set-once CAS 容器)

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/SourceCardsHolder.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/SourceCardsHolderTest.java`

- [ ] **Step 2.1: 写失败测试 `SourceCardsHolderTest.java`**

```java
package com.knowledgebase.ai.ragent.rag.service.handler;

import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCardsHolderTest {

    @Test
    void getShouldReturnEmptyBeforeSet() {
        SourceCardsHolder holder = new SourceCardsHolder();
        assertThat(holder.get()).isEqualTo(Optional.empty());
    }

    @Test
    void trySetShouldReturnTrueOnFirstCallAndStoreValue() {
        SourceCardsHolder holder = new SourceCardsHolder();
        List<SourceCard> cards = List.of(SourceCard.builder().index(1).docId("d1").build());

        boolean ok = holder.trySet(cards);

        assertThat(ok).isTrue();
        assertThat(holder.get()).isPresent().get().isEqualTo(cards);
    }

    @Test
    void trySetShouldReturnFalseOnSecondCallAndPreserveFirstValue() {
        SourceCardsHolder holder = new SourceCardsHolder();
        List<SourceCard> first = List.of(SourceCard.builder().index(1).docId("d1").build());
        List<SourceCard> second = List.of(SourceCard.builder().index(2).docId("d2").build());

        holder.trySet(first);
        boolean ok2 = holder.trySet(second);

        assertThat(ok2).isFalse();
        assertThat(holder.get()).isPresent().get().isEqualTo(first);
    }

    @Test
    void concurrentTrySetShouldOnlyAcceptOne() throws Exception {
        SourceCardsHolder holder = new SourceCardsHolder();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                int k = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (holder.trySet(List.of(
                                SourceCard.builder().index(k).docId("d" + k).build()))) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(holder.get()).isPresent();
    }
}
```

- [ ] **Step 2.2: 运行测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=SourceCardsHolderTest
```

预期：编译错误 "cannot find symbol: class SourceCardsHolder"

- [ ] **Step 2.3: 实现 `SourceCardsHolder.java`**

```java
package com.knowledgebase.ai.ragent.rag.service.handler;

import com.knowledgebase.ai.ragent.rag.dto.SourceCard;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回答来源卡片的 set-once CAS 容器。
 * <p>
 * 编排层在检索 + 聚合完成后 {@link #trySet(List)} 一次；LLM 异步流回调
 * （PR4 的 {@code onComplete}）通过 {@link #get()} 读取快照，避免依赖
 * ThreadLocal。
 */
public class SourceCardsHolder {

    private final AtomicReference<List<SourceCard>> ref = new AtomicReference<>();

    /**
     * 一次性设值。已设过则返回 {@code false} 且不覆盖既有值。
     */
    public boolean trySet(List<SourceCard> cards) {
        return ref.compareAndSet(null, cards);
    }

    /**
     * 未设值时返回 {@link Optional#empty()}。
     */
    public Optional<List<SourceCard>> get() {
        return Optional.ofNullable(ref.get());
    }
}
```

- [ ] **Step 2.4: 运行测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=SourceCardsHolderTest
```

预期：Tests run: 4, Failures: 0, Errors: 0

- [ ] **Step 2.5: Spotless + 提交**

```bash
mvn spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/SourceCardsHolder.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/SourceCardsHolderTest.java
git commit -m "feat(sources): add SourceCardsHolder set-once CAS container [PR2]"
```

---

### Task 3: RagSourcesProperties + application.yaml

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagSourcesProperties.java`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 3.1: 创建 `RagSourcesProperties.java`**

沿用项目主流模式 `@Configuration + @ConfigurationProperties`（参考 `GuidanceProperties` / `MemoryProperties` 等）。与签字的 spec § 2 一致。

```java
package com.knowledgebase.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 回答来源功能配置。
 * <p>
 * 默认 {@code enabled=false}，PR2~PR4 合并期间保持关闭，PR5 转为 true。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.sources")
public class RagSourcesProperties {

    /** 功能总开关（默认关闭）。 */
    private Boolean enabled = false;

    /** Chunk preview 截断长度（按 codePoint 计算）。 */
    private Integer previewMaxChars = 200;

    /** SourceCard 列表条数上限。 */
    private Integer maxCards = 8;
}
```

- [ ] **Step 3.2: 在 `application.yaml` 的 `rag:` 节点下加 `sources` 配置块**

定位到 `application.yaml` 中 `rag:` 下现有的 `query-rewrite:` / `suggestions:` 等块附近，追加：

```yaml
rag:
  # ...existing config
  sources:
    enabled: false
    preview-max-chars: 200
    max-cards: 8
```

- [ ] **Step 3.3: 编译 + 启动时校验属性绑定**

```bash
mvn -pl bootstrap compile -q
```

预期：无编译错误。

- [ ] **Step 3.4: Spotless + 提交**

```bash
mvn spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagSourcesProperties.java \
        bootstrap/src/main/resources/application.yaml
git commit -m "feat(sources): add RagSourcesProperties + application.yaml config [PR2]"
```

---

### Task 4: SourceCardBuilder

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/SourceCardBuilder.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/source/SourceCardBuilderTest.java`

- [ ] **Step 4.1: 写失败测试 `SourceCardBuilderTest.java`**

```java
package com.knowledgebase.ai.ragent.rag.core.source;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import com.knowledgebase.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SourceCardBuilderTest {

    private KnowledgeDocumentService documentService;
    private SourceCardBuilder builder;

    @BeforeEach
    void setUp() {
        documentService = mock(KnowledgeDocumentService.class);
        builder = new SourceCardBuilder(documentService);
    }

    private static RetrievedChunk chunk(String id, String docId, Integer idx, float score, String text) {
        return RetrievedChunk.builder()
                .id(id).docId(docId).chunkIndex(idx).score(score).text(text).build();
    }

    @Test
    void shouldReturnEmptyListOnEmptyInput() {
        List<SourceCard> cards = builder.build(List.of(), 8, 200);

        assertThat(cards).isEmpty();
        verifyNoInteractions(documentService);
    }

    @Test
    void shouldAggregateByDocIdSortByTopScoreDesc() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d1", 0, 0.5f, "hello A"),
                chunk("c2", "d2", 1, 0.9f, "hello B"),
                chunk("c3", "d1", 2, 0.7f, "hello C")
        );
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "Doc One", "kb1"),
                new DocumentMetaSnapshot("d2", "Doc Two", "kb2")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).getDocId()).isEqualTo("d2");
        assertThat(cards.get(0).getIndex()).isEqualTo(1);
        assertThat(cards.get(0).getTopScore()).isEqualTo(0.9f);
        assertThat(cards.get(1).getDocId()).isEqualTo("d1");
        assertThat(cards.get(1).getIndex()).isEqualTo(2);
        assertThat(cards.get(1).getTopScore()).isEqualTo(0.7f);
    }

    @Test
    void shouldSortChunksWithinCardByChunkIndexAsc() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d1", 5, 0.7f, "five"),
                chunk("c2", "d1", 1, 0.5f, "one"),
                chunk("c3", "d1", 3, 0.9f, "three")
        );
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getChunks()).extracting("chunkIndex")
                .containsExactly(1, 3, 5);
    }

    @Test
    void shouldDropChunksWithNullDocId() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", null, 0, 0.5f, "null docid"),
                chunk("c2", "d1", 0, 0.8f, "valid"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getDocId()).isEqualTo("d1");
    }

    @Test
    void shouldFilterCardsWhoseDocIdMissingFromMetaQuery() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d_unknown", 0, 0.9f, "ghost"),
                chunk("c2", "d1", 0, 0.5f, "real"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getDocId()).isEqualTo("d1");
    }

    @Test
    void shouldReturnEmptyWhenAllDocIdsMissFromMetaQuery() {
        List<RetrievedChunk> chunks = List.of(chunk("c1", "d_ghost", 0, 0.9f, "ghost"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of());

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).isEmpty();
    }

    @Test
    void shouldTruncatePreviewByCodePoints() {
        String longText = "中文内容".repeat(100);
        List<RetrievedChunk> chunks = List.of(chunk("c1", "d1", 0, 0.9f, longText));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 10);

        String preview = cards.get(0).getChunks().get(0).getPreview();
        assertThat(preview.codePointCount(0, preview.length())).isLessThanOrEqualTo(10);
    }

    @Test
    void shouldClipToMaxCards() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d1", 0, 0.9f, "a"),
                chunk("c2", "d2", 0, 0.8f, "b"),
                chunk("c3", "d3", 0, 0.7f, "c"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D1", "kb1"),
                new DocumentMetaSnapshot("d2", "D2", "kb1"),
                new DocumentMetaSnapshot("d3", "D3", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 2, 200);

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting("docId").containsExactly("d1", "d2");
    }
}
```

- [ ] **Step 4.2: 运行测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=SourceCardBuilderTest
```

预期：编译错误 "cannot find symbol: class SourceCardBuilder"

- [ ] **Step 4.3: 实现 `SourceCardBuilder.java`**

```java
package com.knowledgebase.ai.ragent.rag.core.source;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import com.knowledgebase.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import com.knowledgebase.ai.ragent.rag.dto.SourceChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 把检索到的 {@link RetrievedChunk} 聚合成文档级 {@link SourceCard} 列表。
 * <p>
 * 纯聚合器：不理解业务分支（feature flag / 推不推判定 / SSE / 落库 / kbName
 * 查询）。边界判定由 {@code RAGChatServiceImpl} 的三层闸门控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceCardBuilder {

    private final KnowledgeDocumentService documentService;

    /**
     * 聚合 chunks 成文档级卡片。
     *
     * @param chunks           已去重的 chunk 列表（由编排层保证）
     * @param maxCards         卡片数上限（来自 {@code rag.sources.max-cards}）
     * @param previewMaxChars  preview 文本的 codePoint 截断长度
     * @return 按 {@code topScore} 降序的 cards 列表（空列表合法）
     */
    public List<SourceCard> build(List<RetrievedChunk> chunks, int maxCards, int previewMaxChars) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        // 1) 过滤 docId == null 的 chunk，同时按 docId 分组（保留插入序）
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk c : chunks) {
            if (c.getDocId() == null) {
                log.warn("SourceCardBuilder: chunk id={} 无 docId，已丢弃", c.getId());
                continue;
            }
            grouped.computeIfAbsent(c.getDocId(), k -> new java.util.ArrayList<>()).add(c);
        }
        if (grouped.isEmpty()) {
            return List.of();
        }

        // 2) 批量查 meta
        Set<String> docIds = grouped.keySet();
        Map<String, DocumentMetaSnapshot> metaById = toMetaMap(documentService.findMetaByIds(docIds));

        // 3) 按 docId 生成卡片（过滤 meta 查不到的）
        AtomicInteger tmpIndex = new AtomicInteger(0);
        List<SourceCard> cards = grouped.entrySet().stream()
                .filter(e -> metaById.containsKey(e.getKey()))
                .map(e -> buildCard(e.getKey(), e.getValue(), metaById.get(e.getKey()), previewMaxChars))
                .sorted(Comparator.comparingDouble((SourceCard c) -> c.getTopScore()).reversed())
                .limit(maxCards)
                .toList();

        // 4) 分配最终 index（降序后 1..N）
        List<SourceCard> numbered = new java.util.ArrayList<>(cards.size());
        int i = 1;
        for (SourceCard c : cards) {
            numbered.add(SourceCard.builder()
                    .index(i++)
                    .docId(c.getDocId())
                    .docName(c.getDocName())
                    .kbId(c.getKbId())
                    .topScore(c.getTopScore())
                    .chunks(c.getChunks())
                    .build());
        }
        return numbered;
    }

    private Map<String, DocumentMetaSnapshot> toMetaMap(List<DocumentMetaSnapshot> snapshots) {
        return snapshots.stream().collect(Collectors.toMap(DocumentMetaSnapshot::docId, s -> s, (a, b) -> a));
    }

    private SourceCard buildCard(String docId, List<RetrievedChunk> chunksInDoc,
                                 DocumentMetaSnapshot meta, int previewMaxChars) {
        List<SourceChunk> sortedChunks = chunksInDoc.stream()
                .sorted(Comparator.comparing(
                        RetrievedChunk::getChunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(rc -> SourceChunk.builder()
                        .chunkId(rc.getId())
                        .chunkIndex(rc.getChunkIndex() == null ? -1 : rc.getChunkIndex())
                        .preview(truncateByCodePoint(rc.getText(), previewMaxChars))
                        .score(rc.getScore() == null ? 0f : rc.getScore().floatValue())
                        .build())
                .toList();

        float topScore = (float) chunksInDoc.stream()
                .map(RetrievedChunk::getScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

        return SourceCard.builder()
                .index(0)
                .docId(docId)
                .docName(meta.docName())
                .kbId(meta.kbId())
                .topScore(topScore)
                .chunks(sortedChunks)
                .build();
    }

    private static String truncateByCodePoint(String text, int max) {
        if (text == null) return "";
        if (text.codePointCount(0, text.length()) <= max) return text;
        int[] cps = text.codePoints().limit(max).toArray();
        return new String(cps, 0, cps.length);
    }
}
```

**注意：** `RetrievedChunk.getScore()` 的返回类型是 `Double`（非 primitive float）。`SourceChunk.score` 是 `float`。测试里用 `0.9f` 等构造 chunk 时 `RetrievedChunk.score` 是 `Double`，代码里正确转换为 `float`。

- [ ] **Step 4.4: 运行测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=SourceCardBuilderTest
```

预期：Tests run: 8, Failures: 0, Errors: 0

若因 `RetrievedChunk.getScore()` 类型不匹配失败，检查 `framework/convention/RetrievedChunk.java` 里 score 字段类型并调整 `SourceCardBuilderTest` 里 `chunk()` helper 的入参类型（`Double score` 而非 `float score`）。

- [ ] **Step 4.5: Spotless + 提交**

```bash
mvn spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/SourceCardBuilder.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/source/SourceCardBuilderTest.java
git commit -m "feat(sources): add SourceCardBuilder aggregator [PR2]"
```

---

### Task 5: StreamChatHandlerParams 加 cardsHolder 字段

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java`

- [ ] **Step 5.1: 加 cardsHolder 字段（`@Builder.Default` + `@NonNull`）**

在 `StreamChatHandlerParams.java` 的 `private final RAGConfigProperties ragConfigProperties;` 之后追加：

```java
    /**
     * 回答来源 cards 的 set-once 容器。
     * <p>
     * 使用 {@code @Builder.Default} 保证任何 {@code builder().build()} 调用
     * 都拿到非 null 的空壳实例；{@code @NonNull} 让显式传 null 在构建期 NPE。
     */
    @lombok.Builder.Default
    @lombok.NonNull
    private final SourceCardsHolder cardsHolder = new SourceCardsHolder();
```

（`@lombok.Builder.Default` 用全限定是为避免和文件顶部 `import lombok.Builder;` 的潜在别名冲突；`@NonNull` 要求 `import lombok.NonNull;`）

导入（文件顶部）：

```java
import lombok.Builder;   // 已存在
import lombok.NonNull;   // 新增（如文件已有则跳过）
```

- [ ] **Step 5.2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

预期：无编译错误。`StreamCallbackFactory.createChatEventHandler` 现有 `.builder()...build()` 调用不受影响（默认值兜底生效）。

- [ ] **Step 5.3: Spotless + 提交**

```bash
mvn spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java
git commit -m "feat(sources): add cardsHolder field to StreamChatHandlerParams [PR2]"
```

---

### Task 6: StreamChatEventHandler 加 trySetCards + emitSources 方法

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`

- [ ] **Step 6.1: 构造器解包 cardsHolder + 加 field**

在 `StreamChatEventHandler.java` 的 field 声明区（`private volatile SuggestionContext suggestionContext = SuggestionContext.skip();` 之前）加：

```java
    private final SourceCardsHolder cardsHolder;
```

在构造器（`public StreamChatEventHandler(StreamChatHandlerParams params)`）体内 `this.ragConfigProperties = params.getRagConfigProperties();` 之后加：

```java
        this.cardsHolder = params.getCardsHolder();
```

- [ ] **Step 6.2: 加 `trySetCards` + `emitSources` 公开方法**

在类的合适位置（例如 `public void onComplete()` 之前）追加：

```java
    /**
     * 一次性存入 cards。委托给 {@link SourceCardsHolder#trySet(List)}。
     * <p>
     * 返回值用于调用方防御（理论上 orchestrator 主路径仅调一次，始终返回 true）。
     */
    public boolean trySetCards(java.util.List<com.knowledgebase.ai.ragent.rag.dto.SourceCard> cards) {
        return cardsHolder.trySet(cards);
    }

    /**
     * 机械发射 SSE {@code sources} 事件。异常语义沿用 {@link SseEmitterSender#sendEvent}，
     * 不做额外吞错。
     */
    public void emitSources(com.knowledgebase.ai.ragent.rag.dto.SourcesPayload payload) {
        sender.sendEvent(SSEEventType.SOURCES.value(), payload);
    }
```

（如果觉得 FQCN 不美观，也可以在文件顶部加 `import` 并去掉全限定；此处为不扩大 import 面）

- [ ] **Step 6.3: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

- [ ] **Step 6.4: Spotless + 提交**

```bash
mvn spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java
git commit -m "feat(sources): expose trySetCards + emitSources on handler [PR2]"
```

---

### Task 7: RAGChatServiceImpl 编排（3 闸门 + emit）+ 单测

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`

- [ ] **Step 7.1: 注入 `SourceCardBuilder` + `RagSourcesProperties`**

在 `RAGChatServiceImpl.java` 的 `@RequiredArgsConstructor` 字段区末尾（`ConversationMapper conversationMapper;` 之后）加：

```java
    private final com.knowledgebase.ai.ragent.rag.core.source.SourceCardBuilder sourceCardBuilder;
    private final com.knowledgebase.ai.ragent.rag.config.RagSourcesProperties ragSourcesProperties;
```

（或顶部 import 后去掉 FQCN）

- [ ] **Step 7.2: 在主链路插入 3 闸门逻辑**

定位到 `distinctChunks` 构造（L190-195 区域，`.distinct().toList();` 之后）。在 `List<RetrievedChunk> topChunks = ...` 计算之后、`callback.updateSuggestionContext(...)` 之前插入：

```java
        // ---- 回答来源事件推送（3 层闸门）----
        // 闸门 1：feature flag（继续回答，仅跳过 sources）
        // 闸门 2：distinctChunks.isEmpty()（MCP-Only / Mixed 但 KB 零命中）
        // 闸门 3：cards.isEmpty()（findMetaByIds 全部过滤）
        if (Boolean.TRUE.equals(ragSourcesProperties.getEnabled()) && !distinctChunks.isEmpty()) {
            List<SourceCard> cards = sourceCardBuilder.build(
                    distinctChunks,
                    ragSourcesProperties.getMaxCards(),
                    ragSourcesProperties.getPreviewMaxChars());
            if (!cards.isEmpty() && callback.trySetCards(cards)) {
                callback.emitSources(SourcesPayload.builder()
                        .conversationId(actualConversationId)
                        .messageId(null)
                        .cards(cards)
                        .build());
            }
        }
```

顶部新增 imports：

```java
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import com.knowledgebase.ai.ragent.rag.dto.SourcesPayload;
```

**注意**：闸门 1 早返回分支（`guidanceDecision.isPrompt()` / `allSystemOnly` / `ctx.isEmpty()`）**不要**加 sources 发射代码——它们在 `distinctChunks` 计算之前就 `return` 了，天然不会走到 sources 逻辑。

- [ ] **Step 7.3: 写 `RAGChatServiceImplSourcesTest.java`（真实 service + @InjectMocks + InOrder）**

**关键设计**：此测试**驱动真实的 `RAGChatServiceImpl.streamChat(...)` 方法**，用 `@InjectMocks` 组装 14 个依赖的 mock，通过 `Mockito.inOrder(...)` 锁住 `retrieve → build → emit → streamChat` 的相对调用顺序。这样：
- 代码放错位置（如放到 `ctx.isEmpty()` 之前、放到 `llmService.streamChat(...)` 之后）测试都会挂
- 闸门判定错误（如把 `distinctChunks.isEmpty()` 写成 `ctx.isEmpty()`）的场景测试都会挂

```java
package com.knowledgebase.ai.ragent.rag.service.impl;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.chat.LLMService;
import com.knowledgebase.ai.ragent.infra.chat.StreamCancellationHandle;
import com.knowledgebase.ai.ragent.rag.config.RagSourcesProperties;
import com.knowledgebase.ai.ragent.rag.config.RAGConfigProperties;
import com.knowledgebase.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.knowledgebase.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentResolver;
import com.knowledgebase.ai.ragent.rag.core.intent.SubQuestionIntent;
import com.knowledgebase.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.prompt.RAGPromptService;
import com.knowledgebase.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalContext;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.knowledgebase.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.knowledgebase.ai.ragent.rag.core.rewrite.RewriteResult;
import com.knowledgebase.ai.ragent.rag.core.source.SourceCardBuilder;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import com.knowledgebase.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.knowledgebase.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.knowledgebase.ai.ragent.rag.service.handler.StreamChatEventHandler;
import com.knowledgebase.ai.ragent.rag.service.handler.StreamTaskManager;
import com.knowledgebase.ai.ragent.user.access.AccessScope;
import com.knowledgebase.ai.ragent.user.access.KbAccessService;
import com.knowledgebase.ai.ragent.user.access.KbReadAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驱动真实的 {@link RAGChatServiceImpl#streamChat} 方法，验证：
 * <ol>
 *   <li>sources 发射点在 {@code retrieve} 之后、{@code llmService.streamChat} 之前</li>
 *   <li>三层闸门下 builder/emit 的调用次数符合预期</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RAGChatServiceImplSourcesTest {

    @Mock LLMService llmService;
    @Mock RAGPromptService promptBuilder;
    @Mock PromptTemplateLoader promptTemplateLoader;
    @Mock ConversationMemoryService memoryService;
    @Mock StreamTaskManager taskManager;
    @Mock IntentGuidanceService guidanceService;
    @Mock StreamCallbackFactory callbackFactory;
    @Mock QueryRewriteService queryRewriteService;
    @Mock IntentResolver intentResolver;
    @Mock RetrievalEngine retrievalEngine;
    @Mock KbAccessService kbAccessService;
    @Mock KbReadAccessPort kbReadAccess;
    @Mock ConversationMapper conversationMapper;
    @Mock SourceCardBuilder sourceCardBuilder;
    @Mock StreamChatEventHandler callback;

    RagSourcesProperties props;

    @InjectMocks
    RAGChatServiceImpl service;

    @BeforeEach
    void setUp() {
        // 用 lenient() 避免 MockitoExtension 的 strict stubbing 对"只在部分测试里用"的 stub 报错
        lenient().when(kbReadAccess.getAccessScope(any(), any())).thenReturn(AccessScope.empty());

        lenient().when(queryRewriteService.rewriteWithSplit(any(), any()))
                .thenReturn(new RewriteResult("q", "q", List.of("q")));
        lenient().when(intentResolver.resolve(any())).thenReturn(List.<SubQuestionIntent>of());
        lenient().when(intentResolver.isSystemOnly(any())).thenReturn(false);

        lenient().when(guidanceService.detectAmbiguity(any(), any()))
                .thenReturn(GuidanceDecision.pass());

        lenient().when(callbackFactory.createChatEventHandler(any(), any(), any())).thenReturn(callback);

        // 默认给回空 history，避免 NPE
        lenient().when(memoryService.loadAndAppend(any(), any(), any(), any())).thenReturn(List.of());

        // 默认流式 LLM 返回一个可注册的 handle
        lenient().when(llmService.streamChat(any(), any()))
                .thenReturn(mock(StreamCancellationHandle.class));

        // 配置
        props = new RagSourcesProperties();
        props.setEnabled(true);
        props.setMaxCards(8);
        props.setPreviewMaxChars(200);
        // 通过反射 / 构造器注入 props —— @InjectMocks 已处理 @Mock 字段，
        // 这里手动把 props 赋给 service 的 ragSourcesProperties 字段（同样手法处理其他
        // 非 @Mock 字段；实施时可用 ReflectionTestUtils.setField）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "ragSourcesProperties", props);
    }

    private RetrievalContext ctxWithKbChunks(List<RetrievedChunk> chunks) {
        RetrievalContext mock = mock(RetrievalContext.class);
        when(mock.isEmpty()).thenReturn(chunks.isEmpty());
        when(mock.getIntentChunks()).thenReturn(Map.of("i1", chunks));
        when(mock.getMcpContext()).thenReturn("");
        when(mock.getKbContext()).thenReturn("");
        when(mock.hasMcp()).thenReturn(false);
        return mock;
    }

    @Test
    void happyPath_emitSourcesBetweenRetrieveAndStreamChat() {
        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").docId("d1").chunkIndex(0).score(0.9).text("hi").build());
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctxWithKbChunks(chunks));
        List<SourceCard> cards = List.of(SourceCard.builder().index(1).docId("d1").build());
        when(sourceCardBuilder.build(any(), anyInt(), anyInt())).thenReturn(cards);
        when(callback.trySetCards(cards)).thenReturn(true);

        service.streamChat("q", null, null, false, mock(SseEmitter.class));

        InOrder order = inOrder(retrievalEngine, sourceCardBuilder, callback, llmService);
        order.verify(retrievalEngine).retrieve(any(), anyInt(), any(), any());
        order.verify(sourceCardBuilder).build(any(), anyInt(), anyInt());
        order.verify(callback).emitSources(any());
        order.verify(llmService).streamChat(any(), any());
    }

    @Test
    void flagOff_shouldNotCallBuilder_butShouldStillStartLlmStream() {
        props.setEnabled(false);
        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").docId("d1").chunkIndex(0).score(0.9).text("hi").build());
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctxWithKbChunks(chunks));

        service.streamChat("q", null, null, false, mock(SseEmitter.class));

        verify(sourceCardBuilder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
        verify(llmService, times(1)).streamChat(any(), any());  // 回答照常走
    }

    @Test
    void emptyDistinctChunks_mcpOnlyScenario_shouldNotCallBuilder_butLlmStillStarts() {
        // ctx 非空（有 MCP），但 intentChunks 为空 → distinctChunks.isEmpty()
        RetrievalContext ctx = mock(RetrievalContext.class);
        when(ctx.isEmpty()).thenReturn(false);
        when(ctx.getIntentChunks()).thenReturn(Map.of());
        when(ctx.hasMcp()).thenReturn(true);
        when(ctx.getMcpContext()).thenReturn("mcp-ctx");
        when(ctx.getKbContext()).thenReturn("");
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        service.streamChat("q", null, null, false, mock(SseEmitter.class));

        verify(sourceCardBuilder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
        verify(llmService, times(1)).streamChat(any(), any());  // MCP-Only 回答路径照常
    }

    @Test
    void emptyCards_shouldNotEmit_butLlmStillStarts() {
        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").docId("d1").chunkIndex(0).score(0.9).text("hi").build());
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctxWithKbChunks(chunks));
        when(sourceCardBuilder.build(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.streamChat("q", null, null, false, mock(SseEmitter.class));

        verify(sourceCardBuilder, times(1)).build(any(), anyInt(), anyInt());
        verify(callback, never()).trySetCards(any());
        verify(callback, never()).emitSources(any());
        verify(llmService, times(1)).streamChat(any(), any());
    }

    @Test
    void ctxIsEmpty_shouldNotCallBuilder_andShouldNotStartLlm() {
        // ctx.isEmpty() 是真早返回分支
        RetrievalContext ctx = mock(RetrievalContext.class);
        when(ctx.isEmpty()).thenReturn(true);
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        service.streamChat("q", null, null, false, mock(SseEmitter.class));

        verify(sourceCardBuilder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
        verify(llmService, never()).streamChat(any(), any());  // 真早返回，LLM 不启动
    }
}
```

**实施期备注**：
1. `GuidanceDecision.pass()` 是假定存在的静态工厂。若实际类型不同（例如需 `.notPrompt()` 语义），实施时按实际 API 替换
2. `RewriteResult` / `SubQuestionIntent` 的构造签名可能与上文不同；编译错误时按实际签名调整
3. `@InjectMocks` 无法注入非 `@Mock` 的 `RagSourcesProperties`（它是一个 POJO，需要实例），因此通过 `ReflectionTestUtils.setField(...)` 手动赋值；如果项目已有更优雅的注入模式（如 `@Spy`），实施期可替换

- [ ] **Step 7.4: 运行测试**

```bash
mvn -pl bootstrap test -Dtest=RAGChatServiceImplSourcesTest
```

预期：Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 7.5: 全量编译确认无回归**

```bash
mvn -pl bootstrap compile -q
```

- [ ] **Step 7.6: Spotless + 提交**

```bash
mvn spotless:apply -q
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java
git commit -m "feat(sources): RAGChatServiceImpl 3-gate orchestration + emit [PR2]"
```

---

### Task 8: （可选）后端 @SpringBootTest 真实 SSE wire 格式验证

**Files:**
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/controller/RAGChatControllerSourcesIntegrationTest.java`

**说明**：**Task 7 已通过真实 service + InOrder 锁住了 `retrieve → build → emit → streamChat` 的相对顺序**，这对 SSE 帧序的实际保障已经足够（`sender.sendEvent` 是同步调用，在单一 SseEmitter 上必然按调用顺序序列化）。

本任务是**可选加强**：若项目已有 `@SpringBootTest` + `MockMvc` 的 SSE 断言基类可复用，再加一层真实 wire 格式验证；**否则直接跳过并在 PR 描述里记录**"SSE 顺序由 Task 7 的 InOrder 断言保证"。

- [ ] **Step 8.1: 先确认项目是否有可复用的 @SpringBootTest 基类**

```bash
grep -rn "@SpringBootTest" bootstrap/src/test/java/ | head
grep -rn "SseEmitter\|text/event-stream" bootstrap/src/test/java/ | head
```

若命中零条或不含 SSE 断言模式 → **跳过 Task 8，直接进 Task 9**。

- [ ] **Step 8.2: 若有基类可复用，再写 wire 格式测试**

（此处按项目实际基类抽象填充；无现成基类则不写）

- [ ] **Step 8.3: 提交（仅当 8.2 写了代码）**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/controller/RAGChatControllerSourcesIntegrationTest.java
git commit -m "test(sources): @SpringBootTest SSE wire format integration test [PR2]"
```

**执行期决策**：Task 8 的存在与否不影响 PR2 交付的完整性；若跳过，在 PR 描述 "Test plan" 部分明确写 "SSE 顺序由 Task 7 的 Mockito InOrder 断言保证；未加 @SpringBootTest 层，因项目无现成 SSE 测试基类"。

---

### Task 9: Frontend TS 类型 + Message.sources

**Files:**
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 9.1: 定位 `types/index.ts` 中 `SuggestionsPayload` 定义附近**

搜索 `SuggestionsPayload` 找到现有 payload 类型的定义位置。

- [ ] **Step 9.2: 在 `SuggestionsPayload` 附近追加 3 个新类型**

```typescript
export interface SourceChunk {
  chunkId: string;
  chunkIndex: number;
  preview: string;
  score: number;
}

export interface SourceCard {
  /** 引用编号，1..N（对应未来 LLM 输出的 [^n]）。 */
  index: number;
  docId: string;
  docName: string;
  kbId: string;
  topScore: number;
  chunks: SourceChunk[];
}

export interface SourcesPayload {
  conversationId: string;
  /** 流式阶段为 null；前端按 streamingMessageId 定位消息，不依赖此字段。 */
  messageId: string | null;
  cards: SourceCard[];
}
```

- [ ] **Step 9.3: 给 `Message` 接口加 `sources?: SourceCard[]`**

搜索 `export interface Message {` 找到定义，在 `suggestedQuestions?: string[];` 附近追加：

```typescript
  sources?: SourceCard[];
```

- [ ] **Step 9.4: TypeScript 编译验证**

```bash
cd frontend && npx tsc --noEmit
```

预期：无编译错误（现有代码因可选字段向下兼容）。

- [ ] **Step 9.5: 提交**

```bash
git add frontend/src/types/index.ts
git commit -m "feat(sources): add SourceCard/Chunk/Payload TS types + Message.sources [PR2]"
```

---

### Task 10: Frontend `useStreamResponse` — 加 `case "sources"`

**Files:**
- Modify: `frontend/src/hooks/useStreamResponse.ts`

- [ ] **Step 10.1: 更新 imports**

文件顶部 import 行改为：

```typescript
import type {
  CompletionPayload,
  MessageDeltaPayload,
  SourcesPayload,
  StreamMetaPayload,
  SuggestionsPayload
} from "@/types";
```

- [ ] **Step 10.2: 在 `StreamHandlers` 接口加 `onSources`**

在 `StreamHandlers` 接口内 `onSuggestions?:` 之后加：

```typescript
  onSources?: (payload: SourcesPayload) => void;
```

- [ ] **Step 10.3: 在 `dispatchEvent` 的 switch 里加 `case "sources"`**

在 `case "suggestions":` 之前（保持事件顺序对应）插入：

```typescript
      case "sources":
        handlers.onSources?.(payload as SourcesPayload);
        break;
```

- [ ] **Step 10.4: TypeScript 编译验证**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 10.5: 提交**

```bash
git add frontend/src/hooks/useStreamResponse.ts
git commit -m "feat(sources): wire sources SSE event in useStreamResponse [PR2]"
```

---

### Task 11: Frontend `chatStore` — 提取 `createStreamHandlers` 工厂 + 加 `onSources`

**设计决策**：当前 `chatStore.ts` 的 `handlers = {...}` 在 `sendMessage` 闭包内构造（L299-L465），测试无法拿到引用。本任务把 handlers 对象**提取**为模块级 `export function createStreamHandlers(...)`，行为等价不变。这是 PR2 能够写出 P4 评审要求的 store 单测的前提。

**Files:**
- Modify: `frontend/src/stores/chatStore.ts`

- [ ] **Step 11.1: 更新 imports**

顶部 type imports 追加 `SourcesPayload`（如果现有 import 还没有）：

```typescript
import type {
  CompletionPayload,
  MessageDeltaPayload,
  SourcesPayload,
  StreamMetaPayload,
  SuggestionsPayload
} from "@/types";
```

同时保证 `createStreamResponse` 的类型 `StreamHandlers` 能 import 进来。若 `useStreamResponse.ts` 未 export `StreamHandlers`，先加 `export` 关键字：

`frontend/src/hooks/useStreamResponse.ts` 顶部：

```typescript
export interface StreamHandlers { /* ... 原有字段不变 */ }
```

然后在 `chatStore.ts`：

```typescript
import { createStreamResponse, type StreamHandlers } from "@/hooks/useStreamResponse";
```

- [ ] **Step 11.2: 提取 `createStreamHandlers` 工厂（含 `onSources`）**

定位 `chatStore.ts` 中 `sendMessage` 函数体里 `const handlers = { ... }`（约 L299-L465）。把整个 handlers 对象移到模块顶层，包裹为可导出的工厂函数。工厂签名需要闭包内用到的所有外部引用——`get` / `set` / `assistantId` / 以及 `stopTask`（来自 `@/services/taskService` 或类似）。

**操作步骤**：

1. 先看 handlers 对象体里用到的外部变量有哪些（搜 `get(` / `set(` / `assistantId` / `stopTask`）
2. 把它们全列入工厂参数
3. 新建一个 `createStreamHandlers` 函数并 `export`，位置在 `useChatStore` 定义之前
4. 把 handlers 对象体整体复制进工厂（保持每个 handler 的行为不变）
5. 在原 `sendMessage` 内把 `const handlers = { ... }` 替换为 `const handlers = createStreamHandlers(get, set, assistantId, stopTask);`
6. 在 handlers 对象里**新增** `onSources`

建议的工厂长这样（实施时把 TODO 替换为实际闭包捕获的参数列表）：

```typescript
type GetFn = typeof useChatStore.getState;
type SetFn = typeof useChatStore.setState;

export function createStreamHandlers(
  get: GetFn,
  set: SetFn,
  assistantId: string,
  stopTask: (taskId: string) => Promise<unknown>
): StreamHandlers {
  return {
    onMeta: (payload) => {
      // [原 onMeta 代码体，完全照搬]
    },
    onMessage: (payload) => {
      // [原 onMessage 代码体]
    },
    onThinking: (payload) => {
      // [原 onThinking 代码体]
    },
    onReject: (payload) => {
      // [原 onReject 代码体]
    },
    onFinish: (payload) => {
      // [原 onFinish 代码体]
    },
    onSuggestions: (payload) => {
      // [原 onSuggestions 代码体]
    },
    onSources: (payload: SourcesPayload) => {
      if (get().streamingMessageId !== assistantId) return;
      if (!payload || !Array.isArray(payload.cards)) return;
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === state.streamingMessageId
            ? { ...message, sources: payload.cards }
            : message
        )
      }));
    },
    onCancel: (payload) => {
      // [原 onCancel 代码体]
    },
    onTitle: (payload) => {
      // [原 onTitle 代码体（如果存在）]
    },
    onError: (error) => {
      // [原 onError 代码体]
    }
  };
}
```

`onSources` 的关键约束：
- Guard 先于所有 set：`streamingMessageId !== assistantId` 时直接 return
- 更新目标用 `state.streamingMessageId`，**不**用 `payload.messageId`（流式阶段为 null）
- payload 形状 guard：`!Array.isArray(payload.cards)` 丢弃异常载荷

- [ ] **Step 11.3: 在 `sendMessage` 里改为调用工厂**

原 L299 附近：

```typescript
const handlers = { /* 大段对象 */ };
```

改为：

```typescript
const handlers = createStreamHandlers(get, set, assistantId, stopTask);
```

（若 `stopTask` 不在 chatStore 内直接可见、是闭包外 import 的函数，则保留原 import 即可；它是静态引用，传进工厂即可）

- [ ] **Step 11.4: TypeScript + ESLint 验证行为等价**

```bash
cd frontend && npx tsc --noEmit && npm run lint
```

预期：无错误；行为等价（所有 handler 函数体逐字搬运，仅作用域从闭包换成显式参数）。

- [ ] **Step 11.5: 提交**

```bash
git add frontend/src/stores/chatStore.ts frontend/src/hooks/useStreamResponse.ts
git commit -m "feat(sources): extract createStreamHandlers factory + add onSources [PR2]"
```

---

### Task 12: Frontend Vitest 基础设施

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/vitest.config.ts`

- [ ] **Step 12.1: 安装 Vitest + jsdom**

```bash
cd frontend && npm install -D vitest@^2.0.0 jsdom@^25.0.0
```

（版本号按当前 npm 最新稳定；若 npm lookup 失败降级为 `vitest@latest`）

- [ ] **Step 12.2: 加 `vitest.config.ts`**

```typescript
import { defineConfig } from "vitest/config";
import path from "node:path";

export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src")
    }
  },
  test: {
    globals: true,
    environment: "jsdom",
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    exclude: ["node_modules", "dist"]
  }
});
```

- [ ] **Step 12.3: 加 `test` script 到 `package.json`**

在 `"scripts"` 内加：

```json
    "test": "vitest run",
    "test:watch": "vitest"
```

- [ ] **Step 12.4: 冒烟验证 vitest 能跑（`--passWithNoTests` 允许零测试文件通过）**

临时改 `package.json` 的 test script 为：

```json
    "test": "vitest run --passWithNoTests",
    "test:watch": "vitest"
```

然后：

```bash
cd frontend && npm run test
```

预期：`No test files found` 但 exit code 为 0（`--passWithNoTests` 作用）。Task 13 写入第一个测试后会实际执行。

- [ ] **Step 12.5: 提交**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vitest.config.ts
git commit -m "chore(frontend): bootstrap vitest + jsdom for unit tests [PR2]"
```

---

### Task 13: Frontend `useStreamResponse` 单测（parser-level）

**Files:**
- Test: `frontend/src/hooks/useStreamResponse.test.ts`

- [ ] **Step 13.1: 写 parser-level 路由测试**

```typescript
import { describe, expect, it, vi } from "vitest";
import { createStreamResponse, type StreamHandlers } from "./useStreamResponse";
import type { SourcesPayload } from "@/types";

/**
 * 给定一串 SSE 帧构造的 Response body，断言 useStreamResponse 内部的
 * dispatchEvent 能把 "event: sources" 的帧正确路由到 handlers.onSources。
 * 这是 P4 评审要求的 parser-level 回归保护：若有人改 chatStore 但忘了在
 * useStreamResponse 里加 case "sources"，本测试失败。
 */
function buildSseResponse(framesText: string): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(framesText));
      controller.close();
    }
  });
  return new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

describe("useStreamResponse SSE routing", () => {
  it("routes event: sources to handlers.onSources with parsed payload", async () => {
    const payload: SourcesPayload = {
      conversationId: "c_abc",
      messageId: null,
      cards: [{
        index: 1, docId: "d1", docName: "doc.pdf", kbId: "kb1",
        topScore: 0.9, chunks: [{ chunkId: "c1", chunkIndex: 0, preview: "hi", score: 0.9 }]
      }]
    };
    const frame = `event: sources\ndata: ${JSON.stringify(payload)}\n\n`;
    const onSources = vi.fn();
    const handlers: StreamHandlers = { onSources };

    // Hook directly into readSseStream via fetch mock
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue(buildSseResponse(frame));
    try {
      const r = createStreamResponse({ url: "/x" }, handlers);
      await r.start();
    } finally {
      globalThis.fetch = originalFetch;
    }

    expect(onSources).toHaveBeenCalledTimes(1);
    expect(onSources).toHaveBeenCalledWith(payload);
  });
});
```

- [ ] **Step 13.2: 运行测试**

```bash
cd frontend && npx vitest run src/hooks/useStreamResponse.test.ts
```

预期：1 测试通过。

若 `createStreamResponse` 未导出，先在 `useStreamResponse.ts` 顶部加 `export`（它已经是 `export function` — 应该无需改动）；若 `StreamHandlers` 未导出，同理加 `export`。

- [ ] **Step 13.3: 提交**

```bash
git add frontend/src/hooks/useStreamResponse.test.ts
git commit -m "test(sources): useStreamResponse routes sources event to handler [PR2]"
```

---

### Task 14: Frontend `chatStore` 单测（onSources guard + onFinish preservation）

**Files:**
- Test: `frontend/src/stores/chatStore.test.ts`

**前置**：Task 11 已把 handlers 对象提取为 `createStreamHandlers` 导出工厂。本任务直接调用该工厂并对 zustand store 状态断言。

- [ ] **Step 14.1: 写 store 层测试（完整具体测试体）**

```typescript
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Message, SourceCard, SourcesPayload } from "@/types";
import { createStreamHandlers, useChatStore } from "./chatStore";

/**
 * 构造一个带占位 assistant message 的初始 store 状态。
 * assistantId 即占位消息的 id，也是 streamingMessageId。
 */
function seedStreamingState(assistantId: string) {
  const placeholder: Message = {
    id: assistantId,
    role: "assistant",
    content: "",
    status: "streaming",
    feedback: null,
    createdAt: new Date().toISOString()
  };
  useChatStore.setState((state) => ({
    ...state,
    messages: [placeholder],
    streamingMessageId: assistantId,
    isStreaming: true,
    currentSessionId: "c_test",
    sessions: [{ id: "c_test", title: "t", lastTime: new Date().toISOString() }],
    thinkingStartAt: null
  }));
}

function buildHandlers(assistantId: string) {
  const stopTask = vi.fn().mockResolvedValue(undefined);
  return createStreamHandlers(
    useChatStore.getState,
    useChatStore.setState,
    assistantId,
    stopTask
  );
}

function buildSourcesPayload(): SourcesPayload {
  const card: SourceCard = {
    index: 1,
    docId: "d1",
    docName: "doc.pdf",
    kbId: "kb1",
    topScore: 0.9,
    chunks: [{ chunkId: "c1", chunkIndex: 0, preview: "hi", score: 0.9 }]
  };
  return { conversationId: "c_test", messageId: null, cards: [card] };
}

describe("chatStore.onSources", () => {
  beforeEach(() => {
    useChatStore.setState({
      messages: [],
      streamingMessageId: null,
      isStreaming: false,
      sessions: [],
      currentSessionId: null,
      thinkingStartAt: null
    } as Partial<ReturnType<typeof useChatStore.getState>>);
  });

  it("ignores payload when streamingMessageId does not match assistantId (stale stream)", () => {
    seedStreamingState("msgA");
    const staleHandlers = buildHandlers("msgB");

    staleHandlers.onSources?.(buildSourcesPayload());

    const msg = useChatStore.getState().messages[0];
    expect(msg.id).toBe("msgA");
    expect(msg.sources).toBeUndefined();
  });

  it("writes sources to the streaming message when ids match", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    const payload = buildSourcesPayload();

    handlers.onSources?.(payload);

    const msg = useChatStore.getState().messages[0];
    expect(msg.sources).toEqual(payload.cards);
  });

  it("drops payload when cards is not an array", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");

    handlers.onSources?.({
      conversationId: "c_test",
      messageId: null,
      cards: null as unknown as SourceCard[]
    });

    const msg = useChatStore.getState().messages[0];
    expect(msg.sources).toBeUndefined();
  });
});

describe("chatStore.onFinish sources preservation", () => {
  beforeEach(() => {
    useChatStore.setState({
      messages: [],
      streamingMessageId: null,
      isStreaming: false,
      sessions: [],
      currentSessionId: null,
      thinkingStartAt: null
    } as Partial<ReturnType<typeof useChatStore.getState>>);
  });

  it("preserves sources after id is replaced by db id on finish", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");

    // 先塞 sources
    handlers.onSources?.(buildSourcesPayload());
    expect(useChatStore.getState().messages[0].sources).toBeDefined();

    // onFinish 触发 id 替换
    handlers.onFinish?.({ messageId: "db_1", title: "新标题" });

    const finalMsg = useChatStore.getState().messages[0];
    expect(finalMsg.id).toBe("db_1");
    expect(finalMsg.sources).toBeDefined();
    expect(finalMsg.sources?.[0].docId).toBe("d1");
  });
});
```

**实施期备注**：
1. `useChatStore.setState` 的 `Partial` 类型断言 `as Partial<ReturnType<typeof useChatStore.getState>>` 是为了绕过 zustand 严格的 set shape 检查；若 TypeScript 报更严格，改用 `store.setState(state => ({ ...state, messages: [], ... }))` 的函数式 set
2. `Message` 的必填字段按实际 `types/index.ts` 定义调整；这里构造的占位 message 至少需要 `id / role / content / status / feedback / createdAt`
3. `onFinish` payload 的字段按实际 `CompletionPayload` 定义调整；当前假设含 `messageId` / `title`

- [ ] **Step 14.2: 运行测试**

```bash
cd frontend && npx vitest run src/stores/chatStore.test.ts
```

预期：4 个测试全通过。若 `createStreamHandlers` 未 export（Task 11 遗漏），先回 Task 11 补 `export`。

- [ ] **Step 14.3: 提交**

```bash
git add frontend/src/stores/chatStore.test.ts
git commit -m "test(sources): chatStore.onSources guard + onFinish sources preservation [PR2]"
```

---

## 全量验收

执行完 Task 1-14 后，在分支上跑全量验收：

- [ ] **A. 后端全量编译 + spotless**

```bash
mvn clean install -DskipTests spotless:check
```

预期：BUILD SUCCESS，spotless 无违规。

- [ ] **B. 后端全量测试**

```bash
mvn test
```

预期：PR2 新增测试全绿；`CLAUDE.md` 记录的 10 个基线失败（Milvus 容器 / pgvector 扩展 / KB 种子数据相关）可忽略。

- [ ] **C. 前端类型 + lint + test**

```bash
cd frontend && npx tsc --noEmit && npm run lint && npm run test
```

预期：三项全绿。

- [ ] **D. 本机冒烟（flag on）**

1. 改 `application.yaml` 临时设 `rag.sources.enabled: true`
2. `mvn -pl bootstrap spring-boot:run`（记得设 `NO_PROXY=localhost,127.0.0.1`）
3. 在前端 `/chat` 问一个能命中 KB 的问题
4. DevTools Network 找到 `/rag/v3/chat` 请求，查 EventStream 标签
5. 确认看到 `event: sources` 帧、帧内 JSON shape 正确、位置在 `meta` 后 `message` 前
6. DevTools Console 里 `useChatStore.getState().messages` 检查 assistant message 的 `sources` 字段非空
7. UI：**零可见变化**（没有 `<Sources />` 组件）

- [ ] **E. 本机冒烟（flag off，回归）**

1. 回退 `application.yaml` 为 `enabled: false`（默认值）
2. 重启应用
3. 同样的问题不产生 `event: sources` 帧
4. UI 与 PR1 时期完全一致

---

## Self-Review 结果

**Spec 覆盖检查**（`docs/superpowers/specs/2026-04-21-answer-sources-pr2-design.md`）：

- § 1 架构与数据流：Task 7 + 8 实现 3 闸门 + 发射点
- § 2 后端组件清单（6 新增 / 5 修改）：Task 1-7 全覆盖，`StreamCallbackFactory` 无需改动（spec 明确声明）
- § 3 前端改动清单：Task 9-11 覆盖 3 文件
- § 4 SSE 契约 + 缺席矩阵：契约在 Task 1 DTOs 实现；缺席矩阵行为在 Task 7 + 8 测试覆盖
- § 5 测试点：Task 2 + 4 + 7 + 8（后端单测 3 类 + 集成）、Task 13 + 14（前端单测 2 个）全对齐
- § 6 Not in PR2：本计划不触达任何 PR3/PR4 文件（`MessageItem.tsx` / `sessionService.ts` / `t_message` schema 均未出现）
- § 7 Gotcha：Task 5 落实 `@Builder.Default @NonNull`、Task 6 落实 `emitSources` 不吞错
- § 8 PR 拆分：本计划严格属于 PR2 边界

**Placeholder 扫描**：无——Task 8 已改成可选加强（有/无基类二选一决策点），Task 14 已写具体测试体不留 TODO，Task 7 用真实 service + InOrder 取代原来的"抽取闸门辅助"误设计。

**类型一致性**：`SourceCardsHolder.trySet/get` 签名在 Task 2 定义、Task 6 消费；`SourceCardBuilder.build(chunks, maxCards, previewMaxChars)` 在 Task 4 定义、Task 7 调用；`createStreamHandlers(get, set, assistantId, stopTask)` 在 Task 11 提取并 export，Task 14 消费——签名一致。

---

## 执行选择

**Plan complete and saved to `docs/superpowers/plans/2026-04-21-answer-sources-pr2-implementation.md`. Two execution options:**

**1. Subagent-Driven（推荐）** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints
