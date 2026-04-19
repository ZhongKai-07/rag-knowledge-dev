# KB 删除级联回收实施计划

> 日期：2026-04-19
> 目标分支：`feature/kb-delete-cascade`
> 预计改动：~150 LOC（含测试）
> 交付方式：新会话按此文档从 `main` 起步执行

---

## 一、目标

修复 `KnowledgeBaseServiceImpl.delete()` 软删 KB 后遗留外部资源的问题。当前：
- 软删 `t_knowledge_base` 行
- 不回收 `t_role_kb_relation` 绑定
- 不回收 OpenSearch index（即便无文档，空索引仍占分片/mapping）
- 不回收 S3 bucket（历史软删文档原文残留）
- 不失效 `kb_access:*` / `kb_security_level:*` 缓存

## 二、已锁定的设计决定

| 决定 | 选择 | 依据 |
|---|---|---|
| D1 对话历史 | **保留不级联**（`t_conversation` / `t_message` 不动） | 审计价值；DEPT_ADMIN 天然看不到跨部门对话；重建同名 KB 时 `kb_id` 不同语义正确 |
| D2 外部资源清理失败策略 | **`afterCommit` 异步 best-effort + ERROR 日志**，不回滚 DB | 与 `security_level` 刷新同模式；跨系统事务不可靠 |
| D3 前置校验 | **保留**（有未软删文档即 `ClientException`） | 防误删；运维需先清文档再删 KB 可审计 |

## 三、架构约束（必须遵守）

1. **`knowledge` 域不得直接依赖 `S3Client`。** 当前 `KnowledgeBaseServiceImpl` 直接 `import software.amazon.awssdk.services.s3.S3Client` 并调 `createBucket(...)` 是历史欠债，本次顺手收拢到 `FileStorageService`。
2. **`knowledge` 域不得直接操作 `t_role_kb_relation`。** 该表在 `user` 域，跨域调用必须走 `KbAccessService`（或同域 port）暴露的方法。
3. **`rag/core/vector/` 是向量存储抽象边界。** drop 索引必须通过 `VectorStoreAdmin` 接口，不允许 `knowledge` 域直接依赖 OpenSearch SDK。
4. **清理逻辑必须集中在一处。** 不要在 controller / schedule / 其他 service 里散落 "顺带清理" 的调用 —— 所有级联都走 `KnowledgeBaseServiceImpl.delete()` 的 `afterCommit` 钩子。
5. **不改表结构、不改 API 返回签名、不改前端。**

## 四、Pre-work：必须先验证的假设

执行者开工第一件事是**验证**下列假设，如有偏差先报告再决定怎么办（不要硬推）：

1. `FileStorageService` 接口（`bootstrap/src/main/java/.../rag/service/FileStorageService.java`）目前只有 `upload` / `reliableUpload` 几个方法，没有 `createBucket` / `deleteBucket`。
2. `S3FileStorageService` 是唯一实现（`rag/service/impl/S3FileStorageService.java`）。
3. `VectorStoreAdmin` 接口（`rag/core/vector/VectorStoreAdmin.java`）只有 `ensureVectorSpace(VectorSpaceSpec)`。三种实现：`OpenSearchVectorStoreAdmin` / `MilvusVectorStoreAdmin` / `PgVectorStoreAdmin`。
4. `KbAccessService` 接口（`user/service/KbAccessService.java`）没有"按 kbId 清理所有 role-KB 绑定并失效缓存"的方法。
5. `KnowledgeBaseServiceImpl.delete()` 当前实现在 line 193-213，仅软删 `t_knowledge_base` 行，前置校验"无未软删文档"。
6. `KnowledgeBaseServiceImpl.create()` 当前直接用 `s3Client.createBucket(...)`（line 108-119），并在 `vectorStoreAdmin.ensureVectorSpace(...)`（line 127）创建向量空间。

如果任意一条与现状不符，**停下来报告**，不要基于过时假设继续。

## 五、实施步骤（分五层改动）

### Step 1. `FileStorageService` 补齐 bucket 生命周期 API

