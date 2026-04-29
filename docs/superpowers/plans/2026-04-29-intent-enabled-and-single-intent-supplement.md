# Intent Enabled Filter + Single-Intent Supplement Threshold

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** cherry-pick upstream `nageoffer/ragent@b794fd17` 的两项意图层改进 —— (1) 意图树加载时强制过滤 `enabled=1`（修复禁用节点仍被分类的真 bug）；(2) `VectorGlobalSearchChannel` 新增第 3 触发条件 "单一中等置信度意图（单意图 + maxScore < 0.8）启用补充全局检索"（提升召回率）；并顺手简化 `IntentTreeCacheManager.clearIntentTreeCache` 的 try-catch（让 Redis 异常上抛而非静默吞掉）。

**Architecture:** 单一 PR 4 个文件 + 1 个新测试文件，全部位于 `rag/core/intent/` 与 `rag/core/retrieve/channel/` 包内，**不动 framework `security/port` 任何 Port**，**不动 `DefaultMetadataFilterBuilder` / `RetrievalScopeBuilder` 权限链路**。`VectorGlobalSearchChannel.isEnabled` 改动只决定通道**是否进入**，进入后仍走我方 PR4/PR5 强制注入的 `kb_id + security_level` filter 路径，权限语义零变化。

**Tech Stack:** Java 17 / Spring Boot 3.5.7 / MyBatis Plus（DB 查询）/ Spring Data Redis（StringRedisTemplate）/ JUnit 5 + Mockito。

---

## Context Notes for Implementer

- Upstream commit `b794fd17` 路径前缀 `com.nageoffer.ai.ragent` 在我方对应 `com.knowledgebase.ai.ragent` —— 仅用于检索定位。
- **跳过 upstream `IntentTreeCacheManager.isCacheExists` 的简化**：upstream 把 `return Boolean.TRUE.equals(exists)` 改为 `return stringRedisTemplate.hasKey(...)`，**丢失 null-safe 行为**（虽然外层 try-catch 能兜住 NPE，但显式 null-safe 写法更稳健）。我方保留原写法，只采纳 `clearIntentTreeCache` 的 try-catch 简化与日志措辞改进。
- **`SearchChannelProperties.VectorGlobal` 我方比 upstream 多一个 `@Deprecated topKMultiplier` 字段**：新增 `singleIntentSupplementThreshold` 字段插在 `confidenceThreshold` 之后、`@Deprecated topKMultiplier` 之前（与 upstream diff 行序对齐）。
- **测试设计**：必须新增 `VectorGlobalSearchChannelTriggerTest` 锁住单意图补充阈值的 3 个边界（单意图 + maxScore < 0.8 → enable；单意图 + maxScore >= 0.8 → disable；多意图 + maxScore < 0.8 → disable）。**不需要新增** `DefaultIntentClassifier` / `IntentTreeCacheManager` 单测——前者改的是 DB 查询条件（mock DB 后无业务断言可写），后者只是异常风格调整（无可观测行为变化）。
- **VectorGlobalSearchChannel 已有的 `*FilterAlignmentTest`、`*KbMetadataReaderTest`、`ChannelFailFastPropagationTest` 必须仍然 PASS** ——这是 PR4/PR5 留下的 metadata filter 注入契约，本次改动**不应该破坏**它们。
- **架构/权限合规**：详见 plan 末尾"Self-Review Notes"中的合规审计表。
- **单一逻辑提交**：4 个生产文件 + 1 个新测试同时提；不拆 commit。提交信息标明 cherry-pick 来源。

---

## File Structure

