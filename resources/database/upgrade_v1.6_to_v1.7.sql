-- 升级脚本：v1.6 → v1.7
-- 功能：给 t_role.dept_id 加 NOT NULL 约束（P2.2 收尾）
-- 设计文档：docs/dev/design/2026-04-19-access-center-redesign.md §八 P2.2

-- 前置条件：所有 t_role 行必须已有非空 dept_id。执行前必跑：
--   SELECT id, name, role_type FROM t_role WHERE dept_id IS NULL AND deleted = 0;
--   → 必须 0 行；否则先跑 upgrade_v1.5_to_v1.6.sql 的 backfill 步骤

-- 1. 安全检查：残留 NULL 行时直接报错而非静默通过
DO $$
DECLARE
    null_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO null_count FROM t_role WHERE dept_id IS NULL AND deleted = 0;
    IF null_count > 0 THEN
        RAISE EXCEPTION '存在 % 行未归属部门的 t_role，无法加 NOT NULL 约束；请先运行 v1.5→v1.6 backfill', null_count;
    END IF;
END $$;

-- 2. 加 NOT NULL 约束
ALTER TABLE t_role ALTER COLUMN dept_id SET NOT NULL;

-- （软删除行 deleted=1 不纳入校验，但 PG 的 SET NOT NULL 会检查所有行。
--  本项目软删除行也会填 dept_id，故无需特殊处理；若将来出现历史残留，
--  可先 DELETE WHERE deleted=1 AND dept_id IS NULL，再执行本脚本）
