# Bootstrap 架构边界改造一页版

## 1. 现状判断

当前顶层模块方向基本健康：

- `framework` 作为基础设施层，没有反向依赖业务域
- `infra-ai` 只依赖 `framework`
- `mcp-server` 基本独立

真正的问题集中在 `bootstrap` 内部：

- 业务域之间大量直接互相引用，形成软循环依赖
- `service` 大面积依赖 `controller.request/vo`
- 通用能力被放进 `rag` 包，导致 `rag` 变成隐性平台层
- RBAC 正在迁移到 port-adapter，但旧新两套抽象并存

## 2. 目标架构图

### 2.1 顶层模块

```text
framework <- infra-ai
framework <- bootstrap -> infra-ai
mcp-server (independent)
frontend -> bootstrap API
```

### 2.2 bootstrap 内部目标分层

```text
controller -> application/service-contract -> domain/service -> dao
controller -> HTTP DTO
service    -> command/query/view/domain model

knowledge \
rag       \
user       > 只依赖 framework + core + shared + security ports
ingestion /
admin    /

shared -> framework + core
core   -> framework + infra-ai
```

### 2.3 强制边界

- `service` 不允许 import `controller`
- 业务域不允许直接 import 其他业务域的 `controller/service/dao`
- 跨域访问必须走 `framework.security.port` 或新的 `bootstrap.shared.*` port
- 文件存储、向量存储、远程抓取、文档来源模型这类通用能力不允许继续放在 `rag` 或 `ingestion`

## 3. 目标状态

改造完成后，我们希望得到这样的结构：

- `controller` 只做协议适配和参数转换
- `service` 只处理业务，不感知 HTTP DTO
- `knowledge/rag/user/ingestion/admin` 之间不再直接穿透内部实现
- RBAC 只有一套对外抽象
- 用架构测试把这些边界固化下来，后续 PR 违反规则时立即失败

## 4. 执行清单

### 阶段 1：先加护栏

- 在 `bootstrap` 加 ArchUnit 测试
- 先落 2 条硬规则：
  - `service -> controller` 禁止
  - 关键跨域直连禁止
- 先允许少量临时白名单，只为后续迁移留过渡

完成标志：

- 能用测试稳定复现当前违规点
- 后续每做一轮重构，都能看到违规数量下降

### 阶段 2：先拿 knowledge 做样板

- 抽出中性共享能力：
  - `FileStoragePort`
  - `VectorStorePort`
  - `VectorStoreAdminPort`
  - `StoredFile`
  - `VectorSpaceId/Spec`
- `knowledge` 的 service 契约改成应用层 command/query/view
- controller 负责把 HTTP DTO 转成应用层模型

完成标志：

- `knowledge` 不再依赖 `rag` 下的共享能力类型
- `KnowledgeBaseService` / `KnowledgeDocumentService` 不再直接吃 controller DTO

### 阶段 3：收敛 RBAC

- 用细粒度 security port 替代旧 `KbAccessService` 直连
- 去掉 `user` service 对其他域 controller 类型的依赖
- 所有 KB 元数据读取统一走 `KbMetadataReader`

完成标志：

- 新代码只使用 `CurrentUserProbe / KbReadAccessPort / KbManageAccessPort` 等细粒度 port
- `KbAccessService` 只保留兼容外壳，或在最后一轮删除

### 阶段 4：清理 rag / ingestion 归属漂移

- 把 `DocumentSource` 抽成中性模型
- 把 `IntentTreeService` 从 `ingestion` 收回到 `rag`
- 把 `HttpClientHelper` 这类通用远程访问能力移到 `shared/http`

完成标志：

- `ingestion` 不再依赖 `rag.controller.*`
- `knowledge` 不再依赖 `ingestion.util.*`
- 概念归属和包位置一致

### 阶段 5：删兼容层，收紧规则

- 删除旧 wrapper 或把它们冻结为纯适配层
- 去掉测试白名单
- 补一份正式边界文档到 `docs/dev/design/`

完成标志：

- 架构测试变成 deny-by-default
- 任何新增跨域直连都会在测试阶段失败

## 5. 推荐落地顺序

建议按 5 个 PR 推进：

1. `test: add bootstrap architecture boundary rules`
2. `refactor: extract shared ports and decouple knowledge contracts`
3. `refactor: converge rbac on security ports`
4. `refactor: normalize rag and ingestion ownership`
5. `refactor: enforce bootstrap dependency boundaries`

## 6. 风险控制

- 不做大爆炸式模块拆分，先在 `bootstrap` 内部建立硬边界
- 先迁移共享能力和契约，再迁移业务调用点
- 每一阶段都配套小范围回归测试
- 只有在包级边界稳定后，再考虑把 `shared`、`knowledge`、`rag`、`user` 继续拆成 Maven 子模块

## 7. 一句话结论

这次改造不是“重写系统”，而是把现在靠约定维持的软分层，逐步变成能被编译期和测试期强制执行的硬边界。
