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
