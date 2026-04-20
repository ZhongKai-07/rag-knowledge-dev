-- v1.8 -> v1.9
-- 为会话消息增加引用来源字段，支持 chat 页面 source 卡片回放
ALTER TABLE t_message
    ADD COLUMN IF NOT EXISTS sources_json TEXT;

COMMENT ON COLUMN t_message.sources_json IS '引用来源（JSON）';
