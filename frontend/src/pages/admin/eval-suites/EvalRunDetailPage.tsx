import { useEffect, useMemo, useState } from "react";
import { useParams, Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid
} from "recharts";
import { toast } from "sonner";
import {
  getEvalRun,
  listEvalRunResults,
  getEvalResult
} from "@/services/evalSuiteService";
import type {
  EvalRunDetail,
  EvalResultSummary,
  EvalResult
} from "@/services/evalSuiteService";
import { RunStatusBadge } from "./components/RunStatusBadge";
import { fmt, parseMetricsSummary } from "./utils";

const METRIC_KEYS = [
  "faithfulness",
  "answerRelevancy",
  "contextPrecision",
  "contextRecall"
] as const;

type MetricKey = (typeof METRIC_KEYS)[number];

const METRIC_LABEL: Record<MetricKey, string> = {
  faithfulness: "Faithfulness",
  answerRelevancy: "Answer Relevancy",
  contextPrecision: "Context Precision",
  contextRecall: "Context Recall"
};

const SUMMARY_KEY: Record<MetricKey, string> = {
  faithfulness: "faithfulness",
  answerRelevancy: "answer_relevancy",
  contextPrecision: "context_precision",
  contextRecall: "context_recall"
};

function bucketize(rows: EvalResultSummary[], key: MetricKey) {
  const buckets = [0, 0, 0, 0, 0];
  for (const r of rows) {
    const v = r[key];
    if (v == null) continue;
    const idx = Math.min(4, Math.floor(v * 5));
    buckets[idx]++;
  }
  return buckets.map((c, i) => ({
    range: `${(i * 0.2).toFixed(1)}-${((i + 1) * 0.2).toFixed(1)}`,
    count: c
  }));
}

