import { Navigate } from "react-router-dom";
import { toast } from "sonner";
import type { ReactNode } from "react";
import { usePermissions, type AdminMenuId } from "@/utils/permissions";
import { useAuthStore } from "@/stores/authStore";

export function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export function RequireAnyAdmin({ children }: { children: ReactNode }) {
  const { canSeeAdminMenu } = usePermissions();
  if (!canSeeAdminMenu) return <Navigate to="/spaces" replace />;
  return <>{children}</>;
}

export function RequireSuperAdmin({ children }: { children: ReactNode }) {
  const { isSuperAdmin } = usePermissions();
  if (!isSuperAdmin) {
    toast.info("您没有此页面的访问权限");
    return <Navigate to="/admin/dashboard" replace />;
  }
  return <>{children}</>;
}

export function RequireMenuAccess({
  menuId,
  children,
}: {
  menuId: AdminMenuId;
  children: ReactNode;
}) {
  const { canSeeMenuItem } = usePermissions();
  if (!canSeeMenuItem(menuId)) {
    toast.info("您没有此页面的访问权限");
    return <Navigate to="/admin/dashboard" replace />;
  }
  return <>{children}</>;
}
