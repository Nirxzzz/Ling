// lan-store：永久行为回归（真实 K.Store / X.JsonStore / LanStore，无 Electron）
// 只断言用户可见数据与落盘文件，不做源码字符串断言，也不替换真实存储模块。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const K = require('../knowledge')
const X = require('../extras')
const LanStore = require('../lan-store')

const T0 = 1750000000000
const PEER = 'peer'

/** 每个用例一个临时目录；模块级守卫会在用例间串，开始前先清掉。 */
function tmpDir() {
  K.setWriteGuard(null)
  X.setWriteGuard(null)
  return fs.mkdtempSync(path.join(os.tmpdir(), 'orb-lan-store-'))
}

function cleanup(dir) {
  K.setWriteGuard(null)
  X.setWriteGuard(null)
  if (dir) fs.rmSync(dir, { recursive: true, force: true })
}

/** 真实的 knowledge.json / history.json 环境。 */
function envFor(dir) {
  const hist = new X.JsonStore(path.join(dir, 'history.json'))
  return {
    dir: dir,
    store: K.storeIn(dir),
    hist: hist,
    sidecar: path.join(dir, LanStore.SIDECAR),
    journal: path.join(dir, LanStore.JOURNAL)
  }
}

function lanFor(env, store, historyWrite) {
  return new LanStore({
    dir: env.dir,
    knowledge: store || env.store,
    historyRead: () => env.hist.read(),
    historyWrite: historyWrite || ((list) => env.hist.write(list))
  })
}

function sidecarState(env) {
  return JSON.parse(fs.readFileSync(env.sidecar, 'utf8'))
}

test('坏 knowledge/history JSON：export 抛错，不发墓碑，sidecar 字节不变', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('卡一', '背面一', 'manual', T0).ok, true)
    env.hist.write([
      { t: T0 + 1, kind: 'clip', label: '收藏一', text: '内容一', src: 'answer', star: true }
    ])
    const lan = lanFor(env)
    const first = lan.export()
    assert.ok(first.some((r) => r.value && r.value.front === '卡一'))
    const saved = sidecarState(env)
    assert.ok(saved.records.every((r) => r.value !== null), '正常导出不应出现墓碑')

    const sidecarBytes = fs.readFileSync(env.sidecar)
    const knowledgeBytes = fs.readFileSync(path.join(dir, 'knowledge.json'))

    // knowledge.json 坏：内存里已有旧缓存，必须重建 Store 模拟重启后的首次严格读。
    fs.writeFileSync(path.join(dir, 'knowledge.json'), '{ 坏 json')
    const badK = lanFor(env, K.storeIn(dir))
    assert.throws(() => badK.export())
    assert.deepStrictEqual(fs.readFileSync(env.sidecar), sidecarBytes)
    assert.ok(!fs.existsSync(env.journal))

    // 恢复 knowledge，只弄坏 history.json：严格读必须阻断，不能把收藏读成空库。
    fs.writeFileSync(path.join(dir, 'knowledge.json'), knowledgeBytes)
    fs.writeFileSync(path.join(dir, 'history.json'), '{ 坏 json')
    const badH = lanFor(env, K.storeIn(dir))
    assert.throws(() => badH.export())
    assert.deepStrictEqual(fs.readFileSync(env.sidecar), sidecarBytes)
    assert.ok(!fs.existsSync(env.journal))
  } finally {
    cleanup(dir)
  }
})

