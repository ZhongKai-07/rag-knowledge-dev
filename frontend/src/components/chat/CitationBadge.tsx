import type { SourceCard } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  n: number;
  indexMap: Map<number, SourceCard>;
  onClick: (n: number) => void;
}

export function CitationBadge({ n, indexMap, onClick }: Props) {
  const card = indexMap.get(n);
  // 越界编号（LLM 产出 [^99] 而 cards 只有 8）降级为纯文本 <sup>，不交互
  if (!card) {
    return <sup className="text-[11px] text-muted-foreground">[^{n}]</sup>;
  }
  return (
    <sup className="mx-0.5">
      <button
        type="button"
        onClick={() => onClick(n)}
        title={card.docName}
        aria-label={`引用 ${n}：${card.docName}`}
        className={cn(
          "inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-md",
          "bg-[#DBEAFE] px-1 text-[11px] font-semibold text-[#2563EB]",
          "transition-colors hover:bg-[#BFDBFE]",
          "dark:bg-[#1E3A8A] dark:text-[#93C5FD] dark:hover:bg-[#1E40AF]",
        )}
      >
        {n}
      </button>
    </sup>
  );
}