| 路径 | 责任 | 操作 |
| --- | --- | --- |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/SearchChannelProperties.java` | 检索通道配置 | Modify：在 `VectorGlobal` 内部类加 `singleIntentSupplementThreshold = 0.8` 字段 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/DefaultIntentClassifier.java` | 默认意图分类器 | Modify：`loadIntentTreeFromDB` 查询条件加 `.eq(IntentNodeDO::getEnabled, 1)` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentTreeCacheManager.java` | 意图树 Redis 缓存 | Modify：`clearIntentTreeCache` 去掉外层 try-catch + 改 else log 措辞（**保留** `isCacheExists` 原写法） |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java` | 向量全局检索通道 | Modify：`isEnabled` 加单意图补充阈值条件 + 顶部 javadoc 删 2 行过时描述 |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannelTriggerTest.java` | 通道触发条件单测 | Create：3 个用例锁住补充阈值边界 |

---

## Task 0: Setup Worktree + Branch

**Files:** N/A（git 操作）

- [ ] **Step 1: 在主仓库根目录创建 worktree**

```bash
git worktree add .worktrees/intent-supplement -b intent-supplement main
```

- [ ] **Step 2: 进入 worktree，确认基线**

```bash
cd .worktrees/intent-supplement
git status
git log --oneline -3
```

Expected：`On branch intent-supplement` / 工作树干净 / HEAD 是 `63258a50 Merge pull request #32 from ZhongKai-07/cleanup-ttl-minutes`。

---

## Task 1: 加配置字段 `singleIntentSupplementThreshold`

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/SearchChannelProperties.java`

- [ ] **Step 1: 在 `VectorGlobal` 内部类加字段**

打开 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/SearchChannelProperties.java`，定位 `VectorGlobal` 内部类（约 52-74 行）。在 `confidenceThreshold = 0.6;` 那行下方紧邻、`@Deprecated topKMultiplier` 上方，**插入**：

```java

        /**
         * 单意图补充检索阈值
         * 当仅识别出一个意图且分数低于此阈值时，启用全局检索作为安全网
         */
        private double singleIntentSupplementThreshold = 0.8;
```

插入后该段代码应为：

```java
        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * 单意图补充检索阈值
         * 当仅识别出一个意图且分数低于此阈值时，启用全局检索作为安全网
         */
        private double singleIntentSupplementThreshold = 0.8;

        /**
         * @deprecated since PR2 — retrieval topK amplification moved into
         * {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties} (recallTopK / rerankTopK split).
         * This field is no longer read by channel code; left in place only to avoid breaking existing yaml.
         * Will be removed once all deployments drop the setting from their config.
         */
        @Deprecated(forRemoval = true)
        private int topKMultiplier = 3;
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap -am compile -DskipTests
```

Expected：`BUILD SUCCESS`。Lombok `@Data` 会自动生成 `getSingleIntentSupplementThreshold()` getter。

---

## Task 2: VectorGlobalSearchChannel — TDD 单意图补充阈值

**Files:**
- Create: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannelTriggerTest.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`

- [ ] **Step 1: 写 3 个失败的单测**

创建 `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannelTriggerTest.java`：

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

package com.knowledgebase.ai.ragent.rag.core.retrieve.channel;

import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.rag.config.SearchChannelProperties;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.SearchContext;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VectorGlobalSearchChannelTriggerTest {

    private SearchChannelProperties properties;
    private VectorGlobalSearchChannel channel;

    @BeforeEach
    void setUp() {
        properties = new SearchChannelProperties();
        // 默认：confidenceThreshold=0.6, singleIntentSupplementThreshold=0.8
        channel = new VectorGlobalSearchChannel(
                mock(RetrieverService.class),
                properties,
                mock(KbMetadataReader.class),
                mock(MetadataFilterBuilder.class),
                Runnable::run
        );
    }

    @Test
    void singleIntentBelowSupplementThresholdEnablesChannel() {
        // ratio = 0.7 < 0.8 但 >= 0.6（不会被旧 confidenceThreshold 触发）
        SearchContext ctx = SearchContext.builder()
                .intents(List.of(new SubQuestionIntent("q", List.of(score(0.7)))))
                .build();

        assertThat(channel.isEnabled(ctx)).isTrue();
    }

    @Test
    void singleIntentAtOrAboveSupplementThresholdDoesNotEnableChannel() {
        // ratio = 0.85 >= 0.8 应跳过（高置信度单意图不需要兜底）
        SearchContext ctx = SearchContext.builder()
                .intents(List.of(new SubQuestionIntent("q", List.of(score(0.85)))))
                .build();

        assertThat(channel.isEnabled(ctx)).isFalse();
    }

    @Test
    void multipleIntentsDoNotTriggerSupplementThreshold() {
        // 两个意图都 < 0.8，但因为不是"单一意图"，不走补充阈值分支
        // maxScore=0.75 仍 >= confidenceThreshold(0.6)，所以不进入第二条件
        SearchContext ctx = SearchContext.builder()
                .intents(List.of(new SubQuestionIntent("q", List.of(score(0.75), score(0.7)))))
                .build();

        assertThat(channel.isEnabled(ctx)).isFalse();
    }

    private static NodeScore score(double s) {
        IntentNode node = new IntentNode();
        node.setId("kb-1");
        return new NodeScore(node, s);
    }
}
```

