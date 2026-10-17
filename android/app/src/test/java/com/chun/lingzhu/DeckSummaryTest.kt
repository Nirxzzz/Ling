package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背单词设置页词库区块（v1.76.5）：默认态只渲染摘要行，不再把词库全文塞进 EditText。
 * 这里测的是「摘要 + 展开」背后的纯逻辑：摘要口径、缓存正确性、增删改勾选。
 */
class DeckSummaryTest {
    private val deck = Words.Deck("d1", "古诗", "床前明月光：疑是地上霜\n静夜思：举头望明月\n春晓：春眠不觉晓")

    @Test fun summaryCountsCardsAndMastered() {
        val cards = Words.parse(deck.text)
        assertEquals(3, cards.size)
        // 只认「连续认识够 MASTER_STREAK 次」的卡；没记录、只见过、连 2 次的都不算掌握
        val st = mapOf(
            Words.key(deck.id, cards[0]) to Words.Stat(3, 5, 0),
            Words.key(deck.id, cards[1]) to Words.Stat(2, 4, 0),
            Words.key(deck.id, cards[2]) to Words.Stat(0, 1, 0)
        )
        val s = Words.summaries(listOf(deck), st).single()
        assertEquals(deck.id, s.id); assertEquals("古诗", s.name)
        assertEquals(3, s.total); assertEquals(1, s.mastered)
    }

    @Test fun summaryWithNoProgressIsAllZeroMastered() {
        val s = Words.summaries(listOf(deck), emptyMap()).single()
        assertEquals(3, s.total); assertEquals(0, s.mastered)
    }

    @Test fun summaryCountsDedupeByFront() {
        // 同一正面出现两次只算一条，摘要条数必须跟真正会弹的卡片数一致
        val dup = Words.Deck("d2", "重复", "abandon\nabandon\nability")
        val s = Words.summaries(listOf(dup), emptyMap()).single()
        assertEquals(2, s.total)
        assertEquals(Words.parse(dup.text).size, s.total)
    }

    @Test fun summarySeparatesMasteryByDeckId() {
        // 同一个正面在两个词库里各记各的：只把其中一个词库记成掌握，另一格必须还是 0
        val other = Words.Deck("d2", "另一个", "床前明月光：疑是地上霜")
        val front = Words.parse(deck.text).first()
        val st = mapOf(Words.key(deck.id, front) to Words.Stat(9, 9, 0))
        val sums = Words.summaries(listOf(deck, other), st)
        assertEquals(1, sums[0].mastered)
        assertEquals(0, sums[1].mastered)
    }

    @Test fun summariesCoverEveryDeckInOrder() {
        val decks = listOf(deck, Words.Deck("d2", "公式", "sin²x + cos²x = 1"))
        val sums = Words.summaries(decks, emptyMap())
        assertEquals(listOf("d1", "d2"), sums.map { it.id })
        assertEquals(listOf("古诗", "公式"), sums.map { it.name })
        assertEquals(listOf(3, 1), sums.map { it.total })
    }

    @Test fun builtinDeckSummaryMatchesDirectParse() {
        // 内置英语词库的摘要口径跟直接 parse 一致（条数不能因为走缓存而偏）
        val s = Words.summaries(listOf(Words.Deck(Words.BUILTIN_EN, "英语常用词（内置）", Words.BUILTIN_EN_TEXT)), emptyMap()).single()
        assertEquals(Words.parse(Words.BUILTIN_EN_TEXT).size, s.total)
        assertEquals(0, s.mastered)
    }

    @Test fun emptyDeckSummarizesToZero() {
        // 新建词库是空正文：摘要该显示 0 条，且不能因此崩掉
        val s = Words.summaries(listOf(Words.Deck("d9", "我的词库 9", "")), emptyMap()).single()
        assertEquals(0, s.total); assertEquals(0, s.mastered)
    }

    @Test fun summaryDoesNotNeedToRenderFullText() {
        // 卡顿的根因是「为渲染把全文塞进控件」。摘要只依赖条数与掌握数：
        // 近万行词库（5 级内置词库全导入的规模）出摘要必须是常数级、不随行数放大成控件内容。
        val decks = (0 until 5).map { lv ->
            Words.Deck("wb_$lv", "内置 $lv", (0 until 10000).joinToString("\n") { "word${lv}_$it | 释义$it" })
        }
        val sums = Words.summaries(decks, emptyMap())
        assertEquals(5, sums.size)
        assertTrue(sums.all { it.total == 10000 })
        // 摘要对象里只有 id/名字/两个数字，不该把全文挂在上面
        assertTrue(sums.none { it.toString().contains("word0_1 |") })
    }

