# Collateral MCP Field Lookup MVP Design

## Context

OPS Collateral 场景 2 需要支持协议字段筛查问答。典型问题是：

> 华泰和 HSBC 交易的 ISDA&CSA 下的 minimum transfer amount 是多少？

MVP 目标是先跑通现有 RAG 系统中的 MCP 调用链路，不重构意图管理范围，不引入部门级或知识库级意图治理，不处理 OCR 和精确坐标。第一阶段只验证：

- 全局意图树能命中 Collateral 字段筛查 MCP 意图。
- LLM 参数提取能得到 `counterparty`、`agreementType`、`fieldName`。
- MCP 工具能返回字段值、原文片段和粗粒度来源。
- Chatbot 能用 PRD 要求的 `Part 1 + Part 2` 形式回答。

## Scope

### In Scope

- 在 `bootstrap` 模块实现一个本地 MCP executor：`collateral_field_lookup`。
- 在全局意图树中配置一个 MCP 类型节点，绑定 `mcp_tool_id=collateral_field_lookup`。
- 使用 PRD 中 HSBC / ISDA&CSA 示例数据作为第一版静态数据源。
- 返回粗粒度来源：`docName`、`page`、`sourceChunkId`、`sourceText`。
- 缺少参数、找不到字段、找不到协议时返回明确的业务错误文本，由 Chatbot 展示给用户。

### Out of Scope

- 不调整意图管理为部门级或 KB 级配置。
- 不新增协议元数据表。
- 不做扫描件 OCR。
- 不做 PDF bbox、行号级、坐标级跳转。
- 不修改普通 COB/SOP RAG 链路。
- 不接入真实 Share-folder 或自动文件监听。
- MVP 不让 MCP 结果进入前端 Sources 面板。`SourceCard` 仍只服务 KB 检索路径，MCP 来源信息只通过 `textResult` 中的 `Part 2:` 段落以正文形式呈现给用户。Sources 面板与 MCP 的整合属于后续阶段。

## Architecture

MVP 复用现有 RAG + MCP 执行链：

1. `RAGChatServiceImpl` 接收用户问题。
2. `DefaultIntentClassifier` 使用全局意图树做分类。
3. 命中 Collateral MCP 意图后，`RetrievalEngine` 走 MCP 分支。
4. `LLMMCPParameterExtractor` 根据工具定义和节点级 `paramPromptTemplate` 抽取参数。
5. `DefaultMCPToolRegistry` 找到本地 `CollateralFieldLookupMCPExecutor`。
6. Executor 查静态示例数据并返回文本结果。
7. MCP 结果进入 `RetrievalContext.mcpContext`，再由现有 prompt 链路生成最终回答。

第一期选择本地 executor，而不是独立 `mcp-server`，原因是本地 executor 会被 Spring 自动注册，能最快验证链路，也避免服务间鉴权、用户上下文传递和远程超时问题。另一个关键原因是当前下游 prompt 路径只消费 `MCPResponse.textResult`，没有结构化数据通道；在这个限制下，本地 executor 与远程 MCP Server 信息上等价，但部署和调试成本更低。

Concrete classes for MVP:

- `com.knowledgebase.ai.ragent.rag.core.mcp.executor.CollateralFieldLookupMCPExecutor`
- `com.knowledgebase.ai.ragent.rag.core.mcp.executor.CollateralSeedData`

The executor may define its `MCPTool` inline. A separate `CollateralFieldLookupTool` class is optional and should only be added if the executor becomes too large.

## MCP Tool Contract

Tool ID:

```text
collateral_field_lookup
```

Parameters:

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `counterparty` | string | true | 交易对手方，例如 `HSBC`、`The HongKong and Shanghai Banking Corporation Limited` |
| `agreementType` | string | true | 协议类型，例如 `ISDA&CSA`、`ISDA`、`CSA`、`GMRA` |
| `fieldName` | string | true | 业务字段，例如 `minimum transfer amount`、`rounding` |

The LLM extractor returns raw text. All canonicalization happens inside `CollateralFieldLookupMCPExecutor`; the prompt should not be trusted to normalize aliases.

Tool response is text-first for MVP, because current MCP integration feeds `textResult` into the RAG prompt. The text should be deterministic:

```text
Part 1:
Minimum Transfer Amount: US$250,000.00

Part 2:
Source: OTCD - ISDA & CSA in same doc.pdf, P23, source_chunk_id=demo-hsbc-isda-csa-mta-p23
Original Text: "Minimum Transfer Amount" means with respect to Party A: US$250,000.00 ...
```

