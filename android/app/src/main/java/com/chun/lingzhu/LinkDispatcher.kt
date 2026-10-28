package com.chun.lingzhu

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 电脑经 Ling Link 发来的指令在这一层执行（v2.7 MCP）。
 *
 * ## 这一层是**唯一**的权限关卡
 * 契约写明「永远不信任线路上带来的 `level`」：电脑说 L1 不代表它真是 L1，电脑说已确认也不代表手机上点过。
 * 所以每条指令都在这里重新做三件事：
 *  1. **scope**：按工具自己的要求查 `command.client.scopes`（read / write / act / personal）。缺 scope 直接拒 ——
 *     不因为电脑那边已经过滤过就放行，两端各判一次，任一端出 bug 都不会越权。
 *  2. **args**：逐字段校验类型 / 长度 / 取值域，超出 schema 一律 [LinkRules.INVALID_ARGS]，不做「尽力猜」。
 *  3. **deadline**：指令带截止时刻。**确认前**和**每个副作用之前**各查一次；过期就 [LinkRules.TIMEOUT] 且不执行。
 *     已经提交的写入无法撤销，这个类不会因此谎称「已回滚」。
 *
 * ## 为什么不在这里写业务
 * 记忆、待办、截屏、启动 App 全都有现成的真实实现（[AgentMemory] / [Todo] / [A11yService] / [PackageManager]）。
 * 这一层只做「校验 + 调它 + 如实回报」：任何一处拿不到真实结果就报真实错误码，**绝不编造成功回执**。
 *
 * ## 序列化
 * 传输层用单线程执行器调 [execute]，这里再加一把锁兜底：同一条配对上永远只有一条指令在跑，
 * 不会出现「两条提醒抢同一个 id」或「两个确认框叠在一起」。这把锁**不跨**长轮询，[cancel] 也不去抢它。
 *
 * ## 隐私口径（涉及 T7）
 * 读屏截图**留在手机上**：只有转写／答题得到的**文字**回到电脑。但截图会发给用户在本机配置的识别模型
 * （现有识屏链路的既定行为，确认框上如实写明）。本类不把任何原始图像写进结果、审计或日志。
 */
class LinkDispatcher(context: Context) {

    private val ctx: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audit = LinkAudit(ctx)
    private val confirm = LinkConfirm(ctx)
    private val pairs = LinkPairStore(ctx)
    private val aliases = LinkAliasBook(ctx)

    /** 执行串行化（传输层已是单线程，这里是兜底） */
    private val execLock = Any()

    /**
     * 取消代数：[cancel] 每调一次 +1。execute 开跑时记下当时的值，任一步发现变了就立刻放弃。
     * 用代数而不是布尔：取消之后**下一条**指令必须还能正常跑（服务只是断了一次网，不是永久停摆）。
     */
    @Volatile private var cancelGen = 0

    /** 在途的视觉请求：[cancel] 直接 abort 连接，别让它在后台跑完再回来记副作用 */
    @Volatile private var inFlight: Cancel? = null

    /** 上次截屏失败的原因（给人看的真话），供 T7 的错误信息用 */
    @Volatile private var lastScreenError: String = "未知原因"

    // ==================================================================================
    // 对外接口
    // ==================================================================================

    /**
     * 设备能力（hello 里报给电脑）。**只报现在真能做的** —— 报了却做不了，等于让电脑点一个死按钮。
     * `apps` 带全部已知别名与开关状态（传输层只挑 `on` 的发给电脑，见 LingLink.enabledApps）。
     */
    fun capabilities(): JSONObject {
        val list = appList()
        val caps = JSONArray()
        caps.put("memory")   // 本地文件读写，无系统权限门槛
        // 待办：没有通知权限时提醒等于不响（Android 13+），这种情况不报 —— 报了就是骗人
        if (RemindExact.notiGranted(ctx)) caps.put("todo")
        // 读屏：要无障碍已连接（能截）且能弹确认框（要悬浮窗）
        if (A11yService.available && Settings.canDrawOverlays(ctx)) caps.put("screen")
        // 打开 App：要悬浮窗（确认框 + Android 10+ 后台启动豁免）且至少有一个真能打开的别名
        if (Settings.canDrawOverlays(ctx) && list.any { it.alias !in INTERNAL_ALIASES }) caps.put("open_app")
        return JSONObject()
            .put("model", Build.MODEL ?: "")
            .put("app", appLabel())
            .put("caps", caps)
            .put("apps", appsWithState(list))
    }

    /** 全部已知别名 + 开关状态（设置页渲染 / hello 上报）。只读本地偏好，任意线程安全。 */
    fun apps(): JSONArray = appsWithState(appList())

    /**
     * 执行一条指令。
     *
     * 输入（来自线路）：`{id, tool, args, level, client:{id,name,scopes}}`，可带 `deadline`。
     * 输出：`{id, ok, result|error:{code,message}, confirm}`。`confirm` 只有 approved / denied / timeout
     * 三个有意义的值，其余一律 `n/a`（与 LingLink.normalizeConfirm 同口径）。
     */
    fun execute(command: JSONObject): JSONObject {
        val id = command.optString("id", "")
        val tool = command.optString("tool", "").trim()
        val client = command.optJSONObject("client")
        val ex = Exec(
            id = id,
            tool = tool,
            clientName = client?.optString("name")?.takeIf { it.isNotBlank() } ?: "未知客户端",
            clientId = client?.optString("id")?.takeIf { it.isNotBlank() } ?: "-",
            startedAt = System.currentTimeMillis(),
            deadline = LinkRules.deadlineAt(command.opt("deadline"), System.currentTimeMillis()),
            gen = cancelGen,
        )

        if (id.isBlank()) return ex.fail(LinkRules.INVALID_ARGS, "指令缺少 id")
        if (tool.isBlank()) return ex.fail(LinkRules.INVALID_ARGS, "指令缺少 tool")
        val scope = TOOLS[tool] ?: return ex.fail(LinkRules.NOT_SUPPORTED, "手机不支持工具 $tool")
        // ---- scope（不信任线路的 level）----
        if (!hasScope(client, scope)) {
            return ex.fail(LinkRules.SCOPE_DENIED, "这个客户端没有 $scope 权限，手机拒绝执行 $tool")
        }
        // ---- 截止时刻（确认前第一道）----
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "指令已过截止时刻，未执行")

