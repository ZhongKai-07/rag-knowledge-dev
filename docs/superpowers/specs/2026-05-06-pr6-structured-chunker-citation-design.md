# PR 6 (Phase 2.5 / DOCINT-2) — Structured Chunker + Retrieval Citation 设计文档

| 项 | 值 |
|---|---|
| 起草日期 | 2026-05-06 |
| 设计阶段 | Brainstorm 完成、待写实施 plan |
| 前置 PR | PR 1-5 (Phase 2.5 Parser Enhancement) 全部已合并到 main，HEAD `a03a4acf` |
| 关联 plan | `docs/superpowers/plans/2026-05-05-parser-enhancement-docling.md` PR 6 章节（line 2646-3287，本 spec 在多处偏离原 plan，详见 §12 Plan deviations） |
| 关联 forward-compat 目标 | `docs/superpowers/specs/2026-05-02-collateral-mcp-field-lookup-design.md`（Collateral 单问单答字段查询 PR）|

## 1. Goal & Non-Goals

### 1.1 Goal

把 PR 5 落地后已经在产生但被丢弃的 Docling layout 信息（`ParseResult.pages` / `tables`）打通到下游：

1. **layout-aware chunking**：StructuredChunkingStrategy 按 title/heading 边界切分、表格保持原子，输出 chunk 同时携带页/章节/块类型等结构化元信息
2. **chunk 级 evidence 持久化**：layout 元信息写入 OpenSearch metadata（可检索/过滤）+ `t_knowledge_chunk` 9 个布局列（DB 反查）
3. **检索路径回流**：`OpenSearchRetrieverService` 把 layout 元信息读回 `RetrievedChunk`
4. **SourceChunk evidence 可见**：用户在 RAG 答案的引用卡片里看到"第 X 页 / 章节路径"等定位信息
5. **降级路径鲁棒**：ENHANCED 路径在 Docling sidecar 不可用时仍正常完成 ingestion（`FallbackParserDecorator` → Tika），层间契约不破

### 1.2 Non-Goals

PR 6 **明确不做**以下五件事，避免范围漂移：

1. **不激活 `t_knowledge_document_page` 页级表**。PR 3 留的 entity + mapper 维持 0 调用状态；激活由未来"evidence preview UI" PR 承担（Backlog 条目 `PARSER-PAGE-PERSIST`）。理由：PR 6 用户价值只需 chunk 级元信息支撑 SourceChunk；激活页表会过早冻结 `blocks_json` schema 并引入 `(doc_id, page_no)` 幂等管理面
2. **不修改 CHUNK 处理路径**（[`KnowledgeDocumentServiceImpl.runChunkProcess`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)）。BASIC 上传仍硬编码走 Tika，与 PR 6 范围"PIPELINE 路径下的 layout-aware chunking"无关
3. **不解决 EnhancerNode-before-StructuredChunkingStrategy 的二阶语义对齐问题**。`enhancedText` 与原始 `parseResult.layout` 之间的对齐策略（如何把 Q&A enhanced text 与 Docling 块边界对齐）不在 PR 6 范围内，留给未来需要这个组合的特定 PR
4. **不实现 Collateral 单问单答字段查询业务链路**。PR 6 只交付 chunk evidence 底座（SourceChunk 6 个新字段为可见契约）；Slot 抽取、字段值提取、答案分档由后续 Collateral PR 实现
5. **不在 SourceChunk 渲染 bbox 高亮**。`bbox_refs` 进 OS metadata 和 DB 列保留为未来扩展，但**不进 SourceChunk DTO**，前端 UI 也不消费。bbox 高亮属于"future work"

## 2. Forward Compatibility

PR 6 是即将到来的 Collateral 单问单答字段查询 PR 的**必要前置但非充分**底座。Collateral PR 的 tier-A 答案返回结构：

```
字段值: <从 chunk 中抽取的精确 value>
出处: <docName>，第 <pageStart-pageEnd> 页，<headingPath>
原文段落: <chunk text>
```

支撑这个返回结构的 evidence 字段必须由 PR 6 在 `SourceChunk` 上锁定。**契约一旦锁，下游 Collateral PR 与 PR 6 之间签约成立，避免跨 PR 反复对齐。**

### 2.1 SourceChunk 6 个新字段（契约锁定）

| 字段 | Java 类型 | TypeScript 类型 | 来源 | nullable 含义 |
|---|---|---|---|---|
| `pageNumber` | `Integer` | `number \| undefined` | OS metadata `page_number` | BASIC chunk / 跨多页 chunk 表示首页号；其他场景 null |
| `pageStart` | `Integer` | `number \| undefined` | OS metadata `page_start` | 跨页 chunk 起始页；单页 chunk 等于 `pageNumber`；BASIC chunk null |
| `pageEnd` | `Integer` | `number \| undefined` | OS metadata `page_end` | 跨页 chunk 结束页；单页 chunk 等于 `pageNumber`；BASIC chunk null |
| `headingPath` | `List<String>` | `string[] \| undefined` | OS metadata `heading_path` | 章节路径 `["第三章 风险管理", "3.2 信用风险"]`；BASIC null；ENHANCED 但无明确 heading 时为空数组 |
| `blockType` | `String`（来自 `BlockType.name()`）| `string \| undefined` | OS metadata `block_type` | `"PARAGRAPH"` / `"TABLE"` / `"TITLE"` 等 8 种枚举值的 name；BASIC null |
| `sourceBlockIds` | `List<String>` | `string[] \| undefined` | OS metadata `source_block_ids` | chunk 由哪些 LayoutBlock 拼成；BASIC null；ENHANCED 但 chunker 未填充时空数组 |

### 2.1.1 JSON 序列化策略

SourceChunk 6 个新字段全部 `null`/`undefined` 友好。Java 端使用 Jackson 默认（包含 null 字段，序列化为 `"pageNumber":null`），无需 `@JsonInclude(Include.NON_NULL)` 注解 —— 前端 TypeScript interface 用 optional `?:` 容忍 `null` 与 `undefined`。这条与 `Sources.tsx` 的 `chunk.pageNumber !== undefined` 守卫一致，BASIC chunk 的 null 值不会渲染 evidence 行。

### 2.2 value-in-paragraph guard 契约

Collateral PR 的 tier-A 降级判定是"抽取的字段值必须在 chunk text 中能匹配上"。**这个匹配必须用 [`RetrievedChunk.text()`](../../framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/RetrievedChunk.java) 的全文做，不能用 `SourceChunk.preview` 截断版**。原因：

- `SourceChunk.preview` 是 UI 展示截断（[`SourceCardBuilder.previewMaxChars`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCardBuilder.java) 控制）
- 如果 value 在 chunk 末尾、preview 已截断，guard 会假阴性，正确答案被错误降级
- guard 在服务端 composer 里完成，时机早于 SourceCard/SourceChunk 构造，天然有 RetrievedChunk.text() 可用

