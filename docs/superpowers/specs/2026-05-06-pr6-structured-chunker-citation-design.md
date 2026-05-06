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
2. **不修改 CHUNK 处理路径**（[`KnowledgeDocumentServiceImpl.runChunkProcess`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)）。BASIC 上传仍硬编码走 Tika，与 PR 6 范围"PIPELINE 路径下的 layout-aware chunking"无关
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
| `pageNumber` | `Integer` | `number \| null` | OS metadata `page_number` | BASIC chunk / 跨多页 chunk 表示首页号；其他场景 null |
| `pageStart` | `Integer` | `number \| null` | OS metadata `page_start` | 跨页 chunk 起始页；单页 chunk 等于 `pageNumber`；BASIC chunk null |
| `pageEnd` | `Integer` | `number \| null` | OS metadata `page_end` | 跨页 chunk 结束页；单页 chunk 等于 `pageNumber`；BASIC chunk null |
| `headingPath` | `List<String>` | `string[] \| null` | OS metadata `heading_path` | 章节路径 `["第三章 风险管理", "3.2 信用风险"]`；BASIC null；ENHANCED 但无明确 heading 时为空数组 |
| `blockType` | `String`（来自 `BlockType.name()`）| `string \| null` | OS metadata `block_type` | `"PARAGRAPH"` / `"TABLE"` / `"TITLE"` 等 8 种枚举值的 name；BASIC null |
| `sourceBlockIds` | `List<String>` | `string[] \| null` | OS metadata `source_block_ids` | chunk 由哪些 LayoutBlock 拼成；BASIC null；ENHANCED 但 chunker 未填充时空数组 |

### 2.1.1 JSON 序列化策略（三层防御）

SourceChunk 6 个新字段全部 nullable，必须在三层都防住"假阳性渲染"：(a) null 假阳性、(b) `undefined !== null` 类型错觉、(c) **空数组 `[]` 通过 `@JsonInclude(NON_NULL)` 漏出**（v4 review P2 #5 修正）。三条契约同时生效：

1. **后端 Java 字段**：[`SourceChunk.java`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/dto/SourceChunk.java) 类级加 `@JsonInclude(JsonInclude.Include.NON_EMPTY)`（**不是 NON_NULL**）。`NON_EMPTY` 既跳 null，又跳空 `List` / 空 `String` / `Optional.empty()`。BASIC chunk 即使因为某种原因得到了空 `List`（如 `headingPath = List.of()`），序列化结果也不会出现 `"headingPath":[]`
2. **前端 TypeScript 类型**：在 `frontend/src/types/index.ts` 中定义为 `pageNumber?: number | null`（同时容忍 undefined 和 null —— 即使 Jackson 配置漂移漏了 NON_EMPTY，类型仍诚实）
3. **前端守卫**：`Sources.tsx` 中**统一用 `!= null`**（不是 `!== undefined`），覆盖 null 与 undefined 两种 falsy。**集合字段额外用 `chunk.headingPath?.length`**（不是 `chunk.headingPath != null`）—— 这条同时拒 null / undefined / 空数组，与 `NON_EMPTY` 后端契约对称
4. **后端 reader 返回 null**：[`ChunkLayoutMetadata.headingPath / sourceBlockIds`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/ChunkLayoutMetadata.java) 在 metadata Map 缺 key 时返回 `null`（不是 `Collections.emptyList()`），让"无信息"在 Java 侧就具体地是 null 而非空集合（v4 review P2 #5）

四层防御：后端 `@JsonInclude(NON_EMPTY)` + 后端 reader 返 null + 前端类型双容忍 + 前端守卫 `!= null` / `?.length`。任意三层失效仍能拦下假阳性。

### 2.2 value-in-paragraph guard 契约

Collateral PR 的 tier-A 降级判定是"抽取的字段值必须在 chunk text 中能匹配上"。**这个匹配必须用 [`RetrievedChunk.text()`](../../../framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/RetrievedChunk.java) 的全文做，不能用 `SourceChunk.preview` 截断版**。原因：

- `SourceChunk.preview` 是 UI 展示截断（[`SourceCardBuilder.previewMaxChars`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCardBuilder.java) 控制）
- 如果 value 在 chunk 末尾、preview 已截断，guard 会假阴性，正确答案被错误降级
- guard 在服务端 composer 里完成，时机早于 SourceCard/SourceChunk 构造，天然有 RetrievedChunk.text() 可用

PR 6 不实现 guard 本身（这是 Collateral PR 的事），但**spec 在此处显式记录这条契约**，作为下游 PR 的输入。

## 3. Discovery Deltas vs Original Plan

Brainstorm 阶段对 main HEAD `a03a4acf` 的 4 路并行代码调查发现 **12 处 plan 与现实不符**。Spec 处理方式如下：

