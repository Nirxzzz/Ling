package com.chun.lingzhu

import android.content.Context

/**
 * 内置词库（v1.74 起）：assets/wordbank/ 下每级一个 .txt。
 *  - junior / gaokao / cet4 / cet6 / ielts：**每行一个单词**，背面留空，释义靠预翻译或点「查」补。
 *
 * 本开源副本只包含上述 5 个 Wiktionary 词频表（CC BY-SA 3.0，可商用、可再分发，需署名）。
 * 原发行版中的考研三级词库是受版权保护的第三方内容，已从开源副本中整体移除；
 * 富文本词库解析器（[Words.parseRich]）仍保留，供用户自行导入合法来源的词库使用。
 * 来源与授权见 assets/wordbank/LICENSE.txt。
 *
 * v1.77：**开箱可选，不再需要导入**。[levelDecks] 直接把它们当成普通词库（id 就是 [deckId]，
 * 与老版本导入后生成的 id 相同，所以老用户的勾选与进度天然并轨，不需要迁移）。
 * 正文走 [cachedText]（整进程只读一次 assets）+ [Words.parseMemoized]，
 * 设置页渲染摘要、弹词挑卡这些热路径上一次 IO 都没有。
 */
object WordBank {
    const val DIR = "wordbank"
    const val DECK_PREFIX = "wb_"
    const val LICENSE_ASSET = "$DIR/LICENSE.txt"
    /** 署名摘要（设置页展示用，全文在 LICENSE.txt） */
    const val LICENSE_NOTE = "Wiktionary 词频表（CC BY-SA 3.0，署名见 assets/wordbank/LICENSE.txt）"

    data class Level(val id: String, val name: String, val file: String)

    /** 分级列表；顺序即展示顺序 */
    val LEVELS = listOf(
        Level("junior", "基础（初中向）", "junior.txt"),
        Level("gaokao", "高考", "gaokao.txt"),
        Level("cet4", "CET4", "cet4.txt"),
        Level("cet6", "CET6", "cet6.txt"),
        Level("ielts", "雅思拓展", "ielts.txt"),
    )

    fun levelById(id: String) = LEVELS.firstOrNull { it.id == id }

    /** 词库 id（清单里那张内置词库的 id；与老版本导入后生成的 id 相同） */
    fun deckId(l: Level) = DECK_PREFIX + l.id

    /**
     * 新装默认勾选的词库：第一级（junior），它确实存在于本开源副本的 assets 中。
     *
     * 原发行版默认勾选考研三级；那些词库因版权原因未随本副本分发，默认项不能继续指向
     * 不存在的资产。只改「键不存在时」的默认值：已经动过这个开关的用户保持原样。
     */
    val DEFAULT_ON: Set<String> = listOf(deckId(LEVELS.first())).toSet()

    /** 去 BOM（有些编辑器会加 \uFEFF）；兼容 CRLF */
    fun stripBom(text: String) = text.removePrefix("\uFEFF").replace("\r\n", "\n")

    /** assets 里实际存在的分级（按 LEVELS 顺序；测试环境没有 assets，返回空列表） */
    fun availableLevels(context: Context): List<Level> = try {
        val files = context.assets.list(DIR)?.toSet() ?: emptySet()
        LEVELS.filter { it.file in files }
    } catch (_: Exception) { emptyList() }

    /** 读某一级的原文（包含 # 注释也交给 Words.parse 处理） */
    fun readText(context: Context, l: Level): String = context.assets.open("$DIR/${l.file}").bufferedReader(Charsets.UTF_8).use { it.readText() }

    // ---- v1.77：开箱可选（不再导入）· 正文与分级清单都进进程内缓存 ----
    // 词库清单在热路径上会被反复取：设置页每重建一次词库区块、弹一次词、算一次错词数都要一份。
    // 原来只有解析结果被缓存，assets 的 IO 仍在每次调用里发生 —— 现在把「列目录」和「读正文」一起缓存住。
    // 缓存上限就是 [LEVELS] 的大小（安装包自带、进程内不变），条目不会涨，不需要淘汰。

    private var levelListMemo: List<Level>? = null
    private val textMemo = HashMap<String, String>()

    /** [availableLevels] 的缓存版（assets.list 也是 IO）；一次没读到（老包 / assets 缺失）也不重复问 */
    @Synchronized
    fun cachedLevels(context: Context): List<Level> = levelListMemo ?: availableLevels(context).also { levelListMemo = it }

    /** [readText] 的缓存版，顺手去 BOM / 归一换行；同一级只真读一次 assets */
    @Synchronized
    fun cachedText(context: Context, l: Level): String = textMemo.getOrPut(l.file) { stripBom(readText(context, l)) }

    /**
     * 纯函数层：把若干分级变成普通词库（id = [deckId]，名字 = 级名，正文 = 原文）。
     * 正文由 [source] 给，测试注入假实现即可，不必碰 assets —— 这里不做 IO，可以直接单测。
     * 顺序即入参顺序（[LEVELS] 的顺序就是设置页展示顺序）。
     */
    fun decksOf(levels: List<Level>, source: (Level) -> String): List<Words.Deck> =
        levels.map { l -> Words.Deck(deckId(l), l.name, source(l)) }

    /** 开箱可选的内置词库清单：assets 里有的分级 + 缓存正文。IO 只在进程第一次发生 */
    fun levelDecks(context: Context): List<Words.Deck> = decksOf(cachedLevels(context)) { cachedText(context, it) }

    /**
     * 提前把分级清单与正文读进缓存。调用方放后台线程：[levelDecks] 本身是 [Synchronized] 的，
     * 预热没跑完时主线程来取也只是同一把锁等着，不会读到半截状态。
     */
    fun warm(context: Context) {
        cachedLevels(context).forEach { cachedText(context, it) }
    }

    /**
     * 完整词库清单的顺序：内置英语 → 5 级 → 自定义。纯函数，单测直接验顺序。
     * [Prefs.deckList] / [Prefs.allDecks] 都以它为准，两边不会走岔。
     */
    fun assemble(builtin: Words.Deck, levels: List<Words.Deck>, custom: List<Words.Deck>): List<Words.Deck> =
        listOf(builtin) + levels + custom

    /** 这个 id 是不是内置分级词库（设置页据此不给「编辑 ▸」，正文来自 assets 改不了） */
    fun isLevelDeck(id: String) = id.startsWith(DECK_PREFIX) && levelById(id.substring(DECK_PREFIX.length)) != null
}
