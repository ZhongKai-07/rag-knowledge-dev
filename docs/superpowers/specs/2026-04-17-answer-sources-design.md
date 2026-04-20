# 回答来源（Answer Sources / Grounding）功能设计

> 2026-04-17 · v1 设计稿

## 背景与目标

当前 RAG 问答在 `/chat` 页面生成答案时，用户无法看到答案依据哪些文档，缺乏"可核查性"。这在内部知识库场景（HTFH 部署）尤为关键——答案是否可信需要用户能快速跳回原文核对。

本功能在答案下方展示一组**文档级来源卡片**，并在答案正文里插入与卡片编号对应的引用角标（类似 Perplexity / ChatGPT Search 的 `[1][2]`，但使用 `[^n]` 脚注式 marker），点击角标可滚动定位并高亮对应卡片。

**v1 面向的主要受益者**：终端用户（信任 & 核查）。
**同时预埋的扩展方向**：chunk 级精确引用（B）、Drawer 原文预览（面向用户的 v2）、调试视图（C，面向管理员）。

---

## 总体目标

1. 用户看到答案时能清楚知道答案参考了哪些文档
2. 答案正文和卡片通过 `[^n]` 编号形成双向关联
3. 刷新页面、重新打开历史会话时，来源卡片与角标持久化可见
4. v1 不做行内精确引用（chunk 级高亮）、不做 Drawer 原文预览，但数据层与交互接口为其预留

## Non-Goals（明确排除）

- ❌ 行内精确引用（角标点击滚到原文具体段落）
- ❌ Drawer 原文预览组件（`onCardClick` 签名预埋，内部仅就地展开）
- ❌ 严格派引用校验 / 失败重试（v1 只做埋点不拦截；v2 基于数据决策）
- ❌ MCP / 工具调用来源展示（未来独立事件，不复用 `sources`）
- ❌ 引用质量仪表盘 UI（数据先写进 `extra_data`，需要时再补前端）
- ❌ 跨会话引用搜索 / 反向引用统计（"这份文档被哪些回答引用了"）
- ❌ 同一文档跨多轮对话合并引用（每轮独立统计）
- ❌ 用户自定义展示偏好（折叠阈值、卡片数上限用 `application.yaml` 硬编码）
- ❌ 向量库 metadata 补 `docName`（避免改名漂移，docName 始终从 DB 取当时快照）

---

## 架构与改动总览

```
用户提问 → RAGChatServiceImpl
 ├─ 检索 (RetrievalEngine)
 │   └─ 在 RetrievedChunk 上扩展 docId/chunkIndex 两个可空字段（沿用 kbId/securityLevel 的可选字段模式，零行为变化）
 ├─ 来源聚合 (SourceCardBuilder)
 │   └─ 通过 KnowledgeDocumentService 批量查 docName/kbId
 ├─ Prompt 构建 (RAGPromptService.buildContextWithCitations)
 │   └─ context 注入 [^1]《文档 A》...，system 规则要求引用
 └─ SSE 推送
     ├─ meta
     ├─ sources (新事件)
     ├─ message ... (流式正文含 [^n])
     ├─ finish → 落库: t_message.sources_json
     ├─ suggestions
     └─ done

前端 MessageItem
 ├─ onSources 事件 → message.sources 就位
 ├─ MarkdownRenderer 挂 remarkCitations AST 插件 → 渲染 <CitationBadge/>
 ├─ <Sources /> 文档级卡片列表（默认折叠，点击就地展开）
 └─ 角标点击 → 滚动 + 高亮对应卡片（按 index 字段匹配，非数组位置）
```

### 依赖方向（严格单向）

```
framework     ← 零改动、无新依赖
knowledge 域   ← +1 service 方法 + 1 DTO（对外开放）
rag 域        ← 新增 SourceCardBuilder 依赖 KnowledgeDocumentService
编排层         ← RAGChatServiceImpl 注入 SourceCardBuilder
```

不反向依赖，不跨域访问 DAO，不污染 framework。

---

## 数据模型

### 向量库 metadata 字段来源

