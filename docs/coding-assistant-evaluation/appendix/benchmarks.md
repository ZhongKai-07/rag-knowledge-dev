# Benchmark 引用与口径说明

> 截至 2026-04-29

## 1. SWE-bench Verified

SWE-bench Verified 是衡量 LLM 解决真实 GitHub Issue 能力的标准 benchmark，使用经过人工验证的测试用例子集，要求模型给出可通过单元测试的代码修改。

| 排名 | 模型 | 成绩 | 备注 |
|------|------|------|------|
| 1 | Claude Mythos Preview（Anthropic） | 93.9% | — |
| 2 | Claude Opus 4.7 Adaptive（Anthropic） | 87.6% | — |
| 3 | GPT-5.3 Codex（OpenAI） | 85.0% | — |
| 4 | Claude Opus 4.5（Anthropic） | 80.9% | SWE-bench Pro 仅 45.9%，存在数据污染风险 |
| 5 | Claude Opus 4.6（Anthropic） | 80.8% | — |
| 8 | GPT-5.2（OpenAI） | 80.0% | — |
| 9 | Claude Sonnet 4.6（Anthropic） | 79.6% | — |
| 19 | Claude Sonnet 4.5（Anthropic） | 77.2% | Kiro 当前底层模型之一 |
| 39 | Gemini 2.5 Pro（Google） | 63.8% | Gemini Code Assist 当前底层模型 |

**重要说明**：
- OpenAI 在 2026 年初停止上报 SWE-bench Verified 成绩，转而推荐使用 SWE-bench Pro（使用标准化脚手架、有训练数据泄露保护）。
- Gemini 3.x 系列未出现在 SWE-bench Verified 榜单。
- Gemini 最新入榜模型为 Gemini 2.5 Pro（63.8%）。

来源：https://benchlm.ai/benchmarks/sweVerified（数据更新日期 2026-04-29）

---

## 2. Terminal-Bench 2.0

Terminal-Bench 2.0 测量 agent + 模型组合在终端任务中的成功率，包含 124 个 agent-model 组合，评估范围涵盖命令行工程任务的自主完成能力。

| 排名 | 模型 | Agent | 成绩 | 数据日期 |
|------|------|-------|------|----------|
| 1 | GPT-5.5（OpenAI） | Codex | 82.0% ±2.2 | 2026-04-23 |
| 2 | GPT-5.4（OpenAI） | ForgeCode | 81.8% ±2.0 | 2026-03-12 |
| 3 | Gemini 3.1 Pro（Google） | TongAgents | 80.2% ±2.6 | 2026-03-13 |
| 4 | Claude Opus 4.6（Anthropic） | ForgeCode | 79.8% ±1.6 | 2026-03-12 |
| 5 | Gemini 3.1 Pro（Google） | ForgeCode | 78.4% ±1.8 | 2026-03-02 |
| 6 | GPT-5.3-Codex（OpenAI） | SageAgent | 78.4% ±2.2 | 2026-03-13 |
| 7 | Claude Opus 4.6（Anthropic） | Capy | 75.3% ±2.4 | 2026-03-12 |
| 8 | Claude Opus 4.6（Anthropic） | Terminus-KIRA | 74.7% ±2.6 | 2026-02-22 |

**说明**：GPT-5.5 via Codex agent 当前排名第一（82.0%），与 Codex 作为 GitHub Copilot 可选外部 agent 的定位高度相关。

来源：https://tbench.ai/leaderboard/terminal-bench/2.0

---

## 3. LiveCodeBench

**信息缺口 — 需后续补查**（对应 `vendor-terms.md` 第 4 节缺口 #3）

LiveCodeBench 测量代码生成、测试输出预测等多维度编程能力，使用持续从 LeetCode、AtCoder、CodeForces 等平台收集的新题目，可有效规避训练数据污染问题。

**数据状态**：调研期间未能从官网（https://livecodebench.github.io/）获取最新排行表格数据（页面动态渲染，无法直接解析）。GPT-5、Gemini 3.x、Claude Opus 4.x 在 LiveCodeBench 的具体最新分数属于已识别的信息缺口，不在本报告评估范围内。

**已知基线（较旧数据，仅供参考）**：
- Claude 3.5 Sonnet 系列曾在 LiveCodeBench 取得较高成绩（具体数字待官方更新）
- LiveCodeBench 论文：https://arxiv.org/abs/2403.07974

**建议**：后续补查直接访问 https://livecodebench.github.io/leaderboard.html，关注 Claude Opus 4.x、GPT-5.x、Gemini 2.5 Pro 的最新成绩。

---

## 4. 口径说明

**Benchmark 评的是模型，不是产品**

上述 benchmark 数据衡量的是底层语言模型在标准化测试集上的能力，而非最终用户产品（GitHub Copilot / Gemini Code Assist / AWS Kiro）的实际体验。产品体验受 harness 设计（工具调用策略、上下文管理、重试逻辑）、IDE 集成质量、网络延迟等因素影响显著，同一底层模型在不同产品 harness 下的表现可能差异较大。

**跨 benchmark 横向比较需谨慎**

不同模型提交 benchmark 的时间不一致（数据更新日期从 2026-02 到 2026-04 不等），跨 benchmark 直接比较（SWE-bench vs Terminal-Bench vs LiveCodeBench）意义有限。

**Benchmark 不能反映金融行业特定场景**

SWE-bench 和 Terminal-Bench 使用的是通用开源代码库，无法覆盖金融行业特定场景，例如量化策略代码、VBA 宏、Bloomberg API 调用、KDB+/Q 语言等。此处 benchmark 数据仅作为"通用 coding 能力"的参考基线，不代表在金融代码库上的实际表现。

**三款候选工具底层模型映射关系（截至 2026-04-29）**

| 工具 | 底层模型 | 备注 |
|------|----------|------|
| GitHub Copilot Enterprise | GPT-4.1、GPT-5 mini（默认包含，不计 Credits）；可选切换至 GPT-5.2/5.3/5.4/5.5、Claude Haiku 4.5 / Sonnet 4–4.6 / Opus 4.5–4.7、Gemini 2.5 Pro / 3.1 Pro、Grok Code Fast 1 | 用户可在 IDE 内按 session 切换；Enterprise 可访问全部支持模型 |
| Gemini Code Assist（Standard / Enterprise） | Gemini 2.5 Pro | Agent Mode 支持最高 1M token 上下文；用户是否可切换模型版本未在公开材料中明确说明 |
| AWS Kiro | Claude Sonnet 4.5 + Claude Haiku 4.5（Auto 模式混合使用） | Auto 模式通过意图检测和缓存优化成本；Claude Sonnet 4.5 比 Auto 模式贵约 1.3 倍 credits |

来源：GitHub Copilot 模型列表 https://docs.github.com/en/enterprise-cloud@latest/copilot/reference/copilot-billing/models-and-pricing；Kiro 定价 https://kiro.dev/pricing/；Gemini Code Assist 概览 https://docs.cloud.google.com/gemini/docs/codeassist/overview
