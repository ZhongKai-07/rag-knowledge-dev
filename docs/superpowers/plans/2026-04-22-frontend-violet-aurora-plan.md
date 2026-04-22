# Violet Aurora 前端重构 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 Spec `docs/superpowers/specs/2026-04-22-frontend-violet-aurora-design.md` 把 HT KnowledgeBase 前端全量换肤为 Violet Aurora（雾紫极光）视觉语言，同时接入 i18n（zh-CN / zh-HK / en），交付 7 个可独立合并的 Phase。

**Architecture:** 先在 CSS token 层引入新 Violet Aurora 变量并保留旧蓝 token 双写（P0），再按"高信号场景优先"顺序落地视觉（P1 ChatPage → P2 Sidebar → P3 Spaces+Admin），然后独立加 i18n 骨架（P4）与翻译（P5），最后长尾 + 清理（P6-P7）。每个 Phase 都可独立 merge / 回滚。

**Tech Stack:** React 18 + TypeScript + Vite + TailwindCSS + shadcn/ui + Radix + Zustand + Vitest + React Router v6。新增：`react-i18next` + `i18next` + `i18next-browser-languagedetector`。字体：Fraunces + Inter Tight + JetBrains Mono + Noto Serif CJK + Noto Sans CJK（Google Fonts）。

---

## 文件结构总览

### P0 · Tokens
- Modify: `frontend/src/styles/globals.css` — 新 `--vio-*` token + 字体 face + `:lang()` 切换；删除 `.dark {}` 空壳
- Modify: `frontend/tailwind.config.cjs` — 扩展 `vio.*` 色族 + `fontFamily.display/body/mono` + gradient keyframe
- Modify: `frontend/index.html` — `<link rel="preconnect">` + Fraunces preload
- Create: `frontend/src/styles/aurora.css` — gradient 专用 utility 类（与基础 token 隔离，便于未来 lint）

### P1 · ChatPage
- Modify: `frontend/src/components/chat/WelcomeScreen.tsx` — 雾紫 hero、两团 halo、gradient-clip 标题
- Modify: `frontend/src/components/chat/ChatInput.tsx` — focus aurora 外发光
- Modify: `frontend/src/components/chat/CitationBadge.tsx` — badge #1 gradient、其他实色紫
- Modify: `frontend/src/components/chat/MessageItem.tsx` — user/assistant 气泡新样式
- Modify: `frontend/src/components/chat/MarkdownRenderer.tsx` — 引用块 / 标题 / 段落新版式
- Modify: `frontend/src/components/chat/ThinkingIndicator.tsx` — 雾紫胶囊
- Modify: `frontend/src/components/chat/CitationBadge.test.tsx` — 锁定 gradient 应用于 n=1 的契约

### P2 · Sidebar + Header 拆除
- Modify: `frontend/src/components/layout/Sidebar.tsx` — HT monogram logo、新建对话卡 halo、暖米底
- Modify: `frontend/src/components/layout/MainLayout.tsx` — 移除 `<Header>` 挂载、移除 `onToggleSidebar` 链路
- Delete: `frontend/src/components/layout/Header.tsx` — 整文件删除
- Modify: `frontend/src/pages/admin/AdminLayout.tsx` — 面包屑改为页面内 kicker，移除顶栏

### P3 · Spaces + Dashboard
- Modify: `frontend/src/pages/SpacesPage.tsx` — kbs 列表新视觉
- Modify: `frontend/src/pages/admin/AdminLayout.tsx` — 密度调整 + 雾紫选中
- Modify: `frontend/src/pages/admin/dashboard/**.tsx` — KPI 卡 + 趋势图配色
- Modify: `frontend/src/components/admin/SimpleLineChart.tsx` — 图表线色改紫
- Modify: `frontend/src/components/ui/table.tsx` — 表格头部 mono + 偶数行雾底

### P4 · i18n 骨架
- Create: `frontend/src/i18n/index.ts` — i18next init
- Create: `frontend/src/i18n/config.ts` — locale 列表、默认语言、namespace
- Create: `frontend/src/locales/zh-CN/{common,chat,admin,access,errors}.json` — 5 个 namespace
- Create: `frontend/src/locales/zh-HK/{common,chat,admin,access,errors}.json` — 初始 fallback 空对象
- Create: `frontend/src/locales/en/{common,chat,admin,access,errors}.json` — 初始 fallback 空对象
- Create: `frontend/src/components/common/LanguageSwitcher.tsx` — 语言切换下拉
- Create: `frontend/src/components/common/LanguageSwitcher.test.tsx`
- Modify: `frontend/src/main.tsx` — `import './i18n'`
- Modify: `frontend/src/components/layout/Sidebar.tsx` — LanguageSwitcher 挂载到头像菜单
- Create: `docs/dev/i18n/glossary.md` — 术语表

### P5 · 翻译内容 + key 替换
- Modify: `zh-CN` 所有 json — 从 UI 硬编码抽取
- Modify: `en` 所有 json — 全量翻译
- Modify: `zh-HK` 所有 json — 全量翻译（母语审校）
- Modify: 所有业务组件 — 硬编码字符串替换为 `t("namespace:key")`

### P6 · 长尾管理后台
- Modify: `frontend/src/pages/admin/access/**/*.tsx`
- Modify: `frontend/src/pages/admin/intent-tree/**/*.tsx`
- Modify: `frontend/src/pages/admin/traces/**/*.tsx`
- Modify: `frontend/src/pages/admin/evaluations/**/*.tsx`
- Modify: `frontend/src/pages/admin/ingestion/**/*.tsx`
- Modify: `frontend/src/pages/admin/knowledge/**/*.tsx`
- Modify: `frontend/src/pages/admin/settings/**/*.tsx`

### P7 · 清理
- Modify: `frontend/src/styles/globals.css` — 删除旧 shadcn `--primary/--accent`等与 `--brand-*` 双写档
- Modify: `frontend/tailwind.config.cjs` — 删除 Space Grotesk、旧 brand 色族
- Modify: `frontend/CLAUDE.md` — 写入 gradient budget 约束 + 新 token 速查
- Modify: `docs/dev/gotchas.md` — 新增 "Violet Aurora gradient budget" 条目

---

## Phase 0 · Design Token Foundation

**目标**：把 Violet Aurora 所有 token 放进代码，同时保证页面视觉零改变（双写阶段）。后续 Phase 才开始用新 token 渲染。

### Task 0.1 · Tailwind 配置扩展

**Files:**
- Modify: `frontend/tailwind.config.cjs`

- [ ] **Step 1: 在 `theme.extend.colors` 块末尾新增 `vio.*` 色族**

改动在 `tailwind.config.cjs:7-62` 的 `colors` 对象内，在现有 `danger` 块后追加：

```js
        // ── Violet Aurora Tokens (P0) ──────────────────
        vio: {
          surface:   "var(--vio-surface)",
          "surface-2": "var(--vio-surface-2)",
          ink:       "var(--vio-ink)",
          line:      "var(--vio-line)",
          accent:    "var(--vio-accent)",
          "accent-2": "var(--vio-accent-2)",
          "accent-subtle": "var(--vio-accent-subtle)",
          "accent-mist": "var(--vio-accent-mist)",
          success:   "var(--vio-success)",
          warning:   "var(--vio-warning)",
          danger:    "var(--vio-danger)",
        },
```

- [ ] **Step 2: 扩展 `fontFamily`**

替换 `tailwind.config.cjs:63-67` 的 `fontFamily` 块：

```js
      fontFamily: {
        display: ["Fraunces", "Noto Serif CJK SC", "Noto Serif CJK HK", "Songti SC", "Georgia", "serif"],
        body: ["'Inter Tight'", "Noto Sans SC", "Noto Sans HK", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["'JetBrains Mono'", "ui-monospace", "SFMono-Regular", "monospace"],
      },
```

- [ ] **Step 3: 新增 aurora keyframe 与 boxShadow**

在 `tailwind.config.cjs:68-72` 的 `boxShadow` 块追加，并在 `keyframes`/`animation` 追加 `aurora-sweep`：

```js
      boxShadow: {
        soft: "0 24px 60px -30px rgba(10, 10, 15, 0.65)",
        glow: "0 0 0 1px rgba(59, 130, 246, 0.2), 0 16px 40px rgba(59, 130, 246, 0.25)",
        neon: "0 0 30px rgba(59, 130, 246, 0.35)",
        paper: "0 1px 0 var(--vio-line)",
        halo:  "0 12px 40px -12px rgba(139,127,239,0.25)",
      },
```

```js
      keyframes: {
        // ... existing keyframes unchanged ...
        "aurora-sweep": {
          "0%":   { transform: "translateX(-30%)", opacity: 0.0 },
          "40%":  { opacity: 0.6 },
          "100%": { transform: "translateX(130%)", opacity: 0.0 },
        },
      },
      animation: {
        // ... existing animations unchanged ...
        "aurora-sweep": "aurora-sweep 1400ms ease-out 200ms 1",
      },
```

- [ ] **Step 4: Build 验证**

Run: `cd frontend && npm run build`
Expected: 构建成功，dist 目录产出；无 "Unknown theme key" 类报错。

- [ ] **Step 5: Commit**

```bash
git add frontend/tailwind.config.cjs
git commit -m "feat(frontend): add Violet Aurora tokens to Tailwind [P0]"
```

---

### Task 0.2 · globals.css 引入 Violet Aurora CSS 变量

**Files:**
- Modify: `frontend/src/styles/globals.css`

- [ ] **Step 1: 在 `:root {}` 块末尾（`--dsw-specific-sidebar-nav-item-active-accent` 之后，`}` 之前）追加 Violet Aurora 变量**

定位：`frontend/src/styles/globals.css:168` 后（闭合 `}` 前）。

```css
  /* ===== Violet Aurora (P0) ===== */
  --vio-surface:        #FCFBF7;
  --vio-surface-2:      #F8F5EC;
  --vio-ink:            #15141A;
  --vio-line:           #EAE6DB;

  --vio-accent:         #5B4BE8;
  --vio-accent-2:       #8B7FEF;
  --vio-accent-subtle:  #D4CEF5;
  --vio-accent-mist:    #F3F0FB;

  --vio-success:        #4C9F7A;
  --vio-warning:        #D9A14E;
  --vio-danger:         #D55757;

  --vio-aurora: linear-gradient(
    110deg,
    #9DC3FF 0%,
    #B39BF5 35%,
    #D4AEF5 60%,
    #FFB8D4 85%,
    #FFD4B3 100%
  );

  /* locale-aware display font (切 lang 时自动 reassign) */
  --font-display: "Fraunces", "Noto Serif CJK SC", "Songti SC", Georgia, serif;
  --font-body:    "Inter Tight", "Noto Sans SC", ui-sans-serif, system-ui, sans-serif;
  --font-mono:    "JetBrains Mono", ui-monospace, SFMono-Regular, monospace;
```