| 字段 | 来源 | 备注 |
|------|------|------|
| `chunkId` | 向量库主键 `_source.id` | 已有 |
| `docId` | `_source.metadata.doc_id` | **索引时已写入**，检索层之前丢弃，现在回填 |
| `chunkIndex` | `_source.metadata.chunk_index` | 同上 |
| `score` | 向量库相关性分数 | 已有 |
| `text` | `_source.content` | 已有 |
| `docName` | DB `t_knowledge_document.name` | **批量 JOIN** 取当时快照 |
| `kbId` | DB `t_knowledge_document.kb_id` | 同上 |
| `kbName` | DB `t_knowledge_base.name` | 可选展示 |

docName/kbName 不存向量 metadata，避免文档改名导致的数据漂移。

### 新增 / 修改的 Java 类型

```java
// framework/convention/RetrievedChunk.java（扩展，新增 2 字段）
// 沿用既有"可空可选字段"模式（类似 kbId / securityLevel，非 OpenSearch 后端不回填）
private String docId;                // 从 vector metadata.doc_id 回填
private Integer chunkIndex;          // 从 vector metadata.chunk_index 回填

// bootstrap/knowledge/dto/DocumentMetaSnapshot.java（新）
public record DocumentMetaSnapshot(String docId, String docName, String kbId) {}

// bootstrap/knowledge/service/KnowledgeDocumentService.java（扩展）
List<DocumentMetaSnapshot> findMetaByIds(Collection<String> docIds);

// bootstrap/rag/dto/SourceCard.java（新）
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SourceCard {
    private int index;                   // 稳定引用编号，1..N，对应正文 [^n]
    private String docId;
    private String docName;
    private String kbId;
    private String kbName;
    private float topScore;
    private List<SourceChunk> chunks;    // 按 chunkIndex 升序（阅读顺序）
}

// bootstrap/rag/dto/SourceChunk.java（新）
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SourceChunk {
    private String chunkId;
    private int chunkIndex;
    private String preview;              // 后端截断到 preview-max-chars
    private float score;
}

// bootstrap/rag/dto/SourcesPayload.java（新，SSE 载荷）
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SourcesPayload {
    private String conversationId;
    private String messageId;            // 可能为空串（流式中 DB 尚未写入）
    private List<SourceCard> cards;
}

// bootstrap/rag/enums/SSEEventType.java（枚举扩展）
SOURCES("sources")

// bootstrap/rag/dao/entity/ConversationMessageDO.java（加字段）
private String sourcesJson;              // SourceCard[] 的 JSON 序列化，NULL 表示无引用

// bootstrap/rag/controller/vo/ConversationMessageVO.java（加字段）
private List<SourceCard> sources;        // 从 sourcesJson 反序列化
// 顺带补齐历史坑：thinkingContent / thinkingDuration 已有字段在前端未映射
```

所有涉及 Jackson 反序列化的新类型都显式标注 `@NoArgsConstructor` + `@AllArgsConstructor`，避免 Lombok `@Data @Builder` 组合下 Jackson 实例化失败（项目历史坑）。

### 前端类型

```typescript
// types/index.ts
interface Message {
  // ...existing
  sources?: SourceCard[];
}
interface SourceCard { index: number; docId: string; docName: string; kbId: string;
                       kbName: string; topScore: number; chunks: SourceChunk[] }
interface SourceChunk { chunkId: string; chunkIndex: number; preview: string; score: number }
interface SourcesPayload { conversationId: string; messageId: string; cards: SourceCard[] }
```

---

## SSE 契约

### 事件顺序（后端保证单流内有序）

```
meta → sources → message+ → finish → suggestions → done
```

同一 `StreamChatEventHandler` 链路单线程顺序发送；`sources` 保证在首个 `message` 事件之前、`finish` 事件之前到达。

前端 `useStreamResponse.readSseStream` 单线程 while-loop 消费，不依赖外部顺序假设。

### `sources` 事件载荷

```
event: sources
data: {
  "conversationId": "...",
  "messageId": "",                // 流式中可能为空串
  "cards": [
    {
      "index": 1,
      "docId": "doc_abc",
      "docName": "员工手册 v3.2.pdf",
      "kbId": "kb_xyz",
      "kbName": "人事制度库",
      "topScore": 0.892,
      "chunks": [
        { "chunkId": "c_001", "chunkIndex": 12, "preview": "…", "score": 0.892 },
        { "chunkId": "c_002", "chunkIndex": 13, "preview": "…", "score": 0.815 }
      ]
    },
    { "index": 2, ... }
  ]
}
```

