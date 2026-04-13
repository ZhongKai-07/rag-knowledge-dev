# 企业级 RAG 知识库问答系统 — 权限管理体系设计文档

## 一、设计目标

为 PWM 领域的 RAG 知识库问答系统建立基于 RBAC 的权限管理体系，满足以下核心需求：

1. **知识库级访问控制** — 用户只能访问被授权的知识库
2. **文档级可见性控制** — 同一知识库内，不同职级看到不同敏感度的文档
3. **部门自治管理** — 业务部门可自主管理本部门知识库，无需依赖超级管理员
4. **跨部门共享** — 知识库的归属（谁管理）与访问（谁使用）解耦

---

## 二、角色体系

系统定义三种角色类型，通过 `role_type` 字段区分：

| 角色类型 | 标识 | 知识库操作 | 文档操作 | 用户管理 | 约束条件 |
|---------|------|-----------|---------|---------|---------|
| 超级管理员 | `SUPER_ADMIN` | 全局 CRUD | 全局 CRUD | 全局分配角色与权限 | 无限制 |
| 部门管理员 | `DEPT_ADMIN` | 本部门知识库 CRUD | 本部门文档 CRUD | 本部门内分配知识库访问权限 | `dept_id` 隔离 |
| 普通用户 | `USER` | 无 | 无 | 无 | 仅查询被授权的知识库 |

**关键设计决策：**

- 一个用户可挂载多个角色（如既是 USER 也是某部门的 DEPT_ADMIN）
- SUPER_ADMIN 可创建任意知识库并指定归属部门
- DEPT_ADMIN 只能创建 `dept_id = 自己部门` 的知识库

---

## 三、两层权限过滤机制

### 第一层：知识库级（横向 — RBAC）

通过 `用户 → 角色 → 知识库` 的关联链，控制用户**能访问哪些知识库**。

```
用户A ──▶ 角色X ──▶ 知识库1 (READ)
              ├──▶ 知识库2 (READ)
用户A ──▶ 角色Y ──▶ 知识库3 (READ)
```

角色与知识库的关联通过 `role_kb_rel` 表实现，并附带操作权限：

| 权限级别 | 标识 | 说明 |
|---------|------|------|
| 只读 | `READ` | 只能对知识库进行问答查询 |
| 读写 | `WRITE` | 可上传/更新文档（DEPT_ADMIN 默认） |
| 管理 | `MANAGE` | 可删除知识库、管理访问权限（SUPER_ADMIN 默认） |

### 第二层：文档级（纵向 — Security Level）

同一知识库内，文档按敏感度打标签，角色绑定可访问的最高安全等级：

| 等级 | 值 | 可见范围 | 典型内容 |
|-----|---|---------|---------|
| `PUBLIC` | 0 | 所有有该知识库访问权的用户 | 一般业务流程、公开政策 |
| `INTERNAL` | 1 | 部门内部员工 | 内部操作手册、SOP |
| `CONFIDENTIAL` | 2 | 管理层/特定角色 | 风控策略、合规敏感文件 |
| `RESTRICTED` | 3 | 仅指定人员 | 监管审计材料、高度机密文件 |

每个角色分配 `max_security_level`，检索时叠加过滤。

### 两层叠加效果

| | 无知识库权限 | 有知识库权限 + PUBLIC | 有知识库权限 + CONFIDENTIAL |
|---|---|---|---|
| 查询结果 | 拒绝访问 | 仅返回 PUBLIC/INTERNAL 文档切片 | 返回 PUBLIC ~ CONFIDENTIAL 文档切片 |

---

## 四、数据模型

### 4.1 核心权限表（6 张）

```
sys_user            用户表
├── user_id         PK
├── dept_id         FK → sys_dept
├── username        用户名
└── ...

sys_dept            部门表
├── dept_id         PK
├── dept_name       部门名称（OPS / FICC / EST / EQD ...）
└── ...

sys_role            角色表
├── role_id         PK
├── role_type       SUPER_ADMIN / DEPT_ADMIN / USER
├── role_name       角色名称（如 "FICC普通员工"、"OPS部门管理员"）
├── max_security_level  该角色可访问的最高安全等级（0-3）
└── ...

user_role_rel       用户-角色关联表
├── user_id         FK → sys_user
├── role_id         FK → sys_role
└── (联合主键)

role_kb_rel         角色-知识库关联表
├── role_id         FK → sys_role
├── kb_id           FK → knowledge_base
├── permission      READ / WRITE / MANAGE
└── (联合主键)

knowledge_base      知识库表
├── kb_id           PK
├── dept_id         FK → sys_dept（归属部门，决定谁能管理）
├── kb_name         知识库名称
├── description     描述
└── ...
```

