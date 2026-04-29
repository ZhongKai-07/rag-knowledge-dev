# 代码助手企业选型分析 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 写出一份 25~30 页的代码助手企业选型分析 Markdown 主文档（含附录），按 spec § 6.1 大纲，支撑技术管理层 + 产品经理做"按部门组合 2~3 款工具"的决策。

**Architecture:** 主文档 1 个 + 附录 3 个 + README 1 个，共 5 个 Markdown 文件。写作顺序按"信息依赖流"组织：基础事实（速览/附录）→ 详写章节 → 决策入口（部门推荐）→ TL;DR。每 task 完成一节并 commit。

**Tech Stack:** Markdown，无需代码工具链；信息源以厂商官方文档 + 公开 benchmark 为主。

**关键约束（来自 spec）：**
- 候选 3 款：GitHub Copilot Enterprise / Gemini Code Assist (Ent + Std) / AWS Kiro
- 基准对照 2 款（仅在 § 3.1/3.3/3.4/3.6 关键能力点点名）：Claude Code / Codex
- 8 个部门：研发（含 CI/CD）/ 量化研究 / 算法 AI / EST / PWM / 业务通用 / PM PoC / 运营
- 详写：A.Harness / D.上下文 / F.扩展生态；轻写：B.模型支持 / C.模型能力 / E.治理
- 评分口径：**强 / 一般 / 弱**，每条附"理由 + 来源"
- 合规瘦身：硬约束 1（大陆可用性）+ 硬约束 2（训练/留存）+ 说明 3（核心资产红线）+ 开放清单
- 时效性：文档末尾标"截至 2026-04-29"
- 中文为主，工具名/术语保留英文

---

## File Structure

| 路径 | 责任 | 创建顺序 |
|---|---|---|
| `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` | 主文档（第 0~7 章 + 第 8 章引用） | Task 1 起骨架，逐章填充 |
| `docs/coding-assistant-evaluation/appendix/methodology.md` | 评测方法、评分口径、信息来源优先级 | Task 2（先定，后续引用） |
| `docs/coding-assistant-evaluation/appendix/vendor-terms.md` | 三家企业版条款摘录与原文链接 | Task 3 |
| `docs/coding-assistant-evaluation/appendix/benchmarks.md` | SWE-bench / Terminal-bench / LiveCodeBench 引用与口径 | Task 4 |
| `docs/coding-assistant-evaluation/README.md` | 入口 + TL;DR 复制 | Task 16 |

---

## 写作通用原则（每个 task 都要遵守）

1. **章节骨架先列 H3/H4**，再填正文，避免重复返工。
2. **每个事实陈述必带 source**：厂商文档链接 / benchmark 发布日期 / "厂商声称（待实测确认）"标注。
3. **三款工具地位平等地谈**：不能漏 Kiro 不谈，也不能 Copilot/Gemini 各 3 段而 Kiro 只 1 段。
4. **基准对照只在 § 3.1 / 3.3 / 3.4 / 3.6 出现**，每处一段，不展开成完整章节。
5. **不打数字分**，只用"强 / 一般 / 弱"三档定性评级。
6. **章节末预留 1~2 句"对部门选型的影响"**，呼应第 1 章 / 第 4 章。
7. **不确定的能力点**显式标注 `（待实测确认）` 或 `（基于厂商声称）`，不伪装确定。

---

## Task 1: 创建目录结构 + 主文档骨架 + spec 引用

**Files:**
- Create: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`
- Create: `docs/coding-assistant-evaluation/appendix/` (空目录通过 .gitkeep 或后续 task 落地)

- [ ] **Step 1: 创建主文档骨架**

写入完整章节骨架（H1/H2/H3 占位 + spec 引用 + 时效性声明），不填正文。骨架内容：

```markdown
# 代码助手企业选型分析报告

> **截至日期**：2026-04-29
> **文档状态**：草稿
> **设计 spec**：[2026-04-29-coding-assistant-evaluation-design.md](../superpowers/specs/2026-04-29-coding-assistant-evaluation-design.md)
> **目标读者**：技术管理层 + 产品经理
> **决策框架**：B 方案（按部门组合 2~3 款工具）

---

## 第 0 章 TL;DR 与推荐总览

（待 Task 16 填充：一句话结论 + 部门 × 工具推荐矩阵 + 三款工具一句话定位 + 关键风险红线）

---

## 第 1 章 部门场景画像与推荐结论

（待 Task 14 填充：8 个部门 × 五栏，研发部门下含 CI/CD 子场景）

### 1.1 研发（含 CI/CD 子场景）
### 1.2 量化研究
### 1.3 算法（AI）
### 1.4 EST（Equities Sales & Trading）
### 1.5 PWM 产品数据分析
### 1.6 业务部门（通用）
### 1.7 产品经理 / PoC
### 1.8 运营

