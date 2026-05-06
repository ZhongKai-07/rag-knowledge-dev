import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as React from "react";
import { Sources } from "./Sources";
import type { SourceCard, SourceChunk } from "@/types";

function chunk(id: string, idx: number, preview = "preview"): SourceChunk {
  return { chunkId: id, chunkIndex: idx, preview, score: 0.9 };
}
function card(index: number, docName = `D${index}`): SourceCard {
  return {
    index, docId: `d${index}`, docName, kbId: "kb", topScore: 0.9,
    chunks: [chunk(`c${index}_1`, 0), chunk(`c${index}_2`, 1)],
  };
}

describe("<Sources />", () => {
  afterEach(cleanup);

  it("renders null when cards is empty", () => {
    const { container } = render(<Sources cards={[]} highlightedIndex={null} />);
    expect(container.innerHTML).toBe("");
  });

  it("renders cards in backend-supplied order with semantic header", () => {
    render(<Sources cards={[card(1), card(2), card(3)]} highlightedIndex={null} />);
    expect(screen.getByText("来源 1")).toBeDefined();
    expect(screen.getByText("来源 2")).toBeDefined();
    expect(screen.getByText("来源 3")).toBeDefined();
    expect(screen.getByText(/参考来源/)).toBeDefined();
  });

  it("does not display topScore numeric directly to user", () => {
    render(<Sources cards={[card(1)]} highlightedIndex={null} />);
    // 0.9 / 0.90 这样的分数字符串不在可见文本中
    expect(screen.queryByText("0.90")).toBeNull();
    expect(screen.queryByText("0.9")).toBeNull();
  });

  it("always shows first chunk preview by default (collapsed state)", () => {
    render(<Sources cards={[card(1)]} highlightedIndex={null} />);
    // 折叠：rest 容器 aria-hidden，ByRole 默认过滤；firstChunk <p> 可见
    // 注：grid-rows trick 下 restChunks DOM 节点仍存在但被 ByRole 跳过
    expect(screen.queryByRole("list")).toBeNull();
  });

  it("auto-expands the card matching highlightedIndex on mount", () => {
    render(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    // card 1 折叠 → 0 list；card 2 高亮展开 → 1 list
    expect(screen.getAllByRole("list")).toHaveLength(1);
  });

  it("auto-expands a different card when highlightedIndex changes on re-render", () => {
    // 初始 card 1 高亮：card 1 展开 → 1 list；card 2 折叠 → 0
    const { rerender } = render(
      <Sources cards={[card(1), card(2)]} highlightedIndex={1} />
    );
    expect(screen.getAllByRole("list")).toHaveLength(1);

    // 切到 card 2：Set add 不移除 1，两张都展开 → 2 list
    rerender(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    expect(screen.getAllByRole("list")).toHaveLength(2);
  });

  it("toggles card expansion on click (rest chunks revealed/hidden)", async () => {
    render(<Sources cards={[card(1)]} highlightedIndex={null} />);
    const headerBtn = screen.getByRole("button", { name: /展开\/折叠来源\s*1/ });
    // 初始折叠：rest 列表被 aria-hidden 过滤
    expect(screen.queryByRole("list")).toBeNull();
    await userEvent.click(headerBtn);
    // 展开：1 个 list 出现
    expect(screen.getByRole("list")).toBeDefined();
    await userEvent.click(headerBtn);
    // 再点收起：回到 null
    expect(screen.queryByRole("list")).toBeNull();
  });

  it("applies ring highlight class to the card matching highlightedIndex", () => {
    const { container } = render(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    const cardEls = container.querySelectorAll("[class*='ring']");
    expect(cardEls.length).toBeGreaterThanOrEqual(1);
  });

  it("exposes topScore via title tooltip for debug", () => {
    const { container } = render(<Sources cards={[card(1)]} highlightedIndex={null} />);
    const cardEl = container.querySelector("[title]");
    expect(cardEl?.getAttribute("title")).toMatch(/相关度/);
  });
});
