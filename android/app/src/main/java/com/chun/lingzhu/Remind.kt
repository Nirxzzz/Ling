package com.chun.lingzhu

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 助手主动发起提醒（M4 → v1.86 支持「记期」）：把「用户直白说的」和「模型回复里标的」都翻成提醒。
 *
 * 纯逻辑（**不 import android.***）：[plan] / [askOf] / [fromUser] / [fromReplyFull] / [confirm] 都能 JVM 直测。
 * 真建待办、建规则、排闹钟那层在 [ChatActivity]（[Todo.Store] / [Schedule.Store] + `schedule` / `sync`）。
 *
 * 三条来源各管一段：
 *  - 用户说「提醒我 20 分钟后喝水」—— 意图 + 时间都在原文里，本地直接认（一次性，[fromUser]）；
 *  - 用户说「每周一三五晚上八点提醒我背单词」—— 本地认成**记期规则**（[Schedule.parse]）；
 *  - 用户没直说、但意思到了（「周四要交论文」）—— 交给模型判断，模型在回复里标一行，
 *    这里把标记解析出来、并把标记从给用户看的正文里剥掉（[fromReplyFull]）。
 *    这条链**不引 function-calling**：标记只是普通 chat completion 里的一行约定文本。
 */
object Remind {

    /** 一条要建的提醒：[text] 提醒正文，[dueAt] 到点时刻（毫秒，本机时区） */
    data class Ask(val text: String, val dueAt: Long)

    /** 一句话解析出来的全部东西：一次性待办 + 记期规则 */
    data class Plan(val asks: List<Ask>, val rules: List<Schedule.Rule>) {
        val isEmpty: Boolean get() = asks.isEmpty() && rules.isEmpty()
    }

    // ---------------- 直白的本地识别 ----------------

    /**
     * 提醒意图词（长词在前，剥标题时先剥长的，「提醒我」不会只剩个「提醒」）。
     * 光有意图词不算数 —— 必须同时命中时间才建，所以这里可以放得宽一点。
     */
    private val INTENT = listOf(
        "记得提醒我", "提醒一下我", "提醒一下", "提醒我",
        "记得叫我", "叫我",
        "定个闹钟", "设个闹钟", "定闹钟", "设闹钟",
        "记得", "别忘记", "别忘了", "别忘",
        "到点提醒", "到时提醒", "到点叫我", "到时叫我",
    )

    /**
     * 否定 / 问功能：这类句子虽有「提醒」二字，也不是要建。**先过这一关再谈意图**。
     * 覆盖用户点名的两个反例：「我不需要提醒」「提醒功能怎么开」。
     */
    private val NEG = listOf(
        "不用提醒", "不需要提醒", "无需提醒", "不要提醒", "别提醒", "取消提醒", "关闭提醒",
        "提醒功能", "提醒怎么", "怎么提醒", "如何提醒", "提醒在哪", "提醒设置", "提醒开关",
        "能提醒吗", "可以提醒吗", "有没有提醒",
    )

    /** [Todo.parseWhen] 认得的时间写法（命中其一即可）。和[Todo.TIME_RE] 一起用，改一处要改另一处。 */
    private val TIME = listOf(
        Regex("(今天|明天|后天)"),
        Regex("\\d+(分钟|小时|天)后"),
    )

    /** 一句话里可能有好几件事：「提醒我明天8点起床，9点开会」。这组词当分隔符。 */
    private val SEG_SPLIT = Regex("[。！？；;\\n，,、]+|然后|还有|以及|接着|另外|顺便")

    private fun compact(s: String) = s.replace(Regex("[\\s\\u3000]+"), "")

    private fun hasTime(s: String): Boolean = TIME.any { it.containsMatchIn(s) } || Todo.TIME_RE.containsMatchIn(s)

    private fun hasIntent(s: String): Boolean = INTENT.any { s.contains(it) }

