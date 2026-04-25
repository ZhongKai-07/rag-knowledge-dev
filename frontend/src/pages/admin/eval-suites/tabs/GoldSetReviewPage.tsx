import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Textarea } from "@/components/ui/textarea";
import { toast } from "sonner";
import { Check, X, Pencil, CheckCircle2, ArrowLeft } from "lucide-react";
import {
  listGoldItems,
  reviewGoldItem,
  editGoldItem,
  activateGoldDataset,
  getGoldDataset,
  type GoldItem,
  type GoldDataset
} from "@/services/evalSuiteService";

export function GoldSetReviewPage() {
  const { datasetId } = useParams<{ datasetId: string }>();
  const navigate = useNavigate();

  const [dataset, setDataset] = useState<GoldDataset | null>(null);
  const [items, setItems] = useState<GoldItem[]>([]);
  const [currentIdx, setCurrentIdx] = useState(0);
  const [editing, setEditing] = useState(false);
  const [editedQuestion, setEditedQuestion] = useState("");
  const [editedAnswer, setEditedAnswer] = useState("");
  const [note, setNote] = useState("");

  const refresh = useCallback(async () => {
    if (!datasetId) return;
    const [ds, list] = await Promise.all([getGoldDataset(datasetId), listGoldItems(datasetId)]);
    setDataset(ds);
    setItems(list);
  }, [datasetId]);

  useEffect(() => { refresh(); }, [refresh]);

  const current = items[currentIdx];
  const pendingCount = items.filter((i) => i.reviewStatus === "PENDING").length;
  const approvedCount = items.filter((i) => i.reviewStatus === "APPROVED").length;
  const canActivate = dataset?.status === "DRAFT" && approvedCount > 0 && pendingCount === 0;

  const gotoNext = () => {
    const nextPending = items.findIndex((it, idx) => idx > currentIdx && it.reviewStatus === "PENDING");
    if (nextPending >= 0) setCurrentIdx(nextPending);
    else {
      setCurrentIdx(Math.min(currentIdx + 1, items.length - 1));
    }
  };

  const approve = useCallback(async () => {
    if (!current) return;
    try {
      await reviewGoldItem(current.id, "APPROVE", note || undefined);
      toast.success("已接受");
      await refresh();
      setNote("");
      gotoNext();
    } catch (e) {
      toast.error((e as Error).message);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, note, refresh]);

  const reject = useCallback(async () => {
    if (!current) return;
    try {
      await reviewGoldItem(current.id, "REJECT", note || undefined);
      toast.success("已拒绝");
      await refresh();
      setNote("");
      gotoNext();
    } catch (e) {
      toast.error((e as Error).message);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, note, refresh]);

  const startEdit = () => {
    if (!current) return;
    setEditedQuestion(current.question);
    setEditedAnswer(current.groundTruthAnswer);
    setEditing(true);
  };

  const saveEdit = async () => {
    if (!current) return;
    try {
      await editGoldItem(current.id, { question: editedQuestion, groundTruthAnswer: editedAnswer });
      setEditing(false);
      toast.success("已保存");
      await refresh();
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  const activate = async () => {
    if (!datasetId) return;
    try {
      await activateGoldDataset(datasetId);
      toast.success("数据集已激活");
      navigate("/admin/eval-suites");
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  // 快捷键 y/n/e（编辑中暂不响应）
  useEffect(() => {
    if (editing) return;
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName?.toLowerCase();
      if (tag === "input" || tag === "textarea") return;
      if (e.key === "y") approve();
      else if (e.key === "n") reject();
      else if (e.key === "e") startEdit();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editing, approve, reject]);

  if (!dataset || items.length === 0) {
    return <div className="p-6 text-sm text-slate-500">加载中或无数据……</div>;
  }

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-3">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="sm" onClick={() => navigate("/admin/eval-suites")}>
            <ArrowLeft className="mr-1 h-4 w-4" />返回
          </Button>
          <div>
            <div className="font-semibold">{dataset.name} · 审核</div>
            <div className="text-xs text-slate-500">
              {currentIdx + 1} / {items.length} · 待审 {pendingCount} · 已通过 {approvedCount}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">快捷键：y 通过 · n 拒绝 · e 编辑</span>
          <Button disabled={!canActivate} onClick={activate}>
            <CheckCircle2 className="mr-1 h-4 w-4" />激活数据集
          </Button>
        </div>
      </header>

      <div className="grid flex-1 grid-cols-2 gap-4 overflow-auto p-6">
        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <h3 className="mb-2 text-sm font-semibold text-slate-600">原文 chunk（快照）</h3>
          <p className="text-xs text-slate-400">
            文档：{current.sourceDocName ?? current.sourceDocId ?? "（已失效）"} · chunk_id {current.sourceChunkId ?? "（已失效）"}
          </p>
          <pre className="mt-3 whitespace-pre-wrap break-words rounded bg-slate-50 p-3 text-sm leading-relaxed">
            {current.sourceChunkText}
          </pre>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <div className="mb-2 flex items-center justify-between">
            <h3 className="text-sm font-semibold text-slate-600">合成问答</h3>
            <Badge variant={
              current.reviewStatus === "APPROVED" ? "default" :
                current.reviewStatus === "REJECTED" ? "destructive" : "secondary"
            }>
              {current.reviewStatus}
            </Badge>
          </div>
          {editing ? (
            <div className="space-y-3">
              <div>
                <label className="text-xs font-medium text-slate-500">Question</label>
                <Textarea value={editedQuestion} onChange={(e) => setEditedQuestion(e.target.value)} rows={3} />
              </div>
              <div>
                <label className="text-xs font-medium text-slate-500">Ground truth answer</label>
                <Textarea value={editedAnswer} onChange={(e) => setEditedAnswer(e.target.value)} rows={6} />
              </div>
              <div className="flex justify-end gap-2">
                <Button variant="outline" onClick={() => setEditing(false)}>取消</Button>
                <Button onClick={saveEdit}>保存</Button>
              </div>
            </div>
          ) : (
            <div className="space-y-3">
              <div>
                <div className="text-xs font-medium text-slate-500">Question</div>
                <p className="mt-1 text-sm">{current.question}</p>
              </div>
              <div>
                <div className="text-xs font-medium text-slate-500">Ground truth answer</div>
                <p className="mt-1 whitespace-pre-wrap text-sm">{current.groundTruthAnswer}</p>
              </div>
              <div>
                <label className="text-xs font-medium text-slate-500">备注（可选）</label>
                <Textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} placeholder="留给自己的审核理由" />
              </div>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" onClick={approve}><Check className="mr-1 h-3 w-3" />接受 (y)</Button>
                <Button size="sm" variant="destructive" onClick={reject}><X className="mr-1 h-3 w-3" />拒绝 (n)</Button>
                <Button size="sm" variant="outline" onClick={startEdit}><Pencil className="mr-1 h-3 w-3" />编辑 (e)</Button>
              </div>
            </div>
          )}

          <div className="mt-4 flex justify-between text-xs text-slate-500">
            <button disabled={currentIdx === 0} onClick={() => setCurrentIdx((i) => i - 1)}>← 上一条</button>
            <button disabled={currentIdx >= items.length - 1} onClick={() => setCurrentIdx((i) => i + 1)}>下一条 →</button>
          </div>
        </section>
      </div>
    </div>
  );
}
