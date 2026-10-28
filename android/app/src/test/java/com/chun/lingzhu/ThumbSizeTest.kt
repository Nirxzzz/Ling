package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.4 缩略图尺寸换算。原来这段藏在 NoteAttach 里、靠两个魔法常量拼，测不到也看不清 */
class ThumbSizeTest {

    @Test fun portraitScreenshotScalesByWidth() {
        // 1080x2400 竖屏截图：短边是宽，缩到 320，高按比例 711
        assertEquals(320 to 711, ThumbSize.of(1080, 2400))
    }

    @Test fun landscapeScreenshotScalesByHeight() {
        // 横屏时短边是高。这里最容易写错成「永远缩宽」，那样横图会变得又矮又扁
        assertEquals(711 to 320, ThumbSize.of(2400, 1080))
    }

    @Test fun squareIsSquare() {
        assertEquals(320 to 320, ThumbSize.of(1080, 1080))
    }

    @Test fun tinyImageIsNotUpscaled() {
        // 本来就比缩略图小：放大只会让它更糊，还多占内存
        assertEquals(100 to 80, ThumbSize.of(100, 80))
    }

    @Test fun exactSizeIsKept() {
        assertEquals(320 to 320, ThumbSize.of(320, 320))
    }

    @Test fun extremeTallImageKeepsAtLeastOnePixel() {
        // 1x5000 这种极端比例：round 可能把宽算成 0，0 宽位图会直接抛异常
        val (w, h) = ThumbSize.of(1, 5000)
        assertTrue("宽至少 1px，实际 " + w, w >= 1)
        assertTrue("高至少 1px", h >= 1)
    }

    @Test fun aspectRatioIsPreserved() {
        // 缩放不能改变长宽比：算错会让卡片上的图变形
        val (w, h) = ThumbSize.of(1080, 2400)
        val before = 1080.0 / 2400.0
        val after = w.toDouble() / h.toDouble()
        assertTrue("比例偏差应小于 1%", Math.abs(before - after) < 0.01)
    }

    @Test fun shortSideIsAlwaysTheTarget() {
        // 不管横竖，短边都应该等于目标（除非原图更小）
        listOf(1080 to 2400, 2400 to 1080, 1440 to 3200, 3200 to 1440).forEach { (w, h) ->
            val (tw, th) = ThumbSize.of(w, h, 320)
            assertEquals("短边应为 320（" + w + "x" + h + "）", 320, minOf(tw, th))
        }
    }

    @Test fun zeroOrNegativeIsHandled() {
        // 防御性：脏数据不该让整个存图流程崩
        assertEquals(0 to 0, ThumbSize.of(0, 0))
        assertEquals(0 to 2400, ThumbSize.of(-5, 2400))
    }

    // ---------------- 按月子目录名 ----------------

    @Test fun monthNamePadsMonth() {
        // 1 月必须写成 01，不能是 20261（少补一位的话所有月份排序全乱）
        assertEquals("202601", ThumbSize.monthName(2026, 1))
        assertEquals("202610", ThumbSize.monthName(2026, 10))
        assertEquals("202612", ThumbSize.monthName(2026, 12))
    }

    @Test fun monthIsOneBasedNotZeroBased() {
        // Java 的 Calendar.MONTH 是 0-based，调用方已经 +1；这里再确认 1 就是不差月。
        // 差一个月会导致 12 月的截图存进下一年 1 月的目录。
        assertEquals("202512", ThumbSize.monthName(2025, 12))
        assertEquals("202501", ThumbSize.monthName(2025, 1))
    }

    @Test fun monthNameIsPureAscii() {
        // 关键：String.format("%04d") 在阿拉伯语 locale 下会输出 ٢٠٢٦ 这类本地数字，
        // 目录名就成了非法文件名。自己补零保证只输出 ASCII。
        val hasNonAscii = ThumbSize.monthName(2026, 10).any { it.code > 127 }
        assertTrue("目录名必须全是 ASCII", !hasNonAscii)
    }

    @Test fun monthNameClampsOutOfRange() {
        // 防御：万一调用方忘了 +1 传了 0，也不该生成 "202600" 这种目录
        assertEquals("202601", ThumbSize.monthName(2026, 0))
        assertEquals("202612", ThumbSize.monthName(2026, 13))
    }

