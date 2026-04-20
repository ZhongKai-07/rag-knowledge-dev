# OPS 部门试点 · 一个月收敛计划

- **日期**: 2026-04-17
- **范围**: OPS 单部门试点,周期一个月
- **前提(已锁定)**:
  - 向量库只用 OpenSearch(不再评估 Milvus / pgvector)
  - 文档解析从 Apache Tika 迁移到 docling-java
  - OPS 语料规模:**~10 GB / 数千份文档**(估算分块数百万级)
  - 除本文件列出的条目外,现有能力可接受

---

## 一、一句话目标

用 OPS 部门真实文档和真实查询,把「**检索质量 · 可观测 · 合规审计 · 反馈回流**」四条主线跑通,为下一阶段扩展部门打基础。

---

## 二、本期必做清单(P0)

| # | 项目 | 关键动作 | 验收 |
|---|---|---|---|
| 1 | Tika → docling-java | ParserNode 策略化;新增 `DoclingDocumentParser`;保留 Tika 作 fallback;ChunkerNode 支持 docling 的表格/版式元数据 | OPS 文档 top-20 难样本,正确率 ≥ Tika + 30% |
| 2a | Hybrid 检索健康度保障(**注:Hybrid 已实现,本条是兜底**) | `OpenSearchRetrieverService` 启动时若未检测到 `ragent-hybrid-search-pipeline`,自动创建而非静默降级;日志等级由 `debug` 升级为 `warn`;新增 Prometheus gauge `opensearch_hybrid_pipeline_ready`;Grafana 大盘可见 | 生产环境 pipeline 确实启用(非 knn-only 退化);大盘可查 pipeline 状态 |
| 2b | Hybrid 权重 / 中文分词调参 | 基于 OPS Golden Set 迭代 RRF 或加权权重 2~3 轮;评估并确定中文 analyzer(`ik_max_word` / `smartcn` / 自定义词典)与 OPS 术语表对齐 | Golden Set recall@10 相对 **knn-only 基线** ≥ +15%;术语/型号召回全部命中 |
| 3 | 审计日志统一管道 | 新建 `t_audit_log`;`@Audit` 注解 + AOP 异步写;覆盖问答链路(问题/命中 chunkId/答案/tokenUsage)+ 权限类 CRUD | 可按"用户 × 时间"导出 CSV;不阻塞业务路径 |
| 4 | 低置信度兜底 + 用户反馈闭环 | 检索 top1 score < 阈值则不调 LLM,直接返回"未检索到";消息尾部 👍/👎 + 可选文字,写 `t_message_feedback`;定期回流到 `t_rag_evaluation_record` | 试点期反馈率 ≥ 15%;👎 样本已加入 Golden Set |
| 5 | 基础可观测性 | Prometheus 指标:HTTP QPS/P99、RAG 各 stage 耗时、token rate、模型错误率、检索 top1 命中率;Grafana 大盘 + 企业 IM 告警 | 大盘上线;关键告警可触发 |
| 6 | 成本看板 | 管理后台新增「用量与成本」页,按用户/日/模型聚合,估算成本(按模型单价),软预算预警 | OPS 部门管理员可见本部门月度用量 |
| 7 | Last Admin 硬保护 | `AdminInvariantGuard` 在 `UserService` / `RoleService` 入口统一 pre-check;覆盖删用户/改角色/停用/解绑 | 删最后一个 SUPER_ADMIN 返 `E_LAST_ADMIN`,事务回滚 |
| 8 | 权限缓存失效面补全 | 统一发 `AccessCacheInvalidateEvent`,监听清 `kb_access:*` 和 `kb_security_level:*`;失效面覆盖成本高的子分支按 SUPER_ADMIN / DEPT_ADMIN 直接 bypass cache | 权限变更后下一次请求 < 1s 内生效 |
| 9 | 意图树 RBAC 最低改造(吸收 [intent-tree-rbac-multitenancy.md](./intent-tree-rbac-multitenancy.md) 最小面) | ① 建立 Global 池(`kb_id IS NULL`):SYSTEM / 通用 MCP 意图归入全局,所有登录用户可见<br>② 加载层按 kbId 过滤:`DefaultIntentClassifier.loadIntentTreeData(kbId)` SQL 改为 `WHERE kb_id IS NULL OR kb_id = :currentKb`<br>③ 缓存 key 拆分:`ragent:intent:tree:global` + `ragent:intent:tree:kb:{kbId}`;节点跨池迁移时同时失效新旧 key<br>④ 接口签名透传:`IntentClassifier.classifyTargets(q, kbId)`;`IntentResolver.resolve(..., kbId)`;`RAGChatServiceImpl.streamChat` 透传已有 `knowledgeBaseId`<br>⑤ 管理写权接入 `checkManageAccess(kbId)`,复用 `KbAccessService`,不引入新权限概念 | 用户在 KB_A 空间发问时,LLM 看不到 KB_B 意图;跨 KB 改意图走管理写权拒绝;**模板库(`t_intent_template`)本期不做,进暂缓** |
| 10 | 两层 Rerank(粗排 + 精排) | `RerankPostProcessor` 拆成两个 postprocessor:<br>① **`CoarseRerankPostProcessor`**(order=9):bi-encoder 对 Hybrid 合并后 top-100 重打分,输出 top-20。选型:本地部署 **BGE-base-zh** 或复用现有 embedding model 做重打分(零成本)<br>② **`FineRerankPostProcessor`**(order=10):保留现有 `qwen3-rerank` cross-encoder,输入 top-20,输出 top-6<br>配置:`rag.rerank.coarse.enabled` / `rag.rerank.coarse.topIn=100` / `rag.rerank.coarse.topOut=20` | Golden Set nDCG@5 相对单层 rerank ≥ +8%;P99 延迟相对单层增加 < 30% |

