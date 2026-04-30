# 代码助手企业选型分析报告

> **截至**：2026-04-29
> **目标读者**：技术管理层 / 产品经理
> **决策方案**：B（按部门组合 2~3 款工具）
> **基线对照**：Claude Code / Codex（仅在关键能力点出现）

---

## 第 0 章 TL;DR + 推荐总览
<!-- TBD: Task 14 -->

## 第 1 章 部门场景画像 & 推荐结论

### 1.0 章节引言

本章是全文的**决策入口**。技术管理层和产品经理可以只读本章 + 第 4 章矩阵 + 第 5 章推荐方案，即可完成工具选型决策，无需通读第 2~3 章的能力深度分析。

本章对公司 8 个业务部门逐一给出"场景画像 + 推荐结论"，结论均有第 3 章能力依据和第 7 章合规约束作为支撑，不凭直觉下判断。每个部门按固定 5 栏结构展开：

- **画像**：角色 / 典型场景 / 关键诉求
- **首选工具 + 理由**：引用 § 3.x / § 7.x 能力评级
- **次选工具 + 适用条件**：满足特定前提时的替代方案
- **不推荐项 + 原因**：明确排除，避免浪费评估资源
- **风险红线 + 小贴士**：该部门必须遵守的合规底线

**部门顺序**：1.1 研发 → 1.2 量化研究 → 1.3 算法（AI/ML）→ 1.4 EST → 1.5 PWM 产品数据分析 → 1.6 业务通用 → 1.7 产品经理/PoC → 1.8 运营

三款候选工具在大陆可用性上均存在"出境 + 走代理"的硬约束（§ 7.1），不存在纯境内合规通道，本章不重复说明，各部门红线只关注资产级风险。

---

### 1.1 研发（含 CI/CD 子场景）

#### 画像

角色：后端 / 前端 / 全栈工程师、SRE / DevOps。典型场景：大型金融系统长期维护（50 万行以上代码库）、跨模块重构、Spec-driven design（需求文档 → 设计稿 → 实现）、Code Review 协助、Bug 修复、CI/CD 流水线脚本开发。

**关键诉求**：仓库级上下文感知（数十万行规模）、多文件 Agent 编排与计划审阅、稳定的 JetBrains / VS Code IDE 集成、跨会话长生命周期 Memory、Git/PR 工作流深度集成、可在 CI/CD 子场景触发 Agent 任务。

#### 首选：GitHub Copilot Enterprise

研发场景对 harness 工程质量要求最高（§ 3.1.5），Copilot Enterprise 在以下维度具有领先优势：

- **IDE 覆盖最广**（§ 3.6.2）：覆盖 VS Code、JetBrains 全系（IntelliJ IDEA、PyCharm 等）、Vim/Neovim、Visual Studio 等 12+ 平台，适合公司多元 IDE 基础，无需统一切换 IDE
- **GitHub 代码搜索 + Spaces**（§ 3.4.2）：Copilot Spaces 是三款候选中跨仓上下文聚合能力最明确可用的，在微服务架构或多仓库场景下聚合上下文效率最高
- **Agent Mode + Copilot Workspace GA**（§ 3.1.2）：Copilot Workspace 实现 plan-first 流程（需求 → 实施计划 → 分支执行 → PR），IDE 内 Agent Mode 支持多文件自主修改，均已 GA
- **Marketplace + Extensions 生态成熟**（§ 3.6.2）：Jira、Confluence 等开发工具有现成 Extension，研发团队落地摩擦最低
- **License 池管理与 SSO 成熟**（§ 3.5）：集成 GitHub Enterprise 现有 SAML SSO，管理员可集中控制模型访问权限和 MCP 工具权限

综合评级：**强**（§ 3.6 扩展性）、**一般**（§ 3.1 harness）——harness 绝对值不及 Claude Code，但在三款候选中综合适配度最高。

#### 次选：Gemini Code Assist Enterprise

**适用条件**：团队已深度绑定 GCP 生态（BigQuery、Cloud Workstations、GCP 云原生 CI/CD）；或有 CMEK 数据主权严格要求（§ 7.2，Gemini 是三款中唯一明确支持 CMEK 的）；或需要 1M token 上下文处理超大型代码文件（§ 3.4.2）。

**主要短板**：模型选单封闭，仅 Gemini 系列可选，无跨厂商切换能力（§ 3.2）；harness 工程整体相对最落后，Plan mode 评级**弱**（§ 3.1.2）；无原生 Spec-driven 流程，跨会话 Memory 为无状态服务（§ 3.1.2）。

#### 不推荐：AWS Kiro

- GA 仅约 5 个月（§ 2 成熟度警示框），长生命周期工程项目的稳定性未经企业验证
- 独立 IDE 形态（§ 3.6.2 评级**弱**）：已深度使用 IntelliJ / PyCharm 的 Java/Python 研发团队切换成本过高，不适合作为主力工具
- 建议用于小规模 Spec-driven 流程探索的**试点池**，而非研发主力

#### 风险红线 + 小贴士

- **核心资产**：风控引擎、核心算法等代码段参照 § 7.3 红线，不建议进入任何 SaaS prompt；可通过 Copilot Spaces 配置 Policy 限制索引范围，排除核心资产目录
- **CI/CD 子场景**：Copilot 在 GitHub Actions 集成最深，可通过 GitHub Actions 事件触发 Copilot Workspace 任务；Kiro Hooks / Gemini 与 GitHub Actions 整合需借道 Marketplace 或自建适配层
- **与 Claude Code 基准的对照差距**（§ 3.1.4）：Claude Code 的 plan mode + Subagents + Skills 三层扩展在大型重构 / Spec-driven 项目上具有 30%+ 的任务完成率优势。若公司未来能引入 Claude Code 作为高级研发岗工具，建议与 Copilot Enterprise 分层使用：Copilot 承担日常补全与 CR，Claude Code 承担复杂 Agent 任务

---

### 1.2 量化研究

#### 画像

角色：量化研究员、策略开发工程师。典型场景：因子开发（Python / C++ / KDB+/Q）、回测框架建设与维护、信号挖掘脚本、策略代码调试。

**关键诉求**：数学 / 统计推理正确性、Notebook 友好度、回测代码模式识别、**极严格的数据隔离**（策略代码 / 因子库是公司核心竞争资产）。

#### 首选：GitHub Copilot Enterprise（辅助脚本场景）

适合用于**非核心策略代码**的辅助开发：

- **IDE 覆盖**（§ 3.6.2）：JetBrains 全系 + VS Code 对 Python 量化生态友好，无需更换开发环境
- **模型选单宽**（§ 3.2）：可按需切换至 Claude Opus 4.x（SWE-bench 87.6%，§ 3.3），数学推理能力强，适合复杂因子逻辑的辅助分析
- **Memory + Spaces**（§ 3.1.2）：在三款候选中跨会话记忆相对最完善（Public Preview 阶段），可维护回测框架级别的上下文

**重要前提**：策略代码 / 因子库核心逻辑**不应输入任何 SaaS prompt**（§ 7.3 红线）；工具仅用于辅助脚本（数据清洗、可视化、单元测试模板），严格限制使用范围。

#### 次选：Gemini Code Assist Enterprise

**适用条件**：量化团队有长 prompt 分析需求（论文级公式推导 + 多文件代码组合），需要 1M token 上下文（§ 3.4.2）；或有 CMEK 数据主权要求（§ 7.2）。

CMEK 支持是 Gemini 在量化场景的差异化优势——如果公司要求模型处理的 prompt 数据由自己掌握加密密钥，Gemini Enterprise 是三款候选中唯一可选项。

#### 不推荐：AWS Kiro

- 数据训练承诺为**信息缺口 #3**（§ 7.2），Kiro 尚无公开的零训练承诺书面声明
- 量化策略是公司最高级别核心资产，在数据处理条款未明确前，规避使用任何承诺不清晰的 SaaS 工具
- 建议在 Kiro 向 AWS 销售索取书面 DPA 并通过法务审核后，才可重新评估

#### 风险红线 + 小贴士

- **核心红线**（§ 7.3）：因子库 / 信号策略 / 回测核心逻辑 = 公司最高级核心资产，**不进任何 SaaS prompt**，无例外
- **实施建议**：建立部门内"AI 工具使用清单"，明确哪些代码段可用（回测框架工具函数、数据清洗脚本）、哪些不可用（Alpha 因子逻辑、持仓优化算法）
- **BYOK 诉求**：量化部门可能是中长期 BYOK 诉求最强的部门；三款工具均未明确支持标准 BYOK 端点（§ 3.2），采购谈判时应专项索取书面说明
- **与 Claude Code 基准的对照差距**（§ 3.4.4）：Claude Code 的探索式上下文理解（不预先建立持久化索引）天然与量化代码的保密要求对齐——策略代码仅在单次推理时过模型，不在任何厂商云端建档存档。此特性是 Claude Code 在量化场景的结构性合规优势，当前三款候选均无对应机制

---

### 1.3 算法（AI / ML）

#### 画像

角色：ML / DL 工程师。典型场景：模型训练脚本开发（PyTorch / HuggingFace）、推理服务优化、特征工程、Notebook 迭代、GPU / 集群资源脚本、调参实验追踪（MLflow / W&B）。

**关键诉求**：1M 级长上下文（论文 + 模型配置 + 数据 schema 的联合分析）、ML 框架熟悉度、Notebook + 训练脚本混合场景、GPU 集群脚本生成、接入 HuggingFace / MLflow / W&B 等科研工作台。

#### 首选：Gemini Code Assist Enterprise

算法场景的任务特点是"短周期迭代 + 长上下文输入"，与 Gemini 的优势高度匹配：

- **1M token 上下文是核心优势**（§ 3.4.2）：处理论文级 prompt（模型配置文件 + 数据集 schema + 多 Notebook 跨文件联合分析）时，1M 上下文缓冲空间是其他两款不具备的
- **Gemini 2.5 Pro 底层模型**（§ 3.3）：在数学 / 代码任务上具有竞争力，Terminal-Bench 2.0 榜单中 Gemini 3.1 Pro 排名前三（待后续版本确认）
- **GCP 生态深度集成**（§ 3.6.2）：Cloud Workstations / Cloud Shell 多端覆盖，适合云原生 ML 工作流；BigQuery 集成对特征数据处理有直接价值
- **MCP Server 接入**（§ 3.6.2）：Agent Mode 支持 MCP Server 连接，理论上可接入 HuggingFace / W&B MCP Server，但接入细节需实测确认

算法场景任务短平快（调参、脚本迭代），harness 落后（§ 3.1 评级**弱**）的短板相对可接受（§ 3.1.5）。

#### 次选：GitHub Copilot Enterprise

**适用条件**：算法团队已绑定 GitHub 生态，或需要访问多个旗舰模型（如 Claude Opus 4.x 进行复杂数学推理，§ 3.2）；团队主力 IDE 为 JetBrains PyCharm / VS Code，不希望迁移工作环境。

模型选单宽是 Copilot 在此场景的主要优势，可按任务复杂度切换模型。

#### 不推荐：AWS Kiro

- Notebook 支持不明（§ 3.6，Kiro 独立 IDE 不支持 JetBrains，Notebook 使用体验待实测）
- 算法团队工作流与 AWS 生态不一定深度绑定；Kiro Spec-driven 流程对短周期 Notebook 迭代场景是负担
- 建议作为可选试点，而非主力工具

#### 风险红线 + 小贴士

- 训练数据集 / 模型权重 / 预训练 checkpoint 不应进入 prompt（数据规模大且可能包含业务数据，§ 7.3）
- HuggingFace token / WandB API key / GCP Service Account 等敏感凭证须走公司 Secret Scanner，不能硬编码后被 AI 读取
- 建议在试点阶段重点测试 PyTorch / HuggingFace 生态的补全和重构质量（§ 3.3 中金融小众语言列为"待实测确认"，ML 框架同理）
- **与 Claude Code 基准的对照差距**（§ 3.4.4）：Claude Code 的长上下文实战可用率（基于探索式工具调用，而非声称 token 数）在论文级 prompt 处理上有实战验证；Gemini 1M token 是声称上限，实际可用率高度依赖手动上下文构造（§ 3.4.2）

---

### 1.4 EST（Equities Sales & Trading）

#### 画像

角色：交易员、销售交易、交易支持。典型场景：盘中数据查询辅助脚本、一次性 Python / VBA / SQL / Excel 自动化、Bloomberg / Wind 数据接口脚本、报表生成。

**关键诉求**：极低门槛（非工程师背景为主）、自然语言交互强、Web / 桌面多端可用、**不强制要求 Git 工作流**、**强合规约束**（客户订单 / 持仓数据高度敏感）。

#### 首选：Gemini Code Assist Standard（限定辅助脚本）

- **计费亲民**（$19/月，§ 2.2）：适合交易员个人订阅，无需 IT 统一采购高价 Enterprise 计划
- **Web / IDE 多端覆盖**（§ 3.6.2）：VS Code + JetBrains 可用，对交易支持团队覆盖充分
- **自然语言交互友好**：Gemini 2.5 Pro 在自然语言→代码任务（如"生成一个查询过去 30 天订单量的 SQL 模板"）上表现良好
- **单文件 / 临时脚本场景友好**：无状态服务（§ 3.1.2）对一次性脚本场景反而降低了管理复杂度，无需维护跨会话项目状态