**语义约定**
- `index` 从 1 开始，**等于** LLM 看到的引用编号、卡片显示编号
- `cards` 按 `topScore` 降序排列（LLM 看到的也是这个顺序，最相关文档在前）
- `chunks` 在卡片内按 `chunkIndex` 升序（阅读顺序，不按 score）
- `preview` 后端截断到配置长度，避免 SSE 帧过大
- `cards: []`（空列表）场景**不推送** `sources` 事件，前端 `message.sources` 保持 undefined

### 缺席场景

以下场景**不推送** `sources` 事件、不落库 `sources_json`，前端 `<Sources />` 不渲染：

- 空检索（未召回任何 chunk）
- System-Only 意图（闲聊、跳过检索）
- MCP-Only 意图（纯工具调用）
- 意图歧义引导（短路返回 clarification）
- feature flag 关闭

---

## Prompt 注入与引用规则

### Context 格式

当前 `RAGPromptService.buildContext()` 返回片段简单拼接。新方法 `buildContextWithCitations(cards, ctx)` 按文档分组 + 注入编号：

```
【参考文档】
[^1]《员工手册 v3.2.pdf》（来自：人事制度库）
—— 片段 1：<chunk 12 正文>
—— 片段 2：<chunk 13 正文>

[^2]《年度考勤办法.docx》（来自：人事制度库）
—— 片段 1：<chunk 3 正文>

[^3]《……》
...
```

### System Prompt 追加规则（文案草稿）

```
【引用规则】
回答中凡是基于【参考文档】的陈述，必须在该陈述末尾附上对应文档的编号，
格式为半角方括号加脱字符加数字 [^n]，多个来源用 [^1][^2] 连写。
例：
  员工入职后第 6 个月可申请转正评估[^2]。
  培训期满后考评合格者予以正式录用[^1][^3]。
若陈述属于常识或不依赖参考文档，则不要添加 [^n]。
禁止编造参考文档中不存在的编号。
```

### 宽容策略 + 埋点（v1 不拦截）

`StreamChatEventHandler.onComplete()` 对最终回答做正则扫描 `\[\^(\d+)\]`：

```java
Map<String, Object> citationStats = Map.of(
    "citationTotal", total,       // 出现的 [^n] 次数
    "citationValid", valid,       // n ∈ 1..cards.size() 的次数
    "citationInvalid", invalid,   // n 越界次数
    "citationCoverage", coverage  // 出现 [^n] 的句子数 / 总句子数（按 。！？ 粗切）
);
ragTraceRecordService.mergeRunExtraData(traceId, citationStats);
```

**强制要求**：使用 `mergeRunExtraData(traceId, Map)`（JSON 树合并），**禁止** `updateRunExtraData(traceId, String)`（覆盖写），避免和 `totalTokens / suggestedQuestions` 互相覆盖。

**已知误差**：后端正则是粗略统计，不做 AST 解析。若答案含代码块 `array[^1]`，可能计入但不影响前端渲染（前端走 AST 跳过代码）。作为埋点数据可接受轻微偏差。

---

## 持久化 Schema

### 新增列

```sql
-- t_message (MyBatis Plus 实体 ConversationMessageDO 对应的物理表)
ALTER TABLE t_message ADD COLUMN sources_json TEXT;
COMMENT ON COLUMN t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
```

> 注意：Java 侧实体类名为 `ConversationMessageDO`，但 `@TableName("t_message")` 映射到物理表 `t_message`。本 schema / migration 一律以物理表 `t_message` 为准。

### 迁移脚本

`resources/database/upgrade_v1.4_to_v1.5.sql`（遵循项目命名惯例；v1.3→v1.4 是上一次升级）。

**两份 schema 都要改**（CLAUDE.md 约束）：

1. `schema_pg.sql`：`CREATE TABLE t_message` 块加列
2. `full_schema_pg.sql`：`CREATE TABLE public.t_message` 块加列 + **单独 COMMENT 块**（项目规范：COMMENT 不能内联在 CREATE TABLE 内）

### 字段约定

- 存的是**答案生成时的快照**，不是外键。文档后续被删除 / 改名都不影响历史引用展示
- **Jackson** 序列化（不用 Gson）——避免 Gson 把 int 写成 `"N.0"` 的项目历史坑
- 读取时 Jackson 反序列化，失败降级为 `sources=null`，不影响消息展示
- 数据量估算：5 文档 × 3 chunk × 200 字 preview ≈ 4KB JSON / 消息，TEXT 类型无压力

