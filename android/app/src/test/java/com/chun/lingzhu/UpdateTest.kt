package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.65 更新通道：清单解析 / 新版判断 / 自动检查节流 */
class UpdateTest {

    @Test fun parseValidManifest() {
        val m = Update.parse("""{"versionCode":166,"versionName":"1.66.0","url":"https://x/a.apk","notes":"修了些问题"}""")!!
        assertEquals(166, m.versionCode)
        assertEquals("1.66.0", m.versionName)
        assertEquals("https://x/a.apk", m.url)
        assertEquals("修了些问题", m.notes)
    }

    @Test fun parseRejectsBadManifest() {
        assertNull(Update.parse(null))
        assertNull(Update.parse("垃圾"))
        assertNull(Update.parse("""{"url":"https://x/a.apk"}"""))            // 没 versionCode
        assertNull(Update.parse("""{"versionCode":166}"""))                  // 没 url
        assertNull(Update.parse("""{"versionCode":166,"url":"ftp://x"}"""))  // 非 http(s)
    }

    @Test fun notesOptional() {
        val m = Update.parse("""{"versionCode":2,"url":"https://x/a.apk"}""")!!
        assertEquals("", m.notes)
        assertEquals("2", m.versionName)   // 缺 versionName 用数字兜底
    }

    @Test fun newerOnly() {
        val m = Update.parse("""{"versionCode":166,"url":"https://x/a.apk"}""")!!
        assertTrue(Update.isNewer(m, 165))
        assertFalse(Update.isNewer(m, 166))
        assertFalse(Update.isNewer(m, 200))
    }

    @Test fun autoCheckThrottleAndUrlGate() {
        val now = 1_000_000_000L
        assertFalse(Update.shouldAutoCheck(0, now, ""))                       // 没填地址：永不自动查
        assertTrue(Update.shouldAutoCheck(0, now, "https://x/m.json"))        // 首次：查
        assertFalse(Update.shouldAutoCheck(now, now, "https://x/m.json"))     // 刚查过：不查
        assertTrue(Update.shouldAutoCheck(now - Update.CHECK_INTERVAL_MS, now, "https://x/m.json"))  // 隔天：查
        assertTrue(Update.shouldAutoCheck(now, now, "https://x/m.json", interval = 0))  // 手动不节流
    }

    // ---------------- v1.77：自动检查不再自己开下载（用户反馈「每次打开页面突然弹下载」） ----------------

    private val newer = Update.Manifest(178, "1.76.0", "https://x/a.apk", "修了些问题")
    private val same = Update.Manifest(177, "1.77.0", "https://x/a.apk", "")

    /** 反馈的根因那条：自动检查发现新版，只给文案 + 一个下载按钮，绝不替用户开下载 */
    @Test fun autoCheckNewVersionOnlyOffersButton() {
        val r = Update.check(newer, localCode = 177, localName = "1.77.0", manual = false)
        assertTrue(r is Update.CheckResult.NewVersion)
        r as Update.CheckResult.NewVersion
        assertTrue("新版文案要把清单里的版本名给用户看", r.text.contains("1.76.0"))
        assertTrue("按钮带的是这个包的版本，不是别的", r.button.contains("1.76.0"))
        assertEquals(newer, r.manifest)          // 按钮点的是这个包，不是别的
    }

    @Test fun autoCheckUpToDateStaysSilent() {
        // 自动检查遇到「已是最新」：什么都不写，页面保持空白（不写「检查中…」也不写「✓ 已是最新」）
        assertSame(Update.CheckResult.Silent, Update.check(same, 177, "1.77.0", manual = false))
        // 远端还更旧（本机比清单新）也一样静默
        assertSame(Update.CheckResult.Silent, Update.check(Update.Manifest(150, "1.50.0", "https://x/a.apk", ""), 177, "1.77.0", manual = false))
    }

    @Test fun autoCheckBadManifestFailsSilently() {
        // 自动检查读不到清单：静默失败，不用错误文案吓人
        assertSame(Update.CheckResult.Silent, Update.check(null, 177, "1.77.0", manual = false))
    }

