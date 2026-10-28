// v1.64：MCP 工具集 —— 权限 / 严格参数 / untrusted 标注 / 守卫 / 回滚归属 / 资源 / prompt。
// 只测这一层：Hub 的协议、Link 的加密通道各有自己的测试。
const test = require('node:test')
const assert = require('node:assert')
const os = require('node:os')
const path = require('node:path')
const fs = require('node:fs')
const K = require('../knowledge')
const M = require('../mcp-tools')

const CLIENT = { id: 'c1', name: 'Claude Code', scopes: ['read', 'write', 'act'] }
const READ_ONLY = { id: 'r1', name: '只读', scopes: ['read'] }

/** 一套临时环境：真实 Knowledge.Store + 假 link / 确认框。 */
function rig(over) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-mcp-'))
  const store = K.storeIn(dir)
  const state = {
    dir: dir,
    store: store,
    handouts: path.join(dir, 'handouts'),
    confirms: [],
    opened: [],
    confirmAnswer: true,
    guardCalls: 0,
    writes: 0,
    linkCalls: [],
    linkReply: null,
    linkError: null,
    audit: [],
    history: [],
    roots: [dir],
    now: 1760000000000,
  }
  const tools = M.createTools({
    store: () => state.store,
    history: () => state.history,
    guard: () => {
      state.guardCalls += 1
      if (state.guardThrows) throw new Error('journal pending')
      if (state.guardFalse) return false
    },
    afterWrite: () => { state.writes += 1 },
    dir: path.join(dir, 'mcp'),
    handoutsDir: state.handouts,
    version: '1.64.0',
    roots: () => state.roots,
    confirmFile: async (req) => { state.confirms.push(req); return state.confirmAnswer },
    openFile: async (p) => { state.opened.push(p); return state.openError || '' },
    audit: { record: (e) => state.audit.push(e) },
    now: () => state.now,
    link: {
      status: () => ({
        running: true, port: 47322, error: '',
        devices: [{ id: 'p1', name: 'Pixel', online: true, lastSeen: state.now, caps: ['memory', 'todo'], apps: [{ alias: 'calendar', name: '日历' }] }],
      }),
      call: async (tool, args, client, opts) => {
        state.linkCalls.push({ tool: tool, args: args, client: client, opts: opts })
        if (state.linkError) { const e = new Error('offline'); e.code = state.linkError; throw e }
        if (typeof state.linkReply === 'function') return state.linkReply(tool, args, client)
        return state.linkReply || {}
      },
    },
    ...(over || {}),
  })
  state.tools = tools
  state.cleanup = () => fs.rmSync(dir, { recursive: true, force: true })
  return state
}

/* ---------------- 工具清单 / 权限 ---------------- */

test('definitions：10 个工具，按 scope 收窄能力面', () => {
  const r = rig()
  try {
    const all = r.tools.definitions(CLIENT).map((d) => d.name)
    assert.deepStrictEqual(all, [
      'ling_memory_search', 'ling_memory_add', 'ling_cards_search', 'ling_cards_add', 'ling_weak_points',
      'ling_reminder_create', 'ling_phone_screen_read', 'ling_phone_open_app', 'ling_desktop_open_file', 'ling_handout_save',
    ])
    assert.deepStrictEqual(r.tools.definitions(READ_ONLY).map((d) => d.name),
      ['ling_memory_search', 'ling_cards_search', 'ling_weak_points'])
    assert.deepStrictEqual(r.tools.definitions(null), [])
  } finally { r.cleanup() }
})

test('definitions：inputSchema 是原始 JSON Schema，且 additionalProperties:false', () => {
  const r = rig()
  try {
    for (const d of r.tools.definitions(CLIENT)) {
      assert.strictEqual(d.inputSchema.type, 'object', d.name)
      assert.strictEqual(d.inputSchema.additionalProperties, false, d.name)
      assert.ok(['L1', 'L2', 'L3'].indexOf(d.level) >= 0, d.name)
      assert.ok(d.annotations && typeof d.annotations.title === 'string', d.name)
    }
  } finally { r.cleanup() }
})

test('definitions：client.tools 白名单只放行指定工具', async () => {
  const r = rig()
  try {
    const one = { id: 'x', scopes: ['read', 'write'], tools: ['ling_handout_save'] }
    assert.deepStrictEqual(r.tools.definitions(one).map((d) => d.name), ['ling_handout_save'])
    // 白名单外的工具即使有 scope 也调不动
    await assert.rejects(() => r.tools.call('ling_memory_add', { content: '一个事实' }, one), (e) => e.code === 'DENIED')
  } finally { r.cleanup() }
})