### 落库时机

`StreamChatEventHandler.onComplete()` 内完成，与 token 用量、suggestions 统一在 complete 阶段处理。异常中断（onError / onCancel）**不落库**，已推送到前端的卡片保留当前会话可见，刷新后消失（符合预期）。

---

## 前端渲染

### 组件层级

```
MessageItem.tsx
├── ThinkingIndicator                          (已有)
├── 折叠的深度思考块                           (已有)
├── MarkdownRenderer                           (已有，挂 remarkCitations)
│   └── <CitationBadge n={1} onClick={..} />   ← 自定义 cite 节点渲染
├── <Sources cards={..} highlighted={..} />    (新)
│   └── <SourceCardView index={1} ... />
│       └── 展开后: <ChunkPreviewList />
├── <FeedbackButtons />                        (已有)
└── 推荐问题区                                 (已有)
```

渲染顺序：Sources 放在答案之后、反馈按钮之前（用户先读答案 → 想核查时就近看卡片 → 满意后再点赞/踩）。

### `[^n]` 走 remark AST，不做字符串 preprocess

**硬约束**：禁止 `content.replace(/\[\^(\d+)\]/g, ...)` 之类的 preprocess——会误伤代码块、行内代码、链接文本里的字面量。

**方案**：自定义 `utils/remarkCitations.ts` remark 插件，在 mdast 阶段遍历 text node，跳过 `inlineCode / code / link / image / linkReference` 父节点，仅在普通段落文本里把 `[^n]` 转为自定义节点：

```typescript
// 伪代码
visit(tree, "text", (node, index, parent) => {
  const skipParents = ["inlineCode", "code", "link", "image", "linkReference"];
  if (skipParents.includes(parent.type)) return SKIP;
  // 切分 text → [text, citationRef, text, citationRef, ...]
  // citationRef.data.hName = "cite", hProperties = { dataN: n }
});
```

`MarkdownRenderer` 通过 `components.cite` 映射渲染成 `<CitationBadge />`。

**安全性**：不引入 `rehype-raw` / `rehype-sanitize`。走 remark 自定义节点 + React 组件映射路径，LLM 输出的任何原始 HTML 依然被 react-markdown 默认转义为文本，**不打开可执行 HTML 的输入面**。

### `<CitationBadge />`

- 视觉：`<sup>` 小上标，蓝色数字，圆角背景（蓝色系 Tailwind token，和项目"深度思考""推荐问题"区域的视觉语言一致）
- Tooltip：悬停显示 `indexMap.get(n)?.docName`
- onClick：`handleCitationClick(n)`，滚动到 Sources 区 + 高亮对应卡片

### `<Sources />` 卡片区

**字段契约**：`index` 是后端分配的稳定引用编号，UI **禁止** `cards[n-1]` 这种"数组位置 = 编号"假设。

```typescript
const indexMap = useMemo(
  () => new Map(cards.map(c => [c.index, c])),
  [cards]
);
const handleCitationClick = (n: number) => {
  const card = indexMap.get(n);
  if (!card) return;        // 越界 [^n] 忽略
  sourcesRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  setHighlightedIndex(card.index);
  setExpandedIndexes(prev => new Set(prev).add(card.index));  // 自动展开被引用卡片
  // 定时器管理见下
};
```

**折叠规则**：`max-cards` 配置限制显示条数，但**被引用过的卡片必须可见**，不可折叠。

```typescript
const citedIndexes = extractCitedIndexes(content);  // 扫描正文 [^n]
const visible = [
  ...cards.filter(c => citedIndexes.has(c.index)),
  ...cards.filter(c => !citedIndexes.has(c.index)).slice(0, maxCards - citedCount),
];
```

**交互行为**：
- 默认折叠：每张卡显示 `[^n] 文档名 + KB名 + topScore`
- 点击卡片：就地展开该卡的 chunk preview 列表（`preview` 字段已在 SSE 中携带）
- `highlightedIndex` 非 null 时给对应卡加 1.5s 蓝色外环，定时器用 ref 管理，重复点击取消前次、组件卸载清理

```typescript
const timerRef = useRef<number | null>(null);
const clearHighlight = () => {
  if (timerRef.current) window.clearTimeout(timerRef.current);
  setHighlightedIndex(null);
  timerRef.current = null;
};
useEffect(() => () => clearHighlight(), []);  // 卸载清理
```

