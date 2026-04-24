# ragent 全新环境搭建指南

> 适用场景：一台干净的机器（或老环境需要完全重建），Docker 在但容器/数据库/索引全部是老的或为空。从零拉起全套基础设施 + 建库 + 跑通后端前端。
>
> 基线分支：`feature/rbac-pr3-frontend-demo`（含 PR1 RBAC 数据层 + PR3 设计文档）

---

## 0. 前提条件

| 依赖 | 版本要求 | 检查命令 |
|---|---|---|
| Docker + Docker Compose v2 | 最新 | `docker compose version` |
| JDK | 17 | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Node.js | 18+ | `node -v` |
| Git | 任意 | `git --version` |

确保仓库已 clone 并切到正确分支：

```bash
cd E:\AIProject\ragent    # 或你的实际路径
git checkout feature/rbac-pr3-frontend-demo
git pull origin feature/rbac-pr3-frontend-demo
```

---

## 1. 启动基础设施容器

需要 **5 个容器组**，按依赖顺序启动。

### 1.1 PostgreSQL + Redis

如果已有 PostgreSQL 和 Redis 容器在跑，跳到 1.2。否则：

```bash
# PostgreSQL（密码 postgres，端口 5432）
docker run -d --name postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16

# Redis（密码 123456，端口 6379）
docker run -d --name redis \
  -p 6379:6379 \
  redis:7 \
  redis-server --requirepass 123456
```

验证：

```bash
docker exec postgres psql -U postgres -c "SELECT 1;"
docker exec redis redis-cli -a 123456 PING
```

### 1.2 RustFS（S3 兼容对象存储）+ Milvus

```bash
cd resources/docker
docker compose -f milvus-stack-2.6.6.compose.yaml up -d
```

启动 3 个容器：
- `rustfs`：S3 兼容对象存储（端口 9000 API / 9001 Console）
- `etcd`：Milvus 元数据
- `milvus-standalone`：向量数据库（端口 19530）

验证：

```bash
# RustFS
curl -s http://localhost:9000/ | head -5   # 有响应即可

# Milvus 健康
curl -s http://localhost:9091/healthz      # 期望 {"isHealthy":true}
```

> **注意**：应用配置 `rag.vector.type: opensearch`，所以 **Milvus 不是运行时必须**。但 **RustFS 是必须的** —— 文档上传存储全走它。如果你不想启动 Milvus，可以只启 RustFS：
> ```bash
> docker compose -f milvus-stack-2.6.6.compose.yaml up -d rustfs
> ```

### 1.3 OpenSearch + Dashboards

```bash
cd resources/docker
docker compose -f opensearch-stack.compose.yaml up -d
```

启动 2 个容器：
- `ragent-opensearch`：端口 9201（映射容器内 9200）
- `ragent-opensearch-dashboards`：端口 5602

验证：

```bash
curl -s http://localhost:9201/_cluster/health | jq .status
# 期望 "green" 或 "yellow"（单节点正常是 yellow）
```

### 1.4 RocketMQ

```bash
cd resources/docker
docker compose -f rocketmq-stack-5.2.0.compose.yaml up -d
```

> 如果机器是 ARM 架构，改用 `rocketmq-stack-amd-5.2.0.compose.yaml`

启动 3 个容器：
- `rmqnamesrv`：端口 9876
- `rmqbroker`：端口 10909/10911/10912
- `rocketmq-dashboard`：端口 8082

验证：

```bash
docker logs rmqnamesrv 2>&1 | tail -3
# 日志中有 "The Name Server boot success" 即可
```

---

## 2. 初始化数据库

```bash
# 2.1 创建数据库（如果已有旧的 ragent 库，先删再建）
docker exec postgres psql -U postgres -c "DROP DATABASE IF EXISTS ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"

# 2.2 导入新 schema（PR1 后的完整 DDL）
# 包含：sys_dept / t_role.role_type / t_role.max_security_level /
#       t_knowledge_document.security_level / t_user.dept_id /
#       t_knowledge_base.dept_id 等新字段
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql

# 2.3 导入种子数据
# 包含：GLOBAL 部门 + 超级管理员角色(SUPER_ADMIN/max=3) + 普通用户角色(USER/max=0) +
#       admin 用户(dept=GLOBAL) + admin-角色关联
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql
```

