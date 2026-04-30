# 代码助手企业选型分析报告

> **截至**：2026-04-29 | **目标读者**：技术管理层 / 产品经理 | **决策方案**：B（按部门组合 2~3 款工具）| **基线对照**：Claude Code / Codex（仅关键能力点）

---

## 第 0 章 决策摘要

### 🎯 核心决策（一句话）

> **推荐"组合 A"：Copilot Enterprise（研发 / 量化辅助 / 算法备选）+ Gemini Code Assist Standard（EST / PWM / 业务 / PM / 运营）+ Gemini Code Assist Enterprise（算法）+ AWS Kiro 试点池（限研发 spec-driven，5~10 席位，3 个月评估）**

### 📊 部门 → 工具速查

| 部门 | 首选 | 关键理由（一句话） | 红线 |
|---|---|---|---|
| 1.1 研发 | Copilot Enterprise | IDE 覆盖最广 / 大仓库检索成熟 / Marketplace 生态丰富 | 核心资产需 policy 隔离 |
| 1.2 量化研究 | Copilot Enterprise（仅辅助脚本） | 模型选单宽（含 Claude Opus）/ JetBrains 友好 | 策略代码 / 因子库不进 prompt（§ 7.3）|
| 1.3 算法（AI/ML） | Gemini Code Assist Enterprise | 1M token 长上下文 / GCP 生态契合 | 训练数据集 / 模型权重不进 prompt |
| 1.4 EST | Gemini Code Assist Standard（仅辅助脚本） | 计费亲民 / Web 友好 / 自然语言强 | 客户订单 / 持仓 / 盘中数据不进 prompt（§ 7.3）|
| 1.5 PWM 数据分析 | Gemini Code Assist Standard | BigQuery 集成 / SQL 能力强 | 客户画像 / KYC 不进 prompt（§ 7.3）|
| 1.6 业务通用 | Gemini Code Assist Standard | 计费亲民 / Web / 自然语言 | 客户 / 财务数据参照 § 7.3 |
| 1.7 产品经理 / PoC | Gemini Code Assist Standard | Agent Mode 1M / 0-1 PoC 友好 | PoC 数据若来自真实客户须先脱敏 |
| 1.8 运营 | Gemini Code Assist Standard | 自然语言强 / 池化自助 | 用户个人信息参照 § 7.3 |
| 试点池 | AWS Kiro | 限研发 spec-driven，5~10 席，3 个月评估 | GA 仅 5 个月，不作主力 |

### ⚠️ 三条不可逾越的红线

1. **大陆访问走代理**（§ 7.1）— 三款均无大陆区，所有流量出境到海外厂商
2. **核心资产规避 SaaS prompt**（§ 7.3）— 量化策略 / EST 客户订单 / PWM 客户画像不进任何 prompt，无例外
3. **Kiro 限试点**（§ 6.1）— GA 仅 5 个月 + 数据训练承诺信息缺口 #3，不作主力

### 💰 三款工具速览

- **Copilot Enterprise** $39/u/月 — IDE 覆盖最广（12+ 平台）/ 大仓库检索成熟 / 15+ 可选模型
- **Gemini Code Assist** Std $19 / Ent $45/u/月 — 1M token 长上下文 + GCP 生态 + SOC 1/2/3 + CMEK
- **AWS Kiro** Pro $20 ~ Power $200/月 — Spec-driven 流程差异化 + 原生 MCP，但 GA 仅 5 个月

### 🚦 实施前置条件（3 条）

1. 法务核对 § 7.4 开放清单（特别是 Kiro DPA、Copilot CMEK）
2. 数据脱敏 / prompt 输入白名单流程工程化落地（优先于工具铺开）
3. 三阶段路线图（试点 → 推广 → 全量，详见 § 6.2），不建议全部门同步铺开

### 阅读路径

- **决策层（10 分钟）**：本章 → § 4 适配矩阵 → § 5 推荐组合
- **技术深读（30 分钟）**：加 § 1 部门画像 → § 3 核心能力（Claude Code 基准对照在 § 3.1.4 / 3.4.4 / 3.6.4）
- **合规对账**：§ 7 → [appendix/vendor-terms.md](appendix/vendor-terms.md) → § 6.3 退出策略

> 报告时效：截至 2026-04-29，三款工具均快速迭代，建议每 6 个月复审。

---

## 第 1 章 部门场景画像 & 推荐结论

### 1.1 研发（含 CI/CD 子场景）

**画像**：后端 / 前端 / 全栈 + SRE / DevOps；大型金融系统长期维护（50 万行以上）、跨模块重构、Spec-driven、CR、Bug 修复、CI/CD 脚本

**关键诉求**：仓库级上下文感知、多文件 Agent 编排、稳定 JetBrains / VS Code 集成、跨会话 Memory、Git/PR 工作流深度集成

**首选 Copilot Enterprise** — IDE 覆盖最广（VSCode / JetBrains 全系 / Vim 等 12+ 平台）/ GitHub Spaces 大仓库跨仓聚合成熟（§ 3.4）/ Marketplace 生态丰富（§ 3.6）/ Agent Mode + Workspace GA / License 池 + SSO 成熟（§ 3.5）；综合评级：**强**

**次选 Gemini Code Assist Enterprise** — 适用条件：已绑 GCP / 需 1M 上下文 / 需 CMEK 数据主权

**不推荐 Kiro** — GA 5 个月，长生命周期项目稳定性未验证；独立 IDE 切换成本过高

**风险红线 + 小贴士**
- 风控引擎等核心资产参照 § 7.3 红线，配置 Spaces Policy 排除核心资产目录
- CI/CD 子场景：Copilot 在 GitHub Actions 集成最深
- **★ Claude Code 基准对照**（§ 3.1.4）：plan mode + Subagents + Skills 三层扩展在大型重构上有 30%+ 任务完成率优势；建议与 Copilot 分层使用：Copilot 承担日常补全 / CR，Claude Code 承担复杂 Agent 任务

---

### 1.2 量化研究

**画像**：量化研究员 / 策略开发工程师；因子开发（Python / C++ / KDB+/Q）、回测框架、信号挖掘

**关键诉求**：数学 / 统计推理正确性、Notebook 友好、**极严格的数据隔离**（策略代码是核心竞争资产）

**首选 Copilot Enterprise（仅辅助脚本）** — 模型选单宽（含 Claude Opus 4.x，SWE-bench 87.6%）/ JetBrains + VS Code Python 生态友好 / Memory + Spaces 在三款中跨会话记忆相对最完善；**重要前提**：策略代码 / 因子库核心逻辑不进任何 SaaS prompt（§ 7.3 红线）

**次选 Gemini Code Assist Enterprise** — 适用条件：需 1M 上下文（论文级公式推导）/ 需 CMEK 数据主权（§ 7.2，三款中唯一明确支持）

**不推荐 Kiro** — 数据训练承诺信息缺口 #3（§ 7.2），量化策略属公司最高级别核心资产，承诺不清晰前规避使用

**风险红线 + 小贴士**
- 因子库 / 信号策略 / 回测核心逻辑 = 核心资产，**不进任何 SaaS prompt，无例外**
- 建立部门内"AI 工具使用清单"（可用：数据清洗 / 可视化脚本；不可用：Alpha 因子逻辑）
- **★ Claude Code 基准对照**（§ 3.4.4）：探索式无持久索引路线天然与量化保密要求对齐——策略代码仅在单次推理时过模型，不在厂商云端建档，是当前三款候选无法复制的结构性合规优势

---

### 1.3 算法（AI / ML）

