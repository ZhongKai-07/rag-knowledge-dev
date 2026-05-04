# Collateral 场景2 协议智能筛查全景 Roadmap

- 初版日期：2026-05-03
- 修订日期：2026-05-04
- 状态：Draft
- 目标：防止后续围绕 Collateral 场景2的开发漂移，明确每个阶段的业务目标、工程边界、硬前置与退出标准。

## 0. 一句话方向

场景2不是普通 KB 问答，也不是 MCP 工具调用问题，而是“单 KB 入口下，证据可复核的协议字段查询与筛查工作流”：

```text
单 KB 问答入口
  -> 加载全局 SYSTEM 意图树 + 当前 KB 专属意图树
  -> 识别 SYSTEM / KB-LOCAL FAQ / KB-LOCAL Slot-Filled / MCP action
  -> QueryPlanner 抽取 Counterparty / Agreement Type / Field 等槽位
  -> RetrievalConstraints 注入检索 metadata filter / query hints
  -> 在授权 KB 内定位协议 chunk / 字段证据
  -> AnswerComposer 产出确定性答案档 + evidenceRefs
  -> 后续进入批量筛查 / 人工复核 / 导出
```

路线必须先把 Phase 1 的“查询型 MCP demo”拨回正确抽象：字段查询是 **slot-filled retrieval**，不是 action tool。MCP 在 Phase 2.4 后回归 actions only，保留给批量抽取、任务状态、导出等有副作用或异步语义的能力。

## 1. 核心架构决策

### 1.1 单 KB 入口是产品基线

当前产品形态已从“所有 KB 共用一个问答入口”调整为“一个 KB 一个问答入口”。因此：

- `RetrievalScope` 只表达权限边界：当前用户能不能读 active KB，以及能读到什么 security level。
- KB 检索不是必须由 `IntentKind.KB` 显式命中的 intent；在单 KB 入口下，它是默认 fallback 能力。
- 意图树的运行时加载范围是：全局 SYSTEM 节点 + 当前 KB 的本地业务节点。

### 1.2 全局意图树 + KB 专属意图树

目标治理模型：

| 层级 | 配置者 | 内容 | 约束 |
| --- | --- | --- | --- |
| 全局 SYSTEM 意图树 | SUPER_ADMIN / system admin | greeting / help / 系统引导 / 平台级命令 | 不承载业务字段查询；不绑定业务 MCP 查询工具 |
| KB 专属意图树 | KB owner / DEPT_ADMIN | FAQ / Slot-Filled intent / KB-local MCP actions | 必须写入 `kb_id`；只能在当前 KB 入口命中 |
| 隐式 fallback | 系统内置 | 默认 KB 检索 + 全局向量补充 | 不是 intent 节点，不需要管理端配置 |

Phase 2 使用最小运行时形态实现该模型：

- `t_intent_node.kb_id IS NULL` 表示 GLOBAL SYSTEM。
- `t_intent_node.kb_id = activeKbId` 表示 KB-LOCAL。
- 分类器加载 `GLOBAL + activeKbId`。
- 检索执行层再做 KB-scope guard。

Phase H 再把这套约定升级为完整治理平台：显式 `scope`、slot 配置、answer template、MCP action binding 和管理 UI。

### 1.3 查询与 action 分离

| 类型 | 示例 | 应走路径 |
| --- | --- | --- |
| 查询型字段定位 | “HSBC 的 ISDA&CSA 下 MTA 是多少” | QueryPlanner -> RetrievalConstraints -> Retrieval -> AnswerComposer |
| 系统引导 | “你能做什么” | SYSTEM intent -> 固定/LLM 引导 |
| 默认知识问答 | “这份协议主要内容是什么” | fallback KB retrieval -> 默认 prompt |
| action 工具 | “批量筛查这些协议并导出 Excel” | MCP action -> task/workflow |

Phase 1 的 `mcp_collateral_field_lookup` 是为了演示先跑通的临时路径，不是长期架构。

## 2. 阶段总览

| 阶段 | 新定位 | PR 边界 | 是否接真实协议答案 |
| --- | --- | --- | --- |
| Phase 1 | 静态 MCP demo，证明 PRD 答案形态 | 已完成 | 否 |
| Phase 1.5 | demo 稳定性 hotfix + alias/ingestion 资产沉淀 | PR-15 | 否 |
| Phase 2 | KB-scope 安全门 + Pre-Retrieval Query Planner 查询闭环 | PR-20.0 ~ PR-20.4 | 否，但可用真实文档 chunk 做受控验证 |
| Phase 2.5 | OCR / Layout 文档智能底座 | PR-DOCINT-1/2 | 否，但扫描件真实接入前必须完成 |
| Phase H | 全局意图树 + KB 专属意图树 + MCP action binding 治理平台 | PR-INT-1/2/3 | 否，治理能力 |
| Phase 3 | 真实协议字段数据源，接入 Composer / FieldLookupService | PR-BIZ-1/2 | 是 |
| Phase 4 | 批量智能筛查 workflow + MCP actions | PR-BIZ-3/4 | 是 |
| Phase 5 | Sources / evidence 产品化 | PR-UX-1/2 | 是 |

