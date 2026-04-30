# 代码助手企业选型分析报告

> **截至**：2026-04-29
> **目标读者**：技术管理层 / 产品经理
> **决策方案**：B（按部门组合 2~3 款工具）
> **基线对照**：Claude Code / Codex（仅在关键能力点出现）

---

## 第 0 章 TL;DR + 推荐总览
<!-- TBD: Task 14 -->

## 第 1 章 部门场景画像 & 推荐结论
<!-- TBD: Task 10 -->

## 第 2 章 三款工具速览

### 2.1 GitHub Copilot Enterprise

#### 厂商定位

GitHub（Microsoft 旗下）将 Copilot Enterprise 定位为面向企业研发组织的 AI 编程全链路平台，覆盖从代码补全、IDE Chat 到云端 Agent 自主执行的完整工作流，并深度集成 GitHub 代码托管与 CI/CD 生态。

#### 产品形态

- **IDE 插件**：VS Code、Visual Studio、JetBrains 全系（IntelliJ IDEA、PyCharm 等）、Vim、Neovim、Xcode、Eclipse、Azure Data Studio、SQL Server Management Studio、Zed、Raycast
- **Web**：github.com 集成 Copilot Chat；Copilot Workspace（云端 Agent，可自主研究仓库、生成实施计划、在分支执行代码修改）
- **CLI**：`gh copilot` 命令行扩展
- **Mobile**：GitHub Mobile App 内置 Copilot Chat
- **其他**：Windows Terminal Canary 集成

#### 核心卖点（5 条）

1. **最宽的模型选单**：内置 GPT-4.1 / GPT-5 mini（不消耗 credits），可按 session 切换至 Claude Sonnet/Opus 4.x、Gemini 2.5 Pro / 3.x、GPT-5.x 系列、Grok Code Fast 1、Fine-tuned 定制模型等（截至 2026-04-29，共 15+ 可选模型）
2. **Agent Mode GA（2026-03）**：在 VS Code 和 JetBrains 均已正式上线，支持多文件自主修改；Copilot Workspace（Cloud Agent）实现从自然语言需求到 PR 的全链路自动化
3. **Copilot Spaces + Memory**：Spaces 将文档和代码库组织为共享知识库，Memory（Public Preview）可自动推断并持久化仓库相关信息，两者共同提升跨会话上下文连续性
4. **Code Review GA（2026-03）**：可聚合完整项目上下文后给出代码审查建议，并直接将建议传递给 Agent 生成 fix PR，形成闭环
5. **第三方 Coding Agents 委派**：支持将任务委派给 Claude by Anthropic、OpenAI Codex 等外部 Agent；MCP Server 配置可赋予 Copilot 外部工具访问能力

#### 大陆可用性

**弱**。中国大陆（不含香港）不在官方支持区域列表，需通过 VPN/代理访问，有用户报告账号存在被封禁风险。官方无中国区域节点，不提供官方支持（详见附录 vendor-terms.md §1.6）。第 7 章将归并为硬约束。

#### 默认模型 + 可切换模型

- **默认/免费 credits 模型**：GPT-4.1、GPT-5 mini（使用不消耗 AI Credits）
- **可选模型（按 credits 计费）**：Claude Haiku 4.5、Claude Sonnet 4/4.5/4.6、Claude Opus 4.5/4.6/4.7；GPT-5.2、GPT-5.2-Codex、GPT-5.3-Codex、GPT-5.4、GPT-5.4 mini、GPT-5.4 nano、GPT-5.5；Gemini 2.5 Pro、Gemini 3 Flash、Gemini 3.1 Pro；Grok Code Fast 1；Fine-tuned 定制模型 Raptor mini、Goldeneye
- **切换粒度**：用户在 IDE 内可按 session 切换；Enterprise plan 可访问全部支持模型
- **BYOK**：未在公开材料中明确说明（Enterprise 支持自定义私有模型 fine-tuning，但非标准 BYOK）

#### 计费 + 企业版门槛

