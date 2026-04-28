# Upstream Selective Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Selectively merge high-value upstream changes (nageoffer/ragent) into our fork without disrupting RBAC, Knowledge Spaces, OpenSearch, or security_level features.

**Architecture:** Five independent changes across three modules: (1) thinking-chain persistence in framework+bootstrap, (2-3) infra-ai internal improvements (LLMService overload + ProbeStreamBridge), (4) bootstrap node dependency cleanup using new infra-ai APIs, (5) RoutingEmbeddingService consistency fix. No module boundary changes; change #4 *improves* dependency hygiene (bootstrap stops importing infra-ai internals).

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis Plus, PostgreSQL

**Upstream ref:** `upstream/main` (https://github.com/nageoffer/ragent.git), commits bd62f68..b165edc

**Preserved (must NOT be touched):**
- All RBAC code (user/, KbAccessService, RoleController, SysDeptController)
- Knowledge Spaces (SpacesController, `t_conversation.kb_id`, kbId in memory interfaces)
- OpenSearch integration (OpenSearchConfig, OpenSearchRetrieverService, etc.)
- security_level filtering (MetadataFilter, IndexerNode security_level metadata)
- EvaluationCollector / RAGAS evaluation pipeline
- TokenUsage + `onTokenUsage` in StreamCallback

---

### Task 1: Add thinking-chain fields to conversation message storage

**Why:** Upstream (b165edc) added persistence for deep-thinking model reasoning traces. Currently our `t_message` only stores `content` — the model's thinking process is lost between sessions.

**Files:**
- Modify: `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/ChatMessage.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationMessageDO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/bo/ConversationMessageBO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/ConversationMessageVO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java`
- Modify: `resources/database/schema_pg.sql`
- Modify: `resources/database/full_schema_pg.sql`
- Create: `resources/database/upgrade_v1.3_to_v1.4.sql`

**Constraint:** Do NOT remove `kbId` parameter from `append()` / `loadAndAppend()` — that's our Knowledge Spaces feature.

- [ ] **Step 1: Add thinking fields to ChatMessage**

```java
// framework/src/main/java/.../convention/ChatMessage.java
// Add two fields after `content`:

    /**
     * 深度思考内容（模型推理链），仅 assistant 消息可能携带
     */
    private String thinkingContent;

    /**
     * 深度思考耗时（毫秒）
     */
    private Long thinkingDuration;
```

Keep existing `@AllArgsConstructor` — Lombok will auto-generate a 4-arg constructor. Also keep the 2-arg constructors used by `ChatMessage.user()` / `system()` / `assistant()` factory methods working (they set thinking fields to null via the `@NoArgsConstructor` + setter path, which is already what happens via `@Data`).

- [ ] **Step 2: Add columns to database schema**

Create `resources/database/upgrade_v1.3_to_v1.4.sql`:

```sql
-- Upgrade: v1.3 → v1.4
-- Add thinking-chain fields to conversation messages

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS thinking_content TEXT;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS thinking_duration BIGINT;
COMMENT ON COLUMN t_message.thinking_content IS '深度思考内容（模型推理链）';
COMMENT ON COLUMN t_message.thinking_duration IS '深度思考耗时（毫秒）';
```

Update `resources/database/schema_pg.sql` — add the two columns to the `CREATE TABLE t_message` block, after the `content` column:

```sql
    content         TEXT        NOT NULL,
    thinking_content TEXT,
    thinking_duration BIGINT,
```

Update `resources/database/full_schema_pg.sql` with the matching columns and COMMENT blocks.

- [ ] **Step 3: Add fields to ConversationMessageDO**

```java
// bootstrap/.../rag/dao/entity/ConversationMessageDO.java
// Add after `content` field:

    /**
     * 深度思考内容
     */
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private String thinkingContent;

    /**
     * 深度思考耗时（毫秒）
     */
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private Long thinkingDuration;
```

需要在 import 区域添加：
```java
import com.baomidou.mybatisplus.annotation.FieldStrategy;
```

这样在其他不涉及深度思考的 update 路径中，null 值不会进入 SQL SET 子句，避免误覆盖。

- [ ] **Step 4: Add fields to ConversationMessageBO**

```java
// bootstrap/.../rag/service/bo/ConversationMessageBO.java
// Add after `content` field:

    /**
     * 深度思考内容
     */
    private String thinkingContent;

    /**
     * 深度思考耗时（毫秒）
     */
    private Long thinkingDuration;
```

- [ ] **Step 5: Add fields to ConversationMessageVO**

```java
// bootstrap/.../rag/controller/vo/ConversationMessageVO.java
// Add after `content` field:

    /**
     * 深度思考内容
     */
    private String thinkingContent;

    /**
     * 深度思考耗时（毫秒）
     */
    private Long thinkingDuration;
```

- [ ] **Step 6: Update JdbcConversationMemoryStore to write and read thinking fields**

In `append()` method (line ~76), add thinking fields to the BO builder:

```java
    @Override
    public String append(String conversationId, String userId, ChatMessage message, String kbId) {
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .build();
        // ... rest unchanged
```

In `toChatMessage()` method (line ~102), populate thinking fields:

```java
    private ChatMessage toChatMessage(ConversationMessageVO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage msg = new ChatMessage();
        msg.setRole(ChatMessage.Role.fromString(record.getRole()));
        msg.setContent(record.getContent());
        msg.setThinkingContent(record.getThinkingContent());
        msg.setThinkingDuration(record.getThinkingDuration());
        return msg;
    }
```

- [ ] **Step 7: Update ConversationMessageServiceImpl to include thinking fields in VO**

In `listMessages()` (line ~100), add thinking fields to the VO builder:

```java
            ConversationMessageVO vo = ConversationMessageVO.builder()
                    .id(String.valueOf(record.getId()))
                    .conversationId(record.getConversationId())
                    .role(record.getRole())
                    .content(record.getContent())
                    .thinkingContent(record.getThinkingContent())
                    .thinkingDuration(record.getThinkingDuration())
                    .vote(votesByMessageId.get(record.getId()))
                    .createTime(record.getCreateTime())
                    .build();
```

- [ ] **Step 8: Run DB migration on dev**

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.3_to_v1.4.sql
```

- [ ] **Step 9: Build and verify**

```bash
mvn clean compile -DskipTests
```

Expected: BUILD SUCCESS, no compilation errors.

- [ ] **Step 10: Commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/ChatMessage.java \
  bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationMessageDO.java \
  bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/bo/ConversationMessageBO.java \
  bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/ConversationMessageVO.java \
  bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/memory/JdbcConversationMemoryStore.java \
  bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java \
  resources/database/schema_pg.sql \
  resources/database/full_schema_pg.sql \
  resources/database/upgrade_v1.3_to_v1.4.sql
git commit -m "feat(memory): persist thinking-chain content and duration in conversation messages"
```

---

### Task 2: Add `LLMService.chat(request, modelId)` overload

**Why:** Upstream added this method so callers can specify a model ID while still getting routing/fallback from the executor. Currently `EnhancerNode`, `EnricherNode`, and `ChunkEmbeddingService` manually do model selection — this method enables their simplification in Task 4.

**Files:**
- Modify: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/LLMService.java`
- Modify: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java`

- [ ] **Step 1: Add default method to LLMService interface**

Add after the existing `chat(ChatRequest)` method (line ~86):

```java
    /**
     * 同步调用（指定模型）
     * <p>
     * modelId 为空时等同于 chat(request)，走默认路由。
     * modelId 不为空时优先使用指定模型，仍走路由层的健康检查与 fallback。
     *
     * @param request ChatRequest 完整配置的请求
     * @param modelId 指定的模型ID，为空时走默认路由
     * @return 模型返回的完整回答
     */
    default String chat(ChatRequest request, String modelId) {
        return chat(request);
    }
```

- [ ] **Step 2: Override in RoutingLLMService**

Add after the existing `chat(ChatRequest)` method (line ~89):

```java
    @Override
    public String chat(ChatRequest request, String modelId) {
        if (!org.springframework.util.StringUtils.hasText(modelId)) {
            return chat(request);
        }
        List<ModelTarget> allTargets = selector.selectChatCandidates(
                Boolean.TRUE.equals(request.getThinking()));
        List<ModelTarget> filtered = allTargets.stream()
                .filter(t -> modelId.equals(t.id()))
                .toList();
        if (filtered.isEmpty()) {
            return chat(request);
        }
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                filtered,
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }
```

Add `import org.springframework.util.StringUtils;` to imports (replace the inline qualified name above with just `StringUtils.hasText`).

- [ ] **Step 3: Build and verify**

```bash
mvn clean compile -DskipTests -pl infra-ai
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/LLMService.java \
  infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java
git commit -m "feat(infra): add LLMService.chat(request, modelId) for caller-specified model routing"
```

---

### Task 3: Replace FirstPacketAwaiter with ProbeStreamBridge

**Why:** Upstream (998bc30, ff3c452) fixed a buffer-callback ordering bug in the stream probe logic by replacing the two-class design (`FirstPacketAwaiter` + `ProbeBufferingCallback` inner class) with a single `ProbeStreamBridge` that buffers `Runnable` lambdas instead of typed event records. The lambda approach naturally supports all callback methods (including our `onTokenUsage`) without needing exhaustive event type enums.

**Files:**
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/ProbeStreamBridge.java`
- Modify: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java`
- Delete: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/FirstPacketAwaiter.java`

- [ ] **Step 1: Create ProbeStreamBridge**

Create `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/ProbeStreamBridge.java`:

```java
package com.knowledgebase.ai.ragent.infra.chat;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器
 * <p>
 * 探测阶段将所有回调事件缓存为 Runnable，避免失败模型的内容污染下游输出。
 * 首包成功后自动提交（commit），按原始顺序回放缓存并切换为实时转发。
 * <p>
 * 使用 Runnable lambda 而非类型化事件记录，天然兼容 StreamCallback 的所有方法
 * （包括 onTokenUsage 等扩展方法），无需维护事件类型枚举。
 */
final class ProbeStreamBridge implements StreamCallback {

    private final StreamCallback downstream;
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    private final Object lock = new Object();
    private final List<Runnable> buffer = new ArrayList<>();
    private volatile boolean committed;

    ProbeStreamBridge(StreamCallback downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onContent(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }

    @Override
    public void onThinking(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    @Override
    public void onTokenUsage(TokenUsage usage) {
        bufferOrDispatch(() -> downstream.onTokenUsage(usage));
    }

    @Override
    public void onComplete() {
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    @Override
    public void onError(Throwable t) {
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    /**
     * 阻塞等待首包探测结果，SUCCESS 时自动提交缓冲
     */
    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        ProbeResult result;
        try {
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        if (result.isSuccess()) {
            commit();
        }
        return result;
    }

    private void commit() {
        synchronized (lock) {
            if (committed) {
                return;
            }
            committed = true;
            buffer.forEach(Runnable::run);
        }
    }

    private void bufferOrDispatch(Runnable action) {
        boolean dispatchNow;
        synchronized (lock) {
            dispatchNow = committed;
            if (!dispatchNow) {
                buffer.add(action);
            }
        }
        if (dispatchNow) {
            action.run();
        }
    }

    @Getter
    static class ProbeResult {

        enum Type { SUCCESS, ERROR, TIMEOUT, NO_CONTENT }

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        static ProbeResult error(Throwable t) {
            return new ProbeResult(Type.ERROR, t);
        }

        static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
```

Key difference from upstream: includes `onTokenUsage()` override to preserve our TokenUsage pipeline.

- [ ] **Step 2: Rewrite RoutingLLMService.streamChat() to use ProbeStreamBridge**

Replace the entire `streamChat` method and helper methods. The full new `RoutingLLMService.java` content for `streamChat()` and related methods:

Replace lines 92-211 (from `public StreamCancellationHandle streamChat` through `notifyAllFailed`) with:

```java
    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(request.getThinking());
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            ProbeStreamBridge bridge = new ProbeStreamBridge(callback);

            StreamCancellationHandle handle;
            try {
                handle = client.streamChat(request, bridge, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            ProbeStreamBridge.ProbeResult result = awaitFirstPacket(bridge, handle, callback);

            if (result.isSuccess()) {
                healthStore.markSuccess(target.id());
                return handle;
            }

            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        throw notifyAllFailed(callback, lastError);
    }

    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    private ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                            StreamCancellationHandle handle,
                                                            StreamCallback callback) {
        try {
            return bridge.awaitFirstPacket(FIRST_PACKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    private Throwable buildLastErrorAndLog(ProbeStreamBridge.ProbeResult result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }
```

Also remove the `ProbeBufferingCallback` inner class (lines 218-343) and the `import java.util.ArrayList` that was only used by it.

- [ ] **Step 3: Delete FirstPacketAwaiter.java**

Delete `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/FirstPacketAwaiter.java`.

- [ ] **Step 4: Build and verify**

```bash
mvn clean compile -DskipTests -pl infra-ai,bootstrap
```

Expected: BUILD SUCCESS. No other file references `FirstPacketAwaiter` or `ProbeBufferingCallback`.

- [ ] **Step 5: Commit**

```bash
git add infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/ProbeStreamBridge.java \
  infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMService.java
git rm infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/FirstPacketAwaiter.java
git commit -m "refactor(infra): replace FirstPacketAwaiter with ProbeStreamBridge for stream probe"
```

---

### Task 4: Simplify bootstrap nodes to use high-level service interfaces

**Why:** `EnhancerNode`, `EnricherNode`, and `ChunkEmbeddingService` currently import infra-ai internals (`ChatClient`, `ModelSelector`, `ModelTarget`) and manually do model selection + client lookup. After Task 2 added `LLMService.chat(request, modelId)`, these can delegate to the high-level service interface. This removes ~75 lines of boilerplate and fixes a dependency hygiene issue (bootstrap should depend on infra-ai's public interfaces, not its internals).

**Depends on:** Task 2 (LLMService.chat overload)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/EnhancerNode.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/EnricherNode.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/ChunkEmbeddingService.java`

- [ ] **Step 1: Simplify EnhancerNode**

Replace the constructor + `chat()` + `resolveChatTarget()` + `pickTarget()` methods and update imports:

Remove imports:
```java
import com.knowledgebase.ai.ragent.infra.chat.ChatClient;
import com.knowledgebase.ai.ragent.infra.model.ModelSelector;
import com.knowledgebase.ai.ragent.infra.model.ModelTarget;
import java.util.function.Function;
import java.util.stream.Collectors;
```

Add import:
```java
import com.knowledgebase.ai.ragent.infra.chat.LLMService;
```

Replace the fields + constructor (lines 51-64) with:

```java
@Component
public class EnhancerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    public EnhancerNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }
```

Replace `chat()` + `resolveChatTarget()` + `pickTarget()` (lines 138-163) with:

```java
    private String chat(ChatRequest request, String modelId) {
        return llmService.chat(request, modelId);
    }
```

Also remove `import com.knowledgebase.ai.ragent.framework.exception.ClientException;` (no longer used) and `import org.springframework.util.StringUtils;` if only used in `pickTarget`.

Wait — `StringUtils` is still used in `resolveInputText` and `buildUserPrompt`. Keep it.

Remove `ClientException` import only if not used elsewhere in the file. Check: it's not used after removing `pickTarget`. Remove it.

- [ ] **Step 2: Simplify EnricherNode**

Identical pattern. Remove the same infra-ai internal imports, add `LLMService` import.

Replace the fields + constructor with:

```java
@Component
public class EnricherNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    public EnricherNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }
```

Replace `chat()` + `resolveChatTarget()` + `pickTarget()` with:

```java
    private String chat(ChatRequest request, String modelId) {
        return llmService.chat(request, modelId);
    }
```

Remove unused imports: `ChatClient`, `ModelSelector`, `ModelTarget`, `ClientException`, `Function`, `Collectors`.

- [ ] **Step 3: Simplify ChunkEmbeddingService**

Remove imports:
```java
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingClient;
import com.knowledgebase.ai.ragent.infra.model.ModelSelector;
import com.knowledgebase.ai.ragent.infra.model.ModelTarget;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
```

Add import:
```java
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingService;
```

Replace the class body (keep `@Service` annotation, keep `embed()` signature and `applyEmbeddings`):

```java
@Service
@RequiredArgsConstructor
public class ChunkEmbeddingService {

    private final EmbeddingService embeddingService;

    /**
     * 为分块列表计算嵌入向量
     *
     * @param chunks         已切分的文本块（embedding 字段将被原地填充）
     * @param embeddingModel 嵌入模型 ID，null 时使用系统默认模型
     */
    public void embed(List<VectorChunk> chunks, String embeddingModel) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (chunks.stream().allMatch(c -> c.getEmbedding() != null && c.getEmbedding().length > 0)) {
            return;
        }
        List<String> texts = chunks.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .toList();
        List<List<Float>> vectors = StringUtils.hasText(embeddingModel)
                ? embeddingService.embedBatch(texts, embeddingModel)
                : embeddingService.embedBatch(texts);
        applyEmbeddings(chunks, vectors);
    }

    private void applyEmbeddings(List<VectorChunk> chunks, List<List<Float>> vectors) {
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new ClientException("Embedding result size mismatch");
        }
        for (int i = 0; i < chunks.size(); i++) {
            List<Float> row = vectors.get(i);
            if (row == null) {
                throw new ClientException("Embedding result missing, index: " + i);
            }
            float[] vec = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                vec[j] = row.get(j);
            }
            chunks.get(i).setEmbedding(vec);
        }
    }
}
```

Keep `ClientException` import — still used in `applyEmbeddings`.

- [ ] **Step 4: Build and verify**

```bash
mvn clean compile -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Verify no remaining internal imports from bootstrap**

```bash
grep -rn "import com.knowledgebase.ai.ragent.infra.model.ModelSelector\|import com.knowledgebase.ai.ragent.infra.model.ModelTarget\|import com.knowledgebase.ai.ragent.infra.chat.ChatClient\|import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingClient" bootstrap/src/main/java/
```

Expected: no matches from EnhancerNode, EnricherNode, or ChunkEmbeddingService. Other files (e.g., `RAGChatServiceImpl`) may still import these — that's out of scope for this plan.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/EnhancerNode.java \
  bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/EnricherNode.java \
  bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/ChunkEmbeddingService.java
git commit -m "refactor(bootstrap): simplify nodes to use LLMService/EmbeddingService instead of internal model APIs"
```

---

### Task 5: Unify RoutingEmbeddingService to use executor for model-specified calls

**Why:** The `embed(text, modelId)` and `embedBatch(texts, modelId)` methods currently do manual health checks + try/catch, while the default versions delegate to `ModelRoutingExecutor.executeWithFallback`. The executor already handles health checks internally, so the manual code is redundant and inconsistent. Upstream (a01ee2c) unified these.

**Files:**
- Modify: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/RoutingEmbeddingService.java`

- [ ] **Step 1: Replace embed(text, modelId) and embedBatch(texts, modelId)**

Replace the two method bodies (lines 73-115) with:

```java
    @Override
    public List<Float> embed(String text, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.embed(text, target)
        );
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
        );
    }
```

Also update the existing non-modelId methods to use method reference for consistency:

```java
    @Override
    public List<Float> embed(String text) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> client.embed(text, target)
        );
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
        );
    }
```

The `resolveTarget` and `resolveClient` methods stay as-is. The `healthStore` field becomes unused — remove it from the field declaration and constructor:

```java
    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, EmbeddingClient> clientsByProvider;

    public RoutingEmbeddingService(
            ModelSelector selector,
            ModelRoutingExecutor executor,
            List<EmbeddingClient> clients) {
        this.selector = selector;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
    }
```

Remove imports:
```java
import com.knowledgebase.ai.ragent.framework.errorcode.BaseErrorCode;
import com.knowledgebase.ai.ragent.framework.exception.RemoteException;
import com.knowledgebase.ai.ragent.infra.model.ModelHealthStore;
```

Wait — `RemoteException` is still used in `resolveTarget` and `resolveClient`. Keep it. Only remove `BaseErrorCode` and `ModelHealthStore`.

- [ ] **Step 2: Build and verify**

```bash
mvn clean compile -DskipTests -pl infra-ai
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/RoutingEmbeddingService.java
git commit -m "refactor(infra): unify RoutingEmbeddingService to use executor for model-specified calls"
```

---

## Execution Order

```
Task 1 (thinking fields)     — independent
Task 2 (LLMService overload) — independent
Task 3 (ProbeStreamBridge)   — independent, touches same file as Task 2 (RoutingLLMService)
Task 4 (node simplification) — depends on Task 2
Task 5 (embedding cleanup)   — independent
```

Recommended sequential order: **1 → 2 → 3 → 4 → 5**

Tasks 1, 2, 5 are fully independent and could run in parallel. Task 3 should run after Task 2 since both modify `RoutingLLMService.java`. Task 4 must run after Task 2.

## What This Plan Does NOT Touch

- RBAC (user/, KbAccessService, roles, departments, permissions)
- Knowledge Spaces (SpacesController, kbId in memory/conversation)
- OpenSearch integration
- security_level / MetadataFilter
- EvaluationCollector / RAGAS
- TokenUsage / onTokenUsage in StreamCallback
- Abstract base class extraction (AbstractOpenAIStyleChatClient) — deferred to future plan
- ChatClient implementations (BaiLian/Ollama/SiliconFlow) — deferred
- ModelSelector / ModelHealthStore signature changes — not needed
- Frontend changes — none
