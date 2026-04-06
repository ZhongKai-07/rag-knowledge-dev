# OpenSearch 迁移 + RBAC 知识库权限体系 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Milvus/Pg 向量引擎适配层基础上新增 OpenSearch 实现（含混合查询），并引入 RBAC 知识库级别权限体系。

**Architecture:** 复用 `VectorStoreAdmin` / `VectorStoreService` / `RetrieverService` 三层接口，新增 OpenSearch 实现类通过 `@ConditionalOnProperty` 配置切换。RBAC 采用增量三表（`t_role` / `t_role_kb_relation` / `t_user_role`）管理知识库可见性，保留 `t_user.role` 为系统角色不变。

**Tech Stack:** opensearch-java 官方客户端, OpenSearch 2.10+ (Docker 本地 / AWS 生产), Spring Boot 3.5, MyBatis Plus, Sa-Token, Redis, React 18 + Zustand

**Spec:** `docs/superpowers/specs/2026-04-07-opensearch-migration-and-rbac-design.md`

---

## File Structure

### Phase 1: OpenSearch 向量引擎

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `resources/docker/opensearch-stack.compose.yaml` | 本地 OpenSearch Docker 环境 |
| Create | `bootstrap/src/main/java/.../rag/config/OpenSearchProperties.java` | OpenSearch 配置属性 |
| Create | `bootstrap/src/main/java/.../rag/config/OpenSearchConfig.java` | 客户端 Bean + Pipeline 初始化 |
| Create | `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreAdmin.java` | Index 创建与管理 |
| Create | `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreService.java` | Chunk CRUD |
| Create | `bootstrap/src/main/java/.../rag/core/retrieve/OpenSearchRetrieverService.java` | 混合查询检索 |
| Modify | `pom.xml` (parent) | 新增 opensearch-java 版本属性 |
| Modify | `bootstrap/pom.xml` | 新增 opensearch-java 依赖 |
| Modify | `bootstrap/src/main/resources/application.yaml` | 新增 opensearch 配置段 |
| Modify | `bootstrap/src/main/java/.../rag/core/vector/MilvusVectorStoreAdmin.java:53` | 修正为幂等 |
| Modify | `bootstrap/src/main/java/.../rag/core/retrieve/PgRetrieverService.java:54` | Score 归一化修正 |

### Phase 2: RBAC 权限体系

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `resources/database/upgrade_v1.1_to_v1.2.sql` | RBAC 三表 DDL |
| Create | `bootstrap/src/main/java/.../user/dao/entity/RoleDO.java` | 角色实体 |
| Create | `bootstrap/src/main/java/.../user/dao/entity/RoleKbRelationDO.java` | 角色-知识库关联实体 |
| Create | `bootstrap/src/main/java/.../user/dao/entity/UserRoleDO.java` | 用户-角色关联实体 |
| Create | `bootstrap/src/main/java/.../user/dao/mapper/RoleMapper.java` | 角色 Mapper |
| Create | `bootstrap/src/main/java/.../user/dao/mapper/RoleKbRelationMapper.java` | 角色-KB Mapper |
| Create | `bootstrap/src/main/java/.../user/dao/mapper/UserRoleMapper.java` | 用户-角色 Mapper |
| Create | `bootstrap/src/main/java/.../user/service/KbAccessService.java` | 权限解析接口 |
| Create | `bootstrap/src/main/java/.../user/service/impl/KbAccessServiceImpl.java` | 权限解析实现 |
| Create | `bootstrap/src/main/java/.../user/service/RoleService.java` | 角色管理接口 |
| Create | `bootstrap/src/main/java/.../user/service/impl/RoleServiceImpl.java` | 角色管理实现 |
| Create | `bootstrap/src/main/java/.../user/controller/RoleController.java` | 角色管理 API |

### Phase 3: 检索链路集成

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `bootstrap/src/main/java/.../rag/core/retrieve/channel/SearchContext.java` | 新增 `accessibleKbIds` 字段 |
| Modify | `bootstrap/src/main/java/.../rag/core/retrieve/channel/VectorGlobalSearchChannel.java` | RBAC 过滤 |
| Modify | `bootstrap/src/main/java/.../rag/core/retrieve/channel/IntentDirectedSearchChannel.java` | RBAC 过滤 |
| Modify | `bootstrap/src/main/java/.../rag/service/RAGChatService.java` | 签名新增 knowledgeBaseId |
| Modify | `bootstrap/src/main/java/.../rag/service/impl/RAGChatServiceImpl.java` | 注入 accessibleKbIds |
| Modify | `bootstrap/src/main/java/.../rag/controller/RAGChatController.java` | 新增 knowledgeBaseId 参数 + 权限校验 |
| Modify | `bootstrap/src/main/java/.../knowledge/controller/KnowledgeBaseController.java` | 列表权限过滤 |
| Modify | `bootstrap/src/main/java/.../knowledge/controller/KnowledgeDocumentController.java` | 接口权限校验 |

### Phase 4: 前端

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `frontend/src/stores/chatStore.ts` | buildQuery 新增 knowledgeBaseId |
| Create/Modify | 聊天页面组件 | 知识库选择器 UI |
| Create/Modify | 前端 API 层 | 知识库列表接口 + 角色管理接口 |

---

## Phase 1: OpenSearch 向量引擎

### Task 1: Docker 环境 + Maven 依赖

**Files:**
- Create: `resources/docker/opensearch-stack.compose.yaml`
- Modify: `pom.xml` (parent, properties + dependencyManagement)
- Modify: `bootstrap/pom.xml` (dependency)

