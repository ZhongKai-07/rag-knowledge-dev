# Answer Sources PR4 设计 — 持久化 sources_json + VO 映射补齐

> 2026-04-22 · v1
>
> **本文档范围**：Answer Sources 功能 5 个 PR 中的 **PR4**。承接 PR1+PR2+PR3（全部已合入 `main`，PR3 合并见 `log/dev_log/2026-04-22-answer-sources-pr3.md`，commit `c462de7`）。PR4 在 feature flag `rag.sources.enabled=false` 下**仍静默**，零用户可见变化；PR5 才翻 true。
>
> **上游 v1 spec**：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`（§ "持久化 Schema"）
>
> **PR3 设计**：`docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md`（§ 6 "Not in PR3" 明确列出 PR4 范围）
>
> **基线分支**：`feature/answer-sources-pr4` 从 `main` 拉（PR1+PR2+PR3 均已合入，不再 stacked）

---

## § 0 与上游 v1 spec 的差异摘要（PR4 边界收紧）

| 条目 | v1 spec 原文 | 本文档收紧 | 原因 |
|---|---|---|---|
| 迁移脚本命名 | `upgrade_v1.4_to_v1.5.sql` | **`upgrade_v1.8_to_v1.9.sql`** | `v1.4_to_v1.5.sql` 已被"软删后复用 KB `collection_name`"占用（CLAUDE.md 记录）；当前最新是 v1.7→v1.8，PR4 递增 |
| 迁移幂等实现 | 未明确 | `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` + `COMMENT ON COLUMN`（两条都是幂等语句）| 对齐 `upgrade_v1.3_to_v1.4.sql` L4 的极简风；无需 `information_schema` 条件块 |
| VO 映射方法 | "`ConversationService.selectSession(...)` 里反序列化" | **`ConversationMessageServiceImpl.listMessages(...)`** | 实际代码里 VO 构建在 `ConversationMessageServiceImpl.listMessages`（L99-110），`ConversationService` 无 `selectSession`；消除口误 |
| `thinkingContent`/`thinkingDuration` 补齐 | "VO 加这两字段" | **仅改前端**（后端 `ConversationMessageVO` 与 `ConversationMessageServiceImpl.listMessages` 已映射，见 L59-64 / L105-106）| PR4 定性为"前端映射 catch-up"，后端零改动，避免 scope 漂移 |
| `updateSourcesJson` 的 MyBatis 写法 | 未明确 | **`mapper.update(entity, Wrappers.lambdaUpdate(...).eq(...))`** | 对齐 `RagTraceRecordServiceImpl.java:76-82` 仓库风格；不依赖 `@TableField(updateStrategy)` 声明；entity 仅带目标列 |
| `ObjectMapper` 注入风格 | 未明确 | **静态 `private static final ObjectMapper SOURCES_MAPPER = new ObjectMapper();`** | 对齐 `RagTraceRecordServiceImpl.EXTRA_DATA_MAPPER`；`SourceCard` 无多态，无需 Spring 管理 |
| 落库入口 | "`StreamChatEventHandler.onComplete` 里" | **`onComplete` 中 `memoryService.append` 之后、`updateTraceTokenUsage` 之前**；**`buildCompletionPayloadOnCancel`（取消路径）不落库** | v1 spec § "落库时机" 明确"异常中断（onError/onCancel）不落库" |
| Handler 侧 wiring | 未提 | **需扩 `StreamChatHandlerParams` + `StreamCallbackFactory`** 将 `ConversationMessageService` 线程进 handler；不新增 ThreadLocal | PR3 已锁"零 ThreadLocal 新增"约束，sources_json 落库必须走构造期 final 字段 |
| 持久化幂等 guard | 未明确 | **`messageId` blank → 早返回**；`cardsHolder.get()` empty → 早返回；Jackson 异常 + DB 异常 → log.warn 降级，不阻塞 `onComplete` 后续流程 | 落库失败不影响 FINISH/merge/SUGGESTIONS/DONE 主链路是 CLAUDE.md 硬约束 |
| 读路径失败策略 | "反序列化失败 → `sources=null`，不抛" | 保留；`malformed-json` 与 `null` 同语义降级 | 单个消息的历史快照损坏不拖累整个 listMessages 请求 |
| 测试类切分 | 未明确 | **2 个后端测试类**（`ConversationMessageServiceSourcesTest` 读写合并 + `StreamChatEventHandlerPersistenceTest`）+ 前端 `chatStore.test.ts` 1 扩用例 | 写读均在 `ConversationMessageServiceImpl` 同一 SUT，mock 依赖一致，合并成一个测试类更干净 |

---

## § 1 架构与数据流

### 继承自 PR3 的基线（PR4 不变）

```
RAGChatServiceImpl.chat
 ├─ 三层闸门决策 cards 推不推
 ├─ cardsHolder.trySet(cards)
 ├─ callback.emitSources(..)                      (SOURCES 事件)
 └─ streamLLMResponse → buildStructuredMessages → llmService.streamChat

