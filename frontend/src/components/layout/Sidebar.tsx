import * as React from "react";
import { differenceInCalendarDays, isValid } from "date-fns";
import {
  ArrowLeft,
  BookOpen,
  LogOut,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  Settings,
  Trash2
} from "lucide-react";
import { useNavigate } from "react-router-dom";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { useTranslation } from "react-i18next";

import { LanguageSwitcher } from "@/components/common/LanguageSwitcher";
import { Loading } from "@/components/common/Loading";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { usePermissions } from "@/utils/permissions";
import { useChatStore } from "@/stores/chatStore";

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ isOpen, onClose }: SidebarProps) {
  const { t } = useTranslation(["chat", "common"]);
  const {
    sessions,
    currentSessionId,
    isLoading,
    sessionsLoaded,
    activeKbId,
    activeKbName,
    createSession,
    deleteSession,
    renameSession,
    selectSession,
    fetchSessions
  } = useChatStore();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const permissions = usePermissions();
  const [query, setQuery] = React.useState("");
  const [renamingId, setRenamingId] = React.useState<string | null>(null);
  const [renameValue, setRenameValue] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<{
    id: string;
    title: string;
  } | null>(null);
  const [avatarFailed, setAvatarFailed] = React.useState(false);
  const renameInputRef = React.useRef<HTMLInputElement | null>(null);

  const kbId = activeKbId;

  React.useEffect(() => {
    if (sessions.length === 0 && kbId) {
      fetchSessions(kbId).catch(() => null);
    }
  }, [fetchSessions, sessions.length, kbId]);

  const filteredSessions = React.useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return sessions;
    return sessions.filter((session) => {
      const title = (session.title || "新对话").toLowerCase();
      return title.includes(keyword) || session.id.toLowerCase().includes(keyword);
    });
  }, [query, sessions]);

  const groupedSessions = React.useMemo(() => {
    const now = new Date();
    const groups = new Map<string, typeof filteredSessions>();
    const order: string[] = [];

    const resolveLabel = (value?: string) => {
      const parsed = value ? new Date(value) : now;
      const date = isValid(parsed) ? parsed : now;
      const diff = Math.max(0, differenceInCalendarDays(now, date));
      if (diff === 0) return "今天";
      if (diff <= 7) return "7天内";
      if (diff <= 30) return "30天内";
      return "更早";
    };

    filteredSessions.forEach((session) => {
      const label = resolveLabel(session.lastTime);
      if (!groups.has(label)) {
        groups.set(label, []);
        order.push(label);
      }
      groups.get(label)?.push(session);
    });

    return order.map((label) => ({
      label,
      items: groups.get(label) || []
    }));
  }, [filteredSessions]);

  React.useEffect(() => {
    if (renamingId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [renamingId]);

  React.useEffect(() => {
    setAvatarFailed(false);
  }, [user?.avatar, user?.userId]);

  const avatarUrl = user?.avatar?.trim();
  const showAvatar = Boolean(avatarUrl) && !avatarFailed;
  const avatarFallback = (user?.username || user?.userId || "用户").slice(0, 1).toUpperCase();

  const chatPath = kbId ? `/chat?kbId=${kbId}` : "/chat";
  const sessionPath = (sid: string) => kbId ? `/chat/${sid}?kbId=${kbId}` : `/chat/${sid}`;

  const startRename = (id: string, title: string) => {
    setRenamingId(id);
    setRenameValue(title || "新对话");
  };

  const cancelRename = () => {
    setRenamingId(null);
    setRenameValue("");
  };

  const commitRename = async () => {
    if (!renamingId) return;
    const nextTitle = renameValue.trim();
    if (!nextTitle) {
      cancelRename();
      return;
    }
    const currentTitle = sessions.find((session) => session.id === renamingId)?.title || "新对话";
    if (nextTitle === currentTitle) {
      cancelRename();
      return;
    }
    await renameSession(renamingId, nextTitle);
    cancelRename();
  };

  return (
    <>
      <div
        className={cn(
          "fixed inset-0 z-30 bg-vio-ink/20 backdrop-blur-sm transition-opacity lg:hidden",
          isOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
        onClick={onClose}
      />
      <aside
        className={cn(
          "fixed left-0 top-0 z-40 flex h-screen w-[280px] flex-shrink-0 flex-col bg-[var(--vio-surface-2)] p-3 transition-transform lg:static lg:h-screen lg:translate-x-0",
          isOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        <div className="border-b border-vio-line pb-3">
          <div className="flex items-center gap-3">
            {/* Signature moment #5: HT monogram gradient fill */}
            <div className="vio-aurora-fill flex h-10 w-10 items-center justify-center rounded-xl text-white font-display text-lg font-medium italic leading-none select-none">
              HT
            </div>
            <div>
              <p className="font-display text-base font-medium text-vio-ink">HT KnowledgeBase</p>
              <p className="font-mono text-[10px] uppercase tracking-wider text-vio-ink/40">知识坊</p>
            </div>
          </div>
          <button
            type="button"
            className="mt-2 flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-xs font-medium text-vio-ink/50 transition-colors hover:bg-[var(--vio-accent-mist)] hover:text-vio-accent"
            onClick={() => {
              navigate("/spaces");
              onClose();
            }}
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            返回空间列表
          </button>
        </div>
        <div className="py-3 space-y-4">
          {/* Signature moment #6: new conversation card with aurora halo */}
          <div className="relative overflow-hidden rounded-2xl border border-vio-line bg-white p-3 shadow-paper">
            {/* corner halo — only appears on hover to avoid overdoing gradient */}
            <div
              aria-hidden="true"
              className="pointer-events-none absolute -right-8 -top-8 h-24 w-24 opacity-0 transition-opacity duration-300 group-hover:opacity-100"
              style={{
                background: "radial-gradient(circle at 70% 30%, rgba(179,155,245,0.4) 0%, transparent 65%)",
                filter: "blur(20px)",
              }}
            />
            <div className="relative">
              <div className="flex items-center justify-between px-1">
                <span className="font-mono text-[10px] uppercase tracking-wider text-vio-ink/40">快速开始</span>
                {activeKbName ? (
                  <span className="inline-flex items-center gap-1 rounded-full bg-[var(--vio-accent-mist)] px-2 py-0.5 text-[10px] font-medium text-vio-accent">
                    <BookOpen className="h-2.5 w-2.5" />
                    {activeKbName}
                  </span>
                ) : null}
              </div>
              <button
                type="button"
                className="mt-2 group flex w-full items-center gap-3 rounded-xl border border-vio-line bg-white px-4 py-2.5 text-left transition-all hover:border-vio-accent-subtle hover:shadow-halo"
                onClick={() => {
                  createSession().catch(() => null);
                  navigate(chatPath);
                  onClose();
                }}
              >
                <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-[var(--vio-accent)] to-[var(--vio-accent-2)] text-white shadow-sm">
                  <Plus className="h-4 w-4" />
                </span>
                <span className="flex-1">
                  <span className="block text-sm font-medium text-vio-ink">{t("chat:session.new_chat")}</span>
                  <span className="block font-mono text-[10px] text-vio-ink/40">{t("chat:session.new_chat_hint")}</span>
                </span>
              </button>
            </div>
          </div>
          <div className="rounded-xl border border-vio-line bg-white p-2.5">
            <div className="flex items-center justify-between px-1">
              <span className="text-[11px] font-semibold text-vio-ink/50">搜索对话</span>
              <span className="text-[10px] text-vio-ink/30">Ctrl / Cmd + K</span>
            </div>
            <div className="mt-2">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-vio-ink/40" />
                <input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder={t("chat:session.search_placeholder")}
                  className="h-9 w-full rounded-lg border border-vio-line bg-[var(--vio-surface-2)] pl-9 pr-3 text-sm text-vio-ink placeholder:text-vio-ink/40 focus:border-vio-accent focus:outline-none transition-colors"
                />
              </div>
            </div>
          </div>
        </div>
        <div className="relative flex-1 min-h-0">
          <div className="h-full overflow-y-auto sidebar-scroll">
            {sessions.length === 0 && (!sessionsLoaded || isLoading) ? (
              <div className="flex h-full items-center justify-center text-ink-4">
                <Loading label="加载会话中" />
              </div>
            ) : filteredSessions.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center text-ink-4">
                <MessageSquare className="h-8 w-8 opacity-40" />
                <p className="mt-2 text-[13px]">
                  {query.trim() ? "没有匹配的对话" : "暂无对话记录"}
                </p>
              </div>
            ) : (
              <div>
                {groupedSessions.map((group, index) => (
                  <div key={group.label} className={cn("flex flex-col", index === 0 ? "mt-0" : "mt-4")}>
                    <p className="mb-1.5 pl-3 font-mono text-[10px] uppercase tracking-wider text-vio-ink/40">
                      {group.label}
                    </p>
                    {group.items.map((session) => (
                      <div
                        key={session.id}
                        className={cn(
                          "group my-[1px] flex min-h-[38px] cursor-pointer select-none items-center justify-between gap-2 rounded-lg px-3 py-2 text-[14px] leading-[22px] transition-colors duration-200",
                          currentSessionId === session.id
                            ? "border-l-2 border-vio-accent bg-[var(--vio-accent-mist)] text-vio-accent"
                            : "text-vio-ink/80 hover:bg-[var(--vio-accent-mist)]/50 hover:text-vio-ink"
                        )}
                        role="button"
                        tabIndex={0}
                        onClick={() => {
                          if (renamingId === session.id) return;
                          if (renamingId) {
                            cancelRename();
                          }
                          selectSession(session.id).catch(() => null);
                          navigate(sessionPath(session.id));
                          onClose();
                        }}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            selectSession(session.id).catch(() => null);
                            navigate(sessionPath(session.id));
                            onClose();
                          }
                        }}
                      >
                        {renamingId === session.id ? (
                          <input
                            ref={renameInputRef}
                            value={renameValue}
                            onChange={(event) => setRenameValue(event.target.value)}
                            onClick={(event) => event.stopPropagation()}
                            onKeyDown={(event) => {
                              if (event.key === "Enter") {
                                event.preventDefault();
                                commitRename().catch(() => null);
                              }
                              if (event.key === "Escape") {
                                event.preventDefault();
                                cancelRename();
                              }
                            }}
                            onBlur={() => {
                              commitRename().catch(() => null);
                            }}
                            className="h-6 flex-1 rounded-md border border-vio-line bg-white px-2 text-[14px] leading-[22px] text-vio-ink focus:border-vio-accent focus:outline-none"
                          />
                        ) : (
                          <span className="min-w-0 flex-1 truncate font-normal">
                            {session.title || "新对话"}
                          </span>
                        )}
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <button
                              type="button"
                              className={cn(
                                "flex h-6 w-6 items-center justify-center rounded text-ink-3 transition-opacity duration-150 hover:bg-black/[0.06]",
                                currentSessionId === session.id
                                  ? "pointer-events-auto opacity-100 text-brand"
                                  : "pointer-events-none opacity-0 group-hover:pointer-events-auto group-hover:opacity-100"
                              )}
                              onClick={(event) => event.stopPropagation()}
                              aria-label="会话操作"
                            >
                              <MoreHorizontal className="h-4 w-4" />
                            </button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent
                            align="start"
                            className="min-w-[120px] rounded-lg border-0 bg-surface p-0 py-1 shadow-[0_4px_16px_rgba(0,0,0,0.12)]"
                          >
                            <DropdownMenuItem
                              onClick={(event) => {
                                event.stopPropagation();
                                startRename(session.id, session.title || "新对话");
                              }}
                              className="px-4 py-2 text-[14px] text-ink-2 focus:bg-surface-3 focus:text-ink-2 data-[highlighted]:bg-surface-3 data-[highlighted]:text-ink-2"
                            >
                              <Pencil className="mr-2 h-4 w-4" />
                              重命名
                            </DropdownMenuItem>
                            <DropdownMenuItem
                              onClick={(event) => {
                                event.stopPropagation();
                                setDeleteTarget({
                                  id: session.id,
                                  title: session.title || "新对话"
                                });
                              }}
                              className="px-4 py-2 text-[14px] text-danger focus:bg-surface-3 focus:text-danger data-[highlighted]:bg-surface-3 data-[highlighted]:text-danger"
                            >
                              <Trash2 className="mr-2 h-4 w-4" />
                              删除
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            )}
          </div>
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 bottom-0 z-10 h-5 bg-gradient-to-b from-transparent to-[var(--vio-surface-2)]"
          />
        </div>
        <div className="mt-auto pt-3">
          <div className="px-2 pb-2">
            <LanguageSwitcher />
          </div>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                className="flex w-full items-center gap-2 rounded-lg p-2 text-left transition-colors hover:bg-[var(--vio-accent-mist)] data-[state=open]:bg-[var(--vio-accent-mist)]"
                aria-label="用户菜单"
              >
                <div className="flex h-8 w-8 items-center justify-center overflow-hidden rounded-full bg-[var(--vio-accent-subtle)] text-vio-accent">
                  {showAvatar ? (
                    <img
                      src={avatarUrl}
                      alt={user?.username || user?.userId || "用户"}
                      className="h-full w-full object-cover"
                      onError={() => setAvatarFailed(true)}
                    />
                  ) : (
                    <span className="text-sm font-medium">{avatarFallback}</span>
                  )}
                </div>
                <span className="flex-1 truncate text-sm font-medium text-ink">
                  {(() => {
                    const fallback = user?.username || user?.userId || "用户";
                    return /^\d+$/.test(fallback) ? "用户" : fallback;
                  })()}
                </span>
                <MoreHorizontal className="h-4 w-4 text-ink-4" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="top" sideOffset={8} className="w-48">
              {permissions.canSeeAdminMenu ? (
                <DropdownMenuItem
                  onClick={() => {
                    window.open("/admin", "_blank");
                    onClose();
                  }}
                >
                  <Settings className="mr-2 h-4 w-4" />
                  管理后台
                </DropdownMenuItem>
              ) : null}
              <DropdownMenuItem asChild>
                <a
                  href="#"
                  target="_blank"
                  rel="noreferrer"
                  className="flex items-center"
                >
                  <BookOpen className="mr-2 h-4 w-4" />
                  用户手册
                </a>
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => logout()} className="text-rose-600 focus:text-rose-600">
                <LogOut className="mr-2 h-4 w-4" />
                {t("common:actions.logout")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </aside>
      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => {
        if (!open) {
          setDeleteTarget(null);
        }
      }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除该会话？</AlertDialogTitle>
            <AlertDialogDescription>
              [{deleteTarget?.title || "该会话"}] 将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                const isCurrent = currentSessionId === target.id;
                setDeleteTarget(null);
                deleteSession(target.id)
                  .then(() => {
                    if (isCurrent) {
                      navigate(chatPath);
                    }
                  })
                  .catch(() => null);
              }}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
