# Answer Sources PR3 设计 — Prompt 改造 + 前端引用渲染 + 引用质量埋点

> 2026-04-22 · v1
>
> **本文档范围**：Answer Sources 功能 5 个 PR 中的 **PR3**。承接 PR1+PR2（已合入 `main`，见 `log/dev_log/2026-04-21-answer-sources-pr1-pr2.md`）。PR3 在 feature flag `rag.sources.enabled=false` 下**仍静默**，零用户可见变化；PR5 才翻 true。
>
> **上游 v1 spec**：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`
>
> **PR2 设计**：`docs/superpowers/specs/2026-04-21-answer-sources-pr2-design.md`
>
> **基线分支**：`feature/answer-sources-pr3` 从 `main` 拉（PR1+PR2 已合入）

---

## § 0 与上游 v1 spec 的差异摘要（PR3 边界收紧）

| 条目 | v1 spec 原文 | 本文档收紧 | 原因 |
|---|---|---|---|
| `buildContextWithCitations` 输入 | "按 docId 分组注入 `[^1]《文档名》...`" | 签名为 `buildCitationEvidence(PromptContext ctx)`；正文从 `ctx.intentChunks` 回收 `RetrievedChunk.text`，**不**用 `SourceChunk.preview` | `preview` 是 PR2 为 SSE/UI 定义的 200 字截断字段；用它喂 LLM 会悄悄降级 `kbContext` 的 evidence 信息量 |
| context 里 "（来自：kbName）" | 含 kbName | **去掉整个括号**，只 `[^1]《docName》` | PR2 数据面无 kbName（SRC-5 延期）；填 kbId 丑陋、保留空括号更糟；未来 SRC-5 推进时再 additive 补上 |
| 引用规则文案 | 6 行草稿 | 收紧为 7 行，末段改为**动态白名单**：`本次可用引用编号仅限 {RANGE}` 其中 `RANGE` 按 `cards.size()` 分 1 / ≥2 两种渲染 | 把抽象"禁止编造"收紧成显式白名单，压低 `[^99]` 越界输出 |
| 引用规则注入位置 | 未明确 | **append** 到 `buildSystemPrompt()` 结果尾部，之后整体 `cleanupPrompt` | 现有 `RAG_ENTERPRISE_PROMPT` 已规定基本义务；引用规则是补充而非覆盖；append 符合阅读顺序 |
| cards 如何流入 `RAGPromptService` | "新方法 `buildContextWithCitations(cards, ctx)`" | `PromptContext` 扩 `cards` 字段；`buildStructuredMessages` 签名零变化，内部**局部派生** `resolvedSystemPrompt` / `resolvedKbEvidence` | "证据如何呈现给模型"的格式化职责留在 prompt service；orchestrator 只做是否传 cards 的决策；`PromptContext` 本就是"所有 prompt 输入"的聚合包，扩字段是演进、非新概念 |
| Scene 判定 | 未明确 | `plan(ctx)` / `hasKb()` 继续锚定原 `ctx.kbContext`，**cards 不参与 scene 判定**；异常组合 "cards 非空 + kbContext 空" 不反推 scene；citationMode 守护为 `cards 非空 && ctx.hasKb()` | 保持 scene 选择语义不被 sources 路径污染 |
| 引用质量埋点正则 | "`\[\^(\d+)\]`，可接受轻微偏差" | 采纳 spec 宽容口径；`valid` 判定用 **`indexSet.contains(n)`**（index 集合成员性）而非 `1..N` range | 对齐 spec 前端"`indexMap.get(n)` 不用 `cards[n-1]`"的契约；未来支持非连续 index（如 `[^n.m]`）时语义不动 |
| 前端 `[^n]` 边界 | "走 remark AST，不做字符串 preprocess" | remark 插件 A3 方案：三合一（text 节点 / footnoteReference / footnoteDefinition） | `react-markdown@9` 的 `components` 映射的是 HTML tag name 而非 mdast 节点名，原 spec 里的"直接 components 覆盖 footnoteReference"行不通；必须在插件里先转节点 |
| `<Sources />` 展示规则 | "被引用卡片强制可见 / max-cards clip" | 前端**忠实渲染**后端 `cards` 原序；后端 `SourceCardBuilder` 已按 `topScore desc` clip 到 `rag.sources.max-cards=8`，前端不再做 partition / 不做 cited-never-clipped 保护 | 产品约束"顶多 8 张卡"是服务端事实（服务 prompt 成本 / SSE 帧 / LLM 注意力 / UI 布局）；前端再做一层"cited 永不裁剪"是防御不存在的威胁（cited ≤ cards.length ≤ 8 必然成立），反而需要虚构测试；简化后 PR3 scope 更干净 |
| `CitationBadge` tooltip | 未明确 | **仅 `docName`**；越界编号（`indexMap` miss）降级为纯 `<sup>[^n]</sup>` 文本、无交互 | tooltip 主职责是"确认点的是哪份文档"，不承载二级信息；KB 名 / score / preview 属 `<Sources />` 职责；触屏上 tooltip 不是稳定主交互 |
| `remarkCitations` 挂载时机 | v1 spec 未明确 | `MarkdownRenderer` 按 `hasSources`（`sources?.length > 0`）gate plugin 与 `components.cite` 映射；`hasSources=false` 时 plugin 不挂、cite 不映射 | flag off 或该消息无 sources 时，若 plugin 无条件挂载，答案里字面 `[^1]`（教程示例 / 幻觉 marker）会被渲染为 `<sup>[^1]</sup>` 上标，破坏"flag off 完全等同 PR2"的回滚契约 |
| `/rag/v3/chat` 集成测 | v1 spec 提到 | **PR3 不新建**集成测 | 代码库当前无 `/rag/v3/chat` happy-path 端到端基线可复用；PR3 以后端单测 + handler/service 单测 + 手动冒烟覆盖；若未来需要锁 SSE 帧序再单独立项 |

---

## § 1 架构与数据流

### 继承自 PR2 的基线（PR3 不变）

```
RAGChatServiceImpl.chat
 ├─ 三层闸门决策 cards 推不推
 ├─ cardsHolder.trySet(cards)
 ├─ callback.emitSources(..)
 └─ streamLLMResponse → promptBuilder.buildStructuredMessages → llmService.streamChat

