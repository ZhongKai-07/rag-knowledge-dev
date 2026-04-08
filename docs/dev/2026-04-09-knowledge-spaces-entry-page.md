# Knowledge Spaces 入口页开发日志

**日期：** 2026-04-09
**范围：** 新增 `/spaces` 知识库入口页，实现空间级别的会话隔离

---

## 开发思路

### 背景

原有设计：用户登录后直接进入 `/chat`，知识库选择仅是 ChatInput 中的一个下拉框，会话(session)不与特定知识库关联。

新设计：新增 `/spaces` 入口页，展示用户可访问的知识库卡片。点击卡片进入该 KB 范围内的聊天，实现"先选空间，再聊天"的强隔离模型。

### 核心约束

1. **后端 `conversation.kb_id` 是真相源** —— 会话归属哪个空间由数据库决定
2. **前端 URL `?kbId=xxx` 是唯一锁定源** —— store 只做派生缓存
3. **进入新空间必须清空旧状态** —— 不允许跨空间串会话

---

## 对话链路与知识库、用户权限的绑定机制

整个绑定分三层：**写入时绑定、读取时过滤、操作时校验**。

### 1. 写入时绑定：会话创建时写入 kb_id

```
用户在 test000 空间发送第一条消息
  -> RAGChatServiceImpl.streamChat(knowledgeBaseId="test000的ID")
  -> ConversationMemoryService.loadAndAppend(conversationId, userId, message, kbId)
  -> JdbcConversationMemoryStore.append()
     构建 ConversationCreateRequest 时 .kbId(kbId)
  -> ConversationServiceImpl.createOrUpdate()
     仅新建时设置，更新时不覆盖
  -> INSERT INTO t_conversation (..., kb_id) VALUES (..., 'test000的ID')
```

从这一刻起，这条会话永久属于 test000 空间。

### 2. 读取时过滤：RBAC + kb_id 双重过滤

**会话列表过滤：**
```
用户进入 test000 空间 -> 前端请求 GET /conversations?kbId=xxx
  -> ConversationServiceImpl.listByUserId(userId, kbId)
  -> SQL: WHERE user_id = ? AND kb_id = ?
  -> 只返回当前用户在当前空间的会话
```

**RBAC 层面（决定用户能进哪些空间）：**
```
用户登录 -> GET /knowledge-base
  -> KnowledgeBaseController -> kbAccessService.getAccessibleKbIds(userId)
  -> 查询链: t_user_role -> t_role_kb_relation -> 可访问的 kbId 集合（Redis 缓存 30min）
  -> 用 accessibleKbIds 过滤 KB 列表
  -> SpacesPage 只展示用户有权访问的知识库卡片
```

### 3. 操作时校验：fail-closed 强一致性

**聊天时校验（防止篡改 conversationId）：**
```
RAGChatServiceImpl.streamChat(conversationId, knowledgeBaseId)
  -> 加载历史前先查 ConversationDO
  -> Objects.equals(existing.getKbId(), knowledgeBaseId) ?
  -> 不匹配 -> 400 "会话不属于当前知识库"
  -> 匹配 -> 正常加载历史 + 检索 + 生成
```

**读消息/改名/删除时校验（所有端点 kbId 必填）：**
```
GET    /conversations/{id}/messages?kbId=xxx -> validateKbOwnership -> 通过才返回
PUT    /conversations/{id}?kbId=xxx          -> validateKbOwnership -> 通过才改名
DELETE /conversations/{id}?kbId=xxx          -> validateKbOwnership -> 通过才删除
```

**旧会话处理（kb_id=NULL，迁移前的数据）：**
```
validateKbOwnership 使用 Objects.equals() 而非 .equals()
  -> NULL != 任何 kbId -> 校验失败（fail-closed）
  -> 旧会话不属于任何空间，不会被意外访问
```

### 三层绑定关系图

```
+--------------------------------------------------+
|                  用户权限 (RBAC)                   |
|  t_user_role -> t_role_kb_relation -> kbId 集合    |
|  决定用户能看到哪些知识库（SpacesPage 卡片）        |
+-------------------------+------------------------+
                          | 用户点击卡片
                          v
+--------------------------------------------------+
|              知识库锁定 (URL kbId)                  |
|  /chat?kbId=xxx -> store.activeKbId                |
|  决定当前空间，所有 API 请求携带 kbId               |
+-------------------------+------------------------+
                          | 发送消息 / 加载会话
                          v
+--------------------------------------------------+
|           会话归属 (t_conversation.kb_id)           |
|  写入时绑定 -> 读取时过滤 -> 操作时校验             |
|  会话永久归属一个空间，跨空间操作被后端拦截          |
+--------------------------------------------------+
```

---

## 实现阶段与修改清单

