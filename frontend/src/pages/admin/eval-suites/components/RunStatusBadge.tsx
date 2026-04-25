import { cn } from "@/lib/utils";
import type { RunStatus } from "@/services/evalSuiteService";

const STATUS_STYLES: Record<RunStatus, string> = {
  PENDING: "bg-slate-100 text-slate-600",
  RUNNING: "bg-blue-100 text-blue-700",
  SUCCESS: "bg-emerald-100 text-emerald-700",
  PARTIAL_SUCCESS: "bg-amber-100 text-amber-700",
  FAILED: "bg-rose-100 text-rose-700",
  CANCELLED: "bg-slate-200 text-slate-700"
};

const STATUS_LABEL: Record<RunStatus, string> = {
  PENDING: "排队中",
  RUNNING: "运行中",
  SUCCESS: "全部成功",
  PARTIAL_SUCCESS: "部分成功",
  FAILED: "全部失败",
  CANCELLED: "已取消"
};

interface Props {
  status: RunStatus;
}

export function RunStatusBadge({ status }: Props) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
        STATUS_STYLES[status]
      )}
    >
      {STATUS_LABEL[status]}
    </span>
  );
}
