package com.chun.lingzhu

/**
 * 记事本语法：从正文里解析 `#标签`、`[[双链]]`、`[文字](链接)`、标题，以及给编辑器用的高亮区间。
 *
 * ## 为什么是纯函数
 * 标签和双链都从 body 解析，body 是唯一事实来源（照 Memos 的做法）。
 * 好处：导出成 Markdown 内容不丢，同步只搬 body，tags/links 两张表本地重建。
 * 代价：解析规则必须**极其确定** —— 同一个 body 任何时候都必须解析出同一批标签，
 * 否则同步两端会各自重建出不同的结果。所以这里不碰 Context、不碰 IO，全部纯函数。
 *
 * ## 规则里几个反直觉的地方（都是踩过的）
 *  - `C#` 不是标签：`#` 紧挨着字母数字就是代码/编号，不是标签
 *  - `#1` 不是标签、`#1班` 是：整个标签至少要有一个非数字字符，否则「第#3题」会被吃掉
 *  - `# 标题` 不是标签：`#` 后面跟空格是 Markdown 标题
 *  - 代码块里的 `#x` 不是标签：注释/字符串里的井号不该变成真标签
 *  - URL 里的 `#anchor` 不是标签
 *  - `[[#标题]]` 里的 `#` 也不是标签：它属于双链，不是正文里的标签
 *
 * ## 排除区（代码 / 注释）
 * [mask] 一次扫描算出「这不是正文」的区间，标签、双链、高亮共用同一份结论：
 *  - ``` / ~~~ 围栏代码块（整块）
 *  - 行内代码（反引号成对；**未闭合时只吞到行尾**，不会连带吞掉整篇的标签）
 *  - `%%注释%%`（含跨行；未闭合时按普通文字处理，避免误吞后半篇）
 * 只算一遍、只读一遍，100 KB 的正文也是线性的。
 *
 * ## 不截断错误目标
 * 双链目标 / Markdown 链接目标超过上限、或者中途换行、或者夹着 `[` `]`，一律**整条跳过**，
 * 绝不把目标截一半当成合法目标 —— 截断会凭空造出一个指向别处的链接。
 */
object NoteSyntax {

    /** 标签总长度上限（含层级斜杠） */
    const val MAX_TAG = 48

    /** 双链标题长度上限 */
    const val MAX_LINK = 80

    /** 一条 wiki 链接内部（目标+标题+别名）允许的最大长度，超了整条不算 */
    private const val MAX_WIKI_INNER = 4 * MAX_LINK

    /** Markdown 链接目标的长度上限（超了跳过，不截断） */
    private const val MAX_MD_DEST = 2048

    /** 高亮区间种类 */
    enum class Kind { TAG, LINK, CODE }

    /** 正文里一段需要上色的区间，半开区间 [start, end) */
    data class Span(val kind: Kind, val start: Int, val end: Int)

    /**
     * 一条 wiki 链接（`[[目标#标题|别名]]`，embed 时前面多一个 `!`）。
     *
     * [raw] 是原文切片（编辑器替换 / 点击命中都靠 start/end，不用自己再数）。
     * [target] 是 `#` 和 `|` 之前的路径原文（没小写、没去 `.md`），空串表示同篇笔记的标题链接。
     */
    data class Wiki(
        val raw: String,
        val start: Int,
        val end: Int,
        val target: String,
        val heading: String,
        val alias: String,
        val embed: Boolean,
        val width: Int,
        val height: Int,
        val isPdf: Boolean,
    )

    /** 一条标准 Markdown 链接 / 图片 */
    data class MdLink(
        val text: String,
        val dest: String,
        val title: String,
        val image: Boolean,
        val start: Int,
        val end: Int,
    )

    /** 一个 Markdown 标题（ATX 风格），[offset] 是 `#` 所在的下标 */
    data class Heading(val text: String, val level: Int, val offset: Int)

    // ---------------- 标签 ----------------

