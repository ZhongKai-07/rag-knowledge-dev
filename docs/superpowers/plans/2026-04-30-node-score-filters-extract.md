# 计划：NodeScoreFilters 工具类提取（#6 PR-A — infrastructure only）

> **PR 边界声明**：本 PR 仅交付**过滤工具类的技术债提取**，**不交付任何用户可见行为**。
> - 不引入引导式 LLM 二次确认
> - 不改变意图分类 / 检索 / 引导任何对外 API 或 SSE 事件序列
> - 不改产品行为或埋点
>
> 引导式 LLM 二次确认（触发条件、用户确认协议、LLM 失败兜底、SSE 事件、前端展示、埋点、验收）是 **#6 PR-C（dcdcc67b）** 的范围，不在本 PR 中。

- **来源 commit**：upstream `89594104 refactor(intent): 提取NodeScore过滤逻辑至工具类`
- **目标 PR**：单一 commit + 单一 PR
- **基线 HEAD**：`19794517 Merge pull request #35 from ZhongKai-07/memory-thread-pool-and-bo`
- **分支**：`node-score-filters-extract`（worktree 路径 `.worktrees/node-score-filters-extract`）
- **预计代码量**：4 文件改 + 3 新增（1 个生产工具类 + 2 个测试类）；约 +180/-55
- **预计耗时**：实施 40 min + 自测

---

## 1. 价值与目标

`IntentResolver` / `RetrievalEngine` / `IntentGuidanceService` 三处各自有 KB/MCP `NodeScore` 过滤逻辑，分散重复，且 `RetrievalEngine` 与 `IntentGuidanceService` 都内嵌 `INTENT_MIN_SCORE` gate（同一规则三处定义）。提取 `NodeScoreFilters` 工具类统一维护，为 #6 后续 PR-B（cb66d84c 异步过滤增强）和 PR-C（dcdcc67b 歧义 LLM 二次确认）的引导路径做基础设施铺垫——**避免 PR-C 时引导路径与 utility 漂移**。

---

## 2. fork 与 upstream 的关键行为差异

### IntentResolver（无差异）

| 调用点 | fork | upstream |
|---|---|---|
| `filterMcpIntents` 内部 | 无 score gate（gate 在 `classifyIntents` 上游已应用） | 无 score gate |
| `filterKbIntents` 内部 | 无 score gate | 无 score gate |

→ verbatim 替换安全。

### RetrievalEngine（行为差异）

> ⚠️ Fork 当前签名与 upstream 不同（PR2 起 `recallTopK / rerankTopK` 拆分）：
> - **fork**: `buildSubQuestionContext(SubQuestionIntent intent, RetrievalPlan plan, RetrievalScope scope)`
> - **fork**: `resolveSubQuestionPlan(SubQuestionIntent intent, int globalRecall, int globalRerank)` 返回 `RetrievalPlan(recallTopK, rerankTopK)`
> - **upstream（89594104 末态）**：`buildSubQuestionContext(SubQuestionIntent, int topK)` + `resolveSubQuestionTopK(...)`
>
> upstream diff 不能 verbatim 应用——会破坏 fork 的 recall/rerank 漏斗。本 PR 只替换两处方法**内部**的 `filterKbIntents / filterMCPIntents` 调用，方法签名/方法名 / `RetrievalPlan` 全部保留。

| 调用点 | fork（`filterMCPIntents` / `filterKbIntents`） | upstream（89594104 `NodeScoreFilters.mcp/kb`） |
|---|---|---|
| 内部 | **`score >= INTENT_MIN_SCORE` 后**再过 kind | 无 score gate |

→ 直接用 upstream 的 `NodeScoreFilters.kb(scores)` / `mcp(scores)` 替换会**丢失 RetrievalEngine 的 score gate**，是行为回归。

### IntentGuidanceService（重复定义）

`IntentGuidanceService.filterCandidates(scores)`（line 105-113）：
```java
.filter(ns -> ns.getScore() >= RAGConstant.INTENT_MIN_SCORE)
.filter(ns -> ns.getNode() != null && ns.getNode().isKB())
```
等价于 `NodeScoreFilters.kb(scores, INTENT_MIN_SCORE)`。本 PR 一并迁移，**避免 PR-C 引入 LLM 二次确认时引导路径与 utility 漂移**。

