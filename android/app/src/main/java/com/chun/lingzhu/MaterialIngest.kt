package com.chun.lingzhu

/**
 * 资料一键结构化：把 DocumentText.extract 出来的纯文本切成「章节 → 知识点 → 建议卡」。
 *
 * 为什么是纯规则而不是调模型（这是相对「AI 导入」的差异点）：
 *  1. 离线可用：用户在飞机上导入讲义也能用；AI 导入断网就废。
 *  2. 零成本：一份 200 页资料切成 300 个知识点不花一分钱。
 *  3. 可解释：每个知识点都带 Point.origin（原文那一句），界面上点开就能核对是编的还是抽的。
 *  4. 可单测：不依赖网络与随机性，MaterialIngestTest 全量跑在 JVM 上。
 *
 * 与 KnowledgeAiImport 的分工：AI 版负责出题（要理解语义、要编干扰项），
 * 本类负责先把手里的资料拆成可信的骨架 —— 两者产物都进 KnowledgeCard，用同一套预览/勾选/落库流程。
 *
 * 设计红线：抽不出来就少抽。宁可 IngestResult.isEmpty，也不许拼一个原文里没有的概念。
 *
 * 纯 JVM：本文件不 import android.*，JVM 单测直接跑（见 MaterialIngestTest）。
 */
object MaterialIngest {

    /** 知识点名字长度上限。比这长的多半是把整句话当成了标题。 */
    const val NAME_MAX = 40

    /** 原文摘录上限：界面上一行两行能看完的量级。 */
    const val ORIGIN_MAX = 200

    /** 单张卡片背面的字符预算：一份资料动辄上千句，全塞进一张卡既背不下来、也会撑爆卡库。 */
    const val BACK_BUDGET = 260

    /** 一份资料最多出多少张建议卡。卡多了用户要点太多次取消勾选，不如不给。 */
    const val CARD_MAX = 200

    /** 章节切不出来时（整段散文）用的章节名。它不是原文，所以 Section.number 为空。 */
    const val WHOLE = "全文"

    /** 节点 JSON 的三个字段名（与 GraphActivity / KnowledgeGraph 的约定，改动要同时改两边）。 */
    const val NODE_ID = "id"
    const val NODE_LABEL = "label"
    const val NODE_DECK = "deck"

    /** 一个章节的正文最多收这么多句：再长就该被副标题切开了，收多了也没人看。 */
    private const val BODY_SENTENCES_CAP = 4

    /** 卡片背面单句的长度上限：太长的句子做背面记不住，只留开头这么多字。 */
    private const val CARD_LINE_MAX = 120
    // ---------------- 数据模型 ----------------

    /**
     * 一个章节。[number] 是原文里那个编号（2.1 / 三 / (四)），没有编号的隐式章节为空串。
     * 编号原样保留是为了界面上能说「出自 2.1 节」—— 数字比标题原文省地方。
     */
    data class Section(
        val title: String,
        val number: String = "",
        val body: String = "",
        /**
         * 本章唯一键（标题去标点后 + 出现序号）。
         * 用来区分两个都叫光合作用的章节 —— 讲义里同名章节很常见，按标题去重会把第二节吃掉。
         */
        val key: String = title,
    )

    /**
     * 一个知识点。[origin] 是原文里那一句原话（可核对），[line] 是它在原文中的 0 基行号。
     * [section] 同时当知识卡牌组用，用户在卡库里按资料分得开。
     */
    data class Point(
        val name: String,
        val section: String,
        val origin: String,
        val line: Int = -1,
        /**
         * 所属章节的唯一键（默认同 section）。
         * 为什么不能直接用章节名当去重键：两个章节可以都叫光合作用（讲义里很常见），
         * 那时它们是两节、各该出一条；只有当同一节里重复出现才算重复。
         */
        val sectionKey: String = section,
    ) {
        /**
         * 跨章节合并同名概念用的键：去掉空白与标点后的小写形式。
         * 为什么需要：同一份讲义里大标题「一、光合作用」和小节「2.1 光合作用」各出现一次，
         * 画到图上该是同一个节点；但卡片仍按章节各出一张（背面内容不一样）。
         */
        val linkKey: String get() = key(name)
    }

