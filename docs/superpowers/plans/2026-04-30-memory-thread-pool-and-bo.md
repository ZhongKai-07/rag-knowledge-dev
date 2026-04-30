# 计划：记忆并行加载独立线程池 + ConversationCreate BO 解耦

- **来源 commit**：upstream `f1bc7524 optimize(conversation): 优化会话记忆相关代码`
- **目标 PR**：单一 commit + 单一 PR
- **基线 HEAD**：`a43aa2fc docs(coding-assistant): 第 4 章 — 部门 × 工具适配矩阵`
- **分支**：`memory-thread-pool-and-bo`（worktree 路径 `.worktrees/memory-thread-pool-and-bo`）
- **预计代码量**：5 个文件 +~85/-~22；新增 1 个文件（`ConversationCreateBO`）
- **预计耗时**：实施 30–40 min + 自测 + push/PR

---

## 1. 价值与目标

两件事打包：

### A. 独立 `memoryLoadThreadPoolExecutor`

`DefaultConversationMemoryService.load()` 当前用 `CompletableFuture.supplyAsync(() -> ...)` **不带 executor 参数**，默认走 ForkJoinPool 公共池。问题：

- `loadSummaryWithFallback` 内部走 `ConversationMemorySummaryService.loadLatestSummary`，路径上有 LLM 调用 + JDBC 阻塞 IO；占公共池等于把同 JVM 内**任何 parallelStream / supplyAsync** 拖慢
- 公共池没有背压，高并发下任务排队不可见

**Fix**：新增 `memoryLoadThreadPoolExecutor` Bean（核心 ≥ 2 / 最大 ≥ CPU / 队列 200 / `CallerRunsPolicy`），通过 `@Qualifier` 注入到 `DefaultConversationMemoryService`，两个 future 显式挂在专属池。

### B. `ConversationCreateRequest` → `ConversationCreateBO` 解耦

当前 `ConversationServiceImpl` / `JdbcConversationMemoryStore` 两个 service 层文件 import `controller.request.ConversationCreateRequest`。这是**反向依赖**（service 不该 import controller DTO）。

**Fix**：在 `rag/service/bo/` 新增 `ConversationCreateBO`（兄弟类 `ConversationMessageBO` / `ConversationSummaryBO` 已存在，目录约定一致），把 service 接口和 store 内部全部切到 BO。`ConversationCreateRequest` 本身**保留**作为 controller 入参类（如果有 controller 直接接收的话；当前看实际**没有 controller endpoint 用它**，是 store 内部构造，但保留无成本，仍可用于未来 admin 显式建会话场景）。

---

## 2. 必须保留的 fork 特化（cherry-pick 时跳过/适配）

### ⚠️ 跳过（保留 fork 现状）

- **`JdbcConversationMemoryStore.toChatMessage` 的 thinking 字段删除 hunk** — upstream 将 `new ChatMessage(role, content, thinkingContent, thinkingDuration)` 简化为 `new ChatMessage(role, content)`。这会破坏 v1.4 migration 的深度思考链持久化。fork 当前用 setter 构造（`msg.setThinkingContent(record.getThinkingContent())`），保持不动。

- **`ConversationCreateBO` 必须新增 `kbId` 字段**（upstream 没有）— 这是 v1.3 Knowledge Spaces feature 的关键字段，`JdbcConversationMemoryStore.append(...)` 第 90 行 `.kbId(kbId)` 使用中。upstream `ConversationCreateBO` 只有 `conversationId / userId / question / lastTime` 4 字段，我们要 5 字段。

### 🟢 跟随（fork 也受益）

- **`normalizeHistory` 移除冗余 filter** — fork `loadHistory` 第 68 行已有 `.filter(this::isHistoryMessage)`，但 `normalizeHistory` 第 120-122 行又 filter 了一次。upstream 简化掉冗余 filter。**直接跟随**，因为 fork 这个冗余确实是无意义的。