### 适配策略

在 `NodeScoreFilters` 直接提供**两组重载**（一次性引入，预先解锁 #6 PR-B / PR-C 需求）：

```java
public static List<NodeScore> mcp(List<NodeScore> scores);
public static List<NodeScore> mcp(List<NodeScore> scores, double minScore);
public static List<NodeScore> kb(List<NodeScore> scores);
public static List<NodeScore> kb(List<NodeScore> scores, double minScore);
```

- `IntentResolver.mergeIntentGroup` → 用无 gate 重载（与 fork 当前行为等价）
- `RetrievalEngine.buildSubQuestionContext / resolveSubQuestionPlan` → 用 gated 重载传 `INTENT_MIN_SCORE`（与 fork 当前行为等价）
- `IntentGuidanceService.filterCandidates` → 直接调用 `NodeScoreFilters.kb(scores, INTENT_MIN_SCORE)` 内联（删除 private 方法）

这样**三个调用点都行为级等价**，零回归。

---

## 3. 文件级改动清单

| # | 文件 | 类型 | 说明 |
|---|---|---|---|
| 1 | `bootstrap/.../rag/core/intent/NodeScoreFilters.java` | **新增** | 4 个静态方法（mcp / mcp+gate / kb / kb+gate） |
| 2 | `bootstrap/.../rag/core/intent/IntentResolver.java` | 修改 | `mergeIntentGroup` 改调 `NodeScoreFilters.mcp/kb`（无 gate）；删除两个 private 方法；清理 `StrUtil` / `IntentKind` import（如不再用） |
| 3 | `bootstrap/.../rag/core/retrieve/RetrievalEngine.java` | 修改 | `buildSubQuestionContext(intent, plan, scope)` + `resolveSubQuestionPlan(intent, globalRecall, globalRerank)` **签名/方法名/RetrievalPlan 不动**，仅把内部 `filterKbIntents/filterMCPIntents` 改调 `NodeScoreFilters.kb(scores, INTENT_MIN_SCORE)` / `mcp(scores, INTENT_MIN_SCORE)`；删除两个 private 方法；保留 `INTENT_MIN_SCORE` import |
| 4 | `bootstrap/.../rag/core/guidance/IntentGuidanceService.java` | 修改 | `filterCandidates` 删除（或内联调用方）→ 各调用点改 `NodeScoreFilters.kb(scores, RAGConstant.INTENT_MIN_SCORE)` |
| 5 | `bootstrap/src/test/java/.../rag/core/intent/NodeScoreFiltersTest.java` | **新增** | 工具类单测：低分剔除 / KB-MCP kind 互斥 / 空 mcpToolId 剔除 / null Node 安全 / 无 gate vs gated 对比 |
| 6 | `bootstrap/src/test/java/.../rag/core/retrieve/RetrievalEngineScoreGateRegressionTest.java` | **新增** | RetrievalEngine 回归测试：低分 KB intent 不进 rerank、低分 MCP intent 不被执行、`resolveSubQuestionPlan` 不被低分 topK override 影响 |

---

## 4. 详细 diff

### 4.1 新增 `NodeScoreFilters.java`

```java
/*
 * Licensed to the Apache Software Foundation (ASF) ...
 */
package com.knowledgebase.ai.ragent.rag.core.intent;

import cn.hutool.core.util.StrUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * NodeScore 过滤工具类
 * 统一 KB / MCP 意图过滤逻辑，避免多处重复定义。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NodeScoreFilters {

    /** 过滤 MCP 类型意图（node 非空、kind=MCP、mcpToolId 非空） */
    public static List<NodeScore> mcp(List<NodeScore> scores) {
        return scores.stream()
                .filter(ns -> ns.getNode() != null && ns.getNode().isMCP())
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    /** 带最小分数门控的 MCP 过滤 */
    public static List<NodeScore> mcp(List<NodeScore> scores, double minScore) {
        return scores.stream()
                .filter(ns -> ns.getScore() >= minScore)
                .filter(ns -> ns.getNode() != null && ns.getNode().isMCP())
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    /** 过滤 KB 类型意图（node 非空、kind 为 null 或 KB） */
    public static List<NodeScore> kb(List<NodeScore> scores) {
        return scores.stream()
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }

    /** 带最小分数门控的 KB 过滤 */
    public static List<NodeScore> kb(List<NodeScore> scores, double minScore) {
        return scores.stream()
                .filter(ns -> ns.getScore() >= minScore)
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }
}
```

