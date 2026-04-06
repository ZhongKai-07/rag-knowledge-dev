import { api } from "@/services/api";

export interface RagEvaluationRecord {
  id: string;
  traceId?: string | null;
  conversationId?: string | null;
  messageId?: string | null;
  userId?: string | null;
  originalQuery?: string | null;
  rewrittenQuery?: string | null;
  subQuestions?: string | null;
  retrievedChunks?: string | null;
  retrievalTopK?: number | null;
  answer?: string | null;
  modelName?: string | null;
  intentResults?: string | null;
  evalStatus?: string | null;
  evalMetrics?: string | null;
  createTime?: string | null;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface RagasExportItem {
  question: string;
  contexts: string[];
  answer: string;
  groundTruths: string[];
}

export async function getEvaluationRecords(
  query: { current?: number; size?: number; evalStatus?: string } = {}
): Promise<PageResult<RagEvaluationRecord>> {
  return api.get<PageResult<RagEvaluationRecord>, PageResult<RagEvaluationRecord>>(
    "/rag/evaluations",
    {
      params: {
        current: query.current ?? 1,
        size: query.size ?? 10,
        evalStatus: query.evalStatus || undefined
      }
    }
  );
}

export async function getEvaluationDetail(id: string): Promise<RagEvaluationRecord> {
  return api.get<RagEvaluationRecord, RagEvaluationRecord>(`/rag/evaluations/${id}`);
}

export async function exportForRagas(
  evalStatus?: string,
  limit = 100
): Promise<RagasExportItem[]> {
  return api.get<RagasExportItem[], RagasExportItem[]>("/rag/evaluations/export", {
    params: { evalStatus: evalStatus || undefined, limit }
  });
}