**文件**：`bootstrap/src/main/java/.../rag/service/FileStorageService.java`

新增接口方法：
```java
/** 创建 bucket（幂等）。已存在视为成功。*/
void ensureBucket(String bucketName);

/** 删除 bucket（含所有对象）。不存在视为成功（幂等）。*/
void deleteBucket(String bucketName);
```

**文件**：`bootstrap/src/main/java/.../rag/service/impl/S3FileStorageService.java`

- `ensureBucket`：把现在 `KnowledgeBaseServiceImpl.create()` 里 line 108-119 的 `s3Client.createBucket(...)` + `BucketAlreadyOwnedByYouException` 捕获逻辑**整体搬过来**。保留 `ServiceException` 抛出契约。
- `deleteBucket`：先 `listObjectsV2` 分批删对象（S3 的 `deleteBucket` 要求空 bucket），然后 `s3Client.deleteBucket(...)`。捕获 `NoSuchBucketException` 视为幂等成功。任何其它异常原样向上抛（调用方决定怎么处理）。

### Step 2. `VectorStoreAdmin` 补齐 drop API

**文件**：`rag/core/vector/VectorStoreAdmin.java`

```java
/** 删除向量空间。不存在视为成功（幂等）。*/
void dropVectorSpace(VectorSpaceId spaceId);
```

**三种实现**：
- `OpenSearchVectorStoreAdmin`：`DELETE /{collection}`，捕获 `index_not_found_exception` 视为成功 —— 参考同类文件里 `OpenSearchVectorStoreService.isIndexNotFound` 已有的幂等处理（CLAUDE.md gotcha）。
- `MilvusVectorStoreAdmin` / `PgVectorStoreAdmin`：生产已锁 OpenSearch，这两个**抛 `UnsupportedOperationException("dropVectorSpace: only OpenSearch supports 生产删除")`**。与现有"Milvus/pg 忽略 metadataFilters"的分歧模式一致，不要假装实现。

### Step 3. `KbAccessService` 暴露按 KB 解绑 API

**文件**：`user/service/KbAccessService.java`

```java
/**
 * 删除 t_role_kb_relation 里所有 kb_id=? 的行，并失效涉及的缓存。
 * 返回删除的绑定数。不存在绑定视为成功（返回 0）。
 */
int unbindAllRolesFromKb(String kbId);
```

**实现**：`user/service/impl/KbAccessServiceImpl.java`

1. `SELECT DISTINCT role_id FROM t_role_kb_relation WHERE kb_id=?` —— 先查，后面用于缓存失效
2. 关联的用户集合通过 `t_user_role` 查出（可以直接两张表 JOIN 一次查 userIds，避免大量单点查询）
3. `DELETE FROM t_role_kb_relation WHERE kb_id=?`（用 `LambdaQueryWrapper` + `delete()` 或 mapper 直接 SQL，任选，和现有代码风格一致即可）
4. 对受影响的 userId 集合逐个失效 `kb_access:{userId}` 与 `kb_security_level:{userId}` 两个 Redis key（如果已有 `evictKbAccessCache` 单用户方法就复用）
5. 返回 step 3 的 affected rows

**注意**：如果受影响 userId 数量 > 某阈值（例如 500），改用 `DEL kb_access:*` 模式（memory 里记录的 "失效面大时用户类别 bypass cache" 模式）。阈值和实现形式由执行者判断，选最简单的先上。

### Step 4. `KnowledgeBaseServiceImpl` 改造（核心）

**文件**：`knowledge/service/impl/KnowledgeBaseServiceImpl.java`

#### 4.1 替换 `s3Client` 直调
- 删除 `private final S3Client s3Client;`（line 66）
- 删除 `import software.amazon.awssdk...`（line 47-49）
- 新增 `private final FileStorageService fileStorageService;`
- `create()` line 108-119 改为单行 `fileStorageService.ensureBucket(bucketName);`
- `create()` 的 `ServiceException("存储桶名称已被占用")` 抛出逻辑移到 `S3FileStorageService.ensureBucket` 内部（见 Step 1）

