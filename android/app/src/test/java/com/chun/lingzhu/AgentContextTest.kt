package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v2.7.1 会话压缩（[AgentContext]）的永久行为：**什么时候压、压在哪个范围、失败/取消时不落盘**。
 *
 * 只测纯逻辑 + 一个真临时文件（摘要 sidecar），不发网络请求：[AgentContext.compact] 的 `ask` 就是
 * 注入点，测试里给一个直接返回摘要的 lambda 就能跑完整条压缩流程。Android 那层（真 HTTP、ChatActivity
 * 接线）不在这里。
 *
 * 触发口径是 **token 预算**，不是条数：所以这里有「几百条短消息一条不压」和「几十条长消息就压」两组。
 * 阈值一律用 [AgentContext.estimateTokens] / [AgentContext.WINDOW_TOKENS] 表达，不写死魔法数字。
 */
class AgentContextTest {
    private val t0 = 1_760_000_000_000L
    private val cfg = ApiConfig("https://x/v1", "k", "m")

    private fun u(i: Int, text: String = "问$i") = ChatStore.user(text, t0 + i * 2000L)
    private fun a(i: Int, text: String = "答$i") = ChatStore.orb(text, t0 + i * 2000L + 500L)

    /** n 轮「问 / 答」，末尾最新（和 [ChatStore.Store] 落盘顺序一致）；[fill] > 0 时每条正文都垫这么多字 */
    private fun convo(n: Int, fill: Int = 0, at0: Long = t0): List<ChatStore.Msg> = (1..n).flatMap { i ->
        val pad = if (fill > 0) "长".repeat(fill) else ""
        listOf(
            ChatStore.user("问$i$pad", at0 + i * 2000L),
            ChatStore.orb("答$i$pad", at0 + i * 2000L + 500L),
        )
    }

    private fun tempStore(): AgentContext.SummaryStore {
        val f = File.createTempFile("chat-ctx", ".json")
        return AgentContext.SummaryStore(f)
    }

    /** 「装得下」的判据，和 [AgentContext.compact] 内部同一口径（历史 + 冻结前缀 + 输出预留 ≤ 窗口） */
    private fun fits(sent: List<ChatStore.Msg>, reserve: Int = 0) =
        AgentContext.estimateTokens(sent) + reserve + AgentContext.OUTPUT_RESERVE <= AgentContext.WINDOW_TOKENS

    /** 该压的会话：plan 必须非空（null 时直接让测试失败，而不是 NPE / 静默通过） */
    private fun planOf(list: List<ChatStore.Msg>, memo: AgentContext.Memo? = null, reserve: Int = 0): AgentContext.Plan =
        AgentContext.plan(list, memo, reserve) ?: throw AssertionError("该压却没有给出压缩计划")

    // ---------------- 1. 触发：只看 token，不看条数 ----------------

    /** 几百条短消息：总量远没到窗口，一条都不压、原样全发（条数多本身不再是压缩理由） */
    @Test fun manyShortMessagesAreNeverCompacted() {
        val list = convo(150)                       // 300 条
        assertEquals("前提：条数很多", 300, list.size)
        assertTrue("前提：总量很小", fits(list))

        var asked = 0
        var phases = 0
        val store = tempStore()
        val out = AgentContext.compact(
            cfg, list, store,
            ask = { asked++; "不该被调用" },
            fresh = { list },
            onCompacting = { phases++ },
        )
        assertEquals("一次额外请求都不发", 0, asked)
        assertEquals("一条都不许丢", list, out)
        assertEquals("没真压就不该闪「正在整理」", 0, phases)
        assertNull("没压就不该写 sidecar", store.get())
    }

