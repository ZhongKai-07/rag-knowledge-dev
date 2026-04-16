# Suggested Questions（追问预测）+ 联调修复 + 链路追踪曝光

**日期**：2026-04-16
**分支**：`feature/suggested-questions`
**状态**：实现完成 + 验收修复完成，待合并
**设计文档**：`docs/superpowers/specs/2026-04-16-suggested-questions-design.md`
**实施计划**：`docs/superpowers/plans/2026-04-16-suggested-questions.md`

---

## 背景与目标

每次 RAG 回答完成后，基于本轮问答 + 检索片段，通过一次独立的小模型调用（`qwen-turbo`）预测 3 个后续追问，以 chip 按钮形式展示在回答下方。类似 ChatGPT "Continue" / Perplexity "Related questions"。

核心约束：
- 不破坏现有 SSE 契约（`meta → message* → finish → done` 变成 `finish → suggestions? → done`，新增只插一条事件）
- 推荐生成是辅助 UX，失败不影响主链路
- 不新建持久化表，审计数据合并进现有 `t_rag_trace_run.extra_data`

---

## 第一阶段：22 Task 顺序实现（subagent-driven + TDD）

29 commit 从 `c3653b5`（spec）到 `e2619e4`（yaml），每个 Task 单独 commit。

### 后端基础设施（Task 1-8）

| Commit | 内容 |
|--------|------|
| `91d1eb5` | `SSEEventType.SUGGESTIONS` 枚举项 |
| `723c38e` | `SuggestionsPayload(messageId, questions)` record |
| `48e431a` | `SuggestionContext(question, history, topChunks, shouldGenerate)` + `skip()` 工厂 |
| `aa30c74` | `RAGConfigProperties.suggestions.{enabled,modelId,maxOutputTokens,timeoutMs}` 4 字段 |
| `ca532cb` | `RAGConstant.SUGGESTED_QUESTIONS_PROMPT_PATH` |
| `6429b82` | `prompt/suggested-questions.st` 模板 |
| `5858dcc` | `@Bean("suggestedQuestionsExecutor")` 专用线程池（core=2 / max=8 / queue=50 / AbortPolicy） |
| `a7162db` | `SuggestedQuestionsService` 接口 |

### TDD 核心服务（Task 9-13）

`DefaultSuggestedQuestionsService` 分 4 个 TDD 循环、5 个单元测试：

| Commit | 测试 | 防御点 |
|--------|------|--------|
| `e015075` | `happy_path_returns_three_questions_from_valid_json` | 正常 JSON 解析 |
| `cb52a7e` | `tolerates_markdown_code_fence_wrapping_json` | 剥 ```json ... ``` |
| `62df87a` | `returns_empty_when_llm_throws` | LLM 异常返空 |
| `7a99afb` | `returns_empty_when_llm_returns_garbage` + `returns_empty_when_questions_field_missing` | 非 JSON / 字段缺失 |
| `82276d9` | `RagTraceRecordService.mergeRunExtraData` 3 个测试（null / preserve / override） | 合并到 `extra_data` |

Task 9 意外发现 `bootstrap/pom.xml` 缺 surefire 3.2.5 override（parent pom 的 `@{argLine}` 依赖未装的 JaCoCo，且 2.12.4 发现不了 JUnit 5），随 commit 一起修了——sibling `framework/pom.xml` 的现有 override 是模板。

### Stream 处理器接线（Task 14-17）

| Commit | 内容 |
|--------|------|
| `9a204c0` | `StreamChatHandlerParams` 加 3 字段；`StreamCallbackFactory` 返回类型窄化为 `StreamChatEventHandler`（以调用 setter）；`StreamChatEventHandler.onComplete` 重写为"FINISH → 异步 submit → finally 保证 DONE + unregister + complete"三段式，抽出 `sendDoneAndClose()` / `generateAndFinish()` / `mergeSuggestionsIntoTrace()` 三个私有 helper |
| `be68347` | `StreamChatEventHandlerSuggestionsTest` 3 场景集成测试（happy / skip / throws）。测试期间发现原 plan 里的 `RecordingEmitter` 反射 `name` 字段是错的——Spring 6 的 `SseEventBuilderImpl` 把 "event:name\n" 塞进一个 `DataWithMediaType` 的 `getData()` 字符串里，改成扫 `build()` + `String.startsWith("event:")` 并 `indexOf('\n')` 切片才正确 |