**严格限定**：仅用于辅助脚本开发（数据清洗模板、报表自动化脚本），**绝不输入客户数据 / 订单数据 / 持仓信息**（见风险红线）。

#### 次选：GitHub Copilot Enterprise

**适用条件**：交易支持团队已与研发部门共用 GitHub Enterprise 账户体系，可直接复用已购 Copilot Enterprise License，无需单独采购 Gemini 订阅。使用规范与首选相同：仅辅助脚本场景，不输客户敏感数据。

#### 不推荐：AWS Kiro

- Kiro 为独立 IDE 形态（§ 3.6.2 评级**弱**），需要用户切换 IDE，对技术水平参差不齐的 EST 群体门槛过高
- AWS 生态对 EST 工作流（Bloomberg / Wind / 内部交易系统）非天然适配，额外集成成本不值

#### 风险红线 + 小贴士

- **核心红线**（§ 7.3）：**客户订单数据 / 持仓信息 / 内部盘中价格曲线绝对不可输入任何 SaaS prompt**；此红线优先于所有工具效率收益，无例外
- **监管合规待核**（§ 7.4）：证监会 / 交易所对盘中数据的审计要求是否延伸至 AI 工具调用记录，须请法务专项核对（§ 7.4 开放清单第 2 条）
- **实施建议**：部门级"prompt 输入白名单"（允许输入：SQL 查询结构模板、VBA 函数框架；禁止输入：真实订单数据、实时持仓、客户账户信息）；白名单应书面化，纳入合规培训
- **不要求 Git**：Gemini Standard 的单文件场景使用体验对无 Git 背景用户友好；若选择 Copilot，须向用户明确说明不需要创建 GitHub 仓库也可正常使用 Chat 模式

---

### 1.5 PWM 产品数据分析

#### 画像

角色：PWM 产品经理、客户数据分析师。典型场景：客户画像分析、产品业绩对比、SQL 查询生成（BigQuery / PostgreSQL）+ Excel / Pandas 数据处理、定期报表生成、一次性数据探索脚本。

**关键诉求**：自然语言→SQL 转换能力强、Excel / Pandas 场景友好、单文件 / 一次性脚本工作流（不强求 Git）、合规约束（客户画像 / 持仓数据敏感）。

#### 首选：Gemini Code Assist Standard

- **BigQuery 集成深度最高**（§ 3.6.2）：若 PWM 数据仓库在 GCP/BigQuery 上，Gemini 的自然语言→BigQuery SQL 生成有 GCP 原生支持，是三款候选中集成路径最短的
- **自然语言→SQL 能力强**：Gemini 2.5 Pro 在 SQL 生成任务上表现良好，适合"用自然语言描述业务需求 → 生成查询"的高频工作模式
- **计费亲民**（$19/月，§ 2.2）：数据分析师个人订阅成本低
- **单文件 / 无 Git 依赖**：无状态服务特性（§ 3.1.2）对一次性分析脚本场景友好，不引入不必要的 Git 流程

#### 次选：GitHub Copilot Enterprise

**适用条件**：团队已统一使用 GitHub Enterprise，希望与研发部门共用 License 池，避免多套账户管理；数据仓库不在 GCP 上，GCP 集成优势消失。VS Code + Jupyter 集成成熟（§ 3.6.2），对 Pandas / Notebook 工作流支持良好。

#### 不推荐：AWS Kiro

- 客户画像 / KYC 信息 / 持仓数据属于 § 7.3 核心资产；Kiro 的零训练承诺为信息缺口 #3（§ 7.2），数据处理条款未明确前，不适合接触客户数据的部门使用
- 独立 IDE 门槛对数据分析师群体过高

#### 风险红线 + 小贴士

- **核心红线**（§ 7.3）：客户画像 / KYC 信息 / 持仓明细 / 资产配置数据**不进任何 SaaS prompt**；如需分析，须先完成数据脱敏 / 合成数据替换
- **数据脱敏前置**：数据脱敏 + 合成数据流程的工程化建设，优先级应高于 AI 工具铺开；没有脱敏流程，AI 工具无法在数据分析师中安全落地
- **prompt 模板库**：建立公司专用"数据分析 prompt 模板库"（按业务场景固化最优 prompt 结构），既提升效率，也作为合规边界的操作指引
- **与 Claude Code 基准的对照差距**（§ 3.6.4）：Claude Code Skills 可以固化"客户画像分析最佳实践"为可复用的 `/data-analysis` 工作流模板；三款候选均无对应机制，团队需自行维护 prompt 模板库作为替代

---

### 1.6 业务部门（通用）

#### 画像

角色：Business Analyst（BA）、运营支持、数据团队、流程改进人员。典型场景：数据拉取脚本、报表自动化（Python / SQL / Excel VBA）、流程小工具开发、文档辅助。

**关键诉求**：极低门槛、模板驱动、自然语言为主、**可能完全不接触 Git**、合规意识参差不齐（需要简单清晰的使用规范）。

#### 首选：Gemini Code Assist Standard

- **计费亲民**（$19/月，§ 2.2）：适合按需发放，无需大规模采购
- **Web 端 + IDE 多端友好**（§ 3.6.2）：非工程师用户通过 VS Code + Gemini 插件即可上手
- **自然语言能力强**：Gemini 2.5 Pro 的自然语言→代码转换对描述性输入（"帮我写一个每周自动发送销售数据报表的 Python 脚本"）响应质量良好
- **无状态服务**（§ 3.1.2）：对一次性脚本场景无额外管理负担

#### 次选 / 不推荐

- **次选：GitHub Copilot Enterprise**（前提：部门已被纳入公司 GitHub Enterprise License 池，无需单独采购）
- **不推荐：AWS Kiro**：独立 IDE 形态（§ 3.6.2 评级**弱**）门槛过高，非技术人员无法独立上手，运维成本高

#### 风险红线 + 小贴士

- 业务数据若涉及客户 / 财务 / 持仓信息，参照 § 7.3 红线；BA 群体的合规边界认知需要通过培训强化
- **推广建议**：池化 License 自助申请机制（IT 统一采购 + 员工按需申领），配合月度 token 消耗预算 cap，避免超支
- 工具定位：对业务部门而言，AI 代码助手应被视作"高级 Excel 公式辅助"，**不替代专业开发**；涉及生产系统的脚本须经研发团队 Code Review

---

### 1.7 产品经理 / PoC

#### 画像

角色：产品经理、产品设计。典型场景：PoC 原型快速验证（0 → 1 应用生成）、Demo 页面开发（React / HTML）、轻量前端交互原型、需求文档辅助。

**关键诉求**：0-1 项目快速生成能力、UI 预览友好、快速迭代（不要求工程化的代码质量）、**几乎不要求工程背景**。

#### 首选：Gemini Code Assist Standard

- **Agent Mode（1M 上下文）适合 PoC 原型生成**（§ 3.1.2 / § 3.4.2）：一次性注入完整原型需求描述 + 设计规范，生成完整 PoC 代码，无需分多次 Chat
- **Web 端友好**（§ 3.6.2）：产品经理无需安装专业 IDE，Web 入口可快速上手
- **计费亲民**（$19/月，§ 2.2）：PoC 场景频次不高，Standard 层已足够
- **GCP 预置集成**（§ 3.6.2）：若 PoC 需要快速接入 Firebase / BigQuery 作为数据后端，GCP 生态加速落地

#### 次选：GitHub Copilot Enterprise

**适用条件**：PM 已熟悉 GitHub 工作流，需要 Copilot Workspace 的 plan-first 流程（需求 → 实施计划 → 代码生成）来快速对齐研发的技术实现路径（§ 3.1.2）。Workspace 的结构化规划对 PoC 阶段的需求澄清有额外价值。

#### 不推荐：AWS Kiro

- Kiro 的 Spec-driven 三段式流程（EARS 记法需求 → 设计文档 → 任务列表，§ 3.1.2 评级**强**）在 PoC 速度场景**反而是负担**——PoC 追求快速验证，不需要工程化的 EARS 需求文档；Spec-driven 流程适合长生命周期项目，对 PoC 场景是过度设计
- Kiro 独立 IDE 对非工程师 PM 群体门槛过高（§ 3.6.2）

#### 风险红线 + 小贴士

- PoC 原型通常不涉及核心资产，§ 7.3 红线压力相对最低
- 但如果 Demo 数据来源于真实客户脱敏数据，仍须确认脱敏已完整，再输入 prompt（§ 7.3 适用）
- **与 Claude Code 基准的对照差距**（§ 3.6.4）：Claude Code 的 `/frontend-design` skill 是 PoC 场景的差异化能力，可以按品牌规范生成高质量 UI 原型；三款候选均无对应机制，PM 需要通过 prompt 工程手动描述 UI 风格要求

---

### 1.8 运营

#### 画像

角色：运营、内容、增长团队。典型场景：简单数据处理脚本、报表自动化（Excel / Python）、可能涉及 RPA 流程配置、内容摘要辅助。

**关键诉求**：极低门槛、模板化操作、**几乎不写代码只描述需求**、几乎不接触 Git。

#### 首选：Gemini Code Assist Standard

- **自然语言能力强、Web 端易用**（§ 3.6.2）：适合"描述需求 → 生成脚本"的操作模式，无需了解编程基础
- **计费亲民**（$19/月，§ 2.2）：按需分配，不浪费

#### 次选 / 不推荐

- **不需要主力工具**：运营团队的 AI 编程需求频次低且场景简单，池化 License 自助申请即可满足，无需单独配置专项工具预算
- **Kiro / Copilot Enterprise** 对此场景过度——运营场景不需要仓库级上下文、Agent Mode、Spec-driven 流程等企业级能力，引入会增加不必要的操作复杂度

#### 风险红线 + 小贴士

- 客户行为数据 / 用户个人信息若作为输入，参照 § 7.3 红线处理；运营数据脱敏要求须纳入培训
- **工具定位**：对运营团队而言，AI 代码助手应被视作"高级 Excel 公式"辅助工具，不替代专业开发，不用于生产系统操作


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

#### 3.1.1 维度说明

"Harness Engineering"指一款工具如何将语言模型"组装"成可用的 agentic 工作流——包括系统提示构造、工具调用协议、规划机制、子任务编排、跨会话记忆以及权限控制。选型时常见的认知偏差是把精力集中在底层模型比分上；但实测中，模型相近的两款工具，因 harness 设计差异，长链路 agentic 任务的完成率可能相差 30% 以上。Harness 决定了"模型怎么被使唤"：上下文被如何裁剪与注入、工具调用失败后是否有重试与回滚、规划结果能否被用户审阅与干预、记忆是会话内短期还是跨项目长期。本节聚焦 6 个子能力点，对三款候选工具做并列说明，并以 Claude Code 作为当前顶尖水位基准对照。

#### 3.1.2 三款现状对照

##### Copilot Enterprise

**1. 系统提示与上下文构造**（**一般**）

Copilot Enterprise 通过 Custom Instructions（全局指令文件）和 Copilot Spaces（项目级共享知识库）向模型注入上下文。Enterprise 层级支持对整个组织 codebase 进行索引，为 Chat 和 Agent Mode 提供深度代码库感知。但系统提示构造的具体 token 预算、截断策略未在公开材料中明确说明，用户对"模型在任意 agent 调用中看到哪些上下文"的可视性有限。

**2. 工具调用机制**（**一般**）

Agent Mode（2026-03 GA）支持多文件修改、终端命令执行和 MCP Server 工具调用。工具调用失败后的重试逻辑和循环防护机制未在公开材料中明确说明；Copilot Workspace（Cloud Agent）的工具执行发生在 GitHub 托管的云端 sandbox 中，本地 IDE 的工具调用发生在 Agent Mode 内，两条路径的错误恢复策略不统一。

**3. Plan mode 与 Spec-driven 流程**（**一般**）

Copilot Workspace 实现了 plan-first 流程：输入自然语言需求 → 生成研究报告 + 实施计划 → 用户审阅修改 → 在分支执行代码变更 → 生成 PR。这与 Plan Mode 的"先规划后执行"理念吻合，但该能力仅在 Workspace（Cloud Agent）中完整可用，IDE 内 Agent Mode 没有同等结构化的规划阶段，两者存在能力断层。Copilot Memory + Spaces 可在多次任务间保留项目上下文，作为轻量级 spec 持续更新的基础。

**4. Subagent / 多 agent 编排**（**一般**）

支持将任务委派给外部第三方 Coding Agents（Claude by Anthropic、OpenAI Codex 等），GitHub Marketplace 集成提供了借道外部 agent 能力的通道。但这是"委派给外部系统"而非"内部原生多 agent 编排"——Copilot 自身没有类似 Claude Code Subagents 的独立上下文子任务机制；Custom Agents 可创建专用版本的 Copilot Cloud Agent，但并行协作调度能力未在公开材料中明确说明。