        val args = command.optJSONObject("args") ?: JSONObject()
        return try {
            synchronized(execLock) {
                if (ex.cancelled()) return ex.fail(LinkRules.CANCELLED, "连接已断开，指令取消")
                when (tool) {
                    "memory_search" -> memorySearch(ex, args, client)
                    "memory_add" -> memoryAdd(ex, args)
                    "reminder_create" -> reminderCreate(ex, args)
                    "phone_screen_read" -> phoneScreenRead(ex, args)
                    "phone_open_app" -> phoneOpenApp(ex, args)
                    "profile" -> profile(ex, client)
                    "rollback" -> rollback(ex, args)
                    else -> ex.fail(LinkRules.NOT_SUPPORTED, "手机不支持工具 $tool")
                }
            }
        } catch (e: BadArgs) {
            ex.fail(LinkRules.INVALID_ARGS, e.message ?: "参数不合法")
        } catch (e: Cancelled) {
            ex.fail(LinkRules.CANCELLED, e.message ?: "连接已断开，指令取消")
        } catch (e: Expired) {
            ex.fail(LinkRules.TIMEOUT, e.message ?: "指令已过截止时刻")
        } catch (e: Throwable) {
            ex.fail(LinkRules.INTERNAL, brief(e))
        }
    }

    /**
     * 取消：断网 / 用户关掉常连 / 撤销配对 / 服务停时由传输层调用。
     * 三件事立刻做到 —— 在等的确认框按拒绝收掉、在途视觉请求 abort、后续步骤不再执行。
     * **本身无副作用**：不写入、不删除、不启动任何东西。
     */
    fun cancel() {
        cancelGen++
        inFlight?.abort()
        try { confirm.cancel() } catch (_: Exception) {}
    }

    // ==================================================================================
    // 一次执行的上下文（结果 / 审计 / 存活性判断）
    // ==================================================================================

    private inner class Exec(
        val id: String,
        val tool: String,
        val clientName: String,
        val clientId: String,
        val startedAt: Long,
        val deadline: Long,
        val gen: Int,
    ) {
        fun expired(): Boolean = LinkRules.expired(deadline, System.currentTimeMillis())
        fun cancelled(): Boolean = gen != cancelGen

        fun fail(code: String, message: String, confirmState: LinkConfirm.Outcome? = null, detail: String = ""): JSONObject {
            val label = if (confirmState == null) "n/a" else LinkRules.confirmLabel(confirmState)
            audit.record(tool, clientName, code, label, auditDetail(detail))
            return JSONObject()
                .put("id", id)
                .put("ok", false)
                .put("error", JSONObject().put("code", code).put("message", message))
                .put("confirm", label)
        }

        fun ok(result: JSONObject, confirmState: LinkConfirm.Outcome? = null, detail: String = ""): JSONObject {
            val label = if (confirmState == null) "n/a" else LinkRules.confirmLabel(confirmState)
            audit.record(tool, clientName, "ok", label, auditDetail(detail))
            return JSONObject()
                .put("id", id)
                .put("ok", true)
                .put("result", result)
                .put("confirm", label)
        }

        /** 审计细节 = 调用方给的一句短语 + 耗时（审计只留元数据，正文一律不进） */
        private fun auditDetail(extra: String): String {
            val ms = System.currentTimeMillis() - startedAt
            return if (extra.isBlank()) "${ms}ms" else "$extra · ${ms}ms"
        }
    }

    // ==================================================================================
    // T1 memory_search
    // ==================================================================================

    /**
     * 检索长期记忆。**不回写命中计数** —— [AgentMemory.top] 会顺手 uses+1 / lastUsed=now，
     * 外部 Agent 每查一次就把本地排序推一遍，等于让别人的检索量污染用户自己的记忆权重。
     * 所以这里只用 [AgentMemory.score] 算分、排序、截断，一个字节都不写回去。
     */
    private fun memorySearch(ex: Exec, args: JSONObject, client: JSONObject?): JSONObject {
        val query = args.reqStr("query", 1, 200)
        val limit = args.reqInt("limit", 1, 20, 5)
        val pid = personaOf(args.optString("persona", ""))
            ?: return ex.fail(LinkRules.NOT_FOUND, "找不到指定的人格")
        val wanted = args.enumSet("categories", AgentMemory.CATEGORIES, AgentMemory.CATEGORIES)
        // 敏感两类只有持 personal 的客户端才看得到
        val personal = hasScope(client, SCOPE_PERSONAL)
        val mems = AgentMemory.Store(ctx, pid).get()
        val qBi = AgentMemory.bigrams(query)
        val qUni = AgentMemory.unigrams(query)
        val now = System.currentTimeMillis()

        var dropped = 0
        val items = JSONArray()
        mems.asSequence()
            .filter { m ->
                when {
                    m.category !in wanted -> false
                    !personal && m.category in SENSITIVE -> { dropped++; false }
                    else -> true
                }
            }
            .map { it to AgentMemory.score(qBi, qUni, now, it) }
            .filter { it.second >= AgentMemory.MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit)
            .forEach { (m, s) ->
                items.put(
                    JSONObject()
                        .put("content", m.content)
                        .put("category", m.category)
                        .put("importance", m.importance)
                        .put("at", LinkRules.isoLocal(m.at))
                        .put("score", Math.round(s * 1000.0) / 1000.0),
                )
            }
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "检索完成时已过截止时刻")
        return ex.ok(
            JSONObject()
                .put("items", items)
                .put("persona", pid)
                .put("total", mems.size)
                .put("dropped_personal", dropped),
            null,
            "hits=${items.length()} persona=$pid dropped=$dropped",
        )
    }

    // ==================================================================================
    // T2 memory_add
    // ==================================================================================

    /**
     * 写入一条长期记忆。
     *
     * **正文不加任何前缀**：用户在自己的记忆列表和提示词里看到的就是原句。
     * 归属记在手机侧 sidecar（[LinkOrigin]）里 —— 于是 rollback 只能删「外部 Agent 写的、且用户没动过」的条目：
     * 用户后来编辑 / 被新内容合并过，hash 就对不上，**跳过而不是删掉**。
     *
     * 两件事必须同时成立才登记归属（否则 rollback 会删用户数据）：
     *  1. 这条正文是**这次新插入**的 —— 合并进用户已有那条时正文没变，登记就等于把用户原话认成我们的；
     *  2. 它原本没有别的 origin —— 别的客户端先写过同一句时，不把它改成自己的。
     * 合并与登记在同一把锁里（[AgentMemory.Store.mergeAtomically]），读 → 改 → 写不留并发窗口。
     */
    private fun memoryAdd(ex: Exec, args: JSONObject): JSONObject {
        val content = args.reqStr("content", 2, 120)
        val category = args.optString("category", "FACT").uppercase(Locale.US)
        if (category !in WRITABLE) {
            return ex.fail(LinkRules.INVALID_ARGS, "外部客户端只能写 FACT / PREFERENCE / EVENT / HABIT，不能写 $category")
        }
        val impRaw = args.opt("importance")
        val importance: Double
        if (impRaw == null || impRaw == JSONObject.NULL) {
            importance = 0.5
        } else {
            val d = (impRaw as? Number)?.toDouble()
                ?: return ex.fail(LinkRules.INVALID_ARGS, "importance 必须是数字")
            if (d.isNaN() || d < 0.0 || d > 1.0) {
                return ex.fail(LinkRules.INVALID_ARGS, "importance 必须在 0~1 之间")
            }
            importance = d
        }
        val pid = personaOf(args.optString("persona", ""))
            ?: return ex.fail(LinkRules.NOT_FOUND, "找不到指定的人格")
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "写入前已过截止时刻，未写入")
        if (ex.cancelled()) throw Cancelled("写入前连接已断开，未写入")

        val store = AgentMemory.Store(ctx, pid)
        val candidate = AgentMemory.Mem(content, category, importance)
        val now = System.currentTimeMillis()
        val origin = "mcp:${ex.clientId}"
        val book = LinkOrigin(ctx)
        // 合并与归属登记在同一把锁里（[AgentMemory.Store.mergeAtomically]）：读 → 改 → 写中间不留窗口，
        // 归属也不会先登记成功、再被别的写入覆盖成「库里查不到原文」的假记录。
        var merged = false
        var owned = false
        /** 这一笔最终落在哪条正文上：新增 = 提交的那句；合并 = 库里原有的那句（正文没被改） */
        var target = content
        val after = store.mergeAtomically(listOf(candidate), now) { before, next ->
            // 「合并」= 撞上了库里已有的同一件事（正文没变）；与 [AgentMemory.merge] 同一口径。
            val dup = LinkRules.dupIndex(before, content)
            merged = dup != null
            if (dup != null) target = before[dup].content
            // 只有**真正新插入、而且没被库容裁掉**的那条才算客户端写入（[LinkRules.memoryInserted] 已经
            // 包含「合并结果里确实多了这句」）：合并进用户原有条目时正文没变，记归属会让 rollback 删用户原话；
            // 别的客户端先写过同一句时也不抢它的归属。
            owned = !merged && LinkRules.memoryInserted(before, next, target) &&
                LinkRules.mayClaim(book.memories()[LinkRules.hash16(target)]?.origin, origin)
            if (owned) book.putMemory(LinkRules.hash16(target), origin, category, now, pid)
        }
        val kept = after.any { it.content == target }

        return ex.ok(
            JSONObject()
                .put("ok", true)
                .put("merged", merged)
                .put("total", after.size)
                .put("id", LinkRules.hash16(target))
                .put("origin", origin)
                .put("owned", owned)
                .put("dropped", if (kept) 0 else 1),
            null,
            "persona=$pid merged=$merged owned=$owned total=${after.size}",
        )
    }

    // ==================================================================================
    // T6 reminder_create
    // ==================================================================================

    /**
     * 建一条真实待办提醒：写 [Todo.Store] → 排 [TodoReceiver] 的闹钟 → 到点走既有的通知 / 试弹链路。
     *
     * 到点提醒本身用户可撤销：通知上就有「已完成 / 稍后」，条目也进待办列表；
     * 此外 rollback 能删掉**没被用户动过**的那条（[LinkRules.reminderFingerprint] 对得上才删）。
     * `exact=false` 说明系统没给精确闹钟授权（会晚几分钟到几十分钟）—— **如实回给电脑**，不假装准点。
     */
    private fun reminderCreate(ex: Exec, args: JSONObject): JSONObject {
        val text = args.reqStr("text", 1, 120)
        val noteRaw = args.optString("note", "")
        if (noteRaw.length > 200) return ex.fail(LinkRules.INVALID_ARGS, "note 最多 200 字")
        val dueRaw = args.reqStr("due_at", 1, 64)
        val dueAt = LinkRules.parseIso(dueRaw)
            ?: return ex.fail(
                LinkRules.INVALID_ARGS,
                "due_at 必须是带时区的 ISO 8601（例如 2026-10-22T20:00:00+08:00）",
            )
        val now = System.currentTimeMillis()
        if (dueAt < now + 60_000L) return ex.fail(LinkRules.INVALID_ARGS, "due_at 必须晚于现在至少 1 分钟")
        if (dueAt > now + 365L * 86_400_000L) return ex.fail(LinkRules.INVALID_ARGS, "due_at 最远只支持一年内")
        if (!RemindExact.notiGranted(ctx)) {
            return ex.fail(LinkRules.GUARD_BLOCKED, "手机没有通知权限，提醒到点不会响；请允许 Ling 发通知后重试")
        }
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "写入前已过截止时刻，未创建")
        if (ex.cancelled()) throw Cancelled("写入前连接已断开，未创建")

        // 通知正文里要让用户看得见「这条不是我自己建的」（note 是既有的展示位，不新开字段）
        val attribution = "由 ${ex.clientName} 创建"
        val note = if (noteRaw.isBlank()) attribution else LinkRules.clip("$noteRaw · $attribution", 200)
        val store = Todo.Store(ctx)
        val origin = "mcp:${ex.clientId}"
        var todoId = -1L
        var created = false
        var readBack = false
        // 快照 + 写入 + 归属登记在同一把锁里（[FileMemo] 的 monitor；`Todo.Store.add` 内部走
        // [FileMemo.update]，同锁可重入）。
        // `Todo.add` 见到「最新一条的文案 + 时刻完全一样」会原样返回旧表，而 `Store.add` 回的 id 正是
        // **用户自己那条**的 id。所以先自己判一次重复：命中了就**一个字节都不写**，更不登记归属 ——
        // 否则 rollback 会删掉用户的待办（审查缺陷 1）。
        synchronized(FileMemo) {
            val before = store.list()
            // 判重口径与 `Todo.add` 完全一致：只比**最新一条**的文案 + 时刻（多比一处就会误拒真该新建的）
            val dup = before.firstOrNull()?.takeIf { it.text == text && it.dueAt == dueAt }
            if (dup != null) {
                todoId = dup.id
                created = false
            } else {
                todoId = store.add(text, dueAt, note)
                created = LinkRules.todoCreated(before.mapTo(HashSet()) { it.id }, todoId)
                if (created) {
                    val item = store.get(todoId)
                    if (item != null) {
                        readBack = true
                        LinkOrigin(ctx).putTodo(todoId, origin, LinkRules.reminderFingerprint(item), now)
                    }
                }
            }
        }
        if (todoId <= 0L) return ex.fail(LinkRules.INTERNAL, "待办没写进去（可能是库已满）")
        if (!created) {
            // 库里已有文案 + 时刻完全相同的一条（用户自己建的，或同一客户端重试）：不写归属、不排闹钟、
            // 不谎称创建。用户那条待办原样留着，回执里说清楚「没新建、原条目 id 是多少」。
            return ex.fail(LinkRules.INVALID_ARGS, "同一条提醒已存在（id=$todoId），没有重复创建")
        }
        if (!readBack) return ex.fail(LinkRules.INTERNAL, "待办写入后读不回来")
        TodoReceiver.schedule(ctx, todoId)

        val exact = RemindExact.canExact(ctx)
        return ex.ok(
            JSONObject()
                .put("ok", true)
                .put("id", todoId.toString())
                .put("due_at", LinkRules.isoLocal(dueAt))
                .put("exact", exact)
                .put("channel", if (exact) "精确闹钟" else "近似闹钟（未授权精确定时，可能晚几分钟）"),
            null,
            "id=$todoId exact=$exact",
        )
    }

    // ==================================================================================
    // T7 phone_screen_read（L3：本机确认后才截屏）
    // ==================================================================================

    /**
     * 读当前屏幕：**纯转写**（mode=ocr）或直接答题（mode=solve）。只有文字回到电脑。
     *
     * 顺序严格，任何一步不成立就停（fail closed）：
     *  1. 参数 / 环境校验 → 2. 本机确认（20s，默认拒）→ 3. 再查截止与取消 → 4. 截屏
     *  → 5. 调用户自己的视觉模型 → 6. 记 [CostLedger.Feature.MCP] → 7. 只回文字。
     *
     * 「先确认后截图」是硬要求：确认框还没点之前，屏幕上的一切都不该被读走。
     * 确认框自己先摘窗再放行（[LinkConfirm.HIDE_SETTLE_MS]），所以截到的画面里不会有它。
     */
    private fun phoneScreenRead(ex: Exec, args: JSONObject): JSONObject {
        val mode = args.optString("mode", "ocr").lowercase(Locale.US)
        if (mode !in MODES_READ) return ex.fail(LinkRules.INVALID_ARGS, "mode 只能是 ocr 或 solve")
        val purpose = args.reqStr("purpose", 2, 60)

        if (!A11yService.available) {
            return ex.fail(LinkRules.SCREEN_UNAVAILABLE, A11yService.disconnectedMessage())
        }
        if (!Settings.canDrawOverlays(ctx)) {
            return ex.fail(LinkRules.NO_OVERLAY_PERMISSION, "手机没有悬浮窗权限，无法在本机弹出确认框，读屏已拒绝")
        }
        val cfg = Prefs(ctx).api()
        if (cfg.baseUrl.isBlank()) return ex.fail(LinkRules.NO_API, "手机还没有配置识别模型接口，读屏转写做不了")
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "指令已过截止时刻，未确认也未截屏")

        // ---- 1. 本机确认（先问人，后动屏幕）----
        val outcome = confirm.ask(
            LinkConfirm.Request(
                title = if (mode == "solve") "读取屏幕并答题" else "读取手机屏幕文字",
                client = ex.clientName,
                rows = listOf(
                    LinkConfirm.Row("用途", purpose),
                    LinkConfirm.Row("方式", if (mode == "solve") "截屏 → 识别模型答题" else "截屏 → 识别模型转写文字"),
                    LinkConfirm.Row("回传给电脑的内容", "只有文字，不传截图"),
                ),
                privacy = "确认后 Ling 会截取当前屏幕并发送给你在本机配置的识别模型处理；" +
                    "截图留在手机上，电脑和外部 Agent 只会收到文字结果。",
            ),
        )
        when (outcome) {
            LinkConfirm.Outcome.DENIED -> return ex.fail(LinkRules.DENIED, "本机拒绝了这次读屏", outcome)
            LinkConfirm.Outcome.TIMEOUT ->
                return ex.fail(LinkRules.TIMEOUT, "本机确认超时（20 秒内没人点允许），未截屏", outcome)
            LinkConfirm.Outcome.UNAVAILABLE -> return ex.fail(
                LinkRules.NO_OVERLAY_PERMISSION, "确认框弹不出来（悬浮窗权限被收回或建窗失败），未截屏", outcome,
            )
            LinkConfirm.Outcome.APPROVED -> Unit
        }

        // ---- 2. 副作用前再查一次截止 / 取消 ----
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "确认回来时已过截止时刻，未截屏", outcome)
        if (ex.cancelled()) throw Cancelled("确认期间连接断开，未截屏")

        // ---- 3. 截屏（截图只留在内存里，不出手机）----
        val b64 = captureJpeg() ?: return ex.fail(LinkRules.SCREEN_UNAVAILABLE, "截屏失败：$lastScreenError", outcome)
        if (ex.cancelled()) throw Cancelled("截屏后连接断开，未调用模型")

        // ---- 4. 真实模型调用 ----
        val token = Cancel()
        inFlight = token
        try {
            val run = if (mode == "solve") runSolve(cfg, b64, token) else runOcr(cfg, b64, token)
            if (token.get()) return ex.fail(LinkRules.CANCELLED, "连接断开，读屏已取消", outcome)
            val text = run.text.trim()
            if (text.isEmpty()) return ex.fail(LinkRules.INTERNAL, "模型没有返回任何文字", outcome)

            // ---- 5. 台账：优先用接口上报的真实 usage，接口不给才按长度估 ----
            val model = cfg.model
            val real = run.usage
            val pin = real?.first?.toLong() ?: CostLedger.estimatePin(OCR_PROMPT.length, 1)
            val pout = real?.second?.toLong() ?: CostLedger.estimatePout(text.length)
            val date = Prefs(ctx).todayInt().toString()
            CostLedger.Store(ctx).record(date, date.substring(0, 6), model, CostLedger.Feature.MCP, pin, pout)
            Prefs(ctx).noteUsage(pin.toInt(), pout.toInt(), text.length)
            val micro = CostLedger.priceOf(model).costMicro(pin, pout)
            val wrapped = JSONObject().put("untrusted", true).put("source", "phone_screen").put("value", text)
            val result = JSONObject()
                .put("text", wrapped)
                .put("chars", text.length)
                .put("cost_micro", micro)
                .put("model", model)
            if (mode == "solve") result.put("answer", wrapped)
            return ex.ok(
                result,
                outcome,
                "mode=$mode chars=${text.length} usage=${if (real != null) "real" else "estimated"}",
            )
        } finally {
            inFlight = null
        }
    }

    /**
     * 截一张 JPEG base64（短边 ≤ 1080）。null = 失败，原因在 [lastScreenError]。
     *
     * 优先「只截目标 App 窗口」（API 34+，浮窗天然不在画面里）；不支持时退回整屏截图，
     * 先把 Ling 自己的浮层让开、截完立刻恢复。无障碍没连上时**不假装成功**。
     */
    private fun captureJpeg(): String? {
        val src = A11ySource { r -> main.post(r) }
        if (!src.ready) {
            lastScreenError = A11yService.disconnectedMessage()
            return null
        }
        // ① 窗口截图（不必藏浮窗）
        val viaWindow = awaitString(8_000L) { cb -> src.captureForScan(cb) }
        if (viaWindow.started) {
            val v = viaWindow.value
            if (v != null) return v
            lastScreenError = src.lastError ?: "窗口截图失败"
            // 窗截失败不硬退到整屏：此刻浮窗还开着，退回去会把它截进画面
            return null
        }
        // ② 整屏：先让开 Ling 自己的浮层，再截
        OrbService.suppressForForeground(ctx)
        try {
            Thread.sleep(OVERLAY_HIDE_WAIT_MS)
            for (attempt in 0..1) {
                val r = awaitString(10_000L) { cb -> src.capture(cb); true }
                val v = r.value
                if (v != null) return v
                lastScreenError = src.lastError ?: "截屏失败"
                // 码 3 = 两次截图隔得太近；等一秒再来一次，其余失败直接放弃
                if (src.lastCode != 3 || attempt == 1) break
                Thread.sleep(1_200L)
            }
            return null
        } finally {
            OrbService.restoreAfterForeground(ctx)
        }
    }

    // ==================================================================================
    // T8 phone_open_app
    // ==================================================================================

    /**
     * 打开一个 App。参数是**别名**，永远不接受包名。
     *
     * 三重门槛：别名必须存在 → 用户必须在手机设置里打开过它（[LinkPairStore.allowedApps]）→ 本机确认 20 秒。
     * 任何一步不满足都不启动。启动失败（系统拒绝后台启动 / 包被卸了）**如实报错**，绝不回 `ok:true`。
     */
    private fun phoneOpenApp(ex: Exec, args: JSONObject): JSONObject {
        val alias = args.reqStr("app", 1, 32).lowercase(Locale.US)
        if (!ALIAS_RE.matches(alias)) return ex.fail(LinkRules.INVALID_ARGS, "app 只能是白名单别名（a-z0-9_）")
        val reason = args.reqStr("reason", 2, 60)
        val target = appList().firstOrNull { it.alias == alias }
        if (target == null) {
            // 名字合法但本机没有：用户之前允许过 → 是被卸载 / 不可启动了（真话），否则就是没这个别名
            return if (pairs.isAppAllowed(alias)) {
                ex.fail(LinkRules.APP_NOT_AVAILABLE, "「$alias」对应的 App 已经不在这台手机上了（被卸载或不可启动）")
            } else {
                ex.fail(LinkRules.APP_NOT_ALLOWED, "没有这个别名：$alias")
            }
        }
        if (!pairs.isAppAllowed(alias)) {
            return ex.fail(LinkRules.APP_NOT_ALLOWED, "用户没有在这台手机上允许打开「${target.name}」")
        }
        // appList() 只收「启动入口真能拿到」的（含内置两个）—— 走到这里说明本机能开
        if (!Settings.canDrawOverlays(ctx)) {
            return ex.fail(
                LinkRules.NO_OVERLAY_PERMISSION,
                "手机没有悬浮窗权限：既弹不出确认框，Android 10+ 也不允许后台启动界面，未打开",
            )
        }
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "指令已过截止时刻，未确认也未打开")

        val outcome = confirm.ask(
            LinkConfirm.Request(
                title = "在这台手机上打开应用",
                client = ex.clientName,
                rows = listOf(
                    LinkConfirm.Row("应用", "${target.name}（$alias）"),
                    LinkConfirm.Row("理由", reason),
                    LinkConfirm.Row("动作", "切换到该应用前台"),
                ),
                privacy = "确认后 Ling 会把这个应用切到前台；不会读取该应用里的任何内容。",
            ),
        )
        when (outcome) {
            LinkConfirm.Outcome.DENIED -> return ex.fail(LinkRules.DENIED, "本机拒绝了这次打开", outcome)
            LinkConfirm.Outcome.TIMEOUT ->
                return ex.fail(LinkRules.TIMEOUT, "本机确认超时（20 秒内没人点允许），未打开", outcome)
            LinkConfirm.Outcome.UNAVAILABLE -> return ex.fail(
                LinkRules.NO_OVERLAY_PERMISSION, "确认框弹不出来（悬浮窗权限被收回或建窗失败），未打开", outcome,
            )
            LinkConfirm.Outcome.APPROVED -> Unit
        }
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "确认回来时已过截止时刻，未打开", outcome)
        if (ex.cancelled()) throw Cancelled("确认期间连接断开，未打开")

        val intent = target.newIntent()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            ex.ok(JSONObject().put("ok", true).put("opened", alias).put("name", target.name), outcome, "alias=$alias pkg=${target.pkg}")
        } catch (e: Exception) {
            // 后台启动被系统拦 / 目标 Activity 被禁用：都是真失败，不冒充成功
            ex.fail(LinkRules.LAUNCH_FAILED, "启动「${target.name}」失败：${brief(e)}", outcome)
        }
    }

    // ==================================================================================
    // profile（只读，给资源 ling://profile 用）
    // ==================================================================================

    private fun profile(ex: Exec, client: JSONObject?): JSONObject {
        val pid = personaOf("") ?: return ex.fail(LinkRules.NOT_FOUND, "找不到当前人格")
        val card = MemoryCard.Store(ctx, pid).get()
        val personal = hasScope(client, SCOPE_PERSONAL)
        val mem = JSONArray()
        var dropped = 0
        AgentMemory.Store(ctx, pid).get()
            .asSequence()
            .filter { m ->
                when {
                    !personal && m.category in SENSITIVE -> { dropped++; false }
                    else -> true
                }
            }
            .sortedByDescending { it.importance }
            .take(PROFILE_MEMORY_LIMIT)
            .forEach { m ->
                mem.put(
                    JSONObject()
                        .put("content", m.content)
                        .put("category", m.category)
                        .put("importance", m.importance),
                )
            }
        val topics = JSONArray()
        card.topics.sortedByDescending { it.at }.take(MemoryCard.MAX_TOPICS).forEach { topics.put(it.text) }
        val out = JSONObject()
            .put("persona", pid)
            .put("memory", mem)
            .put("topics", topics)
            .put("dropped_personal", dropped)
        if (card.name.isNotBlank()) out.put("preferred_name", card.name)
        if (card.goal.isNotBlank()) out.put("goal", card.goal)
        return ex.ok(out, null, "persona=$pid mem=${mem.length()} dropped=$dropped")
    }

    // ==================================================================================
    // rollback（按 origin 撤销外部 Agent 的写入）
    // ==================================================================================

    /**
     * 撤销某个客户端经 MCP 写进来的东西。**只删还能证明「是我们写的、而且用户没动过」的条目**：
     *  - 记忆：content 的 hash 还在 sidecar、且库里确实还有这段原文 → 删；用户后来编辑 / 被新内容合并掉
     *    → hash 对不上 → 计入 `skipped_edited`，**留着**。
     *  - 提醒：sidecar 里记了创建时的指纹（text + dueAt + note）→ 现在一模一样才删；用户改过时间 / 文案 /
     *    已完成 → 跳过。用户的任何后续动作都优先于「外部 Agent 想撤销」。
     *
     * 每个副作用（写完一份记忆文件、删一条待办、清一条归属记录）**之前**都复检截止时刻与取消：
     * 电脑那边 15s 就判超时了，撤销不该在它放弃之后还在删数据。已经提交的删除无法撤回，
     * 所以中途停下时如实回报「已删几条」，不谎称「全部回滚」。
     */
    private fun rollback(ex: Exec, args: JSONObject): JSONObject {
        val origin = args.reqStr("origin", 4, 48)
        if (!ORIGIN_RE.matches(origin)) return ex.fail(LinkRules.INVALID_ARGS, "origin 必须形如 mcp:<clientId>")
        if (ex.expired()) return ex.fail(LinkRules.TIMEOUT, "撤销前已过截止时刻，未做任何改动")
        if (ex.cancelled()) throw Cancelled("撤销前连接已断开，未做任何改动")

        val book = LinkOrigin(ctx)
        var removedMem = 0
        var skippedMem = 0
        var removedTodo = 0
        var skippedTodo = 0
        /** 中途停下时的真实状态：已提交的删除收不回来，只能如实说。 */
        fun partial(): String =
            if (removedMem + removedTodo == 0) "未做任何改动"
            else "已提交的删除无法撤回（记忆 $removedMem 条、待办 $removedTodo 条）"
        /** 副作用前复检：过期抛 [Expired]（TIMEOUT），取消抛 [Cancelled]，两者都不做这一步。 */
        fun guard(what: String) {
            val block = LinkRules.sideEffectBlock(ex.expired(), ex.cancelled()) ?: return
            if (block == LinkRules.TIMEOUT) throw Expired("撤销到「$what」前已过截止时刻，" + partial())
            throw Cancelled("撤销到「$what」前连接已断开，" + partial())
        }

        val owned = book.memories().filterValues { it.origin == origin }
        if (owned.isNotEmpty()) {
            // 按记录里的人格分组：只打开这个 origin 写过东西的那几个人格的记忆文件
            val byPersona = owned.entries.groupBy { (_, rec) ->
                rec.persona.takeIf { it.isNotBlank() } ?: personaOf("") ?: Personas.P0
            }
            for ((pid, entries) in byPersona) {
                val hashes = entries.map { it.key }.toSet()
                val store = AgentMemory.Store(ctx, pid)
                guard("记忆 $pid")
                // 同一个 hash 只删**一条**：归属台账只能证明「我们写过这样一句话」，
                // 证明不了库里那两条里哪条是我们的 —— 重复的那条留着，绝不把用户的一份一起删。
                // 删和判在同一把锁里（[AgentMemory.Store.removeOwned]）：不会把中间别人写进来的记忆抹掉，
                // 也不会在「算完 hash 之后、写回之前」被用户编辑骗过去。
                val seen = HashSet<String>()
                val removedHere = store.removeOwned { m ->
                    val h = LinkRules.hash16(m.content)
                    h in hashes && seen.add(h)
                }
                removedMem += removedHere
                // 记过、但库里已经没有对应原文的 → 用户改过 / 已被合并掉，不碰
                skippedMem += hashes.size - removedHere
                guard("清除 $pid 的归属记录")
                book.dropMemories(hashes)
            }
        }

        val todoOwned = book.todos().filterValues { it.origin == origin }
        for ((id, rec) in todoOwned) {
            guard("待办 $id")
            val store = Todo.Store(ctx)
            // 判指纹 → 删，同一把锁里做完：用户刚改过的那条不会被当成「我们原来写的那条」删掉
            val removed = synchronized(FileMemo) {
                val item = store.get(id)
                if (item == null) {
                    false                   // 用户已经删了：不算我们删的
                } else if (item.done || LinkRules.reminderFingerprint(item) != rec.fp) {
                    skippedTodo++           // 用户动过：留着
                    false
                } else {
                    store.remove(id)
                    true
                }
            }
            if (removed) removedTodo++
            guard("清除待办 $id 的归属记录")
            book.dropTodo(id)
        }

        return ex.ok(
            JSONObject()
                .put("ok", true)
                .put("removed", removedMem + removedTodo)
                .put("skipped_edited", skippedMem + skippedTodo)
                .put("todos_removed", removedTodo)
                .put("total", removedMem + skippedMem + removedTodo + skippedTodo),
            null,
            "mem=$removedMem skipped=$skippedMem todo=$removedTodo",
        )
    }

    // ==================================================================================
    // 视觉调用（读屏）
    // ==================================================================================

    private class Run(val text: String, val usage: Pair<Int, Int>?)

    private fun runOcr(cfg: ApiConfig, jpegB64: String, token: Cancel): Run {
        val usage = AtomicReference<Pair<Int, Int>?>(null)
        val body = Bodies.vision(cfg, OCR_PROMPT, listOf(jpegB64), OCR_BUDGET, thinkingOff = true)
        val text = ApiClient(cfg).stream(
            body,
            object : ApiClient.Listener {
                override fun onText(text: String) {}
                override fun onUsage(prompt: Int, completion: Int) { usage.set(prompt to completion) }
            },
            token,
            readTimeoutMs = VISION_TIMEOUT_MS,
        ).first
        return Run(text, usage.get())
    }

    /** mode=solve：复用手机自己的识屏问答（同一套提示词 / 预算），行为与用户双击小球一致 */
    private fun runSolve(cfg: ApiConfig, jpegB64: String, token: Cancel): Run {
        val usage = AtomicReference<Pair<Int, Int>?>(null)
        val text = Solver(cfg).solve(
            jpegB64,
            Prefs(ctx).solveOptions(),
            object : Solver.Callbacks {
                override fun onPhase(t: String) {}
                override fun onText(t: String) {}
                override fun onUsage(prompt: Int, completion: Int) { usage.set(prompt to completion) }
            },
            token,
        )
        return Run(text, usage.get())
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    private class Waited(val started: Boolean, val value: String?)

    /**
     * 等一个回调结果（只认第一次）。[start] 返回 false = **没开始**（例如窗口截图不支持），
     * 此时 [Waited.started] 为 false，调用方必须走别的路径 —— 不能把它当「截到一张空图」。
     */
    private fun awaitString(timeoutMs: Long, start: (cb: (String?) -> Unit) -> Boolean): Waited {
        val once = AtomicBoolean(false)
        val ref = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val started = start { v ->
            if (once.compareAndSet(false, true)) {
                ref.set(v)
                done.countDown()
            }
        }
        if (!started) return Waited(false, null)
        val got = try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return Waited(true, if (got) ref.get() else null)
    }

    private fun appLabel(): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${ctx.packageName}/${pi.versionName ?: "?"}"
    } catch (_: Exception) {
        ctx.packageName
    }

    private fun appsWithState(list: List<AppEntry>): JSONArray {
        val on = pairs.allowedApps()
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("alias", e.alias)
                    .put("name", e.name)
                    .put("on", e.alias in on),
            )
        }
        return arr
    }

    /**
     * 本机可打开的 App 清单（别名 → 启动方式）。内置两个应用内页面排在最前，其余按应用名排序。
     * 别名一旦对应上包名就落盘固定（[LinkAliasBook]），免得上一次用户勾的开关这次指到别的 App。
     */
    private fun appList(): List<AppEntry> {
        val out = ArrayList<AppEntry>(24)
        out.add(AppEntry("ling_review", "Ling · 复习", ctx.packageName, Intent(ctx, ReviewActivity::class.java)))
        out.add(AppEntry("ling_words", "Ling · 学习与复习", ctx.packageName, Intent(ctx, StudyActivity::class.java)))
        val launchable = launchablePackages()
        // 日历：只在系统里真能找到可启动的日历包时才出现
        CALENDAR_PKGS.firstOrNull { it in launchable }?.let { pkg ->
            launchIntent(pkg)?.let { out.add(AppEntry("calendar", launchable.getValue(pkg).label, pkg, it)) }
        }
        val builtinPkgs = HashSet<String>(CALENDAR_PKGS).apply { add(ctx.packageName) }
        val used = HashSet<String>().apply { add("ling_review"); add("ling_words"); add("calendar") }
        for ((pkg, info) in launchable.entries.sortedBy { it.value.label }) {
            if (pkg in builtinPkgs) continue
            // 列出来就一定要能开：启动入口拿不到的不进名单（否则设置页会出现一个点了没反应的开关）
            val intent = launchIntent(pkg) ?: continue
            val alias = aliases.aliasFor(pkg, used)
            used.add(alias)
            out.add(AppEntry(alias, info.label, pkg, intent))
        }
        return out
    }

    private class AppEntry(val alias: String, val name: String, val pkg: String, private val intent: Intent) {
        fun newIntent(): Intent = Intent(intent)
    }

    private fun launchIntent(pkg: String): Intent? = try {
        ctx.packageManager.getLaunchIntentForPackage(pkg)
    } catch (_: Exception) {
        null
    }

    private class Info(val label: String)

    /** 系统里所有有启动入口的包（要 Manifest 里的 `<queries>`，否则只能看到自己） */
    private fun launchablePackages(): Map<String, Info> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val out = LinkedHashMap<String, Info>()
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val label = try {
                ri.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } ?: pkg.substringAfterLast('.')
            // 同一个包有多个入口时保留第一个（顺序由系统给，稳定）
            if (!out.containsKey(pkg)) out[pkg] = Info(label)
        }
        return out
    }

    // ---------------- 参数 / scope ----------------

    private fun hasScope(client: JSONObject?, scope: String): Boolean {
        val arr = client?.optJSONArray("scopes") ?: return false
        for (i in 0 until arr.length()) if (arr.optString(i) == scope) return true
        return false
    }

    /**
     * 人格 id：空 = 当前人格。给了就必须真存在 —— 不默默退回当前人格
     * （否则「查 A 人格」会变成「查到 B 人格的私事」）。
     */
    private fun personaOf(raw: String): String? {
        val store = Personas.Store(ctx)
        if (raw.isBlank()) return store.current()
        val clean = raw.filter { it.isLetterOrDigit() }.take(24)
        if (clean.isBlank()) return null
        return if (store.list().any { it.id == clean }) clean else null
    }

    private fun brief(e: Throwable): String =
        (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(200)

    /** 参数不合法（内部异常，execute 统一转成 INVALID_ARGS） */
    private class BadArgs(message: String) : Exception(message)

    /**
     * 连接断开 / 取消（内部异常，execute 统一转成 CANCELLED）。
     * [message] 里带上「已删几条」：撤销中途停下时，已提交的删除收不回来，必须如实说。
     */
    private class Cancelled(message: String) : Exception(message)

    /** 中途发现过了截止时刻（内部异常，execute 统一转成 TIMEOUT）—— 与 [Cancelled] 同一个用意 */
    private class Expired(message: String) : Exception(message)

    /** 严格取字符串：类型不对 / 空 / 超长都抛 [BadArgs]，绝不静默兜底 */
    private fun JSONObject.reqStr(key: String, min: Int, max: Int): String {
        val v = opt(key)
        if (v == null || v == JSONObject.NULL) throw BadArgs("缺少参数 $key")
        val s = (v as? String) ?: throw BadArgs("$key 必须是字符串")
        val t = s.trim()
        if (t.length < min) throw BadArgs("$key 至少要 $min 个字符")
        if (t.length > max) throw BadArgs("$key 最多 $max 个字符")
        return t
    }

    private fun JSONObject.reqInt(key: String, min: Int, max: Int, default: Int): Int {
        val v = opt(key)
        if (v == null || v == JSONObject.NULL) return default
        val n = (v as? Number)?.toInt() ?: throw BadArgs("$key 必须是整数")
        if (n < min || n > max) throw BadArgs("$key 必须在 $min~$max 之间")
        return n
    }

    /**
     * 枚举数组：给了就按给定集合校验，没给用 [default]。
     * **空数组不当作「全都要」**（那样「只看 FACT」会被读成「什么都能看」）。
     */
    private fun JSONObject.enumSet(key: String, allowed: Set<String>, default: Set<String>): Set<String> {
        val v = opt(key) ?: return default
        if (v == JSONObject.NULL) return default
        val arr = (v as? JSONArray) ?: throw BadArgs("$key 必须是数组")
        if (arr.length() == 0) throw BadArgs("$key 不能是空数组")
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val s = (arr.opt(i) as? String)?.uppercase(Locale.US)
                ?: throw BadArgs("$key 里的项必须是字符串")
            if (s !in allowed) throw BadArgs("$key 不支持 $s")
            out.add(s)
        }
        return out
    }

    // ==================================================================================
    // 常量
    // ==================================================================================

    companion object {
        const val SCOPE_READ = "read"
        const val SCOPE_WRITE = "write"
        const val SCOPE_ACT = "act"
        const val SCOPE_PERSONAL = "personal"

        /** 敏感两类：只有持 personal 的客户端能看到 */
        val SENSITIVE = setOf("EMOTION", "RELATIONSHIP")

        /** 外部客户端能写的四类（情绪 / 关系是本人私事，不让外部 Agent 写） */
        val WRITABLE = setOf("FACT", "PREFERENCE", "EVENT", "HABIT")

        /** 读屏的两种模式 */
        val MODES_READ = setOf("ocr", "solve")

        /** 应用内页面的别名（不依赖系统里别的 App；用来判断 open_app 值不值得报给电脑） */
        val INTERNAL_ALIASES = setOf("ling_review", "ling_words")

        /** 工具 → 需要的 scope。**这里就是权限表**，加工具必须同步加一行 */
        val TOOLS: Map<String, String> = mapOf(
            "memory_search" to SCOPE_READ,
            "profile" to SCOPE_READ,
            "memory_add" to SCOPE_WRITE,
            "rollback" to SCOPE_WRITE,
            "reminder_create" to SCOPE_WRITE,
            "phone_screen_read" to SCOPE_ACT,
            "phone_open_app" to SCOPE_ACT,
        )

        /** 常见日历包（各厂商不一样；按顺序取第一个装了的） */
        val CALENDAR_PKGS = listOf(
            "com.google.android.calendar",
            "com.android.calendar",
            "com.xiaomi.calendar",
            "com.coloros.calendar",
            "com.oppo.calendar",
            "com.huawei.calendar",
            "com.samsung.android.calendar",
            "com.vivo.calendar",
            "com.meizu.calendar",
        )

        /** 别名只允许这个形状（工具参数里出现的就是它，绝不出现包名） */
        val ALIAS_RE = Regex("^[a-z0-9_]{1,32}$")

        /** rollback 的 origin 形状 */
        val ORIGIN_RE = Regex("^mcp:[A-Za-z0-9_.-]{1,44}$")

        /** profile 里最多回几条记忆（资源是给提示词用的，不是全量导出） */
        const val PROFILE_MEMORY_LIMIT = 20

        /** 整屏截图前等浮层让开的时间（与 OrbService 速记那条路同量级） */
        const val OVERLAY_HIDE_WAIT_MS = 120L

        /**
         * 纯转写提示词。
         *
         * 为什么不用识屏那套「解题」提示词：读屏要的是**画面上的字**，任何解释 / 总结 / 作答都会污染结果，
         * 让电脑拿到一段话而不是「那里写着什么」。所以约束只有：按阅读顺序、原样、看不懂就说看不懂。
         */
        val OCR_PROMPT: String = listOf(
            "你是屏幕文字转写器。把这张截图里所有可见的文字按屏幕上的阅读顺序原样转写出来。",
            "",
            "规则：",
            "1. 只转写，不解释、不总结、不回答、不补充；不要写「图片中是」这类开头。",
            "2. 保留原有的换行与分段：标题、正文、按钮、列表项、数字、公式都照原样输出。",
            "3. 表格用「|」分隔单元格；数字、单位、标点原样保留，不四舍五入、不改写。",
            "4. 看不清的字用「[看不清]」占位，不要猜。",
            "5. 截图里没有文字就只输出「[没有文字]」。",
        ).joinToString("\n")

        /** 转写的输出预算：整屏中文正文 + 列表，3000 足够；给太小会把后半屏截掉 */
        const val OCR_BUDGET = 3000

        /** 视觉调用读超时：截图 → 推理 → 正文，卡住就断，别让一条指令挂在 90 秒上 */
        const val VISION_TIMEOUT_MS = 120_000
    }
}

