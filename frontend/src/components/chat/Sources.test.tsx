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

  it("renders cards in backend-supplied order", () => {
    render(<Sources cards={[card(1), card(2), card(3)]} highlightedIndex={null} />);
    const headings = screen.getAllByText(/\[\^(1|2|3)\]/);
    expect(headings.map(h => h.textContent)).toEqual(["[^1]", "[^2]", "[^3]"]);
  });

  it("auto-expands the card matching highlightedIndex on mount", () => {
    render(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    // 卡片 2 的 chunk 展开时有 preview
    expect(screen.getAllByText("preview").length).toBeGreaterThan(0);
  });

  it("auto-expands a different card when highlightedIndex changes on re-render", () => {
    // 初始高亮 card 1 → 仅 card 1 的 2 个 chunk 展开（preview×2）
    const { rerender } = render(
      <Sources cards={[card(1), card(2)]} highlightedIndex={1} />
    );
    expect(screen.getAllByText("preview")).toHaveLength(2);

    // 切到 card 2 → useEffect 触发 expanded.add(2)，
    // 现有契约是 Set add 不移除 1，两张卡都保持展开（preview×4）
    rerender(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    expect(screen.getAllByText("preview")).toHaveLength(4);
  });

  it("toggles card expansion on click", async () => {
    render(<Sources cards={[card(1)]} highlightedIndex={null} />);
    const headerBtn = screen.getByRole("button", { name: /员工|D1|\[\^1\]/ });
    // 初始折叠：无 preview 文本
    expect(screen.queryAllByText("preview")).toHaveLength(0);
    await userEvent.click(headerBtn);
    expect(screen.getAllByText("preview").length).toBeGreaterThan(0);
    await userEvent.click(headerBtn);
    // 再点一次收起
    expect(screen.queryAllByText("preview")).toHaveLength(0);
  });

  it("applies ring highlight class to the card matching highlightedIndex", () => {
    const { container } = render(<Sources cards={[card(1), card(2)]} highlightedIndex={2} />);
    const cardEls = container.querySelectorAll("[class*='ring']");
    expect(cardEls.length).toBeGreaterThanOrEqual(1);
  });
});
