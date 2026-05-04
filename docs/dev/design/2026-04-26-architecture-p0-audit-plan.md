# 架构重整 P0：架构盘点与护栏计划

- **状态**：可执行
- **日期**：2026-04-26
- **责任分工**：Codex 起草并执行审计；仓库维护者评审发现并决定第一个 P1 PR。
- **范围**：只做架构盘点与护栏规划，不改业务行为。
- **上下文**：承接 Notion 页面「产品级架构设计」中的方向判断：先把系统整理成边界清晰的模块化单体，再基于稳定边界新增产品能力。

---

## 1. 目标

P0 要回答一个问题：**当前哪些架构边界已经足够清晰，哪些地方如果不加护栏，后续新增功能会持续放大维护成本？**

P0 的交付物不是重构 PR，而是一份有证据支撑的审计报告，以及一组能指导 P1-P3 开工的护栏 backlog。

## 2. 非目标

- 不拆微服务。
- 不移动包、不重命名类。
- 不改变 API 行为、数据库 schema 或前端路由。
- 不在 P0 替换 RocketMQ。
- 不在 P0 实现 Q&A Ledger。

## 3. P0 交付物

| 交付物 | 路径 / 位置 | 用途 |
| --- | --- | --- |
| P0 审计计划 | `docs/dev/design/2026-04-26-architecture-p0-audit-plan.md` | 本文档，定义执行清单与验收标准。 |
| P0 审计报告 | `docs/dev/followup/2026-04-26-architecture-p0-audit-report.md` | 记录证据、发现、严重级别、后续建议。 |
| 依赖盘点 | 写入审计报告 | 当前跨域 import、service 注入、mapper 注入、废弃 API 使用情况。 |
| 护栏 backlog | 写入审计报告 | 建议补充的 ArchUnit 规则、包依赖规则、静态扫描规则。 |
| P1 推荐切入点 | 写入审计报告 | 明确下一阶段优先做权限 ports、RAG 编排拆分，还是事件抽象。 |

## 4. 需要验证的架构边界

### 4.1 Maven 模块依赖方向

允许：

```text
bootstrap -> framework
bootstrap -> infra-ai
bootstrap -> mcp-server
infra-ai  -> framework
frontend  -> bootstrap REST/SSE API
```

禁止：

```text
framework -> bootstrap
framework -> infra-ai
infra-ai  -> bootstrap
frontend  -> mcp-server direct API
```

### 4.2 Bootstrap 业务域边界

目标域划分：

| 业务域 | 路径 | 核心职责 |
| --- | --- | --- |
| RAG | `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag` | 聊天编排、查询改写、意图、检索、Prompt、记忆、来源卡片、链路追踪。 |
| Knowledge | `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge` | 知识库、文档、分块、向量空间生命周期。 |
| Ingestion | `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion` | Fetch / Parse / Enhance / Chunk / Enrich / Index 节点流水线。 |
| Access | `bootstrap/src/main/java/com/nageoffer/ai/ragent/user` + `framework/security/port` | 用户、角色、部门、KB 授权、security level。 |
| Eval | `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval` | Gold dataset、合成、RAGAS 评估、质量闭环。 |
| Admin | `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin` | 只读跨域仪表盘聚合。 |
| Core | `bootstrap/src/main/java/com/nageoffer/ai/ragent/core` | 文档解析、分块等被 ingestion/RAG 复用的基础能力。 |

允许的跨域模式：

- Controller 只调用本域 application/service 层。
- 一个域调用另一个域时走明确 service/port，不直接注入对方 mapper。
- `admin` 可以为仪表盘做跨域只读聚合，但不写其他业务域。
- 跨域写流程必须显式化：domain event、MQ event 或命名清晰的 application service。
- 新授权检查优先使用 `framework/security/port` 下的细粒度端口，不再新增 deprecated `KbAccessService` 调用。

禁止或需要重点审计的模式：

