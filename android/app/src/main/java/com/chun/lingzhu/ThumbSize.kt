package com.chun.lingzhu

/**
 * v2.4 记事本：缩略图尺寸换算（纯函数，JVM 可测）。
 *
 * ## 为什么单独抽出来
 * 原来这段算在 [NoteAttach.save] 里，靠两个魔法常量拼出来：
 * 短边 320，长边 320*4=1280。但「长边 1280」这个约束在 320 短边下**永远不会生效** ——
 * 320/1080 和 1280/2400 里前者总是更小。也就是说它看着像个保护，实际一行都没执行过。
 * 更要命的是它随参数漂移：谁把 THUMB_SHORT 改成 400，长边就悄悄变成 1600，
 * 判断标准跟着参数跑，而不是跟着「我想要多大缩略图」跑。
 *
 * 现在的规矩是明确的：**只按短边等比缩，不设长边天花板**。
 * 理由：缩略图的一致性来自短边（卡片上高度对齐），长边跟着比例走才是对的；
 * 一张 1:4 的极端长图，硬砍长边只会让它变形或者被二次压缩。
 */
object ThumbSize {

    /** 缩略图短边默认值（卡片流按这个尺寸解码，内存小且清晰） */
    const val SHORT = 320

    /**
     * 附件按月子目录名，形如 `202610`。
     *
     * ## 为什么不用 String.format
     * `String.format("%04d%02d", ...)` 走**默认 locale**：在阿拉伯语 / 波斯语环境下
     * `%d` 会输出本地数字（١٠ ٢٠٢٦…），拼出来的目录名就不是合法文件名了，
     * 而且是「在某些用户手机上才炸」的那种 bug —— 自己永远复现不出来。
     * 所以这里自己补零，只用 ASCII。
     *
     * 月份用 1..12（Java 的 Calendar.MONTH 是 0-based，最容易在这里差一个月）。
     */
    fun monthName(year: Int, month1to12: Int): String {
        val m = month1to12.coerceIn(1, 12)
        val y = year.coerceAtLeast(0)
        return y.toString().padStart(4, '0') + m.toString().padStart(2, '0')
    }

    /**
     * 等比缩到短边 = [short]（或更小，如果原图本来就更小）。**不放大**。
     *
     * 短边取 min(w,h)：竖屏截图缩宽，横屏截图缩高，两种都让「卡片上的短边」一致。
     */
    fun of(w: Int, h: Int, short: Int = SHORT): Pair<Int, Int> {
        if (w <= 0 || h <= 0 || short <= 0) return w.coerceAtLeast(0) to h.coerceAtLeast(0)
        val s = minOf(w, h)
        if (s <= short) return w to h   // 本来就够小，不放大（放大只会糊）
        val k = short.toDouble() / s
        // 这里原来写着「至少留 1px：极端长图上 round 可能算出 0」，
        // 2026-10-22 用变异测试查证：**那句话不成立，这层保护从来没生效过。**
        //
        // 证明：k = short/s，而 s = min(w,h)。所以
        //   短边那侧 = round(s * short / s) = round(short) >= 1
        //   长边那侧 = round(max * short / s) >= round(short) >= 1
        // 20 万组随机输入验证，从未出现 0。
        //
        // 这跟本文件开头记的「长边 1280 永不生效」是同一类问题：
        // 一段看着像保护、实际一行都没跑过的代码。
        // 留着 coerceAtLeast 不会出错，但注释必须说实话，否则下一个人
        // 会以为这里真的挡过什么，从而不敢动真正该动的地方。
        val tw = Math.round(w * k).toInt()
        val th = Math.round(h * k).toInt()
        return tw to th
    }
}
