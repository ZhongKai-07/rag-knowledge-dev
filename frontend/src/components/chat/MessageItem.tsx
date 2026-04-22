import * as React from "react";
import { Brain, ChevronDown } from "lucide-react";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { Sources } from "@/components/chat/Sources";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { CITATION_HIGHLIGHT_MS } from "@/utils/citationAst";
import type { Message } from "@/types";

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
}

export const MessageItem = React.memo(function MessageItem({ message, isLast }: MessageItemProps) {
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const isThinking = Boolean(message.isThinking);
  const [thinkingExpanded, setThinkingExpanded] = React.useState(false);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !isThinking && !hasContent;

  // --- citation interaction (hooks must precede early return) ---
  const sourcesRef = React.useRef<HTMLDivElement>(null);
  const [highlightedIndex, setHighlightedIndex] = React.useState<number | null>(null);
  const timerRef = React.useRef<number | null>(null);

  const handleCitationClick = React.useCallback(
    (n: number) => {
      if (!message.sources?.some((c) => c.index === n)) return;
      sourcesRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      setHighlightedIndex(n);
      if (timerRef.current != null) window.clearTimeout(timerRef.current);
      timerRef.current = window.setTimeout(() => {
        setHighlightedIndex(null);
        timerRef.current = null;
      }, CITATION_HIGHLIGHT_MS);
    },
    [message.sources]
  );

  React.useEffect(
    () => () => {
      if (timerRef.current != null) window.clearTimeout(timerRef.current);
    },
    []
  );

  const hasSources =
    message.role === "assistant" &&
    Array.isArray(message.sources) &&
    message.sources.length > 0;
  // --- end citation interaction ---

  if (isUser) {
    return (
      <div className="flex justify-end">
        <div
          className={cn(
            "ml-auto max-w-[75%] rounded-[14px] rounded-br-[4px]",
            "border border-vio-accent-subtle bg-[var(--vio-accent-mist)]",
            "px-3.5 py-2.5 font-body text-sm text-vio-ink",
          )}
        >
          <p className="whitespace-pre-wrap break-words">{message.content}</p>
        </div>
      </div>
    );
  }

  const thinkingDuration = message.thinkingDuration ? `${message.thinkingDuration}秒` : "";
  return (
    <div className="group flex">
      <div className="min-w-0 flex-1 space-y-4">
        {isThinking ? (
          <ThinkingIndicator content={message.thinking} duration={message.thinkingDuration} />
        ) : null}
        {!isThinking && hasThinking ? (
          <div className="overflow-hidden rounded-lg border border-vio-accent-subtle bg-[var(--vio-accent-mist)]">
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-vio-accent-subtle/30"
            >
              <div className="flex flex-1 items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-vio-accent-subtle">
                  <Brain className="h-4 w-4 text-vio-accent" />
                </div>
                <span className="text-sm font-medium text-vio-accent">深度思考</span>
                {thinkingDuration ? (
                  <span className="rounded-full bg-vio-accent-subtle px-2 py-0.5 text-xs text-vio-accent">
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-vio-accent-2 transition-transform",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div className="border-t border-vio-accent-subtle px-4 pb-4">
                <div className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-vio-ink/80">
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
        <div className="space-y-2">
          {isWaiting ? (
            <div className="ai-wait" aria-label="思考中">
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {hasContent ? (
            <MarkdownRenderer
              content={message.content}
              sources={message.sources}
              onCitationClick={handleCitationClick}
            />
          ) : null}
          {hasSources && (
            <Sources
              ref={sourcesRef}
              cards={message.sources!}
              highlightedIndex={highlightedIndex}
            />
          )}
          {message.status === "error" ? (
            <p className="text-xs text-rose-500">生成已中断。</p>
          ) : null}
          {showFeedback ? (
            <FeedbackButtons
              messageId={message.id}
              feedback={message.feedback ?? null}
              content={message.content}
              alwaysVisible={Boolean(isLast)}
            />
          ) : null}
          {message.role === "assistant" &&
            message.suggestedQuestions &&
            message.suggestedQuestions.length > 0 && (
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <span className="text-xs text-vio-ink/50">可能想问：</span>
                {message.suggestedQuestions.map((q, idx) => (
                  <button
                    key={idx}
                    type="button"
                    onClick={() => sendMessage(q)}
                    className="rounded-full border border-vio-line bg-white px-3 py-1 text-xs transition-colors hover:border-vio-accent-subtle hover:bg-[var(--vio-accent-mist)] hover:text-vio-accent"
                  >
                    {q}
                  </button>
                ))}
              </div>
            )}
        </div>
      </div>
    </div>
  );
});
