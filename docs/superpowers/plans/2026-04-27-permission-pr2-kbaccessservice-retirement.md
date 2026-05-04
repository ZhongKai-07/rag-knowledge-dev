# Permission PR2 — KbAccessService God-Service Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 退役 `KbAccessService` god-service。bootstrap 业务代码迁到 `framework.security.port` 下 8 个具体 port(7 既有 + 1 新增 `KbRoleBindingAdminPort`)。引入 `KbScopeResolver`,scope 跨边界统一为 `AccessScope` sealed type。零业务功能增量。

**Architecture:** 6 commit 序列(c1-c6)。c1 落 `KbScopeResolver` + scope DTO 解耦 + service 入参形态;c2 RAG 热路径迁 `KbReadAccessPort`;c3 KB/Doc/Chunk service 内部 + 新建 `KbRoleBindingAdminPort`;c4 user 域 controller/service 全迁;c5 接口 `@Deprecated`;c6 `KbAccessServiceRetirementArchTest` + verification 脚本双护栏。每 commit 独立 compile + targeted test green;PR1 不变量 A-D + 既有 33 测试保持 green;新增不变量 E/F/G/H。

**Tech Stack:** Java 17 + Spring Boot 3.5 + Mockito 5 + ArchUnit 1.x + JUnit 5 + Sa-Token + Lombok + ripgrep(verification)

**Spec:** `docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md`
**Roadmap:** `docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 A · PR2

---

## File Structure

### 新建文件(c1)

| 路径 | 责任 |
|---|---|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolver.java` | 接口:`resolveForRead` / `resolveForOwnerScope`,返回 `AccessScope` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java` | 实现:`@Service` + 注入 `KbReadAccessPort + KbMetadataReader`,**不**注入 `CurrentUserProbe`,**不**读 `UserContext` |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolverImplTest.java` | T5 三态契约 9 测试方法 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/AccessScopeServiceContractTest.java` | T6 wrapper-capture,2 service × 3 state = 6 case |

### 新建文件(c3)

| 路径 | 责任 |
|---|---|
| `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbRoleBindingAdminPort.java` | 单方法 port:`int unbindAllRolesFromKb(String kbId)` |

### 新建文件(c6)

| 路径 | 责任 |
|---|---|
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/KbAccessServiceRetirementArchTest.java` | T7 ArchUnit gate + T5.10 两条 resolver 子规则 |
| `docs/dev/verification/permission-pr2-kb-access-retired.sh` | B+C 双 gate,文件级 + 注入级 |

### 主要修改文件(分布在 c1-c5)

- c1:`KnowledgeBaseController` / `SpacesController` / `KnowledgeDocumentController` / `KnowledgeBasePageRequest`(删 accessibleKbIds 字段)/ `KnowledgeBaseService`(接口加 scope 形参)/ `KnowledgeBaseServiceImpl` / `KnowledgeDocumentService`(接口改 search 签名)/ `KnowledgeDocumentServiceImpl` / `TestServiceBuilders`(加 setter)
- c2:`RAGChatServiceImpl` / `MultiChannelRetrievalEngine`
- c3:`KnowledgeBaseServiceImpl` / `KnowledgeDocumentServiceImpl` / `KnowledgeChunkServiceImpl` / `RoleServiceImpl`(部分)/ `KbAccessServiceImpl`(implements 加 1)/ `framework/CLAUDE.md`
- c4:`UserAdminGuard`(接口加 `checkRoleMutation`)/ `UserController` / `RoleController` / `AccessController` / `SysDeptController` / `DashboardController` / `UserServiceImpl` / `RoleServiceImpl`(剩余)/ `AccessServiceImpl`
- c5:`KbAccessService.java`(接口标 `@Deprecated`)
- c6:见上面"新建文件(c6)"

---

## Pre-flight 检查(每个 Task 执行前必看)

### 0.1 确认当前在 main 分支或新建 feature 分支

```bash
git status -sb
git branch --show-current
```

如果当前不在 `feature/permission-pr2-...` 类似分支,先建分支:

```bash
git checkout -b feature/permission-pr2-kbaccessservice-retirement
```

### 0.2 确认 PR1 测试基线

```bash
mvn -pl bootstrap test -Dtest="KbAccessServiceSystemActorTest,KbAccessServiceImplTest,PermissionBoundaryArchTest" -q
```

期望:全部 PASS(PR1 不变量未被破坏)。

### 0.3 baseline-red 已知失败列表(全程不需要修)

`MilvusCollectionTests` / `InvoiceIndexDocumentTests`×3 / `IntentTreeServiceTests.initFromFactory` / `VectorTreeIntentClassifierTests`×4 / `PgVectorStoreServiceTest.testChineseCharacterInsertion` 共 10 errors。每次跑全量测试遇到这些**保持忽略**,不在 PR2 范围。

---

## Task 1: c1 — `KbScopeResolver` + Scope 跨边界改造

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolver.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolverImplTest.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/AccessScopeServiceContractTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBasePageRequest.java`(删 `accessibleKbIds` 字段)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseService.java`(`pageQuery` 加 `AccessScope` 形参)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java`(`search` 入参 `Set<String>` → `AccessScope`)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java:266-282`(解构 `AccessScope`)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`(`search` 解构 `AccessScope`)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java:55-128`(注入 `KbScopeResolver` + 改 `applyKbScope`)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java:40-83`(注入 `KbScopeResolver` + sealed type 解构)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java:55-130`(注入 `KbScopeResolver` + `search` 调用改)
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/test/support/TestServiceBuilders.java:68-115`(签名同步)
- Modify(测试): `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseControllerScopeTest.java`
- Modify(测试): `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/PageQueryFailClosedTest.java`

### 1.1 Step 1: 写 `KbScopeResolverImplTest`(T5 三态契约失败测试)

- [ ] **Step 1: 创建测试文件,写 9 个测试方法**

写到 `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolverImplTest.java`:

```java
package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.knowledge.service.impl.KbScopeResolverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KbScopeResolverImplTest {

    private KbReadAccessPort kbReadAccess;
    private KbMetadataReader kbMetadataReader;
    private KbScopeResolverImpl resolver;

    @BeforeEach
    void setUp() {
        kbReadAccess = mock(KbReadAccessPort.class);
        kbMetadataReader = mock(KbMetadataReader.class);
        resolver = new KbScopeResolverImpl(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForRead_null_user_returns_empty() {
        AccessScope result = resolver.resolveForRead(null);
        assertEquals(AccessScope.empty(), result);
        verifyNoInteractions(kbReadAccess);
    }

    @Test
    void resolveForRead_user_with_null_userId_returns_empty() {
        LoginUser user = LoginUser.builder().userId(null).username("anon").build();
        AccessScope result = resolver.resolveForRead(user);
        assertEquals(AccessScope.empty(), result);
        verifyNoInteractions(kbReadAccess);
    }

    @Test
    void resolveForRead_super_admin_returns_all_without_querying_port() {
        LoginUser user = LoginUser.builder()
                .userId("u-1").username("admin")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN)).build();
        AccessScope result = resolver.resolveForRead(user);
        assertThat(result).isInstanceOf(AccessScope.All.class);
        verifyNoInteractions(kbReadAccess);
    }

    @Test
    void resolveForRead_user_delegates_to_port() {
        LoginUser user = LoginUser.builder()
                .userId("u-1").username("alice")
                .roleTypes(Set.of(RoleType.USER)).build();
        AccessScope expected = AccessScope.ids(Set.of("kb-1"));
        when(kbReadAccess.getAccessScope("u-1", Permission.READ)).thenReturn(expected);
        AccessScope result = resolver.resolveForRead(user);
        assertEquals(expected, result);
    }

    @Test
    void resolveForOwnerScope_super_admin_returns_all() {
        LoginUser user = LoginUser.builder()
                .userId("u-1").username("admin")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN)).build();
        AccessScope result = resolver.resolveForOwnerScope(user);
        assertThat(result).isInstanceOf(AccessScope.All.class);
        verifyNoInteractions(kbMetadataReader);
    }

    @Test
    void resolveForOwnerScope_dept_admin_with_dept_returns_dept_kb_ids() {
        LoginUser user = LoginUser.builder()
                .userId("u-1").username("ops_admin")
                .deptId("d-ops")
                .roleTypes(Set.of(RoleType.DEPT_ADMIN)).build();
        when(kbMetadataReader.listKbIdsByDeptId("d-ops")).thenReturn(Set.of("kb-ops-1", "kb-ops-2"));
        AccessScope result = resolver.resolveForOwnerScope(user);
        assertEquals(AccessScope.ids(Set.of("kb-ops-1", "kb-ops-2")), result);
    }

    @Test
    void resolveForOwnerScope_dept_admin_with_null_dept_returns_empty() {
        LoginUser user = LoginUser.builder()
                .userId("u-1").username("rogue_admin")
                .deptId(null)
                .roleTypes(Set.of(RoleType.DEPT_ADMIN)).build();
        AccessScope result = resolver.resolveForOwnerScope(user);
        assertEquals(AccessScope.empty(), result);
        verifyNoInteractions(kbMetadataReader);
    }

    @Test
    void resolveForOwnerScope_user_returns_empty() {
        LoginUser user = LoginUser.builder()
                .userId("u-1").username("alice")
                .roleTypes(Set.of(RoleType.USER)).build();
        AccessScope result = resolver.resolveForOwnerScope(user);
        assertEquals(AccessScope.empty(), result);
        verifyNoInteractions(kbMetadataReader);
    }

    @Test
    void resolveForOwnerScope_null_user_returns_empty() {
        AccessScope result = resolver.resolveForOwnerScope(null);
        assertEquals(AccessScope.empty(), result);
        verifyNoInteractions(kbMetadataReader);
    }
}
```

- [ ] **Step 2: 跑测试,确认 9 个全部失败(因接口/实现尚未创建)**

```bash
mvn -pl bootstrap test -Dtest=KbScopeResolverImplTest -q
```

Expected: COMPILATION ERROR(`KbScopeResolverImpl` 不存在)。这是 TDD 的"红"阶段。

### 1.2 Step 3: 创建 `KbScopeResolver` 接口

- [ ] **Step 3: 写 `KbScopeResolver.java` 接口**

写到 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolver.java`:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;

