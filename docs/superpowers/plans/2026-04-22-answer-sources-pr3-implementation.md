# Answer Sources PR3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Prompt 改造 + 前端引用渲染（remarkCitations + CitationBadge + Sources）+ 引用质量埋点 + SRC-6 测试，feature flag `rag.sources.enabled` 仍默认 `false`，零用户可见变化。

**Architecture:**
- 后端：`PromptContext` 扩 `cards` 字段；`RAGPromptService.buildStructuredMessages` 内部局部派生 `resolvedSystemPrompt` / `resolvedKbEvidence`（入参不改）；`RAGChatServiceImpl.streamLLMResponse` 多接一个 `cards` 参数；`StreamChatEventHandler.onComplete` 在 `updateTraceTokenUsage`（overwrite）之后 merge citation stats。
- 前端：`utils/citationAst.ts` 存共享常量；`utils/remarkCitations.ts` 三合一 AST 插件；`<CitationBadge>` + `<Sources>` 组件；`MarkdownRenderer` 按 `hasSources` 同时 gate plugin 与 `cite` 组件映射，守 rollback 契约；`MessageItem` 管理 `highlightedIndex / timerRef / sourcesRef`。

**Tech Stack:**
- 后端：Java 17 / Spring Boot 3.5.7 / Lombok / Jackson / MyBatis Plus
- 前端：React 18 / TypeScript / vitest 2.x / jsdom / react-markdown 9 / remark-gfm 4 / unist-util-visit 5（新）
- 测试：JUnit 5 + Mockito（后端）/ vitest + @testing-library/react（新，前端组件测）

**基线**：`feature/answer-sources-pr3` 从 `main` 拉，HEAD=`2c1a75c`（仅含 spec 文档）。本 plan 产出的每个 commit 都可独立 revert，flag off 下对 PR2 末态字节级等价。

**参考 spec**：`docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md`

---

## 文件结构概览

### 新建（9 文件）

| 路径 | 职责 |
|---|---|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollector.java` | 纯工具：扫 answer 统计 total/valid/invalid/coverage |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceCitationTest.java` | Prompt 改造单测 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollectorTest.java` | 埋点工具单测 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerCitationTest.java` | handler merge 顺序单测 |
| `frontend/src/utils/citationAst.ts` | 共享常量 `CITATION` + `SKIP_PARENT_TYPES` |
| `frontend/src/utils/remarkCitations.ts` | mdast 三合一插件 |
| `frontend/src/components/chat/CitationBadge.tsx` | 蓝色角标 + tooltip |
| `frontend/src/components/chat/Sources.tsx` | 卡片区，忠实渲染后端 cards 原序 |
| `frontend/src/utils/remarkCitations.test.ts` + 4 个组件测试文件 | 前端单测 |

### 修改（5 文件）

| 路径 | 改动 |
|---|---|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContext.java` | 加 `List<SourceCard> cards` 字段 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java` | 内部局部派生 + 2 个私有方法 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` | 把 `cards` 从 gate-3 局部变量提到外层 + `streamLLMResponse` 加参数 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java` | `onComplete` 加 `mergeCitationStatsIntoTrace()` 调用 + 私有方法 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java` | 扩 SRC-6 测试 |
| `frontend/src/components/chat/MarkdownRenderer.tsx` | `hasSources` gate plugin + cite 组件映射 |
| `frontend/src/components/chat/MessageItem.tsx` | 新增 `<Sources />` 渲染 + handle click state |
| `frontend/package.json` | 加 `unist-util-visit`, `@testing-library/react`, `@testing-library/user-event`, `unified` (dev), `remark-parse` (dev) |

---

## 任务执行约束（所有 task 必读）

- **强制 `mvn clean compile` 而非 `compile`**：PR2 教训 L2，`target/` stale bytecode 会让错误 import 被误判为 "BUILD SUCCESS"。每次后端代码变更后 `mvn -pl bootstrap clean compile -q` 作为验证 gate。
- **Subagent 读真源，不信 spec**：若 spec 里的类路径 / 签名与实际代码冲突，以代码为准；先 `Read` 目标文件再写测试。
- **TDD 严格**：每个功能 task 都是"写失败测试 → 跑确认 FAIL → 写实现 → 跑确认 PASS → commit"。
- **Commit 消息规范**：沿用 PR2 格式 `<type>(sources): <desc> [PR3]`，type ∈ `feat / fix / test / chore / docs`。

---

## Task 1: `PromptContext` 扩 `cards` 字段

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContext.java`

### - [ ] Step 1: Read 当前 PromptContext

Run: `cat bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContext.java`
Expected output: `@Data @Builder` 类含 `question / mcpContext / kbContext / mcpIntents / kbIntents / intentChunks` 6 个字段 + `hasMcp() / hasKb()` 方法。

### - [ ] Step 2: 加字段 `cards`

在 `private Map<String, List<RetrievedChunk>> intentChunks;` 下方（第 44 行附近）加一行：

```java
    private List<com.nageoffer.ai.ragent.rag.dto.SourceCard> cards;
```

或改用 import 写法（推荐）——在 imports 区加：

```java
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
```

并把字段写成：

```java
    private List<SourceCard> cards;
```

### - [ ] Step 3: 编译验证

Run: `mvn -pl bootstrap clean compile -q`
Expected: BUILD SUCCESS，零 error / 警告。

### - [ ] Step 4: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/PromptContext.java
git commit -m "feat(sources): extend PromptContext with cards field [PR3]"
```

---

## Task 2: `RAGPromptServiceCitationTest` — 写失败测试（T2 驱动 T3 实现）

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceCitationTest.java`

### - [ ] Step 1: Read `RAGPromptService` 现有结构

Run: `cat bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java | head -100`
Expected: `buildStructuredMessages(PromptContext, List<ChatMessage>, String, List<String>)` 返回 `List<ChatMessage>`；`buildSystemPrompt(PromptContext)` 返回 `String`；依赖 `promptTemplateLoader`。

### - [ ] Step 2: Read PR2 的 `SourceCard` / `SourceChunk` DTO

Run: `cat bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourceCard.java`
Expected: 字段 `int index / String docId / String docName / String kbId / float topScore / List<SourceChunk> chunks`；`@Data @NoArgsConstructor @AllArgsConstructor @Builder`。

Run: `cat bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SourceChunk.java`
Expected: 字段 `String chunkId / int chunkIndex / String preview / float score`。

### - [ ] Step 3: Read `RetrievedChunk` 字段

Run: `cat framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunk.java | grep -E "private|@"`
Expected: `@EqualsAndHashCode(of = "id")` + 字段 `id / text / score / kbId / securityLevel / docId / chunkIndex`。

### - [ ] Step 4: 写测试文件

Create `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceCitationTest.java`：

```java
package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGPromptServiceCitationTest {

    @Mock PromptTemplateLoader promptTemplateLoader;

    @InjectMocks RAGPromptService promptService;

    @BeforeEach
    void setUp() {
        when(promptTemplateLoader.load(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("你是企业知识库助手。请基于参考文档回答。");
    }

    // ---------- helpers ----------

    private SourceCard card(int index, String docId, String docName, List<SourceChunk> chunks) {
        return SourceCard.builder()
                .index(index)
                .docId(docId)
                .docName(docName)
                .kbId("kb_x")
                .topScore(0.9f)
                .chunks(chunks)
                .build();
    }

    private SourceChunk chunk(String chunkId, int chunkIndex, String preview) {
        return SourceChunk.builder()
                .chunkId(chunkId)
                .chunkIndex(chunkIndex)
                .preview(preview)
                .score(0.8f)
                .build();
    }

    private RetrievedChunk rc(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).score(0.9f).build();
    }

    private PromptContext baseCtxWithKb() {
        return PromptContext.builder()
                .question("员工手册要求？")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                .build();
    }

    private String systemText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(ChatMessage::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private String allText(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ---------- tests ----------

    @Test
    void buildStructuredMessages_whenCardsNull_thenNoRuleBlockAndOriginalKbContext() {
        PromptContext ctx = baseCtxWithKb();    // cards 默认 null
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "员工手册要求？", List.of());
        String all = allText(msgs);
        assertThat(all).doesNotContain("【引用规则】");
        assertThat(all).doesNotContain("【参考文档】");
        assertThat(all).contains("kb-evidence");   // 原 kbContext 未被改写
    }

    @Test
    void buildStructuredMessages_whenCardsEmpty_thenNoRuleBlockAndOriginalKbContext() {
        PromptContext ctx = baseCtxWithKb().toBuilder().cards(List.of()).build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "员工手册要求？", List.of());
        String all = allText(msgs);
        assertThat(all).doesNotContain("【引用规则】");
        assertThat(all).contains("kb-evidence");
    }

    @Test
    void buildStructuredMessages_whenCardsNonEmptyButKbContextBlank_thenNoRuleBlock() {
        // 异常组合：cards 非空但 kbContext 空，citationMode 应 false（守护 scene 选择）
        SourceCard c = card(1, "d1", "员工手册.pdf", List.of(chunk("c_001", 12, "preview-1")));
        PromptContext ctx = PromptContext.builder()
                .question("q")
                .mcpContext("mcp-only-evidence")
                .kbContext("")                                // 关键：kbContext 空
                .cards(List.of(c))
                .intentChunks(Map.of())
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String all = allText(msgs);
        assertThat(all).doesNotContain("【引用规则】");
        assertThat(all).doesNotContain("【参考文档】");
    }

    @Test
    void buildStructuredMessages_whenOneCard_thenRangeIsSingleBracket() {
        SourceCard c = card(1, "d1", "员工手册.pdf", List.of(chunk("c_001", 12, "preview-1")));
        PromptContext ctx = baseCtxWithKb().toBuilder().cards(List.of(c)).build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String system = systemText(msgs);
        assertThat(system).contains("【引用规则】");
        assertThat(system).contains("本次可用引用编号仅限 [^1]");
        assertThat(system).doesNotContain("至 [^1]");          // N==1 不输出"至"区间
    }

    @Test
    void buildStructuredMessages_whenMultipleCards_thenRangeIsInterval() {
        SourceCard c1 = card(1, "d1", "A.pdf", List.of(chunk("c_001", 1, "preview-1")));
        SourceCard c2 = card(2, "d2", "B.pdf", List.of(chunk("c_002", 2, "preview-2")));
        PromptContext ctx = baseCtxWithKb().toBuilder().cards(List.of(c1, c2)).build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String system = systemText(msgs);
        assertThat(system).contains("本次可用引用编号仅限 [^1] 至 [^2]");
    }

    @Test
    void buildStructuredMessages_whenCitationMode_thenEvidenceUsesFullText() {
        // 关键回归：evidence 必须用 RetrievedChunk.text 全文，不是 SourceChunk.preview
        SourceCard c = card(1, "d1", "手册.pdf", List.of(
                chunk("c_001", 12, "PREVIEW_TRUNCATED"),   // 故意把 preview 设成与 text 不同
                chunk("c_002", 13, "PREVIEW_TRUNCATED_2")
        ));
        PromptContext ctx = baseCtxWithKb().toBuilder().cards(List.of(c)).build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String all = allText(msgs);
        assertThat(all).contains("【参考文档】");
        assertThat(all).contains("[^1]《手册.pdf》");
        assertThat(all).contains("员工入职第 6 个月可申请转正评估。");   // 来自 intentChunks
        assertThat(all).contains("培训期满后考评合格予以录用。");
        assertThat(all).doesNotContain("PREVIEW_TRUNCATED");           // 绝不用 preview
    }

    @Test
    void buildStructuredMessages_whenChunkIdMissingInIntentChunks_thenFallbackToPreview() {
        // chunkId 在 intentChunks 缺失 → 回退到 preview（避免整个 card 失效）
        SourceCard c = card(1, "d1", "手册.pdf", List.of(
                chunk("c_missing", 99, "fallback-preview")
        ));
        PromptContext ctx = baseCtxWithKb().toBuilder().cards(List.of(c)).build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String all = allText(msgs);
        assertThat(all).contains("[^1]《手册.pdf》");
        assertThat(all).contains("fallback-preview");
    }
}
```