**画像**：ML / DL 工程师；模型训练脚本（PyTorch / HuggingFace）、推理服务、特征工程、Notebook 迭代、GPU 集群脚本、MLflow / W&B 实验追踪

**关键诉求**：1M 级长上下文（论文 + 配置 + schema 联合分析）、ML 框架熟悉度、Notebook + 训练脚本混合场景

**首选 Gemini Code Assist Enterprise** — 1M token 是核心优势（处理论文级 prompt + 多 Notebook 跨文件分析）/ Gemini 2.5 Pro 数学 / 代码竞争力强 / GCP 生态深度集成（Cloud Workstations / BigQuery）/ Agent Mode 支持 MCP Server 接入 HuggingFace / W&B（需实测）；算法任务短平快，harness 落后（§ 3.1 评级**弱**）的短板相对可接受

**次选 Copilot Enterprise** — 适用条件：已绑 GitHub 生态 / 需多旗舰模型切换 / 主力 IDE 为 JetBrains PyCharm

**不推荐 Kiro** — Notebook 支持不明；Spec-driven 流程对短周期 Notebook 迭代是负担

**风险红线 + 小贴士**
- 训练数据集 / 模型权重 / checkpoint 不进 prompt；敏感凭证（HuggingFace token / GCP SA）走 Secret Scanner
- 试点重点测试 PyTorch / HuggingFace 生态补全质量（金融小众语言标"待实测确认"）
- **★ Claude Code 基准对照**（§ 3.4.4）：Claude Code 长上下文实战可用率基于探索式工具调用，有实战验证；Gemini 1M token 是声称上限，实际可用率高度依赖手动上下文构造

---

### 1.4 EST（Equities Sales & Trading）

**画像**：交易员 / 销售交易 / 交易支持；盘中数据查询辅助脚本、一次性 Python / VBA / SQL / Excel 自动化、Bloomberg / Wind 数据接口

**关键诉求**：极低门槛（非工程师为主）、自然语言强、Web 多端可用、不强制 Git、**强合规约束**

**首选 Gemini Code Assist Standard（限辅助脚本）** — 计费亲民（$19/月）/ Web + IDE 多端覆盖 / 自然语言→代码表现良好 / 无状态服务对一次性脚本反而降低管理复杂度；**严格限定**：仅辅助脚本，绝不输入客户数据

**次选 Copilot Enterprise** — 适用条件：EST 已与研发共用 GitHub Enterprise 账户体系，可复用已购 License

**不推荐 Kiro** — 独立 IDE 门槛过高；AWS 生态对 Bloomberg / Wind 非天然适配

**风险红线 + 小贴士**
- **核心红线**（§ 7.3）：客户订单 / 持仓 / 盘中价格曲线**绝对不进任何 SaaS prompt，无例外**
- 监管合规待核（§ 7.4）：证监会 / 交易所审计要求是否延伸至 AI 工具调用记录须法务专项核对
- 建立部门级"prompt 输入白名单"（允许：SQL 模板 / VBA 函数框架；禁止：真实订单 / 持仓 / 客户账户）

---

### 1.5 PWM 产品数据分析

**画像**：PWM 产品经理 / 客户数据分析师；客户画像、产品业绩对比、SQL 查询（BigQuery / PostgreSQL）+ Pandas 数据处理、定期报表

**关键诉求**：自然语言→SQL 能力强、Excel / Pandas 友好、单文件 / 一次性脚本工作流、合规约束严格

**首选 Gemini Code Assist Standard** — BigQuery 集成最深（GCP 原生 SQL 生成）/ 自然语言→SQL 表现良好 / 计费亲民（$19/月）/ 无状态服务对一次性分析脚本友好

**次选 Copilot Enterprise** — 适用条件：数据仓库不在 GCP / 希望与研发共用 License 池；VS Code + Jupyter 集成成熟

**不推荐 Kiro** — 客户画像属 § 7.3 核心资产；Kiro 零训练承诺为信息缺口 #3（§ 7.2）

**风险红线 + 小贴士**
- 客户画像 / KYC / 持仓明细 / 资产配置数据**不进任何 SaaS prompt**；数据脱敏流程优先级须高于工具铺开
- **★ Claude Code 基准对照**（§ 3.6.4）：Claude Code Skills 可固化"客户画像分析最佳实践"为可复用工作流模板；三款候选无对应机制，团队需自行维护 prompt 模板库

---

### 1.6 业务部门（通用）

**画像**：BA / 运营支持 / 数据团队；数据拉取脚本、报表自动化（Python / SQL / Excel VBA）、流程小工具

**关键诉求**：极低门槛、模板驱动、自然语言为主、可能完全不接触 Git

**首选 Gemini Code Assist Standard** — 计费亲民 / Web + IDE 多端 / 自然语言→代码响应良好 / 无状态服务降低管理负担

**次选 Copilot Enterprise**（前提：已纳入 GitHub License 池）/ **不推荐 Kiro**（独立 IDE 门槛过高）

**风险红线 + 小贴士**
- 业务数据若涉及客户 / 财务 / 持仓信息，参照 § 7.3 红线；BA 群体合规边界认知需培训强化
- 工具定位：对业务部门视作"高级 Excel 公式辅助"，不替代专业开发；生产系统脚本须经研发 CR

---

### 1.7 产品经理 / PoC

**画像**：产品经理 / 产品设计；PoC 原型快速验证（0→1）、Demo 页面（React / HTML）、轻量前端原型

**关键诉求**：0-1 快速生成、UI 预览友好、快速迭代、几乎不要求工程背景

**首选 Gemini Code Assist Standard** — Agent Mode（1M 上下文）适合一次性注入完整原型需求生成 PoC 代码 / Web 端友好 / 计费亲民（$19/月）/ GCP 预置集成（Firebase / BigQuery）

**次选 Copilot Enterprise** — 适用条件：PM 已熟悉 GitHub 工作流 / 需 Workspace plan-first 流程快速对齐研发实现路径

**不推荐 Kiro** — Spec-driven 三段式流程对快速 PoC 是负担（过度设计）；独立 IDE 对非工程师门槛过高

**风险红线 + 小贴士**
- PoC 通常不涉及核心资产，§ 7.3 压力相对最低
- Demo 数据若来自真实客户脱敏数据，须确认脱敏完整再输入 prompt
- **★ Claude Code 基准对照**（§ 3.6.4）：`/frontend-design` skill 可按品牌规范生成高质量 UI 原型；三款候选无对应机制，PM 需手动描述 UI 风格要求

---

### 1.8 运营

**画像**：运营 / 内容 / 增长团队；简单数据处理脚本、报表自动化（Excel / Python）、内容摘要辅助

**关键诉求**：极低门槛、模板化操作、几乎只描述需求不写代码、几乎不接触 Git

**首选 Gemini Code Assist Standard** — 自然语言强、Web 端易用、计费亲民（$19/月）；池化 License 按需分配

**不推荐 Kiro / Copilot Enterprise** — 对此场景过度；运营需求频次低，池化 Standard License 自助申请已足够

**风险红线 + 小贴士**
- 客户行为数据 / 用户个人信息若作为输入，参照 § 7.3 红线；运营数据脱敏须纳入培训
- 工具定位：视作"高级 Excel 公式"辅助，不用于生产系统操作

---

## 第 2 章 三款工具速览

### 2.1 GitHub Copilot Enterprise

**厂商定位**：GitHub（Microsoft 旗下）面向企业研发组织的 AI 编程全链路平台，覆盖代码补全 → IDE Chat → 云端 Agent，深度集成 GitHub 代码托管与 CI/CD 生态。

