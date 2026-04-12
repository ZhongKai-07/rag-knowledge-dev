import { useMemo } from "react";
import { useAuthStore } from "@/stores/authStore";
import type { User } from "@/types";

export type AdminMenuId =
  | "dashboard"
  | "knowledge"
  | "users"
  | "departments"
  | "intent-tree"
  | "ingestion"
  | "mappings"
  | "traces"
  | "evaluations"
  | "sample-questions"
  | "roles"
  | "settings";

const DEPT_VISIBLE: AdminMenuId[] = ["dashboard", "knowledge", "users"];

export interface Permissions {
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
  isAnyAdmin: boolean;
  deptId: string | null;
  deptName: string | null;
  maxSecurityLevel: number;
  canSeeAdminMenu: boolean;
  canSeeMenuItem: (id: AdminMenuId) => boolean;
  canCreateKb: (targetDeptId: string) => boolean;
  canManageKb: (kb: { deptId: string }) => boolean;
  canManageUser: (targetUser: { deptId: string | null }) => boolean;
  canEditDocSecurityLevel: (doc: { kbDeptId: string }) => boolean;
  canAssignRole: (role: { roleType: string }) => boolean;
}

export function getPermissions(user: User | null): Permissions {
  const isSuperAdmin = user?.isSuperAdmin ?? false;
  const isDeptAdmin = user?.isDeptAdmin ?? false;
  const isAnyAdmin = isSuperAdmin || isDeptAdmin;
  return {
    isSuperAdmin,
    isDeptAdmin,
    isAnyAdmin,
    deptId: user?.deptId ?? null,
    deptName: user?.deptName ?? null,
    maxSecurityLevel: user?.maxSecurityLevel ?? 0,
    canSeeAdminMenu: isAnyAdmin,
    canSeeMenuItem: (id) => isSuperAdmin || (isDeptAdmin && DEPT_VISIBLE.includes(id)),
    canCreateKb: (targetDeptId) =>
      isSuperAdmin || (isDeptAdmin && targetDeptId === user?.deptId),
    canManageKb: (kb) =>
      isSuperAdmin || (isDeptAdmin && kb.deptId === user?.deptId),
    canManageUser: (targetUser) =>
      isSuperAdmin || (isDeptAdmin && targetUser.deptId === user?.deptId),
    canEditDocSecurityLevel: (doc) =>
      isSuperAdmin || (isDeptAdmin && doc.kbDeptId === user?.deptId),
    canAssignRole: (role) => isSuperAdmin || role.roleType !== "SUPER_ADMIN",
  };
}

export function usePermissions(): Permissions {
  const user = useAuthStore((s) => s.user);
  return useMemo(() => getPermissions(user), [user]);
}
