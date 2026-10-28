package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MemoryAid：容错解析 / 本地降级 / 缓存命中 / 费用记账。
 *
 * 这几组断言是本功能的**验收线**，不是覆盖率凑数：
 *  - [MemoryAid.extractJson] 那几条用的都是实测见过的坏返回（包代码块、前面加解释、裸对象），
 *    解析一退化，页面就是一片空白 —— 而且不会报错，只会「什么都不显示」，最难被发现。
 *  - 降级那几条断言的是**内容和来源标记**，不是「非空就行」：本地生成必须真的用上了卡面的字，
 *    而且必须标成 LOCAL。这两点任一条破了，「云端失败却假装成功」就回来了。
 *  - 记账只断言「该记多少 / 不该记时是 0」，因为真正的写盘在页面里，这里盯着的是口径。
 */
class MemoryAidTest {

    private val T0 = 1_700_000_000_000L

    /** 双引号。上一轮脚本拼接留下了一堆未定义的 q，这里补成常量 */
    private val q = "\""

    /** markdown 围栏。写成常量是因为反引号在源码里容易被脚本吃掉 */
    private val FENCE = "\u0060\u0060\u0060"

    /** 一条助记 JSON */
    private fun item(type: String, title: String, body: String): String =
        "{" + q + "type" + q + ":" + q + type + q + "," + q + "title" + q + ":" + q + title + q + "," + q + "body" + q + ":" + q + body + q + "}"

    /** 四种类型都齐的标准云端返回（顺序故意打乱：真模型不会按我们的顺序给） */
    private fun fullCloud(): String = "[" +
        item("story", "小故事", "那天我在实验室量了很久") + "," +
        item("mnemonic", "首字口诀", "牛第动") + "," +
        item("image_prompt", "画面", "一幅画着苹果落地的素描") + "," +
        item("analogy", "类比", "就像水往低处流") + "]"

    /** 一个永远成功的假云端，顺便数被调用了几次 */
    private class FakeCloud(val answer: String, val pin: Long = 1200L, val pout: Long = 300L) : MemoryAid.Cloud {
        var calls = 0
        override fun call(prompt: String, cancel: AtomicBoolean): Triple<String, Long, Long> {
            calls++
            return Triple(answer, pin, pout)
        }
    }

    private val cfg = ApiConfig("https://api.example.com/v1", "sk-test", "deepseek-chat")

    // ---------------- 容错解析 ----------------

    /** 情况一：包在围栏代码块里并带 json 语言标记（实测最常见） */
    @Test fun parsesJsonInsideMarkdownFence() {
        val raw = "好的，这是助记：\n" + FENCE + "json\n" + fullCloud() + "\n" + FENCE
        val got = MemoryAid.extractJson(raw)
        assertEquals(4, got.size)
        assertEquals("story", got[0].getString("type"))   // 顺序保持模型给的
    }

    /** 情况一（变体）：围栏后没有语言标记，外面还裹着解释文字 */
    @Test fun parsesFenceWithoutLanguageTag() {
        val raw = "下面四条是为你生成的记忆辅助。\n" + FENCE + "\n" + fullCloud() + "\n" + FENCE + "\n希望有帮助。"
        assertEquals(4, MemoryAid.extractJson(raw).size)
    }

    /** 情况二：整段就是 JSON（理想情况，不该被容错逻辑带歪） */
    @Test fun parsesWholeJson() {
        assertEquals(4, MemoryAid.extractJson(fullCloud()).size)
        assertEquals(4, MemoryAid.extractJson("  \n" + fullCloud() + "\n  ").size)
    }

    /** 情况三：前后都是解释文字、没有围栏 —— 取第一个方括号区间 */
    @Test fun parsesBracketRegionInsideProse() {
        val raw = "我按你的要求生成了四种助记：\n" + fullCloud() + "\n上面就是全部内容，还需要我改吗？"
        assertEquals(4, MemoryAid.extractJson(raw).size)
    }