    /**
     * 从正文里解析标签。返回**规范化后**的名字（去首尾斜杠、合并连续斜杠、小写化用于比较）。
     *
     * 展示时用户看到的仍是原文 —— 这里返回的小写版只用于去重、比较和查询。
     */
    fun tags(body: String): List<String> {
        val hits = scanTags(body)
        if (hits.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>(hits.size)
        for (h in hits) out.add(h.name)
        return out.toList()
    }

    /** 标签到哪里结束：空白、标点、括号都是终点 */
    private fun tagEnd(s: String, from: Int): Int {
        var j = from
        while (j < s.length && isTagChar(s[j])) j++
        return j
    }

    /**
     * 合法的标签字符：Unicode 字母数字、emoji，外加 `_ - + & /`。
     * 全角 ＃ 不认（那是中文标点，不是井号）；中文标点也不做分隔符之外的用途。
     */
    private fun isTagChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '+' || c == '&' || c == '/' ||
        Character.getType(c) == Character.OTHER_SYMBOL.toInt()   // emoji 等（OTHER_SYMBOL 是 Byte，要转 Int）

    /** 全是数字 → 不算标签（#1、#2026 是编号） */
    private fun isAllDigits(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() }

    /** 扫一遍标签，带位置。tags() 和高亮都用它，保证两边结论一致 */
    private fun scanTags(body: String, m: BooleanArray = mask(body)): List<TagHit> {
        val n = body.length
        if (n == 0) return emptyList()
        val out = ArrayList<TagHit>()
        var i = 0
        while (i < n) {
            if (m[i]) { i++; continue }
            val c = body[i]
            // 双链内部不是正文：#在这里属于 [[目标#标题]]，不该变成标签
            if (c == '[' && i + 1 < n && body[i + 1] == '[') {
                val close = body.indexOf("]]", i + 2)
                val nl = body.indexOf('\n', i)
                if (close > 0 && (nl < 0 || nl > close)) { i = close + 2; continue }
            }
            if (c != '#') { i++; continue }
            // 前一个字符是字母/数字 → 不是标签（C#、a#b）
            val prev = if (i > 0) body[i - 1] else ' '
            if (prev.isLetterOrDigit()) { i++; continue }
            // 前一个是反斜杠 → 转义，不是标签
            if (prev == '\\') { i++; continue }
            // 前一个是 / → URL 片段（http://a.com/#b）。注意「#数学/导数」这种层级标签的 / 在 # 后面，
            // 只有 # 紧跟在 / 后面才判为 URL
            if (prev == '/') { i++; continue }
            val end = tagEnd(body, i + 1)
            val raw = body.substring(i + 1, end)
            val norm = normTag(raw)
            if (norm.isNotEmpty() && !isAllDigits(norm)) out.add(TagHit(i, end, norm))
            i = if (end > i + 1) end else i + 1
        }
        return out
    }

    private class TagHit(val start: Int, val end: Int, val name: String)

    /**
     * 规范化标签：去掉首尾 `/`，把连续的 `//` 合成一个，转小写。
     * 大小写只影响比较，展示仍用原文。
     */
    fun normTag(raw: String): String {
        var s = raw.trim().replace(Regex("/{2,}"), "/")
        while (s.startsWith("/")) s = s.substring(1)
        while (s.endsWith("/")) s = s.substring(0, s.length - 1)
        if (s.length > MAX_TAG) s = s.substring(0, MAX_TAG)
        return s.lowercase()
    }

    // ---------------- 双链 ----------------

    /**
     * 从正文里解析双链。返回目标（规范化：去 `.md`、`\`→`/`、折叠斜杠、小写）。
     *
     * 只看 `[[...]]`，不看 `![[...]]`（嵌入是附件/整篇转写，不是双链；算 backlink 会把图片当地址）。
     * 要拿到标题、别名、尺寸、PDF 页码请用 [wikiLinks]。
     */
    fun links(body: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in wikiLinks(body)) {
            if (w.embed) continue
            val t = targetKey(w.target)
            if (t.isNotEmpty()) out.add(t)
        }
        return out.toList()
    }

