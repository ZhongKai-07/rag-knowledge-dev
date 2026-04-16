# Frontend 层架构

> React 18 + TypeScript + Vite 单页应用，跑在 5173（dev）代理到后端 9090。
>
> 架构图：[`diagram/architecture/arch_frontend.drawio`](../../../diagram/architecture/arch_frontend.drawio)

## 1. 分层与目录

```
frontend/src/
├── main.tsx          ← 入口：挂载 React + Router
├── App.tsx           ← 根组件 + 全局 Provider
├── router.tsx        ← 路由表（含 <RequireAuth> 包装）
├── router/guards.tsx ← 路由守卫：RequireAuth / RequireAnyAdmin / RequireSuperAdmin / RequireMenuAccess
├── pages/            ← 页面组件（与路由一一对应）
├── components/       ← 可复用组件（ui/chat/layout/session/admin/common）
├── stores/           ← Zustand 全局状态（chatStore · authStore）
├── services/         ← Axios HTTP 服务（按后端业务域拆分）
├── utils/            ← 纯函数工具（permissions, helpers, constants）
├── hooks/            ← 自定义 Hooks
├── types/            ← TypeScript 类型定义
└── styles/           ← Tailwind + 全局 CSS
```

## 2. 路由与守卫

路由表在 `router.tsx`，顶层结构：

```
/login                         → LoginPage
/spaces                        → <RequireAuth> SpacesPage（登录默认页）
/chat                          → <RequireAuth> ChatPage (需要 ?kbId=xxx)
/admin                         → <RequireAnyAdmin> AdminLayout
  /admin/dashboard             → <RequireMenuAccess("dashboard")>
  /admin/knowledge             → <RequireMenuAccess("knowledge")>
  /admin/knowledge/:kbId/docs/:docId  → KnowledgeChunksPage（实为"文档详情"，命名遗留）
  /admin/roles                 → <RequireSuperAdmin>
  /admin/users                 → <RequireMenuAccess("users")>
  /admin/settings              → <RequireSuperAdmin>
  /admin/ingestion · traces · evaluations · intent-tree ...
```

守卫失败策略：**Navigate + toast**，不做 403 页；因为后端也不返回 403（见下）。

## 3. 权限层（单一真相源）

**`utils/permissions.ts`** 是所有权限判断的唯一入口，不允许组件里内联 `user.isSuperAdmin` 判断：

```ts
const perms = getPermissions(user);              // 纯函数，非组件/单测可用
const perms = usePermissions();                  // Hook，组件内使用
perms.canSeeMenuItem("roles")                    // 菜单项可见
perms.canManageKb(kb)                            // KB 级写权限
perms.canManageUser(user)                        // 用户级写权限
```

权限规则：
- `SUPER_ADMIN` 全量可见。
- `DEPT_ADMIN` 见 `DEPT_VISIBLE` 白名单菜单（Dashboard / 知识库 / 用户管理 / 角色管理[只读]）+ 同部门 KB 写权限。
- 普通 `USER` 不进 `/admin`。

**前端是乐观渲染**：`isAnyAdmin` 可能覆盖 DEPT_ADMIN 访问不到的子页（如角色管理写操作）。这类组件必须处理后端拒绝 —— 例如 `KbSharingTab` 捕获加载错误后 `setNoAccess(true)` 并 `return null`，不能仅靠前端判断。

## 4. 状态管理（Zustand）

| Store | 文件 | 关键字段 | 关键方法 |
| --- | --- | --- | --- |
| chatStore | `stores/chatStore.ts` (~550 行) | `sessions · currentSessionId · messages · activeKbId · isStreaming · streamTaskId · deepThinkingEnabled` | `fetchSessions(kbId?)` · `sendMessage(content)` · `cancelGeneration()` · `resetForNewSpace()` |
| authStore | `stores/authStore.ts` | `user · token` | `login · logout · checkAuth` |

**关键约束**：
- **`activeKbId` 是派生缓存**，KB 锁定的唯一真相源是 URL `?kbId=xxx`。首次导航判断必须从 URL 读，不能从 store 读。
- **进入新 KB 空间必须调 `resetForNewSpace()`**，否则旧 KB 的 sessions/messages 残留。
- **`sendMessage` 走 SSE**：通过 `fetch` 流式消费 `GET /rag/v3/chat`，逐帧 parse 后 `appendMessage`。`streamTaskId` 用于 `POST /rag/v3/stop` 取消。

## 5. Services（Axios HTTP 层）