---

## 三、本期选做 / PoC(有余力再做,不阻塞 P0)

| # | 项目 | 说明 |
|---|---|---|
| 11 | Parent-Child Chunking | 小块(~256 token)做检索,父段(~1k token)做上下文;与 docling 表格/版式结构联动 |
| 12 | 问答来源展示(前端) | 消息气泡附 "参考来源" pill,可展开查看 chunk 详情;与审计日志共用 `docId / chunkId` 字段 |
| 13 | MCP OPS 工具(1~2 个) | 基于已有 `MCPToolExecutor` / `DefaultMCPToolRegistry`,给 OPS 写 1~2 个真实工具(候选:工单查询、告警查询、配置查询);意图归属按 #9 策略决定(默认 Global,特殊工具 KB 绑定) |
| 14 | **知识图谱并行检索 PoC(不上线)** | 手工抽 OPS 核心实体 20~50 个(产品/模块/工单类型/常见故障)构小图,存 PG 宽表或 JSON 即可,**不引入图数据库**;新增 `KnowledgeGraphSearchChannel` 实验通道;融合分数后对比 Golden Set 召回。**仅评测,不进主路径;图库选型/自动抽取/生产融合本期不做** |

---

## 四、本期暂缓(进入下一阶段 backlog)

明确列出暂缓项和触发时机,避免重复讨论。

