/* v1.72 [KCard] 桌面端接线测试 —— 跑 node --test test/kcard-wiring.test.js
 *
 * 这里测的是**接线层**的行为（存卡判定 / 到期该不该通知 / 视图字段 / 菜单角标文案），
 * FSRS 数学和 knowledge.json 格式在 test/fsrs.test.js 与 test/knowledge.test.js 里已经测过，
 * 本文件只补「面板 → IPC → Store → 复习队列」这条链在纯 Node 下能验的部分。
 */
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const F = require('../fsrs')
const K = require('../knowledge')

const T0 = 1750000000000
const DAY = F.DAY_MS
/** T1 = 「已经存了一天之后」，用来跨过 Good 首刷那一步学习步，看到期队列。 */
const T1 = T0 + F.DAY_MS

function tmpStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-kw-'))
  return { dir: dir, store: K.storeIn(dir) }
}

/** main.js 里 kcardAddFromTranscript 的等价实现（真身是 main.js:26xx 那一段，这里复制一份测逻辑）。 */
function addFromTranscript(store, raw, now, deck) {
  const fb = K.fromTranscript(raw)
  const r = store.add(fb.front, fb.back, K.Source.ANSWER, now, deck)
  return r.ok ? { ok: true, id: r.id, reason: '', front: fb.front } : { ok: false, id: 0, reason: r.reason, front: fb.front }
}
/** main.js 里 kcardShouldNotify 的等价实现。 */
function shouldNotify(due) { return K.shouldNotify(due) }

/* ─────────── ① 存卡 ─────────── */

test('① 存卡：面板原文 → Store.add，抽正反面用的是 knowledge.fromTranscript', () => {
  const { store } = tmpStore()
  const raw = '这道题考的是什么？\n\n## 答案\n\n[名词] 光合作用\n\n关键词：光合\n\n植物靠它把光能变成化学能。\n'
  const r = addFromTranscript(store, raw, T0)
  assert.strictEqual(r.ok, true)
  const c = store.all()[0]
  assert.strictEqual(c.source, 'answer')
  assert.strictEqual(c.front, '这道题考的是什么？')
  // 背面剥掉了 [名词] 行和「关键词：」行
  assert.ok(!c.back.includes('[名词]'), '不该保留名词清单行')
  assert.ok(!c.back.includes('关键词：'), '不该保留关键词行')
  assert.ok(c.back.includes('光能变成化学能'))
})

test('① 存卡：同一条原文存两次 → 第二次 dup（按钮会变 ✓，不重复入库）', () => {
  const { store } = tmpStore()
  const raw = '什么是 FSRS？\n\n## 答案\n\n一种间隔重复调度算法。\n'
  assert.strictEqual(addFromTranscript(store, raw, T0).ok, true)
  const again = addFromTranscript(store, raw, T0 + 1000)
  assert.strictEqual(again.ok, false)
  assert.strictEqual(again.reason, 'dup')
  assert.strictEqual(store.count(), 1, '库不能变大')
})

test('① 存卡：kcard:has 的判定（面板据此把按钮画成 ✓）', () => {
  const { store } = tmpStore()
  const raw = '什么是 R 平方？\n\n## 答案\n\n拟合优度，0~1 之间。\n'
  const fb = K.fromTranscript(raw)
  assert.strictEqual(K.hasDup(store.all(), K.DEFAULT_DECK, fb.front), false)
  store.add(fb.front, fb.back, K.Source.ANSWER, T0)
  assert.strictEqual(K.hasDup(store.all(), K.DEFAULT_DECK, fb.front), true)
})

test('① 存卡：抽不出正面的原文 → empty（面板给「抽不出可存的内容」提示，库不变）', () => {
  const { store } = tmpStore()
  const r = addFromTranscript(store, '', T0)
  assert.strictEqual(r.ok, false)
  assert.strictEqual(r.reason, 'empty')
  assert.strictEqual(store.count(), 0)
})

