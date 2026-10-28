package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 和小珠聊天的会话（v1.69 陪伴 M2）。
 *
 * 落 filesDir/chat.json（数组，每条 role / content / 时间戳），最新的一条在**末尾** ——
 * 和 [History]（最新的在最前、列表倒着显示）相反：聊天是自上而下顺着读的，顺序即时间顺序。
 *
 * 分工和项目里其它存储一样：解析 / 追加 / 裁剪 / 滑窗 / 提示词全是纯函数（单元测试直接跑），
 * Android 那层只在 [Store] 里，文件读写走 [FileMemo]（内存副本 + 后台落盘）。
 *
 * ⚠️ 本轮范围最小：只有一条会话、纯文本、不做 markdown 排版。人格编辑、流式、截图附件、
 * 多会话列表都留给下一轮（见 报告-聊天界面.md）。
 */
object ChatStore {
    /** [MP] 旧文件（v1.69 单会话时代）。首次读新路径时把内容搬过去，然后删掉它 */
    const val FILE = "chat.json"
    /** [MP] 人格化之后每个 pid 一份：chat-<pid>.json */
    fun file(pid: String) = "chat-${safeFile(pid)}.json"
    /**
     * 每次请求带最近多少条消息（用户定的 20）。超出的老消息在**请求里**不带 —— 接口本身不记上下文；
     * 但**本地记录一条都不删**：更早的那些由 [AgentContext] 压成摘要，跟着最近几轮一起发出去。
     */
    const val WINDOW = 20

    /**
     * 成块驱逐的块大小：超出 [WINDOW] 后**攒到 [WINDOW] + [EVICT_CHUNK] 才裁一次**，中间的轮次一条都不裁。
     *
     * 为什么不能逐条滑：服务端前缀缓存锚在**请求开头**（[body] 里 system + 历史）。逐条滑动 = 每轮请求
     * 开头那一条就换人了 = 缓存对历史段 100% 失效，历史每轮都按全价重新计费。
     * 成块驱逐让请求前缀在 (WINDOW, WINDOW + EVICT_CHUNK] 这个区间里**逐字不变**，同一前缀连续多轮命中，
     * 历史段按缓存价（约全价的 1/10）结算。
     *
     * 取 6：聊天一来一回是 2 条（用户 1 + 小珠 1），一轮对话涨 2 条 —— 6 = 3 个来回，
     * 块边界正好落在轮次上。裁完那轮之后，21~26 条这 3 轮前缀一条不动、全走缓存价，
     * 第 4 轮跨过 26 才跳一次 head。也就是每 4 轮里 1 轮付历史全价、3 轮按缓存价（约 1/10）算，
     * 历史段总花费降到原来的约 1/3。块再大（比如 20）能省更多，但超期窗口会太宽、
     * 且失效那一次要重算的段也更长，6 是稳得住的折中。
     */
    const val EVICT_CHUNK = 6

    /** 角色：小珠 / 用户。存盘用这两个词，不直接写 OpenAI 的 role，免得换模型时整份文件作废 */
    const val ORB = "orb"
    const val USER = "user"

    /** 聊天里发出去的图存在哪（[Msg.img] 只记文件名，目录由 Activity 拼） */
    const val IMG_DIR = "chat-img"

    /**
     * 空正文消息发给接口时的占位文字。
     * 1) 历史里的图片消息：base64 不落盘（一张几百 KB，200 条能撑爆会话文件），
     *    所以重进聊天后那张图在接口眼里只剩这个占位 —— 宁可它记得「这儿发过一张图」，
     *    也不能发一条 content 为空的消息过去（OpenAI 兼容实现会直接 400）。
     * 2) 这一轮刚发的图：正文本来就是空的，占位换成 [IMG_ASK] 更像个问句。
     */
    const val IMG_PLACEHOLDER = "[图片]"
    const val IMG_ASK = "看看这张图"

    /** role = 谁说的；content = 正文；at = 说的时刻（毫秒）；img = 发的图的文件名（null = 纯文字） */
    data class Msg(val role: String, val content: String, val at: Long, val img: String? = null) {
        val isOrb: Boolean get() = role == ORB
        /** 发给接口时的 role（小珠 → assistant） */
        val wireRole: String get() = if (isOrb) "assistant" else "user"
    }

