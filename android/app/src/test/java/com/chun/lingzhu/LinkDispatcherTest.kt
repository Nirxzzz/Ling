package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * [LinkRules] 与参数/审计清洗的纯函数断言（不碰 Android）。
 *
 * 每一条都在钉一个安全或正确性决定：
 *  - 截止时刻**猜错方向是不对称的**（当剩余毫秒用时永远不过期，当绝对时刻用立刻过期）；
 *  - 不带时区的 ISO 时间**必须拒**，否则提醒会排到半夜；
 *  - 指纹必须把用户改过的条目与「当初写进去的那条」分开，否则 rollback 会删用户数据；
 *  - 审计 / 确认框的值必须去掉换行并截断，否则一行审计会毁掉文件结构。
 */
class LinkDispatcherTest {

    // ---------------- 错误码表 ----------------

    @Test fun everyToolHasAScopeAndOnlyKnownScopes() {
        val allowed = setOf(
            LinkDispatcher.SCOPE_READ, LinkDispatcher.SCOPE_WRITE,
            LinkDispatcher.SCOPE_ACT, LinkDispatcher.SCOPE_PERSONAL,
        )
        assertEquals(7, LinkDispatcher.TOOLS.size)
        for ((tool, scope) in LinkDispatcher.TOOLS) {
            assertTrue("工具 $tool 的 scope 必须在 read/write/act/personal 里", scope in allowed)
        }
        // 敏感读 / 操作类工具的要求不能被放宽
        assertEquals(LinkDispatcher.SCOPE_ACT, LinkDispatcher.TOOLS["phone_screen_read"])
        assertEquals(LinkDispatcher.SCOPE_ACT, LinkDispatcher.TOOLS["phone_open_app"])
        assertEquals(LinkDispatcher.SCOPE_WRITE, LinkDispatcher.TOOLS["memory_add"])
        assertEquals(LinkDispatcher.SCOPE_READ, LinkDispatcher.TOOLS["memory_search"])
    }

    @Test fun sensitiveCategoriesAreExactlyEmotionAndRelationship() {
        assertEquals(setOf("EMOTION", "RELATIONSHIP"), LinkDispatcher.SENSITIVE)
        // 可写四类与敏感两类**互不相交**：外部 Agent 永远写不进情绪 / 关系
        assertTrue(LinkDispatcher.WRITABLE.intersect(LinkDispatcher.SENSITIVE).isEmpty())
        assertEquals(setOf("FACT", "PREFERENCE", "EVENT", "HABIT"), LinkDispatcher.WRITABLE)
    }

    @Test fun aliasPatternRejectsPackagesAndPaths() {
        assertTrue(LinkDispatcher.ALIAS_RE.matches("ling_review"))
        assertTrue(LinkDispatcher.ALIAS_RE.matches("calendar"))
        assertTrue(LinkDispatcher.ALIAS_RE.matches("app_2"))
        // 包名 / 大小写 / 空白 / 路径都不是合法别名：工具参数里绝不放任意包名
        assertFalse(LinkDispatcher.ALIAS_RE.matches("com.android.calendar"))
        assertFalse(LinkDispatcher.ALIAS_RE.matches("Calendar"))
        assertFalse(LinkDispatcher.ALIAS_RE.matches("a b"))
        assertFalse(LinkDispatcher.ALIAS_RE.matches(""))
        assertFalse(LinkDispatcher.ALIAS_RE.matches("a".repeat(33)))
    }

    @Test fun originPatternMatchesOnlyMcpClients() {
        assertTrue(LinkDispatcher.ORIGIN_RE.matches("mcp:claude-code"))
        assertTrue(LinkDispatcher.ORIGIN_RE.matches("mcp:a.b_c-1"))
        assertFalse(LinkDispatcher.ORIGIN_RE.matches("mcp:"))
        assertFalse(LinkDispatcher.ORIGIN_RE.matches("claude-code"))
        assertFalse(LinkDispatcher.ORIGIN_RE.matches("mcp:a b"))
    }

    // ---------------- 截止时刻 ----------------

