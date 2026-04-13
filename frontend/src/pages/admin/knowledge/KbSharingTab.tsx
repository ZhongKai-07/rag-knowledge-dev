import { useEffect, useState } from "react";
import { Plus, Trash2, Save } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { getRoles, RoleItem } from "@/services/roleService";
import {
  getKbRoleBindings,
  setKbRoleBindings,
  KbRoleBindingVO,
  KbRoleBindingRequest,
} from "@/services/knowledgeService";

interface Props {
  kbId: string;
}

const PERMISSION_OPTIONS = ["READ", "WRITE", "MANAGE"] as const;

export default function KbSharingTab({ kbId }: Props) {
  const [bindings, setBindings] = useState<KbRoleBindingRequest[]>([]);
  const [allRoles, setAllRoles] = useState<RoleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const [roles, existing] = await Promise.all([getRoles(), getKbRoleBindings(kbId)]);
        setAllRoles(roles);
        setBindings(
          existing.map((b: KbRoleBindingVO) => ({
            roleId: b.roleId,
            permission: b.permission,
            maxSecurityLevel: b.maxSecurityLevel,
          }))
        );
      } catch {
        toast.error("加载失败");
      } finally {
        setLoading(false);
      }
    })();
  }, [kbId]);

  const addBinding = () => {
    const usedIds = new Set(bindings.map((b) => b.roleId));
    const available = allRoles.find((r) => !usedIds.has(r.id));
    if (!available) {
      toast.info("所有角色已添加");
      return;
    }
    setBindings((prev) => [
      ...prev,
      { roleId: available.id, permission: "READ", maxSecurityLevel: 0 },
    ]);
  };

  const removeBinding = (idx: number) => {
    setBindings((prev) => prev.filter((_, i) => i !== idx));
  };

  const updateBinding = (idx: number, field: string, value: string | number) => {
    setBindings((prev) =>
      prev.map((b, i) => (i === idx ? { ...b, [field]: value } : b))
    );
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await setKbRoleBindings(kbId, bindings);
      toast.success("保存成功");
    } catch {
      toast.error("保存失败");
    } finally {
      setSaving(false);
    }
  };

  const getRoleName = (roleId: string) =>
    allRoles.find((r) => r.id === roleId)?.name ?? roleId;

  if (loading) return <div className="p-4 text-muted-foreground">加载中...</div>;

  return (
    <div className="space-y-4 p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-medium">共享管理</h3>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={addBinding}>
            <Plus className="mr-1 h-4 w-4" /> 添加角色
          </Button>
          <Button size="sm" onClick={handleSave} disabled={saving}>
            <Save className="mr-1 h-4 w-4" /> {saving ? "保存中..." : "保存"}
          </Button>
        </div>
      </div>

      {bindings.length === 0 ? (
        <p className="text-sm text-muted-foreground">暂无角色绑定</p>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="pb-2">角色</th>
              <th className="pb-2">权限</th>
              <th className="pb-2">安全等级</th>
              <th className="pb-2 w-16"></th>
            </tr>
          </thead>
          <tbody>
            {bindings.map((b, idx) => (
              <tr key={idx} className="border-b">
                <td className="py-2">
                  <Select
                    value={b.roleId}
                    onValueChange={(v) => updateBinding(idx, "roleId", v)}
                  >
                    <SelectTrigger className="w-48">
                      <SelectValue>{getRoleName(b.roleId)}</SelectValue>
                    </SelectTrigger>
                    <SelectContent>
                      {allRoles.map((r) => (
                        <SelectItem key={r.id} value={r.id}>
                          {r.name} ({r.roleType})
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </td>
                <td className="py-2">
                  <Select
                    value={b.permission}
                    onValueChange={(v) => updateBinding(idx, "permission", v)}
                  >
                    <SelectTrigger className="w-32">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {PERMISSION_OPTIONS.map((p) => (
                        <SelectItem key={p} value={p}>{p}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </td>
                <td className="py-2">
                  <Select
                    value={String(b.maxSecurityLevel ?? 0)}
                    onValueChange={(v) => updateBinding(idx, "maxSecurityLevel", Number(v))}
                  >
                    <SelectTrigger className="w-24">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {[0, 1, 2, 3].map((lvl) => (
                        <SelectItem key={lvl} value={String(lvl)}>
                          Level {lvl}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </td>
                <td className="py-2">
                  <Button variant="ghost" size="icon" onClick={() => removeBinding(idx)}>
                    <Trash2 className="h-4 w-4 text-destructive" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
