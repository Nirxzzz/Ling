package com.chun.lingzhu

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * 笔记的 Markdown 形态：frontmatter + 正文。
 *
 * ## 为什么单独一个文件
 * 这里**全部是纯字符串处理**，不碰 Context、不碰 SQLite、不碰文件系统。
 * 工程只有 JVM 单测，没有 androidTest —— 所以「写出去的 md 能不能原样读回来」
 * 「用户手写的未知 frontmatter 字段会不会被抹掉」这些必须能在 JVM 上钉死，
 * 否则只能等真机上把用户的笔记改坏才发现。
 * 落盘（临时文件 + fsync + 同盘 rename）在 [NoteVault] 里。
 *
 * ## 三条不可协商的规则
 * 1. **正文逐字节保留**：LaTeX（`$...$`、反斜杠转义）、行内注释、代码块、行尾空格
 *    都不许被解析器规范化。所以正文根本不进 YAML，就是分隔线之后的那段原文。
 * 2. **未知 frontmatter 字段原样保留**：用户或别的工具加的 `source_url` 之类
 *    不能在 Ling 保存一次之后就消失。重写时先读旧文件的 frontmatter，只覆盖自己认识的键。
 * 3. **YAML 只用 SafeConstructor**：md 是外部可编辑、可导入的输入，
 *    绝不允许 YAML 构造任意 Java 对象（`!!javax.script...` 那一类）。
 */
object NoteMarkdown {

    /** 附件在 md/导出目录里的子目录名。物理目录由 [NoteAttach.dir] 决定，两者不是同一处 */
    const val ATTACH_DIR = "attachments"

    /** 本对象认识的顶层键，其余一律当「用户的字段」保留 */
    private val KNOWN_TOP = setOf("uid", "title", "aliases", "ling")

    /** `ling` 段里由本应用维护的键，其余同样保留 */
    private val KNOWN_LING = setOf(
        "id", "source", "origin", "pinned", "archived", "deleted_at",
        "created_at", "updated_at", "ocr_text", "summary", "todo_id", "card_ids", "rev",
        "attachments",
    )

    /**
     * 一个附件在 md 里的记录。
     *
     * `path` / `thumb` 是**相对附件根**的路径（[NoteAttach.dir]），不是绝对路径：
     * 换手机、恢复备份之后 filesDir 会变，存绝对路径等于让所有图片都找不到。
     */
    data class AttRec(
        val uid: String,
        val mime: String = "image/jpeg",
        val path: String,
        val thumb: String = "",
        val w: Int = 0,
        val h: Int = 0,
        val bytes: Long = 0L,
        val sha256: String = "",
        val createdAt: Long = 0L,
    )

    /**
     * 列表/链接扫描用的一篇文档。
     *
     * `uid` 是稳定标识（frontmatter 里那个），`path` 是相对 vault 根的 md 路径**去掉 `.md`**，
     * `body` 是剥离 frontmatter 之后的正文（和索引里的 notes.body 一致）。
     */
    data class DocMeta(
        val uid: String,
        val title: String,
        val body: String = "",
        val path: String = "",
        val aliases: List<String> = emptyList(),
    )

    /** 解析结果。`localId` 为 0 表示文件里没写（导入的新文件） */
    data class Parsed(
        val uid: String,
        val title: String,
        val body: String,
        val aliases: List<String>,
        val localId: Long,
        val item: Note.Item,
        val attachments: List<AttRec>,
        val hadFrontmatter: Boolean,
    )

    // ---------------- 命名 ----------------

