// v1.61：知识卡存储 + 导入（对齐安卓 KnowledgeCard.kt / KnowledgeImport.kt）—— node --test test/
const test = require('node:test')
const assert = require('node:assert')
const os = require('node:os')
const path = require('node:path')
const fs = require('node:fs')
const F = require('../fsrs')
const K = require('../knowledge')

const DAY = F.DAY_MS
const T0 = 1750000000000

/** 每个用例一个临时 userData 目录，测完删掉。 */
function tmpStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-kc-'))
  return { dir: dir, store: K.storeIn(dir) }
}

test('数据结构：字段与安卓一致，knowledge.json 是数组', () => {
  const c = K.card({ id: 3, deck: '生物', front: ' f ', back: ' b ', source: 'answer', createdAt: T0 })
  assert.deepStrictEqual(Object.keys(c), ['id', 'deck', 'front', 'back', 'source', 'createdAt', 'fsrs'])
  assert.strictEqual(c.source, 'answer')
  // 缺字段退回默认值；来源认不出当 manual（与安卓 Source.of 一致）
  assert.strictEqual(K.card({}).deck, '默认')
  assert.strictEqual(K.card({}).source, 'manual')
  assert.strictEqual(K.card({ source: '乱写' }).source, 'manual')
  for (const s of ['answer', 'chat', 'clip', 'manual', 'import']) assert.strictEqual(K.card({ source: s }).source, s)
  // fsrs 子结构与安卓 Fsrs.toJson 同名
  assert.ok(c.fsrs && typeof c.fsrs.reps === 'number')
  assert.ok(Object.keys(c.fsrs).indexOf('lastReview') >= 0)
})

test('序列化：与安卓逐字段一致，能被安卓读、能读安卓写的', () => {
  const c = K.card({ id: 7, deck: '生物', front: '鲸鱼是？', back: '哺乳动物', source: 'answer', createdAt: T0,
    fsrs: F.review(F.card(), F.Rating.Good, T0) })
  const j = K.toJson(c)
  assert.deepStrictEqual(Object.keys(j), ['id', 'deck', 'front', 'back', 'source', 'createdAt', 'fsrs'])
  assert.strictEqual(j.id, 7)
  // fsrs 内层是 snake_case，跟安卓 Fsrs.toJson 一个字节都不差
  assert.deepStrictEqual(Object.keys(j.fsrs),
    ['state', 'stability', 'difficulty', 'due', 'last_review', 'reps', 'lapses', 'learning_step', 'scheduled_days', 'elapsed_days'])
  // 往返
  const arr = K.toJsonList([c])
  const back = K.parse(JSON.stringify(arr))
  assert.strictEqual(back.length, 1)
  assert.strictEqual(back[0].front, '鲸鱼是？')
  assert.strictEqual(back[0].fsrs.due, c.fsrs.due)
  // 坏输入兜底：不是数组 / 语法坏 / 空文件 → 空库，不抛
  assert.deepStrictEqual(K.parse('{坏 json'), [])
  assert.deepStrictEqual(K.parse(null), [])
  assert.deepStrictEqual(K.parse('  '), [])
  assert.deepStrictEqual(K.parse('{"不是数组":1}'), [])
  // 坏元素跳过，好元素照读（安卓 mapNotNull 的语义）
  // 对齐安卓 optString：数字 front 转成字符串保留，不丢弃
  assert.strictEqual(K.parse('[null,{"front":"在"},{"front":123}]').length, 2)
})