### SSE 处理 + 临时 id 竞态防护

**背景**：`chatStore` 在 `onFinish` 里把临时 `assistant-${ts}` 替换成 DB `messageId`（保留其他字段）。如果 `sources` 事件晚于 `finish` 到达且用临时 id 查找会打空。

**防护**：沿用现有 `onMeta / onMessage` 的 guard 模式：

```typescript
case "sources": {
  if (get().streamingMessageId !== assistantId) return;  // 请求已取消/替换
  const payload = JSON.parse(event.data) as SourcesPayload;
  set(state => ({
    messages: state.messages.map(m =>
      m.id === state.streamingMessageId ? { ...m, sources: payload.cards } : m
    ),
  }));
  break;
}
```

因为后端保证 `sources` 在 `finish` 之前发出 + 前端单线程 SSE 消费，`streamingMessageId` 在 `sources` 到达时仍是临时 id，guard 必然成立。`onFinish` 替换 id 时用展开运算 `{ ...m, id: newId }` 保留 `sources` 字段。

### 历史消息映射（显式补齐，非零改动）

后端 `ConversationMessageVO` 原本已有 `thinkingContent / thinkingDuration` 字段，但前端 `sessionService.ts` 的 `ConversationMessageVO` type **从未声明**这两个字段，`selectSession` 映射也未带入。加入 `sources` 时一并修复：

```typescript
// services/sessionService.ts
export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  vote: number | null;
  createTime?: string;
  thinkingContent?: string;      // 补
  thinkingDuration?: number;     // 补
  sources?: SourceCard[];        // 新
}

// stores/chatStore.ts selectSession 映射
const mapped: Message[] = data.map(item => ({
  id: String(item.id),
  role: item.role === "assistant" ? "assistant" : "user",
  content: item.content,
  createdAt: item.createTime,
  feedback: mapVoteToFeedback(item.vote),
  status: "done",
  thinking: item.thinkingContent,               // 补
  thinkingDuration: item.thinkingDuration,      // 补
  sources: item.sources,                        // 新
}));
```

---

## Feature Flag

```yaml
rag:
  sources:
    enabled: true              # 总开关；false 时不推 sources 事件、不改 prompt、不落库
    preview-max-chars: 200     # chunk preview 截断长度
    max-cards: 8               # 卡片数上限；被引用卡片强制可见不在此限
```

关闭时行为与旧版完全一致，回退路径干净。

---

## 实现 Gotchas（写代码时必查）

### 1. 异步线程上下文丢失

`StreamChatEventHandler.onComplete()` 运行在 `modelStreamExecutor` 异步线程池。所需的 `cards` 列表**必须**通过 `StreamChatHandlerParams` 作为不可变构造参数传入，**禁止**在 `onComplete` 时从 ThreadLocal / RagTraceContext 读（CLAUDE.md 明确记录该上下文已被 `ChatRateLimitAspect.finally` 提前清空）。

```java
// StreamChatHandlerParams 加字段
private final List<SourceCard> cards;

// StreamChatEventHandler 构造器
public StreamChatEventHandler(StreamChatHandlerParams params, ...) {
    this.cards = params.getCards();  // final，同 traceId 处理模式
}
```

### 2. Jackson 反序列化要求

`SourceCard / SourceChunk / SourcesPayload` **必须**显式标注 `@NoArgsConstructor @AllArgsConstructor`。CLAUDE.md 记录：`@Data @Builder` 单独使用时 Lombok 生成的构造器 Jackson 无法识别，历史上 `IntentNode` 踩过这个坑（每次请求 Redis 反序列化失败回退重建）。

### 3. extra_data 写入用 merge 不用 overwrite

埋点写入 `RagTraceRecordService.mergeRunExtraData(traceId, Map)`，禁止 `updateRunExtraData(traceId, String)`。后者会覆盖 token 用量、推荐问题等并发写入字段。

### 4. 引用 marker 选用 `[^n]` 而非 `[n]`

- `[n]` 纯方括号：代码示例 `array[1]` / 年份 `[2024]` 都可能被误伤
- `[^n]` 脚注风格：字符组合在代码/文本中极罕见，误伤率接近零
- 前端走 remark AST 解析（不走 remark-gfm 的原生 footnote 渲染，需要关闭或覆盖），**不使用字符串 preprocess**
- 后端正则 `\[\^(\d+)\]` 误伤率同样低

