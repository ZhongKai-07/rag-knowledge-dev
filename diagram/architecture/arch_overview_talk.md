# 架构概览汇报 · 口播稿（10 分钟）

> 配合主图：[`arch_overview.drawio`](arch_overview.drawio)
> 场景：给领导 + 团队做系统概览汇报；其他分层细节见 `docs/dev/arch/*.md`。

## 0. 导出主图

drawio 桌面版 → 打开 `arch_overview.drawio` → `File → Export as → PNG`，勾选 "Include a copy of the diagram" 方便后续可编辑。

---

## 1. 系统是什么（1 分钟）

> "HT KnowledgeBase 是一个企业级知识库 + RAG 问答系统。用户上传内部文档，系统自动切块、向量化、建索引；用户提问时走'检索 + 生成'管道，结合 LLM 输出带出处的答案，并受 RBAC 与安全等级控制谁能看到哪些资料。"

一句话目标 + 两条主链路（问答 / 入库），其他都是支撑。

## 2. 四层分工（2 分钟，指着总览图讲）

指图从上到下讲：

- **Frontend**：React SPA，只做交互和展示，不承担权限边界。
- **Bootstrap**：Spring Boot 主应用，按**业务域**组织（rag / ingestion / knowledge / user / admin / core），是唯一对外接口层。
- **framework + infra-ai**：两块共享基础设施——framework 提供 Result/异常/TTL 链路追踪/幂等等通用能力；**infra-ai 把 LLM/Embedding/Rerank 统一成接口，屏蔽百炼/Ollama/SiliconFlow 三家差异，内建路由和故障降级**。
- **Infrastructure**：PostgreSQL + Redis + RocketMQ + **OpenSearch（向量库）** + RustFS（S3 对象存储） + 外部 LLM。

一句点题：
> "**依赖方向严格单向**，framework/infra-ai 不能反过来依赖 bootstrap，业务代码永远不碰具体模型或具体向量库。"

## 3. 两条核心链路（3.5 分钟）

### 3.1 RAG 问答（SSE）

> "用户在聊天框发问 → `RAGChatController` 接 SSE → AOP 限流入队 + 生成 traceId → `RAGChatServiceImpl` 按顺序跑：加载历史记忆 → 查询改写与子问题拆分 → 意图识别（歧义直接返回引导语） → 多通道检索（从 OpenSearch 拿 chunk，去重 + 重排） → 组装 Prompt → 调 `LLMService.streamChat`，结果边生成边推到前端；结束时回写 token 用量和追踪。"

强调两点：

- **全链路 traceId 贯穿**，`t_rag_trace_run/node` 可回放问答过程。
- **所有短路点都可观测**（未登录 / 无可见 KB / 歧义 / 零命中），在追踪表里能看到停在哪一步。

### 3.2 文档入库（MQ 异步）

> "用户在管理后台上传文档 → 文件进 RustFS，数据库记录 `status=PENDING` → 手动点击'开始分块' → 发一条 RocketMQ **事务消息**（保证'消息发出'和'状态变 RUNNING'原子） → 消费者异步跑节点链：Fetcher 取文件 → Parser (Tika) 解析 → Chunker 切块并向量化 → Indexer 写入 OpenSearch → 原子事务里删旧写新，`status=SUCCESS`。"

强调两点：

- **用户请求立即返回，耗时的 LLM 嵌入走异步**，不阻塞前端。
- **节点链是配置驱动**，加新节点（PII 脱敏、OCR 等）不改编排代码。

## 4. 关键架构决策（3 分钟）

### 4.1 RBAC 单一真相源（重点）

> "整个系统的权限唯一真相源就是后端的 `KbAccessService`。前端有个 `permissions.ts` 只负责**乐观渲染**——决定菜单显不显示、按钮灰不灰——它永远不是权限边界。任何 API 调用到后端后都会再判一次，前端被拒时优雅地 return null 或 toast，不会崩。"

> "这个约定避免了权限散落到前端十几个地方造成不一致。改权限规则只改一处。"

### 4.2 RBAC 过滤链路（重点，按时间顺序讲）

**登录阶段**：

```
用户登录 → AuthController → Sa-Token 签发 token
       → UserProfileLoader 一次 JOIN 加载 user+dept+roles
       → 生成 LoginUser（含 roleTypes、maxSecurityLevel）
```

**每次请求入口**：

```
HTTP 请求 → SaInterceptor.checkLogin()
        → UserContext 填充（ThreadLocal 里存 LoginUser）
```

**RAG 检索前过滤**：

```
RAGChatServiceImpl 开场先调：
  accessibleKbIds = KbAccessService.getAccessibleKbIds(userId)
                    ↑ Redis 缓存 kb_access:{userId}，命中直接返回
                    ↑ 未命中查 t_user_role + t_role_kb_relation 再回写
对每个 KB 再拿 maxSecurityLevel → 塞进 SearchContext
```

**向量库过滤**（OpenSearch 真正拦截的地方）：

```
OpenSearchRetrieverService 构造查询：
  bool {
    must: 向量相似度
    filter: kb_id IN {accessibleKbIds}
            AND security_level <= {userMaxSecurityLevel[kbId]}
  }
→ 用户永远拿不到越权数据，连 chunk 都检索不出来
```

**关键特性**：

- **失效协议主动触发**：改角色、改部门、改 KB 绑定时主动清 Redis 缓存，不靠 TTL 自愈，避免权限滞后。
- **Last-SUPER_ADMIN 守护**：删除/降级最后一个超管前会 pre-flight 模拟检查，防止系统失管。
- **DEPT_ADMIN 隐式权限**：同部门 KB 不用显式绑定也有 MANAGE；跨部门必须显式配。

### 4.3 LLM 路由降级（次重点，一句话带过）

> "业务代码只注入 `LLMService` 接口。infra-ai 内部按优先级选模型，失败自动切下一家，连续失败进入熔断（Redis 存状态），评测时换模型不用改业务代码。"

## 5. 一个已知风险（30 秒）

> "目前 `security_level` 过滤逻辑**只在 OpenSearch 实现了**。我们当前生产只用 OpenSearch，风险可控；但切换到 Milvus/pgvector 会静默失效——已登记在 `docs/dev/followup/backlog.md`，切换前必须先补齐。其他细节技术债都在同一份文档里。"

---

## 节奏建议

| 段 | 时长 | 重点听众 |
| --- | --- | --- |
| 1 是什么 | 1' | 领导 |
| 2 四层 | 2' | 团队 |
| 3 两条链路 | 3.5' | 领导 + 团队 |
| **4 RBAC 两节** | **3'** | **领导（合规）+ 团队** |
| 5 风险 | 0.5' | 领导 |

RBAC 两节是全场信息密度最高的地方，讲慢、手指跟着链路走。其余位置可以略加速。
