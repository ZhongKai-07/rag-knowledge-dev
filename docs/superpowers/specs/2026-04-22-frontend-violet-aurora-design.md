# 前端设计升级 · Violet Aurora 雾紫极光

> 2026-04-22 · v1 设计稿 · HT KnowledgeBase 前端整体视觉 + 交互 + i18n 重构

## 背景与目标

当前前端（见 `frontend/screenshot-chat.png`）的视觉语言存在几个问题：

1. **"Helpful clean" AI-SaaS 泛风格** —— 蓝色渐变 + 圆角 + "把问题变成清晰答案"大字 + 三建议卡片，与 ChatGPT / Perplexity / Copilot 视觉高度同构，没有 HT 品牌记忆点。
2. **字体选择触发 AI-gen 刻板印象** —— Space Grotesk + DM Sans 是 frontend-design skill 明确点名要避免的组合。
3. **信息层级单调** —— 所有块一律"白底 + 浅灰边 + 柔和阴影"，引用 / 思考 / 源 / 建议视觉上是兄弟关系，缺节奏对比。
4. **没有 signature moment** —— 只有常规 hover / focus / fade-up，没有让用户"记住产品"的瞬间（流式节奏、引用跳转、深度思考展开）。
5. **暗色模式已 dead code** —— `.dark { color-scheme: light }` 只剩兼容空壳，token 完全没配套。
6. **缺多语言支持** —— 所有 UI 文本中文硬编码，而 HT 业务需要简体 / 繁体 (HK) / 英文三语覆盖。

**本次设计目标**：

1. 确立 **Violet Aurora · 雾紫极光** 作为全站视觉语言，替换当前蓝-灰-白体系。
2. 重构信息架构：合并顶栏、重做 Sidebar 与 Logo，让聊天主区回归"一张纸"的阅读体验。
3. 引入 i18n（zh-CN / zh-HK / en）覆盖 UI chrome，知识库内容本身不翻译。
4. 删除暗色模式残留代码，留作未来独立工程。

---

## Non-Goals（明确排除）

- ❌ 暗色模式（当前 dead code 清理为 tech-debt，不做新暗色变体）。
- ❌ 后端检索内容 / 文档文本的多语言翻译（i18n 只覆盖 UI chrome：菜单、按钮、toast、empty state、占位符）。
- ❌ 移动端自适应重做（保持现有响应式断点，本次只覆盖 ≥1024px 桌面体验）。
- ❌ 图表 / Recharts 主题化（Dashboard KPI 色板单独跟进，与品牌 token 对齐但不在本次范围）。
- ❌ 文档阅读器 / Drawer 视觉升级（Answer Sources PR4 已独立设计）。
- ❌ 自定义主题 / 用户切换视觉风格（单一品牌语言）。
- ❌ 动画库引入（Framer Motion / Lottie）—— 所有 signature moment 用纯 CSS + 已有 Tailwind keyframes 实现。
- ❌ 图标系统替换（继续用 `lucide-react`，只调 size / stroke 与新 token 对齐）。

---

## 设计方向：Violet Aurora

### 1. 色彩系统

**基础层（95% 场景用这三档）**

| token | 值 | 用途 |
|---|---|---|
| `--vio-surface` | `#FCFBF7` | 暖白底色（主区、输入框） |
| `--vio-surface-2` | `#F8F5EC` | 侧边栏、次级面板 |
| `--vio-ink` | `#15141A` | 主墨（深近黑，带微冷紫调） |
| `--vio-line` | `#EAE6DB` | 暖米分隔线 |

**雾紫主色族（按语义渐浅）**

| token | 值 | 用途 |
|---|---|---|
| `--vio-accent` | `#5B4BE8` | 主按钮、链接、主 citation 文本色 |
| `--vio-accent-2` | `#8B7FEF` | 选中、tag、label、lighter CTA |
| `--vio-accent-subtle` | `#D4CEF5` | hover、focus ring、高亮标记 |
| `--vio-accent-mist` | `#F3F0FB` | 雾底（选中 sidebar 项、浅卡片） |

**极光渐变（signature-only，见 §4）**

```css
--vio-aurora: linear-gradient(
  110deg,
  #9DC3FF 0%,     /* sky */
  #B39BF5 35%,    /* lilac */
  #D4AEF5 60%,    /* orchid */
  #FFB8D4 85%,    /* blush */
  #FFD4B3 100%    /* peach */
);
```

五色都降饱和 + 以 `filter: blur(20-30px)` 呈现"雾感"。

**状态色**（保留语义，与品牌协调）

| token | 值 |
|---|---|
| `--vio-success` | `#4C9F7A` |
| `--vio-warning` | `#D9A14E` |
| `--vio-danger` | `#D55757` |

### 2. 字体系统