    /** 总量超过窗口才压，而且压的是「摘要 + 完整尾部」：一条不丢、不重排、当前这一轮在末尾 */
    @Test fun compactionStartsOnlyWhenTokensOverflow() {
        val list = convo(20, 1500)                  // 40 条，每条约 1500 字
        assertTrue("前提：总量超窗", !fits(list))
        val plan = planOf(list)
        assertEquals("覆盖范围加尾部就是整段会话，一条不丢", list.size, plan.coverTo + plan.tail.size)
        assertEquals("尾部起点 = tailStart", AgentContext.tailStart(list, 0), plan.coverTo)
        assertTrue("被覆盖的必须是一段真实历史", plan.coverTo >= 2)
        assertFalse("尾部第一条必须是用户说的（不剪在半轮中间）", plan.tail.first().isOrb)
        assertEquals("当前这一轮一定在尾部", list.last(), plan.tail.last())

        var asked = 0
        var phases = 0
        val store = tempStore()
        val out = AgentContext.compact(
            cfg, list, store,
            ask = { asked++; "【目标】查清青鹭七号的下落\n【关键上下文】松果 4826 号仓" },
            fresh = { list },
            onCompacting = { phases++ },
        )
        assertTrue("只有 plan 非空才发摘要请求", asked >= 1)
        assertEquals("onCompacting 只在真要压时回调一次", 1, phases)
        assertEquals("摘要一条 + 尾部", plan.tail.size + 1, out.size)
        assertTrue("摘要要带上固定抬头", out.first().content.startsWith(AgentContext.BLOCK_HEAD))
        assertTrue(out.first().content.contains("青鹭七号"))
        assertEquals("尾部逐字保留", plan.tail, out.drop(1))
        assertEquals("摘要落盘并记下覆盖范围", plan.coverTo, store.get()!!.covered)
        assertTrue("压完必须装得下", fits(out))
    }

    /** 冻结的 system / suffix / 工具定义（[AgentContext.estimateFixed]）计入预算：同样一段会话，前缀一大就要压 */
    @Test fun frozenPrefixBudgetCanTriggerCompaction() {
        val list = convo(15, 1000)
        assertTrue("前提：不计前缀时装得下", fits(list))
        assertNull("装得下就不压", AgentContext.plan(list, null))

        val reserve = 12_000
        assertTrue("前提：加上前缀就超窗", !fits(list, reserve))
        val plan = planOf(list, null, reserve)
        assertTrue(plan.coverTo > 0 && plan.tail.isNotEmpty())

        val store = tempStore()
        val out = AgentContext.compact(cfg, list, store, reserve = reserve, ask = { "旧事摘要" }, fresh = { list })
        assertTrue("压完（含前缀预留）必须装得下", fits(out, reserve))
        assertEquals("尾部仍在末尾", list.last(), out.last())
    }

    /** 尾部按 token 预算整段收：能收几条由 [AgentContext.TAIL_BUDGET] 和用户边界决定，条数不写死 */
    @Test fun tailKeepsWholeExchangesWithinBudget() {
        val list = convo(20, 1500)
        val start = AgentContext.tailStart(list, 0)
        val tail = list.subList(start, list.size).toList()
        assertTrue("尾部不超过预算", AgentContext.estimateTokens(tail) <= AgentContext.TAIL_BUDGET)
        assertFalse("头部锚在用户消息上", list[start].isOrb)
        assertTrue("预算不是摆设：再往前一整轮就会超",
            start >= 2 && AgentContext.estimateTokens(list.subList(start - 2, list.size).toList()) > AgentContext.TAIL_BUDGET)
    }

    /** 当前这一轮（最后一条用户消息 + 它后面的小珠消息）无论多长都整段保留，也不被截断 */
    @Test fun currentExchangeIsKeptWholeNoMatterHowLong() {
        val huge = "问999" + "长".repeat(20_000)
        val list = convo(20, 1500) + listOf(u(999, huge), a(999, "答999"))
        assertTrue("前提：当前这一轮自己就超过尾部预算",
            AgentContext.estimateTokens(list.takeLast(2)) > AgentContext.TAIL_BUDGET)

        val plan = planOf(list)
        assertEquals("当前 exchange 整段在尾部末尾", list.takeLast(2), plan.tail.takeLast(2))
        assertTrue("覆盖范围到当前 exchange 之前", plan.coverTo <= list.size - 2)

        val store = tempStore()
        val out = AgentContext.compact(cfg, list, store, ask = { "旧事摘要" }, fresh = { list })
        assertEquals("当前请求逐字发出去（不截断）", huge, out[out.size - 2].content)
        assertEquals("当前请求的小珠回复也在", "答999", out.last().content)
    }

