# Collateral MCP MVP Design (v2)

> Supersedes `2026-05-02-collateral-mcp-field-lookup-design.md`. v2 锁定四档文案、SSE 帧序口径、双路边界、`kb_id=NULL`（GLOBAL）的意图落点。

## 背景

PRD `OPS+AI+场景2：Colleteral+智能筛查+Chatbot.doc` 要求在同一 Chatbot 入口里支持：

- COB / SOP 两个知识库做普通 RAG 知识问答；
- Collateral 知识库下，识别"`Counterparty + Agreement Type + 业务字段`"三槽位问题，定位到协议条款并以 `Part 1 + Part 2` 形式回答（字段值 + 来源原文）。

MVP 的目标：在不改动当前 RAG 主链路、不重构权限体系的前提下，跑通 Collateral 字段筛查的 MCP 链路，业务可演示。

## Scope

### In Scope

- Collateral 字段筛查 MCP 工具与本地 executor。
- 全局意图树新增一个 MCP 节点（`kb_id=NULL` 即 GLOBAL）。
- 静态 seed 数据（HSBC × ISDA&CSA × MTA 等高频字段）。
- 四档文案分流（A/B/C/D）。
- Collateral MCP 路径的 SSE 帧序验收口径。
- COB / SOP / Collateral 三个 KB 的并存使用。

### Out of Scope

- 真实协议库 / 真实文档检索（Phase 2）。
- KB-scoped MCP intent 治理（Phase 2 硬前置）。
- MCP 来源进入 Sources 面板（Phase 3）。
- OCR / PDF 坐标 / 精确跳转（Phase 3）。
- ingestion pipeline 改造（Phase 1.5 可选）。
- 意图管理改部门级或 KB 级。
- 自动文件夹监听 / Share-folder 集成。
- 修改 COB / SOP 现有 RAG 链路。

## 架构

主链路完全复用现有：

```text
/rag/v3/chat?kbId=<COB|SOP|Collateral>
  → RAGChatServiceImpl
  → DefaultIntentClassifier（全局意图树）
  → RetrievalEngine
       ├─ KB 分支：NodeScoreFilters.kb(...)  → 普通 RAG 通道（COB/SOP/Collateral）
       └─ MCP 分支：NodeScoreFilters.mcp(...) → 命中 mcp_collateral_field_lookup
            → LLMMCPParameterExtractor
            → 槽位分流（A/B/C/D，见下）
            → CollateralFieldLookupMCPExecutor（仅 A/B 调）
            → MCPResponse.textResult → RetrievalContext.mcpContext
  → RAGPromptService.planMcpOnly() / KB 默认模板
  → LLMService 流式输出
  → StreamChatEventHandler → SSE
```

新增类（仅 bootstrap 模块本地）：

- `com.knowledgebase.ai.ragent.rag.core.mcp.executor.CollateralFieldLookupMCPExecutor`（implements `MCPToolExecutor`）
- `com.knowledgebase.ai.ragent.rag.core.mcp.executor.CollateralSeedData`
- `com.knowledgebase.ai.ragent.rag.core.mcp.executor.CollateralFieldLookupSlotRouter`（四档分流，可与 executor 同包）

不新增任何 controller / DAO / 实体表。

## MCP 工具契约

Tool ID:

```text
collateral_field_lookup
```

参数：

| 名称 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `counterparty` | string | true | 交易对手方，如 `HSBC`、`The HongKong and Shanghai Banking Corporation Limited` |
| `agreementType` | string | true | 协议类型，如 `ISDA&CSA`、`ISDA`、`CSA`、`GMRA` |
| `fieldName` | string | true | 业务字段，如 `minimum transfer amount`、`MTA`、`Rounding`、`Valuation Agent` |

LLM 抽参只做 raw span 提取，不做归一化。所有归一化在 executor 内完成。

A 档命中时的文本响应必须确定性可复现：