test('从面板原文抽正反面：问题行当正面，答案段剥标记截 500', () => {
  const raw = '这道题选哪个？\n\n## 答案\nB. 鲸鱼\n\n## 解析\n鲸鱼是哺乳动物，用肺呼吸\n[可点击名词] 鲸鱼｜哺乳'
  const r = K.fromTranscript(raw)
  assert.strictEqual(r.front, '这道题选哪个？')       // 「## 答案」之前的首个非标题非空行
  assert.strictEqual(r.back, 'B. 鲸鱼')              // 只取答案段，且剥掉 [可点击名词] 行
  assert.ok(!r.back.includes('解析') && !r.back.includes('鲸鱼是哺乳动物'))
  // 没有 ## 标题（纯小白格式）时退回全文剥标记
  const r2 = K.fromTranscript('这题在问什么：求导定义\n\n先弄懂\n导数就是变化率\n[可点击名词] 导数')
  assert.strictEqual(r2.back, '这题在问什么：求导定义\n\n先弄懂\n导数就是变化率')
  // 关键词行也剥掉
  assert.ok(!K.fromTranscript('## 答案\n结论在这\n关键词：甲乙丙').back.includes('关键词'))
  // 背面超长截到 500 + 省略号
  const long = K.fromTranscript('## 答案\n' + '字'.repeat(900)).back
  assert.strictEqual(long.length, K.BACK_MAX + 1)
  assert.ok(long.endsWith('…'))
  // 空输入不炸
  assert.strictEqual(K.fromTranscript('').front, '')
  assert.strictEqual(K.fromTranscript('').back, '')
})

test('多轮追问：只取最后一轮，正面是那句追问', () => {
  const raw = '第一轮答案\n\n---\n\n**追问：** 追问一\n\n答一\n\n---\n\n**追问：** 追问二\n\n答二'
  const r = K.fromTranscript(raw)
  assert.strictEqual(r.front, '追问二')          // 最后一轮的追问
  assert.strictEqual(r.back, '答二')             // 只带最后一轮的答案
  assert.ok(!r.back.includes('答一'))
  assert.ok(!r.back.includes('追问'))            // 正面那句不再混进背面
  // 有 ## 答案 标题的追问：照常走答案段
  assert.strictEqual(K.fromTranscript('原文\n\n---\n\n**追问：** 问\n\n## 答案\nX').back, 'X')
})

test('去重：同牌组同正面视为同一张；跨牌组不拦', () => {
  assert.strictEqual(K.dedupKey('生物', ' 鲸鱼是？ '), '生物|鲸鱼是？')
  const list = [K.card({ id: 1, deck: '生物', front: '鲸鱼是？' })]
  assert.strictEqual(K.hasDup(list, '生物', '鲸鱼是？'), true)
  assert.strictEqual(K.hasDup(list, '生物', '  鲸鱼是？  '), true)   // 首尾空白不算差异
  assert.strictEqual(K.hasDup(list, '历史', '鲸鱼是？'), false)      // 换牌组可以重名
  assert.strictEqual(K.hasDup(list, '生物', '别的题'), false)
  // tryAdd：重复不新增、库原样返回
  const dup = K.tryAdd(list, { deck: '生物', front: '鲸鱼是？' }, K.MAX, T0)
  assert.strictEqual(dup.ok, false)
  assert.strictEqual(dup.reason, 'dup')
  assert.strictEqual(dup.list.length, 1)
  // 空正面不新增
  const empty = K.tryAdd(list, { deck: '生物', front: '   ' }, K.MAX, T0)
  assert.strictEqual(empty.ok, false)
  assert.strictEqual(empty.reason, 'empty')
})

test('tryAdd：新卡 id = 最大 id + 1，插到最前；满了拒收', () => {
  const list = [K.card({ id: 5, front: 'a' }), K.card({ id: 2, front: 'b' })]
  const r = K.tryAdd(list, { deck: '默认', front: '新题', back: '答案' }, K.MAX, T0)
  assert.strictEqual(r.ok, true)
  assert.strictEqual(r.id, 6)                     // max(5,2)+1
  assert.strictEqual(r.list.length, 3)
  assert.strictEqual(r.list[0].front, '新题')      // 插最前
  // 入参不被改（纯函数）
  assert.strictEqual(list.length, 2)
  // 上限：满了返回 null 语义（ok:false reason:'full'）
  const full = [K.card({ id: 1, front: 'x' })]
  assert.strictEqual(K.tryAdd(full, { front: 'y' }, 1, T0).ok, false)
  assert.strictEqual(K.tryAdd(full, { front: 'y' }, 1, T0).reason, 'full')
  // 空库时 id 从 1 起
  assert.strictEqual(K.tryAdd([], { front: 'z' }, K.MAX, T0).id, 1)
})

