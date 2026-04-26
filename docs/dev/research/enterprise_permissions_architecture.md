你的共享逻辑可以作为主线继续推进：

```text
OPS Admin 创建 COB KB
  → COB KB.dept_id = OPS
  → 默认授权 OPS_USER READ
  → 共享时新增：
       FICC_USER → COB KB → READ
       EST_USER  → COB KB → READ
       EQD_USER  → COB KB → READ
```

这个模型不需要马上升级成 OpenFGA / OPA。当前阶段最务实的路线是：**基于 `role_kb_relation` 做好知识库级共享，再逐步补 security_level、管理边界、审计和申请流**。你已有计划里也识别了“缺少 KB sharing API”“retrieval security_level 泄漏”等权限问题，后续阶段应该围绕这些缺口推进。

---

# 0. 权限体系目标形态

最终权限模型可以先保持这几个核心对象：

```text
User
  └── UserRole
        └── Role
              └── RoleKbRelation
                    └── KnowledgeBase
                          └── Document
                                └── Chunk
```

其中：

```text
knowledge_base.dept_id
```

决定**谁能管理这个知识库**。

```text
role_kb_relation
```

决定**哪些角色能访问这个知识库**。

```text
document.security_level
```

决定**同一个知识库内哪些文档能被当前用户检索到**。

你的核心模型就是：

```text
归属权：KB.dept_id
使用权：role_kb_relation
内容安全等级：document.security_level / chunk.metadata.security_level
```

---

# 1. 角色体系先不要搞复杂

建议把角色分成两类理解，但可以继续存在同一张 `t_role` 表里。

## 1.1 平台角色

控制系统操作边界：

```text
SUPER_ADMIN
DEPT_ADMIN
USER
```

| 平台角色        | 能力        |
| ----------- | --------- |
| SUPER_ADMIN | 管全局       |
| DEPT_ADMIN  | 管自己部门的知识库 |
| USER        | 普通使用者     |

## 1.2 业务访问角色

控制知识库访问范围：

```text
OPS_USER
FICC_USER
EST_USER
EQD_USER
OPS_COB_ADMIN
FICC_COB_USER
```

你的共享逻辑应该是授权给这些“带组织语义的角色”，而不是授权给一个全局 `USER`。

正确：

```text
COB KB → FICC_USER
COB KB → EST_USER
COB KB → EQD_USER
```

危险：

```text
COB KB → USER
```

因为这可能等于全公司普通用户都能访问。

---

# 2. 阶段化开发路线

## Phase 0：先堵住明显越权点

目标：确保非管理员不能随便创建、删除、修改知识库或 chunk。

这一阶段主要做热修复：

```text
1. 知识库创建 / 删除 / 重命名接口加权限校验
2. 文档管理接口加权限校验
3. chunk 管理接口加权限校验
4. accessibleKbIds 为空时必须返回空结果，不能 fail-open
```

特别注意这个问题：

```java
.in(condition, field, values)
```

如果 `condition = false`，MyBatis-Plus 可能直接不拼 `.in()` 条件，导致本来没有权限的用户查到所有数据。所以要显式处理：

```java
if (accessibleKbIds.isEmpty()) {
    return emptyResult;
}
```

这一阶段不做复杂模型，只是防止明显漏洞。

---

## Phase 1：建立最小 RBAC + 知识库共享模型

目标：让“OPS 共享 COB 给 FICC / EST / EQD”正式落库。

核心表：

```text
t_user
t_role
t_user_role
t_knowledge_base
t_role_kb_relation
```

建议 `t_knowledge_base` 至少有：

```sql
id
name
dept_id
collection_name
description
status
created_by
create_time
```

`dept_id` 表示知识库归属部门。

例如：

```text
COB KB:
  id = ops_cob_kb
  dept_id = OPS
  name = COB Knowledge Base
```

`t_role_kb_relation` 至少有：

```sql
id
role_id
kb_id
permission
max_security_level
created_by
create_time
```

其中 `permission` 先支持：

```text
READ
WRITE
MANAGE
```

含义：

| permission | 含义           |
| ---------- | ------------ |
| READ       | 可以问答、检索、查看引用 |
| WRITE      | 可以上传 / 更新文档  |
| MANAGE     | 可以管理知识库配置和共享 |

