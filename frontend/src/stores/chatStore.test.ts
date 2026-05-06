import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Message, SourceCard, SourcesPayload } from "@/types";
import { createStreamHandlers, useChatStore } from "./chatStore";
import { listMessages, listSessions } from "@/services/sessionService";

vi.mock("@/services/sessionService", async (importActual) => {
  const actual = await importActual<typeof import("@/services/sessionService")>();
  return {
    ...actual,
    listMessages: vi.fn(),
    listSessions: vi.fn()
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
    thinkingStartAt: null,
    activeKbId: null,
    activeKbName: null,
    sessionsLoaded: false,
    streamTaskId: null,
    streamAbort: null,
    cancelRequested: false
  }));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
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
    vi.mocked(listSessions).mockReset();
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

  it("passes explicit kbId to listMessages", async () => {
    vi.mocked(listMessages).mockResolvedValue([]);

    await useChatStore.getState().selectSession("c1", "kbA");

    expect(listMessages).toHaveBeenCalledWith("c1", "kbA");
  });

  it("does not apply stale messages after active kb changes", async () => {
    const pending = deferred<Awaited<ReturnType<typeof listMessages>>>();
    vi.mocked(listMessages).mockReturnValue(pending.promise);

    useChatStore.setState((s) => ({ ...s, activeKbId: "kbA" }));
    const loading = useChatStore.getState().selectSession("c1", "kbA");
    useChatStore.setState((s) => ({ ...s, activeKbId: "kbB" }));

    pending.resolve([
      {
        id: "m1",
        conversationId: "c1",
        role: "assistant",
        content: "stale answer",
        vote: null
      }
    ]);
    await loading;

    expect(useChatStore.getState().messages).toEqual([]);
  });

  it("does not apply stale sessions after active kb changes", async () => {
    const pending = deferred<Awaited<ReturnType<typeof listSessions>>>();
    vi.mocked(listSessions).mockReturnValue(pending.promise);

    useChatStore.setState((s) => ({ ...s, activeKbId: "kbA" }));
    const loading = useChatStore.getState().fetchSessions("kbA");
    useChatStore.setState((s) => ({ ...s, activeKbId: "kbB" }));

    pending.resolve([
      {
        conversationId: "c1",
        title: "KB A session",
        kbId: "kbA"
      }
    ]);
    await loading;

    expect(useChatStore.getState().sessions).toEqual([]);
  });

  it("preserves the current session when fetchSessions resolves without it", async () => {
    const pending = deferred<Awaited<ReturnType<typeof listSessions>>>();
    vi.mocked(listSessions).mockReturnValue(pending.promise);

    useChatStore.setState((s) => ({ ...s, activeKbId: "kbA" }));
    const loading = useChatStore.getState().fetchSessions("kbA");

    seedStreamingState("msgA");
    useChatStore.setState((s) => ({ ...s, activeKbId: "kbA" }));
    const handlers = buildHandlers("msgA");
    handlers.onMeta?.({ conversationId: "c_meta", taskId: "task1" });

    pending.resolve([]);
    await loading;

    expect(useChatStore.getState().sessions).toEqual([
      expect.objectContaining({
        id: "c_meta",
        kbId: "kbA"
      })
    ]);
  });
});

describe("chatStore.onMeta", () => {
  beforeEach(() => {
    resetStore();
  });

  it("stores active kbId on session created from stream metadata", () => {
    seedStreamingState("msgA");
    useChatStore.setState((s) => ({ ...s, activeKbId: "kb1" }));
    const handlers = buildHandlers("msgA");

    handlers.onMeta?.({ conversationId: "c_meta", taskId: "task1" });

    expect(useChatStore.getState().sessions[0]).toMatchObject({
      id: "c_meta",
      kbId: "kb1"
    });
  });
});

