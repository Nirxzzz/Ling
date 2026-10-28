package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M3 待办：列表 / 去重 / 排序 / 到期判断 / 简报文案。
 * [Todo.Store] / [TodoReceiver] / [TodoNotify] 碰 Android，不在这里；存储层的并发不变量在文末用 [FileMemo.update] 测。
 */
class TodoTest {
    private val now = 1_760_000_000_000L
    private val day = 24L * 3600 * 1000

    private fun it(
        id: Long,
        text: String = "t$id",
        dueAt: Long = now + day,
        createdAt: Long = 0L,
        done: Boolean = false,
        note: String = "",
        calId: Long = 0L,
        calendar: String = "",
    ) = Todo.Item(id, text, dueAt, createdAt, done, note, calId, calendar)

    // ---------------- 增删改 ----------------

    @Test fun addNewestFirstDedupAndIdBump() {
        var l = Todo.add(emptyList(), it(1))
        l = Todo.add(l, it(2))
        assertEquals(listOf("t2", "t1"), l.map { it.text })
        assertEquals(l, Todo.add(l, it(3, "t2", now + day)))            // 和最新一条文字+时间都一样 → 不重复记
        assertEquals(3, Todo.add(l, it(4, "t1", now + day)).size)        // 去重只看最新一条：和更早的一样 → 照记
        assertEquals(3L, Todo.add(l, it(2, "别的")).first().id)          // id 撞了往后挪
        assertEquals(l, Todo.add(l, it(9, "   ")))               // 空文本不进列表
    }

    @Test fun addKeepsUndoneOverMax() {
        // 250 条都没处理 → 一条都不挤掉（未处理的不能被挤掉是铁律）
        var l = emptyList<Todo.Item>()
        for (i in 1..250) l = Todo.add(l, it(i.toLong()))
        assertEquals(250, l.size)
        assertEquals("t250", l.first().text)
        assertEquals(1L, l.last().id)
    }

    @Test fun addCapsOnceOldOnesBecomeEvictable() {
        // 已完成的老条目可以被挤掉 → 列表回到 MAX
        var l = emptyList<Todo.Item>()
        for (i in 1..250) l = Todo.add(l, it(i.toLong(), done = i <= 100))
        assertEquals(Todo.MAX, l.size)
        assertEquals("t250", l.first().text)
        assertTrue(l.none { it.text == "t50" })                  // 最老的已完成那条被挤掉
        assertTrue(l.any { it.text == "t51" })
    }

    @Test fun trimDropsDoneOrWrittenFirst() {
        // 最新的在最前；超上限时从最老的一头开始删已完成 / 已写日历的
        val l = listOf(it(5, "A"), it(4, "B", done = true), it(3, "C"), it(2, "D", calId = 99), it(1, "E"))
        assertEquals(listOf(5L, 3L, 1L), Todo.trim(l, 3).map { it.id })
        assertEquals(l, Todo.trim(l, 5))                                 // 没超上限原样返回
    }

    @Test fun trimKeepsUndoneEvenWhenOverMax() {
        // 全是没处理的 → 挤不动，宁可超上限也不删（数据不丢优先）
        val l = listOf(it(3), it(2), it(1))
        assertEquals(3, Todo.trim(l, 2).size)
    }

    @Test fun removeAndGet() {
        val l = listOf(it(1), it(2), it(3))
        assertEquals(listOf(1L, 3L), Todo.remove(l, 2L).map { it.id })
        assertEquals(l, Todo.remove(l, 99L))
        assertEquals("t2", Todo.get(l, 2L)?.text)
        assertNull(Todo.get(l, 99L))
    }