你的共享数据应该长这样：

```text
role_id       kb_id         permission
OPS_USER      ops_cob_kb    READ
FICC_USER     ops_cob_kb    READ
EST_USER      ops_cob_kb    READ
EQD_USER      ops_cob_kb    READ
OPS_COB_ADMIN ops_cob_kb    MANAGE
```

这一阶段要实现的后端能力：

```java
Set<String> getAccessibleKbIds(String userId, Permission minPermission);

void checkAccess(String kbId, Permission minPermission);

void checkManageAccess(String kbId);
```

核心判断：

```text
用户 → 用户角色 → role_kb_relation → 可访问 KB
```

---

## Phase 2：把共享能力产品化

目标：OPS Admin 可以在 COB 知识库详情页里直接管理共享对象。

新增接口：

```http
GET /knowledge-base/{kbId}/role-bindings
PUT /knowledge-base/{kbId}/role-bindings
```

例如：

```json
[
  {
    "roleId": "OPS_USER",
    "permission": "READ",
    "maxSecurityLevel": 1
  },
  {
    "roleId": "FICC_USER",
    "permission": "READ",
    "maxSecurityLevel": 0
  },
  {
    "roleId": "EST_USER",
    "permission": "READ",
    "maxSecurityLevel": 0
  }
]
```

前端做一个 `Sharing / 权限共享` tab：

```text
COB Knowledge Base
  ├── Overview
  ├── Documents
  ├── Chunks
  ├── Sharing
  └── Audit
```

`Sharing` 页面里允许 OPS Admin：

```text
1. 查看当前被授权角色
2. 添加 FICC_USER / EST_USER / EQD_USER
3. 设置 READ / WRITE / MANAGE
4. 设置 max_security_level
5. 移除共享角色
```

这一阶段仍然不要做访问申请流，先让管理员能手工共享。

---

## Phase 3：明确 DEPT_ADMIN 管理边界

目标：OPS Admin 只能管理 OPS 自己的知识库，但可以把 OPS 的知识库共享给别人的角色。

核心规则：

```text
DEPT_ADMIN 能管理 KB 的条件：
  kb.dept_id == currentUser.dept_id
```

所以 OPS Admin 可以：

```text
✅ 创建 OPS / COB KB
✅ 上传 COB 文档
✅ 删除 COB 文档
✅ 把 COB KB 授权给 FICC_USER
✅ 把 COB KB 授权给 EST_USER
```

但不能：

```text
❌ 修改 FICC / Rates KB
❌ 删除 EST 知识库
❌ 管理 FICC 用户
❌ 创建 FICC_USER 角色
```

服务层建议这样写：

```java
public void checkManageAccess(String kbId) {
    LoginUser user = UserContext.get();

    if (user.hasRoleType(SUPER_ADMIN)) {
        return;
    }

    KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);

    if (user.hasRoleType(DEPT_ADMIN)
            && Objects.equals(kb.getDeptId(), user.getDeptId())) {
        return;
    }

    throw new AccessDeniedException("No permission to manage this knowledge base");
}
```

注意：**不要只靠前端隐藏按钮**。所有写接口都要在后端调用 `checkManageAccess(kbId)`。

---

## Phase 4：把权限压进 RAG 检索链路

这是最关键的一阶段。

目标：用户问答时，后端不能相信前端传来的 KB 范围。

错误方式：

```text
前端传 indexNames
后端直接查这些 index
```

正确方式：

```text
当前用户
  → 计算 allowedKbIds
  → 校验 request.kbIds 是否是 allowedKbIds 子集
  → 检索时加入 kb_id filter
  → 加入 security_level filter
  → 只把有权限的 chunk 送进 LLM
```

`RagRequest` 建议从：

```java
List<String> indexNames;
```

改成：

```java
List<String> kbIds;
String question;
String scopeMode; // SELECTED_KB / ALL_AUTHORIZED
```

检索上下文：

```java
public record RetrievalScope(
    Set<String> kbIds,
    Map<String, Integer> kbMaxSecurityLevelMap,
    Permission permission
) {}
```

查询流程：

