import { api } from "@/services/api";

export interface UserItem {
  id: string;
  username: string;
  avatar?: string;
  deptId: string | null;
  deptName: string | null;
  roleTypes: string[];
  maxSecurityLevel: number;
  createTime: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface UserCreatePayload {
  username: string;
  password: string;
  avatar?: string;
  deptId: string;
  roleIds: string[];
}

export interface UserUpdatePayload {
  username: string;
  avatar?: string;
  password?: string;
  deptId?: string;
}

export interface ChangePasswordPayload {
  currentPassword: string;
  newPassword: string;
}

export async function getUsersPage(
  current = 1,
  size = 10,
  keyword?: string
): Promise<PageResult<UserItem>> {
  return api.get<PageResult<UserItem>, PageResult<UserItem>>("/users", {
    params: { current, size, keyword: keyword || undefined }
  });
}

export async function createUser(payload: UserCreatePayload): Promise<string> {
  return api.post<string, string>("/users", payload);
}

export async function updateUser(id: string, payload: UserUpdatePayload): Promise<void> {
  await api.put(`/users/${id}`, payload);
}

export async function deleteUser(id: string): Promise<void> {
  await api.delete(`/users/${id}`);
}

export async function changePassword(payload: ChangePasswordPayload): Promise<void> {
  await api.put("/user/password", payload);
}
