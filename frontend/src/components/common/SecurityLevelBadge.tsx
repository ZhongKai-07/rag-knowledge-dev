export const SECURITY_LEVEL_LABELS = ["公开", "内部", "机密", "绝密"] as const;

const COLORS = [
  "bg-green-100 text-green-800",
  "bg-blue-100 text-blue-800",
  "bg-orange-100 text-orange-800",
  "bg-red-100 text-red-800",
];

interface Props {
  level: number;
  showLevel?: boolean;
}

export function SecurityLevelBadge({ level, showLevel = false }: Props) {
  const label = SECURITY_LEVEL_LABELS[level] ?? String(level);
  const color = COLORS[level] ?? COLORS[0];
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${color}`}>
      {showLevel ? `${level} · ${label}` : label}
    </span>
  );
}