/**
 * 解析当前请求/调用方的知识库可见范围。返回 {@link AccessScope} sealed type,
 * 跨 controller/service 边界的权限范围唯一表达。
 *
 * <p>实现不直接读 {@code UserContext} ThreadLocal — SUPER/DEPT_ADMIN 判定从入参
 * {@code LoginUser.getRoleTypes()} 取(不变量 G)。
 *
 * <p>Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §1.2 §2.4
 */
public interface KbScopeResolver {

    /**
     * 列表 / 检索 / 全局浏览的可读范围。
     * <ul>
     *   <li>{@code null} 或 userId 为 null → {@link AccessScope#empty()}</li>
     *   <li>SUPER_ADMIN → {@link AccessScope.All}</li>
     *   <li>其他 → 委托 {@link com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort#getAccessScope}</li>
     * </ul>
     */
    AccessScope resolveForRead(LoginUser user);

    /**
     * KB 列表 {@code scope=owner} 分支专用:用户自己"管"的 KB 集合。
     * <ul>
     *   <li>{@code null} 或 userId 为 null → {@link AccessScope#empty()}</li>
     *   <li>SUPER_ADMIN → {@link AccessScope.All}</li>
     *   <li>DEPT_ADMIN(deptId 非 null)→ 本部门 KB 集合</li>
     *   <li>DEPT_ADMIN(deptId 为 null,数据异常)/ USER → {@link AccessScope#empty()}</li>
     * </ul>
     */
    AccessScope resolveForOwnerScope(LoginUser user);
}
```

### 1.3 Step 4-5: 创建 `KbScopeResolverImpl` 实现

- [ ] **Step 4: 写 `KbScopeResolverImpl.java` 实现**

写到 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java`:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.knowledge.service.KbScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KbScopeResolverImpl implements KbScopeResolver {

    private final KbReadAccessPort kbReadAccess;
    private final KbMetadataReader kbMetadataReader;

    @Override
    public AccessScope resolveForRead(LoginUser user) {
        if (user == null || user.getUserId() == null) {
            return AccessScope.empty();
        }
        if (hasRole(user, RoleType.SUPER_ADMIN)) {
            return AccessScope.all();
        }
        return kbReadAccess.getAccessScope(user.getUserId(), Permission.READ);
    }

    @Override
    public AccessScope resolveForOwnerScope(LoginUser user) {
        if (user == null || user.getUserId() == null) {
            return AccessScope.empty();
        }
        if (hasRole(user, RoleType.SUPER_ADMIN)) {
            return AccessScope.all();
        }
        if (hasRole(user, RoleType.DEPT_ADMIN) && user.getDeptId() != null) {
            return AccessScope.ids(kbMetadataReader.listKbIdsByDeptId(user.getDeptId()));
        }
        return AccessScope.empty();
    }

    private static boolean hasRole(LoginUser user, RoleType type) {
        return user.getRoleTypes() != null && user.getRoleTypes().contains(type);
    }
}
```

- [ ] **Step 5: 跑 T5 测试,确认 9 个全部 PASS**

```bash
mvn -pl bootstrap test -Dtest=KbScopeResolverImplTest -q
```

Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`。

### 1.4 Step 6-9: 改造 service 入参 + 解构模板

- [ ] **Step 6: 改 `KnowledgeBaseService.pageQuery` 接口签名**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseService.java` 的 `pageQuery` 方法:

```java
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;

// ...

/**
 * 分页查询知识库
 *
 * @param requestParam 分页查询请求参数
 * @param scope        访问范围(All / Ids(empty) / Ids(nonEmpty),由 controller 经 KbScopeResolver 计算)
 * @return 知识库分页结果
 */
IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam, AccessScope scope);
```

- [ ] **Step 7: 改 `KnowledgeBaseServiceImpl.pageQuery` 解构 `AccessScope`(替换原 `accessibleKbIds` 三态)**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java:266-282`:

```java
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;

// ...

@Override
public IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam, AccessScope scope) {
    LambdaQueryWrapper<KnowledgeBaseDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeBaseDO.class)
            .like(StringUtils.hasText(requestParam.getName()), KnowledgeBaseDO::getName, requestParam.getName())
            .eq(KnowledgeBaseDO::getDeleted, 0)
            .orderByDesc(KnowledgeBaseDO::getUpdateTime);

    if (scope instanceof AccessScope.Ids ids) {
        if (ids.kbIds().isEmpty()) {
            return new Page<>(requestParam.getCurrent(), requestParam.getSize(), 0);
        }
        queryWrapper.in(KnowledgeBaseDO::getId, ids.kbIds());
    } else if (!(scope instanceof AccessScope.All)) {
        throw new IllegalStateException("Unsupported AccessScope type: " + scope.getClass());
    }

    Page<KnowledgeBaseDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
    IPage<KnowledgeBaseDO> result = knowledgeBaseMapper.selectPage(page, queryWrapper);
    // ... 其余逻辑保留(deptNameMap / docCountMap / 转 VO)
}
```

- [ ] **Step 8: 删除 `KnowledgeBasePageRequest.accessibleKbIds` 字段**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBasePageRequest.java`,**物理删除**:

```java
private Set<String> accessibleKbIds;  // 删除整行

// 同时移除 import(若仅供此字段使用):
// import java.util.Set;
```

- [ ] **Step 9: 改 `KnowledgeDocumentService.search` 接口签名**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java:111`:

```java
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;

// ...

/**
 * 搜索文档(全局检索建议)
 *
 * @param keyword 关键词
 * @param limit   返回上限
 * @param scope   访问范围(All / Ids(empty) / Ids(nonEmpty),由 controller 经 KbScopeResolver 计算)
 */
List<KnowledgeDocumentSearchVO> search(String keyword, int limit, AccessScope scope);
```

### 1.5 Step 10-12: 改造 `KnowledgeDocumentServiceImpl.search` 内部解构

- [ ] **Step 10: 找到 `KnowledgeDocumentServiceImpl.search` 当前位置**

```bash
grep -n "public List<KnowledgeDocumentSearchVO> search" bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
```

读该方法 + 上下文(20 行),确认现有 `Set<String> accessibleKbIds` 处理方式(`null = admin 不过滤`)。

- [ ] **Step 11: 改 `KnowledgeDocumentServiceImpl.search` 解构 `AccessScope`**

**关键约束**:保留现有方法的所有结构(`StringUtils.hasText` 空 keyword guard / `Math.min(Math.max(limit, 1), 20)` 清洗 / `Page` + `selectPage` 分页 / `KnowledgeDocumentDO::getDocName` 谓词 / `documentMapper` 字段 / records 后处理 kbName 等)。**仅替换** `accessibleKbIds` 三态(`null = 不过滤` / `empty = 全拒` / `nonEmpty = in`)为 `AccessScope` sealed type 解构:

```java
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;

// ... 文件开头 imports 处加 AccessScope import,删除 import java.util.Set; (若仅供 search 用)

@Override
public List<KnowledgeDocumentSearchVO> search(String keyword, int limit, AccessScope scope) {
    if (!StringUtils.hasText(keyword)) {
        return Collections.emptyList();
    }
    // Fail-closed: Ids(empty) → 短路返空(替换原 accessibleKbIds.isEmpty() 检查)
    if (scope instanceof AccessScope.Ids ids && ids.kbIds().isEmpty()) {
        return Collections.emptyList();
    }

    int size = Math.min(Math.max(limit, 1), 20);
    Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
    LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
            .eq(KnowledgeDocumentDO::getDeleted, 0)
            .like(KnowledgeDocumentDO::getDocName, keyword)
            .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

    // 仅 Ids(nonEmpty) 加 in;All / Ids(空) 不进入此分支(空已上方短路,All 不过滤)
    if (scope instanceof AccessScope.Ids ids) {
        qw.in(KnowledgeDocumentDO::getKbId, ids.kbIds());
    } else if (!(scope instanceof AccessScope.All)) {
        throw new IllegalStateException("Unsupported AccessScope type: " + scope.getClass());
    }

    IPage<KnowledgeDocumentDO> result = documentMapper.selectPage(mpPage, qw);
    List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
            .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
            .toList();
    // ... 后续 records 后处理(kbName 查询等)整段保留,无改动
    if (records.isEmpty()) {
        return records;
    }
    // ... rest of method body unchanged
}
```

**对照原 L667-688**:`accessibleKbIds != null && accessibleKbIds.isEmpty()` → `scope instanceof AccessScope.Ids ids && ids.kbIds().isEmpty()`;`.in(accessibleKbIds != null, KnowledgeDocumentDO::getKbId, accessibleKbIds)` → `if (scope instanceof AccessScope.Ids ids) qw.in(...)`。其他逻辑(分页 / 谓词 / 后处理)逐字保留。

