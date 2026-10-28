package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoteMarkdown 的 md 往返契约：frontmatter 与正文。
 *
 * vault 的 md 是事实源、notes.db 是索引，「发布 → 重建」每跑一轮正文都必须一个字节不变。
 * 2.7 在这里翻过车：encode 在结束分隔线后固定写了一个空行，split 却只剥一个换行，
 * 于是每次重建正文都长出一个前导 `\n`（device5598 上 4 条迁移基线笔记全部中招）。
 * 这些用例就是把「分隔线行尾 + 分隔空行 = 框架，其余全是正文」的对称性钉死。
 */
class NoteMarkdownTest {

    private fun item(body: String) = Note.Item(
        uid = "11111111-2222-3333-4444-555555555555",
        title = "标题",
        body = body,
        createdAt = 100L,
        updatedAt = 200L,
    )

    /** 2.7 重建事故的直接回归：迁移基线的正文（含故意的前导/尾随换行）逐字节不变 */
    @Test fun rebuildKeepsBodyVerbatim() {
        val bodies = listOf(
            "Ling27-migration-baseline wiki-link preserve-body",
            "Pinned migration fixture\n\n#compat [[Ling27-migration-baseline|baseline]]\n\n```text\nkeep  two spaces\n```\n\n\$E=mc^2\$", 
            "\nLing27-migration-baseline wiki-link preserve-body", // 用户故意以空行开头
            "结尾带换行\n",
            "",
        )
        bodies.forEach { b ->
            assertEquals("往返后正文必须逐字节一致：" + b, b, NoteMarkdown.decode(NoteMarkdown.encode(item(b), 7L)).body)
        }
    }

    /** 发布 → 重建 → 再发布必须收敛：md 字节不变，重建不再每轮长出一个 \n */
    @Test fun publishRebuildCycleIsStable() {
        val body = "\n\n首行之前有空行，尾行之后也有\n\n"
        val first = NoteMarkdown.encode(item(body), 3L)
        // encode 的框架：结束分隔线后恰好一个空行，正文从下一行开始
        assertTrue(first.contains("---\n\n" + body))
        val rebuilt = NoteMarkdown.decode(first)
        assertEquals(body, rebuilt.body)
        assertEquals(first, NoteMarkdown.encode(rebuilt.item, 3L, previous = first))
    }

    /** 手写 md 常常没有分隔线后的空行：只剥分隔线行尾，正文自己的第一个换行不能动 */
    @Test fun handWrittenSeparatorVariantsKeepBodyByteExact() {
        assertEquals(
            "正文第一行\n第二行",
            NoteMarkdown.decode("---\nuid: u-1\ntitle: T\n---\n正文第一行\n第二行").body,
        )
        // 分隔线后一个空行（手写或本应用写的）：空行是框架，正文从下一行开始
        assertEquals(
            "正文",
            NoteMarkdown.decode("---\nuid: u-1\ntitle: T\n---\n\n正文").body,
        )
        // 分隔空行 + 正文自己的前导空行：两者共存时只吃框架那个
        assertEquals(
            "\n正文",
            NoteMarkdown.decode("---\nuid: u-1\ntitle: T\n---\n\n\n正文").body,
        )
        // CRLF 文件同样对称
        assertEquals(
            "正文",
            NoteMarkdown.decode("---\r\nuid: u-1\r\ntitle: T\r\n---\r\n\r\n正文").body,
        )
    }

    /** 重建走的是 md 里的 frontmatter：元数据与附件记录必须原样回得来 */
    @Test fun metadataAndAttachmentsSurviveRebuild() {
        val src = item("带附件").copy(
            pinned = true,
            archived = true,
            deletedAt = 5L,
            ocrText = "识别文本",
            summary = "摘要",
            todoId = 9L,
            cardIds = listOf("c1", "c2"),
            rev = 4,
        )
        val att = NoteMarkdown.AttRec(
            uid = "att-uid",
            mime = "image/png",
            path = "202610/x.png",
            thumb = "202610/t.png",
            w = 8,
            h = 9,
            bytes = 123L,
            sha256 = "abc",
            createdAt = 77L,
        )
        val p = NoteMarkdown.decode(NoteMarkdown.encode(src, 12L, listOf(att)))
        assertEquals(src, p.item)
        assertEquals(12L, p.localId)
        assertEquals(listOf(att), p.attachments)
    }

    /** 用户/别的工具加的未知 frontmatter 字段，保存一次之后不能消失 */
    @Test fun unknownFrontmatterKeysSurviveResave() {
        val md = "---\nuid: u-1\ntitle: T\nsource_url: https://x\nling:\n  id: 3\n  mine: keep\n---\n\n正文"
        val parsed = NoteMarkdown.decode(md)
        val out = NoteMarkdown.encode(parsed.item.copy(title = "T2"), 3L, previous = md)
        assertTrue(out.contains("source_url: https://x"))
        assertTrue(out.contains("mine: keep"))
        assertEquals("正文", NoteMarkdown.decode(out).body)
    }
}
