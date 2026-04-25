import { useEffect, useRef, useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { getSynthesisProgress, type SynthesisProgress } from "@/services/evalSuiteService";
import { useNavigate } from "react-router-dom";

interface Props {
  open: boolean;
  onClose: () => void;
  datasetId: string | null;
  onFinished?: () => void;
}

export function SynthesisProgressDialog({ open, onClose, datasetId, onFinished }: Props) {
  const [progress, setProgress] = useState<SynthesisProgress | null>(null);
  const timerRef = useRef<number | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!open || !datasetId) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const p = await getSynthesisProgress(datasetId);
        if (cancelled) return;
        setProgress(p);
        if (p.status === "COMPLETED" || p.status === "FAILED") {
          if (timerRef.current) window.clearTimeout(timerRef.current);
          if (p.status === "COMPLETED") onFinished?.();
          return;
        }
      } catch {
        // polling failures silent
      }
      timerRef.current = window.setTimeout(tick, 2000);
    };
    tick();
    return () => {
      cancelled = true;
      if (timerRef.current) window.clearTimeout(timerRef.current);
    };
  }, [open, datasetId, onFinished]);

  const pct = progress && progress.total > 0 ? Math.round(((progress.processed + progress.failed) / progress.total) * 100) : 0;

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>合成进度</DialogTitle>
          <DialogDescription>
            {progress?.status === "RUNNING" && "合成进行中，可关闭此窗口稍后回来查看"}
            {progress?.status === "COMPLETED" && "合成完成，可进入审核页"}
            {progress?.status === "FAILED" && "合成失败"}
            {(!progress || progress.status === "IDLE") && "等待进度……"}
          </DialogDescription>
        </DialogHeader>
        {progress && progress.total > 0 ? (
          <div className="space-y-2">
            <div className="h-2 w-full overflow-hidden rounded bg-slate-200">
              <div className="h-full bg-indigo-500 transition-all" style={{ width: `${pct}%` }} />
            </div>
            <p className="text-sm text-slate-500">
              已处理 {progress.processed} / {progress.total}（失败 {progress.failed}）
            </p>
          </div>
        ) : (
          <p className="text-sm text-slate-500">启动中……</p>
        )}
        {progress?.error && <p className="text-sm text-rose-600">{progress.error}</p>}
        <DialogFooter>
          {progress?.status === "COMPLETED" && datasetId && (
            <Button onClick={() => {
              onClose();
              navigate(`/admin/eval-suites/datasets/${datasetId}/review`);
            }}>去审核</Button>
          )}
          <Button variant="outline" onClick={onClose}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