---

## 第 2 章 三款工具速览

（待 Task 5 填充：每款"能力名片"）

### 2.1 GitHub Copilot Enterprise
### 2.2 Gemini Code Assist (Enterprise / Standard)
### 2.3 AWS Kiro（Preview 阶段成熟度警示）

---

## 第 3 章 核心能力深度对比

### 3.1 Harness Engineering [详写 + Claude Code 基准]
### 3.2 模型支持与路由 [轻写]
### 3.3 模型 coding / agentic 能力 [轻写 + Codex 基准]
### 3.4 代码理解与上下文深度 [详写 + Claude Code 基准]
### 3.5 可靠性与企业治理 [轻写]
### 3.6 扩展性与生态 [详写 + Claude Code 基准]

---

## 第 4 章 部门 × 工具适配矩阵

（待 Task 13 填充：8 部门 × 3 工具大表）

---

## 第 5 章 推荐组合方案（B 方案落地）

（待 Task 15 填充）

---

## 第 6 章 风险、迁移成本、落地路线图

（待 Task 15 填充）

---

## 第 7 章 合规与可用性约束

（待 Task 6 填充）

---

## 第 8 章 附录

- [评测方法与口径](appendix/methodology.md)
- [厂商企业版条款摘录](appendix/vendor-terms.md)
- [Benchmark 引用与口径](appendix/benchmarks.md)
```

- [ ] **Step 2: 创建 appendix 目录**

```bash
mkdir -p docs/coding-assistant-evaluation/appendix
```

- [ ] **Step 3: Self-check**

- 文件已创建在正确路径
- 章节骨架与 spec § 6.1 完全对应（第 0~8 章）
- 时效性声明 + spec 引用链接已加
- 每章占位都标注了"待 Task N 填充"，方便后续追踪

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/
git commit -m "docs(eval): 主文档骨架 + appendix 目录"
```

---

## Task 2: 附录 — methodology.md 评测方法与口径

**Files:**
- Create: `docs/coding-assistant-evaluation/appendix/methodology.md`

**为什么先做这个**：评分口径、信息来源优先级、实测限制声明是后续所有章节的基线。先把"我们怎么评的"写清楚，避免后续章节口径漂移。

- [ ] **Step 1: 列出本附录的 H3 章节**

骨架：

```markdown
# 附录 A：评测方法与口径

## A.1 评分口径
- 三档定性评级：强 / 一般 / 弱
- 不打数字分的理由
- 每条评级需附"理由 + 来源"

## A.2 信息来源优先级
- 一手：厂商官方文档、企业版条款、定价页、产品博客、API/SDK 文档
- 二手：技术分析报告、独立评测、社区 benchmark（标日期）
- 基准对照：Claude Code / Codex 官方文档与公开材料

## A.3 实测限制
- 未做完整三家并行实测
- 接受的实测形式：Demo 视频、文档示例、有团队成员实际使用反馈
- 不确定的能力点显式标注「（待实测确认）」或「（基于厂商声称）」

## A.4 不确定性与时效性
- 文档落稿后 3~6 个月可能需修订
- 不预先承诺修订节奏
- 退出策略中说明"半年内复审"机制（详见主文档第 6 章）

## A.5 为什么不上 Excel 评分表
- 数字分容易被各部门拿去抠
- 三家企业版差异更适合质性表述
- 没有完全控制变量的实测条件，伪精确不诚实
```

- [ ] **Step 2: 填充正文**

每节 100~200 字，参考 spec § 4.2 + § 8 的措辞。

- [ ] **Step 3: Self-check**

- 5 节齐全（A.1~A.5）
- 强/一般/弱 三档评级口径明确
- 信息来源优先级清晰
- 实测限制诚实声明

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/appendix/methodology.md
git commit -m "docs(eval): 附录 - 评测方法与口径"
```

---

## Task 3: 附录 — vendor-terms.md 厂商条款摘录

**Files:**
- Create: `docs/coding-assistant-evaluation/appendix/vendor-terms.md`

**为什么先做这个**：第 5 章 § 5.2 数据训练/留存表 + 第 7 章合规章 都依赖这份摘录。先建立"事实底层"，后续章节直接引用。

- [ ] **Step 1: 列出本附录的 H3 章节**

```markdown
# 附录 B：厂商企业版条款摘录

> **重要**：条款会变。本附录采集时间为 2026-04-29，引用前请回访原文链接确认最新版本。

## B.1 GitHub Copilot Enterprise
- 数据训练承诺
- 数据留存策略
- SOC2 / ISO27001 报告获取方式
- 审计日志能力
- 原文链接

## B.2 Gemini Code Assist (Enterprise)
- 数据训练承诺
- 数据留存策略 / CMEK 支持
- 区域选择
- 审计日志能力
- 原文链接

