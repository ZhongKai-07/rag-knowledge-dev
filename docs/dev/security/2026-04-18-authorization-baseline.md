# 权限矩阵审计基线（2026-04-18）

> **用途**：RBAC/ACL 重构前的端点级权限审计快照。重构完成后应以此为对照，目标覆盖率 60% → 100%（除显式 `@PublicEndpoint` 外）。
>
> **快照基于的 commit**：`2e860b1`（分支 `main`）
>
> **审计方式**：逐个阅读 `bootstrap/.../**/controller/*Controller.java`，核对 `@SaCheckRole` / `kbAccessService.check*` / 手写 `if (isSuperAdmin)` 三类检查机制，对照 `SaTokenConfig` 仅含 `checkLogin()` 的网关。

---

## 覆盖率基线

| 状态 | 数量 | 占比 |
|---|---|---|
| 有 RBAC 检查（正确拦截） | ~42 | 60% |
| **零检查**（仅登录） | ~23 | 33% |
| 设计为公开/自助 | ~5 | 7% |
| **合计** | ~70 | 100% |

零检查端点中**约 20 个是漏洞**（应受管控），约 3 个是合理的（feedback 提交、welcome 页 sample 等）。

---

## 记号

| 符号 | 含义 |
|---|---|
| ✅ | 允许 |
| 🔸 | 仅在自己部门范围内（DEPT_ADMIN 专属） |
| 🎯 | 仅自己的数据（owner check） |
| ❌ | 后端主动拒绝 |
| — | 未登录被 SaInterceptor 挡下 |
| 🔴 | **代码漏洞**：任何登录用户都能调，应该拒绝 |
| ⚪ | **意图公开**：登录即可，属设计（白名单式） |

---

## 1. 认证域（Auth）

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 |
|---|---|---|---|---|---|---|
| `/auth/login` | POST | ✅ | ✅ | ✅ | ✅ | `SaInterceptor` 豁免路径 |
| `/auth/logout` | POST | ✅ | ✅ | ✅ | — | login-only |

---

## 2. 用户管理域

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 | Notes |
|---|---|---|---|---|---|---|---|
| `/user/me` | GET | 🎯 | 🎯 | 🎯 | — | login-only | 自我查询 |
| `/user/password` | PUT | 🎯 | 🎯 | 🎯 | — | login-only | 改自己密码 |
| `/users` (list) | GET | ✅ | 🔸 | ❌ | — | 手写 `isSuperAdmin \|\| isDeptAdmin` | 🐛 DEPT_ADMIN 看到的是**所有**用户，未按部门过滤 |
| `/users` | POST | ✅ | 🔸 | ❌ | — | `checkCreateUserAccess` | |
| `/users/{id}` | PUT | ✅ | 🔸 | ❌ | — | `checkUserManageAccess` | 同部门 |
| `/users/{id}` | DELETE | ✅ | 🔸 | ❌ | — | `checkUserManageAccess` + Last-SUPER_ADMIN 守护 | |

---

