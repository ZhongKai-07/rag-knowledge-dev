# RBAC 解耦 + 检索侧授权加固（10 PR + 3 hotfix）

**日期**：2026-04-18
**分支**：`feature/rbac-decoupling-authz-hardening`
**状态**：已完成
**完整 plan**：`docs/superpowers/plans/2026-04-18-rbac-decoupling-authz-hardening.md`
**执行笔记**：`docs/superpowers/plans/2026-04-18-rbac-execution-notes.md`
**团队回顾**：`docs/dev/follow-up/2026-04-18-rbac-refactor-retrospective.md`

---

## 一、背景

`KbAccessService` 演变为 22 方法上帝对象，47 个调用点依赖；user 域反向依赖 knowledge Mapper；检索授权只靠 retriever 单层 filter；CHUNK-mode 写入的 chunk 元数据里没有 `kb_id`，AuthzPostProcessor 前提不成立。本轮做 3 件事：

1. **接口隔离 + 模块解耦**（ISP + 消除反向依赖）
2. **写路径签名加固**（`kb_id` / `security_level` 显式传参）
3. **检索侧纵深防御**（`AuthzPostProcessor` 作为 post-filter）

---

## 二、14 个 commit 概览

| Commit | 类型 | 内容 |
|---|---|---|
| `36a8f76` | docs | plan baseline（1356 行） |
| `df57b56` | feat(rbac) | **PR-A**：`VectorStoreService` 签名扩 `kbId+securityLevel`；7 处写路径调用同批迁；OpenSearch mapping 加 `kb_id: keyword`；IndexerNode 消除 JsonObject round-trip 丢 metadata |
| `33c94cf` | fix(vector) | **Hotfix #1**：`OpenSearchVectorStoreService.deleteDocumentVectors` 对 `index_not_found` 幂等 |
| `8a3caed` | fix(vector) | **Hotfix #2**：CHUNK-mode `persistChunksAndVectorsAtomically` 入口补 `VectorStoreAdmin.ensureVectorSpace`（原只在 PIPELINE + KB 创建时调）|
| `1bb066a` | feat(framework) | **PR-C**：framework 新增 9 个 security port 接口（`AccessScope` / `CurrentUserProbe` / `KbReadAccessPort` / `KbManageAccessPort` / `UserAdminGuard` / `SuperAdminInvariantGuard` / `KbAccessCacheAdmin` / `KbMetadataReader` / `SuperAdminMutationIntent`） |
| `8451caa` | refactor(rbac) | **PR-D**：`SuperAdminMutationIntent` 从 bootstrap 迁入 framework，4 处 import 切换 + 删老文件 |
| `2191ee5` | refactor(rbac) | **PR-E**：`KbMetadataReaderImpl` 放 knowledge 域（7 方法），`KbAccessServiceImpl` 删 `KnowledgeBaseMapper` / `KnowledgeDocumentMapper` 两字段 + 替换 9 处查询 → 彻底消除 user→knowledge 反向依赖 |
| `23c13b7` | docs | 执行笔记落盘（plan 未记录的 7 vs 6 处调用点、`VectorChunk.metadata` 已存在、两条 hotfix 根因等） |
| `4df42a4` | refactor(rbac) | **PR-F**：`KbAccessServiceImpl implements` 7 个新 port；diamond override 解决 `KbAccessService` 与 `KbReadAccessPort` 的 `getAccessibleKbIds(String)` default 冲突；顺手清 `SysDeptDO` 未用 import |
| `82ade22` | refactor(rbac) | **PR-G**：`MetadataFilterBuilder` + `DefaultMetadataFilterBuilder` 抽为可注入 bean；`MultiChannelRetrievalEngine` 原 `public static buildMetadataFilters` 删除，3 处调用（本类 + `VectorGlobalSearchChannel` + `IntentParallelRetriever`）全部迁到 bean 注入 |
| `cfda1dd` | feat(rbac) | **PR-H**：`AccessScope` 贯通 RAGChat → RetrievalEngine → MultiChannel → SearchContext；`SearchContext.accessibleKbIds` 字段**一次性删除**（不留 deprecated getter）；2 channels 改读 `AccessScope`；顺手修未登录态 fail-closed（老 bug：`null` 等价于 SUPER_ADMIN） |
| `039af5c` | feat(rbac) | **PR-I**：`RetrievedChunk` 扩 `kbId` + `securityLevel` 字段；`OpenSearchRetrieverService.toRetrievedChunk` 从 `_source.metadata` 回填；Milvus/Pg 保持 null 占位（dev-only） |
| `fc28abc` | feat(security) | **PR-J**：`AuthzPostProcessor(order=0)` 纵深防御（kbId null / 不在 scope / level > ceiling 三重 fail-closed）；`Milvus/PgRetrieverService` 的 `matchIfMissing=false`；新增 `RagVectorTypeValidator` 启动时 WARN 非 OpenSearch 配置 |
| `15e3347` | fix(document) | **Hotfix #3**：`KnowledgeDocumentServiceImpl.update` 编辑文档元数据报 `jsonb ↔ varchar` 500 错；hybrid 改成 `updateById(entity)` 走 `@TableField(typeHandler)` + 二次 `LambdaUpdateWrapper` 清 null 字段 |

