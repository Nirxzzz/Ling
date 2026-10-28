package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * v1.71 错题台账：识屏答题时顺手把「这道题、考什么、我答得对不对」记下来，
 * 供 WeakPoints 算薄弱点和给模型看的学习建议。
 *
 * 为什么单独一个文件而不是塞进 KnowledgeCard：知识卡是「用户主动想背的东西」，
 * 错题是「系统观察到的表现」。前者删掉不该影响诊断，后者要按最新一次覆盖同一题、
 * 上限 2000 条自动淘汰，两种保留策略混在一个文件里就各自都做不干净。
 *
 * 存储 filesDir/wrong.json，走 FileMemo（内存副本 + 单线程落盘），写法照 KnowledgeCard.Store。
 * 纯逻辑全放 object 本体，Android 那层只在 Store。
 */
object WrongLog {
    /** 台账上限。超了裁最旧的；同一道题本来会合并成一条，所以限的是「不同题目」数。 */
    const val MAX = 2000
    const val FILE = "wrong.json"

    /** 存下来的题干显示上限。归一化键不受这个限制，去重看的是全文。 */
    const val QUESTION_MAX = 200

    /**
     * 一条记录。[id] 本机单调递增、删了不复用；[knowledge] 为空串表示「没识别出知识点」——
     * 统计时归到「未分类」而不是直接丢掉，「识别不出考点」本身也是信息。
     */
    data class Q(
        val id: Long,
        val question: String,
        val knowledge: String,
        val answer: String,
        val correct: Boolean,
        /** 答题时间，毫秒。 */
        val at: Long,
        /** 来源：answer=答题面板、clip=截屏识别、manual=手动。只用于排查，不参与统计。 */
        val source: String = "answer",
    )

    /** 一次 record 的结果。界面要据此说「已收录 / 已更新」，所以不能只返回 void。 */
    data class Rec(val list: List<Q>, val q: Q?, val added: Boolean)

    /** 文本启发式的抽取结果。对错拿不准时 [correct] 为 null，调用方可以决定不记。 */
    data class Hint(val question: String, val knowledge: String, val correct: Boolean?)