PR 6 不实现 guard 本身（这是 Collateral PR 的事），但**spec 在此处显式记录这条契约**，作为下游 PR 的输入。

## 3. Discovery Deltas vs Original Plan

Brainstorm 阶段对 main HEAD `a03a4acf` 的 4 路并行代码调查发现 **12 处 plan 与现实不符**。Spec 处理方式如下：

| # | Delta | Plan 假设 | 现实 | Spec 处理 |
|---|---|---|---|---|
| 1 | ParseResult.pages/tables 在 ParserNode 被丢弃 | "PR 5 已写 ParseResult 到 context" | [`ParserNode.java:88-96`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java) 只取 `text()` + `metadata()`，pages/tables 立即丢失；IngestionContext 无 `parseResult` 字段 | §5.9 ParserNode 改写 + IngestionContext 加 `parseResult` 字段 + 5 条 SoT 约定（§4.2） |
| 2 | OS index `dynamic:false` 拒收新字段 | "OS 能直接存 layout metadata" | [`OpenSearchVectorStoreAdmin.java:260-299`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java) 的 metadata mapping `dynamic:false`；9 个 layout 字段全缺；未声明字段虽然进 `_source` 但**不可被 term/range filter 命中**，也不索引；`ensureVectorSpace` 仅 create-if-missing | §5.11 OS mapping forward-only 升级 + §11 运维契约（已存在 KB 切 ENHANCED 走 DELETE+re-ingest，半升级状态明确禁止） |
| 3 | 老 `ChunkingStrategy` 接口签名异构 | "StructuredChunkingStrategy 实现同接口" | 现接口 `chunk(String text, ChunkingOptions config)` 只吃 String；StructuredChunkingStrategy 需要的 `(pages, tables, options)` 异构 | §5.1-5.5 引入新顶层接口 `IngestionChunkingStrategy` + `IngestionChunkingInput` 输入模型 + LegacyAdapter 包装老 strategy |
| 4 | v0.5.1 不暴露 textLayerType/confidence | Plan 设计了 `textLayerTypeForRange` / `averageConfidence` aggregation | [`DoclingResponseAdapter.java:207-216`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java) 这两个字段永远 null；plan aggregation 自然返回 null | §6.4 接受 null 不伪造；DB / OS 列保持空；§12 标记为 known limitation |
| 5 | parseMode 没传到 ChunkerNode | Plan 假设可用 | ParserNode 读 parseMode 但不存 context | §5.9 IngestionContext 已有 `parseMode: String` 字段（PR 4 加的）；§5.2 IngestionChunkingInput 通过 `IngestionContext.parseMode` 读 |
| 6 | KnowledgeChunkDO 9 字段无 typeHandler | Plan 用 LambdaUpdateWrapper.set | gotcha §2 警告：typeHandler 不会触发，会写字面字符串 | §5.10 持久化必须走 entity-based saveBatch；headingPath/sourceBlockIds JSON 序列化由 service 层用 ObjectMapper 完成（不是 typeHandler 也不是 helper） |
| 7 | RetrievedChunk 不读 layout | Plan 直接 chunk.pageNumber 用 | [`OpenSearchRetrieverService.toRetrievedChunk`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java) 只抽 4 字段 | §5.12 扩 RetrievedChunk + 扩 toRetrievedChunk 字段映射；用 ChunkLayoutMetadata helper 反序列化 |
| 8 | Page table mapper 0 调用 | Plan 写到 `persistChunksAndVectorsAtomically` | [`KnowledgeDocumentPageMapper`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentPageMapper.java) 全仓 0 调用 | §1.2 Non-Goal 1：PR 6 不激活，签名不接受 `pages` 参数 |
| 9 | OcrFallbackParser / TextLayerQualityDetector 0 调用 | Plan 未提 | 接口 + Noop 实现都在但全仓 0 调用 | §6 不依赖；textLayerType 走 Q7 null 路径 |
| 10 | CHUNK 模式硬编码 Tika | Plan 未提 | runChunkProcess 写死 `parserSelector.select(TIKA)` | §1.2 Non-Goal 2：PR 6 不动 CHUNK |
| 11 | ChunkerNode 已耦合 embedding | Plan 假设可分 | ChunkerNode 内部 `chunkEmbeddingService.embed(chunks, null)` | §5.8 ChunkerNode 重写后保留此调用，无变化（A' 接口让 strategy 直接输出 List\<VectorChunk\>，embedding 调用零改动 —— Q6 bonus） |
| 12 | Milvus/PG retriever 不读 metadataFilters | gotcha §4 已警告 | backlog SL-1 | §11 spec 明确：PR 6 layout 字段在非 OS 后端会丢失，与 SL-1 同步处理 |

## 4. Architecture

### 4.1 触达面 file inventory

**新建（7 个 main + 测试若干）**：

| 文件 | 包 | 作用 |
|---|---|---|
| `IngestionChunkingStrategy.java` | `bootstrap/.../ingestion/chunker` | A' 接口：`supports(mode, input) + chunk(input, options) + priority()` |
| `IngestionChunkingInput.java` | `bootstrap/.../ingestion/chunker` | record：`{ text, parseResult, parseMode, metadata }` |
| `StructuredChunkingStrategy.java` | `bootstrap/.../ingestion/chunker` | priority=100；layout 可用时接管 |
| `LegacyTextChunkingStrategyAdapter.java` | `bootstrap/.../ingestion/chunker` | priority=10；包装老 `FixedSizeTextChunker` / `StructureAwareTextChunker` |
| `IngestionChunkingDispatcher.java` | `bootstrap/.../ingestion/chunker` | 优先级 dispatch + 同优先级 fail-fast |
| `StructuredChunkingOptionsResolver.java` | `bootstrap/.../ingestion/chunker`（package-private）| 把异构 ChunkingOptions 收口为 `StructuredChunkingDimensions` |
| `ChunkLayoutMetadata.java` | `bootstrap/.../rag/core/vector` | layout 字段在 VectorChunk.metadata 上的类型化访问层（双向 reader/writer，三态读取兼容） |

**修改（10 个）**：

| 文件 | 改动 |
|---|---|
| `IngestionContext.java` | 加 `parseResult: ParseResult` 字段 |
| `ParserNode.java` | parse 后写 `setParseResult(result)`（同步保留 setRawText） |
| `ChunkerNode.java` | rewire 走 `IngestionChunkingDispatcher`（替换原 `chunkingStrategyFactory.requireStrategy(...)`） |
| `KnowledgeDocumentServiceImpl.java` | `persistChunksAndVectorsAtomically` 签名加 `structuredChunks` 参数；从 metadata 抽 layout 写 DB 9 列 |
| `OpenSearchVectorStoreAdmin.java` | metadata mapping 加 9 个 layout 字段类型声明 |
| `RetrievedChunk.java` | 加 6 个 nullable 字段 |
| `OpenSearchRetrieverService.java` | `toRetrievedChunk` 用 `ChunkLayoutMetadata` 抽 layout |
| `SourceChunk.java` | 加 6 个 nullable 字段 |
| `SourceCardBuilder.java` | RetrievedChunk → SourceChunk 时映射 6 个字段 |
| `frontend/src/types/index.ts` + `frontend/src/components/chat/Sources.tsx` | TS interface 6 字段 + 卡片头部渲染 page/heading |

