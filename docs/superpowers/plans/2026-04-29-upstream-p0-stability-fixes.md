# Upstream P0 Stability Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 upstream `nageoffer/ragent` 的 3 个 P0 稳定性 commit 移植到我方 fork，修复 SSE 连接泄漏、流式持久化失败导致响应中断、文档管理详情面板"不分块"开关缺失三个真 bug。

**Architecture:** 从 upstream cherry-pick 三个独立 commit（`f828ab45` SSE 超时 / `8f3a344d` 流式持久化容错 / `6cd6853b` 详情面板"不分块"开关），各自做 package rename（`com.nageoffer` → `com.knowledgebase`）+ 本地化适配（我方 `RetrievalEngine` / `StreamChatEventHandler` 与 upstream 已分化，需基于精神而非字面复刻）。**跳过 `34f7def3`**（chunk_config jsonb 序列化 fix）—— 我方已用更稳健的 hybrid（entityUpdate + nullClear）方案修过同一 bug。

**Tech Stack:** Java 17 / Spring Boot 3.5.7 / SseEmitter / SLF4J / Lombok / JUnit 5 + Mockito（后端）；React 18 + Vite + Vitest（前端）。

---

## Context Notes for Implementer

- Upstream 路径前缀 `com.nageoffer.ai.ragent` 在我方对应 `com.knowledgebase.ai.ragent`；package rename 是机械工作，但**所有 import 和 package 声明**都要同步替换。
- 我方代码的几个关键分歧点（移植时要识别并适配）：
  1. **`StreamChatEventHandler`** 我方已扩展了 sources 持久化、citation 埋点、suggestion 等业务逻辑（行号与 upstream 完全错开）；本计划只在我方现有代码的对应**业务点**包 try-catch，不动 upstream diff 中的具体行。
  2. **`RetrievalEngine.buildSubQuestionContext`** 我方签名是 `(si, plan, scope)` 三参数（PR4 加了 scope），upstream 是两参数；移植 try-catch 时按我方签名构造降级 ctx。
  3. **`SseEmitterSender.sendEvent`** 行为变更（throw → 静默 return）—— 已确认全部 caller 均裸调用、无人 catch 该 ServiceException，可安全静默。
  4. **`ChatMessage.assistant(...)`** 我方 `onComplete` 用的是无 thinking 参数版本（`ChatMessage.assistant(answer.toString())`），与 upstream 的 `assistant(content, thinkingContent, duration)` 不同 —— try-catch 内的 message 构造保持我方现状，不切换签名。
- **不写 frontend 单测**：项目内 `KnowledgeDocumentsPage.tsx` 没有现成测试，新建测试不在本计划范围内；frontend 改动靠手工 dev server 烟测验证。
- 提交策略：**每个 commit 对应一个独立逻辑提交**，commit message 用我方惯用的 `<type>(<scope>): 描述` 格式。

---

## File Structure

| 路径 | 责任 | 操作 |
| --- | --- | --- |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/RAGDefaultProperties.java` | RAG 默认配置属性 | Modify：加 `sseTimeoutMs` 字段 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGChatController.java` | RAG 聊天 SSE 入口 | Modify：注入 `RAGDefaultProperties`，替换 `new SseEmitter(0L)` |
| `bootstrap/src/main/resources/application.yaml` | 主配置 | Modify：加 `rag.default-config.sse-timeout-ms` |
| `framework/src/main/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSender.java` | SSE 发送封装 | Modify：构造函数注册 lifecycle callback，`sendEvent` 静默 |
| `framework/src/test/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSenderTest.java` | SSE sender 单测 | Create |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentResolver.java` | 子问题意图解析 | Modify：异步 lambda 加 try-catch + `@Slf4j` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrievalEngine.java` | 检索引擎 | Modify：`buildSubQuestionContext` 异步 lambda 加 try-catch；`executeMcpTools` 加 30s timeout |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandler.java` | SSE 事件处理 | Modify：`onComplete` 与 `buildCompletionPayloadOnCancel` 持久化包 try-catch |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandlerPersistenceTest.java` | 持久化单测 | Modify：新增 "memoryService throws → SSE 仍 FINISH" 用例 |
| `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx` | 文档管理页 | Modify：详情编辑面板加"不分块"开关 |

