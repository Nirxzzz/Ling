package com.chun.lingzhu

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 双链解析：把正文里的 `[[目标#标题|别名]]` / `[文字](目标)` 落到「是哪一篇笔记」。
 *
 * ## 为什么是纯函数
 * 链接的落地规则（同名两篇怎么办、路径写了一半怎么办、别名算不算）如果放进 UI，
 * 详情页和列表页各写一份，迟早出现两处结论不一样。这里只做判断，
 * 数据由调用方传进来（[Doc]），所以能在 JVM 上把所有歧义场景钉死。
 *
 * ## 匹配优先级（高 → 低）
 * 1. uid 全等（`[[01a1...]]` 这种，同步/外部工具常见）
 * 2. 路径全等（`[[Notes/导数]]`）
 * 3. 路径后缀（`[[导数]]` 命中 `Notes/导数`，Obsidian 的短路径写法）
 * 4. 标题全等
 * 5. 别名（frontmatter 里的 aliases）
 *
 * 同一级出现**多篇**命中时返回 [Resolution.Ambiguous]，由调用方让用户选 ——
 * 绝不按「列表里第一条」静默取胜者：那种静默的选择会在用户完全不知情时把链接指到错的地方。
 *
 * ## 附件路径
 * 只允许落在 vault 内部：拒绝绝对路径、`..`、盘符、`~`，
 * 最后再比一次 canonical 路径，防止软链接把读取带出 vault。
 */
object NoteLinks {

    /**
     * 一篇笔记的解析输入。
     *
     * [path] 是相对 vault 的路径（`.md` 可带可不带），[aliases] 来自 frontmatter。
     * 这里不解析 Markdown：frontmatter 归 NoteMarkdown，本对象只吃已解析好的值。
     */
    data class Doc(
        val uid: String,
        val title: String,
        val body: String = "",
        val path: String = "",
        val aliases: List<String> = emptyList(),
    )

    /** 命中的方式。UI 想提示「按别名找到的」时用得上 */
    enum class Via { UID, PATH, TITLE, ALIAS }

    /** 解析结果。没有「猜一个」这个分支，要么唯一、要么明确告诉调用方有歧义 */
    sealed class Resolution {
        data class Hit(val doc: Doc, val via: Via) : Resolution()
        data class Ambiguous(val key: String, val matches: List<Doc>) : Resolution()
        data class Missing(val key: String) : Resolution()

        /** 唯一命中才有文档，歧义/缺失都是 null（调用方必须显式处理那两个分支） */
        val docOrNull: Doc? get() = (this as? Hit)?.doc
    }

    // ---------------- 目标的规范化 ----------------

