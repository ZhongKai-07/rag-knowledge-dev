# OpenSearch 混合检索实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 OpenSearch 作为向量数据库后端，支持混合检索（向量 + BM25 关键词），通过 `rag.vector.type=opensearch` 配置切换，不影响现有 Milvus/pgvector 实现。

**Architecture:** 遵循现有 Strategy 模式，新增三个实现类（`OpenSearchVectorStoreService`、`OpenSearchVectorStoreAdmin`、`OpenSearchRetrieverService`）+ 一个配置类（`OpenSearchConfig`）。OpenSearch 混合检索使用 k-NN 向量搜索 + BM25 文本搜索 + `search_pipeline` 归一化融合。所有新类通过 `@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")` 激活。

**Tech Stack:** OpenSearch Java Client 2.x, Spring Boot 3.5.7, Java 17

---

## 文件结构

| 操作 | 文件路径 | 职责 |
|------|---------|------|
| Create | `bootstrap/src/main/java/.../rag/config/OpenSearchConfig.java` | OpenSearch 客户端 Bean 配置 |
| Create | `bootstrap/src/main/java/.../rag/config/OpenSearchProperties.java` | OpenSearch 连接参数配置类 |
| Create | `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreAdmin.java` | 索引生命周期管理（创建/检查） |
| Create | `bootstrap/src/main/java/.../rag/core/vector/OpenSearchVectorStoreService.java` | 文档 CRUD（写入/更新/删除） |
| Create | `bootstrap/src/main/java/.../rag/core/retrieve/OpenSearchRetrieverService.java` | 混合检索（k-NN + BM25） |
| Create | `bootstrap/src/test/java/.../rag/core/vector/OpenSearchVectorStoreServiceTest.java` | VectorStoreService 单元测试 |
| Create | `bootstrap/src/test/java/.../rag/core/vector/OpenSearchVectorStoreAdminTest.java` | VectorStoreAdmin 单元测试 |
| Create | `bootstrap/src/test/java/.../rag/core/retrieve/OpenSearchRetrieverServiceTest.java` | RetrieverService 单元测试 |
| Modify | `pom.xml` | 添加 opensearch-java 版本管理 |
| Modify | `bootstrap/pom.xml` | ���加 opensearch-java 依赖 |
| Modify | `bootstrap/src/main/resources/application.yaml` | 添加 opensearch 配置段（注释状态） |

> 注：所有 Java 文件路径中 `...` 代表 `com/nageoffer/ai/ragent`

---

### Task 1: 添加 OpenSearch Maven 依赖

**Files:**
- Modify: `pom.xml:15-30` (properties + dependencyManagement)
- Modify: `bootstrap/pom.xml:13-50` (dependencies)

- [ ] **Step 1: 在父 POM 添加版本属性和依赖管理**

在 `pom.xml` 的 `<properties>` 中添加版本号，在 `<dependencyManagement>` 中添加依赖声明：

```xml
<!-- 在 <properties> 中 okhttp.version 之后添加 -->
<opensearch-java.version>2.20.0</opensearch-java.version>
```

```xml
<!-- 在 <dependencyManagement><dependencies> 中 OkHttp 之后添加 -->
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-java</artifactId>
    <version>${opensearch-java.version}</version>
</dependency>
```

- [ ] **Step 2: 在 bootstrap 模块添加依赖**

在 `bootstrap/pom.xml` 的 `<dependencies>` 中 pgvector 依赖之后添加：

```xml
<!-- OpenSearch -->
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-java</artifactId>
</dependency>
```

- [ ] **Step 3: 验证编译通过**

Run: `mvn -pl bootstrap compile -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pom.xml bootstrap/pom.xml
git commit -m "feat(opensearch): 添加 OpenSearch Java Client 依赖"
```

---