```text
Part 1:
Minimum Transfer Amount: US$250,000.00

Part 2:
Source: OTCD - ISDA & CSA in same doc.pdf, P23, source_chunk_id=demo-hsbc-isda-csa-mta-p23
Original Text: "Minimum Transfer Amount" means with respect to Party A: US$250,000.00 ...
```

下游 prompt 只消费 `MCPResponse.textResult`，不假设结构化字段。

## 四档文案分流

槽位分流逻辑发生在 MCP 分支入口、参数抽取之后、调 executor 之前。

| 档 | 槽位状态（按 raw nonblank 计数） | 命中 seed | 是否调 executor | 输出 |
| --- | --- | --- | --- | --- |
| A | 三槽位齐（counterparty + agreementType + fieldName 均非空） | 命中协议 + 命中字段 | 是 | `Part 1` 字段值 + `Part 2` 来源（含 `docName / page / source_chunk_id / sourceText`） |
| B | 三槽位齐 | 未命中协议 | 是（executor 自身判定） | `未找到与 [Agreement Type] + [Counterparty] 相关的文档` |
| B' | 三槽位齐 | 命中协议但未命中字段 | 是（executor 自身判定） | `已定位协议，但未找到字段 [fieldName] 的示例答案` |
| C | 1 或 2 个槽位 nonblank | — | 否 | `请补充更多信息以定位文档,例如:Please provide {missingSlots}`，按缺失槽位拼接 |
| D | 0 槽位 nonblank | — | 否 | 由 router 直接产出通用引导文本（见下） |

实现锚点：

- 槽位计数口径：`StringUtils.isNotBlank(value)` 即计为 1 个槽位。**不**在 router 层做 fieldName 同义词/seed 匹配过滤；router 只看 raw 是否非空。同义词归一化与未知字段判定全部在 executor 内做（这样 `unknown field` 仍被计为 3 槽位 → 进入 executor → 走 B' 档）。
- A / B / B' 由 `CollateralFieldLookupMCPExecutor` 自身判定：归一化后命中 seed 协议 + 命中字段 → A；命中协议未命中字段 → B'；未命中协议 → B。
- C 档由 `CollateralFieldLookupSlotRouter` 在调 executor 之前直接产出 `MCPResponse.text(...)`，不进 executor。C 档文案模板：

  ```text
  请补充更多信息以定位文档,例如:Please provide {missingSlots}
  ```

  `{missingSlots}` 按缺失槽位拼接英文标签 `Agreement Type` / `Counterparty` / `Field Name`，多个用 ` or ` 连接（保持 PRD 原文风格）。

- D 档同样由 `CollateralFieldLookupSlotRouter` 直接产出 `MCPResponse.text(...)`，**不**回流到 `IntentGuidanceService`（该服务只在 `RAGChatServiceImpl` 检索前的 ambiguity 检测路径上运行；MCP 参数抽取之后已无回路，返回空 MCP context 会落到通用 empty-context 回复，不是 guidance）。D 档固定文案：

  ```text
  请提供更具体的协议筛查条件，例如交易对手方（Counterparty）、协议类型（Agreement Type）以及您想查询的字段（Field Name）。
  ```

  D 档触发条件：LLM 抽参返回 `{}` 或全部字段空字符串。

- 我方别名回退（`华泰 / HT / Huatai / HUATAI CAPITAL INVESTMENT LIMITED`）发生在 executor 内：若抽出的 `counterparty` 命中我方别名，executor 用 `MCPRequest.userQuestion` 二次匹配 counterparty alias；二次仍失败时，executor 直接返回 C 档等价的"counterparty 槽位缺失"文案（即视为 counterparty 实际为空）。

## 静态 Seed 数据

主条目（PRD 样例）：