- **Enterprise**：$39/user/月；促销期（至 2026-08）享 $70 credits/月，促销期后恢复 $39 credits/月
- **计费模式**：2026-06-01 起切换为 AI Credits token 计费；Code completions 和 Next Edit Suggestions 不计入 credits，免费使用；超出 credits 部分按 token 费率计费，管理员可设 budget cap
- **企业版门槛**：需 GitHub Enterprise Cloud 账户
- **试用**：未在公开材料中明确说明 Enterprise 试用期限
- **合规认证**：SOC 2 Type I + ISO/IEC 27001:2013（注：Type I 而非 Type II，持续审计覆盖面有限）

#### 总评（约 80 字）

Copilot Enterprise 是三款工具中模型生态最丰富、与 GitHub 平台整合最深的选项，Agent Mode 和 Code Review 均已 GA，成熟度较高。主要短板是：SOC 2 仅 Type I（非 Type II），CMEK 支持未明确，中国大陆无官方访问路径。对于已深度使用 GitHub 的团队，平台黏性优势明显；对有数据主权要求的场景，合规认证需进一步核实。

---

### 2.2 Gemini Code Assist (Standard / Enterprise)

#### 厂商定位

Google Cloud 将 Gemini Code Assist 定位为企业级 AI 编程助手，底层使用 Gemini 2.5 Pro，主打 1M token 超长上下文和 Enterprise 层级的私有代码库定制能力，与 Google Cloud 生态（GCP、Apigee 等）深度绑定。

#### 产品形态

- **IDE 插件**：VS Code、JetBrains 全系（IntelliJ IDEA、PyCharm、GoLand、WebStorm、CLion、Rider）、Android Studio、Cloud Shell Editor、Cloud Workstations
- **Web**：Google Cloud Console 内集成；无独立 Web Chat 界面
- **CLI**：无专属 Code Assist CLI（Gemini CLI 存在但非 Code Assist 专属产品）
- **三个订阅层次**：Individual（免费）、Standard（团队/合规）、Enterprise（私有代码定制 + 增强集成）

#### 核心卖点（4 条）

1. **1M token 上下文窗口**：Agent Mode 基于 Gemini 2.5 Pro，单次可处理 1M token 上下文，适合超大型仓库的跨文件分析
2. **Enterprise 私有代码库定制**：支持接入 GitHub Enterprise Cloud/Server、GitLab、Bitbucket 私有代码库，生成更贴合组织代码风格的建议（Standard 层不含此功能）
3. **CMEK 支持**：支持客户管理加密密钥配置，满足数据主权要求（三款候选工具中唯一明确支持 CMEK 的）
4. **完整 SOC 1/2/3 + ISO 认证体系**：ISO 27001/27017/27018/27701 + SOC 1/2/3，合规认证覆盖最完整

#### Standard 与 Enterprise 差异

Standard（$19/月）与 Enterprise（$45/月年付）共享同一底层 Gemini 2.5 Pro 模型和 Agent Mode 能力，**差异不在模型本身，而在企业治理层面**：Enterprise 独有私有代码库索引（GitHub/GitLab/Bitbucket 接入）、VPC Service Controls 网络隔离、提高使用配额、以及 Apigee / Application Integration 等 Google Cloud 扩展集成。IP 赔偿保护 Standard 和 Enterprise 均覆盖，而 Individual 免费层不含。选型时，若团队无私有代码定制或 GCP 深度集成需求，Standard 已可满足日常合规场景。

#### 大陆可用性

**弱**。Google 服务被防火长城封锁，Gemini Code Assist 无法直接在中国大陆访问，需使用 VPN 或 API Gateway 代理，官方不提供中国区域节点（详见附录 vendor-terms.md §2.6）。第 7 章将归并为硬约束。

#### 默认模型 + 可切换模型

- **默认**：Gemini 2.5 Pro（Agent Mode 及日常补全均使用）
- **可选模型**：未在公开材料中明确说明用户是否可切换底层模型版本
- **切换粒度**：未在公开材料中明确说明
- **BYOK**：未在公开材料中明确说明

#### 计费 + 企业版门槛

- **Standard**：$19/user/月（年付）
- **Enterprise**：$45/user/月（年付）或 $54/user/月（月付）
- **计费模式**：按座位订阅，无公开的 token/credits 超用计费机制说明
- **企业版门槛**：Standard 和 Enterprise 均为组织订阅，无最低席位要求的公开说明
- **试用**：Enterprise/Standard 提供最多 50 用户的 30 天免费试用
- **合规认证**：ISO 27001/27017/27018/27701；SOC 1、SOC 2、SOC 3