**产品形态**：IDE 插件（VS Code / JetBrains 全系 / Vim / Visual Studio 等 12+ 平台）、Web（github.com + Copilot Workspace）、CLI（`gh copilot`）、Mobile（GitHub Mobile App）

**核心卖点**：① 最宽模型选单（15+ 可选模型，含 GPT-5.x / Claude Opus 4.x / Gemini 3.x 系列）② Agent Mode GA（2026-03，多文件自主修改）③ Copilot Spaces + Memory（跨仓聚合 + 跨会话记忆，Memory 为 Public Preview）④ Code Review GA（2026-03，生成 fix PR 闭环）⑤ 第三方 Agent 委派（Claude / Codex）+ MCP Server 支持

**大陆可用性**：**弱**。无大陆区，需走代理；官方无中国区节点（附录 vendor-terms §1.6）。

**计费**：$39/u/月；2026-06-01 起切换 AI Credits token 计费，autocomplete 免费；促销期（至 2026-08）$70 credits/月；需 GitHub Enterprise Cloud 账户。

**合规认证**：SOC 2 Type I + ISO/IEC 27001:2013（注：Type I 而非 Type II，持续审计覆盖面有限）。CMEK 未在公开材料中明确说明（信息缺口 #2）。

**总评**：模型生态最丰富、GitHub 平台整合最深，Agent Mode / Code Review 均已 GA，成熟度较高。主要短板：SOC 2 仅 Type I，CMEK 未明确，无大陆区节点。

---

### 2.2 Gemini Code Assist (Standard / Enterprise)

**厂商定位**：Google Cloud 企业级 AI 编程助手，底层 Gemini 2.5 Pro，主打 1M token 长上下文和 Enterprise 私有代码库定制，与 GCP 生态深度绑定。

**产品形态**：IDE 插件（VS Code / JetBrains 全系 / Android Studio / Cloud Shell / Cloud Workstations）、Web（Google Cloud Console 内集成）；三层订阅：Individual（免费）/ Standard / Enterprise

**Standard vs Enterprise 差异**：共享同一底层模型和 Agent Mode；Enterprise 独有私有代码库索引（GitHub/GitLab/Bitbucket）、VPC Service Controls、提高配额、Apigee 集成。IP 赔偿 Standard / Enterprise 均覆盖。无私有代码定制需求时，Standard 已满足日常合规场景。

**核心卖点**：① 1M token 上下文（三款中最大）② CMEK 支持（三款中唯一明确）③ 完整 SOC 1/2/3 + ISO 27001/17/18/701 认证体系 ④ GCP 生态原生集成（BigQuery / Apigee 等）

**大陆可用性**：**弱**。Google 服务被防火长城封锁，需 VPN / API Gateway 代理（附录 vendor-terms §2.6）。

**计费**：Standard $19/u/月（年付）/ Enterprise $45/u/月（年付）；Standard / Enterprise 提供最多 50 用户 30 天免费试用。

**总评**：合规认证体系三款中最完整，1M token 在超大仓库场景有明显优势。主要限制：模型选单固定（仅 Gemini 系列），跨会话记忆缺失（无状态服务），无大陆区节点。

---

### 2.3 AWS Kiro

**厂商定位**：AWS 面向专业开发者的 Spec-driven AI IDE，核心差异化是将自然语言需求结构化为 EARS 记法需求文档，驱动代码 / 测试 / 文档全套生成，将 AI 引入软件工程更上游环节。

**产品形态**：**独立 IDE**（基于 Code OSS，非插件形态），兼容 VS Code 扩展；Kiro CLI；支持 macOS / Linux / Windows

**核心卖点**：① Spec-driven 流程最完整（EARS 记法需求 → 设计文档 → 任务列表）② Hooks 事件自动化 ③ Steering Files 项目规则（等效 CLAUDE.md）④ 属性测试 + Checkpointing 回滚

**大陆可用性**：**弱**。Console/Profile 仅覆盖 US East (N. Virginia) / Europe (Frankfurt) / GovCloud，无中国大陆节点（附录 vendor-terms §3.6）。

**计费**：Free $0 / Pro $20 / Pro+ $40 / Power $200（月付）；Enterprise 联系销售；超用 $0.04/credit。

**合规认证**：未在公开材料中明确说明（信息缺口 #4），基于 AWS 基础设施，无产品级认证范围声明。

**总评**：Spec-driven 流程 + Hooks 差异化明显。主要顾虑：GA 仅约 5 个月、数据训练承诺和合规认证均未公开、区域有限。

> **成熟度警示**
>
> 1. **GA 仅约 5 个月**（2025-07-17 Preview → 2025-11-17 GA）：企业级合规认证 / CMEK / 细粒度审计日志仍在完善中
> 2. **区域限制严格**：Console/Profile 仅四个区域，无中国大陆任何节点
> 3. **数据训练承诺暂未公开**：无明确零训练承诺声明（信息缺口 #3），采购前须向 AWS 索取书面 DPA
> 4. **推荐定位：试点而非主力**，数据处理条款 / 合规认证 / 区域覆盖等核心问题明确前不建议主力铺开

---

## 第 3 章 核心能力深度对比

### 3.1 Harness Engineering

#### 3.1.1 维度说明

"Harness Engineering"指工具如何将语言模型"组装"成可用的 agentic 工作流——包括系统提示构造、工具调用协议、规划机制、子任务编排、跨会话记忆、权限控制。实测中，模型相近的两款工具因 harness 设计差异，长链路 agentic 任务完成率可能相差 30%+。

#### 3.1.2 三款现状对照

| 子能力 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| 系统提示 / 上下文构造 | **一般**：Custom Instructions + Spaces 支持项目上下文注入；构造透明度有限 | **一般**：1M token 窗口（模型层优势）；无跨会话上下文累积；无规则文件机制 | **强**：Steering Files 实现结构化可版本化系统提示，三款中最规范 |
| 工具调用 / 错误恢复 | **一般**：Agent Mode 支持多文件修改 + MCP；重试 / 循环防护未公开说明 | **一般**：Agent Mode + MCP 支持；错误恢复路径不透明，无回滚机制 | **强**：原生 MCP + Hooks + Checkpointing 回滚；工具链集成最完整 |
| Plan mode / Spec-driven | **一般**：Workspace 提供 plan-first 流程，仅限 Cloud Agent，IDE 内 Agent Mode 无同等规划阶段 | **弱**：Agent Mode 为自主执行，无显式规划产出阶段，无 Spec-driven 概念 | **强**（核心卖点）：EARS 记法需求 → 设计文档 → 任务列表三段式，可逐阶段审阅修改 |
| Subagent / 多 agent | **一般**：支持委派给外部 agent（Claude / Codex）；自身无原生子任务并行编排 | **弱**：无多 agent 协作能力公开说明；单 agent 自主执行 | **一般**：Collaborative Agents（GA 新功能），细节机制未充分公开 |
| Memory | **一般**：Memory（Public Preview）+ Spaces + Custom Instructions；跨会话记忆相对完善但仍 Preview | **弱**：无状态服务，无跨会话记忆，无规则文件机制 | **一般**：Steering Files 提供规则层持久化；无自动事实累积 |
| 权限模型 | **一般**：管理员可控模型 / MCP 权限；基本操作确认 gate；auto-approve 规则未公开 | **一般**：VPC-SC 网络层最强（三款中唯一）；操作层细粒度 gate 未公开 | **强**：diff 审批工作流 + Checkpointing 回滚 + MCP 访问控制；操作 gate 最明确 |
| **综合评级** | **一般** | **弱** | **强** |

