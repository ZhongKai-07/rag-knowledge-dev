# Bootstrap Architecture Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate soft cycles inside `bootstrap`, remove `service -> controller` coupling, and establish enforceable dependency boundaries without a big-bang rewrite.

**Architecture:** Keep the current top-level Maven layout first, but harden boundaries inside `bootstrap` in three passes: add architecture tests, introduce neutral shared ports/models, then migrate domain code one slice at a time. Only after package boundaries are stable should the team consider extracting additional Maven modules.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis Plus, JUnit 5, Mockito, ArchUnit (test scope)

---

## Target Dependency Hierarchy

Current module direction is acceptable:

```text
framework <- infra-ai
framework <- bootstrap -> infra-ai
mcp-server (independent)
```

Target package direction inside `bootstrap`:

```text
controller -> application/service-contract -> domain/service -> dao
controller -> request/response DTO only
service -> command/query/view/domain model only

knowledge \
rag       \
user       > depend only on framework + core + shared + explicit security ports
ingestion /
admin    /

shared -> framework + core (+ infra-ai only when capability truly belongs to AI infra)
core   -> framework + infra-ai
```

Hard rules after the refactor:

- `..service..` must not import `..controller..`
- business domains must not import another domain's `controller`, `service`, or `dao` package directly
- cross-domain reads/writes must go through neutral ports in `framework.security.port` or new `bootstrap.shared.*` ports
- generic capabilities such as file storage, vector store contracts, remote fetch, and document source models must not live under `rag` or `ingestion`

## PR Sequence

Execute this as five independent PRs:

1. Architecture guardrails
2. Shared port extraction + `knowledge` pilot
3. `user` / RBAC convergence
4. `rag` + `ingestion` ownership cleanup
5. Legacy type removal + rule tightening

---

### Task 1: Add Architecture Guardrails

**Files:**
- Modify: `bootstrap/pom.xml`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/architecture/BootstrapDependencyRulesTest.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/architecture/BootstrapDependencyRulesTest.java`

- [ ] **Step 1: Add ArchUnit test dependency**

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.4.1</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the initial failing boundary test**

```java
@AnalyzeClasses(packages = "com.knowledgebase.ai.ragent")
class BootstrapDependencyRulesTest {

    @ArchTest
    static final ArchRule services_should_not_depend_on_controllers =
            noClasses().that().resideInAnyPackage("..service..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..controller..");
}
```

- [ ] **Step 3: Run the test to capture the current violation baseline**

Run: `mvn -pl bootstrap -Dtest=BootstrapDependencyRulesTest test`

Expected: FAIL, with violations including `KnowledgeBaseService`, `UserService`, `RoleService`, `IntentTreeService`, and related implementations.

- [ ] **Step 4: Expand rules to cover cross-domain leakage, but keep them staged**

Add rules for:
- no `knowledge` imports from `rag.controller|rag.service|rag.dao`
- no `user` imports from `knowledge.controller`
- no `ingestion` imports from `rag.controller`

Use temporary allowlists only for legacy wrappers that will be deleted in Tasks 2-5.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/pom.xml bootstrap/src/test/java/com/nageoffer/ai/ragent/architecture/BootstrapDependencyRulesTest.java
git commit -m "test: add bootstrap architecture boundary rules"
```

---