    @Test fun settersOnlyTouchTarget() {
        val l = listOf(it(1), it(2))
        assertEquals(listOf(false, true), Todo.setDone(l, 2L, true).map { it.done })
        assertEquals(listOf(l[0].dueAt, now + 5 * day), Todo.setDue(l, 2L, now + 5 * day).map { it.dueAt })   // 没动的那条保持原值
        assertEquals(listOf("", "备注"), Todo.setNote(l, 2L, "备注").map { it.note })
        assertEquals(listOf(0L, 77L), Todo.setCalendar(l, 2L, 77L, "家庭").map { it.calId })
        assertEquals(listOf("", "家庭"), Todo.setCalendar(l, 2L, 77L, "家庭").map { it.calendar })
        assertEquals(l, Todo.setDone(l, 99L, true))                        // id 不在列表里 → 不动
    }

    @Test fun setNoteClipsTo200() {
        val l = Todo.setNote(listOf(it(1)), 1L, "笔".repeat(500))
        assertEquals(201, l[0].note.length)                                 // 200 + 省略号
        assertTrue(l[0].note.endsWith("…"))
    }

    // ---------------- 排序 / 筛选 ----------------

    @Test fun sortedPutsDoneLastThenByDue() {
        val l = listOf(it(3, "晚", now + 3 * day), it(1, "早", now + day), it(2, "已做", now, done = true))
        assertEquals(listOf("早", "晚", "已做"), Todo.sorted(l).map { it.text })
    }

    @Test fun sortedBreaksTieByCreatedAt() {
        val l = listOf(it(1, "A", now + day, createdAt = 200), it(2, "B", now + day, createdAt = 100))
        assertEquals(listOf("B", "A"), Todo.sorted(l).map { it.text })
    }

    @Test fun pendingUpcomingDuePartition() {
        val l = listOf(
            it(1, "过期了", now - day),
            it(2, "刚好到点", now),
            it(3, "还没到", now + day),
            it(4, "做完了", now + 2 * day, done = true),
        )
        assertEquals(listOf(1L, 2L, 3L), Todo.pending(l).map { it.id })
        assertEquals(listOf(3L), Todo.upcoming(l, now).map { it.id })       // 严格大于
        assertEquals(listOf(1L, 2L), Todo.due(l, now).map { it.id })       // 小于等于（含刚好到点）
    }

    @Test fun onDayUsesHalfOpenRange() {
        val (s, e) = Todo.dayRange(now)
        val l = listOf(
            it(1, "当天早上", s + 3600_000),
            it(2, "当天 0 点整", s),                                        // 含
            it(3, "次日 0 点整", e),                                        // 不含
            it(4, "前一天", s - 1000),
        )
        assertEquals(listOf(1L, 2L), Todo.onDay(l, s, e).map { it.id })
    }

    @Test fun dayRangeIsOneDayAndDayStartIsMidnight() {
        val (s, e) = Todo.dayRange(now)
        assertEquals(s, Todo.dayStart(now))
        assertEquals(s, Todo.dayStart(s))
        assertEquals(s, Todo.dayStart(e - 1))                              // 同一天的末尾仍是同一个 0 点
        assertEquals(e, Todo.dayStart(e))                                  // 次日 0 点
        assertTrue(e - s in (23 * 3600_000L..25 * 3600_000L))
    }

    @Test fun daysBetweenIsPlainSubtraction() {
        assertEquals(0L, Todo.daysBetween(100L, 100L))
        assertEquals(3L, Todo.daysBetween(100L, 103L))
        assertEquals(-2L, Todo.daysBetween(100L, 98L))
    }

    @Test fun fmtDueRelativeDays() {
        // v1.85.13：fmtDue 内部把毫秒差归一化成天 —— 用真实的毫秒时刻（天文数字回归哨兵见 FmtDueContractTest）
        val day = 86_400_000L
        val now = 50 * day          // 任意对齐日
        val f = { _: Long -> "15:30" }
        assertEquals("今天 15:30", Todo.fmtDue(now, now, f))
        assertEquals("明天 15:30", Todo.fmtDue(now + day, now, f))
        assertEquals("昨天 15:30", Todo.fmtDue(now - day, now, f))
        assertEquals("3 天后", Todo.fmtDue(now + 3 * day, now, f))
        assertEquals("2 天前", Todo.fmtDue(now - 2 * day, now, f))
    }