### - [ ] Step 5: 确认测试 FAIL（驱动 T3）

Run: `mvn -pl bootstrap test -Dtest=RAGPromptServiceCitationTest -q 2>&1 | tail -30`
Expected: 7 个测试都失败——4 个 cards 非空测试预期 `【引用规则】`/`【参考文档】` 出现但没出现；2 个 cards null/empty 测试此时会通过（因为原路径）；异常组合测试可能通过。具体失败数量不重要，**关键是"cards 非空时 evidence/rule 出现"这组断言当前必然失败**。

### - [ ] Step 6: Commit

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptServiceCitationTest.java
git commit -m "test(sources): RAGPromptService citation mode test scaffolding [PR3]"
```

---

## Task 3: `RAGPromptService` — 实现 citation 派生（让 T2 测试通过）

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`

### - [ ] Step 1: Read 现有 `buildStructuredMessages` 方法

Run: `sed -n '66,100p' bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java`
Expected: 方法签名 `public List<ChatMessage> buildStructuredMessages(PromptContext context, ...)`，内部有 `String systemPrompt = buildSystemPrompt(context);` 和 `messages.add(ChatMessage.user(formatEvidence(KB_CONTEXT_HEADER, context.getKbContext())));`。

### - [ ] Step 2: 改造 `buildStructuredMessages` 使用局部派生变量

把方法体（从 `String systemPrompt = buildSystemPrompt(context);` 开始到 `messages.add(ChatMessage.user(formatEvidence(KB_CONTEXT_HEADER, context.getKbContext())));` 的片段）替换为：

```java
        List<ChatMessage> messages = new ArrayList<>();

        boolean citationMode = CollUtil.isNotEmpty(context.getCards()) && context.hasKb();

        String resolvedSystemPrompt = buildSystemPrompt(context);
        String resolvedKbEvidence = context.getKbContext();

        if (citationMode) {
            resolvedKbEvidence = buildCitationEvidence(context);
            resolvedSystemPrompt = appendCitationRule(resolvedSystemPrompt, context.getCards());
        }

        if (StrUtil.isNotBlank(resolvedSystemPrompt)) {
            messages.add(ChatMessage.system(resolvedSystemPrompt));
        }
        if (StrUtil.isNotBlank(context.getMcpContext())) {
            messages.add(ChatMessage.system(formatEvidence(MCP_CONTEXT_HEADER, context.getMcpContext())));
        }
        if (StrUtil.isNotBlank(resolvedKbEvidence)) {
            messages.add(ChatMessage.user(formatEvidence(KB_CONTEXT_HEADER, resolvedKbEvidence)));
        }
```

后半段（`if (CollUtil.isNotEmpty(history))` 等）保持不动。

### - [ ] Step 3: 加 `buildCitationEvidence` 私有方法

在文件内（靠近 `formatEvidence` 同区域）加：

```java
    private String buildCitationEvidence(PromptContext ctx) {
        // chunkId → 全文索引；同一 chunkId 可能在多个意图 key 下，put 幂等覆盖（值相同）
        java.util.Map<String, String> chunkTextById = new java.util.HashMap<>();
        java.util.Map<String, List<RetrievedChunk>> intentChunks = ctx.getIntentChunks();
        if (intentChunks != null) {
            intentChunks.values().forEach(list -> {
                if (list == null) return;
                for (RetrievedChunk rc : list) {
                    if (rc != null && rc.getId() != null) {
                        chunkTextById.put(rc.getId(), rc.getText());
                    }
                }
            });
        }

        StringBuilder sb = new StringBuilder("【参考文档】\n");
        for (com.nageoffer.ai.ragent.rag.dto.SourceCard card : ctx.getCards()) {
            sb.append('[').append('^').append(card.getIndex()).append(']')
              .append('《').append(card.getDocName()).append('》').append('\n');
            int i = 1;
            for (com.nageoffer.ai.ragent.rag.dto.SourceChunk chunk : card.getChunks()) {
                String body = chunkTextById.getOrDefault(chunk.getChunkId(), chunk.getPreview());
                sb.append("—— 片段 ").append(i++).append("：").append(body).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
```

（或提到 imports 区：`import com.nageoffer.ai.ragent.rag.dto.SourceCard;` / `import com.nageoffer.ai.ragent.rag.dto.SourceChunk;` / `import java.util.HashMap;`；然后把内部用短名。推荐 imports 写法。）

### - [ ] Step 4: 加 `appendCitationRule` 私有方法

```java
    private String appendCitationRule(String base, List<com.nageoffer.ai.ragent.rag.dto.SourceCard> cards) {
        String range = cards.size() == 1
                ? "[^1]"
                : "[^1] 至 [^" + cards.size() + "]";
        String rule = """
                【引用规则】
                回答中凡是基于【参考文档】的陈述，必须在该陈述末尾附上对应文档的编号，
                格式为半角方括号加脱字符加数字 [^n]，多个来源用 [^1][^2] 连写。
                例：
                  员工入职后第 6 个月可申请转正评估[^2]。
                  培训期满后考评合格者予以正式录用[^1][^3]。
                若陈述属于常识或不依赖参考文档，则不要添加 [^n]。
                本次可用引用编号仅限 %s；不要输出任何超出范围或未在【参考文档】中出现的编号。
                """.formatted(range);
        return PromptTemplateUtils.cleanupPrompt(base + "\n\n" + rule);
    }
```

### - [ ] Step 5: 跑测试确认全部 PASS

Run: `mvn -pl bootstrap clean test -Dtest=RAGPromptServiceCitationTest -q 2>&1 | tail -15`
Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` — 全绿。

### - [ ] Step 6: 跑全量 PromptService 相关测试防回归

Run: `mvn -pl bootstrap test -Dtest='RAGPromptService*' -q 2>&1 | tail -10`
Expected: 零 Failures / Errors。

### - [ ] Step 7: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/prompt/RAGPromptService.java
git commit -m "feat(sources): RAGPromptService citation mode — local-derived evidence + rule append [PR3]"
```

---