- [ ] **Step 12: 编译确认 service 层签名一致**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS。会有 controller 编译错(尚未改 caller),那是下一步。

### 1.6 Step 13-17: 改造 3 个 controller

- [ ] **Step 13: 改 `KnowledgeBaseController` `applyKbScope` + `pageQuery`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`:

```java
// 字段改造(L58):
// 删除: private final KbAccessService kbAccessService;
// 添加: private final KbScopeResolver kbScopeResolver;

import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.knowledge.service.KbScopeResolver;

// pageQuery 改造(L100-106):
@GetMapping("/knowledge-base")
public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
    LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
    AccessScope scope = "owner".equalsIgnoreCase(requestParam.getScope())
            ? kbScopeResolver.resolveForOwnerScope(user)
            : kbScopeResolver.resolveForRead(user);
    return Results.success(knowledgeBaseService.pageQuery(requestParam, scope));
}

// 整段删除原 applyKbScope 方法(L108-128)
```

同步移除 import:
```java
// 删除 import com.nageoffer.ai.ragent.user.service.KbAccessService;
// 删除 import java.util.Set; (若只 applyKbScope 用到)
```

- [ ] **Step 14: 改 `SpacesController.getStats`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java`:

```java
// 字段改造:
// 删除: private final KbAccessService kbAccessService;
// 添加: private final KbScopeResolver kbScopeResolver;

import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.knowledge.service.KbScopeResolver;

@GetMapping("/spaces/stats")
public Result<SpacesStatsVO> getStats() {
    LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
    AccessScope scope = kbScopeResolver.resolveForRead(user);

    if (scope instanceof AccessScope.All) {
        long kbCount = knowledgeBaseMapper.selectCount(Wrappers.lambdaQuery(KnowledgeBaseDO.class));
        long totalDocCount = knowledgeDocumentMapper.selectCount(Wrappers.lambdaQuery(KnowledgeDocumentDO.class));
        return Results.success(new SpacesStatsVO(kbCount, totalDocCount));
    }
    if (scope instanceof AccessScope.Ids ids) {
        if (ids.kbIds().isEmpty()) {
            return Results.success(new SpacesStatsVO(0L, 0L));
        }
        long kbCount = knowledgeBaseMapper.selectCount(Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                .in(KnowledgeBaseDO::getId, ids.kbIds()));
        long totalDocCount = knowledgeDocumentMapper.selectCount(Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .in(KnowledgeDocumentDO::getKbId, ids.kbIds()));
        return Results.success(new SpacesStatsVO(kbCount, totalDocCount));
    }
    throw new IllegalStateException("Unsupported AccessScope type: " + scope.getClass());
}
```

- [ ] **Step 15: 改 `KnowledgeDocumentController.search`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java:122-130`:

```java
// 字段改造:
// 删除: private final KbAccessService kbAccessService;
// 添加: private final KbScopeResolver kbScopeResolver;

import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.knowledge.service.KbScopeResolver;

@GetMapping("/knowledge-base/docs/search")
public Result<List<KnowledgeDocumentSearchVO>> search(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "limit", defaultValue = "8") int limit) {
    LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
    AccessScope scope = kbScopeResolver.resolveForRead(user);
    return Results.success(documentService.search(keyword, limit, scope));
}
```

- [ ] **Step 16: 改 `TestServiceBuilders` — 加 mapper-injecting 重载(供 T6 wrapper-capture 测试用)**

修改 `bootstrap/src/test/java/com/nageoffer/ai/ragent/test/support/TestServiceBuilders.java:68-76`,**保留**原单参 `knowledgeBaseService(KbAccessService)` 工厂(c1 期间生产代码 `KnowledgeBaseServiceImpl` 仍接 `KbAccessService`,签名不变),并**新增** mapper-injecting 重载供 T6 用:

```java
public static KnowledgeBaseServiceImpl knowledgeBaseService(KbAccessService kbAccessService) {
    return knowledgeBaseService(kbAccessService, mock(KnowledgeBaseMapper.class));
}

// 新增重载:T6 wrapper-capture 需要捕获实际 mapper
public static KnowledgeBaseServiceImpl knowledgeBaseService(
        KbAccessService kbAccessService, KnowledgeBaseMapper kbMapper) {
    return new KnowledgeBaseServiceImpl(
            kbMapper,
            mock(KnowledgeDocumentMapper.class),
            mock(VectorStoreAdmin.class),
            mock(FileStorageService.class),
            kbAccessService,
            mock(SysDeptMapper.class));
}
```

`AccessScopeServiceContractTest`(Step 18 写)直接调用 `TestServiceBuilders.knowledgeBaseService(kbAccess, mapper)` 二参版本(原 Step 19 内容已并入此 Step,后续 Step 19 不再重复)。

**关于 controller 测试**:`KnowledgeBaseControllerScopeTest` 等 controller-level 测试仍需把 mock 从 `KbAccessService` 换成 `KbScopeResolver`(已在 Step 17 验证里指明)。

- [ ] **Step 17: 编译 + 跑 c1 受影响的既有测试**

```bash
mvn -pl bootstrap compile -q
mvn -pl bootstrap test -Dtest="KbScopeResolverImplTest,KnowledgeBaseControllerScopeTest,PageQueryFailClosedTest" -q
```

Expected:`KbScopeResolverImplTest` 9 PASS。其他两个可能因签名变化报错,需要按以下原则修:
- `KnowledgeBaseControllerScopeTest`:把 mock 从 `KbAccessService` 换成 `KbScopeResolver`,`when(kbScopeResolver.resolveForRead(any())).thenReturn(...)` 替代旧的 `getAccessibleKbIds` mock
- `PageQueryFailClosedTest`:测试方法改为构造 `AccessScope.ids(emptySet)` 入参,断言 `pageQuery(req, scope)` 返空 page

修完后再跑一次,期望 PASS。

### 1.7 Step 18-21: 写 T6 wrapper-capture 契约测试

- [ ] **Step 18: 写 `AccessScopeServiceContractTest`**

写到 `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/AccessScopeServiceContractTest.java`:

```java
package com.nageoffer.ai.ragent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.test.support.TestServiceBuilders;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessScopeServiceContractTest {

    // T6.A — KnowledgeBaseService.pageQuery All
    @Test
    void pageQuery_with_all_scope_does_not_add_in_clause() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbAccessService kbAccess = mock(KbAccessService.class);
        KnowledgeBaseServiceImpl service = TestServiceBuilders.knowledgeBaseService(kbAccess);
        // 注:pageQuery 内部会用 service 自身 KnowledgeBaseMapper,需通过反射或 builder 注入 mock
        // 详细做法:扩展 TestServiceBuilders 加 setter 或在测试内重建 service
        // (本测试在 c1 step 21 修正实现细节)

        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());
        KnowledgeBasePageRequest req = new KnowledgeBasePageRequest();
        req.setCurrent(1L); req.setSize(10L);

        service.pageQuery(req, AccessScope.all());

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(1)).selectPage(any(), cap.capture());
        assertThat(cap.getValue().getCustomSqlSegment()).doesNotContain(" IN ");
    }

    // T6.B — KnowledgeBaseService.pageQuery Ids(empty)
    @Test
    void pageQuery_with_empty_ids_returns_empty_page_and_skips_mapper() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbAccessService kbAccess = mock(KbAccessService.class);
        KnowledgeBaseServiceImpl service = TestServiceBuilders.knowledgeBaseService(kbAccess);

        KnowledgeBasePageRequest req = new KnowledgeBasePageRequest();
        req.setCurrent(1L); req.setSize(10L);
        IPage<?> result = service.pageQuery(req, AccessScope.empty());

        assertThat(result.getTotal()).isEqualTo(0);
        verify(mapper, never()).selectPage(any(), any());
    }

    // T6.C — KnowledgeBaseService.pageQuery Ids(nonEmpty)
    @Test
    void pageQuery_with_nonEmpty_ids_adds_in_clause_with_correct_kbIds() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbAccessService kbAccess = mock(KbAccessService.class);
        KnowledgeBaseServiceImpl service = TestServiceBuilders.knowledgeBaseService(kbAccess);

        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());
        KnowledgeBasePageRequest req = new KnowledgeBasePageRequest();
        req.setCurrent(1L); req.setSize(10L);

        service.pageQuery(req, AccessScope.ids(Set.of("kb-1", "kb-2")));

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(1)).selectPage(any(), cap.capture());
        assertThat(cap.getValue().getCustomSqlSegment()).contains(" IN ");
        assertThat(cap.getValue().getParamNameValuePairs().values())
                .contains("kb-1", "kb-2");
    }

    // T6.A/B/C 同样模式覆盖 KnowledgeDocumentService.search 3 case (略;参考上述模式)
}
```

- [ ] **Step 19: (已并入 Step 16,无独立动作)**

mapper-injecting 重载已在 Step 16 加好,`AccessScopeServiceContractTest` 直接调用即可。本 Step 保留为占位以维持 Step 编号连续(后续 Step 20-22 不变)。

- [ ] **Step 20: 跑 T6**

```bash
mvn -pl bootstrap test -Dtest=AccessScopeServiceContractTest -q
```

Expected:6 测试 PASS(2 service × 3 case)。

- [ ] **Step 21: 跑 c1 受影响的全部测试,确认无既有回归**

```bash
mvn -pl bootstrap test -Dtest="KbScopeResolverImplTest,AccessScopeServiceContractTest,KnowledgeBaseControllerScopeTest,PageQueryFailClosedTest,KnowledgeBaseServiceAuthBoundaryTest,KnowledgeDocumentServiceAuthBoundaryTest" -q
```

Expected:全 PASS(boundary tests 仍 mock `KbAccessService`,c1 不动其内部 check)。

### 1.8 Step 22: c1 commit

- [ ] **Step 22: c1 commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolver.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBasePageRequest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolverImplTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/AccessScopeServiceContractTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseControllerScopeTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/PageQueryFailClosedTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/test/support/TestServiceBuilders.java

git commit -m "$(cat <<'EOF'
refactor(security): 引入 KbScopeResolver,scope 跨边界统一为 AccessScope (PR2 c1)

- KbScopeResolver 接口 + 实现,resolveForRead / resolveForOwnerScope
- resolver 不读 ThreadLocal,SUPER/DEPT_ADMIN 判定从 LoginUser.getRoleTypes() 取
- KnowledgeBaseController + SpacesController + KnowledgeDocumentController 改注 resolver
- KnowledgeBasePageRequest.accessibleKbIds 物理删除
- KnowledgeBaseService.pageQuery / KnowledgeDocumentService.search 加 AccessScope 入参
- service 解构模板:All 不加 in / Ids(empty) 短路返空 / Ids(nonEmpty) in()
- 不变量 F + G(部分):scope 跨边界 sealed type;resolver 不直接读 ThreadLocal
- 测试:T5 三态契约(9) + T6 wrapper-capture(6) + 既有 boundary mock 调整

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR2 commit 1
Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §2.4 §3.2 T5/T6
EOF
)"
```