test('call：没有 scope 一律 DENIED，且不产生任何副作用', async () => {
  const r = rig()
  try {
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 'Q', back: 'A' }, READ_ONLY), (e) => e.code === 'DENIED')
    await assert.rejects(() => r.tools.call('ling_phone_open_app', { app: 'calendar', reason: '看看' }, READ_ONLY), (e) => e.code === 'DENIED')
    await assert.rejects(() => r.tools.call('ling_no_such_tool', {}, CLIENT), (e) => e.code === 'NOT_FOUND')
    assert.strictEqual(r.store.listStrict().length, 0)
    assert.strictEqual(r.linkCalls.length, 0)
  } finally { r.cleanup() }
})

test('call：未授权工具对任何 client 都返回 NOT_FOUND（名字不存在）', async () => {
  const r = rig()
  try {
    await assert.rejects(() => r.tools.call('ling_bogus', {}, { id: 'z', scopes: ['read', 'write', 'act', 'personal'] }),
      (e) => e.code === 'NOT_FOUND')
  } finally { r.cleanup() }
})

/* ---------------- 严格参数 ---------------- */

test('参数：多一个字段就 INVALID_ARGS（严格 allowlist）', async () => {
  const r = rig()
  try {
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 'Q', back: 'A', sneaky: 1 }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_memory_search', { query: 'x', extra: 'y' }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
  } finally { r.cleanup() }
})

test('参数：缺必填 / 类型错 / 越界 / 枚举外都拒', async () => {
  const r = rig()
  try {
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 'Q' }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 1, back: 'A' }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 'x'.repeat(81), back: 'A' }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_cards_search', { limit: 51 }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_memory_add', { content: '事实', category: 'EMOTION' }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_phone_open_app', { app: 'My App!', reason: '看看' }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
  } finally { r.cleanup() }
})

test('参数：due_at 必须带时区', async () => {
  const r = rig()
  try {
    await assert.rejects(() => r.tools.call('ling_reminder_create', { text: '背书', due_at: '2026-11-01T20:00:00' }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_reminder_create', { text: '背书', due_at: '明天八点' }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
  } finally { r.cleanup() }
})

/* ---------------- 本地知识卡 ---------------- */

test('cards_add：写卡带 origin、过守卫、刷新 UI；重复调用是幂等（dup）', async () => {
  const r = rig()
  try {
    const a = await r.tools.call('ling_cards_add', { front: '导数是什么', back: '瞬时变化率' }, CLIENT)
    assert.strictEqual(a.ok, true)
    assert.strictEqual(a.origin, 'mcp:c1')
    const cards = r.store.listStrict()
    assert.strictEqual(cards.length, 1)
    assert.strictEqual(cards[0].origin, 'mcp:c1')
    assert.strictEqual(r.guardCalls, 1)
    assert.strictEqual(r.writes, 1)
    const b = await r.tools.call('ling_cards_add', { front: '导数是什么', back: '改成别的' }, CLIENT)
    assert.strictEqual(b.ok, false)
    assert.strictEqual(b.reason, 'dup')
    assert.strictEqual(r.store.listStrict().length, 1)
  } finally { r.cleanup() }
})

test('cards_add：守卫抛错 / 返回 false 都是 GUARD_BLOCKED，绝不假成功', async () => {
  const r = rig()
  try {
    r.guardThrows = true
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 'Q', back: 'A' }, CLIENT), (e) => e.code === 'GUARD_BLOCKED')
    r.guardThrows = false
    r.guardFalse = true
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 'Q2', back: 'A2' }, CLIENT), (e) => e.code === 'GUARD_BLOCKED')
    assert.strictEqual(r.store.listStrict().length, 0)
    assert.strictEqual(r.writes, 0)
  } finally { r.cleanup() }
})

test('cards_add：卡库损坏时报 INTERNAL，不拿空库覆盖', async () => {
  const r = rig()
  try {
    fs.writeFileSync(path.join(r.dir, 'knowledge.json'), '{坏掉')
    await assert.rejects(() => r.tools.call('ling_cards_add', { front: 'Q', back: 'A' }, CLIENT), (e) => e.code === 'INTERNAL')
    // 原文件没被覆盖
    assert.strictEqual(fs.readFileSync(path.join(r.dir, 'knowledge.json'), 'utf8'), '{坏掉')
  } finally { r.cleanup() }
})

test('cards_search：BM25 命中 / 无命中返回空 / 空查询按到期排', async () => {
  const r = rig()
  try {
    await r.tools.call('ling_cards_add', { front: '什么是导数', back: '切线斜率' }, CLIENT)
    await r.tools.call('ling_cards_add', { front: '牛顿第二定律', back: 'F = ma' }, CLIENT)
    const hit = await r.tools.call('ling_cards_search', { query: '导数' }, CLIENT)
    assert.strictEqual(hit.matched, 1)
    assert.strictEqual(hit.cards[0].front.value, '什么是导数')
    // 卡片正文是 untrusted
    assert.strictEqual(hit.cards[0].front.untrusted, true)
    assert.strictEqual(hit.cards[0].front.source, 'knowledge_card')
    const none = await r.tools.call('ling_cards_search', { query: '光合作用' }, CLIENT)
    assert.strictEqual(none.matched, 0)
    const all = await r.tools.call('ling_cards_search', {}, CLIENT)
    assert.strictEqual(all.cards.length, 2)
    assert.strictEqual(all.library, 2)
  } finally { r.cleanup() }
})

