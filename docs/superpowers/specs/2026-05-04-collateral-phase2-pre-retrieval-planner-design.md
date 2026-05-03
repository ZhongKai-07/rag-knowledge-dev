# Collateral Phase 2 Design: Pre-Retrieval Query Planner

- 日期：2026-05-04
- 状态：Draft
- 替代：`2026-05-03-collateral-phase2-min-security-design.md`（已作废，旧版仅做"安全闭环"，新版扩展为"安全门 + 预检索槽位约束闭环"）
- 上游：
  - 全景 Roadmap：`docs/dev/design/2026-05-03-collateral-smart-screening-roadmap.md`
  - Phase 1（已合）：`docs/superpowers/specs/2026-05-03-collateral-mcp-mvp-design.md`
  - Phase 1.5（独立 PR，与本 spec 解耦）：`docs/superpowers/specs/2026-05-03-collateral-phase15-design.md`
- 目标 PR 集合：Phase 2.0、2.1、2.2、2.3、2.4（共 5 个 PR，依赖串行）
- PRD：`E:/AIProject/PRD/OPS+AI+场景2：Colleteral+智能筛查+Chatbot.doc`

## 0. 定位

Phase 1 把 `collateral_field_lookup` 实现为一个 MCP 通道，和 KB 检索通道在 `RetrievalEngine.buildSubQuestionContext(...)` 里**并行拼接**结果。代码事实：

- `MCPParameterExtractor.extractParameters(userQuestion, tool, ...)` 只看用户问题，不消费 retrieval 上下文
- `RetrievalScope` 只承载权限边界（`AccessScope` / `kbSecurityLevels` / `targetKbId`），无业务 metadata filter slot
- `IntentParallelRetriever` 在 MCP 抽参之前就把 metadata filter 算好了
- `MultiChannelRetrievalEngine` 翻译 OpenSearch 查询时只接受 KB / security level filter

**结论**：Phase 1 的 MCP 通道**位置错位**——它本质是"意图命中后槽位抽取再注入检索约束"，不是工具调用。Phase 2 把这个错位修正：

1. **MCP 抽象回归 actions only**（保留给 Phase 4 的 `start_collateral_extraction` / `export_collateral_excel` 等真有副作用的工具）
2. **新增 Pre-Retrieval Query Planner** 作为意图分类后、检索前的核心环节
3. **重新理解意图分类边界**：单 KB 入口下，"KB 检索"不再是 intent kind，是默认 fallback；意图分两类——SYSTEM 全局唯一 + KB-LOCAL 的 MCP / FAQ / Slot-Filled
4. **保持向后兼容**：Phase 1 的 MCP intent 在 Phase 2.4 才退役，期间通过 KB-scope 安全门避免误触发

Phase 2 不接真实协议数据（属 Phase 3）；不做 OCR / chunk 级 page metadata（属 Phase 2.5）；不做坐标级跳转（属 Phase 5）。

## 1. 范围与边界

### 1.1 In Scope（按 PR 拆分见 §8）

- **Phase 2.0 安全门**：KB-scoped intent + COB/SOP 误路由 CI gate
- **Phase 2.1 抽象骨架（一）**：`IntentSlotExtractor` SPI、`QueryPlanner` 组件、默认 noop 注册；主链路接入但行为零变化
- **Phase 2.2 抽象骨架（二）**：`RetrievalConstraints` 对象 + 通道 1（意图定向）+ 通道 2（全局向量）消费 metadata filter；`MultiChannelRetrievalEngine` 翻译到 OpenSearch
- **Phase 2.3 Collateral 实现**：`CollateralSlotExtractor` + `CollateralAnswerComposer` + `EvidenceValueExtractor`（LLM 抽值 + chunk-membership 校验）+ Collateral prompt 模板 + 上传表单 + ingestion 文档级 metadata 写入
- **Phase 2.4 Phase 1 MCP 查询意图退役**：删除 `mcp_collateral_field_lookup` intent node + 对应 executor 注册；MCP 抽象保留给 Phase 4 actions

### 1.2 Out of Scope

- OCR / 版面解析 / chunk 级 page metadata → Phase 2.5
- 真实协议字段值结构化存储（`t_collateral_field_value`） → Phase 3
- 批量筛查工作流 / 复核台 / Excel 导出 → Phase 4
- PDF 坐标级跳转 / Sources 面板 MCP source card → Phase 5
- `IntentKind` enum 重命名 / `t_intent_node.scope` 列 / `t_kb_mcp_binding` 表 → Phase H
- 跨 KB 全量 chat（已废弃，单 KB 入口下不复存在）
- 字段精确值的 100% 准确度承诺（依赖 PDF 文本质量；扫描件由 Phase 2.5 解决）

### 1.3 验收降级（与用户已对齐）

- "点击跳转原文" 在 Phase 2/3 降级为"跳转到 chunk 文本 + 显示文件名/段落"，**不带 PDF 页坐标**。Phase 2.5 OCR 上线后补页码。
- A 档字段精确值由 LLM 抽 + chunk-membership 校验保证；扫描件 / 复杂版式准确度由 Phase 2.5 + Phase 3 进一步提升。

## 2. 重新定义意图分类边界（基于单 KB 入口）

### 2.1 当前实现 vs 单 KB 入口下的合理模型

