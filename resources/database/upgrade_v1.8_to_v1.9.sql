-- 升级脚本：v1.8 → v1.9
-- 功能：为 t_message 增加 sources_json 列，用于持久化答案引用来源快照（Answer Sources 功能 PR4）
-- 设计文档：docs/superpowers/specs/2026-04-22-answer-sources-pr4-design.md

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS sources_json TEXT;

COMMENT ON COLUMN t_message.sources_json IS '答案引用来源快照（SourceCard[] 的 JSON 序列化），NULL 表示无引用';
