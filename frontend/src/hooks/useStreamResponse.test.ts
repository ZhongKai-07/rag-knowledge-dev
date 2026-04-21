import { describe, expect, it, vi } from "vitest";
import { createStreamResponse, type StreamHandlers } from "./useStreamResponse";
import type { SourcesPayload } from "@/types";

/**
 * 给定一串 SSE 帧构造的 Response body，断言 useStreamResponse 内部的
 * dispatchEvent 能把 "event: sources" 的帧正确路由到 handlers.onSources。
 *
 * 这是 P4 评审要求的 parser-level 回归保护：若有人改 chatStore 但忘了在
 * useStreamResponse 里加 case "sources"，本测试失败。
 */
function buildSseResponse(framesText: string): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(framesText));
      controller.close();
    }
  });
  return new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

describe("useStreamResponse SSE routing", () => {
  it("routes event: sources to handlers.onSources with parsed payload", async () => {
    const payload: SourcesPayload = {
      conversationId: "c_abc",
      messageId: null,
      cards: [{
        index: 1, docId: "d1", docName: "doc.pdf", kbId: "kb1",
        topScore: 0.9, chunks: [{ chunkId: "c1", chunkIndex: 0, preview: "hi", score: 0.9 }]
      }]
    };
    const frame = `event: sources\ndata: ${JSON.stringify(payload)}\n\n`;
    const onSources = vi.fn();
    const handlers: StreamHandlers = { onSources };

    // 通过 fetch mock 让 createStreamResponse 读到我们构造的 SSE 帧
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue(buildSseResponse(frame));
    try {
      const r = createStreamResponse({ url: "/x" }, handlers);
      await r.start();
    } finally {
      globalThis.fetch = originalFetch;
    }

    expect(onSources).toHaveBeenCalledTimes(1);
    expect(onSources).toHaveBeenCalledWith(payload);
  });
});
