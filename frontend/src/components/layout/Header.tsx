import * as React from "react";
import { ArrowLeft, Menu } from "lucide-react";
import { useNavigate } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { useChatStore } from "@/stores/chatStore";

interface HeaderProps {
  onToggleSidebar: () => void;
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const navigate = useNavigate();
  const { currentSessionId, sessions, activeKbName } = useChatStore();
  const currentSession = React.useMemo(
    () => sessions.find((session) => session.id === currentSessionId),
    [sessions, currentSessionId]
  );

  return (
    <header className="sticky top-0 z-20 bg-white">
      <div className="flex h-16 items-center justify-between px-6">
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            onClick={onToggleSidebar}
            aria-label="切换侧边栏"
            className="text-gray-500 hover:bg-gray-100 lg:hidden"
          >
            <Menu className="h-5 w-5" />
          </Button>
          <button
            type="button"
            onClick={() => navigate("/spaces")}
            className="hidden items-center gap-1 rounded-md px-2 py-1 text-sm text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700 lg:inline-flex"
            aria-label="返回空间列表"
          >
            <ArrowLeft className="h-4 w-4" />
            空间
          </button>
          {activeKbName ? (
            <>
              <span className="hidden text-gray-300 lg:inline">/</span>
              <span className="hidden text-sm font-medium text-[#2563EB] lg:inline">
                {activeKbName}
              </span>
            </>
          ) : null}
          <span className="text-gray-300">&middot;</span>
          <p className="text-base font-medium text-gray-900">
            {currentSession?.title || "新对话"}
          </p>
        </div>
      </div>
    </header>
  );
}
