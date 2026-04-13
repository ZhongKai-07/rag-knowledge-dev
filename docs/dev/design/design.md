 Knowledge Spaces 入口页实现计划

 Context

 当前用户登录后直接进入 /chat，知识库选择仅是 ChatInput 中的下拉框，且会话(session)不与特定知识库关联。用户希望新增一个
  /spaces 入口页，展示用户可访问的知识库卡片，点击卡片进入该 KB 范围内的聊天，实现"空间"级别的知识隔离。

 设计决策：
 - 路由：新增 /spaces，登录后默认跳转此页
 - KB 锁定：进入聊天后锁定当前 KB，切换需返回 /spaces
 - 侧边栏：不变，仍显示会话历史（按 KB 过滤）
 - 统计行：简化版，只显示 KB 数量 + 文档总数
 - 卡片内容：简化版，显示名称 + 文档数 + 创建时间 + 图标（不新增 DB 字段）

 ---
 Phase 1: 后端 — 会话关联知识库

 Step 1.1: 数据库迁移

 - 创建 resources/database/upgrade_v1.2_to_v1.3.sql
 - t_conversation 表新增 kb_id VARCHAR(20) DEFAULT NULL 列
 - 新增索引 idx_conversation_kb_user(user_id, kb_id, last_time)
 - 同步更新 resources/database/full_schema_pg.sql 中的建表语句

 Step 1.2: ConversationDO 实体

 - 文件： bootstrap/.../rag/dao/entity/ConversationDO.java
 - 新增字段 private String kbId;

 Step 1.3: ConversationCreateRequest

 - 文件： bootstrap/.../rag/controller/request/ConversationCreateRequest.java
 - 新增字段 private String kbId;

 Step 1.4: ConversationVO

 - 文件： bootstrap/.../rag/controller/vo/ConversationVO.java
 - 新增字段 private String kbId;
 - 更新 ConversationServiceImpl.listByUserId() 中的 VO 映射

 Step 1.5: 会话创建时写入 kbId

 调用链需要全链路透传 knowledgeBaseId：

 RAGChatServiceImpl.streamChat(knowledgeBaseId)
   → DefaultConversationMemoryService.loadAndAppend(conversationId, userId, message, kbId)  [新增参数]
     → JdbcConversationMemoryStore.append(conversationId, userId, message, kbId)  [新增参数]
       → ConversationServiceImpl.createOrUpdate(request)  [request 含 kbId]

 修改文件（按调用链顺序）：
 1. ConversationMemoryStore 接口 rag/core/memory/ConversationMemoryStore.java — append() 新增 String kbId 参数
 2. ConversationMemoryService 接口 rag/core/memory/ConversationMemoryService.java — append() 和 loadAndAppend() 新增
 String kbId 参数
 3. DefaultConversationMemoryService rag/core/memory/DefaultConversationMemoryService.java — 透传 kbId
 4. JdbcConversationMemoryStore rag/core/memory/JdbcConversationMemoryStore.java — append() 中构建
 ConversationCreateRequest 时设置 .kbId(kbId)
 5. ConversationServiceImpl rag/service/impl/ConversationServiceImpl.java — createOrUpdate() 中设置
 conversationDO.setKbId(request.getKbId())（仅新建时设置，更新时不覆盖）
 6. RAGChatServiceImpl rag/service/impl/RAGChatServiceImpl.java — 调用 memoryService.loadAndAppend() 时传入
 knowledgeBaseId

 Step 1.6: 会话列表按 kbId 过滤

 - ConversationController rag/controller/ConversationController.java — GET /conversations 新增 @RequestParam(required =
  false) String kbId
 - ConversationService 接口 — listByUserId 新增 String kbId 参数
 - ConversationServiceImpl — 查询条件新增 .eq(kbId != null, ConversationDO::getKbId, kbId)

 Step 1.7: Spaces 统计端点

 - 新建 SpacesController knowledge/controller/SpacesController.java
 - GET /spaces/stats — 返回 { kbCount, totalDocumentCount }
 - 复用 KnowledgeBaseService.pageQuery() 获取当前用户可访问 KB 列表，聚合 documentCount

 ---
 Phase 2: 前端 — Spaces 页面

 Step 2.1: 创建 SpacesPage 组件

 - 新建： frontend/src/pages/SpacesPage.tsx
 - 独立布局（不使用 MainLayout/Sidebar，因为没有聊天上下文）
 - 顶部：简化头部栏（logo + 用户头像/登出）
 - 统计行：KB 数量 + 文档总数（调用 GET /spaces/stats）
 - 卡片网格：调用 getKnowledgeBases() 获取数据
 - 每张卡片：名称 + 图标（首字取色）+ 文档数 + 创建时间
 - 点击卡片：navigate(/chat?kbId=${kb.id})
 - 管理员可见"管理后台"入口按钮

 Step 2.2: 创建 spacesService

 - 新建： frontend/src/services/spacesService.ts
 - getSpacesStats() — 调用 GET /spaces/stats

 Step 2.3: 路由调整

 - 文件： frontend/src/router.tsx
 - 新增 /spaces 路由（RequireAuth 包裹）
 - HomeRedirect: /chat → /spaces
 - RedirectIfAuth: /chat → /spaces
 - RequireAdmin 非 admin 重定向: /chat → /spaces

 Step 2.4: LoginPage 重定向

 - 文件： frontend/src/pages/LoginPage.tsx (line 30)
 - navigate("/chat") → navigate("/spaces")

 ---
 Phase 3: 前端 — Chat 页面适配

 Step 3.1: ChatPage 读取 kbId

 - 文件： frontend/src/pages/ChatPage.tsx
 - 使用 useSearchParams 读取 kbId
 - 无 kbId 时重定向到 /spaces
 - 挂载时调用 setSelectedKnowledgeBase(kbId) 锁定 KB
 - fetchSessions 传入 kbId 过滤会话
 - URL 导航保持 kbId：/chat/${sessionId}?kbId=${kbId}

 Step 3.2: ChatInput 锁定 KB

 - 文件： frontend/src/components/chat/ChatInput.tsx
 - 当 kbId 从 URL 传入时，隐藏知识库下拉框（已锁定）
 - 显示当前 KB 名称标签（替代下拉框）

 Step 3.3: chatStore 适配

 - 文件： frontend/src/stores/chatStore.ts
 - fetchSessions(kbId?) — 调用 listSessions(kbId) 按 KB 过滤
 - 确保 selectedKnowledgeBaseId 在 KB 锁定模式下不被清空

 Step 3.4: sessionService 适配

 - 文件： frontend/src/services/sessionService.ts
 - listSessions(kbId?) — 传递 ?kbId=xxx 查询参数
 - ConversationVO 新增 kbId?: string 字段

 Step 3.5: Sidebar 导航保持 kbId

 - 文件： frontend/src/components/layout/Sidebar.tsx
 - 点击会话时保持 ?kbId=xxx 参数
 - 新建对话时保持 ?kbId=xxx 参数
 - 新增"返回空间列表"按钮（放在侧边栏顶部 logo 区域下方）

 Step 3.6: Header 显示当前 KB

 - 文件： frontend/src/components/layout/Header.tsx
 - 显示当前 KB 名称（从 chatStore 或通过 API 获取）
 - 可选：添加返回 /spaces 的导航按钮

 ---
 Phase 4: 边界情况

 - 直接访问 /chat 无 kbId：重定向到 /spaces
 - 历史会话无 kb_id：数据库迁移后旧会话 kb_id=NULL，在某个 KB 下不会显示这些旧会话（可接受）
 - 用户仅有一个 KB 权限：仍显示 spaces 页面（保持一致性）
 - KB 删除后用户访问：kbId 无效时，API 返回错误，前端重定向到 /spaces

 ---
 关键修改文件清单

 ┌─────────────────────────────────────────────────────────────────────┬──────┐
 │                                文件                                 │ 操作 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ resources/database/upgrade_v1.2_to_v1.3.sql                         │ 新建 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ resources/database/full_schema_pg.sql                               │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/dao/entity/ConversationDO.java                    │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/controller/request/ConversationCreateRequest.java │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/controller/vo/ConversationVO.java                 │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/core/memory/ConversationMemoryStore.java          │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/core/memory/ConversationMemoryService.java        │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/core/memory/DefaultConversationMemoryService.java │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/core/memory/JdbcConversationMemoryStore.java      │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/service/impl/ConversationServiceImpl.java         │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/service/impl/RAGChatServiceImpl.java              │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../rag/controller/ConversationController.java            │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ bootstrap/.../knowledge/controller/SpacesController.java            │ 新建 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/pages/SpacesPage.tsx                                   │ 新建 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/services/spacesService.ts                              │ 新建 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/router.tsx                                             │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/pages/LoginPage.tsx                                    │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/pages/ChatPage.tsx                                     │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/components/chat/ChatInput.tsx                          │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/stores/chatStore.ts                                    │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/services/sessionService.ts                             │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/components/layout/Sidebar.tsx                          │ 修改 │
 ├─────────────────────────────────────────────────────────────────────┼──────┤
 │ frontend/src/components/layout/Header.tsx                           │ 修改 │
 └─────────────────────────────────────────────────────────────────────┴──────┘

 ---
 验证方案

 1. 后端验证： 执行 SQL 迁移 → mvn clean install -DskipTests 构建通过 → 启动应用
 2. Spaces 页面： 登录 admin/admin → 自动跳转 /spaces → 看到 KB 卡片和统计数据
 3. 进入聊天： 点击 KB 卡片 → 跳转 /chat?kbId=xxx → KB 下拉框被锁定/隐藏
 4. 会话隔离： 在 KB-A 创建会话 → 返回 /spaces → 进入 KB-B → 侧边栏不显示 KB-A 的会话
 5. 返回导航： 在聊天页面点击"返回空间列表" → 回到 /spaces
 6. Chrome DevTools 截图验证： 使用 take_screenshot 对各页面进行视觉验证