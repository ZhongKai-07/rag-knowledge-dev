# Core Business Flows

系统主要业务链路的端到端调用流程。改动任何一条链路前先读对应段，避免漏掉 AOP / 缓存 / RBAC 检查点。

## 1. RAG 问答链路

```
GET /rag/v3/chat → RAGChatController → RAGChatServiceImpl.streamChat()

AOP: 限流入队 + traceId 生成 + 链路追踪开始
 → RBAC 权限校验（KbAccessService.getAccessibleKbIds）
 → 加载会话记忆 + 追加当前问题（ConversationMemoryService.loadAndAppend）
 → 查询改写 + 子问题拆分（QueryRewriteService.rewriteWithSplit）
 → 意图识别（IntentResolver.resolve → DefaultIntentClassifier，LLM 调用，并行）
 → 歧义引导检测（IntentGuidanceService.detectAmbiguity）→ 触发则短路返回引导提示
 → System-Only 检测 → 全是 SYSTEM 意图则跳过检索直接调 LLM
 → 多通道检索（RetrievalEngine.retrieve，并行）
   ├── KB 意图 → MultiChannelRetrievalEngine → 向量检索（过滤可访问 KB）→ 去重 → 重排
   └── MCP 意图 → MCPToolExecutor → 参数提取（LLM）→ 工具执行
 → 检索结果为空则短路返回"未检索到"
 → 上下文组装（RAGPromptService.buildStructuredMessages）
 → LLM 流式生成（SSE 推送，temperature 根据 MCP 动态调整）
 → onComplete: 回答写入记忆 + token 用量记录 + 评估数据采集
AOP: 链路追踪结束 + 限流出队
```

## 2. 知识库管理链路

```
POST /knowledge-base           → 创建知识库（DB 记录 + VectorStoreAdmin.ensureVectorSpace 创建向量索引）
GET  /knowledge-base           → 列表查询（RBAC 过滤可访问的 KB）
PUT  /knowledge-base/{id}      → 更新知识库元信息
DELETE /knowledge-base/{id}    → 删除知识库 + 关联文档 + 向量数据
```

## 3. 文档入库链路

```
POST /knowledge-base/{kbId}/docs/upload → KnowledgeDocumentController.upload()
 → FileStorageService 存文件到 S3
 → 创建 KnowledgeDocumentDO（status=PENDING）
 → 返回 200

POST /knowledge-base/docs/{docId}/chunk → KnowledgeDocumentController.startChunk()
 → RBAC 权限校验
 → RocketMQ 事务消息发送（事务回调中更新 status=RUNNING）
 → 返回 200（异步处理）

KnowledgeDocumentChunkConsumer 异步消费：
 → KnowledgeDocumentServiceImpl.executeChunk()
   ├── CHUNK 模式: Extract(Tika) → Chunk(策略分块) → Embed(向量化) → Persist
   └── PIPELINE 模式: IngestionEngine.execute()（Fetcher→Parser→[Enhancer]→Chunker→[Enricher]→Indexer）
 → 原子事务: 删旧分块/向量 → 写新分块/向量 → 更新 status=SUCCESS
 → 记录 ChunkLog（各阶段耗时）
```

## 4. 后台管理链路

```
GET /admin/dashboard/overview     → 概览 KPI（文档总数、知识库数、对话数等）
GET /admin/dashboard/performance  → 性能指标（处理成功率、平均耗时）
GET /admin/dashboard/trends       → 趋势分析（时间窗口内的对话/文档趋势）

DashboardServiceImpl 跨域只读查询 rag + knowledge + user 的 Mapper 做聚合统计。
管理后台所有页面在 frontend/src/pages/admin/ 下，路由注册在 router.tsx。
```

## 5. RBAC 用户权限管理链路

```
认证:
  POST /auth/login  → Sa-Token 签发 Token
  POST /auth/logout → 注销

用户管理:
  GET/POST/PUT/DELETE /users → UserController

角色管理（SUPER_ADMIN / DEPT_ADMIN own-dept，见 access-center-followup-p3）:
  POST/PUT/DELETE /role           → 角色 CRUD（DEPT_ADMIN 限 own-dept）
  GET  /role/{id}/delete-preview  → 删除影响面预览（affected users + KBs）
  PUT  /role/{roleId}/knowledge-bases → 设置角色可访问的知识库列表（含 maxSecurityLevel）
  PUT  /user/{userId}/roles       → 给用户分配角色（D11 hard reject 跨部门）

KB 共享管理（AnyAdmin，按 KB dept 归属）:
  GET /knowledge-base/{kb-id}/role-bindings  → 查看 KB 的角色绑定
  PUT /knowledge-base/{kb-id}/role-bindings  → 全量覆盖 KB 的角色绑定

权限中心新接口（2026-04-19 access-center-redesign P1.3）:
  GET /access/roles                        → 列出角色（含 deptName/userCount/kbCount）
  GET /access/users/{userId}/kb-grants     → 用户的 KB 可访问列表（含 implicit/explicit 区分）
  GET /access/roles/{roleId}/usage         → 角色的 users + kbs 使用列表
  GET /access/departments/tree             → 部门树 + 每节点 user/role/kb 计数

权限校验（贯穿所有业务链路）:
  新代码（2026-04-18 后）：注入 framework/security/port/ 下的细粒度端口
    CurrentUserProbe.isSuperAdmin() / isDeptAdmin()
    KbReadAccessPort.getAccessibleKbIds(userId) / getMaxSecurityLevelForKb(userId, kbId)
    KbManageAccessPort.checkManageAccess(kbId)
  旧代码：KbAccessService 上帝接口（@Deprecated），47 个调用点分批迁移中
  底层逻辑：查 t_user_role → 查 t_role_kb_relation → 返回可访问 kbId 集合 / 安全等级
             SUPER_ADMIN 跳过校验；DEPT_ADMIN 同部门 KB 跳过；Redis 缓存

生效点:
  - RAG 检索时: RetrievalEngine 过滤 accessibleKbIds
  - 知识库列表: KnowledgeBaseController 过滤可见 KB
  - 文档操作: KnowledgeDocumentController 校验文档所属 KB 的访问权限
```
