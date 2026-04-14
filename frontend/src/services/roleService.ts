import { api } from "@/services/api";

export interface RoleItem {
  id: string;
  name: string;
  roleType: string;
  maxSecurityLevel: number;
  description?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface RoleCreatePayload {
  name: string;
  description?: string;
  roleType: string;
  maxSecurityLevel: number;
}

export interface RoleKbBinding {
  kbId: string;
  permission: "READ" | "WRITE" | "MANAGE";
  maxSecurityLevel?: number;
}

// 角色 CRUD
export async function getRoles(): Promise<RoleItem[]> {
  return api.get<RoleItem[], RoleItem[]>("/role");
}

export async function createRole(payload: RoleCreatePayload): Promise<string> {
  return api.post<string, string>("/role", payload);
}

export async function updateRole(id: string, payload: RoleCreatePayload): Promise<void> {
  await api.put(`/role/${id}`, payload);
}

export async function deleteRole(id: string): Promise<void> {
  await api.delete(`/role/${id}`);
}

// 角色-知识库关联
export async function getRoleKnowledgeBases(roleId: string): Promise<RoleKbBinding[]> {
  return api.get<RoleKbBinding[], RoleKbBinding[]>(`/role/${roleId}/knowledge-bases`);
}

export async function setRoleKnowledgeBases(
  roleId: string,
  bindings: RoleKbBinding[]
): Promise<void> {
  await api.put(`/role/${roleId}/knowledge-bases`, bindings);
}

// 用户-角色关联
export async function getUserRoles(userId: string): Promise<RoleItem[]> {
  return api.get<RoleItem[], RoleItem[]>(`/user/${userId}/roles`);
}

export async function setUserRoles(userId: string, roleIds: string[]): Promise<void> {
  await api.put(`/user/${userId}/roles`, roleIds);
}
