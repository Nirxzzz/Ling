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
    // ---------------- v2.5 Mint Rose Atelier 追加槽位 ----------------
    // 全部带默认值，且默认值取「老配色里最接近的那个既有槽位」——
    // 这样六套青春配色 / 瑞士 / 护眼**一个字节都不用改**，渲染也逐位不变；
    // 只有新配色（MINT_ROSE / MINT_ROSE_DARK）才显式填这些槽位。
    /** 卡片底之外的第二层表面（输入框、内嵌面板、次级卡片） */
    val surfaceAlt: Int = paper,
    /** 玫瑰金深档：边框 / 图标细节 / 强调文字（浅底上能承载小字号的深度） */
    val roseDeep: Int = secondary,
    /** 雾霾紫：创意功能、个性化内容卡片、特殊状态 */
    val lavender: Int = highlight,
    /** 冰川蓝：信息辅助、次级模块、清凉过渡 */
    val glacier: Int = highlight,
    /** 蜜桃珊瑚：少量提醒 / 活动元素 */
    val coral: Int = highlight,
    /** 奶油黄：趣味元素 / 成就 */
    val butter: Int = highlight,
    // ---------------- v2.7.3 液态玻璃 ----------------
    /** 玻璃材质：Ui.material 把卡面画成「轻透明层 + 高光细边 + 柔和阴影」（统一清透材质，无实时模糊）。默认 false，老配色零影响 */
    val glassMaterial: Boolean = false,
) {
    /**
     * 这套色板是不是「分层圆角 + 柔和阴影 + 玫瑰金细边」那一族的配色（v2.5 Mint Rose）。
     *
     * 为什么不判断 id：渲染层（Brut）拿到的只有色板，没有 id；而且以后加同族配色只要
     * 让 surfaceAlt != paper 就自动继承这套视觉。老配色 surfaceAlt 默认等于 paper，恒为 false。
     */
    val isAtelier: Boolean get() = surfaceAlt != paper
}

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
    /**
     * v2.4：这套配色是否配合「无边框 / 无投影」的紧凑形状（目前只有瑞士）。
     *
     * 为什么要这个开关：有边框和投影时，弱化文字淡一点还能靠框线定位；没有框线时，
     * 40% 那档文字会和纸底糊在一起（实测对比度 2.58，低于「看得出来」的 3.0）。
     * 配色自己声明这件事，而不是让渲染层去猜 id —— 以后加同类风格只要置 true。
     */
    val noShadowStyle: Boolean = false,
) {
    /** v2.7.3：这套是不是液态玻璃材质（palette.glassMaterial）。渲染层据此走玻璃分支，判色板不猜 id */
    val isGlassMaterial: Boolean get() = palette?.glassMaterial == true
    val surface: Int get() = argb(opacity * 255 / 100, bg)
    /** 渐变右下端（带不透明度）；纯色时等于 surface */
    val surfaceEnd: Int get() = argb(opacity * 255 / 100, bg2 ?: bg)
    /** 面板底色的两端（对比度要两端都够） */
    val bgs: List<Int> get() = listOfNotNull(bg, bg2)
    /** 卡片底：色板存在时统一用 palette.card（和 Brut.CARD 同一来源，不再拿 bg 冒充卡面）；老配色 / 自定义沿用 bg 推导 */
    val card: Int get() = argb(Math.min(100, opacity + 3) * 255 / 100, palette?.card ?: bg)
    val tx1: Int get() = argb(0xFF, text)
    val tx2: Int get() = argb(0xCC, text)
    val tx3: Int get() = argb(if (noShadowStyle) 0xB0 else 0x99, text)
    val hairline: Int get() = argb(if (noShadowStyle) 0x55 else 0x33, palette?.ink ?: text)
    val pillFill: Int get() = argb(0x1F, text)
    val handle: Int get() = argb(if (noShadowStyle) 0x88 else 0x66, text)
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
    fun derivedContrast(): Double {
        // v2.4：以前这里写死 0xCC / 0x99 / 0x66 三档，但它量的应该是**实际用到的**那几档。
        // 紧凑风格（瑞士）把各档都提亮了，写死的数字就量不到真实观感 —— 量一个不存在的颜色没有意义。
        val levels = if (noShadowStyle) listOf(0xCC, 0xB0, 0x88) else listOf(0xCC, 0x99, 0x66)
        return levels.flatMap { a -> bgs.map { contrast(flat(a, it), it) } }.min()
    }
    // ---------------- v2.7.3 液态玻璃：卡面渐变两端的纯派生（渲染与测试同一个函数） ----------------
    /**
     * 玻璃卡面渐变**上端**（ARGB，alpha 随传入填色）：浅色保持 0.10 的白高光；深色只提 0.02 ——
     * 深底上白高光每多提一分，卡面合成到纯白底的正文对比就掉一分（0.10 时 floor×白底×标题只剩 5.68）。
     * 纯 Int 运算、零分配：Ui.glassMaterialOf 画的和 GlassThemeTest 量的是同一个函数。
     */
    fun glassTop(color: Int): Int = argb(color ushr 24, mix(color and 0xFFFFFF, 0xFFFFFF, if (isDark) 0.02f else 0.10f))

    /**
     * 玻璃卡面渐变**下端**（ARGB，alpha 随传入填色）：浅色向墨收 0.04（上亮下沉）；深色**保持基色** ——
     * 深色的 ink 是亮字色，混下去只会把下端提亮（0.04 时 floor×白底×标题只剩 6.78 < 7）。
     * 纯 Int 运算、零分配，和 [glassTop] 同一套纪律。
     */
    fun glassBottom(color: Int): Int = argb(color ushr 24, if (isDark) (color and 0xFFFFFF) else mix(color and 0xFFFFFF, palette?.ink ?: 0x141414, 0.04f))

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

        // ---------------- v2.4 瑞士国际主义 ----------------
        /**
         * 瑞士国际主义（Müller-Brockmann 一路）：纸底近白、墨色近黑、只有**一个**信号红。
         *
         * 为什么是这套颜色：
         *  - 纸底 0xF2F2F0 不是纯白 —— 纯白在 AMOLED 上刺眼，且没有「印刷纸」的质感。
         *  - 墨色 0x111111 不是纯黑 —— 纯黑与纸底对比过猛，大段正文会累。
         *  - 主色用信号红的深版 0xE02B20（海报里那种正红），副色用同一个红压暗，强调色用中灰。
         *    瑞士风格不要第二、第三个彩色 —— 一旦多一个颜色就不再是这套语言了。
         *  - 没有 bg2（无渐变）：渐变是装饰，这套风格靠网格和留白，不靠过渡。
         */
        val SWISS = Theme(0xF2F2F0, 99, 0x111111, 0x111111, 0x111111, 0xE02B20, 0xB31F17,
            palette = ThemePalette(
                paper = 0xF2F2F0, card = 0xFFFFFF,
                primary = 0xE02B20, secondary = 0xB31F17, highlight = 0x9A9A96,
                // hint 取 0.62 这一档：比原来的 0.55 更靠墨色（对比度 4.06 → 5.12），
                // 又比通用 HINT_MIX 0.72 略淡，仍是「明显比正文淡」的一档 ——
                // 瑞士没有边框和投影帮忙分隔，提示灰太淡就和纸底糊成一片（0.55 时只有 4.06:1）。
                // 深色版本来就是 0.62（6.94:1），两版从此同一档。
                ink = 0x111111, hint = mix(0xF2F2F0, 0x111111, 0.62f),
            ),
            noShadowStyle = true)

        /** 瑞士风格的深色版：黑纸 + 白墨 + 同一个信号红（红在深底上更亮一档，否则糊成一团） */
        val SWISS_DARK = Theme(0x0E0E0E, 99, 0xF2F2F0, 0xF2F2F0, 0xF2F2F0, 0xFF3B30, 0xD62B20,
            palette = ThemePalette(
                paper = 0x0E0E0E, card = 0x1C1C1C,
                primary = 0xFF3B30, secondary = 0xD62B20, highlight = 0x777772,
                ink = 0xF2F2F0, hint = mix(0x0E0E0E, 0xF2F2F0, 0.62f),
            ),
            noShadowStyle = true)

        /** 这套配色要不要把形状切成瑞士直角（给 Ui / Brut 判断用，避免各处硬编码 id） */
        fun isSwiss(id: String?): Boolean = id == "swiss" || id == "swiss_dark"

        /** 这套配色要不要把形状切成 v2.5 的分层圆角（Mint Rose Atelier 一族） */
        fun isAtelier(id: String?): Boolean = id == "mint_rose" || id == "mint_rose_dark"

        /** v2.7.3：这套配色要不要把材质切成液态玻璃（liquid_glass 一族；形状走 [Shape.LIQUID_GLASS]，材质走 glassMaterial） */
        fun isGlass(id: String?): Boolean = id == "liquid_glass" || id == "liquid_glass_dark"

        // ---------------- v2.5 Mint Rose Atelier ----------------
        /**
         * Mint Rose Atelier：薄荷绿主色 + 玫瑰金点缀 + 奶油白纸底的「年轻轻奢」配色。
         *
         * ## 为什么是这几个数
         *  - 纸底 0xF7FAF7 不是纯白：它是带一点点绿调的「雾面奶油」，纯白在 AMOLED 上刺眼，
         *    而且和卡片底 0xFFFFFF 拉不开层次。卡片才用纯白，两者差 1 档正好是「浮起来」的量。
         *  - 主色 0x65C9A8（Deep Mint）而不是 #A8E6CF：用户给的 A8E6CF 亮度太高，
         *    当按钮底时必须配黑字，且对比度只有 1.6，写不了任何小字。Deep Mint 才是它「能用的那档」。
         *    （0xA8E6CF 保留在 highlight 里做浅色块 / 选中态背景。）
         *  - 副色用 0xD9A0A7（Rose Gold），但**所有能承载小字的场合一律用 roseDeep 0xB87982** ——
         *    玫瑰金在浅底上的对比度只有 2.3，当文字必糊。这是「玫瑰金≠粉色」的工程落点。
         *  - 墨色 0x293B35（用户给的 Primary Text）带绿调，和薄荷主色同族，比纯灰更「暖」。
         *  - 没有 bg2（无渐变）：这套配色靠色块分区和留白，不靠大面积渐变——大渐变会把它变成廉价彩虹。
         */
        private const val MR_PAPER = 0xF7FAF7
        private const val MR_CARD = 0xFFFFFF
        private const val MR_MINT = 0x65C9A8          // Deep Mint
        private const val MR_MINT_LIGHT = 0xA8E6CF    // Mint Green（浅色块）
        // Forest Mint（深色文字 / 强调）。原来是 0x347D68，在纸底 #F7FAF7 上只有 4.67:1 ——
        // 标题是大字号没错，但 ThemeTest.presetsAreReadable 要求全表「正文/答案/标题」一律 >= 7.0，
        // 而「浅色不能承载小字号正文」也是这轮视觉重做的硬要求，所以往墨色压。
        // 实测 #2D5A4C：纸底 7.46 / 卡片 7.84 / 第二层表面 7.17；色相仍是同一支绿，没有改成蓝。
        private const val MR_MINT_DEEP = 0x2D5A4C
        private const val MR_ROSE = 0xD9A0A7          // Rose Gold
        private const val MR_ROSE_DEEP = 0xB87982     // Copper Rose（承载小字的档）
        private const val MR_INK = 0x293B35
        private const val MR_HINT = 0x788780
        private const val MR_SURFACE_ALT = 0xF1F6F2
        private const val MR_LAVENDER = 0xC7B8EA
        private const val MR_GLACIER = 0xA9DDF3
        private const val MR_CORAL = 0xF4B8A8
        private const val MR_BUTTER = 0xF6E7A8

        val MINT_ROSE = Theme(
            bg = MR_PAPER, opacity = 100, text = MR_INK, answer = MR_INK, heading = MR_MINT_DEEP,
            orbA = MR_MINT, orbB = MR_ROSE,
            palette = ThemePalette(
                paper = MR_PAPER, card = MR_CARD,
                primary = MR_MINT, secondary = MR_ROSE, highlight = MR_MINT_LIGHT,
                ink = MR_INK, hint = MR_HINT,
                surfaceAlt = MR_SURFACE_ALT, roseDeep = MR_ROSE_DEEP,
                lavender = MR_LAVENDER, glacier = MR_GLACIER, coral = MR_CORAL, butter = MR_BUTTER,
            ),
        )

        /**
         * 深色版：**深森林绿底，不是把浅色反转**。
         *
         * 反转会得到「灰底 + 亮绿」，那是另一套配色。这套做法是：底压到 0x0F1A16（带绿的黑），
         * 卡片提亮到 0x18261F（仍带绿），薄荷主色提亮到 0x7FD9BC（深底上原色太沉、发灰），
         * 玫瑰金降到低饱和 0xC89AA0（夜里高饱和粉会刺眼）。
         */
        private const val MRD_PAPER = 0x0F1A16
        private const val MRD_CARD = 0x18261F
        private const val MRD_INK = 0xE8F1EC

        val MINT_ROSE_DARK = Theme(
            bg = MRD_PAPER, opacity = 100, text = MRD_INK, answer = MRD_INK, heading = 0x9FE8CC,
            orbA = 0x7FD9BC, orbB = 0xC89AA0,
            palette = ThemePalette(
                paper = MRD_PAPER, card = MRD_CARD,
                primary = 0x7FD9BC, secondary = 0xC89AA0, highlight = 0x3D6B58,
                ink = MRD_INK, hint = mix(MRD_PAPER, MRD_INK, 0.70f),
                surfaceAlt = 0x13211B, roseDeep = 0xD8AAB0,
                lavender = 0xA99BD0, glacier = 0x8CC6DE, coral = 0xD9988A, butter = 0xD9CB8E,
            ),
            noShadowStyle = false,
        )

        // ---------------- v2.7.3 液态玻璃 ----------------
        /**
         * 液态玻璃：冷白 / 冰蓝 / 极轻薰衣草的「清透材质」配色（浅色正文深蓝墨，主色蓝 / 青，和默认薄荷明显区分）。
         *
         * ## 为什么 opacity = 87
         * 玻璃没有实时模糊（跨窗口模糊在手机上不可依赖，见 Ui.SURFACE 的注释），所以卡面必须**自身够不透明**：
         * opacity 87 → [card] alpha = min(100, 90) ≈ 89%，卡面合成到纯黑底上正文仍有 11.5:1、合成到纯白底 15:1
         * （深色版对应 8.7 / 9.9），即 [GLASS_CARD_ALPHA] 的下限边界两头都过 7.0（见 GlassThemeTest）。
         * 再透明就该靠 material 的「高光细边 + 柔和阴影」表达，而不是拿可读性换。
         *
         * - 浅色：纸底近冷白（带一点蓝），正文深蓝墨，标题深冰蓝；主色海蓝、副色青。
         * - 深色：墨蓝底亮正文；不是把浅色反转 —— 底是独立调的墨蓝，卡片是提亮一档的蓝灰。
         * - 没有 bg2：渐变是装饰，玻璃靠材质分层表达层次。
         */
        private const val LG_INK = 0x132639
        private const val LG_HEAD = 0x0A4054
        private const val LG_BLUE = 0x2F8FDB
        private const val LG_CYAN = 0x2BB8C9

        val LIQUID_GLASS = Theme(
            bg = 0xF2F7FC, opacity = 87, text = LG_INK, answer = LG_INK, heading = LG_HEAD,
            orbA = LG_BLUE, orbB = LG_CYAN,
            palette = ThemePalette(
                paper = 0xF2F7FC, card = 0xFBFDFF,
                primary = LG_BLUE, secondary = LG_CYAN, highlight = 0xB8E0F5,
                ink = LG_INK, hint = mix(0xF2F7FC, LG_INK, 0.66f),
                surfaceAlt = 0xE9F1FA, roseDeep = 0x2E6B8A,
                lavender = 0xC9CBF2, glacier = 0x9FD9F2, coral = 0xF0C6C0, butter = 0xF0E6B8,
                glassMaterial = true,
            ),
        )

        /**
         * 玻璃卡面的可读性安全档（**约定值**）：玻璃画卡面 / 按钮 / 弹层时把低于这一档的填色兜到这一档。
         * 这不是按对比度反推出来的数学最小值 —— 真正量出来的边界在 GlassThemeTest：卡面 / 面板底的
         * 渐变两端（[glassTop] / [glassBottom]）合成到纯黑 / 纯白底上，正文 / 答案 / 标题两头都过 7.0。
         */
        const val GLASS_CARD_ALPHA = 0.88f

        /** [GLASS_CARD_ALPHA] 换算成 0..255 的 alpha 档：渲染兜底和 GlassThemeTest 量边界用同一档，别处不要再换算 */
        val glassFloorAlpha: Int = Math.round(255 * GLASS_CARD_ALPHA)

        val LIQUID_GLASS_DARK = Theme(
            bg = 0x0B1622, opacity = 87, text = 0xE9F1FA, answer = 0xE9F1FA, heading = 0xB0EAFA,
            orbA = 0x6FC0F2, orbB = 0x5FD8E0,
            palette = ThemePalette(
                paper = 0x0B1622, card = 0x1A2A3E,
                primary = 0x6FC0F2, secondary = 0x5FD8E0, highlight = 0x3E5E80,
                ink = 0xE9F1FA, hint = mix(0x0B1622, 0xE9F1FA, 0.72f),
                surfaceAlt = 0x122133, roseDeep = 0x8FC9E8,
                lavender = 0xAAB6E8, glacier = 0x8CC6DE, coral = 0xE0A8A8, butter = 0xD8CC90,
                glassMaterial = true,
            ),
        )

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
            // v2.4 瑞士国际主义：一套独立风格（颜色 + 形状一起换），不是第七套青春配色
            Triple("swiss", "瑞士国际主义 · Swiss（直角 / 黑白红 / 无投影）", SWISS),
            Triple("swiss_dark", "瑞士国际主义 · 深色 · Swiss Dark", SWISS_DARK),
            // v2.5 Mint Rose Atelier：薄荷绿 + 玫瑰金 + 奶油白，分层圆角 + 柔和阴影
            Triple("mint_rose", "薄荷玫瑰 · Mint Rose Atelier", MINT_ROSE),
            Triple("mint_rose_dark", "薄荷玫瑰 · 深色 · Mint Rose Dark", MINT_ROSE_DARK),
            // v2.7.3 液态玻璃：清透材质 + 蓝青主色（换一套材质，不只是换一组颜色）
            Triple("liquid_glass", "液态玻璃 · Glass（冷白 / 冰蓝 / 清透材质）", LIQUID_GLASS),
            Triple("liquid_glass_dark", "液态玻璃 · 深色 · Glass Dark（墨蓝 / 清透材质）", LIQUID_GLASS_DARK),
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
            // v2.4：瑞士国际主义单独排在最后，它是「换一套风格」而不是「换一组颜色」
            "swiss",
            // v2.5：薄荷玫瑰是新的默认观感（v2.5 起新装用户默认选它，见 Prefs.themeId）
            "mint_rose",
            // v2.7.3：液态玻璃也是「换一套风格」的成员，排最后；深色伴生只在跟随系统时顶上
            "liquid_glass",
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
            // v2.5：薄荷玫瑰的深色伴生就是它自己那套深森林绿
            "mint_rose" to "mint_rose_dark",
            // v2.4：瑞士深浅是同一套语言的两半。缺这条时「瑞士 + 系统深色」落进 DARK_FALLBACK
            // （赛博蓝），形状也跟着失守 —— Prefs.effectiveShape 是拿生效 id 去问 isSwiss 的。
            "swiss" to "swiss_dark",
            // v2.7.3：玻璃深浅是同一套材质的两半；缺这条「玻璃 + 系统深色」会落进赛博蓝兜底，形状材质一起失守
            "liquid_glass" to "liquid_glass_dark",
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
            // alpha 必须一起插值：只拼 RGB 会得到 0x00RRGGBB，也就是 alpha=0 的全透明色。
            // 这样算出来的颜色「十六进制看着正常、对比度算着也够」，画到屏幕上却什么都不显示。
            // v2.5 底栏未选中格就是这样整格消失的（selected=true 走 Brut.INK 常量，所以只有那一格在）。
            val al = ch(24)
            return (al shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
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