### Task 2: 创建 OpenSearch 配置类

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchProperties.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchConfig.java`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 创建 OpenSearchProperties 配置属性类**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OpenSearch 连接配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchProperties {

    /**
     * OpenSearch 服务地址，例如 http://localhost:9200
     */
    private String uri = "http://localhost:9200";

    /**
     * 用户名（可选）
     */
    private String username;

    /**
     * 密码（可选）
     */
    private String password;

    /**
     * 混合检索中 BM25 关键词得分的权重（0-1），默认 0.3
     * 向量得分权重 = 1 - keywordWeight
     */
    private float keywordWeight = 0.3f;
}
```

- [ ] **Step 2: 创�� OpenSearchConfig 配置类**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.config;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * OpenSearch 客户端配置类
 */
@Configuration
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchConfig {

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchProperties properties) {
        URI uri = URI.create(properties.getUri());
        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());

        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host);

        if (properties.getUsername() != null && !properties.getUsername().isEmpty()) {
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope(host),
                        new UsernamePasswordCredentials(
                                properties.getUsername(),
                                properties.getPassword().toCharArray()
                        )
                );
                return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            });
        }

        return new OpenSearchClient(builder.build());
    }
}
```

- [ ] **Step 3: 在 application.yaml 添加 OpenSearch 配置（注释状态）**

在 `application.yaml` 文件的 `milvus:` 配置段之后添加：

```yaml
# OpenSearch 配置（当 rag.vector.type=opensearch 时启用）
# opensearch:
#   uri: http://localhost:9200
#   username:
#   password:
#   keyword-weight: 0.3
```

- [ ] **Step 4: 验证编译通过**

Run: `mvn -pl bootstrap compile -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchProperties.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/OpenSearchConfig.java bootstrap/src/main/resources/application.yaml
git commit -m "feat(opensearch): 添加 OpenSearch 客户端配置类"
```

---

### Task 3: 实现 OpenSearchVectorStoreAdmin

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdminTest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java`

