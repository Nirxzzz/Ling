package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MaterialIngest：纯文本结构化的验收测试。
 *
 * 每条断言都在验「用户能核对的东西」，不是验「函数被调用过」：
 *  - 章节切分对得上原文标题（含各种中文编号体系）；
 *  - 每个知识点带**原文摘录**，且摘录必须真的能在原文里搜到（含加粗残留那一例）；
 *  - 抽不出来时宁可空手，也不许编（乱文字 / 纯散文都要么空、要么只出章节卡）；
 *  - 卡片正面背面非空、牌组 = 章节名、来源是 IMPORT，能直接进 KnowledgeCard.addBatch。
 */
class MaterialIngestTest {

    private val lecture = listOf(
        "# 第一章 细胞的结构"
        , "细胞的边界是细胞膜。细胞膜控制物质进出。"
        , "一、细胞核"
        , "细胞核：细胞的遗传信息库。"
        , "二、线粒体"
        , "线粒体是细胞进行有氧呼吸的主要场所。"
    ).joinToString("\n")
    // ---------------- 空与容错 ----------------

    @Test fun blankTextYieldsEmptyResult() {
        for (s in listOf("", "   ", "\n\n\t")) {
            val r = MaterialIngest.ingest(s)
            assertTrue(r.isEmpty)
            assertTrue(r.sections.isEmpty())
            assertTrue(r.cards.isEmpty())
        }
    }

    @Test fun randomNoiseDoesNotThrowAndInventsNothing() {
        val noise = "a]x[ {\"乱\"} ;;\n\u0000???!!!\n\u3000\u3000"
        val r = MaterialIngest.ingest(noise)
        assertTrue("没有锚点就不该出知识点", r.points.isEmpty())
    }

    @Test fun proseWithoutHeadingStillProducesWholeSection() {
        val r = MaterialIngest.ingest("今天天气很好。我们去了公园。")
        assertTrue(r.points.isEmpty())
        assertEquals(1, r.sections.size)
        assertEquals(MaterialIngest.WHOLE, r.sections[0].title)
        assertTrue(r.sections[0].body.isNotEmpty())
    }

    @Test fun tooLongInputDoesNotCrash() {
        val big = buildString { repeat(3000) { append("第 ").append(it).append(" 行的正文内容。\n") } }
        assertNotNull(MaterialIngest.ingest(big))
    }
    // ---------------- 标题切分 ----------------

    @Test fun splitsMarkdownAndChineseHeadings() {
        val r = MaterialIngest.ingest(lecture)
        val titles = r.sections.map { it.title }
        assertEquals(listOf("细胞的结构", "细胞核", "线粒体"), titles)
        assertEquals("第一章", r.sections[0].number)
        assertEquals("一", r.sections[1].number)
        assertEquals("二", r.sections[2].number)
    }

    @Test fun headingLevelsAreOrderedButNotGuessedTooDeep() {
        val text = "一、总论\n1.1 细胞膜\n（一）磷酸双分子层"
        val s = MaterialIngest.sections(text)
        assertEquals(3, s.size)
        assertEquals("总论", s[0].title)
        assertEquals("细胞膜", s[1].title)
        assertEquals("1.1", s[1].number)
        assertEquals("磷酸双分子层", s[2].title)
    }

    @Test fun numberedStepWithCommaIsNotAHeading() {
        // 「1、先洗手，再停药」是操作步骤，不是章节：单看编号会误判成标题
        val r = MaterialIngest.ingest("1、先洗手，再停药")
        assertTrue(r.sections.all { it.title == MaterialIngest.WHOLE })
    }

    @Test fun quotedLineIsNotAHeading() {
        val r = MaterialIngest.ingest("> 一、别人的引用\n正文一句。")
        assertTrue(r.sections.none { it.title == "别人的引用" })
    }

