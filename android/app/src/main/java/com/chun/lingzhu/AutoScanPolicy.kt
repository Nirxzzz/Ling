package com.chun.lingzhu

import java.security.MessageDigest

/**
 * 自动扫描的策略层（v1.70）：全纯函数，不碰 Android 也不碰 SharedPreferences，单测直接测。
 *
 * 三件事：
 *  1. **变化预检** —— 每轮截屏后先算一个轻量画面指纹，和上一轮相同就跳过这一轮，连缓存查询都不做
 *     （以前是「照调、命中缓存再退回」：省了钱但白跑一遍解码 + 缓存文件读写 + 写一条历史）。
 *     只认「一模一样」：复用 Cache 那套缩图灰度 + 粗量化，但网格更稀（8×16 而不是 240 宽全采样），
 *     所以更便宜。不做模糊匹配 —— 感知哈希那种「差不多就算」会把两道只差一个数字的题当成一道（见 Cache 注释）。
 *  2. **连续无变化自动暂停** —— 用户忘了关自动扫描、题目停在某一页不动时，整晚空烧。连续 N 轮指纹一样就自己停下来，
 *     发一条可点的通知，点一下或下次双击小球才继续。
 *  3. **倒计时文案** —— 面板状态行写「自动扫描 · 12s 后」，让人知道它还活着、下一轮什么时候来。
 */
object AutoScanPolicy {

    /** 「连续多少轮没变化就自动暂停」的默认值 */
    const val DEFAULT_STILL_ROUNDS = 10
    /** 这个 N 的取值范围（太小：题目切页中间就误停；太大：该停没停） */
    const val STILL_ROUNDS_MIN = 2
    const val STILL_ROUNDS_MAX = 200

    /** 轻量指纹的网格密度（列 × 行）。比 Cache 的 240 宽全采样便宜一个数量级，够认出「同一页没动」 */
    const val FP_COLS = 8
    const val FP_ROWS = 16
    /** 轻量指纹解码时的缩图宽度（越小越快；32 宽足够区分「翻页 / 滚动 / 弹窗」） */
    const val FP_WIDTH = 32

    // ---------------- 指纹 ----------------

    /**
     * 轻量画面指纹（纯计算）：gray = 缩图后的灰度像素（行优先，w×h，0~255）。
     * 去掉最上面 skipTop 比例（状态栏的时间、电量一直在变），剩下的按 FP_COLS×FP_ROWS 网格取样，
     * 每个采样点只留高 4 位（JPEG 细微噪声不影响）再哈希。
     * 输入不合法（尺寸为 0、像素数不够）返回空串 —— 调用方按「拿不到指纹」处理，不当成「画面没变」。
     */
    fun lightFingerprint(gray: IntArray, w: Int, h: Int, cols: Int = FP_COLS, rows: Int = FP_ROWS, skipTop: Float = 0.06f): String {
        if (w <= 0 || h <= 0 || gray.size < w * h) return ""
        val cc = cols.coerceAtLeast(1); val rr = rows.coerceAtLeast(1)
        val top = (h * skipTop).toInt().coerceIn(0, (h - 1).coerceAtLeast(0))
        val avail = (h - top).coerceAtLeast(1)
        val sb = StringBuilder(cc * rr + 12).append(cc).append('x').append(rr).append(':')
        for (ry in 0 until rr) {
            val y = (top + avail * ry / rr).coerceIn(0, h - 1)
            for (rx in 0 until cc) {
                val x = (w * rx / cc).coerceIn(0, w - 1)
                sb.append(('a'.code + (gray[y * w + x].coerceIn(0, 255) shr 4)).toChar())
            }
        }
        return sha256(sb.toString())
    }

    // ---------------- 变化预检 ----------------

    /**
     * 这一轮要不要调接口：上一轮和这一轮指纹都拿到了、且完全一样 → 跳过。
     * 拿不到指纹（null / 空串）→ 不跳（宁可多调一次，也不要漏更新）。
     */
    fun shouldSkip(prevFp: String?, curFp: String?): Boolean {
        if (prevFp.isNullOrEmpty() || curFp.isNullOrEmpty()) return false
        return prevFp == curFp
    }

