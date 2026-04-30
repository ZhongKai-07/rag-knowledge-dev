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
<!-- TBD: Task 6 -->

### 3.3 模型 coding / agentic 能力
<!-- TBD: Task 6 -->

### 3.4 代码理解与上下文深度
<!-- TBD: Task 8 -->

### 3.5 可靠性与企业治理
<!-- TBD: Task 6 -->

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