**5. Memory（会话内 / 跨会话 / 项目级）**（**一般**）

三层记忆机制：Copilot Memory（Public Preview，自动推断并存储仓库相关信息）、Copilot Spaces（持久知识库，手动构建项目上下文）、Custom Instructions / Prompt Files（规则文件，项目或用户级）。Memory 功能仍为 Public Preview 阶段，稳定性和功能边界未完全确立。与 Gemini 的无状态设计相比，Copilot 的跨会话记忆机制相对完善；但与 Claude Code 的 CLAUDE.md + Auto Memory 多级作用域体系相比，结构化程度仍有差距。

**6. 权限模型**（**一般**）

管理员可控制模型访问权限、MCP 工具权限和功能启用；Agent Mode 执行敏感操作（如文件修改、终端命令）前会在 IDE 内提示用户确认。细粒度的 auto-approve 规则和 sandboxing 隔离机制未在公开材料中明确说明，运维和安全管控层面的可配置性相对有限。

**综合评级：一般**

---

##### Gemini Code Assist (Standard / Enterprise)

**1. 系统提示与上下文构造**（**一般**）

Agent Mode 基于 Gemini 2.5 Pro 的 1M token 上下文窗口，可一次性注入超大型仓库的代码文件，这是三款候选工具中上下文容量最大的。但 Gemini Code Assist 是无状态服务，不存储提示词和响应；每次调用需重新构造上下文，没有跨会话的上下文累积机制，利用 1M 窗口的前提是用户主动传入代码内容。Enterprise 私有代码库定制（接入 GitHub/GitLab/Bitbucket）可在推荐层面提升上下文相关性，但并非运行时动态注入。

**2. 工具调用机制**（**一般**）

Agent Mode 支持自主多步骤编码和"Finish Changes"功能；支持 MCP Server 连接（来源：Gemini Code Assist 官方文档）。但工具调用失败时的重试策略、循环防护和回滚机制未在公开材料中明确说明；Subagent / 多 agent 协作能力未在公开材料中明确说明，工具调用的并行度和错误恢复路径相对不透明。

**3. Plan mode 与 Spec-driven 流程**（**弱**）

Agent Mode 支持自主多步骤编码，但没有类似 Copilot Workspace 的显式 plan 产出阶段，也没有类似 Kiro 的结构化需求文档（EARS 记法）生成流程。"Finish Changes"是能力终点，而非规划起点。用户无法在执行前审阅和修改一份结构化计划——这是 Gemini 在 harness 工程上相对最薄弱的一环。未在公开材料中找到 Spec-driven 相关的明确产品声明。

**4. Subagent / 多 agent 编排**（**弱**）

未在公开材料中明确说明多 agent 协作或子任务编排能力。Gemini Code Assist 的 Agent Mode 是单一 agent 自主执行，没有原生的 lead agent + subagent 协调机制；也没有通过 Marketplace 接入外部 agent 的官方通道（不同于 Copilot 的第三方委派模式）。

**5. Memory（会话内 / 跨会话 / 项目级）**（**弱**）

Gemini Code Assist Standard/Enterprise 是无状态服务，不在 Google Cloud 存储提示词和响应（来源：Gemini Code Assist 数据治理文档）。没有跨会话记忆机制，没有类似 CLAUDE.md 或 Steering Files 的项目级规则配置文件机制。每次会话对话历史从零开始，长期维护的项目上下文必须依赖用户手动重传或 Enterprise 私有代码库定制功能作为间接补偿。

**6. 权限模型**（**一般**）

Enterprise 层级支持 VPC Service Controls 网络隔离，这是三款候选中唯一有网络层沙箱隔离的方案；管理员可通过 Google Workspace 管理控制台配置访问策略。但 Agent Mode 执行具体操作前的细粒度用户确认机制（如哪些工具调用需要 gate、哪些可 auto-approve）未在公开材料中明确说明。VPC-SC 是基础设施层强项，操作层的权限粒度相对不透明。

**综合评级：弱**（harness 整体是三款候选中相对最不完整的；1M 上下文是竞争力，但属于模型层而非 harness 层能力）

---

##### AWS Kiro

**1. 系统提示与上下文构造**（**强**）

Steering Files 是 Kiro 的项目级系统提示机制——开发者可在项目根目录配置全局或项目级 AI 行为规则（编码标准、工作流偏好、团队规范），Kiro 在每次 agent 任务中自动加载，效果类似 CLAUDE.md（来源：kiro.dev 官方文档）。Kiro 的"高级上下文管理"宣称可感知项目结构，具体 token 预算未在公开材料中明确说明。Steering 机制使系统提示可版本化、可 Git 管理，是三款候选中上下文构造最结构化的。

**2. 工具调用机制**（**强**）

原生支持 MCP Server（连接文档、数据库、API），管理员可控制 MCP 访问权限（来源：kiro.dev 官方文档）。Agent 执行过程中支持 Checkpointing（步骤回滚），这是三款候选中唯一明确支持 agent 任务回滚的工具。Hooks 机制实现了事件驱动的工具调用触发（文件保存等事件触发后台 AI 任务），类似 CI hooks 的工具链集成。错误恢复的具体重试逻辑未在公开材料中明确说明，但 Checkpointing 提供了人工干预的回退通道。

**3. Plan mode 与 Spec-driven 流程**（**强**，核心卖点）

Kiro 的 Spec-driven workflow 是三款候选中最完整的规划执行分离机制，是 Kiro 的核心差异化卖点。三段式流程：**①** 自然语言需求输入 → 转换为 EARS（Easy Approach to Requirements Syntax）记法的结构化需求文档；**②** 从需求生成设计文档 + 任务分解列表；**③** 按任务列表逐步执行代码实现（可人工审阅并修改每个阶段的产出）。每个阶段的产出均为可读的结构化文档，用户可在执行前审阅、修改甚至重写，确保 AI 执行的是被审阅过的意图，而非自由发挥（来源：kiro.dev 官方文档）。与 Copilot Workspace 的 plan-first 相比，Kiro 的 spec 层更完整（含 EARS 记法需求 + 设计文档），且作为独立 IDE 的原生能力而非附加 Cloud Agent。

**4. Subagent / 多 agent 编排**（**一般**）

支持"Collaborative Agents"（面向团队规模 AI 集成的 GA 新功能，来源：kiro.dev/blog/general-availability）；Kiro CLI 提供终端工作流支持。详细的多 agent 协作机制（是否有 lead agent / subagent 上下文隔离、并行调度策略）未在公开材料中明确说明，与 Claude Code 的 Subagents + Agent Teams 相比，成熟度待观察。

**5. Memory（会话内 / 跨会话 / 项目级）**（**一般**）

Steering Files 提供跨会话的项目级规则持久化，等效于项目级 Memory 的"规则层"（来源：kiro.dev 官方文档）。但没有类似 Claude Code Auto Memory 的自动事实累积机制——Steering 是手动维护的规则文件，而非 AI 自动从对话历史中提取并沉淀的知识。Spec 文档本身也可以扮演部分项目状态存储的角色（需求文档版本化后在 Git 中留存），但这需要开发者主动维护工作流，而非系统自动处理。

**6. 权限模型**（**强**）

管理员可控制 MCP 访问权限（Enterprise 层级）；Agent 执行代码修改时提供 diff 审批工作流（代码差异审阅 + 接受/拒绝），这是三款候选中最明确的"操作前 gate"机制（来源：kiro.dev 官方文档）。Checkpointing 提供了执行中回滚的安全网。Kiro 基于 AWS IAM Identity Center，SSO + SCIM 成熟度高，组织级访问控制有 AWS 基础设施背书。细粒度的 auto-approve 规则配置能力（等价于 Claude Code 的 acceptEdits 模式）未在公开材料中明确说明。

**综合评级：强**（Spec-driven 是领先差异；整体 harness 设计最成熟）

---

#### 3.1.3 子能力对照表

| 子能力 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| 系统提示 / 上下文构造 | **一般**：Custom Instructions + Spaces 支持项目上下文注入，但构造透明度有限 | **一般**：1M token 窗口（模型层优势），无跨会话上下文累积，无规则文件机制 | **强**：Steering Files 实现结构化可版本化系统提示，三款中最规范 |
| 工具调用 / 错误恢复 | **一般**：Agent Mode 支持多文件修改 + MCP；重试 / 循环防护未公开说明 | **一般**：Agent Mode + MCP 支持；错误恢复路径不透明，无回滚机制 | **强**：原生 MCP + Hooks + Checkpointing 回滚；工具链集成最完整 |
| Plan mode / Spec-driven | **一般**：Workspace 提供 plan-first 流程，但仅限 Cloud Agent，IDE 内 Agent Mode 无同等规划阶段 | **弱**：Agent Mode 为自主执行，无显式规划产出阶段，无 Spec-driven 概念 | **强**（核心卖点）：EARS 记法需求 → 设计文档 → 任务列表三段式，可逐阶段审阅修改 |
| Subagent / 多 agent | **一般**：支持委派给外部 agent（Claude、Codex）；自身无原生子任务并行编排 | **弱**：无多 agent 协作能力公开说明；单 agent 自主执行 | **一般**：Collaborative Agents（GA 新功能），细节机制未充分公开 |
| Memory | **一般**：Memory（Public Preview）+ Spaces + Custom Instructions，跨会话记忆相对完善但仍 Preview 阶段 | **弱**：无状态服务，无跨会话记忆，无规则文件机制 | **一般**：Steering Files 提供规则层持久化；无自动事实累积 |
| 权限模型 | **一般**：管理员可控模型 / MCP 权限；操作确认有基本 gate，auto-approve 规则未公开 | **一般**：VPC-SC 网络层最强（三款中唯一）；操作层细粒度 gate 未公开说明 | **强**：diff 审批工作流 + Checkpointing 回滚 + MCP 访问控制；操作 gate 最明确 |

#### 3.1.4 ★ Claude Code 基准对照

Claude Code 代表了当前商用代码助手 harness 工程的顶尖水位。以下 5 项能力作为参照基准，并标注三款候选的对应现状：

**1. Plan Mode**

Claude Code 通过 `/plan` 命令进入显式规划模式，在执行任何代码修改前产出可读可修改的行动方案（来源：Claude Code 官方文档）。这是"人类在环"的关键检查点，防止 agent 自作主张直接修改生产代码。对应候选：Kiro 的 spec-driven 三段式流程是功能上最接近的，且更结构化（含 EARS 记法文档）——在"规划深度"这一子维度上 Kiro 甚至部分超越 Claude Code Plan Mode；Copilot Workspace 的 plan-first 是部分对应，但仅限 Cloud Agent 路径；Gemini 暂未有明确的 Plan Mode 或规划阶段对应能力。

**2. Subagents**

Claude Code 通过内置 `Agent` 工具派发独立上下文的子任务：每个 Subagent 拥有独立上下文窗口、定制化系统提示、特定工具访问权限和独立权限集；Lead agent 可协调多 Subagent 并行工作，Agent Teams 支持多 session 并行执行（来源：Claude Code Subagents 文档）。这是长链路、跨模块任务分解的核心机制。对应候选：Kiro 的 Collaborative Agents 是最近似的产品声明，但细节机制未完全公开；Copilot 通过 GitHub Marketplace 委派外部 agent（Claude、Codex）是"借道外部系统"的替代路径，而非原生 Subagent 编排；Gemini 无对应能力公开说明。

**3. Skills**

Claude Code 的 Skills 是可复用的工作流模板，通过斜杠命令触发（如 `/brainstorm`、`/writing-plans`、`/tdd`、`/debug`），支持项目级和用户级共享（来源：Claude Code Skills 文档）。Skills 将常见工程工作流标准化为可调用的 agent 流程，是 Claude Code 差异化能力中三款候选均无对应机制的一项。对应候选：三款候选均无原生 Skills 机制。Copilot 的 Agent Skills（专用指令文件夹）是静态提示文件，而非可执行工作流模板；Kiro 的 Steering Files 是规则配置，不是工作流触发器；Gemini 无相关机制。

**4. TodoWrite + 进度跟踪**

Claude Code 内置 `TodoWrite` 工具，在长任务执行过程中动态维护 to-do 列表并跟踪进度，使用户可随时审查当前任务状态而不丢失上下文（来源：Claude Code 官方文档）。对应候选：Kiro 的 task list（从 Spec 生成的任务分解列表）是功能上最近似的对应，区别在于 Kiro 的 task list 是 spec 流程产出的静态文档，而 Claude Code 的 TodoWrite 是 agent 运行时的动态状态；Copilot 通过 Spaces / Memory 可部分实现跨会话项目状态保留，但没有运行时动态 to-do 机制；Gemini 无对应能力。

**5. Permission Tiers**

Claude Code 提供四档权限模式：`bypassPermissions`（完全自主，适合受信任的 CI 环境）、`acceptEdits`（自动接受文件修改但需确认命令执行）、`plan`（仅规划不执行）、`default`（每次敏感操作均需用户确认），可按场景和信任级别精确配置（来源：Claude Code 官方文档）。对应候选：三款候选的权限模型相对粗粒度——Kiro 有 diff 审批工作流（近似 acceptEdits + 确认命令的组合）和 MCP 权限控制；Copilot 有管理员层面的模型/功能开关；Gemini 有 VPC-SC 网络隔离（基础设施层强项）+ 基本操作确认，但均无法对应 Claude Code 四档可配置权限粒度。