## B.3 Gemini Code Assist (Standard)
- 与 Enterprise 的差异点（特别是数据使用条款）
- 是否可对企业用户禁用训练
- 原文链接

## B.4 AWS Kiro
- 当前 Preview 状态说明
- 数据训练 / 留存（如条款已发布）
- 区域可用性
- 原文链接

## B.5 三家对照表
| 维度 | Copilot Ent | Gemini CA Ent | Gemini CA Std | Kiro |
|---|---|---|---|---|
| 零训练 | | | | |
| 留存天数 | | | | |
| 客户管理密钥 | | | | |
| SOC2 | | | | |
```

- [ ] **Step 2: 数据收集**

逐家厂商访问官网企业版条款页，记录原文链接 + 摘录关键句子（带英文原文 + 中文释义）。优先来源：
- GitHub: docs.github.com/en/copilot/responsible-use-of-github-copilot-features 等
- Google: cloud.google.com/gemini/docs + Terms of Service
- AWS: aws.amazon.com/kiro 相关条款（注意 Preview 阶段条款可能有限）

每家至少 3 个权威链接。

- [ ] **Step 3: 填充正文**

把摘录填进 B.1~B.4，每家 200~300 字；B.5 对照表至少 5 行。

- [ ] **Step 4: Self-check**

- 三家 + Std 单列共 4 子节齐全
- 每家至少 3 个原文链接
- B.5 对照表无空格（不知道就写"未公开"或"未查到"，不留空）
- 提示读者条款会变 + 采集日期

- [ ] **Step 5: Commit**

```bash
git add docs/coding-assistant-evaluation/appendix/vendor-terms.md
git commit -m "docs(eval): 附录 - 厂商企业版条款摘录"
```

---

## Task 4: 附录 — benchmarks.md

**Files:**
- Create: `docs/coding-assistant-evaluation/appendix/benchmarks.md`

**为什么先做这个**：第 3.3 节模型 coding/agentic 能力依赖此附录的数据。

- [ ] **Step 1: 列出本附录的 H3 章节**

```markdown
# 附录 C：Benchmark 引用与口径

## C.1 SWE-bench Verified
- 测试集说明、规模、来源
- 当前榜单截图日期 2026-04-29
- 三款工具默认模型的成绩（如有）
- Claude Code（Sonnet 4.6 / Opus 4.7）的对照成绩
- Codex 的对照成绩
- 原文链接

## C.2 Terminal-bench
- 测试集说明
- 三款 / 基准对照成绩
- 原文链接

## C.3 LiveCodeBench
- 测试集说明
- 三款 / 基准对照成绩
- 原文链接

## C.4 Benchmark 局限性声明
- 公开 benchmark 与企业实战之间的 gap
- 模型版本 vs 工具版本的区别（同一模型在不同 harness 下表现差异巨大）
- 为什么本报告 § 3.3 引用 benchmark 但不作为决策唯一依据
```

- [ ] **Step 2: 数据收集**

记录每个 benchmark 的最新公开榜单数据（写入日期），重点关注三款工具默认模型成绩 + Claude Code / Codex 对照。

- [ ] **Step 3: 填充正文**

每节 150~250 字 + 数据表。

- [ ] **Step 4: Self-check**

- 3 个主要 benchmark + 1 节局限性声明齐全
- 每条数据带日期
- 显式说明"模型成绩 ≠ 工具成绩"

- [ ] **Step 5: Commit**

```bash
git add docs/coding-assistant-evaluation/appendix/benchmarks.md
git commit -m "docs(eval): 附录 - benchmark 引用与口径"
```

---

## Task 5: 第 2 章 — 三款工具速览

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 2 章

**依赖**：Task 3（vendor-terms）

- [ ] **Step 1: 三个子节骨架（每款一致）**

```markdown
### 2.x [工具名]

**厂商定位**：（一句话）
**产品形态**：IDE 插件 / 独立 IDE / CLI / Web
**核心卖点**：（3 个 bullet）
**大陆可用性**：（一句话，引用第 7 章硬约束 1）
**默认模型 + 可切换模型**：（清单）
**计费模式**：（每座 / 池 / token）
**~80 字总评**：（中性、无营销话术、点出最佳场景与短板）
```

AWS Kiro 多一栏：

```markdown
**Preview 阶段成熟度警示**：当前 Preview / 区域 / 能力变动风险 / 不建议生产关键路径
```

- [ ] **Step 2: 填充三款工具的能力名片**

参考 vendor-terms 附录 + 厂商官方产品页。每款 1~2 页（约 400~600 字）。

- [ ] **Step 3: Self-check**

- 三款都按同一模板填写
- 每款 80 字总评中性、无营销话术
- Kiro 有"Preview 警示"
- Gemini 双版本（Ent / Std）的差异在 2.2 内单独点出（一段即可）

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 2 章 三款工具速览"
```

