/* v1.64：知识卡检索（BM25）—— 与安卓 v1.70 KnowledgeSearch.kt 逐条对齐的 JS 移植。
 *
 * 用途：MCP 的 ling_cards_search / ling://review/today 需要「按关键词挑卡」，
 * 而桌面端此前只在渲染层做过 substring 过滤。移植的是同一套分词 + BM25，
 * 所以同一个查询在手机和电脑上挑出的是同一批卡（顺序也一致）。
 *
 * 与安卓逐条对应：
 *   tokenize / isStopGram / STOP_ZH / STOP_EN   → 同一套 2-gram + 英文整词 + 停用词
 *   bm25Score / scoreAll / search               → 同一套 k1=1.5 / b=0.75 / 语料版 IDF
 *   docText                                     → 正面 + 背面
 *
 * 安卓那边区分「平铺 IDF 的单文档打分」和「语料版 IDF 的全库排序」，这里照搬：
 * bm25Score 只用于对拍 / 自测，真排序必须走 scoreAll。
 *
 * 纯模块：不 require electron、不读时钟、不碰文件。
 */
'use strict'

/** 一次最多取几张卡（同安卓 TOP_K）。 */
const TOP_K = 3
/** 上下文块总长上限（同安卓 CONTEXT_MAX）。 */
const CONTEXT_MAX = 1200
/** 单卡正面留多少字。 */
const FRONT_CLIP = 80
/** 单卡背面留多少字。 */
const BACK_CLIP = 200

/** BM25 参数：标准取值。 */
const K1 = 1.5
const B = 0.75

/** 平铺 IDF（单文档打分用；真实语料 IDF 见 scoreAll）。ln(2) */
const IDF_FLAT = 0.6931471805599453
/** 长度归一化参照长度（token 数），中文 2-gram 化后一张普通卡约 100~200 个 token。 */
const REF_DL = 120.0

/** 汉字（含扩展 A 区）算中文，其余 ASCII 字母数字算英文/型号。 */
const CJK = /[\u4e00-\u9fff\u3400-\u4dbf]/
/** 一切非「中文、非字母数字」都是分隔符（标点、空格、换行、markdown 符号）。 */
const SPLIT = /[^0-9a-z\u4e00-\u9fff\u3400-\u4dbf]+/

/** 单字停用词（中文）。与安卓 STOP_ZH 完全一致。 */
const STOP_ZH = new Set([
  '的', '了', '吗', '呢', '啊', '吧', '是', '在', '和', '与', '就', '都', '也', '很', '不', '有', '个',
  '上', '下', '中', '要', '会', '说', '把', '被', '给', '让', '对', '从', '到', '着', '过', '地', '得', '我', '你',
])
/**
 * 英文停用词（小写）。刻意只收虚词 —— 疑问词留在词表里，
 * 「how do you spell」这种问法本身就是知识卡正面上的原话。
 */
const STOP_EN = new Set([
  'the', 'a', 'an', 'is', 'are', 'was', 'were', 'of', 'to', 'in', 'on', 'and', 'or', 'it', 'this', 'that',
  'for', 'with', 'as', 'at', 'be',
])
const STOP = new Set([...STOP_ZH, ...STOP_EN])

/** 2-gram 整体是停用词，或两个字都是单字停用词 → 丢。 */
function isStopGram(g) {
  if (STOP.has(g)) return true
  return g.length === 2 && STOP_ZH.has(g[0]) && STOP_ZH.has(g[1])
}

/**
 * 分词：中文按 2-gram（单字成词时留那个单字），英文 / 型号按词切（`R7250`、`Wi-Fi` 照原样留，只压小写），
 * 停用词丢掉。与安卓 tokenize 逐行对应。
 */