StreamChatEventHandler
 ├─ initialize / onContent / emitSources
 └─ onComplete → FINISH → SUGGESTIONS → DONE
```

### PR3 的 delta（只增这些）

```
RAGChatServiceImpl.streamLLMResponse
 └─ 多接收 List<SourceCard> cards 参数
    └─ PromptContext.builder().cards(cards).build()

RAGPromptService.buildStructuredMessages（签名不变）
 ├─ plan() / hasKb() 继续用原 kbContext 决定 scene  （不动）
 ├─ 局部派生 resolvedKbEvidence:
 │   ├─ citationMode → buildCitationEvidence(ctx)
 │   └─ 否则        → ctx.getKbContext()
 └─ 局部派生 resolvedSystemPrompt:
     ├─ citationMode → appendCitationRule(base, cards)
     └─ 否则        → base
 其中 citationMode = CollUtil.isNotEmpty(ctx.getCards()) && ctx.hasKb()

StreamChatEventHandler.onComplete
 ├─ updateTraceTokenUsage()         （既有，overwrite 写）
 └─ mergeCitationStatsIntoTrace()   （新，merge 写）  ← 顺序不可颠倒
     └─ cardsHolder.get() 非空时：CitationStatsCollector.scan(answer, cards)
        → traceRecordService.mergeRunExtraData(traceId, statsMap)
     ※ traceId 用构造期缓存的 final 字段，零 ThreadLocal 新增

前端
 ├─ utils/citationAst.ts            （新，轻量共享常量：SKIP_PARENT_TYPES + CITATION 正则）
 ├─ utils/remarkCitations.ts        （新，三合一：text / footnoteReference → cite，footnoteDefinition → 删）
 ├─ components/chat/CitationBadge.tsx   （新）
 ├─ components/chat/Sources.tsx         （新，忠实渲染后端 cards 原序）
 ├─ components/chat/MarkdownRenderer.tsx
 │   ├─ hasSources = sources?.length > 0
 │   ├─ remarkPlugins = hasSources ? [remarkGfm, remarkCitations] : [remarkGfm]   （无 sources 不挂 plugin，守 rollback 契约）
 │   └─ components.cite → <CitationBadge>（仅 hasSources 时映射）
 └─ components/chat/MessageItem.tsx
     ├─ 管理 highlightedIndex / timerRef / sourcesRef
     └─ message.sources?.length > 0 → <Sources ... ref={sourcesRef} />

package.json
 ├─ dependencies 新增: unist-util-visit@^5
 └─ devDependencies 新增: @testing-library/react, @testing-library/user-event, unified@^11, remark-parse@^11
    （测试 parse 用；生产不引入 unified/remark-parse，react-markdown 自己 parse）