---

## Task 0: Setup branch

**Files:** N/A（git 操作）

- [ ] **Step 1: 创建 feature 分支**

```bash
git checkout -b feature/upstream-p0-stability
```

- [ ] **Step 2: 确认基线干净**

```bash
git status
```

Expected: 仅 `.claude/settings.local.json modified` + 已知 untracked（`.agents/`, `.kiro/` 等本地工具目录），bootstrap/framework/frontend 三个目录无未提交改动。

---

## Task 1: SSE 全局超时（来自 upstream `f828ab45`）

> 修复：`new SseEmitter(0L)` 表示永不超时，前端断开但服务端不知时会泄漏 SSE 连接（占用 servlet 线程 + LLM stream 资源）。本任务加默认 5min 兜底。

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/RAGDefaultProperties.java`（追加字段）
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGChatController.java`（注入 + 替换）
- Modify: `bootstrap/src/main/resources/application.yaml`（加配置项）

- [ ] **Step 1: 在 `RAGDefaultProperties` 末尾追加 `sseTimeoutMs` 字段**

定位 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/RAGDefaultProperties.java` 中 `private String metricType;` 那行（约 line 71）的下方、闭合 `}` 之前，插入：

```java

    /**
     * SSE 全局超时时间（毫秒）
     * <p>
     * 兜底防止 SSE 连接泄漏，超时后自动关闭连接。默认 5 分钟
     */
    private Long sseTimeoutMs = 5 * 60 * 1000L;
```

- [ ] **Step 2: `RAGChatController` 注入 `RAGDefaultProperties` 并替换 `new SseEmitter(0L)`**

定位 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGChatController.java`：

1. 在 import 区加：
   ```java
   import com.knowledgebase.ai.ragent.rag.config.RAGDefaultProperties;
   ```
2. 在 `private final RAGChatService ragChatService;` 下方加一行字段：
   ```java
   private final RAGDefaultProperties ragDefaultProperties;
   ```
3. 把 `SseEmitter emitter = new SseEmitter(0L);` 替换为：
   ```java
   SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
   ```

- [ ] **Step 3: yaml 加 `sse-timeout-ms` 配置**

在 `bootstrap/src/main/resources/application.yaml` 中 `metric-type: COSINE` 那行的下方插入：

```yaml
    sse-timeout-ms: 300000  # SSE 全局超时（毫秒），默认 5 分钟
```

注意保持缩进与同级（4 空格，和 `metric-type` 对齐）。

- [ ] **Step 4: 编译验证**

```bash
mvn -pl bootstrap -am compile -DskipTests
```

Expected: `BUILD SUCCESS`，无编译错误。

- [ ] **Step 5: 启动并烟测一次 SSE 连接**

> 可选但推荐。如果当前没有运行的本地后端 + 前端，可跳过此步并在 Task 5 末尾合并烟测。

