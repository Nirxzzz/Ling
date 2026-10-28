package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KnowledgeGraph：第 10 条知识图谱的验收测试。
 *
 * 重点不是「能不能建出图」，而是**边的质量**——这个功能唯一会死的地方就是「什么都连」：
 * 同一副牌组里毫无交集的卡连成毛线团，图就白做了。所以这里对阈值、预算、同牌组不单独成边
 * 各写一条断言，并且都写成「构造一个具体场景 + 断言一个具体数」的形式，不是同义反复。
 */
class KnowledgeGraphTest {

    private fun card(
        id: Long,
        deck: String = "默认",
        front: String,
        back: String = "",
        origin: String = "",
        createdAt: Long = id * 1000,
    ) = KnowledgeCard.KCData(
        id = id, deck = deck, front = front, back = back, source = "import",
        createdAt = createdAt, fsrs = Fsrs.Card(), origin = origin,
    )

    // ---------------- 关键词 ----------------

    @Test fun keywordsDropStopwordsAndVirtualGlue() {
        val k = KnowledgeGraph.keywords("什么是导数？导数的几何意义是什么")
        assertTrue("不该出现「什么」: $k", k.none { it == "什么" || it == "什么是" })
        assertTrue("不该出现虚词粘连: $k", k.none { it.first() in "的了着过和与在是" && it.length == 2 && it !in setOf("导数") })
        assertTrue("应当抽到「导数」: $k", k.contains("导数"))
    }

    @Test fun keywordsNormalizeFullWidthAndCase() {
        // 全角 Ｆ 和半角 f 必须归一，否则同一张卡的英文术语永远连不上
        assertEquals(KnowledgeGraph.keywords("ＦＳＲＳ 算法"), KnowledgeGraph.keywords("fsrs 算法"))
    }

    @Test fun keywordsKeepLatinTermsButDropOneChar() {
        val k = KnowledgeGraph.keywords("用 fx 求导")
        assertTrue("fx 是有效词", k.contains("fx"))
        assertFalse("单字母没有区分度", k.contains("f"))
    }

    @Test fun keywordsAreDedupedAndCapped() {
        // 中文段靠标点切分（没有空格就整段算一个词），所以这里用顿号把词分开
        val k = KnowledgeGraph.keywords("函数、函数、极限、极限、连续、可导、单调、极值", 3)
        assertEquals(3, k.size)
        assertEquals(k.size, k.toSet().size)
    }

    // ---------------- 建图：空 / 单点 ----------------

    @Test fun emptyInputGivesEmptyGraph() {
        val g = KnowledgeGraph.build(emptyList())
        assertTrue(g.isEmpty())
        assertEquals(0, g.edges.size)
        assertTrue(KnowledgeGraph.clusters(g).isEmpty())
        assertTrue(KnowledgeGraph.layout(g, 300f, 300f).isEmpty())
    }

    @Test fun singleCardGivesOneNodeNoEdge() {
        val g = KnowledgeGraph.build(listOf(card(1, front = "导数的定义是什么")))
        assertEquals(1, g.nodes.size)
        assertEquals(0, g.edges.size)
        assertEquals(0, g.nodes[0].degree)
        // 单点也要有位置：布局会等比塞进画布并居中，所以点落在正中附近（不是贴在角落）
        val pos = KnowledgeGraph.layout(g, 400f, 300f)
        val p0 = pos.getValue(g.nodes[0].id)
        assertTrue("单点应该居中: $p0", Math.abs(p0.x - 200f) < 40f && Math.abs(p0.y - 150f) < 40f)
    }

    // ---------------- 建图：边的三种信号与阈值 ----------------

    @Test fun sharedKeywordConnectsWithExplainableReason() {
        // 两张不同牌组、不同来源的卡，共享 1 个词 = W_KEY/KEY_CAP = 1 分 → 仅凭一个模糊的共同词不成边
        // （这是「阈值过滤」最要紧的一条：泛词共享不该产生连线）
        val weak = KnowledgeGraph.build(listOf(card(1, deck = "A", front = "导数的定义"), card(2, deck = "B", front = "导数的几何意义")))
        assertEquals(0, weak.edges.size)
        // 共享 3 个词（满档 KEY_CAP）= 3.0 分 → 成边，并且理由里逐条点出是哪几个词
        val g = KnowledgeGraph.build(
            listOf(
                card(1, deck = "A", front = "导数 极限 连续"),
                card(2, deck = "B", front = "导数 极限 连续"),
            )
        )
        assertEquals(2, g.nodes.size)
        assertEquals(1, g.edges.size)
        val e = g.edges[0]
        assertEquals(KnowledgeGraph.W_KEY, e.weight, 1e-9)
        assertTrue("理由必须念得出来: ${e.reason}", e.reason.contains("共享关键词"))
        assertTrue("理由里要点出那个词: ${e.reason}", e.reason.contains("导数"))
    }