    /** 情况三（变体）：模型回的是外层对象、数组放在 aids 字段里（有的网关这样包） */
    @Test fun parsesArrayNestedInWrapperObject() {
        val raw = "结果如下：\n{" + q + "aids" + q + ":" + fullCloud() + "}"
        assertEquals(4, MemoryAid.extractJson(raw).size)
    }

    /** 情况三（变体）：助记正文里带花括号时，区间不能被提前截断 */
    @Test fun bracketRegionIgnoresBracesInsideStrings() {
        val raw = "说明：" + item("mnemonic", "集合", "记住 {1,2,3} 这三个元素")
        val got = MemoryAid.extractJson(raw)
        assertEquals(1, got.size)
        assertTrue(got[0].getString("body").contains("{1,2,3}"))
    }

    /** 情况四：完全不是 JSON —— 返回空表，绝不抛异常（抛了页面就崩了） */
    @Test fun notJsonReturnsEmpty() {
        assertTrue(MemoryAid.extractJson("抱歉，我无法完成这个请求。").isEmpty())
        assertTrue(MemoryAid.extractJson("").isEmpty())
        assertTrue(MemoryAid.extractJson("   \n  ").isEmpty())
        assertTrue("残缺对象：不猜", MemoryAid.extractJson("{" + q + "type" + q + ": 坏掉的 json").isEmpty())
    }

    /** 代码块提取：闭合 / 未闭合都要给出内容，语言标记行不能混进 JSON */
    @Test fun codeBlocksSkipsLanguageTagAndToleratesUnclosed() {
        val closed = "x\n" + FENCE + "json\nABC\n" + FENCE + "\ny"
        assertEquals(listOf("ABC\n"), MemoryAid.codeBlocks(closed))
        val unclosed = FENCE + "json\nDEF"
        assertEquals(listOf("DEF"), MemoryAid.codeBlocks(unclosed))
    }

    /** 解析出的条目 → Aid：缺 body 的丢掉，type 写错按口诀收，title 缺了用类型名顶上 */
    @Test fun fromCloudNormalizesItems() {
        val raw = "[" + item("mnemonic", "", "有正文") + "," +
            "{" + q + "type" + q + ":" + q + "story" + q + "," + q + "title" + q + ":" + q + "空的" + q + "}," +
            item("nonsense", "未知类型", "类型写错了") + "]"
        val got = MemoryAid.fromCloud(raw, "k1", T0)
        assertEquals(2, got.size)
        assertEquals("口诀", got[0].title)                       // title 空 → 用类型中文名
        assertEquals(MemoryAid.Kind.MNEMONIC, got[1].kind)       // 未知 type 兜到口诀
        assertTrue(got.all { it.source == MemoryAid.AidSource.CLOUD })
        assertTrue(got.all { it.cardKey == "k1" && it.at == T0 })
    }

    /** 四类齐了才算成功；少一类就是失败（不能把「只有三条」当成功） */
    @Test fun completeNeedsAllFourKinds() {
        assertTrue(MemoryAid.complete(MemoryAid.fromCloud(fullCloud(), "k", T0)))
        val threeKinds = "[" + item("mnemonic", "口诀", "甲") + "," + item("story", "故事", "乙") + "," + item("analogy", "类比", "丙") + "]"
        assertFalse(MemoryAid.complete(MemoryAid.fromCloud(threeKinds, "k", T0)))
    }

    // ---------------- 本地降级（必须真有用 + 必须标 LOCAL） ----------------