    /** 输出整体。三个列表都可能是空的 —— 抽不出来是合法结果，不是错误。 */
    data class IngestResult(
        val sections: List<Section> = emptyList(),
        val points: List<Point> = emptyList(),
        val cards: List<KnowledgeCard.KCData> = emptyList(),
        /** 给界面用的一句话结论，例如「3 个章节 · 12 个知识点 · 建议 9 张卡」。 */
        val summary: String = "",
    ) {
        val isEmpty: Boolean get() = points.isEmpty() && cards.isEmpty()
    }
    // ---------------- 入口 ----------------

    /**
     * 结构化一段原文。[sourceName] 只用于卡片 origin 的署名（文件名 · 原文摘录），
     * 与 ImportActivity.commit() 写进 KnowledgeCard.KCData.origin 的口径一致。
     *
     * 不抛异常：任何输入（空 / 全空白 / 乱文字 / 几百 KB 无意义字符）都返回结果对象；
     * 抽不出内容就返回 isEmpty 的结果 —— 导入流程不该因为这份文件结构太烂而中断。
     */
    fun ingest(raw: String, sourceName: String = ""): IngestResult {
        // 空输入也要给一句话：界面上「什么都没发生」和「这段资料没结构」必须能区分开
        if (raw.isBlank()) return IngestResult(summary = SUMMARY_EMPTY)

        // 逐行预处理：行号要留着（origin 指回原文行），行首 Markdown 标记（# > - * **）统一剥掉。
        // 前导空白一律去掉：PDF 取文常带排版缩进，靠缩进判层级会误判成代码块样例。
        val lines = ArrayList<Line>()
        for ((i, rawLine) in raw.split('\n').withIndex()) {
            val t = stripMarkers(rawLine)
            if (t.text.isNotEmpty()) lines.add(Line(i, t.text, t.openerBold, t.quoted))
        }
        if (lines.isEmpty()) return IngestResult(summary = SUMMARY_EMPTY)

        val sections = ArrayList<Section>()
        val points = ArrayList<Point>()
        // 当前章节：整段没有标题时也要有个落点，否则知识点无处归类
        var cur = Section(WHOLE, "", "")
        // 章节去重键：标题 + 序号。同名章节（讲义里两节都叫光合作用）必须是两个键，
        // 否则第二节的同名概念会被第一节的去重规则吃掉。
        var curKey = WHOLE
        var sectionSeq = 0
        var body = ArrayList<Holder>()

        // 收口当前章节：正文取前几句拼起来（够卡片兜底用），然后等下一章
        fun flush() {
            val kept = body.take(BODY_SENTENCES_CAP).joinToString("") { s -> s.plain }
            if (cur.title != WHOLE || kept.isNotEmpty()) sections.add(cur.copy(body = kept))
            body = ArrayList()
        }

        for (ln in lines) {
            // 引用块里的行不当标题：那是转述别人的话，不是资料的目录结构。
            // 带冒号的编号行同样不当标题：「1. 牛顿第一定律：惯性」是条目不是目录 ——
            // 若先按标题吃掉，冒号后的解释就成了下一节正文，这一条知识点整段丢失。
            val isColonItem = indexOfSeparator(ln.text) > 0
            val kind = if (ln.quoted || isColonItem) null else headingOf(ln.text)
            if (kind != null) {
                flush()
                val name = headingName(ln, kind)
                curKey = key(name) + "#" + sectionSeq
                sectionSeq++
                cur = Section(title = name, number = kind.number, key = curKey)
                addPoint(points, name, curKey, ln, name)
                continue
            }

            // 非标题行依次试三种锚点：定义句 → 编号条目 → 枚举句。
            // 定义句先试：「1. 牛顿第一定律：惯性」既是编号条目也是定义句，两种切法名字一样，
            // 而定义句能保住冒号后面的解释当正文，编号条目只在没有冒号时才有额外价值。
            val def = definitionOf(ln.text)
            if (def != null) {
                addPoint(points, def.name, curKey, ln, cur.title)
                // 定义句的另一半（是…之后的部分）就是这一章的正文，卡片背面用它
                if (def.rest.isNotEmpty()) body.add(Holder(def.rest, ln))
                continue
            }

            // 编号条目（1. 牛顿第一定律 / ①光合作用）：没有冒号的定义句，靠编号认出来。
            val numItem = numberedItem(ln.text)
            if (numItem != null) {
                addPoint(points, numItem.name, curKey, ln, cur.title)
                if (numItem.rest.isNotEmpty()) body.add(Holder(numItem.rest, ln))
                continue
            }

            // 枚举句（X 包括 A、B、C）也值得当节点，但名字只取主语，不然会变成一句话标题
            val en = enumerationOf(ln.text)
            if (en != null) addPoint(points, en, curKey, ln, cur.title)

            // 不是标题、也不是条目：这一行仍是当前章节的正文（标题后紧跟的第一句也在这里收）
            for (s in sentences(ln.text)) body.add(Holder(s, ln))
        }
        flush()

        val cards = buildCards(sections, points, sourceName)
        return IngestResult(
            sections = sections,
            points = points,
            cards = cards,
            summary = summaryOf(sections, points, cards),
        )
    }

