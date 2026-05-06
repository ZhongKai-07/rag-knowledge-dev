import * as React from "react";
import type { SourceCard } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  cards: SourceCard[];
  highlightedIndex: number | null;
}

export const Sources = React.forwardRef<HTMLDivElement, Props>(
  function Sources({ cards, highlightedIndex }, ref) {
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
      <div ref={ref} className="mt-3 space-y-1.5">
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
              <button
                type="button"
                onClick={() => setExpanded(prev => {
                  const next = new Set(prev);
                  if (next.has(card.index)) next.delete(card.index);
                  else next.add(card.index);
                  return next;
                })}
                className="flex w-full items-center gap-2 text-left text-sm"
                aria-expanded={isExpanded}
              >
                <span className="font-mono text-xs text-[#2563EB] shrink-0">
                  来源 {card.index}
                </span>
                <span className="text-muted-foreground shrink-0">|</span>
                <span className="flex-1 truncate font-medium">{card.docName}</span>
                {restChunks.length > 0 ? (
                  <span className="text-xs text-muted-foreground shrink-0">
                    +{restChunks.length}
                  </span>
                ) : null}
              </button>
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