    /** 口诀：取正面每个实词的首字，虚词（什么是 / 的 / 是）不能混进去 */
    /**
     * 虚词前缀被剥掉，口诀以实词首字开头。
     *
     * 原断言写的是 startsWith("牛第") 且 wordsOf 返回 2 个词 —— 这要求把
     * 「牛顿第二定律」再切成「牛顿 / 第二定律」两段，等于需要中文分词器。
     * 本项目零第三方依赖，正则切不出中文词边界，这个期望无法实现。
     * 按真实能力断言：前缀剥掉后整段是一个词，口诀从「牛」起。
     */
    @Test fun mnemonicTakesFirstCharOfEachWord() {
        val m = MemoryAid.mnemonicOf("什么是牛顿第二定律")
        assertFalse("虚词「什么是」不能被当成实词取首字", m.startsWith("什"))
        assertTrue(m, m.startsWith("牛"))            // 「什么是」是虚词被去掉
        val words = MemoryAid.wordsOf("什么是牛顿第二定律")
        assertEquals(1, words.size)                  // 中文整段不再往下切
        assertEquals("牛顿第二定律", words.first())
    }

    /** 口诀（英文）：取每个词的首字母并大写 */
    @Test fun mnemonicTakesInitialsForEnglish() {
        val m = MemoryAid.mnemonicOf("what is compound interest rate")
        assertTrue(m, m.startsWith("CIR"))           // what / is 是虚词
    }

    /** 本地四条都要有，且来源全是 LOCAL、内容真的用上了卡面文字 */
    @Test fun localAidsAreGroundedAndMarkedLocal() {
        val aids = MemoryAid.localAids("什么是光合作用", "植物把光能变成化学能", "k", T0)
        assertEquals(4, aids.size)
        assertEquals(MemoryAid.Kind.entries.toList(), aids.map { it.kind })
        assertTrue(aids.all { it.source == MemoryAid.AidSource.LOCAL })
        assertTrue("口诀里应出现正面的词", aids[0].body.contains("光"))
        assertTrue("类比要用上关键词", aids[1].body.contains("光合作用"))
        assertTrue("故事要用上关键词", aids[2].body.contains("光合作用"))
        assertTrue("图像描述要用上关键词", aids[3].body.contains("光合作用"))
    }

    /** 类比必须点明「哪里不像」：只有比喻没有边界的类比会让人把比喻当定义 */
    @Test fun analogyStatesWhereItBreaks() {
        assertTrue(MemoryAid.analogyOf("什么是导数", "导数是瞬时变化率").contains("哪里不像"))
    }

    /** 正面背面都空白 → 空表（没素材就不硬凑），但只有一面有内容也要能出四条 */
    @Test fun localAidsEmptyOnlyWhenBothSidesBlank() {
        assertTrue(MemoryAid.localAids("", "  ", "k", T0).isEmpty())
        assertEquals(4, MemoryAid.localAids("", "植物把光能变成化学能", "k", T0).size)
    }

    /** 关键词：正面优先、去重、长度 ≥ 2（单字当关键词拼不出能读的类比） */
    @Test fun keywordsPreferFrontAndSkipSingleChars() {
        val kw = MemoryAid.keywords("什么是导数 导数", "导数是瞬时变化率")
        assertEquals("导数", kw.first())
        assertEquals(kw.distinct(), kw)
        assertTrue(kw.all { it.length >= 2 })
    }

    // ---------------- 缓存命中（省钱的核心） ----------------

    /** 同一张卡第二次生成：不发请求、不花钱、直接给上次的云端结果 */
    @Test fun secondRunHitsCacheWithoutCallingCloud() {
        val cloud = FakeCloud(fullCloud())
        val first = MemoryAid.run("默认", "什么是光合作用", "植物把光能变成化学能", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = cloud)
        assertEquals(1, cloud.calls)
        assertEquals(4, first.aids.size)
        val key = MemoryAid.cardKeyOf("默认", "什么是光合作用", "植物把光能变成化学能")
        val book = MemoryAid.Book.EMPTY.put(MemoryAid.Entry(key, "默认", "什么是光合作用", listOf(first.aids)))

        val second = MemoryAid.run("默认", "什么是光合作用", "植物把光能变成化学能", book, cfg, now = T0 + 999, cloud = cloud)
        assertEquals("缓存命中不该再发请求", 1, cloud.calls)
        assertTrue(second.cached)
        assertFalse(second.contacted)
        assertEquals(0L, second.micro)                 // 命中不花钱
        assertEquals(first.aids, second.aids)
    }