- [ ] **Step 1: 创建 OpenSearch Docker Compose 文件**

```yaml
# resources/docker/opensearch-stack.compose.yaml
version: '3.8'
services:
  opensearch:
    image: opensearchproject/opensearch:2.18.0
    container_name: opensearch
    environment:
      - discovery.type=single-node
      - DISABLE_SECURITY_PLUGIN=true
      - OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m
      - plugins.security.disabled=true
    ports:
      - "9200:9200"
      - "9600:9600"
    volumes:
      - opensearch-data:/usr/share/opensearch/data

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2.18.0
    container_name: opensearch-dashboards
    environment:
      - OPENSEARCH_HOSTS=["http://opensearch:9200"]
      - DISABLE_SECURITY_DASHBOARDS_PLUGIN=true
    ports:
      - "5601:5601"
    depends_on:
      - opensearch

volumes:
  opensearch-data:
```

- [ ] **Step 2: parent pom.xml 添加版本属性和依赖管理**

在 `pom.xml` 的 `<properties>` 中添加：

```xml
<opensearch-java.version>2.18.0</opensearch-java.version>
```

在 `pom.xml` 的 `<dependencyManagement><dependencies>` 中添加：

```xml
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-java</artifactId>
    <version>${opensearch-java.version}</version>
</dependency>
```

- [ ] **Step 3: bootstrap/pom.xml 添加依赖**

在 `bootstrap/pom.xml` 的 `<dependencies>` 中添加（和 milvus-sdk-java 同级）：

```xml
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-java</artifactId>
</dependency>
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add resources/docker/opensearch-stack.compose.yaml pom.xml bootstrap/pom.xml
git commit -m "feat: add OpenSearch Docker environment and Maven dependency"
```

---

### Task 2: OpenSearch 配置类

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchProperties.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchConfig.java`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 创建 OpenSearchProperties**

```java
package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchProperties {

    private String uris = "http://localhost:9200";
    private String username;
    private String password;
    private String authType = "basic"; // basic | aws-sigv4

    private AnalyzerConfig analyzer = new AnalyzerConfig();
    private HybridConfig hybrid = new HybridConfig();

    @Data
    public static class AnalyzerConfig {
        private String defaultAnalyzer = "ik_max_word";
        private String searchAnalyzer = "ik_smart";
    }

    @Data
    public static class HybridConfig {
        private double vectorWeight = 0.5;
        private double textWeight = 0.5;
    }
}
```

- [ ] **Step 2: 创建 OpenSearchConfig**

```java
package com.nageoffer.ai.ragent.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchConfig {

    @Bean(destroyMethod = "close")
    public OpenSearchClient openSearchClient(OpenSearchProperties properties) {
        HttpHost host = HttpHost.create(properties.getUris());

        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host);

        if (properties.getUsername() != null && !properties.getUsername().isEmpty()) {
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope(host),
                        new UsernamePasswordCredentials(
                                properties.getUsername(),
                                properties.getPassword().toCharArray()));
                return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            });
        }

        return new OpenSearchClient(builder.build());
    }
}
```

- [ ] **Step 3: application.yaml 添加 opensearch 配置段**

在 `application.yaml` 的 `rag.vector.type` 注释中新增 `opensearch` 选项，并添加：

```yaml
opensearch:
  uris: http://localhost:9200
  # username: admin
  # password: admin
  # auth-type: basic
  analyzer:
    default-analyzer: ik_max_word
    search-analyzer: ik_smart
  hybrid:
    vector-weight: 0.5
    text-weight: 0.5
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchProperties.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchConfig.java
git add bootstrap/src/main/resources/application.yaml
git commit -m "feat: add OpenSearch configuration and client bean"
```

---

### Task 3: OpenSearchVectorStoreAdmin

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java`

- [ ] **Step 1: 实现 OpenSearchVectorStoreAdmin**