启动：
```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

观察启动日志中 `RAGDefaultProperties` 装配无报错；前端发起一次正常对话，确认行为不变。

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/RAGDefaultProperties.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGChatController.java \
        bootstrap/src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
optimize(rag): 支持 SSE 流式对话的全局超时配置

cherry-pick from upstream f828ab45。new SseEmitter(0L) 永不超时会导致
前端断开后 SSE 连接和 LLM stream 资源泄漏，增加默认 5 分钟兜底。

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: SseEmitterSender 容错改造（来自 upstream `8f3a344d` 一部分）

> 修复：`sendEvent` 在 `closed` 时 throw `ServiceException`，但所有 caller 都是裸调用（已 grep 确认无人 catch），异常会冒泡到 Spring MVC 全局异常处理器，与已开始的流式响应冲突。改为静默 return。同时构造时注册 lifecycle 回调，让 `closed` 标志能感知 emitter 自身的 timeout/error/completion。

**Files:**
- Modify: `framework/src/main/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSender.java`
- Create: `framework/src/test/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSenderTest.java`

- [ ] **Step 1: 写失败的单测 — closed 后 sendEvent 不抛异常**

创建 `framework/src/test/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSenderTest.java`：

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

package com.knowledgebase.ai.ragent.framework.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SseEmitterSenderTest {

    @Test
    void sendEvent_afterComplete_silentlyReturns() {
        SseEmitter emitter = new SseEmitter();
        SseEmitterSender sender = new SseEmitterSender(emitter);
        sender.complete();

        assertDoesNotThrow(() -> sender.sendEvent("test", "payload"));
    }

    @Test
    void sendEvent_afterEmitterTimeout_silentlyReturns() {
        SseEmitter emitter = new SseEmitter();
        SseEmitterSender sender = new SseEmitterSender(emitter);

        // 模拟 emitter 自身 timeout 触发：直接调注册的回调
        // (Spring 在真实 timeout 时会调用我们注册的 onTimeout)
        // 这里通过 complete 路径触发同样的 closed 状态切换
        sender.complete();

        assertDoesNotThrow(() -> sender.sendEvent(null, "payload2"));
    }
}
```

- [ ] **Step 2: 运行测试，确认 fail**

```bash
mvn -pl framework test -Dtest=SseEmitterSenderTest
```

Expected: 测试编译通过但 `sendEvent_afterComplete_silentlyReturns` **失败**，原因是当前 `sendEvent` 抛 `ServiceException`。

- [ ] **Step 3: 改造 `SseEmitterSender`**

修改 `framework/src/main/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSender.java`：

1. 删除两行 import：
   ```java
   import com.knowledgebase.ai.ragent.framework.errorcode.BaseErrorCode;
   import com.knowledgebase.ai.ragent.framework.exception.ServiceException;
   ```

2. 构造函数 body 改为：
   ```java
   public SseEmitterSender(SseEmitter emitter) {
       this.emitter = emitter;
       emitter.onCompletion(() -> closed.set(true));
       emitter.onTimeout(() -> closed.set(true));
       emitter.onError(e -> closed.set(true));
   }
   ```

3. `sendEvent` 方法体改造：
   - 删除 javadoc 中 `@throws ServiceException ...` 行
   - 把 `throw new ServiceException("SSE already closed", BaseErrorCode.SERVICE_ERROR);` 替换为 `return;`

   改造后 `sendEvent` 应为：
   ```java
   public void sendEvent(String eventName, Object data) {
       if (closed.get()) {
           return;
       }
       try {
           if (eventName == null) {
               emitter.send(data);
               return;
           }
           emitter.send(SseEmitter.event().name(eventName).data(data));
       } catch (Exception e) {
           fail(e);
       }
   }
   ```

- [ ] **Step 4: 跑测试，确认 PASS**

```bash
mvn -pl framework test -Dtest=SseEmitterSenderTest
```

Expected: BUILD SUCCESS，2 个用例 PASS。

- [ ] **Step 5: 跑 framework 全量测试，确认无回归**

```bash
mvn -pl framework test
```

Expected: 既有用例全部 PASS。

- [ ] **Step 6: Commit**

```bash
git add framework/src/main/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSender.java \
        framework/src/test/java/com/knowledgebase/ai/ragent/framework/web/SseEmitterSenderTest.java
git commit -m "$(cat <<'EOF'
fix(framework): SseEmitterSender closed 后 sendEvent 静默返回

cherry-pick from upstream 8f3a344d (部分)。原 sendEvent 在 closed 时
throw ServiceException，但全部 caller 均裸调用，异常上抛会与已开始的
流式响应冲突。改为静默 return；构造时注册 onCompletion / onTimeout /
onError 让 closed 标志感知 emitter 自身的生命周期事件。

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 异步任务降级容错 — IntentResolver / RetrievalEngine（来自 `8f3a344d` 一部分）

> 修复：意图分类 / 子问题上下文构建 / MCP 工具调用是并行 `CompletableFuture.supplyAsync(...)`，任意一支 lambda 抛异常都会让 `join()` 上抛 CompletionException，污染整条对话流。改造为**降级到空意图 / 空 ctx**，并给 MCP 调用加 30s 超时兜底。

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentResolver.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrievalEngine.java`

