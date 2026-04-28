# Answer Sources PR4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist `t_message.sources_json` at `onComplete` and deserialize it into `ConversationMessageVO.sources` on `listMessages`, completing the Answer Sources end-to-end data path; also backfill frontend mapping of `thinkingContent` / `thinkingDuration` that was missed in PR2/PR3.

**Architecture:** Spec docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md. Write path: `StreamChatEventHandler.onComplete` → `persistSourcesIfPresent(messageId)` → `ConversationMessageService.updateSourcesJson(messageId, json)` → `mapper.update(entity, lambdaUpdate)`. Read path: `ConversationMessageServiceImpl.listMessages` builder adds `.sources(deserializeSources(record.getSourcesJson()))`. Wiring delta adds `ConversationMessageService` to `StreamChatHandlerParams` + `StreamCallbackFactory` + handler constructor. Flag `rag.sources.enabled` stays `false` — PR4 is static-off, no user-visible change.

**Tech Stack:** Java 17 / Spring Boot 3.5.7 / MyBatis Plus 3.5.14 (`mapper.update(entity, Wrappers.lambdaUpdate(...))`) / Jackson (static `ObjectMapper`) / PostgreSQL / React 18 + TypeScript + Vite / Vitest + @testing-library/react.

**Subagent instructions (MUST appear in every task prompt when dispatched):**
> 若本 plan 或 spec 中声明的类路径、字段名、方法签名与实际代码冲突，**一律以代码为准**。动手前先 Read 真源确认锚点；测试断言只锁本 task 要落地的契约，不过度耦合 wrapper 内部实现细节或其他无关字段。

**Backend compile gate:** 每完成一个后端 task 且 commit 之前，必须跑：
```
mvn -pl bootstrap clean compile -q
```
`clean` 强制消除 stale bytecode（PR2 L2 教训）。不加 `clean` 的 incremental compile 可能瞒过错误 import。

**Frontend type gate:** 每完成一个前端 task 且 commit 之前，必须跑：
```
cd frontend && ./node_modules/.bin/tsc --noEmit
```
`npx tsc` 可能落到全局 tsc 并 "silently pass"（frontend CLAUDE.md 记录）。

**Branch:** `feature/answer-sources-pr4`（已从 main 拉，HEAD = PR4 spec commit `c2ca1b7`）。

**Commit message format:** `<type>(sources): <desc> [PR4]`，type ∈ `feat / test / chore / docs / fix`。

---

## File Structure

**Create**（新文件，5 个）：
- `resources/database/upgrade_v1.8_to_v1.9.sql` — 幂等迁移脚本
- `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceSourcesTest.java` — 读写双路径单测
- `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerPersistenceTest.java` — handler 落库 + 异常不阻塞单测

**Modify**（现有文件，10 个）：

后端（7）：
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationMessageDO.java` — 加 `sourcesJson` 字段
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/ConversationMessageVO.java` — 加 `sources` 字段
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ConversationMessageService.java` — 加 `updateSourcesJson` 方法
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java` — 实现 `updateSourcesJson` + 扩 `listMessages` mapping + 加 `@Slf4j`
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java` — 加 `conversationMessageService` 字段
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java` — 注入 + builder 链加一行
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java` — 加 field + 构造器 + `persistSourcesIfPresent` + onComplete 调用

SQL / 文档（3）：
- `resources/database/schema_pg.sql` — CREATE TABLE 加列（L158-169 附近）+ COMMENT 块加一行（L575-585 附近）
- `resources/database/full_schema_pg.sql` — CREATE TABLE 加列（L701 附近）+ 独立 COMMENT 块（L768 后）
- `CLAUDE.md` — Upgrade scripts 清单加一行

前端（2）：
- `frontend/src/services/sessionService.ts` — 加 `SourceCard` import + VO 接口补三字段
- `frontend/src/stores/chatStore.ts` — `selectSession` mapper 加三行

前端测试（1）：
- `frontend/src/stores/chatStore.test.ts` — 追加 `selectSession mapping` describe 块

---

## Task 1: SQL 三件套 + CLAUDE.md 清单

**Files:**
- Create: `resources/database/upgrade_v1.8_to_v1.9.sql`
- Modify: `resources/database/schema_pg.sql`（加列 + COMMENT 块）
- Modify: `resources/database/full_schema_pg.sql`（加列 + 独立 COMMENT 块）
- Modify: `CLAUDE.md`（根级 upgrade 脚本清单）

- [ ] **Step 1.1: 创建 upgrade_v1.8_to_v1.9.sql**

写入 `resources/database/upgrade_v1.8_to_v1.9.sql`：

```sql
-- 升级脚本：v1.8 → v1.9
-- 功能：为 t_message 增加 sources_json 列，用于持久化答案引用来源快照（Answer Sources 功能 PR4）
-- 设计文档：docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS sources_json TEXT;

