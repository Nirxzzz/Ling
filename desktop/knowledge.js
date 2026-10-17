/* v1.61（对齐安卓 v1.69 KnowledgeCard.kt + v1.70 KnowledgeImport.kt）：知识卡 · 纯 JS / 纯 Node。
 *
 * ## 这是什么
 * 可复习的问答卡（跟「记忆卡」不是一回事 —— 那张是给小球自己看的人设记忆，这张是给你自己复习的）。
 * 存进 userData/knowledge.json，调度复用 [fsrs]：**存入即 Good 首刷**，到期用 Fsrs.dueCards。
 *
 * ## 数据格式（为三端同步打底）
 * knowledge.json 是一个数组，元素：
 * ```
 * { id, deck, front, back, source, createdAt, fsrs }
 * ```
 * fsrs 是 Fsrs.toJson 的结构（snake_case，跟 ts-fsrs / 安卓一致）。
 * **这个结构与安卓端逐字段一致，两端文件可以直接互换。**
 *
 * ## 与安卓的两处差异（有意为之）
 * 1. **front 的取法多了「追问」这一路。** 桌面端面板里追问的原文是
 *    `\n\n---\n\n**追问：** <问题>`（main.js 自己写进去的），所以有追问时正面取那句追问；
 *    没追问时照安卓的逻辑走。其余（答案段、剥标记、截断）完全一致。
 * 2. **落盘用 tmp + rename 原子替换。** 卡片的 FSRS 进度是用户攒下来的复习历史，
 *    半截 JSON 会把整副牌组弄丢 —— 安卓那边靠 FileMemo 的内存副本兜，桌面这边补上原子写。
 *
 * 纯模块：不 require('electron')、不读时钟（now 由调用方传入）。Store 只碰 fs/path。
 */
