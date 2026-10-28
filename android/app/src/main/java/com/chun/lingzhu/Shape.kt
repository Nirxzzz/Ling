package com.chun.lingzhu

/**
 * v2.4「瑞士国际主义」：一套和现有「新粗野主义」在**形状语言**上完全相反的风格。
 *
 * ## 两套风格到底差在哪
 * 新粗野主义（默认那套）：6dp 圆角 + 2dp 黑描边 + 4dp **实心**投影 + 高饱和薄荷/玫瑰色。
 * 瑞士国际主义（Müller-Brockmann 那一路）：
 *  
 *  - **直角**，没有一处圆角。网格是方的，元素就该是方的。
 *  - **没有投影**。层次靠留白、对齐、字重，不靠假的立体感。
 *  - **极细线**（1dp）代替描边，只用来分栏，不用来「框住」内容。
 *  - **黑白 + 一个信号红**，其余一律靠灰阶。
 *  - **Helvetica / 无衬线**，字重对比大（粗标题 + 细正文），而不是全粗。
 *
 * ## 为什么做成「形状维度」而不是「颜色维度」
 * 只换颜色做不出瑞士感 —— 圆角和投影是这套风格最刺眼的两处。所以 [Shape] 独立于 [ThemePalette]：
 * 色板和形状可以自由组合（比如「瑞士配色 + 粗野形状」也不会崩）。
 */
data class Shape(
    /** 圆角半径 dp。瑞士兵 0）；粗野 6 */
    val cornerDp: Float,
    /** 描边粗细 dp */
    val strokeDp: Float,
    /** 投影偏移 dp。0 = 无投影 */
    val shadowDp: Float,
    /** 输入框聚焦时的描边粗细 dp（默认风格用加粗表示聚焦；瑞士风格用 1dp→2dp） */
    val focusStrokeDp: Float,
    // ---------------- v2.5 Mint Rose Atelier：分层圆角 ----------------
    // 用户明确要求「避免所有组件都用相同大圆角：列表、按钮、标签、弹窗、内容卡片应有
    // 不同但协调的圆角层级」。单值 cornerDp 做不到这件事，所以单开四个槽位。
    //
    // 默认值是这个字段**不存在时**的语义（全部回落到 cornerDp），
    // 于是 BRUT / SWISS 两个既有形状一行都不用改，构造出来的对象逐位不变。
    //
    // 层级怎么定的（数值来自 4/8/12/16/24 的圆角尺度，且差值都 >= 4dp 才看得出层级）：
    /** 内容卡片 / 列表项：最大的一档，因为它面积最大，圆角小了会显得「硬」 */
    val cardCornerDp: Float = cornerDp,
    /** 按钮：比卡片小一档 —— 按钮贴着卡片内边，圆角一样大会糊成一片 */
    val buttonCornerDp: Float = cornerDp,
    /** 标签 / chip / 药丸：等于高度的一半就成药丸，但数值写死更可控 */
    val chipCornerDp: Float = cornerDp,
    /** 底部弹层 / 对话框：只有上方两角，最大一档 */
    val sheetCornerDp: Float = cornerDp,
) {
    /** 这套形状是不是「分层圆角」（圆角不全相等）。老形状恒为 false，渲染走原路径 */
    val isLayered: Boolean get() = !(cardCornerDp == cornerDp && buttonCornerDp == cornerDp &&
        chipCornerDp == cornerDp && sheetCornerDp == cornerDp)

    companion object {
        /** 默认：新粗野主义（6dp 圆角 + 2dp 线 + 4dp 实心投影）。**数值必须与改造前逐位一致** */
        val BRUT = Shape(cornerDp = 6f, strokeDp = 2f, shadowDp = 4f, focusStrokeDp = 4f)

        /** 瑞士国际主义：直角、1dp 细线、无投影 */
        val SWISS = Shape(cornerDp = 0f, strokeDp = 1f, shadowDp = 0f, focusStrokeDp = 2f)

        /**
         * v2.5 Mint Rose Atelier：分层圆角 + 发丝线 + 柔和投影。
         *
         * 和粗野（6dp/2dp 黑描边/4dp 实心投影）的三点差别，正是「精致」与「积木」的分界：
         *  1. **圆角分四档**：卡片 20 / 按钮 14 / 标签 999（药丸）/ 弹层 24。
         *     单一圆角会让整页像一堆同样圆的白块，分档之后眼睛才分得出「哪层是哪层」。
         *  2. **描边降到 1dp 且不再是黑色**：粗野的 2dp 黑边是「积木感」的来源，
         *     这里用发丝线 + 色板里的 roseDeep（见 Brut.strokeOf），边界在，但不抢内容。
         *  3. **投影 2dp 且是柔和 alpha**：粗野的 4dp 实心黑投影是印刷错位效果，
         *     这里只要「卡片离纸面 2dp」的体积感。
         *  4. focusStroke 4dp：聚焦靠描边加粗，浅色细线风格里这是唯一不引入新颜色的聚焦信号。
         */
        val ATELIER = Shape(
            cornerDp = 20f, strokeDp = 1f, shadowDp = 2f, focusStrokeDp = 4f,
            cardCornerDp = 20f, buttonCornerDp = 14f, chipCornerDp = 999f, sheetCornerDp = 24f,
        )

        /**
         * v2.7.3 液态玻璃：分层圆角 + 细高光边 + 柔和阴影。
         *
         * 和 Atelier（20/14/999/24 + 2dp 阴影）同一族「分层」语言，但整体大一档、边更细：
         *  - 圆角四档：卡片 24 / 按钮 16 / 标签 999（药丸）/ 弹层 28 —— 玻璃块面大，圆角跟着大才不像塑料贴纸。
         *  - cornerDp 24：非分层调用点（老 helper 直读 cornerDp）也能拿到玻璃的主圆角。
         *  - 描边 1dp：玻璃的边界靠 Ui.material 的高光细边表达，不要再叠一圈粗线。
         *  - **shadowDp 2 不是 0**：无投影（<=0）会被全库当成「瑞士直角语言」的判据
         *    （Ui.noShadow / Brut.noShadow / 各页 swiss 分支），玻璃一旦写 0 就会被误折成直角。
         *    2dp 柔和阴影正好是玻璃「浮起来」该有的量。
         *  - focus 2dp：聚焦反馈沿用「加粗一档」的既有信号。
         */
        val LIQUID_GLASS = Shape(
            cornerDp = 24f, strokeDp = 1f, shadowDp = 2f, focusStrokeDp = 2f,
            cardCornerDp = 24f, buttonCornerDp = 16f, chipCornerDp = 999f, sheetCornerDp = 28f,
        )
    }
}