| # | Delta | Plan 假设 | 现实 | Spec 处理 |
|---|---|---|---|---|
| 1 | ParseResult.pages/tables 在 ParserNode 被丢弃 | "PR 5 已写 ParseResult 到 context" | [`ParserNode.java:88-96`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/ingestion/node/ParserNode.java) 只取 `text()` + `metadata()`，pages/tables 立即丢失；IngestionContext 无 `parseResult` 字段 | §5.9 ParserNode 改写 + IngestionContext 加 `parseResult` 字段 + 5 条 SoT 约定（§4.2） |
| 2 | OS index `dynamic:false` 拒收新字段 | "OS 能直接存 layout metadata" | [`OpenSearchVectorStoreAdmin.java:260-299`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java) 的 metadata mapping `dynamic:false`；9 个 layout 字段全缺；未声明字段虽然进 `_source` 但**不可被 term/range filter 命中**，也不索引；`ensureVectorSpace` 仅 create-if-missing | §5.11 OS mapping forward-only 升级 + §11 运维契约（已存在 KB 切 ENHANCED 走 DELETE+re-ingest，半升级状态明确禁止） |
| 3 | 老 `ChunkingStrategy` 接口签名异构 | "StructuredChunkingStrategy 实现同接口" | 现接口 `chunk(String text, ChunkingOptions config)` 只吃 String；StructuredChunkingStrategy 需要的 `(pages, tables, options)` 异构 | §5.1-5.5 引入新顶层接口 `IngestionChunkingStrategy` + `IngestionChunkingInput` 输入模型 + LegacyAdapter 包装老 strategy |
| 4 | v0.5.1 不暴露 textLayerType/confidence | Plan 设计了 `textLayerTypeForRange` / `averageConfidence` aggregation | [`DoclingResponseAdapter.java:207-216`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/core/parser/DoclingResponseAdapter.java) 这两个字段永远 null；plan aggregation 自然返回 null | §6.4 接受 null 不伪造；DB / OS 列保持空；§12 标记为 known limitation |
| 5 | parseMode 没传到 ChunkerNode | Plan 假设可用 | ParserNode 读 parseMode 但不存 context | §5.9 IngestionContext 已有 `parseMode: String` 字段（PR 4 加的）；§5.2 IngestionChunkingInput 通过 `IngestionContext.parseMode` 读 |
| 6 | KnowledgeChunkDO 9 字段无 typeHandler | Plan 用 LambdaUpdateWrapper.set | gotcha §2 警告：typeHandler 不会触发，会写字面字符串 | §5.11 持久化全程走 entity-based `chunkMapper.insert(List)`（仓库实际 API，非 `saveBatch`）；headingPath/sourceBlockIds 的 List → JSON 序列化由 `KnowledgeChunkLayoutMapper.copyToCreateRequest`（唯一入口，§5.10 定义）完成，不散落在 service 层多处 |
| 7 | RetrievedChunk 不读 layout | Plan 直接 chunk.pageNumber 用 | [`OpenSearchRetrieverService.toRetrievedChunk`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java) 只抽 4 字段 | §5.13 扩 RetrievedChunk + 扩 toRetrievedChunk 字段映射；用 ChunkLayoutMetadata reader 反序列化 |
| 8 | Page table mapper 0 调用 | Plan 写到 `persistChunksAndVectorsAtomically` | [`KnowledgeDocumentPageMapper`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/dao/mapper/KnowledgeDocumentPageMapper.java) 全仓 0 调用 | §1.2 Non-Goal 1：PR 6 不激活，签名不接受 `pages` 参数 |
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
| `ChunkLayoutMetadata.java` | `bootstrap/.../rag/core/vector` | layout 字段在 `VectorChunk.metadata` Map 上的类型化访问层：**纯 vector core 抽象，不依赖任何 knowledge / parser 域类型**。仅 2 类 API：(a) reader 静态方法（含三态兼容：List / String[] / JSON String 兜底解析）、(b) `Builder` writer。**DO/CreateRequest 桥接交给 `KnowledgeChunkLayoutMapper`**（v3 review 锁定的层级边界）|
| `KnowledgeChunkLayoutMapper.java` | `bootstrap/.../knowledge/service/support` | **v3 review 引入**：DO ↔ VectorChunk + VectorChunk ↔ CreateRequest 双向桥接。`copyFromDO(DO, vc)` 用于 re-index 路径，`copyToCreateRequest(vc, req)` 用于 persist 路径。内部用 `ObjectMapper` 处理 `headingPath` / `sourceBlockIds` 的 `List ↔ JSON String`。`@Component` 由 Spring 注入到 `KnowledgeDocumentServiceImpl` + `KnowledgeChunkServiceImpl` |

**修改（13 个 Java/TS + 3 个文档）**：

| 文件 | 改动 |
|---|---|
| `IngestionContext.java` | 加 `parseResult: ParseResult` 字段 |
| `ParserNode.java` | parse 后写 `setParseResult(result)`（同步保留 setRawText） |
| `ChunkerNode.java` | rewire 走 `IngestionChunkingDispatcher`（替换原 `chunkingStrategyFactory.requireStrategy(...)`） |
| `KnowledgeDocumentServiceImpl.java` | **签名不变**（`persistChunksAndVectorsAtomically` 仍然 `(coll, docId, kbId, sec, chunks)`）；line 323-331 的 VectorChunk → CreateRequest 转换体内**新增** layout 字段拷贝；DB 9 列填值由扩展后的 `KnowledgeChunkCreateRequest` 承载 |
| `KnowledgeChunkCreateRequest.java` | **加 9 个 nullable layout 字段**（pageNumber/pageStart/pageEnd/headingPath/blockType/sourceBlockIds/bboxRefs/textLayerType/layoutConfidence；headingPath / sourceBlockIds 字段类型为 `String` 已序列化 JSON，由 service 层提前 ObjectMapper 处理）|
| `KnowledgeChunkServiceImpl.java` | (a) `batchCreate` line 220-232 的 `KnowledgeChunkDO.builder()` 块加 9 字段拷贝；(b) **5 处 VectorChunk.builder() 调用点**（line 244 / 303 / 434 / 534 + KnowledgeDocumentServiceImpl line 786）必须用 `KnowledgeChunkLayoutMapper.copyFromDO(do, vc)` 把 DO 9 字段重新填入 `vc.getMetadata()`，否则 enable/disable/单 chunk 编辑等再索引路径会丢 layout（这是 review P1 #1 锁定的契约） |
| `OpenSearchVectorStoreAdmin.java` | metadata mapping 加 9 个 layout 字段类型声明 |
| `RetrievedChunk.java` | 加 6 个 nullable 字段 |
| `OpenSearchRetrieverService.java` | `toRetrievedChunk` 用 `ChunkLayoutMetadata` 抽 layout |
| `SourceChunk.java` | 加 6 个 nullable 字段；类级 `@JsonInclude(JsonInclude.Include.NON_EMPTY)`（v4 P2 #5：跳 null + 空 List） |
| `SourceCardBuilder.java` | RetrievedChunk → SourceChunk 时映射 6 个字段 |
| `frontend/src/types/index.ts` + `frontend/src/components/chat/Sources.tsx` | TS interface 6 字段（`number \| null` / `string[] \| null` 而非 `?: number`）+ 卡片头部用 `!= null` 守卫渲染 page/heading |
| `docs/dev/setup/docling-service.md` | 加"已存在 KB 切 ENHANCED 的迁移步骤"章节（§11.2）|
| `docs/dev/followup/backlog.md` | 加 5 条 backlog 条目（PARSER-PAGE-PERSIST / PARSER-OCR-PIPELINE / PARSER-TEXT-LAYER-V0.6+ / PARSER-ENHANCER-LAYOUT-ALIGN，加上已有 SL-1 的关联说明）|
| `docs/dev/gotchas.md` §8 | 加新条："PR 6 起 layout 字段双路径写入"——chunker 阶段写 metadata Map（被 OS catch-all 透传写入索引）+ persist 阶段抽到 KnowledgeChunkDO 9 列；DB 与 OS 必须保持双侧一致；任何 re-index 路径必须用 `KnowledgeChunkLayoutMapper.copyFromDO` 从 DO 反向回填 metadata Map |

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

