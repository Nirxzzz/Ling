package com.chun.lingzhu

import java.util.Calendar

/**
 * 会话列表（仿微信聊天列表）的纯逻辑：一行一个 Agent 的摘要、时间标签、未读数、排序、搜索。
 *
 * 和项目其它模块同一个分工：这里**不 import android.***，JVM 单测直接跑（[ChatListTest]）；
 * 画界面在 [ChatListActivity]，读写「已读位置 / 置顶」在 [ChatListActivity.Marks]。
 *
 * 未读的口径：原数据没有「未读」概念，这里定义为「**上次离开这个会话之后，Agent 新回的消息条数**」。
 * 没有已读记录（老用户升级上来、从没打开过列表）一律按 0 算 —— 宁可少报，也别一升级就给每个会话
 * 挂上 200 条未读。
 */
object ChatList {

    /** 列表里的一行。[lastAt] = 0 表示还没聊过 */
    data class Session(
        val pid: String,
        val name: String,
        val summary: String,
        val lastAt: Long,
        val unread: Int,
        val pinned: Boolean,
        val order: Int,          // 人格在 personas.json 里的顺序：同时间 / 都没聊过时按它排，结果稳定
    )

    /** 摘要最多留多少字（再长界面也只显示一行，多留只是白占内存） */
    const val SUMMARY_CHARS = 60

    /**
     * 一条消息压成单行摘要：换行 / 连续空白并成一个空格，去掉最常见的 Markdown 记号；
     * 只有图没有字 → 「[图片]」。不加「小Ling：」前缀 —— 一行就是一个 Agent 的会话，跟微信单聊一样不重复写名字。
     */
    fun summary(m: ChatStore.Msg?): String {
        if (m == null) return ""
        val t = m.content
            .replace(Regex("```[a-zA-Z0-9]*"), " ")
            .replace(Regex("[*_`#>]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val body = if (t.isEmpty() && m.img != null) ChatStore.IMG_PLACEHOLDER
                   else if (m.img != null) ChatStore.IMG_PLACEHOLDER + " " + t
                   else t
        return if (body.length > SUMMARY_CHARS) body.take(SUMMARY_CHARS) else body
    }

    /**
     * 右上角时间：今天 `HH:mm`、昨天「昨天」、今年更早 `M月d日`、跨年 `yyyy/M/d`。
     * [now] 由调用方传（单测好钉）；期望值都按本机时区，不写死 UTC。
     */
    fun timeLabel(at: Long, now: Long): String {
        if (at <= 0L) return ""
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(c, n) || at > now) return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val y = (n.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (same(c, y)) return "昨天"
        val md = "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
        return if (c.get(Calendar.YEAR) == n.get(Calendar.YEAR)) md
               else "${c.get(Calendar.YEAR)}/${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}"
    }

    /** 未读 = 已读位置之后 Agent 说的条数；[readAt] = null（从没记过）按 0 */
    fun unread(list: List<ChatStore.Msg>, readAt: Long?): Int =
        if (readAt == null) 0 else list.count { it.isOrb && it.at > readAt }

    /** 角标文字：1~99 原样，再多写「99+」；0 不显示 */
    fun badge(n: Int): String = when {
        n <= 0 -> ""
        n > 99 -> "99+"
        else -> n.toString()
    }

    /** 顶栏标题：有未读时「聊天(N)」，没有就「聊天」 */
    fun title(totalUnread: Int, base: String = "聊天"): String =
        if (totalUnread > 0) "$base(${badge(totalUnread)})" else base

    /**
     * 排序：置顶的在前；同一组里按最后消息时间新→旧；都没聊过的放在最后，按人格原顺序。
     * 同时间按人格原顺序 —— 结果完全确定，重画不会跳。
     */
    fun sort(list: List<Session>): List<Session> =
        list.sortedWith(compareBy<Session>({ !it.pinned }, { -it.lastAt }, { it.order }))

    fun totalUnread(list: List<Session>): Int = list.sumOf { it.unread }

    /**
     * 搜索：名字或任一条消息正文包含关键字（不分大小写）。命中消息时摘要换成**最近一条命中的消息**，
     * 时间也跟着换成那条的时间 —— 用户搜的是那句话，就该看见那句话。
     * [msgs] = pid → 这个会话的全部消息。空关键字 = 原列表。
     */
    fun search(list: List<Session>, msgs: Map<String, List<ChatStore.Msg>>, q: String): List<Session> {
        val k = q.trim()
        if (k.isEmpty()) return list
        return list.mapNotNull { s ->
            val hit = msgs[s.pid].orEmpty().lastOrNull { it.content.contains(k, ignoreCase = true) }
            when {
                hit != null -> s.copy(summary = summary(hit), lastAt = hit.at)
                s.name.contains(k, ignoreCase = true) -> s
                else -> null
            }
        }
    }

    /**
     * 必要配置提示条的一句话（聊天页和会话列表共用；原来写在 ChatActivity 里，原文照搬）。
     * 两项都齐返回 null = 不显示。
     */
    fun setupHint(noOverlay: Boolean, noApi: Boolean): String? = when {
        noOverlay && noApi -> "还差 2 项：接口地址、悬浮窗权限 · 点这里去设置"
        noApi -> "还没连接模型 —— 填上接口地址才能聊 · 点这里去设置"
        noOverlay -> "悬浮球未授权（识别屏幕 / 弹单词要用）· 点这里去开启"
        else -> null
    }

    /** 首字头像：取名字第一个字（按 code point，不劈半个字）；空名字给「A」 */
    fun initial(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "A"
        return String(Character.toChars(Character.codePointAt(t, 0))).uppercase()
    }
}