    /** 没有可压的旧 exchange（当前这一轮自己就装不下）：明确失败，绝不截断 */
    @Test fun oversizeSingleMessageWithoutOlderHistoryFails() {
        val list = listOf(u(1, "长".repeat(60_000)))
        assertTrue("前提：单条就超窗", !fits(list))
        try {
            AgentContext.plan(list, null)
            fail("没有任何可压的旧 exchange 时必须报错")
        } catch (e: AgentContext.TooLong) {
            assertTrue(e.message.orEmpty().contains("太长"))
        }

        var asked = 0
        val store = tempStore()
        try {
            AgentContext.compact(cfg, list, store, ask = { asked++; "摘要" }, fresh = { list })
            fail("超长必须失败，不能靠截断发出去")
        } catch (_: AgentContext.TooLong) {}
        assertEquals("连摘要请求都不该发", 0, asked)
        assertNull(store.get())
    }

    /** 旧对话能压，但「摘要 + 仍要整段带的这一轮」仍超窗：明确失败（不截断、不白花一次摘要请求） */
    @Test fun compactionFailsWhenTailPlusSummaryCannotFit() {
        val list = convo(10, 1500) + listOf(u(999, "长".repeat(48_000)))
        assertTrue("前提：总量超窗", !fits(list))
        assertTrue("前提：这一轮确实要压才可能装下", planOf(list).coverTo > 0)
        var asked = 0
        val store = tempStore()

        try {
            AgentContext.compact(cfg, list, store, ask = { asked++; "摘要" }, fresh = { list })
            fail("摘要放进去还装不下时必须失败")
        } catch (_: AgentContext.TooLong) {}
        assertEquals("不浪费摘要请求", 0, asked)
        assertNull(store.get())
    }

    // ---------------- 2. 复用 / 指纹 ----------------

    /** 已经有验过的摘要且后缀装得下：不再重新压（前缀不变，缓存继续命中），后续内容一条不跳 */
    @Test fun validSummaryIsReusedWithoutAnotherRequest() {
        val list = convo(20, 1500)
        val covered = 30
        val memo = AgentContext.Memo(covered, AgentContext.fingerprint(list, covered), "早先的结论：松果 4826")
        assertEquals(memo, AgentContext.reuse(list, memo))

        val store = tempStore()
        store.put(memo)
        var asked = 0
        var phases = 0
        val out = AgentContext.compact(
            cfg, list, store, ask = { asked++; "不该再压" }, fresh = { list }, onCompacting = { phases++ },
        )
        assertEquals("复用旧摘要时不该再发请求", 0, asked)
        assertEquals("复用不重压，也不闪「正在整理」", 0, phases)
        assertTrue(out.first().content.contains("松果 4826"))
        assertEquals("摘要 + 摘要之后的每条消息", 1 + list.size - covered, out.size)
        assertEquals("memo.covered 之后一条都不许跳", list.subList(covered, list.size).toList(), out.drop(1))
        assertEquals("当前请求仍在末尾", list.last(), out.last())
    }