describe("chatStore stale-stream guards (PR-stream-ux)", () => {
  beforeEach(() => {
    resetStore();
  });

  it("onMessage drops delta when streamingMessageId does not match assistantId", async () => {
    seedStreamingState("msgA");
    // staleHandlers 绑定 msgB；当前 streaming 是 msgA
    const stale = buildHandlers("msgB");
    stale.onMessage?.({ type: "response", delta: "stale" });
    // 等下一帧让任何 rAF flush 跑完
    await new Promise((r) => setTimeout(r, 32));
    expect(useChatStore.getState().messages[0].content).toBe("");
  });

  it("onThinking drops delta when streamingMessageId does not match assistantId", async () => {
    seedStreamingState("msgA");
    const stale = buildHandlers("msgB");
    stale.onThinking?.({ type: "think", delta: "stale think" });
    await new Promise((r) => setTimeout(r, 32));
    expect(useChatStore.getState().messages[0].thinking).toBeUndefined();
  });

  it("onReject drops delta when streamingMessageId does not match assistantId", () => {
    seedStreamingState("msgA");
    const stale = buildHandlers("msgB");
    stale.onReject?.({ type: "response", delta: "stale reject" });
    expect(useChatStore.getState().messages[0].content).toBe("");
  });
});

describe("chatStore.onStatus", () => {
  beforeEach(() => {
    resetStore();
  });

  it("writes streamStatus to streaming message when ids match", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    handlers.onStatus?.({ phase: "retrieving", text: "正在检索…" });
    expect(useChatStore.getState().messages[0].streamStatus).toEqual({
      phase: "retrieving",
      text: "正在检索…"
    });
  });

  it("ignores stale onStatus", () => {
    seedStreamingState("msgA");
    const stale = buildHandlers("msgB");
    stale.onStatus?.({ phase: "rewriting", text: "stale" });
    expect(useChatStore.getState().messages[0].streamStatus).toBeUndefined();
  });

  it("clears streamStatus when onFinish runs", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    handlers.onStatus?.({ phase: "generating", text: "生成中…" });
    expect(useChatStore.getState().messages[0].streamStatus).toBeDefined();
    handlers.onFinish?.({ messageId: "db_x", title: null });
    expect(useChatStore.getState().messages[0].streamStatus).toBeUndefined();
  });
});

describe("chatStore rAF batching (PR-stream-ux)", () => {
  beforeEach(() => {
    resetStore();
  });

  it("multiple onMessage deltas concatenate in order after flush", async () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    handlers.onMessage?.({ type: "response", delta: "Hello" });
    handlers.onMessage?.({ type: "response", delta: " " });
    handlers.onMessage?.({ type: "response", delta: "world" });
    // 等待 rAF（jsdom 默认是 setTimeout 模拟，给出足够时间）
    await new Promise((r) => setTimeout(r, 32));
    expect(useChatStore.getState().messages[0].content).toBe("Hello world");
  });

  it("onFinish flushes pending response buffer (no tail-loss)", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    handlers.onMessage?.({ type: "response", delta: "tail" });
    // 不等 rAF，立刻触发 finish
    handlers.onFinish?.({ messageId: "db_x", title: null });
    expect(useChatStore.getState().messages[0].content).toBe("tail");
  });

  it("onCancel flushes pending response buffer before adding cancel suffix", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    handlers.onMessage?.({ type: "response", delta: "partial" });
    handlers.onCancel?.({ messageId: null, title: null });
    expect(useChatStore.getState().messages[0].content).toContain("partial");
    expect(useChatStore.getState().messages[0].content).toContain("（已停止生成）");
  });

  it("onDone flushes pending thinking buffer", () => {
    seedStreamingState("msgA");
    const handlers = buildHandlers("msgA");
    handlers.onThinking?.({ type: "think", delta: "thought-tail" });
    handlers.onDone?.();
    expect(useChatStore.getState().messages[0].thinking).toBe("thought-tail");
  });
});

describe("resetForNewSpace", () => {
  beforeEach(() => {
    resetStore();
  });

  it("clears streaming state and ignores late stream metadata", () => {
    seedStreamingState("msgA");
    useChatStore.setState((s) => ({
      ...s,
      activeKbId: "kbA",
      streamAbort: vi.fn()
    }));
    const handlers = buildHandlers("msgA");

    useChatStore.getState().resetForNewSpace();
    useChatStore.setState((s) => ({ ...s, activeKbId: "kbB" }));
    handlers.onMeta?.({ conversationId: "c_late", taskId: "task-late" });

    expect(useChatStore.getState()).toMatchObject({
      currentSessionId: null,
      isStreaming: false,
      streamingMessageId: null,
      streamTaskId: null,
      sessions: []
    });
  });
});
