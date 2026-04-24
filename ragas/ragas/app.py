"""ragent-eval FastAPI 服务入口。

PR E1：只实现 /health 和 /synthesize（真 LLM 调用，不落库）。
PR E3 再加 /evaluate。
"""
from functools import lru_cache
from typing import List

from fastapi import FastAPI
from openai import OpenAI
from pydantic import BaseModel

from ragas.settings import Settings
from ragas.synthesize import ChunkInput, SynthesisError, synthesize_one


app = FastAPI(title="ragent-eval", version="0.1.0")


@lru_cache(maxsize=1)
def _settings() -> Settings:
    return Settings()


@lru_cache(maxsize=1)
def _llm_client() -> OpenAI:
    s = _settings()
    return OpenAI(api_key=s.dashscope_api_key, base_url=s.dashscope_base_url)


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
