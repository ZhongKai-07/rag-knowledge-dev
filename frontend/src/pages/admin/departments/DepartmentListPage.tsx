import { useEffect, useState } from "react";
import { Building2, Lock, Pencil, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { SysDept, SysDeptCreatePayload } from "@/services/sysDeptService";
import { listDepartments, createDepartment, updateDepartment, deleteDepartment } from "@/services/sysDeptService";
import { getErrorMessage } from "@/utils/error";

const buildEmptyForm = (): SysDeptCreatePayload => ({
  deptCode: "",
  deptName: ""
});

export function DepartmentListPage() {
  const [departments, setDepartments] = useState<SysDept[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<SysDept | null>(null);

  // 创建/编辑 Dialog
  const [dialogState, setDialogState] = useState<{ open: boolean; mode: "create" | "edit"; dept: SysDept | null }>({
    open: false,
    mode: "create",
    dept: null
  });
  const [form, setForm] = useState(buildEmptyForm());
  const [saving, setSaving] = useState(false);

  const loadDepartments = async (kw?: string) => {
    try {
      setLoading(true);
      const data = await listDepartments(kw?.trim() || undefined);
      setDepartments(data ?? []);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载部门列表失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDepartments();
  }, []);

  const handleSearch = () => {
    loadDepartments(keyword);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      handleSearch();
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteDepartment(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      await loadDepartments(keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    } finally {
      setDeleteTarget(null);
    }
  };

  const openCreateDialog = () => {
    setForm(buildEmptyForm());
    setDialogState({ open: true, mode: "create", dept: null });
  };

  const openEditDialog = (dept: SysDept) => {
    setForm({ deptCode: dept.deptCode, deptName: dept.deptName });
    setDialogState({ open: true, mode: "edit", dept });
  };

  const handleSave = async () => {
    const trimmedCode = form.deptCode.trim();
    const trimmedName = form.deptName.trim();
    if (!trimmedCode) {
      toast.error("请输入部门编码");
      return;
    }
    if (!trimmedName) {
      toast.error("请输入部门名称");
      return;
    }
    try {
      setSaving(true);
      const payload: SysDeptCreatePayload = { deptCode: trimmedCode, deptName: trimmedName };
      if (dialogState.mode === "create") {
        await createDepartment(payload);
        toast.success("创建成功");
      } else if (dialogState.dept) {
        await updateDepartment(dialogState.dept.id, payload);
        toast.success("更新成功");
      }
      setDialogState({ open: false, mode: "create", dept: null });
      await loadDepartments(keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
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
          <h1 className="admin-page-title">部门管理</h1>
          <p className="admin-page-subtitle">管理组织部门与成员归属</p>
        </div>
        <div className="admin-page-actions">
          <div className="flex items-center gap-2">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <Input
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="搜索部门名称或编码..."
                className="pl-9 w-56"
              />
            </div>
            <Button variant="outline" onClick={handleSearch}>
              搜索
            </Button>
          </div>
          <Button variant="outline" onClick={() => loadDepartments(keyword)}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={openCreateDialog}>
            <Plus className="w-4 h-4 mr-2" />
            新建部门
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : departments.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">暂无部门，点击"新建部门"创建</div>
          ) : (
            <Table className="min-w-[800px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[160px]">部门编码</TableHead>
                  <TableHead className="w-[200px]">部门名称</TableHead>
                  <TableHead className="w-[100px]">用户数</TableHead>
                  <TableHead className="w-[100px]">知识库数</TableHead>
                  <TableHead className="w-[180px]">创建时间</TableHead>
                  <TableHead className="w-[160px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {departments.map((dept) => (
                  <TableRow key={dept.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <Building2 className="w-4 h-4 text-indigo-500 shrink-0" />
                        <span className="font-mono text-sm text-slate-700">{dept.deptCode}</span>
                        {dept.systemReserved && (
                          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500">
                            <Lock className="w-3 h-3" />
                            系统保留
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <span className="font-medium text-slate-900">{dept.deptName}</span>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm font-medium text-indigo-600">{dept.userCount ?? 0} 人</span>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm font-medium text-indigo-600">{dept.kbCount ?? 0} 个</span>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{formatDate(dept.createTime)}</TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={dept.systemReserved}
                          onClick={() => openEditDialog(dept)}
                        >
                          <Pencil className="w-4 h-4 mr-0.5" />
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          disabled={dept.systemReserved}
                          onClick={() => setDeleteTarget(dept)}
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
              删除部门"{deleteTarget?.deptName}"后，其成员将失去部门归属。确定要继续吗？
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
            <DialogTitle>{dialogState.mode === "create" ? "新建部门" : "编辑部门"}</DialogTitle>
            <DialogDescription>
              {dialogState.mode === "create" ? "创建新的组织部门" : "修改部门基本信息"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium">部门编码</label>
              <Input
                value={form.deptCode}
                onChange={(e) => setForm((prev) => ({ ...prev, deptCode: e.target.value }))}
                placeholder="例如：IT、HR、FINANCE"
                disabled={dialogState.mode === "edit" && (dialogState.dept?.systemReserved ?? false)}
              />
              {dialogState.mode === "edit" && dialogState.dept?.systemReserved && (
                <p className="text-xs text-muted-foreground">系统保留部门的编码不可修改</p>
              )}
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">部门名称</label>
              <Input
                value={form.deptName}
                onChange={(e) => setForm((prev) => ({ ...prev, deptName: e.target.value }))}
                placeholder="例如：信息技术部、人力资源部"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogState({ open: false, mode: "create", dept: null })}>
              取消
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {dialogState.mode === "create" ? (
                <><Plus className="mr-2 h-4 w-4" />{saving ? "创建中..." : "创建"}</>
              ) : (
                <><Pencil className="mr-2 h-4 w-4" />{saving ? "保存中..." : "保存"}</>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
