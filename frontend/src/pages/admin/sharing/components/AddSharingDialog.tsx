import { useMemo, useState } from "react";
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
import type { RoleItem } from "@/services/roleService";

const PERMISSION_OPTIONS = ["READ", "WRITE", "MANAGE"] as const;
const SECURITY_OPTIONS = [
  { value: "0", label: "0 · 公开" },
  { value: "1", label: "1 · 内部" },
  { value: "2", label: "2 · 机密" },
  { value: "3", label: "3 · 绝密" },
];

export interface AddSharingValue {
  roleId: string;
  permission: string;
  maxSecurityLevel: number;
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  availableRoles: RoleItem[];
  onConfirm: (value: AddSharingValue) => Promise<void> | void;
}

export function AddSharingDialog({ open, onOpenChange, availableRoles, onConfirm }: Props) {
  const [roleId, setRoleId] = useState<string>("");
  const [permission, setPermission] = useState<string>("READ");
  const [securityLevel, setSecurityLevel] = useState<string>("0");
  const [keyword, setKeyword] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);

  const filteredRoles = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return availableRoles;
    return availableRoles.filter(
      (r) =>
        r.name.toLowerCase().includes(kw) ||
        (r.roleType || "").toLowerCase().includes(kw)
    );
  }, [availableRoles, keyword]);

  const handleSubmit = async () => {
    if (!roleId) return;
    setSubmitting(true);
    try {
      await onConfirm({
        roleId,
        permission,
        maxSecurityLevel: Number(securityLevel),
      });
      setRoleId("");
      setPermission("READ");
      setSecurityLevel("0");
      setKeyword("");
      onOpenChange(false);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle>添加共享</DialogTitle>
          <DialogDescription>选择角色并设置该角色在此知识库上的权限与密级上限。</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-2">
            <label className="text-sm font-medium text-slate-700">搜索角色</label>
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="按角色名或类型过滤"
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium text-slate-700">角色</label>
            <Select value={roleId} onValueChange={setRoleId}>
              <SelectTrigger>
                <SelectValue placeholder="请选择角色" />
              </SelectTrigger>
              <SelectContent>
                {filteredRoles.length === 0 ? (
                  <div className="px-2 py-3 text-sm text-slate-400">无可用角色</div>
                ) : (
                  filteredRoles.map((r) => (
                    <SelectItem key={r.id} value={r.id}>
                      {r.name} <span className="text-slate-400">({r.roleType})</span>
                    </SelectItem>
                  ))
                )}
              </SelectContent>
            </Select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">权限</label>
              <Select value={permission} onValueChange={setPermission}>
                <SelectTrigger>
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
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">密级上限</label>
              <Select value={securityLevel} onValueChange={setSecurityLevel}>
                <SelectTrigger>
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
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={!roleId || submitting}>
            {submitting ? "保存中..." : "确定"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
