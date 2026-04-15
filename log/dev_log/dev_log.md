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

---

## 2026-04-11 | PR1 — RBAC 安全等级数据层

详情：[`2026-04-11-pr1-rbac-security-level.md`](./2026-04-11-pr1-rbac-security-level.md)

**核心改动**：
- 新增 `sys_dept`（部门），`t_user.dept_id`，`t_role.role_type`（SUPER_ADMIN/DEPT_ADMIN/USER）+ `t_role.max_security_level`（0-3 角色天花板），`t_knowledge_base.dept_id`，`t_knowledge_document.security_level`。
- `KbAccessService` 增加 `isSuperAdmin()`/`isDeptAdmin()`/`getMaxSecurityLevelForKb()`；检索链 `OpenSearchRetrieverService` 支持按 `security_level LTE_OR_MISSING` 过滤。
- `UserProfileLoader` 单次 JOIN 加载 `LoginUser` 身份快照（user + dept + roles）。
- Last-SUPER_ADMIN 不变量：`SuperAdminMutationIntent` sealed interface + `simulateActiveSuperAdminCountAfter` 模拟器。

---

## 2026-04-12 | PR3 — RBAC 前端演示闭环

详情：[`2026-04-12-pr3-rbac-frontend-demo.md`](./2026-04-12-pr3-rbac-frontend-demo.md)

**核心改动**：
- 前端权限单一真相源：`utils/permissions.ts`（`getPermissions` 纯函数 + `usePermissions` hook）、`router/guards.tsx`（`RequireAnyAdmin` / `RequireMenuAccess`）。
- 管理后台新增：部门管理页、KB 共享 Tab（`KbSharingTab`），角色/用户页对 DEPT_ADMIN 自动裁剪为只读或本部门视图。
- Spaces 入口页、`?kbId=` URL 作为空间锁唯一来源，`resetForNewSpace` 防止会话串空间。
- 后端：`SpacesController` 统计 API、`SysDeptController` 部门 CRUD（GLOBAL 硬保护）、KB 创建时 `dept_id` 解析器。
- 验证：`docs/dev/pr3-demo-walkthrough.md`（Mode A UI 闭环）+ `docs/dev/pr3-curl-matrix.http`（Mode B 后端边界 17/18 PASS）。

---

## 2026-04-13 | PR3 收尾 — 权限修复 & 跨部门共享

详情：[`2026-04-13-rbac-permission-fixes.md`](./2026-04-13-rbac-permission-fixes.md)

**核心改动**：
- `t_role_kb_relation.max_security_level` 新增（per-KB 粒度），替代全局 MAX 语义；检索链用 per-KB 解析替代全局 ceiling。
- KB-centric 角色绑定 API（支持跨部门共享）+ 前端 `KbSharingTab` 条件隐藏（DEPT_ADMIN 对非本部门 KB fail-closed）。
- DEPT_ADMIN 可访问 `/admin/roles`（只读）；privilege escalation 守护：`validateRoleAssignment` 禁止分配超出自身 ceiling 的角色/SUPER_ADMIN/DEPT_ADMIN。
- `DashboardController` 补 `@SaCheckRole` 审计修复；fixture 改为幂等。

---

## 2026-04-15 | Upstream 选择性融合

详情：[`2026-04-15-upstream-selective-merge.md`](./2026-04-15-upstream-selective-merge.md)

**核心改动**：
- 对比上游 `nageoffer/ragent` 20 个新 commit，评估 4 个方向后选择性合并高价值低冲突改动。
- `t_message` 新增 `thinking_content`/`thinking_duration` 列，ChatMessage 全链路贯通（存储层就绪，写入端待后续接入）。
- `LLMService.chat(request, modelId)` 重载 + `RoutingLLMService` 路由实现，支持调用方指定模型。
- `ProbeStreamBridge` 替换 `FirstPacketAwaiter` + `ProbeBufferingCallback`（修复上游已知回调乱序 bug + /simplify 发现锁内回放回归并修复）。
- `EnhancerNode`/`EnricherNode`/`ChunkEmbeddingService` 从依赖 infra-ai 内部类改为依赖公开接口（9 处内部依赖 → 0）。
- `RoutingEmbeddingService` 统一用 `executor.executeWithFallback`，移除冗余手动健康检查。
- 17 文件，+320/-423 行（净减 103 行），RBAC/Spaces/OpenSearch/security_level 零改动。

---

## 2026-04-14 | /simplify 审查 + controller 参数名扫雷

详情：本次会话直接在 `main` 合并（PR #3）；审查报告 agent 生成，未单独落文档。

**核心改动**（分 3 commit）：
- `refactor(rbac)`：9 项 /simplify 修复 — `SysDeptServiceImpl` TOCTOU（`@Transactional` + `SELECT FOR UPDATE`）、`sameDept()` helper 去重 5 处不一致 null 判断、`canAssignRole` 前端加 `maxSecurityLevel` 天花板、`KbAccessService.getMaxSecurityLevelsForKbs` 批量解析（单次 DB 查询替代 N 次 Redis hit）、DEPT_ADMIN `getAccessibleKbIds` Redis 缓存化、共享 `SecurityLevelBadge` + `formatDateTime` 组件、清理 narrative 注释与 `java.util.*` FQN。
- `fix(controllers)`：7 controller 显式 `@PathVariable("...")`/`@RequestParam("...")` 补名（Maven 不带 `-parameters` 导致的运行时 `IllegalArgumentException`）。
- `chore`：设计文档/图表 + `.gitignore` 过滤 `.playwright-mcp/`、`log/*.log`、根目录 `*.png`。

**CLAUDE.md 更新**：记录 DB 全清重建命令、pgvector 缺失误报、Spotless 在 `mvn compile` 自动 apply、`@RequiredArgsConstructor + @Qualifier` 安全性（lombok.config 已配 `copyableAnnotations`）、裸标注扫描一行命令。