### 4.2 业务数据表

```
kb_document         文档表
├── doc_id          PK
├── kb_id           FK → knowledge_base
├── security_level  文档安全等级（0-3）
├── file_name       文件名
├── file_path       S3 存储路径
└── ...

doc_chunk           文档切片表（对应 OpenSearch 索引记录）
├── chunk_id        PK
├── doc_id          FK → kb_document
├── kb_id           冗余字段，检索过滤用
├── security_level  冗余字段，检索过滤用
├── content         切片文本内容
└── embedding       向量
```

### 4.3 ER 关系总览

```
sys_user ──M:N──▶ sys_role ──M:N──▶ knowledge_base ──1:N──▶ kb_document ──1:N──▶ doc_chunk
    │                                      │
    └── FK dept_id ──▶ sys_dept ◀── FK dept_id ──┘
```

**关键设计：知识库归属（dept_id）与访问权限（role_kb_rel）解耦。**

- `knowledge_base.dept_id` 决定哪个 DEPT_ADMIN 能管理此知识库
- `role_kb_rel` 决定哪些用户能访问此知识库
- 两者独立，互不干扰

---

## 五、OpenSearch 索引设计

每个知识库对应一个 OpenSearch 索引，命名规则：`kb_{kb_id}`

### Mapping

```json
{
  "mappings": {
    "properties": {
      "chunk_id":        { "type": "keyword" },
      "doc_id":          { "type": "keyword" },
      "kb_id":           { "type": "keyword" },
      "security_level":  { "type": "integer" },
      "content":         { "type": "text", "analyzer": "ik_max_word" },
      "embedding": {
        "type": "knn_vector",
        "dimension": 1024,
        "method": {
          "name": "hnsw",
          "space_type": "cosinesimil",
          "engine": "nmslib"
        }
      }
    }
  }
}
```

### 检索时权限过滤

`security_level` 作为 pre-filter 注入，不影响 ANN 向量检索性能：

```json
{
  "query": {
    "bool": {
      "must": [
        {
          "knn": {
            "embedding": {
              "vector": [0.1, 0.2, ...],
              "k": 10
            }
          }
        }
      ],
      "filter": [
        { "terms": { "kb_id": ["kb_001", "kb_002"] } },
        { "range": { "security_level": { "lte": 2 } } }
      ]
    }
  }
}
```

---

## 六、核心业务流程

### 6.1 用户登录 → 获取知识库列表 → 问答

```
1. 用户登录
   └─▶ 后端签发 JWT（携带 user_id, dept_id, role_type 等 claims）

2. 前端请求 GET /api/knowledge-bases
   └─▶ 后端聚合查询：
       SELECT DISTINCT kb.kb_id, kb.kb_name, kb.description, rkr.permission
       FROM user_role_rel urr
       JOIN role_kb_rel rkr ON urr.role_id = rkr.role_id
       JOIN knowledge_base kb ON rkr.kb_id = kb.kb_id
       WHERE urr.user_id = :currentUserId
   └─▶ 返回知识库卡片列表（含 permission 标识）

3. 用户点击知识库卡片，进入问答页面

4. 用户提问 POST /api/chat { kb_id, question }
   └─▶ 校验用户对该 kb_id 有 READ 权限
   └─▶ 获取用户 max_security_level（取所有角色的最大值）
   └─▶ OpenSearch 检索：kb_id filter + security_level <= max_level
   └─▶ 检索结果 chunks 送入 LLM 生成回答
   └─▶ 返回答案
```

### 6.2 管理员操作知识库

```
SUPER_ADMIN 操作任意知识库：
  └─▶ 无约束，直接放行

DEPT_ADMIN 操作知识库：
  └─▶ 校验 knowledge_base.dept_id == currentUser.dept_id
  └─▶ 匹配 → 放行
  └─▶ 不匹配 → 拒绝（AccessDeniedException）

USER 操作知识库：
  └─▶ 直接拒绝
```

### 6.3 知识库创建

```
SUPER_ADMIN：
  └─▶ 可创建任意知识库，dept_id 由请求参数指定

DEPT_ADMIN：
  └─▶ 可创建知识库，dept_id 强制设为自己所属部门

USER：
  └─▶ 无创建权限
```

---

## 七、前端页面划分

| 角色 | 入口页面 | 可见内容 |
|------|---------|---------|
| SUPER_ADMIN | 完整管理后台 | 所有用户管理、所有知识库 CRUD、角色与权限配置、全局设置 |
| DEPT_ADMIN | 部门管理页面 | 本部门知识库 CRUD、本部门文档管理、本部门用户的知识库访问权限分配 |
| USER | 问答首页 | 被授权的知识库卡片列表 → 点击卡片进入对应知识库的问答页 |