#### 总评（约 80 字）

Gemini Code Assist 的合规认证体系是三款中最完整的（SOC 1/2/3 全覆盖 + CMEK），1M token 上下文在超大型仓库场景有明显优势。主要限制是模型选单固定（无多模型切换选项），跨会话记忆机制缺失（无状态服务），以及中国大陆无官方访问路径。对有 GCP 生态绑定且合规要求较高的团队，Enterprise 层是值得认真评估的选项。

---

### 2.3 AWS Kiro

#### 厂商定位

AWS 将 Kiro 定位为面向专业开发者的 Spec-driven AI IDE，核心差异化是将自然语言需求结构化为 EARS 记法需求文档，再驱动代码、测试、文档的全套生成，试图将 AI 引入软件工程生命周期的更上游环节，而非仅提供补全和 Chat。

#### 产品形态

- **IDE 类型**：**独立 IDE**（基于 Code OSS，非插件形态），兼容 VS Code 扩展和主题
- **支持平台**：macOS、Linux、Windows
- **CLI**：Kiro CLI（支持终端工作流，底层同样使用 Claude Sonnet 4.5 / Haiku 4.5）
- **Web/Mobile**：未在公开材料中明确说明独立 Web 或 Mobile 入口

#### 核心卖点（4 条）

1. **Spec-driven 开发流程（最完整）**：将提示词转为 EARS 记法结构化需求 → 设计文档 → 任务列表 → 代码实现，是三款候选工具中 Spec-driven 流程最完整的产品形态
2. **Hooks 事件自动化**：文件保存等事件触发后台 AI 任务（类似 CI hooks），可实现"写完代码自动跑测试/lint/文档更新"的工作流自动化
3. **Steering Files 项目规则**：全局或项目级 AI 行为配置（编码标准、工作流偏好），等效于 CLAUDE.md 机制，提供跨会话的项目规则持久化
4. **属性测试（GA 新功能）+ Checkpointing**：从需求提取属性并生成大量随机测试用例验证合规性；Agent 执行过程中支持步骤回滚

#### 大陆可用性

**弱**。Kiro Console/Profile 仅支持 US East (N. Virginia)、Europe (Frankfurt)、AWS GovCloud (US-East/West) 四个区域，无中国大陆节点（详见附录 vendor-terms.md §3.6）。IAM Identity Center 支持亚太多区域（新加坡、东京等），但均无中国大陆覆盖。第 7 章将归并为硬约束。

#### 默认模型 + 可切换模型

- **默认**：Auto 模式（混合前沿模型，通过意图检测和缓存优化成本）
- **可选**：Claude Sonnet 4.5（约为 Auto 模式 1.3 倍 credits 成本）、Claude Haiku 4.5
- **切换粒度**：用户可在 Auto / Sonnet / Haiku 之间切换，底层由 AWS 管理
- **BYOK**：未在公开材料中明确说明

#### 计费 + 企业版门槛

- **Free**：$0 / 50 credits/月
- **Pro**：$20/月 / 1,000 credits/月，超用 $0.04/credit
- **Pro+**：$40/月 / 2,000 credits/月，超用 $0.04/credit
- **Power**：$200/月 / 10,000 credits/月，超用 $0.04/credit
- **Enterprise**：联系销售 / 自定义 credits / 含集中计费、SAML/SCIM SSO（AWS IAM Identity Center）、组织管理仪表板
- **新用户奖励**：注册后 500 bonus credits（30 天有效）
- **自主 Agent Preview**：Pro/Pro+/Power 用户 Preview 阶段每周有用量限制，暂不额外收费
- **合规认证**：未在公开材料中明确说明（基于 AWS 基础设施，可能继承部分认证，但无产品级认证范围声明）

#### 总评（约 80 字）

Kiro 在 Spec-driven 开发流程和 Hooks 自动化上有明显差异化，适合希望将 AI 引入需求阶段的团队试点。主要顾虑是产品 GA 仅约 5 个月、数据训练承诺和合规认证均未公开说明、区域覆盖有限。建议作为探索性试点工具，而非在未核清数据处理条款前投入主力使用。

