# 权限管理体系第一性原理产品评审（2026-04-24）

> **用途**：产品经理视角的权限体系全面分析。用于指导 RBAC/ACL 重构方向与长期建设路线。
>
> **配套文档**：
> - `2026-04-18-authorization-baseline.md` — 端点级权限矩阵审计快照（覆盖率 60%，20+ 漏洞端点）
> - `2026-04-18-rbac-refactor-retrospective.md` — RBAC 重构复盘
> - `../followup/backlog.md` — 权限/安全相关技术债（SL-1 / SL-2 / SRC-1 / SEC-1 等）
>
> **观察样本**：截至 2026-04-24 的 `main` + `feature/pr2-over-retrieve`。

---

## 一、先给一个诚实的判断：这不是"权限系统"的问题，是"信任模型"的问题

真实命题不是"如何构建健壮的权限管理体系"，而是：**这个系统里有多少种不同的信任关系？每种信任关系下谁对谁负责？**

当前代码里混在一起的信任域至少有 **6 类**，它们被同一套"SUPER/DEPT/USER 三级 + KB 读写"模型强行压平了：

| 信任域 | 本质 | 当前建模 | 问题 |
|---|---|---|---|
| 个人数据 | 会话/消息 | `user_id` owner | ✅ 合理 |
| 数据资产 | KB + Doc + security_level | RBAC + MAC | 🟡 建模正确但覆盖不全 |
| 组织边界 | 部门 | `dept_id` 单层扁平 | 🔴 层级/转岗/跨部门协作全缺 |
| **AI 运营配置** | 意图树/映射/样例问题/RAG 参数 | **无** | 🔥 投毒入口，全员可改 |
| **可观测数据** | trace/evaluation | **无** | 🔥 泄他人查询内容 |
| **AI Pipeline 自动化** | ingestion tasks | **无** | 🔥 SSRF + 任意文件拉取 |

基线审计的结论非常直白："**20/23 零检查行是结构性问题**——新加的 Controller 默认无检查"。这不是某个 PR 的疏漏，是**默认信任边界没被制度性表达**。

---

## 二、从第一性原理看权限管理

### 2.1 权限的底层目的函数

```
最小化:  Σ [ P(坏事发生) × Loss(坏事) ]
约束:    用户完成任务的摩擦 ≤ 可接受阈值
```

任何权限设计偏离这两项都会失败——"太严"产生绕过（用户拿管理员 token 工作），"太松"产生事故。

### 2.2 任何访问决策可还原为四元组 `(Subject, Object, Action, Context)`

```
Decision = Policy(S, O, A, C) → { Allow | Deny | Challenge }
```

当前项目只建模了 S（角色）和 O（KB/Doc）的部分属性，**Action 粒度模糊、Context 完全缺失**（无 IP、设备、时间、查询频次、业务状态）。这是为什么 `/rag/evaluations` 这种"能绕过 security_level 拿全量检索结果"的漏洞能存在——Action 没被建模为独立维度。

### 2.3 权限模型谱系与本项目的错配

```
DAC  ──► MAC ──► RBAC ──► ABAC ──► ReBAC ──► PBAC
owner   强制     角色      属性     关系     策略即代码
授权   (SL)    (现状)    (需要)   (需要)   (建议终态)
```

**RAG 知识库的本质决定了 RBAC 必然不够用**：
- 知识库是**数据图谱**，用户与知识之间的关系是**多对多图**（部门归属 × 项目参与 × 临时授权 × 委托），不是"岗位"
- 单纯 RBAC 会让"角色爆炸"成为必然——每新增一个 KB 就得发明 `KB_X_Reader/Writer/Manager` 三个角色
- 这也是为什么生产环境"没有 DEPT_ADMIN 实例，退化成二极模式"的根本原因：RBAC 在实际业务里**建不出中间态**

---

## 三、AI 知识库独有的权限维度（传统 RBAC 框架不覆盖）

这是本项目最被忽视的部分。传统系统管好 CRUD 就够了，AI 系统不行：

