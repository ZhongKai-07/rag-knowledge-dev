import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Key, Plus, RefreshCw, Trash2, UserMinus, UserPlus, X } from "lucide-react";
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
import { Checkbox } from "@/components/ui/checkbox";
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
import { SecurityLevelBadge } from "@/components/common/SecurityLevelBadge";
import { getErrorMessage, isRbacRejection } from "@/utils/error";
import { formatDateTime } from "@/utils/helpers";
import { usePermissions } from "@/utils/permissions";

import {
  createUser,
  deleteUser,
  getUsersPage,
  type UserItem,
} from "@/services/userService";
import {
  getUserRoles,
  setUserRoles,
  type RoleItem,
} from "@/services/roleService";
import {
  getUserKbGrants,
  listAccessRoles,
  type AccessRole,
  type UserKbGrant,
} from "@/services/access";

import { useAccessScope } from "../hooks/useAccessScope";
import { OrgTree } from "../components/OrgTree";

const PAGE_SIZE = 50;

/**
 * 权限中心 Tab 1 「团队成员」
 * - 左 30%：OrgTree（部门树）+ 部门下成员列表
 * - 右 70%：选中成员详情 + 已分配角色 + 因角色可访问的 KB
 *
 * 不对称规则：Tab 1 分配角色下拉 = 本部门 + GLOBAL（由 listAccessRoles 的 deptId
 * 参数驱动）。共享（Tab 2）则取全部角色。
 */
