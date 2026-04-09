import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Database, FileText, LogOut, Settings } from "lucide-react";

import { useAuthStore } from "@/stores/authStore";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import { getSpacesStats, type SpacesStats } from "@/services/spacesService";

/** Derive a deterministic hue from a string (for the KB icon background). */
function nameToHue(name: string): number {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return ((hash % 360) + 360) % 360;
}

/** Color palette entries keyed by hue bucket for the KB card icon. */
const COLOR_PALETTE = [
  { bg: "bg-blue-100", text: "text-blue-700" },
  { bg: "bg-emerald-100", text: "text-emerald-700" },
  { bg: "bg-violet-100", text: "text-violet-700" },
  { bg: "bg-amber-100", text: "text-amber-700" },
  { bg: "bg-rose-100", text: "text-rose-700" },
  { bg: "bg-cyan-100", text: "text-cyan-700" },
  { bg: "bg-indigo-100", text: "text-indigo-700" },
  { bg: "bg-orange-100", text: "text-orange-700" }
];

function nameToColor(name: string) {
  const hue = nameToHue(name);
  return COLOR_PALETTE[Math.floor(hue / 45) % COLOR_PALETTE.length];
}

function formatDate(dateStr?: string | null): string {
  if (!dateStr) return "-";
  try {
    const d = new Date(dateStr);
    return d.toLocaleDateString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit"
    });
  } catch {
    return dateStr;
  }
}

