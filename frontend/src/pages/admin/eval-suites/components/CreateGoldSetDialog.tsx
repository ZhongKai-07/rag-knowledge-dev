import { useEffect, useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import { createGoldDataset } from "@/services/evalSuiteService";
import { toast } from "sonner";

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: (datasetId: string) => void;
  defaultKbId?: string;
}

export function CreateGoldSetDialog({ open, onClose, onCreated, defaultKbId }: Props) {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string>(defaultKbId ?? "");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    getKnowledgeBases(1, 100, "").then(setKbs).catch(() => setKbs([]));
  }, [open]);

  const submit = async () => {
    if (!kbId || !name.trim()) {
      toast.error("请选择 KB 并填写名称");
      return;
    }
    try {
      setSubmitting(true);
      const id = await createGoldDataset({ kbId, name: name.trim(), description });
      toast.success("数据集已创建");
      onCreated(id);
      onClose();
      setName("");
      setDescription("");
    } catch (e) {
      toast.error((e as Error).message || "创建失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>创建黄金集</DialogTitle>
          <DialogDescription>空数据集创建后状态为 DRAFT；下一步触发合成。</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-2">
            <label className="text-sm font-medium">知识库</label>
            <Select value={kbId} onValueChange={setKbId}>
              <SelectTrigger><SelectValue placeholder="选择 KB" /></SelectTrigger>
              <SelectContent>
                {kbs.map((kb) => <SelectItem key={kb.id} value={kb.id}>{kb.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">名称</label>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：smoke-50" />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">描述（可选）</label>
            <Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button onClick={submit} disabled={submitting}>{submitting ? "创建中…" : "创建"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
