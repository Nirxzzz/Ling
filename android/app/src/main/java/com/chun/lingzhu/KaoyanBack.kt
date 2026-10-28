package com.chun.lingzhu

/**
 * 「书页式」词库背面的结构化解析（v1.81）。
 *
 * 新增的三个内置考研词库，卡片背面不再是「一行释义」，而是一整页书：
 * ```
 * ['dʒʌstɪs] n.
 * 【助记】 just(公正的)+ ice(名词后缀)→公正
 * 【词义】① 公平,公正,合理(u): All men should be treated with justice.人人都应当受到公平的待遇。
 * ② 审判,司法(制度),法律制裁(u): The courts ensure that justice is achieved.法庭确保审判公正。
 * 【词组】① in justice to 为了对······公平起见
 * · 由于缺乏资金，这位科学家放弃了他的研究工作。
 * ```
 * 纯 View 排版要能给标签配底色、给序号行加缩进、把换页处撕断的残句淡化 —— 前提是先把这一页
 * 拆成结构化的 [Block] 列表。本文件**只做纯逻辑，不 import android.\***，JVM 单测直接覆盖。
 *
 * 标签用 `tag` 表示：`tag == ""` 有两种含义 —— 首行音标/词性行（UI 加粗），或者没有标签的散行；
 * 靠 [Block.frag] 再区分「换页残句」。
 */
data class Block(val tag: String, val text: String, val frag: Boolean = false)

object KaoyanBack {
    // ---- 背面块的错峰淡入节奏（v1.81）----
    // 卡片本体先弹进来（AnimGlue 的 popIn，约 180ms），等它落定一点再让背面的块**依次**冒出来：
    // 每块从下方 STAGGER_SHIFT_DP 处淡入 + 弹簧上浮。一屏大概看得到 5~7 块，所以只在前面
    // STAGGER_MAX_BLOCKS 块上错峰，后面的（多半在滚动区外）一起出来 —— 不让长条目等上一秒。
    const val STAGGER_START_MS = 60L
    const val STAGGER_STEP_MS = 16L
    const val STAGGER_MAX_BLOCKS = 8
    const val STAGGER_SHIFT_DP = 6f

    /** 第 [i] 个背面块什么时候开始淡入（毫秒）。纯函数，单测直接盯。 */
    fun staggerDelayMs(i: Int): Long =
        STAGGER_START_MS + i.coerceIn(0, STAGGER_MAX_BLOCKS) * STAGGER_STEP_MS

    /**
     * 把「挤在一行」的背面正文拆成多行、每行一个可读单位（v1.82）。纯函数、幂等
     * （`wrap(wrap(x)) == wrap(x)`），空串/全空白返回空串。
     *
     * 只在**行内**这些边界插 `\n`：
     *  1. 序号前：① U+2460~⑩ U+2469、❶ U+2776~❿ U+277F（同一段里的 ①②③ 各占一行）；
     *  2. 标签前：`【`（出现于行中间时）；
     *  3. 英文/中文交界：一个位置前一个是 ASCII 字母/数字/标点（[ASCII_SIDE]）、后一个是汉字，
     *     且再前一个字符是英文字母或数字。这样 `justice.人人都` / `Gump.汤姆` 的句点才切，
     *     而 `公平,公正` 里的逗号、`司法(制度),法律` 里「)、」连着的逗号都不会被误切 ——
     *     英文例句与中文译文因此各占一行。
     *
     * 例外：`【…】` 标签**内部**整体不切（如 `【2019年考研阅读】` 保持完整）；紧跟 `】` 的实心
     * 记号 `❶` 属于「标签 + 记号 + 词性」，跟标签走（`【词性】❶n.` 一行）。收尾逐行 trim、
     * 丢掉空行、以 `\n` 相连 —— 不产出空行。
     */
    fun wrap(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length + 16)
        var inTag = false
        for (i in text.indices) {
            val c = text[i]
            when {
                c == '\u3010' -> {                 // 规则 2：【 前断行
                    if (i > 0) sb.append('\n')
                    inTag = true
                    sb.append(c)
                }
                c == '\u3011' -> { inTag = false; sb.append(c) }   // 】：标签内容不切
                i > 0 && !inTag && needsBreak(text, i) -> { sb.append('\n'); sb.append(c) }
                else -> sb.append(c)
            }
        }
        return sb.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** [i] 处（i>0，且不在 `【…】` 标签内部）是否要插换行 */
    private fun needsBreak(text: String, i: Int): Boolean {
        val cur = text[i]
        // 规则 1：序号前断行。但紧跟【…】的实心记号 ❶ 是「标签+记号+词性」的一部分，别切（规则 4）
        if (cur in CIRCLES || cur in CLOSED_CIRCLES) {
            if (cur in CLOSED_CIRCLES && text.substring(0, i).trimEnd().endsWith("\u3011")) return false
            return true
        }
        // 规则 3：英文 → 中文交界。左侧是英文侧的标点/字母，且「锚点」（再前一个字符，i==1 时取左侧本身）
        // 是英文字母或数字 —— 这样「justice.人」的句点切，「司法(制度),法律」里那个中文逗号（锚点是 `)`）不切。
        if (cur !in HAN) return false
        if (text[i - 1] !in ASCII_SIDE) return false
        val anchor = if (i >= 2) text[i - 2] else text[i - 1]
        return anchor in 'a'..'z' || anchor in 'A'..'Z' || anchor in '0'..'9'
    }