    /**
     * 只做章节切分，不出卡片。给只需要目录的调用方（例如先把资料挂到学习计划前看结构）用。
     * 与 ingest 共用同一套标题规则，不会出现这里认成标题、那里不认的分裂。
     */
    fun sections(raw: String): List<Section> = ingest(raw).sections
    /**
     * 把 points 序列化成图谱页能直接吃的节点 JSON。
     *
     * 为什么用 JSON 字符串跨 Activity 传而不是把 Point 塞进 Intent：
     * Point 是本模块的 data class，图谱那边按节点协议解析即可，不必依赖本类的字段名；
     * 字段名 NODE_ID / NODE_LABEL / NODE_DECK 是两边约定好的最小集合，改字段要同时改两边。
     * 同一概念（linkKey 相同）只出一个节点：图谱上光合作用出现三次会连成一团毛线。
     */
    fun graphNodesJson(points: List<Point>): String {
        val seen = HashSet<String>()
        val sb = StringBuilder("[")
        var n = 0
        for (p in points) {
            if (!seen.add(p.linkKey)) continue
            if (n > 0) sb.append(',')
            sb.append('{')
                .append(Q).append(NODE_ID).append(COLON)
                .append(escapeJson(p.linkKey)).append(COMMA)
                .append(Q).append(NODE_LABEL).append(COLON)
                .append(escapeJson(p.name)).append(COMMA)
                .append(Q).append(NODE_DECK).append(COLON)
                .append(escapeJson(p.section)).append(Q)
            sb.append('}')
            n++
        }
        return sb.append(']').toString()
    }

    /**
     * 最小 JSON 字符串转义：引号 / 反斜杠 / 控制字符。
     * 知识点名字来自外部资料，出现这些字符时不能让 Intent 里的 JSON 坏掉（图谱页会解析失败）。
     */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                // when (c) 的 c 是 Char：分支必须用字符字面量，不能拿 String 常量来比
                '"' -> sb.append(BS).append('"')
                '\\' -> sb.append(BS).append('\\')
                '\n' -> sb.append(BS).append('n')
                '\r' -> sb.append(BS).append('r')
                '\t' -> sb.append(BS).append('t')
                else -> if (c < ' ') sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val Q = "\""
    private const val COLON = "\":"
    private const val COMMA = "\","
    private const val BS = "\\"
    private const val NL = "\n"
    private const val CR = "\r"
    private const val TAB = "\t"

    // ---------------- 标题识别 ----------------

    /**
     * 标题层级。[level] 只用来区分同级 / 下级，具体数值不参与任何判断 ——
     * 中文资料的编号体系五花八门，硬编码一套层级只会漏判。
     */
    private class Heading(val level: Int, val number: String, val title: String)

