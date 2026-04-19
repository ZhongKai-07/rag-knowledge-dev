import { api } from "./api";

/** GET /access/roles 返回体 */
export interface AccessRole {
  id: string;
  name: string;
  description?: string | null;
  roleType: string;
  maxSecurityLevel: number;
  deptId?: string | null;
  deptName?: string | null;
}

/** GET /access/users/{id}/kb-grants 返回体 */
export interface UserKbGrant {
  kbId: string;
  kbName: string;
  deptId?: string | null;
  /** 有效权限：implicit ? MANAGE : explicitPermission */
  permission: string | null;
  /** 显式 role 链权限；null 表示隐式命中 */
  explicitPermission: string | null;
  securityLevel: number | null;
  sourceRoleIds: string[];
  /** DEPT_ADMIN 同部门隐式 MANAGE 命中时为 true */
  implicit: boolean;
}

/** GET /access/roles/{id}/usage 返回体 */
export interface RoleUsage {
  roleId: string;
  roleName: string;
  roleType: string;
  deptId?: string | null;
  deptName?: string | null;
  users: Array<{
    userId: string;
    username: string;
    deptId?: string | null;
    deptName?: string | null;
  }>;
  kbs: Array<{
    kbId: string;
    kbName: string;
    deptId?: string | null;
    deptName?: string | null;
    permission: string | null;
    maxSecurityLevel: number | null;
  }>;
}

/** GET /access/departments/tree 返回体（复用 SysDeptVO + 三项 count） */
export interface AccessDeptNode {
  id: string;
  deptCode: string;
  deptName: string;
  userCount: number;
  kbCount: number;
  roleCount: number;
  systemReserved: boolean;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface ListRolesParams {
  deptId?: string | null;
  includeGlobal?: boolean;
}

export async function listAccessRoles(params: ListRolesParams = {}): Promise<AccessRole[]> {
  return api.get<AccessRole[], AccessRole[]>("/access/roles", {
    params: {
      dept_id: params.deptId ?? undefined,
      include_global: params.includeGlobal ?? true,
    },
  });
}

export async function getUserKbGrants(userId: string): Promise<UserKbGrant[]> {
  return api.get<UserKbGrant[], UserKbGrant[]>(`/access/users/${userId}/kb-grants`);
}

export async function getRoleUsage(roleId: string): Promise<RoleUsage> {
  return api.get<RoleUsage, RoleUsage>(`/access/roles/${roleId}/usage`);
}

export async function getDepartmentsTree(): Promise<AccessDeptNode[]> {
  return api.get<AccessDeptNode[], AccessDeptNode[]>("/access/departments/tree");
}

/** GET /role/{id}/delete-preview 返回体 */
export interface RoleDeletePreview {
  roleId: string;
  roleName: string;
  affectedUsers: Array<{
    userId: string;
    username: string;
    deptId?: string | null;
    deptName?: string | null;
  }>;
  affectedKbs: Array<{
    kbId: string;
    kbName: string;
    deptId?: string | null;
    deptName?: string | null;
  }>;
  userKbDiff: Array<{
    userId: string;
    username: string;
    lostKbIds: string[];
    lostKbNames: string[];
  }>;
}

export async function getRoleDeletePreview(roleId: string): Promise<RoleDeletePreview> {
  return api.get<RoleDeletePreview, RoleDeletePreview>(`/role/${roleId}/delete-preview`);
}
