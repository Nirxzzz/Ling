// v1.60：追问 what / why + 识别关键词（名词清单）—— node --test test/
const test = require('node:test')
const assert = require('node:assert')
const P = require('../prompts')

test('what：讲简单点 + 举例，万维钢式，不硬套知识体系', () => {
  assert.match(P.ASK_WHAT, /讲简单点/)
  assert.match(P.ASK_WHAT, /例子/)
  assert.match(P.ASK_WHAT, /万维钢/)
  assert.match(P.ASK_WHAT, /不要硬往某个理论框架/)
  const q = P.styledAsk(' 第二步怎么来的 ', true, false)
  assert.ok(q.startsWith('第二步怎么来的\n\n回答方式：\n'))
  assert.ok(q.includes(P.ASK_WHAT) && !q.includes(P.ASK_WHY))
  assert.strictEqual(P.askShown(' 第二步怎么来的 ', true, false), '［what］第二步怎么来的')
})

test('why：第一性原理', () => {
  assert.match(P.ASK_WHY, /第一性原理/)
  const q = P.styledAsk('为什么选 B', false, true)
  assert.ok(q.includes(P.ASK_WHY) && !q.includes(P.ASK_WHAT))
  assert.strictEqual(P.askShown('为什么选 B', false, true), '［why］为什么选 B')
})

test('两个都开 / 空问题 / 都关', () => {
  const both = P.styledAsk('', true, true)
  assert.ok(both.startsWith(P.ASK_WHAT_Q + ' ' + P.ASK_WHY_Q + '\n'))
  assert.ok(both.indexOf(P.ASK_WHAT) < both.indexOf(P.ASK_WHY))   // what 在前
  assert.strictEqual(P.askShown('', true, true), '［what·why］' + P.ASK_WHAT_Q + ' ' + P.ASK_WHY_Q)
  assert.strictEqual(P.styledAsk('', false, false), '')            // 不发
  assert.strictEqual(P.styledAsk('原样', false, false), '原样')
  assert.strictEqual(P.askShown('原样', false, false), '原样')
})

test('关键词提示词：型号 / 编号 / 品牌 / 规格优先，格式约定', () => {
  for (const w of ['型号', '编号', '品牌', '规格', 'R7250', 'P3 广色域', 'Wi-Fi 7', 'LPDDR5X', P.TERMS_HEAD]) assert.ok(P.TERMS_RULE.includes(w), w)
  assert.ok(P.TERMS_RULE.indexOf('型号') < P.TERMS_RULE.indexOf('术语'))   // 专有名词排在术语前面
})

test('解析：型号 / 规格不能被过滤或剥坏', () => {
  const r = P.splitTerms('正文第一段\n\n[可点击名词] R7250｜P3 广色域｜Wi-Fi 7｜LPDDR5X｜3.5mm 耳机孔｜802.11ax｜5G')
  assert.deepStrictEqual(r.terms, ['R7250', 'P3 广色域', 'Wi-Fi 7', 'LPDDR5X', '3.5mm 耳机孔', '802.11ax', '5G'])
  assert.strictEqual(r.text, '正文第一段')
})

test('解析：兼容编号前缀 / 半角分隔 / 顿号 / 长英文 / 去重 / 上限 10', () => {
  assert.deepStrictEqual(P.splitTerms('x\n[可点击名词] 1. 导数｜2、极限｜3) 积分｜4.微分').terms, ['导数', '极限', '积分', '微分'])
  assert.deepStrictEqual(P.splitTerms('x\n[可点击名词] 概率|均值、方差').terms, ['概率', '均值', '方差'])
  assert.deepStrictEqual(P.splitTerms('x\n[可点击名词] Material Design｜A｜A').terms, ['Material', 'A'])
  const many = Array.from({ length: 15 }, (_, i) => 'T' + i).join('｜')
  assert.strictEqual(P.splitTerms('x\n[可点击名词] ' + many).terms.length, 10)
  assert.strictEqual(P.splitTerms('x\n[可点击名词] ' + '超'.repeat(21)).terms.length, 0)
})

test('解析：没有清单原样返回；追问后面的内容保留；多行清单都剥掉', () => {
  assert.deepStrictEqual(P.splitTerms('普通回答'), { text: '普通回答', terms: [] })
  const r = P.splitTerms('正文\n[可点击名词] 电阻\n\n---\n\n**追问：** 再讲讲\n\n回答\n[可点击名词] 电压｜电阻')
  assert.deepStrictEqual(r.terms, ['电阻', '电压'])
  assert.ok(!r.text.includes(P.TERMS_HEAD) && r.text.includes('**追问：** 再讲讲'))
})