    // ---------------- 正文分行（v1.84）：模仿 nirx 的「严格下标对齐切分」----------------

    /**
     * 正文里的一行。[indent] = 悬挂缩进层级：0 顶格；1 = 属于上一个 ①②③ / ❶ 序号项的续行。
     *
     * 挂在 [KaoyanBack] 下（`KaoyanBack.Line`）—— 与 [Block] 一样是纯数据，不 import android。
     */
    data class Line(val text: String, val indent: Int = 0)

    /** 软断行上限：一行超过这么多字符就回扫软断点（见 [layout] 的 R5）。 */
    const val MAX_LINE = 56

    /**
     * 按 R1~R6 把一块正文切成若干行（纯函数、幂等）。**硬不变量**：字符不增不减 ——
     * `layout(text).joinToString("") { it.text } == text`（全空白输入同样原样拼回，不靠 trim 丢字符）。
     *
     * 与 [wrap] 的关系：wrap 是「往文本里**插 `\n`**」的旧实现，仍被 [parse] 使用、行为一字不动地保留；
     * layout 只**按下标切**、不往文本里加/减任何字符，并补上 wrap 缺的那几条：
     *  - **R1 序号项**：`①②③…`（U+2460~U+2469）/`❶❷…`（U+2776~U+277F）前断行；从该项到**下一个序号 /
     *    下一个 `【tag】` / 空行**之间的续行 `indent = 1`（悬挂缩进归属，修「续行顶格」）；
     *  - **R2 `【tag】`**：`【` 位于行首、或前一非空白字符是句末标点（`。！？；!?;.` 或 `】`）、
     *    或**标签名是已知块标签**（词义/助记/词组…）时才断；否则（内嵌，如「详见【注】」）不断；
     *  - **R3 中↔英双向断行**：汉字→ASCII 与 ASCII→汉字都断；ASCII→汉字时**跳过连续的 ASCII 标点**
     *    找锚点（`Gump.)汤姆` 能断），英文句末标点后**可隔一个空格**（`structured. 这篇` 能断，但
     *    `in justice to 为了…` 这种「短语 + 释义」不断）；该标点若是**词性/语法缩写**的句点
     *    （`n. 公正`/`adj. 公正的`/`vt. 放弃`），那是「词性 + 释义」的拼接、不当句末（见 [endsWithPosAbbrev]）；
     *    汉字→ASCII 与 ASCII→汉字两个方向都跳过「嵌在汉语里的记号」——单纯的**纯数字 / 单个字母**
     *    （`答案B。`/`第3页`/`20世纪`），不当交界（见 [isInlineToken]）；`公平,公正`（中文+中文标点+中文）不许断；
     *  - **R4 行首禁则**：断点之后若是 `。，、；：！？）】」』…—` 之类，则把断点挪到最近的合法位置；
     *  - **R5 超长行软断点回扫**：超过 [MAX_LINE] 的行回扫 ` `、`、`、`·`、`—`、`～`、`/` 断开；
     *    实在没有软断点就硬切在 [MAX_LINE]（左右少许避让禁则字符），**绝不丢字符**；
     *  - **R6**：`【…】` 内部整体不切（含显式换行也不撕开标签）。
     *
     * 全部切点只由下标决定；不做任何 trim，行文本原样保留（含行尾空白/换行符），以保证不变量成立。
     */
    fun layout(text: String): List<Line> {
        val n = text.length

        // 位置 i 是否落在 【…】 内部：标签内部整体不切（开放式【缺少闭合时，其后全部视为标签内）
        val inTag = BooleanArray(n)
        var open = false
        for (i in 0 until n) {
            when (text[i]) {
                '\u3010' -> { inTag[i] = false; open = true }
                '\u3011' -> { inTag[i] = false; open = false }
                else -> inTag[i] = open
            }
        }

        // 候选断点：cand[i] = 「在 i 之前断行」
        val cand = BooleanArray(n)
        for (i in 1 until n) {
            if (inTag[i]) continue
            val c = text[i]
            if (text[i - 1] == '\n') { cand[i] = true; continue }        // 已有的显式换行，原样保留
            if (c in CIRCLES || c in CLOSED_CIRCLES) {                   // R1 序号
                // 紧跟【…】的实心记号 ❶ 是「标签+记号+词性」的一部分，不当作新序号（回归防线）
                if (c in CLOSED_CIRCLES && text.substring(0, i).trimEnd().endsWith("\u3011")) continue
                cand[i] = true; continue
            }
            if (c == '\u3010') {                                         // R2 【tag】
                var j = i - 1
                while (j >= 0 && text[j].isWhitespace()) j--
                val afterSentence = j >= 0 && text[j] in SENTENCE_END
                val tn = tagNameAt(text, i)
                if (afterSentence || (tn != null && tn in BLOCK_TAGS)) cand[i] = true
                continue
            }
            if (c in HAN) {                                             // R3a：英文侧（跳过连续 ASCII 标点/空白）→ 汉字
                var p = i - 1
                // 源里英文例句与中文译文之间常夹一个空格（`…structured. 这篇文章…`）。只跨空格、不跨换行，
                // 让「句末标点 + 空格 + 汉字」也能断，英文例句与译文各占一行。
                while (p >= 0 && text[p] == ' ') p--
                if (p >= 0 && text[p] in ASCII_SIDE) {
                    // 汉字左侧紧贴的 ASCII 词若是「嵌在汉语里的记号」（纯数字 / 单个字母，见 [isInlineToken]），
                    // 那是汉语里的记号（`第3页` / `20世纪` / `维生素C含量`），不当交界，不在此断。判据取**整段**
                    // alnum 的起点（而不是紧邻汉字的那一位），才能把多位数字的前半（`123` 的首位 `1`）一起看进去。
                    val inlineMark = isAsciiAlnum(text[p]) && isInlineToken(text, asciiTokenStart(text, p))
                    // 跨了空格时，只有英文侧以句子收束标点结尾才算「例句 → 译文」的交界；否则
                    // `① in justice to 为了对······公平起见` 这种「短语 + 释义」会被拆开（回归防线）。
                    // 收束标点若是**词性/语法缩写**的句点（`n. 公正` / `adj. 公正的` / `vt. 放弃`），那是
                    // 「词性 + 释义」的拼接、不是英文例句的句末 —— 也不在这里断（见 [endsWithPosAbbrev]）。
                    if (!inlineMark && (p == i - 1 || text[p] in SENTENCE_BREAK) && !endsWithPosAbbrev(text, p)) {
                        var j = p
                        var hit = false
                        while (j >= 0 && text[j] in ASCII_SIDE) {
                            if (isAsciiAlnum(text[j])) { hit = true; break }
                            j--
                        }
                        if (hit) cand[i] = true
                    }
                }
                continue
            }
            if (isAsciiAlnum(c)) {                                       // R3b：汉字（可隔句读）→ ASCII 字母/数字
                var j = i - 1
                while (j >= 0 && text[j].isWhitespace()) j--
                var hit = false
                if (j >= 0) {
                    if (text[j] in HAN) {
                        hit = true
                    } else if (text[j] in CH_PUNCT) {
                        var k = j - 1
                        while (k >= 0 && text[k].isWhitespace()) k--
                        if (k >= 0 && text[k] in HAN) hit = true
                    }
                }
                // 汉字 → 内嵌记号（`答案B。` / `第3页` / `20世纪` / `维生素C`）不是中英交界：那是汉语里的
                // 单个字母或纯数字，切开只会把「答案B。」读成「答案 ⏎ B。」。真英文词才断（见 [isInlineToken]）。
                if (hit && !isInlineToken(text, i)) cand[i] = true
            }
        }

        // R4：断点之后若是行首禁则字符，就挪到最近的合法断点（先往后、再往前，找不到就放弃该断点）
        val candList = (1 until n).filter { cand[it] }
        val breaks = sortedSetOf<Int>()
        for (i in candList) {
            if (text[i] !in FORBIDDEN_START) { breaks.add(i); continue }
            val next = candList.firstOrNull { it > i && text[it] !in FORBIDDEN_START }
            if (next != null) { breaks.add(next); continue }
            val prev = candList.lastOrNull { it < i && text[it] !in FORBIDDEN_START }
            if (prev != null) breaks.add(prev)
        }

        // 切行：只做下标切片，不 trim、不丢字符（硬不变量）
        val raw = ArrayList<Line>()
        var start = 0
        for (b in breaks) {
            raw.add(Line(text.substring(start, b)))
            start = b
        }
        raw.add(Line(text.substring(start)))

        // R1 悬挂缩进归属：序号行之后、到下一个序号 / 【tag】 / 空行之前的续行 indent = 1
        var hang = false
        val indented = ArrayList<Line>(raw.size)
        for (l in raw) {
            val head = l.text.trimStart()
            indented.add(
                when {
                    l.text.isBlank() -> { hang = false; Line(l.text, 0) }
                    head.first() in CIRCLES || head.first() in CLOSED_CIRCLES -> { hang = true; Line(l.text, 0) }
                    head.startsWith("\u3010") -> { hang = false; Line(l.text, 0) }
                    else -> Line(l.text, if (hang) 1 else 0)
                },
            )
        }

        // R5：超长行软断点回扫（没有软断点就硬切在 MAX_LINE，仍旧不许丢字符）
        val out = ArrayList<Line>(indented.size)
        for (l in indented) out.addAll(softBreak(l))
        return out
    }