| 项目 | 为何暂缓 | 建议触发时机 |
|---|---|---|
| Flyway / Liquibase 接管 schema 迁移 | 单环境单部门风险可控;两份 schema 手工维护可以撑过试点期 | 扩展到 2+ 部门 或 2+ 环境时 |
| OpenTelemetry 跨进程 trace | 当前单进程,应用内 `RagTraceContext` 够用 | MCP 工具链 / 异步消费链路复杂时 |
| 多租户(tenant_id 全链路改造) | 单部门试点不需要;涉及所有 `t_*` 表 + 向量索引命名 | 跨部门扩展 或 私有化第 2 家客户时 |
| SSE 集群化 / 限流集群化 | 单机部署够用 | 需要水平扩展 / 一台 JVM 扛不住时 |
| SSO(OAuth2 / SAML / LDAP) | 本地 Sa-Token 账号够 | 对接企业 AD / 集团统一身份时 |
| MCP 工具沙箱 + 工具调用审计 | 当前 MCP 工具数量少、可控 | 引入外部 / 用户自定义工具时 |
| A/B 测试框架 | 试点期不做线上实验 | 正式运营阶段换 prompt / 换模型时 |
| DDD 重建 / 六边形架构 | 重构成本高,当前分层架构撑得住 | 业务模型复杂度跃升 / 多团队并行开发时 |
| PII 自动脱敏 + 输出红线过滤 | OPS 内部数据不对外 | 对外暴露 或 2C 场景时 |
| HyDE / Query Expansion | 先看 hybrid 效果 | hybrid 收益不足时 |
| Embedding 模型升级迁移方案(dual-write / reindex) | 本期不换 embedding | 计划换 embedding 模型时 **必做**,否则存量向量废 |
| RAGAS 在线评测接入 | 离线跑够用 | 需要持续监控答案质量回归时 |
| 数据备份与灾备(PG + OpenSearch snapshot) | 试点数据可接受丢 | 存入正式业务数据时 |
| 非 OpenSearch 后端 security_level 过滤修复 | **本期不需要** —— 只用 OpenSearch | 若未来重新考虑 Milvus / pgvector 时 **必做**,否则密级穿透 |
| 意图模板库(`t_intent_template` 表 + 全局/部门模板 UI + `apply` 克隆) | 属于意图树 RBAC 的"管理便利层",本期只做最低改造(#9) | 第二个部门接入 / 需要跨 KB 复用意图骨架时 |
| 跨 KB 虚拟视图 / `currentKbId == null` 聚合池 | 单部门场景下强制先选 KB 即可 | 多部门、跨 KB 搜索需求出现时 |
| 知识图谱生产级接入(图数据库选型 + 实体关系自动抽取 + 主路径融合) | 本期只做 PoC(#14),不确定 ROI | PoC 验证 KG 通道对实体关系类问题召回提升 ≥ 20% 时 |

---

## 五、周度里程碑

| 周次 | 目标 |
|---|---|
| W1 (04-21 ~ 04-27) | docling-java 接入 + Tika 对比评测(小批 50 份);审计日志表 + AOP 管道;Golden Set 收集(**≥300 条**,覆盖事实/关系/数值/故障排查等分类);核对生产/试点 OpenSearch 上的 hybrid pipeline 是否真实注册;**OpenSearch 索引容量规划**(shard 数、refresh_interval、HNSW 参数) |
| W2 (04-28 ~ 05-04) | 全量 docling 解析(分批入库,进度可观测);Hybrid pipeline 自建 + 健康度埋点(#2a);权重/分词器调参(#2b);意图树 RBAC 最低改造(#9);Last Admin 保护 + 权限缓存失效面补齐 |
| W3 (05-05 ~ 05-11) | 两层 Rerank 粗排 + 精排(#10)上线 + 基线对比;低置信度兜底 + 反馈回流;Prometheus / Grafana 接入 + 告警规则 |
| W4 (05-12 ~ 05-18) | 成本看板;OPS 部门灰度 3 天,收集反馈 + 复盘;**有余力**:MCP OPS 工具(#13)、KG PoC(#14)、问答来源展示(#12)、Parent-Child(#11) |

---

## 六、数据规模与容量规划

OPS 语料 ~10 GB / 数千份文档,按平均单份 2~3 MB 估算:

| 维度 | 估算 | 规划 |
|---|---|---|
| 文档数 | 3000~5000 份 | 分批 docling 解析,W1 先跑 50 份小批评测 + W2 全量 |
| 总分块数 | **数百万级**(按 512 token 分块 + 几百 chunks/doc) | OpenSearch 单索引分 2~4 shard,1 replica;`refresh_interval=30s` 做 bulk indexing 时临时调到 `-1` |
| HNSW 参数 | — | `m=16` / `ef_construction=256`;查询 `ef=100`(先保准确率,后续基于 P99 调) |
| 向量维度 | 依当前 embedding model | 需确认 ≠1536 时不要和密级/正在用的 `t_knowledge_vector` 冲突(本期不用 pgvector,但 schema 有引用) |
| 解析耗时 | 取决于 docling 并行度 | 需并发解析(CPU 密集型),W1 内要定并发数和失败重试策略 |
| Golden Set | **≥ 300 条**(而非初版 100 条) | 覆盖事实查询、关系推理、数值计算、故障排查、政策解读等分类;每类 ≥30 条 |
| 审计日志 | 按 OPS 几十人日均查询 1000 条估算 | 约 3 万行/天,百万行/月,当前不分区;接入后观察一个月决定是否按月分区 |

**容量红线**:如 OpenSearch 索引首次写入 > 6 小时,说明 bulk batch / shard / refresh 策略有问题,先回滚配置不要硬扛。

---

## 七、风险与依赖

- **docling-java 的运行时形态**:确认纯 Java 方案还是要拉 Python 侧服务。如是后者,W1 要同步落运维(部署 + 健康检查 + 降级到 Tika 的触发条件)
- **docling 并行解析**:10 GB 语料如单线程解析,W2 全量可能跑不完。W1 要定并发度 + 进度可观测(哪个文档在处理、失败了哪些、ETA)
- **docling 解析失败时的降级策略**:失败率阈值、是否自动切 Tika、切换的可观测性 —— 需在 W1 明确
- **Hybrid pipeline 静默降级风险**:`OpenSearchRetrieverService.init()` 在 pipeline 不存在时仅 `log.debug`,线上可能长期跑在 knn-only 下而无人察觉。W1 需先确认现有生产/试点环境的 pipeline 是否真实注册(`GET _search/pipeline/ragent-hybrid-search-pipeline` 返 200)
- **Hybrid 权重调参**:基于 OPS 真实语料迭代 2~3 轮。W1 末必须拿到 Golden Set(≥300),否则 W2 验收无基线
- **中文 analyzer 选型**:OpenSearch 默认 analyzer 对中文效果差,需验证当前索引 mapping 实际使用的 analyzer,并与 OPS 术语表对齐
- **两层 rerank 的延迟上限**:粗排 top-100 × bi-encoder 推理 + 精排 top-20 × cross-encoder API 往返,P99 不能劣化超 30%;如劣化,先降粗排 topIn 到 50 或关闭粗排保底
- **粗排模型选型**:优先复用现有 embedding model(零成本、零额外依赖),次选本地部署 BGE-base-zh。不引入新的 GPU 依赖 —— 如必须 GPU 才能跑,本条降级为 P1 或改用 RRF 纯分数融合
- **意图树 RBAC 缓存失效面**:节点在 `kb_id` 维度迁移(从全局改 KB 绑定或反之)时,**新旧 key 都要清** + 保险清 `:global`;抽 `IntentCacheEvictor.evict(oldState, newState)` 集中处理,别散在 CRUD 里
- **KG PoC 的产出定义**:PoC 不是"先构图再看";必须先从 Golden Set 里筛出"实体关系类问题"(如"A 故障常伴 B 吗"),明确该子集 baseline,再看 KG 通道带来的提升。没有此对照就是为 PoC 而 PoC
- **审计日志量级**:估算每日 `t_audit_log` 行数,超 100 万/天要上 PG 分区表(按月分区);当前可先不分区,在表设计里预留 `create_time` 索引

---

## 八、既有 follow-up 文档整合

本文件是本期收敛视图。下列既有文档已被吸收或改期:

- `docs/dev/followup/backlog.md` —— 2026-04-14 `/simplify` 积累的短期债,条目按本计划吸收或推迟
- `docs/dev/followup/intent-tree-rbac-multitenancy.md` —— **拆分吸收**:
  - **本期做(必做 #9)**:第二节 "两层意图池模型"、"读写两条权限轴" 最小落地(DB 过滤 + 缓存拆分 + 管理写权)
  - **本期不做 / 进暂缓**:模板库(第四节 "模板数据模型" / 第五节 "apply 操作" / 第六节 "模板权限检查" / 第七节 "跨部门共享模板" / 第八节 "管理后台 UI 结构" 中的模板部分)
- `docs/dev/followup/architecture-backlog.md` —— 待 W1 核对并入本计划或 backlog
- `memory/` 中 `project_source_display_plan.md` → 选做 #12
- `memory/` 中 `feedback_last_admin_protection.md` → 必做 #7
- `memory/` 中 `feedback_cache_invalidation_coverage.md` → 必做 #8
- `memory/` 中 `project_permission_redesign.md` → 企业三层权限重设计,进暂缓表
- `memory/` 中 `project_ddd_rebuild_plan.md` → 暂缓表"DDD 重建"

---

## 九、退出条件(试点期结束如何判断成败)

- **检索质量**:Golden Set(≥300 条)recall@10 ≥ 目标值;nDCG@5 相对单层 rerank 基线 ≥ +8%;OPS 👎 率 < 20%
- **权限正确性**:意图树 RBAC 改造后,跨 KB 意图泄漏用例 0 命中(自动化测试覆盖)
- **稳定性**:P99 响应 < 目标值(含两层 rerank 后);试点期无数据丢失 / 权限越权事故
- **合规**:审计日志完整,可应对一次抽查
- **反馈闭环**:收到 ≥ 100 条用户反馈,其中 ≥ 20 条已回流到 Golden Set
- **成本透明**:部门管理员可看到清晰的月度用量数字
- **PoC 产出**:KG PoC(#14)交付评测报告,明确"是否值得下期做生产级"的判断;本期不强求上线

任一主线未达标 → 延长 2 周修正后再扩量;均达标 → 按 backlog 启动下一阶段(意图模板库 / 多租户 / Flyway / 第二个部门扩展 / KG 生产级接入 视 PoC 结论)。