test('historyWrite 首次 false：apply 抛且 journal 保留，重启恢复后原 uid/星标不重复', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('旧卡', '旧背面', 'manual', T0).ok, true)
    const t = T0 + 100
    env.hist.write([
      { t: t, kind: 'clip', label: '老收藏', text: '老内容', src: 'answer', star: true }
    ])

    // 只模拟一次写入失败；恢复阶段用同一个真实 JsonStore 写回。
    let failNext = false
    const writer = (list) => {
      if (failNext) { failNext = false; return false }
      return env.hist.write(list)
    }

    const lan = lanFor(env, env.store, writer)
    const first = lan.export()
    const hUid = first.find((r) => r.value && r.value.label === '老收藏').uid
    assert.ok(first.some((r) => r.value && r.value.front === '旧卡'))

    const remote = [{
      uid: 'h:phone-1',
      clock: { phone: 1 },
      value: { kind: 'clip', label: '远端收藏', text: '远端内容', src: 'answer', star: true, createdAt: T0 + 5000 }
    }]
    failNext = true
    assert.throws(() => lan.apply(remote), (err) => err && err.code === 'E_WRITE')
    assert.ok(fs.existsSync(env.journal), '写失败后 journal 必须保留待重放')
    assert.strictEqual(sidecarState(env).bindings[hUid], t, '未 ack 时 sidecar 仍是旧绑定')

    // 模拟重启：真实进程里模块级守卫是空的，先清掉再让新实例重放 journal。
    K.setWriteGuard(null)
    X.setWriteGuard(null)
    const store2 = K.storeIn(dir)
    const restarted = lanFor(env, store2, writer)
    assert.ok(!fs.existsSync(env.journal), '恢复成功后 journal 应删除')

    const out = env.hist.readStrict()
    const original = out.filter((e) => e.t === t)
    assert.strictEqual(original.length, 1)
    assert.strictEqual(original[0].star, true)
    assert.strictEqual(original[0].text, '老内容')
    assert.strictEqual(out.filter((e) => e.text === '老内容').length, 1)
    assert.ok(out.some((e) => e.label === '远端收藏' && e.star === true))

    const state = sidecarState(env)
    assert.strictEqual(state.bindings[hUid], t, '原 uid 仍绑定原 t')
    assert.strictEqual(Object.values(state.bindings).filter((v) => v === t).length, 1, '同一行不重复绑定')
    assert.strictEqual(state.bindings['h:phone-1'], T0 + 5000)

    assert.strictEqual(store2.list().length, 1)
    assert.strictEqual(store2.list()[0].front, '旧卡')

    const again = restarted.export()
    const same = again.filter((r) => r.value && r.value.label === '老收藏')
    assert.strictEqual(same.length, 1)
    assert.strictEqual(same[0].uid, hUid)
    assert.strictEqual(same[0].value.star, true)
    assert.strictEqual(again.filter((r) => r.uid === hUid).length, 1)
  } finally {
    cleanup(dir)
  }
})

test('删除最大 id 后新增：id 不复用，旧卡发墓碑、新卡拿新 uid', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('卡一', '背面一', 'manual', T0).id, 1)
    assert.strictEqual(env.store.add('卡二', '背面二', 'manual', T0 + 1).id, 2)
    assert.strictEqual(env.store.add('卡三', '背面三', 'manual', T0 + 2).id, 3)

    const lan = lanFor(env)
    const first = lan.export()
    const uid3 = first.find((r) => r.value && r.value.front === '卡三').uid

    assert.strictEqual(env.store.remove(3), true)
    const added = env.store.add('新卡', '新背面', 'manual', T0 + 3000)
    assert.strictEqual(added.ok, true)
    assert.notStrictEqual(added.id, 3)
    assert.strictEqual(added.id, 4, '删除最大 id 后新卡仍走单调水位')

    const after = lan.export()
    const oldRec = after.filter((r) => r.uid === uid3)
    assert.strictEqual(oldRec.length, 1)
    assert.strictEqual(oldRec[0].value, null, '被删的旧卡必须同步墓碑')

    const newRec = after.filter((r) => r.value && r.value.front === '新卡')
    assert.strictEqual(newRec.length, 1)
    assert.notStrictEqual(newRec[0].uid, uid3, '新卡不能复用旧 uid')
    assert.ok(newRec[0].uid.startsWith('k:'))
    assert.strictEqual(after.filter((r) => r.value && r.value.front === '卡三').length, 0)

    const state = sidecarState(env)
    assert.ok(!Object.prototype.hasOwnProperty.call(state.bindings, uid3))
    assert.strictEqual(state.bindings[newRec[0].uid], 4)

    // 再导出一次：墓碑与新 uid 都不漂移。
    const again = lan.export()
    assert.strictEqual(again.filter((r) => r.uid === uid3)[0].value, null)
    assert.strictEqual(again.filter((r) => r.value && r.value.front === '新卡')[0].uid, newRec[0].uid)
  } finally {
    cleanup(dir)
  }
})