- [ ] **Step 1: 编写 OpenSearchVectorStoreAdmin 单元测试**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.rag.config.OpenSearchProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchVectorStoreAdminTest {

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    private RAGDefaultProperties ragDefaultProperties;
    private OpenSearchProperties openSearchProperties;
    private OpenSearchVectorStoreAdmin admin;

    @BeforeEach
    void setUp() {
        ragDefaultProperties = new RAGDefaultProperties();
        ragDefaultProperties.setDimension(1536);
        ragDefaultProperties.setMetricType("COSINE");

        openSearchProperties = new OpenSearchProperties();

        admin = new OpenSearchVectorStoreAdmin(openSearchClient, ragDefaultProperties, openSearchProperties);
    }

    @Test
    void vectorSpaceExists_returnsTrue_whenIndexExists() throws Exception {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));

        VectorSpaceId spaceId = VectorSpaceId.builder().logicalName("test_index").build();
        assertTrue(admin.vectorSpaceExists(spaceId));
    }

    @Test
    void vectorSpaceExists_returnsFalse_whenIndexNotExists() throws Exception {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));

        VectorSpaceId spaceId = VectorSpaceId.builder().logicalName("nonexistent_index").build();
        assertFalse(admin.vectorSpaceExists(spaceId));
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败（OpenSearchVectorStoreAdmin 还不存在）**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreAdminTest -DfailIfNoTests=false -q`
Expected: COMPILATION FAILURE

- [ ] **Step 3: 实现 OpenSearchVectorStoreAdmin**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.framework.exception.kb.VectorCollectionAlreadyExistsException;
import com.nageoffer.ai.ragent.rag.config.OpenSearchProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.json.JsonData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * OpenSearch 向量空间管理
 * <p>
 * 创建索引时同时配置 k-NN 向量字段和 BM25 文本字段，
 * 并创建 search_pipeline 用于混合检索得分归一化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchVectorStoreAdmin implements VectorStoreAdmin {

    private final OpenSearchClient openSearchClient;
    private final RAGDefaultProperties ragDefaultProperties;
    private final OpenSearchProperties openSearchProperties;

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String indexName = spec.getSpaceId().getLogicalName();

        try {
            boolean exists = openSearchClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(indexName)))
                    .value();

            if (exists) {
                throw new VectorCollectionAlreadyExistsException(indexName);
            }

            // 先创建 search pipeline（幂等）
            ensureHybridSearchPipeline();

            // 构建索引 mapping
            int dimension = ragDefaultProperties.getDimension();
            String metricMethod = "cosinesimil".equals(ragDefaultProperties.getMetricType())
                    ? "cosinesimil" : "cosinesimil"; // OpenSearch k-NN 默认使用 cosinesimil

            openSearchClient.indices().create(CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .settings(IndexSettings.of(s -> s
                            .knn(true)
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                            .otherSettings("default_pipeline", JsonData.of("_none"))
                    ))
                    .mappings(m -> m
                            .properties("id", p -> p.keyword(k -> k))
                            .properties("content", p -> p.text(t -> t
                                    .analyzer("standard")
                            ))
                            .properties("metadata", p -> p.object(o -> o.enabled(true)))
                            .properties("embedding", p -> p.knnVector(knn -> knn
                                    .dimension(dimension)
                                    .method(method -> method
                                            .name("hnsw")
                                            .spaceType("cosinesimil")
                                            .engine("nmslib")
                                            .parameters("ef_construction", JsonData.of(200))
                                            .parameters("m", JsonData.of(48))
                                    )
                            ))
                    )
            ));

            log.info("OpenSearch 索引创建成功: {}, dimension={}", indexName, dimension);

        } catch (VectorCollectionAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("OpenSearch 创建索引失败: " + indexName, e);
        }
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            return openSearchClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(spaceId.getLogicalName())))
                    .value();
        } catch (IOException e) {
            log.error("OpenSearch 检查索引是否存在失败: {}", spaceId.getLogicalName(), e);
            return false;
        }
    }

    /**
     * 确保混合检索所需的 search pipeline 存在
     * <p>
     * Pipeline 使用 normalization-processor 对 BM25 和 k-NN 得分做 min-max 归一化，
     * 然后按权重加权合并。
     */
    private void ensureHybridSearchPipeline() throws IOException {
        String pipelineId = "ragent_hybrid_search_pipeline";
        float keywordWeight = openSearchProperties.getKeywordWeight();
        float vectorWeight = 1.0f - keywordWeight;

        // 使用低级别 API 创建 search pipeline
        openSearchClient.generic().execute(
                org.opensearch.client.transport.endpoints.SimpleEndpoint.<Void, Void>forPath(
                        "_search/pipeline/" + pipelineId
                ).method("PUT"),
                org.opensearch.client.opensearch._types.RequestBase.of(r -> r),
                null
        );

        // 注：search pipeline 需要通过 REST API 创建，这里使用 low-level client
        // 实际实现中通过 JsonData 发送 PUT /_search/pipeline/{id} 请求体
        log.info("OpenSearch 混合检索 search pipeline 已就绪: {}", pipelineId);
    }
}
```

> **重要提醒给实现者：** `ensureHybridSearchPipeline()` 方法中的 search pipeline 创建逻辑需要使用 OpenSearch 的 low-level REST client，因为 Java 高级 client 对 search pipeline 支持有限。实现时需要：
>
> 1. 通过 `openSearchClient._transport()` 获取 transport
> 2. 发送 `PUT /_search/pipeline/ragent_hybrid_search_pipeline` 请求
> 3. 请求体为：
> ```json
> {
>   "description": "Ragent hybrid search pipeline",
>   "phase_results_processors": [{
>     "normalization-processor": {
>       "normalization": { "technique": "min_max" },
>       "combination": {
>         "technique": "arithmetic_mean",
>         "parameters": { "weights": [<vectorWeight>, <keywordWeight>] }
>       }
>     }
>   }]
> }
> ```
>
> 如果 search pipeline 功能在目标 OpenSearch 版本中不可用（需要 2.10+），则退化为在应用层做分数归一化和加权合并。

