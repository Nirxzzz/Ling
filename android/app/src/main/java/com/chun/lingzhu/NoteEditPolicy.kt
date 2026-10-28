package com.chun.lingzhu

/**
 * NoteEditActivity 的保存决策 —— 抽成纯函数，好在 JVM 上测。
 *
 * ## 为什么要单独抽出来
 *
 * 这些规则写在 Activity 的 save() 里就测不了（Activity 跑不了 JVM 单测）。
 * 而它们恰恰是最容易写错、错了又最难发现的部分：
 * 空内容存进去会得到一条「（空笔记）」；
 * 内容没变也写库会把 updatedAt 推到现在，而列表按 createdAt 排序，
 * 用户会看到这条笔记莫名其妙跳了位置。
 */
object NoteEditPolicy {

    /** 保存时应当做什么 */
    sealed class Action {
        /** 内容为空，不写库，提示用户 */
        object RejectEmpty : Action()
        /** 内容没变，直接关页面，不写库 */
        object CloseWithoutWrite : Action()
        /** 新建一条 */
        data class Create(val body: String) : Action()
        /** 更新已有笔记 */
        data class Update(val body: String) : Action()
    }

    /**
     * 决定保存动作。
     *
     * @param body 用户输入的正文（原样，不 trim —— 用户可能故意保留缩进）
     * @param isEdit true = 编辑已有笔记
     * @param original 编辑模式下的原始正文；新建时传空串
     */
    fun decide(body: String, isEdit: Boolean, original: String): Action {
        // 只有空白字符也算空 —— 存一条只有空格的笔记没有意义，
        // 而且列表里它会显示成「（空笔记）」，用户会困惑
        if (body.isBlank()) return Action.RejectEmpty
        if (isEdit && body == original) return Action.CloseWithoutWrite
        return if (isEdit) Action.Update(body) else Action.Create(body)
    }

    /**
     * 退出前是否需要问一句「不保存就退出？」。**这是唯一的退出判据。**
     *
     * 抽成纯函数是因为它和 [decide] 的目标不同：
     * [decide] 管「保存时做什么」，这里管「**不**保存时要不要拦一下」。
     *
     * 规则刻意和 [decide] 只有一点不一致：
     * - 只有空白字符 **算脏**（用户打了几个空格，退出时也该问一下）
     * - 因为 [decide] 会把纯空白判成 RejectEmpty，若这里也判「不脏」，
     *   用户会先看到「还没写内容」再看到页面正常关闭，两次反馈互相打架
     *
     * 编辑模式下与原文完全一致则不算脏 —— 打开看了一眼就退出，不该弹窗。
     *
     * 曾经这里另外有一段「这次有没有真的落库过」的判断（savedOnce），
     * 结果打开页面什么都没改、直接退出也会弹「不保存就退出？」，和纯空白的判定也互相打架。
     * 保存被拒时页面留在原地、输入框不清空，用户手里的字不会丢，
     * 因此退出判据只留这一条。
     */
    fun isDirty(body: String, isEdit: Boolean, original: String): Boolean {
        if (!isEdit) return body.isNotEmpty()
        return body != original
    }
}