The executor may also keep internal structured data, but no downstream structured contract is required for MVP.

Prompt coupling:

- The intent node should include a `prompt_template` for MCP-only answers.
- When `## 动态数据片段` contains content beginning with `Part 1:` / `Part 2:`, the answer must preserve those two sections, the numeric value formatting, and identifiers such as `source_chunk_id` without rewriting, translating, summarizing, or omitting them.
- MVP should prefer the intent node `prompt_template`, because `RAGPromptService.planMcpOnly()` already supports a single MCP intent template. Changing `RAGPromptService` is not required for MCP-only MVP.

## Static Data

MVP seed data contains at least the PRD sample:

| Counterparty Alias | Agreement Type | Field | Value | Source |
| --- | --- | --- | --- | --- |
| `HSBC`, `The HongKong and Shanghai Banking Corporation Limited` | `ISDA&CSA` | `minimum transfer amount`, `MTA` | `US$250,000.00` | `OTCD - ISDA & CSA in same doc.pdf`, `P23` |

## Normalization

Normalization belongs in the executor, not in the LLM prompt. `LLMMCPParameterExtractor` should be treated as a raw span extractor.

Rules:

- `counterpartyAliases`: map canonical counterparty names to aliases. Matching is case-insensitive after trimming and whitespace normalization. MVP should support exact alias match and conservative contains match against known aliases, for example `HSBC` and `The HongKong and Shanghai Banking Corporation Limited`.
- `housePartyAliases`: recognize our side aliases such as `华泰`, `HT`, `Huatai`, and `HUATAI CAPITAL INVESTMENT LIMITED`. If the extracted `counterparty` is a house-party alias, the executor should fall back to `MCPRequest.userQuestion` and try to match a known counterparty alias from the original question.
- `agreementTypeNormalizer`: trim spaces, uppercase, and treat `ISDA&CSA`, `ISDA & CSA`, `ISDA-CSA`, and `ISDA+CSA` as equivalent. Unknown agreement text should remain unmatched instead of being guessed.
- `fieldNameSynonyms`: map canonical field names to aliases. MVP must support `MTA` -> `minimum transfer amount`.
- If normalization fails, return the relevant miss message rather than calling LLM again.

Optional additional fields can be added from the PRD high-frequency list if useful for manual testing:

- `rounding`
- `Eligible collateral`
- `Regular Settlement Day`
- `Valuation Date Location`
- `Notification Time`
- `Resolution Time`
- `Interest Rate`
- `Interest Period`
- `Daily Interest Compounding`
- `Valuation Agent`

## Intent Configuration

Add one global MCP intent node:

- Name: `Collateral 协议字段筛查`
- Kind: `MCP`
- `mcp_tool_id`: `collateral_field_lookup`
- Examples:
  - `华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少`
  - `HSBC 的 ISDA&CSA 下 Rounding 是多少`
  - `华泰和HSBC交易的ISDA&CSA下的 Valuation Agent 是什么`

Parameter prompt template should force JSON-only extraction:

```text
你是 Collateral 协议字段筛查的参数抽取器。
从用户问题中只提取以下 JSON 字段：
{
  "counterparty": "...",
  "agreementType": "...",
  "fieldName": "..."
}

规则：
- counterparty 是交易对手方，可能是简称，例如 HSBC，也可能是完整机构名。
- counterparty 必须是用户问题中"我方/华泰/HT/Huatai"以外的交易对手方；若问题只提到一方，该方即 counterparty。
- 示例："华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少" -> {"counterparty":"HSBC","agreementType":"ISDA&CSA","fieldName":"minimum transfer amount"}
- 示例："HSBC 的 ISDA & CSA 下 MTA 是多少" -> {"counterparty":"HSBC","agreementType":"ISDA & CSA","fieldName":"MTA"}
- agreementType 抽取用户原文中的协议类型表达，不要自行归一化；例如 ISDA&CSA、ISDA & CSA、ISDA-CSA、ISDA+CSA、ISDA、CSA、GMRA。
- fieldName 是用户想查询的业务字段，保留英文原词或中英文混合原词，不要自行归一化。
- 不确定的字段返回空字符串。
- 只输出 JSON，不输出解释。
```

Answer prompt template for this intent:

```text
你正在回答 Collateral 协议字段筛查问题。
若动态数据片段中包含 Part 1 和 Part 2：
- 必须保留 Part 1 / Part 2 两段结构。
- 必须逐字保留字段值、金额格式、页码、source_chunk_id 和原文片段。
- 不要把金额换算、翻译、合并或改写。
- 不要补充动态数据片段之外的协议结论。
```

Database migration:

- Add `resources/database/upgrade_v1.11_to_v1.12.sql`.
- Insert one `t_intent_node` row for the MCP node:
  - `intent_code`: `mcp_collateral_field_lookup`
  - `name`: `Collateral 协议字段筛查`
  - `kind`: `2`
  - `mcp_tool_id`: `collateral_field_lookup`
  - `examples`: JSON array containing the sample questions above
  - `param_prompt_template`: the parameter prompt above
  - `prompt_template`: the answer prompt above
  - `enabled`: `1`
  - `deleted`: `0`
  - `level`, `parent_code`, and `sort_order`: follow the current global tree structure chosen during implementation.

## Error Handling

The executor should return business-readable text rather than throwing for normal misses.

- Missing parameter:
  - `请补充更多信息以定位协议，例如 Agreement Type、Counterparty 或业务字段。`
- No matching agreement in static data:
  - `未找到与 [Agreement Type] + [Counterparty] 相关的示例协议。`
- No matching field:
  - `已定位协议，但未找到字段 [fieldName] 的示例答案。`
- Unexpected exception:
  - Return `MCPResponse.error(...)` so the existing MCP path can surface the failure.

## Security And Scope Notes

Because MVP keeps the intent node global, the classifier may surface this MCP intent outside the Collateral knowledge space. This is accepted only for chain validation.

MCP responses do not pass through KB retrieval post-processors such as `AuthzPostProcessor`. The MVP is acceptable only because it serves a tiny static seed dataset and does not query real documents, OpenSearch, Share-folder, or external systems.

Risk mitigation in MVP:

- Tool description and examples are strongly Collateral-specific.
- Tool only answers known static Collateral sample data.
- Tool does not access unrestricted documents or external systems.
- Tool returns explicit miss text instead of falling back to unrelated data.

Hard gate before real data:

- Before replacing static seed data with any real agreement metadata or document lookup, implement KB scoping or runtime filtering by `activeKbId`.
- Before replacing static seed data with any real agreement metadata or document lookup, enforce current-user KB read permission in the executor path, aligned with `framework.security.port.KbReadAccessPort`.

## Testing

Manual test questions:

1. `华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少`
   - Expected: MCP intent selected, parameters extracted, answer contains `US$250,000.00`, `P23`, and `source_chunk_id`.
2. `HSBC 的 ISDA&CSA 下 MTA 是多少`
   - Expected: synonym `MTA` maps to `minimum transfer amount`.
3. `HSBC 的 GMRA 下 minimum transfer amount 是多少`
   - Expected: no matching agreement message.
4. `华泰和HSBC交易的ISDA&CSA下的 unknown field 是什么`
   - Expected: matching agreement but no matching field message.
5. Normal SOP/COB question
   - Expected: ideally normal RAG path. If classifier routes to Collateral MCP due global MVP config, executor should return a miss instead of unrelated answer.

Regression baseline for global-intent pollution:

- Select at least five existing COB/SOP high-frequency questions from available manual cases or FAQ examples.
- Verify the Collateral MCP intent score stays below `INTENT_MIN_SCORE` and does not enter the MCP execution branch.
- If any question is misrouted, first strengthen the Collateral intent description/examples and parameter prompt. If that remains unstable, defer production use until a per-intent threshold or KB-scoped intent filter exists.

Focused automated tests:

- Unit test `CollateralFieldLookupMCPExecutor` for exact match, alias match, field synonym match, missing parameter, no agreement, and no field.
- Unit test tool definition exposes required parameters.
- Optional integration test confirms `DefaultMCPToolRegistry` auto-registers the local executor.
- Integration test with fake `LLMService`: load the intent node from DB, trigger the MCP parameter extraction path with the PRD sample question, assert the prompt includes the DB-provided `param_prompt_template`, then return deterministic JSON and assert the resulting `MCPRequest.parameters` contain `counterparty=HSBC`, non-empty `agreementType`, and non-empty `fieldName`.

## Future Work

Phase 2 (hard prerequisite before real data):

- Move the Collateral MCP intent node under a KB-scoped intent tree or add runtime filtering by `activeKbId`.
- Enforce current-user KB read permission in the executor path.
- Add a regression gate to prevent Collateral MCP from misrouting COB/SOP questions.

Phase 3 (after Phase 2):

- Add `t_collateral_agreement` or equivalent metadata model.
- Replace static map with DB / OpenSearch-backed lookup.
- Add document/chunk page metadata for source display.
- Integrate MCP-derived source records with the Sources panel if the product requires it.
- Add OCR/Layout pipeline for scanned agreements and precise original-document jump.