### 5. SSE 临时 id 竞态

前端处理 `sources` 事件时**必须**用 `streamingMessageId === assistantId` guard，与 `onMeta / onMessage` 模式一致。依赖后端单流顺序约束 + 前端单线程消费，禁止假设 `messageId` 字段在流式中已有值。

### 6. 历史消息映射补齐

`sessionService.ts` 的前端 `ConversationMessageVO` type 需同步补齐 `thinkingContent / thinkingDuration / sources` 字段；`chatStore.selectSession()` 映射一并带入。这是一个独立、可单独提交的修复子任务。

### 7. rag 域依赖 knowledge 域通过 service 接口

`SourceCardBuilder` 注入 `KnowledgeDocumentService.findMetaByIds(docIds)`，**不**注入 `KnowledgeDocumentMapper`，**不**直接写 SQL 访问 `t_knowledge_document`。保持 rag → knowledge 单向依赖、走 service 边界的约定。

### 8. schema 双写

`schema_pg.sql`（干净 DDL）和 `full_schema_pg.sql`（pg_dump 格式）必须同步。后者的 `COMMENT` 为独立块、不内联在 CREATE TABLE 中。

### 9. 显式 `@RequestParam` / `@PathVariable` 名称

本功能不新增 controller 端点；`listMessages` 已有实现不改签名。仅扩展 VO 字段。

---

## 边界条件矩阵

| 场景 | sources 事件 | sources_json | 前端渲染 |
|------|-------------|--------------|----------|
| 正常 KB 命中多文档 | 推送 | 落库 | 卡片 + 角标 |
| 空检索 | 不推送 | NULL | 无卡片，"未检索到"兜底不变 |
| System-Only 意图 | 不推送 | NULL | 同上 |
| MCP-Only 意图 | 不推送 | NULL | 同上；未来单独事件 |
| 意图歧义引导 | 不推送 | NULL | 同上 |
| 所有 chunk 同一文档 | 推送 1 张卡 | 1 张卡 | 单卡正常 |
| LLM 漏加 `[^n]` | 推送 | 落库 | 卡片在、正文无角标；coverage=0 埋点 |
| LLM 产出 `[^99]` 越界 | 推送 | 落库 | 角标原样保留为文本，`invalid++` 埋点 |
| LLM 在代码块写 `[^1]` | 推送 | 落库 | AST 跳过；代码原样显示 |
| feature flag 关闭 | 不推送 | NULL | 等同旧版 |
| 历史 sources_json 解析失败 | — | 原样 | `sources=undefined` 不渲染不报错 |
| 异常中断 (onError / cancel) | 已推送的保留 | 不落库 | 刷新后消失 |
| 文档后续被删除 | — | 保留快照 | 历史引用快照可见 |

---

## 测试点

### 后端单元

- `SourceCardBuilderTest`
  - 10 chunk 跨 3 文档 → cards=3、index 按 topScore 降序、chunks 按 chunkIndex 升序、preview 截断到 200 字
  - 输入空列表 → 返回空
  - DB 某 docId 查询为空 → 该文档被过滤、不挂空壳卡片
- `CitationStatsTest`
  - 含 `[^1][^2][^99]` 的答案 → total=3, valid=2, invalid=1
  - 含多句、引用覆盖部分句子 → `coverage` 合理
- `KnowledgeDocumentServiceTest.findMetaByIds`
  - 批量传入含不存在 id → 返回可用子集、不抛异常

### 后端集成

- 端到端 `/rag/v3/chat` → SSE 事件顺序断言 `meta → sources → message+ → finish → suggestions → done`
- `sources_json` 落库后再次 `listMessages` → Jackson 反序列化还原等价 SourceCard[]
- feature flag off → SSE 无 `sources` 事件、`sources_json` NULL
- 空检索路径 → 不推 `sources`

### 前端单元

- `remarkCitations`
  - 段落 text `见 [^1]` → 输出 cite 节点
  - `\`\`\`python\narr[^1]\n\`\`\`` inlineCode / code 父节点 → 原样保留
  - link 文本 `[文档 [^1]](/x)` → 原样保留
- `<Sources />`
  - 折叠时被引用卡片强制可见
  - 数据中 index 非连续（如 [1,3,5]）时 UI 正常
