package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AnkiImport：.apkg 导入的纯函数部分。
 * 真 .apkg 的解包 / SQLite 读取在 Android 壳（AnkiImporter）里，单测覆盖不到，这里只测纯函数。
 */
class AnkiImportTest {

    private val SEP = "\u001F"

    private fun flds(vararg f: String) = f.joinToString(SEP)

    // ---------------- splitFields ----------------
    // ---------------- edge：极端 flds ----------------

    @Test fun splitFieldsAllEmptyReturnsEmpty() {
        assertEquals(0, AnkiImport.splitFields("\u001f\u001f").size)
    }

    @Test fun toKCDataSkipsNotesWithFewerThanTwoFields() {
        val out = AnkiImport.toKCData(listOf(1L to "只有一段"), "默认", 1L)
        assertTrue(out.isEmpty())
    }

    @Test fun stripHtmlOnHugeInputDoesNotThrow() {
        val huge = "<div>" + "字".repeat(100_000) + "</div>"
        assertTrue(AnkiImport.stripHtml(huge).contains("字"))
    }

    @Test fun splitFieldsSplitsOnUnitSeparator() {
        assertEquals(listOf("正面", "背面"), AnkiImport.splitFields(flds("正面", "背面")))
    }

    @Test fun splitFieldsKeepsAllSegments() {
        assertEquals(4, AnkiImport.splitFields(flds("a", "b", "c", "d")).size)
    }

    @Test fun splitFieldsDropsEmptySegments() {
        assertEquals(listOf("a", "b"), AnkiImport.splitFields(flds("a", "", "b")))
    }

    @Test fun splitFieldsEmptyStringGivesEmptyList() {
        assertTrue(AnkiImport.splitFields("").isEmpty())
    }

    // ---------------- stripHtml ----------------

    @Test fun stripHtmlRemovesTags() {
        assertEquals("bold", AnkiImport.stripHtml("<b>bold</b>"))
    }

    @Test fun stripHtmlDecodesNamedEntities() {
        assertEquals("a & b < c > d", AnkiImport.stripHtml("a &amp; b &lt; c &gt; d"))
    }

    @Test fun stripHtmlDecodesNumericEntities() {
        assertEquals("café 中", AnkiImport.stripHtml("caf&#233; &#20013;"))
    }

    @Test fun stripHtmlDecodesAmpOnceNotTwice() {
        // &amp;lt; 真实文本就是 "&lt;"，只解一层，不能变成 "<"
        assertEquals("&lt;", AnkiImport.stripHtml("&amp;lt;"))
    }

    @Test fun stripHtmlSeparatesBlockTagsWithSpace() {
        assertEquals("a b", AnkiImport.stripHtml("<div>a</div><div>b</div>"))
    }

    @Test fun stripHtmlCompressesWhitespace() {
        assertEquals("a b", AnkiImport.stripHtml("a \n\t  b"))
    }

    @Test fun stripHtmlEmptyStaysEmpty() {
        assertEquals("", AnkiImport.stripHtml(""))
    }

    // ---------------- toKCData ----------------

    @Test fun toKCDataMapsFrontBackAndSource() {
        val out = AnkiImport.toKCData(listOf(1L to flds("问", "答")), "我的牌组", 1_000L)
        assertEquals(1, out.size)
        assertEquals("问", out[0].front)
        assertEquals("答", out[0].back)
        assertEquals("我的牌组", out[0].deck)
        assertEquals(KnowledgeCard.Source.IMPORT.code, out[0].source)
        assertEquals(1_000L, out[0].createdAt)
    }

    @Test fun toKCDataStripsHtmlOnBothSides() {
        val out = AnkiImport.toKCData(listOf(1L to flds("<b>问</b>", "<div>答</div>")), "d", 1L)
        assertEquals("问", out[0].front)
        assertEquals("答", out[0].back)
    }


    @Test fun toKCDataSkipsNoteWithLessThanTwoFields() {
        val notes = listOf(1L to "只有正面", 2L to flds("有正面", "有背面"), 3L to "")
        assertEquals(1, AnkiImport.toKCData(notes, "d", 1L).size)
    }

