# 代码助手企业选型分析 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec 完成一份 25~30 页的代码助手企业选型 Markdown 主文档 + 3 份附录 + 1 份入口 README，支撑公司技术管理层和产品经理对 GitHub Copilot Enterprise / Gemini Code Assist / AWS Kiro 三款工具的部门组合采购决策。

**Architecture:** 单包文档项目，无代码改动。文档树：`docs/coding-assistant-evaluation/` 下 1 个主文档 + `appendix/` 三份。写作顺序为"附录与方法论先行 → 工具速览 → 合规 → 技术深度章节 → 部门画像 → 矩阵 / 推荐 / 风险 → TL;DR 收口"。每个章节独立成 commit，便于回滚和 review。

**Tech Stack:** Markdown（GitHub-flavored）、Context7 MCP 拉厂商文档、WebFetch 抓厂商博客 / 条款。无构建系统，无测试框架。

**全局写作约定（所有任务通用）：**
- 评级一律使用 **强 / 一般 / 弱** 三档（spec § 4.2），不打数字分，不混用"高/中/低"
- 任何能力声明必须能引到一手或二手来源；无来源的项标"未在公开材料中明确说明"，不要编
- 时态：以"截至 2026-04-29"为基准；声称的能力（vs 实测）必须显式标注
- 命名首次用全称："GitHub Copilot Enterprise"、"Gemini Code Assist Enterprise"、"AWS Kiro"；后续可简称

**Spec 引用：** [docs/superpowers/specs/2026-04-29-coding-assistant-evaluation-design.md](../specs/2026-04-29-coding-assistant-evaluation-design.md)

**Spec 章节 → 任务映射：**
- Spec § 1（背景与决策目标）→ 主文档第 0 章 TL;DR 引用 + 不需独立任务
- Spec § 2（候选与基准）→ Task 4
- Spec § 3（部门画像）→ Task 10
- Spec § 4（评测维度）→ Task 6/7/8/9（按详略分布到 6 个子节）
- Spec § 5（合规约束）→ Task 5 + Task 3 中的 vendor-terms
- Spec § 6（章节结构）→ 全计划骨架
- Spec § 7（产出物）→ Task 1（脚手架）+ Task 14（README）
- Spec § 8（评测方法）→ Task 3 中的 methodology
- Spec § 9（读者风格）→ 写作时贯穿，不独立任务
- Spec § 10（风险与不确定性）→ Task 13

---

## File Structure

最终交付的 5 个文件：