| 维度 | 当前 Phase 1 实现 | 单 KB 入口下的合理模型 |
| --- | --- | --- |
| Intent 类型 | SYSTEM / KB / MCP（三分类，IntentKind enum） | SYSTEM 全局 + KB-LOCAL（MCP / FAQ / Slot-Filled）+ 隐式默认 fallback |
| `t_intent_node.kb_id` | Phase 1 写 NULL（GLOBAL） | SYSTEM intent 写 NULL；KB-LOCAL intent 写具体 kb_id |
| 跨 KB 命中 | 可能（Phase 1 的 collateral 意图能在 COB/SOP 命中，KI-1 风险） | 不可能（KB-scope 加载 + retrieval 端 guard 双层） |
| "KB 检索" 是不是 intent | 是（IntentKind.KB） | 不是，是无显式意图命中时的隐式 fallback |
| Collateral field lookup 归属 | MCP 通道 | KB-LOCAL Slot-Filled intent（Phase 2.4 退役 MCP 后） |

### 2.2 意图分类输出契约（Phase 2 后）

每个 sub-question 的分类返回 0~N 个候选 leaf：

```
SYSTEM leaves (kb_id IS NULL):
  - 全局共享，所有 KB 入口可命中
  - 当前用例：greeting / help / system commands
  - Phase 2 不引入新 SYSTEM intent

KB-LOCAL leaves (kb_id = activeKbId):
  - 仅当前 KB 可命中
  - 子类：FAQ（curated 答案路由）/ MCP（Phase 4 actions）/ Slot-Filled（带槽位的检索约束，如 collateral_field_lookup）

无候选命中（或最高分 < 阈值）:
  -> 隐式 fallback：通道 1 意图定向检索退化为 KB 默认检索 + 通道 2 全局向量检索
  -> Prompt 走默认模板
```

### 2.3 Phase 2 不动 IntentKind enum，但更新语义文档

- `IntentKind.KB`（kind=1）当前承载"KB 检索意图"——单 KB 入口下其实是"KB-LOCAL FAQ + KB-LOCAL Slot-Filled"两种。Phase 2 不改 enum，仅在 design doc + javadoc 中标注语义将在 Phase H 评估改名（如 `KB_LOCAL_FAQ`）。
- `IntentKind.MCP`（kind=2）继续表示"工具调用"，Phase 2.4 后仅承载 actions，不承载查询。
- Collateral field lookup 改由"KB-LOCAL FAQ 节点 + 注册 Slot Extractor"实现（kind=1，名为 `collateral_field_lookup`，kb_id=Collateral KB）。
- 迁移分两步避免 Phase 2.3 的"intent code 不存在导致集成测试无法验证"问题：
- 三步迁移路线（与 §8 PR 拆分对齐）：
  - **Phase 2.0 migration v1.12→v1.13**：把 Phase 1 的 `mcp_collateral_field_lookup` 行 `kb_id` 从 NULL 收窄到 Collateral KB（仍 kind=2，仍 enabled=1，运行时仍可命中——但仅在 Collateral KB 入口）
  - **Phase 2.3 migration v1.13→v1.14**：新增 `collateral_field_lookup`（kind=1，kb_id=Collateral KB，enabled=1），同时把旧 `mcp_collateral_field_lookup` 置 `enabled=0`（保留行作 audit trail；分类器加载 SQL 已带 `enabled=1` 过滤，物理上不进缓存）
  - **Phase 2.4 migration v1.14→v1.15**：物理删除 `mcp_collateral_field_lookup` 行 + 取消 `DefaultMCPToolRegistry` 中 collateral executor 的自动注册
  - 共存窗口：2.3 PR 合并后到 2.4 PR 合并之前，由 `enabled=0` 屏障保证只有新意图被加载；不依赖分数 tiebreaker

## 3. 核心抽象

### 3.1 IntentResult（每 sub-question）

```java
public record IntentResult(
    String subQuestion,
    List<NodeScore> candidates,            // 排序后的候选 intent leaves（含分数）
    Optional<NodeScore> selected,          // 高于阈值且与次高分有显著差距的命中
    Map<String, Object> metadata           // trace / 调试用
) {}
```

**`selected` 的计算职责归 `IntentResolver`**（不是 QueryPlanner）。判定规则：

- `candidates[0].score >= INTENT_HARD_THRESHOLD`（配置默认 0.7）
- `candidates[0].score - candidates[1].score >= INTENT_MARGIN`（配置默认 0.1，避免 top1/top2 接近时误命中）
- 否则 `selected = empty()`，走隐式 fallback

QueryPlanner 仅消费 `selected`，不做选择决策。这样阈值和 margin 集中在意图层（与意图配置同地维护），不分散到下游。

### 3.2 QueryPlanner（Spring `@Component`，新建）

定位：意图分类与检索之间的**单 sub-question 串行环节**，独立于 `IntentResolver`（后者负责并行多 sub-question 分类）。