- 普通业务域 service 直接注入其他域的 `*Mapper`。
- Controller 直接注入 mapper。
- 新代码继续注入 deprecated `KbAccessService`。
- 业务 service 直接使用 Spring transaction synchronization 或 RocketMQ transaction 语义。
- 异步线程中读取 `UserContext`，而不是在进入异步前捕获 `userId`。

## 5. 发现严重级别

审计报告统一使用以下严重级别：

| 级别 | 含义 | 例子 |
| --- | --- | --- |
| S0 | 阻塞安全推进 P1/P2，必须优先处理。 | 某个可配置向量后端静默忽略 security-level 过滤。 |
| S1 | 高优先级架构债，触碰相关链路时应优先修复。 | 核心 RAG 或 KB 写路径仍依赖 deprecated `KbAccessService`。 |
| S2 | 维护性技术债，记录并排期。 | 命名不准、前端 store 过大、格式化 helper 重复。 |
| S3 | 观察项，当前可接受但需要说明理由。 | 模式不常见，但有明确上下文和收益。 |

## 6. 执行计划

### Task 1：基线上下文快照

**需要阅读的文件**

- `CLAUDE.md`
- `bootstrap/CLAUDE.md`
- `framework/CLAUDE.md`
- `infra-ai/CLAUDE.md`
- `frontend/CLAUDE.md`
- `docs/dev/arch/overview.md`
- `docs/dev/arch/bootstrap.md`
- `docs/dev/arch/frontend.md`
- `docs/dev/arch/code-map.md`
- `docs/dev/arch/business-flows.md`
- `docs/dev/followup/architecture-backlog.md`

**检查项**

- [ ] 确认文档中的模块依赖方向仍然匹配 `pom.xml`。
- [ ] 确认文档中的 bootstrap 业务域仍然匹配实际包名。
- [ ] 确认哪些现有 architecture backlog 与 P0 重叠，避免把已知债务误报成新发现。

**预期输出**

在审计报告中新增 `基线快照` 小节，包含：

- 当前 Maven 模块列表；
- 当前业务域列表；
- 已知预存架构债，作为后续发现的背景。

### Task 2：Maven 模块依赖盘点

**命令**

在仓库根目录执行：

```bash
rg "<artifactId>|<module>" pom.xml */pom.xml
```

如果 shell 不稳定，直接读取：

```text
pom.xml
bootstrap/pom.xml
framework/pom.xml
infra-ai/pom.xml
mcp-server/pom.xml
```

**检查项**

- [ ] 验证 `framework` 不依赖 `bootstrap`。
- [ ] 验证 `framework` 不依赖 `infra-ai`。
- [ ] 验证 `infra-ai` 不依赖 `bootstrap`。
- [ ] 列出所有可能弱化边界的 compile-scope 依赖。

**预期输出**

在审计报告中新增 `Maven 模块依赖` 表：

| From | To | Scope | 状态 | 备注 |
| --- | --- | --- | --- | --- |

### Task 3：Java 跨域 import 与注入盘点

**命令**

在仓库根目录执行：

```bash
rg "^import com\\.nageoffer\\.ai\\.ragent\\.(rag|knowledge|ingestion|user|admin|core|eval)\\." bootstrap/src/main/java
rg "@Autowired|private final .*Service|private final .*Mapper" bootstrap/src/main/java
```

**检查项**

- [ ] 按来源域和目标域分组 imports。
- [ ] 区分可接受 import 与可疑 import。
- [ ] 非 `admin` 域直接注入其他域 `*Mapper` 时，默认标为 S1，除非能证明是明确例外。
- [ ] Controller 直接注入 mapper 时标为 S1。

**预期输出**

在审计报告中新增 `跨域 import 与注入` 表：

| 源文件 | 来源域 | 目标符号 | 目标域 | 模式 | 严重级别 | 建议 |
| --- | --- | --- | --- | --- | --- | --- |

### Task 4：Deprecated 授权接口盘点

**命令**

