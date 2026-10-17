package com.chun.lingzhu

/**
 * v1.70 K4 知识导入（纯 JVM，可单测）：剪藏、TXT/MD 文件 → 知识卡。
 * 截断规则与 KnowledgeCard 一致：front ≤200（clip），back ≤500（clip 带省略号）。
 */
object KnowledgeImport {

    /** 剪藏：第一句（或首行）当正面，其余当背面。纯空白返回 null。 */
    fun clipToCard(text: String): Pair<String, String>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // 句尾标点可能成簇（？！、!?）：边界吞掉整个标点簇，不能在「！」和「？」中间切
        val first = t.indexOfFirst { it in "。！？!?\n" }
        var splitAt = if (first < 0) t.length else first + 1
        if (first >= 0) while (splitAt < t.length && t[splitAt] in "！？!?…。") splitAt++
        val front = Memo.clip(t.take(splitAt), 200)
        val rest = t.removePrefix(t.take(splitAt)).trim()
        val back = if (rest.isEmpty()) front else Memo.clip(rest, 500)
        return front to back
    }

    /** Markdown 导入：`问 | 答` 行，或 `# 标题`=正面 + 标题下正文=背面（到下一标题止）。 */
    fun mdToCards(md: String, deck: String = "默认"): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var curTitle = ""
        var curBody = StringBuilder()
        fun flush() {
            if (curTitle.isNotBlank()) out.add(Memo.clip(curTitle.trim(), 200) to Memo.clip(curBody.toString().trim(), 500))
            curTitle = ""; curBody = StringBuilder()
        }
        for (rawLine in md.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#") -> { flush(); curTitle = line.trimStart('#', ' ') }
                line.contains('|') && !line.startsWith("#") -> {
                    flush()   // 保持阅读顺序：先落前面的标题卡，再出管道卡
                    val i = line.indexOf('|')
                    val f = line.take(i).trim(); val b = line.substring(i + 1).trim()
                    if (f.isNotBlank() && b.isNotBlank() && f != "问") out.add(Memo.clip(f, 200) to Memo.clip(b, 500))
                }
                line.isBlank() -> {}   // 空行只是排版
                else -> curBody.append(line).append("\n")
            }
        }
        flush()
        return out.filter { it.first.isNotBlank() }
    }

    /** TXT 导入：每行 `正面<sep>背面`，只有一段的行 → 整行当正面、背面同正面（自测卡）。 */
    fun txtToCards(txt: String, sep: String = "\t"): List<Pair<String, String>> =
        txt.lineSequence()
            .map { it.trim().trimStart('\uFEFF') }
            .filter { it.isNotEmpty() }
            .map { line ->
                val i = line.indexOf(sep)
                if (i > 0) Memo.clip(line.take(i), 200) to Memo.clip(line.substring(i + sep.length), 500)
                else Memo.clip(line, 200) to Memo.clip(line, 500)
            }
            .filter { it.first.isNotBlank() }
            .toList()
}
