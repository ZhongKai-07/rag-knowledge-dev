# Collateral MCP MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改 RAG 主链路、不重构权限的前提下，跑通 Collateral 字段筛查的 MCP 链路：COB / SOP 走普通 RAG，Collateral KB 命中 `collateral_field_lookup` MCP intent 后由本地 executor 用静态 seed 数据返回 `Part 1 + Part 2` 答案，配合四档（A/B/B'/C/D）槽位分流。

**Architecture:** 新增本地 `MCPToolExecutor` 实现 `CollateralFieldLookupMCPExecutor`，由 Spring 自动注册到 `DefaultMCPToolRegistry`；executor 内部委托 `CollateralFieldLookupSlotRouter` 做槽位分流（C/D 直接返回 `MCPResponse.text(...)`、A/B/B' 走 executor 主体）；新增 `CollateralSeedData` 静态数据；migration `upgrade_v1.11_to_v1.12.sql` 在 `t_intent_node` 写一条 `kb_id=NULL`、`kind=2`、`mcp_tool_id=collateral_field_lookup` 的全局意图。`RetrievalEngine`、`LLMMCPParameterExtractor`、SSE 帧序、SourceCardBuilder、权限链路全部不动。

**Tech Stack:** Java 17, Spring Boot 3.5.7, MyBatis Plus, JUnit 5, AssertJ, Mockito, Lombok, Hutool (`StrUtil` / `CollUtil`), Redis (`StringRedisTemplate` 缓存 `ragent:intent:tree`).

**Spec:** `docs/superpowers/specs/2026-05-03-collateral-mcp-mvp-design.md`

---

## 已知现状与风险（自检结论）

实施前需要知道的三条仓库真相，避免实施时踩坑：

1. **`IntentNode.examples` 加载语义**：`DefaultIntentClassifier.loadIntentTreeFromDB()` 用 `BeanUtil.toBean(IntentNodeDO, IntentNode.class)`，源端 `examples` 是 JSON String、目标端是 `List<String>`。Hutool `BeanUtil` 不会把 JSON 字符串自动反序列化为 `List<String>`，所以 migration 写入的 `examples` JSON 数组在 LLM 分类 prompt 里**实际不会出现**（与 `IntentTreeServiceImpl` 走 admin REST 写入时同样的限制）。MVP 不依赖 examples 进入 prompt——`description` + `name` + `mcp_tool_id` 足以驱动分类。Task 5 的 `examples` 列照写，是为了 admin UI 后续可读、且不与现有数据形态冲突。
2. **Spotless 行为**：`pom.xml` 把 `spotless:apply` 绑在 `compile` phase（根 pom 配置），任何 `mvn install` / `mvn test` 都会自动格式化。Task 6 的 `spotless:check` 是手工 CI 验证，不是格式化入口；如失败请 `mvn spotless:apply` 后重新提交。
3. **Component scan 范围**：`@SpringBootApplication` 在 `com.knowledgebase.ai.ragent` 顶包，新建子包 `rag.core.mcp.executor` 在扫描路径内，无需额外 `@ComponentScan`。

**架构边界检查清单**（实施时不得违反）：

- 所有新类只 import `com.knowledgebase.ai.ragent.rag.core.mcp.{MCPRequest,MCPResponse,MCPTool,MCPToolExecutor}` + Hutool / Lombok / Spring（`@Component` / `@RequiredArgsConstructor` / `@Slf4j`）。
- **禁止** import `knowledge.*` / `user.*` / `ingestion.*` / `framework.security.*` / `infra.chat.*`（MVP 不接真实数据，不需要 KB 元数据 / 权限端口 / LLM 服务）。
- **禁止** 在 router / seed / executor 里读 `UserContext` 或任何 ThreadLocal——这一层是无状态纯函数 + 静态数据。
- 现有 `RetrievalEngine` 的 `executeSingleMcpTool` / `buildMcpRequest` / `LLMMCPParameterExtractor` / `RAGPromptService` / `StreamChatEventHandler` / `SourceCardBuilder` 全部不动；plan 的所有改动都在新建文件 + 一条 SQL + 根 CLAUDE.md 一行。

---

## File Structure

新增（main）：

- `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedRecord.java` — seed 数据 record（不可变结构体）。
- `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedData.java` — 静态 seed 仓库；提供 `findAgreement` / `findField` / `housePartyAliases` 等只读查找。
- `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupSlotRouter.java` — 槽位分流，C/D 档产 `MCPResponse.text(...)`，A/B/B' 档返回信号让 executor 继续。
- `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutor.java` — `MCPToolExecutor` 实现，先调 router，然后归一化 + 查 seed。

新增（test）：

- `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedDataTest.java`
- `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupSlotRouterTest.java`
- `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutorTest.java`
- `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralRegistryWiringTest.java`

新增（resources）：

- `resources/database/upgrade_v1.11_to_v1.12.sql` — 单条 `INSERT INTO t_intent_node`。

修改：

- `CLAUDE.md` 根 `Upgrade scripts in resources/database/` 列表追加 v1.11→v1.12 一行（保持文档真相单一）。

不动：

- `RetrievalEngine.java`（自动通过 `DefaultMCPToolRegistry` 找到新 executor，无需改代码）。
- `LLMMCPParameterExtractor.java`、`RAGPromptService.java`、`StreamChatEventHandler.java`、SourceCardBuilder、SSE 链路。
- `IntentTreeCacheManager.java`（migration 上线后由运维清 `ragent:intent:tree` Redis key 触发重新加载，记入手工验收步骤）。

---

## Task 1: Seed 数据结构与静态仓库

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedRecord.java`
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedData.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedDataTest.java`

