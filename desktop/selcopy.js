/* 选中即可复制（v1.46）—— 答案面板 / 翻译面板共用
 *
 * 为什么需要这个（先量过再做的）：
 *   灵珠的面板是 `focusable:false` 的纯 HUD（刻意不抢焦点，见 main.js 的注释）。实测（合成鼠标 + CDP）：
 *     · 拖拽划选**是好的** —— 鼠标事件照常到达，#wrap 里的文字能选中，::selection 也能上色；
 *     · 但 ⌘C / ⌘A **永远收不到** —— 窗口不能成为 key window，键盘事件根本不会送进来（探针里 keys=[]、剪贴板纹丝不动）。
 *   所以「选中可复制」不能指望系统快捷键，必须给一个只用鼠标就能完成的入口。
 *
 * 交互照抄的是自家 dsh 的划词浮层规范（dsh-tauri/deepseek-harness-desktop#540）：
 *   「selectionchange + pointerup/keyup 合成判定，延后约 120ms 出现，避免拖选过程中闪烁；纯空白选区不弹出」，
 *   浮层默认落在**选区上方**（上方放不下才翻到下方），选区清空 / 滚动 / Esc / 点浮层外即消失。
 *   和 Medium / Notion / OnlyOffice 的浮动工具条是同一套习惯，用户不用学。
 *
 * 实现要点：
 *   1) 只在 #wrap 内的选区上出现（标题栏、语言下拉、悬停释义气泡都不弹）。
 *   2) 气泡的 mousedown 要 preventDefault —— 否则这一下就把选区清了，复制到的是空串。
 *   3) 复制走 window.orbApi.copy()（主进程 clipboard.writeText），而不是 navigator.clipboard：
 *      后者要求文档处于聚焦状态，非聚焦窗口里会直接 reject。
 *   4) 气泡挂在 .panel 上（那层是 position:relative），坐标按 .panel 的边框盒换算 —— 跟 #wtip 同一套算法。
 */
