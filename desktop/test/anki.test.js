'use strict'
/** AnkiImport 纯函数单测：node test/anki.test.js 直接跑 */
const assert = require('node:assert')
const Anki = require('../anki.js')

let pass = 0, fail = 0
function t(name, fn) {
  try { fn(); pass++; console.log('✔', name) }
  catch (e) { fail++; console.log('✖', name, '|', (e.message || e).slice(0, 110)) }
}
function eq(a, b) { assert.strictEqual(a, b) }

t('splitFields 按 U+001F 切字段', () => {
  const sep = String.fromCharCode(31)
  assert.deepStrictEqual(Anki.splitFields(['问', '答附'].join(sep)), ['问', '答附'])
})

t('splitFields 尾部分隔符给出空尾字段', () => {
  assert.deepStrictEqual(Anki.splitFields('问\u001f'), ['问', ''])
})

t('stripHtml 剥标签与实体并压空白', () => {
  eq(Anki.stripHtml('<div>hello <b>world</b></div>'), 'hello world')
  eq(Anki.stripHtml('a&nbsp;b'), 'a b')
  eq(Anki.stripHtml('x<br>y'), 'x y')
})

t('pickDbName 优先 anki21 / anki2 兜底 / 仅 21b 报 zstd', () => {
  eq(Anki.pickDbName(['media', 'collection.anki21', 'collection.anki2']), 'collection.anki21')
  eq(Anki.pickDbName(['collection.anki2']), 'collection.anki2')
  eq(Anki.pickDbName(['collection.anki21b']), 'zstd')
  eq(Anki.pickDbName(['nothing']), null)
})

t('importsLeft 截断与满额', () => {
  eq(Anki.importsLeft(0, 100, 5000), 100)
  eq(Anki.importsLeft(4990, 100, 5000), 10)
  eq(Anki.importsLeft(5000, 100, 5000), 0)
})

t('zipExtract 找不到条目 / 空输入返回 null', () => {
  eq(Anki.zipExtract(Buffer.alloc(0), 'collection.anki2'), null)
})

t('rowToCard 少字段 / 空栏跳过', () => {
  eq(Anki.rowToCard('只有一段'), null)
  eq(Anki.rowToCard('问\u001f'), null)          // 背面空
  eq(Anki.rowToCard('\u001f答'), null)          // 正面空
  const c = Anki.rowToCard('问\u001f答')
  eq(c.front, '问'); eq(c.back, '答')
})

// 构造一个 store 模式的最小 zip 验证 zipExtract 端到端
t('zipExtract 能读 store 条目（端到端，真 zip 夹具）', () => {
  const buf = require('fs').readFileSync(__dirname + '/fixtures/mini.apkg')
  const out = Anki.zipExtract(buf, 'collection.anki2')
  assert.ok(out && out.toString().includes('fake sqlite bytes'))
})

console.log(`\n通过 ${pass} / ${pass + fail}`)
if (fail > 0) process.exit(1)