### 4.2 `IntentResolver.java`

```java
public IntentGroup mergeIntentGroup(List<SubQuestionIntent> subIntents) {
    List<NodeScore> mcpIntents = new ArrayList<>();
    List<NodeScore> kbIntents = new ArrayList<>();
    for (SubQuestionIntent si : subIntents) {
        mcpIntents.addAll(NodeScoreFilters.mcp(si.nodeScores()));
        kbIntents.addAll(NodeScoreFilters.kb(si.nodeScores()));
    }
    return new IntentGroup(mcpIntents, kbIntents);
}
```

删除 `filterMcpIntents` / `filterKbIntents` 两个 private 方法（line 102-119）。

清理 import：
- 删除 `cn.hutool.core.util.StrUtil`（若 IntentResolver 不再用）
- 删除 `IntentKind`（若 IntentResolver 不再用）—— 注意 `isSystemOnly` 仍用 `getKind() == SYSTEM`，需检查 SYSTEM 是否走 `IntentKind` 静态导入

实际操作：删之前先 grep 该文件其他地方是否仍用这两个 import；不能盲删。

### 4.3 `RetrievalEngine.java`

> ⚠️ **签名/方法名 0 改**：fork 的 PR2 起 `recallTopK / rerankTopK` 漏斗 + `RetrievalScope` 权限注入必须保留。**禁止**采用 upstream 的 `int topK` / `resolveSubQuestionTopK` 形态。

```java
private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, RetrievalPlan plan,
                                                   RetrievalScope scope) {
    List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores(), INTENT_MIN_SCORE);
    List<NodeScore> mcpIntents = NodeScoreFilters.mcp(intent.nodeScores(), INTENT_MIN_SCORE);

    KbResult kbResult = retrieveAndRerank(intent, kbIntents, plan, scope);
    String mcpContext = CollUtil.isNotEmpty(mcpIntents)
            ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
            : "";
    return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
}

private RetrievalPlan resolveSubQuestionPlan(SubQuestionIntent intent,
                                              int globalRecall, int globalRerank) {
    int effectiveRerank = NodeScoreFilters.kb(intent.nodeScores(), INTENT_MIN_SCORE).stream()
            .map(NodeScore::getNode)
            .filter(Objects::nonNull)
            .map(IntentNode::getTopK)
            .filter(Objects::nonNull)
            .filter(topK -> topK > 0)
            .max(Integer::compareTo)
            .orElse(globalRerank);
    int effectiveRecall = Math.max(globalRecall, effectiveRerank);
    return new RetrievalPlan(effectiveRecall, effectiveRerank);
}
```

删除 `filterMCPIntents` / `filterKbIntents` 两个 private 方法（line 186-204）。

新增 import：
- `com.knowledgebase.ai.ragent.rag.core.intent.NodeScoreFilters`

清理 import：
- 删除 `cn.hutool.core.util.StrUtil`（若 RetrievalEngine 不再用）
- 删除 `IntentKind`（若 RetrievalEngine 不再用）
- 保留 `INTENT_MIN_SCORE` static import

实际操作：grep RetrievalEngine 检查 StrUtil / IntentKind 其他用法。

### 4.4 `IntentGuidanceService.java`

```java
// 替换 filterCandidates(scores) 调用点
// 旧: List<NodeScore> candidates = filterCandidates(scores);
// 新: List<NodeScore> candidates = NodeScoreFilters.kb(scores, RAGConstant.INTENT_MIN_SCORE);
```

删除 `filterCandidates` 方法（line 105-113）。

新增 import：
- `com.knowledgebase.ai.ragent.rag.core.intent.NodeScoreFilters`

清理 import：
- `RAGConstant` 仍要用到，保留

---

## 5. 架构与权限审计

| 检查项 | 结果 |
|---|---|
| 触碰 `framework/security/port` | ❌ |
| 触碰 `MetadataFilterBuilder` / `RetrievalScopeBuilder` | ❌ |
| 触碰 retrieval channel / metadata filter 注入链路 | ❌ |
| 改变 KB / MCP 意图过滤的语义（含 score gate） | ❌（gated 重载传 `INTENT_MIN_SCORE`，行为级等价） |
| 数据库迁移 | ❌ |
| `application.yaml` | ❌ |