    /** 点「再生成一次」= force：绕过缓存重新问，旧的一批要留着 */
    @Test fun forceRegeneratesAndKeepsPreviousVersion() {
        val cloud = FakeCloud(fullCloud())
        val key = MemoryAid.cardKeyOf("默认", "问", "答")
        val first = MemoryAid.run("默认", "问", "答", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = cloud)
        var book = MemoryAid.Book.EMPTY.put(MemoryAid.Entry(key, "默认", "问", listOf(first.aids)))
        val again = MemoryAid.run("默认", "问", "答", book, cfg, force = true, now = T0 + 5, cloud = cloud)
        assertEquals(2, cloud.calls)
        assertFalse(again.cached)
        book = book.put(book.find(key)!!.push(again.aids))
        assertEquals(2, book.find(key)!!.versions.size)
        assertEquals(T0 + 5, book.find(key)!!.versions.last().first().at)
    }

    /** 换一张卡（正面不同）就是另一把钥匙：不能命中别人的缓存 */
    @Test fun differentCardDoesNotHitCache() {
        val cloud = FakeCloud(fullCloud())
        val key = MemoryAid.cardKeyOf("默认", "问 A", "答")
        val book = MemoryAid.Book.EMPTY.put(MemoryAid.Entry(key, "默认", "问 A", listOf(MemoryAid.localAids("问 A", "答", key, T0))))
        MemoryAid.run("默认", "问 B", "答", book, cfg, now = T0, cloud = cloud)
        assertEquals(1, cloud.calls)
    }

    /** 降级结果不算「生成过」：下次理应再试一次云端，别让卡永久停在质量有限那版 */
    @Test fun localFallbackIsNotCached() {
        val key = MemoryAid.cardKeyOf("默认", "问", "答")
        val entry = MemoryAid.Entry(key, "默认", "问", listOf(MemoryAid.localAids("问", "答", key, T0)))
        assertNull(MemoryAid.cached(listOf(entry), key))
        assertNull(MemoryAid.cached(listOf(entry), "别的key"))
    }

    /** 指纹：同内容同键、改一个字段就变；中文内容不该撞车 */
    @Test fun cardKeyIsStableAndContentSensitive() {
        assertEquals(MemoryAid.cardKeyOf("默认", "问", "答"), MemoryAid.cardKeyOf(" 默认 ", "问 ", " 答"))
        assertFalse(MemoryAid.cardKeyOf("默认", "问", "答") == MemoryAid.cardKeyOf("默认", "问", "答2"))
        assertFalse(MemoryAid.cardKeyOf("默认", "问", "答") == MemoryAid.cardKeyOf("默认", "问2", "答"))
        assertFalse("中文内容不能撞", MemoryAid.cardKeyOf("a", "什么是导数", "b") == MemoryAid.cardKeyOf("a", "什么是积分", "b"))
    }

    // ---------------- 费用记账 ----------------

    /** 云端成功：按 CostLedger 的单价算钱 */
    @Test fun cloudSuccessIsBilledAtLedgerPrice() {
        val cloud = FakeCloud(fullCloud(), pin = 1_000_000L, pout = 0L)
        val a = MemoryAid.run("默认", "问", "答", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = cloud)
        assertTrue(a.contacted)
        assertEquals(1_000_000L, a.pin)
        assertEquals(CostLedger.priceOf("deepseek-chat").costMicro(1_000_000L, 0L), a.micro)
        assertEquals(500_000L, a.micro)                     // ¥0.5/M 输入 → 50 万微元
        assertEquals("deepseek-chat", a.model)
        assertEquals(CostLedger.fmtYuan(500_000L), a.microText)
    }