---

## Task 6: 第 7 章 — 合规与可用性约束（瘦身版）

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 7 章

**依赖**：Task 3（vendor-terms）

- [ ] **Step 1: 子节骨架**

```markdown
### 7.1 硬约束 1：大陆可用性
（表：3 款 × 大陆企业版可用性 × 备注）
结论一句话。

### 7.2 硬约束 2：数据训练 / 留存
（引用 vendor-terms 附录的 B.5 对照表，本章只放结论 + 关键差异点 3~5 句）

### 7.3 说明 3：核心资产泄露红线
- Prompt 仍上传厂商服务器（即使不训练）
- 服务商被攻破 / 内鬼 / 司法调取的风险
- 红线规则：核心资产相关代码 / 数据避免走 SaaS

### 7.4 开放清单（待法务/合规进一步核对）
- 跨境数据传输（《数据出境安全评估办法》《个人信息保护法》）
- 证监会 / 交易所对 AI 工具的审计要求
- 等保 2.0 / 数据分级
- 出问题的责任界定 / 合同条款细节
- SOC2 / ISO27001 报告获取
```

- [ ] **Step 2: 填充正文**

按 spec § 5 措辞。本章约 1.5~2 页。

- [ ] **Step 3: Self-check**

- 3 条硬约束/说明 + 1 个开放清单齐全
- 7.1 表格三家齐全
- 7.4 至少 5 条开放项
- 红线规则一句话醒目可引用

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 7 章 合规与可用性约束"
```

---

## Task 7: 第 3.1 节 — Harness Engineering [详写 + Claude Code 基准]

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 3.1 节

**依赖**：Task 5（已写出三款定位）

- [ ] **Step 1: 子节骨架**

```markdown
### 3.1 Harness Engineering

#### 3.1.1 维度说明
（这条为什么重要：harness 决定 agent 能力上限，模型再强 harness 弱也跑不出来）

#### 3.1.2 子维度对照
- 系统提示与上下文构造
- 工具调用机制（tool use 设计、并行、错误恢复、循环防护）
- Plan mode / Spec-driven 流程支持（**Kiro 的 spec-driven 必谈**）
- Subagent / 多 agent 编排
- Memory（会话内 / 跨会话 / 项目级）
- 权限模型（auto-approve、敏感操作 gate）

每个子维度做"三家现状 + 强/一般/弱评级 + 理由"。

#### 3.1.3 ★ 基准对照：Claude Code
（一段，~200 字）
- Plan mode（EnterPlanMode）+ TodoWrite 任务编排
- Subagents（general-purpose / code-reviewer / Explore 等）
- Skills（user-invokable + auto-trigger）
- Memory（auto-memory 文件系统）
- Permission tiers（acceptEdits / plan / bypassPermissions）
- 这些能力是当前 agentic harness 的天花板参考

#### 3.1.4 对部门选型的影响
- 研发：harness 强 → 能跑大型重构 / Spec-driven，倾向 Kiro 的 spec-driven 或 Copilot 的 Workspace
- 量化 / 算法：plan mode + memory 重要（论文级长任务）
- EST / 业务：harness 复杂度反而是负担（需要"一句话出结果"的轻量形态）
```

- [ ] **Step 2: 填充正文**

约 2~3 页（800~1200 字）。基准对照段落明确"这是天花板参考，不是要求三款也达到"。

- [ ] **Step 3: Self-check**

- 6 个子维度都谈到三款
- 评级用强/一般/弱（不打数字分）
- Kiro 的 spec-driven 在"Plan mode / Spec-driven"子维度专门点出
- Claude Code 基准对照独立成段，~200 字
- 末段呼应部门影响

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 3.1 节 Harness Engineering"
```

---

## Task 8: 第 3.2 节 — 模型支持与路由 [轻写]

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 3.2 节

- [ ] **Step 1: 子节骨架**

```markdown
### 3.2 模型支持与路由 [轻]

#### 3.2.1 一张对照表
| 工具 | 默认模型 | 可切换模型 | 切换粒度 | BYOK | 更新节奏 |
|---|---|---|---|---|---|
| Copilot Enterprise | | | | | |
| Gemini CA Ent | | | | | |
| Gemini CA Std | | | | | |
| Kiro | | | | | |

#### 3.2.2 一段总评
（~150 字，谈三家"模型多样性 vs 模型一致性"的取舍 + 对企业灰度策略的影响）
```

- [ ] **Step 2: 填充正文**

约 0.5~1 页。轻写章节，不展开。

- [ ] **Step 3: Self-check**