```

### 依赖方向（严格单向，PR3 保持）

- **rag 域内部**：`RAGPromptService` → `rag.dto.SourceCard`；`StreamChatEventHandler` → `rag.core.source.CitationStatsCollector`（新）→ `rag.dto.SourceCard`
- **无跨域新增依赖**；**无 framework 改动**；**无 ThreadLocal 新增**
- **前端新增 1 个直接依赖** `unist-util-visit@^5`（原 `remark-gfm` transitive，显式提升避免未来升级断链）

### SSE 契约

**完全不变**。PR3 不动 SSE 事件种类 / 顺序 / 载荷。`citationStats` 是 server-side 埋点（落 `t_rag_trace_run.extra_data`），**不**走 SSE。

### Feature flag

`rag.sources.enabled` 仍默认 `false`：

- off → Prompt 改造跳过、埋点跳过、前端 `message.sources` undefined → `MarkdownRenderer` 按 `hasSources=false` **不**挂 `remarkCitations` 也**不**映射 `cite` → 答案里字面 `[^n]`（教程 / 幻觉 marker）保持为纯文本，与 PR2 末态**字节级等价**；`<Sources />` 不渲染
- on → 端到端可用；但**持久化仍缺，PR4 才补**，刷新后 sources 消失（符合预期）

---

## § 2 后端改动清单

### 2.1 `PromptContext` 扩字段

```java
// bootstrap/rag/core/prompt/PromptContext.java
@Data @Builder
public class PromptContext {
    // ...existing fields（question / mcpContext / kbContext / mcpIntents / kbIntents / intentChunks）保持不变
    private List<SourceCard> cards;   // 新；默认 null；MCP_ONLY / 空检索等路径下为 null
}
```

`hasMcp()` / `hasKb()` 方法**不动**——继续锚定 `mcpContext` / `kbContext` 字面，`cards` 不参与 scene 判定。

### 2.2 `RAGPromptService.buildStructuredMessages`（签名不变，内部局部派生）

```java
public List<ChatMessage> buildStructuredMessages(PromptContext ctx,
                                                 List<ChatMessage> history,
                                                 String question,
                                                 List<String> subQuestions) {
    boolean citationMode = CollUtil.isNotEmpty(ctx.getCards()) && ctx.hasKb();

    String resolvedSystemPrompt = buildSystemPrompt(ctx);
    String resolvedKbEvidence   = ctx.getKbContext();

    if (citationMode) {
        resolvedKbEvidence   = buildCitationEvidence(ctx);
        resolvedSystemPrompt = appendCitationRule(resolvedSystemPrompt, ctx.getCards());
    }

    // 后续拼装使用 resolvedSystemPrompt / resolvedKbEvidence，其余逻辑不动
    // （system message、mcpContext、resolvedKbEvidence、history、user 的现有组装顺序完全保留）
    ...
}

// 私有：从 cards + intentChunks 构造 citation 证据（§ 2.4）
private String buildCitationEvidence(PromptContext ctx) { ... }