## 3. Phase 1 已完成：静态 MCP Demo

目标：

- `collateral_field_lookup` 本地 executor；
- `HSBC x ISDA&CSA x MTA` seed；
- A/B/B'/C/D 五档文案；
- MCP-only SSE 不发 `SOURCES`；
- 全局 `t_intent_node` 跑通分类 -> 抽参 -> executor -> `MCPResponse.textResult`。

已接受风险：

- MCP intent 是 `kb_id=NULL` 全局节点；
- COB / SOP 下可能误触发 Collateral MCP；
- executor 不校验 KB read permission；
- seed 静态，不接真实数据；
- 查询型 MCP 的抽象位置不正确，Phase 2.4 必须退役。

退出标准：

- 自动化测试全绿；
- 手工确认 A/B/B'/C/D；
- 业务方接受 demo 范围。

## 4. Phase 1.5：演示稳定性与入库增强

目标：

- 修复 `华泰和HSBC` / `Huatai and HSBC` combined span；
- 沉淀 house-party alias 排除、counterparty alias 扫描等资产；
- Collateral KB 可以上传文档并走 pipeline / 普通检索；
- 文档检索增强只辅助 KB 问答，不把真实字段答案接入 MCP executor。

设计文档：

- `docs/superpowers/specs/2026-05-03-collateral-phase15-design.md`

PR 内容：

- executor + seedData alias scanning 小修；
- pipeline preset / 示例配置；
- 不启用正文 `CONTEXT_ENHANCE`；
- 测试与手工 smoke。

退出标准：

- combined span 不再导致 B miss；
- Collateral 普通知识问题仍可走 KB-only；
- Collateral 字段问题在 Phase 2.4 前仍可走 MCP demo；
- alias / normalization 资产可迁移到 Phase 2.3 的 `CollateralSlotNormalizer`。

## 5. Phase 2：安全门 + Pre-Retrieval Query Planner 查询闭环

目标：

- 关闭 Phase 1 的跨 KB 误路由风险；
- 建立字段查询的正确主链路：`IntentResult -> QueryPlanner -> RetrievalConstraints -> Retrieval -> IntentAnswerComposer -> RAGPromptService`；
- 将 `collateral_field_lookup` 从 MCP 查询路径迁移为 KB-LOCAL Slot-Filled intent；
- 保证其他 KB 在 noop 路径下行为不变。

设计文档：

- `docs/superpowers/specs/2026-05-04-collateral-phase2-pre-retrieval-planner-design.md`

### 5.1 PR-20.0：KB-scope 安全门

内容：

- `IntentClassifier` / `IntentResolver` 支持 active KB；
- 分类器加载 `kb_id IS NULL OR kb_id = activeKbId`；
- intent tree cache 按 KB 分桶；
- 检索执行层再做 `node.kbId == null || node.kbId == scope.targetKbId` guard；
- migration `v1.12 -> v1.13` 将 Phase 1 的 `mcp_collateral_field_lookup` 从 GLOBAL 收窄到 Collateral KB；
- COB/SOP 误路由 CI gate。

约束：

- migration 不能硬编码未知环境的 Collateral KB id；必须按稳定标识查找，或在找不到目标 KB 时禁用旧 MCP intent。
- MCP 权限校验保留到 Phase 2.4 后，供 future actions 复用。

退出标准：

- COB/SOP KB 下不会执行 `collateral_field_lookup`；
- 无 active KB 或无 READ 权限时不会执行 MCP；
- Collateral KB 下 Phase 1 A/B/B'/C/D demo 行为保持。

### 5.2 PR-20.1：Planner 骨架

内容：

- 新增 `IntentResult`；
- 新增 `IntentSlotExtractor` SPI；
- 新增 `IntentSlotExtractorRegistry`；
- 新增 `QueryPlanner` Spring `@Component`；
- 新增 per sub-question `QueryPlan`；
- 默认 noop：未注册 extractor 时只保留原检索行为。

退出标准：

- COB/SOP / 现有 KB 行为零变化；
- extractor 超时或失败时降级到 noop constraints；
- 低置信或 top1/top2 接近时不跑 slot extraction。

