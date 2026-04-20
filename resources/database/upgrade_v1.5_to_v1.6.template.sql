-- ⚠️ 执行前请将本文件复制为 upgrade_v1.5_to_v1.6.<env>.sql，再替换每一处 <...> 占位符。
-- 升级脚本：v1.5 → v1.6（template）
-- 功能：给 t_role 加 dept_id 列（值 = sys_dept.id，不是 dept_code），让角色按部门归属
-- 设计文档：docs/dev/design/2026-04-19-access-center-redesign.md §一·六
-- 风险提示：本迁移的 Step 2 是数据回填，必须按当前环境真实 role/dept 映射逐条填写；
--          禁止保留占位符，更禁止复制 local-dev 的 snowflake id 到 staging/prod。

-- 1. 加列 + 索引
ALTER TABLE t_role ADD COLUMN IF NOT EXISTS dept_id VARCHAR(20);
COMMENT ON COLUMN t_role.dept_id IS '角色归属部门 ID（sys_dept.id），值 = ''1'' 时为 GLOBAL 角色（仅 SUPER 可创建）';
CREATE INDEX IF NOT EXISTS idx_role_dept_id ON t_role(dept_id);

-- 2. Backfill
-- Step 1: SUPER_ADMIN / 默认通用角色 → GLOBAL (id='1')
UPDATE t_role SET dept_id = '1' WHERE role_type = 'SUPER_ADMIN' AND dept_id IS NULL;
UPDATE t_role SET dept_id = '1' WHERE name = '普通用户' AND dept_id IS NULL;

-- Step 2: 业务角色 → 部门 id（必须按目标环境填写，每一行都要替换 <...>）
-- 先执行：
--   SELECT id, name, role_type FROM t_role WHERE deleted = 0 ORDER BY id;
--   SELECT id, dept_code, dept_name FROM sys_dept ORDER BY id;
--
-- 再把每个角色归属明确写成显式 UPDATE，示例：
-- UPDATE t_role SET dept_id = '<研发部 sys_dept.id>' WHERE id = '<角色 id>' AND dept_id IS NULL; -- 研发部管理员
-- UPDATE t_role SET dept_id = '<法务部 sys_dept.id>' WHERE id = '<角色 id>' AND dept_id IS NULL; -- 法务专员

-- 3. 验证
-- SELECT id, name, role_type FROM t_role WHERE dept_id IS NULL AND deleted = 0;
-- 必须返回 0 行；若仍有残留，先修完 backfill，再执行 upgrade_v1.6_to_v1.7.sql。

-- P2.2 验证全部归位后追加约束：
--   ALTER TABLE t_role ALTER COLUMN dept_id SET NOT NULL;