### 4.2 SoT 约定（5 条，spec 锁定）

写入 `IngestionContext.parseResult` 字段附近代码注释，以及 `docs/dev/gotchas.md` §8 Parser 域：

1. **ParserNode 是唯一写入 `parseResult` 的节点**。其它节点不应回写
2. **`rawText` 是 `parseResult.text()` 的派生兼容字段**。ParserNode 内保持二者同步；约定"PR 6 起 `rawText == parseResult.text()` 永远成立"
3. **`enhancedText` 是独立派生文本**（EnhancerNode 写），不回写 `parseResult`，不与 layout 对齐
4. **layout / table 新代码只读 `parseResult.pages()` / `parseResult.tables()`**，不要从 `document.tables`（StructuredDocument 的 legacy tables）读，后者是不同时代的抽象
5. **`StructuredDocument` 继续保留**给 legacy 输出和兼容，**不是** PR 6 layout 事实源。新代码不应依赖它的 sections / tables 字段

## 5. Component Design

### 5.1 `IngestionChunkingStrategy`（新接口）

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

public interface IngestionChunkingStrategy {

    /**
     * Higher value = tried first in dispatch.
     * StructuredChunkingStrategy = 100; LegacyTextChunkingStrategyAdapter = 10.
     */
    int priority();

    /**
     * Whether this strategy can handle (settings.mode, current input).
     * MUST be cheap — called per-document during dispatch.
     */
    boolean supports(ChunkingMode mode, IngestionChunkingInput input);

    /**
     * Chunk the input. MUST return List<VectorChunk> with layout fields
     * (if any) populated via {@link ChunkLayoutMetadata.Builder}.
     */
    List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options);
}
```

### 5.2 `IngestionChunkingInput`（新 record）

```java
package com.knowledgebase.ai.ragent.ingestion.chunker;

public record IngestionChunkingInput(
        String text,                       // enhancedText > rawText（legacy adapter 用）
        ParseResult parseResult,           // SoT for layout（structured strategy 用）
        ParseMode parseMode,               // BASIC / ENHANCED（信号性，不强制 dispatch）
        Map<String, Object> metadata       // ingestion-level metadata，可空
) {
    public IngestionChunkingInput {
        if (metadata == null) metadata = Map.of();
        // text 不做 hasText 校验：legacy adapter 自己处理空 text 边界
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
                context.getMetadata()
        );
    }
}
```

### 5.3 `StructuredChunkingStrategy`（新组件）

```java
@Component
public class StructuredChunkingStrategy implements IngestionChunkingStrategy {

    @Override public int priority() { return 100; }

    /**
     * supports() 不依赖 mode 参数 —— 这是有意设计：layout 可用性是客观判断，
     * 不应被用户的 mode 偏好覆盖。mode 只在 layout 不可用时引导 fallback 选哪个 legacy chunker。
     */
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
            VectorChunk vc = newVectorChunk(serializeTable(t), out.size());
            ChunkLayoutMetadata.writer(vc)
                    .pageNumber(t.pageNo())
                    .pageRange(t.pageNo(), t.pageNo())
                    .headingPath(t.headingPath())
                    .blockType(BlockType.TABLE.name())
                    .sourceBlockIds(t.tableId() == null ? List.of() : List.of(t.tableId()))
                    .bboxRefs(bboxRefsJson(t.tableId(), t.pageNo(), t.bbox()))
                    .textLayerType(textLayerTypeForRange(pr.pages(), t.pageNo(), t.pageNo()))  // v0.5.1 永远 null
                    .layoutConfidence(t.confidence());                                          // v0.5.1 永远 null
            out.add(vc);
        }

        // 2. body blocks：按 title 边界分段，按 dims 软切
        // ... 见 plan Task 6.1 Step 2 line 2802-2882 的完整实现
        // 关键差异：plan 用 StructuredChunk record，本 spec 直接产出 VectorChunk + ChunkLayoutMetadata.writer

        return out;
    }
}
```

完整 chunking 算法见 plan Task 6.1 Step 2 [`line 2783-2925`](../../docs/superpowers/plans/2026-05-05-parser-enhancement-docling.md#L2783)，本 spec 偏离仅限于 `StructuredChunk` record → `VectorChunk + ChunkLayoutMetadata.writer` 形态切换。

### 5.4 `LegacyTextChunkingStrategyAdapter`（新组件）

```java
public class LegacyTextChunkingStrategyAdapter implements IngestionChunkingStrategy {

    private final ChunkingMode supportedMode;
    private final ChunkingStrategy delegate;

    public LegacyTextChunkingStrategyAdapter(ChunkingMode mode, ChunkingStrategy delegate) {
        this.supportedMode = mode;
        this.delegate = delegate;
    }

    @Override public int priority() { return 10; }

    @Override
    public boolean supports(ChunkingMode mode, IngestionChunkingInput input) {
        return this.supportedMode == mode;
    }

    @Override
    public List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options) {
        // 关键契约：legacy adapter 不写任何 layout 字段
        // delegate 老 chunker 输出原生 VectorChunk，metadata Map 只含 chunk_index 等老字段
        // 任何 PARAGRAPH / null layout 字段都不会被写入 → BASIC byte-equivalent
        return delegate.chunk(input.text(), options);
    }
}
```

`@Configuration` 注册 2 个 adapter bean（每个 ChunkingMode 一个）：

```java
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

### 5.5 `IngestionChunkingDispatcher`（新组件）

```java
@Component
public class IngestionChunkingDispatcher {

    private final List<IngestionChunkingStrategy> strategies;

    public IngestionChunkingDispatcher(List<IngestionChunkingStrategy> all) {
        // Spring 注入后按 priority desc 排序，避免依赖 bean 注入顺序
        this.strategies = all.stream()
                .sorted(Comparator.comparingInt(IngestionChunkingStrategy::priority).reversed())
                .toList();
    }

    public List<VectorChunk> chunk(ChunkingMode mode, IngestionChunkingInput input, ChunkingOptions options) {
        List<IngestionChunkingStrategy> matched = strategies.stream()
                .filter(s -> s.supports(mode, input))
                .toList();
        if (matched.isEmpty()) {
            throw new IllegalStateException(
                    "No chunking strategy supports mode=" + mode
                    + " (parseResult.pages=" + (input.parseResult() == null ? "null" : input.parseResult().pages().size())
                    + ", parseResult.tables=" + (input.parseResult() == null ? "null" : input.parseResult().tables().size()) + ")");
        }
        int topPriority = matched.get(0).priority();
        List<IngestionChunkingStrategy> tied = matched.stream()
                .filter(s -> s.priority() == topPriority)
                .toList();
        if (tied.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous chunking strategies at priority=" + topPriority + ": "
                    + tied.stream().map(s -> s.getClass().getSimpleName()).toList());
        }
        return tied.get(0).chunk(input, options);
    }
}
```