```java
@Component
public class QueryPlanner {
    QueryPlan plan(IntentResult intentResult, RetrievalScope scope) {
        if (intentResult.selected().isEmpty()) {
            return QueryPlan.fallback(intentResult.subQuestion());
        }
        NodeScore selected = intentResult.selected().get();
        Optional<IntentSlotExtractor> extractor = registry.find(selected.getNode().getIntentCode());
        if (extractor.isEmpty()) {
            return QueryPlan.intentDirected(intentResult, RetrievalConstraints.empty());
        }
        Map<String, String> slots = extractor.get().extractSlots(intentResult.subQuestion(), selected.getNode());
        RetrievalConstraints constraints = constraintTranslator.translate(selected.getNode(), slots);
        return QueryPlan.slotFilled(intentResult, slots, constraints);
    }
}
```

**独立性原因**：
- 单 sub-question 串行；与 `IntentResolver` 的多 sub-question 并行模型不同
- 需独立 timeout（默认 5s，超时降级到 noop constraints）、token budget、trace、配置开关
- 独立 metric（slot extraction 命中率 / 超时率）

**调用阈值**：仅当 `intentResult.selected().isPresent()` 且注册了对应 extractor 时跑 LLM。

### 3.3 IntentSlotExtractor SPI

```java
public interface IntentSlotExtractor {
    String supportedIntentCode();              // 如 "collateral_field_lookup"
    Map<String, String> extractSlots(String question, IntentNode leaf);
}
```

- 注册中心 `IntentSlotExtractorRegistry`：`@Component` 启动时聚合所有 SPI 实现 → `Map<intentCode, extractor>`
- 默认行为：未注册的 intent code → planner 返回 empty constraints，主链路行为零变化
- 第一个实现：`CollateralSlotExtractor`（见 §6.1）

### 3.4 RetrievalConstraints

```java
public record RetrievalConstraints(
    Map<String, String> metadataFilters,   // 已通过白名单 + 别名归一化的 metadata filter
    Map<String, String> queryHints         // 给 keyword 检索用，如 fieldName 同义词扩展
) {
    public static RetrievalConstraints empty() {
        return new RetrievalConstraints(Map.of(), Map.of());
    }
    public boolean isEmpty() { return metadataFilters.isEmpty() && queryHints.isEmpty(); }
}
```

**关键不变量**：
- `RetrievalScope` 只承载**权限边界**，`RetrievalConstraints` 只承载**业务边界**，二者并列入参，不混
- `metadataFilters` 的 key 必须在白名单内（见 §7.1）
- 业务 filter 与权限 scope 合并时**仅 AND**，不能放宽 scope（见 §7.2）

### 3.5 IntentAnswerComposer / ComposedAnswer（决定档位的确定性逻辑）

```java
public interface IntentAnswerComposer {
    String supportedIntentCode();
    ComposedAnswer compose(QueryPlan plan, RetrievalContext retrieved);
}

public record ComposedAnswer(
    AnswerTier tier,                       // A / B / Bp / C / D / DEFAULT
    String renderingTemplate,              // prompt 模板 ID（如 "collateral.tier_a"）
    Map<String, Object> renderingData,     // 模板插槽
    boolean allowLlmGeneration,            // false = 直接固定文案，跳过 LLM
    List<EvidenceRef> evidenceRefs         // chunk_id + doc_id（page 留 null，Phase 2.5 后补）
) {}
```

**核心契约**：档位由 composer 用纯 Java 判断决定，prompt 不做条件判断。`allowLlmGeneration=false` 的档（B/C/D）走固定文案，不调 LLM，**保证检索没命中时 LLM 不可能编出字段值**。

### 3.6 Prompt 路由：用 conditional template 而不是 `IntentPromptStrategy` SPI

理由：Phase 2 引入 SPI 主链路改动已不少，prompt 策略再加一个 SPI 收益不高。改为：

- `RAGPromptService` 接受 `ComposedAnswer.renderingTemplate` 作为 hint
- 现有 prompt 模板加 conditional section，按 templateId 分支渲染
- Phase H 如果 KB 数 > 3 个有专属 prompt 需求时，再升级为 SPI

**注**：用户先前 feedback memory 写过"Prompt evidence formatting 职责边界"——证据格式化职责应留 prompt service。本设计遵循：composer 决定**结构性数据**（chunks / tier / data），prompt service 决定**自然语言渲染**。

## 4. 端到端数据流（per sub-question）

```
RAGChatServiceImpl.streamChat
  ↓
RetrievalScopeBuilder.build(activeKbId)         # 权限边界 (KbReadAccessPort.checkReadAccess)
  ↓
intentResolver.resolve(rewriteResult, activeKbId)  # 并行多 sub-question 分类
  ↓ List<IntentResult>
queryPlanner.plan(intentResult, scope)            # 串行单 sub-question 槽位抽取
  ↓ List<QueryPlan>
retrievalEngine.retrieve(queryPlans, scope)
  ├─ 通道 1 IntentDirectedRetrieval              # 读 plan.constraints.metadataFilters
  ├─ 通道 2 GlobalVectorRetrieval                 # 读 plan.constraints.metadataFilters
  └─ 通道 3 MCP                                    # Phase 2.4 后仅 actions, 查询走 KB-LOCAL Slot-Filled
  ↓ RetrievalContext
answerComposerRegistry.lookup(plan.intentCode).compose(plan, retrieved)
  ↓ ComposedAnswer (tier + renderingTemplate + renderingData + allowLlmGeneration)
ragPromptService.render(composedAnswer)
  ├─ allowLlmGeneration=false → 直接返回 renderingData 里的固定文案
  └─ allowLlmGeneration=true → 按 templateId + renderingData 渲染 prompt → LLM
  ↓
SSE → 前端
```