### 编排器 + 配置（Task 18-19）

| Commit | 内容 |
|--------|------|
| `7f30117` | `RAGChatServiceImpl` 在 KB-answer 路径（`ctx.isEmpty()` 之后、`evalCollector.setTopK` 之前）装配 `SuggestionContext`：扁平化 `intentChunks` 取 top-3、检测是否含 MCP 意图（`ns.getNode().isMCP()`）、回填 handler 的 `updateSuggestionContext`。guidance/sysOnly/empty-retrieval 三条短路分支**不调用** setter，让 handler 默认 `skip()` 生效 |
| `e2619e4` | `application.yaml` 新增 `rag.suggestions` 配置节 |

### 前端（Task 20-22）

| Commit | 内容 |
|--------|------|
| `e35a979` | `SuggestionsPayload` 类型 + `useStreamResponse` 的 `onSuggestions` handler + `case "suggestions"` dispatch |
| `0953fd3` | `Message.suggestedQuestions?: string[]` + `chatStore.sendMessage` 绑定 `onSuggestions`，按 `messageId` 更新消息 |
| `f4381d4` | `MessageItem.tsx` 在 assistant 消息下渲染可点击 chip 按钮，点击调 `sendMessage(q)` |

---

## 第二阶段：验收联调修复（4 个 bug）

启动后端后点击 chip 复测，日志暴露 4 个问题：

### Bug 1：`t_message.thinking_content` 列不存在

**症状**：第二轮对话 loadHistory 抛 `PSQLException: column "thinking_content" does not exist`。

**根因**：v1.4 schema 迁移（`upgrade_v1.3_to_v1.4.sql`）没跑，DB 还是 v1.3，entity 已经声明了该字段。

**修复**：用户直接执行迁移脚本，无需代码改动。

### Bug 2：IntentNode Redis 缓存反序列化永远失败（`d49e339`）

**症状**：每次请求都打印 `Cannot construct instance of IntentNode (no Creators, like default constructor, exist)`，fallback 到 `IntentTreeFactory` 重建。意图分类器看到的 tree 是空的 `[]`，所有查询都走 multi-channel 兜底（其实 OK，但慢）。

**根因**：`IntentNode` 用了 `@Data @Builder` 但没加 `@NoArgsConstructor @AllArgsConstructor`。Lombok 对两者同时存在且无 final 字段的情况，并未可靠地生成一个 **public** 无参构造。Jackson 的 `ObjectMapper.readValue` 找不到 Creator 直接报错。

**修复**：补 `@NoArgsConstructor @AllArgsConstructor` → `javap` 验证两个构造都有 → 清 Redis 缓存。

**审计**：所有 `@Data @Builder` 类扫一遍 —— 其他 `@RequestBody` / MQ event / NodeScore 已有全套注解；只 `IntentNode` 是 outlier。DO 类只走 MyBatis Plus 不经 Jackson，不受影响。

### Bug 3：Dashboard 总 Token 聚合 SQL 炸（`fc46de1`）

**症状**：`invalid input syntax for type integer: "5228.0"`。`/admin/dashboard/overview` 直接 500。

**根因分析**：
```
onComplete 主链路 → updateRunExtraData 用 StrUtil.format 写 {"totalTokens":5228}  ✓
executor.submit(generateAndFinish) → finally → mergeRunExtraData（Task 13 刚加的）
  ↓
  Gson.fromJson(json, Map.class)  ← ★ Gson 的特性：所有数字都变 Double
  ↓
  merged = {totalTokens=Double(5228.0), ...}
  ↓
  Gson.toJson(merged) → "totalTokens":5228.0  ← 整数类型丢失
  ↓
  Dashboard SQL: CAST(extra_data::json->>'totalTokens' AS INTEGER) 炸在 "5228.0"
```