> **重要**：上方测试假定 `SearchContext.builder().intents(...).build()` 可用、`SubQuestionIntent` 是 record 形式 `(String subQuestion, List<NodeScore> nodeScores)`、`NodeScore` 是 record 形式 `(IntentNode node, double score)`。这些都是我方现有签名（参考已存在的 `VectorGlobalChannelFilterAlignmentTest`）。如果实际签名有出入，按打开文件时见到的为准。

- [ ] **Step 2: 跑测试，确认 fail**

```bash
mvn -pl bootstrap test -Dtest='VectorGlobalSearchChannelTriggerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：第 1 个用例 `singleIntentBelowSupplementThresholdEnablesChannel` **失败**（当前 `isEnabled` 在 maxScore=0.7 >= confidenceThreshold=0.6 时返回 false，没有补充阈值条件）。第 2、3 用例可能 PASS（旧逻辑已经能跳过）。

- [ ] **Step 3: 在 `VectorGlobalSearchChannel.isEnabled` 加单意图补充阈值条件**

修改 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`：

1. **类顶部 javadoc**（约 39-43 行）改为：

   ```java
   /**
    * 向量全局检索通道
    */
   ```

   即删除原 javadoc 中 `<p>在所有知识库中进行向量检索...低时启用` 那 2 行过时描述（新增触发条件后描述不准确，与 upstream 一致简化为单行）。

2. `isEnabled` 方法体（约 75-105 行）：在 `if (maxScore < threshold)` 那个 `return true;` 块的下方、最终 `return false;` 上方，**插入** 4 行：

   ```java
           double supplementThreshold = properties.getChannels().getVectorGlobal().getSingleIntentSupplementThreshold();
           if (allScores.size() == 1 && maxScore < supplementThreshold) {
               log.info("单一中等置信度意图（{}），启用补充全局检索", maxScore);
               return true;
           }
   ```

   插入后 `isEnabled` 完整方法应为：

   ```java
   @Override
   public boolean isEnabled(SearchContext context) {
       // 检查配置是否启用
       if (!properties.getChannels().getVectorGlobal().isEnabled()) {
           return false;
       }

       // 条件1：没有识别出任何意图
       List<NodeScore> allScores = context.getIntents().stream()
               .flatMap(si -> si.nodeScores().stream())
               .toList();
       if (CollUtil.isEmpty(allScores)) {
           log.info("未识别出任何意图，启用全局检索");
           return true;
       }

       // 条件2：意图置信度都很低
       double maxScore = allScores.stream()
               .mapToDouble(NodeScore::getScore)
               .max()
               .orElse(0.0);

       double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
       if (maxScore < threshold) {
           log.info("意图置信度过低（{}），启用全局检索", maxScore);
           return true;
       }

       // 条件3：单一中等置信度意图，启用补充全局检索作为安全网
       double supplementThreshold = properties.getChannels().getVectorGlobal().getSingleIntentSupplementThreshold();
       if (allScores.size() == 1 && maxScore < supplementThreshold) {
           log.info("单一中等置信度意图（{}），启用补充全局检索", maxScore);
           return true;
       }

       return false;
   }
   ```

   > 注意：upstream 同步删了原本的两行注释 `// 条件1：...` 和 `// 条件2：...`。我方**保留**这两行注释，因为新增"条件3"成立后注释体系完整，去掉反而损失可读性。

- [ ] **Step 4: 跑测试，确认 PASS**

```bash
mvn -pl bootstrap test -Dtest='VectorGlobalSearchChannelTriggerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：3/3 用例 PASS。

- [ ] **Step 5: 跑全部 VectorGlobal 相关测试，确认无回归**

```bash
mvn -pl bootstrap test -Dtest='VectorGlobal*Test,ChannelFailFastPropagationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：现有 `VectorGlobalChannelFilterAlignmentTest`、`VectorGlobalSearchChannelKbMetadataReaderTest`、`ChannelFailFastPropagationTest` 全部 PASS（这些覆盖 PR4/PR5 metadata filter 注入契约，不应受新增触发条件影响）。

