# frontend 模块

React 18 + TypeScript + Vite 单页应用。开发服务器热更新，后端需要手动重启。

## 开发命令

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器（代理到后端 9090）
npm run dev

# 生产构建
npm run build

# 代码检查
npm run lint

# 格式化
npm run format
```

## 目录结构

```
src/
├── main.tsx              ← 应用入口，挂载 React + Router
├── App.tsx               ← 路由定义（React Router v6）
├── pages/                ← 页面组件
│   ├── LoginPage.tsx
│   ├── SpacesPage.tsx    ← 知识库空间入口（登录后默认跳转）
│   ├── ChatPage.tsx      ← 主聊天页
│   └── admin/            ← 管理后台（AdminLayout 嵌套路由）
│       ├── AdminLayout.tsx        ← 管理后台布局 + 侧边栏
│       ├── dashboard/
│       ├── knowledge/
│       ├── ingestion/
│       ├── intent-tree/
│       ├── traces/
│       ├── evaluations/
│       ├── roles/
│       ├── users/
│       └── settings/
├── components/
│   ├── ui/               ← 原子 UI（Radix UI 封装，shadcn/ui 模式）
│   ├── layout/           ← MainLayout, Header, Sidebar
│   ├── chat/             ← ChatInput, MessageItem, MarkdownRenderer 等
│   ├── session/          ← SessionList, SessionItem
│   ├── admin/            ← CreateKnowledgeBaseDialog, SimpleLineChart
│   └── common/           ← ErrorBoundary, Loading, Toast, Avatar
├── services/             ← API 调用层（Axios）
├── stores/               ← 全局状态（Zustand）
├── hooks/                ← 自定义 Hooks
├── types/                ← TypeScript 类型定义
├── utils/                ← 工具函数
└── styles/               ← globals.css（Tailwind + 自定义 CSS）
```

## 状态管理（Zustand）

### chatStore（stores/chatStore.ts）

聊天功能的核心状态，约 550 行。

关键状态字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessions` | `Session[]` | 当前 KB 的会话列表 |
| `currentSessionId` | `string \| null` | 当前选中的会话 |
| `messages` | `Message[]` | 当前会话的消息列表 |
| `activeKbId` | `string \| null` | 当前锁定的知识库 ID（派生缓存） |
| `isStreaming` | `boolean` | 是否正在接收流式响应 |
| `streamTaskId` | `string \| null` | 用于取消生成的任务 ID |
| `deepThinkingEnabled` | `boolean` | 深度思考模式开关 |

关键方法：

| 方法 | 说明 |
|------|------|
| `fetchSessions(kbId?)` | 加载指定 KB 的会话列表 |
| `sendMessage(content)` | 发送消息并建立 SSE 连接处理流式响应 |
| `cancelGeneration()` | 调用 stop 接口终止生成 |
| `resetForNewSpace()` | 进入新 KB 空间时清空 sessions/messages/currentSessionId |

### authStore（stores/authStore.ts）

| 字段/方法 | 说明 |
|-----------|------|
| `user` | 当前登录用户信息 |
| `token` | Sa-Token 令牌（存 localStorage） |
| `login(username, password)` | 登录并存储 token |
| `checkAuth()` | 页面刷新时恢复认证状态 |

## API 服务层（services/）

所有服务通过 `services/api.ts` 中的 Axios 实例发请求。

**统一响应格式**：`{ code: number, data: T, message: string }`，拦截器自动解包 `data`。

| 文件 | 主要方法 |
|------|---------|
| `authService.ts` | `login`, `logout`, `getCurrentUser` |
| `sessionService.ts` | `listSessions(kbId)`, `listMessages(conversationId, kbId)`, `deleteSession`, `renameSession` |
| `knowledgeService.ts` | 知识库/文档/分块的完整 CRUD，含 `startDocumentChunk(docId)` |
| `spacesService.ts` | 知识库空间统计（`getSpacesStats`） |
| `dashboardService.ts` | `getDashboardOverview`, `getDashboardPerformance`, `getDashboardTrends` |
| `ragTraceService.ts` | 追踪记录查询 |
| `ragEvaluationService.ts` | 评估数据查询 |
| `intentTreeService.ts` | 意图树 CRUD |
| `roleService.ts` | 角色管理 + 角色-知识库关联 |
| `userService.ts` | 用户 CRUD（管理员） |
| `ingestionService.ts` | 摄入管道 + 任务管理 |

## 关键 UI 约定

- **UI 组件**：一律从 `components/ui/` 引入（shadcn/ui 模式，基于 Radix UI）。图标用 `lucide-react`。
- **样式**：Tailwind CSS 原子类为主，复杂布局的自定义 CSS 写在 `styles/globals.css`，用 `@apply` 引用 Tailwind 类。
- **新增管理后台页面**：组件放 `pages/admin/{feature}/`，在 `App.tsx` 注册路由，在 `AdminLayout.tsx` 添加侧边栏菜单项和面包屑。
- **表格**：用 `@tanstack/react-table` + `components/ui/table.tsx`。
- **表单**：用 `react-hook-form` + `zod` schema 校验。

## Gotchas

- **`kbId` 是 URL 参数（`?kbId=xxx`），是 KB 锁定的唯一来源**：`chatStore.activeKbId` 只是缓存，从 URL 读取后才更新。不要从 store 读 kbId 用于初次导航判断。
- **进入新 KB 空间时必须调 `resetForNewSpace()`**：否则旧 KB 的会话列表会显示在新空间里。
- **所有会话接口都需要传 `kbId`**：`listMessages`、`renameSession`、`deleteSession` 都要带 `kbId` 参数，后端做所有权校验。API 签名变更后必须全局搜索所有调用点（不只是计划里列的文件）。
- **Vite 热更新 vs 后端重启**：前端改动即时生效，后端 Java 改动需要手动重启 `mvn -pl bootstrap spring-boot:run`，改完后端后一定先确认后端已重启再验证接口。
- **SSE 聊天接口**：`GET /rag/v3/chat` 用 SSE 推送，前端通过原生 `EventSource` 或自定义 fetch 流处理，不是 WebSocket。取消生成调用 `POST /rag/v3/stop`，带 `taskId`。
- **品牌名**：应用名称是 "HT KnowledgeBase"（不是 "Ragent"），影响 `index.html` title、`.env` 中 `VITE_APP_NAME`、`Sidebar.tsx`、`AdminLayout.tsx`。

## 技术栈速查

| 用途 | 库 |
|------|----|
| 路由 | React Router v6 |
| 状态 | Zustand v4 |
| HTTP | Axios v1.7 |
| 表格 | @tanstack/react-table v8 |
| 表单 | react-hook-form + zod |
| Markdown | react-markdown + remark-gfm |
| 图表 | Recharts v3 |
| 文件上传 | react-dropzone |
| 虚拟滚动 | react-virtuoso |
| 通知 | sonner |
| 类名合并 | clsx + tailwind-merge |
