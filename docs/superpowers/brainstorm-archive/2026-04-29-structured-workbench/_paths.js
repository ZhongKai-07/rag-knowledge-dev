// Brainstorm archive · 单线性故事模式跳转
// 每页 </body> 之前: <script src="_paths.js"></script>
// 三种模式:📖 叙事顺序 / 🎯 领导汇报版 / 📋 业务分享版
// 模式存 localStorage,index 跳转可带 ?mode=exec 强制设置

const STORAGE_KEY = 'wb-archive-mode';

const PATHS = {
  narrative: {
    name: '叙事顺序',
    icon: '📖',
    pages: [
      { id: 'product-shape.html', label: '01 产品形态' },
      { id: 'paas-vs-workbench.html', label: '03 PaaS vs 工作台' },
      { id: '08-five-categories.html', label: '08 五分法' },
      { id: 'task-vs-agent.html', label: '02 Task vs Agent' },
      { id: 'ux-task-hidden.html', label: '06 UX 术语锁定' },
      { id: 'v1-scope-and-paths.html', label: '05 v1 范围 + 路径' },
      { id: '10-v1-v2-v3-scope.html', label: '10 v1/v2/v3 切分' },
      { id: '11-pilot-permission.html', label: '11 试点权限' },
      { id: '14-dual-admin.html', label: '14 双级 admin' },
      { id: 'continual-learning.html', label: '04 持续学习' },
      { id: '12-three-pillars.html', label: '12 三件事' },
      { id: '13-self-improving-engine.html', label: '13 Self-improving' },
      { id: '16-ux-home.html', label: '16 工作台首屏' },
      { id: '17-ux-knowledge-qa.html', label: '17 知识问答' },
      { id: '18-ux-doc-handling.html', label: '18 文档处理' },
      { id: '19-ux-insight-board.html', label: '19 Insight 看板' },
      { id: '20-ux-proposal-inbox.html', label: '20 提议箱' },
      { id: '09-cmd-troubleshoot-arch.html', label: '09 复杂指令/排查' },
      { id: '15-ledger-schema.html', label: '15 Ledger schema' },
      { id: '21-overall-loop.html', label: '21 整体闭环' }
    ]
  },
  exec: {
    name: '领导汇报版',
    icon: '🎯',
    pages: [
      { id: 'paas-vs-workbench.html', label: '03 PaaS vs 工作台' },
      { id: '08-five-categories.html', label: '08 五分法' },
      { id: '13-self-improving-engine.html', label: '13 Self-improving' },
      { id: '19-ux-insight-board.html', label: '19 Insight 看板' },
      { id: '21-overall-loop.html', label: '21 整体闭环' }
    ]
  },
  biz: {
    name: '业务分享版',
    icon: '📋',
    pages: [
      { id: 'paas-vs-workbench.html', label: '03 PaaS vs 工作台' },
      { id: '08-five-categories.html', label: '08 五分法' },
      { id: 'ux-task-hidden.html', label: '06 UX 术语锁定' },
      { id: '12-three-pillars.html', label: '12 三件事' },
      { id: '13-self-improving-engine.html', label: '13 Self-improving' },
      { id: '16-ux-home.html', label: '16 工作台首屏' },
      { id: '17-ux-knowledge-qa.html', label: '17 知识问答' },
      { id: '18-ux-doc-handling.html', label: '18 文档处理' },
      { id: '19-ux-insight-board.html', label: '19 Insight 看板' },
      { id: '20-ux-proposal-inbox.html', label: '20 提议箱' }
    ]
  }
};

// 叙事模式下显示的章节信息(只在叙事模式生效)
const NARRATIVE_CHAPTERS = [
  { num: '章 1', name: '困境与立意', start: 0, end: 2 },
  { num: '章 2', name: '架构骨架', start: 3, end: 4 },
  { num: '章 3', name: 'v1 范围与治理', start: 5, end: 8 },
  { num: '章 4', name: 'Self-improving 闭环', start: 9, end: 11 },
  { num: '章 5', name: '看见产品', start: 12, end: 16 },
  { num: '终幕', name: '深潜与闭环', start: 17, end: 19 }
];

