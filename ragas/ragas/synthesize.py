"""Gold Set 合成：给定 chunk，用强模型反向生成 (Q, A) 对。

契约：只返回 LLM 产出的 source_chunk_id + question + answer；
      source_chunk_text / source_doc_id / source_doc_name 由 Java 侧冻结。
"""
import json
from dataclasses import dataclass


SYNTHESIS_PROMPT = """你是一位领域专家。请基于下面这段文档片段，生成一个**自然的用户问题**和**基于片段可直接回答的标准答案**。

要求：
1. 问题要像真实用户会问的，不要太学术
2. 答案必须能从片段中**直接**得出；不许编造片段外信息
3. 严格返回 JSON 对象，格式：{{"question": "...", "answer": "..."}}，不要额外解释

文档片段：
---
{text}
---

（文档来源：{doc_name}）
"""


class SynthesisError(Exception):
    """合成失败（LLM 返回非法 JSON / 字段空 / 超时等）。"""


@dataclass
class ChunkInput:
    id: str
    text: str
    doc_name: str


@dataclass
class SynthesizedItem:
    source_chunk_id: str
    question: str
    answer: str


def synthesize_one(chunk: ChunkInput, client, model: str) -> SynthesizedItem:
    """用 client（OpenAI 兼容）调 model 合成一条 Q-A。失败抛 SynthesisError。"""
    prompt = SYNTHESIS_PROMPT.format(text=chunk.text, doc_name=chunk.doc_name)
    response = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": prompt}],
        temperature=0.3,
        response_format={"type": "json_object"},
    )
    content = response.choices[0].message.content

    try:
        data = json.loads(content)
    except (json.JSONDecodeError, TypeError) as e:
        raise SynthesisError(f"LLM returned non-JSON for chunk {chunk.id}: {e}") from e

    question = (data.get("question") or "").strip()
    answer = (data.get("answer") or "").strip()

    if not question:
        raise SynthesisError(f"empty question for chunk {chunk.id}")
    if not answer:
        raise SynthesisError(f"empty answer for chunk {chunk.id}")

    return SynthesizedItem(
        source_chunk_id=chunk.id,
        question=question,
        answer=answer,
    )