## Task 4: `RAGChatServiceImpl.streamLLMResponse` 加 `cards` 参数

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`

### - [ ] Step 1: Read 当前三层闸门 + streamLLMResponse 区域

Run: `sed -n '218,260p' bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`
Expected: 闸门 3 里 `List<SourceCard> cards = sourceCardBuilder.build(...)` 是**局部变量**（scope 限于 `if` 块内）；`streamLLMResponse(...)` 调用在闸门 3 之后。

### - [ ] Step 2: 把 `cards` 提到外层 scope

把闸门 3 的代码块改成：

```java
        // ---- 回答来源事件推送（3 层闸门）----
        // 闸门 1：feature flag off → 跳过 sources（不影响回答路径）
        // 闸门 2：distinctChunks.isEmpty() → 跳过 builder 调用（MCP-Only / Mixed-but-no-KB 等）
        // 闸门 3：cards.isEmpty() → 不 trySet / 不 emit（findMetaByIds 过滤后无卡片）
        List<SourceCard> cards = List.of();   // 提到外层，初始空；传给 streamLLMResponse 时若空则 PromptService citationMode=false
        if (Boolean.TRUE.equals(ragSourcesProperties.getEnabled()) && !distinctChunks.isEmpty()) {
            cards = sourceCardBuilder.build(
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

### - [ ] Step 3: 把 `cards` 传进 `streamLLMResponse` 调用

把下方调用：

```java
        StreamCancellationHandle handle = streamLLMResponse(
                rewriteResult,
                ctx,
                mergedGroup,
                history,
                thinkingEnabled,
                callback
        );
```

改为：

```java
        StreamCancellationHandle handle = streamLLMResponse(
                rewriteResult,
                ctx,
                mergedGroup,
                history,
                thinkingEnabled,
                callback,
                cards
        );
```

### - [ ] Step 4: 修改 `streamLLMResponse` 签名和 PromptContext 构造

找到 `private StreamCancellationHandle streamLLMResponse(...)`（约 L286），把签名末尾加 `List<SourceCard> cards`：

```java
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback,
                                                       List<SourceCard> cards) {
```

方法内 `PromptContext.builder()` 链路加 `.cards(cards)`：

```java
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .cards(cards)
                .build();
```

### - [ ] Step 5: 验证编译

Run: `mvn -pl bootstrap clean compile -q 2>&1 | tail -10`
Expected: BUILD SUCCESS。

### - [ ] Step 6: 跑现有集成单测确认零回归

Run: `mvn -pl bootstrap test -Dtest=RAGChatServiceImplSourcesTest -q 2>&1 | tail -10`
Expected: 现有 7 个用例全绿（PR2 建立的 happy path / flag off / distinctChunks empty / cards empty / SystemOnly / trySet=false / MCP-Only）。

### - [ ] Step 7: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java
git commit -m "feat(sources): thread cards into streamLLMResponse + PromptContext [PR3]"
```

---

## Task 5: SRC-6 测试 — 独立 commit

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`

### - [ ] Step 1: Read 测试类现有 setup

Run: `sed -n '85,160p' bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`
Expected: 看到 `@InjectMocks RAGChatServiceImpl service;` / `@Mock StreamChatEventHandler callback;` / `@Mock SseEmitter emitter;` / `guidanceService.detectAmbiguity(any(), any()).thenReturn(GuidanceDecision.none());`

Run: `grep -n "GuidanceDecision" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/guidance/GuidanceDecision.java`
Expected: `public static GuidanceDecision prompt(String prompt)` 单参。

### - [ ] Step 2: 在末尾追加测试方法

在 `RAGChatServiceImplSourcesTest` 最后一个 `@Test` 方法之后、类的最后 `}` 之前追加：

```java
    @Test
    void streamChat_whenGuidanceClarificationPrompt_thenSkipSourcesEntirely() {
        // guidance 返回 prompt（GuidanceDecision.prompt 只接受一个 String）
        when(guidanceService.detectAmbiguity(any(), any()))
                .thenReturn(com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision.prompt("请补充您的问题细节"));

        service.streamChat("q", "cid-src6", null, false, emitter);

        // 负向：sources 链路 + LLM 均未启动
        verifyNoInteractions(sourceCardBuilder);
        verify(callback, never()).trySetCards(any());
        verify(callback, never()).emitSources(any());
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));

        // 正向：clarification 仍走流式返回（RAGChatServiceImpl.java L170 直接 callback.onContent + onComplete）
        verify(callback).onContent(eq("请补充您的问题细节"));
        verify(callback).onComplete();
    }
```

若 imports 区缺 `eq`：加 `import static org.mockito.ArgumentMatchers.eq;`；缺 `verifyNoInteractions`：加 `import static org.mockito.Mockito.verifyNoInteractions;`。

### - [ ] Step 3: 跑测试确认新增用例 + 既有用例都 PASS

Run: `mvn -pl bootstrap test -Dtest=RAGChatServiceImplSourcesTest -q 2>&1 | tail -10`
Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`。

### - [ ] Step 4: Commit（独立 commit，便于 revert）

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java
git commit -m "test(sources): SRC-6 — lock clarification branch skip sources [PR3]"
```

---

## Task 6: `CitationStatsCollector` — TDD 纯工具类

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollectorTest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollector.java`

### - [ ] Step 1: 写测试（失败）

Create `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollectorTest.java`：

```java
package com.nageoffer.ai.ragent.rag.core.source;

import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationStatsCollectorTest {

    private SourceCard card(int index) {
        return SourceCard.builder()
                .index(index).docId("d" + index).docName("D" + index).kbId("kb")
                .topScore(0.9f).chunks(List.of(SourceChunk.builder()
                        .chunkId("c" + index).chunkIndex(0).preview("p").score(0.9f).build()))
                .build();
    }

    @Test
    void scan_whenAnswerHasMixedValidAndInvalid_thenCountsCorrectly() {
        String answer = "第一句[^1]。第二句[^2]。越界[^99]。";
        var stats = CitationStatsCollector.scan(answer, List.of(card(1), card(2)));
        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.valid()).isEqualTo(2);
        assertThat(stats.invalid()).isEqualTo(1);
    }

    @Test
    void scan_whenAnswerBlank_thenAllZero() {
        var stats = CitationStatsCollector.scan("", List.of(card(1)));
        assertThat(stats.total()).isZero();
        assertThat(stats.valid()).isZero();
        assertThat(stats.invalid()).isZero();
        assertThat(stats.coverage()).isEqualTo(0.0);
    }

    @Test
    void scan_whenCardsEmpty_thenAllZero() {
        var stats = CitationStatsCollector.scan("有些文字[^1]。", List.of());
        assertThat(stats.total()).isZero();
        assertThat(stats.coverage()).isEqualTo(0.0);
    }

    @Test
    void scan_whenCardIndexesNonContiguous_thenValidByMembership() {
        // cards index 集合为 {1, 3, 5}；answer 出现 [^2] 应计为 invalid
        var stats = CitationStatsCollector.scan(
                "句子[^1]。句子[^2]。句子[^3]。句子[^5]。",
                List.of(card(1), card(3), card(5))
        );
        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.valid()).isEqualTo(3);   // 1,3,5
        assertThat(stats.invalid()).isEqualTo(1); // 2
    }

    @Test
    void scan_whenPartialSentencesHaveCitation_thenCoverageProportional() {
        String answer = "A[^1]。B。C[^1]。";   // 3 句粗切，2 句有引用
        var stats = CitationStatsCollector.scan(answer, List.of(card(1)));
        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.coverage()).isBetween(0.66, 0.67);
    }

    @Test
    void scan_whenAnswerLastSentenceHasNoPunctuation_thenCoverageReflectsCoarseSplit() {
        // 锁行为：spec 粗切会漏掉末尾无标点句，total sentence=1，有引用句=0
        String answer = "无标点尾段[^1]";
        var stats = CitationStatsCollector.scan(answer, List.of(card(1)));
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.valid()).isEqualTo(1);
        assertThat(stats.coverage()).isEqualTo(0.0);   // 无完整句子
    }
}
```

Run: `mvn -pl bootstrap test -Dtest=CitationStatsCollectorTest -q 2>&1 | tail -10`
Expected: FAIL — 类不存在，compilation error。

### - [ ] Step 2: 实现类

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollector.java`：

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

package com.nageoffer.ai.ragent.rag.core.source;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class CitationStatsCollector {

    private static final Pattern CITATION = Pattern.compile("\\[\\^(\\d+)]");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？]+[。！？]");

    private CitationStatsCollector() {}

    public static CitationStats scan(String answer, List<SourceCard> cards) {
        if (StrUtil.isBlank(answer) || CollUtil.isEmpty(cards)) {
            return new CitationStats(0, 0, 0, 0.0);
        }
        Set<Integer> validIndexes = cards.stream()
                .map(SourceCard::getIndex)
                .collect(Collectors.toSet());

        int total = 0, valid = 0, invalid = 0;
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            total++;
            if (validIndexes.contains(n)) valid++; else invalid++;
        }

        Matcher sm = SENTENCE.matcher(answer);
        int totalSent = 0, citedSent = 0;
        while (sm.find()) {
            totalSent++;
            if (CITATION.matcher(sm.group()).find()) citedSent++;
        }
        double coverage = totalSent == 0 ? 0.0 : (double) citedSent / totalSent;

        return new CitationStats(total, valid, invalid, coverage);
    }

    public record CitationStats(int total, int valid, int invalid, double coverage) {}
}
```

### - [ ] Step 3: 跑测试确认 PASS

Run: `mvn -pl bootstrap clean test -Dtest=CitationStatsCollectorTest -q 2>&1 | tail -10`
Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

### - [ ] Step 4: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollector.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollectorTest.java
git commit -m "feat(sources): CitationStatsCollector pure utility + 6 unit tests [PR3]"
```

---