StreamChatEventHandler.onComplete（既有 PR3 末态）
 ├─ messageId = memoryService.append(..)
 ├─ updateTraceTokenUsage()                       (overwrite 写)
 ├─ mergeCitationStatsIntoTrace()                 (merge 写)
 ├─ saveEvaluationRecord(messageId)
 ├─ FINISH → SUGGESTIONS → DONE
```

### PR4 的 delta（只增这些）

```
新后端 wiring（因 handler 需要新依赖）
 ├─ StreamChatHandlerParams 加字段  conversationMessageService
 ├─ StreamCallbackFactory 注入 ConversationMessageService 并传进 builder
 └─ StreamChatEventHandler 构造器缓存为 final 字段

StreamChatEventHandler.onComplete 新增一行
 └─ persistSourcesIfPresent(messageId)             (新；在 memoryService.append 之后)
     ├─ messageId 为空 → return                    (guard)
     ├─ cardsHolder.get() empty → return           (guard)
     ├─ SOURCES_MAPPER.writeValueAsString(cards)   (Jackson)
     ├─ conversationMessageService.updateSourcesJson(messageId, json)
     └─ try/catch Exception → log.warn（降级不阻塞）

ConversationMessageService（接口加方法）
 └─ void updateSourcesJson(String messageId, String json);

ConversationMessageServiceImpl
 ├─ updateSourcesJson(..)                          (新)
 │   ├─ StrUtil.isBlank(messageId) → return
 │   └─ mapper.update(
 │         ConversationMessageDO.builder().sourcesJson(json).build(),
 │         Wrappers.lambdaUpdate(..).eq(..getId, messageId))
 └─ listMessages 的 VO builder 加一行
     .sources(deserializeSources(record.getSourcesJson()))
     （失败 → null 不抛）

ConversationMessageDO
 └─ @TableField("sources_json") private String sourcesJson;   (不加 updateStrategy)

ConversationMessageVO
 └─ private List<SourceCard> sources;              (新字段)

前端
 ├─ services/sessionService.ts
 │   ├─ import type { SourceCard } from "@/types"   (新 import)
 │   └─ ConversationMessageVO 接口补:
 │      thinkingContent?: string / thinkingDuration?: number / sources?: SourceCard[]
 └─ stores/chatStore.ts selectSession 映射补三字段:
    thinking: item.thinkingContent
    thinkingDuration: item.thinkingDuration
    sources: item.sources

SQL
 ├─ resources/database/upgrade_v1.8_to_v1.9.sql                  (新)
 ├─ resources/database/schema_pg.sql                             (改，CREATE TABLE t_message 加列)
 └─ resources/database/full_schema_pg.sql                        (改，CREATE TABLE public.t_message 加列 + 独立 COMMENT 块)

CLAUDE.md 根级"Upgrade 脚本"清单加一行 upgrade_v1.8_to_v1.9.sql
```

### 依赖方向（严格单向，PR4 保持）

- rag 域内部 handler → rag 域 `ConversationMessageService` service 边界；**不跨域**
- **无 framework 改动**；**无 ThreadLocal 新增**（`conversationMessageService` 和 `messageId` 都是构造期 / 同步段拿到的值）
- **无前端新依赖**（仅 import 已有 `SourceCard` type）

### SSE 契约

**完全不变**。PR4 不动 SSE 事件种类 / 顺序 / 载荷。持久化是 server-side 副作用，前端通过 `listMessages` 读历史时才看到映射后的 `sources`。

### Feature flag

`rag.sources.enabled` 仍默认 `false`：

- off → PR3 路径仍静默（三层闸门第一层就 short-circuit），`cardsHolder` 永远为空，`persistSourcesIfPresent` 的 `cardsOpt.isEmpty()` 早返回 → 不落库 → 老 `listMessages` 响应字节级不变
- on → 端到端可用；新消息 `sources_json` 落库；刷新页面 `selectSession` 从 DB 反序列化回 `List<SourceCard>` 填 VO；前端 `<Sources />` 正确渲染

---

## § 2 后端改动清单

### 2.1 `ConversationMessageDO` 扩字段

```java
// bootstrap/rag/dao/entity/ConversationMessageDO.java
import com.baomidou.mybatisplus.annotation.TableField;

