# OpenSearch 迁移 + RBAC 知识库权限体系 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将向量存储从 Milvus 切换到 OpenSearch（支持混合查询），并实现基于角色的知识库可见性权限体系。

**Architecture:** 复用现有 `VectorStoreAdmin` / `VectorStoreService` / `RetrieverService` 三层接口，新增 OpenSearch 实现类，通过 `@ConditionalOnProperty` 配置切换。RBAC 新增 3 张表（t_role / t_role_kb_relation / t_user_role）+ KbAccessService 权限服务，Controller 和 Service 双层防越权校验。

**Tech Stack:** opensearch-java 2.x 官方客户端, OpenSearch 2.10+（lucene 引擎 + hybrid query）, Sa-Token, MyBatis Plus, PostgreSQL

**Design Spec:** `docs/superpowers/specs/2026-04-07-opensearch-migration-and-rbac-design.md`

---

## 文件结构

### 新增文件

| 文件路径 | 职责 |
|---------|------|
| `bootstrap/src/main/java/.../rag/config/OpenSearchConfig.java` | OpenSearch 客户端 Bean + search pipeline 初始化 |
| `bootstrap/src/main/java/.../rag/config/OpenSearchProperties.java` | OpenSearch 连接配置属性类 |
| `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreAdmin.java` | Index 创建与管理 |
| `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreService.java` | Chunk CRUD（bulk index / upsert / delete） |
| `bootstrap/src/main/java/.../rag/core/retrieve/OpenSearchRetrieverService.java` | 混合查询检索（knn + match） |
| `bootstrap/src/main/java/.../user/dao/entity/RoleDO.java` | 角色实体 |
| `bootstrap/src/main/java/.../user/dao/entity/RoleKbRelationDO.java` | 角色-知识库关联实体 |
| `bootstrap/src/main/java/.../user/dao/entity/UserRoleDO.java` | 用户-角色关联实体 |
| `bootstrap/src/main/java/.../user/dao/mapper/RoleMapper.java` | 角色 Mapper |
| `bootstrap/src/main/java/.../user/dao/mapper/RoleKbRelationMapper.java` | 角色-知识库 Mapper |
| `bootstrap/src/main/java/.../user/dao/mapper/UserRoleMapper.java` | 用户-角色 Mapper |
| `bootstrap/src/main/java/.../user/service/KbAccessService.java` | 知识库权限解析接口 |
| `bootstrap/src/main/java/.../user/service/impl/KbAccessServiceImpl.java` | 知识库权限解析实现 |
| `bootstrap/src/main/java/.../user/service/RoleService.java` | 角色管理接口 |
| `bootstrap/src/main/java/.../user/service/impl/RoleServiceImpl.java` | 角色管理实现 |
| `bootstrap/src/main/java/.../user/controller/RoleController.java` | 角色管理 API |
| `bootstrap/src/main/java/.../user/controller/request/RoleCreateRequest.java` | 角色创建请求 |
| `bootstrap/src/main/java/.../user/controller/request/RoleUpdateRequest.java` | 角色更新请求 |
| `bootstrap/src/main/java/.../user/controller/vo/RoleVO.java` | 角色视图对象 |
| `bootstrap/src/test/java/.../rag/core/vector/OpenSearchVectorStoreAdminTest.java` | Admin 集成测试 |
| `bootstrap/src/test/java/.../rag/core/vector/OpenSearchVectorStoreServiceTest.java` | Service 集成测试 |
| `bootstrap/src/test/java/.../rag/core/retrieve/OpenSearchRetrieverServiceTest.java` | 混合查询集成测试 |
| `bootstrap/src/test/java/.../user/service/KbAccessServiceTest.java` | 权限解析测试 |
| `resources/database/upgrade_v1.1_to_v1.2.sql` | RBAC 建表 DDL |
| `resources/docker/opensearch/docker-compose.yml` | OpenSearch 本地 Docker 环境 |

### 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `pom.xml` (root) | 新增 opensearch-java 版本管理 |
| `bootstrap/pom.xml` | 新增 opensearch-java 依赖 |
| `bootstrap/src/main/resources/application.yaml` | 新增 opensearch 配置段 |
| `bootstrap/src/main/java/.../knowledge/service/impl/KnowledgeBaseServiceImpl.java` | pageQuery 加权限过滤 |
| `bootstrap/src/main/java/.../knowledge/controller/KnowledgeBaseController.java` | 详情接口加 fail-fast 权限校验 |
| `bootstrap/src/main/java/.../rag/controller/RAGChatController.java` | chat 接口加 fail-fast 权限校验 |
| `bootstrap/src/main/java/.../rag/service/RAGChatService.java` 或其实现 | chat 方法内 Service 层兜底校验 |
| `bootstrap/src/main/java/.../user/config/SaTokenStpInterfaceImpl.java` | getPermissionList 对接 RBAC |
| `bootstrap/src/main/java/.../user/service/impl/UserServiceImpl.java` | 创建/更新用户时支持角色分配 |
| `bootstrap/src/main/java/.../user/controller/request/UserCreateRequest.java` | 新增 roleIds 字段 |
| `bootstrap/src/main/java/.../user/controller/request/UserUpdateRequest.java` | 新增 roleIds 字段 |

> 以下所有路径中 `...` 代表 `com/nageoffer/ai/ragent`

---

## Task 1: Docker 环境 + Maven 依赖

**Files:**
- Create: `resources/docker/opensearch/docker-compose.yml`
- Modify: `pom.xml` (root)
- Modify: `bootstrap/pom.xml`

- [ ] **Step 1: 创建 OpenSearch Docker Compose 文件**

```yaml
# resources/docker/opensearch/docker-compose.yml
version: '3'
services:
  opensearch:
    image: opensearchproject/opensearch:2.19.1
    container_name: opensearch
    environment:
      - discovery.type=single-node
      - DISABLE_SECURITY_PLUGIN=true
      - "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m"
    ports:
      - "9200:9200"
      - "9600:9600"
    volumes:
      - opensearch-data:/usr/share/opensearch/data

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2.19.1
    container_name: opensearch-dashboards
    ports:
      - "5601:5601"
    environment:
      - OPENSEARCH_HOSTS=["http://opensearch:9200"]
      - DISABLE_SECURITY_DASHBOARDS_PLUGIN=true

volumes:
  opensearch-data:
```