**Collateral 字段查询走的具体路径**（举例 `华泰和HSBC的ISDA&CSA下的MTA是多少`，activeKbId=Collateral）：

```
1. IntentResolver 分类 → selected = collateral_field_lookup (kind=KB_LOCAL_FAQ, score=0.95)
2. QueryPlanner → CollateralSlotExtractor.extractSlots:
     {counterparty: "HSBC", agreementType: "ISDA&CSA", fieldName: "Minimum Transfer Amount"}
3. 槽位 → RetrievalConstraints:
     metadataFilters: {counterparty_normalized: "HSBC", agreement_type: "ISDA_CSA"}
     queryHints: {fieldNameSynonyms: ["Minimum Transfer Amount", "MTA"]}
4. RetrievalEngine 通道 1+2 用 metadata filter 查 OpenSearch → chunks 来自 HSBC ISDA&CSA 文档
5. CollateralAnswerComposer 决策:
     - 槽位齐全 + chunks 非空 + EvidenceValueExtractor 抽出 "US$250,000.00" 且能在 chunk 中匹配
     -> tier = A, allowLlmGeneration = true (但 prompt 强约束抄录), renderingData 含 { precisionValue, originalParagraph, sourceChunkId, docName }
6. PromptService 用 collateral.tier_a 模板渲染 → LLM 输出 Part1 + Part2 (附 source ref)
7. SSE 推送
```

## 5. 主链路改动清单

| 文件 | 改动 | 是否破坏向后兼容 | PR |
| --- | --- | --- | --- |
| `IntentClassifier` | 新增 `classifyTargets(question, activeKbId)` 默认方法（旧签名转发） | 否（默认方法） | 2.0 |
| `IntentResolver` | 新增 `resolve(rewriteResult, activeKbId)`，旧签名 → activeKbId=null | 否 | 2.0 |
| `DefaultIntentClassifier` | DB 查询条件按 activeKbId 过滤；缓存按 KB 分桶 | 否（外部行为不变，缓存 key 升级） | 2.0 |
| `IntentTreeCacheManager` | `kb:{id}` / `global` 双键，`clear` 用 pattern delete | 否 | 2.0 |
| `IntentResult`（新建 record） | 新建 | 否 | 2.1 |
| `IntentSlotExtractor` SPI（新建） | 新建 | 否 | 2.1 |
| `IntentSlotExtractorRegistry`（新建 `@Component`） | 新建 | 否 | 2.1 |
| `QueryPlanner`（新建 `@Component`） | 新建 | 否 | 2.1 |
| `QueryPlan`（新建 record） | 新建 | 否 | 2.1 |
| `RetrievalConstraints`（新建 record） | 新建 | 否 | 2.2 |
| `RetrievalEngine.retrieve(...)` | 签名扩展接受 `List<QueryPlan>`；旧入口保留 deprecated 适配 | 否（默认转发） | 2.2 |
| `IntentParallelRetriever` / `MultiChannelRetrievalEngine` | 接受 `RetrievalConstraints`；OpenSearch query builder 翻译 metadataFilters → `bool.filter.term` | 否（empty 时跳过） | 2.2 |
| `IntentAnswerComposer` SPI（新建） | 新建 | 否 | 2.2 |
| `ComposedAnswer`（新建 record） | 新建 | 否 | 2.2 |
| `RAGPromptService` | 接受 `ComposedAnswer.renderingTemplate` + `renderingData`；现有模板加 conditional section | 否（默认行为不变） | 2.2 |
| `MCPRequest` | `activeKbId` 字段（Phase 1 已加）保留 | n/a | n/a |
| `MCPParameterExtractor` | 不再用于查询路径；保留接口给 Phase 4 actions | 否 | 2.4 |
| `t_intent_node` | 2.0 收窄 Phase 1 MCP 意图 kb_id；2.3 新增 `collateral_field_lookup`（kind=1，kb_id=Collateral KB）+ 旧 MCP 意图 `enabled=0`；2.4 物理删除旧 MCP 意图 | 是（三个 migration） | 2.0 / 2.3 / 2.4 |
| `DefaultMCPToolRegistry` | Phase 2.4 取消 collateral executor 自动注册（保留代码以备将来 actions 复用） | 是 | 2.4 |

**主链路对其他 KB（COB/SOP）的影响**：
- 所有新接口 / 新字段在 noop 实现下行为零变化
- `RetrievalConstraints.empty()` 时通道 1/2 行为与现状完全一致
- `QueryPlan.fallback(...)` 时 `IntentAnswerComposer` 不命中任何注册实现 → 走默认 prompt 模板

## 6. Collateral 专属实现

### 6.1 CollateralSlotExtractor（Phase 2.3）

```java
@Component
public class CollateralSlotExtractor implements IntentSlotExtractor {
    @Override public String supportedIntentCode() { return "collateral_field_lookup"; }

    @Override
    public Map<String, String> extractSlots(String question, IntentNode leaf) {
        // LLM call with tight prompt:
        //   抽出 counterparty, agreementType, fieldName 三个槽位; 任一缺失返回空字符串
        ExtractedSlots raw = llmExtract(question);
        return Map.of(
            "counterparty", normalizer.normalizeCounterparty(raw.counterparty(), question),
            "agreementType", normalizer.normalizeAgreementType(raw.agreementType()),
            "fieldName", normalizer.normalizeFieldName(raw.fieldName())
        );
    }
}
```

