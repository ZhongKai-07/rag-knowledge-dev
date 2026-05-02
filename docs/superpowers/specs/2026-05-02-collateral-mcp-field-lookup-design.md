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

## Architecture

MVP 复用现有 RAG + MCP 执行链：

1. `RAGChatServiceImpl` 接收用户问题。
2. `DefaultIntentClassifier` 使用全局意图树做分类。
3. 命中 Collateral MCP 意图后，`RetrievalEngine` 走 MCP 分支。
4. `LLMMCPParameterExtractor` 根据工具定义和节点级 `paramPromptTemplate` 抽取参数。
5. `DefaultMCPToolRegistry` 找到本地 `CollateralFieldLookupMCPExecutor`。
6. Executor 查静态示例数据并返回文本结果。
7. MCP 结果进入 `RetrievalContext.mcpContext`，再由现有 prompt 链路生成最终回答。

第一期选择本地 executor，而不是独立 `mcp-server`，原因是本地 executor 会被 Spring 自动注册，能最快验证链路，也避免服务间鉴权、用户上下文传递和远程超时问题。

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

Tool response is text-first for MVP, because current MCP integration feeds `textResult` into the RAG prompt. The text should be deterministic:

```text
Part 1:
Minimum Transfer Amount: US$250,000.00

Part 2:
Source: OTCD - ISDA & CSA in same doc.pdf, P23, source_chunk_id=demo-hsbc-isda-csa-mta-p23
Original Text: "Minimum Transfer Amount" means with respect to Party A: US$250,000.00 ...
```

The executor may also keep internal structured data, but no downstream structured contract is required for MVP.

## Static Data

MVP seed data contains at least the PRD sample:

| Counterparty Alias | Agreement Type | Field | Value | Source |
| --- | --- | --- | --- | --- |
| `HSBC`, `The HongKong and Shanghai Banking Corporation Limited` | `ISDA&CSA` | `minimum transfer amount`, `MTA` | `US$250,000.00` | `OTCD - ISDA & CSA in same doc.pdf`, `P23` |

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
- agreementType 是协议类型，只能抽取 ISDA、CSA、ISDA&CSA、GMRA 或用户原文中的近似表达。
- fieldName 是用户想查询的业务字段，保留英文原词或中英文混合原词。
- 不确定的字段返回空字符串。
- 只输出 JSON，不输出解释。
```

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

Risk mitigation in MVP:

- Tool description and examples are strongly Collateral-specific.
- Tool only answers known static Collateral sample data.
- Tool does not access unrestricted documents or external systems.
- Tool returns explicit miss text instead of falling back to unrelated data.

Future production hardening:

- Move the intent node under a KB-scoped intent tree or add runtime filtering by `activeKbId`.
- Replace static data with a Collateral agreement metadata index.
- Enforce current-user KB read permission before answering.

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

Focused automated tests:

- Unit test `CollateralFieldLookupMCPExecutor` for exact match, alias match, field synonym match, missing parameter, no agreement, and no field.
- Unit test tool definition exposes required parameters.
- Optional integration test confirms `DefaultMCPToolRegistry` auto-registers the local executor.

## Future Work

- Add `t_collateral_agreement` or equivalent metadata model.
- Add document/chunk page metadata for source display.
- Add KB-scoped intent loading so Collateral MCP does not appear in COB/SOP chats.
- Replace static map with OpenSearch / DB-backed lookup.
- Add OCR/Layout pipeline for scanned agreements and precise original-document jump.