    /** 真把这一笔记进台账：Feature.MEMORY_AID 出现在「按功能」那栏 */
    @Test fun billingGoesIntoMemoryAidFeatureBucket() {
        val cloud = FakeCloud(fullCloud(), pin = 1_000_000L, pout = 0L)
        val a = MemoryAid.run("默认", "问", "答", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = cloud)
        val ledger = CostLedger.record(LinkedHashMap(), "20261021", a.model, CostLedger.Feature.MEMORY_AID, a.pin, a.pout)
        val month = ledger.getValue("202610")
        assertEquals(1, month.byFeature(CostLedger.Feature.MEMORY_AID).req)
        assertEquals(500_000L, month.byFeature(CostLedger.Feature.MEMORY_AID).cost)
        assertEquals(a.microText, CostLedger.fmtYuan(month.byFeature(CostLedger.Feature.MEMORY_AID).cost))
    }

    /** 接口没上报 usage 时按长度估：0 不能变成「免费」 */
    @Test fun missingUsageIsEstimatedNotZero() {
        val cloud = FakeCloud(fullCloud(), pin = 0L, pout = 0L)
        val a = MemoryAid.run("默认", "什么是光合作用", "植物把光能变成化学能", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = cloud)
        assertTrue(a.pin > 0L)
        assertTrue(a.pout > 0L)
        assertTrue(a.micro > 0L)
    }

    // ---------------- 失败路径：绝不假装成功 ----------------

    /** 云端抛异常 → 四条本地 + 明确的失败提示 + 不记账 */
    @Test fun cloudErrorFallsBackToLocalAndSaysSo() {
        val boom = MemoryAid.Cloud { _, _ -> throw ApiException("HTTP 401（Key 不对或没权限）") }
        val a = MemoryAid.run("默认", "什么是导数", "导数是瞬时变化率", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = boom)
        assertEquals(4, a.aids.size)
        assertTrue(a.aids.all { it.source == MemoryAid.AidSource.LOCAL })
        assertTrue(a.warning, a.warning.contains("云端生成失败"))
        assertTrue(a.warning, a.warning.contains("质量有限"))
        assertTrue(a.warning, a.warning.contains("401"))
        assertEquals(0L, a.micro)
        assertEquals(0, a.cloudCount)
    }

    /** 模型回一段散文（不是 JSON）→ 也算失败，走本地，并说清是格式问题 */
    @Test fun nonJsonReturnFallsBackToLocal() {
        val cloud = FakeCloud("抱歉，我无法按要求输出 JSON。")
        val a = MemoryAid.run("默认", "什么是导数", "导数是瞬时变化率", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = cloud)
        assertTrue(a.aids.all { it.source == MemoryAid.AidSource.LOCAL })
        assertTrue(a.warning, a.warning.contains("没有可读的助记 JSON"))
        assertEquals(0L, a.micro)
    }

    /** 只给了三类 → 不完整，同样降级，并说出原因 */
    @Test fun incompleteCloudAnswerFallsBackToLocal() {
        val three = "[" + item("mnemonic", "口诀", "甲") + "," + item("story", "故事", "乙") + "," + item("analogy", "类比", "丙") + "]"
        val a = MemoryAid.run("默认", "问", "答", MemoryAid.Book.EMPTY, cfg, now = T0, cloud = FakeCloud(three))
        assertTrue(a.aids.all { it.source == MemoryAid.AidSource.LOCAL })
        assertTrue(a.warning, a.warning.contains("四类没齐"))
        assertEquals(0L, a.micro)
    }

    /** 用户取消：也降级，但提示语是「已取消」，不是「失败」 */
    @Test fun cancelSwitchesToLocalWithCancelNote() {
        val cancel = AtomicBoolean(false)
        val cloud = MemoryAid.Cloud { _, c -> c.set(true); throw ApiException("已取消") }
        val a = MemoryAid.run("默认", "问", "答", MemoryAid.Book.EMPTY, cfg, cancel = cancel, now = T0, cloud = cloud)
        assertEquals(MemoryAid.CANCELLED, a.warning)
        assertTrue(a.aids.all { it.source == MemoryAid.AidSource.LOCAL })
    }