---

## Task 2: c2 — RAG 热路径迁 `KbReadAccessPort`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java:67,97,134`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java:35,67,262`
- Modify(测试): `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`
- Modify(测试): `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEnginePostProcessorChainTest.java`

### 2.1 Step 1-3: 改 RAGChatServiceImpl

- [ ] **Step 1: 删除 `RAGChatServiceImpl` 上 `KbAccessService` 字段 + import + check 调用**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`:

```java
// L67 删除:
// import com.nageoffer.ai.ragent.user.service.KbAccessService;

// L97 删除:
// private final KbAccessService kbAccessService;
// (注:L98 的 kbReadAccess 已注入,保留)

// L134 修改:
// 旧: kbAccessService.checkAccess(knowledgeBaseId);
// 新: kbReadAccess.checkReadAccess(knowledgeBaseId);
```

- [ ] **Step 2: 编译确认**

```bash
mvn -pl bootstrap compile -q
```

Expected:BUILD SUCCESS。

- [ ] **Step 3: 改 `RAGChatServiceImplSourcesTest` mock 类型**

修改 `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`,把:

```java
// 旧:
KbAccessService kbAccessService = mock(KbAccessService.class);
// 旧的字段注入参数也要拿掉

// 新:
KbReadAccessPort kbReadAccess = mock(KbReadAccessPort.class);
// (kbReadAccess mock 应已存在,删除原 kbAccessService mock 即可)
```

如有 stub `when(kbAccessService.checkAccess(...))` → 改 `when(kbReadAccess.checkReadAccess(...))`。

### 2.2 Step 4-6: 改 MultiChannelRetrievalEngine

- [ ] **Step 4: 改 `MultiChannelRetrievalEngine`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`:

```java
// L35 替换 import:
// 旧: import com.nageoffer.ai.ragent.user.service.KbAccessService;
// 新: import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;

// L67 替换字段:
// 旧: private final KbAccessService kbAccessService;
// 新: private final KbReadAccessPort kbReadAccess;

// L262 调用 receiver 改名:
// 旧: kbAccessService.getMaxSecurityLevelsForKbs(UserContext.getUserId(), ids.kbIds());
// 新: kbReadAccess.getMaxSecurityLevelsForKbs(UserContext.getUserId(), ids.kbIds());
```

- [ ] **Step 5: 改 `MultiChannelRetrievalEnginePostProcessorChainTest` mock 类型**

同 Task 2.1 Step 3 模式:`mock(KbAccessService.class)` → `mock(KbReadAccessPort.class)`。

- [ ] **Step 6: 跑 c2 受影响测试**

```bash
mvn -pl bootstrap test -Dtest="RAGChatServiceImplSourcesTest,MultiChannelRetrievalEnginePostProcessorChainTest" -q
```

Expected:全 PASS。

### 2.3 Step 7: c2 commit

- [ ] **Step 7: c2 commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEnginePostProcessorChainTest.java

git commit -m "$(cat <<'EOF'
refactor(rag): RAG 热路径迁 KbReadAccessPort,移除 KbAccessService 注入 (PR2 c2)

- RAGChatServiceImpl:134 kbAccessService.checkAccess → kbReadAccess.checkReadAccess
  + 移除 KbAccessService 字段(kbReadAccess 已注入,L98)
- MultiChannelRetrievalEngine:262 字段类型替换,getMaxSecurityLevelsForKbs 调用 receiver 改名
- 测试 mock 类型同步替换

Roadmap: §3 阶段 A · PR2 commit 2
Spec: §2.3 §3.3
EOF
)"
```

---

## Task 3: c3 — KB/Doc/Chunk Service 内部迁移 + 新建 `KbRoleBindingAdminPort`

**Files:**
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbRoleBindingAdminPort.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java:66-68`(implements 加 1)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`(字段 + 5 caller)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`(字段 + 9 caller)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java`(字段 + 6 caller)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java`(部分 — 仅 :408/:450)
- Modify: `framework/CLAUDE.md`(7→8 port)
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/test/support/TestServiceBuilders.java`(支持新 port mock)
- Modify(测试): 4 个 `*ServiceAuthBoundaryTest` + `KnowledgeBaseServiceImplDeleteTest` + `KnowledgeDocumentServiceImplFindMetaTest` + `KnowledgeDocumentServiceImplTest`

### 3.1 Step 1-3: 新建 `KbRoleBindingAdminPort`

- [ ] **Step 1: 写 `KbRoleBindingAdminPort.java`**

写到 `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbRoleBindingAdminPort.java`:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.security.port;

/**
 * 知识库角色绑定写操作端口(minimal)。
 * PR2 c3 引入,语义来自 {@code KbAccessService.unbindAllRolesFromKb}。
 *
 * <p>PR D7(sharing 产品化)将扩展本接口加 add/remove/update 等方法,
 * 或拆为 read/write 子接口。当前为内部工程边界,**对外不承诺稳定 API**。
 *
 * <p>Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §2.1
 */
public interface KbRoleBindingAdminPort {

    /**
     * 删除 t_role_kb_relation 里所有 kb_id=? 的行,并失效涉及的缓存。
     * 返回删除的绑定数。不存在绑定视为成功(返回 0)。
     */
    int unbindAllRolesFromKb(String kbId);
}
```