- [ ] **Step 2: 在 root pom.xml 添加 opensearch-java 版本管理**

在 `<properties>` 段添加：

```xml
<opensearch-java.version>2.20.0</opensearch-java.version>
```

在 `<dependencyManagement><dependencies>` 段添加：

```xml
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-java</artifactId>
    <version>${opensearch-java.version}</version>
</dependency>
```

- [ ] **Step 3: 在 bootstrap/pom.xml 添加依赖**

在 `<dependencies>` 段添加：

```xml
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-java</artifactId>
</dependency>
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean install -DskipTests -pl bootstrap -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: 启动 Docker 并验证 OpenSearch 可用**

Run: `cd resources/docker/opensearch && docker-compose up -d`
Run: `curl http://localhost:9200`
Expected: 返回 OpenSearch 版本信息 JSON

- [ ] **Step 6: Commit**

```bash
git add resources/docker/opensearch/docker-compose.yml pom.xml bootstrap/pom.xml
git commit -m "chore: 添加 OpenSearch Docker 环境和 Maven 依赖"
```

---

## Task 2: OpenSearch 配置类

**Files:**
- Create: `bootstrap/src/main/java/.../rag/config/OpenSearchProperties.java`
- Create: `bootstrap/src/main/java/.../rag/config/OpenSearchConfig.java`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 创建配置属性类**

```java
package com.knowledgebase.ai.ragent.rag.config;

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
}
```

- [ ] **Step 2: 创建配置类（客户端 Bean + Search Pipeline 初始化）**

```java
package com.knowledgebase.ai.ragent.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchConfig {

    public static final String HYBRID_PIPELINE_NAME = "ragent-hybrid-search-pipeline";

    private final OpenSearchProperties properties;

    @Bean
    public OpenSearchClient openSearchClient() {
        HttpHost host = HttpHost.create(properties.getUris());
        ApacheHttpClient5TransportBuilder builder =
                ApacheHttpClient5TransportBuilder.builder(host);

        if (StringUtils.hasText(properties.getUsername())) {
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

    @PostConstruct
    public void ensureSearchPipeline() {
        // Pipeline 创建在 OpenSearchVectorStoreAdmin 中实现
        // 因为需要 OpenSearchClient bean 已就绪
    }
}
```

- [ ] **Step 3: 在 application.yaml 添加 opensearch 配置**

在 `milvus:` 配置段之后添加：

```yaml
opensearch:
  uris: http://localhost:9200
  # username: admin
  # password: admin
```

将 `rag.vector.type` 的注释更新：

```yaml
rag:
  vector:
    type: pg  # milvus | pg | opensearch
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean install -DskipTests -pl bootstrap -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchProperties.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchConfig.java
git add bootstrap/src/main/resources/application.yaml
git commit -m "feat: OpenSearch 客户端配置类和连接属性"
```

---

## Task 3: OpenSearchVectorStoreAdmin

**Files:**
- Create: `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreAdmin.java`
- Test: `bootstrap/src/test/java/.../rag/core/vector/OpenSearchVectorStoreAdminTest.java`

- [ ] **Step 1: 编写集成测试**

```java
package com.knowledgebase.ai.ragent.rag.core.vector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OpenSearchVectorStoreAdminTest {

    @Autowired
    private OpenSearchVectorStoreAdmin admin;

    @Test
    void ensureVectorSpace_createsIndexWithCorrectMapping() {
        String indexName = "test_kb_" + System.currentTimeMillis();
        VectorSpaceSpec spec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder().logicalName(indexName).build())
                .remark("test knowledge base")
                .build();

        admin.ensureVectorSpace(spec);

        assertTrue(admin.vectorSpaceExists(spec.getSpaceId()));
    }

    @Test
    void ensureVectorSpace_idempotent_noExceptionOnDuplicate() {
        String indexName = "test_kb_idempotent_" + System.currentTimeMillis();
        VectorSpaceSpec spec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder().logicalName(indexName).build())
                .remark("test")
                .build();

        admin.ensureVectorSpace(spec);
        // 第二次调用不应抛异常
        assertDoesNotThrow(() -> admin.ensureVectorSpace(spec));
    }

    @Test
    void vectorSpaceExists_returnsFalseForNonExistent() {
        VectorSpaceId id = VectorSpaceId.builder().logicalName("non_existent_index").build();
        assertFalse(admin.vectorSpaceExists(id));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreAdminTest`
Expected: FAIL — `OpenSearchVectorStoreAdmin` 类不存在

- [ ] **Step 3: 实现 OpenSearchVectorStoreAdmin**