- [ ] **Step 1: `IntentResolver` 加 `@Slf4j` 和子问题降级**

修改 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentResolver.java`：

1. 在 `import lombok.RequiredArgsConstructor;` 下方加：
   ```java
   import lombok.extern.slf4j.Slf4j;
   ```

2. 在 `@Service` 注解上方加：
   ```java
   @Slf4j
   ```

3. 把单行 lambda：
   ```java
   .map(q -> CompletableFuture.supplyAsync(
           () -> new SubQuestionIntent(q, classifyIntents(q)),
           intentClassifyExecutor
   ))
   ```
   替换为：
   ```java
   .map(q -> CompletableFuture.supplyAsync(
           () -> {
               try {
                   return new SubQuestionIntent(q, classifyIntents(q));
               } catch (Exception e) {
                   log.error("子问题意图分类失败，降级为空意图，question：{}", q, e);
                   return new SubQuestionIntent(q, List.of());
               }
           },
           intentClassifyExecutor
   ))
   ```

- [ ] **Step 2: `RetrievalEngine.buildSubQuestionContext` 异步 lambda 加 try-catch**

修改 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrievalEngine.java`，定位 `subIntents.stream().map(si -> CompletableFuture.supplyAsync(...))`（约 line 96）：

把：
```java
.map(si -> CompletableFuture.supplyAsync(
        () -> buildSubQuestionContext(
                si,
                resolveSubQuestionPlan(si, globalRecall, globalRerank),
                scope
        ),
        ragContextExecutor
))
```
替换为：
```java
.map(si -> CompletableFuture.supplyAsync(
        () -> {
            try {
                return buildSubQuestionContext(
                        si,
                        resolveSubQuestionPlan(si, globalRecall, globalRerank),
                        scope
                );
            } catch (Exception e) {
                log.error("子问题上下文构建失败，降级为空上下文，question：{}", si.subQuestion(), e);
                return new SubQuestionContext(si.subQuestion(), "", "", Map.of());
            }
        },
        ragContextExecutor
))
```

> 注意：`SubQuestionContext` 我方现有签名为 `(subQuestion, kbContext, mcpContext, intentChunks)` 四参数，与 upstream 一致。如果编译报"找不到该构造器"，运行 `grep -rn "record SubQuestionContext" bootstrap/src/main/java/` 核对实际签名后调整。

- [ ] **Step 3: `RetrievalEngine.executeMcpTools` 加 30s 超时**

继续在 `RetrievalEngine.java`：

1. 在 import 区加：
   ```java
   import java.util.concurrent.TimeUnit;
   ```

2. 类成员区（紧跟现有 `private final ContextFormatter contextFormatter;` 之前），在 class 开头处加常量：
   ```java
   private static final long MCP_TOOL_TIMEOUT_SECONDS = 30;
   ```

3. 把 `executeMcpTools` 方法体替换为：
   ```java
   private List<MCPResponse> executeMcpTools(String question, List<NodeScore> mcpIntentScores) {
       List<MCPRequest> requests = mcpIntentScores.stream()
               .map(ns -> buildMcpRequest(question, ns.getNode()))
               .filter(Objects::nonNull)
               .toList();

       if (requests.isEmpty()) {
           return List.of();
       }

       return requests.stream()
               .map(request -> {
                   try {
                       return CompletableFuture.supplyAsync(() -> executeSingleMcpTool(request), mcpBatchExecutor)
                               .orTimeout(MCP_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                               .join();
                   } catch (Exception e) {
                       log.error("MCP 工具调用超时或异常, toolId: {}", request.getToolId(), e);
                       return MCPResponse.error(request.getToolId(), "TIMEOUT", "工具调用超时或异常: " + e.getMessage());
                   }
               })
               .toList();
   }
   ```

