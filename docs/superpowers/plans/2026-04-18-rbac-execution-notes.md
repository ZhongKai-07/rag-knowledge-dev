# RBAC 改造执行笔记

本文档承载 plan 文档 `2026-04-18-rbac-decoupling-authz-hardening.md` 执行过程中**发现但 plan 未记录**的事实、踩坑和 hand-off 模板。新会话接手后续 PR 时只读这两份文档即可，无需依赖前序对话记忆。

---

## 当前进度（持续更新）

| PR | 状态 | Commit | 备注 |
|---|---|---|---|
| PR-A 写路径注入 | ✅ | `df57b56` | 踩出 2 个 hotfix |
| PR-B step 0.7 清库重建 | ✅ 运维 | — | opscobtest2 已重建；opscobtest1 空（用户未手动触发）|
| Hotfix #1 | ✅ | `33c94cf` | `deleteDocumentVectors` 幂等 |
| Hotfix #2 | ✅ | `8a3caed` | CHUNK mode ensureVectorSpace |
| PR-C framework port | ✅ | `1bb066a` | 9 接口纯新增 |
| PR-D SuperAdminMutationIntent 迁移 | ✅ | `8451caa` | 4 处 import 切换 + 删老文件 |
| PR-E KbMetadataReader 实现 + KbAccessServiceImpl 去 Mapper | ✅ | `2191ee5` | +153/-43 |
| PR-F KbAccessServiceImpl implements 新端口 | ⏳ | | |
| PR-G MetadataFilterBuilder 抽 bean | ⏳ | | |
| PR-H AccessScope 贯通 | ⏳ | | |
| PR-I RetrievedChunk 扩字段 | ⏳ | | |
| PR-J AuthzPostProcessor + 配置校验 | ⏳ | | |

---

## PR-A 执行中发现的 plan 缺口

1. **7 处调用点而非 6 处**：plan 漏了 `KnowledgeDocumentServiceImpl.java:705` 文档 toggle-enable 重建向量的路径
2. **`syncChunkToVector` 有 2 个调用者**（`create` + 单 chunk `enableChunk`），plan 只提 1 个
3. **Step 0.5a 是空操作**：`VectorChunk.java:58` 已有 `@Builder.Default private Map<String, Object> metadata = new HashMap<>()` 字段
4. **Spotless apply 在每次 `mvn compile` 触发**：CLAUDE.md 已有 gotcha，本次未引发无关文件 drift（10 个 Java 都是本轮改动）

## 新发现的 hotfix 必需

### Hotfix #1: `deleteDocumentVectors` 幂等

**触发场景**：手工清 OS 索引后重跑 CHUNK mode ingestion

**根因**：`OpenSearchVectorStoreService.deleteDocumentVectors` 原实现对 `index_not_found_exception` 直接抛 `RuntimeException`，吞掉 OpenSearch 的 404 异常类型。事务内 delete 失败 → 整个 `persistChunksAndVectorsAtomically` 回滚 → document.status=FAILED。

**修复**：catch 异常链里含 `index_not_found` 时视为删除成功（no-op）。见 `OpenSearchVectorStoreService.isIndexNotFound()`。

### Hotfix #2: CHUNK mode 必须 `ensureVectorSpace`

**触发场景**：KB 创建后外部删索引；或 chunk 模式首次写入但索引被删

**根因**：`ensureVectorSpace`（应用声明 mapping）只在 2 处调用：
- `IndexerNode.execute` — 仅 **PIPELINE mode**
- `KnowledgeBaseServiceImpl.create` — KB 创建时

CHUNK mode 分块流程 `persistChunksAndVectorsAtomically` 从未调用，导致索引若丢失，写入时 OpenSearch auto-create + dynamic mapping，`kb_id` 会是 `text` 而非 `keyword`。

**修复**：`KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically` 入口注入 `VectorStoreAdmin`，事务外调用 `ensureVectorSpace`（idempotent 的 HEAD+PUT）。

### 两个 hotfix 的共性教训

- **操作索引生命周期 = 运维契约**，代码侧必须 fail-safe：缺失索引 delete 无事可删、写入前确保存在 schema
- 任何"索引可能消失"的 edge case 都要过一遍：未来新加的 VectorStoreService 方法也应照此加固
- 建议之后在 CLAUDE.md 的 "Key Gotchas" 里补一条：**清 OS 索引是 safe operation，但需要代码路径满足 idempotent-on-missing 语义**

