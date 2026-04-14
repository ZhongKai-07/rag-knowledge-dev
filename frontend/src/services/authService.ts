import { api } from "@/services/api";

export interface LoginResponse {
  userId: string;
  username: string;
  avatar?: string;
  token: string;
  deptId: string | null;
  deptName: string | null;
  roleTypes: string[];
  maxSecurityLevel: number;
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
}

export interface CurrentUserResponse {
  userId: string;
  username: string;
  avatar?: string;
  deptId: string | null;
  deptName: string | null;
  roleTypes: string[];
  maxSecurityLevel: number;
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
}

export async function login(username: string, password: string) {
  return api.post<LoginResponse>("/auth/login", { username, password });
}

export async function logout() {
  return api.post<void>("/auth/logout");
}

export async function getCurrentUser() {
  return api.get<CurrentUserResponse>("/user/me");
}
