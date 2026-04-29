# Cleanup `memory.ttl-minutes` Dead Configuration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 `rag.memory.ttl-minutes` 配置字段及其全部引用 —— 该字段只在 settings 展示链路出现，未被任何业务代码读取（核心记忆链路用的是 `historyKeepTurns` / `summaryStartTurns` 等其他字段），属于纯死代码。

**Architecture:** cherry-pick from upstream `nageoffer/ragent@803d2fd3`。我方共 6 处引用，全部对齐 upstream diff（已 grep 校验过整个仓库无其它隐藏引用）。改动范围：`MemoryProperties` 字段 + `RAGSettingsController.toMemorySettings` 调用 + `SystemSettingsVO.MemorySettings` builder + `application.yaml` 配置项 + 前端 `SystemSettings.memory` 类型 + 前端 `SystemSettingsPage` 展示行。

**Tech Stack:** Java 17 / Spring Boot 3.5.7 / Lombok（后端）；React 18 + TypeScript + Vite（前端）。

---

## Context Notes for Implementer

- Upstream commit `803d2fd3` 路径前缀 `com.nageoffer.ai.ragent` 在我方对应 `com.knowledgebase.ai.ragent` —— 仅用于检索定位，不需要批量替换 import。
- **不需要写新测试**：删除一个未被业务读取的字段没有行为测试可写。靠编译 + spotless + frontend typecheck + 一次启动验证。
- **不需要 TDD**：本任务是纯死代码清理，不存在"先红后绿"的可观测行为变化。
- 我方 `ValidMemoryConfig` 校验器（`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/validation/`）经 grep 确认**未引用** `ttlMinutes`，无连带改动。
- 我方 `application.yaml` 该段已存在 `ttl-minutes: 60`（第 89 行），删除即可，无需改其他 memory 配置项。
- 数据库迁移 / migration 脚本经 grep 确认**无引用**，纯应用层 + 前端展示层删除。
- **单一逻辑提交**：6 处引用同时删，不拆 commit；提交信息标明 cherry-pick 来源。

---

## File Structure

| 路径 | 责任 | 操作 |
| --- | --- | --- |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/MemoryProperties.java` | rag.memory 配置属性 | Modify：删 `ttlMinutes` 字段 + 注释 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGSettingsController.java` | settings GET 接口 | Modify：删 builder 链中 `.ttlMinutes(...)` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/vo/SystemSettingsVO.java` | settings VO | Modify：从 `MemorySettings` 内部类删 `ttlMinutes` 字段 |
| `bootstrap/src/main/resources/application.yaml` | 主配置 | Modify：删 `rag.memory.ttl-minutes: 60` 行 |
| `frontend/src/services/settingsService.ts` | settings 类型定义 | Modify：从 `SystemSettings.memory` 类型删 `ttlMinutes: number` |
| `frontend/src/pages/admin/settings/SystemSettingsPage.tsx` | settings 展示页 | Modify：删 `<InfoItem label="TTL Minutes" ...>` 行 |

---

## Task 0: Setup Worktree + Branch

**Files:** N/A（git 操作）

- [ ] **Step 1: 在主仓库根目录创建 worktree**

```bash
git worktree add .worktrees/cleanup-ttl-minutes -b cleanup-ttl-minutes main
```

- [ ] **Step 2: 进入 worktree，确认基线**

```bash
cd .worktrees/cleanup-ttl-minutes
git status
git log --oneline -3
```

Expected：`On branch cleanup-ttl-minutes` / 工作树干净 / HEAD 是 `1cfa2ab1 Merge pull request #31 ...`。

---

## Task 1: 删除后端 Java 三处引用

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/MemoryProperties.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGSettingsController.java`
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/vo/SystemSettingsVO.java`

- [ ] **Step 1: 删 `MemoryProperties.ttlMinutes` 字段**

打开 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/MemoryProperties.java`，定位 `historyKeepTurns` 字段后紧邻的这块（约 47-51 行）：

```java
    /**
     * 缓存过期时间（分钟）
     */
    private Integer ttlMinutes = 60;