- [ ] **Step 2: 让 `KbAccessServiceImpl` implements 它**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java:66-68`:

```java
// 旧 implements 列表:
public class KbAccessServiceImpl implements KbAccessService,
        CurrentUserProbe, KbReadAccessPort, KbManageAccessPort,
        UserAdminGuard, SuperAdminInvariantGuard, KbAccessCacheAdmin {

// 新 implements 列表(末尾加 KbRoleBindingAdminPort):
public class KbAccessServiceImpl implements KbAccessService,
        CurrentUserProbe, KbReadAccessPort, KbManageAccessPort,
        UserAdminGuard, SuperAdminInvariantGuard, KbAccessCacheAdmin,
        KbRoleBindingAdminPort {
```

加 import:
```java
import com.nageoffer.ai.ragent.framework.security.port.KbRoleBindingAdminPort;
```

实现方法零改动(已存在 `unbindAllRolesFromKb`,签名一致)。

- [ ] **Step 3: 编译确认**

```bash
mvn -pl framework install -DskipTests -q
mvn -pl bootstrap compile -q
```

Expected:framework 编译通过 + bootstrap 编译通过。

### 3.2 Step 4-6: 改 `KnowledgeBaseServiceImpl` 字段拆 3 个 + 5 caller

- [ ] **Step 4: 改字段 + caller**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`:

```java
// 字段(原 L69 单字段 KbAccessService kbAccessService 改为 3 字段):
private final KbReadAccessPort kbReadAccess;
private final KbManageAccessPort kbManageAccess;
private final KbRoleBindingAdminPort kbRoleBindingAdmin;
// 删除 private final KbAccessService kbAccessService;

// import 替换:
// 删除 import com.nageoffer.ai.ragent.user.service.KbAccessService;
// 添加:
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbManageAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbRoleBindingAdminPort;

// caller 替换:
// L97  kbAccessService.resolveCreateKbDeptId(...) → kbManageAccess.resolveCreateKbDeptId(...)
// L166 kbAccessService.checkManageAccess(kbId)    → kbManageAccess.checkManageAccess(kbId)
// L198 kbAccessService.checkManageAccess(kbId)    → kbManageAccess.checkManageAccess(kbId)
// L216 kbAccessService.unbindAllRolesFromKb(kbId) → kbRoleBindingAdmin.unbindAllRolesFromKb(kbId)
// L250 kbAccessService.checkAccess(kbId)          → kbReadAccess.checkReadAccess(kbId)
```

- [ ] **Step 5: 改 `KnowledgeDocumentServiceImpl` 字段拆 2 个 + 9 caller**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java:79,110,137,174,438,466,473,591,653,718,778`:

```java
// 字段(L110):
private final KbReadAccessPort kbReadAccess;
private final KbManageAccessPort kbManageAccess;
// 删除 private final KbAccessService kbAccessService;

// import 同上模式

// caller 替换:
// L137 kbAccessService.checkManageAccess(kbId)            → kbManageAccess.checkManageAccess(kbId)
// L174 kbAccessService.checkDocManageAccess(docId)        → kbManageAccess.checkDocManageAccess(docId)
// L438 kbAccessService.checkDocManageAccess(docId)        → kbManageAccess.checkDocManageAccess(docId)
// L466 kbAccessService.checkAccess(documentDO.getKbId())  → kbReadAccess.checkReadAccess(documentDO.getKbId())
// L473 kbAccessService.checkDocManageAccess(docId)        → kbManageAccess.checkDocManageAccess(docId)
// L591 kbAccessService.checkDocSecurityLevelAccess(...)   → kbManageAccess.checkDocSecurityLevelAccess(...)
// L653 kbAccessService.checkAccess(kbId)                  → kbReadAccess.checkReadAccess(kbId)
// L718 kbAccessService.checkDocManageAccess(docId)        → kbManageAccess.checkDocManageAccess(docId)
// L778 kbAccessService.checkDocManageAccess(docId)        → kbManageAccess.checkDocManageAccess(docId)
```

- [ ] **Step 6: 改 `KnowledgeChunkServiceImpl` 字段拆 2 个 + 6 caller**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java:50,78,88,106,264,313,342,384`:

```java
// 字段(L78):
private final KbReadAccessPort kbReadAccess;
private final KbManageAccessPort kbManageAccess;
// 删除 private final KbAccessService kbAccessService;

// caller 替换:
// L88  kbAccessService.checkAccess(kbId)              → kbReadAccess.checkReadAccess(kbId)
// L106 kbAccessService.checkDocManageAccess(docId)    → kbManageAccess.checkDocManageAccess(docId)
// L264 kbAccessService.checkDocManageAccess(docId)    → kbManageAccess.checkDocManageAccess(docId)
// L313 kbAccessService.checkDocManageAccess(docId)    → kbManageAccess.checkDocManageAccess(docId)
// L342 kbAccessService.checkDocManageAccess(docId)    → kbManageAccess.checkDocManageAccess(docId)
// L384 kbAccessService.checkDocManageAccess(docId)    → kbManageAccess.checkDocManageAccess(docId)
```

### 3.3 Step 7-9: `RoleServiceImpl` 部分迁移 + framework/CLAUDE.md 同步

- [ ] **Step 7: 改 `RoleServiceImpl` 仅 `:408/:450`(临时双注入)**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java`:

```java
// 字段(L66 既有 kbAccessService 保留,加新字段):
private final KbAccessService kbAccessService;  // 保留 — c4 删除
private final KbManageAccessPort kbManageAccess;  // 新增

// import 加:
import com.nageoffer.ai.ragent.framework.security.port.KbManageAccessPort;

// caller 替换 仅 2 处:
// L408 kbAccessService.checkKbRoleBindingAccess(kbId) → kbManageAccess.checkKbRoleBindingAccess(kbId)
// L450 kbAccessService.checkKbRoleBindingAccess(kbId) → kbManageAccess.checkKbRoleBindingAccess(kbId)

// 其他 simulateActiveSuperAdminCountAfter / evictCache / isSuperAdmin caller 保留 KbAccessService 调用 — c4 处理
```

- [ ] **Step 8: 更新 `framework/CLAUDE.md` 7→8 port**

修改 `framework/CLAUDE.md` 大约 line 22 处的 port 列表描述:

```markdown
├── security/port/   ← 权限端口:**8 个 Port**(KbReadAccessPort / KbManageAccessPort / KbMetadataReader / CurrentUserProbe / UserAdminGuard / SuperAdminInvariantGuard / KbAccessCacheAdmin / KbRoleBindingAdminPort)+ **2 个 Sealed 支持类型**(AccessScope / SuperAdminMutationIntent)。业务代码只注入 8 个 Port,Sealed 类型做参数或返回值
```

(具体行号以实际文件为准,核心是把 "7 个 Port" 改为 "8 个 Port" + 在列表末尾加 `KbRoleBindingAdminPort`)

- [ ] **Step 9: 编译 + 跑 boundary tests**

```bash
mvn -pl framework install -DskipTests -q
mvn -pl bootstrap compile -q
```

Expected:BUILD SUCCESS。

### 3.4 Step 10-12: 改 4 个 boundary test mock 拆 port

- [ ] **Step 10: 改 `KnowledgeBaseServiceAuthBoundaryTest`**

修改 `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseServiceAuthBoundaryTest.java`:

```java
// 旧:
KbAccessService kbAccessService = mock(KbAccessService.class);

// 新:
KbReadAccessPort kbReadAccess = mock(KbReadAccessPort.class);
KbManageAccessPort kbManageAccess = mock(KbManageAccessPort.class);
KbRoleBindingAdminPort kbRoleBindingAdmin = mock(KbRoleBindingAdminPort.class);

// stub 替换:
// 旧: doThrow(new ClientException("denied")).when(kbAccessService).checkManageAccess("kb-1");
// 新: doThrow(new ClientException("denied")).when(kbManageAccess).checkManageAccess("kb-1");

// service 构造改用 TestServiceBuilders 新签名(见 Step 12)
```

3 个测试方法(rename / delete / queryById)对应改 stub 接收者:
- rename / delete → `kbManageAccess.checkManageAccess`
- queryById → `kbReadAccess.checkReadAccess`

- [ ] **Step 11: 改 `KnowledgeDocumentServiceAuthBoundaryTest` + `KnowledgeChunkServiceAuthBoundaryTest`**

模式同 Step 10。9 个 + 7 个测试方法 stub 接收者按 service 内部 caller 表(spec §2.5)对应改:

`KnowledgeDocumentServiceAuthBoundaryTest`:
- upload → `kbManageAccess.checkManageAccess`
- startChunk / delete / update / enable / getChunkLogs → `kbManageAccess.checkDocManageAccess`
- get → `kbReadAccess.checkReadAccess`
- page → `kbReadAccess.checkReadAccess`
- updateSecurityLevel → `kbManageAccess.checkDocSecurityLevelAccess`

`KnowledgeChunkServiceAuthBoundaryTest`:
- pageQuery → `kbReadAccess.checkReadAccess`
- create / update / delete / enableChunk / batchToggleEnabled / (辅助测试) → `kbManageAccess.checkDocManageAccess`

- [ ] **Step 12: 扩展 `TestServiceBuilders` 支持新 port mock**

修改 `bootstrap/src/test/java/com/nageoffer/ai/ragent/test/support/TestServiceBuilders.java:68-115`:

```java
// 添加 import:
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbManageAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbRoleBindingAdminPort;

// 重写 knowledgeBaseService 工厂方法 — 保留 mapper-injecting 重载供 T6 wrapper-capture 测试继续用:
public static KnowledgeBaseServiceImpl knowledgeBaseService(
        KbReadAccessPort kbReadAccess,
        KbManageAccessPort kbManageAccess,
        KbRoleBindingAdminPort kbRoleBindingAdmin) {
    return knowledgeBaseService(kbReadAccess, kbManageAccess, kbRoleBindingAdmin, mock(KnowledgeBaseMapper.class));
}

// 新签名 mapper-injecting 重载(替代 c1 的 (KbAccessService, KnowledgeBaseMapper) 重载)
public static KnowledgeBaseServiceImpl knowledgeBaseService(
        KbReadAccessPort kbReadAccess,
        KbManageAccessPort kbManageAccess,
        KbRoleBindingAdminPort kbRoleBindingAdmin,
        KnowledgeBaseMapper kbMapper) {
    return new KnowledgeBaseServiceImpl(
            kbMapper,
            mock(KnowledgeDocumentMapper.class),
            mock(VectorStoreAdmin.class),
            mock(FileStorageService.class),
            kbReadAccess,
            kbManageAccess,
            kbRoleBindingAdmin,
            mock(SysDeptMapper.class));
}

// **同时**:更新 AccessScopeServiceContractTest(c1 写的)从二参 (KbAccessService, mapper) 切到
// 四参 (KbReadAccessPort, KbManageAccessPort, KbRoleBindingAdminPort, mapper)。Stub 行为也对应改:
//   when(mapper.selectPage(any(), any())).thenReturn(new Page<>());
// 不再依赖 KbAccessService stub。c3 此 commit 必须把 c1 的 T6 测试一并迁过来,否则编译失败。

// 同样模式重写 knowledgeChunkService(KbReadAccessPort + KbManageAccessPort + KbMetadataReader)
public static KnowledgeChunkServiceImpl knowledgeChunkService(
        KbReadAccessPort kbReadAccess,
        KbManageAccessPort kbManageAccess,
        KbMetadataReader kbMetadataReader) {
    return new KnowledgeChunkServiceImpl(
            mock(KnowledgeChunkMapper.class),
            mock(KnowledgeDocumentMapper.class),
            mock(KnowledgeBaseMapper.class),
            mock(EmbeddingService.class),
            mock(TokenCounterService.class),
            mock(VectorStoreService.class),
            mock(TransactionOperations.class),
            kbReadAccess,
            kbManageAccess,
            kbMetadataReader);
}

// 同样模式重写 knowledgeDocumentService(KbReadAccessPort + KbManageAccessPort)
public static KnowledgeDocumentServiceImpl knowledgeDocumentService(
        KbReadAccessPort kbReadAccess,
        KbManageAccessPort kbManageAccess,
        KnowledgeDocumentMapper documentMapper) {
    return new KnowledgeDocumentServiceImpl(
            mock(KnowledgeBaseMapper.class),
            documentMapper,
            kbReadAccess,
            kbManageAccess,
            mock(DocumentParserSelector.class),
            mock(ChunkingStrategyFactory.class),
            mock(FileStorageService.class),
            mock(VectorStoreService.class),
            mock(VectorStoreAdmin.class),
            mock(KnowledgeChunkService.class),
            mock(ObjectMapper.class),
            mock(KnowledgeDocumentScheduleService.class),
            mock(IngestionPipelineService.class),
            mock(IngestionPipelineMapper.class),
            mock(IngestionEngine.class),
            mock(ChunkEmbeddingService.class),
            mock(KnowledgeDocumentChunkLogMapper.class),
            mock(TransactionOperations.class),
            mock(MessageQueueProducer.class),
            mock(KnowledgeScheduleProperties.class),
            mock(RemoteFileFetcher.class));
}

// roleService(c3 部分仍接 KbAccessService,c4 才完全拆):
public static RoleServiceImpl roleService(
        KbAccessService kbAccessService,
        KbManageAccessPort kbManageAccess) {
    return new RoleServiceImpl(
            mock(RoleMapper.class),
            mock(RoleKbRelationMapper.class),
            mock(UserRoleMapper.class),
            mock(UserMapper.class),
            mock(SysDeptMapper.class),
            mock(KnowledgeBaseMapper.class),
            kbAccessService,
            kbManageAccess);
}
```

注意:`KnowledgeBaseServiceImpl` / `KnowledgeChunkServiceImpl` / `KnowledgeDocumentServiceImpl` 构造方法的参数顺序由 `@RequiredArgsConstructor` 按字段声明顺序生成,需要与上面 caller 同步。

- [ ] **Step 13: 跑 c3 全部受影响测试 — 必须包含 c1 引入的 T6 + fail-closed 回归**

```bash
mvn -pl bootstrap test -Dtest="*ServiceAuthBoundaryTest,KnowledgeBaseServiceImplDeleteTest,KnowledgeDocumentServiceImpl*Test,AccessScopeServiceContractTest,PageQueryFailClosedTest,KbScopeResolverImplTest" -q
```

Expected:全 PASS。

**为什么 c3 必须包含 T6 + scope tests**:`TestServiceBuilders` 在 c3 Step 12 被重写(从 `KbAccessService` 入参切到小 port 入参),c1 引入的 `AccessScopeServiceContractTest` 直接调用工厂方法,**c3 commit 必须把这些测试一起迁完**(更新 mock 类型为 `KbReadAccessPort` / `KbManageAccessPort` 等),否则 c3 末态编译失败。把这两个测试加入 c3 验证命令是 c3 完成的硬指标,不是可选。

### 3.5 Step 14: c3 commit

- [ ] **Step 14: c3 commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbRoleBindingAdminPort.java \
        framework/CLAUDE.md \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/test/support/TestServiceBuilders.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/

git commit -m "$(cat <<'EOF'
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
EOF
)"
```

---

## Task 4: c4 — User 域 Controller + Service 全迁

**Files:**
- Modify: `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/UserAdminGuard.java`(加 `checkRoleMutation`)
- Modify: 5 个 controller(`UserController` / `RoleController` / `AccessController` / `SysDeptController` / `DashboardController`)
- Modify: 3 个 service(`UserServiceImpl` / `RoleServiceImpl` 剩余 / `AccessServiceImpl`)
- Modify(测试): `RoleControllerMutationAuthzTest` / `RoleServiceImplDeleteTest` / `RoleServiceImplDeletePreviewTest` / `AccessServiceImplTest`

### 4.1 Step 1-2: `UserAdminGuard` 加方法

- [ ] **Step 1: 改 `UserAdminGuard.java` 接口加 `checkRoleMutation`**

修改 `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/UserAdminGuard.java`,加方法:

```java
/**
 * 角色 CRUD / delete-preview 授权。
 * SUPER_ADMIN 可操作任意角色;DEPT_ADMIN 仅可操作本部门且非 GLOBAL 的角色。
 */
void checkRoleMutation(String roleDeptId);
```

- [ ] **Step 2: framework 重新编译 + bootstrap 编译验证 `KbAccessServiceImpl` 满足新签名**

```bash
mvn -pl framework install -DskipTests -q
mvn -pl bootstrap compile -q
```

Expected:BUILD SUCCESS(`KbAccessServiceImpl` 已有 `checkRoleMutation` 方法,自动 implements 新签名)。

### 4.2 Step 3-7: 改 5 个 controller

- [ ] **Step 3: 改 `DashboardController`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/DashboardController.java:27,40,63`:

```java
// import 替换:
// 删除 import com.nageoffer.ai.ragent.user.service.KbAccessService;
// 添加:
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;

// 字段(L40):
// 旧: private final KbAccessService kbAccessService;
// 新: private final CurrentUserProbe currentUser;

// caller(L63):
// 旧: if (!kbAccessService.isSuperAdmin() && !kbAccessService.isDeptAdmin()) {
// 新: if (!currentUser.isSuperAdmin() && !currentUser.isDeptAdmin()) {
```

- [ ] **Step 4: 改 `SysDeptController`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/SysDeptController.java:26,45,49,55`:

```java
// import 替换 → UserAdminGuard
import com.nageoffer.ai.ragent.framework.security.port.UserAdminGuard;

// 字段:
private final UserAdminGuard userAdminGuard;
// 删除 KbAccessService

// caller:
// L49 kbAccessService.checkAnyAdminAccess(); → userAdminGuard.checkAnyAdminAccess();
// L55 kbAccessService.checkAnyAdminAccess(); → userAdminGuard.checkAnyAdminAccess();
```

- [ ] **Step 5: 改 `AccessController`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/AccessController.java:27,50,60,72,73,83,93`:

```java
import com.nageoffer.ai.ragent.framework.security.port.UserAdminGuard;

// 字段:
private final UserAdminGuard userAdminGuard;
// 删除 KbAccessService

// caller(全部 6 处):
// L60/72/83/93 kbAccessService.checkAnyAdminAccess()    → userAdminGuard.checkAnyAdminAccess()
// L73 kbAccessService.checkUserManageAccess(userId)     → userAdminGuard.checkUserManageAccess(userId)
```

- [ ] **Step 6: 改 `UserController`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java:33,55,85,104,114`:

```java
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.framework.security.port.UserAdminGuard;

// 字段:
private final CurrentUserProbe currentUser;
private final UserAdminGuard userAdminGuard;
// 删除 KbAccessService

// caller:
// L85 if (!kbAccessService.isSuperAdmin() && !kbAccessService.isDeptAdmin())
//     → if (!currentUser.isSuperAdmin() && !currentUser.isDeptAdmin())
// L104 kbAccessService.checkUserManageAccess(id) → userAdminGuard.checkUserManageAccess(id)
// L114 kbAccessService.checkUserManageAccess(id) → userAdminGuard.checkUserManageAccess(id)
```

- [ ] **Step 7: 改 `RoleController`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java:26,39,43,57,73,90,112,119,120,133`:

```java
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.framework.security.port.UserAdminGuard;

// 字段:
private final CurrentUserProbe currentUser;
private final UserAdminGuard userAdminGuard;
// 删除 KbAccessService

// caller:
// L43/57/73/90 kbAccessService.checkRoleMutation(deptId)         → userAdminGuard.checkRoleMutation(deptId)
// L112 kbAccessService.checkAssignRolesAccess(userId, roleIds)   → userAdminGuard.checkAssignRolesAccess(userId, roleIds)
// L119 kbAccessService.checkAnyAdminAccess()                     → userAdminGuard.checkAnyAdminAccess()
// L120 kbAccessService.checkUserManageAccess(userId)             → userAdminGuard.checkUserManageAccess(userId)
// L133 if (kbAccessService.isSuperAdmin())                       → if (currentUser.isSuperAdmin())
```

### 4.3 Step 8-10: 改 3 个 service

- [ ] **Step 8: 改 `UserServiceImpl`**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java:40,55,67,91,149,152,161,162`:

```java
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.framework.security.port.UserAdminGuard;
import com.nageoffer.ai.ragent.framework.security.port.SuperAdminInvariantGuard;

// 字段:
private final CurrentUserProbe currentUser;
private final UserAdminGuard userAdminGuard;
private final SuperAdminInvariantGuard superAdminGuard;
// 删除 KbAccessService

// caller:
// L67   if (!kbAccessService.isSuperAdmin() && kbAccessService.isDeptAdmin())
//       → if (!currentUser.isSuperAdmin() && currentUser.isDeptAdmin())
// L91   kbAccessService.checkCreateUserAccess(...) → userAdminGuard.checkCreateUserAccess(...)
// L149  && !kbAccessService.isSuperAdmin()         → && !currentUser.isSuperAdmin()
// L152  if (... && kbAccessService.isSuperAdmin()) → if (... && currentUser.isSuperAdmin())
// L161  if (kbAccessService.isUserSuperAdmin(id))  → if (currentUser.isUserSuperAdmin(id))
// L162  kbAccessService.simulateActiveSuperAdminCountAfter(...) → superAdminGuard.simulateActiveSuperAdminCountAfter(...)
```

- [ ] **Step 9: 改 `RoleServiceImpl`(c3 剩余 → c4 完整拆 4 字段)**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java:41,66,111,142,363,380,480,512`:

```java
import com.nageoffer.ai.ragent.framework.security.port.SuperAdminInvariantGuard;
import com.nageoffer.ai.ragent.framework.security.port.KbAccessCacheAdmin;
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;

// 字段(c3 末态有 KbAccessService + KbManageAccessPort,c4 改为 4 字段):
private final SuperAdminInvariantGuard superAdminGuard;
private final KbAccessCacheAdmin cacheAdmin;
private final KbManageAccessPort kbManageAccess;  // c3 已加,保留
private final CurrentUserProbe currentUser;
// 物理删除 private final KbAccessService kbAccessService;

// caller:
// L111 kbAccessService.simulateActiveSuperAdminCountAfter(...) → superAdminGuard.simulateActiveSuperAdminCountAfter(...)
// L142 kbAccessService.simulateActiveSuperAdminCountAfter(...) → superAdminGuard.simulateActiveSuperAdminCountAfter(...)
// L363 kbAccessService.simulateActiveSuperAdminCountAfter(...) → superAdminGuard.simulateActiveSuperAdminCountAfter(...)
// L380 kbAccessService.evictCache(userId)                       → cacheAdmin.evictCache(userId)
// L480 if (!kbAccessService.isSuperAdmin())                     → if (!currentUser.isSuperAdmin())
// L512 kbAccessService.evictCache(ur.getUserId())              → cacheAdmin.evictCache(ur.getUserId())

// 删除 import com.nageoffer.ai.ragent.user.service.KbAccessService;
```

- [ ] **Step 10: 改 `AccessServiceImpl`(批量版 + map 解构)**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java:42,66,183`:

```java
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import java.util.Map;

// 字段(L66):
// 旧: private final KbAccessService kbAccessService;
// 新: private final KbReadAccessPort kbReadAccess;

// 删除 import com.nageoffer.ai.ragent.user.service.KbAccessService;

// :183 改造(假设原代码在循环里逐个调,改为循环外一次批量调 + map.getOrDefault):
// 找到包含 kbAccessService.getMaxSecurityLevelForKb 的循环代码,改造模式:

// 旧(伪代码):
// for (KnowledgeBaseDO kb : kbList) {
//     Integer securityLevel = kbAccessService.getMaxSecurityLevelForKb(userId, kb.getId());
//     // ... build VO
// }

// 新(伪代码):
// Set<String> kbIdList = kbList.stream().map(KnowledgeBaseDO::getId).collect(Collectors.toSet());
// Map<String, Integer> levels = kbReadAccess.getMaxSecurityLevelsForKbs(userId, kbIdList);
// for (KnowledgeBaseDO kb : kbList) {
//     Integer securityLevel = levels.getOrDefault(kb.getId(), 0);
//     // ... build VO
// }
```

注意:实际改造前先读 `AccessServiceImpl.java` 的相关上下文(:170-200 左右),按现有 `kbList` / VO 构造代码做就地适配。该 commit message 必须明确"仅移除注入,不修 caller-context 语义"(Inv H,留 PR3 修)。

### 4.4 Step 11-13: 改 4 个测试 + Cross-cutting AccessServiceImpl T8 断言

- [ ] **Step 11: 改 `RoleControllerMutationAuthzTest`**

mock 从 `KbAccessService` 换 `UserAdminGuard` + `CurrentUserProbe`:

```java
UserAdminGuard userAdminGuard = mock(UserAdminGuard.class);
CurrentUserProbe currentUser = mock(CurrentUserProbe.class);

// stub:
doThrow(new ClientException("denied")).when(userAdminGuard).checkRoleMutation("d-pwm");
when(currentUser.isSuperAdmin()).thenReturn(false);
```

- [ ] **Step 12: 改 `RoleServiceImplDeleteTest` + `RoleServiceImplDeletePreviewTest`**

mock 拆 4 个,与生产代码字段对齐:

```java
SuperAdminInvariantGuard superAdminGuard = mock(SuperAdminInvariantGuard.class);
KbAccessCacheAdmin cacheAdmin = mock(KbAccessCacheAdmin.class);
KbManageAccessPort kbManageAccess = mock(KbManageAccessPort.class);
CurrentUserProbe currentUser = mock(CurrentUserProbe.class);

// stub 替换原 kbAccessService.simulateXxx → superAdminGuard.simulateXxx
// 等等
```

`TestServiceBuilders.roleService` 工厂方法相应改为接收 4 个 port:
```java
public static RoleServiceImpl roleService(
        SuperAdminInvariantGuard superAdminGuard,
        KbAccessCacheAdmin cacheAdmin,
        KbManageAccessPort kbManageAccess,
        CurrentUserProbe currentUser) {
    return new RoleServiceImpl(
            mock(RoleMapper.class),
            mock(RoleKbRelationMapper.class),
            mock(UserRoleMapper.class),
            mock(UserMapper.class),
            mock(SysDeptMapper.class),
            mock(KnowledgeBaseMapper.class),
            superAdminGuard,
            cacheAdmin,
            kbManageAccess,
            currentUser);
}
```

- [ ] **Step 13: 改 `AccessServiceImplTest`(T8 batch 迁移断言)**

```java
KbReadAccessPort kbReadAccess = mock(KbReadAccessPort.class);

// stub 批量调用:
when(kbReadAccess.getMaxSecurityLevelsForKbs("u-1", Set.of("kb-1", "kb-2")))
    .thenReturn(Map.of("kb-1", 2));  // kb-2 缺失测 getOrDefault

// 测试方法 javadoc:
/**
 * T8 — Batch 迁移断言。
 * PR2 改为批量版仅移除 KbAccessService 注入。caller-context 泄漏未修,
 * admin-views-target 路径 PR3 KbAccessCalculator 覆盖。
 */
@Test
void getKbAccessForUser_uses_batch_port_with_getOrDefault() {
    // ... 调用 AccessServiceImpl.getKbAccessForUser("u-1")
    // 断言:
    // 1. kbReadAccess.getMaxSecurityLevelsForKbs 被单次调用
    // 2. result for kb-1 → securityLevel = 2 (from map)
    // 3. result for kb-2 → securityLevel = 0 (getOrDefault default)
    verify(kbReadAccess, times(1)).getMaxSecurityLevelsForKbs(eq("u-1"), any());
}
```

### 4.5 Step 14-15: c4 验证 + commit

- [ ] **Step 14: 跑 c4 受影响 targeted tests**

```bash
mvn -pl bootstrap test -Dtest="*UserController*Test,*RoleController*Test,*AccessController*Test,*UserServiceImpl*Test,*RoleServiceImpl*Test,*AccessServiceImpl*Test" -q
```

Expected:全 PASS。

- [ ] **Step 15: c4 commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/UserAdminGuard.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/DashboardController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/AccessController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/SysDeptController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java \
        bootstrap/src/test/

git commit -m "$(cat <<'EOF'
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
EOF
)"
```

---

## Task 5: c5 — `KbAccessService` 接口标 `@Deprecated`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`

### 5.1 Step 1-2

- [ ] **Step 1: 改接口 `@Deprecated` + javadoc**

修改 `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`,在 `public interface KbAccessService` 上方加注解 + 改 javadoc:

```java
/**
 * 知识库访问权限服务(god-service,@Deprecated 缓冲接口)。
 *
 * <p>PR2 完成 RAG/KB/user 域迁移,本接口保留作过渡 deprecated 缓冲。
 * 新代码请注入 framework.security.port 下 8 个具体 port:
 * <ul>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe}</li>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort}</li>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.KbManageAccessPort}</li>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.UserAdminGuard}</li>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.SuperAdminInvariantGuard}</li>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.KbAccessCacheAdmin}</li>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader}</li>
 *   <li>{@link com.nageoffer.ai.ragent.framework.security.port.KbRoleBindingAdminPort}</li>
 * </ul>
 *
 * <p>PR3 + sharing PR D7 完成后开 cleanup PR 物理移除本接口及实现 implements。
 *
 * <p>Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §2.2
 */
@Deprecated(forRemoval = false)
public interface KbAccessService {
    // ... 接口方法签名零改动
}
```

- [ ] **Step 2: 跑全量测试,确认 `@Deprecated` 不破坏既有测试**

```bash
mvn -pl bootstrap test -q
```

Expected:仅已知 baseline-red 失败(10 errors),无新增失败。会有大量 deprecation warnings 在 `KbAccessServiceImpl` / 测试类(`KbAccessServiceImplTest` / `KbAccessServiceSystemActorTest`)— 这是预期的,**不要**给这些测试加 `@SuppressWarnings`(它们就是测 deprecated 实现)。

- [ ] **Step 3: c5 commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java

git commit -m "$(cat <<'EOF'
refactor(security): KbAccessService 接口标 @Deprecated,实现保留 implements (PR2 c5)

- @Deprecated(forRemoval = false) — 接口签名零改动
- javadoc 改写为"PR2 退役 god-service,新代码请注入 framework.security.port 8 个具体 port;
  PR3 + sharing PR D7 完成后开 cleanup PR 物理移除"
- KbAccessServiceImpl 保留 implements KbAccessService 作 ArchUnit allowlist 锚点
- 实现层零改动,所有 7+1 port 委托不变
- main 代码无任何 caller(c1-c4 已全部迁走,c6 ArchUnit 强制)

Roadmap: §3 阶段 A · PR2 commit 5
Spec: §2.2
EOF
)"
```

---

## Task 6: c6 — ArchUnit + Verification 脚本双护栏

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/KbAccessServiceRetirementArchTest.java`
- Create: `docs/dev/verification/permission-pr2-kb-access-retired.sh`