test('sidecar 损坏：构造即抛，绝不重建或覆盖原文件', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('卡一', '背面一', 'manual', T0).ok, true)
    env.hist.write([])

    // 1) 语法坏
    const badJson = '{"deviceId":"mac-1","records":['
    fs.writeFileSync(env.sidecar, badJson)
    assert.throws(() => lanFor(env), (err) => err && err.code === 'E_STATE')
    assert.strictEqual(fs.readFileSync(env.sidecar, 'utf8'), badJson)
    assert.ok(!fs.existsSync(env.journal))

    // 2) 结构坏：records 不是数组
    const badShape = JSON.stringify({ deviceId: 'mac-1', records: { nope: true }, bindings: {} })
    fs.writeFileSync(env.sidecar, badShape)
    assert.throws(() => lanFor(env), (err) => err && err.code === 'E_STATE')
    assert.strictEqual(fs.readFileSync(env.sidecar, 'utf8'), badShape)

    // 3) deviceId 非法：同样拒绝，且不写回「修复后」的 sidecar
    const badDevice = JSON.stringify({ deviceId: '__proto__', records: [], bindings: {} })
    fs.writeFileSync(env.sidecar, badDevice)
    assert.throws(() => lanFor(env), (err) => err && err.code === 'E_STATE')
    assert.strictEqual(fs.readFileSync(env.sidecar, 'utf8'), badDevice)
  } finally {
    cleanup(dir)
  }
})

/* ---------------- v1.62：同步统计 ---------------- */

test('stats：远端已物化副本计入 conflicts 不计 added；重复同步全 0；墓碑副本不计', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('统计卡', '背面', 'manual', T0).ok, true)
    env.hist.write([])
    const lan = lanFor(env)
    const first = lan.export()
    const base = first.find((r) => r.value && r.value.front === '统计卡')
    assert.ok(base)

    const copyUid = base.uid + '~conflict~' + 'a'.repeat(24)
    const copyValue = Object.assign({}, base.value, { front: '远端副本' })
    const remote = [
      { uid: base.uid, clock: { peer: 1 }, value: base.value },
      { uid: copyUid, clock: { peer: 1 }, value: copyValue }
    ]

    const one = lan.apply(remote)
    assert.deepStrictEqual(one.stats.cards, {
      before: 1, after: 2, added: 0, updated: 0, deleted: 0, restored: 0, conflicts: 1
    })
    assert.strictEqual(one.conflicts, 1, '副本只进 conflicts，顶层 conflicts 不能少报')
    assert.strictEqual(one.stats.history.conflicts, 0)
    assert.strictEqual(env.store.listStrict().length, 2)

    const again = lan.apply(remote)
    assert.deepStrictEqual(again.stats.cards, {
      before: 2, after: 2, added: 0, updated: 0, deleted: 0, restored: 0, conflicts: 0
    })
    assert.strictEqual(again.conflicts, 0)

    // 副本被远端墓碑化（clock 支配本地副本）→ 行消失，但副本不算 deleted / conflicts。
    const dev = sidecarState(env).deviceId
    const tomb = lan.apply([
      { uid: base.uid, clock: { [dev]: 1, peer: 1 }, value: base.value },
      { uid: copyUid, clock: { [dev]: 1, peer: 2 }, value: null }
    ])
    assert.deepStrictEqual(tomb.stats.cards, {
      before: 2, after: 1, added: 0, updated: 0, deleted: 0, restored: 0, conflicts: 0
    })
    const copyRec = sidecarState(env).records.find((r) => r.uid === copyUid)
    assert.strictEqual(copyRec.value, null)
    assert.strictEqual(env.store.listStrict().length, 1)
  } finally {
    cleanup(dir)
  }
})