test('① 存卡：存入即 Good 首刷（FSRS 学习步 → 约 10 分钟后到期，当天一定会到期）', () => {
  const { store } = tmpStore()
  store.add('问', '答', K.Source.ANSWER, T0)
  const c = store.all()[0]
  assert.strictEqual(c.fsrs.reps, 1, '首刷已经算过一次')
  assert.strictEqual(c.fsrs.state, F.State.Learning, 'Good 首刷落在 Learning 态')
  // 不是「立刻到期」，是学习步之后（约 10 分钟）—— 这跟安卓 / ts-fsrs 一致
  assert.strictEqual(store.dueCount(T0), 0, '存进去的这一秒还没到期')
  assert.ok(c.fsrs.due - T0 > 0 && c.fsrs.due - T0 <= 60 * F.MINUTE_MS, '到期时间在一步学习步之内')
  assert.strictEqual(store.dueCount(T0 + F.DAY_MS), 1, '当天之内一定会到期，复习页能过到它')
  // 学习态的卡在真的被复习之前一直是到期的（FSRS 语义：没复习就一直欠着）——
  // 这也是「每小时检查」会一直提醒、直到你把它过掉的原因
  assert.strictEqual(store.dueCount(T0 + 365 * F.DAY_MS), 1, '没复习过的学习态卡永远到期')
  // 复习过（Easy）之后才真的推到未来 —— 判据是「到期之前不算到期」，不是「一年后不算」
  assert.strictEqual(store.answer(c.id, 4, T0 + F.DAY_MS), true)
  const c2 = store.all()[0]
  assert.strictEqual(c2.fsrs.state, F.State.Review, 'Easy 之后毕业成 Review 态')
  assert.strictEqual(store.dueCount(T0 + F.DAY_MS), 0, '到期之前不算到期')
  assert.strictEqual(store.dueCount(c2.fsrs.due), 1, '到了 due 那一刻才算到期')
})

/* ─────────── ④ 到期检查 ─────────── */

test('④ 到期通知：dueCount 为 0 一律不发通知', () => {
  assert.strictEqual(shouldNotify(0), false)
  assert.strictEqual(shouldNotify(-1), false, '负数也不发')
  assert.strictEqual(shouldNotify(1), true)
  assert.strictEqual(shouldNotify(37), true)
})

test('④ 到期通知：文案「知识卡复习 · N 张到期」，与安卓 ReviewNotify 一致', () => {
  assert.strictEqual(K.notiTitle(3), '知识卡复习 · 3 张到期')
  assert.ok(K.notiBody(3).length > 0)
  assert.strictEqual(K.notiBody(0), '', '没到期时正文留空')
})

test('④ 到期检查：同一批到期不重复弹；新卡进来到期数变了才再提醒', () => {
  const { store } = tmpStore()
  // 复现 main.js checkKcardDue 的去重逻辑：due == kcardNotifiedFor 就跳过
  let notifiedFor = -1
  const check = (force) => {
    const due = store.dueCount(T1)
    if (!shouldNotify(due)) { if (due === 0) notifiedFor = -1; return false }
    if (!force && due === notifiedFor) return false
    notifiedFor = due
    return true
  }
  assert.strictEqual(store.dueCount(T1), 0, '空库一张都不到期')
  assert.strictEqual(check(false), false, '空库不发')
  // 存入即 Good 首刷 → 一步学习步之后到期，所以「明天」来看已经有 1 张
  store.add('a', 'A', K.Source.ANSWER, T1 - F.DAY_MS)
  assert.strictEqual(check(false), true, '有 1 张到期 → 发')
  assert.strictEqual(check(false), false, '同一批不再发（每小时检查不反复骚扰）')
  assert.strictEqual(check(false), false)
  assert.strictEqual(check(true), true, 'force 能强推一次')
  store.add('b', 'B', K.Source.ANSWER, T1 - F.DAY_MS)
  assert.strictEqual(check(false), true, '新卡进来到期数变了 → 再发')
  // 全过完（评成 Easy 推到一年后）→ 到期清零 → 计数复位，之后有新卡才会再提醒
  for (const c of store.all()) store.answer(c.id, 4, T1)
  assert.strictEqual(store.dueCount(T1), 0)
  assert.strictEqual(check(false), false, '没到期一条都不发')
  store.add('c', 'C', K.Source.ANSWER, T1 - F.DAY_MS)
  assert.strictEqual(check(false), true, '清零后又有到期卡 → 重新提醒')
})

/* ─────────── ②③ 复习页 / 卡库 ─────────── */

test('② 评分：四档都改得了 due，且从队列里掉出去', () => {
  for (const r of [1, 2, 3, 4]) {
    const { store } = tmpStore()
    store.add('问题' + r, '答案', K.Source.ANSWER, T0)
    const id = store.all()[0].id
    assert.strictEqual(store.answer(id, r, T0), true, 'rating=' + r + ' 应写库成功')
    const c = store.all()[0]
    // 存入即 Good 首刷 reps 已经是 1，再评一次 → 2
    assert.strictEqual(c.fsrs.reps, 2, 'rating=' + r + ' 复习次数应 +1')
    // Good/Easy 推到未来，Again 留在今天
    if (r === 1) assert.ok(c.fsrs.due <= T0 + DAY, 'Again 立刻又到期')
    else assert.ok(c.fsrs.due > T0, 'rating=' + r + ' 应推到未来')
  }
})

test('② 评分：认不出的 rating 不写库（渲染层传错也不能把进度搞乱）', () => {
  const { store } = tmpStore()
  store.add('q', 'a', K.Source.ANSWER, T0)
  const before = JSON.stringify(store.list())
  assert.strictEqual(store.answer(store.all()[0].id, '乱写', T0), false)
  assert.strictEqual(store.answer(999999, 3, T0), false, '库里没有这张 → false')
  assert.strictEqual(JSON.stringify(store.list()), before, '库内容必须一模一样')
})

