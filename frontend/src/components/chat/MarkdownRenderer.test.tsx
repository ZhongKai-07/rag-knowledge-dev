import { describe, it, expect, afterEach } from "vitest";
import { render, cleanup } from "@testing-library/react";
import { MarkdownRenderer } from "./MarkdownRenderer";
import type { SourceCard } from "@/types";

const card1: SourceCard = {
  index: 1, docId: "d1", docName: "手册.pdf", kbId: "kb", topScore: 0.9, chunks: [],
};

describe("<MarkdownRenderer /> rollback contract", () => {
  afterEach(cleanup);

  it("when sources is undefined, [^1] remains as plain text (no <sup> superscript)", () => {
    const { container } = render(
      <MarkdownRenderer content={"前文[^1]后文"} />
    );
    // 不应出现 <sup> 结构
    expect(container.querySelector("sup")).toBeNull();
    // [^1] 作为纯文本保留（react-markdown 默认会转义为文本节点）
    expect(container.textContent).toContain("[^1]");
  });

  it("when sources is empty array, plugin is not mounted — same as undefined", () => {
    const { container } = render(
      <MarkdownRenderer content={"前文[^1]后文"} sources={[]} />
    );
    expect(container.querySelector("sup")).toBeNull();
    expect(container.textContent).toContain("[^1]");
  });

  it("when sources has matching card, [^1] becomes <CitationBadge>", () => {
    const { container } = render(
      <MarkdownRenderer content={"前文[^1]后文"} sources={[card1]} />
    );
    // 应出现 <sup> 且内部含 button
    const sup = container.querySelector("sup");
    expect(sup).not.toBeNull();
    expect(sup?.querySelector("button")).not.toBeNull();
  });

  it("isStreaming=true skips SyntaxHighlighter for fenced code blocks (PR-2 A)", () => {
    const fence = "```js\nconst a = 1;\nconst b = 2;\n```";
    const { container, rerender } = render(
      <MarkdownRenderer content={fence} isStreaming={true} />
    );
    // 流式降级容器有 data-streaming，且无 prismjs token span
    expect(container.querySelector('[data-streaming="true"]')).not.toBeNull();
    expect(container.querySelectorAll('span[class*="token"]').length).toBe(0);

    // done 后切回高亮：token span 出现
    rerender(<MarkdownRenderer content={fence} isStreaming={false} />);
    expect(container.querySelector('[data-streaming="true"]')).toBeNull();
    expect(container.querySelectorAll('span[class*="token"]').length).toBeGreaterThan(0);
  });
});
