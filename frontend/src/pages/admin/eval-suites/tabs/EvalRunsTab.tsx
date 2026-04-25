import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Play, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { formatDateTime } from "@/utils/helpers";
import { listEvalRuns, getEvalRun } from "@/services/evalSuiteService";
import type { EvalRunSummary } from "@/services/evalSuiteService";
import { StartRunDialog } from "../components/StartRunDialog";
import { RunStatusBadge } from "../components/RunStatusBadge";

const TERMINAL_STATUSES = new Set(["SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELLED"]);
const POLL_INTERVAL_MS = 2_000;

export function EvalRunsTab() {
  const navigate = useNavigate();
  const [datasetFilter, setDatasetFilter] = useState<string>("");
  const [runs, setRuns] = useState<EvalRunSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);

  const reload = async (datasetId: string) => {
    if (!datasetId) {
      setRuns([]);
      return;
    }
    try {
      setLoading(true);
      const data = await listEvalRuns(datasetId);
      setRuns(data);
    } catch (e: unknown) {
      toast.error(`加载评估运行列表失败：${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    reload(datasetFilter);
  }, [datasetFilter]);

  const hasRunning = useMemo(
    () => runs.some((r) => !TERMINAL_STATUSES.has(r.status)),
    [runs]
  );

  useEffect(() => {
    if (!hasRunning) return;
    const t = setInterval(async () => {
      try {
        const next: EvalRunSummary[] = [];
        for (const r of runs) {
          if (TERMINAL_STATUSES.has(r.status)) {
            next.push(r);
          } else {
            const fresh = await getEvalRun(r.id);
            next.push(fresh ? { ...r, ...fresh } : r);
          }
        }
        setRuns(next);
      } catch {
        // 单次轮询失败忽略
      }
    }, POLL_INTERVAL_MS);
    return () => clearInterval(t);
  }, [hasRunning, runs]);

  const parseAvg = (m?: string | null) => {
    if (!m) return null;
    try {
      return JSON.parse(m) as Record<string, number | null>;
    } catch {
      return null;
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <input
            className="rounded border px-2 py-1 text-sm"
            placeholder="按 datasetId 过滤..."
            value={datasetFilter}
            onChange={(e) => setDatasetFilter(e.target.value)}
          />
          <Button
            variant="outline"
            size="sm"
            onClick={() => reload(datasetFilter)}
            disabled={loading}
          >
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
        <Button onClick={() => setDialogOpen(true)}>
          <Play className="mr-1 h-4 w-4" />
          开始评估
        </Button>
      </div>

      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-3 py-2">Run</th>
              <th className="px-3 py-2">Dataset</th>
              <th className="px-3 py-2">状态</th>
              <th className="px-3 py-2">进度</th>
              <th className="px-3 py-2">指标均值</th>
              <th className="px-3 py-2">触发时间</th>
            </tr>
          </thead>
          <tbody>
            {runs.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-8 text-center text-slate-400">
                  {datasetFilter ? "暂无评估运行" : "请先输入 datasetId 过滤"}
                </td>
              </tr>
            ) : (
              runs.map((r) => {
                const avg = parseAvg(r.metricsSummary);
                return (
                  <tr
                    key={r.id}
                    className="cursor-pointer border-t hover:bg-slate-50"
                    onClick={() => navigate(`/admin/eval-suites/runs/${r.id}`)}
                  >
                    <td className="px-3 py-2 font-mono text-xs">{r.id}</td>
                    <td className="px-3 py-2 text-xs">{r.datasetId}</td>
                    <td className="px-3 py-2">
                      <RunStatusBadge status={r.status} />
                    </td>
                    <td className="px-3 py-2">
                      {r.succeededItems}/{r.totalItems}（失败 {r.failedItems}）
                    </td>
                    <td className="px-3 py-2 text-xs">
                      {avg
                        ? `F=${fmt(avg.faithfulness)} AR=${fmt(avg.answer_relevancy)} CP=${fmt(avg.context_precision)} CR=${fmt(avg.context_recall)}`
                        : "—"}
                    </td>
                    <td className="px-3 py-2 text-xs">
                      {formatDateTime(r.createTime || "")}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      <StartRunDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onStarted={(runId) => {
          toast.success(`已触发 run ${runId}`);
          reload(datasetFilter);
        }}
      />
    </div>
  );
}

function fmt(n: number | null | undefined) {
  return n == null ? "—" : n.toFixed(3);
}