/**
 * 指令的纯规则（不碰 Android，JVM 单测直接跑）：
 * 错误码、截止时刻口径、ISO 8601 解析 / 格式化、指纹、去重判定。
 */
internal object LinkRules {

    // ---- 错误码：与桌面工具层同一套（旧方案 §4.1）----
    const val INVALID_ARGS = "INVALID_ARGS"
    const val DENIED = "DENIED"
    const val TIMEOUT = "TIMEOUT"
    const val NOT_FOUND = "NOT_FOUND"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val GUARD_BLOCKED = "GUARD_BLOCKED"
    const val INTERNAL = "INTERNAL"
    // ---- 手机侧的真实原因（桌面原样展示，别都塞进 INTERNAL）----
    /** 客户端没有这个工具要求的 scope */
    const val SCOPE_DENIED = "SCOPE_DENIED"
    /** 连接中途断开 / 用户取消 */
    const val CANCELLED = "CANCELLED"
    /** 手机不认识这个工具 */
    const val NOT_SUPPORTED = "NOT_SUPPORTED"
    /** 截不了屏（无障碍没连 / 系统拒绝 / 安全窗口） */
    const val SCREEN_UNAVAILABLE = "SCREEN_UNAVAILABLE"
    /** 没有悬浮窗权限（弹不出确认框 / 后台启动会被拦） */
    const val NO_OVERLAY_PERMISSION = "NO_OVERLAY_PERMISSION"
    /** 手机没配置识别模型接口 */
    const val NO_API = "NO_API"
    /** 别名不在白名单 / 不存在 */
    const val APP_NOT_ALLOWED = "APP_NOT_ALLOWED"
    /** 别名对应的 App 不在本机 */
    const val APP_NOT_AVAILABLE = "APP_NOT_AVAILABLE"
    /** 启动失败（系统拦了 / Activity 被禁用） */
    const val LAUNCH_FAILED = "LAUNCH_FAILED"