- **`ConversationServiceImpl.createOrUpdate` 入参类型替换** — 跟随。

- **`ConversationService` 接口签名替换** — 跟随。

---

## 3. 文件级改动清单

| # | 文件 | 类型 | 说明 |
|---|---|---|---|
| 1 | `bootstrap/.../rag/config/ThreadPoolExecutorConfig.java` | 修改 | 文末新增 `memoryLoadThreadPoolExecutor()` Bean |
| 2 | `bootstrap/.../rag/service/bo/ConversationCreateBO.java` | **新增** | 5 字段（含 `kbId`） |
| 3 | `bootstrap/.../rag/core/memory/DefaultConversationMemoryService.java` | 修改 | 构造器新增 `@Qualifier("memoryLoadThreadPoolExecutor") Executor`；两个 `supplyAsync` 加 executor 参数 |
| 4 | `bootstrap/.../rag/service/ConversationService.java` | 修改 | 接口入参 `ConversationCreateRequest → ConversationCreateBO` + import 切换 + javadoc 更新 |
| 5 | `bootstrap/.../rag/service/impl/ConversationServiceImpl.java` | 修改 | impl 入参类型同步切换 + import |
| 6 | `bootstrap/.../rag/core/memory/JdbcConversationMemoryStore.java` | 修改 | `append` 内部构造 `ConversationCreateBO`；import 切换；`normalizeHistory` 去掉冗余内层 filter；**`toChatMessage` 不动** |

---

## 4. 详细 diff

### 4.1 新增 `ConversationCreateBO.java`（fork 适配版，含 kbId）

```java
package com.knowledgebase.ai.ragent.rag.service.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 会话创建/更新业务对象 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationCreateBO {
    private String conversationId;
    private String userId;
    private String kbId;       // ← fork 特化：Knowledge Spaces 归属
    private String question;
    private Date lastTime;
}
```

License header 与兄弟类一致（Apache 2.0 标准头）。

### 4.2 `ThreadPoolExecutorConfig.java` 新增 Bean

文末追加：

```java
/**
 * 对话记忆加载线程池（并行加载摘要与历史记录）
 */
@Bean
public Executor memoryLoadThreadPoolExecutor() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(
            Math.max(2, CPU_COUNT >> 1),
            Math.max(4, CPU_COUNT),
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            ThreadFactoryBuilder.create()
                    .setNamePrefix("memory_load_executor_")
                    .build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    return TtlExecutors.getTtlExecutor(executor);
}
```

依赖 import 已存在（同文件其他 Bean 都用了 `ThreadPoolExecutor` / `TimeUnit` / `LinkedBlockingQueue` / `ThreadFactoryBuilder` / `TtlExecutors`），不需新增。

### 4.3 `DefaultConversationMemoryService.java`

构造器：
```java
private final Executor memoryLoadExecutor;

public DefaultConversationMemoryService(ConversationMemoryStore memoryStore,
                                        ConversationMemorySummaryService summaryService,
                                        @Qualifier("memoryLoadThreadPoolExecutor") Executor memoryLoadExecutor) {
    this.memoryStore = memoryStore;
    this.summaryService = summaryService;
    this.memoryLoadExecutor = memoryLoadExecutor;
}
```

`load()` 方法：
```java
CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
        () -> loadSummaryWithFallback(conversationId, userId), memoryLoadExecutor
);
CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
        () -> loadHistoryWithFallback(conversationId, userId), memoryLoadExecutor
);
```

新 imports：
```java
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
```

### 4.4 `ConversationService.java` 接口

```java
import com.knowledgebase.ai.ragent.rag.service.bo.ConversationCreateBO;
// 删除：import ...controller.request.ConversationCreateRequest;
...
/**
 * 创建或更新会话
 * 如果 ConversationCreateBO 里的会话 ID 存在则更新，不存在则创建
 */
void createOrUpdate(ConversationCreateBO request);
```

