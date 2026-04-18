# RBAC 权限解耦 + 检索侧授权加固 — 完整实施方案

- **日期**：2026-04-18
- **作者**：基于 4 轮 review 迭代（ZhongKai-07 + Codex reviewer）后由 `feature-dev:code-architect` 产出
- **状态**：待执行
- **目标分支**：待定（建议按 PR 拆分逐步提交到 main）

## 背景

项目当前权限体系：`user/service/KbAccessService` 是 22 方法上帝对象，被 47 个调用点依赖。检索侧靠 OpenSearch 元数据 `security_level` + `kb_id` 白名单做过滤。本方案通过：

1. **ISP 接口隔离**：按消费者把 22 方法拆成 7 个细粒度 port
2. **消除反向依赖**：`user` 域不再直接持有 `knowledge` 域的 Mapper
3. **写路径闭环**：`VectorStoreService` 签名显式接 `kbId/securityLevel`，消除手工 chunk 路径的密级越权窗口
4. **检索侧纵深防御**：新增 `AuthzPostProcessor` 做 post-filter，retriever 过滤失效时 fail-closed

## 锁定的关键决策

1. **向量库**：生产只用 OpenSearch，Milvus/Pg 保留代码但只需编译通过
2. **数据策略**：开发环境，允许 `DELETE /opensearch/<collection>` + 重跑 ingestion，不做 `_update_by_query` 回填
3. **Java 基线**：17，不得使用 record pattern / pattern matching for switch（项目未开 `--enable-preview`）
4. **`AuthzPostProcessor`**：直接 enforce 模式，`kbId==null` fail-closed
5. **老接口**：`KbAccessService` 保留 `@Deprecated`（extends 新端口），47 个调用点允许分阶段迁移，不强求一次性切完
6. **非目标**：本轮不做 UserContext 参数化、不做缓存事件化、不做多向量库过滤一致性

## Review 历史摘要（扎实性背书）

- **Round 1**（原始清单）：reviewer 指出 `KbMetadataReader` 端口不足、`RetrievedChunk` 缺 kbId/securityLevel、`SuperAdminMutationIntent` 模块耦合、`buildMetadataFilters` 调用点漏、Milvus 编译挂、`AccessScope` 密级语义不一致
- **Round 2**：reviewer 指出 PIPELINE + `skipIndexerWrite(true)` 路径让 kb_id 写入成死代码、非 PIPELINE 的 4 处 `new VectorChunk` 漏 metadata、`AccessScope` 和 `accessibleKbIds` 双轨漂移
- **Round 3**：reviewer 指出手工 chunk 写路径漏 `security_level` = 新的越权窗口、`IndexerNode` 非 skip 模式丢 metadata round-trip、Java 17 不支持 record pattern for switch、`AccessScope` 没真正到入口
- **Round 4**：本方案已覆盖所有 Round 1-3 findings

---

## 实际代码状况与任务差异核对

在开始前，先说明几处任务描述与实际代码的差异，这些差异会影响具体步骤：

**已存在、无需新增的内容：**
- `OpenSearchVectorStoreAdmin` mapping 中 `security_level` 已存在（第 246 行），但 `kb_id` 确实缺失，需补充
- `IngestionContext` 已有 `metadata` Map 字段，`IndexerNode.buildRows` 已从 `metadata` 读 `security_level`，但读的是 `mergedMetadata`（即 `context.getMetadata()`），不是专用字段
- `IndexerNode.buildRows` 已写入 `task_id / pipeline_id / source_type / source_location`（行 229-237），但 `insertRows` 的 round-trip 丢弃这些（行 150-174），需修复
- `KnowledgeDocumentDO` 已有 `securityLevel` 字段（第 139 行）

**实际调用点数量（与任务描述对比）：**
- `KnowledgeChunkServiceImpl` 中调用 `vectorStoreService.indexDocumentChunks` 的行：create（146行）、batchCreate（233行）、batchToggleEnabled（412行），`updateChunk` 在 update 方法（272行），`syncChunkToVector`（495行）
- 任务描述的 5 个 `KnowledgeChunkServiceImpl` 行号大体准确，但实际是 4 个独立调用点 + 1 个私有方法

**`SuperAdminMutationIntent` 已在 `bootstrap/.../user/service/` 下**，需迁移至 framework

---

## 阶段 0：写路径闭环 + Schema（必须最先完成）

### 步骤 0.1 — OpenSearch mapping 补 `kb_id` 字段

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java`

定位 `buildMappingJson` 方法的 `"metadata"` 段（实际在第 219-256 行，`metadata.properties` 块）。在 `"security_level": { "type": "integer" }` 之后（第 246 行后），新增一行：

```
"kb_id": { "type": "keyword" },
```

完整的 `metadata.properties` 顺序变为：`doc_id` / `chunk_index` / `task_id` / `pipeline_id` / `source_type` / `source_location` / `security_level` / `kb_id` / `keywords` / `summary`

**验证点：** `mvn -pl bootstrap clean compile` 通过；新建 KB 后 `curl http://localhost:9201/<collection>/_mapping | python -m json.tool | grep kb_id` 有输出

**依赖：** 无前置步骤

---

### 步骤 0.2 — `VectorStoreService` 接口扩签名

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`

将第 37 行和第 46 行两个方法签名替换：

```java
/**
 * 批量建立文档的向量索引。
 * @param kbId          所属知识库 ID（写入 metadata.kb_id，null 时 warn 并写入空字符串）
 * @param securityLevel 文档安全等级（写入 metadata.security_level，null 时兜底为 0）
 */
void indexDocumentChunks(String collectionName, String docId,
                         String kbId, Integer securityLevel,
                         List<VectorChunk> chunks);

/**
 * 更新单个 chunk 的向量索引。
 * @param kbId          所属知识库 ID
 * @param securityLevel 文档安全等级，null 时兜底为 0
 */
void updateChunk(String collectionName, String docId,
                 String kbId, Integer securityLevel,
                 VectorChunk chunk);
```

保留其余方法签名（`deleteDocumentVectors`、`deleteChunkById`、`deleteChunksByIds`、`updateChunksMetadata`）不变。

**关于 `updateChunksMetadata(collectionName, docId, Map<String, Object> fields)`（line 83）：** 此方法是文档级元数据 bulk 更新入口（典型场景：用户通过 `PUT /documents/{id}/security-level` 修改密级后，刷新该 doc 下所有 chunk 的 `metadata.security_level`）。由于 `fields` 已是通用 map，能天然承载 `kb_id`/`security_level` 变更，**本次不改签名**。执行者请勿"为了对齐"而扩展它。

**依赖：** 步骤 0.1 完成后才能有效验证 mapping，但接口签名改动本身无依赖

**风险：** 此步骤改完后 `bootstrap` 模块立即编译失败（`OpenSearchVectorStoreService`、`MilvusVectorStoreService`、`PgVectorStoreService` 未实现新签名），必须与步骤 0.3a/0.3b 同批提交，不允许中间态进入 CI

---

### 步骤 0.3a — `OpenSearchVectorStoreService` 实现新签名

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java`

**改动 1：** 两个方法签名加参数（第 43、78 行）：

```java
@Override
public void indexDocumentChunks(String collectionName, String docId,
                                String kbId, Integer securityLevel,
                                List<VectorChunk> chunks) { ... }

@Override
public void updateChunk(String collectionName, String docId,
                        String kbId, Integer securityLevel,
                        VectorChunk chunk) { ... }
```

**改动 2：** `buildDocument` 方法（第 199 行）调整后：在 chunk.getMetadata() merge 之后，显式覆盖这两个字段，确保优先级高于 chunk 内部携带的值：

