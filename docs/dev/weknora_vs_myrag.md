# RAG 链路对比：WeKnora vs rag-knowledge-dev

---

## 完整链路流程对比

| 阶段 | WeKnora (Go) | rag-knowledge-dev (Java) | 差异说明 |
|:----:|:------------|:------------------------|:--------|
| **前置校验** | — | ✅ RBAC：过滤可访问 KB 列表 | 你的更完善 |
| **历史加载** | ✅ LOAD_HISTORY<br/>配对Q&A轮次，剥离`<think>`标签<br/>图片Caption注入历史 | ✅ 对话记忆<br/>窗口截取 + 摘要压缩 | 你的有摘要压缩，更节省上下文 |
| **查询理解** | ✅ QUERY_UNDERSTAND<br/>改写 + 意图分类 + 图片描述<br/>输出结构化JSON | ✅ 查询改写 + 问题分解<br/>意图树形分类（层次+置信度） | 你的意图分类更精细（树形层次）<br/>WeKnora多图片理解能力 |
| **意图路由** | ⚠️ 简单意图判断<br/>（需不需要检索） | ✅ 歧义引导（SHORT-CIRCUIT）<br/>系统专属意图（SHORT-CIRCUIT）<br/>意图定向检索 | 你的短路机制更完善 |
| **检索阶段** | ✅ **并行三路**<br/>① 向量+BM25 RRF融合<br/>② Web Search<br/>③ Graph/实体搜索 | ✅ **并行两路**<br/>① 意图定向检索<br/>② 全局向量检索<br/>③ MCP工具调用（并行）| 各有侧重：<br/>WeKnora多Web/Graph<br/>你的有MCP工具 |
| **后处理** | ✅ **精细后处理**<br/>① Cross-Encoder精排<br/>② 复合评分(0.6+0.3+0.1)<br/>③ FAQ加权<br/>④ **阈值自动降级**<br/>⑤ **MMR去冗余** | ⚠️ **基础后处理**<br/>① 去重<br/>② Rerank API<br/>③ TopK截断<br/>— 无MMR<br/>— 无降级机制 | ⭐ WeKnora明显领先 |
| **上下文重建** | ✅ **CHUNK_MERGE**<br/>① 父块内容替换<br/>② 区间合并<br/>③ 短块邻居扩展<br/>④ 历史引用注入<br/>⑤ FAQ答案填充 | ❌ 无此阶段<br/>直接使用原始chunk | ⭐ WeKnora明显领先 |
| **TopK截断** | ✅ FILTER_TOP_K | ✅ TopK过滤 | 相同 |
| **表格分析** | ✅ DATA_ANALYSIS<br/>CSV/Excel特殊处理 | — | WeKnora专项支持 |
| **Prompt构建** | ✅ INTO_CHAT_MESSAGE<br/>模板渲染<br/>FAQ/Doc分区显示<br/>图片OCR/Caption注入 | ✅ RAGPromptService<br/>按意图节点分组格式化<br/>来源归属标注 | 各有特色：<br/>WeKnora多模态<br/>你的按意图分组更结构化 |
| **LLM生成** | ✅ 流式SSE<br/>Fallback兜底响应 | ✅ 流式SSE<br/>模型熔断+路由+故障转移 | 你的模型路由更健壮 |
| **生成后** | ✅ 异步存储消息+引用 | ✅ 存记忆+Token统计<br/>+RAGAS评测数据采集 | 你的评测能力更完善 |

---

## 流程示意图

### WeKnora RAG 链路

```mermaid
flowchart TD
    A([用户问题]) --> B[LOAD_HISTORY\n历史加载·剥离think·图片Caption]
    B --> C[QUERY_UNDERSTAND\n查询改写 + 意图分类 + 图片描述\nLLM输出结构化JSON]

    C -->|意图=无需检索| X[意图专属Prompt\n跳过检索]
    X --> K

    C -->|意图=kb_search| D

    D[CHUNK_SEARCH_PARALLEL\n并行检索]
    D --> D1[向量检索 Dense\n+ BM25关键词\n→ RRF融合]
    D --> D2[Web Search\n可选]
    D --> D3[Graph实体检索\n可选·GraphRAG]
    D1 & D2 & D3 --> E

    E[CHUNK_RERANK\n精排阶段]
    E --> E1[Cross-Encoder重排\nPassage清洗·富文本增强]
    E1 --> E2[复合评分\n0.6×model + 0.3×base + 0.1×source]
    E2 --> E3[FAQ Score Boost]
    E3 --> E4{结果为空?}
    E4 -->|是·阈值降级| E1
    E4 -->|否| E5[MMR去冗余\nλ=0.7]

    E5 --> F[CHUNK_MERGE\n上下文重建]
    F --> F1[父块内容替换\nParent-Child]
    F1 --> F2[区间合并\n相邻重叠chunk拼接]
    F2 --> F3[短块邻居扩展]
    F3 --> F4[历史引用注入\n上轮引用chunk重注入]
    F4 --> F5[FAQ答案填充]

    F5 --> G[FILTER_TOP_K]
    G --> H[INTO_CHAT_MESSAGE\n渲染Prompt模板\nFAQ+Doc分区·OCR图片注入]
    H --> K[CHAT_COMPLETION_STREAM\n流式SSE]
    K --> L([异步保存·消息+引用])

    style E4 fill:#f9a,stroke:#f00
    style D1 fill:#bbf,stroke:#33f
    style D2 fill:#bbf,stroke:#33f
    style D3 fill:#bbf,stroke:#33f
    style F1 fill:#bfb,stroke:#0a0
    style F2 fill:#bfb,stroke:#0a0
    style F3 fill:#bfb,stroke:#0a0
    style F4 fill:#bfb,stroke:#0a0
```