| Counterparty Aliases | Agreement Type Aliases | Field Aliases | Value | Source |
| --- | --- | --- | --- | --- |
| `HSBC`, `The HongKong and Shanghai Banking Corporation Limited` | `ISDA&CSA`, `ISDA & CSA`, `ISDA-CSA`, `ISDA+CSA` | `Minimum Transfer Amount`, `MTA`, `minimum transfer amount` | `US$250,000.00` | `OTCD - ISDA & CSA in same doc.pdf`, `P23`, `source_chunk_id=demo-hsbc-isda-csa-mta-p23` |

可选追加字段（手测 B/A 切换用，从 PRD 高频字段挑选）：

- `Rounding`
- `Eligible Collateral`
- `Regular Settlement Day`
- `Valuation Date Location`
- `Notification Time`
- `Resolution Time`
- `Interest Rate`
- `Interest Period`
- `Daily Interest Compounding`
- `Valuation Agent`

每条记录都带 `docName / page / sourceChunkId / sourceText` 四件套。

## 归一化规则

所有归一化在 `CollateralFieldLookupMCPExecutor` 内完成，禁止下沉到 LLM prompt。

- **counterpartyAliases**：trim + 大小写不敏感 + 空格归一化；命中 alias 即 alias 表登记的 canonical 名。
- **housePartyAliases**：识别我方别名 `华泰 / HT / Huatai / HUATAI CAPITAL INVESTMENT LIMITED`。若 LLM 抽出的 `counterparty` 命中我方别名，executor 回退到 `MCPRequest.userQuestion` 二次匹配已知 counterparty alias；二次匹配仍失败按 C 档处理（视为 counterparty 槽位缺失）。
- **agreementTypeNormalizer**：trim + uppercase；`ISDA&CSA / ISDA & CSA / ISDA-CSA / ISDA+CSA` 归一化为 `ISDA&CSA`。未知协议文本不猜，直接走 B 档。
- **fieldNameSynonyms**：`MTA → Minimum Transfer Amount` 等显式映射表。命中即映射，未命中保留原值供 B 档文案输出。
- 归一化失败不再二次调 LLM。

## 意图配置

新增一行 `t_intent_node`（schema 详见 `resources/database/schema_pg.sql:344`）：

| 列 | 值 |
| --- | --- |
| `id` | 实现时生成的 VARCHAR(20) ID |
| `kb_id` | `NULL`（GLOBAL 约定，**不存在 `scope` 列**；schema 仅有可空 `kb_id`，`NULL` 即全局） |
| `intent_code` | `mcp_collateral_field_lookup` |
| `name` | `Collateral 协议字段筛查` |
| `kind` | `2`（MCP，对应 `IntentKind.MCP.code`；schema 注释把 `kind` 写为 0/1 是过期说明，2 是当前 enum 实际值） |
| `mcp_tool_id` | `collateral_field_lookup` |
| `enabled` | `1` |
| `deleted` | `0` |
| `level / parent_code / sort_order` | 跟随当前全局意图树结构在实现时确定 |
| `examples` | JSON 数组，包含三条样例问题（见下） |
| `param_prompt_template` | 见下 |
| `prompt_template` | 见下 |

样例问题：

- `华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少`
- `HSBC 的 ISDA&CSA 下 Rounding 是多少`
- `华泰和HSBC交易的ISDA&CSA下的 Valuation Agent 是什么`

`param_prompt_template`：

```text
你是 Collateral 协议字段筛查的参数抽取器。
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
- 只输出 JSON，不输出解释。
```

`prompt_template`：

```text
你正在回答 Collateral 协议字段筛查问题。
若动态数据片段中包含 Part 1 和 Part 2：
- 必须保留 Part 1 / Part 2 两段结构。
- 必须逐字保留字段值、金额格式、页码、source_chunk_id 和原文片段。
- 不要把金额换算、翻译、合并或改写。
- 不要补充动态数据片段之外的协议结论。
若动态数据片段是 miss 文案（B/C 档）：
- 直接原样返回该文案，不要补充推测内容。
```

DB migration：`resources/database/upgrade_v1.11_to_v1.12.sql`，单条 INSERT。

## SSE 帧序