### 5.3 PR-20.2：RetrievalConstraints + Composer 骨架

内容：

- 新增 `RetrievalConstraints`，与 `RetrievalScope` 并列，不混入权限对象；
- 新增 `RetrievalConstraintTranslator`，只允许白名单 metadata filter key；
- `SearchContext` / `DefaultMetadataFilterBuilder` 支持合并业务 filter；
- single-KB short-circuit 路径和多通道路径都消费 constraints；
- 业务 filter 与权限 filter 只做 AND 合并；
- 新增 `IntentAnswerComposer` / `ComposedAnswer`；
- `RAGPromptService` 接收 composer 给出的 rendering template / data。

退出标准：

- `RetrievalConstraints.empty()` 时 OpenSearch query shape 与改动前等价；
- 非空 constraints 会在 single-KB 和 multi-channel 两条 retrieval path 都生效；
- 业务 filter 不能放宽 KB / security_level 权限；
- 未注册 composer 时走默认 prompt。

### 5.4 PR-20.3：Collateral Slot-Filled 查询实现

内容：

- 新增 `collateral_field_lookup` KB-LOCAL intent（kind=1，kb_id=Collateral KB）；
- 禁用旧 `mcp_collateral_field_lookup`（保留行作 audit trail）；
- 新增 `CollateralSlotExtractor`；
- 新增 `CollateralSlotNormalizer`，复用 Phase 1.5 alias / house-party 排除资产；
- 新增 `CollateralAnswerComposer`；
- 新增 `EvidenceValueExtractor`：LLM-backed 字段值抽取 + chunk-membership 校验；
- A 档输出由 template 渲染结构化数据，不再让最终 LLM 二次改写数值；
- B/C/D 档 `allowLlmGeneration=false`；
- 上传表单增加 counterparty / agreementType / agreementDate；
- ingestion 将文档级 metadata 写入 OpenSearch chunk metadata；
- migration `v1.13 -> v1.14` 完成新旧意图共存切换。

退出标准：

- Collateral 字段问题走 KB-LOCAL Slot-Filled 路径，而不是 MCP 查询路径；
- `华泰和HSBC` / `Huatai and HSBC` / `HSBC` 均能归一化到 HSBC；
- A 档必须有 `precisionValue`、`originalParagraph`、`sourceChunkId`、`docName`；
- A 档 `precisionValue` 必须能在某个 chunk 文本中匹配，否则降 B；
- B/C/D 档不调 LLM，不会编造字段值。

### 5.5 PR-20.4：Phase 1 MCP 查询意图退役

内容：

- migration `v1.14 -> v1.15` 物理删除 `mcp_collateral_field_lookup`；
- `DefaultMCPToolRegistry` 取消 collateral query executor 自动注册；
- 保留 `MCPToolExecutor` / `MCPRequest` / `MCPToolRegistry` 抽象，供 Phase 4 actions 使用；
- 迁移 Phase 1/1.5 的测试资产到 SlotExtractor / Composer / EvidenceValueExtractor。

退出标准：

- 查询路径 trace 不再含 collateral MCP node；
- Collateral 字段查询仍能出 A/B/B'/C/D；
- COB/SOP 下问 Collateral 字段问题不会触发 Collateral 业务答案；
- MCP 通道只剩 future actions 占位。

## 6. Phase 2.5：OCR / Layout 文档智能底座

目标：

为扫描件、图片型 PDF、复杂版式协议建立可复核的文本与版面坐标底座。这个阶段不产生字段答案，只保证后续 Phase 3/4 能把字段值追溯回页码、块、可选 bbox。

为什么独立成阶段：

- Phase 2 是安全门 + 查询链路修正，不能被 OCR 引擎选型拖住；
- Phase 3 接真实协议字段时，如果输入包含扫描件，没有 OCR/layout 会导致漏抽、错页、证据不可复核；
- Phase 5 的 Sources UI 只能展示证据，不能补造 OCR/layout metadata。

### PR-DOCINT-1：Parser/OCR fallback 与页面文本模型

- 在 ingestion parser 层增加 text-layer 质量判断：
  - born-digital PDF：优先 Tika / 现有 parser 文本层；
  - image-only 或低文本密度 PDF：进入 OCR fallback；
  - mixed PDF：按页 fallback。
- 新增统一页面文本模型：

```text
DocumentPageText
  docId
  pageNo
  text
  textLayerType: NATIVE_TEXT | OCR | MIXED
  confidence
  blocks[]

LayoutBlock
  blockId
  pageNo
  blockType: TITLE | PARAGRAPH | TABLE | HEADER | FOOTER | OTHER
  text
  bbox: x,y,width,height
  readingOrder
  confidence
```