- 表格 4 行齐全（含 Gemini Std 单独行）
- 每格非空（"未公开"或"不支持"代替留空）
- 总评谈"多样性 vs 一致性"取舍

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 3.2 节 模型支持与路由"
```

---

## Task 9: 第 3.3 节 — 模型 coding / agentic 能力 [轻写 + Codex 基准]

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 3.3 节

**依赖**：Task 4（benchmarks）

- [ ] **Step 1: 子节骨架**

```markdown
### 3.3 模型 coding / agentic 能力 [轻 + Codex 基准]

#### 3.3.1 公开 benchmark 速览
（引用附录 C 的数据，本节只放对照表 + 一段解读）

#### 3.3.2 实战体感（如有团队反馈）
（一段，标注"基于 X 部门 N 周使用反馈"或"暂无内部实测"）

#### 3.3.3 ★ 基准对照：Codex（CLI + Cloud）
（一段，~150 字，指出 Codex 的 agentic 任务完成率天花板，是三款工具未来的追赶目标）

#### 3.3.4 对部门选型的影响
（一段：模型成绩对量化 / 算法部门更敏感，对业务 / EST 影响小）
```

- [ ] **Step 2: 填充正文**

约 1 页。重点引用附录 C，本节不重复数据。

- [ ] **Step 3: Self-check**

- 引用附录 C 的数据，不重复
- 实战体感段诚实标注（有 / 没有内部反馈）
- Codex 基准对照独立成段

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 3.3 节 模型 coding/agentic 能力"
```

---

## Task 10: 第 3.4 节 — 代码理解与上下文深度 [详写 + Claude Code 基准]

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 3.4 节

- [ ] **Step 1: 子节骨架**

```markdown
### 3.4 代码理解与上下文深度 [详 + Claude Code 基准]

#### 3.4.1 维度说明
（为什么重要：大型项目能不能用、跨模块重构能不能稳）

#### 3.4.2 子维度对照
- 单仓索引规模上限
- 增量索引时延
- 跨仓 / 跨服务的上下文聚合
- 代码图（call graph / 依赖图）感知
- 长上下文实际可用率（声称 vs 实际）

每子维度三家 + 强/一般/弱评级。Copilot 的 indexing 模式 vs Gemini 的长上下文模式 vs Kiro 的 spec-context 模式 — 三家路线不同要点出。

#### 3.4.3 ★ 基准对照：Claude Code
（一段 ~200 字）
- Grep + Read + agent 自主探索式理解
- 不依赖预建索引（也无索引规模上限）
- 长上下文（1M）实际可用率高，缺点是首次理解大仓库需多轮探索
- "索引式" vs "探索式"的优劣对比

#### 3.4.4 对部门选型的影响
- 研发（大型项目）：索引能力是核心
- 量化 / 算法（论文级长 prompt）：长上下文胜过索引
- EST / 业务（一次性脚本）：上下文要求低
```

- [ ] **Step 2: 填充正文**

约 2~3 页。"索引式 vs 探索式"是本节最关键的概念分类。

- [ ] **Step 3: Self-check**

- 5 个子维度都谈到三款
- 三家路线差异（索引 / 长上下文 / spec-context）显式分类
- Claude Code 基准对照独立成段
- 末段呼应部门影响

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 3.4 节 代码理解与上下文深度"
```

---

## Task 11: 第 3.5 节 — 可靠性与企业治理 [轻写]

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 3.5 节

- [ ] **Step 1: 子节骨架**

```markdown
### 3.5 可靠性与企业治理 [轻]

#### 3.5.1 治理能力对照表
| 维度 | Copilot Ent | Gemini CA Ent | Kiro |
|---|---|---|---|
| SLA | | | |
| SSO / SCIM | | | |
| RBAC | | | |
| 审计日志 | | | |
| Policy / Guardrail | | | |
| 公开代码片段检测（IP 风险） | | | |

#### 3.5.2 一段总评
（~150 字，重点指出 Kiro 当前 Preview 在治理能力上的不足）

> 数据隐私 / 训练留存的合规细节见第 7 章 + 附录 B，本节不重复。
```

- [ ] **Step 2: 填充正文**

约 0.5~1 页。

- [ ] **Step 3: Self-check**

- 6 个治理维度齐全
- 与第 7 章 + 附录 B 交叉引用，不重复
- Kiro Preview 阶段的治理短板点出

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 3.5 节 可靠性与企业治理"
```

---

## Task 12: 第 3.6 节 — 扩展性与生态 [详写 + Claude Code 基准]

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 3.6 节

- [ ] **Step 1: 子节骨架**