## Task 7: `StreamChatEventHandlerCitationTest` — 写失败测试（驱动 T8）

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerCitationTest.java`

### - [ ] Step 1: Read 现有 handler + `StreamChatHandlerParams` 签名

Run: `grep -n "public StreamChatEventHandler\|Builder\|cardsHolder" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java | head -20`

Run: `cat bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java | head -80`

Expected: 构造器 `public StreamChatEventHandler(StreamChatHandlerParams params)`；params 通过 `@Builder` 暴露字段；`cardsHolder` 已有 `@Builder.Default`。

### - [ ] Step 2: Read `RagTraceRecordService`

Run: `grep -nE "updateRunExtraData|mergeRunExtraData" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RagTraceRecordService.java`
Expected: `void updateRunExtraData(String traceId, String extraData)` 和 `void mergeRunExtraData(String traceId, Map<String, Object> additions)` 两个方法。

### - [ ] Step 3: 写测试

Create `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerCitationTest.java`：

```java
package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.TokenUsage;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamChatEventHandlerCitationTest {

    @Mock SseEmitter emitter;
    @Mock ConversationMemoryService memoryService;
    @Mock ConversationGroupService conversationGroupService;
    @Mock StreamTaskManager taskManager;
    @Mock RagEvaluationService evaluationService;
    @Mock RagTraceRecordService traceRecordService;
    @Mock SuggestedQuestionsService suggestedQuestionsService;
    @Mock ThreadPoolTaskExecutor suggestedQuestionsExecutor;
    @Mock RAGConfigProperties ragConfigProperties;

    private SourceCardsHolder holder;
    private StreamChatEventHandler handler;

    @BeforeEach
    void setUp() {
        // 关键：traceId 在 handler 构造期从 RagTraceContext 读取，必须先 set
        RagTraceContext.setTraceId("test-trace-id");

        holder = new SourceCardsHolder();
        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("msg-1");
        when(ragConfigProperties.getSuggestionsEnabled()).thenReturn(false);   // 关掉 suggestions 路径
        when(taskManager.isCancelled(anyString())).thenReturn(false);

        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("c-1")
                .taskId("t-1")
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .evaluationService(evaluationService)
                .traceRecordService(traceRecordService)
                .suggestedQuestionsService(suggestedQuestionsService)
                .suggestedQuestionsExecutor(suggestedQuestionsExecutor)
                .ragConfigProperties(ragConfigProperties)
                .cardsHolder(holder)
                .build();

        handler = new StreamChatEventHandler(params);
    }

    @AfterEach
    void tearDown() {
        RagTraceContext.clear();
    }

    private SourceCard card(int n) {
        return SourceCard.builder()
                .index(n).docId("d" + n).docName("D" + n).kbId("kb").topScore(0.9f)
                .chunks(List.of(SourceChunk.builder()
                        .chunkId("c").chunkIndex(0).preview("p").score(0.8f).build()))
                .build();
    }

    @Test
    void onComplete_whenCardsHolderUnset_thenMergeRunExtraDataNotCalled() {
        // cards 未 set → handler 不读 holder 后续逻辑，不调 mergeRunExtraData
        handler.onContent("回答正文[^1]。");
        handler.setTokenUsage(new TokenUsage(10, 20, 30));    // token usage 可以有
        handler.onComplete();

        verify(traceRecordService, never()).mergeRunExtraData(anyString(), anyMap());
        // 但 updateRunExtraData 仍走（token overwrite）
        verify(traceRecordService).updateRunExtraData(eq("test-trace-id"), anyString());
    }

    @Test
    void onComplete_whenCardsHolderSetAndAnswerHasCitations_thenMergeCalledOnceWithFourKeys() {
        holder.trySet(List.of(card(1), card(2)));
        handler.onContent("句[^1]。句[^2]。越界[^99]。");
        handler.setTokenUsage(new TokenUsage(5, 6, 11));
        handler.onComplete();

        // 锁顺序：overwrite 先、merge 后
        InOrder io = inOrder(traceRecordService);
        io.verify(traceRecordService).updateRunExtraData(eq("test-trace-id"), anyString());
        io.verify(traceRecordService).mergeRunExtraData(eq("test-trace-id"), anyMap());

        // 锁 merge 的 Map 内容
        verify(traceRecordService).mergeRunExtraData(eq("test-trace-id"), org.mockito.ArgumentMatchers.argThat(map -> {
            Map<String, Object> m = (Map<String, Object>) map;
            return m.containsKey("citationTotal")
                    && m.containsKey("citationValid")
                    && m.containsKey("citationInvalid")
                    && m.containsKey("citationCoverage")
                    && ((Integer) m.get("citationTotal")) == 3
                    && ((Integer) m.get("citationValid")) == 2
                    && ((Integer) m.get("citationInvalid")) == 1;
        }));
    }

    @Test
    void onComplete_whenCardsSetButAnswerEmpty_thenStillMergesAllZeros() {
        holder.trySet(List.of(card(1)));
        handler.setTokenUsage(new TokenUsage(0, 0, 0));
        handler.onComplete();     // answer 为空

        verify(traceRecordService).mergeRunExtraData(eq("test-trace-id"), org.mockito.ArgumentMatchers.argThat(map -> {
            Map<String, Object> m = (Map<String, Object>) map;
            return ((Integer) m.get("citationTotal")) == 0
                    && ((Integer) m.get("citationValid")) == 0
                    && ((Integer) m.get("citationInvalid")) == 0
                    && ((Double) m.get("citationCoverage")) == 0.0;
        }));
    }
}
```

> **注意**：`handler.setTokenUsage(...)` 可能并非现有 public API；如 Read 后发现 `tokenUsage` 是 volatile field 由 `onTokenUsage(TokenUsage)` 回调 set，则改为 `handler.onTokenUsage(new TokenUsage(...))`。**执行前必须 Read 验证**，见下一 Step。

### - [ ] Step 4: 对齐 TokenUsage 写入 API

Run: `grep -nE "tokenUsage|onTokenUsage|setTokenUsage" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java | head -10`

如果找到 `public void onTokenUsage(TokenUsage usage)` 或同效写入方法，修改测试对应调用；如果 `tokenUsage` 是 callback 在外部直接 set 的 volatile 字段，可用 `org.springframework.test.util.ReflectionTestUtils.setField(handler, "tokenUsage", new TokenUsage(...))` 替代 `setTokenUsage(...)`。

### - [ ] Step 5: 跑测试确认 FAIL（驱动 T8）

Run: `mvn -pl bootstrap test -Dtest=StreamChatEventHandlerCitationTest -q 2>&1 | tail -15`
Expected: 3 个测试都失败——因为 `mergeCitationStatsIntoTrace` 尚未实现，`mergeRunExtraData` 永远不被调用。

### - [ ] Step 6: Commit

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerCitationTest.java
git commit -m "test(sources): StreamChatEventHandler citation merge test scaffolding [PR3]"
```

---

## Task 8: `StreamChatEventHandler.onComplete` — 实现 mergeCitationStatsIntoTrace

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`

### - [ ] Step 1: Read `onComplete` 现有插入点（第 180-196 行区域）

Run: `sed -n '180,200p' bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`
Expected: 看到 `updateTraceTokenUsage();` 下方紧跟 `saveEvaluationRecord(messageId);`。

### - [ ] Step 2: 在 `updateTraceTokenUsage()` 之后插入 merge 调用

把：

```java
        // 更新 Trace token 用量
        updateTraceTokenUsage();

        // 保存评测记录（异步）
        saveEvaluationRecord(messageId);
```

改为：

```java
        // 更新 Trace token 用量（overwrite 写，必须在 merge 之前）
        updateTraceTokenUsage();

        // 合并 citation 埋点到 Trace extra_data（merge 写，必须在 overwrite 之后）
        mergeCitationStatsIntoTrace();

        // 保存评测记录（异步）
        saveEvaluationRecord(messageId);
```

### - [ ] Step 3: 加私有方法 `mergeCitationStatsIntoTrace`

在 `updateTraceTokenUsage` 方法附近（类似区域）加：

```java
    /**
     * 合并 citation 埋点到 trace.extra_data（PR3）。
     * <p>
     * 顺序要求：必须在 {@link #updateTraceTokenUsage()}（overwrite 写）之后调用，
     * 否则 merge 结果会被后续 overwrite 清掉。
     * <p>
     * traceId 来自构造期缓存的 final 字段，不读 ThreadLocal。
     */
    private void mergeCitationStatsIntoTrace() {
        if (traceRecordService == null || StrUtil.isBlank(traceId)) {
            return;
        }
        Optional<List<SourceCard>> cardsOpt = cardsHolder.get();
        if (cardsOpt.isEmpty()) {
            return;
        }
        try {
            com.nageoffer.ai.ragent.rag.core.source.CitationStatsCollector.CitationStats stats =
                    com.nageoffer.ai.ragent.rag.core.source.CitationStatsCollector.scan(
                            answer.toString(), cardsOpt.get());
            traceRecordService.mergeRunExtraData(traceId, Map.of(
                    "citationTotal", stats.total(),
                    "citationValid", stats.valid(),
                    "citationInvalid", stats.invalid(),
                    "citationCoverage", stats.coverage()
            ));
        } catch (Exception e) {
            log.warn("合并引用统计到 trace.extra_data 失败", e);
        }
    }
```

（或把 import 提到顶部：`import com.nageoffer.ai.ragent.rag.core.source.CitationStatsCollector;`，然后用短名。推荐 imports 写法。）

### - [ ] Step 4: 跑 T7 测试确认 PASS

Run: `mvn -pl bootstrap clean test -Dtest=StreamChatEventHandlerCitationTest -q 2>&1 | tail -15`
Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`。

### - [ ] Step 5: 跑全量 sources 相关测试防回归

Run: `mvn -pl bootstrap test -Dtest='*Source*,*Citation*,RAGChatServiceImplSourcesTest' -q 2>&1 | tail -15`
Expected: 全绿。

### - [ ] Step 6: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java
git commit -m "feat(sources): mergeCitationStatsIntoTrace in onComplete (after token overwrite) [PR3]"
```

---

## Task 9: 前端依赖声明

**Files:**
- Modify: `frontend/package.json`

### - [ ] Step 1: Read 当前 package.json

Run: `cat frontend/package.json`
Expected: `dependencies` 含 `react-markdown / remark-gfm`；`devDependencies` 含 `vitest / jsdom`。

### - [ ] Step 2: 安装新依赖

```bash
cd frontend && npm install --save unist-util-visit@^5.0.0
cd frontend && npm install --save-dev @testing-library/react@^16.0.0 @testing-library/user-event@^14.5.0 unified@^11.0.0 remark-parse@^11.0.0
```

### - [ ] Step 3: 验证 package.json 和 lock

Run: `cd frontend && cat package.json | grep -E "unist-util-visit|@testing-library|unified|remark-parse"`
Expected: 全部 5 个包出现在相应位置（unist-util-visit 在 deps，其余 4 个在 devDeps）。

### - [ ] Step 4: 跑已有前端测试防回归

Run: `cd frontend && npm run test -- --run 2>&1 | tail -15`
Expected: PR2 已有测试（`useStreamResponse.test.ts` / `chatStore.test.ts`）全绿。

### - [ ] Step 5: Commit

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "chore(frontend): add unist-util-visit + testing-library + unified/remark-parse (dev) [PR3]"
```