### 6.1 Step 1-2: 启用前 verification gate 自检

- [ ] **Step 1: 写 verification 脚本**

写到 `docs/dev/verification/permission-pr2-kb-access-retired.sh`:

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

```bash
chmod +x docs/dev/verification/permission-pr2-kb-access-retired.sh
```

- [ ] **Step 2: 跑 verification 脚本确认零注入**

```bash
bash docs/dev/verification/permission-pr2-kb-access-retired.sh
echo "exit=$?"
```

Expected: `OK: KbAccessService retired.` + `exit=0`。

如果失败:回到 Task 1-4 检查漏掉的 caller,**不**直接进 Step 3。

### 6.2 Step 3-5: 写 ArchUnit 测试

- [ ] **Step 3: 写 `KbAccessServiceRetirementArchTest`(T7 + T5.10 两条 resolver 子规则)**

写到 `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/KbAccessServiceRetirementArchTest.java`:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.arch;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.impl.KbAccessServiceImpl;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Locks PR2 KbAccessService retirement invariants (Inv E + Inv G).
 *
 * <p>Inv E: bootstrap 业务代码不得注入 KbAccessService god-service。
 * Exact-class allowlist:仅 {@link KbAccessService} 接口本身 + {@link KbAccessServiceImpl}
 * 委托者实现。包级 allowlist 会让 user.service.impl 下其他类(如 RoleServiceImpl)被豁免,
 * 规则失效。
 *
 * <p>Inv G: KbScopeResolverImpl 不直接读 ThreadLocal。SUPER/DEPT_ADMIN 判定从入参
 * LoginUser.getRoleTypes() 取,不依赖 UserContext 或 CurrentUserProbe。
 *
 * <p>Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR2
 * <p>Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §3.2 T7 + T5.10
 */