export function SpacesPage() {
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();

  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [stats, setStats] = useState<SpacesStats | null>(null);
  const [loading, setLoading] = useState(true);

  const isAdmin = user?.role === "admin";
  const avatarUrl = user?.avatar?.trim();
  const showAvatar = Boolean(avatarUrl);

  useEffect(() => {
    let active = true;

    async function load() {
      setLoading(true);
      try {
        const [kbList, spacesStats] = await Promise.all([
          getKnowledgeBases(),
          getSpacesStats().catch(() => null)
        ]);
        if (!active) return;
        setKnowledgeBases(kbList || []);
        setStats(spacesStats);
      } catch {
        if (active) {
          setKnowledgeBases([]);
        }
      } finally {
        if (active) setLoading(false);
      }
    }

    load();
    return () => {
      active = false;
    };
  }, []);

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  const kbCount = stats?.kbCount ?? knowledgeBases.length;
  const totalDocs = stats?.totalDocumentCount ?? knowledgeBases.reduce((sum, kb) => sum + (kb.documentCount ?? 0), 0);

  const statCards = useMemo(
    () => [
      {
        label: "知识库",
        value: kbCount,
        icon: Database,
        color: "text-blue-600",
        bg: "bg-blue-50"
      },
      {
        label: "总文档数",
        value: totalDocs,
        icon: FileText,
        color: "text-emerald-600",
        bg: "bg-emerald-50"
      }
    ],
    [kbCount, totalDocs]
  );

  return (
    <div className="relative flex min-h-screen flex-col overflow-hidden">
      {/* Decorative background layers */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 bg-gradient-to-br from-[#F8FAFC] via-white to-[#EFF6FF]" />
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 bg-grid-pattern opacity-40 [background-size:40px_40px]" />
      <div aria-hidden="true" className="pointer-events-none absolute -top-32 right-[-40px] h-72 w-72 rounded-full bg-gradient-radial from-[#BFDBFE]/60 via-transparent to-transparent blur-3xl animate-float" />
      <div aria-hidden="true" className="pointer-events-none absolute -bottom-36 left-[-80px] h-80 w-80 rounded-full bg-gradient-radial from-[#FDE68A]/40 via-transparent to-transparent blur-3xl animate-float" />

      {/* Top bar */}
      <header className="relative sticky top-0 z-30 border-b border-slate-200/60 bg-white/80 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
          <div className="flex items-center gap-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-indigo-600 text-xs font-bold text-white shadow-sm">
              HT
            </div>
            <span className="text-base font-semibold text-slate-800">HT KnowledgeBase</span>
          </div>

          <div className="flex items-center gap-2">
            {isAdmin ? (
              <Button
                variant="outline"
                size="sm"
                className="hidden items-center gap-1.5 sm:inline-flex"
                onClick={() => navigate("/admin")}
              >
                <Settings className="h-3.5 w-3.5" />
                管理后台
              </Button>
            ) : null}

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-2.5 py-1.5 text-sm text-slate-600 shadow-sm transition-colors hover:bg-slate-50"
                  aria-label="用户菜单"
                >
                  <Avatar
                    name={user?.username || "用户"}
                    src={showAvatar ? avatarUrl : undefined}
                    className="h-7 w-7 border-slate-200 bg-indigo-50 text-xs font-semibold text-indigo-600"
                  />
                  <span className="hidden sm:inline">{user?.username || "用户"}</span>
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" sideOffset={8} className="w-40">
                <div className="px-3 py-2 text-xs text-slate-500">
                  {user?.username || "用户"} · {isAdmin ? "管理员" : "成员"}
                </div>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={handleLogout} className="text-rose-600 focus:text-rose-600">
                  <LogOut className="mr-2 h-4 w-4" />
                  退出登录
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="relative mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6">
        {/* Stats row */}
        <div className="grid grid-cols-2 gap-4 sm:gap-6 animate-fade-up" style={{ animationFillMode: "both" }}>
          {statCards.map((card) => {
            const Icon = card.icon;
            return (
              <div
                key={card.label}
                className="flex items-center gap-4 rounded-2xl border border-white/70 bg-white/70 p-5 shadow-sm backdrop-blur-xl transition-all duration-200 hover:-translate-y-0.5 hover:border-[#BFDBFE] hover:shadow-md"
              >
                <div className={`flex h-12 w-12 items-center justify-center rounded-xl shadow-sm ${card.bg}`}>
                  <Icon className={`h-5 w-5 ${card.color}`} />
                </div>
                <div>
                  <p className="text-2xl font-semibold text-slate-800">
                    {loading ? "-" : card.value.toLocaleString()}
                  </p>
                  <p className="text-sm text-slate-500">{card.label}</p>
                </div>
              </div>
            );
          })}
        </div>

        {/* Section header */}
        <div className="mt-10 mb-5 flex items-center gap-3 animate-fade-up" style={{ animationDelay: "80ms", animationFillMode: "both" }}>
          <h2 className="text-lg font-semibold text-slate-800">我的知识库</h2>
          <span className="inline-flex items-center gap-1.5 rounded-full border border-white/70 bg-white/70 px-2.5 py-0.5 text-xs font-medium text-[#2563EB] shadow-sm">
            <Database className="h-3 w-3" />
            {loading ? "..." : knowledgeBases.length}
          </span>
          <span className="h-px flex-1 bg-[#E5E7EB]" />
        </div>

        {/* Card grid */}
        {loading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {Array.from({ length: 4 }).map((_, i) => (
              <div
                key={i}
                className="h-[140px] animate-pulse rounded-2xl border border-white/70 bg-white/70 backdrop-blur-xl"
              />
            ))}
          </div>
        ) : knowledgeBases.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl border border-white/70 bg-white/70 shadow-sm backdrop-blur-xl">
              <Database className="h-8 w-8 text-slate-400" />
            </div>
            <p className="mt-4 text-base font-medium text-slate-600">暂无可访问的知识库</p>
            <p className="mt-1 text-sm text-slate-400">
              请联系管理员为你分配知识库访问权限
            </p>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 animate-fade-up" style={{ animationDelay: "160ms", animationFillMode: "both" }}>
            {knowledgeBases.map((kb) => {
              const color = nameToColor(kb.name);
              const initial = kb.name ? kb.name.charAt(0).toUpperCase() : "?";
              return (
                <button
                  key={kb.id}
                  type="button"
                  onClick={() => navigate(`/chat?kbId=${kb.id}`)}
                  className="group flex flex-col rounded-2xl border border-white/70 bg-white/70 p-5 text-left shadow-sm backdrop-blur-xl transition-all duration-200 hover:-translate-y-0.5 hover:border-[#BFDBFE] hover:shadow-md"
                >
                  <div className="flex items-start gap-3">
                    <div
                      className={`flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl text-base font-bold shadow-sm ${color.bg} ${color.text}`}
                    >
                      {initial}
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-semibold text-slate-800 group-hover:text-blue-700">
                        {kb.name}
                      </p>
                      <p className="mt-1 text-xs text-slate-400">
                        {kb.documentCount ?? 0} 篇文档
                      </p>
                    </div>
                  </div>
                  <div className="mt-4 flex items-center justify-between border-t border-slate-100/60 pt-3 text-xs text-slate-400">
                    <span>创建于 {formatDate(kb.createTime)}</span>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
}