- [ ] **Step 2: 新增 `:lang()` 选择器切换 display / body fallback 链**

追加到 `:root {}` 结束后（同级）：

```css
html:lang(zh-CN) {
  --font-display: "Fraunces", "Noto Serif CJK SC", "Songti SC", Georgia, serif;
  --font-body:    "Inter Tight", "Noto Sans SC", ui-sans-serif, system-ui, sans-serif;
}
html:lang(zh-HK) {
  --font-display: "Fraunces", "Noto Serif CJK HK", "Songti HK", Georgia, serif;
  --font-body:    "Inter Tight", "Noto Sans HK", ui-sans-serif, system-ui, sans-serif;
}
html:lang(en) {
  --font-display: "Fraunces", Georgia, serif;
  --font-body:    "Inter Tight", ui-sans-serif, system-ui, sans-serif;
}
```

- [ ] **Step 3: 删除 `.dark { color-scheme: light }` 空壳**

定位 `frontend/src/styles/globals.css:171-173` 整块，删除：

```css
.dark {
  color-scheme: light;
}
```

- [ ] **Step 4: 追加 global `font-feature-settings` 防御 Windows 字距锯齿**

在 `body` 样式（`frontend/src/styles/globals.css:185-189`）块内追加两行：

```css
body {
  @apply bg-[#FAFAFA] text-gray-900 font-body antialiased min-h-screen;
  background-color: var(--bg-secondary);
  overflow: hidden;
  font-feature-settings: "kern", "liga";
  -webkit-font-smoothing: antialiased;
}
```

- [ ] **Step 5: 运行 tsc 与 build 验证无破坏**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit
cd frontend && npm run build
```

Expected: 两个命令都成功；`dist/index.html` 已生成。

- [ ] **Step 6: 启动 dev server 目测页面**

```bash
$env:NO_PROXY='localhost,127.0.0.1'; cd frontend && npm run dev
```

打开 http://localhost:5173/login 与 /chat（需登录），目测应与改动前完全一致（旧蓝色 UI 没任何变化，因为还没有组件使用新 token）。

- [ ] **Step 7: Commit**

```bash
git add frontend/src/styles/globals.css
git commit -m "feat(frontend): add Violet Aurora CSS variables + lang-aware fonts; drop dead .dark shell [P0]"
```

---

### Task 0.3 · 字体加载（Google Fonts preconnect + preload）

**Files:**
- Modify: `frontend/index.html`

- [ ] **Step 1: 在 `<head>` 块内，`<title>` 之后追加字体加载**

```html
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link
      rel="preload"
      as="style"
      href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,400;9..144,500;9..144,600&display=swap"
    />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,400;9..144,500;9..144,600&display=swap"
    />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Inter+Tight:wght@400;500;600;700&display=swap"
    />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600&display=swap"
    />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@400;500;600&display=swap"
    />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;600&display=swap"
    />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Noto+Serif+HK:wght@400;500;600&display=swap"
    />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Noto+Sans+HK:wght@400;500;600&display=swap"
    />
```

- [ ] **Step 2: Dev server 启动后目测字体是否加载**

```bash
cd frontend && npm run dev
```

浏览器打开 http://localhost:5173，DevTools → Network → 按 "Font" 过滤，应看到 Fraunces + Inter Tight + JetBrains Mono + Noto Serif/Sans SC + HK 全部加载成功（Status 200）。

- [ ] **Step 3: Commit**

```bash
git add frontend/index.html
git commit -m "feat(frontend): preload Fraunces + Inter Tight + JetBrains Mono + Noto CJK [P0]"
```

---

### Task 0.4 · Aurora gradient utility 类

**Files:**
- Create: `frontend/src/styles/aurora.css`
- Modify: `frontend/src/main.tsx`

- [ ] **Step 1: 创建 `frontend/src/styles/aurora.css`**

```css
/**
 * Violet Aurora · Gradient utility classes
 *
 * ⚠️ 这些类只允许在 spec §4 定义的 6 个 signature moment 使用：
 *   1. Hero "清晰的答案" 标题（.vio-aurora-text）
 *   2. 欢迎屏角落 halo（.vio-aurora-halo）
 *   3. 输入框 focus（.vio-aurora-focus）
 *   4. 主 CitationBadge [^1]（.vio-aurora-chip）
 *   5. Sidebar Logo monogram（.vio-aurora-fill）
 *   6. "新建对话"卡 + 流式 cursor trail（.vio-aurora-halo）
 *
 * 新增使用位置必须在 spec §4 白名单中，否则 code review reject。
 */

