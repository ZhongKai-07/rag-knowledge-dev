# 架构重整 P0 审计报告

- **日期**：2026-04-26
- **状态**：Draft
- **范围**：只做架构盘点与护栏规划；不移动包、不改 API 行为、不改 schema、不改前端路由

## Executive Summary

P0 结论：Maven 模块依赖方向总体可依赖，bootstrap 的 7 个业务域也与文档一致；后续 P1-P3 的风险主要不在模块边界，而在 bootstrap 内部跨域访问和少数基础设施语义泄漏。

本轮确认的最高优先级是 **S0：非 OpenSearch 向量后端仍可通过配置选中，但读过滤与写 metadata 不满足 security-level 不变量**。代码已有 `AuthzPostProcessor` 对普通登录会话 fail-closed，但 Milvus / pgvector 的 retriever 仍忽略 `metadataFilters`，写侧也不写入 `kb_id/security_level`，且系统态 / 离线评估路径不走 `UserContext.hasUser()` 门禁。P1 应先把这个配置风险收口为启动期 fail-fast 或补齐实现。

S1 级别债务集中在三类：`KbAccessService` deprecated 上帝接口仍出现在 RAG/KB/管理路径；非 `admin` 域直接注入其他域 `*Mapper`；Controller 直接注入 mapper。事件边界方面，`sendInTransaction` 与 `TransactionSynchronizationManager` 仍暴露在业务 service，但这是已登记的 ARCH-6 / ARCH-7，不应误报为新问题。

## 基线快照

### Maven 模块

根 `pom.xml` 当前声明 4 个 Maven 子模块：

| 模块 | 职责 | P0 状态 |
| --- | --- | --- |
| `bootstrap` | Spring Boot 主应用；业务域实现 | 符合文档 |
| `framework` | 跨切面基础设施与 security ports | 符合文档 |
| `infra-ai` | LLM / embedding / rerank / 模型路由 | 符合文档 |
| `mcp-server` | MCP server 实现 | 符合文档 |

### Bootstrap 业务域

`bootstrap/src/main/java/com/nageoffer/ai/ragent/` 下当前顶级业务域与计划一致：

| 域 | Java 文件数 | 备注 |
| --- | ---: | --- |
| `rag` | 216 | 最大域；RAG 编排、检索、prompt、trace、source cards |
| `knowledge` | 69 | KB / 文档 / chunk / MQ |
| `ingestion` | 64 | ETL pipeline / intent tree / nodes |
| `user` | 48 | Sa-Token / RBAC / dept / roles |
| `eval` | 46 | Gold dataset / synthesis / eval run |
| `core` | 17 | 解析与分块基础能力 |
| `admin` | 10 | 仪表盘只读聚合 |

### 已知预存架构债

这些已在 `docs/dev/followup/backlog.md` 或 `docs/dev/followup/architecture-backlog.md` 登记，P0 报告引用为背景，不按全新发现处理：

| 编号 | 内容 | P0 关系 |
| --- | --- | --- |
| `SL-1` | Milvus / Pg retriever 未实现 `metadataFilters` | 本轮确认为 P1 首要风险 |
| `SRC-1` | Milvus / Pg retriever 未回填 `kbId/securityLevel/docId/chunkIndex` | 与 SL-1 同族，影响 source cards 与 fail-closed 行为 |
| `ARCH-1` | `KbAccessServiceImpl` god-service | 本轮统计残留调用点 |
| `ARCH-6` | `sendInTransaction` 泄漏 RocketMQ 概念 | 本轮列入事件护栏 |
| `ARCH-7` | `TransactionSynchronizationManager` 出现在业务 service | 本轮列入事件护栏 |
| `SRC-3` | `StreamChatEventHandler` 遗留 ThreadLocal 读取 | 本轮列入 RAG 编排候选 |
| `SEC-1` | 检索通道可能绕过 `AuthzPostProcessor` 的排查项 | 本轮建议用护栏/测试锁住 |

## Maven 模块依赖