完整 chunking 算法见 plan Task 6.1 Step 2 [`line 2783-2925`](../plans/2026-05-05-parser-enhancement-docling.md#L2783)，本 spec 偏离仅限于 `StructuredChunk` record → `VectorChunk + ChunkLayoutMetadata.writer` 形态切换。

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

**边界（v4 严格措辞）**：

- helper 只做 `VectorChunk.metadata: Map<String, Object>` 的类型化 access；**不依赖任何 knowledge 域 / parser 域类型**
- **不做外部存储的序列化写入，仅在读取时兼容 JSON String** —— reader 路径兜底反序列化 DB-derived 数据（如 `headingPath` 的 JSON 字符串），writer 路径不负责把 `List<String>` 序列化为 JSON String（这是 persist 层的职责）
- **writer 对 `BlockType` 解耦**：调用方传 `BlockType.name()` String，`rag.core.vector` 不 import `core.parser.layout.BlockType`
- **DO ↔ VectorChunk 与 CreateRequest ↔ VectorChunk 的桥接由 `knowledge.service.support.KnowledgeChunkLayoutMapper` 承担**（见 §5.10），**不在本 helper 内**。这条是 v3 review 锁定的层级边界：`rag.core.vector` 不可反向 import `knowledge.dao.entity.KnowledgeChunkDO` 或 `knowledge.controller.request.KnowledgeChunkCreateRequest`
- **reader 返回 `null`（不是空 List）来表达 "key 缺失"**（v4 review P2 #5 fix）。这条配合 `@JsonInclude(NON_EMPTY)`（§5.14）共同保证 BASIC chunk 的 `headingPath / sourceBlockIds` **不会以 `[]` 形式出现在 SourceChunk 序列化结果**。具体语义：`null` = "无信息"；`List.of()` = "信息存在但项数为零"（理论极端，writer 已跳过空 list 写入，所以这种状态在实践中不会出现）；`非空 List` = "实际数据"

**收口理由（更新）**：v2 spec 把跨域桥接塞进 `ChunkLayoutMetadata` 是为"写入唯一入口"图省事，但代价是把 vector core 拖进 knowledge DTO 的依赖网。v3 修正：vector core 守住 Map 操作，桥接职责下放给 knowledge 域自己的适配层 —— 那里本来就 import 这些 DTO。两层各自只做职责内的事：

```
rag.core.vector.ChunkLayoutMetadata
    ↓ 提供 reader / writer Builder API
knowledge.service.support.KnowledgeChunkLayoutMapper
    ↓ 在调用 ChunkLayoutMetadata 的同时处理 DO/CreateRequest 字段映射 + List↔JSON 序列化
KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically
KnowledgeChunkServiceImpl.batchEnableChunks / update / 等 re-index 路径
```

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

### 5.10 `KnowledgeChunkLayoutMapper`（新组件，在 `knowledge.service.support`）

**v3 review 引入**：作为 `rag.core.vector.ChunkLayoutMetadata` 与 knowledge 域 DTO（`KnowledgeChunkDO` / `KnowledgeChunkCreateRequest`）之间的桥接层，让 vector core 不必反向 import knowledge 域类型。这条契约在 PR 6 spec 是层级整洁的关键。

**包路径**：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/support/KnowledgeChunkLayoutMapper.java`（与 `ParseModePolicy` / `ParseModeRouter` 同包）

**职责**：

1. **DO → VectorChunk.metadata 反填**（re-index 路径）：从 `KnowledgeChunkDO` 9 个 layout 列读出，调 `ChunkLayoutMetadata.writer(...)` 写回 metadata Map；JSON String 字段（`headingPath` / `sourceBlockIds`）用 `ObjectMapper` 反序列化为 `List<String>`
2. **VectorChunk.metadata → CreateRequest 抽取**（persist 路径）：从 metadata Map 用 `ChunkLayoutMetadata` reader 读出，写到 `KnowledgeChunkCreateRequest` 9 个新字段；`List<String>` 字段用 `ObjectMapper` 序列化为 JSON String

**为什么必须放在 knowledge 域**（不放 rag.core.vector）：

- `KnowledgeChunkDO` 在 `knowledge.dao.entity`，`KnowledgeChunkCreateRequest` 在 `knowledge.controller.request` —— 都是 knowledge 域内部抽象
- `rag.core.vector.ChunkLayoutMetadata` 是"vector metadata 契约的类型化访问层"，向上对 chunker / retriever / source-builder 提供服务，**它不应该知道 knowledge 域 DTO 长什么样**
- 反向：`knowledge` 依赖 `rag.core.vector` 是合规方向（业务层依赖通用契约）

**实现**：

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

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeChunkLayoutMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /**
     * DB 反向回填：DO 9 字段 → VectorChunk.metadata。用于所有从 DO 重建 VectorChunk 写回 OS 的 re-index 路径
     * （KnowledgeChunkServiceImpl 中的 batchEnableChunks / update 等）。
     *
     * <p>headingPath / sourceBlockIds 在 DO 中是 JSON String，本方法用 ObjectMapper 解出 List<String>
     * 后调 ChunkLayoutMetadata.writer 写入；JSON 解析失败仅 log warn，不抛 —— 保证 re-index 路径不
     * 因历史脏数据中断。
     */
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

    /**
     * Persist 序列化：VectorChunk.metadata → CreateRequest 9 字段。
     * 用于 KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically 的 stream map 中。
     *
     * <p>List<String> → JSON String 在此处发生（**写路径唯一入口**），避免散落在调用方多处。
     */
    public void copyToCreateRequest(VectorChunk source, KnowledgeChunkCreateRequest target) {
        if (source == null || target == null || source.getMetadata() == null) return;
        target.setPageNumber(ChunkLayoutMetadata.pageNumber(source));
        target.setPageStart(ChunkLayoutMetadata.pageStart(source));
        target.setPageEnd(ChunkLayoutMetadata.pageEnd(source));
        target.setBlockType(ChunkLayoutMetadata.blockType(source));
        target.setBboxRefs(ChunkLayoutMetadata.bboxRefs(source));
        target.setTextLayerType(ChunkLayoutMetadata.textLayerType(source));
        target.setLayoutConfidence(ChunkLayoutMetadata.layoutConfidence(source));

        List<String> hp = ChunkLayoutMetadata.headingPath(source);
        target.setHeadingPath(hp.isEmpty() ? null : toJson(hp));
        List<String> sb = ChunkLayoutMetadata.sourceBlockIds(source);
        target.setSourceBlockIds(sb.isEmpty() ? null : toJson(sb));
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

**注入点**：

- `KnowledgeDocumentServiceImpl` 注入 mapper（用于 §5.11 persist 路径的 stream map）
- `KnowledgeChunkServiceImpl` 注入 mapper（用于 §6.5 re-index 路径的 5 处 VectorChunk.builder 调用点）

### 5.11 持久化路径：复用 `batchCreate`，扩展 `KnowledgeChunkCreateRequest`

**关键事实修正**（spec v1 笔误，v2 修正）：现有 [`KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically:312-346`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java) 是已经在 main 上的真实路径：

```java
// main 上的现状（PR 6 不改签名、不改事务结构）
private int persistChunksAndVectorsAtomically(
        String collectionName, String docId, String kbId, Integer securityLevel,
        List<VectorChunk> chunkResults) {
    vectorStoreAdmin.ensureVectorSpace(...);

    // ↓ line 323-331：VectorChunk → CreateRequest 转换体
    List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
            .map(vc -> {
                KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                req.setChunkId(vc.getChunkId());
                req.setIndex(vc.getIndex());
                req.setContent(vc.getContent());
                return req;
            })  // ← 这里 layout 字段被丢
            .toList();

    transactionOperations.executeWithoutResult(status -> {
        knowledgeChunkService.deleteByDocId(docId);
        knowledgeChunkService.batchCreate(docId, chunks);                                       // ← DB 写：CreateRequest → DO
        vectorStoreService.deleteDocumentVectors(collectionName, docId);
        vectorStoreService.indexDocumentChunks(collectionName, docId, kbId, securityLevel, chunkResults);  // ← OS 写：用原始 VectorChunk
        documentMapper.updateById(...);
    });
    return chunks.size();
}
```

**关键不对称要先理清**：

- OS 写路径用 `chunkResults`（原始 VectorChunk 全部 metadata 健在），所以 OS 索引能拿到 9 字段 layout（catch-all 透传成立）
- DB 写路径走 [`KnowledgeChunkCreateRequest`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/controller/request/KnowledgeChunkCreateRequest.java)（当前只有 `content / index / chunkId` 3 字段）作为中转 DTO —— **layout 在 stream map 处被丢**，DB 9 列写入永远 NULL

PR 6 修正策略：**扩展 CreateRequest + 在 stream map 处用 `KnowledgeChunkLayoutMapper.copyToCreateRequest` 拷贝 + `batchCreate` 内的 DO 构造也加 9 字段拷贝**。`persistChunksAndVectorsAtomically` 签名 / 事务结构 / OS 写入逻辑全部不动 —— 这是与原 plan（plan 想加 `pages` 参数）的关键偏离。

#### 5.11.1 扩展 `KnowledgeChunkCreateRequest`

```java
@Data
public class KnowledgeChunkCreateRequest {
    /** 现有字段不动 */
    private String content;
    private Integer index;
    private String chunkId;

    /** PR 6 新增：layout 字段，全 nullable。
     *  manual chunk creation API（KnowledgeChunkController）不会填（手工 chunk 没 layout），
     *  ingestion persist 路径由 KnowledgeChunkLayoutMapper.copyToCreateRequest 自动填。
     *  headingPath / sourceBlockIds 与 KnowledgeChunkDO 同形态（已序列化为 JSON String）。 */
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

#### 5.11.2 修改 `persistChunksAndVectorsAtomically` 的 stream map 块（注入 mapper + 加一行调用）

`KnowledgeDocumentServiceImpl` 通过 `@RequiredArgsConstructor` 注入 `KnowledgeChunkLayoutMapper layoutMapper` 字段；stream map 内调用 instance 方法：

```java
List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
        .map(vc -> {
            KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
            req.setChunkId(vc.getChunkId());
            req.setIndex(vc.getIndex());
            req.setContent(vc.getContent());
            layoutMapper.copyToCreateRequest(vc, req);   // ← PR 6 新增唯一一行，9 layout 字段一次性拷过去
            return req;
        })
        .toList();
```

#### 5.11.3 修改 `KnowledgeChunkServiceImpl.batchCreate` 的 DO 构造块（line 220-232）

注意 DO 字段名是 **`content`**（不是 spec v1 笔误的 `chunkContent`），仓库实际使用 **`chunkMapper.insert(chunkDOList)`**（不是 `saveBatch`）：

```java
KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
        .id(chunkId)
        .kbId(kbId)
        .docId(docId)
        .chunkIndex(chunkIndex)
        .content(content)                          // ← 字段名是 content
        .contentHash(SecureUtil.sha256(content))
        .charCount(content.length())
        .tokenCount(resolveTokenCount(content))
        // PR 6 新增 9 字段：直接从扩展后的 CreateRequest 字段拷贝（CreateRequest 已携带 JSON String 形态）
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
// ... line 237 现有 chunkMapper.insert(chunkDOList) 不变
```

#### 5.11.4 持久化路径责任分工

| 步骤 | 负责方 | 数据形态 |
|---|---|---|
| chunker 输出 | `StructuredChunkingStrategy` | `VectorChunk.metadata: Map<String, Object>` 含 `List<String>` 等原生类型 |
| VectorChunk → CreateRequest | `KnowledgeChunkLayoutMapper.copyToCreateRequest` | `List<String>` → `JSON String`（**唯一**做 List→JSON serialize 的入口）|
| CreateRequest → DO | `KnowledgeChunkServiceImpl.batchCreate` | 字段直接拷贝（CreateRequest 已是最终形态）|
| DO → DB 列 | `chunkMapper.insert(List)` | 各字段已是 SQL 兼容类型（INTEGER / VARCHAR / TEXT / DOUBLE）|
| OS 写入 | `vectorStoreService.indexDocumentChunks(..., chunkResults)` | 原始 `List<VectorChunk>` 含 metadata Map，`OpenSearchVectorStoreService.buildDocument` catch-all 透传 |

**`gotcha §2` typeHandler 陷阱不会触发**：因为持久化全程走 entity-based `chunkMapper.insert(List)`，没有任何 `LambdaUpdateWrapper.set` 路径碰 jsonb / 集合字段。

### 5.12 OS mapping forward-only 升级

[`OpenSearchVectorStoreAdmin.java`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java) `metadata.properties` 加 9 个字段（与 [`VectorMetadataFields`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/VectorMetadataFields.java) 常量对齐）：

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

### 5.13 RetrievedChunk + OpenSearchRetrieverService 扩展

[`RetrievedChunk.java`](../../../framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/RetrievedChunk.java) 加 6 字段：

```java
private Integer pageNumber;
private Integer pageStart;
private Integer pageEnd;
private List<String> headingPath;
private String blockType;
private List<String> sourceBlockIds;
```

注意：`bboxRefs / textLayerType / layoutConfidence` **不进** RetrievedChunk —— 不是 SourceChunk 契约的一部分（§2.1）。它们只在 OS metadata + DB 列存在。如果未来 evidence preview / bbox highlighting 等业务需要，再单独扩。

[`OpenSearchRetrieverService.toRetrievedChunk`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java) 在现有 `kb_id / security_level / doc_id / chunk_index` 抽取后追加：

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

### 5.14 SourceCardBuilder + SourceChunk 扩展

[`SourceChunk.java`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/dto/SourceChunk.java) 加同样 6 字段（§2.1）。

[`SourceCardBuilder`](../../../bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/source/SourceCardBuilder.java) `chunksInDoc.stream().map(rc -> ...)` 内加：

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

### 5.15 Frontend Sources.tsx + types

`frontend/src/types/index.ts` 扩 SourceChunk interface：

```ts
export interface SourceChunk {
    chunkId: string;
    chunkIndex: number;
    preview: string;
    score: number;
    // PR 6 新增（与后端 @JsonInclude(NON_NULL) 对齐：null 字段在 wire 上不出现 → 前端字段为 undefined；
    // 但 cache 漂移 / 历史数据仍可能产生 null，所以类型用 ?: T | null 同时覆盖 undefined 和 null）
    pageNumber?: number | null;
    pageStart?: number | null;
    pageEnd?: number | null;
    headingPath?: string[] | null;
    blockType?: string | null;
    sourceBlockIds?: string[] | null;
}
```

`Sources.tsx` chunk 项渲染加 evidence 行（守卫一律用 `!= null` 同时拒 null 与 undefined，配合可选链处理数组）：

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

**注意**：所有守卫均用 `!= null`（不是 `!== undefined`），同时拒 null 与 undefined。后端 `@JsonInclude(NON_NULL)` 让 null 字段在 wire 上不出现 → 前端字段值为 `undefined`；但若 Jackson 配置漂移、或上游 cache 有历史 null 值，前端 `!= null` 守卫仍能阻止假阳性渲染。这就是 §2.1.1 锁定的"双侧防御"。

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
     → stream map: VectorChunk → KnowledgeChunkCreateRequest（含 KnowledgeChunkLayoutMapper.copyToCreateRequest 把 layout 拷过去）
     → knowledgeChunkService.batchCreate(docId, requests)
         → KnowledgeChunkDO.builder() 含 9 layout 字段（CreateRequest → DO 直接拷贝）
         → chunkMapper.insert(List)  // entity-based，不走 LambdaUpdate
     → vectorStoreService.indexDocumentChunks(collectionName, docId, kbId, sec, chunkResults)  // 用原始 VectorChunk，metadata 透传
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

### 6.5 Re-index 路径（disable → enable / 单 chunk 编辑 / re-embed）—— review P1 #1 锁定

ENHANCED 文档完成 ingestion 之后，多种业务操作会**重新构建 OS 索引**。**v4 review P1 #1 修正：明确两条不同的代码形态**：

| 路径 | 数据源 | 修正方式 |
|---|---|---|
| `KnowledgeChunkServiceImpl.batchEnableChunks` line 432-455 | `chunkMapper.selectList(...)` 直接读 **DO** | builder 后紧跟 `layoutMapper.copyFromDO(do, vc)` |
| `KnowledgeChunkServiceImpl.update` line 285+ | `chunkMapper.selectById` 读 **DO** | 见 §6.6（manual edit 契约） |
| `KnowledgeChunkServiceImpl` 单 chunk add line 152 / 303 / 534 | 已有 DO 直接构造 | builder 后紧跟 `layoutMapper.copyFromDO(do, vc)` |
| **`KnowledgeDocumentServiceImpl` 文档级 enable line 784** | `knowledgeChunkService.listByDocId(docId)` → **VO**（`KnowledgeChunkVO` 没 9 layout 字段）| **改路径**：line 784 改为 `chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(::getDocId, docId))` 直接拿 DO，再走同 `copyFromDO` 模式 |

**关键决策（v4）**：文档级 enable 路径**不**通过 VO（也就**不**扩 `KnowledgeChunkVO` + 加 `copyFromVO`）。理由：

1. `KnowledgeChunkVO` 是 controller→frontend 的展示形态，加 9 个 layout 字段是为内部实现需要污染 API 出参 schema
2. 直接走 mapper 查 DO 是更短的路径；VO 在这里本身就只用于"读 chunkId / content / chunkIndex" 3 个字段，去 service-VO 间接性反而绕远
3. ArchUnit 等机制里 `KnowledgeDocumentServiceImpl` 注入 `KnowledgeChunkMapper` 已是合规依赖（service 域可以直接调 mapper）

PR 6 修正：**所有 5 处 VectorChunk.builder 调用点路径全部改为"读 `KnowledgeChunkDO` → builder 后紧跟 `layoutMapper.copyFromDO(do, vc)`"**：

```
disable 全文档 / 部分 chunk
  → chunkMapper.update(...).set(enabled, 0)
  → vectorStoreService.deleteChunksByIds(collectionName, ids)   // OS chunks 物理删

enable 回来
  → needUpdateChunks = chunkMapper.selectList(...)               // 从 DB 读，含 9 layout 列
  → vectorChunks = needUpdateChunks.stream().map(c -> {
        VectorChunk vc = VectorChunk.builder()
            .chunkId(c.getId()).content(c.getContent()).index(c.getChunkIndex())
            .build();
        layoutMapper.copyFromDO(c, vc);                          // ← PR 6 关键新增（instance 调用）
        return vc;
    }).toList();
  → attachEmbeddings(vectorChunks, embeddingModel)                // 重新计算 embedding
  → vectorStoreService.indexDocumentChunks(coll, docId, kbId, sec, vectorChunks)  // OS 重写，layout 透传回去
```

**测试锁**：`ChunkServiceReindexCallSitesMockTest`（§10.2，v4 review P1 #4）通过统一 helper `buildVectorChunkFromDO` 间接锁定 5 处 call site 都不会丢 layout —— 任何 call site 绕开 helper 自己 builder 都会被 review 灯红。

### 6.6 手工编辑 chunk content 的 layout 契约（v3 review P2 #3 锁定）

`KnowledgeChunkServiceImpl.update`（line 285-310）允许管理员编辑单个 chunk 的 `content`。这与 §6.5 的 disable→enable 路径不同 —— **content 已被人工改写，原 layout 元数据可能不再忠于"chunk 实际文本"**：

| 字段 | 编辑后是否仍然有效？ | 处理策略 |
|---|---|---|
| `pageNumber` / `pageStart` / `pageEnd` | ✅ chunk 仍来自该文档该页范围 | **不清** |
| `headingPath` | ✅ chunk 仍属于该章节 | **不清** |
| `blockType` | ✅ 这个 chunk 概念上仍是段落/表格/标题 | **不清** |
| `sourceBlockIds` | ❌ 已不忠于改写后的文本 | **清空（NULL / 空数组）** |
| `bboxRefs` | ❌ 坐标盒指向的 PDF 区域文字与 chunk 内容已不匹配 | **清空** |
| `textLayerType` | ❌ 改写后已不是 NATIVE_TEXT 抽出 | **清空** |
| `layoutConfidence` | ❌ 解析器置信度对人工编辑的文本无意义 | **清空** |

**契约**：

- 保留：**5 个 "document location" 字段**（`pageNumber` / `pageStart` / `pageEnd` / `headingPath` / `blockType`）。理由：chunk 在文档中**所处的位置**没变。
- 清除：**4 个 "extraction-specific" 字段**（`sourceBlockIds` / `bboxRefs` / `textLayerType` / `layoutConfidence`）。理由：这些反映的是**机器解析的具体产物**，与人工改写后的文本失去对应关系。

**实现位置 + 关键 gotcha**：`KnowledgeChunkServiceImpl.update` 必须**走 `LambdaUpdateWrapper.set` 显式 null clear**，不能用 `chunkMapper.updateById(chunkDO)` 配 setter null 赋值 —— 后者在 MyBatis Plus 默认 `FieldStrategy.NOT_NULL` 下**会跳过 null 字段**，DB 里 4 个 extraction 列**不会被清空**（gotcha §2 警告过的同型陷阱）。

```java
// PR 6 / v4 review P1 #2 fix：用 LambdaUpdateWrapper.set 显式发送 NULL 给 SQL，
// 绕过 MyBatis Plus FieldStrategy.NOT_NULL 默认对 null 字段的跳过。
// 9 layout 列均无 typeHandler 注解（gotcha §2 验证过），LambdaUpdateWrapper.set 安全。
chunkMapper.update(null, Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
        .eq(KnowledgeChunkDO::getId, chunkId)
        .set(KnowledgeChunkDO::getContent, newContent)
        .set(KnowledgeChunkDO::getContentHash, SecureUtil.sha256(newContent))
        .set(KnowledgeChunkDO::getCharCount, newContent.length())
        .set(KnowledgeChunkDO::getTokenCount, resolveTokenCount(newContent))
        .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
        // PR 6：清 4 个 extraction-specific 字段（保留 5 个 location 字段，不出现在 set 列表里 = 不动）
        .set(KnowledgeChunkDO::getSourceBlockIds, null)
        .set(KnowledgeChunkDO::getBboxRefs, null)
        .set(KnowledgeChunkDO::getTextLayerType, null)
        .set(KnowledgeChunkDO::getLayoutConfidence, null));

// 重读最新 DO（含保留的 location 字段 + 已清空的 extraction 字段）然后 copyFromDO 写 OS
KnowledgeChunkDO refreshed = chunkMapper.selectById(chunkId);
VectorChunk vc = VectorChunk.builder()
        .chunkId(refreshed.getId())
        .content(refreshed.getContent())
        .index(refreshed.getChunkIndex())
        .build();
layoutMapper.copyFromDO(refreshed, vc);   // location 字段进 metadata；extraction 字段已 null，被 writer 跳过
attachEmbeddings(List.of(vc), embeddingModel);
vectorStoreService.indexDocumentChunks(collectionName, docId, kbId, securityLevel, List.of(vc));
```

**测试锁**：`ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest`（§10.2 加）—— 编辑 chunk content 后断言：DB 5 个 location 列保持原值；4 个 extraction 列**确实**变 NULL（用 `chunkMapper.selectById` 查实际行而非依赖 entity setter，验证 SQL 真发出去了）；OS metadata 同样 5 在 4 不在。

## 7. Persistence Contract

| 层 | 存储介质 | 格式 | 写入责任方 | 读取责任方 |
|---|---|---|---|---|
| **chunker 输出** | `VectorChunk.metadata: Map<String, Object>` | 内存对象，layout key 字符串值 | `StructuredChunkingStrategy`（写入侧唯一入口）；调用 `ChunkLayoutMetadata.writer(...)` | OS 写：`OpenSearchVectorStoreService.buildDocument` 透传；DB 写：`KnowledgeChunkLayoutMapper.copyToCreateRequest` 抽到 CreateRequest；re-index 写回：`KnowledgeChunkLayoutMapper.copyFromDO` 从 DO 反向回填 |
| **OpenSearch metadata** | `_source.metadata` JSON object | 9 keyword/integer/float/text 字段 | `OpenSearchVectorStoreService.buildDocument` catch-all 透传 chunk.metadata | `OpenSearchRetrieverService.toRetrievedChunk` 用 `ChunkLayoutMetadata` reader 反序列化到 RetrievedChunk |
| **DB t_knowledge_chunk** | 9 个 SQL 列 | INTEGER / TEXT / VARCHAR / DOUBLE | 持久化路径：`persistChunksAndVectorsAtomically` → `KnowledgeChunkLayoutMapper.copyToCreateRequest`（含 List → JSON 序列化）→ `KnowledgeChunkServiceImpl.batchCreate` → `chunkMapper.insert(List)`（entity-based，避免 typeHandler 陷阱）| Re-index 读路径：`KnowledgeChunkServiceImpl` 读 DO 后用 `KnowledgeChunkLayoutMapper.copyFromDO` 反向回填到 `VectorChunk.metadata` |
| **RetrievedChunk** | POJO | 6 字段（无 bbox/textLayer/confidence）| `OpenSearchRetrieverService.toRetrievedChunk` | `SourceCardBuilder` |
| **SourceChunk** | POJO + JSON 序列化给前端 | 6 字段 + `@JsonInclude(NON_NULL)` 省略 null | `SourceCardBuilder.buildCard` | 前端 Sources.tsx 用 `!= null` 守卫渲染 |

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

最小改动，全部 nullable + graceful 回退（参见 §5.15 代码片段）：

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
| `KnowledgeChunkLayoutMapperCopyFromDOTest` | `KnowledgeChunkLayoutMapper.copyFromDO`（re-index helper） | (a) DO 9 字段全填 → VectorChunk.metadata 9 key 全在；(b) DO 字段全 null → VectorChunk.metadata 完全不写（保持 BASIC byte-equivalent）；(c) DO `headingPath` 是非法 JSON → log warn 但不抛异常，对应 metadata key 不写 |
| `KnowledgeChunkLayoutMapperCopyToCreateRequestTest` | `KnowledgeChunkLayoutMapper.copyToCreateRequest`（persist helper） | (a) VectorChunk metadata 完整 layout → CreateRequest 9 字段全填且 `headingPath/sourceBlockIds` 是 JSON String 形态；(b) metadata 空 → CreateRequest 9 字段保持 null（BASIC 等价）；(c) metadata.HEADING_PATH 是 List<String> → 序列化成 JSON String（验证 list → JSON 唯一入口契约） |
| `StructuredChunkingOptionsResolverTest` | `StructuredChunkingOptionsResolver` | TextBoundaryOptions / FixedSizeOptions（验证 30% 弹性 + 40% 下限）/ null → 默认值（1400, 1800, 600） |
| `StructuredChunkingStrategyChunkAlgorithmTest` | `StructuredChunkingStrategy.chunk()` | (a) title 边界正确切分；(b) 表格原子不切；(c) 跨页 chunk 的 pageStart/pageEnd 正确；(d) headingPath 在每个 chunk 上对齐；(e) v0.5.1 边界（textLayerType / confidence 全 null 透传） |

### 10.2 Integration-ish

测试命名遵循仓库 convention `*IntegrationTest`（参考 [`EnhancedRoutingIntegrationTest`](../../../bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/EnhancedRoutingIntegrationTest.java)）。

| 测试 | 范围 | 覆盖 |
|---|---|---|
| `BasicChunkPathByteEquivalentIntegrationTest` | 全栈 BASIC + CHUNK | 上传 PDF（parseMode=basic）；断言 chunk 数 / content / chunkIndex 与 PR 5 末态一致；DB 9 layout 列 SELECT 全 NULL；OS `_source.metadata` 无 9 个 layout key |
| `BasicPipelineNoPhantomBlockTypeIntegrationTest` | BASIC + 显式 pipelineId | 上传 PDF（parseMode=basic + 用户传 pipelineId 强走 PIPELINE）；LegacyAdapter 接管；同 BasicChunk 断言；**特别断言** `block_type` 字段在 OS 与 DB 都不存在或为 NULL（修正 plan 隐性偏离） |
| `EnhancedDoclingFailureFallbackIntegrationTest` | ENHANCED + Docling 模拟失败 | mock DoclingClient 抛 IOException；FallbackParserDecorator 捕获；ParseResult.metadata 含 `parse_engine_actual=Tika, parse_fallback_reason=primary_failed`；StructuredChunkingStrategy.supports()=false；LegacyAdapter(structure_aware) 接管；chunks 无 layout；ingestion 完成 status=success |
| `EnhancedHappyPathLayoutEndToEndIntegrationTest` | ENHANCED 成功 | 上传 PDF（parseMode=enhanced）；Docling 成功；**最小集断言**：DB 列 `pageNumber / pageStart / pageEnd / blockType` 4 列非 NULL；**条件性断言**：`headingPath` 与 `sourceBlockIds` 仅在 chunker 实际产出时（即 LayoutBlock 列表中存在 heading / blockId）才断言非空数组，没有则允许 NULL（应对"PDF 没有明确章节标题"或"Docling 块未填充 blockId"的真实场景）；OS 检索断言用 `term: { "metadata.block_type": "PARAGRAPH" }`（**注意是 `metadata.block_type` 嵌套路径**，不是根级 `block_type`）；SourceChunk 序列化包含 6 字段（null 字段经 `@JsonInclude(NON_NULL)` 已被省略，断言"key 存在性"而非"value 非 null"）|
| `RetrieverLayoutFieldRoundTripIntegrationTest` | 检索读路径 | 写入 chunk + layout metadata → OS → 检索 → 断言 RetrievedChunk 6 字段值与写入完全一致（特别 List<String> 不被错误序列化为 String） |
| `EnableDisableLayoutRoundTripIntegrationTest` | **re-index 路径契约（review P1 #1）** | ENHANCED 上传 → 验证 OS chunk 含 layout → `KnowledgeChunkController` 触发**禁用全部 chunks**（OS chunks 被 `deleteChunksByIds` 清掉）→ 触发**启用**（KnowledgeChunkServiceImpl 从 DB 读 KnowledgeChunkDO + `KnowledgeChunkLayoutMapper.copyFromDO` 重建 VectorChunk + `indexDocumentChunks` 写回 OS）→ 断言 OS chunk 的 `metadata.page_number / metadata.heading_path / metadata.block_type` 等 9 字段与启用前**完全一致**。如果 `copyFromDO` 漏了任一字段，此测试失败 |
| `ChunkServiceReindexCallSitesMockTest` | **5 处 VectorChunk.builder 实际调用契约（review P1 #4）** | 用 Mockito mock `VectorStoreService` + `ChunkEmbeddingService`；分别触发 (a) `batchEnableChunks` 启用分支、(b) `update` 编辑 content、(c) `addChunk` / 单 chunk 启用、(d) `KnowledgeDocumentServiceImpl` 文档级 enable；每条路径都用 `ArgumentCaptor<List<VectorChunk>>` 捕获传给 `vectorStoreService.indexDocumentChunks` 的 chunks 参数；断言每个 chunk 的 `metadata` 包含**预期的 layout key**（来自 mock DO 的 9 列）。**这是 review P1 #4 核心契约**：仅靠 helper 单测不够，必须证明"5 处 call site 真的调了 copyFromDO"。建议从 `KnowledgeChunkServiceImpl` 抽 `private VectorChunk buildVectorChunkFromDO(KnowledgeChunkDO)` helper 给 5 处共用，使本测试能覆盖该 helper 即覆盖 5 处 |
| `ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest` | **manual edit 契约（review P2 #3）** | ENHANCED 上传 → 验证 chunk DB 9 layout 列含值 → 触发 `KnowledgeChunkServiceImpl.update` 改写 chunk content → 断言：(a) DB **5 个 location 列**（pageNumber/pageStart/pageEnd/headingPath/blockType）值**未变**；(b) DB **4 个 extraction 列**（sourceBlockIds/bboxRefs/textLayerType/layoutConfidence）变 NULL；(c) OS metadata 5 在 4 不在；(d) chunk content + contentHash + embedding 已更新 |

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
| 5 | `persistChunksAndVectorsAtomically(... pages, structuredChunks)` | `persistChunksAndVectorsAtomically(... chunks)`（**签名完全不变**；layout 通过扩展 `KnowledgeChunkCreateRequest` + `KnowledgeChunkLayoutMapper.copyToCreateRequest` 流转）| Q4 page 表 Non-Goal + Q6 layout 在 chunks.metadata + review P2 #4 锁定路径 |
| 6 | 持久化 page 行到 `t_knowledge_document_page` | 不持久化 | Q4 Non-Goal 1 |
| 7 | SourceCard 加 `pageNumber / headingPath` | **SourceChunk** 加 6 字段（chunk 级 evidence） | Δ7 修正：page/heading 是 chunk 级，不是 doc 级 |
| 8 | OS mapping 升级未明示 | §5.12 forward-only 9 字段升级 | Δ2 |
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