test('到期排序：到期的排前面按 due 升序；没到期的按创建时间倒序', () => {
  const due = K.card({ id: 1, front: '到期早', createdAt: T0, fsrs: Object.assign(F.card(), { state: F.State.Review, due: T0 - 5 * DAY }) })
  const fresh = K.card({ id: 2, front: '没到期', createdAt: T0, fsrs: Object.assign(F.card(), { state: F.State.Review, due: T0 + 5 * DAY }) })
  const brandNew = K.card({ id: 3, front: '新卡', createdAt: T0 })       // New 恒到期
  const sorted = K.dueFirst([fresh, due, brandNew], T0)
  assert.strictEqual(sorted.length, 3)
  assert.strictEqual(sorted[0].front, '新卡')      // 新卡到期最前
  assert.strictEqual(sorted[1].front, '到期早')
  assert.strictEqual(sorted[2].front, '没到期')
  // dueCount / dueList 口径一致
  assert.strictEqual(K.dueCount([due, fresh, brandNew], T0), 2)
  assert.strictEqual(K.dueList([due, fresh, brandNew], T0).length, 2)
  // daysLeft：已到期算 0
  assert.strictEqual(K.daysLeft(due, T0), 0)
  assert.strictEqual(K.daysLeft(fresh, T0), 5)
})

test('Store：存入即 Good 首刷，落盘后可重新读回来', () => {
  const { dir, store } = tmpStore()
  const r = store.add('鲸鱼是？', '哺乳动物', K.Source.ANSWER, T0)
  assert.strictEqual(r.ok, true)
  assert.strictEqual(r.id, 1)
  assert.strictEqual(store.count(), 1)
  const c = store.all()[0]
  assert.strictEqual(c.front, '鲸鱼是？')
  assert.strictEqual(c.source, 'answer')
  assert.strictEqual(c.createdAt, T0)
  assert.strictEqual(c.fsrs.state, F.State.Learning)   // Good 首刷 → 学习步骤
  assert.strictEqual(c.fsrs.reps, 1)
  assert.ok(c.fsrs.due > T0)                           // 排在 10 分钟后
  // 文件真的写出来了，格式是数组
  const raw = JSON.parse(fs.readFileSync(path.join(dir, 'knowledge.json'), 'utf8'))
  assert.ok(Array.isArray(raw) && raw.length === 1)
  assert.deepStrictEqual(Object.keys(raw[0]), ['id', 'deck', 'front', 'back', 'source', 'createdAt', 'fsrs'])
  // 新 Store 实例从磁盘读回来（内存副本不是唯一真相）
  assert.strictEqual(K.storeIn(dir).count(), 1)
  assert.strictEqual(K.storeIn(dir).all()[0].front, '鲸鱼是？')
  fs.rmSync(dir, { recursive: true, force: true })
})

test('Store：去重 / 上限 / 删除 / 评分改 due', () => {
  const { dir, store } = tmpStore()
  store.add('题一', '答一', K.Source.ANSWER, T0)
  const dup = store.add('题一', '答一又改了', K.Source.ANSWER, T0 + DAY)
  assert.strictEqual(dup.ok, false)
  assert.strictEqual(dup.reason, 'dup')
  assert.strictEqual(store.count(), 1)                  // 没多出第二张
  store.add('题二', '答二', K.Source.MANUAL, T0)
  assert.strictEqual(store.count(), 2)

  // 评分 → 走 FSRS 改 due（写回磁盘）
  const c0 = store.all().find((c) => c.front === '题一')
  const dueBefore = c0.fsrs.due
  assert.strictEqual(store.answer(c0.id, F.Rating.Easy, T0 + DAY), true)
  const c1 = store.all().find((c) => c.id === c0.id)
  assert.notStrictEqual(c1.fsrs.due, dueBefore)
  assert.strictEqual(c1.fsrs.reps, 2)
  // 认不出的评分 / 不存在的 id → 不写库
  assert.strictEqual(store.answer(c0.id, '乱写', T0), false)
  assert.strictEqual(store.answer(9999, F.Rating.Good, T0), false)
  // 字符串评分名也认
  assert.strictEqual(store.answer(c0.id, 'Again', T0 + 2 * DAY), true)
  assert.ok(store.all().find((c) => c.id === c0.id).fsrs.lapses >= 0)

  store.remove(c0.id)
  assert.strictEqual(store.count(), 1)
  assert.strictEqual(store.all()[0].front, '题二')
  fs.rmSync(dir, { recursive: true, force: true })
})