---

## Task 3: DefaultIntentClassifier — 加 `enabled=1` 过滤

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/DefaultIntentClassifier.java`

- [ ] **Step 1: 在 `loadIntentTreeFromDB` 查询条件加 `.eq(IntentNodeDO::getEnabled, 1)`**

打开 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/DefaultIntentClassifier.java`，定位 `loadIntentTreeFromDB`（约 263-271 行）：

```java
    private List<IntentNode> loadIntentTreeFromDB() {
        // 1. 查出所有未删除节点（扁平结构）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
        );
```

修改为：

```java
    private List<IntentNode> loadIntentTreeFromDB() {
        // 1. 查出所有未删除且已启用的节点（扁平结构）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
        );
```

> `IntentNodeDO.enabled` 字段已存在（`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/dao/entity/IntentNodeDO.java:122`），无需改 DO。

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap -am compile -DskipTests
```

Expected：`BUILD SUCCESS`。

---

## Task 4: IntentTreeCacheManager — 简化 `clearIntentTreeCache`

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentTreeCacheManager.java`

- [ ] **Step 1: 把 `clearIntentTreeCache` 的外层 try-catch 去掉 + 改 else log 措辞**

打开 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentTreeCacheManager.java`，定位 `clearIntentTreeCache`（约 99-111 行）：

```java
    public void clearIntentTreeCache() {
        try {
            Boolean deleted = stringRedisTemplate.delete(INTENT_TREE_CACHE_KEY);
            if (deleted) {
                log.info("意图树缓存已清除，Key: {}", INTENT_TREE_CACHE_KEY);
            } else {
                log.warn("意图树缓存清除失败或缓存不存在");
            }
        } catch (Exception e) {
            log.error("清除意图树缓存失败", e);
        }
    }
```

替换为：

```java
    public void clearIntentTreeCache() {
        Boolean deleted = stringRedisTemplate.delete(INTENT_TREE_CACHE_KEY);
        if (deleted) {
            log.info("意图树缓存已清除，Key: {}", INTENT_TREE_CACHE_KEY);
        } else {
            log.info("意图树缓存不存在，无需清除");
        }
    }
```

> **不动 `isCacheExists` 方法**：upstream 把 `return Boolean.TRUE.equals(exists)` 改为 `return stringRedisTemplate.hasKey(...)` 丢失了 null-safe 语义，虽然外层 try-catch 兜得住但不严谨。我方保留原 null-safe 写法。

---

## Task 5: 全量验证 + Commit

**Files:** N/A（验证）

- [ ] **Step 1: 后端 spotless + 全量编译**

```bash
mvn clean install -DskipTests spotless:check
```

Expected：`BUILD SUCCESS`。如 spotless 报 formatting 问题，跑 `mvn spotless:apply` 修复后**作为本次改动的一部分一起 commit**。

- [ ] **Step 2: 跑相关测试套件**

```bash
mvn -pl bootstrap test -Dtest='VectorGlobal*Test,ChannelFailFastPropagationTest,MultiChannelRetrievalEngine*Test,OpenSearchRetrieverServiceFilterContractTest,IntentResolver*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：全部 PASS（含本次新增的 `VectorGlobalSearchChannelTriggerTest` 3 个用例）。

- [ ] **Step 3: 后端启动烟测（必做）**

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

观察启动日志：
- `SearchChannelProperties` 装配无错（新字段 `single-intent-supplement-threshold` 用默认值 0.8）
- 无 `Failed to bind properties under 'rag.search.channels'` 报错
- 应用启动完成，端口 9090 可访问

Ctrl+C 停止。

- [ ] **Step 4: 单一 commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/SearchChannelProperties.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/DefaultIntentClassifier.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/intent/IntentTreeCacheManager.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannelTriggerTest.java
git status
```

Expected：仅上述 5 个文件 staged。

```bash
git commit -m "$(cat <<'EOF'
optimize(intent): 意图树 enabled 过滤 + 单意图补充检索阈值