.vio-aurora-text {
  background: var(--vio-aurora);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.vio-aurora-fill {
  background: var(--vio-aurora);
  color: #FFFFFF;
}

.vio-aurora-halo {
  position: absolute;
  inset: -40px;
  background: radial-gradient(
    circle at 30% 30%,
    rgba(179,155,245,0.35) 0%,
    rgba(255,184,212,0.25) 35%,
    transparent 65%
  );
  filter: blur(30px);
  pointer-events: none;
  z-index: 0;
}

.vio-aurora-halo-2 {
  position: absolute;
  inset: -40px;
  background: radial-gradient(
    circle at 70% 70%,
    rgba(157,195,255,0.30) 0%,
    rgba(212,174,245,0.22) 40%,
    transparent 70%
  );
  filter: blur(30px);
  pointer-events: none;
  z-index: 0;
}

.vio-aurora-chip {
  background: var(--vio-aurora);
  color: #FFFFFF;
  box-shadow:
    0 0 0 2px var(--vio-surface),
    0 2px 6px rgba(139, 127, 239, 0.35);
}

.vio-aurora-focus:focus-visible {
  outline: none;
  box-shadow:
    0 0 0 2px var(--vio-surface),
    0 0 0 4px var(--vio-accent-subtle),
    0 8px 24px -8px rgba(139, 127, 239, 0.45);
}

/* 流式生成 cursor trail */
.vio-aurora-cursor {
  display: inline-block;
  width: 2px;
  height: 1.1em;
  vertical-align: text-bottom;
  background: var(--vio-aurora);
  animation: blink 1s step-end infinite;
}
```

- [ ] **Step 2: 在 `main.tsx` 里 import aurora.css**

定位 `frontend/src/main.tsx`，在现有 `import "./styles/globals.css"` 之后追加：

```ts
import "./styles/aurora.css";
```

- [ ] **Step 3: Build + dev 验证**

```bash
cd frontend && npm run build
```

Expected: 构建成功。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/styles/aurora.css frontend/src/main.tsx
git commit -m "feat(frontend): add aurora gradient utility classes with signature-moment whitelist [P0]"
```

---

## Phase 1 · ChatPage Signature Moments

**目标**：落地 6 个 signature moment 中的 5 个（#1 hero 标题、#2 halo、#3 input focus、#4 citation badge、#6 cursor trail）。这是用户第一眼看到的门面，必须完美。

### Task 1.1 · 更新 CitationBadge 测试（锁 gradient 契约）

**Files:**
- Modify: `frontend/src/components/chat/CitationBadge.test.tsx`

- [ ] **Step 1: 读现有测试看现有契约**

```bash
cat frontend/src/components/chat/CitationBadge.test.tsx
```

- [ ] **Step 2: 追加新契约测试**

在现有测试块末尾追加：

```tsx
  it("主引用 n=1 应用 vio-aurora-chip gradient 类", () => {
    const card: SourceCard = { n: 1, docName: "GMRA.pdf", docId: "d1", kbId: "kb1" };
    const indexMap = new Map<number, SourceCard>([[1, card]]);

    const { container } = render(
      <CitationBadge n={1} indexMap={indexMap} onClick={() => {}} />,
    );

    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    expect(button!.className).toContain("vio-aurora-chip");
  });

  it("非主引用 n>1 使用实色雾紫底（无 gradient）", () => {
    const card: SourceCard = { n: 2, docName: "ISDA.pdf", docId: "d2", kbId: "kb1" };
    const indexMap = new Map<number, SourceCard>([[2, card]]);

    const { container } = render(
      <CitationBadge n={2} indexMap={indexMap} onClick={() => {}} />,
    );

    const button = container.querySelector("button");
    expect(button).not.toBeNull();
    expect(button!.className).not.toContain("vio-aurora-chip");
    expect(button!.className).toMatch(/bg-vio-accent-subtle|bg-\[var\(--vio-accent-subtle\)\]/);
  });
```

- [ ] **Step 3: 运行测试验证它们 FAIL**

```bash
cd frontend && npm run test -- CitationBadge
```

Expected: 两个新用例 FAIL（因为 `CitationBadge.tsx` 还是旧蓝色，没用 vio-aurora-chip）。

- [ ] **Step 4: 暂不 commit，等 Task 1.2 一起**

---

### Task 1.2 · CitationBadge 换装

**Files:**
- Modify: `frontend/src/components/chat/CitationBadge.tsx`

- [ ] **Step 1: 整体改写为 gradient-for-n=1 + 实色-for-others**

替换整个文件（`frontend/src/components/chat/CitationBadge.tsx`）为：

```tsx
import type { SourceCard } from "@/types";
import { cn } from "@/lib/utils";

interface Props {
  n: number;
  indexMap: Map<number, SourceCard>;
  onClick: (n: number) => void;
}

export function CitationBadge({ n, indexMap, onClick }: Props) {
  const card = indexMap.get(n);
  // 越界编号（LLM 产出 [^99] 而 cards 只有 8）降级为纯文本 <sup>，不交互
  if (!card) {
    return <sup className="text-[11px] text-vio-ink/40 font-mono">[^{n}]</sup>;
  }

  const isPrimary = n === 1;

  return (
    <sup className="mx-0.5">
      <button
        type="button"
        onClick={() => onClick(n)}
        title={card.docName}
        aria-label={`引用 ${n}：${card.docName}`}
        className={cn(
          "inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full",
          "px-1.5 text-[10px] font-bold font-mono",
          "transition-all duration-200",
          isPrimary
            ? "vio-aurora-chip hover:brightness-110"
            : "bg-[var(--vio-accent-subtle)] text-[var(--vio-accent)] hover:bg-[var(--vio-accent-2)] hover:text-white",
        )}
      >
        {n}
      </button>
    </sup>
  );
}
```

- [ ] **Step 2: 运行测试验证全部 PASS**

```bash
cd frontend && npm run test -- CitationBadge
```

Expected: 所有 CitationBadge 用例 PASS（包括 Task 1.1 新增的 2 个 + 原有用例）。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/chat/CitationBadge.tsx frontend/src/components/chat/CitationBadge.test.tsx
git commit -m "feat(chat): CitationBadge gradient-for-primary, violet-fill for others [P1]"
```

---

### Task 1.3 · ChatInput focus halo

**Files:**
- Modify: `frontend/src/components/chat/ChatInput.tsx`

- [ ] **Step 1: 读现有文件定位输入容器根 div 与按钮**

```bash
cat frontend/src/components/chat/ChatInput.tsx | head -80
```

记录：textarea 外层 div 的 className 起始行、提交按钮起始行。

- [ ] **Step 2: 外层输入容器加 `vio-aurora-focus-within` 包装**

找到最外层包裹 textarea + "深度思考" + 发送按钮的 div（类似 `<div className="flex items-center ...">`），把其 className 里原有的蓝色 ring / border / focus 类全部删除，替换为：

```tsx
<div className="relative">
  <div
    className={cn(
      "relative flex items-center gap-2 rounded-2xl border border-vio-line bg-white px-4 py-3",
      "transition-shadow duration-200",
      "focus-within:border-vio-accent focus-within:shadow-halo",
      "focus-within:ring-2 focus-within:ring-vio-accent-subtle",
    )}
  >
    {/* textarea + 深度思考按钮 + 提交按钮 — 保持原有 JSX */}
  </div>
</div>
```

- [ ] **Step 3: 提交按钮改为实心紫圆按钮**

把提交按钮（通常是最右侧带箭头的 button）的 className 改为：

```tsx
className={cn(
  "inline-flex h-8 w-8 items-center justify-center rounded-full",
  "bg-gradient-to-br from-[var(--vio-accent)] to-[var(--vio-accent-2)] text-white",
  "shadow-sm transition-all hover:shadow-halo disabled:opacity-40 disabled:cursor-not-allowed",
)}
```

- [ ] **Step 4: "深度思考" 按钮改为雾紫 pill**

把深度思考按钮的 className 改为：

```tsx
className={cn(
  "inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] font-medium",
  "transition-colors",
  deepThinkingEnabled
    ? "border-vio-accent-subtle bg-[var(--vio-accent-mist)] text-vio-accent"
    : "border-vio-line bg-transparent text-vio-ink/60 hover:bg-[var(--vio-accent-mist)] hover:text-vio-accent",
)}
```

(`deepThinkingEnabled` 的变量名以当前代码为准；如为 `isDeepThinking` 等请保留原名。)

- [ ] **Step 5: dev server 目测 `focus` 时有雾紫光晕**

```bash
cd frontend && npm run dev
```

登录后到 /chat，点击输入框 —— 应看到 2px 雾紫 ring + 柔和 halo 阴影；失焦消失。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/chat/ChatInput.tsx
git commit -m "feat(chat): ChatInput violet focus halo + gradient submit button [P1]"
```

---

### Task 1.4 · WelcomeScreen Hero（signature moment #1 + #2）

**Files:**
- Modify: `frontend/src/components/chat/WelcomeScreen.tsx`

- [ ] **Step 1: 读现有文件记录关键 JSX**

```bash
cat frontend/src/components/chat/WelcomeScreen.tsx
```

确认：大标题 "把问题变成清晰答案" 的位置、三张建议卡片的位置、输入框的位置。

- [ ] **Step 2: 整体重写 hero 区域**

把现有的 hero 标题区 + 输入框外层包装替换为：

```tsx
<div className="relative mx-auto max-w-3xl px-6 py-16">
  {/* signature moment #2: 两团角落 halo */}
  <div className="vio-aurora-halo absolute -top-20 right-0 h-80 w-80" aria-hidden />
  <div className="vio-aurora-halo-2 absolute -bottom-20 left-0 h-80 w-80" aria-hidden />

  <div className="relative z-10 text-center">
    {/* kicker */}
    <div className="font-display text-[10px] uppercase tracking-[4px] text-vio-accent-2">
      HT · 知识坊 · № 042
    </div>

    {/* signature moment #1: gradient-clip 标题 */}
    <h1 className="mt-4 font-display text-5xl font-normal leading-[1.0] tracking-[-0.03em] text-vio-ink md:text-6xl">
      把问题
      <br />
      变成 <span className="vio-aurora-text italic">清晰的答案</span>
    </h1>

    <p className="mt-5 font-body text-sm text-vio-ink/60 tracking-wide">
      结构化检索 · 多源互证 · 深度推理
    </p>

    {/* ChatInput 宿主位置（保留原输入框组件调用，只换外层 classname） */}
    <div className="relative mt-8">
      {/* 此处保留原 ChatInput 调用 */}
    </div>

    {/* 建议 chips — 用已有 suggestions 数据源 */}
    <div className="mt-5 flex flex-wrap justify-center gap-2">
      {suggestions.map((s) => (
        <button
          key={s.id}
          type="button"
          onClick={() => onSuggestionClick(s.prompt)}
          className="rounded-full border border-vio-line bg-white px-3 py-1.5 font-body text-[11px] text-vio-ink/70 transition-colors hover:border-vio-accent-subtle hover:text-vio-accent hover:bg-[var(--vio-accent-mist)]"
        >
          · {s.label}
        </button>
      ))}
    </div>
  </div>
</div>
```

注意：
- 保留原有的 `suggestions` state / fetch 逻辑不变；只改外层 DOM。
- 如果原文件用的是 `useSuggestions()` hook，保留；替换的只有 JSX。
- 三张大卡片（"内容总结 / 任务拆解 / 灵感扩展"）改为小 chips 形式，信息密度一致但视觉权重降下来 —— hero 大字是主角。

- [ ] **Step 3: 移除旧的"RAG 智能问答"蓝色 badge 与三张建议卡 JSX**

在文件里搜索并删除类似以下的块：
- `<Badge ...>RAG 智能问答</Badge>` 或 `bg-blue-* / text-blue-*` 的 badge
- `<div className="grid grid-cols-3 gap-4">` 下 3 张建议卡的整块（如果保留 chips 形式就删 card 形式）

- [ ] **Step 4: dev server 目测**

```bash
cd frontend && npm run dev
```

登录后到 /chat 空态：应看到
1. 暖白背景 + 两团雾紫 halo（非中心焦点，作氛围）
2. "把问题 / 变成 清晰的答案" 两行大标题，其中"清晰的答案"是彩虹渐变文字 + italic
3. 输入框下方 3-5 个圆角 chip suggestions
4. 所有文字 serif display（Fraunces）

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/WelcomeScreen.tsx
git commit -m "feat(chat): WelcomeScreen violet aurora hero with gradient-clip title + corner halos [P1]"
```

---

### Task 1.5 · MessageItem user/assistant 气泡

**Files:**
- Modify: `frontend/src/components/chat/MessageItem.tsx`

- [ ] **Step 1: 读现有文件定位 user bubble 与 assistant container**

```bash
cat frontend/src/components/chat/MessageItem.tsx
```

- [ ] **Step 2: user 气泡换成雾紫底 + 不对称圆角**

找到 user 分支的容器 JSX（通常是 `role === "user"` 或类似 prop），把其 className 改为：

```tsx
<div
  className={cn(
    "ml-auto max-w-[75%] rounded-[14px] rounded-br-[4px]",
    "border border-vio-accent-subtle bg-[var(--vio-accent-mist)]",
    "px-3.5 py-2.5 font-body text-sm text-vio-ink",
  )}
>
  {content}
</div>
```

- [ ] **Step 3: assistant 容器去掉白色 card 边框，让答案"躺在纸上"**

找到 assistant 分支的容器 JSX，把其外层 className 改为：

```tsx
<div className="font-body text-[14px] leading-[1.75] text-vio-ink/90">
  {/* 保留原有的 MarkdownRenderer + Sources + Feedback 调用 */}
</div>
```

不要包外框 / 阴影 / 边框 —— assistant 答案应该看起来是"纸上印的字"。

- [ ] **Step 4: Build + dev server 验证**

```bash
cd frontend && npm run build && npm run dev
```

提问一条消息（需后端运行），观察 user 气泡（紫雾底 + 不对称圆角）和 assistant 内容（无边框裸文本）。

- [ ] **Step 5: 跑 MessageItem 单测**

```bash
cd frontend && npm run test -- MessageItem
```

Expected: 所有用例 PASS（现有测试锁定的是 timer cleanup 等行为，不涉及 className，不会退化）。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/chat/MessageItem.tsx
git commit -m "feat(chat): MessageItem user bubble violet mist, assistant unboxed text [P1]"
```

---

### Task 1.6 · MarkdownRenderer 排版节奏

**Files:**
- Modify: `frontend/src/components/chat/MarkdownRenderer.tsx`

- [ ] **Step 1: 定位现有 `components` 映射**

```bash
grep -n "components:" frontend/src/components/chat/MarkdownRenderer.tsx
grep -n "h1\|h2\|h3\|blockquote\|p:" frontend/src/components/chat/MarkdownRenderer.tsx
```

- [ ] **Step 2: 在 components 映射里改写 h1/h2/h3/blockquote**

定位 react-markdown 的 `components={...}` prop，修改/追加（保留原有 cite/code 等映射不动）：

```tsx
components={{
  h1: ({ children, ...props }) => (
    <h1
      {...props}
      className="font-display text-[30px] font-medium leading-[1.15] tracking-[-0.02em] text-vio-ink mb-3 mt-6 first:mt-0"
    >
      {children}
    </h1>
  ),
  h2: ({ children, ...props }) => (
    <h2
      {...props}
      className="font-display text-[22px] font-medium leading-[1.2] tracking-[-0.015em] text-vio-ink mb-2 mt-5 first:mt-0"
    >
      {children}
    </h2>
  ),
  h3: ({ children, ...props }) => (
    <h3
      {...props}
      className="font-display text-[17px] font-semibold leading-[1.3] text-vio-ink mb-2 mt-4 first:mt-0"
    >
      {children}
    </h3>
  ),
  blockquote: ({ children, ...props }) => (
    <blockquote
      {...props}
      className="my-3 rounded-r-[10px] border-l-[3px] border-vio-accent-2 bg-white pl-3 pr-4 py-2 font-body italic text-[13px] leading-[1.55] text-vio-ink/80"
    >
      {children}
    </blockquote>
  ),
  strong: ({ children, ...props }) => (
    <strong
      {...props}
      className="font-semibold bg-gradient-to-t from-[var(--vio-accent-subtle)]/50 from-35% to-transparent to-35% px-[2px]"
    >
      {children}
    </strong>
  ),
  // ... 保留原有 cite / code / ul / ol 映射不变
}}
```

- [ ] **Step 3: 跑 MarkdownRenderer 单测**

```bash
cd frontend && npm run test -- MarkdownRenderer
```

Expected: 所有用例 PASS（测试锁定的是 cite 契约与 remarkPlugins 顺序，与 className 无关）。

- [ ] **Step 4: dev server 目测 markdown 输出**

发一条要求总结的问题（让 LLM 返回 markdown 标题 + 引用 + 强调）。观察：
- 标题是 serif（Fraunces），带负字距
- 引用块是左侧雾紫边条 + 斜体
- `**加粗**` 文字带雾紫底色下划线效果

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/MarkdownRenderer.tsx
git commit -m "feat(chat): MarkdownRenderer editorial serif headings + violet blockquote + highlight strong [P1]"
```

---

### Task 1.7 · ThinkingIndicator 雾紫胶囊

**Files:**
- Modify: `frontend/src/components/chat/ThinkingIndicator.tsx`

- [ ] **Step 1: 整个组件重写**

```tsx
import { cn } from "@/lib/utils";

interface Props {
  duration?: number;
  chunkCount?: number;
  isActive?: boolean;
}

export function ThinkingIndicator({ duration, chunkCount, isActive }: Props) {
  return (
    <div
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1",
        "border border-vio-accent-subtle",
        "bg-gradient-to-r from-[#9DC3FF]/10 via-[#D4AEF5]/10 to-[#FFB8D4]/10",
        "font-mono text-[10px] text-vio-accent",
      )}
    >
      <span
        className={cn(
          "h-1.5 w-1.5 rounded-full bg-[var(--vio-accent)]",
          isActive && "animate-pulse-soft",
        )}
      />
      <span>深度思考</span>
      {typeof duration === "number" && (
        <>
          <span className="text-vio-ink/30">·</span>
          <span>{(duration / 1000).toFixed(1)}s</span>
        </>
      )}
      {typeof chunkCount === "number" && (
        <>
          <span className="text-vio-ink/30">·</span>
          <span>{chunkCount} 片段召回</span>
        </>
      )}
    </div>
  );
}
```

注意：props 签名保持与当前组件兼容 —— 请先 `cat frontend/src/components/chat/ThinkingIndicator.tsx` 确认 props，不要破坏调用方。如果当前组件 props 不同，保持 props 不变，只换内部 JSX。

- [ ] **Step 2: Build 验证**

```bash
cd frontend && npm run build
```

Expected: 构建成功；无 TypeScript 错误。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/chat/ThinkingIndicator.tsx
git commit -m "feat(chat): ThinkingIndicator violet aurora pill [P1]"
```

