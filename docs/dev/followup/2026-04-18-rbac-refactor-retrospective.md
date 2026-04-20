# RBAC 改造回顾 + 下一阶段 Backlog

**日期**：2026-04-18
**分支**：`feature/rbac-decoupling-authz-hardening`（14 commit，待合并 main）
**详细 dev log**：`log/dev_log/2026-04-18-rbac-decoupling-authz-hardening.md`
**受众**：工程团队 / 下阶段承接的工程师

---

## 结论

本轮做完后，**RBAC 体系从"单层散耦合的上帝对象"升级为"分层契约 + 纵深防御"**，属于基础设施级改进。但**消费侧迁移、强一致密级刷新、缓存事件化这三件事是未兑现的期票**，它们决定了是否还要写 PR-K/L/M。

一句话：工程底座加固完了，治理层还差半步。

---

## 已经做到的（有实锤）

### 接口契约
- `KbAccessService` 22 方法上帝对象 → 7 个按消费者切的 port（`CurrentUserProbe` / `KbReadAccessPort` / `KbManageAccessPort` / `UserAdminGuard` / `SuperAdminInvariantGuard` / `KbAccessCacheAdmin` + `KbMetadataReader`）
- `KbAccessServiceImpl` 同时实现 7 个接口，Spring 按 port 类型注入；物理还是一个 bean，**逻辑契约变小**
- 单测 mock 面：按消费者域只 mock 4-5 方法，不再是 22 方法
- [KbAccessServiceImpl](</E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java:65>)
- [KbReadAccessPort](</E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbReadAccessPort.java>)

### 模块层级
- user 域 **不再 import** `KnowledgeBaseMapper` / `KnowledgeDocumentMapper`
- `KbMetadataReader` port 住在 framework，impl 住在 knowledge 域（正向依赖：framework ← knowledge.impl）
- 未来 knowledge schema 变化，user 域零被动改动
- [KbMetadataReader](</E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbMetadataReader.java>)
- [KbMetadataReaderImpl](</E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbMetadataReaderImpl.java>)

### 检索授权
- 从 OpenSearch `metadataFilters` 单层 → retriever + `AuthzPostProcessor(order=0)` 双层
- AuthzPostProcessor 三重 fail-closed：`kbId==null` / scope 不含 kbId / `level > ceiling`
- 命中即 `log.error`，生产该日志出现意味着 retriever 过滤失效或索引 schema 漂移（可观测）
- [AuthzPostProcessor](</E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/AuthzPostProcessor.java>)

### 写路径强制不变量
- `VectorStoreService.indexDocumentChunks(..., kbId, securityLevel, ...)` 签名强制
- OpenSearch mapping 声明 `kb_id: keyword` + `security_level: integer`（非 dynamic 推断）
- CHUNK-mode 现在也走 `ensureVectorSpace`（过去只 PIPELINE 走）
- ingestion → 落盘每一条 chunk 都有可信 `kb_id`，这是 `AuthzPostProcessor` 运作的前提
- [VectorStoreService](</E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java:37>)

### 语义纠偏（顺手）
- 未登录态以前 `accessibleKbIds=null` 被 channel 解释为"全放行"（隐藏 RBAC 绕过 bug）
- 现在 `AccessScope.All`（SUPER_ADMIN）vs `AccessScope.empty()`（未登录 fail-closed）严格区分
- 正常路径被 Sa-Token 挡住，这是纯纵深防御改善，用户无感

---

## 没做但要诚实承认的

### 1. 消费侧迁移是期票（FU-1，P1）
**现状**：47 个 `KbAccessService` 注入点一个没动（老接口 `@Deprecated` 继承新 port 保兼容）
**后果**：ISP 收益延后——控制器/服务还能按 22 方法接口拿到 bean，窄化依赖没兑现
**下一步**：按消费者域逐批迁（rag / knowledge / user / admin），每批 1-2 PR，最后删 `@Deprecated` 接口。预计 ~2 周。

### 2. `security_level` 变更仍异步最终一致（FU-2，P1）
**现状**：文档密级改后走 MQ 刷 chunk metadata，刷完之前检索请求读旧密级
**后果**：高敏场景（把 level=3 降为 level=0 后上架）有权限窗口期
**下一步**：
- 方案 A：事务内同步 `_update_by_query`（OpenSearch 支持）
- 方案 B：检索返回后按 doc_id 二次校验最新密级（性能损失小，防御强）
- 推荐 A 用作主路径，B 用作热路径 guard