    /** 后缀又长大到超窗：只补压 memo.covered 之后、尾部之前的那段，已有摘要当底稿 */
    @Test fun growingTailCompactsOnlyTheUncoveredPart() {
        val head = convo(5, 1500)                     // 10 条已被摘要盖住
        val covered = 10
        val memo = AgentContext.Memo(covered, AgentContext.fingerprint(head, covered), "旧摘要")
        val list = head + convo(25, 1500, at0 = t0 + 1_000_000L)
        assertTrue("前提：后缀自己就超窗", !fits(list.subList(covered, list.size).toList()))

        val plan = planOf(list, memo)
        assertEquals("从已压的边界接着压", covered, plan.coverTo - plan.pending.size)
        assertEquals("已压的部分不再进 pending", list.size - plan.coverTo, plan.tail.size)
        assertEquals("底稿就是已有摘要", "旧摘要", plan.base)
        assertEquals("memo.covered 之后没压到的都被 pending 收走", list.subList(covered, plan.coverTo).toList(), plan.pending)

        val store = tempStore()
        store.put(memo)
        var firstPrompt = ""
        val out = AgentContext.compact(
            cfg, list, store,
            ask = { b -> if (firstPrompt.isEmpty()) firstPrompt = b; "合并后的摘要" },
            fresh = { list },
        )
        assertTrue("摘要请求里带上了已有摘要", firstPrompt.contains("旧摘要"))
        assertTrue("也带上了新补的旧对话", firstPrompt.contains(plan.pending.first().content.take(40)))
        assertTrue(out.first().content.contains("合并后的摘要"))
        assertEquals("尾部仍是完整 exchange", plan.tail, out.drop(1))
        assertEquals("新摘要覆盖到新的边界", plan.coverTo, store.get()!!.covered)
    }

    /** 覆盖范围里的任何一条被改过 → 旧摘要作废（不能只凭条数/时间认） */
    @Test fun editedHistoryInvalidatesSummary() {
        val list = convo(30, 100)
        val memo = AgentContext.Memo(52, AgentContext.fingerprint(list, 52), "旧摘要")
        assertEquals(memo, AgentContext.reuse(list, memo))

        val edited = list.toMutableList().also { it[10] = it[10].copy(content = "改过的内容") }
        assertNull("前 52 条不再逐字相同", AgentContext.reuse(edited, memo))

        val replaced = list.toMutableList().also { it[10] = it[10].copy(at = it[10].at + 1) }
        assertNull("时间戳变了也算改动", AgentContext.reuse(replaced, memo))

        val replacedImg = list.toMutableList().also { it[10] = it[10].copy(img = "x.jpg") }
        assertNull("附图变了也算改动", AgentContext.reuse(replacedImg, memo))
    }

    /** 会话被清空 / 变短 / 覆盖范围越界 → 旧摘要一律不当真 */
    @Test fun clearedOrShorterSessionDropsSummary() {
        val list = convo(30, 100)
        val memo = AgentContext.Memo(52, AgentContext.fingerprint(list, 52), "旧摘要")
        assertNull(AgentContext.reuse(emptyList(), memo))
        assertNull(AgentContext.reuse(convo(3), memo))
        assertNull("覆盖范围盖满整段就没东西可盖了", AgentContext.reuse(list, memo.copy(covered = list.size)))
        assertNull("空正文的摘要不算", AgentContext.reuse(list, memo.copy(text = " ")))
        assertNull(AgentContext.reuse(list, null))
    }

    /** 同一个 pid 的 sidecar 换人格就换文件：两个 Agent 的摘要不可能互相串 */
    @Test fun summaryFileIsPerPersona() {
        assertEquals("chat-ctx-p1.json", AgentContext.file("p1"))
        assertFalse(AgentContext.file("p1") == AgentContext.file("p2"))
    }

    @Test fun sidecarRoundTrips() {
        val store = tempStore()
        assertNull(store.get())
        val m = AgentContext.Memo(7, "abc", "摘要正文")
        store.put(m)
        assertEquals(m, store.get())
        store.clear()
        assertNull(store.get())
    }

    // ---------------- 3. 摘要那条消息本身 / 请求体 ----------------

    /** 摘要永远是一条独立的历史消息：尾部每一条（含当前请求）逐字不动 */
    @Test fun summaryIsItsOwnMessageAndTailIsVerbatim() {
        val tail = listOf(u(1), a(1), u(2), a(2))
        val out = AgentContext.withSummary("摘要正文", tail)
        assertEquals(tail.size + 1, out.size)
        assertTrue(out.first().content.startsWith(AgentContext.BLOCK_HEAD))
        assertTrue("摘要正文在里面", out.first().content.contains("摘要正文"))
        assertEquals("尾部一条都不许改", tail, out.drop(1))
        assertEquals("当前请求仍在末尾", tail.last(), out.last())
        assertFalse("摘要不是小珠说的", out.first().isOrb)
    }