---

## 八、典型场景验证

### 场景 1：部门自建自管

> FICC 部门创建 FICC 产品知识库，FICC DEPT_ADMIN 自主管理文档和部门内用户访问权限。

- FICC DEPT_ADMIN 创建知识库 → `dept_id = FICC`
- FICC DEPT_ADMIN 上传文档、设置 security_level
- FICC DEPT_ADMIN 将 FICC 员工角色关联到该知识库（READ 权限）
- ✅ 全流程在部门内闭环，无需 SUPER_ADMIN 介入

### 场景 2：跨部门共享

> OPS 部门维护 COB 合规知识库，FICC / EST / EQD 部门员工都需要访问。

- OPS DEPT_ADMIN 创建 COB 知识库 → `dept_id = OPS`
- OPS DEPT_ADMIN 管理 COB 文档内容
- SUPER_ADMIN（或 OPS DEPT_ADMIN）将 FICC/EST/EQD 的员工角色关联到 COB 知识库（READ 权限）
- FICC/EST/EQD 的 DEPT_ADMIN 对 COB 知识库**无管理权限**（因为 `dept_id ≠ 自己部门`）
- ✅ 归属与访问解耦，管理边界清晰

### 场景 3：同库不同可见度

> COB 知识库中，部分 AML/KYC 高敏感文件仅管理层可见，一般合规文件全员可见。

- 一般合规文件 → `security_level = PUBLIC (0)`
- AML/KYC 敏感文件 → `security_level = CONFIDENTIAL (2)`
- 普通员工角色 `max_security_level = 1` → 只能检索到 PUBLIC + INTERNAL 的切片
- 管理层角色 `max_security_level = 2` → 可检索到 CONFIDENTIAL 及以下全部切片
- ✅ 无需逐文件配置 ACL，通过等级标签批量控制

### 场景 4：公司级公共知识库

> HR 政策、公司制度等全员可访问的知识库。

- SUPER_ADMIN 创建知识库 → `dept_id = GLOBAL`（特殊标识）
- 将全员通用角色关联到该知识库（READ 权限）
- ✅ 覆盖全公司访问场景

---

## 九、Spring Boot 实现要点

### 9.1 权限校验（AOP / Spring Security）

```java
// 知识库操作权限校验
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DEPT_ADMIN')")
public void updateKnowledgeBase(String kbId, KbUpdateDTO dto) {
    KnowledgeBase kb = kbRepository.findById(kbId);

    if (currentUser.isSuperAdmin()) {
        // 放行
    } else if (currentUser.isDeptAdmin()) {
        if (!kb.getDeptId().equals(currentUser.getDeptId())) {
            throw new AccessDeniedException("无权操作其他部门知识库");
        }
    }
    // proceed...
}
```

### 9.2 知识库列表查询

```java
public List<KnowledgeBaseVO> getAccessibleKnowledgeBases(String userId) {
    List<UserKbPermission> perms = kbMapper.findAccessibleKbs(userId);

    return perms.stream().map(p -> KnowledgeBaseVO.builder()
            .kbId(p.getKbId())
            .kbName(p.getKbName())
            .description(p.getDescription())
            .permission(p.getPermission())
            .docCount(p.getDocCount())
            .build())
        .collect(Collectors.toList());
}
```

### 9.3 问答权限校验 + 检索

```java
public ChatResponse chat(String userId, String kbId, String question) {
    // 1. 校验知识库访问权限
    UserKbPermission perm = permissionService.checkAccess(userId, kbId);
    if (perm == null) {
        throw new AccessDeniedException("无权访问该知识库");
    }

    // 2. 获取用户最高安全等级
    int maxLevel = permissionService.getMaxSecurityLevel(userId);

    // 3. 带权限过滤的向量检索
    List<Chunk> chunks = searchService.retrieve(kbId, question, maxLevel);

    // 4. LLM 生成回答
    return llmService.generate(question, chunks);
}
```

---

## 十、设计总结

| 维度 | 机制 | 作用 |
|------|------|------|
| 横向访问边界 | `role_kb_rel` (RBAC) | 控制用户能访问哪些知识库 |
| 纵向可见深度 | `security_level` (文档标签) | 控制用户在知识库内能看到哪些文档 |
| 管理边界 | `dept_id` (部门隔离) | 控制 DEPT_ADMIN 能管理哪些知识库 |
| 归属与访问解耦 | `dept_id` ≠ `role_kb_rel` | 知识库归 A 部门管理，B/C/D 部门可访问 |

三个机制正交组合，覆盖部门自治、跨部门共享、文档级权限、全局管控等全部场景，且未引入文件级 ACL 的维护复杂度。