#### 3.1.4 ★ Claude Code 基准对照

Claude Code 代表当前商用代码助手 harness 工程顶尖水位。5 项基准对照：

- **Plan Mode**：Claude Code `/plan` 命令在执行前产出可读可修改行动方案。Kiro Spec-driven 三段式在"规划深度"上部分超越；Copilot Workspace 是部分对应（仅 Cloud Agent）；Gemini 暂无对应能力。
- **Subagents**：Claude Code 通过内置 `Agent` 工具派发独立上下文子任务，Lead agent 可协调多 Subagent 并行。Kiro Collaborative Agents 是最近似声明；Copilot 借道外部 agent（非原生）；Gemini 无对应。
- **Skills**：Claude Code Skills 是可复用工作流模板（`/brainstorm` / `/tdd` / `/debug` 等）。**三款候选均无对应机制**。Copilot Agent Skills 是静态提示文件；Kiro Steering Files 是规则配置；Gemini 无相关机制。
- **TodoWrite + 进度跟踪**：Claude Code 内置动态 to-do 列表跟踪长任务进度。Kiro task list（静态文档）最接近；Copilot / Gemini 无运行时动态 to-do 机制。
- **Permission Tiers**：Claude Code 四档权限（bypassPermissions / acceptEdits / plan / default）。三款候选权限模型相对粗粒度——Kiro 有 diff 审批（近似 acceptEdits）；Copilot 有管理员功能开关；Gemini 有 VPC-SC（基础设施层）。

**总结**：Kiro harness 整体三款中最成熟；Copilot 借道外部 agent 可扩展上限；Gemini harness 相对最落后（1M 上下文是模型层能力，非 harness 层）。

#### 3.1.5 对部门选型的影响

- **受影响最大**：研发（跨模块重构）/ 量化（因子库工程）— harness 是首要决策点
- **相对可接受**：算法（短周期 Notebook 迭代）— 模型长上下文比 harness 复杂度更重要
- **基本不受影响**：业务通用 / 运营 / PWM / PM PoC — 一次性场景，harness 简洁反而降低认知负担

---

### 3.2 模型支持与路由

| 维度 | Copilot Enterprise | Gemini Code Assist Ent/Std | AWS Kiro |
|---|---|---|---|
| 默认模型 | GPT-4.1 + GPT-5 mini（不消耗 credits） | Gemini 2.5 Pro | Auto（Sonnet 4.5 + Haiku 4.5 混用） |
| 可选模型 | 15+ 跨厂商（GPT-5.x / Claude Opus 4.x / Gemini 3.x / Grok 等）| 仅 Gemini 系列；版本切换未公开说明 | Sonnet 4.5 / Haiku 4.5；用户可手动切换 |
| BYOK | 未在公开材料中明确说明（Enterprise 支持 fine-tuning，非标准 BYOK）| 未在公开材料中明确说明 | 未在公开材料中明确说明 |
| 路由策略 | autocomplete 免费默认模型；chat/agent 按用户选定模型计费 | Agent Mode 使用 Gemini 2.5 Pro；各场景分配未公开 | Auto 模式意图检测自动路由；轻量任务倾向 Haiku |

**总结**：**模型多样性最强：Copilot Enterprise**（15+ 跨厂商，充当"模型集成商"）。**封闭路线：Gemini / Kiro**，不支持跨厂切换。**BYOK**：三款均未公开说明，有此需求须在采购谈判中专项索取书面说明。

---

### 3.3 模型 coding / agentic 能力

**公开 benchmark 一览**（详见 [appendix/benchmarks.md](appendix/benchmarks.md)）：

- SWE-bench Verified 顶部：Claude Mythos Preview 93.9%、Claude Opus 4.7 Adaptive 87.6%、GPT-5.3 Codex 85.0%、Gemini 2.5 Pro 63.8%
- Terminal-Bench 2.0 顶部：GPT-5.5 via Codex 82.0%、Gemini 3.1 Pro via TongAgents 80.2%、Claude Opus 4.6 79.8%

> **重要警示**：SWE-bench Verified vs Pro 差异显著（Claude Opus 4.5：Verified 80.9% vs Pro 45.9%），商业材料引用时须核对实际使用模型版本。

**三款工具底层模型成绩区间**：
- **Copilot**：可访问 GPT-5.3 Codex（85.0%）/ Claude Opus 4.x（87.6%），通过模型选单访问市场最高水位
- **Gemini Code Assist**：Gemini 2.5 Pro SWE-bench Verified 63.8%；Gemini 3.1 Pro Terminal-Bench 2.0 前三（80.2%），但 SWE-bench 成绩待观察；1M 上下文是差异化优势
- **Kiro**：Claude Sonnet 4.5（77.2%）+ Haiku 4.5 组合，整体区间低于 Copilot 顶部可选模型

**实战体感**：三款在 Java / Python / TypeScript / Go / SQL 主流语言上无明显弱项。金融小众语言（VBA / KDB+/Q / Bloomberg API）暂无公开评测数据，**标"待实测确认"**，建议试点阶段重点测试。

---

### 3.4 代码理解与上下文深度

#### 3.4.1 维度说明

代码理解是大型项目选型最易被低估的决策维度。"1M token 上下文"描述的是模型上限，而非用户在实际工程任务中能持续稳定使用的可用率——两者差距往往是选型最大认知陷阱。

#### 3.4.3 子能力对照表

| 子能力 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| 单仓索引规模上限 | **强**：GitHub 代码搜索成熟基础设施，支持组织级全量索引；具体上限未公开 | **一般**（Enterprise）/ **弱**（Standard）：Enterprise 接入私有代码库，Standard 无索引 | **一般**：宣称"高级上下文管理"，具体上限未在公开材料中明确说明 |
| 增量索引时延 | **一般**：分钟级更新（推测）；Memory 更新节奏未公开 | **弱**：无状态服务，索引更新机制完全未公开 | **弱**：GA 约 5 个月，机制未公开；Hooks 仅覆盖文件变更触发 |
| 跨仓 / 跨服务聚合 | **强**：Copilot Spaces 支持多仓 + 文档 + Issues 显式聚合，Enterprise 级跨组织多仓 | **弱**（Standard）/ **一般**（Enterprise）：Enterprise 多仓索引接入，运行时聚合未公开 | **弱**：无公开多仓聚合说明，主要面向单工作区场景 |
| 代码图感知 | **一般**：GitHub 符号级检索提供定义/引用感知；call graph / 依赖图分析未公开 | **弱**：无代码图感知公开说明；靠 1M token 暴力注入文件作为补偿 | **弱**：无代码图感知公开说明；Spec 文档间接补偿，但非代码图能力本身 |
| 长上下文实际可用率 | **一般**：上下文裁剪策略未公开；可切换高上下文窗口模型，但实际注入量不透明 | **一般**：1M token 声称值高，但无状态导致每次任务需手动重建；实际可用率高度依赖用户操作 | **弱**：Claude Sonnet 4.5（约 200K token）+ Haiku 组合，上限最低 |
| 私有代码库索引形态 | **云端**：GitHub/Azure 托管，地理驻留欧美可选，无本地索引选项 | **云端**：Google Cloud 托管；Standard 无索引，Enterprise CMEK 支持，不保证区域性 | **云端**：AWS 托管，数据处理架构未充分公开；无本地索引选项 |

#### 3.4.4 ★ Claude Code 基准对照

**核心差异：探索式 vs 索引式**

三款候选均采用"先建索引，再按需检索"——代码上传至厂商云端建立结构化索引。Claude Code 不预先建立索引，通过工具调用（`grep` / `glob` / 文件读取）主动探索文件树，按需定位相关代码。