```

整段（含上方空行 + javadoc + 字段声明 + 下方空行）**整段删除**。

删除后该字段相邻段落变为：

```java
    @Min(1)
    @Max(100)
    private Integer historyKeepTurns = 8;

    /**
     * 是否启用对话记忆压缩
     */
    private Boolean summaryEnabled = false;
```

- [ ] **Step 2: 删 `RAGSettingsController.toMemorySettings` 中的 builder 调用**

打开 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGSettingsController.java`，定位 `toMemorySettings` 方法（约 101-110 行）：

```java
    private MemorySettings toMemorySettings(MemoryProperties props) {
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .ttlMinutes(props.getTtlMinutes())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }
```

删除单行 `.ttlMinutes(props.getTtlMinutes())`，结果为：

```java
    private MemorySettings toMemorySettings(MemoryProperties props) {
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }
```

- [ ] **Step 3: 删 `SystemSettingsVO.MemorySettings.ttlMinutes` 字段**

打开 `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/vo/SystemSettingsVO.java`，定位内部类 `MemorySettings`（约 144-152 行）：

```java
    @Data
    @Builder
    public static class MemorySettings {
        private Integer historyKeepTurns;
        private Integer ttlMinutes;
        private Boolean summaryEnabled;
        private Integer summaryStartTurns;
        private Integer summaryMaxChars;
        private Integer titleMaxLength;
    }
```

删除单行 `private Integer ttlMinutes;`，结果为：

```java
    @Data
    @Builder
    public static class MemorySettings {
        private Integer historyKeepTurns;
        private Boolean summaryEnabled;
        private Integer summaryStartTurns;
        private Integer summaryMaxChars;
        private Integer titleMaxLength;
    }
```

> 因为整个 `MemorySettings` 是 `@Data @Builder` Lombok 类，无需手动维护 builder 方法或 getter/setter——Lombok 在编译期会自动重新生成。

- [ ] **Step 4: 编译验证**

```bash
mvn -pl bootstrap -am compile -DskipTests
```

Expected：`BUILD SUCCESS`，无 `cannot find symbol` 或类似错误（如果有，意味着仓库内还有未被 grep 命中的引用，回去补 grep 后处理）。

---

## Task 2: 删除 yaml 配置项

**Files:**
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 删 `rag.memory.ttl-minutes: 60`**

打开 `bootstrap/src/main/resources/application.yaml`，定位 `rag.memory` 段（约 85-91 行）：

```yaml
  memory:
    history-keep-turns: 4
    summary-start-turns: 5
    summary-enabled: true
    ttl-minutes: 60
    summary-max-chars: 200
    title-max-length: 30
```

删除单行 `    ttl-minutes: 60`，结果为：

```yaml
  memory:
    history-keep-turns: 4
    summary-start-turns: 5
    summary-enabled: true
    summary-max-chars: 200
    title-max-length: 30
```

> 缩进保持 4 空格（与同级其他配置项对齐）。

---

## Task 3: 删除前端两处引用

**Files:**
- Modify: `frontend/src/services/settingsService.ts`
- Modify: `frontend/src/pages/admin/settings/SystemSettingsPage.tsx`

- [ ] **Step 1: 删 `SystemSettings.memory.ttlMinutes` 类型字段**

打开 `frontend/src/services/settingsService.ts`，定位 `memory` 类型（约 28-35 行）：

```typescript
    memory: {
      historyKeepTurns: number;
      summaryStartTurns: number;
      summaryEnabled: boolean;
      ttlMinutes: number;
      summaryMaxChars: number;
      titleMaxLength: number;
    };
```

删除单行 `      ttlMinutes: number;`，结果为：

```typescript
    memory: {
      historyKeepTurns: number;
      summaryStartTurns: number;
      summaryEnabled: boolean;
      summaryMaxChars: number;
      titleMaxLength: number;
    };
```

- [ ] **Step 2: 删 `SystemSettingsPage` 展示行**

打开 `frontend/src/pages/admin/settings/SystemSettingsPage.tsx`，定位 memory 卡片 `<CardContent>` 内（约 117-123 行）：

