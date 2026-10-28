package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.69 陪伴 M2：聊天会话（[ChatStore]）。
 * 只测纯函数那一半 —— 解析 / 追加 / 裁剪 / 滑窗 / 请求体 / 状态行。
 * [ChatStore.Store] 和 [ChatActivity] 碰 Android（文件、界面、网络），不在这里。
 */
class ChatStoreTest {
    private val t0 = 1_760_000_000_000L
    private fun u(i: Int) = ChatStore.user("问$i", t0 + i * 1000L)
    private fun a(i: Int) = ChatStore.orb("答$i", t0 + i * 1000L + 500L)

    /** i 对话：问1 答1 问2 答2 … 问n 答n（时间递增，末尾最新） */
    private fun convo(n: Int): List<ChatStore.Msg> =
        (1..n).flatMap { listOf(u(it), a(it)) }

    // ---------------- 1. 序列化 ----------------

    @Test fun roundTripKeepsRoleContentAndTime() {
        val l = listOf(u(1), a(1))
        assertEquals(l, ChatStore.parse(ChatStore.toJson(l)))
    }

    @Test fun jsonShapeIsArrayOfRoleContentAt() {
        val a0 = JSONArray(ChatStore.toJson(listOf(u(1), a(1))))
        assertEquals(2, a0.length())
        assertEquals("user", a0.getJSONObject(0).getString("role"))
        assertEquals("问1", a0.getJSONObject(0).getString("content"))
        assertEquals(t0 + 1000L, a0.getJSONObject(0).getLong("at"))
        assertEquals(ChatStore.ORB, a0.getJSONObject(1).getString("role"))
    }

    // ---------------- 2. 坏数据 / 空会话 ----------------