### Task 2: Extract Neutral Shared Ports and Use `knowledge` as the Pilot Slice

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/storage/FileStoragePort.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/storage/StoredFile.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/vector/VectorStorePort.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/vector/VectorStoreAdminPort.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/vector/VectorSpaceId.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/vector/VectorSpaceSpec.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/application/command/KnowledgeBaseCreateCommand.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/application/command/KnowledgeBaseRenameCommand.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/application/query/KnowledgeBasePageQuery.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/application/view/KnowledgeBaseView.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/FileStorageService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/StoredFileDTO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreAdmin.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorSpaceId.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorSpaceSpec.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/handler/RemoteFileFetcher.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseServiceImplDeleteTest.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseControllerScopeTest.java`

- [ ] **Step 1: Introduce neutral shared contracts without deleting the old `rag` types**

```java
public interface FileStoragePort {
    StoredFile upload(String bucketName, InputStream content, long size, String originalFilename, String contentType);
    InputStream openStream(String url);
    void deleteByUrl(String url);
}
```

Make legacy `rag` contracts temporary wrappers:
- `FileStorageService extends FileStoragePort`
- `VectorStoreService extends VectorStorePort`
- mark legacy package locations `@Deprecated`

- [ ] **Step 2: Move `knowledge` service contracts off controller DTOs**

Refactor `KnowledgeBaseService` from:

```java
String create(KnowledgeBaseCreateRequest requestParam);
```

to:

```java
String create(KnowledgeBaseCreateCommand command);
```

The controller remains responsible for translating HTTP DTOs into application commands/views.

- [ ] **Step 3: Run focused tests to verify the pilot slice**

Run: `mvn -pl bootstrap -Dtest=KnowledgeBaseServiceImplDeleteTest,KnowledgeBaseControllerScopeTest test`

Expected: PASS

- [ ] **Step 4: Migrate direct `knowledge -> rag` shared-capability imports to neutral shared packages**

At minimum replace imports in:
- `KnowledgeBaseServiceImpl`
- `KnowledgeDocumentServiceImpl`
- `RemoteFileFetcher`

No `knowledge` class should import `com.knowledgebase.ai.ragent.rag.service.FileStorageService` or `com.knowledgebase.ai.ragent.rag.core.vector.*` after this slice.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/shared bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge
git commit -m "refactor: extract shared ports and decouple knowledge contracts"
```

---

### Task 3: Finish `user` / RBAC Convergence Around Security Ports

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/RoleService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/UserService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/AccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/SpacesController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceImplTest.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/AccessServiceImplTest.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/controller/RoleControllerMutationAuthzTest.java`

- [ ] **Step 1: Remove controller type leakage from user services**

Replace signatures like:

```java
List<KnowledgeBaseController.KbRoleBindingVO> getKbRoleBindings(String kbId);
```

with neutral application models:

```java
List<KbRoleBindingView> getKbRoleBindings(String kbId);
```

- [ ] **Step 2: Finish the RBAC port migration**

Target state:
- controllers use `KbManageAccessPort`, `KbReadAccessPort`, `CurrentUserProbe`
- services depend on fine-grained ports
- `KbAccessService` becomes a deprecated facade only, or is deleted after the last caller is gone

- [ ] **Step 3: Remove duplicate knowledge lookups from `AccessServiceImpl`**

Delete direct `KnowledgeBaseMapper` usage and route all KB metadata reads through `KbMetadataReader`.

- [ ] **Step 4: Run focused RBAC regression tests**

Run: `mvn -pl bootstrap -Dtest=KbAccessServiceImplTest,AccessServiceImplTest,RoleControllerMutationAuthzTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller bootstrap/src/main/java/com/nageoffer/ai/ragent/rag bootstrap/src/test/java/com/nageoffer/ai/ragent/user
git commit -m "refactor: converge rbac on security ports"
```

---

### Task 4: Normalize `rag` / `ingestion` Ownership and Shared Document-Source Models

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/docsource/DocumentSource.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/shared/http/RemoteHttpClient.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/controller/request/IngestionTaskCreateRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/request/DocumentSourceRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/IngestionTaskService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IngestionTaskServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/IntentTreeService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/service/impl/IntentTreeServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/IntentTreeController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/handler/RemoteFileFetcher.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/util/HttpClientHelper.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/Intent/IntentTreeServiceTests.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessorTest.java`

- [ ] **Step 1: Create a neutral document-source model**

```java
public class DocumentSource {
    private SourceType type;
    private String location;
    private String fileName;
    private Map<String, String> credentials;
}
```

