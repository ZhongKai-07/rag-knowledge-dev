import { useEffect, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { listGoldDatasets, startEvalRun } from "@/services/evalSuiteService";
import type { GoldDataset } from "@/services/evalSuiteService";
import { toast } from "sonner";

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  onStarted: (runId: string, datasetId: string) => void;
}

export function StartRunDialog({ open, onOpenChange, onStarted }: Props) {
  const [datasets, setDatasets] = useState<GoldDataset[]>([]);
  const [selected, setSelected] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    listGoldDatasets(undefined, "ACTIVE")
      .then(setDatasets)
      .catch(() => toast.error("加载 ACTIVE 数据集失败"));
  }, [open]);

  const handleStart = async () => {
    if (!selected) {
      toast.warning("请选择数据集");
      return;
    }
    try {
      setSubmitting(true);
      const runId = await startEvalRun(selected);
      onStarted(runId, selected);
      onOpenChange(false);
      setSelected("");
    } catch (e: unknown) {
      toast.error(`触发失败：${(e as Error).message}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>开始新评估运行</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <div className="text-sm text-slate-600">
            评估走真实 RAG 链路，每条 gold item 会触发一次 LLM 调用 + 一次 RAGAS 4 指标评分。
          </div>
          <Select value={selected} onValueChange={setSelected}>
            <SelectTrigger>
              <SelectValue placeholder="选择 ACTIVE 数据集" />
            </SelectTrigger>
            <SelectContent>
              {datasets.length === 0 ? (
                <SelectItem value="__none__" disabled>
                  无 ACTIVE 数据集
                </SelectItem>
              ) : (
                datasets.map((d) => (
                  <SelectItem key={d.id} value={d.id}>
                    {d.name} （{d.itemCount} 条）
                  </SelectItem>
                ))
              )}
            </SelectContent>
          </Select>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={submitting}>
            取消
          </Button>
          <Button
            onClick={handleStart}
            disabled={!selected || selected === "__none__" || submitting}
          >
            {submitting ? "提交中..." : "开始评估"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
