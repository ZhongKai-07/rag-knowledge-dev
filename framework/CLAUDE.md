# framework 模块

framework 是项目的跨切面基础设施模块，提供所有业务域共享的工具类、抽象基类和自动配置。**不包含任何业务逻辑**，只暴露通用能力。

## 构建

```bash
mvn -pl framework install -DskipTests
```

## 包结构与职责

```
com.nageoffer.ai.ragent.framework/
├── cache/           ← Redis key 序列化
├── config/          ← Spring Boot 自动配置（DB、RocketMQ、Web）
├── context/         ← 用户上下文（ThreadLocal）
├── convention/      ← 统一数据结构（Result、ChatRequest、ChatMessage）
├── database/        ← MyBatis Plus 元数据自动填充
├── distributedid/   ← Snowflake ID 生成
├── errorcode/       ← 错误码接口与基础实现
├── exception/       ← 三层异常体系
├── idempotent/      ← 幂等性注解与切面（提交 + 消费）
├── mq/              ← 消息包装器
├── security/port/   ← 权限端口：**7 个 Port**（KbReadAccessPort / KbManageAccessPort / KbMetadataReader / CurrentUserProbe / UserAdminGuard / SuperAdminInvariantGuard / KbAccessCacheAdmin）+ **2 个 Sealed 支持类型**（AccessScope / SuperAdminMutationIntent）。业务代码只注入 7 个 Port，Sealed 类型做参数或返回值
├── trace/           ← 链路追踪上下文（TransmittableThreadLocal）
└── web/             ← 全局异常处理、Result 工厂、SSE 工具
```

## 关键类

### 统一数据结构（convention/）

| 类 | 说明 |
|----|------|
| `Result<T>` | 所有 API 响应的包装类，含 `code`、`data`、`message` |
| `ChatRequest` | 调用大模型的统一请求对象 |
| `ChatMessage` | 聊天消息对象（role + content + thinkingContent + thinkingDuration） |
| `RetrievedChunk` | 检索结果分块对象 |

### 异常体系（exception/）

三层结构，均继承 `AbstractException`：

| 类 | HTTP 语义 |
|----|-----------|
| `ClientException` | 客户端错误（4xx），如参数非法 |
| `ServiceException` | 服务端错误（5xx），如内部逻辑异常 |
| `RemoteException` | 远程依赖异常，如调用外部 AI 服务失败 |

抛出异常时传入 `IErrorCode`（来自 `BaseErrorCode`），由 `GlobalExceptionHandler` 统一捕获转为 `Result`。

### 用户上下文（context/）

| 类 | 说明 |
|----|------|
| `UserContext` | 基于 ThreadLocal 存储当前登录用户信息 |
| `LoginUser` | 携带 userId、username、`deptId`、`roleTypes`（`Set<RoleType>`）、`maxSecurityLevel`（`int`） |
| `RoleType` | 角色类型枚举：`SUPER_ADMIN / DEPT_ADMIN / USER` |
| `Permission` | 知识库权限级别枚举：`READ / WRITE / MANAGE`（`ordinal()` 反映权限大小，可用于 `>=` 比较） |
| `ApplicationContextHolder` | 静态持有 Spring `ApplicationContext`，供非 Bean 代码获取 Bean |

在 Controller 层由 Sa-Token 拦截器填充，业务层直接调用 `UserContext.getUserId()` 等方法取值。

### 链路追踪（trace/）

| 类 | 说明 |
|----|------|
| `RagTraceContext` | 持有 `traceId`、`taskId`、节点栈、评估采集器，全部用 `TransmittableThreadLocal` 实现（可跨线程传递） |
| `RagTraceRoot` | 追踪根节点 |
| `RagTraceNode` | 单个追踪节点（含耗时、输入输出） |

**重要**：使用 TTL（transmittable-thread-local）而非普通 ThreadLocal，因为 RAG 流式处理中会有线程池切换。`ChatRateLimitAspect.finally` 会提前清空上下文——在需要上下文的回调构造器中提前捕获值。

### 幂等性（idempotent/）

| 注解 | 用途 |
|------|------|
| `@IdempotentSubmit` | 防重复提交（基于 Redis，Token 模式） |
| `@IdempotentConsume` | RocketMQ 消息幂等消费（基于 Redis） |

Key 通过 SpEL 表达式在注解参数中指定，由 `SpELUtil` 解析。

### 自动配置（config/）

| 类 | 配置内容 |
|----|---------|
| `DataBaseConfiguration` | MyBatis Plus 分页插件、乐观锁插件、逻辑删除 |
| `WebAutoConfiguration` | 全局 CORS、SSE 支持、`GlobalExceptionHandler` |
| `RocketMQAutoConfiguration` | RocketMQ 生产者公共配置 |

### Web 工具（web/）

| 类 | 说明 |
|----|------|
| `Results` | `Result` 工厂方法：`Results.success(data)`、`Results.failure(errorCode)` |
| `SseEmitterSender` | 向 `SseEmitter` 发送事件的工具，屏蔽底层序列化细节 |
| `GlobalExceptionHandler` | `@ControllerAdvice` 全局异常转换为标准 `Result` |

### ID 生成（distributedid/）

`CustomIdentifierGenerator` 实现 MyBatis Plus 的 `IdentifierGenerator`，底层用 Snowflake 算法。`@TableId` 不设置 type 时默认调用此生成器。

## Gotchas

- **不要在 framework 里加业务逻辑**：framework 是纯基础设施，不能依赖 bootstrap 中的任何类。
- **TTL vs 普通 ThreadLocal**：凡是需要跨异步线程传递的上下文，必须用 `TransmittableThreadLocal`（已在 RagTraceContext 中使用）。普通 `ThreadLocal` 在线程池中会丢失。
- **`UserContext` 在异步线程中不可用**：Sa-Token 拦截器只在 HTTP 请求线程上设置 UserContext，异步线程（如 RocketMQ 消费者、`@Async` 方法）里无法直接调用，需要在请求线程中提前捕获 userId 传参。
