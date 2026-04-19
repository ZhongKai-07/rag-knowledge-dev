import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Search } from "lucide-react";
import { toast } from "sonner";

import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

import {
  getKnowledgeBases,
  getKbRoleBindings,
  type KbRoleBindingVO,
  type KnowledgeBase,
} from "@/services/knowledgeService";
import type { RoleItem } from "@/services/roleService";
import { listAccessRoles } from "@/services/access";
import { getErrorMessage } from "@/utils/error";
import { usePermissions } from "@/utils/permissions";

import { KbSharingCard } from "./components/KbSharingCard";

type SortKey = "recent" | "name";

export function SharingPage() {
  const permissions = usePermissions();
  const [searchParams] = useSearchParams();
  const highlightKbId = searchParams.get("kb");
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [allRoles, setAllRoles] = useState<RoleItem[]>([]);
  const [bindingsMap, setBindingsMap] = useState<Record<string, KbRoleBindingVO[]>>({});
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState("");
  const [deptFilter, setDeptFilter] = useState<string>("all");
  const [sort, setSort] = useState<SortKey>("recent");

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        // Sharing 下拉范围 = 全部角色（含跨部门）；由 KB 所属部门 admin 决策。
        // /access/roles 携带 deptId/deptName，供 ⚡ 跨部门徽章使用。
        const [kbList, accessRoles] = await Promise.all([
          getKnowledgeBases(1, 200),
          listAccessRoles({ includeGlobal: true }),
        ]);
        if (cancelled) return;
        setKbs(kbList);
        setAllRoles(accessRoles);
        const bindingsEntries = await Promise.all(
          kbList.map(async (kb) => {
            try {
              const list = await getKbRoleBindings(kb.id);
              return [kb.id, list] as const;
            } catch {
              return [kb.id, [] as KbRoleBindingVO[]] as const;
            }
          })
        );
        if (cancelled) return;
        const map: Record<string, KbRoleBindingVO[]> = {};
        bindingsEntries.forEach(([id, list]) => (map[id] = list));
        setBindingsMap(map);
      } catch (err) {
        toast.error(getErrorMessage(err, "加载共享数据失败"));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const deptOptions = useMemo(() => {
    const seen = new Map<string, string>();
    kbs.forEach((kb) => {
      const id = kb.deptId ?? "";
      const name = kb.deptName ?? "—";
      if (id && !seen.has(id)) seen.set(id, name);
    });
    return Array.from(seen.entries()).map(([id, name]) => ({ id, name }));
  }, [kbs]);

  const filteredKbs = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    let list = kbs.filter((kb) => {
      if (kw && !kb.name.toLowerCase().includes(kw)) return false;
      if (deptFilter !== "all" && (kb.deptId ?? "") !== deptFilter) return false;
      return true;
    });
    if (sort === "name") {
      list = [...list].sort((a, b) => a.name.localeCompare(b.name));
    } else {
      list = [...list].sort((a, b) => (b.updateTime || "").localeCompare(a.updateTime || ""));
    }
    return list;
  }, [kbs, keyword, deptFilter, sort]);

  const handleBindingsChange = (kbId: string, next: KbRoleBindingVO[]) => {
    setBindingsMap((prev) => ({ ...prev, [kbId]: next }));
  };

  return (
    <div className="space-y-4">
      <header className="rounded-lg border bg-white p-4">
        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">共享管理</h1>
            <p className="mt-1 text-sm text-slate-500">
              集中管理所有知识库的角色共享。添加或移除共享立即生效。
            </p>
          </div>
          <div className="text-right text-xs text-slate-500">
            <div>
              当前身份：
              <span className="font-medium text-slate-700">
                {permissions.isSuperAdmin
                  ? "超级管理员"
                  : permissions.isDeptAdmin
                  ? `${permissions.deptName ?? ""}管理员`
                  : "成员"}
              </span>
            </div>
            <div>
              管理范围：
              <span className="font-medium text-slate-700">
                {permissions.isSuperAdmin
                  ? "全公司"
                  : permissions.deptName
                  ? `${permissions.deptName} 部门`
                  : "—"}
              </span>
            </div>
          </div>
        </div>
      </header>

      <div className="flex flex-wrap items-center gap-3 rounded-lg border bg-white p-3">
        <div className="relative flex-1 min-w-[220px]">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索知识库名称"
            className="pl-8"
          />
        </div>
        <Select value={deptFilter} onValueChange={setDeptFilter}>
          <SelectTrigger className="w-40">
            <SelectValue placeholder="部门" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">全部部门</SelectItem>
            {deptOptions.map((opt) => (
              <SelectItem key={opt.id} value={opt.id}>
                {opt.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={sort} onValueChange={(v) => setSort(v as SortKey)}>
          <SelectTrigger className="w-36">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="recent">最近修改</SelectItem>
            <SelectItem value="name">按名称</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {loading ? (
        <div className="rounded-lg border bg-white p-8 text-center text-sm text-slate-500">
          加载中...
        </div>
      ) : filteredKbs.length === 0 ? (
        <div className="rounded-lg border bg-white p-8 text-center text-sm text-slate-500">
          暂无匹配的知识库
        </div>
      ) : (
        <div className="space-y-3">
          {filteredKbs.map((kb) => (
            <KbSharingCard
              key={kb.id}
              kb={kb}
              bindings={bindingsMap[kb.id] ?? []}
              allRoles={allRoles}
              onBindingsChange={handleBindingsChange}
              highlight={highlightKbId === kb.id}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export default SharingPage;
