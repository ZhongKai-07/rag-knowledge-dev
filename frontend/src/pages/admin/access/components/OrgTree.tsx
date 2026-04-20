import { useEffect, useMemo, useState } from "react";
import { Building2, ChevronDown, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { getDepartmentsTree, type AccessDeptNode } from "@/services/access";
import { getErrorMessage } from "@/utils/error";
import { toast } from "sonner";
import { useAccessScope } from "../hooks/useAccessScope";

export type OrgTreeCountField = "userCount" | "roleCount" | "kbCount";

interface Props {
  /** 选中节点的 dept id；null 表示"全公司"聚合视图（仅 SUPER 可用） */
  selectedDeptId: string | null;
  onSelect: (deptId: string | null, deptName?: string | null) => void;
  /** 节点右侧显示哪一类计数。Tab 1 = userCount, Tab 3 = roleCount */
  countField: OrgTreeCountField;
  /** 允许选中"全公司"聚合节点；默认 true（SUPER 视角） */
  allowAllNode?: boolean;
  /** DEPT_ADMIN 是否只看本部门；Tab 3 传 false 以支持跨部门只读浏览 */
  restrictToOwnDept?: boolean;
  className?: string;
}

/**
 * Tab 1 / Tab 3 共用的组织树：
 * - SUPER：树根 "🏢 全公司" + 所有部门
 * - DEPT_ADMIN：默认仅渲染本部门单节点；Tab 3 可显式放开为全量只读可见
 */
export function OrgTree({
  selectedDeptId,
  onSelect,
  countField,
  allowAllNode = true,
  restrictToOwnDept = true,
  className,
}: Props) {
  const scope = useAccessScope();
  const [loading, setLoading] = useState(true);
  const [nodes, setNodes] = useState<AccessDeptNode[]>([]);
  const [expanded, setExpanded] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getDepartmentsTree()
      .then((list) => {
        if (cancelled) return;
        setNodes(list);
      })
      .catch((err) => {
        if (cancelled) return;
        toast.error(getErrorMessage(err, "加载部门树失败"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const visibleNodes = useMemo(() => {
    if (scope.isSuperAdmin) return nodes;
    if (scope.isDeptAdmin && scope.deptId) {
      return restrictToOwnDept ? nodes.filter((n) => n.id === scope.deptId) : nodes;
    }
    return [];
  }, [nodes, restrictToOwnDept, scope]);

  const totalCount = useMemo(
    () => visibleNodes.reduce((acc, n) => acc + (n[countField] ?? 0), 0),
    [visibleNodes, countField]
  );

  const showAllNode = allowAllNode && scope.isSuperAdmin;

  if (loading) {
    return (
      <div className={cn("p-3 text-xs text-slate-400", className)}>加载部门中…</div>
    );
  }

  return (
    <div className={cn("space-y-0.5 text-sm", className)}>
      {showAllNode && (
        <div
          className={cn(
            "flex items-center justify-between rounded-md px-2 py-1.5 hover:bg-slate-100",
            selectedDeptId === null && "bg-indigo-50 text-indigo-700"
          )}
        >
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => setExpanded((v) => !v)}
              className="rounded p-0.5 hover:bg-slate-200"
              aria-label={expanded ? "折叠" : "展开"}
            >
              {expanded ? (
                <ChevronDown className="h-3.5 w-3.5" />
              ) : (
                <ChevronRight className="h-3.5 w-3.5" />
              )}
            </button>
            <button
              type="button"
              onClick={() => onSelect(null, "全公司")}
              className="flex items-center gap-1.5 text-left"
            >
              <Building2 className="h-4 w-4" />
              <span className="font-medium">全公司</span>
            </button>
          </div>
          <span className="text-xs text-slate-400">{totalCount}</span>
        </div>
      )}

      {(expanded || !showAllNode) && visibleNodes.map((node) => {
        const isActive = selectedDeptId === node.id;
        return (
          <button
            key={node.id}
            type="button"
            onClick={() => onSelect(node.id, node.deptName)}
            className={cn(
              "flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left hover:bg-slate-100",
              showAllNode && "pl-7",
              isActive && "bg-indigo-50 text-indigo-700"
            )}
          >
            <span className="flex items-center gap-1.5 truncate">
              <span className="text-slate-400">📂</span>
              <span className="truncate">{node.deptName}</span>
              {node.systemReserved && (
                <span className="rounded bg-slate-100 px-1 text-[10px] text-slate-500">
                  GLOBAL
                </span>
              )}
            </span>
            <span className="text-xs text-slate-400">{node[countField] ?? 0}</span>
          </button>
        );
      })}

      {visibleNodes.length === 0 && (
        <div className="px-2 py-3 text-xs text-slate-400">暂无部门</div>
      )}
    </div>
  );
}
