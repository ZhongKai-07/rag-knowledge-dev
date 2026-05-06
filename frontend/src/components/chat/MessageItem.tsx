import * as React from "react";
import { Brain, ChevronDown, RotateCcw, Sparkles } from "lucide-react";

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
  const regenerateLastAssistantMessage = useChatStore(
    (s) => s.regenerateLastAssistantMessage
  );
  const isStreaming = useChatStore((s) => s.isStreaming);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !isThinking && !hasContent;

  // --- citation interaction (hooks must precede early return) ---
  const sourcesRef = React.useRef<HTMLDivElement>(null);
  const contentRef = React.useRef<HTMLDivElement>(null);
  const [highlightedIndex, setHighlightedIndex] = React.useState<number | null>(null);
  const timerRef = React.useRef<number | null>(null);

  const scheduleClearHighlight = React.useCallback((n: number) => {
    setHighlightedIndex(n);
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => {
      setHighlightedIndex(null);
      timerRef.current = null;
    }, CITATION_HIGHLIGHT_MS);
  }, []);

  const handleCitationClick = React.useCallback(
    (n: number) => {
      if (!message.sources?.some((c) => c.index === n)) return;
      sourcesRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      scheduleClearHighlight(n);
    },
    [message.sources, scheduleClearHighlight]
  );

  const handleJumpToCite = React.useCallback(
    (n: number) => {
      if (!message.sources?.some((c) => c.index === n)) return;
      // 限定在当前 assistant 消息的正文容器内查找（避免跨消息匹配）
      const target = contentRef.current?.querySelector<HTMLElement>(
        `[data-cite-n="${n}"]`
      );
      target?.scrollIntoView({ behavior: "smooth", block: "center" });
      scheduleClearHighlight(n);
    },
    [message.sources, scheduleClearHighlight]
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
      <div className="flex">
        <div className="user-message">
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
          <div className="overflow-hidden rounded-lg border border-[#BFDBFE] bg-[#DBEAFE]">
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[#BFDBFE]/30"
            >
              <div className="flex flex-1 items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#BFDBFE]">
                  <Brain className="h-4 w-4 text-[#2563EB]" />
                </div>
                <span className="text-sm font-medium text-[#2563EB]">深度思考</span>
                {thinkingDuration ? (
                  <span className="rounded-full bg-[#BFDBFE] px-2 py-0.5 text-xs text-[#2563EB]">
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-[#3B82F6] transition-transform",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div className="border-t border-[#BFDBFE] px-4 pb-4">
                <div className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-[#1E40AF]">
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
        <div className="space-y-2">
          {!isThinking ? (
            <div className="flex items-center gap-2">
              <div className="flex h-6 w-6 items-center justify-center rounded-md bg-[#F0F7FF] dark:bg-[#1E3A8A]">
                <Sparkles className="h-3.5 w-3.5 text-[#3B82F6] dark:text-[#60A5FA]" />
              </div>
              <span className="text-xs font-medium text-muted-foreground">
                HT KnowledgeBase
              </span>
            </div>
          ) : null}
          {isWaiting ? (
            <div className="ai-wait" aria-label={message.streamStatus?.text || "思考中"}>
              {message.streamStatus?.text ? (
                <span className="text-sm text-muted-foreground">
                  {message.streamStatus.text}
                </span>
              ) : null}
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {hasContent ? (
            <div
              ref={contentRef}
              className={cn(message.status === "streaming" && "ai-streaming")}
            >
              <MarkdownRenderer
                content={message.content}
                sources={message.sources}
                onCitationClick={handleCitationClick}
                isStreaming={message.status === "streaming"}
              />
            </div>
          ) : null}
          {hasSources && (
            <Sources
              ref={sourcesRef}
              cards={message.sources!}
              highlightedIndex={highlightedIndex}
              onJumpToCite={handleJumpToCite}
            />
          )}
          {message.status === "error" ? (
            <p className="text-xs text-rose-500">生成已中断。</p>
          ) : null}
          {showFeedback ? (
            <div className="flex items-center gap-1">
              <FeedbackButtons
                messageId={message.id}
                feedback={message.feedback ?? null}
                content={message.content}
                alwaysVisible={Boolean(isLast)}
              />
              {isLast && !isStreaming ? (
                <button
                  type="button"
                  onClick={() => {
                    regenerateLastAssistantMessage().catch(() => null);
                  }}
                  aria-label="重新生成"
                  title="重新生成"
                  className="flex h-8 w-8 items-center justify-center rounded text-[#999999] transition-colors hover:bg-[#F5F5F5] hover:text-[#666666]"
                >
                  <RotateCcw className="h-4 w-4" />
                </button>
              ) : null}
            </div>
          ) : null}
          {message.role === "assistant" &&
            message.suggestedQuestions &&
            message.suggestedQuestions.length > 0 && (
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <span className="text-xs text-muted-foreground">可能想问：</span>
                {message.suggestedQuestions.map((q, idx) => (
                  <button
                    key={idx}
                    type="button"
                    onClick={() => sendMessage(q)}
                    className="rounded-full border border-border bg-background px-3 py-1 text-xs transition-colors hover:bg-accent hover:text-accent-foreground"
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
