import { useMemo } from "react";

interface Props {
  before?: string | null;
  after?: string | null;
}

export function SnapshotDiffViewer({ before, after }: Props) {
  const diff = useMemo(() => {
    let a: Record<string, unknown> = {};
    let b: Record<string, unknown> = {};
    try {
      if (before) a = JSON.parse(before);
    } catch {
      // ignore
    }
    try {
      if (after) b = JSON.parse(after);
    } catch {
      // ignore
    }
    const keys = Array.from(new Set([...Object.keys(a), ...Object.keys(b)])).sort();
    return keys.map((k) => ({
      key: k,
      a: a[k],
      b: b[k],
      changed: JSON.stringify(a[k]) !== JSON.stringify(b[k])
    }));
  }, [before, after]);

  return (
    <div className="rounded-lg border bg-white">
      <table className="w-full text-xs">
        <thead className="bg-slate-50 text-left">
          <tr>
            <th className="px-2 py-1">字段</th>
            <th className="px-2 py-1">前一次</th>
            <th className="px-2 py-1">本次</th>
          </tr>
        </thead>
        <tbody>
          {diff.map((d) => (
            <tr key={d.key} className={d.changed ? "bg-amber-50" : ""}>
              <td className="px-2 py-1 font-mono">{d.key}</td>
              <td className="px-2 py-1 font-mono">{JSON.stringify(d.a)}</td>
              <td className="px-2 py-1 font-mono">{JSON.stringify(d.b)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