    fun orb(content: String, at: Long) = Msg(ORB, content, at)
    fun user(content: String, at: Long, img: String? = null) = Msg(USER, content, at, img)

    /** 这条消息发给接口的**文字部分**：有图但没字时给占位，绝不发空 content */
    fun wireText(m: Msg, blankText: String = IMG_PLACEHOLDER): String =
        m.content.ifBlank { if (m.img != null) blankText else "" }

    /** 认 role：只认 orb / user（别的、老的、空的都当用户说），返回归一化后的 role */
    fun normRole(s: String?): String = if (s == ORB) ORB else USER

    // ---------------- 序列化 ----------------

    fun parse(json: String?): List<Msg> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optString("content")
                val img = o.optString("img").takeIf { it.isNotBlank() }
                if (c.isBlank() && img == null) null          // 空正文又没图 = 不是一条消息，丢掉
                else Msg(normRole(o.optString("role")), c, o.optLong("at"), img)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Msg>): String = JSONArray().apply {
        for (m in list) put(JSONObject().put("role", m.role).put("content", m.content).put("at", m.at).apply {
            // 只有带图的才多写一个键 —— 纯文字会话的落盘格式和加图片功能之前**逐字一样**
            if (m.img != null) put("img", m.img)
        })
    }.toString()

    // ---------------- 增删 ----------------

    /**
     * 追加到末尾；空正文（且没带图）不进；时间戳撞了往后挪（同毫秒连发两条很常见）。
     *
     * **不裁剪**：本地会话就是完整记录，用户明确要求一条都不许丢（老版本这里按 MAX=200 砍头，已移除）。
     * 请求那边变长的问题由 [AgentContext] 用摘要解决，落盘这一层只管追加。
     */
    fun add(list: List<Msg>, m: Msg): List<Msg> {
        if (m.content.isBlank() && m.img == null) return list
        val at = if (list.any { it.at == m.at }) ((list.maxOfOrNull { it.at } ?: 0L) + 1) else m.at
        return list + m.copy(at = at)
    }

    fun clear(): List<Msg> = emptyList()

    // ---------------- 发给接口的那一段 ----------------

    /**
     * 滑窗：最近 [n] 条。
     * 开头如果是小珠说的（砍在两轮中间），从下一条用户消息接上 —— 让接口看到的第一句是用户说的，
     * 和真人打电话的顺序一样。**只砍开头连续的小珠**：砍到一条不剩（比如这段全是小珠说的）
     * 就把砍过的原样还回去 —— 宁可开头是小珠 spoke，也不能发一个空 messages 过去。
     */
    fun window(list: List<Msg>, n: Int = WINDOW): List<Msg> {
        if (list.size <= n) return list
        val kept = list.takeLast(n).toMutableList()
        val cut = kept.size
        while (kept.isNotEmpty() && kept.first().isOrb) kept.removeAt(0)
        return if (kept.isEmpty()) list.takeLast(cut) else kept
    }

    /**
     * 成块驱逐版滑窗：目标还是最近 [n] 条，但**不是每加一条就裁**。
     *
     * - `list.size <= n + chunk` → 一条不裁，原样全返回（绝大多数轮次都走这条）。
     * - 超过 `n + chunk` → 一次裁到 [n]，裁法全交给 [window]（开头那句对齐用户的老规矩不变）。
     *
     * 效果：窗口停在 (n, n + chunk] 这个区间里，**头部锚住不动**，每轮只是末尾多挂一条新消息。
     * 服务端前缀缓存按最长公共前缀匹配，尾部增长正是它想要的形状 —— 于是这段前缀连续多轮命中，
     * 历史段按缓存价（约全价的 1/10）结算。只有跨过 n + chunk 那轮 head 跳一次、失效重算一次。
     * 所以每 [chunk] 条消息付一次全价，其余轮次都走缓存价。
     *
     * [chunk] <= 0 视为防御性地退化成 [window]（立刻裁到 [n]），不因为参数非法而给出比目标更宽的窗口。
     * [n] <= 0 一律返回空 —— 目标窗口 0 条就是 0 条，不许被 size <= n+chunk 分支撑回全量（edge 探针抓过）。
     */
    fun windowChunked(list: List<Msg>, n: Int = WINDOW, chunk: Int = EVICT_CHUNK): List<Msg> {
        if (n <= 0) return emptyList()
        return if (chunk <= 0) window(list, n)
        else if (list.size <= n + chunk) list
        else window(list, n)
    }

    /** 滑窗 → 接口的 messages（不含 system）。只测这一层，不碰网络。走成块驱逐，前缀才稳。 */
    fun turns(list: List<Msg>, n: Int = WINDOW): List<Pair<String, String>> =
        windowChunked(list, n).map { it.wireRole to it.content }

    /** 滑窗后的消息本体（[turns] 是它的 (role, 正文) 版）。带图那一轮得拿 Msg 才知道图是哪条的 */
    fun recent(list: List<Msg>, n: Int = WINDOW): List<Msg> = windowChunked(list, n)

    /**
     * 请求体（非流式，`stream: false`）。
     * system 单独一条放最前面 —— 接口的前缀缓存在 system 上，历史轮次变了也不用重算它。
     * 思考参数直接调 [Bodies.thinking]（和识别那条线同一份，不另写一套）。
     *
     * [suffix] 是**这一次才有的**尾巴（识屏记录摘要，见 [historySummary]），追加到末条 user 消息末尾。
     * 刻意**不进 system**：system 是前缀缓存的锚点，摘要每次都变，塞进去等于每次全价重算 system。
     * 默认空串 → 请求体和加这个参数之前逐字一致。
     *
     * [imgB64] 只对**这一刻真要发出去的那张图**返回 base64（由调用方按文件名判断）。
     * 默认恒空 → 纯文字会话的请求体和加图片功能之前**逐字一样**（前缀缓存不受影响）。
     *
     * [history] = 调用方已经备好的历史（[AgentContext] 压出来的「摘要 + 最近完整轮次」）：
     * 给了就**原样用**，绝不再过滑窗 —— 否则那份精心保留的尾部会被 20 条的默认窗口二次裁掉。
     * 默认 null → 行为和加这个参数之前逐字一致。
     */
    fun body(
        cfg: ApiConfig,
        list: List<Msg>,
        n: Int = WINDOW,
        maxTokens: Int = 2048,
        system: String = systemPrompt(),
        suffix: String = "",
        imgB64: (Msg) -> String? = { null },
        /** v2.2.1 联网搜索：开着时聊天改走流式 + tools（默认 false → 请求体和以前逐字一样） */
        stream: Boolean = false,
        withTools: Boolean = false,
        history: List<Msg>? = null,
    ): String {
        val o = JSONObject()
            .put("model", cfg.model)
            .put("stream", stream)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).apply {
                for (m in history ?: recent(list, n)) put(messageOf(m, imgB64))
                if (suffix.isNotEmpty()) appendSuffix(this, suffix)
            })
        Bodies.thinking(cfg, false, o)
        if (withTools) Bodies.tools(cfg, o)   // 开关关 / 没配全时 tools() 自己什么都不加
        return o.toString()
    }

    /**
     * 一条消息 → 接口要的那个 message。**没图时和以前逐字一样**（content 是纯字符串），
     * 只有 [imgB64] 真的给出了 base64（这一轮刚发的那张图）才升格成多模态数组：
     * `content = [{type:text},{type:image_url}]` —— 形状和识屏那条线（[ApiClient.vision]）同一套。
     * 小珠自己的消息永远没有图。
     */
    private fun messageOf(m: Msg, imgB64: (Msg) -> String?): JSONObject {
        val b64 = if (m.isOrb) null else imgB64(m)?.takeIf { it.isNotBlank() }
        if (b64 == null) return JSONObject().put("role", m.wireRole).put("content", wireText(m))
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", wireText(m, IMG_ASK)))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        return JSONObject().put("role", m.wireRole).put("content", content)
    }

    /**
     * 把 [suffix] 接到**最后一条 user 消息**末尾（倒着找第一条 user）。
     * 一条 user 都没有（只有 system / 全是小珠说的）→ 什么都不做：宁可这条尾巴丢掉，
     * 也不能改 system —— 那会把前缀缓存整个掀翻。
     */
    private fun appendSuffix(ms: JSONArray, suffix: String) {
        for (i in ms.length() - 1 downTo 0) {
            val o = ms.optJSONObject(i) ?: continue
            if (o.optString("role") == "user") { o.put("content", o.optString("content") + suffix); return }
        }
    }

    /**
     * 一行「现在几点」，给模型当本机当地时间用：`[现在时间 2026-10-14T15:04 周三 Asia/Shanghai]`。
     *
     * [REMIND_RULE] 让模型写「本机当地时间 yyyy-MM-ddTHH:mm」的提醒标记，但请求里从来没给过当前值 ——
     * 模型只能靠训练截止时间瞎猜。这一行由 [ChatActivity.runChatFlight] **每轮实时**取时钟拼进 suffix 开头，
     * 走 [appendSuffix] 落到末条 user 消息上；**不进 system** —— system 是前缀缓存的锚点，时间每轮都变，
     * 塞进去等于每轮全价重算 system（同 [body] 里 suffix / 记忆那几段的理由）。
     *
     * 纯函数：[now] 由调用方注入（不碰 [System.currentTimeMillis]），单测好钉。
     * 日期时间沿用 yyyy-MM-ddTHH:mm 的 %02d 口径，星期取中文一二三四五六日（[java.util.Calendar.DAY_OF_WEEK]
     * 周日=1 起），时区取 **JVM 默认时区**的真实 ID（测试机多半不是 Asia/Shanghai，别拿上海的 ID 去断言）。
     */
    fun nowLine(now: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val date = String.format(
            "%04d-%02d-%02dT%02d:%02d",
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
        )
        val week = "日一二三四五六"[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]   // 周日=1 → 下标 0
        return "[现在时间 $date 周$week ${java.util.TimeZone.getDefault().getID()}]"
    }

    /**
     * [MP] 系统提示词 = 人格段（空则回落 [Prompts.PERSONA]，老用户逐字不变）+ 聊天场景那两句 + 该人格的记忆卡。
     *
     * 记忆卡也跟人格走：每个人格只带自己那份，别人记得的事不串台。
     */
    /**
     * M4 提醒标记的 system 段（[Remind] 解析它、并把这行从展示的正文里剥掉）。
     * 只是普通 chat 里的一行约定文本 —— **不引 function-calling**。
     * 单独的常量：PersonasTest 靠它锁住「P0 的聊天提示词」逐字拼法。
     */
    const val REMIND_RULE = "只有用户明显想让你到点提醒他、而你又没法让他自己设的时候，才在回复**最后单独一行**加一条提醒标记：" +
        "\n[[提醒 2026-10-12T09:00|要提醒的事]]　（一次性：时间是本机当地时间 yyyy-MM-ddTHH:mm，竖线后写提醒内容）" +
        "\n[[记期 1,3,5 20:00|要提醒的事]]　（每周重复：逗号前是周几，周一=1 … 周日=7；后面是几点几分）" +
        "\n多条就多行。其他情况（只是随便聊聊、说他打算做什么、或你已经用文字答应提醒了）都不要输出这个标记。"

    fun systemPrompt(p: Persona? = null, card: Card? = null): String {
        val who = p ?: Personas.p0()
        val sb = StringBuilder(Personas.promptOf(who))
        sb.append("\n\n现在是聊天，不是识别屏幕 —— 你手上没有截图，用户在跟你说话。")
        sb.append("\n用中文，直接回他说的这件事。")
        sb.append("\n\n").append(REMIND_RULE)
        Prompts.cardBlock(card).takeIf { it.isNotEmpty() }?.let { sb.append("\n\n").append(it) }
        return sb.toString()
    }

    // ---------------- 聊天里带识屏记录 ----------------

    /** 摘要块抬头（跟在末条 user 消息后面，模型看到的约束离正文最近） */
    const val HISTORY_HEAD = "[以下是最近的识屏记录，供回答参考]"

    /**
     * 一次最多带多少条识屏记录。
     * 20 是拍的：一条压成一行 ≈ 40~60 token，20 条约 1k token，够小Ling 看清「最近都问了什么」，
     * 又不会把这一轮的回答预算挤掉。再多收益也递减（20 条之前的东西它已经记不清了）。
     */
    const val HISTORY_N = 20

    /** 单条正文预览留多少字（沿用 [History.preview] 自己的默认 80） */
    const val HISTORY_CLIP = 80

    /**
     * 「这次要不要带识屏记录」—— 用户这句话里出现了下面这些词就算。
     *
     * 词表分两类：动词类（总结 / 归纳 / 梳理…）单独出现不敢算 ——「帮我总结一下这段代码」
     * 跟识屏记录没关系；所以动词必须和「记录 / 识别 / 屏幕 / 最近」这类词**同时**出现才算命中。
     * 名词类（识屏记录 / 历史记录 / 最近识别…）本身就是明确指路，单独出现就命中。
     * 跟 [KnowledgeSearch.shouldSearch] 同一个路子：宁可漏（那就跟没这功能一样），
     * 别误注入（白烧 token，还把不相干的题塞给小Ling）。
     */
    private val NOUN_WORDS = listOf(
        "识屏记录", "识别记录", "识屏历史", "识别历史", "历史记录", "屏幕记录",
        "最近识别", "最近识屏", "最近记录", "最近看的", "刚才识别", "刚才识屏", "看过什么", "识别过",
    )
    private val VERB_WORDS = listOf("总结", "归纳", "梳理", "汇总", "整理")
    private val CONTEXT_WORDS = listOf("记录", "识别", "识屏", "屏幕", "最近", "刚才", "之前", "看过")

    /** 这次输入要不要带识屏记录进请求 */
    fun wantsHistory(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (NOUN_WORDS.any { t.contains(it) }) return true
        return VERB_WORDS.any { t.contains(it) } && CONTEXT_WORDS.any { t.contains(it) }
    }

    /**
     * 最近 [n] 条**识屏**记录压成「相对时间 · 模式 · 预览」一行一条的摘要块。
     *
     * 三个取舍：
     *  - **只取 kind=solve**（识屏）：翻译记录混进来会让模型以为你在问刚才查的那个词，答偏。
     *  - **按新到旧**：[History.Store.list] 本来就是新的在最前，直接取前 [n] 条，不用再排。
     *  - **一条 solve 都没有就返回空串**：调用方拿空串当「没这功能」，请求体逐字不变。
     *
     * 纯函数（不碰 Context / 文件），单测直接跑。相对时间复用 [ago]，和聊天状态行一个口径。
     */
    fun historySummary(list: List<History.Entry>, now: Long, n: Int = HISTORY_N): String {
        val rows = list.filter { it.kind == History.KIND_SOLVE }.take(n)
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("\n\n").append(HISTORY_HEAD).append('\n')
        for (e in rows) {
            sb.append(ago(e.id, now))
            val label = e.label.trim().removePrefix("· ").trim()
            if (label.isNotEmpty()) sb.append(" · ").append(label)
            sb.append("：").append(History.preview(e, HISTORY_CLIP)).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    // ---------------- 界面上那两行字 ----------------

    /** 空会话时气泡流里那一行。[MP] 按人格换一句 —— 四个模板各有各的开场白 */
    fun greeting(p: Persona? = null): String = Personas.greeting(p ?: Personas.p0())

    /** 顶部状态行：还没聊过 / 共 N 条 · 上次 3 分钟前 */
    // ---------------- v1.85.4 时间分隔 ----------------

    /** 两条消息间隔超过这个值，就在它们之间插一条时间分隔（用肉眼核对「几点说的」） */
    const val DIVIDER_GAP_MS = 10 * 60_000L

    /**
     * 时间分隔文案：这条消息（[at]）和上一条（[prevAt]，null = 会话第一条）隔得够远时给一行居中时间。
     * 纯函数（[now] 由调用方传，单测好钉）；格式：今天 HH:mm / 昨天 HH:mm / M月d日 HH:mm / 跨年带年份。
     */
    fun dividerText(prevAt: Long?, at: Long, now: Long): String? {
        if (prevAt != null && at - prevAt < DIVIDER_GAP_MS) return null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val hm = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = if (now > 0) now else at }
        fun sameDay(a: java.util.Calendar, b: java.util.Calendar): Boolean =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameDay(cal, nowCal) -> hm
            else -> {
                val yest = nowCal.clone() as java.util.Calendar
                yest.add(java.util.Calendar.DAY_OF_YEAR, -1)
                val md = "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 $hm"
                if (sameDay(cal, yest)) "昨天 $hm"
                else if (cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR)) md
                else "${cal.get(java.util.Calendar.YEAR)}年$md"
            }
        }
    }

    fun status(list: List<Msg>, now: Long): String =
        if (list.isEmpty()) "还没聊过" else "共 ${list.size} 条 · 上次${ago(list.last().at, now)}"

    /** 「刚刚 / 5 分钟前 / 3 小时前 / 10月7日」—— 只按时间差算，纯函数 */
    fun ago(at: Long, now: Long): String {
        val d = now - at
        return when {
            d < 60_000 -> "刚刚"
            d < 3600_000 -> "${d / 60_000} 分钟前"
            d < 86400_000 -> "${d / 3600_000} 小时前"
            else -> "${java.util.Calendar.getInstance().apply { timeInMillis = at }.let { c ->
                "${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}日"
            }}"
        }
    }

    // ---------------- Android 那层 ----------------

    /**
     * [MP] 读写 chat-<pid>.json（内存副本 + 后台落盘，同 Memo.Store）。同步方法：聊天页和它都在主线程调。
     *
     * [pid] = 人格 id（[Personas.P0] 默认）。每个人格一份会话文件 —— 切人格 = 换路径，
     * 读写逻辑零分支（[FileMemo] 本来就按路径共享）。
     *
     * 迁移：v1.69 只有一份 chat.json。第一次用 p0 打开时，若 chat-p0.json 不存在而旧的 chat.json 在，
     * 就把它改名成 chat-p0.json —— 老用户升上来聊天记录不丢，也不会被清空后重新长一份。
     */
    class Store(ctx: Context, val pid: String = Personas.P0) {
        private val dir = ctx.applicationContext.filesDir
        private val file = File(dir, file(pid))

        init { if (pid == Personas.P0) migrate(dir) }

        /** 旧 chat.json → chat-p0.json。改名成功就完事；失败（跨设备等）退回复制一份，再删旧的 */
        private fun migrate(dir: File) {
            val old = File(dir, FILE)
            try {
                if (!file.exists() && old.exists()) {
                    if (!old.renameTo(file)) { file.writeText(old.readText()); old.delete() }
                }
            } catch (_: Exception) {}
        }

        fun list(): List<Msg> = FileMemo.get(file) { parse(it) }
        private fun save(l: List<Msg>) = FileMemo.put(file, l) { toJson(it) }

        /** 记一条，返回真正写进去的那条（时间戳撞了会被挪过，所以返回它而不是入参） */
        @Synchronized fun say(m: Msg): Msg {
            val l = list()
            val at = if (l.any { it.at == m.at }) (l.maxOfOrNull { it.at } ?: 0L) + 1 else m.at
            val out = add(l, m.copy(at = at))
            save(out)
            return out.last()
        }

        /**
         * 清空会话：记录删掉，[AgentContext] 的摘要 sidecar 一起删 —— 留着只会把「已经清掉的旧事」
         * 又带进下一轮。人格被删时也走这里（[Personas.wipe]）。
         *
         * **和「核对 + 写摘要」共用 [AgentContext.LOCK]**：正在飞的那次压缩要么先完成、要么看到会话已变，
         * 不可能出现「清空之后摘要又写回来」。
         */
        @Synchronized fun clear() = synchronized(AgentContext.LOCK) {
            FileMemo.clear(file, emptyList<Msg>())
            AgentContext.SummaryStore(File(dir, AgentContext.file(pid))).clear()
            Unit
        }
    }
}