```markdown
### 3.6 扩展性与生态 [详 + Claude Code 基准]

#### 3.6.1 维度说明
（为什么重要：能否接公司既有系统决定落地天花板）

#### 3.6.2 子维度对照
- IDE / CLI 覆盖度（VSCode / JetBrains / Vim / Web / 终端）
- **MCP 支持现状**（重点：三家是否原生支持 MCP？兼容程度？）
- Plugin / Extension / Skills 类机制
- 与公司既有系统集成（Jira / Confluence / 内部知识库 / 私有 git）

每子维度三家 + 强/一般/弱评级。

#### 3.6.3 ★ 基准对照：Claude Code 三层扩展模型
（一段 ~250 字）
- 第一层 MCP server（接外部工具 / 数据源）
- 第二层 Plugins（社区扩展，含 marketplace）
- 第三层 Skills（auto-trigger + user-invokable，含 brainstorming / writing-plans / executing-plans 等）
- 三层叠加形成"工具 → 工作流 → 方法论"的完整扩展栈

#### 3.6.4 对部门选型的影响
- 研发：MCP / plugins 决定能否接内部 RAG / 知识库 / Jira
- 全员：skills 类机制决定能否落"标准化操作流程"
```

- [ ] **Step 2: 填充正文**

约 2~3 页。MCP 支持现状是本节最关键问题。

- [ ] **Step 3: Self-check**

- 4 个子维度都谈到三款
- MCP 现状有明确结论（"原生支持 / 部分支持 / 不支持"）
- Claude Code 三层扩展模型独立成段
- 末段呼应部门影响

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 3.6 节 扩展性与生态"
```

---

## Task 13: 第 4 章 — 部门 × 工具适配矩阵

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 4 章

**依赖**：Task 7~12（第 3 章全部）

- [ ] **Step 1: 矩阵骨架**

```markdown
## 第 4 章 部门 × 工具适配矩阵

> 本章是第 1 章和第 3 章的视觉收束。每格三栏：推荐度（★~★★★）/ 关键理由（一句）/ 主要风险（一句）。

| 部门 | Copilot Ent | Gemini CA (Ent/Std) | AWS Kiro |
|---|---|---|---|
| 研发（含 CI/CD） | | | |
| 量化研究 | | | |
| 算法（AI） | | | |
| EST | | | |
| PWM 数据分析 | | | |
| 业务通用 | | | |
| 产品经理 / PoC | | | |
| 运营 | | | |

### 矩阵图例
- ★★★ 首选 / ★★ 次选 / ★ 不推荐
- 每格统一格式：「推荐度 \| 一句理由 \| 一句风险」

### 矩阵解读（一段）
（指出最显著的"工具与部门强匹配"3 处 + "明显错配"3 处）
```

- [ ] **Step 2: 填充矩阵**

每格按"★★★/★★/★ + 一句理由 + 一句风险"格式填写。所有评级要与第 3 章评级一致 / 第 1 章推荐一致（虽然第 1 章后写，但维持评级一致性可以从矩阵推回去）。

- [ ] **Step 3: Self-check**

- 24 个格子全填（8 部门 × 3 工具）
- 每格三栏齐全
- 评级图例放矩阵上方
- 解读段落指出 3 处强匹配 + 3 处错配

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 4 章 部门×工具适配矩阵"
```

---

## Task 14: 第 1 章 — 部门画像与推荐结论

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 1 章

**依赖**：Task 7~13（第 3+4 章已成稿，方便回填推荐理由）

**为什么放这么后面写**：第 1 章是决策入口，但写作上需要第 3+4 章的论据支撑。先做后置写作，再放回前置位置。

- [ ] **Step 1: 子节模板（每个部门用同一模板）**

```markdown
### 1.x [部门名]

**画像**
- 角色：（参考 spec § 3 表格）
- 核心场景：（同上）
- 关键诉求：（同上）

**首选工具：[X] ★★★**
- 理由 1：（呼应 § 3.x 评级）
- 理由 2：（呼应 § 3.x 评级）
- 理由 3：（呼应 § 3.x 评级）

**次选工具：[Y] ★★（适用条件：…）**

**不推荐：[Z] ★（原因：…）**

**风险红线**
- （核心资产 / 数据合规相关红线，不是所有部门都有）

**小贴士**
- 用法建议
- 与 Claude Code 基准能力对照的差距点（"如未来能用上 Claude Code，X 场景会显著提升"）
```

- [ ] **Step 2: 逐部门填充**

8 个部门各 ~0.5~1 页。研发部门下设 CI/CD 子场景（一段说明，不单独列推荐）。每部门约 300~500 字。

填充时严格保证：
- 推荐评级（★★★/★★/★）与 § 4 矩阵一致
- 理由引用 § 3 的对应章节
- 风险红线呼应 § 7

- [ ] **Step 3: Self-check**

- 8 个部门齐全
- 每部门 5 栏齐全
- 推荐评级与第 4 章矩阵一致（**逐格 cross-check**）
- 研发部门 CI/CD 子场景已说明