```java
package com.knowledgebase.ai.ragent.rag.core.vector;

import com.knowledgebase.ai.ragent.rag.config.OpenSearchConfig;
import com.knowledgebase.ai.ragent.rag.config.RAGDefaultProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
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

    private final OpenSearchClient client;
    private final RAGDefaultProperties ragDefaultProperties;

    @PostConstruct
    public void init() {
        ensureSearchPipeline();
    }

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String indexName = spec.getSpaceId().getLogicalName();
        try {
            if (vectorSpaceExists(spec.getSpaceId())) {
                log.info("OpenSearch index [{}] already exists, skipping creation", indexName);
                return;
            }

            int dimension = ragDefaultProperties.getDimension() != null
                    ? ragDefaultProperties.getDimension() : 1536;

            // knn_vector field 需要用 JSON 方式构建，因为 opensearch-java 对 knn 参数支持有限
            String embeddingMapping = """
                    {
                      "type": "knn_vector",
                      "dimension": %d,
                      "method": {
                        "name": "hnsw",
                        "space_type": "cosinesimil",
                        "engine": "lucene",
                        "parameters": { "ef_construction": 200, "m": 48 }
                      }
                    }
                    """.formatted(dimension);

            CreateIndexRequest request = new CreateIndexRequest.Builder()
                    .index(indexName)
                    .settings(new IndexSettings.Builder()
                            .knn(true)
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                            .otherSettings("index.search.default_pipeline",
                                    JsonData.of(OpenSearchConfig.HYBRID_PIPELINE_NAME))
                            .build())
                    .mappings(new TypeMapping.Builder()
                            .properties("id", new Property.Builder()
                                    .keyword(new KeywordProperty.Builder().build()).build())
                            .properties("content", new Property.Builder()
                                    .text(new TextProperty.Builder()
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart").build()).build())
                            .properties("embedding", new Property.Builder()
                                    .withJson(new StringReader(embeddingMapping)).build())
                            .properties("metadata", new Property.Builder()
                                    .object(new ObjectProperty.Builder()
                                            .properties(buildMetadataProperties()).build()).build())
                            .build())
                    .build();

            client.indices().create(request);
            log.info("Created OpenSearch index [{}] with dimension={}", indexName, dimension);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create OpenSearch index: " + indexName, e);
        }
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            return client.indices().exists(r -> r.index(spaceId.getLogicalName())).value();
        } catch (Exception e) {
            throw new RuntimeException("Failed to check index existence: " + spaceId.getLogicalName(), e);
        }
    }

    private Map<String, Property> buildMetadataProperties() {
        return Map.of(
                "doc_id", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().build()).build(),
                "chunk_index", new Property.Builder()
                        .integer(new IntegerNumberProperty.Builder().build()).build(),
                "task_id", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().index(false).build()).build(),
                "pipeline_id", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().build()).build(),
                "source_type", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().build()).build(),
                "source_location", new Property.Builder()
                        .keyword(new KeywordProperty.Builder().index(false).build()).build(),
                "keywords", new Property.Builder()
                        .text(new TextProperty.Builder()
                                .analyzer("ik_smart")
                                .fields("raw", new Property.Builder()
                                        .keyword(new KeywordProperty.Builder().build()).build())
                                .build()).build(),
                "summary", new Property.Builder()
                        .text(new TextProperty.Builder()
                                .analyzer("ik_max_word").build()).build()
        );
    }

    private void ensureSearchPipeline() {
        String pipelineName = OpenSearchConfig.HYBRID_PIPELINE_NAME;
        try {
            // 检查 pipeline 是否存在
            try {
                client.generic().execute(org.opensearch.client.transport.endpoints.SimpleEndpoint
                        .forPath("/_search/pipeline/" + pipelineName));
                log.info("Search pipeline [{}] already exists", pipelineName);
                return;
            } catch (Exception ignored) {
                // Pipeline 不存在，继续创建
            }

            String pipelineBody = """
                    {
                      "description": "RAGent hybrid search normalization pipeline",
                      "phase_results_processors": [
                        {
                          "normalization-processor": {
                            "normalization": { "technique": "min_max" },
                            "combination": {
                              "technique": "arithmetic_mean",
                              "parameters": { "weights": [0.5, 0.5] }
                            }
                          }
                        }
                      ]
                    }
                    """;

            client.generic().execute(
                    org.opensearch.client.transport.endpoints.SimpleEndpoint
                            .forPath("/_search/pipeline/" + pipelineName),
                    org.opensearch.client.transport.endpoints.SimpleEndpoint
                            .withPayload(pipelineBody, "application/json"));
            log.info("Created search pipeline [{}]", pipelineName);
        } catch (Exception e) {
            log.warn("Failed to create search pipeline [{}]: {}", pipelineName, e.getMessage());
        }
    }
}
```

> **注意**：`ensureSearchPipeline()` 中使用 `generic()` API 执行 REST 调用，因为 opensearch-java 客户端尚未原生封装 search pipeline API。实际实现时可能需要根据 opensearch-java 版本调整调用方式（如使用低级别 REST 请求）。实现者应参考 opensearch-java 的最新文档确认 API 调用方式。

- [ ] **Step 4: 运行测试**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreAdminTest`
Expected: PASS（需要本地 OpenSearch 运行中且安装了 IK 分词器插件）

> 如果 IK 分词器未安装，需要先执行：
> `docker exec opensearch bin/opensearch-plugin install analysis-ik`
> 然后重启容器。

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdminTest.java
git commit -m "feat: OpenSearchVectorStoreAdmin 实现 index 创建与 search pipeline 管理"
```

---

## Task 4: OpenSearchVectorStoreService

**Files:**
- Create: `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreService.java`
- Test: `bootstrap/src/test/java/.../rag/core/vector/OpenSearchVectorStoreServiceTest.java`

- [ ] **Step 1: 编写集成测试**

```java
package com.knowledgebase.ai.ragent.rag.core.vector;

import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OpenSearchVectorStoreServiceTest {

    @Autowired
    private OpenSearchVectorStoreService service;

    @Autowired
    private OpenSearchVectorStoreAdmin admin;

    private static final String TEST_COLLECTION = "test_svc_" + System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        VectorSpaceSpec spec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder().logicalName(TEST_COLLECTION).build())
                .remark("test").build();
        admin.ensureVectorSpace(spec);
    }

    @Test
    void indexDocumentChunks_andDeleteByDocId() {
        float[] embedding = new float[1536];
        embedding[0] = 0.1f;

        VectorChunk chunk = VectorChunk.builder()
                .chunkId("chunk_001")
                .index(0)
                .content("这是一段测试文本")
                .metadata(Map.of("keywords", List.of("测试", "文本")))
                .embedding(embedding)
                .build();

        service.indexDocumentChunks(TEST_COLLECTION, "doc_001", List.of(chunk));

        // 删除后不应报错
        assertDoesNotThrow(() ->
                service.deleteDocumentVectors(TEST_COLLECTION, "doc_001"));
    }

    @Test
    void deleteChunkById_removesSpecificChunk() {
        float[] embedding = new float[1536];
        VectorChunk chunk = VectorChunk.builder()
                .chunkId("chunk_del_001")
                .index(0).content("待删除").embedding(embedding).build();

        service.indexDocumentChunks(TEST_COLLECTION, "doc_002", List.of(chunk));
        assertDoesNotThrow(() ->
                service.deleteChunkById(TEST_COLLECTION, "chunk_del_001"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreServiceTest`
Expected: FAIL — `OpenSearchVectorStoreService` 类不存在

- [ ] **Step 3: 实现 OpenSearchVectorStoreService**