    /** 截止时刻的远期上限：超过一个月一定是错的时间源，夹住比信它安全 */
    private const val MAX_HORIZON_MS = 86_400_000L * 30
    /** 低于它当「剩余毫秒」，高于当「绝对时刻」。1e11 ms ≈ 1973 年，真实 epoch 都在右边 */
    private const val ABS_THRESHOLD = 100_000_000_000L

    /**
     * 解析指令的 `deadline`：绝对 epoch 毫秒和「还剩多少毫秒」两种都支持。
     *
     * 为什么必须区分：猜错方向的两个后果不对称 —— 把绝对时刻当剩余毫秒，截止会被推到几十年后（等于没有截止）；
     * 把剩余毫秒当绝对时刻，会立刻判过期（安全侧）。所以按数量级区分，并把离谱的远未来夹到 [MAX_HORIZON_MS]。
     *
     * @return 绝对截止毫秒；0 = 没有截止时刻
     */
    fun deadlineAt(raw: Any?, now: Long): Long {
        val n = when (raw) {
            null, JSONObject.NULL -> return 0L
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull() ?: return 0L
            else -> return 0L
        }
        if (n <= 0L) return 0L
        val abs = if (n >= ABS_THRESHOLD) n else now + n
        return if (abs - now > MAX_HORIZON_MS) now + MAX_HORIZON_MS else abs
    }