    @Test fun sectionBodyKeepsFollowingSentences() {
        val r = MaterialIngest.ingest("一、光合作用\n光合作用需要光。它发生在叶绿体里。")
        val sec = r.sections.first { it.title == "光合作用" }
        assertTrue(sec.body.contains("光合作用需要光"))
        assertTrue(sec.body.contains("叶绿体"))
    }
    // ---------------- 知识点与摘录 ----------------

    @Test fun everyPointCarriesOriginFoundInSource() {
        val r = MaterialIngest.ingest(lecture)
        assertTrue("讲义里该抽出多个知识点", r.points.size >= 4)
        for (p in r.points) {
            assertTrue("知识点名不能为空", p.name.isNotBlank())
            assertTrue("摘录不能为空", p.origin.isNotBlank())
            assertTrue("摘录必须是原文子串：" + p.origin, lecture.contains(p.origin))
            assertTrue("行号要指得回原文", p.line >= 0)
        }
    }

    @Test fun headlineBecomesPointNamedByTitle() {
        val r = MaterialIngest.ingest("一、细胞核\n细胞核是遗传信息库。")
        val p = r.points.firstOrNull { it.name == "细胞核" }
        assertNotNull("标题本身就该是一个知识点", p)
        assertEquals("细胞核", p!!.section)
    }

    @Test fun colonDefinitionBecomesPoint() {
        val r = MaterialIngest.ingest("线粒体：细胞进行有氧呼吸的主要场所。")
        val p = r.points.firstOrNull { it.name == "线粒体" }
        assertNotNull(p)
        assertTrue(p!!.origin.contains("线粒体"))
    }

    @Test fun shiDefinitionBecomesPointWithoutTrailingZhi() {
        // 「是指」必须先于「是」匹配，否则名字会变成「叶绿体指」
        val r = MaterialIngest.ingest("叶绿体是指植物细胞中进行光合作用的细胞器。")
        assertTrue(r.points.any { it.name == "叶绿体" })
        assertTrue(r.points.none { it.name.endsWith("指") })
    }

    @Test fun pronounOpeningIsNotTreatedAsDefinition() {
        val r = MaterialIngest.ingest("它是一种常见的细胞器。")
        assertTrue("回指代词开头的不算新概念", r.points.none { it.name == "它" })
    }
    @Test fun boldInsideLineIsKeptInOrigin() {
        // 原文里两个星号把词隔开了：摘录要把星号一起带上，否则用户拿它去原文里搜不到
        val src = "光合作用：**植物**把光能变成化学能的过程。"
        val r = MaterialIngest.ingest(src)
        val p = r.points.first { it.name == "光合作用" }
        assertTrue("摘录要能在原文里搜到：" + p.origin, src.contains(p.origin))
        assertTrue(p.origin.contains("**植物**"))
    }

    @Test fun sameNameInSameSectionIsDeduped() {
        val r = MaterialIngest.ingest("一、酶\n酶：催化剂。\n酶：蛋白质。")
        assertEquals(1, r.points.count { it.name == "酶" && it.section == "酶" })
    }

    @Test fun linkKeyMergesSameConceptAcrossSections() {
        val r = MaterialIngest.ingest("一、光合作用\n光合作用是…\n二、呼吸作用\n光合作用是…")
        val hits = r.points.filter { it.name == "光合作用" }
        assertTrue("两个章节各出一条", hits.size >= 2)
        assertEquals("但图上是一个节点", 1, hits.map { it.linkKey }.distinct().size)
    }

    @Test fun numberedListWithColonBecomesPoint() {
        val r = MaterialIngest.ingest("1. 牛顿第一定律：物体不受力时保持匀速直线运动。\n2. 牛顿第二定律：F=ma")
        assertTrue(r.points.any { it.name == "牛顿第一定律" })
        assertTrue(r.points.any { it.name == "牛顿第二定律" })
    }

    @Test fun circledNumberListBecomesPoint() {
        val r = MaterialIngest.ingest("①光合作用\n②呼吸作用")
        assertTrue(r.points.any { it.name == "光合作用" })
        assertTrue(r.points.any { it.name == "呼吸作用" })
    }