    /**
     * 归一化题干，用作去重键。
     *
     * 只处理「同一道题换了排版」这一层：全半角、空白、常见标点、序号前缀、选项标记、大小写。
     * **故意不做**同义词 / 词序之类的模糊归并——识屏轻微抖动就可能把两道不同的题并成一条，
     * 错题记录丢了是补不回来的，宁可多留一条。
     */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.isWhitespace() -> Unit                        // 空白全丢
                ch == '\u3000' -> Unit                           // 全角空格
                ch in '\uFF01'..'\uFF5E' -> sb.append(ch - 0xFEE0) // 全角 ASCII 折半角
                ch in "\uFF0C\u3002\uFF1B\uFF1A\uFF1F\uFF01\uFF08\uFF09\u3001\u201C\u201D\u2018\u2019" -> sb.append(halfOrDrop(ch))
                else -> sb.append(ch)
            }
        }
        var out = sb.toString()
        // 序号前缀：同一题在不同截图里编号常常不一致，不剥掉就会重复入库
        out = out.replace(Regex("^(?:\\(?\\d{1,3}\\)?[.、:]?|第\\d{1,3}题[:.、]?)"), "")
        // 选项标记：A. / A、 / A)。只认紧跟在行首或中文顿号后的字母，免得误伤正文里的“A股”
        out = out.replace(Regex("(?:^|(?<=\\s))[A-Ha-h][.、)]"), "")
        return out.trim().lowercase()
    }

    /** 常见中文标点折成半角；语义标点（引号、括号类书名号）直接丢——只用来当键，不需要保留。 */
    private fun halfOrDrop(ch: Char): Char = when (ch) {
        '\uFF0C' -> ','
        '\u3002' -> '.'
        '\uFF1B' -> ';'
        '\uFF1A' -> ':'
        '\uFF1F' -> '?'
        '\uFF01' -> '!'
        '\uFF08' -> '('
        '\uFF09' -> ')'
        else -> ' '
    }

    /**
     * 从识屏 / 答题原文里抽「题目 + 知识点 + 对错」，纯启发式，不花钱调模型。
     *
     * 不依赖模型的理由：每答一题都要记一次，走一次模型又慢又贵；真正的语义判断
     * 在答题面板那侧已经做完了（见 fromAnswer），这里只负责「不管给什么文本都捞点东西出来」。
     */
    fun parse(raw: String): Hint {
        val lines = raw.lines().map { it.trim() }
        // 题干：第一行既有内容、又不是 markdown 标题、也不是知识点行 / 对错行
        val question = lines.firstOrNull { l ->
            l.isNotEmpty() && !l.startsWith("#") && !knowledgeMarked(l) && judgeOf(l) == null
        } ?: Memo.clip(raw.trim(), 60)

        val knowledge = lines.mapNotNull { l ->
            val bracket = Regex("\u3010([^\u3011]{1,30})\u3011").find(l)?.groupValues?.get(1)?.trim()
            if (!bracket.isNullOrEmpty()) return@mapNotNull bracket
            Regex("(?:知识点|考点|考查点)\\s*[:\uFF1A]\\s*(.{1,30})").find(l)?.groupValues?.get(1)?.trim()
        }.firstOrNull { !it.isNullOrEmpty() } ?: ""

        // 同一份文本里既有「正确」又有「错误」时取先出现的：结论一般在开头，
        // 后面出现的往往是解析正文里引用的一句话。
        val correct = lines.asSequence().mapNotNull { judgeOf(it) }.firstOrNull()

        return Hint(Memo.clip(question, QUESTION_MAX), Memo.clip(knowledge, 40), correct)
    }

    private fun knowledgeMarked(line: String): Boolean =
        line.startsWith("知识点") || line.startsWith("考点") || line.startsWith("考查点")

    /**
     * 单行的对错判断；null = 这行没说对错。
     *
     * 先认显式词再认符号：单独的「×」在数学题干里就是乘号，必须配「批改 / 判断 / 结果」
     * 这类上下文才敢当成错，否则每道选择题都会被记成答错。
     */
    fun judgeOf(line: String): Boolean? {
        when {
            line.contains("回答错误") || line.contains("答错") || line.contains("判断错误") -> return false
            line.contains("回答正确") || line.contains("答对") || line.contains("判断正确") -> return true
            Regex("(?:结果|答案|判断|批改|判定)\\s*[:：]?\\s*(?:错误|不正确|错|✗|✘|×)").containsMatchIn(line) -> return false
            Regex("(?:结果|答案|判断|批改|判定)\\s*[:：]?\\s*(?:正确|✓|✔|√|对)").containsMatchIn(line) -> return true
            line in setOf("✓", "✔", "√", "正确", "对", "√正确") -> return true
            line in setOf("✗", "✘", "×", "错误", "错", "不正确", "×错误") -> return false
        }
        return null
    }

    /**
     * 答题面板入口：题目 / 知识点 / 对错都是已知的，直接组装，不用再走 parse 猜。
     * 题干空白返回 null——那种记录只会污染统计。
     */
    fun fromAnswer(question: String, knowledge: String, answer: String, correct: Boolean?): Hint? {
        val q = Memo.clip(question.trim(), QUESTION_MAX)
        if (q.isEmpty()) return null
        return Hint(q, Memo.clip(knowledge.trim(), 40), correct)
    }

    /**
     * 纯函数版 record：给定现状 + 一条新记录，返回合并结果。
     *
     * 归一化题干相同视为同一题，**保留最新一次**（整条替换，但 id 沿用旧的，
     * 界面上「第 N 题」不会因为重做一遍而跳号）。否则按 id 前插；超过 max 裁最旧的。
     */
    fun merge(list: List<Q>, q: Q, max: Int = MAX): Rec {
        if (q.question.isBlank()) return Rec(list, null, false)
        val key = norm(q.question)
        val old = list.firstOrNull { norm(it.question) == key }
        if (old != null) {
            val updated = q.copy(id = old.id)
            return Rec(list.map { if (it.id == old.id) updated else it }, updated, false)
        }
        val nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        val fresh = q.copy(id = nextId)
        val grown = listOf(fresh) + list
        return Rec(if (grown.size > max) grown.subList(0, max) else grown, fresh, true)
    }

    fun toJson(list: List<Q>): String = JSONArray().apply {
        list.forEach { q ->
            put(JSONObject().apply {
                put("id", q.id); put("q", q.question); put("k", q.knowledge)
                put("a", q.answer); put("ok", q.correct); put("at", q.at); put("src", q.source)
            })
        }
    }.toString()

    /**
     * 容错解析：坏元素跳过，整体坏 JSON 返回空表。
     * 台账坏了不该让学习页整个打不开——薄弱的诊断缺数据是可接受的，白屏不是。
     */
    fun fromJson(s: String?): List<Q> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until min(a.length(), MAX)).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val question = o.optString("q")
                if (question.isBlank()) return@mapNotNull null
                Q(
                    id = o.optLong("id", 0L),
                    question = question,
                    knowledge = o.optString("k").trim(),
                    answer = o.optString("a"),
                    correct = o.optBoolean("ok", false),
                    at = o.optLong("at", 0L),
                    source = o.optString("src", "answer"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** filesDir/wrong.json 的读写。读写都锁 FileMemo，和其它 Store 共用同一把锁的约定。 */
    class Store(ctx: Context) {
        private val app = ctx.applicationContext
        private val file = File(app.filesDir, FILE)

        fun list(): List<Q> = synchronized(FileMemo) {
            FileMemo.getChecked(file, { s -> fromJson(s) }, { s -> fromJson(s) }).value ?: emptyList()
        }

        /**
         * 记一条。题干空白或对错未知时不写盘（返回 q = null）：
         * 对错未知的记录一旦进了台账就永远没法纠正，会长期拉低那个知识点的正确率。
         */
        fun record(question: String, knowledge: String, answer: String, correct: Boolean?, now: Long, source: String = "answer"): Rec =
            synchronized(FileMemo) {
                val cur = list()
                val hint = fromAnswer(question, knowledge, answer, correct)
                val ok = hint?.correct
                if (hint == null || ok == null) return@synchronized Rec(cur, null, false)
                val res = merge(cur, Q(0L, hint.question, hint.knowledge, answer, ok, now, source))
                if (res.q != null) FileMemo.put(file, res.list, { l -> toJson(l) })
                res
            }

        /** 直接吞一段识屏原文：能判出对错才记，判不出就当没发生。 */
        fun recordText(raw: String, now: Long, source: String = "clip"): Rec = synchronized(FileMemo) {
            val cur = list()
            val h = parse(raw)
            val ok = h.correct ?: return@synchronized Rec(cur, null, false)
            val res = merge(cur, Q(0L, h.question, h.knowledge, "", ok, now, source))
            if (res.q != null) FileMemo.put(file, res.list, { l -> toJson(l) })
            res
        }

        fun clear() = synchronized(FileMemo) { FileMemo.clear(file, emptyList<Q>()) }

        fun count(): Int = list().size
    }
}