| 维度 | 问题 | 当前状态 |
|---|---|---|
| **Query-level 授权** | "能问什么"本身是权限——PII 查询、越狱注入、恶意探测 | ❌ 无 |
| **Retrieval 配额** | 单次/日度能读多少 chunk（防内部爬取 KB） | ❌ 无 |
| **生成时再鉴权** | 多 chunk 拼合出高于单 chunk 的机密等级（derivative confidentiality） | ❌ 无 |
| **Tool-level 授权** | MCP 工具调用边界（哪些工具对哪些角色可见） | ❌ 无 |
| **Observability 授权** | trace 含他人 query，默认泄漏他人意图 | 🔥 当前已泄 |
| **Export 授权** | 导出 = 数据出境，放大攻击面 | 🔥 `/evaluations/export` 全员可调 |
| **AI 配置授权** | 意图树/映射/样例题 = 投毒入口，污染所有人检索 | 🔥 全员可改 |
| **Citation 可信度** | 用户收到答案后如何判断自己"该不该看到这条" | ❌ 前端无等级标签 |

**"生成时再鉴权"特别关键**：假设文档 A（SL=1）讲"项目代号=X"、文档 B（SL=1）讲"X 的客户=Y"，一个 maxSL=1 的用户逐个查都合规，但让 LLM 同时拿 A+B 生成的答案"客户 Y 的项目=X"可能实际是 SL=2 级。这是只有 AI 系统才有的新型权限问题，目前项目完全没考虑。

---

## 四、对当前实现的产品评审

### 4.1 做对的地方（保留）

1. **双保险检索链路** — `DefaultMetadataFilterBuilder`（pre-filter）+ `AuthzPostProcessor`（post-filter）的纵深防御是正确方向
2. **KB-Role-Permission 细分 READ/WRITE/MANAGE** — 三级读写分离建模合理，只是未下沉到所有写接口
3. **前端权限快照（`getPermissions(user)`）** — 集中判定函数，可维护性好
4. **Last-SUPER_ADMIN 守护** — 防止锁死系统，符合"制度不可自毁"原则

### 4.2 结构性缺陷（必改）

| # | 缺陷 | 产品后果 |
|---|---|---|
| D1 | **默认放行**：无注解 = 登录即可用 | 新 Controller 必漏，60% 是现在、不是终点 |
| D2 | **RBAC 单维度**：缺 Attribute/Relationship/Context 维度 | 跨部门协作、临时授权、外部顾问全做不出来 |
| D3 | **部门扁平单层** | 集团→事业部→子公司 三层组织建不出 |
| D4 | **KbAccessService 成 god-service**（22 方法/550 行） | 权限逻辑纠缠在业务里，无法单独测试/审计 |
| D5 | **审计日志 = 0** | 出事无法追溯，合规性不可证明 |
| D6 | **security_level 是纯数字** | 用户不知道自己是几级，管理员不知道文档打几级 |
| D7 | **Milvus/Pg 静默忽略 metadataFilters（SL-1）** | 换向量后端=权限系统瘫痪，但不会报错 |
| D8 | **无租户隔离** | 企业客户问"我司数据独立吗"无法回答 |
| D9 | **AI 运营配置零门禁** | 普通用户可修改 intent-tree 瘫痪全员检索 |
| D10 | **观测/评测数据泄漏** | 他人 query 明文可见，隐私不可证 |

### 4.3 伪需求 vs 真需求识别

用产品语言**重新提问**：

❌ "我们要一套 RBAC/ABAC 系统" — 这是技术，不是需求

✅ **真需求 1**：管理员能在**不读代码**的情况下，告诉审计组"某条答案为什么被这个用户看到"

✅ **真需求 2**：新人入职 T+0 能拿到**刚好够用**的权限，不需要找运维

✅ **真需求 3**：敏感文档误传时，能**10 分钟内**撤回它被任何人看到的可能性

✅ **真需求 4**：任何新加的 Controller，不写权限**就无法合入**主干

✅ **真需求 5**：客户问"你们怎么保证我的数据不被别人看到"时，有一段可验证的回答（不是"我们有 RBAC"）

把真需求写清楚后会发现，单独做"RBAC 重构"永远达不到——必须重构**三个层**：**策略模型、制度机制、用户叙事**。

---

## 五、第一性原理下的建设方向

### 5.1 策略模型：RBAC + ABAC + ReBAC 三层组合

