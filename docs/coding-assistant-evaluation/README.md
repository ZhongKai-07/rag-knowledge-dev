# 代码助手企业选型分析

> **截至**：2026-04-29
> **目标读者**：技术管理层 / 产品经理
> **决策方案**：B（按部门组合 2~3 款工具）

## 速读

- **决策结论**：建议采用"组合 A" — Copilot Enterprise（研发 / 量化辅助 / 算法备选）+ Gemini Code Assist Standard（EST / PWM / 业务 / PM / 运营）+ Gemini Code Assist Enterprise（算法）+ AWS Kiro（限研发 spec-driven 试点池，5~10 席位，3 个月评估）

## 文件导航

### 主报告
- [evaluation-report.md](2026-04-29-evaluation-report.md) — 主文档（第 0 章 TL;DR / 第 1 章部门画像 / 第 2 章工具速览 / 第 3 章核心能力 / 第 4 章适配矩阵 / 第 5 章推荐组合 / 第 6 章风险路线图 / 第 7 章合规约束）

### 附录
- [appendix/methodology.md](appendix/methodology.md) — 评测方法、评分口径、信息来源
- [appendix/vendor-terms.md](appendix/vendor-terms.md) — 厂商企业版条款摘录与原文链接
- [appendix/benchmarks.md](appendix/benchmarks.md) — Benchmark 引用与口径说明

### 设计与计划文档
- [设计稿（spec）](../superpowers/specs/2026-04-29-coding-assistant-evaluation-design.md)
- [实施计划（plan）](../superpowers/plans/2026-04-29-coding-assistant-evaluation.md)

## 阅读路径建议

- **技术管理层 / 产品经理快读路径（30 分钟）**：第 0 章 TL;DR → 第 1 章部门画像 → 第 4 章适配矩阵 → 第 5 章推荐组合 → 第 6 章风险路线图
- **技术深度路径**：第 2 章工具速览 → 第 3 章核心能力对比（含 Claude Code 基准对照）→ 第 7 章合规约束
- **采购 / 合规对账路径**：第 7 章 → appendix/vendor-terms.md → 第 6.3 退出策略

## 关键风险红线

1. **大陆访问需走代理**：三款均无大陆区，所有流量出境到海外厂商
2. **核心资产规避 SaaS prompt**：量化策略代码 / EST 客户订单 / PWM 客户画像不进 prompt
3. **Kiro 限试点池**：GA 仅 5 个月，不作主力

## 时效性

三款工具均为快速迭代产品（特别是 AWS Kiro 自 2025-11-17 GA 仅约 5 个月）。**建议每 6 个月复审**，关注：
- MCP 生态在三款候选中的演进
- AWS Kiro 的成熟度演进（GA 满 1 年时再评）
- Anthropic / OpenAI 是否进入中国大陆采购可能（如能，可考虑接入公司内部 RAG 形成自建+商业混合方案）