#### 4.2 改造 `delete()` 方法
当前签名保持：`public void delete(String kbId)`。`@Transactional` 保留。

```java
@Transactional(rollbackFor = Exception.class)
@Override
public void delete(String kbId) {
    KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
    if (kbDO == null || (kbDO.getDeleted() != null && kbDO.getDeleted() == 1)) {
        throw new ClientException("知识库不存在");
    }

    // 保留前置校验 (D3)
    Long docCount = knowledgeDocumentMapper.selectCount(
            Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                    .eq(KnowledgeDocumentDO::getKbId, kbId)
                    .eq(KnowledgeDocumentDO::getDeleted, 0)
    );
    if (docCount != null && docCount > 0) {
        throw new ClientException("当前知识库下还有文档，请删除文档");
    }

    // 事务内：DB 软删 + 按 kbId 解绑所有 role-KB 关系
    kbDO.setDeleted(1);
    kbDO.setUpdatedBy(UserContext.getUsername());
    knowledgeBaseMapper.deleteById(kbDO);
    int unbound = kbAccessService.unbindAllRolesFromKb(kbId);
    log.info("KB 软删 + role-KB 解绑完成, kbId={}, unbound={}", kbId, unbound);

    // 事务后：best-effort 回收外部资源
    String collectionName = kbDO.getCollectionName();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupExternalResources(kbId, collectionName);
            }
        });
    } else {
        cleanupExternalResources(kbId, collectionName);
    }
}

private void cleanupExternalResources(String kbId, String collectionName) {
    try {
        vectorStoreAdmin.dropVectorSpace(
                VectorSpaceId.builder().logicalName(collectionName).build());
        log.info("KB 向量空间已删除, kbId={}, collection={}", kbId, collectionName);
    } catch (Exception ex) {
        log.error("KB 向量空间删除失败（需运维介入）, kbId={}, collection={}", kbId, collectionName, ex);
    }
    try {
        fileStorageService.deleteBucket(collectionName);
        log.info("KB 存储桶已删除, kbId={}, bucket={}", kbId, collectionName);
    } catch (Exception ex) {
        log.error("KB 存储桶删除失败（需运维介入）, kbId={}, bucket={}", kbId, collectionName, ex);
    }
}
```

**关键点**：
- `role-KB` 解绑放在**事务内**（DB 一致），与 KB 软删同成败
- 向量空间 / 存储桶放在**事务后**，失败只打 ERROR 日志不回滚
- `cleanupExternalResources` 两个 try-catch **互不影响**（bucket 删失败不能阻止前面的 log）
- `else` 分支处理"非事务上下文调用此方法"的罕见情况（测试里主要走这条）

### Step 5. 文档层面

**不要**改 CLAUDE.md —— 这次 gotcha 没有 "反直觉的坑"，业务行为直观。

**要**在 `log/dev_log/` 下新建 `log/dev_log/YYYY-MM-DD-kb-delete-cascade.md` 记录：
- PR 链接、commit 哈希
- 3 个 D 决定的依据
- 已知限制：Milvus/Pg 后端的 `dropVectorSpace` 抛 `UnsupportedOperationException` —— 使用非 OpenSearch 后端时 KB 删除会部分失败

## 六、测试计划

新建：`bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseServiceImplDeleteTest.java`

必须覆盖的 6 条用例（用纯 Mockito + 非 `@SpringBootTest`）：

| # | 场景 | 期望 |
|---|---|---|
| T1 | KB 不存在 | `ClientException("知识库不存在")` |
| T2 | KB 已软删（`deleted=1`） | `ClientException("知识库不存在")` |
| T3 | 有未软删文档 | `ClientException("当前知识库下还有文档")`，不调任何清理 |
| T4 | 正常删除 | `knowledgeBaseMapper.deleteById` 被调 1 次；`kbAccessService.unbindAllRolesFromKb(kbId)` 被调 1 次；`vectorStoreAdmin.dropVectorSpace(...)` 被调 1 次；`fileStorageService.deleteBucket(...)` 被调 1 次 |
| T5 | `vectorStoreAdmin.dropVectorSpace` 抛异常 | `fileStorageService.deleteBucket` 仍被调；方法整体不抛（ERROR 已记录） |
| T6 | `fileStorageService.deleteBucket` 抛异常 | `vectorStoreAdmin.dropVectorSpace` 被调；方法整体不抛 |

