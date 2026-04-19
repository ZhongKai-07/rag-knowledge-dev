import { SharingPage } from "@/pages/admin/sharing/SharingPage";

/**
 * P1.5a: 暂直接复用 P0.3 的 SharingPage（已具备 KB 卡片 + 添加/移除共享）。
 * P1.5c 会在此 Tab 内升级：加入 ⚡ 跨部门徽章（基于 role.deptId vs kb.deptId）、
 * role 下拉接入 /access/roles 并按 scope 过滤、密级下调轻提示已齐全。
 */
export function SharingTab() {
  return <SharingPage />;
}