```
┌─────────────────────────────────────────┐
│  PEP (Policy Enforcement Point)         │  ← 网关/注解统一切入
├─────────────────────────────────────────┤
│  PDP (Policy Decision Point)            │  ← OPA/Cedar 策略引擎
│  ┌─────────┬─────────┬─────────┐        │
│  │  RBAC   │  ABAC   │  ReBAC  │        │
│  │ (岗位)  │ (属性)  │ (关系)  │        │
│  └─────────┴─────────┴─────────┘        │
├─────────────────────────────────────────┤
│  PIP (Policy Information Point)         │  ← user/dept/kb/doc 属性源
└─────────────────────────────────────────┘
```

- **RBAC 管"岗位层"**：谁在什么位置（SUPER/DEPT/USER × 部门 × 职级）
- **ABAC 管"条件层"**：文档标签、时间窗口、IP、设备、业务状态
- **ReBAC 管"关系层"**：协作项目、临时授权、代理关系、数据血缘

策略用 DSL 表达（OPA Rego 或 AWS Cedar），**策略即代码**、可版本化、可单测。

### 5.2 制度机制：让"默认拒绝"不可绕过

这是重构成败的试金石。按 `2026-04-18-authorization-baseline.md` 第 13 节的思路：

```java
@ArchTest
static final ArchRule all_endpoints_require_decision =
    methods().that().areAnnotatedWithRestMapping()
        .should().beAnnotatedWith(RequiresPermission.class)
        .orShould().beAnnotatedWith(PublicEndpoint.class);
// 漏一个端点 → mvn verify 失败
```

配套四件套：
1. **注解义务化** `@RequiresPermission(resource, action)` 或 `@PublicEndpoint(reason="...")`
2. **启动时校验** ArchUnit 扫全部 Controller，漏注解即编译失败
3. **审计不可关闭** 所有 Deny/高敏 Allow 决策写 `t_access_audit`（分区表 + 归档）
4. **PR 模板钩子** "本 PR 新增/修改了 Controller？请列出权限决策"

### 5.3 面向用户的权限叙事（产品化）

工程师画权限矩阵，用户只看**三件事**：

1. **我能做什么**（Capability View）— 进系统第一屏展示"你有 3 个知识库、2 种权限"
2. **为什么看不到这个**（Explainability）— 点任何被禁用的按钮都告诉"需要 X 权限，找谁申请"
3. **谁看过我的东西**（Transparency）— 文档上传者能看访问记录（Google Docs 模式）

目前项目的 `security_level 0-3` 是典型反例——**用户不知道自己几级、管理员不知道给文档打几级**。必须做一层语义转译：

| 工程级 | 业务语义 | 用户叙事 |
|---|---|---|
| 0 | PUBLIC | 对所有员工开放 |
| 1 | INTERNAL | 本部门 + 授权员工 |
| 2 | CONFIDENTIAL | 授权人群 + 审批 |
| 3 | RESTRICTED | 白名单 + 审计 |

上传文档时**不让用户选 0-3**，让他选"谁能看这份文档"——系统反推等级。

### 5.4 AI 特有的权限扩展（其他系统没有的）

| 扩展 | 机制 | 预期收益 |
|---|---|---|
| Query Firewall | LLM 预检 query 意图，拦截探测/越狱/PII 查询 | 防内部滥用、攻击日志 |
| Retrieval 配额 | 分钟/日/月粒度 chunk 读取限速 | 防内部爬库 |
| 拼合敏感性重判 | 检索到的 chunks 集合送安全分类器再评级 | 防 derivative confidentiality 泄漏 |
| Tool 分级 | MCP 工具按角色白名单 | 外部工具调用可审计 |
| Trace 脱敏 | trace 落盘时自动 PII mask，观测角色分级读 | 可观测性与隐私平衡 |
| Export 审批 | 批量导出需二次确认 + 审计告警 | 防内鬼批量拷贝 |

---

## 六、分阶段迭代路线（产品优先级视角）

### Phase 0 · 止血（2-3 周，P0）

**目标：把 🔥 高危漏洞补平，避免"有权限的错觉"**

