import { describe, it, expect } from "vitest";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import { remarkCitations } from "./remarkCitations";
import type { Root } from "mdast";

function transform(markdown: string): Root {
  // PR5 N-4：走完整 unified pipeline（.use(remarkCitations).runSync(...)），
  // 插件在 unified context 下执行。之前的 `.parse()` + 手调插件形式让 plugin
  // 绕过 context，未来若 remarkCitations 用到 context API 会 silently 失败。
  const processor = unified().use(remarkParse).use(remarkGfm).use(remarkCitations);
  return processor.runSync(processor.parse(markdown)) as Root;
}

function findAllCiteNodes(tree: any): Array<{ n?: number }> {
  const out: Array<{ n?: number }> = [];
  const walk = (node: any) => {
    if (!node) return;
    if (node.type === "cite") {
      out.push({ n: node?.data?.hProperties?.["data-n"] });
    }
    if (Array.isArray(node.children)) node.children.forEach(walk);
  };
  walk(tree);
  return out;
}

function hasNodeType(tree: any, type: string): boolean {
  let found = false;
  const walk = (node: any) => {
    if (!node || found) return;
    if (node.type === type) { found = true; return; }
    if (Array.isArray(node.children)) node.children.forEach(walk);
  };
  walk(tree);
  return found;
}

describe("remarkCitations plugin", () => {
  it("transforms text [^n] into cite nodes", () => {
    const tree = transform("见 [^1] 和 [^3]。");
    const cites = findAllCiteNodes(tree);
    expect(cites.map(c => c.n)).toEqual([1, 3]);
  });

  it("skips inlineCode: array[^2]` not transformed", () => {
    const tree = transform("代码 `arr[^2]`");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });

  it("skips fenced code block", () => {
    const tree = transform("```ts\nconst x = arr[^4];\n```");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });

  it("skips link text", () => {
    const tree = transform("[文档 [^5]](/x)");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });

  it("transforms footnoteReference into cite", () => {
    const tree = transform("见 [^1]\n\n[^1]: 定义内容");
    const cites = findAllCiteNodes(tree);
    expect(cites.length).toBeGreaterThanOrEqual(1);
    expect(cites.some(c => c.n === 1)).toBe(true);
  });

  it("removes footnoteDefinition nodes entirely", () => {
    const tree = transform("见 [^1]\n\n[^1]: 定义内容");
    expect(hasNodeType(tree, "footnoteDefinition")).toBe(false);
  });

  it("does not emit cite for [^n] inside footnoteDefinition body (definition deleted before text walk)", () => {
    // 定义体里的 [^2] 不应被产出任何 cite；因为 footnoteDefinition 整段被删
    const tree = transform("正文无引用\n\n[^1]: 定义里有 [^2]");
    expect(findAllCiteNodes(tree)).toEqual([]);
  });
});