    /**
     * 编号只留编号本身：去掉尾部的顿号、点号和空白。
     * 为什么不留标点：界面上写「出自 一、 节」很别扭；编号是给用户定位用的，不是排版的一部分。
     */
    private fun trimNumber(raw: String): String =
        raw.trim().trimEnd { it in TRIM_NUMBER_CHARS }

    private const val TRIM_NUMBER_CHARS = " 、.．:："

    // 编号前缀规则全部集中在这里：headingOf / numberedItem 都从这里取，免得两处规则漂移
    private val RE_CHAPTER = Regex("^第[一二三四五六七八九十百零0-9]{1,4}[章节篇部]\\s*(.{0,40})$")
    private val RE_PAREN_CN = Regex("^[(（]\\s*[一二三四五六七八九十]{1,3}\\s*[)）]\\s*(.{0,40})$")
    private val RE_CN = Regex("^[一二三四五六七八九十]{1,3}\\s*[、.．]\\s*(.{0,40})$")
    private val RE_DOTTED = Regex("^([0-9]{1,3}(?:[.][0-9]{1,3})+)[.、．]?\\s*(.{0,40})$")
    private val RE_ARABIC = Regex("^([0-9]{1,3})\\s*[、.．]\\s*(.{0,40})$")
    private val RE_ITEM = Regex("^([0-9]{1,2}|[①-⑳])\\s*[)）.、．]\\s*(.+)$")
    private val RE_CIRCLED = Regex("^([①-⑳])\\s*(.+)$")
    /**
     * 判断一行是不是标题，null = 不是标题。
     * 单看编号会把「1. 先洗手」这种编号步骤当成章节标题，所以还要看句末标点与长度。
     */
    private fun headingOf(text: String): Heading? {
        val s = text.trim()
        if (s.isEmpty() || s.length > NAME_MAX + 16) return null
        if (s.any { it in SENTENCE_ENDS }) return null

        RE_CHAPTER.find(s)?.let { m ->
            val title = m.groupValues[1].trim()
            return Heading(1, trimNumber(s.substringBefore(title)), title.ifEmpty { s })
        }
        RE_PAREN_CN.find(s)?.let { m ->
            val title = m.groupValues[1].trim()
            if (title.isEmpty()) return null
            return Heading(3, trimNumber(s.substringBefore(title)), title)
        }
        RE_CN.find(s)?.let { m ->
            val title = m.groupValues[1].trim()
            if (title.isEmpty()) return null
            return Heading(1, trimNumber(s.substringBefore(title)), title)
        }
        RE_DOTTED.find(s)?.let { m ->
            val title = m.groupValues[2].trim()
            if (title.isEmpty()) return null
            val depth = m.groupValues[1].count { it == '.' }
            return Heading(minOf(depth + 1, 3), m.groupValues[1], title)
        }
        RE_ARABIC.find(s)?.let { m ->
            val title = m.groupValues[2].trim()
            if (title.isEmpty() || title.length > NAME_MAX) return null
            if (title.contains(PAUSE_COMMA_CN) || title.contains(PAUSE_COMMA_EN)) return null
            return Heading(2, m.groupValues[1], title)
        }
        return null
    }

    private const val PAUSE_COMMA_CN = '，'
    private const val PAUSE_COMMA_EN = ','

    /** 标题行的章节名：优先原文标题文字，退而求其次用整行。 */
    private fun headingName(ln: Line, h: Heading): String {
        val n = clean(h.title).trim()
        return if (n.isNotEmpty()) trimTo(n, NAME_MAX) else trimTo(clean(ln.text), NAME_MAX)
    }

    private class Item(val name: String, val rest: String)

    /** 编号条目（列表项），与标题的区别是允许句子形态：它本来就是一个知识点。 */
    private fun numberedItem(text: String): Item? {
        val s = text.trim()
        RE_ITEM.find(s)?.let { m ->
            val rest = m.groupValues[2].trim()
            if (rest.isEmpty()) return null
            val name = nameOf(rest)
            return if (plausibleName(name)) Item(name, rest) else null
        }
        RE_CIRCLED.find(s)?.let { m ->
            val rest = m.groupValues[2].trim()
            if (rest.isEmpty()) return null
            val name = nameOf(rest)
            return if (plausibleName(name)) Item(name, rest) else null
        }
        return null
    }

