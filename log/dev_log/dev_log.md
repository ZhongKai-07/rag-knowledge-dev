# 开发日志

---

## 2026-04-07 | feature/opensearch-and-rbac

### 一、实施计划执行（14 个 Task，全部完成）

#### Phase 1：OpenSearch 向量引擎

| Task | 内容 | 备注 |
|------|------|------|
| 1 | Docker Compose + Maven 依赖 | opensearch-java 2.18.0 |
| 2 | OpenSearchProperties + OpenSearchConfig | 需显式添加 httpclient5 依赖 |
| 3 | OpenSearchVectorStoreAdmin | `SimpleEndpoint.forPath()` 不存在，改用 `Requests.builder()` |
| 4 | OpenSearchVectorStoreService | `FieldValue.of(docId)` API 修正 |
| 5 | OpenSearchRetrieverService | `SearchRequest.Builder.withJson()` 不存在，改用 generic client |
| 6 | MilvusVectorStoreAdmin 幂等修正 + Pg Score 归一化 | |

#### Phase 2：RBAC 权限体系

| Task | 内容 |
|------|------|
| 7 | DDL：t_role / t_role_kb_relation / t_user_role |
| 8 | 三个实体类 + MyBatis Mapper |
| 9 | KbAccessService（Redis 缓存 + 双上下文鉴权） |
| 10 | RoleService + RoleController |

#### Phase 3：检索链路集成

| Task | 内容 |
|------|------|
| 11 | SearchContext.accessibleKbIds + 两个检索通道 RBAC 过滤 |
| 12 | Controller → Service → RetrievalEngine → MultiChannel 全链路贯通 |
| 13 | KnowledgeBase / Document 接口权限校验 |

#### Phase 4：前端

| Task | 内容 |
|------|------|
| 14 | chatStore 新增 knowledgeBaseId + ChatInput 知识库选择器 |

---

### 二、启动调试

| 问题 | 原因 | 修复 |
|------|------|------|
| 第一次启动失败 | `opensearch-java` jar 不在 IDE 运行时 classpath | `mvn clean install -DskipTests` + IDE reload |
| 第二次启动失败 | Task 2 子 Agent 修改 yaml 时将 `type: pg` 误改为 `opensearch` | 还原为 `pg` |
| 第二次启动失败（同时） | `@Bean(destroyMethod = "close")` 但 `OpenSearchClient` 无此方法 | 移除 `destroyMethod` |
| OpenSearch 容器无法启动 | compose 文件中 `plugins.security.disabled=true` 与 `DISABLE_SECURITY_PLUGIN=true` 重复设置冲突 | 删除其中一行 |
| OpenSearch 端口冲突 | 9200/5601 被旧项目占用 | 改为 9201/9601/5602，容器名加 `ragent-` 前缀 |

---

### 三、功能验证与修复

| 问题 | 原因 | 修复 |
|------|------|------|
| 检索返回 0 结果（404） | 切换 opensearch 后未重新摄入，索引不存在 | 重新摄入文档（方案 B） |
| 混合检索重启后失效 | `pipelineReady` 是内存标志，重启归零，需摄入才能恢复 | 新增 `@PostConstruct` 启动时自动检测 pipeline |

---

### 四、当前状态

- 分支：`feature/opensearch-and-rbac`
- 向量引擎：`opensearch`（混合检索已就绪，权重 0.5:0.5）
- RBAC：代码就绪，需执行 `resources/database/upgrade_v1.1_to_v1.2.sql` 建表后可用
- 待办：将本分支合并回 `main`

---

### 五、关键配置说明

**OpenSearch Docker 端口（避免与其他项目冲突）**
- API：9201（容器内 9200）
- 性能分析：9601（容器内 9600）
- Dashboards：5602（容器内 5601）
- 容器名：`ragent-opensearch` / `ragent-opensearch-dashboards`

**混合检索权重调整方式**

修改 `application.yaml`：
```yaml
opensearch:
  hybrid:
    vector-weight: 0.5
    text-weight: 0.5
```
用 curl 直接更新 pipeline，重启应用即可生效（无需重新摄入文档）：
```bash
curl -X PUT http://localhost:9201/_search/pipeline/ragent-hybrid-search-pipeline \
  -H "Content-Type: application/json" \
  -d '{"phase_results_processors":[{"normalization-processor":{"normalization":{"technique":"min_max"},"combination":{"technique":"arithmetic_mean","parameters":{"weights":[0.7,0.3]}}}}]}'
```