    // ---------------- 文案 ----------------

    @Test fun digestEmptyAndAllDone() {
        assertEquals("今天没有待办", Todo.digest(emptyList()))
        assertEquals("2 件都做完了 ✓", Todo.digest(listOf(it(1, done = true), it(2, done = true))))
    }

    @Test fun digestCountsOnlyUndoneAndTruncates() {
        val l = (1..5).map { it(it.toLong(), "待办$it") } + listOf(it(9, "做完了", done = true))
        val s = Todo.digest(l)
        assertTrue(s, s.startsWith("5 件："))
        assertTrue(s, s.contains("待办1、待办2、待办3"))
        assertTrue(s, s.contains(" 等 5 件"))
        assertTrue(s, !s.contains("待办4"))                                // 只列前 3 条
        assertTrue(s, !s.contains("做完了"))
    }

    @Test fun digestKeepsUndoneEvenWhenFinishedComeFirst() {
        val l = listOf(it(1, "已做", now, done = true), it(2, "还没做", now + day))
        assertEquals("1 件：还没做", Todo.digest(l))
    }

    @Test fun digestLongTextTruncatedTo16() {
        val s = Todo.digest(listOf(it(1, "笔".repeat(40))))
        assertEquals("1 件：" + "笔".repeat(16), s)
    }

    @Test fun notiBodyByOverdueSpan() {
        assertEquals("刚刚到期", Todo.notiBody(it(1, dueAt = now), now))
        assertEquals("刚刚到期", Todo.notiBody(it(1, dueAt = now - 59_999), now))
        assertEquals("已过期 5 分钟", Todo.notiBody(it(1, dueAt = now - 5 * 60_000), now))
        assertEquals("已过期 1 小时", Todo.notiBody(it(1, dueAt = now - 3600_000), now))
        assertEquals("已过期 23 小时", Todo.notiBody(it(1, dueAt = now - 23 * 3600_000), now))
        assertEquals("已过期 1 天", Todo.notiBody(it(1, dueAt = now - 86400_000), now))
        assertEquals("已过期 3 天", Todo.notiBody(it(1, dueAt = now - 3 * 86400_000), now))
    }

    @Test fun notiBodyAppendsClippedNote() {
        val t = it(1, dueAt = now, note = "  带备注  ")
        assertEquals("刚刚到期 · 带备注", Todo.notiBody(t, now))
        val long = it(2, dueAt = now, note = "n".repeat(100))
        val s = Todo.notiBody(long, now)
        assertTrue(s, s.startsWith("刚刚到期 · nnn"))
        assertEquals(60 + 1, s.substring("刚刚到期 · ".length).length)      // 60 + 省略号
    }

    @Test fun notiBodyEmptyNoteStaysBare() {
        assertEquals("刚刚到期", Todo.notiBody(it(1, dueAt = now, note = "   "), now))
    }

    @Test fun memoTextClipsToDefault() {
        assertEquals("交周报", Todo.memoText(it(1, text = "  交周报  ")))
        assertEquals(201, Todo.memoText(it(1, text = "x".repeat(400))).length)
    }

    // ---------------- 存取 ----------------

    @Test fun jsonRoundTrip() {
        val l = listOf(
            it(1, "交周报", now + 3600_000, createdAt = 12, note = "n", calId = 77, calendar = "家庭"),
            it(2, "买菜", now + 7200_000, done = true),
        )
        assertEquals(l, Todo.parse(Todo.toJson(l)))
    }

    @Test fun parseSkipsBlankTextAndToleratesGarbage() {
        assertTrue(Todo.parse(null).isEmpty())
        assertTrue(Todo.parse("").isEmpty())
        assertTrue(Todo.parse("   ").isEmpty())
        assertTrue(Todo.parse("[坏").isEmpty())
        assertTrue(Todo.parse("""[{"id":1,"text":"   "},{"id":2}]""").isEmpty())   // 空 / 缺 text → 丢
        assertEquals(1, Todo.parse("""[{"id":1,"text":"好的","dueAt":5},7]""").size) // 非对象元素跳过
    }