```tsx
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="History Keep Turns" value={rag.memory.historyKeepTurns} />
          <InfoItem label="Summary Start Turns" value={rag.memory.summaryStartTurns} />
          <InfoItem label="Summary Enabled" value={<BoolBadge value={rag.memory.summaryEnabled} />} />
          <InfoItem label="TTL Minutes" value={rag.memory.ttlMinutes} />
          <InfoItem label="Summary Max Chars" value={rag.memory.summaryMaxChars} />
          <InfoItem label="Title Max Length" value={rag.memory.titleMaxLength} />
        </CardContent>
```

删除单行 `<InfoItem label="TTL Minutes" value={rag.memory.ttlMinutes} />`，结果为：

```tsx
        <CardContent className="grid gap-4 md:grid-cols-3">
          <InfoItem label="History Keep Turns" value={rag.memory.historyKeepTurns} />
          <InfoItem label="Summary Start Turns" value={rag.memory.summaryStartTurns} />
          <InfoItem label="Summary Enabled" value={<BoolBadge value={rag.memory.summaryEnabled} />} />
          <InfoItem label="Summary Max Chars" value={rag.memory.summaryMaxChars} />
          <InfoItem label="Title Max Length" value={rag.memory.titleMaxLength} />
        </CardContent>
```

> 不改外层 `md:grid-cols-3` 网格 —— 剩余 5 个 InfoItem 在 3 列网格内自然换行（`5 = 3 + 2`），CSS Grid 会自动补齐。

---

## Task 4: 全量验证

**Files:** N/A（验证）

- [ ] **Step 1: 后端 spotless + 全量编译**

```bash
mvn clean install -DskipTests spotless:check
```

Expected：`BUILD SUCCESS`。

> 如果 spotless 报告 formatting 问题，跑 `mvn spotless:apply` 修复后**作为本次改动的一部分一起 commit**，不要单独提一个 chore commit。

- [ ] **Step 2: 后端 settings 相关测试（如果有）**

```bash
mvn -pl bootstrap test -Dtest='RAGSettingsController*Test,MemoryProperties*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：测试 PASS 或 "No tests matching"（无关联测试也接受，因为 settings 这块上游就没有单测覆盖）。

- [ ] **Step 3: 前端 typecheck**

```bash
cd frontend
npx tsc --noEmit
echo "tsc exit=$?"
```

Expected：`tsc exit=0`。

- [ ] **Step 4: 前端 vitest（确认无回归）**

```bash
npm run test
```

Expected：`40 passed (40)` 或现有所有测试 PASS。

- [ ] **Step 5: 后端启动烟测（必做）**

回到 worktree 根目录：

```bash
cd ..
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

观察启动日志：
- `MemoryProperties` 装配无错误
- 无 `Failed to bind properties under 'rag.memory'` 报错（如果有，说明 yaml 与 properties 类不匹配）

Ctrl+C 停止。如果启动日志干净，进入下一步。

> 不需要前端 dev server 烟测（这次纯展示行删除，无逻辑变化，靠 typecheck 已经覆盖）。

---

## Task 5: Commit + Push + 开 PR

**Files:** N/A（git / GitHub 操作）

- [ ] **Step 1: 单一 commit**

```bash
git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/config/MemoryProperties.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/RAGSettingsController.java \
        bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/controller/vo/SystemSettingsVO.java \
        bootstrap/src/main/resources/application.yaml \
        frontend/src/services/settingsService.ts \
        frontend/src/pages/admin/settings/SystemSettingsPage.tsx
git status
```

Expected：仅上述 6 个文件 staged，无其它文件。

