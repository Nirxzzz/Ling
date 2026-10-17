// 默认「护眼」+ 六套青春配色（对齐安卓 Theme.kt 六套口径）。
// 只测它们经 applyTheme 落到 root 上的真实作用 —— 用伪 root.style 记录写入 / 清理，
// 断言消费者看得见的结果（写没写、写成了什么、切回默认清没清），不复制 themes.js 里的常量与算法。
const test = require('node:test')
const assert = require('node:assert')
const T = require('../themes')

// 六套之外就是默认护眼；从 PRESETS 现取，不另抄一份 id 表
const SIX = T.PRESETS.filter((p) => p.id !== 'eye').map((p) => p.id)
// applyTheme 只在六套下写的 palette 变量 —— 护眼必须一个都不留（残留 = 上一套的串色）
const PALETTE_VARS = ['--paper', '--card', '--primary', '--secondary', '--highlight', '--ink', '--hint', '--btn-primary-bg', '--btn-primary-fg']

/** 记录 applyTheme 对 root 干了什么的伪 root */
function fakeRoot() {
  const vars = {}                       // 当前挂在 root 上的自定义属性
  const removed = []                    // removeProperty 被调过的键
  const classes = new Set()
  const root = {
    style: {
      setProperty: (k, v) => { vars[k] = v },
      removeProperty: (k) => { removed.push(k); delete vars[k] }
    },
    classList: { toggle: (c, on) => { on ? classes.add(c) : classes.delete(c) } },
    dataset: {}
  }
  return { root, vars, removed, classes }
}
/** 把 applyTheme 写出的 "rgb(r, g, b)" 解析回 0xRRGGBB（按 CSS 实际值读，不拼常数） */
function parseRgb(s) {
  const m = /^rgb\((\d{1,3}), (\d{1,3}), (\d{1,3})\)$/.exec(s)
  assert.ok(m, '不是完整 rgb() 颜色：' + s)
  return (Number(m[1]) << 16) | (Number(m[2]) << 8) | Number(m[3])
}

test('normId：未知 / 空落默认，旧 id 迁到最近的六套', () => {
  assert.strictEqual(T.normId(undefined), 'eye')            // 没选过
  assert.strictEqual(T.normId('随便一个不存在的'), 'eye')     // 存的 id 不在了
  assert.strictEqual(T.normId('toString'), 'eye')           // 不认原型上的键
  assert.strictEqual(T.normId('eye'), 'eye')                // 默认 id 不变
  for (const id of ['dark', 'black', 'ocean_deep', 'morandi_blue']) assert.strictEqual(T.normId(id), 'cyber_pop', id)
  for (const id of ['light', 'macaron_taro']) assert.strictEqual(T.normId(id), 'soft_digital', id)
  for (const id of ['macaron_mint', 'forest']) assert.strictEqual(T.normId(id), 'fresh_pop', id)
  for (const id of ['morandi_rose', 'macaron_berry']) assert.strictEqual(T.normId(id), 'candy_pop', id)
  assert.strictEqual(T.normId('morandi_ink'), 'acid_green')
})

test('色系：覆盖全部预设，每个恰好属于一个色系', () => {
  const ids = T.FAMILIES.flatMap((f) => f[1])
  assert.strictEqual(ids.length, new Set(ids).size)
  assert.deepStrictEqual(new Set(ids), new Set(T.PRESETS.map((p) => p.id)))
  assert.strictEqual(T.familyOf('eye'), 0)
  assert.ok(T.familyOf('candy_pop') >= 0 && T.familyOf('candy_pop') !== 0)
})

test('每套正文 / 答案 / 标题对两端底色的对比度 ≥ 7', () => {
  for (const p of T.PRESETS) for (const fg of [p.text, p.answer, p.heading]) for (const bg of [p.bg, p.bg2 == null ? p.bg : p.bg2])
    assert.ok(T.contrast(fg, bg) >= 7, p.id)
})

test('浅色底的面板至少 0.82 不透明；深色底沿用背景浓度', () => {
  assert.ok(T.panelAlpha(T.preset('eye'), 0.5) >= 0.82)
  for (const id of SIX) assert.ok(T.panelAlpha(T.preset(id), 0.5) >= 0.82, id)
  assert.strictEqual(T.panelAlpha({ bg: 0x1C1C1E }, 0.5), 0.5)
})

test('applyTheme：护眼写旧变量、不碰任何 palette 变量、不挂 palette-theme', () => {
  const f = fakeRoot()
  T.applyTheme(f.root, { theme: 'eye', bgAlpha: 0.5 })
  assert.ok('--fg' in f.vars && '--bg' in f.vars && '--bg2' in f.vars && '--mint' in f.vars)
  for (const k of PALETTE_VARS) assert.ok(!(k in f.vars), k)
  assert.ok(!f.classes.has('palette-theme'))
  assert.ok(f.classes.has('light-theme'))                   // eye 是浅色底
  assert.strictEqual(f.root.dataset.theme, 'eye')
})

test('applyTheme：六套把主按钮实底 / 前景写成完整颜色（不是裸三元组）', () => {
  for (const id of SIX) {
    const f = fakeRoot()
    T.applyTheme(f.root, { theme: id })
    assert.match(f.vars['--btn-primary-bg'], /^rgb\(\d{1,3}, \d{1,3}, \d{1,3}\)$/, id)
    assert.match(f.vars['--btn-primary-fg'], /^rgb\(\d{1,3}, \d{1,3}, \d{1,3}\)$/, id)
    // highlight 走 rgb(var(--highlight))，所以这里存三元组
    assert.ok(f.vars['--highlight'] && !/^rgb\(/.test(f.vars['--highlight']), id)
    assert.ok(f.classes.has('palette-theme'), id)
    assert.strictEqual(f.root.dataset.theme, id)
  }
})

test('applyTheme：六套主按钮前景对实底对比度 ≥ 4.5', () => {
  for (const id of SIX) {
    const f = fakeRoot()
    T.applyTheme(f.root, { theme: id })
    const bg = parseRgb(f.vars['--btn-primary-bg'])
    const fg = parseRgb(f.vars['--btn-primary-fg'])
    assert.ok(T.contrast(fg, bg) >= 4.5, id)
  }
})

test('applyTheme：六套切回护眼清掉所有 palette 变量，按钮不残留', () => {
  const f = fakeRoot()
  T.applyTheme(f.root, { theme: 'dopamine_pop' })
  assert.ok(PALETTE_VARS.every((k) => k in f.vars), '先确认六套确实写满了')
  T.applyTheme(f.root, { theme: 'eye' })
  for (const k of PALETTE_VARS) {
    assert.ok(!(k in f.vars), k)            // 已从 root 清掉
    assert.ok(f.removed.includes(k), k)     // 且是通过 removeProperty 清的
  }
  assert.ok(!f.classes.has('palette-theme'))
  assert.strictEqual(f.root.dataset.theme, 'eye')
})