    @Test fun parseFillsDefaultsForOldFiles() {
        val t = Todo.parse("""[{"id":1,"text":"老格式","dueAt":9}]""").single()
        assertEquals(Todo.Item(1L, "老格式", 9L, 0L, false, "", 0L, ""), t)
    }

    @Test fun ruleIdAndVibeRoundTrip() {
        val t = Todo.Item(1L, "背单词", now, 0L, false, "", 0L, "", ruleId = 7L, vibe = 3)
        assertEquals(listOf(t), Todo.parse(Todo.toJson(listOf(t))))
        val old = Todo.parse("""[{"id":1,"text":"老格式","dueAt":9}]""").single()
        assertEquals("老文件的生成标记默认是「手加的」", 0L, old.ruleId)
        assertEquals("老文件的强度默认是「跟全局」", -1, old.vibe)
    }

    @Test fun constantsAreStable() {
        assertEquals(200, Todo.MAX)
        assertEquals("todo.json", Todo.FILE)
        assertEquals(50, Todo.NOTI_ID)
        assertEquals("todo", Todo.CHANNEL)
        assertEquals(30 * 60_000L, TodoReceiver.SNOOZE_MS)
    }

    /**
     * 存储层不变量（回归）：读改写必须针对**当前**值，不能拿旧快照整表写。
     * Todo.Store 需要 Context 测不了，但它与 [Schedule.sync] 的原子读改写都落在 [FileMemo.update] 上，测这一层。
     */
    private fun edit(f: File, transform: (List<Todo.Item>) -> List<Todo.Item>) =
        FileMemo.update(f, { Todo.parse(it) }, { Todo.toJson(it) }, transform)

    private fun raw(f: File): List<Todo.Item> = FileMemo.get(f) { Todo.parse(it) }

    private fun it_(text: String, id: Long) = Todo.Item(id, text, 1_000L, 1L)

    /** 与 Todo.Store 的 add 同一形状：发 id → 插到最前（不走 [Todo.add] 的去重，免得测成去重语义） */
    private fun append(l: List<Todo.Item>, text: String): List<Todo.Item> =
        listOf(it_(text, (l.maxOfOrNull { it.id } ?: 0L) + 1)) + l

    @Test fun eachEditSeesLatestValueNotStaleSnapshot() {
        val f = File.createTempFile("todo-rmw", ".json")
        try {
            edit(f) { append(it, "A") }
            edit(f) { append(it, "B") }                          // 用户期间新增
            edit(f) { cur -> cur.filterNot { t -> t.text == "A" } }   // 用户期间删除
            // sync 式整表重排：必须基于当前（只有 B）而不是某个更早的快照
            edit(f) { cur -> cur.filterNot { t -> t.ruleId > 0 } + Todo.Item(9L, "规则项", 5_000L, 1L, ruleId = 9L) }
            val out = raw(f)
            assertEquals("用户删掉的条目不能被物化重排带回", listOf("B", "规则项"), out.map { it.text })
            assertEquals(1, out.count { it.ruleId > 0 })
        } finally {
            FileMemo.clear(f, emptyList<Todo.Item>()); FileMemo.flush()
            f.delete(); File(f.path + ".tmp").delete()
        }
    }

    @Test fun concurrentEditsNeverLoseOrDuplicate() {
        val f = File.createTempFile("todo-concurrent", ".json")
        try {
            val a = Thread { repeat(40) { n -> edit(f) { append(it, "A$n") } } }
            val b = Thread { repeat(40) { n -> edit(f) { append(it, "B$n") } } }
            a.start(); b.start(); a.join(); b.join()
            val out = raw(f)
            assertEquals("并发新增一条都不能丢", 80, out.size)
            assertEquals("id 不能重复", 80, out.map { it.id }.toSet().size)
        } finally {
            FileMemo.clear(f, emptyList<Todo.Item>()); FileMemo.flush()
            f.delete(); File(f.path + ".tmp").delete()
        }
    }

