package com.chun.lingzhu

import java.net.URI

/**
 * 联网搜索结果的「怎么显示」：只做判断，不画界面。
 *
 * 为什么单独一个文件：v2.3.2 之前搜索结果的展示完全靠 [WebSearch.Session.sourcesLine] 拼一行
 * 「来源：网址　网址　网址」——标题和摘要在登记白名单时就被丢掉了，用户看到的是一坨没法读的网址。
 * 现在要把每条结果排成「标题 / 域名 / 摘要」三行并支持折叠，这些判断（取标题、取域名、折叠几条、
 * 还剩几条、按钮该写什么）全是纯逻辑，抽出来才能单测，也不用起 Activity。
 *
 * 这个文件**不 import android.***：单测在 JVM 上直接跑。
 */
object WebSources {

    /** 折叠时默认显示几条。2 条大约占满手机屏宽的两屏内，再多就该让用户自己点开。 */
    const val PREVIEW = 2

    /** 摘要单行里最多留多少字。超过就截断加省略号——摘要是用来决定「要不要点」的，不是用来读完的。 */
    const val SUMMARY_MAX = 80

    /** 折叠时只显示前 [max] 条。[max] 为负按 0 处理，避免调用方传错时把整列都吐出来。 */
    fun preview(hits: List<WebSearch.Hit>, max: Int = PREVIEW): List<WebSearch.Hit> =
        hits.take(if (max < 0) 0 else max)

    /** 折叠时「还有 N 条」里的 N。已经全显示了就是 0，界面据此决定要不要画那一行。 */
    fun restCount(total: Int, shown: Int): Int = (total - shown).coerceAtLeast(0)

    /**
     * 每行的主标题：优先用搜索结果的 title；标题空（有些源就是不返回标题）才退回域名。
     * 两样都没有就返回空串 —— 界面会跳过这一样，而不是画一个「无标题」。
     */
    fun label(hit: WebSearch.Hit): String = hit.title.trim().ifBlank { domain(hit.url) }

    /**
     * 从网址里取域名（去掉 scheme、userinfo、端口、路径和开头的 www.）。
     *
     * 用 [URI] 解析而不是正则：正则要处理 IP、端口、国际化域名、带查询串的各种边角，写不全就是错的。
     * 解析失败（空串、相对路径、奇怪的输入）一律返回空串，由调用方决定怎么兜底。
     */
    fun domain(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""
        val host = try {
            URI(raw).host ?: return ""
        } catch (_: Exception) {
            return ""
        }
        val h = host.trim().lowercase()
        if (h.isEmpty()) return ""
        return if (h.startsWith("www.")) h.removePrefix("www.") else h
    }

    /**
     * 摘要那一行。取 snippet，没了才退回 content（自动补过正文的那几条）。
     * 两样都空就返回**空串**——绝不能填「无摘要」这种假话，界面据此直接不画这一行。
     * 压平换行再截断，否则多行摘要会把整个卡片撑得歪歪扭扭。
     */
    fun summary(hit: WebSearch.Hit, max: Int = SUMMARY_MAX): String {
        val text = (hit.snippet.ifBlank { hit.content }).replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return ""
        return if (text.length <= max) text else text.take(if (max < 1) 0 else max - 1).trimEnd() + "…"
    }

    /**
     * 折叠按钮的文案。已经全显示了就写「收起」；否则写「展开全部 N 条」，N = 总数（不是剩余数）——
     * 用户关心的是「点开能看到多少」，不是「还藏着多少」。
     */
    fun toggleText(expanded: Boolean, total: Int, shown: Int = PREVIEW): String =
        if (!expanded && total > shown) "展开全部 $total 条" else "收起"

    /** 要不要画折叠按钮：能折起来（总数 > 折叠条数）才有意义，否则一个点了没反应的按钮只是噪音。 */
    fun foldable(total: Int, shown: Int = PREVIEW): Boolean = total > shown

    /** 压成一行时把连续空白（含全角空格、换行、制表）当分隔符。 */
    private val WHITESPACE = Regex("""[\s　]+""")
}