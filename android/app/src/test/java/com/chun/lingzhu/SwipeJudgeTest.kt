package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词卡滑出判词的纯逻辑（v1.85）。盯三件事：方向 → 评分、两段临界、阻尼后的键程。
 * 纯 JUnit4，不碰 Android。
 */
class SwipeJudgeTest {

    private val th = SwipeJudge.thresholds(span = 1000, floor = 44f)   // t1 = 150, t2 = 340

    @Test fun thresholdsAreOrderedAndLongerThanBefore() {
        assertTrue(th.t1 < th.t2)
        assertEquals(150f, th.t1, 0.01f)
        assertEquals(340f, th.t2, 0.01f)
        // 「键程加长」：提交线比旧版那条 0.3 × 跨度 更远
        assertTrue(th.t2 > 1000 * 0.3f)
        // 小卡片走像素下限，且 t2 仍至少是 t1 的 2.2 倍
        val small = SwipeJudge.thresholds(span = 100, floor = 44f)
        assertEquals(44f, small.t1, 0.01f)
        assertTrue(small.t2 >= small.t1 * 2.2f - 0.01f)
    }

    @Test fun directionMapsToGrade() {
        // 左滑 = 不认识，右滑 = 认识，下滑 = 模糊，上滑 = 关闭不评
        assertEquals(SwipeJudge.Grade.UNKNOWN, SwipeJudge.gradeOf(-200f, 0f))
        assertEquals(SwipeJudge.Grade.KNOWN, SwipeJudge.gradeOf(200f, 0f))
        assertEquals(SwipeJudge.Grade.FUZZY, SwipeJudge.gradeOf(0f, 200f))
        assertEquals(SwipeJudge.Grade.CLOSE, SwipeJudge.gradeOf(0f, -200f))
        // 斜着划：主方向说了算（|dx| == |dy| 归横向）
        assertEquals(SwipeJudge.Grade.UNKNOWN, SwipeJudge.gradeOf(-300f, 250f))
        assertEquals(SwipeJudge.Grade.FUZZY, SwipeJudge.gradeOf(250f, 300f))
    }

    @Test fun dampingOnlyKicksInAfterT1() {
        // t1 之内 1:1 跟手
        assertEquals(100f, SwipeJudge.damp(100f, th.t1), 0.01f)
        assertEquals(-100f, SwipeJudge.damp(-100f, th.t1), 0.01f)
        // 过了 t1 打折：再多拖 100px，卡片只多走 62px
        val just = SwipeJudge.damp(150f, th.t1)
        val more = SwipeJudge.damp(250f, th.t1)
        assertEquals(150f, just, 0.01f)
        assertEquals(150f + 100f * SwipeJudge.DAMP_AFTER, more, 0.01f)
        // 方向保留
        assertEquals(-more, SwipeJudge.damp(-250f, th.t1), 0.01f)
    }

    @Test fun stageReportsTheTwoCriticals() {
        assertEquals(0, SwipeJudge.stage(100f, 0f, th))
        assertEquals(1, SwipeJudge.stage(200f, 0f, th))     // 过了 t1
        assertEquals(2, SwipeJudge.stage(400f, 0f, th))     // 过了 t2
        // 斜着划按总位移算（斜向也能进判词区），但方向仍是主方向
        assertEquals(2, SwipeJudge.stage(250f, 250f, th))
    }

    @Test fun releaseOnlyCommitsPastT2() {
        // 只过 t1、速度小 → 弹回，不评
        assertNull(SwipeJudge.judge(200f, 0f, 0f, 0f, th))
        // 过 t2 → 提交，方向对
        assertEquals(SwipeJudge.Grade.KNOWN, SwipeJudge.judge(400f, 0f, 0f, 0f, th))
        assertEquals(SwipeJudge.Grade.UNKNOWN, SwipeJudge.judge(-400f, 0f, 0f, 0f, th))
        assertEquals(SwipeJudge.Grade.FUZZY, SwipeJudge.judge(0f, 400f, 0f, 0f, th))
        assertEquals(SwipeJudge.Grade.CLOSE, SwipeJudge.judge(0f, -400f, 0f, 0f, th))
    }

    @Test fun fastFlickCommitsEvenFromShortDistance() {
        // 位移只到 t1，但甩得快 → 投影够到 t2，照样生效（「甩一下」也算数）
        assertEquals(SwipeJudge.Grade.KNOWN, SwipeJudge.judge(200f, 0f, 2000f, 0f, th))
        // 反向速度（拖出去又往回甩）不加成 → 不生效
        assertNull(SwipeJudge.judge(200f, 0f, -3000f, 0f, th))
        // 横着甩不会把纵向判定带过去
        assertEquals(SwipeJudge.Grade.FUZZY, SwipeJudge.judge(0f, 200f, 0f, 2000f, th))
    }

    @Test fun flickBonusIsSymmetricAndSignedByDragDirection() {
        // 左 / 上 快甩：负向速度必须和右 / 下 的正向速度一样算加成，同样够到 t2 = 340
        assertEquals(SwipeJudge.Grade.UNKNOWN, SwipeJudge.judge(-200f, 0f, -2000f, 0f, th))
        assertEquals(SwipeJudge.Grade.CLOSE, SwipeJudge.judge(0f, -200f, 0f, -2000f, th))
        // 反向速度（拖出去又往回甩）不加成 —— 四个方向都不能靠反向速度生效
        assertNull(SwipeJudge.judge(-200f, 0f, 9000f, 0f, th))
        assertNull(SwipeJudge.judge(0f, -200f, 0f, 9000f, th))
        // 正向位移 + 反向高速：投影终点会穿过原点、落到反方向，必须判 null
        // （卡住 abs(d + v * PROJECT_S) 那种「取绝对值越过原点」的错误写法）
        assertNull(SwipeJudge.judge(200f, 0f, -9000f, 0f, th))
        assertNull(SwipeJudge.judge(0f, 200f, 0f, -9000f, th))
    }
}