## 3. 角色管理域

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 |
|---|---|---|---|---|---|---|
| `/role` | POST | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` |
| `/role/{roleId}` | PUT | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` |
| `/role/{roleId}` | DELETE | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` |
| `/role` (list) | GET | ✅ | ✅ 只读 | ❌ | — | `checkAnyAdminAccess` |
| `/role/{roleId}/knowledge-bases` | PUT | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` |
| `/role/{roleId}/knowledge-bases` | GET | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` |
| `/user/{userId}/roles` | PUT | ✅ | 🔸 | ❌ | — | `checkAssignRolesAccess` |
| `/user/{userId}/roles` | GET | ✅ | 🔸 | ❌ | — | AnyAdmin + `checkUserManageAccess` |

---

## 4. 部门管理域

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 |
|---|---|---|---|---|---|---|
| `/sys-dept` (list) | GET | ✅ | ✅ 只读 | ❌ | — | `checkAnyAdminAccess` |
| `/sys-dept/{id}` | GET | ✅ | ✅ 只读 | ❌ | — | `checkAnyAdminAccess` |
| `/sys-dept` | POST | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` |
| `/sys-dept/{id}` | PUT | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` |
| `/sys-dept/{id}` | DELETE | ✅ | ❌ | ❌ | — | `@SaCheckRole("SUPER_ADMIN")` + GLOBAL 硬保护 |

---

## 5. 知识库域

### 5.1 KB 本体

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 |
|---|---|---|---|---|---|---|
| `/knowledge-base` | POST | ✅（任意 dept） | 🔸（本部门下） | ❌ | — | `resolveCreateKbDeptId` |
| `/knowledge-base/{kb-id}` | PUT | ✅ | 🔸 | ❌ | — | `checkManageAccess` |
| `/knowledge-base/{kb-id}` | DELETE | ✅ | 🔸 | ❌ | — | `checkManageAccess` |
| `/knowledge-base/{kb-id}` | GET | ✅ | 🔸+授权 | 🎯 授权 | — | `checkAccess` |
| `/knowledge-base` (list) | GET | ✅ | 🔸 | 🎯 | — | `accessibleKbIds` 过滤 |
| `/knowledge-base/chunk-strategies` | GET | ⚪ | ⚪ | ⚪ | — | login-only（返回静态枚举） |
| `/knowledge-base/{kb-id}/role-bindings` | GET/PUT | ✅ | 🔸 | ❌ | — | `checkKbRoleBindingAccess` |

### 5.2 Document

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 |
|---|---|---|---|---|---|---|
| `/knowledge-base/{kb-id}/docs/upload` | POST | ✅ | 🔸 | ❌ | — | `checkManageAccess(kbId)` |
| `/knowledge-base/docs/{doc-id}/chunk` | POST | ✅ | 🔸 | ❌ | — | `checkDocManageAccess` |
| `/knowledge-base/docs/{doc-id}` | DELETE | ✅ | 🔸 | ❌ | — | `checkDocManageAccess` |
| `/knowledge-base/docs/{docId}` | GET | ✅ | 🔸+授权 | 🎯 授权 | — | `checkAccess(doc.kbId)` |
| `/knowledge-base/docs/{docId}` | PUT | ✅ | 🔸 | ❌ | — | `checkDocManageAccess` |
| `/knowledge-base/{kb-id}/docs` | GET | ✅ | 🔸+授权 | 🎯 授权 | — | `checkAccess(kbId)` |
| `/knowledge-base/docs/search` | GET | ✅ | 🔸 | 🎯 | — | `accessibleKbIds` 过滤 |
| `/knowledge-base/docs/{docId}/enable` | PATCH | ✅ | 🔸 | ❌ | — | `checkDocManageAccess` |
| `/knowledge-base/docs/{docId}/security-level` | PUT | ✅ | 🔸 | ❌ | — | `checkDocSecurityLevelAccess` |
| `/knowledge-base/docs/{docId}/chunk-logs` | GET | ✅ | 🔸 | ❌ | — | `checkDocManageAccess` |

### 5.3 Chunk（类级 `@SaCheckRole("SUPER_ADMIN")`）

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon |
|---|---|---|---|---|---|
| `/knowledge-base/docs/{doc-id}/chunks` | GET/POST | ✅ | ❌ | ❌ | — |
| `/knowledge-base/docs/{doc-id}/chunks/{chunk-id}` | PUT/DELETE | ✅ | ❌ | ❌ | — |
| `/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable` | PATCH | ✅ | ❌ | ❌ | — |
| `/knowledge-base/docs/{doc-id}/chunks/batch-enable` | PATCH | ✅ | ❌ | ❌ | — |

---

## 6. 会话 / 消息 / 反馈域

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 | Notes |
|---|---|---|---|---|---|---|---|
| `/spaces/stats` | GET | ✅ | 🎯 | 🎯 | — | `accessibleKbIds` 过滤 | |
| `/conversations` | GET | 🎯 | 🎯 | 🎯 | — | `UserContext.getUserId()` | |
| `/conversations/{conversationId}` | PUT | 🎯 | 🎯 | 🎯 | — | `validateKbOwnership` | |
| `/conversations/{conversationId}` | DELETE | 🎯 | 🎯 | 🎯 | — | `validateKbOwnership` | |
| `/conversations/{conversationId}/messages` | GET | 🎯 | 🎯 | 🎯 | — | `validateKbOwnership` | |
| `/conversations/messages/{messageId}/feedback` | POST | ⚪ | ⚪ | ⚪ | — | 只登录 | 🐛 **未校验 message 属于自己** |

---

## 7. RAG 问答域

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 机制 |
|---|---|---|---|---|---|---|
| `/rag/v3/chat` | GET (SSE) | ✅ 无 KB 过滤 | 🔸 按 KB 过滤 | 🎯 按 KB 过滤 | — | `getAccessibleKbIds` + `security_level` 过滤 |
| `/rag/v3/stop` | POST | ✅ | ✅ | ✅ | — | login-only + 自有 taskId 检查 |
| `/rag/sample-questions` | GET | ⚪ | ⚪ | ⚪ | — | login-only（欢迎页展示） |

---

## 8. 🔴 配置类：Intent / Mapping / Sample（全漏）

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 漏洞描述 |
|---|---|---|---|---|---|---|
| `/intent-tree/trees` | GET | 🔴 | 🔴 | 🔴 | — | 普通用户可读意图树配置 |
| `/intent-tree` | POST | 🔴 | 🔴 | 🔴 | — | 普通用户可插入节点 |
| `/intent-tree/{id}` | PUT | 🔴 | 🔴 | 🔴 | — | 可改全局配置 |
| `/intent-tree/{id}` | DELETE | 🔴 | 🔴 | 🔴 | — | 可删节点 |
| `/intent-tree/batch/enable` | POST | 🔴 | 🔴 | 🔴 | — | 可批量启用 |
| `/intent-tree/batch/disable` | POST | 🔴 | 🔴 | 🔴 | — | 瘫痪所有人检索路由 |
| `/intent-tree/batch/delete` | POST | 🔴 | 🔴 | 🔴 | — | 可批量删除 |
| `/mappings` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/mappings/{id}` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/mappings` | POST | 🔴 | 🔴 | 🔴 | — | 投毒全局查询改写 |
| `/mappings/{id}` | PUT | 🔴 | 🔴 | 🔴 | — | |
| `/mappings/{id}` | DELETE | 🔴 | 🔴 | 🔴 | — | |
| `/sample-questions` (paged) | GET | 🔴 | 🔴 | 🔴 | — | |
| `/sample-questions/{id}` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/sample-questions` | POST | 🔴 | 🔴 | 🔴 | — | 欢迎页钓鱼 |
| `/sample-questions/{id}` | PUT | 🔴 | 🔴 | 🔴 | — | |
| `/sample-questions/{id}` | DELETE | 🔴 | 🔴 | 🔴 | — | |
| `/rag/settings` | GET | 🔴 | 🔴 | 🔴 | — | 🔥 **API Key 泄漏** |