| 层级 | 西文 | 简中 (zh-CN) | 繁中 HK (zh-HK) | 用途 |
|---|---|---|---|---|
| Display | `Fraunces` | `Noto Serif CJK SC` | `Noto Serif CJK HK` | Hero 标题、回答大标题 |
| Body | `Inter Tight` | `Noto Sans SC` | `Noto Sans HK` | 所有正文、UI 控件 |
| Label / Meta | `JetBrains Mono` | 同（JetBrains Mono 字体内置 CJK fallback 到系统等宽） | 同 | 小字 label、期号、代码、citation `[^n]`、时间戳 |

**加载策略**：

- Google Fonts 通过 `<link rel="preconnect">` + `font-display: swap` 加载。
- Hero display 字体（`Fraunces 400/500` + 当前 locale 的 CJK Serif regular）走 `<link rel="preload" as="font">`。
- Body / Label 字体常规 `@import`，允许 FOIT 降级到 serif/sans system fallback。
- Fraunces 只加载 wght 400-500、opsz 14-96 的子集，避免整档加载（~180KB）。

**Fallback 链**（每 locale 都要）：

```css
/* 简中场景 */
font-family: 'Fraunces', 'Noto Serif CJK SC', 'Songti SC', Georgia, serif;
/* 繁中 HK */
font-family: 'Fraunces', 'Noto Serif CJK HK', 'Songti HK', Georgia, serif;
/* 英文 */
font-family: 'Fraunces', Georgia, serif;
```

### 3. 版式节奏（沿用"编辑磁刊"骨架）

- **期号 / kicker**：Display serif 10px letter-spacing 3px uppercase（英文），CJK 场景用小字 serif + 字距 4px（`№ 042 · 知识坊`）。
- **Hero 标题**：Display serif 48-56px，weight 400，letter-spacing -0.03em，line-height 1.0。CJK 下保留相同字号但 line-height 1.12。
- **正文段落**：Body sans 14px / line-height 1.7（CJK 略松到 1.75）。
- **引用块**：左侧 3px 实色紫边，斜体 Display serif italic 13px（英文场景才斜体；CJK serif 不走斜体）。
- **分隔线**：发丝线 1px `--vio-line`，非全宽而是限在"段落栏宽"内。

### 4. "一道光"约束（Gradient Budget）

极光渐变（`--vio-aurora` + 其派生 blur halo）**只允许在 6 个 signature moment 出现**。违反此约束的 PR 必须在 code review 时被退回。

| # | 位置 | 实现方式 |
|---|---|---|
| 1 | Hero "清晰的答案" 4 字 | `background-clip: text` |
| 2 | 欢迎屏角落两团 halo | `radial-gradient` + `filter: blur(30px)` + opacity 0.25-0.35 |
| 3 | 输入框 focus 外发光 | `box-shadow` 外加 2px blur halo，用 gradient 的中段 lilac 色 |
| 4 | 主 Citation Badge `[^1]` 底 | gradient 圆底 + `box-shadow: 0 0 0 2px #fff, 0 2px 6px rgba(139,127,239,0.35)`；其余 `[^n]` 用实色紫 `--vio-accent-subtle` 底 |
| 5 | Sidebar Logo HT monogram | 唯一一处 gradient 实心填充（不 blur） |
| 6 | "新建对话"卡右上角光晕 + 流式生成 cursor trail | radial halo + CSS animated pseudo-element |

其余 **95% 场景**：只用实色紫族 + 暖白 + 深墨。

### 5. 阴影层级

放弃当前的三层 `--shadow-xs/sm/md/lg/xl`，简化为 2 档：

| token | 值 | 用途 |
|---|---|---|
| `--vio-shadow-paper` | `0 1px 0 #EAE6DB` | 卡片"纸张堆叠"的发丝阴影 |
| `--vio-shadow-halo` | `0 12px 40px -12px rgba(139,127,239,0.25)` | 浮层、模态、输入 focus |

### 6. 圆角

统一为 2 档：`12px`（控件、卡片）+ `16px`（大面板、模态外框）。放弃当前 6/12/16/20/full 五档，更符合编辑磁刊"纸张感"（纸张角不会太圆）。

---

## 结构变化

### 1. 取消顶栏 Header

当前 `MainLayout` 里顶部 60px Header 展示页面标题 / 面包屑，与 Sidebar 构成"双重 chrome"。

**重构后**：

- 聊天页：完全无 Header，会话名直接在会话列表选中项高亮展示；主区整个是"一张纸"。
- 管理后台：保留极简面包屑，但改为页面内联（第一个内容块的 kicker 位置），不占全宽 bar。

### 2. Sidebar 重做