    @Test fun enumerationSentenceKeepsSubjectOnly() {
        val r = MaterialIngest.ingest("细胞器包括线粒体、叶绿体和高尔基体。")
        assertTrue("名字取主语，不该是一整句", r.points.any { it.name == "细胞器" })
    }
    // ---------------- 卡片 ----------------

    @Test fun cardsAreUsableByKnowledgeCard() {
        val r = MaterialIngest.ingest(lecture, "生物讲义.txt")
        assertTrue("讲义该出卡", r.cards.isNotEmpty())
        assertEquals(r.cards.size, r.cards.map { it.front }.distinct().size)
        for (c in r.cards) {
            assertTrue("正面不能为空", c.front.isNotBlank())
            assertTrue("背面不能为空", c.back.isNotBlank())
            assertTrue("牌组不能为空", c.deck.isNotBlank())
            assertEquals(KnowledgeCard.Source.IMPORT.code, c.source)
            assertTrue("草稿 id 由 addBatch 分配", c.id == 0L)
            assertTrue("新卡应是未学状态", c.fsrs.isNew)
            assertTrue("origin 要带来源署名", c.origin.startsWith("生物讲义.txt"))
            assertTrue("正面不超上限", c.front.length <= KnowledgeAiImport.FRONT_MAX)
            assertTrue("背面不超上限", c.back.length <= KnowledgeCard.BACK_MAX)
        }
    }

    @Test fun cardOriginTextIsTraceableToSource() {
        val src = "一、细胞核\n细胞核：细胞的遗传信息库。"
        val r = MaterialIngest.ingest(src, "s.txt")
        val c = r.cards.first { it.front.contains("细胞核") }
        val excerpt = c.origin.removePrefix("s.txt · ")
        assertTrue("卡片 origin 里的摘录必须能在原文里搜到", src.contains(excerpt))
    }

    @Test fun cardsBatchMergesIntoEmptyLibrary() {
        val r = MaterialIngest.ingest(lecture, "a.txt")
        val batch = KnowledgeCard.mergeBatch(emptyList(), r.cards)
        assertEquals(r.cards.size, batch.result.added)
        assertTrue(batch.list.all { it.id > 0L })
    }

    @Test fun emptySectionDoesNotProduceCard() {
        // 书目录式的标题行没有正文，出卡就是给用户添垃圾
        val r = MaterialIngest.ingest("一、绪论\n二、细胞\n三、遗传")
        assertTrue(r.sections.size >= 3)
        assertTrue(r.cards.none { it.back.isBlank() })
    }

    @Test fun cardCountIsCapped() {
        val src = buildString { repeat(400) { append("第").append(it).append("节\n内容：这是第").append(it).append("节的定义说明。\n") } }
        assertTrue(MaterialIngest.ingest(src).cards.size <= MaterialIngest.CARD_MAX)
    }
    // ---------------- 摘要 / 小工具 ----------------

    @Test fun summaryMentionsCounts() {
        val r = MaterialIngest.ingest(lecture)
        assertTrue(r.summary.contains("章节"))
        assertTrue(r.summary.contains("知识点"))
    }

    @Test fun emptyResultExplainsItself() {
        assertTrue(MaterialIngest.ingest("").summary.isNotBlank())
    }

    @Test fun trimToAddsEllipsisOnlyWhenCut() {
        assertEquals("abc", MaterialIngest.trimTo("abc", 3))
        assertEquals("ab\u2026", MaterialIngest.trimTo("abcd", 2))
        assertEquals("", MaterialIngest.trimTo("abc", 0))
    }

    @Test fun literalSpanFallsBackWhenTextWasCleaned() {
        // 对不上就退回干净文字：内容依然可信，绝不返回空串让卡片没有出处
        assertEquals("被改过的句子", MaterialIngest.literalSpan("完全不相干的行", "被改过的句子"))
    }
}
