import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Message, SourceCard, SourcesPayload } from "@/types";
import { createStreamHandlers, useChatStore } from "./chatStore";
import { listMessages } from "@/services/sessionService";

vi.mock("@/services/sessionService", async (importActual) => {
  const actual = await importActual<typeof import("@/services/sessionService")>();
  return {
    ...actual,
    listMessages: vi.fn()
  };
});

/**
 * 构造一个带占位 assistant message 的初始 store 状态。
 * assistantId 既是占位消息的 id，也是 streamingMessageId。
 */
function seedStreamingState(assistantId: string) {
  const placeholder: Message = {
    id: assistantId,
    role: "assistant",
    content: "",
    status: "streaming",
    feedback: null,
    createdAt: new Date().toISOString()
  };
  useChatStore.setState((state) => ({
    ...state,
    messages: [placeholder],
    streamingMessageId: assistantId,
    isStreaming: true,
    currentSessionId: "c_test",
    sessions: [{ id: "c_test", title: "t", lastTime: new Date().toISOString() }],
    thinkingStartAt: null
  }));
}

function buildHandlers(assistantId: string) {
  const stopTask = vi.fn().mockResolvedValue(undefined);
  return createStreamHandlers(
    useChatStore.getState,
    useChatStore.setState,
    assistantId,
    stopTask
  );
}

function buildSourcesPayload(): SourcesPayload {
  const card: SourceCard = {
    index: 1,
    docId: "d1",
    docName: "doc.pdf",
    kbId: "kb1",
    topScore: 0.9,
    chunks: [{ chunkId: "c1", chunkIndex: 0, preview: "hi", score: 0.9 }]
  };
  return { conversationId: "c_test", messageId: null, cards: [card] };
}

function resetStore() {
  useChatStore.setState((state) => ({
    ...state,
    messages: [],
    streamingMessageId: null,
    isStreaming: false,
    sessions: [],
    currentSessionId: null,
    thinkingStartAt: null
  }));
}

describe("chatStore.onSources", () => {
  beforeEach(() => {
    resetStore();
  });

  it("ignores payload when streamingMessageId does not match assistantId (stale stream)", () => {
    seedStreamingState("msgA");
    // staleHandlers is bound to "msgB", but store has "msgA" as streamingMessageId
    const staleHandlers = buildHandlers("msgB");

    staleHandlers.onSources?.(buildSourcesPayload());

    const msg = useChatStore.getState().messages[0];
    expect(msg.id).toBe("msgA");
    expect(msg.sources).toBeUndefined();
  });

  it("writes sources to the streaming message when ids match", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    const payload = buildSourcesPayload();

    handlers.onSources?.(payload);

    const msg = useChatStore.getState().messages[0];
    expect(msg.sources).toEqual(payload.cards);
  });

  it("drops payload when cards is not an array", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");

    handlers.onSources?.({
      conversationId: "c_test",
      messageId: null,
      cards: null as unknown as SourceCard[]
    });

    const msg = useChatStore.getState().messages[0];
    expect(msg.sources).toBeUndefined();
  });
});

describe("chatStore.onFinish sources preservation", () => {
  beforeEach(() => {
    resetStore();
  });

  it("preserves sources after id is replaced by db id on finish", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");

    // 先塞 sources
    handlers.onSources?.(buildSourcesPayload());
    expect(useChatStore.getState().messages[0].sources).toBeDefined();

    // onFinish 触发 id 替换（uses spread so sources must be preserved）
    handlers.onFinish?.({ messageId: "db_1", title: "新标题" });

    const finalMsg = useChatStore.getState().messages[0];
    expect(finalMsg.id).toBe("db_1");
    expect(finalMsg.sources).toBeDefined();
    expect(finalMsg.sources?.[0].docId).toBe("d1");
  });
});

describe("selectSession mapping", () => {
  beforeEach(() => {
    vi.mocked(listMessages).mockReset();
    resetStore();
  });

  it("maps thinkingContent / thinkingDuration / sources from VO to Message", async () => {
    const card: SourceCard = {
      index: 1,
      docId: "d1",
      docName: "doc.pdf",
      kbId: "kb1",
      topScore: 0.9,
      chunks: [{ chunkId: "c1", chunkIndex: 0, preview: "hi", score: 0.9 }]
    };
    vi.mocked(listMessages).mockResolvedValue([
      {
        id: "m1",
        conversationId: "c_test",
        role: "assistant",
        content: "answer",
        vote: null,
        thinkingContent: "thinking...",
        thinkingDuration: 3,
        sources: [card]
      }
    ]);

    useChatStore.setState((s) => ({ ...s, activeKbId: null }));
    await useChatStore.getState().selectSession("c_test");

    const messages = useChatStore.getState().messages;
    expect(messages).toHaveLength(1);
    expect(messages[0].thinking).toBe("thinking...");
    expect(messages[0].thinkingDuration).toBe(3);
    expect(messages[0].sources).toEqual([card]);
  });

  it("tolerates missing thinking/sources fields (undefined)", async () => {
    vi.mocked(listMessages).mockResolvedValue([
      {
        id: "m1",
        conversationId: "c_test",
        role: "assistant",
        content: "answer",
        vote: null
      }
    ]);

    useChatStore.setState((s) => ({ ...s, activeKbId: null }));
    await useChatStore.getState().selectSession("c_test");

    const messages = useChatStore.getState().messages;
    expect(messages).toHaveLength(1);
    expect(messages[0].thinking).toBeUndefined();
    expect(messages[0].thinkingDuration).toBeUndefined();
    expect(messages[0].sources).toBeUndefined();
  });
});