    /** 摘要为空 / 尾部为空时原样返回（不发一条空消息） */
    @Test fun blankSummaryOrEmptyTailIsNoop() {
        val tail = listOf(u(1), a(1))
        assertEquals(tail, AgentContext.withSummary("   ", tail))
        assertEquals(emptyList<ChatStore.Msg>(), AgentContext.withSummary("摘要", emptyList()))
    }

    /** 请求体里就是 system + 摘要 + 尾部：历史不再被二次滑窗裁掉，当前请求的正文逐字发出去 */
    @Test fun bodySendsSummaryPlusVerbatimCurrentRequest() {
        val list = convo(20, 1500)
        val store = tempStore()
        val history = AgentContext.compact(cfg, list, store, ask = { "摘要：青鹭七号在松果 4826 号仓" }, fresh = { list })
        val ms = JSONObject(ChatStore.body(cfg, list, system = "SYS", suffix = "\nSUF", history = history))
            .getJSONArray("messages")
        assertEquals("system + 摘要 + 尾部", 1 + history.size, ms.length())
        assertEquals("system", ms.getJSONObject(0).getString("role"))
        assertEquals("SYS", ms.getJSONObject(0).getString("content"))
        assertTrue(ms.getJSONObject(1).getString("content").contains("青鹭七号"))
        assertEquals("assistant", ms.getJSONObject(ms.length() - 1).getString("role"))
        assertEquals("末条 user 的正文 + suffix，多一个字都不许有",
            "问20" + "长".repeat(1500) + "\nSUF", ms.getJSONObject(ms.length() - 2).getString("content"))
    }

    /** 没有摘要的请求体一个字都没变（[ChatStore.body] 的 history 默认 null） */
    @Test fun bodyWithoutHistoryIsUnchanged() {
        val list = convo(3)
        assertEquals(
            ChatStore.body(cfg, list, system = "SYS"),
            ChatStore.body(cfg, list, system = "SYS", history = null),
        )
    }

    // ---------------- 4. 失败 / 取消不提交 ----------------

    /** 摘要请求失败：异常抛出去（调用方给可见提示），sidecar 一个字都不写 */
    @Test fun failedSummaryWritesNothing() {
        val list = convo(20, 1500)
        val store = tempStore()
        try {
            AgentContext.compact(cfg, list, store, ask = { throw ApiException("HTTP 502（服务端出错，稍后再试）") }, fresh = { list })
            fail("摘要失败必须抛出，不能假装成功")
        } catch (e: ApiException) {
            assertTrue(e.message.orEmpty().contains("502"))
        }
        assertNull("失败不许落盘", store.get())
    }

    /** 空摘要 / 复读提示词 / 超长摘要 / 报错文案都算失败（不能把这种东西记成「旧对话摘要」） */
    @Test fun emptyOrBadSummaryIsFailure() {
        val list = convo(20, 1500)
        val store = tempStore()
        val tooLong = "长".repeat(AgentContext.SUMMARY_MAX_CHARS + 1)
        for (bad in listOf("", "   ", AgentContext.SYSTEM, tooLong, "HTTP 502（服务端出错，稍后再试）")) {
            try {
                AgentContext.compact(cfg, list, store, ask = { bad }, fresh = { list })
                fail("坏摘要必须抛出：'${bad.take(20)}'")
            } catch (_: ApiException) {}
            assertNull("坏摘要不许落盘", store.get())
        }
        assertNull(AgentContext.parse(null))
        assertNull("超长摘要宁可当失败，也不截断成半份事实", AgentContext.parse(tooLong))
        assertEquals("刚好到上限的摘要可用", AgentContext.SUMMARY_MAX_CHARS,
            AgentContext.parse("字".repeat(AgentContext.SUMMARY_MAX_CHARS))!!.length)
    }