/**
 * 答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用
 */
@TableField("sources_json")
private String sourcesJson;
```

**约束**：
- 显式 `@TableField("sources_json")` 列名，避免 MyBatis Plus 下划线转换歧义（与 `thinkingContent` 字段隐式转换同理，但对新增字段更倾向显式声明）
- **不**加 `updateStrategy`：`mapper.update(entity, wrapper)` 走仓库默认（`NOT_NULL` 语义被 `RagTraceRecordServiceImpl.updateRunExtraData` 验证可用）

### 2.2 `ConversationMessageVO` 扩字段

```java
// bootstrap/rag/controller/vo/ConversationMessageVO.java
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import java.util.List;

/**
 * 答案引用来源快照，历史消息从 t_message.sources_json 反序列化；NULL 表示无引用或反序列化失败
 */
private List<SourceCard> sources;
```

**约束**：
- `@Data @NoArgsConstructor @AllArgsConstructor @Builder` 既有注解不动（Jackson 友好）
- `SourceCard` / `SourceChunk` 已由 PR2 在 `rag/dto/` 下定义，import 即可

### 2.3 `ConversationMessageService` 接口扩方法

```java
// bootstrap/rag/service/ConversationMessageService.java
/**
 * 更新指定消息的 sources_json 列。messageId blank 时 no-op。
 * 其他异常由调用方 try/catch 降级处理。
 *
 * <p><b>契约限制</b>：当前实现基于 {@code mapper.update(entity, lambdaUpdate)} +
 * MyBatis Plus 默认 {@code NOT_NULL} 字段策略。传入 {@code json=null} 不会清空列，
 * 而会被 MP 视作"跳过该字段"，UPDATE 实际为 no-op。PR4 的唯一调用方
 * {@code StreamChatEventHandler.persistSourcesIfPresent} 只在 {@code cardsHolder}
 * 非空时调用，因此 {@code json} 永远非空。若未来需要"显式清空"语义，
 * 应改为 {@code LambdaUpdateWrapper.set(field, null)} 写法并同步扩测试。
 *
 * @param messageId 消息 ID
 * @param json      SourceCard[] 的 JSON 序列化字符串；调用方保证非空
 */
void updateSourcesJson(String messageId, String json);
```

### 2.4 `ConversationMessageServiceImpl` 实现

```java
// bootstrap/rag/service/impl/ConversationMessageServiceImpl.java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;

private static final ObjectMapper SOURCES_MAPPER = new ObjectMapper();
private static final TypeReference<List<SourceCard>> SOURCES_TYPE = new TypeReference<>() {};

@Override
public void updateSourcesJson(String messageId, String json) {
    if (StrUtil.isBlank(messageId)) {
        return;
    }
    ConversationMessageDO update = ConversationMessageDO.builder()
            .sourcesJson(json)
            .build();
    conversationMessageMapper.update(update,
            Wrappers.lambdaUpdate(ConversationMessageDO.class)
                    .eq(ConversationMessageDO::getId, messageId));
}

// listMessages 内 VO builder 链加一行：
// .sources(deserializeSources(record.getSourcesJson()))

private List<SourceCard> deserializeSources(String json) {
    if (StrUtil.isBlank(json)) {
        return null;
    }
    try {
        return SOURCES_MAPPER.readValue(json, SOURCES_TYPE);
    } catch (Exception e) {
        log.warn("反序列化 sources_json 失败，降级为 null", e);
        return null;
    }
}
```

**约束**：
- `SOURCES_MAPPER` 静态 final 实例（对齐 `RagTraceRecordServiceImpl.EXTRA_DATA_MAPPER`）
- `deserializeSources(null)` / `deserializeSources(blank)` / `deserializeSources(malformed)` 统一返回 `null`，不抛
- 日志用 `log.warn`（类上需加 `@Slf4j` lombok 注解或现有已加）

### 2.5 `StreamChatHandlerParams` 扩字段

```java
// bootstrap/rag/service/handler/StreamChatHandlerParams.java
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;

