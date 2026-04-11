# PR3 — RBAC Frontend Demo Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the PR1 RBAC capabilities in the frontend so users can perform cross-department isolation demo, security_level isolation demo, and DEPT_ADMIN self-management entirely via UI — while removing all legacy `LoginUser.role` / `"admin".equals` references.

**Architecture:** Method C per spec §3 — "后端地基 → 前端垂直切片". Phase 0 adds SysDept CRUD, `UserProfileLoader`, extends `KbAccessService` with 11 new methods (incl. Last-SUPER_ADMIN post-mutation simulator), downgrades KB/doc/user/role write endpoints, and bundles the legacy-removal cut (LoginUser.role delete + frontend `permissions.ts` + router guards + sidebar filter) into one tight commit per Decision 3-F. Slices 1-5 then add per-page UI changes (department page greenfield, user page redesign, role page upgrade, KB dept_id field, document security_level full loop). Slices 8-9 produce fixture SQL + curl matrix + walkthrough + verification log.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis Plus, Sa-Token, PostgreSQL, RocketMQ 5.x, React 18, TypeScript, Vite, Zustand, shadcn/ui.

**TDD Exception (spec §10.1):** PR3 continues to skip unit TDD — test infrastructure (MockMvc / Sa-Token mock / Mockito) has not been introduced yet and is scheduled as independent P3 task. Instead, every task's verification surface is:
1. **Backend compile**: `mvn -pl bootstrap spotless:check && mvn -pl bootstrap install -DskipTests`
2. **Frontend compile**: `cd frontend && npm run build` (strict TypeScript)
3. **Curl bypass**: append to `docs/dev/pr3-curl-matrix.http` as new rule comes online
4. **Manual walkthrough**: visual verification per Slice 9 checklist

Every task below pairs code changes with at least one of (1)/(2)/(3). Manual walkthroughs are reserved for the final Slice 9 task.

**Spec reference:** `docs/superpowers/specs/2026-04-12-pr3-rbac-frontend-demo-design.md`

**Branch:** `feature/rbac-pr3-frontend-demo` (branched from `feature/rbac-security-level-pr1` per Decision C; rebased to main after PR1 merges)

---

## Commit Hygiene Rules (Decision 3-F)

- **Never push a commit where frontend cannot build.** TypeScript strict mode is the enforcement.
- Phase 0 backend changes are additive until Task 0.15. Task 0.15 is the one "breaking change bundle" commit that must land atomically.
- Slice tasks after Phase 0 are independent — commit per task.
- All commits follow conventional style: `<type>(pr3): <short>` — types: `feat`, `fix`, `refactor`, `docs`, `chore`.

---

## File Structure Overview

### Backend — NEW files (9)
```
bootstrap/src/main/java/com/nageoffer/ai/ragent/user/
├── dao/dto/
│   └── LoadedUserProfile.java                      (record, JOIN snapshot)
├── service/
│   ├── UserProfileLoader.java                      (interface)
│   ├── SysDeptService.java                         (interface)
│   └── impl/
│       ├── UserProfileLoaderImpl.java              (single JOIN, no cache)
│       └── SysDeptServiceImpl.java                 (CRUD + GLOBAL protect)
└── controller/
    ├── SysDeptController.java                      (5 endpoints @SaCheckRole SUPER_ADMIN)
    ├── request/SysDeptCreateRequest.java
    ├── request/SysDeptUpdateRequest.java
    └── vo/SysDeptVO.java
```

Plus 1 new auxiliary DTO inside KbAccessService package:
```
bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/
└── SuperAdminMutationIntent.java                   (sealed interface + 4 records)
```

### Backend — MOD files (18)
```
framework/src/main/java/com/nageoffer/ai/ragent/framework/context/
└── LoginUser.java                                  (DELETE `role` field)

bootstrap/src/main/java/com/nageoffer/ai/ragent/
├── user/controller/vo/
│   ├── LoginVO.java                                (extend; remove legacy role)
│   ├── CurrentUserVO.java                          (extend; remove legacy role)
│   └── UserVO.java                                 (extend list item)
├── user/controller/request/
│   ├── UserCreateRequest.java                      (add deptId, roleIds)
│   └── UserUpdateRequest.java                      (add deptId)
├── user/service/
│   ├── KbAccessService.java                        (11 new method signatures)
│   └── impl/
│       ├── KbAccessServiceImpl.java                (11 new impls + cache bypass)
│       ├── AuthServiceImpl.java                    (login via UserProfileLoader)
│       ├── UserServiceImpl.java                    (atomic create + delete pre-check)
│       └── RoleServiceImpl.java                    (3 Last-SUPER_ADMIN pre-checks)
├── user/controller/
│   ├── UserController.java                         (4 writes → new guards; drop StpUtil.checkRole)
│   └── RoleController.java                         (setUserRoles → checkAssignRolesAccess)
├── user/config/
│   └── UserContextInterceptor.java                 (delegate to UserProfileLoader)
├── knowledge/controller/
│   ├── KnowledgeBaseController.java                (2 writes + list admin.equals)
│   ├── KnowledgeDocumentController.java            (5 writes + new security-level endpoint + 2 admin.equals)
│   └── SpacesController.java                       (1 admin.equals)
├── knowledge/controller/request/
│   └── KnowledgeDocumentUpdateRequest.java         (DELETE securityLevel field)
├── knowledge/service/impl/
│   └── KnowledgeDocumentServiceImpl.java           (extract updateSecurityLevel method)
└── rag/service/impl/
    └── RAGChatServiceImpl.java                     (1 admin.equals)
```

### Frontend — NEW files (3)
```
frontend/src/
├── utils/
│   └── permissions.ts                              (getPermissions pure + usePermissions hook)
├── router/
│   └── guards.tsx                                  (RequireAuth/AnyAdmin/SuperAdmin/MenuAccess)
├── services/
│   └── sysDeptService.ts                           (5 API methods)
└── pages/admin/departments/
    └── DepartmentListPage.tsx                      (greenfield)
```

### Frontend — MOD files (~12)
```
frontend/src/
├── types/index.ts                                  (User shape migration)
├── stores/authStore.ts                             (new state shape)
├── services/
│   ├── authService.ts                              (LoginResponse typing)
│   ├── userService.ts                              (UserItem + CreatePayload + UpdatePayload)
│   ├── roleService.ts                              (RoleItem + roleType + binding.permission)
│   └── knowledgeService.ts                         (KB deptId + doc securityLevel + updateSecurityLevel)
├── router.tsx                                      (RequireAdmin → new guards)
├── pages/
│   ├── admin/AdminLayout.tsx                       (sidebar filter + roleLabel)
│   ├── admin/users/UserListPage.tsx                (table redesign + dialog)
│   ├── admin/roles/RoleListPage.tsx                (new columns + binding permission)
│   ├── admin/knowledge/KnowledgeListPage.tsx       (dept_id in create dialog)
│   └── admin/knowledge/KnowledgeDocumentsPage.tsx  (security_level column/dialog/edit)
├── components/chat/Sidebar.tsx                     (admin entry via permissions)
└── pages/SpacesPage.tsx                            (admin entry via permissions)
```

### Fixture + Docs — NEW (3)
```
resources/database/
└── fixture_pr3_demo.sql                            (idempotent demo data)

docs/dev/
├── pr3-demo-walkthrough.md                         (12-step demo script)
├── pr3-curl-matrix.http                            (HTTP client bypass suite)
└── pr3-verification-log.md                         (Slice 9 run log)
```

---

## Task Index

| # | Task | Area | Type |
|---|---|---|---|
| 0.1 | SysDept DTOs + VO | backend | additive |
| 0.2 | SysDeptService + Impl | backend | additive |
| 0.3 | SysDeptController | backend | additive |
| 0.4 | LoadedUserProfile + UserProfileLoader | backend | additive |
| 0.5 | LoginVO/CurrentUserVO extend (additive) + AuthServiceImpl/UserContextInterceptor wire | backend | additive |
| 0.6 | KbAccessService user management methods | backend | additive |
| 0.7 | KbAccessService KB/doc management methods | backend | additive |
| 0.8 | KbAccessService getAccessibleKbIds v2 + checkAccess v2 (DEPT_ADMIN-aware + cache bypass) | backend | behavioral |
| 0.9 | KbAccessService Last SUPER_ADMIN invariant + SuperAdminMutationIntent | backend | additive |
| 0.10 | KnowledgeDocumentController write endpoints + security-level endpoint | backend | behavioral |
| 0.11 | KnowledgeBaseController write endpoints + list admin.equals | backend | behavioral |
| 0.12 | UserController write endpoints + atomic create + delete pre-check | backend | behavioral |
| 0.13 | RoleServiceImpl Last SUPER_ADMIN pre-checks + RoleController.setUserRoles | backend | behavioral |
| 0.14 | Remaining 3 admin.equals cleanup (RAGChatServiceImpl, SpacesController, KnowledgeDocumentController.search) | backend | behavioral |
| 0.15 | **BUNDLE** Legacy removal: LoginUser.role delete + VO role removal + frontend User/authStore/permissions.ts/router guards/AdminLayout/admin entry buttons | both | breaking |
| 0.16 | Phase 0 integration smoke (backend up + frontend up + login round-trip) | both | verification |
| S1 | Slice 1: Department page frontend (service + page + router + menu) | frontend | greenfield |
| S2.1 | Slice 2a: User page — list view redesign | frontend | refactor |
| S2.2 | Slice 2b: User page — create/edit dialog with atomic create | frontend | refactor |
| S3.1 | Slice 3a: Role page — list columns + edit dialog | frontend | refactor |
| S3.2 | Slice 3b: Role page — KB binding dialog permission dropdown | frontend | refactor |
| S4 | Slice 4: KB create dialog dept_id field | frontend | refactor |
| S5.1 | Slice 5a: Document security_level — upload + list column | frontend | refactor |
| S5.2 | Slice 5b: Document security_level — detail page + edit button | frontend | refactor |
| S8.1 | Slice 8a: fixture_pr3_demo.sql idempotent | fixture | doc |
| S8.2 | Slice 8b: pr3-curl-matrix.http bypass suite | doc | doc |
| S8.3 | Slice 8c: pr3-demo-walkthrough.md + README cross-ref | doc | doc |
| S9 | Slice 9: Full rebuild + 12-step verification + log | verification | verification |

Total: **28 tasks**.

---

## Phase 0 Task Details

### Task 0.1: SysDept DTOs + VO

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/SysDeptCreateRequest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/SysDeptUpdateRequest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/SysDeptVO.java`

- [ ] **Step 1**: Create `SysDeptCreateRequest.java`

```java
package com.nageoffer.ai.ragent.user.controller.request;

import lombok.Data;

@Data
public class SysDeptCreateRequest {
    /** 部门编码，全局唯一，如 RND / LEGAL */
    private String deptCode;

    /** 部门显示名，如 研发部 */
    private String deptName;
}
```

- [ ] **Step 2**: Create `SysDeptUpdateRequest.java`

```java
package com.nageoffer.ai.ragent.user.controller.request;

import lombok.Data;

@Data
public class SysDeptUpdateRequest {
    /** 部门编码可改（GLOBAL 除外） */
    private String deptCode;

    /** 部门显示名 */
    private String deptName;
}
```

- [ ] **Step 3**: Create `SysDeptVO.java`

```java
package com.nageoffer.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SysDeptVO {
    private String id;
    private String deptCode;
    private String deptName;
    /** 该部门关联的用户数 */
    private Integer userCount;
    /** 该部门关联的知识库数 */
    private Integer kbCount;
    private Date createTime;
    private Date updateTime;
    /** GLOBAL 部门该字段为 true，前端据此禁用编辑/删除按钮 */
    private Boolean systemReserved;
}
```

- [ ] **Step 4**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS, no new errors.

- [ ] **Step 5**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/SysDeptCreateRequest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/SysDeptUpdateRequest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/SysDeptVO.java
git commit -m "feat(pr3): add SysDept DTOs and VO"
```

---

### Task 0.2: SysDeptService + Impl

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/SysDeptService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/SysDeptServiceImpl.java`

- [ ] **Step 1**: Create `SysDeptService.java`

```java
package com.nageoffer.ai.ragent.user.service;

import com.nageoffer.ai.ragent.user.controller.request.SysDeptCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.SysDeptVO;

import java.util.List;

/**
 * 部门管理服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>GLOBAL 部门（id='1' / dept_code='GLOBAL'）硬保护：不可删除、dept_code 不可改</li>
 *   <li>删除部门前校验：t_user 和 t_knowledge_base 中无引用，否则 409</li>
 *   <li>dept_code 唯一（数据库 uk_dept_code 约束 + 业务层预检）</li>
 * </ul>
 */
public interface SysDeptService {

    /** 列表 + 可选关键字过滤 */
    List<SysDeptVO> list(String keyword);

    /** 根据 id 查询，不存在返回 null */
    SysDeptVO getById(String id);

    /** 创建部门，返回新 id */
    String create(SysDeptCreateRequest request);

    /** 更新部门；GLOBAL 拒绝 */
    void update(String id, SysDeptUpdateRequest request);

    /** 删除部门；GLOBAL 或有引用时拒绝 */
    void delete(String id);
}
```

- [ ] **Step 2**: Create `SysDeptServiceImpl.java`

```java
package com.nageoffer.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.SysDeptVO;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysDeptServiceImpl implements SysDeptService {

    /** GLOBAL 部门硬编码 id，对应 init_data_pg.sql seed */
    public static final String GLOBAL_DEPT_ID = "1";
    public static final String GLOBAL_DEPT_CODE = "GLOBAL";

    private final SysDeptMapper sysDeptMapper;
    private final UserMapper userMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public List<SysDeptVO> list(String keyword) {
        var wrapper = Wrappers.lambdaQuery(SysDeptDO.class);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(SysDeptDO::getDeptName, keyword.trim())
                   .or()
                   .like(SysDeptDO::getDeptCode, keyword.trim());
        }
        wrapper.orderByAsc(SysDeptDO::getCreateTime);
        return sysDeptMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public SysDeptVO getById(String id) {
        SysDeptDO dept = sysDeptMapper.selectById(id);
        return dept == null ? null : toVO(dept);
    }

    @Override
    public String create(SysDeptCreateRequest request) {
        validateFields(request.getDeptCode(), request.getDeptName());
        // dept_code 唯一性预检（兜底数据库 uk_dept_code 约束）
        Long existing = sysDeptMapper.selectCount(
                Wrappers.lambdaQuery(SysDeptDO.class)
                        .eq(SysDeptDO::getDeptCode, request.getDeptCode().trim())
        );
        if (existing != null && existing > 0) {
            throw new ClientException("部门编码已存在: " + request.getDeptCode());
        }
        SysDeptDO dept = SysDeptDO.builder()
                .deptCode(request.getDeptCode().trim())
                .deptName(request.getDeptName().trim())
                .build();
        sysDeptMapper.insert(dept);
        return dept.getId();
    }

    @Override
    public void update(String id, SysDeptUpdateRequest request) {
        SysDeptDO dept = sysDeptMapper.selectById(id);
        if (dept == null) {
            throw new ClientException("部门不存在: " + id);
        }
        if (GLOBAL_DEPT_ID.equals(id)) {
            throw new ClientException("GLOBAL 部门不可修改");
        }
        validateFields(request.getDeptCode(), request.getDeptName());
        // 改动到 dept_code 时同样预检唯一性
        if (!dept.getDeptCode().equals(request.getDeptCode().trim())) {
            Long existing = sysDeptMapper.selectCount(
                    Wrappers.lambdaQuery(SysDeptDO.class)
                            .eq(SysDeptDO::getDeptCode, request.getDeptCode().trim())
                            .ne(SysDeptDO::getId, id)
            );
            if (existing != null && existing > 0) {
                throw new ClientException("部门编码已被其他部门占用: " + request.getDeptCode());
            }
        }
        dept.setDeptCode(request.getDeptCode().trim());
        dept.setDeptName(request.getDeptName().trim());
        sysDeptMapper.updateById(dept);
    }

    @Override
    public void delete(String id) {
        SysDeptDO dept = sysDeptMapper.selectById(id);
        if (dept == null) {
            throw new ClientException("部门不存在: " + id);
        }
        if (GLOBAL_DEPT_ID.equals(id)) {
            throw new ClientException("GLOBAL 部门不可删除");
        }
        Long userCount = userMapper.selectCount(
                Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getDeptId, id));
        if (userCount != null && userCount > 0) {
            throw new ClientException("部门下仍有 " + userCount + " 个用户，不可删除");
        }
        Long kbCount = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class).eq(KnowledgeBaseDO::getDeptId, id));
        if (kbCount != null && kbCount > 0) {
            throw new ClientException("部门下仍有 " + kbCount + " 个知识库，不可删除");
        }
        sysDeptMapper.deleteById(id);
    }

    private void validateFields(String deptCode, String deptName) {
        if (deptCode == null || deptCode.isBlank()) {
            throw new ClientException("部门编码不能为空");
        }
        if (deptName == null || deptName.isBlank()) {
            throw new ClientException("部门名称不能为空");
        }
        if (deptCode.length() > 32) {
            throw new ClientException("部门编码不能超过 32 字符");
        }
        if (deptName.length() > 64) {
            throw new ClientException("部门名称不能超过 64 字符");
        }
    }

    private SysDeptVO toVO(SysDeptDO dept) {
        Long userCount = userMapper.selectCount(
                Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getDeptId, dept.getId()));
        Long kbCount = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class).eq(KnowledgeBaseDO::getDeptId, dept.getId()));
        return new SysDeptVO(
                dept.getId(),
                dept.getDeptCode(),
                dept.getDeptName(),
                userCount == null ? 0 : userCount.intValue(),
                kbCount == null ? 0 : kbCount.intValue(),
                dept.getCreateTime(),
                dept.getUpdateTime(),
                GLOBAL_DEPT_ID.equals(dept.getId())
        );
    }
}
```

- [ ] **Step 3**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 4**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/SysDeptService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/SysDeptServiceImpl.java
git commit -m "feat(pr3): add SysDeptService with GLOBAL protection and reference-count deletion guard"
```