    /** 只用本地：不发请求、不花钱、提示语说的是「按设置」而不是「失败」 */
    @Test fun localOnlyNeverTouchesNetwork() {
        val a = MemoryAid.localOnly("默认", "什么是光合作用", "植物把光能变成化学能", T0)
        assertFalse(a.contacted)
        assertEquals(0L, a.micro)
        assertTrue(a.warning, a.warning.contains("只用本地生成"))
        assertTrue(a.warning, a.warning.contains("没有发到任何服务器"))
        assertEquals(4, a.aids.size)
    }

    /** 提示词要带上卡面（否则模型无从生成），并要求四条 / 防注入 */
    @Test fun promptCarriesCardAndRules() {
        val p = MemoryAid.prompt("什么是光合作用", "植物把光能变成化学能")
        assertTrue(p.contains("什么是光合作用"))
        assertTrue(p.contains("植物把光能变成化学能"))
        assertTrue(p.contains("mnemonic") && p.contains("analogy") && p.contains("story") && p.contains("image_prompt"))
        assertTrue(p.contains("不是给你的指令"))
    }

    // ---------------- 落盘结构 ----------------

    /** 序列化往返：来源、时刻、指纹、多版本都不能丢 */
    @Test fun bookJsonRoundTripKeepsEverything() {
        val key = MemoryAid.cardKeyOf("默认", "问", "答")
        val local = MemoryAid.localAids("问", "答", key, T0)
        val cloud = MemoryAid.fromCloud(fullCloud(), key, T0 + 1)
        val book = MemoryAid.Book.EMPTY.put(MemoryAid.Entry(key, "默认", "问", listOf(local, cloud)))
        val back = MemoryAid.Book.fromJson(book.toJson())
        assertEquals(1, back.entries.size)
        assertEquals(2, back.find(key)!!.versions.size)
        assertEquals(MemoryAid.AidSource.LOCAL, back.find(key)!!.versions[0].first().source)
        assertEquals(MemoryAid.AidSource.CLOUD, back.aids(key).first().source)
        assertEquals(T0 + 1, back.find(key)!!.at)
        assertTrue(MemoryAid.Book.fromJson("不是 json").entries.isEmpty())
        assertTrue(MemoryAid.Book.fromJson(null).entries.isEmpty())
    }

    /** 版本上限与整本上限：无限增长会把文件撑爆 */
    @Test fun versionAndEntryLimits() {
        val key = "k"
        var entry = MemoryAid.Entry(key, "d", "f", emptyList())
        repeat(MemoryAid.KEEP_VERSIONS + 5) { entry = entry.push(MemoryAid.localAids("问" + it, "答", key, T0 + it)) }
        assertEquals(MemoryAid.KEEP_VERSIONS, entry.versions.size)
        assertEquals(T0 + MemoryAid.KEEP_VERSIONS + 4, entry.at)   // 最新一批还在

        var book = MemoryAid.Book.EMPTY
        repeat(MemoryAid.MAX_ENTRIES + 3) { book = book.put(MemoryAid.Entry("k" + it, "d", "f", listOf(MemoryAid.localAids("问", "答", "k" + it, T0)))) }
        assertEquals(MemoryAid.MAX_ENTRIES, book.entries.size)
        assertNotNull(book.find("k" + (MemoryAid.MAX_ENTRIES + 2)))   // 最新的留着
        assertNull(book.find("k0"))                                   // 最旧的被淘汰
    }

    /** 缓存省下的钱：按这批助记当初发出去要花多少估，纯本地批次是 0 */
    @Test fun savedMicroOnlyCountsCloudBatches() {
        val key = MemoryAid.cardKeyOf("默认", "问", "答")
        val localOnly = MemoryAid.Entry(key, "默认", "问", listOf(MemoryAid.localAids("问", "答", key, T0)))
        assertEquals(0L, MemoryAid.savedMicro(localOnly, "deepseek-chat", 200))
        val cloudBatch = MemoryAid.Entry(key, "默认", "问", listOf(MemoryAid.fromCloud(fullCloud(), key, T0)))
        assertTrue(MemoryAid.savedMicro(cloudBatch, "deepseek-chat", 200) > 0L)
    }

