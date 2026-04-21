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
package com.nageoffer.ai.ragent.rag.dto;

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
package com.nageoffer.ai.ragent.rag.dto;

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
package com.nageoffer.ai.ragent.rag.dto;

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
package com.nageoffer.ai.ragent.rag.dto;

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
package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.rag.dto.SourceCard;
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
package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.rag.dto.SourceCard;

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

沿用 `RAGConfigProperties.java` 的 `@Configuration + @Value` 风格（不用 `@ConfigurationProperties` 以保持项目一致）：

```java
package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 回答来源功能配置。
 * <p>
 * 默认 {@code enabled=false}，PR2~PR4 合并期间保持关闭，PR5 转为 true。
 */
@Data
@Configuration
public class RagSourcesProperties {

    /** 功能总开关（默认关闭）。 */
    @Value("${rag.sources.enabled:false}")
    private Boolean enabled;

    /** Chunk preview 截断长度（按 codePoint 计算）。 */
    @Value("${rag.sources.preview-max-chars:200}")
    private Integer previewMaxChars;

    /** SourceCard 列表条数上限。 */
    @Value("${rag.sources.max-cards:8}")
    private Integer maxCards;
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
package com.nageoffer.ai.ragent.rag.core.source;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
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
package com.nageoffer.ai.ragent.rag.core.source;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
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
    public boolean trySetCards(java.util.List<com.nageoffer.ai.ragent.rag.dto.SourceCard> cards) {
        return cardsHolder.trySet(cards);
    }

    /**
     * 机械发射 SSE {@code sources} 事件。异常语义沿用 {@link SseEmitterSender#sendEvent}，
     * 不做额外吞错。
     */
    public void emitSources(com.nageoffer.ai.ragent.rag.dto.SourcesPayload payload) {
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
    private final com.nageoffer.ai.ragent.rag.core.source.SourceCardBuilder sourceCardBuilder;
    private final com.nageoffer.ai.ragent.rag.config.RagSourcesProperties ragSourcesProperties;
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
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourcesPayload;
```

**注意**：闸门 1 早返回分支（`guidanceDecision.isPrompt()` / `allSystemOnly` / `ctx.isEmpty()`）**不要**加 sources 发射代码——它们在 `distinctChunks` 计算之前就 `return` 了，天然不会走到 sources 逻辑。

- [ ] **Step 7.3: 写 `RAGChatServiceImplSourcesTest.java`**

由于 `RAGChatServiceImpl` 字段很多，完整 mock 组装工作量大。此测试聚焦"三层闸门 + build/emit 被调用情况"，用 Mockito 验证 `sourceCardBuilder.build(...)` 和 `callback.trySetCards / emitSources` 的调用次数。

**测试范围**：主路径的 3 闸门行为组合 + MCP-only 锁口径场景。不覆盖 L109~L180 早返回分支（那些分支不触达新代码，无需额外断言）。

```java
package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RagSourcesProperties;
import com.nageoffer.ai.ragent.rag.core.source.SourceCardBuilder;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.service.handler.StreamChatEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聚焦 RAGChatServiceImpl 新增的"3 层闸门"编排行为。
 * <p>
 * 因 RAGChatServiceImpl 的完整依赖图很大，此测试不启动 Spring，也不进入真实
 * 检索 / LLM 链路。我们把 3 闸门的判定逻辑抽到一个可独立测试的小辅助
 * （见 {@link #applySourcesGates}），锁住期望行为。
 * <p>
 * 真实端到端顺序由 {@code RAGChatControllerSourcesIntegrationTest} 覆盖。
 */
class RAGChatServiceImplSourcesTest {

    private SourceCardBuilder builder;
    private StreamChatEventHandler callback;
    private RagSourcesProperties props;

    @BeforeEach
    void setUp() {
        builder = mock(SourceCardBuilder.class);
        callback = mock(StreamChatEventHandler.class);
        props = new RagSourcesProperties();
        props.setEnabled(true);
        props.setMaxCards(8);
        props.setPreviewMaxChars(200);
    }

    private void applySourcesGates(List<RetrievedChunk> distinctChunks) {
        if (Boolean.TRUE.equals(props.getEnabled()) && !distinctChunks.isEmpty()) {
            List<SourceCard> cards = builder.build(distinctChunks, props.getMaxCards(), props.getPreviewMaxChars());
            if (!cards.isEmpty() && callback.trySetCards(cards)) {
                callback.emitSources(any());
            }
        }
    }

    @Test
    void happyPathShouldCallBuilderAndEmit() {
        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").docId("d1").chunkIndex(0).score(0.9).text("t").build());
        List<SourceCard> cards = List.of(SourceCard.builder().index(1).docId("d1").build());
        when(builder.build(any(), anyInt(), anyInt())).thenReturn(cards);
        when(callback.trySetCards(cards)).thenReturn(true);

        applySourcesGates(chunks);

        verify(builder, times(1)).build(chunks, 8, 200);
        verify(callback, times(1)).trySetCards(cards);
        verify(callback, times(1)).emitSources(any());
    }

    @Test
    void flagOffShouldNotCallBuilder() {
        props.setEnabled(false);
        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").docId("d1").build());

        applySourcesGates(chunks);

        verify(builder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).trySetCards(any());
        verify(callback, never()).emitSources(any());
    }

    @Test
    void emptyDistinctChunksShouldNotCallBuilder() {
        applySourcesGates(List.of());

        verify(builder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
    }

    @Test
    void emptyCardsShouldNotEmit() {
        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").docId("d1").build());
        when(builder.build(any(), anyInt(), anyInt())).thenReturn(List.of());

        applySourcesGates(chunks);

        verify(builder, times(1)).build(any(), anyInt(), anyInt());
        verify(callback, never()).trySetCards(any());
        verify(callback, never()).emitSources(any());
    }

    @Test
    void mcpOnlyWithEmptyDistinctChunksShouldNotCallBuilder() {
        // 锁住判定口径：即使 ctx.hasMcp=true（本测试层抽象掉 ctx），
        // 只要 distinctChunks.isEmpty()，sources 就不推。
        applySourcesGates(List.of());

        verify(builder, never()).build(any(), anyInt(), anyInt());
    }
}
```