    @Test fun sameDeckAloneDoesNotConnect() {
        // 这一条是整张图的命门：同牌组但毫无交集的卡，一条边都不许有
        val g = KnowledgeGraph.build(
            listOf(
                card(1, deck = "同一批导入", front = "牛顿莱布尼茨公式"),
                card(2, deck = "同一批导入", front = "概率分布列"),
                card(3, deck = "同一批导入", front = "受热膨胀系数"),
            )
        )
        assertEquals(3, g.nodes.size)
        assertEquals(0, g.edges.size)
        // 三个孤立点：`clusters` 不把它们当成 3 个主题（单点不构成主题），
        // 但也不能返回空 —— 页面会显示「分成 0 块」，那是谎话。约定：至少 1 个主题。
        assertEquals(1, KnowledgeGraph.clusters(g).size)
        // 真正的诚实结论在边上：一条边都没有
        assertEquals(0, g.edges.size)
    }

    @Test fun thresholdFiltersWeakEdges() {
        val cards = listOf(
            card(1, deck = "A", front = "导数 极限 连续"),
            card(2, deck = "A", front = "导数 极限 连续"),
        )
        // 3 个词满档 3.0 分 + 同牌组 1.0 = 4.0；默认阈值 3.0 当然保留
        assertEquals(1, KnowledgeGraph.build(cards).edges.size)
        assertEquals(1, KnowledgeGraph.build(cards, threshold = 4.0).edges.size)
        // 阈值抬到 4.5，这条边就不够格了 —— 阈值确实在起作用，不是摆设
        assertEquals(0, KnowledgeGraph.build(cards, threshold = 4.5).edges.size)
        // 反方向：只共享一个泛词（1.0 分）在任何阈值下都不该成边
        val weak = listOf(card(1, deck = "A", front = "连读弱化"), card(2, deck = "A", front = "语调升降"))
        assertEquals(0, KnowledgeGraph.build(weak, threshold = 1.5).edges.size)
        assertEquals(0, KnowledgeGraph.build(weak).edges.size)
    }

    @Test fun sameOriginConnectsStrongerThanOneKeyword() {
        val g = KnowledgeGraph.build(
            listOf(
                card(1, deck = "A", front = "牛顿第一定律", origin = "物理笔记.pdf · 一切物体总保持匀速直线运动"),
                card(2, deck = "A", front = "惯性参照系", origin = "物理笔记.pdf · 惯性参照系是……"),
            )
        )
        assertEquals(1, g.edges.size)
        val e = g.edges[0]
        // 同来源 3.0 + 同牌组 1.0 = 4.0（关键词没有共享）
        assertEquals(KnowledgeGraph.W_FILE + KnowledgeGraph.W_DECK, e.weight, 1e-9)
        assertTrue(e.reason.contains("同来源"))
        assertTrue("理由要指出是哪份资料: ${e.reason}", e.reason.contains("物理笔记.pdf"))
    }

    @Test fun emptyOriginIsNotASharedSource() {
        // 手动建的卡 origin 是空串：不能把「都没有来源」当成「来自同一个来源」
        val g = KnowledgeGraph.build(
            listOf(
                card(1, deck = "A", front = "牛顿第一定律"),
                card(2, deck = "A", front = "惯性参照系"),
            )
        )
        assertEquals(0, g.edges.size)
    }

    @Test fun sameOriginEdgesRespectBudget() {
        // 一份 40 张卡的资料：同来源最多只发 FILE_EDGE_BUDGET 条，剩下的人只剩关键词可连。
        // 这里每张卡的正文都是独一无二的（没有共享词），所以边数 = 预算数。
        val cards = (1L..40L).map { i ->
            // i 是 Long：'α' + i.toInt() % 20 先取模再转 Int，直接 'α' + i 会把 Long 当字符串拼进去
            card(i, deck = "大资料", front = "第${i}个概念${'α' + (i.toInt() % 20)}", origin = "大书.pdf · 摘录${i}")
        }
        val g = KnowledgeGraph.build(cards, threshold = 3.0)
        assertTrue("同来源边数不得超过预算: ${g.edges.size}", g.edges.size <= KnowledgeGraph.FILE_EDGE_BUDGET)
        assertTrue("至少要发出一些边: ${g.edges.size}", g.edges.size > 0)
    }

