-- 升级脚本：v1.7 → v1.8
-- 功能：修复 t_knowledge_base.dept_id 默认值与 GLOBAL 部门真实 id 不一致的问题
-- 设计文档：docs/dev/design/2026-04-20-access-center-followup-p3.md §P3.5

-- 执行前建议先跑：
--   SELECT COUNT(*) FROM t_knowledge_base WHERE dept_id NOT IN (SELECT id FROM sys_dept);
-- 若结果非 0，请先确认是否只有历史字面量 'GLOBAL'；除 'GLOBAL' 外的孤立值需要人工处理。

DO $$
DECLARE
    invalid_count INTEGER;
    global_literal_count INTEGER;
BEGIN
    SELECT COUNT(*)
      INTO invalid_count
      FROM t_knowledge_base
     WHERE dept_id NOT IN (SELECT id FROM sys_dept)
       AND dept_id <> 'GLOBAL';

    IF invalid_count > 0 THEN
        RAISE EXCEPTION 't_knowledge_base 存在 % 行非法 dept_id（不含历史值 GLOBAL），请先人工修复', invalid_count;
    END IF;

    SELECT COUNT(*)
      INTO global_literal_count
      FROM t_knowledge_base
     WHERE dept_id = 'GLOBAL';

    RAISE NOTICE 'Fixing % knowledge base rows with dept_id=GLOBAL', global_literal_count;
END $$;

UPDATE t_knowledge_base
   SET dept_id = '1'
 WHERE dept_id = 'GLOBAL';

ALTER TABLE t_knowledge_base ALTER COLUMN dept_id SET DEFAULT '1';