> **成熟度警示**
>
> 1. **GA 仅约 5 个月**：Kiro 于 2025-07-17 发布 Preview，2025-11-17 正式 GA，截至 2026-04-29 上线不足半年，企业级功能（合规认证、CMEK、细粒度审计日志）仍在持续完善中
> 2. **区域限制严格**：Console/Profile 仅覆盖 US East (N. Virginia)、Europe (Frankfurt)、AWS GovCloud (US-East/West)，无北京/宁夏中国区、无中国大陆任何节点
> 3. **数据训练承诺暂未公开**：官方企业版页面仅声明"遵循 AWS 云基础设施安全标准"，无明确的零训练承诺声明；此为调研信息缺口 #3，采购前须向 AWS 销售团队索取书面 DPA（数据处理协议）
> 4. **推荐定位：试点而非主力**：建议用于小规模 Spec-driven 开发流程探索，在数据处理条款、合规认证、区域覆盖等核心问题明确前，不建议作为主力生产工具全面铺开

## 第 3 章 核心能力深度对比

### 3.1 Harness Engineering
<!-- TBD: Task 7 -->

### 3.2 模型支持与路由

三款工具在模型选单、切换粒度和 BYOK 支持上的差异，直接影响研发与算法部门对"换用更强模型"或"接入私有微调模型"的可行性。本节整理三款工具的模型路线，不涉及模型本身的编码能力评分（见 § 3.3）。

#### 一览对照

| 维度 | Copilot Enterprise | Gemini Code Assist Ent | Gemini Code Assist Std | AWS Kiro |
|---|---|---|---|---|
| 默认模型 | GPT-4.1 + GPT-5 mini（不消耗 credits，含在订阅内） | Gemini 2.5 Pro | Gemini 2.5 Pro | Auto 模式（Claude Sonnet 4.5 与 Haiku 4.5 混用，意图检测自动路由） |
| 可选模型清单 | OpenAI：GPT-5.2、GPT-5.2-Codex、GPT-5.3-Codex、GPT-5.4、GPT-5.4 mini、GPT-5.4 nano、GPT-5.5；Anthropic：Claude Haiku 4.5、Sonnet 4/4.5/4.6、Opus 4.5/4.6/4.7；Google：Gemini 2.5 Pro、Gemini 3 Flash、Gemini 3.1 Pro；xAI：Grok Code Fast 1；Fine-tuned：Raptor mini、Goldeneye（共 15+ 款，详见附录 vendor-terms §1） | 仅 Gemini 系列；具体版本切换未在公开材料中明确说明 | 仅 Gemini 系列；具体版本切换未在公开材料中明确说明 | Claude Sonnet 4.5（约 Auto 模式 1.3 倍 credits）/ Claude Haiku 4.5 |
| 切换粒度 | 用户在 IDE 内可按 session 切换；Enterprise plan 可访问全部支持模型 | 未在公开材料中明确说明 | 未在公开材料中明确说明 | Auto 模式自动切换；用户也可手动指定 Sonnet / Haiku |
| BYOK / 自定义端点 | 未在公开材料中明确说明（Enterprise 支持自定义 fine-tuning，但非标准 BYOK 接口） | 未在公开材料中明确说明 | 未在公开材料中明确说明 | 未在公开材料中明确说明 |
| 模型版本更新节奏 | 未在公开材料中明确说明；新模型上架后需用户手动在 IDE 内选择 | 未在公开材料中明确说明；Gemini 2.5 Pro 已随 Agent Mode 上线 | 同 Enterprise | 未在公开材料中明确说明；Auto 模式下更新对用户透明 |
| 路由策略（chat vs autocomplete vs agent） | autocomplete 使用默认模型（不消耗 credits）；chat / agent 任务按用户选定模型计费；支持委派给外部 Codex / Claude agent | 未在公开材料中明确说明各场景的模型分配策略；Agent Mode 使用 Gemini 2.5 Pro | 同 Enterprise | Auto 模式根据任务意图自动选择 Sonnet 或 Haiku；autocomplete 倾向 Haiku（轻量低延迟） |

#### 总结

