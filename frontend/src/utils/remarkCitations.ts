import { visit, SKIP } from "unist-util-visit";
import type { Plugin } from "unified";
import type { Root, Parent, Text } from "mdast";
import { CITATION, SKIP_PARENT_TYPES } from "./citationAst";

/**
 * 三合一职责：
 *   1) footnoteReference → cite（remark-gfm 预解析产物）
 *   2) footnoteDefinition → 整段删除（避免底部渲染 GFM footnote block）
 *   3) text 节点的 [^n] → cite（主路径：LLM 只写 [^n] 不写定义块）
 *
 * 第 2 步的 splice 删除使得第 3 步 text visit 时定义子树已不存在，
 * 所以插件这里采用三段 visit 是安全的（历史坑：若在同一 tree 上跑两次
 * text-only visit 会让第二趟走进定义体误抓 [^n]，故保留此三段顺序）。
 *
 * 顺序硬固定：`remarkPlugins={[remarkGfm, remarkCitations]}`
 */
export const remarkCitations: Plugin<[], Root> = () => (tree) => {
  // 1) footnoteReference → cite
  visit(tree, "footnoteReference", (node: any) => {
    const n = parseInt(String(node.identifier ?? ""), 10);
    if (!Number.isFinite(n)) return;
    node.type = "cite";
    node.data = {
      ...(node.data ?? {}),
      hName: "cite",
      hProperties: { "data-n": n },
    };
  });

  // 2) footnoteDefinition → 删除
  visit(tree, "footnoteDefinition", (_node, index, parent) => {
    if (parent && typeof index === "number") {
      (parent as Parent).children.splice(index, 1);
      return [SKIP, index];
    }
  });

  // 3) text 节点 [^n] → cite
  visit(tree, "text", (node: Text, index, parent) => {
    if (!parent || typeof index !== "number") return;
    if (SKIP_PARENT_TYPES.has((parent as any).type)) return SKIP;
    const value = node.value;
    if (!value.includes("[^")) return;

    const parts: any[] = [];
    let lastEnd = 0;
    let match: RegExpExecArray | null;
    CITATION.lastIndex = 0;
    while ((match = CITATION.exec(value)) !== null) {
      if (match.index > lastEnd) {
        parts.push({ type: "text", value: value.slice(lastEnd, match.index) });
      }
      const n = parseInt(match[1], 10);
      parts.push({
        type: "cite",
        data: { hName: "cite", hProperties: { "data-n": n } },
      });
      lastEnd = match.index + match[0].length;
    }
    if (parts.length === 0) return;
    if (lastEnd < value.length) {
      parts.push({ type: "text", value: value.slice(lastEnd) });
    }
    (parent as Parent).children.splice(index, 1, ...parts);
    return [SKIP, index + parts.length];
  });
};
