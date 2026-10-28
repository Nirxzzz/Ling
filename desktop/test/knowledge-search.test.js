// v1.64：知识卡 BM25 检索 —— 与安卓 v1.70 KnowledgeSearch.kt 逐条对拍。
// 用例的期望值直接来自安卓的 KnowledgeSearchTest.kt，改这里之前先看那边有没有同步改。
const test = require('node:test')
const assert = require('node:assert')
const S = require('../knowledge-search')

function kc(id, deck, front, back) {
  return { id: id, deck: deck || '默认', front: front, back: back || '', source: 'manual', createdAt: 1000000 }
}

const LIB = [
  kc(1, '数学', '什么是导数', '导数是函数在某一点的瞬时变化率，几何上是切线斜率。'),
  kc(2, '物理', '牛顿第二定律', 'F = ma，力等于质量乘加速度。'),
  kc(3, '英语', 'how do you spell', '拼写：spell，两个 l。'),
]

/* ---------------- 分词 ---------------- */

test('分词：中文按 2-gram，停用字整词丢掉', () => {
  assert.deepStrictEqual(S.tokenize('我的卡片'), ['的卡', '卡片'])
})

test('分词：英文按词切、压小写', () => {
  assert.deepStrictEqual(S.tokenize('How do you spell'), ['how', 'do', 'you', 'spell'])
})

test('分词：中英混合，型号整词保留', () => {
  const t = S.tokenize('我的卡片 R7250 怎么设置')
  assert.ok(t.indexOf('卡片') >= 0, '中文 2-gram：' + t)
  assert.ok(t.indexOf('r7250') >= 0, '型号整词：' + t)
})

test('分词：中文 / 英文停用词、空白、纯标点都是空', () => {
  assert.deepStrictEqual(S.tokenize('的了呢啊'), [])
  assert.deepStrictEqual(S.tokenize('the a is'), [])
  assert.deepStrictEqual(S.tokenize(''), [])
  assert.deepStrictEqual(S.tokenize('   \n '), [])
  assert.deepStrictEqual(S.tokenize('，。！？——'), [])
})

test('分词：单字实词留下，单字停用词丢掉', () => {
  assert.deepStrictEqual(S.tokenize('卡'), ['卡'])
  assert.deepStrictEqual(S.tokenize('的'), [])
})

/* ---------------- BM25 单文档打分 ---------------- */

test('BM25：词频越高分越高', () => {
  const one = S.bm25Score(['导数'], '导数')
  const many = S.bm25Score(['导数'], '导数 导数 导数')
  assert.ok(many > one, 'many=' + many + ' one=' + one)
})

test('BM25：无重叠 = 0', () => {
  assert.strictEqual(S.bm25Score(['导数'], '牛顿第二定律 F = ma'), 0)
})

test('BM25：空查询 / 空文档 = 0', () => {
  assert.strictEqual(S.bm25Score([], '导数'), 0)
  assert.strictEqual(S.bm25Score(['导数'], ''), 0)
})

test('BM25：同样词频下短文档分更高（长度归一）', () => {
  const short = S.bm25Score(['导数'], '导数')
  const padded = S.bm25Score(['导数'], '导数 ' + '字'.repeat(400))
  assert.ok(short > padded, 'short=' + short + ' padded=' + padded)
})

test('BM25：命中越多单调上升', () => {
  const q = S.tokenize('导数 牛顿 定律')
  const s1 = S.bm25Score(q, '导数')
  const s2 = S.bm25Score(q, '导数 牛顿')
  const s3 = S.bm25Score(q, '导数 牛顿 定律')
  assert.ok(s1 < s2 && s2 < s3, [s1, s2, s3].join(' < '))
})

test('BM25：查询词重复只算一次', () => {
  assert.strictEqual(
    S.bm25Score(['导数'], '导数 定义'),
    S.bm25Score(['导数', '导数', '导数'], '导数 定义'),
  )
})

/* ---------------- 检索 ---------------- */

test('检索：能找到对的那张卡', () => {
  const hits = S.search(LIB, '导数是什么')
  assert.strictEqual(hits[0].id, 1)
})

test('检索：一张都不沾边就返回空（不凑数）', () => {
  assert.deepStrictEqual(S.search(LIB, '光合作用'), [])
})

test('检索：topK 截断，topK 超过总数就全给', () => {
  const many = []
  for (let i = 1; i <= 10; i += 1) many.push(kc(i, '数学', '考点' + i, '考点' + i + ' 的详细说明'))
  assert.strictEqual(S.search(many, '考点', 3).length, 3)
  assert.strictEqual(S.search(many, '考点', 1).length, 1)
  assert.strictEqual(S.search(many, '考点', 99).length, 10)
})

test('检索：topK <= 0 返回空', () => {
  assert.deepStrictEqual(S.search(LIB, '导数', 0), [])
  assert.deepStrictEqual(S.search(LIB, '导数', -1), [])
})

test('检索：同分按 id 升序（与安卓一致）', () => {
  const tie = [kc(5, 'd', '同题', '同答'), kc(2, 'd', '同题', '同答')]
  assert.deepStrictEqual(S.search(tie, '同题').map((c) => c.id), [2, 5])
})

test('检索：正面和背面都参与打分', () => {
  // 「切线斜率」只在背面 —— 查得到说明背面进了检索文本
  assert.strictEqual(S.search(LIB, '切线斜率')[0].id, 1)
})

test('scoreAll 返回分数，score>0 才算命中', () => {
  const all = S.scoreAll(LIB, '导数')
  assert.strictEqual(all.length, 1)
  assert.ok(all[0].score > 0)
  assert.strictEqual(all[0].card.id, 1)
})

test('clip：超长截断加省略号，不长则原样', () => {
  assert.strictEqual(S.clip('abc', 5), 'abc')
  assert.strictEqual(S.clip('abcdef', 3), 'abc…')
  assert.strictEqual(S.clip('abcdef', 0), '')
})
