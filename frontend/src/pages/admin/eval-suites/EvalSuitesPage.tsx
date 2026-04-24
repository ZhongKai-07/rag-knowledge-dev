import { useSearchParams } from "react-router-dom";
import { cn } from "@/lib/utils";
import { GoldSetListTab } from "./tabs/GoldSetListTab";
import { RunsPlaceholderTab } from "./tabs/placeholders/RunsPlaceholderTab";
import { TrendsPlaceholderTab } from "./tabs/placeholders/TrendsPlaceholderTab";

const VALID_TABS = ["gold-sets", "runs", "trends"] as const;
type TabId = (typeof VALID_TABS)[number];

interface TabDef {
  id: TabId;
  label: string;
}

const TABS: TabDef[] = [
  { id: "gold-sets", label: "黄金集" },
  { id: "runs", label: "评估运行" },
  { id: "trends", label: "趋势对比" }
];

function isTabId(v: string | null): v is TabId {
  return v !== null && (VALID_TABS as readonly string[]).includes(v);
}

export function EvalSuitesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get("tab");
  const activeTab: TabId = isTabId(requested) ? requested : "gold-sets";

  const handleSelect = (id: TabId) => {
    const next = new URLSearchParams(searchParams);
    next.set("tab", id);
    setSearchParams(next, { replace: true });
  };

  return (
    <div className="space-y-4 p-6">
      <div>
        <h1 className="text-2xl font-semibold">质量评估</h1>
        <p className="mt-1 text-sm text-slate-500">
          Gold Set 合成 / 审核 / 评估运行 / 趋势对比——RAG 调优闭环入口。
        </p>
      </div>

      <div className="rounded-lg border bg-white">
        <div className="flex flex-wrap gap-1 border-b px-2 pt-2">
          {TABS.map((tab) => {
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
                {tab.label}
              </button>
            );
          })}
        </div>

        <div className="p-4">
          {activeTab === "gold-sets" && <GoldSetListTab />}
          {activeTab === "runs" && <RunsPlaceholderTab />}
          {activeTab === "trends" && <TrendsPlaceholderTab />}
        </div>
      </div>
    </div>
  );
}
