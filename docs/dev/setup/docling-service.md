# Docling 服务部署

Phase 2.5 Parser Enhancement (PR-DOCINT-1c) 引入独立 Docling Python 服务作为 ENHANCED 解析路径的 primary engine。Tika 仍作为 fallback。

## 启动

```bash
docker compose -f resources/docker/docling.compose.yaml up -d docling
```

首次启动需拉取 `quay.io/ds4sd/docling-serve:v0.5.1` 镜像（约 2-3 GB），耐心等待。

> 镜像没有 `latest` tag。当前固定 `v0.5.1`（2026-05-06 quay.io 最新 release）。升级前应在新分支跑 task 5.6 重新校准 `DoclingConvertResponse` 字段名。

## 健康检查

```bash
curl http://localhost:5001/health
# 期望: {"status":"ok"}
```

## 项目配置

`bootstrap/src/main/resources/application.yaml` 加入：

```yaml
docling:
  service:
    enabled: true
    host: http://localhost:5001
    timeout-ms: 60000
    health-endpoint: /health
    convert-endpoint: /v1alpha/convert/file
```

`enabled=true` 时 `DoclingDocumentParser` 才被注册为 Spring bean，`DocumentParserSelector.buildEnhancedParser()` 自动把 ENHANCED 路径的 primary 从 Tika 切到 Docling，fallback 仍是 Tika。

## 关闭 / 回滚

把 `docling.service.enabled` 设为 `false` 即可：

- 所有 ENHANCED 上传通过 `FallbackParserDecorator.degraded()` 走 Tika（构造期一次性 INFO 日志，每文档 stamp `parse_engine_actual=Tika` + `parse_fallback_reason=primary_unavailable` metadata）
- 前端 UI 不变；文档列表可读 `parse_engine_actual` 显示「⚠️ 增强解析尚未启用，已使用基础解析」
- DB schema / 上传字段 / 流水线节点全部不动

## 故障排查

| 现象 | 检查 |
|------|------|
| 启动失败 `pull access denied` | 检查 Docker 是否登录 / 镜像源可达；可考虑国内镜像代理 |
| `curl /health` 超时 | `docker logs docling` 看是否模型未下载完 |
| 上传后 `parse_fallback_reason=primary_failed` | `docker logs docling` 看具体错误（PDF 损坏 / 格式不支持 / 内存不足） |
| 配置 `enabled=true` 但日志仍显示 degraded | 确认 `DoclingDocumentParser` bean 被注册（`grep DoclingDocumentParser` 启动日志） |

## 已存在 KB 切换到 ENHANCED 的迁移步骤（PR 6 起）

PR 6 引入 9 个 layout metadata 字段写入 OpenSearch + DB。`OpenSearchVectorStoreAdmin`
的 mapping 定义已扩，但 `ensureVectorSpace` 仅 create-if-missing，**不会自动 patch 已有索引**。

要让一个 PR 6 之前创建的 KB 真正使用 layout 索引（而非 `_source` 仅存不索引的半升级状态），按以下步骤：

1. 后端服务可继续运行；目标 KB 须无正在进行的 ingestion
2. `curl -X DELETE http://localhost:9201/<kb-collection-name>`（NO_PROXY 见 gotcha §7）
3. KB 管理页面把所有文档状态改为"待重新 ingest"，或后端管理工具批量触发
4. 等 RocketMQ chunker 队列消费完毕
5. OS `_mapping` 校验：新索引应含 9 个 layout 字段
6. 抽样检索一份带 page 的 chunk，断言 SourceChunk 6 字段非空

**禁止半升级状态**：不要靠 `_source` 的"看起来字段还在"绕过此流程；未声明 mapping 的字段不可被
term/range filter 命中。