    @Test fun editableDecksExcludesBuiltin() {
        // 内置英语词库正文写在代码里，5 级内置词库正文在 assets 里，都没有可编辑的全文，所以不参与展开
        val decks = listOf(Words.Deck(Words.BUILTIN_EN, "英语常用词（内置）", Words.BUILTIN_EN_TEXT), deck)
        assertEquals(listOf("d1"), Words.editableDecks(decks).map { it.id })
        assertTrue(Words.editableDecks(listOf(Words.Deck(Words.BUILTIN_EN, "x", "y"))).isEmpty())
    }

    @Test fun editableDecksExcludesLevelDecks() {
        // v1.77：5 级词库开箱可选，正文来自 assets，同样不给「编辑 ▸」
        val levels = WordBank.decksOf(WordBank.LEVELS) { "word | 释义" }
        assertEquals(WordBank.LEVELS.size, levels.size)
        assertTrue(Words.editableDecks(levels).isEmpty())
        assertEquals(listOf("d1"), Words.editableDecks(levels + deck).map { it.id })
    }

    @Test fun levelDecksUseLevelIdNameAndRawText() {
        // 开箱可选的清单：id = wb_<级 id>（与老版本导入后的 id 相同，进度天然并轨）、名字 = 级名、正文 = assets 原文
        val levels = WordBank.decksOf(WordBank.LEVELS) { "  raw-${it.id}\n" }
        assertEquals(WordBank.LEVELS.map { WordBank.DECK_PREFIX + it.id }, levels.map { it.id })
        assertEquals(WordBank.LEVELS.map { it.name }, levels.map { it.name })
        assertEquals("  raw-cet4\n", levels.first { it.id == "wb_cet4" }.text)
    }

    @Test fun levelDecksKeepGivenOrderAndSkipMissingOnes() {
        // assets 里缺哪级就不出哪级（老包 / 安装包不完整）；顺序仍按传进来的 LEVELS 顺序
        val only = WordBank.LEVELS.filter { it.id == "ielts" || it.id == "junior" }
        val decks = WordBank.decksOf(only) { it.id }
        assertEquals(listOf("wb_junior", "wb_ielts"), decks.map { it.id })
        assertTrue(WordBank.decksOf(emptyList()) { "" }.isEmpty())
    }

    @Test fun isLevelDeckTellsBuiltinLevelsApart() {
        assertTrue(WordBank.isLevelDeck("wb_cet4"))
        WordBank.LEVELS.forEach { assertTrue(WordBank.isLevelDeck(WordBank.deckId(it))) }
        // 自定义词库（d1）与内置英语词库（en）都不是内置分级
        assertTrue(!WordBank.isLevelDeck("d1"))
        assertTrue(!WordBank.isLevelDeck(Words.BUILTIN_EN))
        // 光有前缀不算：wb_ 前缀 + 不存在的级名同样是自定义词库
        assertTrue(!WordBank.isLevelDeck("wb_notalevel"))
        assertTrue(!WordBank.isLevelDeck(""))
    }

    @Test fun assembleOrdersBuiltinEnThenLevelsThenCustom() {
        // deckList()/allDecks() 的顺序口径：内置英语 → 各级（按 LEVELS 顺序）→ 自定义
        val en = Words.Deck(Words.BUILTIN_EN, "英语常用词（内置）", Words.BUILTIN_EN_TEXT)
        val levels = WordBank.decksOf(WordBank.LEVELS) { "w | 释义" }
        val list = WordBank.assemble(en, levels, listOf(deck))
        assertEquals(WordBank.LEVELS.size + 2, list.size)
        assertEquals(Words.BUILTIN_EN, list[0].id)
        // 中间几级必须严格按 LEVELS 顺序排列（不因后加的考研三级而错位）
        assertEquals(WordBank.LEVELS.map { WordBank.deckId(it) }, list.subList(1, 1 + WordBank.LEVELS.size).map { it.id })
        assertEquals("d1", list[WordBank.LEVELS.size + 1].id)
        // 两端都空时也只该剩内置英语
        assertEquals(listOf(Words.BUILTIN_EN), WordBank.assemble(en, emptyList(), emptyList()).map { it.id })
    }