export function MembersTab() {
  const scope = useAccessScope();
  const perms = usePermissions();
  const [searchParams, setSearchParams] = useSearchParams();

  // ─── 左侧 ─────────────────────────────────────────────
  const initialDeptId = scope.isSuperAdmin ? null : scope.deptId;
  const [selectedDeptId, setSelectedDeptId] = useState<string | null>(initialDeptId);

  const [users, setUsers] = useState<UserItem[]>([]);
  const [usersLoading, setUsersLoading] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [pendingUserId, setPendingUserId] = useState<string | null>(null);

  // ─── 右侧 ─────────────────────────────────────────────
  const [userRoles, setUserRolesState] = useState<RoleItem[]>([]);
  const [userKbGrants, setUserKbGrants] = useState<UserKbGrant[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  // ─── 弹窗 ─────────────────────────────────────────────
  const [deleteTarget, setDeleteTarget] = useState<UserItem | null>(null);
  const [assignOpen, setAssignOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);

  const selectedUser = useMemo(
    () => users.find((u) => u.id === selectedUserId) ?? null,
    [users, selectedUserId]
  );

  const loadUsers = useCallback(
    async (deptId: string | null, kw: string) => {
      try {
        setUsersLoading(true);
        const page = await getUsersPage(1, PAGE_SIZE, kw || undefined, deptId ?? undefined);
        setUsers(page.records || []);
      } catch (err) {
        toast.error(getErrorMessage(err, "加载成员失败"));
      } finally {
        setUsersLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    let cancelled = false;
    setUsersLoading(true);
    getUsersPage(1, PAGE_SIZE, keyword || undefined, selectedDeptId ?? undefined)
      .then((page) => {
        if (!cancelled) setUsers(page.records || []);
      })
      .catch((err) => {
        if (!cancelled) toast.error(getErrorMessage(err, "加载成员失败"));
      })
      .finally(() => {
        if (!cancelled) setUsersLoading(false);
      });
    if (!pendingUserId) {
      setSelectedUserId(null);
    }
    return () => {
      cancelled = true;
    };
    // keyword changes are handled by handleSearch() calling loadUsers directly.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedDeptId, pendingUserId]);

  useEffect(() => {
    if (!pendingUserId) return;
    if (users.some((user) => user.id === pendingUserId)) {
      setSelectedUserId(pendingUserId);
      setPendingUserId(null);
    }
  }, [pendingUserId, users]);

  useEffect(() => {
    const userIdParam = searchParams.get("userId");
    const deptIdParam = searchParams.get("deptId");
    if (!userIdParam) return;
    if (userIdParam === selectedUserId && (!deptIdParam || deptIdParam === selectedDeptId)) {
      return;
    }
    if (userIdParam === pendingUserId && (!deptIdParam || deptIdParam === selectedDeptId)) {
      return;
    }
    setPendingUserId(userIdParam);
    if (deptIdParam && deptIdParam !== selectedDeptId) {
      setSelectedDeptId(deptIdParam);
      return;
    }
    setSelectedUserId(userIdParam);
    setPendingUserId(null);
  }, [searchParams, selectedDeptId, selectedUserId, pendingUserId]);

  const loadDetail = useCallback(async (userId: string) => {
    try {
      setDetailLoading(true);
      const [roles, grants] = await Promise.all([
        getUserRoles(userId),
        getUserKbGrants(userId).catch((err) => {
          if (isRbacRejection(err)) return [] as UserKbGrant[];
          throw err;
        }),
      ]);
      setUserRolesState(roles);
      setUserKbGrants(grants);
    } catch (err) {
      toast.error(getErrorMessage(err, "加载成员详情失败"));
      setUserRolesState([]);
      setUserKbGrants([]);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!selectedUserId) {
      setUserRolesState([]);
      setUserKbGrants([]);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    Promise.all([
      getUserRoles(selectedUserId),
      getUserKbGrants(selectedUserId).catch((err) => {
        if (isRbacRejection(err)) return [] as UserKbGrant[];
        throw err;
      }),
    ])
      .then(([roles, grants]) => {
        if (cancelled) return;
        setUserRolesState(roles);
        setUserKbGrants(grants);
      })
      .catch((err) => {
        if (cancelled) return;
        toast.error(getErrorMessage(err, "加载成员详情失败"));
        setUserRolesState([]);
        setUserKbGrants([]);
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedUserId]);

  const handleSearch = () => loadUsers(selectedDeptId, keyword);

  const handleRefresh = async () => {
    await loadUsers(selectedDeptId, keyword);
    if (selectedUserId) await loadDetail(selectedUserId);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteUser(deleteTarget.id);
      toast.success("删除成功");
      if (selectedUserId === deleteTarget.id) setSelectedUserId(null);
      await loadUsers(selectedDeptId, keyword);
    } catch (err) {
      toast.error(getErrorMessage(err, "删除失败"));
    } finally {
      setDeleteTarget(null);
    }
  };

  const handleRemoveRole = async (roleId: string) => {
    if (!selectedUser) return;
    const remainingIds = userRoles.filter((r) => r.id !== roleId).map((r) => r.id);
    try {
      await setUserRoles(selectedUser.id, remainingIds);
      toast.success("已移除角色");
      await loadDetail(selectedUser.id);
      await loadUsers(selectedDeptId, keyword);
    } catch (err) {
      toast.error(getErrorMessage(err, "移除角色失败"));
    }
  };

  const handleJumpToSharing = (kbId: string) => {
    const next = new URLSearchParams(searchParams);
    next.set("tab", "sharing");
    next.set("kb", kbId);
    setSearchParams(next, { replace: false });
  };

  const scopeHint = scope.isSuperAdmin
    ? selectedDeptId
      ? "已按部门筛选"
      : "显示所有部门成员"
    : `仅显示 ${scope.deptName ?? "本"} 部门成员`;

  const canAddMember = perms.isAnyAdmin;
  const canManageSelected = selectedUser ? perms.canManageUser(selectedUser) : false;
  const isProtected = selectedUser?.username === "admin";

  return (
    <div className="grid gap-4 md:grid-cols-[320px_1fr]">
      {/* ─── 左栏 ────────────────────────────────────── */}
      <div className="space-y-3">
        <div className="rounded-md border bg-white p-3">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-semibold text-slate-500">组织架构</span>
            <Button variant="ghost" size="sm" onClick={handleRefresh} className="h-6 px-1">
              <RefreshCw className="h-3.5 w-3.5" />
            </Button>
          </div>
          <OrgTree
            selectedDeptId={selectedDeptId}
            onSelect={(deptId) => {
              setPendingUserId(null);
              setSelectedDeptId(deptId);
            }}
            countField="userCount"
          />
        </div>

        <div className="rounded-md border bg-white p-3">
          <div className="mb-2 flex items-center gap-2">
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleSearch()}
              placeholder="搜索成员用户名"
              className="h-8 text-xs"
            />
            <Button size="sm" variant="outline" onClick={handleSearch} className="h-8">
              搜索
            </Button>
          </div>
          <div className="mb-2 text-xs text-slate-400">{scopeHint}</div>
          <div className="max-h-[calc(100vh-360px)] overflow-y-auto">
            {usersLoading ? (
              <div className="py-6 text-center text-xs text-slate-400">加载中…</div>
            ) : users.length === 0 ? (
              <div className="py-6 text-center text-xs text-slate-400">暂无成员</div>
            ) : (
              <ul className="space-y-0.5">
                {users.map((u) => {
                  const isActive = selectedUserId === u.id;
                  return (
                    <li key={u.id}>
                      <button
                        type="button"
                        onClick={() => {
                          setPendingUserId(null);
                          setSelectedUserId(u.id);
                        }}
                        className={
                          "flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-sm hover:bg-slate-100 " +
                          (isActive ? "bg-[var(--vio-accent-mist)] text-vio-accent" : "")
                        }
                      >
                        <span className="flex items-center gap-1.5 truncate">
                          <span className="text-slate-400">👤</span>
                          <span className="truncate">{u.username}</span>
                        </span>
                        {u.deptName && (
                          <span className="ml-2 truncate rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">
                            {u.deptName}
                          </span>
                        )}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          {canAddMember && (
            <Button
              variant="outline"
              size="sm"
              className="mt-3 w-full"
              onClick={() => setCreateOpen(true)}
            >
              <UserPlus className="mr-1.5 h-3.5 w-3.5" />
              添加成员
            </Button>
          )}
        </div>
      </div>

      {/* ─── 右栏 ────────────────────────────────────── */}
      <div className="space-y-4">
        {!selectedUser ? (
          <div className="flex h-60 items-center justify-center rounded-md border border-dashed bg-white text-sm text-slate-400">
            请选择一位成员以查看详情
          </div>
        ) : (
          <>
            {/* 成员基本信息 */}
            <div className="rounded-md border bg-white p-4">
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-xl">👤</span>
                    <h3 className="text-lg font-semibold text-slate-900">
                      {selectedUser.username}
                    </h3>
                    {isProtected && (
                      <span className="rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-700">
                        默认管理员
                      </span>
                    )}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
                    <span>部门: {selectedUser.deptName ?? "-"}</span>
                    <span>最大密级: <SecurityLevelBadge level={selectedUser.maxSecurityLevel ?? 0} /></span>
                    <span>创建时间: {formatDateTime(selectedUser.createTime)}</span>
                  </div>
                </div>
                <div className="flex gap-2">
                  {canManageSelected && (
                    <Button variant="outline" size="sm" disabled title="暂不开放">
                      <Key className="mr-1.5 h-3.5 w-3.5" />
                      重置密码
                    </Button>
                  )}
                  {canManageSelected && !isProtected && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-destructive hover:text-destructive"
                      onClick={() => setDeleteTarget(selectedUser)}
                    >
                      <Trash2 className="mr-1.5 h-3.5 w-3.5" />
                      删除用户
                    </Button>
                  )}
                </div>
              </div>
            </div>

            {/* 已分配角色 */}
            <div className="rounded-md border bg-white p-4">
              <div className="mb-3 flex items-center justify-between">
                <span className="text-sm font-semibold text-slate-700">已分配角色</span>
                {canManageSelected && (
                  <Button size="sm" variant="outline" onClick={() => setAssignOpen(true)}>
                    <Plus className="mr-1 h-3.5 w-3.5" />
                    分配角色
                  </Button>
                )}
              </div>
              {detailLoading ? (
                <div className="py-4 text-center text-xs text-slate-400">加载中…</div>
              ) : userRoles.length === 0 ? (
                <div className="py-4 text-center text-xs text-slate-400">暂无角色</div>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>角色名</TableHead>
                      <TableHead className="w-[120px]">类型</TableHead>
                      <TableHead className="w-[100px]">密级天花板</TableHead>
                      {canManageSelected && <TableHead className="w-[80px]">操作</TableHead>}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {userRoles.map((r) => (
                      <TableRow key={r.id}>
                        <TableCell className="font-medium">{r.name}</TableCell>
                        <TableCell>
                          <span className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600">
                            {r.roleType}
                          </span>
                        </TableCell>
                        <TableCell>
                          <SecurityLevelBadge level={r.maxSecurityLevel ?? 0} />
                        </TableCell>
                        {canManageSelected && (
                          <TableCell>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-7 w-7 p-0 text-destructive"
                              onClick={() => handleRemoveRole(r.id)}
                              title="移除角色"
                            >
                              <UserMinus className="h-3.5 w-3.5" />
                            </Button>
                          </TableCell>
                        )}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </div>

            {/* 因角色可访问的 KB（只读派生视图） */}
            <div className="rounded-md border bg-white p-4">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-sm font-semibold text-slate-700">因角色可访问的知识库</span>
                <span className="text-xs text-slate-400">只读派生视图</span>
              </div>
              {detailLoading ? (
                <div className="py-4 text-center text-xs text-slate-400">加载中…</div>
              ) : userKbGrants.length === 0 ? (
                <div className="py-4 text-center text-xs text-slate-400">
                  该成员当前无可访问的知识库
                </div>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>KB 名</TableHead>
                      <TableHead className="w-[100px]">权限</TableHead>
                      <TableHead className="w-[100px]">密级</TableHead>
                      <TableHead>来自角色</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {userKbGrants.map((g) => (
                      <TableRow key={g.kbId}>
                        <TableCell>
                          <button
                            type="button"
                            onClick={() => handleJumpToSharing(g.kbId)}
                            className="text-vio-accent hover:underline"
                            title="跳转到 知识库共享 并定位此 KB"
                          >
                            {g.kbName}
                          </button>
                        </TableCell>
                        <TableCell>
                          <span className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600">
                            {g.permission ?? "-"}
                          </span>
                        </TableCell>
                        <TableCell>
                          <SecurityLevelBadge level={g.securityLevel ?? 0} />
                        </TableCell>
                        <TableCell>
                          <SourceRolesCell grant={g} roles={userRoles} />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </div>
          </>
        )}
      </div>

      {/* ─── 删除确认 ───────────────────────────────────── */}
      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除用户</AlertDialogTitle>
            <AlertDialogDescription>
              此操作将永久删除用户 <strong>{deleteTarget?.username}</strong>，无法恢复。
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

      {/* ─── 分配角色 Modal ─────────────────────────────── */}
      {selectedUser && (
        <AssignRolesDialog
          open={assignOpen}
          onOpenChange={setAssignOpen}
          user={selectedUser}
          currentRoles={userRoles}
          onSaved={async () => {
            setAssignOpen(false);
            await loadDetail(selectedUser.id);
            await loadUsers(selectedDeptId, keyword);
          }}
        />
      )}

      {/* ─── 添加成员 Modal ─────────────────────────────── */}
      <CreateMemberDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        defaultDeptId={selectedDeptId ?? scope.deptId ?? ""}
        onCreated={async () => {
          setCreateOpen(false);
          await loadUsers(selectedDeptId, keyword);
        }}
      />
    </div>
  );
}

// ─── 子组件 ────────────────────────────────────────────

function SourceRolesCell({ grant, roles }: { grant: UserKbGrant; roles: RoleItem[] }) {
  if (grant.implicit) {
    return (
      <span
        className="rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-700"
        title="DEPT_ADMIN 对本部门 KB 的隐式 MANAGE 权限"
      >
        部门隐式
      </span>
    );
  }
  const names = grant.sourceRoleIds
    .map((id) => roles.find((r) => r.id === id)?.name ?? id)
    .filter(Boolean);
  if (names.length === 0) return <span className="text-xs text-slate-400">-</span>;
  if (names.length === 1) return <span className="text-xs text-slate-600">{names[0]}</span>;
  return (
    <span
      className="cursor-help text-xs text-slate-600"
      title={names.join("、")}
    >
      {names[0]} +{names.length - 1}
    </span>
  );
}

function AssignRolesDialog({
  open,
  onOpenChange,
  user,
  currentRoles,
  onSaved,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  user: UserItem;
  currentRoles: RoleItem[];
  onSaved: () => void | Promise<void>;
}) {
  const scope = useAccessScope();
  const perms = usePermissions();
  const [loading, setLoading] = useState(false);
  const [candidates, setCandidates] = useState<AccessRole[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    // Seed selection on rising edge only; later re-renders of the parent must
    // not overwrite in-progress checkbox edits.
    setSelected(new Set(currentRoles.map((r) => r.id)));
    const targetDeptId = scope.isSuperAdmin ? user.deptId : scope.deptId;
    listAccessRoles({ deptId: targetDeptId, includeGlobal: true })
      .then((rs) => {
        if (!cancelled) setCandidates(rs);
      })
      .catch((err) => {
        if (!cancelled) toast.error(getErrorMessage(err, "加载角色失败"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // Intentionally omit currentRoles: it's a parent-owned array whose reference
    // changes on every render and would both refetch roles and stomp selections.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, user.deptId, scope.isSuperAdmin, scope.deptId]);

  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  };

  const filteredCandidates = candidates.filter((r) => perms.canAssignRole(r));

  const handleSave = async () => {
    try {
      await setUserRoles(user.id, Array.from(selected));
      toast.success("已更新角色分配");
      await onSaved();
    } catch (err) {
      toast.error(getErrorMessage(err, "保存失败"));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle>分配角色 · {user.username}</DialogTitle>
          <DialogDescription>
            仅本部门 + GLOBAL 角色可分配；保存后立即生效。
          </DialogDescription>
        </DialogHeader>
        {loading ? (
          <div className="py-6 text-center text-sm text-slate-400">加载中…</div>
        ) : filteredCandidates.length === 0 ? (
          <div className="py-6 text-center text-sm text-slate-400">暂无可分配角色</div>
        ) : (
          <div className="max-h-[320px] overflow-y-auto divide-y rounded-md border">
            {filteredCandidates.map((r) => (
              <label
                key={r.id}
                className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-slate-50"
              >
                <Checkbox
                  checked={selected.has(r.id)}
                  onCheckedChange={() => toggle(r.id)}
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 text-sm">
                    <span className="font-medium">{r.name}</span>
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">
                      {r.roleType}
                    </span>
                    {r.deptName && (
                      <span className="text-xs text-slate-400">{r.deptName}</span>
                    )}
                  </div>
                  {r.description && (
                    <div className="truncate text-xs text-slate-500">{r.description}</div>
                  )}
                </div>
              </label>
            ))}
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            <X className="mr-1.5 h-3.5 w-3.5" />
            取消
          </Button>
          <Button onClick={handleSave} disabled={loading}>
            保存
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CreateMemberDialog({
  open,
  onOpenChange,
  defaultDeptId,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  defaultDeptId: string;
  onCreated: () => void | Promise<void>;
}) {
  const scope = useAccessScope();
  const perms = usePermissions();
  const [form, setForm] = useState({
    username: "",
    password: "",
    deptId: defaultDeptId,
    roleIds: [] as string[],
  });
  const [candidates, setCandidates] = useState<AccessRole[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setForm((f) => ({ ...f, deptId: defaultDeptId }));
  }, [defaultDeptId, open]);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    const deptId = scope.isSuperAdmin ? form.deptId || null : scope.deptId;
    listAccessRoles({ deptId, includeGlobal: true })
      .then(setCandidates)
      .catch((err) => toast.error(getErrorMessage(err, "加载角色失败")))
      .finally(() => setLoading(false));
  }, [open, form.deptId, scope.isSuperAdmin, scope.deptId]);

  const deptLocked = !scope.isSuperAdmin;

  const toggleRole = (id: string) => {
    setForm((prev) => {
      const ids = new Set(prev.roleIds);
      if (ids.has(id)) ids.delete(id);
      else ids.add(id);
      return { ...prev, roleIds: Array.from(ids) };
    });
  };

  const handleSubmit = async () => {
    const username = form.username.trim();
    const password = form.password.trim();
    if (!username) return toast.error("请输入用户名");
    if (!password) return toast.error("请输入初始密码");
    if (!form.deptId) return toast.error("请选择部门");
    try {
      await createUser({
        username,
        password,
        deptId: form.deptId,
        roleIds: form.roleIds,
      });
      toast.success("创建成功");
      setForm({ username: "", password: "", deptId: defaultDeptId, roleIds: [] });
      await onCreated();
    } catch (err) {
      toast.error(getErrorMessage(err, "创建失败"));
    }
  };

  const assignableRoles = candidates.filter((r) => perms.canAssignRole(r));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle>添加成员</DialogTitle>
          <DialogDescription>
            设置账号基本信息与初始角色，创建后立即生效。
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium">用户名</label>
            <Input
              value={form.username}
              onChange={(e) => setForm((p) => ({ ...p, username: e.target.value }))}
              placeholder="请输入用户名"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium">初始密码</label>
            <Input
              type="password"
              value={form.password}
              onChange={(e) => setForm((p) => ({ ...p, password: e.target.value }))}
              placeholder="设置初始密码"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium">部门</label>
            {deptLocked ? (
              <div className="flex items-center gap-2 rounded-md border bg-slate-50 px-3 py-2 text-sm text-slate-700">
                <span>{scope.deptName ?? form.deptId}</span>
                <span className="ml-auto text-xs text-slate-400">（本部门锁定）</span>
              </div>
            ) : (
              <div className="text-xs text-slate-400">
                默认为当前左侧选中的部门；SUPER 新建可跨部门，暂不在此弹层切换部门
              </div>
            )}
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium">初始角色</label>
            {loading ? (
              <div className="py-3 text-center text-xs text-slate-400">加载中…</div>
            ) : assignableRoles.length === 0 ? (
              <div className="py-3 text-center text-xs text-slate-400">
                暂无可分配角色，可在 Tab 3 创建
              </div>
            ) : (
              <div className="max-h-[180px] overflow-y-auto divide-y rounded-md border">
                {assignableRoles.map((r) => (
                  <label
                    key={r.id}
                    className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-slate-50"
                  >
                    <Checkbox
                      checked={form.roleIds.includes(r.id)}
                      onCheckedChange={() => toggleRole(r.id)}
                    />
                    <div className="flex-1 min-w-0 text-sm">
                      <span className="font-medium">{r.name}</span>
                      <span className="ml-2 text-xs text-slate-500">[{r.roleType}]</span>
                    </div>
                  </label>
                ))}
              </div>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={loading}>
            <Plus className="mr-1.5 h-3.5 w-3.5" />
            创建
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