---

### Task 0.3: SysDeptController

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/SysDeptController.java`

- [ ] **Step 1**: Create controller with class-level SUPER_ADMIN guard

```java
package com.nageoffer.ai.ragent.user.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.SysDeptVO;
import com.nageoffer.ai.ragent.user.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@SaCheckRole("SUPER_ADMIN")
public class SysDeptController {

    private final SysDeptService sysDeptService;

    @GetMapping("/sys-dept")
    public Result<List<SysDeptVO>> list(@RequestParam(required = false) String keyword) {
        return Results.success(sysDeptService.list(keyword));
    }

    @GetMapping("/sys-dept/{id}")
    public Result<SysDeptVO> getById(@PathVariable String id) {
        return Results.success(sysDeptService.getById(id));
    }

    @PostMapping("/sys-dept")
    public Result<String> create(@RequestBody SysDeptCreateRequest request) {
        return Results.success(sysDeptService.create(request));
    }

    @PutMapping("/sys-dept/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody SysDeptUpdateRequest request) {
        sysDeptService.update(id, request);
        return Results.success();
    }

    @DeleteMapping("/sys-dept/{id}")
    public Result<Void> delete(@PathVariable String id) {
        sysDeptService.delete(id);
        return Results.success();
    }
}
```

- [ ] **Step 2**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 3**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/SysDeptController.java
git commit -m "feat(pr3): add SysDeptController with class-level SUPER_ADMIN guard"
```

---

### Task 0.4: LoadedUserProfile + UserProfileLoader

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/dto/LoadedUserProfile.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/UserProfileLoader.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserProfileLoaderImpl.java`

- [ ] **Step 1**: Create `LoadedUserProfile.java` as a record

```java
package com.nageoffer.ai.ragent.user.dao.dto;

import com.nageoffer.ai.ragent.framework.context.RoleType;

import java.util.List;
import java.util.Set;

/**
 * 用户身份快照（bootstrap 内部 DTO）。
 *
 * <p>单次 JOIN 的结果：t_user + sys_dept + t_user_role + t_role。
 * 从这里可投影到 LoginUser（授权用）/ LoginVO（登录响应）/ CurrentUserVO（/user/me 响应）。
 */
public record LoadedUserProfile(
        String userId,
        String username,
        String avatar,
        String deptId,
        String deptName,
        List<String> roleIds,
        Set<RoleType> roleTypes,
        int maxSecurityLevel,
        boolean isSuperAdmin,
        boolean isDeptAdmin
) {}
```

- [ ] **Step 2**: Create `UserProfileLoader.java` interface

```java
package com.nageoffer.ai.ragent.user.service;

import com.nageoffer.ai.ragent.user.dao.dto.LoadedUserProfile;

/**
 * 单一职责：给 userId 返回完整的 user+dept+role 快照。
 * 被 AuthServiceImpl.login / UserController.currentUser / UserContextInterceptor 共用。
 *
 * <p>PR3 不做 Redis 缓存（Decision 3-G）。每次都 JOIN。
 */
public interface UserProfileLoader {
    /**
     * @param userId 用户主键
     * @return 完整 profile；若用户不存在返回 null
     */
    LoadedUserProfile load(String userId);
}
```

- [ ] **Step 3**: Create `UserProfileLoaderImpl.java`

```java
package com.nageoffer.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.UserProfileLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserProfileLoaderImpl implements UserProfileLoader {

    private final UserMapper userMapper;
    private final SysDeptMapper sysDeptMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    @Override
    public LoadedUserProfile load(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        // dept
        String deptId = user.getDeptId();
        String deptName = null;
        if (deptId != null) {
            SysDeptDO dept = sysDeptMapper.selectById(deptId);
            if (dept != null) {
                deptName = dept.getDeptName();
            }
        }

        // user → roleIds
        List<String> roleIds = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId)
        ).stream().map(UserRoleDO::getRoleId).collect(Collectors.toList());

        // roleIds → roles
        Set<RoleType> roleTypes = EnumSet.noneOf(RoleType.class);
        int maxSecurityLevel = 0;
        if (!roleIds.isEmpty()) {
            List<RoleDO> roles = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds)
            );
            for (RoleDO role : roles) {
                if (role.getRoleType() != null) {
                    try {
                        roleTypes.add(RoleType.valueOf(role.getRoleType()));
                    } catch (IllegalArgumentException ignored) {
                        // 未知 role_type，跳过
                    }
                }
                if (role.getMaxSecurityLevel() != null && role.getMaxSecurityLevel() > maxSecurityLevel) {
                    maxSecurityLevel = role.getMaxSecurityLevel();
                }
            }
        }

        boolean isSuperAdmin = roleTypes.contains(RoleType.SUPER_ADMIN);
        boolean isDeptAdmin = roleTypes.contains(RoleType.DEPT_ADMIN);

        return new LoadedUserProfile(
                user.getId(),
                user.getUsername(),
                user.getAvatar(),
                deptId,
                deptName,
                Collections.unmodifiableList(roleIds),
                Collections.unmodifiableSet(roleTypes),
                maxSecurityLevel,
                isSuperAdmin,
                isDeptAdmin
        );
    }
}
```

**Notes:**
- `RoleDO` must have `roleType: String` and `maxSecurityLevel: Integer` fields. Verify before editing this file; if not present, add them first (PR1 should have already).
- Validation of this task is purely compile; runtime wiring comes in Task 0.5.

- [ ] **Step 4**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS. If `RoleDO.getRoleType()` or `getMaxSecurityLevel()` missing, add them to RoleDO first (PR1 should have done this, but verify).

- [ ] **Step 5**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/dto/LoadedUserProfile.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/UserProfileLoader.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserProfileLoaderImpl.java
git commit -m "feat(pr3): add UserProfileLoader with single-JOIN snapshot (no cache per Decision 3-G)"
```

---

### Task 0.5: LoginVO/CurrentUserVO extend (additive) + wire via UserProfileLoader

**Note:** This task is *additive only* — it keeps the legacy `role` field on LoginVO/CurrentUserVO/LoginUser untouched. The field removal is bundled into Task 0.15.

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/LoginVO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/CurrentUserVO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AuthServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java`

- [ ] **Step 1**: Read `LoginVO.java`, add new fields additively while keeping existing `role` field

New LoginVO shape (show full file after edit):

```java
package com.nageoffer.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginVO {
    private String userId;
    private String username;
    /** @deprecated 保留给 Task 0.15 移除 */
    @Deprecated
    private String role;
    private String token;
    private String avatar;
    // --- PR3 新增 ---
    private String deptId;
    private String deptName;
    private List<String> roleTypes;
    private Integer maxSecurityLevel;
    private Boolean isSuperAdmin;
    private Boolean isDeptAdmin;
}
```

- [ ] **Step 2**: Same for `CurrentUserVO.java` (drop `token` and keep `role` legacy-deprecated, add all PR3 fields)

```java
package com.nageoffer.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CurrentUserVO {
    private String userId;
    private String username;
    /** @deprecated 保留给 Task 0.15 移除 */
    @Deprecated
    private String role;
    private String avatar;
    // --- PR3 新增 ---
    private String deptId;
    private String deptName;
    private List<String> roleTypes;
    private Integer maxSecurityLevel;
    private Boolean isSuperAdmin;
    private Boolean isDeptAdmin;
}
```

- [ ] **Step 3**: Update `AuthServiceImpl.login()` to assemble via `UserProfileLoader`

Before (line 57):
```java
return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
```

After:
```java
LoadedUserProfile profile = userProfileLoader.load(loginId);
if (profile == null) {
    throw new ClientException("加载用户资料失败");
}
LoginVO vo = new LoginVO();
vo.setUserId(profile.userId());
vo.setUsername(profile.username());
vo.setRole(user.getRole());                // legacy, Task 0.15 removes
vo.setToken(StpUtil.getTokenValue());
vo.setAvatar(avatar);
vo.setDeptId(profile.deptId());
vo.setDeptName(profile.deptName());
vo.setRoleTypes(profile.roleTypes().stream().map(Enum::name).toList());
vo.setMaxSecurityLevel(profile.maxSecurityLevel());
vo.setIsSuperAdmin(profile.isSuperAdmin());
vo.setIsDeptAdmin(profile.isDeptAdmin());
return vo;
```

Add constructor-injection `UserProfileLoader userProfileLoader` to `AuthServiceImpl`.

- [ ] **Step 4**: Update `UserController.currentUser()` similarly

Before (lines 55-64):
```java
@GetMapping("/user/me")
public Result<CurrentUserVO> currentUser() {
    LoginUser user = UserContext.requireUser();
    return Results.success(new CurrentUserVO(
            user.getUserId(),
            user.getUsername(),
            user.getRole(),
            user.getAvatar()
    ));
}
```

After:
```java
@GetMapping("/user/me")
public Result<CurrentUserVO> currentUser() {
    LoginUser user = UserContext.requireUser();
    LoadedUserProfile profile = userProfileLoader.load(user.getUserId());
    if (profile == null) {
        throw new ClientException("加载用户资料失败");
    }
    CurrentUserVO vo = new CurrentUserVO();
    vo.setUserId(profile.userId());
    vo.setUsername(profile.username());
    vo.setRole(user.getRole());                // legacy, Task 0.15 removes
    vo.setAvatar(profile.avatar());
    vo.setDeptId(profile.deptId());
    vo.setDeptName(profile.deptName());
    vo.setRoleTypes(profile.roleTypes().stream().map(Enum::name).toList());
    vo.setMaxSecurityLevel(profile.maxSecurityLevel());
    vo.setIsSuperAdmin(profile.isSuperAdmin());
    vo.setIsDeptAdmin(profile.isDeptAdmin());
    return Results.success(vo);
}
```

Add constructor-injection `UserProfileLoader userProfileLoader` to `UserController`.

- [ ] **Step 5**: Update `UserContextInterceptor` to delegate to the loader

Read the current interceptor first (it currently does a JOIN inline around line 95). Replace the inline JOIN logic with:

```java
LoadedUserProfile profile = userProfileLoader.load(userId);
if (profile == null) {
    return true;   // or whatever the existing "skip set" path is
}
LoginUser loginUser = LoginUser.builder()
        .userId(profile.userId())
        .username(profile.username())
        .role(user.getRole())                // legacy, still populated until Task 0.15
        .avatar(profile.avatar())
        .deptId(profile.deptId())
        .roleTypes(profile.roleTypes())
        .maxSecurityLevel(profile.maxSecurityLevel())
        .build();
UserContext.set(loginUser);
```

Inject `UserProfileLoader` via constructor.

- [ ] **Step 6**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 7**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/LoginVO.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/CurrentUserVO.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AuthServiceImpl.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java
git commit -m "feat(pr3): extend LoginVO/CurrentUserVO additively and wire via UserProfileLoader"
```

---

### Task 0.6: KbAccessService user management methods

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1**: Add interface methods per spec §5.1

Insert into `KbAccessService.java` after existing methods:

```java
// === PR3 新增：用户管理授权 ===

/**
 * 创建用户授权。SUPER_ADMIN 任何 deptId；
 * DEPT_ADMIN 仅 targetDeptId == self.deptId 且 roleIds 中不含 role_type=SUPER_ADMIN 的角色。
 */
void checkCreateUserAccess(String targetDeptId, java.util.List<String> roleIds);

/**
 * 改/删用户授权。SUPER_ADMIN 任何 target；
 * DEPT_ADMIN 仅当 target.deptId == self.deptId。
 */
void checkUserManageAccess(String targetUserId);

/**
 * 分配角色授权。
 * SUPER_ADMIN 任何；DEPT_ADMIN 仅当 target.deptId == self.deptId
 * 且 newRoleIds 中不含 role_type=SUPER_ADMIN 的角色。
 */
void checkAssignRolesAccess(String targetUserId, java.util.List<String> newRoleIds);

/**
 * 当前是否是 DEPT_ADMIN（任一部门）。
 */
boolean isDeptAdmin();
```

- [ ] **Step 2**: Implement in `KbAccessServiceImpl.java`

```java
@Override
public boolean isDeptAdmin() {
    if (!UserContext.hasUser()) {
        return false;
    }
    LoginUser user = UserContext.get();
    return user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.DEPT_ADMIN);
}

@Override
public void checkCreateUserAccess(String targetDeptId, java.util.List<String> roleIds) {
    if (!UserContext.hasUser()) {
        throw new ClientException("未登录用户不可创建用户");
    }
    if (isSuperAdmin()) {
        return;
    }
    LoginUser user = UserContext.get();
    if (!isDeptAdmin()) {
        throw new ClientException("无权创建用户");
    }
    if (user.getDeptId() == null || !user.getDeptId().equals(targetDeptId)) {
        throw new ClientException("DEPT_ADMIN 只能在本部门创建用户");
    }
    // 禁止给新用户分配 role_type=SUPER_ADMIN 的角色
    if (roleIds != null && !roleIds.isEmpty()) {
        long superRoleCount = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .in(RoleDO::getId, roleIds)
                        .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
        ).size();
        if (superRoleCount > 0) {
            throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
        }
    }
}

@Override
public void checkUserManageAccess(String targetUserId) {
    if (!UserContext.hasUser()) {
        throw new ClientException("未登录用户不可管理用户");
    }
    if (isSuperAdmin()) {
        return;
    }
    if (!isDeptAdmin()) {
        throw new ClientException("无权管理用户");
    }
    LoginUser current = UserContext.get();
    UserDO target = userMapper.selectById(targetUserId);
    if (target == null) {
        throw new ClientException("目标用户不存在");
    }
    if (target.getDeptId() == null || !target.getDeptId().equals(current.getDeptId())) {
        throw new ClientException("DEPT_ADMIN 只能管理本部门用户");
    }
}

@Override
public void checkAssignRolesAccess(String targetUserId, java.util.List<String> newRoleIds) {
    // 先复用用户管理权校验
    checkUserManageAccess(targetUserId);
    if (isSuperAdmin()) {
        return;   // SUPER_ADMIN 可分配任意角色
    }
    // DEPT_ADMIN：newRoleIds 里不能有 SUPER_ADMIN 角色
    if (newRoleIds != null && !newRoleIds.isEmpty()) {
        long superRoleCount = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .in(RoleDO::getId, newRoleIds)
                        .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
        ).size();
        if (superRoleCount > 0) {
            throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
        }
    }
}
```

