# RAG 评估闭环 · PR E1（地基）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec**：`docs/superpowers/specs/2026-04-24-rag-eval-closed-loop-design.md`
**Goal**：打通 eval 域骨架 —— 4 张新表上线、Java `eval/` 包能被 `@MapperScan` 装配、Python `ragent-eval` 服务能 docker up 并响应 `POST /synthesize`，返回 LLM 合成的 Q-A（不落库）
**Architecture**：Java bootstrap 新增顶级 `eval/` 域（空骨架 + config + client + DAO 层）；Python 改造现有 `ragas/` 目录为 FastAPI 服务 + Dockerfile；用 docker-compose 文件挂载
**Tech Stack**：Java 17 + Spring Boot 3.5 + MyBatis Plus 3.5 + PostgreSQL；Python 3.11 + FastAPI + pydantic-settings + RAGAS + OpenAI SDK（百炼兼容）

**覆盖 Spec 章节**：§5（数据模型全表）、§7.1 结构骨架、§7.2 `EvalAsyncConfig/RagasEvalClient/SystemSnapshotBuilder` 的雏形（仅 Config + Client）、§7.3 Python 服务骨架 + `/synthesize`、§7.4 compose、§8 `rag.eval.*` 配置

**不覆盖**（留给后续 PR）：
- 合成真落库 + 审核前端（PR E2）
- `AnswerPipeline` / `ChatForEvalService` 重构（PR E3-spike / E3）
- 评估执行 + 结果看板（PR E3）
- `SystemSnapshotBuilder` / `EvalRunExecutor` / 前端页面

**分支**：`feature/eval-e1-foundation`（基于 `main`）

---

## Task 1: DB Migration — 4 张新表

**Files:**
- Create: `resources/database/upgrade_v1.9_to_v1.10.sql`
- Modify: `resources/database/schema_pg.sql`（追加 4 张表）
- Modify: `CLAUDE.md`（根目录 Upgrade scripts 段加一行）

- [ ] **Step 1: 写 upgrade SQL**

Create `resources/database/upgrade_v1.9_to_v1.10.sql`:

```sql
-- v1.9 → v1.10：新增 RAG 评估闭环（eval 域）4 张表
-- 对齐 spec §5；所有表 t_eval_* 前缀归属 eval 域

CREATE TABLE t_eval_gold_dataset (
    id             VARCHAR(20)   NOT NULL PRIMARY KEY,
    kb_id          VARCHAR(20)   NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    description    TEXT,
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    item_count     INT           NOT NULL DEFAULT 0,
    created_by     VARCHAR(20)   NOT NULL,
    updated_by     VARCHAR(20),
    create_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_eval_gold_dataset_kb_name UNIQUE (kb_id, name, deleted)
);
CREATE INDEX idx_eval_gold_dataset_kb ON t_eval_gold_dataset (kb_id);
COMMENT ON TABLE t_eval_gold_dataset IS 'RAG 评估黄金集（按 KB 划分）';

CREATE TABLE t_eval_gold_item (
    id                     VARCHAR(20)   NOT NULL PRIMARY KEY,
    dataset_id             VARCHAR(20)   NOT NULL,
    question               TEXT          NOT NULL,
    ground_truth_answer    TEXT          NOT NULL,
    source_chunk_id        VARCHAR(20),
    source_chunk_text      TEXT          NOT NULL,
    source_doc_id          VARCHAR(20),
    source_doc_name        VARCHAR(255),
    review_status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    review_note            TEXT,
    synthesized_by_model   VARCHAR(64),
    created_by             VARCHAR(20)   NOT NULL,
    updated_by             VARCHAR(20),
    create_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_eval_gold_item_dataset_review ON t_eval_gold_item (dataset_id, review_status);
COMMENT ON TABLE t_eval_gold_item IS 'RAG 评估黄金集条目（含 chunk 快照）';

CREATE TABLE t_eval_run (
    id                VARCHAR(20)   NOT NULL PRIMARY KEY,
    dataset_id        VARCHAR(20)   NOT NULL,
    kb_id             VARCHAR(20)   NOT NULL,
    triggered_by      VARCHAR(20)   NOT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    total_items       INT           NOT NULL DEFAULT 0,
    succeeded_items   INT           NOT NULL DEFAULT 0,
    failed_items      INT           NOT NULL DEFAULT 0,
    metrics_summary   TEXT,
    system_snapshot   TEXT,
    evaluator_llm     VARCHAR(64),
    error_message     TEXT,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    created_by        VARCHAR(20)   NOT NULL,
    updated_by        VARCHAR(20),
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_eval_run_dataset ON t_eval_run (dataset_id);
CREATE INDEX idx_eval_run_kb ON t_eval_run (kb_id);
COMMENT ON TABLE t_eval_run IS 'RAG 评估运行（含系统配置快照）';

CREATE TABLE t_eval_result (
    id                     VARCHAR(20)    NOT NULL PRIMARY KEY,
    run_id                 VARCHAR(20)    NOT NULL,
    gold_item_id           VARCHAR(20)    NOT NULL,
    question               TEXT,
    ground_truth_answer    TEXT,
    system_answer          TEXT,
    retrieved_chunks       TEXT,
    faithfulness           DECIMAL(5,4),
    answer_relevancy       DECIMAL(5,4),
    context_precision      DECIMAL(5,4),
    context_recall         DECIMAL(5,4),
    error                  TEXT,
    elapsed_ms             INT,
    create_time            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_eval_result_run ON t_eval_result (run_id);
CREATE INDEX idx_eval_result_run_faith ON t_eval_result (run_id, faithfulness);
COMMENT ON TABLE t_eval_result IS 'RAG 评估每条 gold item 结果';
```

- [ ] **Step 2: 对本地 PG 跑迁移**

```bash
docker exec -i postgres psql -U postgres -d ragent < resources/database/upgrade_v1.9_to_v1.10.sql
docker exec postgres psql -U postgres -d ragent -c "\dt t_eval_*"
```
Expected output：列出 4 张表 `t_eval_gold_dataset / t_eval_gold_item / t_eval_result / t_eval_run`

