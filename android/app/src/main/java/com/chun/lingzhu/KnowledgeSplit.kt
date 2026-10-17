package com.chun.lingzhu

import org.json.JSONArray

/**
 * v2.0 知识导入（A 切片）：把长资料切成「不丢尾巴」的分段，再把 AI 输出解析成**出处可信**的问答草稿。
 * 纯 JVM，可直接单测。
 *
 * 两条纪律（用户明确要求）：
 *  1. 全文都要处理 —— [chunks] 只按长度分段，不改写、不省略、不静默丢掉结尾；
 *     每一段都 ≤ maxChars，拼起来覆盖原文的全部非空白内容。
 *  2. 出处必须真实 —— [parse] 只接受能在对应分段里定位到的 excerpt（允许模型把原文的换行 / 多空格
 *     压成单空格这类空白差异，但交付出去的仍是原文里那一段字），定位不到就拒绝整段，
 *     绝不拿编造的句子充数（no fake fallback）。
 *
 * 任何一张卡不合格（front / back 为空、超过 [KnowledgeAiImport.FRONT_MAX] / [KnowledgeAiImport.BACK_MAX]、
 * excerpt 定位不到或超过 [KnowledgeAiImport.EXCERPT_MAX]）都**整段拒绝**并抛出 [ApiException]（带卡序号），
 * 不做截断、也不静默丢卡 ——
 * 混合好卡坏卡时只留好卡等于悄悄篡改了模型的输出（用户要求 reject 而不是 clip / 部分导入）。
 */
object KnowledgeSplit {

    /**
     * 按行累积分段：一段不超过 [maxChars]；某一行自身就超长时按字符硬切（逐字保留，不丢尾巴）。
     * 空行只影响排版、不单独成段；返回的每一段都不超过 [maxChars]。
     * 纯空白输入返回空列表（调用方据此判「没有可导入的文字」）。
     */
    fun chunks(text: String, maxChars: Int = 6000): List<String> {
        val max = if (maxChars < 1) 1 else maxChars
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.length > max) {
                // 单行就超过一段：先把已攒的落段，再把这行按 max 逐字切开（每片都是原文子串）
                flush(buf, out)
                var i = 0
                while (i < line.length) {
                    val end = minOf(i + max, line.length)
                    out.add(line.substring(i, end))
                    i = end
                }
                continue
            }
            if (buf.isNotEmpty() && buf.length + line.length + 1 > max) flush(buf, out)
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(line)
        }
        flush(buf, out)
        return out
    }

    /**
     * 解析一段 AI 输出 → 问答草稿。**整段卡要么全留、要么全拒**，不静默丢任何一张：
     *  - front / back 为空 → 抛 [ApiException]（带卡序号）；
     *  - front > [KnowledgeAiImport.FRONT_MAX] 或 back > [KnowledgeAiImport.BACK_MAX] → 抛（不截断）；
     *  - excerpt 在 [sourceText] 里定位不到 → 抛；
     *  - excerpt 超过 [KnowledgeAiImport.EXCERPT_MAX] → 抛（不截断）；
     *  - 某张卡不是 JSON 对象 → 抛；
     *  - deck 缺失 → 用 [KnowledgeAiImport.DEFAULT_DECK]。
     *
     * 整段没有合法 JSON 数组（被截断 / 不是结构化输出）或 [raw] 为空 → 抛 [ApiException]（不去编内容）。
     * 合法的空数组 [] → 返回空列表（这一段资料确实没有可出题的内容，这是明确的语义，由调用方决定整体是否算失败）。
     */
    fun parse(raw: String, sourceText: String): List<KnowledgeAiImport.Draft> {
        val text = raw.trim()
        if (text.isEmpty()) throw ApiException("AI 没有返回内容")
        val arrText = sliceJsonArray(text)
            ?: throw ApiException("AI 返回的内容里找不到问答列表（不是合法的 JSON 数组）")
        val arr = try {
            JSONArray(arrText)
        } catch (e: Exception) {
            throw ApiException("AI 返回的问答列表不是合法的 JSON")
        }
        val out = ArrayList<KnowledgeAiImport.Draft>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw ApiException("第 ${i + 1} 张卡不是合法的 JSON 对象")
            val front = o.optString("front", "").trim()
            val back = o.optString("back", "").trim()
            if (front.isEmpty() || back.isEmpty()) throw ApiException("第 ${i + 1} 张卡的正面或背面为空")
            if (front.length > KnowledgeAiImport.FRONT_MAX || back.length > KnowledgeAiImport.BACK_MAX) {
                throw ApiException("第 ${i + 1} 张卡的正面或背面超过长度上限")
            }
            val excerpt = literalExcerpt(sourceText, o.optString("excerpt", ""))
                ?: throw ApiException("第 ${i + 1} 张卡的出处（excerpt）在资料里定位不到")
            if (excerpt.length > KnowledgeAiImport.EXCERPT_MAX) {
                throw ApiException("第 ${i + 1} 张卡的出处（excerpt）超过 ${KnowledgeAiImport.EXCERPT_MAX} 字上限")
            }
            val deck = o.optString("deck", "").trim().ifEmpty { KnowledgeAiImport.DEFAULT_DECK }
            out.add(KnowledgeAiImport.Draft(deck, front, back, excerpt))
        }
        return out
    }

    private fun flush(buf: StringBuilder, out: MutableList<String>) {
        if (buf.isNotBlank()) out.add(buf.toString().trim())
        buf.setLength(0)
    }

    /**
     * 从可能带前后缀说明 / Markdown 代码块围栏的整段文本里，按**括号配对**取出 JSON 数组原文。
     * 跳过字符串内部的括号与转义引号，避免资料里出现 `a[0]` 这类方括号时切错。
     */
    private fun sliceJsonArray(s: String): String? {
        val start = s.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 在 [source] 里定位 AI 给的 [excerpt]：
     * 先按字面找；找不到再允许「空白差异」（模型把原文换行 / 连续空格压成单空格之类）。
     * 两种情况下返回的都是 **source 里那一段原样文字**，保证交付的 excerpt 一定是原文子串。
     */
    private fun literalExcerpt(source: String, excerpt: String): String? {
        val e = excerpt.trim()
        if (e.isEmpty()) return null
        val hit = source.indexOf(e)
        if (hit >= 0) return e
        var start = 0
        while (start < source.length) {
            val end = matchFrom(source, start, e)
            if (end >= 0) return source.substring(start, end)
            start++
        }
        return null
    }

    /** 从 source[start] 起匹配 ex（ex 里的空白串对应 source 里一段连续空白）：命中返回结束下标，否则 -1 */
    private fun matchFrom(source: String, start: Int, ex: String): Int {
        var si = start
        var ei = 0
        while (ei < ex.length) {
            if (si >= source.length) return -1
            val ec = ex[ei]
            if (ec.isWhitespace()) {
                if (!source[si].isWhitespace()) return -1
                while (si < source.length && source[si].isWhitespace()) si++
                ei++
            } else {
                if (ec != source[si]) return -1
                si++
                ei++
            }
        }
        return si
    }
}
