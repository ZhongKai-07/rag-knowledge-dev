import { useEffect, useRef, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Download,
  FileText,
  Filter,
  Loader2,
  RefreshCw,
  Search,
  ClipboardList,
  MessageSquareText,
  DatabaseZap
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import {
  getEvaluationRecords,
  exportForRagas,
  type PageResult,
  type RagEvaluationRecord
} from "@/services/ragEvaluationService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

// ============ 工具函数 ============

function formatDateTime(dateStr?: string | null): string {
  if (!dateStr) return "-";
  try {
    return new Date(dateStr).toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    });
  } catch {
    return dateStr;
  }
}

function truncate(text: string | null | undefined, maxLen: number): string {
  if (!text) return "-";
  return text.length > maxLen ? text.slice(0, maxLen) + "..." : text;
}

type ChunkItem = { id?: string; text?: string; score?: number };

function parseChunks(json: string | null | undefined): ChunkItem[] {
  if (!json) return [];
  try {
    return JSON.parse(json);
  } catch {
    return [];
  }
}

function parseSubQuestions(json: string | null | undefined): string[] {
  if (!json) return [];
  try {
    return JSON.parse(json);
  } catch {
    return [];
  }
}

// ============ 详情展开组件 ============

function DetailSection({
  icon: Icon,
  title,
  children,
  defaultOpen = false
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  children: React.ReactNode;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="border border-slate-200 rounded-lg overflow-hidden">
      <button
        className="w-full flex items-center gap-2 px-4 py-2.5 bg-slate-50 hover:bg-slate-100 transition-colors text-left"
        onClick={() => setOpen(!open)}
      >
        {open ? (
          <ChevronDown className="h-4 w-4 text-slate-400" />
        ) : (
          <ChevronRight className="h-4 w-4 text-slate-400" />
        )}
        <Icon className="h-4 w-4 text-slate-500" />
        <span className="text-sm font-medium text-slate-700">{title}</span>
      </button>
      {open && <div className="px-4 py-3 bg-white">{children}</div>}
    </div>
  );
}