### 4.5 `ConversationServiceImpl.java`

签名 + import 同步切换：

```java
import com.knowledgebase.ai.ragent.rag.service.bo.ConversationCreateBO;
// 删除：import ...controller.request.ConversationCreateRequest;
...
@Override
public void createOrUpdate(ConversationCreateBO request) { ... }
```

方法体内对字段的调用（`request.getUserId() / getConversationId() / getQuestion()`）字段名一致，无需改。

### 4.6 `JdbcConversationMemoryStore.java`

**append 方法（line 87）**：
```java
ConversationCreateBO conversation = ConversationCreateBO.builder()
        .conversationId(conversationId)
        .userId(userId)
        .kbId(kbId)        // ← 必须保留
        .question(message.getContent())
        .lastTime(new Date())
        .build();
```

**imports**：
```java
import com.knowledgebase.ai.ragent.rag.service.bo.ConversationCreateBO;
// 删除：import ...controller.request.ConversationCreateRequest;
```

**normalizeHistory（line 116-134）跟随简化**：
```java
private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
    if (messages == null || messages.isEmpty()) {
        return List.of();
    }
    int start = 0;
    while (start < messages.size() && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
        start++;
    }
    if (start >= messages.size()) {
        return List.of();
    }
    return messages.subList(start, messages.size());
}
```
（去掉中间 `cleaned` 变量及 `.filter(this::isHistoryMessage)`，因为 `loadHistory` 第 68 行已经过滤过了）

**toChatMessage（line 104-114）不动** —— 保留 fork 的 setter-style 构造 + thinking 字段。

---

## 5. 架构与权限审计

| 检查项 | 结果 |
|---|---|
| 触碰 `framework/security/port`？ | 否 |
| 触碰 `MetadataFilterBuilder` / `RetrievalScopeBuilder` / `OpenSearchRetrieverService`？ | 否 |
| 触碰 retrieval channel 或 RAG 检索链路？ | 否 |
| 触碰 `t_conversation.kb_id` 归属验证（Knowledge Spaces 闸门）？ | 否 — `kbId` 仍透传到 `ConversationCreateBO`，下游 `createOrUpdate` 行为不变 |
| 触碰深度思考链持久化（v1.4 migration）？ | 否 — 显式跳过 `toChatMessage` 的 thinking 字段删除 hunk |
| 新建表/迁移？ | 否 |
| 改 `application.yaml`？ | 否 |
| 触碰 controller 入参契约（前端兼容）？ | 否 — `ConversationCreateRequest` 类保留；service 层切到 BO 是内部重构 |

**结论**：纯 service-internal 重构 + 线程池基础设施新增，零权限/契约面，零数据库面。

---

## 6. 测试计划

### 6.1 编译

```powershell
mvn -pl bootstrap install -DskipTests
```
通过即表示包替换、import 切换、Bean 注入都正确。

### 6.2 静态自检

```powershell
mvn spotless:apply
mvn spotless:check
```

### 6.3 单测

只跑可能受影响的两个测试类（如果存在）：

```powershell
mvn -pl bootstrap test -Dtest=DefaultConversationMemoryServiceTest
mvn -pl bootstrap test -Dtest=JdbcConversationMemoryStoreTest
```

> 即使没有现成的测试类，也不补 — 因为本 commit 是基础设施 + 类型替换，单测主要保 wiring，编译通过 + 启动通过即可。

### 6.4 启动冒烟