test('stats：updated / deleted / restored（知识卡）与 star 口径（收藏）', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('原卡', '原背面', 'manual', T0).ok, true)
    env.hist.write([
      { t: T0 + 1, kind: 'clip', label: '收藏一', text: '内容一', src: 'answer', star: true }
    ])
    const lan = lanFor(env)
    const first = lan.export()
    const dev = sidecarState(env).deviceId
    const card = first.find((r) => r.value && r.value.front === '原卡')
    const hist = first.find((r) => r.value && r.value.label === '收藏一')
    assert.ok(card && hist)
    assert.deepStrictEqual(hist.clock, { [dev]: 1 })

    // updated
    const upd = lan.apply([{ uid: card.uid, clock: { [dev]: 2 }, value: Object.assign({}, card.value, { front: '改过' }) }])
    assert.deepStrictEqual(upd.stats.cards, {
      before: 1, after: 1, added: 0, updated: 1, deleted: 0, restored: 0, conflicts: 0
    })
    assert.strictEqual(env.store.listStrict()[0].front, '改过')

    // deleted
    const del = lan.apply([{ uid: card.uid, clock: { [dev]: 3 }, value: null }])
    assert.deepStrictEqual(del.stats.cards, {
      before: 1, after: 0, added: 0, updated: 0, deleted: 1, restored: 0, conflicts: 0
    })
    assert.strictEqual(env.store.listStrict().length, 0)

    // restored
    const res = lan.apply([{ uid: card.uid, clock: { [dev]: 4 }, value: Object.assign({}, card.value, { front: '又回来' }) }])
    assert.deepStrictEqual(res.stats.cards, {
      before: 0, after: 1, added: 0, updated: 0, deleted: 0, restored: 1, conflicts: 0
    })
    assert.strictEqual(env.store.listStrict()[0].front, '又回来')

    // 收藏：取消星标 = deleted（未收藏的普通历史不进同步总数）
    const unstar = lan.apply([{ uid: hist.uid, clock: { [dev]: 2 }, value: null }])
    assert.deepStrictEqual(unstar.stats.history, {
      before: 1, after: 0, added: 0, updated: 0, deleted: 1, restored: 0, conflicts: 0
    })
    const rows = env.hist.readStrict()
    assert.strictEqual(rows.length, 1)
    assert.notStrictEqual(rows[0].star, true)

    // 远端重新收藏（clock 支配墓碑）→ restored
    const re = lan.apply([{
      uid: hist.uid,
      clock: { [dev]: 3 },
      value: Object.assign({}, hist.value, { label: '收藏一', text: '内容一' })
    }])
    assert.deepStrictEqual(re.stats.history, {
      before: 0, after: 1, added: 0, updated: 0, deleted: 0, restored: 1, conflicts: 0
    })
    assert.ok(env.hist.readStrict().some((e) => e.star === true && e.text === '内容一'))
  } finally {
    cleanup(dir)
  }
})

/* ---------------- v1.62：冲突副本来历（消费者可见） ---------------- */

test('side：对端数组里的旧第三方副本不构成来源证据；可证实来源仍是 localKept/peerKept', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('电脑版本', '电脑背面', 'manual', T0).ok, true)
    assert.strictEqual(env.store.add('aaa 本机', '本机背面', 'manual', T0 + 1).ok, true)
    assert.strictEqual(env.store.add('同值卡', '同值背面', 'manual', T0 + 2).ok, true)
    env.hist.write([])
    const lan = lanFor(env)
    const first = lan.export()
    const localWins = first.find((r) => r.value && r.value.front === '电脑版本')
    const peerWins = first.find((r) => r.value && r.value.front === 'aaa 本机')
    const sameValue = first.find((r) => r.value && r.value.front === '同值卡')
    assert.ok(localWins && peerWins && sameValue)

    // 两份旧副本只出现在对端数组里，且 uid / payload 都不是两侧任何 base 算出来的。
    const staleCopyUid = localWins.uid + '~conflict~' + 'f'.repeat(24)
    const sameCopyUid = sameValue.uid + '~conflict~' + 'e'.repeat(24)
    const remote = [
      // canonical 更小 → 对端版本进副本，本机留主版本：可证实 localKept。
      { uid: localWins.uid, clock: { [PEER]: 1 }, value: Object.assign({}, localWins.value, { front: '手机版本' }) },
      // canonical 更大 → 主版本留对端，本机版本进副本：可证实 peerKept。
      { uid: peerWins.uid, clock: { [PEER]: 1 }, value: Object.assign({}, peerWins.value, { front: 'zzz 手机' }) },
      // 第三方旧副本：payload 与两侧 base 都不同，只是恰好来自对端数组。
      { uid: staleCopyUid, clock: { [PEER]: 1 }, value: Object.assign({}, localWins.value, { front: '第三方旧版本' }) },
      // 两侧 base 同 payload，旧副本也等于它：两侧都解释得通 → 歧义。
      { uid: sameValue.uid, clock: { [PEER]: 1 }, value: Object.assign({}, sameValue.value) },
      { uid: sameCopyUid, clock: { [PEER]: 1 }, value: Object.assign({}, sameValue.value) }
    ]

    lan.apply(remote)

    const list = lan.listConflicts()
    const stale = list.find((x) => x.copyUid === staleCopyUid)
    assert.ok(stale)
    assert.strictEqual(stale.side, 'unknown', '对端转发的第三方旧副本不能按数组来源判成 peerKept')
    const ambiguous = list.find((x) => x.copyUid === sameCopyUid)
    assert.ok(ambiguous)
    assert.strictEqual(ambiguous.side, 'unknown', '两侧都能解释副本时必须 unknown，不能先到先得')

    const provenLocal = list.find((x) => x.baseUid === localWins.uid && x.copyUid !== staleCopyUid)
    assert.strictEqual(provenLocal.side, 'localKept')
    const provenPeer = list.find((x) => x.baseUid === peerWins.uid)
    assert.strictEqual(provenPeer.side, 'peerKept')

    const sides = sidecarState(env).sides
    assert.deepStrictEqual(sides, {
      [provenLocal.copyUid]: 'localKept',
      [provenPeer.copyUid]: 'peerKept'
    })
    assert.ok(!Object.prototype.hasOwnProperty.call(sides, staleCopyUid))
    assert.ok(!Object.prototype.hasOwnProperty.call(sides, sameCopyUid))
  } finally {
    cleanup(dir)
  }
})