    // ---------------- 提醒震动：通知通道选择（纯逻辑） ----------------

    private fun env(
        enabled: Boolean = true,
        importance: Int = TodoNotify.IMP_DEFAULT,
        userSet: Boolean = false,
    ) = TodoNotify.Env(enabled, importance, userSet)

    /** 补通知（卡已经震过）必须走无声通道 —— 这是「不能 6 秒后又震一次」的钉子 */
    @Test fun silentFallbackNeverVibrates() {
        val e = env()
        for (l in intArrayOf(Vibrate.LIGHT, Vibrate.MID, Vibrate.STRONG, Vibrate.OFF))
            assertEquals("卡已震过，补通知必须无声无震：$l", TodoNotify.CH_QUIET, TodoNotify.channelFor(l, silent = true, env = e))
    }

    /** 每个强度一条**自己的、永不变的**通道（Android 8 通道建好就不能改，所以 id 必须按档位分开） */
    @Test fun eachStrengthHasItsOwnImmutableChannel() {
        val e = env()
        assertEquals(TodoNotify.CH_LIGHT, TodoNotify.channelFor(Vibrate.LIGHT, false, e))
        assertEquals(TodoNotify.CH_MID, TodoNotify.channelFor(Vibrate.MID, false, e))
        assertEquals(TodoNotify.CH_STRONG, TodoNotify.channelFor(Vibrate.STRONG, false, e))
        assertEquals("关档仍是正常通知（有声），只是不震", TodoNotify.CH_OFF, TodoNotify.channelFor(Vibrate.OFF, false, e))
        // 同一条通道映射稳定：哪一档都只有唯一一条，不会今天建 v2、明天又建另一条同名不同参数
        assertEquals(TodoNotify.loudChannel(Vibrate.MID), TodoNotify.channelFor(Vibrate.MID, false, e))
        val ids = listOf(TodoNotify.CH_OFF, TodoNotify.CH_OFF_LOW, TodoNotify.CH_QUIET, TodoNotify.CH_LIGHT, TodoNotify.CH_MID, TodoNotify.CH_STRONG)
        assertEquals("六条通道 id 必须互不相同", ids.size, ids.toSet().size)
    }

    /**
     * 核心故障回归：老 "todo" 通道没显式 enableVibration，震不震我们控制不了 —— 这**不是**「用户动过设置」。
     * 判定只看重要性：默认 DEFAULT 且没设过 → 迁到新分档通道；不能因为老通道「可能不震」就把老用户导回它。
     */
    @Test fun legacyChannelWithoutVibrationIsNotTreatedAsUserTouched() {
        assertEquals("老通道 importance 停在默认、用户没设过 → 照用新分档通道（别退回不震老通道）",
            TodoNotify.CH_LIGHT, TodoNotify.channelFor(Vibrate.LIGHT, false, env(importance = TodoNotify.IMP_DEFAULT)))
        assertEquals(TodoNotify.CH_MID, TodoNotify.channelFor(Vibrate.MID, false, env(importance = TodoNotify.IMP_DEFAULT)))
        assertEquals("压根没有老通道的新用户 → 同样用新分档通道",
            TodoNotify.CH_STRONG, TodoNotify.channelFor(Vibrate.STRONG, false, env()))
    }