### 3. 缓存失效仍 manual fan-out（FU-3，P2）
**现状**：`evictCache(userId)` 靠 `RoleServiceImpl` 各 mutation 路径记得调；30min TTL 前角色改了可能读到旧权限
**后果**：新加 mutation 入口忘记清缓存就是长期暗伤（memory 的 `feedback_cache_invalidation_coverage.md` 已标过）
**下一步**：领域事件 `RoleKbRelationChangedEvent` / `UserRoleChangedEvent` → 单一 `CacheEvictionListener` 集中处理
**AuthzPostProcessor 不能救缓存脏**：它读的 ceiling map 本身来自该缓存

### 4. Milvus/Pg 是定时炸弹（FU-4，P2）
**现状**：`matchIfMissing=false` + 启动 WARN 拦住意外激活，但不是 fail-fast
**后果**：配置一旦切 Milvus，retriever 不填 `RetrievedChunk.kbId` → AuthzPostProcessor 全丢 → **登录用户空答案，且无运行时报错**
**下一步**：`MilvusRetrieverService.retrieve` 在 `UserContext.hasUser()` 时直接抛 `UnsupportedOperationException`（生产强拒），开发环境通过 `-Drag.vector.type=opensearch` 绕行

---

## 部署上线前必做（运维侧）

| # | 动作 | 原因 |
|---|---|---|
| 1 | 停后端再启 | 代码改动不能热加载 |
| 2 | 所有 OpenSearch 索引清库重建 | PR-A 之前写入的 chunk 没有 `metadata.kb_id`，AuthzPostProcessor 会 fail-closed 全丢 |
| 3 | 触发所有文档重分块 | 配合第 2 步 |
| 4 | opscobtest1 的 3 个文档必须手动触发 | 当前 status=success 但 OS 索引已空 |
| 5 | 监控 `AuthzPostProcessor dropped N chunks` ERROR 日志 | 上线后若出现该日志 = 索引 schema 漂移或 retriever 过滤失效 |

---

## 验收速查

| 检查 | 命令 | 结果 |
|---|---|---|
| framework 编译 | `mvn -pl framework install -DskipTests` | ✅ |
| bootstrap 编译 | `mvn -pl bootstrap compile` | ✅ |
| user 域无 knowledge DAO 反向依赖 | `grep 'knowledgeBaseMapper\|knowledgeDocumentMapper' KbAccessServiceImpl.java` | 0 命中 |
| SearchContext 单一真相源 | `grep 'accessibleKbIds' rag/core/retrieve/` | 0 命中 |
| static filter builder 灭绝 | `grep 'buildMetadataFilters' bootstrap/` | 0 命中（已是 bean） |
| Java 17 兼容 | `grep -E 'case\s+AccessScope\.'` | 0 命中（无 record pattern for switch） |
| Chunk metadata 正确 | `curl localhost:9201/<kb>/_doc/<id>` | `_source.metadata.kb_id` + `security_level` 有值 |

---

## Backlog 优先级建议

| # | 任务 | 优先级 | 预估 | 收益 |
|---|---|---|---|---|
| FU-5 | opscobtest1 的 3 文档触发重分块 | **P0** | 10 分钟 | 立即可用 |
| FU-1 | 47 个 `KbAccessService` 调用点迁 port | P1 | ~2 周 | ISP 心智收益兑现 |
| FU-2 | `security_level` 强一致补偿 | P1 | ~1 周 | 高敏场景关闭窗口期 |
| FU-3 | 缓存失效事件化 | P2 | ~3 天 | 解决长期暗伤 |
| FU-4 | Milvus/Pg 在 authenticated session 硬拒 | P2 | ~1 天 | 防止定时炸弹 |

---

## 与既有 review 的交叉

与同目录 `CTO_review.md` / `architect_review.md` / `IT_Manager_review.md` / `PM_review.md` 对照：

- **CTO_review**: "向量库三选一目前不是生产级等价" → 本轮已用 `matchIfMissing=false` + 启动 WARN 部分缓解，但 FU-4 才是彻底
- **CTO_review**: "安全等级变更最终一致" → 本轮未动，FU-2 处理
- **CTO_review**: "密钥治理 + 索引参数 + 资源回收" → 本轮外范围，不冲突
- **architect_review**（若存在）: ISP 建议已落地
- **PM_review / IT_Manager_review**: 运维上线前必做清单已整合到本文"部署上线前必做"

---

## 一句话给下一位承接人

读完这份 + `log/dev_log/2026-04-18-rbac-decoupling-authz-hardening.md`（技术细节） + `docs/superpowers/plans/2026-04-18-rbac-execution-notes.md`（踩坑笔记）三份文档，你就掌握了本轮全部上下文，能直接上手 FU-1 任何一块工作。