---

## PR-E 执行中的约定

### KbMetadataReader 语义边界

- `getKbDeptId(kbId) == null` ↔ "KB 不存在或已 @TableLogic 删除"
- 代码依赖 **`t_knowledge_base.dept_id NOT NULL`** schema 不变量（`getKbDeptId` 不需要区分"存在但 dept_id=null"的边界情形）
- 相关 check 处加了显式注释

### 当时未做的清理

- `SysDeptDO` 在 `KbAccessServiceImpl` 是 pre-existing 未使用 import，**不属于 PR-E scope**，后续 PR 顺手清（不要专门为此提 commit）

---

## 基础设施/运维现状

- **分支**：`feature/rbac-decoupling-authz-hardening`（本地，未推远程）
- **后端**：跑在端口 9090，启动命令（PowerShell）：
  ```powershell
  $env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
  ```
  跨分支切换首次运行加 `clean`，同分支迭代不需要
- **OpenSearch**：`http://localhost:9201`，所有 curl 必须带 `NO_PROXY=localhost,127.0.0.1`（bash）或 `$env:NO_PROXY=...`（powershell），否则 curl 走本地代理返回 503
- **活跃 KB**：`opscobtest1`（collection 已空，3 doc 状态 success 但 OS 无数据）/ `opscobtest2`（collection 353 chunks，验证 PR-A 的金样本）
- **遗留不一致**：opscobtest1 的 PG 状态与 OS 索引不同步；新会话可让用户手工在 UI 点 3 个 doc 重分块修复，或置之不理（不影响后续 PR）

---

## 新会话启动 prompt 模板

```
我在继续推进 RBAC 解耦重构。请按以下顺序读这两份文档：

1. 完整 plan：docs/superpowers/plans/2026-04-18-rbac-decoupling-authz-hardening.md
2. 执行笔记（覆盖 plan 缺口）：docs/superpowers/plans/2026-04-18-rbac-execution-notes.md

两份文档完全自包含。读完后请在执行笔记的 "当前进度" 表格里确认接下来要做哪个 PR，并：
- 切确认在分支 `feature/rbac-decoupling-authz-hardening`
- 如要跑 maven/curl，按执行笔记"基础设施/运维现状"的代理规则
- 如要启 subagent，参考该 PR 是否适合并行（PR-C 并行过 9 个 port，PR-D/F/G 可切会话但主会话做也行）
- 完成后 commit（不要 push）+ 更新执行笔记的"当前进度"表格

现在开始 PR-[X]：[PR 名称]
```

---

## 后续 PR 风险速览

- **PR-F**（KbAccessServiceImpl implements 新端口）：机械性，~30min。注意 47 个老调用点**不要一次性迁**，本 PR 只是让 impl 同时实现新老接口让 Spring bean 可以按新端口类型注入
- **PR-G**（MetadataFilterBuilder 抽 bean）：3 处 static 调用必须同批改；`IntentParallelRetriever` 非 Spring 管理，通过 `IntentDirectedSearchChannel` 构造函数传入
- **PR-H**（AccessScope 贯通）：**最大的一个 PR**，跨 RAGChatServiceImpl + RetrievalEngine + MultiChannelRetrievalEngine + SearchContext + 2 channels。`SearchContext.accessibleKbIds` 字段一次性删除，不留 deprecated getter。建议留主会话做、不切 subagent
- **PR-I**（RetrievedChunk 扩字段）：跨 framework + bootstrap，每次 framework 改要 `mvn -pl framework install`
- **PR-J**（AuthzPostProcessor + Milvus/Pg matchIfMissing=false）：相对独立，2 个新文件 + 2 处 `matchIfMissing` 改

---

## 更新本文档的节奏

每完成一个 PR 后：
1. "当前进度" 表格加上该 PR 的 commit hash
2. 如执行中发现 plan 未记的事实（新调用点、新踩坑），追加到本文档对应章节
3. 保持文档 ≤ 500 行，避免与 plan 文档功能重叠（plan 是**规划**，本文档是**执行现场**）
