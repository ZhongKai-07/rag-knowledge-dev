# Knowledge Spaces 功能快速部署指南

## 前置条件

- 代码分支：`feature/knowledge-spaces`
- 基础设施已运行（PostgreSQL、Redis、RocketMQ、OpenSearch/Milvus、RustFS）

## 1. 数据库迁移（必须）

只需执行一条 SQL，在 `t_conversation` 表新增 `kb_id` 列：

```sql
-- 方式一：通过 docker exec
docker exec postgres psql -U postgres -d ragent -c "
  ALTER TABLE t_conversation ADD COLUMN IF NOT EXISTS kb_id VARCHAR(20) DEFAULT NULL;
  COMMENT ON COLUMN t_conversation.kb_id IS '关联知识库ID';
  CREATE INDEX IF NOT EXISTS idx_conversation_kb_user ON t_conversation (user_id, kb_id, last_time);
"

-- 方式二：执行迁移脚本文件
docker exec postgres psql -U postgres -d ragent -f /dev/stdin < resources/database/upgrade_v1.2_to_v1.3.sql
```

> **注意：** 如果是全新数据库初始化，直接使用 `resources/database/schema_pg.sql`（已包含 kb_id 列），无需额外迁移。

### 验证迁移成功

```sql
docker exec postgres psql -U postgres -d ragent -c "\d t_conversation"
-- 确认输出中包含 kb_id 列和 idx_conversation_kb_user 索引
```

## 2. 后端构建与启动

```bash
# 构建（跳过测试）
mvn clean install -DskipTests

# 启动（Windows PowerShell）
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

## 3. 前端启动

```bash
cd frontend
npm install   # 首次或依赖有变化时
npm run dev   # 开发模式，端口 5173
```

## 4. 验证功能

1. 浏览器访问 `http://localhost:5173` → 应自动跳转 `/spaces`
2. 登录 admin/admin → 看到知识库卡片 + 统计数据
3. 点击卡片 → 进入 `/chat?kbId=xxx` → 侧边栏空（旧会话被隔离）
4. 发消息 → 会话创建并绑定当前 KB
5. 返回 `/spaces` → 进入另一个 KB → 看不到前一个 KB 的会话

## 注意事项

- **旧会话不可见：** 迁移前的会话 `kb_id=NULL`，不属于任何空间，在 KB 空间内不会显示（设计如此，fail-closed）
- **品牌名称已改：** `Ragent AI 智能体` → `HT KnowledgeBase`（涉及 index.html、.env、Sidebar、AdminLayout）
- **后端必须重启：** Spring Boot 代码变更不会热加载，每次 `mvn install` 后需重启
- **前端自动热更新：** Vite dev server 会自动 HMR，无需手动刷新
