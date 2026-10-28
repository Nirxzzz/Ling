// lan-store 冲突解决：三选 / 再同步不复活 / 嵌套副本 / history 重新收藏不复活。
// 只断言用户可见数据与落盘文件，不替换真实存储模块，也不做源码字符串断言。
'use strict'

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

function tmpDir() {
  K.setWriteGuard(null)
  X.setWriteGuard(null)
  return fs.mkdtempSync(path.join(os.tmpdir(), 'orb-lan-conflict-'))
}

function cleanup(dir) {
  K.setWriteGuard(null)
  X.setWriteGuard(null)
  if (dir) fs.rmSync(dir, { recursive: true, force: true })
}

function envFor(dir) {
  const hist = new X.JsonStore(path.join(dir, 'history.json'))
  return {
    dir: dir,
    store: K.storeIn(dir),
    hist: hist,
    sidecar: path.join(dir, LanStore.SIDECAR)
  }
}

function lanFor(env) {
  return new LanStore({
    dir: env.dir,
    knowledge: env.store,
    historyRead: () => env.hist.read(),
    historyWrite: (list) => env.hist.write(list)
  })
}

function sidecarState(env) {
  return JSON.parse(fs.readFileSync(env.sidecar, 'utf8'))
}

/** 造一对知识卡并发冲突。 */
function cardConflict() {
  const dir = tmpDir()
  const env = envFor(dir)
  assert.strictEqual(env.store.add('电脑版本', '电脑背面', 'manual', T0).ok, true)
  env.hist.write([])
  const lan = lanFor(env)
  const first = lan.export()
  const base = first.find((r) => r.value && r.value.front === '电脑版本')
  assert.ok(base)
  const remote = [{
    uid: base.uid,
    clock: { [PEER]: 1 },
    value: Object.assign({}, base.value, { front: '手机版本', back: '手机背面' })
  }]
  const applied = lan.apply(remote)
  assert.strictEqual(applied.conflicts, 1)
  const list = lan.listConflicts()
  assert.strictEqual(list.length, 1)
  return {
    dir: dir,
    env: env,
    lan: lan,
    baseUid: base.uid,
    remote: remote,
    info: list[0],
    device: sidecarState(env).deviceId
  }
}

function replaceCardFront(env, id, front) {
  const cards = env.store.listStrict()
  env.store.replaceFromSync(cards.map((c) => (c.id === id ? Object.assign({}, c, { front: front }) : c)), { internal: true })
}

test('KEEP_BASE：主记录保留 observe 后的最新内容，副本墓碑；再同步不复活', () => {
  const c = cardConflict()
  try {
    const before = sidecarState(c.env)
    assert.ok(before.resolved, '解决前 sidecar 可以有空的 resolved，但不能丢字段')
    assert.deepStrictEqual(before.resolved, {})

    replaceCardFront(c.env, c.info.baseRowId, '电脑最新')
    const out = c.lan.resolveConflict(c.info.copyUid, 'KEEP_BASE')
    assert.strictEqual(out.baseUid, c.baseUid)
    assert.strictEqual(out.copyUid, c.info.copyUid)

    const list = c.lan.listConflicts()
    const pair = list.find((x) => x.copyUid === c.info.copyUid)
    assert.ok(pair, '已解决的副本仍应能查到（带 resolved 元数据）')
    assert.strictEqual(pair.copyValue, null)
    assert.strictEqual(pair.baseValue.front, '电脑最新')
    assert.strictEqual(pair.resolved.pick, 'KEEP_BASE')
    assert.strictEqual(pair.resolved.baseUid, c.baseUid)

    const state = sidecarState(c.env)
    assert.strictEqual(state.resolved[c.info.copyUid].pick, 'KEEP_BASE')
    assert.strictEqual(state.records.find((r) => r.uid === c.info.copyUid).value, null)
    assert.strictEqual(state.bindings[c.info.copyUid], undefined, '卡墓碑不应保留 binding')

    const cards = c.env.store.listStrict()
    assert.strictEqual(cards.length, 1)
    assert.strictEqual(cards[0].front, '电脑最新')

    // 再同步同一份远端：旧值不能翻盘，也不能重新长出副本。
    const again = c.lan.apply(c.remote)
    assert.deepStrictEqual(again.stats.cards, {
      before: 1, after: 1, added: 0, updated: 0, deleted: 0, restored: 0, conflicts: 0
    })
    assert.strictEqual(c.env.store.listStrict().length, 1)
    assert.strictEqual(c.env.store.listStrict()[0].front, '电脑最新')
    const recs = c.lan.export()
    assert.strictEqual(recs.find((r) => r.uid === c.info.copyUid).value, null)
    assert.strictEqual(recs.filter((r) => r.value && r.value.front === '手机版本').length, 0)
  } finally {
    cleanup(c.dir)
  }
})

