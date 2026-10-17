'use strict'
/* v1.72 [K4D] Anki .apkg 牌组导入（桌面端）。
 *
 * .apkg = zip 包：collection.anki2（SQLite 老版）/ collection.anki21（新版）/
 * collection.anki21b（zstd 压缩，解不开——明确报错让用户换旧格式导出）+ media 清单。
 * zip 用 zlib 手解（EOCD → 中央目录 → 局部头），SQLite 用 Node 24 内置 node:sqlite。
 * 纯函数（字段切分 / HTML 剥离 / 配额）与读库壳分开放，前者可单测。
 */
const zlib = require('zlib')
const { DatabaseSync } = require('node:sqlite')

const DBNAMES = ['collection.anki21', 'collection.anki2']
const SEP = String.fromCharCode(31)   // Anki notes.flds 的字段分隔符（U+001F）

// ---------------- 纯函数 ----------------

/** notes.flds 按分隔符切字段（Anki 用 \u001f）。 */
function splitFields(flds) {
  return String(flds == null ? '' : flds).split(SEP).map((s) => s)
}

/** 剥 HTML 标签 + 常见实体 + 压缩空白（卡面基本是 HTML，不剥会满屏尖括号）。 */
function stripHtml(s) {
  return String(s == null ? '' : s)
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<\/(div|p|li|tr)>/gi, ' ')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/\s+/g, ' ')
    .trim()
}

/** 从 zip 条目名里挑可读的库：anki21 优先，anki2 兜底；只有 anki21b 返回 'zstd'。 */
function pickDbName(names) {
  const set = new Set(names)
  if (set.has('collection.anki21')) return 'collection.anki21'
  if (set.has('collection.anki2')) return 'collection.anki2'
  if (set.has('collection.anki21b')) return 'zstd'
  return null
}

/** 剩余可导入数（ KnowledgeCard.MAX 上限）。 */
function importsLeft(existing, incoming, max) {
  return Math.max(0, Math.min(incoming, max - existing))
}

/** 解析 notes 表行 → {front, back}；字段不足 / 两栏全空 → 跳过（清洗交给 knowledge.toCards）。 */
function rowToCard(flds) {
  const f = splitFields(flds)
  if (f.length < 2) return null
  const front = stripHtml(f[0]).trim()
  const back = stripHtml(f[1]).trim()
  if (!front || !back) return null
  return { front, back }
}

// ---------------- zip 解包（EOCD → 中央目录 → 局部头数据）----------------

function u16(b, o) { return b.readUInt16LE(o) }
function u32(b, o) { return b.readUInt32LE(o) }

/** 从 zip 字节流里取出指定条目的内容（支持 store / deflate），找不到返回 null。 */
function zipExtract(buf, name) {
  // EOCD：从尾部向前找 0x06054b50
  let eocd = -1
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66000); i--) {
    if (u32(buf, i) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) return null
  const count = u16(buf, eocd + 10)
  let off = u32(buf, eocd + 16)
  for (let i = 0; i < count; i++) {
    if (u32(buf, off) !== 0x02014b50) return null
    const method = u16(buf, off + 10)
    const csize = u32(buf, off + 20)
    const nameLen = u16(buf, off + 28)
    const extraLen = u16(buf, off + 30)
    const commentLen = u16(buf, off + 32)
    const localOff = u32(buf, off + 42)
    const entryName = buf.slice(off + 46, off + 46 + nameLen).toString('utf8')
    if (entryName === name) {
      const lNameLen = u16(buf, localOff + 26)
      const lExtraLen = u16(buf, localOff + 28)
      const dataStart = localOff + 30 + lNameLen + lExtraLen
      const raw = buf.slice(dataStart, dataStart + csize)
      if (method === 0) return raw
      if (method === 8) return zlib.inflateRawSync(raw)
      return null
    }
    off += 46 + nameLen + extraLen + commentLen
  }
  return null
}

// ---------------- 读库壳（主进程用）----------------

/**
 * 从 .apkg 字节流里读出全部 notes 的 (id, flds)。
 * 返回 { rows, dbName }；dbName='zstd' 表示只有压缩新版库（不支持）。
 */
function readNotes(buf) {
  const names = zipNames(buf)
  const db = pickDbName(names)
  if (!db) return { rows: [], dbName: null }
  const entry = zipExtract(buf, db)
  if (!entry) return { rows: [], dbName: db }
  const tmp = require('os').tmpdir() + '/lingzhu-anki-' + Date.now() + '.db'
  require('fs').writeFileSync(tmp, entry)
  let rows = []
  try {
    const db = new DatabaseSync(tmp, { readOnly: true })
    try {
      const st = db.prepare('SELECT id, flds FROM notes')
      for (const r of st.iterate()) rows.push({ id: Number(r.id), flds: String(r.flds ?? '') })
    } finally { db.close() }
  } catch (_e) { /* 坏库按空处理 */ } finally {
    try { require('fs').unlinkSync(tmp) } catch (_e) {}
  }
  return { rows, dbName: db }
}

function zipNames(buf) {
  const names = []
  let eocd = -1
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66000); i--) {
    if (u32(buf, i) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) return names
  const count = u16(buf, eocd + 10)
  let off = u32(buf, eocd + 16)
  for (let i = 0; i < count; i++) {
    if (u32(buf, off) !== 0x02014b50) break
    const nameLen = u16(buf, off + 28)
    const extraLen = u16(buf, off + 30)
    const commentLen = u16(buf, off + 32)
    names.push(buf.slice(off + 46, off + 46 + nameLen).toString('utf8'))
    off += 46 + nameLen + extraLen + commentLen
  }
  return names
}

module.exports = { splitFields, stripHtml, pickDbName, importsLeft, rowToCard, zipExtract, zipNames, readNotes }