    /** 过期判定：[deadline] 0 = 没有截止时刻 = 永不过期 */
    fun expired(deadline: Long, now: Long): Boolean = deadline > 0L && now >= deadline

    /** 本机确认的结论 → 协议里的 confirm 三值。**弹不出框 = 没得到同意 → denied** */
    fun confirmLabel(o: LinkConfirm.Outcome?): String = when (o) {
        LinkConfirm.Outcome.APPROVED -> "approved"
        LinkConfirm.Outcome.DENIED -> "denied"
        LinkConfirm.Outcome.TIMEOUT -> "timeout"
        LinkConfirm.Outcome.UNAVAILABLE -> "denied"
        null -> "n/a"
    }

    /** 只带偏移的 ISO 8601（`...Z` 与 `...±HH:MM` 两种写法都收） */
    private val OFFSET_TAIL = Regex("[+-]\\d{2}:\\d{2}$")
    private val ISO_PATTERNS = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )

    /**
     * 解析带时区的 ISO 8601。**不带时区的一律拒** —— 那串时间在不同时区差 8 小时，
     * 猜错就是把提醒排到半夜。
     */
    fun parseIso(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val hasOffset = t.endsWith("Z") || t.endsWith("z") || OFFSET_TAIL.containsMatchIn(t)
        if (!hasOffset) return null
        for (p in ISO_PATTERNS) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = false
                f.timeZone = TimeZone.getTimeZone("UTC")
                return f.parse(t)?.time
            } catch (_: Exception) {
                // 换下一个格式
            }
        }
        return null
    }

    /** 绝对毫秒 → 带**本机时区偏移**的 ISO 8601（回给电脑的字符串与手机上的闹钟同一口径） */
    fun isoLocal(ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        if (ms <= 0L) return ""
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = tz
        return f.format(java.util.Date(ms))
    }

    /** 内容指纹（前 16 位十六进制）：记忆归属 / 提醒「有没有被用户改过」都用它 */
    fun hash16(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append(String.format(Locale.US, "%02x", d[i]))
        return sb.toString()
    }

    /** 提醒指纹：文案 + 提醒时刻 + 备注都算进去 —— 用户动过任何一项就不再等同「我们当初写的那条」 */
    fun reminderFingerprint(t: Todo.Item): String = hash16("${t.text}\u0000${t.dueAt}\u0000${t.note}")

    /**
     * 库里是否已有「同一件事」。口径与 [AgentMemory.merge] 完全一致（同 [AgentMemory.DUP_SIM]），
     * 只是提前算一次，好让调用方知道这一笔是新增还是合并。
     */
    fun dupIndex(existing: List<AgentMemory.Mem>, content: String): Int? {
        val cb = AgentMemory.bigrams(content)
        var best: Int? = null
        var bestSim = 0.0
        for (i in existing.indices) {
            val sim = AgentMemory.similarity(cb, AgentMemory.bigrams(existing[i].content))
            if (sim >= AgentMemory.DUP_SIM && sim > bestSim) {
                best = i
                bestSim = sim
            }
        }
        return best
    }

    /**
     * 这次 merge 是否**真的新增**了一条正文为 [content] 的记忆。
     *
     * 归属只能记在「库里本来没有、这次才进去」的条目上：合并进用户已有那条时正文没变，
     * 记成客户端写入会让 rollback 删掉用户自己的原话（短句相似度很容易过 [AgentMemory.DUP_SIM]）。
     * 按条数比较而不是「存在与否」：同一句被合并时条数不变，才不会误判成新增。
     */
    fun memoryInserted(before: List<AgentMemory.Mem>, after: List<AgentMemory.Mem>, content: String): Boolean =
        after.count { it.content == content } > before.count { it.content == content }

    /**
     * 这条记忆的归属能不能记到 [origin] 名下：没记过、或本来就是它写的才记。
     * 别的客户端先写过同一句话时**不覆盖**它的归属 —— 覆盖等于把别人的写入认成自己的，rollback 会删错。
     */
    fun mayClaim(existingOrigin: String?, origin: String): Boolean =
        existingOrigin == null || existingOrigin == origin

    /**
     * [Todo.Store.add] 之后这个 id 是不是**这次新分配的**。
     *
     * 必须判：`Todo.add` 见到「最新一条的文案 + 时刻完全一样」就原样返回旧表，而 `Store.add` 回的 id 是
     * `next.firstOrNull()?.id` —— 也就是**用户自己那条**的 id。不判就会把它登记成客户端写入，
     * 之后 rollback 删掉用户的待办。新条目的 id 是创建时刻（撞了就 max+1），所以「快照里没有」= 这次新建。
     */
    fun todoCreated(beforeIds: Set<Long>, id: Long): Boolean = id > 0L && id !in beforeIds

    /**
     * 副作用（含撤销里的每一次删除）开始前该判什么。返回错误码 = 立刻停、什么都不做；null = 可以继续。
     *
     * **过期优先于取消**：两者都不能有副作用，但「电脑已经判超时」是调用方最需要知道的那一条。
     * 撤销里逐个副作用前都过一遍这个判定 —— 已提交的删除收不回来，宁可不删。
     */
    fun sideEffectBlock(expired: Boolean, cancelled: Boolean): String? = when {
        expired -> TIMEOUT
        cancelled -> CANCELLED
        else -> null
    }

    /** 截断（超长截掉尾巴加省略号） */
    fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"
}