```bash
rg "KbAccessService" bootstrap/src/main/java framework/src/main/java
rg "CurrentUserProbe|KbReadAccessPort|KbManageAccessPort|SecurityLevel" bootstrap/src/main/java framework/src/main/java
```

**检查项**

- [ ] 统计每一个残留的 `KbAccessService` 注入或调用。
- [ ] 按行为分类：读权限、管理权限、最大安全等级、角色分配校验、缓存失效。
- [ ] 识别哪些调用已经有细粒度 port 替代。
- [ ] RAG 检索、KB 写、文档写、角色分配路径按风险标 S0/S1。

**预期输出**

在审计报告中新增 `Deprecated 授权接口使用` 表：

| 文件 | 方法 / 链路 | 当前 API | 替代 port | 严重级别 | 迁移说明 |
| --- | --- | --- | --- | --- | --- |

### Task 5：异步、事件与 MQ 泄漏盘点

**命令**

```bash
rg "RocketMQ|sendInTransaction|TransactionSynchronizationManager|@Async|@TransactionalEventListener|UserContext" bootstrap/src/main/java framework/src/main/java
```

**检查项**

- [ ] 列出业务 service 直接调用 RocketMQ transaction API 的位置。
- [ ] 列出业务 service 直接调用 `TransactionSynchronizationManager` 的位置。
- [ ] 列出异步路径中读取 `UserContext` 的位置。
- [ ] 区分安全的 `RagTraceContext` TTL 用法与不安全的普通 `UserContext` 用法。

**预期输出**

在审计报告中新增 `异步与事件边界` 表：

| 文件 | 机制 | 为什么重要 | 严重级别 | 建议 |
| --- | --- | --- | --- | --- |

### Task 6：向量后端与 security-level 不变量检查

**命令**

```bash
rg "metadataFilters|security_level|VectorStoreService|RetrieverService|VectorStoreAdmin" bootstrap/src/main/java
```

**检查项**

- [ ] 对比 OpenSearch、Milvus、pgvector 的 metadata filter 行为。
- [ ] 对比三个向量后端的向量空间创建 / 删除生命周期能力。
- [ ] 确认不支持的后端是 fail-fast，还是静默降级。
- [ ] 如果某后端可通过 `rag.vector.type` 选中且会静默忽略 security-level，标为 S0。

**预期输出**

在审计报告中新增 `向量后端不变量` 表：

| 后端 | 读过滤支持 | 写 metadata 支持 | Admin 生命周期支持 | 风险 | 建议 |
| --- | --- | --- | --- | --- | --- |

### Task 7：RAG 编排热点盘点

**命令**

```bash
rg "class RAGChatServiceImpl|streamChat\\(|buildStructuredMessages|retrieve\\(|appendAssistant|RagTraceContext|CitationStats" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag
```

**检查项**

- [ ] 判断 `RAGChatServiceImpl` 中还剩多少编排逻辑。
- [ ] 列出已经拆成 service 的子能力。
- [ ] 列出仍然以 private method 或 callback side effect 存在的子能力。
- [ ] 找出 P2 最安全的第一个拆分点。

**预期输出**

在审计报告中新增 `RAG 编排拆分候选` 表：

| 能力 | 当前位置 | 耦合点 | 建议边界 | 优先级 |
| --- | --- | --- | --- | --- |

### Task 8：前端架构热点盘点

**命令**

在 `frontend/` 下执行：

```bash
rg "create\\(|zustand|use.*Store|fetch\\(|ReadableStream|EventSource|permissions|canSee|isSuperAdmin|isDeptAdmin" src
```

**检查项**

- [ ] 确认 `chatStore.ts` 是否仍然是最大状态热点。
- [ ] 列出解析 SSE frame 或管理 streaming state 的位置。
- [ ] 验证权限判断是否仍然通过 `utils/permissions.ts`。
- [ ] 找出一个低风险的 P5 前端拆分候选点。

**预期输出**

在审计报告中新增 `前端热点` 表：