    /** 摘要的输出预算必须容得下字数上限（旧值 512 会先撞上限，把合格摘要截断成失败） */
    @Test fun summaryOutputBudgetCoversCharLimit() {
        val needed = Math.ceil(AgentContext.SUMMARY_MAX_CHARS * Usage.ZH_TOKEN_PER_CHAR.toDouble()).toInt()
        assertTrue("输出预算 $needed 也要留余量", AgentContext.SUMMARY_MAX_TOKENS >= needed)
    }

    /** 压缩期间会话被清空：不落盘、也不拿旧摘要去发（只报错让用户重发） */
    @Test fun sessionClearedWhileCompactingAbortsSend() {
        val list = convo(20, 1500)
        val store = tempStore()
        try {
            AgentContext.compact(cfg, list, store, ask = { "摘要" }, fresh = { emptyList() })
            fail("会话变了就不该提交")
        } catch (_: ApiException) {}
        assertNull(store.get())
    }

    /** 被取消（同一人格来了新的一轮）：不写盘、不返回半截结果 */
    @Test fun cancelledCompactionWritesNothing() {
        val list = convo(20, 1500)
        val store = tempStore()
        var asked = 0
        try {
            AgentContext.compact(cfg, list, store, ask = { asked++; "摘要" }, fresh = { list }, cancelled = { true })
            fail("取消之后不该继续")
        } catch (_: ApiException) {
            assertEquals("一次摘要请求都不该发出去", 0, asked)
        }
        assertNull(store.get())
    }

    /** 摘要这一路的报错被塞进会话之后，绝不能再被压成「旧事」（它会变成之后每轮的假事实） */
    @Test fun errorNoticeIsNeverSummarized() {
        val list = listOf(
            ChatStore.user("问1", t0),
            ChatStore.orb("HTTP 502（服务端出错，稍后再试）", t0 + 500),
            ChatStore.user("问2", t0 + 1000),
            ChatStore.orb("摘要", t0 + 1500),
        )
        val prompt = AgentContext.summaryPrompt("", list)
        assertFalse("报错提示不进摘要输入", prompt.contains("HTTP 502"))
        assertTrue(prompt.contains("问1"))
        assertNull("报错文案本身也不能当摘要", AgentContext.parse("HTTP 502（服务端出错，稍后再试）"))
    }

    /** 报错文案的过滤只针对小珠侧：用户自己开头就像报错的话必须原样进摘要，assistant 故障提示照样滤掉 */
    @Test fun userTextStartingLikeErrorStillEnterPrompt() {
        val list = listOf(
            ChatStore.user("HTTP 502 是什么原因", t0),
            ChatStore.orb("连不上网络（稍后再试）", t0 + 500),
            ChatStore.user("连不上网了怎么办", t0 + 1000),
            ChatStore.orb("这轮没有发出去，点重试", t0 + 1500),
        )
        val prompt = AgentContext.summaryPrompt("", list)
        assertTrue("用户问 HTTP 502 的原话要留着", prompt.contains("用户：HTTP 502 是什么原因"))
        assertTrue("用户问连不上网的原话要留着", prompt.contains("用户：连不上网了怎么办"))
        assertFalse("小珠侧的故障提示不进摘要", prompt.contains("连不上网络（稍后再试）"))
        assertFalse("小珠侧的发送失败提示不进摘要", prompt.contains("这轮没有发出去"))
    }

    /** clear 与「核对 + 写」共用一把锁：清空之后旧摘要再也写不回来 */
    @Test fun clearBlocksLateCommit() {
        val list = convo(30, 100)
        val store = tempStore()
        assertTrue("覆盖范围没变才允许落盘",
            store.commit({ list }, AgentContext.Memo(52, AgentContext.fingerprint(list, 52), "旧摘要")))
        assertNotNull(store.get())
        store.clear()
        assertNull(store.get())
        assertFalse("会话已空 → 提交必须失败", store.commit({ emptyList() }, AgentContext.Memo(52, "x", "旧摘要")))
        assertNull(store.get())
    }
}