/**
 * 别名 ↔ 包名的落盘映射（`filesDir/link-apps.json`）。
 *
 * 为什么必须落盘：别名是给用户和工具看的稳定名字。如果每次现算（取包名末段 + 顺序去重），
 * 装 / 卸一个 App 就可能让某个已勾选的别名指到另一个包 —— 用户答应打开「日历」，实际打开了别的。
 * 一旦「包 → 别名」写下来就永远不改；包真没了只是它不出现，回来还是同一个别名。
 */
internal class LinkAliasBook(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE)

    /** 已分配的「包 → 别名」（[FileMemo] 保证进程内只有一份） */
    fun load(): Book = FileMemo.get(file) { parse(it) }

    /**
     * 给一个包取别名：已有映射直接返回；没有就按包名末段生成，撞了加 `_2`、`_3`…
     * [used] 是本次已经占掉的别名（含内置三个）—— 只在这一次调用里生效，**不会**被写回台账。
     *
     * **两个方向都要占住**：以前只按 `used`（= 这次装机列表里已出现的别名）去重，于是包 A 分到 `notes` 后
     * 卸载，新装的包 B 就能拿到同一个 `notes`，`alias2pkg["notes"]` 被改成 B；A 重装回来，`pkg2alias[A]`
     * 仍是 `notes` —— 两个包同别名，用户在设置页勾的「允许打开 X」指向另一个 App。
     * 所以候选名要避开台账里**历史上分配过的所有别名**（`pkg2alias` 的值与 `alias2pkg` 的键都算），
     * 且分配本身在 [FileMemo] 的锁里做：两个包不会同时拿到同一个名字，冲突也不会被静默吞掉。
     */
    fun aliasFor(pkg: String, used: MutableSet<String>): String {
        var alias = ""
        FileMemo.update(file, { parse(it) }, { toJson(it) }) { b ->
            val existing = b.pkg2alias[pkg]
            if (existing != null) {
                alias = existing
                return@update b
            }
            val base = baseAlias(pkg)
            // 已分配过的（哪怕那个包已经卸载）一律视为占用：别名一旦指过包就不再回收
            val reserved = HashSet(used)
            reserved.addAll(b.pkg2alias.values)
            reserved.addAll(b.alias2pkg.keys)
            val cand = pickAlias(base, reserved)
            alias = cand
            b.pkg2alias[pkg] = cand
            b.alias2pkg[cand] = pkg
            b
        }
        return alias
    }

    // ---------------- 序列化 ----------------

    class Book {
        val pkg2alias = LinkedHashMap<String, String>()
        val alias2pkg = LinkedHashMap<String, String>()
    }

    private fun parse(json: String?): Book {
        val b = Book()
        if (json.isNullOrBlank()) return b
        try {
            val o = JSONObject(json)
            o.optJSONObject("p2a")?.let { m ->
                val ks = m.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    m.optString(k).takeIf { it.isNotBlank() }?.let { b.pkg2alias[k] = it }
                }
            }
            o.optJSONObject("a2p")?.let { m ->
                val ks = m.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    m.optString(k).takeIf { it.isNotBlank() }?.let { b.alias2pkg[k] = it }
                }
            }
        } catch (_: Exception) {}
        return b
    }

    private fun toJson(b: Book): String = JSONObject()
        .put("v", 1)
        .put("p2a", JSONObject().apply { for ((k, v) in b.pkg2alias) put(k, v) })
        .put("a2p", JSONObject().apply { for ((k, v) in b.alias2pkg) put(k, v) })
        .toString()

    companion object {
        const val FILE = "link-apps.json"

        /** 包名 → 合法别名（纯函数：小写、非法字符换 `_`、去首尾 `_`、数字开头补 `app_`、≤24） */
        fun baseAlias(pkg: String): String {
            val seg = pkg.split('.').lastOrNull { it.isNotBlank() } ?: pkg
            val sb = StringBuilder(24)
            for (ch in seg.lowercase(Locale.US)) {
                if (sb.length >= 24) break
                sb.append(if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_') ch else '_')
            }
            var out = sb.toString().trim('_')
            if (out.isEmpty()) out = "app"
            if (out.first().isDigit()) out = "app_$out"
            return out.take(24)
        }

        /**
         * 从 [base] 起挑一个没被 [reserved] 占掉的别名（纯函数，可直接单测）：
         * 撞了就加 `_2`、`_3`…，长度仍压在 32 以内（[LinkDispatcher.ALIAS_RE] 的上限）。
         *
         * [reserved] 必须包含**历史上分配过的所有别名**，而不只是这次装机列表里出现的：
         * 名字一旦指过某个包就不再回收，否则卸载重装会让两个包拿到同一个别名。
         */
        fun pickAlias(base: String, reserved: Set<String>): String {
            var cand = base
            var n = 2
            while (cand in reserved) {
                val suffix = "_$n"
                cand = base.take(32 - suffix.length) + suffix
                n++
            }
            return cand
        }
    }
}