    // ------------------------------------------------------------------
    // 下面这组是 2026-10-22 加的：用变异测试查证「coerceAtLeast 是死代码」时
    // 顺手把这条数学性质钉住，免得后人再加回无效保护，或者改坏比例算法。
    // ------------------------------------------------------------------

    @Test fun resultIsNeverZeroForAnyPositiveInput() {
        // 这是 of() 最关键的不变量：Bitmap 遇到 0 宽或 0 高会直接抛异常。
        // 曾经的代码用 coerceAtLeast(1) 来「防 round 算出 0」，
        // 但数学上 tw/th 都 >= round(short)，永远不会是 0 —— 那层保护从没生效。
        // 所以真正该守的不是「有没有 coerce」，而是「结果是否真的 > 0」。
        val sizes = listOf(
            1 to 5000, 5000 to 1, 1080 to 2400, 2400 to 1080,
            320 to 320, 321 to 321, 1 to 1, 99999 to 1,
            1000000 to 1000001, 10000000 to 10000001,
        )
        for ((w, h) in sizes) {
            val (tw, th) = ThumbSize.of(w, h)
            assertTrue("${w}x${h} 的宽必须 > 0，实际 $tw", tw > 0)
            assertTrue("${w}x${h} 的高必须 > 0，实际 $th", th > 0)
        }
    }

    @Test fun shortSideNeverExceedsTargetAfterScaling() {
        // 缩放的语义就是「短边 = short」。如果不放大分支写错，短边可能超过 short。
        for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 5000 to 5000)) {
            val (tw, th) = ThumbSize.of(w, h, 320)
            assertEquals("短边应正好缩到 320", 320, minOf(tw, th))
        }
    }

    @Test fun extremeAspectRatioStaysExtreme() {
        // 1:5000 这种截图：不能被压成方块，也不能出现 0
        val (tw, th) = ThumbSize.of(1, 5000)
        assertTrue("1x5000 不该被放大，实际 ${tw}x${th}", tw == 1 && th == 5000)
        val (tw2, th2) = ThumbSize.of(2, 10000)
        assertTrue("2x10000 应保持比例，实际 ${tw2}x${th2}", th2 / tw2 >= 4000)
    }

    @Test fun nonPositiveInputDoesNotCrash() {
        // 损坏的图片头可能给出 0 或负数
        for ((w, h) in listOf(0 to 100, 100 to 0, -5 to 100, 100 to -5, 0 to 0)) {
            val (tw, th) = ThumbSize.of(w, h)
            assertTrue("${w}x${h} 不应产生负值，实际 ${tw}x${th}", tw >= 0 && th >= 0)
        }
    }

    @Test fun scaledResultNeverExceedsOriginal() {
        // 「不放大」的另一面：缩略图永远不该比原图大
        for ((w, h) in listOf(1080 to 2400, 640 to 480, 100 to 100, 100000 to 100000)) {
            val (tw, th) = ThumbSize.of(w, h, 320)
            assertTrue("${w}x${h} -> ${tw}x${th} 不该被放大", tw <= w && th <= h)
        }
    }

    @Test fun monthNameHandlesBoundaries() {
        assertEquals("202601", ThumbSize.monthName(2026, 1))
        assertEquals("202612", ThumbSize.monthName(2026, 12))
        // 越界月份要夹住，而不是拼出 202613 或 202600
        assertEquals("202612", ThumbSize.monthName(2026, 13))
        assertEquals("202601", ThumbSize.monthName(2026, 0))
        // 只用 ASCII：某些 locale 下 %d 会吐本地数字，目录名就不是合法文件名了
        val name = ThumbSize.monthName(2026, 10)
        assertTrue("月份目录名必须是纯 ASCII，实际 $name", name.all { it in '0'..'9' })
    }

    @Test fun monthNamePadsYearToFourDigits() {
        assertEquals("0005" + "01", ThumbSize.monthName(5, 1))
        assertEquals("0000" + "01", ThumbSize.monthName(0, 1))
        assertEquals("1202601", ThumbSize.monthName(12026, 1))
    }
}
