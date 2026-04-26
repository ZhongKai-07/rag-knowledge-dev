/** Shared helpers for the eval-suites pages. */

export function parseMetricsSummary(
  raw?: string | null
): Record<string, number | null> {
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

export function fmt(n: number | null | undefined): string {
  return n == null ? "—" : n.toFixed(3);
}

// 用户从侧栏直进 /admin/eval-suites?tab=runs 时 URL 没 datasetId，
// 这个 fallback 让上次用过的 datasetId 跨刷新 / 跨 tab 复用
const STORAGE_KEY = "eval-suites:lastDatasetId";

export function readStoredDatasetId(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? "";
  } catch {
    return "";
  }
}

export function writeStoredDatasetId(v: string): void {
  try {
    if (v) localStorage.setItem(STORAGE_KEY, v);
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    // localStorage 不可用（隐身 / 配额）忽略，URL 仍 source of truth
  }
}