    /** 用户动过老通道的重要性（调低到 <=LOW，或 API29+ 说用户设过，调高也认）→ 不许拿新通道绕过 */
    @Test fun userTouchedLegacyImportanceIsRespected() {
        assertEquals("老通道被设成 NONE（禁）→ 不发（哨兵 NONE，消费者直接返回）",
            TodoNotify.NONE, TodoNotify.channelFor(Vibrate.STRONG, false, env(importance = TodoNotify.IMP_NONE)))
        assertEquals("老通道被调低到 LOW（正好卡在边界）→ 回老通道",
            Todo.CHANNEL, TodoNotify.channelFor(Vibrate.STRONG, false, env(importance = TodoNotify.IMP_LOW)))
        assertEquals("边界之上（DEFAULT）没设过 → 才用新分档通道",
            TodoNotify.CH_STRONG, TodoNotify.channelFor(Vibrate.STRONG, false, env(importance = TodoNotify.IMP_DEFAULT)))
        assertEquals("API29+ 用户把老通道调高（4 = IMPORTANCE_HIGH）也认 → 回老通道",
            Todo.CHANNEL, TodoNotify.channelFor(Vibrate.STRONG, false, env(importance = 4, userSet = true)))
        assertEquals("API29+ 用户设过重要性、值即使仍是默认 → 也回老通道",
            Todo.CHANNEL, TodoNotify.channelFor(Vibrate.STRONG, false, env(importance = TodoNotify.IMP_DEFAULT, userSet = true)))
    }

    /**
     * 关档 / 补通知**绝不 fallback 老通道**：老通道震不震我们控制不了，拿它当关档的容器就可能「关了还在震」。
     * 用户压过重要性时也只换到同为不震、低重要性的 [TodoNotify.CH_OFF_LOW]，仍不回老通道。
     */
    @Test fun offAndSilentNeverFallBackToLegacyChannel() {
        assertEquals("关档走自己的不震通道，不回老通道",
            TodoNotify.CH_OFF, TodoNotify.channelFor(Vibrate.OFF, false, env()))
        assertEquals("老通道被动过重要性但没被禁 → 关档换成低重要性不震通道，不拿老通道当容器",
            TodoNotify.CH_OFF_LOW, TodoNotify.channelFor(Vibrate.OFF, false, env(importance = TodoNotify.IMP_LOW)))
        assertEquals(TodoNotify.CH_OFF_LOW, TodoNotify.channelFor(Vibrate.OFF, false, env(importance = 4, userSet = true)))
        assertEquals("补通知同理：老通道被动过也走 CH_QUIET",
            TodoNotify.CH_QUIET, TodoNotify.channelFor(Vibrate.STRONG, true, env(importance = TodoNotify.IMP_LOW)))
    }

    /**
     * 关档遇到「用户把老通道压到低重要性」：同时守两件事 —— 不震（不回老通道）、也不把重要性顶回 DEFAULT。
     * 走 [TodoNotify.CH_OFF_LOW]（不震 + 低重要性），别让「关震动」反而把用户的低重要性设置顶响。
     */
    @Test fun offHonorsUserLowImportanceCeiling() {
        assertEquals("老通道被压到 LOW → 关档走低重要性不震通道，别拿 DEFAULT 顶上去",
            TodoNotify.CH_OFF_LOW, TodoNotify.channelFor(Vibrate.OFF, false, env(importance = TodoNotify.IMP_LOW)))
        assertEquals("API29+ 用户设过重要性（即便值仍是 DEFAULT）→ 关档同样退到低重要性不震通道",
            TodoNotify.CH_OFF_LOW, TodoNotify.channelFor(Vibrate.OFF, false, env(importance = TodoNotify.IMP_DEFAULT, userSet = true)))
        assertEquals("老通道没被动过（默认）→ 关档用普通「关震动」通道（有声不震）",
            TodoNotify.CH_OFF, TodoNotify.channelFor(Vibrate.OFF, false, env()))
    }