- 底色 `--vio-surface-2`（暖米），与主区 `--vio-surface`（暖白）形成层级。
- Logo：HT monogram（Fraunces italic "HT"）+ gradient 圆底。
- "新建对话"卡：角落 halo，hover 时扩散（唯一 Sidebar signature moment）。
- 会话列表：`TODAY` / `YESTERDAY` 分组用 JetBrains Mono 小字 uppercase。
- 选中项：`--vio-accent-mist` 底 + 2px 实色紫左边条。

### 3. ChatPage 欢迎屏

- 上下两团 halo（角落 radial blur）。
- Hero 大标题三行（kicker / 大字 / subtitle）。
- 输入框 focus 时 aurora 外发光。
- 建议 chips 放在输入框下方（与当前位置相同），但改为圆角 999 + 暖白底 + 细边。

### 4. 管理后台 AdminLayout

- 继承 Sidebar 语言但列表项密度更高（40px → 32px 行高）。
- 表格（`@tanstack/react-table` + `components/ui/table.tsx`）：头部 JetBrains Mono uppercase、偶数行浅紫雾底 `--vio-accent-mist`（不是灰）、hover 行紫 subtle。

---

## 多语言适配（i18n）

### 范围

**覆盖**：所有 UI chrome 文本 —— 菜单、按钮、toast、empty state、占位符、表单 label、错误信息、tooltip。

**不覆盖**：
- 后端返回的检索内容、文档正文、chunk 文本（保持原文语言）。
- 用户自建知识库的名称、描述（用户输入什么存什么）。
- LLM 生成的答案正文（由上游 prompt + 用户问题语言决定）。
- 日志 / 管理后台的技术字段（`kbId`、`traceId` 等英文标识符）。

### 技术方案

- 库：**`react-i18next` + `i18next-browser-languagedetector`**（社区成熟、与 React 18 + Vite 兼容、与现有 Zustand 无冲突）。
- 资源组织：`frontend/src/locales/{zh-CN,zh-HK,en}/{common,chat,admin,access,errors}.json` —— 按页面域分拆，懒加载。
- 命名空间：`common` / `chat` / `admin` / `access` / `errors` 五个，与 bootstrap 模块划分对齐。
- 变量 / 复数：`{{count}}` + i18next plural rules（zh-CN / zh-HK 只走 `other`，en 有 `one/other`）。
- 字符串 key 命名：`domain.scope.purpose`，例 `chat.input.placeholder`、`access.role.delete.confirm_title`。

### 语言切换

- 入口：Sidebar 底部，头像菜单内（与"退出登录"同层），下拉选"语言 / Language"子菜单。
- 持久化：`localStorage.htkb.locale`，优先级：`user.preferredLocale`（未来后端支持时） > localStorage > 浏览器 `navigator.language` 匹配 > 默认 `zh-CN`。
- 切换时：无需 reload，i18next 运行时切语言；CJK/西文字体通过 `:lang()` 选择器自动切对应 fallback 链。

### 字体与 locale 联动

```css
html:lang(zh-CN) { --font-display: 'Fraunces', 'Noto Serif CJK SC', 'Songti SC', Georgia, serif; }
html:lang(zh-HK) { --font-display: 'Fraunces', 'Noto Serif CJK HK', 'Songti HK', Georgia, serif; }
html:lang(en)    { --font-display: 'Fraunces', Georgia, serif; }
```

i18next 切语言时同步 `document.documentElement.lang` 属性，CSS 自动重算。

### 繁简差异细节

- zh-HK 使用**港字标准**而非通用繁体 / 台湾繁体（字形如"裏→裡"、"爲→為"）。翻译底稿基于简体，但必须由母语审校人员校对，不是机器转码。
- 常见业务术语术语表必须显式维护：例"知识库 / 知識庫（HK） / Knowledge Base"，避免逐 PR 漂移。术语表放在 `docs/dev/i18n/glossary.md`（本次创建）。

### 文本溢出保护

- 所有按钮 / tag / menu item 在 CSS 层加 `max-width` + `text-overflow: ellipsis` 兜底。
- 三语中文本长度比：zh-CN ≈ 1.0 / zh-HK ≈ 1.05 / en ≈ 1.6-1.8；设计 mockup 时按 en 长度预留布局空间。

---

## 分阶段交付

每个 Phase 可独立 review / 合并 / 回滚。

