# PR3 — Access Calculator + ThreadLocal Guards (Stage A 收口)

- **日期**：2026-04-27
- **作者**：brainstorming session（zk2793126229@gmail.com + Claude）
- **范围**：把权限计算从"`userId` 装饰参数 + 实现偷读 ThreadLocal"的形态切到"显式 `KbAccessSubject` + 纯函数 calculator + factory 单点 ThreadLocal"。修复 admin-views-target 路径的 security-level caller-context 泄漏。`StreamChatEventHandler` 全类去 `UserContext`。引入 `VectorBackendCapabilityValidator` 收口阶段 A。
- **路线图位置**：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 A · PR3
- **前置**：PR1（commit `a24ea4bb`）+ PR2（commit `7e1ef680`）已合并至 main
- **Out-of-scope marker**：chunk internal split 推迟到 **PR3.5** 独立 PR；阶段 B（检索链路对齐）/ C（per-KB security level 粒度）/ D-F 全部不在本 PR

---

## 0. Problem Statement

PR2 把 `KbAccessService` 22 个方法的上帝接口拆为 7 个 framework port，热路径从 `KbAccessService` 迁到了 port。然而**泄漏并未消除，只是换了形状**：

### 0.1 当前泄漏点（main 上活着的 bug）

`KbAccessServiceImpl.getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds)`（`:342-386`）：

```java
public Map<String, Integer> getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds) {
    // ...
    LoginUser current = UserContext.hasUser() ? UserContext.get() : null;   // ← 读 caller

    if (current != null && current.getRoleTypes().contains(RoleType.SUPER_ADMIN)) {
        // 给 kbIds 里的每个 KB 都返回上限 3
        for (String kbId : kbIds) result.put(kbId, 3);
        return result;                                                       // ← caller=SUPER_ADMIN 时
                                                                             //   target 在每个 KB 上都被报为 3
    }
    // ... RBAC 查询用 userId 参数（这部分正确）...

    if (current != null && current.getRoleTypes().contains(RoleType.DEPT_ADMIN)
            && current.getDeptId() != null) {
        int deptCeiling = current.getMaxSecurityLevel();                     // ← caller 的 ceiling
        Set<String> sameDeptKbIds = kbMetadataReader.filterKbIdsByDept(
                kbIds, current.getDeptId());                                 // ← caller 的 deptId
        for (String kbId : sameDeptKbIds) {
            result.merge(kbId, deptCeiling, Math::max);                       // ← 把 caller 的天花板贴到 result
        }
    }
}
```

`KbAccessServiceImpl.getMaxSecurityLevelForKb(String userId, String kbId)`（`:284-339`）单 key 版有完全相同的 leak（`:290 / :297`）。

### 0.2 实际影响

`AccessServiceImpl.listUserKbGrants(targetUserId)` 是 admin 后台"查看其他用户授权"路径，`:145` 调 `kbReadAccess.getMaxSecurityLevelsForKbs(targetUserId, ...)`：

| Caller (admin) | Target | 预期 ceiling | 实际返回 |
|---|---|---|---|
| SUPER_ADMIN | FICC_USER | target 的 RBAC ceiling（如 1） | **3**（全部 KB 都误报满级） |
| DEPT_ADMIN(OPS) | FICC_USER | target 的 RBAC ceiling | OPS 部门 KB 上误报为 caller 的 ceiling，跨域污染 |

后台 UI 显示的 `securityLevel` 字段是 caller 的能力，不是 target 的实际授权——管理员看不到正确的下属授权事实。

### 0.3 根因

`KbReadAccessPort` 的签名诱导：

```java
AccessScope getAccessScope(String userId, Permission minPermission);
Map<String, Integer> getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds);
```

签名"看起来"是 target-aware（接受 `userId`），但实现实际是 current-user only（决策走 `UserContext.get()`）。**PR2 修了一半**：把 caller-context 从签名上抽离到参数，但实现仍偷读 ThreadLocal。`AccessServiceImpl` 注释里的"管理员查其他人时会漏算成全量"（`:135`）警告了这一点，但只对 `getAccessibleKbIds` 路径绕开（用 `KbRbacAccessSupport`），`getMaxSecurityLevelsForKbs` 路径没绕——因为没有 calculator 替代品。

### 0.4 PR3 的根治姿态

不是"加 mismatch guard 让 userId 参数和 UserContext 不一致时抛错"——guard 防错不防诱因，**签名仍然继续诱导下一个人犯错**。

PR3 改契约：

- `KbReadAccessPort` 不再接受 `userId` 参数 → 签名级回归 current-user
- target-aware 路径**不走 port**，走显式 `KbAccessSubject` + 纯函数 `KbAccessCalculator`
- `KbAccessSubjectFactory` 是项目内**唯一**把 `UserContext` / `UserProfileLoader` 转成 subject 的地方

---

## 1. Layering Contract

### 1.1 类拓扑

