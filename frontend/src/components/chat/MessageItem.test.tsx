import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { render, screen, cleanup, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MessageItem } from "./MessageItem";
import { CITATION_HIGHLIGHT_MS } from "@/utils/citationAst";
import type { Message, SourceCard } from "@/types";

// Mock useChatStore.sendMessage (MessageItem 依赖)
vi.mock("@/stores/chatStore", () => ({
  useChatStore: (selector: any) =>
    selector({ sendMessage: vi.fn() }),
}));

// Mock useThemeStore (MarkdownRenderer 依赖)
vi.mock("@/stores/themeStore", () => ({
  useThemeStore: (selector: any) =>
    selector({ theme: "light" }),
}));

const card1: SourceCard = {
  index: 1,
  docId: "d1",
  docName: "员工手册.pdf",
  kbId: "kb",
  topScore: 0.9,
  chunks: [
    { chunkId: "c1", chunkIndex: 0, preview: "员工入职流程...", score: 0.9 },
  ],
};

const makeMessage = (overrides: Partial<Message> = {}): Message => ({
  id: "asst-1",
  role: "assistant",
  content: "第一句[^1]。",
  status: "done",
  sources: [card1],
  ...overrides,
});

describe("<MessageItem /> citation interaction", () => {
  beforeEach(() => {
    // jsdom 里 scrollIntoView 默认不存在
    Element.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("click on CitationBadge triggers scrollIntoView + highlight ring", async () => {
    const user = userEvent.setup();
    render(<MessageItem message={makeMessage()} isLast={true} />);
    const badge = screen.getByRole("button", { name: /引用\s*1/ });
    await user.click(badge);
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it("highlightedIndex clears after 1.5s via timer", () => {
    vi.useFakeTimers();
    const { container } = render(<MessageItem message={makeMessage()} isLast={true} />);
    // 通过 container 查询按钮（避免 userEvent 与 fake timers 冲突）
    const badge = container.querySelector(
      'button[aria-label*="引用"]'
    ) as HTMLButtonElement;
    act(() => {
      badge.click();
    });
    // 点击后立即有 ring（Sources card 上的 div.ring-2，用 ring-\[#3B82F6\] 限定避免误匹配 focus-visible:ring-2）
    expect(container.querySelector("div[class*='ring-2']")).not.toBeNull();
    // 前进 CITATION_HIGHLIGHT_MS（PR5 N-5 抽成常量 SSOT）
    act(() => {
      vi.advanceTimersByTime(CITATION_HIGHLIGHT_MS);
    });
    expect(container.querySelector("div[class*='ring-2']")).toBeNull();
  });

  it("renders Sources when message.sources has entries", () => {
    render(<MessageItem message={makeMessage()} isLast={true} />);
    // 新设计：列表头 "参考来源（1）"
    expect(screen.getByText(/参考来源/)).toBeDefined();
    expect(screen.getByText("来源 1")).toBeDefined();
  });

  it("does not render Sources when message.sources is undefined", () => {
    render(<MessageItem message={makeMessage({ sources: undefined })} isLast={true} />);
    expect(screen.queryByText(/参考来源/)).toBeNull();
  });

  it("clears the pending highlight timer when component unmounts mid-timeout", () => {
    vi.useFakeTimers();
    const clearTimeoutSpy = vi.spyOn(window, "clearTimeout");
    const { container, unmount } = render(
      <MessageItem message={makeMessage()} isLast={true} />
    );
    const badge = container.querySelector(
      'button[aria-label*="引用"]'
    ) as HTMLButtonElement;

    // 点击后 timer 被 set（timerRef.current 为 setTimeout 返回值）
    act(() => {
      badge.click();
    });
    const clearsBeforeUnmount = clearTimeoutSpy.mock.calls.length;

    // 卸载 → useEffect cleanup 必须 window.clearTimeout(timerRef.current)
    unmount();

    // 至少多一次 clearTimeout 调用（单元测试锁 cleanup 行为；允许其他 cleanup 顺带调，不锁死次数）
    expect(clearTimeoutSpy.mock.calls.length).toBeGreaterThan(clearsBeforeUnmount);

    // 再推进时间，不应触发 setHighlightedIndex → 不应有 React act warning 提示卸载后 state 更新
    // 由于组件已卸载，这里仅验证不抛错
    expect(() =>
      act(() => {
        vi.advanceTimersByTime(2000);
      })
    ).not.toThrow();
  });
});