Inject `UserMapper userMapper` and `RoleMapper roleMapper` via constructor if not already present.

- [ ] **Step 3**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 4**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "feat(pr3): add KbAccessService user management authz methods"
```

---

### Task 0.7: KbAccessService KB/doc management methods

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1**: Add interface methods per spec §5.1

```java
// === PR3 新增：KB / 文档管理授权 ===

/**
 * 创建 KB 时的权限解析器（Decision 3-H）。
 * - 未登录：抛 ClientException("未登录用户不可创建知识库")；无 GLOBAL fallback
 * - SUPER_ADMIN：返回 requestedDeptId，空则 fallback GLOBAL_DEPT_ID ("1")
 * - DEPT_ADMIN：强制 self.deptId；若 requestedDeptId 非空且 != self.deptId 抛 403
 * - USER：抛 403
 */
String resolveCreateKbDeptId(String requestedDeptId);

/**
 * 文档级管理权：doc → kb → checkManageAccess(kb.id)。
 */
void checkDocManageAccess(String docId);

/**
 * 文档 security_level 修改专用（目前等同 checkDocManageAccess，保留独立方法以便未来加 level-specific 规则）。
 */
void checkDocSecurityLevelAccess(String docId, int newLevel);
```

- [ ] **Step 2**: Implement

```java
@Override
public String resolveCreateKbDeptId(String requestedDeptId) {
    if (!UserContext.hasUser() || UserContext.getUserId() == null) {
        throw new ClientException("未登录用户不可创建知识库");
    }
    if (isSuperAdmin()) {
        return (requestedDeptId == null || requestedDeptId.isBlank())
                ? SysDeptServiceImpl.GLOBAL_DEPT_ID
                : requestedDeptId;
    }
    if (!isDeptAdmin()) {
        throw new ClientException("无权创建知识库");
    }
    LoginUser user = UserContext.get();
    String selfDeptId = user.getDeptId();
    if (selfDeptId == null) {
        throw new ClientException("当前 DEPT_ADMIN 用户未挂载部门");
    }
    if (requestedDeptId != null && !requestedDeptId.isBlank()
            && !requestedDeptId.equals(selfDeptId)) {
        throw new ClientException("DEPT_ADMIN 只能在本部门创建知识库");
    }
    return selfDeptId;
}

@Override
public void checkDocManageAccess(String docId) {
    if (!UserContext.hasUser() || UserContext.getUserId() == null) {
        return;   // 系统态
    }
    if (isSuperAdmin()) {
        return;
    }
    KnowledgeDocumentDO doc = knowledgeDocumentMapper.selectById(docId);
    if (doc == null) {
        throw new ClientException("文档不存在: " + docId);
    }
    checkManageAccess(doc.getKbId());
}

@Override
public void checkDocSecurityLevelAccess(String docId, int newLevel) {
    checkDocManageAccess(docId);
    // 当前与 checkDocManageAccess 等价；未来可加 newLevel 相关的细粒度规则
}
```

Inject `KnowledgeDocumentMapper knowledgeDocumentMapper` via constructor.

- [ ] **Step 3**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 4**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "feat(pr3): add KbAccessService KB/doc management authz with resolveCreateKbDeptId"
```

---

### Task 0.8: KbAccessService getAccessibleKbIds v2 + checkAccess v2 (DEPT_ADMIN aware + cache bypass)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

This is a **behavioral change**, not additive. The method signatures stay the same but the logic changes per spec §5.1 javadoc.

- [ ] **Step 1**: Rewrite `getAccessibleKbIds(String userId, Permission minPermission)` method

Replacement implementation:

```java
@Override
public Set<String> getAccessibleKbIds(String userId, Permission minPermission) {
    // SUPER_ADMIN 全量，不走缓存
    if (isSuperAdmin()) {
        return knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getId)
        ).stream().map(KnowledgeBaseDO::getId).collect(Collectors.toSet());
    }

    // DEPT_ADMIN bypass cache —— 每次 JOIN: RBAC 授权 KB ∪ 同部门 KB
    if (isDeptAdmin()) {
        return computeDeptAdminAccessibleKbIds(userId, minPermission);
    }

    // USER 走 PR1 原有缓存路径
    boolean cacheable = minPermission == Permission.READ;
    String cacheKey = CACHE_PREFIX + userId;
    if (cacheable) {
        RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
        Set<String> cached = bucket.get();
        if (cached != null) {
            return cached;
        }
    }
    Set<String> result = computeRbacKbIds(userId, minPermission);
    if (cacheable) {
        redissonClient.getBucket(cacheKey).<Set<String>>set(result, CACHE_TTL);
    }
    return result;
}

/** DEPT_ADMIN 的可见 KB = RBAC 授权 KB ∪ 本部门所有 KB */
private Set<String> computeDeptAdminAccessibleKbIds(String userId, Permission minPermission) {
    Set<String> rbacKbs = computeRbacKbIds(userId, minPermission);
    LoginUser user = UserContext.get();
    String deptId = user.getDeptId();
    if (deptId != null) {
        List<KnowledgeBaseDO> deptKbs = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getDeptId, deptId)
                        .select(KnowledgeBaseDO::getId)
        );
        deptKbs.stream().map(KnowledgeBaseDO::getId).forEach(rbacKbs::add);
    }
    return rbacKbs;
}

/** 原 PR1 RBAC 路径抽成私有方法（user → roles → kb_relations → filter permission） */
private Set<String> computeRbacKbIds(String userId, Permission minPermission) {
    List<UserRoleDO> userRoles = userRoleMapper.selectList(
            Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId));
    if (userRoles.isEmpty()) {
        return new java.util.HashSet<>();
    }
    List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();
    List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
            Wrappers.lambdaQuery(RoleKbRelationDO.class).in(RoleKbRelationDO::getRoleId, roleIds));
    Set<String> kbIds = relations.stream()
            .filter(r -> permissionSatisfies(r.getPermission(), minPermission))
            .map(RoleKbRelationDO::getKbId)
            .collect(Collectors.toSet());
    if (!kbIds.isEmpty()) {
        List<KnowledgeBaseDO> validKbs = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .in(KnowledgeBaseDO::getId, kbIds)
                        .select(KnowledgeBaseDO::getId));
        kbIds = validKbs.stream().map(KnowledgeBaseDO::getId).collect(Collectors.toSet());
    }
    return kbIds;
}
```

- [ ] **Step 2**: Update `checkAccess(String kbId)` to include DEPT_ADMIN own-dept fast path

```java
@Override
public void checkAccess(String kbId) {
    if (!UserContext.hasUser() || UserContext.getUserId() == null) {
        return;  // 系统态
    }
    if (isSuperAdmin()) {
        return;
    }
    if (isDeptAdmin()) {
        // DEPT_ADMIN 同部门 KB 直接放行（不走缓存）
        LoginUser user = UserContext.get();
        if (user.getDeptId() != null) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
            if (kb != null && user.getDeptId().equals(kb.getDeptId())) {
                return;
            }
        }
        // 否则退化到 RBAC 检查
    }
    Set<String> accessible = getAccessibleKbIds(UserContext.getUserId(), Permission.READ);
    if (!accessible.contains(kbId)) {
        throw new ClientException("无权访问该知识库: " + kbId);
    }
}
```

- [ ] **Step 3**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 4**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "refactor(pr3): DEPT_ADMIN-aware getAccessibleKbIds/checkAccess with cache bypass"
```

---

### Task 0.9: KbAccessService Last SUPER_ADMIN invariant + SuperAdminMutationIntent

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/SuperAdminMutationIntent.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1**: Create `SuperAdminMutationIntent.java` — sealed interface + 4 records

```java
package com.nageoffer.ai.ragent.user.service;

import java.util.List;

/**
 * Last SUPER_ADMIN invariant 模拟器的输入语义（Decision 3-M）。
 * 4 种 mutation 各对应一个 record。
 */
public sealed interface SuperAdminMutationIntent
        permits SuperAdminMutationIntent.DeleteUser,
                SuperAdminMutationIntent.ReplaceUserRoles,
                SuperAdminMutationIntent.ChangeRoleType,
                SuperAdminMutationIntent.DeleteRole {

    /** 删除某个用户，该用户的所有 user-role 关联作废 */
    record DeleteUser(String userId) implements SuperAdminMutationIntent {}

    /** 用 newRoleIds 替换 userId 的角色集（对应 setUserRoles） */
    record ReplaceUserRoles(String userId, List<String> newRoleIds) implements SuperAdminMutationIntent {}

    /** 改变某角色的 role_type */
    record ChangeRoleType(String roleId, String newRoleType) implements SuperAdminMutationIntent {}

    /** 删除某角色，所有用到它的 user-role 关联作废 */
    record DeleteRole(String roleId) implements SuperAdminMutationIntent {}
}
```

- [ ] **Step 2**: Add interface methods to `KbAccessService.java`

```java
// === Last SUPER_ADMIN 系统级硬不变量（Decision 3-M）===

/**
 * 当前系统内有效 SUPER_ADMIN 用户数量。
 * 有效 = t_user.deleted=0 AND t_user_role.deleted=0 AND t_role.deleted=0 AND t_role.role_type='SUPER_ADMIN'。
 * 不要 JOIN t_role_kb_relation —— KB 绑定与超管身份无关。
 */
int countActiveSuperAdmins();

/**
 * 判断某用户当前是否有任一有效 SUPER_ADMIN 角色。
 */
boolean isUserSuperAdmin(String userId);

/**
 * Post-mutation 模拟器：返回 mutation 执行后剩余的有效 SUPER_ADMIN 用户数量。
 * 调用方用 {@code < 1} 判断是否拒绝。
 * 禁止使用 pre-mutation 的 count==1 作判据。
 */
int simulateActiveSuperAdminCountAfter(SuperAdminMutationIntent intent);
```

- [ ] **Step 3**: Implement in `KbAccessServiceImpl.java`

```java
@Override
public int countActiveSuperAdmins() {
    // SQL: SELECT DISTINCT ur.user_id
    //      FROM t_user_role ur
    //      JOIN t_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.role_type = 'SUPER_ADMIN'
    //      JOIN t_user u ON u.id = ur.user_id AND u.deleted = 0
    //      WHERE ur.deleted = 0
    return countSuperAdminsExcluding(java.util.Set.of(), java.util.Set.of(), java.util.Map.of());
}

@Override
public boolean isUserSuperAdmin(String userId) {
    if (userId == null) return false;
    List<RoleDO> superAdminRoles = roleMapper.selectList(
            Wrappers.lambdaQuery(RoleDO.class)
                    .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
    );
    if (superAdminRoles.isEmpty()) return false;
    Set<String> superAdminRoleIds = superAdminRoles.stream()
            .map(RoleDO::getId).collect(Collectors.toSet());
    Long count = userRoleMapper.selectCount(
            Wrappers.lambdaQuery(UserRoleDO.class)
                    .eq(UserRoleDO::getUserId, userId)
                    .in(UserRoleDO::getRoleId, superAdminRoleIds)
    );
    return count != null && count > 0;
}

@Override
public int simulateActiveSuperAdminCountAfter(SuperAdminMutationIntent intent) {
    return switch (intent) {
        case SuperAdminMutationIntent.DeleteUser du ->
                countSuperAdminsExcluding(java.util.Set.of(du.userId()), java.util.Set.of(), java.util.Map.of());
        case SuperAdminMutationIntent.ReplaceUserRoles rur ->
                countSuperAdminsExcluding(java.util.Set.of(), java.util.Set.of(),
                        java.util.Map.of(rur.userId(), new java.util.HashSet<>(rur.newRoleIds())));
        case SuperAdminMutationIntent.ChangeRoleType crt -> {
            // 若新 role_type 不是 SUPER_ADMIN，该 role 从有效 SUPER_ADMIN 来源集里剔除
            if (!RoleType.SUPER_ADMIN.name().equals(crt.newRoleType())) {
                yield countSuperAdminsExcluding(java.util.Set.of(), java.util.Set.of(crt.roleId()), java.util.Map.of());
            }
            yield countActiveSuperAdmins();
        }
        case SuperAdminMutationIntent.DeleteRole dr ->
                countSuperAdminsExcluding(java.util.Set.of(), java.util.Set.of(dr.roleId()), java.util.Map.of());
    };
}

/**
 * 核心聚合：基于当前 DB 快照计算"在给定的排除条件下"还有多少有效 SUPER_ADMIN 用户。
 *
 * @param excludedUserIds 视为已删除的用户 id
 * @param invalidatedRoleIds 视为"不再是 SUPER_ADMIN 来源"的 role id
 * @param userRoleOverrides 用户的模拟角色集覆盖（用于 ReplaceUserRoles；key=userId, value=新 roleIds）
 */
private int countSuperAdminsExcluding(Set<String> excludedUserIds,
                                      Set<String> invalidatedRoleIds,
                                      java.util.Map<String, Set<String>> userRoleOverrides) {
    // 1. 有效 SUPER_ADMIN role id 集合（剔除 invalidatedRoleIds）
    List<RoleDO> superRoles = roleMapper.selectList(
            Wrappers.lambdaQuery(RoleDO.class)
                    .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
    );
    Set<String> validSuperRoleIds = superRoles.stream()
            .map(RoleDO::getId)
            .filter(id -> !invalidatedRoleIds.contains(id))
            .collect(Collectors.toSet());
    if (validSuperRoleIds.isEmpty()) return 0;

    // 2. 对每个 user 判断其"模拟后角色集"是否与 validSuperRoleIds 有交集
    List<UserDO> allUsers = userMapper.selectList(
            Wrappers.lambdaQuery(UserDO.class).select(UserDO::getId)
    );
    int count = 0;
    for (UserDO user : allUsers) {
        if (excludedUserIds.contains(user.getId())) continue;
        Set<String> effectiveRoleIds;
        if (userRoleOverrides.containsKey(user.getId())) {
            effectiveRoleIds = userRoleOverrides.get(user.getId());
        } else {
            effectiveRoleIds = userRoleMapper.selectList(
                    Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, user.getId())
            ).stream().map(UserRoleDO::getRoleId).collect(Collectors.toSet());
        }
        for (String rid : effectiveRoleIds) {
            if (validSuperRoleIds.contains(rid)) {
                count++;
                break;
            }
        }
    }
    return count;
}
```

**Perf note:** For a small user base (< 10k), this O(U·R_avg) loop is fine. Writing-plans flags this for P3 (test infra PR) to add a single SQL query optimization.

- [ ] **Step 4**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 5**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/SuperAdminMutationIntent.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "feat(pr3): add Last SUPER_ADMIN post-mutation simulator + SuperAdminMutationIntent"
```