    /**
     * 用户这句话是不是「明确让我到点提醒」。**意图词 + 能解析的时间**两样都齐才返回，否则 null。
     * 只管**一次性**那种；「每周 / 每天」这种重复的走 [Schedule.parse]（见 [plan]）。
     *
     * @param now 当前时刻（毫秒）；「20 分钟后」这类相对时间以它为基准
     */
    fun fromUser(text: String, now: Long): Ask? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (NEG.any { t.contains(it) }) return null            // 「不需要提醒」「提醒功能怎么开」
        if (!hasIntent(t)) return null                          // 没有提醒意图
        return askOf(t, now)
    }

    /** 已经确认有意图的一句 → 一次性提醒（内部用；[plan] 对继承意图的分段也用它） */
    private fun askOf(seg: String, now: Long): Ask? {
        // 去掉空白再解析：用户常写「20 分钟后」「8 点」，parseWhen 的正则要求数字紧贴单位 / 时间词。
        val q = compact(seg)
        if (!hasTime(q)) return null
        val p = Todo.parseWhen(q, now)
        return Ask(clean(p.title), p.dueAt)
    }

    /**
     * v1.86 一句话 → [Plan]（一次性 + 记期）。
     *
     * 规则：先按分隔符切成几段，每段独立判。**第一段之后**的分段允许「继承意图」
     * （「提醒我明天8点起床，9点开会」里第二句没写「提醒我」也算），但继承的分段必须自带时间，
     * 否则丢掉 —— 宁可漏，也不要把闲聊里的半句当成提醒。
     */
    fun plan(text: String, now: Long = System.currentTimeMillis()): Plan {
        val t = text.trim()
        if (t.isEmpty()) return Plan(emptyList(), emptyList())
        val asks = ArrayList<Ask>()
        val rules = ArrayList<Schedule.Rule>()
        var started = false
        for (raw in t.split(SEG_SPLIT)) {
            val seg = raw.trim()
            if (seg.isEmpty() || NEG.any { seg.contains(it) }) continue
            if (!hasIntent(seg) && !started) continue           // 没意图、前面也没认出来过 → 跳过
            // 「每周一三五晚上八点提醒我背单词」→ 记期规则（必须在 parseWhen 之前认，否则时间会算错）
            val rule = Schedule.parse(seg, now)
            if (rule != null) {
                rules.add(rule.copy(text = clean(rule.text)))
                started = true
                continue
            }
            val a = askOf(seg, now) ?: continue
            asks.add(a)
            started = true
        }
        return Plan(asks, rules)
    }

    /** 标题里再抹掉意图词、修一下空白；实在空了就用「提醒」兜底 */
    fun clean(title: String): String {
        var s = title
        for (w in INTENT) s = s.replace(w, " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.ifEmpty { "提醒" }
    }

    // ---------------- 模型回复里的标记：[fromReplyFull] ----------------

    /**
     * 约定标记（写进聊天 system prompt）：一行一条，
     *   `[[提醒 2026-10-12T09:00|交论文]]`            —— 一次性
     *   `[[记期 1,3,5 20:00|背单词]]`                 —— 每周一/三/五 20:00（数字是周一=1..周日=7）
     * 时间都是**本机当地时间**，人能一眼看懂。
     */
    const val MARK_HEAD = "[[提醒"
    const val RULE_HEAD = "[[记期"

    /** 严格格式：时间 + `|` + 正文。解析不出时间的（格式像但日期非法）剥掉、不建。 */
    private val MARK_OK = Regex("\\[\\[\\s*提醒\\s+(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2})\\s*\\|\\s*([^\\]\\r\\n]+?)\\s*\\]\\]")

    /** 记期标记：`1,3,5` + `HH:mm` + `|` + 正文 */
    private val RULE_OK = Regex("\\[\\[\\s*记期\\s*([1-7](?:\\s*[,，]\\s*[1-7])*)\\s+(\\d{1,2}:\\d{2})\\s*\\|\\s*([^\\]\\r\\n]+?)\\s*\\]\\]")

    /** 宽格式：只要是「[[提醒…」/「[[记期…」这一整段就剥掉（写坏的也剥），保证标记永远不漏到界面上。 */
    private val MARK_ANY = Regex("\\[\\[\\s*提醒[^\\]\\r\\n]*\\]\\]?")
    private val RULE_ANY = Regex("\\[\\[\\s*记期[^\\]\\r\\n]*\\]\\]?")

    /**
     * 从模型回复里抽提醒 / 记期，并返回剥掉标记后的正文。
     * 返回 `(正文, 一次性列表, 记期列表)`；正文里**不会**残留任何 `[[提醒` / `[[记期`（写坏的也剥、只是不建）。
     */
    fun fromReplyFull(reply: String, now: Long): Triple<String, List<Ask>, List<Schedule.Rule>> {
        if (reply.isBlank()) return Triple("", emptyList(), emptyList())
        val asks = MARK_OK.findAll(reply).mapNotNull { m ->
            val at = parseTime(m.groupValues[1]) ?: return@mapNotNull null
            val text = m.groupValues[2].trim()
            if (text.isEmpty()) null else Ask(text, at)
        }.toList()
        val rules = RULE_OK.findAll(reply).mapNotNull { m ->
            val days = m.groupValues[1].split(Regex("\\s*[,，]\\s*")).mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }
            val hm = m.groupValues[2].split(":")
            val h = hm.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val mi = hm.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val text = m.groupValues[3].trim()
            if (text.isEmpty() || days.isEmpty() || h !in 0..23 || mi !in 0..59) null
            else Schedule.Rule(0L, text, weekly = true, mask = Schedule.maskOf(days), minute = h * 60 + mi, createdAt = now)
        }.toList()
        return Triple(strip(reply), asks, rules)
    }

    /** 只要一次性那部分的旧口径（老调用点 / 单测用；记期那条链不走它） */
    fun fromReply(reply: String, now: Long): Pair<String, List<Ask>> {
        val (body, asks, _) = fromReplyFull(reply, now)
        return body to asks
    }

    /**
     * 剥标记：**带标记的整行直接丢掉**（标记是「单独一行」的约定，丢掉整行不会留空行，
     * 也保证写坏的 / 半截标记一行都不漏到界面上）；再兜底把行内残留的段（理论上没有）抹掉。
     */
    private fun strip(reply: String): String {
        val kept = reply.lines().filterNot { it.contains(MARK_HEAD) || it.contains(RULE_HEAD) }
        val s = RULE_ANY.replace(MARK_ANY.replace(kept.joinToString("\n"), ""), "")
        return s
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** 「2026-10-12T09:00」→ 本机时区毫秒；格式 / 日期非法 → null */
    private fun parseTime(s: String): Long? = try {
        // 每次新建：SimpleDateFormat 不是线程安全的
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { isLenient = false }.parse(s)?.time
    } catch (_: Exception) { null }

    // ---------------- 回给用户的那句确认 ----------------

    /** 建完待办后回一句：「记下了：明天 08:00 提醒你「起床」」 */
    fun confirm(ask: Ask, now: Long): String = "记下了：${whenText(ask.dueAt, now)} 提醒你「${ask.text}」"

    /** 多条拼成一段（一条一行） */
    fun confirmAll(asks: List<Ask>, now: Long): String = asks.joinToString("\n") { confirm(it, now) }

    /** 建完记期规则后回一句：「记下了：每周一、三、五 20:00 提醒你「背单词」」 */
    fun confirmRule(r: Schedule.Rule): String {
        val hm = String.format(Locale.US, "%02d:%02d", r.minute / 60, r.minute % 60)
        val times = if (r.times > 1) "（提醒 ${r.times} 次）" else ""
        return if (r.weekly) "记下了：${Schedule.weekdayLabel(r.mask)} $hm 提醒你「${r.text}」$times"
        else "记下了：${Schedule.Month.formatDate(r.dateMs)} $hm 提醒你「${r.text}」$times"
    }

    fun confirmRules(rules: List<Schedule.Rule>): String = rules.joinToString("\n") { confirmRule(it) }

    /** 一次性 + 记期合起来回一段（聊天页用） */
    fun confirmPlan(plan: Plan, now: Long): String = buildList {
        addAll(plan.asks.map { confirm(it, now) })
        addAll(plan.rules.map { confirmRule(it) })
    }.joinToString("\n")

    /** 「今天 08:00 / 明天 08:00 / 后天 08:00 / 3 天后 08:00」（本机时区，按自然日算天数） */
    fun whenText(dueAt: Long, now: Long): String {
        val days = ((Todo.dayStart(dueAt) - Todo.dayStart(now)) / 86_400_000L).toInt()
        val hm = hm(dueAt)
        return when {
            days <= 0 -> "今天 $hm"
            days == 1 -> "明天 $hm"
            days == 2 -> "后天 $hm"
            else -> "$days 天后 $hm"
        }
    }

    private fun hm(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
