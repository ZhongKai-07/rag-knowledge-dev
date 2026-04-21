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
});
