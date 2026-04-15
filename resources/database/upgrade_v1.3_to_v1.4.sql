-- 升级脚本：v1.3 → v1.4
-- 功能：为 t_message 表添加深度思考字段

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS thinking_content TEXT;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS thinking_duration BIGINT;
COMMENT ON COLUMN t_message.thinking_content IS '深度思考内容（模型推理链）';
COMMENT ON COLUMN t_message.thinking_duration IS '深度思考耗时（毫秒）';