**总结：Claude Code 的 harness 能力代表当前顶尖水位。** 三款候选与之相比：

- **Kiro** 在 Spec-driven 一项最贴近甚至部分超越（Plan Mode 深度上），harness 工程整体是三款候选中最成熟的；
- **Copilot** 在 Subagent 路径上有"借道外部 agent"通道，凭借 GitHub Marketplace 生态可间接扩展能力上限，但原生 harness 能力距 Claude Code 仍有差距；
- **Gemini** 在 harness 工程上是三款候选中相对最落后的——1M 上下文是模型层的竞争力，但在规划机制、跨会话记忆、Subagent 编排等 harness 核心能力上均相对缺失。

#### 3.1.5 对部门选型的影响

Harness 能力差距的影响因部门工作模式而异：**越是长期、大型、结构化的工程任务，harness 差距对最终产出质量的放大效应越大**；越是一次性、短平快的辅助场景，harness 差距的影响越小。

**受影响最大的部门：**

- **研发（大型项目长期维护、跨模块重构）**：Harness 是首要决策点。重构一个跨 20 个文件的模块，plan/spec 机制决定了工程师能否在执行前审阅完整意图；Memory 机制决定了是否需要每次重新向工具"介绍项目背景"；Subagent 机制决定了并行推进多个子模块的可行性。Kiro 的 spec-driven 流程对长生命周期项目（需求变更频繁、代码债积累）可能特别适配——EARS 记法需求文档本身就是对齐开发意图的工程工件，而不仅仅是工具的输入。

- **量化研究（因子开发 / 回测框架建设）**：因子库和回测框架是结构化工程代码，spec-driven 流程的价值高——开发者可以先产出 EARS 需求文档再执行，确保因子逻辑被正确理解。同时 Memory / Subagent 机制帮助维护"研究-实验-部署"的长链路上下文，减少工具"失忆"导致的重复沟通成本。注意：策略代码属于核心资产，数据出境红线（见 §7.3）在此更为严格，harness 能力的选择须在合规约束下进行。

- **算法（AI）团队（Notebook 迭代 / 训练脚本开发）**：此场景的任务通常较短平快，模型长上下文理解能力（处理大型数据集 schema、模型配置文件）比 harness 复杂度更重要。Gemini Code Assist 的 1M token 窗口在此场景有独特价值，harness 不足的短板相对可接受。

**基本不受影响的部门：**

- **业务通用 / 运营 / PWM 数据分析 / 产品经理 PoC**：一次性的快速验证场景（写个数据分析脚本、生成原型页面、总结文档），Agent Mode 或 Chat 模式足够，简洁的 harness 反而降低认知负担。这类场景下三款工具的体验差距主要来自模型能力而非 harness 设计，底层模型编码能力（见 §3.3）是更重要的选型维度。

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

#### 3.4.1 维度说明

代码理解与上下文深度是大型项目选型时最易被低估的决策性维度。金融公司研发部门的大型项目动辄 50 万行以上，模型上下文窗口装不下整个代码库；关键在于工具的 harness 如何通过索引、检索或探索式扫描，将相关代码片段注入模型的有效视野。厂商声称的"1M token 上下文"描述的是模型上限，而非用户在实际工程任务中能持续稳定使用的可用率——这两者之间的差距往往是选型的最大认知陷阱。本节聚焦 6 个子能力点：**单仓索引规模上限**、**增量索引时延**、**跨仓/跨服务上下文聚合**、**代码图感知**、**长上下文实际可用率**、**私有代码库索引部署形态**，对三款候选工具做并列说明，并以 Claude Code 的"探索式理解"路线作为对照基准。

#### 3.4.2 三款现状对照

##### Copilot Enterprise

**1. 单仓索引规模上限**（**强**）

GitHub 代码搜索是 Copilot Enterprise 最大的基础设施优势。Enterprise 计划支持对整个组织 codebase 进行索引，GitHub 平台本身有为数十亿行开源代码建立全文索引的成熟基础设施。具体的单仓 token 上限或文件数上限未在公开材料中明确说明，但依托 GitHub 托管的超大规模仓库均可被 Copilot 的代码搜索后端感知，这是三款候选中索引基础设施规模最成熟的。

**2. 增量索引时延**（**一般**）

代码推送到 GitHub 后，Copilot Chat 和 Agent Mode 能感知变更的具体延迟未在公开材料中明确说明。从 GitHub 代码搜索的公开记录来看，索引更新通常在分钟级别，但对于 Copilot 上下文感知的生效时间，尤其是 Copilot Memory（Public Preview）自动推断新信息的节奏，暂无公开数据可参考。

**3. 跨仓/跨服务上下文聚合**（**强**）

Copilot Spaces 是其核心的跨仓上下文聚合机制：开发者可将多个代码库、文档、Issues 组织为一个共享知识库空间，为 AI 任务提供跨仓范围的基础上下文。Enterprise 层可将多仓库纳入同一 Space，在微服务架构或 mono-repo 切分场景下聚合上下文，是三款候选中跨仓聚合能力最明确可用的。具体 Space 内支持的仓库数量上限未在公开材料中明确说明。

**4. 代码图感知**（**一般**）

GitHub 代码搜索提供符号级检索（class / function / method 名搜索），Copilot 可借助这一能力在 Chat 中定位相关定义与引用；但 Copilot 是否内置显式的 call graph（调用图）、依赖图或类型图谱分析能力未在公开材料中明确说明。与专用静态分析工具相比，Copilot 的代码图能力更偏向"搜索式感知"而非"结构化图分析"。

**5. 长上下文实际可用率**（**一般**）

Copilot 在不同场景使用不同模型：autocomplete 使用默认模型（GPT-4.1 / GPT-5 mini），用户切换至 Claude Opus 或 Gemini 系列时可获得更大的上下文窗口。但无论底层模型窗口多大，Copilot 实际注入上下文的裁剪策略（哪些文件、多少 token 被送入模型）未在公开材料中明确说明。"声称上限"等于"用户在 Agent 任务中可稳定使用的上下文量"这一假设，在 Copilot 中同样不成立。未在公开材料中找到针对 Copilot 的 needle-in-a-haystack 实际可用率评测数据。

**6. 私有代码库索引部署形态**（**云端**，**强**）

索引完全由 GitHub 云端基础设施管理，无本地索引选项。Enterprise 计划的代码索引存储在 GitHub 侧；代码驻留区遵循 GitHub Enterprise Cloud 的地理数据驻留（Geographic Data Residency）政策，欧美区域可选，中国大陆无节点。金融公司需接受代码上传至 GitHub/Azure 云端这一前提。

**综合评级：强**（GitHub 代码搜索基础设施成熟度是三款候选中最高的；跨仓聚合（Spaces）能力明确可用；但索引必须托管在 GitHub 云端，有数据出境约束）

---

##### Gemini Code Assist (Standard / Enterprise)

**1. 单仓索引规模上限**（**一般** / **强**，按计划层次）

Standard 层不提供私有代码库索引——上下文来源仅限于用户在当次会话中传入的代码，以及模型训练时学习的公开代码模式；Enterprise 层支持接入 GitHub Enterprise Cloud/Server、GitLab、Bitbucket 私有代码库作为定制化上下文来源，但具体单仓索引的 token/文件数上限未在公开材料中明确说明。**Standard 选型时需特别注意**：无私有代码库索引意味着"长期项目上下文感知"能力在 Standard 层基本为零。

**2. 增量索引时延**（**未在公开材料中明确说明**）

Enterprise 私有代码库索引的更新节奏未在公开材料中明确说明。由于 Gemini Code Assist 是无状态服务（不存储 prompt/response），索引更新的触发机制（是基于 webhook、定时拉取还是手动触发）以及生效延迟均未公开，是三款候选中增量索引信息最不透明的。

**3. 跨仓/跨服务上下文聚合**（**弱** / **一般**，按计划层次）

Standard 层无跨仓聚合能力；Enterprise 层通过私有代码库接入（GitHub/GitLab/Bitbucket）间接支持多仓聚合，但聚合是在索引层面发生的，并非类似 Copilot Spaces 的运行时动态聚合机制。具体能否在一次 Agent 任务中同时引用多个仓库的代码、引用边界如何定义，未在公开材料中明确说明。

**4. 代码图感知**（**弱**）

未在公开材料中找到 Gemini Code Assist 内置 call graph、依赖图或类型图谱分析的相关描述。Agent Mode 的 1M token 上下文可以一次性注入大量代码文件，但这是靠"大量文件暴力注入"而非"结构化代码图感知"来弥补语义理解——代码图感知能力未在公开材料中明确说明。

**5. 长上下文实际可用率**（**一般**）

Gemini Code Assist Agent Mode 基于 Gemini 2.5 Pro，声称支持最高 1M token 上下文窗口。然而，声称的 1M ≠ 实际可用率：用户需要主动将代码文件传入会话，模型才能"看到"这些上下文；由于 Gemini Code Assist 是无状态服务，不自动维护跨会话的代码索引，每次任务都需要重新构造上下文，1M 窗口的实际利用率高度依赖用户的手动操作。未在公开材料中找到针对 Gemini Code Assist 的 needle-in-a-haystack 实际可用率独立评测。

**6. 私有代码库索引部署形态**（**云端**，**Standard 无、Enterprise 有**）

Standard 层无私有代码库索引；Enterprise 层索引由 Google Cloud 托管，数据存储在 Google 侧（"Enterprise 私有代码库会被安全存储，仅用于提供定制建议功能"）。处理发生在距离请求最近的数据中心，不保证区域性。VPC Service Controls 可增强网络隔离，但索引数据本身需上传 Google Cloud，同样有数据出境约束。

**综合评级：一般**（1M token 是模型层优势，但 Standard 无私有代码库索引、无状态服务导致跨会话上下文归零；Enterprise 有索引能力，但信息透明度低，数据出境约束同样存在）

---

##### AWS Kiro

**1. 单仓索引规模上限**（**一般**）

Kiro 官方宣称提供"高级上下文管理"能力，可感知项目结构；具体的单仓索引 token 上限或文件数上限未在公开材料中明确说明。Kiro 是独立 IDE，对当前打开工作区的代码可以直接读取；跨仓库的索引规模是否能与 Copilot Enterprise（基于 GitHub 代码搜索）媲美，暂无公开数据可参考，标"未在公开材料中明确说明"。

**2. 增量索引时延**（**未在公开材料中明确说明**）

由于 Kiro 是独立 IDE 且产品 GA 仅约 5 个月，索引更新机制的细节（是否有后台增量索引、文件保存后多久生效）未在公开材料中明确说明。Hooks 机制（文件保存事件触发 AI 任务）提供了一种轻量级的"变更感知"路径，但这是针对具体文件变更的 AI 任务触发，而非代码库级别的全量索引更新。

**3. 跨仓/跨服务上下文聚合**（**弱**）

未在公开材料中找到 Kiro 对多仓库或跨服务上下文聚合的明确说明。Kiro 作为独立 IDE，主要面向单工作区开发场景；在微服务或多仓库架构下，跨仓聚合的可行性和机制限制未在公开材料中明确说明，是三款候选中跨仓能力最不清晰的。

**4. 代码图感知**（**弱**）

未在公开材料中找到 Kiro 内置 call graph、类型图谱或依赖分析的相关描述。Spec-driven 流程通过结构化需求文档（EARS 记法）和设计文档来弥补部分上下文理解短板，但这是工程流程层的补偿，而非代码图感知能力本身。Steering Files 提供项目级规则，可以间接描述代码结构，但同样不是语义代码图。

**5. 长上下文实际可用率**（**一般**）

Kiro 底层使用 Claude Sonnet 4.5 和 Claude Haiku 4.5；Claude Sonnet 4.5 的上下文窗口约为 200K token，远小于 Gemini 2.5 Pro 的 1M token，在超大仓库场景下上限更低。Auto 模式动态选择 Sonnet 或 Haiku，在轻量任务下会回退至 Haiku，上下文窗口进一步缩减。实际任务中，Kiro 通过"高级上下文管理"机制裁剪注入哪些代码，具体策略未在公开材料中明确说明。

**6. 私有代码库索引部署形态**（**云端**，**信息有限**）

索引由 AWS 云端基础设施管理，Kiro 遵循"与 AWS 云基础设施相同的安全、治理和加密标准"，但具体的私有代码库索引部署架构（是否支持 VPC 内部署、数据是否落地特定区域）未在公开材料中明确说明。鉴于 Kiro 的数据训练承诺本身也未在公开材料中明确说明，私有代码库索引的数据处理细节是三款候选中最不透明的。

**综合评级：弱**（跨仓聚合、代码图感知均无公开说明；索引规模上限信息不透明；上下文窗口最小；产品较新，信息缺口最多）

#### 3.4.3 子能力对照表