test('KEEP_COPY：副本最新 payload 写进主 uid 的业务行，副本墓碑；再同步不复活', () => {
  const c = cardConflict()
  try {
    replaceCardFront(c.env, c.info.copyRowId, '手机最新')
    // 先 export 拿到 resolve 前的真实 clock，再用同一套 max + 本机 +1 算期望的 N。
    const before = c.lan.export()
    const baseBefore = before.find((r) => r.uid === c.baseUid)
    const copyBefore = before.find((r) => r.uid === c.info.copyUid)
    const expectedClock = Object.assign({}, baseBefore.clock)
    for (const dev of Object.keys(copyBefore.clock)) {
      expectedClock[dev] = Math.max(expectedClock[dev] || 0, copyBefore.clock[dev])
    }
    expectedClock[c.device] = (expectedClock[c.device] || 0) + 1

    const out = c.lan.resolveConflict(c.info.copyUid, 'KEEP_COPY')
    assert.strictEqual(out.baseUid, c.baseUid)
    assert.strictEqual(out.copyUid, c.info.copyUid)

    const state = sidecarState(c.env)
    const baseRec = state.records.find((r) => r.uid === c.baseUid)
    const copyRec = state.records.find((r) => r.uid === c.info.copyUid)
    assert.strictEqual(baseRec.value.front, '手机最新', '主 uid 持有副本的最新 payload')
    assert.strictEqual(copyRec.value, null, '副本 uid 必须是墓碑')
    assert.deepStrictEqual(baseRec.clock, expectedClock, '主 uid clock = bump(max(base, copy))')
    assert.deepStrictEqual(copyRec.clock, expectedClock, '副本 clock 同一个 N')
    assert.strictEqual(state.bindings[c.baseUid], c.info.baseRowId, '业务行仍是主 uid 绑定的那一行')
    assert.strictEqual(state.bindings[c.info.copyUid], undefined, '卡墓碑不应保留 binding')

    const cards = c.env.store.listStrict()
    assert.strictEqual(cards.length, 1)
    assert.strictEqual(cards[0].id, c.info.baseRowId, '主 uid 的业务行没有被换成副本行')
    assert.strictEqual(cards[0].front, '手机最新', '主 uid 的业务行同步成副本值')

    const pair = c.lan.listConflicts().find((x) => x.copyUid === c.info.copyUid)
    assert.strictEqual(pair.baseValue.front, '手机最新')
    assert.strictEqual(pair.copyValue, null)
    assert.strictEqual(pair.resolved.pick, 'KEEP_COPY')

    // 再同步主 uid 的旧远端值：不能翻盘，也不能让副本复活。
    const again = c.lan.apply(c.remote)
    assert.strictEqual(again.stats.cards.conflicts, 0)
    assert.strictEqual(again.stats.cards.added, 0)
    assert.strictEqual(again.stats.cards.updated, 0)
    assert.strictEqual(c.env.store.listStrict().length, 1)
    assert.strictEqual(c.env.store.listStrict()[0].front, '手机最新')
    const recs = c.lan.export()
    assert.strictEqual(recs.find((r) => r.uid === c.baseUid).value.front, '手机最新', '主记录不能被旧远端值翻盘')
    assert.strictEqual(recs.find((r) => r.uid === c.info.copyUid).value, null, '副本不能再活')
    assert.strictEqual(recs.filter((r) => r.value && r.value.front === '手机版本').length, 0)
  } finally {
    cleanup(c.dir)
  }
})

