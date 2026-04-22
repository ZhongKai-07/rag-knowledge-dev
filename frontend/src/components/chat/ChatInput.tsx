import * as React from "react";
import { BookOpen, Brain, ChevronDown, Lightbulb, Send, Square } from "lucide-react";

import { Textarea } from "@/components/ui/textarea";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";

export function ChatInput() {
  const [value, setValue] = React.useState("");

  const [knowledgeBases, setKnowledgeBases] = React.useState<KnowledgeBase[]>([]);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    deepThinkingEnabled,
    setDeepThinkingEnabled,
    selectedKnowledgeBaseId,
    setSelectedKnowledgeBase,
    activeKbId,
    activeKbName,
    inputFocusKey
  } = useChatStore();

  React.useEffect(() => {
    getKnowledgeBases().then(setKnowledgeBases).catch(console.error);
  }, []);

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  React.useEffect(() => {
    if (!inputFocusKey) return;
    focusInput();
  }, [inputFocusKey, focusInput]);

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className="space-y-4">
      <div
        className={cn(
          "relative flex flex-col rounded-2xl border bg-white px-4 pt-3 pb-2 transition-all duration-200",
          "border-vio-line hover:border-vio-accent-subtle",
          "focus-within:border-vio-accent focus-within:shadow-halo",
          "focus-within:ring-2 focus-within:ring-vio-accent-subtle",
        )}
      >
        <div className="relative">
          <Textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder={deepThinkingEnabled ? "输入需要深度分析的问题..." : "输入你的问题..."}
            className="max-h-40 min-h-[44px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 pr-2 text-[15px] text-ink-2 shadow-none placeholder:text-ink-4 focus-visible:ring-0"
            rows={1}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={() => {
              isComposingRef.current = false;
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                const nativeEvent = event.nativeEvent as KeyboardEvent;
                if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                  return;
                }
                event.preventDefault();
                handleSubmit();
              }
            }}
            aria-label="聊天输入框"
          />
          <div className="pointer-events-none absolute bottom-0 left-0 right-0 h-[10px] bg-gradient-to-b from-surface/0 via-surface/40 to-surface/90" />
        </div>
        <div className="relative mt-2 flex items-center gap-2">
          <button
            type="button"
            onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
            disabled={isStreaming}
            aria-pressed={deepThinkingEnabled}
            className={cn(
              "inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] font-medium",
              "transition-colors",
              deepThinkingEnabled
                ? "border-vio-accent-subtle bg-[var(--vio-accent-mist)] text-vio-accent"
                : "border-vio-line bg-transparent text-vio-ink/60 hover:bg-[var(--vio-accent-mist)] hover:text-vio-accent",
              isStreaming && "cursor-not-allowed opacity-60"
            )}
          >
            <span className="inline-flex items-center gap-2">
              <Brain className={cn("h-3.5 w-3.5", deepThinkingEnabled && "text-brand-muted")} />
              深度思考
              {deepThinkingEnabled ? (
                <span className="h-2 w-2 rounded-full bg-brand-muted animate-pulse" />
              ) : null}
            </span>
          </button>
          {activeKbId ? (
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-brand-faint bg-brand-subtle px-3 py-1.5 text-xs font-medium text-brand">
              <BookOpen className="h-3.5 w-3.5" />
              {activeKbName || "知识库"}
            </span>
          ) : knowledgeBases.length > 0 ? (
            <DropdownMenu>
              <DropdownMenuTrigger asChild disabled={isStreaming}>
                <button
                  type="button"
                  className={cn(
                    "inline-flex cursor-pointer items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-ring",
                    selectedKnowledgeBaseId
                      ? "border-brand-faint bg-brand-subtle text-brand"
                      : "border-transparent bg-surface-3 text-ink-4 hover:bg-surface-4",
                    isStreaming && "cursor-not-allowed opacity-60"
                  )}
                  aria-label="选择知识库"
                >
                  <BookOpen className="h-3.5 w-3.5" />
                  {selectedKnowledgeBaseId
                    ? (knowledgeBases.find((kb) => kb.id === selectedKnowledgeBaseId)?.name ?? "知识库")
                    : "自动"}
                  <ChevronDown className="h-3 w-3 opacity-60" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="min-w-[160px]">
                <DropdownMenuItem
                  onClick={() => setSelectedKnowledgeBase(null)}
                  className={cn(!selectedKnowledgeBaseId && "font-medium text-brand")}
                >
                  自动
                </DropdownMenuItem>
                {knowledgeBases.map((kb) => (
                  <DropdownMenuItem
                    key={kb.id}
                    onClick={() => setSelectedKnowledgeBase(kb.id)}
                    className={cn(selectedKnowledgeBaseId === kb.id && "font-medium text-brand")}
                  >
                    {kb.name}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          ) : null}
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!hasContent && !isStreaming}
            aria-label={isStreaming ? "停止生成" : "发送消息"}
            className={cn(
              "ml-auto rounded-full p-2.5 transition-all duration-200",
              isStreaming
                ? "bg-danger-subtle text-danger hover:bg-danger-border"
                : hasContent
                  ? "bg-gradient-to-br from-[var(--vio-accent)] to-[var(--vio-accent-2)] text-white shadow-sm hover:shadow-halo"
                  : "cursor-not-allowed bg-surface-3 text-ink-5"
            )}
          >
            {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
          </button>
        </div>
      </div>
      {deepThinkingEnabled ? (
        <p className="text-xs text-brand">
          <span className="inline-flex items-center gap-1.5">
            <Lightbulb className="h-3.5 w-3.5" />
            深度思考模式已开启，AI将进行更深入的分析推理
          </span>
        </p>
      ) : null}
      <p className="hidden text-center text-xs text-ink-4 md:block">
        <kbd className="rounded bg-surface-3 px-1.5 py-0.5 text-ink-3">Enter</kbd> 发送
        <span className="px-1.5">·</span>
        <kbd className="rounded bg-surface-3 px-1.5 py-0.5 text-ink-3">
          Shift + Enter
        </kbd>{" "}
        换行
        {isStreaming ? <span className="ml-2 animate-pulse-soft">生成中...</span> : null}
      </p>
    </div>
  );
}