| From | To | Scope | 状态 | 备注 |
| --- | --- | --- | --- | --- |
| `bootstrap` | `framework` | compile | 允许 | 主应用依赖基础设施与 ports |
| `bootstrap` | `infra-ai` | compile | 允许 | 主应用依赖 AI client/service |
| `bootstrap` | `mcp-server` | - | 未依赖 | 计划中允许，但当前未出现 |
| `infra-ai` | `framework` | compile | 允许 | 复用 convention / exception / trace |
| `framework` | `bootstrap` | - | 通过 | POM 与 Java import 均未发现 |
| `framework` | `infra-ai` | - | 通过 | POM 与 Java import 均未发现 |
| `infra-ai` | `bootstrap` | - | 通过 | POM 与 Java import 均未发现 |
| `mcp-server` | 内部业务模块 | - | 通过 | 当前独立，只依赖 Spring Web / Gson |

可能弱化边界的 compile-scope 依赖：

| 模块 | 依赖 | 风险 | 建议 |
| --- | --- | --- | --- |
| `framework` | `rocketmq-spring-boot-starter` | MQ 专属语义进入通用 framework；已对应 ARCH-6/9 | 事件抽象阶段迁出或隔离到 infrastructure adapter |
| `framework` | `mybatis-plus-*` | framework 提供数据库通用配置，当前可接受；但不要放业务 mapper | 先记录，ArchUnit 只禁止业务包反向 import |
| `bootstrap` | Milvus / OpenSearch / pgvector client | 向量后端实现与业务域同模块，当前模块化单体可接受 | P1 先加后端能力声明 / fail-fast |

## 跨域 import 与注入

机器扫描统计：bootstrap main Java 文件 471 个；跨域 import 126 条；跨域 service/mapper 注入候选 58 条；跨域 mapper 注入 14 条；Controller mapper 注入 3 条。

| 源文件 | 来源域 | 目标符号 | 目标域 | 模式 | 严重级别 | 建议 |
| --- | --- | --- | --- | --- | --- | --- |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/service/impl/DashboardServiceImpl.java:68` | `admin` | `UserMapper` | `user` | 跨域 mapper 注入 | S3 | `admin` 只读仪表盘聚合例外；护栏中显式 allowlist |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/service/impl/DashboardServiceImpl.java:69-71` | `admin` | `ConversationMapper` / `ConversationMessageMapper` / `RagTraceRunMapper` | `rag` | 跨域 mapper 注入 | S3 | 同上，保持只读，不扩展写操作 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IntentTreeServiceImpl.java:61` | `ingestion` | `KnowledgeBaseMapper` | `knowledge` | 非 admin 跨域 mapper 注入 | S1 | 改走 `KbMetadataReader` 或 ingestion 专用 KB 查询 port |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java:70` | `knowledge` | `SysDeptMapper` | `user` | 非 admin 跨域 mapper 注入 | S1 | user/dept 侧补 `DeptReaderPort` 或 application service |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java:118` | `knowledge` | `IngestionPipelineMapper` | `ingestion` | 非 admin 跨域 mapper 注入 | S1 | 改由 `IngestionPipelineService` 暴露只读查询 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java:66` | `rag` | `KnowledgeBaseMapper` | `knowledge` | RAG 热路径跨域 mapper | S1 | 改由 `KbMetadataReader` / retrieval port 提供 collection/kb 元数据 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java:50` | `rag` | `KnowledgeBaseMapper` | `knowledge` | RAG 检索通道跨域 mapper | S1 | 同上，避免检索层直查 knowledge DAO |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceQueryServiceImpl.java:60` | `rag` | `UserMapper` | `user` | 跨域 mapper | S1 | 用户展示信息改由 user read port 或 query DTO service 提供 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/support/TraceEvalAccessSupport.java:41` | `rag` | `UserMapper` | `user` | 跨域 mapper | S1 | 改为 `CurrentUserProbe` + 专用 user metadata reader |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java:65` | `user` | `KnowledgeBaseMapper` | `knowledge` | 反向 mapper 依赖 | S1 | 已有 `KbMetadataReader`，优先迁移 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java:65` | `user` | `KnowledgeBaseMapper` | `knowledge` | 反向 mapper 依赖 | S1 | 已有 `KbMetadataReader`，优先迁移 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/SysDeptServiceImpl.java:51` | `user` | `KnowledgeBaseMapper` | `knowledge` | 反向 mapper 依赖 | S1 | 迁移到 `KbMetadataReader` 或 dept 引用计数 port |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/EvalRunController.java:60` | `eval` | `EvalResultMapper` | `eval` | Controller 直接 mapper | S1 | 移入 `EvalRunService` / query service，Controller 只调 service |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java:44-45` | `knowledge` | `KnowledgeBaseMapper` / `KnowledgeDocumentMapper` | `knowledge` | Controller 直接 mapper | S1 | 提取 `SpacesService`，Controller 只做参数与响应封装 |

