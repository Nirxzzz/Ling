package com.chun.lingzhu

/**
 * Ling 2.7 Anki 卡片模板渲染（纯 JVM：不引用任何 `android.*`，可直接 JVM 单测）。
 *
 * ## 干什么
 * 把 [AnkiData.StudyCard]（笔记 + 模型 + 卡片）按 Anki 的模板语义渲染成正面/背面 HTML。
 * **不做扁平化**：字段原文里的 `<img>`、内联样式、模板自带的 `<script>` 全部原样保留；
 * `[sound:x]` 是本文件唯一会改写的媒体标记（换成 `<audio>`，否则 WebView 里无声可播）。
 *
 * ## 输出约定（与 UI 的契约，别改）
 * - [Rendered.front] / [Rendered.back] 都是 **body 片段**：不含 `<html>`/`<head>`/`<body>`、
 *   不含 `<style>`、不含 `.card` 包裹 div。UI 自己拼骨架，`<body class="card">` 由 UI 加
 *   （Anki 的模型 CSS 选择器写的是 `.card`）。
 * - [Rendered.back] 是**完整背面**：模板里的 `{{FrontSide}}` 已替换为渲染好的正面，
 *   所以 UI 只需要「先显示 front，再整块换成 back」，不要自己再拼一次正面。
 * - [Rendered.css] 是笔记类型自带的 CSS **原文**（可能为空、可能含 `@import`），未包 `<style>`。
 * - 媒体引用保持牌组里的裸相对文件名（`<img src="tiny.png">`），由 UI 的虚拟源
 *   （`https://anki.local/` → `AnkiDeckStore.mediaDir`）解析；不会出现 `file://`、绝对路径或 `https://`。
 * - [Rendered.warnings] 是「用户可见的能力说明」，不是失败信号：拿不准的功能一律
 *   **保留原文 + 报警**，绝不假装兼容，也绝不静默丢内容。本方法不抛异常。
 *
 * ## 支持的模板语法
 * - 字段：`{{Field}}`，按字段名精确匹配；名字不存在才算「未知字段」（见下）。
 * - 条件：`{{#Field}}…{{/Field}}` / `{{^Field}}…{{/Field}}`，可嵌套。
 * - 特殊字段：`{{FrontSide}}`、`{{Card}}`、`{{Deck}}`、`{{Subdeck}}`、`{{Tags}}`、`{{Type}}`。
 * - 过滤器：`text`（去 HTML）、`cloze`（含嵌套与 `::提示`）、`hint`、`type`；可串联
 *   （`{{cloze:text:Text}}` 先 `text` 后 `cloze`）。
 * - 模型 CSS 原样输出；`[sound:x]` → `<audio controls preload="none" src="x">`。
 *
 * ## 有意与 Anki 不同的地方（都在 warnings 里说明）
 * 1. 未知字段 / 未知标签 / 不支持的过滤器（`furigana`/`kanji`/`tts`…）保留 `{{…}}` 原文并报警；
 *    Anki 自己会渲染成空串，那等于静默吞掉内容。
 * 2. `{{hint:Field}}` 用原生 `<details class="hint">` 实现（无脚本也能点开），不是 Anki 那套
 *    `onclick` 链接；语义等价且不依赖 JS。
 * 3. 打字题 `{{type:Field}}`：正面给 `<input id="typeans">`；背面只有调用方经由
 *    [render] 的 `typedAnswer` 传入用户输入才做对错比较，否则显示参考答案并报警。
 * 4. 图像遮挡（Image Occlusion）牌组需要 Anki 的遮挡绘制，这里只按字段原文显示并报警。
 *
 * ## 不假装兼容
 * 插件字段、任意 JS、`tts` 语音、外部网络资源、绝对/`file://` 引用都不"猜"：
 * 保留原文 + 写进 [Rendered.warnings]，由 UI 判断怎么提示用户。
 *
 * 语义对照 Anki 的模板行为重写（未复制上游 AGPL 实现）。
 */
object AnkiTemplate {

    /** 渲染结果。字段语义见类注释里的「输出约定」。 */
    data class Rendered(
        val front: String,
        val back: String,
        val css: String,
        val warnings: List<String>,
    )

