import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Plus, Trash2, Zap } from "lucide-react";
import { toast } from "sonner";

import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

import {
  setKbRoleBindings,
  type KbRoleBindingRequest,
  type KbRoleBindingVO,
  type KnowledgeBase,
} from "@/services/knowledgeService";
import type { RoleItem } from "@/services/roleService";
import { getErrorMessage } from "@/utils/error";

import { AddSharingDialog, type AddSharingValue } from "./AddSharingDialog";

const PERMISSION_OPTIONS = ["READ", "WRITE", "MANAGE"] as const;
const SECURITY_OPTIONS = [
  { value: "0", label: "公开" },
  { value: "1", label: "内部" },
  { value: "2", label: "机密" },
  { value: "3", label: "绝密" },
];

interface Props {
  kb: KnowledgeBase;
  bindings: KbRoleBindingVO[];
  allRoles: RoleItem[];
  onBindingsChange: (kbId: string, next: KbRoleBindingVO[]) => void;
  /** 深链定位：匹配时卡片高亮 + 滚动到视野（Tab 1 点 KB 名跳转用） */
  highlight?: boolean;
}

export function KbSharingCard({ kb, bindings, allRoles, onBindingsChange, highlight = false }: Props) {
  const cardRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (highlight && cardRef.current) {
      cardRef.current.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  }, [highlight]);
  const [addOpen, setAddOpen] = useState(false);
  const [removeTarget, setRemoveTarget] = useState<KbRoleBindingVO | null>(null);
  const [downgradeTarget, setDowngradeTarget] = useState<
    | { binding: KbRoleBindingVO; nextLevel: number }
    | null
  >(null);
  const [busy, setBusy] = useState(false);

  const roleMap = useMemo(() => {
    const m = new Map<string, RoleItem>();
    allRoles.forEach((r) => m.set(r.id, r));
    return m;
  }, [allRoles]);

  const availableRolesForAdd = useMemo(() => {
    const used = new Set(bindings.map((b) => b.roleId));
    return allRoles.filter((r) => !used.has(r.id));
  }, [allRoles, bindings]);

  const toRequest = (list: KbRoleBindingVO[]): KbRoleBindingRequest[] =>
    list.map((b) => ({
      roleId: b.roleId,
      permission: b.permission,
      maxSecurityLevel: b.maxSecurityLevel,
    }));

  const persist = async (next: KbRoleBindingVO[]): Promise<boolean> => {
    setBusy(true);
    try {
      await setKbRoleBindings(kb.id, toRequest(next));
      onBindingsChange(kb.id, next);
      return true;
    } catch (err) {
      toast.error(getErrorMessage(err, "保存失败"));
      return false;
    } finally {
      setBusy(false);
    }
  };

  const handleAdd = async (value: AddSharingValue) => {
    const role = roleMap.get(value.roleId);
    const next: KbRoleBindingVO[] = [
      ...bindings,
      {
        roleId: value.roleId,
        roleName: role?.name ?? value.roleId,
        roleType: role?.roleType ?? "",
        permission: value.permission,
        maxSecurityLevel: value.maxSecurityLevel,
        deptId: role?.deptId ?? null,
        deptName: role?.deptName ?? null,
      },
    ];
    const ok = await persist(next);
    if (ok) toast.success("已添加共享");
  };

  const handleRemove = async (target: KbRoleBindingVO) => {
    const next = bindings.filter((b) => b.roleId !== target.roleId);
    const ok = await persist(next);
    if (ok) toast.success("已移除共享");
    setRemoveTarget(null);
  };

  const handleUpdateField = async (
    target: KbRoleBindingVO,
    field: "permission" | "maxSecurityLevel",
    value: string
  ) => {
    const next = bindings.map((b) =>
      b.roleId === target.roleId
        ? {
            ...b,
            ...(field === "maxSecurityLevel"
              ? { maxSecurityLevel: Number(value) }
              : { permission: value }),
          }
        : b
    );
    await persist(next);
  };

  const handleSecurityChange = (target: KbRoleBindingVO, value: string) => {
    const nextLevel = Number(value);
    if (nextLevel < target.maxSecurityLevel) {
      setDowngradeTarget({ binding: target, nextLevel });
      return;
    }
    handleUpdateField(target, "maxSecurityLevel", value);
  };

  const confirmDowngrade = async () => {
    if (!downgradeTarget) return;
    await handleUpdateField(
      downgradeTarget.binding,
      "maxSecurityLevel",
      String(downgradeTarget.nextLevel)
    );
    setDowngradeTarget(null);
  };

  return (
    <Card
      ref={cardRef}
      className={cn(
        "shadow-sm transition-all",
        highlight && "ring-2 ring-indigo-400 shadow-md"
      )}
    >
      <CardHeader className="flex flex-row items-start justify-between space-y-0 pb-3">
        <div className="space-y-1">
          <CardTitle className="text-base">
            {kb.name}
            <span className="ml-2 text-xs font-normal text-slate-500">
              部门：{kb.deptName || "—"}
              {kb.documentCount != null && ` · 文档 ${kb.documentCount}`}
            </span>
          </CardTitle>
          <p className="text-xs text-slate-500">
            已共享 {bindings.length} 个角色
          </p>
        </div>
        <Button
          size="sm"
          variant="outline"
          onClick={() => setAddOpen(true)}
          disabled={busy || availableRolesForAdd.length === 0}
        >
          <Plus className="mr-1 h-4 w-4" /> 添加共享
        </Button>
      </CardHeader>

      <CardContent>
        {bindings.length === 0 ? (
          <p className="rounded border border-dashed border-slate-200 p-4 text-center text-sm text-slate-400">
            暂无共享角色
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-slate-500">
                <th className="pb-2 font-medium">角色</th>
                <th className="pb-2 font-medium">类型</th>
                <th className="pb-2 font-medium">权限</th>
                <th className="pb-2 font-medium">密级</th>
                <th className="pb-2 font-medium">归属</th>
                <th className="w-12 pb-2 font-medium"></th>
              </tr>
            </thead>
            <tbody>
              {bindings.map((b) => {
                const isCrossDept =
                  !!kb.deptId && !!b.deptId && b.deptId !== kb.deptId;
                return (
                <tr key={b.roleId} className="border-b last:border-0">
                  <td className="py-2">
                    <Link
                      to={`/admin/access?tab=roles&roleId=${b.roleId}`}
                      className="font-medium text-vio-accent hover:underline"
                      title="跳转到角色管理并定位此角色"
                    >
                      {b.roleName}
                    </Link>
                  </td>
                  <td className="py-2">
                    <Badge variant="outline" className="text-xs">
                      {b.roleType}
                    </Badge>
                  </td>
                  <td className="py-2">
                    <Select
                      value={b.permission}
                      onValueChange={(v) => handleUpdateField(b, "permission", v)}
                      disabled={busy}
                    >
                      <SelectTrigger className="h-8 w-28">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {PERMISSION_OPTIONS.map((p) => (
                          <SelectItem key={p} value={p}>
                            {p}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </td>
                  <td className="py-2">
                    <Select
                      value={String(b.maxSecurityLevel ?? 0)}
                      onValueChange={(v) => handleSecurityChange(b, v)}
                      disabled={busy}
                    >
                      <SelectTrigger className="h-8 w-24">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {SECURITY_OPTIONS.map((opt) => (
                          <SelectItem key={opt.value} value={opt.value}>
                            {opt.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </td>
                  <td className="py-2">
                    {isCrossDept ? (
                      <span
                        className="inline-flex items-center gap-1 rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-700"
                        title={`跨部门：角色归属「${b.deptName ?? b.deptId}」，KB 归属「${kb.deptName ?? kb.deptId}」`}
                      >
                        <Zap className="h-3 w-3" />
                        {b.deptName ?? b.deptId}
                      </span>
                    ) : b.deptId === kb.deptId ? (
                      <span className="text-xs text-slate-500">本部门</span>
                    ) : (
                      <span className="text-xs text-slate-400">{b.deptName ?? "—"}</span>
                    )}
                  </td>
                  <td className="py-2">
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => setRemoveTarget(b)}
                      disabled={busy}
                    >
                      <Trash2 className="h-4 w-4 text-destructive" />
                    </Button>
                  </td>
                </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </CardContent>

      <AddSharingDialog
        open={addOpen}
        onOpenChange={setAddOpen}
        availableRoles={availableRolesForAdd}
        onConfirm={handleAdd}
      />

      <AlertDialog
        open={Boolean(removeTarget)}
        onOpenChange={(open) => (!open ? setRemoveTarget(null) : null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>移除共享？</AlertDialogTitle>
            <AlertDialogDescription>
              角色「{removeTarget?.roleName}」将失去对知识库「{kb.name}」的访问权限。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground"
              onClick={() => removeTarget && handleRemove(removeTarget)}
            >
              确认移除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog
        open={Boolean(downgradeTarget)}
        onOpenChange={(open) => (!open ? setDowngradeTarget(null) : null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认下调密级？</AlertDialogTitle>
            <AlertDialogDescription>
              此操作会限制该角色在此 KB 下可见的文档（只能看到密级 ≤ {downgradeTarget?.nextLevel} 的文档）。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDowngrade}>确认</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
