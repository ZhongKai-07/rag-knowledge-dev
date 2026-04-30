# 厂商企业版条款摘录与原文链接

> 截至 2026-04-29

## 1. GitHub Copilot Enterprise

### 1.1 数据训练承诺

Copilot Business 和 Copilot Enterprise 用户的交互数据**不用于**模型训练，协议明确禁止将 Business/Enterprise 客户的 Copilot 交互数据用于训练。即便用户账户同时是付费组织的成员或外部协作者，其交互数据也被排除在训练范围之外。

注意：2026-04-24 起，Free/Pro/Pro+ 用户的交互数据默认用于训练，需主动 opt-out；Business/Enterprise 不受此变更影响。

来源：https://github.blog/news-insights/company-news/updates-to-github-copilot-interaction-data-usage-policy/

### 1.2 数据留存

Business/Enterprise 提供代码保留控制（Code Retention Controls）。GitHub Enterprise Cloud 支持地理数据驻留（Geographic Data Residency），可将数据限定在特定地理区域内。具体可选驻留区域（如欧洲、美国等）未在本次调研中明确获取，属于信息缺口（见第 4 节，缺口 #6）。

### 1.3 审计 / 日志

Business/Enterprise 集成 GitHub 现有 Audit Log 基础设施。Enterprise 管理员可访问合规报告。

### 1.4 加密 / 密钥（CMEK）

**未在公开材料中明确说明。** GitHub Copilot Enterprise 是否支持客户管理加密密钥（CMEK）无法从现有官方文档中确认。该项为信息缺口 #2（见第 4 节）。

### 1.5 合规认证

已覆盖范围（截至 2026-04-29）：
- SOC 2 Type I 报告
- ISO/IEC 27001:2013 认证

上述认证已明确覆盖 Copilot Business 和 Copilot Enterprise。

来源：https://github.blog/changelog/2024-06-03-github-copilot-compliance-soc-2-type-1-report-and-iso-iec-270012013-certification-scope/

HIPAA：未在公开材料中明确说明是否具备单独 BAA（Business Associate Agreement）。该项为信息缺口 #1（见第 4 节）。

### 1.6 大陆访问

中国大陆（不含香港）**不在官方支持区域列表**。实际访问需通过 VPN/代理，存在账号被封禁风险（来源：GitHub Community Discussion #134149）。官方无中国区域节点，不可直接访问。

---

## 2. Gemini Code Assist (Standard / Enterprise)

### 2.1 数据训练承诺

Standard 和 Enterprise 的提示词和响应内容**不用于**训练 Gemini Code Assist 模型。官方表述为："Google 不会在未经许可的情况下使用客户数据训练 AI/ML 模型"。

来源：https://developers.google.com/gemini-code-assist/docs/data-governance

### 2.2 数据留存

Gemini Code Assist Standard/Enterprise 是**无状态（stateless）服务**，不在 Google Cloud 中存储用户的提示词和响应内容。传输中数据通过 TLS 加密，静态数据默认加密。

Enterprise 私有代码库（用于代码定制功能）会被安全存储，仅用于提供定制建议功能，不用于模型训练。

来源：https://docs.cloud.google.com/gemini/docs/codeassist/security-privacy-compliance

### 2.3 审计 / 日志

支持 Cloud Logging 桶存储（可选），可记录用户输入和响应。支持管理活动审计日志。

来源：https://docs.cloud.google.com/gemini/docs/codeassist/security-privacy-compliance

### 2.4 加密 / 密钥（CMEK）

**支持客户管理加密密钥（Customer-Managed Encryption Keys，CMEK）配置。**

### 2.5 合规认证

已覆盖范围（截至 2026-04-29）：
- ISO 27001
- ISO 27017
- ISO 27018
- ISO 27701
- SOC 1
- SOC 2
- SOC 3

HIPAA：未在公开材料中明确说明。

### 2.6 大陆访问

Google 服务被防火长城封锁，Gemini Code Assist **无法直接在中国大陆访问**。需使用 VPN 或 API Gateway 代理，官方不提供中国区域支持（来源：https://www.aifreeapi.com/en/posts/how-to-use-gemini-in-china）。

---

## 3. AWS Kiro

