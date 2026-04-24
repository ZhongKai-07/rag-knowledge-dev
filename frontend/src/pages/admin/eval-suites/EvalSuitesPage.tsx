import { useSearchParams } from "react-router-dom";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { GoldSetListTab } from "./tabs/GoldSetListTab";
import { RunsPlaceholderTab } from "./tabs/placeholders/RunsPlaceholderTab";
import { TrendsPlaceholderTab } from "./tabs/placeholders/TrendsPlaceholderTab";

const VALID_TABS = ["gold-sets", "runs", "trends"] as const;
type TabId = (typeof VALID_TABS)[number];

export function EvalSuitesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get("tab") as TabId) ?? "gold-sets";
  const activeTab: TabId = VALID_TABS.includes(tab) ? tab : "gold-sets";

  const setTab = (t: TabId) => {
    const next = new URLSearchParams(searchParams);
    next.set("tab", t);
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
      <Tabs value={activeTab} onValueChange={(v) => setTab(v as TabId)}>
        <TabsList>
          <TabsTrigger value="gold-sets">黄金集</TabsTrigger>
          <TabsTrigger value="runs">评估运行</TabsTrigger>
          <TabsTrigger value="trends">趋势对比</TabsTrigger>
        </TabsList>
        <TabsContent value="gold-sets">
          <GoldSetListTab />
        </TabsContent>
        <TabsContent value="runs">
          <RunsPlaceholderTab />
        </TabsContent>
        <TabsContent value="trends">
          <TrendsPlaceholderTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