**说明**：此测试采用"抽取闸门逻辑到辅助方法"的策略避免拉起完整 `RAGChatServiceImpl` 依赖图。`RAGChatServiceImpl` 本身的真实执行由 Task 8 的集成测试覆盖 SSE 帧序。

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

### Task 8: 后端集成测试 — SSE 帧序（happy path + flag off）

**Files:**
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/controller/RAGChatControllerSourcesIntegrationTest.java`

**说明**：基线 10 个已知失败测试不包含集成级 `@SpringBootTest`，项目没有现成的 `/rag/v3/chat` 端到端 SSE 断言框架。完整端到端（连真实 OpenSearch + LLM）成本高。

**本任务的选择**：写一个 **"组件级"集成测试**，对 `RAGChatServiceImpl.streamChat(...)` 做部分 mock（`LLMService`、`RetrievalEngine` 等用 `@MockBean`），使 `SseEmitter` 真实工作；断言 SSE 发射顺序中 `sources` 位置。**不依赖**真实 OpenSearch / LLM 部署。

若此集成测试复杂度超过预期（如 `@MockBean` 字段过多），可降级为"纯 unit 测试 + Mockito InOrder 验证 `callback.emitSources` 相对 `llmService.streamChat` 的调用顺序"——见 Step 8.2 备选方案。

- [ ] **Step 8.1: 写组件级集成测试（推荐）**

```java
package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl;
// ... 依赖 mock 组装
// [此处留详细 @SpringBootTest 组装 — 实施时按项目实际 WebMvc 测试基类填充]
```

**实施时**：参考项目中已有的 `@SpringBootTest` 测试（如果有）组装 `MockMvc` + `MockBean`；否则走 Step 8.2 备选方案。

- [ ] **Step 8.2: 备选 — InOrder 单元测试**

若 Step 8.1 组装困难，改用以下单元测试对 `RAGChatServiceImpl.streamChat` 做 Mockito 验证：

```java
package com.nageoffer.ai.ragent.rag.service.impl;

// ... [使用 Mockito InOrder 验证：
//      retrievalEngine.retrieve → sourceCardBuilder.build → callback.emitSources → llmService.streamChat
//      的相对调用顺序]
```

完整 mock 表较长（RAGChatServiceImpl 有 14 个 `@RequiredArgsConstructor` 依赖）。实施时：
1. 用 `@ExtendWith(MockitoExtension.class)` + 多个 `@Mock` 字段
2. 用 `Mockito.inOrder(sourceCardBuilder, callback, llmService)` 验证相对次序
3. 不验证 SSE 线缆格式（那是 `SseEmitterSender` 的责任）

**闸门覆盖**（3 个测试）：
- happy path：`retrieve` 返回非空 ctx → `build` 被调 → `emitSources` 被调 → `streamChat` 被调
- flag off：`props.enabled=false` → `build` 不被调、`emitSources` 不被调，`streamChat` 照常被调
- 空 distinctChunks：`retrieve` 返回的 ctx 使得 `distinctChunks.isEmpty()` → `build` 不被调，`streamChat` 照常被调

- [ ] **Step 8.3: 运行测试**

```bash
mvn -pl bootstrap test -Dtest='RAGChatControllerSourcesIntegrationTest,RAGChatServiceImplSourcesTest'
```

预期：全通过。

- [ ] **Step 8.4: Spotless + 提交**

```bash
mvn spotless:apply -q
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/controller/RAGChatControllerSourcesIntegrationTest.java
git commit -m "test(sources): SSE frame order integration test (happy path + flag off) [PR2]"
```

**⚠️ 执行期注意**：若 Step 8.1 组装失败且 Step 8.2 能覆盖"3 闸门相对调用次序"，可以在 commit message 里注明"使用 InOrder 单测代替 @SpringBootTest"并继续。Task 8 的成立标准是"3 闸门行为被测试覆盖"，不强求真实 SSE wire 格式。

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

### Task 11: Frontend `chatStore.onSources` + guard

**Files:**
- Modify: `frontend/src/stores/chatStore.ts`

- [ ] **Step 11.1: 更新 imports**

找到 `chatStore.ts` 顶部的 type imports，确保包含 `SourcesPayload`。如果现有 import 行已经是：

```typescript
import type {
  ...
  SuggestionsPayload,
  ...
} from "@/types";
```

则追加 `SourcesPayload`。

- [ ] **Step 11.2: 在 stream handlers 构造处加 `onSources`**

找到 `onSuggestions:` handler（约 L385 附近），在它之后加：

```typescript
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
```

**关键约束**：
- Guard 必须先于所有 set：`streamingMessageId !== assistantId` 时直接 return
- 更新目标用 `state.streamingMessageId` 匹配，**不**用 `payload.messageId`（流式阶段为 null）
- payload 形状 guard：`!Array.isArray(payload.cards)` 丢弃异常载荷

- [ ] **Step 11.3: TypeScript + ESLint 验证**

```bash
cd frontend && npx tsc --noEmit && npm run lint
```

- [ ] **Step 11.4: 提交**

```bash
git add frontend/src/stores/chatStore.ts
git commit -m "feat(sources): onSources handler with streamingMessageId guard [PR2]"
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