- [ ] **Step 3: 同步 schema_pg.sql（新安装路径）**

在 `resources/database/schema_pg.sql` 末尾追加与 upgrade SQL 相同的 4 个 `CREATE TABLE` + 索引 + COMMENT 块（不要 `-- v1.9 → v1.10` 注释，改成：`-- ============================================\n-- RAG Evaluation (eval 域)\n-- ============================================`）

- [ ] **Step 4: 更新根 CLAUDE.md 的 Upgrade scripts 段**

`CLAUDE.md` 里找到 `upgrade_v1.8_to_v1.9.sql` 那条，追加下一行：

```markdown
- `upgrade_v1.9_to_v1.10.sql` — 新增 eval 域 4 张表（`t_eval_gold_dataset / t_eval_gold_item / t_eval_run / t_eval_result`）
```

- [ ] **Step 5: 提交**

```bash
git add resources/database/upgrade_v1.9_to_v1.10.sql resources/database/schema_pg.sql CLAUDE.md
git commit -m "feat(eval): add v1.10 schema for RAG eval domain (4 tables)"
```

---

## Task 2: `EvalProperties` + `application.yaml` 配置

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/config/EvalPropertiesTest.java`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 写失败测试**

Create `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/config/EvalPropertiesTest.java`:

```java
package com.knowledgebase.ai.ragent.eval.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalPropertiesTest {

    @Test
    void bindsFromYamlLikeProperties() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "rag.eval.python-service.url", "http://ragent-eval:9091",
                "rag.eval.python-service.timeout-ms", "120000",
                "rag.eval.python-service.max-retries", "2",
                "rag.eval.synthesis.default-count", "50",
                "rag.eval.synthesis.max-per-doc", "5",
                "rag.eval.synthesis.strong-model", "qwen-max",
                "rag.eval.run.batch-size", "5",
                "rag.eval.run.per-item-timeout-ms", "30000",
                "rag.eval.run.max-parallel-runs", "1"
        )));
        EvalProperties props = Binder.get(env).bind("rag.eval", EvalProperties.class).get();

        assertThat(props.getPythonService().getUrl()).isEqualTo("http://ragent-eval:9091");
        assertThat(props.getPythonService().getTimeoutMs()).isEqualTo(120_000);
        assertThat(props.getSynthesis().getDefaultCount()).isEqualTo(50);
        assertThat(props.getSynthesis().getMaxPerDoc()).isEqualTo(5);
        assertThat(props.getSynthesis().getStrongModel()).isEqualTo("qwen-max");
        assertThat(props.getRun().getBatchSize()).isEqualTo(5);
        assertThat(props.getRun().getMaxParallelRuns()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=EvalPropertiesTest
```
Expected：FAIL，编译错误 `cannot find symbol class EvalProperties`

- [ ] **Step 3: 写 EvalProperties**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java`（Apache 2.0 header 参照项目里其他文件）:

```java
package com.knowledgebase.ai.ragent.eval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * eval 域配置属性（rag.eval.*）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.eval")
public class EvalProperties {

    private PythonService pythonService = new PythonService();
    private Synthesis synthesis = new Synthesis();
    private Run run = new Run();

    @Data
    public static class PythonService {
        private String url = "http://ragent-eval:9091";
        private int timeoutMs = 120_000;
        private int maxRetries = 2;
    }

    @Data
    public static class Synthesis {
        private int defaultCount = 50;
        private int maxPerDoc = 5;
        private String strongModel = "qwen-max";
    }

    @Data
    public static class Run {
        private int batchSize = 5;
        private int perItemTimeoutMs = 30_000;
        private int maxParallelRuns = 1;
    }
}
```

- [ ] **Step 4: 再跑测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=EvalPropertiesTest
```
Expected：PASS

- [ ] **Step 5: 更新 application.yaml**

找到现有 `rag:` 节（已有 `rag.vector.*`, `rag.retrieval.*` 等），追加：

```yaml
rag:
  # ... existing entries ...
  eval:
    python-service:
      url: http://ragent-eval:9091
      timeout-ms: 120000
      max-retries: 2
    synthesis:
      default-count: 50
      max-per-doc: 5
      strong-model: qwen-max
    run:
      batch-size: 5
      per-item-timeout-ms: 30000
      max-parallel-runs: 1
```

- [ ] **Step 6: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/config/EvalPropertiesTest.java \
        bootstrap/src/main/resources/application.yaml
git commit -m "feat(eval): add EvalProperties config binding + rag.eval.* yaml"
```

---

## Task 3: `EvalAsyncConfig` — evalExecutor bean

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/async/EvalAsyncConfig.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/async/EvalAsyncConfigTest.java`

**关键设计**：不加 `@EnableAsync`（spec Gotcha #12：避免让 legacy `RagEvaluationServiceImpl.saveRecord` 的失效 `@Async` 突然生效）。只定义一个显式命名的 `ThreadPoolTaskExecutor` bean，名字 `evalExecutor`，业务代码以 `@Qualifier("evalExecutor")` 注入后 `execute()`。

- [ ] **Step 1: 写失败测试**

Create `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/async/EvalAsyncConfigTest.java`:

```java
package com.knowledgebase.ai.ragent.eval.async;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class EvalAsyncConfigTest {

    @Test
    void evalExecutorIsBoundedThreadPool() {
        EvalAsyncConfig config = new EvalAsyncConfig();
        ThreadPoolTaskExecutor executor = config.evalExecutor();
        executor.initialize();

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("eval-exec-");
    }

    @Test
    void evalExecutorQueueCapacityIs50() {
        EvalAsyncConfig config = new EvalAsyncConfig();
        ThreadPoolTaskExecutor executor = config.evalExecutor();
        executor.initialize();
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=EvalAsyncConfigTest
```
Expected：FAIL，class not found

- [ ] **Step 3: 写 EvalAsyncConfig**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/async/EvalAsyncConfig.java`:

```java
package com.knowledgebase.ai.ragent.eval.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * eval 域异步执行线程池配置。
 *
 * <p>不使用 Spring `@EnableAsync` —— 项目主启动类没有开启全局 async，
 * 且 legacy `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 注解目前处于失效状态（EVAL-2）。
 * 这里只定义一个显式命名的 `evalExecutor` bean，业务代码 `@Qualifier("evalExecutor")` 注入后
 * 调 `execute(Runnable)`，不依赖全局 async 语义。
 */
@Configuration
public class EvalAsyncConfig {

    @Bean(name = "evalExecutor")
    public ThreadPoolTaskExecutor evalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("eval-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
```

- [ ] **Step 4: 再跑测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=EvalAsyncConfigTest
```
Expected：PASS（两个方法都绿）

- [ ] **Step 5: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/async/EvalAsyncConfig.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/async/EvalAsyncConfigTest.java
git commit -m "feat(eval): add evalExecutor ThreadPoolTaskExecutor bean"
```

---

## Task 4: 4 个 DO + 4 个 Mapper 接口

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/GoldDatasetDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/GoldItemDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/EvalRunDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/EvalResultDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/GoldDatasetMapper.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/GoldItemMapper.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/EvalRunMapper.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/EvalResultMapper.java`

**无单测**：纯 MyBatis Plus boilerplate，Task 5 的 `@MapperScan` 启动期会作为事实验证。

- [ ] **Step 1: 写 GoldDatasetDO**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/GoldDatasetDO.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_gold_dataset")
public class GoldDatasetDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String kbId;
    private String name;
    private String description;
    private String status;
    private Integer itemCount;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 2: 写 GoldItemDO**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/GoldItemDO.java`（同风格）:

```java
package com.knowledgebase.ai.ragent.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_gold_item")
public class GoldItemDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String datasetId;
    private String question;
    private String groundTruthAnswer;
    private String sourceChunkId;
    private String sourceChunkText;
    private String sourceDocId;
    private String sourceDocName;
    private String reviewStatus;
    private String reviewNote;
    private String synthesizedByModel;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 3: 写 EvalRunDO**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/EvalRunDO.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_run")
public class EvalRunDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String datasetId;
    private String kbId;
    private String triggeredBy;
    private String status;
    private Integer totalItems;
    private Integer succeededItems;
    private Integer failedItems;
    private String metricsSummary;
    private String systemSnapshot;
    private String evaluatorLlm;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 4: 写 EvalResultDO**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/entity/EvalResultDO.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_result")
public class EvalResultDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String runId;
    private String goldItemId;
    private String question;
    private String groundTruthAnswer;
    private String systemAnswer;
    private String retrievedChunks;
    private BigDecimal faithfulness;
    private BigDecimal answerRelevancy;
    private BigDecimal contextPrecision;
    private BigDecimal contextRecall;
    private String error;
    private Integer elapsedMs;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
```

- [ ] **Step 5: 写 4 个 Mapper**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/GoldDatasetMapper.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldDatasetDO;

public interface GoldDatasetMapper extends BaseMapper<GoldDatasetDO> {
}
```

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/GoldItemMapper.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldItemDO;

public interface GoldItemMapper extends BaseMapper<GoldItemDO> {
}
```

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/EvalRunMapper.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.eval.dao.entity.EvalRunDO;

public interface EvalRunMapper extends BaseMapper<EvalRunDO> {
}
```

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/mapper/EvalResultMapper.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.eval.dao.entity.EvalResultDO;

public interface EvalResultMapper extends BaseMapper<EvalResultDO> {
}
```

- [ ] **Step 6: 编译确认**

```bash
mvn -pl bootstrap compile
```
Expected：BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/dao/
git commit -m "feat(eval): add 4 DO entities + 4 BaseMapper interfaces for eval domain"
```

---

## Task 5: `@MapperScan` 加 eval.dao.mapper + 启动期装配验证

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/dao/mapper/EvalMapperScanTest.java`

**注意**：这是 spec Gotcha #14 明确点名的陷阱——不改 `@MapperScan` 所有 eval mapper bean 都装配不了。

- [ ] **Step 1: 先写失败测试**

Create `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/dao/mapper/EvalMapperScanTest.java`:

```java
package com.knowledgebase.ai.ragent.eval.dao.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EvalMapperScanTest {

    @Autowired(required = false)
    private GoldDatasetMapper goldDatasetMapper;
    @Autowired(required = false)
    private GoldItemMapper goldItemMapper;
    @Autowired(required = false)
    private EvalRunMapper evalRunMapper;
    @Autowired(required = false)
    private EvalResultMapper evalResultMapper;

    @Test
    void allFourEvalMappersAreWiredByMapperScan() {
        assertThat(goldDatasetMapper).as("GoldDatasetMapper").isNotNull();
        assertThat(goldItemMapper).as("GoldItemMapper").isNotNull();
        assertThat(evalRunMapper).as("EvalRunMapper").isNotNull();
        assertThat(evalResultMapper).as("EvalResultMapper").isNotNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=EvalMapperScanTest
```
Expected：FAIL —— 要么 context 启动期 `UnsatisfiedDependencyException`，要么 4 个字段都 null（因为 `required=false` 时装不到就给 null）

- [ ] **Step 3: 改 RagentApplication.@MapperScan**

Modify `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java`（现在 basePackages 有 4 个，加第 5 个）:

```java
@MapperScan(basePackages = {
        "com.knowledgebase.ai.ragent.rag.dao.mapper",
        "com.knowledgebase.ai.ragent.ingestion.dao.mapper",
        "com.knowledgebase.ai.ragent.knowledge.dao.mapper",
        "com.knowledgebase.ai.ragent.user.dao.mapper",
        "com.knowledgebase.ai.ragent.eval.dao.mapper"
})
```

- [ ] **Step 4: 再跑测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=EvalMapperScanTest
```
Expected：PASS（4 个 mapper 都成功装配）

- [ ] **Step 5: 启动期 smoke（手动）**

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```
Expected：应用正常启动到 `Started RagentApplication in X.X seconds`，无 `UnsatisfiedDependencyException`。Ctrl+C 停。

- [ ] **Step 6: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/dao/mapper/EvalMapperScanTest.java
git commit -m "feat(eval): register eval.dao.mapper in @MapperScan + startup assertion test"
```

---

## Task 6: Domain DTOs — Python 服务契约

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizeChunkInput.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizedItem.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizeRequest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizeResponse.java`

对照 spec §7.3 的 Python `/synthesize` 契约。

- [ ] **Step 1: 写 4 个 record**

**注意**：Python 返回 snake_case，Java 默认 Jackson 不自动转 camelCase，所有与 Python 契约对应的字段必须用 `@JsonProperty` 显式映射。

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizeChunkInput.java`:

```java
package com.knowledgebase.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 送给 Python /synthesize 的单条 chunk 输入。
 * Java 侧采样时已从 t_knowledge_chunk + t_knowledge_document join 出这些字段。
 */
public record SynthesizeChunkInput(
        String id,
        String text,
        @JsonProperty("doc_name") String docName
) {
}
```

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizedItem.java`:

```java
package com.knowledgebase.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python /synthesize 返回的单条合成结果。
 * 仅包含 LLM 产出的三件套；chunk_text / doc_name 等由 Java 侧冻结。
 */
public record SynthesizedItem(
        @JsonProperty("source_chunk_id") String sourceChunkId,
        String question,
        String answer
) {
}
```

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizeRequest.java`:

```java
package com.knowledgebase.ai.ragent.eval.domain;

import java.util.List;

public record SynthesizeRequest(List<SynthesizeChunkInput> chunks) {
}
```

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/SynthesizeResponse.java`:

```java
package com.knowledgebase.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SynthesizeResponse(
        List<SynthesizedItem> items,
        @JsonProperty("failed_chunk_ids") List<String> failedChunkIds
) {
}
```

- [ ] **Step 2: 编译确认**

```bash
mvn -pl bootstrap compile
```
Expected：BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/
git commit -m "feat(eval): add synthesize request/response record DTOs"
```

---

## Task 7: `RagasEvalClient` — Python HTTP 客户端骨架

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java`

**说明**：PR E1 只写骨架 + RestClient 调用，不加单测（没有 WireMock，Mockito 对 RestClient 链式 API 不友好，得不偿失）。Task 14 的 E2E smoke 会实打实打一次真实服务，那是可靠验证。消费方（`GoldDatasetSynthesisService`）在 PR E2 加进来时再写集成测试。

- [ ] **Step 1: 写 RagasEvalClient**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java`:

```java
package com.knowledgebase.ai.ragent.eval.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgebase.ai.ragent.eval.config.EvalProperties;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizeRequest;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Python ragent-eval 服务 HTTP 客户端。
 *
 * <p>无状态、无 ThreadLocal；失败快速抛异常，由调用方决定兜底策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagasEvalClient {

    private final EvalProperties evalProperties;

    private RestClient buildClient() {
        return RestClient.builder()
                .baseUrl(evalProperties.getPythonService().getUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    log.debug("[ragas-eval] {} {}", request.getMethod(), request.getURI());
                    return execution.execute(request, body);
                })
                .build();
    }

    public SynthesizeResponse synthesize(SynthesizeRequest request) {
        return buildClient()
                .post()
                .uri("/synthesize")
                .body(request)
                .retrieve()
                .body(SynthesizeResponse.class);
    }

    public HealthStatus health() {
        return buildClient()
                .get()
                .uri("/health")
                .retrieve()
                .body(HealthStatus.class);
    }

    public record HealthStatus(
            String status,
            @JsonProperty("ragas_version") String ragasVersion,
            @JsonProperty("evaluator_llm") String evaluatorLlm
    ) {
    }
}
```

- [ ] **Step 2: 编译确认**

```bash
mvn -pl bootstrap compile
```
Expected：BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java
git commit -m "feat(eval): add RagasEvalClient HTTP skeleton (synthesize + health)"
```

---

## Task 8: `eval/CLAUDE.md` 域说明文档

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md`
- Modify: `bootstrap/CLAUDE.md`（加 eval 域指针 + legacy 警告）

- [ ] **Step 1: 写 eval/CLAUDE.md**

Create `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md`:

````markdown
# eval 域（RAG 评估闭环）

独立 bounded context，不属于 `rag/` 域。见设计文档 `docs/superpowers/specs/2026-04-24-rag-eval-closed-loop-design.md`。

## 职责

- Gold Set 管理（合成 / 审核 / 激活）
- 评估运行调度 + 结果聚合
- 调 Python `ragent-eval` 服务跑 RAGAS 四指标

## 依赖方向（硬约束）

```
eval/  → rag.core.ChatForEvalService   (✓ 合法 port，PR E3 引入)
eval/  → knowledge.KbReadAccessPort     (✓ 合法 port)
eval/  → framework.*                    (✓ 合法)

eval/  ← ⛔ rag/ 不得依赖 eval
eval/  ← ⛔ 读/写 rag/ 内部表
```

## 目录结构

```
eval/
├── controller/   REST 入口（PR E2+）
├── service/      业务编排（PR E2+）
├── async/        EvalAsyncConfig —— evalExecutor bean
├── dao/          4 个 DO + Mapper
├── domain/       DTOs
├── client/       RagasEvalClient
└── config/       EvalProperties
```

## 关键 Gotchas（本域专属，通用坑点见 `docs/dev/gotchas.md`）

1. **零 ThreadLocal 新增**：所有跨方法/跨线程状态走参数 / record / DO。违反示例：
   - `class EvalRunContext extends ThreadLocal<EvalRun>` ❌
   - `RagasEvalClient` 里读 `RagTraceContext.traceId` ❌
   - `evalExecutor + TaskDecorator 续 UserContext` ❌

2. **`@MapperScan` 必须包含 eval.dao.mapper**：在 `RagentApplication.@MapperScan` 里显式写上，否则启动期 `UnsatisfiedDependencyException`。

3. **不使用 `@Async` 注解**：项目主启动类没有 `@EnableAsync`。eval 域一律用 `@Qualifier("evalExecutor")` 注入 + 显式 `execute()`。

4. **系统级 `AccessScope.all()` 仅 SUPER_ADMIN 手动触发合法**（PR E3 起生效）：扩展到定时任务 / 部门管理员 / 回归守门等场景前**必须**重新做权限模型。

5. **评估读接口一律 SUPER_ADMIN**（PR E2+ 起生效）：`t_eval_result.retrieved_chunks` 是系统级检索产物，含跨 `security_level` 内容；直到 EVAL-3（查询侧 redaction）落地前不得降级为 `AnyAdmin`。

## 配置

`application.yaml` 下 `rag.eval.*`（见 `EvalProperties`）。

## 测试

- 配置绑定：`EvalPropertiesTest`（纯 Binder，无 Spring context）
- 线程池：`EvalAsyncConfigTest`（纯 bean 构造）
- Mapper 装配：`EvalMapperScanTest`（`@SpringBootTest`）
````

- [ ] **Step 2: 改 bootstrap/CLAUDE.md 加入口 + legacy 说明**

在 `bootstrap/CLAUDE.md` 的"### rag 域"表格前面（或顶部的"代码组织"节末尾），加一段：

```markdown
### eval 域（RAG 评估闭环）

独立顶级域，详见 `eval/CLAUDE.md`。

⚠️ **不要混淆**：`rag/service/impl/RagEvaluationServiceImpl.java` 是 legacy trace 留存（当前处于失效 `@Async` 状态，见 backlog EVAL-2），**不是**新评估域入口；新评估见 `eval/`。
```

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md bootstrap/CLAUDE.md
git commit -m "docs(eval): add eval domain CLAUDE.md + legacy evaluation warning"
```

---

## Task 9: Python `settings.py` — pydantic-settings 配置

**Files:**
- Create: `ragas/ragas/settings.py`
- Create: `ragas/ragas/__init__.py`（如不存在）
- Create: `ragas/tests/__init__.py`
- Create: `ragas/tests/test_settings.py`

- [ ] **Step 1: 确保 Python 包结构**

```bash
# PowerShell (Windows) or any shell
touch ragas/ragas/__init__.py
touch ragas/tests/__init__.py
```
（Windows 如 `touch` 不可用，手动 new empty file）

- [ ] **Step 2: 写失败测试**

Create `ragas/tests/test_settings.py`:

```python
import os
from unittest.mock import patch

import pytest


def test_settings_reads_env_vars(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-test-xxx")
    monkeypatch.setenv("EVALUATOR_CHAT_MODEL", "qwen3.5-flash")
    monkeypatch.setenv("SYNTHESIS_STRONG_MODEL", "qwen-max")

    # 新鲜 import 绕过 lru_cache
    from ragas.settings import Settings
    settings = Settings()

    assert settings.dashscope_api_key == "sk-test-xxx"
    assert settings.evaluator_chat_model == "qwen3.5-flash"
    assert settings.synthesis_strong_model == "qwen-max"
    assert settings.dashscope_base_url.startswith("https://dashscope")


def test_settings_missing_api_key_raises(monkeypatch):
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    from ragas.settings import Settings
    with pytest.raises(Exception):
        Settings()
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd ragas
python -m pytest tests/test_settings.py -v
```
Expected：FAIL（`ModuleNotFoundError: No module named 'ragas.settings'` —— 或 `pytest` 没装）

如果 pytest 没装，先装：`pip install pytest pydantic-settings`

- [ ] **Step 4: 写 settings.py**

Create `ragas/ragas/settings.py`:

```python
"""pydantic-settings 配置，从环境变量读取百炼 API key 等敏感值。"""
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # 百炼 API 配置
    dashscope_api_key: str = Field(..., description="百炼 API key，从 env 注入")
    dashscope_base_url: str = Field(
        default="https://dashscope.aliyuncs.com/compatible-mode/v1",
    )

    # 评测用模型（评价 RAG 输出质量）
    evaluator_chat_model: str = Field(default="qwen3.5-flash")
    evaluator_embedding_model: str = Field(default="text-embedding-v3")

    # 合成用强模型（生成 Gold Set 问答对）
    synthesis_strong_model: str = Field(default="qwen-max")
```

- [ ] **Step 5: 再跑测试确认通过**

```bash
cd ragas
python -m pytest tests/test_settings.py -v
```
Expected：PASS（两个 case）

- [ ] **Step 6: 提交**

```bash
git add ragas/ragas/__init__.py ragas/ragas/settings.py \
        ragas/tests/__init__.py ragas/tests/test_settings.py
git commit -m "feat(eval): add Python Settings (pydantic-settings) + env var loading"
```

---

## Task 10: Python `synthesize.py` — 合成逻辑

**Files:**
- Create: `ragas/ragas/synthesize.py`
- Create: `ragas/tests/test_synthesize.py`

**关键设计**：`synthesize_one(chunk, client, model)` 用 LLM 产出 Q-A，调用方（`app.py`）负责错误兜底和组装 response。

- [ ] **Step 1: 写失败测试**

Create `ragas/tests/test_synthesize.py`:

```python
from unittest.mock import MagicMock

import pytest


def _fake_llm_response(content: str):
    """构造 OpenAI SDK 风格的响应对象（至少具备 choices[0].message.content）。"""
    response = MagicMock()
    response.choices = [MagicMock()]
    response.choices[0].message.content = content
    return response


def test_synthesize_one_parses_json_envelope():
    from ragas.synthesize import synthesize_one, ChunkInput

    client = MagicMock()
    client.chat.completions.create.return_value = _fake_llm_response(
        '{"question": "如何配置 X？", "answer": "在 yaml 里设 X=y。"}'
    )

    chunk = ChunkInput(id="c1", text="X 是一个参数...", doc_name="manual.pdf")
    result = synthesize_one(chunk, client, model="qwen-max")

    assert result.source_chunk_id == "c1"
    assert result.question == "如何配置 X？"
    assert result.answer == "在 yaml 里设 X=y。"


def test_synthesize_one_bad_json_raises():
    from ragas.synthesize import synthesize_one, ChunkInput, SynthesisError

    client = MagicMock()
    client.chat.completions.create.return_value = _fake_llm_response(
        "这不是 JSON"
    )
    chunk = ChunkInput(id="c2", text="...", doc_name="x.pdf")

    with pytest.raises(SynthesisError):
        synthesize_one(chunk, client, model="qwen-max")


def test_synthesize_one_empty_answer_raises():
    from ragas.synthesize import synthesize_one, ChunkInput, SynthesisError

    client = MagicMock()
    client.chat.completions.create.return_value = _fake_llm_response(
        '{"question": "XX？", "answer": ""}'
    )
    chunk = ChunkInput(id="c3", text="...", doc_name="x.pdf")

    with pytest.raises(SynthesisError, match="empty answer"):
        synthesize_one(chunk, client, model="qwen-max")
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd ragas
python -m pytest tests/test_synthesize.py -v
```
Expected：FAIL（`ModuleNotFoundError: ragas.synthesize`）

- [ ] **Step 3: 写 synthesize.py**

Create `ragas/ragas/synthesize.py`:

```python
"""Gold Set 合成：给定 chunk，用强模型反向生成 (Q, A) 对。

契约：只返回 LLM 产出的 source_chunk_id + question + answer；
      source_chunk_text / source_doc_id / source_doc_name 由 Java 侧冻结。
"""
import json
from dataclasses import dataclass


SYNTHESIS_PROMPT = """你是一位领域专家。请基于下面这段文档片段，生成一个**自然的用户问题**和**基于片段可直接回答的标准答案**。

要求：
1. 问题要像真实用户会问的，不要太学术
2. 答案必须能从片段中**直接**得出；不许编造片段外信息
3. 严格返回 JSON 对象，格式：{"question": "...", "answer": "..."}，不要额外解释

文档片段：
---
{text}
---

（文档来源：{doc_name}）
"""


class SynthesisError(Exception):
    """合成失败（LLM 返回非法 JSON / 字段空 / 超时等）。"""


@dataclass
class ChunkInput:
    id: str
    text: str
    doc_name: str


@dataclass
class SynthesizedItem:
    source_chunk_id: str
    question: str
    answer: str


def synthesize_one(chunk: ChunkInput, client, model: str) -> SynthesizedItem:
    """用 client（OpenAI 兼容）调 model 合成一条 Q-A。失败抛 SynthesisError。"""
    prompt = SYNTHESIS_PROMPT.format(text=chunk.text, doc_name=chunk.doc_name)
    response = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": prompt}],
        temperature=0.3,
        response_format={"type": "json_object"},
    )
    content = response.choices[0].message.content

    try:
        data = json.loads(content)
    except (json.JSONDecodeError, TypeError) as e:
        raise SynthesisError(f"LLM returned non-JSON for chunk {chunk.id}: {e}") from e

    question = (data.get("question") or "").strip()
    answer = (data.get("answer") or "").strip()

    if not question:
        raise SynthesisError(f"empty question for chunk {chunk.id}")
    if not answer:
        raise SynthesisError(f"empty answer for chunk {chunk.id}")

    return SynthesizedItem(
        source_chunk_id=chunk.id,
        question=question,
        answer=answer,
    )
```

- [ ] **Step 4: 再跑测试确认通过**

```bash
cd ragas
python -m pytest tests/test_synthesize.py -v
```
Expected：PASS（3 个 case）

- [ ] **Step 5: 提交**

```bash
git add ragas/ragas/synthesize.py ragas/tests/test_synthesize.py
git commit -m "feat(eval): add Python synthesize_one with JSON envelope + fail-fast"
```

---

## Task 11: Python `app.py` — FastAPI 服务

**Files:**
- Create: `ragas/ragas/app.py`
- Create: `ragas/tests/test_app.py`

- [ ] **Step 1: 写失败测试**

Create `ragas/tests/test_app.py`:

```python
from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-test")
    from fastapi.testclient import TestClient
    from ragas.app import app
    return TestClient(app)


def test_health_returns_ok(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert "ragas_version" in body
    assert "evaluator_llm" in body


def test_synthesize_returns_items_for_given_chunks(client):
    """一条成功 + 一条失败的混合批，返回 items[0] 且 failed_chunk_ids=[c2]."""
    from ragas.synthesize import SynthesizedItem, SynthesisError

    def fake_synthesize_one(chunk, client_arg, model):
        if chunk.id == "c1":
            return SynthesizedItem(
                source_chunk_id="c1",
                question="X 是啥？",
                answer="X 是 Y。",
            )
        raise SynthesisError(f"forced failure for {chunk.id}")

    with patch("ragas.app.synthesize_one", side_effect=fake_synthesize_one):
        r = client.post(
            "/synthesize",
            json={
                "chunks": [
                    {"id": "c1", "text": "X 是 Y 的别名...", "doc_name": "a.pdf"},
                    {"id": "c2", "text": "异常触发片段", "doc_name": "b.pdf"},
                ]
            },
        )

    assert r.status_code == 200
    body = r.json()
    assert len(body["items"]) == 1
    assert body["items"][0]["source_chunk_id"] == "c1"
    assert body["items"][0]["question"] == "X 是啥？"
    assert body["items"][0]["answer"] == "X 是 Y。"
    assert body["failed_chunk_ids"] == ["c2"]
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd ragas
pip install fastapi uvicorn httpx   # TestClient 需要 httpx
python -m pytest tests/test_app.py -v
```
Expected：FAIL（`ModuleNotFoundError: ragas.app`）

- [ ] **Step 3: 写 app.py**

Create `ragas/ragas/app.py`:

```python
"""ragent-eval FastAPI 服务入口。

PR E1：只实现 /health 和 /synthesize（真 LLM 调用，不落库）。
PR E3 再加 /evaluate。
"""
from functools import lru_cache
from typing import List

from fastapi import FastAPI
from openai import OpenAI
from pydantic import BaseModel

from ragas.settings import Settings
from ragas.synthesize import ChunkInput, SynthesisError, synthesize_one


app = FastAPI(title="ragent-eval", version="0.1.0")


@lru_cache(maxsize=1)
def _settings() -> Settings:
    return Settings()


@lru_cache(maxsize=1)
def _llm_client() -> OpenAI:
    s = _settings()
    return OpenAI(api_key=s.dashscope_api_key, base_url=s.dashscope_base_url)


# ============================================
# /health
# ============================================
class HealthResponse(BaseModel):
    status: str
    ragas_version: str
    evaluator_llm: str


@app.get("/health", response_model=HealthResponse)
def health():
    try:
        import ragas as ragas_pkg
        ragas_version = getattr(ragas_pkg, "__version__", "unknown")
    except Exception:
        ragas_version = "unknown"
    return HealthResponse(
        status="ok",
        ragas_version=ragas_version,
        evaluator_llm=_settings().evaluator_chat_model,
    )


# ============================================
# /synthesize
# ============================================
class SynthChunkIn(BaseModel):
    id: str
    text: str
    doc_name: str


class SynthesizeRequest(BaseModel):
    chunks: List[SynthChunkIn]


class SynthItemOut(BaseModel):
    source_chunk_id: str
    question: str
    answer: str


class SynthesizeResponse(BaseModel):
    items: List[SynthItemOut]
    failed_chunk_ids: List[str]


@app.post("/synthesize", response_model=SynthesizeResponse)
def synthesize(request: SynthesizeRequest):
    settings = _settings()
    client = _llm_client()

    items: List[SynthItemOut] = []
    failed: List[str] = []

    for c in request.chunks:
        chunk = ChunkInput(id=c.id, text=c.text, doc_name=c.doc_name)
        try:
            result = synthesize_one(chunk, client, model=settings.synthesis_strong_model)
            items.append(SynthItemOut(
                source_chunk_id=result.source_chunk_id,
                question=result.question,
                answer=result.answer,
            ))
        except SynthesisError as e:
            failed.append(chunk.id)

    return SynthesizeResponse(items=items, failed_chunk_ids=failed)
```

- [ ] **Step 4: 再跑测试确认通过**

```bash
cd ragas
python -m pytest tests/test_app.py -v
```
Expected：PASS（2 个 case）

- [ ] **Step 5: 提交**

```bash
git add ragas/ragas/app.py ragas/tests/test_app.py
git commit -m "feat(eval): add FastAPI app with /health + /synthesize endpoints"
```

---

## Task 12: `requirements.txt` + `Dockerfile`

**Files:**
- Create: `ragas/requirements.txt`
- Create: `ragas/requirements-dev.txt`
- Create: `ragas/Dockerfile`
- Create: `ragas/.dockerignore`

- [ ] **Step 1: 写 requirements.txt（运行时）**

Create `ragas/requirements.txt`:

```
fastapi==0.115.*
uvicorn[standard]==0.32.*
pydantic==2.*
pydantic-settings==2.*
openai==1.*
ragas==0.2.*
```

- [ ] **Step 2: 写 requirements-dev.txt（测试）**

Create `ragas/requirements-dev.txt`:

```
-r requirements.txt
pytest==8.*
pytest-asyncio==0.24.*
httpx==0.28.*
```

- [ ] **Step 3: 写 Dockerfile**

Create `ragas/Dockerfile`:

```dockerfile
FROM python:3.11-slim

WORKDIR /app

# 装系统依赖（ragas 需要 gcc 编译某些 wheel）
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY ragas ./ragas

EXPOSE 9091

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:9091/health').read()"

CMD ["uvicorn", "ragas.app:app", "--host", "0.0.0.0", "--port", "9091"]
```

- [ ] **Step 4: 写 .dockerignore**

Create `ragas/.dockerignore`:

```
tests/
log/
run_eval.py
__pycache__/
*.pyc
.pytest_cache/
.venv/
requirements-dev.txt
```

- [ ] **Step 5: 本地 docker build + run 验证**

```bash
cd ragas
docker build -t ragent-eval:test .
docker run --rm -d --name ragent-eval-test -p 9091:9091 \
    -e DASHSCOPE_API_KEY=sk-fake-for-health-only ragent-eval:test
sleep 5
curl http://localhost:9091/health
docker stop ragent-eval-test
```
Expected：curl 返回 `{"status":"ok","ragas_version":"...","evaluator_llm":"qwen3.5-flash"}`

- [ ] **Step 6: 提交**

```bash
git add ragas/requirements.txt ragas/requirements-dev.txt ragas/Dockerfile ragas/.dockerignore
git commit -m "feat(eval): add Python service Dockerfile + requirements split"
```

---

## Task 13: compose yaml + `.env.example` + launch docs

**Files:**
- Create: `resources/docker/ragent-eval.compose.yaml`
- Create: `.env.example`（如不存在）
- Modify: `docs/dev/setup/launch.md`（加启动命令）

- [ ] **Step 1: 写 compose yaml**

Create `resources/docker/ragent-eval.compose.yaml`:

```yaml
services:
  ragent-eval:
    build: ../../ragas
    image: ragent-eval:latest
    container_name: ragent-eval
    ports:
      - "9091:9091"
    environment:
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
      DASHSCOPE_BASE_URL: https://dashscope.aliyuncs.com/compatible-mode/v1
      EVALUATOR_CHAT_MODEL: qwen3.5-flash
      EVALUATOR_EMBEDDING_MODEL: text-embedding-v3
      SYNTHESIS_STRONG_MODEL: qwen-max
      # 代理绕过（对齐项目 NO_PROXY 惯例）
      NO_PROXY: localhost,127.0.0.1,ragent-eval
      no_proxy: localhost,127.0.0.1,ragent-eval
    restart: unless-stopped
```

- [ ] **Step 2: 创建/更新 .env.example**

检查仓库根目录是否有 `.env.example`：

```bash
ls -la .env.example 2>&1 || echo "MISSING"
```

若不存在，create `.env.example`:

```bash
# 百炼（DashScope）API Key，用于 ragent-eval Python 服务
# 申请：https://dashscope.console.aliyun.com/
DASHSCOPE_API_KEY=
```

若已存在，只在末尾追加上面两行。

- [ ] **Step 3: 更新 launch.md**

在 `docs/dev/setup/launch.md` 里找到 docker services 启动段（OpenSearch / RocketMQ 等所在处），末尾追加：

```markdown
### ragent-eval（RAG 评估 Python 服务，可选）

用于 Gold Set 合成与 RAGAS 四指标评估。开发环境默认不起，需要评估时再拉起。

```bash
# 先设 DASHSCOPE_API_KEY（或写进 .env）
export DASHSCOPE_API_KEY=sk-xxx    # Windows PowerShell: $env:DASHSCOPE_API_KEY='sk-xxx'

docker compose -f resources/docker/ragent-eval.compose.yaml up -d

# 验证
curl http://localhost:9091/health
```
```

- [ ] **Step 4: 提交**

```bash
git add resources/docker/ragent-eval.compose.yaml .env.example docs/dev/setup/launch.md
git commit -m "feat(eval): add ragent-eval compose file + launch docs + env placeholder"
```

---

## Task 14: 端到端 smoke（手动验证 PR E1 验收）

**无新文件**。这是对齐 spec §12 PR E1 验收标准的手工操作。需要**真实 DASHSCOPE_API_KEY**。

- [ ] **Step 1: 拉起 Python 服务**

```bash
export DASHSCOPE_API_KEY=sk-xxx-your-real-key
docker compose -f resources/docker/ragent-eval.compose.yaml up -d --build
sleep 10
```

- [ ] **Step 2: 验证 /health**

```bash
curl -s http://localhost:9091/health | jq
```
Expected：
```json
{
  "status": "ok",
  "ragas_version": "0.2.x",
  "evaluator_llm": "qwen3.5-flash"
}
```

- [ ] **Step 3: 真实 /synthesize 调用**

```bash
curl -s -X POST http://localhost:9091/synthesize \
    -H "Content-Type: application/json" \
    -d '{
      "chunks": [
        {
          "id": "test-chunk-1",
          "text": "Spring Boot 是一个基于 Spring 框架的开源 Java 应用框架，简化了新 Spring 应用的初始搭建与开发过程。它使用约定优于配置的理念，提供了起步依赖，大幅减少 XML 配置。",
          "doc_name": "spring-boot-intro.md"
        }
      ]
    }' | jq
```
Expected：
```json
{
  "items": [
    {
      "source_chunk_id": "test-chunk-1",
      "question": "...",
      "answer": "..."
    }
  ],
  "failed_chunk_ids": []
}
```
（question/answer 内容由 LLM 生成，大致应关于 Spring Boot）

- [ ] **Step 4: 启动 Java 应用，验证 mapper 装配**

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```
Expected：
- 启动日志有 `Started RagentApplication in X.X seconds`
- **无** `UnsatisfiedDependencyException`
- **无** `BeanCreationException` 相关 eval 包的错误

Ctrl+C 停。

- [ ] **Step 5: 跑一次完整测试（回归兜底）**

```bash
mvn -pl bootstrap test -Dtest="Eval*Test"
cd ragas && python -m pytest tests/ -v
```
Expected：
- Java 测试：`EvalPropertiesTest` / `EvalAsyncConfigTest` / `EvalMapperScanTest` 全 PASS
- Python 测试：6 个 case 全 PASS

- [ ] **Step 6: 清理 + 写 PR body**

```bash
docker compose -f resources/docker/ragent-eval.compose.yaml down
```

PR body 参考：

```markdown
## Summary
- 新增 eval 域顶级包与 4 张表 `t_eval_*`，打通 `@MapperScan` 装配
- 新增 Python ragent-eval 微服务（FastAPI + Dockerfile + compose），支持 `/health` 和 `/synthesize`
- 新增 `EvalProperties` / `EvalAsyncConfig` / `RagasEvalClient` 骨架

## Spec
`docs/superpowers/specs/2026-04-24-rag-eval-closed-loop-design.md`

## Test Plan
- [x] Java：`EvalPropertiesTest` / `EvalAsyncConfigTest` / `EvalMapperScanTest` 全绿
- [x] Python：6 个 unit tests 全绿
- [x] 手工 smoke：docker up ragent-eval + curl /synthesize 真实 LLM 返回合成 Q-A
- [x] 手工 smoke：`mvn spring-boot:run` 启动无 UnsatisfiedDependencyException

## Known Follow-ups (backlog)
- **EVAL-2**：legacy `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 失效 bug，独立 PR 修
- **EVAL-3**：评估读接口的 `security_level` redaction，放开 `AnyAdmin` 的前置
- PR E2：合成真落库 + 审核前端 + dataset 激活
- PR E3-spike：从 `streamChat` 抽 `AnswerPipeline` 的代价评估
- PR E3：评估执行 + 结果看板 + 趋势对比
```

---

## Self-Review Checklist

（Plan 作者 / 执行者在合并前过一遍）

- [ ] 所有 14 个 Task 的验收命令都跑过，输出符合 Expected
- [ ] `git log --oneline` 每个 task 一个 commit，message 都带 `feat(eval):` 或 `docs(eval):` 前缀
- [ ] `mvn spotless:check` 通过（如失败 `mvn spotless:apply` 后再提交）
- [ ] `mvn -pl bootstrap compile` 无警告
- [ ] 手工 checklist：打开 Spec §5 / §7 / §10 Gotcha #12 #14 #15 对照检查
- [ ] 没有把 PR E2/E3 的代码提前引入（`ChatForEvalService` / `EvalRunExecutor` / `GoldDatasetSynthesisService` / 前端页面都不应出现）