    /** 从「名字：解释」里取名字；没有冒号就取整句（再交给 plausibleName 把关长度）。 */
    private fun nameOf(rest: String): String {
        val i = indexOfSeparator(rest)
        return if (i > 0) rest.substring(0, i).trim() else rest.trim()
    }
    // ---------------- 定义句识别 ----------------

    private class Definition(val name: String, val rest: String)

    // 长词必须先匹配：「是指」若在「是」之后，名字会变成「叶绿体指」
    private val DEF_KEYS = listOf("指的是", "定义为", "被称为", "是指", "是", "指")
    private val ENUM_KEYS = listOf("包括", "包含", "分为", "主要有", "分成")

    /**
     * 抽「X 是/指/定义为 ……」这种定义句的主语。
     * 抽不出合式的名字（太长、太短、回指代词、包含标点）就返回 null —— 宁缺毋滥。
     */
    private fun definitionOf(text: String): Definition? {
        val s = text.trim()
        if (s.length < 3) return null
        val idx = indexOfSeparator(s)
        if (idx > 0) {
            val name = s.substring(0, idx).trim()
            val rest = s.substring(idx + 1).trim()
            if (plausibleName(name) && rest.isNotEmpty()) return Definition(name, rest)
        }
        for (k in DEF_KEYS) {
            val i = s.indexOf(k)
            if (i <= 0) continue
            val name = s.substring(0, i).trim()
            if (!plausibleName(name)) continue
            // 单字键（是 / 指）要求左边是完整名词短语：至少两个字，否则容易切出半截词
            if (k.length <= 1 && name.length < 2) continue
            return Definition(name, s.substring(i + k.length).trim())
        }
        return null
    }
    /** 名字是否像个概念：长度合理、不含句读标点、不是回指代词、不是纯数字。 */
    private fun plausibleName(name: String): Boolean {
        val n = name.trim()
        if (n.length < 2 || n.length > NAME_MAX) return false
        if (n.any { it in NAME_BAD_CHARS }) return false
        if (n.first() in BAD_STARTS) return false
        if (n.all { it.isDigit() }) return false
        return true
    }

    private const val NAME_BAD_CHARS = "。！？；，、,.!?;"
    private const val BAD_STARTS = "它这那他她此"

    /**
     * 枚举句（细胞器包括线粒体、叶绿体和高尔基体）只取主语当知识点：
     * 把整句当名字就成了句子标题，卡片正面会很长且没法复习。
     */
    private fun enumerationOf(text: String): String? {
        val s = text.trim()
        for (k in ENUM_KEYS) {
            val i = s.indexOf(k)
            if (i <= 1) continue
            val name = s.substring(0, i).trim()
            if (plausibleName(name)) return name
        }
        return null
    }

    /** 找定义分隔符（全角或半角冒号）的位置；行首冒号说明是提示语，不算定义。 */
    private fun indexOfSeparator(s: String): Int {
        for (i in s.indices) {
            if (s[i] == SEP_FULL || s[i] == SEP_HALF) return if (i == 0) -1 else i
        }
        return -1
    }

    private val SEP_FULL = '：'
    private val SEP_HALF = ':'
    // ---------------- 卡片 ----------------