```java
private Map<String, Object> buildDocument(String collectionName, String docId,
                                           String kbId, Integer securityLevel,
                                           VectorChunk chunk) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("collection_name", collectionName);
    metadata.put("doc_id", docId);
    metadata.put("chunk_index", chunk.getIndex());

    // merge chunk 内部携带的 metadata（task_id、pipeline_id、source_type 等）
    if (chunk.getMetadata() != null) {
        chunk.getMetadata().forEach((k, v) -> { if (v != null) metadata.put(k, v); });
    }

    // 显式覆盖授权关键字段（以入参为准，不允许 chunk.metadata 覆盖）
    metadata.put("kb_id", kbId != null ? kbId : "");
    metadata.put("security_level", securityLevel != null ? securityLevel : 0);

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("id", chunk.getChunkId());
    doc.put("content", chunk.getContent() != null ? chunk.getContent() : "");
    if (chunk.getEmbedding() != null) {
        List<Float> embedding = new ArrayList<>(chunk.getEmbedding().length);
        for (float f : chunk.getEmbedding()) embedding.add(f);
        doc.put("embedding", embedding);
    }
    doc.put("metadata", metadata);
    return doc;
}
```

两处调用 `buildDocument` 的地方同步传入新参数：`indexDocumentChunks` 循环中传 `(collectionName, docId, kbId, securityLevel, chunk)`，`updateChunk` 中传同样 4 参。

**依赖：** 步骤 0.2 接口签名必须先改

---

### 步骤 0.3b — Milvus 和 Pg 实现编译占位

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java` 对应的 `VectorStoreService` 实现

查找 `MilvusVectorStoreService.java`（路径 `rag/core/vector/MilvusVectorStoreService.java`）和 `PgVectorStoreService.java`：

两个类的 `indexDocumentChunks` 和 `updateChunk` 方法签名加上 `String kbId, Integer securityLevel` 参数，方法体不变（不使用新参数，编译通过即可，行为不变）。在方法体内加注释：`// TODO kbId/securityLevel ignored – Milvus/Pg non-OpenSearch backend dev-only`

**依赖：** 步骤 0.2

**验证点：** `mvn -pl bootstrap clean compile` 无报错

---

### 步骤 0.4 — `IngestionContext` 扩专用字段

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/domain/context/IngestionContext.java`

在现有 `metadata` Map 字段之后新增两个专用字段（第 99 行之后）：

```java
/**
 * 本次 ingestion 对应的知识库 ID（用于写入 OpenSearch metadata.kb_id）。
 * 独立字段，不依赖 metadata Map，避免 round-trip 丢失。
 */
private String kbId;

/**
 * 文档安全等级（0=PUBLIC … 3=RESTRICTED）。
 * 独立字段，不依赖 metadata Map。null 时 IndexerNode 兜底为 0。
 */
private Integer securityLevel;
```

类上已有 `@Data @NoArgsConstructor @AllArgsConstructor @Builder`，Lombok 自动生成 getter/setter/builder 方法。

**依赖：** 无

---

### 步骤 0.5 — `IndexerNode.insertRows` 消除 JsonObject round-trip

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IndexerNode.java`

**现状分析：**
- `execute` 调用路径：`buildRows` 构造 `JsonObject` 列表（已填充 `task_id/pipeline_id/source_type/source_location/security_level`），然后 `insertRows` 将 `JsonObject` 重新解析为 `VectorChunk`，这一步只还原了 `id/content/embedding/chunk_index`，丢弃了所有 metadata
- `buildRows` 已经在 `VectorChunk.setChunkId` 和 `VectorChunk.setEmbedding` 上做了 side-effect（第 217-219 行），即 chunks 列表本身已包含 chunkId 和 embedding

**修复方案：** 跳过 `insertRows` 的 round-trip，直接传递 `context.getChunks()`（已经过 buildRows 填充 chunkId/embedding），并将 metadata 信息通过 `VectorChunk.metadata` 传递：

修改 `execute` 方法中的非 skipIndexerWrite 分支（第 110 行处）：

```java
// 将 ingestion 元信息注入每个 chunk 的 metadata（用于 buildDocument merge）
String taskId = context.getTaskId();
String pipelineId = context.getPipelineId();
DocumentSource source = context.getSource();
for (VectorChunk chunk : chunks) {
    Map<String, Object> meta = chunk.getMetadata();
    if (meta == null) {
        meta = new java.util.HashMap<>();
        chunk.setMetadata(meta);
    }
    if (taskId != null) meta.put("task_id", taskId);
    if (pipelineId != null) meta.put("pipeline_id", pipelineId);
    if (source != null && source.getType() != null) meta.put("source_type", source.getType().getValue());
    if (source != null && source.getLocation() != null) meta.put("source_location", source.getLocation());
    // security_level 和 kb_id 通过 vectorStoreService 的新签名传入，不写入 chunk.metadata
}

vectorStoreService.indexDocumentChunks(
    collectionName,
    context.getTaskId(),
    context.getKbId(),                                     // 步骤 0.4 新增字段
    context.getSecurityLevel(),                            // 步骤 0.4 新增字段
    chunks
);
```

删除原 `insertRows` 私有方法（第 145-179 行）。

**风险：** `VectorChunk` 需有 `Map<String, Object> metadata` 字段和 setter。需确认 `core/chunk/VectorChunk.java` 是否有该字段。

**验证点：** 独立 ingestion 任务跑完后，`curl http://localhost:9201/<col>/_doc/<id>` 查看 `_source.metadata.task_id`、`source_type`、`kb_id`、`security_level` 均有值

---

### 步骤 0.5a — ~~确认 VectorChunk 有 metadata 字段~~（审查已验证，跳过）

**动作：无（前置验证完成）**

审查时已确认 `VectorChunk.java:58` 已有字段：

```java
@Builder.Default
private Map<String, Object> metadata = new HashMap<>();
```

含 `@Data @Builder.Default`，Lombok 自动生成 getter/setter。**本步骤无需任何代码改动**，保留在 plan 里仅作为 step 0.5 的前置依赖声明。

---

### 步骤 0.6 — 6 处写路径调用点同批迁移

**动作：modify**
**涉及文件：**

**(A) `KnowledgeDocumentServiceImpl.java` — 第 272 行 `persistChunksAndVectorsAtomically`**

该私有方法签名扩为：`private int persistChunksAndVectorsAtomically(String collectionName, String docId, String kbId, Integer securityLevel, List<VectorChunk> chunkResults)`

方法内第 272 行处的调用改为：`vectorStoreService.indexDocumentChunks(collectionName, docId, kbId, securityLevel != null ? securityLevel : 0, chunkResults);`

调用此私有方法的位置（`runChunkTask` 方法内）需从 `documentDO` 取值传入：`kbId = documentDO.getKbId()`，`securityLevel = documentDO.getSecurityLevel()`

**(B) `KnowledgeDocumentServiceImpl.java` — `runPipelineProcess` 方法（第 362-371 行）**

构建 `IngestionContext` 时补充：

```java
IngestionContext context = IngestionContext.builder()
    .taskId(docId)
    .pipelineId(pipelineId)
    .rawBytes(fileBytes)
    .mimeType(documentDO.getFileType())
    .vectorSpaceId(VectorSpaceId.builder().logicalName(kbDO.getCollectionName()).build())
    .kbId(documentDO.getKbId())                           // 新增
    .securityLevel(documentDO.getSecurityLevel())         // 新增
    .skipIndexerWrite(true)
    .build();
```

注：此处 `skipIndexerWrite=true`，向量写入由调用方（`persistChunksAndVectorsAtomically`）统一处理，`kbId/securityLevel` 也是通过 (A) 的新签名传入，此处填充是为独立 pipeline 模式（步骤 (C)）准备的。

**(C) `IngestionTaskServiceImpl.java` — `executeInternal` 方法（第 162 行）**

构建 `IngestionContext` 时补充：

