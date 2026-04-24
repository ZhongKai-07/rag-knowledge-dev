import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Plus, Play, CheckCircle2, Archive, Trash2, FileSearch } from "lucide-react";
import {
  listGoldDatasets,
  activateGoldDataset,
  archiveGoldDataset,
  deleteGoldDataset,
  type GoldDataset
} from "@/services/evalSuiteService";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import { CreateGoldSetDialog } from "../components/CreateGoldSetDialog";
import { TriggerSynthesisDialog } from "../components/TriggerSynthesisDialog";
import { SynthesisProgressDialog } from "../components/SynthesisProgressDialog";
import { toast } from "sonner";
import { formatDateTime } from "@/utils/helpers";

export function GoldSetListTab() {
  // Radix Select 不允许 value=""——用 "__all__" sentinel，API 调用前翻译成 undefined
  const ALL = "__all__";
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [kbFilter, setKbFilter] = useState<string>(ALL);
  const [statusFilter, setStatusFilter] = useState<string>(ALL);
  const [datasets, setDatasets] = useState<GoldDataset[]>([]);
  const [loading, setLoading] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [progressOpen, setProgressOpen] = useState(false);
  const [activeDatasetId, setActiveDatasetId] = useState<string | null>(null);

  useEffect(() => {
    getKnowledgeBases(1, 100, "").then(setKbs).catch(() => setKbs([]));
  }, []);

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await listGoldDatasets(
        kbFilter === ALL ? undefined : kbFilter,
        statusFilter === ALL ? undefined : statusFilter
      );
      setDatasets(data);
    } catch (e) {
      toast.error((e as Error).message || "加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [kbFilter, statusFilter]);

  const statusBadge = (status: string) => {
    const map: Record<string, { variant: "secondary" | "default" | "outline" | "destructive"; label: string }> = {
      DRAFT: { variant: "secondary", label: "草稿" },
      ACTIVE: { variant: "default", label: "可用" },
      ARCHIVED: { variant: "outline", label: "归档" }
    };
    const it = map[status] ?? { variant: "secondary" as const, label: status };
    return <Badge variant={it.variant}>{it.label}</Badge>;
  };

  const onTriggerSynth = (id: string) => {
    setActiveDatasetId(id);
    setTriggerOpen(true);
  };

  const onActivate = async (id: string) => {
    try {
      await activateGoldDataset(id);
      toast.success("已激活");
      refresh();
    } catch (e) {
      toast.error((e as Error).message || "激活失败");
    }
  };

  const onArchive = async (id: string) => {
    try {
      await archiveGoldDataset(id);
      toast.success("已归档");
      refresh();
    } catch (e) {
      toast.error((e as Error).message || "归档失败");
    }
  };

  const onDelete = async (id: string) => {
    if (!confirm("确认删除数据集？ACTIVE 态需先归档。")) return;
    try {
      await deleteGoldDataset(id);
      toast.success("已删除");
      refresh();
    } catch (e) {
      toast.error((e as Error).message || "删除失败");
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Select value={kbFilter} onValueChange={setKbFilter}>
          <SelectTrigger className="w-[240px]"><SelectValue placeholder="全部 KB" /></SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>全部 KB</SelectItem>
            {kbs.map((k) => <SelectItem key={k.id} value={k.id}>{k.name}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="w-[160px]"><SelectValue placeholder="全部状态" /></SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>全部状态</SelectItem>
            <SelectItem value="DRAFT">草稿</SelectItem>
            <SelectItem value="ACTIVE">可用</SelectItem>
            <SelectItem value="ARCHIVED">归档</SelectItem>
          </SelectContent>
        </Select>
        <div className="flex-1" />
        <Button onClick={() => setCreateOpen(true)}><Plus className="mr-1 h-4 w-4" />新建数据集</Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>名称</TableHead>
            <TableHead>KB</TableHead>
            <TableHead>状态</TableHead>
            <TableHead>已审批 / 总数</TableHead>
            <TableHead>创建时间</TableHead>
            <TableHead className="text-right">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {datasets.map((d) => (
            <TableRow key={d.id}>
              <TableCell className="font-medium">{d.name}</TableCell>
              <TableCell>{kbs.find((k) => k.id === d.kbId)?.name ?? d.kbId}</TableCell>
              <TableCell>{statusBadge(d.status)}</TableCell>
              <TableCell>{d.itemCount} / {d.totalItemCount}</TableCell>
              <TableCell>{d.createTime ? formatDateTime(d.createTime) : "-"}</TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  {d.status === "DRAFT" && d.totalItemCount === 0 && (
                    <Button size="sm" variant="outline" onClick={() => onTriggerSynth(d.id)}>
                      <Play className="mr-1 h-3 w-3" />合成
                    </Button>
                  )}
                  {d.status === "DRAFT" && d.totalItemCount > 0 && (
                    <>
                      <Button size="sm" variant="outline" asChild>
                        <Link to={`/admin/eval-suites/datasets/${d.id}/review`}>
                          <FileSearch className="mr-1 h-3 w-3" />审核
                          {d.pendingItemCount > 0 && <span className="ml-1 text-xs text-amber-600">({d.pendingItemCount})</span>}
                        </Link>
                      </Button>
                      {d.itemCount > 0 && d.pendingItemCount === 0 && (
                        <Button size="sm" onClick={() => onActivate(d.id)}>
                          <CheckCircle2 className="mr-1 h-3 w-3" />激活
                        </Button>
                      )}
                    </>
                  )}
                  {d.status === "ACTIVE" && (
                    <Button size="sm" variant="outline" onClick={() => onArchive(d.id)}>
                      <Archive className="mr-1 h-3 w-3" />归档
                    </Button>
                  )}
                  {d.status !== "ACTIVE" && (
                    <Button size="sm" variant="ghost" onClick={() => onDelete(d.id)}>
                      <Trash2 className="h-3 w-3 text-rose-500" />
                    </Button>
                  )}
                </div>
              </TableCell>
            </TableRow>
          ))}
          {datasets.length === 0 && !loading && (
            <TableRow>
              <TableCell colSpan={6} className="py-8 text-center text-sm text-slate-500">
                暂无数据集，点"新建数据集"开始。
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <CreateGoldSetDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => refresh()}
        defaultKbId={kbFilter === ALL ? undefined : kbFilter}
      />
      <TriggerSynthesisDialog
        open={triggerOpen}
        onClose={() => setTriggerOpen(false)}
        datasetId={activeDatasetId}
        onStarted={(id) => {
          setActiveDatasetId(id);
          setProgressOpen(true);
        }}
      />
      <SynthesisProgressDialog
        open={progressOpen}
        onClose={() => { setProgressOpen(false); refresh(); }}
        datasetId={activeDatasetId}
        onFinished={() => refresh()}
      />
    </div>
  );
}