Controllers may keep HTTP request DTOs, but services must consume `DocumentSource`, not another domain's controller request.

- [ ] **Step 2: Move intent-tree ownership to `rag`**

`IntentTreeService` currently lives under `ingestion` while operating on `rag.dao.entity.IntentNodeDO` and `rag.controller.*` types. Move the service contract and implementation to the `rag` domain, then leave a temporary deprecated delegator only if needed for compile compatibility.

- [ ] **Step 3: Replace `knowledge -> ingestion.util.HttpClientHelper` with a neutral shared HTTP client**

Rename or move `HttpClientHelper` into `shared/http`, then update `RemoteFileFetcher` to import the neutral type.

- [ ] **Step 4: Run focused ownership-regression tests**

Run: `mvn -pl bootstrap -Dtest=IntentTreeServiceTests,ScheduleRefreshProcessorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/shared bootstrap/src/main/java/com/nageoffer/ai/ragent/rag bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/handler bootstrap/src/test/java/com/nageoffer/ai/ragent/rag bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge
git commit -m "refactor: normalize rag and ingestion ownership"
```

---

### Task 5: Remove Legacy Compatibility and Tighten the Rules

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/architecture/BootstrapDependencyRulesTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/FileStorageService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Create: `docs/dev/design/bootstrap-dependency-boundaries.md`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/architecture/BootstrapDependencyRulesTest.java`

- [ ] **Step 1: Delete or freeze the remaining legacy wrappers**

After Tasks 2-4, legacy types should be either:
- deleted, or
- reduced to trivial adapters with no business callers

- [ ] **Step 2: Tighten the architecture tests from allowlist mode to deny-by-default**

Add rules such as:

```java
noClasses().that().resideInAnyPackage("..knowledge..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("..rag.controller..", "..rag.service..", "..rag.dao..");
```

Repeat for `user`, `ingestion`, and `admin`.

- [ ] **Step 3: Document the final boundary map**

Create `docs/dev/design/bootstrap-dependency-boundaries.md` with:
- allowed dependency directions
- migration examples
- how to add a new cross-domain port instead of a direct import

- [ ] **Step 4: Run the full high-signal test set**

Run:
- `mvn -pl bootstrap -Dtest=BootstrapDependencyRulesTest test`
- `mvn -pl bootstrap -Dtest=KnowledgeBaseControllerScopeTest,KnowledgeBaseServiceImplDeleteTest,KnowledgeDocumentServiceImplTest test`
- `mvn -pl bootstrap -Dtest=KbAccessServiceImplTest,AccessServiceImplTest,RoleControllerMutationAuthzTest test`
- `mvn -pl bootstrap -Dtest=IntentTreeServiceTests,ScheduleRefreshProcessorTest,RagTraceQueryServiceVisibilityTest,RagEvaluationServiceVisibilityTest test`

Expected: PASS, excluding the repository's known baseline failures documented in `CLAUDE.md`.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent bootstrap/src/test/java/com/nageoffer/ai/ragent/architecture docs/dev/design/bootstrap-dependency-boundaries.md
git commit -m "refactor: enforce bootstrap dependency boundaries"
```

---

## Exit Criteria

This plan is complete when all of the following are true:

- no `service` class imports any `controller` class or DTO
- `knowledge`, `rag`, `user`, and `ingestion` no longer import one another's `controller`, `service`, or `dao` packages directly
- file storage, vector store contracts, remote HTTP fetch, and document source models live in neutral packages
- RBAC callers use fine-grained security ports, not mixed old/new abstractions
- architecture tests fail immediately when a forbidden dependency is reintroduced

## Deferred Follow-Up

Only after Tasks 1-5 land cleanly:

- consider extracting `bootstrap/shared` into a new Maven module
- consider extracting `knowledge`, `rag`, and `user` into separate Maven modules if build time and ownership boundaries justify it
- consider adding a CI gate that runs `BootstrapDependencyRulesTest` on every PR