| Phase | 内容 | 验收 |
|---|---|---|
| **P0 · Tokens** | `globals.css` 新 token、`tailwind.config` 扩展、删除 `.dark` 空壳、Fraunces/Inter Tight/JetBrains Mono 加载 | 新 token 可用，旧 token 暂保留双写；页面视觉不变 |
| **P1 · ChatPage** | 欢迎屏 + 对话气泡 + citation badge 升级 | 6 个 signature moment 中 5 个落地（#1-4, #6） |
| **P2 · Sidebar** | Logo + 新建对话卡 + 会话列表；取消 Header | signature moment #5 落地；聊天页无顶栏 |
| **P3 · Spaces + Admin** | SpacesPage、AdminLayout、Dashboard 换装 | 管理后台继承雾紫 token；表格层级清晰 |
| **P4 · i18n 骨架** | `react-i18next` 接入；locale 切换 UI；zh-CN 提取为 key（保持内容不变） | zh-CN 可用；切到 en/zh-HK 暂时 fallback 回 zh-CN |
| **P5 · 翻译内容** | en 全量翻译、zh-HK 全量翻译（母语审校）；字体 locale 联动 | 三语切换无乱码、无溢出；视觉对齐 |
| **P6 · 长尾管理后台** | 剩余 access / intent-tree / traces / evaluations / ingestion 页面 | 全站视觉统一 |
| **P7 · 清理** | 删除 P0 双写的旧 token；`.dark` 空壳、`--glow` / `gradient-primary` 等未用变量 | `grep` 不再出现旧 token 引用 |

**预计总工作量**：6-8 个工作日（P0-P2 ~ 3 天 / P3 ~ 1 天 / P4-P5 ~ 2-3 天 / P6-P7 ~ 1-2 天），可并行。

---

## 验收标准

### 视觉

- [ ] 聊天欢迎屏与 `.superpowers/brainstorm/.../violet-aurora.html` 原型的 hero 部分人眼比对一致（截图 diff）。
- [ ] 全站 `grep` 确认 `from-indigo-*` / `to-blue-*` / `bg-primary` / `text-brand` 等旧蓝色 token 不再被业务组件引用（UI 原子除外）。
- [ ] `Space Grotesk` 从 `tailwind.config.cjs` 和 `globals.css` 中完全移除。
- [ ] 6 个 signature moment 全部落地；额外位置无 gradient。

### 交互

- [ ] 输入框 focus 有 aurora halo（仅首帧，不持续动画）。
- [ ] Citation badge `[1]` 有 gradient；`[2]` `[3]` 为实色紫。
- [ ] 流式生成 cursor 末尾有 trail 效果。

### i18n

- [ ] 切换 zh-CN / zh-HK / en 无乱码、无 key 字符串（如 `chat.input.placeholder`）泄漏到界面。
- [ ] 所有按钮 / menu / tag 在 en 下不溢出、不换行破版（ChatPage、Spaces、AdminLayout 三处必测）。
- [ ] `document.documentElement.lang` 切换正确，字体 fallback 链自动切换。
- [ ] HK 繁体母语审校人员签字确认。

### 性能

- [ ] Lighthouse FCP 相比 main 基线退化 < 200ms。
- [ ] 字体加载 `font-display: swap` 生效（DevTools Network 验证）。
- [ ] 首屏不加载所有 locale 资源（懒加载，DevTools Network 验证）。

### 可访问性

- [ ] Gradient 文字必须有实色 `color` fallback（打印 / 高对比模式下仍可读）。
- [ ] 所有紫色文字与背景对比度 ≥ WCAG AA（4.5:1 body / 3:1 large text）。
- [ ] 语言切换器有 `aria-label` 和键盘导航支持。

---

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| Fraunces + Noto Serif CJK 双字体加载（首屏 ~350KB）影响 FCP | preload 仅 display weight；body 字体允许 FOIT；子集化 Fraunces 只保留 latin-ext |
| "一道光"被后续 PR 滥用，两个月后退化为彩虹 Canva 风 | 在 `CLAUDE.md` / `frontend/CLAUDE.md` 写入 gradient budget；code review checklist 加"新增 gradient 位置必须在 spec §4 白名单内，否则 reject" |
| zh-HK 翻译没人审校，发布后港同事吐槽 | 默认保持 `zh-HK` fallback 到 `zh-CN`，上线 P5 前必须有签字确认 |
| 管理后台数据密度上来后"暖色纸感"显得过软 | 表格 / dense list 用专门的 `--vio-surface` pure 白 + `--vio-line` 更深一档（P3 细化） |
| dead `.dark` 空壳删除破坏下游组件（如果有意外引用 `dark:*` 类） | P0 开始前先 `grep -r "\bdark:"` 全局扫，列出清单，逐个改成条件类或删除 |
| Fraunces 在 Windows Chrome 的 GDI 渲染下锯齿（老 bug） | `font-feature-settings: "kern", "liga"` + `-webkit-font-smoothing: antialiased` 全局启用 |

---

## 参考

- 视觉原型：`.superpowers/brainstorm/6944-1776852927/content/violet-aurora.html`
- 气质对比原型：`.superpowers/brainstorm/6944-1776852927/content/bright-refined.html`
- 当前截图：`frontend/screenshot-chat.png`
- 当前 tokens：`frontend/src/styles/globals.css` / `frontend/tailwind.config.cjs`
- 关联 gotcha：`docs/dev/gotchas.md`（新增 gradient budget 条目 in P7）