**时间链**：此 bug **只出现在本 PR 引入 `mergeRunExtraData` 之后**。老代码只有 `updateRunExtraData` 直接写整数字面量，从未经过 Gson round-trip。Task 13 的 code-review agent 当时提醒过 "raw `Map.class` → Double" 但我判定是 polish、低估业务影响，后悔。

**修复**（双保险）：
1. **根因**：`RagTraceRecordServiceImpl.mergeRunExtraData` 从 Gson 换成 Jackson（`ObjectMapper.readValue(json, TypeReference<Map<String,Object>>)`），保留 Integer/Long。
2. **防御**：`RagTraceRunMapper.sumTokensInWindow` 从 `CAST(... AS INTEGER)` 改为 `CAST(CAST(... AS NUMERIC) AS BIGINT)`，兼容历史已写入的 `"5228.0"` 行，无需数据清洗。

### Bug 4（非 bug）：用户反馈 "检索到 chunk 但显示未检索到"

**症状**：第二轮对话收到 chunks 但前端显示"未检索到相关知识"。

**诊断**：用户提供的日志只到 `14:13:06.687` 就截断了，retrieval 明明 10 chunks 成功。代码路径分析表明 `ctx.isEmpty()` 应返 false 才对。实际上 Bug 1（列不存在）解决后就不复现了 —— 之前应该是 loadHistory 失败传递到了其他路径。

---

## 第三阶段：链路追踪曝光（2 commit）

### Option A：Trace 详情页展示 chip（`a309650`）

- `RagTraceRunVO` 加 `List<String> suggestedQuestions`
- `RagTraceQueryServiceImpl.parseTokenUsage` 扩展：同一次 Gson 解析顺带读 `suggestedQuestions` 数组
- 前端 `ragTraceService.ts` 加类型
- `RagTraceDetailPage.tsx` 指标行和瀑布图之间插入一张 Card，展示 3 个只读 chip

零额外 DB 调用、零新持久化，纯读路径扩展。

### Option B（半做）：`@RagTraceNode` 包装（`ff96bbd`）

从 trace 截图发现底部 `bailian-chat` 2.13s @16.56s 就是 suggestion LLM call，但和其他 `bailian-chat` 混在一起没法识别。

原计划 Option B 要改线程池 + 补 TTL，工作量 × 5；但截图里的 `bailian-chat` 已经能出现在 trace 中，说明 `TransmittableThreadLocal` 已经跨过了 `ThreadPoolTaskExecutor`（非预期，比我 follow-ups 里写的乐观）。所以**只需 1 行注解**：`@RagTraceNode(name="suggested-chat", type="SUGGESTION")` 挂在 `DefaultSuggestedQuestionsService.generate`。

实测验证后同步更新 `docs/dev/follow-ups.md` 里的 OBS-1（核心已完成）和 OBS-2（降级为纯一致性问题）。

---

## 第四阶段：`/simplify` 三 agent 审查清理（`abc3825`）

3 agent 并行审（reuse / quality / efficiency），共 13 条 finding，真正应用了 8 条（-34 / +27 行，净减 7 行）：

| 类别 | 修复 |
|------|------|
| **复用** | `RAGChatServiceImpl` dedup `flatMap + distinct`（3 agent 一致标注）；`DefaultSuggestedQuestionsService.renderPrompt` 用 `PromptTemplateUtils.fillSlots` 替掉手写 `.replace()` 链；`RagTraceDetailPage` chip 换 `<Badge variant="outline">` |
| **质量** | `SuggestionContext.skip()` javadoc 去除对 "handler" 的引用（领域层泄漏）；`RagTraceRunMapper` / `RagTraceRecordServiceImpl` 压缩冗长注释；`RagTraceQueryServiceImpl` 内联 FQN 改 import；`StreamChatEventHandler.onComplete` 去掉不可达的 `ctx != null` 守护 |