帧序由现有 4 层 sources 闸门和 `RAGChatServiceImpl` 的同步 emit 段联合保证，**不写新代码**，仅作为验收口径。

- COB / SOP（KB-only）：`META → SOURCES → MESSAGE+ → FINISH → (SUGGESTIONS) → DONE`。
- Collateral 命中 MCP（A / B / B' / C / D 档）：`META → MESSAGE+ → FINISH → DONE`。
  - 全部五档统一不发 SOURCES 帧。A/B/B' 由 executor 产文本、C/D 由 router 产文本，所有路径都没有 KB chunks。
  - 第 2 层闸门 `hasRelevantKbEvidence = !distinctChunks.isEmpty() && maxScore ≥ min-top-score` 为 false → 不调 `SourceCardBuilder`，不发 SOURCES 帧。
  - 不发空 SOURCES 帧，前端 `onSources` 必须保持可选处理；MVP 收尾必须手工 smoke 一次缺 SOURCES 的 SSE 渲染，确认消息体不卡。
- 双路（MCP + KB 同时命中）边界：MVP 不主动追求该路径，但若意外触发，帧序回退到 KB-only 形态 `META → SOURCES → MESSAGE+ → FINISH → DONE`，由现有 mixed 模板 `answer-chat-mcp-kb-mixed.st` 处理；不算 MVP bug。

## 双路边界

`RetrievalEngine` 架构层支持 MCP + KB 双路并行。MVP 不依赖也不阻断该路径：

- MVP seed 设计上保证 Collateral 字段筛查问题强命中 MCP intent，KB 通道分数低于 `INTENT_MIN_SCORE`。
- 普通 Collateral 知识问题（如"什么是 ISDA"）只命中 KB 通道，MCP intent 分数不过门。
- 验收要对 A 档加一条负向断言：SSE 流不含 `event: sources`、KB 通道日志 `intentChunks=0`，确认未意外触发双路。
- 若调阈值或换 LLM 后双路命中，回退到 KB-only 帧序由现有 mixed 模板回答，不写新代码。

## 错误处理

`CollateralFieldLookupMCPExecutor` 对正常 miss 用业务文本而非异常：

- 三槽位齐 + 未命中协议（B 档）：`未找到与 [Agreement Type] + [Counterparty] 相关的文档`。
- 三槽位齐 + 命中协议但缺字段（B' 档）：`已定位协议，但未找到字段 [fieldName] 的示例答案`。
- 不可恢复异常：返回 `MCPResponse.error(...)`，由现有 MCP 路径上抛失败信息。

C / D 档由 `CollateralFieldLookupSlotRouter` 直接产出 `MCPResponse.text(...)`，**不**进 executor，**不**回流 `IntentGuidanceService`。executor 不感知 C / D。

## 安全与权限边界

MVP 接受以下风险，理由是只查静态 seed：

- 意图节点 `kb_id = NULL`（GLOBAL），分类器在 COB / SOP KB 下也可能曝光 Collateral MCP intent。
- MCP 路径不经 `AuthzPostProcessor` 等 KB 后置处理器。
- 不强制 KB-scoped 意图过滤。

**已接受风险（明确写入 spec）**：在 COB 或 SOP KB 下若用户问 `HSBC 的 ISDA&CSA 下 MTA 是多少` 这类典型 Collateral 问题，分类器**有可能**仍命中 `mcp_collateral_field_lookup` 并返回 A 档答案。MVP 不阻断该跨 KB 命中。验收阶段必须人工确认业务方对此可接受。

**Fallback：若业务方不接受跨 KB 命中**（不在默认 MVP 范围，业务方明确拒绝时再做）：