    // ---------------- 连续无变化计数 ----------------

    /** 自动扫描的「静默」状态：fp = 上一轮的画面指纹；rounds = 连续几轮指纹一样 */
    data class Still(val fp: String? = null, val rounds: Int = 0) {
        /** 拿它当下一轮的「上一轮」：指纹保留，轮数清零（下一轮接着比） */
        fun resetRounds(): Still = copy(rounds = 0)
    }

    /**
     * 记一轮扫描的结果，返回新的状态和「这一轮是不是因为画面没变而被跳过」。
     *  - 指纹和上一轮一样 → rounds + 1，跳过（不调接口）
     *  - 指纹变了 → rounds 归零，不跳过
     *  - 这一轮没拿到指纹 → rounds 归零，不跳过（当成「状态不可知」，不许拿它凑「无变化」的数）
     */
    fun observe(prev: Still, curFp: String?): Pair<Still, Boolean> {
        if (curFp.isNullOrEmpty()) return prev.resetRounds() to false
        return if (shouldSkip(prev.fp, curFp)) Still(curFp, prev.rounds + 1) to true
        else Still(curFp, 0) to false
    }

    /** 连续 rounds 轮没变化、且用户开着这个开关 → 该自动暂停了。threshold <= 0 视为关闭 */
    fun shouldPause(rounds: Int, threshold: Int): Boolean = threshold > 0 && rounds >= threshold

    /** 设置里那个「连续 N 轮」的值：夹到 [MIN, MAX] */
    fun clampRounds(v: Int): Int = v.coerceIn(STILL_ROUNDS_MIN, STILL_ROUNDS_MAX)

    // ---------------- 文案 ----------------

    /** "12s 后" / "马上"（剩余不足 1 秒就说马上；不会显示 0s） */
    fun countdown(remainingMs: Long): String {
        if (remainingMs <= 0) return "马上"
        val s = ((remainingMs + 999) / 1000).coerceAtLeast(1)
        return "$s" + "s 后"
    }

    /**
     * 面板状态行的文案。扫描中（solving）时这一行由 AnswerPanel 现有的「自动扫描 · 正在重新识别…」管，这里不碰。
     *  - 因画面没变化而暂停 → 告诉人为什么停的、点一下能继续（可点）
     *  - 正常开着 → 下一轮倒计时
     *  - 关着 → 就写「关」
     */
    fun status(autoOn: Boolean, pausedByStill: Boolean, stillRounds: Int, remainingMs: Long): String = when {
        // 用户已把开关关掉：暂停态必须让位（P2），否则显示「点这里继续」但点了也没有扫描在跑
        !autoOn -> "自动扫描 · 关"
        pausedByStill -> "自动扫描已暂停 · 画面 $stillRounds 轮没变化 · 点这里继续"
        else -> "自动扫描 · " + countdown(remainingMs)
    }

    /** 跳过这一轮时状态行写的话（rounds 是算上这一轮之后连续几轮没变） */
    fun skippedStatus(rounds: Int): String = "画面没变化 · 第 $rounds 轮跳过（没调接口）"

    // ---------------- 会话记页与下一轮截止（v1.91） ----------------
    //
    // 自动扫描一开就是一次「会话」：面板那行「第 N 页 · 下次自动识题 XXs」的页数只在这里算。
    // 只数**真的成功换了一页**：手动识别、失败 / 取消、同一画面（含被指纹预检跳过的那些轮）都不算。
    // 新画面命中缓存也算（那同样是一次成功识别，只是没调接口），答案碰巧和上一页一样也不影响记页。

    /** 一次自动扫描会话的记页状态；[fp] = 上一张**记过页**的画面指纹（不是上一轮看过的） */
    data class Pages(val pages: Int = 0, val fp: String = "") {
        /** 重新开一轮扫描：页数从 0 数（开始 / 继续扫描） */
        fun reset(): Pages = Pages()