- OCR 引擎通过 adapter 接入，不把具体供应商锁进业务 service。
- 首版可以只支持一种本地/内网 OCR adapter；云 OCR、表格结构恢复留后续增强。

### PR-DOCINT-2：Layout-aware chunk 与 evidence metadata

- chunk 生成时保留：
  - `page_start / page_end`
  - `source_block_ids`
  - `bbox_refs`（可选）
  - `text_layer_type`
- 对协议条款类文本使用 reading order，避免页眉页脚、水印、双栏文本打乱正文。
- table block 暂以可读纯文本保留，不在本阶段做复杂单元格语义抽取。
- OpenSearch metadata 写入 page/block 信息，为 Phase 3 字段证据和 Phase 5 source card 做准备。

退出标准：

- born-digital PDF 仍走现有 parser，不回归；
- image-only PDF 能产生 OCR 文本 chunk；
- chunk 能追溯到 pageNo，必要时追溯到 block/bbox；
- 页眉页脚不会主导检索结果；
- 不产生字段答案，不绕过 Phase 2 权限。

## 7. Phase H：全局 / KB 专属意图树治理平台

目标：

把 Phase 2 的 `kb_id IS NULL / kb_id = activeKbId` 最小运行时约定，演进成可由系统管理员和 KB 管理者维护的治理能力。

### PR-INT-1：Schema + Tree Service

- `t_intent_node` 增加显式 `scope`：
  - `GLOBAL_SYSTEM`
  - `KB_LOCAL`
- 增加 `slot_pattern` / `slot_schema` / `answer_template_id`；
- 现有 `kb_id IS NULL` SYSTEM 节点迁移为 `GLOBAL_SYSTEM`；
- 现有带 `kb_id` 节点迁移为 `KB_LOCAL`；
- `IntentTreeFactory.buildTreeForKb(kbId)` 合并全局 SYSTEM 树 + 当前 KB 专属树；
- cache 按 `global` / `kb:{id}` 分桶；
- 写路径区分 SUPER_ADMIN 与 KB owner / DEPT_ADMIN 权限。

### PR-INT-2：MCP Action Binding + 运行时 Guard

- 新增 `t_kb_mcp_binding(kb_id, tool_id, enabled, config_json)`；
- `RetrievalEngine` / MCP action dispatch 执行前校验 `KbMcpBindingService.isEnabled(kbId, toolId)`；
- GLOBAL intent 禁止绑定业务 MCP tool；
- KB 软删级联软删 KB-local intent / binding；
- `IntentTreeCacheInvalidator` 覆盖 admin CRUD / migration / KB 软删；
- ArchUnit / grep gate：业务 action 不允许绕过 binding 校验。

### PR-INT-3：管理 API / UI + Collateral 首批治理数据

- `/admin/intent/global`：SUPER_ADMIN 管全局 SYSTEM 意图树；
- `/kb/{kbId}/intent`：KB 管理者管 KB 专属意图树；
- `/kb/{kbId}/mcp-bindings`：KB action tool 绑定；
- 前端三块：
  - 全局意图树；
  - KB 专属意图树；
  - MCP action 绑定；
- Collateral KB 首批 action binding：
  - `start_collateral_extraction`
  - `get_collateral_extraction_result`
  - `export_collateral_excel`

退出标准：

- SUPER_ADMIN 可管理全局 SYSTEM 意图树；
- DEPT_ADMIN / KB owner 只能管理自己 KB 的业务 intent / action binding；
- COB/SOP 未绑定 Collateral action 时无法调用；
- Collateral KB 绑定后可调用；
- `collateral_field_lookup` 保持 Slot-Filled retrieval，不回到 MCP query。

## 8. Phase 3：真实协议字段数据源

目标：

把 Phase 2.3 的文档级 metadata + chunk evidence 能力升级为真实、授权、可追溯的协议字段数据源。Phase 3 不再使用 `CollateralFieldLookupMCPExecutor` 承载查询，而是接入 `CollateralAnswerComposer` / `CollateralFieldLookupService`。

依赖说明：

- 如果真实协议主要是 born-digital PDF，Phase 3 可先基于现有 parser + chunk evidence 启动，但证据定位粒度较粗。
- 如果真实协议包含扫描件、图片型 PDF、双栏/表格重版式，Phase 2.5 是 Phase 3 的硬前置。
- Phase 3 不负责 OCR/layout 补救；它只消费已经可追溯的文档文本、page/block metadata。

### PR-BIZ-1：协议元数据与字段值模型

新增最小表：

