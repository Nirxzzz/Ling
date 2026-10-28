package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 词库源数据体检（v1.85.11 维护）：
 * - [noCjkInsideEnglishWords] 是**回归门禁**：英文单词里夹汉字 = 源数据串行的最硬证据，零误报，必须永远为 0
 *   （v1.85.11 修掉 1 处：monotonous 的 "on a二diet"；pour / skim / arrange 等同类乱码一并手术修复）。
 * - [scan] 是诊断输出：GLUED / SPLIT 两类启发式误报多（音标重音符、正常词组），只打印不判死。
 */
class WordbankHygieneScanTest {

    private fun bankFiles(): List<File> {
        val dir = File("src/main/assets/wordbank")
        return dir.listFiles { f -> f.name.endsWith(".txt") }!!.sorted()
    }

    // 英文单词中间夹汉字（串行最硬的证据）
    private val CJK_IN_EN = Regex("[a-z][\u4e00-\u9fff][a-z]")
    // 小写连大写无空格（多为音标重音符，仅诊断用）
    private val GLUED = Regex("[a-z]{3,}[A-Z][a-z]+")
    // 词中断开带空格（"pour ed"），误报多（"for sth."），仅诊断用
    private val SPLIT = Regex("\\b([a-z]{3,6}) ([a-z]{2,3})\\b(,|\\.|$)")

    /** 去掉音标段（[...]、/.../）再查，避免 IPA 重音符假阳性 */
    private fun stripPhonetics(s: String): String =
        s.replace(Regex("\\[[^\\]]*]"), " ")
            .replace(Regex("/[^/\n]{2,40}/"), " ")
            // 助记注解 (y变i,有可能的)：含汉字的括号注解整段剥掉，避免把注解当成「字母夹汉字」
            .replace(Regex("\\([^)\\n]*[\u4e00-\u9fff][^)\\n]*\\)"), " ")

    private fun issuesOf(back: String): List<String> {
        val iss = mutableListOf<String>()
        val body = stripPhonetics(back)
        if (CJK_IN_EN.containsMatchIn(body)) iss += "CJK-IN-EN"
        for (m in GLUED.findAll(body)) iss += "GLUED:${m.value}"
        for (m in SPLIT.findAll(body)) iss += "SPLIT:${m.value}"
        return iss
    }

    @Test fun noCjkInsideEnglishWords() {
        val bad = mutableListOf<String>()
        for (f in bankFiles()) {
            for (c in Words.parse(f.readText())) {
                if (CJK_IN_EN.containsMatchIn(stripPhonetics(c.back))) bad += "${f.name}:${c.front}"
            }
        }
        assertEquals("词库存在串行损坏: $bad", 0, bad.size)
    }

    @Test fun scan() {
        val out = StringBuilder()
        for (f in bankFiles()) {
            val cards = Words.parse(f.readText())
            var n = 0
            for (c in cards) {
                val issues = issuesOf(c.back)
                if (issues.isNotEmpty()) {
                    n++
                    if (n <= 15) out.append("${f.name} [${c.front}] ${issues.joinToString(";")}\n   ${c.back.take(140).replace("\n", "⏎")}\n")
                }
            }
            out.append("---- ${f.name}: $n entries with flags\n")
        }
        println(out)
    }
}