| 子能力 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| 单仓索引规模上限 | **强**：GitHub 代码搜索成熟基础设施，支持组织级全量索引；具体上限未公开 | **一般**（Enterprise）/ **弱**（Standard）：Enterprise 接入私有代码库索引，Standard 无索引；具体规模上限未公开 | **一般**：宣称"高级上下文管理"，具体上限未在公开材料中明确说明 |
| 增量索引时延 | **一般**：分钟级更新（推测，基于 GitHub 代码搜索历史记录）；Memory 更新节奏未公开 | **弱**：无状态服务，索引更新机制完全未公开说明 | **弱**：GA 约 5 个月，索引更新机制未公开；Hooks 仅覆盖文件变更触发，非全量索引 |
| 跨仓/跨服务上下文聚合 | **强**：Copilot Spaces 支持多仓 + 文档 + Issues 的显式聚合空间，Enterprise 级可跨组织多仓 | **弱**（Standard）/ **一般**（Enterprise）：Enterprise 多仓库索引接入，但运行时聚合机制未公开 | **弱**：无公开的多仓库聚合说明，主要面向单工作区场景 |
| 代码图感知 | **一般**：GitHub 符号级检索提供定义/引用感知；显式 call graph / 依赖图分析未公开说明 | **弱**：无代码图感知公开说明；靠 1M token 暴力注入代码文件作为补偿 | **弱**：无代码图感知公开说明；Spec 文档间接补偿，但非代码图能力本身 |
| 长上下文实际可用率 | **一般**：上下文裁剪策略未公开；可切换至高上下文窗口模型（Claude Opus / Gemini 2.5 Pro），但实际注入量不透明 | **一般**：1M token 声称值高，但无状态导致每次任务需手动重建上下文；实际可用率高度依赖用户操作 | **弱**：底层 Claude Sonnet 4.5（约 200K token）+ Haiku 组合，上限最低；实际裁剪策略未公开 |
| 私有代码库索引部署形态 | **云端**：GitHub/Azure 托管，地理驻留可选（欧美区域），无本地索引选项 | **云端**：Google Cloud 托管；Standard 无索引，Enterprise CMEK 支持，不保证区域性 | **云端**：AWS 托管，数据处理架构未充分公开；无本地索引选项 |

#### 3.4.4 ★ Claude Code 基准对照

Claude Code 与三款候选工具在代码理解上走了完全不同的路线：**探索式理解**，而非索引式理解。

**核心差异：探索式 vs 索引式**

三款候选工具（Copilot/Gemini/Kiro）均以"先建索引，再按需检索"为基础——代码库先被上传至厂商云端进行结构化索引，AI 任务时从索引中检索相关片段注入上下文。Claude Code 不预先建立索引，而是在每次任务中通过工具调用（`grep`、`glob`、文件读取、bash 命令）主动探索文件树，按需定位相关代码，再将结果注入模型上下文。

这种设计带来了两方面的核心优势：

**优势：**

- **冷启动即可用**：无需等待索引建立，新仓库克隆后立即可开始 Claude Code 辅助，节省冷启动时间，尤其适合频繁切换仓库的场景
- **随变更自动更新**：文件修改后，下次探索时即读取最新内容，无增量索引延迟，也无"索引过期"导致的幻觉风险
- **私有代码不上传第三方索引服务**：对金融公司而言，这是核心资产保密层面的显著优势——策略代码、因子库等高度敏感的资产，在 Claude Code 模式下仅在单次推理时过模型，不在任何厂商云端建立持久化索引；代码不被"建档存档"，降低数据被泄露或司法调取的暴露面
- **探索过程透明可中断**：用户可以看到 Claude Code 在探索哪些文件、执行哪些命令，任何一步都可以审阅和干预，与三款候选工具的"黑盒索引检索"相比，过程可视性更高

**劣势：**

- **单次查询 token 消耗高**：探索式理解需要实际读取文件内容，每次任务的工具调用次数和 token 消耗显著高于从索引中直接检索；在 API 计费模式下，成本更敏感
- **超大仓库效率有上限**：对于 50 万行以上的超大仓库，探索式扫描覆盖全量代码的效率存在上限；模型需要通过多轮工具调用逐步收敛到相关代码区域，对于跨多个模块的大规模重构，探索链路可能较长
- **依赖模型工具调用稳健性**：探索质量取决于 Claude 在工具调用序列中的推理准确性——选错文件路径、glob 模式写错、过早停止探索都可能导致上下文不完整

**对照三款候选的索引式路线：**

- **Copilot** 用 GitHub 代码搜索（全球最成熟的代码索引基础设施之一）+ Spaces 实现跨仓聚合，索引质量高、检索速度快，但索引驻留在 GitHub/Azure 云端
- **Gemini Enterprise** 接 GitHub/GitLab/Bitbucket 索引，Standard 层无此能力；索引驻留在 Google Cloud，数据处理区域不保证
- **Kiro** 索引能力偏轻量，跨仓聚合能力最不清晰，且产品较新，索引机制细节未充分公开

**金融公司适配讨论：**

金融公司面临"上下文需求"与"资产保密合规"之间的根本性张力。量化策略代码、因子库是核心竞争资产，一旦进入厂商云端索引，即使有"不用于训练"承诺，也无法消除推理过程中的数据暴露风险（详见 § 7.3）。Claude Code 的"探索式、无持久索引"路径，天然与这一合规要求对齐：

- **策略代码/因子库**：建议规避走任何需要上传代码建立持久索引的路径（与 § 7.3 红线一致）；Claude Code 的探索式路线在此场景具有合规层面的结构性优势
- **三款候选的折中方案**：若业务必须使用候选工具的索引功能，可通过 Enterprise/Cloud KMS（如 Gemini CMEK）+ 严格的索引范围 Policy（仅允许特定仓库/目录进入索引，排除策略代码目录）来降低风险，但需要额外的治理成本和技术验证
- **企业治理层面的差异**：Claude Code 的无索引路线在数据保护上占优，但在企业治理（集中管控、Policy 配置、审计日志）层面相对较弱——Claude Code 更适合有经验的开发者自主使用，对企业级集中管控的支持度不如三款候选工具

#### 3.4.5 对部门选型的影响

**上下文深度影响最大的部门：**

**研发**（大型项目长期维护、跨模块重构）：上下文深度是首要决策点。大型金融系统代码库往往 50 万行以上，跨模块重构时能否有效感知调用链和依赖图，直接决定 AI 辅助的质量上限。Copilot Enterprise 的 GitHub 代码搜索基础设施在索引成熟度上最具优势，对于已将代码托管在 GitHub 的团队而言，切换成本低且索引能力立即可用；若涉及多服务微服务架构，Spaces 的跨仓聚合是目前三款候选中最明确可用的能力。

**算法（AI）团队**（大型训练脚本、论文级长 prompt 分析）：Gemini Code Assist 的 1M token 上下文在超长输入场景有明显优势——处理包含完整模型配置文件、大型数据集 schema 或跨多个 Notebook 的上下文时，1M 窗口提供了其他两款不具备的缓冲空间。但需注意：Standard 层无私有代码库索引，Enterprise 层才有（$45/user/月），且跨会话上下文必须每次手动重建。

**量化研究**（因子库代码 + 回测框架长期维护）：此场景存在典型的"合规 vs 上下文需求"张力——因子库和回测框架是结构化工程代码，索引式上下文理解价值高；但策略代码、Alpha 因子属于核心资产，不应进入任何厂商云端索引（§ 7.3 红线）。实践上建议：非核心代码（回测框架基础设施、工具库）可使用候选工具并配置严格索引范围 Policy；策略代码和 Alpha 因子模块优先使用 Claude Code 的探索式路线，或在本地/私有云部署环境下使用支持本地推理的工具。两类代码分开管理，是缓解这一张力的折中路径。

**基本不受上下文深度影响的部门：**

业务通用开发/运营/PWM 数据分析/产品经理 PoC——这类场景的代码量小、生命周期短（一次性分析脚本、原型页面），不存在大型代码库上下文感知需求；AI 工具的使用体验主要由模型编码能力（§ 3.3）和 Chat 交互流畅度决定，索引规模和跨仓聚合能力对这类用户基本透明，不构成选型的决定性因素。

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

#### 3.6.1 维度说明

扩展性在企业选型中往往是决策性维度而非附加项。金融公司内部系统密度高——研发团队使用 Jira / Confluence 追踪需求与文档，使用私有 GitLab / Bitbucket 托管敏感代码仓库，CI 流水线与合规检查环节需要与工具集成；EST / PWM / 量化部门日常依赖 Bloomberg Terminal、Wind 数据终端、风控系统和内部 RAG 知识库。代码助手能否通过标准协议或扩展机制接入这些系统，直接决定了工具在业务一线的实用性上限。纯靠 Chat 和补全解决单文件问题的工具，无法替代"能感知内部系统上下文"的 agentic 工具。本节聚焦 5 个子能力点：**IDE / CLI 覆盖度**、**MCP 协议支持现状**、**Plugin / Extension / Skills 类机制**、**与公司既有系统集成**（Jira / Confluence / 私有 Git / 内部 RAG / Bloomberg / Wind）、**API / Webhook / 自定义集成**。

#### 3.6.2 三款现状对照

##### Copilot Enterprise

**1. IDE / CLI 覆盖度**（**强**）

Copilot Enterprise 的 IDE 覆盖是三款候选工具中最广的：VS Code、Visual Studio、JetBrains 全系（IntelliJ IDEA、PyCharm、GoLand、WebStorm、CLion、Rider）、Vim、Neovim、Xcode、Eclipse、Azure Data Studio、SQL Server Management Studio、Zed、Raycast。CLI 方面提供 `gh copilot` 命令行扩展，支持终端工作流；同时集成 GitHub Mobile App（iOS / Android）和 Windows Terminal Canary。多语言 IDE 的广覆盖意味着无需要求团队统一切换 IDE——Java 研发用 IntelliJ、数据团队用 VS Code、部分老系统维护者用 Vim，均可接入。

**2. MCP 支持现状**（**一般**）

Copilot 支持配置 MCP Server，可赋予 Agent Mode 访问外部工具的能力（来源：GitHub Copilot 官方文档 features 页面，2026-04-29）。从架构上看，Copilot 通过 MCP Server 配置接入第三方工具，属于"MCP 消费端"，即可以读取外部 MCP Server 的工具；但 Copilot 自身并非以 MCP 标准化协议为核心构建的开放生态——其对外系统集成的主干路径是 GitHub Marketplace Extension 机制，MCP 支持是后续补充的能力叠加层。原生 MCP server 客户端的配置粒度、可用工具范围和权限管理细节未在公开材料中明确说明。

**3. Plugin / Extension / Skills 类机制**（**强**）

Copilot Extensions 是最成熟的企业级扩展发布体系：扩展可通过 GitHub Marketplace 发布和发现，支持私有扩展（不公开发布）供企业内部分发；Copilot Spaces 提供"知识库型扩展"机制，将文档、代码库、Issues 组织为 AI 任务的持久上下文空间，可按部门或项目共享；Agent Skills（专用指令文件夹）允许为特定场景创建定制 Copilot 版本。GitHub Marketplace 当前已有数十个第三方 Copilot Extension，覆盖 Jira、Confluence、Sentry、Datadog 等主流开发工具。

**4. 与公司既有系统集成**（**强**）

GitHub 平台原生与 Jira（通过 GitHub + Jira 集成）、Confluence 深度绑定；Copilot Extension 体系通过 Marketplace 支持 Jira、Confluence 扩展。私有 Git 方面，若公司代码已托管在 GitHub Enterprise Cloud，集成是零摩擦的；若使用 GitLab 或 Bitbucket，则需通过 GitHub Actions + Mirror 机制或放弃代码索引能力。内部 RAG / 知识库可通过自建 Copilot Extension 对接，但走 Marketplace 机制存在审核与发布流程成本。Bloomberg / Wind 等金融终端目前无公开的官方 Copilot Extension，需自建 MCP Server 或 Extension；是否可行取决于数据许可协议，商业数据通常有二次集成限制。

**5. API / Webhook / 自定义集成**（**一般**）

Copilot 通过 GitHub Actions 实现 CI/CD 集成，支持在 PR、Push、Issue 等 GitHub 原生事件上触发 Copilot Workspace 任务；GitHub API 和 Webhooks 成熟度高，与现有研发工具链集成路径清晰。但 Copilot 自身没有对外暴露"调用 Copilot 能力"的标准 REST API——外部系统无法像调用 LLM API 那样直接调度 Copilot。深度自定义集成必须借助 GitHub Marketplace Extension SDK 或 GitHub Actions，灵活度相对有限。

**综合评级：强**（IDE 覆盖最广、Extension 体系最成熟，GitHub 平台原生集成是核心优势；对非 GitHub 生态的外部系统接入，灵活度略低于 MCP 原生架构）

---

##### Gemini Code Assist (Standard / Enterprise)

**1. IDE / CLI 覆盖度**（**一般**）

