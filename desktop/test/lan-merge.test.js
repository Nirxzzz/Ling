// lan-merge：双端同步合并内核的常驻测试（只测行为：并发新增 / 删除vs编辑 / 复用幂等 / 时钟支配 / 对称）
const test = require('node:test')
const assert = require('node:assert')
const crypto = require('node:crypto')
const { merge, canonical } = require('../lan-merge')

const MAC = 'mac'
const PHONE = 'phone'
const T0 = 1750000000000

function card(front, back) {
  return {
    deck: '默认',
    front: front,
    back: back || 'back-' + front,
    source: 'manual',
    createdAt: T0,
    fsrs: { state: 0, stability: 0, difficulty: 0, due: T0, reps: 0 },
    origin: MAC
  }
}

function history(label) {
  return { kind: 'clip', label: label, text: 'text-' + label, src: 'answer', star: true, createdAt: T0 }
}

function rec(uid, clock, value) {
  return { uid: uid, clock: clock, value: value }
}

test('并发新增：两端各自新增/新增同一 uid 都不丢，clock 取并集', () => {
  const a = [rec('k:a', { mac: 1 }, card('甲'))]
  const b = [rec('h:b', { phone: 1 }, history('乙'))]
  const r = merge(a, b)
  assert.strictEqual(r.conflicts, 0)
  assert.deepStrictEqual(r.records.map((x) => x.uid), ['h:b', 'k:a'])
  assert.deepStrictEqual(r.records[0].value, history('乙'))
  assert.deepStrictEqual(r.records[0].clock, { phone: 1 })
  assert.deepStrictEqual(r.records[1].value, card('甲'))
  assert.deepStrictEqual(r.records[1].clock, { mac: 1 })

  // 同一 uid、同一 value、各自新增：合一份，clock 相加
  const sameA = [rec('k:c', { mac: 1 }, card('丙'))]
  const sameB = [rec('k:c', { phone: 2 }, card('丙'))]
  const same = merge(sameA, sameB)
  assert.strictEqual(same.conflicts, 0)
  assert.strictEqual(same.records.length, 1)
  assert.deepStrictEqual(same.records[0].clock, { mac: 1, phone: 2 })

  // 一侧为空：原样带走
  assert.deepStrictEqual(merge(a, []).records, a)
  assert.deepStrictEqual(merge([], b).records, b)
})

test('并发不同值：canonical 较大者当主版本，败方按自己的原 clock 进确定性副本', () => {
  const v1 = card('苹果')
  const v2 = card('香蕉')
  const a = [rec('k:fruit', { mac: 2 }, v1)]
  const b = [rec('k:fruit', { phone: 2 }, v2)]

  const r = merge(a, b)
  assert.strictEqual(r.conflicts, 1)
  assert.strictEqual(r.records.length, 2)

  const primary = r.records.find((x) => x.uid === 'k:fruit')
  const copy = r.records.find((x) => x.uid !== 'k:fruit')
  const c1 = canonical(v1)
  const c2 = canonical(v2)
  const winner = c1 > c2 ? v1 : v2
  const loser = c1 > c2 ? v2 : v1
  // 败方记录的原始 clock（不是合并后的 { mac: 2, phone: 2 }）
  const loserClock = c1 > c2 ? { phone: 2 } : { mac: 2 }

  assert.strictEqual(primary.value.front, '香蕉')
  assert.deepStrictEqual(primary.value, winner)
  assert.deepStrictEqual(copy.value, loser)
  assert.deepStrictEqual(primary.clock, { mac: 2, phone: 2 })
  assert.deepStrictEqual(copy.clock, { mac: 2, phone: 2 })

  const digest = crypto
    .createHash('sha256')
    .update(canonical({ uid: 'k:fruit', clock: loserClock, value: loser }), 'utf8')
    .digest('hex')
    .slice(0, 24)
  assert.strictEqual(copy.uid, 'k:fruit~conflict~' + digest)
  assert.notStrictEqual(
    copy.uid,
    'k:fruit~conflict~' +
      crypto
        .createHash('sha256')
        .update(canonical({ uid: 'k:fruit', clock: { mac: 2, phone: 2 }, value: loser }), 'utf8')
        .digest('hex')
        .slice(0, 24)
  )

  // 主版本与到达顺序无关
  assert.deepStrictEqual(merge(b, a), r)
})

test('删除 vs 编辑：主 uid 保持 null，被删掉的编辑内容进冲突副本', () => {
  const edited = card('电脑上改过')
  const deleted = rec('k:x', { phone: 3 }, null)
  const edit = rec('k:x', { mac: 3 }, edited)

  const r = merge([deleted], [edit])
  assert.strictEqual(r.conflicts, 1)
  assert.strictEqual(r.records.length, 2)

  const primary = r.records.find((x) => x.uid === 'k:x')
  const copy = r.records.find((x) => x.uid !== 'k:x')
  assert.strictEqual(primary.value, null)
  assert.deepStrictEqual(primary.clock, { mac: 3, phone: 3 })
  assert.ok(copy.uid.startsWith('k:x~conflict~'))
  assert.deepStrictEqual(copy.value, edited)
  assert.deepStrictEqual(copy.clock, { mac: 3, phone: 3 })

  // 交换两端，结果完全一致
  assert.deepStrictEqual(merge([edit], [deleted]), r)
})

