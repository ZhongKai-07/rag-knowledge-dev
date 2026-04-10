-- ============================================================
-- Upgrade: v1.2 → v1.3
-- Description: 会话关联知识库，支持按知识库隔离会话
-- ============================================================

-- 1. t_conversation 增加 kb_id 字段
ALTER TABLE t_conversation ADD COLUMN kb_id VARCHAR(20) DEFAULT NULL;
COMMENT ON COLUMN t_conversation.kb_id IS '关联知识库ID';

-- 2. 新增复合索引：按用户+知识库查询会话列表
CREATE INDEX idx_conversation_kb_user ON t_conversation (user_id, kb_id, last_time);
