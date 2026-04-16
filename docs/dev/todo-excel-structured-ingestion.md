# 代办：Excel 结构化入库

**创建日期**：2026-04-15
**状态**：待实施（尚未动代码）
**优先级**：中（现状可用但效果差，非阻塞）

---

## 背景

当前 Excel（`.xls` / `.xlsx`）入库走 `TikaDocumentParser.parseToString()`：

- 多 Sheet 被拼成一段长文本，丢失 **行/列/表头** 结构
- 再经 `FixedSizeTextChunker` 按字符数切块，**表格语义被切断**（同一行可能跨块，表头只在第一块）
- 对"查某字段在哪一行"这类结构化问答效果差

Tika 底层虽然走 POI，但 `parseToString` 把表格降级成纯文本流，不保留二维关系。

---

## 目标

1. 保留 Excel 表格结构到文本层（Markdown 表格）
2. 分块时不切断行、每块保留表头，便于向量召回后 LLM 能看到完整上下文
3. 兼容现有 CHUNK 模式 + PIPELINE 模式两条入库链路
4. 不影响非 Excel 文档的既有行为

---

## 方案

### 1. 新增 `ExcelDocumentParser`

**位置**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/ExcelDocumentParser.java`

- 与 `TikaDocumentParser` / `MarkdownDocumentParser` 并列，实现 `DocumentParser` 接口
- 基于 Apache POI `ss.usermodel`（统一处理 `.xls` HSSF 和 `.xlsx` XSSF）
- `supports(mimeType)` 命中：
  - `application/vnd.ms-excel`
  - `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- 输出格式：**每个 Sheet 一段 Markdown 表格**
  - 第一行作表头（列数由 `options.headerRow` 控制，默认 0）
  - 保留列对齐分隔符 `| --- |`
  - 空行跳过，合并单元格按左上值填充
  - Sheet 之间用 `## Sheet: <sheetName>` 标题分隔
- 返回 `ParseResult.ofText(markdown)`，下游分块器正常消费

### 2. 注册 `ParserType.EXCEL`

**位置**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/ParserType.java`

- 新增枚举值 `EXCEL("EXCEL")`

### 3. `DocumentParserSelector` 自动路由

- `selectByMimeType()` 已按 `supports()` 遍历，新 parser 加 `@Component` 即被 Spring 收集
- **无需改 selector 代码**

### 4. Pipeline 模式 `ParserNode` 分支路由

**位置**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/ParserNode.java`

- 已能识别 `EXCEL` 类型（`ParserNode.java:190, 219, 244`），只需在执行分支里 `type == "EXCEL"` 时显式选 `ExcelDocumentParser`，不再 fallback 到 Tika

### 5.（可选）`ExcelRowAwareChunker`

**位置**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/core/chunk/strategy/ExcelRowAwareChunker.java`

- 如果 `StructureAwareTextChunker` 已能按 Markdown 表格边界切分 → **跳过这步**
- 如果仍被切断，再实现：
  - 按 Sheet 边界分块
  - 单 Sheet 内每 N 行一块（N 由 `NodeConfig.maxRowsPerChunk` 配置）
  - **每块开头复制表头**，保证召回后 LLM 能理解列含义
- 先实现第 1~4 步上线看效果，再决定是否做这步

### 6. 依赖

**位置**：`bootstrap/pom.xml`

- Tika 3.2 的 `tika-parsers-standard-package` 已间接引入 POI，但版本由 Tika 锁定
- 如果直接用 POI API，显式加 `org.apache.poi:poi-ooxml`，**版本与 Tika 内带的对齐**，避免 classpath 冲突
- 确认命令：`mvn -pl bootstrap dependency:tree | grep poi`

### 7. 配置开关

**位置**：`bootstrap/src/main/resources/application.yaml`

```yaml
rag:
  parser:
    excel:
      enabled: true          # 关掉则回退 Tika
      header-row: 0          # 表头行索引
      max-rows-per-chunk: 50 # 可选分块器用
```

---

## 验证

- **单元测试**：`bootstrap/src/test/java/com/nageoffer/ai/ragent/core/parser/ExcelDocumentParserTests.java`
  - 覆盖 `.xls` / `.xlsx` / 多 Sheet / 空单元格 / 合并单元格 / 空 Sheet
- **端到端**：
  - 上传一个带表头的 `.xlsx`
  - 查 `t_knowledge_chunk` 确认每块含表头 + 完整行
  - 用表内某字段做 RAG 问答，看召回是否精准

---

## 不在本次范围

- 按单元格结构化检索（需要表格专用向量模型，另案）
- Excel 内嵌图表 / 图片 / OLE 对象解析
- 跨 Sheet 联表问答

---

## 影响面

- 新增文件：2~3 个（parser + 测试 + 可选 chunker）
- 修改文件：3 个（`ParserType`、`ParserNode`、`pom.xml`、`application.yaml`）
- **不影响** 非 Excel 文档的既有链路（`Tika` 仍是兜底）
- **不影响** 数据库 schema