test('时钟支配：严格支配者胜，不生成冲突副本；删除可被后续编辑复活', () => {
  const newer = card('新')
  const older = card('旧')
  const a = [rec('k:d', { mac: 5, phone: 2 }, newer)]
  const b = [rec('k:d', { mac: 1 }, older)]

  const r = merge(a, b)
  assert.strictEqual(r.conflicts, 0)
  assert.strictEqual(r.records.length, 1)
  assert.deepStrictEqual(r.records[0].value, newer)
  assert.deepStrictEqual(r.records[0].clock, { mac: 5, phone: 2 })
  assert.deepStrictEqual(merge(b, a), r)

  // 只多一个分量也算严格支配
  const one = merge([rec('k:s', { mac: 2, phone: 1 }, newer)], [rec('k:s', { mac: 2 }, older)])
  assert.strictEqual(one.conflicts, 0)
  assert.deepStrictEqual(one.records[0].value, newer)

  // 删除之后又编辑：编辑支配删除，记录复活，不产生副本
  const revived = merge([rec('k:r', { mac: 2 }, newer)], [rec('k:r', { mac: 1 }, null)])
  assert.strictEqual(revived.conflicts, 0)
  assert.strictEqual(revived.records.length, 1)
  assert.deepStrictEqual(revived.records[0].value, newer)
})

test('对称：交换两端后记录逐字节一致，输出按 uid 升序', () => {
  const A = [
    rec('k:1', { mac: 2 }, card('一')),
    rec('k:2', { mac: 1 }, null),
    rec('h:1', { mac: 3 }, history('收藏'))
  ]
  const B = [
    rec('k:1', { phone: 2 }, card('壹')),
    rec('k:2', { phone: 1 }, card('二')),
    rec('h:1', { phone: 3 }, history('收藏'))
  ]

  const ab = merge(A, B)
  const ba = merge(B, A)
  assert.deepStrictEqual(ab, ba)
  assert.strictEqual(JSON.stringify(ab), JSON.stringify(ba))

  const uids = ab.records.map((x) => x.uid)
  assert.deepStrictEqual(uids, uids.slice().sort())

  // canonical：payload 的 key 在输出里已经排序，数组保持原顺序
  for (const record of ab.records) {
    if (record.value === null) continue
    assert.deepStrictEqual(Object.keys(record.value), Object.keys(record.value).slice().sort())
  }
  const h = ab.records.find((x) => x.uid === 'h:1')
  assert.strictEqual(h.value.label, '收藏')
  assert.deepStrictEqual(h.clock, { mac: 3, phone: 3 })
})

test('复用幂等：同一对数据反复同步不产生新副本，副本 uid 不漂移', () => {
  const A = [rec('k:c', { mac: 2 }, card('甲'))]
  const B = [rec('k:c', { phone: 2 }, card('乙'))]

  const first = merge(A, B)
  assert.strictEqual(first.conflicts, 1)
  const copyUid = first.records.find((x) => x.uid.includes('~conflict~')).uid

  // 结果自合并
  const self = merge(first.records, first.records)
  assert.deepStrictEqual(self.records, first.records)
  assert.strictEqual(self.conflicts, 0)

  // 同一份结果再掺回任意一端的旧数据
  assert.deepStrictEqual(merge(first.records, B).records, first.records)
  assert.deepStrictEqual(merge(A, first.records).records, first.records)

  // 两端各自都已持有同一份合并结果：再同步既不新增副本，也不计数
  assert.strictEqual(merge(first.records, first.records).conflicts, 0)

  // 又冒出一个并发新值：只计新生成的那个副本，已有副本不重复计数
  const more = merge(first.records, [rec('k:c', { phone: 3 }, card('丙'))])
  assert.strictEqual(more.conflicts, 1)
  assert.strictEqual(more.records.length, 3)
  assert.strictEqual(more.records.filter((x) => x.uid === copyUid).length, 1)
  assert.strictEqual(more.records.filter((x) => x.uid.includes('~conflict~')).length, 2)

  // 连跑三轮，副本仍只有一个且 uid 不变
  let acc = first.records
  for (let i = 0; i < 3; i += 1) {
    const step = merge(acc, i % 2 === 0 ? A : B)
    assert.deepStrictEqual(step.records, first.records)
    assert.strictEqual(step.conflicts, 0)
    acc = step.records
  }
  assert.deepStrictEqual(acc.filter((x) => x.uid.includes('~conflict~')).map((x) => x.uid), [copyUid])
})