- 当前 router 设计点位于 `RetrievalEngine` 的 MCP 分支（参数抽取之后），手头直接可用的是 `RetrievalScope`，没有 `RAGChatServiceImpl` 的上下文，`MCPRequest` 也没有 `kbId` 字段。
- 实施步骤：
  1. 在 `MCPRequest` 增加 `String activeKbId` 字段（或在 `CollateralFieldLookupSlotRouter` 入参里带 `RetrievalScope` / `String activeKbId`）。
  2. `RetrievalEngine` 调 router 时把 `scope.targetKbId()`（或同名访问器）传入。
  3. router 在分流前判断：若 `activeKbId != Collateral KB ID`，直接转为 D 档 router 文案（保持 SSE 帧序与现有路径一致）。
- 这是几行的小改，但不是"router 加一行 if"那么简单——需要碰 `MCPRequest` 字段或 router 签名。不修改意图存储结构、不动权限模型。Collateral KB ID 来源由实施时确定（配置项 / DB 查询），不在本 spec 锁定。

风险缓释：

- 工具描述与 examples 强 Collateral 化。
- 工具仅查静态 seed，不接 OpenSearch / Share-folder / 外部系统。
- B / B' / C / D 档明确 miss 文案，杜绝幻觉跨 KB 答案。

**硬前置门**（在替换静态 seed 为任何真实数据之前必须先做）：

- 实现 KB-scoped 意图过滤（按 `activeKbId` 限制 Collateral MCP intent 仅在 Collateral KB 生效）。
- 在 executor 路径上对齐 `framework.security.port.KbReadAccessPort` 校验当前用户 KB 读权限。

## 验收

### 自动化测试

- `CollateralFieldLookupMCPExecutor` 单测：精确命中（A）、counterparty alias（A）、agreement type 归一化（A）、field 同义词 `MTA → Minimum Transfer Amount`（A）、我方别名回退后二次匹配成功（A）、我方别名回退后二次仍失败（→ counterparty 缺失文案）、未命中协议（B）、命中协议但未命中字段含 `unknown field`（B'）。
- `CollateralFieldLookupSlotRouter` 单测：3 槽位 nonblank → 调 executor（覆盖 raw `unknown field` 仍计为 3 槽位、进 executor 后落 B'）；2 槽位 → C 档拼接缺失槽位英文标签；1 槽位 → C 档拼接 2 个缺失槽位；0 槽位 → router 直接返回 D 档固定文案（**不**调 `IntentGuidanceService`）。
- 工具注册测试：`DefaultMCPToolRegistry` 自动注册 `collateral_field_lookup`，`MCPTool` 暴露 3 个必填参数。
- 集成测试（fake `LLMService`）：从 DB 加载 `mcp_collateral_field_lookup` 节点，触发抽参路径，断言 prompt 含 DB 提供的 `param_prompt_template`，返回确定性 JSON 后 `MCPRequest.parameters` 含 `counterparty=HSBC`、非空 `agreementType` 与 `fieldName`。

### 手工验收脚本