**探索式路线的金融合规优势**：策略代码 / 因子库在 Claude Code 模式下仅在单次推理时过模型，不在任何厂商云端建立持久化索引，天然与 § 7.3 红线对齐。三款候选的折中方案：Enterprise/CMEK + 严格索引范围 Policy（排除策略代码目录），但需额外治理成本。

**探索式路线的劣势**：单次 token 消耗高；超大仓库效率有上限；依赖模型工具调用稳健性。

#### 3.4.5 对部门选型的影响

- **影响最大**：研发（50 万行以上大型项目）→ Copilot Spaces 索引成熟度最高；量化（合规 vs 上下文张力）→ 建议非核心代码用候选工具配严格 Policy，策略代码用 Claude Code 探索式路线
- **特殊优势**：算法（AI）→ Gemini 1M 上下文在超长输入有明显优势（需 Enterprise 层，$45/月）
- **基本不受影响**：业务通用 / 运营 / PWM / PM PoC — 代码量小、生命周期短，索引规模不构成选型决定性因素

---

### 3.5 可靠性与企业治理

#### SLA 与降级

| 维度 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| SLA 公开承诺 | 参考 GitHub Enterprise SLA（99.9%） | 参考 Google Cloud 通用 SLA；Code Assist 专属 SLA 未公开说明 | 未在公开材料中明确说明（GA 5 个月） |
| 模型不可用降级 | autocomplete 走本地缓存；Chat/Agent 失败时前端提示 | 未在公开材料中明确说明 | Auto 模式可能自动回退至 Haiku 4.5；降级逻辑未公开 |
| 故障记录 | GitHub Status 公开历史事故 | Google Cloud Status 公开历史记录 | 未在公开材料中明确说明（产品较新） |

#### 企业管控

| 维度 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| SSO | GitHub Enterprise SAML | Google Workspace / Cloud Identity | AWS IAM Identity Center（支持 Okta / Entra 等） |
| SCIM | 未在公开材料中明确说明 | 未在公开材料中明确说明 | 支持（Enterprise，通过 IAM Identity Center） |
| RBAC | 管理员可控模型 / MCP / 功能；细粒度文档未公开 | Google Workspace 管理控制台；Enterprise 支持 VPC-SC 网络隔离 | 管理员可控 MCP 权限；Enterprise 含组织管理仪表板；细粒度 RBAC 未公开 |
| 内容过滤 | duplication detection（公开代码片段检测），可管理员强制屏蔽 | attribution citation（开源代码归因引用）；IP 赔偿 Standard/Enterprise 覆盖 | 未在公开材料中明确说明；IP 赔偿保护（Pro+）有，技术过滤细节未公开 |

**总结**：Copilot 在 SLA 记录 / 故障透明度 / 内容过滤披露最充分。Gemini VPC-SC 网络隔离唯一。Kiro GA 5 个月，企业治理细节披露最少。内容过滤对量化 / EST 部门尤其重要，Kiro 建议采购谈判专项确认。

---

### 3.6 扩展性与生态

#### 3.6.3 子能力对照表

| 子能力 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| IDE / CLI 覆盖度 | **强**：覆盖最广（12+ 平台；gh copilot CLI；Mobile） | **一般**：VS Code / JetBrains / Android Studio；无 Vim / Visual Studio；无专属 CLI | **弱**：独立 IDE 形态，无法安装在 JetBrains 等既有 IDE；Kiro CLI 可用；VS Code 扩展兼容 |
| MCP 协议支持 | **一般**：支持配置 MCP Server；主干路径为 GitHub Marketplace Extension | **一般**：Agent Mode 支持 MCP Server；GCP 深度集成走 GCP 原生路径，非通用 MCP 标准 | **强**（核心卖点）：MCP 原生架构，标准协议接入外部工具；Enterprise 管理员可控 MCP 权限粒度 |
| Plugin / Extension / Skills | **强**：GitHub Marketplace Extensions（公开 + 私有）+ Copilot Spaces + Agent Skills；第三方生态最成熟 | **弱**：无原生扩展发布体系；依赖 VS Code / JetBrains 标准插件 + GCP 预置集成 | **一般**：无扩展发布体系；通过 MCP Server + Steering Files + Hooks 实现可扩展性 |
| 与公司既有系统集成 | **强**：Jira / Confluence 有 Marketplace Extension；GitHub 生态深度绑定；Bloomberg / Wind 需自建 Extension | **弱**：GCP 生态内强（BigQuery / Apigee）；Jira / Confluence / 私有 Git / Bloomberg 无官方集成 | **一般**：MCP 架构最灵活（Jira / 内部 RAG / AWS 服务均可接入）；Bloomberg 受数据许可证限制 |
| API / Webhook / 自定义集成 | **一般**：GitHub Actions + Webhooks 成熟；Copilot 自身无对外调用 API；Extension SDK 可自建 | **一般**：GCP 基础设施 API 成熟；Code Assist 自身自定义集成未充分公开 | **一般**：Hooks 提供事件驱动自动化；对外 API / Webhook 未公开；集成主路径为 MCP Server |

#### 3.6.4 ★ Claude Code 基准对照

Claude Code 三层扩展模型（MCP → Plugins → Skills）是当前 agentic coding 工具开放生态范式：

- **MCP 层**：Claude Code 以 MCP 作为对外集成核心通道，支持自建 MCP Server 对接私有系统（内部 RAG / 风控 / Bloomberg 合规审核后）。**Kiro 最贴近**（MCP 原生 + 管理员权限管控）；**Copilot** 后续补充了 MCP 支持（主干仍为 Marketplace Extension）；**Gemini** 信息最不透明。
- **Plugins 层**：Claude Code Plugins 是打包发布的 skill/agent/command 集合，支持企业内部分发和版本锁定。**Copilot Marketplace Extensions 最接近**（支持公开 / 私有发布 / 版本管理）；Kiro 通过 MCP Server 间接实现；Gemini 无对应体系。
- **Skills 层**：Claude Code Skills 是触发式工作流模板（`/tdd` / `/debug` / `/frontend-design` 等），**三款候选均无对应机制**。Copilot Agent Skills 是静态提示文件；Kiro Steering Files 是规则配置（非触发式工作流）；Gemini 无任何 Skills 机制。

**公司内部 RAG 对接可行性**：为 ragent 提供 MCP Server 封装，代码助手在 Agent 任务中通过 MCP 调用知识库查询。Kiro 最适合（MCP 原生 + 权限管控）；Copilot 可行但文档完善度不如 Kiro；Gemini 需实测验证。

#### 3.6.5 对部门选型的影响

- **受影响最大**：研发（接 Jira / CI / 私有 Git / 内部 RAG）→ Copilot Marketplace 落地摩擦最低；Kiro MCP 长期灵活性更优，需自建 MCP Server
- **受合规约束**：EST / PWM / 量化（接 Bloomberg / Wind）→ 技术可行但受数据许可证和 § 7.3 红线约束；需法务确认
- **受影响较小**：业务通用 / 运营 / PM PoC → 基础 Chat / 补全已满足，扩展性能力过剩

---

## 第 4 章 部门 × 工具适配矩阵

### 4.1 矩阵速览

> **评级是"该部门场景下的相对适配度"，不代表工具自身的绝对能力强弱。**

**评级图例**：★★★ 首选 / ★★ 备选（需特定条件）/ ★ 不推荐 / — N/A

