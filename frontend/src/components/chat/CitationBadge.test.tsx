import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CitationBadge } from "./CitationBadge";
import type { SourceCard } from "@/types";

const card1: SourceCard = {
  index: 1, docId: "d1", docName: "员工手册.pdf", kbId: "kb",
  topScore: 0.9, chunks: [],
};

describe("<CitationBadge />", () => {
  afterEach(cleanup);

  it("renders button when index exists in indexMap and triggers onClick(n)", async () => {
    const onClick = vi.fn();
    const indexMap = new Map<number, SourceCard>([[1, card1]]);
    render(<CitationBadge n={1} indexMap={indexMap} onClick={onClick} />);
    const btn = screen.getByRole("button");
    expect(btn).toBeDefined();
    expect(btn.getAttribute("title")).toBe("员工手册.pdf");
    await userEvent.click(btn);
    expect(onClick).toHaveBeenCalledWith(1);
  });

  it("renders plain <sup>[^n]</sup> (no button) when index is out of range", () => {
    const indexMap = new Map<number, SourceCard>([[1, card1]]);
    render(<CitationBadge n={99} indexMap={indexMap} onClick={vi.fn()} />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText("[^99]")).toBeDefined();
  });

  it("uses card.docName as title attribute", () => {
    const indexMap = new Map<number, SourceCard>([[1, card1]]);
    render(<CitationBadge n={1} indexMap={indexMap} onClick={vi.fn()} />);
    const btn = screen.getByRole("button");
    expect(btn.getAttribute("title")).toBe("员工手册.pdf");
  });

  it("主引用 n=1 应用 vio-aurora-chip gradient 类", () => {
    const card: SourceCard = {
      index: 1, docName: "GMRA.pdf", docId: "d1", kbId: "kb1",
      topScore: 0.9, chunks: [],
    };
    const indexMap = new Map<number, SourceCard>([[1, card]]);

    const { container } = render(
      <CitationBadge n={1} indexMap={indexMap} onClick={() => {}} />,
    );

    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    expect(button!.className).toContain("vio-aurora-chip");
  });

  it("非主引用 n>1 使用实色雾紫底（无 gradient）", () => {
    const card: SourceCard = {
      index: 2, docName: "ISDA.pdf", docId: "d2", kbId: "kb1",
      topScore: 0.8, chunks: [],
    };
    const indexMap = new Map<number, SourceCard>([[2, card]]);

    const { container } = render(
      <CitationBadge n={2} indexMap={indexMap} onClick={() => {}} />,
    );

    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    expect(button!.className).not.toContain("vio-aurora-chip");
    expect(button!.className).toMatch(/bg-vio-accent-subtle|bg-\[var\(--vio-accent-subtle\)\]/);
  });
});
