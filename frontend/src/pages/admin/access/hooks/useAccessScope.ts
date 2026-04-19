import { createContext, useContext, useMemo, type ReactNode } from "react";
import { createElement } from "react";
import { usePermissions } from "@/utils/permissions";

/**
 * 权限中心的 scope 上下文。
 * 直接从 authStore.user 派生（经 usePermissions），不引入独立的 /access/scope
 * 接口；若日后上线"SUPER 代入部门视角"功能，再替换此 Provider 的数据源。
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
