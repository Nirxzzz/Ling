package com.chun.lingzhu

import android.content.Context
import android.content.Intent

/**
 * Anki 牌组的入口注册项。薄到只有元数据 + intent()，真正的逻辑都在 [AnkiActivity]。
 *
 * 单独一个文件而不是塞进 `FeaturePacks.kt`：`FeaturePacks.kt` 是所有功能包的集中登记处，
 * 而 Anki 是一整套自成一体的新模块（包解析 / 模板渲染 / FSRS 复习），代码全在 `anki/` 目录下，
 * 入口跟着它一起放着，将来整体挪走或删掉时不用回到别处拆线。
 */
object AnkiFeature : Feature {
    override val id = "anki"
    override val title = "Anki 牌组"
    override val subtitle = "导入 .apkg，按原模板复习"
    override val order = 150

    override fun intent(ctx: Context): Intent = AnkiActivity.intent(ctx)

    /**
     * 清单漏注册时 startActivity 会抛 ActivityNotFoundException，表现是「点了没反应」——
     * 这里吞掉并返回 false，让调用方显示一句人话。
     */
    override fun open(ctx: Context): Boolean = try {
        ctx.startActivity(intent(ctx))
        true
    } catch (e: Exception) {
        false
    }
}