```
┌─────────────────────────────────────────────────────────────────────┐
│ Controller / Service entry                                          │
│   - current-user 路径：注入 KbReadAccessPort，调 getAccessScope(p)  │
│   - target-aware 路径：注入 KbAccessSubjectFactory + Calculator     │
│     forTargetUser(uid) → calculator.computeXxx(subject, ...)        │
├─────────────────────────────────────────────────────────────────────┤
│ KbReadAccessPort  (framework)                                       │
│   AccessScope getAccessScope(Permission minPermission);             │
│   void checkReadAccess(String kbId);                                │
│   Map<String,Integer> getMaxSecurityLevelsForKbs(Collection<String>)│
│   * 全部 current-user-only,签名零 userId                            │
├─────────────────────────────────────────────────────────────────────┤
│ KbAccessServiceImpl  (bootstrap user/service/impl/)                 │
│   - 实现 7 ports + KbAccessService(@Deprecated)                     │
│   - 内部注入 KbAccessSubjectFactory + KbAccessCalculator             │
│   - port 方法体: subject = factory.currentOrThrow();                 │
│                  // getAccessScope 保留 SUPER_ADMIN→AccessScope.all  │
│                  return calculator.computeXxx(subject, ...);         │
│   - 不再直接读 UserContext 做 RBAC 决策                              │
├─────────────────────────────────────────────────────────────────────┤
│ KbAccessSubjectFactory  (bootstrap user/service/support/)           │
│   currentOrThrow()    → 唯一 UserContext 触点                       │
│   forTargetUser(uid)  → 唯一 UserProfileLoader 调用                 │
├─────────────────────────────────────────────────────────────────────┤
│ KbAccessCalculator  (bootstrap user/service/support/)               │
│   - 纯函数 (Spring bean),不 import UserContext                       │
│   - 参数全部显式: KbAccessSubject + Permission/Collection<String>   │
│   - 接管 KbRbacAccessSupport 的全部逻辑（PR3 后该静态 util 删除）   │
│   - DEPT_ADMIN implicit / SUPER_ADMIN bypass 全部基于 subject 字段   │
├─────────────────────────────────────────────────────────────────────┤
│ KbAccessSubject  (bootstrap user/service/support/)                  │
│   record(userId, deptId, roleTypes, maxSecurityLevel)               │
│   方法: isSuperAdmin() / isDeptAdmin()                              │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Invariants（PR3 起所有后续 PR 必须保持）

继承 PR1 的 A/B/C/D 不变量。新增 6 条**PR3 不变量**：

- **PR3-1 — Calculator 纯函数性**：`KbAccessCalculator` 不得 import `UserContext`、`LoginUser`、`UserProfileLoader`。所有输入由参数传入；ArchUnit 守门。
- **PR3-2 — Port 签名 current-user only**：`KbReadAccessPort` 任何方法不得接受 `String userId` 参数；ArchUnit 守门。
- **PR3-3 — admin-views-target 走 calculator**：`AccessServiceImpl` 不得调 `kbReadAccess.*`；只能通过 `subjectFactory.forTargetUser(uid) + calculator`；grep 守门。
- **PR3-4 — RAG handler 包零 UserContext**：`com.knowledgebase.ai.ragent.rag.service.handler` 包内任何类不得 import `UserContext`；ArchUnit 守门。`userId` 由构造参数显式传入。
- **PR3-5 — 单 key `getMaxSecurityLevelForKb` 物理删除**：从 `KbAccessService` 接口与 `KbAccessServiceImpl` 实现完全删除（不 `@Deprecated`）。grep `getMaxSecurityLevelForKb` `bootstrap/src/main/java` 应返回零结果（除注释）。
- **PR3-6 — 向量后端启动 fail-fast**：`rag.vector.type != opensearch` 且 `rag.vector.allow-incomplete-backend != true` 时启动失败。`VectorBackendCapabilityValidator` 实现 + 启动测试覆盖。

### 1.3 与 PR1 invariants 的关系

| PR1 invariant | PR3 影响 |
|---|---|
| A — 边界唯一性 | 不变。calculator 不是新边界，是边界**内部**的纯函数提取 |
| B — 系统态显式 | 不变。`KbAccessSubjectFactory.currentOrThrow()` 在 `UserContext.isSystem()=true` 时**不应被调用**（系统态走 `bypassIfSystemOrAssertActor` 早返回，不到 calculator 层）。如错误调用 → 抛 `IllegalStateException` |
| C — HTTP 语义不变 | 不变。PR3 不改任何 controller 入口；仅改 service 内部实现 |
| D — Service vs HTTP 边界分离 | 不变 |

---

## 2. Per-File Inventory

### 2.1 新增文件（commit 1）

| File | 内容 |
|---|---|
| `bootstrap/.../user/service/support/KbAccessSubject.java` | `record(String userId, String deptId, Set<RoleType> roleTypes, int maxSecurityLevel)` + `isSuperAdmin()` / `isDeptAdmin()` |
| `bootstrap/.../user/service/support/KbAccessSubjectFactory.java` | `interface { KbAccessSubject currentOrThrow(); KbAccessSubject forTargetUser(String userId); }` |
| `bootstrap/.../user/service/support/KbAccessSubjectFactoryImpl.java` | Spring `@Component`：`currentOrThrow()` 读 `UserContext.get()` → subject；`UserContext.isSystem()` 时抛 `IllegalStateException`；`!hasUser() \|\| getUserId()==null` 时抛 `ClientException("missing user context")`；`forTargetUser(uid)` 调 `UserProfileLoader.load(uid)` → subject；`load()==null` 时抛 `ClientException("目标用户不存在")` |
| `bootstrap/.../user/service/support/KbAccessCalculator.java` | Spring `@Component`，构造参数 `UserRoleMapper / RoleKbRelationMapper / KbMetadataReader`。提供 `Set<String> computeAccessibleKbIds(KbAccessSubject, Permission)` + `Map<String, Integer> computeMaxSecurityLevels(KbAccessSubject, Collection<String>)`；类内不得 import `UserContext` / `LoginUser` |
| `bootstrap/.../user/service/support/KbAccessCalculatorTest.java` | 单测（参数化）：覆盖 SUPER_ADMIN bypass / DEPT_ADMIN implicit / regular RBAC / 跨部门 implicit 不污染 / per-KB level 隔离 / RBAC + DEPT_ADMIN merge `Math::max` 取大 |
| `bootstrap/.../user/service/support/KbAccessSubjectFactoryImplTest.java` | 单测：currentOrThrow 三态（系统态抛 ISE / no-user 抛 CE / 正常返回）；forTargetUser 两态（用户不存在抛 CE / 正常返回） |

### 2.2 修改文件（commit 2 — port 签名 + 实现迁移）

#### 2.2.1 `framework/security/port/KbReadAccessPort.java`

```diff
- AccessScope getAccessScope(String userId, Permission minPermission);
+ AccessScope getAccessScope(Permission minPermission);

  void checkReadAccess(String kbId);