Gemini Code Assist 的 IDE 支持：VS Code、JetBrains 全系（IntelliJ IDEA、PyCharm、GoLand、WebStorm、CLion、Rider）、Android Studio、Cloud Shell Editor、Cloud Workstations（来源：Gemini Code Assist 官方文档）。相比 Copilot，缺少 Vim / Neovim、Visual Studio、Xcode、SQL Server Management Studio 等覆盖。CLI 方面无专属 Code Assist CLI（Gemini CLI 存在但并非 Code Assist 的独立产品），终端工作流支持弱于 Copilot 和 Kiro。Android Studio 的覆盖对移动研发团队有价值，但金融行业适用场景有限。

**2. MCP 支持现状**（**一般**）

Agent Mode 支持 MCP Server 连接（来源：Gemini Code Assist 官方文档）。Standard 层的 MCP 支持范围和 Enterprise 层的差异未在公开材料中明确说明；Enterprise 层通过 Google Cloud 扩展集成（Apigee、Application Integration 等），部分集成走的是 GCP 原生集成路径而非通用 MCP 标准协议。与 Kiro 的 MCP 原生架构相比，Gemini 的 MCP 支持更像是对 Agent Mode 的工具扩展能力，协议标准化程度和生态丰富度均未在公开材料中明确说明。

**3. Plugin / Extension / Skills 类机制**（**弱**）

Gemini Code Assist 没有类似 Copilot Extensions 的公开发布 / 发现机制，也没有企业内部扩展分发体系。标准 VS Code 和 JetBrains 扩展生态可正常安装使用，但这是 IDE 原生扩展，而非 Gemini Code Assist 专有扩展机制。Enterprise 层独有 Google Cloud 深度集成（Apigee 接口管理、Application Integration 工作流），但这些是 GCP 生态内部的预置集成，而非面向第三方的开放扩展框架。未在公开材料中找到 Gemini Code Assist 的第三方扩展 Marketplace 或 SDK 文档。

**4. 与公司既有系统集成**（**弱**）

Gemini Code Assist 与 GCP 生态绑定最深，适合已重度使用 GCP 服务的研发团队：BigQuery 可直接通过 Code Assist 生成 SQL，Apigee API 管理和 Application Integration 均有 Enterprise 层集成支持。但对于金融公司常用的 Jira / Confluence（Atlassian 生态）、私有 GitLab / Bitbucket、Bloomberg / Wind 数据终端、内部 RAG 知识库，Gemini 没有官方预置集成；接入路径需自建 MCP Server（能力范围待实测），缺乏成熟的第三方扩展覆盖。研发部门若非 GCP 用户，实际集成价值较低。

**5. API / Webhook / 自定义集成**（**一般**）

Gemini Code Assist 通过 Google Cloud 基础设施提供 API 访问，Enterprise 支持 VPC Service Controls 网络隔离，适合有严格网络管控要求的团队。但 Code Assist 自身的自定义集成能力（Webhook、事件触发、外部系统回调）未在公开材料中明确说明；GCP Eventarc 和 Pub/Sub 可作为周边集成层，但需要额外的 GCP 服务配置，增加了集成复杂度和运维成本。对于非 GCP 用户，自定义集成的门槛较高。

**综合评级：一般**（GCP 生态内集成能力强，对非 GCP 用户的外部系统集成支持薄弱；MCP 支持未充分公开，第三方扩展机制缺失）

---

##### AWS Kiro

**1. IDE / CLI 覆盖度**（**弱**）

Kiro 是独立 IDE（基于 Code OSS 构建），非插件形态，**无法安装在 JetBrains 系列、Visual Studio、Xcode 等现有 IDE 中**。开发者必须切换到 Kiro 作为主力 IDE，这是三款候选工具中入驻门槛最高的产品形态——对于已在 IntelliJ / PyCharm 深度使用 IDE 的 Java / Python 研发团队，切换成本不容忽视。VS Code 扩展和主题可在 Kiro 中安装，兼容既有 VS Code 插件生态；Kiro CLI 提供终端工作流支持，底层使用 Claude Sonnet 4.5 / Haiku 4.5。支持 macOS、Linux、Windows 三平台。

**2. MCP 支持现状**（**强**，核心卖点）

MCP 原生支持是 Kiro 最具差异化的扩展能力，也是三款候选工具中与 MCP 标准化协议对齐最深的。Kiro 将 MCP Server 作为对接外部系统的标准通道：开发者可在配置中声明 MCP Server（本地进程或远程服务），Kiro 在 Agent 任务中通过 MCP 协议调用外部工具，覆盖 Filesystem、数据库、API、文档系统等（来源：kiro.dev 官方文档）。Enterprise 计划管理员可控制 MCP 访问权限，在 MCP 工具粒度实施安全管控，而非只能在功能级别开关。这意味着公司可以为 Bloomberg / Wind / 内部 RAG 构建自定义 MCP Server，Kiro 即可原生调用，无需等待厂商发布官方集成。

**3. Plugin / Extension / Skills 类机制**（**一般**）

Kiro 原生 extension / plugin 机制不存在（无类似 Copilot Extensions 的发布 / 发现体系）；Kiro 的可扩展性主要通过以下两条路径实现：**① MCP Server**（工具与数据接入的主要通道，见上）；**② Steering Files**（项目级 AI 行为规则配置，类似 CLAUDE.md，控制 Agent 工作流偏好与编码规范，可 Git 版本化管理）。Hooks 机制（文件保存等事件触发后台 AI 任务）提供了轻量级的工作流自动化扩展点，但并非面向用户共享和分发的插件框架。VS Code 扩展在 Kiro 中可安装运行，为 IDE 功能层提供补充，但这是 VS Code 生态复用，而非 Kiro 原生扩展机制。

**4. 与公司既有系统集成**（**一般**）

得益于 MCP 原生架构，Kiro 对接外部系统的路径最灵活：Jira / Confluence、私有 Git（GitLab / Bitbucket）、内部 RAG 知识库（如公司自建的 ragent 系统）均可通过自建 MCP Server 接入，无需等待厂商官方集成。Bloomberg / Wind 的接入在 MCP 技术上可行，但需遵守数据许可证协议，不可随意二次集成（详见 § 7.3）。AWS 生态（S3、DynamoDB、Lambda 等）可通过 AWS 官方 MCP Server 接入，对已使用 AWS 的团队有现成方案可用。限制在于：Kiro GA 约 5 个月，社区维护的第三方 MCP Server 库尚不如成熟生态丰富；复杂集成需要工程投入自建 MCP Server。

**5. API / Webhook / 自定义集成**（**一般**）

Kiro Hooks 提供事件驱动的自动化能力：文件保存、代码变更等事件可触发 AI 任务（如自动运行测试、更新文档），类似 CI 层的 pre-commit hook 概念，但在 IDE 内部执行。Kiro 自身对外暴露的 API / Webhook 能力未在公开材料中明确说明，外部系统主动调度 Kiro 的方式不如 GitHub Actions 触发 Copilot Workspace 那样有清晰的文档路径。企业自定义集成主要依赖 MCP Server 模式，而非事件推送 / Webhook 拉取模式。

**综合评级：一般**（MCP 原生是最大优势，对外部系统接入最灵活；IDE 覆盖受限于独立 IDE 形态，切换成本高；第三方扩展生态尚不成熟）

#### 3.6.3 子能力对照表（5 行 × 3 列）

| 子能力 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| IDE / CLI 覆盖度 | **强**：覆盖最广（VSCode、JetBrains 全系、Visual Studio、Vim / Neovim、Xcode 等 12+ 平台；gh copilot CLI；Mobile） | **一般**：VSCode、JetBrains、Android Studio、Cloud Shell；无 Vim / Neovim / Visual Studio；无专属 CLI | **弱**：独立 IDE 形态，无法安装在 JetBrains 等既有 IDE 中；Kiro CLI 可用；VS Code 扩展兼容 |
| MCP 协议支持 | **一般**：支持配置 MCP Server（Agent Mode 工具扩展），但非 MCP 原生架构；主干路径为 GitHub Marketplace Extension | **一般**：Agent Mode 支持 MCP Server 连接；与 GCP 深度集成走 GCP 原生路径，非通用 MCP 标准 | **强**（核心卖点）：MCP 原生架构，标准协议接入外部工具；Enterprise 管理员可控 MCP 权限粒度 |
| Plugin / Extension / Skills | **强**：GitHub Marketplace Extensions（公开 + 私有）+ Copilot Spaces 知识库 + Agent Skills；第三方扩展生态最成熟 | **弱**：无原生扩展发布体系；依赖 VS Code / JetBrains 标准插件 + GCP 预置集成；无第三方 Marketplace | **一般**：无扩展发布体系；通过 MCP Server + Steering Files + Hooks 实现可扩展性；VS Code 扩展可复用 |
| 与公司既有系统集成 | **强**：Jira / Confluence 有 Marketplace Extension；GitHub 生态深度绑定；Bloomberg / Wind 需自建 Extension | **弱**：GCP 生态内强（BigQuery、Apigee）；Jira / Confluence / 私有 Git / Bloomberg 无官方集成；非 GCP 用户门槛高 | **一般**：MCP 架构最灵活（Jira / 内部 RAG / AWS 服务均可接入）；Bloomberg / Wind 受数据许可证限制；社区 MCP Server 库尚不丰富 |
| API / Webhook / 自定义集成 | **一般**：GitHub Actions + Webhooks 成熟；Copilot 自身无对外调用 API；Extension SDK 可自建 | **一般**：GCP 基础设施 API 成熟；Code Assist 自身自定义集成能力未充分公开；非 GCP 用户门槛高 | **一般**：Hooks 提供事件驱动自动化；对外 API / Webhook 未在公开材料中明确说明；集成主路径为 MCP Server |

#### 3.6.4 ★ Claude Code 基准对照

Claude Code 的扩展性架构代表了当前 agentic coding 工具的开放生态范式，其核心是"三层扩展模型"。以下以 Claude Code 为标杆，对三款候选工具做逐层对照。

**第一层：MCP（Model Context Protocol）**

MCP 是 Anthropic 主导设计并开源的标准化工具调用协议，Claude Code 以 MCP 作为对外集成的核心通道。通过 MCP，Claude Code 可接入任意符合协议的 MCP Server：官方 MCP Registry 提供 Filesystem、Slack、Jira、GitHub、自建数据库等现成 Server；公司可自建 MCP Server 对接私有系统——包括内部 RAG 知识库（如公司自建的 ragent 系统）、Bloomberg / Wind 数据接口（合规审核后）、私有 Git 仓库、风控系统 API 等。MCP 协议标准化意味着 MCP Server 是跨工具复用的：一个为 Claude Code 构建的 ragent MCP Server，理论上在任何支持 MCP 协议的工具中均可直接复用。

对照三款候选：
- **Kiro** 宣称 MCP 原生支持，是三款候选中与 Claude Code MCP 路线最贴近的，且已将 MCP 作为外部系统集成的主标准通道，管理员可在 MCP 工具粒度控制权限；
- **Copilot** 后续补充了 MCP Server 配置能力，但主干扩展路径为 GitHub Marketplace Extension，与 MCP 开放标准的对齐程度不如 Kiro；
- **Gemini** 在 Agent Mode 中支持 MCP Server 连接，但与 GCP 的深度集成优先走 GCP 原生路径，MCP 作为通用标准协议的落实程度未在公开材料中明确说明，是三款候选中 MCP 信息最不透明的。

**第二层：Plugins**

Claude Code 的 Plugins 是打包发布的 skill / agent / command 集合，可在团队或企业内部共享、版本化管理，并通过官方 Plugin Registry 发现和安装。Plugin 将多个 skill 和工具调用封装为可复用的工作流单元，支持企业内部分发和版本锁定，使研发团队无需每次重复配置相同的工作流环境。

对照三款候选：
- **Copilot** 的 GitHub Marketplace Extensions 是功能上最相似的机制——支持公开发布、私有发布、版本管理和企业内部分发，这是三款候选中与 Claude Code Plugins 模式最接近的；
- **Kiro** 通过 MCP Server 间接实现 Plugin 类功能（将工具和流程打包为 MCP Server），但缺少正式的发布 / 发现 / 版本管理体系；Steering Files 提供规则级"配置包"，但不是可执行的工作流单元；
- **Gemini** 无对应的扩展发布体系，第三方扩展能力最弱，是三款候选中在此层落差最大的。

**第三层：Skills**

Claude Code 的 Skills 是触发式工作流模板，通过斜杠命令调用（如 `/brainstorm`、`/writing-plans`、`/executing-plans`、`/tdd`、`/debug`、`/frontend-design`），将常见工程场景的最佳实践封装为可重复调用的 agent 流程。Skills 的关键价值是：开发者不需要每次在 Chat 中重新用自然语言描述工作流，而是调用预设的"模板化最佳实践"，由 Claude Code 按模板驱动 agent 执行——这是 Claude Code 差异化能力中**三款候选均无对应机制的一项**。