    /** 目标规范化：`Notes/导数.md` → `notes/导数` */
    fun targetKey(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("[[") && s.endsWith("]]")) s = s.substring(2, s.length - 2)
        s = unwrap(s).replace('\\', '/')
        val pipe = s.indexOf('|')
        if (pipe >= 0) s = s.substring(0, pipe)
        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash)
        val qm = s.indexOf('?')
        if (qm >= 0) s = s.substring(0, qm)
        s = collapseSlash(s).trim('/')
        if (s.length > 3 && s.endsWith(".md", ignoreCase = true)) {
            s = s.substring(0, s.length - 3).trim('/')
        }
        return s.lowercase()
    }

    /**
     * 解析所有 wiki 链接，带位置与完整结构。
     *
     * 支持：`[[目标]]`、`[[目标|别名]]`、`[[目标#标题]]`、`[[目标#标题|别名]]`、
     * `[[目标.md]]`、`[[目录/目标]]`、`![[图.png]]`、`![[图.png|300]]`、`![[图.png|300x200]]`、
     * `[[文档.pdf#page=3]]`。
     */
    fun wikiLinks(body: String, m: BooleanArray = mask(body)): List<Wiki> {
        val n = body.length
        if (n < 4) return emptyList()
        val out = ArrayList<Wiki>()
        var i = 0
        while (i + 1 < n) {
            if (m[i] || body[i] != '[' || body[i + 1] != '[') { i++; continue }
            val open = i
            var j = open + 2
            var close = -1
            var broken = false
            while (j + 1 < n) {
                if (body[j] == '\n') break             // 双链不跨行
                if (m[j]) { broken = true; break }      // 目标里夹代码/注释 → 畸形
                if (body[j] == ']' && body[j + 1] == ']') { close = j; break }
                j++
            }
            if (close < 0 || broken) {
                // 从 open+2 继续找下一个 [[：不猜、不截断，畸形的一条整个丢掉
                i = open + 2
                continue
            }
            val embed = open > 0 && body[open - 1] == '!'
            val start = if (embed) open - 1 else open
            val raw = body.substring(start, close + 2)
            val inner = body.substring(open + 2, close)
            val w = parseWiki(inner, raw, start, close + 2, embed)
            if (w != null) out.add(w)
            i = close + 2
        }
        return out
    }

    private fun parseWiki(inner: String, raw: String, start: Int, end: Int, embed: Boolean): Wiki? {
        if (inner.isEmpty() || inner.length > MAX_WIKI_INNER) return null
        if (inner.any { it == '[' || it == ']' || it == '\n' || it == '\r' }) return null

        val pipe = inner.indexOf('|')
        val head = if (pipe >= 0) inner.substring(0, pipe) else inner
        val aliasRaw = if (pipe >= 0) inner.substring(pipe + 1) else ""

        var target = head
        var heading = ""
        val hash = head.indexOf('#')
        if (hash >= 0) {
            target = head.substring(0, hash)
            heading = head.substring(hash + 1).trim()
        }
        target = unwrap(target)
        if (target.length > MAX_LINK || heading.length > MAX_LINK) return null
        if (target.isEmpty() && heading.isEmpty()) return null
        // 目标里不允许空白之外的怪东西：空白会把「标题」悄悄变成两个词的两个目标
        if (target.any { it == '\u0000' }) return null

        var alias = aliasRaw.trim()
        var w = 0
        var h = 0
        if (embed && alias.isNotEmpty()) {
            val size = parseSize(alias)
            if (size != null) {
                w = size[0]
                h = size[1]
                alias = ""
            }
        }
        val isPdf = target.lowercase().endsWith(".pdf")
        return Wiki(
            raw = raw,
            start = start,
            end = end,
            target = target,
            heading = heading,
            alias = alias,
            embed = embed,
            width = w,
            height = h,
            isPdf = isPdf,
        )
    }

    private val SIZE = Regex("^(\\d{1,5})(?:[xX](\\d{1,5}))?$")

    /** 嵌入尺寸：`300`（宽）或 `300x200`（宽x高）。不是尺寸就返回 null（当别名处理） */
    private fun parseSize(s: String): IntArray? {
        val m = SIZE.matchEntire(s) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: 0
        if (w <= 0) return null
        return intArrayOf(w, h)
    }

    /** PDF 链接的页码：`page=3` / `#page=3` → 3；没有或不合法 → 0 */
    fun pdfPage(heading: String): Int {
        val t = heading.trim()
        if (t.isEmpty()) return 0
        var from = 0
        val key = "page="
        while (true) {
            val at = t.indexOf(key, from, ignoreCase = true)
            if (at < 0) return 0
            var j = at + key.length
            var v = 0
            var digits = 0
            while (j < t.length && t[j] in '0'..'9') {
                if (digits == 5) return 0
                v = v * 10 + (t[j] - '0')
                j++
                digits++
            }
            if (digits > 0 && v > 0) return v
            from = at + key.length
        }
    }

    // ---------------- Markdown 链接 ----------------

    /**
     * 解析标准 Markdown 链接与图片：`[文字](目标)`、`[文字](目标 "标题")`、`![](图.png)`、`[a](<带 空格.md>)`。
     *
     * 只认同一行内闭合的写法；跨行、缺 `)` 的一条整个跳过（不猜结尾）。
     */
    fun markdownLinks(body: String, m: BooleanArray = mask(body)): List<MdLink> {
        val n = body.length
        if (n < 4) return emptyList()
        val out = ArrayList<MdLink>()
        var i = 0
        while (i < n) {
            if (m[i] || body[i] != '[') { i++; continue }
            // [[ 是 wiki 链接，交给 wikiLinks，别在这里重复解析
            if (i + 1 < n && body[i + 1] == '[') { i += 2; continue }
            val image = i > 0 && body[i - 1] == '!'
            val start = if (image) i - 1 else i
            var depth = 0
            var j = i
            var close = -1
            var broken = false
            while (j < n) {
                val c = body[j]
                if (c == '\n') break
                if (m[j]) { broken = true; break }
                if (c == '[') depth++
                else if (c == ']') { depth--; if (depth == 0) { close = j; break } }
                j++
            }
            if (close < 0 || broken) { i++; continue }
            if (close + 1 >= n || body[close + 1] != '(' || m[close + 1]) { i = close + 1; continue }

            var k = close + 2
            while (k < n && body[k] == ' ') k++
            var dest: String
            if (k < n && body[k] == '<') {
                var q = k + 1
                while (q < n && body[q] != '>' && body[q] != '\n') q++
                if (q >= n || body[q] != '>') { i = close + 1; continue }
                dest = body.substring(k + 1, q)
                k = q + 1
            } else {
                var q = k
                var par = 0
                while (q < n) {
                    val c = body[q]
                    if (c == '\n') break
                    if (c == '\\' && q + 1 < n) { q += 2; continue }
                    if (c == '(') par++
                    else if (c == ')') { if (par == 0) break; par-- }
                    else if (c == ' ' && par == 0) break
                    q++
                }
                dest = body.substring(k, q)
                k = q
            }
            var title = ""
            while (k < n && body[k] == ' ') k++
            if (k < n && (body[k] == '"' || body[k] == '\'' || body[k] == '(')) {
                val qc = if (body[k] == '(') ')' else body[k]
                var q = k + 1
                while (q < n && body[q] != qc && body[q] != '\n') q++
                if (q < n && body[q] == qc) { title = body.substring(k + 1, q).trim(); k = q + 1 }
            }
            while (k < n && body[k] == ' ') k++
            if (k >= n || body[k] != ')') { i = close + 1; continue }

            val text = body.substring(i + 1, close)
            if (dest.length <= MAX_MD_DEST) {
                out.add(MdLink(text, dest.trim(), title, image, start, k + 1))
            }
            i = k + 1
        }
        return out
    }

    // ---------------- 标题 ----------------

    /** ATX 标题：`# 标题` … `###### 标题`（可带结尾 `#`）。围栏代码块里的不算 */
    fun headings(body: String, m: BooleanArray = mask(body)): List<Heading> {
        val n = body.length
        if (n == 0) return emptyList()
        val out = ArrayList<Heading>()
        var lineStart = 0
        while (lineStart < n) {
            var lineEnd = body.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = n
            var p = lineStart
            var indent = 0
            while (p < lineEnd && body[p] == ' ' && indent < 4) { p++; indent++ }
            if (indent <= 3 && p < lineEnd && body[p] == '#' && !m[p]) {
                var q = p
                while (q < lineEnd && body[q] == '#') q++
                val level = q - p
                if (level in 1..6 && q < lineEnd && (body[q] == ' ' || body[q] == '\t')) {
                    var text = body.substring(q, lineEnd).trim()
                    // 结尾的 # 是收尾标记（「## 标题 ##」），但「C#」这种不算
                    val runStart = text.length - text.trimEnd('#').length
                    if (runStart > 0) {
                        val before = text.length - runStart - 1
                        if (before < 0 || text[before] == ' ' || text[before] == '\t') {
                            text = text.substring(0, text.length - runStart).trimEnd()
                        }
                    }
                    if (text.isNotEmpty()) out.add(Heading(text, level, p))
                }
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    /** 标题锚点（Obsidian 风格）：小写、空白转 `-`、去掉标点 */
    fun anchorOf(text: String): String {
        val sb = StringBuilder(text.length)
        var dashed = false
        for (ch in text) {
            when {
                ch.isLetterOrDigit() -> { sb.append(ch.lowercaseChar()); dashed = false }
                ch == '-' || ch == '_' -> { sb.append(ch); dashed = false }
                ch.isWhitespace() -> {
                    if (!dashed && sb.isNotEmpty()) { sb.append('-'); dashed = true }
                }
                else -> { /* 标点丢掉 */ }
            }
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == '-') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    // ---------------- 高亮 ----------------

    /**
     * 编辑器高亮用的区间表：标签、链接、代码，按位置排序、互不重叠。
     *
     * 和 tags()/wikiLinks() 共用同一份排除区结论 —— 两处各写一遍规则的话，
     * 迟早出现「高亮成了一个标签、保存时却不是标签」。
     */
    fun spans(body: String): List<Span> {
        val n = body.length
        if (n == 0) return emptyList()
        // 一份掩码、一次扫描：三处解析共用它，规则不可能各说各话
        val m = mask(body)
        val raw = ArrayList<Span>()
        var i = 0
        while (i < n) {
            if (!m[i]) { i++; continue }
            var j = i
            while (j < n && m[j]) j++
            raw.add(Span(Kind.CODE, i, j))
            i = j
        }
        for (h in scanTags(body, m)) raw.add(Span(Kind.TAG, h.start, h.end))
        for (w in wikiLinks(body, m)) raw.add(Span(Kind.LINK, w.start, w.end))
        for (l in markdownLinks(body, m)) raw.add(Span(Kind.LINK, l.start, l.end))
        raw.sortWith(compareBy({ it.start }, { it.end }))
        // 交付给渲染层的表必须互不重叠：先到的（更靠左/更短/代码块优先）胜出，后来的整段丢掉
        val out = ArrayList<Span>(raw.size)
        var lastEnd = -1
        for (s in raw) {
            if (s.start < lastEnd) continue
            out.add(s)
            lastEnd = s.end
        }
        return out
    }

    // ---------------- 排除区 ----------------

    /**
     * 「这不是正文」的掩码：true = 该下标处在代码块 / 行内代码 / `%%注释%%` 里。
     *
     * 一次扫描，三条规则按顺序叠加：围栏块 → 行内代码 → 注释。
     */
    private fun mask(body: String): BooleanArray {
        val n = body.length
        val m = BooleanArray(n)
        if (n == 0) return m
        maskFences(body, m)
        maskInlineCode(body, m)
        maskComments(body, m)
        return m
    }

    /** ``` / ~~~ 围栏块（含开头的 info 字符串行）。没闭合就一直到结尾 */
    private fun maskFences(body: String, m: BooleanArray) {
        val n = body.length
        var inFence = false
        var fChar = ' '
        var fLen = 0
        var fStart = 0
        var lineStart = 0
        while (lineStart < n) {
            var lineEnd = body.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = n
            var p = lineStart
            var indent = 0
            while (p < lineEnd && body[p] == ' ' && indent < 4) { p++; indent++ }
            if (!inFence) {
                if (indent <= 3 && p < lineEnd && (body[p] == '`' || body[p] == '~')) {
                    val c = body[p]
                    var q = p
                    while (q < lineEnd && body[q] == c) q++
                    val len = q - p
                    if (len >= 3) {
                        // 反引号围栏的 info 字符串里不能再有反引号（CommonMark）
                        val tick = body.indexOf('`', q)
                        val infoOk = c == '~' || tick < 0 || tick >= lineEnd
                        if (infoOk) { inFence = true; fChar = c; fLen = len; fStart = lineStart }
                    }
                }
            } else {
                if (indent <= 3 && p < lineEnd && body[p] == fChar) {
                    var q = p
                    while (q < lineEnd && body[q] == fChar) q++
                    if (q - p >= fLen && body.substring(q, lineEnd).isBlank()) {
                        inFence = false
                        for (k in fStart until lineEnd) m[k] = true
                    }
                }
            }
            lineStart = lineEnd + 1
        }
        if (inFence) for (k in fStart until n) m[k] = true
    }

    /** 行内代码：成对的反引号。未闭合的只吞到行尾（旧行为是吞到结尾，一个手滑就吞掉整篇标签） */
    private fun maskInlineCode(body: String, m: BooleanArray) {
        val n = body.length
        var i = 0
        while (i < n) {
            if (m[i] || body[i] != '`') { i++; continue }
            var q = i
            while (q < n && body[q] == '`' && !m[q]) q++
            val run = q - i
            var lineEnd = body.indexOf('\n', q)
            if (lineEnd < 0) lineEnd = n
            var close = -1
            var closeEnd = -1
            var j = q
            while (j < lineEnd) {
                if (m[j] || body[j] != '`') { j++; continue }
                var r = j
                while (r < lineEnd && body[r] == '`' && !m[r]) r++
                if (r - j == run) { close = j; closeEnd = r; break }
                j = r
            }
            if (close < 0) {
                for (k in i until lineEnd) m[k] = true
                i = lineEnd
            } else {
                for (k in i until closeEnd) m[k] = true
                i = closeEnd
            }
        }
    }

    /** %%注释%%（可跨行）。没闭合就按普通文字，不吞后半篇 */
    private fun maskComments(body: String, m: BooleanArray) {
        val n = body.length
        var i = 0
        while (i + 1 < n) {
            if (m[i] || body[i] != '%' || body[i + 1] != '%' || m[i + 1]) { i++; continue }
            var j = i + 2
            var close = -1
            while (j + 1 < n) {
                if (!m[j] && body[j] == '%' && body[j + 1] == '%' && !m[j + 1]) { close = j; break }
                j++
            }
            if (close < 0) { i += 2; continue }
            for (k in i until close + 2) m[k] = true
            i = close + 2
        }
    }

    // ---------------- 小工具 ----------------

    /** 去掉包在外面的 `<...>` / 引号 */
    private fun unwrap(s: String): String {
        var t = s.trim()
        if (t.length >= 2) {
            val a = t.first()
            val b = t.last()
            if ((a == '<' && b == '>') || (a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                t = t.substring(1, t.length - 1).trim()
            }
        }
        return t
    }

    /** 把 `a//b///c` 折成 `a/b/c`（保留开头/结尾的斜杠，由调用方 trim） */
    private fun collapseSlash(s: String): String {
        if (s.indexOf("//") < 0) return s
        val sb = StringBuilder(s.length)
        var prev = ' '
        for (ch in s) {
            if (ch == '/' && prev == '/') continue
            sb.append(ch)
            prev = ch
        }
        return sb.toString()
    }

    /**
     * 浮层里选中的标签追加到留言末尾。
     *
     * 已经写过的标签不重复追加 —— 用户点了 chip 又在正文里打了同一个标签时，
     * 追加两次会让正文出现「#数学 #数学」。
     */
    fun appendTags(body: String, add: List<String>): String {
        if (add.isEmpty()) return body
        val have = tags(body).toSet()
        val missing = add.filter { normTag(it) !in have }
        if (missing.isEmpty()) return body
        val tail = missing.joinToString(" ") { "#" + it.trim().trimStart('#') }
        return if (body.isBlank()) tail else body.trimEnd() + " " + tail
    }
}