- [ ] **Step 1.1: 写 CollateralSeedDataTest 失败测试**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralSeedDataTest {

    private final CollateralSeedData data = new CollateralSeedData();

    @Test
    void findField_exactHsbcIsdaCsaMta_returnsRecord() {
        Optional<CollateralSeedRecord> rec = data.findField("HSBC", "ISDA&CSA", "Minimum Transfer Amount");
        assertThat(rec).isPresent();
        assertThat(rec.get().value()).isEqualTo("US$250,000.00");
        assertThat(rec.get().docName()).isEqualTo("OTCD - ISDA & CSA in same doc.pdf");
        assertThat(rec.get().page()).isEqualTo("P23");
        assertThat(rec.get().sourceChunkId()).isEqualTo("demo-hsbc-isda-csa-mta-p23");
        assertThat(rec.get().sourceText()).contains("Minimum Transfer Amount");
    }

    @Test
    void findField_mtaSynonym_resolvesToCanonical() {
        Optional<CollateralSeedRecord> rec = data.findField("HSBC", "ISDA&CSA", "MTA");
        assertThat(rec).isPresent();
        assertThat(rec.get().value()).isEqualTo("US$250,000.00");
    }

    @Test
    void findAgreement_unknownAgreementType_returnsEmpty() {
        assertThat(data.findAgreement("HSBC", "GMRA")).isEmpty();
    }

    @Test
    void findAgreement_caseInsensitiveCounterpartyAlias() {
        assertThat(data.findAgreement("the hongkong and shanghai banking corporation limited", "ISDA&CSA"))
                .isPresent();
    }

    @Test
    void isHouseParty_recognisesHuataiAliases() {
        assertThat(data.isHouseParty("华泰")).isTrue();
        assertThat(data.isHouseParty("HT")).isTrue();
        assertThat(data.isHouseParty("Huatai")).isTrue();
        assertThat(data.isHouseParty("HUATAI CAPITAL INVESTMENT LIMITED")).isTrue();
        assertThat(data.isHouseParty("HSBC")).isFalse();
    }

    @Test
    void normalizeAgreementType_collapsesIsdaCsaSpacings() {
        assertThat(data.normalizeAgreementType("ISDA & CSA")).isEqualTo("ISDA&CSA");
        assertThat(data.normalizeAgreementType("ISDA-CSA")).isEqualTo("ISDA&CSA");
        assertThat(data.normalizeAgreementType("ISDA+CSA")).isEqualTo("ISDA&CSA");
        assertThat(data.normalizeAgreementType("isda&csa")).isEqualTo("ISDA&CSA");
    }
}
```

- [ ] **Step 1.2: 运行测试确认失败**

Run: `mvn -pl bootstrap test -Dtest=CollateralSeedDataTest`
Expected: COMPILE FAIL（`CollateralSeedData` / `CollateralSeedRecord` 不存在）。

- [ ] **Step 1.3: 写 CollateralSeedRecord**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

public record CollateralSeedRecord(
        String counterpartyCanonical,
        String agreementTypeCanonical,
        String fieldCanonical,
        String value,
        String docName,
        String page,
        String sourceChunkId,
        String sourceText
) {
}
```

- [ ] **Step 1.4: 写 CollateralSeedData**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Collateral 字段筛查 MVP 静态 seed 数据。
 * <p>
 * MVP 阶段仅服务 PRD 样例：HSBC × ISDA&CSA × Minimum Transfer Amount。
 * 替换为真实数据前必须先实现 KB-scoped 意图过滤 + KbReadAccessPort 校验
 * （见 spec：2026-05-03-collateral-mcp-mvp-design.md「硬前置门」）。
 */
@Component
public class CollateralSeedData {

    private static final String CANONICAL_HSBC = "HSBC";
    private static final String CANONICAL_ISDA_CSA = "ISDA&CSA";
    private static final String CANONICAL_MTA = "Minimum Transfer Amount";

    private static final Map<String, String> COUNTERPARTY_ALIAS_TO_CANONICAL = Map.of(
            "hsbc", CANONICAL_HSBC,
            "the hongkong and shanghai banking corporation limited", CANONICAL_HSBC
    );

    private static final Set<String> HOUSE_PARTY_ALIASES = Set.of(
            "华泰", "ht", "huatai", "huatai capital investment limited"
    );

    private static final Map<String, String> FIELD_ALIAS_TO_CANONICAL = Map.of(
            "minimum transfer amount", CANONICAL_MTA,
            "mta", CANONICAL_MTA
    );

    private static final List<CollateralSeedRecord> RECORDS = List.of(
            new CollateralSeedRecord(
                    CANONICAL_HSBC,
                    CANONICAL_ISDA_CSA,
                    CANONICAL_MTA,
                    "US$250,000.00",
                    "OTCD - ISDA & CSA in same doc.pdf",
                    "P23",
                    "demo-hsbc-isda-csa-mta-p23",
                    "\"Minimum Transfer Amount\" means with respect to Party A: US$250,000.00 ..."
            )
    );

    public Optional<String> resolveCounterparty(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(COUNTERPARTY_ALIAS_TO_CANONICAL.get(key));
    }

    public boolean isHouseParty(String raw) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        return HOUSE_PARTY_ALIASES.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    public String normalizeAgreementType(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        String squeezed = raw.replaceAll("\\s+", "")
                .replace("-", "&")
                .replace("+", "&")
                .toUpperCase(Locale.ROOT);
        return squeezed;
    }

    public Optional<String> resolveField(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(FIELD_ALIAS_TO_CANONICAL.get(key));
    }

    public Optional<CollateralSeedRecord> findAgreement(String counterpartyRaw, String agreementTypeRaw) {
        Optional<String> cp = resolveCounterparty(counterpartyRaw);
        if (cp.isEmpty()) {
            return Optional.empty();
        }
        String at = normalizeAgreementType(agreementTypeRaw);
        return RECORDS.stream()
                .filter(r -> r.counterpartyCanonical().equalsIgnoreCase(cp.get()))
                .filter(r -> r.agreementTypeCanonical().equalsIgnoreCase(at))
                .findFirst();
    }