| # | KB | 问题 | 预期结果 |
| --- | --- | --- | --- |
| 1 | COB | 5 个高频问题（取 FAQ） | KB-only RAG，SSE 含 SOURCES 帧 |
| 2 | SOP | 5 个高频问题（取 FAQ） | KB-only RAG，SSE 含 SOURCES 帧 |
| 3 | Collateral | `华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少` | A 档：含 `US$250,000.00` / `P23` / `source_chunk_id`，**无 SOURCES 帧**，KB 通道 `intentChunks=0` |
| 4 | Collateral | `HSBC 的 ISDA&CSA 下 MTA 是多少` | A 档：`MTA → Minimum Transfer Amount` 同义命中 |
| 5 | Collateral | `华泰和HSBC交易的GMRA下的 minimum transfer amount 是多少` | B 档：`未找到与 GMRA + HSBC 相关的文档` |
| 6 | Collateral | `华泰和HSBC交易的ISDA&CSA下的 unknown field 是什么` | B' 档：`已定位协议，但未找到字段 unknown field 的示例答案`（验证 raw 计数让 3 槽位进 executor） |
| 7 | Collateral | `HSBC 的 MTA 是多少`（缺 agreement type） | C 档：`请补充更多信息以定位文档,例如:Please provide Agreement Type` |
| 8 | Collateral | `MTA 是多少`（仅 1 槽位） | C 档：`请补充更多信息以定位文档,例如:Please provide Agreement Type or Counterparty` |
| 9 | Collateral | `这个协议是什么意思` | D 档：router 直接返回固定通用引导文案，不回流 `IntentGuidanceService` |
| 10 | COB / SOP | 5 个高频问题在非 Collateral KB 下 | 不触发 `mcp_collateral_field_lookup`，分数 < `INTENT_MIN_SCORE` |
| 11 | Collateral | A 档问题 | 浏览器 DevTools EventStream 验证：无 `event: sources` 帧，消息体正常渲染、`onFinish` 正常触发，前端不卡 |
| 12 | COB / SOP | `HSBC 的 ISDA&CSA 下 MTA 是多少`（典型 Collateral 问题问到非 Collateral KB） | 业务方人工裁决：**允许**返回 MCP A 档答案 → MVP 通过；**不允许** → 启动 Security 节"Fallback"步骤（`MCPRequest`/router 签名增加 `activeKbId`，由 `RetrievalEngine` 注入 `scope.targetKbId()`，router 在 Collateral KB ID 不匹配时转 D 档） |

第 10 项若失败，先强化 Collateral intent description / examples / `param_prompt_template`；仍不稳定，推迟实际上线，等 Phase 2 KB-scoped 意图过滤上线后再开。

第 12 项是已接受风险的人工验收门：本身不会 fail MVP 代码，但裁决结果决定是否启动"Fallback：activeKbId gate"（见 Security 节）。

## 实施顺序

1. 新增 `CollateralSeedData`（静态数据）。
2. 新增 `CollateralFieldLookupMCPExecutor`（含归一化、A/B/B' 档判定、`MCPTool` 定义）。
3. 新增 `CollateralFieldLookupSlotRouter`（raw 槽位计数、3 槽位 → executor、1–2 槽位 → C 档文案、0 槽位 → D 档固定文案；router 内部完成全部分流，**不**回流 `IntentGuidanceService`）。
4. 接入 MCP 分支：在 `LLMMCPParameterExtractor` 之后调用 router；A/B/B' 走 executor，C/D 由 router 直接产 `MCPResponse.text(...)`。
5. 写 `upgrade_v1.11_to_v1.12.sql`，插入意图节点（`kb_id=NULL`、`kind=2`、`mcp_tool_id=collateral_field_lookup`）。
6. executor / router 单测、registry 测试、fake-LLM 集成测试。
7. 手工验收 1–12，重点 smoke 第 11 项（缺 SOURCES 的 SSE 渲染），第 12 项触发业务方人工裁决。
8. COB / SOP 回归 5 题，确认无误路由。

## Future Work

### Phase 1.5（可选增强）

- 给 Collateral KB 启用 ingestion pipeline，配置 `Parser → Chunker → Enricher → Indexer`，禁用 `CONTEXT_ENHANCE` 改写正文。
- 增加 Collateral 专用 chunk 检索头（`counterparty / agreement / field / page`）提高召回，但字段答案仍由 MCP 提供。

### Phase 2（接真实数据前的硬前置）

- KB-scoped 意图过滤：按 `activeKbId` 限制 `mcp_collateral_field_lookup` 仅在 Collateral KB 生效。
- 在 MCP executor 链路上对齐 `KbReadAccessPort`，校验当前用户读权限。
- 加 COB/SOP 误路由回归 gate。

### Phase 3（产品化）

- 新增协议元数据模型（如 `t_collateral_agreement`）。
- MCP 切换到 DB / OpenSearch 后端。
- 文档/分块附 page metadata，支持精确来源跳转。
- MCP 来源整合进 Sources 面板。
- OCR / Layout 流水线支持扫描件。
- 启动场景 3（协议智能提取）的结构化任务闭环。
