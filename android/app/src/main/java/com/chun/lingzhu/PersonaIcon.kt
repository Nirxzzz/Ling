package com.chun.lingzhu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 人格头像：**自己画的**小图标（v1.85）。
 *
 * 为什么不用 emoji：用户原话「我不要那些莫名其妙的 emoji，实在不行你就画 SVG，简单的，能代表意思的」。
 * 用 emoji 当头像还有个实际问题 —— 不同 ROM 的字形宽高/基线差很多，嵌进列表行里会和文字挤在一起
 * （用户报的聊天页「重叠」就是这一类）。这里用 Path 画几个简单图形，尺寸由 View 决定，和排版天然对齐。
 *
 * 纯逻辑 [keyFor] 可单测（[PersonaIconTest]），画的部分只管把 key 变成图形。
 */
class PersonaIcon(ctx: Context, private val key: String, sizeDp: Float = 30f) : View(ctx) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Brut.INK
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(ctx, 1.6f).toFloat()
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Brut.MINT }
    private val path = Path()
    private val box = RectF()

    init {
        val px = Ui.dp(ctx, sizeDp)
        layoutParams = android.widget.LinearLayout.LayoutParams(px, px)
    }

    override fun onDraw(canvas: Canvas) {
        val s = Math.min(width, height).toFloat()
        val pad = s * 0.14f
        box.set(pad, pad, width - pad, height - pad)
        val v = box.width() / 24f             // 虚拟坐标缩放到留白内的绘图区
        fun x(a: Float) = box.left + a * v
        fun y(a: Float) = box.top + a * v
        canvas.save()
        when (key) {
            K_STUDY -> {                     // 书：两页
                fill.color = Brut.SAND
                path.reset()
                path.moveTo(x(3f), y(6f)); path.lineTo(x(12f), y(8f)); path.lineTo(x(12f), y(19f))
                path.lineTo(x(3f), y(17f)); path.close()
                canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(x(21f), y(6f)); path.lineTo(x(12f), y(8f)); path.lineTo(x(12f), y(19f))
                path.lineTo(x(21f), y(17f)); path.close()
                canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
            }
            K_LIFE -> {                      // 房子
                fill.color = Brut.MINT
                path.reset()
                path.moveTo(x(4f), y(11f)); path.lineTo(x(12f), y(4f)); path.lineTo(x(20f), y(11f))
                path.close()
                canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
                path.reset()
                path.addRoundRect(RectF(x(6.5f), y(11f), x(17.5f), y(20f)), v, v, Path.Direction.CW)
                canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
            }
            K_CODE -> {                      // 尖括号 + 斜杠
                fill.color = Brut.SAND
                path.reset()
                path.addRoundRect(RectF(box.left, box.top, box.right, box.bottom), s * 0.18f, s * 0.18f, Path.Direction.CW)
                canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(x(10f), y(9f)); path.lineTo(x(7f), y(12f)); path.lineTo(x(10f), y(15f))   // <
                path.moveTo(x(14f), y(9f)); path.lineTo(x(17f), y(12f)); path.lineTo(x(14f), y(15f))   // >
                canvas.drawPath(path, stroke)
            }
            else -> {                        // chat：对话泡泡（也是内置「小Ling」的默认头像）
                fill.color = Brut.MINT
                path.reset()
                path.addRoundRect(RectF(x(3f), y(5f), x(21f), y(17f)), v * 2f, v * 2f, Path.Direction.CW)
                canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(x(8f), y(17f)); path.lineTo(x(8f), y(21f)); path.lineTo(x(12.5f), y(17f))
                path.close()
                canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
            }
        }
        canvas.restore()
    }

    companion object {
        const val K_CHAT = "chat"
        const val K_STUDY = "study"
        const val K_LIFE = "life"
        const val K_CODE = "code"

        /**
         * 人格 → 图标 key。**只看 id 和模板**（不从 emoji 推，那个字段已经不再渲染）。
         * 纯函数，单测直接盯。
         */
        fun keyFor(id: String, templateId: String): String = when {
            id == Personas.P0 -> K_CHAT                    // 内置「小Ling」
            templateId == "study" -> K_STUDY
            templateId == "life" -> K_LIFE
            templateId == "code" -> K_CODE
            else -> K_CHAT                                 // custom / 未知 → 对话泡泡兜底
        }
    }
}