/**
 * 会话消息服务，用于持久化 sources_json
 */
private final ConversationMessageService conversationMessageService;
```

位置：紧跟 `memoryService` 字段（语义同族，都是对话消息相关 service）；复用既有 `@Getter @Builder` 机制，不加 `@NonNull`（sources 落库是可选路径，PR4 注入即可）。

### 2.6 `StreamCallbackFactory` 注入并传递

```java
// bootstrap/rag/service/handler/StreamCallbackFactory.java
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;

// 字段列表加一行（@RequiredArgsConstructor 自动生成 final 构造器）
private final ConversationMessageService conversationMessageService;

// createChatEventHandler 的 builder 链加一行
.conversationMessageService(conversationMessageService)
```

### 2.7 `StreamChatEventHandler` 落库逻辑

```java
// bootstrap/rag/service/handler/StreamChatEventHandler.java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;

private static final ObjectMapper SOURCES_MAPPER = new ObjectMapper();

private final ConversationMessageService conversationMessageService;

// 构造器加一行（紧跟 memoryService 那行）
this.conversationMessageService = params.getConversationMessageService();

// onComplete 内在 memoryService.append 之后、updateTraceTokenUsage 之前插入
persistSourcesIfPresent(messageId);

private void persistSourcesIfPresent(String messageId) {
    if (StrUtil.isBlank(messageId)) {
        return;
    }
    Optional<List<SourceCard>> cardsOpt = cardsHolder.get();
    if (cardsOpt.isEmpty()) {
        return;
    }
    try {
        String json = SOURCES_MAPPER.writeValueAsString(cardsOpt.get());
        conversationMessageService.updateSourcesJson(messageId, json);
    } catch (Exception e) {
        log.warn("持久化 sources_json 失败，messageId={}", messageId, e);
    }
}
```

**关键约束**：

- **落库位置**：`memoryService.append` 之后（才有 `messageId`），`updateTraceTokenUsage` 之前（避免与埋点段交织，语义上"持久化用户数据"先于"遥测更新"）。三段互不依赖，顺序调换不影响正确性，但遵循此顺序便于阅读
- **异常降级**：Jackson 序列化失败或 DB update 失败 → 仅 `log.warn`，**不 rethrow**，不阻塞 `updateTraceTokenUsage` / `mergeCitationStatsIntoTrace` / `saveEvaluationRecord` / FINISH 事件发送 / SUGGESTIONS 分支 / DONE
- **取消 / 异常路径不落库**：`buildCompletionPayloadOnCancel`（L140-148）也调 `memoryService.append`，但 PR4 **不**在其中加 persist 调用；`onError` 不走 `onComplete`。符合 v1 spec § "落库时机"的"异常中断不落库"契约
- **零 ThreadLocal 新增**：`conversationMessageService` 是构造期 final 字段；`messageId` 是 `onComplete` 的同步段局部变量；`cardsHolder` 是 PR2 立的 set-once CAS 容器

---

## § 3 前端改动清单

### 3.1 `services/sessionService.ts`

```typescript
// frontend/src/services/sessionService.ts
import { api } from "@/services/api";
import type { SourceCard } from "@/types";   // 新 import

export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  vote: number | null;
  createTime?: string;
  thinkingContent?: string;        // 新
  thinkingDuration?: number;       // 新
  sources?: SourceCard[];          // 新
}
```

**约束**：
- `SourceCard` 已由 PR2 在 `frontend/src/types/index.ts` 定义；import 路径走 `@/types`
- 三字段均 optional（`?`）：历史消息 / 无深度思考 / 无引用的场景都合法

### 3.2 `stores/chatStore.ts` `selectSession` 映射

```typescript
// frontend/src/stores/chatStore.ts  L406-413 附近
const mapped: Message[] = data.map((item) => ({
  id: String(item.id),
  role: item.role === "assistant" ? "assistant" : "user",
  content: item.content,
  createdAt: item.createTime,
  feedback: mapVoteToFeedback(item.vote),
  status: "done",
  thinking: item.thinkingContent,           // 新
  thinkingDuration: item.thinkingDuration,  // 新
  sources: item.sources,                    // 新
}));
```

**约束**：
- `Message` 类型已有 `thinking` / `thinkingDuration` / `sources?` 字段（PR2 定义）；mapper 加 3 个属性即可，无 type 扩展
- 不动 SSE 路径（`onSources` handler 是流式写入，与 `selectSession` 的历史读路径互不干扰；guard 与映射策略都 PR2/PR3 已就绪）

### 3.3 非改动文件（显式枚举，防 scope 漂移）

- **不动** `components/chat/MessageItem.tsx`（PR3 已渲染 `<Sources />` + citation click）
- **不动** `components/chat/MarkdownRenderer.tsx`（PR3 已 gate `hasSources`）
- **不动** `components/chat/Sources.tsx` / `CitationBadge.tsx` / `utils/remarkCitations.ts`（PR3 已就绪）
- **不动** `types/index.ts`（SourceCard/Message.sources 已定义）
- **不动** `hooks/useStreamResponse.ts`（SSE 路由已就绪）

---

## § 4 数据库改动清单

### 4.1 `upgrade_v1.8_to_v1.9.sql`

```sql
-- 升级脚本：v1.8 → v1.9
-- 功能：为 t_message 增加 sources_json 列，用于持久化答案引用来源快照（Answer Sources 功能 PR4）
-- 设计文档：docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS sources_json TEXT;