/* ---------------- v1.85：主墓碑 + 副本存活仍可见 / 可恢复 ---------------- */

test('冲突：主记录已删但副本仍活时列表不隐藏，KEEP_COPY 能把副本内容恢复到主记录', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('主卡', '主背面', 'manual', T0).ok, true)
    env.hist.write([])
    const lan = lanFor(env)
    const first = lan.export()
    const base = first.find((r) => r.value && r.value.front === '主卡')
    assert.ok(base)
    const copyUid = base.uid + '~conflict~' + 'b'.repeat(24)
    const copyValue = Object.assign({}, base.value, { front: '副本卡', back: '副本背面' })

    lan.apply([
      { uid: base.uid, clock: base.clock, value: base.value },
      { uid: copyUid, clock: { [PEER]: 1 }, value: copyValue }
    ])
    assert.strictEqual(env.store.listStrict().length, 2)

    // 对端删掉主记录（clock 严格支配本地）：主墓碑，副本保持存活。
    const dev = sidecarState(env).deviceId
    lan.apply([{ uid: base.uid, clock: { [dev]: 9 }, value: null }])
    const cards = env.store.listStrict()
    assert.strictEqual(cards.length, 1)
    assert.strictEqual(cards[0].front, '副本卡')

    const list = lan.listConflicts()
    const item = list.find((x) => x.copyUid === copyUid)
    assert.ok(item, '主墓碑 + 副本存活的未解决冲突必须对用户可见')
    assert.strictEqual(item.baseValue, null)
    assert.strictEqual(item.copyValue.front, '副本卡')
    assert.strictEqual(item.resolved, null)
    assert.strictEqual(item.baseRowId, null)
    assert.strictEqual(item.copyRowId, cards[0].id)

    // 后端本来就允许 KEEP_COPY：副本内容搬到主 uid，副本 uid 墓碑，业务行只剩一份。
    const r = lan.resolveConflict(copyUid, 'KEEP_COPY')
    assert.strictEqual(r.pick, 'KEEP_COPY')
    const afterCards = env.store.listStrict()
    assert.strictEqual(afterCards.length, 1)
    assert.strictEqual(afterCards[0].front, '副本卡')
    assert.strictEqual(afterCards[0].back, '副本背面')

    const after = lan.listConflicts().find((x) => x.copyUid === copyUid)
    assert.ok(after, '已解决的墓碑副本仍按 resolved 记录列出')
    assert.strictEqual(after.resolved.pick, 'KEEP_COPY')
    assert.strictEqual(after.copyValue, null)
    assert.strictEqual(after.baseValue.front, '副本卡')

    const records = sidecarState(env).records
    assert.strictEqual(records.find((x) => x.uid === base.uid).value.front, '副本卡')
    assert.strictEqual(records.find((x) => x.uid === copyUid).value, null)
  } finally {
    cleanup(dir)
  }
})
