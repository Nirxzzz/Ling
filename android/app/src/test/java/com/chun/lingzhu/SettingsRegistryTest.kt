package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页改版（微信式重新分类）的「注册表完整性」：只改呈现和组织，**一个旧设置项都不能丢**。
 *
 * 钉的是决定，不是文案：
 *  - 盘点出的旧项总数、每个旧组各有几项 —— 谁删了 / 漏登一项立刻红；
 *  - 每项恰好一个新位置，且新位置是真实存在的卡片（或首页）；
 *  - 8 个分类都非空、每张卡片至少有一项（不出空白卡）；
 *  - 14 个旧折叠组 id 原样保留为卡片（跳转 EXTRA_API / EXTRA_MODES 和 Prefs.openGroups 存档键不变）。
 */
class SettingsRegistryTest {
    private val R = SettingsRegistry

    @Test fun inventoryCountMatchesAndIdsUnique() {
        assertEquals(R.INVENTORY_COUNT, R.ITEMS.size)
        val ids = (R.ITEMS + R.NEW_ITEMS).map { it.id }
        assertEquals("设置项 id 不许重复", ids.size, ids.toSet().size)
    }

    /** 每个旧组的项数（照 设置项盘点.md 第一节）。漏登 / 误删一项，这里对应那组就少一 */
    @Test fun perOldGroupCountsArePinned() {
        val expect = mapOf(
            R.OLD_HUB to 6, R.G_AUTH to 7, R.G_KEEP to 6, R.G_DATA to 11, R.G_API to 11, R.G_SEARCH to 10,
            R.G_TRANSLATE to 7, R.G_MODES to 7, R.G_PANEL to 11, R.G_SCAN to 4, R.G_COLOR to 5, R.G_CUSTOM to 1,
            R.G_WORDS_POP to 6, R.G_WORDS_DECK to 8, R.G_REMIND to 18,
        )
        assertEquals(expect, R.ITEMS.groupingBy { it.oldGroup }.eachCount())
        assertEquals(R.INVENTORY_COUNT, expect.values.sum())
    }

    @Test fun everyItemHasExactlyOneRealNewLocation() {
        val secIds = R.SECTIONS.map { it.id }.toSet()
        for (it in R.ITEMS + R.NEW_ITEMS) {
            assertTrue("「${it.name}」的新位置 ${it.section} 不存在", it.section in secIds || it.section == R.S_HUB)
            assertTrue("「${it.name}」的旧位置 ${it.oldGroup} 不认识", it.oldGroup in R.OLD_GROUPS.keys || it.oldGroup == R.OLD_HUB || it.oldGroup == R.OLD_NEW)
        }
        // 「合并」的只能是与别处重复的入口，且必须写明合并到哪
        for (it in R.ITEMS.filter { it.merged != null }) assertTrue(it.merged!!.isNotBlank())
        assertTrue("合并项只该是个别重复入口", R.ITEMS.count { it.merged != null } <= 2)
    }

    @Test fun sectionsAreUniqueAndBelongToKnownCategories() {
        val ids = R.SECTIONS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (s in R.SECTIONS) assertTrue("${s.id} 的分类 ${s.cat} 不在 CATEGORIES", s.cat in R.CATEGORIES)
        assertEquals(8, R.CATEGORIES.size)
        assertEquals(R.CATEGORIES.size, R.CATEGORIES.toSet().size)
    }

    @Test fun everyCategoryAndEverySectionIsNonEmpty() {
        val live = (R.ITEMS + R.NEW_ITEMS).filter { it.merged == null }
        for (c in R.CATEGORIES) assertTrue("分类「$c」是空的", R.SECTIONS.any { it.cat == c })
        for (s in R.SECTIONS) assertTrue("卡片「${s.title}」里一项都没有", live.any { it.section == s.id })
    }

    /** 旧折叠组 id 原样保留为卡片；MainActivity 的 GROUPS / CATEGORIES 都由注册表转出 */
    @Test fun oldGroupsSurviveAsCardsAndActivityUsesRegistry() {
        assertEquals(14, R.OLD_GROUPS.size)
        for (g in R.OLD_GROUPS.keys) assertNotNull("旧组 $g 没有对应卡片", R.section(g))
        assertEquals(R.OLD_GROUPS.keys.toList(), MainActivity.GROUPS.map { it.id })
        for (g in MainActivity.GROUPS) assertEquals(R.categoryOf(g.id), g.tab)
        assertEquals(R.CATEGORIES, MainActivity.CATEGORIES)
        // 两条外部跳转的落点：接口 → AI 接口与翻译；识别模式 → 小球与识屏
        assertEquals(MainActivity.C_AI, R.categoryOf(MainActivity.G_API))
        assertEquals(MainActivity.C_ORB, R.categoryOf(MainActivity.G_MODES))
    }

    /** 新分类按用途收拢：提醒类不再和学习、同步混在一组 */
    @Test fun regroupedByPurpose() {
        fun cat(id: String) = R.categoryOf(R.ITEMS.first { it.id == id }.section)
        assertEquals(R.C_STUDY, cat("R16"))      // 导入知识卡 → 学习与词库
        assertEquals(R.C_DATA, cat("R9"))        // 局域网同步 → 数据与同步
        assertEquals(R.C_POP, cat("W1"))         // 弹词开关 → 弹出与提醒
        assertEquals(R.C_POP, cat("R7"))         // 免打扰 → 弹出与提醒
        assertEquals(R.C_ABOUT, cat("D9"))       // 检查更新 → 关于与更新
        assertEquals(R.C_PERM, cat("A3"))        // 权限入口 → 权限与后台
    }
}