test('Store：批量导入一次落盘，重复/空正面跳过', () => {
  const { dir, store } = tmpStore()
  const cards = K.toCards([
    { front: '甲', back: '一' }, { front: '乙', back: '二' }, { front: '甲', back: '重复' }, { front: '', back: '空' },
  ], '导入', T0, K.Source.IMPORT)
  const n = store.addAll(cards)
  assert.strictEqual(n, 2)                     // 甲、乙；重复和空都跳过
  assert.strictEqual(store.count(), 2)
  assert.strictEqual(store.all()[0].deck, '导入')
  assert.strictEqual(store.all()[0].source, 'import')
  // 空数组不落盘也不报错
  assert.strictEqual(store.addAll([]), 0)
  assert.strictEqual(store.addAll(null), 0)
  fs.rmSync(dir, { recursive: true, force: true })
})

test('Store：到期统计与卡片库口径一致', () => {
  const { dir, store } = tmpStore()
  store.add('新卡题', '答', K.Source.ANSWER, T0)                    // FSRS-5 短期步骤：首刷后 due 在几分钟后
  const dueAt = store.dueCards(T0 + 15 * 60_000).length > 0 ? T0 + 15 * 60_000 : T0
  assert.strictEqual(store.dueCount(dueAt), 1)
  assert.strictEqual(store.dueCards(dueAt).length, 1)
  // 推到 Review 态且 due 在未来 → 不再到期
  const c = store.all()[0]
  let cur = c.fsrs
  while (cur.state !== F.State.Review) cur = F.review(cur, F.Rating.Good, cur.due || T0)
  store.answer(c.id, F.Rating.Good, T0)
  for (let i = 0; i < 6; i++) store.answer(c.id, F.Rating.Good, store.all()[0].fsrs.due)
  assert.strictEqual(store.all()[0].fsrs.state, F.State.Review)
  assert.strictEqual(store.dueCount(T0), 0)                          // 排到将来 → 今天不用过
  assert.strictEqual(store.dueCount(store.all()[0].fsrs.due), 1)      // 到期那天又算到期
  fs.rmSync(dir, { recursive: true, force: true })
})

test('导入 MD：标题卡 + 管道行；BOM / CRLF / 空行都吃干净', () => {
  const md = '﻿# 标题一\r\n正文一\r\n正文一续\r\n\r\n问 | 答：管道卡\r\n# 标题二\r\n正文二\r\n'
  const out = K.mdToCards(md)
  assert.strictEqual(out.length, 2)                               // 「问 |」左侧是表头标记 → 该管道行跳过
  assert.deepStrictEqual(out[0], { front: '标题一', back: '正文一\n正文一续' })   // BOM 剥掉
  assert.deepStrictEqual(out[1], { front: '标题二', back: '正文二' })
  // 阅读顺序：标题卡排它上面的管道卡之前（先 flush）
  assert.deepStrictEqual(K.mdToCards('# A\nx\n问 | 答\n# B\ny').map((c) => c.front), ['A', 'B'])   // 「问 | 答」整行是表头 → 跳过   // 「问 | 答」整行是表头 → 跳过
  // 表头口径：左侧恰为「问」→ 整行跳过（实现与断言同口径）
  // 单侧为空的管道行不进
  assert.deepStrictEqual(K.mdToCards('只有正面 |\n| 只有背面\n'), [])
  // ## 深层标题也是标题；正文并到所属标题
  assert.deepStrictEqual(K.mdToCards('## 深标题\n正文'), [{ front: '深标题', back: '正文' }])
  // 空输入
  assert.deepStrictEqual(K.mdToCards(''), [])
  assert.deepStrictEqual(K.mdToCards(null), [])
  // 截断：front 200 / back 500
  const big = K.mdToCards('# ' + '题'.repeat(300) + '\n' + '答'.repeat(900))
  assert.strictEqual(big[0].front.length, K.FRONT_CLIP + 1)
  assert.strictEqual(big[0].back.length, K.BACK_CLIP + 1)
})

