import { useEffect, useState } from "react";
import { Pencil, Plus, RefreshCw, Trash2, UserPlus } from "lucide-react";
import { toast } from "sonner";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Avatar } from "@/components/common/Avatar";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { PageResult, UserItem, UserCreatePayload, UserUpdatePayload } from "@/services/userService";
import { createUser, deleteUser, getUsersPage, updateUser } from "@/services/userService";
import type { RoleItem } from "@/services/roleService";
import { getRoles, getUserRoles, setUserRoles } from "@/services/roleService";
import { getErrorMessage } from "@/utils/error";
import { usePermissions } from "@/utils/permissions";

const PAGE_SIZE = 10;

const SECURITY_LEVEL_LABELS = ["公开", "内部", "机密", "绝密"];

function SecurityLevelBadge({ level }: { level: number }) {
  const colors = [
    "bg-green-100 text-green-800",
    "bg-blue-100 text-blue-800",
    "bg-orange-100 text-orange-800",
    "bg-red-100 text-red-800",
  ];
  const label = SECURITY_LEVEL_LABELS[level] ?? String(level);
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${colors[level] ?? colors[0]}`}>
      {label}
    </span>
  );
}

const buildEmptyForm = () => ({
  username: "",
  password: "",
  avatar: "",
  roleIds: [] as string[],
});

export function UserListPage() {
  const permissions = usePermissions();

  const [pageData, setPageData] = useState<PageResult<UserItem> | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchInput, setSearchInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [deleteTarget, setDeleteTarget] = useState<UserItem | null>(null);

  const [dialogState, setDialogState] = useState<{ open: boolean; mode: "create" | "edit"; user: UserItem | null }>({
    open: false,
    mode: "create",
    user: null,
  });
  const [form, setForm] = useState(buildEmptyForm());
  const [allRoles, setAllRoles] = useState<RoleItem[]>([]);

  const users = pageData?.records || [];

  const loadUsers = async (current = pageNo, name = keyword) => {
    try {
      setLoading(true);
      const data = await getUsersPage(current, PAGE_SIZE, name || undefined);
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载用户列表失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadUsers();
  }, [pageNo, keyword]);

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchInput.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadUsers(1, keyword);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteUser(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      setPageNo(1);
      await loadUsers(1, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const openCreateDialog = async () => {
    setForm(buildEmptyForm());
    setDialogState({ open: true, mode: "create", user: null });
    try {
      const roles = await getRoles();
      setAllRoles(roles);
    } catch { /* ignore */ }
  };

  const openEditDialog = async (user: UserItem) => {
    setDialogState({ open: true, mode: "edit", user });
    try {
      const [roles, assigned] = await Promise.all([getRoles(), getUserRoles(user.id)]);
      setAllRoles(roles);
      setForm({
        username: user.username || "",
        password: "",
        avatar: user.avatar || "",
        roleIds: assigned.map((r) => r.id),
      });
    } catch {
      setForm({
        username: user.username || "",
        password: "",
        avatar: user.avatar || "",
        roleIds: [],
      });
    }
  };

  const toggleFormRole = (roleId: string) => {
    setForm((prev) => {
      const ids = new Set(prev.roleIds);
      if (ids.has(roleId)) ids.delete(roleId);
      else ids.add(roleId);
      return { ...prev, roleIds: Array.from(ids) };
    });
  };

  const handleSave = async () => {
    const trimmedUsername = form.username.trim();
    const trimmedPassword = form.password.trim();
    if (!trimmedUsername) {
      toast.error("请输入用户名");
      return;
    }

    try {
      if (dialogState.mode === "create") {
        if (!trimmedPassword) {
          toast.error("请输入初始密码");
          return;
        }
        const payload: UserCreatePayload = {
          username: trimmedUsername,
          password: trimmedPassword,
          avatar: form.avatar?.trim() || undefined,
          deptId: "",
          roleIds: form.roleIds,
        };
        await createUser(payload);
        toast.success("创建成功");
        setPageNo(1);
        await loadUsers(1, keyword);
      } else if (dialogState.user) {
        const payload: UserUpdatePayload = {
          username: trimmedUsername,
          avatar: form.avatar?.trim() || undefined,
          password: trimmedPassword || undefined,
        };
        await updateUser(dialogState.user.id, payload);
        await setUserRoles(dialogState.user.id, form.roleIds);
        toast.success("更新成功");
        await loadUsers(pageNo, keyword);
      }
      setDialogState({ open: false, mode: "create", user: null });
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
      console.error(error);
    }
  };

  const formatDate = (dateStr?: string | null) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
  };

  const isProtectedAdmin = (user: UserItem) => user.username === "admin";

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">用户管理</h1>
          <p className="admin-page-subtitle">管理账号、部门归属与角色权限</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
            placeholder="搜索用户名"
            className="w-[220px]"
          />
          <Button variant="outline" onClick={handleSearch}>
            搜索
          </Button>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={openCreateDialog}>
            <UserPlus className="w-4 h-4 mr-2" />
            新增用户
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : users.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">暂无用户</div>
          ) : (
            <Table className="min-w-[900px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[200px]">用户名</TableHead>
                  <TableHead className="w-[140px]">部门</TableHead>
                  <TableHead className="w-[220px]">角色类型</TableHead>
                  <TableHead className="w-[100px]">最大密级</TableHead>
                  <TableHead className="w-[160px]">创建时间</TableHead>
                  <TableHead className="w-[180px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.map((user) => {
                  const isProtected = isProtectedAdmin(user);
                  const canManage = permissions.canManageUser(user);
                  return (
                    <TableRow key={user.id}>
                      <TableCell>
                        <div className="flex items-center gap-3">
                          <Avatar
                            name={user.username || "用户"}
                            src={user.avatar?.trim() || undefined}
                            className="h-9 w-9 border-slate-200 bg-indigo-50 text-xs font-semibold text-indigo-600"
                          />
                          <div>
                            <div className="font-medium text-slate-900">{user.username || "-"}</div>
                            {isProtected && (
                              <div className="text-xs text-slate-400">默认管理员</div>
                            )}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        {user.deptName ? (
                          <span className="inline-flex items-center px-2 py-0.5 rounded bg-slate-100 text-slate-700 text-xs font-medium">
                            {user.deptName}
                          </span>
                        ) : (
                          <span className="text-muted-foreground text-xs">未分配</span>
                        )}
                      </TableCell>
                      <TableCell>
                        {user.roleTypes && user.roleTypes.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {user.roleTypes.map((rt) => (
                              <span
                                key={rt}
                                className="inline-flex items-center px-2 py-0.5 rounded bg-indigo-50 text-indigo-700 text-xs font-medium"
                              >
                                {rt}
                              </span>
                            ))}
                          </div>
                        ) : (
                          <span className="text-muted-foreground text-xs">未分配</span>
                        )}
                      </TableCell>
                      <TableCell>
                        <SecurityLevelBadge level={user.maxSecurityLevel ?? 0} />
                      </TableCell>
                      <TableCell className="text-muted-foreground">{formatDate(user.createTime)}</TableCell>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          {canManage && (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => openEditDialog(user)}
                            >
                              <Pencil className="w-4 h-4 mr-0.5" />
                              编辑
                            </Button>
                          )}
                          {canManage && !isProtected && (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="text-destructive hover:text-destructive"
                              onClick={() => setDeleteTarget(user)}
                            >
                              <Trash2 className="w-4 h-4 mr-0.5" />
                              删除
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Delete confirmation */}
      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              此操作将永久删除该用户，无法恢复。确定要继续吗？
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

      {/* Create / Edit dialog (basic) */}
      <Dialog open={dialogState.open} onOpenChange={(open) => setDialogState((prev) => ({ ...prev, open }))}>
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>{dialogState.mode === "create" ? "新增用户" : "编辑用户"}</DialogTitle>
            <DialogDescription>
              {dialogState.mode === "create" ? "配置账号基本信息" : "更新账号信息，密码留空则不修改"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium">用户名</label>
              <Input
                value={form.username}
                onChange={(event) => setForm((prev) => ({ ...prev, username: event.target.value }))}
                placeholder="请输入用户名"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">密码</label>
              <Input
                type="password"
                value={form.password}
                onChange={(event) => setForm((prev) => ({ ...prev, password: event.target.value }))}
                placeholder={dialogState.mode === "create" ? "设置初始密码" : "留空则不修改"}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">头像</label>
              <Input
                value={form.avatar}
                onChange={(event) => setForm((prev) => ({ ...prev, avatar: event.target.value }))}
                placeholder="可选，填写头像 URL"
              />
            </div>
            {allRoles.length > 0 && (
              <div className="space-y-2">
                <label className="text-sm font-medium">RBAC 角色</label>
                <div className="max-h-[160px] overflow-y-auto border rounded-md">
                  {allRoles.map((role) => (
                    <label
                      key={role.id}
                      className="flex items-center gap-3 px-3 py-2 hover:bg-slate-50 cursor-pointer transition-colors"
                    >
                      <Checkbox
                        checked={form.roleIds.includes(role.id)}
                        onCheckedChange={() => toggleFormRole(role.id)}
                      />
                      <div className="flex-1 min-w-0">
                        <span className="text-sm text-slate-900">{role.name}</span>
                        {role.description && (
                          <span className="text-xs text-muted-foreground ml-2">{role.description}</span>
                        )}
                      </div>
                    </label>
                  ))}
                </div>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogState({ open: false, mode: "create", user: null })}>
              取消
            </Button>
            <Button onClick={handleSave}>
              {dialogState.mode === "create" ? (
                <>
                  <Plus className="mr-2 h-4 w-4" />
                  创建
                </>
              ) : (
                <>
                  <Pencil className="mr-2 h-4 w-4" />
                  保存
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Pagination */}
      {pageData ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
          <span>共 {pageData.total} 条</span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={pageData.current <= 1}
            >
              上一页
            </Button>
            <span>
              {pageData.current} / {pageData.pages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.min(pageData.pages || 1, prev + 1))}
              disabled={pageData.current >= pageData.pages}
            >
              下一页
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