`CollateralSlotNormalizer`：
- counterparty：复用 Phase 1.5 `CollateralSeedData.findCounterpartyAliasInText` 的 alias 表 + house party fallback（"华泰和HSBC" → "HSBC"）
- agreementType：归一化"ISDA&CSA" / "ISDA和CSA" / "ISDA-CSA" → 统一字面 `ISDA_CSA`
- fieldName：归一化常见字段（MTA → "Minimum Transfer Amount"），低置信时返回原值（让 keyword 检索兜底）

**槽位映射到 metadata filter（白名单 mapping，见 §7.1）**：
```
counterparty (normalized non-empty) → metadataFilters["counterparty_normalized"]
agreementType (whitelisted enum)     → metadataFilters["agreement_type"]
fieldName (任意值)                    → queryHints["fieldNameSynonyms"]  (不进 filter)
```

### 6.2 CollateralAnswerComposer（Phase 2.3）

档位决策（纯 Java，不调 LLM）：

```
let cp = slots.counterparty
let at = slots.agreementType
let fn = slots.fieldName
let chunks = retrieved.chunksFromCollateralKb()
let evidence = EvidenceValueExtractor.extract(fn, chunks)   // LLM-backed 值抽取，见 §6.3

A 档：
  cp 非空 && at 非空 && fn 非空 && chunks 非空 && evidence.precisionValue 在某 chunk 原文出现
  → tier=A, allowLlmGeneration=true (走 collateral.tier_a 严格抄录模板)
  → renderingData={ precisionValue, originalParagraph, sourceChunkId, docName, fieldName }

B' 档：
  (cp 命中 + at 缺失) 或 (cp 缺失 + at 命中)，且 chunks 非空
  → tier=Bp, allowLlmGeneration=true (走 collateral.tier_bp 多文档汇总模板)
  → renderingData={ candidateDocs, fieldName }

B 档：
  cp + at 都命中, 但 chunks 为空 或 evidence.precisionValue 抽取失败 / 校验失败
  → tier=B, allowLlmGeneration=false
  → 固定文案"未在指定文档中找到字段 X 的明确表述。建议人工复核 [docName]"

C 档：
  仅 1 个 slot 命中 (cp 或 at)
  → tier=C, allowLlmGeneration=false
  → 固定文案"请补充更多信息以定位文档，例如：Please provide Agreement Type or Counterparty"

D 档：
  0 个 slot 命中（slots 全空）
  → tier=D, allowLlmGeneration=false
  → 通用引导文案
```

**与 Phase 1 五档文案的语义对齐**：A/B/B'/C/D 文案 PRD 已固定，新 composer 内部模板 ID `collateral.tier_a` / `collateral.tier_bp` / 等直接引用 Phase 1 文案（避免对外语义变化）。

### 6.3 EvidenceValueExtractor（Phase 2.3，LLM-backed + 校验）

```java
@Component
public class EvidenceValueExtractor {
    public Optional<ExtractedValue> extract(String fieldName, List<Chunk> chunks) {
        // 1. LLM 调用：从 chunks 拼接的文本中抽出 fieldName 对应的精确值
        //    Prompt 强约束：必须逐字抄录 chunk 中的数字 / 短语，不允许改写、推断、补全
        String llmAnswer = llmExtractValue(fieldName, chunks);

        // 2. chunk-membership 校验：抽出的值必须能在某个 chunk 原文中字符匹配
        Optional<Chunk> sourceChunk = findChunkContaining(llmAnswer, chunks);
        if (sourceChunk.isEmpty()) {
            log.warn("EvidenceValueExtractor 抽出值 [{}] 未在 chunks 中找到匹配，拒绝进 A 档", llmAnswer);
            return Optional.empty();
        }

        // 3. 提取所在段落（chunk 文本本身或定位到段落级）
        String paragraph = extractParagraph(llmAnswer, sourceChunk.get());
        return Optional.of(new ExtractedValue(llmAnswer, paragraph, sourceChunk.get().getId()));
    }
}
```

**校验逻辑**：
- 数字 / 金额：去除空白和千分位后做子串匹配（容许 `US$250,000.00` vs `US$ 250,000.00` 这种格式差异）
- 长短语：去除多空格后做子串匹配
- 校验失败 → 直接降 B 档（不允许 A 档无证据）

**Phase 3 演进**：高频字段（MTA / IA / Rounding 等）改走 `t_collateral_field_value` 表查询（DB 精确值），仅低频字段保留 LLM 抽取。本 spec 不触及。

### 6.4 上传表单 + ingestion 文档级 metadata（Phase 2.3）

**前端**：
- Collateral KB 上传页加三个必填字段：counterparty / agreementType / agreementDate
- 字段值进 `IngestionTaskDTO.metadata`，POST `/ingestion/tasks/upload`

**后端**：
- `IngestionTaskService` 把 metadata 透传到 `StructuredDocument.metadata`
- `IndexerNode` 把白名单字段（counterparty_normalized / agreement_type / agreement_date）写到 OpenSearch chunk 的 metadata 字段

