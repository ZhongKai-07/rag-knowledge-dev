# 图 1 · 权限模型（ER 图 + 授权链）

本图配合《详细设计文档 - 权限管理设计》章节第 2、3 节使用。
- **上半部（ER）**：权限相关数据表结构与关联关系。
- **下半部（授权链）**：身份链（你是谁）与资源链（看什么）的业务视图，以及两者如何通过角色-KB 绑定汇合。

---

## (a) 数据模型 ER 图

```mermaid
erDiagram
    SYS_DEPT          ||--o{ T_USER              : "拥有成员"
    SYS_DEPT          ||--o{ T_KNOWLEDGE_BASE    : "拥有 KB"
    T_USER            ||--o{ T_USER_ROLE         : "被赋予"
    T_ROLE            ||--o{ T_USER_ROLE         : "被分配给"
    T_ROLE            ||--o{ T_ROLE_KB_RELATION  : "共享 KB"
    T_KNOWLEDGE_BASE  ||--o{ T_ROLE_KB_RELATION  : "被共享给"
    T_KNOWLEDGE_BASE  ||--o{ T_KNOWLEDGE_DOCUMENT: "包含文档"

    SYS_DEPT {
        bigint   id              PK
        string   dept_code       UK
        string   dept_name
    }

    T_USER {
        bigint   id              PK
        string   username        UK
        string   password
        bigint   dept_id         FK "所属部门"
    }

    T_ROLE {
        bigint   id                  PK
        string   name
        string   role_type           "SUPER_ADMIN | DEPT_ADMIN | USER"
        smallint max_security_level  "0-3 角色天花板"
    }

    T_USER_ROLE {
        bigint   user_id         FK
        bigint   role_id         FK
    }

    T_ROLE_KB_RELATION {
        bigint   role_id             FK
        bigint   kb_id               FK
        string   permission          "READ | WRITE | MANAGE"
        smallint max_security_level  "该 KB 下的密级上限 0-3"
    }

    T_KNOWLEDGE_BASE {
        bigint   id              PK
        string   name
        bigint   dept_id         FK "归属部门 -> DEPT_ADMIN 隐式 MANAGE"
    }

    T_KNOWLEDGE_DOCUMENT {
        bigint   id              PK
        bigint   kb_id           FK
        smallint security_level  "0-3 文档密级, 同步进 chunk metadata"
    }
```

---

## (b) 授权链：身份链 × 资源链

```mermaid
flowchart LR
    %% ============ 身份链 ============
    subgraph ID_CHAIN["① 身份链 —— 你是谁"]
        direction TB
        D1["部门 Dept<br/>sys_dept"]
        U["用户 User<br/>t_user"]
        R["角色 Role<br/>t_role<br/>role_type / max_security_level"]
        D1 -- "归属 dept_id" --> U
        U  -- "t_user_role" --> R
    end

    %% ============ 资源链 ============
    subgraph RES_CHAIN["② 资源链 —— 看什么"]
        direction TB
        D2["部门 Dept<br/>sys_dept"]
        KB["知识库 KB<br/>t_knowledge_base<br/>dept_id"]
        DOC["文档 Document<br/>t_knowledge_document<br/>security_level 0-3"]
        D2 -- "归属 dept_id" --> KB
        KB -- "包含" --> DOC
    end

    %% ============ 两链汇合 ============
    R  ==>|"t_role_kb_relation<br/>permission: READ/WRITE/MANAGE<br/>max_security_level (KB 级上限)"| KB

    %% ============ 隐式规则 ============
    U  -. "DEPT_ADMIN + user.dept_id == kb.dept_id<br/>→ 隐式 MANAGE (无需绑定)" .-> KB
    U  -. "SUPER_ADMIN → 全部 KB + 密级恒为 3" .-> KB

    %% ============ 三把锁 ============
    LOCK1["锁 1 · 登录<br/>Sa-Token"]:::lock
    LOCK2["锁 2 · KB 权限<br/>READ / WRITE / MANAGE"]:::lock
    LOCK3["锁 3 · 密级过滤<br/>用户上限 ≥ 文档密级"]:::lock
    U    -.-> LOCK1
    R    -.-> LOCK2
    DOC  -.-> LOCK3

    classDef lock fill:#fff2cc,stroke:#d6b656,color:#333,stroke-width:1px,stroke-dasharray: 4 2
    style ID_CHAIN  fill:#e8f0fe,stroke:#6c8ebf
    style RES_CHAIN fill:#eaf5ea,stroke:#82b366
```

---

## 关键要点（配合图阅读）

1. **ER 图的核心是两张关联表**：
   - `t_user_role` 承载 "用户—角色" N:N
   - `t_role_kb_relation` 承载 "角色—KB" N:N，并附带 `permission` 和 KB 级 `max_security_level`
2. **`dept_id` 出现在两处**（`t_user.dept_id` 与 `t_knowledge_base.dept_id`），两者相等即触发 DEPT_ADMIN 的**隐式 MANAGE**（授权链图中的虚线）。
3. **授权最终落点是文档**：用户能否读到一篇文档，必须同时通过"身份链 → 锁 2 → 资源链 → 锁 3"四段路径。
4. **密级有两层天花板**：角色本身的 `max_security_level`（粗）+ 绑定到具体 KB 时的 `max_security_level`（细）。实际生效取 MAX 跨角色聚合。