- `<CitationBadge />`
  - 重复点击取消前次定时器；卸载清理
- `chatStore.onSources`
  - `streamingMessageId !== assistantId` 时丢弃
- `selectSession` 映射
  - 历史消息 `sources / thinkingContent / thinkingDuration` 正确回填

### 手动回归

- 打开已有会话（无 sources 历史消息）→ 显示正常无报错
- 流式中途取消 → 卡片保留当前会话，刷新后消失
- 深度思考模式下 sources 正常
- 切换 KB 空间 → `resetForNewSpace` 正常清理

---

## PR 拆分与回滚

按独立可 revert 粒度拆 5 个 PR，每个 PR 可单独测试、单独回滚：

| PR | 范围 | 可见影响 |
|----|------|----------|
| PR1 | `EnrichedChunk` + OpenSearch/Milvus/PG 检索层回填 metadata（新方法） + `KnowledgeDocumentService.findMetaByIds` + `DocumentMetaSnapshot`（OpenSearch only — Milvus/PG 保持既有 dev-only 不回填状态，未来启用时再补） | 无行为变化，零业务影响 |
| PR2 | `SourceCardBuilder` + `SourceCard/SourceChunk/SourcesPayload` DTO + `SSEEventType.SOURCES` + `RAGChatServiceImpl` 编排 + 前端 `<Sources />` 骨架（**flag 默认 off**） | 默认无感 |
| PR3 | Prompt 改造 + `remarkCitations` + `CitationBadge` + 埋点（仍在 flag 后） | 默认无感 |
| PR4 | `t_message.sources_json` + upgrade_v1.4_to_v1.5.sql + schema 双写 + `ConversationMessageVO.sources` + `selectSession` 映射补齐（含顺带修 `thinkingContent/thinkingDuration`） | 默认无感 |
| PR5 | feature flag 默认打开 | 功能上线 |

**三层回滚预案**：

1. **运行时软回滚**：`rag.sources.enabled: false` → 立即停推 / 停改 prompt / 停落库；已落库 `sources_json` 保留，历史消息仍可展示
2. **数据回滚**：`UPDATE t_message SET sources_json = NULL`；完全弃用时 `ALTER TABLE t_message DROP COLUMN sources_json`
3. **代码回滚**：按 PR 粒度独立 revert

---

## 未来演进预埋

**B. chunk 级精确引用**
- 数据层已保留 `chunkId / chunkIndex`，SSE 契约无需变动
- 改动点：prompt 引用规则从文档级 `[^n]` 升级为 `[^n.m]`（n=文档、m=片段），前端 `CitationBadge` 加 `m` 字段，卡片内高亮 chunk 预览

**Drawer 原文预览（v2 的 A→交互升级）**
- 新增 `GET /knowledge/docs/{docId}/preview` 接口 + `<DocumentPreviewDrawer />` 组件
- `<SourceCardView onClick>` 签名不变，仅内部实现由"就地展开"替换为"打开 Drawer"
- `SourceCard` 字段、SSE 契约、Prompt 规则均不改

**C. 调试 / 可解释性视图**
- 数据层已保留 score / chunkIndex
- 改动点：管理后台追加"Retrieval 可解释性"页面，读 `t_rag_trace_run.extra_data.citationStats` + 历史 `sources_json` 做可视化

**引用质量升级（宽容派 → 严格派）**
- 数据层已埋点 `citationCoverage / citationInvalid`
- v2 基于指标判断是否需要：①重写 prompt ②生成结束后后端后验 ③模型白名单限制（小模型关闭引用要求）
- SSE / 落库契约不变

---

## 参考

- 现有 SSE 设计：`rag/enums/SSEEventType.java`、`rag/service/handler/StreamChatEventHandler.java`、`frontend/src/hooks/useStreamResponse.ts`
- extra_data merge 机制：`rag/service/impl/RagTraceRecordServiceImpl.java:85-112`
- 向量库 metadata 索引点：`ingestion/node/IndexerNode.java:227-242`、`rag/core/vector/OpenSearchVectorStoreService.java:201-208`
- 历史升级脚本：`resources/database/upgrade_v1.3_to_v1.4.sql`
- 同类 SSE 事件实现参考：推荐问题（`rag/dto/SuggestionsPayload.java` + 前端 `onSuggestions`）