    /** 契约入口：不带用户打字内容。 */
    fun render(c: AnkiData.StudyCard): Rendered = render(c, null)

    /**
     * 带打字题用户输入的重载（[render] 单参版等价于 `typedAnswer == null`）。
     *
     * `typedAnswer` 只影响背面 `{{type:Field}}` 的对错比较：为 null 时背面显示参考答案并报警。
     */
    fun render(c: AnkiData.StudyCard, typedAnswer: String?): Rendered {
        val warnings = LinkedHashSet<String>()
        checkModel(c.model, warnings)
        val tpl = pickTemplate(c, warnings)
        val activeCloze = if (c.model.cloze) c.card.ord + 1 else 0
        if (tpl == null) {
            // 导入器已校验「无可用卡片就拒收」，正常走不到；真到了也按字段原文显示，别丢内容。
            val joined = postProcess(c.note.fields.joinToString("<br>"), warnings)
            return Rendered(joined, joined, c.model.css, warnings.toList())
        }
        val front = postProcess(
            renderNodes(
                parse(tpl.front, warnings),
                Ctx(c.note, c.model, c.deck, tpl, Mode.QUESTION, null, typedAnswer, activeCloze, warnings),
            ),
            warnings,
        )
        val back = postProcess(
            renderNodes(
                parse(tpl.back, warnings),
                Ctx(c.note, c.model, c.deck, tpl, Mode.ANSWER, front, typedAnswer, activeCloze, warnings),
            ),
            warnings,
        )
        return Rendered(front, back, c.model.css, warnings.toList())
    }

    // ───────────────────────── 模型 / 模板选择 ─────────────────────────

    /**
     * 挑模板：普通模型按 `card.ord` 找（Anki 里卡片 ord 就是模板 ord）；
     * 挖空模型只有一张模板，`card.ord` 是挖空号不是模板号，所以固定 ord=0。
     */
    private fun pickTemplate(c: AnkiData.StudyCard, warnings: MutableSet<String>): AnkiData.Template? {
        val tpls = c.model.templates
        if (tpls.isEmpty()) {
            warnings += "模型「${c.model.name}」没有模板，按字段原文显示"
            return null
        }
        if (c.model.cloze) return tpls.firstOrNull { it.ord == 0 } ?: tpls.first()
        return tpls.firstOrNull { it.ord == c.card.ord }
            ?: tpls.getOrNull(c.card.ord)
            ?: run {
                warnings += "找不到 ord=${c.card.ord} 的模板（模型「${c.model.name}」），已用第一张"
                tpls.first()
            }
    }

    /** 模型级一次性检查：图像遮挡、CSS 外部引用。 */
    private fun checkModel(model: AnkiData.Model, warnings: MutableSet<String>) {
        val occlusion = model.name.contains("Image Occlusion", true) ||
            model.fields.any { it.name.contains("Occlusion", true) }
        if (occlusion) {
            warnings += "图像遮挡（Image Occlusion）牌组：遮挡图形由 Anki 的遮挡绘制生成，这里按字段原文显示"
        }
        if (EXTERNAL_REF_RE.containsMatchIn(model.css)) {
            warnings += "笔记类型 CSS 引用了外部 http(s) 资源：离线不加载"
        }
    }

    /** 渲染完的收尾：`[sound:]` 换 `<audio>`，并把「脚本/外部资源」这类事实写进 warnings。 */
    private fun postProcess(html: String, warnings: MutableSet<String>): String {
        var out = html
        if (out.contains("[sound:")) {
            out = SOUND_RE.replace(out) { m -> audioTag(m.groupValues[1].trim()) }
        }
        if (out.contains("<script", ignoreCase = true)) {
            warnings += "卡片含 <script>：脚本已原样保留，需要在无桥接沙箱里执行"
        }
        if (BASE_TAG_RE.containsMatchIn(out)) {
            // <base href> 会改掉**所有**素材的相对解析基准。原文保留（不偷偷删内容），
            // 但 WebView 的文档 CSP 带 base-uri 'none'，所以它在本 App 里不生效 —— 两件事都告诉用户。
            warnings += "卡片含 <base>：已保留原文，但 WebView 不采用它（素材只从牌组里按相对名取）"
        }
        if (EXTERNAL_REF_RE.containsMatchIn(out)) {
            warnings += "卡片引用了外部 http(s) 资源：离线 WebView 会拦截，不自动联网"
        }
        return out
    }