验证：

```bash
# 检查 sys_dept 表有 GLOBAL 种子
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT id, dept_code, dept_name FROM sys_dept;"
#  id | dept_code | dept_name
# ----+-----------+-----------
#  1  | GLOBAL    | 全局部门

# 检查 admin 用户及其部门
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT id, username, dept_id FROM t_user;"
#  id | username | dept_id
# ----+----------+---------
#  1  | admin    | 1

# 检查 admin 的角色关联
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT ur.user_id, r.name, r.role_type, r.max_security_level FROM t_user_role ur JOIN t_role r ON r.id = ur.role_id;"
#  user_id |    name    | role_type   | max_security_level
# ---------+------------+-------------+--------------------
#  1       | 超级管理员 | SUPER_ADMIN | 3
```

---

## 3. 清空 OpenSearch 索引和 Redis 缓存

```bash
# 删除所有旧索引（PR1 改了 metadata schema，旧索引不兼容）
curl -X DELETE "http://localhost:9201/_all"
# 期望 {"acknowledged":true}

# 清空 Redis（旧的 kb_access 缓存 key 结构可能不兼容）
docker exec redis redis-cli -a 123456 FLUSHDB
# 期望 OK
```

---

## 4. 构建后端

```bash
# 在仓库根目录执行
mvn spotless:apply                        # 先自动格式化
mvn clean install -DskipTests             # 编译打包（跳过测试）
```

期望最终输出 `BUILD SUCCESS`。

---

## 5. 启动后端

**必须在 PowerShell 中执行**（`$env:` 是 PowerShell 语法；不设代理变量，RustFS/S3 连接会走系统代理导致超时）：

```powershell
$env:NO_PROXY='localhost,127.0.0.1'
$env:no_proxy='localhost,127.0.0.1'
mvn -pl bootstrap spring-boot:run
```

期望看到：

```
Started RagentCoreApplication in X.XX seconds
```

后端跑在 `http://localhost:9090/api/ragent`。

---

## 6. 安装并启动前端

```bash
cd frontend
npm install          # 首次需要，之后跳过
npm run dev          # 开发模式，Vite HMR
```

Vite 启动后显示 `http://localhost:5173`。代理配置自动把 `/api/ragent` 请求转到 `localhost:9090`。

---

## 7. 验证全链路

### 7.1 登录

浏览器打开 `http://localhost:5173/login`

| 用户名 | 密码 | 预期 |
|---|---|---|
| admin | 123456 | 登录成功，跳转到 /spaces |

### 7.2 检查 API 响应

```bash
# 获取 token（注意：auth header 是 raw token，没有 Bearer 前缀）
TOKEN=$(curl -s -X POST http://localhost:9090/api/ragent/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | jq -r '.data.token')

echo "Token: $TOKEN"

# 检查 /user/me
curl -s http://localhost:9090/api/ragent/user/me \
  -H "Authorization: $TOKEN" | jq .
```

期望返回包含 `userId`、`username` 等字段的 JSON。

> PR3 Task 0.15 完成后，响应会新增 `deptId / deptName / roleTypes / maxSecurityLevel / isSuperAdmin / isDeptAdmin` 字段并删除 `role` 字段。PR1 基线下 `role` 还在。

### 7.3 检查基础设施端点

```bash
# OpenSearch 集群状态
curl -s http://localhost:9201/_cluster/health | jq .

# RustFS（应返回 XML 错误或 access denied，说明服务在跑）
curl -s http://localhost:9000/ | head -5

# RocketMQ Dashboard（浏览器访问）
# http://localhost:8082
```

---

## 8. 端口速查表

