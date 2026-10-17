package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test

/** v1.66 首次配置引导：步骤没做完不能往后、可以往回；老用户不拦 */
class OnboardingTest {
    private val none = Onboarding.State(overlay = false, apiReady = false, a11ySupported = true, a11yOn = false, notifications = false, battery = false)
    private val all = none.copy(overlay = true, apiReady = true, a11yOn = true, notifications = true, battery = true)

    @Test fun everyInstallHasApiStep() {
        val pub = Onboarding.steps(none)
        assertEquals(Onboarding.Step.WELCOME, pub.first())
        assertEquals(Onboarding.Step.FINISH, pub.last())
        assertTrue(Onboarding.Step.API in pub)
        // 接口没填时这一步本身没完成
        assertFalse(Onboarding.done(Onboarding.Step.API, none))
        assertTrue(Onboarding.done(Onboarding.Step.API, none.copy(apiReady = true)))
    }

    @Test fun cannotAdvancePastUnfinishedStep() {
        val steps = Onboarding.steps(none)
        val ov = steps.indexOf(Onboarding.Step.OVERLAY)
        assertTrue("欢迎页没有前置条件", Onboarding.canGo(steps, 0, ov, none))
        assertFalse("悬浮窗没开不能往后", Onboarding.canGo(steps, ov, ov + 1, none))
        assertFalse("也不能一下跳过好几页", Onboarding.canGo(steps, 0, steps.size - 1, none))
        assertTrue("开了就能往后", Onboarding.canGo(steps, ov, ov + 1, none.copy(overlay = true)))
        // 接口没填：悬浮窗开了也卡在接口这一步
        val api = steps.indexOf(Onboarding.Step.API)
        assertFalse(Onboarding.canGo(steps, api, api + 1, none.copy(overlay = true)))
        assertTrue(Onboarding.canGo(steps, api, api + 1, none.copy(overlay = true, apiReady = true)))
    }

    @Test fun canAlwaysGoBackButNotOutOfRange() {
        val steps = Onboarding.steps(none)
        assertTrue(Onboarding.canGo(steps, 3, 2, none))
        assertTrue(Onboarding.canGo(steps, 3, 0, none))
        assertFalse(Onboarding.canGo(steps, 0, -1, none))
        assertFalse(Onboarding.canGo(steps, steps.size - 1, steps.size, all))
    }

    @Test fun requiredStepsCannotBeSkippedOptionalCan() {
        assertFalse(Onboarding.canSkip(Onboarding.Step.OVERLAY))
        assertFalse(Onboarding.canSkip(Onboarding.Step.API))
        assertTrue(Onboarding.canSkip(Onboarding.Step.CAPTURE))
        assertTrue(Onboarding.canSkip(Onboarding.Step.NOTIFY))
        assertTrue(Onboarding.canSkip(Onboarding.Step.BATTERY))
        // 必需步骤即便记了「跳过」也不算完成
        val s = none.copy(skipped = setOf("overlay", "api"))
        assertFalse(Onboarding.done(Onboarding.Step.OVERLAY, s))
        assertFalse(Onboarding.done(Onboarding.Step.API, s))
        // 推荐步骤点了「先不开」就能往后
        val steps = Onboarding.steps(none)
        val cap = steps.indexOf(Onboarding.Step.CAPTURE)
        val ok = none.copy(overlay = true, apiReady = true)
        assertFalse(Onboarding.canGo(steps, cap, cap + 1, ok))
        assertTrue(Onboarding.canGo(steps, cap, cap + 1, ok.copy(skipped = setOf("capture"))))
    }

    @Test fun oldSystemWithoutA11yAutoPassesCapture() {
        assertTrue(Onboarding.done(Onboarding.Step.CAPTURE, none.copy(a11ySupported = false)))
    }

    @Test fun reachableStopsAtFirstUnfinished() {
        val steps = Onboarding.steps(none)
        assertEquals(steps.indexOf(Onboarding.Step.OVERLAY), Onboarding.reachable(steps, none))
        assertEquals(steps.indexOf(Onboarding.Step.API), Onboarding.reachable(steps, none.copy(overlay = true)))
        assertEquals(steps.size - 1, Onboarding.reachable(steps, all))
    }

    @Test fun showOnlyForNewUsersOrUnfinishedRuns() {
        assertTrue("全新安装", Onboarding.shouldShow(false, false, emptySet()))
        assertTrue("与配置无关的键不算老用户", Onboarding.shouldShow(false, false, setOf("updateLastCheck")))
        assertFalse("老用户配置过接口", Onboarding.shouldShow(false, false, setOf("baseUrl")))
        assertFalse("老用户只启动过小球", Onboarding.shouldShow(false, false, setOf("orbWanted", "orbX")))
        assertTrue("引导走到一半（已经填了接口）再回来，接着走", Onboarding.shouldShow(false, true, setOf("baseUrl")))
        assertFalse("走完了", Onboarding.shouldShow(true, true, emptySet()))
    }
}
