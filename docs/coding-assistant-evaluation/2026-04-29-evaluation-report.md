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
