package com.chun.lingzhu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 扫码用的 Y 平面旋转（[LumaRotation]）：实机崩溃是 ZXing 的 PlanarYUVLuminanceSource 不支持旋转，
 * 修法是把亮度图自己转好再喂解码。旋转错方向 / 行列没互换都不会抛异常，只会永远扫不到码，
 * 所以这里用非方阵把 90 / 180 / 270 三个角度逐像素钉死。
 *
 * 相机给的 `SENSOR_ORIENTATION` 是「顺时针转到自然方向」的度数，实现按顺时针转。
 */
class LumaRotationTest {

    /** 3×2：`1 2 3 / 4 5 6`，非方阵，行列互换错一眼就能看出来。 */
    private fun sample(): ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun rotate(src: ByteArray, w: Int, h: Int, degrees: Int): ByteArray {
        val out = ByteArray(LumaRotation.rotatedWidth(w, h, degrees) * LumaRotation.rotatedHeight(w, h, degrees))
        LumaRotation.rotateLuma(src, w, h, degrees, out)
        return out
    }

    private fun aspect(w: Int, h: Int, degrees: Int): Pair<Int, Int> =
        LumaRotation.rotatedWidth(w, h, degrees) to LumaRotation.rotatedHeight(w, h, degrees)

    @Test
    fun clockwise90SwapsRowsAndColumns() {
        // 4 1
        // 5 2
        // 6 3
        assertEquals(2 to 3, aspect(3, 2, 90))
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), rotate(sample(), 3, 2, 90))
    }

    @Test
    fun halfTurnKeepsShapeAndReversesBothAxes() {
        // 6 5 4
        // 3 2 1
        assertEquals(3 to 2, aspect(3, 2, 180))
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), rotate(sample(), 3, 2, 180))
    }

    @Test
    fun clockwise270SwapsRowsAndColumnsTheOtherWay() {
        // 3 6
        // 2 5
        // 1 4
        assertEquals(2 to 3, aspect(3, 2, 270))
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), rotate(sample(), 3, 2, 270))
    }

    @Test
    fun zeroAndFullTurnAreIdentity() {
        assertEquals(3 to 2, aspect(3, 2, 0))
        assertArrayEquals(sample(), rotate(sample(), 3, 2, 0))
        assertEquals(3 to 2, aspect(3, 2, 360))
        assertArrayEquals(sample(), rotate(sample(), 3, 2, 360))
    }

    /** 90 和 270 弄反不会报错，只会扫不到码：这里要求两个方向互为逆运算。 */
    @Test
    fun oppositeAnglesCancelEachOther() {
        val once = rotate(sample(), 3, 2, 90)
        assertArrayEquals(sample(), rotate(once, 2, 3, 270))

        val twice = rotate(rotate(sample(), 3, 2, 180), 3, 2, 180)
        assertArrayEquals(sample(), twice)

        var four = sample()
        var w = 3
        var h = 2
        repeat(4) {
            four = rotate(four, w, h, 90)
            val next = aspect(w, h, 90)
            w = next.first
            h = next.second
        }
        assertEquals(3 to 2, w to h)
        assertArrayEquals(sample(), four)
    }
}