主动忽略的建议：`mergeRunExtraData` vs `updateRunExtraData` race（auto-commit + 秒级异步延迟已规避）；factory 返回窄类型的 leaky abstraction（原设计已承认 + javadoc 标注，引入窄接口是把简单问题复杂化）；`StreamChatHandlerParams` 字段 javadoc（系统性清理是独立 PR）。

---

## 第五阶段：文档沉淀

### `docs/dev/follow-ups.md` 新增 3 条（`27f961e`，后经 `ff96bbd` 更新）

| ID | 内容 | 当前状态 |
|----|------|---------|
| OBS-1 | Suggested Questions 挂 `@RagTraceNode` 节点 | ✅ 核心已完成，剩下 inputData/outputData 快照可选 |
| OBS-2 | `suggestedQuestionsExecutor` 与其他池风格不一致 | 降级为纯代码一致性问题（TTL 实测 OK） |
| OBS-3 | `sendEvent(FINISH, ...)` 未包 try/catch 导致 taskManager 泄漏 | 待做（pre-existing，本 PR 放大了窗口） |

### `CLAUDE.md` + `bootstrap/CLAUDE.md` 新增 3 条（`a9c0e67`）

| Key Gotcha | 起因 |
|------------|------|
| `extra_data` Gson vs Jackson 分工 | Bug 3 的直接教训：query path Gson `getAsInt()` 兼容，但 merge/write MUST 用 Jackson |
| `@Data @Builder` 对 Jackson 不够 | Bug 2 的直接教训：Redis/HTTP/MQ 反序列化必加 `@NoArgsConstructor @AllArgsConstructor` |
| `PromptTemplateUtils.fillSlots` 工具 | 本 session 手写 `.replace()` 链，被 simplify review 捕获；链到 rag 域 key-class 表里 |

---

## 关键数字

| 指标 | 值 |
|------|----|
| Commit 数 | 29（含 spec + plan + dev log） |
| 后端新增 Java 文件 | 6 |
| 后端修改 Java 文件 | 11 |
| 前端新增/修改 | 5 |
| 单元测试 | 11（5 service + 3 merge + 3 handler integration） |
| 核心代码行数变化 | +3718 / -16（含 spec + plan 2590 行文档） |
| 清理后净行数 | 清理修复贡献 +27 / -34（净 -7） |

---

## 踩坑回放

1. **Code review 预警别忽视**：Task 13 reviewer 一句 "raw Map.class → Double, hence 100.0 test assertions" 就是后来 Bug 3 的完整剧透，我当时判为 polish 错失预警。凡是涉及数据 round-trip + 下游 CAST / 硬类型消费的，review 提到的类型问题都必须按 blocking 处理。

2. **多写几次 `git add <path>` 不会死**：session 里有一次 `git add -u` 把 working tree 里已有的 `CLAUDE.md`/`frontend/package.json`/`frontend/vite.config.ts` 改动一起 stage 了，赶紧 `git reset HEAD <file>` 回来。CLAUDE.md 早就说过"prefer adding specific files by name"，一致遵守成本极低。

3. **Plan 里的反射黑魔法务必验证**：Task 17 集成测试的 `RecordingEmitter` 反射 `SseEventBuilderImpl.name` 字段，plan 这么写我也这么抄，跑起来才发现 Spring 6 把 `event:name\n` 和 `data:` 合并到了同一个 `DataWithMediaType` 里，要扫 `build()` 切片才对。原 plan 作者（我）当时就应该现场写个最小 repro 验证，而不是当然耳地抄。

4. **并发假设要实证**：follow-ups 里我一开始把 "OBS-2 executor TTL 包装" 列为 Option B 的**前置依赖**，按"项目其他池都用 `TtlExecutors`"的方向推。后来截图显示 `bailian-chat` 已经出现在 trace 里，说明 `TransmittableThreadLocal` 的传播机制比我想的宽（大概率是 agent 级别的 instrumentation 或 spring-aop + 当前线程状态捕获）。**猜测并发模型的代价=绕远路** —— 先跑一遍看实际行为再决定是否需要 refactor。