**模型多样性最强：Copilot Enterprise**。用户可在 15+ 款跨厂商旗舰模型间按 session 自由切换，GitHub 在此充当"模型集成商"角色，可随时受益于最新发布的模型能力。**模型选项简洁：Gemini Code Assist 与 Kiro**，两者均采用封闭路线，Gemini 锁定 Gemini 系列，Kiro 锁定 Claude Sonnet 4.5 / Haiku 4.5，不支持跨厂商模型切换。**BYOK 支持**：三款工具均未在公开材料中明确说明标准 BYOK 端点接入能力，这直接影响量化/算法部门"接入私有微调模型"的诉求——任何有此需求的场景，均须在采购谈判中向厂商明确索取书面说明。对于希望优先使用性能最优模型的研发部门，Copilot Enterprise 的多模型切换能力是明显优势；对于不需要跨厂切换、只需稳定编码辅助的场景，Gemini / Kiro 的单一模型路线反而降低了管理复杂度。

### 3.3 模型 coding / agentic 能力

本节引用 [appendix/benchmarks.md](appendix/benchmarks.md) 的公开成绩做基准对照。**需特别说明的是：benchmark 评的是底层语言模型，而非产品本身**——同一模型在不同产品 harness（工具调用策略、上下文管理、重试逻辑）下的实际体验可能差异显著，benchmark 数据是参考基线，而非最终产品性能背书。详细口径说明见附录 benchmarks.md §4。

#### 公开 benchmark 一览

详细数据见 [appendix/benchmarks.md](appendix/benchmarks.md)。关键成绩摘要：

**SWE-bench Verified 顶部（截至 2026-04-29）**：

- Claude Mythos Preview 93.9%、Claude Opus 4.7 Adaptive 87.6%、GPT-5.3 Codex 85.0%、Claude Opus 4.5 80.9%、Claude Opus 4.6 80.8%、Claude Sonnet 4.5 77.2%、Gemini 2.5 Pro 63.8%

**Terminal-Bench 2.0 顶部（截至 2026-04-29）**：

- GPT-5.5 via Codex agent 82.0%、GPT-5.4 via ForgeCode 81.8%、Gemini 3.1 Pro via TongAgents 80.2%、Claude Opus 4.6 via ForgeCode 79.8%

**重要警示**：SWE-bench Verified 与 SWE-bench Pro 数据差异显著——Claude Opus 4.5 在 Verified 得 80.9%，在 Pro（使用标准化脚手架、有训练数据泄露保护）仅 45.9%。商业材料中引用 benchmark 成绩时，需对照所使用的实际模型版本，而非榜单顶部数字。

#### 三款工具底层模型当前成绩区间

- **Copilot Enterprise**：用户可选 GPT-5.x 系列（SWE-bench 顶部区间：GPT-5.3 Codex 85.0%）或 Claude Opus 4.x（87.6%），通过模型选单访问当前市场最高水位；产品体验同时受 harness 调度质量影响（详见 § 3.1）。
- **Gemini Code Assist**：Gemini 2.5 Pro 在 SWE-bench Verified 排名第 39，成绩 63.8%，低于当前顶部区间约 24 个百分点；Gemini 3.1 Pro 出现在 Terminal-Bench 2.0 前三（80.2%），但尚未出现在 SWE-bench Verified 榜单，成绩待观察；Agent Mode 的 1M token 上下文是其差异化优势，在超长上下文理解场景有补充价值。
- **AWS Kiro**：Claude Sonnet 4.5（SWE-bench Verified 77.2%）+ Claude Haiku 4.5 组合；Sonnet 4.5 编码能力处于中上区间，Haiku 4.5 承担轻量补全任务以节省 credits 消耗，整体 benchmark 区间低于 Copilot 可选的顶部模型。

#### ★ Codex 基准对照

Codex（CLI + Cloud Agent + ChatGPT 集成）是当前 agentic coding 任务的代表性参照系：