COMMENT ON COLUMN t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
```

- [ ] **Step 1.2: 修改 schema_pg.sql 加列**

在 `CREATE TABLE t_message (` 块（约 L158 开始）内，紧跟 `thinking_duration` 列定义后面加一行 `sources_json TEXT,`。具体锚点：先 Read L158-169 确认当前字段顺序，再用 Edit 在 `thinking_duration` 之后插入。

- [ ] **Step 1.3: 修改 schema_pg.sql COMMENT 块加一行**

在 `-- t_message` COMMENT 块（约 L575-585）内，紧跟 `COMMENT ON COLUMN t_message.thinking_duration IS '深度思考耗时（毫秒）';`（L582 附近）后插入：

```sql
COMMENT ON COLUMN t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
```

- [ ] **Step 1.4: 修改 full_schema_pg.sql 加列**

在 `CREATE TABLE public.t_message (` 块（L701）内加列：先 Read L701-715 确认字段顺序，在 `thinking_duration` 列之后插入 `sources_json text,`（注意 full_schema_pg.sql 是 pg_dump 风格，类型小写 `text`，逗号结尾）。

- [ ] **Step 1.5: 修改 full_schema_pg.sql 加独立 COMMENT 块**

在 `COMMENT ON COLUMN public.t_message.thinking_duration IS '深度思考耗时（毫秒）';`（L768 附近）后插入独立块（仿 full_schema_pg.sql 现有格式的注释头 + COMMENT 语句）：

```sql


--
-- Name: COLUMN t_message.sources_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
```

**硬约束**：COMMENT 必须是独立块，不可内联在 `CREATE TABLE` 内（CLAUDE.md 规范）。先 Read L765-785 确认现有 t_message COMMENT 块的精确注释头格式再照抄。

- [ ] **Step 1.6: 修改根 CLAUDE.md upgrade 脚本清单**

找到 `Upgrade scripts in \`resources/database/\`:` 下的列表（用 Grep `upgrade_v1.7_to_v1.8.sql` 定位），在其后加一行：

```
- `upgrade_v1.8_to_v1.9.sql` — 为 `t_message` 增加 `sources_json` 列（Answer Sources PR4 持久化）
```

- [ ] **Step 1.7: 本地手测迁移幂等**

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.8_to_v1.9.sql
# Expected: ALTER TABLE + COMMENT 两行 NOTICE 或 NOTICE 静默；无 ERROR
docker exec postgres psql -U postgres -d ragent -c "\d+ t_message" | grep sources_json
# Expected: "sources_json | text | | | | | plain |  |" 且 Description 含 '答案引用来源快照'
# 再跑一次
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.8_to_v1.9.sql
# Expected: 第二次同样无 ERROR（ADD COLUMN IF NOT EXISTS 静默成功；COMMENT 可重复执行）
```

- [ ] **Step 1.8: 提交 SQL + 清单 commit**

```bash
git add resources/database/upgrade_v1.8_to_v1.9.sql \
        resources/database/schema_pg.sql \
        resources/database/full_schema_pg.sql \
        CLAUDE.md
git commit -m "chore(sources): upgrade_v1.8_to_v1.9.sql + schema dual-write + CLAUDE.md list [PR4]"
```

---

## Task 2: DO + VO 字段扩展

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationMessageDO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/ConversationMessageVO.java`

- [ ] **Step 2.1: 扩 ConversationMessageDO**

在 `ConversationMessageDO.java` 的 `thinkingDuration` 字段定义之后（L83 附近），加：

```java
    /**
     * 答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用
     */
    @TableField("sources_json")
    private String sourcesJson;
```

`@TableField` import 已在文件顶部（L23），无需新增。**不**加 `updateStrategy`（用默认 `NOT_NULL` 语义，见 spec §2.1）。

- [ ] **Step 2.2: 扩 ConversationMessageVO**

在 `ConversationMessageVO.java` 顶部 import 区域加两行：

```java
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import java.util.List;
```

在 `createTime` 字段之前（或根据字段分组就近）加：

```java
    /**
     * 答案引用来源快照，历史消息从 t_message.sources_json 反序列化；NULL 表示无引用或反序列化失败
     */
    private List<SourceCard> sources;
```

- [ ] **Step 2.3: 跑 compile gate**

```bash
mvn -pl bootstrap clean compile -q
```

Expected: BUILD SUCCESS，无 error。

- [ ] **Step 2.4: 提交 DO/VO commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dao/entity/ConversationMessageDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/vo/ConversationMessageVO.java
git commit -m "feat(sources): add sourcesJson to DO + sources to VO [PR4]"
```

---

## Task 3: Service 接口方法 + stub 实现

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ConversationMessageService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java`

> **说明**：先立 stub 让 compile 通过，Task 4 的 failing test 会暴露 stub 的 "未实际调用 mapper" 空缺。

- [ ] **Step 3.1: 扩 ConversationMessageService 接口**

在 `ConversationMessageService.java` 末尾（`addMessageSummary` 之后）加：

```java
    /**
     * 更新指定消息的 sources_json 列。messageId blank 时 no-op。
     * 其他异常由调用方 try/catch 降级处理。
     *
     * <p><b>契约限制</b>：当前实现基于 {@code mapper.update(entity, lambdaUpdate)} +
     * MyBatis Plus 默认 {@code NOT_NULL} 字段策略。传入 {@code json=null} 不会清空列，
     * 而会被 MP 视作"跳过该字段"，UPDATE 实际为 no-op。调用方保证 json 非空。
     *
     * @param messageId 消息 ID
     * @param json      SourceCard[] 的 JSON 序列化字符串；调用方保证非空
     */
    void updateSourcesJson(String messageId, String json);
```

- [ ] **Step 3.2: 加 stub 实现**

在 `ConversationMessageServiceImpl.java` 末尾加（紧跟 `addMessageSummary` 实现之后）：

```java
    @Override
    public void updateSourcesJson(String messageId, String json) {
        // stub, will be implemented by a later task guided by failing test
    }
```

- [ ] **Step 3.3: 跑 compile gate**

```bash
mvn -pl bootstrap clean compile -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 3.4: 提交 interface + stub commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/ConversationMessageService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java
git commit -m "chore(sources): add updateSourcesJson interface + stub [PR4]"
```

---

## Task 4: TDD `updateSourcesJson` 实现（写路径）

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceSourcesTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java`（替换 stub 为真实实现）

- [ ] **Step 4.1: 写 failing test**

创建 `ConversationMessageServiceSourcesTest.java`：

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

package com.knowledgebase.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.knowledgebase.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.knowledgebase.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.knowledgebase.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.knowledgebase.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.knowledgebase.ai.ragent.rag.service.MessageFeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ConversationMessageServiceSourcesTest {

    @Mock ConversationMessageMapper conversationMessageMapper;
    @Mock ConversationSummaryMapper conversationSummaryMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock MessageFeedbackService feedbackService;

    private ConversationMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConversationMessageServiceImpl(
                conversationMessageMapper,
                conversationSummaryMapper,
                conversationMapper,
                feedbackService
        );
    }

    @Test
    void updateSourcesJson_withValidInputs_updatesOnlySourcesJsonColumnById() {
        String messageId = "msg-001";
        String json = "[{\"index\":1,\"docId\":\"d1\"}]";

        service.updateSourcesJson(messageId, json);

        ArgumentCaptor<ConversationMessageDO> entityCaptor =
                ArgumentCaptor.forClass(ConversationMessageDO.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<ConversationMessageDO>> wrapperCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(conversationMessageMapper, times(1))
                .update(entityCaptor.capture(), wrapperCaptor.capture());

        // entity 只带 sourcesJson
        ConversationMessageDO captured = entityCaptor.getValue();
        assertEquals(json, captured.getSourcesJson());
        assertNull(captured.getId(), "id should not be set on update entity");
        assertNull(captured.getContent(), "content should not be touched");
        assertNull(captured.getRole(), "role should not be touched");

        // wrapper 形状：含 id 谓词（MP 生成占位符，不含 messageId 字面量）
        Wrapper<ConversationMessageDO> wrapper = wrapperCaptor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("id ="),
                "wrapper should contain 'id =' predicate, got: " + sqlSegment);

        // wrapper 绑定值：实际绑定的 value 是 messageId
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.values().contains(messageId),
                "wrapper params should bind messageId=" + messageId + ", got: " + params);
    }

    @Test
    void updateSourcesJson_withBlankMessageId_isNoOp() {
        service.updateSourcesJson("", "[]");
        service.updateSourcesJson("  ", "[]");
        service.updateSourcesJson(null, "[]");

        verifyNoInteractions(conversationMessageMapper);
    }
}
```

- [ ] **Step 4.2: 跑测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=ConversationMessageServiceSourcesTest -q
```

Expected: `updateSourcesJson_withValidInputs_updatesOnlySourcesJsonColumnById` 失败（stub 不调 mapper → `verify(..., times(1)).update(...)` 不满足）。`updateSourcesJson_withBlankMessageId_isNoOp` 通过（stub 也没调）。

- [ ] **Step 4.3: 实现 updateSourcesJson**

替换 `ConversationMessageServiceImpl.updateSourcesJson` 的 stub 为真实实现。先在 `ConversationMessageServiceImpl.java` 顶部 import 区域加（如已有则跳过）：

```java
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
```

`Wrappers` 可能已在文件顶部有 import（L22），先 Read 确认；`StrUtil` 已在（L21）。然后替换方法体：

```java
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
```

- [ ] **Step 4.4: 跑测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=ConversationMessageServiceSourcesTest -q
```

Expected: 2/2 通过。

- [ ] **Step 4.5: 跑 compile gate**

```bash
mvn -pl bootstrap clean compile -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 4.6: 两次 commit（test 先、impl 后）**

由于 Step 4.1 和 Step 4.3 已 sequentially 完成，现在合并成两个 commit：先把测试 commit 作为 "failing test scaffolding"，再 commit impl。**先 stash impl 改动，commit test，再 restore**：

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java
git stash push -m "pr4-t4-impl"
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceSourcesTest.java
git commit -m "test(sources): ConversationMessageServiceSourcesTest write-path scaffolding [PR4]"
git stash pop
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java
git commit -m "feat(sources): implement updateSourcesJson via lambdaUpdate [PR4]"
```

> 若本次执行模式的 subagent 不便 stash，可直接一次性 commit 成 `feat(sources): updateSourcesJson impl + test [PR4]`，记录到 plan 执行日志即可。TDD 的"test red → impl green"顺序在 Step 4.2/4.4 已验证。

---

## Task 5: TDD `listMessages` sources mapping（读路径）

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceSourcesTest.java`（追加用例）
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java`（加 `deserializeSources` helper + VO builder 链扩一行 + `@Slf4j`）

- [ ] **Step 5.1: 追加读路径 failing tests**

在 `ConversationMessageServiceSourcesTest.java` 的 import 区域加：

```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.knowledgebase.ai.ragent.rag.dao.entity.ConversationDO;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import com.knowledgebase.ai.ragent.rag.dto.SourceChunk;
import com.knowledgebase.ai.ragent.rag.enums.ConversationMessageOrder;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
```

在类里追加（作为后续 3 个 test method 的共享工厂方法）：

```java
    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();
    private static final TypeReference<List<SourceCard>> SOURCES_TYPE = new TypeReference<>() {};

    private ConversationDO stubConversation() {
        ConversationDO conv = new ConversationDO();
        conv.setConversationId("conv-1");
        conv.setUserId("user-1");
        conv.setDeleted(0);
        return conv;
    }

    private ConversationMessageDO assistantRecord(String id, String sourcesJson) {
        return ConversationMessageDO.builder()
                .id(id)
                .conversationId("conv-1")
                .userId("user-1")
                .role("assistant")
                .content("answer")
                .sourcesJson(sourcesJson)
                .createTime(new Date())
                .deleted(0)
                .build();
    }

    private SourceCard sampleCard() {
        return SourceCard.builder()
                .index(1).docId("d1").docName("D1").kbId("kb").topScore(0.9f)
                .chunks(List.of(SourceChunk.builder()
                        .chunkId("c1").chunkIndex(0).preview("p").score(0.85f).build()))
                .build();
    }

    private void stubListQueries(ConversationMessageDO record) {
        when(conversationMapper.selectOne(any())).thenReturn(stubConversation());
        when(conversationMessageMapper.selectList(any())).thenReturn(List.of(record));
        when(feedbackService.getUserVotes(any(), any())).thenReturn(java.util.Map.of());
    }

    @Test
    void listMessages_withValidSourcesJson_deserializesToSourceCardList() throws Exception {
        SourceCard card = sampleCard();
        String json = TEST_MAPPER.writeValueAsString(List.of(card));
        stubListQueries(assistantRecord("msg-1", json));

        List<ConversationMessageVO> result = service.listMessages(
                "conv-1", "user-1", null, ConversationMessageOrder.ASC);

        assertEquals(1, result.size());
        List<SourceCard> sources = result.get(0).getSources();
        assertNotNull(sources, "sources should be populated");
        assertEquals(1, sources.size());
        assertEquals(card.getIndex(), sources.get(0).getIndex());
        assertEquals(card.getDocId(), sources.get(0).getDocId());
        assertEquals(card.getDocName(), sources.get(0).getDocName());
    }

    @Test
    void listMessages_withMalformedJson_returnsNullSourcesNotThrow() {
        stubListQueries(assistantRecord("msg-2", "not-a-json"));

        List<ConversationMessageVO> result = service.listMessages(
                "conv-1", "user-1", null, ConversationMessageOrder.ASC);

        assertEquals(1, result.size());
        assertNull(result.get(0).getSources(), "malformed json should degrade to null, not throw");
    }

    @Test
    void listMessages_withNullSourcesJson_returnsNullSources() {
        stubListQueries(assistantRecord("msg-3", null));

        List<ConversationMessageVO> result = service.listMessages(
                "conv-1", "user-1", null, ConversationMessageOrder.ASC);

        assertEquals(1, result.size());
        assertNull(result.get(0).getSources());
    }
```

- [ ] **Step 5.2: 跑测试确认 3 个新用例失败**

```bash
mvn -pl bootstrap test -Dtest=ConversationMessageServiceSourcesTest -q
```

Expected: 前 2 个（write-path）通过；新加的 `listMessages_with*` 3 个失败——`result.get(0).getSources()` 始终返回 null（`listMessages` 当前 VO builder 未映射 sources），失败现象是：
- `withValidSourcesJson` 在 `assertNotNull(sources)` 断言失败
- `withMalformedJson` 和 `withNullSourcesJson` 本来期望 null，会"假阳性通过"——但前者只要 `listMessages` 不抛异常即可通过

实际失败数量可能是 1 而非 3。**这是 TDD 可接受场景**：核心断言（valid sources → 非 null）真红。

- [ ] **Step 5.3: 实现 listMessages sources 映射**

在 `ConversationMessageServiceImpl.java`：

1. 类级注解加 `@Slf4j`（如未加）——Read 现状确认，若已有则跳过；import `lombok.extern.slf4j.Slf4j`。

2. 顶部 import 区域加（如已有则跳过）：

```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
```

3. 类内加两个静态常量（紧跟类声明后、字段前）：

```java
    private static final ObjectMapper SOURCES_MAPPER = new ObjectMapper();
    private static final TypeReference<List<SourceCard>> SOURCES_TYPE = new TypeReference<>() {};
```

4. `listMessages` 内 VO builder 链（当前 L100-109 附近）加一行 `.sources(deserializeSources(record.getSourcesJson()))`：

```java
            ConversationMessageVO vo = ConversationMessageVO.builder()
                    .id(String.valueOf(record.getId()))
                    .conversationId(record.getConversationId())
                    .role(record.getRole())
                    .content(record.getContent())
                    .thinkingContent(record.getThinkingContent())
                    .thinkingDuration(record.getThinkingDuration())
                    .sources(deserializeSources(record.getSourcesJson()))  // 新
                    .vote(votesByMessageId.get(record.getId()))
                    .createTime(record.getCreateTime())
                    .build();
```

5. 加私有 helper（类末尾）：

```java
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

- [ ] **Step 5.4: 跑测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=ConversationMessageServiceSourcesTest -q
```

Expected: 5/5 通过。

- [ ] **Step 5.5: 跑 compile gate**

```bash
mvn -pl bootstrap clean compile -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 5.6: 两个 commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java
git stash push -m "pr4-t5-impl"
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceSourcesTest.java
git commit -m "test(sources): ConversationMessageServiceSourcesTest read-path scaffolding [PR4]"
git stash pop
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/ConversationMessageServiceImpl.java
git commit -m "feat(sources): listMessages deserialize sources_json to VO [PR4]"
```

---

## Task 6: Wiring delta — Params + Factory + Handler 构造器

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`（只加 field 和 constructor init，**不**加 `persistSourcesIfPresent`，留 Task 7）

> **说明**：此 task 纯 wiring，无新行为。compile 通过即可；Task 7 的 failing test 会暴露 `persistSourcesIfPresent` 的缺失。

- [ ] **Step 6.1: 扩 StreamChatHandlerParams**

顶部 import 区域加：

```java
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
```

字段列表（紧跟 `memoryService` 那行，约 L64 之后）加：

```java
    /**
     * 会话消息服务，用于持久化 sources_json
     */
    private final ConversationMessageService conversationMessageService;
```

**不**加 `@NonNull`——sources 落库是可选路径。

- [ ] **Step 6.2: 扩 StreamCallbackFactory**

顶部 import 区域加：

```java
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
```

字段列表（紧跟 `memoryService` 那行，约 L45 之后）加：

```java
    private final ConversationMessageService conversationMessageService;
```

`createChatEventHandler` 方法内 builder 链（约 L67 附近 `.memoryService(memoryService)` 之后）加：

```java
                .conversationMessageService(conversationMessageService)
```

- [ ] **Step 6.3: 扩 StreamChatEventHandler constructor**

顶部 import 区域加：

```java
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
```

字段列表（紧跟 `memoryService` 那行，约 L63）加：

```java
    private final ConversationMessageService conversationMessageService;
```

构造器内（紧跟 `this.memoryService = params.getMemoryService();` 那行，约 L89）加：

```java
        this.conversationMessageService = params.getConversationMessageService();
```

**不**加 `persistSourcesIfPresent` 方法，**不**在 `onComplete` 调用——Task 7 才做。

- [ ] **Step 6.4: 跑 compile gate**

```bash
mvn -pl bootstrap clean compile -q
```

Expected: BUILD SUCCESS。

> 若有测试类（如 `StreamChatEventHandlerCitationTest`）构造 Params 时走 builder，新增 field 不是必填（`@Builder` 允许未设置的 final 字段为 null），因此既有测试不用改。若 mvn test 全量跑存在红，先 Read 相关 setup 判断是否必须补一行 `.conversationMessageService(mock)`。

- [ ] **Step 6.5: 可选快速验证既有 handler 测试仍绿**

```bash
mvn -pl bootstrap test -Dtest=StreamChatEventHandlerCitationTest -q
```

Expected: 既有 4 用例全通（Task 6 纯 wiring，无行为变化）。

- [ ] **Step 6.6: Commit wiring**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java
git commit -m "chore(sources): wire ConversationMessageService into handler via Params + Factory [PR4]"
```

---

## Task 7: TDD `persistSourcesIfPresent` + `onComplete` 集成

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerPersistenceTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`（加 `SOURCES_MAPPER` 静态字段 + `persistSourcesIfPresent` 私有方法 + `onComplete` 调用）

- [ ] **Step 7.1: 写 failing test**

创建 `StreamChatEventHandlerPersistenceTest.java`。**结构参照 `StreamChatEventHandlerCitationTest` 的 setUp / tearDown 模式（L55-108）**，差异：多一个 `@Mock ConversationMessageService conversationMessageService` + builder 链加 `.conversationMessageService(conversationMessageService)`。

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

package com.knowledgebase.ai.ragent.rag.service.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.framework.trace.RagTraceContext;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.rag.config.RAGConfigProperties;
import com.knowledgebase.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import com.knowledgebase.ai.ragent.rag.dto.SourceChunk;
import com.knowledgebase.ai.ragent.rag.service.ConversationGroupService;
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
import com.knowledgebase.ai.ragent.rag.service.RagEvaluationService;
import com.knowledgebase.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamChatEventHandlerPersistenceTest {

    @Mock SseEmitter emitter;
    @Mock ConversationMemoryService memoryService;
    @Mock ConversationGroupService conversationGroupService;
    @Mock StreamTaskManager taskManager;
    @Mock RagEvaluationService evaluationService;
    @Mock RagTraceRecordService traceRecordService;
    @Mock SuggestedQuestionsService suggestedQuestionsService;
    @Mock ThreadPoolTaskExecutor suggestedQuestionsExecutor;
    @Mock RAGConfigProperties ragConfigProperties;
    @Mock ConversationMessageService conversationMessageService;

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();
    private static final TypeReference<List<SourceCard>> SOURCES_TYPE = new TypeReference<>() {};

    private SourceCardsHolder holder;

    @BeforeEach
    void setUp() {
        RagTraceContext.setTraceId("test-trace-id");
        holder = new SourceCardsHolder();
        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("msg-1");
        when(ragConfigProperties.getSuggestionsEnabled()).thenReturn(false);
        when(taskManager.isCancelled(anyString())).thenReturn(false);
        when(conversationGroupService.findConversation(anyString(), any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        RagTraceContext.clear();
    }

    private StreamChatEventHandler newHandler() {
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("c-1")
                .taskId("t-1")
                .modelProperties(new AIModelProperties())
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .evaluationService(evaluationService)
                .traceRecordService(traceRecordService)
                .suggestedQuestionsService(suggestedQuestionsService)
                .suggestedQuestionsExecutor(suggestedQuestionsExecutor)
                .ragConfigProperties(ragConfigProperties)
                .cardsHolder(holder)
                .conversationMessageService(conversationMessageService)
                .build();
        return new StreamChatEventHandler(params);
    }

    private SourceCard card(int n) {
        return SourceCard.builder()
                .index(n).docId("d" + n).docName("D" + n).kbId("kb").topScore(0.9f)
                .chunks(List.of(SourceChunk.builder()
                        .chunkId("c").chunkIndex(0).preview("p").score(0.8f).build()))
                .build();
    }

    @Test
    void onComplete_whenCardsHolderEmpty_thenUpdateSourcesJsonNeverCalled() {
        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer");

        handler.onComplete();

        verify(conversationMessageService, never()).updateSourcesJson(anyString(), anyString());
    }

    @Test
    void onComplete_whenCardsHolderSet_thenUpdateSourcesJsonCalledOnceWithEquivalentCards() throws Exception {
        holder.trySet(List.of(card(1), card(2)));

        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer [^1]");

        handler.onComplete();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationMessageService, times(1))
                .updateSourcesJson(eq("msg-1"), jsonCaptor.capture());

        List<SourceCard> roundtrip = TEST_MAPPER.readValue(jsonCaptor.getValue(), SOURCES_TYPE);
        assertEquals(2, roundtrip.size());
        assertEquals(1, roundtrip.get(0).getIndex());
        assertEquals("d1", roundtrip.get(0).getDocId());
        assertEquals(2, roundtrip.get(1).getIndex());
    }

    @Test
    void onComplete_whenMessageIdBlank_thenUpdateSourcesJsonNeverCalled() {
        holder.trySet(List.of(card(1)));
        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("");

        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer");

        handler.onComplete();

        verify(conversationMessageService, never()).updateSourcesJson(anyString(), anyString());
    }

    @Test
    void onComplete_whenUpdateSourcesJsonThrows_thenSubsequentSegmentsStillRun() {
        holder.trySet(List.of(card(1)));
        doThrow(new RuntimeException("db down"))
                .when(conversationMessageService).updateSourcesJson(anyString(), anyString());

        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer [^1]");

        handler.onComplete();

        // persist 被调一次（且抛了），但 onComplete 后续段仍执行：
        verify(conversationMessageService, times(1)).updateSourcesJson(anyString(), anyString());
        // mergeCitationStatsIntoTrace 仍跑（cardsHolder 非空 + traceRecordService 非空）
        verify(traceRecordService, times(1)).mergeRunExtraData(eq("test-trace-id"), anyMap());
        // FINISH 事件仍发送
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        // 注意：emitter.send 调用次数取决于 META / MESSAGE 分片等事件总数；
        // 此处用 atLeastOnce 更稳。若本断言脆化，改为：
        // verify(emitter, org.mockito.Mockito.atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
```

> **子代理提醒**：上面最后一个用例的 `emitter.send` times 断言可能在 mock setup 下脆化。若运行时发现 send 调用次数 ≠ 1（由于 `onContent("answer [^1]")` 触发 messageChunkSize 分片），**改为 `atLeastOnce()`**；主旨是验证"FINISH 路径被触达"而非"只发一次"。先 Read `StreamChatEventHandler.sendChunked` 和构造期 `resolveMessageChunkSize` 逻辑确认分片行为再决定。

- [ ] **Step 7.2: 跑测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=StreamChatEventHandlerPersistenceTest -q
```

Expected: 4 用例中至少前两个失败——`onComplete` 当前**没调** `conversationMessageService.updateSourcesJson`。

- [ ] **Step 7.3: 实现 persistSourcesIfPresent**

在 `StreamChatEventHandler.java`：

顶部 import 区域加：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
```

类内加静态常量（紧跟既有 `private static final String TYPE_THINK = "think";` 之后）：

```java
    private static final ObjectMapper SOURCES_MAPPER = new ObjectMapper();
```

`onComplete` 方法（当前约 L181-220）内，紧跟 `String messageId = memoryService.append(...)` 那行（约 L185-186）之后插入调用：

```java
        persistSourcesIfPresent(messageId);
```

位置约束：**必须在 `updateTraceTokenUsage()` 之前**（spec § 2.7）。

类末尾（紧跟 `mergeCitationStatsIntoTrace` 方法之后、`saveEvaluationRecord` 之前，或任何便于阅读的位置）加私有方法：

```java
    /**
     * 在 onComplete 中持久化 sources_json。holder 空或 messageId blank 时早返回。
     * 任何异常仅 log.warn，不 rethrow，避免阻塞 onComplete 后续段（token 埋点 / citation merge
     * / evaluation / FINISH / SUGGESTIONS / DONE）。
     */
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

- [ ] **Step 7.4: 跑测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=StreamChatEventHandlerPersistenceTest -q
```

Expected: 4/4 通过。如用例 4 的 `emitter.send` 断言失败，按 Step 7.1 提醒改为 `atLeastOnce()`。

- [ ] **Step 7.5: 跑 compile gate + 确认既有测试不回归**

```bash
mvn -pl bootstrap clean compile -q
mvn -pl bootstrap test -Dtest=StreamChatEventHandlerCitationTest -q
```

Expected: 编译通过；既有 4 用例仍绿。

- [ ] **Step 7.6: 两个 commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java
git stash push -m "pr4-t7-impl"
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerPersistenceTest.java
git commit -m "test(sources): StreamChatEventHandlerPersistenceTest scaffolding [PR4]"
git stash pop
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java
git commit -m "feat(sources): persistSourcesIfPresent in onComplete + exception degrades [PR4]"
```

---

## Task 8: 前端 sessionService.ts 接口扩展

**Files:**
- Modify: `frontend/src/services/sessionService.ts`

- [ ] **Step 8.1: 加 type import + 三个 optional 字段**

在 `frontend/src/services/sessionService.ts` 顶部 import 后加：

```typescript
import type { SourceCard } from "@/types";
```

在 `ConversationMessageVO` 接口内（当前 L10-17）扩字段：

```typescript
export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  vote: number | null;
  createTime?: string;
  thinkingContent?: string;
  thinkingDuration?: number;
  sources?: SourceCard[];
}
```

- [ ] **Step 8.2: 跑类型 gate**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit
```

Expected: exit 0，无 type error。

- [ ] **Step 8.3: Commit**

```bash
git add frontend/src/services/sessionService.ts
git commit -m "feat(sources): sessionService VO adds thinking + sources optional fields [PR4]"
```

---

## Task 9: TDD `chatStore.selectSession` 映射补三字段

**Files:**
- Modify: `frontend/src/stores/chatStore.test.ts`（追加 describe 块）
- Modify: `frontend/src/stores/chatStore.ts`（selectSession mapper 加三行）

- [ ] **Step 9.1: 在 chatStore.test.ts 追加 failing tests**

先 Read 现有 `chatStore.test.ts` 顶部 30 行确认 `vi.mock("@/services/sessionService", ...)` 的 setup 模式（现有测试覆盖 onSources，同一 service mock 可复用）。若 mock 尚未覆盖 `listMessages`，需在测试文件顶部补 mock。

在文件末尾追加：

```typescript
import { listMessages } from "@/services/sessionService";

vi.mock("@/services/sessionService", async (importActual) => {
  const actual = await importActual<typeof import("@/services/sessionService")>();
  return {
    ...actual,
    listMessages: vi.fn(),
  };
});

describe("selectSession mapping", () => {
  beforeEach(() => {
    vi.mocked(listMessages).mockReset();
    resetStore();
  });

  it("maps thinkingContent / thinkingDuration / sources from VO to Message", async () => {
    const card: SourceCard = {
      index: 1, docId: "d1", docName: "doc.pdf", kbId: "kb1",
      topScore: 0.9,
      chunks: [{ chunkId: "c1", chunkIndex: 0, preview: "hi", score: 0.9 }]
    };
    vi.mocked(listMessages).mockResolvedValue([
      {
        id: "m1",
        conversationId: "c_test",
        role: "assistant",
        content: "answer",
        vote: null,
        thinkingContent: "thinking...",
        thinkingDuration: 3,
        sources: [card]
      }
    ]);

    useChatStore.setState((s) => ({ ...s, activeKbId: null }));
    await useChatStore.getState().selectSession("c_test");

    const messages = useChatStore.getState().messages;
    expect(messages).toHaveLength(1);
    expect(messages[0].thinking).toBe("thinking...");
    expect(messages[0].thinkingDuration).toBe(3);
    expect(messages[0].sources).toEqual([card]);
  });

  it("tolerates missing thinking/sources fields (undefined)", async () => {
    vi.mocked(listMessages).mockResolvedValue([
      {
        id: "m1",
        conversationId: "c_test",
        role: "assistant",
        content: "answer",
        vote: null
        // 无 thinkingContent / thinkingDuration / sources
      }
    ]);

    useChatStore.setState((s) => ({ ...s, activeKbId: null }));
    await useChatStore.getState().selectSession("c_test");

    const messages = useChatStore.getState().messages;
    expect(messages).toHaveLength(1);
    expect(messages[0].thinking).toBeUndefined();
    expect(messages[0].thinkingDuration).toBeUndefined();
    expect(messages[0].sources).toBeUndefined();
  });
});
```

> **子代理提醒**：若 `chatStore.test.ts` 已有 `vi.mock("@/services/sessionService", ...)` setup，**不要重复 mock**。先 Read 整个文件确认现有 mock 结构，按需 patch。`resetStore` / `buildSourcesPayload` 等 helpers 已在文件顶部定义，直接复用。

- [ ] **Step 9.2: 跑测试确认失败**

```bash
cd frontend && npm run test -- chatStore.test.ts
```

Expected: 新增 2 用例中的第 1 个失败——`messages[0].thinking` / `.thinkingDuration` / `.sources` 都是 undefined（`selectSession` mapper 还没带这三字段）。第 2 个"容忍 undefined"会假阳性通过。

- [ ] **Step 9.3: 实现 selectSession mapper 补三字段**

在 `frontend/src/stores/chatStore.ts` 的 `selectSession` mapper（当前 L406-413）加三行：

```typescript
      const mapped: Message[] = data.map((item) => ({
        id: String(item.id),
        role: item.role === "assistant" ? "assistant" : "user",
        content: item.content,
        createdAt: item.createTime,
        feedback: mapVoteToFeedback(item.vote),
        status: "done",
        thinking: item.thinkingContent,
        thinkingDuration: item.thinkingDuration,
        sources: item.sources,
      }));
```

- [ ] **Step 9.4: 跑测试确认通过**

```bash
cd frontend && npm run test -- chatStore.test.ts
```

Expected: 现有所有用例 + 新增 2 用例全通。

- [ ] **Step 9.5: 跑类型 gate**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit
```

Expected: exit 0。

- [ ] **Step 9.6: 两个 commit**

```bash
git add frontend/src/stores/chatStore.ts
git stash push -m "pr4-t9-impl"
git add frontend/src/stores/chatStore.test.ts
git commit -m "test(sources): chatStore selectSession mapping scaffolding [PR4]"
git stash pop
git add frontend/src/stores/chatStore.ts
git commit -m "feat(sources): selectSession maps thinking + sources to Message [PR4]"
```

---

## Task 10: 综合验证与 dev_log

**Files:**
- Read 所有 PR4 改动的最终 commit 历史
- Optional: 写 `log/dev_log/2026-04-22-answer-sources-pr4.md`（可留到 PR 合并后再补）

- [ ] **Step 10.1: 全量后端测试**

```bash
mvn -pl bootstrap test -Dtest='ConversationMessageServiceSourcesTest,StreamChatEventHandlerPersistenceTest,StreamChatEventHandlerCitationTest,RAGPromptServiceCitationTest,CitationStatsCollectorTest,RAGChatServiceImplSourcesTest' -q
```

Expected: 全绿。基线失败项（Milvus / pgvector / seeded KB data，根 CLAUDE.md 记录的 10 个）不算回归。

- [ ] **Step 10.2: 全量前端测试**

```bash
cd frontend && npm run test
```

Expected: 全绿（含新加的 2 用例）。

- [ ] **Step 10.3: 前端类型 + 构建 gate**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit && npm run build
```

Expected: type check exit 0；vite build 成功。

- [ ] **Step 10.4: Spotless 检查**

```bash
mvn -pl bootstrap spotless:check
```

Expected: BUILD SUCCESS。若失败：`mvn -pl bootstrap spotless:apply` 修格式，commit `chore(sources): spotless apply [PR4]`。

- [ ] **Step 10.5: 迁移脚本幂等手测（复核）**

已在 Step 1.7 初次验证过；在 push 前再跑一次冒烟：

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.8_to_v1.9.sql
docker exec postgres psql -U postgres -d ragent -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='t_message' AND column_name='sources_json';"
```

Expected: 列存在且类型 `text`。

- [ ] **Step 10.6: 可选 — flag on 冒烟**

```bash
# 修改 bootstrap/src/main/resources/application.yaml:
# rag:
#   sources:
#     enabled: true
# 启动后端，浏览器新开 KB 问题
```

Expected：
- 新问题答案含 `[^1][^2]` 角标 + `<Sources />` 卡片区
- 刷新页面 → sources 持久化回来、UI 再次渲染（PR3 刷新丢失问题修复）
- 历史消息深度思考字段正确显示

冒烟完成后**把 flag 改回 false** + 不 commit（flag 翻 true 是 PR5 动作）。

- [ ] **Step 10.7: 检查最终 commit 链**

```bash
git log main..HEAD --oneline
```

Expected: 约 10-14 个 commit，全部 `[PR4]` 标签。

- [ ] **Step 10.8: 推送分支 + 开 PR（**仅在所有 gate 通过**）**

```bash
git push -u origin feature/answer-sources-pr4
gh pr create --base main --title "feat(sources): PR4 persist sources_json + VO mapping catch-up" --body "$(cat <<'EOF'
## Summary
- PR4 for Answer Sources: persist sources_json to t_message + thread VO.sources through listMessages + fix frontend's missing thinkingContent/Duration mapping
- Feature flag `rag.sources.enabled=false` unchanged — zero user-visible change
- Jackson-only serialization (static ObjectMapper); persistence failures degrade to log.warn without blocking FINISH/merge/suggestions/DONE chain
- Migration upgrade_v1.8_to_v1.9.sql idempotent (ADD COLUMN IF NOT EXISTS); dual schema write (schema_pg.sql + full_schema_pg.sql with independent COMMENT blocks)

## Test plan
- [x] Backend unit: ConversationMessageServiceSourcesTest (5 cases) + StreamChatEventHandlerPersistenceTest (4 cases) — all green
- [x] Frontend unit: chatStore.test.ts selectSession mapping — 2 new cases green
- [x] Type check: `./node_modules/.bin/tsc --noEmit` exit 0
- [x] Spotless: `mvn -pl bootstrap spotless:check` BUILD SUCCESS
- [x] Migration: `upgrade_v1.8_to_v1.9.sql` runs cleanly + idempotent (verified twice)
- [ ] Manual smoke (flag on, not committed): answer streams with [^n], refresh page → sources persist and re-render; thinking 字段在已有会话也正确映射

## Links
- Spec: docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md
- Implementation plan: docs/superpowers/plans/2026-04-22-answer-sources-pr4-implementation.md
- PR3 dev_log: log/dev_log/2026-04-22-answer-sources-pr3.md
- v1 design: docs/superpowers/specs/2026-04-17-answer-sources-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Not-in-PR4 回顾（执行中如见诱惑，一律不做）

- 不动 `MessageItem` / `MarkdownRenderer` / `Sources` / `CitationBadge` / `remarkCitations`（PR3 已就绪）
- 不收 PR3 known follow-ups N-3/N-4/N-5/N-6（留 PR5）
- 不改 `updateTraceTokenUsage` overwrite 写法（SRC-9 backlog）
- 不在 `buildCompletionPayloadOnCancel`（handler L140-148）加 persist 调用（v1 spec 明确"异常中断不落库"）
- 不翻 `rag.sources.enabled=true`（PR5 独立）
- 不补 `onSuggestions streamingMessageId guard`（SRC-4）
- 不清 handler 其他 ThreadLocal 读取（SRC-3）
- 不改 Milvus/Pg retriever 元数据回填（SRC-1）