```java
package com.knowledgebase.ai.ragent.rag.core.vector;

import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch._types.Refresh;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchVectorStoreService implements VectorStoreService {

    private final OpenSearchClient client;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        String indexName = resolveIndex(collectionName);
        try {
            List<BulkOperation> operations = new ArrayList<>();
            for (VectorChunk chunk : chunks) {
                Map<String, Object> doc = buildDocument(indexName, docId, chunk);
                String id = chunk.getChunkId();
                operations.add(new BulkOperation.Builder()
                        .index(op -> op.index(indexName).id(id).document(doc))
                        .build());
            }

            BulkResponse response = client.bulk(new BulkRequest.Builder()
                    .operations(operations)
                    .refresh(Refresh.WaitFor)
                    .build());

            if (response.errors()) {
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .forEach(item -> log.error("Bulk index error for [{}]: {}",
                                item.id(), item.error().reason()));
                throw new RuntimeException("Bulk index had errors for index: " + indexName);
            }
            log.info("Indexed {} chunks to [{}] for doc [{}]", chunks.size(), indexName, docId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to index chunks to: " + indexName, e);
        }
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        String indexName = resolveIndex(collectionName);
        try {
            Map<String, Object> doc = buildDocument(indexName, docId, chunk);
            client.index(new IndexRequest.Builder<Map<String, Object>>()
                    .index(indexName)
                    .id(chunk.getChunkId())
                    .document(doc)
                    .refresh(Refresh.WaitFor)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to update chunk: " + chunk.getChunkId(), e);
        }
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        String indexName = resolveIndex(collectionName);
        try {
            client.deleteByQuery(new DeleteByQueryRequest.Builder()
                    .index(indexName)
                    .query(new Query.Builder()
                            .term(new TermQuery.Builder()
                                    .field("metadata.doc_id")
                                    .value(docId).build()).build())
                    .refresh(true)
                    .build());
            log.info("Deleted vectors for doc [{}] from [{}]", docId, indexName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete document vectors: " + docId, e);
        }
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        String indexName = resolveIndex(collectionName);
        try {
            client.delete(new DeleteRequest.Builder()
                    .index(indexName)
                    .id(chunkId)
                    .refresh(Refresh.WaitFor)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete chunk: " + chunkId, e);
        }
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        String indexName = resolveIndex(collectionName);
        try {
            List<BulkOperation> operations = chunkIds.stream()
                    .map(id -> new BulkOperation.Builder()
                            .delete(op -> op.index(indexName).id(id))
                            .build())
                    .toList();

            client.bulk(new BulkRequest.Builder()
                    .operations(operations)
                    .refresh(Refresh.WaitFor)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to batch delete chunks from: " + indexName, e);
        }
    }

    private Map<String, Object> buildDocument(String indexName, String docId, VectorChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_id", docId);
        metadata.put("chunk_index", chunk.getIndex());

        if (chunk.getMetadata() != null) {
            chunk.getMetadata().forEach((k, v) -> {
                if (v != null) metadata.putIfAbsent(k, v);
            });
        }

        String content = chunk.getContent() == null ? "" : chunk.getContent();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        // 将 float[] 转为 List<Float> 以便 JSON 序列化
        List<Float> embeddingList = new ArrayList<>(chunk.getEmbedding().length);
        for (float v : chunk.getEmbedding()) {
            embeddingList.add(v);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", chunk.getChunkId());
        doc.put("content", content);
        doc.put("embedding", embeddingList);
        doc.put("metadata", metadata);
        return doc;
    }

    private String resolveIndex(String collectionName) {
        return StringUtils.hasText(collectionName)
                ? collectionName
                : ragDefaultProperties.getCollectionName();
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreServiceTest.java
git commit -m "feat: OpenSearchVectorStoreService 实现 chunk CRUD"
```

---

## Task 5: OpenSearchRetrieverService

**Files:**
- Create: `bootstrap/src/main/java/.../rag/core/retrieve/OpenSearchRetrieverService.java`
- Test: `bootstrap/src/test/java/.../rag/core/retrieve/OpenSearchRetrieverServiceTest.java`