    // ---- 行宽预算（v1.85.9）：按显示宽度算，不再按字符数 ----
    // 英文例句 60~80 个字符本来显示宽度只相当于半行中文，按字符数算就会超「行宽」被拦腰切 ——
    // 用户报的「莫名的分行打断」大多数是这一个根。汉字/全角记 2 个单位、其余记 1，
    // 预算 = MAX_LINE * 2：中文行宽与旧版一字不差，ASCII 容量翻倍。
    private fun charW(c: Char): Int = if (c in HAN || c in FULLWIDTH) 2 else 1
    private const val BUDGET = MAX_LINE * 2

    private fun widthOf(s: String): Int {
        var w = 0
        for (c in s) w += charW(c)
        return w
    }

    /** 全角区（含全角标点/全角字母，CJK 全角空格另算）—— 记双宽 */
    private val FULLWIDTH = '\uFF01'..'\uFF60'

    /** 最大的下标 j（含）使前缀 s[0..j] 的显示宽度 <= [budget]；没有则 -1 */
    private fun lastFitIndex(s: String, budget: Int): Int {
        var w = 0
        for (i in s.indices) {
            w += charW(s[i])
            if (w > budget) return i - 1
        }
        return s.length - 1
    }

    /** R5：把一行超长文本按软断点（或硬上限）再切成若干行；子行沿用父行的 [Line.indent]。 */
    private fun softBreak(line: Line): List<Line> {
        if (widthOf(line.text) <= BUDGET) return listOf(line)
        val out = ArrayList<Line>()
        var s = line.text
        while (widthOf(s) > BUDGET) {
            // v1.85.9：最先问「句末边界」——一行里塞了两句话时，断在第一句结尾，
            // 不再把第二句从句中间拦腰切开（见 [sentenceCut]）
            var cut = sentenceCut(s)
            // v1.85：再问「中英交界」——断在交界上，整句英文例句才能完整留在下一行（见 [semanticCut]）
            if (cut < 0) cut = semanticCut(s)
            // v1.85.10：再问「子句边界」——整句超预算又没有句号时，断在 `, ` / `; ` 后，
            // 别让空格回扫把短语从中间撕开（`for ⏎ the short term` → `performance, ⏎ not just for…`）
            if (cut < 0) cut = clauseCut(s)
            if (cut < 0) {
                // 退回：<= 行宽预算内最近的一个软断点，且断点之后不是禁则字符（断点留在上一行行尾）
                var j = lastFitIndex(s, BUDGET - 1)
                while (j >= 1) {
                    if (s[j] in SOFT_BREAK && s[j + 1] !in FORBIDDEN_START) { cut = j + 1; break }
                    j--
                }
            }
            if (cut < 0) {
                // 没有软断点：硬切在行宽预算；若切点后是禁则字符就右移一点（放宽 14 个汉字宽）
                cut = lastFitIndex(s, BUDGET) + 1
                val cap = (lastFitIndex(s, BUDGET + 28) + 1).coerceAtLeast(cut)
                while (cut < s.length && s[cut] in FORBIDDEN_START && cut < cap) cut++
                if (cut >= s.length) break
            }
            out.add(Line(s.substring(0, cut), line.indent))
            s = s.substring(cut)
        }
        out.add(Line(s, line.indent))
        return out
    }

