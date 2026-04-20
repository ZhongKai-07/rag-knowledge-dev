# Followup：多租户 / RBAC 下的意图识别树策略

**创建日期**：2026-04-17
**状态**：策略已定，待开发（开发阶段，不需要数据迁移顾虑）
**关联**：RBAC 体系（`KbAccessService` / `t_role_kb_relation`）、`DefaultIntentClassifier`、`IntentTreeCacheManager`

---

## 背景：当前设计与 RBAC 的错位

### 现状

- `t_intent_node.kb_id` 字段**存在**，但 `DefaultIntentClassifier.loadIntentTreeFromDB()` **不按 kbId 过滤**（[源码](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/DefaultIntentClassifier.java#L263)）
- Redis 缓存 key 为单一全局 `ragent:intent:tree`，所有 KB 共享
- `classifyTargets(question)` 签名不接 kbId，`RAGChatServiceImpl` 也不透传 `knowledgeBaseId` 到意图层
- 结果：任何用户在任何 KB 空间发问，LLM 都会看到**全量叶子节点清单**

### 和 RBAC 体系的错位

本项目的权限体系层级是：`User → Role → KB`（`t_role_kb_relation` 控制），每个 KB 归属某 `dept_id`，`KbAccessService.getAccessibleKbIds()` 是权限判断入口。

**意图层事实上绕过了这道边界**：
1. **信息泄漏**：`IntentGuidanceService.detectAmbiguity` 可能把跨 KB 的意图名字作为引导选项返给用户，暴露用户无权访问的 KB 的存在和主题结构
2. **审计污染**：`evalCollector.setIntents(...)` 和 trace 节点 `extra_data` 会写入跨 KB 意图，下游分析系统能看到超出用户权限的信息
3. **分类准确率下降**：叶子节点池大，Prompt 里信噪比变差
4. **Token 成本放大**：每次分类烧全量意图的描述 token
5. **管理权限错位**：DEPT_ADMIN 没有天然的"管本部门意图"的入口

## 核心策略：两层意图池 + 读写两条权限轴

### 一、两层意图池模型

```
┌─────────────────────────────────────────────────┐
│  Tier 1: Global Intent Pool                     │
│  kb_id IS NULL                                  │
│  - SYSTEM: 闲聊、自我介绍、道谢、兜底             │
│  - MCP (全局): 不依赖 KB 的通用工具                │
└─────────────────────────────────────────────────┘
         ⊕  (加载时合并)
┌─────────────────────────────────────────────────┐
│  Tier 2: Per-KB Intent Trees                    │
│  kb_id = <具体 KB>                              │
│  - KB: 本 KB 业务 topic                          │
│  - MCP (KB 绑定): 和本 KB 工作流耦合的工具         │
└─────────────────────────────────────────────────┘

effectivePool(user, currentKbId) = Global ∪ tree(currentKbId)
```

**原则**：意图树按"路由目的地"切分，不按"访问者"切分。
- KB 是路由目的地（collectionName）→ 业务意图按 KB 聚集
- SYSTEM 意图不路由到任何 KB → 全局池
- **部门不是路由目的地**，因此不引入 `dept_id` 作为意图归属维度

### 二、RBAC 对齐：读写两条独立权限轴

意图的 RBAC 不是一套新系统，只是 KB RBAC 的"附属条款"。

#### 读取轴（分类时 LLM 能看见哪些意图）

```
intentsVisibleAt(user, kbId) =
    { n | n.kb_id IS NULL }                                       ← 全局池，所有登录用户可见
  ∪ { n | n.kb_id = kbId AND kbAccessService.checkAccess(kbId) }  ← 本 KB 意图，跟 KB 读权走
```

**关键**：用户能进到 `kbId` 空间发问，意味着已通过 KB 访问检查；本 KB 意图对他自然可见。
**零新增权限判断**，意图可见性**自动**继承 KB 可见性。

#### 写入轴（管理端谁能改意图）

| 操作对象 | 能写 | 复用现有检查 |
|---------|------|-------------|
| Global 意图 | SUPER_ADMIN | `@SaCheckRole("SUPER_ADMIN")` |
| KB_X 的意图 | SUPER_ADMIN + KB_X 所属 dept 的 DEPT_ADMIN | `kbAccessService.checkManageAccess(kbId)` |

`checkManageAccess` 已实现（对 `kb.dept_id == self.dept_id` 的 DEPT_ADMIN 放行，跨部门需 SUPER_ADMIN）。直接复用，**不增加新的权限检查方法**。

#### 读权 ≠ 写权

- 外部用户通过 `t_role_kb_relation` 被共享 KB_X 的读权 → 可在 KB_X 空间发问、触发 KB_X 意图分类（**读**）
- 但**不能**编辑 KB_X 的意图树（**写**需要 ownership，即 `checkManageAccess`）

和 KB 本身的读写权限模型**完全同构**，不需要新设计。

### 三、四种身份的实际体验

| 身份 | 读意图池 | 意图树管理界面 | 写权限 |
|------|---------|---------------|--------|
| **USER** | Global + currentKb 的意图 | **不露出菜单** | 无 |
| **DEPT_ADMIN** | 同上（发问时） | 见全局（只读） + 本部门 KB 的树 | 本部门 KB 意图 |
| **SUPER_ADMIN** | 同上（发问时） | 全部 KB + 全局 | 全部 |
| **跨部门协作用户**（`t_role_kb_relation` 共享拿到外部 KB 读权） | 能触发外部 KB 意图（进该 KB 空间时） | 不见外部 KB 的管理面 | 无 |

### 四、共享 KB 场景（零同步成本）

假设风控部把 `KB_Collateral` 共享给运营部（通过 `t_role_kb_relation` 给运营部某角色）：

- 意图归属：`KB_Collateral` 的意图树 `kb_id = KB_Collateral.id`，归属不变
- 运营部用户进 `KB_Collateral` 空间发问 → 意图池 = Global + KB_Collateral 的树
- 风控部改 KB_Collateral 意图 → 运营部用户**立即生效**（数据是共享的）
- 风控部撤销共享 → 运营部用户不再访问 KB_Collateral，也不再触发它的意图

**这是"每 KB 一棵树"相对"每部门一棵树"的决定性优势**：共享 KB 时意图作为附属资产自动跟随，零配置漂移。

### 五、边界：`currentKbId == null` 时的退化策略

三种可能的场景：
- 用户在"所有空间"聚合页
- 刚登录还没选 KB
- 跨 KB 虚拟视图（如"部门全貌"）

加载策略：

```
if currentKbId == null:
    intentPool = Global ∪ union(tree(kb) for kb in accessibleKbIds)
else:
    intentPool = Global ∪ tree(currentKbId)
```

- 意图污染只限于**该用户已有权看的 KBs**，不跨 RBAC 边界
- 如果聚合池仍然很大（比如 SUPER_ADMIN），污染就是权限设计的结果，不是意图层的问题

**建议**：默认 UX **不提供** `currentKbId == null` 的发问场景。强制用户先选 KB。跨 KB 搜索需求走"虚拟 KB 组 + 多通道检索"（意图分类退化到粗粒度 "业务 vs 闲聊 vs 工具" 三分类），让**向量分数**决定 KB，不再让意图层做细粒度跨 KB 路由。

## 管理工作流与模板库

### 一、KB-First 原则

意图节点物理归属 KB（或 Global 池），**KB 必须先存在**意图树才有归宿。不引入 "Tree-First"（树独立存在后挂 KB）模型 —— 会导致：

- 孤儿树问题（没挂 KB 的树要不要保留？）
- 一棵树被多 KB 引用时 owner 归属模糊
- 删 KB 时树的处理语义不清

**唯一例外**：全局 SYSTEM / MCP 意图，不归属任何 KB，独立于 KB 生命周期。

### 二、SUPER_ADMIN vs DEPT_ADMIN 流程对照

**SUPER_ADMIN 负责**：

| 步骤 | 对象 | 说明 |
|------|------|------|
| 1. 系统初始化配全局 SYSTEM 意图 | `t_intent_node`, `kb_id=NULL, kind=SYSTEM` | 闲聊、自我介绍、道谢 |
| 2. 系统初始化配全局 MCP 意图 | `kb_id=NULL, kind=MCP` | 通用工具 |
| 3. 发布全局意图模板 | `t_intent_template`, `scope=GLOBAL` | 跨部门可复用的业务骨架 |
| 4. 按需给任意 KB 配/改业务意图（跨部门越权） | `kb_id=具体 KB` | `checkManageAccess` 全通过 |
| 5. 审计 / 清理所有模板 | `t_intent_template` 全量 | 治理 |

**DEPT_ADMIN 负责**（以运营部为例）：

| 步骤 | 对象 | 说明 |
|------|------|------|
| 1. 建运营部 KB | `t_knowledge_base, dept_id=运营部` | 已有权 |
| 2. 建本 KB 意图（三选一起步） | `t_intent_node, kb_id=该 KB` | [a] 从零 [b] 全局模板复制 [c] 本部门模板复制 |
| 3. 编辑本部门 KB 意图 | 同上 | `checkManageAccess` 在本部门 KB 通过 |
| 4. 发布部门意图模板 | `t_intent_template, scope=DEPT, scope_id=运营部` | 仅本部门 DEPT_ADMIN + SUPER_ADMIN 可见/可用 |
| 5. 不可触碰：全局模板、他部门模板、他部门 KB 意图 | — | 读写均拒 |

**USER**：不露出任何意图管理 UI；发问时自动触发 `effectivePool = Global ∪ tree(currentKbId)`（读权限隐式）。

### 三、模板库作用域模型（三层）

模板比意图本身**多一层"部门级"作用域**：

| 作用域 | 字段 | 发布者 | 可见于 | 可用于 |
|--------|------|--------|--------|--------|
| 全局模板 | `scope=GLOBAL, scope_id=NULL` | SUPER_ADMIN | 所有 admin | 新建/更新任意 KB 意图时 |
| 部门模板 | `scope=DEPT, scope_id=X` | 部门 X 的 DEPT_ADMIN / SUPER_ADMIN | 部门 X 的 DEPT_ADMIN + SUPER_ADMIN | 新建/更新部门 X 的 KB 意图时 |

**为什么模板作用域是"部门"而不是 KB**：模板的意义是**跨 KB 复用**。绑到 KB 上等于"克隆另一个 KB 的意图" —— 不需要"模板"概念。部门是 DEPT_ADMIN 自治的最大范围，正好是模板的天然复用边界。

### 四、模板数据模型（新表，独立于 t_intent_node）

**不要**复用 `t_intent_node` 加 `is_template` flag —— 会污染所有业务查询（到处都要 `WHERE is_template = 0`）。独立建表最干净：

```
t_intent_template
├── id              VARCHAR(20)   主键
├── name            VARCHAR(128)  模板名
├── description     TEXT          模板说明
├── scope_type      SMALLINT      0=GLOBAL, 1=DEPT
├── scope_id        VARCHAR(20)   scope_type=DEPT 时填 dept_id，否则 NULL
├── content_json    TEXT          整棵树的 JSON 序列化（节点列表 + 层级关系）
├── enabled         SMALLINT
├── create_by / update_by / create_time / update_time / deleted
```

### 五、apply 操作：克隆而非引用

"从模板创建意图树"的语义是**一次性克隆**。克隆后模板与 KB 意图**完全脱耦**：
- 模板后续改动**不回流**到已生成的 KB 树
- KB 意图后续改动**不回写**模板

```
输入: template_id, target_kb_id

Step 1 权限校验:
  - 当前用户对 target_kb_id 有 checkManageAccess（防越权克隆到无权 KB）
  - 当前用户对 template 有读权（GLOBAL 可见 / 或本部门）
Step 2 读 template.content_json
Step 3 遍历 JSON 节点:
  - 生成新 id（雪花）
  - 设置 kb_id = target_kb_id
  - 重建 parent_id 关系
Step 4 批量 INSERT t_intent_node
Step 5 清 Redis ragent:intent:tree:kb:{target_kb_id}
```

### 六、模板权限检查（复用 KbAccessService，零新增维度）

扩展两个方法，语义上遵守**"写模板权限 ≤ 写该 scope 下实际意图的权限"**：

```java
boolean canReadTemplate(template) {
    return template.scope_type == GLOBAL
        || (template.scope_type == DEPT && userDeptId == template.scope_id)
        || isSuperAdmin();
}

boolean canWriteTemplate(template) {
    return (template.scope_type == GLOBAL && isSuperAdmin())
        || (template.scope_type == DEPT && userDeptId == template.scope_id && isDeptAdmin())
        || isSuperAdmin();
}
```

对应关系：
- DEPT_ADMIN 能改本部门 KB 意图 → 能发布本部门模板
- SUPER_ADMIN 能改全局意图 → 能发布全局模板

### 七、跨部门共享模板：不支持

DEPT_ADMIN 把本部门模板共享给其他部门：**禁止**。需要时的替代方案：

- SUPER_ADMIN 将其"升级"为全局模板
- 或各部门维护自己的副本

**理由**：跨部门共享会让 `scope_id` 变多值，权限判断瞬间复杂。和"共享 KB（read-only 共享 + 写权仍在 owner 部门）"不同，跨部门共享模板的编辑归属语义不清晰（谁改？谁审批？），直接禁以保持模型简单。

### 八、管理后台 UI 结构

```
系统管理（仅 SUPER_ADMIN）
├── 全局 SYSTEM 意图          ← 独立 CRUD
├── 全局 MCP 意图             ← 独立 CRUD
└── 全局意图模板库            ← SUPER_ADMIN 发布全局模板

部门管理（DEPT_ADMIN + SUPER_ADMIN）
└── 本部门意图模板库          ← DEPT_ADMIN 发布本部门模板
    （按 scope_id 过滤，DEPT_ADMIN 只见自己部门，SUPER 见全部）

知识库管理
└── KB 详情页
    ├── 基本信息 / 文档 / 角色绑定
    └── 意图树 tab            ← 本 KB 业务意图 CRUD
        └── "从模板创建" 按钮
              可选模板 = 全局模板 + 本部门模板
              （SUPER_ADMIN 可见全部模板）
```

## 策略总表

**意图层**：

| 意图类型 | `kb_id` | `kind` | 读权 | 写权 |
|---------|---------|--------|------|------|
| 全局 SYSTEM | NULL | SYSTEM | 所有登录用户 | SUPER_ADMIN |
| 全局 MCP | NULL | MCP | 所有登录用户 | SUPER_ADMIN |
| KB 业务意图 | 具体 KB | KB | `checkAccess(kbId)` 通过 | `checkManageAccess(kbId)` 通过 |
| KB 绑定 MCP | 具体 KB | MCP | 同上 | 同上 |

**模板层**：

| 模板类型 | `scope_type` | `scope_id` | 读权 | 写权 |
|---------|-------------|-----------|------|------|
| 全局模板 | GLOBAL | NULL | 所有 admin（SUPER + 任意 DEPT） | SUPER_ADMIN |
| 部门模板 | DEPT | 具体 `dept_id` | 该部门 DEPT_ADMIN + SUPER_ADMIN | 该部门 DEPT_ADMIN + SUPER_ADMIN |

## 为什么这个策略是干净的

1. **单一权限源**：所有意图访问判断最终走到 `KbAccessService`，不引入 `IntentAccessService`
2. **读跟 KB，写跟 ownership**：多租户系统的标准双轴模型，不是为意图树特别设计的
3. **Global 池是逃生舱**：SYSTEM / 通用 MCP 天然没有归属，放 Global 最符合直觉；不参与 RBAC，所有人可见
4. **共享 KB 零维护**：意图物理绑定 KB，`t_role_kb_relation` 改动自动生效

## 落地涉及的模块（开发阶段，不考虑数据迁移）

**意图层改造**：

| 层 | 文件 | 改动点 |
|----|------|--------|
| DB | `schema_pg.sql` / `full_schema_pg.sql` | 加 `idx_intent_node_kb_kind` 索引；`kb_id` 字段已存在 |
| 加载层 | `DefaultIntentClassifier.loadIntentTreeData(kbId)` | 新签名，DB 查询 `WHERE kb_id IS NULL OR kb_id = :currentKb` |
| 接口 | `IntentClassifier.classifyTargets(q, kbId)` | 加 kbId 形参 |
| 缓存 | `IntentTreeCacheManager` | key 拆为 `:global` + `:kb:{kbId}`，失效面集中处理（节点在两池间迁移时要清多个 key） |
| 路由 | `IntentResolver.resolve(rewriteResult, kbId)` | 透传 |
| 入口 | `RAGChatServiceImpl.streamChat` | 把已有 `knowledgeBaseId` 传给 resolve |
| 引导 | `IntentGuidanceService` | 无需特殊处理：接收的已经是过滤后的 `nodeScores`，天然不跨 KB |
| 管理接口 | `IntentTreeController` | 创建/更新/查询加 kbId，写接口接入 `checkManageAccess` |
| 前端 | `IntentTreePage` / `IntentListPage` / `IntentEditPage` | 拆为"全局意图（仅 SUPER_ADMIN）"+"KB 意图树 tab（内嵌 KB 详情页）" |
| MCP 意图 | `IntentTreeFactory` seed | 决策：默认 global，特殊工具可 KB 绑定 |

**模板层新增**：

| 层 | 文件 | 改动点 |
|----|------|--------|
| DB | `schema_pg.sql` / `full_schema_pg.sql` | 新建 `t_intent_template` 表（结构见上文） |
| Entity | `IntentTemplateDO` + `IntentTemplateMapper` | MyBatis Plus 标配 |
| Service | `IntentTemplateService` | CRUD + `apply(templateId, targetKbId)` 克隆逻辑 |
| Controller | `IntentTemplateController` | `GET/POST/PUT/DELETE /intent-template`, `POST /intent-template/{id}/apply` |
| 权限 | `KbAccessService` | 新增 `canReadTemplate(t)` / `canWriteTemplate(t)`（静态语义，零新增依赖） |
| 前端 | 系统管理下 "全局意图模板库"、部门管理下 "本部门意图模板库"、KB 详情页意图 tab "从模板创建" 按钮 | 新增三处 UI |

## 开发时的两个关键注意点

### 1. 缓存失效面的覆盖

节点在 `kb_id` 维度上迁移时（比如从"全局"改为"KB 绑定"或反之），要清**多个**缓存 key：

```
迁移前 key → 清
迁移后 key → 清
保险起见 :global → 清（不确定是否涉及时）
```

建议抽 `IntentCacheEvictor.evict(oldState, newState)` 集中判断，别散在 CRUD 里。

### 2. MCP 意图的归属策略

开发阶段先定一个默认：
- **默认 Global**（`kb_id IS NULL`）：适合"天气查询"、"员工目录"等通用工具
- **可选 KB 绑定**（`kb_id = X`）：适合和某 KB 工作流强耦合的工具，如"OPS-COB 工单查询"

管理界面要让创建者显式选择，默认 Global。

## 不在本次范围

- **数据库数据迁移**（开发阶段，不涉及）
- **向量检索层的 `accessibleKbIds` 过滤**（现有 `MultiChannelRetrievalEngine` 已实现）
- **"跨 KB 虚拟视图" 的详细设计**（另一个大话题，等实际需求出现再说）
- **意图分类失败降级语义**（`diagnostic_log.md` P1 #3，独立议题）

## 一句话策略

> **意图的 RBAC 不是一套新系统，只是 KB RBAC 的"附属条款"**：读跟随 KB 读权、写跟随 KB 管理权、全局池作为逃生舱不走 RBAC。**KB-First** 原则保证意图树物理归属 KB；**模板库**提供"跨 KB 复用"UX 便利，作用域分**全局（SUPER_ADMIN 发布）**和**部门（DEPT_ADMIN 发布）**两层，应用时**克隆而非引用**，发布权限严格对齐"能改该 scope 实际意图"的范围。现有的 `KbAccessService` 已经提供全部判断能力，无需引入任何新的权限概念。