```java
IngestionContext context = IngestionContext.builder()
    .taskId(String.valueOf(task.getId()))
    .pipelineId(resolvedPipelineId)
    .source(source)
    .rawBytes(rawBytes)
    .mimeType(mimeType)
    .vectorSpaceId(vectorSpaceId)
    .kbId(null)            // 独立 ingestion 任务无 docId，kbId 来源为 vectorSpaceId 的 logicalName
    .securityLevel(0)      // 默认 PUBLIC；调用方如需覆盖，在 API 层解析后传入
    .logs(new ArrayList<>())
    .build();
```

**架构决策（rationale）：** 独立 ingestion 任务（通过 `IngestionTaskServiceImpl`）不与 KB 文档关联，没有 `docId` 也没有 `kbId`。此场景下 `security_level` 兜底为 0（PUBLIC），`kb_id` 写空字符串。`AuthzPostProcessor`（步骤 2.3）在 `kbId==null` 时 fail-closed，所以独立 pipeline 的 chunk 不会通过 RBAC 过滤进入 authenticated session 的问答 — 这是预期行为，记录在此。

**(D) `KnowledgeChunkServiceImpl.java` — `create` 方法（第 146 行）**

`syncChunkToVector` 私有方法签名扩为：

```java
private void syncChunkToVector(String collectionName, String docId,
                                String kbId, Integer securityLevel,
                                KnowledgeChunkDO chunkDO, String embeddingModel) { ... }
```

方法体内调用 `vectorStoreService.indexDocumentChunks` 时传入 `kbId, securityLevel != null ? securityLevel : 0`。

`create` 方法内的调用处（第 146 行）改为：`syncChunkToVector(collectionName, docId, documentDO.getKbId(), documentDO.getSecurityLevel(), chunkDO, embeddingModel);`

**(E) `KnowledgeChunkServiceImpl.java` — `batchCreate` 方法（第 233 行）**

`writeVector=true` 分支中对 `vectorStoreService.indexDocumentChunks` 的调用：`vectorStoreService.indexDocumentChunks(collectionName, docId, documentDO.getKbId(), documentDO.getSecurityLevel() != null ? documentDO.getSecurityLevel() : 0, vectorChunks);`

**(F) `KnowledgeChunkServiceImpl.java` — `update` 方法（第 272 行）**

对 `vectorStoreService.updateChunk` 的调用：`vectorStoreService.updateChunk(collectionName, docId, documentDO.getKbId(), documentDO.getSecurityLevel() != null ? documentDO.getSecurityLevel() : 0, VectorChunk.builder()...);`

**(G) `KnowledgeChunkServiceImpl.java` — `batchToggleEnabled` / `enableChunk` 方法（412 行附近）**

`indexDocumentChunks` 调用处（enabled 分支）：`vectorStoreService.indexDocumentChunks(collectionName, docId, documentDO.getKbId(), documentDO.getSecurityLevel() != null ? documentDO.getSecurityLevel() : 0, vectorChunks);`

**验证点：**
- `mvn -pl bootstrap clean compile` 通过
- `grep -rn 'indexDocumentChunks\|updateChunk' bootstrap/src/main/java/ | grep -v '\.java:.*void\|interface\|VectorStoreService\|//'` 检查所有调用处已传 5 参

---

### 步骤 0.7 — 清库重建（运维操作）

```bash
# 对每个 KB collection（collection_name 来自 t_knowledge_base.collection_name）
docker exec postgres psql -U postgres -d ragent -c "SELECT collection_name FROM t_knowledge_base WHERE deleted=0;"
# 对每个 collection_name 执行：
curl -X DELETE http://localhost:9201/<collection-name>
# 通过 API 重新触发分块（POST /knowledge-base/docs/{docId}/chunk）
```

**验证点：**
- `curl http://localhost:9201/<col>/_mapping | python -m json.tool | grep kb_id` 有输出
- 取一条 chunk：`curl http://localhost:9201/<col>/_doc/<id>` 中 `_source.metadata.kb_id` 和 `security_level` 有值

---

## 阶段 1：接口隔离 + 模块解耦

阶段 1 的所有步骤均在阶段 0 全部完成（`mvn clean install -DskipTests` 绿色）后才开始。

---

### 步骤 1.1 — framework 新建 security port 目录和接口文件

**动作：create**
**目录：** `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/`

**文件清单及签名骨架（按依赖顺序）：**

**文件 1：** `AccessScope.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

import java.util.Set;

/**
 * 知识库访问范围，检索路径单一状态源。
 * 使用 if instanceof 消费（Java 17，禁止 switch record pattern）。
 *
 * 示例消费模式：
 * <pre>
 * if (scope instanceof AccessScope.All) { // 全量放行，不过滤 }
 * if (scope instanceof AccessScope.Ids ids) { filterBy(ids.kbIds()); }
 * </pre>
 */
public sealed interface AccessScope
        permits AccessScope.All, AccessScope.Ids {

    /** 全量放行（SUPER_ADMIN 或系统态）。*/
    record All() implements AccessScope {}

    /**
     * 仅允许指定 KB ID 集合。
     * 空集表示无权限（未登录 / 无任何授权 KB）。
     */
    record Ids(Set<String> kbIds) implements AccessScope {}

    static AccessScope all() { return new All(); }
    static AccessScope ids(Set<String> kbIds) { return new Ids(kbIds); }
    static AccessScope empty() { return new Ids(Set.of()); }
}
```

**文件 2：** `SuperAdminMutationIntent.java`（从 bootstrap 迁来，内容 1:1）

```java
package com.nageoffer.ai.ragent.framework.security.port;

import java.util.List;

/**
 * Last SUPER_ADMIN invariant 模拟器的输入语义（Decision 3-M）。
 * 从 bootstrap/user/service/ 迁至 framework，供所有模块引用。
 * 4 种 mutation 各对应一个 record。
 */
public sealed interface SuperAdminMutationIntent
        permits SuperAdminMutationIntent.DeleteUser,
                SuperAdminMutationIntent.ReplaceUserRoles,
                SuperAdminMutationIntent.ChangeRoleType,
                SuperAdminMutationIntent.DeleteRole {

    record DeleteUser(String userId) implements SuperAdminMutationIntent {}
    record ReplaceUserRoles(String userId, List<String> newRoleIds) implements SuperAdminMutationIntent {}
    record ChangeRoleType(String roleId, String newRoleType) implements SuperAdminMutationIntent {}
    record DeleteRole(String roleId) implements SuperAdminMutationIntent {}
}
```

**文件 3：** `CurrentUserProbe.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

/**
 * 当前请求上下文的用户角色探针。
 * 只做"是/否"判断，不做权限决策。
 */
public interface CurrentUserProbe {

    /** 当前上下文是否是 SUPER_ADMIN。*/
    boolean isSuperAdmin();

    /** 当前上下文是否是 DEPT_ADMIN（任一部门）。*/
    boolean isDeptAdmin();

    /** 指定 userId 是否持有任一 SUPER_ADMIN 角色。*/
    boolean isUserSuperAdmin(String userId);
}
```

**文件 4：** `KbReadAccessPort.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

import com.nageoffer.ai.ragent.framework.context.Permission;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 知识库读取侧权限端口。
 * RAG 检索路径和知识库列表查询的授权入口。
 */
public interface KbReadAccessPort {

    /**
     * 获取当前用户的访问范围。
     * SUPER_ADMIN 返回 {@link AccessScope.All}；其他角色返回 {@link AccessScope.Ids}。
     * 未登录时调用方应传入 empty Ids（不调用此方法）。
     *
     * @param userId        当前用户 ID
     * @param minPermission 最低所需权限
     */
    AccessScope getAccessScope(String userId, Permission minPermission);

    /**
     * 校验当前用户对指定 KB 的 READ 权限，无权抛 ClientException。
     * SUPER_ADMIN 和系统态直接放行。
     */
    void checkReadAccess(String kbId);

    /**
     * 批量解析当前用户对一组 KB 的最高安全等级。
     * 返回 map 仅包含 kbIds 中用户实际拥有访问权的 KB。
     *
     * @param userId 当前用户 ID
     * @param kbIds  待解析的 KB ID 集合
     */
    Map<String, Integer> getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds);

    /**
     * @deprecated 迁移过渡用，新代码改用 {@link #getAccessScope}
     */
    @Deprecated
    default Set<String> getAccessibleKbIds(String userId) {
        throw new UnsupportedOperationException("use getAccessScope()");
    }
}
```