/**
 * 外部 Agent 写进来的东西的归属台账（`filesDir/link-origin.json`）。
 *
 * 为什么要有它：
 *  - **记忆正文保持干净**。旧方案想把 `[mcp:client]` 前缀写进 content，但那会出现在用户自己的记忆列表
 *    和注入的提示词里；用户改了一个字前缀还在，撤销就只能靠猜。
 *  - **撤销必须分得清「我写的」和「用户后来动过的」**。这里存的是内容 hash / 条目指纹：
 *    现在库里的东西 hash 一变就说明用户编辑过 → rollback 跳过，绝不动用户的数据。
 */
internal class LinkOrigin(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE)

    data class MemRec(val origin: String, val category: String, val at: Long, val persona: String)
    data class TodoRec(val origin: String, val fp: String, val at: Long)

    class Data {
        val mem = LinkedHashMap<String, MemRec>()
        val todo = LinkedHashMap<Long, TodoRec>()
    }

    fun memories(): Map<String, MemRec> = load().mem
    fun todos(): Map<Long, TodoRec> = load().todo

    /** 记忆 hash 记录时带上人格：撤销时靠它决定去哪个记忆文件里查，而不是把所有文件都扫一遍 */
    fun putMemory(hash: String, origin: String, category: String, at: Long, persona: String) {
        FileMemo.update(file, { parse(it) }, { toJson(it) }) { d ->
            d.mem[hash] = MemRec(origin, category, at, persona)
            d
        }
    }

    fun putTodo(id: Long, origin: String, fp: String, at: Long) {
        FileMemo.update(file, { parse(it) }, { toJson(it) }) { d ->
            d.todo[id] = TodoRec(origin, fp, at)
            d
        }
    }

    fun dropMemories(keys: Collection<String>) {
        FileMemo.update(file, { parse(it) }, { toJson(it) }) { d ->
            for (k in keys) d.mem.remove(k)
            d
        }
    }

    fun dropTodo(id: Long) {
        FileMemo.update(file, { parse(it) }, { toJson(it) }) { d ->
            d.todo.remove(id)
            d
        }
    }

    // ---------------- 内部 ----------------

    private fun load(): Data = FileMemo.get(file) { parse(it) }

    private fun parse(json: String?): Data {
        val d = Data()
        if (json.isNullOrBlank()) return d
        try {
            val o = JSONObject(json)
            o.optJSONObject("mem")?.let { m ->
                val ks = m.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val r = m.optJSONObject(k) ?: continue
                    val origin = r.optString("o")
                    if (origin.isBlank()) continue
                    d.mem[k] = MemRec(origin, r.optString("cat"), r.optLong("at"), r.optString("p"))
                }
            }
            o.optJSONObject("todo")?.let { m ->
                val ks = m.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val id = k.toLongOrNull() ?: continue
                    val r = m.optJSONObject(k) ?: continue
                    val origin = r.optString("o")
                    if (origin.isBlank()) continue
                    d.todo[id] = TodoRec(origin, r.optString("fp"), r.optLong("at"))
                }
            }
        } catch (_: Exception) {}
        return d
    }

    private fun toJson(d: Data): String = JSONObject()
        .put("v", 1)
        .put(
            "mem",
            JSONObject().apply {
                for ((k, v) in d.mem) {
                    put(k, JSONObject().put("o", v.origin).put("cat", v.category).put("at", v.at).put("p", v.persona))
                }
            },
        )
        .put(
            "todo",
            JSONObject().apply {
                for ((k, v) in d.todo) put(k.toString(), JSONObject().put("o", v.origin).put("fp", v.fp).put("at", v.at))
            },
        )
        .toString()

    companion object {
        const val FILE = "link-origin.json"
    }
}