```bash
git commit -m "$(cat <<'EOF'
chore(rag): 删除 memory.ttl-minutes 死代码配置

cherry-pick from upstream 803d2fd3。该字段只在 settings 展示链路出现，
未被任何业务代码读取（核心记忆链路用的是 historyKeepTurns /
summaryStartTurns 等其他字段），属于纯死代码。

清理范围：
- MemoryProperties.ttlMinutes 字段
- RAGSettingsController.toMemorySettings 中的 builder 调用
- SystemSettingsVO.MemorySettings 内部类字段
- application.yaml rag.memory.ttl-minutes 配置项
- 前端 SystemSettings.memory.ttlMinutes 类型
- 前端 SystemSettingsPage TTL Minutes InfoItem

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 2: 推分支**

```bash
git push -u origin cleanup-ttl-minutes
```

Expected：远程创建新分支 `cleanup-ttl-minutes`，推送提示给出 GitHub PR URL。

- [ ] **Step 3: 创建 PR**

如果 `gh` CLI 已登录：

```bash
gh pr create --base main --head cleanup-ttl-minutes \
  --title "chore(rag): 删除 memory.ttl-minutes 死代码配置" \
  --body "$(cat <<'EOF'
## Summary

cherry-pick upstream `nageoffer/ragent@803d2fd3` 的死代码清理：

- `rag.memory.ttl-minutes` 字段只在 settings 展示链路出现，未被任何业务代码读取
- 核心记忆链路（`DefaultConversationMemoryService` / `JdbcConversationMemoryStore` / `JdbcConversationMemorySummaryService`）用的是 `historyKeepTurns` / `summaryStartTurns` / `summaryMaxChars` 等其他字段
- 已 grep 整仓确认 6 处引用全部清理，无隐藏引用

## Plan-内容已知风险

无。纯字段删除，零业务行为变化。

## Test plan

- [x] `mvn clean install -DskipTests spotless:check` — BUILD SUCCESS
- [x] `npx tsc --noEmit`（前端 typecheck）— exit 0
- [x] `npm run test`（vitest）— 全 PASS
- [x] 后端启动烟测：`MemoryProperties` 装配无错，无 `Failed to bind` 异常

## 实施计划

详见 [`docs/superpowers/plans/2026-04-29-cleanup-memory-ttl-minutes.md`](docs/superpowers/plans/2026-04-29-cleanup-memory-ttl-minutes.md)。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

如果 `gh` 未登录，输出推送时 GitHub 给的链接，让 user 手工开 PR。

- [ ] **Step 4: PR merge 后清理**

PR 合入 main 后，回到主仓库根目录：

```bash
cd ..   # 回到 worktree 集合根（仍在 .worktrees/ 内的话再 ../..）
# 或直接：
cd "E:/AI Application/rag-knowledge-dev"
git pull --ff-only origin main
git worktree remove .worktrees/cleanup-ttl-minutes
git branch -D cleanup-ttl-minutes
git worktree list  # 验证已删
```

Expected：worktree 与本地分支删除，主仓库 `main` HEAD 推进到包含此 PR 的 merge commit。

---

## Self-Review Notes

**Spec coverage check:**
- ✅ 6 处引用全部对应 task：MemoryProperties / RAGSettingsController / SystemSettingsVO（Task 1）+ application.yaml（Task 2）+ settingsService.ts / SystemSettingsPage.tsx（Task 3）
- ✅ 验证步骤覆盖：编译 + spotless + typecheck + vitest + 启动烟测（Task 4）
- ✅ Cherry-pick 元信息保留：commit message 标明 upstream 803d2fd3
- ✅ Worktree 流程闭环：从 setup 到清理（Task 0 + Task 5 Step 4）

**Placeholder scan:**
- 无 "TBD" / "implement later" / "适当处理"
- 所有代码块都给出完整改动后的状态片段
- 命令都是可粘贴执行的具体形式

**Type consistency:**
- 字段名全程统一为 `ttlMinutes`（驼峰）/ `ttl-minutes`（kebab，仅 yaml）/ `TTL Minutes`（仅 frontend label 字符串）
- 文件路径全程使用绝对路径前缀 `bootstrap/...` / `frontend/...`，无相对路径歧义

**架构 / 权限合规：**
- 不动 framework `security/port` 任何 Port
- 不动 `RetrievalScopeBuilder` / `KbMetadataReader` / `DefaultMetadataFilterBuilder`
- 不动权限 cache / scope 链路
- **零权限影响**

**风险等级：极低**。本计划应在 30 分钟内完成（含烟测）。
