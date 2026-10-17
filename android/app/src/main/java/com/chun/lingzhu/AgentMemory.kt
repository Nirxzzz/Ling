package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.math.pow

/**
 * Agent 长期记忆（v1.86）· **纯独立端**：存本机、检索纯本地算法、提炼走用户自己填的接口。
 * 不依赖任何外部记忆服务 —— 用户填上自己的 AI 接口就能用，和聊天本身同一个依赖面。
 *
 * 三段分工（照 MemoryCard / ChatStore 的老规矩，能测的全是纯函数）：
 *  - 纯规则在这里：分词、相似度、打分、去重合并、上下文块、提炼结果解析 —— JVM 单测直接测；
 *  - 落盘在 [Store]（filesDir/agent-mem/<pid>.json），复用 [FileMemo]：进程内副本 + 后台写，每个人格一份不串台；
 *  - 提炼在 [Extractor]：一次非流式 chat/completions（和聊天同一套 [ApiConfig]），让模型从这一轮对话里
 *    挑出值得长期记住的事。**只在后台线程调**，失败静默 —— 记忆是增益，不是依赖。
 *
 * 检索为什么不用向量：本机没有嵌入模型（零第三方库的立身之本），汉字二元组重叠对「养了只猫 ↔ 什么宠物」
 * 这类近义已经够用；更准的语义检索等哪天真上端侧小模型再说。
 *
 * 和记忆卡（[MemoryCard]）的关系：卡是「用户手动记的 5 条结论」，这里是「Agent 自己攒的事实库」，
 * 两份都注入提示词，互补不替代。
 */
object AgentMemory {
    /** 库容硬上限：超了丢「最不值钱」的（分数最低的）。一条 ~50 字，300 条也就十几 KB 的 JSON */
    const val MAX = 300

    /** 一次检索最多带几条进上下文 */
    const val TOP_N = 5

    /**
     * 分数低于这条不带上 —— 沾不上边的记忆塞进上下文只会分心、白烧 token。
     * 0.08 的口径：词法检索只有一个二元组沾边时（最常见的一词命中，如「年糕」）分数约 0.09~0.12，
     * 要放得进来；完全无关的重叠是 0，照样拦得住。
     */
    const val MIN_SCORE = 0.08

    /** 相似度过这条 = 同一件事（合并而不是新增） */
    const val DUP_SIM = 0.85

    /** 时间衰减半衰期（天）：14 天没用过的记忆权重减半 */
    const val HALF_LIFE_DAYS = 14.0

    /** 类别（和服务端方案对齐过的六分类，模型提炼时二选一地用） */
    val CATEGORIES = setOf("FACT", "EMOTION", "PREFERENCE", "EVENT", "HABIT", "RELATIONSHIP")

    /** 摘要块抬头（跟在末条 user 消息后面 —— 约束离正文最近，同 [ChatStore.HISTORY_HEAD] 的道理） */
    const val MEM_HEAD = "[以下是从长期记忆里翻出的几条相关记录，供回答参考；和这次说的无关的就忽略]"

    data class Mem(
        val content: String,
        val category: String = "FACT",
        val importance: Double = 0.5,
        val at: Long = 0L,          // 记下/更新 的时间
        val uses: Int = 1,          // 被检索命中的次数（微加成）
        val lastUsed: Long = 0L,    // 上次命中时间（衰减从这算）
    )

    // ---------------- 检索（纯函数） ----------------