    /**
     * 由章节正文与知识点生成建议卡。
     * 正面 = 知识点名，背面 = 原文里那句解释（截到 BACK_BUDGET）。
     * 为什么背面用原文而不是改写：改写等于编，用户没法核对；原文才是可复习的锚点。
     * 没有正文的纯目录章节不出卡 —— 那种卡背面只能是空的，给用户添垃圾。
     */
    private fun buildCards(
        sections: List<Section>,
        points: List<Point>,
        sourceName: String,
    ): List<KnowledgeCard.KCData> {
        if (points.isEmpty()) return emptyList()
        val bodyOf = HashMap<String, String>()
        for (sec in sections) if (sec.body.isNotBlank()) bodyOf[sec.key] = sec.body
        val out = ArrayList<KnowledgeCard.KCData>()
        val seenFront = HashSet<String>()

        for (p in points) {
            if (out.size >= CARD_MAX) break
            val deck = trimTo(clean(p.section), DECK_MAX).ifEmpty { KnowledgeAiImport.DEFAULT_DECK }
            val front = trimTo(clean(p.name), KnowledgeAiImport.FRONT_MAX)
            val fromBody = bodyOf[p.sectionKey].orEmpty()
            // 背面优先用章节正文（定义句的另一半），没有再用摘录兜底
            val backRaw = if (fromBody.isNotBlank()) fromBody else p.origin
            val back = trimTo(clean(backRaw), minOf(KnowledgeCard.BACK_MAX, BACK_BUDGET))
            if (front.isEmpty() || back.isEmpty()) continue
            if (!seenFront.add(key(front))) continue
            val origin = if (sourceName.isBlank()) p.origin else sourceName + SEP_DOT + p.origin
            out.add(
                KnowledgeCard.KCData(
                    id = 0L,
                    deck = deck,
                    front = front,
                    back = back,
                    // KCData.source 存字符串码（KnowledgeCard.Source.code），不是枚举本身
                    source = KnowledgeCard.Source.IMPORT.code,
                    createdAt = 0L,
                    fsrs = Fsrs.Card(),
                    origin = trimTo(origin, ORIGIN_MAX),
                )
            )
        }
        return out
    }

    private const val DECK_MAX = 40
    private const val SEP_DOT = " · "
    /** 界面用的一句话结论。抽不出东西时也要有一句，否则用户不知道是失败了还是在加载。 */
    private fun summaryOf(
        sections: List<Section>,
        points: List<Point>,
        cards: List<KnowledgeCard.KCData>,
    ): String {
        if (sections.isEmpty() && points.isEmpty()) return SUMMARY_EMPTY
        val parts = ArrayList<String>()
        val real = sections.filter { it.title != WHOLE }
        if (real.isNotEmpty()) parts.add(real.size.toString() + " 个章节")
        if (points.isNotEmpty()) parts.add(points.size.toString() + " 个知识点")
        if (cards.isNotEmpty()) parts.add("建议 " + cards.size + " 张卡")
        val uniq = points.map { it.linkKey }.distinct().size
        val tail = if (uniq in 1 until points.size) "（去重后 " + uniq + " 个概念）" else ""
        return parts.joinToString(SEP_DOT) + tail
    }

    private const val SUMMARY_EMPTY = "这段资料里没找到可结构化的标题或定义句。"
    // ---------------- 工具 ----------------

    /** 预处理后的一行。[index] 是原文行号，[openerBold] / [quoted] 记录行首标记。 */
    private class Line(
        val index: Int,
        val text: String,
        val openerBold: Boolean,
        val quoted: Boolean,
    )

    /** 正文里的一句话及其来源行（卡片要指回原文）。 */
    private class Holder(val plain: String, val line: Line)

    private class Pre(val text: String, val openerBold: Boolean, val quoted: Boolean)

    /**
     * 剥掉行首 Markdown 标记（#、>、-、*、**），保留正文。
     * 加粗标记只剥行首：行内的加粗必须留在原文摘录里，否则用户拿摘录搜不到原文。
     */
    private fun stripMarkers(raw: String): Pre {
        var i = 0
        while (i < raw.length && isBlankChar(raw[i])) i++
        var quoted = false
        var bold = false
        while (i < raw.length) {
            val c = raw[i]
            if (c == QUOTE_MARK) { quoted = true; i++; continue }
            if (c == HEAD_MARK) { i++; continue }
            if (c == STAR && i + 1 < raw.length && raw[i + 1] == STAR) { bold = true; i += 2; continue }
            if (c == STAR || c == DASH) {
                if (i + 1 < raw.length && isBlankChar(raw[i + 1])) { i++; continue }
            }
            if (isBlankChar(c)) { i++; continue }
            break
        }
        return Pre(raw.substring(i).trimEnd(), bold, quoted)
    }