```powershell
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

观察启动日志：
- 没有 `NoSuchBeanDefinitionException` for `memoryLoadThreadPoolExecutor`
- 没有 `BeanCreationException` for `DefaultConversationMemoryService`

### 6.5 端到端

打开前端聊天界面，进任一 KB，发一句问题。观察：
- 问答正常返回
- 后端日志包含 `加载对话记忆 - conversationId: ..., 摘要: ..., 历史消息数: ..., 耗时: ...ms`（确认并行加载路径走通）
- 线程名包含 `memory_load_executor_*`（grep 日志）

预期 baseline：~50–200ms 每次记忆加载（取决于是否有摘要历史）。

---

## 7. 实施步骤（执行顺序）

1. 创建 worktree
   ```powershell
   git worktree add .worktrees/memory-thread-pool-and-bo -b memory-thread-pool-and-bo main
   cd .worktrees/memory-thread-pool-and-bo
   ```
2. 新建 `ConversationCreateBO.java`
3. 改 `ThreadPoolExecutorConfig.java`（追加 Bean）
4. 改 `DefaultConversationMemoryService.java`（构造器 + 两个 future 加 executor）
5. 改 `ConversationService.java` 接口
6. 改 `ConversationServiceImpl.java` impl
7. 改 `JdbcConversationMemoryStore.java`（append 用 BO + normalizeHistory 简化，**不动 toChatMessage**）
8. `mvn -pl bootstrap install -DskipTests` 通过
9. `mvn spotless:apply && mvn spotless:check`
10. 启动冒烟（5 min）
11. 提交：单 commit
    ```
    optimize(memory): 独立的对话记忆加载线程池 + ConversationCreate BO 解耦

    - 新增 memoryLoadThreadPoolExecutor，避免摘要+历史并行加载占用 ForkJoin 公共池
    - service 层从 ConversationCreateRequest 切到 ConversationCreateBO（保留 kbId 字段）
    - normalizeHistory 移除冗余 filter（loadHistory 已 filter 过一次）
    - 跳过 upstream 的 toChatMessage thinking 字段删除（保留 v1.4 深度思考持久化）

    Cherry-picked from upstream f1bc7524 (with 3 adaptations).
    ```
12. push + 创建 PR（标题 `optimize(memory): 独立线程池 + BO 解耦 (cherry-pick f1bc7524)`）
13. PR body 引用本计划文件路径
14. merge 后回 main：worktree 清理 + `git pull --ff-only origin main`

---

## 8. 风险

| 风险 | 缓解 |
|---|---|
| `@Qualifier` Bean 名拼错导致启动失败 | 启动冒烟必做 |
| `JdbcConversationMemoryStore.toChatMessage` 误删 thinking 字段 | 计划里显式标记，diff 中只看 `append` 和 `normalizeHistory` 两块；review 时三检 toChatMessage 行号 104-114 是否原样 |
| `ConversationCreateBO` 漏掉 `kbId` 字段 | 计划 §4.1 明确列出 5 字段；review 时对照 `ConversationCreateRequest` 字段集（kbId 是 v1.3 加的） |
| `ConversationCreateRequest` 实际还有 controller 调用（grep 不全） | 已 grep 全仓，`createOrUpdate(ConversationCreateRequest)` 无 controller 直调，只有 `JdbcConversationMemoryStore.append` 内部构造一处 |

---

## 9. 完成标准

- [ ] 计划路径 `docs/superpowers/plans/2026-04-30-memory-thread-pool-and-bo.md` 已落库（即本文）
- [ ] 6 个文件全部按 §3 表格修改完毕
- [ ] `mvn -pl bootstrap install -DskipTests` 通过
- [ ] `mvn spotless:check` 通过
- [ ] 启动 + 端到端聊天冒烟通过
- [ ] PR 开启并 merge
- [ ] 本地 main 同步 origin/main
- [ ] worktree 清理

---

## 10. 后续

完成后立即推进 #6 三连：
1. PR-A：cherry-pick `89594104`（NodeScoreFilters 工具类提取）
2. PR-B：cherry-pick `cb66d84c` 中 NodeScoreFilters 部分（异步处理 + 过滤 + 超时增强）
3. PR-C：cherry-pick `dcdcc67b`（歧义判断 LLM 二次确认）

每个 PR 单独走 plan → worktree → PR → merge 节奏。