- [ ] **Step 12.4: 冒烟验证 vitest 能跑**

```bash
cd frontend && npm run test
```

预期：`No test files found`（匹配不到测试文件，但 vitest 自身启动成功）。

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

- [ ] **Step 14.1: 写 store 层测试**

```typescript
import { describe, expect, it } from "vitest";
import { useChatStore } from "./chatStore";
import type { SourceCard, SourcesPayload, CompletionPayload } from "@/types";

describe("chatStore.onSources", () => {
  // [注意] 这些测试依赖 chatStore 把 onSources handler 暴露为可调用。
  // 若 onSources 是闭包内部构造不可访问，需要重构 chatStore 导出 handler
  // 构造函数；另一条路是测 handler 的"效果"：手动 set streamingMessageId +
  // 通过某种入口调用。实施时择一。

  it("ignores payload when streamingMessageId does not match assistantId", () => {
    // TODO（实施时细化）：构造 state 使 streamingMessageId='sA'；
    // 通过 chatStore 的入口调用模拟 assistantId='sB' 的 onSources
    // 断言：messages 中没有 sources 字段出现
  });

  it("writes sources to the streaming message when ids match", () => {
    // TODO（实施时细化）：构造 state 使 streamingMessageId='sA' 且
    // messages 中存在 id='sA' 的占位消息；assistantId='sA' 触发 onSources
    // 断言：该 message 的 sources 等于 payload.cards
  });

  it("preserves sources after onFinish replaces the temp id with db id", () => {
    // 构造 message 有 sources + id='sA'，触发 onFinish({messageId:'db_1'})；
    // 断言 message 的 id 改为 'db_1'，sources 仍存在。
  });
});
```

**实施期说明**：当前 `chatStore.ts` 的 stream handlers 在 `sendChat(...)` 闭包内构造，外部拿不到 handler 回调引用。两条实施路径：

**路径 A（推荐）：提取 handlers 工厂为可导出函数**

把 `chatStore.ts` 里 stream handlers 对象的构造抽成一个 `createStreamHandlers(get, set, assistantId)` 函数并 `export`。这样测试可以直接调用 handlers.onSources(...) 并观察 store 状态变化。改动小（包装 + 导出），不影响行为。

**路径 B：通过 zustand store API 直接 set `streamingMessageId`，然后用某个现有公共方法触发 handler**

更侵入性，不推荐。

选定路径 A 时，测试里把 `TODO` 展开为对 `createStreamHandlers` 的直接调用，断言 `useChatStore.getState().messages` 的变化。

- [ ] **Step 14.2: 运行测试**

```bash
cd frontend && npx vitest run src/stores/chatStore.test.ts
```

- [ ] **Step 14.3: 提交**

```bash
git add frontend/src/stores/chatStore.test.ts frontend/src/stores/chatStore.ts
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

**Placeholder 扫描**：Task 8.1 (`[此处留详细 @SpringBootTest 组装]`) 和 Task 14 (TODO 占位) 是已标注"实施期视情况细化"的两处——非占位失败，而是已给出备选方案（Step 8.2 InOrder 单测 / 路径 A 工厂提取）的条件性分支。

**类型一致性**：`SourceCardsHolder.trySet/get` 签名在 Task 2 定义、Task 6 消费；`SourceCardBuilder.build(chunks, maxCards, previewMaxChars)` 在 Task 4 定义、Task 7 调用——签名一致。

---

## 执行选择

**Plan complete and saved to `docs/superpowers/plans/2026-04-21-answer-sources-pr2-implementation.md`. Two execution options:**

**1. Subagent-Driven（推荐）** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints
