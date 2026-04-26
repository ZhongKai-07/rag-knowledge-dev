-- v1.9 → v1.10：新增 RAG 评估闭环（eval 域）4 张表
-- 对齐 spec §5；所有表 t_eval_* 前缀归属 eval 域

CREATE TABLE t_eval_gold_dataset (
    id             VARCHAR(20)   NOT NULL PRIMARY KEY,
    kb_id          VARCHAR(20)   NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    description    TEXT,
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    item_count     INT           NOT NULL DEFAULT 0,
    created_by     VARCHAR(20)   NOT NULL,
    updated_by     VARCHAR(20),
    create_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_eval_gold_dataset_kb_name ON t_eval_gold_dataset (kb_id, name) WHERE deleted = 0;
CREATE INDEX idx_eval_gold_dataset_kb ON t_eval_gold_dataset (kb_id);
COMMENT ON TABLE t_eval_gold_dataset IS 'RAG 评估黄金集（按 KB 划分）';

CREATE TABLE t_eval_gold_item (
    id                     VARCHAR(20)   NOT NULL PRIMARY KEY,
    dataset_id             VARCHAR(20)   NOT NULL,
    question               TEXT          NOT NULL,
    ground_truth_answer    TEXT          NOT NULL,
    source_chunk_id        VARCHAR(20),
    source_chunk_text      TEXT          NOT NULL,
    source_doc_id          VARCHAR(20),
    source_doc_name        VARCHAR(255),
    review_status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    review_note            TEXT,
    synthesized_by_model   VARCHAR(64),
    created_by             VARCHAR(20)   NOT NULL,
    updated_by             VARCHAR(20),
    create_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_eval_gold_item_dataset_review ON t_eval_gold_item (dataset_id, review_status);
COMMENT ON TABLE t_eval_gold_item IS 'RAG 评估黄金集条目（含 chunk 快照）';

CREATE TABLE t_eval_run (
    id                VARCHAR(20)   NOT NULL PRIMARY KEY,
    dataset_id        VARCHAR(20)   NOT NULL,
    kb_id             VARCHAR(20)   NOT NULL,
    triggered_by      VARCHAR(20)   NOT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    total_items       INT           NOT NULL DEFAULT 0,
    succeeded_items   INT           NOT NULL DEFAULT 0,
    failed_items      INT           NOT NULL DEFAULT 0,
    metrics_summary   TEXT,
    system_snapshot   TEXT,
    evaluator_llm     VARCHAR(64),
    error_message     TEXT,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    created_by        VARCHAR(20)   NOT NULL,
    updated_by        VARCHAR(20),
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_eval_run_dataset ON t_eval_run (dataset_id);
CREATE INDEX idx_eval_run_kb ON t_eval_run (kb_id);
COMMENT ON TABLE t_eval_run IS 'RAG 评估运行（含系统配置快照）';

CREATE TABLE t_eval_result (
    id                     VARCHAR(20)    NOT NULL PRIMARY KEY,
    run_id                 VARCHAR(20)    NOT NULL,
    gold_item_id           VARCHAR(20)    NOT NULL,
    question               TEXT,
    ground_truth_answer    TEXT,
    system_answer          TEXT,
    retrieved_chunks       TEXT,
    faithfulness           DECIMAL(5,4),
    answer_relevancy       DECIMAL(5,4),
    context_precision      DECIMAL(5,4),
    context_recall         DECIMAL(5,4),
    error                  TEXT,
    elapsed_ms             INT,
    create_time            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_eval_result_run ON t_eval_result (run_id);
CREATE INDEX idx_eval_result_run_faith ON t_eval_result (run_id, faithfulness);
COMMENT ON TABLE t_eval_result IS 'RAG 评估每条 gold item 结果';