```java
public RagResponse chat(RagRequest request) {
    LoginUser user = UserContext.get();

    Set<String> allowedKbIds =
        kbAccessService.getAccessibleKbIds(user.getUserId(), Permission.READ);

    Set<String> effectiveKbIds =
        scopeResolver.resolve(request.getKbIds(), allowedKbIds);

    if (effectiveKbIds.isEmpty()) {
        throw new AccessDeniedException("No accessible knowledge base");
    }

    RetrievalScope scope = retrievalScopeBuilder.build(user, effectiveKbIds);

    List<RetrievedChunk> chunks =
        retriever.retrieve(request.getQuestion(), scope);

    return answerGenerator.generate(request.getQuestion(), chunks);
}
```

OpenSearch metadata 至少要有：

```json
{
  "kb_id": "ops_cob_kb",
  "doc_id": "doc_001",
  "security_level": 1,
  "status": "published"
}
```

检索 filter：

```json
{
  "bool": {
    "filter": [
      { "terms": { "metadata.kb_id": ["ops_cob_kb"] } },
      { "range": { "metadata.security_level": { "lte": 1 } } },
      { "term": { "metadata.status": "published" } }
    ]
  }
}
```

不要先全局召回再过滤。这会带来权限泄漏风险。

---

## Phase 5：修正 security_level 的粒度

这里是你当前设计里很容易踩坑的地方。

不要只给用户一个全局 `maxSecurityLevel`。

因为同一个用户可能对不同 KB 有不同权限：

```text
FICC_USER 对 FICC 自己的 KB:
  max_security_level = 2

FICC_USER 对 OPS / COB KB:
  max_security_level = 0
```

如果你只用用户全局最大等级：

```text
FICC_USER maxSecurityLevel = 2
```

那他访问 OPS / COB 时可能越权看到 level 2 文档。

所以 `max_security_level` 更适合放在：

```text
t_role_kb_relation.max_security_level
```

即：

```text
某个角色对某个 KB 的最高安全等级
```

例如：

```text
role_id       kb_id        permission   max_security_level
OPS_USER      ops_cob_kb   READ         2
FICC_USER     ops_cob_kb   READ         0
EST_USER      ops_cob_kb   READ         0
OPS_COB_ADMIN ops_cob_kb   MANAGE       3
```

这样 FICC 用户虽然能访问 COB，但只能检索低敏文档。

这一点已经在你的现有修复计划里被识别出来：计划中提出给 `t_role_kb_relation` 增加 `max_security_level`，并用 per-KB resolver 替代全局 security level，避免跨 KB 的 security_level 泄漏。

---

## Phase 6：补审计日志

目标：权限体系可追溯。

先不做复杂审计平台，先落一张表：

```sql
audit_log
```

字段：

```text
id
actor_user_id
action
object_type
object_id
detail_json
ip
user_agent
create_time
```

至少记录这些动作：

```text
KB_CREATE
KB_UPDATE
KB_DELETE
KB_SHARE_ADD
KB_SHARE_REMOVE
KB_ROLE_BINDING_UPDATE
DOC_UPLOAD
DOC_DELETE
RAG_QUERY
SOURCE_OPEN
```

对于 RAG 查询，建议记录：

```text
user_id
question
selected_kb_ids
effective_kb_ids
retrieved_doc_ids
retrieved_chunk_ids
answer_id
```

审计日志不一定一开始做很漂亮的前端页面，但后端必须先记下来。

---

## Phase 7：访问申请流

目标：用户没权限时可以申请访问，而不是线下找人。

新增表：

```text
kb_access_request
```

字段：

```text
id
requester_user_id
requester_dept_id
kb_id
target_role_id
requested_permission
reason
status
approver_user_id
approved_at
rejected_reason
create_time
```

流程：

```text
FICC 用户申请 OPS / COB
  → OPS COB Admin 审批
  → 审批通过后，把用户加入 FICC_COB_USER 角色
     或者把 FICC_USER 授权给 COB KB
```

这里要注意一个产品选择：

## 方式 A：审批后给整个角色授权

```text
FICC_USER → COB KB
```

适合：

```text
整个 FICC 都应该能用 COB
```

## 方式 B：审批后把用户加入细分角色

```text
用户张三 → FICC_COB_USER
FICC_COB_USER → COB KB
```

适合：

```text
只有部分 FICC 用户能用 COB
```

企业里更推荐方式 B，因为最小授权原则更清晰。

---