    @Test fun multipleFilesEachGetAnInternalEdge() {
        // 预算按文件轮转发放：小资料不该因为排在大资料后面就一条边都拿不到
        val cards = ArrayList<KnowledgeCard.KCData>()
        var id = 0L
        for (f in listOf("甲.pdf", "乙.pdf", "丙.pdf")) {
            for (i in 0 until 3) {
                id++
                cards += card(id, deck = f, front = "${f}要点${i}${'一'.plus(i)}", origin = "$f · 摘录$i")
            }
        }
        val g = KnowledgeGraph.build(cards)
        val files = g.edges.mapNotNull { e ->
            Regex("同来源 · (\\S+?\\.pdf)").find(e.reason)?.groupValues?.get(1)
        }.toSet()
        assertEquals("三份资料都该有内部边: $files", setOf("甲.pdf", "乙.pdf", "丙.pdf"), files)
    }

    // ---------------- 节点身份 ----------------

    @Test fun sameQuestionIsOneNode() {
        // 导入时同一段原文常被切成「问 A」和「追问 A」两张卡，它们就是一个知识点
        // 默认一个知识点 = 一张卡；显式打开 mergeSameQuestion 才把同问题合并（页面用默认值）
        val cards = listOf(
            card(1, front = "什么是导数", back = "瞬时变化率"),
            card(2, front = "什么是导数？", back = "极限定义"),
        )
        assertEquals(2, KnowledgeGraph.build(cards).nodes.size)
        val g = KnowledgeGraph.build(cards, mergeSameQuestion = true)
        assertEquals(1, g.nodes.size)
        assertEquals(2, g.nodes[0].cardIds.size)
        // 标签取最短的那个问题，读起来才短
        assertEquals("什么是导数", g.nodes[0].label)
    }

    @Test fun detailReturnsAllCardsUnderANode() {
        val cards = listOf(
            card(1, front = "什么是导数", back = "瞬时变化率"),
            card(2, front = "什么是导数", back = "极限定义"),
        )
        val g = KnowledgeGraph.build(cards, mergeSameQuestion = true)
        val d = KnowledgeGraph.detail(g, cards, g.nodes[0].id)
        assertNotNull(d)
        assertEquals(2, d!!.cards.size)
        assertNull(KnowledgeGraph.detail(g, cards, "不存在的 id"))
    }

    @Test fun labelIsClippedToReadableLength() {
        val long = "这是一个非常长的问题，长到在图上根本读不完，必须截断"
        assertEquals(10, KnowledgeGraph.clip(long, 10).length)
        assertTrue(KnowledgeGraph.clip(long, 10).endsWith("…"))
        assertEquals("短标签", KnowledgeGraph.clip("短标签", 10))
    }

    // ---------------- 聚类 ----------------

    @Test fun clustersFindSeparateThemes() {
        // 两坨：导数一组（共享 3 个独立词 → 3.0 满档成边），概率一组同样；两组之间一个词都不共享。
        // 语义决策：**孤立节点不算一个主题**（单点构成不了「这份资料的一个部分」），
        // 所以这里必须真的连出两条边，才能得到 2 个主题 —— 否则会退化成 4 个孤立点。
        val g = KnowledgeGraph.build(
            listOf(
                card(1, deck = "数学", front = "导数 极限 连续"),
                card(2, deck = "数学", front = "导数 极限 连续"),
                card(3, deck = "数学", front = "概率 分布 期望"),
                card(4, deck = "数学", front = "概率 分布 期望"),
            )
        )
        assertEquals("两组内部各要有 1 条边", 2, g.edges.size)
        val cs = KnowledgeGraph.clusters(g)
        assertEquals(2, cs.size)
        assertEquals(2, cs[0].size)
        assertEquals(2, cs[1].size)
        assertTrue(cs.all { it.weight > 0.0 })
    }

    @Test fun clusterOrderPrefersHeavyThemeOverManyLoosePoints() {
        // 一坨三张互相满档相连的卡（权重 3 条 × 4.0）应该排在若干个孤立点（权重 0）前面
        val tight = listOf(
            card(1, deck = "甲", front = "导数 极限 连续"),
            card(2, deck = "甲", front = "导数 极限 连续"),
            card(3, deck = "甲", front = "导数 极限 连续"),
        )
        val g = KnowledgeGraph.build(tight + card(4, front = "光合作用") + card(5, front = "磁通量"))
        val cs = KnowledgeGraph.clusters(g)
        // 语义决策：**孤立节点不构成主题**（单点没有「和谁相关」可言），所以这里只有 1 个主题。
        // 两个孤立点仍然出现在 graph.nodes 里（页面上会画成灰点），只是不参与主题统计。
        assertEquals(1, cs.size)
        assertEquals(3, cs[0].size)
        assertTrue("主题权重必须来自真实连线", cs[0].weight > 0.0)
    }