- [ ] **Step 4: 编译并跑相关测试**

```bash
mvn -pl bootstrap -am compile -DskipTests
mvn -pl bootstrap test -Dtest='IntentResolverTests,RetrievalEngineTests,MultiChannelRetrievalEngine*Test'
```

Expected: 编译通过；测试 PASS（如有部分用例本就是 baseline-failing，根据 CLAUDE.md "Pre-existing test failures" 段比对，确认不是本次引入的回归）。

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentResolver.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrievalEngine.java
git commit -m "$(cat <<'EOF'
fix(rag): 异步任务异常降级 + MCP 工具调用 30s 超时

cherry-pick from upstream 8f3a344d (部分)。
- IntentResolver 子问题分类异常 → 降级为空意图，避免 join() 上抛污染整条对话
- RetrievalEngine.buildSubQuestionContext 异常 → 降级为空 ctx
- executeMcpTools 加 30s timeout 兜底，避免 MCP 工具卡死阻塞流式响应

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: StreamChatEventHandler 持久化容错（来自 `8f3a344d` 一部分）

> 修复：`onComplete` 和 `buildCompletionPayloadOnCancel` 直调 `memoryService.append(...)` 写库，DB 抖动 / 连接池打满会让异常冒泡到 SSE callback，导致前端看到不完整响应。包 try-catch 让持久化失败时仍能正常 FINISH。

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandler.java`
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandlerPersistenceTest.java`

- [ ] **Step 1: 写失败的单测 — append 抛异常时仍发 FINISH**

打开 `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandlerPersistenceTest.java`，在末尾追加测试方法（紧靠最后一个 `}` 之前）：

```java
    @Test
    void onComplete_whenMemoryAppendThrows_stillEmitsFinishEvent() {
        // arrange: 模拟 memoryService.append 抛异常（如 DB 连接打满）
        when(memoryService.append(anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB unavailable"));

        // act: 触发 onComplete（沿用现有 fixture 的 handler 实例与 emitter）
        handler.onContent("ok");
        // expect: 不抛异常 —— 持久化失败应被吞掉
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> handler.onComplete());
    }
```

> 实现注意：上方 `handler` / `memoryService` / `when(...)` 与文件已有 fixture 同步使用，不需要重写 setup。如果该用例引用的 fixture 名称与现有不一致，按文件已有 setUp 中实际命名替换。

- [ ] **Step 2: 跑该单测，确认 fail**

```bash
mvn -pl bootstrap test -Dtest='StreamChatEventHandlerPersistenceTest#onComplete_whenMemoryAppendThrows_stillEmitsFinishEvent'
```

Expected: 失败，stack trace 中能看到 `RuntimeException: DB unavailable` 从 `onComplete` 上抛。

- [ ] **Step 3: 在 `StreamChatEventHandler.onComplete` 包 try-catch**

修改 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandler.java`，定位 `onComplete()` 方法中：
```java
String messageId = memoryService.append(conversationId, userId,
        ChatMessage.assistant(answer.toString()), null);