1. `/rag/settings` 拆两档（API Key 单独 SUPER_ONLY）
2. `/ingestion/*` 类级 `@SaCheckRole("SUPER_ADMIN")` + `HttpUrlFetcher` 私网黑名单
3. `/rag/traces/*`、`/rag/evaluations/*` 加 Any-Admin + userId/kbId 过滤
4. `/intent-tree/*`、`/mappings/*`、`/sample-questions/*`（非公开读）类级 SUPER_ADMIN
5. 修 `SL-1`（Milvus/Pg 实装 metadataFilters）——**这条在止血阶段不做，后端切换就是灾难**

**验收**：审计基线矩阵🔴行=0，非白名单端点全部有显式注解

### Phase 1 · 制度化（4-6 周，P0）

**目标：让"漏端点"在编译期失败**

1. 定义 `@RequiresPermission(resource, action)` + `@PublicEndpoint(reason)` 注解
2. ArchUnit 规则加入 `mvn verify` 门禁
3. 建 `t_access_audit` 审计表 + AOP 切面（所有 Deny + 高敏 Allow）
4. 补 MockMvc + Sa-Token 测试框架（PR1 已识别但未做）
5. `KbAccessService` 拆分：`KbReadAuthService` / `KbWriteAuthService` / `DocSecurityAuthService` 三个

**验收**：新加 Controller 不写注解 → 构建失败；`t_access_audit` 能查到"谁在什么时间被拒绝做什么"

### Phase 2 · 模型升级（6-10 周，P1）

**目标：RBAC 单维度 → RBAC+ABAC 组合**

1. 接入策略引擎（OPA/Cedar）
2. 把 `KbAccessServiceImpl` 里的决策逻辑迁移到策略文件
3. Context 维度引入：时间窗口、IP 段、设备指纹
4. 部门模型扩展为树形（支持集团→事业部→子公司三层）
5. "临时授权"能力（TTL 权限，过期自动回收）

**验收**：权限变更**不需要改 Java 代码**，只改策略文件；策略变更有版本/审批/回滚

### Phase 3 · AI 特有扩展（10+ 周，P2）

**目标：建设 AI 系统特有的权限维度**

1. Query Firewall（LLM 预检）
2. Retrieval 配额（Redis 计数器）
3. 检索拼合敏感性重判（derivative confidentiality）
4. Tool 分级 + MCP 工具白名单
5. 前端"能看不到解释"（Explainability UI）+ 文档访问记录（Transparency UI）

**验收**：能回答"这条答案为什么给这个用户"全链路问题

### Phase 4 · 多租户（按需启动）

**目标：当有第一个需要"数据独立"的客户时启动**

数据分层（Schema per tenant / Row-level `tenant_id`）、密钥分层（BYOK）、计量/配额、租户管理后台。不急做，但模型设计时要预留（比如现在的 `userId/deptId` 就要能扩展成 `tenantId:userId`）。

---

## 七、总结：健壮权限体系的五条准则

1. **默认拒绝可编译**——制度必须能在构建流水线里强制，不靠人肉 review
2. **策略与代码分离**——策略用 DSL，可版本化/可审批/可回滚，降低变更门槛
3. **审计不可关闭**——任何访问决策（尤其 Deny）必须留痕，审计表分区 + 归档
4. **用户视角优先**——3 层语义：我能做什么 / 为什么不能 / 谁看过我
5. **AI 系统要有 AI 维度**——Query/Retrieval/Tool/Export/Trace 各自独立授权

当前项目有**很好的"组件"**（`KbAccessService`、`AuthzPostProcessor`、前端 `permissions.ts`），但**缺"制度"**（默认拒绝、审计、策略引擎）和**缺"AI 视角"**（query 授权、配额、derivative confidentiality）。重构不是把 RBAC 做得更全，而是**从三层策略 + 制度化 + AI 扩展三个方向同时升级**。

---

## 八、显式非目标（避免 scope 膨胀）

- ❌ 不自己造策略引擎（用 OPA/Cedar 现成方案）
- ❌ 不做多租户 SaaS 化（除非有明确客户）
- ❌ 不做细到字段级 ACL（ROI 低，KB 级 + Doc SL 够用）
- ❌ 不做人脸/MFA（除非合规要求）