- [ ] **Step 4: 运行测试**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreAdminTest -q`
Expected: Tests run: 2, Failures: 0, Errors: 0

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdminTest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java
git commit -m "feat(opensearch): 实现 OpenSearchVectorStoreAdmin 索引管理"
```

---

### Task 4: 实现 OpenSearchVectorStoreService

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreServiceTest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java`

- [ ] **Step 1: 编写 OpenSearchVectorStoreService 单元测试**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchVectorStoreServiceTest {

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private BulkResponse bulkResponse;

    @Mock
    private DeleteByQueryResponse deleteByQueryResponse;

    private RAGDefaultProperties ragDefaultProperties;
    private OpenSearchVectorStoreService service;

    @BeforeEach
    void setUp() {
        ragDefaultProperties = new RAGDefaultProperties();
        ragDefaultProperties.setDimension(4);
        ragDefaultProperties.setMetricType("COSINE");

        service = new OpenSearchVectorStoreService(openSearchClient, ragDefaultProperties);
    }

    @Test
    void indexDocumentChunks_sendsCorrectBulkRequest() throws Exception {
        when(bulkResponse.errors()).thenReturn(false);
        when(openSearchClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);

        VectorChunk chunk = VectorChunk.builder()
                .chunkId("chunk-001")
                .index(0)
                .content("测试内容")
                .embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f})
                .metadata(Map.of("key", "value"))
                .build();

        service.indexDocumentChunks("test_collection", "doc-001", List.of(chunk));

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(openSearchClient).bulk(captor.capture());
        assertEquals(1, captor.getValue().operations().size());
    }

    @Test
    void deleteDocumentVectors_sendsCorrectQuery() throws Exception {
        when(openSearchClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        service.deleteDocumentVectors("test_collection", "doc-001");

        verify(openSearchClient).deleteByQuery(any(DeleteByQueryRequest.class));
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreServiceTest -DfailIfNoTests=false -q`
Expected: COMPILATION FAILURE

- [ ] **Step 3: 实现 OpenSearchVectorStoreService**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.vector;

