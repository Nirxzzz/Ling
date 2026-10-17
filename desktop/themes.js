/* 配色预设 + 色系。默认「护眼」= 面板底左上薄荷绿 → 右下粉渐变、深色字。
 * 默认『eye』的字段与旧版（v1.60）逐字一致、渲染变量完全不变；
 * 其余可选配色换成六套青春配色（对齐安卓 Theme.kt 六套口径）：
 *   candy_pop / cyber_pop / fresh_pop / dopamine_pop / acid_green / soft_digital。
 * 六套各自带 palette（paper/card/primary/secondary/highlight/ink/hint，均 RGB 0xRRGGBB）。
 * 旧 id 在读取时由 normId 迁到最近的六套；空 / 未知 → 默认护眼。
 * 主进程（require）拿来校验 / 存 id；各面板页面（<script src>）用 applyTheme 把配色写进 ui.css 的变量。
 * 颜色的含义一律配文字（名字里写明深浅），不靠看颜色。 */
(function () {
  const DEFAULT = 'eye'
  // 主按钮前景：深（沿用旧主按钮字色）/ 浅，按实底亮度二选一
  const BTN_FG_DARK = 0x14202E
  const BTN_FG_LIGHT = 0xFFFFFF

  function rgb(c) { return [(c >> 16) & 255, (c >> 8) & 255, c & 255] }
  function trip(c) { return rgb(c).join(', ') }
  function mix(a, b, t) { const x = rgb(a), y = rgb(b); return (Math.round(x[0] * (1 - t) + y[0] * t) << 16) | (Math.round(x[1] * (1 - t) + y[1] * t) << 8) | Math.round(x[2] * (1 - t) + y[2] * t) }
  /** WCAG 相对亮度 / 对比度 */
  function luminance(c) { return rgb(c).map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4) }).reduce((s, v, i) => s + v * [0.2126, 0.7152, 0.0722][i], 0) }
  function contrast(a, b) { const x = luminance(a), y = luminance(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05) }
  /** 在给定实底上取对比度更高的前景（深 / 浅）—— 主按钮标签用 */
  function foregroundOn(bg) { return contrast(BTN_FG_DARK, bg) >= contrast(BTN_FG_LIGHT, bg) ? BTN_FG_DARK : BTN_FG_LIGHT }
  function isDark(t) { return luminance(t.bg) < 0.2 }
  /** 面板底的实际不透明度：深色底沿用「背景浓度」滑杆；浅色底至少 0.82（深色字要压得住后面的壁纸） */
  function panelAlpha(t, bgAlpha) { const a = bgAlpha == null ? 0.5 : bgAlpha; return isDark(t) ? a : Math.min(0.97, Math.max(0.82, a + 0.35)) }

  /** 默认护眼（字段与旧版逐字一致，含 label；不改渲染） */
  const EYE = { id: DEFAULT, label: '护眼（薄荷绿 → 粉渐变，深色字，默认）', bg: 0xCDEFE0, text: 0x1F2D28, answer: 0x0F1F19, heading: 0x1F3A30, orbA: 0x5FDFB4, orbB: 0xF29FB0, bg2: 0xF7D4DE }

  /** 六套青春配色的精确 RGB 角色（与安卓 ThemePalette 同源）：
   *  paper 阅读底 / card 卡片底 / primary 主色 / secondary 副色 / highlight 高亮 / ink 正文 / hint 弱化文字 */
  const PALETTES = {
    candy_pop: { paper: 0xFFFDF8, card: 0xFFFFFF, primary: 0xFF6BCE, secondary: 0x845EC2, highlight: 0xFFD93D, ink: 0x242038 },
    cyber_pop: { paper: 0xF0F7FF, card: 0xFFFFFF, primary: 0x4361EE, secondary: 0x7209B7, highlight: 0x4CC9F0, ink: 0x171738 },
    fresh_pop: { paper: 0xFFFDF5, card: 0xFFFFFF, primary: 0x38B48B, secondary: 0x7AE7C7, highlight: 0xFFF0A6, ink: 0x183D35 },
    dopamine_pop: { paper: 0xFFF8F0, card: 0xFFFFFF, primary: 0xFF6B6B, secondary: 0xFF9F1C, highlight: 0xFFB3C6, ink: 0x33272A },
    acid_green: { paper: 0xF5F5F0, card: 0xFFFFFF, primary: 0xC6F432, secondary: 0xDFFF00, highlight: 0xA3A3A3, ink: 0x151515 },
    soft_digital: { paper: 0xF8F9FF, card: 0xFFFFFF, primary: 0xA5D8FF, secondary: 0xCDB4DB, highlight: 0xFFC8DD, ink: 0x25243A }
  }
  /** 六套的 id + 中文（English）简洁名，画廊按此顺序 */
  const SIX = [
    ['candy_pop', '糖果 Candy（粉紫 · 奶油底）'],
    ['cyber_pop', '赛博 Cyber（蓝紫 · 浅蓝底）'],
    ['fresh_pop', '清氧 Fresh（薄荷青绿 · 奶油底）'],
    ['dopamine_pop', '多巴胺 Dopamine（珊瑚橙 · 蜜桃底）'],
    ['acid_green', '酸绿 Acid（青柠 · 黑白灰）'],
    ['soft_digital', '柔光 Soft（蓝紫粉 · 浅底）']
  ]
  /** 六套的预设对象：阅读底 = paper，正文 / 答案 / 标题 = ink（可读），
   *  小球渐变 = primary → secondary，面板底另一端 = paper 掺一点副色（浅、仍可读）。 */
  const SIX_PRESETS = SIX.map(([id, label]) => {
    const p = PALETTES[id]
    return {
      id, label,
      bg: p.paper, text: p.ink, answer: p.ink, heading: p.ink,
      orbA: p.primary, orbB: p.secondary,
      bg2: mix(p.paper, p.secondary, 0.18),
      palette: { paper: p.paper, card: p.card, primary: p.primary, secondary: p.secondary, highlight: p.highlight, ink: p.ink, hint: mix(p.ink, p.paper, 0.35) }
    }
  })
  /** 可选配色：默认护眼 + 六套（旧的可选预设对象已全部移除） */
  const PRESETS = [EYE].concat(SIX_PRESETS)

  /** 色系（设置 → 外观里的分组）：每个预设恰好在一个色系里 */
  const FAMILIES = [
    ['默认', [DEFAULT]],
    ['青春', SIX.map(([id]) => id)]
  ]

  /** 旧配色 id → 最近的六套（读取时迁移；不主动改写已存的键）。未知 / 空 → 默认护眼。 */
  const LEGACY = {
    dark: 'cyber_pop', black: 'cyber_pop', night: 'cyber_pop', midnight: 'cyber_pop',
    ocean_deep: 'cyber_pop', ocean_shallow: 'cyber_pop', ocean_sky: 'cyber_pop', morandi_blue: 'cyber_pop',
    light: 'soft_digital', macaron_taro: 'soft_digital', macaron_mix: 'soft_digital',
    morandi_sage: 'fresh_pop', macaron_mint: 'fresh_pop', forest: 'fresh_pop', moss: 'fresh_pop',
    morandi_rose: 'candy_pop', macaron_berry: 'candy_pop',
    morandi_oat: 'dopamine_pop', macaron_lemon: 'dopamine_pop', clay: 'dopamine_pop', sepia: 'dopamine_pop',
    morandi_ink: 'acid_green'
  }
  function preset(id) { return PRESETS.find((p) => p.id === id) || null }
  /** 存着的配色 id → 实际用的：默认 / 六套原样；已知旧 id 迁到最近的六套；空 / 未知 → 默认护眼 */
  function normId(id) { if (preset(id)) return id; return Object.prototype.hasOwnProperty.call(LEGACY, id) ? LEGACY[id] : DEFAULT }
  function familyOf(id) { const i = FAMILIES.findIndex((f) => f[1].includes(normId(id))); return i < 0 ? 0 : i }

  /** 六套额外写入的 palette 变量；切回默认护眼时逐个 remove —— 上一套的实底 / 前景留着就是串色 */
  const PALETTE_VARS = ['--paper', '--card', '--primary', '--secondary', '--highlight', '--ink', '--hint', '--btn-primary-bg', '--btn-primary-fg']

  /** 把配色写进 ui.css 的变量（渲染进程用）。a = 外观设置 { theme, bgAlpha, contrast, fontSize } */
  function applyTheme(root, a) {
    a = a || {}
    const t = preset(normId(a.theme))
    const s = root.style
    const al = panelAlpha(t, a.bgAlpha)
    const dark = isDark(t)
    s.setProperty('--fg', trip(t.text))
    s.setProperty('--bg', trip(t.bg))
    s.setProperty('--bg2', trip(t.bg2 == null ? t.bg : t.bg2))
    s.setProperty('--panel-alpha', String(al))
    s.setProperty('--card-alpha', String(Math.min(0.97, al + (dark ? 0.40 : 0.10))))
    s.setProperty('--mint', trip(t.orbA))
    s.setProperty('--mint-light', trip(mix(t.orbA, 0xFFFFFF, 0.25)))
    s.setProperty('--rose', trip(t.orbB))
    s.setProperty('--rose-light', trip(mix(t.orbB, 0xFFFFFF, 0.25)))
    s.setProperty('--heading', trip(dark ? mix(t.heading, t.orbA, 0.5) : t.heading))
    s.setProperty('--answer', trip(t.answer))
    s.setProperty('--glass-tint', dark ? '.16' : '0')   // 深色底叠一层淡淡的小球色；浅色底本身就是彩色，不叠
    // 六套额外暴露 palette 角色；默认护眼不设、并清掉上一套的，旧变量保持逐字不变
    if (t.palette) {
      const p = t.palette
      s.setProperty('--paper', trip(p.paper))
      s.setProperty('--card', trip(p.card))
      s.setProperty('--primary', trip(p.primary))
      s.setProperty('--secondary', trip(p.secondary))
      s.setProperty('--highlight', trip(p.highlight))
      s.setProperty('--ink', trip(p.ink))
      s.setProperty('--hint', trip(p.hint))
      // 主按钮：ui.css 里是 background / color: var(--btn-primary-…)，不是 rgb(var(…))——
      // 所以这里必须存完整颜色，存裸三元组「67, 97, 238」是无效值，CSS 会整条丢掉。
      s.setProperty('--btn-primary-bg', 'rgb(' + trip(p.primary) + ')')
      s.setProperty('--btn-primary-fg', 'rgb(' + trip(foregroundOn(p.primary)) + ')')
    } else {
      for (const k of PALETTE_VARS) s.removeProperty(k)
    }
    root.classList.toggle('light-theme', !dark)
    root.classList.toggle('palette-theme', !!t.palette)
    root.dataset.theme = t.id
  }

  /** 小卡片页面（追问卡 / 菜单 / 设置 / 提示）：<script src="themes.js" data-auto> 自动取配色、跟着变（字号不动） */
  function autoApply() {
    if (typeof window === 'undefined' || !window.orbApi || !window.orbApi.getAppearance) return
    const go = (a) => { try { applyTheme(document.documentElement, a) } catch {} }
    window.orbApi.getAppearance().then(go).catch(() => {})
    if (window.orbApi.onAppearanceChanged) window.orbApi.onAppearanceChanged(go)
  }
  if (typeof document !== 'undefined' && document.currentScript && document.currentScript.hasAttribute('data-auto')) autoApply()

  const api = { DEFAULT, PRESETS, FAMILIES, preset, normId, familyOf, luminance, contrast, foregroundOn, isDark, panelAlpha, mix, applyTheme, autoApply }
  if (typeof window !== 'undefined') window.LZThemes = api
  if (typeof module !== 'undefined') module.exports = api
})()
