-- 升级脚本：v1.4 → v1.5
-- 功能：允许软删后复用知识库 collection_name

ALTER TABLE t_knowledge_base DROP CONSTRAINT IF EXISTS uk_collection_name;
ALTER TABLE t_knowledge_base ADD CONSTRAINT uk_collection_name UNIQUE (collection_name, deleted);