    /**
     * 消费者边界：老通道被用户禁掉 / 通知总开关关了 → [TodoNotify.NONE]。
     * 消费者（[TodoNotify.notify]）见它必须直接返回 —— 不许用 [TodoNotify.CH_QUIET] 偷偷绕过去，
     * 也不许往不存在的老通道（新装根本没有 "todo"）发一条无效通知。
     */
    @Test fun blockedOrDisabledYieldsNoneSentinel() {
        val blocked = env(importance = TodoNotify.IMP_NONE)
        assertEquals("老通道被禁 → 连补通知也 NONE（不许偷偷弹静默频道）",
            TodoNotify.NONE, TodoNotify.channelFor(Vibrate.STRONG, silent = true, env = blocked))
        assertEquals("老通道被禁 → 关档也 NONE", TodoNotify.NONE, TodoNotify.channelFor(Vibrate.OFF, false, blocked))
        assertEquals("老通道被禁 → 响档也 NONE", TodoNotify.NONE, TodoNotify.channelFor(Vibrate.LIGHT, false, blocked))
        assertEquals("通知总开关关 → NONE", TodoNotify.NONE, TodoNotify.channelFor(Vibrate.STRONG, false, env(enabled = false)))
        assertEquals("总开关关时连补通知也 NONE", TodoNotify.NONE, TodoNotify.channelFor(Vibrate.STRONG, true, env(enabled = false)))
        assertTrue("NONE 是空串哨兵，不能撞上任何真实通道 id",
            listOf(TodoNotify.CH_OFF, TodoNotify.CH_OFF_LOW, TodoNotify.CH_QUIET, TodoNotify.CH_LIGHT, TodoNotify.CH_MID, TodoNotify.CH_STRONG)
                .none { it == TodoNotify.NONE })
    }

    /** 单条强度解析：-1 = 跟全局；0 = 这条自己关；越界归默认档 */
    @Test fun levelFollowsItemVibeOrGlobal() {
        assertEquals(Vibrate.STRONG, TodoNotify.levelOf(Schedule.VIBE_GLOBAL, Vibrate.STRONG))
        assertEquals(Vibrate.OFF, TodoNotify.levelOf(Vibrate.OFF, Vibrate.STRONG))
        assertEquals(Vibrate.LIGHT, TodoNotify.levelOf(Vibrate.LIGHT, Vibrate.STRONG))
        assertEquals(Vibrate.DEFAULT, TodoNotify.levelOf(99, Vibrate.OFF))
    }

    /** 稍后 30 分钟：文案 / 时间语义不变，震动档跟着原条目走，rule provenance 有意不带 */
    @Test fun snoozeKeepsVibeAndExactThirtyMinutes() {
        val t = Todo.Item(7L, "喝水", now, 0L, note = "n", ruleId = 3L, vibe = Vibrate.STRONG)
        val s = Todo.snoozeItem(t, now, TodoReceiver.SNOOZE_MS)
        assertEquals("喝水（稍后）", s.text)
        assertEquals("30 分钟语义不能改", now + 30 * 60_000L, s.dueAt)
        assertEquals("稍后那一版要保持用户给这条设的震动档", Vibrate.STRONG, s.vibe)
        assertEquals("不带 ruleId：否则下一趟 Schedule.sync 会把它当闲置生成项收走", 0L, s.ruleId)
        assertTrue("稍后是新的一条，不能顶掉原条目的身份", s.id != t.id)
    }
}
/** edge 轮 3（v1.85.13）：确认卡/待办列表把原始毫秒当日索引用 → 「41526013 天前」天文数字。钉住正确口径 */
class FmtDueContractTest {
    @Test fun `fmtDue expects day indices not raw millis`() {
        val now = 1_791_800_000_000L
        val dueTomorrow = now + 86_400_000L
        val ok = Todo.fmtDue(Todo.dayStart(dueTomorrow), Todo.dayStart(now)) { "09:52" }
        assertEquals("明天 09:52", ok)
        // 原始毫秒直接传会产出天文数字（回归哨兵：断言不出现 5 位以上天数）
        val bad = Todo.fmtDue(dueTomorrow, now) { "09:52" }
        assertTrue("错误口径的天文数字不应再出现", !bad.contains(Regex("\\d{5,} 天")))
    }
}