**统一入口 `services/api.ts`**：
- 单例 Axios 实例，`baseURL = /api/ragent`（Vite dev 代理到 9090）。
- 请求拦截：`Authorization: <token>`（Sa-Token 原 token，**无 `Bearer ` 前缀**，见 `api.ts:15` 与 `application.yaml` `sa-token.token-name`）。
- 响应拦截：自动解包 `{code, data, message}`，`code !== "0"` 抛错（由调用方 try/catch 处理，通常渲染为 sonner toast）。

**按后端业务域拆分的 service 文件**：

| 文件 | 对应后端 |
| --- | --- |
| `authService.ts` | `/auth/*` |
| `sessionService.ts` | `/rag/conversation/*`（注意：所有方法都必须传 `kbId`，后端做所有权校验） |
| `knowledgeService.ts` | `/knowledge-base/*` + `/knowledge-base/docs/*` |
| `spacesService.ts` | `/spaces/*` |
| `dashboardService.ts` | `/admin/dashboard/*` |
| `ragTraceService.ts` · `ragEvaluationService.ts` · `intentTreeService.ts` | `/rag/*` 下对应子模块 |
| `roleService.ts` · `userService.ts` · `sysDeptService.ts` | `/role/*` · `/users/*` · `/sys-dept/*`（部分仅 SUPER_ADMIN） |
| `ingestionService.ts` | `/ingestion/pipelines/*` + `/ingestion/tasks/*` |

## 6. UI 约定（组件层）

- **原子 UI**：一律从 `components/ui/` 引入（shadcn/ui 模式，基于 Radix UI）；图标 `lucide-react`。
- **表格**：`@tanstack/react-table` + `components/ui/table.tsx`。
- **表单**：`react-hook-form` + `zod` schema 校验。
- **安全等级徽章**：`components/common/SecurityLevelBadge`（`level: number`, `showLevel?: boolean`）—— 不要在页面里重写。
- **日期格式**：列表页 `formatDateTime(value)` 输出 `yyyy/M/d HH:mm:ss`；简短格式 `formatTimestamp` 输出 `MM月dd日 HH:mm`。均在 `utils/helpers.ts`。
- **Markdown 渲染**：`react-markdown + remark-gfm`；代码块高亮在 `components/chat/MarkdownRenderer.tsx`。
- **虚拟滚动**：`react-virtuoso`（会话消息列表）。

## 7. SSE 聊天细节

```
ChatInput.submit
    → chatStore.sendMessage(content)
        → 追加 user message 到本地 messages
        → fetch('/rag/v3/chat?...', { headers: { Authorization: token } })
        → ReadableStream reader.read() loop
            → parse SSE frames:
                 data: { type: "content", content: "..." }
                 data: { type: "thinking", content: "..." }
                 data: { type: "usage", tokens: ... }
                 data: [DONE]   ← 仅此退出
            → 按 type 更新最后一条 assistant message 的字段
        → finally: isStreaming=false
```

**关键坑位**：
- 不要用原生 `EventSource` —— 它不支持自定义 header（无法带 Sa-Token）。用 `fetch` + 流式解析。
- 循环退出看 `[DONE]` 不是 `finish_reason` 帧（后端还会紧跟一个 usage 帧）。
- 取消：`POST /rag/v3/stop { taskId }`，后端中断上游 LLM 调用。

## 8. 构建与部署

```bash
cd frontend
npm install
npm run dev       # 本地开发 (5173，代理 /api/ragent → 9090)
npm run build     # 生产构建 (输出 dist/)
npm run lint      # eslint
npm run format    # prettier
```

**品牌名**："HT KnowledgeBase"（见 `index.html`, `.env VITE_APP_NAME`, `Sidebar.tsx`, `AdminLayout.tsx`）。

## 9. 评审关注点

- **URL 驱动 vs 状态驱动**：KB 锁定、深度思考开关等核心状态都走 URL/URL 派生，刷新页面不丢状态，方便分享链接。
- **前端不承担权限边界**：永远不信前端，后端 RBAC 是唯一边界。前端失败要优雅降级（null / toast），不要 crash。
- **单一真相源**：权限 → `permissions.ts`；KB 锁 → URL；会话状态 → `chatStore`。组件不应绕过这些源做独立判断。
- **已知命名债**：`KnowledgeChunksPage.tsx` 实际是文档详情页（`knowledge/:kbId/docs/:docId`），改动前确认上下文；`components/chat/Sidebar.tsx` 不存在，Sidebar 在 `components/layout/Sidebar.tsx`。