纯方法提取 + 重命名调用，零行为变化。

---

## 6. 测试计划

### 6.1 编译

```bash
mvn -pl bootstrap install -DskipTests
```

### 6.2 静态自检

```bash
mvn spotless:apply && mvn spotless:check
```

### 6.3 启动冒烟（Redis/PG/OpenSearch 都需在跑）

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

观察启动日志：无 `BeanCreationException` / `NoSuchBeanDefinitionException`。

### 6.4 端到端

打开前端聊天，发一句问题：
- 问答正常返回，意图分类 / 检索流程正常
- 后端日志的 trace 节点 `IntentResolve` 和 `Retrieval` 行没有异常

### 6.5 单测（必做 — 锁住 score gate 行为）

> 评审反馈：score-gate 行为回归是本 PR 主要风险，**必须**有单测锁住。fork 已有 `IntentResolverDegradationTest` / `RetrievalEngineDegradationTest`，本 PR 在同目录补两份测试：

#### 6.5.1 `NodeScoreFiltersTest`（新增）

8 个 case：
- `kb_returnsOnlyKbKindAndNullKind` — KB intent + kind=null intent 都进，MCP 被剔
- `kb_filtersOutNullNode` — null Node 被剔
- `kb_withMinScore_dropsBelowGate` — 低分（< 0.35）KB intent 被剔；高分（≥ 0.35）保留
- `kb_withMinScoreZero_equalsNoGate` — minScore=0 时与无 gate 重载等价
- `mcp_returnsOnlyMcpKindWithToolId` — MCP + 非空 mcpToolId 进；MCP 但 toolId 空被剔；KB 被剔
- `mcp_filtersOutNullNode` — null Node 被剔
- `mcp_withMinScore_dropsBelowGate` — 低分 MCP intent 被剔
- `bothOverloads_emptyInput_returnEmpty` — 空 list 输入返回空

#### 6.5.2 `RetrievalEngineScoreGateRegressionTest`（新增）

使用 Mockito 纯单测风格（参考现有 `RetrievalEngineDegradationTest`），不启动 Spring Context，不用 `@MockBean`。`RetrievalEngine` 的过滤结果通过当前可观察协作点验证：

- `lowScoreKbIntent_isNotPassedToContextFormatter`
  - 构造两个 KB intent：`score=0.1/id=low-kb` + `score=0.9/id=high-kb`
  - mock `multiChannelRetrievalEngine.retrieveKnowledgeChannels(...)` 返回非空 chunk，确保走到 `contextFormatter.formatKbContext(...)`
  - 用 `ArgumentCaptor<List<NodeScore>>` 捕获 `formatKbContext(kbIntents, intentChunks, topK)` 第一个参数
  - 断言只包含 `high-kb`，不包含 `low-kb`
- `lowScoreMcpIntent_isNotResolvedOrExecuted`
  - 构造 `score=0.1` 的 MCP intent，`mcpToolId=approval_query`
  - mock KB 检索返回空 list
  - 调用 `retrieve(...)`
  - verify `mcpToolRegistry.getExecutor("approval_query")` **never()**，并 verify `contextFormatter.formatMcpContext(...)` **never()**
- `lowScoreKbTopKOverride_doesNotInfluenceRetrievalPlan`
  - 构造 `score=0.1 + topK=50` 的 KB intent；`RagRetrievalProperties` 使用默认 `recallTopK=30 / rerankTopK=10`
  - 用 `ArgumentCaptor<RetrievalPlan>` 捕获 `multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, plan, scope)` 的第二个参数
  - 断言 `plan.rerankTopK() == 10` 且 `plan.recallTopK() == 30`，证明低分 topK override 不参与 `resolveSubQuestionPlan`

---

## 7. 实施步骤

1. 创建 worktree
   ```powershell
   git worktree add .worktrees/node-score-filters-extract -b node-score-filters-extract main
   ```
