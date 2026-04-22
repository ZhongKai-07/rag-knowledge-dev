import { ShieldCheck } from "lucide-react";
import { useAccessScope } from "../hooks/useAccessScope";

/** 权限中心顶部公用 Banner（4 Tab 共享）：当前身份 + 管理范围。 */
export function AccessBanner() {
  const scope = useAccessScope();
  return (
    <div className="rounded-lg border bg-white p-4">
      <div className="flex items-center gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-[var(--vio-accent-mist)] text-vio-accent">
          <ShieldCheck className="h-5 w-5" />
        </div>
        <div className="flex-1">
          <h1 className="text-lg font-semibold text-slate-900">权限中心</h1>
          <p className="mt-0.5 text-xs text-slate-500">
            当前身份：
            <span className="font-medium text-slate-700">{scope.identityLabel}</span>
            <span className="mx-2 text-slate-300">·</span>
            管理范围：
            <span className="font-medium text-slate-700">{scope.scopeLabel}</span>
          </p>
        </div>
      </div>
    </div>
  );
}
