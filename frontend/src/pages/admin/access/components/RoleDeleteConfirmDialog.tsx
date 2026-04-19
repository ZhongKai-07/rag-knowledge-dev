import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { getRoleDeletePreview, type RoleDeletePreview } from "@/services/access";
import { getErrorMessage } from "@/utils/error";

interface Props {
  roleId: string | null;
  roleName: string;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => Promise<void> | void;
}

/**
 * P1.5d: 删除角色前显示影响面预览（设计文档 §5.3）。
 * preview 来自 GET /role/{id}/delete-preview (P0.2)。
 */
export function RoleDeleteConfirmDialog({ roleId, roleName, onOpenChange, onConfirm }: Props) {
  const open = roleId !== null;
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<RoleDeletePreview | null>(null);

  useEffect(() => {
    if (!roleId) {
      setPreview(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    getRoleDeletePreview(roleId)
      .then((data) => {
        if (!cancelled) setPreview(data);
      })
      .catch((err) => {
        if (!cancelled) toast.error(getErrorMessage(err, "加载影响面失败"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [roleId]);

  const userCount = preview?.affectedUsers.length ?? 0;
  const kbCount = preview?.affectedKbs.length ?? 0;

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="sm:max-w-[560px]">
        <AlertDialogHeader>
          <AlertDialogTitle className="flex items-center gap-2 text-destructive">
            <AlertTriangle className="h-5 w-5" />
            删除角色「{roleName}」
          </AlertDialogTitle>
        </AlertDialogHeader>

        {loading ? (
          <div className="py-4 text-center text-sm text-slate-400">加载影响面中…</div>
        ) : preview ? (
          <div className="space-y-4 text-sm">
            <section>
              <h4 className="mb-1 font-medium text-slate-700">
                {userCount} 个用户将失去此角色
              </h4>
              {userCount === 0 ? (
                <p className="text-xs text-slate-400">（无）</p>
              ) : (
                <ul className="max-h-[120px] space-y-0.5 overflow-y-auto text-xs text-slate-600">
                  {preview.affectedUsers.map((u) => (
                    <li key={u.userId}>
                      · {u.username}
                      {u.deptName && <span className="text-slate-400"> ({u.deptName})</span>}
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section>
              <h4 className="mb-1 font-medium text-slate-700">
                {kbCount} 个 KB 共享将被解除
              </h4>
              {kbCount === 0 ? (
                <p className="text-xs text-slate-400">（无）</p>
              ) : (
                <ul className="max-h-[120px] space-y-0.5 overflow-y-auto text-xs text-slate-600">
                  {preview.affectedKbs.map((k) => (
                    <li key={k.kbId}>
                      · {k.kbName}
                      {k.deptName && <span className="text-slate-400"> ({k.deptName})</span>}
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {preview.userKbDiff.length > 0 && (
              <section>
                <h4 className="mb-1 font-medium text-slate-700">受影响用户的 KB 访问差集</h4>
                <ul className="max-h-[140px] space-y-1 overflow-y-auto text-xs text-slate-600">
                  {preview.userKbDiff.map((diff) => (
                    <li key={diff.userId}>
                      <span className="font-medium">{diff.username}</span>
                      <span className="text-slate-400">
                        {" "}
                        将失去访问：
                      </span>
                      {diff.lostKbNames.length === 0 ? (
                        <span className="text-slate-400">（无实际损失，其他角色仍覆盖）</span>
                      ) : (
                        diff.lostKbNames.join("、")
                      )}
                    </li>
                  ))}
                </ul>
              </section>
            )}

            <p className="rounded bg-red-50 px-3 py-2 text-xs text-red-700">
              此操作不可恢复。级联删除：<code>t_user_role</code>、<code>t_role_kb_relation</code> 同步失效。
            </p>
          </div>
        ) : (
          <div className="py-4 text-center text-sm text-slate-400">无数据</div>
        )}

        <AlertDialogFooter>
          <AlertDialogCancel>取消</AlertDialogCancel>
          <AlertDialogAction
            className="bg-destructive text-destructive-foreground"
            onClick={() => onConfirm()}
            disabled={loading}
          >
            确认删除
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
