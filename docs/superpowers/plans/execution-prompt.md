# 执行任务 Prompt（新会话使用）

复制以下内容到新的 Claude Code 会话中：

---

## Prompt

请在 `feature/opensearch-and-rbac` 分支上，按照实施计划逐 Task 执行 OpenSearch 迁移 + RBAC 知识库权限体系的开发工作。

### 背景

- **设计文档**：`docs/superpowers/specs/2026-04-07-opensearch-migration-and-rbac-design.md`
- **实施计划**：`docs/superpowers/plans/2026-04-07-opensearch-and-rbac.md`
- **当前分支**：`feature/opensearch-and-rbac`（已从 main 创建）
- **项目指南**：根目录 `CLAUDE.md` 包含构建命令、项目结构、技术栈等

### 目标

在现有 Milvus/Pg 向量引擎适配层基础上：
1. 新增 OpenSearch 实现（含混合查询，1 知识库 = 1 index）
2. 引入 RBAC 知识库级别权限体系（用户 → 角色 → 知识库可见性）
3. 将 RBAC 集成到聊天检索链路和前端

### 执行要求

1. **先阅读实施计划全文**（2100+ 行，14 个 Task，4 个 Phase），理解完整范围后再动手
2. **严格按 Task 顺序执行**：Phase 1 (Task 1-6) → Phase 2 (Task 7-10) → Phase 3 (Task 11-13) → Phase 4 (Task 14)
3. **每个 Task 的每个 Step 都有具体代码**，直接按文档中的代码实现，不要自行发挥
4. **每个 Task 完成后执行编译验证**：`mvn clean compile -DskipTests -pl bootstrap`
5. **每个 Task 完成后按文档中的 git commit 命令提交**
6. **遇到编译错误时先诊断修复**，不要跳过

### 关键注意事项

- 返回值包装器用 `Results.success()`（来自 `com.nageoffer.ai.ragent.framework.web.Results`），不是 `Result.success()`
- `EmbeddingService` 在 `com.nageoffer.ai.ragent.infra.embedding` 包下，不在 `rag.service` 下
- `KnowledgeBaseService.queryById(kbId)` 不是 `query(kbId)`
- `KnowledgeDocumentService.get(docId)` 不是 `getById(docId)` 或 `getDetail(docId)`
- 前端复用 `frontend/src/services/knowledgeService.ts` 已有的 `getKnowledgeBases()` 方法（返回 `KnowledgeBase[]`），不新建 API 文件
- `KbAccessService.checkAccess(String kbId)` 完全依赖 `UserContext`，不传 `userId`
- 指定知识库检索时仍复用 `executePostProcessors` 后处理链（去重 + rerank）
- AWS SigV4 本期不实现，`authType` 仅支持 `basic`

### 开始

请先切换到 `feature/opensearch-and-rbac` 分支，确认工作区干净，然后从 Task 1 开始执行。每完成一个 Task 向我汇报进度。
