"""RAG 输出 RAGAS 评测：4 指标 (faithfulness / answer_relevancy / context_precision / context_recall)。

契约（与 Java 侧 EvaluateRequest/Response 对齐，T9 重命名后 result_id 携带 EvalResultDO 雪花 id）：

请求：{"items": [{"result_id", "question", "contexts": [...], "answer", "ground_truth"}]}
响应：{"results": [{"result_id", "faithfulness", "answer_relevancy",
                   "context_precision", "context_recall", "error"}]}

行为约定：
- 空 items 短路返回 {"results": []}
- 整批失败：所有 item 的 error = "batch failed: {ExcType}: {msg}"，4 个指标置 None
- 单条 RAGAS 计算返回 NaN：对应指标置 None，error 留 None（不算失败）
- 指标四舍五入到 4 位小数；非数值 / NaN → None

包名冲突说明
------------
本仓库的本地 Python 包也叫 `ragas`（与 PyPI 上的 `ragas` 评测库重名），cwd 落在
`ragas/` 父目录时，`from ragas.X import Y` 会优先解析到本地包，导致 PyPI 子模块（如
`ragas.dataset_schema` / `ragas.llms` / `ragas.embeddings`）无法 import。

解决：在本模块加载时临时清掉 sys.modules / sys.path 里的本地 ragas 干扰，按文件路径
直接加载 PyPI ragas 的几个目标符号，再恢复 sys.path 和本地包状态。这样：
  - 测试里 `from ragas.evaluate import ...` 仍走本地包；
  - `_ragas_evaluate` / `_EvaluationDataset` / 4 个 metric / 2 个 wrapper 已经是 PyPI 的对象。
"""
from __future__ import annotations

import contextlib
import logging
import math
import os
import sys
from typing import List, Optional

from pydantic import BaseModel, Field


logger = logging.getLogger(__name__)


# ============================================
# PyPI ragas 加载（绕过本地包名冲突）
# ============================================
def _load_pypi_ragas_symbols():
    """临时屏蔽本地 ragas 包，从 PyPI ragas 加载需要的符号。

    返回 (ragas_evaluate_fn, EvaluationDataset, [4 个 metric], LangchainLLMWrapper, LangchainEmbeddingsWrapper)。
    返回后 sys.path / sys.modules 状态恢复，本地 ragas 仍可被其他模块正常 import。
    """
    import importlib.metadata as md

    dist = md.distribution("ragas")
    pypi_dir = None
    for f in (dist.files or []):
        s = str(f).replace(os.sep, "/")
        if s.endswith("ragas/__init__.py"):
            pypi_dir = os.path.dirname(str(dist.locate_file(f)))
            break
    if not pypi_dir:
        raise RuntimeError(
            "PyPI ragas package directory could not be located via importlib.metadata"
        )

    saved_sys_path = list(sys.path)
    saved_modules = {
        k: v for k, v in sys.modules.items()
        if k == "ragas" or k.startswith("ragas.")
    }
    site = os.path.dirname(pypi_dir)

    try:
        # 1) 清空 ragas* 模块缓存
        for k in list(sys.modules.keys()):
            if k == "ragas" or k.startswith("ragas."):
                del sys.modules[k]

        # 2) 把 site-packages 顶到最前，过滤掉所有"含 ragas 子目录但不是 PyPI 那个"的路径
        new_path = [site]
        for p in saved_sys_path:
            if not p or p == site:
                continue
            candidate = os.path.join(p, "ragas", "__init__.py")
            if os.path.isfile(candidate) and os.path.dirname(candidate) != pypi_dir:
                continue  # 这条路径会 shadow，跳过
            new_path.append(p)
        sys.path[:] = new_path

        # 3) 在干净的环境里从 PyPI ragas 抓符号
        from ragas import evaluate as _ragas_evaluate_fn
        from ragas.dataset_schema import EvaluationDataset as _EvaluationDataset
        from ragas.metrics import (
            answer_relevancy as _answer_relevancy,
            context_precision as _context_precision,
            context_recall as _context_recall,
            faithfulness as _faithfulness,
        )
        from ragas.embeddings import LangchainEmbeddingsWrapper as _LangchainEmbeddingsWrapper
        from ragas.llms import LangchainLLMWrapper as _LangchainLLMWrapper

        return {
            "evaluate": _ragas_evaluate_fn,
            "EvaluationDataset": _EvaluationDataset,
            "faithfulness": _faithfulness,
            "answer_relevancy": _answer_relevancy,
            "context_precision": _context_precision,
            "context_recall": _context_recall,
            "LangchainLLMWrapper": _LangchainLLMWrapper,
            "LangchainEmbeddingsWrapper": _LangchainEmbeddingsWrapper,
        }
    finally:
        # 4) 恢复 sys.path 和本地 ragas 模块缓存（让其他 import 'ragas.*' 仍走本地包）
        sys.path[:] = saved_sys_path
        for k in list(sys.modules.keys()):
            if k == "ragas" or k.startswith("ragas."):
                del sys.modules[k]
        for k, v in saved_modules.items():
            sys.modules[k] = v