### Phase 1: 后端 — 会话关联知识库 + 强隔离

| Step | 内容 | 关键文件 |
|------|------|----------|
| 1.1 | DB 迁移：t_conversation 新增 kb_id 列+索引 | upgrade_v1.2_to_v1.3.sql, schema_pg.sql, full_schema_pg.sql |
| 1.2-1.4 | 实体/VO/Request 新增 kbId 字段 | ConversationDO, ConversationCreateRequest, ConversationVO |
| 1.5 | 全链路透传 kbId（6个接口+2个额外调用方） | ConversationMemoryStore, ConversationMemoryService, DefaultConversationMemoryService, JdbcConversationMemoryStore, ConversationServiceImpl, RAGChatServiceImpl |
| 1.6 | 会话列表按 kbId 过滤 | ConversationController, ConversationServiceImpl |
| 1.7 | 强一致性校验：validateKbOwnership + 所有端点 kbId 必填 | ConversationController, ConversationServiceImpl, RAGChatServiceImpl |
| 1.8 | Spaces 统计端点 | SpacesController（新建）, SpacesStatsVO（新建） |

### Phase 2: 前端 — Spaces 入口页

| Step | 内容 | 关键文件 |
|------|------|----------|
| 2.1 | SpacesPage 组件（独立布局、统计行、KB 卡片网格） | SpacesPage.tsx（新建） |
| 2.2 | spacesService | spacesService.ts（新建） |
| 2.3 | 路由调整：新增 /spaces，所有重定向改为 /spaces | router.tsx |
| 2.4 | 登录后重定向到 /spaces | LoginPage.tsx |

### Phase 3: 前端 — Chat 页面适配

| Step | 内容 | 关键文件 |
|------|------|----------|
| 3.1 | chatStore 新增 activeKbId/resetForNewSpace | chatStore.ts |
| 3.2 | ChatPage 副作用重写（空间切换清状态、navigate 保持 kbId） | ChatPage.tsx |
| 3.3 | ChatInput 锁定 KB 下拉框 | ChatInput.tsx |
| 3.4 | WelcomeScreen 显示当前 KB 名称 | WelcomeScreen.tsx |
| 3.5 | sessionService 所有方法传入 kbId | sessionService.ts |
| 3.6 | Sidebar 全路径保持 kbId + 返回空间列表按钮 | Sidebar.tsx |
| 3.7 | Header 面包屑导航 | Header.tsx |
| 3.8 | 类型补齐 Session.kbId | types/index.ts |

---

## 开发过程中的关键教训

### 1. 竞态条件：Sidebar 在 activeKbId 设置前 fetch 全量会话

**现象：** 进入 KB 空间后，侧边栏仍显示所有旧会话。
**根因：** Sidebar 的 `useEffect` 在 `activeKbId` 尚为 null 时触发 `fetchSessions(undefined)`，拉取全量会话。ChatPage 随后设置 `activeKbId` 并重新 fetch，但 Sidebar 的首次 fetch 结果可能覆盖。
**修复：** Sidebar 的 fetch 条件增加 `kbId` 真值检查：`if (sessions.length === 0 && kbId)`。

### 2. 遗漏调用方：listMessages 没传 kbId 导致 400 错误

**现象：** 切换会话时后端报 `Required request parameter 'kbId' is not present`。
**根因：** 后端将 `GET /conversations/{id}/messages` 的 kbId 设为必填，但前端 `sessionService.listMessages()` 和 `chatStore.selectSession()` 未同步更新。
**教训：** 后端改接口签名时，必须全量搜索前端所有调用点（不只是 Task 清单里列出的文件）。

### 3. admin 统计特判

**问题：** `kbAccessService.getAccessibleKbIds()` 走 `user->roles->kb_relations` 链路，admin 用户可能没有显式的角色-KB 映射，导致返回空集。
**解决：** SpacesController 中 admin 分支直接查全量 COUNT，不经过 `getAccessibleKbIds()`。同时普通用户空集时短路返回 `{0, 0}`，避免 SQL `IN ()` 语法错误。

### 4. NULL 安全与 fail-closed

**问题：** 迁移前的旧会话 `kb_id=NULL`，直接 `.equals()` 会 NPE。
**解决：** `validateKbOwnership` 使用 `Objects.equals()` 做 null-safe 比较。旧会话 `NULL != 任何kbId`，校验必定失败（fail-closed），不属于任何空间。

### 5. 后端需重启才能生效

**现象：** 前端改动通过 Vite HMR 即时生效，但后端 Spring Boot 代码变更需要重启。验证时 `/spaces/stats` 返回 404、`/conversations?kbId=xxx` 返回未过滤数据。
**教训：** 前后端联调时，后端代码变更后必须确认 Spring Boot 已重启。