;(function () {
  const DELAY = 120            // 拖选过程中不闪：松手/选区稳定后再等这么久
  const FLASH = 1200           // 「已复制」保持多久
  const PANEL_PAD = 6          // 气泡离面板边缘的留白

  const wrap = document.getElementById('wrap')
  const panel = document.querySelector('.panel')
  if (!wrap || !panel) return

  let pill = null, showTimer = null, flashTimer = null
  let selText = ''             // 出现时那一刻的选区文本（复制就用它，避免半路被改）
  let copiedText = ''          // 刚复制过的那段 —— 「已复制」只对它显示，换选区才收回去
  let dragging = false

  const orb = window.orbApi
  const canCopy = !!(orb && orb.copy)

  function build() {
    if (pill) return pill
    pill = document.createElement('div')
    pill.className = 'selcopy'
    pill.innerHTML = '<svg class="i-copy" viewBox="0 0 16 16" aria-hidden="true">'
      + '<rect x="5.9" y="5.9" width="7.3" height="7.3" rx="1.6"/>'
      + '<path d="M10.2 5.9V4.6a1.7 1.7 0 0 0-1.7-1.7H4.6a1.7 1.7 0 0 0-1.7 1.7v3.9a1.7 1.7 0 0 0 1.7 1.7h1.3"/></svg>'
      + '<span class="t">复制</span>'
    // 关键：不让这一下 mousedown 落到文档上 —— 否则默认行为会把选区清掉
    pill.addEventListener('mousedown', (e) => { e.preventDefault(); e.stopPropagation() })
    pill.addEventListener('click', (e) => { e.stopPropagation(); doCopy() })
    panel.appendChild(pill)
    return pill
  }

  function hide() {
    clearTimeout(showTimer); showTimer = null
    clearTimeout(flashTimer); flashTimer = null
    copiedText = ''
    if (pill) {
      pill.classList.remove('on', 'ok')
      const t = pill.querySelector('.t')
      if (t) t.textContent = '复制'
    }
    selText = ''
  }

  function currentSel() {
    const s = window.getSelection()
    if (!s || s.isCollapsed || !s.rangeCount) return null
    const r = s.getRangeAt(0)
    const text = r.toString()
    if (!text.trim()) return null                                   // 纯空白选区不弹
    if (!wrap.contains(r.commonAncestorContainer) && !wrap.contains(r.startContainer)) return null
    const rects = r.getClientRects()
    if (!rects.length) return null
    // 多行选区取整体外接框：气泡居中挂在选区上方
    let l = Infinity, t = Infinity, rt = -Infinity, b = -Infinity
    for (const x of rects) {
      if (x.width === 0 && x.height === 0) continue
      l = Math.min(l, x.left); t = Math.min(t, x.top)
      rt = Math.max(rt, x.right); b = Math.max(b, x.bottom)
    }
    if (l === Infinity) return null
    return { text, left: l, top: t, right: rt, bottom: b }
  }

  function place(sel) {
    const el = build()
    el.classList.add('on')                                          // 先显示才能量到自身尺寸
    const pr = panel.getBoundingClientRect()
    const w = el.offsetWidth, h = el.offsetHeight
    const cx = (sel.left + sel.right) / 2 - pr.left
    let x = cx - w / 2
    x = Math.max(PANEL_PAD, Math.min(x, pr.width - w - PANEL_PAD))
    let y = sel.top - pr.top - h - 6                                // 默认贴选区上方
    if (y < PANEL_PAD) y = sel.bottom - pr.top + 6                  // 上方放不下才翻到下方
    y = Math.max(PANEL_PAD, Math.min(y, pr.height - h - PANEL_PAD))
    el.style.left = Math.round(x) + 'px'
    el.style.top = Math.round(y) + 'px'
  }

  function setLabel(ok) {
    const el = build()
    el.classList.toggle('ok', ok)
    const t = el.querySelector('.t')
    if (t) t.textContent = ok ? '已复制' : '复制'
  }

  function refresh() {
    showTimer = null
    const sel = currentSel()
    if (!sel) { hide(); return }
    const changed = sel.text !== selText
    selText = sel.text
    build()
    // 「已复制」只压在同一段选区上：换了选区才收回 —— 否则点气泡那一下自带的 mouseup
    // 会再触发一次 refresh，把刚写上的「已复制」当场抹掉（实测踩过）。
    if (changed || selText !== copiedText) setLabel(false)
    place(sel)
  }

  function schedule() {
    clearTimeout(showTimer)
    showTimer = setTimeout(refresh, DELAY)
  }

  function doCopy() {
    if (!canCopy || !selText) return
    try { orb.copy(selText) } catch (e) { return }
    copiedText = selText
    setLabel(true)
    clearTimeout(flashTimer)
    flashTimer = setTimeout(() => {
      if (selText === copiedText) { copiedText = ''; setLabel(false) }
    }, FLASH)
  }

  // —— 出现：selectionchange 是主信号；mouseup 兜底「又拖了一圈选同一段」这种不触发 selectionchange 的情况 ——
  document.addEventListener('selectionchange', schedule)
  document.addEventListener('mouseup', () => { dragging = false; schedule() })
  document.addEventListener('mousedown', (e) => {
    dragging = true
    if (pill && pill.contains(e.target)) return                     // 点气泡自己别自残
    hide()                                                          // 点别处 / 开始新一次拖选：先收掉旧的
  }, true)
  // 拖拽期间上一轮的选区还在（浏览器会自己更新），别让它把气泡闪来闪去
  wrap.addEventListener('scroll', hide, { passive: true })
  window.addEventListener('resize', hide)
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hide() }, true)

  // 给宿主页面用的钩子：正文被整块换掉（新一轮识别 / 重译）时手动收一下；
  // busy() 供翻译面板判断「现在该不该弹悬停查词」——有选区时不该弹。
  window.__selcopy = {
    hide,
    busy: () => dragging || !!selText || (function () {
      const s = window.getSelection()
      return !!(s && !s.isCollapsed && String(s).trim())
    })()
  }
})()