    @Test fun deadlineZeroMeansNoDeadline() {
        val now = 1_760_000_000_000L
        assertEquals(0L, LinkRules.deadlineAt(null, now))
        assertEquals(0L, LinkRules.deadlineAt(JSONObject.NULL, now))
        assertEquals(0L, LinkRules.deadlineAt(0, now))
        assertEquals(0L, LinkRules.deadlineAt(-5, now))
        assertEquals(0L, LinkRules.deadlineAt("abc", now))
        assertFalse(LinkRules.expired(0L, now))
    }

    @Test fun absoluteDeadlineIsTakenAsIs() {
        val now = 1_760_000_000_000L
        val abs = now + 12_000L
        assertEquals(abs, LinkRules.deadlineAt(abs, now))
        assertFalse(LinkRules.expired(abs, now))
        assertTrue(LinkRules.expired(abs, abs))
        assertTrue(LinkRules.expired(abs, abs + 1))
    }

    @Test fun smallNumbersAreRemainingMillis() {
        val now = 1_760_000_000_000L
        // 「还剩 9000ms」不能当成 1970 年的绝对时刻（那样会立刻判过期、每条指令都白跑）
        assertEquals(now + 9_000L, LinkRules.deadlineAt(9_000, now))
        assertEquals(now + 9_000L, LinkRules.deadlineAt("9000", now))
    }

    @Test fun absurdFarFutureIsClampedNotTrusted() {
        val now = 1_760_000_000_000L
        val tenYears = now + 10L * 365 * 86_400_000L
        val d = LinkRules.deadlineAt(tenYears, now)
        // 夹到 30 天内：一个错的时间源不该换来「永不超时」
        assertTrue(d in (now + 1)..(now + 30L * 86_400_000L))
        assertFalse(LinkRules.expired(d, now))
    }

    // ---------------- ISO 8601 ----------------

    @Test fun isoWithOffsetParsesToSameInstant() {
        val a = LinkRules.parseIso("2026-10-22T20:00:00+08:00")
        val b = LinkRules.parseIso("2026-10-22T12:00:00Z")
        assertEquals(a, b)
        assertTrue(a != null && a > 0L)
        // 带毫秒的写法也收
        assertEquals(a, LinkRules.parseIso("2026-10-22T20:00:00.000+08:00"))
    }

    @Test fun isoWithoutOffsetIsRejected() {
        // 不带时区的时间在任何时区都「看着对」：猜错就是把提醒排到半夜，所以一律拒
        assertNull(LinkRules.parseIso("2026-10-22T20:00:00"))
        assertNull(LinkRules.parseIso("2026-10-22 20:00:00"))
        assertNull(LinkRules.parseIso(""))
        assertNull(LinkRules.parseIso("明天晚上八点"))
    }