- [ ] **Step 4: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 1 章 部门画像与推荐结论"
```

---

## Task 15: 第 5 章 + 第 6 章 — 推荐组合 + 风险路线图

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 5+6 章

**依赖**：Task 14

- [ ] **Step 1: 第 5 章骨架**

```markdown
## 第 5 章 推荐组合方案（B 方案落地）

### 5.1 主力工具 + 备选工具
（基于第 4 章矩阵，定 1 主 1 备 1 长期评估）

### 5.2 部门分发
| 工具 | 主要服务部门 | 预估 license 量级 |
|---|---|---|

### 5.3 与 Claude Code / Codex 基准的关系
（一段：当前三款不能完全替代 Claude Code 的 skills/MCP/subagents 完整能力，标注哪些场景如能用上 Claude Code 类工具会显著提升，作为长期评估窗口）
```

- [ ] **Step 2: 第 6 章骨架**

```markdown
## 第 6 章 风险、迁移成本、落地路线图

### 6.1 风险表
| 风险 | 影响 | 缓解措施 |
|---|---|---|
| 合规风险（数据出境） | | |
| 厂商锁定 | | |
| Kiro 成熟度（Preview） | | |
| 数据泄露（核心资产） | | |
| 模型能力变化 | | |

### 6.2 三阶段路线图
- 阶段 1（试点）：1~2 部门，1~3 月
- 阶段 2（推广）：扩展至更多部门，3~6 月
- 阶段 3（全量 + 复审）：6 月+ 半年期复审

> 不强承诺具体日期，以"相对周期"表述。

### 6.3 退出策略
- 工具不达预期的判定标准
- 切换到备选工具的迁移路径
- License 池可逐步释放的合同条款（采购阶段需谈）
```

- [ ] **Step 3: 填充正文**

第 5 章约 1~1.5 页，第 6 章约 1.5~2 页。

- [ ] **Step 4: Self-check**

- 5.1 + 5.2 + 5.3 齐全；5.3 必须包含 Claude Code 基准的"长期评估窗口"一段
- 6.1 风险表 5 行齐全，每行有缓解措施
- 6.2 三阶段路线图无强承诺日期
- 6.3 退出策略包含三要素（判定标准 / 迁移路径 / 合同弹性）

- [ ] **Step 5: Commit**

```bash
git add docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
git commit -m "docs(eval): 第 5+6 章 推荐组合 + 风险路线图"
```

---

## Task 16: 第 0 章 TL;DR + README

**Files:**
- Modify: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md` 第 0 章
- Create: `docs/coding-assistant-evaluation/README.md`

**依赖**：Task 1~15（全部）

**为什么放最后**：TL;DR 是全文结论的提炼，必须等正文成稿才能写。

- [ ] **Step 1: 第 0 章骨架**

```markdown
## 第 0 章 TL;DR 与推荐总览

### 一句话结论
（一句话，约 50 字）

### 部门 × 工具推荐矩阵（精简版）
（复用第 4 章的精简版表格，去掉风险栏，仅留推荐度 + 一句理由）

### 三款工具一句话定位
- **GitHub Copilot Enterprise**：…
- **Gemini Code Assist**：…
- **AWS Kiro**：…

### 关键风险红线
1. 核心资产相关代码不进任何 SaaS prompt
2. AWS Kiro Preview 阶段不上生产关键路径
3. 合规细节待法务核对（详见第 7 章 § 7.4）
```

- [ ] **Step 2: 填充第 0 章**

约 1 页，每段都是结论，不要论据。

- [ ] **Step 3: 创建 README.md**

```markdown
# 代码助手企业选型分析

> 截至 2026-04-29

## 入口
- [完整报告](2026-04-29-evaluation-report.md)
- [评测方法](appendix/methodology.md)
- [厂商条款摘录](appendix/vendor-terms.md)
- [Benchmark 引用](appendix/benchmarks.md)

## TL;DR
（复用主文档第 0 章一句话结论 + 矩阵 + 三款定位 + 风险红线）

## 阅读建议
- 技术管理层：第 0 章 → 第 4 章 → 第 5+6 章
- 产品经理：第 0 章 → 第 1 章 → 第 4 章
- IT/合规：第 7 章 → 附录 B + C
- 各部门主管：第 1 章对应小节 → 第 4 章对应行
```

- [ ] **Step 4: Self-check**

- 第 0 章 4 部分齐全（结论 / 矩阵 / 定位 / 风险）
- README 包含 4 类读者的阅读路径
- README 的 TL;DR 与第 0 章一致（直接复制）

- [ ] **Step 5: Commit**

```bash
git add docs/coding-assistant-evaluation/
git commit -m "docs(eval): 第 0 章 TL;DR + README"
```

---

## Task 17: 全文通读 + 时效性最终确认 + 最终 commit

**Files:**
- Review/touch: `docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md`
- Review/touch: `docs/coding-assistant-evaluation/appendix/*.md`
- Review/touch: `docs/coding-assistant-evaluation/README.md`