- [ ] **Step 1: 编写集成测试**

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.rag.core.vector.OpenSearchVectorStoreAdmin;
import com.knowledgebase.ai.ragent.rag.core.vector.OpenSearchVectorStoreService;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorSpaceId;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorSpaceSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenSearchRetrieverServiceTest {

    @Autowired
    private OpenSearchRetrieverService retriever;

    @Autowired
    private OpenSearchVectorStoreAdmin admin;

    @Autowired
    private OpenSearchVectorStoreService storeService;

    private static final String TEST_COLLECTION = "test_retrieve_" + System.currentTimeMillis();

    @BeforeAll
    void setUp() {
        admin.ensureVectorSpace(VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder().logicalName(TEST_COLLECTION).build())
                .remark("test").build());

        // 插入测试数据
        float[] embedding = new float[1536];
        embedding[0] = 0.9f;
        embedding[1] = 0.1f;

        VectorChunk chunk = VectorChunk.builder()
                .chunkId("retrieve_test_001")
                .index(0)
                .content("OpenSearch 是一个开源的搜索和分析引擎")
                .embedding(embedding)
                .build();

        storeService.indexDocumentChunks(TEST_COLLECTION, "doc_r_001", List.of(chunk));
    }

    @Test
    void retrieve_returnsResultsWithNormalizedScore() {
        RetrieveRequest request = RetrieveRequest.builder()
                .query("搜索引擎")
                .topK(5)
                .collectionName(TEST_COLLECTION)
                .build();

        List<RetrievedChunk> results = retriever.retrieve(request);

        assertNotNull(results);
        // Score 应在 0-1 范围
        results.forEach(r -> {
            assertNotNull(r.getScore());
            assertTrue(r.getScore() >= 0f && r.getScore() <= 1f,
                    "Score should be normalized to 0-1, got: " + r.getScore());
        });
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest`
Expected: FAIL — `OpenSearchRetrieverService` 类不存在

- [ ] **Step 3: 实现 OpenSearchRetrieverService**

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingService;
import com.knowledgebase.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchRetrieverService implements RetrieverService {

    private final OpenSearchClient client;
    private final EmbeddingService embeddingService;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        List<Float> embeddingList = embeddingService.embed(retrieveParam.getQuery());
        float[] vector = new float[embeddingList.size()];
        for (int i = 0; i < embeddingList.size(); i++) {
            vector[i] = embeddingList.get(i);
        }
        return retrieveByVector(vector, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        String indexName = StringUtils.hasText(retrieveParam.getCollectionName())
                ? retrieveParam.getCollectionName()
                : ragDefaultProperties.getCollectionName();
        int topK = retrieveParam.getTopK() > 0 ? retrieveParam.getTopK() : 5;

        try {
            // 构建向量数组字符串
            StringBuilder vectorStr = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) vectorStr.append(",");
                vectorStr.append(vector[i]);
            }
            vectorStr.append("]");

            // 构建混合查询 JSON
            String hybridQuery = """
                    {
                      "hybrid": {
                        "queries": [
                          {
                            "knn": {
                              "embedding": {
                                "vector": %s,
                                "k": %d
                              }
                            }
                          },
                          {
                            "match": {
                              "content": {
                                "query": "%s"
                              }
                            }
                          }
                        ]
                      }
                    }
                    """.formatted(vectorStr, topK,
                    escapeJson(retrieveParam.getQuery() != null ? retrieveParam.getQuery() : ""));

            SearchRequest searchRequest = new SearchRequest.Builder()
                    .index(indexName)
                    .size(topK)
                    .query(q -> q.withJson(new StringReader(hybridQuery)))
                    .source(sc -> sc.filter(f -> f.includes("id", "content", "metadata")))
                    .build();

            SearchResponse<Map> response = client.search(searchRequest, Map.class);

            List<RetrievedChunk> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) continue;

                String content = source.get("content") != null
                        ? source.get("content").toString() : "";

                // Score 已经通过 search pipeline 的 min_max normalization 归一化到 0-1
                float score = hit.score() != null ? hit.score().floatValue() : 0f;
                // 兜底确保 0-1 范围
                score = Math.max(0f, Math.min(1f, score));

                results.add(RetrievedChunk.builder()
                        .id(hit.id())
                        .text(content)
                        .score(score)
                        .build());
            }

            log.debug("Retrieved {} chunks from [{}]", results.size(), indexName);
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve from: " + indexName, e);
        }
    }

    private String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceTest.java
git commit -m "feat: OpenSearchRetrieverService 实现混合查询检索"
```

---

## Task 6: RBAC 建表 + 实体类 + Mapper

**Files:**
- Create: `resources/database/upgrade_v1.1_to_v1.2.sql`
- Create: `bootstrap/src/main/java/.../user/dao/entity/RoleDO.java`
- Create: `bootstrap/src/main/java/.../user/dao/entity/RoleKbRelationDO.java`
- Create: `bootstrap/src/main/java/.../user/dao/entity/UserRoleDO.java`
- Create: `bootstrap/src/main/java/.../user/dao/mapper/RoleMapper.java`
- Create: `bootstrap/src/main/java/.../user/dao/mapper/RoleKbRelationMapper.java`
- Create: `bootstrap/src/main/java/.../user/dao/mapper/UserRoleMapper.java`

- [ ] **Step 1: 创建 DDL 迁移脚本**

```sql
-- resources/database/upgrade_v1.1_to_v1.2.sql
-- RBAC: 角色 + 角色-知识库可见性 + 用户-角色关联

CREATE TABLE IF NOT EXISTS t_role (
    id            VARCHAR(20)  PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL UNIQUE,
    description   VARCHAR(256),
    created_by    VARCHAR(64),
    updated_by    VARCHAR(64),
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_role_kb_relation (
    id            VARCHAR(20)  PRIMARY KEY,
    role_id       VARCHAR(20)  NOT NULL,
    kb_id         VARCHAR(20)  NOT NULL,
    created_by    VARCHAR(64),
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          DEFAULT 0
);

CREATE INDEX idx_role_kb_role_id ON t_role_kb_relation(role_id);
CREATE INDEX idx_role_kb_kb_id ON t_role_kb_relation(kb_id);

CREATE TABLE IF NOT EXISTS t_user_role (
    id            VARCHAR(20)  PRIMARY KEY,
    user_id       VARCHAR(20)  NOT NULL,
    role_id       VARCHAR(20)  NOT NULL,
    created_by    VARCHAR(64),
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          DEFAULT 0
);

CREATE INDEX idx_user_role_user_id ON t_user_role(user_id);
CREATE INDEX idx_user_role_role_id ON t_user_role(role_id);
```

- [ ] **Step 2: 执行 DDL**

Run: `psql -h 127.0.0.1 -U ragent -d ragent -f resources/database/upgrade_v1.1_to_v1.2.sql`
Expected: CREATE TABLE / CREATE INDEX 成功

- [ ] **Step 3: 创建实体类**

**RoleDO.java:**
```java
package com.knowledgebase.ai.ragent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_role")
public class RoleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String name;

    private String description;

    private String createdBy;

    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

**RoleKbRelationDO.java:**
```java
package com.knowledgebase.ai.ragent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_role_kb_relation")
public class RoleKbRelationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String roleId;

    private String kbId;

    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

**UserRoleDO.java:**
```java
package com.knowledgebase.ai.ragent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_user_role")
public class UserRoleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    private String roleId;

    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 4: 创建 Mapper 接口**

**RoleMapper.java:**
```java
package com.knowledgebase.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMapper extends BaseMapper<RoleDO> {
}
```

**RoleKbRelationMapper.java:**
```java
package com.knowledgebase.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleKbRelationMapper extends BaseMapper<RoleKbRelationDO> {
}
```

**UserRoleMapper.java:**
```java
package com.knowledgebase.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleDO> {
}
```

- [ ] **Step 5: 验证编译通过**

Run: `mvn clean install -DskipTests -pl bootstrap -am`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add resources/database/upgrade_v1.1_to_v1.2.sql
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleDO.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/UserRoleDO.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleMapper.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleKbRelationMapper.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/UserRoleMapper.java
git commit -m "feat: RBAC 建表脚本和实体类（t_role / t_role_kb_relation / t_user_role）"
```

---

## Task 7: KbAccessService 权限解析

**Files:**
- Create: `bootstrap/src/main/java/.../user/service/KbAccessService.java`
- Create: `bootstrap/src/main/java/.../user/service/impl/KbAccessServiceImpl.java`
- Test: `bootstrap/src/test/java/.../user/service/KbAccessServiceTest.java`

- [ ] **Step 1: 编写测试**

```java
package com.knowledgebase.ai.ragent.user.service;

import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.knowledgebase.ai.ragent.user.service.impl.KbAccessServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbAccessServiceTest {

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleKbRelationMapper roleKbRelationMapper;

    @InjectMocks
    private KbAccessServiceImpl kbAccessService;

    @Test
    void getAccessibleKbIds_returnsDeduplicatedKbIds() {
        // 用户有 2 个角色
        UserRoleDO ur1 = UserRoleDO.builder().roleId("role_1").build();
        UserRoleDO ur2 = UserRoleDO.builder().roleId("role_2").build();
        when(userRoleMapper.selectList(any())).thenReturn(List.of(ur1, ur2));

        // 角色 1 关联 kb_a, kb_b；角色 2 关联 kb_b, kb_c（kb_b 重复）
        RoleKbRelationDO rk1 = RoleKbRelationDO.builder().kbId("kb_a").build();
        RoleKbRelationDO rk2 = RoleKbRelationDO.builder().kbId("kb_b").build();
        RoleKbRelationDO rk3 = RoleKbRelationDO.builder().kbId("kb_b").build();
        RoleKbRelationDO rk4 = RoleKbRelationDO.builder().kbId("kb_c").build();
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(rk1, rk2, rk3, rk4));

        Set<String> result = kbAccessService.getAccessibleKbIds("user_1");

        assertEquals(Set.of("kb_a", "kb_b", "kb_c"), result);
    }

    @Test
    void getAccessibleKbIds_returnsEmptyForUserWithNoRoles() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        Set<String> result = kbAccessService.getAccessibleKbIds("user_no_role");

        assertTrue(result.isEmpty());
    }

    @Test
    void checkAccess_throwsWhenUnauthorized() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertThrows(RuntimeException.class, () ->
                kbAccessService.checkAccess("user_1", "kb_secret"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl bootstrap test -Dtest=KbAccessServiceTest`
Expected: FAIL — `KbAccessService` / `KbAccessServiceImpl` 不存在

- [ ] **Step 3: 创建接口**

```java
package com.knowledgebase.ai.ragent.user.service;

import java.util.Set;

public interface KbAccessService {

    /**
     * 获取用户可访问的所有知识库 ID
     */
    Set<String> getAccessibleKbIds(String userId);

    /**
     * 校验用户是否有权访问指定知识库，无权则抛异常
     */
    void checkAccess(String userId, String kbId);
}
```

- [ ] **Step 4: 创建实现**

```java
package com.knowledgebase.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.knowledgebase.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KbAccessServiceImpl implements KbAccessService {

    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;

    @Override
    public Set<String> getAccessibleKbIds(String userId) {
        // 1. 查用户所有角色
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRoleDO>()
                        .eq(UserRoleDO::getUserId, userId));

        if (userRoles.isEmpty()) {
            return Collections.emptySet();
        }

        List<String> roleIds = userRoles.stream()
                .map(UserRoleDO::getRoleId)
                .toList();

        // 2. 查角色关联的所有知识库
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                new LambdaQueryWrapper<RoleKbRelationDO>()
                        .in(RoleKbRelationDO::getRoleId, roleIds));

        // 3. 去重返回
        return relations.stream()
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toSet());
    }

    @Override
    public void checkAccess(String userId, String kbId) {
        Set<String> accessible = getAccessibleKbIds(userId);
        if (!accessible.contains(kbId)) {
            throw new ClientException("无权访问该知识库");
        }
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `mvn -pl bootstrap test -Dtest=KbAccessServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceTest.java
git commit -m "feat: KbAccessService 知识库权限解析（用户→角色→知识库）"
```

---

## Task 8: RoleService 角色管理 + Controller

**Files:**
- Create: `bootstrap/src/main/java/.../user/service/RoleService.java`
- Create: `bootstrap/src/main/java/.../user/service/impl/RoleServiceImpl.java`
- Create: `bootstrap/src/main/java/.../user/controller/RoleController.java`
- Create: `bootstrap/src/main/java/.../user/controller/request/RoleCreateRequest.java`
- Create: `bootstrap/src/main/java/.../user/controller/request/RoleUpdateRequest.java`
- Create: `bootstrap/src/main/java/.../user/controller/vo/RoleVO.java`

- [ ] **Step 1: 创建请求/响应 DTO**

**RoleCreateRequest.java:**
```java
package com.knowledgebase.ai.ragent.user.controller.request;

import lombok.Data;

import java.util.List;

@Data
public class RoleCreateRequest {

    private String name;

    private String description;

    /** 关联的知识库 ID 列表 */
    private List<String> kbIds;
}
```

**RoleUpdateRequest.java:**
```java
package com.knowledgebase.ai.ragent.user.controller.request;

import lombok.Data;

import java.util.List;

@Data
public class RoleUpdateRequest {

    private String name;

    private String description;

    /** 关联的知识库 ID 列表（全量替换） */
    private List<String> kbIds;
}
```

**RoleVO.java:**
```java
package com.knowledgebase.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleVO {

    private String id;

    private String name;

    private String description;

    /** 关联的知识库 ID 列表 */
    private List<String> kbIds;

    private Date createTime;

    private Date updateTime;
}
```

- [ ] **Step 2: 创建 RoleService 接口**

```java
package com.knowledgebase.ai.ragent.user.service;

import com.knowledgebase.ai.ragent.user.controller.request.RoleCreateRequest;
import com.knowledgebase.ai.ragent.user.controller.request.RoleUpdateRequest;
import com.knowledgebase.ai.ragent.user.controller.vo.RoleVO;

import java.util.List;

public interface RoleService {

    String create(RoleCreateRequest request);

    void update(String roleId, RoleUpdateRequest request);

    void delete(String roleId);

    List<RoleVO> listAll();

    RoleVO getById(String roleId);
}
```

- [ ] **Step 3: 创建 RoleServiceImpl**

```java
package com.knowledgebase.ai.ragent.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.user.controller.request.RoleCreateRequest;
import com.knowledgebase.ai.ragent.user.controller.request.RoleUpdateRequest;
import com.knowledgebase.ai.ragent.user.controller.vo.RoleVO;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleMapper;
import com.knowledgebase.ai.ragent.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;

    @Override
    @Transactional
    public String create(RoleCreateRequest request) {
        // 校验名称唯一
        Long count = roleMapper.selectCount(
                new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getName, request.getName()));
        if (count > 0) {
            throw new ClientException("角色名称已存在");
        }

        RoleDO role = RoleDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        roleMapper.insert(role);

        // 关联知识库
        saveKbRelations(role.getId(), request.getKbIds());

        return role.getId();
    }

    @Override
    @Transactional
    public void update(String roleId, RoleUpdateRequest request) {
        RoleDO role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ClientException("角色不存在");
        }

        if (StringUtils.hasText(request.getName())) {
            role.setName(request.getName());
        }
        if (request.getDescription() != null) {
            role.setDescription(request.getDescription());
        }
        role.setUpdatedBy(UserContext.getUsername());
        roleMapper.updateById(role);

        // 全量替换知识库关联
        if (request.getKbIds() != null) {
            // 逻辑删除旧关联
            roleKbRelationMapper.delete(
                    new LambdaQueryWrapper<RoleKbRelationDO>()
                            .eq(RoleKbRelationDO::getRoleId, roleId));
            saveKbRelations(roleId, request.getKbIds());
        }
    }

    @Override
    @Transactional
    public void delete(String roleId) {
        roleMapper.deleteById(roleId);
        roleKbRelationMapper.delete(
                new LambdaQueryWrapper<RoleKbRelationDO>()
                        .eq(RoleKbRelationDO::getRoleId, roleId));
    }

    @Override
    public List<RoleVO> listAll() {
        List<RoleDO> roles = roleMapper.selectList(
                new LambdaQueryWrapper<RoleDO>().orderByDesc(RoleDO::getCreateTime));
        return roles.stream().map(this::toVO).toList();
    }

    @Override
    public RoleVO getById(String roleId) {
        RoleDO role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ClientException("角色不存在");
        }
        return toVO(role);
    }

    private RoleVO toVO(RoleDO role) {
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                new LambdaQueryWrapper<RoleKbRelationDO>()
                        .eq(RoleKbRelationDO::getRoleId, role.getId()));
        List<String> kbIds = relations.stream()
                .map(RoleKbRelationDO::getKbId).toList();

        return RoleVO.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .kbIds(kbIds)
                .createTime(role.getCreateTime())
                .updateTime(role.getUpdateTime())
                .build();
    }

    private void saveKbRelations(String roleId, List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return;
        String username = UserContext.getUsername();
        for (String kbId : kbIds) {
            RoleKbRelationDO relation = RoleKbRelationDO.builder()
                    .roleId(roleId)
                    .kbId(kbId)
                    .createdBy(username)
                    .build();
            roleKbRelationMapper.insert(relation);
        }
    }
}
```

- [ ] **Step 4: 创建 RoleController**

```java
package com.knowledgebase.ai.ragent.user.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.knowledgebase.ai.ragent.framework.convention.Result;
import com.knowledgebase.ai.ragent.framework.web.Results;
import com.knowledgebase.ai.ragent.user.controller.request.RoleCreateRequest;
import com.knowledgebase.ai.ragent.user.controller.request.RoleUpdateRequest;
import com.knowledgebase.ai.ragent.user.controller.vo.RoleVO;
import com.knowledgebase.ai.ragent.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @SaCheckRole("admin")
    @PostMapping("/role")
    public Result<String> create(@RequestBody RoleCreateRequest request) {
        return Results.success(roleService.create(request));
    }

    @SaCheckRole("admin")
    @PutMapping("/role/{role-id}")
    public Result<Void> update(@PathVariable("role-id") String roleId,
                               @RequestBody RoleUpdateRequest request) {
        roleService.update(roleId, request);
        return Results.success();
    }

    @SaCheckRole("admin")
    @DeleteMapping("/role/{role-id}")
    public Result<Void> delete(@PathVariable("role-id") String roleId) {
        roleService.delete(roleId);
        return Results.success();
    }

    @SaCheckRole("admin")
    @GetMapping("/role")
    public Result<List<RoleVO>> listAll() {
        return Results.success(roleService.listAll());
    }

    @SaCheckRole("admin")
    @GetMapping("/role/{role-id}")
    public Result<RoleVO> getById(@PathVariable("role-id") String roleId) {
        return Results.success(roleService.getById(roleId));
    }
}
```

- [ ] **Step 5: 验证编译通过**

Run: `mvn clean install -DskipTests -pl bootstrap -am`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/RoleCreateRequest.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/RoleUpdateRequest.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/RoleVO.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/RoleService.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java
git commit -m "feat: 角色管理 CRUD（RoleService + RoleController，admin only）"
```