export function EvalRunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const [run, setRun] = useState<EvalRunDetail | null>(null);
  const [results, setResults] = useState<EvalResultSummary[]>([]);
  const [drilldown, setDrilldown] = useState<EvalResult | null>(null);
  const [drilldownLoading, setDrilldownLoading] = useState(false);

  useEffect(() => {
    if (!runId) return;
    Promise.all([getEvalRun(runId), listEvalRunResults(runId)])
      .then(([r, rs]) => {
        setRun(r);
        setResults(rs);
      })
      .catch((e) => toast.error(`加载失败：${(e as Error).message}`));
  }, [runId]);

  const openDrilldown = async (summary: EvalResultSummary) => {
    if (!runId) return;
    try {
      setDrilldownLoading(true);
      const full = await getEvalResult(runId, summary.id);
      setDrilldown(full);
    } catch (e: unknown) {
      toast.error(`drill-down 加载失败：${(e as Error).message}`);
    } finally {
      setDrilldownLoading(false);
    }
  };

  const summary = useMemo(
    () => parseMetricsSummary(run?.metricsSummary),
    [run]
  );

  const sorted = useMemo(
    () => [...results].sort((a, b) => (a.faithfulness ?? 999) - (b.faithfulness ?? 999)),
    [results]
  );

  // Pre-bucket once per results change instead of per render × 4 charts
  const bucketsByMetric = useMemo(
    () =>
      Object.fromEntries(METRIC_KEYS.map((k) => [k, bucketize(results, k)])) as Record<
        MetricKey,
        ReturnType<typeof bucketize>
      >,
    [results]
  );

  if (!run) {
    return <div className="p-6 text-slate-500">加载中...</div>;
  }

  return (
    <div className="space-y-4 p-6">
      <Link
        to="/admin/eval-suites?tab=runs"
        className="inline-flex items-center text-sm text-slate-600 hover:text-slate-900"
      >
        <ArrowLeft className="mr-1 h-4 w-4" />
        返回列表
      </Link>

      <div className="flex items-center gap-3">
        <h1 className="text-xl font-semibold">Run {run.id}</h1>
        <RunStatusBadge status={run.status} />
        <div className="text-sm text-slate-500">
          {run.succeededItems}/{run.totalItems} 成功（失败 {run.failedItems}）
        </div>
      </div>

      <div className="grid grid-cols-4 gap-3">
        {METRIC_KEYS.map((k) => {
          const v = summary[SUMMARY_KEY[k]];
          return (
            <div key={k} className="rounded-lg border bg-white p-4">
              <div className="text-xs text-slate-500">{METRIC_LABEL[k]}</div>
              <div className="mt-1 text-2xl font-semibold">
                {v == null ? "—" : v.toFixed(3)}
              </div>
            </div>
          );
        })}
      </div>

      <div className="grid grid-cols-2 gap-3">
        {METRIC_KEYS.map((k) => (
          <div key={k} className="rounded-lg border bg-white p-3">
            <div className="mb-2 text-xs text-slate-600">{METRIC_LABEL[k]} 分布</div>
            <ResponsiveContainer width="100%" height={140}>
              <BarChart data={bucketsByMetric[k]}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="range" tick={{ fontSize: 10 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} />
                <Tooltip />
                <Bar dataKey="count" fill="#6366f1" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        ))}
      </div>

      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="px-3 py-2 text-left">Gold</th>
              <th className="px-3 py-2 text-left">问题</th>
              <th className="px-3 py-2">F</th>
              <th className="px-3 py-2">AR</th>
              <th className="px-3 py-2">CP</th>
              <th className="px-3 py-2">CR</th>
              <th className="px-3 py-2">耗时</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((r) => (
              <tr
                key={r.id}
                className="cursor-pointer border-t hover:bg-slate-50"
                onClick={() => openDrilldown(r)}
              >
                <td className="px-3 py-2 font-mono text-xs">{r.goldItemId}</td>
                <td className="px-3 py-2 max-w-md truncate">
                  {r.error ? (
                    <span className="text-rose-600">⚠ {r.error}</span>
                  ) : (
                    r.question
                  )}
                </td>
                <td className="px-3 py-2 text-center">{fmt(r.faithfulness)}</td>
                <td className="px-3 py-2 text-center">{fmt(r.answerRelevancy)}</td>
                <td className="px-3 py-2 text-center">{fmt(r.contextPrecision)}</td>
                <td className="px-3 py-2 text-center">{fmt(r.contextRecall)}</td>
                <td className="px-3 py-2 text-center text-xs text-slate-500">
                  {r.elapsedMs ?? "—"}ms
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Dialog
        open={!!drilldown || drilldownLoading}
        onOpenChange={(v) => {
          if (!v) {
            setDrilldown(null);
            setDrilldownLoading(false);
          }
        }}
      >
        <DialogContent className="max-w-3xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Drill-down: {drilldown?.goldItemId ?? "加载中..."}</DialogTitle>
          </DialogHeader>
          {drilldownLoading && !drilldown && (
            <div className="text-sm text-slate-500">加载中...</div>
          )}
          {drilldown && (
            <div className="space-y-3 text-sm">
              <Section label="问题">{drilldown.question}</Section>
              <Section label="Ground Truth">{drilldown.groundTruthAnswer}</Section>
              <Section label="System Answer">
                {drilldown.error ? (
                  <span className="text-rose-600">⚠ {drilldown.error}</span>
                ) : (
                  drilldown.systemAnswer
                )}
              </Section>
              <div>
                <div className="mb-1 font-medium text-slate-700">检索到的 chunks</div>
                <div className="space-y-2">
                  {drilldown.retrievedChunks.map((c) => (
                    <div key={c.chunk_id} className="rounded border p-2">
                      <div className="flex items-center justify-between text-xs text-slate-500">
                        <span>{c.doc_name || c.doc_id || c.chunk_id}</span>
                        <span>
                          SL={c.security_level ?? 0} score=
                          {c.score?.toFixed(3) ?? "—"}
                        </span>
                      </div>
                      <div
                        className={
                          c.text === "[REDACTED]" ? "italic text-slate-400" : ""
                        }
                      >
                        {c.text}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Section({
  label,
  children
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <div className="mb-1 text-xs uppercase text-slate-500">{label}</div>
      <div className="rounded bg-slate-50 p-2">{children}</div>
    </div>
  );
}

