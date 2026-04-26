-- v1.10 → v1.11：修复 eval 黄金集软删后无法重复删除/重建同名数据集
-- 旧约束 UNIQUE (kb_id, name, deleted) 会让第二个已删除同名行冲突；
-- 改为 PostgreSQL partial unique index，仅约束未删除行。

ALTER TABLE t_eval_gold_dataset
    DROP CONSTRAINT IF EXISTS uk_eval_gold_dataset_kb_name;

CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_gold_dataset_kb_name
    ON t_eval_gold_dataset (kb_id, name)
    WHERE deleted = 0;