**同优先级 fail-fast** 是有意设计：避免 Spring 注入顺序变成隐藏决策。未来加新 strategy 必须显式选 priority。

### 5.6 `ChunkLayoutMetadata`（新 helper，在 `rag.core.vector`）

```java
package com.knowledgebase.ai.ragent.rag.core.vector;

public final class ChunkLayoutMetadata {

    private ChunkLayoutMetadata() {}

    // ============ Reader API ============

    public static Integer pageNumber(VectorChunk c)        { return readInt(c, VectorMetadataFields.PAGE_NUMBER); }
    public static Integer pageStart(VectorChunk c)         { return readInt(c, VectorMetadataFields.PAGE_START); }
    public static Integer pageEnd(VectorChunk c)           { return readInt(c, VectorMetadataFields.PAGE_END); }
    public static List<String> headingPath(VectorChunk c)  { return readStringList(c, VectorMetadataFields.HEADING_PATH); }
    public static String blockType(VectorChunk c)          { return readString(c, VectorMetadataFields.BLOCK_TYPE); }
    public static List<String> sourceBlockIds(VectorChunk c){ return readStringList(c, VectorMetadataFields.SOURCE_BLOCK_IDS); }
    public static String bboxRefs(VectorChunk c)           { return readString(c, VectorMetadataFields.BBOX_REFS); }
    public static String textLayerType(VectorChunk c)      { return readString(c, VectorMetadataFields.TEXT_LAYER_TYPE); }
    public static Double layoutConfidence(VectorChunk c)   { return readDouble(c, VectorMetadataFields.LAYOUT_CONFIDENCE); }

    // 三态读取兼容：写入路径的 List<String> | OS _source 反序列化的 List/String[] | 历史/DB 路径的 JSON String
    private static List<String> readStringList(VectorChunk c, String key) {
        if (c == null || c.getMetadata() == null) return List.of();
        Object raw = c.getMetadata().get(key);
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (raw instanceof String[] arr) {
            return Arrays.stream(arr).filter(Objects::nonNull).toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            // legacy / DB-derived path: try parse as JSON array
            try {
                return SHARED_MAPPER.readValue(s, new TypeReference<List<String>>() {});
            } catch (JsonProcessingException ignored) {
                return List.of(s);   // 兜底：单值 String
            }
        }
        return List.of();
    }

    // ============ Writer API ============

    public static Builder writer(VectorChunk c) {
        if (c.getMetadata() == null) c.setMetadata(new HashMap<>());
        return new Builder(c.getMetadata());
    }

    public static final class Builder {
        private final Map<String, Object> meta;
        Builder(Map<String, Object> meta) { this.meta = meta; }

        public Builder pageNumber(Integer v) { return putIfPresent(VectorMetadataFields.PAGE_NUMBER, v); }
        public Builder pageRange(Integer s, Integer e) {
            putIfPresent(VectorMetadataFields.PAGE_START, s);
            putIfPresent(VectorMetadataFields.PAGE_END, e);
            return this;
        }
        public Builder headingPath(List<String> path) {
            if (path != null && !path.isEmpty()) meta.put(VectorMetadataFields.HEADING_PATH, path);
            return this;
        }
        /** 调用方传 BlockType.name()，避免 rag.core.vector 反向 import core.parser.layout.BlockType */
        public Builder blockType(String blockTypeName) {
            if (blockTypeName != null && !blockTypeName.isBlank())
                meta.put(VectorMetadataFields.BLOCK_TYPE, blockTypeName);
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
            if (tlt != null && !tlt.isBlank())
                meta.put(VectorMetadataFields.TEXT_LAYER_TYPE, tlt);
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

**边界**：

- helper 只做 Map 内的 type access；**不做 IO，不做 JSON serialize/deserialize 给外部存储**
- DB 落库时 `headingPath` / `sourceBlockIds` 的 `List<String> → String` JSON 序列化由 `KnowledgeDocumentServiceImpl` / 持久化层用现有 `ObjectMapper` 完成（§5.10 详细说明）
- **writer 对 BlockType 解耦**：调用方传 `BlockType.name()` String，避免 `rag.core.vector` 反向 import `core.parser.layout.BlockType`

### 5.7 `StructuredChunkingOptionsResolver`（package-private helper）

```java
final class StructuredChunkingOptionsResolver {

    private StructuredChunkingOptionsResolver() {}

    static StructuredChunkingDimensions resolve(ChunkingOptions options) {
        if (options instanceof TextBoundaryOptions tb) {
            return new StructuredChunkingDimensions(tb.targetChars(), tb.maxChars(), tb.minChars());
        }
        if (options instanceof FixedSizeOptions fs) {
            int target = fs.chunkSize();
            int max = (int) Math.ceil(target * 1.3);   // 30% 弹性，避免 chunker 因 maxChars=target 在边界硬切
            int min = Math.max(1, (int) (target * 0.4));
            return new StructuredChunkingDimensions(target, max, min);
        }
        // null / unknown options → structured 默认值（与 plan 推荐值一致）
        return new StructuredChunkingDimensions(1400, 1800, 600);
    }
}

record StructuredChunkingDimensions(int target, int max, int min) {}
```

### 5.8 ChunkerNode rewire

```java
@Override
public NodeResult execute(IngestionContext context, NodeConfig config) {
    IngestionChunkingInput input = IngestionChunkingInput.from(context);
    if (!StringUtils.hasText(input.text()) && (input.parseResult() == null
            || (input.parseResult().pages().isEmpty() && input.parseResult().tables().isEmpty()))) {
        return NodeResult.fail(new ClientException("可分块文本与 layout 同时为空"));
    }

    ChunkerSettings settings = parseSettings(config.getSettings());
    ChunkingOptions chunkConfig = convertToChunkConfig(settings);

    List<VectorChunk> chunks = ingestionChunkingDispatcher.chunk(settings.getStrategy(), input, chunkConfig);

    // embedding 调用零改动 —— A' 接口让 strategy 直接输出 List<VectorChunk>
    chunkEmbeddingService.embed(chunks, null);
    context.setChunks(chunks);
    return NodeResult.ok("已分块 " + chunks.size() + " 段");
}
```

### 5.9 ParserNode + IngestionContext

`IngestionContext.java` 加单字段：

```java
@Getter @Setter
private ParseResult parseResult;
```

`ParserNode.execute(...)`：

```java
ParseResult result = parser.parse(context.getRawBytes(), mimeType, options);
context.setParseResult(result);                     // ← 新增。SoT for layout
context.setRawText(result.text());                  // 保留：派生兼容
context.setDocument(StructuredDocument.builder()    // 保留：legacy 输出
        .text(result.text())
        .metadata(result.metadata())
        .build());
