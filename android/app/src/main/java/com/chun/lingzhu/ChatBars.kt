package com.chun.lingzhu

/**
 * 聊天页底栏的 insets 计算（v1.85.8 修「输入栏被导航条 / 键盘压住一半」）。
 *
 * 为什么单独一个对象：判断要能在 JVM 单测里跑（项目纪律：判断逻辑抽纯函数），
 * 而 Brut 是带着 android 控件字段的皮肤对象，单测环境起不来。
 *
 * 背景：targetSdk 35 起强制 edge-to-edge，`Brut.page()` 在 root 上垫的是**废弃的**
 * `systemWindowInsetBottom` —— 这一个数在不同机型 / 导航模式上口径不稳（有设备反馈偏小，
 * 底栏被压住一半）。v1.85.8 起聊天页改成：root 只管状态栏和左右，底栏容器自己垫
 * 系统栏 + 键盘，两个数都走 API 30+ 的 `getInsets(...)`。
 */
object ChatBars {

    /**
     * 底栏该垫的总高度 = 系统栏（手势条 / 三键导航）+ 键盘。
     * IME inset 通常已包含导航条高度，所以键盘部分取「IME 超出系统栏的差值」，不重复垫。
     * 两个量都 < 0 视为异常值按 0 处理（有些 ROM 会给负的 cutout 值）。
     */
    fun bottomPad(systemBar: Int, ime: Int): Int {
        val sb = systemBar.coerceAtLeast(0)
        val im = ime.coerceAtLeast(0)
        return sb + (im - sb).coerceAtLeast(0)
    }

    /**
     * root 该垫的上边（状态栏，取状态栏和刘海的较大者）和左右（刘海）。
     * 返回 intArrayOf(left, top, right)；底边固定 0 —— 底归 [bottomPad] 管。
     */
    fun topPad(statusBar: Int, cutoutTop: Int, cutoutLeft: Int, cutoutRight: Int): IntArray {
        val t = listOf(statusBar, cutoutTop).filter { it > 0 }.maxOrNull() ?: 0
        val l = cutoutLeft.coerceAtLeast(0)
        val r = cutoutRight.coerceAtLeast(0)
        return intArrayOf(l, t, r)
    }
}