---

### rag-knowledge-dev RAG 链路

```mermaid
flowchart TD
    A([用户问题]) --> AA[RBAC校验\n获取可访问KB列表]
    AA --> B[加载对话记忆\n窗口截取 + 摘要压缩]
    B --> C[查询改写 + 问题分解\nSub-question Decomposition]
    C --> D[意图分类\nLLM树形遍历·层次意图·置信度评分]

    D --> DA{歧义检测}
    DA -->|歧义| DAX([引导用户澄清\n⚡SHORT-CIRCUIT])
    DA -->|清晰| DB{系统专属意图?}
    DB -->|是| DBX([纯LLM回答\n⚡SHORT-CIRCUIT跳过检索])
    DB -->|否| E

    E[多路并行检索]
    E --> E1[意图定向检索\n单KB·精准匹配]
    E --> E2[全局向量检索\n所有可访问KB]
    E --> E3[MCP工具调用\nLLM提参·并行执行·容错]
    E1 & E2 & E3 --> F

    F[后处理 串行]
    F --> F1[去重\nby chunk ID]
    F1 --> F2[Rerank\n外部API]
    F2 --> F3[TopK截断]

    F3 --> G[结果合并分组\n按意图节点分组·KB上下文/MCP上下文分离]
    G --> H[Prompt构建\n系统Prompt + 对话历史 + 结构化上下文 + 问题]
    H --> K[LLM流式输出\nSSE·模型熔断+路由+故障转移]
    K --> L[生成后处理\n存记忆 + Token统计 + RAGAS评测数据]

    style DA fill:#f9a,stroke:#f00
    style DB fill:#f9a,stroke:#f00
    style E1 fill:#bbf,stroke:#33f
    style E2 fill:#bbf,stroke:#33f
    style E3 fill:#bbf,stroke:#33f
```

---

## 关键差距可视化

```
检索质量链路对比（后处理阶段）

WeKnora:
Rerank候选 → [Cross-Encoder精排] → [复合评分] → [FAQ加权] → [阈值降级兜底] → [MMR去冗余] → TopK
                                                                      ↑
                                                               无结果自动重试

rag-knowledge-dev:
Rerank候选 → [Rerank API] → TopK
                ↑
           改进空间：加入MMR + 阈值降级


上下文质量链路对比（Merge阶段）

WeKnora:
RerankResult → [父块替换] → [区间合并] → [邻居扩展] → [历史引用注入] → [FAQ填充] → LLM
                  ↑              ↑              ↑
              上下文完整      连续性保证      多轮连贯性

rag-knowledge-dev:
RerankResult → [分组格式化] → LLM
                  ↑
           改进空间：加入区间合并 + 邻居扩展 + 历史引用注入
```

---

## 能力维度评估

| 能力维度 | WeKnora | rag-knowledge-dev | 说明 |
|:-------:|:-------:|:-----------------:|:----|
| 意图理解 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 你的树形层次意图更精细 |
| 上下文重建 | ⭐⭐⭐⭐⭐ | ⭐⭐ | 父块/区间合并/邻居扩展均缺失 |
| Rerank质量 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | 缺MMR + 降级 + 复合评分 |
| 多路检索 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | 各有侧重，基本持平 |
| 模型健壮性 | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 你的熔断+路由更完善 |
| 多模态支持 | ⭐⭐⭐⭐ | ⭐ | 你的系统暂无图片处理 |
| 评测能力 | ⭐⭐ | ⭐⭐⭐⭐⭐ | 你的RAGAS集成更完善 |

---

## 建议迁移优先级

| 优先级 | 特性 | 预计工期 | 收益 |
|:------:|:----|:--------:|:-----|
| 🔴 P0 | Rerank 阈值自动降级 | 0.5天 | 消除空结果问题 |
| 🔴 P0 | MMR 去冗余 | 1天 | 减少上下文冗余，提升回答质量 |
| 🟠 P1 | 历史引用注入 | 1天 | 多轮对话上下文连贯性 |
| 🟠 P1 | 区间合并 + 邻居扩展 | 2天 | 上下文完整性显著提升 |
| 🟡 P2 | 父子块 Chunking | 3-5天 | 需重新入库，收益最大 |
| 🟡 P2 | 复合评分 | 1天 | 引入Web Search后适用 |
| 🟢 P3 | 多模态图片理解 | 5天+ | 有图文知识库需求时引入 |
