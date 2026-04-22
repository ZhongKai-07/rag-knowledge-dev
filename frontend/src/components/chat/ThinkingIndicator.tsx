import { Brain, Loader2 } from "lucide-react";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
}

export function ThinkingIndicator({ content, duration }: ThinkingIndicatorProps) {
  return (
    <div className="rounded-xl border border-vio-accent-subtle bg-[var(--vio-accent-mist)] px-4 py-3">
      <div className="flex items-center gap-2 text-vio-accent">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="font-mono text-xs font-medium uppercase tracking-wider">正在深度思考...</span>
        {duration ? (
          <span className="ml-auto rounded-full bg-vio-accent-subtle px-2 py-0.5 font-mono text-[10px] text-vio-accent">
            {duration}秒
          </span>
        ) : null}
      </div>
      <div className="mt-2 flex items-start gap-2 text-sm text-vio-ink/70">
        <Brain className="mt-0.5 h-4 w-4 shrink-0 text-vio-accent-2" />
        <p className="whitespace-pre-wrap leading-relaxed">
          {content || ""}
          <span className="vio-aurora-cursor ml-1" />
        </p>
      </div>
    </div>
  );
}