**文件 5：** `KbManageAccessPort.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

/**
 * 知识库写/管理侧权限端口。
 * knowledge 域 controller 和 service 的授权入口。
 */
public interface KbManageAccessPort {

    /** 校验当前用户对指定 KB 的 MANAGE 权限，无权抛 ClientException。*/
    void checkManageAccess(String kbId);

    /** 文档级管理权：doc → kb → checkManageAccess(kb.id)。*/
    void checkDocManageAccess(String docId);

    /**
     * 文档 security_level 修改专用。
     * 目前等同 checkDocManageAccess，保留独立方法以便未来加 level-specific 规则。
     */
    void checkDocSecurityLevelAccess(String docId, int newLevel);

    /** 校验当前用户是否有权管理指定 KB 的角色绑定。*/
    void checkKbRoleBindingAccess(String kbId);

    /**
     * 创建 KB 时的 deptId 解析器。
     * SUPER_ADMIN 返回 requestedDeptId（空则 fallback GLOBAL_DEPT_ID）；
     * DEPT_ADMIN 强制 self.deptId；USER 和未登录 → 抛 ClientException。
     */
    String resolveCreateKbDeptId(String requestedDeptId);
}
```

**文件 6：** `UserAdminGuard.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

import java.util.List;

/**
 * 用户与角色管理操作的授权守卫。
 * user/role/dept controller 的授权入口。
 */
public interface UserAdminGuard {

    /** 校验当前用户是 SUPER_ADMIN 或 DEPT_ADMIN，否则抛异常。*/
    void checkAnyAdminAccess();

    /**
     * 创建用户授权。
     * SUPER_ADMIN 任何 deptId；DEPT_ADMIN 仅 targetDeptId == self.deptId 且不含 SUPER_ADMIN 角色。
     */
    void checkCreateUserAccess(String targetDeptId, List<String> roleIds);

    /** 改/删用户授权。DEPT_ADMIN 仅当 target.deptId == self.deptId。*/
    void checkUserManageAccess(String targetUserId);

    /**
     * 分配角色授权。
     * SUPER_ADMIN 任何；DEPT_ADMIN 仅当 target.deptId == self.deptId 且 newRoleIds 无 SUPER_ADMIN 角色。
     */
    void checkAssignRolesAccess(String targetUserId, List<String> newRoleIds);

    /** 校验 DEPT_ADMIN 分配角色的合法性（不可分配 SUPER_ADMIN/DEPT_ADMIN 角色，不可超自身天花板）。*/
    void validateRoleAssignment(List<String> roleIds);
}
```

**文件 7：** `SuperAdminInvariantGuard.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

/**
 * Last SUPER_ADMIN 系统级硬不变量守卫（Decision 3-M）。
 * 所有改变 SUPER_ADMIN 数量的 mutation 路径在执行前必须调用此守卫。
 */
public interface SuperAdminInvariantGuard {

    /** 当前系统内有效 SUPER_ADMIN 用户数量。*/
    int countActiveSuperAdmins();

    /**
     * Post-mutation 模拟器：返回 mutation 执行后剩余的有效 SUPER_ADMIN 用户数量。
     * 调用方用 {@code simulateActiveSuperAdminCountAfter(intent) < 1} 判断是否拒绝。
     */
    int simulateActiveSuperAdminCountAfter(SuperAdminMutationIntent intent);
}
```

**文件 8：** `KbAccessCacheAdmin.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

/**
 * 知识库权限缓存管理端口。
 * 供 role service 在角色-KB 关系变更后调用。
 */
public interface KbAccessCacheAdmin {

    /** 清除指定用户的所有权限缓存（kb_access:、kb_access:dept:、kb_security_level:）。*/
    void evictCache(String userId);
}
```

**文件 9：** `KbMetadataReader.java`

```java
package com.nageoffer.ai.ragent.framework.security.port;

import java.util.Collection;
import java.util.Set;

/**
 * 知识库元数据读取端口。
 * 消除 user 域对 knowledge 域 Mapper 的直接依赖（反向依赖）。
 * 实现放 knowledge 域（KbMetadataReaderImpl）。
 */
public interface KbMetadataReader {

    /** 获取 KB 的所属部门 ID。KB 不存在时返回 null。*/
    String getKbDeptId(String kbId);

    /** 获取文档所属 KB 的 ID。文档不存在时返回 null。*/
    String getKbIdOfDoc(String docId);

    /** 指定 KB 是否存在(已删除视为不存在)。*/
    boolean kbExists(String kbId);

    /** 返回所有未删除 KB 的 ID 集合(SUPER_ADMIN 全量可见路径使用)。*/
    Set<String> listAllKbIds();

    /** 返回指定部门下所有未删除 KB 的 ID 集合。*/
    Set<String> listKbIdsByDeptId(String deptId);

    /** 过滤出 kbIds 中实际存在(未删除)的 KB ID。*/
    Set<String> filterExistingKbIds(Collection<String> kbIds);

    /** 过滤出 kbIds 中 dept_id == deptId 的 KB ID。*/
    Set<String> filterKbIdsByDept(Collection<String> kbIds, String deptId);
}
```

**验证点：** `mvn -pl framework install -DskipTests` 通过（framework 本身无业务依赖，应干净编译）

---

### 步骤 1.2 — `SuperAdminMutationIntent` 迁移全 checklist

**动作：delete + modify（共 6 处）**

| 序号 | 操作 | 文件 | 说明 |
|------|------|------|------|
| 1 | create | `framework/.../security/port/SuperAdminMutationIntent.java` | 步骤 1.1 已完成 |
| 2 | delete | `bootstrap/.../user/service/SuperAdminMutationIntent.java` | 删除旧文件 |
| 3 | modify import | `bootstrap/.../user/service/KbAccessService.java:169` | 将 `com.nageoffer.ai.ragent.user.service.SuperAdminMutationIntent` 替换为 `com.nageoffer.ai.ragent.framework.security.port.SuperAdminMutationIntent` |
| 4 | modify import | `bootstrap/.../user/service/impl/KbAccessServiceImpl.java` 顶部 | 同上 |
| 5 | modify import | `bootstrap/.../user/service/impl/UserServiceImpl.java` | 同上 |
| 6 | modify import | `bootstrap/.../user/service/impl/RoleServiceImpl.java`（3处引用） | 同上 |

**依赖：** 步骤 1.1 framework 安装完成

**验证点：** `grep -rn 'user\.service\.SuperAdminMutationIntent' bootstrap/src/` 零命中；`mvn -pl bootstrap clean compile` 通过

---

### 步骤 1.3 — `KbMetadataReaderImpl` 实现放 knowledge 域