- Map<String, Integer> getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds);
+ Map<String, Integer> getMaxSecurityLevelsForKbs(Collection<String> kbIds);

- @Deprecated
- default Set<String> getAccessibleKbIds(String userId) {
-     throw new UnsupportedOperationException("use getAccessScope()");
- }
  // 整个 default 删除
```

#### 2.2.2 `bootstrap/.../user/service/impl/KbAccessServiceImpl.java`

| 方法 | 当前 | 改为 |
|---|---|---|
| `getAccessScope(userId, p)` `:135` | 自己读 `isSuperAdmin()` + 调 `getAccessibleKbIds(userId, p)` | 删除 userId 参数；方法体 `KbAccessSubject s = subjectFactory.currentOrThrow(); return s.isSuperAdmin() ? AccessScope.all() : AccessScope.ids(calculator.computeAccessibleKbIds(s, p));` —— **SUPER_ADMIN 分支必须保留 `AccessScope.all()` sentinel**，`AccessScope.All` 是 sealed 类型且全仓 8+ 处 `instanceof AccessScope.All` 短路（`SpacesController:56` / `KnowledgeBaseServiceImpl:281` / `KnowledgeDocumentServiceImpl:686` / `MultiChannelRetrievalEngine:253` 等）依赖此契约。calculator 内部可同时支持 SUPER_ADMIN 物化全集（admin-views-target 报表需要真实 KB 列表），但 **port 出口语义不变** |
| `getMaxSecurityLevelsForKbs(userId, kbIds)` `:342` | 偷读 UserContext | 删除 userId 参数；方法体 `KbAccessSubject s = subjectFactory.currentOrThrow(); return calculator.computeMaxSecurityLevels(s, kbIds);` |
| `getAccessibleKbIds(userId, p)` `:86`（KbAccessService 接口的 deprecated 方法）| 方法体内 `isSuperAdmin()` / `isDeptAdmin()` 读 caller context（与 `getMaxSecurityLevelsForKbs` 同等 leak） | **保留签名**（KbAccessService 仍 @Deprecated 接口，PR2 决定保留实现作为 port 委托者）；方法体改为 **永远** `subjectFactory.forTargetUser(userId)` 路径——即使 caller-as-self 也走 `UserProfileLoader.load(userId)` 加载快照。`isSuperAdmin/isDeptAdmin` 决策迁入 calculator 内部基于 subject 字段。**性能取舍**：caller-as-self 多一次 JOIN，但本方法是 deprecated 接口的死路，热路径走 port `getAccessScope(p)`，不受影响。Redis 缓存逻辑（`:109-124`）保留——cache key 已按 `userId` 索引，写入的是 target 真值，反而修正了原 leak 期间被污染的缓存形态 |
| `getAccessibleKbIds(userId)` `:128` | 调 `getAccessibleKbIds(userId, READ)` | 不变 |
| `computeRbacKbIds(userId, p)` `:161` | 调 `KbRbacAccessSupport.computeRbacKbIdsFor` | **删除**该 private 方法（无内部 caller 后） |
| `computeDeptAdminAccessibleKbIds(userId, p)` `:148` | 读 `UserContext.get().getDeptId()` | **删除**（逻辑迁入 calculator） |
| `bypassIfSystemOrAssertActor()` PR1 helper | 不变 | 不变 |

#### 2.2.3 `bootstrap/.../user/service/impl/KbRbacAccessSupport.java`

**整个文件删除**。逻辑迁入 `KbAccessCalculator` 私有方法。

#### 2.2.4 `bootstrap/.../user/service/impl/AccessServiceImpl.java`

```diff
  // :66 注入字段
- private final KbReadAccessPort kbReadAccess;
+ private final KbAccessSubjectFactory subjectFactory;
+ private final KbAccessCalculator calculator;

  // :117 listUserKbGrants(String userId)
+ KbAccessSubject target = subjectFactory.forTargetUser(userId);          // 替代 :130-138 的手动 super/dept 计算

- // Step 1: 真相范围（:135-138）—— 现状用 KbRbacAccessSupport 静态调用
- Set<String> targetReadableKbIds = computeTargetUserAccessibleKbIds(...);
+ Set<String> targetReadableKbIds = calculator.computeAccessibleKbIds(target, Permission.READ);

  // :145
- Map<String, Integer> levels = kbReadAccess.getMaxSecurityLevelsForKbs(userId, targetReadableKbIds);
+ Map<String, Integer> levels = calculator.computeMaxSecurityLevels(target, targetReadableKbIds);

  // :318-336 computeTargetUserAccessibleKbIds private helper —— 整个方法删除
```

`UserDO user = userMapper.selectById(userId)` 在 `:118` 仍保留（用于 VO 拼装的 `userDeptId`），但**权限决策完全不依赖**它——决策路径输入只有 `target` subject。

#### 2.2.5 `bootstrap/.../knowledge/service/impl/KbScopeResolverImpl.java`

```diff
  // :47
- return kbReadAccess.getAccessScope(user.getUserId(), Permission.READ);
+ return kbReadAccess.getAccessScope(Permission.READ);
```

#### 2.2.6 `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java`

```diff
  // :125
