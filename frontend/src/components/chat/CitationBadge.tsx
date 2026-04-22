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
    return <sup className="text-[11px] text-vio-ink/40 font-mono">[^{n}]</sup>;
  }

  const isPrimary = n === 1;

  return (
    <sup className="mx-0.5">
      <button
        type="button"
        onClick={() => onClick(n)}
        title={card.docName}
        aria-label={`引用 ${n}：${card.docName}`}
        className={cn(
          "inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full",
          "px-1.5 text-[10px] font-bold font-mono",
          "transition-all duration-200",
          isPrimary
            ? "vio-aurora-chip hover:brightness-110"
            : "bg-[var(--vio-accent-subtle)] text-[var(--vio-accent)] hover:bg-[var(--vio-accent-2)] hover:text-white",
        )}
      >
        {n}
      </button>
    </sup>
  );
}