    private fun audioTag(name: String): String =
        if (name.isEmpty()) "" else "<audio controls preload=\"none\" src=\"${escapeHtml(name)}\"></audio>"

    // ───────────────────────── 模板解析 ─────────────────────────

    private enum class Mode { QUESTION, ANSWER }

    private sealed interface Node {
        /** 模板字面文本（原样输出）。 */
        class Text(val text: String) : Node

        /** 字段引用（可能带过滤器）。[raw] 是原始 `{{…}}` 文本，用来在出问题时原样回填。 */
        class Field(val name: String, val filters: List<String>, val raw: String) : Node

        /** `{{#X}}…{{/X}}`（[negated]=false）或 `{{^X}}…{{/X}}`。 */
        class Section(val name: String, val negated: Boolean, val label: String, val body: List<Node>) : Node

        /** 无法处理但**必须保留**的原文（未知过滤器、未知标签、未闭合条件块…）。 */
        class Literal(val text: String) : Node
    }

    private class Parsed(val nodes: List<Node>, val next: Int, val closed: Boolean)

    private fun parse(src: String, warnings: MutableSet<String>): List<Node> =
        parseNodes(src, 0, src.length, null, warnings).nodes

    /**
     * 容错解析：任意畸形模板都只降级成「原文 + warning」，不抛异常、不丢字符。
     * [closing] 非空表示正在解析某个条件块的 body，遇到配对的 `{{/closing}}` 才收手。
     */
    private fun parseNodes(
        src: String,
        from: Int,
        to: Int,
        closing: String?,
        warnings: MutableSet<String>,
    ): Parsed {
        val out = ArrayList<Node>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                out += Node.Text(text.toString())
                text.setLength(0)
            }
        }
        var i = from
        while (i < to) {
            val open = src.indexOf("{{", i)
            if (open < 0 || open >= to) {
                text.append(src, i, to)
                i = to
                break
            }
            text.append(src, i, open)
            val close = src.indexOf("}}", open + 2)
            if (close < 0 || close >= to) {
                text.append(src, open, to)
                warnings += "模板里有未闭合的 {{…}}，原样保留"
                i = to
                break
            }
            val raw = src.substring(open, close + 2)
            val inner = src.substring(open + 2, close).trim()
            when {
                inner.startsWith("#") || inner.startsWith("^") -> {
                    flush()
                    val negated = inner[0] == '^'
                    val name = inner.substring(1).trim()
                    if (name.isEmpty() || name.contains(':')) {
                        warnings += "无法识别的条件标签 $raw：原样保留"
                        out += Node.Literal(raw)
                        i = close + 2
                    } else {
                        val sub = parseNodes(src, close + 2, to, name, warnings)
                        if (!sub.closed) {
                            warnings += "条件块 $raw 没有闭合：原样保留"
                            out += Node.Literal(src.substring(open, to))
                            i = to
                        } else {
                            out += Node.Section(name, negated, raw, sub.nodes)
                            i = sub.next
                        }
                    }
                }

                inner.startsWith("/") -> {
                    val name = inner.substring(1).trim()
                    flush()
                    if (closing != null && name == closing) return Parsed(out, close + 2, true)
                    warnings += "多余的闭合标签 $raw：原样保留"
                    out += Node.Literal(raw)
                    i = close + 2
                }

                else -> {
                    flush()
                    val split = splitFilters(inner)
                    when {
                        split.unsupported != null -> {
                            warnings += "不支持的过滤器 ${split.unsupported}（$raw）：原样保留，未做替换"
                            out += Node.Literal(raw)
                        }

                        split.name.isEmpty() -> {
                            warnings += "无法识别的标签 $raw：原样保留"
                            out += Node.Literal(raw)
                        }

                        else -> out += Node.Field(split.name, split.filters, raw)
                    }
                    i = close + 2
                }
            }
        }
        flush()
        return Parsed(out, i, false)
    }

    private class Split(val name: String, val filters: List<String>, val unsupported: String? = null)

    /** `{{cloze:text:Text}}` → filters=[cloze, text]、name=Text（靠字段侧最近的过滤器先应用）。 */
    private fun splitFilters(inner: String): Split {
        val parts = inner.split(':')
        val filters = ArrayList<String>(2)
        var idx = 0
        while (idx < parts.size - 1) {
            val key = parts[idx].trim().lowercase()
            if (key.startsWith("tts")) return Split("", filters, "tts")
            if (key in UNSUPPORTED_FILTERS) return Split("", filters, key)
            if (key !in FILTERS) break
            filters += key
            idx++
        }
        return Split(parts.subList(idx, parts.size).joinToString(":").trim(), filters)
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private class Ctx(
        val note: AnkiData.Note,
        val model: AnkiData.Model,
        val deck: String,
        val template: AnkiData.Template,
        val mode: Mode,
        val frontSide: String?,
        val typedAnswer: String?,
        val activeCloze: Int,
        val warnings: MutableSet<String>,
    )

    private fun renderNodes(nodes: List<Node>, ctx: Ctx): String {
        val sb = StringBuilder()
        for (n in nodes) {
            when (n) {
                is Node.Text -> sb.append(n.text)
                is Node.Literal -> sb.append(n.text)
                is Node.Field -> sb.append(renderField(n, ctx))
                is Node.Section -> {
                    val shown = sectionShown(n.name, ctx)
                    when {
                        shown == null -> {
                            ctx.warnings += "条件 ${n.label} 引用了未知字段：内容原样保留"
                            sb.append(renderNodes(n.body, ctx))
                        }

                        shown != n.negated -> sb.append(renderNodes(n.body, ctx))
                        // 条件不成立：整块消失（Anki 语义）
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun renderField(n: Node.Field, ctx: Ctx): String {
        val plain = plainValue(n.name, ctx)
        if (plain == null) {
            ctx.warnings += "未知字段/标签 ${n.raw}：原样保留（Anki 会渲染成空串）"
            return n.raw
        }
        if (n.filters.isEmpty()) return htmlValue(n.name, plain)
        var s: String = plain
        for (f in n.filters.asReversed()) s = applyFilter(f, s, ctx)
        return s
    }

    /**
     * 字段的**纯文本**取值（字段原值是 HTML，特殊字段是文本）。
     * 只有「模型里根本没有这个字段/标签」才返回 null；模型有但笔记里取不到就按空串
     * （Anki 里这确实是空字段，不该被当成未知标签保留 `{{…}}`）。
     */
    private fun plainValue(name: String, ctx: Ctx): String? {
        return when (name) {
            "FrontSide" -> ctx.frontSide ?: ""
            "Card" -> ctx.template.name
            "Deck" -> ctx.deck
            "Subdeck" -> ctx.deck.substringAfterLast("::")
            "Tags" -> ctx.note.tags.joinToString(" ")
            "Type" -> ctx.model.name
            else -> {
                val f = ctx.model.fields.firstOrNull { it.name == name } ?: return null
                ctx.note.fields.getOrNull(f.ord) ?: ""
            }
        }
    }

    /** 无过滤器时的输出：[plainValue] 的 HTML 形式（字段原文不转义，特殊字段转义）。 */
    private fun htmlValue(name: String, plain: String): String = when (name) {
        "Tags" -> plain.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "<span class=\"tag\">${escapeHtml(it)}</span>" }

        "Deck", "Subdeck", "Card", "Type" -> escapeHtml(plain)
        else -> plain // 含 FrontSide 与普通字段：本来就是 HTML
    }

    /** 条件块的判定：null = 字段不存在（调用方保留内容并报警）。 */
    private fun sectionShown(name: String, ctx: Ctx): Boolean? {
        val plain = plainValue(name, ctx) ?: return null
        return hasVisibleContent(plain)
    }

    private fun applyFilter(filter: String, value: String, ctx: Ctx): String = when (filter) {
        "text" -> stripHtml(value)
        "cloze" -> renderCloze(value, ctx.activeCloze, ctx.mode)
        "hint" -> "<details class=\"hint\"><summary>提示</summary><div class=\"hint-body\">$value</div></details>"
        "type" -> typeAnswer(value, ctx)
        else -> value
    }

    // ───────────────────────── 过滤器实现 ─────────────────────────

    /**
     * 条件块的「有没有内容」判定：`[sound:]` 与 HTML 注释不算内容，纯标签不算内容，
     * 但媒体标签（img/audio/video/object/embed/iframe）算内容 —— 一张图不是空字段。
     */
    private fun hasVisibleContent(src: String): Boolean {
        if (src.isBlank()) return false
        var t = SOUND_RE.replace(src, "")
        t = HTML_COMMENT_RE.replace(t, "")
        if (MEDIA_TAG_RE.containsMatchIn(t)) return true
        t = TAG_RE.replace(t, " ")
        t = decodeEntities(t).replace('\u00A0', ' ')
        return t.isNotBlank()
    }

    /** `text` 过滤器：去标签 + 去 `[sound:]` + 解实体；块级换行保留成换行，别把行黏在一起。 */
    private fun stripHtml(src: String): String {
        var s = SOUND_RE.replace(src, "")
        s = HTML_COMMENT_RE.replace(s, "")
        s = BR_RE.replace(s, "\n")
        s = BLOCK_END_RE.replace(s, "\n")
        s = TAG_RE.replace(s, "")
        return decodeEntities(s).trim()
    }

    /**
     * `cloze` 过滤器：只把**当前卡片**（[activeOrd]）的挖空当作挖空，其余挖空按纯内容展开。
     * 正面：`<span class="cloze">[...]</span>`；背面：`<span class="cloze">内容</span>`，
     * 有 `::提示` 时再跟一段 `<span class="cloze-hint">`。
     * 支持嵌套（`{{c1::外 {{c2::内}}}}`）与提示里的嵌套。
     */
    private fun renderCloze(src: String, activeOrd: Int, mode: Mode): String {
        if (!src.contains("{{c")) return src
        return clozeSegment(src, activeOrd, mode)
    }

    private fun clozeSegment(src: String, activeOrd: Int, mode: Mode): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val start = src.indexOf("{{c", i)
            if (start < 0) {
                out.append(src, i, src.length)
                break
            }
            var j = start + 3
            while (j < src.length && src[j].isDigit()) j++
            if (j == start + 3 || !src.startsWith("::", j)) {
                out.append(src, i, j)
                i = j
                continue
            }
            val ord = src.substring(start + 3, j).toIntOrNull()
            val close = findClozeClose(src, j + 2)
            if (ord == null || ord <= 0 || close < 0) {
                out.append(src, i, j)
                i = j
                continue
            }
            out.append(src, i, start)
            val inner = src.substring(j + 2, close)
            val sep = topLevelDoubleColon(inner)
            val body = if (sep < 0) inner else inner.substring(0, sep)
            val hint = if (sep < 0) "" else inner.substring(sep + 2)
            if (ord == activeOrd) {
                when (mode) {
                    Mode.QUESTION -> out.append("<span class=\"cloze\">[...]</span>")
                    Mode.ANSWER -> {
                        out.append("<span class=\"cloze\">")
                            .append(clozeSegment(body, activeOrd, mode))
                            .append("</span>")
                        if (hint.isNotBlank()) {
                            out.append(" <span class=\"cloze-hint\">")
                                .append(clozeSegment(hint, activeOrd, mode))
                                .append("</span>")
                        }
                    }
                }
            } else {
                out.append(clozeSegment(body, activeOrd, mode))
            }
            i = close + 2
        }
        return out.toString()
    }

    /** 从 [from] 起找与当前 `{{cN::` 配对的 `}}`（考虑嵌套的 `{{`/`}}`）；找不到返回 -1。 */
    private fun findClozeClose(src: String, from: Int): Int {
        var depth = 0
        var k = from
        while (k < src.length) {
            if (src.startsWith("{{", k)) {
                depth++
                k += 2
                continue
            }
            if (src.startsWith("}}", k)) {
                if (depth == 0) return k
                depth--
                k += 2
                continue
            }
            k++
        }
        return -1
    }

    /** 找 `内容::提示` 的分隔点，跳过嵌套的 `{{…}}`。 */
    private fun topLevelDoubleColon(s: String): Int {
        var depth = 0
        var k = 0
        while (k + 1 < s.length) {
            if (s.startsWith("{{", k)) {
                depth++
                k += 2
                continue
            }
            if (s.startsWith("}}", k)) {
                if (depth > 0) depth--
                k += 2
                continue
            }
            if (depth == 0 && s.startsWith("::", k)) return k
            k++
        }
        return -1
    }

    /**
     * `type` 过滤器：正面出输入框；背面对比。
     *
     * 注意：Anki 的逐字 diff（`typeGood`/`typeBad`/`typeMissed` 三段着色）需要用户输入，
     * 而契约里的 `render(c)` 没有这个参数。这里如实处理：有输入就比，没输入就只显示
     * 参考答案并报警，不假装判对错。
     */
    private fun typeAnswer(value: String, ctx: Ctx): String = when (ctx.mode) {
        Mode.QUESTION -> TYPE_INPUT

        Mode.ANSWER -> {
            val expected = stripHtml(value)
            val typed = ctx.typedAnswer?.trim()
            when {
                typed == null -> {
                    ctx.warnings += "打字题：未提供用户输入，背面只显示参考答案"
                    "<code class=\"typeGood\">${escapeHtml(expected)}</code>"
                }

                typed == expected -> "<code class=\"typeGood\">${escapeHtml(expected)}</code>"

                else -> "<code class=\"typeBad\">${escapeHtml(typed)}</code><br>" +
                    "<code class=\"typeGood\">${escapeHtml(expected)}</code>"
            }
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun escapeHtml(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '&') {
                out.append(ch)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                out.append(ch)
                i++
                continue
            }
            val body = s.substring(i + 1, semi)
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.substring(2).toIntOrNull(16))

                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
                else -> NAMED_ENTITIES[body]
            }
            if (decoded != null) {
                out.append(decoded)
                i = semi + 1
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun codePoint(cp: Int?): String? {
        if (cp == null || cp < 0 || cp > 0x10FFFF) return null
        return runCatching { String(Character.toChars(cp)) }.getOrNull()
    }

    private const val TYPE_INPUT =
        "<input type=\"text\" id=\"typeans\" class=\"typeans\" autocomplete=\"off\" " +
            "autocorrect=\"off\" autocapitalize=\"off\" spellcheck=\"false\">"

    private val FILTERS = setOf("text", "cloze", "hint", "type")

    /** 上游确实有、本实现不做的过滤器：如实报警并保留原文。 */
    private val UNSUPPORTED_FILTERS = setOf("furigana", "kanji", "kana", "ruby", "tts")

    private val SOUND_RE = Regex("\\[sound:([^\\]]*)]")
    private val TAG_RE = Regex("(?s)<[^>]*>")
    private val MEDIA_TAG_RE = Regex("(?i)<(?:img|audio|video|object|embed|iframe)\\b")
    private val HTML_COMMENT_RE = Regex("(?s)<!--.*?-->")
    private val BR_RE = Regex("(?i)<br\\s*/?>")
    private val BLOCK_END_RE = Regex("(?i)</(?:p|div|li|tr|h[1-6])\\s*>")
    private val BASE_TAG_RE = Regex("(?i)<base\\b")
    private val EXTERNAL_REF_RE =
        Regex("(?i)(?:(?:src|href)\\s*=\\s*[\"']?https?://|url\\(\\s*[\"']?https?://)")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "ensp" to "\u2002",
        "emsp" to "\u2003",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "hellip" to "\u2026",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "times" to "\u00D7",
        "divide" to "\u00F7",
        "middot" to "\u00B7",
        "bull" to "\u2022",
        "rarr" to "\u2192",
        "larr" to "\u2190",
        "harr" to "\u2194",
        "deg" to "\u00B0",
        "plusmn" to "\u00B1",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
    )
}
