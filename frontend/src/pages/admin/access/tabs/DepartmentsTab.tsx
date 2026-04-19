import { DepartmentListPage } from "@/pages/admin/departments/DepartmentListPage";

/**
 * P2.1 会把 DepartmentListPage 正式迁入此 Tab 并拓展列（含 roleCount）。
 * P1.5a 先复用现有 SUPER-only 页面作为占位，保留访问连续性。
 */
export function DepartmentsTab() {
  return <DepartmentListPage />;
}