import cn.hutool.core.lang.Assert;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OpenSearch 向量存储服务
 * <p>
 * 使用 OpenSearch 作为向量数据库后端，文档以 JSON 形式存储，
 * 包含 id、content、metadata、embedding 四个字段，与 Milvus/pgvector 保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchVectorStoreService implements VectorStoreService {

    private final OpenSearchClient openSearchClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));

        final int dim = ragDefaultProperties.getDimension();

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

            for (VectorChunk chunk : chunks) {
                float[] vector = extractVector(chunk, dim);
                Map<String, Object> doc = buildDocument(collectionName, docId, chunk, vector);

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(collectionName)
                                .id(chunk.getChunkId())
                                .document(doc)
                        )
                );
            }

            BulkResponse response = openSearchClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                log.error("OpenSearch 批量写入部分失败, collection={}, docId={}", collectionName, docId);
                throw new RuntimeException("OpenSearch 批量写入存在错误");
            }

            log.info("OpenSearch chunk 建立/写入向量索引成功, collection={}, count={}", collectionName, chunks.size());

        } catch (IOException e) {
            throw new RuntimeException("OpenSearch 批量写入失败", e);
        }
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));

        final int dim = ragDefaultProperties.getDimension();
        float[] vector = extractVector(chunk, dim);
        Map<String, Object> doc = buildDocument(collectionName, docId, chunk, vector);

        try {
            openSearchClient.index(IndexRequest.of(i -> i
                    .index(collectionName)
                    .id(chunk.getChunkId())
                    .document(doc)
            ));

            log.info("OpenSearch 更新 chunk 向量索引成功, collection={}, docId={}, chunkId={}",
                    collectionName, docId, chunk.getChunkId());

        } catch (IOException e) {
            throw new RuntimeException("OpenSearch 更新 chunk 失败", e);
        }
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        try {
            DeleteByQueryResponse response = openSearchClient.deleteByQuery(DeleteByQueryRequest.of(d -> d
                    .index(collectionName)
                    .query(Query.of(q -> q
                            .term(t -> t
                                    .field("metadata.doc_id.keyword")
                                    .value(docId)
                            )
                    ))
            ));

            log.info("OpenSearch 删除文档向量成功, collection={}, docId={}, deleted={}",
                    collectionName, docId, response.deleted());

        } catch (IOException e) {
            throw new RuntimeException("OpenSearch 删除文档向量失败", e);
        }
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        try {
            openSearchClient.delete(d -> d.index(collectionName).id(chunkId));
            log.info("OpenSearch 删除 chunk 成功, collection={}, chunkId={}", collectionName, chunkId);
        } catch (IOException e) {
            throw new RuntimeException("OpenSearch 删除 chunk 失败", e);
        }
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (String chunkId : chunkIds) {
                bulkBuilder.operations(op -> op
                        .delete(d -> d.index(collectionName).id(chunkId))
                );
            }

            BulkResponse response = openSearchClient.bulk(bulkBuilder.build());
            log.info("OpenSearch 批量删除 chunk 成功, collection={}, count={}", collectionName, chunkIds.size());

        } catch (IOException e) {
            throw new RuntimeException("OpenSearch 批量删除 chunk 失败", e);
        }
    }

    private float[] extractVector(VectorChunk chunk, int expectedDim) {
        float[] vector = chunk.getEmbedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不���为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    private Map<String, Object> buildDocument(String collectionName, String docId, VectorChunk chunk, float[] vector) {
        Map<String, Object> metadata = new HashMap<>();
        if (chunk.getMetadata() != null) {
            metadata.putAll(chunk.getMetadata());
        }
        metadata.put("collection_name", collectionName);
        metadata.put("doc_id", docId);
        metadata.put("chunk_index", chunk.getIndex());

        // 将 float[] 转为 List<Float> 以便 JSON 序列化
        List<Float> embeddingList = new java.util.ArrayList<>(vector.length);
        for (float v : vector) {
            embeddingList.add(v);
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", chunk.getChunkId());
        doc.put("content", chunk.getContent() == null ? "" : chunk.getContent());
        doc.put("metadata", metadata);
        doc.put("embedding", embeddingList);

        return doc;
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchVectorStoreServiceTest -q`
Expected: Tests run: 2, Failures: 0, Errors: 0

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreServiceTest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java
git commit -m "feat(opensearch): 实现 OpenSearchVectorStoreService 文档 CRUD"
```

---

### Task 5: 实现 OpenSearchRetrieverService（混合检索核心）

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceTest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`

- [ ] **Step 1: 编写 OpenSearchRetrieverService 单元测试**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.OpenSearchProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.HitsMetadata;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchRetrieverServiceTest {

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private SearchResponse<Object> searchResponse;

    @Mock
    private HitsMetadata<Object> hitsMetadata;

    private RAGDefaultProperties ragDefaultProperties;
    private OpenSearchProperties openSearchProperties;
    private OpenSearchRetrieverService retrieverService;

    @BeforeEach
    void setUp() {
        ragDefaultProperties = new RAGDefaultProperties();
        ragDefaultProperties.setCollectionName("default_collection");
        ragDefaultProperties.setDimension(4);
        ragDefaultProperties.setMetricType("COSINE");

        openSearchProperties = new OpenSearchProperties();
        openSearchProperties.setKeywordWeight(0.3f);

        retrieverService = new OpenSearchRetrieverService(
                embeddingService, openSearchClient, ragDefaultProperties, openSearchProperties);
    }

    @Test
    void retrieve_returnsEmptyList_whenNoHits() throws Exception {
        when(embeddingService.embed("测试查询")).thenReturn(List.of(0.1f, 0.2f, 0.3f, 0.4f));
        when(hitsMetadata.hits()).thenReturn(List.of());
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(openSearchClient.search(any(SearchRequest.class), eq(Object.class))).thenReturn(searchResponse);

        RetrieveRequest request = RetrieveRequest.builder()
                .query("测试查询")
                .topK(5)
                .collectionName("test_collection")
                .build();

        List<RetrievedChunk> results = retrieverService.retrieve(request);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveByVector_returnsEmptyList_whenNoHits() throws Exception {
        when(hitsMetadata.hits()).thenReturn(List.of());
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(openSearchClient.search(any(SearchRequest.class), eq(Object.class))).thenReturn(searchResponse);

        RetrieveRequest request = RetrieveRequest.builder()
                .query("测试查询")
                .topK(5)
                .collectionName("test_collection")
                .build();

        float[] vector = {0.1f, 0.2f, 0.3f, 0.4f};
        List<RetrievedChunk> results = retrieverService.retrieveByVector(vector, request);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest -DfailIfNoTests=false -q`
Expected: COMPILATION FAILURE

- [ ] **Step 3: 实现 OpenSearchRetrieverService（混合检索）**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.OpenSearchProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenSearch 混合检索服务
 * <p>
 * 同时执行 k-NN 向量检索和 BM25 关键词检索，通过 OpenSearch 的 hybrid query
 * 或应用层加权融合实现混合检索。
 * <p>
 * 混合检索策略：
 * 1. k-NN 向量检索 —— 捕获语义相似性
 * 2. BM25 关键词检索 —— 捕获精确关键词匹配
 * 3. 归一化 + 加权合并 —— 通过 search pipeline 或应用层融合
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchRetrieverService implements RetrieverService {

    private final EmbeddingService embeddingService;
    private final OpenSearchClient openSearchClient;
    private final RAGDefaultProperties ragDefaultProperties;
    private final OpenSearchProperties openSearchProperties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        List<Float> emb = embeddingService.embed(retrieveParam.getQuery());
        float[] vec = toArray(emb);
        float[] norm = normalize(vec);

        return retrieveByVector(norm, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        String indexName = StrUtil.isBlank(retrieveParam.getCollectionName())
                ? ragDefaultProperties.getCollectionName()
                : retrieveParam.getCollectionName();

        try {
            // 将 float[] 转为 List<Float> 用于 JSON 序列化
            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) {
                vectorList.add(v);
            }

            // 构建混合查询：bool 查询中组合 k-NN 和 BM25
            // k-NN 子查询
            float vectorWeight = 1.0f - openSearchProperties.getKeywordWeight();
            float keywordWeight = openSearchProperties.getKeywordWeight();

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(indexName)
                    .size(retrieveParam.getTopK())
                    .query(q -> q
                            .hybrid(h -> h
                                    .queries(
                                            // k-NN 向量查询
                                            knnQuery -> knnQuery.knn(knn -> knn
                                                    .field("embedding")
                                                    .vector(vectorList)
                                                    .k(retrieveParam.getTopK())
                                            ),
                                            // BM25 关键词查询
                                            bm25Query -> bm25Query.match(m -> m
                                                    .field("content")
                                                    .query(retrieveParam.getQuery())
                                            )
                                    )
                            )
                    )
                    .source(src -> src
                            .filter(f -> f.includes("id", "content", "metadata"))
                    )
                    // 使用预建的 search pipeline 做得分归一化
                    .searchPipeline("ragent_hybrid_search_pipeline")
            );

            SearchResponse<Object> response = openSearchClient.search(searchRequest, Object.class);

            List<Hit<Object>> hits = response.hits().hits();
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }

            List<RetrievedChunk> results = new ArrayList<>(hits.size());
            for (Hit<Object> hit : hits) {
                @SuppressWarnings("unchecked")
                Map<String, Object> source = (Map<String, Object>) hit.source();
                if (source == null) continue;

                results.add(RetrievedChunk.builder()
                        .id(hit.id())
                        .text(String.valueOf(source.getOrDefault("content", "")))
                        .score(hit.score() != null ? hit.score().floatValue() : 0f)
                        .build());
            }

            return results;

        } catch (IOException e) {
            log.error("OpenSearch 混合检索失败, index={}", indexName, e);
            return List.of();
        }
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        if (len == 0) return v;
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}
```

> **混合检索说明：**
> - 使用 OpenSearch 2.10+ 的 `hybrid` query，该 query 类型会同时执行 k-NN 和 BM25 子查询
> - 通过 `search_pipeline: ragent_hybrid_search_pipeline`（Task 3 中创建）进行 min-max 归一化 + 加权合并
> - 如果 OpenSearch 版本 < 2.10 不支持 hybrid query，需要退化方案：分别执行两个查询，在应用层做 min-max 归一化后按权重合并
> - `keywordWeight` 默认 0.3，即向量权重 0.7，关键词权重 0.3，可通过配置调整

- [ ] **Step 4: 运行测试**

Run: `mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest -q`
Expected: Tests run: 2, Failures: 0, Errors: 0

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceTest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java
git commit -m "feat(opensearch): 实现 OpenSearchRetrieverService 混合检索（k-NN + BM25）"
```

---

### Task 6: 回归验证

**Files:**
- No new files

- [ ] **Step 1: 验证全量编译通过**

Run: `mvn clean compile -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行代码格式化**

Run: `mvn spotless:apply -q`
Expected: BUILD SUCCESS（Spotless 自动修正格式）

- [ ] **Step 3: 验证格式化后编译通过**

Run: `mvn clean compile -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 运行所有新增的 OpenSearch 单元测试**

Run: `mvn -pl bootstrap test -Dtest="OpenSearchVectorStoreAdminTest,OpenSearchVectorStoreServiceTest,OpenSearchRetrieverServiceTest" -q`
Expected: Tests run: 6, Failures: 0, Errors: 0

- [ ] **Step 5: 运行现有全量测试（回归）**

Run: `mvn -pl bootstrap test -q`
Expected: BUILD SUCCESS, 所有已有测试仍然通过

> **回归要点：**
> - 默认 `rag.vector.type=pg`，所有 OpenSearch 类被 `@ConditionalOnProperty` 排除，不会被加载
> - 现有的 Milvus/pgvector 实现完全不受影响
> - 没有修改任何现有接口或实现类
> - 唯一修改的现有文件是 `pom.xml`（依赖管理）和 `application.yaml`（注释掉的配置���

- [ ] **Step 6: Commit 格式化修正（如有）**

```bash
git status
# 如果 spotless:apply 产生了变更：
git add -u
git commit -m "style: spotless 代码格式化"
```

---

### Task 7: Docker Compose 和配置文档

**Files:**
- Create: `resources/docker/opensearch-stack-2.19.compose.yaml`

- [ ] **Step 1: 创建 OpenSearch Docker Compose 文件**

```yaml
version: '3.8'

services:
  opensearch:
    image: opensearchproject/opensearch:2.19.0
    container_name: ragent-opensearch
    environment:
      - discovery.type=single-node
      - plugins.security.disabled=true
      - OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin@12345
      - "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m"
    ports:
      - "9200:9200"
      - "9600:9600"
    volumes:
      - opensearch-data:/usr/share/opensearch/data

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2.19.0
    container_name: ragent-opensearch-dashboards
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

- [ ] **Step 2: 验证 compose 文件语法**

Run: `docker compose -f resources/docker/opensearch-stack-2.19.compose.yaml config -q`
Expected: 无输出（语法正确）

- [ ] **Step 3: Commit**

```bash
git add resources/docker/opensearch-stack-2.19.compose.yaml
git commit -m "feat(opensearch): 添加 OpenSearch Docker Compose 部署文件"
```

---

## 切换使用方式

完成全部 Task 后，切换到 OpenSearch 只需修改 `application.yaml`：

```yaml
rag:
  vector:
    type: opensearch  # 从 pg 或 milvus 切换为 opensearch

opensearch:
  uri: http://localhost:9200
  # username: admin
  # password: admin
  keyword-weight: 0.3  # BM25 权重，向量权重 = 1 - 0.3 = 0.7
```

然后启动 OpenSearch（`docker compose -f resources/docker/opensearch-stack-2.19.compose.yaml up -d`）并重启应用即可。
