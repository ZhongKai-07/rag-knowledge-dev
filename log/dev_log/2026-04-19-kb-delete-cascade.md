# KB 删除级联回收

**日期**：2026-04-19
**分支**：`feature/kb-delete-cascade`
**状态**：已完成

---

## 一、提交记录

| Commit | 类型 | 内容 |
|---|---|---|
| `54f93df` | feat(storage) | `FileStorageService` 新增 bucket 生命周期 API，`S3FileStorageService` 收拢 bucket 创建/删除 |
| `aab67a7` | feat(vector) | `VectorStoreAdmin` 新增 `dropVectorSpace`，OpenSearch 实现幂等删除，Milvus/Pg 明确不支持 |
| `当前提交` | refactor(knowledge) | KB 删除级联解绑 role-KB、事务后回收向量索引和 bucket，补单测与验证 |
| `当前提交` | fix(schema) | `t_knowledge_base.uk_collection_name` 改为 `(collection_name, deleted)`，允许软删后复用 collection 名称 |

---

## 二、设计决定落地

1. **D1 对话历史保留不级联**
   `t_conversation` / `t_message` 不参与本次删除，保留审计与历史追踪价值。
2. **D2 外部资源清理失败不回滚 DB**
   `KnowledgeBaseServiceImpl.delete()` 在事务提交后 best-effort 清理向量空间和存储桶，失败只记 ERROR，避免跨系统事务扩大故障面。
3. **D3 保留删除前置校验**
   仍要求 KB 下无未软删文档才能删除，避免误删外部资源和授权关系。

---

## 三、执行备注

- `knowledge` 域已不再直接依赖 `S3Client`，创建 bucket 改走 `FileStorageService.ensureBucket(...)`。
- `t_role_kb_relation` 在当前仓库实际 schema 中带 `deleted` 列，解绑实现沿用现有 `@TableLogic` 语义，与 `RoleServiceImpl` 现有删除路径保持一致。
- OpenSearch 的索引删除按“不预查 exists，直接删并对 not-found 幂等”实现，避免额外 RT 和 TOCTOU。
- `t_knowledge_base` 的唯一约束已从 `UNIQUE (collection_name)` 调整为 `UNIQUE (collection_name, deleted)`，对齐应用层“只校验活跃 KB”的语义，支持“删除后重建同 collectionName”的 E2E。

---

## 四、已知限制

- `MilvusVectorStoreAdmin` / `PgVectorStoreAdmin` 的 `dropVectorSpace` 明确抛 `UnsupportedOperationException("dropVectorSpace: only OpenSearch supports 生产删除")`。
- 使用非 OpenSearch 后端时，KB 删除会完成 DB 软删与 role-KB 解绑，但外部向量空间回收会记录 ERROR，需运维介入。

---

## 五、验证结果

- 通过：`mvn -pl bootstrap test -Dtest='KnowledgeBaseServiceImplDeleteTest,KbAccessServiceImplTest,KnowledgeDocumentServiceImplTest,RagEvaluationServiceVisibilityTest,RagTraceQueryServiceVisibilityTest,TraceEvalAccessSupportTest'`
- 通过：`git grep 'S3Client' bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/` 无输出
- 未通过：`mvn -pl bootstrap clean compile`
  Windows 文件锁导致 `bootstrap/target/test-classes/mockito-extensions/org.mockito.plugins.MockMaker` 无法删除；常规 `compile` 与目标测试均已通过。