    @Test fun toKCDataSkipsNoteWithBlankFrontAfterStrip() {
        assertTrue(AnkiImport.toKCData(listOf(1L to flds("<b></b>", "答")), "d", 1L).isEmpty())
    }

    @Test fun toKCDataDedupsSameFrontInSameDeck() {
        val notes = listOf(1L to flds("同", "A"), 2L to flds("同", "B"))
        assertEquals(1, AnkiImport.toKCData(notes, "d", 1L).size)
    }

    @Test fun toKCDataKeepsSameFrontAcrossDifferentDecks() {
        val a = AnkiImport.toKCData(listOf(1L to flds("同", "A")), "d1", 1L)
        val b = AnkiImport.toKCData(listOf(2L to flds("同", "A")), "d2", 1L)
        assertEquals(1, a.size); assertEquals(1, b.size)
    }

    @Test fun toKCDataClipsFrontTo200() {
        val out = AnkiImport.toKCData(listOf(1L to flds("字".repeat(500), "答")), "d", 1L)
        assertEquals(AnkiImport.FRONT_MAX + 1, out[0].front.length)   // 截断 + 省略号
    }

    @Test fun toKCDataClipsBackTo500() {
        val out = AnkiImport.toKCData(listOf(1L to flds("问", "字".repeat(900))), "d", 1L)
        assertEquals(AnkiImport.BACK_MAX + 1, out[0].back.length)
    }

    @Test fun toKCDataIdsStartAtZeroForStoreToAssign() {
        val out = AnkiImport.toKCData(listOf(7L to flds("问", "答")), "d", 1L)
        assertEquals(0L, out[0].id)
    }

    // ---------------- zipEntryName ----------------

    @Test fun zipEntryNamePrefersAnki21OverAnki2() {
        val n = AnkiImport.zipEntryName(setOf("collection.anki2", "collection.anki21", "media"))
        assertEquals("collection.anki21", n)
    }

    @Test fun zipEntryNameFallsBackToAnki2() {
        assertEquals("collection.anki2", AnkiImport.zipEntryName(setOf("media", "collection.anki2")))
    }

    @Test fun zipEntryNameIgnoresDirectoryPrefix() {
        assertEquals("collection.anki2", AnkiImport.zipEntryName(setOf("deck1/collection.anki2")))
    }

    @Test fun zipEntryNameRejectsZstdAnki21b() {
        assertNull(AnkiImport.zipEntryName(setOf("collection.anki21b", "media")))
    }

    @Test fun zipEntryNameNullWhenNoCollection() {
        assertNull(AnkiImport.zipEntryName(setOf("media", "collection.anki21.zip")))
    }

    @Test fun hasZstdOnlyDetectedOnlyWhenNothingElseOpenable() {
        assertTrue(AnkiImport.hasZstdOnly(setOf("collection.anki21b", "media")))
        assertFalse(AnkiImport.hasZstdOnly(setOf("collection.anki21b", "collection.anki2")))
        assertFalse(AnkiImport.hasZstdOnly(setOf("collection.anki21")))
        assertFalse(AnkiImport.hasZstdOnly(setOf("media")))
    }

    // ---------------- importsLeft ----------------

    @Test fun importsLeftAllowsAllWhenRoomy() {
        assertEquals(100, AnkiImport.importsLeft(existing = 0, incoming = 100, max = 5000))
    }

    @Test fun importsLeftTruncatesToRemainingRoom() {
        assertEquals(50, AnkiImport.importsLeft(existing = 4950, incoming = 100, max = 5000))
    }

    @Test fun importsLeftZeroWhenFull() {
        assertEquals(0, AnkiImport.importsLeft(existing = 5000, incoming = 100, max = 5000))
    }

    @Test fun importsLeftNeverNegativeWhenOverfull() {
        assertEquals(0, AnkiImport.importsLeft(existing = 6000, incoming = 100, max = 5000))
    }

    @Test fun importsLeftZeroWhenNothingIncoming() {
        assertEquals(0, AnkiImport.importsLeft(existing = 10, incoming = 0, max = 5000))
    }

    @Test fun importsLeftAgreesWithKnowledgeCardMax() {
        assertEquals(20, AnkiImport.importsLeft(existing = KnowledgeCard.MAX - 20, incoming = 999, max = KnowledgeCard.MAX))
    }
}