- Terminal-Bench 2.0 排名第一：GPT-5.5 via Codex agent 82.0%（2026-04-23）
- SWE-bench Pro：GPT-5.5 达 58.6%（OpenAI 官方公告）；Codex 整体 56.8%
- 形态多样：终端 CLI（`codex` 命令）、Cloud agent（隔离 sandbox，完整文件系统访问）、ChatGPT 应用集成、Cerebras WSE-3 高速推理版（GPT-5.3-Codex-Spark，1,000+ tokens/s）
- **与三款候选的关系**：Codex 自身不在本次采购候选范围内，但 Copilot Enterprise 已开放对外部 agent（包括 Codex）的委派调度能力（GitHub Marketplace 集成），这为 Copilot 提供了一条借道 Codex agent 能力的路径，是 Copilot 生态的上限扩展选项之一

#### 实战体感

> 以下内容基于厂商 demo、公开评测报告和用户社区反馈，非本团队实测数据。

- **多语言覆盖**：三款工具在 Java / Python / TypeScript / Go / SQL 等主流语言上均表现良好，无明显弱项。金融行业小众语言（VBA / KDB+/Q / 量化策略 DSL / Bloomberg API）的覆盖**暂无公开评测数据，标"待实测确认"**，建议在试点阶段重点测试此类语言的补全和重构质量。
- **长链路任务完成率**：Claude Code / Codex 类 agentic 标杆工具在 50+ 步骤自主任务上的完成率有公开测试记录；三款候选产品的长链路能力受 harness 调度和用户配置影响，跨场景跨度较大，尚缺乏横向可比的第三方实测数据。
- **工具使用稳健性**：三款候选均依赖底层模型与自身 harness 的工具调用协同，harness 工程质量直接影响实际 agent 任务成功率，详见 § 3.1 的分析。

### 3.4 代码理解与上下文深度
<!-- TBD: Task 8 -->

### 3.5 可靠性与企业治理

本节聚焦三款工具的运营可靠性与企业管控能力。数据隐私、数据训练承诺、加密标准、合规认证等合规内容已在第 7 章及附录 [vendor-terms.md](appendix/vendor-terms.md) 中详细展开，**本节不重复**，仅从运营视角补充 SLA、降级机制和身份与访问管控的差异。

#### SLA 与降级

| 维度 | Copilot Enterprise | Gemini Code Assist Ent / Std | AWS Kiro |
|---|---|---|---|
| SLA 公开承诺 | 参考 GitHub Enterprise 通用 SLA（99.9%）；Copilot 服务可用性数据见 GitHub Status 页面 | 参考 Google Cloud 通用 SLA；具体针对 Code Assist 的 SLA 承诺未在公开材料中明确说明 | 未在公开材料中明确说明（GA 仅 5 个月，产品级 SLA 尚未公开） |
| 模型不可用时降级 | autocomplete 场景走本地缓存，用户无感知；Chat / Agent 请求失败时前端提示错误 | 未在公开材料中明确说明降级机制 | Auto 模式下若高级模型不可用，可能自动回退至 Haiku 4.5；降级逻辑未在公开材料中明确说明 |
| 故障历史记录 | GitHub Status（https://www.githubstatus.com）公开历史事故记录 | Google Cloud Status 页面公开历史记录 | 未在公开材料中明确说明（产品较新，公开故障记录有限） |
| 区域化部署 | 依托 GitHub Enterprise Cloud 地理数据驻留；无独立区域 SLA | 处理发生在距请求最近的数据中心，不保证单区域 SLA | 仅 US East (N. Virginia)、Europe (Frankfurt)、GovCloud 四个区域，无中国大陆节点 |

#### 企业管控

| 维度 | Copilot Enterprise | Gemini Code Assist Ent / Std | AWS Kiro |
|---|---|---|---|
| SSO | 集成 GitHub Enterprise 现有 SSO 体系（SAML） | 集成 Google Workspace / Cloud Identity SSO | SAML SSO（通过 AWS IAM Identity Center，支持 Okta、Microsoft Entra 等） |
| SCIM | 未在公开材料中明确说明 Copilot 专属 SCIM 配置 | 未在公开材料中明确说明 | 支持 SCIM（通过 AWS IAM Identity Center，Enterprise 计划） |
| RBAC | 组织管理员可控制模型访问权限、MCP 权限、功能启用；无公开细粒度 RBAC 文档 | 管理员可通过 Google Workspace 管理控制台配置访问策略；Enterprise 支持 VPC-SC 网络隔离 | 管理员可控制 MCP 访问权限；Enterprise 含组织管理仪表板；细粒度 RBAC 文档未在公开材料中明确说明 |
| License 池 | per-seat（Enterprise $39/user/月，含 credits 配额）；管理员可设 budget cap | per-seat 年付（Standard $19 / Enterprise $45；月付 Enterprise $54）；无公开超用计费机制 | per-seat，5 档（Free / Pro / Pro+ / Power / Enterprise）；超用 $0.04/credit；Enterprise 含个人计划分配与集中计费 |
| Policy / Guardrail | 公司级 policy 配置 + 内容过滤；管理员可关闭特定模型访问；Copilot Spaces 可限定上下文范围 | 企业级管理控制台 policy 配置；Enterprise 支持 VPC Service Controls 网络层隔离 | 未在公开材料中明确说明企业级 policy 配置能力的细节 |