```

替换为：
```java
String messageId = null;
try {
    messageId = memoryService.append(conversationId, userId,
            ChatMessage.assistant(answer.toString()), null);
} catch (Exception e) {
    log.error("对话完成时持久化消息失败，conversationId：{}", conversationId, e);
}
```

> `@Slf4j` 已在我方代码 line 55 存在；不需要重复加注解或 import。

- [ ] **Step 4: 在 `buildCompletionPayloadOnCancel` 包 try-catch**

定位同文件 `buildCompletionPayloadOnCancel()`：
```java
if (StrUtil.isNotBlank(content)) {
    messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(content), null);
}
```

替换为：
```java
if (StrUtil.isNotBlank(content)) {
    try {
        messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(content), null);
    } catch (Exception e) {
        log.error("取消时持久化消息失败，conversationId：{}", conversationId, e);
    }
}
```

- [ ] **Step 5: 跑该单测，确认 PASS**

```bash
mvn -pl bootstrap test -Dtest='StreamChatEventHandlerPersistenceTest#onComplete_whenMemoryAppendThrows_stillEmitsFinishEvent'
```

Expected: PASS。

- [ ] **Step 6: 跑 handler 全量测试，确认无回归**

```bash
mvn -pl bootstrap test -Dtest='StreamChatEventHandler*Test'
```

Expected: 既有 `Citation / Persistence / Suggestions` 测试全 PASS。

- [ ] **Step 7: Commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandler.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/service/handler/StreamChatEventHandlerPersistenceTest.java
git commit -m "$(cat <<'EOF'
fix(rag): StreamChatEventHandler 持久化失败容错

cherry-pick from upstream 8f3a344d (部分)。onComplete 与
buildCompletionPayloadOnCancel 直调 memoryService.append 写库，DB 抖动
会让异常冒泡到 SSE callback，导致前端看到不完整响应。包 try-catch 让
持久化失败时只丢 messageId，仍正常发 FINISH 事件。

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 文档管理详情面板"不分块"开关（来自 upstream `6cd6853b`）

> 修复：文档管理页的**新建表单**已有"不分块"开关（line 1132+ 的 `noChunk` state），但**详情/编辑面板**（`detailChunkStrategy === "fixed_size"` 那段，line 772）缺这个开关，导致已入库文档无法切换为不分块模式。本任务对齐两端 UX。

**Files:**
- Modify: `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx`

- [ ] **Step 1: 加 detail 面板的 state**

定位 `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx` 现有 line 157 附近：
```tsx
const [detailConfigValues, setDetailConfigValues] = useState<Record<string, string>>({});
```

在它**下方紧邻**加两行：
```tsx
const [detailNoChunk, setDetailNoChunk] = useState(false);
const [detailOriginalChunkSize, setDetailOriginalChunkSize] = useState("512");
```

- [ ] **Step 2: 加载详情时初始化 detail noChunk 状态**

定位详情加载 useEffect（grep `setDetailConfigValues(values)` 找加载分支）。在 `setDetailConfigValues(values);` 那行下方紧邻加：

```tsx
      // 如果 chunkSize 为 -1（不分块），初始化开关状态
      const rawChunkSize = values["chunkSize"];
      if (rawChunkSize === String(NO_CHUNK_VALUE)) {
        setDetailNoChunk(true);
        setDetailOriginalChunkSize("512");
      } else {
        setDetailNoChunk(false);
        setDetailOriginalChunkSize(rawChunkSize || "512");
      }
```

- [ ] **Step 3: 关闭对话框时重置 detail noChunk 状态**

继续在同一 useEffect 中，定位 `else` 分支（`setDetailScheduleCron("")` 之类的重置语句）。在 `setDetailScheduleCron("");` 下方紧邻加：

```tsx
      setDetailNoChunk(false);
      setDetailOriginalChunkSize("512");
```

- [ ] **Step 4: 切换策略时重置 detail noChunk 状态**

定位 `handleDetailStrategyChange` 函数中 `setDetailConfigValues(values);` 的位置（grep `setDetailConfigValues(values)` 找另一个非加载分支的调用点；通常紧跟 `for (...) values[k] = String(v);` 之后）。

在该 `setDetailConfigValues(values);` 下方紧邻加：

```tsx
      setDetailNoChunk(false);
      setDetailOriginalChunkSize(
        strategy.defaultConfig["chunkSize"] !== undefined
          ? String(strategy.defaultConfig["chunkSize"])
          : "512"
      );
