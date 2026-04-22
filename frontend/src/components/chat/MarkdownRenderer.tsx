// @ts-nocheck
/* eslint-disable */

import * as React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Check, Copy, ImageIcon } from "lucide-react";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark, oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useThemeStore } from "@/stores/themeStore";
import { remarkCitations } from "@/utils/remarkCitations";
import { CitationBadge } from "@/components/chat/CitationBadge";
import type { SourceCard } from "@/types";

interface MarkdownRendererProps {
  content: string;
  sources?: SourceCard[];
  onCitationClick?: (n: number) => void;
}

// theme 订阅下沉到 CodeRenderer 内部，使 MarkdownRenderer 的 components 对象
// 可以严格按 [hasSources, indexMap, onCitationClick] memo（N-3 / PR5）。
function CodeRenderer({ inline, className, children, node, ...props }: any) {
  const theme = useThemeStore((state) => state.theme);
  const match = /language-(\w+)/.exec(className || "");
  const language = match?.[1] || "text";
  const value = String(children).replace(/\n$/, "");

  if (inline || !value.includes("\n")) {
    return (
      <code
        className={cn(
          "rounded px-1.5 py-0.5 text-[13px] font-mono bg-[#f6f8fa] text-[#24292f]",
          "dark:bg-[#161b22] dark:text-[#c9d1d9]",
          className
        )}
        {...props}
      >
        {children}
      </code>
    );
  }

  return (
    <div className="my-3 overflow-hidden rounded-md border border-[#d0d7de] bg-[#f6f8fa] dark:border-[#30363d] dark:bg-[#161b22]">
      <div className="flex items-center justify-between border-b border-[#d0d7de] bg-[#f6f8fa] px-3 py-1.5 dark:border-[#30363d] dark:bg-[#161b22]">
        <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-[#57606a] dark:text-[#8b949e]">
          {language}
        </span>
        <CopyButton value={value} />
      </div>
      <div className="overflow-x-auto">
        <SyntaxHighlighter
          language={language}
          style={theme === "dark" ? oneDark : oneLight}
          PreTag="div"
          customStyle={{
            margin: 0,
            padding: "0.75rem 1rem",
            background: "transparent",
            fontSize: "13px",
            lineHeight: "1.5"
          }}
          showLineNumbers={false}
          wrapLines={true}
        >
          {value}
        </SyntaxHighlighter>
      </div>
    </div>
  );
}

export function MarkdownRenderer({ content, sources, onCitationClick }: MarkdownRendererProps) {
  const hasSources = Array.isArray(sources) && sources.length > 0;

  const indexMap = React.useMemo(
    () => new Map((sources ?? []).map((c) => [c.index, c])),
    [sources]
  );

  const remarkPlugins = React.useMemo(
    () => (hasSources ? [remarkGfm, remarkCitations] : [remarkGfm]),
    [hasSources]
  );

  const components = React.useMemo(
    () => ({
      code: CodeRenderer,
      img({ src, alt, ...props }: any) {
        const [hasError, setHasError] = React.useState(false);

        if (hasError) {
          return (
            <div className="my-3 flex items-center gap-2 text-sm text-[#999999]">
              <ImageIcon className="h-4 w-4" />
              <span>图片加载失败</span>
            </div>
          );
        }

        return (
          <img
            src={src}
            alt=""
            className="my-3 max-w-full rounded-lg"
            onError={() => setHasError(true)}
            loading="lazy"
            {...props}
          />
        );
      },
      a({ children, ...props }: any) {
        return (
          <a
            className="text-vio-accent underline-offset-4 hover:underline hover:text-vio-accent-2"
            target="_blank"
            rel="noreferrer"
            {...props}
          >
            {children}
          </a>
        );
      },
      table({ children, ...props }: any) {
        return (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse border border-[#d0d7de] rounded-md dark:border-[#30363d]" {...props}>
              {children}
            </table>
          </div>
        );
      },
      thead({ children, ...props }: any) {
        return (
          <thead className="bg-[#f6f8fa] dark:bg-[#161b22]" {...props}>
            {children}
          </thead>
        );
      },
      th({ children, ...props }: any) {
        return (
          <th className="border-b border-[#d0d7de] border-r border-r-[#d0d7de] px-3 py-2 text-left text-sm font-semibold text-[#24292f] last:border-r-0 dark:border-[#30363d] dark:border-r-[#30363d] dark:text-[#c9d1d9]" {...props}>
            {children}
          </th>
        );
      },
      td({ children, ...props }: any) {
        return (
          <td className="border-b border-[#d0d7de] border-r border-r-[#d0d7de] px-3 py-2.5 text-sm text-[#24292f] last:border-r-0 dark:border-[#30363d] dark:border-r-[#30363d] dark:text-[#c9d1d9]" {...props}>
            {children}
          </td>
        );
      },
      h1: ({ children, ...props }: any) => (
        <h1
          {...props}
          className="font-display text-[30px] font-medium leading-[1.15] tracking-[-0.02em] text-vio-ink mb-3 mt-6 first:mt-0"
        >
          {children}
        </h1>
      ),
      h2: ({ children, ...props }: any) => (
        <h2
          {...props}
          className="font-display text-[22px] font-medium leading-[1.2] tracking-[-0.015em] text-vio-ink mb-2 mt-5 first:mt-0"
        >
          {children}
        </h2>
      ),
      h3: ({ children, ...props }: any) => (
        <h3
          {...props}
          className="font-display text-[17px] font-semibold leading-[1.3] text-vio-ink mb-2 mt-4 first:mt-0"
        >
          {children}
        </h3>
      ),
      strong: ({ children, ...props }: any) => (
        <strong
          {...props}
          className="font-semibold"
          style={{
            background: "linear-gradient(to top, var(--vio-accent-subtle) 35%, transparent 35%)",
            paddingInline: "2px",
          }}
        >
          {children}
        </strong>
      ),
      blockquote: ({ children, ...props }: any) => (
        <blockquote
          {...props}
          className="my-3 rounded-r-[10px] border-l-[3px] border-vio-accent-2 bg-white pl-3 pr-4 py-2 font-body italic text-[13px] leading-[1.55] text-vio-ink/80"
        >
          {children}
        </blockquote>
      ),
      ul({ children, ...props }: any) {
        return (
          <ul className="my-2 ml-6 list-disc space-y-1" {...props}>
            {children}
          </ul>
        );
      },
      ol({ children, ...props }: any) {
        return (
          <ol className="my-2 ml-6 list-decimal space-y-1" {...props}>
            {children}
          </ol>
        );
      },
      ...(hasSources
        ? {
            cite({ node }: any) {
              const raw = node?.properties?.["data-n"] ?? node?.properties?.dataN;
              const n = Number(raw);
              if (!Number.isFinite(n)) return null;
              return (
                <CitationBadge
                  n={n}
                  indexMap={indexMap}
                  onClick={onCitationClick ?? (() => {})}
                />
              );
            },
          }
        : {}),
    }),
    [hasSources, indexMap, onCitationClick]
  );

  return (
    <ReactMarkdown
      remarkPlugins={remarkPlugins}
      components={components}
      className="prose max-w-none prose-p:leading-[1.75] prose-p:text-vio-ink/90 prose-p:text-[14px] prose-li:text-vio-ink/90 prose-li:text-[14px]"
    >
      {content}
    </ReactMarkdown>
  );
}

function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = React.useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={handleCopy}
      aria-label="复制代码"
      className="h-7 w-7 hover:bg-[#eaeef2] dark:hover:bg-[#30363d] transition-colors"
    >
      {copied ? (
        <Check className="h-3.5 w-3.5 text-green-600 dark:text-green-400" />
      ) : (
        <Copy className="h-3.5 w-3.5 text-[#57606a] dark:text-[#8b949e]" />
      )}
    </Button>
  );
}