cherry-pick from upstream nageoffer/ragent@b794fd17。两项改进 + 一处简化：

1. DefaultIntentClassifier.loadIntentTreeFromDB 加 .eq(enabled, 1) 过滤
   - 修复 bug：禁用的意图节点之前会进入分类树被误识别
   - 现在只加载 deleted=0 AND enabled=1 的节点

2. VectorGlobalSearchChannel.isEnabled 加第 3 触发条件
   - 单一意图 + maxScore < 0.8 → 启用补充全局检索作为安全网
   - 配置项 rag.search.channels.vector-global.single-intent-supplement-threshold
     默认 0.8（与 upstream 一致）
   - 提升召回率：意图识别中等置信时全局向量检索做兜底
   - 不影响 PR4/PR5 metadata filter 注入：通道入口只决定"是否进入"，
     进入后仍走 DefaultMetadataFilterBuilder 强制 kb_id+security_level filter

3. IntentTreeCacheManager.clearIntentTreeCache 去掉外层 try-catch
   - Redis 异常应让调用方感知而非静默吞掉
   - else 分支日志措辞从"清除失败或缓存不存在"改为"缓存不存在，无需清除"
   - 保留 isCacheExists 原 Boolean.TRUE.equals null-safe 写法（不采纳 upstream
     该处简化）

新增测试：
- VectorGlobalSearchChannelTriggerTest（3 用例锁住补充阈值边界）

权限审计：
- 不动 framework/security/port 任何 Port
- 不动 DefaultMetadataFilterBuilder / RetrievalScopeBuilder / OpenSearchRetrieverService
- 通道入口改动只决定是否进入，进入后权限 filter 链路完整保留
- 已验证 ChannelFailFastPropagationTest / *FilterAlignmentTest / 
  OpenSearchRetrieverServiceFilterContractTest 全部 PASS

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Push + 开 PR

**Files:** N/A（git / GitHub 操作）

- [ ] **Step 1: 推分支**

```bash
git push -u origin intent-supplement
```

Expected：远程创建分支 `intent-supplement`，推送提示给出 GitHub PR URL。

- [ ] **Step 2: 创建 PR**

如果 `gh` CLI 已登录：