// 注入 CSS
(function injectCss() {
  const style = document.createElement('style');
  style.textContent = `
    .wb-navbar {
      background: var(--bg-secondary);
      border-bottom: 1px solid var(--border);
      padding: 0.5rem 1.25rem;
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 1rem;
      align-items: center;
      font-size: 0.8rem;
      color: var(--text-secondary);
    }
    .wb-navbar.offpath {
      background: rgba(255,159,10,0.06);
      border-bottom-color: rgba(255,159,10,0.25);
    }
    .wb-mode-toggle {
      display: inline-flex;
      background: var(--bg-tertiary);
      border-radius: 6px;
      padding: 2px;
      gap: 1px;
    }
    .wb-mode-btn {
      background: transparent;
      border: none;
      padding: 3px 8px;
      font-size: 0.78rem;
      color: var(--text-secondary);
      border-radius: 4px;
      cursor: pointer;
      font-family: inherit;
      line-height: 1.2;
    }
    .wb-mode-btn:hover { color: var(--text-primary); }
    .wb-mode-btn.active {
      background: var(--bg-secondary);
      color: var(--text-primary);
      box-shadow: 0 1px 2px rgba(0,0,0,0.06);
    }
    .wb-mode-btn .wb-mode-icon { margin-right: 3px; }
    .wb-mode-btn .wb-mode-pos {
      color: var(--text-tertiary);
      font-size: 0.72rem;
      margin-left: 3px;
    }
    .wb-mode-btn.active .wb-mode-pos { color: var(--accent); font-weight: 500; }

    .wb-pos {
      text-align: center;
      color: var(--text-secondary);
    }
    .wb-pos .wb-pos-mode {
      color: var(--text-primary);
      font-weight: 500;
      margin-right: 6px;
    }
    .wb-pos .wb-pos-chapter {
      color: var(--text-secondary);
    }
    .wb-pos .wb-pos-frac {
      color: var(--text-tertiary);
      font-size: 0.74rem;
      margin-left: 4px;
    }

    .wb-jump {
      display: flex;
      gap: 0.6rem;
      align-items: center;
      justify-content: flex-end;
      white-space: nowrap;
    }
    .wb-jump a {
      color: var(--accent);
      text-decoration: none;
      font-size: 0.8rem;
    }
    .wb-jump a:hover { text-decoration: underline; }
    .wb-jump .wb-sep { color: var(--text-tertiary); font-size: 0.74rem; }
    .wb-jump .wb-end {
      color: var(--text-tertiary);
      font-size: 0.76rem;
      font-style: italic;
    }

    .wb-offpath-hint {
      color: var(--text-secondary);
      font-style: italic;
    }

    @media (max-width: 800px) {
      .wb-navbar { grid-template-columns: 1fr; gap: 0.5rem; }
      .wb-pos { text-align: left; }
      .wb-jump { justify-content: flex-start; }
    }
  `;
  document.head.appendChild(style);
})();

function getMode() {
  const url = new URLSearchParams(window.location.search);
  const param = url.get('mode');
  if (param && PATHS[param]) {
    localStorage.setItem(STORAGE_KEY, param);
    // 清理 URL,避免分享/书签时带参数
    history.replaceState(null, '', window.location.pathname + window.location.hash);
    return param;
  }
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored && PATHS[stored] ? stored : 'narrative';
}

function setMode(mode) {
  if (!PATHS[mode]) return;
  localStorage.setItem(STORAGE_KEY, mode);
  render();
}

function currentPageId() {
  return (window.location.pathname.split('/').pop() || '').toLowerCase();
}

function getNarrativeChapter(narrativeIdx) {
  for (const c of NARRATIVE_CHAPTERS) {
    if (narrativeIdx >= c.start && narrativeIdx <= c.end) return c;
  }
  return null;
}

function renderModeToggle(currentMode, currentPage) {
  return Object.entries(PATHS).map(([key, path]) => {
    const idx = path.pages.findIndex(p => p.id === currentPage);
    const total = path.pages.length;
    const inPath = idx !== -1;
    const active = key === currentMode ? 'active' : '';
    const posText = inPath ? `${idx + 1}/${total}` : '—';
    const title = inPath
      ? `${path.icon} ${path.name} · 当前位置 ${idx + 1}/${total}`
      : `${path.icon} ${path.name} · 本页不在此路径(共 ${total} 张)`;
    return `<button class="wb-mode-btn ${active}" onclick="window.wbSetMode('${key}')" title="${title}">
      <span class="wb-mode-icon">${path.icon}</span>${path.name}<span class="wb-mode-pos">${posText}</span>
    </button>`;
  }).join('');
}

