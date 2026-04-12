-- PR3 curl-matrix fixture
-- 用途：为 docs/dev/pr3-curl-matrix.http 提供稳定的业务键和固定账号/角色/KB。
-- 使用时机：仅在跑 curl 矩阵前加载；UI walkthrough 不应依赖此文件（walkthrough 要证明 UI CRUD 闭环）。
--
-- 幂等：所有 INSERT 使用 ON CONFLICT DO UPDATE，可安全重复执行。
-- 依赖：sys_dept 表里已存在 GLOBAL 种子（id='1'），t_user 里已存在 admin 种子（id='1'），
--       由 init_data_pg.sql 提供。

BEGIN;

-- 1. 部门（固定 id）
INSERT INTO sys_dept (id, dept_code, dept_name) VALUES
    ('2', 'RND', '研发部'),
    ('3', 'LEGAL', '法务部')
ON CONFLICT (id) DO UPDATE SET dept_code = EXCLUDED.dept_code, dept_name = EXCLUDED.dept_name;

-- 2. 用户（密码明文 123456，沿用 seed admin 的 plain-text 约定）
--    注意：t_user.role 列仍存在（Sa-Token 兼容层保留，PR3 不 drop）；业务授权走 t_user_role
INSERT INTO t_user (id, username, password, role, dept_id, avatar) VALUES
    ('10', 'alice', '123456', 'user', '2', ''),
    ('11', 'bob',   '123456', 'user', '3', ''),
    ('12', 'carol', '123456', 'user', '2', '')
ON CONFLICT (id) DO UPDATE SET username = EXCLUDED.username, password = EXCLUDED.password,
    role = EXCLUDED.role, dept_id = EXCLUDED.dept_id, avatar = EXCLUDED.avatar;

-- 3. 角色（role_type + max_security_level，PR1 已加列）
INSERT INTO t_role (id, name, description, role_type, max_security_level) VALUES
    ('100', '研发部管理员', '管理研发部的 KB 和用户', 'DEPT_ADMIN', 3),
    ('101', '法务部管理员', '管理法务部的 KB 和用户', 'DEPT_ADMIN', 3),
    ('102', '普通研发员',   '只读访问研发 KB',       'USER',       0),
    ('103', '普通法务员',   '只读访问法务 KB',       'USER',       0)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
    role_type = EXCLUDED.role_type, max_security_level = EXCLUDED.max_security_level;

-- 4. 用户-角色关联（schema_pg.sql 要求 t_user_role.id 显式 PK，必须提供）
INSERT INTO t_user_role (id, user_id, role_id) VALUES
    ('1001', '10', '100'),   -- alice = 研发部管理员
    ('1002', '11', '101'),   -- bob   = 法务部管理员
    ('1003', '12', '102')    -- carol = 普通研发员 (max=0)
ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, role_id = EXCLUDED.role_id;

-- 5. 知识库（schema_pg.sql 实际列：id / name / embedding_model / collection_name / created_by / updated_by / dept_id）
--    没有 description 列！embedding_model 和 created_by 是 NOT NULL 无默认，必须提供。
--    embedding_model 值跟随 application.yaml 的当前配置 —— 'text-embedding-v1' 是 bailian 默认，
--    若 RAG 运行时配置不同需手动对齐，否则向量入库会失败。
INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by, updated_by, dept_id) VALUES
    ('kb-rnd-001',   '研发知识库', 'text-embedding-v1', 'kb_rnd_001',   '1', '1', '2'),
    ('kb-legal-001', '法务知识库', 'text-embedding-v1', 'kb_legal_001', '1', '1', '3')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, embedding_model = EXCLUDED.embedding_model,
    collection_name = EXCLUDED.collection_name, dept_id = EXCLUDED.dept_id;

-- 6. 角色-KB 绑定（schema_pg.sql 要求 t_role_kb_relation.id 显式 PK，必须提供）
INSERT INTO t_role_kb_relation (id, role_id, kb_id, permission) VALUES
    ('2001', '100', 'kb-rnd-001',   'MANAGE'),
    ('2002', '101', 'kb-legal-001', 'MANAGE'),
    ('2003', '102', 'kb-rnd-001',   'READ')
ON CONFLICT (id) DO UPDATE SET role_id = EXCLUDED.role_id, kb_id = EXCLUDED.kb_id, permission = EXCLUDED.permission;

COMMIT;

-- 提示：curl 矩阵步骤如需实际触发 security-level 刷新，仍需手动 UI 上传文档到 kb-rnd-001
-- （向量入库依赖 embedding 管道，无法用 SQL seed）。