COMMENT ON COLUMN t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
```

**约束**：
- `IF NOT EXISTS` 保证重复执行不报错（对齐 `upgrade_v1.3_to_v1.4.sql` L4 风格）
- `COMMENT ON COLUMN` 本身可重复执行（PostgreSQL 语义）
- 无 backfill：新列默认 NULL，历史消息 `sources = null`，前端 `<Sources />` 不渲染（符合 PR3 既有行为）

### 4.2 `schema_pg.sql`

**两处改动**（不是一处——此文件已有完整 t_message COMMENT 块，见 L575-585）：

**(A) `CREATE TABLE t_message (...)` 块内加列**（L158 附近）：

```sql
sources_json TEXT,
```

位置：紧跟 `thinking_duration` 列定义。

**(B) `-- t_message` COMMENT 块内加一行**（L575-585 附近）：

```sql
COMMENT ON COLUMN t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
```

位置：紧跟 `COMMENT ON COLUMN t_message.thinking_duration` 那行（或按该 table 既有 COMMENT 的字段顺序逻辑就位，保持可读）。

**硬约束**：若实现者只改 CREATE TABLE 不补 COMMENT 块，`sources_json` 会成为 `t_message` 里**唯一**无 COMMENT 的列——违背仓库"所有业务列都带 COMMENT"惯例，造成注释漂移。

### 4.3 `full_schema_pg.sql`

- `CREATE TABLE public.t_message` 块加列 `sources_json text`（参考同文件里其他 TEXT 列格式）
- 文件末尾（或同 table 其他 COMMENT 附近）加独立块：
  ```sql
  COMMENT ON COLUMN public.t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
  ```
- **硬约束**：`COMMENT` 必须为独立块，不可内联在 `CREATE TABLE` 内（项目历史规范，CLAUDE.md 记录）

### 4.4 CLAUDE.md 清单同步

根 `CLAUDE.md` "Upgrade scripts in `resources/database/`" 清单加一行：

```
- `upgrade_v1.8_to_v1.9.sql` — 为 `t_message` 增加 `sources_json` 列（Answer Sources PR4 持久化）
```

---

## § 5 测试清单

### 5.1 后端单测（2 个测试类）

| 测试类 | 用例 |
|---|---|
| `ConversationMessageServiceSourcesTest`（新） | **写路径**：(1) `updateSourcesJson` 正常 → `mapper.update` 调用 1 次，entity 只带 `sourcesJson`（用 `ArgumentCaptor<ConversationMessageDO>` 抓取并断言 `captured.getSourcesJson()` 为入参 JSON、其他字段为 null/default），wrapper 的 "按 messageId 更新" 核心契约分两层断言：**形状**用 `wrapper.getSqlSegment()` 验含 `id =` 谓词片段（不含 messageId 字面量，MP 生成的是占位符）；**绑定值**用 `wrapper.getParamNameValuePairs()` 取出占位符对应的实际绑定 value，断言等于 messageId。**不**用 `getTargetSql()`（此 API 非当前 MP 依赖版本的稳定断言点，且易落到完整 SQL 字符串上脆化）；(2) blank messageId → mapper 零交互。**读路径**：(3) `listMessages` + sources_json 有值 → VO.sources Jackson round-trip 等价；(4) `listMessages` + sources_json=`"not-a-json"` → VO.sources=null，不抛，log.warn 捕获（或放宽为"不 rethrow"）；(5) `listMessages` + sources_json=null → VO.sources=null |
| `StreamChatEventHandlerPersistenceTest`（新） | (1) `cardsHolder` 未 set + messageId 非空 → `conversationMessageService.updateSourcesJson` **never called**；(2) `cardsHolder` 有值 + messageId 非空 → `updateSourcesJson` 调一次、传入的 JSON 能用同一 ObjectMapper 反序列化回等价 `List<SourceCard>`（语义正确性而非字节完全相等）；(3) messageId blank + holder 有值 → **never called**；(4) `updateSourcesJson` 抛 RuntimeException → `log.warn` 捕获，`updateTraceTokenUsage` / `mergeCitationStatsIntoTrace` / `saveEvaluationRecord` **仍被依次调用**，FINISH 事件 **仍被发送**（此用例是异常不阻塞链路的核心锁） |

### 5.2 前端单测（扩 `chatStore.test.ts`）

在现有 `chatStore.test.ts` 追加：

```typescript
describe("selectSession mapping", () => {
  it("maps thinkingContent / thinkingDuration / sources from VO to Message", async () => {
    // mock listMessages 返回 [{ id, role: "assistant", content, thinkingContent: "t", thinkingDuration: 3, sources: [card], vote: null }]
    // 调 selectSession("c_test")
    // 断言 messages[0].thinking === "t" / .thinkingDuration === 3 / .sources 等价 [card]
  });
  it("tolerates missing thinkingContent / thinkingDuration / sources (undefined)", async () => {
    // mock 返回不带三字段
    // 断言 messages[0].thinking / .thinkingDuration / .sources 均 undefined，其他字段正常
  });
});
```

**约束**：
- 复用现有 `seedStreamingState` / `buildSourcesPayload` / `resetStore` helpers
- `vi.mock` `@/services/sessionService.ts` 的 `listMessages`；已有模式可直接参考 `chatStore.test.ts` 既有用例

### 5.3 手动冒烟（迁移 + 端到端）

1. **迁移幂等**：
   ```bash
   docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.8_to_v1.9.sql
   # 预期：ALTER TABLE + COMMENT 各一行 NOTICE；无 ERROR
   # 再跑一次
   docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.8_to_v1.9.sql
   # 预期：第二次 ALTER 因 IF NOT EXISTS 静默成功；COMMENT 重设无害；无 ERROR
   ```
2. **flag 临时 on**：`rag.sources.enabled=true`
3. **新问一个 KB 命中问题** → 流式完成 → DevTools Network 看 `listMessages` 响应包含 `sources` 字段 → 前端 `<Sources />` 渲染
4. **刷新页面 `selectSession`** → sources 持久化回来、UI 再次渲染 `<Sources />`（PR3 刷新后消失的问题被 PR4 修复）
5. **thinking 字段同步验证**：深度思考模式下新问一个问题 → 刷新页面 → 历史消息正确显示思考链和耗时（之前 PR2/PR3 期间因前端未映射而丢失）
6. **flag off 回归**：`rag.sources.enabled=false` → 新问一个问题 → 不落库 → `sources_json IS NULL` → listMessages 响应 `sources` 字段为 null → `<Sources />` 不渲染；与 PR3 末态等价

### 5.4 测试 setup 细节（避免踩坑）

- **`StreamChatEventHandlerPersistenceTest`**：`traceId` 是 handler 构造期从 `RagTraceContext` 读取的 final 字段。必须在 `new StreamChatEventHandler(...)` 之前 `RagTraceContext.setTraceId("test-trace-id")`；`@AfterEach` 做 `RagTraceContext.clear()`。参照 PR3 的 `StreamChatEventHandlerCitationTest` setup 模式
- **mock `ConversationMessageService`**：用 `@Mock` + `@InjectMocks` 或手工构造，注入到 `StreamChatHandlerParams.builder().conversationMessageService(mock).build()`
- **mock `conversationMessageMapper`**：`ConversationMessageServiceSourcesTest` 里 mock `ConversationMessageMapper`，不 mock 整个 MyBatis 栈；用 `ArgumentCaptor<ConversationMessageDO>` 抓实际 entity 断言其 `sourcesJson`；用 `ArgumentCaptor<Wrapper<ConversationMessageDO>>` 抓 wrapper，**断言分两层**：(a) `wrapper.getSqlSegment()` 验包含 `id =` 谓词形状（不直接含 messageId 字面量，MP 生成的是 `#{ew.paramNameValuePairs.MPGENVAL1}` 占位符）；(b) `wrapper.getParamNameValuePairs()` 取出对应 key 的 value 断言等于 messageId。**不**用 `getTargetSql()`——该 API 非当前 MP 版本稳定断言点，且易在完整 SQL 字符串上脆化测试
- **`log.warn` 捕获**：**优先放宽为"仅断言没 rethrow"**（最稳定，不依赖任何 log 框架细节）。若后续真的需要精确断言 `log.warn` 被调用，可在本测试类里新建 Logback `ListAppender` 并 attach 到目标 Logger——**注意仓库目前无此模式的现成参考**，需从头写；因此除非有明确需要，不建议仅为此引入 log appender 机制

