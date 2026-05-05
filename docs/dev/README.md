# docs/dev — 开发者文档索引

本目录所有开发者文档的一页地图。按类型分组；每类下的文件按日期倒序或字母序排。

> 新写文档时先看看归哪类；找不到合适子目录再新建。

---

## 🧭 入口级（常驻查看）

| 文件 | 用途 |
|------|------|
| [`entry-points.md`](entry-points.md) | 场景化导航："我想做 X，从哪儿开始" |
| [`gotchas.md`](gotchas.md) | 历史坑点 + 修复规则（按后端/DB/安全/向量/MQ/前端/运维分组） |

---

## 🏛️ `arch/` — 架构文档

稳定的架构视图（分层、模块、数据流），改动业务前先读。

| 文件 | 用途 |
|------|------|
| [`arch/overview.md`](arch/overview.md) | 架构总览 + 文档导航 |
| [`arch/bootstrap.md`](arch/bootstrap.md) | bootstrap 业务域分层 |
| [`arch/frontend.md`](arch/frontend.md) | 前端架构 |
| [`arch/infra-ai.md`](arch/infra-ai.md) | LLM/Embedding/Rerank 抽象 |
| [`arch/infrastructure.md`](arch/infrastructure.md) | PG/Redis/MQ/向量库/RustFS 拓扑 |
| [`arch/code-map.md`](arch/code-map.md) | bootstrap 各业务域目录树 + 关键类 |
| [`arch/business-flows.md`](arch/business-flows.md) | 5 大核心业务链路端到端（RAG / KB / 文档入库 / 后台 / RBAC） |

---

## 📐 `design/` — 设计文档

按日期前缀排序的实施计划 / 设计规格。

| 文件 | 主题 |
|------|------|
| [`design/rag-permission-design.md`](design/rag-permission-design.md) | 权限体系奠基（非日期） |
| [`design/rbac-and-security-level-implementation.md`](design/rbac-and-security-level-implementation.md) | RBAC + security_level 落地路线（非日期） |
| [`design/2026-04-09-knowledge-spaces-entry-page-plan.md`](design/2026-04-09-knowledge-spaces-entry-page-plan.md) | Knowledge Spaces 入口页计划 |
| [`design/2026-04-13-rbac-permission-fixes-design.md`](design/2026-04-13-rbac-permission-fixes-design.md) | RBAC v4 补全 |
| [`design/2026-04-19-access-center-redesign.md`](design/2026-04-19-access-center-redesign.md) | 权限中心 4-Tab 重构（P0-P2） |
| [`design/2026-04-19-kb-delete-cascade-plan.md`](design/2026-04-19-kb-delete-cascade-plan.md) | KB 删除级联回收 |
| [`design/2026-04-20-access-center-followup-p3.md`](design/2026-04-20-access-center-followup-p3.md) | 权限中心 P3 后续跟进 |
| [`design/2026-05-04-agentscope-agentic-poc-design.md`](design/2026-05-04-agentscope-agentic-poc-design.md) | Agentic 化 PoC（AgentScope-java + Plan-and-Execute，新增 `/agent` 链路） |

---

## 🚀 `setup/` — 启动 / 部署指南

| 文件 | 用途 |
|------|------|
| [`setup/launch.md`](setup/launch.md) | 全环境 Docker + DB 初始化 + 前后端启动 |
| [`setup/quick-setup-knowledge-spaces.md`](setup/quick-setup-knowledge-spaces.md) | Knowledge Spaces 功能快速部署 |

---

## 🧾 `followup/` — 技术债 / 跟进项

| 文件 | 类型 |
|------|------|
| [`followup/backlog.md`](followup/backlog.md) | 通用 backlog（2026-04-14 `/simplify` 积累） |
| [`followup/architecture-backlog.md`](followup/architecture-backlog.md) | 架构审查 backlog |
| [`followup/intent-tree-rbac-multitenancy.md`](followup/intent-tree-rbac-multitenancy.md) | 专题：意图树多租户 |
| [`followup/todo-excel-structured-ingestion.md`](followup/todo-excel-structured-ingestion.md) | 待办：Excel 结构化入库 |
| [`followup/2026-04-17-ops-pilot-one-month-plan.md`](followup/2026-04-17-ops-pilot-one-month-plan.md) | OPS 部门一个月试点计划 |
| [`followup/2026-04-18-rbac-refactor-retrospective.md`](followup/2026-04-18-rbac-refactor-retrospective.md) | RBAC 重构复盘 |
| [`followup/reviews/`](followup/reviews/) | 多角色评审（CTO / 架构师 / IT 经理 / PM） |

---

## ✅ `verification/` — 验收 / Demo 产物

| 文件 | 用途 |
|------|------|
| [`verification/pr3-demo-walkthrough.md`](verification/pr3-demo-walkthrough.md) | PR3 手动 UI 验收 12 步 |
| [`verification/pr3-curl-matrix.http`](verification/pr3-curl-matrix.http) | PR3 curl 授权矩阵 |
| [`verification/pr3-verification-log.md`](verification/pr3-verification-log.md) | PR3 验收日志 |

---

## 🔐 `security/` — 安全基线

| 文件 | 用途 |
|------|------|
| [`security/2026-04-18-authorization-baseline.md`](security/2026-04-18-authorization-baseline.md) | 端点级授权矩阵基线 |

---

## 🔬 `research/` — 研究 / 对比

| 文件 | 用途 |
|------|------|
| [`research/weknora_vs_myrag.md`](research/weknora_vs_myrag.md) | RAG 链路对比（WeKnora vs 本仓库） |

---

## 📖 其他相关位置

这些不在 `docs/dev/` 下，但经常配合查阅：

| 位置 | 用途 |
|------|------|
| `log/dev_log/dev_log.md` + `log/dev_log/YYYY-MM-DD-*.md` | 按日期的 PR/开发日志（已合并的真实变更记录） |
| `docs/superpowers/plans/` + `docs/superpowers/specs/` | 历史大功能的 superpowers 执行计划（冻结，仅作审计参考） |
| `CLAUDE.md`（根 + 各模块） | 面向 Claude Code 的项目指引，重要内容分散指向本目录 |

---

## 新增文档指南

1. **按类型入目录**：设计 → `design/`；跟进 → `followup/`；验收 → `verification/`；启动 → `setup/`。
2. **日期前缀**：设计文档和有时效的跟进项用 `YYYY-MM-DD-` 前缀（如 `2026-04-19-access-center-redesign.md`）。奠基性、长生命周期的文档可无日期。
3. **新建子类目录要慎重**：如果某类只有 1-2 个文件，先留在父目录；超过 3 个再独立子目录（如 `followup/reviews/`）。
4. **回头更新本 README**：新文件落库后务必在对应段落加一行。
