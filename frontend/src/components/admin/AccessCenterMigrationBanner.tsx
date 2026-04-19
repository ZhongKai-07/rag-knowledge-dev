import { ArrowRight, Info } from "lucide-react";
import { Link } from "react-router-dom";

import { cn } from "@/lib/utils";

interface Props {
  /** 跳转到的 access-center tab，例如 "members" / "roles" / "departments" / "sharing" */
  tab: "members" | "roles" | "departments" | "sharing";
  /** 当前页面名，用于提示文案 */
  pageName: string;
  className?: string;
}

const TAB_LABEL: Record<Props["tab"], string> = {
  members: "团队成员",
  roles: "角色管理",
  departments: "部门组织",
  sharing: "知识库共享",
};

/**
 * P1.6: 旧 admin 路由顶部的迁移提示 banner。
 * 设计文档 §D9：旧 4 路由保留 30 天，顶部加"已迁移"提示；P2.2 再改 301 重定向。
 */
export function AccessCenterMigrationBanner({ tab, pageName, className }: Props) {
  return (
    <div
      className={cn(
        "mb-4 flex flex-wrap items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900",
        className
      )}
      role="status"
    >
      <Info className="h-4 w-4 flex-shrink-0 text-amber-600" />
      <div className="flex-1 min-w-0">
        <span className="font-medium">本页面已迁移至「权限中心」</span>
        <span className="ml-2 text-amber-700">
          {pageName}现已归入 <code className="rounded bg-white/60 px-1">/admin/access</code> 的「{TAB_LABEL[tab]}」Tab 下，统一管理。旧入口将保留观察期至 2026-05-20。
        </span>
      </div>
      <Link
        to={`/admin/access?tab=${tab}`}
        className="inline-flex items-center gap-1 rounded-md bg-amber-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-amber-700"
      >
        打开权限中心
        <ArrowRight className="h-3 w-3" />
      </Link>
    </div>
  );
}