---

## Task 10: `citationAst.ts` — 共享常量

**Files:**
- Create: `frontend/src/utils/citationAst.ts`

### - [ ] Step 1: 写文件

Create `frontend/src/utils/citationAst.ts`：

```typescript
/**
 * 共享常量：`remarkCitations` 插件需要的 AST 判定材料。
 *
 * PR3 Option X 下前端不再需要独立的 cited 提取路径，
 * 因此本文件不再导出 collectCitationIndexesFromTree。
 */

/**
 * 匹配 [^n] 的正则（g 标志支持 matcher.lastIndex 复用）。
 */
export const CITATION = /\[\^(\d+)]/g;

/**
 * 遍历 mdast 时需跳过的父节点类型集合。
 * 对应 remarkCitations 的硬约束：代码块 / 行内代码 / 链接 / 图片 / 链接引用里的
 * [^n] 字面量保持原样，不转 cite 节点。
 */
export const SKIP_PARENT_TYPES = new Set<string>([
  "inlineCode", "code", "link", "image", "linkReference",
]);
```

### - [ ] Step 2: 类型检查

Run: `cd frontend && npx tsc --noEmit 2>&1 | tail -10`
Expected: 零 error。

### - [ ] Step 3: Commit

```bash
git add frontend/src/utils/citationAst.ts
git commit -m "feat(sources): add citationAst.ts shared constants [PR3]"
```

---

## Task 11: `remarkCitations.test.ts` — 写失败测试（驱动 T12）

**Files:**
- Create: `frontend/src/utils/remarkCitations.test.ts`

### - [ ] Step 1: 写测试

Create `frontend/src/utils/remarkCitations.test.ts`：

```typescript
import { describe, it, expect } from "vitest";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import { remarkCitations } from "./remarkCitations";
import type { Root } from "mdast";

function transform(markdown: string): Root {
  const tree = unified()
    .use(remarkParse)
    .use(remarkGfm)
    .parse(markdown) as Root;
  // 插件是 () => (tree) => void | tree；同步变换
  const plugin = remarkCitations();
  (plugin as any)(tree);
  return tree;
}

function findAllCiteNodes(tree: any): Array<{ n?: number }> {
  const out: Array<{ n?: number }> = [];
  const walk = (node: any) => {
    if (!node) return;
    if (node.type === "cite") {
      out.push({ n: node?.data?.hProperties?.["data-n"] });
    }
    if (Array.isArray(node.children)) node.children.forEach(walk);
  };
  walk(tree);
  return out;
}

function hasNodeType(tree: any, type: string): boolean {
  let found = false;
  const walk = (node: any) => {
    if (!node || found) return;
    if (node.type === type) { found = true; return; }
    if (Array.isArray(node.children)) node.children.forEach(walk);
  };
  walk(tree);
  return found;
}

describe("remarkCitations plugin", () => {
  it("transforms text [^n] into cite nodes", () => {
    const tree = transform("见 [^1] 和 [^3]。");
    const cites = findAllCiteNodes(tree);
    expect(cites.map(c => c.n)).toEqual([1, 3]);
  });

  it("skips inlineCode: array[^2]` not transformed", () => {
    const tree = transform("代码 `arr[^2]`");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });

  it("skips fenced code block", () => {
    const tree = transform("```ts\nconst x = arr[^4];\n```");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });

  it("skips link text", () => {
    const tree = transform("[文档 [^5]](/x)");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });

  it("transforms footnoteReference into cite", () => {
    const tree = transform("见 [^1]\n\n[^1]: 定义内容");
    const cites = findAllCiteNodes(tree);
    expect(cites.length).toBeGreaterThanOrEqual(1);
    expect(cites.some(c => c.n === 1)).toBe(true);
  });

  it("removes footnoteDefinition nodes entirely", () => {
    const tree = transform("见 [^1]\n\n[^1]: 定义内容");
    expect(hasNodeType(tree, "footnoteDefinition")).toBe(false);
  });

  it("does not emit cite for [^n] inside footnoteDefinition body (definition deleted before text walk)", () => {
    // 定义体里的 [^2] 不应被产出任何 cite；因为 footnoteDefinition 整段被删
    const tree = transform("正文无引用\n\n[^1]: 定义里有 [^2]");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });
});
```

Run: `cd frontend && npm run test -- --run remarkCitations 2>&1 | tail -15`
Expected: FAIL — `remarkCitations` 未定义。

### - [ ] Step 2: Commit

```bash
git add frontend/src/utils/remarkCitations.test.ts
git commit -m "test(sources): remarkCitations AST plugin test scaffolding [PR3]"
```

---

## Task 12: `remarkCitations.ts` — AST 插件实现

**Files:**
- Create: `frontend/src/utils/remarkCitations.ts`

### - [ ] Step 1: 写实现

Create `frontend/src/utils/remarkCitations.ts`：

```typescript
import { visit, SKIP } from "unist-util-visit";
import type { Plugin } from "unified";
import type { Root, Parent, Text } from "mdast";
import { CITATION, SKIP_PARENT_TYPES } from "./citationAst";

/**
 * 三合一职责：
 *   1) footnoteReference → cite（remark-gfm 预解析产物）
 *   2) footnoteDefinition → 整段删除（避免底部渲染 GFM footnote block）
 *   3) text 节点的 [^n] → cite（主路径：LLM 只写 [^n] 不写定义块）
 *
 * 第 2 步的 splice 删除使得第 3 步 text visit 时定义子树已不存在，
 * 所以插件这里采用三段 visit 是安全的（历史坑：若在同一 tree 上跑两次
 * text-only visit 会让第二趟走进定义体误抓 [^n]，故保留此三段顺序）。
 *
 * 顺序硬固定：`remarkPlugins={[remarkGfm, remarkCitations]}`
 */
export const remarkCitations: Plugin<[], Root> = () => (tree) => {
  // 1) footnoteReference → cite
  visit(tree, "footnoteReference", (node: any) => {
    const n = parseInt(String(node.identifier ?? ""), 10);
    if (!Number.isFinite(n)) return;
    node.type = "cite";
    node.data = {
      ...(node.data ?? {}),
      hName: "cite",
      hProperties: { "data-n": n },
    };
  });

  // 2) footnoteDefinition → 删除
  visit(tree, "footnoteDefinition", (_node, index, parent) => {
    if (parent && typeof index === "number") {
      (parent as Parent).children.splice(index, 1);
      return [SKIP, index];
    }
  });

  // 3) text 节点 [^n] → cite
  visit(tree, "text", (node: Text, index, parent) => {
    if (!parent || typeof index !== "number") return;
    if (SKIP_PARENT_TYPES.has((parent as any).type)) return SKIP;
    const value = node.value;
    if (!value.includes("[^")) return;

    const parts: any[] = [];
    let lastEnd = 0;
    let match: RegExpExecArray | null;
    CITATION.lastIndex = 0;
    while ((match = CITATION.exec(value)) !== null) {
      if (match.index > lastEnd) {
        parts.push({ type: "text", value: value.slice(lastEnd, match.index) });
      }
      const n = parseInt(match[1], 10);
      parts.push({
        type: "cite",
        data: { hName: "cite", hProperties: { "data-n": n } },
      });
      lastEnd = match.index + match[0].length;
    }
    if (parts.length === 0) return;
    if (lastEnd < value.length) {
      parts.push({ type: "text", value: value.slice(lastEnd) });
    }
    (parent as Parent).children.splice(index, 1, ...parts);
    return [SKIP, index + parts.length];
  });
};
```

### - [ ] Step 2: 跑测试确认 PASS

Run: `cd frontend && npm run test -- --run remarkCitations 2>&1 | tail -20`
Expected: 7 个用例全绿。

### - [ ] Step 3: Commit

```bash
git add frontend/src/utils/remarkCitations.ts
git commit -m "feat(sources): remarkCitations 3-phase AST plugin [PR3]"
```

---

## Task 13: `<CitationBadge />` 组件 + 测试

**Files:**
- Create: `frontend/src/components/chat/CitationBadge.test.tsx`
- Create: `frontend/src/components/chat/CitationBadge.tsx`

### - [ ] Step 1: 写测试（失败）

Create `frontend/src/components/chat/CitationBadge.test.tsx`：

```typescript
import { describe, it, expect, vi } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CitationBadge } from "./CitationBadge";
import type { SourceCard } from "@/types";

const card1: SourceCard = {
  index: 1, docId: "d1", docName: "员工手册.pdf", kbId: "kb",
  topScore: 0.9, chunks: [],
};

describe("<CitationBadge />", () => {
  afterEach(cleanup);

  it("renders button when index exists in indexMap and triggers onClick(n)", async () => {
    const onClick = vi.fn();
    const indexMap = new Map<number, SourceCard>([[1, card1]]);
    render(<CitationBadge n={1} indexMap={indexMap} onClick={onClick} />);
    const btn = screen.getByRole("button");
    expect(btn).toBeDefined();
    expect(btn.getAttribute("title")).toBe("员工手册.pdf");
    await userEvent.click(btn);
    expect(onClick).toHaveBeenCalledWith(1);
  });

  it("renders plain <sup>[^n]</sup> (no button) when index is out of range", () => {
    const indexMap = new Map<number, SourceCard>([[1, card1]]);
    render(<CitationBadge n={99} indexMap={indexMap} onClick={vi.fn()} />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText("[^99]")).toBeDefined();
  });

  it("uses card.docName as title attribute", () => {
    const indexMap = new Map<number, SourceCard>([[1, card1]]);
    render(<CitationBadge n={1} indexMap={indexMap} onClick={vi.fn()} />);
    const btn = screen.getByRole("button");
    expect(btn.getAttribute("title")).toBe("员工手册.pdf");
  });
});
```

Run: `cd frontend && npm run test -- --run CitationBadge 2>&1 | tail -10`
Expected: FAIL — `CitationBadge` 未定义。

### - [ ] Step 2: 写实现

Create `frontend/src/components/chat/CitationBadge.tsx`：

```tsx
import type { SourceCard } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  n: number;
  indexMap: Map<number, SourceCard>;
  onClick: (n: number) => void;
}