    /**
     * R5 首选断点（v1.85）：「中文义项 → 英文例句」的**交界**，让整句英文完整留在下一行。
     *
     * 真机投诉原文：英文例句 `Many universities have institutes of education.` 被从中间切开，
     * `education.` 成了孤行 —— 根因是这里原来只回扫**空格**，而英文例句内部全是空格。
     * 空格是「英文内部的合法断点」，不是语义边界：切在词与词之间会把一句完整的话读成两截。
     *
     * 定位方式（全部只按下标，纯函数）：
     *  1. 先记下 MAX_LINE 之前**最后一个汉字**的位置：中文语境到此为止，再往后就是英文；
     *  2. 越过黏在义项和例句之间的「胶水」—— 词性缩写 `(u)`、冒号、空白（都可能在 MAX_LINE 内）；
     *  3. 胶水之后的第一个 ASCII 字母/数字即断点：那里正是英文例句的开头。
     *
     * 于是 `…研究院(u) : Many universities have institutes of education.` 断成
     * `…研究院(u) : ` + `Many universities have institutes of education.` —— 英文例句完整占一行，
     * 中文译文仍由 R3a 单独断行。字符只被切成两段，**一个都不丢**。
     *
     * 返回 -1 = 这行没有可用的语义边界 → 由 [softBreak] 退回原来的空格回扫 / 硬切：
     *  纯英文长句（没有汉字）、纯中文长句、胶水还没结束就撞上 MAX_LINE（词性缩写特别长）三种情形。
     *
     * 还有一条**放弃条件**：只有「英文那半截自己塞得进一行」才值得断在这里。否则断出一个三五字
     * 的残行、下一行照样还得再切，纯粹是把 [MAX_LINE] 的行宽优势丢掉 —— 不如让空格回扫去填满。
     * 所以余下部分超过 [MAX_LINE] 时返回 -1（用户看的是整句例句可读，不是行宽对齐）。
     */
    private fun semanticCut(s: String): Int {
        if (widthOf(s) <= BUDGET) return -1
        // 行宽预算内最后一个汉字：中文语境到此为止，再往后就是英文
        var han = -1
        var w = 0
        for (i in s.indices) {
            w += charW(s[i])
            if (w > BUDGET) break
            if (s[i] in HAN) han = i
        }
        if (han < 0) return -1                       // 整行没有汉字 → 没有「中英交界」可言
        var k = han + 1
        while (k < s.length && widthOf(s.substring(0, k)) <= BUDGET) {
            val c = s[k]
            when {
                // 词性/单复数缩写 (u) (c) (pl.) —— 属于中文义项的修饰，整体跳过
                c == '(' -> {
                    val e = s.indexOf(')', k + 1)
                    if (e < 0 || widthOf(s.substring(0, e + 1)) > BUDGET) return -1
                    k = e + 1
                }
                // 胶水里的 ASCII 标点/空白；字母数字 = 英文例句真开始了
                isAsciiAlnum(c) -> {
                    // 整句英文自己都塞不下一行 → 保持「填满行宽」，不切这个残行
                    if (widthOf(s.substring(k)) > BUDGET) return -1
                    return k
                }
                c in ASCII_SIDE || c.isWhitespace() -> k++
                else -> return -1                     // 又是别的内容 → 这段没有英文例句可保
            }
        }
        return -1
    }