**注意** T4-T6 都要用 `setField` → `ReflectionTestUtils.setField` 注入 `@Value` 字段（如果有的话），参考 `KnowledgeDocumentServiceImplTest` 的模式。

单测中 `TransactionSynchronizationManager.isSynchronizationActive()` 返回 `false`，走 `else` 分支，因此 `cleanupExternalResources` 直接被调 —— 测试天然覆盖。

另外为 `KbAccessServiceImpl.unbindAllRolesFromKb` 新增独立单测，覆盖：
- 无绑定（返回 0）
- 有绑定但没对应 user（空 userIds，缓存不调）
- 有绑定也有 user（验证缓存 evict 被调对次数）

## 七、Definition of Done

- [ ] Pre-work 验证通过（无假设偏差）
- [ ] Step 1-4 代码完成，`mvn clean compile` 通过
- [ ] 新建测试 + 已有测试（9 个来自 PR #7）全部通过：`mvn -pl bootstrap test -Dtest='KnowledgeBaseServiceImplDeleteTest,KbAccessServiceImplTest,KnowledgeDocumentServiceImplTest,RagEvaluationServiceVisibilityTest,RagTraceQueryServiceVisibilityTest,TraceEvalAccessSupportTest'`
- [ ] `git grep 'S3Client' bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/` 无输出（knowledge 域已不依赖 S3Client）
- [ ] 提交 3 个 commit：
  - `feat(storage): add bucket lifecycle APIs on FileStorageService`
  - `feat(vector): add dropVectorSpace on VectorStoreAdmin (OpenSearch impl only)`
  - `refactor(knowledge): cascade external resource cleanup on KB delete`
  （`unbindAllRolesFromKb` 加进最后这条 commit 或拆成独立 `feat(security)`，由执行者按改动量决定）
- [ ] 写 dev log 记录
- [ ] 创建 PR，目标 `main`，标题 `feat(knowledge): cascade cleanup of role bindings / vector index / S3 bucket on KB delete`

## 八、显式 Out of Scope

- 不改 `t_conversation` / `t_message`（D1 选 A）
- 不改 Milvus / Pg 的 `dropVectorSpace` 实现（仅 OpenSearch）
- 不改前端
- 不改 `KnowledgeBaseServiceImpl.create()` 的 API 签名或事务边界 —— 只把 `s3Client` 调用替换为 `fileStorageService.ensureBucket(...)`
- 不改 `t_role_kb_relation` 表结构
- 不补"历史残留脏 bucket / index 批量清理"脚本（那是运维 one-off，不在本 PR）

## 九、风险提示

1. **并发删除同 KB**：两条请求同时进入 `delete()`，`@Transactional` 基于 DB 行锁（`selectById` 不自动加锁，但 `deleteById` 会）。低概率问题，可先不处理；真要防就加 Redis 分布式锁。
2. **`t_role_kb_relation` 无 `deleted` 列**（参考 schema_pg.sql），是**硬删**表。`unbindAllRolesFromKb` 用真 DELETE 不是逻辑删。验证时 `docker exec postgres psql ... -c "SELECT count(*) FROM t_role_kb_relation WHERE kb_id='...'"` 应返回 0。
3. **S3 bucket 非空删除**：RustFS 的 `deleteBucket` 要求 bucket 为空，否则报错。`S3FileStorageService.deleteBucket` 必须先分页 `listObjectsV2` + 批量 `deleteObjects`。历史软删文档会在这一步被清掉 —— 这是预期行为，但日志里要打出"删除了多少对象"。
4. **`bucketName == collectionName == KB.collection_name`** 是现有约定（`KnowledgeBaseServiceImpl.create()` 里就这么用的）。不要引入新字段，直接复用 `kbDO.getCollectionName()`。