```text
t_collateral_agreement
  id
  kb_id
  counterparty
  counterparty_aliases
  agreement_type
  agreement_date
  doc_id
  status
  deleted

t_collateral_field_value
  id
  agreement_id
  field_name
  field_aliases
  value_text
  source_chunk_id
  source_doc_id
  source_page
  source_text
  confidence
  review_status
  deleted
```

约束：

- 所有查询带 `kb_id`；
- 所有字段答案带 source；
- 所有写入保留 review status；
- 不允许无证据字段进入 A 档；
- 表内数据不能绕过 `RetrievalScope` / KB read permission。

### PR-BIZ-2：FieldLookupService 接入 Composer

- 新增 `CollateralFieldLookupService`；
- `CollateralAnswerComposer` 优先查询 `t_collateral_field_value`；
- 查表无命中时 fallback 到 Phase 2.3 的 chunk + `EvidenceValueExtractor`；
- seed 数据只保留为 test fixture，不进生产路径；
- A 档返回真实 `value_text + sourceText + source_chunk_id/page`；
- B/B'/C/D 文案保持兼容。

退出标准：

- HSBC 样例由真实字段表或真实 chunk evidence 返回；
- 所有 A 档都有 `source_chunk_id` / original text；
- 没有 reviewed 或可信 evidence 的字段不能进入确定性 A 档；
- 查询路径不经过 MCP executor。

## 9. Phase 4：批量智能筛查工作流

目标：

从单问单答升级为“批量字段筛查任务”。这是 MCP action 的主战场。

MCP actions：

- `start_collateral_extraction`
- `get_collateral_extraction_result`
- `export_collateral_excel`

业务对象：

```text
t_collateral_screening_task
t_collateral_screening_item
t_collateral_review_decision
```

核心状态：

```text
DRAFT -> RUNNING -> AI_PREFILLED -> REVIEWING -> APPROVED -> EXPORTED
```

约束：

- AI 只做 prefill；
- 人工 review 是进入导出前的硬门；
- 每个 item 必须有 `evidenceRefs`；
- 审核修改要保留 before/after；
- action tool 必须通过 Phase H 的 KB binding guard。

退出标准：

- 上传或选择一批协议；
- 选择字段模板；
- 后台生成筛查结果；
- 用户逐条复核；
- 导出 Excel。

## 10. Phase 5：Evidence / Sources 产品化

目标：

让字段答案和证据在 UI 上可复核，而不是只靠文本。

内容：

- `EvidenceRef` 接入 Sources 面板；
- `source_chunk_id` 可点击定位 chunk；
- 基于 Phase 2.5 的 page/block/bbox metadata 展示 page preview；
- 坐标级跳转作为增强，不在没有 layout metadata 时临时拼；
- 批量筛查行级 evidence 可展开查看；
- 导出包含 evidence columns。

退出标准：

- A 档答案可从 UI 打开原文证据；
- 批量筛查每一行可查看 evidence；
- 导出包含 evidence columns。

## 11. 不做清单

在 Phase 2.4 之前不做：

- 删除 MCP 抽象本身；
- 把 Phase 1 demo executor 直接接真实数据；
- 将 LLM 抽出的任意 key 直接作为 metadata filter；
- 在无 KB-scope guard 时接真实协议字段答案。

在 Phase H 之前不做：

- 面向管理员开放完整意图树治理 UI；
- 任意 KB 可调用任意 MCP action；
- GLOBAL business MCP intent；
- GLOBAL Slot-Filled 业务字段查询；
- 审计散写在 service 方法里。

在 Phase 2.5 之前不做：

- 扫描件字段抽取质量承诺；
- OCR 文本作为高置信字段答案来源；
- bbox / 坐标级 evidence；
- 复杂表格单元格结构恢复。

在 Phase 4 之前不做：

- 批量任务导出；
- 人工复核台；
- 多协议自动抽取闭环。

在 Phase 5 之前不做：

- PDF 坐标级跳转；
- 完整 source card UI。

## 12. 每个 PR 的防漂移检查

每个 Collateral PR 描述必须回答：

1. 属于哪个阶段？
2. 是否接触真实协议字段值？
3. 是否改变全局 / KB 专属意图树语义？
4. 是否改变 MCP action 可见范围？
5. 是否改变 KB 权限语义？
6. A 档答案是否有 evidence？
7. COB/SOP 误路由 gate 是否仍通过？
8. 是否引入了阶段外 UI / 管理后台？

只要第 2/3/4/5 项答案是“是”，PR 必须引用 Phase 2、Phase H 或 Phase 3 的设计，不允许作为普通业务改动混入。