---

## Task 9: 防越权校验集成

**Files:**
- Modify: `bootstrap/src/main/java/.../knowledge/controller/KnowledgeBaseController.java`
- Modify: `bootstrap/src/main/java/.../knowledge/service/impl/KnowledgeBaseServiceImpl.java`
- Modify: `bootstrap/src/main/java/.../rag/controller/RAGChatController.java`

- [ ] **Step 1: KnowledgeBaseController — 详情接口 fail-fast 校验**

在 `KnowledgeBaseController` 中注入 `KbAccessService` 和 `UserContext`，修改 `queryKnowledgeBase` 方法：

在类的字段中添加：

```java
private final KbAccessService kbAccessService;
```

修改 `queryKnowledgeBase` 方法：

```java
@GetMapping("/knowledge-base/{kb-id}")
public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
    // fail-fast 权限校验
    if (UserContext.hasUser() && !"admin".equals(UserContext.getRole())) {
        kbAccessService.checkAccess(UserContext.getUserId(), kbId);
    }
    return Results.success(knowledgeBaseService.queryById(kbId));
}
```

添加 import：

```java
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.user.service.KbAccessService;
```

- [ ] **Step 2: KnowledgeBaseServiceImpl — pageQuery 加权限过滤**

在 `KnowledgeBaseServiceImpl` 中注入 `KbAccessService`：