**Phase 1.5 的 ingestion preset**：已规划 enricher 抽 counterparty / agreementType / fieldHint，本 spec 在 enricher 输出基础上叠加上传表单输入（表单输入优先，enricher 仅 fallback）。

**OpenSearch mapping**：在 `indexBuilder` 增加几个 keyword 字段（无 OCR/page 字段，那些是 Phase 2.5）。

### 6.5 Collateral prompt 模板

`prompts/collateral.tier_a.md`：

```
你必须严格逐字抄录提供的 chunk 中的内容；不得改写数字、金额、单位；不得推断或补全。
按以下结构输出：

Part 1：{{fieldName}}：{{precisionValue}}
Part 2：{{originalParagraph}}

来源：{{docName}} (chunk: {{sourceChunkId}})
```

`collateral.tier_bp.md`：多文档汇总模板，列出候选文档和字段名，让用户挑。

`collateral.tier_b.md` / `tier_c.md` / `tier_d.md`：固定文案，不调 LLM，由 composer 直接返回。

## 7. 安全约束

### 7.1 metadataFilters key 白名单（防 prompt injection）

LLM 抽出的槽位 key **不能直接当 OpenSearch metadata filter key**。必须由 `RetrievalConstraintTranslator` 做白名单 mapping：

```java
@Component
public class RetrievalConstraintTranslator {
    private static final Set<String> ALLOWED_FILTER_KEYS = Set.of(
        "counterparty_normalized",  // 由 CollateralSlotNormalizer 输出（HSBC / BOCHK / ...）
        "agreement_type",           // 枚举：ISDA / CSA / GMRA / ISDA_CSA
        "agreement_date",           // ISO date 格式，由上传表单填，可选
        "doc_id"                    // chunk 所属文档 id
        // 仅这些 key 可作为 OpenSearch filter；Phase 2.5 后增 page / block_id
    );

    RetrievalConstraints translate(IntentNode leaf, Map<String, String> rawSlots) {
        Map<String, String> filters = new HashMap<>();
        Map<String, String> hints = new HashMap<>();

        // 仅按 leaf 已注册的 mapping 翻译
        SlotMapping mapping = leaf.getSlotMapping();   // 静态配置 / annotation
        for (var entry : rawSlots.entrySet()) {
            String dest = mapping.destinationFor(entry.getKey());
            if (dest == null) continue;
            if (ALLOWED_FILTER_KEYS.contains(dest)) {
                filters.put(dest, sanitize(entry.getValue()));
            } else {
                hints.put(dest, entry.getValue());
            }
        }
        return new RetrievalConstraints(filters, hints);
    }
}
```

**关键不变量**：
- `metadataFilters` 的 key 必须在 `ALLOWED_FILTER_KEYS` 中
- value 经 `sanitize` 去除特殊字符（避免 OpenSearch DSL 注入）
- 任何不在白名单的 LLM 输出 → 退化到 hints（仅给 keyword 检索 boost），不影响 filter

### 7.2 业务 filter 与权限 scope 合并：仅 AND

`MultiChannelRetrievalEngine` 翻译 OpenSearch query 时：

```
bool {
  filter [
    { term: { kb_id: scope.targetKbId } },             # 权限边界
    { range: { security_level: <= scope.maxLevel } }, # 权限边界
    constraints.metadataFilters 翻译为 term 子句       # 业务边界（AND 进 filter）
  ]
}
```

**禁止**：
- 业务 filter 不能用 `should`（OR）逃出 scope 限制
- 业务 filter 不能 `must_not` 反转权限条件
- `metadataFilters` 的 value 即使包含 `*` / `?` 等通配符，也按字面量匹配（`term` 不是 `wildcard`）

### 7.3 KB-scope intent 过滤双层 guard

| 层 | 实现 | 失败行为 |
| --- | --- | --- |
| 加载层 | `DefaultIntentClassifier.loadIntentTreeFromDB(activeKbId)` SQL `WHERE kb_id IS NULL OR kb_id = ?` | KB-LOCAL intent 物理上不进缓存 |
| 执行层 | `RetrievalEngine.retrieve()` 进入前再 check `node.kbId IS NULL OR node.kbId == scope.targetKbId` | 如果加载层有 bug，retrieval 端再拦一次，记 WARN |

### 7.4 MCP 通道权限校验（Phase 2.0 完成）

Phase 1 后保留：
- `MCPRequest.activeKbId` 字段已存在
- `RetrievalEngine.executeMcpAndMerge` 进入前 `KbReadAccessPort.checkReadAccess(activeKbId)`
- Phase 2.4 后 MCP 通道仅承载 actions，但安全校验保留

## 8. PR 拆分

### 8.1 PR 列表与依赖