```bash
gh pr create --base main --head intent-supplement \
  --title "optimize(intent): 意图树 enabled 过滤 + 单意图补充检索阈值" \
  --body "$(cat <<'EOF'
## Summary

cherry-pick upstream `nageoffer/ragent@b794fd17` 的两项意图层改进 + 一处简化：

1. **`DefaultIntentClassifier.loadIntentTreeFromDB` 加 `.eq(enabled, 1)` 过滤**
   - **真 bug 修复**：禁用的意图节点之前会进入分类树被误识别
   - 现在只加载 `deleted=0 AND enabled=1` 的节点

2. **`VectorGlobalSearchChannel.isEnabled` 加第 3 触发条件**
   - 单一意图 + `maxScore < 0.8` → 启用补充全局检索作为安全网
   - 新增配置项 `rag.search.channels.vector-global.single-intent-supplement-threshold`，默认 0.8
   - **召回率改进**：意图识别中等置信时全局向量检索做兜底，减少"答案就在 KB 里但意图判错被忽略"的情况

3. **`IntentTreeCacheManager.clearIntentTreeCache` 去掉外层 try-catch**
   - Redis 异常应让调用方感知而非静默吞掉
   - else 分支日志措辞改进
   - **保留** `isCacheExists` 原 `Boolean.TRUE.equals` null-safe 写法（不采纳 upstream 该处简化）

## 权限/架构合规审计

- ✅ 不动 framework `security/port` 任何 Port
- ✅ 不动 `DefaultMetadataFilterBuilder` / `RetrievalScopeBuilder` / `OpenSearchRetrieverService`
- ✅ `VectorGlobalSearchChannel.isEnabled` 改动只决定**通道是否进入**；进入后仍走我方 PR4/PR5 强制注入的 `kb_id + security_level` filter 路径，权限语义零变化
- ✅ `DefaultIntentClassifier.loadIntentTreeFromDB` 加查询条件不接 KB 数据访问，权限无影响

## 测试

新增：
- `VectorGlobalSearchChannelTriggerTest` — 3 个用例锁住补充阈值边界（单意图 < 0.8 / 单意图 >= 0.8 / 多意图 < 0.8）

回归：
- [x] `ChannelFailFastPropagationTest` PASS
- [x] `VectorGlobalChannelFilterAlignmentTest` / `VectorGlobalSearchChannelKbMetadataReaderTest` PASS
- [x] `MultiChannelRetrievalEngine*Test` PASS
- [x] `OpenSearchRetrieverServiceFilterContractTest` PASS
- [x] `mvn clean install -DskipTests spotless:check` BUILD SUCCESS
- [x] 后端启动烟测：`SearchChannelProperties` 装配无错

## 实施计划

详见 [`docs/superpowers/plans/2026-04-29-intent-enabled-and-single-intent-supplement.md`](docs/superpowers/plans/2026-04-29-intent-enabled-and-single-intent-supplement.md)（plan 文件本地保留，不入此 PR）。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

如果 `gh` 未登录，把上述 PR title + body 落盘到 `PR_BODY_intent_supplement.md`，然后让 user 用 push 输出的 GitHub URL 手工开 PR。

- [ ] **Step 3: PR merge 后清理**

PR 合入 main 后：

```bash
cd "E:/AI Application/rag-knowledge-dev"
git checkout main
git pull --ff-only origin main
git worktree remove .worktrees/intent-supplement
git branch -D intent-supplement
rm -f PR_BODY_intent_supplement.md
git worktree list
```

Expected：worktree 与本地分支删除，主仓库 main HEAD 推进到包含此 PR 的 merge commit。

---

## Self-Review Notes

**Spec coverage check:**
- ✅ upstream `b794fd17` 4 个文件（properties / classifier / cache manager / channel）全部对应 task
- ✅ 新增测试覆盖 single-intent supplement threshold（Task 2 Step 1 三个边界用例）
- ✅ 已存在的 PR4/PR5 测试不应破坏（Task 2 Step 5 + Task 5 Step 2 验证）
- ✅ Cherry-pick 元信息保留：commit message 标明 upstream b794fd17
- ✅ 配置默认值与 upstream 一致：`singleIntentSupplementThreshold = 0.8`

**Placeholder scan:**
- 无 "TBD" / "implement later" / "适当处理"
- 所有代码块都给出可粘贴的具体内容
- 命令都是可执行形式

**Type consistency:**
- 字段全程统一为 `singleIntentSupplementThreshold`（驼峰）/ `single-intent-supplement-threshold`（kebab，仅 yaml 默认）
- 测试用 record `SubQuestionIntent(question, nodeScores)` 与 `NodeScore(node, score)` —— 与既有 `*FilterAlignmentTest` 一致
- VectorGlobalSearchChannel 的方法名 `isEnabled` —— 与我方代码一致（不是 `shouldExecute`）

**架构 / 权限合规审计表：**

| 改动点 | 权限/架构层级风险 | 审计结论 |
| --- | --- | --- |
| `SearchChannelProperties.singleIntentSupplementThreshold` | 配置字段加，不接业务 | ✅ 无风险 |
| `DefaultIntentClassifier.loadIntentTreeFromDB +.eq(enabled, 1)` | DB 查询条件加强 | ✅ 不接 KB 数据访问；意图节点本身不映射到 KB 权限 |
| `IntentTreeCacheManager.clearIntentTreeCache` 去 try-catch | Redis 异常处理风格 | ✅ 不接权限端口；异常上抛交由调用方处理（`QueryTermMappingAdminServiceImpl` 之类的入口已被 Spring `@Transactional` / 全局异常处理器兜住） |
| `VectorGlobalSearchChannel.isEnabled` 加补充阈值条件 | **关键审视点** | ✅ 只决定**通道是否进入**；进入后仍走 `DefaultMetadataFilterBuilder.build()` 强制注入 `kb_id + security_level` filter，PR5 c1+c2 fail-fast 行为不变；已通过 `ChannelFailFastPropagationTest` 等回归测试锁定 |
| `VectorGlobalSearchChannel` javadoc 删 2 行过时描述 | 文档措辞 | ✅ 无功能影响 |

**风险等级：低**。本计划应在 60 分钟内完成（含烟测 + PR）。