        /**
         * 这一轮算不算新的一页：
         *  - 手动双击 / 框选 / 图片识别（autoRound=false）不算；
         *  - 失败 / 取消 / 空答案（ok=false）不算；
         *  - 拿不到指纹（null / 空）不算 —— 无法确认是「新画面」；
         *  - 同一指纹只算一次（同屏重复成功、指纹预检跳过的轮次都不会把页数刷上去）。
         */
        fun recognized(autoRound: Boolean, fp: String?, ok: Boolean): Pages {
            if (!autoRound || !ok || fp.isNullOrEmpty() || fp == this.fp) return this
            return Pages(pages + 1, fp)
        }
    }

    /** 下一轮的**单调**截止时刻：intervalMs 非法（<=0）就当成「现在」 */
    fun deadline(now: Long, intervalMs: Long): Long = now + intervalMs.coerceAtLeast(0L)

    /**
     * 距下一轮还有多久：截止时刻已过 / 没有截止时刻 → 0。
     * 调用方用 SystemClock.elapsedRealtime()（不是墙上时钟），用户改时间 / 时区都不影响倒计时。
     */
    fun remaining(deadline: Long, now: Long): Long = if (deadline <= 0L) 0L else (deadline - now).coerceAtLeast(0L)

    // ---------------- 连续失败（v1.86） ----------------
    //
    // 被动模式下面板**没有状态行**（`AnswerPanel.applyPassive` 把 status 收起来、窗口还不可触摸），
    // 所以「截屏失败 / 接口失败」写了也永远看不见 —— 自动扫描坏了一整晚都在悄悄重试，这是用户报的静默失效。
    // 对策两条：① 每次失败**破例亮 3 秒**（见 [AnswerPanel.peekStatus]，只露那一行，不还原整块面板）；
    //          ② 连败到上限就**自己停**，并留一条可点的通知。
    // 计数、文案、判据全在这里（纯函数），服务里只做「亮 / 停 / 发通知」这三件事。

    /** 连败几次就自己停下来。3 次是有意的：一次失败可能是熄屏 / 切应用的瞬间，两次三次还不行就是真坏了 */
    const val FAIL_LIMIT = 3

    /** 失败原因在被动面板上亮多久（毫秒）：够读完一行，又不至于一直杵在那儿挡屏 */
    const val PEEK_MS = 3000L

    /** 自动扫描的失败状态：streak = 连着第几次失败；last = 最近一次原因（调用方传进来的已经是大白话） */
    data class Fails(val streak: Int = 0, val last: String = "")

    /** 记一次失败（原因空白也要有个说法，别亮一行空字） */
    fun failed(f: Fails, reason: String): Fails =
        f.copy(streak = f.streak + 1, last = reason.trim().ifEmpty { "未知错误" })

    /** 这一轮成功了 → 连败清零 */
    fun succeeded(f: Fails): Fails = if (f.streak == 0) f else Fails()

    /** 该不该因为连败自己停 */
    fun shouldPauseOnFail(f: Fails): Boolean = f.streak >= FAIL_LIMIT

    /** 没到上限：亮一行「第几次 + 为什么」（3 秒后自己收） */
    fun failStatus(f: Fails): String = "✗ 这一轮没成（连续 ${f.streak} 次）：${f.last}"

    /**
     * 因为**连败**自己停下来的那一行。
     * ⚠️ 不走 [status] —— 那里「用户把开关关掉」的优先级最高（P2 定的），而这是**它自己停的**，
     * 说「关」会让人以为是自己关的、也不知道为什么停。所以自己停的两种原因各有一条独立文案。
     */
    fun failPausedStatus(f: Fails): String =
        "自动扫描已停 · 连续 ${f.streak} 次失败：${f.last} · 点一下继续"

    /** 因为**画面没变化**自己停下来的那一行（同上，别用 [status]：那时 autoOn 已经是 false，会被「关」盖掉） */
    fun stillPausedStatus(stillRounds: Int): String =
        "自动扫描已暂停 · 画面 $stillRounds 轮没变化 · 点一下继续"

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}