import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowRight } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  getKbRoleBindings,
  type KbRoleBindingVO,
} from "@/services/knowledgeService";
import { isRbacRejection } from "@/utils/error";

interface Props {
  kbId: string;
}

const SECURITY_LABELS = ["公开", "内部", "机密", "绝密"];

export default function KbSharingSummary({ kbId }: Props) {
  const [bindings, setBindings] = useState<KbRoleBindingVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [noAccess, setNoAccess] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await getKbRoleBindings(kbId);
        if (!cancelled) setBindings(list);
      } catch (err) {
        if (!cancelled && isRbacRejection(err)) setNoAccess(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [kbId]);

  if (noAccess) return null;

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-3">
        <CardTitle className="text-base">共享管理</CardTitle>
        <Link
          to={`/admin/access?tab=sharing&kb=${kbId}`}
          className="inline-flex items-center gap-1 text-sm text-indigo-600 hover:text-indigo-700"
        >
          在共享管理页修改
          <ArrowRight className="h-4 w-4" />
        </Link>
      </CardHeader>
      <CardContent>
        {loading ? (
          <p className="text-sm text-slate-400">加载中...</p>
        ) : bindings.length === 0 ? (
          <p className="text-sm text-slate-400">暂无共享角色</p>
        ) : (
          <div className="space-y-2">
            <p className="text-xs text-slate-500">
              已共享 {bindings.length} 个角色（只读预览）
            </p>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-slate-500">
                  <th className="pb-2 font-medium">角色</th>
                  <th className="pb-2 font-medium">类型</th>
                  <th className="pb-2 font-medium">权限</th>
                  <th className="pb-2 font-medium">密级</th>
                </tr>
              </thead>
              <tbody>
                {bindings.map((b) => (
                  <tr key={b.roleId} className="border-b last:border-0">
                    <td className="py-2 font-medium text-slate-800">{b.roleName}</td>
                    <td className="py-2">
                      <Badge variant="outline" className="text-xs">
                        {b.roleType}
                      </Badge>
                    </td>
                    <td className="py-2 text-slate-600">{b.permission}</td>
                    <td className="py-2 text-slate-600">
                      {SECURITY_LABELS[b.maxSecurityLevel ?? 0] ?? b.maxSecurityLevel}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