| 服务 | 端口 | 用途 | 凭证 |
|---|---|---|---|
| Spring Boot 后端 | 9090 | API `/api/ragent` | — |
| Vite 前端 | 5173 | 开发服务器（代理到后端） | — |
| PostgreSQL | 5432 | 数据库 | user: `postgres` / pwd: `postgres` |
| Redis | 6379 | 缓存 | pwd: `123456` |
| RustFS API | 9000 | S3 兼容对象存储 | key: `rustfsadmin` / secret: `rustfsadmin` |
| RustFS Console | 9001 | Web 管理控制台 | 同上 |
| OpenSearch | 9201 | 向量搜索引擎 | 无认证（已禁用 security plugin） |
| OpenSearch Dashboards | 5602 | 可视化界面 | — |
| RocketMQ NameServer | 9876 | 消息队列注册中心 | — |
| RocketMQ Broker | 10909/10911 | 消息代理 | — |
| RocketMQ Dashboard | 8082 | Web 管理控制台 | — |
| Milvus | 19530 | 向量数据库（当前未启用） | — |

---

## 9. 常见问题

**Q: 后端启动报 RustFS 连接超时**
A: 没设 `NO_PROXY` 环境变量。必须在 PowerShell 里 `$env:NO_PROXY='localhost,127.0.0.1'` 再跑 mvn。

**Q: 后端启动报 `Connection refused: localhost:9876`**
A: RocketMQ NameServer 未就绪。等容器 healthy 后再启动：
```bash
docker logs rmqnamesrv 2>&1 | grep "boot success"
```

**Q: 前端登录后页面白屏 / 有旧数据残留**
A: 浏览器 F12 → Application → Local Storage → 清空所有 key，刷新。

**Q: OpenSearch 报 `index_not_found_exception`**
A: 正常。首次启动没有索引。创建知识库 + 上传文档 + 触发分块后，OpenSearch 会自动建索引。

**Q: `mvn clean install` 报 surefire VM crash（Windows）**
A: 已知问题（P2 待修）。用 `-DskipTests` 绕过，不影响应用运行。

**Q: 数据库用户搞混了**
A: PostgreSQL 用户是 `postgres`，**不是** `ragent`。所有 `psql` 命令用 `-U postgres`。

**Q: 8080/8081 端口被占**
A: RocketMQ Broker 暴露了 8080/8081/8082 三个端口（broker 管理 + dashboard）。如果和本机其他服务冲突，修改 `rocketmq-stack-5.2.0.compose.yaml` 的端口映射。

---

## 10. 快速重建（日常开发用）

当你需要清空数据重来（不重建容器）：

```bash
# 数据库重建
docker exec postgres psql -U postgres -c "DROP DATABASE ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql

# OpenSearch 清空
curl -X DELETE "http://localhost:9200/_all"

# Redis 清空
docker exec redis redis-cli -a 123456 FLUSHDB

# 重启后端（PowerShell）
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'
mvn -pl bootstrap spring-boot:run
```

---

## 11. PR3 Demo 数据（可选）

PR3 的 28 个 task 全部完成后，提供两种验收模式：

### Mode A — UI walkthrough（证明前端 CRUD 闭环）

使用 §10 的快速重建，**不加载 fixture**。然后按 `docs/dev/verification/pr3-demo-walkthrough.md` 的 12 步手动操作。

### Mode B — curl bypass 矩阵（证明后端授权边界）

在 §10 的快速重建基础上，**额外加载 fixture**：

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/fixture_pr3_demo.sql
```

然后按 `docs/dev/verification/pr3-curl-matrix.http` 逐条执行。

> 两种模式**不可混合**。详见 `docs/superpowers/plans/2026-04-12-pr3-rbac-frontend-demo.md` Task S9。

---

## 12. 可选基础设施

### ragent-eval（RAG 评估 Python 服务，可选）

用于 Gold Set 合成与 RAGAS 四指标评估。开发环境默认不起，需要评估时再拉起。

```bash
# 先设 DASHSCOPE_API_KEY（或写进 .env）
export DASHSCOPE_API_KEY=sk-xxx    # Windows PowerShell: $env:DASHSCOPE_API_KEY='sk-xxx'

docker compose -f resources/docker/ragent-eval.compose.yaml up -d

# 验证
curl http://localhost:9091/health
```