---

### Task 1.8 · P1 视觉回归自检

**Files:** 无改动

- [ ] **Step 1: 清洁构建 + 启动**

```bash
cd frontend && npm run build
cd frontend && npm run dev
```

- [ ] **Step 2: 场景清单目测**

登录到 /chat，逐项检查：
1. 欢迎屏：两团 halo、gradient 大标题"清晰的答案"、suggestion chips 圆角
2. 输入框：focus 时雾紫 ring + halo 阴影
3. "深度思考"按钮：未启用灰调、启用紫雾底
4. 发送按钮：实心紫圆
5. 提问 → 等 assistant 回答：
   - user 气泡紫雾底 + 不对称圆角
   - assistant 裸文本（无边框）
   - markdown 标题 serif
   - `[^1]` 彩虹 badge、`[^2]` 实色紫 badge
   - 引用块左边条紫色 + 斜体
   - ThinkingIndicator 雾紫胶囊

- [ ] **Step 3: Tag P1 完成**

```bash
git tag violet-aurora-p1
```

---

## Phase 2 · Sidebar + Header 拆除

**目标**：落地 signature moment #5（Logo monogram）；取消顶栏，让聊天页变"一张纸"。

### Task 2.1 · Sidebar Logo 换成 HT monogram

**Files:**
- Modify: `frontend/src/components/layout/Sidebar.tsx`

- [ ] **Step 1: 读现有 Sidebar 文件头部 Logo 区**

```bash
cat frontend/src/components/layout/Sidebar.tsx | head -80
```

定位当前 logo 区的 JSX（通常在顶部带 "Ragent AI 智能体" 或 "HT KnowledgeBase" 的那块）。

- [ ] **Step 2: 替换 logo 区为 HT monogram + gradient 底**

```tsx
<div className="flex items-center gap-2.5 px-4 py-4">
  {/* signature moment #5: gradient 实心 monogram */}
  <div className="vio-aurora-fill flex h-9 w-9 items-center justify-center rounded-[10px] font-display text-[16px] font-semibold italic text-white shadow-sm">
    HT
  </div>
  <div className="flex flex-col leading-none">
    <div className="font-display text-[15px] font-medium text-vio-ink">
      华泰知识坊
    </div>
    <div className="mt-1 font-mono text-[9px] uppercase tracking-[1.5px] text-vio-ink/40">
      № 042 · KB.MAIN
    </div>
  </div>
</div>
```

注意：保留 i18n-ready —— P4 会把"华泰知识坊"替换为 `t("common:brand_name")`，此刻先硬编码 zh-CN。

- [ ] **Step 3: dev 目测**

```bash
cd frontend && npm run dev
```

Sidebar 顶部应出现 gradient 方形 + "HT" 字母 + 两行文字。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/layout/Sidebar.tsx
git commit -m "feat(layout): Sidebar HT monogram logo with aurora gradient [P2]"
```

---

### Task 2.2 · "新建对话"卡 halo（signature moment #6）

**Files:**
- Modify: `frontend/src/components/layout/Sidebar.tsx`

- [ ] **Step 1: 定位现有"新建对话"按钮/卡 JSX**

```bash
grep -n "新建对话\|newChat\|createSession" frontend/src/components/layout/Sidebar.tsx
```

- [ ] **Step 2: 替换为带角落 halo 的卡**

```tsx
<button
  type="button"
  onClick={handleNewChat}
  className="group relative mx-3 mt-1 mb-3 overflow-hidden rounded-[12px] border border-vio-line bg-white px-3 py-2.5 text-left shadow-paper transition-all hover:shadow-halo hover:border-vio-accent-subtle"
>
  {/* 角落 halo — hover 时放大 */}
  <div
    className="pointer-events-none absolute -right-6 -top-6 h-20 w-20 rounded-full bg-[radial-gradient(circle,rgba(179,155,245,0.40),transparent_65%)] blur-md transition-transform duration-300 group-hover:scale-125"
    aria-hidden
  />

  <div className="relative flex items-center gap-2.5">
    <div className="flex h-7 w-7 items-center justify-center rounded-[8px] bg-gradient-to-br from-[var(--vio-accent)] to-[var(--vio-accent-2)] text-white">
      <PlusIcon className="h-4 w-4" />
    </div>
    <div>
      <div className="font-body text-[13px] font-semibold text-vio-ink">
        新建对话
      </div>
      <div className="mt-0.5 font-body text-[10px] text-vio-ink/50">
        从空白开始
      </div>
    </div>
  </div>
</button>
```

(`PlusIcon` 从 `lucide-react` import；若已 import 保留。)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/layout/Sidebar.tsx
git commit -m "feat(layout): Sidebar new-chat card with corner halo hover [P2]"
```

---

### Task 2.3 · Sidebar 会话列表换装

**Files:**
- Modify: `frontend/src/components/layout/Sidebar.tsx`

- [ ] **Step 1: 把 "今天 / 昨天" 分组标签改为 Mono uppercase**

定位分组标签 JSX（通常是 `<div className="...">今天</div>`），替换为：

```tsx
<div className="mt-5 px-4 font-mono text-[9px] uppercase tracking-[1.5px] text-vio-ink/40">
  {label /* 原 '今天' / '昨天' / 'xxx 月' */}
</div>
```

- [ ] **Step 2: 会话项 hover / 选中换紫雾系**

定位会话项（`SessionItem` 或内联渲染），把容器 className 改为：

```tsx
className={cn(
  "mx-3 px-3 py-2 rounded-[8px] cursor-pointer font-body text-[12.5px]",
  "transition-colors",
  isActive
    ? "bg-[var(--vio-accent-mist)] text-vio-ink border-l-2 border-[var(--vio-accent)] pl-[10px]"
    : "text-vio-ink/70 hover:bg-[var(--vio-accent-mist)]/50 hover:text-vio-ink",
)}
```

- [ ] **Step 3: Sidebar 根容器底色切到暖米**

定位 Sidebar 外层 div 的 className，把底色（`bg-surface-2` / `bg-[#fafafa]` / 类似）改为：

```tsx
className="relative flex w-[280px] flex-col border-r border-vio-line bg-[var(--vio-surface-2)]"
```

- [ ] **Step 4: dev 目测**

Sidebar 整体暖米底、选中项雾紫+左边条、hover 项浅紫底。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/layout/Sidebar.tsx
git commit -m "feat(layout): Sidebar warm-cream bg, mono section labels, violet selection [P2]"
```

---

### Task 2.4 · 移除 Header 挂载

**Files:**
- Modify: `frontend/src/components/layout/MainLayout.tsx`
- Delete: `frontend/src/components/layout/Header.tsx`

- [ ] **Step 1: 读 MainLayout 当前结构**

```bash
cat frontend/src/components/layout/MainLayout.tsx
```

- [ ] **Step 2: 重写 MainLayout（无 Header）**

```tsx
import * as React from "react";

import { Sidebar } from "@/components/layout/Sidebar";

interface MainLayoutProps {
  children: React.ReactNode;
}

export function MainLayout({ children }: MainLayoutProps) {
  const [sidebarOpen, setSidebarOpen] = React.useState(false);

  return (
    <div className="flex min-h-screen bg-[var(--vio-surface)]">
      <Sidebar isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <main className="flex-1 min-h-0 overflow-hidden bg-[var(--vio-surface)]">
        {children}
      </main>
    </div>
  );
}
```

- [ ] **Step 3: 搜索 Header 引用**

```bash
grep -rn "from \"@/components/layout/Header\"\|from '@/components/layout/Header'" frontend/src
```

Expected: 只有 MainLayout.tsx 引用 Header（已被刚才的修改删除）。如果有其他引用，逐个清除。

- [ ] **Step 4: 删除 Header.tsx 文件**

```bash
rm "frontend/src/components/layout/Header.tsx"
```

- [ ] **Step 5: 再次 grep 确保无引用残留**

```bash
grep -rn "Header" frontend/src/components/layout/
```

Expected: 无输出（或只剩无关匹配）。

- [ ] **Step 6: tsc + build 验证**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit
cd frontend && npm run build
```

Expected: 两个都 PASS。

- [ ] **Step 7: dev 目测 /chat 无顶栏**

```bash
cd frontend && npm run dev
```

/chat 页面应无任何顶部 bar，聊天主区整体是一张纸。

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/layout/MainLayout.tsx frontend/src/components/layout/Header.tsx
git commit -m "refactor(layout): remove Header, unify surface, single-pane ChatPage [P2]"
```

注：被删除的 `Header.tsx` 会在 `git add` 时被标记为 deletion。

---

### Task 2.5 · AdminLayout 面包屑内联

**Files:**
- Modify: `frontend/src/pages/admin/AdminLayout.tsx`

- [ ] **Step 1: 读现有 AdminLayout**

```bash
cat frontend/src/pages/admin/AdminLayout.tsx
```

- [ ] **Step 2: 移除顶部面包屑 bar，改为页面内 kicker**

找到顶部 breadcrumb 容器（通常是 `<div className="...bg-white h-14...">` 包裹 `<Breadcrumb>` 或类似），替换为：

```tsx
{/* 面包屑从顶栏下沉到内容区顶部 kicker */}
<div className="px-8 pt-6 pb-2">
  <div className="font-mono text-[9px] uppercase tracking-[2px] text-vio-accent-2">
    {breadcrumbs.map((b, i) => (
      <span key={i}>
        {b.label}
        {i < breadcrumbs.length - 1 && <span className="mx-1.5 text-vio-ink/30">/</span>}
      </span>
    ))}
  </div>