2. 新建 `NodeScoreFilters.java`（4 个静态方法）
3. 改 `IntentResolver.java`：替换调用 → 删 private 方法 → 清理 import
4. 改 `RetrievalEngine.java`：**保留** `buildSubQuestionContext(intent, plan, scope)` + `resolveSubQuestionPlan(...)` 签名/方法名，仅替换内部 `filterKbIntents/filterMCPIntents` 调用为 `NodeScoreFilters.kb/mcp(scores, INTENT_MIN_SCORE)` → 删 private 方法 → 清理 import
5. 改 `IntentGuidanceService.java`：替换 `filterCandidates` 调用 → 删除该 private 方法 → 加 import
6. 新建 `NodeScoreFiltersTest.java`（8 case）
7. 新建 `RetrievalEngineScoreGateRegressionTest.java`（3 case）
8. `mvn -pl bootstrap test -Dtest=NodeScoreFiltersTest`，`mvn -pl bootstrap test -Dtest=RetrievalEngineScoreGateRegressionTest` 全 pass
9. `mvn -pl bootstrap install -DskipTests` 通过
10. `mvn spotless:apply && mvn spotless:check`
11. 启动冒烟
12. 提交：单 commit
    ```
    refactor(intent): 提取 NodeScoreFilters 工具类（带 score gate 重载）

    - 新增 NodeScoreFilters.kb / mcp 4 个静态方法（含 minScore 重载）
    - IntentResolver / RetrievalEngine / IntentGuidanceService 三处过滤逻辑统一收敛
    - RetrievalEngine 保留 buildSubQuestionContext(intent, plan, scope) 签名 + RetrievalPlan
      漏斗，仅替换内部调用，显式传 INTENT_MIN_SCORE 保留 score gate 行为
    - IntentGuidanceService.filterCandidates 一并迁移，避免 PR-C 时引导路径与 utility 漂移
    - 新增 NodeScoreFiltersTest (8 case) + RetrievalEngineScoreGateRegressionTest (3 case)
      锁住 score gate 行为
    - 本 PR 仅 infrastructure，不引入引导式 LLM 二次确认（属 PR-C 范围）

    Cherry-picked from upstream 89594104 (with score-gate overload pre-introduced
    and fork RetrievalPlan signature preserved).
    ```
13. push + PR

---

## 8. 风险

| 风险 | 缓解 |
|---|---|
| `IntentNode.isKB() / isMCP()` 不存在 | 已 grep 确认存在（line 143 / 150） |
| RetrievalEngine 丢失 score gate | gated 重载显式传 `INTENT_MIN_SCORE` + `RetrievalEngineScoreGateRegressionTest` 锁住 |
| upstream `int topK` / `resolveSubQuestionTopK` 形态被误用，破坏 fork PR2 的 recall/rerank 漏斗 | 计划 §4.3 显式标注**禁止**采用 upstream 签名；只动方法**内部**调用 |
| 删 import 误伤其他用法 | grep 文件其他出现位置 |
| `IntentGuidanceService` 改动改变了引导路径行为 | `filterCandidates` 旧实现 = `score >= INTENT_MIN_SCORE && isKB()`，新调用 `NodeScoreFilters.kb(scores, INTENT_MIN_SCORE)` 字节级等价 |
| 评审误以为 PR-A 交付 LLM 二次确认 | 文件首"PR 边界声明"明确边界 + commit message 重申 |
| spotless 格式化误判 | spotless:apply 自动修 |

---

## 9. 完成标准

- [ ] 新建 `NodeScoreFilters.java` 4 静态方法
- [ ] `IntentResolver.java` 替换 + 删 private + 清 import
- [ ] `RetrievalEngine.java` 替换（gated 重载，**保留** `buildSubQuestionContext(intent, plan, scope)` + `resolveSubQuestionPlan` 签名/`RetrievalPlan`） + 删 private + 清 import
- [ ] `IntentGuidanceService.java` 替换 `filterCandidates` + 删除该 private 方法
- [ ] 新增 `NodeScoreFiltersTest`（8 case）全 pass
- [ ] 新增 `RetrievalEngineScoreGateRegressionTest`（3 case）全 pass
- [ ] `mvn -pl bootstrap install -DskipTests` 通过
- [ ] `mvn spotless:check` 通过
- [ ] 启动冒烟通过
- [ ] PR 开启并 merge
- [ ] worktree 清理 + 本地 main 同步

---

## 10. 后续

PR-A 合入后立即进 #6 PR-B（`cb66d84c` 异步处理 + 过滤 + 超时增强中跟 NodeScoreFilters 相关的部分）。
