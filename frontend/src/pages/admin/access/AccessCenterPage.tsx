import { useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { Users, Share2, ShieldCheck, Building2 } from "lucide-react";
import { cn } from "@/lib/utils";

import { AccessScopeProvider, useAccessScope } from "./hooks/useAccessScope";
import { AccessBanner } from "./components/AccessBanner";
import { MembersTab } from "./tabs/MembersTab";
import { SharingTab } from "./tabs/SharingTab";
import { RolesTab } from "./tabs/RolesTab";
import { DepartmentsTab } from "./tabs/DepartmentsTab";

type TabId = "members" | "sharing" | "roles" | "departments";

interface TabDef {
  id: TabId;
  label: string;
  icon: typeof Users;
  /** true 时仅 SUPER 可见；Tab 4 是部门管理，DEPT_ADMIN 不渲染 */
  superOnly?: boolean;
}

const TABS: TabDef[] = [
  { id: "members", label: "团队成员", icon: Users },
  { id: "sharing", label: "知识库共享", icon: Share2 },
  { id: "roles", label: "角色管理", icon: ShieldCheck },
  { id: "departments", label: "部门组织", icon: Building2, superOnly: true },
];

function isTabId(v: string | null): v is TabId {
  return v === "members" || v === "sharing" || v === "roles" || v === "departments";
}

function AccessCenterInner() {
  const scope = useAccessScope();
  const [searchParams, setSearchParams] = useSearchParams();

  const visibleTabs = useMemo(
    () => TABS.filter((t) => !t.superOnly || scope.isSuperAdmin),
    [scope.isSuperAdmin]
  );

  const requestedTab = searchParams.get("tab");
  const activeTab: TabId = useMemo(() => {
    if (isTabId(requestedTab) && visibleTabs.some((t) => t.id === requestedTab)) {
      return requestedTab;
    }
    return visibleTabs[0]?.id ?? "members";
  }, [requestedTab, visibleTabs]);

  const handleSelect = (id: TabId) => {
    const next = new URLSearchParams(searchParams);
    next.set("tab", id);
    setSearchParams(next, { replace: true });
  };

  return (
    <div className="space-y-4">
      <AccessBanner />

      <div className="rounded-lg border bg-white">
        <div className="flex flex-wrap gap-1 border-b px-2 pt-2">
          {visibleTabs.map((tab) => {
            const Icon = tab.icon;
            const isActive = tab.id === activeTab;
            return (
              <button
                key={tab.id}
                type="button"
                onClick={() => handleSelect(tab.id)}
                className={cn(
                  "inline-flex items-center gap-2 rounded-t-md px-3 py-2 text-sm font-medium transition-colors",
                  isActive
                    ? "border border-b-0 border-slate-200 bg-white text-indigo-600"
                    : "text-slate-500 hover:text-slate-700"
                )}
              >
                <Icon className="h-4 w-4" />
                {tab.label}
              </button>
            );
          })}
        </div>

        <div className="p-4">
          {activeTab === "members" && <MembersTab />}
          {activeTab === "sharing" && <SharingTab />}
          {activeTab === "roles" && <RolesTab />}
          {activeTab === "departments" && scope.isSuperAdmin && <DepartmentsTab />}
        </div>
      </div>
    </div>
  );
}

export function AccessCenterPage() {
  return (
    <AccessScopeProvider>
      <AccessCenterInner />
    </AccessScopeProvider>
  );
}

export default AccessCenterPage;
