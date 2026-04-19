import { useEffect, useMemo, useState } from "react";
import { Building2, Lock, Pencil, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

import { getDepartmentsTree, type AccessDeptNode } from "@/services/access";
import {
  createDepartment,
  deleteDepartment,
  updateDepartment,
  type SysDeptCreatePayload,
} from "@/services/sysDeptService";
import { getErrorMessage } from "@/utils/error";
import { formatDateTime } from "@/utils/helpers";

const buildEmptyForm = (): SysDeptCreatePayload => ({
  deptCode: "",
  deptName: "",
});

/**
 * 权限中心 Tab 4 「部门组织」（仅 SUPER；可见性由 AccessCenterPage 上游过滤）。
 * 数据源：/access/departments/tree —— 一次调用获得 userCount/kbCount/roleCount。
 * CRUD 继续走现有 /sys-dept 接口（GLOBAL 硬保护 + 引用计数 409 由后端承担）。
 */
export function DepartmentsTab() {
  const [depts, setDepts] = useState<AccessDeptNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<AccessDeptNode | null>(null);

  const [dialogState, setDialogState] = useState<{
    open: boolean;
    mode: "create" | "edit";
    dept: AccessDeptNode | null;
  }>({ open: false, mode: "create", dept: null });
  const [form, setForm] = useState<SysDeptCreatePayload>(buildEmptyForm());
  const [saving, setSaving] = useState(false);

  const reload = async () => {
    try {
      setLoading(true);
      const list = await getDepartmentsTree();
      setDepts(list);
    } catch (err) {
      toast.error(getErrorMessage(err, "加载部门列表失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    reload();
  }, []);

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return depts;
    return depts.filter(
      (d) =>
        d.deptCode.toLowerCase().includes(kw) || d.deptName.toLowerCase().includes(kw)
    );
  }, [depts, keyword]);

  const openCreate = () => {
    setForm(buildEmptyForm());
    setDialogState({ open: true, mode: "create", dept: null });
  };

  const openEdit = (dept: AccessDeptNode) => {
    setForm({ deptCode: dept.deptCode, deptName: dept.deptName });
    setDialogState({ open: true, mode: "edit", dept });
  };

  const handleSave = async () => {
    const code = form.deptCode.trim();
    const name = form.deptName.trim();
    if (!code) return toast.error("请输入部门编码");
    if (!name) return toast.error("请输入部门名称");
    try {
      setSaving(true);
      if (dialogState.mode === "create") {
        await createDepartment({ deptCode: code, deptName: name });
        toast.success("创建成功");
      } else if (dialogState.dept) {
        await updateDepartment(dialogState.dept.id, { deptCode: code, deptName: name });
        toast.success("更新成功");
      }
      setDialogState({ open: false, mode: "create", dept: null });
      await reload();
    } catch (err) {
      toast.error(getErrorMessage(err, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteDepartment(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      await reload();
    } catch (err) {
      toast.error(getErrorMessage(err, "删除失败"));
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索部门名称或编码"
            className="w-64 pl-9"
          />
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={reload}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
          <Button size="sm" className="admin-primary-gradient" onClick={openCreate}>
            <Plus className="mr-1.5 h-3.5 w-3.5" />
            新建部门
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="py-8 text-center text-sm text-slate-400">加载中…</div>
          ) : filtered.length === 0 ? (
            <div className="py-8 text-center text-sm text-slate-400">
              {keyword ? "未匹配到部门" : "暂无部门，点击「新建部门」创建"}
            </div>
          ) : (
            <Table className="min-w-[860px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[160px]">部门编码</TableHead>
                  <TableHead className="w-[200px]">部门名称</TableHead>
                  <TableHead className="w-[90px]">用户数</TableHead>
                  <TableHead className="w-[90px]">KB 数</TableHead>
                  <TableHead className="w-[90px]">角色数</TableHead>
                  <TableHead className="w-[170px]">创建时间</TableHead>
                  <TableHead className="w-[160px]">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((dept) => (
                  <TableRow key={dept.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <Building2 className="h-4 w-4 shrink-0 text-indigo-500" />
                        <span className="font-mono text-sm text-slate-700">
                          {dept.deptCode}
                        </span>
                        {dept.systemReserved && (
                          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500">
                            <Lock className="h-3 w-3" />
                            系统保留
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="font-medium text-slate-900">
                      {dept.deptName}
                    </TableCell>
                    <TableCell>
                      <span className="text-sm font-medium text-indigo-600">
                        {dept.userCount ?? 0} 人
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm font-medium text-indigo-600">
                        {dept.kbCount ?? 0} 个
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm font-medium text-indigo-600">
                        {dept.roleCount ?? 0} 个
                      </span>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDateTime(dept.createTime ?? undefined)}
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={dept.systemReserved}
                          onClick={() => openEdit(dept)}
                        >
                          <Pencil className="mr-0.5 h-3.5 w-3.5" />
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          disabled={dept.systemReserved}
                          onClick={() => setDeleteTarget(dept)}
                        >
                          <Trash2 className="mr-0.5 h-3.5 w-3.5" />
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
      <AlertDialog
        open={!!deleteTarget}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除部门</AlertDialogTitle>
            <AlertDialogDescription>
              删除部门「{deleteTarget?.deptName}」。
              {deleteTarget && (deleteTarget.userCount || deleteTarget.kbCount || deleteTarget.roleCount) ? (
                <span className="mt-2 block rounded bg-amber-50 px-2 py-1 text-xs text-amber-700">
                  该部门下仍有 {deleteTarget.userCount ?? 0} 位用户 / {deleteTarget.kbCount ?? 0} 个 KB / {deleteTarget.roleCount ?? 0} 个角色，后端将拦截删除。
                </span>
              ) : null}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              className="bg-destructive text-destructive-foreground"
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 创建/编辑 Dialog */}
      <Dialog
        open={dialogState.open}
        onOpenChange={(open) => setDialogState((p) => ({ ...p, open }))}
      >
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>
              {dialogState.mode === "create" ? "新建部门" : "编辑部门"}
            </DialogTitle>
            <DialogDescription>
              {dialogState.mode === "create"
                ? "创建新的组织部门"
                : "修改部门基本信息；系统保留部门的编码不可修改"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium">部门编码</label>
              <Input
                value={form.deptCode}
                onChange={(e) => setForm((p) => ({ ...p, deptCode: e.target.value }))}
                placeholder="例如：IT、HR、FINANCE"
                disabled={
                  dialogState.mode === "edit" &&
                  (dialogState.dept?.systemReserved ?? false)
                }
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">部门名称</label>
              <Input
                value={form.deptName}
                onChange={(e) => setForm((p) => ({ ...p, deptName: e.target.value }))}
                placeholder="例如：信息技术部、人力资源部"
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDialogState({ open: false, mode: "create", dept: null })}
            >
              取消
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {dialogState.mode === "create" ? (
                <>
                  <Plus className="mr-1.5 h-3.5 w-3.5" />
                  {saving ? "创建中…" : "创建"}
                </>
              ) : (
                <>
                  <Pencil className="mr-1.5 h-3.5 w-3.5" />
                  {saving ? "保存中…" : "保存"}
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