```

### 5.10 `persistChunksAndVectorsAtomically` 签名 + layout 落 DB

```java
private int persistChunksAndVectorsAtomically(
        String collectionName,
        String docId,
        String kbId,
        Integer securityLevel,
        List<VectorChunk> chunks) {        // ← 注意：plan 加 pages/structuredChunks 两个参；本 spec 不加 pages（page 表 Non-Goal），不加 structuredChunks（layout 在 chunks.metadata 里）

    // 1. KB-OS 索引保险
    vectorStoreAdmin.ensureVectorSpace(...);

    // 2. KnowledgeChunkDO 构造 + DB 9 列填充
    List<KnowledgeChunkDO> chunkDOs = new ArrayList<>(chunks.size());
    for (VectorChunk vc : chunks) {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .docId(docId)
                .kbId(kbId)
                .chunkIndex(vc.getIndex())
                .chunkContent(vc.getContent())
                // ... 现有非 layout 字段 ...
                // PR 6 新增：从 metadata 抽 layout
                .pageNumber(ChunkLayoutMetadata.pageNumber(vc))
                .pageStart(ChunkLayoutMetadata.pageStart(vc))
                .pageEnd(ChunkLayoutMetadata.pageEnd(vc))
                .headingPath(serializeJsonOrNull(ChunkLayoutMetadata.headingPath(vc)))
                .blockType(ChunkLayoutMetadata.blockType(vc))
                .sourceBlockIds(serializeJsonOrNull(ChunkLayoutMetadata.sourceBlockIds(vc)))
                .bboxRefs(ChunkLayoutMetadata.bboxRefs(vc))
                .textLayerType(ChunkLayoutMetadata.textLayerType(vc))     // v0.5.1 永远 null
                .layoutConfidence(ChunkLayoutMetadata.layoutConfidence(vc))// v0.5.1 永远 null
                .build();
        chunkDOs.add(chunkDO);
    }

    // 3. saveBatch（entity-based，避免 gotcha §2 typeHandler 陷阱）
    chunkMapper.saveBatch(chunkDOs);

    // 4. OS 写入：chunks.metadata 已在 chunker 阶段写入 layout，OpenSearchVectorStoreService 透传
    vectorStoreService.indexDocumentChunks(collectionName, docId, kbId, securityLevel, chunks);

    return chunks.size();
}

/**
 * Helper：List<String> → JSON String，空集 → null（避免写无意义的 "[]" 字符串到 DB）。
 * 由 service 层承担序列化，不进 ChunkLayoutMetadata helper（保持后者只做 Map 内 type access）。
 */
private String serializeJsonOrNull(List<String> list) {
    if (list == null || list.isEmpty()) return null;
    try {
        return objectMapper.writeValueAsString(list);
    } catch (JsonProcessingException e) {
        log.warn("Failed to serialize List<String> to JSON, falling back to null: {}", list, e);
        return null;
    }
}
```

### 5.11 OS mapping forward-only 升级

[`OpenSearchVectorStoreAdmin.java`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java) `metadata.properties` 加 9 个字段（与 [`VectorMetadataFields`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/VectorMetadataFields.java) 常量对齐）：

```json
"metadata": {
  "dynamic": false,
  "properties": {
    /* 现有字段不动 */
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

    /* PR 6 新增 */
    "page_number":        { "type": "integer" },
    "page_start":         { "type": "integer" },
    "page_end":           { "type": "integer" },
    "heading_path":       { "type": "keyword" },     // multi-value，OS 自动支持 List<String>
    "block_type":         { "type": "keyword" },
    "source_block_ids":   { "type": "keyword" },     // multi-value
    "bbox_refs":          { "type": "text", "index": false },  // opaque JSON，不索引
    "text_layer_type":    { "type": "keyword" },
    "layout_confidence":  { "type": "float" }
  }
}
```

**ensureVectorSpace 仍维持 create-if-missing 语义**，不 patch existing。已存在 KB 切 ENHANCED 走 §11 运维契约。

### 5.12 RetrievedChunk + OpenSearchRetrieverService 扩展

[`RetrievedChunk.java`](../../framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/RetrievedChunk.java) 加 6 字段：

```java
private Integer pageNumber;
private Integer pageStart;
private Integer pageEnd;
private List<String> headingPath;
private String blockType;
private List<String> sourceBlockIds;
```

注意：`bboxRefs / textLayerType / layoutConfidence` **不进** RetrievedChunk —— 不是 SourceChunk 契约的一部分（§2.1）。它们只在 OS metadata + DB 列存在。如果未来 evidence preview / bbox highlighting 等业务需要，再单独扩。

[`OpenSearchRetrieverService.toRetrievedChunk`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java) 在现有 `kb_id / security_level / doc_id / chunk_index` 抽取后追加：

```java
// 用 ChunkLayoutMetadata 反序列化（构造一个临时 VectorChunk 包装 metadata Map）
VectorChunk meta = new VectorChunk(); meta.setMetadata(metaMap);
chunk.setPageNumber(ChunkLayoutMetadata.pageNumber(meta));
chunk.setPageStart(ChunkLayoutMetadata.pageStart(meta));
chunk.setPageEnd(ChunkLayoutMetadata.pageEnd(meta));
chunk.setHeadingPath(ChunkLayoutMetadata.headingPath(meta));
chunk.setBlockType(ChunkLayoutMetadata.blockType(meta));
chunk.setSourceBlockIds(ChunkLayoutMetadata.sourceBlockIds(meta));
```

### 5.13 SourceCardBuilder + SourceChunk 扩展

[`SourceChunk.java`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/dto/SourceChunk.java) 加同样 6 字段（§2.1）。

[`SourceCardBuilder`](../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCardBuilder.java) `chunksInDoc.stream().map(rc -> ...)` 内加：

```java
SourceChunk.builder()
        .chunkId(rc.getId())
        .chunkIndex(rc.getChunkIndex())
        .preview(truncateByCodePoint(rc.getText(), previewMaxChars))
        .score(rc.getScore())
        // PR 6 新增
        .pageNumber(rc.getPageNumber())
        .pageStart(rc.getPageStart())
        .pageEnd(rc.getPageEnd())
        .headingPath(rc.getHeadingPath())
        .blockType(rc.getBlockType())
        .sourceBlockIds(rc.getSourceBlockIds())
        .build();
