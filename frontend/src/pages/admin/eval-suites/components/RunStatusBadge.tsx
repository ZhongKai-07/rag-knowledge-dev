import { cn } from "@/lib/utils";
import type { RunStatus } from "@/services/evalSuiteService";

const STATUS_CONFIG: Record<RunStatus, { style: string; label: string }> = {
  PENDING:         { style: "bg-slate-100 text-slate-600",       label: "排队中" },
  RUNNING:         { style: "bg-blue-100 text-blue-700",         label: "运行中" },
  SUCCESS:         { style: "bg-emerald-100 text-emerald-700",   label: "全部成功" },
  PARTIAL_SUCCESS: { style: "bg-amber-100 text-amber-700",       label: "部分成功" },
  FAILED:          { style: "bg-rose-100 text-rose-700",         label: "全部失败" },
  CANCELLED:       { style: "bg-slate-200 text-slate-700",       label: "已取消" }
};

interface Props {
  status: RunStatus;
}

export function RunStatusBadge({ status }: Props) {
  const cfg = STATUS_CONFIG[status];
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
        cfg.style
      )}
    >
      {cfg.label}
    </span>
  );
}