```java
private final KbAccessService kbAccessService;
```

在 `pageQuery` 方法中，构建查询条件时加入权限过滤：

```java
// 在构建 LambdaQueryWrapper 之后，执行分页查询之前添加：
if (UserContext.hasUser() && !"admin".equals(UserContext.getRole())) {
    Set<String> accessibleKbIds = kbAccessService.getAccessibleKbIds(UserContext.getUserId());
    if (accessibleKbIds.isEmpty()) {
        // 无权限，返回空页
        return new Page<>(requestParam.getCurrent(), requestParam.getSize());
    }
    queryWrapper.in(KnowledgeBaseDO::getId, accessibleKbIds);
}
```

添加 import：

```java
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.user.service.KbAccessService;
import java.util.Set;
```

- [ ] **Step 3: RAGChatController — chat 接口 fail-fast 校验**

当前 `RAGChatController.chat()` 的参数中没有 kbId。需要查看 `RAGChatService.streamChat()` 的签名来确定知识库是在哪一层确定的。

> **实现者注意**：需要查看 `RAGChatService` 的实现，确认知识库选择逻辑。如果知识库是在 Service 内部根据意图分类确定的，则 Service 层兜底校验应在检索执行前添加。具体位置取决于 `collectionName` 在 `RetrieveRequest` 中何时被设置。
>
> 对于目前 chat 接口没有显式传入 kbId 的情况，防越权校验应在 `RetrieverService` 调用前的 Service 层中添加，而非 Controller 层。

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean install -DskipTests -pl bootstrap -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGChatController.java
git commit -m "feat: 知识库访问防越权校验（Controller fail-fast + Service 兜底）"
```

---

## Task 10: 用户创建时分配角色 + SaToken 集成

**Files:**
- Modify: `bootstrap/src/main/java/.../user/controller/request/UserCreateRequest.java`
- Modify: `bootstrap/src/main/java/.../user/controller/request/UserUpdateRequest.java`
- Modify: `bootstrap/src/main/java/.../user/service/impl/UserServiceImpl.java`
- Modify: `bootstrap/src/main/java/.../user/config/SaTokenStpInterfaceImpl.java`

- [ ] **Step 1: UserCreateRequest 和 UserUpdateRequest 添加 roleIds 字段**

在 `UserCreateRequest.java` 中添加：

```java
/** 关联的角色 ID 列表 */
private List<String> roleIds;
```

在 `UserUpdateRequest.java` 中添加：

```java
/** 关联的角色 ID 列表（全量替换） */
private List<String> roleIds;
```

两个文件都添加 import：

```java
import java.util.List;
```

- [ ] **Step 2: UserServiceImpl 中处理角色分配**

在 `UserServiceImpl` 中注入：

```java
private final UserRoleMapper userRoleMapper;
```

在 `create()` 方法中，插入用户之后添加角色关联：

```java
// 分配角色
if (requestParam.getRoleIds() != null && !requestParam.getRoleIds().isEmpty()) {
    String username = UserContext.getUsername();
    for (String roleId : requestParam.getRoleIds()) {
        UserRoleDO userRole = UserRoleDO.builder()
                .userId(user.getId())
                .roleId(roleId)
                .createdBy(username)
                .build();
        userRoleMapper.insert(userRole);
    }
}
```

在 `update()` 方法中，添加角色替换逻辑：

```java
// 更新角色关联
if (requestParam.getRoleIds() != null) {
    userRoleMapper.delete(
            new LambdaQueryWrapper<UserRoleDO>()
                    .eq(UserRoleDO::getUserId, id));
    String username = UserContext.getUsername();
    for (String roleId : requestParam.getRoleIds()) {
        UserRoleDO userRole = UserRoleDO.builder()
                .userId(id)
                .roleId(roleId)
                .createdBy(username)
                .build();
        userRoleMapper.insert(userRole);
    }
}
```

添加 import：

```java
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
```

- [ ] **Step 3: SaTokenStpInterfaceImpl 对接 RBAC**

修改 `getPermissionList()` 方法，返回用户可访问的知识库标识：

```java
@Override
public List<String> getPermissionList(Object loginId, String loginType) {
    if (loginId == null) return Collections.emptyList();
    String loginIdStr = loginId.toString();
    if (!StrUtil.isNumeric(loginIdStr)) return Collections.emptyList();

    Set<String> kbIds = kbAccessService.getAccessibleKbIds(loginIdStr);
    return kbIds.stream().map(id -> "kb:" + id).toList();
}
```

在类中注入：

```java
private final KbAccessService kbAccessService;
```

添加 import：

```java
import com.knowledgebase.ai.ragent.user.service.KbAccessService;
import java.util.Set;
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean install -DskipTests -pl bootstrap -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserCreateRequest.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserUpdateRequest.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenStpInterfaceImpl.java
git commit -m "feat: 用户创建/更新时分配角色 + SaToken 权限对接 RBAC"
```

---

## Task 11: 端到端验证

- [ ] **Step 1: 将 application.yaml 切换到 opensearch**

```yaml
rag:
  vector:
    type: opensearch
