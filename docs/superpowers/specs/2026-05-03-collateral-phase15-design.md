# Collateral Phase 1.5 Design

- 日期：2026-05-03
- 状态：Draft
- 上游：`docs/superpowers/specs/2026-05-03-collateral-mcp-mvp-design.md`
- 目标 PR：Phase 1.5 PR

## 0. 定位

Phase 1 已经证明 `collateral_field_lookup` 本地 MCP executor 能在静态 seed 上返回确定性的 `Part 1 / Part 2` 文本。Phase 1.5 不接真实协议答案，不扩大权限面；它只补齐两个演示前必须稳定的工程点：

1. 修复 house-party combined span：LLM 抽出的 `counterparty` 可能是 `华泰和HSBC` / `Huatai and HSBC`，不能把它当作真实对手方去查 seed。
2. 为 Collateral KB 准备 ingestion / retrieval 侧增强：可以上传 Collateral 文档做普通 KB 检索背景，但字段答案仍由 MCP seed 提供。

Phase 1.5 的成功标准是“演示链路更稳”，不是“真实协议数据上线”。

## 1. Scope

### In Scope

- 修复 `CollateralFieldLookupMCPExecutor.resolveEffectiveCounterparty(...)` 对 combined party span 的处理。
- 在 `CollateralSeedData` 增加可复用的 alias 文本扫描能力，避免 executor 硬编码 `hsbc`。
- 增加回归测试覆盖：
  - `counterparty=华泰和HSBC` + `userQuestion` 含 HSBC → A 档；
  - `counterparty=Huatai and HSBC` + `userQuestion` 含 HSBC → A 档；
  - `counterparty=华泰和未知对手方` → counterparty 缺失文案或 B 档，不能误命中 HSBC。
- 新增 Collateral ingestion pipeline preset 的 seed / 文档化配置：
  - `Parser -> Chunker -> Enricher -> Indexer`；
  - 不启用 `EnhancerNode` 的 `CONTEXT_ENHANCE` 正文改写；
  - chunk header 保守补充 `counterparty / agreement / field / page`，仅提高 KB 检索可读性，不作为 MCP 答案源。
- 保留现有 SSE 口径：MCP-only A/B/B'/C/D 不发 `SOURCES`。

### Out of Scope

- 不把 MCP executor 从静态 seed 切到 DB / OpenSearch。
- 不做 KB-scoped intent 过滤，这属于 Phase 2。
- 不在 executor 内读取真实文档、真实 chunk 或外部系统。
- 不新增 Sources 面板里的 MCP source card。
- 不做完整阶段 H 的 `t_kb_mcp_binding` / 管理后台。
- 不做 OCR / layout 版面解析；Phase 1.5 只消费当前 parser 能拿到的文本层。

## 2. Counterparty Combined Span 修复

当前 Phase 1 逻辑：

```text
if raw nonblank && !isHouseParty(raw):
  return raw
else:
  scan userQuestion for HSBC alias
```

问题是 `isHouseParty(raw)` 只做 exact alias。`华泰和HSBC` / `Huatai and HSBC` 不是 exact house alias，于是被直接返回，随后 `findAgreement("华泰和HSBC", ...)` miss，导致 B 档。

Phase 1.5 改为：

```text
resolveEffectiveCounterparty(raw, userQuestion):
  if raw is blank:
    return scanKnownCounterpartyAlias(userQuestion)

  if raw exactly resolves to known counterparty:
    return raw

  if raw contains any house-party alias:
    return scanKnownCounterpartyAlias(raw) or scanKnownCounterpartyAlias(userQuestion)

  return raw
```

关键点：

- “contains house alias” 只用于触发 fallback，不直接做权限或业务判断。
- `scanKnownCounterpartyAlias(...)` 必须来自 `CollateralSeedData`，不要在 executor 里继续硬编码 `hsbc`。
- 如果 raw 同时包含 house alias 和 known counterparty alias，优先从 raw 自身恢复 counterparty，避免 userQuestion 被 query rewrite 改坏时失效。
- 如果只包含 house alias 且找不到 known counterparty，返回 `null`，沿用现有 counterparty 缺失文案。

## 3. Collateral Pipeline Preset

Phase 1.5 可以新增一个示例 / seed pipeline，但不要求用户必须切 pipeline 模式才能使用 MCP。

推荐 pipeline：

```text
fetcher-1(fetcher)
  -> parser-1(parser)
  -> chunker-1(chunker: fixed_size or structure_aware)
  -> enricher-1(enricher: metadata/keywords only, optional)
  -> indexer-1(indexer)
```

明确不使用：

```text
enhancer-1(enhancer: context_enhance)
```

原因：Collateral 协议条款对金额、阈值、日期、定义文本敏感。文档级正文改写会提高语义召回，但会破坏“原文证据可复核”的产品目标。Phase 1.5 只允许结构化 header / metadata 辅助召回，不改正文。

chunk header 示例：

```text
[Collateral]
Counterparty: HSBC
Agreement Type: ISDA&CSA
Field Hint: Minimum Transfer Amount
Page: P23

<original chunk text>
```

约束：

- header 可作为 KB 检索辅助信息；
- MCP A 档答案仍来自 seed；
- 不允许把 header 当作真实字段答案；
- Phase 2 之前不要把真实协议字段值接入 MCP executor。
- 如果协议是扫描件或图片型 PDF，Phase 1.5 不承诺可检索质量；OCR / layout 进入全景 roadmap 的 Phase 2.5。

## 4. 验收

自动化测试：

- `CollateralFieldLookupMCPExecutorTest`
  - `execute_combinedChineseHouseAndCounterpartySpan_recoversCounterparty`
  - `execute_combinedEnglishHouseAndCounterpartySpan_recoversCounterparty`
  - `execute_combinedHouseAndUnknownCounterparty_doesNotRecoverHsbc`
- `CollateralSeedDataTest`
  - `findKnownCounterpartyAliasInText_matchesAliasInsideLongSpan`
  - `containsHousePartyAlias_matchesAliasInsideLongSpan`

手工验收：

| # | 问题 | LLM 抽参模拟 | 预期 |
| --- | --- | --- | --- |
| 1 | `华泰和HSBC交易的ISDA&CSA下的 MTA 是多少` | `counterparty=华泰和HSBC` | A 档，返回 `US$250,000.00` |
| 2 | `Huatai and HSBC ISDA&CSA MTA` | `counterparty=Huatai and HSBC` | A 档 |
| 3 | `华泰和ABC交易的ISDA&CSA下的 MTA 是多少` | `counterparty=华泰和ABC` | 不得误命中 HSBC |
| 4 | Collateral KB 普通知识问题 | 无 MCP 命中 | 走 KB-only，SOURCES 正常 |
| 5 | Collateral MCP A 档问题 | MCP 命中 | 不发 SOURCES，消息不卡 |

## 5. 非目标风险

Phase 1.5 后仍然接受 Phase 1 的跨 KB 风险：COB / SOP 下问典型 Collateral 字段问题，仍可能命中全局 MCP intent。这个风险由 Phase 2 关闭。