---

## § 6 Not in PR4（明确排除）

| 项 | 归属 |
|---|---|
| `rag.sources.enabled=true` 默认打开 | **PR5** |
| `SourceCard.kbName` 字段 | SRC-5（未来 additive） |
| `updateTraceTokenUsage` overwrite 根治（改 merge 写） | SRC-9（顺序依赖已规避） |
| `onSuggestions streamingMessageId guard` 补齐 | SRC-4 |
| `StreamChatEventHandler` 内残留 ThreadLocal 读取 | SRC-3 |
| Milvus/Pg retriever 回填 docId/chunkIndex | SRC-1 P0（切非 OpenSearch 后端前必补） |
| PR3 known follow-ups N-3（MarkdownRenderer.components useMemo）/ N-4（remarkCitations.test 用 unified().use() 真 pipeline）/ N-5（`CITATION_HIGHLIGHT_MS` 常量抽取）/ N-6（`Sources.tsx` 的 `const visible = cards` 去除） | 留 PR5 或独立 follow-up |
| 新建 `/rag/v3/chat` 端到端集成测 | 后续需要锁 SSE + 持久化一致性时单独立项 |
| 任何 prompt / 前端 UI 组件改造 | PR3 已完成 |

---

## § 7 架构 Invariants 回顾（PR4 全部保持）

