package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K5 本地知识库检索：分词（中英混合）、BM25 单调性、topK 截断、零分空返回、
 * 触发判定（含否定样本）、上下文块格式与 1200 字截断。
 */
class KnowledgeSearchTest {

    private fun kc(id: Long, deck: String = "默认", front: String, back: String = ""): KnowledgeCard.KCData =
        KnowledgeCard.KCData(id, deck, front, back, "manual", 1_000_000L,
            Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, 1_000_000L))

    private val lib = listOf(
        kc(1, "数学", "什么是导数", "导数是函数在某一点的瞬时变化率，几何上是切线斜率。"),
        kc(2, "物理", "牛顿第二定律", "F = ma，力等于质量乘加速度。"),
        kc(3, "英语", "how do you spell", "拼写：spell，两个 l。"),
    )

    // ---------------- 分词 ----------------

    @Test fun tokenizeChineseAsBigrams() {
        // 「我的」两个停用字 → 整 gram 丢掉；「卡片」这类实词留下
        assertEquals(listOf("的卡", "卡片"), KnowledgeSearch.tokenize("我的卡片"))
    }

    @Test fun tokenizeEnglishAsWords() {
        assertEquals(listOf("how", "do", "you", "spell"), KnowledgeSearch.tokenize("How do you spell"))
    }

    @Test fun tokenizeMixedChineseAndEnglish() {
        val t = KnowledgeSearch.tokenize("我的卡片 R7250 怎么设置")
        assertTrue("中文 2-gram 在: $t", t.contains("卡片"))
        assertTrue("型号整词保留: $t", t.contains("r7250"))
    }

    @Test fun tokenizeDropsChineseStopwords() {
        val t = KnowledgeSearch.tokenize("的了呢啊")
        assertTrue("全是停用词应被丢掉: $t", t.isEmpty())
    }

    @Test fun tokenizeDropsEnglishStopwords() {
        val t = KnowledgeSearch.tokenize("the a is")
        assertTrue("英文停用词全丢: $t", t.isEmpty())
    }

    @Test fun tokenizeBlankIsEmpty() {
        assertTrue(KnowledgeSearch.tokenize("").isEmpty())
        assertTrue(KnowledgeSearch.tokenize("   \n ").isEmpty())
        assertTrue(KnowledgeSearch.tokenize("，。！？——").isEmpty())   // 纯标点
    }

    @Test fun tokenizeSingleChineseCharKeptWhenContentWord() {
        assertEquals(listOf("卡"), KnowledgeSearch.tokenize("卡"))
        assertTrue("单字停用词丢掉", KnowledgeSearch.tokenize("的").isEmpty())
    }

    // ---------------- BM25 ----------------

    @Test fun bm25HigherWithMoreMatches() {
        val one = KnowledgeSearch.bm25Score(listOf("导数"), "导数")
        val many = KnowledgeSearch.bm25Score(listOf("导数"), "导数 导数 导数")
        assertTrue("词频越多分越高: $many vs $one", many > one)
    }

    @Test fun bm25ZeroWhenNoOverlap() {
        assertEquals(0.0, KnowledgeSearch.bm25Score(listOf("导数"), "牛顿第二定律 F = ma"), 1e-9)
    }

    @Test fun bm25ZeroOnEmptyQueryOrDoc() {
        assertEquals(0.0, KnowledgeSearch.bm25Score(emptyList(), "导数"), 1e-9)
        assertEquals(0.0, KnowledgeSearch.bm25Score(listOf("导数"), ""), 1e-9)
    }

    @Test fun bm25RewardsShorterDocAtSameTermFrequency() {
        val q = listOf("导数")
        val short = KnowledgeSearch.bm25Score(q, "导数")
        val padded = KnowledgeSearch.bm25Score(q, "导数 " + "字".repeat(400))
        assertTrue("长度归一：短文档应更高: ${short} vs ${padded}", short > padded)
    }

    @Test fun bm25MatchesCountMonotonic() {
        val q = KnowledgeSearch.tokenize("导数 牛顿 定律")
        val s1 = KnowledgeSearch.bm25Score(q, "导数")
        val s2 = KnowledgeSearch.bm25Score(q, "导数 牛顿")
        val s3 = KnowledgeSearch.bm25Score(q, "导数 牛顿 定律")
        assertTrue("命中越多单调上升: $s1 < $s2 < $s3", s1 < s2 && s2 < s3)
    }

    @Test fun bm25DuplicateQueryTermsCountedOnce() {
        assertEquals(
            KnowledgeSearch.bm25Score(listOf("导数"), "导数 定义"),
            KnowledgeSearch.bm25Score(listOf("导数", "导数", "导数"), "导数 定义"),
            1e-9,
        )
    }

    // ---------------- 检索 ----------------

    @Test fun searchFindsTheRightCard() {
        val hits = KnowledgeSearch.search(lib, "导数是什么")
        assertEquals(1L, hits[0].id)
    }

    @Test fun searchSkipsZeroScoreCards() {
        val hits = KnowledgeSearch.search(lib, "光合作用")
        assertTrue("没有一张卡沾边就该是空: $hits", hits.isEmpty())
    }

    @Test fun searchRespectsTopK() {
        val many = (1L..10L).map { kc(it, "数学", "考点$it", "考点$it 的详细说明") }
        assertEquals(3, KnowledgeSearch.search(many, "考点", topK = 3).size)
        assertEquals(1, KnowledgeSearch.search(many, "考点", topK = 1).size)
        assertEquals(10, KnowledgeSearch.search(many, "考点", topK = 99).size)
    }

    @Test fun searchNonPositiveTopKGivesEmpty() {
        assertTrue(KnowledgeSearch.search(lib, "导数", topK = 0).isEmpty())
        assertTrue(KnowledgeSearch.search(lib, "导数", topK = -1).isEmpty())
    }

    @Test fun searchEmptyLibraryOrQueryGivesEmpty() {
        assertTrue(KnowledgeSearch.search(emptyList(), "导数").isEmpty())
        assertTrue(KnowledgeSearch.search(lib, "   ").isEmpty())
        assertTrue(KnowledgeSearch.search(lib, "的了吗呢").isEmpty())   // 全停用词 → 没有查询词
    }

    @Test fun searchRanksByScoreNotById() {
        // 两张卡都命中「导数」，但第二张词更密、篇幅更短 → BM25 应把它排前面（长度归一 + 词频）
        val l = listOf(
            kc(1, "数学", "导数", "导数 无关 内容 补充"),
            kc(2, "数学", "导数 导数", "导数"),
        )
        assertEquals(2L, KnowledgeSearch.search(l, "导数")[0].id)
    }

    @Test fun scoreAllExposesScoreAndIsSorted() {
        val s = KnowledgeSearch.scoreAll(lib, "导数 牛顿")
        assertEquals(2, s.size)
        assertTrue("分数递减", s[0].second >= s[1].second)
        assertEquals(setOf(1L, 2L), s.map { it.first.id }.toSet())
    }

    @Test fun docTextHasBothFrontAndBack() {
        val t = KnowledgeSearch.docText(kc(1, "数学", "正面词", "背面词"))
        assertTrue(t.contains("正面词"))
        assertTrue(t.contains("背面词"))
        assertTrue("背面能被查到", KnowledgeSearch.bm25Score(listOf("背面"), t) > 0.0)
    }

    // ---------------- 触发判定 ----------------

    @Test fun shouldSearchOnIntentPhrases() {
        assertTrue(KnowledgeSearch.shouldSearch("看看我的卡片"))
        assertTrue(KnowledgeSearch.shouldSearch("我学过这个"))
        assertTrue(KnowledgeSearch.shouldSearch("知识库里有没有讲导数的"))
        assertTrue(KnowledgeSearch.shouldSearch("之前存的笔记里有吗"))
        assertTrue(KnowledgeSearch.shouldSearch("查一下我的知识卡"))
        assertTrue(KnowledgeSearch.shouldSearch("我收藏的里有这道题吗"))
    }

    @Test fun shouldSearchOnSlashK() {
        assertTrue(KnowledgeSearch.shouldSearch("/k 导数"))
        assertTrue(KnowledgeSearch.shouldSearch("/k：导数"))
        assertTrue(KnowledgeSearch.shouldSearch("  /K 牛顿 "))
    }

    @Test fun shouldSearchOnEnglishIntent() {
        assertTrue(KnowledgeSearch.shouldSearch("check my cards"))
        assertTrue(KnowledgeSearch.shouldSearch("look in my knowledge base"))
        assertTrue(KnowledgeSearch.shouldSearch("from my saved card"))
    }

    @Test fun shouldSearchOnNegationLikePhraseStillTriggers() {
        // 否定 / 疑问语气不该把检索关掉 —— 用户问「我的卡片里没有这个吗」也是要查
        assertTrue(KnowledgeSearch.shouldSearch("查看我的卡片列表"))
        assertTrue(KnowledgeSearch.shouldSearch("我的卡片里没有这个吗"))
    }

    @Test fun shouldNotSearchOnPlainQuestion() {
        assertFalse(KnowledgeSearch.shouldSearch("这道题怎么解"))
        assertFalse(KnowledgeSearch.shouldSearch("北京天气怎么样"))
        assertFalse(KnowledgeSearch.shouldSearch("今天汇率多少"))
    }

    @Test fun shouldNotSearchOnStopwordOnlyInput() {
        assertFalse("的了吗呢不该触发", KnowledgeSearch.shouldSearch("的了吗呢"))
        assertFalse(KnowledgeSearch.shouldSearch(""))
        assertFalse(KnowledgeSearch.shouldSearch("   "))
        assertFalse(KnowledgeSearch.shouldSearch("呢？"))
    }

    @Test fun shouldNotSearchOnCardAsGenericWord() {
        assertFalse("泛词「卡片」不算引用知识库", KnowledgeSearch.shouldSearch("银行卡怎么查余额"))
        assertFalse(KnowledgeSearch.shouldSearch("sd 卡片怎么插"))
    }

    @Test fun stripTriggerRemovesSlashK() {
        assertEquals("导数", KnowledgeSearch.stripTrigger("/k 导数"))
        assertEquals("导数", KnowledgeSearch.stripTrigger("/k：导数"))
        assertEquals("我的卡片", KnowledgeSearch.stripTrigger("我的卡片"))
    }

    // ---------------- 上下文块 ----------------

    @Test fun contextBlockEmptyHitsGivesEmpty() {
        assertEquals("", KnowledgeSearch.contextBlock(emptyList()))
    }

    @Test fun contextBlockFormatHasHeadDeckAndRule() {
        val b = KnowledgeSearch.contextBlock(listOf(kc(1, "数学", "什么是导数", "瞬时变化率")))
        assertTrue("有标题: $b", b.startsWith(KnowledgeSearch.HEAD))
        assertTrue("有编号: $b", b.contains("1. 【数学】什么是导数"))
        assertTrue("有背面: $b", b.contains("背面：瞬时变化率"))
        assertTrue("有引用规矩: $b", b.contains(KnowledgeSearch.USE_RULE))
        assertTrue("标了来自知识卡: $b", b.contains("来自知识卡"))
    }

    @Test fun contextBlockNumbersAllHits() {
        val b = KnowledgeSearch.contextBlock(listOf(kc(1, "A", "q1", "a1"), kc(2, "B", "q2", "a2")))
        assertTrue(b.contains("1. 【A】q1"))
        assertTrue(b.contains("2. 【B】q2"))
    }

    @Test fun contextBlockSkipsBlankBack() {
        val b = KnowledgeSearch.contextBlock(listOf(kc(1, "数学", "只有正面")))
        assertTrue("没背面就不写背面行: $b", !b.contains("背面："))
    }

    @Test fun contextBlockBlankDeckFallsBack() {
        assertTrue(KnowledgeSearch.contextBlock(listOf(kc(1, "  ", "q", "a"))).contains("【默认】"))
    }

    @Test fun contextBlockClippedToLimit() {
        val fat = (1L..6L).map { kc(it, "数学", "正面$it", "背面$it " + "字".repeat(400)) }
        val b = KnowledgeSearch.contextBlock(fat)
        assertTrue("超长被截断，实际长度 ${b.length}", b.length <= KnowledgeSearch.CONTEXT_MAX + 1)
        assertTrue("截断留省略号", b.endsWith("…"))
    }

    @Test fun contextBlockShortEnoughNotClipped() {
        val b = KnowledgeSearch.contextBlock(listOf(kc(1, "数学", "q", "a")))
        assertTrue("短内容不该被截: ${b.length}", b.length <= KnowledgeSearch.CONTEXT_MAX)
        assertFalse(b.endsWith("…"))
    }

    @Test fun contextBlockClipsEachCardIndividually() {
        val b = KnowledgeSearch.contextBlock(listOf(kc(1, "数学", "正面" + "长".repeat(300), "背面" + "长".repeat(300))))
        assertTrue("单卡正面截到上限: ${b.length}", b.length < 600)
    }

    // ---------------- 注入 ----------------

    @Test fun injectEmptyBlockLeavesPromptUntouched() {
        val p = Prompts.system("general", "standard", false)
        assertEquals(p, KnowledgeSearch.inject(p, ""))
    }

    @Test fun injectPutsBlockBeforeTermsRule() {
        val p = Prompts.system("general", "standard", false, terms = true)
        val out = KnowledgeSearch.inject(p, "【知识库块】")
        assertTrue(out.contains("【知识库块】"))
        assertTrue("在 TERMS_RULE 之前", out.indexOf("【知识库块】") < out.indexOf(Prompts.TERMS_RULE))
    }

    @Test fun injectAppendsWhenNoTermsRule() {
        val p = Prompts.system("general", "standard", false, terms = false)
        val out = KnowledgeSearch.inject(p, "【知识库块】")
        assertTrue(out.startsWith(p))
        assertTrue(out.endsWith("【知识库块】"))
    }

    // ---------------- 端到端（走 source 闭包，跟 Solver 里那条链路同形） ----------------

    @Test fun blockForEmptyWithoutSource() {
        KnowledgeSearch.source = null
        assertEquals("", KnowledgeSearch.blockFor("看看我的卡片"))
    }

    @Test fun blockForSkipsWhenNotTriggered() {
        KnowledgeSearch.source = { lib }
        assertEquals("", KnowledgeSearch.blockFor("这道题怎么解"))
    }

    @Test fun blockForEmptyWhenNoHit() {
        KnowledgeSearch.source = { lib }
        assertEquals("", KnowledgeSearch.blockFor("我的卡片里有量子力学吗"))
    }

    @Test fun blockForReturnsBlockOnHit() {
        KnowledgeSearch.source = { lib }
        val b = KnowledgeSearch.blockFor("我的卡片里有讲导数的吗")
        assertTrue("有命中就该有块: $b", b.contains("什么是导数"))
        assertTrue(b.startsWith(KnowledgeSearch.HEAD))
    }

    @Test fun blockForStripsSlashKBeforeSearching() {
        KnowledgeSearch.source = { lib }
        assertTrue(KnowledgeSearch.blockFor("/k 导数").contains("什么是导数"))
    }
}