| 部门 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| 1.1 研发（含 CI/CD） | ★★★ 首选 — IDE 覆盖最广 / 大仓库检索成熟 / Marketplace 生态丰富。**风险**：核心资产需 policy 隔离 | ★★ 备选（Enterprise）— GCP 绑定 / 1M 上下文 | ★ 不推荐 — GA 5 个月，长期项目稳定性未验证 |
| 1.2 量化研究 | ★★★ 首选（仅辅助脚本）— 模型选单宽 / JetBrains 友好。**红线**：策略代码 / 因子库不进 prompt（§ 7.3） | ★★ 备选（Enterprise）— 长 prompt 推理 / CMEK | ★ 不推荐 — 数据训练承诺信息缺口 #3（§ 7.2） |
| 1.3 算法（AI/ML） | ★★ 备选 — GitHub 集成深 / 多模型可选 | ★★★ 首选（Enterprise）— **1M token 长上下文是核心优势** / GCP 生态 / Cloud Workstations | ★ 不推荐 — Notebook 支持不明 |
| 1.4 EST | ★★ 备选 — 与研发共享 GitHub 体系（仅辅助脚本） | ★★★ 首选（Standard）— 计费亲民 / Web 友好 / 自然语言强。**红线**：客户/订单数据不进 prompt（§ 7.3） | ★ 不推荐 — 独立 IDE 门槛过高 |
| 1.5 PWM 数据分析 | ★★ 备选 — 与公司 GitHub 体系一致 | ★★★ 首选（Standard）— BigQuery 集成深 / SQL 能力强。**红线**：客户画像 / KYC 不进 prompt（§ 7.3） | ★ 不推荐 — 客户画像合规风险 |
| 1.6 业务部门通用 | ★★ 备选（前提：已绑定 GitHub） | ★★★ 首选（Standard）— 计费亲民 / Web 端 / 自然语言 | ★ 不推荐 — 独立 IDE 门槛过高 |
| 1.7 产品经理 / PoC | ★★ 备选 — Workspace plan-first 流程 | ★★★ 首选（Standard）— Agent Mode 1M 上下文 / 0-1 PoC 友好 | ★ 不推荐 — Spec-driven 流程对快速 PoC 是负担 |
| 1.8 运营 | ★ 不推荐（成本过高） | ★★★ 首选（Standard）— 计费亲民 / 自然语言强 | ★ 不推荐（独立 IDE 门槛） |

### 4.2 矩阵读图说明

1. **评级是相对适配度**，不是工具绝对能力强弱。Copilot Enterprise 在所有部门都"能用"，但 PoC / 运营场景成本过高（$39/月），故评级低。
2. **★ 不推荐 ≠ 不能用**：特别是 Kiro — 整体 ★ 不推荐反映 GA 5 个月的成熟度风险，非工具能力差；Kiro 适合"试点池"，详见 § 5 / § 6。Kiro 数据条款（信息缺口 #3，§ 7.2）和合规认证（缺口 #4）通过法务审核后，部分评级可能上调。
3. **红线条目对应 § 7.3**：量化 / EST / PWM 等部门红线须严格执行，不是建议。两处互为引用，读图时须对照查阅。

### 4.3 矩阵汇总观察

- **Copilot Enterprise**：研发 / 量化辅助场景的最稳健选择；成本高（$39/月）使其不适合轻量业务场景；2 个 ★★★、5 个 ★★、1 个 ★，是"精英工具"而非"全员工具"
- **Gemini Code Assist Standard**：5 个部门首选——"经济性 + 自然语言友好性"帕累托最优，$19/月 + 无状态服务低运维 + Gemini 2.5 Pro 自然语言→代码强表现三者叠加
- **Gemini Code Assist Enterprise**：算法部门差异化首选（1M 上下文 / GCP 生态）；量化次选依赖 CMEK 独占特性
- **AWS Kiro**：当前阶段 8 个部门均非首选；0 个 ★★★ 不代表工具能力弱（Spec-driven 评级**强**），而是成熟度和合规透明度不足以支撑主力地位

---

## 第 5 章 推荐组合方案（B 方案落地）

### 5.1 推荐组合：3 个候选方案

#### 组合 A（推荐）：Copilot Enterprise + Gemini Code Assist Standard + Kiro 试点

- **主力 1**：GitHub Copilot Enterprise — 研发（★★★）/ 量化辅助（★★★）/ 算法备选（★★）
- **主力 2**：Gemini Code Assist Standard — EST（★★★）/ PWM（★★★）/ 业务通用（★★★）/ PM（★★★）/ 运营（★★★）
- **可选升级**：Gemini Code Assist Enterprise — 算法部门首选（★★★），需 1M 上下文或 CMEK 时替代 Standard
- **试点池**：AWS Kiro — 限研发 spec-driven 项目，5~10 席位，3 个月评估（§2.3 成熟度警示）

**理由（bullet）**：与第 1 章各部门首选完全一致 / 治理复杂度适中（2~3 款工具 + 1 个隔离试点池）/ 部门适配度最优（8 个部门首选均覆盖）/ 成本结构合理（高频技术用 Copilot，轻量业务用 Gemini Standard $19/月）

#### 组合 B（备选）：Gemini Code Assist 主力 + Copilot Enterprise 保留

- **主力**：Gemini Standard（EST / PWM / 业务 / PM / 运营 / 量化辅助）+ Gemini Enterprise（研发 / 算法）
- **保留**：Copilot Enterprise — 已有 GitHub Enterprise 账号的研发团队保留，覆盖 JetBrains / Vim 等非 VS Code 场景

**理由**：GCP 生态整合度更高 / License 成本更简单 / CMEK 全覆盖  
**代价**：研发 IDE 覆盖灵活度受限（Gemini 不覆盖 Vim / Eclipse）/ 失去 Copilot 15+ 模型选单 / 单一供应商集中度风险

#### 组合 C（保守）：仅 Copilot Enterprise（一刀切）

**理由**：治理最简（一份合同 / 一个 SSO / 统一 License 池）/ GitHub 生态深度绑定团队天然适合  
**代价**：算法部门 1M token 优势被牺牲 / 轻量用户成本翻倍（$39 vs $19）/ 单一供应商锁定 / Kiro 探索机会丧失

---

### 5.2 部门到工具的映射表（按推荐组合 A）

| 部门 | 主力工具 | 备选 | 备注 |
|---|---|---|---|
| 1.1 研发（含 CI/CD） | Copilot Enterprise | Gemini Code Assist Enterprise | 核心资产须配置 Spaces policy 隔离（§1.1 红线） |
| 1.2 量化研究 | Copilot Enterprise（仅辅助脚本） | Gemini Code Assist Enterprise（若有 CMEK 需求） | 策略代码 / 因子库 / Alpha 因子**不进任何 SaaS prompt**（§7.3 红线，无例外） |
| 1.3 算法（AI/ML） | Gemini Code Assist Enterprise | Copilot Enterprise | 1M token 是算法核心优势（§3.4.2）；训练数据集 / 模型权重不进 prompt |
| 1.4 EST | Gemini Code Assist Standard（仅辅助脚本） | Copilot Enterprise（研发共享 GitHub 体系时） | 客户订单 / 持仓 / 盘中数据**绝对不进任何 SaaS prompt**（§7.3 红线，无例外） |
| 1.5 PWM 数据分析 | Gemini Code Assist Standard | Copilot Enterprise（与 GitHub 体系一致时） | 客户画像 / KYC 不进 prompt；数据脱敏流程须先于工具铺开（§1.5 红线） |
| 1.6 业务通用 | Gemini Code Assist Standard | Copilot Enterprise（已纳入 GitHub License 池时） | 池化 License；月度 budget cap 管控（§1.6 小贴士） |
| 1.7 产品经理 / PoC | Gemini Code Assist Standard | Copilot Enterprise（需 Workspace plan-first 时） | 池化 License；PoC 数据若来自真实客户须确认脱敏（§1.7 红线） |
| 1.8 运营 | Gemini Code Assist Standard | — | 池化 License；工具定位为"高级 Excel 公式辅助"，不替代专业开发（§1.8 小贴士） |
| 试点池 | AWS Kiro | — | 限研发 spec-driven 项目，5~10 席，3 个月评估；须在采购前向 AWS 索取书面 DPA（§7.2 信息缺口 #3） |