    /**
     * 查询/文本 → 特征集合：汉字按相邻二元组（「橘猫」→ 橘猫 / 也有「的猫」这类跨词组，可接受），
     * 连续拉丁字母/数字整词小写。停用字（的了是我你他它们这那在吗呢吧啊）先剥掉再切，
     * 不然「我的猫」「你的猫」全是交集。
     */
    fun bigrams(s: String): Set<String> {
        val stop = "的了是我你它他们这那在吗呢吧啊呀哦嗯和跟对就也都还很真个"
        val feat = HashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 2) {
                val w = sb.toString().lowercase()
                if (w.any { it.code < 128 }) feat.add(w)      // 拉丁词整词
                else for (i in 0 until w.length - 1) feat.add(w.substring(i, i + 2))
            }
            sb.setLength(0)
        }
        for (ch in s) {
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch)
                ch.code in 0x4E00..0x9FFF ->
                    if (ch in stop) flush()
                    else {
                        if (sb.isNotEmpty() && sb.last().code < 128) flush()
                        sb.append(ch)
                    }
                else -> flush()
            }
        }
        flush()
        return feat
    }

    /** 重叠系数：2|A∩B| / (|A|+|B|)。两边都空 → 0（空查询永远检不出东西） */
    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        return 2.0 * inter / (a.size + b.size)
    }

    /**
     * 单字层特征：去停用字后的 CJK 单字 + 拉丁整词。
     * 为什么要有这一层：bigram 要求两个字都重合，「我的猫叫什么」vs「养了一只橘猫」——「猫」一个在
     * query 词首、一个在 content 词尾，bigram 零重叠，核心问法直接落空（真机实测踩到的）。
     * 单字层召回换来误配风险，所以只作降权补充（×0.6），不单独定命中。
     */
    fun unigrams(s: String): Set<String> {
        val stop = "的了是我你它他们这那在吗呢吧啊呀哦嗯和跟对就也都还很真个"
        val feat = HashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 2 && sb.toString().any { it.code < 128 }) {
                feat.add(sb.toString().lowercase())
            } else {
                for (ch in sb) if (ch.code in 0x4E00..0x9FFF) feat.add(ch.toString())
            }
            sb.setLength(0)
        }
        for (ch in s) {
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch)
                ch.code in 0x4E00..0x9FFF ->
                    if (ch in stop) flush()
                    else {
                        if (sb.isNotEmpty() && sb.last().code < 128) flush()
                        sb.append(ch)
                    }
                else -> flush()
            }
        }
        flush()
        return feat
    }

    /**
     * 一条记忆对这次查询的分数 = 相似度 × 重要度加权 × 时间衰减 × 使用加成。
     * 相似度是主导项（0~1），其余都在 0.4~1.2 倍之间微调 —— 不会让「很老但字面沾边」的盖过「正中题的」。
     */
    /**
     * 两层特征合算：max(bigram 相似度, 0.6 × 单字相似度) × 重要度 × 衰减 × 频次。
     * bigram 命中是硬证据（两字相连都重合）；单字层只做降权补充，专救「猫」这种词首/词尾错位的真实问法。
     */
    fun score(qBi: Set<String>, qUni: Set<String>, now: Long, m: Mem): Double {
        val cBi = bigrams(m.content)
        val cUni = unigrams(m.content)
        val simBi = similarity(qBi, cBi)
        // 单字层要 ≥2 个交集字才计分：一个虚词（"不"/"的"漏网的）搭桥就误命中，真机验证抓过
        val interUni = qUni.intersect(cUni)
        val simUni = if (interUni.size >= 2) similarity(qUni, cUni) else 0.0
        val sim = maxOf(simBi, 0.6 * simUni)
        if (sim <= 0.0) return 0.0
        val days = ((now - (if (m.lastUsed > 0) m.lastUsed else m.at)).coerceAtLeast(0)) / 86_400_000.0
        val decay = 0.5.pow(days / HALF_LIFE_DAYS)
        val freq = 1.0 + 0.1 * ln(1.0 + m.uses)
        val imp = 0.4 + 0.6 * m.importance.coerceIn(0.0, 1.0)
        return sim * imp * decay * freq
    }

    /** 检索：按分排、砍到 [TOP_N]、丢掉低于 [MIN_SCORE] 的。命中一条就顺手把它的使用计数 +1（返回更新后的库） */
    fun top(query: String, mems: List<Mem>, now: Long, n: Int = TOP_N): Pair<List<Mem>, List<Mem>> {
        val q = bigrams(query)
        val qUni = unigrams(query)
        val scored = mems.map { it to score(q, qUni, now, it) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .take(n)
        if (scored.isEmpty()) return emptyList<Mem>() to mems
        val picked = scored.map { it.first }.toSet()
        val bumped = mems.map { if (it in picked) it.copy(uses = it.uses + 1, lastUsed = now) else it }
        return picked.map { it.copy(uses = (it.uses) + 1, lastUsed = now) } to bumped
    }

    /** 检索结果 → 追加到 user 消息末尾的记忆块。空表返回空串：不拼就不改请求体，前缀缓存不受影响 */
    fun contextBlock(mems: List<Mem>): String {
        if (mems.isEmpty()) return ""
        val sb = StringBuilder(MEM_HEAD)
        for (m in mems) sb.append("\n- [").append(m.category).append("] ").append(m.content.replace('\n', ' ').trim())
        return sb.toString()
    }

    // ---------------- 提炼结果的解析与入库（纯函数） ----------------

    /**
     * 模型提炼回复 → 候选记忆。模型被要求只回 JSON 数组，但总有不守规矩的：
     * 这里取「第一个 `[` 到最后一个 `]`」再解析；坏 JSON / 空 / 单条缺正文都跳过，绝不抛。
     */
    fun parseExtraction(reply: String): List<Mem> {
        val s = reply.trim()
        val l = s.indexOf('['); val r = s.lastIndexOf(']')
        if (l < 0 || r <= l) return emptyList()
        return try {
            val arr = JSONArray(s.substring(l, r + 1))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optString("content", "").trim()
                if (c.isEmpty()) return@mapNotNull null
                val cat = o.optString("category", "FACT").uppercase().takeIf { it in CATEGORIES } ?: "FACT"
                val imp = runCatching { o.optDouble("importance", 0.5) }.getOrNull()?.coerceIn(0.0, 1.0) ?: 0.5
                Mem(c, cat, imp)
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 候选并入库：和现有某条相似度 ≥ [DUP_SIM] 的算同一件事 —— 保留库里的老条目（时序稳），
     * 重要度取大、uses+1；否则新增。然后砍到 [MAX]：分数最低的先走（用「空查询」打分 = 纯重要度×衰减×使用，
     * 正好是「这条还有多大价值」的口径）。
     */
    fun merge(existing: List<Mem>, candidates: List<Mem>, now: Long): List<Mem> {
        val out = existing.toMutableList()
        for (c in candidates) {
            val cb = bigrams(c.content)
            val idx = out.indices.filter { similarity(cb, bigrams(out[it].content)) >= DUP_SIM }
                .maxByOrNull { similarity(cb, bigrams(out[it].content)) }
            if (idx != null) {
                val old = out[idx]
                out[idx] = old.copy(importance = maxOf(old.importance, c.importance), at = now, uses = old.uses + 1, lastUsed = now)
            } else {
                out.add(c.copy(at = now, lastUsed = now))
            }
        }
        if (out.size <= MAX) return out
        return out.sortedByDescending { mem -> score(bigrams(mem.content), unigrams(mem.content), now, mem) }.take(MAX)
    }

    // ---------------- 序列化（[Store] 用；纯函数所以坏文件永远救得回来） ----------------

    fun toJson(mems: List<Mem>): String {
        val arr = JSONArray()
        for (m in mems) arr.put(JSONObject()
            .put("content", m.content).put("category", m.category).put("importance", m.importance)
            .put("at", m.at).put("uses", m.uses).put("lastUsed", m.lastUsed))
        return arr.toString()
    }

    fun parse(s: String?): List<Mem> = try {
        if (s.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optString("content", "").trim()
                if (c.isEmpty()) return@mapNotNull null
                Mem(c, o.optString("category", "FACT").takeIf { it in CATEGORIES } ?: "FACT",
                    o.optDouble("importance", 0.5), o.optLong("at", 0), o.optInt("uses", 1), o.optLong("lastUsed", 0))
            }
        }
    } catch (_: Exception) { emptyList() }

    /**
     * 每个人格一份（filesDir/agent-mem/<pid>.json）：学习教练和生活管家不该记得同一摊事，
     * 和 [MemoryCard.Store] 按人格分文件同一个道理。
     */
    class Store(context: Context, pid: String) {
        private val f = java.io.File(java.io.File(context.filesDir, "agent-mem").apply { mkdirs() }, safe(pid) + ".json")

        fun get(): List<Mem> = FileMemo.get(f) { AgentMemory.parse(it) }

        fun put(mems: List<Mem>) = FileMemo.put(f, mems) { AgentMemory.toJson(it) }

        /** 候选并入库一步走（后台线程用）：先和库里去重合并，再整体写回 */
        fun mergeAndPut(candidates: List<Mem>) = put(AgentMemory.merge(get(), candidates, System.currentTimeMillis()))

        fun clear() = FileMemo.clear(f, emptyList<Mem>())

        private fun safe(pid: String) = pid.filter { it.isLetterOrDigit() }.take(24).ifBlank { "p0" }
    }

    /**
     * 提炼：拿聊天**同一个接口配置**发一次非流式请求。只在后台线程用；任何失败抛 [ApiException]
     * 或返回空表 —— 调用方（后台线程）静默吞掉，聊天主流程完全不感知。
     */
    class Extractor(private val cfg: ApiConfig) {
        /** 提示词里写死六分类与「宁缺毋滥」：误记比漏记伤得多（记错了比不记得更伤陪伴感） */
        val prompt: String
            get() = "你是记忆提取器。从下面这轮对话中提取值得长期记住的信息，只输出 JSON 数组，不要输出任何其他文字。" +
                "每条格式：{\"content\":\"…\",\"category\":\"…\",\"importance\":0.0到1.0}\n" +
                "- category 只能取 FACT/EMOTION/PREFERENCE/EVENT/HABIT/RELATIONSHIP 之一\n" +
                "- 只提取长期有效的信息：身份、偏好、关系、重要事件、习惯、强烈情绪\n" +
                "- 寒暄、临时语境、只在这次对话里有意义的内容一律不提取；没有值得记的输出 []\n" +
                "- content 用第三人称、一句话、自包含（脱离上下文也能看懂）\n" +
                "- importance：日常事实 0.4~0.6，偏好/习惯 0.6~0.8，身份/关系类 0.8~1.0\n" +
                "- 宁缺毋滥：记错比不记得更糟"

        /** 独立的提示词请求体（只有一条 user 消息，非流式）—— 不碰 [Bodies.text] 的流式参数 */
        fun body(userInput: String, aiResponse: String): String {
            val text = "用户：${userInput.take(2000)}\nAI：${aiResponse.take(2000)}"
            val o = JSONObject()
                .put("model", cfg.model)
                .put("stream", false)
                .put("max_tokens", 512)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt))
                    .put(JSONObject().put("role", "user").put("content", text)))
            Bodies.thinking(cfg, true, o)   // 提炼不需要思考：最快出结果
            return o.toString()
        }

        fun run(userInput: String, aiResponse: String): List<Mem> {
            if (cfg.baseUrl.isBlank()) return emptyList()
            val conn = (URL(cfg.baseUrl.trim().trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10_000; readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
            }
            try {
                conn.outputStream.use { it.write(body(userInput, aiResponse).toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) return emptyList()
                val txt = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val reply = try {
                    JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
                } catch (_: Exception) { "" }
                return parseExtraction(reply)
            } catch (_: Exception) {
                return emptyList()
            } finally {
                conn.disconnect()
            }
        }
    }
}