**动作：create**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbMetadataReaderImpl.java`

```java
package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KbMetadataReaderImpl implements KbMetadataReader {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Override
    public String getKbDeptId(String kbId) { throw new UnsupportedOperationException("TODO"); }

    @Override
    public String getKbIdOfDoc(String docId) { throw new UnsupportedOperationException("TODO"); }

    @Override
    public boolean kbExists(String kbId) { throw new UnsupportedOperationException("TODO"); }

    @Override
    public Set<String> listAllKbIds() { throw new UnsupportedOperationException("TODO"); }

    @Override
    public Set<String> listKbIdsByDeptId(String deptId) { throw new UnsupportedOperationException("TODO"); }

    @Override
    public Set<String> filterExistingKbIds(Collection<String> kbIds) { throw new UnsupportedOperationException("TODO"); }

    @Override
    public Set<String> filterKbIdsByDept(Collection<String> kbIds, String deptId) { throw new UnsupportedOperationException("TODO"); }
}
```

**依赖：** 步骤 1.1（framework port 安装完成）

**风险：** `KbMetadataReaderImpl` 的 `TODO` 会在调用时抛出 `UnsupportedOperationException`，必须在步骤 1.4 改完 `KbAccessServiceImpl` 之前实现方法体，否则 runtime 炸

**规则：** 此步骤的 7 个 TODO 必须在步骤 1.4 提交之前补全实现，二者在同一 PR 内提交

---

### 步骤 1.4 — `KbAccessServiceImpl` 注入 `KbMetadataReader`，消除 knowledge Mapper 依赖

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

**改动 1：** 删除字段（第 71、75 行）：

```java
// 删除以下两行：
private final KnowledgeBaseMapper knowledgeBaseMapper;
private final KnowledgeDocumentMapper knowledgeDocumentMapper;
```

**改动 2：** 添加字段：

```java
private final KbMetadataReader kbMetadataReader;
```

**改动 3：** 删除 import `com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper` 和 `KnowledgeDocumentMapper`，以及相关的 `KnowledgeBaseDO`、`KnowledgeDocumentDO` import（如其他方法无需）

**改动 4：** 替换 4 处 `knowledgeBaseMapper.selectList` 调用（分别在第 82-85、132-136、164-167、355-359 行及 `checkManageAccess`/`checkKbRoleBindingAccess`/`getMaxSecurityLevelForKb`/`getMaxSecurityLevelsForKbs` 中的 `knowledgeBaseMapper.selectById`）为 `kbMetadataReader` 对应方法。

**改动 5：** 同样替换 `knowledgeDocumentMapper.selectById` 调用（`checkDocManageAccess` 方法中）：

```java
// 旧：KnowledgeDocumentDO doc = knowledgeDocumentMapper.selectById(docId);
// 新：
String kbId = kbMetadataReader.getKbIdOfDoc(docId);
if (kbId == null) throw new ClientException("文档不存在: " + docId);
checkManageAccess(kbId);
```

**验证点：** `grep -n 'knowledgeBaseMapper\|knowledgeDocumentMapper' KbAccessServiceImpl.java` 零命中；`mvn -pl bootstrap clean compile` 通过

---

### 步骤 1.5 — `KbAccessServiceImpl` 实现新端口接口

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

修改类声明行：

```java
public class KbAccessServiceImpl implements KbAccessService,
        CurrentUserProbe, KbReadAccessPort, KbManageAccessPort,
        UserAdminGuard, SuperAdminInvariantGuard, KbAccessCacheAdmin {
```

**实现要点：**

- `KbReadAccessPort.getAccessScope(userId, minPermission)`：调用现有 `getAccessibleKbIds(userId, minPermission)` 逻辑，SUPER_ADMIN 返回 `AccessScope.all()`，其他返回 `AccessScope.ids(kbIds)`
- `KbReadAccessPort.checkReadAccess(kbId)`：委托现有 `checkAccess(kbId)` 方法体
- `KbManageAccessPort.checkManageAccess(kbId)` / `checkDocManageAccess` / `checkDocSecurityLevelAccess` / `checkKbRoleBindingAccess` / `resolveCreateKbDeptId`：方法体已存在，加 `@Override` 即可
- `UserAdminGuard` 的 5 个方法：方法体已存在，加 `@Override` 即可
- `SuperAdminInvariantGuard.countActiveSuperAdmins` / `simulateActiveSuperAdminCountAfter`：方法体已存在，加 `@Override` 即可
- `KbAccessCacheAdmin.evictCache`：方法体已存在，加 `@Override` 即可

**旧 `KbAccessService` 接口保留**，改为扩展新端口（但本次 PR 不强制修改接口声明，47 个调用点分阶段迁移）。

**验证点：** 无编译错误；`KbAccessServiceImpl` 的 Spring bean 可以同时以多个新端口类型注入

---

### 步骤 1.6 — `MetadataFilterBuilder` 抽 bean（3 处 static 调用同批改）

**动作：create + modify**

**新文件 1：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/filter/MetadataFilterBuilder.java`

```java
package com.nageoffer.ai.ragent.rag.core.retrieve.filter;

import com.nageoffer.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;

import java.util.List;

/**
 * 元数据过滤条件构建器。
 * 将 per-KB security_level 过滤逻辑从 static 方法提取为可注入 bean，
 * 方便测试和 AuthzPostProcessor 的白盒验证。
 */
public interface MetadataFilterBuilder {

    /**
     * 根据检索上下文和目标 KB ID 构建元数据过滤条件。
     *
     * @param ctx  当前检索上下文（包含 kbSecurityLevels 等授权信息）
     * @param kbId 目标知识库 ID
     * @return 过滤条件列表，空列表表示不过滤
     */
    List<MetadataFilter> build(SearchContext ctx, String kbId);
}
```

**新文件 2：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/filter/DefaultMetadataFilterBuilder.java`

```java
package com.nageoffer.ai.ragent.rag.core.retrieve.filter;

import com.nageoffer.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认实现：按 kb 查表 security_level，生成 LTE_OR_MISSING 过滤条件。
 * 迁自 MultiChannelRetrievalEngine.buildMetadataFilters（static 方法）。
 */
@Component
public class DefaultMetadataFilterBuilder implements MetadataFilterBuilder {

    private static final String SECURITY_LEVEL_FIELD = "security_level";

    @Override
    public List<MetadataFilter> build(SearchContext ctx, String kbId) {
        List<MetadataFilter> filters = new ArrayList<>();
        if (kbId == null || ctx.getKbSecurityLevels() == null) {
            return filters;
        }
        Integer level = ctx.getKbSecurityLevels().get(kbId);
        if (level != null) {
            filters.add(new MetadataFilter(
                    SECURITY_LEVEL_FIELD,
                    MetadataFilter.FilterOp.LTE_OR_MISSING,
                    level));
        }
        return filters;
    }
}
```

**修改 3 处 static 调用（必须同批）：**

**modify** `MultiChannelRetrievalEngine.java`：
- 注入 `MetadataFilterBuilder metadataFilterBuilder`（删除 `KbAccessService` 注入，因为已不再调用 static 方法）
- 第 93 行和第 181 行将 `MultiChannelRetrievalEngine.buildMetadataFilters(context, kbId)` 替换为 `metadataFilterBuilder.build(context, kbId)`
- 删除 `public static List<MetadataFilter> buildMetadataFilters(...)` 方法（第 275-288 行）

**modify** `VectorGlobalSearchChannel.java` 第 181 行：
- 注入 `MetadataFilterBuilder`（构造函数注入）
- 调用 `metadataFilterBuilder.build(context, kb.getId())` 替代 static 调用

**modify** `IntentParallelRetriever.java` 第 64 行：
- 注入 `MetadataFilterBuilder`（构造函数参数）
- 调用 `metadataFilterBuilder.build(context, nodeScore.getNode().getKbId())` 替代 static 调用

**注意：** `IntentParallelRetriever` 是非 Spring 管理的类（`new IntentParallelRetriever(...)`），在 `IntentDirectedSearchChannel` 构造函数中实例化，需将 `MetadataFilterBuilder` 作为构造参数传入链：`IntentDirectedSearchChannel` → `IntentParallelRetriever`

**验证点：** `grep -rn 'buildMetadataFilters' bootstrap/src/main/java/` 除 `DefaultMetadataFilterBuilder` 自身外零命中；`mvn -pl bootstrap clean compile` 通过

---

## 阶段 2：检索侧单一状态源 + 纵深防御

阶段 2 在阶段 1 全部完成（`mvn clean install -DskipTests` 绿色）后开始。

---

### 步骤 2.1 — `AccessScope` 贯通到 RAGChatServiceImpl

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`

**现状**（第 116-119 行）：

```java
Set<String> accessibleKbIds = null;
if (UserContext.hasUser() && userId != null && !kbAccessService.isSuperAdmin()) {
    accessibleKbIds = kbAccessService.getAccessibleKbIds(userId);
}
```

**替换为：**

```java
// 注入：private final KbReadAccessPort kbReadAccess;
AccessScope accessScope;
if (UserContext.hasUser() && userId != null) {
    accessScope = kbReadAccess.getAccessScope(userId, Permission.READ);
} else {
    accessScope = AccessScope.empty();  // 未登录 → 空 ids，不是 All，fail-closed
}
```

**⚠️ 隐含的安全修复（设计决策，非 bug）：**

当前 `RAGChatServiceImpl.java:115-118` 的行为：SUPER_ADMIN 走 `accessibleKbIds=null`，**未登录态也走 `accessibleKbIds=null`（两者语义等价！）** 。下游 channel 见 null 直接放行，等于未登录用户能访问所有 KB —— 这是一个潜在的 RBAC 绕过 bug（实际被 `SaInterceptor.checkLogin` 挡住，但深度防御层面不应依赖拦截器）。

新实现明确区分：`AccessScope.All`（SUPER_ADMIN，全量）vs `AccessScope.empty()`（未登录，fail-closed 空集）。这是设计决策，code review 时不应视为"不必要的行为变更"。

**传播链：** 将 `accessScope` 向下传递至 `retrievalEngine.retrieve(subIntents, topK, accessScope, knowledgeBaseId)`

**注入：** 在 `RAGChatServiceImpl` 的字段声明中新增 `private final KbReadAccessPort kbReadAccess;`（通过 `@RequiredArgsConstructor`），保留原有的 `KbAccessService kbAccessService`（用于 `checkAccess`、`checkAnyAdminAccess` 等，分阶段迁移）

---

### 步骤 2.2 — `RetrievalEngine` 签名换 `AccessScope`

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`

第 83 行 `retrieve` 方法签名：

```java
public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK,
                                  AccessScope accessScope, String knowledgeBaseId) { ... }
