"""ragent-eval FastAPI 服务入口。

- /health      存活探针
- /synthesize  Gold Set 反向合成（强模型）
- /evaluate    RAG 输出 RAGAS 4 指标评测（评测模型 + embedding）
"""
from functools import lru_cache
from typing import List, Optional

from fastapi import FastAPI, Header
from openai import OpenAI
from pydantic import BaseModel

from ragas.evaluate import EvaluateRequest, EvaluateResponse, run_evaluate
from ragas.settings import Settings
from ragas.synthesize import ChunkInput, SynthesisError, synthesize_one


app = FastAPI(title="ragent-eval", version="0.1.0")
logger = __import__("logging").getLogger(__name__)


@lru_cache(maxsize=1)
def _settings() -> Settings:
    return Settings()


@lru_cache(maxsize=1)
def _llm_client() -> OpenAI:
    s = _settings()
    return OpenAI(api_key=s.dashscope_api_key, base_url=s.dashscope_base_url)


@lru_cache(maxsize=1)
def _evaluator_llm():
    """RAGAS 评测用 LLM：LangchainLLMWrapper(ChatOpenAI(...)) 包 OpenAI 兼容端点（百炼）。

    LangchainLLMWrapper 从 PyPI ragas 拿，路径 import 由 ragas.evaluate 模块统一处理（避包名冲突）。
    """
    from langchain_openai import ChatOpenAI

    from ragas.evaluate import LangchainLLMWrapper

    s = _settings()
    return LangchainLLMWrapper(
        ChatOpenAI(
            model=s.evaluator_chat_model,
            openai_api_key=s.dashscope_api_key,
            openai_api_base=s.dashscope_base_url,
        )
    )


@lru_cache(maxsize=1)
def _evaluator_embeddings():
    """RAGAS 评测用 Embedding：LangchainEmbeddingsWrapper(OpenAIEmbeddings(...))."""
    from langchain_openai import OpenAIEmbeddings

    from ragas.evaluate import LangchainEmbeddingsWrapper

    s = _settings()
    return LangchainEmbeddingsWrapper(
        OpenAIEmbeddings(
            model=s.evaluator_embedding_model,
            openai_api_key=s.dashscope_api_key,
            openai_api_base=s.dashscope_base_url,
        )
    )


# ============================================
# /health
# ============================================
class HealthResponse(BaseModel):
    status: str
    ragas_version: str
    evaluator_llm: str


@app.get("/health", response_model=HealthResponse)
def health():
    try:
        import ragas as ragas_pkg
        ragas_version = getattr(ragas_pkg, "__version__", "unknown")
    except Exception:
        ragas_version = "unknown"
    return HealthResponse(
        status="ok",
        ragas_version=ragas_version,
        evaluator_llm=_settings().evaluator_chat_model,
    )


# ============================================
# /synthesize
# ============================================
class SynthChunkIn(BaseModel):
    id: str
    text: str
    doc_name: str


class SynthesizeRequest(BaseModel):
    chunks: List[SynthChunkIn]


class SynthItemOut(BaseModel):
    source_chunk_id: str
    question: str
    answer: str


class SynthesizeResponse(BaseModel):
    items: List[SynthItemOut]
    failed_chunk_ids: List[str]


@app.post("/synthesize", response_model=SynthesizeResponse)
def synthesize(request: SynthesizeRequest):
    settings = _settings()
    client = _llm_client()

    items: List[SynthItemOut] = []
    failed: List[str] = []

    for c in request.chunks:
        chunk = ChunkInput(id=c.id, text=c.text, doc_name=c.doc_name)
        try:
            result = synthesize_one(chunk, client, model=settings.synthesis_strong_model)
            items.append(SynthItemOut(
                source_chunk_id=result.source_chunk_id,
                question=result.question,
                answer=result.answer,
            ))
        except SynthesisError as e:
            failed.append(chunk.id)

    return SynthesizeResponse(items=items, failed_chunk_ids=failed)


# ============================================
# /evaluate
# ============================================
@app.post("/evaluate", response_model=EvaluateResponse)
def evaluate(req: EvaluateRequest, x_eval_run_id: Optional[str] = Header(default=None)):
    """跑 RAGAS 4 指标评测。

    X-Eval-Run-Id 用于日志关联（Java 侧 EvalRunDO 雪花 id）。失败不抛 5xx，
    错误内联在 results[].error，HTTP 始终 200。
    """
    logger.info("[evaluate] run_id=%s items=%d", x_eval_run_id, len(req.items))
    return run_evaluate(req, _evaluator_llm(), _evaluator_embeddings())
