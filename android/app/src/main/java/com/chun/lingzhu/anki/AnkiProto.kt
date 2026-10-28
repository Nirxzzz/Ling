package com.chun.lingzhu

/**
 * Ling 2.7 Anki：**只读的 protobuf 最小解码器**。
 *
 * 新版 Anki（schema 18 / `collection.anki21b`）把笔记类型、模板、媒体索引从 JSON 换成了
 * protobuf blob 存在 `notetypes.config` / `templates.config` / `media` 里。这里不引 protobuf
 * 运行时（APK 体积 + 还需要 .proto 生成代码），只按**真实字段号**读需要的几个字段，
 * 其余字段按 wire type 跳过 —— 字段号取自 anki 26.9.3 的 `notetypes.proto` /
 * `import_export.proto` 描述符（已用真实导出包核对）：
 *
 * ```
 * message Notetype { int64 id=1; string name=2; int64 mtime_secs=3; sint32 usn=4;
 *                    Config config=7; repeated Field fields=8; repeated Template templates=9; }
 * message Notetype.Config { Kind kind=1; uint32 sort_field_idx=2; string css=3; ... }   // KIND_NORMAL=0, KIND_CLOZE=1
 * message Template.Config { string q_format=1; string a_format=2; ... }
 * message MediaEntries { repeated MediaEntry entries=1; }
 * message MediaEntry { string name=1; uint32 size=2; bytes sha1=3; uint32 legacy_zip_filename=255; }
 * message Meta { uint32 version=1; }
 * ```
 *
 * 为什么手写：这几条消息字段极少、且是**只读**用途；手写 60 行换掉一个代码生成链。
 * 遇到不认识/损坏的 wire type 一律抛 [MalformedProtoException]（坏包要拒，不能瞎读）。
 */
object AnkiProto {

    /** blob 不是合法 protobuf（截断 / 非法 wire type / 超长 length）。 */
    class MalformedProtoException(message: String) : Exception(message)

    /** `Notetype.Config` 里我们关心的两项。 */
    data class NotetypeConfig(val kind: Int, val css: String) {
        val cloze: Boolean get() = kind == KIND_CLOZE

        companion object {
            const val KIND_NORMAL = 0

            /** `Kind.KIND_CLOZE`。 */
            const val KIND_CLOZE = 1
        }
    }

    /** `Template.Config` 里的题面 / 答案模板。 */
    data class TemplateConfig(val front: String, val back: String)

    /** `MediaEntry`：媒体名 + 声明大小 + sha1（用于校验解出来的字节）。 */
    data class MediaEntry(val name: String, val size: Long, val sha1: ByteArray) {
        // ByteArray 在 data class 里的 equals 是引用比较，显式实现更符合直觉。
        override fun equals(other: Any?): Boolean =
            other is MediaEntry && other.name == name && other.size == size && other.sha1.contentEquals(sha1)

        override fun hashCode(): Int = (name.hashCode() * 31 + size.hashCode()) * 31 + sha1.contentHashCode()
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    /** 解析 `Notetype.Config`：只取 kind(1) 与 css(3)。 */
    fun notetypeConfig(bytes: ByteArray): NotetypeConfig {
        var kind = NotetypeConfig.KIND_NORMAL
        var css = ""
        val r = Reader(bytes)
        while (r.hasNext()) {
            val tag = r.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_VARINT) kind = r.readVarint().toInt() else r.skip(tag.wire)
                3 -> if (tag.wire == WIRE_LENGTH) css = r.readString() else r.skip(tag.wire)
                else -> r.skip(tag.wire)
            }
        }
        return NotetypeConfig(kind, css)
    }

    /** 解析 `Template.Config`：q_format(1) / a_format(2)。 */
    fun templateConfig(bytes: ByteArray): TemplateConfig {
        var front = ""
        var back = ""
        val r = Reader(bytes)
        while (r.hasNext()) {
            val tag = r.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) front = r.readString() else r.skip(tag.wire)
                2 -> if (tag.wire == WIRE_LENGTH) back = r.readString() else r.skip(tag.wire)
                else -> r.skip(tag.wire)
            }
        }
        return TemplateConfig(front, back)
    }

    /** 解析 `MediaEntries`：按 blob 里的顺序返回。 */
    fun mediaEntries(bytes: ByteArray): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        val r = Reader(bytes)
        while (r.hasNext()) {
            val tag = r.readTag()
            if (tag.field == 1 && tag.wire == WIRE_LENGTH) out += mediaEntry(r.readBytes()) else r.skip(tag.wire)
        }
        return out
    }

    /** 解析 `Meta.version`（0 = 老包，2 = 老版导出，3 = 新版导出）。 */
    fun metaVersion(bytes: ByteArray): Int {
        var version = 0
        val r = Reader(bytes)
        while (r.hasNext()) {
            val tag = r.readTag()
            if (tag.field == 1 && tag.wire == WIRE_VARINT) version = r.readVarint().toInt() else r.skip(tag.wire)
        }
        return version
    }

    private fun mediaEntry(bytes: ByteArray): MediaEntry {
        var name = ""
        var size = 0L
        var sha1 = ByteArray(0)
        val r = Reader(bytes)
        while (r.hasNext()) {
            val tag = r.readTag()
            when (tag.field) {
                1 -> if (tag.wire == WIRE_LENGTH) name = r.readString() else r.skip(tag.wire)
                2 -> if (tag.wire == WIRE_VARINT) size = r.readVarint() else r.skip(tag.wire)
                3 -> if (tag.wire == WIRE_LENGTH) sha1 = r.readBytes() else r.skip(tag.wire)
                else -> r.skip(tag.wire)
            }
        }
        return MediaEntry(name, size, sha1)
    }

    private class Tag(val field: Int, val wire: Int)

    /**
     * 顺序读取器。越界 / 非法 wire type 立刻抛 [MalformedProtoException]，
     * 不做「读到哪里算哪里」的容错 —— 坏 blob 必须被拒，否则会静默丢字段。
     */
    private class Reader(private val buf: ByteArray) {
        private var pos = 0

        fun hasNext(): Boolean = pos < buf.size

        fun readTag(): Tag {
            val raw = readVarint()
            val field = (raw ushr 3).toInt()
            val wire = (raw and 0x7L).toInt()
            if (field <= 0) throw MalformedProtoException("非法的字段号 $field")
            return Tag(field, wire)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= buf.size) throw MalformedProtoException("消息被截断（varint）")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw MalformedProtoException("varint 超过 64 位")
            }
        }

        fun readBytes(): ByteArray {
            val len = readVarint()
            if (len < 0 || len > buf.size - pos) throw MalformedProtoException("消息被截断（length=$len, 剩余=${buf.size - pos}）")
            val n = len.toInt()
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun readString(): String = String(readBytes(), Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> advance(8)
                WIRE_LENGTH -> advance(readVarint())
                WIRE_FIXED32 -> advance(4)
                else -> throw MalformedProtoException("未知的 wire type $wire")
            }
        }

        private fun advance(n: Long) {
            if (n < 0 || n > buf.size - pos) throw MalformedProtoException("消息被截断（skip $n, 剩余=${buf.size - pos}）")
            pos += n.toInt()
        }
    }
}
