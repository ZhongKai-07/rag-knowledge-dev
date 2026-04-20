-- ⚠️⚠️⚠️ 本文件仅供 local-dev 使用，其他环境禁止直接执行 ⚠️⚠️⚠️
-- 升级脚本：v1.5 → v1.6（local-dev）
-- 功能：给 t_role 加 dept_id 列（值 = sys_dept.id，不是 dept_code），让角色按部门归属
-- 设计文档：docs/dev/design/2026-04-19-access-center-redesign.md §一·六
-- 其他环境：请复制 upgrade_v1.5_to_v1.6.template.sql 并改名为 upgrade_v1.5_to_v1.6.<env>.sql，
--           再按该环境实际 sys_dept / t_role 数据填写 Step 2 的显式 UPDATE。

-- 1. 加列 + 索引
ALTER TABLE t_role ADD COLUMN IF NOT EXISTS dept_id VARCHAR(20);
COMMENT ON COLUMN t_role.dept_id IS '角色归属部门 ID（sys_dept.id），值 = ''1'' 时为 GLOBAL 角色（仅 SUPER 可创建）';
CREATE INDEX IF NOT EXISTS idx_role_dept_id ON t_role(dept_id);

-- 2. Backfill
-- ⚠️ 执行前必须按本环境实际角色 id 核对下列 UPDATE 是否与部署一致
--    禁止用 LIKE 'XXX%' 前缀模式推断（仓库 fixture 角色名为中文，没有可前缀匹配的约定）
--
-- 本地开发环境映射（2026-04-20 审核）:
--   role.id=1                   超级管理员  SUPER_ADMIN → 1 (全局部门)
--   role.id=2                   普通用户    USER        → 1 (全局部门)
--   role.id=2043727418295869440 FICC User   USER        → 2045764702843265024 (固定收益部)
--   role.id=2043727847201202176 OPS admin   DEPT_ADMIN  → 2043727565494968320 (Operation)
--   role.id=2045766877183041536 pwmadmin    DEPT_ADMIN  → 2045766746140401664 (私人财富管理部)
--   role.id=2045766938155638784 pwmuser     USER        → 2045766746140401664 (私人财富管理部)

-- Step 1: SUPER_ADMIN / 默认通用角色 → GLOBAL (id='1')
UPDATE t_role SET dept_id = '1' WHERE role_type = 'SUPER_ADMIN' AND dept_id IS NULL;
UPDATE t_role SET dept_id = '1' WHERE name = '普通用户' AND dept_id IS NULL;

-- Step 2: 业务角色 → 部门 id（本地开发环境）
UPDATE t_role SET dept_id = '2045764702843265024' WHERE id = '2043727418295869440' AND dept_id IS NULL; -- FICC User
UPDATE t_role SET dept_id = '2043727565494968320' WHERE id = '2043727847201202176' AND dept_id IS NULL; -- OPS admin
UPDATE t_role SET dept_id = '2045766746140401664' WHERE id = '2045766877183041536' AND dept_id IS NULL; -- pwmadmin
UPDATE t_role SET dept_id = '2045766746140401664' WHERE id = '2045766938155638784' AND dept_id IS NULL; -- pwmuser

-- Step 3: 验证
-- SELECT id, name, role_type FROM t_role WHERE dept_id IS NULL AND deleted = 0;
-- 必须返回 0 行；若有残留，由 SUPER_ADMIN 在 P1 接口上线后通过 PUT /role 手动归位

-- P2.2 验证全部归位后追加约束：
--   ALTER TABLE t_role ALTER COLUMN dept_id SET NOT NULL;