test('导入 TXT：Tab 分隔；BOM / CRLF / 空行；单段行自测卡', () => {
  const out = K.txtToCards('﻿正面一\t背面一\r\n只有一段\r\n\r\n  前后空白  \t  背面三  \r\n')
  assert.deepStrictEqual(out, [
    { front: '正面一', back: '背面一' },
    { front: '只有一段', back: '只有一段' },     // 只有一段 → 背面同正面（自测卡）
    { front: '前后空白', back: '背面三' },        // 两端空白 trim 掉
  ])
  // 自定义分隔符
  assert.deepStrictEqual(K.txtToCards('甲|乙', '|'), [{ front: '甲', back: '乙' }])
  // 行首就是分隔符 → 不当成有背面（i > 0 的守卫）
  assert.deepStrictEqual(K.txtToCards('\t只有背面'), [{ front: '只有背面', back: '只有背面' }])   // 对齐安卓：先 trim 再找分隔符
  // 空文件
  assert.deepStrictEqual(K.txtToCards(''), [])
  assert.deepStrictEqual(K.txtToCards('\r\n\r\n'), [])
  // fileToCards 按后缀选解析器
  assert.strictEqual(K.fileToCards('a.md', '# 标题\n正文').length, 1)
  assert.strictEqual(K.fileToCards('a.txt', '甲\t乙').length, 1)
  assert.strictEqual(K.fileToCards('a.unknown', '甲\t乙').length, 1)
})

test('导入剪藏：第一句当正面，其余当背面；全空白返回 null', () => {
  assert.deepStrictEqual(K.clipToCard('鲸鱼是哺乳动物。它用肺呼吸。'), { front: '鲸鱼是哺乳动物。', back: '它用肺呼吸。' })
  assert.deepStrictEqual(K.clipToCard('只有一句没有标点'), { front: '只有一句没有标点', back: '只有一句没有标点' })  // 无其余 → 背面同正面
  assert.deepStrictEqual(K.clipToCard('首行\n第二行'), { front: '首行', back: '第二行' })   // 换行也算一句边界
  for (const p of ['！？', '!?']) assert.ok(K.clipToCard('问？' + p + '答').front.endsWith(p))
  assert.strictEqual(K.clipToCard('   '), null)
  assert.strictEqual(K.clipToCard(''), null)
  assert.strictEqual(K.clipToCard(null), null)
  // 截断到 200 / 500
  const big = K.clipToCard('问'.repeat(300) + '。' + '答'.repeat(900))
  assert.strictEqual(big.front.length, K.FRONT_CLIP + 1)
  assert.strictEqual(big.back.length, K.BACK_CLIP + 1)
})