    public Optional<CollateralSeedRecord> findField(String counterpartyRaw, String agreementTypeRaw, String fieldRaw) {
        Optional<CollateralSeedRecord> agreement = findAgreement(counterpartyRaw, agreementTypeRaw);
        if (agreement.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> field = resolveField(fieldRaw);
        if (field.isEmpty()) {
            return Optional.empty();
        }
        return RECORDS.stream()
                .filter(r -> r.counterpartyCanonical().equalsIgnoreCase(agreement.get().counterpartyCanonical()))
                .filter(r -> r.agreementTypeCanonical().equalsIgnoreCase(agreement.get().agreementTypeCanonical()))
                .filter(r -> r.fieldCanonical().equalsIgnoreCase(field.get()))
                .findFirst();
    }
}
```

- [ ] **Step 1.5: 运行测试确认通过**

Run: `mvn -pl bootstrap test -Dtest=CollateralSeedDataTest`
Expected: PASS（6 个测试方法全绿）。

- [ ] **Step 1.6: 提交**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedRecord.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedData.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralSeedDataTest.java
git commit -m "$(cat <<'EOF'
feat(collateral-mvp): add static seed for collateral field lookup

引入 CollateralSeedData / CollateralSeedRecord，承载 PRD 样例
(HSBC × ISDA&CSA × Minimum Transfer Amount = US$250,000.00 / P23)
以及 counterparty / agreement / field alias 归一化能力。
EOF
)"
```

---

## Task 2: 槽位分流路由器

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupSlotRouter.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupSlotRouterTest.java`

按 spec：raw nonblank 计数（不在 router 层做 fieldName seed 匹配过滤）；3 槽位 → 信号给 executor 继续；1–2 槽位 → C 档；0 槽位 → D 档。

- [ ] **Step 2.1: 写 SlotRouterTest 失败测试**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import com.knowledgebase.ai.ragent.rag.core.mcp.MCPRequest;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPResponse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralFieldLookupSlotRouterTest {

    private static final String TOOL_ID = "collateral_field_lookup";

    private final CollateralFieldLookupSlotRouter router = new CollateralFieldLookupSlotRouter();

    private MCPRequest request(String counterparty, String agreementType, String fieldName) {
        Map<String, Object> params = new HashMap<>();
        if (counterparty != null) {
            params.put("counterparty", counterparty);
        }
        if (agreementType != null) {
            params.put("agreementType", agreementType);
        }
        if (fieldName != null) {
            params.put("fieldName", fieldName);
        }
        return MCPRequest.builder()
                .toolId(TOOL_ID)
                .userQuestion("dummy question")
                .parameters(params)
                .build();
    }

    @Test
    void route_threeRawSlots_proceedsToExecutor() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", "ISDA&CSA", "MTA"));
        assertThat(resp).isEmpty();
    }

    @Test
    void route_threeRawSlotsIncludingUnknownField_stillProceeds() {
        // raw nonblank 计数：unknown field 仍计为槽位，让 executor 走 B' 档
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", "ISDA&CSA", "unknown field"));
        assertThat(resp).isEmpty();
    }

    @Test
    void route_twoSlots_missAgreementType_returnsCText() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", null, "MTA"));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("请补充更多信息以定位文档")
                .contains("Please provide Agreement Type");
    }

    @Test
    void route_oneSlot_returnsCTextWithTwoMissingLabels() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request(null, null, "MTA"));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("Please provide Agreement Type or Counterparty");
    }

    @Test
    void route_oneSlot_counterpartyOnly_returnsCText() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", null, null));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("Please provide Agreement Type or Field Name");
    }

    @Test
    void route_zeroSlots_returnsDFixedText() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request(null, null, null));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("请提供更具体的协议筛查条件")
                .contains("Counterparty")
                .contains("Agreement Type")
                .contains("Field Name");
    }

    @Test
    void route_blankStrings_countAsZeroSlots() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("", "  ", "\t"));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult()).contains("请提供更具体的协议筛查条件");
    }

    @Test
    void route_responseToolIdMatches() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request(null, null, null));
        assertThat(resp).isPresent();
        assertThat(resp.get().getToolId()).isEqualTo(TOOL_ID);
        assertThat(resp.get().isSuccess()).isTrue();
    }
}
```

- [ ] **Step 2.2: 运行测试确认失败**

Run: `mvn -pl bootstrap test -Dtest=CollateralFieldLookupSlotRouterTest`
Expected: COMPILE FAIL（`CollateralFieldLookupSlotRouter` 不存在）。

- [ ] **Step 2.3: 写 CollateralFieldLookupSlotRouter**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import cn.hutool.core.util.StrUtil;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPRequest;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collateral 字段筛查槽位分流器（spec：四档文案 v2）。
 * <ul>
 *   <li>3 槽位（raw nonblank） → 返回 empty，由 executor 继续判定 A/B/B'。</li>
 *   <li>1 或 2 槽位 → C 档：拼接缺失槽位英文标签。</li>
 *   <li>0 槽位 → D 档：返回固定通用引导文案，不回流 IntentGuidanceService。</li>
 * </ul>
 * 不在本类做 fieldName 同义词匹配；同义词归一化与 unknown field 判定在 executor 内。
 */
@Component
public class CollateralFieldLookupSlotRouter {

    static final String TOOL_ID = "collateral_field_lookup";

    static final String LABEL_COUNTERPARTY = "Counterparty";
    static final String LABEL_AGREEMENT_TYPE = "Agreement Type";
    static final String LABEL_FIELD_NAME = "Field Name";

    static final String D_TEXT =
            "请提供更具体的协议筛查条件，例如交易对手方（Counterparty）、协议类型（Agreement Type）以及您想查询的字段（Field Name）。";