@AnalyzeClasses(
        packages = "com.nageoffer.ai.ragent",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class KbAccessServiceRetirementArchTest {

    /**
     * T7 — Inv E: 新代码不得依赖 KbAccessService god-service。
     */
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

    /**
     * T5.10 子规则 1 — Inv G: KbScopeResolverImpl 不直接读 UserContext ThreadLocal。
     */
    @ArchTest
    static final ArchRule kb_scope_resolver_must_not_depend_on_user_context =
            noClasses()
                    .that().haveSimpleName("KbScopeResolverImpl")
                    .should().dependOnClassesThat().haveFullyQualifiedName(UserContext.class.getName())
                    .because("Inv G: KbScopeResolverImpl 不直接读 ThreadLocal。SUPER/DEPT_ADMIN 判定从入参 "
                            + "LoginUser.getRoleTypes() 取。target-aware 完全消除 ThreadLocal 依赖留 PR3 "
                            + "KbAccessCalculator(roadmap §3 PR3 commit 1)。");

    /**
     * T5.10 子规则 2 — Inv G: KbScopeResolverImpl 不依赖 CurrentUserProbe(它内部读 UserContext)。
     */
    @ArchTest
    static final ArchRule kb_scope_resolver_must_not_depend_on_current_user_probe =
            noClasses()
                    .that().haveSimpleName("KbScopeResolverImpl")
                    .should().dependOnClassesThat().areAssignableTo(CurrentUserProbe.class)
                    .because("Inv G: KbScopeResolverImpl 不通过 CurrentUserProbe 间接读 ThreadLocal。"
                            + "SUPER/DEPT_ADMIN 判定从入参 LoginUser.getRoleTypes() 取。");
}
```

- [ ] **Step 4: 跑 ArchUnit 测试**

```bash
mvn -pl bootstrap test -Dtest=KbAccessServiceRetirementArchTest -q
```

Expected:3 ArchRules 全 PASS。

- [ ] **Step 5: 跑 PR1 既有 ArchUnit 测试 + 全量 baseline 对比**

```bash
mvn -pl bootstrap test -Dtest="PermissionBoundaryArchTest,KbAccessServiceRetirementArchTest" -q
mvn -pl bootstrap test -q  # 全量 — 仅已知 baseline-red 失败,无新增
```

Expected:PR1 + PR2 ArchUnit 全 PASS;全量 only 10 baseline-red errors。

### 6.3 Step 6: c6 commit

- [ ] **Step 6: c6 commit**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/KbAccessServiceRetirementArchTest.java \
        docs/dev/verification/permission-pr2-kb-access-retired.sh

git commit -m "$(cat <<'EOF'
test(security): KbAccessService god-service retirement gates (PR2 c6)

- KbAccessServiceRetirementArchTest:exact-class allowlist
  noClasses().doNotHaveFullyQualifiedName(KbAccessService) AND
  doNotHaveFullyQualifiedName(KbAccessServiceImpl)
  .should().dependOnClassesThat().areAssignableTo(KbAccessService)
- 同文件附 T5.10 两条子规则:KbScopeResolverImpl 不依赖 UserContext / CurrentUserProbe
- verification 脚本 docs/dev/verification/permission-pr2-kb-access-retired.sh
  Gate 1:rg -l 输出仅 KbAccessService.java + KbAccessServiceImpl.java
  Gate 2:rg "private final KbAccessService\b" 输出空
  EOF heredoc 形式定 EXPECTED,Windows Git Bash 兼容
- 测试包默认 ImportOption.DoNotIncludeTests 排除

Roadmap: §3 阶段 A · PR2 commit 6
Spec: §3.2 T7 §3.4
EOF
)"
```

---

## Pre-Merge 验证清单

c6 commit 后,在合并 PR 前完成:

- [ ] **PM-1: 跑全量 verification 脚本**

```bash
bash docs/dev/verification/permission-pr2-kb-access-retired.sh
echo "exit=$?"
```
Expected: `exit=0`。

- [ ] **PM-2: 跑全量 ArchUnit 套件**

```bash
mvn -pl bootstrap test -Dtest="*ArchTest" -q
```
Expected:`PermissionBoundaryArchTest`(PR1)+ `KbAccessServiceRetirementArchTest`(PR2)全 PASS。

- [ ] **PM-3: 跑全量 boundary tests**

```bash
mvn -pl bootstrap test -Dtest="*ServiceAuthBoundaryTest,KbAccessServiceSystemActorTest,PageQueryFailClosedTest" -q
```
Expected:全 PASS(PR1 21 boundary + 10 system actor + 2 fail-closed = 33 不变)。

- [ ] **PM-4: 跑全量 PR2 新增测试**

```bash
mvn -pl bootstrap test -Dtest="KbScopeResolverImplTest,AccessScopeServiceContractTest,KbAccessServiceRetirementArchTest,AccessServiceImplTest" -q
```
Expected:T5(9)+ T6(6)+ T7(3 ArchRules)+ T8 batch 断言 全 PASS。

- [ ] **PM-5: pre-merge 全量 baseline 对比**

```bash
mvn -pl bootstrap test -q
```
Expected:仅已知 baseline-red(10 errors:`MilvusCollectionTests` / `InvoiceIndexDocumentTests`×3 / `IntentTreeServiceTests.initFromFactory` / `VectorTreeIntentClassifierTests`×4 / `PgVectorStoreServiceTest.testChineseCharacterInsertion`),无新增失败。

- [ ] **PM-6: spotless 干净**

```bash
mvn spotless:check -q
```
Expected:无错误。如失败:`mvn spotless:apply` 自动修。

- [ ] **PM-7: Inv F + Inv G grep 自检**

```bash
# Inv F: scope 跨边界 sealed type,KnowledgeBasePageRequest.accessibleKbIds 已删
rg "Set<String>.*accessibleKbIds|null.*accessibleKbIds" bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service
# 期望:无输出

# Inv G: resolver 不直接读 ThreadLocal
rg "^import .*\.(UserContext|CurrentUserProbe);" bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java
rg "UserContext\.|CurrentUserProbe\b" bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java
# 期望:两个命令都无输出(javadoc 中无该符号)
```

- [ ] **PM-8: 6 路径 smoke(手工)**

按 spec §3.5 手工跑 6 路径(5 PR1 既有 + 1 PR2 新增):
1. OPS_ADMIN 重命名自家 KB → HTTP 200
2. FICC_USER 重命名 OPS KB → HTTP 200 + `success=false` + `无管理权限`
3. 文档上传 → MQ → executeChunk 完成
4a. 匿名 GET KB → HTTP 200 + `未登录或登录已过期`
4b. 缺失 UserContext service-level → `ClientException("missing user context")`
6. **新增**:DEPT_ADMIN ops_admin 调 `GET /knowledge-base?scope=owner` → 列表只含 OPS-* KB,不含 FICC KB

记录到 `docs/dev/verification/permission-pr2-smoke.md`(参照 PR1 smoke 模板)。

---

## 参考文档

- **Spec**:`docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md`
- **Roadmap**:`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 A · PR2
- **PR1 spec(参考不变量 A-D)**:`docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md`
- **PR1 plan(参考 commit / 测试模板)**:`docs/superpowers/plans/2026-04-26-permission-pr1-controller-thinning.md`
- **P0 audit(Deprecated 授权接口使用清单)**:`docs/dev/followup/2026-04-26-architecture-p0-audit-report.md`
- **PR1 verification(参考脚本风格)**:`docs/dev/verification/permission-pr1-controllers-clean.sh` + `permission-pr1-smoke.md`