对照三款候选：
- **Copilot** 的 Agent Skills（专用指令文件夹）是静态自定义提示文件，可以定制 Copilot 对特定提示词的回应风格，但不是"可执行工作流模板"——无法像 `/tdd` 那样驱动 agent 完成一套标准 TDD 流程的端到端执行；
- **Kiro** 的 Steering Files 是项目级规则配置（类似 CLAUDE.md），决定 AI 的行为偏好，但同样不是触发式工作流模板；Spec-driven 流程是 Kiro 最接近"模板化工作流"的设计，但它是内建固定流程，而非可扩展 / 可自定义的 Skills 框架；
- **Gemini** 无任何类似 Skills 的触发式工作流机制，每次任务均需用户在 Chat 中重新描述意图。

**对接公司内部 RAG / 知识库的可行性**

公司已有自建 RAG 系统（如 ragent 项目），MCP 协议是对接代码助手工具的天然接口。对接思路如下：为 ragent 提供一个 MCP Server 封装（暴露"知识库查询"工具），代码助手在执行 Agent 任务时可通过 MCP 调用该工具，将内部知识库查询结果注入上下文。这一方案在理论上对四款工具均可行，但落地难度因工具 MCP 支持深度而不同：
- **Kiro** 最适合：MCP 原生架构 + 管理员权限管控，ragent MCP Server 构建后可直接声明为受信 MCP 工具，Agent 任务中自动可用；
- **Copilot** 通过 MCP Server 配置也可接入，但 MCP 工具配置路径和权限控制的文档完善程度不如 Kiro，且须适配 Copilot 的 Extension SDK 机制；
- **Gemini** 暂无明确的 ragent 对接路径，MCP 支持细节未充分公开，需实测验证是否可行；
- **Claude Code**（基准）：MCP 是核心集成路径，ragent MCP Server 对接是最自然的选择，且 Claude Code 的 Skills 还可以将"查 ragent + 生成代码"封装为可复用的 `/query-ragent` skill。

**总结对照**

Claude Code 的三层扩展模型代表了当前 agentic coding 工具的开放生态范式。三款候选与 Claude Code 相比，扩展性差距分布如下：
- **Kiro** 在 MCP 一项最贴近 Claude Code，但 Plugins 类发布体系和 Skills 类触发式工作流均不存在，扩展能力重心偏向"通过 MCP 接入外部工具"，而非"内部工作流的模板化复用"；
- **Copilot** 在 Plugins 类机制（GitHub Marketplace Extensions）最成熟，与 MCP 标准的对齐程度不如 Kiro，Skills 类机制处于静态提示层面而非可执行模板层；
- **Gemini** 在三层中均落后——MCP 支持未充分公开、Plugins 类机制缺失、Skills 类机制不存在；扩展性是三款候选中的最大短板，主要适用场景是 GCP 生态内的预置集成。

#### 3.6.5 对部门选型的影响

扩展性对不同部门的影响程度差异明显，核心分界线在于"是否需要代码助手感知外部系统上下文"。

**受扩展性影响最大的部门：**

**研发**（要接 Jira / 内部 CI / 私有 Git / 内部知识库 / 公司 RAG）：扩展性是研发团队的首要决策维度之一。Jira 任务追踪、Confluence 设计文档、内部 CI 流水线状态、私有 GitLab 代码仓库——这些系统的上下文如果能被代码助手感知，可以显著减少研发在切换工具之间搜集信息的摩擦成本。Copilot Marketplace Extensions 当前覆盖最丰富（Jira、Confluence 均有第三方扩展），落地摩擦最低；Kiro 的 MCP 架构在长期灵活性上更优，但需要工程投入自建关键系统的 MCP Server，适合有技术基础设施能力的团队。内部 RAG 知识库（如 ragent）的对接，Kiro 是最可行的落地路径。

**EST / PWM / 量化**（要接 Bloomberg / Wind / 内部数据库 / 风控系统）：MCP 兼容工具在技术上能对接这些系统，但受制于 § 7.3 的数据红线——客户数据、持仓数据、盘中数据不应作为代码助手的上下文传入云端 SaaS 工具。扩展能力在此场景的实际可用范围受合规边界严格约束：Bloomberg / Wind 数据许可证通常禁止二次分发和非授权 API 接入，需法务确认；对于内部数据库的查询生成（如 BigQuery SQL 生成、PostgreSQL schema 理解），Gemini Code Assist 的 GCP 生态集成和 Copilot 的 MCP Server 配置均有一定价值，但均须在合规审核后使用。

**算法（AI）团队**（要接 HuggingFace / 训练集群 / 实验追踪 MLflow / W&B）：MCP 兼容工具对这类科研工作台系统的接入最有价值。MLflow、Weights & Biases（W&B）均属于有 API 的开放平台，构建 MCP Server 的技术可行性高；HuggingFace 模型库的接入也可通过 MCP 封装实现。Kiro 的 MCP 原生架构在此场景是优势；Copilot 通过 MCP Server 配置亦可支持，但文档完善度不如 Kiro。

**受扩展性影响较小的部门：**

业务通用开发 / 运营 / PWM 数据分析 / 产品经理 PoC——这类使用场景以一次性脚本、原型页面、文档生成为主，不要求代码助手感知外部系统，基础 Chat 和补全能力已能满足需求。对这类用户，扩展性能力过剩，选型时其他维度（价格、易用性、模型能力）的权重更高。

## 第 4 章 部门 × 工具适配矩阵

### 4.1 矩阵速览

> 本章是第 1 章（部门画像）和第 3 章（核心能力）的视觉收束。**评级是"该部门场景下的相对适配度"，不代表工具自身的"绝对能力强弱"。**

#### 评级图例

- **★★★ 首选**：该部门场景下首推
- **★★ 备选**：可用，但需特定条件
- **★ 不推荐**：该部门不建议
- **— N/A**：不适用 / 无效组合

#### 8 部门 × 3 工具大矩阵

| 部门 | Copilot Enterprise | Gemini Code Assist | AWS Kiro |
|---|---|---|---|
| 1.1 研发（含 CI/CD） | ★★★ 首选 — IDE 覆盖最广 / 大仓库检索成熟 / Marketplace 生态丰富。**风险**：核心资产（如风控引擎）需 policy 隔离 | ★★ 备选（Enterprise）— GCP 绑定 / 1M 上下文 | ★ 不推荐 — GA 5 个月，长期项目稳定性未验证 |
| 1.2 量化研究 | ★★★ 首选（仅辅助脚本）— 模型选单宽（含 Claude Opus 4.x）/ JetBrains IDE 友好。**红线**：策略代码 / 因子库不进 prompt（§ 7.3） | ★★ 备选（Enterprise）— 长 prompt 推理 / CMEK | ★ 不推荐 — 数据训练承诺信息缺口 #3（§ 7.2） |
| 1.3 算法（AI / ML） | ★★ 备选 — GitHub 集成深 / 多模型可选 | ★★★ 首选（Enterprise）— **1M token 长上下文是核心优势** / GCP 生态 / Cloud Workstations 多端 | ★ 不推荐 — Notebook 支持不明 |
| 1.4 EST | ★★ 备选 — 与研发共享 GitHub 体系（仅辅助脚本） | ★★★ 首选（Standard）— 计费亲民 / Web 端友好 / 自然语言强。**红线**：客户/订单数据不进 prompt（§ 7.3） | ★ 不推荐 — 独立 IDE 门槛过高 |
| 1.5 PWM 数据分析 | ★★ 备选 — 与公司 GitHub 体系一致 | ★★★ 首选（Standard）— BigQuery 集成深 / SQL 能力强。**红线**：客户画像 / KYC 不进 prompt（§ 7.3） | ★ 不推荐 — 客户画像合规风险 |
| 1.6 业务部门通用 | ★★ 备选（前提：已绑定 GitHub） | ★★★ 首选（Standard）— 计费亲民 / Web 端 / 自然语言 | ★ 不推荐 — 独立 IDE 门槛过高 |
| 1.7 产品经理 / PoC | ★★ 备选 — Workspace plan-first 流程 | ★★★ 首选（Standard）— Agent Mode 1M 上下文 / 0-1 PoC 友好 | ★ 不推荐 — Spec-driven 流程对快速 PoC 是负担 |
| 1.8 运营 | ★ 不推荐（成本过高） | ★★★ 首选（Standard）— 计费亲民 / 自然语言强 | ★ 不推荐（独立 IDE 门槛） |

### 4.2 矩阵读图说明

1. **评级是相对适配度**：在该部门场景下，工具与需求的契合程度。不是"工具能力的绝对强弱"。Copilot Enterprise 在所有部门都"能用"，但在 PoC / 运营场景成本过高（$39/月），所以评级低。评级的参照系是：在该部门具体场景中，这款工具能满足多少核心诉求，以及代价（成本、门槛、风险）是否匹配。

2. **★★★ 单一首选不代表"独家"**：有 GitHub Enterprise 已购的部门可以用 Copilot 替代 Gemini Standard 的 ★★★ 位（成本前提），实际推荐的"组合方案"见第 5 章。矩阵只描述"在标准采购情境下哪款工具适配度最高"，不排斥组合使用的可能性。

3. **★ 不推荐 ≠ 不能用**：是"在该部门场景下不优先"，特别是 Kiro — 整体 ★ 不推荐反映其 GA 仅 5 个月的成熟度风险，并非工具能力差。Kiro 适合作"试点池"，详见第 5 章和第 6 章。当 Kiro 的数据处理条款（信息缺口 #3，§ 7.2）和合规认证（信息缺口 #4，§ 7.2）通过法务审核后，部分评级可能上调。

4. **风险栏的红线条目对应 § 7.3**：量化 / EST / PWM 等部门的红线必须严格执行，不是建议。矩阵中标注"红线"的格子，对应 § 7.3 核心资产泄露红线和各部门 § 1.x 小节的"风险红线 + 小贴士"栏——两处互为引用，读图时须对照查阅。

5. **评级的"加权"说明**：本章评级综合了第 3 章 6 个能力维度（A. Harness, B. 模型, C. 能力, D. 上下文, E. 治理, F. 扩展性）+ 第 7 章合规约束。详细评分依据见第 1 章对应部门小节。每个部门的首选/次选/不推荐结论均在 § 1.x 中有明确论据，本章矩阵是其浓缩可视化，不独立于第 1 章单独成立。

### 4.3 矩阵汇总观察

从矩阵整体分布可得出以下观察：

- **Copilot Enterprise**：研发 / 量化辅助场景的最稳健选择 — IDE 覆盖广 + 模型多样 + 生态成熟，但成本高（$39/月）使其不适合面向业务 / PoC / 运营场景的"轻量需求"。在 8 个部门中拿到 2 个 ★★★（研发、量化），5 个 ★★（其余技术/业务部门），1 个 ★（运营）。成本结构决定了它是"精英工具"，不是"全员工具"。

- **Gemini Code Assist Standard**：5 个部门首选（EST / PWM / 业务通用 / 产品经理 / 运营）— 不是"业务向能力最强"，而是"经济性 + 自然语言友好性"的帕累托最优。$19/月的座位计费、无状态服务的低运维负担、Gemini 2.5 Pro 对自然语言→代码任务的强表现，三者叠加使其在轻量业务场景具有明显优势。

- **Gemini Code Assist Enterprise**：算法（AI / ML）部门的差异化首选 — 1M 上下文 / GCP 生态绑定的契合度最高。量化研究的次选地位也依赖于 CMEK（§ 7.2）这一独占特性。整体而言，Enterprise 层的优势是"深度 GCP 绑定 + 数据主权"，而非对所有部门通用。

- **AWS Kiro**：当前阶段在所有 8 个部门都不是首选 — GA 仅 5 个月 + 独立 IDE 形态 + 数据合规承诺缺失（信息缺口 #3 / #4，§ 7.2），三重不利因素叠加。0 个 ★★★ 不代表工具能力弱（Spec-driven 流程在三款中评级**强**，§ 3.1.2），而是当前时间节点下成熟度和合规透明度不足以支撑主力地位。建议作"试点池"参与第 5 章组合方案，不作主力。

## 第 5 章 推荐组合方案（B 方案落地）

### 5.1 推荐组合：3 个候选方案

> 本节给出 3 个候选组合，按"治理复杂度 × 部门适配度"权衡选择。第 4 章适配矩阵显示：8 个部门中，Copilot Enterprise 拿到 2 个 ★★★（研发 / 量化辅助），Gemini Code Assist 拿到 6 个 ★★★（算法 / EST / PWM / 业务 / PM / 运营），Kiro 全部 ★ 不推荐——但具备试点价值。三个候选组合均在此分布的基础上做不同权衡。

#### 组合 A（推荐）：Copilot Enterprise + Gemini Code Assist Standard + Kiro 试点

- **主力 1**：GitHub Copilot Enterprise — 研发（★★★ §1.1）/ 量化辅助（★★★ §1.2）/ 算法备选（★★ §1.3）
- **主力 2**：Gemini Code Assist Standard — EST（★★★ §1.4）/ PWM（★★★ §1.5）/ 业务通用（★★★ §1.6）/ PM（★★★ §1.7）/ 运营（★★★ §1.8）
- **可选升级**：Gemini Code Assist Enterprise — 算法部门首选（★★★ §1.3），在有 1M token 长上下文需求或 CMEK 数据主权要求（§7.2）时替代 Standard
- **试点池**：AWS Kiro — 限研发部门 spec-driven 项目，5~10 个种子席位，3 个月评估窗口（§2.3 成熟度警示）