function renderNavBar(currentMode) {
  const cur = currentPageId();
  if (!cur) return '';

  const path = PATHS[currentMode];
  const idx = path.pages.findIndex(p => p.id === cur);

  const toggleHtml = `<div class="wb-mode-toggle">${renderModeToggle(currentMode, cur)}</div>`;

  // 不在该路径
  if (idx === -1) {
    return `<div class="wb-navbar offpath">
      ${toggleHtml}
      <span class="wb-pos">
        <span class="wb-pos-mode">${path.icon} ${path.name}</span>
        <span class="wb-offpath-hint">本页不在此路径中</span>
      </span>
      <span class="wb-jump">
        <a href="#" onclick="window.wbSetMode('narrative');return false;">切回叙事顺序 →</a>
      </span>
    </div>`;
  }

  const prev = idx > 0 ? path.pages[idx - 1] : null;
  const next = idx < path.pages.length - 1 ? path.pages[idx + 1] : null;
  const total = path.pages.length;

  // 位置文本:叙事模式下额外显示章节
  let posHtml;
  if (currentMode === 'narrative') {
    const chap = getNarrativeChapter(idx);
    const chapText = chap ? `${chap.num} · ${chap.name}` : '';
    posHtml = `<span class="wb-pos">
      <span class="wb-pos-mode">${path.icon} ${path.name}</span>
      <span class="wb-pos-chapter">${chapText}</span>
      <span class="wb-pos-frac">第 ${idx + 1}/${total} 张</span>
    </span>`;
  } else {
    posHtml = `<span class="wb-pos">
      <span class="wb-pos-mode">${path.icon} ${path.name}</span>
      <span class="wb-pos-frac">第 ${idx + 1}/${total} 张</span>
    </span>`;
  }

  const prevHtml = prev
    ? `<a href="${prev.id}">← ${prev.label}</a>`
    : `<span class="wb-end">— 起点 —</span>`;
  const nextHtml = next
    ? `<a href="${next.id}">${next.label} →</a>`
    : `<span class="wb-end">— 终点 —</span>`;

  return `<div class="wb-navbar">
    ${toggleHtml}
    ${posHtml}
    <span class="wb-jump">${prevHtml}<span class="wb-sep">|</span>${nextHtml}</span>
  </div>`;
}

// 找出旧的硬编码 nav 条(在 archive-header 紧后,含"第 N/20 张"特征)
function findInlineNav(archiveHeader) {
  let next = archiveHeader.nextElementSibling;
  while (next) {
    if (next.id === 'claude-content') return null;
    if (next.classList && next.classList.contains('wb-navbar')) return null;
    if (next.tagName === 'DIV') {
      const txt = next.innerHTML || '';
      if (/第\s*\d+\s*\/\s*20\s*张/.test(txt) || /ch-tag/.test(txt)) return next;
    }
    next = next.nextElementSibling;
  }
  return null;
}

function render() {
  const archiveHeader = document.querySelector('.archive-header');
  if (!archiveHeader) return;

  // 移除老版的 path-switcher(三行并列)
  const oldSwitcher = document.querySelector('.path-switcher');
  if (oldSwitcher) oldSwitcher.remove();

  // 隐藏每页硬编码的章节 nav 条
  const inlineNav = findInlineNav(archiveHeader);
  if (inlineNav) inlineNav.style.display = 'none';

  // 找/创建 wb-navbar
  let navBar = document.querySelector('.wb-navbar');
  const html = renderNavBar(getMode());
  if (!html) return;

  if (navBar) {
    navBar.outerHTML = html;
  } else {
    const wrapper = document.createElement('div');
    wrapper.innerHTML = html;
    archiveHeader.insertAdjacentElement('afterend', wrapper.firstElementChild);
  }
}

document.addEventListener('DOMContentLoaded', () => {
  window.wbSetMode = setMode;
  render();
});