export function CitationBadge({ n, indexMap, onClick }: Props) {
  const card = indexMap.get(n);
  // 越界编号（LLM 产出 [^99] 而 cards 只有 8）降级为纯文本 <sup>，不交互
  if (!card) {
    return <sup className="text-[11px] text-muted-foreground">[^{n}]</sup>;
  }
  return (
    <sup className="mx-0.5">
      <button
        type="button"
        onClick={() => onClick(n)}
        title={card.docName}
        aria-label={`引用 ${n}：${card.docName}`}
        className={cn(
          "inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-md",
          "bg-[#DBEAFE] px-1 text-[11px] font-semibold text-[#2563EB]",
          "transition-colors hover:bg-[#BFDBFE]",
          "dark:bg-[#1E3A8A] dark:text-[#93C5FD] dark:hover:bg-[#1E40AF]",
        )}
      >
        {n}
      </button>
    </sup>
  );
}
```

### - [ ] Step 3: 跑测试确认 PASS

Run: `cd frontend && npm run test -- --run CitationBadge 2>&1 | tail -10`
Expected: 3 个用例全绿。

### - [ ] Step 4: Commit

```bash
git add frontend/src/components/chat/CitationBadge.tsx frontend/src/components/chat/CitationBadge.test.tsx
git commit -m "feat(sources): CitationBadge component + 3 tests [PR3]"
```

---

## Task 14: `<Sources />` 组件 + 测试

**Files:**
- Create: `frontend/src/components/chat/Sources.test.tsx`
- Create: `frontend/src/components/chat/Sources.tsx`

### - [ ] Step 1: 写测试（失败）

Create `frontend/src/components/chat/Sources.test.tsx`：

```typescript
import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as React from "react";
import { Sources } from "./Sources";
import type { SourceCard, SourceChunk } from "@/types";

function chunk(id: string, idx: number, preview = "preview"): SourceChunk {
  return { chunkId: id, chunkIndex: idx, preview, score: 0.9 };
}
function card(index: number, docName = `D${index}`): SourceCard {
  return {
    index, docId: `d${index}`, docName, kbId: "kb", topScore: 0.9,
    chunks: [chunk(`c${index}_1`, 0), chunk(`c${index}_2`, 1)],
  };
}

