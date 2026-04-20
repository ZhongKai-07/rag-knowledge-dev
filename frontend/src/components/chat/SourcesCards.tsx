import * as React from "react";
import { ChevronDown, FileText } from "lucide-react";

import type { SourceRef } from "@/types";
import { cn } from "@/lib/utils";

interface SourcesCardsProps {
  sources: SourceRef[];
}

export function SourcesCards({ sources }: SourcesCardsProps) {
  const [expanded, setExpanded] = React.useState<Record<number, boolean>>({});

  if (!Array.isArray(sources) || sources.length === 0) {
    return null;
  }

  return (
    <div className="mt-3 space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs font-medium text-muted-foreground">来源</span>
        {sources.map((source) => (
          <button
            key={`ref-${source.index}`}
            type="button"
            className="rounded bg-blue-50 px-1.5 py-0.5 text-xs font-medium text-blue-700 hover:bg-blue-100 dark:bg-blue-900/40 dark:text-blue-200 dark:hover:bg-blue-900/60"
            onClick={() => {
              setExpanded((prev) => ({ ...prev, [source.index]: true }));
              document.getElementById(`source-card-${source.index}`)?.scrollIntoView({
                behavior: "smooth",
                block: "center"
              });
            }}
          >
            [{source.index}]
          </button>
        ))}
      </div>
      {sources.map((source) => {
        const isOpen = Boolean(expanded[source.index]);
        return (
          <div
            key={`${source.chunkId}-${source.index}`}
            id={`source-card-${source.index}`}
            className="overflow-hidden rounded-lg border border-border bg-card"
          >
            <button
              type="button"
              onClick={() =>
                setExpanded((prev) => ({
                  ...prev,
                  [source.index]: !prev[source.index]
                }))
              }
              className="flex w-full items-start gap-3 px-3 py-2 text-left hover:bg-muted/40"
            >
              <div className="mt-0.5 rounded bg-blue-100 px-1.5 py-0.5 text-xs font-medium text-blue-700 dark:bg-blue-900/40 dark:text-blue-200">
                [{source.index}]
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                  <FileText className="h-3.5 w-3.5 shrink-0" />
                  <span className="truncate">{source.docName || "未命名文档"}</span>
                </div>
                <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                  {source.snippet || source.chunkSummary}
                </p>
              </div>
              <ChevronDown
                className={cn(
                  "mt-0.5 h-4 w-4 shrink-0 text-muted-foreground transition-transform",
                  isOpen && "rotate-180"
                )}
              />
            </button>
            {isOpen ? (
              <div className="border-t border-border bg-muted/20 px-3 py-2">
                <p className="whitespace-pre-wrap break-words text-xs leading-6 text-foreground/90">
                  {source.chunkSummary || source.snippet || "-"}
                </p>
              </div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
