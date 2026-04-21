/**
 * 共享常量：`remarkCitations` 插件需要的 AST 判定材料。
 *
 * PR3 Option X 下前端不再需要独立的 cited 提取路径，
 * 因此本文件不再导出 collectCitationIndexesFromTree。
 */

/**
 * 匹配 [^n] 的正则（g 标志支持 matcher.lastIndex 复用）。
 */
export const CITATION = /\[\^(\d+)]/g;

/**
 * 遍历 mdast 时需跳过的父节点类型集合。
 * 对应 remarkCitations 的硬约束：代码块 / 行内代码 / 链接 / 图片 / 链接引用里的
 * [^n] 字面量保持原样，不转 cite 节点。
 */
export const SKIP_PARENT_TYPES = new Set<string>([
  "inlineCode", "code", "link", "image", "linkReference",
]);