_pypi = _load_pypi_ragas_symbols()
# PyPI ragas 顶层目录路径，运行评测时临时把 sys.path 顶部换成它，让 RAGAS 内部
# 的 lazy `import ragas.prompt` / `import ragas.metrics._x` 能解析到 PyPI 而不是本地包。
_PYPI_RAGAS_DIR = os.path.dirname(_pypi.pop("__pypi_dir__", "")) if "__pypi_dir__" in _pypi else None


def _locate_pypi_dir() -> str:
    import importlib.metadata as md

    dist = md.distribution("ragas")
    for f in (dist.files or []):
        s = str(f).replace(os.sep, "/")
        if s.endswith("ragas/__init__.py"):
            return os.path.dirname(os.path.dirname(str(dist.locate_file(f))))
    raise RuntimeError("PyPI ragas not locatable")


_PYPI_SITE = _locate_pypi_dir()


@contextlib.contextmanager
def _pypi_ragas_active():
    """在 with 块内让 sys.modules['ragas*'] 指向 PyPI ragas，退出时还原本地 ragas。

    必要性：RAGAS 0.2.x 内部在 evaluate() 调用期间会 lazy `import ragas.prompt` /
    `import ragas.metrics._faithfulness` 等。若 sys.modules['ragas'] 是本地包就 ModuleNotFoundError。
    """
    saved_path = list(sys.path)
    saved_modules = {
        k: v for k, v in sys.modules.items()
        if k == "ragas" or k.startswith("ragas.")
    }
    try:
        for k in list(sys.modules):
            if k == "ragas" or k.startswith("ragas."):
                del sys.modules[k]
        new_path = [_PYPI_SITE]
        for p in saved_path:
            if not p or p == _PYPI_SITE:
                continue
            shadow = os.path.join(p, "ragas", "__init__.py")
            pypi_init = os.path.join(_PYPI_SITE, "ragas", "__init__.py")
            if os.path.isfile(shadow) and os.path.normcase(shadow) != os.path.normcase(pypi_init):
                continue
            new_path.append(p)
        sys.path[:] = new_path
        # 触发 PyPI ragas 重新进入 sys.modules
        import ragas  # noqa: F401
        yield
    finally:
        sys.path[:] = saved_path
        for k in list(sys.modules):
            if k == "ragas" or k.startswith("ragas."):
                del sys.modules[k]
        for k, v in saved_modules.items():
            sys.modules[k] = v

# 模块级符号；测试用 monkeypatch.setattr("ragas.evaluate._ragas_evaluate", ...) 替换。
_ragas_evaluate = _pypi["evaluate"]
_EvaluationDataset = _pypi["EvaluationDataset"]
_faithfulness = _pypi["faithfulness"]
_answer_relevancy = _pypi["answer_relevancy"]
_context_precision = _pypi["context_precision"]
_context_recall = _pypi["context_recall"]
LangchainLLMWrapper = _pypi["LangchainLLMWrapper"]
LangchainEmbeddingsWrapper = _pypi["LangchainEmbeddingsWrapper"]