| PR | 范围摘要 | 依赖 | 主链路改动 | 验收 |
| --- | --- | --- | --- | --- |
| **2.0** 安全门 | KB-scope intent + COB/SOP 误路由 CI gate + Phase 1 collateral MCP intent 从 GLOBAL 收窄到 Collateral KB（migration v1.11→v1.12 已完成；v1.12→v1.13 写 kb_id 收窄） | 无 | IntentClassifier / IntentResolver / IntentTreeCacheManager / DefaultIntentClassifier | 见 §9.1 |
| **2.1** Planner 骨架 | `IntentResult` / `IntentSlotExtractor` SPI / `QueryPlanner` `@Component` / `QueryPlan` record；默认 noop 注册；主链路接 planner 行为零变化 | 2.0 | `IntentResolver` 输出改 IntentResult；`RAGChatServiceImpl` 调用 planner | 见 §9.2 |
| **2.2** Constraints + 通道消费 | `RetrievalConstraints` / `RetrievalConstraintTranslator` / `IntentAnswerComposer` SPI / `ComposedAnswer`；通道 1+2 翻译 metadata filter；prompt 接 ComposedAnswer | 2.1 | `RetrievalEngine.retrieve` 签名扩展；`IntentParallelRetriever` / `MultiChannelRetrievalEngine` 加 filter 翻译；`RAGPromptService` 路由 templateId | 见 §9.3 |
| **2.3** Collateral 实现 | `CollateralSlotExtractor` + `CollateralSlotNormalizer`（复用 Phase 1.5 alias 资产）+ `CollateralAnswerComposer` + `EvidenceValueExtractor` + Collateral prompt 模板（5 档）+ 上传表单 metadata + ingestion 写文档级 metadata + OpenSearch mapping 增字段 + migration v1.13→v1.14（新增 `collateral_field_lookup` kind=1 + 旧 MCP 意图 `enabled=0`）+ 集成测试 | 2.2 + Phase 1.5 | 仅 Collateral module，主链路零改动 | 见 §9.4 |
| **2.4** Phase 1 MCP 查询意图退役 | migration v1.14→v1.15 物理删 `mcp_collateral_field_lookup`；`DefaultMCPToolRegistry` 取消 collateral executor 自动注册；保留 MCPToolExecutor 抽象给 Phase 4 | 2.3 + 集成测试通过 | DB migration + Bean 注册 | 见 §9.5 |

### 8.2 与 Phase 1.5 / Phase 2.5 的关系

- **Phase 1.5**（独立 PR，与本 spec 解耦）：修 combined party span。alias 归一化资产 Phase 2.3 复用。Phase 2.4 后 Phase 1.5 修的 executor 路径会退役，但 alias 表迁移到 `CollateralSlotNormalizer`，资产保留。
- **Phase 2.5**（独立 spec，未启动）：OCR + 版面解析。与本 spec 完全并行，谁先谁后无依赖。Phase 2.5 完成后，Phase 2.3 的 EvidenceValueExtractor 输出可附 page 信息（升级，非破坏）。

## 9. 验收

### 9.1 Phase 2.0 验收

**自动化测试**：
- `DefaultIntentClassifierKbScopeTest`：activeKbId=COB → 不加载 collateral 意图；activeKbId=Collateral → 加载；activeKbId=null → 仅 GLOBAL
- `IntentTreeCacheManagerKbScopeTest`：双 key 隔离 + clear-all
- `RetrievalEngineMcpSecurityTest`（保留 Phase 1 已写）：activeKbId 缺失跳 MCP；READ 权限失败抛错
- `CollateralMcpKbScopeRegressionTest`（新）：COB/SOP 下强行注入 collateral 意图也不触发 MCP；assertion 基于"results 不含 collateral mcp tool 调用"，不绑文案

**手工**：
- COB/SOP KB 问 Collateral 字段问题 → 走 KB-only 检索，不返回 Collateral 字段答案
- Collateral KB 问同样问题 → Phase 1 路径仍能给 A/B/C/D 答案

### 9.2 Phase 2.1 验收

**自动化测试**：
- `QueryPlannerNoopTest`：未注册 extractor 时返回 `intentDirected(empty constraints)`
- `QueryPlannerSelectedThresholdTest`：top1 < 阈值 → 返回 `fallback(...)`
- `QueryPlannerTimeoutTest`：extractor 超时 → 降级到 noop constraints + WARN

**结构性断言**：
- COB/SOP / 任何已有 KB 的现有集成测试**全部通过零变化**（向后兼容证据）

### 9.3 Phase 2.2 验收

**自动化测试**：
- `RetrievalConstraintTranslatorTest`：白名单内 → filters；白名单外 → hints；恶意字符串 → sanitize
- `MultiChannelRetrievalEngineFilterTest`：constraints 非空 → OpenSearch query 含 `bool.filter.term`；empty → query 不变
- `IntentAnswerComposerRegistryTest`：未注册 → 返回默认 ComposedAnswer
- `RetrievalEngineConstraintsAndScopeTest`：业务 filter 不能放宽 scope（验权限不变 + filter 仅 AND）

**结构性断言**：现有所有 KB 行为不变（filter 为 empty 时主链路完全等价于改动前）

### 9.4 Phase 2.3 验收

**自动化测试**：
- `CollateralSlotExtractorTest`：典型 / combined span / 缺槽位 / LLM 返回非 JSON 兜底
- `CollateralAnswerComposerTest`：5 档分类规则全覆盖（A / B / Bp / C / D）
- `EvidenceValueExtractorTest`：抽出值在 chunk → 通过；不在 chunk → 拒绝；金额格式差异 → 通过
- `CollateralPromptRenderingTest`：A 档模板渲染含 Part 1 + Part 2 + sourceChunkId
- `IngestionDocLevelMetadataTest`：上传 metadata → OpenSearch chunk metadata 含 counterparty_normalized / agreement_type / agreement_date

