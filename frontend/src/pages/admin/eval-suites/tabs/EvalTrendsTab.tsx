import { useEffect, useMemo, useState } from "react";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Legend,
  CartesianGrid
} from "recharts";
import { listEvalRuns, getEvalRun } from "@/services/evalSuiteService";
import type { EvalRunSummary, EvalRunDetail } from "@/services/evalSuiteService";
import { SnapshotDiffViewer } from "../components/SnapshotDiffViewer";
import { toast } from "sonner";

interface ChartRow {
  ts: string;
  faithfulness: number | null;
  answer_relevancy: number | null;
  context_precision: number | null;
  context_recall: number | null;
}

export function EvalTrendsTab() {
  const [datasetId, setDatasetId] = useState("");
  const [runs, setRuns] = useState<EvalRunSummary[]>([]);
  const [beforeRun, setBeforeRun] = useState<EvalRunDetail | null>(null);
  const [afterRun, setAfterRun] = useState<EvalRunDetail | null>(null);

  useEffect(() => {
    if (!datasetId) {
      setRuns([]);
      return;
    }
    listEvalRuns(datasetId)
      .then((rs) => setRuns(rs.filter((r) => r.metricsSummary).reverse()))
      .catch((e) => toast.error(`加载失败：${(e as Error).message}`));
  }, [datasetId]);

  const chartData = useMemo<ChartRow[]>(() => {
    return runs.map((r) => {
      let m: Record<string, number | null> = {};
      try {
        m = r.metricsSummary ? JSON.parse(r.metricsSummary) : {};
      } catch {
        // ignore
      }
      return {
        ts: r.createTime?.slice(5, 16) || r.id.slice(-6),
        faithfulness: m.faithfulness ?? null,
        answer_relevancy: m.answer_relevancy ?? null,
        context_precision: m.context_precision ?? null,
        context_recall: m.context_recall ?? null
      };
    });
  }, [runs]);

  const handleSelectBefore = async (id: string) => {
    if (!id) {
      setBeforeRun(null);
      return;
    }
    try {
      setBeforeRun(await getEvalRun(id));
    } catch (e: unknown) {
      toast.error(`加载 run 失败：${(e as Error).message}`);
    }
  };

  const handleSelectAfter = async (id: string) => {
    if (!id) {
      setAfterRun(null);
      return;
    }
    try {
      setAfterRun(await getEvalRun(id));
    } catch (e: unknown) {
      toast.error(`加载 run 失败：${(e as Error).message}`);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <input
          className="rounded border px-2 py-1 text-sm"
          placeholder="datasetId..."
          value={datasetId}
          onChange={(e) => setDatasetId(e.target.value)}
        />
      </div>

      {chartData.length > 0 ? (
        <div className="rounded-lg border bg-white p-3">
          <div className="mb-2 text-sm font-medium">4 指标趋势</div>
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="ts" tick={{ fontSize: 10 }} />
              <YAxis domain={[0, 1]} tick={{ fontSize: 10 }} />
              <Tooltip />
              <Legend />
              <Line type="monotone" dataKey="faithfulness" stroke="#6366f1" />
              <Line type="monotone" dataKey="answer_relevancy" stroke="#10b981" />
              <Line type="monotone" dataKey="context_precision" stroke="#f59e0b" />
              <Line type="monotone" dataKey="context_recall" stroke="#ef4444" />
            </LineChart>
          </ResponsiveContainer>
        </div>
      ) : (
        <div className="rounded-lg border bg-slate-50 p-6 text-center text-sm text-slate-400">
          {datasetId ? "暂无评估运行" : "请先输入 datasetId"}
        </div>
      )}

      {runs.length >= 2 && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 text-sm">
            <span>对比：</span>
            <select
              className="rounded border px-2 py-1"
              value={beforeRun?.id || ""}
              onChange={(e) => handleSelectBefore(e.target.value)}
            >
              <option value="">选 run A</option>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.id.slice(-6)} · {r.createTime?.slice(5, 16)}
                </option>
              ))}
            </select>
            <span>→</span>
            <select
              className="rounded border px-2 py-1"
              value={afterRun?.id || ""}
              onChange={(e) => handleSelectAfter(e.target.value)}
            >
              <option value="">选 run B</option>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.id.slice(-6)} · {r.createTime?.slice(5, 16)}
                </option>
              ))}
            </select>
          </div>
          {(beforeRun || afterRun) && (
            <SnapshotDiffViewer
              before={beforeRun?.systemSnapshot}
              after={afterRun?.systemSnapshot}
            />
          )}
        </div>
      )}
    </div>
  );
}