# ============================================
# Pydantic models
# ============================================
class EvaluateItem(BaseModel):
    result_id: str = Field(..., description="EvalResultDO 雪花 id（T9 后从 gold_item_id 改名）")
    question: str
    contexts: List[str] = Field(default_factory=list)
    answer: str
    ground_truth: str


class EvaluateRequest(BaseModel):
    items: List[EvaluateItem] = Field(default_factory=list)


class MetricResult(BaseModel):
    result_id: str
    faithfulness: Optional[float] = None
    answer_relevancy: Optional[float] = None
    context_precision: Optional[float] = None
    context_recall: Optional[float] = None
    error: Optional[str] = None


class EvaluateResponse(BaseModel):
    results: List[MetricResult] = Field(default_factory=list)


# ============================================
# Helpers
# ============================================
def _safe_float(v) -> Optional[float]:
    """把 RAGAS DataFrame cell 转成 None / round(4)。

    None / NaN / Inf / 非数值 → None；数值 → round 到 4 位小数。
    """
    if v is None:
        return None
    if isinstance(v, bool):
        # bool 是 int 的子类，但语义上不该当指标值
        return None
    if isinstance(v, (int, float)):
        f = float(v)
        if math.isnan(f) or math.isinf(f):
            return None
        return round(f, 4)
    # 其他类型（含 numpy 标量等）尝试转 float
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if math.isnan(f) or math.isinf(f):
        return None
    return round(f, 4)


def _build_dataset(items: List[EvaluateItem]):
    """组装 RAGAS 0.2.x EvaluationDataset：键名 user_input / retrieved_contexts / response / reference。"""
    samples = [
        {
            "user_input": it.question,
            "retrieved_contexts": list(it.contexts or []),
            "response": it.answer,
            "reference": it.ground_truth,
        }
        for it in items
    ]
    return _EvaluationDataset.from_list(samples)


# ============================================
# Main entrypoint
# ============================================
def run_evaluate(
    request: EvaluateRequest,
    evaluator_llm,
    evaluator_embeddings,
) -> EvaluateResponse:
    """对 request.items 跑 RAGAS 4 指标。

    单批失败不影响契约：异常被吃掉，所有 item 的 error 字段填 batch 错误信息。
    """
    items = request.items
    if not items:
        return EvaluateResponse(results=[])

    dataset = _build_dataset(items)
    metrics = [_faithfulness, _answer_relevancy, _context_precision, _context_recall]

    try:
        with _pypi_ragas_active():
            result = _ragas_evaluate(
                dataset=dataset,
                metrics=metrics,
                llm=evaluator_llm,
                embeddings=evaluator_embeddings,
            )
        df = result.to_pandas()
    except Exception as e:  # noqa: BLE001 — 这里就是要兜底整批
        err = f"batch failed: {type(e).__name__}: {e}"
        logger.exception("[evaluate] batch failed: %s", err)
        return EvaluateResponse(
            results=[
                MetricResult(result_id=it.result_id, error=err)
                for it in items
            ]
        )

    # 行序假定与 items 一致（RAGAS 0.2.x 保持输入顺序）。
    # 防御：若 DataFrame 行数与 items 不等，按 min 长度对齐，缺失的 item 标 batch error。
    n = min(len(df), len(items))
    results: List[MetricResult] = []
    for i, it in enumerate(items):
        if i >= n:
            results.append(MetricResult(
                result_id=it.result_id,
                error=f"batch failed: row {i} missing in RAGAS output (got {len(df)} rows for {len(items)} items)",
            ))
            continue
        row = df.iloc[i]
        results.append(MetricResult(
            result_id=it.result_id,
            faithfulness=_safe_float(row.get("faithfulness")),
            answer_relevancy=_safe_float(row.get("answer_relevancy")),
            context_precision=_safe_float(row.get("context_precision")),
            context_recall=_safe_float(row.get("context_recall")),
            error=None,
        ))

    return EvaluateResponse(results=results)
