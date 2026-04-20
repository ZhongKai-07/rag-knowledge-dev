import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Pencil, Plus, RefreshCw, ShieldCheck, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { SecurityLevelBadge } from "@/components/common/SecurityLevelBadge";
import { getErrorMessage, isRbacRejection } from "@/utils/error";

import {
  createRole,
  deleteRole,
  updateRole,
  type RoleCreatePayload,
} from "@/services/roleService";
import {
  getRoleUsage,
  listAccessRoles,
  type AccessRole,
  type RoleUsage,
} from "@/services/access";

import { useAccessScope } from "../hooks/useAccessScope";
import { OrgTree } from "../components/OrgTree";
import { RoleDeleteConfirmDialog } from "../components/RoleDeleteConfirmDialog";

/**
 * 权限中心 Tab 3 「角色管理」
 * 左 30%：OrgTree（部门树 + roleCount）
 * 右 70%：所选部门下的角色列表 + 选中角色详情（users / kbs usage）
 *
 * 写权限矩阵：
 * - SUPER 全部可 CRUD（含 GLOBAL 节点）；SUPER_ADMIN 行永久禁止 edit/delete。
 * - DEPT_ADMIN：仅本部门节点可 CRUD；GLOBAL / 其他部门只读。
 */

// Mirrors backend SysDeptServiceImpl.GLOBAL_DEPT_ID. Same value is hard-coded
// in init_data_pg.sql for the 全局部门 row; changing one without the others
// silently breaks role visibility.
const GLOBAL_DEPT_ID = "1";
const ROLE_TYPE_OPTIONS = [
  { value: "DEPT_ADMIN", label: "DEPT_ADMIN（部门管理员）" },
  { value: "USER", label: "USER（普通用户）" },
];