- accessScope = kbReadAccess.getAccessScope(userId, Permission.READ);
+ accessScope = kbReadAccess.getAccessScope(Permission.READ);
```
注：`:119 String userId = UserContext.getUserId();` 保留（用于 handler 构造参数注入，见 §2.4 commit 4）。

#### 2.2.7 `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java`

```diff
  // :261-262 已有 hasUser guard
  if (accessScope instanceof AccessScope.Ids ids && !ids.kbIds().isEmpty() && UserContext.hasUser()) {
-     kbSecurityLevels = kbReadAccess.getMaxSecurityLevelsForKbs(UserContext.getUserId(), ids.kbIds());
+     kbSecurityLevels = kbReadAccess.getMaxSecurityLevelsForKbs(ids.kbIds());
  }
```

#### 2.2.8 测试 fixture

所有 `KbReadAccessPort` mock setup 中带 `userId` 参数的 stub 调用调整签名。受影响测试类：

- `bootstrap/src/test/java/.../KbScopeResolverImplTest.java`（如存在）
- `bootstrap/src/test/java/.../knowledge/.../*AuthBoundaryTest.java`（PR1 引入的 4 个参数化边界测试）
- `bootstrap/src/test/java/.../rag/.../RAGChatServiceImplTest.java`
- `bootstrap/src/test/java/.../rag/.../MultiChannelRetrievalEngineTest.java`
- 任何用 `@MockBean KbReadAccessPort` 并 stub 上述方法的测试

ChunkServiceImpl 等仅用 `checkReadAccess(kbId)` 的测试**零改动**。

### 2.3 删除 `getMaxSecurityLevelForKb`（commit 3）

#### 2.3.1 `bootstrap/.../user/service/KbAccessService.java`

```diff
- /** ... */
- Integer getMaxSecurityLevelForKb(String userId, String kbId);
```

#### 2.3.2 `bootstrap/.../user/service/impl/KbAccessServiceImpl.java`

```diff
- @Override
- public Integer getMaxSecurityLevelForKb(String userId, String kbId) {
-     // :284-339 全部删除
- }
```

#### 2.3.3 文档同步

- `bootstrap/.../user/controller/vo/UserKbGrantVO.java:45` 注释 `/** 该 KB 密级上限（getMaxSecurityLevelForKb） */` → 改为 `/** 该 KB 密级上限（calculator.computeMaxSecurityLevels） */`
- `docs/dev/design/2026-04-26-permission-roadmap.md` 路线图 §3 PR3 段落："`AccessServiceImpl:325/:183` caller-context 泄漏" 标注为 PR3 已修
- `docs/dev/gotchas.md`、`docs/dev/arch/business-flows.md` 等任何 `getMaxSecurityLevelForKb` 引用全部更新

### 2.4 `StreamChatEventHandler` ThreadLocal 收尾（commit 4）

#### 2.4.1 `bootstrap/.../rag/service/handler/StreamChatHandlerParams.java`

加 `String userId` 字段（builder + getter）。factory 构造 params 时显式传入。

#### 2.4.2 `bootstrap/.../rag/service/handler/StreamCallbackFactory.java`

**真正的构造点**——`StreamChatEventHandler` 由 factory 在 `createChatEventHandler(...)` 内 new，RAGChatServiceImpl 仅持有 factory 引用。改 factory 方法签名接受 `userId`，并在 builder 链上传入：

```diff
  public StreamChatEventHandler createChatEventHandler(SseEmitter emitter,
                                                       String conversationId,
-                                                      String taskId) {
+                                                      String taskId,
+                                                      String userId) {
      StreamChatHandlerParams params = StreamChatHandlerParams.builder()
              .emitter(emitter)
              .conversationId(conversationId)
              .taskId(taskId)
+             .userId(userId)
              .modelProperties(modelProperties)
              // ...
              .build();
      return new StreamChatEventHandler(params);
  }
