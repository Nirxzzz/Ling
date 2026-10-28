package com.chun.lingzhu

import org.json.JSONObject

/**
 * v2.4 主题包：把「一套界面长什么样」打包成一段可以发给别人的文本。
 *
 * ## 为什么单独一个文件
 * 一个主题包横跨四样东西：颜色（[ThemePalette]）、形状（[Shape]）、字号字重、间距圆角。
 * 它们本来分别住在 Theme / Shape / Prefs 三处，序列化时最容易出的错就是「漏了一个字段」——
 * 漏的字段导入后静默变成默认值，用户看不出是哪一步丢的。所以这里**只做数据搬运**，
 * 用一个显式的字段表 [FIELDS] 驱动读写，加字段只能改一处。
 *
 * ## 格式
 * 一行紧凑 JSON。选 JSON 不选自定义文本格式：
 *  - 项目零第三方依赖，org.json 是系统自带的，够用
 *  - 分享时一个字符串就能贴进聊天窗口，不用传文件
 *  - 以后加字段，老包照样能读（缺的字段用默认值）
 *
 * 形如：`LINGTHEME1:{"v":1,"name":"深夜纸","bg":...}`
 * 前缀是**协议标识 + 版本**，粘贴进来时先认这个前缀，不是主题包的文本一律拒绝，
 * 免得用户把别的字符串贴进来，得到一个「什么都没变」的诡异结果。
 */
object ThemePack {

    /** 协议前缀。后面的数字是**主版本**：将来不兼容地改结构就换它，老版本会明确拒绝而不是瞎猜 */
    const val PREFIX = "LINGTHEME1:"

    /** 字段上限：防止有人粘贴一段几 MB 的垃圾把界面卡住 */
    const val MAX_LEN = 4096

    /**
     * 一个主题包的全部内容。
     *
     * [colors] 是「颜色名 → ARGB」，用 Map 而不是一堆具名字段：
     * 以后加一个「强调色」只要往 [FIELDS] 加一行，不用改 data class 和所有构造点。
     */
    data class Pack(
        val name: String = "我的主题",
        val colors: Map<String, Int> = emptyMap(),
        val cornerDp: Float = Shape.BRUT.cornerDp,
        val strokeDp: Float = Shape.BRUT.strokeDp,
        val shadowDp: Float = Shape.BRUT.shadowDp,
        val textSp: Float = 15f,
        val spacingDp: Float = 12f,
    )

    /** 颜色字段表。键就是存进 [Pack.colors] 的名字，读写都走这里 —— 加字段只改这一行 */
    val FIELDS: List<String> = listOf(
        "bg", "bg2", "text", "answer", "heading", "orbA", "orbB",
        "paper", "card", "primary", "secondary", "highlight", "ink", "hint",
    )

    /** 从当前设置抽一份主题包出来 */
    fun capture(prefs: Prefs, name: String): Pack {
        val t = prefs.baseTheme()
        val c = LinkedHashMap<String, Int>()
        // 老式逐项配色 + 新式色板都收。同一个名字两边都有时以色板为准（它是「真源」）
        c["bg"] = t.bg
        t.bg2?.let { c["bg2"] = it }
        c["text"] = t.text
        c["answer"] = t.answer
        c["heading"] = t.heading
        c["orbA"] = t.orbA
        c["orbB"] = t.orbB
        t.palette?.let { pal ->
            c["paper"] = pal.paper
            c["card"] = pal.card
            c["primary"] = pal.primary
            c["secondary"] = pal.secondary
            c["highlight"] = pal.highlight
            c["ink"] = pal.ink
            c["hint"] = pal.hint
        }
        val s = prefs.effectiveShape()
        return Pack(
            name = name,
            colors = c,
            cornerDp = s.cornerDp,
            strokeDp = s.strokeDp,
            shadowDp = s.shadowDp,
            textSp = prefs.textSp.toFloat(),
            spacingDp = 12f,
        )
    }

    /** 序列化成一个可分享的字符串 */
    fun encode(p: Pack): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("name", p.name.take(24))
        o.put("corner", p.cornerDp.toDouble())
        o.put("stroke", p.strokeDp.toDouble())
        o.put("shadow", p.shadowDp.toDouble())
        o.put("textSp", p.textSp.toDouble())
        o.put("spacing", p.spacingDp.toDouble())
        val cols = JSONObject()
        // 按 FIELDS 顺序写，输出稳定 —— 同样的主题包两次导出得到同样的字符串，方便比对
        FIELDS.forEach { k -> p.colors[k]?.let { cols.put(k, hex(it)) } }
        o.put("colors", cols)
        return PREFIX + o.toString()
    }

    /**
     * 解析。任何不合规的输入都返回 null，**绝不抛异常** ——
     * 这是「用户粘贴了什么进来」的入口，抛异常会让整个设置页崩掉。
     */
    fun decode(raw: String?): Pack? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > MAX_LEN) return null
        if (!s.startsWith(PREFIX)) return null
        val body = s.substring(PREFIX.length)
        return try {
            val o = JSONObject(body)
            // 版本比我们新 → 拒绝。猜字段含义不如直接说「这个包是更新版本做的」
            if (o.optInt("v", 0) > 1) return null
            val colsObj = o.optJSONObject("colors")
            val cols = LinkedHashMap<String, Int>()
            FIELDS.forEach { k ->
                val h = colsObj?.optString(k, null) ?: return@forEach
                Theme.parseHex(h)?.let { cols[k] = it }   // 认不出的颜色丢掉，不猜
            }
            Pack(
                name = o.optString("name", "我的主题").take(24).ifBlank { "我的主题" },
                colors = cols,
                // 形状夹到合理区间：负数圆角会画出鬼东西，超大投影会糊满屏幕
                cornerDp = o.optDouble("corner", Shape.BRUT.cornerDp.toDouble()).toFloat().coerceIn(0f, 40f),
                strokeDp = o.optDouble("stroke", Shape.BRUT.strokeDp.toDouble()).toFloat().coerceIn(0f, 8f),
                shadowDp = o.optDouble("shadow", Shape.BRUT.shadowDp.toDouble()).toFloat().coerceIn(0f, 12f),
                textSp = o.optDouble("textSp", 15.0).toFloat().coerceIn(10f, 26f),
                spacingDp = o.optDouble("spacing", 12.0).toFloat().coerceIn(2f, 32f),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把包应用到设置上。
     *
     * 走 [Prefs.setThemeColor] 逐个写 —— 那里已经有「先从预设抄一份再改」的逻辑，
     * 自己另写一套会漏掉 bg2 / palette 的抄写规则。
     */
    fun apply(prefs: Prefs, p: Pack) {
        // 先落形状与字号，再落颜色：颜色那步会把 themeId 改成 custom，顺序反了不影响结果，
        // 但先做不会改 themeId 的部分，出错时更容易看出是哪一步没生效
        prefs.setShape(p.cornerDp, p.strokeDp, p.shadowDp)
        prefs.textSp = p.textSp.toInt().coerceIn(10, 26)
        FIELDS.forEach { k -> p.colors[k]?.let { prefs.setThemeColor(k, it) } }
    }

    /** 整数值转 #RRGGBB（丢掉 alpha —— 主题包只表达颜色，不表达透明度） */
    fun hex(v: Int): String = String.format("#%06X", v and 0xFFFFFF)
}