;(function (root, factory) {
  const fs = (typeof require === 'function') ? require('node:fs') : null
  const path = (typeof require === 'function') ? require('node:path') : null
  const api = factory(fs, path)
  if (typeof module !== 'undefined' && module.exports) module.exports = api
  if (typeof window !== 'undefined') window.LZKnowledge = api
})(typeof self !== 'undefined' ? self : this, function (fs, path) {
  'use strict'

  const Fsrs = (typeof require === 'function') ? require('./fsrs') : (typeof window !== 'undefined' ? window.LZFsrs : null)

  /** 卡库上限。满了拒新增。 */
  const MAX = 5000
  /** 文件名（安卓同名，两端一致）。 */
  const FILE = 'knowledge.json'
  /** 背面最大字数（clip 带省略号）。 */
  const BACK_MAX = 500
  /** 正面最大字数（同安卓 frontFromTranscript 的 80）。 */
  const FRONT_MAX = 80
  /** 导入时正面 / 背面的截断上限（对齐安卓 KnowledgeImport）。 */
  const FRONT_CLIP = 200
  const BACK_CLIP = 500

  /** 默认牌组名。 */
  const DEFAULT_DECK = '默认'

  /** 来源。编码与安卓 KnowledgeCard.Source 一致，认不出的一律当 manual。 */
  const Source = Object.freeze({ ANSWER: 'answer', CHAT: 'chat', CLIP: 'clip', MANUAL: 'manual', IMPORT: 'import' })
  const SOURCE_CODES = Object.freeze(['answer', 'chat', 'clip', 'manual', 'import'])
  /** 容错解析来源码；认不出返回 'manual'。 */
  function sourceOf(code) {
    const c = String(code == null ? '' : code)
    return SOURCE_CODES.indexOf(c) >= 0 ? c : Source.MANUAL
  }

  /** 截断：超长就切 n 个字 + 省略号。对齐安卓 Memo.clip。 */
  function clip(text, n) {
    const t = String(text == null ? '' : text).trim()
    const max = (n == null) ? 200 : n
    return t.length <= max ? t : t.slice(0, max) + '…'
  }

  /** 按 \r\n / \n / \r 切行 —— JS 的 split('\n') 会在 CRLF 里留下 \r，安卓那边不会。 */
  function lines(text) { return String(text == null ? '' : text).split(/\r\n|\r|\n/) }

  /** 桌面端追问分隔符（main.js 写进面板原文的固定串）。 */
  const FOLLOWUP_MARK = '\n\n---\n\n**追问：** '

  /**
   * 一张知识卡。
   * 字段与安卓 KCData 同名同义；fsrs 是 Fsrs.card() 的结构。
   */
  function card(init) {
    const o = init || {}
    return {
      id: num(o.id, 0),
      deck: String(o.deck == null || o.deck === '' ? DEFAULT_DECK : o.deck),
      front: String(o.front == null ? '' : o.front),
      back: String(o.back == null ? '' : o.back),
      source: sourceOf(o.source),
      createdAt: num(o.createdAt, 0),
      fsrs: Fsrs.card(o.fsrs)
    }
  }

  function num(v, d) { const x = typeof v === 'string' ? Number(v) : v; return typeof x === 'number' && isFinite(x) ? x : d }

  /** 这张卡是不是到期了。 */
  function isDue(c, now) { return Fsrs.isDue(c.fsrs, now) }

  /** 距下次复习还有几天（已到期算 0，新卡算 0）。给复习页展示用。 */
  function daysLeft(c, now) {
    const d = Fsrs.daysBetween(now, c.fsrs.due || now)
    return d < 0 ? 0 : d
  }

  // ───────────────────── 纯函数：抽正反面 ─────────────────────

  /**
   * 从面板对话原文里抽「正面」。
   *
   * 有追问 → 取最后那句追问（桌面端面板的原文里带这个标记）；
   * 没有 → 照安卓：取「## 答案」段之前的第一个非空非标题行（识别场景 = 顶部场景描述）；
   * 再抽不出就用全文前 60 字。
   */
  function frontFromTranscript(raw) {
    const s = String(raw == null ? '' : raw)
    const at = s.lastIndexOf(FOLLOWUP_MARK)
    if (at >= 0) {
      const q = s.slice(at + FOLLOWUP_MARK.length).split(/\r\n|\r|\n/).map((l) => l.trim())
        .find((l) => l && !l.startsWith('#')) || ''
      if (q) return clip(q, FRONT_MAX)
    }
    const head = s.split('## 答案')[0].trim()
    const q = lines(head).map((l) => l.trim()).find((l) => l && !l.startsWith('#'))
    return clip(q || clip(s, 60), FRONT_MAX)
  }

  /**
   * 抽「背面」：取「## 答案」段之后、下一个 ## 段之前，剥掉标记行（名词清单、关键词行），截 BACK_MAX 字。
   * 没有「## 答案」标题时（纯小白格式的段落名不同）退回全文剥标记。
   */
  function backFromTranscript(raw) {
    const s = String(raw == null ? '' : raw)
    let after = ''
    const at = s.indexOf('## 答案\n')
    if (at >= 0) after = s.slice(at + '## 答案\n'.length)
    else {
      const at2 = s.indexOf('## 答案')
      after = at2 >= 0 ? s.slice(at2 + '## 答案'.length) : s
    }
    const body = after.split('\n## ')[0].trim()
    const plain = lines(body)
      .filter((l) => !l.startsWith('[') && !l.startsWith('关键词：'))
      .join('\n')
    return clip(plain, BACK_MAX)
  }

  /**
   * 从面板原文一次性抽出 { front, back }。
   * 只取**最后一轮**：多轮追问时前面的答案已经在上一张卡里了。
   */
  function fromTranscript(raw) {
    const s = String(raw == null ? '' : raw)
    const at = s.lastIndexOf(FOLLOWUP_MARK)
    let seg = s
    if (at >= 0) {
      // 追问那行只是这轮的「问题」，正面已经用它了；背面从它下面接着取。
      // 追问的回答通常没有 `## 答案` 标题（backFromTranscript 会退回全文），
      // 这时首行就是那句问题 —— 去掉，否则同一句话正面背面各写一遍。
      seg = s.slice(at + FOLLOWUP_MARK.length)
      if (seg.indexOf('## 答案') < 0) {
        const ls = lines(seg)
        let k = 0
        while (k < ls.length && !ls[k].trim()) k++
        seg = ls.slice(k + 1).join('\n')
      }
    }
    return { front: frontFromTranscript(s), back: backFromTranscript(seg) }
  }

  // ───────────────────── 纯函数：去重 / 排序 / 新增 ─────────────────────

  /** 去重键：同牌组同正面视为同一张。 */
  function dedupKey(deck, front) { return deck + '|' + String(front == null ? '' : front).trim() }

  /** 库里有没有同牌组同正面的卡。 */
  function hasDup(list, deck, front) {
    const f = String(front == null ? '' : front).trim()
    return (list || []).some((c) => c.deck === deck && c.front.trim() === f)
  }

  /** 按到期排前面（未到期的按创建时间倒序排后面）。与安卓 dueFirst 同序。 */
  function dueFirst(list, now) {
    const due = (c) => isDue(c, now)
    return (list || []).slice().sort((a, b) => {
      const da = due(a), db = due(b)
      if (da !== db) return da ? -1 : 1
      return da ? a.fsrs.due - b.fsrs.due : b.createdAt - a.createdAt
    })
  }

  /**
   * 加一张卡（纯函数）。返回 `{ ok, list, id, reason }`：
   *  - 空正面 / 重复 → ok:false reason:'empty' / 'dup'（**库不变**）
   *  - 超上限 → ok:false reason:'full'
   *  - 成功 → 新卡 id = 库里最大 id + 1，插到最前面（与安卓一致）
   */
  function tryAdd(list, c, max, now) {
    const cur = list || []
    const t = card(c)
    t.fsrs = Fsrs.card(c && c.fsrs)
    if (!t.front.trim()) return { ok: false, list: cur, id: 0, reason: 'empty' }
    if (hasDup(cur, t.deck, t.front)) return { ok: false, list: cur, id: 0, reason: 'dup' }
    if (cur.length >= (max == null ? MAX : max)) return { ok: false, list: cur, id: 0, reason: 'full' }
    const id = cur.reduce((n, x) => Math.max(n, x.id), 0) + 1
    t.id = id
    return { ok: true, list: [t].concat(cur), id: id, reason: '' }
  }

  /** 库里到期的张数（纯函数）。 */
  function dueCount(list, now) { return (list || []).filter((c) => isDue(c, now)).length }

  /** 库里按到期排好的那批（纯函数）。 */
  function dueList(list, now) { return dueFirst(list, now).filter((c) => isDue(c, now)) }

  // ───────────────────── 序列化（与安卓逐字段一致） ─────────────────────

  /** 一张卡 → JSON 对象。字段名 / 顺序跟安卓 KnowledgeCard.Store.toJson 一样。 */
  function toJson(c) {
    return {
      id: c.id,
      deck: c.deck,
      front: c.front,
      back: c.back,
      source: c.source,
      createdAt: c.createdAt,
      fsrs: Fsrs.toJson(c.fsrs)
    }
  }

  /** 整个库 → JSON 数组（可直接 JSON.stringify）。 */
  function toJsonList(list) { return (list || []).map(toJson) }

  /** 解析一个元素；缺字段退回默认值，坏元素返回 null。 */
  function parseOne(o) {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return null
    const c = card({
      id: o.id,
      deck: o.deck,
      front: o.front,
      back: o.back,
      source: sourceOf(o.source),
      createdAt: o.createdAt,
      fsrs: Fsrs.cardFromJson(o.fsrs) || Fsrs.card()
    })
    return c
  }

  /** 解析整个库；不是数组 / 语法坏 → 空库（与安卓 parse 的兜底一致）。 */
  function parse(json) {
    if (json == null || json === '') return []
    let arr = json
    if (typeof json === 'string') {
      const s = json.trim()
      if (!s) return []
      try { arr = JSON.parse(s) } catch { return [] }
    }
    if (!Array.isArray(arr)) return []
    const out = []
    for (const o of arr) { const c = parseOne(o); if (c) out.push(c) }
    return out
  }

  // ───────────────────── 存储 ─────────────────────

  /**
   * 卡库。内存副本 + 落盘（对齐安卓 FileMemo 模式）。
   * 落盘走 tmp + rename：卡片的复习进度丢不起。
   */
  class Store {
    /** @param file knowledge.json 的完整路径 */
    constructor(file) { this.file = file; this._list = null }

    /** 读库（首次读盘，之后走内存副本）。 */
    list() {
      if (this._list) return this._list
      let raw = ''
      try { raw = fs.readFileSync(this.file, 'utf8') } catch { raw = '' }
      this._list = parse(raw)
      return this._list
    }

    /** 写库。原子替换，写失败不污染内存副本。 */
    _save(list) {
      const tmp = this.file + '.tmp'
      try {
        fs.writeFileSync(tmp, JSON.stringify(toJsonList(list)), { mode: 0o600 })
        fs.renameSync(tmp, this.file)
        this._list = list
        return true
      } catch { try { fs.unlinkSync(tmp) } catch {} ; return false }
    }

    /** 存一张卡：去重 + 上限 + 存入即 Good 首刷。返回 `{ ok, id, reason }` */
    add(front, back, source, now, deck) {
      const r = tryAdd(this.list(), {
        id: 0,
        deck: deck || DEFAULT_DECK,
        front: String(front == null ? '' : front).trim(),
        back: String(back == null ? '' : back).trim(),
        source: source || Source.ANSWER,
        createdAt: now,
        fsrs: Fsrs.review(Fsrs.card(), Fsrs.Rating.Good, now)   // 存入即 Good 首刷（与安卓一致）
      }, MAX, now)
      if (!r.ok) return { ok: false, id: 0, reason: r.reason }
      return { ok: this._save(r.list), id: r.id, reason: '' }
    }

    /**
     * 批量导入：一次读一次写，避免逐张全量序列化（O(n²) → O(n)）。返回成功数。
     * 与安卓 addAll 同思路；重复/空的直接跳过，不算失败。
     */
    addAll(cards) {
      if (!Array.isArray(cards) || !cards.length) return 0
      let cur = this.list(), ok = 0
      for (const c of cards) {
        const r = tryAdd(cur, c, MAX, c && c.createdAt)
        if (r.ok) { cur = r.list; ok++ }
      }
      if (ok) this._save(cur)
      return ok
    }

    /** 删一张。 */
    remove(id) { return this._save(this.list().filter((c) => c.id !== id)) }

    /**
     * 评分（走 FSRS 重排 due）。rating 接受 1..4 或 'Again'/'Hard'/'Good'/'Easy'，
     * 认不出返回 false —— 不写库。
     */
    answer(id, rating, now) {
      const r = Fsrs.ratingOf(rating)
      if (!r) return false
      const list = this.list()
      if (!list.some((c) => c.id === id)) return false
      return this._save(list.map((c) => (c.id === id ? Object.assign({}, c, { fsrs: Fsrs.review(c.fsrs, r, now) }) : c)))
    }

    /** 总张数。 */
    count() { return this.list().length }

    /** 到期的那些卡（按到期排前面）。 */
    dueCards(now) { return Fsrs.dueCards(this.list().map((c) => c.fsrs), now) }

    /** 到期张数。 */
    dueCount(now) { return Fsrs.dueCount(this.list().map((c) => c.fsrs), now) }

    /** 整个库（直接给复习页渲染用）。 */
    all() { return this.list() }

    /** 清空。 */
    clear() { return this._save([]) }
  }

  /** 造一个 Store：dir 一般传 app.getPath('userData')。 */
  function storeIn(dir, file) { return new Store(path.join(dir, file || FILE)) }

  // ───────────────────── 导入（TXT / MD / 剪藏） ─────────────────────

  /**
   * 剪藏：第一句（或首行）当正面，其余当背面。纯空白返回 null。
   * 与安卓 KnowledgeImport.clipToCard 逐行一致。
   */
  function clipToCard(text) {
    const t = String(text == null ? '' : text).trim()
    if (!t) return null
    const m = t.search(/[。！？!?\n]/)
    // 句尾标点可能成簇（？！、!?、……）：边界要吞掉整个标点簇，不能在「！」和「？」中间切
    let splitAt = m < 0 ? t.length : m + 1
    if (m >= 0) while (splitAt < t.length && /[！？!?…。]/.test(t[splitAt])) splitAt++
    const front = clip(t.slice(0, splitAt), FRONT_CLIP)
    const rest = t.slice(splitAt).trim()
    const back = rest ? clip(rest, BACK_CLIP) : front
    return { front: front, back: back }
  }

  /**
   * Markdown 导入：`问 | 答` 管道行，或 `# 标题` = 正面 + 标题下正文 = 背面（到下一标题止）。
   * 与安卓 mdToCards 一致：BOM 剥掉、CRLF 当行切、标题卡永远排在它上面的管道卡之前。
   */
  function mdToCards(md, deck) {
    const out = []
    let curTitle = ''
    let curBody = []
    const flush = () => {
      if (curTitle.trim()) out.push({ front: clip(curTitle.trim(), FRONT_CLIP), back: clip(curBody.join('\n').trim(), BACK_CLIP) })
      curTitle = ''
      curBody = []
    }
    for (const rawLine of lines(md)) {
      const line = rawLine.trim().replace(/^﻿/, '')
      if (line.startsWith('#')) {
        flush()
        curTitle = line.replace(/^#+\s*/, '')
      } else if (line.indexOf('|') >= 0) {
        flush()   // 保持阅读顺序：先落前面的标题卡，再出管道卡
        const i = line.indexOf('|')
        const f = line.slice(0, i).trim()
        const b = line.slice(i + 1).trim()
        if (f && b && f !== '问') out.push({ front: clip(f, FRONT_CLIP), back: clip(b, BACK_CLIP) })
      } else if (!line) {
        // 空行只是排版
      } else curBody.push(line)
    }
    flush()
    return out.filter((p) => p.front)
  }

  /**
   * TXT 导入：每行 `正面<sep>背面`（默认 Tab），只有一段的行 → 整行当正面、背面同正面（自测卡）。
   * 与安卓 txtToCards 一致：BOM 剥掉、空行跳过、CRLF 当行切。
   */
  function txtToCards(txt, sep) {
    const s = (sep == null) ? '\t' : sep
    const out = []
    for (const rawLine of lines(txt)) {
      const line = rawLine.trim().replace(/^﻿/, '')
      if (!line) continue
      const i = s ? line.indexOf(s) : -1
      if (i > 0) out.push({ front: clip(line.slice(0, i), FRONT_CLIP), back: clip(line.slice(i + s.length), BACK_CLIP) })
      else out.push({ front: clip(line, FRONT_CLIP), back: clip(line, BACK_CLIP) })
    }
    return out.filter((p) => p.front)
  }

  /** 按文件名后缀选解析器：.md/.markdown 走 mdToCards，其余走 txtToCards。 */
  function fileToCards(fileName, content, sep) {
    const n = String(fileName || '').toLowerCase()
    if (/\.(md|markdown|mdown|mkd)$/.test(n)) return mdToCards(content)
    return txtToCards(content, sep)
  }

  /** 给一组 {front,back} 补上牌组 / 来源 / 首刷调度，落成能直接进库的卡片对象。 */
  function toCards(pairs, deck, now, source) {
    const d = deck || DEFAULT_DECK
    const out = []
    for (const p of (pairs || [])) {
      if (!p || !p.front) continue
      out.push(card({
        id: 0, deck: d, front: p.front, back: p.back || '',
        source: source || Source.IMPORT, createdAt: now,
        fsrs: Fsrs.review(Fsrs.card(), Fsrs.Rating.Good, now)
      }))
    }
    return out
  }

  // ───────────────────── 文案（对齐安卓 ReviewNotify，纯函数） ─────────────────────

  const HEADER = '知识卡复习'
  const EMPTY_HINT = '今日复习完毕。\n现在到期的卡都过完了 —— 有新卡到期会提醒你。' +
    '想加新卡：答案面板上「存为复习卡」，或在这里「导入 TXT/MD」。'

  /** 页头：「知识卡复习 · 到期 3 张」。 */
  function header(due) { return due > 0 ? HEADER + ' · 到期 ' + due + ' 张' : HEADER }
  /** 页头进度：「第 3 / 8 张」。 */
  function progress(i, total) { return '第 ' + i + ' / ' + total + ' 张' }
  /** 四档按钮文案。括号里是 FSRS 的 Rating 编码，方便对照源码。 */
  function ratingLabel(r) { return ratingNameOf(r) + '(' + Fsrs.ratingOf(r) + ')' }
  function ratingNameOf(r) {
    const c = Fsrs.ratingOf(r)
    return c === Fsrs.Rating.Again ? '忘记' : c === Fsrs.Rating.Hard ? '困难' : c === Fsrs.Rating.Good ? '良好' : c === Fsrs.Rating.Easy ? '简单' : ''
  }
  /** 通知标题：「知识卡复习 · 3 张到期」。 */
  function notiTitle(due) { return HEADER + ' · ' + due + ' 张到期' }
  /** 通知正文。 */
  function notiBody(due) { return due <= 0 ? '' : due + ' 张等着过一遍 · 答完自动排下次的间隔' }
  /** 该不该发这条通知：**没到期卡一律不发。** */
  function shouldNotify(due) { return due > 0 }

  return {
    // 常量
    MAX, FILE, BACK_MAX, FRONT_MAX, FRONT_CLIP, BACK_CLIP, DEFAULT_DECK, FOLLOWUP_MARK,
    // 数据
    Source, sourceOf, card, clip, lines,
    // 抽正反面
    frontFromTranscript, backFromTranscript, fromTranscript,
    // 去重 / 排序 / 新增
    dedupKey, hasDup, dueFirst, tryAdd, dueCount, dueList, isDue, daysLeft,
    // 序列化
    toJson, toJsonList, parse, parseOne,
    // 存储
    Store, storeIn,
    // 导入
    clipToCard, mdToCards, txtToCards, fileToCards, toCards,
    // 文案
    HEADER, EMPTY_HINT, header, progress, ratingLabel, ratingNameOf, notiTitle, notiBody, shouldNotify
  }
})