    /**
     * 链接目标 → 比较用的键：去外层 `[[ ]]`/`<>`、去 `?query`、`#标题`、`|别名`、
     * 反斜杠转正斜杠、折叠 `//`、去 `./` 与首尾 `/`、去 `.md`、小写。
     */
    fun normalizeTarget(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("[[") && s.endsWith("]]")) s = s.substring(2, s.length - 2)
        if (s.length >= 2 && s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length - 1)
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        val h = s.indexOf('#')
        if (h >= 0) s = s.substring(0, h)
        val p = s.indexOf('|')
        if (p >= 0) s = s.substring(0, p)
        s = pctDecode(s) ?: s
        s = s.replace('\\', '/')
        s = collapseSlash(s).trim()
        while (s.startsWith("./")) s = s.substring(2)
        s = s.trim('/')
        if (s.length > 3 && s.endsWith(".md", ignoreCase = true)) s = s.substring(0, s.length - 3).trimEnd('/')
        return s.lowercase()
    }

    // ---------------- 解析 ----------------

    /**
     * 把一条链接目标解析成具体笔记。
     *
     * 空目标（`[[#标题]]`）返回 [Resolution.Missing]：那是「本篇的某个标题」，
     * 不是另一篇笔记，调用方应当走 [findHeading]。
     */
    fun resolve(target: String, docs: List<Doc>): Resolution {
        val key = normalizeTarget(target)
        if (key.isEmpty()) return Resolution.Missing(key)
        return Corpus(docs).resolve(key)
    }

    // ---------------- 反向链接 ----------------

    /**
     * 一篇笔记正文里引用到的目标（已规范化）。图片不算引用，网页/邮件这类**外部链接**也不算 ——
     * 它们永远落不到库内某篇笔记上，算进来只会让每篇的反链多走一遍注定不命中的解析。
     */
    fun references(body: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in NoteSyntax.wikiLinks(body)) {
            if (w.embed) continue
            if (isExternal(w.target)) continue
            val k = normalizeTarget(w.target)
            if (k.isNotEmpty()) out.add(k)
        }
        for (l in NoteSyntax.markdownLinks(body)) {
            if (l.image) continue
            if (isExternal(l.dest)) continue
            val k = normalizeTarget(l.dest)
            if (k.isNotEmpty()) out.add(k)
        }
        return out.toList()
    }

    /**
     * 有没有 URL scheme（`http:` / `mailto:` / `file:` …）。
     *
     * 只认白名单，不按「像不像一个 scheme」猜：库内路径理论上允许冒号（`a:b.md`），
     * 而这一批 scheme 绝不会是一篇笔记的名字，白名单能保证不把真笔记误判成外部链接。
     */
    private fun isExternal(raw: String): Boolean {
        val t = raw.trim()
        val colon = t.indexOf(':')
        if (colon <= 0) return false
        return t.substring(0, colon).lowercase() in EXTERNAL_SCHEMES
    }

    private val EXTERNAL_SCHEMES = setOf(
        "http", "https", "mailto", "tel", "sms", "ftp", "ftps",
        "file", "data", "obsidian", "intent", "market", "javascript",
    )

    /**
     * 谁引用了 [target]。结果按标题排序（同标题再看 uid，保证稳定）。
     *
     * 判断分两步：
     *  1. 先真解析一次 —— 链接写的是别名/短路径也要算数；
     *  2. 解析不唯一（[Resolution.Ambiguous] / [Resolution.Missing]）时，
     *     退一步按字面键比对目标自己的 uid/路径/标题/别名。
     *     否则「两篇同名，其中一篇被引用」会因为歧义而丢掉全部反向链接 ——
     *     反向链接宁可多算一条，也不能该显示时不显示。
     */
    fun backlinks(docs: List<Doc>, target: Doc): List<Doc> = backlinks(docs, target.uid)

    fun backlinks(docs: List<Doc>, targetUid: String): List<Doc> {
        val target = docs.firstOrNull { it.uid == targetUid } ?: return emptyList()
        val own = LinkedHashSet<String>()
        own.add(target.uid.lowercase())
        val pk = pathKey(target.path)
        if (pk.isNotEmpty()) {
            own.add(pk)
            own.add(pk.substringAfterLast('/'))
        }
        if (target.title.isNotBlank()) own.add(target.title.trim().lowercase())
        target.aliases.forEach { if (it.isNotBlank()) own.add(it.trim().lowercase()) }

        val corpus = Corpus(docs)
        val out = ArrayList<Doc>()
        for (d in docs) {
            if (d.uid == target.uid) continue
            for (ref in references(d.body)) {
                val hit = if (ref in own) true else {
                    val r = corpus.resolve(ref)
                    r is Resolution.Hit && r.doc.uid == target.uid
                }
                if (hit) { out.add(d); break }
            }
        }
        return out.sortedWith(compareBy({ it.title.trim().lowercase() }, { it.uid }))
    }

    /** 链接目标在 [doc] 里的目标偏移；找不到返回 -1 */
    fun findHeading(body: String, heading: String): Int {
        var want = heading.trim()
        if (want.startsWith("#")) want = want.substring(1).trim()
        if (want.isEmpty()) return 0
        // [[A#父#子]] 这种多级写法：取最后一级
        if (want.contains('#')) want = want.substringAfterLast('#').trim()
        if (want.isEmpty()) return 0

        val heads = NoteSyntax.headings(body)
        heads.firstOrNull { it.text.equals(want, ignoreCase = true) }?.let { return it.offset }

        val anchor = NoteSyntax.anchorOf(want)
        if (anchor.isNotEmpty()) {
            heads.firstOrNull { NoteSyntax.anchorOf(it.text) == anchor }?.let { return it.offset }
        }
        // 标题里本来带 # 的情况（「C#」这种），anchorOf 会把它吃掉
        heads.firstOrNull {
            it.text.trim().replace(Regex("\\s+"), " ").equals(want.replace(Regex("\\s+"), " "), true)
        }?.let { return it.offset }
        return -1
    }

    // ---------------- 附件 ----------------

    /**
     * 附件引用在 md 里的规范前缀。磁盘上的附件根是 `NoteAttach.dir(ctx)`
     * （`filesDir/notes/att`），不是 vault 下的 `attachments/` 目录 ——
     * 所以链接里的 `attachments/202610/x.jpg` 只是**逻辑前缀**，落盘前要去掉。
     */
    const val ATTACH_PREFIX = "attachments/"

    /** vault 内的逻辑附件路径（导出/写 md 时用）：`202610/x.jpg` → `attachments/202610/x.jpg` */
    fun attachmentRef(rel: String): String = ATTACH_PREFIX + rel.trim().trimStart('/').removePrefix(ATTACH_PREFIX)

    /**
     * 把链接目标解析成附件根里的真实文件，找不到/不安全都返回 null。
     *
     * [root] 是**附件根**（调用方传 `NoteAttach.dir(ctx)` 或测试里的临时目录），
     * `[[a.png]]` 与 `![](attachments/a.png)` 两种写法都能落到同一个文件。
     * [fromPath] 给了的话（引用方笔记自己的路径），中间再插一层「笔记所在目录/<目标>」——
     * Markdown 的相对链接本来就是相对引用方的。
     *
     * 安全边界（任何一条不满足就是 null，绝不返回根目录外的文件）：
     *  - 去 `?query` / `#fragment` / 逻辑 `attachments/` 前缀、%XX 解码、`\` → `/`
     *  - 拒绝绝对路径、`~` 开头、盘符、含 `\u0000`
     *  - 拒绝任何 `..` 段
     *  - 结果必须是根内的**普通文件**（canonical 路径比对，挡住软链接逃逸）
     */
    fun attachment(root: File, target: String, fromPath: String = ""): File? {
        val rel = safeRelative(target) ?: return null
        val rootReal = realPath(root) ?: return null
        if (!rootReal.isDirectory) return null

        val candidates = ArrayList<String>(3)
        candidates.add(rel)
        if (rel.startsWith(ATTACH_PREFIX)) {
            val stripped = rel.substring(ATTACH_PREFIX.length).trim('/')
            if (stripped.isNotEmpty()) candidates.add(stripped)
        }
        val dir = fromPath.trim().replace('\\', '/').let {
            val slash = it.lastIndexOf('/')
            if (slash > 0) it.substring(0, slash) else ""
        }
        if (dir.isNotEmpty()) {
            val base = safeRelative(dir)
            if (base != null && base.isNotEmpty()) candidates.add("$base/$rel")
        }

        for (c in candidates) {
            val real = realPath(File(root, c)) ?: continue
            if (!real.isFile) continue
            if (!inside(real, rootReal)) continue
            return real
        }
        return null
    }

    /**
     * 相对路径的安全化：返回 vault 内的相对路径（`/` 分隔，无 `.`/`..`）。
     * 只要出现绝对路径、盘符、`~`、`..`、NUL，就返回 null。
     */
    fun safeRelative(target: String): String? {
        var s = target.trim()
        if (s.length >= 2 && s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length - 1)
        if (s.isEmpty()) return null
        if (s.any { it == '\u0000' }) return null
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        val h = s.indexOf('#')
        if (h >= 0) s = s.substring(0, h)
        s = pctDecode(s) ?: return null
        s = s.replace('\\', '/')
        if (s.isEmpty()) return null
        if (s.startsWith("/")) return null                       // 绝对路径
        if (s.startsWith("~")) return null                        // 家目录写法
        if (s.length >= 2 && s[1] == ':' && s[0].isLetter()) return null  // C:/...
        val parts = ArrayList<String>(4)
        for (seg in s.split('/')) {
            when (seg) {
                "", "." -> continue
                ".." -> return null
                else -> parts.add(seg)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    // ---------------- 小工具 ----------------

    private fun realPath(f: File): File? = try { f.canonicalFile } catch (_: Exception) { null }

    private fun inside(file: File, root: File): Boolean {
        val f = file.path
        val r = root.path
        return f == r || f.startsWith(r + File.separator)
    }

    /** `%E5%AF%BC` → 原文。`+` 不当空格（路径里的加号是加号）；畸形转义按普通文字保留 */
    private fun pctDecode(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = hex(s[i + 1])
                val lo = hex(s[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(StandardCharsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return try { String(out.toByteArray(), StandardCharsets.UTF_8) } catch (_: Exception) { null }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * 一次解析用的索引。
     *
     * 单次 [resolve] 建索引也是 O(n)，与原先那几轮 `docs.filter {}` 同量级；
     * 但反链要拿「每篇 × 每条引用」去解析，不建索引就是 O(篇数² × 引用数) ——
     * 上千篇的库会让后台线程卡上好几秒。索引只是把扫表换成查表：
     * 优先级顺序、歧义判定、以及命中的文档先后，都与扫表版本逐条一致。
     */
    private class Corpus(docs: List<Doc>) {

        private val byUid = HashMap<String, MutableList<Doc>>(docs.size)
        private val byPath = HashMap<String, MutableList<Doc>>(docs.size)
        private val bySuffix = HashMap<String, MutableList<Doc>>(docs.size)
        private val byTitle = HashMap<String, MutableList<Doc>>(docs.size)
        private val byAlias = HashMap<String, MutableList<Doc>>(docs.size)

        init {
            for (d in docs) {
                addOnce(byUid, d.uid.lowercase(), d)
                val pk = pathKey(d.path)
                addOnce(byPath, pk, d)
                for (s in pathSuffixes(pk)) addOnce(bySuffix, s, d)
                addOnce(byTitle, d.title.trim().lowercase(), d)
                for (a in d.aliases) addOnce(byAlias, a.trim().lowercase(), d)
            }
        }

        fun resolve(key: String): Resolution {
            if (key.isEmpty()) return Resolution.Missing(key)
            pick(byUid[key], Via.UID, key)?.let { return it }
            pick(byPath[key], Via.PATH, key)?.let { return it }
            // 短路径：[[导数]] 命中 Notes/导数；[[Notes/导数]] 也能命中更深的 a/Notes/导数
            pick(bySuffix[key], Via.PATH, key)?.let { return it }
            pick(byTitle[key], Via.TITLE, key)?.let { return it }
            pick(byAlias[key], Via.ALIAS, key)?.let { return it }
            return Resolution.Missing(key)
        }

        /** 某一级恰好 1 篇才算命中；0 篇返回 null 继续下一级；多篇直接判定歧义 */
        private fun pick(matches: List<Doc>?, via: Via, key: String): Resolution? {
            if (matches == null || matches.isEmpty()) return null
            // 拷一份交出去：索引自己只读它，但调用方不该拿到内部的可变列表
            if (matches.size == 1) return Resolution.Hit(matches[0], via)
            return Resolution.Ambiguous(key, matches.toList())
        }

        /**
         * 同一个键下同一篇只记一次。
         *
         * 按引用判重：`aliases = ["导数", "导数"]` 是同一篇笔记的重复写法，
         * 若按值塞两遍，它就会把自己判成「两篇同名 → 歧义」。
         */
        private fun addOnce(into: HashMap<String, MutableList<Doc>>, key: String, d: Doc) {
            val list = into.getOrPut(key) { ArrayList(1) }
            if (list.none { it === d }) list.add(d)
        }
    }
}

/**
 * 折叠连续的 `/`：`a//b` → `a/b`。
 *
 * 文件和对象里都要用（[NoteLinks.normalizeTarget] 与路径键），所以放文件级；
 * 顶层 private 对同一文件里的 `object` 与嵌套类都直接可见。
 */
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

/** 文档路径的比较键：与 [NoteLinks.normalizeTarget] 同样的规范化，但没有 `#`/`|` 语义 */
private fun pathKey(path: String): String {
    var s = path.trim().replace('\\', '/')
    s = collapseSlash(s).trim()
    while (s.startsWith("./")) s = s.substring(2)
    s = s.trim('/')
    if (s.length > 3 && s.endsWith(".md", ignoreCase = true)) s = s.substring(0, s.length - 3).trimEnd('/')
    return s.lowercase()
}

/**
 * 路径在**段边界**上的所有真后缀：`a/b/c` → `b/c`、`c`。
 *
 * 「短路径」查询（`[[导数]]` 命中 `notes/导数`）本来判的是 `pk.endsWith("/" + key)`；
 * 枚举后缀就把这一步变成查表，命中集合与逐条 `endsWith` 完全相同 ——
 * 因为 `endsWith("/"+key)` 恰恰要求 key 从一个 `/` 之后开始，而枚举只产出这些位置。
 */
private fun pathSuffixes(key: String): List<String> {
    if (key.isEmpty()) return emptyList()
    val out = ArrayList<String>(2)
    var i = key.indexOf('/')
    while (i >= 0) {
        val s = key.substring(i + 1)
        if (s.isNotEmpty()) out.add(s)
        i = key.indexOf('/', i + 1)
    }
    return out
}
