# PR5 GitHub PR Body

> 本文件是给手动开 PR 时复制粘贴用的。开 PR 后可删除（不入仓）。
>
> **打开 PR 链接**：https://github.com/ZhongKai-07/rag-knowledge-dev/pull/new/feature/permission-pr5-metadata-filter-hardening
>
> **Title**: `feat(security): PR5 — Metadata Filter Contract Hardening (路线图阶段 B 收尾)`
>
> **Base**: `main` ← **Head**: `feature/permission-pr5-metadata-filter-hardening`

---

## Summary

- **路线图位置**：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B 收尾（PR4 已合并 → 本 PR 完成 OpenSearch retrieval `kb_id` + `security_level` filter 多层契约硬化，阶段 B 全部 close）
- **零业务增量**：纯 contract hardening，不改产品语义、不增减 API
- **9 个 commit**：c0 docs / c1 builder 强化 / c2 retriever fail-fast / c3 三通道 alignment 测试 / c4 grep 守门 + ArchUnit + smoke 文档 / c5 SearchContext.metadata cleanup / 2 个 review fixup / finalize（channel rethrow + roadmap §1）

## 4 条 Query Shape Invariants

- **QSI-1** `kb_id IN [kbId]` 由 `DefaultMetadataFilterBuilder` 永远输出（singleton list 在 OpenSearch 端渲染为 `terms(metadata.kb_id, [kbId])`）
- **QSI-2** `security_level LTE_OR_MISSING level` 由同一 builder 永远输出；`AccessScope.All` / 缺 entry 时 fallback 到 `Integer.MAX_VALUE` no-op range，让"DSL 始终含两条 filter"成为绝对契约
- **QSI-3** `OpenSearchRetrieverService.doSearch` fail-fast 守卫 + 显式 `catch (IllegalStateException) { throw e; }` 防御；finalize 把 rethrow 推到三通道 fan-out 路径，QSI-3 真正传播到 SSE
- **QSI-4** 通道 per-KB 调用与 builder 输出 `kb_id` filter 严格对齐（三通道 alignment 测试覆盖 `MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` / `IntentParallelRetriever`）

## 多层防御

| 层 | 实现 |
|---|---|
| Builder 行为 | `DefaultMetadataFilterBuilder` 永远输出两条 filter |
| Builder 单测 (Layer 1) | `DefaultMetadataFilterBuilderTest` 6 用例 |
| Retriever 守卫 (Layer 2) | `OpenSearchRetrieverService.enforceFilterContract` + `OpenSearchRetrieverServiceFilterContractTest` 8 用例 |
| 通道对齐 (Layer 3) | `MultiChannelRetrievalEngineFilterAlignmentTest` / `VectorGlobalChannelFilterAlignmentTest` / `IntentParallelRetrieverFilterAlignmentTest` 6 用例 + 共享 `FilterAlignmentAssertions` helper |
| Fan-out 传播 | `ChannelFailFastPropagationTest` 3 用例锁住三通道 IllegalStateException 不被吞 |
| ArchUnit | `MetadataFilterConstructionArchTest` 禁止 `..rag.core.retrieve..` 内非 `DefaultMetadataFilterBuilder` 构造 `MetadataFilter`（FQN 锁定） |
| Grep 守门 | `permission-pr5-filter-contract.sh` 4 道 gate（G1 白名单 / G2 enforceFilterContract 存在 / G3 doSearch 内顺序正确 / G4 catch IllegalStateException rethrow） |
| Smoke 文档 | `permission-pr5-smoke.md` 4 条手工路径（S5 普通 / **S5b 发布阻塞项** / S6 系统态 / S7 绕过攻击） |

## ⚠️ 部署阻塞项 — S5b 必须执行

**c1 加的 `terms(metadata.kb_id, [kbId])` 会让 OpenSearch 历史数据中 `metadata.kb_id` 字段缺失的 chunk 全部不可见**。每个环境（dev / staging / prod）的每个 KB 索引部署前都必须跑：

```bash
curl -s -X POST "$OS/<collection-name>/_count" -H 'Content-Type: application/json' -d '{
  "query": { "bool": { "must_not": [ { "exists": { "field": "metadata.kb_id" } } ] } }
}'
```

- **count == 0** → 通过，可部署
- **count > 0** → **PR5 部署阻塞**，必须先按 [`permission-pr5-smoke.md`](docs/dev/verification/permission-pr5-smoke.md) S5b 的 `_update_by_query` 脚本回填，再次核查 == 0 才能部署

## Test plan

- [x] `DefaultMetadataFilterBuilderTest` (Layer 1) — 6/6 ✅
- [x] `OpenSearchRetrieverServiceFilterContractTest` (Layer 2) — 8/8 ✅
- [x] `MultiChannelRetrievalEngineFilterAlignmentTest` / `VectorGlobalChannelFilterAlignmentTest` / `IntentParallelRetrieverFilterAlignmentTest` (Layer 3) — 6/6 ✅
- [x] `ChannelFailFastPropagationTest` (fan-out propagation) — 3/3 ✅
- [x] `MetadataFilterConstructionArchTest` (ArchUnit) — 1/1 ✅，负面测试验证 record canonical ctor 也能抓
- [x] `permission-pr5-filter-contract.sh` 4 道 gate ✅，负面测试（手动改代码挪 enforceFilterContract 进 try）也确认 G3 报错
- [x] 既有相关测试不回归：`OpenSearchRetrieverServiceTest` 3/3 / `VectorGlobalSearchChannelKbMetadataReaderTest` 4/4 / `SearchContextBuilderTest` 4/4 / `PermissionBoundaryArchTest` 6/6 / `KbAccessServiceRetirementArchTest` 3/3
- [x] PR3 / PR4 守门脚本不回归
- [x] `mvn spotless:check` 通过
- [ ] **部署前 S5b 历史数据核查**（每个环境逐 KB 跑，count == 0 才能部署）
- [ ] Staging 跑 S5 / S6 smoke 路径（DSL 含 `terms metadata.kb_id` + `range metadata.security_level`）

## Roadmap status update

`docs/dev/design/2026-04-26-permission-roadmap.md` §1：
- 阶段 B 已完成 — PR4 + PR5 c1-c5 全部落地
- 阶段 C / PR6 准备中 — `max_security_level` 粒度修正（管理决策路径仍用全局 `LoginUser.maxSecurityLevel` 的三个 caller 待评估保留 vs 迁移）

## 回滚预案

c1-c5 + finalize 可独立 revert：

- c1 revert → 退回 PR4 行为（builder 不输出新 filter）
- c2 revert → 退化为 PR4 行为（builder 仍输出但 retriever 不 fail-fast）
- c3-c4 revert → 移除测试 + gate（不影响生产代码）
- c5 revert → 复活 SearchContext.metadata 死字段（无害）
- finalize revert → 回到 fan-out catch 吞 IllegalStateException 的退化行为（仍有 ERROR log）

无需协调式回滚，直接 `git revert <commit>` 即可。

## 引用

- Spec: `docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md`
- Plan: `docs/superpowers/plans/2026-04-28-permission-pr5-metadata-filter-hardening.md`
- Roadmap: `docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B