// 私有：在 base 末尾追加引用规则块，再 cleanupPrompt（§ 2.3）
private String appendCitationRule(String base, List<SourceCard> cards) { ... }
```

**约束**：

- `ctx` 作为入参**不修改**；所有衍生状态走局部变量
- `cards == null` 或 `isEmpty()` 时与 PR2 行为**完全等价**（回归锁）
- `cards` 非空但 `ctx.kbContext` 为空的异常组合：`citationMode=false`，走原路径，prompt 不自相矛盾

### 2.3 `appendCitationRule` 与引用规则文案 FINAL 版

```java
private String appendCitationRule(String base, List<SourceCard> cards) {
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

### 2.4 `buildCitationEvidence` — 正文从 `intentChunks` 回收全文

```java
private String buildCitationEvidence(PromptContext ctx) {
    // chunkId → 全文索引；同一 chunkId 可能在多个意图 key 下，put 幂等覆盖（值相同）
    Map<String, String> chunkTextById = new HashMap<>();
    Map<String, List<RetrievedChunk>> intentChunks = ctx.getIntentChunks();
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
    for (SourceCard card : ctx.getCards()) {
        sb.append('[').append('^').append(card.getIndex()).append(']')
          .append('《').append(card.getDocName()).append('》').append('\n');
        int i = 1;
        for (SourceChunk chunk : card.getChunks()) {
            String body = chunkTextById.getOrDefault(chunk.getChunkId(), chunk.getPreview());
            sb.append("—— 片段 ").append(i++).append("：").append(body).append('\n');
        }
        sb.append('\n');
    }
    return sb.toString().stripTrailing();
}
```

**关键约束**：

- `cards` 只定义"文档顺序 + 文档号 + 卡片内 chunk 顺序"
- 正文用 `RetrievedChunk.getText()`——**不**把 UI preview 当作 LLM evidence
- `getOrDefault(..., preview)`：lookup miss 时回退，极少数，避免整个 card 失效

### 2.5 `RAGChatServiceImpl.streamLLMResponse` 多接一个参数

```java
private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                   IntentGroup intentGroup, List<ChatMessage> history,
                                                   boolean deepThinking, StreamCallback callback,
                                                   List<SourceCard> cards) {   // 新参数
    PromptContext promptContext = PromptContext.builder()
            .question(rewriteResult.rewrittenQuestion())
            .mcpContext(ctx.getMcpContext())
            .kbContext(ctx.getKbContext())
            .mcpIntents(intentGroup.mcpIntents())
            .kbIntents(intentGroup.kbIntents())
            .intentChunks(ctx.getIntentChunks())
            .cards(cards)                                // 新
            .build();
    // 其余不变
}
```

`chat(...)` 里：在三层闸门之后、`streamLLMResponse(...)` 调用前，把 gate-3 中局部变量 `cards`（或 null）传下去。

### 2.6 `StreamChatEventHandler.onComplete` 新增埋点块

**插入位置**：`updateTraceTokenUsage()`（overwrite）**之后**、`saveEvaluationRecord()` 之前。

```java
public void onComplete() {
    if (taskManager.isCancelled(taskId)) return;
    String messageId = memoryService.append(...);

    updateTraceTokenUsage();           // 既有：走 updateRunExtraData(String) — overwrite
    mergeCitationStatsIntoTrace();     // 新：走 mergeRunExtraData(Map) — merge on top

    saveEvaluationRecord(messageId);
    // FINISH / SUGGESTIONS / DONE 流程不变
}

private void mergeCitationStatsIntoTrace() {
    if (traceRecordService == null || StrUtil.isBlank(traceId)) return;   // traceId 是构造期缓存的 final 字段
    Optional<List<SourceCard>> cardsOpt = cardsHolder.get();
    if (cardsOpt.isEmpty()) return;
    CitationStatsCollector.CitationStats stats =
            CitationStatsCollector.scan(answer.toString(), cardsOpt.get());
    try {
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

**关键约束**：

- `updateTraceTokenUsage`（overwrite）与 `mergeCitationStatsIntoTrace`（merge）**顺序不可颠倒**；颠倒会让 merge 结果被后续 overwrite 清掉
- `traceId` 来源于构造期缓存的 final 字段，异步段**零 ThreadLocal 读取**
- `cardsHolder.get()` 为 PR2 立的 set-once CAS 只读快照接口

### 2.7 新增 `CitationStatsCollector` 纯工具类

```java
// bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/source/CitationStatsCollector.java
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

**关键约束**：

- `valid` 用 `validIndexes.contains(n)`——对齐 PR2 "index 非位置"契约；未来支持非连续 index 时语义不动
- **不**预剥离代码块 / inline code（spec 宽容口径）
- 空 answer / 空 cards → `(0,0,0,0.0)`，不除零
- SENTENCE 粗切漏最后无句号尾段，spec 已授权接受，不做补偿；若数据显示分布异常再调

### 2.8 SRC-6 独立 test-only commit（≤25 行）

在 `RAGChatServiceImplSourcesTest` 末尾追加（签名、service 字段名、helper 调用风格均对齐**该测试类**现有用例，参见同文件 L203 附近 `service.streamChat("q", "cid-1", null, false, emitter)` 模式）：

```java
@Test
void streamChat_whenGuidanceClarificationPrompt_thenSkipSourcesEntirely() {
    // guidance 返回 prompt（GuidanceDecision.prompt 只接受一个 String）
    when(guidanceService.detectAmbiguity(any(), any()))
            .thenReturn(GuidanceDecision.prompt("请补充您的问题细节"));

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

Commit message：`test(sources): SRC-6 — lock clarification branch skip sources [PR3]`。

> **对齐锚点**：上述 `service` / `callback` / `emitter` 字段名与 `@InjectMocks` / `@Mock` 注解均**复用**该测试类已有 setup（见 `RAGChatServiceImplSourcesTest.java` L91 起），不需要新增 mock。`GuidanceDecision.prompt(String)` 签名由 `bootstrap/rag/core/guidance/GuidanceDecision.java` 的 `public static GuidanceDecision prompt(String prompt)` 确认。

---

## § 3 前端改动清单

### 3.1 文件清单

**新增 4 文件**：`utils/citationAst.ts` / `utils/remarkCitations.ts` / `components/chat/CitationBadge.tsx` / `components/chat/Sources.tsx`

**修改 3 文件**：`components/chat/MarkdownRenderer.tsx` / `components/chat/MessageItem.tsx` / `package.json`

**不动**：`types/index.ts`（PR2 已定义 SourceCard/SourceChunk/SourcesPayload + `Message.sources?`）、`stores/chatStore.ts`（PR2 已接 `onSources`）、`hooks/useStreamResponse.ts`（PR2 已路 `case "sources"`）、`services/sessionService.ts`（PR4 才补历史映射）

### 3.2 `citationAst.ts` — 轻量共享常量

仅存放 `remarkCitations` 需要的两个共享 AST 常量。`Option X` 下 `<Sources />` 不再依赖 cited 提取，因此不再提供 `collectCitationIndexesFromTree` 收集器。

```typescript
// frontend/src/utils/citationAst.ts

/**
 * 匹配 [^n] 的正则（g 标志支持 matcher.lastIndex 复用）。
 */
export const CITATION = /\[\^(\d+)]/g;

/**
 * 遍历 mdast 时需跳过的父节点类型集合。
 * 对应 remarkCitations 的硬约束：代码块 / 行内代码 / 链接 / 图片 / 链接引用里的
 * [^n] 字面量保持原样，不转 cite 节点。
 */
export const SKIP_PARENT_TYPES = new Set([
  "inlineCode", "code", "link", "image", "linkReference",
]);
```

> **删除说明**：上一版 spec 的 `collectCitationIndexesFromTree` / `extractCitedIndexes.ts` / `MessageItem.citedIndexes` useMemo **均已从 PR3 scope 中删除**。`unified` / `remark-parse` 也从生产 `dependencies` 中移除，改为仅在 `devDependencies` 用于 `remarkCitations.test.ts` parse pipeline（见 § 3.8）。原因见 § 0 diff 表中 `<Sources />` 展示规则一行——Option X 下前端不再需要 cited 集合做 visibility / ordering 决策。

### 3.3 `remarkCitations.ts` — 渲染插件

```typescript
// frontend/src/utils/remarkCitations.ts
import { visit, SKIP } from "unist-util-visit";
import type { Plugin } from "unified";
import type { Root, Parent, Text } from "mdast";
import { CITATION, SKIP_PARENT_TYPES } from "./citationAst";

/**
 * 三合一职责：
 *   1) footnoteReference → cite（remark-gfm 预解析产物）
 *   2) footnoteDefinition → 删除（避免底部渲染 GFM footnote block）
 *   3) text 节点的 [^n] → cite（主路径：LLM 只写 [^n] 不写定义块）
 *
 * 第 2 步的 splice 删除使得第 3 步 text visit 时定义子树已不存在，
 * 所以插件这里采用三段 visit 是安全的。
 *
 * 顺序硬固定：remarkPlugins={[remarkGfm, remarkCitations]}
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

### 3.4 `<CitationBadge />`

```tsx
// frontend/src/components/chat/CitationBadge.tsx
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

**约束**：

- 越界时不抛错、不吞文本——降级为 `<sup>[^99]</sup>`
- tooltip 用原生 `title`，最小实现；未来可升级到 `@/components/ui/tooltip` Radix 版
- 视觉复用 MessageItem "深度思考"区域蓝色系 token

### 3.5 `<Sources />`

```tsx
// frontend/src/components/chat/Sources.tsx
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

### 3.6 `MarkdownRenderer.tsx` 集成（hasSources gate）

```tsx
// 顶部 import 增加
import remarkGfm from "remark-gfm";
import { remarkCitations } from "@/utils/remarkCitations";
import { CitationBadge } from "@/components/chat/CitationBadge";
import type { SourceCard } from "@/types";

interface MarkdownRendererProps {
  content: string;
  sources?: SourceCard[];
  onCitationClick?: (n: number) => void;
}

export function MarkdownRenderer({ content, sources, onCitationClick }: MarkdownRendererProps) {
  const hasSources = Array.isArray(sources) && sources.length > 0;

  const indexMap = React.useMemo(
    () => new Map((sources ?? []).map(c => [c.index, c])),
    [sources]
  );

  // plugin 链：无 sources 时不挂 remarkCitations，保持 PR2 末态字节级等价
  const remarkPlugins = React.useMemo(
    () => (hasSources ? [remarkGfm, remarkCitations] : [remarkGfm]),
    [hasSources]
  );

  // components：无 sources 时不映射 cite；LLM 即便意外写 [^n] 也保持原文本
  const components = React.useMemo(() => ({
    // ...既有 code / img / a / table / blockquote / ul / ol 保持不动
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
  }), [hasSources, indexMap, onCitationClick]);

  return (
    <ReactMarkdown remarkPlugins={remarkPlugins} components={components} /* 其余既有 className 不动 */>
      {content}
    </ReactMarkdown>
  );
}
```

**约束**：

- `hasSources` 单一闸门同时 gate plugin 挂载与 `cite` 映射——两者保持一致，不出现"plugin 跑了但无 cite 组件"的虚链
- MarkdownRenderer 的 props 全部可选——向后兼容不关心 sources 的调用场景
- `components.cite` 的 key 必须小写（对齐 `hName: "cite"`）

### 3.7 `MessageItem.tsx` 集成 — 状态所有权

```tsx
import { Sources } from "@/components/chat/Sources";

