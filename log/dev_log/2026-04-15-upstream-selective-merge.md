# Upstream 选择性融合（5 个 Task / 7 个 Commit）

**日期**：2026-04-15
**分支**：`feature/upstream-selective-merge`
**状态**：已完成
**实施计划**：`docs/superpowers/plans/2026-04-15-upstream-selective-merge.md`

---

## 背景

上游仓库 `nageoffer/ragent` 在本项目 fork（2026-04-06）后持续开发，新增约 20 个 commit（截至 04-13）。对比后识别出 4 个方向：

| 方向 | 评估结论 |
|------|---------|
| 1. LLM/Embedding 基础设施重构 | **高价值**，部分可快速融合（`chat(req, modelId)` 重载 + ProbeStreamBridge），抽象基类需后续专项 |
| 2. 知识库 Chunk 管理增强 | 已包含在 fork 中，无需合并 |
| 3. 文档调度与防护 | 已包含在 fork 中，无需合并 |
| 4. 对话存储重构（thinking 字段） | **中等价值**，thinkingContent/Duration 可独立融合，kbId 部分跳过 |

---

## 改动明细

### Task 1：会话消息 thinking 链持久化

**Commit**: `d09329a`, `24e6df1`

在 `t_message` 表增加 `thinking_content TEXT` 和 `thinking_duration BIGINT`，全链路贯通：

```
ChatMessage (framework) → ConversationMessageBO → ConversationMessageDO → ConversationMessageVO
                                                    ↑ @TableField(updateStrategy = NOT_NULL)
JdbcConversationMemoryStore: append() 写入 / toChatMessage() 读取
ConversationMessageServiceImpl: listMessages() VO 映射
```

- DB 升级脚本：`resources/database/upgrade_v1.3_to_v1.4.sql`
- ChatMessage 移除无用 `@AllArgsConstructor`（code review 发现 4-arg 构造器从未被调用）
- **约束**：保留 `kbId` 参数不动（Knowledge Spaces 功能）

### Task 2：`LLMService.chat(request, modelId)` 重载

**Commit**: `a9fbba1`

- `LLMService` 接口新增 `default String chat(ChatRequest, String modelId)` — 空模型 ID 走默认路由
- `RoutingLLMService` override：按 modelId 过滤候选列表 → `executor.executeWithFallback`
- 为 Task 4 的节点简化提供基础

### Task 3：ProbeStreamBridge 替换 FirstPacketAwaiter

**Commit**: `5716201`, `3b074cd`（/simplify 修锁）

- 新建 `ProbeStreamBridge.java`：用 `CompletableFuture` + `Runnable` lambda 缓冲替代 `CountDownLatch` + 类型化事件枚举
- 删除 `FirstPacketAwaiter.java` + `RoutingLLMService` 内部类 `ProbeBufferingCallback`（含 `BufferedEvent` record + `EventType` enum）
- 保留 `onTokenUsage` 回调（上游没有，我们的扩展）
- `/simplify` 发现 `commit()` 锁内回放回归 → 修复为 snapshot+clear 后在锁外回放

### Task 4：bootstrap 节点依赖简化

**Commit**: `ec856fe`, `3b074cd`（/simplify 删包装方法）

| 文件 | 改动前依赖 | 改动后依赖 | 减少行数 |
|------|-----------|-----------|---------|
| `EnhancerNode` | `ChatClient` + `ModelSelector` + `ModelTarget` | `LLMService` | -36 |
| `EnricherNode` | `ChatClient` + `ModelSelector` + `ModelTarget` | `LLMService` | -36 |
| `ChunkEmbeddingService` | `EmbeddingClient` + `ModelSelector` + `ModelTarget` | `EmbeddingService` | -32 |

架构改善：bootstrap 不再穿透到 infra-ai 内部实现类，只依赖公开服务接口。

### Task 5：RoutingEmbeddingService 统一

**Commit**: `33ab4e3`

- `embed(text, modelId)` / `embedBatch(texts, modelId)` 改用 `executor.executeWithFallback`（与无 modelId 版本一致）
- 移除冗余的手动 `healthStore.allowCall()` + try/catch（executor 内部已处理）
- 移除 `ModelHealthStore` 字段依赖

---

## 量化

| 指标 | 数值 |
|------|------|
| 变更文件数 | 17 |
| 新增行 | +320 |
| 删除行 | -423 |
| 净减少 | 103 行 |
| bootstrap → infra-ai 内部类依赖 | 9 处 → 0 处 |

---

## /simplify 审查发现

| 严重度 | 问题 | 处理 |
|--------|------|------|
| HIGH | `ProbeStreamBridge.commit()` 锁内回放缓冲事件，阻塞流回调线程 | 已修复：snapshot+clear 后锁外回放 |
| Low | `EnhancerNode`/`EnricherNode` 一行 `private chat()` 包装方法无意义 | 已删除，直接调 `llmService.chat()` |
| Low | `@TableField(updateStrategy = NOT_NULL)` 在 insert-only 实体上 | 保留：用户明确要求作为防御性编码 |
| Medium | `chat(req, modelId)` 找不到 modelId 时静默 fallback vs Embedding 抛异常 | 保留：设计选择，graceful degradation |
| Medium | `LLMService` default 方法静默丢弃 modelId | 保留：标准 Java 接口模式 |

---

## 未触碰（后续排期）

| 项目 | 说明 |
|------|------|
| 抽象基类提取 | `AbstractOpenAIStyleChatClient` + `AbstractOpenAIStyleEmbeddingClient`，需同时重写 3 个 ChatClient + 2 个 EmbeddingClient，独立分支做 |
| StreamChatEventHandler thinking 累积 | 存储层已就绪，流式回调端还没攒 `thinkingContent` 写入 DB |
| 前端 thinking 内容展示 | 后端 VO 已带字段，前端尚未渲染 |

---

## 关键操作记录

```bash
# 添加上游远程
git remote add upstream https://github.com/nageoffer/ragent.git
git fetch upstream

# DB 升级（已执行）
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.3_to_v1.4.sql

# 启动前必须 install 全模块（framework/infra-ai 改了源码）
mvn clean install -DskipTests
mvn -pl bootstrap spring-boot:run
```
