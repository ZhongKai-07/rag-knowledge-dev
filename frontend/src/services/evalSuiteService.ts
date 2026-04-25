import { api } from "@/services/api";

export interface GoldDataset {
  id: string;
  kbId: string;
  name: string;
  description?: string | null;
  status: "DRAFT" | "ACTIVE" | "ARCHIVED";
  itemCount: number;
  pendingItemCount: number;
  totalItemCount: number;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface GoldItem {
  id: string;
  datasetId: string;
  question: string;
  groundTruthAnswer: string;
  sourceChunkId?: string | null;
  sourceChunkText: string;
  sourceDocId?: string | null;
  sourceDocName?: string | null;
  reviewStatus: "PENDING" | "APPROVED" | "REJECTED";
  reviewNote?: string | null;
  synthesizedByModel?: string | null;
}

export interface SynthesisProgress {
  status: "IDLE" | "RUNNING" | "COMPLETED" | "FAILED";
  total: number;
  processed: number;
  failed: number;
  error?: string | null;
}

export async function listGoldDatasets(kbId?: string, status?: string): Promise<GoldDataset[]> {
  return api.get<GoldDataset[], GoldDataset[]>("/admin/eval/gold-datasets", {
    params: { kbId: kbId || undefined, status: status || undefined }
  });
}

export async function getGoldDataset(id: string): Promise<GoldDataset> {
  return api.get<GoldDataset, GoldDataset>(`/admin/eval/gold-datasets/${id}`);
}

export async function createGoldDataset(body: {
  kbId: string;
  name: string;
  description?: string;
}): Promise<string> {
  return api.post<string, string>("/admin/eval/gold-datasets", body);
}

export async function triggerSynthesis(id: string, count: number): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-datasets/${id}/synthesize`, { count });
}

export async function getSynthesisProgress(id: string): Promise<SynthesisProgress> {
  return api.get<SynthesisProgress, SynthesisProgress>(
    `/admin/eval/gold-datasets/${id}/synthesis-progress`
  );
}

export async function activateGoldDataset(id: string): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-datasets/${id}/activate`);
}

export async function archiveGoldDataset(id: string): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-datasets/${id}/archive`);
}

export async function deleteGoldDataset(id: string): Promise<void> {
  await api.delete<void, void>(`/admin/eval/gold-datasets/${id}`);
}

export async function listGoldItems(datasetId: string, reviewStatus?: string): Promise<GoldItem[]> {
  return api.get<GoldItem[], GoldItem[]>(`/admin/eval/gold-datasets/${datasetId}/items`, {
    params: { reviewStatus: reviewStatus || undefined }
  });
}

export async function reviewGoldItem(
  itemId: string,
  action: "APPROVE" | "REJECT",
  note?: string
): Promise<void> {
  await api.post<void, void>(`/admin/eval/gold-items/${itemId}/review`, { action, note });
}

export async function editGoldItem(
  itemId: string,
  body: { question?: string; groundTruthAnswer?: string }
): Promise<void> {
  await api.put<void, void>(`/admin/eval/gold-items/${itemId}`, body);
}