    // ---------------- 统计 ----------------

    @Test fun statsCountsReasonsAndMerges() {
        // 三种信号都命中：同来源（同一份笔记）+ 共享 3 个独立词 + 同牌组
        val cards = listOf(
            card(1, deck = "A", front = "导数 极限 连续", origin = "笔记.pdf · 摘录一"),
            card(2, deck = "A", front = "导数 极限 连续", origin = "笔记.pdf · 摘录二"),
        )
        val g = KnowledgeGraph.build(cards)
        val s = KnowledgeGraph.stats(g, cards.size)
        assertEquals(2, s.nodes)
        assertEquals(1, s.edges)
        assertEquals(2.0 * 1 / 2, s.avgDegree, 1e-9)
        assertEquals(1, s.byFile)
        assertEquals(1, s.byKey)
        assertEquals(1, s.byDeck)
        assertEquals(0, s.merged)
    }

    // ---------------- 布局 ----------------

    @Test fun layoutKeepsEveryNodeInsideCanvas() {
        val cards = (1L..25L).map { i ->
            card(i, deck = "牌组${i % 3}", front = "概念${'a' + (i.toInt() % 12)} 与 概念${'a' + ((i + 1).toInt() % 12)}")
        }
        val g = KnowledgeGraph.build(cards)
        assertTrue(g.nodes.size > 3)
        for ((w, h) in listOf(300f to 300f, 1080f to 1600f, 200f to 900f)) {
            val pos = KnowledgeGraph.layout(g, w, h)
            assertEquals(g.nodes.size, pos.size)
            for ((id, p) in pos) {
                assertTrue("节点 $id x=${p.x} 越界（w=$w）", p.x in 0f..w)
                assertTrue("节点 $id y=${p.y} 越界（h=$h）", p.y in 0f..h)
            }
        }
    }

    @Test fun layoutIsDeterministic() {
        // 每次打开页面位置必须一样，否则用户会以为数据变了
        val g = KnowledgeGraph.build(
            (1L..12L).map { card(it, front = "概念${'a' + it.toInt()}") }
        )
        val a = KnowledgeGraph.layout(g, 800f, 600f)
        val b = KnowledgeGraph.layout(g, 800f, 600f)
        assertEquals(a.keys, b.keys)
        for (k in a.keys) {
            assertEquals(a.getValue(k).x, b.getValue(k).x, 1e-6f)
            assertEquals(a.getValue(k).y, b.getValue(k).y, 1e-6f)
        }
    }

    @Test fun layoutSeparatesNodesThatShareAnEdge() {
        // 连了线的两个点不能被摆在同一个位置，否则看起来像一个点。
        // 用「共享 3 个词」让它们之间真的成边（共享 2 个词是同一话题、共享 1 个只是泛词，都不该连）。
        val g = KnowledgeGraph.build(
            listOf(card(1, front = "导数 极限 连续"), card(2, front = "导数 极限 连续"))
        )
        assertEquals("这两个点之间必须成边，否则本条用例测不到斥力: " + g.edges.size, 1, g.edges.size)
        val pos = KnowledgeGraph.layout(g, 500f, 500f)
        val pts = g.nodes.map { pos.getValue(it.id) }
        val dx = pts[0].x - pts[1].x
        val dy = pts[0].y - pts[1].y
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
        assertTrue("两个点挤在一起了: " + dist, dist > 10.0)
    }

    @Test fun layoutHandlesDegenerateCanvas() {
        // 画布为 0×0（还没测量出来）时不能崩、也不能返回越界坐标，直接给空结果让页面重画
        val g = KnowledgeGraph.build(listOf(card(1, front = "导数"), card(2, front = "导数")))
        assertTrue(KnowledgeGraph.layout(g, 0f, 0f).isEmpty())
        // 默认一个知识点 = 一张卡，所以两张卡是两个点；各自都要落在画布内
        val pos = KnowledgeGraph.layout(g, 400f, 400f)
        assertEquals(2, pos.size)
        assertTrue("坐标必须落在画布内: " + pos.values, pos.values.all { it.x in 0f..400f && it.y in 0f..400f })
    }
}