1. **orchestrator 决策 / handler 机械发射 / service 执行**：PR4 在 handler 新增的 `persistSourcesIfPresent` 是纯数据搬运（cards → JSON → service 调用），**无业务分支**；是否该落库的决策由"cards 是否 trySet"间接决定，仍锚定 `RAGChatServiceImpl` 的三层闸门
2. **零 ThreadLocal 新增**：`conversationMessageService` 是构造期 final 字段；`messageId` 同步段局部变量；`cardsHolder` PR2 set-once 容器。PR4 新增代码零 ThreadLocal 读写
3. **sources 判定锚点统一**：前端 `indexMap.get(n)`；后端 `CitationStatsCollector.scan` 用 `indexSet.contains(n)`；PR4 不引入新判定点，持久化只是序列化 `List<SourceCard>` 整体，不关心 index 分布
4. **前端 `[^n]` 严禁字符串 preprocess**：PR4 不动 `remarkCitations` 和 UI 组件
5. **`<Sources />` 契约**：忠实展示后端 `cards` 原序；PR4 从 DB 读出的 `sources` 保留 `SourceCardBuilder` 当时存入的顺序（JSON 数组顺序即 Java List 顺序），契约不变
6. **Rollback 契约字节级等价**：flag off 时 PR3 路径已 short-circuit，PR4 的 `persistSourcesIfPresent` 也早返回（`cardsHolder.get()` empty）→ `sources_json` 不写；历史消息无 `sources_json` 时读路径返回 `sources=null` → `<Sources />` 不渲染 → 与 PR3 末态等价
7. **落库失败不阻塞主流程**：`persistSourcesIfPresent` 的任何异常（Jackson / DB）仅 `log.warn`，不 rethrow；`onComplete` 的后续段（update token / merge citation / evaluation / FINISH / SUGGESTIONS / DONE）照常执行。`StreamChatEventHandlerPersistenceTest` 用例 4 显式锁此契约
8. **读路径降级统一 null**：`sources_json=null` / `blank` / `malformed-json` → VO.sources=null；不区分三者（调用方无差异化需求）；反序列化失败 `log.warn` 但不抛；`ConversationMessageServiceSourcesTest` 用例 4-6 锁此契约

---

## § 8 Gotchas（PR4 新增 4 条）

