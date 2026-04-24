from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-test")
    from fastapi.testclient import TestClient
    from ragas.app import app
    return TestClient(app)


def test_health_returns_ok(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert "ragas_version" in body
    assert "evaluator_llm" in body


def test_synthesize_returns_items_for_given_chunks(client):
    """一条成功 + 一条失败的混合批，返回 items[0] 且 failed_chunk_ids=[c2]."""
    from ragas.synthesize import SynthesizedItem, SynthesisError

    def fake_synthesize_one(chunk, client_arg, model):
        if chunk.id == "c1":
            return SynthesizedItem(
                source_chunk_id="c1",
                question="X 是啥？",
                answer="X 是 Y。",
            )
        raise SynthesisError(f"forced failure for {chunk.id}")

    with patch("ragas.app.synthesize_one", side_effect=fake_synthesize_one):
        r = client.post(
            "/synthesize",
            json={
                "chunks": [
                    {"id": "c1", "text": "X 是 Y 的别名...", "doc_name": "a.pdf"},
                    {"id": "c2", "text": "异常触发片段", "doc_name": "b.pdf"},
                ]
            },
        )

    assert r.status_code == 200
    body = r.json()
    assert len(body["items"]) == 1
    assert body["items"][0]["source_chunk_id"] == "c1"
    assert body["items"][0]["question"] == "X 是啥？"
    assert body["items"][0]["answer"] == "X 是 Y。"
    assert body["failed_chunk_ids"] == ["c2"]