function RecordDetail({ record }: { record: RagEvaluationRecord }) {
  const chunks = parseChunks(record.retrievedChunks);
  const subQuestions = parseSubQuestions(record.subQuestions);

  return (
    <div className="space-y-3 py-2">
      {/* Query */}
      <DetailSection icon={Search} title="查询" defaultOpen>
        <div className="space-y-2 text-sm">
          <div>
            <span className="text-slate-500">原始查询：</span>
            <span className="text-slate-800">{record.originalQuery || "-"}</span>
          </div>
          {record.rewrittenQuery && record.rewrittenQuery !== record.originalQuery && (
            <div>
              <span className="text-slate-500">改写查询：</span>
              <span className="text-slate-800">{record.rewrittenQuery}</span>
            </div>
          )}
          {subQuestions.length > 0 && (
            <div>
              <span className="text-slate-500">子问题：</span>
              <ul className="list-disc list-inside ml-2 mt-1 text-slate-700">
                {subQuestions.map((q, i) => (
                  <li key={i}>{q}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </DetailSection>

      {/* Chunks */}
      <DetailSection icon={DatabaseZap} title={`检索分块 (${chunks.length})`}>
        {chunks.length === 0 ? (
          <p className="text-sm text-slate-400">无检索分块</p>
        ) : (
          <div className="space-y-2 max-h-[400px] overflow-y-auto">
            {chunks.map((chunk, i) => (
              <div
                key={chunk.id || i}
                className="p-3 bg-slate-50 rounded-lg border border-slate-100"
              >
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-xs font-mono text-slate-400">#{i + 1}</span>
                  {chunk.score != null && (
                    <Badge variant="secondary" className="text-xs">
                      score: {chunk.score.toFixed(4)}
                    </Badge>
                  )}
                </div>
                <p className="text-sm text-slate-700 whitespace-pre-wrap break-words leading-relaxed">
                  {chunk.text || "-"}
                </p>
              </div>
            ))}
          </div>
        )}
      </DetailSection>

      {/* Answer */}
      <DetailSection icon={MessageSquareText} title="模型回答" defaultOpen>
        <div className="text-sm text-slate-800 whitespace-pre-wrap break-words leading-relaxed max-h-[300px] overflow-y-auto">
          {record.answer || "-"}
        </div>
      </DetailSection>

      {/* Meta */}
      <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-slate-400 px-1">
        {record.traceId && <span>Trace: {record.traceId}</span>}
        {record.conversationId && <span>会话: {record.conversationId}</span>}
        {record.modelName && <span>模型: {record.modelName}</span>}
        {record.retrievalTopK != null && <span>TopK: {record.retrievalTopK}</span>}
      </div>
    </div>
  );
}

// ============ 主组件 ============

export function RagEvaluationPage() {
  const requestRef = useRef(0);
  const [pageNo, setPageNo] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [pageData, setPageData] = useState<PageResult<RagEvaluationRecord> | null>(null);
  const [loading, setLoading] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  const records = pageData?.records || [];

  const loadData = async (current = pageNo, status = statusFilter) => {
    const requestId = ++requestRef.current;
    setLoading(true);
    try {
      const result = await getEvaluationRecords({
        current,
        size: PAGE_SIZE,
        evalStatus: status || undefined
      });
      if (requestRef.current !== requestId) return;
      setPageData(result);
    } catch (error) {
      if (requestRef.current !== requestId) return;
      toast.error(getErrorMessage(error, "加载评测记录失败"));
    } finally {
      if (requestRef.current === requestId) setLoading(false);
    }
  };

  useEffect(() => {
    loadData(1, statusFilter);
  }, [statusFilter]);

  const handlePageChange = (newPage: number) => {
    setPageNo(newPage);
    setExpandedId(null);
    loadData(newPage);
  };

  const handleExport = async () => {
    setExporting(true);
    try {
      const data = await exportForRagas(statusFilter || undefined, 500);
      const blob = new Blob([JSON.stringify(data, null, 2)], {
        type: "application/json"
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `ragas-export-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      toast.success(`已导出 ${data.length} 条评测记录`);
    } catch (error) {
      toast.error(getErrorMessage(error, "导出失败"));
    } finally {
      setExporting(false);
    }
  };

  const statusOptions = [
    { value: "", label: "全部状态" },
    { value: "PENDING", label: "待评测" },
    { value: "COMPLETED", label: "已完成" }
  ];

  const totalRecords = pageData?.total ?? 0;
  const totalPages = pageData?.pages ?? 0;

  return (
    <div className="space-y-5">
      {/* 页头 */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">RAG 评测记录</h1>
          <p className="text-sm text-slate-500 mt-1">
            查看 Query → Chunks → Answer 完整链路，导出 RAGAS 兼容格式进行评测
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setPageNo(1);
              loadData(1);
            }}
            disabled={loading}
          >
            <RefreshCw className={cn("mr-1.5 h-4 w-4", loading && "animate-spin")} />
            刷新
          </Button>
          <Button
            size="sm"
            onClick={handleExport}
            disabled={exporting || totalRecords === 0}
          >
            <Download className={cn("mr-1.5 h-4 w-4", exporting && "animate-spin")} />
            导出 RAGAS JSON
          </Button>
        </div>
      </div>

      {/* 统计 + 筛选 */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2 text-sm text-slate-600">
            <ClipboardList className="h-4 w-4" />
            <span>共 <strong>{totalRecords}</strong> 条记录</span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Filter className="h-4 w-4 text-slate-400" />
          {statusOptions.map((opt) => (
            <Button
              key={opt.value}
              variant={statusFilter === opt.value ? "default" : "outline"}
              size="sm"
              className="text-xs"
              onClick={() => {
                setStatusFilter(opt.value);
                setPageNo(1);
              }}
            >
              {opt.label}
            </Button>
          ))}
        </div>
      </div>

      {/* 列表 */}
      {loading && records.length === 0 ? (
        <div className="min-h-[300px] flex items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-slate-500">
            <Loader2 className="h-8 w-8 animate-spin" />
            <p>加载中...</p>
          </div>
        </div>
      ) : records.length === 0 ? (
        <div className="min-h-[300px] flex items-center justify-center">
          <div className="text-center text-slate-400">
            <FileText className="h-12 w-12 mx-auto mb-3 opacity-50" />
            <p>暂无评测记录</p>
            <p className="text-xs mt-1">发起对话后，系统将自动记录 Query-Chunk-Answer 数据</p>
          </div>
        </div>
      ) : (
        <div className="space-y-2">
          {records.map((record) => {
            const isExpanded = expandedId === record.id;
            return (
              <Card
                key={record.id}
                className={cn(
                  "transition-shadow",
                  isExpanded && "ring-1 ring-blue-200 shadow-sm"
                )}
              >
                <CardContent className="p-0">
                  {/* 行摘要 */}
                  <button
                    className="w-full flex items-center gap-4 px-4 py-3 text-left hover:bg-slate-50/50 transition-colors"
                    onClick={() => setExpandedId(isExpanded ? null : record.id)}
                  >
                    {isExpanded ? (
                      <ChevronDown className="h-4 w-4 text-slate-400 shrink-0" />
                    ) : (
                      <ChevronRight className="h-4 w-4 text-slate-400 shrink-0" />
                    )}

                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-800 truncate">
                        {record.originalQuery || "无查询内容"}
                      </p>
                      <p className="text-xs text-slate-400 mt-0.5 truncate">
                        {truncate(record.answer, 100)}
                      </p>
                    </div>

                    <div className="flex items-center gap-3 shrink-0">
                      <Badge
                        variant={record.evalStatus === "COMPLETED" ? "default" : "secondary"}
                        className="text-xs"
                      >
                        {record.evalStatus === "COMPLETED" ? "已评测" : "待评测"}
                      </Badge>
                      <span className="text-xs text-slate-400 w-[140px] text-right">
                        {formatDateTime(record.createTime)}
                      </span>
                    </div>
                  </button>

                  {/* 详情展开 */}
                  {isExpanded && (
                    <div className="border-t border-slate-100 px-4 pb-4">
                      <RecordDetail record={record} />
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-4 text-sm text-slate-500">
          <span>
            第 {pageNo} / {totalPages} 页，共 {totalRecords} 条
          </span>
          <div className="flex gap-1">
            <Button
              variant="outline"
              size="sm"
              disabled={pageNo <= 1}
              onClick={() => handlePageChange(pageNo - 1)}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={pageNo >= totalPages}
              onClick={() => handlePageChange(pageNo + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