1. **迁移脚本版本号不是 spec 原文的 `v1.4_to_v1.5`**：该版本号已被"软删后复用 KB collection_name"占用（CLAUDE.md 记录在册）。PR4 应命名 `upgrade_v1.8_to_v1.9.sql`，CLAUDE.md 升级脚本清单同步加一行。提交前检查 `ls resources/database/upgrade_*.sql` 确认当前最新递增
2. **JSON 序列化一律 Jackson，禁用 Gson**：项目历史坑——Gson 把 int 写成 `"N.0"`。PR4 写路径用 `SOURCES_MAPPER.writeValueAsString(cards)`，读路径用 `SOURCES_MAPPER.readValue(json, SOURCES_TYPE)`。测试 round-trip 时**必须**用同一 `ObjectMapper` 实例序列化和反序列化，避免 Jackson 配置漂移
3. **`persistSourcesIfPresent` 的异常绝对不能 rethrow**：`onComplete` 后续还有 token 用量埋点、citation 统计 merge、evaluation 记录、FINISH / SUGGESTIONS / DONE 事件。任何 rethrow 都会打断链路，造成前端"卡在流式状态不结束"的用户可见故障。`log.warn` + 返回即可；测试用例 4 是此契约的核心回归锁
4. **取消 / 异常路径不落库**：`buildCompletionPayloadOnCancel`（L140-148）也 `memoryService.append` 出 messageId，但 PR4 **不**在其中加 persist 调用。v1 spec § "落库时机" 明确约定"异常中断不落库，已推送到前端的卡片保留当前会话可见，刷新后消失"。若未来产品决策翻转（希望取消时也落库），需补新 spec，不在 PR4 scope

---

## § 9 PR 拆分回顾（定位本 PR）

| PR | 范围 | 可见影响 |
|----|------|----------|
| PR1 ✅ | `RetrievedChunk.docId/chunkIndex` + OpenSearch 回填 + `findMetaByIds` + `DocumentMetaSnapshot` | 零 |
| PR2 ✅ | `SourceCardBuilder` + DTOs + `SSEEventType.SOURCES` + `RAGChatServiceImpl` 编排 + 前端 SSE 路由 | 默认无感（flag off） |
| PR3 ✅ | Prompt 改造 + `remarkCitations` + `CitationBadge` + `<Sources />` UI + 引用质量埋点 + SRC-6 断言 | 默认无感（flag off） |
| **PR4（本文档）** | `t_message.sources_json` 持久化 + `ConversationMessageVO.sources` + `listMessages` 映射 + 前端 `sessionService.ts` / `chatStore.selectSession` 补 `sources`/`thinking`/`thinkingDuration` | 默认无感（flag off） |
| PR5 | `rag.sources.enabled: true` 上线（顺带收 PR3 N-3/N-4/N-5/N-6） | 功能上线 |

---

## 附：参考路径

- 基线分支：`feature/answer-sources-pr4`（从 `main` 拉）
- 上游 v1 spec：`docs/superpowers/specs/2026-04-17-answer-sources-design.md`（§ "持久化 Schema"）
- PR3 设计：`docs/superpowers/specs/2026-04-22-answer-sources-pr3-design.md`
- PR3 交接记录：`log/dev_log/2026-04-22-answer-sources-pr3.md`
- Backlog（含 SRC-1~9 + PERF + OBS + ARCH）：`docs/dev/followup/backlog.md`
- 主要代码锚点：
  - `ConversationMessageDO.java`（扩 sourcesJson 字段）
  - `ConversationMessageVO.java`（扩 sources 字段）
  - `ConversationMessageService.java` / `ConversationMessageServiceImpl.java`（新 `updateSourcesJson` + `listMessages` mapper 扩）
  - `StreamChatHandlerParams.java`（扩 conversationMessageService 字段）
  - `StreamCallbackFactory.java`（注入并传递）
  - `StreamChatEventHandler.java:181-220`（`onComplete` 加 `persistSourcesIfPresent` 调用）
  - `RagTraceRecordServiceImpl.java:76-82`（`mapper.update(entity, lambdaUpdate)` 模板锚点）
  - `frontend/src/services/sessionService.ts:10`（VO 接口扩字段）
  - `frontend/src/stores/chatStore.ts:389-431`（`selectSession` mapper 扩三字段）
  - `frontend/src/stores/chatStore.test.ts`（扩 selectSession mapping 用例）
  - `resources/database/upgrade_v1.3_to_v1.4.sql:4`（`IF NOT EXISTS` 幂等风格锚点）