```

内部 `buildSubQuestionContext` 和 `retrieveAndRerank` 的签名同步替换 `Set<String> accessibleKbIds` 为 `AccessScope accessScope`，透传至 `multiChannelRetrievalEngine.retrieveKnowledgeChannels`。

**`MultiChannelRetrievalEngine.retrieveKnowledgeChannels` 签名同步：**

```java
public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK,
                                                       AccessScope accessScope, String knowledgeBaseId) { ... }
```

**`buildSearchContext` 更新：**

```java
private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK, AccessScope accessScope) {
    String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();

    // 仅对 Ids scope 预解析安全等级（All scope 的 SUPER_ADMIN 不需要）
    Map<String, Integer> kbSecurityLevels;
    if (accessScope instanceof AccessScope.Ids ids && !ids.kbIds().isEmpty()) {
        kbSecurityLevels = kbReadAccess.getMaxSecurityLevelsForKbs(UserContext.getUserId(), ids.kbIds());
    } else {
        kbSecurityLevels = Collections.emptyMap();
    }

    return SearchContext.builder()
            .originalQuestion(question)
            .rewrittenQuestion(question)
            .intents(subIntents)
            .topK(topK)
            .accessScope(accessScope)    // 新字段
            .kbSecurityLevels(kbSecurityLevels)
            .build();
}
```

注意：在 `if (accessScope instanceof AccessScope.Ids ids && ...)` 中使用 Java 17 的模式变量（`ids` 是模式绑定变量），不是 switch 解构，符合 Java 17 语法。

---

### 步骤 2.3 — `SearchContext` 替换字段

**动作：modify**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java`

**删除** 第 67 行的 `private Set<String> accessibleKbIds;` 字段（包含注释）

**新增** `private AccessScope accessScope;` 字段（含 import）

**修改 2 个 channel 中读 `accessibleKbIds` 的位置：**

`IntentDirectedSearchChannel.java` 第 161-168 行的 RBAC filter 改为：

```java
AccessScope scope = context.getAccessScope();
if (scope instanceof AccessScope.Ids ids) {
    kbIntents = kbIntents.stream()
            .filter(ns -> {
                String kbId = ns.getNode().getKbId();
                return kbId == null || ids.kbIds().contains(kbId);
            })
            .toList();
}
// AccessScope.All：不过滤，全量放行
```

`VectorGlobalSearchChannel.java` `getAccessibleKBs` 方法：

```java
private List<KnowledgeBaseDO> getAccessibleKBs(SearchContext context) {
    List<KnowledgeBaseDO> kbs = knowledgeBaseMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeBaseDO.class));
    AccessScope scope = context.getAccessScope();
    if (scope instanceof AccessScope.Ids ids) {
        kbs = kbs.stream()
                .filter(kb -> ids.kbIds().contains(kb.getId()))
                .toList();
    }
    // AccessScope.All：不过滤
    return kbs.stream()
            .filter(kb -> kb.getCollectionName() != null && !kb.getCollectionName().isBlank())
            .toList();
}
```

**验证点：** `grep -rn 'accessibleKbIds' bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/` 零命中

---

### 步骤 2.4 — `RetrievedChunk` 扩 `kbId` 和 `securityLevel`

**动作：modify**
**文件：** `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunk.java`

在现有 3 个字段（`id`、`text`、`score`）之后新增：

```java
/** 所属知识库 ID（从 OpenSearch metadata.kb_id 回填，AuthzPostProcessor 使用）。*/
private String kbId;

/** 文档安全等级（从 OpenSearch metadata.security_level 回填，AuthzPostProcessor 使用）。*/
private Integer securityLevel;
```

**修改 `OpenSearchRetrieverService.toRetrievedChunk`（第 252-268 行）：**

```java
@SuppressWarnings("unchecked")
private RetrievedChunk toRetrievedChunk(Map<String, Object> hit) {
    Map<String, Object> source = (Map<String, Object>) hit.get("_source");
    String id = ...;     // 同现有逻辑
    String content = ...; // 同现有逻辑
    float score = ...;    // 同现有逻辑

    // 新增：从 metadata 回填授权字段
    String kbId = null;
    Integer securityLevel = null;
    if (source != null) {
        Object meta = source.get("metadata");
        if (meta instanceof Map<?, ?> metaMap) {
            Object kb = metaMap.get("kb_id");
            if (kb != null && !kb.toString().isBlank()) kbId = kb.toString();
            Object sl = metaMap.get("security_level");
            if (sl instanceof Number n) securityLevel = n.intValue();
        }
    }

    return RetrievedChunk.builder()
            .id(id)
            .text(content)
            .score(score)
            .kbId(kbId)
            .securityLevel(securityLevel)
            .build();
}
```

**修改 `MilvusRetrieverService`：** 将 3 参 `new RetrievedChunk(id, content, score)` 改为 builder（`kbId/securityLevel` 留 null，加注释 `// Milvus dev-only: kbId/securityLevel not populated`）

**修改 `PgRetrieverService`：** 已使用 builder，仅添加两个 null 字段即可（加注释同上）

**依赖：** 步骤 2.3 `SearchContext` 修改完成，且 framework 重新安装（`mvn -pl framework install -DskipTests`）

**风险：** `RetrievedChunk` 是 framework 模块的类，改完后需 `mvn -pl framework install -DskipTests` 再编译 bootstrap，否则 bootstrap 持有旧版 class