> **成熟度警示**：Kiro 于 2025-07-17 发布 Preview，2025-11-17 正式 GA（基于 Code OSS 的独立 IDE）。截至 2026-04-29 仅 GA 约 5 个月，部分企业级功能（如 CMEK、完整合规认证）仍在完善中。

### 3.1 数据训练承诺

**未在公开材料中明确说明。** Kiro 官方网站和公开文档中无明确的零训练承诺声明。

**注意**：本项是 Task 2 调研中识别的信息缺口 #3（见第 4 节）。需由法务 / 合规部门查阅 AWS/Kiro 数据处理协议（DPA）或隐私政策后进一步核对，不得以本报告的缺失作为"无限制使用"的依据。

来源尝试：https://kiro.dev/enterprise/（仅声明"遵循与 AWS 云基础设施相同的安全、治理和加密标准"，无具体训练承诺）

### 3.2 数据留存

未在公开材料中明确说明数据留存政策细节。官方企业版页面仅提及遵循 AWS 安全标准，无具体留存期限或控制机制说明。

来源：https://kiro.dev/enterprise/

### 3.3 审计 / 日志

管理员可通过 AWS Management Console 监控团队使用量和成本。详细审计日志能力（如用户级操作日志、导出格式）未在公开材料中明确说明。

### 3.4 加密 / 密钥（CMEK）

**未在公开材料中明确说明。** 官方文档未明确列出 CMEK 支持状态。

### 3.5 合规认证

**未在公开材料中明确说明。** Kiro 基于 AWS 基础设施，可能继承部分 AWS 合规认证，但官方尚未发布 Kiro 产品级别的认证适用范围声明（如 SOC 2、ISO 27001 等是否覆盖 Kiro）。该项为信息缺口 #4（见第 4 节）。

### 3.6 大陆访问

**无中国大陆区域节点。** Console/Profile 仅支持以下区域：
- US East (N. Virginia)
- Europe (Frankfurt)
- AWS GovCloud (US-East)
- AWS GovCloud (US-West)

IAM Identity Center 支持 19 个区域（含亚太：孟买、大阪、首尔、新加坡、悉尼、东京），但均无中国大陆节点。

来源：https://kiro.dev/docs/enterprise/supported-regions/

### 3.7 GA 状态

- **Preview 开始**：2025-07-17
- **正式 GA**：2025-11-17（基于 Code OSS 的独立 IDE，支持 VS Code 扩展/主题）
- **截至 2026-04-29**：GA 约 5 个月，区域有限，定价 / 企业级能力 / 合规认证仍在演进
- **底层模型**：Claude Sonnet 4.5 + Claude Haiku 4.5（Auto 模式混合使用，通过意图检测和缓存优化成本）

来源：https://kiro.dev/blog/general-availability/，https://kiro.dev/pricing/

---

## 4. 信息缺口（待法务 / 合规核对）

以下 5 条信息缺口源自 Task 2 调研（`_research-notes.md` 第 8 节），**不在本报告评估范围内**，需由法务、合规或 IT 安全部门在采购谈判时进一步核对：

1. **Kiro 数据训练承诺**（缺口 #3）：Kiro 未发布明确的零训练承诺文档。需查阅 AWS/Kiro 数据处理协议（DPA）或隐私政策；建议在采购 Enterprise 计划前向 AWS 销售团队索取书面承诺。

2. **Kiro 合规认证适用范围**（缺口 #4）：Kiro 继承 AWS 基础设施安全，但官方未明确列出 SOC 2/ISO 27001 等认证是否覆盖 Kiro 产品本身。建议查阅 https://kiro.dev/security（如存在）或向 AWS 索取合规报告。

3. **LiveCodeBench 最新排行数据**（缺口 #5）：未能获取 GPT-5、Gemini 3.x、Claude Opus 4.x 在 LiveCodeBench 的具体最新分数。需直接访问 https://livecodebench.github.io/leaderboard.html 补充。

4. **GitHub Copilot HIPAA / BAA 状态**（缺口 #1）：未找到 Copilot Enterprise 的 HIPAA 合规声明或 Business Associate Agreement 可用性信息。需查询 https://copilot.github.trust.page/ 或联系 GitHub Sales 确认。

5. **GitHub Copilot CMEK 支持**（缺口 #2）：Copilot Enterprise 是否支持客户管理加密密钥（CMEK）未在公开材料中明确说明。需在企业级采购谈判中向 GitHub 销售团队直接确认。
