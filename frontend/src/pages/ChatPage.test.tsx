import { render, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ChatPage } from "@/pages/ChatPage";
import { getKnowledgeBase } from "@/services/knowledgeService";
import { listMessages, listSessions } from "@/services/sessionService";
import { useChatStore } from "@/stores/chatStore";
import type { Message, Session } from "@/types";

const routerHookMocks = vi.hoisted(() => ({
  navigate: undefined as ReturnType<typeof vi.fn> | undefined,
  params: undefined as { sessionId?: string } | undefined,
  searchParams: undefined as URLSearchParams | undefined,
  setSearchParams: vi.fn()
}));

vi.mock("react-router-dom", async (importActual) => {
  const actual = await importActual<typeof import("react-router-dom")>();
  return {
    ...actual,
    useNavigate: () => routerHookMocks.navigate ?? actual.useNavigate(),
    useParams: () => (routerHookMocks.params ?? actual.useParams()) as never,
    useSearchParams: () =>
      routerHookMocks.searchParams
        ? ([routerHookMocks.searchParams, routerHookMocks.setSearchParams] as never)
        : actual.useSearchParams()
  };
});

vi.mock("@/components/layout/MainLayout", async () => {
  const React = await import("react");
  return {
    MainLayout: ({ children }: { children: unknown }) =>
      React.createElement("div", { "data-testid": "main-layout" }, children)
  };
});

vi.mock("@/components/chat/MessageList", async () => {
  const React = await import("react");
  return {
    MessageList: () => React.createElement("div", { "data-testid": "message-list" })
  };
});

vi.mock("@/components/chat/ChatInput", async () => {
  const React = await import("react");
  return {
    ChatInput: () => React.createElement("div", { "data-testid": "chat-input" })
  };
});

vi.mock("@/services/knowledgeService", async (importActual) => {
  const actual = await importActual<typeof import("@/services/knowledgeService")>();
  return {
    ...actual,
    getKnowledgeBase: vi.fn()
  };
});

vi.mock("@/services/sessionService", async (importActual) => {
  const actual = await importActual<typeof import("@/services/sessionService")>();
  return {
    ...actual,
    listMessages: vi.fn(),
    listSessions: vi.fn()
  };
});

function resetStore() {
  useChatStore.setState((state) => ({
    ...state,
    sessions: [],
    currentSessionId: null,
    messages: [],
    isLoading: false,
    sessionsLoaded: false,
    isStreaming: false,
    isCreatingNew: false,
    activeKbId: null,
    activeKbName: null,
    streamTaskId: null,
    streamAbort: null,
    streamingMessageId: null,
    cancelRequested: false
  }));
}

function renderChat(initialEntry: string) {
  const router = createMemoryRouter(
    [
      { path: "/chat", element: <ChatPage /> },
      { path: "/chat/:sessionId", element: <ChatPage /> },
      { path: "/spaces", element: <div data-testid="spaces" /> }
    ],
    {
      initialEntries: [initialEntry],
      future: { v7_startTransition: true }
    }
  );

  render(<RouterProvider router={router} future={{ v7_startTransition: true }} />);
  return router;
}

describe("ChatPage kb/session routing", () => {
  beforeEach(() => {
    vi.mocked(getKnowledgeBase).mockReset();
    vi.mocked(listMessages).mockReset();
    vi.mocked(listSessions).mockReset();
    routerHookMocks.navigate = undefined;
    routerHookMocks.params = undefined;
    routerHookMocks.searchParams = undefined;
    routerHookMocks.setSearchParams.mockReset();
    vi.mocked(getKnowledgeBase).mockResolvedValue({ id: "kbB", name: "KB B" } as never);
    vi.mocked(listMessages).mockResolvedValue([]);
    vi.mocked(listSessions).mockResolvedValue([]);
    resetStore();
  });

  it("does not navigate an old currentSessionId into a new kb url", async () => {
    const oldMessage: Message = {
      id: "m1",
      role: "assistant",
      content: "old answer",
      status: "done"
    };
    const oldSession: Session = {
      id: "sessionA",
      title: "KB A session",
      kbId: "kbA"
    };
    useChatStore.setState((state) => ({
      ...state,
      activeKbId: "kbA",
      currentSessionId: "sessionA",
      sessions: [oldSession],
      messages: [oldMessage],
      sessionsLoaded: true
    }));

    const router = renderChat("/chat?kbId=kbB");

    await waitFor(() => expect(listSessions).toHaveBeenCalledWith("kbB"));

    expect(router.state.location.pathname).toBe("/chat");
    expect(router.state.location.search).toBe("?kbId=kbB");
  });

  it("redirects a session that is not in the current kb before loading messages", async () => {
    vi.mocked(listSessions).mockResolvedValue([
      {
        conversationId: "sessionB",
        title: "KB B session",
        kbId: "kbB"
      }
    ]);

    const router = renderChat("/chat/sessionA?kbId=kbB");

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/chat");
      expect(router.state.location.search).toBe("?kbId=kbB");
    });
    expect(listMessages).not.toHaveBeenCalled();
  });

  it("does not navigate back to the previous session during same-kb selection", async () => {
    vi.mocked(getKnowledgeBase).mockResolvedValue({ id: "kbA", name: "KB A" } as never);
    vi.mocked(listSessions).mockResolvedValue([
      {
        conversationId: "sessionA",
        title: "Session A",
        kbId: "kbA"
      },
      {
        conversationId: "sessionB",
        title: "Session B",
        kbId: "kbA"
      }
    ]);
    vi.mocked(listMessages).mockResolvedValue([]);

    useChatStore.setState((state) => ({
      ...state,
      activeKbId: "kbA",
      currentSessionId: "sessionA",
      sessions: [
        { id: "sessionA", title: "Session A", kbId: "kbA" },
        { id: "sessionB", title: "Session B", kbId: "kbA" }
      ],
      sessionsLoaded: true
    }));

    const navigate = vi.fn();
    routerHookMocks.navigate = navigate;
    routerHookMocks.params = { sessionId: "sessionB" };
    routerHookMocks.searchParams = new URLSearchParams("kbId=kbA");

    render(<ChatPage />);

    await waitFor(
      () => {
        expect(listMessages).toHaveBeenCalledWith("sessionB", "kbA");
      },
      { timeout: 200 }
    );
    expect(navigate).not.toHaveBeenCalledWith("/chat/sessionA?kbId=kbA", { replace: true });
  });
});