    public Optional<MCPResponse> routeBeforeExecutor(MCPRequest request) {
        String counterparty = request.getStringParameter("counterparty");
        String agreementType = request.getStringParameter("agreementType");
        String fieldName = request.getStringParameter("fieldName");

        List<String> missing = new ArrayList<>(3);
        if (StrUtil.isBlank(agreementType)) {
            missing.add(LABEL_AGREEMENT_TYPE);
        }
        if (StrUtil.isBlank(counterparty)) {
            missing.add(LABEL_COUNTERPARTY);
        }
        if (StrUtil.isBlank(fieldName)) {
            missing.add(LABEL_FIELD_NAME);
        }

        if (missing.isEmpty()) {
            return Optional.empty();
        }

        if (missing.size() == 3) {
            return Optional.of(MCPResponse.success(TOOL_ID, D_TEXT));
        }

        String joined = String.join(" or ", missing);
        String text = "请补充更多信息以定位文档,例如:Please provide " + joined;
        return Optional.of(MCPResponse.success(TOOL_ID, text));
    }
}
```

- [ ] **Step 2.4: 运行测试确认通过**

Run: `mvn -pl bootstrap test -Dtest=CollateralFieldLookupSlotRouterTest`
Expected: PASS（8 个测试方法全绿）。

- [ ] **Step 2.5: 提交**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupSlotRouter.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupSlotRouterTest.java
git commit -m "$(cat <<'EOF'
feat(collateral-mvp): add slot router for field lookup C/D paths

引入 CollateralFieldLookupSlotRouter：raw nonblank 计数，1–2 槽位走 C 档
拼接缺失英文标签；0 槽位走 D 档固定引导文案；3 槽位返回空让 executor 继续。
EOF
)"
```

---

## Task 3: MCP Executor + 工具定义

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutor.java`
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutorTest.java`

executor 的 `execute()` 顺序：
1. 调 `CollateralFieldLookupSlotRouter.routeBeforeExecutor()`，命中 C/D → 直接返回。
2. 我方别名回退：如 counterparty 命中 house party，从 `userQuestion` 二次匹配；二次仍失败 → 等价"counterparty 缺失"文案。
3. `findAgreement` miss → B 档；`findField` miss → B' 档；命中 → A 档 `Part 1` + `Part 2`。

- [ ] **Step 3.1: 写 ExecutorTest 失败测试**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import com.knowledgebase.ai.ragent.rag.core.mcp.MCPRequest;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPResponse;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralFieldLookupMCPExecutorTest {

    private final CollateralFieldLookupMCPExecutor executor =
            new CollateralFieldLookupMCPExecutor(new CollateralSeedData(), new CollateralFieldLookupSlotRouter());

    private MCPRequest req(String userQuestion, String cp, String at, String fn) {
        Map<String, Object> params = new HashMap<>();
        if (cp != null) {
            params.put("counterparty", cp);
        }
        if (at != null) {
            params.put("agreementType", at);
        }
        if (fn != null) {
            params.put("fieldName", fn);
        }
        return MCPRequest.builder()
                .toolId("collateral_field_lookup")
                .userQuestion(userQuestion)
                .parameters(params)
                .build();
    }

    @Test
    void toolDefinition_exposesThreeRequiredParameters() {
        MCPTool tool = executor.getToolDefinition();
        assertThat(tool.getToolId()).isEqualTo("collateral_field_lookup");
        assertThat(tool.getParameters()).containsOnlyKeys("counterparty", "agreementType", "fieldName");
        assertThat(tool.getParameters().get("counterparty").isRequired()).isTrue();
        assertThat(tool.getParameters().get("agreementType").isRequired()).isTrue();
        assertThat(tool.getParameters().get("fieldName").isRequired()).isTrue();
    }

    @Test
    void supports_matchesByToolId() {
        assertThat(executor.supports(MCPRequest.builder().toolId("collateral_field_lookup").build())).isTrue();
        assertThat(executor.supports(MCPRequest.builder().toolId("other").build())).isFalse();
    }

    @Test
    void execute_aHit_returnsPartOnePartTwo() {
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少",
                "HSBC", "ISDA&CSA", "minimum transfer amount"));
        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getTextResult())
                .contains("Part 1:")
                .contains("Minimum Transfer Amount: US$250,000.00")
                .contains("Part 2:")
                .contains("OTCD - ISDA & CSA in same doc.pdf")
                .contains("P23")
                .contains("source_chunk_id=demo-hsbc-isda-csa-mta-p23")
                .contains("\"Minimum Transfer Amount\"");
    }

    @Test
    void execute_aHit_mtaSynonym() {
        MCPResponse resp = executor.execute(req(
                "HSBC 的 ISDA&CSA 下 MTA 是多少", "HSBC", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_aHit_counterpartyAliasFullName() {
        MCPResponse resp = executor.execute(req(
                "The HongKong and Shanghai Banking Corporation Limited 的 ISDA&CSA 下 MTA",
                "The HongKong and Shanghai Banking Corporation Limited", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_aHit_agreementTypeNormalisation() {
        MCPResponse resp = executor.execute(req(
                "HSBC ISDA & CSA MTA", "HSBC", "ISDA & CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_houseAliasFallback_secondaryMatchSucceeds() {
        // LLM 把"华泰"塞进 counterparty。executor 回退到 userQuestion 找 HSBC 二次匹配
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的ISDA&CSA下的 MTA 是多少",
                "华泰", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_houseAliasFallback_secondaryMatchFails_returnsCounterpartyMissingText() {
        MCPResponse resp = executor.execute(req(
                "华泰自己的 ISDA&CSA 下 MTA 是多少",
                "华泰", "ISDA&CSA", "MTA"));
        // 二次仍找不到对手方 → 等价 counterparty 缺失
        assertThat(resp.getTextResult())
                .contains("请补充更多信息以定位文档")
                .contains("Please provide Counterparty");
    }

    @Test
    void execute_bMiss_unknownAgreement() {
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的GMRA下的 MTA 是多少", "HSBC", "GMRA", "MTA"));
        assertThat(resp.getTextResult())
                .contains("未找到与")
                .contains("GMRA")
                .contains("HSBC");
    }

    @Test
    void execute_bPrimeMiss_unknownField_butKnownAgreement() {
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的ISDA&CSA下的 unknown field 是什么",
                "HSBC", "ISDA&CSA", "unknown field"));
        assertThat(resp.getTextResult())
                .contains("已定位协议")
                .contains("未找到字段")
                .contains("unknown field");
    }

    @Test
    void execute_routerCMiss_returnsRouterText() {
        MCPResponse resp = executor.execute(req(
                "HSBC 的 MTA 是多少", "HSBC", null, "MTA"));
        assertThat(resp.getTextResult())
                .contains("请补充更多信息以定位文档")
                .contains("Please provide Agreement Type");
    }

    @Test
    void execute_routerDMiss_returnsRouterText() {
        MCPResponse resp = executor.execute(req(
                "这个协议是什么意思", null, null, null));
        assertThat(resp.getTextResult()).contains("请提供更具体的协议筛查条件");
    }
}
```

- [ ] **Step 3.2: 运行测试确认失败**

Run: `mvn -pl bootstrap test -Dtest=CollateralFieldLookupMCPExecutorTest`
Expected: COMPILE FAIL（`CollateralFieldLookupMCPExecutor` 不存在）。

- [ ] **Step 3.3: 写 CollateralFieldLookupMCPExecutor**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import cn.hutool.core.util.StrUtil;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPRequest;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPResponse;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Collateral 字段筛查 MVP 本地 executor（spec：2026-05-03-collateral-mcp-mvp-design.md）。
 * <p>
 * execute() 顺序：
 * <ol>
 *   <li>SlotRouter 判 C/D，直接返回。</li>
 *   <li>我方别名（华泰/HT/...）回退：从 userQuestion 二次匹配 counterparty alias。</li>
 *   <li>findAgreement miss → B 档；findField miss → B' 档；命中 → A 档 Part1+Part2。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollateralFieldLookupMCPExecutor implements MCPToolExecutor {

    static final String TOOL_ID = "collateral_field_lookup";

    private final CollateralSeedData seedData;
    private final CollateralFieldLookupSlotRouter slotRouter;

    @Override
    public MCPTool getToolDefinition() {
        Map<String, MCPTool.ParameterDef> params = new LinkedHashMap<>();
        params.put("counterparty", MCPTool.ParameterDef.builder()
                .description("交易对手方，如 HSBC、The HongKong and Shanghai Banking Corporation Limited")
                .type("string").required(true).build());
        params.put("agreementType", MCPTool.ParameterDef.builder()
                .description("协议类型，如 ISDA&CSA、ISDA、CSA、GMRA")
                .type("string").required(true).build());
        params.put("fieldName", MCPTool.ParameterDef.builder()
                .description("业务字段，如 minimum transfer amount、MTA、Rounding")
                .type("string").required(true).build());
        return MCPTool.builder()
                .toolId(TOOL_ID)
                .description("Collateral 协议字段筛查：根据 counterparty + agreementType + fieldName 返回字段值与原文证据。")
                .parameters(params)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPResponse execute(MCPRequest request) {
        Optional<MCPResponse> routed = slotRouter.routeBeforeExecutor(request);
        if (routed.isPresent()) {
            return routed.get();
        }

        String counterpartyRaw = request.getStringParameter("counterparty");
        String agreementType = request.getStringParameter("agreementType");
        String fieldName = request.getStringParameter("fieldName");

        String effectiveCounterparty = resolveEffectiveCounterparty(counterpartyRaw, request.getUserQuestion());
        if (StrUtil.isBlank(effectiveCounterparty)) {
            return MCPResponse.success(TOOL_ID,
                    "请补充更多信息以定位文档,例如:Please provide Counterparty");
        }

        Optional<CollateralSeedRecord> agreement = seedData.findAgreement(effectiveCounterparty, agreementType);
        if (agreement.isEmpty()) {
            return MCPResponse.success(TOOL_ID,
                    "未找到与 " + agreementType + " + " + effectiveCounterparty + " 相关的文档");
        }

        Optional<CollateralSeedRecord> rec = seedData.findField(effectiveCounterparty, agreementType, fieldName);
        if (rec.isEmpty()) {
            return MCPResponse.success(TOOL_ID,
                    "已定位协议，但未找到字段 " + fieldName + " 的示例答案");
        }

        return MCPResponse.success(TOOL_ID, formatPartOnePartTwo(rec.get()));
    }

    private String resolveEffectiveCounterparty(String raw, String userQuestion) {
        if (StrUtil.isNotBlank(raw) && !seedData.isHouseParty(raw)) {
            return raw;
        }
        // 我方别名回退：从原问题中二次匹配 counterparty alias
        if (StrUtil.isBlank(userQuestion)) {
            return null;
        }
        String lower = userQuestion.toLowerCase(Locale.ROOT);
        if (lower.contains("hsbc")) {
            return "HSBC";
        }
        if (lower.contains("the hongkong and shanghai banking corporation limited")) {
            return "The HongKong and Shanghai Banking Corporation Limited";
        }
        return null;
    }

    private String formatPartOnePartTwo(CollateralSeedRecord r) {
        return "Part 1:\n"
                + r.fieldCanonical() + ": " + r.value() + "\n\n"
                + "Part 2:\n"
                + "Source: " + r.docName() + ", " + r.page() + ", source_chunk_id=" + r.sourceChunkId() + "\n"
                + "Original Text: " + r.sourceText();
    }
}
```

- [ ] **Step 3.4: 运行测试确认通过**

Run: `mvn -pl bootstrap test -Dtest=CollateralFieldLookupMCPExecutorTest`
Expected: PASS（12 个测试方法全绿）。

- [ ] **Step 3.5: 提交**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutor.java \
        bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupMCPExecutorTest.java
git commit -m "$(cat <<'EOF'
feat(collateral-mvp): add local MCP executor for collateral field lookup

实现 CollateralFieldLookupMCPExecutor：
- SlotRouter 前置 C/D 分流；
- 我方别名（华泰/HT/Huatai）走 userQuestion 二次匹配 counterparty；
- findAgreement/findField miss 分别返 B/B' 档文案；
- 命中走 A 档 Part 1 + Part 2，逐字保留 value/page/source_chunk_id/sourceText。
EOF
)"
```

---

## Task 4: Spring Bean 自动注册回归测试

**Files:**
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralRegistryWiringTest.java`

只验证 `DefaultMCPToolRegistry.init()` 在收到 `[CollateralFieldLookupMCPExecutor]` 列表时正确注册并暴露工具，不启动 Spring Context（节省时间，复用现有 spring-boot-starter-test 套件）。

- [ ] **Step 4.1: 写 CollateralRegistryWiringTest**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import com.knowledgebase.ai.ragent.rag.core.mcp.DefaultMCPToolRegistry;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralRegistryWiringTest {

    @Test
    void defaultRegistry_autoRegistersCollateralExecutor() {
        CollateralFieldLookupMCPExecutor executor =
                new CollateralFieldLookupMCPExecutor(new CollateralSeedData(), new CollateralFieldLookupSlotRouter());
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(executor));

        registry.init();

        assertThat(registry.contains("collateral_field_lookup")).isTrue();
        Optional<MCPToolExecutor> resolved = registry.getExecutor("collateral_field_lookup");
        assertThat(resolved).isPresent();

        MCPTool tool = resolved.get().getToolDefinition();
        assertThat(tool.getParameters()).containsOnlyKeys("counterparty", "agreementType", "fieldName");
    }
}
```

- [ ] **Step 4.2: 运行测试确认通过**

Run: `mvn -pl bootstrap test -Dtest=CollateralRegistryWiringTest`
Expected: PASS。

- [ ] **Step 4.3: 提交**

```bash
git add bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralRegistryWiringTest.java
git commit -m "test(collateral-mvp): verify DefaultMCPToolRegistry auto-registers collateral executor"
```

---

## Task 5: Migration（意图节点 seed）

**Files:**
- Create: `resources/database/upgrade_v1.11_to_v1.12.sql`
- Modify: `CLAUDE.md`（仅追加一行）

为避免 `id` / `intent_code` / `mcp_tool_id` 漂移，统一用确定性短 ID `int-coll-mcp-fl1`。`kb_id=NULL`、`kind=2`、`enabled=1`、`level=2`、`sort_order=1000`、`parent_code=NULL`（GLOBAL 顶层叶子）；`description` / `examples` / `param_prompt_template` / `prompt_template` 与 spec 锁文逐字一致。

- [ ] **Step 5.1: 写 upgrade_v1.11_to_v1.12.sql**

```sql
-- v1.11 → v1.12：新增 Collateral 字段筛查 MCP 意图节点（GLOBAL）
-- 见 spec：docs/superpowers/specs/2026-05-03-collateral-mcp-mvp-design.md
-- 注意：上线后必须清 Redis key `ragent:intent:tree`，否则缓存仍是旧意图树
--   docker exec redis redis-cli DEL ragent:intent:tree

INSERT INTO t_intent_node (
    id,
    kb_id,
    intent_code,
    name,
    level,
    parent_code,
    description,
    examples,
    collection_name,
    top_k,
    mcp_tool_id,
    kind,
    sort_order,
    prompt_snippet,
    prompt_template,
    param_prompt_template,
    enabled,
    create_by,
    update_by,
    create_time,
    update_time,
    deleted
) VALUES (
    'int-coll-mcp-fl1',
    NULL,
    'mcp_collateral_field_lookup',
    'Collateral 协议字段筛查',
    2,
    NULL,
    '根据 Counterparty + Agreement Type + Field Name 在 Collateral 知识空间内定位协议字段值，返回 Part 1 字段值与 Part 2 原文证据。',
    '["华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少","HSBC 的 ISDA&CSA 下 Rounding 是多少","华泰和HSBC交易的ISDA&CSA下的 Valuation Agent 是什么"]',
    NULL,
    NULL,
    'collateral_field_lookup',
    2,
    1000,
    NULL,
    $PROMPT$你正在回答 Collateral 协议字段筛查问题。
若动态数据片段中包含 Part 1 和 Part 2：
- 必须保留 Part 1 / Part 2 两段结构。
- 必须逐字保留字段值、金额格式、页码、source_chunk_id 和原文片段。
- 不要把金额换算、翻译、合并或改写。
- 不要补充动态数据片段之外的协议结论。
若动态数据片段是 miss 文案（B/C/D 档）：
- 直接原样返回该文案，不要补充推测内容。$PROMPT$,
    $PROMPT$你是 Collateral 协议字段筛查的参数抽取器。
从用户问题中只提取以下 JSON 字段：
{
  "counterparty": "...",
  "agreementType": "...",
  "fieldName": "..."
}

规则：
- counterparty 是交易对手方，可能是简称（如 HSBC）也可能是完整机构名。
- counterparty 必须是用户问题中"我方/华泰/HT/Huatai"以外的交易对手方；若问题只提到一方，该方即 counterparty。
- 示例："华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少" -> {"counterparty":"HSBC","agreementType":"ISDA&CSA","fieldName":"minimum transfer amount"}
- 示例："HSBC 的 ISDA & CSA 下 MTA 是多少" -> {"counterparty":"HSBC","agreementType":"ISDA & CSA","fieldName":"MTA"}
- agreementType 抽取用户原文中的协议类型表达，不要自行归一化；例如 ISDA&CSA、ISDA & CSA、ISDA-CSA、ISDA+CSA、ISDA、CSA、GMRA。
- fieldName 是用户想查询的业务字段，保留英文原词或中英文混合原词，不要自行归一化。
- 不确定的字段返回空字符串。
- 只输出 JSON，不输出解释。$PROMPT$,
    1,
    'system',
    'system',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
)
ON CONFLICT (id) DO NOTHING;
```

- [ ] **Step 5.2: 在 PostgreSQL dev 实例上演练 migration**

Run（项目根目录）：

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.11_to_v1.12.sql
```

Expected: 输出 `INSERT 0 1`（首次）或 `INSERT 0 0`（重复幂等）。

- [ ] **Step 5.3: 验证插入正确**

Run：

```bash
docker exec postgres psql -U postgres -d ragent -c "SELECT id, kb_id, intent_code, kind, mcp_tool_id, enabled, deleted FROM t_intent_node WHERE id='int-coll-mcp-fl1';"
```

Expected：`kb_id` 列输出 NULL，`kind=2`，`mcp_tool_id=collateral_field_lookup`，`enabled=1`，`deleted=0`。

- [ ] **Step 5.4: 清 Redis 意图树缓存**

`IntentTreeCacheManager` 把意图树缓存 Redis key `ragent:intent:tree`，TTL 7 天。不清缓存的话新插入的 MCP 节点 7 天内不会被加载。Redis 实例由 `redis-server --requirepass 123456` 启动（见 [docs/dev/setup/launch.md](docs/dev/setup/launch.md)），CLI 必须带 `-a` 密码。

Run：

```bash
docker exec redis redis-cli -a 123456 DEL ragent:intent:tree
```

Expected：返回 `(integer) 1`（首次）或 `(integer) 0`（缓存本就不存在）；可能伴随 `Warning: Using a password with '-a' or '-u' option on the command line interface may not be safe`，可忽略。

- [ ] **Step 5.5: 更新根 CLAUDE.md 升级脚本列表**

Edit `CLAUDE.md`，找到 "Upgrade scripts in `resources/database/`:" 列表，按现有缩进追加：

```markdown
- `upgrade_v1.11_to_v1.12.sql` — 新增 Collateral 字段筛查 MCP 全局意图节点（`mcp_collateral_field_lookup` / `kb_id=NULL` / `kind=2`）；上线后须清 Redis key `ragent:intent:tree`
```

- [ ] **Step 5.6: 提交**

```bash
git add resources/database/upgrade_v1.11_to_v1.12.sql CLAUDE.md
git commit -m "$(cat <<'EOF'
feat(collateral-mvp): seed global intent node for collateral_field_lookup

新增 upgrade_v1.11_to_v1.12.sql 写入一行 t_intent_node：
- kb_id=NULL（GLOBAL 约定）
- kind=2（MCP）
- mcp_tool_id=collateral_field_lookup
- examples / param_prompt_template / prompt_template 与 spec 锁文一致
上线步骤：执行 SQL → DEL Redis key ragent:intent:tree。
EOF
)"
```

---

## Task 6: 全量构建 + 单测扫一遍

新代码不改任何现有 main 类，理论上不会破坏既有测试。这步用来兜底：万一 Spring 自动扫描或包路径出错，能在合并前发现。

- [ ] **Step 6.1: 跑 bootstrap 全部新增测试**

Run:

```bash
mvn -pl bootstrap test -Dtest='CollateralSeedDataTest,CollateralFieldLookupSlotRouterTest,CollateralFieldLookupMCPExecutorTest,CollateralRegistryWiringTest'
```

Expected: 4 个测试类全绿。

- [ ] **Step 6.2: Spotless 格式校验**

Spotless 在根 `pom.xml` 已绑定到 `compile` phase（`apply` goal），任何 `mvn install / test` 都会自动格式化新文件。本步是显式确认仓库无未格式化遗留：

Run:

```bash
mvn -pl bootstrap spotless:check
```

Expected: BUILD SUCCESS。如失败，跑 `mvn -pl bootstrap spotless:apply` 后重新提交（git commit 信息 `style(collateral-mvp): apply spotless`）。如果 Step 1.6 / 2.5 / 3.5 / 4.3 之前的提交里 Spotless 已经自动改过文件，文件状态应该已经是格式化的；这步只在罕见情况下报错。

- [ ] **Step 6.3: 全量构建（跳过基线已知失败用例）**

Run:

```bash
mvn -pl bootstrap test -Dtest='!MilvusCollectionTests,!InvoiceIndexDocumentTests,!PgVectorStoreServiceTest#testChineseCharacterInsertion,!IntentTreeServiceTests#initFromFactory,!VectorTreeIntentClassifierTests'
```

Expected: BUILD SUCCESS（基线失败 5 类已排除，参考根 CLAUDE.md "Pre-existing test failures on fresh checkout"）。

如有新失败：read 失败用例 → 修复 → 再跑该用例 → 再跑 Step 6.3。

- [ ] **Step 6.4: 提交（仅在 Spotless 改动了文件时）**

如果 Spotless 没改文件，跳过此步。否则：

```bash
git status
git add -p
git commit -m "style(collateral-mvp): apply spotless"
```

---

## Task 7: 自动化集成测试 — Fake LLM 通过 DB 加载意图

**Files:**
- Test: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupParamExtractIntegrationTest.java`

目标：从 DB 加载 `mcp_collateral_field_lookup` 节点，触发 `LLMMCPParameterExtractor.extractParameters()`，断言 prompt 含 DB 提供的 `param_prompt_template`，假 LLM 返回确定性 JSON 后参数 map 含 `counterparty=HSBC` + 非空 `agreementType` / `fieldName`。

由于这是 spec 强制的"集成测试"项，但完整 Spring Boot 上下文启动慢，我们用 `LLMMCPParameterExtractor` 直接构造 + Mock `LLMService` + 本地 stub `IntentNodeDO` 模拟 DB 行（避免 DB 依赖）。

- [ ] **Step 7.1: 写集成测试**

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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import com.knowledgebase.ai.ragent.framework.convention.ChatRequest;
import com.knowledgebase.ai.ragent.infra.chat.LLMService;
import com.knowledgebase.ai.ragent.rag.core.mcp.LLMMCPParameterExtractor;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import com.knowledgebase.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模拟"DB 加载 mcp_collateral_field_lookup → 调用 LLMMCPParameterExtractor"链路。
 * 不启动 Spring，不连真实 DB——param_prompt_template 直接以字符串注入，
 * 等价 IntentNodeDO.paramPromptTemplate 取出后的状态。
 */
class CollateralFieldLookupParamExtractIntegrationTest {

    private static final String PARAM_PROMPT_FROM_DB =
            """
            你是 Collateral 协议字段筛查的参数抽取器。
            从用户问题中只提取以下 JSON 字段：
            {
              "counterparty": "...",
              "agreementType": "...",
              "fieldName": "..."
            }
            """;

    @Test
    void extractParameters_usesDbProvidedTemplate_andYieldsThreeSlots() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader loader = mock(PromptTemplateLoader.class);
        // PromptTemplateLoader 是 mock；当 LLMMCPParameterExtractor 走 DB 模板分支时
        // load(...) 不会被调用，无需 stub。若实施时发现 stub 调用了，说明分支判断逻辑被改动。

        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("{\"counterparty\":\"HSBC\",\"agreementType\":\"ISDA&CSA\",\"fieldName\":\"minimum transfer amount\"}");

        LLMMCPParameterExtractor extractor = new LLMMCPParameterExtractor(llmService, loader);

        CollateralFieldLookupMCPExecutor executor =
                new CollateralFieldLookupMCPExecutor(new CollateralSeedData(), new CollateralFieldLookupSlotRouter());
        MCPTool tool = executor.getToolDefinition();

        Map<String, Object> params = extractor.extractParameters(
                "华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少",
                tool,
                PARAM_PROMPT_FROM_DB);

        assertThat(params).containsEntry("counterparty", "HSBC");
        assertThat(params.get("agreementType")).isNotNull();
        assertThat(params.get("fieldName")).isNotNull();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(captor.capture());
        // system message 必须使用 DB 模板（不回退到 PromptTemplateLoader.load）
        String firstSystemContent = captor.getValue().getMessages().get(0).getContent();
        assertThat(firstSystemContent).contains("Collateral 协议字段筛查的参数抽取器");
        // 走 DB 模板时，PromptTemplateLoader.load(...) 不应被调用
        verifyNoInteractions(loader);
    }
}
```

- [ ] **Step 7.2: 运行测试确认通过**

Run: `mvn -pl bootstrap test -Dtest=CollateralFieldLookupParamExtractIntegrationTest`
Expected: PASS。

如果 `LLMMCPParameterExtractor` 实际构造签名 / `ChatRequest.getMessages()` 形态与上面假设不一致，按编译错误指示修正断言（先看 [LLMMCPParameterExtractor.java](bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/LLMMCPParameterExtractor.java) 的 `extractParameters(...)` 当前实现，再调整断言锚点为它真正构造 ChatRequest 时的字段访问方式）。

- [ ] **Step 7.3: 提交**

```bash
git add bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/mcp/executor/CollateralFieldLookupParamExtractIntegrationTest.java
git commit -m "test(collateral-mvp): integration test for DB-provided MCP param template"
```

---

## Task 8: 手工验收脚本（PR 描述内嵌）

代码合并前必须人工跑完。

- [ ] **Step 8.1: 启动后端 + 前端**

Run（项目根目录，PowerShell）：

```bash
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

另一终端：

```bash
cd frontend && npm run dev
```

Expected: 后端启动到 `Started ... on port 9090`；前端 Vite 在 5173 端口可访问。

- [ ] **Step 8.2: 准备 KB 数据**

在 admin 控制台或 DB 中确认存在 COB / SOP / Collateral 三个 KB；记下 Collateral 的 `kbId`，下文称作 `<collateral-kb-id>`。

如缺数据，按 [docs/dev/setup/launch.md](docs/dev/setup/launch.md) 上传少量样例文档（COB/SOP 上传若干 FAQ 文档，Collateral 上传一份说明文档；Collateral 无须接真实协议）。

- [ ] **Step 8.3: 跑 spec 验收用例 1–12（按表格顺序）**

每条问题在前端 `/chat?kbId=...` 提问，记录结果是否符合 spec 第 11 节"手工验收脚本"表格预期。重点：

- 用例 3：A 档 → 检查 SSE EventStream（DevTools Network → 选 `chat` 请求 → "EventStream"）确认无 `event: sources` 帧。
- 用例 6：B' 档 → 文案含 "已定位协议，但未找到字段 unknown field"。
- 用例 7、8：C 档拼接是否对应缺失槽位。
- 用例 9：D 档固定文案。
- 用例 10：在 COB / SOP 5 个高频问题不会触发 `mcp_collateral_field_lookup`（看后端日志关键字 `MCP 工具执行` 是否出现）。
- 用例 11：Collateral A 档 → 浏览器渲染消息、`onFinish` 触发、不卡。
- 用例 12：业务方人工裁决 → 记录到 PR 描述。

- [ ] **Step 8.4: 提交手工验收记录到 PR 描述**

把 Step 8.3 中每条用例的实际结果（PASS / FAIL / N/A）+ 用例 12 业务方裁决结论，整理为 PR 描述里的 "Test Plan" markdown checklist。无须额外 commit。

---

## Self-Review

**1. Spec coverage**

Spec 第 11 节自动化测试要求：
- executor 单测：精确命中、counterparty alias、agreement type 归一化、`MTA → MTA` 同义、我方别名回退成功、回退失败、未命中协议（B）、命中协议未命中字段（B'） → Task 3.1 全部覆盖。
- router 单测：3 槽位（含 unknown field）→ executor；1/2 槽位 → C；0 槽位 → D（不调 guidance） → Task 2.1 覆盖。
- registry 自动注册 → Task 4.1 覆盖。
- DB-provided `param_prompt_template` 集成测试 → Task 7.1 覆盖。

Spec 手工验收 1–12 → Task 8.3 覆盖。

Spec migration / 缓存清理 / 帧序口径 → Task 5 + Task 8（手工 SSE 验收）。

**2. Placeholder scan**

无 "TBD / TODO / 后续填" 类占位。所有代码块完整，包含 license header；命令行带预期输出；提交信息现成。

**3. Type consistency**

- `TOOL_ID = "collateral_field_lookup"`（router、executor、test、SQL `mcp_tool_id` 五处一致）。
- `MCPResponse.success(toolId, textResult)` / `MCPResponse.error(toolId, code, msg)` 与 [MCPResponse.java](bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/MCPResponse.java) 现有静态工厂签名一致。
- `MCPTool.ParameterDef.builder()...build()` 与 [MCPTool.java](bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/MCPTool.java) 内部类签名一致。
- `MCPRequest.getStringParameter(String)` / `userQuestion` / `parameters` 字段访问与 [MCPRequest.java](bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/MCPRequest.java) 现有 API 一致。
- `DefaultMCPToolRegistry(List<MCPToolExecutor> autoDiscoveredExecutors)` + `init()` + `contains(String)` + `getExecutor(String)` 与 [DefaultMCPToolRegistry.java](bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/DefaultMCPToolRegistry.java) 一致。
- migration ID `int-coll-mcp-fl1`、`intent_code = mcp_collateral_field_lookup` 在 SQL 与 spec 一致。
- `LLMMCPParameterExtractor(LLMService, PromptTemplateLoader)` 构造与 [LLMMCPParameterExtractor.java](bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/mcp/LLMMCPParameterExtractor.java) `@RequiredArgsConstructor` 注入顺序一致。
- `ChatRequest.getMessages()` 返回 `List<ChatMessage>`、`ChatMessage.getContent()` 由 Lombok `@Data` 生成（见 [ChatRequest.java](framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/ChatRequest.java) / [ChatMessage.java](framework/src/main/java/com/knowledgebase/ai/ragent/framework/convention/ChatMessage.java)）。

**4. Architectural boundary check**

逐文件审视新增类的 import 列表，确保不越界：

| 文件 | 允许的 import | 禁止的 import |
| --- | --- | --- |
| `CollateralSeedRecord` | `java.*` | 任何外部包 |
| `CollateralSeedData` | `cn.hutool.core.util.StrUtil` / `org.springframework.stereotype.Component` / `java.*` | `knowledge.*` / `user.*` / `framework.security.*` / `infra.*` |
| `CollateralFieldLookupSlotRouter` | 同上 + `rag.core.mcp.{MCPRequest,MCPResponse}` | 同上 + `mcp.executor` 内部其他类（router 不依赖 executor / seedData） |
| `CollateralFieldLookupMCPExecutor` | 同上 + `rag.core.mcp.{MCPRequest,MCPResponse,MCPTool,MCPToolExecutor}` + `lombok.*` | 同上 + `RetrievalEngine` / `RAGPromptService` / `IntentNode` / `IntentNodeDO` / `LLMService` / `PromptTemplateLoader` |

测试类允许 import 其测试目标 + Mockito + AssertJ + JUnit Jupiter；`CollateralFieldLookupParamExtractIntegrationTest` 例外，它必须 import `LLMService` / `PromptTemplateLoader` / `LLMMCPParameterExtractor` / `ChatRequest`，因为它的测试目标就是这条桥接。

**5. Dependency leak check**

- 新增 `@Component` 三个：`CollateralSeedData` / `CollateralFieldLookupSlotRouter` / `CollateralFieldLookupMCPExecutor`，均在 `rag.core.mcp.executor` 包下，不暴露任何 `public` API 给其他域；它们对外只通过 `MCPToolExecutor` 接口与 `DefaultMCPToolRegistry` 交互。
- 没有新建 framework / infra-ai / mcp-server 内文件；`pom.xml` 不需要改 dependencies。
- migration 不改任何已存在表结构、不删任何行；只是 `INSERT ... ON CONFLICT (id) DO NOTHING`，幂等。

**6. Stylistic alignment**

- License header 16 行 Apache 2.0，与 `resources/format/copyright.txt` 字节级一致（Spotless `apply` 会强制对齐）。
- Lombok 用法：`@RequiredArgsConstructor`（构造注入） / `@Slf4j`（log） / `@Data` / `@Builder`，与 `LLMMCPParameterExtractor`、`DefaultMCPToolRegistry` 一致。
- 包路径下划线/驼峰：`rag.core.mcp.executor` 全小写（与 `rag.core.mcp.client` 一致）；类名 PascalCase。
- 中文 JavaDoc + 英文 identifier，与项目约定一致。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-03-collateral-mcp-mvp.md`. Two execution options:

**1. Subagent-Driven (recommended)** — 我每个 Task 派一个新 subagent，Task 间复核迭代快。
**2. Inline Execution** — 在当前 session 顺序执行，按 checkpoint 一组组通过。

Auto mode 下默认走 **Subagent-Driven**：每个 Task 一个 fresh subagent，executor 跑 TDD 红→绿→commit；review subagent 复核；通过后进入下一个 Task。