test('② 复习页文案：页头 / 进度 / 四档名都走 knowledge.js（不另写一份）', () => {
  assert.strictEqual(K.header(3), '知识卡复习 · 到期 3 张')
  assert.strictEqual(K.header(0), '知识卡复习')
  assert.strictEqual(K.progress(2, 8), '第 2 / 8 张')
  assert.strictEqual(K.ratingNameOf(1), '忘记')
  assert.strictEqual(K.ratingNameOf(4), '简单')
  assert.ok(K.EMPTY_HINT.length > 0, '空队列提示不能是空串')
})

test('③ 卡库：删除只删指定那张，其余不动', () => {
  const { store } = tmpStore()
  store.add('q1', 'a1', K.Source.MANUAL, T0)
  store.add('q2', 'a2', K.Source.MANUAL, T0)
  store.add('q3', 'a3', K.Source.MANUAL, T0)
  const victim = store.all()[1]   // q2（all() 是新卡在前：q3,q2,q1）
  assert.strictEqual(store.all().length, 3)
  assert.strictEqual(store.remove(victim.id), true)
  const left = store.all().map((c) => c.front).sort()
  assert.deepStrictEqual(left, ['q1', 'q3'])
  // 删完还能加新卡（id 从现存最大值续）
  store.add('q4', 'a4', K.Source.MANUAL, T0)
  assert.strictEqual(store.count(), 3)
  assert.ok(Math.max(...store.all().map((c) => c.id)) >= 4)
})

test('③ 卡库搜索：正面 / 背面 / 牌组任一命中即算出（渲染层筛选的语义）', () => {
  const { store } = tmpStore()
  // add 的签名是 (front, back, source, now, deck) —— 牌组在最后一个参数
  store.add('光合作用是什么', '把光能变成化学能', K.Source.MANUAL, T0, '生物')
  store.add('牛顿第二定律', 'F = ma', K.Source.MANUAL, T0, '物理')
  const all = store.all()
  const hit = (q) => all.filter((c) => (c.front + ' ' + c.back + ' ' + c.deck).toLowerCase().indexOf(q) >= 0)
  assert.strictEqual(hit('光合').length, 1)
  assert.strictEqual(hit('f = ma').length, 1)
  assert.strictEqual(hit('物理').length, 1)
  assert.strictEqual(hit('量子力学').length, 0)
})

/* ─────────── 数据格式：接线不能把它改坏 ─────────── */

test('数据格式：接线走一遍 add/answer/remove 后，knowledge.json 仍是安卓逐字一致的形状', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-kw-fmt-'))
  const store = K.storeIn(dir)
  addFromTranscript(store, '问题？\n\n## 答案\n\n答案正文。\n', T0, '生物')
  store.answer(store.all()[0].id, 3, T0 + DAY)
  const file = path.join(dir, 'knowledge.json')
  const arr = JSON.parse(fs.readFileSync(file, 'utf8'))
  assert.ok(Array.isArray(arr), '顶层必须是数组')
  assert.deepStrictEqual(Object.keys(arr[0]), ['id', 'deck', 'front', 'back', 'source', 'createdAt', 'fsrs'])
  assert.strictEqual(arr[0].deck, '生物', '自定义牌组要落盘')
  // fsrs 是 snake_case（跟 ts-fsrs / 安卓一致）
  for (const k of ['due', 'stability', 'difficulty', 'learning_step', 'scheduled_days', 'elapsed_days']) {
    assert.ok(k in arr[0].fsrs, 'fsrs 缺字段 ' + k)
  }
  // 评分结果确实落盘了（不是只在内存里）：存入即 Good 首刷 reps=1，再评一次 → 2
  assert.strictEqual(arr[0].fsrs.reps, 2)
  assert.ok(!('tmp' in arr[0]), '不该把临时字段写进去')
  assert.strictEqual(arr[0].createdAt, T0)
})

test('数据格式：写盘是原子的 —— 不会留 .tmp 残渣，落盘后读回来一模一样', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-kw-atomic-'))
  const store = K.storeIn(dir)
  store.add('q', 'a', K.Source.MANUAL, T0)
  assert.strictEqual(fs.existsSync(path.join(dir, 'knowledge.json.tmp')), false, '不该留下 .tmp')
  const back = K.parse(fs.readFileSync(path.join(dir, 'knowledge.json'), 'utf8'))
  assert.strictEqual(back.length, 1)
  assert.strictEqual(back[0].front, 'q')
  assert.deepStrictEqual(Object.keys(back[0]), ['id', 'deck', 'front', 'back', 'source', 'createdAt', 'fsrs'])
})