---

### 5.3 License 池粗估（推荐组合 A）

> 以下是数量级估算，**不报具体单价**（采购另谈）。具体单价见第 2 章 + 附录 vendor-terms.md。

| 工具 / Tier | 推荐席位数 | 备注 |
|---|---|---|
| Copilot Enterprise | N₁ + N₂ × 30%（仅辅助脚本子集） | 研发全员 + 量化辅助子集；N₂ 比例由部门 AI 使用清单核定（§1.2 小贴士） |
| Gemini Code Assist Enterprise | N₃ | 算法部门全员；暂无 CMEK / 1M 刚性需求时可先用 Standard 降成本 |
| Gemini Code Assist Standard（池化） | N₄ + N₅ + N₆ + N₇，预留 20~30% 缓冲 | 池化自助申请，月度 budget cap；缓冲席位用于新员工 / 跨部门试用 |
| AWS Kiro 试点池 | 5~10 个固定席位 | 限研发 spec-driven 志愿者；试点期采用 Pro / Pro+；Enterprise 计划待 DPA 核清后再评 |

**关键说明**：
- EST / 量化席位数须在红线规则核对完成后确定，实际可用比例可能远低于部门总人数
- Copilot credits 超用：2026-06-01 起切换 AI Credits 计费，管理员须为各部门设 budget cap（§2.1）

---

### 5.4 与 Claude Code / Codex 类基准的关系

Claude Code（§3.1.4 / §3.4.4 / §3.6.4）和 Codex（Terminal-Bench 2.0 第一，GPT-5.5 via Codex 82.0%）是本报告 agentic 能力评估的**基准对照工具**，代表当前 AI 代码助手能力顶端水位。**暂不在采购候选**的原因：企业采购通道的中国地区可用性 / 支付渠道 / 合规文件尚未经过本次评估核验；Claude Code 以开发者为中心的自主工作方式，在企业集中管控 / RBAC / 审计日志维度支持度不如三款候选。

**6 个月复审建议**：关注 MCP 生态演进（特别是 Copilot 是否进一步原生支持 MCP）/ Kiro GA 满 1 年时点（约 2026-11）是再评的自然节点（数据训练承诺 + 合规认证应已改善）/ Anthropic / OpenAI 企业采购亚太合规文件完善进度 / benchmark 数据更新。

---

### 5.5 推荐结论

**推荐采用组合 A**。第 4 章适配矩阵 ★★★ 分布是直接决策依据——Copilot Enterprise 在研发 / 量化辅助场景综合优势明确，Gemini Code Assist Standard / Enterprise 在其余 6 个部门首选地位明确，Kiro 以试点身份低成本探索 spec-driven 流程价值，三者边界清晰、治理可控。

**采用工具清单**：
- **GitHub Copilot Enterprise**（研发 / 量化辅助 / 算法备选）
- **Gemini Code Assist Enterprise**（算法首选）
- **Gemini Code Assist Standard**（EST / PWM / 业务 / PM / 运营，池化 License）
- **AWS Kiro**（限研发 spec-driven 项目试点池，5~10 席位，3 个月评估窗口）

**实施前置条件（必须完成，不可并行跳过）**：
1. 法务核对 §7.4 开放清单 5 条（Kiro DPA 须在试点启动前取得书面回复；Copilot CMEK 须在量化部门使用前明确）
2. 数据脱敏 / prompt 输入白名单流程工程化落地（优先级高于工具铺开，EST / 量化 / PWM 无脱敏流程不应开放工具）
3. 三阶段路线图（试点 → 推广 → 全量，详见 § 6.2），分批上线积累经验、降低合规风险

---

## 第 6 章 风险、迁移成本、落地路线图

### 6.1 风险表（4 类主要风险 + 3 类补充风险）

| 风险类别 | 描述 | 影响等级 | 缓解措施 |
|---|---|---|---|
| **合规风险** | 数据出境 / 核心资产泄露 / 监管处罚 | 高 | § 7.3 红线规则 + 法务核对 § 7.4 + 部门级 prompt 白名单 |
| **锁定风险** | License 与厂商深度绑定 | 中 | 退出策略（§ 6.3）+ 组合 A 多供应商天然对冲 |
| **成熟度风险** | Kiro GA 仅 5 个月，能力 / 定价频繁变化 | 中（限试点池范围） | 限 Kiro 为试点池（5~10 席），主力部门不依赖；阶段 1 结束前不扩座 |
| **数据风险** | Prompt 上传厂商服务器，即使不训练也存在第三方暴露面 | 高 | 红线规则（§ 7.3）+ 各部门"不输入哪些内容"清单 + 数据脱敏前置 |
| **License 滥用** | Standard 池化自助申请无审核可能超预算 | 低~中 | 部门申请审批节点 + § 6.4 KPI #2 月度池使用率跟踪 |
| **培训采用** | 非技术部门采用门槛高，License 利用率偏低 | 低~中 | 纳入阶段 2 推广培训计划 |
| **审计追溯** | AI 工具调用记录是否符合证监会 / 交易所审计要求不明 | 中 | 法务核对 § 7.4 开放清单；审计日志覆盖率纳入 § 6.4 KPI #4 |

---

### 6.2 三阶段落地路线图

> 时间线为建议性，实际节奏由管理层根据业务调整。每个阶段设置明确进入门槛，未达门槛不应推进下一阶段。

```
阶段 1：试点（建议 4~6 周）
├─ 选 1~2 个部门：研发 + 算法各 10~20 人
├─ 完成法务核对（§ 7.4 开放清单）— Kiro DPA / Copilot HIPAA / Gemini SOC 等
├─ 验证 SSO / SCIM / 审计日志接入
├─ 部门级"prompt 输入白名单"流程上线（特别是 EST / 量化 / PWM）
└─ 进入阶段 2 的门槛：试点满意度问卷合格 / 生产力增益有记录 / 红线触发次数 = 0

阶段 2：推广（建议 8~12 周）
├─ 扩展到所有研发 + 算法部门
├─ 业务 / PoC / 运营部门池化 License 自助申请流程上线
├─ EST / PWM 启动法务专项核对核心资产红线
├─ 数据脱敏 / 合成数据流程工程化落地
└─ 进入阶段 3 的门槛：推广报告通过 / 红线触发次数 ≤ 3 且均已完成事后审查 / EST / PWM 法务核对书面完成

阶段 3：全量（阶段 2 完成后）
├─ 全部门覆盖
├─ Kiro 试点池保持但不扩张（除非 GA 满 1 年 + 法务条款核对完成）
├─ 启动 6 个月复审机制（§ 5.4）
└─ 持续运营：License 池治理 / 工具升级跟进 / 半年复审
```

**补充说明**：Kiro 试点池 3 个月评估窗口建议与阶段 1 **错开**，在阶段 2 初启动（法务 / SSO 已完成，避免争夺阶段 1 资源）。非技术部门（运营 / PM）建议在阶段 3 纳入，确保培训资源充分准备。