const emptyForm = (): RoleCreatePayload => ({
  name: "",
  description: "",
  roleType: "USER",
  maxSecurityLevel: 0,
  deptId: null,
});
export function RolesTab() {
  const scope = useAccessScope();
  const [searchParams] = useSearchParams();
  const [selectedDeptId, setSelectedDeptId] = useState<string | null>(
    scope.isSuperAdmin ? GLOBAL_DEPT_ID : scope.deptId
  );
  const [selectedDeptName, setSelectedDeptName] = useState<string>(
    scope.isSuperAdmin ? "全局部门" : scope.deptName ?? "本部门"
  );
  const [roles, setRoles] = useState<AccessRole[]>([]);
  const [rolesLoading, setRolesLoading] = useState(false);
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [pendingRoleId, setPendingRoleId] = useState<string | null>(null);
  const [usage, setUsage] = useState<RoleUsage | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);

  const [editDialog, setEditDialog] = useState<{
    open: boolean;
    mode: "create" | "edit";
    role: AccessRole | null;
  }>({ open: false, mode: "create", role: null });
  const [form, setForm] = useState<RoleCreatePayload>(emptyForm());
  const [deleteTarget, setDeleteTarget] = useState<AccessRole | null>(null);

  const selectedRole = useMemo(
    () => roles.find((r) => r.id === selectedRoleId) ?? null,
    [roles, selectedRoleId]
  );

  const loadRoles = useCallback(async (deptId: string | null) => {
    if (!deptId) {
      setRoles([]);
      return;
    }
    try {
      setRolesLoading(true);
      // GLOBAL 节点：只列 GLOBAL 角色（dept_id='1'）
      // 非 GLOBAL 节点：仅列该部门角色，不带 GLOBAL 冗余
      const list = await listAccessRoles({
        deptId,
        includeGlobal: deptId === GLOBAL_DEPT_ID,
      });
      // 做一次前端收敛，保证节点与列表严格对应。
      setRoles(list.filter((r) => (r.deptId ?? GLOBAL_DEPT_ID) === deptId));
    } catch (err) {
      toast.error(getErrorMessage(err, "加载角色失败"));
    } finally {
      setRolesLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRoles(selectedDeptId);
    if (!pendingRoleId) {
      setSelectedRoleId(null);
    }
  }, [selectedDeptId, pendingRoleId, loadRoles]);

  useEffect(() => {
    if (!pendingRoleId) return;
    if (roles.some((role) => role.id === pendingRoleId)) {
      setSelectedRoleId(pendingRoleId);
      setPendingRoleId(null);
    }
  }, [pendingRoleId, roles]);

  const loadUsage = useCallback(async (roleId: string) => {
    try {
      setUsageLoading(true);
      const data = await getRoleUsage(roleId);
      setUsage(data);
    } catch (err) {
      if (!isRbacRejection(err)) {
        toast.error(getErrorMessage(err, "加载角色使用情况失败"));
      }
      setUsage(null);
    } finally {
      setUsageLoading(false);
    }
  }, []);

  useEffect(() => {
    if (selectedRoleId) {
      loadUsage(selectedRoleId);
    } else {
      setUsage(null);
    }
  }, [selectedRoleId, loadUsage]);

  useEffect(() => {
    const roleIdParam = searchParams.get("roleId");
    if (!roleIdParam || roleIdParam === selectedRoleId || roleIdParam === pendingRoleId) return;
    let cancelled = false;
    getRoleUsage(roleIdParam)
      .then((data) => {
        if (cancelled) return;
        setPendingRoleId(roleIdParam);
        setSelectedDeptId(data.deptId ?? GLOBAL_DEPT_ID);
        setSelectedDeptName(data.deptName ?? "所选部门");
      })
      .catch((err) => {
        if (!cancelled && !isRbacRejection(err)) {
          toast.error(getErrorMessage(err, "定位角色失败"));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [searchParams, selectedRoleId, pendingRoleId]);

  // ── 写权限判定 ────────────────────────────────────
  const isGlobalSelected = selectedDeptId === GLOBAL_DEPT_ID;
  const isOwnDeptSelected =
    scope.isDeptAdmin && selectedDeptId !== null && selectedDeptId === scope.deptId;
  const canCrudAtSelected =
    scope.isSuperAdmin || (scope.isDeptAdmin && isOwnDeptSelected && !isGlobalSelected);
  const selectedDeptLabel = isGlobalSelected ? "全局部门" : selectedDeptName || "所选部门";

  const openCreate = () => {
    setForm({
      ...emptyForm(),
      deptId: selectedDeptId,
    });
    setEditDialog({ open: true, mode: "create", role: null });
  };

  const openEdit = (role: AccessRole) => {
    setForm({
      name: role.name,
      description: role.description ?? "",
      roleType: role.roleType,
      maxSecurityLevel: role.maxSecurityLevel,
      deptId: role.deptId ?? null,
    });
    setEditDialog({ open: true, mode: "edit", role });
  };

  const handleSave = async () => {
    const name = form.name.trim();
    if (!name) return toast.error("请输入角色名称");
    try {
      const payload: RoleCreatePayload = {
        name,
        description: form.description?.trim() || "",
        roleType: form.roleType,
        maxSecurityLevel: form.maxSecurityLevel,
        deptId: form.deptId ?? null,
      };
      if (editDialog.mode === "create") {
        await createRole(payload);
        toast.success("创建成功");
      } else if (editDialog.role) {
        await updateRole(editDialog.role.id, payload);
        toast.success("更新成功");
      }
      setEditDialog({ open: false, mode: "create", role: null });
      await loadRoles(selectedDeptId);
    } catch (err) {
      toast.error(getErrorMessage(err, "保存失败"));
    }
  };

  const handleConfirmDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteRole(deleteTarget.id);
      toast.success("删除成功");
      if (selectedRoleId === deleteTarget.id) setSelectedRoleId(null);
      setDeleteTarget(null);
      await loadRoles(selectedDeptId);
    } catch (err) {
      toast.error(getErrorMessage(err, "删除失败"));
    }
  };

  const handleRefresh = async () => {
    await loadRoles(selectedDeptId);
    if (selectedRoleId) await loadUsage(selectedRoleId);
  };

  return (
    <div className="grid gap-4 md:grid-cols-[320px_1fr]">
      {/* 左栏 — 部门树 */}
      <div className="rounded-md border bg-white p-3">
        <div className="mb-2 flex items-center justify-between">
          <span className="text-xs font-semibold text-slate-500">按部门浏览</span>
          <Button variant="ghost" size="sm" onClick={handleRefresh} className="h-6 px-1">
            <RefreshCw className="h-3.5 w-3.5" />
          </Button>
        </div>
        <OrgTree
          selectedDeptId={selectedDeptId}
          onSelect={(id, name) => {
            setSelectedDeptId(id ?? GLOBAL_DEPT_ID);
            setSelectedDeptName(id === null ? "全局部门" : name ?? "所选部门");
          }}
          countField="roleCount"
          allowAllNode={false}
          restrictToOwnDept={false}
        />
      </div>

      {/* 右栏 — 角色列表 + 详情 */}
      <div className="space-y-4">
        <div className="rounded-md border bg-white p-4">
          <div className="mb-3 flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold text-slate-700">
                {isGlobalSelected ? "🏢 全局部门 GLOBAL" : `📂 ${selectedDeptLabel}`}
                <span className="ml-2 text-xs text-slate-400">({roles.length})</span>
              </h3>
              {!canCrudAtSelected && (
                <p className="mt-0.5 text-xs text-slate-400">
                  {isGlobalSelected
                    ? "GLOBAL 角色只读；仅 SUPER 可创建"
                    : "跨部门只读；新建/编辑/删除需该部门管理员"}
                </p>
              )}
            </div>
            {canCrudAtSelected && (
              <Button size="sm" variant="outline" onClick={openCreate}>
                <Plus className="mr-1 h-3.5 w-3.5" />
                新建角色
              </Button>
            )}
          </div>

          {rolesLoading ? (
            <div className="py-6 text-center text-xs text-slate-400">加载中…</div>
          ) : roles.length === 0 ? (
            <div className="py-6 text-center text-xs text-slate-400">该部门暂无角色</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>角色名</TableHead>
                  <TableHead className="w-[120px]">类型</TableHead>
                  <TableHead className="w-[100px]">密级天花板</TableHead>
                  <TableHead>描述</TableHead>
                  <TableHead className="w-[140px]">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {roles.map((r) => {
                  const isActive = selectedRoleId === r.id;
                  return (
                    <TableRow
                      key={r.id}
                      onClick={() => {
                        setPendingRoleId(null);
                        setSelectedRoleId(r.id);
                      }}
                      className={
                        "cursor-pointer " + (isActive ? "bg-indigo-50" : "")
                      }
                    >
                      <TableCell className="font-medium">
                        <ShieldCheck className="mr-1 inline h-3.5 w-3.5 text-indigo-500" />
                        {r.name}
                      </TableCell>
                      <TableCell>
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600">
                          {r.roleType}
                        </span>
                      </TableCell>
                      <TableCell>
                        <SecurityLevelBadge level={r.maxSecurityLevel ?? 0} />
                      </TableCell>
                      <TableCell className="text-xs text-slate-500">
                        {r.description || "—"}
                      </TableCell>
                      <TableCell>
                        {canCrudAtSelected && r.roleType !== "SUPER_ADMIN" ? (
                          <div className="flex gap-1">
                            <Button
                              size="sm"
                              variant="ghost"
                              className="h-7 px-2"
                              onClick={(e) => {
                                e.stopPropagation();
                                openEdit(r);
                              }}
                            >
                              <Pencil className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              className="h-7 px-2 text-destructive"
                              onClick={(e) => {
                                e.stopPropagation();
                                setDeleteTarget(r);
                              }}
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </Button>
                          </div>
                        ) : (
                          <span className="text-xs text-slate-400">—</span>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </div>

        {/* 角色详情 */}
        {selectedRole && (
          <div className="rounded-md border bg-white p-4">
            <h4 className="mb-2 text-sm font-semibold text-slate-700">
              选中角色「{selectedRole.name}」详情
            </h4>
            <div className="mb-3 text-xs text-slate-500">
              {selectedRole.description || "（无描述）"} · 密级天花板 {selectedRole.maxSecurityLevel}
              {selectedRole.deptName && ` · 归属 ${selectedRole.deptName}`}
            </div>

            {usageLoading ? (
              <div className="py-4 text-center text-xs text-slate-400">加载使用情况中…</div>
            ) : !usage ? (
              <div className="py-4 text-center text-xs text-slate-400">暂无数据</div>
            ) : (
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <div className="mb-1 text-xs font-medium text-slate-500">
                    已分配给 {usage.users.length} 个用户
                  </div>
                  {usage.users.length === 0 ? (
                    <p className="text-xs text-slate-400">（无）</p>
                  ) : (
                    <ul className="space-y-0.5 text-xs text-slate-600">
                      {usage.users.slice(0, 8).map((u) => (
                        <li key={u.userId}>
                          ·{" "}
                          <Link
                            to={`/admin/access?tab=members&userId=${u.userId}${u.deptId ? `&deptId=${u.deptId}` : ""}`}
                            className="text-indigo-600 hover:underline"
                          >
                            {u.username}
                          </Link>
                          {u.deptName && (
                            <span className="text-slate-400"> ({u.deptName})</span>
                          )}
                        </li>
                      ))}
                      {usage.users.length > 8 && (
                        <li className="text-slate-400">… +{usage.users.length - 8}</li>
                      )}
                    </ul>
                  )}
                </div>
                <div>
                  <div className="mb-1 text-xs font-medium text-slate-500">
                    共享了 {usage.kbs.length} 个 KB
                  </div>
                  {usage.kbs.length === 0 ? (
                    <p className="text-xs text-slate-400">（无）</p>
                  ) : (
                    <ul className="space-y-0.5 text-xs text-slate-600">
                      {usage.kbs.slice(0, 8).map((k) => (
                        <li key={k.kbId}>
                          ·{" "}
                          <Link
                            to={`/admin/access?tab=sharing&kb=${k.kbId}`}
                            className="text-indigo-600 hover:underline"
                          >
                            {k.kbName}
                          </Link>
                          {k.permission && (
                            <span className="text-slate-400"> [{k.permission}]</span>
                          )}
                          {k.deptName && (
                            <span className="text-slate-400"> ({k.deptName})</span>
                          )}
                        </li>
                      ))}
                      {usage.kbs.length > 8 && (
                        <li className="text-slate-400">… +{usage.kbs.length - 8}</li>
                      )}
                    </ul>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 创建/编辑 Dialog */}
      <Dialog
        open={editDialog.open}
        onOpenChange={(open) => setEditDialog((p) => ({ ...p, open }))}
      >
        <DialogContent className="sm:max-w-[440px]">
          <DialogHeader>
            <DialogTitle>
              {editDialog.mode === "create" ? "新建角色" : "编辑角色"}
            </DialogTitle>
            <DialogDescription>
              {isGlobalSelected
                ? "归属：🏢 全公司 GLOBAL（仅 SUPER 可创建）"
                : `归属：📂 ${selectedDeptLabel}`}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <label className="text-sm font-medium">角色名称</label>
              <Input
                value={form.name}
                onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
                placeholder="例如：运营组、合规组"
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium">描述</label>
              <Input
                value={form.description ?? ""}
                onChange={(e) => setForm((p) => ({ ...p, description: e.target.value }))}
                placeholder="可选"
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium">角色类型</label>
              <Select
                value={form.roleType}
                onValueChange={(v) => setForm((p) => ({ ...p, roleType: v }))}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLE_TYPE_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium">最大密级</label>
              <Select
                value={String(form.maxSecurityLevel)}
                onValueChange={(v) =>
                  setForm((p) => ({ ...p, maxSecurityLevel: Number(v) }))
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="0">0 · 公开</SelectItem>
                  <SelectItem value="1">1 · 内部</SelectItem>
                  <SelectItem value="2">2 · 机密</SelectItem>
                  <SelectItem value="3">3 · 绝密</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() =>
                setEditDialog({ open: false, mode: "create", role: null })
              }
            >
              取消
            </Button>
            <Button onClick={handleSave}>
              {editDialog.mode === "create" ? (
                <>
                  <Plus className="mr-1.5 h-3.5 w-3.5" /> 创建
                </>
              ) : (
                <>
                  <Pencil className="mr-1.5 h-3.5 w-3.5" /> 保存
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除确认 */}
      <RoleDeleteConfirmDialog
        roleId={deleteTarget?.id ?? null}
        roleName={deleteTarget?.name ?? ""}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        onConfirm={handleConfirmDelete}
      />
    </div>
  );
}