test('cards_search：deck / due_only / limit 都生效', async () => {
  const r = rig()
  try {
    await r.tools.call('ling_cards_add', { front: '导数A', back: 'a', deck: '数学' }, CLIENT)
    await r.tools.call('ling_cards_add', { front: '导数B', back: 'b', deck: '物理' }, CLIENT)
    const deck = await r.tools.call('ling_cards_search', { query: '导数', deck: '数学' }, CLIENT)
    assert.strictEqual(deck.matched, 1)
    assert.strictEqual(deck.cards[0].deck, '数学')
    // 存入即首刷（Good）→ due 在未来 10 分钟，此刻不是到期卡；把时钟推过去才是到期。
    const notDueYet = await r.tools.call('ling_cards_search', { due_only: true }, CLIENT)
    assert.strictEqual(notDueYet.cards.length, 0)
    r.now += 30 * 60 * 1000
    const due = await r.tools.call('ling_cards_search', { due_only: true }, CLIENT)
    assert.strictEqual(due.cards.length, 2)
    const limited = await r.tools.call('ling_cards_search', { limit: 1 }, CLIENT)
    assert.strictEqual(limited.cards.length, 1)
  } finally { r.cleanup() }
})

test('weak_points：lapses / 难度 / Relearning 排序 + 收藏错题（untrusted、截断）', async () => {
  const r = rig()
  try {
    r.store.replaceFromSync([
      { id: 1, deck: '数学', front: '弱1', back: 'b', source: 'manual', createdAt: r.now, fsrs: { state: 2, lapses: 3, difficulty: 8, due: r.now - 1000, reps: 5 } },
      { id: 2, deck: '数学', front: '弱2', back: 'b', source: 'manual', createdAt: r.now, fsrs: { state: 3, lapses: 0, difficulty: 3, due: r.now - 1000, reps: 2 } },
      { id: 3, deck: '数学', front: '好卡', back: 'b', source: 'manual', createdAt: r.now, fsrs: { state: 2, lapses: 0, difficulty: 2, due: r.now, reps: 1 } },
    ])
    r.history = [{ t: r.now - 1000, kind: 'solve', star: true, label: '考研·数学', text: 'x'.repeat(2000) }]
    const out = await r.tools.call('ling_weak_points', {}, CLIENT)
    assert.deepStrictEqual(out.weak_cards.map((c) => c.id), [1, 2])
    assert.strictEqual(out.weak_cards[0].score, 7)
    assert.strictEqual(out.starred_mistakes.length, 1)
    assert.strictEqual(out.starred_mistakes[0].text.untrusted, true)
    assert.strictEqual(out.starred_mistakes[0].text.source, 'screen')
    assert.strictEqual(out.starred_mistakes[0].text.value.length, 800)
  } finally { r.cleanup() }
})

test('weak_points：历史读失败 → INTERNAL，不假装没有错题', async () => {
  const r = rig({ history: () => { throw new Error('history.json 坏了') } })
  try {
    await assert.rejects(() => r.tools.call('ling_weak_points', {}, CLIENT), (e) => e.code === 'INTERNAL')
  } finally { r.cleanup() }
})

/* ---------------- 手机工具 ---------------- */

test('memory_search：走 link、私密类别按 personal 过滤、正文 untrusted', async () => {
  const r = rig()
  try {
    r.linkReply = {
      items: [
        { content: '喜欢猫', category: 'PREFERENCE', importance: 0.6, at: 1, score: 0.31 },
        { content: '最近焦虑', category: 'EMOTION', importance: 0.9, at: 1, score: 0.5 },
      ],
      persona: 'p0', total: 2, dropped_personal: 1,
    }
    const plain = await r.tools.call('ling_memory_search', { query: '猫' }, CLIENT)
    assert.deepStrictEqual(plain.items.map((i) => i.category), ['PREFERENCE'])
    assert.strictEqual(plain.hidden_personal, 1)
    assert.strictEqual(plain.items[0].content.untrusted, true)
    assert.strictEqual(plain.items[0].content.source, 'memory')
    assert.strictEqual(r.linkCalls[0].tool, 'memory_search')
    assert.strictEqual(r.linkCalls[0].opts.level, 'L1')

    const personal = await r.tools.call('ling_memory_search', { query: '猫' }, { id: 'p', scopes: ['read', 'personal'] })
    assert.deepStrictEqual(personal.items.map((i) => i.category), ['PREFERENCE', 'EMOTION'])
  } finally { r.cleanup() }
})

test('memory_search：没有 personal 却只要私密类别 → DENIED，不乱查', async () => {
  const r = rig()
  try {
    await assert.rejects(() => r.tools.call('ling_memory_search', { query: 'x', categories: ['EMOTION'] }, CLIENT),
      (e) => e.code === 'DENIED')
    assert.strictEqual(r.linkCalls.length, 0)
  } finally { r.cleanup() }
})