    @Test fun levelDecksAreNotEditableButCustomOnSameIdIsDroppedToo() {
        // 老用户导入过的 wb_xxx 副本现在由内置分级直接供给，清单里不该再出现第二个同 id 的可编辑词库
        val level = Words.Deck("wb_cet4", "CET4", "abandon | v. 放弃")
        val stale = Words.Deck("wb_cet4", "CET4", "abandon | v. 放弃\nability | n. 能力")
        assertTrue(Words.editableDecks(listOf(level, stale)).isEmpty())
    }

    @Test fun togglingLevelDeckAddsAndRemovesLevelId() {
        // 勾选内置分级就是往 wordDecksOn 里增删 wb_<级 id>，与自定义词库同一套开关
        val on = setOf(Words.BUILTIN_EN)
        val withLevel = Words.toggleDeck(on, "wb_cet4", true)
        assertEquals(setOf(Words.BUILTIN_EN, "wb_cet4"), withLevel)
        assertEquals(on, Words.toggleDeck(withLevel, "wb_cet4", false))
        // 取消一个没勾过的级别不动其他勾选
        assertEquals(on, Words.toggleDeck(on, "wb_ielts", false))
    }

    @Test fun summaryForLevelDeckCountsItsOwnWords() {
        // 摘要口径对内置分级同样成立：条数 = 该级原文去重后的卡片数，已掌握按 wb_<级 id> 记
        val raw = "abandon | v. 放弃\nability | n. 能力\nability | n. 能力\n# 注释\n"
        val level = WordBank.decksOf(listOf(WordBank.LEVELS.first { it.id == "cet4" })) { raw }.single()
        val st = mapOf(Words.key(level.id, Words.Card("abandon", "v. 放弃")) to Words.Stat(3, 4, 0))
        val s = Words.summaries(listOf(level), st).single()
        assertEquals("wb_cet4", s.id)
        assertEquals(2, s.total)
        assertEquals(1, s.mastered)
    }

    @Test fun levelsSummaryMasteryIsSeparateFromBuiltinEn() {
        // 内置英语词库里同名单词的掌握记录不能串到 wb_xxx 上（key 带词库 id）
        val level = WordBank.decksOf(listOf(WordBank.LEVELS.first { it.id == "junior" })) { "abandon | v. 放弃" }.single()
        val en = Words.Deck(Words.BUILTIN_EN, "英语常用词（内置）", "abandon | v. 放弃；抛弃")
        val st = mapOf(Words.key(Words.BUILTIN_EN, Words.Card("abandon", "v. 放弃；抛弃")) to Words.Stat(5, 5, 0))
        val sums = Words.summaries(listOf(en, level), st)
        assertEquals(listOf(1, 0), sums.map { it.mastered })
    }

    @Test fun parseMemoizedIsStableAndTracksContent() {
        val text = "床前明月光：疑是地上霜"
        val a = Words.parseMemoized(text); val b = Words.parseMemoized(text)
        assertEquals(a, b)
        // 改了正文必须重新解析，不能拿旧的缓存当新词库的统计
        assertEquals(2, Words.parseMemoized(text + "\n静夜思：举头望明月").size)
        assertEquals(1, Words.parseMemoized(text).size)
    }

    @Test fun parseMemoizedSurvivesCacheEviction() {
        // 缓存装满会整体清空，之后仍然要算得对（不能因为清空而返回空列表）
        val texts = (0 until 40).map { "词条$it：释义$it" }
        val first = Words.parseMemoized(texts[0])
        assertEquals(1, first.size)
        texts.forEach { assertEquals(1, Words.parseMemoized(it).size) }
        assertEquals(listOf(Words.Card("词条0", "释义0")), Words.parseMemoized(texts[0]))
    }

    @Test fun decksMemoizedMatchesDirectParse() {
        val json = Words.decksToJson(listOf(deck))
        assertEquals(Words.decksFromJson(json), Words.decksMemoized(json))
        assertEquals(Words.decksFromJson(null), Words.decksMemoized(null))
        // 换一份 JSON（保存过）要能立刻反映，不能返回上一次的词库
        val edited = Words.decksToJson(listOf(deck.copy(text = "改过的正文")))
        assertEquals(1, Words.decksMemoized(edited).size)
        assertEquals("改过的正文", Words.decksMemoized(edited).single().text)
    }

