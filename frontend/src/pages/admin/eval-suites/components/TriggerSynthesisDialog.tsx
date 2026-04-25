import { useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { triggerSynthesis } from "@/services/evalSuiteService";
import { toast } from "sonner";

interface Props {
  open: boolean;
  onClose: () => void;
  datasetId: string | null;
  onStarted: (datasetId: string) => void;
}

export function TriggerSynthesisDialog({ open, onClose, datasetId, onStarted }: Props) {
  const [count, setCount] = useState<number>(50);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!datasetId) return;
    if (!Number.isFinite(count) || count <= 0 || count > 500) {
      toast.error("合成条数需在 1-500 之间");
      return;
    }
    try {
      setSubmitting(true);
      await triggerSynthesis(datasetId, count);
      toast.success("合成任务已提交");
      onStarted(datasetId);
      onClose();
    } catch (e) {
      toast.error((e as Error).message || "触发合成失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>触发合成</DialogTitle>
          <DialogDescription>
            后端将随机采样 KB 中成功入库的 chunk，调百炼 qwen-max 合成 Q-A 对。合成期间不得再次触发。
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <label className="text-sm font-medium">合成条数</label>
          <Input
            type="number"
            min={1}
            max={500}
            value={count}
            onChange={(e) => setCount(parseInt(e.target.value, 10))}
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button onClick={submit} disabled={submitting}>{submitting ? "提交中…" : "开始合成"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