```java
package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.rag.config.OpenSearchProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.*;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchVectorStoreAdmin implements VectorStoreAdmin {

    private static final String PIPELINE_NAME = "ragent-hybrid-search-pipeline";

    private final OpenSearchClient client;
    private final RAGDefaultProperties ragDefaultProperties;
    private final OpenSearchProperties openSearchProperties;

    private volatile boolean pipelineReady = false;

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String indexName = spec.getSpaceId().getLogicalName();

        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                log.debug("OpenSearch index already exists: {}", indexName);
                return;
            }

            ensurePipelineExists();

            String analyzer = detectAnalyzer();
            String searchAnalyzer = detectSearchAnalyzer();
            int dimension = ragDefaultProperties.getDimension();

            String mappingJson = buildMappingJson(analyzer, searchAnalyzer, dimension);

            client.indices().create(c -> c
                    .index(indexName)
                    .settings(s -> s
                            .knn(true)
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                            .index(i -> i.search(se -> se.defaultPipeline(
                                    pipelineReady ? PIPELINE_NAME : null))))
                    .mappings(m -> m.withJson(new StringReader(mappingJson)))
            );

            log.info("Created OpenSearch index: {}, dimension: {}, analyzer: {}", indexName, dimension, analyzer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create OpenSearch index: " + indexName, e);
        }
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            return client.indices().exists(e -> e.index(spaceId.getLogicalName())).value();
        } catch (Exception e) {
            log.error("Failed to check OpenSearch index existence: {}", spaceId.getLogicalName(), e);
            return false;
        }
    }

    public boolean isPipelineReady() {
        return pipelineReady;
    }

    private void ensurePipelineExists() {
        try {
            // Check if pipeline exists
            var response = client.generic()
                    .execute(org.opensearch.client.transport.endpoints.SimpleEndpoint.forPath(
                            "_search/pipeline/" + PIPELINE_NAME));
            if (response.getStatus() == 200) {
                pipelineReady = true;
                log.debug("Search pipeline already exists: {}", PIPELINE_NAME);
                return;
            }
        } catch (Exception ignored) {
            // Pipeline doesn't exist, create it
        }

        try {
            double vectorWeight = openSearchProperties.getHybrid().getVectorWeight();
            double textWeight = openSearchProperties.getHybrid().getTextWeight();

            String pipelineBody = """
                    {
                      "description": "RAGent hybrid search normalization pipeline",
                      "phase_results_processors": [
                        {
                          "normalization-processor": {
                            "normalization": { "technique": "min_max" },
                            "combination": {
                              "technique": "arithmetic_mean",
                              "parameters": { "weights": [%s, %s] }
                            }
                          }
                        }
                      ]
                    }
                    """.formatted(vectorWeight, textWeight);

            client.generic().execute(
                    org.opensearch.client.transport.endpoints.SimpleEndpoint.forPath(
                            "_search/pipeline/" + PIPELINE_NAME),
                    pipelineBody.getBytes(),
                    "application/json",
                    "PUT"
            );

            pipelineReady = true;
            log.info("Created search pipeline: {}", PIPELINE_NAME);
        } catch (Exception e) {
            pipelineReady = false;
            log.error("Failed to create search pipeline, hybrid query will be unavailable. Falling back to pure knn.", e);
        }
    }

    private String detectAnalyzer() {
        String configured = openSearchProperties.getAnalyzer().getDefaultAnalyzer();
        try {
            client.indices().analyze(a -> a.text("测试").analyzer(configured));
            return configured;
        } catch (Exception e) {
            log.warn("Analyzer '{}' not available, falling back to 'standard'. Install IK plugin for better Chinese search.", configured);
            return "standard";
        }
    }

    private String detectSearchAnalyzer() {
        String configured = openSearchProperties.getAnalyzer().getSearchAnalyzer();
        try {
            client.indices().analyze(a -> a.text("测试").analyzer(configured));
            return configured;
        } catch (Exception e) {
            return "standard";
        }
    }

    private String buildMappingJson(String analyzer, String searchAnalyzer, int dimension) {
        return """
                {
                  "dynamic": false,
                  "properties": {
                    "id": { "type": "keyword" },
                    "content": { "type": "text", "analyzer": "%s", "search_analyzer": "%s" },
                    "embedding": {
                      "type": "knn_vector",
                      "dimension": %d,
                      "method": {
                        "name": "hnsw",
                        "space_type": "cosinesimil",
                        "engine": "lucene",
                        "parameters": { "ef_construction": 200, "m": 48 }
                      }
                    },
                    "metadata": {
                      "dynamic": false,
                      "properties": {
                        "doc_id": { "type": "keyword" },
                        "chunk_index": { "type": "integer" },
                        "task_id": { "type": "keyword", "index": false },
                        "pipeline_id": { "type": "keyword" },
                        "source_type": { "type": "keyword" },
                        "source_location": { "type": "keyword", "index": false },
                        "keywords": {
                          "type": "text",
                          "analyzer": "%s",
                          "fields": { "raw": { "type": "keyword" } }
                        },
                        "summary": { "type": "text", "analyzer": "%s" }
                      }
                    }
                  }
                }
                """.formatted(analyzer, searchAnalyzer, dimension, searchAnalyzer, analyzer);
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java
git commit -m "feat: add OpenSearchVectorStoreAdmin with index creation and pipeline management"
```

---