test('同一冲突两端各自选 KEEP_COPY：再合并只有一条卡，不生成重复', () => {
  const c = cardConflict()
  try {
    const pre = c.lan.export()
    const copyRec = pre.find((r) => r.uid === c.info.copyUid)
    assert.ok(copyRec && copyRec.value, '冲突副本有内容')
    const kept = JSON.parse(JSON.stringify(copyRec.value))

    // 本机选 KEEP_COPY。
    c.lan.resolveConflict(c.info.copyUid, 'KEEP_COPY')

    // 同一冲突，Android 端独立选了 KEEP_COPY 后发来的记录（Android 形状）：
    // 主 uid 持 copy payload、副本 uid 墓碑，clock 只有自己的分量，与桌面端互不支配。
    const android = [
      { uid: c.baseUid, clock: { android: 7 }, value: kept },
      { uid: c.info.copyUid, clock: { android: 7 }, value: null }
    ]
    const out = c.lan.apply(android)
    assert.strictEqual(out.conflicts, 0, '两边都让主 uid 存活，合并不再产生副本')
    assert.strictEqual(out.stats.cards.conflicts, 0)
    assert.strictEqual(out.stats.cards.added, 0)
    assert.strictEqual(out.stats.cards.deleted, 0)

    const cards = c.env.store.listStrict()
    assert.strictEqual(cards.length, 1, '业务上只有一条卡')
    assert.strictEqual(cards[0].id, c.info.baseRowId)
    assert.strictEqual(cards[0].front, '手机版本')

    const recs = c.lan.export()
    assert.strictEqual(recs.filter((r) => r.value && r.value.front === '手机版本').length, 1, '只有一条活记录')
    assert.strictEqual(recs.find((r) => r.uid === c.baseUid).value.front, '手机版本')
    assert.strictEqual(recs.find((r) => r.uid === c.info.copyUid).value, null)
    assert.strictEqual(recs.filter((r) => r.uid.indexOf('~conflict~') > 0 && r.value !== null).length, 0, '没有新的活副本')
    assert.strictEqual(recs.find((r) => r.uid === c.baseUid).clock.android, 7, '对端 clock 分量并进来了')

    const list = c.lan.listConflicts()
    assert.strictEqual(list.length, 1, '只剩那条带 resolved 的副本记录')
    assert.strictEqual(list[0].copyUid, c.info.copyUid)
    assert.strictEqual(list[0].resolved.pick, 'KEEP_COPY')
  } finally {
    cleanup(c.dir)
  }
})

test('KEEP_BOTH：两条都留、副本 uid 不变、再同步不重复', () => {
  const c = cardConflict()
  try {
    c.lan.resolveConflict(c.info.copyUid, 'KEEP_BOTH')
    const cards = c.env.store.listStrict()
    assert.strictEqual(cards.length, 2)

    const state = sidecarState(c.env)
    const baseRec = state.records.find((r) => r.uid === c.baseUid)
    const copyRec = state.records.find((r) => r.uid === c.info.copyUid)
    assert.ok(baseRec.value && copyRec.value)
    assert.deepStrictEqual(baseRec.clock, copyRec.clock, '两边 clock 抬到同一个 N')
    assert.ok(copyRec.uid === c.info.copyUid)
    assert.strictEqual(state.resolved[c.info.copyUid].pick, 'KEEP_BOTH')

    const pair = c.lan.listConflicts().find((x) => x.copyUid === c.info.copyUid)
    assert.ok(pair.baseValue && pair.copyValue)
    assert.strictEqual(pair.resolved.pick, 'KEEP_BOTH')

    const again = c.lan.apply(c.remote)
    assert.deepStrictEqual(again.stats.cards, {
      before: 2, after: 2, added: 0, updated: 0, deleted: 0, restored: 0, conflicts: 0
    })
    assert.strictEqual(c.env.store.listStrict().length, 2)
  } finally {
    cleanup(c.dir)
  }
})

test('嵌套副本：base 取最后一个 ~conflict~ 前的直接父，解决不动外层', () => {
  const c = cardConflict()
  try {
    const copyUid = c.info.copyUid
    const copyRec = c.lan.export().find((r) => r.uid === copyUid)
    assert.ok(copyRec && copyRec.value)
    const nestedRemote = [{
      uid: copyUid,
      clock: { [PEER]: 2 },
      value: Object.assign({}, copyRec.value, { front: '副本上的手机版本' })
    }]
    const nestedApply = c.lan.apply(nestedRemote)
    assert.strictEqual(nestedApply.conflicts, 1)

    const list = c.lan.listConflicts()
    const nested = list.find((x) => x.baseUid === copyUid)
    assert.ok(nested, '应有以副本为父的嵌套副本')
    assert.strictEqual(LanStore.baseUidOf(nested.copyUid), copyUid)
    assert.strictEqual(nested.kind, 'k')

    const out = c.lan.resolveConflict(nested.copyUid, 'KEEP_BASE')
    assert.strictEqual(out.baseUid, copyUid, '解决嵌套副本只能落在直接父上')
    assert.strictEqual(c.env.store.listStrict().length, 2, '外层 pair 仍然保留')

    const outer = c.lan.listConflicts().find((x) => x.copyUid === copyUid)
    assert.ok(outer && outer.baseValue && outer.copyValue)
    assert.strictEqual(outer.resolved, null)
  } finally {
    cleanup(c.dir)
  }
})

