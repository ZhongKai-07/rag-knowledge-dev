import { useEffect, useState } from "react";
import { BookOpen, Pencil, Plus, RefreshCw, ShieldCheck, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { RoleItem, RoleCreatePayload } from "@/services/roleService";
import { getRoles, createRole, updateRole, deleteRole, getRoleKnowledgeBases, setRoleKnowledgeBases } from "@/services/roleService";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import { getErrorMessage } from "@/utils/error";

const buildEmptyForm = (): RoleCreatePayload => ({
  name: "",
  description: ""
});

export function RoleListPage() {
  const [roles, setRoles] = useState<RoleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<RoleItem | null>(null);

  // 创建/编辑 Dialog
  const [dialogState, setDialogState] = useState<{ open: boolean; mode: "create" | "edit"; role: RoleItem | null }>({
    open: false,
    mode: "create",
    role: null
  });
  const [form, setForm] = useState(buildEmptyForm());

  // 知识库配置 Dialog
  const [kbDialogState, setKbDialogState] = useState<{ open: boolean; role: RoleItem | null }>({
    open: false,
    role: null
  });
  const [allKnowledgeBases, setAllKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedKbIds, setSelectedKbIds] = useState<Set<string>>(new Set());
  const [kbLoading, setKbLoading] = useState(false);

  // 每个角色的 KB 数量缓存
  const [roleKbCounts, setRoleKbCounts] = useState<Record<string, number>>({});

  const loadRoles = async () => {
    try {
      setLoading(true);
      const data = await getRoles();
      setRoles(data);

      // 加载每个角色的知识库数量
      const counts: Record<string, number> = {};
      await Promise.all(
        data.map(async (role) => {
          try {
            const kbIds = await getRoleKnowledgeBases(role.id);
            counts[role.id] = kbIds.length;
          } catch {
            counts[role.id] = 0;
          }
        })
      );
      setRoleKbCounts(counts);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载角色列表失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRoles();
  }, []);

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteRole(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      await loadRoles();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    } finally {
      setDeleteTarget(null);
    }
  };

  const openCreateDialog = () => {
    setForm(buildEmptyForm());
    setDialogState({ open: true, mode: "create", role: null });
  };

  const openEditDialog = (role: RoleItem) => {
    setForm({ name: role.name, description: role.description || "" });
    setDialogState({ open: true, mode: "edit", role });
  };

  const handleSave = async () => {
    const trimmedName = form.name.trim();
    if (!trimmedName) {
      toast.error("请输入角色名称");
      return;
    }
    try {
      const payload: RoleCreatePayload = { name: trimmedName, description: form.description?.trim() || "" };
      if (dialogState.mode === "create") {
        await createRole(payload);
        toast.success("创建成功");
      } else if (dialogState.role) {
        await updateRole(dialogState.role.id, payload);
        toast.success("更新成功");
      }
      setDialogState({ open: false, mode: "create", role: null });
      await loadRoles();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    }
  };

  // 知识库配置 Dialog
  const openKbDialog = async (role: RoleItem) => {
    setKbDialogState({ open: true, role });
    setKbLoading(true);
    try {
      const [kbs, assignedIds] = await Promise.all([
        getKnowledgeBases(),
        getRoleKnowledgeBases(role.id)
      ]);
      setAllKnowledgeBases(kbs);
      setSelectedKbIds(new Set(assignedIds));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载知识库列表失败"));
    } finally {
      setKbLoading(false);
    }
  };

  const toggleKb = (kbId: string) => {
    setSelectedKbIds((prev) => {
      const next = new Set(prev);
      if (next.has(kbId)) {
        next.delete(kbId);
      } else {
        next.add(kbId);
      }
      return next;
    });
  };

  const handleSaveKb = async () => {
    if (!kbDialogState.role) return;
    try {
      await setRoleKnowledgeBases(kbDialogState.role.id, Array.from(selectedKbIds));
      toast.success("知识库权限已更新");
      setKbDialogState({ open: false, role: null });
      await loadRoles();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    }
  };

  const formatDate = (dateStr?: string | null) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">角色管理</h1>
          <p className="admin-page-subtitle">管理角色与知识库访问权限</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={loadRoles}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={openCreateDialog}>
            <ShieldCheck className="w-4 h-4 mr-2" />
            新建角色
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : roles.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">暂无角色，点击"新建角色"创建</div>
          ) : (
            <Table className="min-w-[700px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[180px]">角色名称</TableHead>
                  <TableHead className="w-[240px]">描述</TableHead>
                  <TableHead className="w-[120px]">可见知识库</TableHead>
                  <TableHead className="w-[180px]">创建时间</TableHead>
                  <TableHead className="w-[200px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {roles.map((role) => (
                  <TableRow key={role.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <ShieldCheck className="w-4 h-4 text-indigo-500" />
                        <span className="font-medium text-slate-900">{role.name}</span>
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{role.description || "-"}</TableCell>
                    <TableCell>
                      <span className="text-sm font-medium text-indigo-600">
                        {roleKbCounts[role.id] ?? 0} 个
                      </span>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{formatDate(role.createTime)}</TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        <Button variant="outline" size="sm" onClick={() => openKbDialog(role)}>
                          <BookOpen className="w-4 h-4 mr-0.5" />
                          知识库
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => openEditDialog(role)}>
                          <Pencil className="w-4 h-4 mr-0.5" />
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(role)}
                        >
                          <Trash2 className="w-4 h-4 mr-0.5" />
                          删除
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* 删除确认 */}
      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              删除角色"{deleteTarget?.name}"将同时移除其关联的知识库权限和用户分配。确定要继续吗？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 创建/编辑 Dialog */}
      <Dialog open={dialogState.open} onOpenChange={(open) => setDialogState((prev) => ({ ...prev, open }))}>
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>{dialogState.mode === "create" ? "新建角色" : "编辑角色"}</DialogTitle>
            <DialogDescription>
              {dialogState.mode === "create" ? "创建新的访问角色" : "修改角色基本信息"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium">角色名称</label>
              <Input
                value={form.name}
                onChange={(e) => setForm((prev) => ({ ...prev, name: e.target.value }))}
                placeholder="例如：运营组、合规组"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">描述</label>
              <Input
                value={form.description}
                onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value }))}
                placeholder="可选，描述该角色的用途"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogState({ open: false, mode: "create", role: null })}>
              取消
            </Button>
            <Button onClick={handleSave}>
              {dialogState.mode === "create" ? (
                <><Plus className="mr-2 h-4 w-4" />创建</>
              ) : (
                <><Pencil className="mr-2 h-4 w-4" />保存</>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 知识库配置 Dialog */}
      <Dialog open={kbDialogState.open} onOpenChange={(open) => setKbDialogState((prev) => ({ ...prev, open }))}>
        <DialogContent className="sm:max-w-[480px]">
          <DialogHeader>
            <DialogTitle>配置可见知识库</DialogTitle>
            <DialogDescription>
              为角色"{kbDialogState.role?.name}"选择可访问的知识库
            </DialogDescription>
          </DialogHeader>
          {kbLoading ? (
            <div className="text-center py-6 text-muted-foreground">加载中...</div>
          ) : allKnowledgeBases.length === 0 ? (
            <div className="text-center py-6 text-muted-foreground">暂无可用知识库</div>
          ) : (
            <div className="max-h-[320px] overflow-y-auto space-y-1">
              {allKnowledgeBases.map((kb) => (
                <label
                  key={kb.id}
                  className="flex items-center gap-3 p-3 rounded-lg hover:bg-slate-50 cursor-pointer transition-colors"
                >
                  <Checkbox
                    checked={selectedKbIds.has(kb.id)}
                    onCheckedChange={() => toggleKb(kb.id)}
                  />
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-sm text-slate-900 truncate">{kb.name}</div>
                    <div className="text-xs text-muted-foreground truncate">
                      {kb.collectionName || kb.id}
                    </div>
                  </div>
                </label>
              ))}
            </div>
          )}
          <DialogFooter>
            <div className="flex items-center justify-between w-full">
              <span className="text-sm text-muted-foreground">
                已选 {selectedKbIds.size} / {allKnowledgeBases.length} 个
              </span>
              <div className="flex gap-2">
                <Button variant="outline" onClick={() => setKbDialogState({ open: false, role: null })}>
                  取消
                </Button>
                <Button onClick={handleSaveKb}>保存</Button>
              </div>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