</div>
```

- [ ] **Step 3: Sidebar 内菜单项与会话项风格保持 P2 Sidebar 一致**

确认 AdminLayout 的侧边栏菜单 className 与 `Sidebar.tsx` 的 session 项样式相同（`isActive` 时紫雾底 + 左边条）。如不一致，统一为相同类名。

- [ ] **Step 4: tsc + build**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit && npm run build
```

- [ ] **Step 5: dev 目测 /admin/dashboard**

应无顶部 bar；面包屑 kicker 显示在内容区顶部。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/admin/AdminLayout.tsx
git commit -m "refactor(admin): inline breadcrumb as kicker, no top bar [P2]"
```

---

### Task 2.6 · P2 视觉回归

**Files:** 无

- [ ] **Step 1: 完整场景过**

- 登录 → /spaces → 选一个 KB → /chat?kbId=xxx
- 观察 Sidebar：暖米底、HT monogram gradient、新建对话卡 hover 光晕扩散、会话项紫雾选中 + 左边条
- 观察 ChatPage：无顶栏、hero 正常、输入 + 对话正常
- 进 /admin/dashboard：无顶栏、面包屑 kicker、Sidebar 同样风格

- [ ] **Step 2: Tag**

```bash
git tag violet-aurora-p2
```

---

## Phase 3 · Spaces + Admin 换装

**目标**：SpacesPage 入口 + Dashboard KPI + 表格基调换装。

### Task 3.1 · SpacesPage 换装

**Files:**
- Modify: `frontend/src/pages/SpacesPage.tsx`

- [ ] **Step 1: 读现有 SpacesPage**

```bash
cat frontend/src/pages/SpacesPage.tsx
```

- [ ] **Step 2: 顶部 hero 换为编辑磁刊风**

替换页面顶部欢迎/title 区为：

```tsx
<div className="mx-auto max-w-5xl px-8 py-10">
  <div className="text-center">
    <div className="font-display text-[10px] uppercase tracking-[4px] text-vio-accent-2">
      HT · 知识空间 · № 042
    </div>
    <h1 className="mt-3 font-display text-4xl font-normal tracking-[-0.02em] text-vio-ink md:text-5xl">
      选择一个 <span className="vio-aurora-text italic">知识空间</span>
    </h1>
    <p className="mt-3 font-body text-sm text-vio-ink/60">
      {kbs.length} 个空间 · 按部门权限过滤
    </p>
  </div>

  {/* kb grid 保持原有渲染逻辑，只换卡片样式 */}
