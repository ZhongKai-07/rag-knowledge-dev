import * as React from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";

import { ChatInput } from "@/components/chat/ChatInput";
import { MessageList } from "@/components/chat/MessageList";
import { MainLayout } from "@/components/layout/MainLayout";
import { useChatStore } from "@/stores/chatStore";
import { getKnowledgeBase } from "@/services/knowledgeService";

export function ChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const [searchParams] = useSearchParams();
  const kbId = searchParams.get("kbId");
  const {
    messages,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    isCreatingNew,
    activeKbId,
    fetchSessions,
    selectSession,
    createSession,
    setActiveKb,
    resetForNewSpace
  } = useChatStore();
  const showWelcome = messages.length === 0 && !isLoading;
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return sessions.some((session) => session.id === sessionId);
  }, [sessionId, sessions]);

  // Redirect to /spaces if no kbId in URL
  React.useEffect(() => {
    if (!kbId) {
      navigate("/spaces", { replace: true });
    }
  }, [kbId, navigate]);

  // On mount / kbId change: reset state and sync activeKb
  React.useEffect(() => {
    if (!kbId) return;
    let active = true;

    if (kbId !== activeKbId) {
      resetForNewSpace();
      setActiveKb(kbId, null);
      setSessionsReady(false);
    }

    // Fetch KB name
    getKnowledgeBase(kbId)
      .then((kb) => {
        if (active) {
          setActiveKb(kbId, kb.name);
        }
      })
      .catch(() => null);

    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kbId]);

  // Fetch sessions filtered by kbId
  React.useEffect(() => {
    if (!kbId) return;
    let active = true;
    fetchSessions(kbId)
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [kbId, fetchSessions]);

  React.useEffect(() => {
    if (!kbId) return;
    if (sessionId) {
      if (sessionsReady && !sessionExists) {
        createSession().catch(() => null);
        navigate(`/chat?kbId=${kbId}`, { replace: true });
        return;
      }
      selectSession(sessionId).catch(() => null);
      return;
    }
    if (!sessionsReady) {
      return;
    }
    if (isCreatingNew) {
      return;
    }
    if (currentSessionId) {
      return;
    }
    createSession().catch(() => null);
  }, [
    kbId,
    sessionId,
    sessionsReady,
    sessionExists,
    isCreatingNew,
    currentSessionId,
    selectSession,
    createSession,
    navigate
  ]);

  React.useEffect(() => {
    if (!kbId) return;
    if (currentSessionId && currentSessionId !== sessionId) {
      navigate(`/chat/${currentSessionId}?kbId=${kbId}`, { replace: true });
    }
  }, [currentSessionId, sessionId, kbId, navigate]);

  return (
    <MainLayout>
      <div className="flex h-full flex-col bg-white">
        <div className="flex-1 min-h-0">
          <MessageList
            messages={messages}
            isLoading={isLoading}
            isStreaming={isStreaming}
            sessionKey={currentSessionId}
          />
        </div>
        {showWelcome ? null : (
          <div className="relative z-20 border-t border-line/50 bg-surface shadow-[0_-1px_8px_rgba(0,0,0,0.04)]">
            <div className="mx-auto max-w-[800px] px-6 pt-3 pb-4">
              <ChatInput />
            </div>
          </div>
        )}
      </div>
    </MainLayout>
  );
}
