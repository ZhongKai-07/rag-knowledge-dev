--
-- PostgreSQL database dump
--

\restrict Q3WEuQZlPJZgK67wCVaCHTg4cKeuc8ab0MS7bkdxzR1Eir5pwjL9010tfApDrNa

-- Dumped from database version 16.13 (Debian 16.13-1.pgdg12+1)
-- Dumped by pg_dump version 16.13 (Debian 16.13-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

DROP INDEX IF EXISTS public.idx_user_time;
DROP INDEX IF EXISTS public.idx_user_role_user_id;
DROP INDEX IF EXISTS public.idx_user_role_role_id;
DROP INDEX IF EXISTS public.idx_user_id_trace;
DROP INDEX IF EXISTS public.idx_user_id;
DROP INDEX IF EXISTS public.idx_task_id;
DROP INDEX IF EXISTS public.idx_source;
DROP INDEX IF EXISTS public.idx_schedule_time;
DROP INDEX IF EXISTS public.idx_sample_question_deleted;
DROP INDEX IF EXISTS public.idx_role_kb_role_id;
DROP INDEX IF EXISTS public.idx_role_kb_kb_id;
DROP INDEX IF EXISTS public.idx_next_run;
DROP INDEX IF EXISTS public.idx_lock_until;
DROP INDEX IF EXISTS public.idx_kv_metadata;
DROP INDEX IF EXISTS public.idx_kv_embedding_hnsw;
DROP INDEX IF EXISTS public.idx_kv_embedding;
DROP INDEX IF EXISTS public.idx_kb_name;
DROP INDEX IF EXISTS public.idx_kb_id;
DROP INDEX IF EXISTS public.idx_ingestion_task_status;
DROP INDEX IF EXISTS public.idx_ingestion_task_pipeline;
DROP INDEX IF EXISTS public.idx_ingestion_task_node_task;
DROP INDEX IF EXISTS public.idx_ingestion_task_node_status;
DROP INDEX IF EXISTS public.idx_ingestion_task_node_pipeline;
DROP INDEX IF EXISTS public.idx_ingestion_pipeline_node_pipeline;
DROP INDEX IF EXISTS public.idx_eval_trace;
DROP INDEX IF EXISTS public.idx_eval_status;
DROP INDEX IF EXISTS public.idx_eval_conv;
DROP INDEX IF EXISTS public.idx_domain;
DROP INDEX IF EXISTS public.idx_doc_id_log;
DROP INDEX IF EXISTS public.idx_doc_id_exec;
DROP INDEX IF EXISTS public.idx_doc_id;
DROP INDEX IF EXISTS public.idx_conversation_user_time;
DROP INDEX IF EXISTS public.idx_conversation_summary;
DROP INDEX IF EXISTS public.idx_conversation_id;
DROP INDEX IF EXISTS public.idx_conv_user;
ALTER TABLE IF EXISTS ONLY public.sys_dept DROP CONSTRAINT IF EXISTS uk_dept_code;
ALTER TABLE IF EXISTS ONLY public.t_user DROP CONSTRAINT IF EXISTS uk_user_username;
ALTER TABLE IF EXISTS ONLY public.t_rag_trace_node DROP CONSTRAINT IF EXISTS uk_run_node;
ALTER TABLE IF EXISTS ONLY public.t_rag_trace_run DROP CONSTRAINT IF EXISTS uk_run_id;
ALTER TABLE IF EXISTS ONLY public.t_role DROP CONSTRAINT IF EXISTS uk_role_name;
ALTER TABLE IF EXISTS ONLY public.t_message_feedback DROP CONSTRAINT IF EXISTS uk_msg_user;
ALTER TABLE IF EXISTS ONLY public.t_ingestion_pipeline_node DROP CONSTRAINT IF EXISTS uk_ingestion_pipeline_node;
ALTER TABLE IF EXISTS ONLY public.t_ingestion_pipeline DROP CONSTRAINT IF EXISTS uk_ingestion_pipeline_name;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_document_schedule DROP CONSTRAINT IF EXISTS uk_doc_id;
ALTER TABLE IF EXISTS ONLY public.t_conversation DROP CONSTRAINT IF EXISTS uk_conversation_user;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_base DROP CONSTRAINT IF EXISTS uk_collection_name;
ALTER TABLE IF EXISTS ONLY public.t_user_role DROP CONSTRAINT IF EXISTS t_user_role_pkey;
ALTER TABLE IF EXISTS ONLY public.sys_dept DROP CONSTRAINT IF EXISTS sys_dept_pkey;
ALTER TABLE IF EXISTS ONLY public.t_user DROP CONSTRAINT IF EXISTS t_user_pkey;
ALTER TABLE IF EXISTS ONLY public.t_sample_question DROP CONSTRAINT IF EXISTS t_sample_question_pkey;
ALTER TABLE IF EXISTS ONLY public.t_role DROP CONSTRAINT IF EXISTS t_role_pkey;
ALTER TABLE IF EXISTS ONLY public.t_role_kb_relation DROP CONSTRAINT IF EXISTS t_role_kb_relation_pkey;
ALTER TABLE IF EXISTS ONLY public.t_rag_trace_run DROP CONSTRAINT IF EXISTS t_rag_trace_run_pkey;
ALTER TABLE IF EXISTS ONLY public.t_rag_trace_node DROP CONSTRAINT IF EXISTS t_rag_trace_node_pkey;
ALTER TABLE IF EXISTS ONLY public.t_rag_evaluation_record DROP CONSTRAINT IF EXISTS t_rag_evaluation_record_pkey;
ALTER TABLE IF EXISTS ONLY public.t_query_term_mapping DROP CONSTRAINT IF EXISTS t_query_term_mapping_pkey;
ALTER TABLE IF EXISTS ONLY public.t_message DROP CONSTRAINT IF EXISTS t_message_pkey;
ALTER TABLE IF EXISTS ONLY public.t_message_feedback DROP CONSTRAINT IF EXISTS t_message_feedback_pkey;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_vector DROP CONSTRAINT IF EXISTS t_knowledge_vector_pkey;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_document_schedule DROP CONSTRAINT IF EXISTS t_knowledge_document_schedule_pkey;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_document_schedule_exec DROP CONSTRAINT IF EXISTS t_knowledge_document_schedule_exec_pkey;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_document DROP CONSTRAINT IF EXISTS t_knowledge_document_pkey;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_document_chunk_log DROP CONSTRAINT IF EXISTS t_knowledge_document_chunk_log_pkey;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_chunk DROP CONSTRAINT IF EXISTS t_knowledge_chunk_pkey;
ALTER TABLE IF EXISTS ONLY public.t_knowledge_base DROP CONSTRAINT IF EXISTS t_knowledge_base_pkey;
ALTER TABLE IF EXISTS ONLY public.t_intent_node DROP CONSTRAINT IF EXISTS t_intent_node_pkey;
ALTER TABLE IF EXISTS ONLY public.t_ingestion_task DROP CONSTRAINT IF EXISTS t_ingestion_task_pkey;
ALTER TABLE IF EXISTS ONLY public.t_ingestion_task_node DROP CONSTRAINT IF EXISTS t_ingestion_task_node_pkey;
ALTER TABLE IF EXISTS ONLY public.t_ingestion_pipeline DROP CONSTRAINT IF EXISTS t_ingestion_pipeline_pkey;
ALTER TABLE IF EXISTS ONLY public.t_ingestion_pipeline_node DROP CONSTRAINT IF EXISTS t_ingestion_pipeline_node_pkey;
ALTER TABLE IF EXISTS ONLY public.t_conversation_summary DROP CONSTRAINT IF EXISTS t_conversation_summary_pkey;
ALTER TABLE IF EXISTS ONLY public.t_conversation DROP CONSTRAINT IF EXISTS t_conversation_pkey;
DROP TABLE IF EXISTS public.t_user_role;
DROP TABLE IF EXISTS public.t_user;
DROP TABLE IF EXISTS public.sys_dept;
DROP TABLE IF EXISTS public.t_sample_question;
DROP TABLE IF EXISTS public.t_role_kb_relation;
DROP TABLE IF EXISTS public.t_role;
DROP TABLE IF EXISTS public.t_rag_trace_run;
DROP TABLE IF EXISTS public.t_rag_trace_node;
DROP TABLE IF EXISTS public.t_rag_evaluation_record;
DROP TABLE IF EXISTS public.t_query_term_mapping;
DROP TABLE IF EXISTS public.t_message_feedback;
DROP TABLE IF EXISTS public.t_message;
DROP TABLE IF EXISTS public.t_knowledge_vector;
DROP TABLE IF EXISTS public.t_knowledge_document_schedule_exec;
DROP TABLE IF EXISTS public.t_knowledge_document_schedule;
DROP TABLE IF EXISTS public.t_knowledge_document_chunk_log;
DROP TABLE IF EXISTS public.t_knowledge_document;
DROP TABLE IF EXISTS public.t_knowledge_chunk;
DROP TABLE IF EXISTS public.t_knowledge_base;
DROP TABLE IF EXISTS public.t_intent_node;
DROP TABLE IF EXISTS public.t_ingestion_task_node;
DROP TABLE IF EXISTS public.t_ingestion_task;
DROP TABLE IF EXISTS public.t_ingestion_pipeline_node;
DROP TABLE IF EXISTS public.t_ingestion_pipeline;
DROP TABLE IF EXISTS public.t_conversation_summary;
DROP TABLE IF EXISTS public.t_conversation;
DROP EXTENSION IF EXISTS vector;
--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


SET default_table_access_method = heap;

--
-- Name: t_conversation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_conversation (
    id character varying(20) NOT NULL,
    conversation_id character varying(20) NOT NULL,
    user_id character varying(20) NOT NULL,
    kb_id character varying(20) DEFAULT NULL,
    title character varying(128) NOT NULL,
    last_time timestamp without time zone,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_conversation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_conversation IS '会话列表';


--
-- Name: COLUMN t_conversation.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.id IS '主键ID';


--
-- Name: COLUMN t_conversation.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.conversation_id IS '会话ID';


--
-- Name: COLUMN t_conversation.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.user_id IS '用户ID';


--
-- Name: COLUMN t_conversation.kb_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.kb_id IS '关联知识库ID';


--
-- Name: COLUMN t_conversation.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.title IS '会话名称';


--
-- Name: COLUMN t_conversation.last_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.last_time IS '最近消息时间';


--
-- Name: COLUMN t_conversation.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.create_time IS '创建时间';


--
-- Name: COLUMN t_conversation.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.update_time IS '更新时间';


--
-- Name: COLUMN t_conversation.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation.deleted IS '是否删除 0：正常 1：删除';


--
-- Name: t_conversation_summary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_conversation_summary (
    id character varying(20) NOT NULL,
    conversation_id character varying(20) NOT NULL,
    user_id character varying(20) NOT NULL,
    last_message_id character varying(20) NOT NULL,
    content text NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_conversation_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_conversation_summary IS '会话摘要表（与消息表分离存储）';


--
-- Name: COLUMN t_conversation_summary.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.id IS '主键ID';


--
-- Name: COLUMN t_conversation_summary.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.conversation_id IS '会话ID';


--
-- Name: COLUMN t_conversation_summary.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.user_id IS '用户ID';


--
-- Name: COLUMN t_conversation_summary.last_message_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.last_message_id IS '摘要最后消息ID';


--
-- Name: COLUMN t_conversation_summary.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.content IS '会话摘要内容';


--
-- Name: COLUMN t_conversation_summary.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.create_time IS '创建时间';


--
-- Name: COLUMN t_conversation_summary.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.update_time IS '更新时间';


--
-- Name: COLUMN t_conversation_summary.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_conversation_summary.deleted IS '是否删除 0：正常 1：删除';


--
-- Name: t_ingestion_pipeline; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_ingestion_pipeline (
    id character varying(20) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    created_by character varying(20) DEFAULT ''::character varying,
    updated_by character varying(20) DEFAULT ''::character varying,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_ingestion_pipeline; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_ingestion_pipeline IS '摄取流水线表';


--
-- Name: t_ingestion_pipeline_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_ingestion_pipeline_node (
    id character varying(20) NOT NULL,
    pipeline_id character varying(20) NOT NULL,
    node_id character varying(20) NOT NULL,
    node_type character varying(16) NOT NULL,
    next_node_id character varying(20),
    settings_json jsonb,
    condition_json jsonb,
    created_by character varying(20) DEFAULT ''::character varying,
    updated_by character varying(20) DEFAULT ''::character varying,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_ingestion_pipeline_node; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_ingestion_pipeline_node IS '摄取流水线节点表';


--
-- Name: t_ingestion_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_ingestion_task (
    id character varying(20) NOT NULL,
    pipeline_id character varying(20) NOT NULL,
    source_type character varying(20) NOT NULL,
    source_location text,
    source_file_name character varying(255),
    status character varying(16) NOT NULL,
    chunk_count integer DEFAULT 0,
    error_message text,
    logs_json jsonb,
    metadata_json jsonb,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    created_by character varying(20) DEFAULT ''::character varying,
    updated_by character varying(20) DEFAULT ''::character varying,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_ingestion_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_ingestion_task IS '摄取任务表';


--
-- Name: t_ingestion_task_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_ingestion_task_node (
    id character varying(20) NOT NULL,
    task_id character varying(20) NOT NULL,
    pipeline_id character varying(20) NOT NULL,
    node_id character varying(20) NOT NULL,
    node_type character varying(16) NOT NULL,
    node_order integer DEFAULT 0 NOT NULL,
    status character varying(16) NOT NULL,
    duration_ms bigint DEFAULT 0 NOT NULL,
    message text,
    error_message text,
    output_json text,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_ingestion_task_node; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_ingestion_task_node IS '摄取任务节点表';


--
-- Name: t_intent_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_intent_node (
    id character varying(20) NOT NULL,
    kb_id character varying(20),
    intent_code character varying(64) NOT NULL,
    name character varying(64) NOT NULL,
    level smallint NOT NULL,
    parent_code character varying(64),
    description character varying(512),
    examples text,
    collection_name character varying(128),
    top_k integer,
    mcp_tool_id character varying(128),
    kind smallint DEFAULT 0 NOT NULL,
    prompt_snippet text,
    prompt_template text,
    param_prompt_template text,
    sort_order integer DEFAULT 0 NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    create_by character varying(20),
    update_by character varying(20),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_intent_node; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_intent_node IS '意图树节点配置表';


--
-- Name: t_knowledge_base; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_knowledge_base (
    id character varying(20) NOT NULL,
    name character varying(128) NOT NULL,
    embedding_model character varying(64) NOT NULL,
    collection_name character varying(64) NOT NULL,
    created_by character varying(20) NOT NULL,
    updated_by character varying(20),
    dept_id character varying(20) DEFAULT 'GLOBAL'::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_knowledge_base; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_knowledge_base IS '知识库表';


--
-- Name: COLUMN t_knowledge_base.dept_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_knowledge_base.dept_id IS '归属部门ID（决定哪个 DEPT_ADMIN 能管理此知识库）';


--
-- Name: t_knowledge_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_knowledge_chunk (
    id character varying(20) NOT NULL,
    kb_id character varying(20) NOT NULL,
    doc_id character varying(20) NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    content_hash character varying(64),
    char_count integer,
    token_count integer,
    enabled smallint DEFAULT 1 NOT NULL,
    created_by character varying(20) NOT NULL,
    updated_by character varying(20),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_knowledge_chunk; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_knowledge_chunk IS '知识库文档分块表';


--
-- Name: t_knowledge_document; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_knowledge_document (
    id character varying(20) NOT NULL,
    kb_id character varying(20) NOT NULL,
    doc_name character varying(256) NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    chunk_count integer DEFAULT 0,
    file_url character varying(1024) NOT NULL,
    file_type character varying(16) NOT NULL,
    file_size bigint,
    process_mode character varying(16) DEFAULT 'chunk'::character varying,
    status character varying(16) DEFAULT 'pending'::character varying NOT NULL,
    source_type character varying(16),
    source_location character varying(1024),
    schedule_enabled smallint,
    schedule_cron character varying(64),
    chunk_strategy character varying(32),
    chunk_config jsonb,
    pipeline_id character varying(20),
    created_by character varying(20) NOT NULL,
    updated_by character varying(20),
    security_level smallint DEFAULT 0 NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_knowledge_document; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_knowledge_document IS '知识库文档表';


--
-- Name: COLUMN t_knowledge_document.security_level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_knowledge_document.security_level IS '文档安全等级：0=PUBLIC, 1=INTERNAL, 2=CONFIDENTIAL, 3=RESTRICTED';


--
-- Name: t_knowledge_document_chunk_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_knowledge_document_chunk_log (
    id character varying(20) NOT NULL,
    doc_id character varying(20) NOT NULL,
    status character varying(16) NOT NULL,
    process_mode character varying(16),
    chunk_strategy character varying(16),
    pipeline_id character varying(20),
    extract_duration bigint,
    chunk_duration bigint,
    embed_duration bigint,
    persist_duration bigint,
    total_duration bigint,
    chunk_count integer,
    error_message text,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: TABLE t_knowledge_document_chunk_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_knowledge_document_chunk_log IS '知识库文档分块日志表';


--
-- Name: t_knowledge_document_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_knowledge_document_schedule (
    id character varying(20) NOT NULL,
    doc_id character varying(20) NOT NULL,
    kb_id character varying(20) NOT NULL,
    cron_expr character varying(64),
    enabled smallint DEFAULT 0,
    next_run_time timestamp without time zone,
    last_run_time timestamp without time zone,
    last_success_time timestamp without time zone,
    last_status character varying(16),
    last_error character varying(512),
    last_etag character varying(256),
    last_modified character varying(256),
    last_content_hash character varying(128),
    lock_owner character varying(128),
    lock_until timestamp without time zone,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE t_knowledge_document_schedule; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_knowledge_document_schedule IS '知识库文档定时刷新任务表';


--
-- Name: t_knowledge_document_schedule_exec; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_knowledge_document_schedule_exec (
    id character varying(20) NOT NULL,
    schedule_id character varying(20) NOT NULL,
    doc_id character varying(20) NOT NULL,
    kb_id character varying(20) NOT NULL,
    status character varying(16) NOT NULL,
    message character varying(512),
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    file_name character varying(512),
    file_size bigint,
    content_hash character varying(128),
    etag character varying(256),
    last_modified character varying(256),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE t_knowledge_document_schedule_exec; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_knowledge_document_schedule_exec IS '知识库文档定时刷新执行记录';


--
-- Name: t_knowledge_vector; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_knowledge_vector (
    id character varying(20) NOT NULL,
    content text,
    metadata jsonb,
    embedding public.vector(1536)
);


--
-- Name: TABLE t_knowledge_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_knowledge_vector IS '知识库向量存储表';


--
-- Name: COLUMN t_knowledge_vector.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_knowledge_vector.id IS '分块ID';


--
-- Name: COLUMN t_knowledge_vector.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_knowledge_vector.content IS '分块文本内容';


--
-- Name: COLUMN t_knowledge_vector.metadata; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_knowledge_vector.metadata IS '元数据';


--
-- Name: COLUMN t_knowledge_vector.embedding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_knowledge_vector.embedding IS '向量';


--
-- Name: t_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_message (
    id character varying(20) NOT NULL,
    conversation_id character varying(20) NOT NULL,
    user_id character varying(20) NOT NULL,
    role character varying(16) NOT NULL,
    content text NOT NULL,
    thinking_content text,
    thinking_duration bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_message IS '会话消息记录表';


--
-- Name: COLUMN t_message.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.id IS '主键ID';


--
-- Name: COLUMN t_message.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.conversation_id IS '会话ID';


--
-- Name: COLUMN t_message.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.user_id IS '用户ID';


--
-- Name: COLUMN t_message.role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.role IS '角色：user/assistant';


--
-- Name: COLUMN t_message.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.content IS '消息内容';


--
-- Name: COLUMN t_message.thinking_content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.thinking_content IS '深度思考内容（模型推理链）';


--
-- Name: COLUMN t_message.thinking_duration; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.thinking_duration IS '深度思考耗时（毫秒）';


--
-- Name: COLUMN t_message.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.create_time IS '创建时间';


--
-- Name: COLUMN t_message.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.update_time IS '更新时间';


--
-- Name: COLUMN t_message.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message.deleted IS '是否删除 0：正常 1：删除';


--
-- Name: t_message_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_message_feedback (
    id character varying(20) NOT NULL,
    message_id character varying(20) NOT NULL,
    conversation_id character varying(20) NOT NULL,
    user_id character varying(20) NOT NULL,
    vote smallint NOT NULL,
    reason character varying(255),
    comment character varying(1024),
    create_time timestamp without time zone NOT NULL,
    update_time timestamp without time zone NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_message_feedback; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_message_feedback IS '会话消息反馈表';


--
-- Name: COLUMN t_message_feedback.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.id IS '主键ID';


--
-- Name: COLUMN t_message_feedback.message_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.message_id IS '消息ID';


--
-- Name: COLUMN t_message_feedback.conversation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.conversation_id IS '会话ID';


--
-- Name: COLUMN t_message_feedback.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.user_id IS '用户ID';


--
-- Name: COLUMN t_message_feedback.vote; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.vote IS '投票 1：赞 -1：踩';


--
-- Name: COLUMN t_message_feedback.reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.reason IS '反馈原因';


--
-- Name: COLUMN t_message_feedback.comment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.comment IS '反馈评论';


--
-- Name: COLUMN t_message_feedback.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.create_time IS '创建时间';


--
-- Name: COLUMN t_message_feedback.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.update_time IS '更新时间';


--
-- Name: COLUMN t_message_feedback.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_message_feedback.deleted IS '是否删除 0：正常 1：删除';


--
-- Name: t_query_term_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_query_term_mapping (
    id character varying(20) NOT NULL,
    domain character varying(64),
    source_term character varying(128) NOT NULL,
    target_term character varying(128) NOT NULL,
    match_type smallint DEFAULT 1 NOT NULL,
    priority integer DEFAULT 100 NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    remark character varying(255),
    create_by character varying(20),
    update_by character varying(20),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_query_term_mapping; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_query_term_mapping IS '关键词归一化映射表';


--
-- Name: t_rag_evaluation_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_rag_evaluation_record (
    id character varying(20) NOT NULL,
    trace_id character varying(64),
    conversation_id character varying(20),
    message_id character varying(20),
    user_id character varying(20),
    original_query text,
    rewritten_query text,
    sub_questions text,
    retrieved_chunks text,
    retrieval_top_k integer,
    answer text,
    model_name character varying(64),
    intent_results text,
    eval_status character varying(16) DEFAULT 'PENDING'::character varying,
    eval_metrics text,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_rag_evaluation_record; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_rag_evaluation_record IS 'RAG 评测记录表（Query-Chunk-Answer 留存）';


--
-- Name: t_rag_trace_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_rag_trace_node (
    id character varying(20) NOT NULL,
    trace_id character varying(20) NOT NULL,
    node_id character varying(20) NOT NULL,
    parent_node_id character varying(20),
    depth integer DEFAULT 0,
    node_type character varying(16),
    node_name character varying(128),
    class_name character varying(256),
    method_name character varying(128),
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    error_message character varying(1000),
    start_time timestamp(3) without time zone,
    end_time timestamp(3) without time zone,
    duration_ms bigint,
    extra_data text,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_rag_trace_node; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_rag_trace_node IS 'Trace 节点记录表';


--
-- Name: t_rag_trace_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_rag_trace_run (
    id character varying(20) NOT NULL,
    trace_id character varying(64) NOT NULL,
    trace_name character varying(128),
    entry_method character varying(256),
    conversation_id character varying(20),
    task_id character varying(20),
    user_id character varying(20),
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    error_message character varying(1000),
    start_time timestamp(3) without time zone,
    end_time timestamp(3) without time zone,
    duration_ms bigint,
    extra_data text,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_rag_trace_run; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_rag_trace_run IS 'Trace 运行记录表';


--
-- Name: t_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_role (
    id character varying(20) NOT NULL,
    name character varying(64) NOT NULL,
    description character varying(256),
    role_type character varying(32) DEFAULT 'USER'::character varying NOT NULL,
    max_security_level smallint DEFAULT 0 NOT NULL,
    created_by character varying(64),
    updated_by character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted integer DEFAULT 0
);


--
-- Name: t_role_kb_relation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_role_kb_relation (
    id character varying(20) NOT NULL,
    role_id character varying(20) NOT NULL,
    kb_id character varying(20) NOT NULL,
    permission character varying(16) DEFAULT 'READ'::character varying NOT NULL,
    max_security_level smallint DEFAULT 0 NOT NULL,
    created_by character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted integer DEFAULT 0
);


--
-- Name: COLUMN t_role.role_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_role.role_type IS 'SUPER_ADMIN/DEPT_ADMIN/USER';


--
-- Name: COLUMN t_role.max_security_level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_role.max_security_level IS '该角色可访问的最高安全等级（0-3）';


--
-- Name: COLUMN t_role_kb_relation.permission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_role_kb_relation.permission IS 'READ/WRITE/MANAGE';


--
-- Name: COLUMN t_role_kb_relation.max_security_level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_role_kb_relation.max_security_level IS '该角色对该 KB 可访问的最高安全等级（0-3），检索时按此值过滤';


--
-- Name: t_sample_question; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_sample_question (
    id character varying(20) NOT NULL,
    title character varying(64),
    description character varying(255),
    question character varying(255) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_sample_question; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_sample_question IS '示例问题表';


--
-- Name: COLUMN t_sample_question.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_sample_question.id IS 'ID';


--
-- Name: COLUMN t_sample_question.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_sample_question.title IS '展示标题';


--
-- Name: sys_dept; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_dept (
    id character varying(20) NOT NULL,
    dept_code character varying(32) NOT NULL,
    dept_name character varying(64) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE sys_dept; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_dept IS '部门表';


--
-- Name: COLUMN sys_dept.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_dept.id IS '主键ID';


--
-- Name: COLUMN sys_dept.dept_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_dept.dept_code IS '部门编码，全局唯一';


--
-- Name: COLUMN sys_dept.dept_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_dept.dept_name IS '部门名称';


--
-- Name: t_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_user (
    id character varying(20) NOT NULL,
    username character varying(64) NOT NULL,
    password character varying(128) NOT NULL,
    role character varying(32) NOT NULL,
    avatar character varying(128),
    dept_id character varying(20),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted smallint DEFAULT 0
);


--
-- Name: TABLE t_user; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_user IS '系统用户表';


--
-- Name: COLUMN t_user.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.id IS '主键ID';


--
-- Name: COLUMN t_user.username; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.username IS '用户名，唯一';


--
-- Name: COLUMN t_user.password; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.password IS '密码';


--
-- Name: COLUMN t_user.role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.role IS '角色：admin/user';


--
-- Name: COLUMN t_user.avatar; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.avatar IS '用户头像';


--
-- Name: COLUMN t_user.dept_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.dept_id IS '所属部门ID';


--
-- Name: COLUMN t_user.create_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.create_time IS '创建时间';


--
-- Name: COLUMN t_user.update_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.update_time IS '更新时间';


--
-- Name: COLUMN t_user.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.deleted IS '是否删除 0：正常 1：删除';


--
-- Name: t_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_user_role (
    id character varying(20) NOT NULL,
    user_id character varying(20) NOT NULL,
    role_id character varying(20) NOT NULL,
    created_by character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted integer DEFAULT 0
);


--
-- Name: t_conversation t_conversation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_conversation
    ADD CONSTRAINT t_conversation_pkey PRIMARY KEY (id);


--
-- Name: t_conversation_summary t_conversation_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_conversation_summary
    ADD CONSTRAINT t_conversation_summary_pkey PRIMARY KEY (id);


--
-- Name: t_ingestion_pipeline_node t_ingestion_pipeline_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ingestion_pipeline_node
    ADD CONSTRAINT t_ingestion_pipeline_node_pkey PRIMARY KEY (id);


--
-- Name: t_ingestion_pipeline t_ingestion_pipeline_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ingestion_pipeline
    ADD CONSTRAINT t_ingestion_pipeline_pkey PRIMARY KEY (id);


--
-- Name: t_ingestion_task_node t_ingestion_task_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ingestion_task_node
    ADD CONSTRAINT t_ingestion_task_node_pkey PRIMARY KEY (id);


--
-- Name: t_ingestion_task t_ingestion_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ingestion_task
    ADD CONSTRAINT t_ingestion_task_pkey PRIMARY KEY (id);


--
-- Name: t_intent_node t_intent_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_intent_node
    ADD CONSTRAINT t_intent_node_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_base t_knowledge_base_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_base
    ADD CONSTRAINT t_knowledge_base_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_chunk t_knowledge_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_chunk
    ADD CONSTRAINT t_knowledge_chunk_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_document_chunk_log t_knowledge_document_chunk_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_document_chunk_log
    ADD CONSTRAINT t_knowledge_document_chunk_log_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_document t_knowledge_document_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_document
    ADD CONSTRAINT t_knowledge_document_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_document_schedule_exec t_knowledge_document_schedule_exec_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_document_schedule_exec
    ADD CONSTRAINT t_knowledge_document_schedule_exec_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_document_schedule t_knowledge_document_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_document_schedule
    ADD CONSTRAINT t_knowledge_document_schedule_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_vector t_knowledge_vector_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_vector
    ADD CONSTRAINT t_knowledge_vector_pkey PRIMARY KEY (id);


--
-- Name: t_message_feedback t_message_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_message_feedback
    ADD CONSTRAINT t_message_feedback_pkey PRIMARY KEY (id);


--
-- Name: t_message t_message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_message
    ADD CONSTRAINT t_message_pkey PRIMARY KEY (id);


--
-- Name: t_query_term_mapping t_query_term_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_query_term_mapping
    ADD CONSTRAINT t_query_term_mapping_pkey PRIMARY KEY (id);


--
-- Name: t_rag_evaluation_record t_rag_evaluation_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_rag_evaluation_record
    ADD CONSTRAINT t_rag_evaluation_record_pkey PRIMARY KEY (id);


--
-- Name: t_rag_trace_node t_rag_trace_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_rag_trace_node
    ADD CONSTRAINT t_rag_trace_node_pkey PRIMARY KEY (id);


--
-- Name: t_rag_trace_run t_rag_trace_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_rag_trace_run
    ADD CONSTRAINT t_rag_trace_run_pkey PRIMARY KEY (id);


--
-- Name: t_role_kb_relation t_role_kb_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_role_kb_relation
    ADD CONSTRAINT t_role_kb_relation_pkey PRIMARY KEY (id);


--
-- Name: t_role t_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_role
    ADD CONSTRAINT t_role_pkey PRIMARY KEY (id);


--
-- Name: t_sample_question t_sample_question_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_sample_question
    ADD CONSTRAINT t_sample_question_pkey PRIMARY KEY (id);


--
-- Name: sys_dept sys_dept_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_dept
    ADD CONSTRAINT sys_dept_pkey PRIMARY KEY (id);


--
-- Name: sys_dept uk_dept_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_dept
    ADD CONSTRAINT uk_dept_code UNIQUE (dept_code);


--
-- Name: t_user t_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_user
    ADD CONSTRAINT t_user_pkey PRIMARY KEY (id);


--
-- Name: t_user_role t_user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_user_role
    ADD CONSTRAINT t_user_role_pkey PRIMARY KEY (id);


--
-- Name: t_knowledge_base uk_collection_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_base
    ADD CONSTRAINT uk_collection_name UNIQUE (collection_name, deleted);


--
-- Name: t_conversation uk_conversation_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_conversation
    ADD CONSTRAINT uk_conversation_user UNIQUE (conversation_id, user_id);


--
-- Name: t_knowledge_document_schedule uk_doc_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_knowledge_document_schedule
    ADD CONSTRAINT uk_doc_id UNIQUE (doc_id);


--
-- Name: t_ingestion_pipeline uk_ingestion_pipeline_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ingestion_pipeline
    ADD CONSTRAINT uk_ingestion_pipeline_name UNIQUE (name, deleted);


--
-- Name: t_ingestion_pipeline_node uk_ingestion_pipeline_node; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ingestion_pipeline_node
    ADD CONSTRAINT uk_ingestion_pipeline_node UNIQUE (pipeline_id, node_id, deleted);


--
-- Name: t_message_feedback uk_msg_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_message_feedback
    ADD CONSTRAINT uk_msg_user UNIQUE (message_id, user_id);


--
-- Name: t_role uk_role_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_role
    ADD CONSTRAINT uk_role_name UNIQUE (name);


--
-- Name: t_rag_trace_run uk_run_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_rag_trace_run
    ADD CONSTRAINT uk_run_id UNIQUE (trace_id);


--
-- Name: t_rag_trace_node uk_run_node; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_rag_trace_node
    ADD CONSTRAINT uk_run_node UNIQUE (trace_id, node_id);


--
-- Name: t_user uk_user_username; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_user
    ADD CONSTRAINT uk_user_username UNIQUE (username);


--
-- Name: idx_conv_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conv_user ON public.t_conversation_summary USING btree (conversation_id, user_id);


--
-- Name: idx_conversation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversation_id ON public.t_message_feedback USING btree (conversation_id);


--
-- Name: idx_conversation_summary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversation_summary ON public.t_message USING btree (conversation_id, user_id, create_time);


--
-- Name: idx_conversation_user_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversation_user_time ON public.t_message USING btree (conversation_id, user_id, create_time);


--
-- Name: idx_doc_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_id ON public.t_knowledge_chunk USING btree (doc_id);


--
-- Name: idx_doc_id_exec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_id_exec ON public.t_knowledge_document_schedule_exec USING btree (doc_id);


--
-- Name: idx_doc_id_log; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_id_log ON public.t_knowledge_document_chunk_log USING btree (doc_id);


--
-- Name: idx_domain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_domain ON public.t_query_term_mapping USING btree (domain);


--
-- Name: idx_eval_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_conv ON public.t_rag_evaluation_record USING btree (conversation_id);


--
-- Name: idx_eval_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_status ON public.t_rag_evaluation_record USING btree (eval_status);


--
-- Name: idx_eval_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_trace ON public.t_rag_evaluation_record USING btree (trace_id);


--
-- Name: idx_ingestion_pipeline_node_pipeline; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ingestion_pipeline_node_pipeline ON public.t_ingestion_pipeline_node USING btree (pipeline_id);


--
-- Name: idx_ingestion_task_node_pipeline; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ingestion_task_node_pipeline ON public.t_ingestion_task_node USING btree (pipeline_id);


--
-- Name: idx_ingestion_task_node_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ingestion_task_node_status ON public.t_ingestion_task_node USING btree (status);


--
-- Name: idx_ingestion_task_node_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ingestion_task_node_task ON public.t_ingestion_task_node USING btree (task_id);


--
-- Name: idx_ingestion_task_pipeline; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ingestion_task_pipeline ON public.t_ingestion_task USING btree (pipeline_id);


--
-- Name: idx_ingestion_task_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ingestion_task_status ON public.t_ingestion_task USING btree (status);


--
-- Name: idx_kb_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_id ON public.t_knowledge_document USING btree (kb_id);


--
-- Name: idx_kb_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_name ON public.t_knowledge_base USING btree (name);


--
-- Name: idx_kv_embedding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kv_embedding ON public.t_knowledge_vector USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: idx_kv_embedding_hnsw; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kv_embedding_hnsw ON public.t_knowledge_vector USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: idx_kv_metadata; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kv_metadata ON public.t_knowledge_vector USING gin (metadata);


--
-- Name: idx_lock_until; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lock_until ON public.t_knowledge_document_schedule USING btree (lock_until);


--
-- Name: idx_next_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_next_run ON public.t_knowledge_document_schedule USING btree (next_run_time);


--
-- Name: idx_role_kb_kb_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_role_kb_kb_id ON public.t_role_kb_relation USING btree (kb_id);


--
-- Name: idx_role_kb_role_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_role_kb_role_id ON public.t_role_kb_relation USING btree (role_id);


--
-- Name: idx_sample_question_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sample_question_deleted ON public.t_sample_question USING btree (deleted);


--
-- Name: idx_schedule_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_schedule_time ON public.t_knowledge_document_schedule_exec USING btree (schedule_id, start_time);


--
-- Name: idx_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_source ON public.t_query_term_mapping USING btree (source_term);


--
-- Name: idx_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_id ON public.t_rag_trace_run USING btree (task_id);


--
-- Name: idx_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_id ON public.t_message_feedback USING btree (user_id);


--
-- Name: idx_user_id_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_id_trace ON public.t_rag_trace_run USING btree (user_id);


--
-- Name: idx_user_role_role_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_role_role_id ON public.t_user_role USING btree (role_id);


--
-- Name: idx_user_role_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_role_user_id ON public.t_user_role USING btree (user_id);


--
-- Name: idx_user_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_time ON public.t_conversation USING btree (user_id, last_time);


--
-- Name: idx_conversation_kb_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conversation_kb_user ON public.t_conversation USING btree (user_id, kb_id, last_time);


--
-- PostgreSQL database dump complete
--

\unrestrict Q3WEuQZlPJZgK67wCVaCHTg4cKeuc8ab0MS7bkdxzR1Eir5pwjL9010tfApDrNa