可接受但需要记录的跨域模式：

| 模式 | 说明 |
| --- | --- |
| `ingestion -> core` / `knowledge -> core` | `core` 是文档解析、分块基础能力，当前方向合理 |
| `eval -> rag.ChatForEvalService` | eval 离线评测需要复用同步 RAG 编排；`EvalRunExecutor` 已把 `AccessScope.all()` 限定在 SUPER_ADMIN 离线评估语境 |
| `knowledge -> rag.VectorStoreService/VectorStoreAdmin` | 当前向量抽象放在 rag 包下，属于历史包边界问题；P1 不建议先搬包，先补后端能力护栏 |

## Deprecated 授权接口使用

`KbAccessService` 当前仍出现在 16 个文件；排除接口定义和实现类自身后，调用点覆盖 RAG、knowledge、admin、user 多条核心路径。

| 文件 | 方法 / 链路 | 当前 API | 替代 port | 严重级别 | 迁移说明 |
| --- | --- | --- | --- | --- | --- |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java:134` | SSE 问答入口指定 KB 校验 | `checkAccess` | `KbReadAccessPort.checkReadAccess` | S1 | 同类已注入 `KbReadAccessPort`，可作为权限 ports 迁移的最小 PR |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java:262` | 检索热路径批量安全等级 | `getMaxSecurityLevelsForKbs` | `KbReadAccessPort.getMaxSecurityLevelsForKbs` | S1 | 热路径优先迁移，避免继续扩散上帝接口 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java:76-154` | KB 读/写/共享 | `checkManageAccess` / `checkAccess` / `getAccessibleKbIds` / `checkKbRoleBindingAccess` | `KbManageAccessPort` + `KbReadAccessPort` + `CurrentUserProbe` | S1 | Controller 级迁移，调用替换直接 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java:72-178` | 文档上传、分块、level 修改 | `checkManageAccess` / `checkDocManageAccess` / `checkDocSecurityLevelAccess` | `KbManageAccessPort` + `KbReadAccessPort` | S1 | 文档写路径风险高，迁移后可加“新代码禁用 KbAccessService”护栏 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java:97,206` | 创建 KB dept 解析、删除解绑角色 | `resolveCreateKbDeptId` / `unbindAllRolesFromKb` | `KbManageAccessPort`；解绑暂无细粒度 port | S1 | `unbindAllRolesFromKb` 需要新增 `KbRoleBindingAdminPort` 或归入 user application service |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java:54-68` | Spaces 列表聚合 | `isSuperAdmin` / `getAccessibleKbIds` | `CurrentUserProbe` + `KbReadAccessPort.getAccessScope` | S2 | 只读路径，但仍会阻碍 `KbAccessService` 退役 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/DashboardController.java:63` | Dashboard admin 判断 | `isSuperAdmin` / `isDeptAdmin` | `CurrentUserProbe` 或 `UserAdminGuard.checkAnyAdminAccess` | S2 | 管理入口判断，可低风险替换 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/*.java` | 用户 / 角色 / 部门 Controller | 多个 user 管理方法 | `UserAdminGuard` + `CurrentUserProbe` + `SuperAdminInvariantGuard` | S2 | 同域迁移，安全风险较低但能减少 god-service 表面积 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java` | last SUPER_ADMIN、缓存失效、角色分配 | `simulateActiveSuperAdminCountAfter` / `evictCache` / `isSuperAdmin` | `SuperAdminInvariantGuard` + `KbAccessCacheAdmin` + `CurrentUserProbe` | S2 | 同域历史调用；迁移后可收紧 ArchUnit |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java` | 用户创建/修改/删除 | user 管理与 last SUPER_ADMIN 方法 | `UserAdminGuard` + `CurrentUserProbe` + `SuperAdminInvariantGuard` | S2 | 与 controller 可同批或后续迁移 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java:183` | Access Center KB security level 展示 | `getMaxSecurityLevelForKb` | 需要新增单 KB security-level port 或复用 batch port | S2 | 当前 ports 只有 batch 方法；可先用 batch 包一层 |

## 异步与事件边界

| 文件 | 机制 | 为什么重要 | 严重级别 | 建议 |
| --- | --- | --- | --- | --- |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java:176` | `MessageQueueProducer.sendInTransaction` | RocketMQ 半消息“本地事务”语义直接进入 knowledge service；已登记 ARCH-6 | S1 | P3 事件抽象前先加静态扫描告警，禁止新增调用点 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java:597-609` | `TransactionSynchronizationManager.registerSynchronization` | 业务 service 手动关心事务提交后发事件；已登记 ARCH-7 | S1 | 先以 allowlist 记录当前唯一调用，新增调用 fail |
| `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/MessageQueueProducer.java:54` | 通用接口暴露 `sendInTransaction` | framework 抽象被 RocketMQ 专有模型污染 | S1 | P3 以 `DomainEventPublisher` / outbox-like 接口替换 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java:52` | MQ consumer 设置合成 `UserContext` | 当前是从 event 捕获 `operator` 后进入异步，未在线程里读原请求上下文；但只有 username，无 userId | S2 | 保留短期行为；后续把 operator 显式传入 service，减少 ThreadLocal 伪登录 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/GoldDatasetSynthesisServiceImpl.java:93` | `evalExecutor.execute` | 异步任务使用 `principalUserId` 参数，不读 `UserContext` | S3 | 当前模式可作为异步 userId 捕获范例 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/EvalRunServiceImpl.java:139` | `evalExecutor.execute` | runId / principalUserId 已在 DB 快照中固化，执行器不读 `UserContext` | S3 | 当前可接受 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagEvaluationServiceImpl.java:53` | `@Async` | 已登记 EVAL-2：legacy trace 留存，当前处于失效状态 | S2 | 不作为 P1 切入点；评估域稳定后清理 legacy |

## 向量后端不变量

| 后端 | 读过滤支持 | 写 metadata 支持 | Admin 生命周期支持 | 风险 | 建议 |
| --- | --- | --- | --- | --- | --- |
| OpenSearch | 支持。`OpenSearchRetrieverService` 将 `MetadataFilter` 渲染为 `metadata.security_level` range / bool filter | 支持。`OpenSearchVectorStoreService.buildDocument` 显式写入 `kb_id` 与 `security_level` | 支持 ensure / exists / drop | 低；当前生产默认 | 保持为唯一生产后端，补 ArchUnit / startup health 保护 |
| Milvus | 不支持。`MilvusRetrieverService` 接收 request 但未使用 `metadataFilters` | 部分 metadata 有 collection/doc/chunk，但显式 TODO 忽略 `kbId/securityLevel`；retriever 不回填 `kbId/securityLevel/docId/chunkIndex` | ensure / exists 支持；drop 使用接口默认 UOE | S0：配置可选但不满足 security-level 不变量；普通登录会话会被 `AuthzPostProcessor` fail-close，系统态仍可能绕过 | P1 首 PR：`rag.vector.type != opensearch` 启动 fail-fast，直到补齐过滤和 metadata 回填 |
| pgvector | 不支持。SQL 只按 `metadata->>'collection_name'` 过滤 | metadata 写入 collection/doc/chunk，但 TODO 忽略 `kbId/securityLevel`；retriever 不回填 | ensure / exists 支持；drop 使用接口默认 UOE | S0：同 Milvus；且 `updateChunksMetadata` 直接 UOE，security level 修改事件会失败 | 同上；补齐前禁止生产配置 |

额外观察：`MilvusVectorStoreService` 与 `MilvusVectorStoreAdmin` 使用 `matchIfMissing = true`，但 `MilvusRetrieverService` 没有 `matchIfMissing`。如果配置缺失，bean 组合可能不一致；虽然 `application.yaml` 当前声明 OpenSearch，但这应该被启动期校验覆盖。

## RAG 编排拆分候选

`RAGChatServiceImpl` 当前约 350 行，已经拆出 rewrite、intent、retrieval、prompt、stream handler、source card builder 等子能力，但 `streamChat` 仍集中处理权限、会话归属、评测采集、sources gate、prompt 输入组装和 LLM streaming。

| 能力 | 当前位置 | 耦合点 | 建议边界 | 优先级 |
| --- | --- | --- | --- | --- |
| 指定 KB 权限校验 | `RAGChatServiceImpl.streamChat` | 同时注入 `KbAccessService` 与 `KbReadAccessPort` | 先替换为 `KbReadAccessPort.checkReadAccess` | P1 |
| 会话归属校验 | `RAGChatServiceImpl.streamChat` + `ConversationMapper` | 编排层直查 DAO | 提取到 `ConversationService.validateKbOwnership` 或 RAG application guard | P2 |
| sources gate | `RAGChatServiceImpl.streamChat` lines 202-251 | 分数阈值、feature flag、builder、SSE emit 混在主流程 | 提取 `SourceCardsOrchestrator` 返回 cards + emit payload | P2 |
| 评测采集 | `EvaluationCollector` 在 `streamChat` 中 set 多处 | 主流程被 trace/eval side effect 穿插 | 提取 `RagEvalTraceRecorder` 或在 trace aspect 中聚合 | P3 |
| LLM request 构建 | `streamSystemResponse` / `streamLLMResponse` private methods | Prompt、temperature、MCP 判定仍在编排内 | 保持短期现状；等 sources gate 拆后再抽 | P3 |
| Stream handler ThreadLocal | `StreamChatEventHandler` | 已知 SRC-3；handler 内仍读 `UserContext` | 创建时显式传入 `userId`，handler 不读 ThreadLocal | P2 |

P2 最安全第一个拆分点：`sources gate + SourceCardBuilder + emitSources`。它已有清晰输入输出，且可以用现有 sources 相关单测锁住。

## 前端热点

| 区域 | 当前文件 | 风险 | 建议拆分 | 优先级 |
| --- | --- | --- | --- | --- |
| Chat 状态与 streaming 编排 | `frontend/src/stores/chatStore.ts`，约 598 行 | store 同时承担 session CRUD、message reducer、SSE handler、cancel、feedback、toast | 先把 message reducer / stream handler state 转换提到纯函数模块，保持 store API 不变 | P5 |
| SSE frame parser | `frontend/src/hooks/useStreamResponse.ts`，约 184 行 | 解析逻辑集中且可测试；当前结构尚可 | 保持；只补协议 case 测试，不急拆 | P5 |
| Admin 布局 | `frontend/src/pages/admin/AdminLayout.tsx`，约 827 行 | 页面布局、菜单、响应式、面包屑集中，后续菜单增加会继续膨胀 | 后续提取 `adminNavConfig` / `AdminSidebar` / `AdminHeader` | P5 |
| 权限单一真相源 | `frontend/src/utils/permissions.ts` | 当前权限判断集中在 `getPermissions` / `usePermissions`，符合约定 | 维持；新增页面禁止内联 `isSuperAdmin` 判断 | 立即护栏 |
| Access Center scope | `frontend/src/pages/admin/access/hooks/useAccessScope.ts` | `isSuperAdmin/isDeptAdmin` 判定集中，和 permissions 有轻微重叠 | 暂不拆，避免误动 Access Center 深链逻辑 | S3 |

低风险 P5 候选点：把 `chatStore.ts` 中的 `createStreamHandlers` 和 message update 纯函数迁到 `stores/chatStreamHandlers.ts` / `stores/chatMessageReducers.ts`，保留 `useChatStore` 外部 API。

## 护栏 backlog

| 规则 | 执行方式 | 初始状态 | 误报风险 | 建议阶段 |
| --- | --- | --- | --- | --- |
| `framework` 不得 import `bootstrap` / `rag` / `knowledge` / `user` / `eval` 等业务包 | ArchUnit 或 Maven Enforcer + grep | 立即强制 | 低 | P1 |
| `infra-ai` 不得 import `bootstrap` 业务包 | ArchUnit 或 grep | 立即强制 | 低 | P1 |
| `rag.vector.type` 非 `opensearch` 时启动 fail-fast，除非显式 dev override | Spring `@PostConstruct` / conditional validator | 立即强制 | 低 | P1 |
| Controller 不得依赖 `*Mapper` | ArchUnit | 先告警；当前 3 处历史违规 | 中 | P1/P2 |
| 非 `admin` 域不得注入其他域 `*Mapper` | ArchUnit | 先告警 + allowlist 当前违规 | 中 | P2 |
| 新代码不得注入 `bootstrap.user.service.KbAccessService` | ArchUnit / static scan | 先告警；P1 迁掉 RAG + KB 写路径后强制 | 中 | P1 |
| 业务 service 不得直接调用 `MessageQueueProducer.sendInTransaction` | static scan | 先告警；当前 1 处 allowlist | 低 | P3 |
| 业务 service 不得直接使用 `TransactionSynchronizationManager` | static scan | 先告警；当前 1 处 allowlist | 低 | P3 |
| MQ / executor 异步路径不得调用 `UserContext.getUserId()` | static scan + allowlist | 立即告警；合成 username 的 consumer 需要单独说明 | 中 | P2 |
| 前端权限判断不得散落内联 `isSuperAdmin` / `isDeptAdmin` | ESLint custom rule 或 grep CI 警告 | 先告警 | 中 | P5 |

护栏策略：P1 只强制“模块反向依赖”和“向量后端 fail-fast”。对历史违规多的规则先告警并维护 allowlist，避免第一次加入就被存量问题打爆。

## 推荐下一阶段

推荐：**P1 从向量后端 security-level fail-fast 开始**。

理由：

1. 这是唯一 S0：`rag.vector.type` 暴露了 Milvus / pgvector 选择，但这两个后端尚不满足读过滤、metadata 回填、security-level 刷新不变量。
2. 该 PR 不需要移动包、不改变业务 API、不要求大规模重构，符合 P0 后第一个小 PR 的风险控制。
3. 它能把“配置可选但不安全/不可用”的状态变成显式失败，给后续权限 ports 与 RAG 拆分提供稳定边界。

最小第一个 PR：

```text
新增 VectorBackendCapabilityValidator：
- 启动时读取 rag.vector.type。
- type=opensearch 放行。
- type=milvus 或 pg 默认 fail-fast，错误信息指向 docs/dev/followup/backlog.md#SL-1 / P0 报告。
- 如确需本地实验，新增显式 dev-only override，例如 rag.vector.allow-incomplete-backend=true。
- 单测覆盖 opensearch 放行、milvus/pg 拒绝、dev override 放行。
```

验证：

```bash
mvn -pl bootstrap test -Dtest=VectorBackendCapabilityValidatorTest
mvn -pl bootstrap test -Dtest=OpenSearchRetrieverServiceTest
mvn -pl bootstrap test -Dtest=AuthzPostProcessorTest
mvn -pl bootstrap install -DskipTests
```

## Appendix：执行过的命令

PowerShell 宿主在本环境启动时报 `System.Management.Automation.Runspaces.InitialSessionState` 初始化失败，因此本轮使用 Node REPL 的 `fs` / `child_process` 执行等价读取与扫描。执行过的关键检查：

```bash
git status --short --branch
git checkout -b codex/architecture-p0-audit
rg "<artifactId>|<module>" pom.xml */pom.xml
rg "^import com\\.nageoffer\\.ai\\.ragent\\.(rag|knowledge|ingestion|user|admin|core|eval)\\." bootstrap/src/main/java
rg "@Autowired|private final .*Service|private final .*Mapper" bootstrap/src/main/java
rg "KbAccessService" bootstrap/src/main/java framework/src/main/java
rg "CurrentUserProbe|KbReadAccessPort|KbManageAccessPort|SecurityLevel" bootstrap/src/main/java framework/src/main/java
rg "RocketMQ|sendInTransaction|TransactionSynchronizationManager|@Async|@TransactionalEventListener|UserContext" bootstrap/src/main/java framework/src/main/java
rg "metadataFilters|security_level|VectorStoreService|RetrieverService|VectorStoreAdmin" bootstrap/src/main/java
rg "class RAGChatServiceImpl|streamChat\\(|buildStructuredMessages|retrieve\\(|appendAssistant|RagTraceContext|CitationStats" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag
rg "create\\(|zustand|use.*Store|fetch\\(|ReadableStream|EventSource|permissions|canSee|isSuperAdmin|isDeptAdmin" frontend/src
```

读取的基线文件：

```text
CLAUDE.md
bootstrap/CLAUDE.md
framework/CLAUDE.md
infra-ai/CLAUDE.md
frontend/CLAUDE.md
docs/dev/arch/overview.md
docs/dev/arch/bootstrap.md
docs/dev/arch/frontend.md
docs/dev/arch/code-map.md
docs/dev/arch/business-flows.md
docs/dev/followup/architecture-backlog.md
docs/dev/followup/backlog.md
```
