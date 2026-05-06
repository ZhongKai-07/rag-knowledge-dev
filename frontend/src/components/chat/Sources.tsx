import * as React from "react";
import { ArrowUp } from "lucide-react";
import type { SourceCard } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  cards: SourceCard[];
  highlightedIndex: number | null;
  /** 点击卡片"跳到正文"按钮（E 双向引用）；不传则不渲染该按钮。 */
  onJumpToCite?: (n: number) => void;
}

export const Sources = React.forwardRef<HTMLDivElement, Props>(
  function Sources({ cards, highlightedIndex, onJumpToCite }, ref) {
    const [expanded, setExpanded] = React.useState<Set<number>>(new Set());

    // 高亮的卡片随 highlightedIndex 变化自动展开
    React.useEffect(() => {
      if (highlightedIndex == null) return;
      setExpanded(prev => new Set(prev).add(highlightedIndex));
    }, [highlightedIndex]);

    // Option X：忠实渲染后端 cards 原序；后端已按 topScore desc clip 到 max-cards。
    // 前端不做 partition / cited-never-clipped 保护（cited ≤ cards.length 必然成立）。
    if (cards.length === 0) return null;

    return (
      <div ref={ref} className="mt-3 space-y-1.5 animate-fade-up">
        <div className="text-xs font-medium text-muted-foreground">
          参考来源（{cards.length}）
        </div>
        {cards.map(card => {
          const isExpanded = expanded.has(card.index);
          const isHighlighted = highlightedIndex === card.index;
          const firstChunk = card.chunks[0];
          const restChunks = card.chunks.slice(1);
          // topScore 不直接展示给用户，保留在 title tooltip 给开发/排障用
          const tooltip = `相关度 ${card.topScore.toFixed(2)}`;
          return (
            <div
              key={card.index}
              className={cn(
                "rounded-md border border-border bg-background p-2.5 transition-all",
                isHighlighted && "ring-2 ring-[#3B82F6] ring-offset-1",
              )}
              title={tooltip}
            >
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setExpanded(prev => {
                    const next = new Set(prev);
                    if (next.has(card.index)) next.delete(card.index);
                    else next.add(card.index);
                    return next;
                  })}
                  className="flex flex-1 items-center gap-2 text-left text-sm min-w-0"
                  aria-expanded={isExpanded}
                  aria-label={`展开/折叠来源 ${card.index}`}
                >
                  <span className="font-mono text-xs text-[#2563EB] shrink-0">
                    来源 {card.index}
                  </span>
                  <span className="text-muted-foreground shrink-0">|</span>
                  <span className="flex-1 truncate font-medium">{card.docName}</span>
                  {restChunks.length > 0 ? (
                    <span
                      className="text-xs text-muted-foreground shrink-0"
                      title={`剩余 ${restChunks.length} 个片段：\n${restChunks
                        .map((c) => `· ${c.preview.slice(0, 60)}${c.preview.length > 60 ? "…" : ""}`)
                        .join("\n")}`}
                    >
                      +{restChunks.length}
                    </span>
                  ) : null}
                </button>
                {onJumpToCite ? (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onJumpToCite(card.index);
                    }}
                    aria-label={`跳到正文中的引用 ${card.index}`}
                    className="flex h-6 w-6 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
                  >
                    <ArrowUp className="h-3.5 w-3.5" />
                  </button>
                ) : null}
              </div>
              {firstChunk ? (
                <p className="mt-1.5 line-clamp-2 border-l-2 border-border pl-2 text-xs leading-relaxed text-muted-foreground">
                  {firstChunk.preview}
                </p>
              ) : null}
              {isExpanded && restChunks.length > 0 ? (
                <ul className="mt-2 space-y-1 text-xs text-muted-foreground">
                  {restChunks.map(chunk => (
                    <li key={chunk.chunkId} className="border-l-2 border-border pl-2">
                      {chunk.preview}
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          );
        })}
      </div>
    );
  }
);