---

### Task 0.10: KnowledgeDocumentController write endpoints + security-level endpoint

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUpdateRequest.java`

- [ ] **Step 1**: In `KnowledgeDocumentServiceImpl.java`, extract `updateSecurityLevel(String docId, Integer newLevel)` from the existing `update()` method (lines 531-575 per spec §5.6)

New public method on service interface:
```java
/**
 * 更新文档 security_level 并异步刷新 OpenSearch（走 RocketMQ 事务消息）。
 * PR3 新抽出，Controller 通过专用 endpoint 调用，不再复用 update()。
 */
void updateSecurityLevel(String docId, Integer newLevel);
```

Impl: move the existing `security_level != null` branch logic from `update()` into this new method. Call sites:
- New endpoint `PUT /knowledge-base/docs/{docId}/security-level` → this method
- Existing `update()` method → now **throws** if the caller tries to pass a `securityLevel` (defense against Task 0.10.2 DTO change)

- [ ] **Step 2**: In `KnowledgeDocumentUpdateRequest.java`, **delete** `private Integer securityLevel;` field (line 68)

This is a backend-only breaking change for this DTO. The frontend has not started sending this field in practice (current UI doesn't have a security_level edit button on the general update form). Verify by grep:
```bash
```
Run: `grep -rn "securityLevel" frontend/src/services/knowledgeService.ts frontend/src/pages/admin/knowledge/`
Expected: no matches for updating via general update endpoint (upload is OK because that field stays on `KnowledgeDocumentUploadRequest`).

- [ ] **Step 3**: Update `KnowledgeDocumentController.java` — 5 write endpoints switched to `checkDocManageAccess`/`checkManageAccess`, plus new `security-level` endpoint

Replacements (before → after):

```java
// Line 67-73 upload
@PostMapping(value = "/knowledge-base/{kb-id}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public Result<KnowledgeDocumentVO> upload(@PathVariable("kb-id") String kbId,
                                          @RequestPart(value = "file", required = false) MultipartFile file,
                                          @ModelAttribute KnowledgeDocumentUploadRequest requestParam) {
    kbAccessService.checkManageAccess(kbId);   // was: checkAccess(kbId)
    return Results.success(documentService.upload(kbId, requestParam, file));
}
```

```java
// Line 78-83 startChunk
@PostMapping("/knowledge-base/docs/{doc-id}/chunk")
public Result<Void> startChunk(@PathVariable(value = "doc-id") String docId) {
    kbAccessService.checkDocManageAccess(docId);   // was: checkDocAccess(docId)
    documentService.startChunk(docId);
    return Results.success();
}
```

```java
// Line 88-93 delete
@DeleteMapping("/knowledge-base/docs/{doc-id}")
public Result<Void> delete(@PathVariable(value = "doc-id") String docId) {
    kbAccessService.checkDocManageAccess(docId);
    documentService.delete(docId);
    return Results.success();
}
```

```java
// Line 110-116 update
@PutMapping("/knowledge-base/docs/{docId}")
public Result<Void> update(@PathVariable String docId,
                           @RequestBody KnowledgeDocumentUpdateRequest requestParam) {
    kbAccessService.checkDocManageAccess(docId);
    documentService.update(docId, requestParam);
    return Results.success();
}
```

```java
// Line 144-150 enable
@PatchMapping("/knowledge-base/docs/{docId}/enable")
public Result<Void> enable(@PathVariable String docId,
                           @RequestParam("value") boolean enabled) {
    kbAccessService.checkDocManageAccess(docId);
    documentService.enable(docId, enabled);
    return Results.success();
}
```

**Remove** the private `checkDocAccess(String docId)` helper since all callers now use `checkDocManageAccess`.

Add new endpoint:
```java
/**
 * 修改文档安全等级（专用入口；不走通用 update）。
 * 后端校验 checkDocSecurityLevelAccess，然后异步触发 RocketMQ SecurityLevelRefreshEvent 刷新 OpenSearch。
 */
@PutMapping("/knowledge-base/docs/{docId}/security-level")
public Result<Void> updateSecurityLevel(@PathVariable String docId,
                                        @RequestBody UpdateSecurityLevelRequest requestParam) {
    if (requestParam.getNewLevel() == null || requestParam.getNewLevel() < 0 || requestParam.getNewLevel() > 3) {
        throw new ClientException("newLevel 必须在 0-3 之间");
    }
    kbAccessService.checkDocSecurityLevelAccess(docId, requestParam.getNewLevel());
    documentService.updateSecurityLevel(docId, requestParam.getNewLevel());
    return Results.success();
}

@Data
public static class UpdateSecurityLevelRequest {
    private Integer newLevel;
}
```

- [ ] **Step 4**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 5**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUpdateRequest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "refactor(pr3): fix document write authz (READ bug) + add dedicated security-level endpoint"
```

---

### Task 0.11: KnowledgeBaseController write endpoints + list admin.equals

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`

- [ ] **Step 1**: Remove `@SaCheckRole("SUPER_ADMIN")` from `create` (line 60)

Before:
```java
@SaCheckRole("SUPER_ADMIN")
@PostMapping("/knowledge-base")
public Result<String> create(@RequestBody KnowledgeBaseCreateRequest request) {
    return Results.success(knowledgeBaseService.create(request));
}
```

After:
```java
@PostMapping("/knowledge-base")
public Result<String> create(@RequestBody KnowledgeBaseCreateRequest request) {
    // 授权 + dept 解析合一，service 层兜底
    return Results.success(knowledgeBaseService.create(request));
}
```

- [ ] **Step 2**: In `KnowledgeBaseServiceImpl.create()`, call `kbAccessService.resolveCreateKbDeptId(request.getDeptId())` at the start and use the returned value as the effective dept_id:

```java
public String create(KnowledgeBaseCreateRequest request) {
    String effectiveDeptId = kbAccessService.resolveCreateKbDeptId(request.getDeptId());
    // ... existing create logic, but set record.setDeptId(effectiveDeptId) instead of request.getDeptId()
}
```

- [ ] **Step 3**: Replace `@SaCheckRole("SUPER_ADMIN")` with programmatic `checkManageAccess` on `update` (line 69) and `delete` (line 80)

Before:
```java
@SaCheckRole("SUPER_ADMIN")
@PutMapping("/knowledge-base/{id}")
public Result<Void> update(@PathVariable String id, @RequestBody KnowledgeBaseUpdateRequest request) {
    knowledgeBaseService.update(id, request);
    return Results.success();
}
```

After:
```java
@PutMapping("/knowledge-base/{id}")
public Result<Void> update(@PathVariable String id, @RequestBody KnowledgeBaseUpdateRequest request) {
    kbAccessService.checkManageAccess(id);
    knowledgeBaseService.update(id, request);
    return Results.success();
}
```

Same pattern for `delete`.

- [ ] **Step 4**: Replace `"admin".equals` at line 102 (list endpoint) with `isSuperAdmin()`

Before:
```java
if (UserContext.hasUser() && !"admin".equals(UserContext.getRole())) {
    accessibleKbIds = kbAccessService.getAccessibleKbIds(UserContext.getUserId());
}
```

After:
```java
if (UserContext.hasUser() && !kbAccessService.isSuperAdmin()) {
    accessibleKbIds = kbAccessService.getAccessibleKbIds(UserContext.getUserId());
}
```

- [ ] **Step 5**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 6**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java
git commit -m "refactor(pr3): downgrade KB write endpoints + replace admin.equals in list"
```

---

### Task 0.12: UserController write endpoints + atomic create + delete pre-check

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserCreateRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserUpdateRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/UserVO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java`

- [ ] **Step 1**: Extend `UserCreateRequest.java` with `deptId: String` and `roleIds: List<String>`

```java
@Data
public class UserCreateRequest {
    private String username;
    private String password;
    private String role;           // legacy, kept until Task 0.15
    private String avatar;
    // --- PR3 新增 ---
    private String deptId;
    private java.util.List<String> roleIds;
}
```

- [ ] **Step 2**: Extend `UserUpdateRequest.java` with `deptId`

```java
@Data
public class UserUpdateRequest {
    private String username;
    private String role;           // legacy
    private String avatar;
    private String password;
    // --- PR3 新增 ---
    private String deptId;
}
```

- [ ] **Step 3**: Extend `UserVO.java` with PR3 list fields

```java
@Data
public class UserVO {
    private String id;
    private String username;
    private String role;           // legacy
    private String avatar;
    private java.util.Date createTime;
    private java.util.Date updateTime;
    // --- PR3 新增 ---
    private String deptId;
    private String deptName;
    private java.util.List<String> roleTypes;
    private Integer maxSecurityLevel;
}
```

- [ ] **Step 4**: Update `UserServiceImpl.create()` to be transactional atomic user + user_role write

```java
@Transactional(rollbackFor = Exception.class)
public String create(UserCreateRequest requestParam) {
    // 1. 授权
    kbAccessService.checkCreateUserAccess(requestParam.getDeptId(), requestParam.getRoleIds());

    // 2. 写 t_user（沿用现有 validation/normalize 逻辑）
    UserDO record = ...;    // keep existing builder logic
    record.setDeptId(requestParam.getDeptId());
    userMapper.insert(record);

    // 3. 批量写 t_user_role
    if (requestParam.getRoleIds() != null) {
        for (String roleId : requestParam.getRoleIds()) {
            UserRoleDO ur = new UserRoleDO();
            ur.setUserId(record.getId());
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }
    return record.getId();
}
```

Inject `KbAccessService kbAccessService` and `UserRoleMapper userRoleMapper`.

- [ ] **Step 5**: Update `UserServiceImpl.delete()` with Last SUPER_ADMIN pre-check

```java
public void delete(String id) {
    // Last SUPER_ADMIN invariant
    if (kbAccessService.isUserSuperAdmin(id)) {
        int after = kbAccessService.simulateActiveSuperAdminCountAfter(
                new SuperAdminMutationIntent.DeleteUser(id)
        );
        if (after < 1) {
            throw new ClientException("不能删除该用户：此操作会使系统失去最后一个 SUPER_ADMIN");
        }
    }
    // ... existing delete logic (soft delete)
}
```

- [ ] **Step 6**: Update `UserController.java` — 4 write endpoints switched

Before:
```java
@GetMapping("/users")
public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
    StpUtil.checkRole("SUPER_ADMIN");
    return Results.success(userService.pageQuery(requestParam));
}

@PostMapping("/users")
public Result<String> create(@RequestBody UserCreateRequest requestParam) {
    StpUtil.checkRole("SUPER_ADMIN");
    return Results.success(userService.create(requestParam));
}

@PutMapping("/users/{id}")
public Result<Void> update(@PathVariable String id, @RequestBody UserUpdateRequest requestParam) {
    StpUtil.checkRole("SUPER_ADMIN");
    userService.update(id, requestParam);
    return Results.success();
}

@DeleteMapping("/users/{id}")
public Result<Void> delete(@PathVariable String id) {
    StpUtil.checkRole("SUPER_ADMIN");
    userService.delete(id);
    return Results.success();
}
```

After:
```java
@GetMapping("/users")
public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
    if (!kbAccessService.isSuperAdmin() && !kbAccessService.isDeptAdmin()) {
        throw new ClientException("无权访问用户列表");
    }
    return Results.success(userService.pageQuery(requestParam));
}

@PostMapping("/users")
public Result<String> create(@RequestBody UserCreateRequest requestParam) {
    // 授权在 UserServiceImpl.create() 内通过 checkCreateUserAccess 完成
    return Results.success(userService.create(requestParam));
}

@PutMapping("/users/{id}")
public Result<Void> update(@PathVariable String id, @RequestBody UserUpdateRequest requestParam) {
    kbAccessService.checkUserManageAccess(id);
    userService.update(id, requestParam);
    return Results.success();
}

@DeleteMapping("/users/{id}")
public Result<Void> delete(@PathVariable String id) {
    kbAccessService.checkUserManageAccess(id);
    userService.delete(id);
    return Results.success();
}
```

Inject `KbAccessService kbAccessService` via constructor.

- [ ] **Step 7**: `UserServiceImpl.pageQuery` — add DEPT_ADMIN dept-scoped filter

Inside the existing `pageQuery` method, if `!kbAccessService.isSuperAdmin() && kbAccessService.isDeptAdmin()`, add a `WHERE dept_id = currentUser.deptId` filter to the MyBatis Plus wrapper.

- [ ] **Step 8**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 9**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserCreateRequest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserUpdateRequest.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/UserVO.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java
git commit -m "refactor(pr3): downgrade UserController writes + atomic create + Last-super-admin delete pre-check"
```

---

### Task 0.13: RoleServiceImpl Last SUPER_ADMIN pre-checks + RoleController.setUserRoles

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java`

- [ ] **Step 1**: Add pre-check to `RoleServiceImpl.setUserRoles(userId, roleIds)`

```java
public void setUserRoles(String userId, List<String> roleIds) {
    // Last SUPER_ADMIN invariant
    int after = kbAccessService.simulateActiveSuperAdminCountAfter(
            new SuperAdminMutationIntent.ReplaceUserRoles(userId, roleIds)
    );
    if (after < 1) {
        throw new ClientException("不能修改该用户角色：此操作会使系统失去最后一个 SUPER_ADMIN");
    }
    // ... existing replace logic (delete old user_roles, insert new)
}
```

- [ ] **Step 2**: Add pre-check to `RoleServiceImpl.updateRole(roleId, ...)` when role_type changes

```java
public void updateRole(String roleId, String name, String description, String roleType, Integer maxSecurityLevel) {
    RoleDO existing = roleMapper.selectById(roleId);
    if (existing == null) {
        throw new ClientException("角色不存在");
    }
    // 若 role_type 降级，触发 Last SUPER_ADMIN 不变量校验
    if (RoleType.SUPER_ADMIN.name().equals(existing.getRoleType())
            && roleType != null && !RoleType.SUPER_ADMIN.name().equals(roleType)) {
        int after = kbAccessService.simulateActiveSuperAdminCountAfter(
                new SuperAdminMutationIntent.ChangeRoleType(roleId, roleType)
        );
        if (after < 1) {
            throw new ClientException("不能降级该角色：此操作会使系统失去最后一个 SUPER_ADMIN");
        }
    }
    // ... existing update
}
```

Note: `updateRole` signature may need extension — if current signature is `(roleId, name, description)`, extend to `(roleId, name, description, roleType, maxSecurityLevel)` (PR1 added `role_type` / `max_security_level` columns on `t_role` but the service may not yet accept them). Add them now.

- [ ] **Step 3**: Add pre-check to `RoleServiceImpl.deleteRole(roleId)`

```java
public void deleteRole(String roleId) {
    RoleDO role = roleMapper.selectById(roleId);
    if (role == null) {
        throw new ClientException("角色不存在");
    }
    if (RoleType.SUPER_ADMIN.name().equals(role.getRoleType())) {
        int after = kbAccessService.simulateActiveSuperAdminCountAfter(
                new SuperAdminMutationIntent.DeleteRole(roleId)
        );
        if (after < 1) {
            throw new ClientException("不能删除该角色：此操作会使系统失去最后一个 SUPER_ADMIN");
        }
    }
    // ... existing delete
}
```

Inject `KbAccessService kbAccessService` into `RoleServiceImpl` if not already.

- [ ] **Step 4**: In `RoleController.setUserRoles` (line 78-83), remove `@SaCheckRole` and call `checkAssignRolesAccess`

Before:
```java
@SaCheckRole("SUPER_ADMIN")
@PutMapping("/user/{userId}/roles")
public Result<Void> setUserRoles(@PathVariable String userId, @RequestBody List<String> roleIds) {
    roleService.setUserRoles(userId, roleIds);
    return Results.success();
}
```

After:
```java
@PutMapping("/user/{userId}/roles")
public Result<Void> setUserRoles(@PathVariable String userId, @RequestBody List<String> roleIds) {
    kbAccessService.checkAssignRolesAccess(userId, roleIds);
    roleService.setUserRoles(userId, roleIds);
    return Results.success();
}
```

Inject `KbAccessService kbAccessService` into `RoleController`.

- [ ] **Step 5**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 6**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java
git commit -m "feat(pr3): RoleServiceImpl Last-SUPER_ADMIN pre-checks + RoleController.setUserRoles authz"
```