    /** 行首空白判定：半角空格 / 制表符 / 全角空格。 */
    private fun isBlankChar(c: Char): Boolean = c == ' ' || c == TAB_CHAR || c == IDEO_SPACE

    private const val TAB_CHAR = '\t'
    private const val IDEO_SPACE = '\u3000'
    private const val QUOTE_MARK = '>'
    private const val HEAD_MARK = '#'
    private const val STAR = '*'
    private const val DASH = '-'
    /** 去掉多余空白与控制字符，但不碰标点与加粗标记（摘录要能回搜原文）。 */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        var lastBlank = false
        for (c in text) {
            if (isBlankChar(c) || c == NEWLINE || c == CARRIAGE) {
                if (!lastBlank && sb.isNotEmpty()) sb.append(' ')
                lastBlank = true
            } else {
                sb.append(c)
                lastBlank = false
            }
        }
        return sb.toString().trim()
    }

    private const val NEWLINE = '\n'
    private const val CARRIAGE = '\r'
    private const val SENTENCE_ENDS = "。！？!?…；;"

    /** 按句末标点切句，保留标点。切不出东西就原样返回一行。 */
    private fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c in SENTENCE_ENDS) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) out.add(s)
                sb.setLength(0)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return if (out.isEmpty()) listOf(text.trim()) else out
    }
    /**
     * 在原文行里找回干净文本对应的原话片段。
     * 剥 Markdown 标记后文字变了，但用户要拿摘录去原文里搜，所以摘录必须取原文那一段。
     * 对不上就退回 cleanText —— 内容依然可信，绝不返回空串让卡片没有出处。
     */
    fun literalSpan(rawLine: String, cleanText: String): String {
        val target = cleanText.trim()
        if (target.isEmpty()) return ""
        if (rawLine.contains(target)) return target
        var ri = 0
        var ti = 0
        var start = -1
        while (ri < rawLine.length && ti < target.length) {
            val rc = rawLine[ri]
            if (isBlankChar(rc) && isBlankChar(target[ti])) { ri++; ti++; continue }
            if (rc == target[ti]) {
                if (ti == 0) start = ri
                ri++
                ti++
                if (ti == target.length && start >= 0) return rawLine.substring(start, ri).trim()
            } else {
                if (rc in MARKER_CHARS || isBlankChar(rc)) {
                    ri++
                } else {
                    start = -1
                    ti = 0
                    ri++
                }
            }
        }
        return if (rawLine.contains(target)) target else cleanText.trim()
    }

    private const val MARKER_CHARS = "*_#>`~|"

    /** 截断到 max 个字符，被截了就补省略号；max 为 0 返回空串。 */
    fun trimTo(text: String, max: Int): String {
        if (max <= 0) return ""
        val t = text.trim()
        return if (t.length <= max) t else t.substring(0, max) + ELLIPSIS
    }

    private const val ELLIPSIS = "…"
    /** 概念键：去空白与标点后小写。跨章节判同一个概念用它，不用人类看的名字。 */
    private fun key(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * 添加一个知识点：截长、同章节同名去重、摘录映射回原文。
     * 同章节同名只留第一条（一份讲义里「小结」可能出现三次，但那是同一节的同一件事）。
     */
    private fun addPoint(
        points: MutableList<Point>,
        name0: String,
        sectionKey: String,
        ln: Line,
        section: String = sectionKey,
    ) {
        val name = trimTo(clean(name0), NAME_MAX)
        if (name.isEmpty()) return
        // 去重按章节键而不是章节名：两章都可能叫光合作用，那正是该各留一条的情况
        if (points.any { it.sectionKey == sectionKey && it.linkKey == key(name) }) return
        val origin = trimTo(literalSpan(ln.text, ln.text), ORIGIN_MAX)
        if (origin.isEmpty()) return
        points.add(
            Point(
                name = name,
                section = section,
                origin = origin,
                line = ln.index,
                sectionKey = sectionKey,
            )
        )
    }

    /** 供测试与界面复用：直接给出某段文字的知识点链接键。 */
    fun linkKeyOf(name: String): String = key(name)
}