```

### 5.14 Frontend Sources.tsx + types

`frontend/src/types/index.ts` 扩 SourceChunk interface：

```ts
export interface SourceChunk {
    chunkId: string;
    chunkIndex: number;
    preview: string;
    score: number;
    // PR 6 新增（全部 optional）
    pageNumber?: number;
    pageStart?: number;
    pageEnd?: number;
    headingPath?: string[];
    blockType?: string;
    sourceBlockIds?: string[];
}
```

`Sources.tsx` chunk 项渲染加 evidence 行（仅当 layout 字段存在时显示）：

```tsx
{chunk.pageNumber !== undefined && (
    <span className="text-xs text-muted-foreground mr-2">
        第 {chunk.pageStart && chunk.pageEnd && chunk.pageStart !== chunk.pageEnd
            ? `${chunk.pageStart}-${chunk.pageEnd}`
            : chunk.pageNumber} 页
    </span>
)}
{chunk.headingPath && chunk.headingPath.length > 0 && (
    <span className="text-xs text-muted-foreground">
        {chunk.headingPath.join(" › ")}
    </span>
)}
{chunk.blockType === "TABLE" && (
    <Badge variant="outline" className="text-xs ml-2">表格</Badge>
)}
```

`bbox_refs` / `textLayerType` / `layoutConfidence` **不在前端消费**（Non-Goal 5）。

## 6. Data Flow

### 6.1 ENHANCED 成功（happy path，覆盖大部分 ENHANCED 上传）

```
upload (parseMode=ENHANCED) 
  → ParseModeRouter.resolve → ProcessMode.PIPELINE
  → IngestionEngine.execute(enhanced-default, context)
     → ParserNode → DocumentParserSelector.selectByParseMode(ENHANCED) → FallbackParserDecorator(Docling, Tika)
        → DoclingDocumentParser.parse → DoclingClient → /v1alpha/convert
        → DoclingResponseAdapter → ParseResult { text, metadata, pages, tables }
        → context.setParseResult(result) + setRawText(result.text())
     → ChunkerNode → IngestionChunkingDispatcher
        → matched: [StructuredChunkingStrategy(p=100), LegacyAdapter(structure_aware, p=10)]
        → top priority unique = StructuredChunkingStrategy
        → chunk(input, options) → List<VectorChunk> with layout in metadata
     → embedding → IndexerNode (skipIndexerWrite=true, no-op)
  → KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically(chunks)
     → KnowledgeChunkDO 9 layout 列填值 → saveBatch
     → OpenSearchVectorStoreService.indexDocumentChunks(chunks) → metadata 透传 → OS 9 字段索引
  → 用户检索 → OpenSearchRetrieverService.toRetrievedChunk → RetrievedChunk 6 layout 字段
  → SourceCardBuilder → SourceChunk 6 字段
  → 前端渲染 "第 X 页 / 章节路径 / 段落|表格"
```

### 6.2 ENHANCED 但 Docling 失败（降级路径）

```
upload (parseMode=ENHANCED) 
  → ParseModeRouter.resolve → ProcessMode.PIPELINE
  → IngestionEngine.execute(enhanced-default, context)
     → ParserNode → DocumentParserSelector.selectByParseMode(ENHANCED) → FallbackParserDecorator(Docling, Tika)
        → DoclingDocumentParser.parse → DoclingClient.convert → IOException (sidecar down)
        → FallbackParserDecorator catches → fallback = Tika.parse
        → stamps metadata: parse_engine_actual=Tika, parse_fallback_reason=primary_failed
        → ParseResult { text, metadata, pages=[], tables=[] }   ← Tika 不产 layout
        → context.setParseResult(result) (pages/tables 空)
     → ChunkerNode → IngestionChunkingDispatcher
        → StructuredChunkingStrategy.supports() = false (pages/tables 都空)
        → LegacyAdapter(STRUCTURE_AWARE).supports() = true (settings.strategy=structure_aware 来自 v1.14 seed)
        → top priority unique = LegacyAdapter(STRUCTURE_AWARE, priority=10)
        → chunk(input, options) → 老 StructureAwareTextChunker.chunk(text, options)
        → List<VectorChunk> 无任何 layout metadata
     → 持久化：DB 9 layout 列全 NULL；OS metadata 无 9 个 layout key
  → 用户检索 → SourceChunk 6 字段全 null/undefined
  → 前端不显示 evidence 行，与 BASIC 视觉一致
  → ingestion 完成，未抛异常
```

### 6.3 BASIC + CHUNK（默认 BASIC 上传，PR 6 完全不动）

```
upload (parseMode=BASIC) 
  → ParseModeRouter.resolve → ProcessMode.CHUNK
  → KnowledgeDocumentServiceImpl.runChunkProcess（PR 6 不改）
     → parserSelector.select(TIKA).extractText(...) → text
     → chunkingStrategyFactory.requireStrategy(...) → 老 chunker.chunk(text, options) → List<VectorChunk> 无 layout
     → embedding
  → persistChunksAndVectorsAtomically(chunks) → DB 9 layout 列 NULL；OS metadata 无 layout key
  → 与 PR 5 末态字节对账
```

### 6.4 BASIC + PIPELINE（边缘组合，用户显式指定 pipelineId）

```
upload (parseMode=BASIC, pipelineId=xxx) 
  → ParseModeRouter.resolve → ProcessMode.PIPELINE
  → IngestionEngine.execute(xxx, context)
     → ParserNode → DocumentParserSelector.selectByParseMode(BASIC) → Tika
        → ParseResult { text, metadata, pages=[], tables=[] }
        → context.setParseResult(result)
     → ChunkerNode → IngestionChunkingDispatcher
        → StructuredChunkingStrategy.supports() = false
        → LegacyAdapter.supports() = true (按 settings.strategy 选 FIXED_SIZE 或 STRUCTURE_AWARE)
        → 老 chunker 输出 List<VectorChunk> 无 layout
     → 持久化：与 6.2 一致，全 null
  → 用户视觉与 BASIC + CHUNK 一致（前端无差别）
```

## 7. Persistence Contract

| 层 | 存储介质 | 格式 | 写入责任方 | 读取责任方 |
|---|---|---|---|---|
| **chunker 输出** | `VectorChunk.metadata: Map<String, Object>` | 内存对象，layout key 字符串值 | `StructuredChunkingStrategy`（仅）；调用 `ChunkLayoutMetadata.writer(...).build()` | `IndexerNode` 透传给 OS、`persistChunksAndVectorsAtomically` 抽到 DO |
| **OpenSearch metadata** | `_source.metadata` JSON object | 9 keyword/integer/float/text 字段 | `OpenSearchVectorStoreService.buildDocument` catch-all 透传 chunk.metadata | `OpenSearchRetrieverService.toRetrievedChunk` 用 `ChunkLayoutMetadata` 反序列化到 RetrievedChunk |
| **DB t_knowledge_chunk** | 9 个 SQL 列 | INTEGER / TEXT / VARCHAR / DOUBLE | `persistChunksAndVectorsAtomically` 抽 metadata + ObjectMapper 序列化 List/JSON | 暂无业务读路径（PR 6 范围内） |
| **RetrievedChunk** | POJO | 6 字段（无 bbox/textLayer/confidence） | `OpenSearchRetrieverService.toRetrievedChunk` | `SourceCardBuilder` |
| **SourceChunk** | POJO + JSON 序列化给前端 | 6 字段 | `SourceCardBuilder.buildCard` | 前端 Sources.tsx |

**SoT 链路**：
```
ChunkLayoutMetadata.writer (write side, 唯一)
        ↓
VectorChunk.metadata Map
        ↓