    @Test fun manualCheckAlwaysExplains() {
        // 手动检查：读不到 / 已是最新都要说清楚，用户是自己点的，得给反馈
        val f = Update.check(null, 177, "1.77.0", manual = true)
        assertTrue(f is Update.CheckResult.Failed)
        assertTrue((f as Update.CheckResult.Failed).text.isNotBlank())

        val u = Update.check(same, 177, "1.77.0", manual = true)
        assertTrue(u is Update.CheckResult.UpToDate)
        assertTrue((u as Update.CheckResult.UpToDate).text.contains("1.77.0"))

        // 手动检查发现新版：和新版自动检查一样，也是只给按钮，不自己下
        val n = Update.check(newer, 177, "1.77.0", manual = true)
        assertTrue(n is Update.CheckResult.NewVersion)
        assertEquals(newer, (n as Update.CheckResult.NewVersion).manifest)
        assertTrue(n.button.contains("1.76.0"))
    }

    /** 没有新版时按钮不亮：CheckResult 里就没有 NewVersion，Activity 拿不到 manifest 可点 */
    @Test fun noButtonWithoutNewVersion() {
        assertFalse(Update.check(same, 177, "1.77.0", manual = true) is Update.CheckResult.NewVersion)
        assertFalse(Update.check(null, 177, "1.77.0", manual = true) is Update.CheckResult.NewVersion)
    }

    /** notes 照旧截断到 200 字，别把设置页撑爆 */
    @Test fun notesTruncatedInNewVersionText() {
        val long = Update.Manifest(178, "1.76.0", "https://x/a.apk", "长".repeat(500))
        val r = Update.check(long, 177, "1.77.0", manual = false) as Update.CheckResult.NewVersion
        assertTrue(r.text.contains("长".repeat(200)))
        assertFalse("超过 200 字的尾巴不能漏进设置页", r.text.contains("长".repeat(201)))
    }

    /** notes 为空时不留空行 */
    @Test fun blankNotesNoExtraNewline() {
        val r = Update.check(Update.Manifest(178, "1.76.0", "https://x/a.apk", ""), 177, "1.77.0", manual = false)
            as Update.CheckResult.NewVersion
        assertFalse(r.text.endsWith("\n"))
    }

    // ---------------- v1.83：清单地址回落（用户反馈「手机更新检查失败 / 清空输入框后再也查不了」） ----------------

    private val builtIn = "https://builtin/manifest.json"

    /** 手填有效值 → [手填, 内置]：手填的优先，内置源兜底 */
    @Test fun sourcesCustomFirstBuiltInFallback() {
        assertEquals(listOf("https://mine/m.json", builtIn), Update.sources("https://mine/m.json", builtIn))
    }

    /** 手填为空 / 空白 / 从没设置 → 只回内置源（空串不再致命） */
    @Test fun sourcesBlankCustomFallsBackToBuiltIn() {
        assertEquals(listOf(builtIn), Update.sources("", builtIn))
        assertEquals(listOf(builtIn), Update.sources("   ", builtIn))
        assertEquals(listOf(builtIn), Update.sources(null, builtIn))
    }

    /** 手填等于内置 → 只留一个，别对同一个地址白试两次 */
    @Test fun sourcesSameAddressDeduplicated() {
        assertEquals(listOf(builtIn), Update.sources(builtIn, builtIn))
        assertEquals(listOf(builtIn), Update.sources("  $builtIn  ", builtIn))   // 带空格也算同一个
    }

    /** 手填带前后空格 → 用 trim 后的值 */
    @Test fun sourcesTrimsCustom() {
        assertEquals(listOf("https://mine/m.json", builtIn), Update.sources("  https://mine/m.json  ", builtIn))
    }

    /** 这条性质钉死「空串不再致命」：清空输入框后仍是「没有源」，而不是退回到维护者服务器。
     *  （Prefs 那层读 SharedPreferences，JVM 单测碰不到，所以在纯函数这层断言。） */
    @Test fun blankCustomNeverKillsUpdateChannel() {
        assertEquals(emptyList<String>(), Update.sources("", Update.DEFAULT_MANIFEST_URL))
        assertEquals(emptyList<String>(), Update.sources(null))
        assertEquals("", Update.DEFAULT_MANIFEST_URL)
    }
}