</div>
```

- [ ] **Step 3: KB 卡片换装**

找到 KB 卡的渲染代码（类似 `.map(kb => <div className="...">`），把卡片 className 改为：

```tsx
className={cn(
  "group relative flex flex-col rounded-[16px] border border-vio-line bg-white p-5",
  "shadow-paper transition-all duration-300",
  "hover:border-vio-accent-subtle hover:shadow-halo hover:-translate-y-0.5",
)}
```

卡内：
- 标题改为 `font-display text-[18px] font-medium tracking-[-0.01em] text-vio-ink`
- 描述改为 `font-body text-[12px] text-vio-ink/60 mt-1.5`
- 统计数字（文档 / 分块）改为 `font-mono text-[11px] text-vio-accent-2`

- [ ] **Step 4: 空态提示换装**

找到 "暂无知识空间" 类 empty state，把外层改为：

```tsx
<div className="relative mx-auto mt-12 max-w-md text-center overflow-hidden rounded-[20px] border border-vio-line bg-[var(--vio-surface-2)] p-10">
  <div className="vio-aurora-halo absolute -top-16 right-0 h-48 w-48" aria-hidden />
  <div className="relative">
    <div className="font-display text-[22px] text-vio-ink">暂无可用的知识空间</div>
    <div className="mt-2 font-body text-[13px] text-vio-ink/60">
      联系管理员授予你的部门访问权限
    </div>
  </div>
</div>
```

- [ ] **Step 5: dev 目测**

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/SpacesPage.tsx
git commit -m "feat(spaces): SpacesPage editorial hero + violet kb cards [P3]"
```

---

### Task 3.2 · Dashboard KPI 卡 + 图表

**Files:**
- Modify: `frontend/src/pages/admin/dashboard/*.tsx`（通常 1-3 个文件）
- Modify: `frontend/src/components/admin/SimpleLineChart.tsx`

- [ ] **Step 1: 列出 dashboard 文件**

```bash
ls frontend/src/pages/admin/dashboard/
```

- [ ] **Step 2: KPI 卡统一样式**

打开主 Dashboard 页面（通常是 `DashboardPage.tsx` 或 `OverviewPage.tsx`），找到 KPI 卡的渲染块，把每张卡外层 className 改为：

```tsx
className="rounded-[14px] border border-vio-line bg-white p-5 shadow-paper"
```

卡内：
- label（如"总问答次数"）：`font-mono text-[10px] uppercase tracking-[1.5px] text-vio-ink/50 mb-2`
- 数值：`font-display text-[32px] font-medium tracking-[-0.02em] text-vio-ink`
- 变化率（+5.2%）：`font-body text-[12px] text-vio-success` 或 `text-vio-danger` 按方向

- [ ] **Step 3: SimpleLineChart 线色改紫**

打开 `frontend/src/components/admin/SimpleLineChart.tsx`，定位 recharts `<Line stroke={...}>` 或 `<Area fill={...}>`，把所有颜色常量改为：

```tsx
const VIO_STROKE = "#5B4BE8";       // vio-accent
const VIO_AREA   = "rgba(139,127,239,0.15)"; // vio-accent-2 @ 15%
const VIO_GRID   = "#EAE6DB";       // vio-line
```

并用于：
- `<Line stroke={VIO_STROKE} strokeWidth={2} dot={false} />`
- `<Area fill={VIO_AREA} />`（如有）
- `<CartesianGrid stroke={VIO_GRID} strokeDasharray="3 3" />`
- `<XAxis tick={{ fill: "#15141A", fontSize: 11, fontFamily: "JetBrains Mono" }} />`

- [ ] **Step 4: dev 目测 /admin/dashboard**

KPI 卡新样式 + 折线图紫色 + 网格暖米。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/admin/dashboard/ frontend/src/components/admin/SimpleLineChart.tsx
git commit -m "feat(admin): Dashboard KPI cards + violet line chart [P3]"
```

---

### Task 3.3 · Table 样式统一（shadcn ui/table.tsx）

**Files:**
- Modify: `frontend/src/components/ui/table.tsx`

- [ ] **Step 1: 读现有 table.tsx**

```bash
cat frontend/src/components/ui/table.tsx
```

- [ ] **Step 2: 改写 TableHeader / TableHead / TableRow / TableCell className**

保持所有导出的组件名不变，只改内部 className。关键点：

- `TableHeader` 外层：`bg-[var(--vio-surface-2)]`
- `TableHead`（th）：`font-mono text-[10px] uppercase tracking-[1.5px] text-vio-ink/60 font-semibold h-10`
- `TableRow`：`border-b border-vio-line hover:bg-[var(--vio-accent-mist)]/60`
- `TableRow` 偶数行（通过 `[&:nth-child(even)]:bg-vio-surface-2` 或类似）：浅雾底
- `TableCell`：`font-body text-[13px] text-vio-ink py-2.5`

示例 TableHead：

```tsx
const TableHead = React.forwardRef<
  HTMLTableCellElement,
  React.ThHTMLAttributes<HTMLTableCellElement>
>(({ className, ...props }, ref) => (
  <th
    ref={ref}
    className={cn(
      "h-10 px-4 text-left align-middle",
      "font-mono text-[10px] font-semibold uppercase tracking-[1.5px]",
      "text-vio-ink/60",
      className,
    )}
    {...props}
  />
));
```

- [ ] **Step 3: Build**

```bash
cd frontend && npm run build
```

- [ ] **Step 4: dev 目测** — /admin/access?tab=members 或其他有表格的页面，验证表头 mono uppercase、偶数行浅紫。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/table.tsx
git commit -m "feat(ui): Table mono uppercase headers, violet mist zebra rows [P3]"
```

---

### Task 3.4 · Admin Sidebar 菜单项对齐

**Files:**
- Modify: `frontend/src/pages/admin/AdminLayout.tsx`

- [ ] **Step 1: 确保菜单项 className 与聊天页 Sidebar 会话项一致**

把 AdminLayout 的菜单项（Dashboard / 知识库 / 权限中心 / ...）className 统一为：

```tsx
className={cn(
  "flex items-center gap-2.5 mx-3 px-3 py-2 rounded-[8px]",
  "font-body text-[12.5px] transition-colors",
  isActive
    ? "bg-[var(--vio-accent-mist)] text-vio-ink border-l-2 border-[var(--vio-accent)] pl-[10px]"
    : "text-vio-ink/70 hover:bg-[var(--vio-accent-mist)]/50 hover:text-vio-ink",
)}
```

菜单 icon 用 `h-3.5 w-3.5 text-vio-ink/60`。

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/admin/AdminLayout.tsx
git commit -m "feat(admin): AdminLayout menu items align with chat Sidebar [P3]"
git tag violet-aurora-p3
```

---

## Phase 4 · i18n 骨架

**目标**：接入 `react-i18next`；提取 zh-CN 为 key；切换入口 + 持久化；`document.lang` 同步。zh-HK / en 留空（运行时回落 zh-CN）。

### Task 4.1 · 安装依赖

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

- [ ] **Step 1: 安装**

```bash
cd frontend && npm install i18next@^23 react-i18next@^14 i18next-browser-languagedetector@^8
```

- [ ] **Step 2: 验证 package.json 新增条目**

```bash
grep -E "i18next|react-i18next|language-detector" frontend/package.json
```

Expected: 3 条 dependencies。

- [ ] **Step 3: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "chore(frontend): add react-i18next + language detector [P4]"
```

---

### Task 4.2 · i18n 配置模块

**Files:**
- Create: `frontend/src/i18n/config.ts`
- Create: `frontend/src/i18n/index.ts`

- [ ] **Step 1: 创建 `frontend/src/i18n/config.ts`**

```ts
export const SUPPORTED_LOCALES = ["zh-CN", "zh-HK", "en"] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];

export const DEFAULT_LOCALE: Locale = "zh-CN";
export const FALLBACK_LOCALE: Locale = "zh-CN";

export const NAMESPACES = ["common", "chat", "admin", "access", "errors"] as const;
export type Namespace = (typeof NAMESPACES)[number];

export const STORAGE_KEY = "htkb.locale";

export const LOCALE_LABELS: Record<Locale, string> = {
  "zh-CN": "简体中文",
  "zh-HK": "繁體中文（香港）",
  en: "English",
};
```

- [ ] **Step 2: 创建 `frontend/src/i18n/index.ts`**

```ts
import i18n from "i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import { initReactI18next } from "react-i18next";

import {
  DEFAULT_LOCALE,
  FALLBACK_LOCALE,
  NAMESPACES,
  STORAGE_KEY,
  SUPPORTED_LOCALES,
  type Locale,
} from "./config";

// 动态加载每个 locale 的每个 namespace
async function loadBundle(locale: Locale) {
  for (const ns of NAMESPACES) {
    try {
      const mod = await import(`../locales/${locale}/${ns}.json`);
      i18n.addResourceBundle(locale, ns, mod.default ?? mod, true, true);
    } catch {
      // 缺失文件时静默 —— i18next 会走 fallback
    }
  }
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    fallbackLng: FALLBACK_LOCALE,
    supportedLngs: SUPPORTED_LOCALES as unknown as string[],
    ns: NAMESPACES as unknown as string[],
    defaultNS: "common",
    detection: {
      order: ["localStorage", "navigator"],
      lookupLocalStorage: STORAGE_KEY,
      caches: ["localStorage"],
    },
    interpolation: { escapeValue: false },
    returnEmptyString: false,
  });

// init 完成后按当前 locale 加载资源 + 同步 document.lang
i18n.on("initialized", async () => {
  const current = (i18n.resolvedLanguage ?? DEFAULT_LOCALE) as Locale;
  document.documentElement.lang = current;
  await loadBundle(current);
});

i18n.on("languageChanged", async (lng) => {
  document.documentElement.lang = lng;
  await loadBundle(lng as Locale);
});

export default i18n;
```

- [ ] **Step 3: 在 `main.tsx` 导入**

定位 `frontend/src/main.tsx`，在现有 imports 末尾加：

```ts
import "./i18n";
```

- [ ] **Step 4: tsc 验证**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit
```

Expected: 成功（locale 目录下暂无 json 文件，但 dynamic import 只在运行时执行，不会 ts-error）。

- [ ] **Step 5: Commit（含 Step 6 之前不 commit，先准备资源文件）**

---

### Task 4.3 · zh-CN 资源文件骨架

**Files:**
- Create: `frontend/src/locales/zh-CN/common.json`
- Create: `frontend/src/locales/zh-CN/chat.json`
- Create: `frontend/src/locales/zh-CN/admin.json`
- Create: `frontend/src/locales/zh-CN/access.json`
- Create: `frontend/src/locales/zh-CN/errors.json`

- [ ] **Step 1: `zh-CN/common.json`**

```json
{
  "brand_name": "华泰知识坊",
  "brand_issue_label": "№ 042",
  "actions": {
    "save": "保存",
    "cancel": "取消",
    "delete": "删除",
    "confirm": "确认",
    "close": "关闭",
    "back": "返回",
    "refresh": "刷新",
    "search": "搜索",
    "new": "新建",
    "edit": "编辑",
    "logout": "退出登录"
  },
  "language": {
    "menu_label": "语言",
    "zh-CN": "简体中文",
    "zh-HK": "繁體中文（香港）",
    "en": "English"
  },
  "nav": {
    "spaces": "知识空间",
    "chat": "聊天",
    "admin": "管理后台"
  }
}
```

- [ ] **Step 2: `zh-CN/chat.json`**

```json
{
  "welcome": {
    "kicker": "HT · 知识坊",
    "title_line_1": "把问题",
    "title_line_2_before": "变成",
    "title_line_2_accent": "清晰的答案",
    "subtitle": "结构化检索 · 多源互证 · 深度推理"
  },
  "input": {
    "placeholder": "想问点什么...",
    "deep_thinking": "深度思考",
    "send_aria": "发送",
    "hint_send": "Enter 发送",
    "hint_newline": "Shift + Enter 换行"
  },
  "session": {
    "new_chat": "新建对话",
    "new_chat_hint": "从空白开始",
    "today": "今天",
    "yesterday": "昨天",
    "older": "更早",
    "rename": "重命名",
    "delete": "删除",
    "confirm_delete": "确定删除这条对话吗？"
  },
  "suggestions": {
    "section_label": "试试这些开场",
    "summary": "内容总结",
    "breakdown": "任务拆解",
    "compare": "争议条款对比"
  },
  "sources": {
    "label": "来源",
    "primary_citation_aria": "主引用 {{n}}：{{docName}}",
    "citation_aria": "引用 {{n}}：{{docName}}"
  },
  "thinking": {
    "label": "深度思考",
    "chunks": "{{count}} 片段召回"
  },
  "stop": "停止生成"
}
```

- [ ] **Step 3: `zh-CN/admin.json`**

```json
{
  "sidebar": {
    "dashboard": "Dashboard",
    "knowledge": "知识库管理",
    "access": "权限中心",
    "intent_tree": "意图树",
    "ingestion": "摄入任务",
    "traces": "追踪记录",
    "evaluations": "评估数据",
    "settings": "设置"
  },
  "dashboard": {
    "title": "数据总览",
    "kpi": {
      "total_queries": "总问答次数",
      "active_users": "活跃用户",
      "success_rate": "成功率",
      "avg_latency": "平均时延"
    },
    "trends": {
      "title": "使用趋势",
      "range": "近 7 天"
    }
  }
}
```

- [ ] **Step 4: `zh-CN/access.json`**

```json
{
  "tabs": {
    "members": "成员",
    "sharing": "KB 共享",
    "roles": "角色",
    "departments": "部门"
  },
  "role": {
    "delete": {
      "confirm_title": "删除角色？",
      "affected_users": "影响 {{count}} 名用户",
      "affected_kbs": "影响 {{count}} 个知识库"
    }
  }
}
```

- [ ] **Step 5: `zh-CN/errors.json`**

```json
{
  "generic": "出了点问题，请稍后重试",
  "network": "网络连接失败",
  "unauthorized": "登录已失效，请重新登录",
  "forbidden": "没有权限访问",
  "not_found": "资源不存在"
}
```

- [ ] **Step 6: Commit i18n 骨架（配置 + zh-CN 资源）**

```bash
git add frontend/src/i18n/ frontend/src/locales/zh-CN/ frontend/src/main.tsx
git commit -m "feat(i18n): init react-i18next + zh-CN resource bundles [P4]"
```

---

### Task 4.4 · zh-HK / en 空文件（fallback）

**Files:**
- Create: `frontend/src/locales/zh-HK/{common,chat,admin,access,errors}.json`
- Create: `frontend/src/locales/en/{common,chat,admin,access,errors}.json`

- [ ] **Step 1: 创建 10 个空 JSON 文件（每个内容 `{}`）**

所有文件内容：

```json
{}
```

文件列表：
- `frontend/src/locales/zh-HK/common.json`
- `frontend/src/locales/zh-HK/chat.json`
- `frontend/src/locales/zh-HK/admin.json`
- `frontend/src/locales/zh-HK/access.json`
- `frontend/src/locales/zh-HK/errors.json`
- `frontend/src/locales/en/common.json`
- `frontend/src/locales/en/chat.json`
- `frontend/src/locales/en/admin.json`
- `frontend/src/locales/en/access.json`
- `frontend/src/locales/en/errors.json`

(P5 会填充这两个 locale 的全量翻译。)

- [ ] **Step 2: Commit**

```bash
git add frontend/src/locales/zh-HK frontend/src/locales/en
git commit -m "feat(i18n): zh-HK + en empty bundles (P5 will fill) [P4]"
```

---

### Task 4.5 · LanguageSwitcher 组件 + 测试

**Files:**
- Create: `frontend/src/components/common/LanguageSwitcher.tsx`
- Create: `frontend/src/components/common/LanguageSwitcher.test.tsx`

- [ ] **Step 1: 写失败测试**

```tsx
// frontend/src/components/common/LanguageSwitcher.test.tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, beforeEach } from "vitest";
import i18n from "@/i18n";
import { LanguageSwitcher } from "./LanguageSwitcher";

describe("LanguageSwitcher", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("zh-CN");
  });

  it("渲染当前语言 label（默认 zh-CN）", () => {
    render(<LanguageSwitcher />);
    expect(screen.getByText(/简体中文/)).toBeInTheDocument();
  });

  it("点击切换到 en 后，i18n 语言改变", async () => {
    render(<LanguageSwitcher />);
    fireEvent.click(screen.getByRole("button"));
    fireEvent.click(screen.getByText(/English/));
    expect(i18n.resolvedLanguage).toBe("en");
  });

  it("切换后 document.documentElement.lang 同步", async () => {
    render(<LanguageSwitcher />);
    fireEvent.click(screen.getByRole("button"));
    fireEvent.click(screen.getByText(/繁體中文/));
    await new Promise((r) => setTimeout(r, 0));
    expect(document.documentElement.lang).toBe("zh-HK");
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && npm run test -- LanguageSwitcher
```

Expected: FAIL（组件不存在）。

- [ ] **Step 3: 实现 LanguageSwitcher**

```tsx
// frontend/src/components/common/LanguageSwitcher.tsx
import * as React from "react";
import { useTranslation } from "react-i18next";
import { Globe } from "lucide-react";

import { cn } from "@/lib/utils";
import { LOCALE_LABELS, SUPPORTED_LOCALES, type Locale } from "@/i18n/config";

export function LanguageSwitcher() {
  const { i18n, t } = useTranslation("common");
  const [open, setOpen] = React.useState(false);
  const current = (i18n.resolvedLanguage ?? "zh-CN") as Locale;

  const change = async (l: Locale) => {
    await i18n.changeLanguage(l);
    setOpen(false);
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-[8px] border border-vio-line bg-white",
          "px-2.5 py-1.5 font-body text-[11px] text-vio-ink/70",
          "transition-colors hover:bg-[var(--vio-accent-mist)] hover:text-vio-accent",
        )}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={t("language.menu_label")}
      >
        <Globe className="h-3 w-3" />
        <span>{LOCALE_LABELS[current]}</span>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute bottom-full left-0 mb-1 w-40 rounded-[10px] border border-vio-line bg-white p-1 shadow-halo"
        >
          {SUPPORTED_LOCALES.map((l) => (
            <button
              key={l}
              type="button"
              onClick={() => change(l)}
              role="menuitem"
              className={cn(
                "w-full rounded-[6px] px-2 py-1.5 text-left font-body text-[12px]",
                "transition-colors",
                l === current
                  ? "bg-[var(--vio-accent-mist)] text-vio-accent"
                  : "text-vio-ink/80 hover:bg-[var(--vio-accent-mist)]/60",
              )}
            >
              {LOCALE_LABELS[l]}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 4: 测试通过**

```bash
cd frontend && npm run test -- LanguageSwitcher
```

Expected: 所有用例 PASS。

- [ ] **Step 5: 把 LanguageSwitcher 放进 Sidebar 底部头像菜单**

打开 `frontend/src/components/layout/Sidebar.tsx`，定位头像菜单或底部 footer 区，追加：

```tsx
import { LanguageSwitcher } from "@/components/common/LanguageSwitcher";

// 在 Sidebar 底部（头像菜单附近）插入：
<div className="mt-auto border-t border-vio-line px-3 py-3">
  <LanguageSwitcher />
  {/* 如有头像菜单 / 退出登录按钮，保留不动 */}
</div>
```

- [ ] **Step 6: dev 目测切换**

启动 `npm run dev`，登录后点击 Sidebar 底部语言切换器，切到"English" —— 页面立即切换（此刻 en 是空 bundle，所有 UI 会显示 key 字符串如 `chat.input.placeholder`；这是预期，P5 会填翻译）。切回"简体中文"后正常。刷新页面 —— 语言持久化（localStorage 里能看到 `htkb.locale = "en"`）。

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/common/LanguageSwitcher.tsx frontend/src/components/common/LanguageSwitcher.test.tsx frontend/src/components/layout/Sidebar.tsx
git commit -m "feat(i18n): LanguageSwitcher component + mount in Sidebar footer [P4]"
```

---

### Task 4.6 · 术语表

**Files:**
- Create: `docs/dev/i18n/glossary.md`

- [ ] **Step 1: 创建 glossary**

```markdown
# HT KnowledgeBase i18n 术语表

> 三语统一术语 · 本文件是翻译真相源 · PR 新增术语必须先更新这里

## 核心概念

| EN | zh-CN | zh-HK |
|---|---|---|
| Knowledge Base | 知识库 | 知識庫 |
| Knowledge Space | 知识空间 | 知識空間 |
| Document | 文档 | 文件 |
| Chunk | 分块 | 分塊 |
| Session / Conversation | 会话 | 會話 |
| Citation | 引用 | 引用 |
| Source | 来源 | 來源 |
| Deep Thinking | 深度思考 | 深度思考 |
| Intent Tree | 意图树 | 意圖樹 |
| Role | 角色 | 角色 |
| Department | 部门 | 部門 |
| Trace | 追踪 | 追蹤 |

## 操作动词

| EN | zh-CN | zh-HK |
|---|---|---|
| Save | 保存 | 儲存 |
| Delete | 删除 | 刪除 |
| Upload | 上传 | 上載 |
| Download | 下载 | 下載 |
| Share | 共享 | 共享 |
| Send | 发送 | 傳送 |

## 品牌

| EN | zh-CN | zh-HK |
|---|---|---|
| HT KnowledgeBase | 华泰知识坊 | 華泰知識坊 |

## 维护规则

- 新业务术语在 PR 前必须三语补齐
- zh-HK 使用**港字标准**（"裡"不是"裏"、"為"不是"爲"、"着"不是"著"）
- 母语审校人员名单：__TBD 待 P5 前补充__
```

- [ ] **Step 2: Commit**

```bash
git add docs/dev/i18n/glossary.md
git commit -m "docs(i18n): add trilingual glossary [P4]"
git tag violet-aurora-p4
```

---

## Phase 5 · 翻译内容 + key 替换

**目标**：把业务组件里的硬编码中文抽成 `t()` 调用；填充 en / zh-HK 全量翻译。

> 本 Phase 是机械劳动，采用"一个组件一个 commit"节奏。以下只列代表性 Task，其他同类组件照抄模式。

### Task 5.1 · WelcomeScreen 替换为 t()

**Files:**
- Modify: `frontend/src/components/chat/WelcomeScreen.tsx`

- [ ] **Step 1: 在文件顶部 import useTranslation**

```tsx
import { useTranslation } from "react-i18next";
```

- [ ] **Step 2: 在组件内 hook**

```tsx
const { t } = useTranslation("chat");
```

- [ ] **Step 3: 替换硬编码字符串**

| 原文 | 替换为 |
|---|---|
| `HT · 知识坊 · № 042` | `{t("welcome.kicker")}` |
| `把问题` | `{t("welcome.title_line_1")}` |
| `变成` | `{t("welcome.title_line_2_before")}` |
| `清晰的答案` | `{t("welcome.title_line_2_accent")}` |
| `结构化检索 · 多源互证 · 深度推理` | `{t("welcome.subtitle")}` |
| chip 的 `· 内容总结 / 任务拆解 / 争议条款对比` | 来自 `suggestions` 数据则不动；硬编码则换 `t("suggestions.summary")` 等 |

- [ ] **Step 4: dev 目测切到 en / zh-HK**

zh-CN 显示正常；en / zh-HK 显示 key（如 `welcome.kicker`）—— 这是预期，下个 Task 填 en 翻译。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/WelcomeScreen.tsx
git commit -m "feat(i18n): WelcomeScreen use t() for all strings [P5]"
```

---

### Task 5.2 · ChatInput / Sidebar / MessageItem / ThinkingIndicator / Sources 替换

**Files:**
- Modify: `frontend/src/components/chat/{ChatInput,MessageItem,ThinkingIndicator,Sources}.tsx`
- Modify: `frontend/src/components/layout/Sidebar.tsx`

- [ ] **Step 1-5: 每个组件重复 Task 5.1 的 5 步流程**

优先替换对象：
- ChatInput: `想问点什么...` → `t("input.placeholder")`；`深度思考` → `t("input.deep_thinking")`
- Sidebar: `新建对话` → `t("session.new_chat")`；`从空白开始` → `t("session.new_chat_hint")`；`华泰知识坊` → `t("common:brand_name")`（注意跨 namespace 用冒号）
- MessageItem: `停止生成` → `t("stop")` 等
- ThinkingIndicator: `深度思考` → `t("thinking.label")`；`{{count}} 片段召回` 用 interpolation
- Sources: `来源` → `t("sources.label")`

每改完一个组件 commit 一次：

```bash
git commit -m "feat(i18n): <ComponentName> use t() [P5]"
```

---

### Task 5.3 · Admin / Access / Errors 文本替换

**Files:**
- Modify: `frontend/src/pages/admin/**/*.tsx`

- [ ] **Step 1: 列出所有硬编码中文所在文件**

```bash
cd frontend && grep -rln "知识库\|部门\|角色\|删除\|保存\|取消" src/pages/admin/ src/components/admin/
```

- [ ] **Step 2: 逐文件替换**

按 admin.json / access.json / errors.json 已定义的 key 替换。未定义的 key，先补进对应 zh-CN json 再用。

每个文件一个 commit：

```bash
git commit -m "feat(i18n): <Page> use t() [P5]"
```

---

### Task 5.4 · en 全量翻译

**Files:**
- Modify: `frontend/src/locales/en/{common,chat,admin,access,errors}.json`

- [ ] **Step 1: 逐 namespace 翻译（用术语表保证一致）**

`en/common.json`:

```json
{
  "brand_name": "HT Knowledge Studio",
  "brand_issue_label": "№ 042",
  "actions": {
    "save": "Save",
    "cancel": "Cancel",
    "delete": "Delete",
    "confirm": "Confirm",
    "close": "Close",
    "back": "Back",
    "refresh": "Refresh",
    "search": "Search",
    "new": "New",
    "edit": "Edit",
    "logout": "Log out"
  },
  "language": {
    "menu_label": "Language",
    "zh-CN": "简体中文",
    "zh-HK": "繁體中文",
    "en": "English"
  },
  "nav": {
    "spaces": "Spaces",
    "chat": "Chat",
    "admin": "Admin"
  }
}
```

`en/chat.json`（示例关键段）：

```json
{
  "welcome": {
    "kicker": "HT · KNOWLEDGE STUDIO",
    "title_line_1": "Turn questions into",
    "title_line_2_before": "",
    "title_line_2_accent": "clear answers",
    "subtitle": "Structured retrieval · multi-source grounding · deep reasoning"
  },
  "input": {
    "placeholder": "Ask anything...",
    "deep_thinking": "Deep thinking",
    "send_aria": "Send",
    "hint_send": "Enter to send",
    "hint_newline": "Shift + Enter for newline"
  }
}
```

（其余按 zh-CN 结构全量翻译，保持 key 对齐。）

- [ ] **Step 2: 切 en 目测所有页面**

启动 dev，切 en，逐页查看有无 key 泄漏（显示 `namespace.key` 原样）；有则补 key。

- [ ] **Step 3: 按钮 / tag 在 en 下的溢出检查**

特别关注：Sidebar menu item（侧栏 280px 宽）、Dashboard KPI label、表格 TableHead、对话 suggestion chips。
超出的位置加 `truncate` / `max-w-[Npx]` / `text-xs`。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/locales/en/
git commit -m "feat(i18n): full English translations [P5]"
```

---

### Task 5.5 · zh-HK 全量翻译（母语审校）

**Files:**
- Modify: `frontend/src/locales/zh-HK/{common,chat,admin,access,errors}.json`

- [ ] **Step 1: 基于 zh-CN 翻译为港字繁体**

关键差异（按 glossary.md）：
- 知识库 → 知識庫
- 文档 → 文件
- 分块 → 分塊
- 会话 → 會話
- 来源 → 來源
- 追踪 → 追蹤
- 发送 → 傳送
- 下载/上传 → 下載/上載
- 保存 → 儲存
- 删除 → 刪除
- 意图 → 意圖
- 为 → 為（不是爲）
- 里 → 裡（不是裏）
- 着 → 着（港 = 着；台 = 著）

- [ ] **Step 2: 交母语审校**

【⚠️ 人工 gate】把 `frontend/src/locales/zh-HK/*.json` + glossary.md 发给指定的港同事审阅。收到签字确认后才推进。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/locales/zh-HK/ docs/dev/i18n/glossary.md
git commit -m "feat(i18n): full zh-HK translations (HK reviewer: <NAME>) [P5]"
git tag violet-aurora-p5
```

---

## Phase 6 · 长尾管理后台

**目标**：剩余管理后台页面（access / intent-tree / traces / evaluations / ingestion / knowledge / settings / query-term-mapping / sample-questions）完成换装 + i18n。

### Task 6.1 · 列出所有长尾文件

**Files:** 无改动

- [ ] **Step 1: 列出待改页**

```bash
find frontend/src/pages/admin -name "*.tsx" -not -path "*/dashboard/*" -not -name "AdminLayout.tsx"
```

- [ ] **Step 2: 制作清单**

按目录分组记录在这里（以实际输出为准）：
- `admin/access/**`
- `admin/intent-tree/**`
- `admin/traces/**`
- `admin/evaluations/**`
- `admin/ingestion/**`
- `admin/knowledge/**`
- `admin/settings/**`
- `admin/query-term-mapping/**`
- `admin/sample-questions/**`

---

### Task 6.2 · 按页换装（模式化）

**Files:** 上述每个文件夹内的 tsx 文件

每个文件都套用以下模式（按 P1-P3 已建立的样式约定）：

- [ ] **对每个页面重复以下步骤**：

1. 页面顶部 kicker / 标题：
   ```tsx
   <div className="font-mono text-[10px] uppercase tracking-[2px] text-vio-accent-2">{kicker}</div>
   <h1 className="mt-2 font-display text-[28px] font-medium tracking-[-0.02em] text-vio-ink">{title}</h1>
   ```

2. 所有 `bg-white` → `bg-[var(--vio-surface)]`（可用 batch sed 辅助但需人工 review）
3. 所有 `border-gray-*` → `border-vio-line`
4. 所有 `text-gray-500/600/700` → `text-vio-ink/60` 或 `/70`
5. 所有蓝色 CTA `bg-blue-500` 等 → `bg-[var(--vio-accent)]`
6. 按 P3 Task 3.3 已改的 `ui/table.tsx`，表格自然继承新样式；只需检查页面特定覆盖
7. 硬编码中文换 `t()`（按 P5 模式）

每 1-2 个页面 commit 一次：

```bash
git commit -m "feat(<domain>): violet aurora + i18n for <page> [P6]"
```

**注**：页面很多时按域并行 —— access / intent-tree / traces 可分 3 个 PR / 3 个分支并行。

- [ ] **Step 3: P6 全量目测**

启动 dev，超级管理员 + 部门管理员两个角色分别过全站，确认：
- 无蓝色漏网
- 无 key 泄漏
- zh-HK / en 切换无溢出

- [ ] **Step 4: Tag**

```bash
git tag violet-aurora-p6
```

---

## Phase 7 · 清理

**目标**：删除双写阶段保留的旧 token；写入 gradient budget 约束到项目文档。

### Task 7.1 · 删除旧 token

**Files:**
- Modify: `frontend/src/styles/globals.css`
- Modify: `frontend/tailwind.config.cjs`

- [ ] **Step 1: grep 确认旧 token 无业务引用**

```bash
cd frontend && grep -rn "bg-primary\|text-brand\|from-indigo\|to-blue\|Space Grotesk\|bg-\[#DBEAFE\]\|text-\[#2563EB\]" src/
```

Expected: 只在 `ui/*.tsx` 的 shadcn 原子层（`button.tsx` / `input.tsx` 等）里还可能出现 `bg-primary`；业务组件 / 页面层应无。

- [ ] **Step 2: 删除 `globals.css` 里的旧变量**

定位并删除：
- `--primary`, `--primary-foreground`, `--secondary`, `--accent` 等原 shadcn 变量（如确认 shadcn 组件已不用；若 shadcn 仍依赖，保留并把值 remap 到 vio 对应色）
- `--brand*` 族全部
- `--accent-primary / --accent-secondary / --accent-light / --accent-hover`
- `--sidebar-bg / --sidebar-item-*` 族
- `--gradient-primary / --gradient-light`
- `--dsw-*` 族
- `--bg-primary/secondary/tertiary/hover/active`（如已全换）
- `--shadow-xs/sm/md/lg/xl`（替换为 `--vio-shadow-paper/halo`）
- `--text-primary/secondary/tertiary/muted/on-accent`
- `--border-default/light/focus/accent`
- `--chat-user / --chat-assistant / --glow`

保留的（shadcn 兼容层可能仍需）：
- `--foreground / --background` —— 但把值改为 `hsl from --vio-ink / --vio-surface`

- [ ] **Step 3: 删除 `tailwind.config.cjs` 里的旧 colors**

删除 `colors` 块里：
- `brand.*` 族
- `surface.*` 族（与 vio 冲突；如有业务引用 `bg-surface` 等先全局替换为 `bg-vio-surface`）
- `ink.*` 族（同上，替换为 `bg-vio-ink` / `text-vio-ink`）
- `line.*` 族（同上）
- `danger.*` 族（保留 `vio-danger`）

删除 `fontFamily.display/body/mono` 的旧值，全换 vio 版本（Fraunces / Inter Tight / JetBrains Mono）。

**保留**：shadcn 变量 alias（primary / secondary / accent / muted 等），只把其 CSS var 值 remap 到 vio。

- [ ] **Step 4: tsc + build + test 全绿**

```bash
cd frontend && ./node_modules/.bin/tsc --noEmit && npm run build && npm run test
```

- [ ] **Step 5: dev 全局目测（zh-CN / en / zh-HK 各一遍）**

- [ ] **Step 6: Commit**

```bash
git add frontend/src/styles/globals.css frontend/tailwind.config.cjs
git commit -m "refactor(frontend): remove legacy blue tokens; Space Grotesk dead [P7]"
```

---

### Task 7.2 · 写入 CLAUDE.md + gotchas.md

**Files:**
- Modify: `frontend/CLAUDE.md`
- Modify: `docs/dev/gotchas.md`

- [ ] **Step 1: 在 `frontend/CLAUDE.md` 末尾新增章节**

```markdown
## Violet Aurora 设计系统（2026-04 起）

全站视觉语言：`docs/superpowers/specs/2026-04-22-frontend-violet-aurora-design.md`

### Token 速查

- 底 `var(--vio-surface)` / 侧边 `var(--vio-surface-2)` / 墨 `var(--vio-ink)` / 线 `var(--vio-line)`
- 主 `var(--vio-accent) #5B4BE8` / 二 `var(--vio-accent-2)` / 浅 `var(--vio-accent-subtle)` / 雾 `var(--vio-accent-mist)`
- Gradient `var(--vio-aurora)` **受约束使用**
- Tailwind class: `bg-vio-surface`, `text-vio-ink`, `border-vio-line`, `bg-[var(--vio-accent-mist)]` 等

### Gradient Budget（硬约束）

`.vio-aurora-*` 类只允许在以下 6 个 signature moment 出现：
1. `WelcomeScreen` hero "清晰的答案" 标题
2. `WelcomeScreen` 两团角落 halo
3. `ChatInput` focus 外发光
4. 主 `CitationBadge` (n=1)
5. `Sidebar` HT monogram Logo
6. `Sidebar` "新建对话" 卡 halo + 流式 cursor trail

**新增使用位置必须先更新 spec §4 白名单；Code review 会严查。**

### 字体

- Display: `font-display`（Fraunces + Noto Serif CJK）
- Body: `font-body`（Inter Tight + Noto Sans CJK）
- Meta / Code: `font-mono`（JetBrains Mono）
- 按 `html[lang]` 自动切 CJK fallback（见 `globals.css`）

### i18n

- 库：react-i18next；入口 `src/i18n/index.ts`
- 资源：`src/locales/{zh-CN,zh-HK,en}/{common,chat,admin,access,errors}.json`
- 切换：`<LanguageSwitcher />` 在 Sidebar 底部
- 持久化：`localStorage.htkb.locale`
- 术语表：`docs/dev/i18n/glossary.md` — 新术语 PR 前必须三语补齐
- zh-HK 用港字标准（"裡""為""着"）；翻译必须母语审校
```

- [ ] **Step 2: 在 `docs/dev/gotchas.md` 对应分组追加**

（该文件有 7 大主题分组。找到 "前端" 分组或最后一节，新增条目：）

```markdown
- **Violet Aurora gradient budget（2026-04 起）**：`.vio-aurora-*` 类只允许在 6 个 signature moment 使用 —— `WelcomeScreen` hero 标题 / halo / `ChatInput` focus / 主 `CitationBadge` / Sidebar Logo + 新建对话卡。新增位置必须先改 spec §4 白名单。违反约束的 PR 会被 reject。参考：`docs/superpowers/specs/2026-04-22-frontend-violet-aurora-design.md` + `frontend/src/styles/aurora.css` 注释头部。
- **Violet Aurora 字体 + lang 联动**：`html[lang]` 切换时 CSS `:lang()` 选择器自动切 display / body fallback 链。如果 `<html lang>` 与 i18n 语言不同步（比如 login 页进来前 i18n 还没 init），字体会走默认 zh-CN 链。root cause 检查：`src/i18n/index.ts` 里 `on('initialized')` 和 `on('languageChanged')` 两个回调都同步 `document.documentElement.lang`。
- **zh-HK 翻译不等于 zh-TW**：港字标准（裡/為/着）不同于台字（裏/爲/著）。翻译时必须按 `docs/dev/i18n/glossary.md` 术语表，并交港同事母语审校。不要用机器繁简转换。
```

- [ ] **Step 3: Commit**

```bash
git add frontend/CLAUDE.md docs/dev/gotchas.md
git commit -m "docs(frontend): Violet Aurora design system notes + gradient budget gotchas [P7]"
git tag violet-aurora-p7
```

---

## 验收检查清单（对照 spec）

### 视觉
- [ ] 欢迎屏与 `.superpowers/brainstorm/.../violet-aurora.html` 人眼对齐
- [ ] `grep -rn "from-indigo\|to-blue\|bg-primary\|text-brand\|Space Grotesk" frontend/src/` 只命中 shadcn ui/ 原子文件（不命中业务组件/页面）
- [ ] 6 个 signature moment 全部落地，无多余 gradient 使用

### 交互
- [ ] 输入框 focus 雾紫 halo
- [ ] Citation badge `[1]` gradient，`[2]+` 实色紫
- [ ] 流式 cursor trail

### i18n
- [ ] zh-CN / zh-HK / en 切换无乱码、无 key 泄漏
- [ ] 按钮 / menu 在 en 不溢出
- [ ] `document.documentElement.lang` 正确切换
- [ ] HK 母语审校签字

### 性能
- [ ] Lighthouse FCP 退化 < 200ms
- [ ] DevTools Network 确认字体 `font-display: swap` + 按需加载 locale

### 可访问性
- [ ] Gradient 文字有 fallback `color`
- [ ] 紫色文字对比度 ≥ WCAG AA
- [ ] LanguageSwitcher 有 `aria-label` + 键盘可达

---

## 风险点速查（对照 spec §风险与缓解）

| 风险 | 本计划内如何缓解 |
|---|---|
| 字体加载首屏退化 | P0 Task 0.3 preconnect + preload + `font-display: swap`；Task 0.2 全局 `font-feature-settings` |
| gradient 被滥用 | P0 Task 0.4 在 `aurora.css` 文件头注释硬编码白名单；P7 Task 7.2 写入 CLAUDE.md + gotchas；单测锁 CitationBadge 契约（P1 Task 1.1） |
| zh-HK 没人审校 | P5 Task 5.5 设置人工 gate（reviewer signature） |
| dense 管理后台太软 | P3 Task 3.3 表格偶数行雾底（密度足够） + P6 目测每页调密度 |
| `.dark` dead code 破坏组件 | P0 Task 0.2 前先 grep 确认；P2 Task 2.4 也会再扫一次 |
| Fraunces Windows 锯齿 | P0 Task 0.2 全局 antialiased |
