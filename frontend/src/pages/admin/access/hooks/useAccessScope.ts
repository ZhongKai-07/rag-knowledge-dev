import { createContext, useContext, useMemo, type ReactNode } from "react";
import { createElement } from "react";
import { usePermissions } from "@/utils/permissions";

/**
 * P1.5a: 权限中心的 scope 上下文。
 * <p>
 * 决议 D10：不调 {@code /access/scope} 接口，直接从 {@code authStore.user}（经
 * {@code usePermissions}）派生。未来 P3 上"SUPER 代入部门视角"时再重新评估。
 */
export interface AccessScope {
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
  deptId: string | null;
  deptName: string | null;
  /** 管理范围人类可读标签，用于 Banner */
  scopeLabel: string;
  /** 当前身份人类可读标签 */
  identityLabel: string;
}

const AccessScopeContext = createContext<AccessScope | null>(null);

export function useAccessScope(): AccessScope {
  const ctx = useContext(AccessScopeContext);
  if (!ctx) {
    throw new Error("useAccessScope must be used inside AccessScopeProvider");
  }
  return ctx;
}

export function AccessScopeProvider({ children }: { children: ReactNode }) {
  const permissions = usePermissions();
  const scope: AccessScope = useMemo(() => {
    const isSuper = permissions.isSuperAdmin;
    const isDeptAdmin = permissions.isDeptAdmin;
    const scopeLabel = isSuper
      ? "全公司"
      : isDeptAdmin && permissions.deptName
      ? `${permissions.deptName} 部门`
      : "—";
    const identityLabel = isSuper
      ? "超级管理员"
      : isDeptAdmin && permissions.deptName
      ? `${permissions.deptName}管理员`
      : isDeptAdmin
      ? "部门管理员"
      : "成员";
    return {
      isSuperAdmin: isSuper,
      isDeptAdmin,
      deptId: permissions.deptId,
      deptName: permissions.deptName,
      scopeLabel,
      identityLabel,
    };
  }, [permissions]);

  return createElement(AccessScopeContext.Provider, { value: scope }, children);
}
