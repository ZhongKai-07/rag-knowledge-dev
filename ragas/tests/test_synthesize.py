from unittest.mock import MagicMock

import pytest


def _fake_llm_response(content: str):
    """构造 OpenAI SDK 风格的响应对象（至少具备 choices[0].message.content）。"""
    response = MagicMock()
    response.choices = [MagicMock()]
    response.choices[0].message.content = content
    return response


def test_synthesize_one_parses_json_envelope():
    from ragas.synthesize import synthesize_one, ChunkInput

    client = MagicMock()
    client.chat.completions.create.return_value = _fake_llm_response(
        '{"question": "如何配置 X？", "answer": "在 yaml 里设 X=y。"}'
    )

    chunk = ChunkInput(id="c1", text="X 是一个参数...", doc_name="manual.pdf")
    result = synthesize_one(chunk, client, model="qwen-max")

    assert result.source_chunk_id == "c1"
    assert result.question == "如何配置 X？"
    assert result.answer == "在 yaml 里设 X=y。"


def test_synthesize_one_bad_json_raises():
    from ragas.synthesize import synthesize_one, ChunkInput, SynthesisError

    client = MagicMock()
    client.chat.completions.create.return_value = _fake_llm_response(
        "这不是 JSON"
    )
    chunk = ChunkInput(id="c2", text="...", doc_name="x.pdf")

    with pytest.raises(SynthesisError):
        synthesize_one(chunk, client, model="qwen-max")


def test_synthesize_one_empty_answer_raises():
    from ragas.synthesize import synthesize_one, ChunkInput, SynthesisError

    client = MagicMock()
    client.chat.completions.create.return_value = _fake_llm_response(
        '{"question": "XX？", "answer": ""}'
    )
    chunk = ChunkInput(id="c3", text="...", doc_name="x.pdf")

    with pytest.raises(SynthesisError, match="empty answer"):
        synthesize_one(chunk, client, model="qwen-max")