```

#### 2.4.3 `bootstrap/.../rag/service/handler/StreamChatEventHandler.java`

```diff
- import com.knowledgebase.ai.ragent.framework.context.UserContext;

  public StreamChatEventHandler(StreamChatHandlerParams params) {
      // ...
      this.traceId = RagTraceContext.getTraceId();
-     this.userId = UserContext.getUserId();
+     this.userId = params.getUserId();        // 显式注入,非 ThreadLocal 读
      // ...
  }

  public void onComplete() {
      // :190
-     String messageId = memoryService.append(conversationId, UserContext.getUserId(),
+     String messageId = memoryService.append(conversationId, this.userId,
              ChatMessage.assistant(answer.toString()), null);
      // ...
  }
```

#### 2.4.4 `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java`

orchestrator 在 `:119` 已有 `String userId = UserContext.getUserId();`，改 factory 调用透传：

```diff
  // 调 factory 处（约 line 200-210，构造 callback 现场）
- StreamChatEventHandler handler = streamCallbackFactory.createChatEventHandler(
-     emitter, conversationId, taskId);
+ StreamChatEventHandler handler = streamCallbackFactory.createChatEventHandler(
+     emitter, conversationId, taskId, userId);
```

注：实施期需 grep 一次 `createChatEventHandler` 全调用面，更新所有 caller（包括测试 fixture）。

#### 2.4.5 ArchUnit 规则（commit 4 c5）

**位置**：扩展现有 `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/PermissionBoundaryArchTest.java`（PR1 commit `e1cde994` 落地的同一文件，已 `@AnalyzeClasses(packages = "com.knowledgebase.ai.ragent")`，能扫到 bootstrap + framework + infra-ai 全部业务类）。**不**放 framework 模块——framework 测试当前不依赖 ArchUnit，且 `PermissionBoundaryArchTest` 引用 `KbAccessService` 这种 bootstrap 类型，搬到 framework 会破依赖方向。

`KbAccessServiceRetirementArchTest`（PR2 commit `ea8d93bd`）也在同一 package，可作 PR3 规则的姊妹文件参考。

新增 3 条规则到 `PermissionBoundaryArchTest`：

```java
@ArchTest
static final ArchRule rag_handler_package_no_user_context =
    noClasses()
        .that().resideInAPackage("com.knowledgebase.ai.ragent.rag.service.handler..")
        .should().dependOnClassesThat()
        .haveFullyQualifiedName("com.knowledgebase.ai.ragent.framework.context.UserContext")
        .because("PR3-4: handler 类不读 ThreadLocal,userId 由构造参数显式传入");

@ArchTest
static final ArchRule kb_access_calculator_no_user_context =
    noClasses()
        .that().haveFullyQualifiedName(
            "com.knowledgebase.ai.ragent.user.service.support.KbAccessCalculator")
        .should().dependOnClassesThat()
        .haveFullyQualifiedNameMatching(
            "com\\.nageoffer\\.ai\\.ragent\\.framework\\.context\\.(UserContext|LoginUser)")
        .because("PR3-1: Calculator 纯函数性");

@ArchTest
static final ArchRule kb_read_access_port_no_userid_param =
    methods()
        .that().areDeclaredInClassesThat()
        .haveFullyQualifiedName(
            "com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort")
        .should(notHaveStringParameterNamedUserId())
        .because("PR3-2: Port 签名 current-user only");
```

`notHaveStringParameterNamedUserId()` 是自定义 `ArchCondition<JavaMethod>`——遍历方法参数，对类型为 `String` 且名为 `userId` 的参数 fail。实施细节交给 writing-plans。

#### 2.4.6 测试

`StreamChatEventHandlerThreadLocalTest`（可能新增或扩展现有 `StreamChatEventHandlerCitationTest`）：

- 用 `Mockito.InOrder` 验证 `onComplete()` 内调用顺序：`persistSourcesIfPresent` → `updateTraceTokenUsage` → `mergeCitationStatsIntoTrace`（PR3 onComplete 顺序硬约束已写入 bootstrap CLAUDE.md）
- 验证 `memoryService.append` 收到的 userId == 构造期注入值（即使在测试中 ThreadLocal 已 clear，append 仍收到正确 userId）
- **factory 签名兼容性**：`StreamCallbackFactoryTest` 验证 `createChatEventHandler(emitter, conversationId, taskId, userId)` 把 `userId` 传到 params builder 上，且 returned handler 的 `memoryService.append` 调用收到该 userId（防止 factory 落参漏写）

### 2.5 `RagVectorTypeValidator` 升级为 fail-fast（commit 5）

#### 2.5.1 现状（既有文件，不新增）

`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java` **已存在**——`@PostConstruct` 在非 OpenSearch 后端时仅 `log.warn`。PR3 **就地升级**该类，不在 `infra/config` 新建重复 validator。理由：

- 双 validator 并存会产生分裂行为（一个 warn、一个 throw），文档/测试归属不清
- 现有类已挂 `@Component` + 有 javadoc 描述 dev-only 语义；PR3 只需把 `log.warn` 升级为可选 fail-fast
- 包路径 `rag/config` 与 `rag.vector.type` 配置归属一致，比 `infra/config` 更准

#### 2.5.2 改动

```diff
  @Slf4j
  @Component
  public class RagVectorTypeValidator {

      @Value("${rag.vector.type:opensearch}")
      private String vectorType;

+     @Value("${rag.vector.allow-incomplete-backend:false}")
+     private boolean allowIncomplete;

      @PostConstruct
      public void validate() {
-         if (!"opensearch".equalsIgnoreCase(vectorType)) {
-             log.warn("RAG vector backend is '{}' (not opensearch). " + ... );
+         if ("opensearch".equalsIgnoreCase(vectorType)) {
+             return;
+         }
+         if (allowIncomplete) {
+             log.warn(
+                 "rag.vector.type={} 不是 opensearch,但 allow-incomplete-backend=true,"
+               + "权限/security_level 过滤可能不完整。仅 dev 用,禁勿生产。",
+                 vectorType);
+             return;
+         }
+         throw new IllegalStateException(
+             "rag.vector.type=" + vectorType + " 不被支持。"
+           + "当前生产仅支持 opensearch;dev 临时使用其他后端需"
+           + "rag.vector.allow-incomplete-backend=true。"
+           + "见 docs/dev/followup/backlog.md SL-1");
          }
      }
  }
```

`@PostConstruct` 留用（与原文件一致），`ApplicationRunner` 不切换——`@PostConstruct` 在 bean 初始化期抛错即可阻止 context 启动，与 `ApplicationRunner.run` 等价；不引入新接口可减小 diff。

#### 2.5.3 测试

`RagVectorTypeValidatorTest`（如不存在则新建于 `bootstrap/src/test/java/.../rag/config/`）：

- `vectorType=opensearch` → `validate()` 静默成功
- `vectorType=milvus, allowIncomplete=false` → 抛 `IllegalStateException`，msg contains "milvus" + "allow-incomplete-backend"
- `vectorType=milvus, allowIncomplete=true` → 静默通过 + WARN 日志（断言 logged）

#### 2.5.4 配置文档

`bootstrap/src/main/resources/application.yaml` 在 `rag.vector` 节注释新增：

```yaml
rag:
  vector:
    type: opensearch  # 生产仅支持 opensearch
    # allow-incomplete-backend: false  # dev override; 设为 true 才能用 milvus/pg
```

### 2.6 文件清单：明确不修改

PR3 不动以下任何代码：

- `framework/security/port/KbManageAccessPort.java`、`KbRoleBindingAdminPort.java`、`KbMetadataReader.java`、`CurrentUserProbe.java`、`UserAdminGuard.java`、`SuperAdminInvariantGuard.java`、`KbAccessCacheAdmin.java`（其余 7 个 port 不变）
- `KnowledgeChunkService` interface 拆分（→ PR3.5）
- `KnowledgeChunkServiceImpl.batchCreate / updateEnabledByDocId / listByDocId / deleteByDocId`（PR1 已加 javadoc，PR3 不收紧物理 visibility）
- 任何前端文件
- 任何数据库 schema / migration 脚本
- OpenSearch query DSL（→ 阶段 B / PR4-PR5）
- `LoginUser.maxSecurityLevel` 全局字段语义（→ 阶段 C / PR6）
- audit_log 切面（→ 阶段 E / PR9-10）

---

## 3. Test & Acceptance Contracts

### 3.1 必须测试（CI 强制）

#### T1 — admin-views-target security-level 不污染

```java
@Test
void superAdminViewingFiccUser_returnsTargetCeiling_notSuperAdminCeiling() {
    // Given: SUPER_ADMIN logged in via UserContext
    UserContext.set(superAdminLoginUser);
    // FICC_USER target with RBAC ceiling=1 on OPS_KB
    when(userProfileLoader.load("ficc_user_id"))
        .thenReturn(new LoadedUserProfile("ficc_user_id", "ficc", null,
            "FICC_DEPT", "FICC", List.of("FICC_USER"),
            Set.of(RoleType.USER), 1, false, false));
    when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
        relation("FICC_USER", "OPS_KB", Permission.READ.name(), 1)));

    // When: AccessServiceImpl.listUserKbGrants("ficc_user_id")
    List<UserKbGrantVO> grants = accessService.listUserKbGrants("ficc_user_id");

    // Then: OPS_KB ceiling = 1 (target's), not 3 (caller's)
    UserKbGrantVO opsGrant = grants.stream()
        .filter(g -> "OPS_KB".equals(g.getKbId())).findFirst().orElseThrow();
    assertThat(opsGrant.getSecurityLevel()).isEqualTo(1);
}
```

#### T2 — DEPT_ADMIN-views-target 跨部门 implicit 不污染

```java
@Test
void opsAdminViewingFiccUser_doesNotApplyOpsAdminDeptCeiling() {
    // Caller: OPS_ADMIN with deptId=OPS, maxSecurityLevel=2
    UserContext.set(opsAdminLoginUser);

    // Target: FICC_USER with deptId=FICC, RBAC ceiling=0 on FICC_KB
    when(userProfileLoader.load("ficc_user_id"))
        .thenReturn(new LoadedUserProfile("ficc_user_id", "ficc", null,
            "FICC_DEPT", "FICC", List.of("FICC_USER"),
            Set.of(RoleType.USER), 0, false, false));
    // FICC_KB.deptId=FICC; target 不是 DEPT_ADMIN,implicit 路径不应触发
    when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
        relation("FICC_USER", "FICC_KB", Permission.READ.name(), 0)));

    List<UserKbGrantVO> grants = accessService.listUserKbGrants("ficc_user_id");

    UserKbGrantVO ficcGrant = grants.stream()
        .filter(g -> "FICC_KB".equals(g.getKbId())).findFirst().orElseThrow();
    assertThat(ficcGrant.getSecurityLevel()).isEqualTo(0);          // target's RBAC, not caller's 2
    assertThat(ficcGrant.isImplicit()).isFalse();                    // target 不是 OPS DEPT_ADMIN
}
```

#### T3 — calculator 纯函数性（同 subject 不依赖 UserContext）

```java
@Test
void calculator_returnsSameResult_regardlessOfUserContext() {
    KbAccessSubject subject = new KbAccessSubject(
        "u1", "DEPT1", Set.of(RoleType.USER), 1);

    UserContext.set(buildSuperAdminLoginUser("super1"));
    Set<String> resultAsSuper = calculator.computeAccessibleKbIds(subject, Permission.READ);

    UserContext.clear();
    Set<String> resultNoContext = calculator.computeAccessibleKbIds(subject, Permission.READ);

    UserContext.set(buildDeptAdminLoginUser("admin1", "DEPT1"));
    Set<String> resultAsDeptAdmin = calculator.computeAccessibleKbIds(subject, Permission.READ);

    assertThat(resultAsSuper).isEqualTo(resultNoContext);
    assertThat(resultAsSuper).isEqualTo(resultAsDeptAdmin);
}
```

#### T4 — handler async callback 用注入 userId

```java
@Test
void onComplete_usesInjectedUserId_notThreadLocal() {
    StreamChatHandlerParams params = baseParams().userId("injected_uid").build();
    StreamChatEventHandler handler = new StreamChatEventHandler(params);

    UserContext.clear();                         // simulate async pool with cleared TL
    handler.onContent("answer text");
    handler.onComplete();

    verify(memoryService).append(eq(conversationId), eq("injected_uid"),
        any(), isNull());
}
```

#### T5 — onComplete 顺序契约（PR3 已锁定，但 commit 4 InOrder 复测）

```java
@Test
void onComplete_orderingContract() {
    InOrder inOrder = inOrder(conversationMessageService, traceRecordService);
    handler.onComplete();
    inOrder.verify(conversationMessageService).updateSourcesJson(any(), any());     // persistSources
    inOrder.verify(traceRecordService).updateRunExtraData(any(), anyString());      // overwrite tokens
    inOrder.verify(traceRecordService).mergeRunExtraData(any(), anyMap());          // merge citations
}
```

#### T6 — VectorBackendCapabilityValidator fail-fast

```java
@Test
void milvus_withoutOverride_throws() {
    var v = new VectorBackendCapabilityValidator();
    ReflectionTestUtils.setField(v, "vectorType", "milvus");
    ReflectionTestUtils.setField(v, "allowIncomplete", false);
    assertThatThrownBy(() -> v.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("milvus")
        .hasMessageContaining("allow-incomplete-backend");
}

@Test
void milvus_withOverride_succeeds() { /* allowIncomplete=true → 静默 */ }

@Test
void opensearch_succeeds() { /* vectorType=opensearch → 静默 */ }
```

### 3.2 ArchUnit 规则（CI 强制）

见 §2.4.4 三条规则。文件位置：`framework/src/test/java/.../security/PermissionBoundaryArchTest.java`（PR1 commit `e1cde994` 已落地的同一文件，PR3 扩展）。

### 3.3 grep 守门脚本

新增 `docs/dev/verification/permission-pr3-leak-free.sh`：

```bash
#!/usr/bin/env bash
# PR3 守门:验证 admin-views-target 不再走 port + 单 key 方法已删
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0

# PR3-3: AccessServiceImpl 不调 kbReadAccess
if rg -n "kbReadAccess\." \
    bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java; then
    echo "✗ PR3-3 violation: AccessServiceImpl 调用了 KbReadAccessPort"
    fail=1
fi

# PR3-5: 单 key getMaxSecurityLevelForKb 物理删除
hits=$(rg -c "getMaxSecurityLevelForKb\b" bootstrap/src/main/java || true)
if [ -n "$hits" ]; then
    echo "✗ PR3-5 violation: getMaxSecurityLevelForKb 仍存在 ($hits)"
    fail=1
fi

# PR3-2: KbReadAccessPort 不接受 userId 参数
if rg -n "String\s+userId" \
    framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbReadAccessPort.java; then
    echo "✗ PR3-2 violation: KbReadAccessPort 仍有 String userId 参数"
    fail=1
fi

# PR3-4: handler 包不 import UserContext
if rg -n "import.*UserContext" \
    bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler; then
    echo "✗ PR3-4 violation: handler 包仍 import UserContext"
    fail=1
fi

if [ $fail -eq 0 ]; then
    echo "✓ PR3 4 条 grep 守门全通过"
fi
exit $fail
```

### 3.4 手工 smoke（参考 PR1 `permission-pr1-smoke.md` 格式）

新增 `docs/dev/verification/permission-pr3-smoke.md`：

| # | 路径 | 期望 |
|---|---|---|
| 1 | SUPER_ADMIN 在管理后台访问"用户管理 → 查看 FICC_USER 授权" | OPS_KB 显示 securityLevel=1（target ceiling），不是 3（caller ceiling） |
| 2 | OPS_ADMIN 在管理后台查看 FICC_USER 授权 | FICC_KB 上 implicit=false，securityLevel=target's RBAC，不是 OPS_ADMIN's 2 |
| 3 | FICC_USER 登录后 SSE 问答 | onComplete 后 t_message.user_id 正确（即使 SSE pool 跳线程） |
| 4 | 启动应用，`rag.vector.type=milvus`，无 dev override | 启动失败，日志含 `IllegalStateException` + "milvus" + "allow-incomplete-backend" |
| 5 | 启动应用，`rag.vector.type=opensearch`（默认） | 启动成功，无 WARN |

---

## 4. Migration & Rollback

### 4.1 PR2 → PR3 顺序保证

PR3 commit 顺序必须：

```
c1 (新增 calculator/factory + 测试)        ← 编译可独立通过
c2 (port 签名收紧 + 实现迁移)              ← 改 port + 调用方 + 测试 fixture(同 commit)
c3 (删除 getMaxSecurityLevelForKb)         ← 必须在 c2 后(KbAccessServiceImpl 已不再依赖)
c4 (handler ThreadLocal 收尾 + factory 签名扩 userId + ArchUnit)
c5 (RagVectorTypeValidator 升级 fail-fast + 测试 + 配置注释)
```

c2 是**单 commit 大改**（约 8 个文件）但内部强耦合：port 签名变化必须跟实现 + 调用方 + 测试 fixture 同时变。**不能拆**——拆了 c2 中间状态编译都不过。

### 4.2 Rollback

PR 合并后回滚通过 `git revert` 整个 PR。中间 commit 不构成可用快照（c2 一刀切，c3 之前 main 上仍有 dead method）。

### 4.3 数据库

零变更。PR3 不涉及任何 schema、初始化数据或线上数据修复。

---

## 5. Decision Records

迁移到本节避免后续 PR 重新协商。

### 5.1 为什么不在 calculator 里加 mismatch guard？

候选方案："保留 `userId` 参数，加 `if (!Objects.equals(userId, UserContext.getUserId())) throw new IllegalStateException(...)`"。

**拒绝**理由：guard 防错不防诱因，签名仍诱导下一个开发者按"我有 userId 就能查目标"思路写代码。PR3 选择**签名级回归**——port 删掉 userId 参数，target-aware 路径完全不走 port。诱因消除。

### 5.2 为什么 `KbAccessSubjectFactory.currentOrThrow()` 而不是 `fromCurrent() returning Optional`？

current-user 路径在缺 UserContext 时**必须 fail-closed**——这是 PR1 invariant B 的延伸。`Optional` 让调用方猜 `null/empty/exception` 语义，引入软返回路径。`currentOrThrow` 是契约级强制：要么有 subject 要么炸开。

### 5.3 为什么 `KbAccessSubject` 不加 `system` 字段？

系统态在 PR1 已通过 `bypassIfSystemOrAssertActor()` 在**入口处早返回**，根本到不了 calculator 层。subject 加 `system` 字段会让 calculator 内部需要 `if (subject.isSystem()) return all` 分支——重复 PR1 的 bypass 逻辑、且违反 calculator 纯函数性原则（系统态不是 KB 计算的属性，是上下文属性）。

`KbAccessSubjectFactory.currentOrThrow()` 在 `UserContext.isSystem()=true` 时抛 `IllegalStateException`——明确表达"系统态不应到达此层"，把错误暴露在调用现场而非沉默通过。

### 5.4 为什么 calculator 放在 `bootstrap/.../user/service/support/` 而非 framework？

calculator 依赖 `UserRoleMapper / RoleKbRelationMapper / UserRoleDO / RoleKbRelationDO / KbMetadataReader`。前 4 个是 user 域 DAO + 实体；framework 不允许 import bootstrap（ArchUnit 已守门）。把 calculator 上升到 framework 会破依赖方向。

`KbMetadataReader` 是 framework port，calculator 注入 port 接口而非具体实现，依赖方向 OK。

### 5.5 为什么 chunk internal split 推迟到 PR3.5？

chunk 接口拆分（`KnowledgeChunkInternalService` vs `KnowledgeChunkService`）改的是 chunk 域的可见性架构，**不是权限不变量**。混进 PR3 会：

- 扩散 review 焦点（calculator + threadlocal + chunk 三条线）
- 改 `KnowledgeChunkServiceImpl` 的 Spring bean 注入方式（双 interface），波及 `KnowledgeDocumentServiceImpl` 等 in-process caller
- 影响测试 fixture（chunk auth boundary tests 需重写）

PR3.5 作为独立的"chunk 域可见性整理"PR 处理更稳。PR1 加的 javadoc 标记暂时承担保护责任。

### 5.6 为什么 `VectorBackendCapabilityValidator` 放 PR3 而非 PR4？

路线图 §3 阶段 A 成功标准明文写了"启动期 `rag.vector.type=milvus` 触发 fail-fast"。PR3 是阶段 A 收口 PR，按路线图它就归这里。

阶段 B（PR4-PR5）依赖 OpenSearch-only DSL 改造（`terms(metadata.kb_id) + range(security_level)`）。如 PR4 启动时 backend 还能是 milvus，PR4 必须额外写防御代码——把 fail-fast 留 PR3 是把"PR4 的前置不变量"做实。

### 5.7 删除 `getMaxSecurityLevelForKb` 而非 `@Deprecated`？

grep `getMaxSecurityLevelForKb\b` 在 `bootstrap/src/main/java`（含 `src/test/java`）零业务 caller。`@Deprecated` 是给"还有人用、需逃生通道"的方法准备的；本方法是死代码 + 自带 leak。直接删，归零接口面、归零 leak 面。

### 5.8 `KbAccessService.getAccessibleKbIds(userId, p)` 为什么保留 deprecated？

`KbAccessService` 接口本身已 PR2 整体 `@Deprecated`，PR3 期间仍有少量调用点（grep `getAccessibleKbIds(` `bootstrap/src/main/java` 仍有命中）。该方法实现改为内部**永远走 `forTargetUser(userId)` + calculator**——即使是 caller-as-self 调用，也多一次 `UserProfileLoader.load` JOIN 加载 subject。这与 §2.2.2 的裁定一致。

**为什么不做"caller-as-self 走 currentOrThrow"分支优化**：分支会让该方法的语义依赖 `UserContext` 状态（系统态、缺 user 等需要分支判断），重新引入 ThreadLocal 触点。永远走 forTargetUser 让方法语义**只取决于 `userId` 参数**，与"target-shaped API"签名匹配，且方法是 deprecated 死路、性能不敏感。**接口保留**——调用点的物理删除归阶段 B/D 推进，不是 PR3 范围。

---

## 6. Out of Scope（PR4-PR12 接手）

| 工作项 | 接手 PR | 路线图章节 |
|---|---|---|
| `KnowledgeChunkInternalService` 物理拆分 | PR3.5（独立） | 阶段 A 残留 |
| `RetrievalScopeBuilder` + `RagRequest.kbIds` 字段迁移 | PR4 | 阶段 B |
| OpenSearch DSL 强制 `terms(kb_id) + range(security_level)` | PR5 | 阶段 B |
| per-(role × kb) `max_security_level` 改造 | PR6 | 阶段 C |
| Sharing UI / `KbRoleBindingAdminPort` 不对称规则 | PR7-PR8 | 阶段 D |
| audit_log AOP 切面 | PR9-PR10 | 阶段 E |
| 申请流（`t_kb_access_request` + 审批） | PR11-PR12 | 阶段 F |

---

## 7. References

- `docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 A · PR3
- `docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md` — PR1 spec（不变量 A/B/C/D 的权威源，PR3 继承）
- `docs/dev/followup/2026-04-26-architecture-p0-audit-report.md` — 架构审计 S0 项（向量后端 fail-fast）+ S2 项（异步与事件边界）
- `docs/dev/research/enterprise_permissions_architecture.md` — 业务视角 Phase 1（最小 RBAC + 知识库共享）
- `bootstrap/CLAUDE.md` — `onComplete` 顺序约束 / 零 ThreadLocal 新增硬约束
- `framework/CLAUDE.md` — UserContext 在异步线程不可用约束
- `bootstrap/.../user/dao/dto/LoadedUserProfile.java` — subject 构造数据源（`forTargetUser`）
- 当前 main 上的泄漏证据：`KbAccessServiceImpl.java:284-339, :342-386`