---

## 9. 🔴 Ingestion 域（全漏）

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | 漏洞描述 |
|---|---|---|---|---|---|---|
| `/ingestion/pipelines` | POST | 🔴 | 🔴 | 🔴 | — | 🔥 普通用户可建 Pipeline |
| `/ingestion/pipelines/{id}` | PUT | 🔴 | 🔴 | 🔴 | — | |
| `/ingestion/pipelines/{id}` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/ingestion/pipelines` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/ingestion/pipelines/{id}` | DELETE | 🔴 | 🔴 | 🔴 | — | |
| `/ingestion/tasks` | POST | 🔴 | 🔴 | 🔴 | — | 🔥 **自助 SSRF**（`HttpUrlFetcher` 无私网白名单） |
| `/ingestion/tasks/upload` | POST | 🔴 | 🔴 | 🔴 | — | 任意文件上传 |
| `/ingestion/tasks/{id}` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/ingestion/tasks/{id}/nodes` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/ingestion/tasks` | GET | 🔴 | 🔴 | 🔴 | — | |

---

## 10. 🔴 观测域（Dashboard 正常，Trace / Evaluation 全漏）

| 端点 | Method | SUPER_ADMIN | DEPT_ADMIN | USER | Anon | Notes |
|---|---|---|---|---|---|---|
| `/admin/dashboard/overview` | GET | ✅ | ✅ 全量 | ❌ | — | 🐛 DEPT_ADMIN 看全量而非本部门 |
| `/admin/dashboard/performance` | GET | ✅ | ✅ 全量 | ❌ | — | 同上 |
| `/admin/dashboard/trends` | GET | ✅ | ✅ 全量 | ❌ | — | 同上 |
| `/rag/traces/runs` | GET | 🔴 | 🔴 | 🔴 | — | 🔥 跨用户泄漏：全量 trace 含 query 内容 |
| `/rag/traces/runs/{traceId}` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/rag/traces/runs/{traceId}/nodes` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/rag/evaluations` | GET | 🔴 | 🔴 | 🔴 | — | 🔥 **绕过 `security_level` 读全量检索结果** |
| `/rag/evaluations/{id}` | GET | 🔴 | 🔴 | 🔴 | — | |
| `/rag/evaluations/export` | GET | 🔴 | 🔴 | 🔴 | — | 批量导出放大 |
| `/rag/evaluations/{id}/metrics` | PUT | 🔴 | 🔴 | 🔴 | — | 可污染指标 |