    @Test fun emptyOrBrokenJsonGivesEmptyList() {
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse(null))
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse(""))
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse("   "))
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse("{坏"))
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse("[{\"role\":\"user\"}]"))          // 没正文
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse("[{\"role\":\"user\",\"content\":\"  \"}]"))
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.parse("[null]"))                     // null 项
    }

    @Test fun partialArrayKeepsGoodOnesAndSkipsBad() {
        val l = ChatStore.parse("[{\"role\":\"orb\",\"content\":\"答\",\"at\":5},{\"role\":\"x\"},null,{\"content\":\"问\",\"at\":6}]")
        assertEquals(listOf("答", "问"), l.map { it.content })
        assertEquals(listOf(ChatStore.ORB, ChatStore.USER), l.map { it.role })   // 不认识的 role 当用户说
        assertEquals(listOf(5L, 6L), l.map { it.at })
    }

    @Test fun emptyConversationGreetingAndStatus() {
        assertEquals("在。说什么？", ChatStore.greeting())
        assertEquals("还没聊过", ChatStore.status(emptyList(), t0))
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.clear())
        assertEquals(emptyList<Pair<String, String>>(), ChatStore.turns(emptyList()))   // 空会话不发 messages
    }

    // ---------------- 3. 追加 ----------------

    @Test fun appendKeepsChronologicalOrderAndSkipsBlank() {
        var l = ChatStore.add(emptyList(), u(1))
        l = ChatStore.add(l, a(1))
        assertEquals(listOf("问1", "答1"), l.map { it.content })
        assertTrue("时间必须递增", l[0].at < l[1].at)
        assertEquals(l, ChatStore.add(l, ChatStore.user("   ", t0 + 9_999_999L)))   // 空正文不进
    }

    @Test fun sameTimestampBumpsForward() {
        val l = listOf(u(1), a(1))                       // t0+1000 / t0+1500
        val out = ChatStore.add(l, ChatStore.user("撞车", t0 + 1000L))
        assertEquals(t0 + 1501L, out.last().at)          // 撞了 → 排在已有最后一条之后
        assertEquals(t0 + 1500L, out[1].at)              // 已有那条不动
    }

    /** v2.7.1：本地记录**不再按 200 条砍头** —— 用户要求原始聊天一条都不许丢 */
    @Test fun keepsEveryMessageNoMatterHowLong() {
        var l = emptyList<ChatStore.Msg>()
        for (i in 1..250) l = ChatStore.add(l, u(i))
        assertEquals(250, l.size)
        assertEquals("问250", l.last().content)      // 最新的在末尾
        assertEquals("问1", l.first().content)       // 最老的在头部，没被砍
    }

    /** 清空仍然照旧：手动清空是保留能力（[ChatStore.Store.clear] 在这之上还会删摘要 sidecar） */
    @Test fun clearStillWorks() {
        assertEquals(emptyList<ChatStore.Msg>(), ChatStore.clear())
    }

    // ---------------- 4. 滑窗 ----------------

    @Test fun windowKeepsLastTwentyInOrder() {
        val l = convo(20)                                   // 40 条
        val w = ChatStore.window(l, ChatStore.WINDOW)
        assertEquals(20, w.size)
        assertEquals("问11", w.first().content)              // 40-20=20 起 → 第 11 轮开始
        assertEquals("答20", w.last().content)
        assertTrue("滑窗必须保持时间顺序", w.zipWithNext().all { (a, b) -> a.at < b.at })
    }

    @Test fun windowShorterThanCapReturnsAll() {
        val l = convo(3)                                     // 6 条 < 20
        assertEquals(l, ChatStore.window(l, ChatStore.WINDOW))
        assertEquals(l, ChatStore.window(l, 100))
    }

    @Test fun windowStartsFromUserWhenCutMidTurn() {
        val l = convo(11)// 22 条
        val w = ChatStore.window(l, 21)                    // 砍 1 条 → 正好砍掉半轮，开头是小珠说的
        assertEquals(20, w.size)
        assertFalse("开头不能是小珠说的", w.first().isOrb)
        assertEquals("问2", w.first().content)
    }

    @Test fun windowDropsLeadingOrbMessages() {
        // 手工造：开头连着两条小珠 → 都丢掉，从用户那句接
        val l = listOf(a(1), a(2), u(1), a(1), u(2))
        val w = ChatStore.window(l, 4)
        assertEquals(listOf("问1", "答1", "问2"), w.map { it.content })
    }

    @Test fun windowAllOrbKeepsThemRatherThanEmpty() {
        //异常文件：最近的几条全是小珠说的。全砍掉就发了一个空 messages 过去 —— 宁可开头是小珠说
        val l = listOf(u(1), a(1), a(2), a(3))
        assertEquals(listOf("答2", "答3"), ChatStore.window(l, 2).map { it.content })
    }

    // ---------------- 4b. 成块驱逐（缓存友好） ----------------

    /** i 条消息，全是用户说的 —— 裁剪时不会碰到「开头是小珠」那条老规矩，期望值好算 */
    private fun userOnly(n: Int): List<ChatStore.Msg> = (1..n).map { u(it) }

    @Test fun chunkedKeepsEverythingVerbatimUnderThreshold() {
        val l = convo(10)                                // 20 条 = 正好 n
        assertEquals(l, ChatStore.windowChunked(l, 20, 6))
        val over = userOnly(26)                          // 26 条 = n + chunk，卡在阈值上（含）
        assertEquals(over, ChatStore.windowChunked(over, 20, 6))
        assertEquals(over, ChatStore.windowChunked(over))  // 默认参数 = WINDOW / EVICT_CHUNK
    }

    @Test fun chunkedKeepsHeadAnchoredSoOneMoreMessageDoesNotCut() {
        // 逐条滑窗的话，21 条那轮就会把头部砍掉、请求前缀整个变掉；成块驱逐下 21~26 条前缀一条不动
        var l = convo(10)                                // 20 条
        val head = l.first()
        for (i in 11..13) {                              // 一路加到 26 条
            l = l + u(i) + a(i)
            val w = ChatStore.windowChunked(l, 20, 6)
            assertEquals("加到 ${l.size} 条时头部不能动", head, w.first())
            assertEquals("未过阈值不该裁", l, w)
        }
    }

    @Test fun chunkedCutsOnceToTargetOnlyPastThreshold() {
        assertEquals(26, ChatStore.windowChunked(userOnly(26), 20, 6).size)   // 26 <= 26 → 不裁
        val w = ChatStore.windowChunked(userOnly(27), 20, 6)                  // 27 > 26 → 裁
        assertEquals(20, w.size)                                              // 一次裁到 n
    }

    @Test fun chunkedKeepsNewestTargetMessagesAfterCut() {
        val l = userOnly(40)
        val w = ChatStore.windowChunked(l, 20, 6)
        assertEquals((21..40).map { "问$it" }, w.map { it.content })
    }

    @Test fun chunkedWithNonPositiveChunkFallsBackToImmediateCut() {
        for (c in listOf(0, -1, -6)) {                   // 防御：参数非法就退化成逐条滑窗，不给更宽的窗口
            assertEquals(ChatStore.window(convo(15), 20), ChatStore.windowChunked(convo(15), 20, c))
        }
        // 非法 chunk 下窗口不会比目标更宽：还是 20 条
        assertEquals(20, ChatStore.windowChunked(userOnly(30), 20, 0).size)
    }

    @Test fun turnsGoesThroughChunkedWindowSoPrefixStaysStable() {
        // 接了成块驱逐：22 条不再被裁到 20（否则前缀每轮都变，服务端缓存全废）
        assertEquals(22, ChatStore.turns(convo(11)).size)
        assertEquals(ChatStore.WINDOW, ChatStore.turns(convo(30)).size)   // 真超了才裁
        assertEquals("问1", ChatStore.turns(convo(11)).first().second)   // 头部没动
    }

    @Test fun turnsMapOrbToAssistant() {
        assertEquals(
            listOf("user" to "问1", "assistant" to "答1"),
            ChatStore.turns(listOf(u(1), a(1)))
        )
        assertEquals(ChatStore.WINDOW, ChatStore.turns(convo(30)).size)
        assertEquals("assistant", ChatStore.turns(listOf(a(1)))[0].first)
    }

    // ---------------- 5. 请求体 ----------------

    @Test fun bodyPutsSystemFirstThenSlidingTurns() {
        val cfg = ApiConfig("https://x/v1", "k", "m")
        val o = JSONObject(ChatStore.body(cfg, convo(15)))       // 30 条 → 带最近 20 条
        val ms = o.getJSONArray("messages")
        assertEquals(21, ms.length())                           // system + 20
        assertEquals("system", ms.getJSONObject(0).getString("role"))
        assertTrue(ms.getJSONObject(0).getString("content").contains(Prompts.PERSONA))
        assertEquals("user", ms.getJSONObject(1).getString("role"))
        assertEquals("assistant", ms.getJSONObject(2).getString("role"))
        assertEquals("答15", ms.getJSONObject(ms.length() - 1).getString("content"))
    }

    @Test fun bodyIsNonStreamingAndCarriesModel() {
        val cfg = ApiConfig("https://x/v1", "k", "m")
        val o = JSONObject(ChatStore.body(cfg, listOf(u(1))))
        assertFalse("聊天这轮走非流式", o.getBoolean("stream"))
        assertEquals("m", o.getString("model"))
        assertEquals(2, o.getJSONArray("messages").length())
    }

    /**
     * v2.2.1 聊天接搜索：默认（不开搜索）请求体必须和以前**逐字一样** —— 不带 tools、stream=false。
     * 这两条一旦变了，所有老用户的前缀缓存和请求形状都跟着变。
     */
    @Test fun bodyWithoutSearchKeepsOldShape() {
        val cfg = ApiConfig("https://x/v1", "k", "m")
        val o = JSONObject(ChatStore.body(cfg, listOf(u(1))))
        assertFalse(o.has("tools"))
        assertFalse(o.getBoolean("stream"))
    }

    /** 开搜索时聊天才是流式 + tools；工具表跟识屏那条线共用 [WebSearch.toolsJson] 的口径。 */
    @Test fun bodyWithSearchIsStreamingAndCarriesTools() {
        val cfg = ApiConfig("https://x/v1", "k", "deepseek-chat", "high",
            WebSearch.Config(enabled = true, provider = WebSearch.DEEPSEEK, deepseekKey = "sk-search"))
        val o = JSONObject(ChatStore.body(cfg, listOf(u(1)), stream = true, withTools = true))
        assertTrue("开了搜索才走流式", o.getBoolean("stream"))
        // v2.3.1 起工具表前缀是本机工具（AgentTools），位置不再固定，所以按名字查而不是按下标。
        // 这条用例要守住的是：开搜索时联网工具**确实在场**，且 web_search 排在 web_fetch 前面。
        val tools = o.getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue("必须有 web_search：" + names, names.contains(WebSearch.TOOL_SEARCH))
        // 有 DeepSeek Key 时还应该带上 web_fetch（抓具体网页）
        assertTrue("必须有 web_fetch：" + names, names.contains(WebSearch.TOOL_FETCH))
        assertTrue("web_search 要排在 web_fetch 前面：" + names,
            names.indexOf(WebSearch.TOOL_SEARCH) < names.indexOf(WebSearch.TOOL_FETCH))
        // 历史 / system 的形状不变
        assertEquals("system", o.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test fun systemPromptUsesPersonaAndSaysNoScreenshot() {
        val s = ChatStore.systemPrompt()
        assertTrue(s.startsWith(Prompts.PERSONA))       // 语气只有 PERSONA 这一处来源
        assertTrue("聊天没有截图，要说清楚", s.contains("没有截图"))
    }

    @Test fun emptyConversationBodyStillHasSystem() {
        val o = JSONObject(ChatStore.body(ApiConfig("https://x/v1", "k", "m"), emptyList()))
        val ms = o.getJSONArray("messages")
        assertEquals(1, ms.length())
        assertEquals("system", ms.getJSONObject(0).getString("role"))
    }

    // ---------------- 6. 状态行 ----------------

    @Test fun statusCountsAndStampsRelativeTime() {
        val one = listOf(u(1))
        assertEquals("共 1 条 · 上次刚刚", ChatStore.status(one, t0 + 1000L))
        assertEquals("共 1 条 · 上次5 分钟前", ChatStore.status(one, t0 + 1000L + 5 * 60_000L))
        assertEquals("共 6 条 · 上次3 小时前", ChatStore.status(convo(3), t0 + 3500L + 3 * 3600_000L))   // 3 组对话 = 6 条消息
        assertTrue(ChatStore.status(convo(3), System.currentTimeMillis()).startsWith("共 6 条 · 上次"))
    }

    @Test fun agoCoversAllFourBuckets() {
        assertEquals("刚刚", ChatStore.ago(t0, t0 + 59_999L))
        assertEquals("1 分钟前", ChatStore.ago(t0, t0 + 60_000L))
        assertEquals("59 分钟前", ChatStore.ago(t0, t0 + 59 * 60_000L))
        assertEquals("1 小时前", ChatStore.ago(t0, t0 + 3600_000L))
        assertEquals("23 小时前", ChatStore.ago(t0, t0 + 23 * 3600_000L))
        assertTrue(ChatStore.ago(t0 - 2 * 86400_000L, t0).endsWith("日"))   // 超过一天给日期
    }

    // ---------------- 7. 当前时间行（nowLine） ----------------

    @Test fun nowLinePinsExactFormatWhenZoneIsFixed() {
        // 先钉死时区再算期望，就能整串逐字断言 —— 不依赖跑测试那台机器的默认时区
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            // 2026-10-14T15:04+08:00，周三（上海 = UTC+8）对应的绝对时刻
            assertEquals("[现在时间 2026-10-14T15:04 周三 Asia/Shanghai]", ChatStore.nowLine(1_791_961_440_000L))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test fun nowLineFollowsJvmDefaultZoneNotHardcoded() {
        // 不钉时区：日期时间 / 星期 / 时区 ID 全按 JVM 默认时区自己算期望，
        // 绝不能把 Asia/Shanghai 写死 —— 测试机多半不在上海
        val now = 1_760_000_000_000L
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val date = String.format(
            "%04d-%02d-%02dT%02d:%02d",
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
        )
        val week = "日一二三四五六"[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        val zone = java.util.TimeZone.getDefault().getID()
        assertEquals("[现在时间 $date 周$week $zone]", ChatStore.nowLine(now))
        assertTrue("时区 ID 必须是 JVM 默认的那个：${ChatStore.nowLine(now)}", ChatStore.nowLine(now).endsWith(" $zone]"))
    }

    @Test fun nowLineChangesWhenClockAdvances() {
        // 「每轮实时取」的意义：时钟往前走，这一行就得跟着变（不是会话创建时冻结的常量）
        assertTrue(ChatStore.nowLine(t0) != ChatStore.nowLine(t0 + 61_000L))
    }
}

/**
 * 聊天里带识屏记录（[ChatStore.wantsHistory] / [ChatStore.historySummary] / [ChatStore.body] 的 suffix）。
 * 三道门：问法命中 → 有 solve 记录 → 才进请求体；任一不满足 suffix 是空串。
 * Android 那层（[ChatActivity] 接线、[History.Store] 读文件）不在这里。
 */
class ChatHistorySummaryTest {
    private val t0 = 1_760_000_000_000L

    /** 一条 solve 记录：id 决定相对时间，label 是模式名 */
    private fun solve(id: Long, text: String, label: String = "做题·标准") =
        History.Entry(id, History.KIND_SOLVE, label, text)
    private fun translate(id: Long, text: String, src: String) =
        History.Entry(id, History.KIND_TRANSLATE, "→ 中文", text, src)

    // ---------------- 1. 意图命中 ----------------

    @Test fun nounsAloneHit() {
        for (q in listOf(
            "总结一下识屏记录", "梳理我的识别记录", "看看最近识别了什么",
            "刚才识屏的那几道题呢", "历史记录里都问了啥", "最近看的屏幕内容总结一下",
        )) assertTrue("应该命中：$q", ChatStore.wantsHistory(q))
    }

    @Test fun verbNeedsAContextWord() {
        // 动词 + 记录类词 → 命中
        assertTrue(ChatStore.wantsHistory("帮我总结最近的记录"))
        assertTrue(ChatStore.wantsHistory("归纳一下刚才识屏的内容"))
        assertTrue(ChatStore.wantsHistory("梳理之前识别过的题"))
        // 光一个动词，跟识屏记录无关 → 不命中（宁可漏也别白烧 token）
        for (q in listOf("帮我总结一下这段代码", "归纳这个函数的逻辑", "整理一下桌面", "梳理下这周的安排")) {
            assertFalse("不该命中：$q", ChatStore.wantsHistory(q))
        }
    }

    @Test fun ordinaryChatDoesNotHit() {
        for (q in listOf("", "   ", "你好", "这道题怎么做", "1+1等于几", "你在干嘛")) {
            assertFalse("不该命中：'$q'", ChatStore.wantsHistory(q))
        }
    }

    // ---------------- 2. 摘要构建 ----------------

    @Test fun summaryOnlyTakesSolveAndNewestFirst() {
        val l = listOf(
            solve(t0, "解：x=3"),
            translate(t0 - 60_000L, "hello", "你好"),        // 翻译不进来
            solve(t0 - 2 * 60_000L, "答案是 B"),
            solve(t0 - 5 * 60_000L, "答案是 C"),
        )
        val s = ChatStore.historySummary(l, t0)
        assertTrue(s.startsWith("\n\n" + ChatStore.HISTORY_HEAD))
        assertFalse("翻译那条不能混进来：$s", s.contains("hello"))
        assertTrue("最新的排最前：$s", s.contains("刚刚 · 做题·标准：解：x=3"))
        assertTrue(s.contains("2 分钟前 · 做题·标准：答案是 B"))
        assertTrue(s.contains("5 分钟前 · 做题·标准：答案是 C"))
        assertTrue("按新到旧", s.indexOf("答案是 B") < s.indexOf("答案是 C"))
    }

    @Test fun summaryCapsAtHistoryN() {
        val l = (1..(ChatStore.HISTORY_N + 5)).map { solve(t0 - it * 60_000L, "题$it") }
        val s = ChatStore.historySummary(l, t0)
        // 抬头 1 行 + 记录 HISTORY_N 行（开头那两个空行是 "\n\n" 分出来的，不算数）
        assertEquals(ChatStore.HISTORY_N + 1, s.lines().count { it.isNotBlank() })
        assertTrue(s.contains("题1"))
        assertFalse("超出上限的最老记录不该出现：$s", s.contains("题" + (ChatStore.HISTORY_N + 1)))
    }

    @Test fun summaryClipsLongTextAndSkipsBlankLabel() {
        val long = "长".repeat(300)
        val s = ChatStore.historySummary(listOf(solve(t0, long, label = "  ")), t0)
        assertTrue(s.contains("长".repeat(ChatStore.HISTORY_CLIP) + "…"))
        assertFalse("空 label 不该留下「· ：」：$s", s.contains("·  ："))
    }

    @Test fun emptyOrTranslateOnlyGivesEmptyString() {
        assertEquals("", ChatStore.historySummary(emptyList(), t0))
        assertEquals("", ChatStore.historySummary(listOf(translate(t0, "hi", "你好")), t0))   // 全是翻译 = 没有识屏记录
    }

    // ---------------- 3. 接进请求体 ----------------

    private fun u(i: Int) = ChatStore.user("问$i", t0 + i * 1000L)
    private fun a(i: Int) = ChatStore.orb("答$i", t0 + i * 1000L + 500L)
    private val cfg = ApiConfig("https://x/v1", "k", "m")

    @Test fun suffixLandsOnLastUserMessageAndSystemUntouched() {
        val sys = "SYSTEM-ANCHOR"
        // 末尾是小珠说的 —— 尾巴只能接给最后一条**user**（问2），不能接给末条消息，更不能给 system
        val list = listOf(u(1), a(1), u(2), a(2))
        val suffix = "\n\n" + ChatStore.HISTORY_HEAD + "\n刚刚：解：x=3"
        val ms = JSONObject(ChatStore.body(cfg, list, system = sys, suffix = suffix)).getJSONArray("messages")
        assertEquals(5, ms.length())                                   // system + 4，一条没多
        assertEquals(sys, ms.getJSONObject(0).getString("content"))   // system 一个字没变
        assertEquals("问2" + suffix, ms.getJSONObject(3).getString("content"))
        assertEquals("答2", ms.getJSONObject(4).getString("content"))// 末条（小珠说的）不动
        assertEquals("问1", ms.getJSONObject(1).getString("content"))// 更早的轮次也不动
    }

    @Test fun emptySuffixLeavesBodyByteIdentical() {
        val list = listOf(u(1), a(1), u(2))
        val sys = ChatStore.systemPrompt()
        assertEquals(ChatStore.body(cfg, list, system = sys), ChatStore.body(cfg, list, system = sys, suffix = ""))
        val ms = JSONObject(ChatStore.body(cfg, list, system = sys)).getJSONArray("messages")
        assertEquals("问2", ms.getJSONObject(ms.length() - 1).getString("content"))
    }

    @Test fun suffixWithNoUserMessageLeavesSystemAlone() {
        // 只有小珠说的（异常会话）：尾巴宁可丢掉，也不能改 system —— 那会掀翻前缀缓存
        val sys = "SYSTEM-ANCHOR"
        val ms = JSONObject(ChatStore.body(cfg, listOf(a(1)), system = sys, suffix = "\n\n尾巴")).getJSONArray("messages")
        assertEquals(2, ms.length())                                   // system + 小珠那条
        assertEquals(sys, ms.getJSONObject(0).getString("content"))
        assertEquals("答1", ms.getJSONObject(1).getString("content"))  // 尾巴没往这条上贴
    }

    // ---------------- 4. 时间行也走 suffix（v1.87） ----------------

    @Test fun nowLineLandsOnLastUserMessageAndSystemUntouched() {
        val sys = "SYSTEM-ANCHOR"
        // 时间行是 suffix 的一种：落在最后一条 **user**（问2）上，system 与末条（小珠说的）逐字不动
        val list = listOf(u(1), a(1), u(2), a(2))
        val suffix = "\n\n" + ChatStore.nowLine(t0)
        val ms = JSONObject(ChatStore.body(cfg, list, system = sys, suffix = suffix)).getJSONArray("messages")
        assertEquals(5, ms.length())                                   // system + 4，一条没多
        assertEquals(sys, ms.getJSONObject(0).getString("content"))    // system 一个字没变
        assertEquals("问2" + suffix, ms.getJSONObject(3).getString("content"))
        assertTrue("时间行得跟着一起贴上：${ms.getJSONObject(3).getString("content")}",
            ms.getJSONObject(3).getString("content").contains("[现在时间 "))
        assertEquals("答2", ms.getJSONObject(4).getString("content"))
        assertEquals("问1", ms.getJSONObject(1).getString("content"))
    }

    @Test fun nowLineDroppedWhenNoUserMessageAndSystemUntouched() {
        // 只有小珠说的（异常会话）：时间行跟别的 suffix 一样宁可丢掉，绝不改 system（前缀缓存锚点）
        val sys = "SYSTEM-ANCHOR"
        val ms = JSONObject(ChatStore.body(cfg, listOf(a(1)), system = sys, suffix = "\n\n" + ChatStore.nowLine(t0)))
            .getJSONArray("messages")
        assertEquals(2, ms.length())
        assertEquals(sys, ms.getJSONObject(0).getString("content"))
        assertEquals("答1", ms.getJSONObject(1).getString("content"))  // 时间行没往这条上贴
    }
}