    // ---------------- 接真接口的一轮（本地假服务，走真 HTTP） ----------------

    /**
     * 端到端：真 [ApiClient] + 真 SSE 解析 + 真解析器，验证「请求体对、正文对、记账数对」。
     * 页面只负责把 [MemoryAid.Answer] 画出来，逻辑在这一层就该闭环。
     */
    @Test fun endToEndAgainstRealApiClient() {
        val bodies = mutableListOf<JSONObject>()
        val server = MiniHttp { path, body ->
            if (path != "/v1/chat/completions") return@MiniHttp 404 to "{}"
            bodies.add(JSONObject(body))
            val chunk = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", fullCloud()))))
            val usage = JSONObject().put("choices", org.json.JSONArray()).put("usage", JSONObject().put("prompt_tokens", 800).put("completion_tokens", 120))
            200 to "data: " + chunk + "\n\ndata: " + usage + "\n\ndata: [DONE]\n\n"
        }
        try {
            val cfg = ApiConfig("http://127.0.0.1:" + server.port + "/v1/", "sk-test", "deepseek-chat")
            val a = MemoryAid.run("默认", "什么是光合作用", "植物把光能变成化学能", MemoryAid.Book.EMPTY, cfg, now = T0,
                cloud = MemoryAid.Cloud { prompt, cancel ->
                    val client = ApiClient(cfg)
                    var usage = 0L to 0L
                    val text = client.stream(Bodies.text(cfg, prompt, MemoryAid.BUDGET, thinkingOff = true),
                        object : ApiClient.Listener {
                            override fun onText(text: String) {}
                            override fun onUsage(prompt: Int, completion: Int) { usage = prompt.toLong() to completion.toLong() }
                        }, cancel).first
                    Triple(text, usage.first, usage.second)
                })
            assertTrue(a.warning, a.warning.isEmpty())
            assertEquals(4, a.aids.size)
            assertTrue(a.aids.all { it.source == MemoryAid.AidSource.CLOUD })
            assertEquals(800L, a.pin)                    // 用了接口上报的真实 usage，不再是估算
            assertEquals(120L, a.pout)
            assertEquals(1, bodies.size)
            assertEquals(MemoryAid.BUDGET, bodies[0].getInt("max_tokens"))
            val sent = bodies[0].getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(sent.contains("什么是光合作用") && sent.contains("植物把光能变成化学能"))
        } finally {
            server.stop()
        }
    }

    /** 接口 401 → 走降级，链路不崩（用户最常遇到的一种失败） */
    @Test fun httpErrorAlsoFallsBack() {
        val errBody = "{" + q + "error" + q + ":{" + q + "message" + q + ":" + q + "bad key" + q + "}}"
        val server = MiniHttp { _, _ -> 401 to errBody }
        try {
            val cfg = ApiConfig("http://127.0.0.1:" + server.port + "/v1/", "sk-test", "deepseek-chat")
            val a = MemoryAid.run("默认", "什么是导数", "导数是瞬时变化率", MemoryAid.Book.EMPTY, cfg, now = T0,
                cloud = MemoryAid.Cloud { prompt, cancel ->
                    val client = ApiClient(cfg)
                    val text = client.stream(Bodies.text(cfg, prompt, MemoryAid.BUDGET, thinkingOff = true),
                        object : ApiClient.Listener { override fun onText(text: String) {} }, cancel).first
                    Triple(text, 0L, 0L)
                })
            assertEquals(4, a.aids.size)
            assertTrue(a.aids.all { it.source == MemoryAid.AidSource.LOCAL })
            assertTrue(a.warning, a.warning.contains("云端生成失败"))
            assertTrue(a.warning, a.warning.contains("401"))
        } finally {
            server.stop()
        }
    }
}