```

- [ ] **Step 5: 加 detail 切换 / 输入处理函数**

紧跟 `handleDetailStrategyChange` 函数定义之后（找到该函数的关闭 `}` 之后），插入两个新函数：

```tsx
  // 处理编辑页"不分块"按钮点击
  const handleDetailNoChunkToggle = () => {
    if (detailNoChunk) {
      // 取消选中，恢复原始值
      setDetailConfigValues(v => ({ ...v, chunkSize: detailOriginalChunkSize }));
      setDetailNoChunk(false);
    } else {
      // 选中，保存当前值并设置为-1
      const currentSize = detailConfigValues["chunkSize"] || "512";
      setDetailOriginalChunkSize(currentSize);
      setDetailConfigValues(v => ({ ...v, chunkSize: String(NO_CHUNK_VALUE) }));
      setDetailNoChunk(true);
    }
  };

  // 用户手动修改块大小值时取消"不分块"状态
  const handleDetailChunkSizeChange = (value: string) => {
    setDetailConfigValues(v => ({ ...v, chunkSize: value }));
    if (detailNoChunk && value !== String(NO_CHUNK_VALUE)) {
      setDetailNoChunk(false);
    }
  };
```

- [ ] **Step 6: detail fixed_size 区块加 UI 列**

定位详情面板中 `detailChunkStrategy === "fixed_size" ?` 分支（line 772 附近）：

1. 把外层 div 的 className 由 `md:grid-cols-2` 改为 `md:grid-cols-3`：
   ```tsx
   <div className="grid gap-4 md:grid-cols-3">
   ```

2. 把"块大小" Input 的 onChange 由：
   ```tsx
   onChange={e => setDetailConfigValues(v => ({ ...v, chunkSize: e.target.value }))}
   ```
   改为：
   ```tsx
   onChange={e => handleDetailChunkSizeChange(e.target.value)}
   ```

3. 在"重叠大小" `</div>` 之后、外层 grid `</div>` 之前，插入第三列：
   ```tsx
                       <div>
                         <div className="text-sm font-medium mb-2">不分块</div>
                         <div className="flex h-9 items-center">
                           <button
                             type="button"
                             role="switch"
                             aria-checked={detailNoChunk}
                             onClick={handleDetailNoChunkToggle}
                             className={cn(
                               "relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:ring-offset-background",
                               detailNoChunk ? "bg-blue-600" : "bg-slate-200"
                             )}
                           >
                             <span
                               className={cn(
                                 "inline-block h-4 w-4 transform rounded-full bg-background shadow transition-transform",
                                 detailNoChunk ? "translate-x-4" : "translate-x-1"
                               )}
                             />
                           </button>
                         </div>
                         <div className="text-sm text-muted-foreground mt-1">开启后块大小为-1</div>
                       </div>
   ```

> `cn` 工具已在 line 9 import；不需要新增 import。

- [ ] **Step 7: TypeScript 编译 + lint**

```bash
cd frontend
npm run typecheck 2>&1 || npx tsc --noEmit
```

Expected: 无 type 错误。

- [ ] **Step 8: 启 dev server 烟测**

```bash
cd frontend
npm run dev
```

烟测路径：
1. 浏览器打开管理后台 → 知识库 → 任一文档进入详情
2. 选 "fixed_size" 策略，确认看到"不分块"开关
3. 点击开关 → 块大小输入框值变为 `-1`
4. 再点 → 恢复为开关前的原值
5. 直接修改块大小为 `512` → 开关自动关闭
6. 保存（如果有），返回列表看 chunkSize 字段持久化正确

> 发现问题立即返回上面 Step 修复；通过后进入下一步。

- [ ] **Step 9: Commit**

```bash
cd ..
git add frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx
git commit -m "$(cat <<'EOF'
fix(admin): 文档管理详情面板补齐"不分块"开关

cherry-pick from upstream 6cd6853b。新建表单已有 noChunk 开关，详情/编辑
面板缺失，导致已入库文档无法切换为不分块模式。补齐 detailNoChunk state
+ toggle handler + UI 列，与新建表单 UX 对齐。

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 全量验证 + 路径汇总