test('toCards：补牌组 / 来源 / 首刷调度，落成能直接进库的卡', () => {
  const cards = K.toCards([{ front: '甲', back: '一' }, { front: '', back: '跳过' }, null], '生物', T0, K.Source.IMPORT)
  assert.strictEqual(cards.length, 1)                 // 空正面和 null 跳过
  assert.strictEqual(cards[0].deck, '生物')
  assert.strictEqual(cards[0].source, 'import')
  assert.strictEqual(cards[0].id, 0)                  // id 由 tryAdd 分配
  assert.strictEqual(cards[0].fsrs.state, F.State.Learning)   // 存入即 Good 首刷
  assert.strictEqual(cards[0].fsrs.reps, 1)
  // 缺省牌组 / 来源
  const d = K.toCards([{ front: 'x' }], null, T0)
  assert.strictEqual(d[0].deck, '默认')
  assert.strictEqual(d[0].source, 'import')
})

test('文案：页头 / 进度 / 四档按钮 / 通知（与安卓 ReviewNotify 同款）', () => {
  assert.strictEqual(K.header(0), '知识卡复习')
  assert.strictEqual(K.header(3), '知识卡复习 · 到期 3 张')
  assert.strictEqual(K.progress(3, 8), '第 3 / 8 张')
  assert.strictEqual(K.ratingLabel(F.Rating.Again), '忘记(1)')
  assert.strictEqual(K.ratingLabel(F.Rating.Hard), '困难(2)')
  assert.strictEqual(K.ratingLabel(F.Rating.Good), '良好(3)')
  assert.strictEqual(K.ratingLabel(F.Rating.Easy), '简单(4)')
  assert.strictEqual(K.notiTitle(2), '知识卡复习 · 2 张到期')
  assert.strictEqual(K.notiBody(2), '2 张等着过一遍 · 答完自动排下次的间隔')
  assert.strictEqual(K.notiBody(0), '')
  // 没到期卡一律不发通知
  assert.strictEqual(K.shouldNotify(0), false)
  assert.strictEqual(K.shouldNotify(1), true)
  assert.ok(K.EMPTY_HINT.includes('导入'))
})

test('跨端兼容：安卓写的数据桌面读得动，桌面写的数据也符合安卓字段', () => {
  // 模拟安卓端写出来的 knowledge.json（fsrs 是 Fsrs.toJson 的 snake_case 结构）
  const androidWrote = [{
    id: 1, deck: '默认', front: '安卓的卡', back: '安卓的答案', source: 'answer', createdAt: 1750000000000,
    fsrs: { state: 2, stability: 12.5, difficulty: 4.2, due: 1750000000000, last_review: null,
      reps: 3, lapses: 1, learning_step: 0, scheduled_days: 12, elapsed_days: 0 },
  }, {
    id: 2, deck: '默认', front: '第二张', back: '答', source: 'import', createdAt: 1750000001000,
    fsrs: { state: 0, stability: 0, difficulty: 0, due: 0, last_review: null,
      reps: 0, lapses: 0, learning_step: 0, scheduled_days: 0, elapsed_days: 0 },
  }]
  const list = K.parse(JSON.stringify(androidWrote))
  assert.strictEqual(list.length, 2)
  assert.strictEqual(list[0].fsrs.state, F.State.Review)
  assert.strictEqual(list[0].fsrs.reps, 3)
  assert.strictEqual(list[0].fsrs.lapses, 1)
  assert.strictEqual(list[0].fsrs.lastReview, 0)          // null → 0
  assert.strictEqual(list[1].fsrs.state, F.State.New)
  // 桌面写出去的形状，安卓 KnowledgeCard.Store.toJson 能对上
  const j = K.toJsonList(list)
  for (const o of j) {
    for (const k of ['id', 'deck', 'front', 'back', 'source', 'createdAt', 'fsrs']) assert.ok(k in o, k)
  }
  // 两端参数一致 ⇒ 同一张卡同一时刻评分，算出的 due 必须一样（互卡的硬要求）
  const androidCard = F.cardFromJson(androidWrote[0].fsrs)
  const mine = F.review(androidCard, F.Rating.Good, androidWrote[0].fsrs.due)
  const theirs = F.review(F.cardFromJson(androidWrote[0].fsrs), F.Rating.Good, androidWrote[0].fsrs.due)
  assert.strictEqual(mine.due, theirs.due)
  assert.strictEqual(mine.stability, theirs.stability)
})