---

### Task 0.14: Remaining 3 admin.equals cleanup

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` (line 114)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java` (line 54)
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java` (line 135, search endpoint)

- [ ] **Step 1**: `RAGChatServiceImpl:114` replacement

Before:
```java
if (UserContext.hasUser() && userId != null && !"admin".equals(UserContext.getRole())) {
    // RBAC filter ...
}
```

After:
```java
if (UserContext.hasUser() && userId != null && !kbAccessService.isSuperAdmin()) {
    // RBAC filter ...
}
```

Inject `KbAccessService kbAccessService` if not already.

- [ ] **Step 2**: `SpacesController:54` replacement

Before:
```java
boolean isAdmin = "admin".equals(UserContext.getRole());
```

After:
```java
boolean isAdmin = kbAccessService.isSuperAdmin();
```

Inject `KbAccessService kbAccessService`.

- [ ] **Step 3**: `KnowledgeDocumentController:135` replacement

Before:
```java
if (UserContext.hasUser() && !"admin".equals(UserContext.getRole())) {
    accessibleKbIds = kbAccessService.getAccessibleKbIds(UserContext.getUserId());
}
```

After:
```java
if (UserContext.hasUser() && !kbAccessService.isSuperAdmin()) {
    accessibleKbIds = kbAccessService.getAccessibleKbIds(UserContext.getUserId());
}
```

- [ ] **Step 4**: Verify zero `"admin".equals` remain in bootstrap Java code

Run: `grep -rn "\"admin\".equals" bootstrap/src/main/java/`
Expected: only javadoc matches (if any), no code matches.

- [ ] **Step 5**: Compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 6**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java
git commit -m "refactor(pr3): replace remaining 3 admin.equals call sites with isSuperAdmin()"
```

---

### Task 0.15: BUNDLE — Legacy removal (backend LoginUser.role + VO role + frontend User/authStore/permissions.ts/guards/AdminLayout)

**⚠️ This is the only breaking-change bundle of Phase 0.** Per Decision 3-F, all sub-steps below MUST land in the same commit (or a tightly sequenced pair where both endpoints are compile-clean). Do not push intermediate states.

**Files:**

Backend MOD:
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java` — DELETE `role` field
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/LoginVO.java` — DELETE `role` field
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/CurrentUserVO.java` — DELETE `role` field
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/UserVO.java` — DELETE `role` field
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserCreateRequest.java` — DELETE `role` field
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserUpdateRequest.java` — DELETE `role` field
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AuthServiceImpl.java` — remove `vo.setRole(...)` line
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java` — remove `vo.setRole(...)` line in currentUser()
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java` — remove `.role(...)` builder call
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java` — remove legacy `normalizeRole`/`getRole`/`setRole` references; rely on `roleIds` for role wiring

Frontend NEW:
- `frontend/src/utils/permissions.ts`
- `frontend/src/router/guards.tsx`

Frontend MOD:
- `frontend/src/types/index.ts` — `User` type migration
- `frontend/src/stores/authStore.ts` — new state shape
- `frontend/src/services/authService.ts` — LoginResponse typing
- `frontend/src/router.tsx` — replace `RequireAdmin` wrappers with new guards
- `frontend/src/pages/admin/AdminLayout.tsx` — sidebar filter + roleLabel 4-tier + menu id field
- `frontend/src/pages/admin/users/UserListPage.tsx` — remove references to `user.role === "admin"` (only visibility conditions; form rebuild comes in Slice S2)
- `frontend/src/pages/SpacesPage.tsx` — admin entry condition switch
- `frontend/src/components/chat/Sidebar.tsx` — admin entry condition switch (if it has one)
- `frontend/src/pages/LoginPage.tsx` — post-login navigation unchanged (/spaces), but make sure stored user has new shape

---

- [ ] **Step 1**: Delete `role` field from backend DTOs/VOs

Edit each of the 6 backend files (LoginVO, CurrentUserVO, UserVO, UserCreateRequest, UserUpdateRequest, LoginUser.java) — remove the `private String role;` line (and the `@Deprecated` above it if present).

For `AuthServiceImpl.login()`: delete the line `vo.setRole(user.getRole());` (from Task 0.5). The remaining fields are sufficient.

For `UserController.currentUser()`: delete `vo.setRole(user.getRole());`.

For `UserContextInterceptor`: delete `.role(user.getRole())` from the builder chain.

For `UserServiceImpl`: grep for any remaining `.role(`, `.getRole()`, `.setRole(`, `normalizeRole`, etc. that reference `t_user.role` column or `UserDO.role`. **Important:** `UserDO.role` column on the DB stays (Sa-Token compat layer uses it), but the business logic should not read/write it. Keep it as a passive column; if `UserServiceImpl.create` originally set `record.setRole(requestParam.getRole())` with `requestParam.getRole()` pulling from the legacy field, replace with `record.setRole("user")` as a default constant (or whatever the legacy default was) — but remove any *conditional* role writing. Simpler: `record.setRole("user")` unconditionally. The actual authz is via `t_user_role` now.

- [ ] **Step 2**: Create `frontend/src/utils/permissions.ts` per spec §6.2

```typescript
import { useMemo } from "react";
import { useAuthStore } from "@/stores/authStore";
import type { User } from "@/types";

export type AdminMenuId =
  | "dashboard"
  | "knowledge"
  | "users"
  | "departments"
  | "intent-tree"
  | "ingestion"
  | "mappings"
  | "traces"
  | "evaluations"
  | "sample-questions"
  | "roles"
  | "settings";

const DEPT_VISIBLE: AdminMenuId[] = ["dashboard", "knowledge", "users"];

export interface Permissions {
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
  isAnyAdmin: boolean;
  deptId: string | null;
  deptName: string | null;
  maxSecurityLevel: number;
  canSeeAdminMenu: boolean;
  canSeeMenuItem: (id: AdminMenuId) => boolean;
  canCreateKb: (targetDeptId: string) => boolean;
  canManageKb: (kb: { deptId: string }) => boolean;
  canManageUser: (targetUser: { deptId: string | null }) => boolean;
  canEditDocSecurityLevel: (doc: { kbDeptId: string }) => boolean;
  canAssignRole: (role: { roleType: string }) => boolean;
}

export function getPermissions(user: User | null): Permissions {
  const isSuperAdmin = user?.isSuperAdmin ?? false;
  const isDeptAdmin = user?.isDeptAdmin ?? false;
  const isAnyAdmin = isSuperAdmin || isDeptAdmin;
  return {
    isSuperAdmin,
    isDeptAdmin,
    isAnyAdmin,
    deptId: user?.deptId ?? null,
    deptName: user?.deptName ?? null,
    maxSecurityLevel: user?.maxSecurityLevel ?? 0,
    canSeeAdminMenu: isAnyAdmin,
    canSeeMenuItem: (id) => isSuperAdmin || (isDeptAdmin && DEPT_VISIBLE.includes(id)),
    canCreateKb: (targetDeptId) =>
      isSuperAdmin || (isDeptAdmin && targetDeptId === user?.deptId),
    canManageKb: (kb) =>
      isSuperAdmin || (isDeptAdmin && kb.deptId === user?.deptId),
    canManageUser: (targetUser) =>
      isSuperAdmin || (isDeptAdmin && targetUser.deptId === user?.deptId),
    canEditDocSecurityLevel: (doc) =>
      isSuperAdmin || (isDeptAdmin && doc.kbDeptId === user?.deptId),
    canAssignRole: (role) => isSuperAdmin || role.roleType !== "SUPER_ADMIN",
  };
}

export function usePermissions(): Permissions {
  const user = useAuthStore((s) => s.user);
  return useMemo(() => getPermissions(user), [user]);
}
```

- [ ] **Step 3**: Create `frontend/src/router/guards.tsx`

```tsx
import { Navigate } from "react-router-dom";
import { toast } from "sonner";
import type { ReactNode } from "react";
import { usePermissions, type AdminMenuId } from "@/utils/permissions";
import { useAuthStore } from "@/stores/authStore";

export function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export function RequireAnyAdmin({ children }: { children: ReactNode }) {
  const { canSeeAdminMenu } = usePermissions();
  if (!canSeeAdminMenu) return <Navigate to="/spaces" replace />;
  return <>{children}</>;
}

export function RequireSuperAdmin({ children }: { children: ReactNode }) {
  const { isSuperAdmin } = usePermissions();
  if (!isSuperAdmin) {
    toast.info("您没有此页面的访问权限");
    return <Navigate to="/admin/dashboard" replace />;
  }
  return <>{children}</>;
}

export function RequireMenuAccess({
  menuId,
  children,
}: {
  menuId: AdminMenuId;
  children: ReactNode;
}) {
  const { canSeeMenuItem } = usePermissions();
  if (!canSeeMenuItem(menuId)) {
    toast.info("您没有此页面的访问权限");
    return <Navigate to="/admin/dashboard" replace />;
  }
  return <>{children}</>;
}
```

- [ ] **Step 4**: Update `frontend/src/types/index.ts` — `User` type migration

Before:
```typescript
export interface User {
  id: string;
  username: string;
  role: string;
  avatar?: string;
}
```

After:
```typescript
export interface User {
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
```

- [ ] **Step 5**: Update `frontend/src/stores/authStore.ts`

The `login` method must now parse the new `LoginVO` shape:
```typescript
const { data } = await login(username, password);
set({
  token: data.token,
  user: {
    userId: data.userId,
    username: data.username,
    avatar: data.avatar,
    deptId: data.deptId,
    deptName: data.deptName,
    roleTypes: data.roleTypes ?? [],
    maxSecurityLevel: data.maxSecurityLevel ?? 0,
    isSuperAdmin: data.isSuperAdmin ?? false,
    isDeptAdmin: data.isDeptAdmin ?? false,
  },
});
localStorage.setItem("token", data.token);
localStorage.setItem("user", JSON.stringify(get().user));
```

Similar for `checkAuth()` which calls `GET /user/me` and parses the new `CurrentUserVO` shape.

- [ ] **Step 6**: Update `frontend/src/services/authService.ts` `LoginResponse` type

```typescript
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
```

- [ ] **Step 7**: Update `frontend/src/router.tsx` — replace `RequireAdmin` usages

Grep for `RequireAdmin` in router.tsx. For each admin route:
- Top-level `/admin` → wrap with `<RequireAnyAdmin>`
- Dashboard/knowledge/users → wrap with `<RequireMenuAccess menuId="dashboard|knowledge|users">`
- Everything else → wrap with `<RequireSuperAdmin>`
- Delete the old `RequireAdmin` component definition

Example structure:
```tsx
<Route
  path="/admin"
  element={
    <RequireAuth>
      <RequireAnyAdmin>
        <AdminLayout />
      </RequireAnyAdmin>
    </RequireAuth>
  }
>
  <Route path="dashboard" element={<RequireMenuAccess menuId="dashboard"><DashboardPage /></RequireMenuAccess>} />
  <Route path="knowledge" element={<RequireMenuAccess menuId="knowledge"><KnowledgeListPage /></RequireMenuAccess>} />
  <Route path="users" element={<RequireMenuAccess menuId="users"><UserListPage /></RequireMenuAccess>} />
  <Route path="departments" element={<RequireSuperAdmin><DepartmentListPage /></RequireSuperAdmin>} />
  <Route path="roles" element={<RequireSuperAdmin><RoleListPage /></RequireSuperAdmin>} />
  <Route path="intent-tree" element={<RequireSuperAdmin><IntentTreePage /></RequireSuperAdmin>} />
  {/* ... 其他都是 RequireSuperAdmin */}
</Route>
```

**Note:** `DepartmentListPage` doesn't exist yet in this commit — it'll be created in Slice S1. For Task 0.15, put a placeholder import:
```tsx
const DepartmentListPage = () => <div>部门管理（Slice 1 待实现）</div>;
```
Or import from `pages/admin/departments/DepartmentListPage` only if the file is created in a prior commit. Simpler: leave the `/admin/departments` route out until Slice S1 adds it. The menu won't point to it yet.

- [ ] **Step 8**: Update `frontend/src/pages/admin/AdminLayout.tsx`

Add `id: AdminMenuId` to each `menuGroups` item. Example:
```tsx
{
  id: "dashboard",
  path: "/admin/dashboard",
  label: "Dashboard",
  icon: LayoutDashboard
}
```

Add `permissions` hook + filtering:
```tsx
const permissions = usePermissions();
const visibleMenuGroups = useMemo(
  () =>
    menuGroups
      .map((group) => ({
        ...group,
        items: group.items.filter((item) =>
          permissions.canSeeMenuItem(item.id as AdminMenuId)
        ),
      }))
      .filter((group) => group.items.length > 0),
  [permissions]
);
```

Use `visibleMenuGroups` in the render loop instead of `menuGroups`.

Update `roleLabel` logic (was `user?.role === "admin" ? "管理员" : "成员"`):
```tsx
const roleLabel = useMemo(() => {
  if (permissions.isSuperAdmin) return "超级管理员";
  if (permissions.isDeptAdmin && permissions.deptName) return `${permissions.deptName}管理员`;
  if (permissions.isDeptAdmin) return "部门管理员";
  return "成员";
}, [permissions]);
```

- [ ] **Step 9**: Update `frontend/src/pages/SpacesPage.tsx` and `frontend/src/components/chat/Sidebar.tsx` admin entry button

Grep for `user?.role === "admin"` or `user.role === "admin"` in those files. Replace with `usePermissions().canSeeAdminMenu`.

- [ ] **Step 10**: Update `frontend/src/pages/admin/users/UserListPage.tsx` for compile-clean

