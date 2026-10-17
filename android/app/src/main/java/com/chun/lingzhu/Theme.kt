package com.chun.lingzhu

/**
 * 配色（v1.55）：面板底色 / 不透明度 / 正文 / 答案行 / 标题 / 小球两端色。
 * 纯 Int 运算、不碰 android.graphics.Color，单元测试直接跑。
 * 次要文字、分隔线、按钮底都从「正文文字色」按透明度派生 —— 浅色底也不会出现白线白按钮。
 * 颜色的含义一律配文字（名字、亮度、对比度数字），不靠看颜色。
 *
 * v1.67：派生色算法改成「按 alpha 叠回底色」的显式模型（[flat]），深色底也能算出真实观感，
 * 于是深色预设和「跟随系统深浅色」（[effectiveId] / [darkVariantOf]）才站得住。
 *
 * v2.0.3：六套青春配色把「面板底 / 卡片底 / 品牌主副色 / 强调色 / 墨色 / 提示色」收进 [ThemePalette]；
 * 老的自定义配色（palette == null）继续走上面那套派生色，渲染完全不变。
 */

/**
 * 六套青春配色的角色色板（**RGB，不是 ARGB**）。
 * paper 是阅读背景（不饱和纯色），card 是卡片底，primary/secondary 是品牌主副色，highlight 是强调色，
 * ink 是正文墨色，hint 是由墨色与纸底之间取一档而来的提示色。老配色 / 自定义配色为 null。
 */
data class ThemePalette(
    val paper: Int,
    val card: Int,
    val primary: Int,
    val secondary: Int,
    val highlight: Int,
    val ink: Int,
    val hint: Int,
)

