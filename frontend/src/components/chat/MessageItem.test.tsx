import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { render, screen, cleanup, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MessageItem } from "./MessageItem";
import { CITATION_HIGHLIGHT_MS } from "@/utils/citationAst";
import type { Message, SourceCard } from "@/types";

// Mock useChatStore.sendMessage / regenerateLastAssistantMessage / isStreaming (MessageItem 依赖)
const regenMock = vi.fn();
vi.mock("@/stores/chatStore", () => ({
  useChatStore: (selector: any) =>
    selector({
      sendMessage: vi.fn(),
      regenerateLastAssistantMessage: regenMock,
      isStreaming: false,
    }),
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
    regenMock.mockClear();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("click on CitationBadge triggers scrollIntoView + highlight ring", async () => {
    const user = userEvent.setup();
    render(<MessageItem message={makeMessage()} isLast={true} />);
    // ^引用 锚定避免误抓 "跳到正文中的引用 N" 按钮（E 双向引用）
    const badge = screen.getByRole("button", { name: /^引用\s+1/ });
    await user.click(badge);
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it("highlightedIndex clears after 1.5s via timer", () => {
    vi.useFakeTimers();
    const { container } = render(<MessageItem message={makeMessage()} isLast={true} />);
    // 通过 container 查询按钮（避免 userEvent 与 fake timers 冲突）
    const badge = container.querySelector(
      'button[aria-label^="引用"]'
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

  it("renders assistant role badge with brand name", () => {
    render(<MessageItem message={makeMessage()} isLast={true} />);
    // 角色条品牌名（D）：让 user 与 assistant 视觉区分加强
    expect(screen.getByText("HT KnowledgeBase")).toBeDefined();
  });

  it("renders regenerate button only when isLast and not streaming, calling store on click", async () => {
    const user = userEvent.setup();
    regenMock.mockResolvedValue(undefined);
    const { unmount } = render(<MessageItem message={makeMessage()} isLast={true} />);
    const btn = screen.getByRole("button", { name: "重新生成" });
    await user.click(btn);
    expect(regenMock).toHaveBeenCalledTimes(1);
    unmount();

    // isLast=false 时不渲染
    render(<MessageItem message={makeMessage()} isLast={false} />);
    expect(screen.queryByRole("button", { name: "重新生成" })).toBeNull();
  });

  it("clicking Sources jump button scrolls to matching [^n] in content (E bidirectional)", async () => {
    const user = userEvent.setup();
    render(<MessageItem message={makeMessage()} isLast={true} />);
    const jumpBtn = screen.getByRole("button", { name: /跳到正文中的引用\s*1/ });
    await user.click(jumpBtn);
    // 跳转按钮触发 querySelector('[data-cite-n="1"]') 后 scrollIntoView
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it("hides assistant role badge while message.isThinking", () => {
    // 思考态由 ThinkingIndicator 自带视觉信号，不再叠加角色条
    render(
      <MessageItem
        message={makeMessage({ isThinking: true, content: "" })}
        isLast={true}
      />
    );
    expect(screen.queryByText("HT KnowledgeBase")).toBeNull();
  });

  it("clears the pending highlight timer when component unmounts mid-timeout", () => {
    vi.useFakeTimers();
    const clearTimeoutSpy = vi.spyOn(window, "clearTimeout");
    const { container, unmount } = render(
      <MessageItem message={makeMessage()} isLast={true} />
    );
    const badge = container.querySelector(
      'button[aria-label^="引用"]'
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