ChunkLayoutMetadata.reader (read side, 唯一)
        ↙              ↘
OS metadata 透传     DB 列抽取（service 层做 List→JSON 序列化）
```

helper 是双向唯一入口，避免 key 拼写漂移。

## 8. SourceChunk Schema Lockdown

见 §2.1。补充测试断言：

- BASIC 上传所有 6 字段 = `null` / `undefined`
- ENHANCED + Docling 成功：6 字段 ≥ 4 个非 null（最小集 = pageNumber + pageStart + pageEnd + blockType；headingPath 可能空数组、sourceBlockIds 可能空数组）
- ENHANCED + Docling 失败 fallback：6 字段全 null（与 BASIC 一致）

## 9. Frontend Changes

最小改动，全部 nullable + graceful 回退（参见 §5.14 代码片段）：

- 新增字段全部可选 → BASIC 上传文档检索结果不显示 evidence 行（与 PR 5 视觉一致）
- 跨页 chunk 显示 "第 N-M 页"，单页显示 "第 N 页"
- 表格类 chunk 显示 "表格" badge（绿/灰浅色），与段落区分
- 章节路径用中文 lt/gt 符号 "›" 串接（参考已有 breadcrumb 风格）

## 10. Test Plan（按 Unit / Integration-ish / Manual ops smoke 三段）

### 10.1 Unit

| 测试 | 类 | 覆盖 |
|---|---|---|
| `IngestionChunkingDispatcherTest` | `IngestionChunkingDispatcher` | (a) 正常 dispatch 走 priority；(b) 同优先级 fail-fast 抛 IllegalStateException 含类名；(c) no match 抛 IllegalStateException 含 input shape 摘要 |
| `StructuredChunkingStrategySupportsTest` | `StructuredChunkingStrategy.supports()` | (a) parseResult=null → false；(b) pages=[]+tables=[] → false；(c) pages 非空 → true（不看 mode）；(d) tables 非空 → true |
| `LegacyAdapterSupportsTest` | `LegacyTextChunkingStrategyAdapter.supports()` | mode 严格匹配；不看 input.parseResult |
| `LegacyAdapterNoLayoutWriteTest` | `LegacyTextChunkingStrategyAdapter.chunk()` | **关键回归**：调 chunk(input, options) 后断言所有 9 个 layout key **不在** chunk.metadata 里。锁定 "legacy 不写 layout" 契约 |
| `ChunkLayoutMetadataReadbackTest` | `ChunkLayoutMetadata` 三态读取 | List<String> 直读 / List 实例（OS 反序列化产物）/ JSON String 兜底 |
| `ChunkLayoutMetadataWriteSkipNullTest` | `ChunkLayoutMetadata.Builder` | null/empty 输入跳过写入；不写 `[]` 等无意义值 |
| `StructuredChunkingOptionsResolverTest` | `StructuredChunkingOptionsResolver` | TextBoundaryOptions / FixedSizeOptions（验证 30% 弹性 + 40% 下限）/ null → 默认值（1400, 1800, 600） |
| `StructuredChunkingStrategyChunkAlgorithmTest` | `StructuredChunkingStrategy.chunk()` | (a) title 边界正确切分；(b) 表格原子不切；(c) 跨页 chunk 的 pageStart/pageEnd 正确；(d) headingPath 在每个 chunk 上对齐；(e) v0.5.1 边界（textLayerType / confidence 全 null 透传） |

### 10.2 Integration-ish

测试命名遵循仓库 convention `*IntegrationTest`（参考 [`EnhancedRoutingIntegrationTest`](../../bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/EnhancedRoutingIntegrationTest.java)）。

| 测试 | 范围 | 覆盖 |
|---|---|---|
| `BasicChunkPathByteEquivalentIntegrationTest` | 全栈 BASIC + CHUNK | 上传 PDF（parseMode=basic）；断言 chunk 数 / content / chunkIndex 与 PR 5 末态一致；DB 9 layout 列 SELECT 全 NULL；OS `_source.metadata` 无 9 个 layout key |
| `BasicPipelineNoPhantomBlockTypeIntegrationTest` | BASIC + 显式 pipelineId | 上传 PDF（parseMode=basic + 用户传 pipelineId 强走 PIPELINE）；LegacyAdapter 接管；同 BasicChunk 断言；**特别断言** `block_type` 字段在 OS 与 DB 都不存在或为 NULL（修正 plan 隐性偏离） |
| `EnhancedDoclingFailureFallbackIntegrationTest` | ENHANCED + Docling 模拟失败 | mock DoclingClient 抛 IOException；FallbackParserDecorator 捕获；ParseResult.metadata 含 `parse_engine_actual=Tika, parse_fallback_reason=primary_failed`；StructuredChunkingStrategy.supports()=false；LegacyAdapter(structure_aware) 接管；chunks 无 layout；ingestion 完成 status=success |
| `EnhancedHappyPathLayoutEndToEndIntegrationTest` | ENHANCED 成功 | 上传 PDF（parseMode=enhanced）；Docling 成功；DB 9 列至少有 pageNumber/pageStart/pageEnd/headingPath/blockType/sourceBlockIds 非 NULL；OS 检索能命中 `term: { block_type: "PARAGRAPH" }`；SourceChunk 序列化包含 6 字段 |
| `RetrieverLayoutFieldRoundTripIntegrationTest` | 检索读路径 | 写入 chunk + layout metadata → OS → 检索 → 断言 RetrievedChunk 6 字段值与写入完全一致（特别 List<String> 不被错误序列化为 String） |

### 10.3 Manual ops smoke

| 验收项 | 步骤 | 期望 |
|---|---|---|
| OS mapping forward-only 升级 | 启动 PR 6 后端；新建 KB；触发 `ensureVectorSpace` | 新索引 `_mapping` 含 9 个 layout 字段；老索引 `_mapping` 不含（不 patch） |
| 已存在 KB 切 ENHANCED 迁移 | 选定 1 个 PR 6 之前的 BASIC KB；按 §11.2 跑 `DELETE /<collection>` + 全文档重 ingest | 新 chunks DB / OS 都有 layout；老半升级状态被消除 |
| BASIC + ENHANCED 视觉对照 | 同一份 PDF，分别用 BASIC / ENHANCED 上传到两个 KB；对话引用同一份 PDF | BASIC 卡片不显示 page/heading；ENHANCED 卡片显示 |
| Docling sidecar 关掉测试降级 | `docker stop docling`；ENHANCED 上传 | ingestion 不抛；DB layout 列 NULL；后端日志含 `parse_engine_actual=Tika, parse_fallback_reason=primary_failed` |

## 11. Operational Notes

### 11.1 OS mapping forward-only 政策

PR 6 仅扩 `OpenSearchVectorStoreAdmin` 的 mapping 定义。`ensureVectorSpace` 维持 create-if-missing；**不**自动 patch existing。

技术细节：OS index `metadata.dynamic=false` 意味着未声明字段虽然进 `_source` 但**不可被 term/range filter 命中**，也不索引。运维契约**禁止半升级状态**（不要靠 `_source` 的"看起来字段还在"）。

### 11.2 已存在 KB 切 ENHANCED 的迁移步骤

1. 后端服务可继续运行（无需停机），但目标 KB 要求**没有正在进行的 ingestion**
2. `curl -X DELETE http://localhost:9201/<kb-collection-name>` （需 NO_PROXY 设置见 gotcha §7）
3. 在 KB 管理页面把所有文档状态改为"待重新 ingest"（或后端管理工具批量触发）
4. 等 RocketMQ chunker 队列消费完毕
5. OS `_mapping` 校验：新索引应含 9 个 layout 字段
6. 抽样检索一份带 page 的 chunk，断言 SourceChunk 6 字段非空