**理由**：
- 与第 1 章各部门首选结论完全一致，与第 4 章适配矩阵 ★★★ 分布完全对应
- 治理复杂度适中：管理 2~3 款工具 + 1 个隔离试点池，治理边界清晰，License 池各自独立
- 部门适配度最优：8 个部门的首选均被覆盖，无强迫某部门使用次优工具的情况
- 成本结构合理：高频技术用户（研发 / 量化）用 Copilot Enterprise，轻量业务用户（EST / PWM / 业务 / PM / 运营）用 Gemini Standard（$19/月），避免全员套用 $39/月 Copilot 造成浪费

#### 组合 B（备选）：Gemini Code Assist Standard / Enterprise 主力 + Copilot Enterprise 保留

- **主力（Standard）**：Gemini Code Assist Standard — EST / PWM / 业务 / PM / 运营 / 量化辅助
- **主力（Enterprise）**：Gemini Code Assist Enterprise — 研发 / 算法（统一切至 GCP 生态）
- **保留**：GitHub Copilot Enterprise — 已有 GitHub Enterprise 账号的研发团队保留，覆盖 IDE 多样性（JetBrains / Vim / Neovim 等非 VS Code 场景）

**理由**：
- 与 GCP 生态的整合度更高（公司已是 GCP 深度用户、BigQuery 为核心数据平台时适用）
- License 成本结构更简单：Standard $19 / Enterprise $45，无 Copilot $39 与其他工具并行的账单复杂度
- CMEK 全覆盖：Gemini 是三款中唯一明确支持 CMEK 的（§7.2），若公司有数据主权要求，此方案是最直接的路径

**代价**：
- 研发部门的 IDE 覆盖灵活度受限：Gemini Code Assist 不覆盖 Vim / Neovim / Eclipse 等，使用这些 IDE 的工程师必须切换至 VS Code 或 JetBrains（§3.6.2）
- Copilot 的模型选单优势（15+ 可选模型，含 Claude Opus 4.x / GPT-5.x 系列）被放弃，研发团队失去按任务复杂度切换模型的灵活性（§3.2）
- 单一供应商（Google Cloud）集中度风险上升

#### 组合 C（保守）：仅 Copilot Enterprise（一刀切）

- **主力**：GitHub Copilot Enterprise — 全部门统一（$39/user/月）

**理由**：
- 治理最简：一份合同 / 一个 SSO / 统一 License 池 / 一套 Audit Log，IT 管理成本最低
- GitHub 生态绑定深的公司天然适合：代码已在 GitHub Enterprise Cloud，Spaces / Code Review / Actions 全链路整合，零额外摩擦

**代价**：
- 算法部门（§1.3）的 1M token 长上下文优势被牺牲：Copilot 声称上下文窗口因底层模型和裁剪策略而异，实际可用率不及 Gemini Enterprise 的 1M token（§3.4.2）
- 轻量用户成本偏高：EST / PWM / 业务 / PM / 运营等部门的任务场景用 Gemini Standard（$19/月）已足够，强制 $39/月 Copilot 相当于对轻量用户翻倍付费（§4.3 矩阵观察）
- 单一供应商锁定：所有部门对 GitHub / Microsoft 生态强依赖，供应商谈判议价能力下降
- Kiro Spec-driven 流程探索机会丧失：研发团队无法以低成本试点 spec-driven 工作流

---

### 5.2 部门到工具的映射表（按推荐组合 A）

| 部门 | 主力工具 | 备选 | 备注 |
|---|---|---|---|
| 1.1 研发（含 CI/CD） | Copilot Enterprise | Gemini Code Assist Enterprise | 含 CI/CD 子场景；核心资产（风控引擎等）须配置 Spaces policy 隔离（§1.1 红线） |
| 1.2 量化研究 | Copilot Enterprise（仅辅助脚本） | Gemini Code Assist Enterprise（若有 CMEK 需求） | 策略代码 / 因子库 / Alpha 因子**不进任何 SaaS prompt**（§7.3 红线，无例外） |
| 1.3 算法（AI / ML） | Gemini Code Assist Enterprise | Copilot Enterprise | 1M token 长上下文是算法场景核心优势（§3.4.2）；训练数据集 / 模型权重不进 prompt（§1.3 红线） |
| 1.4 EST | Gemini Code Assist Standard（仅辅助脚本） | Copilot Enterprise（与研发共享 GitHub 体系时） | 客户订单 / 持仓 / 盘中数据**绝对不进任何 SaaS prompt**（§7.3 红线，无例外） |
| 1.5 PWM 数据分析 | Gemini Code Assist Standard | Copilot Enterprise（与公司 GitHub 体系一致时） | 客户画像 / KYC / 持仓明细不进 prompt；数据脱敏流程须先于工具铺开（§1.5 红线） |
| 1.6 业务通用 | Gemini Code Assist Standard | Copilot Enterprise（已纳入 GitHub License 池时） | 池化 License 自助申请；月度 token 消耗须设 budget cap（§1.6 小贴士） |
| 1.7 产品经理 / PoC | Gemini Code Assist Standard | Copilot Enterprise（需 Workspace plan-first 流程时） | 池化 License 自助申请；PoC 数据若来自真实客户须确认脱敏（§1.7 红线） |
| 1.8 运营 | Gemini Code Assist Standard | — | 池化 License 自助申请；工具定位为"高级 Excel 公式辅助"，不替代专业开发（§1.8 小贴士） |
| 试点池 | AWS Kiro | — | 限研发部门 spec-driven 项目，5~10 个固定种子席位，3 个月评估窗口；须在采购前向 AWS 索取书面 DPA（§7.2 信息缺口 #3） |

---

### 5.3 License 池粗估（推荐组合 A）

> 以下是数量级估算，**不报具体单价**（采购另谈）。具体单价见第 2 章工具速览 + 附录 vendor-terms.md。

#### 假设公司规模

设公司技术线分布（仅作示例，实际数字按公司报告填）：

- 研发（含 CI/CD）：N₁ 人
- 量化研究：N₂ 人
- 算法（AI / ML）：N₃ 人
- EST + 交易支持：N₄ 人
- PWM 数据分析：N₅ 人
- 业务通用 + 运营：N₆ 人
- 产品经理：N₇ 人

#### License 数量建议

| 工具 / Tier | 推荐席位数 | 备注 |
|---|---|---|
| Copilot Enterprise | N₁ + N₂ × 30%（仅辅助脚本用户子集） | 研发全员 + 量化辅助子集；N₂ 比例由部门 AI 工具使用清单核定（§1.2 小贴士） |
| Gemini Code Assist Enterprise | N₃ | 算法部门全员；若算法团队暂无 CMEK 或 1M 上下文刚性需求，可先用 Standard 降成本 |
| Gemini Code Assist Standard（池化） | N₄ + N₅ + N₆ + N₇，预留 20~30% 缓冲 | 池化自助申请，月度 budget cap 管控；缓冲席位用于新员工入职 / 跨部门试用 |
| AWS Kiro 试点池 | 5~10 个固定席位 | 仅限研发部门 spec-driven 项目志愿者；试点期间采用 Pro 或 Pro+ 计划，Enterprise 计划待 DPA 核清后再评（§7.2 信息缺口 #3） |

**关键说明**：

- **EST / 量化部门的席位数量**须在红线规则核对完成后确定。若策略代码 / 客户数据须严格限制，实际可使用工具的员工比例（即"允许进入 prompt 的代码场景"覆盖范围）可能远低于部门总人数，不宜按全员配席位
- **Gemini Standard 池化机制**：建议由 IT 统一采购批量席位，员工按需申领（设月度申请上限），避免工具散购导致账单失控
- **Copilot credits 超用管控**：Copilot Enterprise 2026-06-01 起切换为 AI Credits token 计费，管理员须为各部门设置 budget cap，防止 Agent Mode / 外部模型调用的超用费用（§2.1 计费说明）

---

### 5.4 与 Claude Code / Codex 类基准的关系

当前候选三款工具（Copilot Enterprise / Gemini Code Assist / AWS Kiro）均为商业 SaaS 产品，受企业规划 / 采购流程 / 地区访问限制约束，是本次选型的实际候选范围。

**Claude Code 与 Codex 的定位**

Claude Code（§3.1.4 / §3.4.4 / §3.6.4）和 Codex（§3.1.4）是本报告 agentic 能力评估的**基准对照工具**，代表当前 AI 代码助手能力的顶端水位：

- **Claude Code**：SWE-bench Verified 排名持续领先（Claude Opus 4.7 Adaptive 87.6%，§6.1），harness engineering 三层扩展（plan mode + Subagents + Skills）、探索式无索引代码理解、Auto Memory 多级记忆机制，在量化场景的合规优势（私有代码不建持久索引，§3.4.4）是当前三款候选无法复制的结构性差异
- **Codex**（OpenAI）：Terminal-Bench 2.0 当前第一（GPT-5.5 via Codex agent 82.0%，§6.2），纯云端 agent 形态，IDE 集成路径与三款候选不同

**暂不在采购候选的原因**：

1. 中国大陆访问路径：Anthropic 和 OpenAI 均无中国区域节点，访问链路与三款候选相同（出境 + 代理），但企业采购通道（Anthropic Console / OpenAI API 企业协议）的中国地区可用性、支付渠道、合规文件尚未经过本次评估核验
2. 企业管控层面：Claude Code 以开发者为中心的自主工作方式，在集中 License 管控 / RBAC / 审计日志等企业治理维度的支持度不如三款候选工具（§3.5），需要额外治理设计
3. 本次评估时间窗口与资源约束

**6 个月复审建议**

建议每 6 个月对工具组合进行一次"基准复审"，重点关注以下变化信号：

- **MCP 生态演进**：特别是 Copilot Enterprise 是否进一步原生支持 MCP（当前 §3.6.2 评级**一般**），以及三款工具与内部系统（Bloomberg Terminal / Wind / 内部 RAG）的 MCP Server 接入成熟度是否提升
- **AWS Kiro 成熟度**：Kiro GA 满 1 年时点（约 2026-11）是再评的自然节点，届时数据训练承诺（信息缺口 #3）和合规认证（信息缺口 #4，§7.2）的透明度应已改善，可考虑扩展试点规模或升级为部分部门主力
- **Anthropic / OpenAI 的企业采购可行性**：如 Claude Code 类工具进入企业采购候选（特别是 Claude.ai Teams / Enterprise 在亚太区域的合规文件完善），应启动对应的法务核查流程
- **基准数据更新**：§6.1 SWE-bench Verified / §6.2 Terminal-Bench 数据变化较快（截至 2026-04-29），下次复审时须以最新榜单数据替换

**长期视角：自建 + 商业混合方案**

如果 Claude Code 类工具未来进入采购候选，且 MCP 生态足够成熟，可考虑接入公司内部 RAG 知识库（如 ragent 项目，当前基于 OpenSearch + Spring Boot 的 RAG 框架），形成"自建 RAG 知识库 + 商业 AI 代码助手"的混合架构——MCP Server 将内部知识库、代码仓库、合规文档等公司特有上下文暴露给 AI 工具，在不将敏感代码上传厂商云端的前提下提供深度上下文感知。这超出本次评估范围，但作为**未来评估窗口**留下伏笔：混合架构的可行性、治理设计和安全模型，建议在下一轮 6 个月复审时专项立题评估。

---

### 5.5 推荐结论

**推荐采用组合 A**，理由简明：第 4 章适配矩阵的 ★★★ 分布是决策的直接依据——Copilot Enterprise 在研发 / 量化辅助场景综合优势明确，Gemini Code Assist Standard / Enterprise 在其余 6 个部门首选地位明确，Kiro 以试点身份低成本探索 spec-driven 流程价值，三者边界清晰、治理可控。

**采用工具清单**：

- **GitHub Copilot Enterprise**（研发 / 量化辅助 / 算法备选）
- **Gemini Code Assist Enterprise**（算法首选）
- **Gemini Code Assist Standard**（EST / PWM / 业务 / PM / 运营，池化 License）
- **AWS Kiro**（限研发部门 spec-driven 项目试点池，5~10 席位，3 个月评估窗口）

**实施前置条件（必须完成，不可并行跳过）**：

1. **法务核对 §7.4 开放清单 5 条**，特别是 Kiro DPA（信息缺口 #3）须在 Kiro 试点启动前取得书面回复；Copilot CMEK（信息缺口 #2）须在量化部门使用前明确
2. **数据脱敏 / prompt 输入白名单流程的工程化落地**，优先级高于工具铺开——EST / 量化 / PWM 部门若无脱敏流程和 prompt 白名单，对应工具不应开放使用（§7.3 红线）
3. **第 6 章风险与路线图的三阶段执行**：试点（研发 + 算法先行）→ 推广（EST / PWM / 业务）→ 全量（运营 / PM）；不建议全部门同步铺开，分批上线可积累经验、降低合规风险

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