test('memory_add：内容原样透传（不加前缀）、结果带 origin、手机报错按 code 传播', async () => {
  const r = rig()
  try {
    r.linkReply = { ok: true, merged: false, total: 12, id: 'abc123', origin: 'mcp:c1' }
    const out = await r.tools.call('ling_memory_add', { content: '喜欢用 FSRS 复习' }, CLIENT)
    assert.strictEqual(out.ok, true)
    assert.strictEqual(out.total, 12)
    assert.strictEqual(out.origin, 'mcp:c1')
    assert.strictEqual(r.linkCalls[0].args.content, '喜欢用 FSRS 复习')
    assert.strictEqual(r.linkCalls[0].args.category, 'FACT')

    r.linkReply = () => { const e = new Error('手机待办已满'); e.code = 'RATE_LIMITED'; throw e }
    await assert.rejects(() => r.tools.call('ling_memory_add', { content: '再次写入' }, CLIENT), (e) => e.code === 'RATE_LIMITED')
  } finally { r.cleanup() }
})

test('reminder_create：时间范围在桌面先卡死，不做无谓的手机往返', async () => {
  const r = rig()
  try {
    const soon = new Date(r.now + 30 * 1000).toISOString()
    await assert.rejects(() => r.tools.call('ling_reminder_create', { text: '背书', due_at: soon }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
    const far = new Date(r.now + 400 * 86400000).toISOString()
    await assert.rejects(() => r.tools.call('ling_reminder_create', { text: '背书', due_at: far }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
    assert.strictEqual(r.linkCalls.length, 0)

    r.linkReply = { ok: true, id: '42', due_at: '2026-11-01T20:00:00.000Z', exact: false, channel: 'alarm' }
    const ok = await r.tools.call('ling_reminder_create', {
      text: '背书', due_at: new Date(r.now + 86400000).toISOString(),
    }, CLIENT)
    assert.strictEqual(ok.ok, true)
    assert.strictEqual(ok.exact, false)
    assert.ok(ok.note.indexOf('精确') >= 0)
    assert.strictEqual(r.linkCalls[0].opts.level, 'L2')
  } finally { r.cleanup() }
})

test('phone_screen_read：只回文字，不回任何图片字段', async () => {
  const r = rig()
  try {
    r.linkReply = {
      text: { untrusted: true, source: 'phone_screen', value: '题目：求导' },
      chars: 6, app_hint: 'com.example',
      answer: { untrusted: true, source: 'phone_screen', value: '答案是 2x' },
      screenshot: 'BASE64SHOULDNEVERLEAK',
    }
    const out = await r.tools.call('ling_phone_screen_read', { purpose: '看这道题' }, CLIENT)
    assert.strictEqual(out.text.value, '题目：求导')
    assert.strictEqual(out.text.untrusted, true)
    assert.strictEqual(out.screenshot_shared, false)
    assert.strictEqual(out.answer.value, '答案是 2x')
    assert.strictEqual(JSON.stringify(out).indexOf('BASE64SHOULDNEVERLEAK'), -1)
    assert.strictEqual(r.linkCalls[0].opts.level, 'L3')
  } finally { r.cleanup() }
})

test('phone_open_app：只发别名，包名由手机解析', async () => {
  const r = rig()
  try {
    r.linkReply = { ok: true, opened: 'calendar' }
    const out = await r.tools.call('ling_phone_open_app', { app: 'calendar', reason: '看今天安排' }, CLIENT)
    assert.strictEqual(out.opened, 'calendar')
    assert.deepStrictEqual(r.linkCalls[0].args, { app: 'calendar', reason: '看今天安排' })
    assert.strictEqual(r.linkCalls[0].opts.level, 'L3')
  } finally { r.cleanup() }
})

test('手机通道没启用 → NOT_AVAILABLE；离线 → PHONE_OFFLINE', async () => {
  const r = rig({ link: null })
  try {
    await assert.rejects(() => r.tools.call('ling_phone_open_app', { app: 'calendar', reason: '看看' }, CLIENT),
      (e) => e.code === 'NOT_AVAILABLE')
  } finally { r.cleanup() }
  const r2 = rig()
  try {
    r2.linkError = 'PHONE_OFFLINE'
    await assert.rejects(() => r2.tools.call('ling_phone_open_app', { app: 'calendar', reason: '看看' }, CLIENT),
      (e) => e.code === 'PHONE_OFFLINE')
  } finally { r2.cleanup() }
})

/* ---------------- 桌面打开文件 ---------------- */

test('desktop_open_file：正常路径 → 确认 → 打开（realpath 后才给确认框）', async () => {
  const r = rig()
  try {
    const f = path.join(r.dir, '讲义.pdf')
    fs.writeFileSync(f, 'x')
    const out = await r.tools.call('ling_desktop_open_file', { path: f, reason: '看讲义' }, CLIENT)
    assert.strictEqual(out.ok, true)
    assert.strictEqual(out.opened, fs.realpathSync(f))
    assert.strictEqual(r.confirms.length, 1)
    assert.strictEqual(r.confirms[0].client.name, 'Claude Code')
    assert.strictEqual(r.confirms[0].path, fs.realpathSync(f))
    assert.strictEqual(r.confirms[0].reason, '看讲义')
    assert.deepStrictEqual(r.opened, [fs.realpathSync(f)])
  } finally { r.cleanup() }
})

test('desktop_open_file：用户拒绝 → DENIED，不打开', async () => {
  const r = rig()
  try {
    const f = path.join(r.dir, '讲义.pdf')
    fs.writeFileSync(f, 'x')
    r.confirmAnswer = false
    await assert.rejects(() => r.tools.call('ling_desktop_open_file', { path: f, reason: '看讲义' }, CLIENT),
      (e) => e.code === 'DENIED')
    assert.deepStrictEqual(r.opened, [])
  } finally { r.cleanup() }
})

test('desktop_open_file：可执行 / 脚本 / 会跳转的扩展名一律 DENIED（连确认框都不弹）', async () => {
  const r = rig()
  try {
    for (const name of ['evil.command', 'run.sh', 'app.app', 'page.webloc', 'x.html', 'a.desktop']) {
      const f = path.join(r.dir, name)
      fs.writeFileSync(f, 'x')
      await assert.rejects(() => r.tools.call('ling_desktop_open_file', { path: f, reason: '看资料' }, CLIENT),
        (e) => e.code === 'DENIED' || e.code === 'INVALID_ARGS', name)
    }
    assert.strictEqual(r.confirms.length, 0)
  } finally { r.cleanup() }
})

test('desktop_open_file：白名单外的目录 / 越界符号链接 / 目录 / 不存在都拒', async () => {
  const r = rig()
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-out-'))
  try {
    const secret = path.join(outside, 'secret.pdf')
    fs.writeFileSync(secret, 'x')
    await assert.rejects(() => r.tools.call('ling_desktop_open_file', { path: secret, reason: '看看' }, CLIENT),
      (e) => e.code === 'DENIED')

    const link = path.join(r.dir, 'link.pdf')
    fs.symlinkSync(secret, link)
    await assert.rejects(() => r.tools.call('ling_desktop_open_file', { path: link, reason: '看看' }, CLIENT),
      (e) => e.code === 'DENIED')

    await assert.rejects(() => r.tools.call('ling_desktop_open_file', { path: r.dir, reason: '看看' }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
    await assert.rejects(() => r.tools.call('ling_desktop_open_file', { path: path.join(r.dir, 'nope.pdf'), reason: '看看' }, CLIENT),
      (e) => e.code === 'NOT_FOUND')
    await assert.rejects(() => r.tools.call('ling_desktop_open_file', { path: 'relative.pdf', reason: '看看' }, CLIENT),
      (e) => e.code === 'INVALID_ARGS')
    assert.strictEqual(r.confirms.length, 0)
  } finally { r.cleanup(); fs.rmSync(outside, { recursive: true, force: true }) }
})

test('desktop_open_file：确认事件进审计，带 approved/denied 与耗时', async () => {
  const r = rig()
  try {
    const f = path.join(r.dir, 'x.pdf')
    fs.writeFileSync(f, 'x')
    await r.tools.call('ling_desktop_open_file', { path: f, reason: '看资料' }, CLIENT)
    const ev = r.audit.filter((e) => e.event === 'confirm')
    assert.strictEqual(ev.length, 1)
    assert.strictEqual(ev[0].confirm, 'approved')
    assert.strictEqual(ev[0].tool, 'ling_desktop_open_file')
    assert.strictEqual(ev[0].target, 'desktop:x.pdf')
    assert.strictEqual(typeof ev[0].ts, 'number')
    assert.strictEqual(typeof ev[0].ms, 'number')
    // 完整路径不进审计（路径本身就是隐私）
    assert.strictEqual(JSON.stringify(ev[0]).indexOf(r.dir), -1)
  } finally { r.cleanup() }
})

/* ---------------- 讲义 ---------------- */

test('handout_save：Markdown 落盘（带来源说明）、卡片进库并带 origin', async () => {
  const r = rig()
  try {
    const out = await r.tools.call('ling_handout_save', {
      title: '错题复习', markdown: '## 错因\n没记住公式', source_ids: [7],
      cards: [{ front: '导数定义', back: '极限' }],
    }, CLIENT)
    assert.strictEqual(out.ok, true)
    assert.strictEqual(out.cards_added, 1)
    assert.ok(out.path.startsWith(fs.realpathSync(r.handouts)))
    const body = fs.readFileSync(out.path, 'utf8')
    assert.ok(body.indexOf('# 错题复习') === 0)
    assert.ok(body.indexOf('Claude Code') >= 0, '要标出保存方')
    assert.ok(body.indexOf('没记住公式') >= 0)
    const cards = r.store.listStrict()
    assert.strictEqual(cards.length, 1)
    assert.strictEqual(cards[0].deck, '错题讲义')
    assert.strictEqual(cards[0].origin, 'mcp:c1')
    assert.strictEqual(r.writes, 1)
  } finally { r.cleanup() }
})

test('handout_save：同名不覆盖（加序号），守卫失败不落半截文件', async () => {
  const r = rig()
  try {
    const a = await r.tools.call('ling_handout_save', { title: '同一份', markdown: 'A' }, CLIENT)
    r.now += 1000
    const b = await r.tools.call('ling_handout_save', { title: '同一份', markdown: 'B' }, CLIENT)
    assert.notStrictEqual(a.path, b.path)
    assert.strictEqual(fs.readFileSync(a.path, 'utf8').indexOf('A') > 0, true)

    r.guardThrows = true
    const before = fs.readdirSync(fs.realpathSync(r.handouts)).length
    await assert.rejects(() => r.tools.call('ling_handout_save', {
      title: '失败的一次', markdown: 'X', cards: [{ front: 'Q', back: 'A' }],
    }, CLIENT), (e) => e.code === 'GUARD_BLOCKED')
    assert.strictEqual(fs.readdirSync(fs.realpathSync(r.handouts)).length, before, '守卫失败不该留下讲义文件')
  } finally { r.cleanup() }
})

test('handout_save：单张卡失败不影响讲义落盘，失败原因如实返回', async () => {
  const r = rig()
  try {
    // 去重是「同牌组同正面」：先在讲义默认牌组里放一张，才测得到 dup
    await r.tools.call('ling_cards_add', { front: '重复正面', back: 'A', deck: '错题讲义' }, CLIENT)
    const out = await r.tools.call('ling_handout_save', {
      title: '有重复', markdown: 'body',
      cards: [{ front: '重复正面', back: 'A' }, { front: '新正面', back: 'B' }],
    }, CLIENT)
    assert.strictEqual(out.cards_added, 1)
    assert.strictEqual(out.cards_failed.length, 1)
    assert.strictEqual(out.cards_failed[0].reason, 'dup')
    assert.ok(fs.existsSync(out.path))
  } finally { r.cleanup() }
})

/* ---------------- 回滚 ---------------- */

test('rollback：只删归属本客户端且没被改过的卡；用户改过的保留', async () => {
  const r = rig()
  try {
    const mine = await r.tools.call('ling_cards_add', { front: '我写的', back: 'A' }, CLIENT)
    const other = await r.tools.call('ling_cards_add', { front: '别人写的', back: 'B' }, { id: 'c2', scopes: ['read', 'write'] })
    // 用户改了「我写的」的背面 → 不许删
    r.store.replaceFromSync(r.store.listStrict().map((c) => (c.id === mine.id ? Object.assign({}, c, { back: '用户改写' }) : c)))
    const out = await r.tools.rollback('c1')
    assert.strictEqual(out.cards_removed, 0)
    assert.strictEqual(out.cards_skipped_edited, 1)
    const left = r.store.listStrict().map((c) => c.id)
    assert.deepStrictEqual(left.sort(), [mine.id, other.id].sort())

    // 再撤一次（用户没再动）：这次也不该删「我写的」（还记着它是被改过的）
    const out2 = await r.tools.rollback('c1')
    assert.strictEqual(out2.cards_removed, 0)
    // 撤另一个客户端：只删它自己那张
    const out3 = await r.tools.rollback('c2')
    assert.strictEqual(out3.cards_removed, 1)
    assert.deepStrictEqual(r.store.listStrict().map((c) => c.id), [mine.id])
  } finally { r.cleanup() }
})

test('rollback：用户已经复习过的卡保留（skipped_reviewed）', async () => {
  const r = rig()
  try {
    const a = await r.tools.call('ling_cards_add', { front: '复习过', back: 'A' }, CLIENT)
    r.store.answer(a.id, 'Good', r.now + 1000)
    const out = await r.tools.rollback('c1')
    assert.strictEqual(out.cards_removed, 0)
    assert.strictEqual(out.cards_skipped_reviewed, 1)
    assert.deepStrictEqual(r.store.listStrict().map((c) => c.id), [a.id])
  } finally { r.cleanup() }
})

test('rollback：没改过的卡真删掉，讲义只删内容未变的', async () => {
  const r = rig()
  try {
    const a = await r.tools.call('ling_cards_add', { front: '干净卡', back: 'A' }, CLIENT)
    const h = await r.tools.call('ling_handout_save', { title: '干净讲义', markdown: 'M' }, CLIENT)
    const h2 = await r.tools.call('ling_handout_save', { title: '改过的讲义', markdown: 'N' }, CLIENT)
    fs.appendFileSync(h2.path, '\n用户补充')
    const out = await r.tools.rollback('c1')
    assert.strictEqual(out.cards_removed, 1)
    assert.strictEqual(out.handouts_removed, 1)
    assert.strictEqual(out.handouts_skipped_edited, 1)
    assert.strictEqual(r.store.listStrict().length, 0)
    assert.strictEqual(fs.existsSync(h.path), false)
    assert.strictEqual(fs.existsSync(h2.path), true, '用户改过的讲义必须留着')
    assert.ok(a.id > 0)
  } finally { r.cleanup() }
})

test('rollback：台账损坏时 fail-safe —— 没有归属证据就不删任何卡，坏台账留证据', async () => {
  const r = rig()
  try {
    await r.tools.call('ling_cards_add', { front: 'A', back: 'a' }, CLIENT)
    await r.tools.call('ling_cards_add', { front: 'B', back: 'b' }, { id: 'c2', scopes: ['read', 'write'] })
    // 换一份全新的 tools 实例（模拟重启）：它第一次读台账就是坏文件
    const fresh = M.createTools({
      store: () => r.store,
      history: () => [],
      guard: () => {},
      afterWrite: () => {},
      dir: path.join(r.dir, 'mcp'),
      handoutsDir: r.handouts,
      version: '1.64.0',
      roots: () => [r.dir],
      confirmFile: async () => true,
      openFile: async () => '',
      now: () => r.now,
    })
    fs.writeFileSync(path.join(r.dir, 'mcp', 'rollback.json'), '{坏台账')
    const out = await fresh.rollback('c1')
    assert.strictEqual(out.cards_removed, 0, '没有归属证据就不许删卡')
    assert.strictEqual(r.store.listStrict().length, 2)
    const files = fs.readdirSync(path.join(r.dir, 'mcp'))
    assert.ok(files.some((f) => f.indexOf('rollback.json.bad-') === 0), '坏台账要留证据')
  } finally { r.cleanup() }
})

test('rollback：手机侧失败如实返回，不假装成功', async () => {
  const r = rig()
  try {
    r.linkError = 'PHONE_OFFLINE'
    const out = await r.tools.rollback('c1')
    assert.strictEqual(out.phone.ok, false)
    assert.strictEqual(out.phone.code, 'PHONE_OFFLINE')
    assert.strictEqual(r.audit.filter((e) => e.event === 'rollback').length, 1)
  } finally { r.cleanup() }
})

/* ---------------- 资源 ---------------- */

test('resources：3 个 JSON 资源；没有 read 就不列', () => {
  const r = rig()
  try {
    const list = r.tools.resources(CLIENT)
    assert.deepStrictEqual(list.map((x) => x.uri), ['ling://status', 'ling://review/today', 'ling://profile/learner'])
    for (const x of list) assert.strictEqual(x.mimeType, 'application/json')
    assert.deepStrictEqual(r.tools.resources({ id: 'n', scopes: [] }), [])
  } finally { r.cleanup() }
})

test('ling://status：给版本 / 到期 / 手机能力 / 白名单别名 / scopes，且不含密钥', async () => {
  const r = rig()
  try {
    await r.tools.call('ling_cards_add', { front: 'Q', back: 'A' }, CLIENT)
    const s = await r.tools.readResource('ling://status', CLIENT)
    assert.strictEqual(s.desktop.version, '1.64.0')
    assert.strictEqual(s.desktop.cards.count, 1)
    assert.strictEqual(s.phone.online, 1)
    assert.deepStrictEqual(s.phone.apps.map((a) => a.alias), ['calendar'])
    assert.deepStrictEqual(s.client.scopes, ['read', 'write', 'act'])
    const text = JSON.stringify(s)
    assert.strictEqual(text.indexOf('key'), -1, 'status 里不该出现 key 字段')
    assert.strictEqual(text.indexOf('token'), -1)
  } finally { r.cleanup() }
})

test('ling://review/today：到期数 / 按牌组分布 / 前 20 张只看正面', async () => {
  const r = rig()
  try {
    await r.tools.call('ling_cards_add', { front: '一', back: '背面秘密' }, CLIENT)
    await r.tools.call('ling_cards_add', { front: '二', back: 'x', deck: '数学' }, CLIENT)
    // 新卡存入即首刷 → 10 分钟后才到期；把时钟推过去才是「今天到期」
    r.now += 30 * 60 * 1000
    const t = await r.tools.readResource('ling://review/today', CLIENT)
    assert.strictEqual(t.due_count, 2)
    assert.strictEqual(t.cards.length, 2)
    assert.strictEqual(t.cards[0].front.untrusted, true)
    assert.strictEqual(JSON.stringify(t).indexOf('背面秘密'), -1, '资源里不该出现背面')
    assert.deepStrictEqual(t.decks.map((d) => d.deck).sort(), ['数学', '默认'].sort())
  } finally { r.cleanup() }
})

test('ling://profile/learner：桌面统计 + 手机画像；私密类仍受 personal 过滤', async () => {
  const r = rig()
  try {
    await r.tools.call('ling_cards_add', { front: 'Q', back: 'A' }, CLIENT)
    r.linkReply = {
      preferred_name: '小林', goal: '考研', topics: ['数学'],
      memory: [{ content: '爱用番茄钟', category: 'HABIT', importance: 0.6 }, { content: '压力大', category: 'EMOTION', importance: 0.9 }],
    }
    const p = await r.tools.readResource('ling://profile/learner', CLIENT)
    assert.strictEqual(p.desktop.cards.total, 1)
    assert.strictEqual(p.phone.available, true)
    assert.strictEqual(p.phone.memory.length, 1)
    assert.strictEqual(p.phone.goal.untrusted, true)
    const withPersonal = await r.tools.readResource('ling://profile/learner', { id: 'p', scopes: ['read', 'personal'] })
    assert.strictEqual(withPersonal.phone.memory.length, 2)
  } finally { r.cleanup() }
})

test('ling://profile/learner：手机离线也给桌面部分，并说明原因', async () => {
  const r = rig()
  try {
    r.linkError = 'PHONE_OFFLINE'
    const p = await r.tools.readResource('ling://profile/learner', CLIENT)
    assert.strictEqual(p.phone.available, false)
    assert.strictEqual(p.phone.code, 'PHONE_OFFLINE')
    assert.strictEqual(typeof p.desktop.cards.total, 'number')
  } finally { r.cleanup() }
})

test('readResource：未知 uri → NOT_FOUND；没有 read → DENIED', async () => {
  const r = rig()
  try {
    await assert.rejects(() => r.tools.readResource('ling://nope', CLIENT), (e) => e.code === 'NOT_FOUND')
    await assert.rejects(() => r.tools.readResource('ling://status', { id: 'n', scopes: [] }), (e) => e.code === 'DENIED')
  } finally { r.cleanup() }
})

/* ---------------- prompt ---------------- */

test('prompts：只列 1 个 prompt，参数是 deck / days / style', () => {
  const r = rig()
  try {
    const list = r.tools.prompts(CLIENT)
    assert.strictEqual(list.length, 1)
    assert.strictEqual(list[0].name, 'ling_review_handout')
    assert.deepStrictEqual(list[0].arguments.map((a) => a.name), ['deck', 'days', 'style'])
    assert.deepStrictEqual(r.tools.prompts({ id: 'n', scopes: [] }), [])
  } finally { r.cleanup() }
})

test('getPrompt：有 write 时指向 ling_handout_save，没有 write 时不教模型调它', () => {
  const r = rig()
  try {
    const rw = r.tools.getPrompt('ling_review_handout', { deck: '数学', days: '3', style: '苏格拉底' }, CLIENT)
    assert.strictEqual(rw.messages[0].role, 'user')
    const text = rw.messages[0].content.text
    assert.ok(text.indexOf('ling_weak_points') >= 0)
    assert.ok(text.indexOf('ling_cards_search') >= 0)
    assert.ok(text.indexOf('ling_handout_save') >= 0)
    assert.ok(text.indexOf('数学') >= 0)
    assert.ok(text.indexOf('3') >= 0)
    assert.ok(text.indexOf('苏格拉底') >= 0)
    assert.ok(text.indexOf('untrusted') >= 0, '要提醒不可信')

    const ro = r.tools.getPrompt('ling_review_handout', {}, READ_ONLY).messages[0].content.text
    assert.strictEqual(ro.indexOf('最后调用 ling_handout_save'), -1)
    assert.ok(ro.indexOf('直接输出') >= 0)
    assert.ok(ro.indexOf('ling_weak_points') >= 0, '只读工具仍可引用')
  } finally { r.cleanup() }
})

test('getPrompt：参数越界 / 未知字段 / 未知 prompt 都拒', () => {
  const r = rig()
  try {
    assert.throws(() => r.tools.getPrompt('ling_review_handout', { days: '0' }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    assert.throws(() => r.tools.getPrompt('ling_review_handout', { days: '400' }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    assert.throws(() => r.tools.getPrompt('ling_review_handout', { style: '随便' }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    assert.throws(() => r.tools.getPrompt('ling_review_handout', { evil: '1' }, CLIENT), (e) => e.code === 'INVALID_ARGS')
    assert.throws(() => r.tools.getPrompt('nope', {}, CLIENT), (e) => e.code === 'NOT_FOUND')
    assert.throws(() => r.tools.getPrompt('ling_review_handout', {}, READ_ONLY_MISSING_READ), (e) => e.code === 'DENIED')
  } finally { r.cleanup() }
})

const READ_ONLY_MISSING_READ = { id: 'n', scopes: [] }

test('handoutsDir：暴露给设置页，默认在 dir 旁边', () => {
  const r = rig({ handoutsDir: undefined })
  try {
    assert.strictEqual(r.tools.handoutsDir, path.join(path.dirname(path.join(r.dir, 'mcp')), 'handouts'))
  } finally { r.cleanup() }
})