#### 内容过滤 / 公开代码片段检测

- **Copilot Enterprise**：内置 duplication detection（公开代码片段重复检测），可在设置中启用；命中时提示代码来源，支持管理员强制屏蔽与公开代码高度重合的建议。
- **Gemini Code Assist**：支持 attribution citation（开源代码归因引用），Standard / Enterprise 层均提供 IP 赔偿保护（Individual 免费层不含）。
- **AWS Kiro**：未在公开材料中明确说明内容过滤或公开代码片段检测机制；Pro / Pro+ / Power 用户享有 IP 赔偿保护，但技术层面的过滤细节未公开。

#### 总结

三款工具均已为企业管控构建了基本框架，但披露透明度差异明显。**Copilot Enterprise** 凭借 GitHub Enterprise 的成熟基础设施，在 SLA 记录、故障透明度和内容过滤上披露最充分。**Gemini Code Assist** 的 VPC Service Controls 在网络隔离层提供了其他两款未有的能力，适合有严格数据主权要求的团队（其他合规细节见 § 7.2）。**Kiro** GA 仅约 5 个月，企业治理细节披露最少，SLA、RBAC、内容过滤均有待厂商进一步公开说明。内容过滤能力对量化/EST 部门尤其重要——避免将公开代码片段（含开源量化库）未经审查地引入私有代码仓库，三款工具中 Copilot 和 Gemini 在此项有明确机制，Kiro 建议在采购谈判中专项确认。

### 3.6 扩展性与生态
<!-- TBD: Task 9 -->

## 第 4 章 部门 × 工具适配矩阵
<!-- TBD: Task 11 -->

## 第 5 章 推荐组合方案（B 方案落地）
<!-- TBD: Task 12 -->

## 第 6 章 风险、迁移成本、落地路线图
<!-- TBD: Task 13 -->

## 第 7 章 合规与可用性约束

> 本章列出三款工具的"硬约束"与"红线规则"。详细厂商条款见 [appendix/vendor-terms.md](appendix/vendor-terms.md)。

### 7.1 硬约束 1：大陆可用性

| 工具 | 大陆企业版可用性 | 备注 | 对部门选型的影响 |
|---|---|---|---|
| GitHub Copilot Enterprise | 走代理可用，无大陆区，所有流量出境到 GitHub/Azure | 个人版国内已正常使用，企业版同链路；官方无中国区域节点（详见附录 §1.6） | 代理层 SLA 决定可用性下限 |
| Gemini Code Assist Enterprise / Standard | 走代理可用，无大陆区，流量出境到 Google Cloud | Google 服务被防火长城封锁，两版同链路；官方不提供中国区域节点（详见附录 §2.6） | 同上 |
| AWS Kiro | 走代理可用，仅 US East / Europe Frankfurt / GovCloud；无大陆区，无北京/宁夏区 | 2025-11-17 GA，区域有限；Console/Profile 仅四个区域，无任何中国大陆节点（详见附录 §3.6） | 延迟与稳定性显著低于 Copilot / Gemini，落地必须考虑代理层 SLA 和数据落地区域 |

**结论**：三款都是"出境 + 走代理"路线，无一可走纯境内合规通道。**任何部门要用都必须默认接受 prompt 出境到海外厂商。**

### 7.2 硬约束 2：数据训练 / 留存