**净改动**：~1900 insertions / ~340 deletions（含 plan + 执行笔记 ~1500 行文档）

---

## 三、踩坑与 plan 缺口

### 3.1 PR-A 执行时

- **plan 说 6 处写路径调用，实际 7 处**：`KnowledgeDocumentServiceImpl:705`（文档 toggle-enable 时重建向量的路径）被漏掉
- **`syncChunkToVector` 有 2 个调用者**（`create` + 单 chunk `enableChunk`），plan 只提 1 个
- **Step 0.5a 是空操作**：`VectorChunk.java:58` 已有 `@Builder.Default private Map<String, Object> metadata = new HashMap<>()` 字段
- **Step 0.7 清索引暴露 2 个 pre-existing bug**（见 Hotfix #1 / #2）

### 3.2 Hotfix #1：`deleteDocumentVectors` 幂等

**触发**：手工 `DELETE /opscobtest2` 后重跑 chunk mode → 事务内 `deleteDocumentVectors` 对不存在的索引抛 `index_not_found_exception` → 整个事务回滚 → document FAILED。

**修复**：catch 异常链含 `index_not_found` 时视为删除成功（语义本来就该如此）。

### 3.3 Hotfix #2：CHUNK-mode 没有 ensureVectorSpace

**根因**：`ensureVectorSpace`（应用声明 mapping）只在 `IndexerNode.execute`（PIPELINE）+ `KnowledgeBaseServiceImpl.create`（KB 创建）调过。CHUNK-mode 的 `persistChunksAndVectorsAtomically` 从未调用，索引若丢，写入时 OpenSearch `auto-create + dynamic` mapping，`kb_id` 变成 `text` 类型（而非声明的 `keyword`），破坏潜在 term query 语义。

**修复**：`KnowledgeDocumentServiceImpl` 注入 `VectorStoreAdmin`，事务外调 `ensureVectorSpace`（idempotent，exists check 内部处理）。

### 3.4 PR-F diamond 冲突

`KbAccessService.getAccessibleKbIds(String)` 和 `KbReadAccessPort.getAccessibleKbIds(String)` 都是 `default`，concrete 类同时继承 → javac 报 "unrelated defaults"。**修复**：impl 显式 override 返回 `getAccessibleKbIds(userId, Permission.READ)`，保留旧语义；迁移完成后可删。

### 3.5 PR-G `MultiChannelRetrievalEngine` 注入不能删 `KbAccessService`

plan 说删 `KbAccessService` 注入，实际 `buildSearchContext` 仍调用 `kbAccessService.getMaxSecurityLevelsForKbs` 做 per-KB 密级预解析（与 static 方法无关）。**保留注入**。

### 3.6 Hotfix #3：chunk_config jsonb typeHandler 绕过

**根因**：`@TableField(typeHandler = JsonbTypeHandler)` 只在 entity-based CRUD（`insert` / `updateById` / `selectById`）生效。`LambdaUpdateWrapper.set(col, val)` 构造 raw SQL，按 `String → VARCHAR` 绑定，PG 拒绝 `jsonb = varchar` 隐式转换。

**修复**：hybrid —— 非 null 字段走 `updateById(entity)`（typeHandler 生效），需置 null 的分支字段走 `LambdaUpdateWrapper.set(col, null)`（NULL 绑定不触发类型冲突），同事务保障原子性。

---

## 四、Step 0.7（OS 索引重建）验收

