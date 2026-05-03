-- v1.11 → v1.12：新增 Collateral 字段筛查 MCP 意图节点（GLOBAL）
-- 见 spec：docs/superpowers/specs/2026-05-03-collateral-mcp-mvp-design.md
-- 注意：上线后必须清 Redis key `ragent:intent:tree`，否则缓存仍是旧意图树
--   docker exec redis redis-cli -a 123456 DEL ragent:intent:tree

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