---

### 步骤 2.5 — 新增 `AuthzPostProcessor`

**动作：create**
**文件：** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/AuthzPostProcessor.java`

```java
package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 授权后置处理器（纵深防御）。
 * <p>
 * 在 dedup/rerank 之前以 order=0 执行，对 retriever 侧授权的二次校验：
 * <ol>
 *   <li>{@code kbId == null} → fail-closed drop（防 Milvus/Pg 穿透）</li>
 *   <li>scope 为 Ids 且 kbId 不在白名单 → drop</li>
 *   <li>securityLevel > 用户天花板 → drop</li>
 * </ol>
 * <p>
 * 若 dropped > 0，以 ERROR 级别记录 — 此日志出现表示 retriever 过滤存在漏洞。
 */
@Slf4j
@Component
public class AuthzPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() { return "Authz"; }

    @Override
    public int getOrder() { return 0; }  // 最靠前，在 DeduplicationPostProcessor(order=1) 之前

    @Override
    public boolean isEnabled(SearchContext context) {
        return UserContext.hasUser();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        AccessScope scope = context.getAccessScope();
        int before = chunks.size();

        List<RetrievedChunk> filtered = chunks.stream()
                .filter(chunk -> isAllowed(chunk, scope, context))
                .collect(Collectors.toList());

        int dropped = before - filtered.size();
        if (dropped > 0) {
            log.error("AuthzPostProcessor dropped {} chunks – retriever filter failure detected. " +
                      "Scope type: {}", dropped, scope.getClass().getSimpleName());
        }
        return filtered;
    }

    private boolean isAllowed(RetrievedChunk chunk, AccessScope scope, SearchContext context) {
        String kbId = chunk.getKbId();

        // Rule 1: kbId == null → fail-closed（防非 OpenSearch 后端穿透）
        if (kbId == null || kbId.isBlank()) {
            log.warn("AuthzPostProcessor: dropping chunk id={} – kbId is null/blank (non-OpenSearch backend?)", chunk.getId());
            return false;
        }

        // Rule 2: Ids scope 白名单检查
        if (scope instanceof AccessScope.Ids ids) {
            if (!ids.kbIds().contains(kbId)) {
                return false;
            }
        }
        // AccessScope.All → 跳过白名单检查

        // Rule 3: security_level 天花板检查
        Integer level = chunk.getSecurityLevel();
        if (level != null) {
            Integer ceiling = context.getKbSecurityLevels().get(kbId);
            // ceiling == null 表示系统态或 SUPER_ADMIN（不限），跳过过滤
            if (ceiling != null && level > ceiling) {
                return false;
            }
        }

        return true;
    }
}
```

**验证点：** Spring context 启动后，`postProcessors` list 中 `AuthzPostProcessor` 排在 `DeduplicationPostProcessor` 之前（order 0 < 1）

---

### 步骤 2.6 — Milvus/Pg 锁定为非默认 + 配置校验

**动作：modify（2 处）+ create（1 处）**

**modify** `MilvusRetrieverService.java` 第 43 行：

```java
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = false)
```

（原为 `matchIfMissing = true`，改为 `false`，防止未配置时意外激活 Milvus）

**modify** `PgRetrieverService.java`：确认 `matchIfMissing = false`（原本就是，无 matchIfMissing 属性则默认 false，可保持不动）

**create** `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java`：

```java
package com.nageoffer.ai.ragent.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量库类型配置校验器。
 * <p>
 * 非 opensearch 配置时打印 WARN，提醒开发者 AuthzPostProcessor 会对 kbId==null 的 chunk fail-closed。
 */
@Slf4j
@Component
public class RagVectorTypeValidator {

    @Value("${rag.vector.type:opensearch}")
    private String vectorType;