data class Theme(
    val bg: Int,        // 面板底色 RGB
    val opacity: Int,   // 面板不透明度 %（50~100）
    val text: Int,      // 正文文字
    val answer: Int,    // 顶部答案行
    val heading: Int,   // ## 标题
    val orbA: Int,      // 小球渐变左上
    val orbB: Int,      // 小球渐变右下
    /** v1.66：面板底色渐变的右下端（null = 纯色）。左上是 bg，右下是 bg2 —— 默认「护眼」就是薄荷绿 → 粉 */
    val bg2: Int? = null,
    /** v2.0.3：六套青春配色的角色色板（RGB）；老配色 / 自定义为 null，继续走上面的派生色 */
    val palette: ThemePalette? = null,
) {
    val surface: Int get() = argb(opacity * 255 / 100, bg)
    /** 渐变右下端（带不透明度）；纯色时等于 surface */
    val surfaceEnd: Int get() = argb(opacity * 255 / 100, bg2 ?: bg)
    /** 面板底色的两端（对比度要两端都够） */
    val bgs: List<Int> get() = listOfNotNull(bg, bg2)
    val card: Int get() = argb(Math.min(100, opacity + 3) * 255 / 100, bg)
    val tx1: Int get() = argb(0xFF, text)
    val tx2: Int get() = argb(0xCC, text)
    val tx3: Int get() = argb(0x99, text)
    val hairline: Int get() = argb(0x33, text)
    val pillFill: Int get() = argb(0x1F, text)
    val handle: Int get() = argb(0x66, text)
    val codeBg: Int get() = argb(0x26, text)

    // ---- v1.67：深色底适配 ----
    /**
     * 半透明前景叠在**不透明**底色上的真实观感（纯 Int 运算）。
     * 浅色底上「深色文字 60%」和深色底上「浅色文字 60%」都是把 alpha 叠回底色 ——
     * 有了这个才能在测试里断言派生色在深底上也够清楚（面板半透明时最终观感由它决定）。
     */
    fun flat(alpha: Int, overRgb: Int = bg): Int = argb(0xFF, mix(overRgb, text, alpha.coerceIn(0, 255) / 255f))

    /** 面板底是不是深色（底暗、字亮）—— 决定这套算深色系 */
    val isDark: Boolean get() = luminance(bg) < 0.4

    /** 正文 / 答案 / 标题里最差的那个对比度（渐变底两端都算） */
    fun minContrast(): Double = listOf(text, answer, heading).flatMap { fg -> bgs.map { contrast(fg, it) } }.min()

    /** 派生色（次要文字 / 提示 / 分隔线）在底上的最差对比度 —— 深浅底都能报一个数出来 */
    fun derivedContrast(): Double = listOf(0xCC, 0x99, 0x66).flatMap { a -> bgs.map { contrast(flat(a, it), it) } }.min()
    /** 小球渐变四个色标：两端 + 各自提亮一点的中间色（同 v1.54 前的薄荷→玫瑰观感） */
    val orbStops: IntArray get() = intArrayOf(argb(0xFF, orbA), argb(0xFF, mix(orbA, 0xFFFFFF, 0.25f)), argb(0xFF, mix(orbB, 0xFFFFFF, 0.25f)), argb(0xFF, orbB))

    companion object {
        const val CUSTOM = "custom"
        /** v1.66 默认配色：护眼（薄荷绿 → 粉渐变）。没选过配色的人（含老用户没动过的）都用它 */
        const val DEFAULT = "eye"

        val DARK = Theme(0x1C1C1E, 95, 0xF2F2F2, 0xF2F2F2, 0xF2F2F2, 0x5FDFB4, 0xF29FB0)
        /** 护眼：面板底左上薄荷绿、右下粉（浅一档，深色字），小球同样薄荷 → 粉 */
        val EYE = Theme(0xCDEFE0, 97, 0x1F2D28, 0x0F1F19, 0x1F3A30, 0x5FDFB4, 0xF29FB0, bg2 = 0xF7D4DE)
        // ---------------- v2.0.3 六套青春配色 ----------------
        // 已下架的老配色不再保留 Theme 对象：老用户存着的 id 由 [normId] 按色系就近迁移到下面六套之一，
        // 存的值不主动改写（见 [MIGRATE]）。默认「护眼」及它的深色伴生 night_mint 原样保留。
        /** 提示色：在纸底与墨色之间取一档，读得清又明显比正文淡 */
        private const val HINT_MIX = 0.72f

        /** 浅色主题：纸底 + 白卡，正文 / 答案 / 标题统一用墨色；小球用品牌主 / 副色 */
        private fun mkLight(paper: Int, primary: Int, secondary: Int, highlight: Int, ink: Int): Theme =
            Theme(paper, 97, ink, ink, ink, primary, secondary,
                palette = ThemePalette(paper, 0xFFFFFF, primary, secondary, highlight, ink, mix(paper, ink, HINT_MIX)))

        /** 深色主题：同色系深底 + 亮墨，品牌主 / 副 / 强调色沿用原色（保色相），卡片底比纸底略提亮 */
        private fun mkDark(paper: Int, primary: Int, secondary: Int, highlight: Int, ink: Int): Theme =
            Theme(paper, 96, ink, ink, ink, primary, secondary,
                palette = ThemePalette(paper, mix(paper, 0xFFFFFF, 0.08f), primary, secondary, highlight, ink, mix(paper, ink, HINT_MIX)))

        /** id → (名字, 配色)。名字里写明深浅，灰度屏上也知道选的是什么 */
        val PRESETS: List<Triple<String, String, Theme>> = listOf(
            Triple(DEFAULT, "护眼（薄荷绿 → 粉渐变，深色字，默认）", EYE),
            // 六套青春配色（画廊可选的六张）
            Triple("candy_pop", "糖果 · Candy", mkLight(0xFFFDF8, 0xFF6BCE, 0x845EC2, 0xFFD93D, 0x242038)),
            Triple("cyber_pop", "赛博 · Cyber", mkLight(0xF0F7FF, 0x4361EE, 0x7209B7, 0x4CC9F0, 0x171738)),
            Triple("fresh_pop", "清新 · Fresh", mkLight(0xFFFDF5, 0x38B48B, 0x7AE7C7, 0xFFF0A6, 0x183D35)),
            Triple("dopamine_pop", "多巴胺 · Dopamine", mkLight(0xFFF8F0, 0xFF6B6B, 0xFF9F1C, 0xFFB3C6, 0x33272A)),
            Triple("acid_green", "酸绿 · Acid", mkLight(0xF5F5F0, 0xC6F432, 0xDFFF00, 0xA3A3A3, 0x151515)),
            Triple("soft_digital", "柔彩 · Soft", mkLight(0xF8F9FF, 0xA5D8FF, 0xCDB4DB, 0xFFC8DD, 0x25243A)),
            // 默认「护眼」的深色伴生：字段与 palette 都原样保留
            Triple("night_mint", "夜航 · 夜薄荷（深绿底浅字）", Theme(0x0F2019, 96, 0xE0F0E8, 0x7DE3C0, 0xD0E8DC, 0x3FA07A, 0xD0708C)),
            // 六套青春配色的深色变体：同色相，只把纸底压暗、墨色提亮（内部用，不进画廊）
            Triple("candy_pop_dark", "糖果 · 深色 · Candy Dark", mkDark(0x211A26, 0xFF6BCE, 0x845EC2, 0xFFD93D, 0xF2E9F1)),
            Triple("cyber_pop_dark", "赛博 · 深色 · Cyber Dark", mkDark(0x14162A, 0x4361EE, 0x7209B7, 0x4CC9F0, 0xE8EBFA)),
            Triple("fresh_pop_dark", "清新 · 深色 · Fresh Dark", mkDark(0x11231C, 0x38B48B, 0x7AE7C7, 0xFFF0A6, 0xE2F2EA)),
            Triple("dopamine_pop_dark", "多巴胺 · 深色 · Dopamine Dark", mkDark(0x251A17, 0xFF6B6B, 0xFF9F1C, 0xFFB3C6, 0xFBE8E1)),
            Triple("acid_green_dark", "酸绿 · 深色 · Acid Dark", mkDark(0x1C1E13, 0xC6F432, 0xDFFF00, 0xA3A3A3, 0xF0F3E2)),
            Triple("soft_digital_dark", "柔彩 · 深色 · Soft Dark", mkDark(0x171927, 0xA5D8FF, 0xCDB4DB, 0xFFC8DD, 0xEBEDF7)),
        )
        fun preset(id: String): Theme? = PRESETS.firstOrNull { it.first == id }?.third

        /**
         * v2.0.3 老配色 id → 六套新配色里色系最近的一套。
         * 这是**已存数据的迁移**：老用户 SP 里的 themeId 仍是老 id，读的时候按这张表落到新配色；
         * 不主动改写 SP 里的值（[Prefs.themeId] 只在内存里归一化），所以换回旧版 App 也读得出来。
         * 六套新 id / eye / custom / 内置 night_mint 都能直接用原来的 id，不进这张表
         * （night_mint 是默认深色伴生，必须原样可解析 —— 见 [normId] 先查 [preset]）。表外的未知 id 一律回默认「护眼」。
         */
        val MIGRATE: Map<String, String> = mapOf(
            // 冷蓝紫系 → 赛博
            "dark" to "cyber_pop", "black" to "cyber_pop", "night" to "cyber_pop", "midnight" to "cyber_pop",
            "night_deep" to "cyber_pop", "night_ink" to "cyber_pop", "ocean_deep" to "cyber_pop",
            "ocean_shallow" to "cyber_pop", "ocean_sky" to "cyber_pop", "morandi_blue" to "cyber_pop",
            "dopa_sky" to "cyber_pop", "jelly_sky" to "cyber_pop", "retro_navy" to "cyber_pop",
            // 薄荷绿系 → 清新（night_mint 除外：它是内置默认深色，直接命中）
            "morandi_sage" to "fresh_pop", "macaron_mint" to "fresh_pop", "forest" to "fresh_pop",
            "moss" to "fresh_pop", "jelly_mint" to "fresh_pop", "wabi_moss" to "fresh_pop",
            "retro_green" to "fresh_pop", "night_forest" to "fresh_pop",
            // 粉紫系 → 糖果
            "morandi_rose" to "candy_pop", "macaron_berry" to "candy_pop", "dopa_grape" to "candy_pop",
            "dopa_mix" to "candy_pop", "jelly_berry" to "candy_pop", "night_plum" to "candy_pop",
            // 暖橘粉系 → 多巴胺
            "morandi_oat" to "dopamine_pop", "macaron_lemon" to "dopamine_pop", "clay" to "dopamine_pop",
            "sepia" to "dopamine_pop", "dopa_lemon" to "dopamine_pop", "dopa_coral" to "dopamine_pop",
            "jelly_mango" to "dopamine_pop", "wabi_ash" to "dopamine_pop", "wabi_clay" to "dopamine_pop",
            "wabi_sand" to "dopamine_pop", "wabi_ink" to "dopamine_pop", "retro_wine" to "dopamine_pop",
            "retro_cream" to "dopamine_pop", "retro_night" to "dopamine_pop", "night_sand" to "dopamine_pop",
            // 柔蓝紫系 → 柔彩
            "light" to "soft_digital", "macaron_taro" to "soft_digital", "macaron_mix" to "soft_digital",
            "jelly_grape" to "soft_digital",
            // 烟灰 → 酸绿（唯一一套本来就低饱和灰调的新配色）
            "morandi_ink" to "acid_green",
        )

        /**
         * 存着的配色 id → 现在实际用的 id（没存过 / 未知 → 默认护眼）。
         * 六套新配色与内置 night_mint 直接命中；老 id 按 [MIGRATE] 就近迁移；custom 原样。
         */
        fun normId(id: String?): String {
            if (id == CUSTOM) return CUSTOM
            if (id != null && preset(id) != null) return id
            if (id != null) MIGRATE[id]?.let { return it }
            return DEFAULT
        }

        /**
         * v2.0.3 画廊精简成六套青春配色 + 默认「护眼」：外观页只摆这七张 + 一个自定义入口。
         * 六套各自的深色变体和内置 night_mint 只是「跟随系统深色」时换的背景，不进画廊。
         */
        val HOT_IDS: List<String> = listOf(
            DEFAULT, "candy_pop", "cyber_pop", "fresh_pop", "dopamine_pop", "acid_green", "soft_digital",
        )

        // ---------------- v2.0.3 跟随系统深浅色 ----------------
        /**
         * 浅色配色 → 它的伴生深色变体。默认「护眼」沿用它原来的 night_mint；六套青春配色各有同色相的 *_dark。
         * 本来就是深色的（night_mint / *_dark）映射到自己；自定义没有伴生 —— 统一落到 [DARK_FALLBACK]。
         * 只按 id 查表，不碰系统状态，纯函数。
         */
        val DARK_COMPANION: Map<String, String> = mapOf(
            DEFAULT to "night_mint",
            "candy_pop" to "candy_pop_dark",
            "cyber_pop" to "cyber_pop_dark",
            "fresh_pop" to "fresh_pop_dark",
            "dopamine_pop" to "dopamine_pop_dark",
            "acid_green" to "acid_green_dark",
            "soft_digital" to "soft_digital_dark",
        )
        /** 浅色配色没有伴生深色时的兜底（自定义也落到它）—— 就近用同色系的赛博深色 */
        const val DARK_FALLBACK = "cyber_pop_dark"

        /**
         * 这个预设是不是深色系（面板底深）。
         * 深色预设的伴生就是自己 —— 切深色时它不动，也不该被换成另一套深色。
         */
        fun isDarkPreset(id: String): Boolean = preset(id)?.isDark ?: false

        /**
         * 系统切到深色时，一个浅色预设该换成哪套（[DARK_COMPANION]；没配 → [DARK_FALLBACK]）。
         * 本来就是深色 / 自定义之外的不存在 id → 原样返回。纯函数，不读系统。
         */
        fun darkVariantOf(id: String): String {
            val k = normId(id)
            if (k == CUSTOM) return DARK_FALLBACK
            val t = preset(k) ?: return DARK_FALLBACK
            if (t.isDark) return k
            return DARK_COMPANION[k] ?: DARK_FALLBACK
        }

        /**
         * 实际该用的预设 id（跟随系统深浅色）。
         * @param stored 用户存的 themeId（老用户升级后原样保留 —— 这里只算「用哪套」，不改存的值）
         * @param systemDark 系统是不是深色
         * @param follow 「跟随系统深浅色」开关（默认开）
         */
        fun effectiveId(stored: String?, systemDark: Boolean, follow: Boolean): String {
            val k = normId(stored)
            if (!follow || !systemDark) return k
            return darkVariantOf(k)
        }

        /** 取色列表：名字 + 色号。亮度在界面上另算另显示 */
        val NAMED: List<Pair<String, Int>> = listOf(
            "白" to 0xFFFFFF, "浅灰" to 0xD9D9D9, "灰" to 0x8E8E93, "深灰" to 0x3A3A3C, "炭黑" to 0x1C1C1E, "黑" to 0x000000,
            "米黄" to 0xF4ECD8, "浅薄荷" to 0xCDEFE0, "浅粉" to 0xF7D4DE, "亮黄" to 0xFFE066, "橙" to 0xFF9F0A, "红" to 0xFF453A, "粉" to 0xF29FB0, "紫" to 0xBF5AF2,
            "蓝" to 0x0A84FF, "天蓝" to 0x64D2FF, "绿" to 0x30D158, "薄荷绿" to 0x5FDFB4, "深棕" to 0x3B2F20,
        )
        fun nameOf(rgb: Int): String? = NAMED.firstOrNull { it.second == (rgb and 0xFFFFFF) }?.first

        fun argb(a: Int, rgb: Int): Int = (a.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        /** "#RGB" / "#RRGGBB"，# 可省，大小写都行；不合法返回 null */
        fun parseHex(s: String): Int? {
            var h = s.trim().removePrefix("#").removePrefix("＃")
            if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
            if (h.length != 6 || h.any { it !in "0123456789abcdefABCDEF" }) return null
            return h.toInt(16)
        }
        fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int) = Math.round(((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t)
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** WCAG 相对亮度 0~1 */
        fun luminance(rgb: Int): Double {
            fun ch(sh: Int): Double { val c = ((rgb shr sh) and 0xFF) / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }
        /** 亮度百分比（给人看的，0 = 纯黑，100 = 纯白） */
        fun brightness(rgb: Int): Int = Math.round(Math.sqrt(luminance(rgb)) * 100).toInt()

        /** WCAG 对比度 1~21 */
        fun contrast(fg: Int, bg: Int): Double {
            val a = luminance(fg); val b = luminance(bg)
            return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
        }
        fun contrastWord(r: Double): String = when {
            r >= 7 -> "很清楚"
            r >= 4.5 -> "清楚"
            r >= 3 -> "偏淡，大字还行"
            else -> "太淡，看不清"
        }
    }
}