---

## 11. 修复优先级（Phase 0 止血）

| 优先级 | 端点 | 最小补丁 |
|---|---|---|
| 🔥 P0 | `/ingestion/pipelines/*`、`/ingestion/tasks/*` | 类级 `@SaCheckRole("SUPER_ADMIN")`；同时给 `HttpUrlFetcher` 加私网 IP 黑名单 |
| 🔥 P0 | `/rag/evaluations/*`、`/rag/traces/*` | 类级 `@SaCheckRole(value={"SUPER_ADMIN","DEPT_ADMIN"}, mode=OR)`；service 层加 `userId`/`kbId` 过滤 |
| 🔥 P0 | `/rag/settings` | 拆两档：基础参数 AnyAdmin / API Key SUPER_ADMIN only |
| 🟠 P1 | `/intent-tree/*`、`/mappings/*` | 类级 `@SaCheckRole("SUPER_ADMIN")` |
| 🟠 P1 | `/sample-questions/*`（除 `/rag/sample-questions` GET） | 类级 `@SaCheckRole("SUPER_ADMIN")` |
| 🟡 P2 | `/conversations/messages/{id}/feedback` | service 层加 message ownership 校验 |
| 🟡 P2 | `/users` (GET list) | DEPT_ADMIN 需按部门过滤 |
| 🟡 P2 | `/admin/dashboard/*` | DEPT_ADMIN 应只看本部门聚合 |

---

## 12. 结构性结论（推动重构的根因）

1. **权限覆盖极不均匀**：用户/角色/部门/KB 域 95%+ 覆盖，意图树/映射/评测/观测/入库 5 个后加的"AI 运营"域几乎为 0。原因是早期迭代重点在 KB 管理，新加的 controller 没纳入权限审查流程。

2. **没有 DEPT_ADMIN 实例导致能力虚设**：表里所有 🔸 行在生产数据下等价 ❌（因为没人是 DEPT_ADMIN）。生产等价二极模式：全能 admin vs 只读 user。

3. **"制度失效"是根因**：20/23 零检查行都具同一结构——**新 controller 默认无检查**。靠人肉审查已失败过多次，重构方案必须加入**默认拒绝的网关 + ArchUnit 启动时强制校验**。

---

## 13. 重构后验收标准

当完成 RBAC/ACL/ABAC 重构后，重新跑这张矩阵应得到：

- 🔴 行数 = 0（所有端点都有显式 `@RequiresPermission` 或 `@PublicEndpoint`）
- ⚪ 行 = 显式标注 `@PublicEndpoint` 的白名单（预计 ≤ 5 条：login、chat-strategies、sample-questions 随机读等）
- 新增审计日志覆盖：所有 Deny 决策落 `t_access_audit`

可运行的 ArchUnit 测试（加入 Maven `verify`）：

```java
@ArchTest
static final ArchRule all_rest_endpoints_require_permission =
    methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
        .and().areAnnotatedWith(
            anyOf(GetMapping.class, PostMapping.class, PutMapping.class,
                  DeleteMapping.class, PatchMapping.class, RequestMapping.class))
        .should().beAnnotatedWith(RequiresPermission.class)
        .orShould().beAnnotatedWith(PublicEndpoint.class);
```

漏一个端点 → 构建失败。**这条是本次重构成败的试金石**。