文档归宿：`docs/dev/setup/docling-service.md` 加 "已有 KB 切 ENHANCED" 章节，引用本节步骤 + gotcha §4。

### 11.3 Milvus / PG 后端的 layout 字段缺失

PR 6 layout 字段只在 OpenSearch retriever 路径回流到 RetrievedChunk。Milvus / PG retriever 与 backlog SL-1 协同处理（不是本 PR 范围）。spec 在 `MilvusRetrieverService` / `PgRetrieverService` 类注释里加一行 TODO（不写代码），让未来读者知道有这个缺口。

## 12. Plan Deviations

本 spec 与 [`2026-05-05-parser-enhancement-docling.md`](../plans/2026-05-05-parser-enhancement-docling.md) PR 6 章节的 12 处偏离：

| # | Plan 写法 | Spec 写法 | 原因 |
|---|---|---|---|
| 1 | `StructuredChunk` record 作为 chunker 输出 | 直接产出 `VectorChunk`，layout 进 `metadata` Map | A' + Q6 的 bonus 简化：避免 `chunkEmbeddingService.embed(List<VectorChunk>)` 接口分裂 |
| 2 | `legacyTextChunkerAdapter` 是 ChunkerNode 内部 helper | `LegacyTextChunkingStrategyAdapter` 是 A' 抽象的一等公民、Spring bean | Q1 抽象层级清理 |
| 3 | ChunkerNode 内 `if (pages 非空)` 分支 | `IngestionChunkingDispatcher` priority + `supports()` capability dispatch | Q5 |
| 4 | LegacyAdapter 给 chunk 写 `blockType=PARAGRAPH` | LegacyAdapter 不写任何 layout 字段 | Q9 锁定 BASIC byte-equivalent；plan 此处是隐性偏离 |
| 5 | `persistChunksAndVectorsAtomically(... pages, structuredChunks)` | `persistChunksAndVectorsAtomically(... chunks)`（不加 pages 参数；不加 structuredChunks 参数） | Q4 page 表 Non-Goal + Q6 layout 在 chunks.metadata |
| 6 | 持久化 page 行到 `t_knowledge_document_page` | 不持久化 | Q4 Non-Goal 1 |
| 7 | SourceCard 加 `pageNumber / headingPath` | **SourceChunk** 加 6 字段（chunk 级 evidence） | Δ7 修正：page/heading 是 chunk 级，不是 doc 级 |
| 8 | OS mapping 升级未明示 | §5.11 forward-only 9 字段升级 | Δ2 |
| 9 | RetrievedChunk 已有 layout 字段（隐含） | RetrievedChunk 加 6 字段 + toRetrievedChunk 抽取 | Δ7 plan 漏层 |
| 10 | OcrFallbackParser / TextLayerQualityDetector 参与决策 | 不依赖（这些是 PR 4 占位接口，0 调用） | Δ9 |
| 11 | `ChunkingMode.LAYOUT_AWARE` 新枚举 + v1.14_to_v1.15 migration | 不加新枚举、不加新 migration；`structure_aware` 自然变 fallback 选择 | Q5 a 方案的 priority dispatch 让 seed 不需改 |
| 12 | textLayerType 由 plan aggregation 算出 | v0.5.1 永远 null；接受 null 不伪造 | Q7 |

## 13. Backlog 关联

PR 6 上线后新增 / 维持的 backlog 条目：

| ID | 范围 | 来源 |
|---|---|---|
| `PARSER-PAGE-PERSIST` | 激活 `t_knowledge_document_page`：写入页文本 + blocks_json schema 决策 + idempotency；激活 mapper 调用；为 evidence preview UI PR 准备 | Q4 Non-Goal 1 |
| `PARSER-OCR-PIPELINE` | OcrFallbackParser 接入：text-layer 质量检测决定走 OCR；OCR-derived layout 适配 | Δ9，未来真实 OCR 业务驱动时启动 |
| `PARSER-TEXT-LAYER-V0.6+` | Docling sidecar 升级到暴露 `textLayerType / confidence` 后，DoclingResponseAdapter 适配；DB / OS 列从 NULL 开始有数据 | Q7 / gotcha §8（已写）|
| `SL-1` | Milvus / PG retriever 支持 metadata filtering（含 PR 6 9 字段）+ security_level | gotcha §4，已存在 |
| `PARSER-ENHANCER-LAYOUT-ALIGN` | EnhancerNode-before-StructuredChunkingStrategy 的 enhancedText vs layout 对齐策略 | Non-Goal 3 |

---

## Appendix A. 决策追溯（brainstorm Q1-Q9）

详细决策过程见 brainstorm 历史。本 spec 锁定：

- **Q1**: A'（IngestionChunkingStrategy.supports + IngestionChunkingInput）
- **Q2**: Forward-only OS mapping
- **Q3**: a（IngestionContext 加 parseResult 单字段，rawText/document 维持兼容）
- **Q4**: b（page 表不激活）+ SourceChunk 6 字段为下游 Collateral PR 契约
- **Q5**: priority dispatch + settings.strategy 双义 + StructuredChunkingOptionsResolver + 同优先级 fail-fast
- **Q6**: layout 在 VectorChunk.metadata + ChunkLayoutMetadata helper（在 rag.core.vector）
- **Q7**: 接受 null 不伪造（v0.5.1 textLayerType / confidence 限制）
- **Q8**: CHUNK 路径不动
- **Q9**: LegacyAdapter 不写 layout + 3 个回归测试锁契约

## Appendix B. 不在 PR 6 范围的相邻议题

- frontend SourceChunk 渲染交互（点击展开 / 高亮 / bbox 标注）—— 进 future PR
- OS layout 字段做 retrieval re-ranking（用 `block_type=TITLE` 加分等）—— 进 future PR
- Collateral 单问单答字段查询（slot 抽取 / 字段值提取 / 答案分档）—— 紧随 PR 6 的下一个 feature PR
- evidence preview UI（点 chunk 弹出整页预览）—— 与 PARSER-PAGE-PERSIST 同 PR
- ChunkerNode embedding 解耦（embedding 上提到 IndexerNode）—— Δ11，PR 6 暂时保持耦合