```

- [ ] **Step 2: 确保 Docker 环境运行中**

Run: `cd resources/docker/opensearch && docker-compose up -d`
Run: `curl http://localhost:9200`
Expected: OpenSearch 版本信息

- [ ] **Step 3: 启动应用**

Run: `mvn -pl bootstrap spring-boot:run`
Expected: 应用启动成功，日志中有 `Created search pipeline [ragent-hybrid-search-pipeline]`

- [ ] **Step 4: 创建知识库，验证 index 创建**

```bash
# 登录获取 token（假设 admin 账户已存在）
# 创建知识库
curl -X POST http://localhost:9090/api/ragent/knowledge-base \
  -H "Content-Type: application/json" \
  -H "satoken: <token>" \
  -d '{"name":"测试知识库","collectionName":"test_e2e_kb","embeddingModel":"qwen3-embedding:8b-fp16"}'
```

验证 OpenSearch index 已创建：

```bash
curl http://localhost:9200/test_e2e_kb/_mapping
```

Expected: 返回包含 knn_vector、ik_max_word 分词器的 mapping

- [ ] **Step 5: 创建角色并分配知识库**

```bash
curl -X POST http://localhost:9090/api/ragent/role \
  -H "Content-Type: application/json" \
  -H "satoken: <token>" \
  -d '{"name":"测试角色","description":"可访问测试知识库","kbIds":["<kb_id>"]}'
```

- [ ] **Step 6: 创建普通用户并分配角色**

```bash
curl -X POST http://localhost:9090/api/ragent/user \
  -H "Content-Type: application/json" \
  -H "satoken: <token>" \
  -d '{"username":"testuser","password":"123456","role":"user","roleIds":["<role_id>"]}'
```

- [ ] **Step 7: 用普通用户登录，验证只能看到有权知识库**

```bash
# 用 testuser 登录
# 查询知识库列表
curl http://localhost:9090/api/ragent/knowledge-base \
  -H "satoken: <user_token>"
```

Expected: 只返回 "测试知识库"

- [ ] **Step 8: 切回 rag.vector.type: pg，验证 Milvus/PG 仍可正常工作**

修改 application.yaml 的 `rag.vector.type` 回 `pg`，重启应用，验证原有功能不受影响。

- [ ] **Step 9: Commit 配置回切**

```bash
git commit -am "test: 端到端验证通过，恢复默认配置"
```