**手工 / 集成**：
- 上传 HSBC ISDA&CSA PDF 一份（Phase 1.5 ingestion preset），表单填 counterparty=HSBC / agreementType=ISDA&CSA
- 问 PRD 11 个问题：MTA / Rounding / Eligible Collateral / ... 各档表现
- 问对手方 alias 变体：`华泰和HSBC` / `Huatai and HSBC` / `HSBC` 各能 A 档
- 问错误对手方：`华泰和ABC` → C 档（缺 counterparty）或 B 档（找不到文档），不能误命中 HSBC

**结构性断言**：
- A 档输出 JSON 解析后必须含 `precisionValue`、`originalParagraph`、`sourceChunkId`、`docName`
- A 档的 `precisionValue` 字符串必须能在某 chunk 文本中字符匹配（验 evidence-membership）
- B/C/D 档的 LLM 调用次数 = 0（验 deterministic 路径）

### 9.5 Phase 2.4 验收

**自动化测试**：
- `CollateralIntentMigrationTest`：v1.15 migration 后，`mcp_collateral_field_lookup` 行物理不存在；`collateral_field_lookup` (kind=1, kb_id=Collateral KB) 存在
- `MCPRegistryNoCollateralQueryExecutorTest`：`DefaultMCPToolRegistry` 不再自动注册 `collateral_field_lookup` executor
- 复跑 Phase 2.3 集成测试：Collateral 字段查询走 KB-LOCAL 路径，不经过 MCP 通道

**结构性断言**：
- `RagTraceContext` 里查询路径的 trace 不含 MCP node（验路径切换成功）
- COB/SOP 下问 Collateral 字段问题 → 双层 guard 都不命中（语义未回退）

## 10. 与其他阶段的关系

- **Phase 1.5**：独立修 alias span，不依赖本 spec；alias 资产被 Phase 2.3 复用
- **Phase 2.5（OCR/版面）**：独立并行；Phase 2.5 完成后 EvidenceValueExtractor 升级附 page，B/C/D 档的固定文案不变
- **Phase 3（真实数据）**：Phase 3 不再需要新建 metadata 入口（Phase 2.3 已建上传表单 + ingestion 链路）；只需补 `t_collateral_field_value` 高频字段表 + `CollateralAnswerComposer` 优先查表 fallback 到 chunk
- **Phase 4（批量筛查）**：MCP 抽象（保留的 actions 通道）承载 `start_extraction` / `get_result` / `export_excel`；与本 spec 的查询路径互不影响
- **Phase 5（坐标级跳转）**：本 spec 的 `EvidenceRef` 已预留 page 字段（默认 null）；Phase 5 + Phase 2.5 完成后填充
- **Phase H（治理平台）**：本 spec 不引入 `t_intent_node.scope` / `t_kb_mcp_binding` 等表；当前 KB-scope 逻辑用 `kb_id IS NULL` 约定。Phase H 评估时再统一升级

## 11. 不做清单

- 不引入 `IntentPromptStrategy` SPI（用 conditional template，Phase H 再评估）
- 不修改 `IntentKind` enum（语义文档化即可）
- 不引入 `t_kb_mcp_binding` 表（Phase H）
- 不接真实协议字段值结构化存储（Phase 3）
- 不做 OCR / 版面解析（Phase 2.5）
- 不做 PDF 坐标级跳转（Phase 5）
- 不做批量筛查工作流（Phase 4）
- 不在本 spec 修复 Phase 1.5 的 combined party span（独立 PR）
- 不删除 MCPToolExecutor / MCPRequest / MCPToolRegistry 抽象（保留给 Phase 4 actions）
- 不做 cross-KB intent 视图（单 KB 入口下不需要）
- 不做 cache 精细化失效（Phase H）

## 12. 关键不变量（实现 / Review 时硬约束）

1. **业务 filter 与权限 scope 仅 AND 合并**，不能用 OR / must_not 逃逸 scope
2. **A 档必须有 evidence**：composer 校验 precisionValue 在某 chunk 文本中字符匹配，否则降 B 档
3. **B/C/D 档不调 LLM**：`allowLlmGeneration=false`；防止 LLM 在无 chunks 时编出字段值
4. **metadataFilters key 必须在白名单**：LLM 输出不能直接当 OpenSearch filter key
5. **KB-scope intent 双层 guard**：加载层（SQL）+ 执行层（retrieve 入口）
6. **`RetrievalScope` 不混业务字段**：只放权限边界（accessScope / kbSecurityLevels / targetKbId）
7. **主链路对其他 KB 透明**：noop 路径下行为完全等价于改动前；现有所有集成测试零变化通过
8. **Phase 1 MCP query intent 在 2.4 才退役**：2.3 之前两套并存，靠 KB-scope 安全门避免风险
9. **断言基于结构性存在/缺失**（per `feedback_assertion_decoupling.md`）：不绑"not found" / "权限失败"等人类可读文案
10. **缓存失效面覆盖完整**（per `feedback_cache_invalidation_coverage.md`）：intent 树 mutation 路径（admin CRUD / migration / KB 软删）必须 invalidate `ragent:intent:tree*`