- [ ] **Step 1: 全文通读自检清单**

通读全文，检查：

- [ ] 三款工具地位平等：每章每款的篇幅大致均衡，无明显厚此薄彼（除非有 Preview 警示等合理原因）
- [ ] 基准对照只在 § 3.1 / 3.3 / 3.4 / 3.6 出现，其他章节不混入
- [ ] 评分一致性：§ 1 推荐 ←→ § 4 矩阵 ←→ § 3 子维度评级 三处对得上
- [ ] 强/一般/弱 三档定性评级，无数字分
- [ ] 不确定的能力点都标注「（待实测确认）」或「（基于厂商声称）」
- [ ] 厂商引用都有 source 链接
- [ ] 所有 ~/约 数字（如"~80 字"）已替换为实际成稿
- [ ] 时效性声明在主文档头部 + README + 各附录头部都有"截至 2026-04-29"
- [ ] 中文为主、工具名/术语英文，未夹杂错误转换
- [ ] 核心资产红线在第 1 章对应部门 + 第 7.3 + 第 0 章风险红线 三处一致

- [ ] **Step 2: Markdown 渲染验证**

```bash
# 任意 Markdown 渲染器或 IDE 预览，检查表格 / 列表 / 代码块格式正确
```

- [ ] **Step 3: 字数 / 页数粗估**

```bash
wc -w docs/coding-assistant-evaluation/2026-04-29-evaluation-report.md
```

预期总字数 ~12000~15000 字（中文 1 页约 500 字，目标 25~30 页）。如远低于则补足，远高于则压缩冗余。

- [ ] **Step 4: 修复发现的问题**

任何不一致 / 占位 / source 缺失 / 错字，逐一修复。

- [ ] **Step 5: 最终 commit**

```bash
git add docs/coding-assistant-evaluation/
git commit -m "docs(eval): 全文通读自检 + 最终修订"
```

- [ ] **Step 6: （可选）开 PR**

如希望走 review，可推到分支并开 PR；本次任务不强制。

---

## Self-Review（plan 自检）

**Spec 覆盖检查**：

- [x] § 1 背景与决策目标 → Task 1（骨架）+ Task 16（TL;DR）
- [x] § 2 候选工具 + 基准 → Task 5（速览）+ Task 7/9/10/12（基准对照点）
- [x] § 3 部门画像（8 部门） → Task 14
- [x] § 4 评测维度（详 ADF 轻 BCE） → Task 7~12
- [x] § 5 合规瘦身 → Task 6（第 7 章）+ Task 3（vendor-terms 附录）
- [x] § 6 主文档章节结构 → Task 1（骨架）+ Task 5/6/7~12/13/14/15/16
- [x] § 7 文件结构 → Task 1 + Task 16
- [x] § 8 评测方法 → Task 2（methodology 附录）
- [x] § 9 目标读者 → Task 16（README 阅读建议）
- [x] § 10 风险与不确定性 → Task 6（第 7 章）+ Task 15（第 6 章）+ Task 17（自检）
- [x] § 11 后续步骤 → 本 plan 即步骤 2，执行后是步骤 3

**Placeholder scan**：所有 task 的 step 都给了具体骨架 / 子节清单 / self-check 清单，无 TBD/TODO。

**Type consistency**：评级口径"强 / 一般 / 弱"在 Task 2 / 7 / 10 / 12 中一致使用；矩阵图例"★★★/★★/★"在 Task 13 / 14 / 16 中一致使用。

**Scope check**：17 个 task 集中在一个 doc set 里，单 plan 合理。无跨子系统依赖。

---

## 执行说明

**写作任务的 TDD 适配**：本 plan 不是写代码，没有真正的"测试"。每 task 用 self-check 清单替代单元测试，确保章节齐全 + 口径一致 + source 完整。

**任务依赖图**：

```
Task 1 (骨架)
  ↓
Task 2 (methodology) ─┐
Task 3 (vendor-terms)─┼─→ Task 5 (速览), Task 6 (第7章合规)
Task 4 (benchmarks) ──┘                         ↓
                                       Task 7 (3.1) Task 8 (3.2)
                                       Task 9 (3.3) Task 10 (3.4)
                                       Task 11 (3.5) Task 12 (3.6)
                                                  ↓
                                            Task 13 (第4章矩阵)
                                                  ↓
                                            Task 14 (第1章部门画像)
                                                  ↓
                                            Task 15 (第5+6章)
                                                  ↓
                                            Task 16 (第0章 TL;DR + README)
                                                  ↓
                                            Task 17 (全文通读)
```

Task 7~12 之间无强依赖，可并行（如用 subagent-driven）。

**预估总工时**：每 task ~30~60 分钟（信息收集 + 写作 + 自检），合计 ~10~15 小时。