---

### 6.3 退出策略

| 工具 | 触发条件 | 迁移路径 | 迁移成本 | 预期周期 |
|---|---|---|---|---|
| **Copilot Enterprise** | 模型质量下降 / 价格大幅上调 / CMEK 信息缺口 #2 无法解决 | 切换到 Gemini Code Assist Enterprise（同为 IDE 插件架构） | SSO 重新对接：中（1~2 周）；用户培训：轻；GitHub Spaces 数据迁移：重（平台级绑定） | 6~8 周 |
| **Gemini Code Assist** | 模型质量下降 / GCP 生态绑定成本超预期 | 切换到 Copilot Enterprise；或回退自管 BYOK 模型 + 第三方 IDE 插件 | 长上下文工作流调整：中；GCP 生态解绑（算法部门 CMEK / Cloud Workstations）：重 | 8~12 周 |
| **AWS Kiro** | 试点未达预期 / GA 满 1 年仍有显著缺陷 / DPA 法务核对失败 | 直接回退到 IntelliJ / VSCode + Copilot / Gemini（试点性质，无深度依赖） | spec 文档迁移（Markdown，可迁移，但自动化链路无法直接复现）：中；团队习惯调整：轻 | 2~4 周 |

**总结**：Kiro 退出成本最低（试点性质）；Gemini 退出成本最高（GCP 生态绑定）；Copilot 退出成本中等（GitHub 平台绑定是主要成本）。组合 A 的 Copilot + Gemini 双主力天然相互对冲——任一主力出现问题，另一家可作承接方。

---

### 6.4 风险监控指标（KPI）

| # | KPI | 目标值 | 说明 |
|---|---|---|---|
| 1 | **红线触发次数** | 0（任何 1 次触发均需法务介入） | 对应 § 7.3；触发后完成事后审查 / 记录根因 / 更新 prompt 白名单 |
| 2 | **License 池使用率** | Standard 池化 70~85% | > 90% 考虑扩容；< 50% 考虑回收；按部门分组跟踪 |
| 3 | **Token 消耗 vs 预算** | 月度对账，偏差 ≤ 15% | 识别异常消耗（可能是滥用）；Enterprise 级均提供 Admin Console 视图 |
| 4 | **审计日志覆盖率** | 100% | Copilot / Gemini 均支持；Kiro 须阶段 1 法务核对时确认 |
| 5 | **用户活跃度（DAU/WAU）** | 试点期 ≥ 70% 周活；推广期 ≥ 60% 周活 | 低活跃度预警培训不足 / 工具体验不匹配 |
| 6 | **生产力增益自报** | 每季度问卷，目标满意度 ≥ 4/5 | 配合客观 token 消耗数据交叉验证；避免单一维度评估 |

> KPI #1 实时监控；KPI #2 / #3 / #4 每月对账；KPI #5 每两周统计；KPI #6 每季度问卷。半年复审时汇总全部 6 项数据形成综合评估报告，作为"续约 / 调整 / 退出"决策依据。

---

## 第 7 章 合规与可用性约束

> 本章列出三款工具的"硬约束"与"红线规则"。详细厂商条款见 [appendix/vendor-terms.md](appendix/vendor-terms.md)。

### 7.1 硬约束 1：大陆可用性

| 工具 | 大陆企业版可用性 | 备注 | 对部门选型的影响 |
|---|---|---|---|
| GitHub Copilot Enterprise | 走代理可用，无大陆区，所有流量出境到 GitHub/Azure | 个人版国内已正常使用，企业版同链路；无中国区节点（附录 §1.6） | 代理层 SLA 决定可用性下限 |
| Gemini Code Assist Enterprise / Standard | 走代理可用，无大陆区，流量出境到 Google Cloud | Google 服务被防火长城封锁，两版同链路；无中国区节点（附录 §2.6） | 同上 |
| AWS Kiro | 走代理可用，仅 US East / Europe Frankfurt / GovCloud；无大陆区 | 2025-11-17 GA，区域有限；无中国大陆节点（附录 §3.6） | 延迟与稳定性低于 Copilot / Gemini，必须考虑代理层 SLA |

**结论**：三款都是"出境 + 走代理"路线，无一可走纯境内合规通道。**任何部门要用都必须默认接受 prompt 出境到海外厂商。**

### 7.2 硬约束 2：数据训练 / 留存

| 维度 | Copilot Enterprise | Gemini Code Assist Ent | AWS Kiro |
|---|---|---|---|
| 零训练承诺 | 明确（Business / Enterprise 不用于训练）— 附录 §1.1 | 明确（Standard / Enterprise 不用于训练）— 附录 §2.1 | **未在公开材料中明确说明**（信息缺口 #3）— 附录 §3.1 |
| 数据留存 | 代码保留控制（Code Retention Controls）；Enterprise Cloud 支持地理数据驻留 — 附录 §1.2 | 无状态服务，不存储 prompt/response；TLS 加密传输，静态默认加密 — 附录 §2.2 | 未在公开材料中明确说明留存期限或控制机制 — 附录 §3.2 |
| CMEK | 未在公开材料中明确说明（信息缺口 #2）— 附录 §1.4 | 明确支持 — 附录 §2.4 | 未在公开材料中明确说明 — 附录 §3.4 |
| 审计日志 | 集成 GitHub 现有 Audit Log；Enterprise 管理员可访问合规报告 — 附录 §1.3 | 支持 Cloud Logging 桶存储（可选），支持管理活动审计 — 附录 §2.3 | 管理员可通过 AWS Management Console 监控；详细审计能力未在公开材料中明确说明 — 附录 §3.3 |
| 合规认证 | SOC 2 Type I + ISO 27001（HIPAA/BAA 缺口 #1）— 附录 §1.5 | ISO 27001 / 27017 / 27018 / 27701；SOC 1 / 2 / 3 — 附录 §2.5 | 未在公开材料中明确说明（缺口 #4）— 附录 §3.5 |

**总结**：零训练承诺：Copilot / Gemini 均有明确书面声明，Kiro 暂无（采购前须向 AWS 索取书面 DPA）。CMEK：仅 Gemini 明确支持。Copilot SOC 2 仅 Type I（设计验证，非运营有效性）。合规认证最完整：Gemini（SOC 1/2/3 全覆盖 + 多项 ISO）。**落地前必须由法务逐条核对最新条款原文，条款可能随时变更。**

### 7.3 说明 3：核心资产泄露红线

#### 三款企业版的"零训练" ≠ "零风险"

- Prompt 仍上传厂商服务器做推理（即使明确承诺不用于训练）
- 一旦服务商被攻破 / 内鬼 / 司法调取，数据可能落入第三方

#### 红线规则

> **核心资产相关的代码 / 数据建议规避走 SaaS 路径。**

#### 适用部门点名

特别影响以下三个部门（详见第 1 章对应小节"风险红线"栏）：
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

上述 5 条与 [appendix/vendor-terms.md §4](appendix/vendor-terms.md#4-信息缺口待法务--合规核对) 已识别的 5 条调研信息缺口（Kiro 零训练承诺缺口 #3 / Kiro 合规认证缺口 #4 / Copilot HIPAA/BAA 缺口 #1 / Copilot CMEK 缺口 #2 / LiveCodeBench 数据缺口 #5）高度重叠，采购谈判时建议一并核对。

---

## 附录索引

- [评测方法 methodology.md](appendix/methodology.md)
- [厂商条款 vendor-terms.md](appendix/vendor-terms.md)
- [Benchmark 引用 benchmarks.md](appendix/benchmarks.md)
