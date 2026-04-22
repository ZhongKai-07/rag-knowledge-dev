import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Database, FileText, LogOut, Settings } from "lucide-react";
import { toast } from "sonner";

import { useAuthStore } from "@/stores/authStore";
import { usePermissions } from "@/utils/permissions";
import { getErrorMessage, isRbacRejection } from "@/utils/error";
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

  const permissions = usePermissions();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [stats, setStats] = useState<SpacesStats | null>(null);
  const [loading, setLoading] = useState(true);

  const isAdmin = permissions.canSeeAdminMenu;
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
      } catch (err) {
        if (active) {
          setKnowledgeBases([]);
          if (!isRbacRejection(err)) {
            toast.error(getErrorMessage(err, "加载知识库列表失败"));
            console.error(err);
          }
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
      { label: "知识库",   value: kbCount,  icon: Database, color: "text-vio-accent",   bg: "bg-[var(--vio-accent-mist)]" },
      { label: "总文档数", value: totalDocs, icon: FileText,  color: "text-vio-accent-2", bg: "bg-[var(--vio-accent-mist)]" }
    ],
    [kbCount, totalDocs]
  );

  return (
    <div className="relative flex min-h-screen flex-col overflow-hidden">
      {/* Violet Aurora background */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0" style={{ backgroundColor: "var(--vio-surface)" }} />
      <div className="vio-aurora-halo absolute -top-20 right-0 h-80 w-80" aria-hidden="true" />
      <div className="vio-aurora-halo-2 absolute -bottom-20 left-0 h-80 w-80" aria-hidden="true" />

      {/* Top bar */}
      <header className="relative sticky top-0 z-30 border-b border-vio-line bg-white/90 backdrop-blur-sm">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
          <div className="flex items-center gap-3">
            <div className="vio-aurora-fill flex h-8 w-8 items-center justify-center rounded-lg text-xs font-bold text-white shadow-sm">
              HT
            </div>
            <span className="font-display text-base font-semibold text-vio-ink">HT KnowledgeBase</span>
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
                  className="flex items-center gap-2 rounded-full border border-vio-line bg-white px-2.5 py-1.5 text-sm text-vio-ink shadow-sm transition-colors hover:bg-[var(--vio-accent-mist)]"
                  aria-label="用户菜单"
                >
                  <Avatar
                    name={user?.username || "用户"}
                    src={showAvatar ? avatarUrl : undefined}
                    className="h-7 w-7 border-vio-line bg-[var(--vio-accent-subtle)] text-xs font-semibold text-vio-accent"
                  />
                  <span className="hidden sm:inline">{user?.username || "用户"}</span>
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" sideOffset={8} className="w-40">
                <div className="px-3 py-2 text-xs text-ink-3">
                  {user?.username || "用户"} · {permissions.isSuperAdmin ? "超级管理员" : permissions.isDeptAdmin ? "部门管理员" : "成员"}
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
        {/* Stats row — 限制最大宽度避免宽屏过度拉伸 */}
        <div className="grid max-w-lg grid-cols-2 gap-4 animate-fade-up" style={{ animationFillMode: "both" }}>
          {statCards.map((card) => {
            const Icon = card.icon;
            return (
              <div
                key={card.label}
                className="flex items-center gap-4 rounded-[14px] border border-vio-line bg-white p-5 shadow-paper transition-all hover:-translate-y-0.5 hover:border-vio-accent-subtle hover:shadow-halo"
              >
                <div className={`flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl shadow-sm ${card.bg}`}>
                  <Icon className={`h-5 w-5 ${card.color}`} />
                </div>
                <div>
                  <p className="text-2xl font-semibold tabular-nums text-ink">
                    {loading ? "-" : card.value.toLocaleString()}
                  </p>
                  <p className="text-xs text-ink-3">{card.label}</p>
                </div>
              </div>
            );
          })}
        </div>

        {/* Section header */}
        <div className="mt-10 mb-5 flex items-center gap-3 animate-fade-up" style={{ animationDelay: "80ms", animationFillMode: "both" }}>
          <h2 className="text-lg font-display font-medium text-vio-ink">我的知识库</h2>
          <span className="inline-flex items-center gap-1.5 rounded-full border border-vio-line bg-[var(--vio-accent-mist)] px-2.5 py-0.5 text-xs font-medium text-vio-accent shadow-sm">
            <Database className="h-3 w-3" />
            {loading ? "..." : knowledgeBases.length}
          </span>
          <span className="h-px flex-1 bg-line" />
        </div>

        {/* Card grid */}
        {loading ? (
          <div
            className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            aria-busy="true"
            aria-label="加载知识库中"
          >
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
              <Database className="h-8 w-8 text-ink-4" />
            </div>
            <p className="mt-4 text-base font-medium text-ink-3">暂无可访问的知识库</p>
            <p className="mt-1 text-sm text-ink-4">
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
                  className="group flex cursor-pointer flex-col rounded-[16px] border border-vio-line bg-white p-5 text-left shadow-paper transition-all duration-200 hover:-translate-y-0.5 hover:border-vio-accent-subtle hover:shadow-halo focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-vio-accent-subtle"
                >
                  <div className="flex items-start gap-3">
                    <div
                      className={`flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl text-base font-bold shadow-sm ${color.bg} ${color.text}`}
                    >
                      {initial}
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-semibold text-vio-ink group-hover:text-vio-accent">
                        {kb.name}
                      </p>
                      <p className="mt-1 text-xs text-ink-4">
                        {kb.documentCount ?? 0} 篇文档
                      </p>
                    </div>
                  </div>
                  <div className="mt-4 flex items-center justify-between border-t border-vio-line pt-3 text-xs text-vio-ink/50">
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