    @Test fun saveDeckReplacesOnlyTarget() {
        val decks = listOf(deck, Words.Deck("d2", "公式", "a+b=c"))
        val saved = Words.saveDeck(decks, "d1", "  唐诗  ", "新正文")
        assertEquals("唐诗", saved[0].name); assertEquals("新正文", saved[0].text)
        assertEquals(decks[1], saved[1])
        assertEquals(2, saved.size)
    }

    @Test fun saveDeckFallsBackToDefaultName() {
        val saved = Words.saveDeck(listOf(deck), "d1", "   ", deck.text)
        assertEquals("我的词库", saved.single().name)
    }

    @Test fun saveDeckWithUnknownIdChangesNothing() {
        val decks = listOf(deck)
        assertEquals(decks, Words.saveDeck(decks, "nope", "改名", "x"))
    }

    @Test fun deleteDeckRemovesDeckAndUnchecks() {
        val decks = listOf(deck, Words.Deck("d2", "公式", "a+b=c"))
        val on = setOf("d1", "d2", Words.MISTAKES)
        val (rest, onAfter) = Words.deleteDeck(decks, on, "d1")
        assertEquals(listOf("d2"), rest.map { it.id })
        // 勾选里不能留下指向已删词库的 id；错词本这种无关的勾选要留着
        assertEquals(setOf("d2", Words.MISTAKES), onAfter)
    }

    @Test fun deleteMissingDeckKeepsEverything() {
        val decks = listOf(deck)
        val on = setOf("d1")
        val (rest, onAfter) = Words.deleteDeck(decks, on, "gone")
        assertEquals(decks, rest); assertEquals(on, onAfter)
    }

    @Test fun toggleDeckAddsAndRemoves() {
        val on = setOf("d1")
        assertEquals(setOf("d1", "d2"), Words.toggleDeck(on, "d2", true))
        assertEquals(setOf("d1"), Words.toggleDeck(setOf("d1", "d2"), "d2", false))
        // 重复勾选不应产生重复项；取消一个没勾过的也不该误伤别人
        assertEquals(setOf("d1"), Words.toggleDeck(setOf("d1"), "d1", true))
        assertEquals(setOf("d1"), Words.toggleDeck(setOf("d1"), "d2", false))
    }

    @Test fun expandedDefaultsToNothingOpen() {
        // 卡顿的判定口径：刚进设置页时没有任何词库展开 → 默认态不会建出任何含全文的 EditText
        val e = Words.Expanded()
        assertTrue(e.openIds().isEmpty())
        listOf("d1", "d2", "wb_cet4").forEach { assertTrue("默认不该展开 $it", !e.isOpen(it)) }
    }

    @Test fun expandedTogglesOpenAndShut() {
        val e = Words.Expanded()
        assertTrue(e.toggled("d1"))
        assertTrue(e.isOpen("d1"))
        assertTrue(!e.toggled("d1"))
        assertTrue(!e.isOpen("d1"))
    }

    @Test fun expandedAllowsMultipleAtOnce() {
        // 不做互斥：同时展开多个才不用反复点回来
        val e = Words.Expanded()
        e.toggled("d1"); e.toggled("d2"); e.toggled("wb_cet4")
        assertEquals(setOf("d1", "d2", "wb_cet4"), e.openIds())
        e.toggled("d2")
        assertEquals(setOf("d1", "wb_cet4"), e.openIds())
    }

    @Test fun expandedOpenedIsIdempotent() {
        // 保存后重建会重复声明「这个是展开的」，不能因此变成收起
        val e = Words.Expanded()
        e.opened("d1"); e.opened("d1")
        assertTrue(e.isOpen("d1"))
        e.closed("d1"); e.closed("d1")
        assertTrue(!e.isOpen("d1"))
    }

    @Test fun expandedRetainOnlyDropsDeletedDecks() {
        // 删除后清掉展开态：否则下一个复用这个 id 的新词库会莫名带着旧正文展开
        val e = Words.Expanded()
        e.toggled("d1"); e.toggled("d2")
        e.retainOnly(setOf("d2"))
        assertEquals(setOf("d2"), e.openIds())
        assertTrue(!e.isOpen("d1"))
    }
}