function tokenize(q) {
  const s = String(q == null ? '' : q)
  if (!s.trim()) return []
  const out = []
  for (const raw of s.toLowerCase().split(SPLIT)) {
    if (!raw) continue
    if (CJK.test(raw)) {
      if (raw.length === 1) { if (!STOP.has(raw)) out.push(raw) }
      else {
        for (let i = 0; i < raw.length - 1; i += 1) {
          const g = raw.slice(i, i + 2)
          if (!isStopGram(g)) out.push(g)
        }
      }
    } else if (raw.length >= 2 || /^[0-9]/.test(raw[0])) {
      if (!STOP.has(raw)) out.push(raw)
    }
  }
  return out
}

/** 一张卡的检索文本 = 正面 + 背面（正面是问题、背面是答案，两头都该能被查到）。 */
function docText(c) {
  const o = c || {}
  return String(o.front == null ? '' : o.front) + '\n' + String(o.back == null ? '' : o.back)
}

/** BM25 主公式：Σ idf(q) · tf·(k1+1) / (tf + k1·(1-b+b·dl/avgdl)) */
function scoreTerms(query, terms, avgdl, idf) {
  if (!query.length || !terms.length) return 0
  const tf = new Map()
  for (const t of terms) tf.set(t, (tf.get(t) || 0) + 1)
  const dl = terms.length
  const avg = avgdl > 0 ? avgdl : dl
  let s = 0
  for (const q of new Set(query)) {
    const f = tf.get(q)
    if (!f) continue
    s += idf(q) * (f * (K1 + 1)) / (f + K1 * (1 - B + B * dl / avg))
  }
  return s
}

/**
 * 单文档 BM25（自测 / 对照用）：IDF 取平铺值、长度按 REF_DL 归一 ——
 * 只有一条 doc 时语料 IDF 无从算起。真排序走 scoreAll。
 */
function bm25Score(query, doc) {
  const q = Array.isArray(query) ? query : tokenize(query)
  if (!q.length) return 0
  return scoreTerms(q, tokenize(doc), REF_DL, () => IDF_FLAT)
}

/**
 * 全库打分（语料版 IDF）：df 在这批卡里现算，avgdl 是这批卡的平均长度。
 * 零分（一个查询词都没命中）的卡不返回 —— 「勉强沾边」的知识卡比不给更坏。
 * 排序：分数降序，同分按 id 升序（与安卓一致）。
 */
function scoreAll(cards, query) {
  const q = tokenize(query)
  const list = Array.isArray(cards) ? cards : []
  if (!q.length || !list.length) return []
  const docs = list.map((c) => tokenize(docText(c)))
  const n = docs.length
  const df = new Map()
  // df 数的是**文档数**，不是出现次数：一张卡里同一词出现 5 次也只算 1 篇。
  for (const d of docs) for (const t of new Set(d)) df.set(t, (df.get(t) || 0) + 1)
  const avgdl = docs.reduce((s, d) => s + d.length, 0) / n
  const idf = (t) => {
    const d = df.get(t) || 0
    return Math.log(1 + (n - d + 0.5) / (d + 0.5))
  }
  const out = []
  for (let i = 0; i < n; i += 1) {
    const s = scoreTerms(q, docs[i], avgdl, idf)
    if (s > 0) out.push({ card: list[i], score: s })
  }
  return out.sort((a, b) => (b.score - a.score) || ((a.card && a.card.id) || 0) - ((b.card && b.card.id) || 0))
}

/** 全库打分取前 topK 张（分数从高到低；零分不返回）。 */
function search(cards, query, topK) {
  const k = topK == null ? TOP_K : topK
  if (!(k > 0)) return []
  return scoreAll(cards, query).slice(0, k).map((h) => h.card)
}

/** 压到 max 字以内，超了截断加省略号（总长 ≤ max + 1）。 */
function clip(s, max) {
  const t = String(s == null ? '' : s)
  const m = max == null ? 200 : max
  if (m <= 0) return ''
  return t.length <= m ? t : t.slice(0, m).trimEnd() + '…'
}

module.exports = {
  TOP_K, CONTEXT_MAX, FRONT_CLIP, BACK_CLIP, K1, B, IDF_FLAT, REF_DL,
  STOP_ZH, STOP_EN, tokenize, isStopGram, docText, bm25Score, scoreAll, search, clip,
}
