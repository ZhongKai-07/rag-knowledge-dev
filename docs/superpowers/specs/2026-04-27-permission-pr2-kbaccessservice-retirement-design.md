# PR2 — KbAccessService God-Service Retirement + KbScopeResolver

**Date**: 2026-04-27
**Author**: brainstorming session (zk2793126229@gmail.com + Claude)
**Scope**: 退役 `KbAccessService` god-service。RAG 热路径 + KB/Doc/Chunk service + user 域 controller/service 全迁到 `framework.security.port` 下 8 个具体 port。引入 `KbScopeResolver`,scope 跨边界统一为 `AccessScope` sealed type。零业务功能增量。
**Out-of-scope marker**: 这是 **PR2** 阶段 A 的第二刀。PR3 (`KbAccessCalculator` + caller-context bug 修复) / 阶段 B-G 显式延期(见 §5.2)。

---

## 0. Problem Statement & Roadmap 修正

### 0.1 当前状态

PR1 (#24, `a24ea4b`) 完成 controller→service 边界下沉 + `system=true` 显式态 + fail-closed 守卫。但 `KbAccessService` god-service 仍在 16 个 main 文件被注入,扩散到:

- **RAG 热路径**:`RAGChatServiceImpl:134` + `MultiChannelRetrievalEngine:262`
- **KB/Doc/Chunk service 内部**:`KnowledgeBaseServiceImpl` / `KnowledgeDocumentServiceImpl` / `KnowledgeChunkServiceImpl` / `RoleServiceImpl` 共 22 处 check 调用
- **user 域 controller/service**:5 个 controller + 3 个 service 内 ~20 处 caller
- **scope 计算**:`KnowledgeBaseController.applyKbScope` / `SpacesController:54-68` / `KnowledgeDocumentController.search:125-128` 三处用 `getAccessibleKbIds(...)` + `Set<String> accessibleKbIds = null` 三态约定

新代码任意注入 `KbAccessService` 都会复活 god-service,PR1 留下的 grep gate 仅守 controller 5 个 KB-scoped check,无法防 god-service 在其他位置扩散。

### 0.2 PR2 目标

1. **退役 god-service**:bootstrap 业务代码(c1-c4 全部完成后)无任何 `KbAccessService` 注入
2. **统一 scope 类型**:跨 controller/service 边界传递权限范围使用 `AccessScope` sealed type,消除 `Set<String> + null` 三态约定
3. **不变 PR1 决策语义**:caller-context 泄漏(`AccessServiceImpl:183`)留 PR3 修;HTTP 语义不变(Inv C);system actor 显式态(Inv B)继续

### 0.3 对 roadmap 的两处修正(决策日志)

PR2 brainstorming 后对 `docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 A · PR2 的 wording 做两处修正(merge 后回写 roadmap):

- **修正 1**:roadmap §3 line 130 "迁到 `KbReadAccessPort / KbManageAccessPort / CurrentUserProbe` 三个 port" → 实际涉及 **`framework.security.port` 下 8 个具体 port**(7 个 PR1 已存在 + 1 个 PR2 新增 `KbRoleBindingAdminPort`)
- **修正 2**:roadmap §3 line 184 "`rg \"KbAccessService\" bootstrap/src/main/java | wc -l ≤ 3`" 阶段 A 全部成功标准 → 被 PR2 的 **B+C 双 gate** 取代:文件级 `rg -l` 输出仅 2 文件 + 注入级 `rg "private final KbAccessService\b"` 输出空。规避 javadoc / `@link` 引用的数字漂移。

---

## 1. Layering Contract

### 1.1 PR2 后的分层契约图

```
┌───────────────────────────────────────────────────────────────┐
│ Controller                                                    │
│   - HTTP 协议职责(参数 / VO / Result)                         │
│   - 仅 scope 计算的 3 个 HTTP caller 注入 KbScopeResolver:    │
│     KnowledgeBaseController.pageQuery /                       │
│     SpacesController.getStats /                               │
│     KnowledgeDocumentController.search                        │
│   - user 域 controller 注入 UserAdminGuard / CurrentUserProbe │
│   - 不再注入 KbAccessService(c6 ArchUnit 强制)                │
│   - PR1 已建立的 5 个 KB-scoped check 不在 controller(继续)   │
├───────────────────────────────────────────────────────────────┤
│ KbScopeResolver(c1 新建)                                     │
│   - 入参 LoginUser,不读 ThreadLocal                           │
│   - 返回 AccessScope sealed type                              │
│   - 依赖 KbReadAccessPort + KbMetadataReader                  │
│   - 两个方法:resolveForRead / resolveForOwnerScope            │
├───────────────────────────────────────────────────────────────┤
│ Service(KB / Document / Chunk / Role / User / Access)         │
│   - PR1 信任边界继续:public user-entry 首行 check              │
│   - PR2 改动:check 调用从 KbAccessService.checkXxx            │
│     迁到 KbReadAccessPort/KbManageAccessPort.xxx              │
│   - service 入参:list/search 路径加 AccessScope 形参          │
│   - service 解构 AccessScope 模板(§2.4)统一                  │
├───────────────────────────────────────────────────────────────┤
│ framework.security.port(7 → 8 个 port)                       │
│   既有(7,PR1 已存在):                                         │
│     CurrentUserProbe / KbReadAccessPort / KbManageAccessPort  │
│     UserAdminGuard / SuperAdminInvariantGuard                 │
│     KbAccessCacheAdmin / KbMetadataReader                     │
│   新增(1,PR2 c3 新建):                                        │
│     KbRoleBindingAdminPort {                                  │
│       int unbindAllRolesFromKb(String kbId);                  │
│     }                                                         │
│   既有接口的方法增量(c4):                                      │
│     UserAdminGuard.checkRoleMutation(String roleDeptId)       │
├───────────────────────────────────────────────────────────────┤
│ KbAccessService(c5 后 @Deprecated 缓冲接口)                   │
│   - 接口签名零改动(保留 unbindAllRolesFromKb /                 │
│     checkRoleMutation / getMaxSecurityLevelForKb 等方法)       │
│   - main 代码无任何 caller(c1-c4 已迁,c6 ArchUnit 强制)       │
│   - KbAccessServiceImpl 保留 implements KbAccessService       │
│     作 ArchUnit allowlist 锚点                                │
│   - PR3 + sharing PR D7 完成后开 cleanup PR 物理移除          │
└───────────────────────────────────────────────────────────────┘
```

### 1.2 不变量 E/F/G/H(PR1 不变量 A-D 之外的新增)

PR1 已建立 A(边界唯一性)/ B(系统态显式)/ C(HTTP 语义不变)/ D(service vs HTTP 边界分离)。PR2 新增:

- **E — Port 注入唯一性**:bootstrap 业务代码注入 `framework.security.port` 下的具体 port,**不**注入 `KbAccessService` god-service。Exact-class allowlist:`KbAccessService` 接口本身 + `KbAccessServiceImpl` 委托者实现。任何回归被 `KbAccessServiceRetirementArchTest` 立即抓住。

- **F — Scope 单一类型(跨边界)**:权限范围在 controller / service / RAG 路径**跨边界**传递时**必须**使用 `AccessScope` sealed type。**禁止** `Set<String> + null` 表达"权限范围三态"。`AccessScope.Ids` 解构后**局部**喂 mapper(`.in(ids.kbIds())`)允许;`AccessServiceImpl` 内 target grants 等业务集合不受此约束。

- **G — Resolver 不直接读 ThreadLocal**:`KbScopeResolver` 自身不调 `UserContext.get()` / `UserContext.getUserId()`,SUPER/DEPT_ADMIN 判定从入参 `LoginUser.getRoleTypes()` 取。**说明**:resolver 调用的 `KbReadAccessPort.getAccessScope` 实现仍读 `UserContext`(其内部 `isSuperAdmin/isDeptAdmin` 需要),resolver 间接依赖 ThreadLocal。这是 PR2 明确折中,**target-aware 完全消除留 PR3 `KbAccessCalculator`**(roadmap §3 PR3 commit 1)。

- **H — Caller-context 语义不变**:PR2 仅迁注入,不修 caller-context bug。`AccessServiceImpl:183` 改用 `getMaxSecurityLevelsForKbs(...)` 批量版后,admin-views-target 路径仍泄漏 caller 的 SUPER scope 而非 target user 的真实 scope。**这是已知缺陷,留 PR3 修复**(roadmap §3 PR3 commit 2)。

### 1.3 反模式增量(PR1 11 条之外新增 2 条)

写进 roadmap §2.3 候选(PR2 merge 后 roadmap 同步更新):

- ❌ **新代码注入 `KbAccessService`**(c6 ArchUnit 强制,exact-class allowlist 仅 2 类)
- ❌ **业务代码用 `Set<String>` + `null` 表达"权限范围三态"**(应使用 `AccessScope` sealed type 的 `All` / `Ids(empty)` / `Ids(nonEmpty)`)

---

## 2. Per-File Inventory

### 2.1 framework 层改动

| 文件 | 改动 | Commit | 行数 |
|---|---|---|---|
| **新建** `framework/.../security/port/KbRoleBindingAdminPort.java` | 单方法 port:`int unbindAllRolesFromKb(String kbId);` + javadoc 说明语义来自 `KbAccessService` 同名方法,PR D7 sharing 产品化时此 port 会扩展 | **c3** | ~30 |
| `framework/.../security/port/UserAdminGuard.java` | 加方法 `void checkRoleMutation(String roleDeptId);` + javadoc 沿用 `KbAccessService` 同名方法的 wording(SUPER 任意,DEPT_ADMIN 仅本部门非 GLOBAL) | **c4** | +8 |

### 2.2 KbAccessService 接口/实现层(c5)

| 文件 | 改动 | Commit | 行数 |
|---|---|---|---|
| `bootstrap/.../user/service/KbAccessService.java` | 接口标 `@Deprecated(forRemoval = false)`;类级 javadoc 改写为"PR2 完成 RAG/KB/user 域迁移,本接口保留作过渡 deprecated 缓冲;新代码请注入 framework.security.port 下 8 个具体 port;PR3 + sharing PR D7 完成后开 cleanup PR 物理移除" | **c5** | +6 |
| `bootstrap/.../user/service/impl/KbAccessServiceImpl.java` | `implements` 列表加 `KbRoleBindingAdminPort`(7→8);**保留** `implements KbAccessService`(ArchUnit allowlist 锚点);**实现方法零改动**(已有 `unbindAllRolesFromKb` / `checkRoleMutation` / `getMaxSecurityLevelForKb` 全部保留) | **c3 + c5** | +1 |

### 2.3 RAG 路径(c2)

`MultiChannelRetrievalEngine.java` 也在 c2:`getMaxSecurityLevelsForKbs` 已是批量版,只替换字段类型和调用 receiver。

| 文件 | Before | After | 行数 |
|---|---|---|---|
| `RAGChatServiceImpl.java:97` | `private final KbAccessService kbAccessService;` | **删除**(下一行 `kbReadAccess` 已有) | -1 |
| `RAGChatServiceImpl.java:134` | `kbAccessService.checkAccess(knowledgeBaseId);` | `kbReadAccess.checkReadAccess(knowledgeBaseId);` | 1 |
| `RAGChatServiceImpl.java:67` | `import ... KbAccessService;` | 删除 import | -1 |
| `MultiChannelRetrievalEngine.java:67` | `private final KbAccessService kbAccessService;` | `private final KbReadAccessPort kbReadAccess;` | 1 |
| `MultiChannelRetrievalEngine.java:262` | `kbAccessService.getMaxSecurityLevelsForKbs(...)` | `kbReadAccess.getMaxSecurityLevelsForKbs(...)` | 1 |
| `MultiChannelRetrievalEngine.java:35` | import 替换 | | 0 |

c2 总计:**2 文件 / ~5 行净变化**

### 2.4 KbScopeResolver + Controller scope 改造(c1, 9 文件 — 2 新建 + 7 修改)

#### 新建文件

| 文件 | 内容 | 行数 |
|---|---|---|
| **新建** `bootstrap/.../knowledge/service/KbScopeResolver.java`(接口) | 2 方法签名 + javadoc | ~30 |
| **新建** `bootstrap/.../knowledge/service/impl/KbScopeResolverImpl.java` | `@Service` + 注入 `KbReadAccessPort` + `KbMetadataReader` + 不读 ThreadLocal | ~70 |

#### resolver 实现规则

| 入参 LoginUser 状态 | resolveForRead 返回 | resolveForOwnerScope 返回 |
|---|---|---|
| `null` 或 `userId == null` | `AccessScope.empty()` | `AccessScope.empty()` |
| roleTypes 含 `SUPER_ADMIN` | `AccessScope.all()` | `AccessScope.all()` |
| roleTypes 含 `DEPT_ADMIN`(deptId 非 null) | `kbReadAccessPort.getAccessScope(userId, READ)` | `AccessScope.ids(kbMetadataReader.listKbIdsByDeptId(deptId))` |
| roleTypes 含 `DEPT_ADMIN`(deptId == null,数据异常) | `kbReadAccessPort.getAccessScope(userId, READ)`(同 USER 兜底) | `AccessScope.empty()` |
| 普通 USER | `kbReadAccessPort.getAccessScope(userId, READ)` | `AccessScope.empty()` |

resolver 自身**不**注入 `CurrentUserProbe`(Inv G),从 `user.getRoleTypes()` 直接判 SUPER/DEPT_ADMIN。

#### Service 层统一解构模板

所有消费 `AccessScope` 的 service 入口必须遵循:

```java
if (scope instanceof AccessScope.All) {
    // 不加 .in(...) — 全表
} else if (scope instanceof AccessScope.Ids ids) {
    if (ids.kbIds().isEmpty()) {
        return /* empty page / empty list / 0 count */;
    }
    queryWrapper.in(KnowledgeBaseDO::getId, ids.kbIds());
} else {
    throw new IllegalStateException("Unsupported AccessScope type: " + scope.getClass());
}
```

#### 修改文件

| 文件 | 改动 | 行数 |
|---|---|---|
| `KnowledgeBaseController.java:108-128` `applyKbScope` | 整段重写:用 `kbScopeResolver.resolveForRead/resolveForOwnerScope`;字段从 `KbAccessService` 换 `KbScopeResolver` | ~25 净 |
| `KnowledgeBaseController.java:101-106` `pageQuery` | 调用改为 `knowledgeBaseService.pageQuery(req, scope)` | 5 |
| `SpacesController.java:51-83` `getStats` | 整段重写:`UserContext.hasUser() ? UserContext.get() : null`;`if (scope instanceof All) {全表 count} else if (scope instanceof Ids ids) {empty 短路 / nonEmpty in()} else throw IllegalState`;字段换 `KbScopeResolver` | ~35 净 |
| `KnowledgeDocumentController.java:122-130` `search` | `null` 三态删除,改为 `documentService.search(keyword, limit, scope)`;字段换 `KbScopeResolver` | ~10 净 |
| `KnowledgeBasePageRequest.java` | **物理删除** `accessibleKbIds: Set<String>` 字段 | -3 |
| `KnowledgeBaseService.java` 接口 | `pageQuery` 方法签名加 `AccessScope scope` 形参 | +1 |
| `KnowledgeBaseServiceImpl.java` `pageQuery` | 入参增加 + 内部按 §2.4 service 模板解构 | ~25 净 |
| `KnowledgeDocumentService.java` 接口 | `search` 方法签名:`Set<String> accessibleKbIds` → `AccessScope scope` | 1 |
| `KnowledgeDocumentServiceImpl.java` `search` | 入参类型替换 + 内部按 §2.4 service 模板解构 | ~25 净 |

c1 总计:**9 文件 / ~230 行**(含新建 100 行)

### 2.5 KB/Doc/Chunk service 内部迁移(c3, 5 文件 + framework/CLAUDE.md)

| 文件 | 改动 | 行数 |
|---|---|---|
| `KnowledgeBaseServiceImpl.java` | 字段:`KbAccessService kbAccessService` → 拆为 `KbReadAccessPort kbReadAccess` + `KbManageAccessPort kbManageAccess` + `KbRoleBindingAdminPort kbRoleBindingAdmin`;`:97` `kbAccessService.resolveCreateKbDeptId` → `kbManageAccess.resolveCreateKbDeptId`;`:166/198` `checkManageAccess` → `kbManageAccess.checkManageAccess`;`:216` `unbindAllRolesFromKb` → `kbRoleBindingAdmin.unbindAllRolesFromKb`;`:250` `checkAccess` → `kbReadAccess.checkReadAccess` | ~15 净 |
| `KnowledgeDocumentServiceImpl.java` | 字段拆 `KbReadAccessPort` + `KbManageAccessPort`;`:137` `checkManageAccess` → `kbManageAccess.checkManageAccess`;`:174/438/473/718/778` `checkDocManageAccess` → `kbManageAccess.checkDocManageAccess`;`:466` `checkAccess` → `kbReadAccess.checkReadAccess`;`:591` `checkDocSecurityLevelAccess` → `kbManageAccess.checkDocSecurityLevelAccess`;`:653` `checkAccess` → `kbReadAccess.checkReadAccess` | ~20 净 |
| `KnowledgeChunkServiceImpl.java` | 字段拆 `KbReadAccessPort` + `KbManageAccessPort`;`:88` `checkAccess` → `kbReadAccess.checkReadAccess`;`:106/264/313/342/384` `checkDocManageAccess` → `kbManageAccess.checkDocManageAccess` | ~12 净 |
| `RoleServiceImpl.java`(部分) | **本 commit 仅迁** `:408/450` `checkKbRoleBindingAccess` → `kbManageAccess.checkKbRoleBindingAccess`(其他 user 域改造留 c4)。**c3 后该类临时双注入(`KbAccessService` + `KbManageAccessPort` 字段共存),c4 完成剩余 `simulate / evict / isSuperAdmin` 调用迁移并物理删除 `KbAccessService` 字段。** 该临时状态在 c3 commit 上 mvn test 仍 green(`KbAccessService` 仍可用),`KbAccessServiceImpl` 仍 implements 全部接口,Spring 注入两个字段指向同一个 bean,无运行时差异。 | 2 |
| `framework/CLAUDE.md` | "security/port — **8 个 Port**"(原"7 个 Port",line 22)+ port 列表新增 `KbRoleBindingAdminPort` | +2 |

c3 总计:**5 文件 + 1 doc / ~50 行**

### 2.6 user 域 controller + service 迁移(c4, 8 main + 1 framework port)

| 文件 | 改动 |
|---|---|
| `UserAdminGuard.java`(framework) | 接口加 `void checkRoleMutation(String roleDeptId)` 方法签名 + javadoc(从 `KbAccessService` 同名方法迁来) |
| `UserController.java:55/85/104/114` | 字段:`KbAccessService` → `CurrentUserProbe` + `UserAdminGuard`;`isSuperAdmin/isDeptAdmin` → `CurrentUserProbe`;`checkUserManageAccess` → `UserAdminGuard.checkUserManageAccess` |
| `RoleController.java:39/43/57/73/90/112/119/120/133` | 字段:`KbAccessService` → `UserAdminGuard` + `CurrentUserProbe`;`checkRoleMutation` → `UserAdminGuard.checkRoleMutation`(c4 加的方法);`checkAssignRolesAccess` → `UserAdminGuard.checkAssignRolesAccess`;`checkAnyAdminAccess` → `UserAdminGuard.checkAnyAdminAccess`;`checkUserManageAccess` → `UserAdminGuard.checkUserManageAccess`;`isSuperAdmin` → `CurrentUserProbe.isSuperAdmin` |
| `AccessController.java:50/60/72/73/83/93` | 字段:`KbAccessService` → `UserAdminGuard`;5 处 `checkAnyAdminAccess` + 1 处 `checkUserManageAccess` 全部走 `UserAdminGuard` |
| `SysDeptController.java:45/49/55` | 字段:`KbAccessService` → `UserAdminGuard`;`checkAnyAdminAccess` → `UserAdminGuard.checkAnyAdminAccess` |
| `DashboardController.java:40/63` | 字段:`KbAccessService` → `CurrentUserProbe`(只用 `isSuperAdmin/isDeptAdmin`) |
| `UserServiceImpl.java:55/67/91/149/152/161/162` | 字段拆:`KbAccessService` → `CurrentUserProbe` + `UserAdminGuard` + `SuperAdminInvariantGuard`;6 处调用对应替换 |
| `RoleServiceImpl.java`(c4 部分) | 字段最终拆为 4 个:`SuperAdminInvariantGuard / KbAccessCacheAdmin / KbManageAccessPort(已 c3 迁) / CurrentUserProbe`(**核对修正:不含 `UserAdminGuard`** — 该 guard 在 `RoleServiceImpl` 内不被需要);`:111/142/363` `simulateActiveSuperAdminCountAfter` → `superAdminGuard`;`:380/512` `evictCache` → `cacheAdmin`;`:480` `isSuperAdmin` → `currentUserProbe`;**物理删除 `KbAccessService` 字段** |
| `AccessServiceImpl.java:66/183` | 字段:`KbAccessService` → `KbReadAccessPort`;`:183` 单 KB `getMaxSecurityLevelForKb(userId, kb.getId())` → 在循环外一次 `Map<String, Integer> levels = kbReadAccess.getMaxSecurityLevelsForKbs(userId, accessibleKbIds);` 循环内 `levels.getOrDefault(kb.getId(), 0)`。**仅移除 `KbAccessService` 注入依赖,不修 caller-context 语义**(Inv H);target-aware 修复留 PR3 `KbAccessCalculator` |

c4 总计:**8 main + 1 framework port = 9 文件 / ~400 行**

### 2.7 测试文件改造(分布在 c1-c6,共 19 既有文件 + 3 新建)

按 commit 分布。改造模板:`mock(KbAccessService)` → `mock(对应小 port)`;断言逻辑保留(测试方法逻辑不变,仅 mock 类型替换)。

#### c1 改的测试(scope/resolver 相关,~3 文件 + 新建 2 个)

| 测试 | 改动 |
|---|---|
| **新建** `KbScopeResolverImplTest` | T5 三态契约(10 测试) |
| **新建** `AccessScopeServiceContractTest` | T6 wrapper-capture(6 测试,2 service × 3 scope state) |
| `KnowledgeBaseControllerScopeTest` | 注入 mock 从 `KbAccessService` 改 `KbScopeResolver`;断言 scope 行为基于 `AccessScope` 三态 |
| `PageQueryFailClosedTest` | mock `KbReadAccessPort`(替代 `KbAccessService`);断言 `pageQuery(req, scope)` 在 `Ids(empty)` 时返空 page |
| `TestServiceBuilders.java` | builder 加 `withScopeResolver` setter;`KnowledgeBaseService` / `KnowledgeDocumentService` 构造方法签名变更同步 |

#### c2 改的测试(RAG,~2 文件)

| 测试 | 改动 |
|---|---|
| `RAGChatServiceImplSourcesTest` | mock 从 `KbAccessService` 换 `KbReadAccessPort`;`when(kbReadAccess.checkReadAccess(...))` |
| `MultiChannelRetrievalEnginePostProcessorChainTest` | mock 从 `KbAccessService.getMaxSecurityLevelsForKbs` 换 `KbReadAccessPort.getMaxSecurityLevelsForKbs` |

#### c3 改的测试(KB/Doc/Chunk service,~7 文件)

| 测试 | 改动 |
|---|---|
| `KnowledgeBaseServiceAuthBoundaryTest` | `mock(KbAccessService)` → `mock(KbManageAccessPort) + mock(KbReadAccessPort) + mock(KbRoleBindingAdminPort)`;3 个测试方法 stub 切换 |
| `KnowledgeDocumentServiceAuthBoundaryTest` | 同上(无 RoleBinding);9 个测试方法切换 |
| `KnowledgeChunkServiceAuthBoundaryTest` | `mock(KbReadAccessPort) + mock(KbManageAccessPort)`;7 个测试方法切换 |
| `KnowledgeBaseServiceImplDeleteTest` | mock 切换;断言 `unbindAllRolesFromKb` 调用走 `KbRoleBindingAdminPort` |
| `KnowledgeDocumentServiceImplFindMetaTest` | mock 字段类型替换 |
| `KnowledgeDocumentServiceImplTest` | mock 字段类型替换 |
| `RoleServiceAuthBoundaryTest`(c3 部分) | mock 改 `KbManageAccessPort`(`checkKbRoleBindingAccess`) |

#### c4 改的测试(user 域,~4 文件)

| 测试 | 改动 |
|---|---|
| `RoleControllerMutationAuthzTest` | mock 从 `KbAccessService` 换 `UserAdminGuard` + `CurrentUserProbe` |
| `RoleServiceImplDeleteTest` | mock 拆 4 个:`SuperAdminInvariantGuard / KbAccessCacheAdmin / KbManageAccessPort / CurrentUserProbe` |
| `RoleServiceImplDeletePreviewTest` | 同上 |
| `AccessServiceImplTest` | mock 从 `KbAccessService` 换 `KbReadAccessPort`;断言 `getMaxSecurityLevelsForKbs` 批量调用 + map 解构(T8 — 普通迁移断言,**非负面契约**;测试方法 javadoc 注明"PR3 KbAccessCalculator 覆盖,本 PR 仅移除注入") |

#### c5 改的测试

| 测试 | 改动 |
|---|---|
| `KbAccessServiceImplTest` | **不动**(它就是测 `KbAccessServiceImpl` 实现,接口仍存在) |
| `KbAccessServiceSystemActorTest` | **不动**(同上,system bypass / fail-closed 行为是实现层) |

#### c6 改的测试(ArchUnit,1 新建)

| 测试 | 改动 |
|---|---|
| **新建** `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/KbAccessServiceRetirementArchTest` | exact-class allowlist;不扫描测试包(`ImportOption.DoNotIncludeTests`) |

---

## 3. Test & Acceptance Contracts

### 3.1 沿用 PR1 不变量测试(零改动)

PR1 落地的契约测试是 PR2 regression 基线,**全部保持 green,不改测试代码**:

| 测试 | 锁的契约 | PR2 影响 |
|---|---|---|
| `KbAccessServiceSystemActorTest`(10 测试) | T2a system bypass + T3 missing-context throw | 0 改动 |
| `KbAccessServiceImplTest` | 决策逻辑(SUPER/DEPT/USER 路径) | 0 改动 |
| `PermissionBoundaryArchTest`(PR1 follow-up) | controller 不调 5 个 KB-scoped check | 0 改动 |

### 3.2 新增契约测试(T5-T7,T8 不独立)

#### T5 — `KbScopeResolver` 三态契约(c1,新建 `KbScopeResolverImplTest`)

```
T5.1  resolveForRead(null) → AccessScope.empty()
T5.2  resolveForRead(LoginUser{userId=null}) → AccessScope.empty()
T5.3  resolveForRead(SUPER_ADMIN) → AccessScope.all()
      AND verifyNoInteractions(kbReadAccessPort) — 证明 SUPER 路径不查 RBAC
T5.4  resolveForRead(USER) → 委托 kbReadAccessPort.getAccessScope(userId, READ)
      (mock 返回 Ids({"kb-1"}),resolver 返回同一对象)
T5.5  resolveForOwnerScope(SUPER_ADMIN) → all()
T5.6  resolveForOwnerScope(DEPT_ADMIN, deptId="d1") → ids(kbMetadataReader.listKbIdsByDeptId("d1"))
T5.7  resolveForOwnerScope(DEPT_ADMIN, deptId=null) → empty()  (数据异常防御)
T5.8  resolveForOwnerScope(USER) → empty()
T5.9  resolveForOwnerScope(null) → empty()
T5.10 KbScopeResolverImpl 不直接依赖 UserContext / CurrentUserProbe
      实现 1(单测 ArchUnit 子规则,见 §3.2 T7 兄弟):
        - noClasses().that().haveSimpleName("KbScopeResolverImpl")
            .should().dependOnClassesThat().haveFullyQualifiedName(UserContext.class.getName())
        - noClasses().that().haveSimpleName("KbScopeResolverImpl")
            .should().dependOnClassesThat().areAssignableTo(CurrentUserProbe.class)
      实现 2(verification 脚本附加 gate,收窄到 import/类型用法,避开 javadoc 误报):
        rg "^import .*\.(UserContext|CurrentUserProbe);" KbScopeResolverImpl.java → 应空
        rg "UserContext\.|CurrentUserProbe\b" KbScopeResolverImpl.java → 应空(排除 javadoc 内提及)
      约定:KbScopeResolverImpl javadoc 不直接出现 `UserContext` / `CurrentUserProbe` 符号
           (要描述折中,使用"caller-context port implementation" 等抽象 wording)
```

#### T6 — `AccessScope` 跨边界 service 解构契约(c1,新建 `AccessScopeServiceContractTest`)

参数化测试,覆盖 2 个 service entry × 3 种 scope 状态 = 6 case:

```
For each service in [knowledgeBaseService.pageQuery, knowledgeDocumentService.search]:

  T6.A  All:
    when:  scope = AccessScope.all()
    then:  verify(mapper).<query>(any(), wrapperCaptor.capture()) called once
           AND captor.getValue().getCustomSqlSegment() does NOT contain " IN "

  T6.B  Ids(empty):
    when:  scope = AccessScope.ids(emptySet)
    then:  verifyNoInteractions(mapper)
           AND result == empty page/list

  T6.C  Ids(nonEmpty):
    when:  scope = AccessScope.ids(Set.of("kb-1","kb-2"))
    then:  verify(mapper).<query>(any(), wrapperCaptor.capture()) called once
           AND captor.getValue().getParamNameValuePairs().values() contains "kb-1" AND "kb-2"
           AND captor.getValue().getCustomSqlSegment() contains " IN "
```

测的是 wrapper 行为(SQL 含 IN + 参数包含 kbId),**不**测 `Wrappers.lambdaQuery()` 静态工厂调用次数(Mockito 不适合验证静态工厂)。

#### T7 — `KbAccessServiceRetirementArchTest`(c6,新建,**唯一 KbAccessService retirement ArchUnit gate**)

注:T5.10 的两条 resolver 子规则(`KbScopeResolverImpl` 不依赖 `UserContext` / `CurrentUserProbe`)是**不同主题**的 ArchUnit gate(锚 Inv G,c1 引入)。两个文件分别承担:`KbAccessServiceRetirementArchTest`(本节 T7,锚 Inv E)+ `KbScopeResolverArchTest` 或归并到 `PermissionBoundaryArchTest`(T5.10 子规则,c1 落)— 由 plan 阶段决定文件归属。

```java
@ArchTest
static final ArchRule new_code_must_not_inject_god_kb_access_service =
    noClasses()
        .that().doNotHaveFullyQualifiedName(KbAccessService.class.getName())
        .and().doNotHaveFullyQualifiedName(KbAccessServiceImpl.class.getName())
        .should().dependOnClassesThat().areAssignableTo(KbAccessService.class)
        .because("PR2 退役 KbAccessService god-service。Exact-class allowlist:仅 "
               + "KbAccessService 接口本身 + KbAccessServiceImpl 委托者实现。"
               + "包级 allowlist 会让 user.service.impl 下其他类(如 RoleServiceImpl)"
               + "被豁免,规则失效。"
               + "Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR2");
```

**covered patterns**:ArchUnit 基于 bytecode dependency,覆盖**字段注入 / 局部变量类型 / 方法参数 / 返回类型 / 强制转型**等真实类型依赖。**纯 unused import 不形成 bytecode 依赖**,不被 ArchUnit 抓 — 由 verification 脚本 Gate 1(`rg -l "KbAccessService"` 文件级 allowlist)覆盖。两层互补:Gate 1 抓字符串引用(含 unused import / javadoc),ArchUnit 抓真实代码依赖。

**explicit allowlist**:`KbAccessService.class.getName()`(接口本身,自我引用如 `default` 方法签名)+ `KbAccessServiceImpl.class.getName()`(`implements KbAccessService` 锚点)。测试包默认被 `@AnalyzeClasses(importOptions = ImportOption.DoNotIncludeTests.class)` 排除。

#### T8 — `AccessServiceImpl` batch 迁移断言(c4,在 `AccessServiceImplTest` 内,**非独立契约**)

断言 3 条(降级为普通迁移测试,避免锁定 caller-context 已知缺陷):

```
- getMaxSecurityLevelsForKbs(userId, accessibleKbIds) 被单次调用
- map.getOrDefault(kbId, 0) 行为(map 含 kb-1=2 时 result.kb-1.level=2;不含 kb-x 时 result.kb-x.level=0)
- 测试方法 javadoc 注明:
  "PR2 改为批量版仅移除 KbAccessService 注入。caller-context 泄漏未修,
   admin-views-target 路径 PR3 KbAccessCalculator 覆盖。"
```

**不构造** SUPER caller / USER target 的负面语义断言 — 避免 PR3 强制翻转测试,reviewer 误读"这是期望行为"。

### 3.3 现有 boundary test 改造矩阵(c1-c4 分批)

PR1 留的 4 个 `*ServiceAuthBoundaryTest` 是 service 层信任边界的"锁"。PR2 改造它们的 mock 类型,**测试方法逻辑不变**:

| 测试 | mock 改造 | commit | 测试方法数 |
|---|---|---|---|
| `KnowledgeBaseServiceAuthBoundaryTest` | `KbAccessService` → `KbManageAccessPort + KbReadAccessPort + KbRoleBindingAdminPort` | c3 | 3 |
| `KnowledgeDocumentServiceAuthBoundaryTest` | `KbAccessService` → `KbManageAccessPort + KbReadAccessPort` | c3 | 9 |
| `KnowledgeChunkServiceAuthBoundaryTest` | `KbAccessService` → `KbReadAccessPort + KbManageAccessPort` | c3 | 7(原 6 + 1 辅助) |
| `RoleServiceAuthBoundaryTest` | `KbAccessService` → `KbManageAccessPort`(c3 部分,仅 `checkKbRoleBindingAccess`) | c3 | 2 |

改造模板:
```java
// Before (PR1):
KbAccessService kbAccessService = mock(KbAccessService.class);
doThrow(new ClientException("denied")).when(kbAccessService).checkManageAccess("kb-1");

// After (PR2 c3):
KbManageAccessPort kbManageAccess = mock(KbManageAccessPort.class);
doThrow(new ClientException("denied")).when(kbManageAccess).checkManageAccess("kb-1");
```

断言逻辑保留:`assertThrows(ClientException.class, () -> service.entry(...))` + `verify(port).checkXxx(...)`。

### 3.4 Verification 脚本(c6 落盘)

`docs/dev/verification/permission-pr2-kb-access-retired.sh`(B+C 双 gate):

```bash
#!/usr/bin/env bash
# PR2 verification: KbAccessService god-service retirement complete.
# Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §3.4
# Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR2

set -euo pipefail
TARGET='bootstrap/src/main/java'

if ! command -v rg >/dev/null 2>&1; then
  echo "ERROR: ripgrep (rg) not found on PATH — cannot run PR2 retirement gate." >&2
  exit 2
fi

# Gate 1: file-level allowlist — KbAccessService string only in 2 files
EXPECTED=$(cat <<'EOF'
bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java
bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
EOF
)
ACTUAL=$(rg -l "KbAccessService" "${TARGET}" | sort)

if [ "${ACTUAL}" != "${EXPECTED}" ]; then
  echo "FAIL: KbAccessService referenced in unexpected files."
  echo "Expected:"
  echo "${EXPECTED}"
  echo "Actual:"
  echo "${ACTUAL}"
  exit 1
fi

# Gate 2: injection-level — no field of type KbAccessService in any class
if rg -n "private\s+final\s+KbAccessService\b" "${TARGET}" --quiet; then
  echo "FAIL: KbAccessService still injected as field:"
  rg -n "private\s+final\s+KbAccessService\b" "${TARGET}"
  exit 1
fi

echo "OK: KbAccessService retired."
echo "  - Only KbAccessService.java + KbAccessServiceImpl.java reference the type"
echo "  - No 'private final KbAccessService' field anywhere in main code"
```

### 3.5 集成 smoke(手工,c6 commit message 要求)

PR1 留的 5 路径仍适用(语义不变);PR2 引入 `KbScopeResolver`,新增 1 路径:

| Path | 期望 |
|---|---|
| 1. OPS_ADMIN 重命名自家 KB | HTTP 200 |
| 2. FICC_USER 重命名 OPS KB | HTTP 200 + `success=false` + `无管理权限` |
| 3. 文档上传 → MQ → executeChunk | 完成,无 missing-user context |
| 4a. 匿名 GET KB | HTTP 200 + `未登录或登录已过期` |
| 4b. 缺失 UserContext service-level | `ClientException("missing user context")` |
| **6 (新增)**. `GET /knowledge-base?scope=owner` 由 DEPT_ADMIN ops_admin 调用 | HTTP 200 + 列表只含 OPS-* KB ID(本部门 KB),不含 FICC KB。锁住 `resolveForOwnerScope(DEPT_ADMIN)` 走 `kbMetadataReader.listKbIdsByDeptId(deptId)` 路径 |

### 3.6 测试基线增量

- **PR1 既有契约测试**:33 个(boundary 21 + system actor 10 + fail-closed 2)— 不改
- **PR2 新增**:约 17-18 个(T5 三态契约 ~10 + T6 wrapper-capture ~6 + T7 ArchUnit 1 + T8 不独立计数)
- **PR2 既有 mock 改造**:19 文件 / ~61 引用(逻辑不变,仅 mock 类型替换)
- **验收口径**:以 `mvn -pl bootstrap test` 输出为准,**仅已知 baseline failures,无新增失败**;**不**写死精确测试数字
- **baseline-red 维持 PR1 列表**:`MilvusCollectionTests` / `InvoiceIndexDocumentTests`×3 / `IntentTreeServiceTests.initFromFactory` / `VectorTreeIntentClassifierTests`×4 / `PgVectorStoreServiceTest.testChineseCharacterInsertion`

---

## 4. Commit Sequence & Rollback

### 4.1 6 commit 完整序列

每个 commit 独立 compile;每 commit 跑 targeted tests 退出 0;PR pre-merge 跑全量 baseline 对比无新增失败。

#### c1 — KbScopeResolver + scope 跨边界改造

**diff 边界**:见 §2.4。

**commit message 模板**:
```
refactor(security): 引入 KbScopeResolver,scope 跨边界统一为 AccessScope (PR2 c1)

- KbScopeResolver 接口 + 实现,resolveForRead / resolveForOwnerScope
- resolver 不读 ThreadLocal,SUPER/DEPT_ADMIN 判定从 LoginUser.getRoleTypes() 取
- KnowledgeBaseController + SpacesController + KnowledgeDocumentController 改注 resolver
- KnowledgeBasePageRequest.accessibleKbIds 物理删除
- KnowledgeBaseService.pageQuery / KnowledgeDocumentService.search 加 AccessScope 入参
- service 解构模板:All 不加 in / Ids(empty) 短路返空 / Ids(nonEmpty) in()
- 不变量 F + G(部分):scope 跨边界 sealed type;resolver 不直接读 ThreadLocal
- 测试:T5 三态契约(10) + T6 wrapper-capture(6) + 既有 boundary mock 调整

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR2 commit 1
Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §2.4 §3.2 T5/T6
```

**c1 验证**:`mvn -pl bootstrap test -Dtest=KbScopeResolverImplTest,AccessScopeServiceContractTest,KnowledgeBaseControllerScopeTest,PageQueryFailClosedTest`

#### c2 — RAG 热路径迁 KbReadAccessPort

**diff 边界**:见 §2.3。

**commit message 模板**:
```
refactor(rag): RAG 热路径迁 KbReadAccessPort,移除 KbAccessService 注入 (PR2 c2)

- RAGChatServiceImpl:134 kbAccessService.checkAccess → kbReadAccess.checkReadAccess
  + 移除 KbAccessService 字段(kbReadAccess 已注入,L98)
- MultiChannelRetrievalEngine:262 字段类型替换,getMaxSecurityLevelsForKbs 调用 receiver 改名
- 测试 mock 类型同步替换

Roadmap: §3 阶段 A · PR2 commit 2
Spec: §2.3 §3.3
```

**c2 验证**:`mvn -pl bootstrap test -Dtest=RAGChatServiceImplSourcesTest,MultiChannelRetrievalEnginePostProcessorChainTest`

#### c3 — KB/Doc/Chunk service 内部 + 新建 KbRoleBindingAdminPort

**diff 边界**:见 §2.5。

**commit message 模板**:
```
refactor(security): KB/Doc/Chunk service 内部迁小 port,新增 KbRoleBindingAdminPort (PR2 c3)

- 新增 KbRoleBindingAdminPort(单方法 unbindAllRolesFromKb)
  + KbAccessServiceImpl implements 列表 7→8
- KnowledgeBaseServiceImpl:字段拆 3 个,5 处 check 替换;
  unbindAllRolesFromKb 改走 KbRoleBindingAdminPort
- KnowledgeDocumentServiceImpl:字段拆 2 个,9 处 check 替换
- KnowledgeChunkServiceImpl:字段拆 2 个,6 处 check 替换
- RoleServiceImpl:仅迁 checkKbRoleBindingAccess(临时双注入,c4 完成剩余迁移)
- framework/CLAUDE.md 同步 8 port

Roadmap: §3 阶段 A · PR2 commit 3
Spec: §2.1 §2.5 §3.3
```

**c3 验证**:`mvn -pl bootstrap test -Dtest="*ServiceAuthBoundaryTest,KnowledgeBaseServiceImpl*Test"`

#### c4 — user 域 controller + service 全迁

**diff 边界**:见 §2.6。

**commit message 模板**:
```
refactor(security): user 域 controller/service 迁 small ports (PR2 c4)

- UserAdminGuard 加 checkRoleMutation 方法(从 KbAccessService 同名方法迁来)
- 5 个 controller 字段 + caller 替换:UserController / RoleController /
  AccessController / SysDeptController / DashboardController
- UserServiceImpl:CurrentUserProbe + UserAdminGuard + SuperAdminInvariantGuard
- RoleServiceImpl:c3 剩余 simulate/evict/isSuperAdmin 迁,删除 KbAccessService 字段
  字段最终拆为 4 个:SuperAdminInvariantGuard + KbAccessCacheAdmin +
  KbManageAccessPort + CurrentUserProbe
- AccessServiceImpl:183 批量版 + map.getOrDefault(kbId, 0)
  注:caller-context 语义未修,PR3 KbAccessCalculator 覆盖

Roadmap: §3 阶段 A · PR2 commit 4
Spec: §2.6 §3.4
```

**c4 验证**:targeted tests `mvn -pl bootstrap test -Dtest="*UserController*Test,*RoleController*Test,*AccessController*Test,*UserServiceImpl*Test,*RoleServiceImpl*Test,*AccessServiceImpl*Test"` + pre-merge 全量 baseline 对比(无新增失败)。

#### c5 — KbAccessService 接口 @Deprecated

**diff 边界**:见 §2.2。

**commit message 模板**:
```
refactor(security): KbAccessService 接口标 @Deprecated,实现保留 implements (PR2 c5)

- @Deprecated(forRemoval = false) — 接口签名零改动
- javadoc 改写为"PR2 退役 god-service,新代码请注入 framework.security.port 8 个具体 port;
  PR3 + sharing PR D7 完成后开 cleanup PR 物理移除"
- KbAccessServiceImpl 保留 implements KbAccessService 作 ArchUnit allowlist 锚点
- 实现层零改动,所有 7+1 port 委托不变
- main 代码无任何 caller(c1-c4 已全部迁走,c6 ArchUnit 强制)

Roadmap: §3 阶段 A · PR2 commit 5
Spec: §2.2
```

**c5 验证**:`mvn -pl bootstrap test`(pre-merge baseline 对比,确认 `@Deprecated` 不破坏既有测试)

#### c6 — ArchUnit + verification 脚本

**diff 边界**:`KbAccessServiceRetirementArchTest.java`(新建)+ `permission-pr2-kb-access-retired.sh`(新建)。

**commit message 模板**:
```
test(security): KbAccessService god-service retirement gates (PR2 c6)

- KbAccessServiceRetirementArchTest:exact-class allowlist
  noClasses().doNotHaveFullyQualifiedName(KbAccessService) AND
  doNotHaveFullyQualifiedName(KbAccessServiceImpl)
  .should().dependOnClassesThat().areAssignableTo(KbAccessService)
- verification 脚本 docs/dev/verification/permission-pr2-kb-access-retired.sh
  Gate 1:rg -l 输出仅 KbAccessService.java + KbAccessServiceImpl.java
  Gate 2:rg "private final KbAccessService\b" 输出空
  EOF heredoc 形式定 EXPECTED,Windows Git Bash 兼容
- 测试包默认 ImportOption.DoNotIncludeTests 排除

Roadmap: §3 阶段 A · PR2 commit 6
Spec: §3.2 T7 §3.4
```

**c6 启用前**:必须先跑 `bash docs/dev/verification/permission-pr2-kb-access-retired.sh` 退出 0(确认 main 代码除 `KbAccessService.java` / `KbAccessServiceImpl.java` 两个文件外零引用),然后再启用 ArchUnit。

**c6 验证**:`mvn -pl bootstrap test -Dtest=KbAccessServiceRetirementArchTest` + 重跑 verification 脚本退出 0。

### 4.2 Rollback 策略(commit 级独立可 revert)

#### Rollback 单元

**unit 1 — c6(纯护栏单元,最易 revert)**:
- 触发:c6 ArchUnit 红 / verification 脚本失败
- 动作:`git revert <c6-sha>` 单 commit revert
- 影响:规则消失,代码不变;c5 `@Deprecated` 可保留(独立的"接口标记"决策)
- c5 一般无需 revert,除非主动决定撤回 deprecated

**unit 2 — c4(user 域改造单元)**:
- 触发:user 域 controller/service 迁移引入回归(`UserAdminGuard.checkRoleMutation` 行为不一致 / `AccessServiceImpl:183` 批量改造结果偏差)
- 动作:`git revert <c4-sha>`
- 影响:user 域回退到注 `KbAccessService` god-service。c1-c3 + c5(若 c5 已落)保留,但 c5 标 deprecated 后这些回退的 caller 会 IDE warn。**若 c5/c6 已落,需级联 revert c6**(否则 ArchUnit 红)

**unit 3 — c1/c2/c3(基础设施单元,互相独立)**:
- 触发:KbScopeResolver 行为偏差 / RAG 热路径回归 / KbRoleBindingAdminPort 设计有误
- 动作:按 sha 单 commit revert(c1/c2/c3 互相独立)

#### TestServiceBuilders 级联 caveat

`TestServiceBuilders` 在 c1/c3/c4 都被修改(`withScopeResolver` setter / mock port 工厂 / 4 字段拆分)。任意 commit revert 可能产生测试支持类冲突。按 git 提示解决冲突后,**必须重新跑对应 targeted tests** 确认 mock 工厂状态自洽。

如冲突大,可暂时把 `TestServiceBuilders` 锁回到 PR1 状态 + 在每个仍存在的 commit 内 inline mock(代价是测试代码冗余,但不阻塞 revert)。

#### 严重情况(c1-c4 多 commit 联动失败)

整 PR2 revert(`git revert -m 1 <merge-commit-sha>`)— 整 PR 回滚:
- 接口形态回到 PR1 末态
- ArchUnit 规则 c6 不存在 → 不会拦
- `KbAccessService` 仍可注入 → 业务代码可继续工作
- 风险:roadmap 阶段 A 推迟,但 PR1 不变量 A-D 仍生效,生产可继续运行

### 4.3 Pre-merge 验证清单(PR review 必跑)

```
✓ T5 / T6 测试 green(c1 引入)
✓ T7 ArchUnit 测试 green(c6 引入)
✓ T8 batch 迁移断言 green(c4 引入,在 AccessServiceImplTest 内)
✓ 4 个 ServiceAuthBoundaryTest mock 类型迁完后全 green
✓ mvn -pl bootstrap test → 仅已知 baseline failures,无新增失败
✓ mvn spotless:check 干净
✓ rg gate(verification 脚本)双 gate 退出 0
✓ ArchUnit 规则启用,exact-class allowlist 仅 2 类
✓ Inv F:`rg "Set<String>.*accessibleKbIds|null.*accessibleKbIds" bootstrap/src/main/java`
       在 controller/service 边界应为空(KnowledgeBasePageRequest.accessibleKbIds 已删)
✓ Inv G:`rg "UserContext|CurrentUserProbe" bootstrap/src/main/java/.../KbScopeResolverImpl.java` 应空
✓ 6 路径 smoke(PR1 5 + PR2 新增 scope=owner)
```

### 4.4 PR description 模板

```markdown
## PR2 — KbAccessService god-service retirement (Phase A · PR2)

### Scope
- 退役 KbAccessService god-service:RAG 热路径 + KB/Doc/Chunk service + user 域 controller/service
- 引入 KbScopeResolver,scope 跨边界统一为 AccessScope sealed type
- framework.security.port 7→8 个 port(新增 KbRoleBindingAdminPort)
- UserAdminGuard 加 checkRoleMutation 方法
- KbAccessService 接口 @Deprecated,实现保留 implements 锚点
- ArchUnit + verification 脚本双护栏

### Out of Scope (deferred)
- KbAccessCalculator(target-aware)→ PR3
- AccessServiceImpl:183 caller-context 语义修复 → PR3(本 PR 仅移除注入)
- SpacesController 抽 SpacesService + Controller 不直注 mapper → 独立 P0 重构 PR
- Spaces count 口径修正(deleted/disabled filter)→ 独立业务修复 PR
- KbAccessService 物理移除(implements + 接口文件)→ cleanup PR(PR3 + sharing PR D7 后)

### Verification
- [x] T5 三态契约 + T6 wrapper-capture + T7 ArchUnit 全 green
- [x] mvn -pl bootstrap test:仅已知 baseline failures,无新增失败
- [x] verification 脚本双 gate 退出 0
- [x] 6 路径 smoke 通过(5 PR1 既有 + 1 PR2 新增 scope=owner)

### Roadmap
- docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR2
- spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md
```

---

## 5. Risks & Known Gaps

### 5.1 PR2 已知风险

| # | 风险 | 触发条件 | mitigation |
|---|---|---|---|
| **R1** | scope 跨边界沿用 `Set<String>` + `null` 的旧约定漂回某 service 入参签名 | 未来某 PR 给 `pageQuery` / `search` 加 overload(避免改 caller 签名),`Set<String> accessibleKbIds = null` 被重新引入 | Inv F + ArchUnit 增量(写进 roadmap §2.3 反模式黑名单);PR review 必看 service 接口签名 diff |
| **R2** | KbScopeResolver 未来因业务需求被加 `CurrentUserProbe` / `UserContext` 注入(打破 Inv G) | 下游 PR 觉得"resolver 应该自己判 SUPER_ADMIN"而绕过入参 LoginUser | T5.10 ArchUnit 子规则:**两条独立规则**,`KbScopeResolverImpl` 不依赖 `UserContext` + `KbScopeResolverImpl` 不依赖 `CurrentUserProbe`(避免 ArchUnit `orShould` 易写错);verification 脚本 `rg "UserContext|CurrentUserProbe" KbScopeResolverImpl.java` 应空 |
| **R3** | c4 改造遗漏某 user 域 caller,c6 ArchUnit 启用时 build 红 | user 域 8 文件改造量大(~400 行),手工 grep 漏掉一处 | c6 commit message 强制要求先跑 verification 脚本 Gate 2(`rg "private final KbAccessService\b"` 输出空);**c4 验证**:targeted tests + pre-merge 全量 baseline 对比;PR review 必跑 verification 脚本 |
| **R4** | `AccessServiceImpl:183` 批量改造的性能特征因 cache 路径而异 | warm-cache 路径(`kb_security_level:{userId}` Redis hash 命中)原是 N 次 Redis get、0 次 DB;批量版当前实现不读该 cache,会每次查 DB | **cold path** 预计改善(原 N 次 DB roundtrip → 1 次 batch DB);**warm-cache 路径**可能绕过 Redis cache,性能特征改变。`AccessServiceImpl` 是 Access Center admin 视图,**非 RAG 热路径**,频次低,可接受此 tradeoff。**PR3 `KbAccessCalculator` + cache 装饰时统一处理** per-KB security level 缓存策略(roadmap §3 PR3 commit 2)。spec §5.1 + §5.3 显式标注 |
| **R5** | T8 降级为普通迁移断言后,PR3 修 caller-context 时缺少负面契约提醒 | PR3 reviewer 不知道这是"已知缺陷"vs"刚好这样" | spec §5.2 deferral 表显式列 `AccessServiceImpl:183 caller-context bug → PR3`;`AccessServiceImplTest` 被改造的测试方法 javadoc 写明"PR3 KbAccessCalculator 覆盖,本 PR 仅移除注入";PR3 spec brainstorming 时回查这条 |
| **R6** | `KbRoleBindingAdminPort` 单方法 minimal port 在 PR D7 sharing 产品化时被扩展,旧 caller 兼容性 | PR D7 加 `addRoleBinding` / `removeRoleBinding` 等方法,可能要把 `KbRoleBindingAdminPort` 拆 read/write 子接口 | `KbRoleBindingAdminPort` 在 framework 包,但目前仍是项目**内部工程边界**(无外部 SDK / 其他模块消费)。PR D7 可在同 repo 内按需扩展 / 拆分,并同步更新唯一 caller。**对外不承诺稳定 API** |
| **R7** | TestServiceBuilders 在 c1/c3/c4 都被修改,任意 commit revert 引入测试编译错 | rollback unit 2/3 触发时,test 工厂状态不自洽 | §4.2 rollback caveat 已说明:revert 后跑 targeted tests 验证。如冲突大,可暂时把 `TestServiceBuilders` 锁回 PR1 状态 + inline mock |
| **R8** | c4 user 域 controller `@SaCheckRole` 与 `UserAdminGuard.checkXxx` 同时存在的双重 check | PR1 已确立 controller 不去 KB-scoped check,但 user 域 controller 仍有 `UserAdminGuard.checkAnyAdminAccess` 等 caller(参考 PR1 spec §1 不变量 C — user 域 controller 内 admin gate 是合规的) | spec §1.1 已写明"PR1 已建立的 5 个 KB-scoped check 不在 controller (继续);user 域 controller 注入 UserAdminGuard / CurrentUserProbe — 这是有意保留的 admin gate 模式,与 KB-scoped 不同语义"。PR2 不动这个语义 |

### 5.2 显式 deferral 完整清单(写进 PR description "Out of Scope")

| # | 延期项 | 接手 |
|---|---|---|
| 1 | **`KbAccessCalculator`** target-aware,消除 `AccessServiceImpl:325/:183` caller-context 泄漏 | **PR3**(roadmap §3 阶段 A · PR3 commit 1-2) |
| 2 | **`AccessServiceImpl:183` caller-context 语义修复** | **PR3**(同上) |
| 3 | **`StreamChatEventHandler` 显式 inject userId**(消除 ThreadLocal 伪登录) | **PR3 commit 4** |
| 4 | **chunk service internal helper 物理 visibility 收紧** / 拆 `KnowledgeChunkInternalService` | **PR3 之后** |
| 5 | **`SpacesController` 抽 `SpacesService`** + Controller 不直注 mapper | 独立 P0 重构 PR |
| 6 | **Spaces count 口径修正**(`.eq(deleted, 0)` / `enabled` / `status` filter) | 独立业务修复 PR |
| 7 | **`KbAccessService` 物理移除**(`KbAccessServiceImpl implements` 列表去掉 + 接口文件删除) | cleanup PR(PR3 + sharing PR D7 后) |
| 8 | **`RetrievalScopeBuilder` + RAG 入口 scope 统一**(`RagRequest.indexNames` 改 `kbIds + scopeMode`) | **PR4**(roadmap §3 阶段 B) |
| 9 | **OpenSearch metadata filter 强制带 `metadata.kb_id` + `range(security_level)`** | **PR5**(同阶段 B) |
| 10 | **retrieval security-level scope 深化 / 去残余全局天花板依赖**(roadmap §3 阶段 C "security_level 粒度修正"原标题)。**澄清**:`getMaxSecurityLevelsForKbs` 已是 per-KB map(PR1 后);PR6 目标是清除 `LoginUser.maxSecurityLevel` 字段在**决策路径**上的残余引用 + 添加跨 KB level 隔离的契约测试,与 PR2/PR3 已有的 batch port **不重复** | **PR6**(roadmap §3 阶段 C) |
| 11 | **sharing 产品化** `KbRoleBindingAdminPort` 扩展 + 前端 Sharing tab | **PR7-PR8**(roadmap §3 阶段 D) |
| 12 | **审计 audit_log + AOP 切面接 ports** | **PR9-PR10**(roadmap §3 阶段 E) |
| 13 | **访问申请流 `kb_access_request`** | **PR11-PR12**(roadmap §3 阶段 F) |
| 14 | **`KnowledgeBaseServiceImpl.update` 加 check**(无生产 caller) | 引入 caller 时再加 |
| 15 | **OpenFGA / OPA / Casbin 第三方授权框架** | 阶段 G,**至少推迟到阶段 F 完成**后再评估 |

### 5.3 已知 PR2 缺陷(写进 spec,不掩盖)

PR2 完成后,代码仍存在以下**已识别但本 PR 不修**的问题:

| 缺陷 | 位置 | 为什么不在 PR2 修 | 何时修 |
|---|---|---|---|
| `AccessServiceImpl:183` admin 看 target user 时回 admin 的 SUPER scope | `AccessServiceImpl.java:183` 改批量版后,`KbReadAccessPort.getMaxSecurityLevelsForKbs` 实现仍读 `UserContext` 判 SUPER/DEPT | PR2 范围是"退役 god-service",修语义 bug 需要 calculator(target-aware,接 `targetUserId` 显式参数);calculator 是 PR3 的核心 | **PR3** commit 1-2 |
| `resolveForRead(USER/DEPT_ADMIN)` 仍依赖 caller-context port implementation | `KbScopeResolverImpl.resolveForRead(...)` 调 `KbReadAccessPort.getAccessScope(userId, READ)`;该实现(`KbAccessServiceImpl.getAccessScope`)内部读 `UserContext` 判 SUPER/DEPT_ADMIN | resolver 本身已遵守 Inv G(自身不读 ThreadLocal,从 LoginUser 入参取);完全消除"caller-context port implementation 读 UserContext"需要 PR3 `KbAccessCalculator` 提供 target-aware 实现 | **PR3** calculator 落地后 |
| `KnowledgeBaseServiceImpl.update` 无授权 check(无生产 caller,所以不暴露) | `KnowledgeBaseServiceImpl.update(requestParam)` | PR1 spec §2.6 已显式列为延期项,无生产 caller 加 check 会形塑未设计的入口形态 | 引入 caller 时 |
| chunk service 内部 helper(`batchCreate` / `deleteByDocId` / `updateEnabledByDocId` / `listByDocId`)仅靠 javadoc 提示,无物理 visibility 保护 | `KnowledgeChunkServiceImpl` | PR1 spec §2.4 已显式接受此 tradeoff,接口方法本质 public,visibility 收紧需要拆 `KnowledgeChunkInternalService` | **PR3 之后** |
| `RoleServiceImpl` c3 后 c4 前临时双注入(`KbAccessService` + 4 小 port 同时注入) | c3 commit 末态 → c4 commit 中态 | 跨 commit 逐步迁移的物理结果,每个 commit 必须独立 compile + test green | c4 完成 |

### 5.4 风险触发后的应对(决策树)

```
PR2 merge 后某测试 / smoke / 生产环境异常
                 │
                 ▼
        异常源在哪个 commit?
        │                  │
   c5/c6(护栏)         c1-c4(改造)
        │                  │
        ▼                  ▼
  revert c6 单 commit   按依赖关系 revert 单 commit
        │                  │
        ▼                  ▼
   规则消失,代码不变   是否触动 TestServiceBuilders?
                       │              │
                       否             是
                       │              │
                       ▼              ▼
                       完成      重跑 targeted test
                                 + 解决 mock 工厂冲突
```

---

## 6. Decision Log

### 6.1 6 大决策汇总

| # | 决策项 | 锁定结果 | 决定的关键理由 |
|---|---|---|---|
| **D1** | 三个 port 缺口归属(`unbindAllRolesFromKb` / `checkRoleMutation` / `getMaxSecurityLevelForKb`) | `unbindAllRolesFromKb` → 新建 minimal `KbRoleBindingAdminPort`(单方法);`checkRoleMutation` → `UserAdminGuard.checkRoleMutation`;`getMaxSecurityLevelForKb` → caller 改批量版 + `map.getOrDefault(kbId, 0)`,**仅移除注入,不修 caller-context** | sharing 域写入口本就在 PR D7 要建 minimal port,提前一点零额外成本;`checkRoleMutation` 与 `checkAssignRolesAccess` 同语义自然归 `UserAdminGuard`;PR2 不越权修 caller-context,留 PR3 专责 |
| **D2** | KbScopeResolver 返回类型 | 返回 `AccessScope` sealed type;**不**塞进 web request DTO,通过 service 入参传(`pageQuery(req, scope)` / `search(kw, limit, scope)`);`KnowledgeBasePageRequest.accessibleKbIds` **物理删除**;resolver **不**注入 `CurrentUserProbe`,从入参 `LoginUser.getRoleTypes()` 取 | `AccessScope` 是项目检索路径单一类型源(roadmap §2 默认事实标准);消除 `Set<String> + null` 三态歧义;PR3 calculator 自然返回 `AccessScope`,PR2 类型对齐降低未来切换成本;Web DTO 保持纯用户输入,授权决策结果走 service 入参 |
| **D3** | ArchUnit 启用时机 | **严格末尾(c6)**:c1-c5 不维护 allowlist;c6 启用前跑 `rg` gate 确认 main 代码除 `KbAccessService.java` / `KbAccessServiceImpl.java` 两个文件外零引用;allowlist exact-class 仅 `KbAccessService` 接口 + `KbAccessServiceImpl` 委托者;**c5 保留** `KbAccessServiceImpl implements KbAccessService` 作 ArchUnit 锚点,**物理移除 implements 留 cleanup PR** | PR1 已有 grep 守门 + `PermissionBoundaryArchTest`(controller-only),review 阶段 c1-c5 漏迁能被发现;长 allowlist 渐进缩小增加 review 噪声;末尾启用让 ArchUnit 抓"未来回归"(主目的),不抓"过程中漏迁"(grep 抓) |
| **D4** | SpacesController admin 路径短路 | **A**:controller 内 `if (scope instanceof All) {全表 count}; if (scope instanceof Ids ids) {empty 短路 / nonEmpty .in()}; throw IllegalState`;`UserContext.hasUser()` 包 `get()` 把无登录契约交给 resolver;**保留** `SpacesController` 直注 mapper 的 P0 技术债,**SpacesService 抽取列入 Out of Scope**;PR2 不修 count 口径 | PR2 锁"零业务功能增量",抽 `SpacesService` 是 P0 报告挂的独立 S1;sealed type 解构是 Java 17 标准模式,与 `RAGChatServiceImpl` 已用一致;count 口径(deleted filter)是业务决策,不顺手改 |
| **D5** | 测试 mock 策略 + ArchUnit allowlist | **A**:测试按生产实际依赖拆 small port mock,`TestServiceBuilders` 加 setter;**不**引入测试专用 `KbAccessServiceMock`;`RoleServiceImpl` 拆 4 个字段:`SuperAdminInvariantGuard / KbAccessCacheAdmin / KbManageAccessPort / CurrentUserProbe`(**核对修正:不含 `UserAdminGuard`**);ArchUnit allowlist 用 **exact-class**(`doNotHaveFullyQualifiedName(KbAccessService)` AND `doNotHaveFullyQualifiedName(KbAccessServiceImpl)`),**不**用包级 | 测试 mock 类型与生产注入 1:1 对称;exact-class allowlist 让规则真正抓回归注入(包级 allowlist 让 `..user.service.impl..` 下其他类被豁免) |
| **D6** | grep gate 期望值 | **B+C 双 gate**:Gate 1 文件级 `rg -l "KbAccessService"` 输出仅 2 文件;Gate 2 注入级 `rg "private final KbAccessService\b"` 输出空;脚本 `docs/dev/verification/permission-pr2-kb-access-retired.sh`;`EXPECTED=$(cat <<'EOF' ... EOF)` heredoc 形式,Windows Git Bash 兼容;**不**检查 `wc -l` 数字 | 文件级 allowlist 稳定;注入级直接锚定"god-service 不再被生产注入"目标;ArchUnit 是 CI 级护栏,脚本是 review/smoke 级护栏,双层互补 |

### 6.2 Brainstorming Derivative Decisions(对未来 PR 仍有约束力)

PR2 brainstorming 阶段沉淀下来、对**后续 PR(PR3 / PR D7 / cleanup)仍然有效**的工程判断:

- **为什么 PR2 不抽 `SpacesService` 顺手解决 P0 报告 §跨域 import 标 S1?** PR2 范围是"god-service 退役",抽 `SpacesService` 是独立的 controller 分层重构。混在 PR2 里会让 reviewer 在权限决策与 controller 分层两个轴上同时审,review 难度倍增。该抽取列入 Out of Scope,等 PR2 + PR3 完成后开独立 PR。

- **为什么 PR2 不一次性物理移除 `KbAccessServiceImpl implements KbAccessService`?** 接口移除涉及编译时全表面影响:javadoc 引用 / 测试类(`KbAccessServiceImplTest` / `KbAccessServiceSystemActorTest`)mock 的接口 / `KbAccessServiceImpl` 自我引用。物理移除是独立 cleanup PR 范围,本 PR 用 `@Deprecated` 标记接口语义即足够,ArchUnit 用 exact-class allowlist 锚定。

- **为什么 PR2 内 `RoleServiceImpl` 字段拆 4 个而不是 3 个?** 4 个是核对实际 caller 后的精确数字(`SuperAdminInvariantGuard.simulateActiveSuperAdminCountAfter` + `KbAccessCacheAdmin.evictCache` + `KbManageAccessPort.checkKbRoleBindingAccess` + `CurrentUserProbe.isSuperAdmin`)。`UserAdminGuard` 在 `RoleServiceImpl` 内**不**被需要(它是 controller / `UserServiceImpl` 范围)。字段语义精准是 spec § 2.6 + § 3.4 的硬要求。

- **为什么 PR2 的 grep gate 不检查精确行数?** PR1 grep gate 检查 controller 内 5 个 KB-scoped check 调用——这是行级精确语义。PR2 retirement 是包级语义,行数受 javadoc / `@link` / 自我引用扰动。文件级 + 注入级双 gate 抓住意图("god-service 不再被生产注入"),不被 wording 漂移影响。

- **为什么 PR2 选 `AccessScope` 不选 `Set<String> + null`?** 项目已有 `AccessScope` sealed type 在 RAG 检索路径作为单一状态源(`RAGChatServiceImpl` line 125-130 已用)。PR2 不引入"两套 scope 类型表达同一概念"的不一致;PR3 `KbAccessCalculator` 自然返回 `AccessScope`,PR2 选 sealed type 让未来 PR3 切换 calculator 时 resolver 实现一行替换 — 选 `Set<String>` 则 PR3 还要回头改 resolver 返回类型。

- **为什么 PR2 不修 `AccessServiceImpl:183` 的 caller-context bug?** 该 bug 的修复需要 target-aware port(接 `targetUserId` 显式参数,而非读 `UserContext`)——这恰是 PR3 `KbAccessCalculator` 的设计目的。PR2 仅替换批量版 + 移除 `KbAccessService` 注入,**不修语义**。`AccessServiceImplTest` 改造的测试方法 javadoc 显式写明"PR3 KbAccessCalculator 覆盖",防止 reviewer 误读为修复。

- **为什么 PR2 的 `KbScopeResolver` 不依赖 `CurrentUserProbe`?** `CurrentUserProbe.isSuperAdmin()` 内部读 `UserContext`。resolver 入参 `LoginUser` 已携带 `getRoleTypes()`,直接 `user.getRoleTypes().contains(SUPER_ADMIN)` 判定。这是 Inv G 的形式化:resolver 自身**不**直接读 ThreadLocal。但 resolver 调的 `KbReadAccessPort.getAccessScope` 实现仍读 `UserContext` — 这是 PR3 calculator 的责任(target-aware 完全消除 ThreadLocal 依赖),PR2 不能在 resolver 层提前完成。

- **为什么 PR2 不顺手解 `RetrievalScopeBuilder` / OpenSearch metadata filter?** roadmap §3 阶段 B 的 PR4-PR5。它们是检索链路改造,与 god-service 退役独立。PR2 引入 `AccessScope` 跨边界后,阶段 B 的 `RetrievalScopeBuilder` 自然继承这个类型。

---

## 7. References

- **PR1 spec**:`docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md`(不变量 A-D / 系统态显式 / fail-closed 守卫)
- **PR1 plan**:`docs/superpowers/plans/2026-04-26-permission-pr1-controller-thinning.md`
- **Permission roadmap**:`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 A · PR2(本 spec 实现的 6 commit 模板)
- **P0 architecture audit**:`docs/dev/followup/2026-04-26-architecture-p0-audit-report.md` §"Deprecated 授权接口使用"表(本 spec caller 清单的源)
- **Enterprise permissions architecture**:`docs/dev/research/enterprise_permissions_architecture.md`(业务视角 Phase 0-8)
- **PR1 verification**:`docs/dev/verification/permission-pr1-controllers-clean.sh` + `permission-pr1-smoke.md`
- **PR2 verification(本 spec 产出)**:`docs/dev/verification/permission-pr2-kb-access-retired.sh`(c6 落盘)