### Task 4: OpenSearchVectorStoreService

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java`

- [ ] **Step 1: 实现 OpenSearchVectorStoreService**

```java
package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchVectorStoreService implements VectorStoreService {

    private final OpenSearchClient client;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().index(collectionName);

            for (VectorChunk chunk : chunks) {
                String chunkId = chunk.getChunkId();
                Map<String, Object> doc = buildDocument(collectionName, docId, chunk);

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .id(chunkId)
                                .document(doc)));
            }

            BulkResponse response = client.bulk(bulkBuilder.build());
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("Bulk index error for chunk {}: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("Bulk index had errors for doc: " + docId);
            }

            log.info("Indexed {} chunks for doc {} into index {}", chunks.size(), docId, collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to index chunks for doc: " + docId, e);
        }
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        try {
            Map<String, Object> doc = buildDocument(collectionName, docId, chunk);
            client.index(i -> i
                    .index(collectionName)
                    .id(chunk.getChunkId())
                    .document(doc));
            log.debug("Updated chunk {} in index {}", chunk.getChunkId(), collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update chunk: " + chunk.getChunkId(), e);
        }
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        try {
            client.deleteByQuery(d -> d
                    .index(collectionName)
                    .query(q -> q
                            .term(t -> t
                                    .field("metadata.doc_id")
                                    .value(docId))));
            log.info("Deleted vectors for doc {} from index {}", docId, collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete vectors for doc: " + docId, e);
        }
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        try {
            client.delete(d -> d.index(collectionName).id(chunkId));
            log.debug("Deleted chunk {} from index {}", chunkId, collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete chunk: " + chunkId, e);
        }
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().index(collectionName);
            for (String chunkId : chunkIds) {
                bulkBuilder.operations(op -> op
                        .delete(del -> del.id(chunkId)));
            }

            BulkResponse response = client.bulk(bulkBuilder.build());
            if (response.errors()) {
                log.warn("Bulk delete had errors for {} chunks in index {}", chunkIds.size(), collectionName);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to bulk delete chunks from index: " + collectionName, e);
        }
    }

    private Map<String, Object> buildDocument(String collectionName, String docId, VectorChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_id", docId);
        metadata.put("chunk_index", chunk.getIndex());

        if (chunk.getMetadata() != null) {
            chunk.getMetadata().forEach((k, v) -> {
                if (v != null) {
                    metadata.put(k, v);
                }
            });
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", chunk.getChunkId());
        doc.put("content", chunk.getContent() != null ? chunk.getContent() : "");

        if (chunk.getEmbedding() != null) {
            List<Float> embedding = new ArrayList<>(chunk.getEmbedding().length);
            for (float f : chunk.getEmbedding()) {
                embedding.add(f);
            }
            doc.put("embedding", embedding);
        }

        doc.put("metadata", metadata);
        return doc;
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java
git commit -m "feat: add OpenSearchVectorStoreService for chunk CRUD"
```

---

### Task 5: OpenSearchRetrieverService

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`

- [ ] **Step 1: 实现 OpenSearchRetrieverService**

```java
package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.OpenSearchProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.OpenSearchVectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.EmbeddingService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchRetrieverService implements RetrieverService {

    private final OpenSearchClient client;
    private final EmbeddingService embeddingService;
    private final RAGDefaultProperties ragDefaultProperties;
    private final OpenSearchVectorStoreAdmin vectorStoreAdmin;

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        RetrieveRequest request = RetrieveRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return retrieve(request);
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        String collectionName = StringUtils.isNotBlank(retrieveParam.getCollectionName())
                ? retrieveParam.getCollectionName()
                : ragDefaultProperties.getCollectionName();

        List<Float> embedding = embeddingService.embed(retrieveParam.getQuery());
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i);
        }

        return doSearch(collectionName, retrieveParam.getQuery(), vector,
                retrieveParam.getTopK(), retrieveParam.getMetadataFilters());
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        String collectionName = StringUtils.isNotBlank(retrieveParam.getCollectionName())
                ? retrieveParam.getCollectionName()
                : ragDefaultProperties.getCollectionName();

        return doSearch(collectionName, retrieveParam.getQuery(), vector,
                retrieveParam.getTopK(), retrieveParam.getMetadataFilters());
    }

    private List<RetrievedChunk> doSearch(String collectionName, String query, float[] vector,
                                          int topK, Map<String, Object> metadataFilters) {
        try {
            String queryJson;

            if (vectorStoreAdmin.isPipelineReady()) {
                queryJson = buildHybridQuery(query, vector, topK, metadataFilters);
            } else {
                queryJson = buildKnnOnlyQuery(vector, topK, metadataFilters);
            }

            SearchResponse<Map> response = client.search(s -> s
                            .index(collectionName)
                            .size(topK)
                            .source(src -> src.filter(f -> f.includes("id", "content", "metadata")))
                            .withJson(new StringReader(queryJson)),
                    Map.class);

            return response.hits().hits().stream()
                    .map(this::toRetrievedChunk)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("OpenSearch retrieval failed for index: {}", collectionName, e);
            return List.of();
        }
    }

    private String buildHybridQuery(String query, float[] vector, int topK,
                                    Map<String, Object> metadataFilters) {
        String vectorStr = floatArrayToJson(vector);
        String filterClause = buildFilterClause(metadataFilters);

        if (filterClause.isEmpty()) {
            return """
                    {
                      "query": {
                        "hybrid": {
                          "queries": [
                            { "knn": { "embedding": { "vector": %s, "k": %d } } },
                            { "match": { "content": "%s" } }
                          ]
                        }
                      }
                    }
                    """.formatted(vectorStr, topK, escapeJson(query));
        }

        return """
                {
                  "query": {
                    "hybrid": {
                      "queries": [
                        {
                          "bool": {
                            "must": { "knn": { "embedding": { "vector": %s, "k": %d } } },
                            "filter": [%s]
                          }
                        },
                        {
                          "bool": {
                            "must": { "match": { "content": "%s" } },
                            "filter": [%s]
                          }
                        }
                      ]
                    }
                  }
                }
                """.formatted(vectorStr, topK, filterClause, escapeJson(query), filterClause);
    }

    private String buildKnnOnlyQuery(float[] vector, int topK, Map<String, Object> metadataFilters) {
        String vectorStr = floatArrayToJson(vector);
        String filterClause = buildFilterClause(metadataFilters);

        if (filterClause.isEmpty()) {
            return """
                    { "query": { "knn": { "embedding": { "vector": %s, "k": %d } } } }
                    """.formatted(vectorStr, topK);
        }

        return """
                {
                  "query": {
                    "bool": {
                      "must": { "knn": { "embedding": { "vector": %s, "k": %d } } },
                      "filter": [%s]
                    }
                  }
                }
                """.formatted(vectorStr, topK, filterClause);
    }

    private String buildFilterClause(Map<String, Object> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return "";
        }

        return metadataFilters.entrySet().stream()
                .map(entry -> """
                        { "term": { "metadata.%s": "%s" } }""".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private RetrievedChunk toRetrievedChunk(Hit<Map> hit) {
        Map<String, Object> source = hit.source();
        String id = source != null ? String.valueOf(source.get("id")) : hit.id();
        String content = source != null ? String.valueOf(source.getOrDefault("content", "")) : "";
        float score = hit.score() != null ? Math.min(hit.score().floatValue(), 1.0f) : 0f;

        return RetrievedChunk.builder()
                .id(id)
                .text(content)
                .score(score)
                .build();
    }

    private String floatArrayToJson(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java
git commit -m "feat: add OpenSearchRetrieverService with hybrid query support"
```

---

### Task 6: 修正现有引擎的契约问题

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/MilvusVectorStoreAdmin.java:53`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java:54`

- [ ] **Step 1: 修正 MilvusVectorStoreAdmin 为幂等**

在 `MilvusVectorStoreAdmin.java`，将第 53 行附近的：

```java
if (exists) {
    throw new VectorCollectionAlreadyExistsException(logicalName);
}
```

改为：

```java
if (exists) {
    log.debug("Milvus collection already exists: {}", logicalName);
    return;
}
```

- [ ] **Step 2: 修正 PgRetrieverService score 归一化**

在 `PgRetrieverService.java`，将第 54 行的 SQL：

```sql
SELECT id, content, 1 - (embedding <=> ?::vector) AS score
```

改为：

```sql
SELECT id, content, 1 - ((embedding <=> ?::vector) / 2) AS score
```

- [ ] **Step 3: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/MilvusVectorStoreAdmin.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java
git commit -m "fix: make MilvusVectorStoreAdmin idempotent and fix Pg score normalization"
```

---

## Phase 2: RBAC 权限体系

### Task 7: RBAC 数据库迁移脚本

**Files:**
- Create: `resources/database/upgrade_v1.1_to_v1.2.sql`

- [ ] **Step 1: 创建 DDL 迁移脚本**

```sql
-- resources/database/upgrade_v1.1_to_v1.2.sql
-- RBAC: 角色 + 知识库可见性

CREATE TABLE IF NOT EXISTS t_role (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(256),
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     INT DEFAULT 0,
    CONSTRAINT uk_role_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS t_role_kb_relation (
    id          VARCHAR(20) NOT NULL PRIMARY KEY,
    role_id     VARCHAR(20) NOT NULL,
    kb_id       VARCHAR(20) NOT NULL,
    created_by  VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_role_kb_role_id ON t_role_kb_relation (role_id);
CREATE INDEX IF NOT EXISTS idx_role_kb_kb_id ON t_role_kb_relation (kb_id);

CREATE TABLE IF NOT EXISTS t_user_role (
    id          VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(20) NOT NULL,
    role_id     VARCHAR(20) NOT NULL,
    created_by  VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_user_role_user_id ON t_user_role (user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_role_id ON t_user_role (role_id);
```

- [ ] **Step 2: Commit**

```bash
git add resources/database/upgrade_v1.1_to_v1.2.sql
git commit -m "feat: add RBAC DDL migration script (t_role, t_role_kb_relation, t_user_role)"
```

---

### Task 8: RBAC 实体类 + Mapper

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/UserRoleDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleMapper.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleKbRelationMapper.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/UserRoleMapper.java`

- [ ] **Step 1: 创建 RoleDO**

```java
package com.nageoffer.ai.ragent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("t_role")
public class RoleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String name;
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 2: 创建 RoleKbRelationDO**

```java
package com.nageoffer.ai.ragent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("t_role_kb_relation")
public class RoleKbRelationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String roleId;
    private String kbId;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 3: 创建 UserRoleDO**

```java
package com.nageoffer.ai.ragent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("t_user_role")
public class UserRoleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;
    private String roleId;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 4: 创建三个 Mapper 接口**

```java
// RoleMapper.java
package com.nageoffer.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMapper extends BaseMapper<RoleDO> {
}
```

```java
// RoleKbRelationMapper.java
package com.nageoffer.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleKbRelationMapper extends BaseMapper<RoleKbRelationDO> {
}
```

```java
// UserRoleMapper.java
package com.nageoffer.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleDO> {
}
```

- [ ] **Step 5: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleDO.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/UserRoleDO.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleMapper.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleKbRelationMapper.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/UserRoleMapper.java
git commit -m "feat: add RBAC entity classes and MyBatis mappers"
```

---

### Task 9: KbAccessService 权限解析

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1: 创建 KbAccessService 接口**

```java
package com.nageoffer.ai.ragent.user.service;

import java.util.Set;

public interface KbAccessService {

    /**
     * 获取用户可访问的所有知识库 ID。admin 返回全量。
     */
    Set<String> getAccessibleKbIds(String userId);

    /**
     * 校验当前用户是否有权访问指定知识库，无权则抛异常。
     * 完全依赖 UserContext：系统态（无登录态）直接放行，admin 放行，user 鉴权。
     */
    void checkAccess(String kbId);

    /**
     * 清除指定用户的权限缓存
     */
    void evictCache(String userId);
}
```

- [ ] **Step 2: 创建 KbAccessServiceImpl**

```java
package com.nageoffer.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbAccessServiceImpl implements KbAccessService {

    private static final String CACHE_PREFIX = "kb_access:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RedissonClient redissonClient;

    @Override
    public Set<String> getAccessibleKbIds(String userId) {
        // Check cache
        String cacheKey = CACHE_PREFIX + userId;
        RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
        Set<String> cached = bucket.get();
        if (cached != null) {
            return cached;
        }

        // Query: user -> roles
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId)
                        .eq(UserRoleDO::getDeleted, 0));

        if (userRoles.isEmpty()) {
            bucket.set(Set.of(), CACHE_TTL);
            return Set.of();
        }

        List<String> roleIds = userRoles.stream()
                .map(UserRoleDO::getRoleId)
                .toList();

        // Query: roles -> kb_ids
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .in(RoleKbRelationDO::getRoleId, roleIds)
                        .eq(RoleKbRelationDO::getDeleted, 0));

        Set<String> kbIds = relations.stream()
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toSet());

        // Filter out deleted KBs
        if (!kbIds.isEmpty()) {
            List<KnowledgeBaseDO> validKbs = knowledgeBaseMapper.selectList(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .in(KnowledgeBaseDO::getId, kbIds)
                            .eq(KnowledgeBaseDO::getDeleted, 0)
                            .select(KnowledgeBaseDO::getId));

            kbIds = validKbs.stream()
                    .map(KnowledgeBaseDO::getId)
                    .collect(Collectors.toSet());
        }

        bucket.set(kbIds, CACHE_TTL);
        return kbIds;
    }

    @Override
    public void checkAccess(String kbId) {
        // System context (MQ consumer, scheduled task) — no full login state, skip
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            return;
        }
        // Admin bypass
        if ("admin".equals(UserContext.getRole())) {
            return;
        }
        // User authorization check
        Set<String> accessible = getAccessibleKbIds(UserContext.getUserId());
        if (!accessible.contains(kbId)) {
            throw new RuntimeException("无权访问该知识库: " + kbId);
        }
    }

    @Override
    public void evictCache(String userId) {
        redissonClient.getBucket(CACHE_PREFIX + userId).delete();
    }
}
```

- [ ] **Step 3: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "feat: add KbAccessService with Redis cache and dual-context authorization"
```

---

### Task 10: RoleService + RoleController

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/RoleService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java`

- [ ] **Step 1: 创建 RoleService 接口**

```java
package com.nageoffer.ai.ragent.user.service;

import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import java.util.List;

public interface RoleService {

    String createRole(String name, String description);

    void updateRole(String roleId, String name, String description);

    void deleteRole(String roleId);

    List<RoleDO> listRoles();

    /** 设置角色关联的知识库列表（全量替换） */
    void setRoleKnowledgeBases(String roleId, List<String> kbIds);

    /** 获取角色关联的知识库 ID 列表 */
    List<String> getRoleKnowledgeBaseIds(String roleId);

    /** 为用户分配角色列表（全量替换） */
    void setUserRoles(String userId, List<String> roleIds);

    /** 获取用户的角色列表 */
    List<RoleDO> getUserRoles(String userId);
}
```

- [ ] **Step 2: 创建 RoleServiceImpl**

```java
package com.nageoffer.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final UserRoleMapper userRoleMapper;
    private final KbAccessService kbAccessService;

    @Override
    public String createRole(String name, String description) {
        RoleDO role = new RoleDO();
        role.setName(name);
        role.setDescription(description);
        roleMapper.insert(role);
        return role.getId();
    }

    @Override
    public void updateRole(String roleId, String name, String description) {
        RoleDO role = new RoleDO();
        role.setId(roleId);
        role.setName(name);
        role.setDescription(description);
        roleMapper.updateById(role);
    }

    @Override
    public void deleteRole(String roleId) {
        roleMapper.deleteById(roleId);
        // Evict cache for all users with this role
        evictCacheForRole(roleId);
    }

    @Override
    public List<RoleDO> listRoles() {
        return roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .eq(RoleDO::getDeleted, 0)
                        .orderByDesc(RoleDO::getCreateTime));
    }

    @Override
    @Transactional
    public void setRoleKnowledgeBases(String roleId, List<String> kbIds) {
        // Logical delete existing relations
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .eq(RoleKbRelationDO::getRoleId, roleId));

        // Insert new relations
        for (String kbId : kbIds) {
            RoleKbRelationDO relation = new RoleKbRelationDO();
            relation.setRoleId(roleId);
            relation.setKbId(kbId);
            roleKbRelationMapper.insert(relation);
        }

        // Evict cache for all affected users
        evictCacheForRole(roleId);
    }

    @Override
    public List<String> getRoleKnowledgeBaseIds(String roleId) {
        return roleKbRelationMapper.selectList(
                        Wrappers.lambdaQuery(RoleKbRelationDO.class)
                                .eq(RoleKbRelationDO::getRoleId, roleId)
                                .eq(RoleKbRelationDO::getDeleted, 0))
                .stream()
                .map(RoleKbRelationDO::getKbId)
                .toList();
    }

    @Override
    @Transactional
    public void setUserRoles(String userId, List<String> roleIds) {
        userRoleMapper.delete(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId));

        for (String roleId : roleIds) {
            UserRoleDO userRole = new UserRoleDO();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }

        kbAccessService.evictCache(userId);
    }

    @Override
    public List<RoleDO> getUserRoles(String userId) {
        List<String> roleIds = userRoleMapper.selectList(
                        Wrappers.lambdaQuery(UserRoleDO.class)
                                .eq(UserRoleDO::getUserId, userId)
                                .eq(UserRoleDO::getDeleted, 0))
                .stream()
                .map(UserRoleDO::getRoleId)
                .toList();

        if (roleIds.isEmpty()) {
            return List.of();
        }

        return roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .in(RoleDO::getId, roleIds)
                        .eq(RoleDO::getDeleted, 0));
    }

    private void evictCacheForRole(String roleId) {
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getRoleId, roleId)
                        .eq(UserRoleDO::getDeleted, 0));
        for (UserRoleDO ur : userRoles) {
            kbAccessService.evictCache(ur.getUserId());
        }
    }
}
```

- [ ] **Step 3: 创建 RoleController**

```java
package com.nageoffer.ai.ragent.user.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.service.RoleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @SaCheckRole("admin")
    @PostMapping("/role")
    public Result<String> createRole(@RequestBody RoleCreateRequest request) {
        String id = roleService.createRole(request.getName(), request.getDescription());
        return Result.success(id);
    }

    @SaCheckRole("admin")
    @PutMapping("/role/{roleId}")
    public Result<Void> updateRole(@PathVariable String roleId, @RequestBody RoleCreateRequest request) {
        roleService.updateRole(roleId, request.getName(), request.getDescription());
        return Result.success(null);
    }

    @SaCheckRole("admin")
    @DeleteMapping("/role/{roleId}")
    public Result<Void> deleteRole(@PathVariable String roleId) {
        roleService.deleteRole(roleId);
        return Result.success(null);
    }

    @SaCheckRole("admin")
    @GetMapping("/role")
    public Result<List<RoleDO>> listRoles() {
        return Result.success(roleService.listRoles());
    }

    @SaCheckRole("admin")
    @PutMapping("/role/{roleId}/knowledge-bases")
    public Result<Void> setRoleKnowledgeBases(@PathVariable String roleId,
                                               @RequestBody List<String> kbIds) {
        roleService.setRoleKnowledgeBases(roleId, kbIds);
        return Result.success(null);
    }

    @SaCheckRole("admin")
    @GetMapping("/role/{roleId}/knowledge-bases")
    public Result<List<String>> getRoleKnowledgeBases(@PathVariable String roleId) {
        return Result.success(roleService.getRoleKnowledgeBaseIds(roleId));
    }

    @SaCheckRole("admin")
    @PutMapping("/user/{userId}/roles")
    public Result<Void> setUserRoles(@PathVariable String userId, @RequestBody List<String> roleIds) {
        roleService.setUserRoles(userId, roleIds);
        return Result.success(null);
    }

    @SaCheckRole("admin")
    @GetMapping("/user/{userId}/roles")
    public Result<List<RoleDO>> getUserRoles(@PathVariable String userId) {
        return Result.success(roleService.getUserRoles(userId));
    }

    @Data
    public static class RoleCreateRequest {
        private String name;
        private String description;
    }
}
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/RoleService.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java
git commit -m "feat: add RoleService and RoleController for RBAC management"
```

---

## Phase 3: 检索链路集成

### Task 11: SearchContext + 检索通道 RBAC 过滤

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java`

- [ ] **Step 1: SearchContext 新增 accessibleKbIds 字段**

在 `SearchContext.java` 的字段声明区域添加（和 `metadata` 字段同级）：

```java
/**
 * 当前用户可访问的知识库 ID 集合（RBAC 安全约束）。
 * null 表示不限（admin 或系统态），非 null 时检索通道必须过滤。
 * 选择显式字段而非复用 metadata：安全约束是一等公民，强类型避免遗漏。
 */
private Set<String> accessibleKbIds;
```

记得在文件头添加 `import java.util.Set;`

- [ ] **Step 2: VectorGlobalSearchChannel.getAllKBCollections() 加 RBAC 过滤**

找到 `VectorGlobalSearchChannel.java` 的 `getAllKBCollections()` 方法（约 line 159），改为接受 `SearchContext` 参数：

将 `search()` 方法中调用 `getAllKBCollections()` 的地方改为 `getAllKBCollections(context)`。

修改 `getAllKBCollections` 方法：

```java
private List<String> getAllKBCollections(SearchContext context) {
    Set<String> collections = new HashSet<>();

    List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                    .select(KnowledgeBaseDO::getId, KnowledgeBaseDO::getCollectionName)
                    .eq(KnowledgeBaseDO::getDeleted, 0)
    );

    for (KnowledgeBaseDO kb : kbList) {
        String collectionName = kb.getCollectionName();
        if (collectionName == null || collectionName.isBlank()) {
            continue;
        }
        // RBAC filter: if accessibleKbIds is set, only include accessible KBs
        if (context.getAccessibleKbIds() != null
                && !context.getAccessibleKbIds().contains(kb.getId())) {
            continue;
        }
        collections.add(collectionName);
    }

    return new ArrayList<>(collections);
}
```

- [ ] **Step 3: IntentDirectedSearchChannel 加 RBAC 过滤**

在 `IntentDirectedSearchChannel.java` 的 `extractKbIntents()` 方法中，过滤掉用户无权访问的知识库意图。找到该方法，在返回前添加过滤逻辑：

在过滤出 KB 类型意图后，增加 accessibleKbIds 过滤：

```java
// 在 extractKbIntents 方法末尾返回前添加：
if (context.getAccessibleKbIds() != null) {
    kbIntents = kbIntents.stream()
            .filter(ns -> {
                String kbId = ns.getNode().getKbId();
                return kbId == null || context.getAccessibleKbIds().contains(kbId);
            })
            .toList();
}
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java
git commit -m "feat: add RBAC filtering to SearchContext and retrieval channels"
```

---

### Task 12: Chat 链路集成（Controller → Service → 检索）

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RAGChatService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java`

- [ ] **Step 1: RAGChatService 接口签名新增 knowledgeBaseId**

修改 `RAGChatService.java` 的 `streamChat` 方法签名：

```java
void streamChat(String question, String conversationId, String knowledgeBaseId,
                Boolean deepThinking, SseEmitter emitter);
```

- [ ] **Step 2: RAGChatServiceImpl 实现新增 knowledgeBaseId 处理**

修改 `RAGChatServiceImpl.java` 的 `streamChat` 签名匹配接口，并在意图解析和检索之前注入权限信息。

在方法签名中新增 `String knowledgeBaseId` 参数。

在 `RewriteResult rewriteResult = ...` 之前注入权限信息：

```java
// Resolve accessible KB IDs for RBAC
Set<String> accessibleKbIds = null;
if (UserContext.hasUser() && UserContext.getUserId() != null
        && !"admin".equals(UserContext.getRole())) {
    accessibleKbIds = kbAccessService.getAccessibleKbIds(UserContext.getUserId());
}
```

新增字段注入 `private final KbAccessService kbAccessService;`

在构建 SearchContext 或调用 retrievalEngine 前，将 `accessibleKbIds` 传入。具体位置取决于 retrievalEngine 如何构建 SearchContext——需要在 `retrievalEngine.retrieve()` 调用链中确保 SearchContext 携带 `accessibleKbIds`。

如果传了 `knowledgeBaseId`：
- 执行 `kbAccessService.checkAccess(knowledgeBaseId)`
- 跳过意图分类，直接用该知识库检索

- [ ] **Step 3: RAGChatController 新增 knowledgeBaseId 参数**

修改 `RAGChatController.java` 的 chat 方法：

```java
@GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chat(@RequestParam String question,
                       @RequestParam(required = false) String conversationId,
                       @RequestParam(required = false) String knowledgeBaseId,
                       @RequestParam(required = false, defaultValue = "false") Boolean deepThinking) {
    // ... existing SseEmitter setup ...
    ragChatService.streamChat(question, conversationId, knowledgeBaseId, deepThinking, emitter);
    return emitter;
}
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RAGChatService.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java
git commit -m "feat: integrate knowledgeBaseId and RBAC into chat pipeline"
```

---

### Task 13: 知识库和文档接口权限校验

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java`

- [ ] **Step 1: KnowledgeBaseController.pageQuery() 加权限过滤**

在 `KnowledgeBaseController.java` 中注入 `KbAccessService`，修改 `pageQuery` 方法：

```java
@GetMapping("/knowledge-base")
public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
    // RBAC: non-admin users only see accessible KBs
    if (UserContext.hasUser() && !"admin".equals(UserContext.getRole())) {
        Set<String> accessibleKbIds = kbAccessService.getAccessibleKbIds(UserContext.getUserId());
        requestParam.setAccessibleKbIds(accessibleKbIds);
    }
    return Result.success(knowledgeBaseService.pageQuery(requestParam));
}
```

注意：`KnowledgeBasePageRequest` 需要新增 `accessibleKbIds` 字段，Service 层查询时用 `IN` 条件过滤。

- [ ] **Step 2: KnowledgeBaseController 详情接口加权限校验**

在 `queryKnowledgeBase` 方法中添加：

```java
@GetMapping("/knowledge-base/{kb-id}")
public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
    kbAccessService.checkAccess(kbId);
    return Result.success(knowledgeBaseService.query(kbId));
}
```

- [ ] **Step 3: KnowledgeDocumentController 加权限校验**

在需要权限校验的接口中注入 `KbAccessService`，在涉及 kbId 的方法开头加 `kbAccessService.checkAccess(kbId)`。

对于 `GET /knowledge-base/docs/{docId}` 等不直接传 kbId 的接口，需要先查出文档所属的 kbId 再校验：

```java
@GetMapping("/knowledge-base/docs/{docId}")
public Result<KnowledgeDocumentVO> get(@PathVariable String docId) {
    KnowledgeDocumentDO doc = documentService.getById(docId);
    if (doc != null) {
        kbAccessService.checkAccess(doc.getKbId());
    }
    return Result.success(documentService.getDetail(docId));
}
```

对于 `GET /knowledge-base/docs/search` 全局搜索，在 Service 层过滤只返回用户有权知识库下的文档。

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean compile -DskipTests -pl bootstrap`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java
git commit -m "feat: add RBAC permission checks to knowledge base and document controllers"
```

---

## Phase 4: 前端

### Task 14: 前端 Chat 集成知识库选择

**Files:**
- Modify: `frontend/src/stores/chatStore.ts`
- 新增/修改: 聊天页面组件（知识库选择器）
- 新增: 前端 API 调用（知识库列表）

- [ ] **Step 1: chatStore.ts 新增 knowledgeBaseId 状态和参数**

在 `chatStore.ts` 中：

1. Store state 新增字段：

```typescript
selectedKnowledgeBaseId: string | null;  // null = "自动"模式
```

2. 修改 `buildQuery` 调用（约 line 259-264），新增 `knowledgeBaseId`：

```typescript
const query = buildQuery({
  question: trimmed,
  conversationId: conversationId || undefined,
  knowledgeBaseId: get().selectedKnowledgeBaseId || undefined,
  deepThinking: deepThinkingEnabled ? true : undefined
});
```

3. 新增 action：

```typescript
setSelectedKnowledgeBase: (kbId: string | null) => set({ selectedKnowledgeBaseId: kbId }),
```

- [ ] **Step 2: 新增知识库列表 API 调用**

在前端 API 层新增获取当前用户可访问的知识库列表接口：

```typescript
// api/knowledgeBase.ts
export async function fetchAccessibleKnowledgeBases(): Promise<KnowledgeBase[]> {
  const response = await request.get('/knowledge-base');
  return response.data.records;
}
```

- [ ] **Step 3: 聊天页面新增知识库选择器**

在聊天输入框上方或旁边添加一个下拉选择器组件：
- 选项：`自动`（默认）+ 用户有权的知识库列表
- 选中值绑定到 `chatStore.selectedKnowledgeBaseId`
- 页面加载时调用 `fetchAccessibleKnowledgeBases()` 获取列表

- [ ] **Step 4: 验证前端编译通过**

Run: `cd frontend && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: add knowledge base selector to chat UI with RBAC integration"
```