    @PostConstruct
    public void validate() {
        if (!"opensearch".equalsIgnoreCase(vectorType)) {
            log.warn("RAG vector backend is '{}' (not opensearch). " +
                     "AuthzPostProcessor will fail-close all chunks where kbId is null " +
                     "in authenticated sessions. This is a dev-only configuration – " +
                     "do NOT use in production without OpenSearch authz support.", vectorType);
        }
    }
}
```

---

## 关键接口骨架汇总

| 接口/类 | 绝对路径 |
|---------|---------|
| `AccessScope` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/AccessScope.java` |
| `SuperAdminMutationIntent` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/SuperAdminMutationIntent.java` |
| `CurrentUserProbe` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/CurrentUserProbe.java` |
| `KbReadAccessPort` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbReadAccessPort.java` |
| `KbManageAccessPort` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbManageAccessPort.java` |
| `UserAdminGuard` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/UserAdminGuard.java` |
| `SuperAdminInvariantGuard` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/SuperAdminInvariantGuard.java` |
| `KbAccessCacheAdmin` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbAccessCacheAdmin.java` |
| `KbMetadataReader` | `E:/AIProject/ragent/framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbMetadataReader.java` |
| `MetadataFilterBuilder` | `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/filter/MetadataFilterBuilder.java` |
| `DefaultMetadataFilterBuilder` | `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/filter/DefaultMetadataFilterBuilder.java` |
| `KbMetadataReaderImpl` | `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbMetadataReaderImpl.java` |
| `AuthzPostProcessor` | `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/AuthzPostProcessor.java` |
| `RagVectorTypeValidator` | `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java` |

---

## 风险点（按阶段）

### 阶段 0 风险

**0.2 接口扩签名造成编译中断窗口：** `VectorStoreService` 签名改完后，若 `OpenSearchVectorStoreService`、`MilvusVectorStoreService`、`PgVectorStoreService` 未同批实现，CI 立即红。步骤 0.2、0.3a、0.3b 必须单次 commit 提交，不允许分开。

**0.5 IndexerNode round-trip 修复的 VectorChunk.metadata 字段：** 若 `VectorChunk` 没有 `metadata` 字段（需先查 `E:/AIProject/ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/VectorChunk.java`），则步骤 0.5a 必须先做。忘做会导致 `NullPointerException` 在 IndexerNode 运行时。

**0.6(C) 独立 ingestion 任务无 kbId：** `IngestionTaskServiceImpl` 启动的独立 pipeline 任务的 chunk 在 OpenSearch 中写入 `kb_id=""` 和 `security_level=0`。这些 chunk 在 `AuthzPostProcessor` 中会因 `kbId==null or blank` 被 fail-closed drop，永远不会出现在 authenticated session 的问答结果中。这是预期行为（已在步骤 0.6 rationale 记录），但如有业务场景需要这些 chunk 可见，必须在独立 pipeline API 层接收 `kbId` 参数。

**0.6 `securityLevel` null 兜底：** `KnowledgeDocumentDO.securityLevel` 字段可能为 null（历史数据），所有调用点已要求加 `!= null ? : 0` 兜底，漏一处则写入 `security_level=null`，`AuthzPostProcessor` 的 `if (level != null)` 判断不会过滤，存在级别越权风险。执行人需逐一核对 6 个调用点。

### 阶段 1 风险

**1.3 + 1.4 `KbMetadataReaderImpl` 实现 TODO 顺序风险：** `KbMetadataReaderImpl` 的 TODO 方法如果未实现就被 `KbAccessServiceImpl` 调用，会在运行时抛 `UnsupportedOperationException`，导致所有权限检查崩溃。规则：步骤 1.3 和 1.4 必须在同一 PR 内，且 1.3 的 7 个 TODO 必须全部实现（不允许留 UnsupportedOperationException）。

**1.4 `checkDocManageAccess` 从 `knowledgeDocumentMapper` 切换到 `KbMetadataReader` 的边界变化：** 现有实现（`KbAccessServiceImpl` 第 498 行）先查 doc 再查 kb，如果 doc 不存在抛 `ClientException("文档不存在")`。新实现通过 `kbMetadataReader.getKbIdOfDoc(docId)` 返回 null 时抛同样异常。`KbMetadataReaderImpl.getKbIdOfDoc` 实现时必须使用带 `@TableLogic` 过滤的 MP 查询（已删除的文档返回 null），不可使用 raw SQL 绕过逻辑删除。

**1.5 `KbAccessServiceImpl` 实现多个端口时的 Spring bean 注入歧义：** 下游 47 个调用点当前注入 `KbAccessService`，新的端口接口（`KbReadAccessPort` 等）由同一个 bean 实现。切换调用点时，需逐一将字段类型从 `KbAccessService` 改为对应端口接口，Spring 按接口类型解析，不存在歧义（一个 bean 实现多个接口）。注意 `@Qualifier` 不需要，但如果在同一个类中同时注入两个端口，须确认两个字段都指向同一 bean。

**1.6 `IntentParallelRetriever` 非 Spring 管理类的 `MetadataFilterBuilder` 传递：** `IntentParallelRetriever` 在 `IntentDirectedSearchChannel` 构造函数中 `new` 出来，不是 Spring bean。`MetadataFilterBuilder` 需通过 `IntentDirectedSearchChannel` 的构造函数参数传入，再转传给 `IntentParallelRetriever`。如果 `IntentDirectedSearchChannel` 的构造函数注入顺序或参数列表有变，需同步更新。

### 阶段 2 风险

**2.1 `RAGChatServiceImpl` 双字段共存窗口：** 步骤 2.1 后，`RAGChatServiceImpl` 同时持有 `KbAccessService kbAccessService`（旧，用于 `checkAccess`/`checkAnyAdminAccess`）和 `KbReadAccessPort kbReadAccess`（新）。在 47 个调用点完成迁移前，这个双字段状态是正常的过渡态。代码审查时不要视为 bug。

**2.3 `SearchContext` 删除 `accessibleKbIds` 字段的破坏范围：** `SearchContext.accessibleKbIds` 被删除后，任何通过 `ctx.getAccessibleKbIds()` 访问的代码立即编译失败。需在全局搜索确认无遗漏：`grep -rn 'getAccessibleKbIds\|accessibleKbIds' bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/`，除已知 3 处（2 个 channel + `buildSearchContext`）外应零命中。

**2.4 framework 模块修改后 bootstrap 编译顺序：** `RetrievedChunk` 在 framework，bootstrap 依赖 framework 的 jar。每次修改 framework 后必须先 `mvn -pl framework install -DskipTests`，再 `mvn -pl bootstrap clean compile`，不可跳过中间步骤。

**2.5 `AuthzPostProcessor` 对空 `kbId` 的 false-positive：** 独立 ingestion 任务写入 `kb_id=""` 的 chunk（步骤 0.6(C) 设计决策），`AuthzPostProcessor` 的 Rule 1 会 drop 这些 chunk 并打 warn 日志（不是 error）。如果这类 chunk 数量大，日志噪音会很高。建议在 warn 日志中添加 `chunkId` 前 8 位以便区分。

**2.5 `AuthzPostProcessor` 与现有 `DeduplicationPostProcessor` 的 order 冲突：** `AuthzPostProcessor.getOrder()=0`，`DeduplicationPostProcessor.getOrder()=1`。Spring 的 `List<SearchResultPostProcessor>` 注入顺序依赖 order，需确认 `executePostProcessors` 调用的 `sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))` 已存在（实际代码第 208-211 行确认有此逻辑）。

---

## PR 拆分建议

| PR | 步骤 | 标题 | 可回滚 | 审查大小 |
|----|------|------|--------|---------|
| PR-A | 0.1 + 0.2 + 0.3a + 0.3b + 0.4 + 0.5 + 0.5a + 0.6(全部) | `feat: 写路径 kb_id/security_level 注入 + VectorStoreService 签名扩展` | 是（清库即可） | 大，约 8 文件 |
| PR-B | 0.7（运维操作，无代码） | 无 PR，直接运维脚本 | — | — |
| PR-C | 1.1（framework 仅新增接口） | `feat(framework): 添加 security port 接口层` | 是（删除即可） | 小，纯新增 |
| PR-D | 1.2（迁移 SuperAdminMutationIntent） | `refactor: 迁移 SuperAdminMutationIntent 至 framework` | 是 | 极小，仅 import 变更 |
| PR-E | 1.3 + 1.4（KbMetadataReader 实现 + KbAccessServiceImpl 去 Mapper） | `refactor: 消除 user→knowledge 反向 Mapper 依赖` | 是 | 中，约 3 文件 |
| PR-F | 1.5（KbAccessServiceImpl 实现新端口） | `refactor: KbAccessServiceImpl 实现拆分后的 ISP 端口` | 是 | 小，仅类声明变更 |
| PR-G | 1.6（MetadataFilterBuilder 抽 bean） | `refactor: 将 buildMetadataFilters 从 static 抽为可注入 bean` | 是 | 中，约 5 文件 |
| PR-H | 2.1 + 2.2 + 2.3（AccessScope 贯通检索链） | `feat: AccessScope 替换 Set<String> 成检索单一状态源` | 是 | 大，约 6 文件 |
| PR-I | 2.4（RetrievedChunk 扩字段 + Retriever 回填） | `feat: RetrievedChunk 回填 kbId/securityLevel` | 是 | 中，约 4 文件（跨模块） |
| PR-J | 2.5 + 2.6（AuthzPostProcessor + 配置校验） | `feat(security): 添加 AuthzPostProcessor 纵深防御` | 是（删除 Component 注解即可关闭） | 小，2 个新文件 |

**PR 合并顺序约束：** A → B（运维）→ C → D → E → F → G → H → I → J

- A 和 C 可并行（无代码依赖），但 C 必须在 D/E/F/G 之前合并进 framework
- D 必须在 E 之前（E 引用 framework SuperAdminMutationIntent）
- H 必须在 I 之前（I 的 OpenSearchRetrieverService 改动依赖 SearchContext.accessScope 字段）
- I 必须在 J 之前（J 的 AuthzPostProcessor 使用 RetrievedChunk.kbId）

---

## 验收清单

| 检查 | 期望 |
|---|---|
| `mvn -pl framework install` | 成功 |
| `mvn -pl bootstrap clean compile` | 成功，无未解析 symbol |
| `grep -rn 'buildMetadataFilters' bootstrap/` | 仅命中新 `DefaultMetadataFilterBuilder` 自身 |
| `grep -rn 'knowledgeBaseMapper\|knowledgeDocumentMapper' KbAccessServiceImpl.java` | 零命中 |
| `grep -rn 'accessibleKbIds' bootstrap/src/main/java/.../rag/core/retrieve/` | 零命中（SearchContext 内部字段已删） |
| `grep -rEn 'case AccessScope\.(All\|Ids)' bootstrap/` | 零命中（Java 17 禁用 record pattern for switch） |
| `curl http://localhost:9201/<col>/_mapping` | 包含 `metadata.properties.kb_id: keyword` |
| 任取 chunk `curl http://localhost:9201/<col>/_doc/<id>` | `_source.metadata.kb_id` + `security_level` 有值 |
| `POST /knowledge-base/docs/{docId}/chunks`（父密级=3） | 新 chunk OS 写入 `security_level=3`，非 null |
| 独立 ingestion 任务跑完 | chunk metadata 完整（kb_id / security_level / task_id / source_type） |
| SUPER_ADMIN chat | 正常返回，无 `AuthzPostProcessor dropped` ERROR |
| 普通用户 chat（跨权限 KB） | `AuthzPostProcessor dropped` 为 0（retriever 已过滤），结果符合权限边界 |
| 手动破坏 `MetadataFilterBuilder` 使其不加过滤 | `AuthzPostProcessor dropped > 0` ERROR 可见 |