describe("<Sources />", () => {
  afterEach(cleanup);

  it("renders null when cards is empty", () => {
    const { container } = render(<Sources cards={[]} highlightedIndex={null} />);
    expect(container.innerHTML).toBe("");
  });

  it("renders cards in backend-supplied order", () => {
    render(<Sources cards={[card(1), card(2), card(3)]} highlightedIndex={null} />);
    const headings = screen.getAllByText(/\[\^(1|2|3)\]/);
    expect(headings.map(h => h.textContent)).toEqual(["[^1]", "[^2]", "[^3]"]);
  });

  it("auto-expands the card matching highlightedIndex on mount", () => {
    // highlightedIndex=2 时，卡片 2 的 chunk preview 应可见（展开）
    render(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    // 卡片 2 的 chunk 展开时有 preview
    expect(screen.getAllByText("preview").length).toBeGreaterThan(0);
  });

  it("toggles card expansion on click", async () => {
    render(<Sources cards={[card(1)]} highlightedIndex={null} />);
    const headerBtn = screen.getByRole("button", { name: /员工|D1|[^1]/ });   // 首行按钮
    // 初始折叠：无 preview 文本
    expect(screen.queryAllByText("preview")).toHaveLength(0);
    await userEvent.click(headerBtn);
    expect(screen.getAllByText("preview").length).toBeGreaterThan(0);
    await userEvent.click(headerBtn);
    // 再点一次收起
    expect(screen.queryAllByText("preview")).toHaveLength(0);
  });

  it("applies ring highlight class to the card matching highlightedIndex", () => {
    const { container } = render(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    const cardEls = container.querySelectorAll("[class*='ring']");
    expect(cardEls.length).toBeGreaterThanOrEqual(1);
  });
});
```

Run: `cd frontend && npm run test -- --run Sources 2>&1 | tail -10`
Expected: FAIL — `Sources` 未定义。

### - [ ] Step 2: 写实现

Create `frontend/src/components/chat/Sources.tsx`：

```tsx
import * as React from "react";
import type { SourceCard } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  cards: SourceCard[];
  highlightedIndex: number | null;
}

export const Sources = React.forwardRef<HTMLDivElement, Props>(
  function Sources({ cards, highlightedIndex }, ref) {
    const [expanded, setExpanded] = React.useState<Set<number>>(new Set());

    // 高亮的卡片随 highlightedIndex 变化自动展开
    React.useEffect(() => {
      if (highlightedIndex == null) return;
      setExpanded(prev => new Set(prev).add(highlightedIndex));
    }, [highlightedIndex]);

    // Option X：忠实渲染后端 cards 原序；后端已按 topScore desc clip 到 max-cards。
    // 前端不做 partition / cited-never-clipped 保护（cited ≤ cards.length 必然成立）。
    const visible = cards;

    if (visible.length === 0) return null;

    return (
      <div ref={ref} className="mt-3 space-y-1.5">
        <div className="text-xs font-medium text-muted-foreground">来源</div>
        {visible.map(card => {
          const isExpanded = expanded.has(card.index);
          const isHighlighted = highlightedIndex === card.index;
          return (
            <div
              key={card.index}
              className={cn(
                "rounded-md border border-border bg-background p-2.5 transition-all",
                isHighlighted && "ring-2 ring-[#3B82F6] ring-offset-1",
              )}
            >
              <button
                type="button"
                onClick={() => setExpanded(prev => {
                  const next = new Set(prev);
                  if (next.has(card.index)) next.delete(card.index);
                  else next.add(card.index);
                  return next;
                })}
                className="flex w-full items-center gap-2 text-left text-sm"
              >
                <span className="font-mono text-xs text-[#2563EB]">[^{card.index}]</span>
                <span className="flex-1 truncate font-medium">{card.docName}</span>
                <span className="text-xs text-muted-foreground">
                  {card.topScore.toFixed(2)}
                </span>
              </button>
              {isExpanded && (
                <ul className="mt-2 space-y-1 text-xs text-muted-foreground">
                  {card.chunks.map(chunk => (
                    <li key={chunk.chunkId} className="border-l-2 border-border pl-2">
                      {chunk.preview}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          );
        })}
      </div>
    );
  }
);
```

### - [ ] Step 3: 跑测试确认 PASS

Run: `cd frontend && npm run test -- --run Sources 2>&1 | tail -10`
Expected: 5 个用例全绿。

### - [ ] Step 4: Commit

```bash
git add frontend/src/components/chat/Sources.tsx frontend/src/components/chat/Sources.test.tsx
git commit -m "feat(sources): Sources card region + 5 tests [PR3]"
```

---

## Task 15: `MarkdownRenderer` — hasSources gate + 测试

**Files:**
- Create: `frontend/src/components/chat/MarkdownRenderer.test.tsx`
- Modify: `frontend/src/components/chat/MarkdownRenderer.tsx`

### - [ ] Step 1: 写守 rollback 契约的测试（失败）

Create `frontend/src/components/chat/MarkdownRenderer.test.tsx`：

```typescript
import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import { MarkdownRenderer } from "./MarkdownRenderer";
import type { SourceCard } from "@/types";

const card1: SourceCard = {
  index: 1, docId: "d1", docName: "手册.pdf", kbId: "kb", topScore: 0.9, chunks: [],
};

describe("<MarkdownRenderer /> rollback contract", () => {
  afterEach(cleanup);

  it("when sources is undefined, [^1] remains as plain text (no <sup> superscript)", () => {
    const { container } = render(
      <MarkdownRenderer content={"前文[^1]后文"} />
    );
    // 不应出现 <sup> 结构
    expect(container.querySelector("sup")).toBeNull();
    // [^1] 作为纯文本保留（react-markdown 默认会转义为文本节点）
    expect(container.textContent).toContain("[^1]");
  });

  it("when sources is empty array, plugin is not mounted — same as undefined", () => {
    const { container } = render(
      <MarkdownRenderer content={"前文[^1]后文"} sources={[]} />
    );
    expect(container.querySelector("sup")).toBeNull();
    expect(container.textContent).toContain("[^1]");
  });

  it("when sources has matching card, [^1] becomes <CitationBadge>", () => {
    const { container } = render(
      <MarkdownRenderer content={"前文[^1]后文"} sources={[card1]} />
    );
    // 应出现 <sup> 且内部含 button
    const sup = container.querySelector("sup");
    expect(sup).not.toBeNull();
    expect(sup?.querySelector("button")).not.toBeNull();
  });
});
```

Run: `cd frontend && npm run test -- --run MarkdownRenderer 2>&1 | tail -10`
Expected: FAIL — 当前 MarkdownRenderer 还没加 sources/onCitationClick props，第 3 个测试必然失败。

### - [ ] Step 2: Read 当前 MarkdownRenderer

Run: `cat frontend/src/components/chat/MarkdownRenderer.tsx | head -30`
Expected: 看到 `interface MarkdownRendererProps { content: string; }` 和 `remarkPlugins={[remarkGfm]}`。

### - [ ] Step 3: 改造 props + hasSources gate

修改 `frontend/src/components/chat/MarkdownRenderer.tsx`：

在顶部 imports 添加：

```typescript
import { remarkCitations } from "@/utils/remarkCitations";
import { CitationBadge } from "@/components/chat/CitationBadge";
import type { SourceCard } from "@/types";
```

把 `interface MarkdownRendererProps` 改为：

```typescript
interface MarkdownRendererProps {
  content: string;
  sources?: SourceCard[];
  onCitationClick?: (n: number) => void;
}
```

把 `export function MarkdownRenderer({ content }: MarkdownRendererProps)` 改为：

```typescript
export function MarkdownRenderer({ content, sources, onCitationClick }: MarkdownRendererProps) {
  const theme = useThemeStore((state) => state.theme);

  const hasSources = Array.isArray(sources) && sources.length > 0;

  const indexMap = React.useMemo(
    () => new Map((sources ?? []).map(c => [c.index, c])),
    [sources]
  );

  const remarkPlugins = React.useMemo(
    () => (hasSources ? [remarkGfm, remarkCitations] : [remarkGfm]),
    [hasSources]
  );
```

然后在 `components={{ ... }}` 里加：

```typescript
        ...(hasSources ? {
          cite({ node }: any) {
            const raw = node?.properties?.["data-n"] ?? node?.properties?.dataN;
            const n = Number(raw);
            if (!Number.isFinite(n)) return null;
            return (
              <CitationBadge
                n={n}
                indexMap={indexMap}
                onClick={onCitationClick ?? (() => {})}
              />
            );
          },
        } : {}),
```

同时把 `remarkPlugins={[remarkGfm]}` 改为 `remarkPlugins={remarkPlugins}`。

> **具体插入点**：
> - `remarkPlugins` 变量在函数体顶部，紧跟 `indexMap` 之后
> - `components.cite` 通过展开运算符合并到原 `components` 对象：`components={{ ...原所有映射, ...(hasSources ? { cite: ... } : {}) }}`

### - [ ] Step 4: 跑测试确认 PASS

Run: `cd frontend && npm run test -- --run MarkdownRenderer 2>&1 | tail -15`
Expected: 3 个用例全绿。

### - [ ] Step 5: 跑全量前端测试防回归

Run: `cd frontend && npm run test -- --run 2>&1 | tail -20`
Expected: 全绿（含 PR2 已有用例 + PR3 新加测试）。

### - [ ] Step 6: 类型检查

Run: `cd frontend && npx tsc --noEmit 2>&1 | tail -10`
Expected: 零 error。

### - [ ] Step 7: Commit

```bash
git add frontend/src/components/chat/MarkdownRenderer.tsx frontend/src/components/chat/MarkdownRenderer.test.tsx
git commit -m "feat(sources): MarkdownRenderer hasSources gate + rollback contract tests [PR3]"
```

---

## Task 16: `MessageItem` — 集成 `<Sources />` + handle citation click

**Files:**
- Create: `frontend/src/components/chat/MessageItem.test.tsx`
- Modify: `frontend/src/components/chat/MessageItem.tsx`

### - [ ] Step 1: 写测试（失败）

Create `frontend/src/components/chat/MessageItem.test.tsx`：

```typescript
import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { render, screen, cleanup, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MessageItem } from "./MessageItem";
import type { Message, SourceCard } from "@/types";

// Mock useChatStore.sendMessage（MessageItem 依赖）
vi.mock("@/stores/chatStore", () => ({
  useChatStore: (selector: any) =>
    selector({ sendMessage: vi.fn() }),
}));

const card1: SourceCard = {
  index: 1, docId: "d1", docName: "员工手册.pdf", kbId: "kb",
  topScore: 0.9, chunks: [{ chunkId: "c1", chunkIndex: 0, preview: "员工入职流程...", score: 0.9 }],
};

const makeMessage = (overrides: Partial<Message> = {}): Message => ({
  id: "asst-1",
  role: "assistant",
  content: "第一句[^1]。",
  status: "done",
  sources: [card1],
  ...overrides,
});

describe("<MessageItem /> citation interaction", () => {
  beforeEach(() => {
    // jsdom 里 scrollIntoView 默认不存在
    Element.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("click on CitationBadge triggers scrollIntoView + highlight ring", async () => {
    const user = userEvent.setup();
    render(<MessageItem message={makeMessage()} isLast={true} />);
    // CitationBadge 的 button aria-label 含 "引用 1"
    const badge = screen.getByRole("button", { name: /引用\s*1/ });
    await user.click(badge);
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it("highlightedIndex clears after 1.5s via timer", () => {
    vi.useFakeTimers();
    const { container, rerender } = render(<MessageItem message={makeMessage()} isLast={true} />);
    // 通过 container 查询按钮（避免 userEvent 与 fake timers 冲突）
    const badge = container.querySelector('button[aria-label*="引用"]') as HTMLButtonElement;
    act(() => { badge.click(); });
    // 点击后立即有 ring
    expect(container.querySelector("[class*='ring-2']")).not.toBeNull();
    // 前进 1500ms
    act(() => { vi.advanceTimersByTime(1500); });
    expect(container.querySelector("[class*='ring-2']")).toBeNull();
  });

  it("renders Sources when message.sources has entries", () => {
    render(<MessageItem message={makeMessage()} isLast={true} />);
    expect(screen.getByText("来源")).toBeDefined();
  });

  it("does not render Sources when message.sources is undefined", () => {
    render(<MessageItem message={makeMessage({ sources: undefined })} isLast={true} />);
    expect(screen.queryByText("来源")).toBeNull();
  });
});
```

Run: `cd frontend && npm run test -- --run MessageItem 2>&1 | tail -15`
Expected: FAIL — MessageItem 尚未集成 Sources / handleCitationClick。

### - [ ] Step 2: Read 当前 MessageItem

Run: `cat frontend/src/components/chat/MessageItem.tsx`
Expected: 现有 `message.role === "user"` 分支 + `MarkdownRenderer content={message.content}` 调用。

### - [ ] Step 3: 改造 MessageItem

在顶部 imports 区追加：

```typescript
import { Sources } from "@/components/chat/Sources";
```

在 `MessageItem` 非 user 分支中（在 `const hasContent = message.content.trim().length > 0;` 之后）加：

```typescript
  const hasSources = message.role === "assistant"
    && Array.isArray(message.sources)
    && message.sources.length > 0;

  const sourcesRef = React.useRef<HTMLDivElement>(null);
  const [highlightedIndex, setHighlightedIndex] = React.useState<number | null>(null);
  const timerRef = React.useRef<number | null>(null);

  const handleCitationClick = React.useCallback((n: number) => {
    if (!message.sources?.some(c => c.index === n)) return;
    sourcesRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    setHighlightedIndex(n);
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      setHighlightedIndex(null);
      timerRef.current = null;
    }, 1500);
  }, [message.sources]);

  React.useEffect(() => () => {
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
  }, []);
```

把 `{hasContent ? <MarkdownRenderer content={message.content} /> : null}` 替换为：

```tsx
          {hasContent ? (
            <MarkdownRenderer
              content={message.content}
              sources={message.sources}
              onCitationClick={handleCitationClick}
            />
          ) : null}
          {hasSources ? (
            <Sources
              ref={sourcesRef}
              cards={message.sources!}
              highlightedIndex={highlightedIndex}
            />
          ) : null}
```

**插入位置**：`<Sources />` 放在 `MarkdownRenderer` 之后、`message.status === "error"` 之前（答案→来源→错误提示→反馈按钮→推荐问题 的顺序）。

### - [ ] Step 4: 跑测试确认 PASS

Run: `cd frontend && npm run test -- --run MessageItem 2>&1 | tail -20`
Expected: 4 个用例全绿。

### - [ ] Step 5: 跑全量前端测试防回归

Run: `cd frontend && npm run test -- --run 2>&1 | tail -25`
Expected: 所有 vitest 测试全绿（PR2 3 个 + PR3 新增 5 文件 ~20 个）。

### - [ ] Step 6: 类型检查

Run: `cd frontend && npx tsc --noEmit 2>&1 | tail -10`
Expected: 零 error。

### - [ ] Step 7: Commit

```bash
git add frontend/src/components/chat/MessageItem.tsx frontend/src/components/chat/MessageItem.test.tsx
git commit -m "feat(sources): MessageItem integrate Sources + citation click handler [PR3]"
```

---

## Task 17: 全量回归 + 手动冒烟准备

**Files:**
- （无代码改动，验证性任务）

### - [ ] Step 1: 跑全量后端测试

Run: `mvn -pl bootstrap test -q 2>&1 | tail -30`
Expected: PR3 新增测试全绿；既有测试不受影响。关注：
- `RAGPromptServiceCitationTest` 7/7 ✅
- `CitationStatsCollectorTest` 6/6 ✅
- `StreamChatEventHandlerCitationTest` 3/3 ✅
- `RAGChatServiceImplSourcesTest` 8/8 ✅（7 原有 + 1 SRC-6）
- `SourceCardBuilderTest` / `SourceCardsHolderTest` / `SourcesPayloadTest` 等 PR1/PR2 遗产全绿

若有预存在的 baseline 失败（`MilvusCollectionTests` / `InvoiceIndexDocumentTests` / `PgVectorStoreServiceTest.testChineseCharacterInsertion` / `IntentTreeServiceTests.initFromFactory` / `VectorTreeIntentClassifierTests`），按 CLAUDE.md 说明忽略。

### - [ ] Step 2: 跑全量前端测试

Run: `cd frontend && npm run test -- --run 2>&1 | tail -25`
Expected: 全绿。关注：
- `remarkCitations.test.ts` 7 ✅
- `CitationBadge.test.tsx` 3 ✅
- `Sources.test.tsx` 5 ✅
- `MarkdownRenderer.test.tsx` 3 ✅
- `MessageItem.test.tsx` 4 ✅
- PR2 `useStreamResponse.test.ts` 1 ✅
- PR2 `chatStore.test.ts` 4 ✅

### - [ ] Step 3: TypeScript 类型检查

Run: `cd frontend && npx tsc --noEmit 2>&1 | tail -10`
Expected: 零 error。

### - [ ] Step 4: Spotless 格式检查

Run: `mvn -pl bootstrap spotless:check -q 2>&1 | tail -10`
Expected: BUILD SUCCESS。若 FAIL：Run `mvn spotless:apply` → 重新 commit。

### - [ ] Step 5: 手动冒烟清单（记录在 PR 描述，不 commit）

准备按以下步骤验证（编辑 `application.yaml` 临时 `rag.sources.enabled: true`，本地启动后逐项勾选）：

1. 打开已有会话 → 历史消息**无** Sources 渲染、无报错（PR4 才持久化）
2. 新问 KB 命中问题 → 答案含 `[^1][^2]` 角标 + 下方 Sources 卡片
3. 点击 `[^1]` → 滚到卡片 + 高亮 1.5s + auto-expand chunk preview
4. 点击 Sources 卡片 → 就地展开/收起
5. 刷新 → sources 消失（PR4 才持久化）
6. 空检索 / MCP-Only / System-Only → 无 Sources 卡片、答案里也没 `[^n]`
7. DB: `t_rag_trace_run.extra_data` 最新一条含 `citationTotal / Valid / Invalid / Coverage` 字段
8. `rag.sources.enabled: false` → UI 完全回到 PR2 末态
9. （核心 rollback 契约）关 flag 后，如果手动把答案复制一段 `见 [^1]` 文本到 chat 渲染处，应保持纯文本、无 `<sup>`（间接通过 `MarkdownRenderer.test.tsx` 已自动锁定）

### - [ ] Step 6: 最终无 commit

本 task 纯验证，不产 commit。

---

## Task 18: backlog 补 SRC-9 条目

**Files:**
- Modify: `docs/dev/followup/backlog.md`

### - [ ] Step 1: Read 现有 backlog

Run: `tail -20 docs/dev/followup/backlog.md`
Expected: 看到 SRC-1~8 + 尾部 `🗂️ 引用` 节。

### - [ ] Step 2: 在 `🗂️ 引用` 之前插入 SRC-9

在 `docs/dev/followup/backlog.md` 中，`## 🗂️ 引用` 这一行之前追加：

```markdown
### SRC-9. `updateTraceTokenUsage` 的 overwrite 写法是 latent 坑

**位置**：`StreamChatEventHandler.updateTraceTokenUsage`
**症状**：走 `updateRunExtraData(traceId, String)` 覆盖写。任何在它**之前**已合入 `extra_data` 的字段都会被它清掉。
**当前规避**：PR3 的 `mergeCitationStatsIntoTrace` 必须在 `updateTraceTokenUsage` **之后**执行；`RAGConfigProperties.suggestionsEnabled=false` 场景下无 `mergeSuggestionsIntoTrace` 抢跑。
**根治**：把 `updateTraceTokenUsage` 改为 `mergeRunExtraData(traceId, Map.of("promptTokens", ..., "completionTokens", ..., "totalTokens", ...))`；非 PR3 scope。
**优先级**：P3（latent，顺序依赖已规避；但将来任何新的"先合后写"字段都可能再踩）。
```

并在文件末尾"本轮新增"类引用条目追加一条说明（可选）：

```markdown
- 2026-04-22 新增 SRC-9：PR3 Answer Sources 合并前记录的 trace.extra_data overwrite 隐患
```

### - [ ] Step 3: Commit

```bash
git add docs/dev/followup/backlog.md
git commit -m "docs(backlog): add SRC-9 — updateTraceTokenUsage overwrite latent risk [PR3]"
```

---

## 执行完 18 个 task 后

**最后一步（不写代码）**：生成 PR 摘要、准备 push 分支。

- 分支：`feature/answer-sources-pr3`
- PR base：`main`（**不走 stacked PR**，PR1+PR2 已合入 main）
- 预期 commit 数：18（含 spec commit `2c1a75c`；本 plan 追加 17 个 feat/test/chore/docs commit）
- flag 仍默认 off，用户不可见

PR body 模板：

```
## Summary
- PR3 for Answer Sources: Prompt citation mode + remarkCitations AST plugin + CitationBadge + Sources UI + citation quality metrics
- Feature flag `rag.sources.enabled=false` unchanged — zero user-visible change
- Architecture invariants from PR2 preserved: orchestrator decides / handler emits / service formats; zero ThreadLocal additions; `indexSet.contains` semantics for index validity
- Rollback contract: `MarkdownRenderer` gates both `remarkCitations` plugin and `cite` component mapping on `hasSources`, so flag-off answers render byte-identical to PR2

## Test plan
- [ ] Backend unit: `mvn -pl bootstrap test -Dtest='RAGPromptServiceCitationTest,CitationStatsCollectorTest,StreamChatEventHandlerCitationTest,RAGChatServiceImplSourcesTest' -q` — 24 new + 7 existing passing
- [ ] Frontend unit: `cd frontend && npm run test -- --run` — ~22 tests passing
- [ ] Type check: `cd frontend && npx tsc --noEmit` — zero errors
- [ ] Spotless: `mvn -pl bootstrap spotless:check -q` — BUILD SUCCESS
- [ ] Manual smoke (flag on): answer contains `[^n]` badges, Sources card below, click scrolls + highlights 1.5s, refresh clears (PR4 persists)
- [ ] Manual smoke (flag off): UI byte-identical to PR2; no `[^n]` rendering in answers

## Links
- Spec: `docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md`
- Plan: `docs/superpowers/plans/2026-04-22-answer-sources-pr3-implementation.md`
- PR1+PR2 记录: `log/dev_log/2026-04-21-answer-sources-pr1-pr2.md`
```

---

## Self-Review

### 1. Spec coverage

| Spec 节 | 实现 task |
|---|---|
| § 2.1 PromptContext 扩字段 | T1 |
| § 2.2 buildStructuredMessages 内部派生 | T2 (test) + T3 (impl) |
| § 2.3 appendCitationRule FINAL 文案 | T3 Step 4（N==1 / N>=2 分支 + cleanupPrompt） |
| § 2.4 buildCitationEvidence 全文回收 | T3 Step 3（锁定 `getOrDefault(..., preview)` fallback） |
| § 2.5 streamLLMResponse 加 cards 参数 | T4 |
| § 2.6 mergeCitationStatsIntoTrace | T7 (test) + T8 (impl) |
| § 2.7 CitationStatsCollector | T6 |
| § 2.8 SRC-6 测试 | T5 |
| § 3.2 citationAst.ts | T10 |
| § 3.3 remarkCitations.ts | T11 (test) + T12 (impl) |
| § 3.4 CitationBadge | T13 |
| § 3.5 Sources | T14 |
| § 3.6 MarkdownRenderer hasSources gate | T15 |
| § 3.7 MessageItem state ownership | T16 |
| § 3.8 前端依赖 | T9 |
| § 4 测试清单 | T2/T5/T6/T7/T11/T13/T14/T15/T16 对齐 |
| § 8 backlog SRC-9 | T18 |

全覆盖。

### 2. Placeholder scan

无 `TBD` / `TODO` / `implement later`。所有代码 step 含完整代码块。唯一带条件分支的是 T7 Step 4（TokenUsage 写入 API 验证），但已写明具体 fallback 方案。

### 3. Type consistency

- `SourceCard` 字段 `int index / String docId / String docName / String kbId / float topScore / List<SourceChunk> chunks` —— 在 T2/T3/T6/T13/T14/T15/T16 各 task 中一致引用
- `CitationStats` record `(int total, int valid, int invalid, double coverage)` —— 在 T6/T7/T8 一致
- 前端 `SourceCard.kbName` 不出现（PR2 DTO 无 kbName，SRC-5 延期） —— 在 T13/T14/T15 测试与组件中一致不用
- `MarkdownRendererProps.sources?: SourceCard[]` / `onCitationClick?: (n: number) => void` —— T15 定义 / T16 使用，一致
- `SourcesProps.highlightedIndex: number | null` / `cards: SourceCard[]` —— T14 定义 / T16 传入，一致
- `handleCitationClick(n: number)` —— T16 定义，签名与 `MarkdownRenderer.onCitationClick` 匹配

### 4. 关键 invariants 散见 task 的约束已被显式编码

- "orchestrator 决策 / handler 机械发射"：T4 保留三层闸门逻辑、T8 mergeCitationStatsIntoTrace 无业务分支
- "零 ThreadLocal 新增"：T8 明确 traceId 来自 final 字段、T7 测试 setup 依赖 `RagTraceContext.setTraceId` 是为了 satisfy handler 构造
- `indexSet.contains(n)` 契约：T6 Step 2 代码实现 + T6 Step 1 测试 4（cards index 非连续锁语义）
- rollback 契约：T15 专门守这条，3 个测试全部围绕 `sources=undefined/empty` 场景

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-22-answer-sources-pr3-implementation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - 我 dispatch 一个 fresh subagent 执行每个 task，每 task 结束做两阶段 review（spec compliance + code quality），节奏快。适用于 18 个 task 的场景，可以并行化部分无依赖 task（虽然本 plan 大多有 TDD 顺序依赖）。

**2. Inline Execution** - 我在本 session 里按 `superpowers:executing-plans` 逐个 task 执行，每 3-4 个 task 让你 checkpoint 一次。适用于你想更紧密控制节奏的场景。

**哪种方式？**
