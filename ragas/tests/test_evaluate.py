"""ragas/evaluate 单元测试 — monkeypatch 掉真实 RAGAS 调用，不烧 token。"""
from unittest.mock import MagicMock

import pandas as pd
import pytest


@pytest.fixture(autouse=True)
def _stub_env(monkeypatch):
    """避免 Settings() 因缺 DASHSCOPE_API_KEY 报错（虽然本测试不直接构造 Settings）。"""
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-test")


def test_run_evaluate_maps_4_metrics(monkeypatch):
    """4 指标从 to_pandas() 映射到 MetricResult，result_id 一一对齐。"""
    from ragas.evaluate import EvaluateItem, EvaluateRequest, run_evaluate

    fake_df = pd.DataFrame(
        [
            {
                "faithfulness": 0.9123,
                "answer_relevancy": 0.8456,
                "context_precision": 0.7777,
                "context_recall": 0.6543,
            },
            {
                "faithfulness": 0.5,
                "answer_relevancy": 0.4,
                "context_precision": 0.3,
                "context_recall": 0.2,
            },
        ]
    )
    fake_result = MagicMock()
    fake_result.to_pandas.return_value = fake_df

    def fake_evaluate(dataset, metrics, llm, embeddings):
        # 验证传递正确
        assert dataset is not None
        assert len(metrics) == 4
        return fake_result

    monkeypatch.setattr("ragas.evaluate._ragas_evaluate", fake_evaluate)

    req = EvaluateRequest(
        items=[
            EvaluateItem(
                result_id="r1",
                question="Q1",
                contexts=["c1"],
                answer="A1",
                ground_truth="GT1",
            ),
            EvaluateItem(
                result_id="r2",
                question="Q2",
                contexts=["c2a", "c2b"],
                answer="A2",
                ground_truth="GT2",
            ),
        ]
    )

    resp = run_evaluate(req, evaluator_llm=MagicMock(), evaluator_embeddings=MagicMock())

    assert len(resp.results) == 2

    r1 = resp.results[0]
    assert r1.result_id == "r1"
    assert r1.faithfulness == 0.9123
    assert r1.answer_relevancy == 0.8456
    assert r1.context_precision == 0.7777
    assert r1.context_recall == 0.6543
    assert r1.error is None

    r2 = resp.results[1]
    assert r2.result_id == "r2"
    assert r2.faithfulness == 0.5
    assert r2.answer_relevancy == 0.4
    assert r2.context_precision == 0.3
    assert r2.context_recall == 0.2
    assert r2.error is None


def test_run_evaluate_batch_failure_fills_error_per_item(monkeypatch):
    """整批 RAGAS 异常 → 每条 item 拿到 batch error，4 指标全 None。"""
    from ragas.evaluate import EvaluateItem, EvaluateRequest, run_evaluate

    def boom(dataset, metrics, llm, embeddings):
        raise RuntimeError("evaluator died")

    monkeypatch.setattr("ragas.evaluate._ragas_evaluate", boom)

    req = EvaluateRequest(
        items=[
            EvaluateItem(
                result_id="r1",
                question="Q1",
                contexts=["c1"],
                answer="A1",
                ground_truth="GT1",
            ),
            EvaluateItem(
                result_id="r2",
                question="Q2",
                contexts=["c2"],
                answer="A2",
                ground_truth="GT2",
            ),
        ]
    )

    resp = run_evaluate(req, evaluator_llm=MagicMock(), evaluator_embeddings=MagicMock())

    assert len(resp.results) == 2
    for i, r in enumerate(resp.results):
        assert r.result_id == f"r{i+1}"
        assert r.faithfulness is None
        assert r.answer_relevancy is None
        assert r.context_precision is None
        assert r.context_recall is None
        assert r.error is not None
        assert r.error.startswith("batch failed: RuntimeError: ")
        assert "evaluator died" in r.error


def test_run_evaluate_empty_items_short_circuits(monkeypatch):
    """空 items 直接返回 results=[]，不调用 RAGAS。"""
    from ragas.evaluate import EvaluateRequest, run_evaluate

    called = {"hit": False}

    def should_not_be_called(*a, **kw):
        called["hit"] = True
        raise AssertionError("RAGAS evaluate must not be called for empty items")

    monkeypatch.setattr("ragas.evaluate._ragas_evaluate", should_not_be_called)

    req = EvaluateRequest(items=[])
    resp = run_evaluate(req, evaluator_llm=MagicMock(), evaluator_embeddings=MagicMock())

    assert resp.results == []
    assert called["hit"] is False


def test_safe_float_handles_nan_and_none():
    """补充：_safe_float 单测 — None / NaN / 字符串 / 整数 / 浮点数边界。"""
    import math
    from ragas.evaluate import _safe_float

    assert _safe_float(None) is None
    assert _safe_float(float("nan")) is None
    assert _safe_float(float("inf")) is None
    assert _safe_float("not a number") is None
    assert _safe_float(0.123456) == 0.1235  # round to 4
    assert _safe_float(1) == 1.0
    assert _safe_float(True) is None  # bool 当无效