// MessageItem 内部、非 user 分支，assistant 渲染处
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

// render：答案之后、反馈按钮之前
// <MarkdownRenderer content={message.content} sources={message.sources} onCitationClick={handleCitationClick} />
// {hasSources ? <Sources ref={sourcesRef} cards={message.sources!} highlightedIndex={highlightedIndex} /> : null}
```

**状态所有权切分**：

- `MessageItem`：`highlightedIndex` / `timerRef` / `sourcesRef`
- `Sources`：`expanded: Set<number>`（本地 UI 状态），useEffect 对 `highlightedIndex` 反应性 auto-expand
- `MarkdownRenderer`：`indexMap` / `hasSources` / `remarkPlugins` / `components`（均为 useMemo 派生）
- **无跨层 Context、无全局 store 写入**

### 3.8 依赖显式声明

```jsonc
// frontend/package.json
"dependencies": {
  "unist-util-visit": "^5.0.0",    // 新（remarkCitations 插件运行时使用）
  "remark-gfm": "^4.0.0"           // 既有
  // 生产运行时不引 unified / remark-parse——react-markdown 自己 parse，plugin 接收 tree
},
"devDependencies": {
  "@testing-library/react": "^16.0.0",        // 新（组件测试）
  "@testing-library/user-event": "^14.5.0",   // 新（点击 / 键盘交互）
  "unified": "^11.0.0",                       // 新（仅测试：remarkCitations.test.ts 需要 unified().use(remarkParse).use(remarkGfm).use(remarkCitations).runSync(tree)）
  "remark-parse": "^11.0.0",                  // 新（同上，测试 parse 用）
  "vitest": "^2.1.9",                         // 既有
  "jsdom": "^25.0.1"                          // 既有
}
```

**为什么 `unified` / `remark-parse` 放 devDependencies 而非 dependencies**：
- 生产代码只用 `remarkCitations` 作为 react-markdown 的 plugin；react-markdown 内部自己 parse，plugin 只接收已解析的 tree——**生产不需要独立 parse 能力**
- 测试（`remarkCitations.test.ts`）要构造 mdast 树喂给插件断言产出，需要 parse pipeline；但这是测试时依赖
- `unified` / `remark-parse` 仍是 `react-markdown` / `remark-gfm` 的 transitive，运行时能通过 transitive 找到——但**显式声明为 devDependencies** 避免"测试隐式依赖 transitive"的漂移风险

安装：

```
cd frontend && npm install --save unist-util-visit@^5
cd frontend && npm install --save-dev @testing-library/react @testing-library/user-event unified@^11 remark-parse@^11
```

---

## § 4 测试清单

### 4.1 后端单测（新增 3 个测试类 + 扩 1 个）

| 测试类 | 用例 |
|---|---|
| `RAGPromptServiceCitationTest`（新） | (1) citationMode=true → evidence 含 `【参考文档】[^1]《docName》`；(2) `buildCitationEvidence` 优先用 `intentChunks.RetrievedChunk.text` 全文，**不**用 `SourceChunk.preview`（断言含全文特征字符串 + 不含 preview 截断标记）；(3) chunkId 在 `intentChunks` 缺失 → 回退到 `preview`；(4) `cards.size()==1` → rule 含 `[^1]` 不含"至"；(5) `cards.size()>=2` → rule 含 `[^1] 至 [^N]`；(6) `cards` 非空但 `kbContext` 空 → citationMode=false，不注入规则（守护异常组合）；(7) `cards==null` → 完全回归 PR2 行为 |
| `CitationStatsCollectorTest`（新） | (1) `[^1][^2][^99]` + cards=[1,2] → total=3 valid=2 invalid=1；(2) 多句部分引用 → coverage 合理；(3) 空 answer / 空 cards → `(0,0,0,0.0)`；(4) cards index 非连续 [1,3,5] + answer 含 `[^2]` → invalid++（锁 indexSet.contains 语义）；(5) 答案末尾无句号 → 记录实际 coverage 锁行为（粗切偏差可见） |
| `StreamChatEventHandlerCitationTest`（新） | (1) `cardsHolder` 未 set → `mergeRunExtraData` 不调用；(2) `cardsHolder` 有值 + 答案含引用 → `mergeRunExtraData(traceId, Map)` 调用一次，Map 含 4 个 citation 字段；(3) 用 `Mockito.InOrder` 锁 overwrite `updateRunExtraData` **先**、merge `mergeRunExtraData` **后**；(4) `cardsHolder` 有值但 answer 为空 → stats 全 0，仍 merge 一次 |
| `RAGChatServiceImplSourcesTest`（扩） | 新增 `chat_whenGuidanceClarificationPrompt_thenSkipSourcesEntirely` — SRC-6 正负断言组合（见 § 2.8） |

### 4.2 后端集成测

**PR3 不新建 `/rag/v3/chat` 集成测**。代码库当前（`main` 截至 2026-04-22）无 happy-path 端到端基线可复用（已扫 `bootstrap/src/test/java`：只有 `RAGChatServiceImplSourcesTest` / `StreamChatEventHandlerSuggestionsTest` 两个 rag 相关测试类；其余 `@SpringBootTest` 集中在 vector / intent / rewrite / embedding）。

PR3 以后端单测 + handler/service 单测 + 手动冒烟覆盖。若未来需要锁 SSE 帧序 / 端到端 extra_data 合并正确性，再单独立项新建最小 `/rag/v3/chat` happy-path 测。

### 4.3 前端单测（vitest + RTL）

| 测试文件 | 用例 |
|---|---|
| `frontend/src/utils/remarkCitations.test.ts`（新） | `unified().use(remarkParse).use(remarkGfm).use(remarkCitations).runSync(tree)` 断言：(1) text `[^1]` → cite 节点含 `hProperties["data-n"]=1`；(2) inlineCode 内 `[^2]` 原样、无 cite 节点；(3) fenced code 内 `[^3]` 原样；(4) link 文本 `[文档 [^5]](/x)` 原样；(5) footnoteReference → cite；(6) footnoteDefinition 整段移除；(7) 定义体内的 `[^2]` 不被产出任何 cite 节点（锁 "不会在被删除前先被 text visit 走到"） |
| `frontend/src/components/chat/Sources.test.tsx`（新） | (1) cards 按后端原序渲染（cards=[1,2,3]→ DOM 出现顺序 1,2,3）；(2) `highlightedIndex` 变化触发对应卡片 auto-expand；(3) 点击卡片 toggle expanded；(4) `cards=[]` → 渲染 null；(5) `highlightedIndex=n` 对应卡片含高亮 ring class |
| `frontend/src/components/chat/CitationBadge.test.tsx`（新） | (1) indexMap.has(n) → 渲染 button + 点击触发 `onClick(n)`；(2) !indexMap.has(n) → 渲染纯 `<sup>`，无 button、无 onClick；(3) `title = card.docName` |
| `frontend/src/components/chat/MarkdownRenderer.test.tsx`（新，守 rollback 契约） | (a) `sources=undefined` + content 含 `[^1]` → 输出中无 `<sup>` 结构，`[^1]` 保持为纯文本（锁"flag off 等同 PR2"的字节级契约）；(b) `sources=[{index:1, docName:"D"}]` + content 含 `[^1]` → 输出含 `<CitationBadge>` 渲染；(c) `sources=[]`（空数组） → 视同 `sources=undefined`，不挂 plugin |
| `frontend/src/components/chat/MessageItem.test.tsx`（新，小） | (1) 点击 CitationBadge → `scrollIntoView` mock 被调用 + `highlightedIndex` 被 set；(2) `vi.useFakeTimers()` + `vi.advanceTimersByTime(1500)` 后 `highlightedIndex` 回 null；(3) 组件卸载时 timer 被清理（无 act warning） |

### 4.4 测试 setup 细节（避免踩坑）

- **`StreamChatEventHandlerCitationTest`**：`traceId` 是 handler 构造期从 `RagTraceContext` 读取的 final 字段。**必须**在 `new StreamChatEventHandler(...)` 之前先 `RagTraceContext.setTraceId("test-trace-id")`；否则 traceId 为空，`mergeRunExtraData` 在 `StrUtil.isBlank(traceId)` 守护下直接 return，出现"为什么 merge 根本没调用"的假阴性。测试 `@AfterEach` 里 `RagTraceContext.clear()`。
- **`MessageItem.test.tsx`**：`window.HTMLElement.prototype.scrollIntoView` 在 jsdom 里默认不存在，需 `vi.spyOn(Element.prototype, "scrollIntoView").mockImplementation(() => {})` 或全局 setup 注入 mock。
- **`MarkdownRenderer.test.tsx`**：直接喂 markdown 字符串 + 断言 rendered DOM（RTL 的 `render` + `container.innerHTML` 或 `queryByText`）即可；无需 mock `remarkCitations` 本体。

### 4.5 手动冒烟（flag 临时 on）

1. 打开已有会话（`t_message.sources_json` 未持久化，PR4 才补）→ 历史消息**无** Sources 渲染、无报错
2. 新问 KB 命中问题 → 答案含 `[^1][^2]` 角标 + Sources 卡片区 → 点击 `[^1]` 滚到卡片 + 高亮 1.5s + auto-expand chunk preview
3. 点击 Sources 卡片 → 就地展开/收起
4. 刷新页面 → sources 消失（PR4 才持久化，符合预期）
5. 空检索 / MCP-Only / System-Only → 无 Sources 卡片、无 `[^n]` 要求
6. DevTools → `t_rag_trace_run.extra_data` 查最新记录，含 `citationTotal / Valid / Invalid / Coverage` 字段
7. `rag.sources.enabled: false` → 完全回到 PR2 末态、UI 零变化

---

## § 5 SSE 契约（PR3 不动）

PR3 **不**动 SSE 事件种类 / 顺序 / 载荷结构。事件顺序仍为 PR2 定义：

```
META → SOURCES → MESSAGE+ → FINISH → (SUGGESTIONS) → DONE
```

`citationStats` 是 server-side 埋点（落 `t_rag_trace_run.extra_data`），**不**走 SSE。

---

## § 6 Not in PR3（明确排除）

| 项 | 归属 |
|---|---|
| `t_message.sources_json` 持久化 + `upgrade_v1.4_to_v1.5.sql` + schema 双写 | **PR4** |
| `ConversationMessageVO.sources` + `selectSession` 映射 + `thinkingContent/Duration` 补齐 | **PR4** |
| `rag.sources.enabled=true` 默认打开 | **PR5** |
| `SourceCard.kbName` 字段（tooltip/card 显示 KB 名） | SRC-5 未来 additive |
| Radix Tooltip 替换原生 `title` | 后续 UX 升级 |
| `updateTraceTokenUsage` overwrite 机制本身改造 | backlog SRC-9（新增，见 § 8） |
| Milvus / Pg retriever 回填 docId/chunkIndex | SRC-1 P0（切后端前必补） |
| 新建 `/rag/v3/chat` happy-path 集成测 | 后续需要锁 SSE 帧序时单独立项 |

---

## § 7 架构 Invariants 回顾（PR3 全部保持）

1. **orchestrator 决策 / handler 机械发射 / service 执行**：Prompt 改造在 `RAGPromptService` 内部派生；handler `onComplete` 的 stats 写入是纯函数 → `mergeRunExtraData`，无业务分支；orchestrator 只决定是否传 cards
2. **set-once CAS 替代 ThreadLocal**：`onComplete` 通过 `cardsHolder.get()` 读 `Optional` 快照；`traceId` 是构造期 final 字段；PR3 新增代码**零** ThreadLocal 读写
3. **sources 判定锚点**：前端 `indexMap.get(n)`；后端 `indexSet.contains(n)`；**绝不**用 `cards[n-1]` 或 `1..N` range
4. **前端 `[^n]` 严禁字符串 preprocess**：citation 渲染走 `remarkCitations` mdast 插件，`SKIP_PARENT_TYPES`（code / inlineCode / link / image / linkReference）硬约束；`citationAst.ts` 是共享常量 SSOT
5. **`<Sources />` 契约**：忠实展示后端 `cards` 原序；后端 `SourceCardBuilder` clip 是**唯一**可见性裁剪层；前端不做 partition、不做第二层预算
6. **rollback 契约**：flag off 或 `hasSources=false` 时，`MarkdownRenderer` **不**挂 `remarkCitations`、**不**映射 `cite`，答案与 PR2 末态字节级等价

---

## § 8 Gotchas（PR3 新增 7 条）

1. `buildCitationEvidence` 正文来源**必须**是 `intentChunks.RetrievedChunk.text`，**不得**用 `SourceChunk.preview`；后者是 UI 截断版，走 preview 会悄悄降级 LLM evidence 信息量
2. `onComplete` 中 `updateTraceTokenUsage`（overwrite）与 `mergeCitationStatsIntoTrace`（merge）**顺序不可颠倒**；颠倒会让 merge 结果被后续 overwrite 清掉
3. `MarkdownRenderer` 必须按 `hasSources = sources?.length > 0` 同时 gate `remarkPlugins` 与 `components.cite`——任何一侧未 gate 都会在 flag off / sources 缺席场景下把字面 `[^n]` 错误渲染成上标，破坏"flag off 完全等同 PR2"的回滚契约
4. `remarkPlugins` 顺序硬固定为 `[remarkGfm, remarkCitations]`——反了的话 remark-gfm 无法把已解析的定义块归并到 footnoteReference
5. `components.cite` 的 key 必须**小写**——对齐 `hName: "cite"`（react-markdown 从 mdast 节点通过 hName 转 HAST 再按 tag name 查 components）
6. `remarkCitations` 内部三段 `visit` 的顺序（footnoteReference → footnoteDefinition → text）不能颠倒：第 2 步 splice 删除定义子树是第 3 步 text visit 不会误抓定义体内 `[^n]` 的前提
7. `citationMode` 守护为 `cards 非空 && ctx.hasKb()`——单靠 cards 非空不够，异常组合 "cards 非空 + kbContext 空" 必须被拦截，否则 scene 判定为 MCP_ONLY 的 prompt 却被 append 了引用规则（自相矛盾的 prompt）

### 新增 backlog 条目（入 `docs/dev/followup/backlog.md`）

**SRC-9. `updateTraceTokenUsage` 的 overwrite 写法是 latent 坑**

- **位置**：`StreamChatEventHandler.updateTraceTokenUsage`
- **症状**：走 `updateRunExtraData(traceId, String)` 覆盖写。任何在它**之前**已合入 `extra_data` 的字段都会被它清掉
- **当前规避**：PR3 的 `mergeCitationStatsIntoTrace` 必须在 `updateTraceTokenUsage` **之后**执行，`RAGConfigProperties.suggestionsEnabled=false` 场景下无 `mergeSuggestionsIntoTrace` 抢跑
- **根治**：把 `updateTraceTokenUsage` 改为 `mergeRunExtraData(traceId, Map.of("promptTokens", ..., "completionTokens", ..., "totalTokens", ...))`；非 PR3 scope
- **优先级**：P3（latent，顺序依赖已规避；但将来任何新的"先合后写"字段都可能再踩）

---

## § 9 PR 拆分回顾（定位本 PR）

| PR | 范围 | 可见影响 |
|----|------|----------|
| PR1 ✅ | `RetrievedChunk.docId/chunkIndex` + OpenSearch 回填 + `findMetaByIds` + `DocumentMetaSnapshot` | 零 |
| PR2 ✅ | `SourceCardBuilder` + DTOs + `SSEEventType.SOURCES` + `RAGChatServiceImpl` 编排 + 前端 SSE 路由 | 默认无感（flag off） |
| **PR3（本文档）** | Prompt 改造 + `remarkCitations` + `CitationBadge` + `<Sources />` UI + 引用质量埋点 + SRC-6 断言 | 默认无感（flag off） |
| PR4 | `t_message.sources_json` 持久化 + `ConversationMessageVO.sources` + `selectSession` 映射（顺带 `thinkingContent/Duration` 修复） | 默认无感（flag off） |
| PR5 | `rag.sources.enabled: true` 上线 | 功能上线 |

---

## 附：参考路径

- 基线分支：`feature/answer-sources-pr3`（从 `main` 拉）
- 上游 v1 spec：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`
- PR2 设计：`docs/superpowers/specs/2026-04-21-answer-sources-pr2-design.md`
- PR1+PR2 交接记录：`log/dev_log/2026-04-21-answer-sources-pr1-pr2.md`
- 主要代码锚点：
  - `RAGPromptService.java`（`buildStructuredMessages` 内部派生）
  - `PromptContext.java`（字段扩展）
  - `RAGChatServiceImpl.java`（`streamLLMResponse` 参数扩展、三层闸门区域在 L222 附近）
  - `StreamChatEventHandler.java`（`onComplete` 埋点块插入位置）
  - `SourceCardBuilder.java`（PR2 已有，PR3 不改）
  - `frontend/src/components/chat/MarkdownRenderer.tsx` / `MessageItem.tsx`
  - `frontend/src/types/index.ts`（PR2 已定义，PR3 不改）
