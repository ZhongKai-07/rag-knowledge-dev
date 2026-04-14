import { api } from "@/services/api";

export interface SysDept {
  id: string;
  deptCode: string;
  deptName: string;
  userCount: number;
  kbCount: number;
  createTime: string;
  updateTime: string;
  systemReserved: boolean;
}

export interface SysDeptCreatePayload {
  deptCode: string;
  deptName: string;
}

export interface SysDeptUpdatePayload {
  deptCode: string;
  deptName: string;
}

export async function listDepartments(keyword?: string): Promise<SysDept[]> {
  return api.get<SysDept[], SysDept[]>("/sys-dept", { params: keyword ? { keyword } : undefined });
}

export async function getDepartment(id: string): Promise<SysDept> {
  return api.get<SysDept, SysDept>(`/sys-dept/${id}`);
}

export async function createDepartment(payload: SysDeptCreatePayload): Promise<string> {
  return api.post<string, string>("/sys-dept", payload);
}

export async function updateDepartment(id: string, payload: SysDeptUpdatePayload): Promise<void> {
  await api.put(`/sys-dept/${id}`, payload);
}

export async function deleteDepartment(id: string): Promise<void> {
  await api.delete(`/sys-dept/${id}`);
}