- 删除 `opscobtest1` / `opscobtest2` 两索引 → 触发 `opscobtest2` 的 2 个文档重分块
- Mapping 验证：`kb_id: keyword`、`security_level: integer`、`doc_id: keyword` 等全部按声明 schema 创建（非 dynamic 推断）
- Chunk 验证：`_source.metadata.kb_id = "2043728453022277632"`（对齐 PG 的 kb_id）/ `security_level = 0`
- 353 chunks = 278（AML KYC）+ 75（Third Party）✓
- **opscobtest1 的 3 个文档尚未触发重分块**（status=success 但 OS 索引为空），作为**预期遗留**记录，等用户后续按需处理

---

## 五、用户可见变化

| 类型 | 项 |
|---|---|
| 🟢 正向修复 | 编辑文档元数据不再 500（Hotfix #3）|
| 🔴 部署前必须处理 | 任何 PR-A 之前写入的 OpenSearch chunk（metadata 无 `kb_id`）将被 `AuthzPostProcessor` fail-closed drop；生产环境需走"清索引 + 重跑 ingestion"一次性流程 |
| 🔴 部署前必须处理 | opscobtest1 的 3 个文档需在 UI 点"重新分块"，否则该 KB 问答永远空 |
| ⚪ 无感 | SUPER_ADMIN + 有权用户的 chat 返回 chunk 集合完全一致（同 kb_id 白名单 + 同 ceiling） |
| ⚪ 纵深防御改进（未暴露到 UI） | 未登录态从"等价 SUPER_ADMIN"的老 bug 改为 `AccessScope.empty()` fail-closed（正常路径被 Sa-Token 挡住） |

---

## 六、架构收益（要点）

**真改进**：
- 22 方法上帝对象 → 7 个按消费者切的 port（ISP，物理仍一个 bean）
- user → knowledge 反向依赖消灭（`KbMetadataReader` port，impl 在 knowledge 域）
- 检索授权从 retriever 单层 → retriever + post-processor 两层纵深
- 写路径签名强制 `kb_id` / `security_level` 传参（ingestion → 落盘的空值问题编译期暴露）
- 未登录态 fail-closed 语义修复（隐藏 bug）

**未兑现（下一阶段 backlog）**：
- 47 个 `KbAccessService` 调用点尚未迁到新 port → ISP 收益延后
- `security_level` 仍异步最终一致（MQ refresh），高敏场景有窗口期
- 缓存失效仍 manual fan-out（`RoleServiceImpl` 各 mutation 路径手动 evictCache）
- Milvus/Pg authz 不完整（已用 `matchIfMissing=false` + 启动 WARN 拦截，但不是 fail-fast）

详见 `docs/dev/follow-up/2026-04-18-rbac-refactor-retrospective.md`。

---

## 七、CLAUDE.md 建议增补

- **VectorStoreService 签名约定**：`indexDocumentChunks` / `updateChunk` 的 `kbId` + `securityLevel` 参数**必须**从 `documentDO` 取值（null 兜底为 `""` / `0`），不能依赖 `chunk.metadata` 里的同名字段
- **`@TableField(typeHandler)` 仅在 entity-based CRUD 生效**：`LambdaUpdateWrapper.set()` 会绕过 handler；jsonb 列的非 null 更新**必须**走 `updateById`，需清 null 的字段用二次 wrapper
- **清 OS 索引后的恢复契约**：`deleteDocumentVectors` 幂等；CHUNK-mode 重跑前会 `ensureVectorSpace` 保 schema；AuthzPostProcessor 对老 chunk（无 `kb_id`）fail-closed，必须重建
- **`AuthzPostProcessor(order=0)` ERROR 日志**：生产出现即意味着 retriever 过滤失效或索引 schema 漂移，必须排查

---

## 八、遗留 follow-ups

1. **FU-1 调用点迁移**（优先级 P1，~2 周）：47 个 `KbAccessService` 注入点按消费者域逐个改到精确 port，最后删 `@Deprecated KbAccessService` 接口
2. **FU-2 密级强一致补偿**（优先级 P1，~1 周）：文档密级变更走同步 `_update_by_query` 或检索时 doc-level 二次校验（消除 MQ 异步窗口）
3. **FU-3 缓存失效事件化**（优先级 P2，~3 天）：`RoleKbRelationChangedEvent` / `UserRoleChangedEvent` 发布 → 集中 listener 清缓存
4. **FU-4 非 OpenSearch 后端硬拒**（优先级 P2，~1 天）：Milvus/Pg 有 `UserContext.hasUser()` 时 fail-fast（而非 WARN + fail-closed）
5. **FU-5 opscobtest1 的 3 文档重分块**（优先级 P0，立即）：切勿漏做，否则该 KB 问答零结果