| 路径 | 责任 |
|---|---|
| `docs/coding-assistant-evaluation/README.md` | 入口 / TL;DR 复制 / 文件导航 |
| `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` | 主文档第 0~7 章 + 内嵌"附录索引"指向 appendix/* |
| `docs/coding-assistant-evaluation/appendix/methodology.md` | 评测方法 / 评分口径 / 信息来源优先级 |
| `docs/coding-assistant-evaluation/appendix/vendor-terms.md` | 厂商企业版条款摘录 + 原文链接 |
| `docs/coding-assistant-evaluation/appendix/benchmarks.md` | SWE-bench / Terminal-bench / LiveCodeBench 等 benchmark 引用与口径 |

工作版分支建议：直接在 `main` 上做（这是文档，无破坏性改动；每章一次 commit 已经足够细粒度）。

---

## Task 1: 脚手架与主文档骨架

**Files:**
- Create: `docs/coding-assistant-evaluation/README.md`
- Create: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`
- Create: `docs/coding-assistant-evaluation/appendix/methodology.md`
- Create: `docs/coding-assistant-evaluation/appendix/vendor-terms.md`
- Create: `docs/coding-assistant-evaluation/appendix/benchmarks.md`

- [ ] **Step 1: 创建目录与五个空文件，主文档落入完整章节占位**

主文档 `2026-04-29-evaluation-report.md` 写入这个骨架（每章只放标题 + 一行 "TBD-Task-N" 标记，方便后续任务定位）：

```markdown
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
<!-- TBD: Task 4 -->

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
<!-- TBD: Task 5 -->

## 附录索引

- [评测方法 methodology.md](appendix/methodology.md)
- [厂商条款 vendor-terms.md](appendix/vendor-terms.md)
- [Benchmark 引用 benchmarks.md](appendix/benchmarks.md)
```

三个 appendix 文件初始化为只有一级标题：

```markdown
# 评测方法、评分口径与信息来源

> 截至 2026-04-29

<!-- TBD: Task 3 -->
```

```markdown
# 厂商企业版条款摘录与原文链接

> 截至 2026-04-29

<!-- TBD: Task 3 -->
```

```markdown
# Benchmark 引用与口径说明

> 截至 2026-04-29

<!-- TBD: Task 3 -->
```

`README.md` 暂空，等 Task 14 写。先放占位：

```markdown
# 代码助手企业选型分析

文档入口将在 Task 14 完成。当前请直接看 [evaluation-report.md](2026-04-29-evaluation-report.md)。
```

- [ ] **Step 2: Commit 脚手架**

```bash
git add docs/coding-assistant-evaluation/
git commit -m "docs(coding-assistant): 脚手架 — 主文档章节骨架 + 附录占位"
```

---

## Task 2: 信息源汇总（不写入文档，只做调研记录）

> 这一步在脑内/临时笔记里完成，不产生文档变更。目的是为后续所有章节锁定权威源 URL，避免每章都重复搜索。

**Files:** （无文件改动；产出是后续每个章节使用的引用清单）

- [ ] **Step 1: 用 Context7 MCP 解析三款工具的官方文档库 ID**

```
mcp__context7__resolve-library-id "github copilot"
mcp__context7__resolve-library-id "gemini code assist"
mcp__context7__resolve-library-id "aws kiro"
mcp__context7__resolve-library-id "claude code"
```

记录返回的 `library_id`，后续 `query-docs` 调用直接用。

- [ ] **Step 2: WebFetch 收集每款工具的"产品 / 定价 / 条款"三类锚点页**

GitHub Copilot Enterprise：
- https://docs.github.com/en/enterprise-cloud@latest/copilot/overview-of-github-copilot/about-github-copilot-business
- https://github.com/features/copilot/plans
- https://docs.github.com/en/copilot/managing-copilot/managing-copilot-as-an-individual-subscriber/about-github-copilot-individual
- https://docs.github.com/en/site-policy/privacy-policies/github-copilot-product-specific-terms

Gemini Code Assist：
- https://cloud.google.com/gemini/docs/codeassist/overview
- https://cloud.google.com/gemini/docs/discover/works
- https://cloud.google.com/gemini/pricing
- https://cloud.google.com/terms/aiml

AWS Kiro：
- https://kiro.dev/
- https://kiro.dev/docs/
- https://aws.amazon.com/blogs/aws/?s=Kiro
- https://kiro.dev/pricing/（如存在；不存在时记录 "preview 阶段无公开定价"）

Claude Code（基准对照）：
- https://docs.anthropic.com/en/docs/claude-code/overview
- https://docs.anthropic.com/en/docs/claude-code/skills
- https://docs.anthropic.com/en/docs/claude-code/mcp
- https://docs.anthropic.com/en/docs/claude-code/sub-agents
- https://docs.anthropic.com/en/docs/claude-code/memory

Codex（基准对照）：
- https://openai.com/index/introducing-codex/（最新形态）
- https://platform.openai.com/docs/codex（如有）

- [ ] **Step 3: Benchmark 锚点**

- https://www.swebench.com/（SWE-bench Verified leaderboard）
- https://terminal-bench.com/
- https://livecodebench.github.io/

每个 benchmark 记录"最新发布日期 + 三款候选的最近成绩（如有）"。注意：很多 benchmark 不直接评测"产品"，只评模型；所以 GitHub Copilot 的成绩需用其底层模型成绩代理。

- [ ] **Step 4: 把汇总的 URL + library_id 列表写到 chat 上下文里（不入文档）**

后续 Task 3~13 都引用这份清单。**这一 Task 不 commit。**

---

## Task 3: 三份附录（methodology / vendor-terms / benchmarks）

**Files:**
- Modify: `docs/coding-assistant-evaluation/appendix/methodology.md`
- Modify: `docs/coding-assistant-evaluation/appendix/vendor-terms.md`
- Modify: `docs/coding-assistant-evaluation/appendix/benchmarks.md`

> 三份附录先写，让后续章节可以直接 cross-reference，避免在主文档里重复堆砌方法论与原始数据。

- [ ] **Step 1: 写 methodology.md**

内容包含 4 节：

1. **评测方法**：不做并行实测，依赖一手文档 + 二手分析 + 厂商声称 + 个别使用反馈。
2. **评分口径**：三档定性 — 强 / 一般 / 弱。每条 must 附"理由 + 论据来源"。明确说"不打数字分"以及为什么。
3. **信息来源优先级**：
   - P0 一手 — 厂商官方文档、企业版条款、定价页、产品博客、API/SDK 文档
   - P1 二手 — 独立技术分析、社区 benchmark（标注发布日期）
   - P2 基准对照 — Claude Code / Codex 官方文档与 Anthropic / OpenAI 公开材料
4. **时效性声明**：截至 2026-04-29。三款工具迭代快速，半年内复审。

每节 ~150 字，全文 ~600 字。

- [ ] **Step 2: 写 vendor-terms.md**

按"厂商 → 子项"二级结构，每款企业版列出：

- **数据训练承诺**：是否承诺企业代码不用于训练（引条款原文 + 链接）
- **数据留存**：是否承诺零留存或保留多少天（引条款）
- **审计 / 日志**：企业版是否提供审计日志（管理员视角）
- **加密 / 密钥**：是否支持 CMEK / 客户管理密钥
- **合规认证**：声称的 SOC2 / ISO27001 / HIPAA 等
- **大陆访问**：基于 spec § 5.1 的可用性结论

每款 ~250 字 + 1~3 条原文引用块。**所有引用必须带条款页面 URL。** 无法找到的项目标"未在公开条款中明确说明"，不要编。

- [ ] **Step 3: 写 benchmarks.md**

三个表 + 一段口径说明：

1. **SWE-bench Verified** — 列三款工具底层模型的最新成绩（如 GPT-5、Gemini 2.5 Pro、Claude Sonnet 4.6 等），以及其在 SWE-bench Verified 上的得分；标注发布日期。
2. **Terminal-bench** — 同上。
3. **LiveCodeBench** — 同上。

口径说明（~200 字）：
- 这些 benchmark 评的是"模型"不是"产品"，产品体验受 harness 影响很大
- 不同模型 benchmark 时间不一致，避免直接横向比较
- benchmark 不能反映金融行业代码场景（量化策略 / VBA / Bloomberg API）；此处仅作"通用 coding 能力"的参考

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/appendix/
git commit -m "docs(coding-assistant): 附录三件套 — methodology + vendor-terms + benchmarks"
```

---

## Task 4: 第 2 章 三款工具速览

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换 `<!-- TBD: Task 4 -->`）

- [ ] **Step 1: 写每款工具的"能力名片"**

每款固定 7 字段：

1. **厂商定位**（一句话） — 例如 "GitHub/Microsoft 主推的 IDE 内代码助手，覆盖 VSCode / JetBrains / Visual Studio / Neovim / Xcode"。
2. **产品形态** — IDE 插件 / 独立 IDE / CLI / Web。
3. **核心卖点 3~5 条** — 例如 Copilot 的"GitHub 代码搜索集成 / Workspace / Spaces / 内嵌 Code Review"。
4. **大陆可用性**（引用第 7 章硬约束 1 的对应行）。
5. **默认模型 + 可切换模型**（引用附录 vendor-terms 与 benchmarks）。
6. **计费模式 + 企业版门槛**（单座 license / 团队池 / 是否支持试用）。
7. **~80 字总评** — 一段中性描述，不夸不贬。

**特别处理 AWS Kiro**：在卡片末尾加"成熟度警示"框，明确 "截至 2026-04 仍在 Preview / 区域有限 / 公开定价缺失或频繁变动 / 推荐定位为试点而非主力"。

- [ ] **Step 2: 自检 — 三款字段是否对齐**

确认三款工具都填了 7 字段；任何一项写不出，标"未在公开材料中明确说明"，不要编。

- [ ] **Step 3: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 2 章 — 三款工具速览能力名片"
```

---

## Task 5: 第 7 章 合规与可用性约束

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换 `<!-- TBD: Task 5 -->`）

> 第 7 章在依赖章节最少时优先写，因为它给后续部门画像和推荐组合提供"红线"约束。

- [ ] **Step 1: 写硬约束 1 — 大陆可用性**

照搬 spec § 5.1 表格，但在每行后追加 1~2 句"对部门选型的影响"。例如：
> **Kiro 在大陆需走代理 + 海外 AWS 区域**：意味着延迟与稳定性显著低于 Copilot / Gemini，落地必须考虑代理层 SLA 和数据落地区域。

- [ ] **Step 2: 写硬约束 2 — 数据训练 / 留存**

构造三款企业版的对照表：

| 维度 | Copilot Enterprise | Gemini Code Assist Ent | AWS Kiro |
|---|---|---|---|
| 零训练承诺 | 引附录 vendor-terms | 引附录 | 引附录 |
| 留存策略 | ... | ... | ... |
| CMEK / 密钥管理 | ... | ... | ... |
| 审计日志 | ... | ... | ... |

表格下追加 100 字总结："企业版承诺差异虽小但条款原文有 nuance，落地前必须由法务逐条核对。"

- [ ] **Step 3: 写说明 3 — 核心资产泄露红线**

照 spec § 5.3，明确两条：

1. Prompt 仍上传厂商服务器做推理（即使不训练）
2. 一旦服务商被攻破 / 内鬼 / 司法调取，数据可能落入第三方

**红线规则**：核心资产相关代码 / 数据建议规避走 SaaS 路径。

特别给 EST / 量化 / PWM 三个部门点名："这条规则在第 1 章对应部门的'风险红线'栏复述。"

- [ ] **Step 4: 写 § 5.4 开放清单**

照搬 spec § 5.4 的 5 条开放项，明确写"以下事项不在本报告评估范围，需由合规 / 法务 / IT 安全部门进一步核对"：
- 跨境数据传输的具体监管要求
- 证监会 / 交易所对 AI 工具的审计要求
- 等保 2.0 / 数据分级
- 出问题的责任界定 / 合同条款细节
- 各家是否提供 SOC2 / ISO27001 报告

- [ ] **Step 5: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 7 章 — 合规与可用性约束"
```

---

## Task 6: 第 3 章 轻写三节（3.2 + 3.3 + 3.5）

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换三个 `<!-- TBD -->`）

> 轻写章节合在一起做，每节 ~300 字 + 1 张表，写得快。

- [ ] **Step 1: 写 3.2 模型支持与路由**

一张大表 + 一段总结：

| 维度 | Copilot Enterprise | Gemini CA Ent | Gemini CA Std | AWS Kiro |
|---|---|---|---|---|
| 默认模型 | (查 vendor docs) | (查) | (查) | (查) |
| 可选模型清单 | ... | ... | ... | ... |
| 切换粒度（per-task / per-feature / global） | ... | ... | ... | ... |
| BYOK / 自定义端点 | ... | ... | ... | ... |
| 模型版本更新节奏 | ... | ... | ... | ... |
| 路由策略（autocomplete vs chat vs agent 是否分模型） | ... | ... | ... | ... |

总结 ~150 字：哪款"模型多样性"最强，哪款"统一路由"最简洁，谁支持 BYOK。

- [ ] **Step 2: 写 3.3 模型 coding / agentic 能力**

引用附录 benchmarks.md 的成绩，不重复列分数。结构：

1. **公开 benchmark 一览**（引附录）
2. **三款工具底层模型当前成绩区间**（一段总结）
3. **★ Codex 基准对照**：~150 字，描述 Codex Cloud + CLI 的 agentic 任务完成率天花板，以及当前 GPT-5 在 SWE-bench Verified 的成绩，作为 "目前 agentic coding 的天花板大致在哪里" 的参照。
4. **实战体感段落**：~200 字，基于厂商 demo + 公开评测，说三款的多语言覆盖差异（Java / Python / TS / Go / SQL / VBA / Rust），特别点出 VBA / KDB+ 等金融行业小众语言的可能短板。

不打分，不排序。

- [ ] **Step 3: 写 3.5 可靠性与企业治理**

不重复第 7 章合规内容，本节聚焦运营可靠性：

- **SLA / 可用性**：各家公开承诺（Copilot 99.9%、Gemini Cloud SLA、Kiro Preview 阶段无 SLA）
- **降级策略**：模型不可用时的 fallback 行为（Copilot autocomplete 走本地缓存 / Gemini 自动切换到次级模型 / Kiro Preview 阶段未明确）
- **企业管控**：SSO / SCIM / RBAC / license 池 / policy / guardrail 一张表
- **内容过滤 / 公开代码片段检测**：Copilot 有 duplication detection、Gemini 有 attribution citation、Kiro 状态待确认

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 3 章 轻写 — 3.2 模型 / 3.3 能力 / 3.5 治理"
```

---

## Task 7: 第 3.1 节 Harness Engineering（详写）

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换 3.1 占位）

- [ ] **Step 1: 写"维度说明"段（~150 字）**

为什么 Harness Engineering 是决策性维度：模型一样的两款工具，harness 设计差距能让 agentic 任务完成率差 30%+。harness 决定了"模型怎么被使唤"。

- [ ] **Step 2: 写"三款现状"对照（每款 ~300 字 + 一张子能力表）**

子能力清单：
1. **系统提示与上下文构造** — 三款各自如何往模型注入项目上下文
2. **工具调用机制** — tool use 设计 / 并行调用 / 错误恢复 / 循环防护
3. **Plan mode 与 Spec-driven 流程** — Kiro 的 spec-driven 是核心差异化（卖点），Copilot Workspace 的 plan-first 流程，Gemini Code Assist 是否有类 plan mode
4. **Subagent / 多 agent 编排** — 三款是否支持子任务派发
5. **Memory** — 会话内 / 跨会话 / 项目级（注意 Copilot 的 Spaces / Gemini 的 codebase awareness / Kiro 的 steering files）
6. **权限模型** — auto-approve / 敏感操作 gate / sandboxing

- [ ] **Step 3: 写"★ Claude Code 基准对照"段（~300 字）**

明确点出 Claude Code 的 5 个 harness 能力作为顶尖参照：

1. **Plan Mode** — 在执行前强制产出可审阅的 plan，对应 Kiro 的 spec-driven 但更轻量
2. **Subagents** — 通过 `Agent` 工具派发隔离上下文的子任务
3. **Skills** — 用 `Skill` 触发可复用的工作流模板（brainstorming / writing-plans / executing-plans）
4. **TodoWrite** — 长任务的进度跟踪
5. **Permission tiers** — bypassPermissions / acceptEdits / plan / default 四档

每个能力对照三款候选的"对应能力 / 缺失"。

- [ ] **Step 4: 写"对部门选型的影响"段（~200 字）**

哪些部门最受 harness 差异影响：研发（大型项目长期维护）、量化（Spec-driven 帮助回测代码组织）。
哪些部门基本不受影响：业务通用、运营、PWM 数据分析（场景一次性，harness 简洁就行）。

- [ ] **Step 5: 自检 — 子能力 6 项每款是否都填了**

如果某款的某项无公开材料，标"未在公开材料中明确说明"，不要编。

- [ ] **Step 6: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 3.1 节 — Harness Engineering 详写 + Claude Code 基准对照"
```

---

## Task 8: 第 3.4 节 代码理解与上下文深度（详写）

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换 3.4 占位）

- [ ] **Step 1: 写"维度说明"段（~150 字）**

为什么上下文深度是决策性维度：金融公司大型项目动辄 50+ 万行代码，光靠模型上下文窗口装不下；必须靠 harness 的索引 / 检索 / 探索式扫描。声称的 1M 上下文 ≠ 实际可用率。

- [ ] **Step 2: 写"三款现状"对照（每款 ~300 字 + 一张子能力表）**

子能力清单：
1. **单仓索引规模上限**（Copilot 的 GitHub 代码搜索能力 / Gemini 的 codebase awareness / Kiro 的 indexing 边界）
2. **增量索引时延**（首次索引耗时 + 文件变化后多久生效）
3. **跨仓 / 跨服务的上下文聚合**（multi-repo / mono-repo 的支持）
4. **代码图感知**（call graph / 依赖图 / 类型图谱）
5. **长上下文实际可用率**（声称 vs 实际，引用公开 needle-in-a-haystack 评测如有）
6. **私有代码库索引部署形态**（云端索引 / 本地索引 / 混合）

- [ ] **Step 3: 写"★ Claude Code 基准对照"段（~300 字）**

Claude Code 走"探索式理解"路线 — 不预先索引，而是用 grep + agent 主动探索文件树。优劣：

- **优**：不需建索引就能开干、随仓库变化自动更新、不存私有代码到第三方索引服务
- **劣**：单次查询要扫描更多文件、对超大仓库（>50 万行）有效率上限

对照三款候选的"索引式理解"路线（都需要先建索引），讨论哪种更适合金融公司：
- 金融公司的代码资产保密要求高，可能更欢迎 Claude Code 的"无索引"路径，但企业治理弱
- 三款候选有索引但需上传代码到厂商云，与第 5 章核心资产红线冲突

- [ ] **Step 4: 写"对部门选型的影响"段（~200 字）**

研发、算法、量化（大代码库长期维护）— 上下文深度是首要决策点。
业务、PoC、运营 — 上下文需求小，本节几乎不影响选型。

- [ ] **Step 5: 自检与 Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 3.4 节 — 上下文深度详写 + Claude Code 探索式基准对照"
```

---

## Task 9: 第 3.6 节 扩展性与生态（详写）

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换 3.6 占位）

- [ ] **Step 1: 写"维度说明"段（~150 字）**

为什么扩展性是决策性维度：金融公司有大量内部系统（Jira / Confluence / 内部 CI / 私有 Git / Bloomberg Terminal / Wind 数据 / 内部知识库），代码助手能否对接决定了实用性。

- [ ] **Step 2: 写"三款现状"对照（每款 ~300 字 + 一张子能力表）**

子能力清单：
1. **IDE / CLI 覆盖度** — VSCode / JetBrains / Vim / Neovim / Visual Studio / Web / 终端
2. **MCP 支持现状** ★ — 这是 2025-2026 的关键能力点：
   - Copilot：GitHub Copilot 的 extension 体系，是否原生 MCP 支持
   - Gemini：是否原生 MCP 支持
   - Kiro：原生支持 MCP（这是 Kiro 主打卖点之一）
3. **Plugin / Extension / Skills 类机制** — 各家可复用流程模板的支持程度
4. **与公司既有系统集成** — Jira / Confluence / 私有 Git / 内部 RAG / Bloomberg / Wind
5. **API / Webhook** — 是否暴露 API 给企业自定义集成

- [ ] **Step 3: 写"★ Claude Code 基准对照"段（~400 字）**

Claude Code 的"三层扩展模型"作为标杆：

1. **MCP（Model Context Protocol）** — 标准化协议，第三方可对接任意 MCP server（Filesystem / Slack / Jira / 自建 RAG）
2. **Plugins** — 打包发布的 skill/agent/command 集合，可分享、版本化
3. **Skills** — 触发式工作流模板（如 brainstorming / writing-plans / executing-plans / frontend-design）

对照：
- Kiro 已宣称原生 MCP 支持，可能是三款中最贴近 Claude Code 扩展模型的
- Copilot 走 GitHub extension 路线，生态丰富但与 MCP 标准未对齐
- Gemini Code Assist 当前以官方功能为主，第三方扩展弱

讨论"对接公司内部 RAG / 知识库（如 ragent）"的可行性：MCP 兼容的工具天然适合接公司内部 MCP server。

- [ ] **Step 4: 写"对部门选型的影响"段（~200 字）**

研发部门高度重视（要接 Jira / 内部 CI / 私有 Git）。
EST / PWM 重视（要接 Bloomberg / Wind / 内部数据库）。
量化重视（要接回测平台 / 数据库）。
业务、PoC、运营弱（一次性脚本，不要求集成）。

- [ ] **Step 5: 自检与 Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 3.6 节 — 扩展性详写 + Claude Code 三层扩展基准对照"
```

---

## Task 10: 第 1 章 部门画像 & 推荐结论（决策入口）

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换第 1 章占位）

> 第 1 章是"决策入口"，依赖第 2~3 章和第 7 章的所有结论。所以放在技术章节后写。

- [ ] **Step 1: 写章节引言（~200 字）**

说明本章是全文决策入口；技术管理层 / 产品经理可以只看本章 + 第 4 章矩阵 + 第 5 章推荐方案。

- [ ] **Step 2: 写 8 个部门小节，每节固定 5 栏结构**

按以下顺序写（影响力高的在前）：

1. **研发**（含 CI/CD 子场景）
2. **量化研究**
3. **算法（AI）**
4. **EST**
5. **PWM 产品数据分析**
6. **产品经理 / PoC**
7. **业务通用**
8. **运营**

每节固定 5 栏：

```markdown
### 1.X 部门名

**画像**
（角色 / 场景 / 关键诉求 — 3~5 句）

**首选工具：[X]**
（理由 — 引用第 3 章哪些维度的优势 — 100~150 字）

**次选工具：[Y]**（适用条件：……）

**不推荐：[Z]**（原因：……）

**风险红线**
- ……
- ……

**小贴士**
- ……
- 与 Claude Code 基准的对照差距：……（如适用）
```

每节 ~400~500 字。8 个部门约占 ~6~8 页。

**示例：研发部门首选工具的写法（Copilot Enterprise 或 Gemini CA Ent）**

> 首选 GitHub Copilot Enterprise。理由：
> 1. IDE 覆盖最广（VSCode / JetBrains / VS / Neovim），与公司主力 IDE 对齐
> 2. GitHub 代码搜索 + Spaces 在大型私有代码库的上下文检索成熟（参 § 3.4）
> 3. 企业版 license 池管理与 SSO 成熟（参 § 3.5）
>
> 风险：与公司核心资产（量化策略代码）混用同一 license 池时需通过 policy 隔离，否则触发第 7 章红线。

- [ ] **Step 3: 自检 — 8 个部门是否都覆盖了 spec § 3 表格**

对照 spec § 3 部门画像 v2 表格，确认每个部门的"关键诉求"在本章首选 / 次选 / 不推荐里都给出回应。

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 1 章 — 8 部门画像与推荐结论"
```

---

## Task 11: 第 4 章 部门 × 工具适配矩阵

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换第 4 章占位）

- [ ] **Step 1: 写大矩阵表**

8 部门 × 3 工具 × 3 子项（推荐度 / 关键理由 / 风险）。

格式示例：

| 部门 | Copilot Enterprise | Gemini CA Ent | AWS Kiro |
|---|---|---|---|
| 研发 | **首选** ★★★ — IDE 覆盖最广 / 大仓库检索成熟。**风险**：核心资产混用 license 需 policy 隔离 | 备选 ★★ — Java/Spring/Cloud 集成强。**风险**：依赖 GCP 认证 | 不推荐 ★ — Preview 不稳，不适合长期大型项目 |
| 量化研究 | 备选 ★★ | 备选 ★★ | 不推荐 ★ |
| 算法（AI） | 备选 ★★ | **首选** ★★★ — 长上下文 + ML 生态 | 不推荐 ★ |
| ... | ... | ... | ... |

3 颗星 = 首选；2 颗 = 备选；1 颗 = 不推荐。每格 ~50 字关键理由 + 风险。

- [ ] **Step 2: 写矩阵下方的"读图说明"（~150 字）**

强调：
- 矩阵是第 1 章和第 3 章的视觉收束，不替代正文
- 评级是"在该部门场景下的相对适配度"，不代表工具自身的"绝对能力强弱"
- 风险栏的红线条目对应第 7 章合规约束

- [ ] **Step 3: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 4 章 — 部门 × 工具适配矩阵"
```

---

## Task 12: 第 5 章 推荐组合方案（B 方案落地）

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换第 5 章占位）

- [ ] **Step 1: 写"推荐组合"段（~400 字）**

按 B 方案给出 2~3 款工具的具体组合。组合候选示例：

> **组合 A（推荐）**：Copilot Enterprise（主力）+ Gemini Code Assist Enterprise（算法 / AI 部门专用 + 长上下文场景） + Kiro（试点池，限研发部门 spec-driven 项目）。
>
> **组合 B（备选）**：Gemini Code Assist Enterprise（主力）+ Copilot Enterprise（前端 / Web 部门 + JetBrains 用户专用）。
>
> **组合 C（保守）**：仅 Copilot Enterprise，全公司一刀切。损失算法部门长上下文优势，但治理最简单。

- [ ] **Step 2: 写"部门到工具的映射表"**

| 部门 | 主力工具 | 备选 |
|---|---|---|
| 研发（含 CI/CD） | Copilot Ent | Gemini CA Ent |
| 量化研究 | （视红线决定，可能任何一款都不进策略代码场景） | — |
| 算法（AI） | Gemini CA Ent | Copilot Ent |
| EST | Copilot Ent（仅辅助脚本） | — |
| PWM | Gemini CA Std（轻量场景） | Copilot Ent |
| 产品经理 / PoC | Gemini CA Std | Copilot Ent |
| 业务通用 | Gemini CA Std | — |
| 运营 | Gemini CA Std | — |

具体映射等 Task 10 的 § 1 推荐结论确定后再回填。

- [ ] **Step 3: 写"License 池粗估"段（~200 字）**

不报具体单价（采购另谈），只给数量级建议：
- 研发 N 人 → N 个 Copilot Ent 座位
- 算法 M 人 → M 个 Gemini CA Ent 座位
- 业务 / PoC / 运营 → 池化 K 个 Gemini CA Std 座位（自助申请）

明确 "EST / 量化 部门座位是否纳入待法务核对核心资产红线后再决定"。

- [ ] **Step 4: 写"与 Claude Code / Codex 类基准的关系"段（~250 字）**

明确表达：
- 当前候选三款都是商业 SaaS，受企业规划 / 采购 / 地区限制
- Claude Code / Codex 是当前 agentic 标杆，但暂不在采购考虑
- 建议每 6 个月做一次"基准复审"，关注 MCP 生态在三款候选中的演进
- 长期视角：如果 Claude Code 类工具未来进入采购可能，且 MCP 生态足够成熟，可考虑接入公司内部 RAG（如 ragent 项目）形成"自建 + 商业"混合方案

- [ ] **Step 5: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 5 章 — 推荐组合方案 B 落地"
```

---

## Task 13: 第 6 章 风险、迁移成本、落地路线图

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换第 6 章占位）

- [ ] **Step 1: 写风险表（4 类）**

| 风险类别 | 描述 | 缓解措施 |
|---|---|---|
| 合规风险 | 数据出境 / 核心资产泄露 / 监管处罚 | 第 7 章红线规则 + 法务核对 |
| 锁定风险 | license 与厂商深度绑定（Copilot 与 GitHub 生态、Gemini 与 GCP 认证、Kiro 与 AWS） | 退出策略（见 Step 3） |
| 成熟度风险 | Kiro 仍在 Preview，能力 / 区域 / 定价频繁变 | 限制 Kiro 用于试点池，主力部门不依赖 |
| 数据风险 | Prompt 上传厂商服务器即使不训练也存第三方暴露面 | 红线规则 + 各部门"不输入哪些内容"清单 |

- [ ] **Step 2: 写三阶段落地路线图（~400 字）**

不写硬时间线（不承诺日期），只写阶段顺序与门槛：

```markdown
**阶段 1：试点（建议 4~6 周）**
- 选 1~2 个部门做小规模试点（建议研发 + 算法各 10~20 人）
- 完成法务核对（spec § 5.4 开放清单）
- 验证 SSO / SCIM / 审计日志接入

**阶段 2：推广（建议 8~12 周）**
- 试点通过 → 扩展到所有研发 + 算法部门
- 业务 / PoC / 运营部门池化 license 自助申请
- EST / PWM 启动法务专项核对核心资产红线

**阶段 3：全量（建议 12 周后）**
- 全部门覆盖
- Kiro 试点池保持但不扩张
- 启动半年复审机制
```

- [ ] **Step 3: 写退出策略（~200 字）**

每款工具的"如果不达预期如何切换"：
- Copilot 退出：账号 + 配置可切换到 Gemini CA Ent，迁移成本主要在 SSO / SCIM 重新对接
- Gemini 退出：同上
- Kiro 退出：因仅试点 / 限研发部门 / 不存关键资产，退出成本最低

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(coding-assistant): 第 6 章 — 风险表 + 三阶段路线图 + 退出策略"
```

---

## Task 14: 第 0 章 TL;DR + README 入口

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`（替换第 0 章占位）
- Modify: `docs/coding-assistant-evaluation/README.md`

> TL;DR 写在最后，因为它是对全文的精确摘要，太早写会和后面正文不一致。

- [ ] **Step 1: 写第 0 章 TL;DR（~1 页）**

固定 4 段：

1. **一句话结论**（~50 字）：例如 "建议采用 Copilot Enterprise 主力 + Gemini Code Assist Enterprise 算法专用 + Kiro 试点的 B 方案组合"。
2. **部门 × 工具推荐矩阵速览**（一张精简版表，从第 4 章拷贝头部）
3. **三款工具一句话定位**：
   - Copilot Enterprise — IDE 覆盖最广 / 大仓库检索成熟 / GitHub 生态绑定
   - Gemini Code Assist Enterprise — 长上下文优势 / 算法 ML 场景适配 / GCP 集成
   - AWS Kiro — Spec-driven 流程差异化 / 原生 MCP / Preview 阶段成熟度需警示
4. **关键风险红线**（3 条）：
   - 大陆访问需走代理，三款均境外
   - 量化策略代码 / EST 客户数据 = 核心资产，规避走 SaaS prompt
   - Kiro 限试点池，不作主力

- [ ] **Step 2: 写 README.md 入口（~150 字）**

```markdown
# 代码助手企业选型分析

> 截至 2026-04-29

## 速读
- **决策结论**：……（从第 0 章 TL;DR 拷一句）
- **报告文件**：[evaluation-report.md](2026-04-29-evaluation-report.md)
- **附录**：
  - [评测方法 methodology.md](appendix/methodology.md)
  - [厂商条款 vendor-terms.md](appendix/vendor-terms.md)
  - [Benchmark 引用 benchmarks.md](appendix/benchmarks.md)
- **设计稿（spec）**：[../superpowers/specs/2026-04-29-coding-assistant-evaluation-design.md](../superpowers/specs/2026-04-29-coding-assistant-evaluation-design.md)
- **实施计划（plan）**：[../superpowers/plans/2026-04-29-coding-assistant-evaluation.md](../superpowers/plans/2026-04-29-coding-assistant-evaluation.md)

## 阅读路径
- **技术管理层 / 产品经理快读路径**：第 0 章 → 第 1 章 → 第 4 章 → 第 5 章
- **技术深度路径**：第 2 章 → 第 3 章（详写 3.1 / 3.4 / 3.6）→ 第 7 章
- **采购 / 合规对账路径**：第 7 章 → 附录 vendor-terms

## 时效性
三款工具均快速迭代，半年内复审。
```

- [ ] **Step 3: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md docs/coding-assistant-evaluation/README.md
git commit -m "docs(coding-assistant): 第 0 章 TL;DR + README 入口收口"
```

---

## Task 15: 终审 — 跨链接 / Spec 覆盖 / 一致性

**Files:** （只做检查与 nit 修订，不预期大量改动）

- [ ] **Step 1: Spec 覆盖检查**

打开 spec 文件 [docs/superpowers/specs/2026-04-29-coding-assistant-evaluation-design.md](../specs/2026-04-29-coding-assistant-evaluation-design.md)，逐节核对：

- [ ] § 1 背景与决策目标 → 反映在第 0 章 TL;DR + 第 5 章 ✓
- [ ] § 2 候选与基准 → 第 2 章工具速览 + 三处基准对照 ✓
- [ ] § 3 部门画像 → 第 1 章 8 个部门 ✓
- [ ] § 4 评测维度 → 第 3 章 6 个子节，详略符合 4.1 ✓
- [ ] § 5 合规约束 → 第 7 章 + 附录 vendor-terms ✓
- [ ] § 7 文件结构 → 5 个文件齐 ✓
- [ ] § 8 评测方法 → 附录 methodology ✓
- [ ] § 10 风险与不确定性 → 第 6 章 + AWS Kiro 成熟度警示 ✓

任何不达标的项，回到对应 Task 补写。

- [ ] **Step 2: 占位与红旗扫描**

```bash
grep -rn "TBD\|TODO\|XXX\|FIXME" docs/coding-assistant-evaluation/
```

预期输出：空。如有残留，修正。

```bash
grep -rn "<!-- TBD" docs/coding-assistant-evaluation/
```

预期输出：空。

- [ ] **Step 3: 类型 / 名称一致性扫描**

检查易出错的命名：
- "Copilot Enterprise" vs "GitHub Copilot Enterprise" — 全文统一为后者首次出现，简称用前者
- "Gemini Code Assist" vs "Gemini CA" — 全文统一为前者首次出现，简称用后者
- "Kiro" vs "AWS Kiro" — 全文统一为后者首次出现，简称用前者
- 三档评级 "强 / 一般 / 弱" — 全文一致，不混用 "高 / 中 / 低"

```bash
grep -n "Copilot Enterprise\|GitHub Copilot" docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
grep -n "Gemini Code Assist\|Gemini CA" docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
grep -n "AWS Kiro\|Kiro" docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
```

逐处检查首次出现是否用全称。

- [ ] **Step 4: 跨章节引用检查**

确认下面这些引用都正确指向：
- 第 1 章每个部门的"风险红线"是否引到第 7 章 § 5.3
- 第 2 章工具速览的"大陆可用性"是否引到第 7 章 § 5.1
- 第 3 章每节的"对部门选型的影响"是否反映在第 1 章对应部门
- 附录索引链接是否全部可点击

- [ ] **Step 5: 字数与页数确认**

```bash
wc -l docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
wc -m docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
```

预期：主文档 ~600~800 行 / ~30000~40000 字符。低于 ~25000 字符说明深度章节写得太薄，需回到 Task 7/8/9 补充。

- [ ] **Step 6: 终审 commit**

如有 nit 修订：

```bash
git add docs/coding-assistant-evaluation/
git commit -m "docs(coding-assistant): 终审 — 跨链接 / 命名一致性 / spec 覆盖核对"
```

如无修订，跳过 commit。

- [ ] **Step 7: 最终确认**

- [ ] 5 个文件齐
- [ ] 主文档 9 个一级章节齐
- [ ] 附录三件套齐
- [ ] README 入口齐
- [ ] 无 TBD / TODO 残留
- [ ] Spec 全部章节覆盖
- [ ] 命名一致

通知用户：报告完成，列出 5 个文件路径，建议用户人工通读后再决定是否生成 Slides（另起任务）。

---

## 备注：Slides 后续（不在本计划范围）

主文档定稿后，单独一个 plan 用 `html-ppt` skill 生成 Slides：

```
docs/presentations/2026-04-29-coding-assistant-eval/
```

抽取主文档的 0/1/4/5 章 ~ 15~20 张。Slides 计划另起。