## Phase 8：再考虑第三方权限框架

现在不要急着上 OpenFGA / OPA。

当前阶段：

```text
Sa-Token / Spring Security 类认证框架
+
数据库 role_kb_relation
+
服务层 KbAccessService
```

已经够用。

什么时候引入第三方框架？

| 情况                       | 建议           |
| ------------------------ | ------------ |
| 只是部门共享知识库                | 不需要          |
| 只是角色访问 KB                | 不需要          |
| 需要用户组、部门、岗位、临时授权、继承关系混合  | 可以考虑 OpenFGA |
| 需要复杂策略，如地域、数据等级、交易线、时间窗口 | 可以考虑 OPA     |
| 只想轻量表达 `sub, obj, act`   | 可以考虑 Casbin  |

但对你当前项目，最合理的是：

```text
现在：role_kb_relation
中期：role_kb_relation + access_request + audit_log
后期：如果关系复杂，再迁移到 kb_grant / OpenFGA
```

不要为了“企业级”而一开始引入过重授权系统。

---

# 3. 每个阶段的交付物

## 第一阶段交付

```text
1. t_knowledge_base.dept_id
2. t_role_kb_relation.permission
3. OPS_USER / FICC_USER / EST_USER / EQD_USER 角色
4. COB KB 默认绑定 OPS_USER
5. 后端 KbAccessService 能算出用户可访问 KB
6. 首页只展示用户可访问 KB
```

完成后，你已经有基本共享能力。

---

## 第二阶段交付

```text
1. 知识库详情页 Sharing Tab
2. GET /knowledge-base/{kbId}/role-bindings
3. PUT /knowledge-base/{kbId}/role-bindings
4. OPS Admin 可以把 COB KB 共享给 FICC_USER / EST_USER
5. 后端校验 OPS Admin 只能管理 dept_id = OPS 的 KB
```

完成后，OPS 可以自主共享自己的知识库。

---

## 第三阶段交付

```text
1. document.security_level
2. chunk.metadata.security_level
3. role_kb_relation.max_security_level
4. 检索时按 kb_id + security_level filter
5. 不同角色访问同一 KB 可见文档不同
```

完成后，RAG 查询链路具备真正权限隔离。

---

## 第四阶段交付

```text
1. audit_log
2. RAG_QUERY 审计
3. KB_SHARE_ADD / KB_SHARE_REMOVE 审计
4. 文档上传 / 删除审计
5. 管理端审计查看
```

完成后，具备企业治理能力。

---

## 第五阶段交付

```text
1. access_request
2. 用户申请访问 KB
3. KB Owner 审批
4. 授权到期
5. 权限定期复审
```

完成后，权限从“管理员手工配置”升级为“可运营流程”。

---

# 4. 推荐你现在的最小实现路径

不要一下子做完。按这个顺序最稳：

```text
Step 1：确认角色命名
  OPS_USER
  FICC_USER
  EST_USER
  EQD_USER
  OPS_COB_ADMIN

Step 2：确认 KB 归属
  COB KB.dept_id = OPS

Step 3：补 role_kb_relation.permission
  READ / WRITE / MANAGE

Step 4：实现 KbAccessService
  getAccessibleKbIds(userId, READ)
  checkAccess(kbId)
  checkManageAccess(kbId)

Step 5：改首页知识库列表
  只返回用户有权访问的 KB

Step 6：改 RAG 查询接口
  禁止前端直接传 indexNames
  后端计算 effectiveKbIds

Step 7：做 Sharing Tab
  OPS Admin 可以把 COB 授权给 FICC_USER / EST_USER

Step 8：补 security_level
  document + chunk metadata + OpenSearch filter

Step 9：补 audit_log

Step 10：再做 access_request
```

---

# 5. 这套模型的核心判断

你的共享逻辑是对的，但要把它做成一个清晰的产品规则：

> 知识库由创建部门拥有和管理；共享不是转移所有权，而是把该知识库的 READ / WRITE / MANAGE 权限授予其他部门的业务角色。用户能看到哪些知识库，由后端根据其角色和 `role_kb_relation` 计算。RAG 检索时必须以这个计算结果作为检索边界，并结合每个角色对该 KB 的 `max_security_level` 过滤文档内容。

这就是当前阶段最合适的权限管理体系。