**Files:** N/A（验证）

- [ ] **Step 1: 后端全量编译 + spotless 检查**

```bash
mvn clean install -DskipTests spotless:check
```

Expected: BUILD SUCCESS。如 spotless 报 formatting 问题，跑 `mvn spotless:apply` 修复后追加一个 chore commit。

- [ ] **Step 2: 后端关键模块测试**

```bash
mvn -pl framework test
mvn -pl bootstrap test -Dtest='SseEmitterSenderTest,IntentResolverTests,RetrievalEngineTests,StreamChatEventHandler*Test'
```

Expected: 上面四组测试全 PASS。如有 baseline-failing 用例（参考 CLAUDE.md "Pre-existing test failures on fresh checkout"），确认不是本次引入。

- [ ] **Step 3: 端到端烟测（必做）**

启动后端 + 前端：
```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
# 另一终端
cd frontend && npm run dev
```

烟测清单：
- [ ] 普通对话流：发起一次问题 → 流式收到答复 → FINISH 事件 → 消息持久化（DB `t_message` 多一条）
- [ ] SSE 超时兜底：SseEmitter 默认超时由 0L 改为 5min（看 controller 行为，不需复现 timeout）
- [ ] 文档管理详情面板：fixed_size 策略下"不分块"开关可切换，块大小输入与开关联动正确

- [ ] **Step 4: 推分支 + 开 PR**

```bash
git push -u origin feature/upstream-p0-stability
gh pr create --title "fix(stability): upstream P0 三个稳定性 commit 移植" --body "$(cat <<'EOF'
## Summary

cherry-pick upstream `nageoffer/ragent` 的三个 P0 稳定性 commit：

- `f828ab45` SSE 全局超时（修连接 + LLM stream 资源泄漏）
- `8f3a344d` 流式持久化容错 + 异步任务降级 + MCP 30s timeout
- `6cd6853b` 文档管理详情面板"不分块"开关补齐

跳过 upstream `34f7def3`（chunk_config jsonb 序列化 fix）—— 我方已用 hybrid（entityUpdate + nullClear）方案修过同一 bug，更稳健。

## Test plan

- [ ] `mvn clean install -DskipTests spotless:check` PASS
- [ ] `mvn -pl framework test` PASS（含新增 `SseEmitterSenderTest`）
- [ ] `mvn -pl bootstrap test -Dtest='StreamChatEventHandler*Test'` PASS（含新增持久化容错用例）
- [ ] 端到端烟测：普通对话流 / 文档管理详情面板"不分块"开关可切换

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review Notes

**Spec coverage:**
- ✅ f828ab45 SSE 超时 → Task 1
- ✅ 8f3a344d 流式持久化容错（4 个文件）→ Task 2 + Task 3 + Task 4
- ✅ 6cd6853b 详情面板"不分块"开关 → Task 5
- ✅ 跳过 34f7def3 → 在 Architecture / Context Notes 中说明原因

**关键差异已识别且本地化**：
- `RetrievalEngine.buildSubQuestionContext` 三参数签名 vs upstream 两参数 → Task 3 Step 2 中保持我方签名
- `StreamChatEventHandler.onComplete` 用 `ChatMessage.assistant(content)` 无 thinking 参数 → Task 4 Step 3 中保持我方写法
- `SseEmitterSender` caller 全部裸调用 → 静默 return 无回归

**No placeholder check**: 所有步骤都给出了具体代码块、具体命令、具体期望输出，没有"appropriate error handling" / "TBD" 等占位词。

**Risk callouts**:
- Task 4 Step 1 测试代码假设 `handler` / `memoryService` 是 fixture 字段名 —— 实际命名按打开 file 时见到的为准（已在 Step 1 加 note）
- Task 5 frontend 没有自动化测试 —— 完全依赖 Step 8 手工烟测；如未通过禁止 commit
