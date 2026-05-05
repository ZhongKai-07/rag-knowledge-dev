-- v1.13 → v1.14
-- Parser enhancement PR 4: seed the default ENHANCED pipeline.
--
-- Why a new file: v1.13 has been merged and applied; appending in-place would break
-- the idempotent assumption other environments rely on (skip if already applied).
--
-- ID note: t_ingestion_pipeline.id and t_ingestion_pipeline_node.id are VARCHAR(20).
-- The plan's draft id "default-enhanced-pipeline" is 25 chars and would overflow.
-- We use the stable id "enhanced-default" (16 chars) instead, both in this seed and
-- in KnowledgeDocumentServiceImpl.upload(...) when ENHANCED is chosen without an
-- explicit pipelineId.

-- 1) Pipeline header
INSERT INTO t_ingestion_pipeline (id, name, description, created_by, updated_by)
VALUES (
    'enhanced-default',
    'Default Enhanced Parsing',
    'Auto-selected for ENHANCED parse mode uploads. Phase 2.5 MVP: ParserNode runs Docling (fallback Tika). PR 6 will swap chunker for structure-aware layout chunks.',
    'system',
    'system'
)
ON CONFLICT (id) DO NOTHING;

-- 2) Pipeline nodes (4 nodes: fetcher → parser → chunker → indexer)
--    settings_json on the parser node carries parseMode=enhanced; ParserNode reads it
--    via DocumentParserSelector.selectByParseMode (PR 1).
--    chunker uses an existing strategy (structure_aware) until PR 6 introduces a
--    layout-aware structured chunker.
INSERT INTO t_ingestion_pipeline_node
    (id, pipeline_id, node_id, node_type, next_node_id, settings_json, created_by, updated_by)
VALUES
    ('enh-default-fetch', 'enhanced-default', 'fetch', 'fetcher', 'parse',
        NULL, 'system', 'system'),
    ('enh-default-parse', 'enhanced-default', 'parse', 'parser', 'chunk',
        '{"parseMode":"enhanced"}'::jsonb, 'system', 'system'),
    ('enh-default-chunk', 'enhanced-default', 'chunk', 'chunker', 'index',
        '{"strategy":"structure_aware"}'::jsonb, 'system', 'system'),
    ('enh-default-index', 'enhanced-default', 'index', 'indexer', NULL,
        NULL, 'system', 'system')
ON CONFLICT (id) DO NOTHING;