    /**
     * 标题 → 文件名里的安全片段。
     *
     * 去掉的字符是会把文件甩出 vault 或让不同系统打不开的那批（路径分隔符、Windows 保留字符、
     * 控制字符），另外压掉首尾空白和结尾的点（Windows 上 `a.` 与 `a` 是同一个名字）。
     * 全空或只剩点号时退回 `无标题` —— 空文件名会让 writeText 直接抛异常。
     */
    fun safeName(raw: String, max: Int = 60): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { c ->
                when {
                    c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
                        c == '"' || c == '<' || c == '>' || c == '|' -> append(' ')
                    c.code < 0x20 || c.code == 0x7F -> append(' ')
                    else -> append(c)
                }
            }
        }
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (collapsed.isEmpty() || collapsed.all { it == '.' }) return "无标题"
        return if (collapsed.length <= max) collapsed else collapsed.substring(0, max).trim()
    }

    /**
     * uid → 文件名里的安全片段。
     *
     * 正常 uid 是 UUID（只含 `-` 和小写十六进制），这里主要是防导入文件里塞进来的怪 uid：
     * 带 `/` 或者 `..` 的 uid 会把路径写到自己目录外面去。
     */
    fun safeUid(uid: String): String {
        val cleaned = buildString(uid.length) {
            uid.forEach { c ->
                if (c.isLetterOrDigit() && c.code < 0x80 || c == '-' || c == '_') append(c) else append('-')
            }
        }.trim('-')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return "n" + Integer.toHexString(uid.hashCode())
        }
        return if (cleaned.length <= 80) cleaned else cleaned.substring(0, 80)
    }

    /** vault 里的文件名：`{safeTitle--uid}.md` */
    fun fileName(title: String, uid: String): String = safeName(title) + "--" + safeUid(uid) + ".md"

    // ---------------- 写出 ----------------

    /**
     * 编码成 md 文本。
     *
     * @param previous 这个文件**原来**的内容（新文件传 null）。给了就沿用它里面本对象不认识的
     *   frontmatter 字段和 `ling` 下的未知键，保证「用户加的字段保存一次不会消失」。
     */
    fun encode(
        item: Note.Item,
        localId: Long,
        attachments: List<AttRec> = emptyList(),
        previous: String? = null,
        /** 额外写进 `ling` 段的键（如导入冲突副本的 `conflict_of`）。放在最后，覆盖同名的旧值 */
        extraLing: Map<String, Any?> = emptyMap(),
    ): String {
        val prev = previous?.let { runCatching { frontmatterOf(it) }.getOrNull() }

        val top = LinkedHashMap<String, Any?>()
        top["uid"] = item.uid
        top["title"] = item.title
        val aliases = prev?.get("aliases").asStringList()
        if (aliases.isNotEmpty()) top["aliases"] = aliases
        // 未知顶层字段原样搬过去（含嵌套 map/list），顺序也基本保持
        prev?.forEach { (k, v) -> if (k !in KNOWN_TOP) top[k] = v }

        val ling = LinkedHashMap<String, Any?>()
        (prev?.get("ling") as? Map<*, *>)?.forEach { (k, v) ->
            val key = k?.toString() ?: return@forEach
            if (key !in KNOWN_LING) ling[key] = v
        }
        ling["id"] = localId
        ling["source"] = item.source
        ling["origin"] = item.origin
        ling["pinned"] = item.pinned
        ling["archived"] = item.archived
        ling["deleted_at"] = item.deletedAt
        ling["created_at"] = item.createdAt
        ling["updated_at"] = item.updatedAt
        ling["ocr_text"] = item.ocrText
        ling["summary"] = item.summary
        ling["todo_id"] = item.todoId
        ling["card_ids"] = item.cardIds
        ling["rev"] = item.rev
        // 额外键最后写：导入冲突副本的 `conflict_of` 必须落盘，
        // 否则「这条是从哪篇复制出来的」在第一次保存之后就永远查不到了
        extraLing.forEach { (k, v) -> if (k.isNotEmpty()) ling[k] = v }
        if (attachments.isNotEmpty()) {
            ling["attachments"] = attachments.map { a ->
                LinkedHashMap<String, Any?>().apply {
                    put("uid", a.uid)
                    put("mime", a.mime)
                    put("path", a.path)
                    if (a.thumb.isNotEmpty()) put("thumb", a.thumb)
                    put("w", a.w)
                    put("h", a.h)
                    put("bytes", a.bytes)
                    if (a.sha256.isNotEmpty()) put("sha256", a.sha256)
                    put("created_at", a.createdAt)
                }
            }
        }
        top["ling"] = ling

        val yamlText = newYamlDump().dump(top)
        // 分隔线之后固定写一个空行再是正文：人看得舒服。这个空行是框架的一部分 ——
        // [split] 会把「分隔线行尾 + 这个空行」恰好两个换行一起剥掉，
        // 所以正文（哪怕自己以换行开头/结尾）都能逐字节还原。
        return "---\n" + yamlText + "---\n\n" + item.body
    }

    // ---------------- 读回 ----------------

    /**
     * 解码 md 文本。**不抛异常**（除了 frontmatter 结构本身坏掉）：
     * 没有 frontmatter 的文件当成「只有正文」，uid 用 [fallbackUid] 或现场生成。
     *
     * @throws IllegalArgumentException frontmatter 开了头却没有结束行 / YAML 语法错误
     */
    fun decode(text: String, fallbackUid: String = ""): Parsed {
        val (fm, body) = split(text)
        if (fm == null) {
            val uid = fallbackUid.ifBlank { Note.newUid() }
            return Parsed(
                uid = uid,
                title = Note.firstLine(body),
                body = body,
                aliases = emptyList(),
                localId = 0L,
                item = Note.Item(uid = uid, title = Note.firstLine(body), body = body),
                attachments = emptyList(),
                hadFrontmatter = false,
            )
        }
        val lingMap = fm["ling"] as? Map<*, *>
        fun long(key: String): Long = when (val v = lingMap?.get(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
        fun int(key: String): Int = when (val v = lingMap?.get(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
        fun str(key: String): String = lingMap?.get(key)?.toString() ?: ""
        fun bool(key: String): Boolean = when (val v = lingMap?.get(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true) || v == "1"
            else -> false
        }

        val uid = fm["uid"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackUid.ifBlank { Note.newUid() }
        val title = fm["title"]?.toString() ?: ""
        val atts = ArrayList<AttRec>()
        (lingMap?.get("attachments") as? List<*>)?.forEach { row ->
            val m = row as? Map<*, *> ?: return@forEach
            val path = m["path"]?.toString().orEmpty()
            // 没有路径的附件记录没有意义：删了也不会有文件可删，留着只会让详情页去找空文件
            if (path.isEmpty()) return@forEach
            atts.add(
                AttRec(
                    uid = m["uid"]?.toString().orEmpty(),
                    mime = m["mime"]?.toString()?.takeIf { it.isNotEmpty() } ?: "image/jpeg",
                    path = path,
                    thumb = m["thumb"]?.toString().orEmpty(),
                    w = (m["w"] as? Number)?.toInt() ?: 0,
                    h = (m["h"] as? Number)?.toInt() ?: 0,
                    bytes = (m["bytes"] as? Number)?.toLong() ?: 0L,
                    sha256 = m["sha256"]?.toString().orEmpty(),
                    createdAt = (m["created_at"] as? Number)?.toLong() ?: 0L,
                )
            )
        }
        val item = Note.Item(
            uid = uid,
            title = title,
            body = body,
            source = str("source").ifBlank { "manual" },
            origin = str("origin"),
            pinned = bool("pinned"),
            archived = bool("archived"),
            deletedAt = long("deleted_at"),
            createdAt = long("created_at"),
            updatedAt = long("updated_at"),
            ocrText = str("ocr_text"),
            summary = str("summary"),
            todoId = long("todo_id"),
            cardIds = (lingMap?.get("card_ids") as? List<*>)?.mapNotNull { it?.toString() }
                ?: str("card_ids").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            rev = int("rev").coerceAtLeast(1),
        )
        return Parsed(
            uid = uid,
            title = title,
            body = body,
            aliases = fm["aliases"].asStringList(),
            localId = long("id").coerceAtLeast(0L),
            item = item,
            attachments = atts,
            hadFrontmatter = true,
        )
    }

    /** 只要正文（不解析 frontmatter 的调用方用；结构坏了就整段当正文） */
    fun plainBody(text: String): String = runCatching { split(text).second }.getOrDefault(text)

    /**
     * 拆出 frontmatter 与正文。
     *
     * 正文 = 结束分隔线之后、剥掉**恰好两个换行**（分隔线自己的行尾 + [encode] 固定写的
     * 那一个空行）的原文 —— 和 encode 的写法严格对称，所以「读出来再写回去」字节不变，
     * 正文自己以换行开头/结尾也原样保留。
     *
     * 手写的 md 常常没有那个空行：第二个换行不存在就不剥，只吃掉分隔线的行尾 ——
     * 绝不多吃正文自己的第一个换行。两种写法剥的都**只有框架部分**，不做任何 trim。
     */
    fun split(text: String): Pair<Map<String, Any?>?, String> {
        val t = if (text.startsWith("\uFEFF")) text.substring(1) else text
        if (!t.startsWith("---")) return null to t
        val firstNl = t.indexOf('\n')
        if (firstNl < 0) return null to t
        var idx = firstNl + 1
        var closeStart = -1
        var closeEnd = -1
        while (idx <= t.length) {
            val nl = t.indexOf('\n', idx)
            val lineEnd = if (nl < 0) t.length else nl
            val line = t.substring(idx, lineEnd).trimEnd('\r')
            if (line == "---" || line == "...") {
                closeStart = idx
                closeEnd = lineEnd
                break
            }
            if (nl < 0) break
            idx = nl + 1
        }
        if (closeStart < 0) throw IllegalArgumentException("frontmatter 开了头却没有结束行")
        val yamlText = t.substring(firstNl + 1, closeStart)
        // 分隔线自己的行尾 + encode 固定写的那一个空行都属于框架、不属于正文：
        // 恰好剥掉这两个换行。正文自己以换行开头/结尾时逐字节保留（见 split 的说明）。
        val rest = stripEol(stripEol(t.substring(closeEnd)))
        return frontmatterOfYaml(yamlText) to rest
    }

    /** 吃掉开头的一个行尾（`\r\n` 或 `\n`）；没有就原样返回。只吃一个，绝不 trim */
    private fun stripEol(s: String): String = when {
        s.startsWith("\r\n") -> s.substring(2)
        s.startsWith("\n") -> s.substring(1)
        else -> s
    }

    /** 只取 frontmatter（重写时用来沿用未知字段）；结构坏了返回 null 而不是炸掉整次保存 */
    fun frontmatterOf(text: String): Map<String, Any?>? =
        runCatching { split(text).first }.getOrNull()

    private fun frontmatterOfYaml(yamlText: String): Map<String, Any?> {
        if (yamlText.isBlank()) return LinkedHashMap()
        val loaded = newYamlLoad().load<Any?>(yamlText)
            ?: return LinkedHashMap()
        if (loaded !is Map<*, *>) throw IllegalArgumentException("frontmatter 不是键值表")
        val out = LinkedHashMap<String, Any?>(loaded.size)
        loaded.forEach { (k, v) -> if (k != null) out[k.toString()] = v }
        return out
    }

    // ---------------- YAML 引擎 ----------------

    /**
     * 读取用的 Yaml：**SafeConstructor**。
     * 换成默认构造函数就等于允许 md 文件实例化任意 Java 类 —— 导入别人给的目录时这是直接的执行面。
     */
    private fun newYamlLoad(): Yaml = Yaml(SafeConstructor(LoaderOptions()))

    /**
     * 写出用的 Yaml：块风格、不折行、允许中文直接出现。
     *
     * `splitLines = false`：默认会把长标题折成多行，读回来值一样但 diff 很难看，
     * 而且多行折叠对含空格的字符串有歧义风险。
     */
    private fun newYamlDump(): Yaml {
        val opts = DumperOptions()
        opts.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        opts.isAllowUnicode = true
        opts.splitLines = false
        opts.indent = 2
        return Yaml(opts)
    }

    private fun Any?.asStringList(): List<String> = when (this) {
        null -> emptyList()
        is List<*> -> mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        is String -> if (isBlank()) emptyList()
        else split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> listOf(toString())
    }
}