| 区域 | 当前文件 | 风险 | 建议拆分 | 优先级 |
| --- | --- | --- | --- | --- |

### Task 9：护栏 backlog

**候选护栏**

- ArchUnit：`framework` 包不得 import `bootstrap`。
- ArchUnit：`infra-ai` 包不得 import `bootstrap`。
- ArchUnit：Controller 不得依赖 `*Mapper`。
- ArchUnit：非 `admin` 域不得注入其他域 `*Mapper`。
- ArchUnit：新代码不得注入 deprecated `KbAccessService`。
- 静态扫描：事件抽象引入后，业务 service 不得直接使用 `TransactionSynchronizationManager`。
- 静态扫描：已知异步消费者中不得调用 `UserContext.getUserId()`。

**检查项**

- [ ] 每条护栏判断为立即强制、先告警、或延后。
- [ ] 避免一开始就加入大量会被历史问题打爆的规则；历史违规需要隔离记录。
- [ ] 优先加入能保护新代码、不会强迫大规模重构的规则。

**预期输出**

在审计报告中新增 `护栏 backlog` 表：

| 规则 | 执行方式 | 初始状态 | 误报风险 | 建议阶段 |
| --- | --- | --- | --- | --- |

### Task 10：P1 推荐切入点

**检查项**

- [ ] 只推荐一个下一阶段切入点。
- [ ] 说明为什么它应该排在其他选项前面。
- [ ] 给出该阶段最小的第一个 PR。
- [ ] 给出该 PR 的验证命令。

**预期输出**

在审计报告中新增 `推荐下一阶段` 小节：

```text
推荐：P1 从 ...
理由：
最小第一个 PR：
验证：
```

## 7. 审计报告模板

创建 `docs/dev/followup/2026-04-26-architecture-p0-audit-report.md`，结构如下：

```markdown
# 架构重整 P0 审计报告

- **日期**：2026-04-26
- **状态**：Draft
- **范围**：只做架构盘点与护栏规划

## Executive Summary

## 基线快照

## Maven 模块依赖

## 跨域 import 与注入

## Deprecated 授权接口使用

## 异步与事件边界

## 向量后端不变量

## RAG 编排拆分候选

## 前端热点

## 护栏 backlog

## 推荐下一阶段

## Appendix：执行过的命令
```

## 8. 验收标准

P0 只有在以下条件全部满足时才算完成：

- [ ] 审计报告已创建：`docs/dev/followup/2026-04-26-architecture-p0-audit-report.md`。
- [ ] 每个 S0/S1 发现都有文件路径、当前行为、风险和建议动作。
- [ ] Deprecated `KbAccessService` 使用点已统计，并按替代路径分组。
- [ ] 跨域 mapper 注入已与 `admin` 只读聚合例外分开。
- [ ] OpenSearch、Milvus、pgvector 的 security-level 行为已明确分类。
- [ ] 护栏 backlog 区分了立即强制规则和延后规则。
- [ ] 推荐的 P1 起点是一个具体 PR，不是宽泛主题。
- [ ] 审计阶段不产生生产行为变更。

## 9. 建议执行顺序

1. 先执行 Task 1-2，确认文档架构与模块依赖仍然一致。
2. 再执行 Task 3-4，因为跨域访问与授权债务会决定 P1 风险。
3. 再执行 Task 5-6，用于判断事件抽象和向量后端问题的优先级。
4. 再执行 Task 7-8，估算后续 RAG 与前端拆分成本。
5. 最后执行 Task 9-10，在证据收集完成后生成护栏 backlog 和 P1 推荐。

## 10. 预期结果

P0 的预期结果是一份能支撑决策的架构报告，而不是重构本身。P0 完成后，团队应该清楚：

- 哪些边界已经可以依赖；
- 哪些跨域依赖是有意例外；
- 哪些跨域依赖是偶然耦合；
- 哪些护栏可以马上启用；
- P1 应该先做权限 ports、RAG 编排拆分，还是事件抽象。