This task doesn't rebuild the user page form (that's Slice S2). It only removes references to the old `user.role` field that would break TypeScript compile. Specifically:
- Remove `const roleOptions = [...]` constant (unused after legacy removal) — actually keep it for Slice S2, but reference `user.roleTypes` where needed
- Any `user.role === "admin"` visibility conditions → `user.isSuperAdmin` or `usePermissions().isSuperAdmin`
- `isProtectedAdmin(user)` → keep the function but change its body from `user.username === "admin"` to `user.username === "admin"` (stays — it's a seed-user UI marker, not a role check)

The detailed form rebuild is Slice S2. For this task, minimum edit to pass TypeScript strict mode.

- [ ] **Step 11**: Grep final check

Run:
```bash
grep -rn "user\.role" frontend/src/ --include="*.tsx" --include="*.ts"
```
Expected: no matches except in docs/comments.

Run:
```bash
grep -rn "user?.role" frontend/src/ --include="*.tsx" --include="*.ts"
```
Expected: no matches.

Run:
```bash
grep -rn "LoginUser\.role\|\.getRole()\|setRole(" bootstrap/src/main/java/com/nageoffer/ai/ragent/user/ bootstrap/src/main/java/com/nageoffer/ai/ragent/framework/
```
Expected: no business-layer code matches. DAO-layer UserDO may still have `getRole`/`setRole` accessors because `t_user.role` column is kept for Sa-Token compat — that's OK as long as the accessors are not called from business logic.

- [ ] **Step 12**: Backend compile check

Run: `mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 13**: Frontend compile check

Run: `cd frontend && npm run build`
Expected: `vite build` succeeds, no TypeScript errors.

- [ ] **Step 14**: Commit — **single bundle commit per Decision 3-F**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/LoginVO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/CurrentUserVO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/UserVO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserCreateRequest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/request/UserUpdateRequest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AuthServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java \
        frontend/src/utils/permissions.ts \
        frontend/src/router/guards.tsx \
        frontend/src/types/index.ts \
        frontend/src/stores/authStore.ts \
        frontend/src/services/authService.ts \
        frontend/src/router.tsx \
        frontend/src/pages/admin/AdminLayout.tsx \
        frontend/src/pages/admin/users/UserListPage.tsx \
        frontend/src/pages/SpacesPage.tsx \
        frontend/src/components/chat/Sidebar.tsx
git commit -m "refactor(pr3)!: remove LoginUser.role field + migrate frontend to permissions hook

BREAKING CHANGE: LoginUser / LoginVO / CurrentUserVO no longer expose \`role\` field.
Frontend User type migrates from \`role: string\` to
\`{ roleTypes, isSuperAdmin, isDeptAdmin, deptId, deptName, maxSecurityLevel }\`.
Adds utils/permissions.ts (getPermissions pure fn + usePermissions hook)
and router/guards.tsx (RequireAnyAdmin / RequireSuperAdmin / RequireMenuAccess).
AdminLayout sidebar filters by role; roleLabel uses 4-tier fallback
(超级管理员 / \${deptName}管理员 / 部门管理员 / 成员).

Per Decision 3-F (compile-clean commit hygiene), backend contract change
and frontend migration are bundled atomically."
```

---

### Task 0.16: Phase 0 integration smoke

**Files:** none modified. This is a verification step.

- [ ] **Step 1**: Database rebuild (per spec §10.3)

Run:
```bash
docker exec postgres psql -U postgres -c "DROP DATABASE ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql
curl -X DELETE "http://localhost:9200/_all"
docker exec redis redis-cli FLUSHDB
```

- [ ] **Step 2**: Backend start

```powershell
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'
mvn -pl bootstrap spring-boot:run
```
Expected: Spring Boot banner, port 9090 listening, no startup errors.

- [ ] **Step 3**: Frontend dev server

```bash
cd frontend && npm run dev
```
Expected: Vite dev server on default port (5173), no errors.

- [ ] **Step 4**: Login smoke test via UI

Open http://localhost:5173/login → log in as admin/123456 → expect:
- Redirect to /spaces
- Navigate to /admin/dashboard manually
- See 11 menu items (this commit does not have the Department page / menu yet — that's Slice S1, so count is 11; it will become 12 after S1)
- Header shows "超级管理员"

- [ ] **Step 5**: API smoke via curl

```bash
# Get token from browser localStorage or by:
TOKEN=$(curl -s -X POST http://localhost:9090/api/ragent/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | jq -r '.data.token')

# Check /user/me returns new shape
curl -s http://localhost:9090/api/ragent/user/me \
  -H "Authorization: Bearer $TOKEN" | jq .
```
Expected: response contains `userId`, `deptId`, `deptName`, `roleTypes`, `maxSecurityLevel`, `isSuperAdmin`, `isDeptAdmin` fields. No `role` field.

```bash
# SysDept list
curl -s http://localhost:9090/api/ragent/sys-dept \
  -H "Authorization: Bearer $TOKEN" | jq .
```
Expected: array containing at least the GLOBAL dept (systemReserved=true).

- [ ] **Step 6**: No commit needed. If smoke fails, roll back to Task 0.15 and debug.

---

## Slice Task Details

### Task S1: Department page frontend (Greenfield)

**Files:**
- Create: `frontend/src/services/sysDeptService.ts`
- Create: `frontend/src/pages/admin/departments/DepartmentListPage.tsx`
- Modify: `frontend/src/router.tsx` (add `/admin/departments` route — if placeholder from Task 0.15 still there, replace)
- Modify: `frontend/src/pages/admin/AdminLayout.tsx` (add menu item with `id: "departments"`)

- [ ] **Step 1**: Create `sysDeptService.ts`

```typescript
import { api } from "./api";

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
  const { data } = await api.get("/sys-dept", { params: { keyword } });
  return data;
}

export async function getDepartment(id: string): Promise<SysDept> {
  const { data } = await api.get(`/sys-dept/${id}`);
  return data;
}

export async function createDepartment(payload: SysDeptCreatePayload): Promise<string> {
  const { data } = await api.post("/sys-dept", payload);
  return data;
}

export async function updateDepartment(id: string, payload: SysDeptUpdatePayload): Promise<void> {
  await api.put(`/sys-dept/${id}`, payload);
}

export async function deleteDepartment(id: string): Promise<void> {
  await api.delete(`/sys-dept/${id}`);
}
```

- [ ] **Step 2**: Create `DepartmentListPage.tsx`

Use the mockup from Slice 1 visual (see `slices-mockup.html` section "Slice 1"). Structure:
- Header with "部门管理" title + search input + refresh + create button
- Table: dept_code | dept_name | userCount | kbCount | createTime | actions
- GLOBAL row: show `🔒 系统保留` chip, disable edit/delete buttons
- Create/Edit Dialog: deptCode input (disabled when editing GLOBAL) + deptName input + save

Model the component on the existing `RoleListPage.tsx` pattern — list + create/edit dialog + delete confirm. Key differences:
- Disable edit/delete buttons when `row.systemReserved === true`
- Toast error message from backend ClientException (409 on delete with references)

Keep implementation tight; no fancy pagination since dept count is small.

```tsx
import { useEffect, useState } from "react";
import { Pencil, Plus, RefreshCw, Trash2, Building2 } from "lucide-react";
import { toast } from "sonner";
// ... import ui components and service ...

export function DepartmentListPage() {
  const [depts, setDepts] = useState<SysDept[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState("");
  const [dialogState, setDialogState] = useState<{
    open: boolean;
    mode: "create" | "edit";
    dept: SysDept | null;
  }>({ open: false, mode: "create", dept: null });
  const [form, setForm] = useState({ deptCode: "", deptName: "" });
  const [deleteTarget, setDeleteTarget] = useState<SysDept | null>(null);

  const loadDepts = async () => {
    try {
      setLoading(true);
      setDepts(await listDepartments(keyword || undefined));
    } catch (e) {
      toast.error(getErrorMessage(e, "加载部门失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadDepts(); }, []);

  const handleSave = async () => {
    if (!form.deptCode.trim() || !form.deptName.trim()) {
      toast.error("请填写部门编码和名称");
      return;
    }
    try {
      if (dialogState.mode === "create") {
        await createDepartment(form);
        toast.success("创建成功");
      } else if (dialogState.dept) {
        await updateDepartment(dialogState.dept.id, form);
        toast.success("更新成功");
      }
      setDialogState({ open: false, mode: "create", dept: null });
      await loadDepts();
    } catch (e) {
      toast.error(getErrorMessage(e, "保存失败"));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteDepartment(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      await loadDepts();
    } catch (e) {
      toast.error(getErrorMessage(e, "删除失败"));
    }
  };

  return (
    <div className="admin-page">
      {/* header with title + search + refresh + new button */}
      {/* table */}
      {/* create/edit dialog */}
      {/* delete confirm */}
    </div>
  );
}
```

(Full component body follows the mockup layout; engineer can reference `RoleListPage.tsx` for exact shadcn/ui component usage.)

- [ ] **Step 3**: Register route in `router.tsx`

```tsx
<Route path="departments" element={<RequireSuperAdmin><DepartmentListPage /></RequireSuperAdmin>} />
```

Remove the placeholder from Task 0.15 if still present.

- [ ] **Step 4**: Add menu item in `AdminLayout.tsx` menuGroups (in "设置" group):

```tsx
{
  id: "departments" as const,
  path: "/admin/departments",
  label: "部门管理",
  icon: Building2
}
```

Also add to `breadcrumbMap`: `departments: "部门管理"`.

- [ ] **Step 5**: Frontend compile

Run: `cd frontend && npm run build`
Expected: success.

- [ ] **Step 6**: Manual smoke

Start dev server, log in as admin, navigate to `/admin/departments`:
- See GLOBAL row with lock chip
- Create "研发部" (RND) and "法务部" (LEGAL) — both appear in list
- Try delete GLOBAL → error toast "GLOBAL 部门不可删除"
- Try edit GLOBAL → error toast "GLOBAL 部门不可修改"

- [ ] **Step 7**: Commit

```bash
git add frontend/src/services/sysDeptService.ts frontend/src/pages/admin/departments/DepartmentListPage.tsx frontend/src/router.tsx frontend/src/pages/admin/AdminLayout.tsx
git commit -m "feat(pr3): Slice 1 — Department management page (greenfield)"
```

---

### Task S2.1: User page — list view redesign

**Files:**
- Modify: `frontend/src/services/userService.ts`
- Modify: `frontend/src/pages/admin/users/UserListPage.tsx`

- [ ] **Step 1**: Extend `userService.ts` types

```typescript
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
```

- [ ] **Step 2**: Rewrite `UserListPage.tsx` table columns per mockup

New columns: 用户 | 部门 (chip) | 角色类型 (chips) | 最大密级 (colored badge) | 创建时间 | 操作

Remove the legacy `roleOptions` / `user.role === "admin"` / `roleLabel = user.role === "admin" ? "管理员" : "成员"` code paths. Use `user.roleTypes.map(t => <Chip>{t}</Chip>)` for display.

For `操作` column buttons, use `usePermissions` to gate:
- Edit button: visible if `canManageUser(user)`
- Delete button: visible if `canManageUser(user)` AND `!isProtectedAdmin(user)`

`isProtectedAdmin(user)` stays as `user.username === "admin"` — it's a seed-user UX marker.

For the `maxSecurityLevel` badge, use a small helper:
```tsx
function SecurityLevelBadge({ level }: { level: number }) {
  const colors = ["bg-green-100 text-green-800", "bg-blue-100 text-blue-800", "bg-orange-100 text-orange-800", "bg-red-100 text-red-800"];
  return <span className={`px-2 py-0.5 rounded text-xs font-medium ${colors[level] ?? colors[0]}`}>{level}</span>;
}
```

- [ ] **Step 3**: Frontend compile

Run: `cd frontend && npm run build`
Expected: success.

- [ ] **Step 4**: Manual smoke

Load `/admin/users` as admin. Verify new columns render correctly with sample data (admin user should show no dept, empty roleTypes, max=0 initially; after Slice 8 fixture, alice/bob/carol will show colored chips).

- [ ] **Step 5**: Commit

```bash
git add frontend/src/services/userService.ts frontend/src/pages/admin/users/UserListPage.tsx
git commit -m "feat(pr3): Slice 2a — User list view with dept/roleTypes/maxSecurityLevel columns"
```

---

### Task S2.2: User page — create/edit dialog + atomic create

**Files:**
- Modify: `frontend/src/pages/admin/users/UserListPage.tsx` (dialog form)
- Modify: `frontend/src/services/userService.ts` (if additional endpoints needed)

- [ ] **Step 1**: Rebuild the create/edit Dialog form

New fields:
- 用户名 (Input)
- 密码 (Input, edit mode optional)
- **部门** (Select; fetched from `listDepartments()`; locked + disabled for DEPT_ADMIN)
- **角色分配** (multi-select checkbox list from `getRoles()`; filter out `role.roleType === "SUPER_ADMIN"` if `!permissions.isSuperAdmin`)
- 头像 (Input)

Remove: the legacy `form.role` field + its `<Select>` + `roleOptions` constant.

- [ ] **Step 2**: Atomic create — call `createUser` with `deptId + roleIds` in single payload

Before (two-step):
```tsx
const newUserId = await createUser(payload);
if (form.roleIds.length > 0 && form.role !== "admin") {
  await setUserRoles(newUserId, form.roleIds);
}
```

After (single call):
```tsx
await createUser({
  username: form.username,
  password: form.password,
  avatar: form.avatar || undefined,
  deptId: form.deptId,
  roleIds: form.roleIds,
});
```

- [ ] **Step 3**: DEPT_ADMIN dept lock

```tsx
const permissions = usePermissions();
const deptLocked = permissions.isDeptAdmin && !permissions.isSuperAdmin;

<Select
  value={form.deptId}
  onValueChange={(v) => setForm({ ...form, deptId: v })}
  disabled={deptLocked}
>
  {deptLocked ? (
    <SelectItem value={permissions.deptId!}>{permissions.deptName}</SelectItem>
  ) : (
    depts.map((d) => <SelectItem key={d.id} value={d.id}>{d.deptName}</SelectItem>)
  )}
</Select>
```

On dialog open (create mode), if `deptLocked`, pre-fill `form.deptId = permissions.deptId`.

- [ ] **Step 4**: Role filter — hide SUPER_ADMIN role type for non-super-admin callers

```tsx
const availableRoles = allRoles.filter(r => permissions.canAssignRole(r));
```

- [ ] **Step 5**: Frontend compile

Run: `cd frontend && npm run build`
Expected: success.

- [ ] **Step 6**: Manual smoke (defer to Slice 9 full run for cross-role test)

Quick check: log in as admin, create a new user with dept + role, verify they appear in the list.

- [ ] **Step 7**: Commit

```bash
git add frontend/src/pages/admin/users/UserListPage.tsx frontend/src/services/userService.ts
git commit -m "feat(pr3): Slice 2b — User create/edit dialog with dept lock, role filter, atomic create"
```

---

### Task S3.1: Role page — list columns + edit dialog

**Files:**
- Modify: `frontend/src/services/roleService.ts`
- Modify: `frontend/src/pages/admin/roles/RoleListPage.tsx`

- [ ] **Step 1**: Extend `roleService.ts`

```typescript
export interface RoleItem {
  id: string;
  name: string;
  description?: string;
  roleType: "SUPER_ADMIN" | "DEPT_ADMIN" | "USER";
  maxSecurityLevel: number;
  createTime: string;
}

export interface RoleCreatePayload {
  name: string;
  description?: string;
  roleType: string;
  maxSecurityLevel: number;
}
```

- [ ] **Step 2**: Rewrite `RoleListPage.tsx` table

New columns: 角色名称 | 角色类型 (chip) | 最大密级 (badge) | 描述 | 可见 KB | 操作

- [ ] **Step 3**: Rewrite create/edit Dialog

New fields in Dialog:
- name + description (existing)
- **roleType** Select: options SUPER_ADMIN/DEPT_ADMIN/USER; but `SUPER_ADMIN` option only shown when `permissions.isSuperAdmin === true` (always true for this page since it's SUPER_ADMIN gated, but defensive)
- **maxSecurityLevel** Select: 0/1/2/3 with labels "公开"/"内部"/"机密"/"绝密"

Default for new role: `roleType = "USER"`, `maxSecurityLevel = 0`.

- [ ] **Step 4**: Frontend compile + manual smoke

Create a test role "PR3 测试角色", set roleType=DEPT_ADMIN, max=2, save, re-edit to verify echo.

- [ ] **Step 5**: Commit

```bash
git add frontend/src/services/roleService.ts frontend/src/pages/admin/roles/RoleListPage.tsx
git commit -m "feat(pr3): Slice 3a — Role list columns + edit dialog with roleType/maxSecurityLevel"
```

---

### Task S3.2: Role page — KB binding dialog permission dropdown

**Files:**
- Modify: `frontend/src/services/roleService.ts`
- Modify: `frontend/src/pages/admin/roles/RoleListPage.tsx` (KB binding dialog section)
- Modify: backend `RoleServiceImpl.setRoleKnowledgeBases` signature (if still accepts `List<String>` instead of `List<RoleKbBinding>`)

- [ ] **Step 1**: Backend DTO + service signature

New record: `RoleKbBinding(String kbId, String permission)` inside the bootstrap `user` package.

Update `RoleService.setRoleKnowledgeBases(String roleId, List<RoleKbBinding> bindings)` — if the current signature is `(roleId, List<String> kbIds)`, refactor to accept the binding list. Update `RoleController.PUT /role/{roleId}/knowledge-bases` body shape accordingly.

`RoleServiceImpl.setRoleKnowledgeBases` implementation: for each binding, upsert into `t_role_kb_relation` with `(role_id, kb_id, permission)`. Delete existing rows for `role_id` not in the new binding list.

- [ ] **Step 2**: Frontend service — payload type switch

```typescript
export interface RoleKbBinding {
  kbId: string;
  permission: "READ" | "WRITE" | "MANAGE";
}

export async function setRoleKnowledgeBases(
  roleId: string,
  bindings: RoleKbBinding[]
): Promise<void> {
  await api.put(`/role/${roleId}/knowledge-bases`, bindings);
}

export async function getRoleKnowledgeBases(roleId: string): Promise<RoleKbBinding[]> {
  const { data } = await api.get(`/role/${roleId}/knowledge-bases`);
  return data;
}
```

- [ ] **Step 3**: `RoleListPage.tsx` KB binding Dialog — add permission select per row

Each KB row gains a shadcn `Select` with options READ / WRITE / MANAGE. Default on first check: MANAGE (matches mockup). Unchecked rows: permission select shown but disabled.

State shape:
```tsx
const [selectedBindings, setSelectedBindings] = useState<Map<string, string>>(new Map());
```
(Map key = kbId, value = permission.)

On save:
```tsx
const bindings: RoleKbBinding[] = Array.from(selectedBindings.entries()).map(([kbId, permission]) => ({ kbId, permission: permission as any }));
await setRoleKnowledgeBases(roleId, bindings);
```

- [ ] **Step 4**: Backend compile + frontend compile

```bash
mvn -pl bootstrap spotless:apply && mvn -pl bootstrap install -DskipTests
cd frontend && npm run build
```

- [ ] **Step 5**: Commit

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/RoleService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java frontend/src/services/roleService.ts frontend/src/pages/admin/roles/RoleListPage.tsx
git commit -m "feat(pr3): Slice 3b — Role-KB binding dialog with permission dropdown (READ/WRITE/MANAGE)"
```

---

### Task S4: KB create dialog dept_id field

**Files:**
- Modify: `frontend/src/services/knowledgeService.ts`
- Modify: `frontend/src/components/admin/CreateKnowledgeBaseDialog.tsx` (or wherever the KB create form lives)
- Modify: `frontend/src/pages/admin/knowledge/KnowledgeListPage.tsx` (if list shows dept)

- [ ] **Step 1**: Extend `knowledgeService.ts` types

```typescript
export interface KnowledgeBase {
  id: string;
  name: string;
  description?: string;
  deptId: string;
  deptName?: string;
  // ... existing fields
}

export interface KnowledgeBaseCreatePayload {
  name: string;
  description?: string;
  deptId: string;
  // ... existing fields
}
```

- [ ] **Step 2**: Add dept_id Select to create dialog

```tsx
const permissions = usePermissions();
const deptLocked = permissions.isDeptAdmin && !permissions.isSuperAdmin;
const [form, setForm] = useState({ /* ... */ deptId: deptLocked ? permissions.deptId! : "1" /* GLOBAL default */ });

<Select value={form.deptId} onValueChange={(v) => setForm({ ...form, deptId: v })} disabled={deptLocked}>
  {/* populate from listDepartments() */}
</Select>
```

- [ ] **Step 3**: `KnowledgeListPage.tsx` — optional: show dept chip on each KB card

- [ ] **Step 4**: Frontend compile + manual smoke

Log in as admin, create KB with dept=研发部, verify it's listed under that dept.

- [ ] **Step 5**: Commit

```bash
git add frontend/src/services/knowledgeService.ts frontend/src/components/admin/CreateKnowledgeBaseDialog.tsx frontend/src/pages/admin/knowledge/KnowledgeListPage.tsx
git commit -m "feat(pr3): Slice 4 — KB create dialog dept_id field with DEPT_ADMIN lock"
```

---

### Task S5.1: Document security_level — upload + list column

**Files:**
- Modify: `frontend/src/services/knowledgeService.ts`
- Modify: `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx`
- Modify: `frontend/src/components/admin/CreateKnowledgeDocumentDialog.tsx` (or wherever upload dialog lives)

- [ ] **Step 1**: Extend types

```typescript
export interface KnowledgeDocumentVO {
  id: string;
  docName: string;
  kbId: string;
  kbName?: string;
  status: string;
  securityLevel: number;
  // ... other fields
}

export interface KnowledgeDocumentUploadPayload {
  docName: string;
  file: File;
  securityLevel: number;  // 0-3
  // ... other fields
}
```

- [ ] **Step 2**: Upload Dialog — add security_level Select (labels per spec)

```tsx
const SECURITY_OPTIONS = [
  { value: "0", label: "0 公开" },
  { value: "1", label: "1 内部" },
  { value: "2", label: "2 机密" },
  { value: "3", label: "3 绝密" },
];

<Select value={String(form.securityLevel)} onValueChange={(v) => setForm({ ...form, securityLevel: parseInt(v, 10) })}>
  {SECURITY_OPTIONS.map(o => <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>)}
</Select>
```

- [ ] **Step 3**: Documents list table — add security_level column with colored badge

Reuse the `SecurityLevelBadge` helper from Task S2.1 or inline:

```tsx
<TableCell>
  <span className={securityLevelClass(doc.securityLevel)}>
    {SECURITY_OPTIONS[doc.securityLevel].label}
  </span>
</TableCell>
```

- [ ] **Step 4**: Frontend compile + manual smoke

Upload a doc with securityLevel=2, verify it's displayed in list.

- [ ] **Step 5**: Commit

```bash
git add frontend/src/services/knowledgeService.ts frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx frontend/src/components/admin/CreateKnowledgeDocumentDialog.tsx
git commit -m "feat(pr3): Slice 5a — Document upload security_level + list column badge"
```

---

### Task S5.2: Document security_level — detail page + edit button

**Files:**
- Modify: `frontend/src/services/knowledgeService.ts` (add `updateDocumentSecurityLevel`)
- Modify: document detail component (path depends on existing structure; likely inside `KnowledgeDocumentsPage.tsx` or a separate detail page)

- [ ] **Step 1**: Add service method

```typescript
export async function updateDocumentSecurityLevel(
  docId: string,
  newLevel: number
): Promise<void> {
  await api.put(`/knowledge-base/docs/${docId}/security-level`, { newLevel });
}
```

- [ ] **Step 2**: Detail view — add security_level badge + "修改密级" button

```tsx
const permissions = usePermissions();
const canEdit = permissions.canEditDocSecurityLevel({ kbDeptId: doc.kbDeptId });

<div>
  <SecurityLevelBadge level={doc.securityLevel} />
  {canEdit && (
    <Button onClick={() => setEditDialogOpen(true)}>
      修改密级
    </Button>
  )}
</div>
```

Note: `doc.kbDeptId` may need to be added to the document VO on the backend if not already. Alternative: look up `doc.kbId` → `kb.deptId` separately. Simpler: extend `KnowledgeDocumentVO` with `kbDeptId` on the backend (join in existing SELECT).

- [ ] **Step 3**: Edit Dialog

```tsx
<Dialog open={editDialogOpen} onOpenChange={setEditDialogOpen}>
  <DialogContent>
    <DialogHeader>
      <DialogTitle>修改密级</DialogTitle>
      <DialogDescription>
        该操作会异步刷新 OpenSearch metadata (RocketMQ)
      </DialogDescription>
    </DialogHeader>
    <Select value={String(newLevel)} onValueChange={(v) => setNewLevel(parseInt(v, 10))}>
      {SECURITY_OPTIONS.map(o => <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>)}
    </Select>
    <DialogFooter>
      <Button onClick={async () => {
        await updateDocumentSecurityLevel(doc.id, newLevel);
        toast.success("密级已更新，正在刷新索引");
        setEditDialogOpen(false);
        await loadDoc();
      }}>保存</Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
```

- [ ] **Step 4**: Frontend compile + manual smoke

Edit a doc's security_level from 0 to 2, check backend logs for `security_level 刷新事件已发出: docId=...`.

- [ ] **Step 5**: Commit

```bash
git add frontend/src/services/knowledgeService.ts frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx
git commit -m "feat(pr3): Slice 5b — Document detail security_level edit with MQ refresh"
```

---

### Task S8.1: fixture_pr3_demo.sql (idempotent)

**Files:**
- Create: `resources/database/fixture_pr3_demo.sql`

- [ ] **Step 1**: Write idempotent SQL with business-key-based cleanup + insert

```sql
-- PR3 演示固定装置
-- 可重复执行：基于业务键清理再插入，使用固定 ID 避免随机。
-- 依赖：sys_dept 表里已存在 GLOBAL 种子（id='1'）。

BEGIN;

-- 1. 清理先前的演示数据（基于业务键）
DELETE FROM t_role_kb_relation WHERE role_id IN (
    SELECT id FROM t_role WHERE name IN ('研发部管理员', '法务部管理员', '普通研发员', '普通法务员')
);
DELETE FROM t_user_role WHERE user_id IN (
    SELECT id FROM t_user WHERE username IN ('alice', 'bob', 'carol')
);
DELETE FROM t_role WHERE name IN ('研发部管理员', '法务部管理员', '普通研发员', '普通法务员');
DELETE FROM t_user WHERE username IN ('alice', 'bob', 'carol');
DELETE FROM t_knowledge_base WHERE name IN ('研发知识库', '法务知识库');
DELETE FROM sys_dept WHERE dept_code IN ('RND', 'LEGAL');

-- 2. 部门（固定 id）
INSERT INTO sys_dept (id, dept_code, dept_name) VALUES
    ('2', 'RND', '研发部'),
    ('3', 'LEGAL', '法务部');

-- 3. 用户（密码明文 123456，沿用 seed admin 的 plain-text 约定）
INSERT INTO t_user (id, username, password, role, dept_id, avatar) VALUES
    ('10', 'alice', '123456', 'user', '2', ''),
    ('11', 'bob',   '123456', 'user', '3', ''),
    ('12', 'carol', '123456', 'user', '2', '');

-- 4. 角色（role_type + max_security_level）
INSERT INTO t_role (id, name, description, role_type, max_security_level) VALUES
    ('100', '研发部管理员', '管理研发部的 KB 和用户', 'DEPT_ADMIN', 3),
    ('101', '法务部管理员', '管理法务部的 KB 和用户', 'DEPT_ADMIN', 3),
    ('102', '普通研发员',   '只读访问研发 KB',       'USER',       0),
    ('103', '普通法务员',   '只读访问法务 KB',       'USER',       0);

-- 5. 用户-角色关联
INSERT INTO t_user_role (user_id, role_id) VALUES
    ('10', '100'),   -- alice = 研发部管理员
    ('11', '101'),   -- bob   = 法务部管理员
    ('12', '102');   -- carol = 普通研发员 (max=0)

-- 6. 知识库（预建，演示跨部门隔离）
--    collection_name / vector_space_name 等字段请保持与 schema 一致；这里只给出关键字段
INSERT INTO t_knowledge_base (id, name, description, dept_id, collection_name) VALUES
    ('kb-rnd-001',   '研发知识库', '研发部技术文档', '2', 'kb_rnd_001'),
    ('kb-legal-001', '法务知识库', '法务部合同文档', '3', 'kb_legal_001');

-- 7. 角色-KB 绑定（带 permission）
INSERT INTO t_role_kb_relation (role_id, kb_id, permission) VALUES
    ('100', 'kb-rnd-001',   'MANAGE'),
    ('101', 'kb-legal-001', 'MANAGE'),
    ('102', 'kb-rnd-001',   'READ');

COMMIT;

-- 提示：验收第 10 步需要手动在 UI 上传两份文档到 kb-rnd-001（一份 security_level=0，一份 =2）。
-- 本 fixture 不 seed 文档/向量数据（向量需要走 embedding 管道）。
```

- [ ] **Step 2**: Dry-run

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/fixture_pr3_demo.sql
```
Expected: `COMMIT` at end, no errors.

Re-run to verify idempotency:
```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/fixture_pr3_demo.sql
```
Expected: same output, no PK conflicts.

- [ ] **Step 3**: Verify seed data

```bash
docker exec postgres psql -U postgres -d ragent -c "SELECT id, dept_code, dept_name FROM sys_dept ORDER BY id;"
```
Expected: 3 rows (GLOBAL + RND + LEGAL).

- [ ] **Step 4**: Commit

```bash
git add resources/database/fixture_pr3_demo.sql
git commit -m "feat(pr3): Slice 8a — fixture_pr3_demo.sql (idempotent demo seed data)"
```

---

### Task S8.2: pr3-curl-matrix.http

**Files:**
- Create: `docs/dev/pr3-curl-matrix.http`

- [ ] **Step 1**: Write HTTP client file covering 19 rules from spec §九

```http
### PR3 Permission Rules Bypass Test Matrix
### Usage: JetBrains HTTP Client / VSCode REST Client
### Prerequisites: fixture_pr3_demo.sql loaded

@host = http://localhost:9090/api/ragent

### Login: admin (SUPER_ADMIN)
# @name loginAdmin
POST {{host}}/auth/login
Content-Type: application/json

{ "username": "admin", "password": "123456" }

> {% client.global.set("admin_token", response.body.data.token); %}

### Login: alice (研发部 DEPT_ADMIN)
# @name loginAlice
POST {{host}}/auth/login
Content-Type: application/json

{ "username": "alice", "password": "123456" }

> {% client.global.set("alice_token", response.body.data.token); %}

### Login: bob (法务部 DEPT_ADMIN)
# @name loginBob
POST {{host}}/auth/login
Content-Type: application/json

{ "username": "bob", "password": "123456" }

> {% client.global.set("bob_token", response.body.data.token); %}

### Login: carol (研发部 USER, max=0)
# @name loginCarol
POST {{host}}/auth/login
Content-Type: application/json

{ "username": "carol", "password": "123456" }

> {% client.global.set("carol_token", response.body.data.token); %}

### -----------------------------------------------------
### R3: DEPT_ADMIN reads /sys-dept → 403
GET {{host}}/sys-dept
Authorization: Bearer {{alice_token}}

### Expected: code != 0, message indicates forbidden

### R4: DEPT_ADMIN creates a department → 403
POST {{host}}/sys-dept
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "deptCode": "HACK", "deptName": "alice-hack" }

### R4: SUPER_ADMIN deletes GLOBAL → 409
DELETE {{host}}/sys-dept/1
Authorization: Bearer {{admin_token}}

### R6: DEPT_ADMIN creates KB in other dept → 403
POST {{host}}/knowledge-base
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "name": "非法 KB", "description": "试图创建到 LEGAL", "deptId": "3" }

### R6: DEPT_ADMIN creates KB in own dept → 200
POST {{host}}/knowledge-base
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "name": "研发 Q2 文档库", "description": "alice 创建的合法 KB", "deptId": "2" }

### R7: DEPT_ADMIN bob updates alice's dept KB → 403
PUT {{host}}/knowledge-base/kb-rnd-001
Authorization: Bearer {{bob_token}}
Content-Type: application/json

{ "name": "偷改" }

### R7: DEPT_ADMIN alice updates her dept KB → 200
PUT {{host}}/knowledge-base/kb-rnd-001
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "name": "研发知识库（改名）" }

### R10: DEPT_ADMIN bob changes security_level of alice's doc → 403
### Replace {{some_doc_id}} with an actual doc id after manual upload
PUT {{host}}/knowledge-base/docs/{{some_doc_id}}/security-level
Authorization: Bearer {{bob_token}}
Content-Type: application/json

{ "newLevel": 3 }

### R11: USER (carol) lists users → 403
GET {{host}}/users?current=1&size=10
Authorization: Bearer {{carol_token}}

### R12: DEPT_ADMIN alice creates user in other dept → 403
POST {{host}}/users
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "username": "hacker", "password": "x", "deptId": "3", "roleIds": ["102"] }

### R12: DEPT_ADMIN alice creates user with SUPER_ADMIN role → 403
### Replace {{super_admin_role_id}} with the actual super admin role id from admin seed
POST {{host}}/users
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "username": "hacker2", "password": "x", "deptId": "2", "roleIds": ["{{super_admin_role_id}}"] }

### R13: DEPT_ADMIN alice deletes bob (other dept user) → 403
DELETE {{host}}/users/11
Authorization: Bearer {{alice_token}}

### R14: DEPT_ADMIN alice assigns SUPER_ADMIN role to carol → 403
PUT {{host}}/user/12/roles
Authorization: Bearer {{alice_token}}
Content-Type: application/json

["{{super_admin_role_id}}"]

### R15: DEPT_ADMIN alice lists roles → 403
GET {{host}}/role
Authorization: Bearer {{alice_token}}

### R15: DEPT_ADMIN alice deletes a role → 403
DELETE {{host}}/role/100
Authorization: Bearer {{alice_token}}

### Last SUPER_ADMIN invariant: SUPER_ADMIN admin deletes themselves → 400 (if they are the only one)
DELETE {{host}}/users/1
Authorization: Bearer {{admin_token}}

### Last SUPER_ADMIN invariant: admin tries to remove their own SUPER_ADMIN role → 400
### (replace user id and roleIds appropriately)
PUT {{host}}/user/1/roles
Authorization: Bearer {{admin_token}}
Content-Type: application/json

[]

### UI-only rules (documented for completeness; no curl):
### R1 - RequireAnyAdmin guard (browser redirect, not 403)
### R2 - RequireMenuAccess hide/redirect
### R5, R8 - list filter via isSuperAdmin() + getAccessibleKbIds()
### R18 - modify own password (non-permission)
### R19 - login (anonymous)
```

- [ ] **Step 2**: Manual dry-run the key rules

Execute R6 create KB (legitimate), R6 create KB (forbidden other dept), R12 create user (both forbidden cases), Last SUPER_ADMIN invariant. Confirm expected response codes.

- [ ] **Step 3**: Commit

```bash
git add docs/dev/pr3-curl-matrix.http
git commit -m "docs(pr3): Slice 8b — curl bypass test matrix covering 19 permission rules"
```

---

### Task S8.3: pr3-demo-walkthrough.md + README cross-ref

**Files:**
- Create: `docs/dev/pr3-demo-walkthrough.md`
- Modify: `README.md`

- [ ] **Step 1**: Write `pr3-demo-walkthrough.md` as a strict 12-step ordered list matching spec §10.4

```markdown
# PR3 Demo Walkthrough

12 steps covering (1) Super admin management, (2) cross-department isolation, (3) security_level isolation.

## Prerequisites

- Database rebuilt with `schema_pg.sql` + `init_data_pg.sql` + `fixture_pr3_demo.sql`
- OpenSearch flushed (`curl -X DELETE http://localhost:9200/_all`)
- Redis flushed
- Backend running on :9090, frontend dev server on :5173
- Sample test documents ready on local disk:
  - `test-public.md` (any content; will be uploaded as security_level=0)
  - `test-confidential.md` (any content; will be uploaded as security_level=2)

## Steps

### 1. admin login — 12 menu items

Open http://localhost:5173/login, log in as `admin` / `123456`.
- Navigate to `/admin/dashboard`.
- Verify the sidebar shows 12 menu items (Dashboard, 知识库管理, 部门管理, 用户管理, 意图管理, 数据通道, 关键词映射, 链路追踪, 评测记录, 示例问题, 角色管理, 系统设置).
- Verify header roleLabel reads "超级管理员".

### 2. Departments already seeded — verify

Go to `/admin/departments`. You should see 3 rows: GLOBAL (locked), 研发部 (RND), 法务部 (LEGAL) — all from fixture.

### 3. Users already seeded — verify

Go to `/admin/users`. You should see admin / alice / bob / carol.
- alice shows 部门=研发部, 角色类型=DEPT_ADMIN (chip), 最大密级=3 (red badge)
- carol shows 部门=研发部, 角色类型=USER, 最大密级=0 (green badge)

### 4. Roles: role_type + max_security_level visible

Go to `/admin/roles`. Verify fixture-seeded roles show:
- 研发部管理员: role_type=DEPT_ADMIN, max_security_level=3
- 普通研发员: role_type=USER, max_security_level=0

### 5. Role assignment verified via fixture

No action — fixture already wires alice → 研发部管理员, bob → 法务部管理员, carol → 普通研发员.

### 6. Alice login — 3 menu items + 研发部管理员 label

Log out admin. Log in as `alice` / `123456`.
- Sidebar shows only 3 items: Dashboard, 知识库管理, 用户管理.
- Header roleLabel reads "研发部管理员".

### 7. Alice's /admin/knowledge — dept-scoped

- Only 研发知识库 visible (not 法务知识库).
- Click "新建知识库" — dept_id dropdown is locked to "研发部" (disabled).

### 8. Alice's /admin/users — dept-scoped CRUD

- Only alice + carol visible (admin and bob hidden).
- Edit carol → dept field locked, role dropdown shows "研发部管理员" and "普通研发员" but NOT any SUPER_ADMIN role.

### 9. Alice uploads a confidential document

- Navigate to /admin/knowledge → 研发知识库 → 文档管理 → 新建文档.
- Upload `test-confidential.md`, set **密级 = 2 机密**.
- Wait for chunk processing (watch status transition from PENDING → RUNNING → SUCCESS).

### 10. Carol login — cannot retrieve level=2 doc

- Log out alice. Log in as `carol` / `123456`.
- Open chat (click "返回聊天" or navigate to /chat).
- Ask "机密架构的核心要点是什么？" (or any query related to the uploaded doc).
- **Structural assertion:** The response's retrieval sources (visible in chat UI or trace) MUST NOT include `test-confidential.md`. Carol's max_security_level=0, doc's security_level=2, so OpenSearch filter excludes it.

### 11. Bob login — no research 部门 resources visible

- Log out carol. Log in as `bob` / `123456`.
- /admin/knowledge shows only 法务知识库; 研发知识库 hidden.
- /admin/users shows only bob (alice/carol hidden).
- Raw curl: try `PUT /knowledge-base/kb-rnd-001` with bob's token → expect 403 (handled by R7 in curl matrix).

### 12. Code hygiene grep

From the repo root:

    grep -rn '"admin"\.equals' bootstrap/src/main/java/ | grep -v /\*     # should be empty or docs only
    grep -rn "LoginUser\.role\|\.getRole()" bootstrap/src/main/java/com/nageoffer/ai/ragent/user/ bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/
    grep -rn "user\.role\|user?\.role" frontend/src/ --include="*.ts" --include="*.tsx"

Expected: no matches outside of deprecated/legacy notes.

## Troubleshooting

- **Step 10 fails (carol retrieves confidential doc)**: Check OpenSearch index was flushed after fixture load; re-upload the doc; verify `security_level` column in `t_knowledge_document` matches the metadata in OpenSearch.
- **Alice sees wrong menu count**: Clear browser localStorage and re-login; stale authStore may persist old user shape.
- **Fixture PK conflict on re-run**: Ensure fixture uses `DELETE ... WHERE` before INSERT; if it still conflicts, grep for residual rows: `SELECT * FROM sys_dept WHERE dept_code IN ('RND','LEGAL');`
```

- [ ] **Step 2**: Append a "PR3 Demo" section to `README.md`

```markdown
## PR3 Demo

演示跨部门 RBAC 隔离 + security_level 检索过滤的完整流程：

1. 加载 fixture: `docker exec -i postgres psql -U postgres -d ragent < resources/database/fixture_pr3_demo.sql`
2. 启动后端 + 前端
3. 按 `docs/dev/pr3-demo-walkthrough.md` 的 12 步执行
4. 对原始 HTTP 边界验证: `docs/dev/pr3-curl-matrix.http`

设计文档: `docs/superpowers/specs/2026-04-12-pr3-rbac-frontend-demo-design.md`
```

- [ ] **Step 3**: Commit

```bash
git add docs/dev/pr3-demo-walkthrough.md README.md
git commit -m "docs(pr3): Slice 8c — demo walkthrough and README cross-ref"
```

---

### Task S9: Full rebuild + 12-step verification + log

**Files:**
- Create: `docs/dev/pr3-verification-log.md`

- [ ] **Step 1**: Full rebuild per spec §10.3

```bash
# Step 1-3: data clean + frontend rebuild (bash / Git Bash)
docker exec postgres psql -U postgres -c "DROP DATABASE ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/fixture_pr3_demo.sql
curl -X DELETE "http://localhost:9200/_all"
docker exec redis redis-cli FLUSHDB
cd frontend && npm run build
```

```powershell
# Step 4: backend start (PowerShell)
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'
mvn -pl bootstrap spring-boot:run
```

- [ ] **Step 2**: Execute all 12 checklist items from `pr3-demo-walkthrough.md`

Create the log file and record each step's outcome:

```markdown
# PR3 Verification Log

**Date:** YYYY-MM-DD
**Operator:** <name>
**Commit tested:** <sha>

| # | Step | Result | Notes |
|---|---|---|---|
| 1 | admin login + 12 menu | PASS |  |
| 2 | departments fixture | PASS |  |
| 3 | users fixture | PASS |  |
| 4 | roles with role_type/max | PASS |  |
| 5 | role assignments | PASS |  |
| 6 | alice 3 menus + 研发部管理员 label | PASS |  |
| 7 | alice /admin/knowledge dept-scoped | PASS |  |
| 8 | alice /admin/users dept-scoped + role filter | PASS |  |
| 9 | alice uploads security_level=2 doc | PASS |  |
| 10 | carol retrieval excludes level=2 doc | PASS | 核心断言 |
| 11 | bob cross-dept isolation | PASS |  |
| 12 | grep hygiene (4 patterns clean) | PASS |  |

## curl matrix bypass results

Execute `docs/dev/pr3-curl-matrix.http`. Record rule pass/fail:

| Rule | Result |
|---|---|
| R3 | PASS |
| R4 | PASS |
| R6 (forbidden) | PASS |
| R6 (allowed) | PASS |
| R7 | PASS |
| R10 | PASS |
| R11 | PASS |
| R12 (forbidden dept) | PASS |
| R12 (forbidden role) | PASS |
| R13 | PASS |
| R14 | PASS |
| R15 | PASS |
| Last SUPER_ADMIN delete self | PASS |
| Last SUPER_ADMIN remove self role | PASS |

## Overall

✅ / ❌ PR3 ready to merge.
```

- [ ] **Step 3**: If any step fails, create a GitHub issue / project task referencing the failing step and the slice it traces to. Do NOT mark task done until all 12 pass.

- [ ] **Step 4**: Commit the verification log

```bash
git add docs/dev/pr3-verification-log.md
git commit -m "test(pr3): Slice 9 — 12-step verification log (all PASS)"
```

---

## Self-Review

**Spec coverage check** — walked through spec §3 (In-Scope) checklist:

| Spec item | Plan task(s) |
|---|---|
| SysDept Service/Controller/DTOs | 0.1, 0.2, 0.3 |
| UserProfileLoader + LoadedUserProfile | 0.4 |
| LoginVO/CurrentUserVO extension (additive) | 0.5 |
| KbAccessService.isDeptAdmin / resolveCreateKbDeptId / checkCreateUserAccess / checkUserManageAccess / checkAssignRolesAccess / checkDocManageAccess / checkDocSecurityLevelAccess | 0.6, 0.7 |
| getAccessibleKbIds v2 + checkAccess v2 (DEPT_ADMIN-aware + cache bypass) | 0.8 |
| Last SUPER_ADMIN invariant (countActiveSuperAdmins, isUserSuperAdmin, simulateActiveSuperAdminCountAfter, SuperAdminMutationIntent) | 0.9 |
| KnowledgeDocumentController 5 writes fix + new security-level endpoint + updateSecurityLevel extraction + KnowledgeDocumentUpdateRequest.securityLevel removal | 0.10 |
| KnowledgeBaseController 2 writes (update/delete) + create via resolveCreateKbDeptId + admin.equals in list | 0.11 |
| UserController 4 writes + atomic POST /users + delete pre-check | 0.12 |
| RoleServiceImpl 3 Last SUPER_ADMIN pre-checks + RoleController.setUserRoles authz | 0.13 |
| Remaining 3 admin.equals cleanup | 0.14 |
| LoginUser.role removal bundle (backend legacy removal + frontend permissions.ts + guards + authStore + AdminLayout) | 0.15 |
| Phase 0 smoke | 0.16 |
| Slice 1 Department page | S1 |
| Slice 2 User page redesign | S2.1, S2.2 |
| Slice 3 Role page upgrade | S3.1, S3.2 |
| Slice 4 KB create dept_id | S4 |
| Slice 5 Document security_level full loop | S5.1, S5.2 |
| Slice 6 sidebar/router (covered in Phase 0) | 0.15 |
| Slice 7 legacy cleanup (covered in Phase 0) | 0.15 |
| Slice 8 fixture + walkthrough + curl matrix | S8.1, S8.2, S8.3 |
| Slice 9 verification | S9 |

**Coverage result:** No spec gaps identified. Slices 6 and 7 from spec are not separate tasks because the brainstorming session intentionally absorbed them into the Phase 0 bundle (Task 0.15) per Decision 3-F.

**Placeholder scan:** Grepped plan for "TBD", "TODO", "implement later", "add validation", "similar to Task". One acceptable TODO remains: Task S5.2 Step 2 notes that `KnowledgeDocumentVO.kbDeptId` may need to be added backend-side — this is a discovered-during-implementation dependency, flagged inline with "Alternative: look up doc.kbId → kb.deptId separately". Not a placeholder; it's a resolved alternative.

**Type consistency:**
- `UserProfileLoader.load(String userId)` returns `LoadedUserProfile` — used consistently in 0.4, 0.5.
- `SuperAdminMutationIntent` is a sealed interface with 4 records (DeleteUser, ReplaceUserRoles, ChangeRoleType, DeleteRole) — used consistently in 0.9 (definition), 0.12 (delete), 0.13 (role changes).
- `simulateActiveSuperAdminCountAfter` return type `int`, called with `< 1` check — consistent across 0.9 (definition), 0.12, 0.13.
- Frontend `Permissions` interface methods — consistent between 0.15 definition, 0.15 usage in router guards, S1-S5 UI usage.
- `canSeeAdminMenu` is a `boolean` property, not a method — consistent across definition and usage.
- `AdminMenuId` type includes `departments` — consistent between 0.15 (defined) and S1 (used in menu item registration).

**One correction applied inline:** In the task list earlier I mentioned `5 writes` for KnowledgeDocumentController. The actual count is 5 (upload, startChunk, delete, update, enable) + 1 new (security-level) = 6 endpoints modified. The task text in 0.10 correctly lists all 6 — no action needed, cross-check accurate.

## Plan Done

Plan complete and saved to `docs/superpowers/plans/2026-04-12-pr3-rbac-frontend-demo.md`.
