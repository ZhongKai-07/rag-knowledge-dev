-- v1.12 → v1.13
-- Parser enhancement migration.
-- Uses the current next migration slot in this repo. If Collateral Phase 2
-- migrations merge first, renumber this file during rebase instead of leaving
-- a gap or colliding with an already-applied migration.

-- 1) Document-level parser choice
ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS parse_mode VARCHAR(16) NOT NULL DEFAULT 'basic';

COMMENT ON COLUMN t_knowledge_document.parse_mode IS
    'User-facing parser choice: basic (Tika) | enhanced (Docling). Engine name decoupled.';

-- 2) Page-level parser output for preview and later evidence lookup
CREATE TABLE IF NOT EXISTS t_knowledge_document_page (
    id                VARCHAR(20) NOT NULL PRIMARY KEY,
    doc_id            VARCHAR(20) NOT NULL,
    page_no           INTEGER     NOT NULL,
    text              TEXT,
    text_layer_type   VARCHAR(32),
    confidence        DOUBLE PRECISION,
    blocks_json       TEXT,
    created_by        VARCHAR(20) NOT NULL,
    updated_by        VARCHAR(20),
    create_time       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_knowledge_document_page UNIQUE (doc_id, page_no)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_page_doc_id
    ON t_knowledge_document_page (doc_id);

COMMENT ON TABLE t_knowledge_document_page IS 'Page-level parser output for layout-aware evidence lookup';
COMMENT ON COLUMN t_knowledge_document_page.text_layer_type IS 'NATIVE_TEXT|OCR|MIXED|UNKNOWN when available';
COMMENT ON COLUMN t_knowledge_document_page.blocks_json IS 'JSON string array of LayoutBlock records including blockId/pageNo/bbox/readingOrder';

-- 3) Chunk-level layout/evidence fields (populated only when parse_mode=enhanced + Docling available)
ALTER TABLE t_knowledge_chunk
    ADD COLUMN IF NOT EXISTS page_number  INTEGER,
    ADD COLUMN IF NOT EXISTS page_start   INTEGER,
    ADD COLUMN IF NOT EXISTS page_end     INTEGER,
    ADD COLUMN IF NOT EXISTS heading_path TEXT,
    ADD COLUMN IF NOT EXISTS block_type   VARCHAR(32),
    ADD COLUMN IF NOT EXISTS source_block_ids TEXT,
    ADD COLUMN IF NOT EXISTS bbox_refs TEXT,
    ADD COLUMN IF NOT EXISTS text_layer_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS layout_confidence DOUBLE PRECISION;

COMMENT ON COLUMN t_knowledge_chunk.page_number IS 'Display page hint; page_start/page_end are canonical evidence range';
COMMENT ON COLUMN t_knowledge_chunk.page_start IS 'First 1-based source page for this chunk';
COMMENT ON COLUMN t_knowledge_chunk.page_end IS 'Last 1-based source page for this chunk';
COMMENT ON COLUMN t_knowledge_chunk.heading_path IS 'JSON array of ancestor headings, e.g. ["第三章 风险管理","3.2 信用风险"]';
COMMENT ON COLUMN t_knowledge_chunk.block_type IS 'TITLE|PARAGRAPH|TABLE|HEADER|FOOTER|LIST|CAPTION|OTHER';
COMMENT ON COLUMN t_knowledge_chunk.source_block_ids IS 'JSON array of source layout block ids';
COMMENT ON COLUMN t_knowledge_chunk.bbox_refs IS 'JSON array of bbox references copied from parser layout output';
COMMENT ON COLUMN t_knowledge_chunk.text_layer_type IS 'NATIVE_TEXT|OCR|MIXED when available';
COMMENT ON COLUMN t_knowledge_chunk.layout_confidence IS 'Parser layout confidence when available';
