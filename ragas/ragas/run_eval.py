import argparse
import asyncio
import csv
import json
import math
import sys
from pathlib import Path

from openai import AsyncOpenAI
from ragas.embeddings.base import embedding_factory
from ragas.llms import llm_factory
from ragas.metrics.collections import AnswerRelevancy, Faithfulness

# 百炼 API 配置
API_KEY = "sk-ccf44e34ec0f42e98e0bafde3efe7e50"
BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
CHAT_MODEL = "qwen3.5-flash"
EMBEDDING_MODEL = "text-embedding-v3"
DEFAULT_INPUT_FILE = "ragas-export-2026-04-06.json"
DEFAULT_OUTPUT_FILE = "ragas-result.csv"


def configure_stdout() -> None:
    """Avoid Windows console encoding crashes when printing Chinese or NBSP."""
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def build_paths() -> tuple[Path, Path]:
    script_dir = Path(__file__).resolve().parent
    log_dir = script_dir / "log"
    return script_dir, log_dir


def parse_args(log_dir: Path) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run RAGAS evaluation from exported JSON.")
    parser.add_argument(
        "--input",
        default=str(log_dir / DEFAULT_INPUT_FILE),
        help="Path to the exported evaluation JSON file.",
    )
    parser.add_argument(
        "--output",
        default=str(log_dir / DEFAULT_OUTPUT_FILE),
        help="Path to the CSV file for saving results.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Only evaluate the first N records for quick debugging.",
    )
    return parser.parse_args()


def normalize_context_item(item: object) -> str:
    if isinstance(item, str):
        return item
    return json.dumps(item, ensure_ascii=False)


def load_records(data_path: Path, limit: int | None = None) -> list[dict[str, object]]:
    if not data_path.exists():
        raise FileNotFoundError(f"评测数据文件不存在: {data_path}")

    with data_path.open("r", encoding="utf-8") as f:
        raw = json.load(f)

    if not isinstance(raw, list):
        raise ValueError("导出的 JSON 顶层必须是 list。")

    records: list[dict[str, object]] = []
    for idx, item in enumerate(raw, start=1):
        if not isinstance(item, dict):
            raise ValueError(f"第 {idx} 条记录不是对象: {item!r}")

        question = item.get("question")
        answer = item.get("answer")
        contexts = item.get("contexts") or []

        if not isinstance(question, str) or not question.strip():
            raise ValueError(f"第 {idx} 条记录缺少有效的 question。")
        if not isinstance(answer, str) or not answer.strip():
            raise ValueError(f"第 {idx} 条记录缺少有效的 answer。")
        if not isinstance(contexts, list):
            raise ValueError(f"第 {idx} 条记录的 contexts 不是 list。")

        normalized_contexts = [normalize_context_item(context) for context in contexts]
        if not normalized_contexts:
            raise ValueError(f"第 {idx} 条记录的 contexts 为空，faithfulness 无法计算。")

        records.append(
            {
                "question": question,
                "contexts": normalized_contexts,
                "answer": answer,
                "groundTruths": item.get("groundTruths", []),
            }
        )

    if limit is not None:
        if limit <= 0:
            raise ValueError("--limit 必须大于 0。")
        records = records[:limit]

    return records


def format_score(value: object) -> str:
    if isinstance(value, float) and math.isnan(value):
        return "nan"
    if isinstance(value, (int, float)):
        return f"{value:.4f}"
    return str(value)


def compute_average(rows: list[dict[str, object]], key: str) -> float:
    values = [
        float(value)
        for row in rows
        if isinstance((value := row.get(key)), (int, float)) and not math.isnan(value)
    ]
    if not values:
        return float("nan")
    return sum(values) / len(values)


def save_rows(output_path: Path, rows: list[dict[str, object]]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "question",
        "context_count",
        "answer",
        "faithfulness",
        "answer_relevancy",
        "error",
    ]
    with output_path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


async def evaluate_records(records: list[dict[str, object]]) -> list[dict[str, object]]:
    client = AsyncOpenAI(api_key=API_KEY, base_url=BASE_URL)
    evaluator_llm = llm_factory(CHAT_MODEL, client=client, max_tokens=4096)
    evaluator_embeddings = embedding_factory(
        "openai",
        model=EMBEDDING_MODEL,
        client=client,
    )

    faithfulness_metric = Faithfulness(llm=evaluator_llm)
    answer_relevancy_metric = AnswerRelevancy(
        llm=evaluator_llm,
        embeddings=evaluator_embeddings,
        strictness=1,
    )

    print(
        f"开始评测: model={CHAT_MODEL}, embedding={EMBEDDING_MODEL}, "
        f"metrics={[faithfulness_metric.name, answer_relevancy_metric.name]}"
    )

    rows: list[dict[str, object]] = []
    total = len(records)
    for idx, record in enumerate(records, start=1):
        print(f"[{idx}/{total}] 正在评测: {record['question']}")

        row: dict[str, object] = {
            "question": record["question"],
            "context_count": len(record["contexts"]),
            "answer": record["answer"],
            "faithfulness": float("nan"),
            "answer_relevancy": float("nan"),
            "error": "",
        }

        try:
            faithfulness_result = await faithfulness_metric.ascore(
                user_input=str(record["question"]),
                response=str(record["answer"]),
                retrieved_contexts=list(record["contexts"]),
            )
            row["faithfulness"] = float(faithfulness_result.value)

            answer_relevancy_result = await answer_relevancy_metric.ascore(
                user_input=str(record["question"]),
                response=str(record["answer"]),
            )
            row["answer_relevancy"] = float(answer_relevancy_result.value)
        except Exception as exc:
            row["error"] = f"{type(exc).__name__}: {exc}"

        rows.append(row)
        print(
            f"  faithfulness={format_score(row['faithfulness'])}, "
            f"answer_relevancy={format_score(row['answer_relevancy'])}"
        )

    return rows


def main() -> None:
    configure_stdout()
    _, log_dir = build_paths()
    args = parse_args(log_dir)
    data_path = Path(args.input).resolve()
    output_path = Path(args.output).resolve()

    records = load_records(data_path, args.limit)
    print(f"已加载 {len(records)} 条评测记录: {data_path}")

    rows = asyncio.run(evaluate_records(records))

    print("\n===== RAGAS 评测结果 =====")
    print(
        {
            "faithfulness": compute_average(rows, "faithfulness"),
            "answer_relevancy": compute_average(rows, "answer_relevancy"),
        }
    )

    print("\n===== 逐条评分 =====")
    for idx, row in enumerate(rows, start=1):
        print(
            f"[{idx}] {row['question']}\n"
            f"  faithfulness={format_score(row['faithfulness'])}, "
            f"answer_relevancy={format_score(row['answer_relevancy'])}"
        )
        if row["error"]:
            print(f"  error={row['error']}")

    save_rows(output_path, rows)
    print(f"\n结果已保存至: {output_path}")


if __name__ == "__main__":
    main()