    private fun isAsciiAlnum(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /**
     * [i] 处的 ASCII 字母/数字是不是「嵌在汉语里的记号」，而不是英文词的开头（v1.85.13）。
     *
     * 判据（纯下标）：从 i 起取连续 ASCII 字母/数字（允许后面夹一个空格，源里 `2021 年` 这种 OCR 空格很常见），
     * 要求紧接着是汉字或中文标点，并且这段「只有纯数字」或「恰好一个字符」。于是
     * `答案B。` / `第3页` / `20世纪` / `和A级` 不当作中英交界（不会被 read 成「答案 ⏎ B。」），
     * 而 `待遇。The courts`（多字母）、`。A pyramid`（单个 `A` 后仍跟别的英文词）照常断行。
     *
     * R3b（汉字→ASCII）在记号**起点**处调用；R3a（ASCII→汉字）用 [asciiTokenStart] 找到整段 alnum 的起点后调用，
     * 两个方向口径一致 —— 多位数字的「前半」不会因为只看紧邻汉字的那一位而漏判。
     */
    private fun isInlineToken(text: String, i: Int): Boolean {
        var j = i
        while (j < text.length && isAsciiAlnum(text[j])) j++
        var k = j
        while (k < text.length && text[k] == ' ') k++
        if (k >= text.length) return false
        val nxt = text[k]
        if (nxt !in HAN && nxt !in CJK_PUNCT) return false
        var hasLetter = false
        for (t in i until j) if (text[t] in 'a'..'z' || text[t] in 'A'..'Z') hasLetter = true
        return !hasLetter || j - i == 1
    }

    /**
     * 以 [p] 结尾的那段连续 ASCII 字母/数字的起始下标。R3a 要拿**整段** alnum（`123` 的 `1`、`20` 的 `2`）
     * 交给 [isInlineToken]，而不是只看紧邻汉字的那一位 —— 否则多位数字/字母数字混排的「前半」会被漏判。
     */
    private fun asciiTokenStart(text: String, p: Int): Int {
        var s = p
        while (s - 1 >= 0 && isAsciiAlnum(text[s - 1])) s--
        return s
    }

    /**
     * 已知的词性 / 语法缩写（仅 [endsWithPosAbbrev] 使用，按实际词库惯例收词）。
     * **刻意不复用 [POS_ONLY] 的 `[a-z]{1,5}\.`**：那是「整行只有词性」的宽松口径，会把 `works.` /
     * `sun.` 这类恰好 1~5 个字母的普通句末词也当成缩写、吞掉真英文例句的句末断行。
     */
    private val POS_ABBREVS = arrayOf(
        "n", "v", "vt", "vi", "adj", "adv", "pron", "prep", "conj",
        "int", "interj", "num", "art", "aux", "pl", "sing",
    )

    /**
     * [p] 处的英文句点是不是某个**词性/语法缩写**的收尾（`n.` / `adj.` / `vt.` / `v./n.` …）。
     *
     * R3a 会把「英文句末标点（可隔一个空格）+ 汉字」当成例句 → 译文交界而断行；但 `n. 公正` / `adj.
     * 公正的` 里的 `.` 是词性与释义之间的拼接，不是句末 —— 断在那里会把 2000+ 处「词性 + 释义」拆开。
     *
     * 判据：取紧贴句点前的**完整**英文词（含大写首字母，避免把 `Gump` 误截成 `ump`），长度对得上再与
     * [POS_ABBREVS] 逐字做**区域比较**（[String.regionMatches]，不分配子串、不拼接 `词 + "."`）——
     * 弹卡是热路径，避开 `substring + concat` 的临时对象。
     */
    private fun endsWithPosAbbrev(text: String, p: Int): Boolean {
        if (p <= 0 || text[p] != '.') return false
        var j = p - 1
        while (j >= 0 && (text[j] in 'a'..'z' || text[j] in 'A'..'Z')) j--
        if (j == p - 1) return false                       // 句点前没有字母 → 不是缩写
        val start = j + 1
        val len = p - start
        return POS_ABBREVS.any { it.length == len && text.regionMatches(start, it, 0, len, ignoreCase = true) }
    }

    /**
     * R5 首选断点之「句末边界」（v1.85.9）：MAX_LINE 之前**最后一个句末标点 + 空白**之后断开。
     *
     * 真机投诉：一行里塞了两句英文例句（`… people poured into the streets. After the meeting…`），
     * 超长回扫切在句子中间 —— `poured ⏎ After the meeting`，一句完整的话被拦腰读成两截。
     * 句末标点后跟空白、且下一句开头是大写字母 / 汉字 / 序号时，那里就是天然的断句位置。
     *
     * 两条防误切（纯下标判断）：
     *  - 切点前至少 [MIN_CUT_BEFORE]、切点后至少 [MIN_CUT_AFTER] 个字符 —— 不产出残行，
     *    也不把 `etc. and` 这类「缩写 + 小写续文」当句界（下一句开头必须「像一句话的开头」）；
     *  - 只认 `句末标点 + 空白`：`0.5`、`e.g.` 后面没空格，天然不会命中。
     *
     * 返回 -1 = 这行没有可用的句末边界 → 由 [softBreak] 退回 [semanticCut] / 空格回扫。
     */
    private fun sentenceCut(s: String): Int {
        if (widthOf(s) <= BUDGET) return -1
        var best = -1
        var w = 0
        var i = 0
        // 搜索窗放宽到 BUDGET + SLACK：句界刚好在行宽外几步时，宁可行稍长也不断在句中
        while (i < s.length) {
            w += charW(s[i])
            if (w > BUDGET + SENT_SLACK * 2) break
            i++
            // 断点候选：i 之前那个字符是句末标点、当前位置是空白
            if (i >= 2 && s[i - 1] in SENTENCE_BREAK && i < s.length && s[i] == ' ') {
                var k = i + 1
                while (k < s.length && s[k] == ' ') k++
                if (k < s.length && startsLikeSentence(s[k])) {
                    val before = widthOf(s.substring(0, k))
                    if (before >= MIN_CUT_BEFORE && widthOf(s) - before >= MIN_CUT_AFTER) best = k
                }
            }
        }
        return best
    }

    /** 句末断点标点（半角 + 全角） */
    private const val SENTENCE_BREAK = ".!?。！？"
    /** 句界搜索窗比行宽放宽多少：句界在行宽外这么远以内时优先保句子完整 */
    private const val SENT_SLACK = 24
    /** 切点前至少这么多宽度单位（约 8 个汉字 / 16 个英文字符，不产出残行） */
    private const val MIN_CUT_BEFORE = 16
    /** 切点后至少这么多宽度单位（下一句别只剩两三个字） */
    private const val MIN_CUT_AFTER = 12

    /** 「像一句话的开头」：大写英文字母、汉字、序号记号（①❶）、引号、反义破折号 */
    private fun startsLikeSentence(c: Char): Boolean =
        c in 'A'..'Z' || c in HAN || c in CIRCLES || c in CLOSED_CIRCLES || c == '“' || c == '(' || c == '（'

    /**
     * R5 次选断点之「子句边界」（v1.85.10）：预算内**最后一个 ASCII `, ` / `; `** 之后断开。
     * 整句超预算、句内又没有句号边界时，空格回扫会把短语撕开（`not just for ⏎ the short term`）；
     * 断在逗号/分号后面是子句的自然缝。纯下标判断，字符一个不丢。
     */
    private fun clauseCut(s: String): Int {
        if (widthOf(s) <= BUDGET) return -1
        var best = -1
        var w = 0
        var i = 0
        while (i < s.length) {
            w += charW(s[i])
            if (w > BUDGET) break
            i++
            if (i >= 2 && s[i - 1] in ",;" && i < s.length && s[i] == ' ') {
                var k = i + 1
                while (k < s.length && s[k] == ' ') k++
                if (k < s.length) {
                    val before = widthOf(s.substring(0, k))
                    if (before >= 12 && widthOf(s) - before >= 8) best = k
                }
            }
        }
        return best
    }


    /** `【` 起、到最近的 `】` 之间的标签名（去空白）；没有闭合（不平衡）时返回 null */
    private fun tagNameAt(text: String, i: Int): String? {
        val end = text.indexOf('\u3011', i + 1)
        return if (end < 0) null else text.substring(i + 1, end).trim()
    }

    /** R2：句末标点（含 `】`）—— `【` 紧随其后才断 */
    private const val SENTENCE_END = "\u3002\uFF01\uFF1F\uFF1B!?;.\u3011"

    /** R3b：中文句读 —— 「汉字 + 句读 + ASCII 字母」也算中→英交界 */
    private const val CH_PUNCT = "\u3002\uFF01\uFF1F\uFF1B\uFF1A\uFF0C\u3001"

    /** 中文侧收尾标点 —— 单个字母/纯数字紧跟它们时是「嵌在中文里的记号」，不当中英交界（见 [isInlineToken]） */
    private const val CJK_PUNCT =
        "\u3002\uFF0C\u3001\uFF1B\uFF1A\uFF01\uFF1F\uFF09\u3011\u300D\u300F\u2026\u2014"

    /** R4：不许出现在断点之后（行首）的收尾标点 */
    private const val FORBIDDEN_START = CJK_PUNCT + ",.;:!?)]"

    /** R5：软断点（回扫时优先在这些字符**之后**断开）—— 模仿 nirx 的 SOFT_BREAK 表 */
    private const val SOFT_BREAK = "\u0020\u3001\u00B7\u2014\uFF5E\u002F\uFF0C\uFF1B\u3002"

    /** R2：这些是「块标签」（【词义】【助记】…），行内遇到也断；其余【…】视作内嵌，不断。 */
    private val BLOCK_TAGS: Set<String> by lazy { (BARE_LABELS + EXTRA_BLOCK_TAGS).toSet() }

    /** 少数不在 [BARE_LABELS] 里、但确实当块标签用的（词性行 / 音标 等） */
    private val EXTRA_BLOCK_TAGS = listOf(
        "词性", "音标", "释义", "词态", "词形", "变形", "时态", "复数", "比较级", "最高级",
    )

    /** 圆形序号 ① U+2460 ~ ⑩ U+2469（源里用它续排上一块的义项/词组） */
    private val CIRCLES = '\u2460'..'\u2469'

    /** 实心圆形序号 ❶ U+2776 ~ ❿ U+277F（和 ① 一样「序号前断行」，但紧跟【…】的那个是记号，见 wrap） */
    private val CLOSED_CIRCLES = '\u2776'..'\u277F'

    /** 汉字（CJK 统一表意文字）——英文→中文交界判定的右侧 */
    private val HAN = '\u4E00'..'\u9FFF'

    /** 英文侧字符：ASCII 字母/数字 + 常见句读、引号、右括号（英文→中文交界判定的左侧） */
    private const val ASCII_SIDE =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,!?;:'\")]"

    /** 行首「淡化残句」记号：中黑点（原书就用它）/ 项目符号 / 假名中点等排版变体都认 */
    private val FRAG_MARKS = charArrayOf('\u00B7', '\u2022', '\u2219', '\u30FB')

    /** 方括号标签：绝大多数标签都写成【词义】这种 */
    private val TAG_BRACKET = Regex("^【([^】]+)】\\s*(.*)$")

    /** 整行只有词性缩写（n. / vt. / adj. / v./n. …），没有别的字 */
    private val POS_ONLY = Regex("^(?:[a-z]{1,5}\\.[\\s/、&]*)+$")

    /** 少数标签源里不带方括号（例：「辨析 abandon, desert, discard, forsake」）。按长度倒序，别让「同义」抢了「同义词」 */
    private val BARE_LABELS = listOf(
        "真题例句", "反义词", "同义词", "长难句",
        "词义", "助记", "词组", "派生", "辨析", "反义", "同义", "近义",
        "例句", "联想", "考点", "搭配", "用法", "词根", "词源", "扩展", "构词", "记忆", "短语", "真题",
    ).sortedByDescending { it.length }

    /**
     * 这张背面要不要走「富排版」：含 `【` 标签，或者首行是音标 / 词性行。
     * 普通词库的一行释义（「v. 放弃；抛弃」）不含方括号、首行也不是纯音标 → false，行为保持不变。
     */
    fun isRich(back: String): Boolean {
        if (back.isBlank()) return false
        if (back.contains('【')) return true
        val first = back.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return isPhonetic(first)
    }

    /**
     * 这块是不是「真题」类（v1.83）：真题内容太长、又只作参考，背面里统一**排到最后、默认折叠**
     * （见 [parse] 的排序与 WordCard 的折叠行）。
     *
     * 判定用「**含**真题」而不是精确等于某几个标签：标签来源不统一 —— 方括号那支可能写
     * `【真题例句】`，不带方括号的 [BARE_LABELS] 里既有「真题例句」也有「真题」，实测语料里还出现过
     * 「典型真题」这类变体（如 `【2019年考研阅读】` 那种带年份的题源标签目前无人要求折叠，故不纳入）。
     * 只要标签里出现「真题」二字就归为一类，既覆盖已知写法，也不用每见一个新变体就来改枚举。
     */
    fun isExamTag(tag: String): Boolean = tag.contains("真题")

    /** 首行是不是音标行：以 `[` 开头，或整行只有词性缩写 */
    private fun isPhonetic(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return false
        if (s.startsWith("[")) return true
        return POS_ONLY.matches(s)
    }

    // 同 [Words.parseMemoized]：弹卡是热路径，同一段背面别反复解析。到顶整体清空，miss 最多多解析一次。
    private val memo = HashMap<String, List<Block>>()

    @Synchronized
    fun parseMemoized(back: String): List<Block> = memo.getOrPut(back) {
        if (memo.size >= 16) memo.clear()
        parse(back)
    }

    /**
     * 书页背面 → 结构化块列表。空输入 / 全是空白 → emptyList()。
     *
     * 逐行判定优先级：①序号行 → `·`残句 → 首行音标 → `【标签】` → 无方括号标签 → 其他（并入上一块）。
     *
     * 最后一步：把 [isExamTag] 为真的块**稳定地**排到末尾（两组的内部相对顺序都不变），
     * 满足「真题例子放在最后」（v1.83）。
     */
    fun parse(back: String): List<Block> {
        val lines = back.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<Block>()
        for ((i, line) in lines.withIndex()) {
            // ①~⑩ 序号行：并进上一块（换行分隔，序号原样保留）
            if (line[0] in CIRCLES) { appendToPrev(out, line); continue }
            // · 残句：自成一块、标 frag（UI 淡化，但内容一字不丢）
            if (FRAG_MARKS.contains(line[0])) { out.add(Block("", line, frag = true)); continue }
            // 首行音标 / 词性：tag 留空，UI 加粗
            if (i == 0 && isPhonetic(line)) { out.add(Block("", line)); continue }
            // 【标签】正文
            val m = TAG_BRACKET.find(line)
            if (m != null) { out.add(Block(m.groupValues[1].trim(), m.groupValues[2].trim())); continue }
            // 不带方括号的标签（辨析 …）
            val bare = bareLabel(line)
            if (bare != null) { out.add(Block(bare.first, bare.second)); continue }
            // 其他没有标签的行：归到上一块；前面没有块就自成一块
            appendToPrev(out, line)
        }
        // v1.85：「【词性】+【词义】」相邻两块先合成一块（见 [mergePosSense]），再各自按可读单位分行
        // v1.85.9：多个「词义」块给 chip 编号（词义 1 / 词义 2 …），否则一屏几张一样的「词义」分不清谁是谁
        val blocks = numberSenses(mergePosSense(out)).map { it.copy(text = wrap(it.text)) }
        // 「真题」类（用户要求「真题例子放在最后，折叠起来」）：稳定地挪到末尾 —— partition 保持两组
        // 内部各自的原相对顺序（非真题块照旧、真题块照旧），只改顺序，text/frag/tag 一字不动。
        val (normal, exam) = blocks.partition { !isExamTag(it.tag) }
        return normal + exam
    }

    /**
     * 把紧邻的「`【词性】` + `【词义】`」两块合成一块（v1.85，真机投诉：「词性和词义分开来、用不同颜色，
     * 让我以为是不同的两个内容，没想到它们是同一个内容」）。
     *
     * 源数据里 `【词性】❶n.` 与 `【词义】学会,协会…` 是**同一个词性条目的两半**，却被 [parse] 切成两个
     * 独立 [Block] → UI 各发一个不同底色的 chip（词义=薄荷、词性=纸白），视觉上断成两块、像两段无关内容。
     * 这里在 parse 阶段就把它们并成一块，只留「词义」这一个 chip（词性归属词义，色板不用动），
     * 词性文字作为正文首行接在词义正文前面 —— 内容一个字符都不动，只改「谁带着谁」。
     *
     * 刻意**只做这一对**：其他标签组合（助记/词组/辨析…）在书里本来就是并列的独立条目，
     * 合并没有语义依据，不该顺手一起合。合并发生在排序之前，且两侧标签都不是「真题」类
     * （[isExamTag]），所以不碰真题折叠块与末尾排序。
     */
    private fun mergePosSense(src: List<Block>): List<Block> {
        val out = ArrayList<Block>(src.size)
        var i = 0
        while (i < src.size) {
            val b = src[i]
            val next = src.getOrNull(i + 1)
            if (next != null && isPosTag(b.tag) && isSenseTag(next.tag)) {
                // 两块正文都空/首块空时不产生「空行 + 空行」：只拼非空的那份
                val body = if (b.text.isEmpty()) next.text
                else if (next.text.isEmpty()) b.text
                else b.text + "\n" + next.text
                out.add(Block(next.tag, body, next.frag))
                i += 2
                continue
            }
            out.add(b)
            i++
        }
        return out
    }

    /** 块标签是「词性」类（含标签变体，如「词性变化」）—— 参与 [mergePosSense] 配对 */
    private fun isPosTag(tag: String): Boolean = tag.contains("词性")

    /**
     * 给「词义」块按出现顺序编号（v1.85.9）：`词义` → `词义 1`、`词义 2` …，全书页只有一块时不动。
     * 纯函数、保序：只改 [Block.tag] 文本，正文一字不动。判定用 [isSenseTag]，
     * 所以「释义」「词义变化」这类变体同样参与编号（chip 上显示的就是各自的原文 + 序号）。
     */
    fun numberSenses(src: List<Block>): List<Block> {
        val n = src.count { isSenseTag(it.tag) }
        if (n <= 1) return src
        var i = 0
        return src.map { b ->
            if (isSenseTag(b.tag)) { i++; b.copy(tag = "${b.tag.trim()} $i") } else b
        }
    }

    /** 块标签是「词义」类（含「释义」写法，与 [WordCard] 的色板一致）—— 配对后由它当 chip */
    private fun isSenseTag(tag: String): Boolean = tag.contains("词义") || tag.contains("释义")

    /**
     * 这一行是不是**词性行**（`❶n.` / `❷ vt.` / `vt.`）—— UI 拿它决定要不要加粗（v1.85）。
     *
     * 词性与词义合并成一块之后，词性成了正文第一行；不加粗的话它和后面的义项读起来还是平级的，
     * 「融合度」只是从「两个 chip」变成「一个 chip + 一段平铺文字」。纯函数，判据 = 序号记号
     * （可选）+ 整行只有词性缩写（[POS_ONLY]，UI 加粗的规则与音标行同一条）。
     */
    fun isPosLine(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return false
        val t = if (s[0] in CLOSED_CIRCLES) s.substring(1).trim() else s
        return POS_ONLY.matches(t)
    }

    /** 追加到最后一个块；没有块就自成一块（`【词义】` 后面没字时，紧随的无标签行就走这条路） */
    private fun appendToPrev(out: MutableList<Block>, line: String) {
        val last = out.lastOrNull()
        if (last == null) out.add(Block("", line))
        else out[out.size - 1] = last.copy(text = if (last.text.isEmpty()) line else last.text + "\n" + line)
    }

    /** 行首是一个已知标签、且后面跟着空白/冒号/行尾 → 拆成 (tag, 正文) */
    private fun bareLabel(line: String): Pair<String, String>? {
        for (lab in BARE_LABELS) {
            if (!line.startsWith(lab)) continue
            val rest = line.substring(lab.length)
            if (rest.isEmpty() || rest[0].isWhitespace() || rest[0] == ':' || rest[0] == '：') {
                // 冒号是「标签 → 正文」的分隔符（识别标签时就是靠它判的），不该留在正文里 ——
                // 否则 `辨析: ment, hire` 的正文会以 `: ` 开头，标签 chip 下面第一行就是个孤零零的冒号。
                val body = rest.trim().let { if (it.startsWith(":") || it.startsWith("：")) it.substring(1).trim() else it }
                return lab to body
            }
        }
        return null
    }
}