test('side 标注：主记录留本机 → localKept；留对端 → peerKept；副本墓碑后清理', () => {
  const local = cardConflict()
  try {
    assert.strictEqual(local.info.side, 'localKept', '主记录是本机版本时标 localKept')
    assert.strictEqual(local.info.baseValue.front, '电脑版本')
    assert.strictEqual(local.info.copyValue.front, '手机版本')
    assert.deepStrictEqual(sidecarState(local.env).sides, { [local.info.copyUid]: 'localKept' })

    // 副本墓碑后 side 没有意义，必须跟着清掉；已解决的副本仍可按 resolved 查到。
    local.lan.resolveConflict(local.info.copyUid, 'KEEP_BASE')
    assert.deepStrictEqual(sidecarState(local.env).sides, {})
    assert.strictEqual(local.lan.listConflicts().find((x) => x.copyUid === local.info.copyUid).side, 'unknown')
  } finally {
    cleanup(local.dir)
  }

  // 反向：远端值 canonical 更大 → 主记录留对端版本，副本是本机版本。
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    assert.strictEqual(env.store.add('aaa 本机', '背面', 'manual', T0).ok, true)
    env.hist.write([])
    const lan = lanFor(env)
    const base = lan.export().find((r) => r.value && r.value.front === 'aaa 本机')
    const remote = [{
      uid: base.uid,
      clock: { [PEER]: 1 },
      value: Object.assign({}, base.value, { front: 'zzz 手机' })
    }]
    assert.strictEqual(lan.apply(remote).stats.cards.conflicts, 1)

    const info = lan.listConflicts()[0]
    assert.strictEqual(info.side, 'peerKept')
    assert.strictEqual(info.baseValue.front, 'zzz 手机')
    assert.strictEqual(info.copyValue.front, 'aaa 本机')
    const sideState = sidecarState(env)
    assert.deepStrictEqual(sideState.sides, { [info.copyUid]: 'peerKept' })
    assert.ok(!Object.prototype.hasOwnProperty.call(sideState.sides, base.uid), 'side 只标冲突副本')
  } finally {
    cleanup(dir)
  }
})

test('history 败方：投影后删 binding+star，重新收藏不会复活副本', () => {
  const dir = tmpDir()
  try {
    const env = envFor(dir)
    const t = T0 + 1
    env.hist.write([
      { t: t, kind: 'clip', label: '电脑收藏', text: '电脑内容', src: 'answer', star: true }
    ])
    const lan = lanFor(env)
    const first = lan.export()
    const base = first.find((r) => r.value && r.value.text === '电脑内容')
    assert.ok(base)

    const remote = [{
      uid: base.uid,
      clock: { [PEER]: 1 },
      value: Object.assign({}, base.value, { label: '手机收藏', text: '手机内容' })
    }]
    assert.strictEqual(lan.apply(remote).stats.history.conflicts, 1)
    const info = lan.listConflicts()[0]
    assert.strictEqual(info.kind, 'h')
    const copyRowId = info.copyRowId
    assert.ok(Number.isSafeInteger(copyRowId))

    lan.resolveConflict(info.copyUid, 'KEEP_BASE')

    const state = sidecarState(env)
    assert.strictEqual(state.records.find((r) => r.uid === info.copyUid).value, null)
    assert.strictEqual(state.bindings[info.copyUid], undefined, 'history 败方必须删 binding')
    const rows = env.hist.readStrict()
    const loserRow = rows.find((e) => e.t === copyRowId)
    assert.ok(loserRow)
    assert.notStrictEqual(loserRow.star, true, 'history 败方必须去掉 star（stamp）')

    // 用户把败方那一行重新收藏：只能长出新 uid，不能让副本 uid 复活。
    env.hist.write(rows.map((e) => (e.t === copyRowId ? Object.assign({}, e, { star: true }) : e)))
    const after = lan.export()
    assert.strictEqual(after.find((r) => r.uid === info.copyUid).value, null, '副本 tombstone 不能被重新收藏复活')
    const revived = after.filter((r) => r.value && r.value.star === true && r.value.text === loserRow.text)
    assert.strictEqual(revived.length, 1)
    assert.notStrictEqual(revived[0].uid, info.copyUid)

    // 再同步手机同一份旧值：副本仍然墓碑，外层的解决结果不被翻盘。
    lan.apply(remote)
    const recs = lan.export()
    assert.strictEqual(recs.find((r) => r.uid === info.copyUid).value, null)
    assert.strictEqual(env.hist.readStrict().filter((e) => e.star === true).length, 2)
  } finally {
    cleanup(dir)
  }
})