| 维度 | Copilot Enterprise | Gemini Code Assist Ent | AWS Kiro |
|---|---|---|---|
| 零训练承诺 | 明确（Business / Enterprise 不用于训练）— 详见附录 §1.1 | 明确（Standard / Enterprise 不用于训练）— 详见附录 §2.1 | **未在公开材料中明确说明**（信息缺口 #3）— 详见附录 §3.1 |
| 数据留存 | 提供代码保留控制（Code Retention Controls）；Enterprise Cloud 支持地理数据驻留 — 详见附录 §1.2 | 无状态服务，不存储 prompt/response；传输中 TLS 加密，静态默认加密 — 详见附录 §2.2 | 未在公开材料中明确说明留存期限或控制机制 — 详见附录 §3.2 |
| CMEK | 未在公开材料中明确说明（信息缺口 #2）— 详见附录 §1.4 | 明确支持 — 详见附录 §2.4 | 未在公开材料中明确说明 — 详见附录 §3.4 |
| 审计日志 | 集成 GitHub 现有 Audit Log；Enterprise 管理员可访问合规报告 — 详见附录 §1.3 | 支持 Cloud Logging 桶存储（可选），支持管理活动审计日志 — 详见附录 §2.3 | 管理员可通过 AWS Management Console 监控使用和成本；详细审计日志能力未在公开材料中明确说明 — 详见附录 §3.3 |
| 合规认证 | SOC 2 Type I + ISO 27001（HIPAA/BAA 缺口 #1）— 详见附录 §1.5 | ISO 27001 / 27017 / 27018 / 27701；SOC 1 / 2 / 3 — 详见附录 §2.5 | 未在公开材料中明确说明（缺口 #4）— 详见附录 §3.5 |

**总结**：三家在"零训练"承诺上存在明显差异：Copilot 和 Gemini 均有明确书面声明，Kiro 暂无公开零训练承诺（信息缺口，采购前须向 AWS 索取书面 DPA）。加密能力方面，仅 Gemini 明确支持 CMEK，其余两家均未公开说明。合规认证覆盖最完整的是 Gemini（SOC 1/2/3 全覆盖 + 多项 ISO）；Copilot 的 SOC 2 仅为 Type I——Type I 仅验证设计，未验证运营有效性，与 Type II 存在实质差距；Kiro 则完全无产品级认证声明。**落地前必须由法务逐条核对最新条款原文，条款可能随时变更。**

### 7.3 说明 3：核心资产泄露红线

#### 三款企业版的"零训练" ≠ "零风险"

- **Prompt 仍上传厂商服务器做推理**（即使明确承诺不用于训练）
- **一旦服务商被攻破 / 内鬼 / 司法调取，数据可能落入第三方**

#### 红线规则

> **核心资产相关的代码 / 数据建议规避走 SaaS 路径。**

#### 适用部门点名

特别影响以下三个部门，详见第 1 章对应小节的"风险红线"栏：

- **量化研究**：策略代码 / 因子库 / 回测信号
- **EST**（Equities Sales & Trading）：客户订单 / 持仓 / 盘中数据
- **PWM 产品数据分析**：客户画像 / 持仓数据

### 7.4 开放清单

> 以下事项**不在本报告评估范围**，需由法务 / 合规 / IT 安全部门进一步核对：

1. 跨境数据传输的具体监管要求（《数据出境安全评估办法》《个人信息保护法》）
2. 证监会 / 交易所对 AI 工具的审计要求
3. 等保 2.0 / 数据分级
4. 出问题的责任界定 / 合同条款细节
5. 各家是否提供 SOC 2 Type II / 最新 ISO 27001 / HIPAA BAA 等

上述 5 条与 [appendix/vendor-terms.md §4](appendix/vendor-terms.md#4-信息缺口待法务--合规核对) 已识别的 5 条调研信息缺口（Kiro 零训练承诺缺口 #3、Kiro 合规认证缺口 #4、Copilot HIPAA/BAA 缺口 #1、Copilot CMEK 缺口 #2、LiveCodeBench 数据缺口 #5）高度重叠，采购谈判时建议一并核对。

## 附录索引

- [评测方法 methodology.md](appendix/methodology.md)
- [厂商条款 vendor-terms.md](appendix/vendor-terms.md)
- [Benchmark 引用 benchmarks.md](appendix/benchmarks.md)