    @Test fun isoLocalRoundTripsThroughParse() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val ms = LinkRules.parseIso("2026-10-22T20:00:00+08:00")!!
        val text = LinkRules.isoLocal(ms, tz)
        assertEquals("2026-10-22T20:00:00+08:00", text)
        // 回给电脑的字符串必须能被自己解析回同一个时刻
        assertEquals(ms, LinkRules.parseIso(text))
    }

    @Test fun isoLocalOfZeroIsEmpty() {
        assertEquals("", LinkRules.isoLocal(0L))
        assertEquals("", LinkRules.isoLocal(-1L))
    }

    // ---------------- 指纹 ----------------

    @Test fun hash16IsStableAndShort() {
        val h = LinkRules.hash16("养了一只橘猫")
        assertEquals(16, h.length)
        assertEquals(h, LinkRules.hash16("养了一只橘猫"))
        // 差一个字必须换指纹：否则用户编辑过的条目会被当成「还是我写的那条」删掉
        assertTrue(h != LinkRules.hash16("养了一只白猫"))
    }

    @Test fun reminderFingerprintCoversTextTimeAndNote() {
        val base = Todo.Item(id = 7L, text = "交周报", dueAt = 1_760_000_000_000L, note = "由 Claude 创建")
        val same = base.copy()
        val movedTime = base.copy(dueAt = base.dueAt + 60_000L)
        val editedText = base.copy(text = "交月报")
        val editedNote = base.copy(note = "自己写的")
        val done = base.copy(done = true)

        assertEquals(LinkRules.reminderFingerprint(base), LinkRules.reminderFingerprint(same))
        // 时间 / 文案 / 备注任一被用户改过，指纹就不同 → rollback 跳过它
        assertTrue(LinkRules.reminderFingerprint(base) != LinkRules.reminderFingerprint(movedTime))
        assertTrue(LinkRules.reminderFingerprint(base) != LinkRules.reminderFingerprint(editedText))
        assertTrue(LinkRules.reminderFingerprint(base) != LinkRules.reminderFingerprint(editedNote))
        // 「完成」不在指纹里（它有自己的判断），但 id 也不该混进去（同一条待办换 id 仍是同一件事）
        assertEquals(LinkRules.reminderFingerprint(base), LinkRules.reminderFingerprint(done))
        assertEquals(LinkRules.reminderFingerprint(base), LinkRules.reminderFingerprint(base.copy(id = 99L)))
    }

    // ---------------- 去重判定 ----------------

    @Test fun dupIndexFindsSameThingAndIgnoresNewThing() {
        val existing = listOf(
            AgentMemory.Mem("用户养了一只橘猫，名字叫年糕", "FACT", 0.6),
            AgentMemory.Mem("用户每天背三十个单词", "HABIT", 0.7),
        )
        assertEquals(0, LinkRules.dupIndex(existing, "用户养了一只橘猫，名字叫年糕")!!)
        assertNull(LinkRules.dupIndex(existing, "用户正在准备考研数学"))
        // 空库永远不判重
        assertNull(LinkRules.dupIndex(emptyList(), "随便什么"))
    }

    // ---------------- 确认结论 → 协议值 ----------------

    @Test fun confirmLabelsAreTheThreeWireValuesOrNA() {
        assertEquals("approved", LinkRules.confirmLabel(LinkConfirm.Outcome.APPROVED))
        assertEquals("denied", LinkRules.confirmLabel(LinkConfirm.Outcome.DENIED))
        assertEquals("timeout", LinkRules.confirmLabel(LinkConfirm.Outcome.TIMEOUT))
        // 弹不出框 = 没得到同意：必须按 denied 算，绝不能算 approved 或 n/a
        assertEquals("denied", LinkRules.confirmLabel(LinkConfirm.Outcome.UNAVAILABLE))
        // L1 / L2 工具没有确认步骤 → n/a
        assertEquals("n/a", LinkRules.confirmLabel(null))
    }

    // ---------------- 清洗 / 截断 ----------------

    @Test fun auditCleanStripsNewlinesAndTruncates() {
        assertEquals("a b c", LinkAudit.clean("a\nb\tc", 10))
        assertEquals("", LinkAudit.clean(null, 10))
        assertEquals("", LinkAudit.clean("", 10))
        assertEquals("abc", LinkAudit.clean("abcdef", 3))
        // 控制字符也不能进审计（一行一条 JSONL，混进控制字符就能伪造行）
        assertEquals("a b", LinkAudit.clean("a\u0000b", 10))
    }

    @Test fun auditNormalizeConfirmOnlyAcceptsWireValues() {
        assertEquals("approved", LinkAudit.normalizeConfirm("approved"))
        assertEquals("denied", LinkAudit.normalizeConfirm("denied"))
        assertEquals("timeout", LinkAudit.normalizeConfirm("timeout"))
        assertEquals("n/a", LinkAudit.normalizeConfirm("yes"))
        assertEquals("n/a", LinkAudit.normalizeConfirm(null))
    }

    @Test fun confirmClipValueNeverShowsAnEmptyRow() {
        assertEquals("（空）", LinkConfirm.clipValue(""))
        assertEquals("（空）", LinkConfirm.clipValue(null))
        assertEquals("读一屏", LinkConfirm.clipValue("  读一屏  "))
        // 超长参数在框里截断：确认框是「看一眼」的地方，不该被一屏字挤满
        val long = "查".repeat(LinkConfirm.VALUE_MAX + 50)
        assertEquals(LinkConfirm.VALUE_MAX, LinkConfirm.clipValue(long).length)
    }

    @Test fun confirmCountdownTextNeverGoesNegative() {
        assertEquals("还有 20 秒自动拒绝", LinkConfirm.secondsText(20))
        assertEquals("还有 0 秒自动拒绝", LinkConfirm.secondsText(0))
        assertEquals("还有 0 秒自动拒绝", LinkConfirm.secondsText(-3))
    }

    @Test fun scrimOnlyChangesAlphaNotHue() {
        val ink = 0xFF141414.toInt()
        val scrim = LinkConfirm.withAlpha(ink, LinkConfirm.SCRIM_ALPHA)
        // 只动透明度：主题换到瑞士黑白 / 青春配色时，遮罩仍取当前主题的墨色
        assertEquals(ink and 0x00FFFFFF, scrim and 0x00FFFFFF)
        assertEquals(LinkConfirm.SCRIM_ALPHA, LinkConfirm.alphaOf(scrim))
        // 半透明 —— 既看得见底下的 App，又不至于全黑挡死画面
        assertTrue(LinkConfirm.alphaOf(scrim) in 1..254)
    }

    @Test fun timeoutIsTwentySeconds() {
        assertEquals(20_000L, LinkConfirm.TIMEOUT_MS)
        // 摘窗到截屏之间的等待要短于 1 秒（否则用户会看到迟滞，还容易截到别的画面）
        assertTrue(LinkConfirm.HIDE_SETTLE_MS in 1L..999L)
    }

    // ---------------- 读屏参数 ----------------

    @Test fun ocrPromptForbidsSolving() {
        val p = LinkDispatcher.OCR_PROMPT
        // 纯转写：不能带「作答 / 解释」这类会污染结果的指令
        assertTrue(p.contains("只转写"))
        assertTrue(p.contains("不解释"))
        assertTrue(p.contains("[看不清]"))
        assertTrue(p.contains("[没有文字]"))
        assertFalse(p.contains("解题"))
        assertTrue(LinkDispatcher.MODES_READ == setOf("ocr", "solve"))
    }

    // ---------------- 别名生成（纯函数部分） ----------------

    @Test fun baseAliasIsLegalForEveryPackageName() {
        assertEquals("calendar", LinkAliasBook.baseAlias("com.android.calendar"))
        assertEquals("app_1password", LinkAliasBook.baseAlias("com.agilebits.onepassword.1password"))
        assertEquals("app", LinkAliasBook.baseAlias("com.x.---"))
        // 生成结果必须永远满足工具参数的形状约束（别名是唯一允许出现在参数里的东西）
        val pkgs = listOf("com.android.calendar", "a", "com.x.1", "com.foo.bar_baz", "com.x." + "y".repeat(60))
        for (pkg in pkgs) {
            val alias = LinkAliasBook.baseAlias(pkg)
            assertTrue("$pkg -> $alias", LinkDispatcher.ALIAS_RE.matches(alias))
        }
    }

    @Test fun pickAliasNeverReusesAReservedName() {
        // 别名一旦指过某个包就不再回收：卸载后重装不能让两个包拿到同一个名字
        val reserved = setOf("notes", "ling_review", "ling_words", "calendar")
        val alias = LinkAliasBook.pickAlias("notes", reserved)
        assertEquals("notes_2", alias)
        assertTrue(alias !in reserved)
        assertTrue(LinkDispatcher.ALIAS_RE.matches(alias))
        // 空集合 → 直接用包名末段；连续撞名时逐个加后缀
        assertEquals("notes", LinkAliasBook.pickAlias("notes", emptySet()))
        assertEquals("notes_3", LinkAliasBook.pickAlias("notes", setOf("notes", "notes_2")))
        // 长包名的后缀也要压在 32 字以内（工具参数形状硬约束）
        val long = LinkAliasBook.pickAlias("y".repeat(32), setOf("y".repeat(32)))
        assertEquals(32, long.length)
        assertTrue(LinkDispatcher.ALIAS_RE.matches(long))
    }

    @Test fun aliasStaysPutAcrossUninstallAndReinstall() {
        // 台账口径：pkg2alias 的值 + alias2pkg 的键都算「历史上分配过」，卸载也不回收
        val pkgA = "com.example.notes"
        val pkgB = "com.other.notes"
        val pkg2alias = LinkedHashMap<String, String>()
        val alias2pkg = LinkedHashMap<String, String>()
        fun allocate(pkg: String, installed: Set<String>): String {
            pkg2alias[pkg]?.let { return it }        // 已有映射直接返回（重装也不改名）
            val reserved = HashSet(installed).apply {
                addAll(pkg2alias.values)
                addAll(alias2pkg.keys)
            }
            val a = LinkAliasBook.pickAlias(LinkAliasBook.baseAlias(pkg), reserved)
            pkg2alias[pkg] = a
            alias2pkg[a] = pkg
            return a
        }
        val aliasA = allocate(pkgA, setOf("ling_review", "ling_words", "calendar"))
        assertEquals("notes", aliasA)
        // A 卸载后，B 装进来：别名绝不能被收回，否则设置页勾的开关会指到另一个 App
        val aliasB = allocate(pkgB, setOf("ling_review", "ling_words", "calendar", "notes"))
        assertEquals("notes_2", aliasB)
        assertTrue(aliasA != aliasB)
        // A 重装：还是当初那个名字，且别名不会被两个包共用
        assertEquals(aliasA, allocate(pkgA, setOf("ling_review", "ling_words", "calendar", aliasB)))
        assertEquals(pkgA, alias2pkg[aliasA])
        assertEquals(pkgB, alias2pkg[aliasB])
    }

    // ---------------- 归属判定（rollback 只删「我们新写的、用户没动过的」） ----------------

    @Test fun existingUserTodoIsNeverClaimedAsCreated() {
        // 用户自己已经建了同一条：`Todo.add` 原样返回旧表，Store.add 回的正是用户那条的 id
        val userIds = setOf(1L, 7L)
        assertFalse(LinkRules.todoCreated(userIds, 7L))
        // 这次新分配出来的 id（表内 max+1）才算我们建的
        assertTrue(LinkRules.todoCreated(userIds, 8L))
        // 写入失败（-1 / 0）当然不算
        assertFalse(LinkRules.todoCreated(userIds, -1L))
        assertFalse(LinkRules.todoCreated(userIds, 0L))
    }

    @Test fun onlyFreshlyInsertedMemoryCanBeOwned() {
        val cat = AgentMemory.Mem("用户养了一只橘猫，名字叫年糕")
        val fact = AgentMemory.Mem("用户每天背三十个单词")
        val before = listOf(cat, fact)
        // 真正新增：条数变了，这条新正文出现在合并结果里
        val added = before + AgentMemory.Mem("用户正在准备考研数学")
        assertTrue(LinkRules.memoryInserted(before, added, "用户正在准备考研数学"))
        // 合并进用户已有那条：正文没变、条数没变 —— 绝不能登记成客户端写入，否则 rollback 删用户原话
        val merged = listOf(cat.copy(importance = 0.9), fact)
        assertFalse(LinkRules.memoryInserted(before, merged, cat.content))
        // 库里本来就有这句话、这次又提了一遍：同样不算新增
        assertFalse(LinkRules.memoryInserted(before, before, cat.content))
    }

    @Test fun ownershipIsNotStolenFromAnotherClient() {
        // 没记过 → 可以登记；本来就是自己写的 → 可以重记（同一客户端重试）
        assertTrue(LinkRules.mayClaim(null, "mcp:claude-code"))
        assertTrue(LinkRules.mayClaim("mcp:claude-code", "mcp:claude-code"))
        // 别的客户端先写过同一句 → 不覆盖：覆盖会把别人的写入认成自己的，撤销时删错
        assertFalse(LinkRules.mayClaim("mcp:other", "mcp:claude-code"))
    }

    @Test fun expiredRollbackDoesNothing() {
        // 截止时刻过了就一步都不做：已提交的删除收不回来，宁可不删
        assertEquals(LinkRules.TIMEOUT, LinkRules.sideEffectBlock(expired = true, cancelled = false))
        // 过期优先于取消（调用方最需要知道的是「电脑早已判超时」）
        assertEquals(LinkRules.TIMEOUT, LinkRules.sideEffectBlock(expired = true, cancelled = true))
        // 连接断开同样不做，但错误码要如实是取消
        assertEquals(LinkRules.CANCELLED, LinkRules.sideEffectBlock(expired = false, cancelled = true))
        // 只有两者都不成立才允许动手
        assertNull(LinkRules.sideEffectBlock(expired = false, cancelled = false))
    }
}
